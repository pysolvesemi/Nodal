# Analog regression and scalar hierarchy simulation profile v0.1

**Status:** Approved
**Scope:** public-api
**Scope:** core-library-boundary
**Date:** 2026-10-18
**Owner:** Foundation 52

## Authority and ownership

The owner's explicit instruction to implement all unchecked Foundation increments
from 43 authorizes F-052 and the essential shared prerequisites below. Standing
public publication/completed-increment integration authority applies with `[skip ci]`.
This is the selected finite implementation contract, not a separate owner choice
of each signature. No CI or exhaustive qualification is introduced.

Maintained source fixtures, independent numerical references and selected case
manifests belong to the existing testkit regression owner. They invoke the existing
public source/compiler/bench/results services and private compiler/simulator
adapters; no second runner, parser, solver or handwritten target model is added.
Canonical source effect attribution belongs to construction; faithful hierarchy
materialization belongs to the native backend before rendering, not generated text.

## Represented analog assignments

The actual analog Variable assignment path records `analog-assignment`, while
unsupported digital assignments retain `assignment`. Existing operation-before-
recorder ordering preserves placement diagnostics; the entire capture must finish
successfully before admission. A failed ordinary recorder assignment poisons its
construction transaction even when authored code catches the original failure;
the original diagnostic is rethrown. ConstructionSession applies the original assignment
placement prohibition to both kinds and preserves `assignment` only for semantic
origin/sink-affinity naming. The simulation allowlist admits the represented analog
kind; procedural ownership, dimensions, DA and native verification still apply.
Generated-procedure recording remains unchanged. No generic assignment whitelist
or partial-DUT success is permitted.

## Explicit scalar hierarchy materialization

Default Verilog-A translation preserves the existing named module graph. The
opt-in `--nodal-to-verilog-a-scalar-hierarchy` translation materializes the complete
selected scalar hierarchy on a verified private IR clone before ordinary rendering.
Public compilation selects this mode explicitly; no fallback silently changes a
failed named-hierarchy request. Original source/normalized graph and emitted model
identities are retained. Root runtime parameter dependence must remain symbolic.

Accepted profile: one explicit root, finite acyclic reachable module graph, direct
scalar electrical terminals/nodes/branches and complete unique child port bindings
to actual parent-local connectivity anchors. Repeated and nested occurrences are
materialized independently. Child parameters are ordinary dimensionless real
formals with literal defaults, literal actuals or direct same-kind/unit parent
parameter references. Continuous scalar math/contributions and pure nonrecursive
module-local functions are supported; function symbols/callees are occurrence-local.
Root-only existing initialization is preserved. Child procedures/storage/stateful
operators, arrays, structural generation, custom disciplines and equation metadata
reject before expansion. They are not approximated or silently dropped.

Preflight limits are 1,000 occurrences, 100,000 resulting operations and hierarchy
depth 64, computed with saturated counts before expansion. Actual SSA mappings and
canonical conservative connectivity identify ports/nets; source strings are not
semantic authority. Materialized IR undergoes independent native verification and
normal backend/reparse checks. Failure is transactional and publishes no target.
Flattened names and authored hierarchy correspondence use deterministic inert
metadata, safely encoded so source text cannot terminate comments or inject code.
No per-actual reusable-module specialization or substitution of root defaults is
allowed. Broader child-state composition is a separate real extension.

## Regression profile and compatibility

Selected fixtures cover RC/RLC, finite Shockley diode, signed controlled relation,
affine/clipped pure-function amplifier, explicit equality-rule analog comparator,
and oscillator/VCO whose phase integrates frequency under changing control. The
existing initial-step scalar state is replayed through public simulation after the
admission repair. Each new case declares its finite stimulus domain, independent
reference and units/tolerances before execution. Unsupported event/noise or consumer
cases retain explicit blocked/error classes and do not inherit qualification from
initial-step success.

The new behavior-bearing repeated/nested hierarchy uses actual analog contributions,
symbolic parent dependence and distinct literal child actuals. Historical H42
metadata-only equations remain a source-to-target-lowering blocker; materialization
does not turn inventory into executable equations. Named-graph consumer rejection
remains separately recorded even when explicit materialization succeeds.

Runtime root-parameter/temperature checks use existing private binding/options
semantics and a nonpublic verified compilation-receipt handoff. They are distinct
from explicitly recaptured constructor-parameter cases. No arbitrary public raw
parameter override API is added. Existing F-048/F-049 tool identities, compiler
crash/array limits, process isolation, raw validation and evidence distinctions
remain unchanged. A successful compile/load alone never becomes numerical evidence.

## Alternatives and focused checks

Rejected alternatives: leaf extraction, handwritten or text-rewritten target graphs,
a fixture recognizer, default-specialized symbolic actuals, blind cloning of child
state, treating unsupported cases as passed, and replacing integrated VCO phase
with instantaneous frequency times elapsed time. Finite shared IR materialization
is selected over implementing another simulator or changing Nodal semantics to
match a consumer limitation.

Checks include canonical assignment/source-origin/placement/DA predecessors;
whole-graph native materialization, aliases, repeated/nested parameters, functions,
malformed/unsupported/budget cases and transactional output; actual generated-model
compile/load/numerical references; seeded cases, repeat identities and meaningful
failure stages. Focused architecture and cost review must complete before closure.
No required checklist case is waived by this gate; unexecuted obligations stay open.

### Literal parameter coefficient boundary

Regression fixture parameters use the existing literal Real default profile.
Composite physical unit factors are ordinary typed analog expressions, so symbolic
coefficient dependence survives emission. Compound Real parameter initializer DAGs
remain explicitly unsupported (`NODAL-BRIDGE-021`); this gate does not extend that
separate parameter contract or permit default substitution.

### Solver stopping accuracy is separate from acceptance tolerance

Actual diode DC execution exposed the public bench's fixed simulator `abstol`
(1e-12 A): two low-current points failed the independently declared 1e-15 A
acceptance criterion. The failed result remains evidence. Add the smallest shared
configuration contract, `SolverTolerances(relative, currentAbsolute,
voltageAbsolute)`, with dimension-typed current/voltage quantities. Defaults remain
1e-6 relative, 1e-12 A and 1e-9 V. All values are finite and strictly positive;
the existing adapter profile requires relative <= 1e-3. Append an optional
`solverTolerances` argument to `SimulationBench.create`, preserving all existing
positional/default calls. The same internal `NgspiceSolverOptions` validation,
request hashing and evidence retention remain authoritative; no raw options/deck,
GMIN or temperature switch is added here.

A stricter solver request does not change waveform acceptance criteria or prove
accuracy by itself. The diode keeps its 1e-15 A absolute acceptance limit, requests
1e-18 A solver stopping tolerance, and must pass an actual causal rerun. Alternatives
were retaining inaccurate default results, loosening the regression threshold or
routing the one fixture around the public bench; none addresses the missing shared
configuration while preserving the intended contract. Broad Foundation authority
above covers this necessary additive F52 API correction; no separate signature
selection by the owner is claimed.
