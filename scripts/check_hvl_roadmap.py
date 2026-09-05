#!/usr/bin/env python3
"""Validate HVL roadmap metadata and documentation, not deferred HVL functionality."""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import re
import sys
from typing import Any

ROOT = Path(__file__).resolve().parents[1]
SURFACE = 'docs/roadmap/nodal-hvl-simulation-v0.1-surface.json'
PLAN = 'docs/roadmap/nodal-hvl-simulation-v0.1-plan.md'
ADR = 'docs/architecture/0027-hvl-execution-projection-capability-contract.md'
DOCUMENTS = [PLAN, 'docs/roadmap/nodal-development-todo.md',
             'docs/roadmap/dependent-productivity-and-verification-tracks-v0.1-plan.md',
             'docs/roadmap/generated-hdl-testbench-projections-v0.1-plan.md',
             'docs/architecture/0023-unified-hvl-native-sim-uvm-uvmms-architecture.md',
             'docs/architecture/0025-generated-procedural-hdl-testbench-projections.md', ADR]


def unique_object(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise ValueError(f'duplicate JSON key: {key}')
        result[key] = value
    return result


def load_surface(root: Path = ROOT) -> dict[str, Any]:
    return json.loads((root / SURFACE).read_text(encoding='utf-8'), object_pairs_hook=unique_object)


def validate_surface(data: dict[str, Any], root: Path = ROOT, *, check_docs: bool = True) -> list[str]:
    errors: list[str] = []

    def require(ok: bool, code: str, message: str) -> None:
        if not ok:
            errors.append(f'NODAL-HVL-{code}: {message}')

    require(data.get('schemaVersion') == '0.3', '001', 'expected capability contract revision 0.3')
    axes = data.get('capabilityAxes', {})
    execution = axes.get('executionClass', {})
    profiles = axes.get('projectionProfile', {})
    live = execution.get('live', {})
    portable = execution.get('portable', {})
    require(live.get('requiresQualifiedImplementationForEveryOperation') is True
            and live.get('automaticallySupportsGeneratedOnlyExtensions') is False,
            '002', 'live eligibility must be qualified, not implied by generation')
    require(live.get('projectionFailureInvalidatesLiveExecution') is False,
            '002', 'projection failure cannot invalidate otherwise-supported live execution')
    require(portable.get('captureIncludesTypedProfileExtensions') is True,
            '003', 'complete capture must include typed profile-extension operations')
    require(portable.get('silentOmissionAllowed') is False, '003', 'silent omission is forbidden')
    layers = data.get('semanticLayers', {})
    extension = set(layers.get('profileExtensionContract', []))
    require({'verifier', 'serialization-roundtrip', 'source-span', 'stable-identity',
             'qualified-mode-implementation', 'typed-operands-results', 'effects-and-scheduling'} <= extension,
            '003', 'extension capture contract is incomplete')
    require('plus declared typed profile-extension' in layers.get('portableMaterialization', ''),
            '003', 'portable materialization must not erase profile extensions')
    libs = data.get('libraryLayers', {})
    require(libs.get('commonSemanticLibrary', {}).get('mayDependOnProfileLibraries') is False
            and libs.get('universalTargetImplementationClassAllowed') is False,
            '004', 'common/profile library boundary violated')
    for name in ('verilogTbProjectionLibrary', 'uvmProjectionLibrary'):
        require(libs.get(name, {}).get('mayDependOn') == ['common-semantic-library'],
                '004', f'illegal profile library dependency: {name}')
    require(layers.get('crossLoweringAllowed') == {
        'uvmToProceduralHdlTbIr': False, 'verilogTbToVerificationSystemVerilogIr': False},
        '004', 'cross-lowering between generated IRs is forbidden')
    for name, ir in [('verilogTb', 'Procedural HDL Testbench IR'), ('uvm', 'Verification SystemVerilog IR')]:
        p = profiles.get(name, {})
        require(p.get('inheritsFrom') == [] and p.get('generatedLanguageIr') == ir,
                '004', f'wrong sibling profile/IR contract: {name}')

    definitions = data.get('capabilityDefinitions', {})
    declared = [v.get('id') for group in axes.values() for v in group.values()]
    require(len(declared) == len(set(declared)), '005', 'duplicate capability ID')
    for group in axes.values():
        for value in group.values():
            name = value.get('id')
            require(name in definitions, '005', f'unresolved capability: {name}')
            require(definitions.get(name, {}).get('requires', []) == value.get('requires', []),
                    '005', f'capability dependency declaration drift: {name}')
    for name, value in definitions.items():
        for dep in value.get('requires', []):
            require(dep in definitions, '005', f'{name} requires unknown capability {dep}')

    nodes = data.get('dependencyNodes', {})
    for name, node in nodes.items():
        require(isinstance(node.get('requires'), list), '006', f'{name} requires must be a list')
        for dep in node.get('requires', []):
            require(dep in nodes, '006', f'{name} requires unresolved dependency {dep}')
    for name, value in definitions.items():
        if 'provider' in value:
            require(value['provider'] in nodes, '006', f'unknown provider for {name}')

    def graph_cycles(graph: dict[str, Any], code: str) -> None:
        visited: set[str] = set()
        active: set[str] = set()

        def visit(name: str) -> None:
            if name in active:
                require(False, code, f'dependency cycle at {name}')
                return
            if name in visited or name not in graph:
                return
            active.add(name)
            for dep in graph[name].get('requires', []):
                visit(dep)
            active.remove(name)
            visited.add(name)

        for name in graph:
            visit(name)

    graph_cycles(nodes, '007')
    graph_cycles(definitions, '007')

    def ancestors(name: str) -> set[str]:
        found: set[str] = set()
        todo = list(nodes.get(name, {}).get('requires', []))
        while todo:
            dep = todo.pop()
            if dep not in found:
                found.add(dep)
                todo.extend(nodes.get(dep, {}).get('requires', []))
        return found

    ids: list[str] = []
    for workstream in data.get('workstreams', []):
        ids.extend(workstream.get('increments', []))
        for dep in workstream.get('blockedBy', []):
            require(dep in nodes, '006', f'unresolved workstream dependency: {dep}')
        for key in ('releaseGate', 'openSourceReleaseGate'):
            if key in workstream:
                require(workstream[key] in nodes, '006', f'unknown gate: {workstream[key]}')
        for gate in workstream.get('profileReleaseGates', []):
            require(gate in nodes, '006', f'unknown profile release: {gate}')
    require(len(ids) == len(set(ids)), '008', 'duplicate work item IDs')
    for item in ids:
        require(nodes.get(item, {}).get('kind') == 'item', '008', f'missing work item binding: {item}')
        require('foundation.complete' in ancestors(item), '009', f'{item} bypasses Foundation')
    for capability, gate in data.get('profileReleaseGates', {}).items():
        require(capability in definitions and nodes.get(gate, {}).get('kind') == 'release',
                '006', f'unresolved capability release: {capability} -> {gate}')

    independence = {
        'release.live': ('CAP-', 'VTB-', 'UVM-', 'AMSP-', 'XPAR-'),
        'release.analog-live': ('MS-', 'CAP-', 'VTB-', 'UVM-', 'AMSP-', 'XPAR-', 'ANA-08'),
        'release.mixed-live': ('CAP-', 'VTB-', 'UVM-', 'AMSP-', 'XPAR-', 'MS-07', 'MS-08'),
        'release.capture': ('VTB-', 'UVM-', 'AMSP-', 'XPAR-'),
        'release.vtb': ('UVM-', 'AMSP-', 'XPAR-'),
        'release.uvm': ('VTB-', 'AMSP-', 'XPAR-'),
        'release.open-ams-harness': ('UVM-', 'XPAR-', 'AMSP-04', 'AMSP-05', 'AMSP-06', 'AMSP-07',
                                     'evidence.verilog-ams', 'evidence.uvm-ms'),
        'release.verilog-ams-tb': ('UVM-', 'XPAR-', 'AMSP-03', 'AMSP-04', 'AMSP-05', 'AMSP-06', 'AMSP-07',
                                 'evidence.open-ams', 'evidence.uvm-ms'),
        'release.uvm-ms': ('VTB-', 'XPAR-', 'AMSP-02', 'AMSP-03', 'AMSP-05', 'AMSP-06', 'AMSP-07',
                          'evidence.open-ams', 'evidence.verilog-ams'),
    }
    for gate, excluded in independence.items():
        require(gate in nodes, '010', f'missing independent release gate: {gate}')
        bad = sorted(dep for dep in ancestors(gate) if dep.startswith(excluded))
        require(not bad, '010', f'{gate} is coupled to forbidden prerequisites: {bad}')
    require('UVM-07' in ancestors('release.uvm'), '011', 'executable UVM needs actual tool qualification')
    for gate, evidence in [('release.open-ams-harness', 'evidence.open-ams-tool'),
                           ('release.verilog-ams-tb', 'evidence.verilog-ams-tool'),
                           ('release.uvm-ms', 'evidence.uvm-ms-tool')]:
        require(evidence in ancestors(gate), '011', f'{gate} lacks execution qualification')

    expected_cases = {
        'host-reference-model': (True, False, False),
        'shared-transaction-check': (True, True, True),
        'uvm-factory-without-live-implementation': (False, False, True),
        'vtb-hook-without-other-implementation': (False, True, False),
    }
    cases = {c['id']: (c.get('live'), c.get('verilogTb'), c.get('uvm')) for c in data.get('qualificationCases', [])}
    require(cases == expected_cases, '012', 'required capability eligibility cases drifted')
    require(data.get('qualificationCasesStatus') == 'required-future-fixtures-not-implemented',
            '012', 'roadmap cases must not be represented as implemented HVL tests')
    require(set(data.get('synchronizedDocuments', [])) == set(DOCUMENTS), '013', 'incomplete documentation synchronization inventory')
    if check_docs:
        docs: dict[str, str] = {}
        for path in DOCUMENTS:
            file = root / path
            require(file.is_file(), '013', f'missing synchronized document: {path}')
            if file.is_file():
                docs[path] = file.read_text(encoding='utf-8')
                if path != ADR:
                    require(Path(ADR).name in docs[path], '013', f'missing authoritative amendment link: {path}')
        plan = docs.get(PLAN, '')
        require('**Revision:** 0.3' in plan, '013', 'plan/surface revision mismatch')
        require('strictly richer than or equal to every generated projection' not in plan,
                '002', 'obsolete universal-live-superset requirement')
        declared_items = re.findall(r'^- \[[ x]\] \*\*((?:LIVE|ANA|MS|CAP|VTB|UVM|AMSP|XPAR)-\d+) —', plan, re.M)
        require(sorted(declared_items) == sorted(ids), '008', 'Markdown/JSON work item inventories differ')
        for name, node in nodes.items():
            path = node.get('source', '')
            file = root / path
            require(file.is_file(), '013', f'{name} has missing source binding')
            if file.is_file():
                text = docs.get(path)
                if text is None:
                    text = file.read_text(encoding='utf-8')
                require(bool(node.get('anchor')) and node.get('anchor', '') in text,
                        '013', f'{name} source anchor is unresolved')
    return errors


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, default=ROOT)
    args = parser.parse_args()
    try:
        surface = load_surface(args.root)
        errors = validate_surface(surface, args.root)
    except (OSError, ValueError, KeyError, TypeError, AttributeError) as exc:
        print(f'NODAL-HVL-000: invalid roadmap input: {exc}', file=sys.stderr)
        return 1
    for error in errors:
        print(error, file=sys.stderr)
    if errors:
        return 1
    print(f'HVL roadmap consistency passed: {len(surface["dependencyNodes"])} resolved nodes; '
          f'{len(surface["profileReleaseGates"])} capability release bindings; documentation only.')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
