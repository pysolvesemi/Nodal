# Increment 37 — Analog events

**Status:** Implementation in progress  
**Branch:** `increment/37-analog-events`  
**Baseline:** `f6e11c5b3f92ee43b4a6d4fc6af21d478249b961`  
**Design gate:** [NodalAnalogEvents-DG-v0.1](../design-gates/NodalAnalogEvents-DG-v0.1.md)

## Implemented foundation

The public API has all prefix arities of `cross`, `above`, and `timer`, typed crossing direction, integer enable, lifecycle events with analysis filters, and ordered analog event OR. The existing `crossing` spelling now delegates to the real crossing contract and retains its semantic name. Digital edge handles remain separate.

`on(event)` is captured inside `analogProcedure`. Its body uses the existing structured procedural tree, not a second mutable-variable model. Event writes cannot establish unconditional definite assignment, including writes under `initialStep`. Empty event bodies remain explicit. Nested controls, runtime-dependent monitor placement, contributions, analog filters, and unimplemented generic effects fail closed.

Source construction retains argument positions, dimensions, source locations, analysis filters and owner-qualified control identities. References are resolved after semantic naming, so distinct parameters and terminals are not serialized as ambiguous class names. Reusing a source event handle creates distinct monitored occurrences at each authored control.

The bridge emits `!nodal.analog_event`, five primitive operations, `nodal.analog_event_or`, and `nodal.analog_on`. Native event validation independently parses the canonical source-expression grammar, derives dimensions from actual parameters, terminals and variables, and recomputes variable-read inventories and constant arithmetic. Parameter defaults are never proof of a constant. Unknown expression grammar is rejected, not treated as executable target text.

## Validation entry points

The public witness is `nodal.increment37fixture.Increment37ConstructionCheck`. The compiler-side harness is `nodal.internal.testkit.Increment37MlirCheck`. Source and bridge tests are `AnalogEventConstructionTests` and `AnalogEventBridgeTests`. The native matrix is `tests/compiler/fixtures/increment37/run_native_matrix.py`; it runs both directly and after constant folding, canonicalization and common-subexpression elimination.

Validation status is recorded separately from implementation. A CI workflow or a test filename is not evidence that its run passed. No numerical event scheduler, simulation, or target equivalence result is claimed.

## Work still required for Increment 37 closure

- Complete supported event-held variable continuity and `transition` integration.
- Implement controlled-statement Verilog-A lowering and structural target checks without dropping or hoisting event-only work. The current backend still rejects these procedural regions, as it did for Increments 33 and 34.
- Complete the native and source-to-target corner-case matrix, exact-head review and CI, implementation merge, post-merge checks, and separate immutable evidence closure.

The roadmap checkbox remains open. Simulator execution, full Verilog-AMS digital processes, and co-simulation scheduling are outside this increment's foundation implementation.
