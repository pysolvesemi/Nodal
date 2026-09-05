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

## Event-target lowering tranche

The candidate backend lowers event-containing procedures to native analog event statements,
ordered blocking assignments, conditionals, case selection, and bounded procedural loops.
Root variables with static initializers become persistent module variables; lexical
initializers execute at their authored location. Explicit variable reads are materialized
at their read locations, so a later write cannot change an earlier captured value.
Canonical source expressions are parsed, bound and typed before rendering; source strings
are never pasted into HDL. Empty and unused event monitors are retained.

Runtime loop bounds are evaluated once and checked against the declared finite envelope.
An invalid bound reports `NODAL-ANALOG-034-008` and terminates the simulation before the
body can run. Break/continue use private flags scoped to the nearest loop, not silent
clamping or unbounded iteration. Event monitors inside structural loops still require
separate per-iteration state legalization and currently fail at the target boundary.
Ordinary event-free procedural lowering remains separately gated.

The target gate parses the emitted procedural grammar, including event arities,
lifecycle filters, nesting, expressions and allowed system tasks. This is structural
acceptance, not a general Verilog-A parser, numerical simulation, or solver proof.
Validation of this tranche must be recorded against its exact published head.

## Work still required for Increment 37 closure

- Qualify the new event-controlled target lowering and complete structural-loop monitor state legalization.
- Complete the native and source-to-target corner-case matrix, exact-head review and CI, implementation merge, post-merge checks, and separate immutable evidence closure.

The roadmap checkbox remains open. Simulator execution, full Verilog-AMS digital processes, and co-simulation scheduling are outside this increment's foundation implementation.

## Held-storage and target acceptance continuation

The restored candidate's held-storage API and bridge are integrated with the checked expression
AST and ordered-read emitter. `nodal.analog_held_read` resolves exactly one initialized root-local
real variable in the same module and proves that all writes are event-controlled. A variable
initializer may depend on a parameter but not on another mutable variable or terminal sample.
The source API requires the procedure to be complete before a continuous held read.

Sampling, continuous `transition` evaluation, and contributions share one analog target process.
The filter is not executed in the event body. Captured procedural reads are materialized at their
original positions, so a later write cannot change an earlier read's value. Monitor expressions
cannot move past an intervening write to observed storage. Static monitor loops use `genvar`;
bounded runtime loops validate the authored envelope instead of truncating or clamping a count.

The acceptance matrix now requires the separately compiled sample/hold and structured-control
source witnesses as well as all event arities. It checks both native parse/optimization paths,
held ownership failures, deterministic before/after target text, malformed target rejection,
and predecessor capability failures. Numerical solver execution is still not claimed.
