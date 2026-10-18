# Foundation 46 readiness

**Date:** 2026-10-18
**Status:** Focused implementation complete; final publication and verified integration pending.
**Branch:** `increment/46-analog-semantic-lint`
**Base:** `a2eed4dca32f468c73c99107d32b227773fea8aa`

F-045 is integrated as `6bc1d37ea5909de093a9e163c618df8018374c35` with exact tested
tree equality, followed by documentation-only closure. Apply the current prototype
policy: focused checks, affected predecessors, concise architecture review and
`[skip ci]`. No CI or hourly monitoring.

## Selected implementation and shared owners

The [v0.1 gate](../design-gates/NodalAnalogSemanticLint-DG-v0.1.md) defines a
read-only source-lint pass. Existing topology, access, units, numeric, events,
parameter and target owners retain their mandatory errors. New advisories cover
concrete source risk and do not claim solver correctness or silently repair IR.

- `DiagnosticSupport` gains mapped warning emission using existing source context.
- `NativeDiagnosticMapper` must isolate a real error and its own context when
  warnings precede it; warning-only abnormal exits remain process failures.
- `ParameterModel` supplies narrow zero-admissibility facts from existing numeric
  constraints, including exclusions and exclusive bounds. Symbolic defaults are
  never substituted into a nonzero proof.
- The existing function registry carries finite step-discontinuity classification.
  A missing classification is not a proof of global continuity.
- `AnalogSemanticLint` collects deterministic issues from verified source operations
  and existing typed event/source expressions, without expanding deferred fill DAGs.
  The finite risk profile covers numeric SSA, monitor arguments and resolved live
  graphs; general non-live procedural source bodies remain outside this checkpoint.

## Required structural-analysis boundary

F-046.B lists local/global equation imbalance and structural singularity, while
F-046.A explicitly allocates later whole-island DAE analysis to F-135. F-134 owns
executable equations and unknown identities; F-135 owns residual construction,
classification, incidence/matching, component and island balance and singularity.
At increment entry, F-032 equations were authored-string inventory, not that representation.

An unavailable-analysis advisory is honest status, not detection of imbalance or
singularity. No equation-string parser, naive equation count, or pretend balanced
result is authorized by this implementation. Preserve the original checklist; the
minimum dependency below resolves its allocation without closing full DAE work.
The selected dependency direction is a minimum genuine typed F-134/F-135 checkpoint,
not wholesale completion of those increments or a substitute string/counting pass.

## Focused checks planned

Pair each risk with a valid neighboring case. Retain existing floating/discipline/
branch/unit/envelope/orientation/division errors, valid partial components, generated
hierarchy and event phase behavior. Test warning/error context separation, stable
issue order, unchanged IR and emitted mathematical statements, sparse/deep source
connectivity, reused expressions and long source paths. Add actual public Scala,
native IR and Verilog-A witnesses plus explicit F-048/F-049/F-052 handoff.

No checks in this plan are claimed executed until their results are recorded.

## Source-risk checkpoint results

Focused native checks passed before typed-model integration: 21 lint cases, 44
parameter zero-admissibility cases and 36 divisor cases. The latter include 14
regressions for fixed parameters whose `parameter_value` still depends on a
symbolic parameter: a fallback default must not be folded as an invariant value.
Ten Scala diagnostic-mapping cases and six public lint source cases passed.

The public Scala fixtures produced eight valid VA/AMS inputs. A 58-command replay
checked exact warning order/source mapping, quiet neighboring controls, normalization
fixed points, repeated output equality and compiler/translator zero-divisor errors.
The existing F-045 targets stayed byte-identical. Raw commands, diagnostics and
outputs are under `.work/increment46/targets/`; these are pre-structural-integration
results. The same 58 assertions subsequently passed against the final native tools.

The exact safe continuous Verilog-A witness compiled with OpenVAF-reloaded
`20260616-3-g0e83f1ed`. The risky witness retained authored `ceil` and triggered a
consumer crash: `intrinsic llvm.ceil.f64 not found` (exit 101, no OSDI output).
This is a concrete F-048 compatibility handoff, not simulator acceptance. No
simulator or numerical-equivalence claim follows from target structural checks.

