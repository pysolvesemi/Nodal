# Increment 36 — Time and waveform operators

**Status:** Validated
**Baseline:** Increment 35 closure merge `99a4c379758489206a72fdbd883d7f89feecf520` (PR #114).  
**Design gate:** [NodalTimeWaveformOperators-DG-v0.1](../design-gates/NodalTimeWaveformOperators-DG-v0.1.md).

## Implementation

The public Scala API adds all supported argument counts for `transition`, `slew`, and `absdelay`, plus `abstime` and effect-only `boundStep`. Construction records identity, owner, context, operand dimensions, result dimension, continuity, state identity, analysis inventory, and source mapping. Argument omissions are represented by actual arity, not invented literals.

The bridge serializes a dedicated waveform inventory and emits five first-class operations for legacy analog regions. Existing equation/contribution source inventories remain distinct from native residual construction. The native verifier independently checks operator contracts and reconstructs legacy scalar dimensions from their definitions. Constant-time parameters are not confused with constant-valued defaults under overrides.

The backend materializes each stateful operator once into a collision-free real temporary. Repeated uses share that temporary. Unused filter invocations and step requests are retained. Constant folding may simplify arguments but may not replace time observations, filter histories, or step effects.

## Executable matrix

| Layer | Evidence entry point |
|---|---|
| Construction | `TimeWaveformConstructionTests.scala` |
| Serialization | `TimeWaveformBridgeTests.scala` |
| Public construction witness | `nodal.increment36fixture.Increment36ConstructionCheck` |
| Compiler-side source witness | `nodal.internal.testkit.Increment36MlirCheck` |
| Native typed fixture | `core/compiler/test/IR/analog-time-waveform.mlir` |
| Native rejection and backend matrix | `tests/compiler/fixtures/increment36/run_native_matrix.py` |
| Repository and mutation checks | `scripts/check_increment36.py`, `tests/compiler/test_increment36.py` |
| Required increment CI | `.github/workflows/increment-36-time-waveform-operators.yml` |

The public example depends only on the public API. A compiler-side test harness imports the separately compiled example and performs bridge emission; the bridge is not exposed through the example's dependencies. The typed native fixture uses unit-bound parameter references rather than illegal dimensioned real literals.

The native matrix exercises all ten supported filter arities, rejects invalid constant arithmetic without treating it as an unknown runtime value, and exercises every diagnostic family through both ordinary parsing/verification and the constant-folding/canonicalization/common-subexpression pipeline. It additionally checks deterministic repeated optimization, exact before/after emission, shared-state references, unused-state retention, effect-only retention, and authored-name collisions. The workflow feeds the actual Scala-emitted MLIR to the compiled native tools; handwritten IR alone is not sufficient evidence.

The backend structural output check recognizes generated waveform declarations, one assignment per state, and step-request tasks. It checks call arity and balanced arguments and rejects malformed targets. This structural gate is not a general Verilog-A parser or a numerical simulation result. The prior RC unsupported-operation fixture now uses an unsupported generic candidate operation; unary negation has a positive regression because signed waveform rates require it.

## Closure policy

The implementation merge and exact post-merge Core CI and Increment 36 validation are complete. The manifest records the immutable accepted head, the complete 26-workflow matrix, and the exact implementation merge and post-merge runs. See the [accepted-evidence record](increment36-evidence-closure.md). The separate evidence-closure PR remains subject to its own Core CI before merge; no self-validation or numerical solver result is claimed.

## Deferred work

This increment does not execute numerical simulations or prove simulator accuracy. Event-held variable continuity, procedural waveform composition, residual DAE construction, analysis-specific AC/noise lowering, and full Verilog-AMS lowering remain deferred. Runtime argument checks for values not provably constant belong to the emitted target intrinsic, not to speculative substitution of defaults.
