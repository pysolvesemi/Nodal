#!/usr/bin/env python3
"""One-shot, exact-input roadmap maintenance; removed before the PR is opened."""
from pathlib import Path
import hashlib
import json
import re

ROOT = Path(__file__).resolve().parents[1]
R = 'docs/roadmap/'
A = 'docs/architecture/'
PLAN = R + 'nodal-hvl-simulation-v0.1-plan.md'
SURFACE = R + 'nodal-hvl-simulation-v0.1-surface.json'
TODO = R + 'nodal-development-todo.md'
DEP = R + 'dependent-productivity-and-verification-tracks-v0.1-plan.md'
GEN = R + 'generated-hdl-testbench-projections-v0.1-plan.md'
ADR23 = A + '0023-unified-hvl-native-sim-uvm-uvmms-architecture.md'
ADR25 = A + '0025-generated-procedural-hdl-testbench-projections.md'
ADR27 = A + '0027-hvl-execution-projection-capability-contract.md'
CI = '.github/workflows/ci.yml'
expected = {
    PLAN: 'fe6c30bdf1ceada1b4cddf03619dcb9cf93b0870',
    SURFACE: 'c9c080b0de6a36f6e9c9826805fa5f2eb0460a4d',
    TODO: '95b155c83553e1d765ba2cb547a7364d0453e1d3',
    DEP: 'f5e3aad42d8609af606fb30e64ac096e87b1a5d1',
    GEN: '9b9a66ab7a6cdad718ab8e755ea02e032ae423c2',
    ADR23: '9410a44543b8465358026af08860253bb9ffeffd',
    ADR25: '8c47e0b81b6b9dee63ae1fe99279bcdfbee05af1',
    CI: '97e73a21aefbd73dbd3bec8924ec38dbc77051e7',
}
texts = {}
for path, sha in expected.items():
    raw = (ROOT / path).read_bytes()
    actual = hashlib.sha1(b'blob ' + str(len(raw)).encode() + b'\0' + raw).hexdigest()
    if actual != sha:
        raise SystemExit(f'Input changed: {path}: {actual} != {sha}')
    texts[path] = raw.decode('utf-8')
original_checkboxes = {p: re.findall(r'^- \[([ x])\] \*\*(.*?)\*\*', texts[p], re.M) for p in (TODO, DEP)}


def change(path, old, new):
    if texts[path].count(old) != 1:
        raise RuntimeError(f'Expected one replacement in {path}: {old[:90]!r}')
    texts[path] = texts[path].replace(old, new, 1)


def add_increment(path, prefix, bullets):
    pattern = r'(^- \[[ x]\] \*\*' + re.escape(prefix) + r' —[^\n]*\n)(.*?)(?=\n- \[[ x]\] \*\*|\n#{1,3} |\Z)'
    match = re.search(pattern, texts[path], re.M | re.S)
    if match is None:
        raise RuntimeError(f'Missing increment: {path}: {prefix}')
    end = match.end()
    texts[path] = texts[path][:end].rstrip() + '\n' + bullets.rstrip() + '\n\n' + texts[path][end:].lstrip('\n')


contract = '''## Capability consistency contract — revision 0.3

[ADR 0027](../architecture/0027-hvl-execution-projection-capability-contract.md) amends the earlier single-source wording. The detailed [HVL plan](nodal-hvl-simulation-v0.1-plan.md) and its [machine-readable surface](nodal-hvl-simulation-v0.1-surface.json) define the same contract.

- Execution class (`live` or `capturable`) and generated-profile eligibility are independent. Capturable does not mean universally projectable.
- A captured program contains common Verification Semantic IR **plus declared typed profile-extension operations**, all with serialization, verification, source locations, capability requirements and stable identities. Portable Core itself stays target-neutral.
- A live test may call a captured component only when every required operation has a qualified live implementation. UVM-only or Verilog-TB-only operations are not automatically live-executable.
- Generated-profile limitations must never restrict otherwise-supported live Nodal HVL. Live is the richer ordinary Scala experience, not a required emulator or strict superset of every target-specific methodology.
- Verilog-TB and UVM are sibling profiles with separate extension libraries, generated-language IRs, validators and release gates. Neither depends on or lowers through the other. Common libraries never import profile implementation libraries.
- Share common test intent; permit explicit profile-specific wrappers and packages. A package may support live only, VTB only, UVM only, several modes, or no runnable mode yet. Every claimed mode needs positive evidence; unsupported/inapplicable is never counted as passed.
- Compare only the declared common semantic intersection. UVM factory/phases/TLM and VTB module/task extensions are separately tested, not flattened into a lowest common denominator.
- `CAP`, `VTB`, `UVM`, `AMSP`, and `XPAR` are the current workstreams. `PORT` is a historical alias only. Numbering is ownership, not an implicit sequence of dependencies.
- Full verification/runtime/generator/VIP implementation remains blocked by the complete Foundation barrier. This roadmap refinement does not close Foundation 147, 148 or 149 or revise historical Increment 152 acceptance evidence.

'''

