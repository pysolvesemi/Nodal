# Nodal differential and integral operators design gate v0.1

**Increment:** 35  
**Status:** Implementation in progress

## Purpose

Increment 35 makes `ddt` and `idt` source-semantic operators rather than backend spellings. Their physical dimensions, legal construction context, solver state, initialization policy, analysis applicability, source origin, and simplification provenance cross the Scala-to-MLIR boundary explicitly.

## Public surface

```scala
val slope = ddt(value)
val accumulated = idt(value)
val initialized = idt(value, initialValue)
```

`ddt(value)` has no owned state. `idt(value)` owns one stable integration state and uses solver-selected initialization. `idt(value, initialValue)` owns the same form of state and uses a fixed authored initial condition.

## Dimensional contract

For input dimension `D`:

- `ddt` returns `D / time`;
- `idt` returns `D * time`;
- a non-zero `idt` initial condition must have the result dimension;
- exact numeric zero is dimension-polymorphic at the source boundary and is canonicalized to the result dimension.

Unknown or incompatible dimensions are rejected before lowering. The native verifier independently checks canonical exponent signatures.

## Legal contexts

The operators are legal only in declarative continuous-time regions:

- `equations {}`;
- `contributions {}`;
- the legacy `analog {}` compatibility region.

They are rejected outside a continuous-time region, in `initialEquations {}`, and in `analogProcedure {}`. This prevents hidden dynamic state from entering initialization-only or ordered procedural semantics.

## State and initialization

Every `idt` operation carries:

- a stable `operator_id`;
- exactly one derived `state_id`, equal to `<operator_id>.state`;
- `initialization = "fixed"` with two operands, or `"solver-selected"` with one operand;
- an `initial_dimension` only for fixed initialization.

No implicit zero is invented for the one-operand form. `ddt` carries `initialization = "none"` and may not carry a state identity.

## Analysis applicability

The source bridge records an explicit canonical analysis set: initialization, operating point, DC, transient, AC, and noise. Native IR requires supported unique analysis names and transient applicability. Analysis-specific solver lowering remains deferred.

## Simplification

Only one Increment 35 simplification is enabled: the derivative of a compiler-proven time-invariant expression may be annotated as zero.

The authored `nodal.analog_ddt` operation remains in IR. The annotation records:

- `nodal.simplification_rule = "ddt-time-invariant-zero"`;
- the preserved result dimension;
- `nodal.simplification_provenance = "increment35"`;
- a typed zero value.

Dynamic inputs and forged annotations are rejected. `idt` is stateful and may never carry constant-fold or continuous-simplification attributes. Inverse cancellation, distribution, reassociation, and state erasure are prohibited.

## Native IR contract

`nodal.analog_ddt` and `nodal.analog_idt` carry a versioned `operator_contract = "increment35"`, identity, owner, legal context, input and result dimensions, initialization policy, analyses, and source-correlated bridge inventory. Older accepted `ddt` IR without the versioned contract keeps its legacy type-only verification behavior.

## Stable diagnostics

| Code | Meaning |
|---|---|
| `NODAL-ANALOG-035-001` | illegal construction or IR context |
| `NODAL-ANALOG-035-002` | malformed operator identity, arity, ownership, or contract |
| `NODAL-ANALOG-035-003` | invalid real type or differential/integral dimension |
| `NODAL-ANALOG-035-004` | invalid fixed initial condition |
| `NODAL-ANALOG-035-005` | invalid state identity or initialization policy |
| `NODAL-ANALOG-035-006` | invalid analysis applicability |
| `NODAL-ANALOG-035-007` | unsafe or forged `ddt` simplification |
| `NODAL-ANALOG-035-008` | attempted folding or simplification of stateful `idt` |

## Deferred boundary

Increment 35 does not implement inverse `ddt`/`idt` cancellation, operator distribution, event composition, state reset/reinitialization, residual DAE assembly, numerical solver execution, analysis-specific AC/noise lowering, or full Verilog-AMS lowering.
