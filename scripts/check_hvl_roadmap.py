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
PROJECTION_PLAN = 'docs/roadmap/generated-hdl-testbench-projections-v0.1-plan.md'
DOCUMENTS = [PLAN, 'docs/roadmap/nodal-development-todo.md',
             'docs/roadmap/dependent-productivity-and-verification-tracks-v0.1-plan.md',
             PROJECTION_PLAN,
             'docs/architecture/0023-unified-hvl-native-sim-uvm-uvmms-architecture.md',
             'docs/architecture/0025-generated-procedural-hdl-testbench-projections.md',
             'docs/architecture/0026-native-digital-simulator-adapter-architecture.md',
             'docs/roadmap/native-digital-simulator-adapters-v0.1-plan.md', ADR]
EXPECTED_RELEASES = {
    'nodal.hvl.live': 'release.live',
    'nodal.hvl.portable.core': 'release.capture',
    'nodal.hvl.projection.verilog_tb': 'release.vtb',
    'nodal.hvl.projection.uvm': 'release.uvm',
    'nodal.hvl.projection.verilog_ams_tb': 'release.verilog-ams-tb',
    'nodal.hvl.projection.open_ams_harness': 'release.open-ams-harness',
    'nodal.hvl.projection.uvm_ms': 'release.uvm-ms',
}


# Reference-only text is checked in full so obsolete prerequisites cannot coexist
# with valid ledger links. Dependency edges remain exclusively in the JSON graph.
# Whitespace-only reflow is allowed; changing the reference contract is explicit.
PROJECTION_DEPENDENCY_BODY = """All dependent implementation remains blocked by the complete [Foundation completion barrier](nodal-development-todo.md#foundation-completion-barrier). This document does not define a second prerequisite list.

Use [ADR 0027](../architecture/0027-hvl-execution-projection-capability-contract.md) for capability and release independence and the [resolved dependency and independent release ledger](nodal-hvl-simulation-v0.1-plan.md#resolved-dependency-and-independent-release-ledger) for prerequisites. Its machine-readable source is [`dependencyNodes` and `profileReleaseGates`](nodal-hvl-simulation-v0.1-surface.json).

Umbrella increment numbers identify ownership, not an implicit execution order. Evaluate the selected item's explicit dependencies in that ledger; neither full live-track completion nor an unrelated generated profile is an implied prerequisite. Shared identities are provided by common contracts, not by completing a sibling backend.

Foundation Increment 152 acceptance remains historical evidence. This correction neither closes Foundation Increments 147-149 nor changes the dependency graph."""


def validate_projection_dependencies(text: str) -> list[str]:
    """Reject missing, duplicate or divergent prerequisite prose in the secondary plan."""
    error = (f'NODAL-HVL-015: {PROJECTION_PLAN}: Implementation dependencies must '
             'reference the authoritative ledger without a second prerequisite list')
    headings = list(re.finditer(r'^##[ \t]+([^\n]+)', text, re.M))
    positions = [i for i, heading in enumerate(headings)
                 if heading[1].strip() == 'Implementation dependencies']
    if len(positions) != 1:
        return [error]
    index = positions[0]
    if index + 1 == len(headings) or headings[index + 1][1].strip() != 'Completion claim':
        return [error]
    section = text[headings[index].end():headings[index + 1].start()]
    if ' '.join(section.split()) != ' '.join(PROJECTION_DEPENDENCY_BODY.split()):
        return [error]
    return []


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
    bindings = data.get('profileReleaseGates', {})
    expected_capabilities = {live.get('id')} | {profile.get('id') for profile in profiles.values()}
    require(set(bindings) == expected_capabilities, '006', 'missing or extraneous capability release binding')
    require(bindings == EXPECTED_RELEASES, '006', 'capability release mapping differs from the revision 0.3 contract')
    for capability, gate in bindings.items():
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
        'release.parity-vtb': ('UVM-', 'ANA-', 'MS-', 'AMSP-', 'XPAR-03', 'XPAR-04', 'XPAR-05'),
        'release.parity-uvm': ('VTB-', 'ANA-', 'MS-', 'AMSP-', 'XPAR-02', 'XPAR-04', 'XPAR-05'),
        'release.parity-open-ams': ('VTB-', 'UVM-', 'AMSP-02', 'AMSP-04', 'AMSP-05', 'AMSP-06', 'AMSP-07',
                                  'XPAR-02', 'XPAR-03', 'XPAR-04', 'XPAR-05', 'evidence.verilog-ams',
                                  'evidence.uvm-ms', 'evidence.parity.verilog-ams', 'evidence.parity.uvm-ms'),
        'release.parity-verilog-ams': ('VTB-', 'UVM-', 'AMSP-03', 'AMSP-04', 'AMSP-05', 'AMSP-06', 'AMSP-07',
                                     'XPAR-02', 'XPAR-03', 'XPAR-04', 'XPAR-05', 'evidence.open-ams',
                                     'evidence.uvm-ms', 'evidence.parity.open-ams', 'evidence.parity.uvm-ms'),
        'release.parity-uvm-ms': ('VTB-', 'AMSP-02', 'AMSP-03', 'AMSP-05', 'AMSP-06', 'AMSP-07',
                                'XPAR-02', 'XPAR-03', 'XPAR-04', 'XPAR-05', 'evidence.open-ams',
                                'evidence.verilog-ams', 'evidence.parity.open-ams', 'evidence.parity.verilog-ams'),
    }
    aggregate_gates = ('release.ams-aggregate', 'release.parity-aggregate', 'release.all-qualification')
    for gate, excluded in independence.items():
        require(gate in nodes, '010', f'missing independent release gate: {gate}')
        bad = sorted(dep for dep in ancestors(gate) if dep.startswith(excluded + aggregate_gates))
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
        errors.extend(validate_projection_dependencies(docs.get(PROJECTION_PLAN, '')))
        expected_owners = {6: 'CAP-01', 7: 'VTB-04', 8: 'UVM-01', 9: 'UVM-07',
                           10: 'XPAR-01', 11: 'VTB-06', 12: 'LIVE-08'}
        for document in ('docs/roadmap/nodal-development-todo.md',
                         'docs/roadmap/dependent-productivity-and-verification-tracks-v0.1-plan.md'):
            ownership: dict[int, list[str]] = {}
            current: int | None = None
            for line in docs.get(document, '').splitlines():
                match = re.match(r'^- \[[ x]\] \*\*Digital Verification Increment (\d+) —', line)
                if match:
                    current = int(match[1])
                    ownership.setdefault(current, [])
                elif line.startswith(('- [', '#', '---')):
                    current = None
                elif current is not None and line.startswith('  - Ownership:'):
                    ownership[current].append(line)
            for number, owner in expected_owners.items():
                require(any(f'Ownership: {owner}' in note for note in ownership.get(number, [])),
                        '014', f'{document}: Digital Verification {number} has missing or misplaced {owner} ownership')
        for path, text in docs.items():
            require('\n---\n  - Ownership:' not in text,
                    '014', f'{path}: ownership note outside an increment section')
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