change(PLAN, '# Nodal HVL live simulation and projection capability v0.2 plan', '# Nodal HVL live simulation and projection capability v0.3 plan')
change(PLAN, '**Revision:** 0.2', '**Revision:** 0.3')
change(PLAN, '## Global dependency rule', contract + '## Global dependency rule')
change(PLAN, '3. A live test may call portable components.', '3. A live test may call portable components only when all required common and profile operations have qualified live implementations.')
change(PLAN, '5. Requesting a projection may reject a live-only or incompatible-profile test, but that rejection must not make the test invalid for live execution.', '5. Projection rejection never invalidates an otherwise-supported live test. A generated-only profile extension does not itself establish live eligibility.')
change(PLAN, '- Portable Core behavior is fully materialized as target-neutral Verification Semantic IR before projection.', '- The complete captured program is common Verification Semantic IR plus declared typed profile-extension operations. Every selected operation is serialized and verified before projection; Portable Core alone does not contain UVM or Verilog-TB methodology mechanisms.')
change(PLAN, '7. live simulation remains strictly richer than or equal to every generated projection rather than being limited by them;', '7. generated-profile limitations never restrict otherwise-supported live execution, while generated-only extensions require separate live qualification;')
add_increment(PLAN, 'CAP-02', '  - Capture profile-extension types, operands, effects, symbol references and control flow as first-class namespaced operations; preserve unknown extensions for inspection but reject execution/lowering without a matching versioned verifier and implementation.\n  - Cover extension serialization, round trips, source maps, dependency/effect checks and unsupported-profile diagnostics. No opaque Scala callbacks or raw generated-code strings substitute for captured semantics.')
add_increment(PLAN, 'CAP-03', '  - Precompute only behavior independent of future DUT observations, or explicitly selected replay artifacts. A trace from one run is not an equivalent replacement for a reactive test across other DUT behaviors; runtime-assisted output must declare its companion dependency.')
add_increment(PLAN, 'UVM-07', '  - Qualify at least one selected capable simulator and locked reference library before claiming an executable UVM release. Additional vendor profiles are optional; lack of such qualification leaves UVM release open, but cannot block live or VTB release. No open-source UVM support is assumed from parsing alone.')
add_increment(PLAN, 'AMSP-07', '  - Close each profile independently through `release.verilog-ams-tb`, `release.open-ams-harness`, and `release.uvm-ms` below. This checkbox is aggregate completion, not a prerequisite for any individual profile release.')
add_increment(PLAN, 'XPAR-04', '  - Qualify each selected pair independently through `evidence.parity.open-ams`, `evidence.parity.verilog-ams`, and `evidence.parity.uvm-ms`. Aggregate XPAR completion cannot delay an individual profile release.')
change(PLAN, '- Commercial live adapters and generated commercial profiles are deferred optional profiles and do not block open-source live or VTB releases.', '- Commercial breadth is deferred and cannot block open-source live, VTB or open-harness releases. An executable UVM, Verilog-AMS-TB or UVM-MS release still requires an actual qualified simulator for that profile; source emission alone is insufficient.')

for path in (TODO, DEP, GEN):
    marker = '## Phase 10 — Foundation comments, FPGA-readiness, and HVL verification-readiness' if path == TODO else '## Global dependency rule' if path == DEP else '## Feasibility decision'
    change(path, marker, contract + marker)
