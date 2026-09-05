# Nodal time and waveform operators design gate v0.1

**Increment:** 36  
**Status:** Approved  
**Scope:** public-api

## Public surface

```scala
transition(value)
transition(value, delay)
transition(value, delay, rise)
transition(value, delay, rise, fall)
transition(value, delay, rise, fall, timeTolerance)
slew(value)
slew(value, positiveRate)
slew(value, positiveRate, negativeRate)
absdelay(value, delay)
absdelay(value, delay, maximumDelay)
abstime
boundStep(maximumStep)
```

The existing four-argument `transition` signature is retained. All expression operators return `Expr[Real]`; `boundStep` returns `Unit`. Real unary negation now records the existing native `analog_neg` operation, so `-positiveRate` remains a real signed expression rather than an inert candidate node.

## Reference and target contract

The reference is the [Accellera Verilog-AMS 2023 LRM](https://www.accellera.org/images/downloads/standards/v-ams/VAMS-LRM-2023.pdf), sections 4.5.7–4.5.9, 4.5.14–4.5.15, and 9.17.2.

Transition timing arguments are non-negative. Omission is retained rather than replacing target defaults. Slew's positive limit is strictly positive and its negative limit strictly negative; an omitted negative limit is symmetric, and no limits means pass-through. Absolute delay is positive; without a maximum it samples delay initially, while a maximum permits changing delay with target clamping. A step request is non-negative, limits only the next transient step, and returns no value. Zero requests retain the simulator's minimum-step behavior. `$abstime` is measured in seconds. Nodal emits these target intrinsics rather than implementing their numerical algorithms.

## Dimensions and diagnostics

Inputs and expression results must be real. A waveform preserves its input dimension. Delay, transition timing, tolerance, and step operands require time. Slew rates require input dimension divided by time. The `abstime` result is time.

Only a compiler-proven, dimensionless exact zero can fill a time slot without a time unit. A zero volt value is not a zero-second value. Negative, zero-where-positive-required, and non-finite known values are rejected. Unknown runtime values are not replaced with parameter defaults; the target intrinsic remains responsible for runtime range enforcement. The maximum delay must be a structurally constant expression in this Nodal increment.

| Diagnostic | Contract |
|---|---|
| `NODAL-ANALOG-036-001` | Illegal construction or native context |
| `NODAL-ANALOG-036-002` | Operator, arity, identity, or ownership mismatch |
| `NODAL-ANALOG-036-003` | Real type, dimension, or unit mismatch |
| `NODAL-ANALOG-036-004` | Known non-finite or out-of-range value |
| `NODAL-ANALOG-036-005` | Unproven or forged continuity |
| `NODAL-ANALOG-036-006` | Invalid state, ordering, folding, or simplification |
| `NODAL-ANALOG-036-007` | Non-constant maximum delay |
| `NODAL-ANALOG-036-008` | Missing or non-canonical analysis inventory |

## Continuity and contexts

`transition` requires a proven constant or piecewise-constant input. The native verifier independently recognizes constant-armed selectors as piecewise constant. The source surface does not yet provide event-held variable continuity; this is an explicit Increment 37 integration boundary. Unknown continuity cannot be overridden by a user-supplied string. Slew with limits produces a continuous result; slew without limits preserves the input classification. Delay output is conservatively unknown because a changing delay can introduce discontinuities.

Construction is allowed in unconditional `analog`, `equations`, and `contributions` regions. Initial-equation, analog-procedural, candidate initial, event-triggered, and candidate conditional contexts are rejected. Native operators must be direct children of `nodal.analog`. General procedural composition is not silently accepted.

## Identity and evaluation

Each operator has an owner-qualified stable identity and source origin. Transition, slew, and delay have exactly one derived state identity, including currently constant inputs and no-limit slew. Time observation owns no history. Step requests own no history and have no SSA result.

All five operations survive the constant-folding, canonicalization, and common-subexpression passes. The Verilog-A emitter declares collision-free real temporaries and evaluates every authored filter once in region order, even when unused. Every later reference uses that same temporary. Step requests are emitted even without contributions. No filter invocation is duplicated by textual inlining.

The native verifier reconstructs legacy f64 dimensions from definitions and canonical unit metadata. It does not accept a waveform's claimed operand dimensions as independent proof. Typed quantities are verified without erasing their dimensions first.

## Validation and deferred boundary

Acceptance requires positive and negative Scala construction tests, deterministic source maps and bridge serialization, real public-source-to-native compilation, native negative diagnostics before and after passes, state/effect retention, shared-state emission, collision avoidance, full repository regressions, exact-head CI, merge validation, and separate evidence closure.

Equation and contribution regions retain source-semantic inventories; their full residual DAE lowering is not introduced here. Numerical solver execution, event composition, general procedural waveform composition, analysis-specific AC/noise lowering, and full Verilog-AMS lowering remain deferred.

## Approval evidence

Approved by the project owner's explicit request to implement Increment 36 and standing approval for Nodal increments. Approval authorizes implementation; it does not substitute for executable validation or evidence closure.