## Typed dependency implementation

The [structural checkpoint gate](../design-gates/NodalAnalogStructuralCheckpoint-DG-v0.1.md)
activates existing public physical components, states and equation capture with
actual typed DAG roots. The Dialect-owned `AnalogEquationModel` checks native,
parameter, numeric, F-032 inventory and source coverage contracts before deriving
finite incidence and matching. Existing F-032 inventory validation moved unchanged
from the transform into `AnalogSemanticInventory`, avoiding a reverse dependency.
The lint and optional diagnostic report consume that shared model. Source equation
operations remain explicitly rejected by HDL emission until their lowering owner
is implemented; no equations are silently omitted or oriented.

The complete checkpoint covers explicit concrete scalar algebraic components with
full coverage, including unused unknowns and separate initialization metadata.
Incomplete hierarchy, guarded or unsupported source behavior produces no matching
success. This is the minimum required Foundation dependency for F-046 detection;
F-134/F-135 remain open for their other original deliverables. Typed source/native
integration passes for the complete finite profile and explicit incomplete cases.

## Later tool and numerical handoff

- F-048: use the exact safe/risky continuous VA witnesses and both event witnesses.
  Record the compiler/version and actual unsupported lifecycle-filter behavior.
  The observed OpenVAF `ceil` crash is a failing compatibility case to preserve.
  Typed source equations deliberately fail HDL capability checking; do not treat
  their diagnostic report as emitted executable equations.
- F-049/F-052: the safe continuous fixture uses a time-domain sine reference;
  compare emitted branch values against the authored formula in the actual target
  units on a fixed transient grid. The risky fixture keeps division and floor/ceil;
  choose a nonzero divisor explicitly, test samples on both sides of integer-time
  boundaries, and compare exact step levels separately from event time placement.
  Proposed value tolerances are absolute `1e-12` plus relative `1e-9` in the target
  quantity's SI unit; event-time tolerance is `1e-12 s` on the selected grid. These
  are future test criteria, not measured simulation results or universal accuracy.
- Event references retain initial/final filter identity, constant cross/above
  conditions and disabled timer arguments. Count reached authored event bodies and
  observe held output values under the chosen supported analysis. Do not infer
  arithmetic evaluation suppression from a disabled monitor.
- Structural witnesses have exact combinatorial references: sorted equation and
  unknown identities, incidence matches and unmatched sets require exact equality.
  Balanced matching is not a numerical nonsingularity reference. Initialization
  solving, hierarchy expansion and target equation lowering remain assigned to
  their existing Foundation owners.

## Native typed checkpoint validation

The final focused native run passes six directly affected unit targets: 318 typed
model assertions, 21 semantic-lint cases, 44 zero-admissibility cases, 36 divisor
cases, 34 canonicalization cases and 60 dead-local cases. The typed checks include
balanced and defective matching, unused states, compensating local defects,
independent malformed-input verification, unit conversion, metadata provenance,
stale report replacement and unchanged malformed failure. The earlier test-only
diagnostic mismatch was corrected to the shared arithmetic promotion diagnostic;
no semantic negative was relaxed.

Architecture review found no concrete blocker in shared typed ownership, iterative
incidence, deterministic matching and local/global defect handling. The F-032
inventory validator retains its previous behavior at the shared Dialect owner.
Matching traverses an explicit sparse graph; repeated dependency sets, verifier
passes and augmenting searches are not claimed to make the whole pipeline linear.
No source expression is oriented, divided, reassociated or repaired by lint.

Five rebuilt affected predecessor targets also pass: semantic pipeline, analog
expression typing, nature/discipline, conservative connectivity and parameter
model. These retain existing mandatory errors for source topology/branch/unit and
parameter contracts rather than replacing them with advisory classifications.

## Reproduction entry points

Use the existing pinned local build/bootstrap instructions, then build `nodalc`,
`nodal-translate` and the six focused unit targets named above. The public testkit
entry points are `Increment46MlirCheck` and `TypedEquationMlirCheck`, under
`nodal.internal.testkit`; each accepts an output directory and emits actual Scala
source witnesses. The latter also checks fresh-instance export equality.