change(DEP, '**Revision:** 0.4', '**Revision:** 0.5')
change(DEP, '**Updated:** 2026-09-04', '**Updated:** 2026-09-05')
change(DEP, 'The normative detailed sequencing is split into ANA analog-live, MS mixed-signal-live, and PORT portable/generated lanes', 'The normative detailed sequencing is split into LIVE common runtime, ANA analog-live, MS mixed-signal-live, CAP common capture, VTB/UVM/AMSP generated profiles, and XPAR common-subset parity lanes')
change(DEP, 'Its selected behavior is fully captured into Verification Semantic IR and may be projected', 'Its complete selected behavior is captured as common Verification Semantic IR plus declared typed profile-extension operations and, only where the selected profile qualifies, may be projected')
change(DEP, 'Run each applicable **portable/capturable** Nodal HVL test through four explicit modes: native Verilator, native Icarus, generated standalone portable Verilog testbench, and generated UVM.', 'Run each common-subset test only through its explicitly qualified modes: native Verilator, native Icarus, generated Verilog-TB, and/or generated UVM. Capturability does not require eligibility for all four; UVM-only and VTB-only extensions stay in their own conformance suites.')
change(DEP, 'Compare each applicable portable/capturable Nodal HVL environment across live open execution, the open AMS harness, generated Verilog-AMS testbench runs on capable tools, and generated UVM-MS.', 'Compare only the explicitly shared semantic intersection for each qualified pair among live open execution, open AMS harness, Verilog-AMS testbench, and UVM-MS. A profile-specific environment has no parity obligation to an unsupported mode.')
change(DEP, 'Author representative protocol VIP only in Nodal HVL—at minimum `Valid`/`Stream`, APB, and AXI4-Lite or another approved protocol—and generate native BFM/agents, portable Verilog testbench collateral where expressible, and UVM VIP.', 'Author representative shared protocol intent in Nodal HVL—at minimum `Valid`/`Stream`, APB, and AXI4-Lite or another approved protocol—with explicit live, VTB, and UVM packages or wrappers. Reuse common intent without requiring every package or extension to support every profile.')
change(DEP, 'Publish separate supported live-HVL and portable/capturable projection capability matrices plus a reusable VIP author conformance kit.', 'Publish independent live, CAP, VTB, UVM, and profile-pair capability/release matrices plus a reusable VIP author conformance kit. This umbrella completion does not delay any independently qualified release.')
change(DEP, 'Author representative mixed-signal VIP only in Nodal HVL, then generate native/open-harness behavior, Verilog-AMS collateral where supported, and UVM-MS forms from its portable subset.', 'Share representative mixed-signal VIP intent in Nodal HVL and supply explicitly qualified live, open-harness, Verilog-AMS, and/or UVM-MS packages. Profile-specific wrappers are permitted; no package must support all profiles.')
for path in (TODO, DEP):
    add_increment(path, 'Foundation Increment 147', '  - Apply ADR 0027 and the revision 0.3 capability contract: execution eligibility is independent of projection eligibility; capture the complete common-plus-typed-extension program without requiring arbitrary live Scala capture.\n  - Freeze compile-positive/negative candidates for live-only, VTB-only, UVM-only and shared components, including live calls to unsupported generated-only extensions. Retain this architecture gate as incomplete until its actual acceptance evidence exists.')
    add_increment(path, 'Foundation Increment 148', '  - Keep otherwise-supported live Scala execution independent of generated-profile limits. A profile-specific operation is live-callable only through an explicitly qualified live implementation; do not require a universal UVM emulator.')
    add_increment(path, 'Foundation Increment 149', '  - Freeze independent UVM/UVM-MS extension namespaces, typed extension serialization/verifiers, per-profile applicability and simulator qualification. Never route UVM through Procedural HDL Testbench IR or make VTB completion a UVM prerequisite.')
    add_increment(path, 'Digital Verification Increment 6', '  - Ownership: CAP-01 through CAP-05 and VTB-01 through VTB-03; use the independent VTB capability set, library and generated-language IR. No UVM implementation prerequisite.')
    add_increment(path, 'Digital Verification Increment 7', '  - Ownership: VTB-04 through VTB-07. Close the VTB release independently of UVM, AMSP and aggregate XPAR completion.')
    add_increment(path, 'Digital Verification Increment 8', '  - Ownership: UVM-01 through UVM-06 after CAP-05, not after VTB. Capture explicit UVM-only extensions with separate typed operations and library boundaries.')
    add_increment(path, 'Digital Verification Increment 9', '  - Ownership: UVM-07 for generated methodology qualification; direct commercial live adapters remain separate. Require an actual qualified simulator for an executable UVM claim, not every vendor family.')
    add_increment(path, 'Digital Verification Increment 10', '  - Ownership: XPAR-01 through XPAR-03 and XPAR-05. Compare only qualified common semantic intersections; generated-only methodology behavior is not a live or sibling-profile parity requirement.')
    add_increment(path, 'Digital Verification Increment 11', '  - Ownership: VTB-06 and UVM-08 in their independent lanes. Common semantic VIP must not import either profile library; profile-specific wrappers and single-profile packages are valid.')
    add_increment(path, 'Digital Verification Increment 12', '  - Ownership: LIVE-08, CAP-05, VTB-07, UVM-09 and applicable XPAR gates. Track independent releases; aggregate track completion is not a release prerequisite for an individual lane.')

