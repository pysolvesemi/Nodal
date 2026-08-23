from __future__ import annotations

import json
from pathlib import Path

ROOT = Path(__file__).resolve().parent
ROADMAP = ROOT / "docs/roadmap/nodal-development-todo.md"
ARCH_INDEX = ROOT / "docs/architecture/README.md"
SURFACE = ROOT / "docs/roadmap/continuous-time-ams-v0.1-surface.json"
PLAN = ROOT / "docs/roadmap/continuous-time-ams-v0.1-plan.md"
ADR = ROOT / "docs/architecture/0022-layered-continuous-time-hybrid-dae-architecture.md"


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected one occurrence, found {count}")
    return text.replace(old, new, 1)


roadmap = ROADMAP.read_text(encoding="utf-8")
roadmap = replace_once(
    roadmap,
    "**Revision:** 1.15",
    "**Revision:** 1.16",
    "roadmap revision",
)

direction_anchor = (
    "- Keep digital resolved `inout`, conservative AMS terminals, directional analog "
    "signal-flow values, and discrete real nets as distinct semantic categories. "
    "Require explicit bridges for every analog/digital or conservative/signal-flow conversion.\n"
)
direction_addition = direction_anchor + (
    "- Preserve source-semantic analog constructs separately from normalized topology, hybrid "
    "equation systems, analysis projections, target AMS IR, and solver-facing representations; "
    "no simulator callback ABI or emitted HDL text defines Nodal semantics.\n"
    "- Partition continuous behavior into explicit `AnalogIsland`s with stable topology, unknown, "
    "equation, contribution, state, event, noise, analysis, capability, and source identities.\n"
    "- Make analog state, initialization, discontinuities, event iteration, analysis context, "
    "environment/PVT, derivatives, solver hints, and model-validity envelopes explicit and "
    "machine-readable rather than backend side effects.\n"
    "- Negotiate simulator and solver capabilities before execution, reject unsupported behavior "
    "without approximation, and keep a native analog solver optional for the initial release.\n"
)
roadmap = replace_once(
    roadmap,
    direction_anchor,
    direction_addition,
    "fixed continuous-time direction",
)

roadmap = replace_once(
    roadmap,
    "- **M2 — Analog preview:** useful Verilog-A subset with open-source compilation and simulation regression.",
    "- **M2 — Analog preview:** useful Verilog-A subset with source-semantic analog IR, explicit island/equation/state/event/analysis contracts, open-source compilation, and simulation regression.",
    "M2 milestone",
)
roadmap = replace_once(
    roadmap,
    "- **M4 — Scalable core release:** packaged compiler, complete reference, frozen plugin and target-HDL pass SPIs, deterministic extension/pass graphs, optimization proof evidence, machine-readable check coverage and waiver inventory, conformance kits, library-author contract, and compatibility policy.",
    "- **M4 — Scalable core release:** packaged compiler, complete reference, frozen plugin and target-HDL pass SPIs, deterministic extension/pass graphs, continuous-time solver-capability and model-validity manifests, optimization proof evidence, machine-readable check coverage and waiver inventory, conformance kits, library-author contract, and compatibility policy.",
    "M4 milestone",
)

phase_anchor = "## Deferred reusable library roadmap\n"
if "## Phase 9 — Continuous-time equation, hybrid DAE, solver, and analog-model qualification closure" in roadmap:
    raise SystemExit("Phase 9 already exists")