For a typed source witness, invoke `nodalc <input.mlir>
--nodal-report-analog-structure --mlir-print-debuginfo` to inspect the recomputed
report, then `nodalc <input.mlir> --nodal-lint-analog` for diagnostics. A report may
contain a well-formed defective graph; successful report serialization does not
mean lint or target acceptance. For supported contribution/event witnesses, use
`nodalc <input.mlir> --nodal-gate-default --mlir-print-debuginfo`, followed by
`nodal-translate <input.mlir> --nodal-to-verilog-a` or `--nodal-to-verilog-ams`.
Replay normalized input and compare exact output and source-mapped diagnostics.


Two source integration defects found during focused replay/review were repaired:
empty mandatory module/analog regions now retain an explicit block, and canonical
reused child definitions retain full authored occurrence inventories with explicit
uncovered partitions. The latter remains an incomplete hierarchy profile rather
than falsely matching missing occurrence equations. Native overlap and false-complete
partition negatives pass.

The shared F-032 authored-value renderer now performs identity-memoized checked
preflight before expansion: maximum 1,048,576 UTF-16 code units and depth below 512.
This prevents a compact shared DAG from exponentially allocating authored text
before the typed model's guard. Ordinary accepted expression spelling is preserved.
The final source run passes 36 tests: eight typed source cases, two empty-region/
occurrence cases, three render-budget cases, seven F-032 predecessors, six public
lint cases and ten diagnostic-mapping cases. The budget tests preserve the exact
eight-level text and reject 40-level shared equations and initializers before
expansion. All 19 refreshed structural exports passed 77 native report/repeat/fixed-point/lint
commands, including explicit source-equation HDL rejection.


Pinned native formatting passes for all 20 affected C++ headers/sources. Source
Scalafmt passes for the affected files; new Scala files pass Scalafix. Shared-file
Scalafix reports 16 unchanged baseline findings verified against the base revision;
these are not represented as a clean whole-file lint result. Architecture, package
visibility, registry regeneration and contribution-policy checks pass.


## Final capability and checklist reconciliation

- F-046.A/B: shared topology, branch, discipline, unit, envelope and semantic
  verifiers keep their existing errors. New source-risk advisories use actual SSA,
  checked event/live graphs and parameter facts. The genuine typed dependency
  supplies local/global balance and square-incidence singularity errors for the
  stated complete algebraic profile; unavailable profiles do not claim detection.
- F-046.C/D: focused causal positives/negatives, public Scala to typed IR, actual
  contribution/event VA/AMS output and affected predecessors pass. Later tool
  references and explicit OpenVAF limitations are retained above.
- F-046.E/F: read-only lint, transactional failure, exact output comparisons,
  stable source ordering, 64 sparse source pairs, bounded shared DAGs and long
  source paths cover the changed algorithms. Existing diagnostic ownership and
  operation/category deduplication are reused. No additional emission optimization
  is needed for this lint increment.
- F-046.G and the parent remain open until verified integration. F-134/F-135 stay
  open beyond this deliberately finite dependency checkpoint. No whole-island,
  numerical solver, initialization solver or generic hierarchy analysis is claimed.

Final structural artifacts are in `.work/increment46/typed-source-final`; native
commands and reports are in `.work/increment46/structural-native`, with assertion
summary `.work/increment46/structural-replay-final.log`. Each public export compares
two independently constructed source instances. Repeated hierarchy preserves two
actual instances and a single reused definition, with explicit uncovered occurrence
coverage and no matched result. Empty components retain their real empty block and
produce a complete zero-by-zero runtime graph.

The final public VA/AMS exports also pass all 58 native target assertions against
final tools. Their generated VA files are byte-identical to the earlier independently
compiled/crashing OpenVAF inputs, so the recorded consumer results apply to these
exact artifacts. Final commands and source hashes are retained under
`.work/increment46/`; the public test entry points and focused native targets above
remain checked-in reproduction sources. No CI or numerical simulation was run.