change(GEN, '**Date:** 2026-08-26', '**Date:** 2026-08-26\n**Amended:** 2026-09-05 (capability consistency revision 0.3; original Increment 152 evidence retained)')
change(GEN, 'Reserve a minimal Foundation seam now so a future Nodal HVL environment can be used in all of these ways without testbench-source duplication:', 'Reserve Foundation seams for reusable common verification intent plus explicit profile-specific extensions. Each environment supports only its qualified modes; the available independent modes are:')
change(GEN, 'Compare the same Nodal tests across all three projections, including deterministic replay, transaction ordering, checks, scoreboards, register behavior, coverage intent, termination, and source-level failure identities.', 'Compare only the qualified common semantic intersection through XPAR: replay, transaction ordering, checks, scoreboards, register behavior, common coverage intent, termination, and source-level failure identities. UVM-only or VTB-only operations have no automatic live or sibling-profile implementation obligation.')
change(GEN, 'Generate native BFM/agent behavior, portable Verilog testbench collateral where expressible, and UVM VIP from one Nodal VIP source.', 'Share common VIP intent and provide separate qualified live, VTB and/or UVM wrappers/libraries. A single-profile package is valid; no universal target implementation class is required.')
change(GEN, 'Retain the current UVM generation scope, renumbered after direct Verilog support.', 'Implement UVM-01 through UVM-06 after CAP-05. Numeric placement after VTB is not a dependency: UVM uses its own extension library and Verification SystemVerilog IR.')
change(GEN, 'Add procedural-testbench generation/runtime scale and publish an HVL/native/Verilog-testbench/UVM/tool capability matrix and conformance kit.', 'Publish independent LIVE, CAP, VTB and UVM scale/release matrices and profile-pair XPAR evidence. Aggregate track completion cannot block an independently qualified release.')

for path in (ADR23, ADR25):
    note = '''## Amendment — execution and projection eligibility (2026-09-05)

[ADR 0027](0027-hvl-execution-projection-capability-contract.md) is authoritative for execution classes, complete common-plus-extension capture, sibling profile libraries/IRs, live eligibility, pairwise parity and independent release gates. Earlier single-source statements below apply to qualified shared semantics, not arbitrary Scala or every target-specific methodology. Historical acceptance and Increment 152 closure evidence remain intact; the amendment does not claim Foundation 147-149 implementation or acceptance is complete.

'''
    change(path, '## Context', note + '## Context')
change(ADR23, '`nodal sim` executes the Verification IR through the Nodal simulation runtime.', '`nodal sim` executes live host-side Nodal HVL through the Nodal runtime; qualified captured components may also execute through that runtime. Arbitrary Scala control does not require complete static IR capture, and generated-only operations require separate live qualification.')
change(ADR23, '**Author verification once in Nodal HVL, preserve it in a target-neutral Verification IR, execute it through the native simulation runtime or project it to UVM/UVM-MS through capability-checked backends.**', '**Share verification intent in Nodal HVL. Run live code directly, or capture common semantics plus declared typed profile extensions and select only qualified execution/projection profiles.**')
change(ADR25, '**Author verification once in Nodal HVL, preserve it in the canonical Verification Semantic IR, and select among native execution, capability-limited procedural HDL testbench generation, UVM generation, or UVM-MS generation without changing semantic ownership.**', '**Share qualified verification intent through common Verification Semantic IR plus declared typed profile extensions; select live, procedural HDL, UVM or UVM-MS only where every required operation is supported. Capturable does not mean universally portable.**')