phase = """## Phase 9 — Continuous-time equation, hybrid DAE, solver, and analog-model qualification closure

This independently schedulable phase closes the cross-layer continuous-time architecture accepted by ADR 0022. It does not replace the source-language, validation, mixed-signal, interface, or backend work in Increments 24-53, 68-78, and 128-129. It gives those increments one solver-independent semantic, mathematical, analysis, and evidence architecture.

- [x] **Increment 132 — Continuous-time equation, hybrid DAE, and solver architecture roadmap contract**
  - Accept [ADR 0022](../architecture/0022-layered-continuous-time-hybrid-dae-architecture.md), the staged [`continuous-time-ams-v0.1-plan.md`](continuous-time-ams-v0.1-plan.md), and the machine-readable [`continuous-time-ams-v0.1-surface.json`](continuous-time-ams-v0.1-surface.json).
  - Record distinct source-semantic analog IR, topology graph, hybrid equation-system IR, analysis projections, and target/solver representations.
  - Record explicit `AnalogIsland`, stable equation/unknown/state/event/noise identities, DAE structural verification, state and initialization ownership, hybrid event ordering, analysis/noise/environment contracts, solver capability negotiation, model validity envelopes, and retained evidence.
  - Keep exact public syntax, compiler implementation, solver behavior, and target lowering assigned to Increments 133-142 and the existing analog/AMS increments.
  - Evidence: [`0022-layered-continuous-time-hybrid-dae-architecture.md`](../architecture/0022-layered-continuous-time-hybrid-dae-architecture.md), [`continuous-time-ams-v0.1-plan.md`](continuous-time-ams-v0.1-plan.md), and [`continuous-time-ams-v0.1-surface.json`](continuous-time-ams-v0.1-surface.json).

- [ ] **Increment 133 — Analog semantic API and analysis contract design gate**
  - Compile and compare public candidates for equations/contributions, explicit analog state and initialization, event tolerance and discontinuity declarations, analysis context, environment/PVT access, noise identity/correlation, validity envelopes, and solver-hint metadata.
  - Publish `NodalContinuousTimeApi-DG-v0.1.md`, a machine-readable public surface, migration notes, stable diagnostics, positive/negative fixtures, and an external reusable-model fixture.
  - Keep frontend, equation normalization, solver, and backend behavior inert.

- [ ] **Increment 134 — Source-semantic analog IR, `AnalogIsland`, and stable identities**
  - Implement source-semantic operations for contributions, equations, operators, events, analyses, noise, environment, connect constructs, and solver hints.
  - Build deterministic islands and stable IDs for topology objects, unknowns, equations, state, events, noise, bridges, and analyses.
  - Add normalized parse/print, source maps, parameter formulas/envelopes, mutation tests, and semantic manifests.

- [ ] **Increment 135 — Topology expansion, residual DAE construction, and structural verification**
  - Expand conservative connections and contribution sets into solver-neutral residual systems while preserving provenance.
  - Classify continuous, derivative, algebraic, discrete, parameter, environment, independent, and input variables.
  - Implement incidence/dependency graphs, structural matching, block decomposition, equation/unknown balance, reference/conservation checks, singularity, algebraic loops, initialization structure, variable-topology classification, and parameter-envelope checks.
  - Reject unsupported higher-index or variable-structure systems explicitly; do not perform unapproved index reduction.

- [ ] **Increment 136 — Continuous state, initialization, operators, and hidden-state reporting**
  - Implement state ownership for `ddt`, `idt`, Laplace/Z-domain operators, delay, transition, slew, hysteresis, sample/hold, and bridge state.
  - Implement fixed values, guesses, initial equations, steady-state conditions, operating-point-derived initialization, reinitialization/jumps, and conflict diagnostics.
  - Generate state/initialization reports and reject accidental duplication, movement, or hidden backend state.
  - Add semantics-preserving operator lowering and differential fixtures.

- [ ] **Increment 137 — Hybrid event scheduler, discontinuities, and mode-dependent topology**
  - Freeze and implement observable ordering for initialization, integration, root detection, timers, analog events, digital events, bridge updates, zero-time event iteration, restart, and finalization.
  - Implement dynamic event tolerances, named events, interrupted transitions, event fixed-point convergence, zero-time oscillation diagnostics, and event dependency graphs.
  - Classify hard discontinuities, smoothness, hysteresis, guarded equations, and explicitly supported topology modes.
  - Keep solver hints separate from mathematical behavior.

- [ ] **Increment 138 — Analysis projections, linearization, derivatives, and noise**
  - Derive initialization, DC/operating-point, transient, AC/small-signal, and noise projections from one semantic model.
  - Implement derivative/Jacobian interfaces, sparse structure, differentiability diagnostics, symbolic/automatic/analytic derivative evidence, and capability-gated numerical differentiation.
  - Preserve stable noise identity, PSD dimensions, hierarchy, correlation, transfer paths, and analysis applicability.
  - Add cross-analysis consistency and derivative differential tests.

- [ ] **Increment 139 — Environment, PVT, statistical variation, and model validity envelopes**
  - Implement immutable typed environment contexts for temperature, nominal temperature, corner/process, supplies or declared conditions, analysis/sweep coordinates, and deterministic random seeds.
  - Separate parameters, environment, small-signal noise, transient stochastic behavior, global variation, and local mismatch.
  - Implement static and dynamic validity-envelope checks, applicability/accuracy metadata, violation policy, source-located diagnostics, and manifests.
  - Add PVT/seed matrix determinism and envelope-boundary fixtures.

- [ ] **Increment 140 — Solver capability profiles and simulator/model ABI seam**
  - Define solver-neutral residual, state, event, derivative, noise, environment, and result interfaces.
  - Publish capability descriptors and negotiation for analyses, DAEs, events, variable topology, noise, derivatives, tolerances, connect rules, mixed-signal bridges, statistics, hierarchy, and result fidelity.
  - Define an OSDI-like external model adapter seam and a future native solver plugin seam without requiring either to define Nodal semantics.
  - Retain adapter versions, options, commands, hashes, capability decisions, diagnostics, and failure classification.

- [ ] **Increment 141 — Verilog-A/Verilog-AMS and solver-facing lowering parity**
  - Prove source-semantic, residual/analysis, and target-lowering correspondence for supported constructs.
  - Emit deterministic state, event, noise, analysis, environment, contribution, and source-map metadata alongside Verilog-A/Verilog-AMS.
  - Add target reparse, OpenVAF/OSDI where supported, ngspice and mixed-signal adapter differential fixtures, and explicit capability rejection.
  - Do not claim waveform equivalence as proof for unsupported structural transformations.

- [ ] **Increment 142 — Continuous-time validation, scale, and reusable-model qualification**
  - Add DC/operating-point/transient/AC/noise differential suites, event-order and initialization tests, parameter/PVT/seed matrices, cross-simulator comparisons, and failure classification.
  - Exercise large sparse islands, hierarchy, repeated models, deterministic ordering, derivative/Jacobian performance, cache keys, and memory/runtime budgets.
  - Qualify one external reusable analog model package using only public contracts, including model/environment/validity/solver manifests and portability reports.
  - Publish a capability and limitations matrix for higher-index DAE, dynamic topology, advanced analyses, stochastic features, and simulator extensions.

"""
roadmap = replace_once(
    roadmap,
    phase_anchor,
    phase + phase_anchor,
    "Phase 9 insertion",
)
ROADMAP.write_text(roadmap, encoding="utf-8")

