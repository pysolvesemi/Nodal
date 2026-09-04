# Increment 35 — Differential and integral operators

**Status:** In progress  
**Roadmap baseline:** revision 1.45  
**Predecessor:** validated Increment 34

## Implemented tranche

This tranche replaces the public `idt` placeholder and routes both `ddt` and `idt` through one continuous-operator construction path.

The construction snapshot now records operator identity, owner, context, input and result dimensions, optional initial-condition path, stable integration-state identity, initialization policy, analysis applicability, and source span. The Scala-to-MLIR bridge serializes the same inventory and emits first-class `nodal.analog_ddt` and `nodal.analog_idt` operations for the executable legacy analog vertical slice.

The native dialect adds `nodal.analog_idt` and independently verifies the Increment 35 contract. Contracted operator identities must be owner-qualified and unique, and every `idt` state identity is derived from and owned by its operator. Older uncontracted `ddt` IR preserves the Increment 24 `NODAL-ANALOG-DDT-001` type-verification contract.

The existing analog constant pass may annotate a contracted `ddt` as zero only when its input is compiler-proven time invariant. It retains the authored operation and its semantic identity. The pass removes no `idt` operation, and the verifier rejects any fold or simplification metadata attached to `idt`.

The Verilog-A vertical slice explicitly accepts both operations and renders `ddt(input)`, `idt(input)`, and `idt(input, initial)`. A verified simplified derivative may render as `0.0` while its authored source-semantic operation remains in IR.

## Source semantics

- `ddt(D)` produces `D / time` and owns no state.
- `idt(D)` produces `D * time`, owns one stable state, and uses solver-selected initialization.
- `idt(D, initial)` uses fixed initialization; non-zero initial values must match `D * time`.
- Exact numeric zero is accepted as a dimension-polymorphic initial condition and recorded with the integral result dimension.
- Operators are legal in equations, contributions, and the legacy analog region only.
- Initial-equation, procedural, and out-of-region use is rejected.
- The bridge records the exact canonical six-analysis inventory.

## Verification matrix

Scala tests cover construction snapshots, source correlation, equation and contribution contexts, owner qualification, exact analysis applicability, deterministic unique state identities, fixed and solver-selected initialization, a typed non-zero initial condition, dimensional mismatch, and deterministic bridge serialization.

Native tests cover typed differential and integral dimensions, time-invariant derivative annotation, preservation of stateful integrals, Verilog-A rendering, legacy `ddt` compatibility, and stable rejection diagnostics for illegal context, missing or invalid contracts, owner mismatch, invalid dimensions, invalid analyses, invalid initial conditions, invalid state identity, duplicate operator identity, forged simplification, and attempted `idt` folding.

A dedicated read-only Increment 35 workflow runs the Increment 24 compatibility checker, the validated Increment 34 predecessor checker, the Increment 35 repository and hardening tests, the full Scala build, the source-semantic witness, native compiler tests, invalid-diagnostic fixtures, backend rendering, and formatting checks. Temporary bootstrap workflows, staging fragments, repair scripts, and triggers are prohibited on the accepted head.

## Honest boundary

Semantic equation and contribution contexts preserve continuous operators in the canonical construction and bridge inventories. First-class executable native operations are currently emitted by the established legacy analog vertical slice; full declarative residual-DAE lowering remains a later increment. Numerical solver behavior, state reset/reinitialization, event composition, and AC/noise-specific lowering are also deferred.

## Closure state

The roadmap item and manifest remain open. They must not be marked validated until the implementation PR is merged, exact post-merge validation succeeds, and a separate evidence-closure PR records immutable run and commit identities.