texts[ADR27] = '''# ADR 0027: Separate HVL execution eligibility from generated-profile capabilities

- **Status:** Accepted (roadmap and architecture refinement only)
- **Date:** 2026-09-05
- **Amends:** ADR 0023 and ADR 0025
- **Scope:** HVL capability boundaries, complete capture, future library packaging, generated IR ownership, dependency metadata and release qualification

## Decision

Live Nodal HVL remains the default host-side Scala experience. Generated testbench restrictions never weaken otherwise-supported live operations. This is not a promise that live execution implements every generated-only Verilog-TB or UVM methodology operation.

A live caller may use a captured component only when all its required common and profile-specific operations have qualified live implementations. Similarly, successful capture does not imply Verilog-TB, UVM, UVM-MS or standalone eligibility. Rejection of one profile does not invalidate a different qualified profile.

The complete captured program is **common Verification Semantic IR plus declared typed profile-extension operations**. Extensions are versioned first-class operations with types, symbols, effects, scheduling requirements, source spans, stable identities, verifiers and serialization/round-trip tests. Portable Core stays target-neutral. Unknown extensions may be preserved for inspection, but execution and lowering fail without matching qualified support. Opaque callbacks, generated source strings, and a trace from one live run are not substitutes for complete capture.

## Library and IR boundaries

Common transaction, protocol, check, scoreboard, coverage and result semantics do not depend on profile libraries. Independent VTB and UVM libraries depend on the common layer. Explicit target-specific wrappers and single-profile VIP packages are valid; duplicating common intent is discouraged, not solved through a universal base implementation class.

VTB lowers through Procedural HDL Testbench IR. UVM lowers through Verification SystemVerilog IR. Neither lowers through or inherits from the other. Shared low-level utilities are allowed when they introduce no target-implementation dependency. UVM-MS may compose qualified UVM capabilities plus AMS capabilities without inheriting procedural Verilog limits.

## Qualification examples

These are required future conformance cases, not implemented language APIs:

| Case | Live | VTB | UVM |
| --- | --- | --- | --- |
| Host Scala reference model using an external library | Qualified live fixture | Rejected | Rejected |
| Common deterministic transaction and check | Qualified | Qualified | Qualified |
| Captured UVM factory override without a live implementation | Rejected | Rejected | Qualified |
| Captured VTB-specific module/task hook without another implementation | Rejected | Qualified | Rejected |

Every accepted mode still depends on the selected simulator's declared capabilities. Add negative cases for a live call to the UVM-only component, extension loss in serialization, unsupported methods hidden behind a wrapper, reactive-test replay substitution, and accidental common-to-profile dependencies.

## Replay and parity

Precomputation is legal only when it preserves the selected test's semantics. Future DUT-dependent decisions must remain reactive in the target or an explicitly declared companion runtime. A recorded trace is an explicit replay artifact, not proof that the original reactive test is portable to different DUT behavior.

XPAR compares the explicit shared semantic intersection for each qualified profile pair. A target-specific feature outside that intersection is inapplicable, not passed and not a parity failure. Claiming a shared capability and then failing its comparison is a failure; an exclusion cannot hide a supported-case regression.

## Dependencies and releases

The [HVL roadmap](../roadmap/nodal-hvl-simulation-v0.1-plan.md) and [surface](../roadmap/nodal-hvl-simulation-v0.1-surface.json) define resolved dependency nodes and separate gates for live, analog-live, mixed-signal-live, capture, VTB, UVM, Verilog-AMS TB, open AMS harness, UVM-MS and profile-pair parity.

Commercial breadth and aggregate track completion do not block open live, VTB or open-harness releases. Claiming an executable UVM/Verilog-AMS/UVM-MS release still requires actual simulator qualification for that selected profile. Source emission, a skipped licensed run, or a missing tool never counts as execution evidence.

## Foundation and history

All dependent implementation remains blocked until every Foundation increment is accepted with evidence. Foundation 147-149 remain responsible for detailed API and semantic acceptance, including complete extension capture and legality fixtures; this amendment does not close them. Existing Increment 152 evidence remains historical proof of its earlier architecture scope, not proof of newly added implementation.

The [approved consistency design gate](../design-gates/NodalHvlCapabilityConsistency-DG-v0.1.md) records this roadmap-only decision. The repository checker validates documentation/metadata consistency, not future HVL functionality.
'''
texts['docs/design-gates/NodalHvlCapabilityConsistency-DG-v0.1.md'] = '''# Nodal HVL Capability Consistency — DG v0.1

**Status:** Approved — roadmap and architecture refinement only  
**Date:** 2026-09-05  
**Scope:** Foundation Increment 147 roadmap refinement; future core/library packaging and HVL capability boundaries  
**Approval:** User requested completion of the reviewed roadmap inconsistencies.  
**Architecture:** [ADR 0027](../architecture/0027-hvl-execution-projection-capability-contract.md)

## Approved decisions

Retain two execution classes, independent profile capability sets, complete common-plus-typed-extension capture, independent live eligibility, one-way common/profile library dependencies, separate VTB/UVM IRs and implementations, common-subset-only parity, resolved dependency references and independent per-profile releases.

Generated-target restrictions cannot weaken otherwise-supported live Scala. Generated-only UVM and VTB extensions are not automatically live-callable. Actual simulator execution evidence is required before an executable generated profile is qualified; optional vendor breadth is distinct from that minimum.

## Change boundary

This approves documentation, machine-readable roadmap metadata and repository consistency checks. It changes no frozen Scala API, compiler semantics, runtime, generator, simulator adapter or VIP implementation. Foundation 147-149 stay unchecked. Completed Increment 152 evidence and unrelated roadmap completion states are preserved.

## Acceptance evidence

Run `python3 scripts/check_hvl_roadmap.py`, `python3 scripts/test_hvl_roadmap.py`, repository Markdown/link checks, contribution policy and required Core CI on the exact PR head. The checker exercises roadmap invariants, including mutation rejection; it does not claim that deferred HVL capability fixtures execute today. PR/check metadata carries immutable validation and merge identities.
'''