arch = ARCH_INDEX.read_text(encoding="utf-8")
row_21 = (
    "| [0021](0021-unified-struct-interface-role-and-inout-architecture.md) | "
    "Separate directionless `Struct` values from connectivity `Interface`s, use generic named roles "
    "with master/slave convenience, support explicit digital resolved inout, preserve conservative "
    "AMS terminals, and retain one logical Interface ABI across flattened and native backends. |\n"
)
row_22 = (
    "| [0022](0022-layered-continuous-time-hybrid-dae-architecture.md) | "
    "Preserve source analog intent through layered semantic, topology, hybrid equation, analysis, "
    "and solver representations with explicit analog islands, state, events, capabilities, and "
    "model-validity evidence. |\n"
)
arch = replace_once(arch, row_21, row_21 + row_22, "ADR 0022 table row")

paragraph_21 = (
    "ADR 0021 specializes the core semantic, clock/reset, AMS, backend-profile, and quality-gate "
    "boundaries for reusable connectivity. It keeps storable values, protocol interfaces, digital "
    "resolved inout, conservative terminals, directional analog signal flow, mixed-signal bridges, "
    "and backend physical layouts distinct while preserving one logical Interface ABI.\n"
)
paragraph_22 = (
    "\nADR 0022 specializes the analog semantic, AMS backend, optimization, simulator-adapter, and "
    "quality-gate boundaries for continuous-time compilation. It keeps source constructs, topology, "
    "hybrid equations, analysis projections, solver callbacks, and target HDL distinct while "
    "preserving stable state, event, noise, environment, capability, validity, and source evidence.\n"
)
arch = replace_once(
    arch,
    paragraph_21,
    paragraph_21 + paragraph_22,
    "ADR 0022 explanatory paragraph",
)
ARCH_INDEX.write_text(arch, encoding="utf-8")

surface = json.loads(SURFACE.read_text(encoding="utf-8"))
if surface.get("roadmap_increment") != 132:
    raise SystemExit("surface roadmap increment is not 132")
if surface.get("public_semantic_gate") != 133:
    raise SystemExit("surface public semantic gate is not 133")
if surface.get("native_solver_required_for_initial_release") is not False:
    raise SystemExit("surface incorrectly requires a native solver")

for path in (ROADMAP, ARCH_INDEX, SURFACE, PLAN, ADR):
    text = path.read_text(encoding="utf-8")
    if not text.endswith("\n"):
        raise SystemExit(f"{path} lacks final newline")

required_roadmap = (
    "**Revision:** 1.16",
    "- [x] **Increment 132 — Continuous-time equation, hybrid DAE, and solver architecture roadmap contract**",
    "- [ ] **Increment 133 — Analog semantic API and analysis contract design gate**",
    "- [ ] **Increment 142 — Continuous-time validation, scale, and reusable-model qualification**",
    "continuous-time-ams-v0.1-plan.md",
    "continuous-time-ams-v0.1-surface.json",
)
for fragment in required_roadmap:
    if fragment not in roadmap:
        raise SystemExit(f"roadmap lacks: {fragment}")

print("Increment 132 roadmap materialization passed")