# Build a fully resolved graph. Existing item IDs and umbrella ownership are retained.
s = json.loads(texts[SURFACE])
s['schemaVersion'] = '0.3'
s['updated'] = '2026-09-05'
s['normativeArchitecture'] = ADR27
s['capabilityAxes']['executionClass']['live']['requiresQualifiedImplementationForEveryOperation'] = True
s['capabilityAxes']['executionClass']['live']['automaticallySupportsGeneratedOnlyExtensions'] = False
s['capabilityAxes']['executionClass']['portable']['captureIncludesTypedProfileExtensions'] = True
s['semanticLayers']['portableMaterialization'] = 'Complete common Verification Semantic IR plus declared typed profile-extension operations before projection.'
s['semanticLayers']['profileExtensionContract'] = ['versioned-operation-id', 'typed-operands-results', 'symbol-references', 'effects-and-scheduling', 'source-span', 'stable-identity', 'verifier', 'serialization-roundtrip', 'qualified-mode-implementation']
s['bindingRules'] = [x.replace('A live test may call portable components.', 'A live test may call captured components only when all required operations have qualified live implementations.').replace('A requested projection may reject a live-only or incompatible-profile test without invalidating that test for live execution.', 'Projection rejection never invalidates otherwise-supported live execution; generated-only extensions do not imply live eligibility.') for x in s['bindingRules']]
s['releaseRules'] = [x.replace('Commercial live adapters and generated commercial profiles are deferred optional profiles.', 'Additional commercial profiles are optional and never block open live, VTB or open-harness release; executable generated profiles require at least one actual qualified simulator.') for x in s['releaseRules']]
s['capabilityDefinitions'] = {entry['id']: {'kind': 'execution' if group == 'executionClass' else 'profile', 'requires': entry.get('requires', [])} for group, values in s['capabilityAxes'].items() for entry in values.values()}
s['capabilityDefinitions']['nodal.hvl.portable.ams'] = {'kind': 'semantic-extension', 'requires': ['nodal.hvl.portable.core'], 'provider': 'AMSP-01'}
nodes = {'foundation.complete': {'kind': 'external-barrier', 'source': TODO, 'anchor': '## Foundation completion barrier', 'requires': [], 'rule': 'Every Foundation increment, including later additions, must be accepted with evidence; this metadata never opens the barrier.'}, 'digital-live.required-slice': {'kind': 'external-increment', 'source': DEP, 'anchor': '**Digital Verification Increment 1 —', 'requires': ['foundation.complete', 'LIVE-08']}, 'foundation.ams.semantics': {'kind': 'external-contract', 'source': TODO, 'anchor': '**Increment 128 —', 'requires': ['foundation.complete'], 'includesFoundationIncrements': [128, 129, 134, 135, 136, 137, 138, 139, 140, 141, 142, 152]}}
for w in s['workstreams']:
    previous = 'foundation.complete'
    for item in w['increments']:
        nodes[item] = {'kind': 'item', 'source': PLAN, 'anchor': '**' + item + ' —', 'requires': [previous]}
        previous = item
    if w['id'] == 'AMSP':
        w['blockedBy'] = ['foundation.complete', 'CAP-05']
        w['profileReleaseGates'] = ['release.verilog-ams-tb', 'release.open-ams-harness', 'release.uvm-ms']
        w['aggregateOnly'] = True
    if w['id'] == 'XPAR':
        w['blockedBy'] = ['foundation.complete', 'CAP-05']
        w['aggregateOnly'] = True

overrides = {
    'ANA-01': ['foundation.complete', 'LIVE-06'], 'ANA-08': ['release.analog-live'], 'ANA-09': ['ANA-07'],
    'MS-01': ['foundation.complete', 'LIVE-08', 'ANA-07', 'digital-live.required-slice'], 'MS-07': ['release.mixed-live'], 'MS-08': ['MS-07'], 'MS-09': ['MS-06'],
    'CAP-01': ['foundation.complete', 'LIVE-01'], 'VTB-01': ['foundation.complete', 'CAP-05'], 'UVM-01': ['foundation.complete', 'CAP-05'],
    'AMSP-01': ['foundation.complete', 'CAP-05', 'foundation.ams.semantics'], 'AMSP-02': ['AMSP-01'], 'AMSP-03': ['AMSP-01'], 'AMSP-04': ['AMSP-01', 'UVM-09'],
    'AMSP-05': ['evidence.verilog-ams-tool', 'evidence.uvm-ms-tool'],
    'AMSP-06': ['evidence.verilog-ams-vip', 'evidence.open-ams-vip', 'evidence.uvm-ms-vip'],
    'AMSP-07': ['release.verilog-ams-tb', 'release.open-ams-harness', 'release.uvm-ms'],
    'XPAR-01': ['foundation.complete', 'CAP-05'], 'XPAR-02': ['XPAR-01', 'LIVE-08', 'VTB-07'], 'XPAR-03': ['XPAR-01', 'LIVE-08', 'UVM-09'],
    'XPAR-04': ['evidence.parity.open-ams', 'evidence.parity.verilog-ams', 'evidence.parity.uvm-ms'], 'XPAR-05': ['XPAR-02', 'XPAR-03', 'XPAR-04'],
}
for name, req in overrides.items():
    nodes[name]['requires'] = req

evidence = {
    'evidence.verilog-ams-tool': (['AMSP-02'], 'AMSP-05', 'Actual selected Verilog-AMS simulator compile/elaborate/run, locked tool profile and wave/result evidence; no UVM-MS requirement.'),
    'evidence.uvm-ms-tool': (['AMSP-04'], 'AMSP-05', 'Actual selected UVM-MS simulator compile/elaborate/run and locked methodology/library profile; no procedural-AMS requirement.'),
    'evidence.open-ams-tool': (['AMSP-03'], 'AMSP-03', 'Open harness compile/load/co-simulation run, bridge/timing checks, replay and normalized waves/results.'),
    'evidence.verilog-ams-vip': (['evidence.verilog-ams-tool'], 'AMSP-06', 'External consumer VIP reuse and profile-specific scale/conformance for Verilog-AMS TB.'),
    'evidence.open-ams-vip': (['evidence.open-ams-tool'], 'AMSP-06', 'External consumer reuse and profile-specific scale/conformance for the open AMS harness.'),
    'evidence.uvm-ms-vip': (['evidence.uvm-ms-tool'], 'AMSP-06', 'External consumer VIP reuse and profile-specific scale/conformance for UVM-MS.'),
    'evidence.parity.open-ams': (['XPAR-01', 'MS-09', 'release.open-ams-harness'], 'XPAR-04', 'Qualified common-subset live/open-harness comparison with explicit exclusions and tolerance evidence.'),
    'evidence.parity.verilog-ams': (['XPAR-01', 'MS-09', 'release.verilog-ams-tb'], 'XPAR-04', 'Qualified common-subset live/Verilog-AMS-TB comparison with explicit exclusions and tolerance evidence.'),
    'evidence.parity.uvm-ms': (['XPAR-01', 'MS-09', 'release.uvm-ms'], 'XPAR-04', 'Qualified common-subset live/UVM-MS comparison with explicit exclusions and tolerance evidence.'),
}
for name, (req, owner, description) in evidence.items():
    nodes[name] = {'kind': 'profile-evidence', 'source': PLAN, 'anchor': '`' + name + '`', 'owner': owner, 'requires': req, 'acceptance': description}
releases = {
    'release.live': ['LIVE-08'], 'release.analog-live': ['ANA-09'], 'release.mixed-live': ['MS-09'], 'release.capture': ['CAP-05'],
    'release.vtb': ['VTB-07'], 'release.uvm': ['UVM-09'],
    'release.verilog-ams-tb': ['AMSP-02', 'evidence.verilog-ams-tool', 'evidence.verilog-ams-vip'],
    'release.open-ams-harness': ['AMSP-03', 'evidence.open-ams-tool', 'evidence.open-ams-vip'],
    'release.uvm-ms': ['AMSP-04', 'evidence.uvm-ms-tool', 'evidence.uvm-ms-vip'],
    'release.parity-vtb': ['XPAR-02'], 'release.parity-uvm': ['XPAR-03'],
    'release.parity-open-ams': ['evidence.parity.open-ams'], 'release.parity-verilog-ams': ['evidence.parity.verilog-ams'], 'release.parity-uvm-ms': ['evidence.parity.uvm-ms'],
    'release.ams-aggregate': ['AMSP-07'], 'release.parity-aggregate': ['XPAR-05'],
    'release.all-qualification': ['LIVE-08', 'ANA-09', 'ANA-08', 'MS-09', 'MS-07', 'MS-08', 'CAP-05', 'VTB-07', 'UVM-09', 'AMSP-07', 'XPAR-05'],
}
for name, req in releases.items():
    nodes[name] = {'kind': 'release', 'source': PLAN, 'anchor': '`' + name + '`', 'requires': req, 'state': 'deferred', 'aggregateOnly': name.endswith('aggregate') or name.endswith('all-qualification')}
s['dependencyNodes'] = nodes
s['profileReleaseGates'] = {'nodal.hvl.live': 'release.live', 'nodal.hvl.portable.core': 'release.capture', 'nodal.hvl.projection.verilog_tb': 'release.vtb', 'nodal.hvl.projection.uvm': 'release.uvm', 'nodal.hvl.projection.verilog_ams_tb': 'release.verilog-ams-tb', 'nodal.hvl.projection.open_ams_harness': 'release.open-ams-harness', 'nodal.hvl.projection.uvm_ms': 'release.uvm-ms'}
s['qualificationCases'] = [
    {'id': 'host-reference-model', 'live': True, 'verilogTb': False, 'uvm': False},
    {'id': 'shared-transaction-check', 'live': True, 'verilogTb': True, 'uvm': True},
    {'id': 'uvm-factory-without-live-implementation', 'live': False, 'verilogTb': False, 'uvm': True},
    {'id': 'vtb-hook-without-other-implementation', 'live': False, 'verilogTb': True, 'uvm': False},
]
s['qualificationCasesStatus'] = 'required-future-fixtures-not-implemented'
s['dependencyInterpretation'] = 'requires are AND edges, not numeric-order inference. Profile-evidence owner identifies umbrella ownership, not a prerequisite on aggregate owner completion. Deferred nodes are not accepted merely because graph validation passes.'
s['synchronizedDocuments'] = [PLAN, TODO, DEP, GEN, ADR23, ADR25, ADR27]
texts[SURFACE] = json.dumps(s, indent=2) + '\n'
ledger = '''## Resolved dependency and independent release ledger

The JSON surface contains the complete dependency graph. Every reference resolves to a work item, a bound external Foundation/digital contract, a profile-specific evidence gate, or a release. Dependencies are explicit AND edges, never implicit numeric ordering. Every implementation and release remains deferred; checking this graph does not open Foundation or qualify a simulator.

External bindings: `foundation.complete` is the complete Foundation barrier in the main roadmap; `digital-live.required-slice` is Digital Verification Increment 1 plus LIVE-08; `foundation.ams.semantics` is the Foundation barrier including analog/interface/equation contracts 128-129, 134-142 and 152. No unresolved 'applicable profiles' placeholder is an acceptance gate.

### Profile-specific evidence gates

An evidence gate can close independently of its umbrella owner. Owner names allocate scope, not prerequisite edges. Accepted evidence must include exact design/test/tool/library versions, commands, artifacts, results, exclusions and source identities. Rendering, missing tools and skipped runs cannot satisfy a gate.

| Evidence ID | Owner | Prerequisites | Required evidence |
| --- | --- | --- | --- |
'''
for name, (req, owner, desc) in evidence.items():
    ledger += f'| `{name}` | {owner} | ' + ', '.join(req) + f' | {desc} |\n'
ledger += '\n### Release gates\n\nEach non-aggregate release closes independently after its listed prerequisites. Additional commercial/vendor breadth and aggregate completion are not implicit prerequisites.\n\n| Release ID | Prerequisites | Kind |\n| --- | --- | --- |\n'
for name, req in releases.items():
    ledger += f'| `{name}` | ' + ', '.join(req) + ' | ' + ('Aggregate only' if nodes[name]['aggregateOnly'] else 'Independent') + ' |\n'
ledger += '\n### Documentation acceptance versus feature acceptance\n\nRun `python3 scripts/check_hvl_roadmap.py` and `python3 scripts/test_hvl_roadmap.py`. These validate dependency resolution, acyclicity, profile independence, capture/eligibility invariants and synchronized roadmap references. The positive/negative capability examples in ADR 0027 remain future compiler/runtime conformance obligations, not implemented HVL fixtures.\n\n'
change(PLAN, '## Mapping to existing umbrella increments', ledger + '## Mapping to existing umbrella increments')

# Current documentation entry points carry an explicit normative amendment reference.
for path in (PLAN, TODO, DEP, GEN):
    if '0027-hvl-execution-projection-capability-contract.md' not in texts[path]:
        raise RuntimeError('Missing amendment link: ' + path)
for path in (TODO, DEP):
    after = re.findall(r'^- \[([ x])\] \*\*(.*?)\*\*', texts[path], re.M)
    if after != original_checkboxes[path]:
        raise RuntimeError('Unrelated increment status/title changed: ' + path)

# Add fast roadmap checks inside the existing required Core CI contracts job.
change(CI, '      - name: Validate contracts, style, and online provenance\n        run: |\n', '      - name: Validate contracts, style, and online provenance\n        run: |\n          python3 scripts/check_hvl_roadmap.py\n          python3 scripts/test_hvl_roadmap.py\n')
for path, content in texts.items():
    dest = ROOT / path
    dest.parent.mkdir(parents=True, exist_ok=True)
    dest.write_text(content, encoding='utf-8')
print('Materialized', len(texts), 'documentation/CI files; preserved all existing umbrella checkbox states and titles.')
