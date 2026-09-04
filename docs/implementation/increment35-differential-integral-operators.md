# Increment 35 — Differential and integral operators

**Status:** Closure candidate  
**Roadmap baseline:** revision 1.46  
**Predecessor:** validated Increment 34  
**Implementation PR:** #113  
**Accepted implementation head:** `d3410f6f64dc66df27d9c7f545c9e78f62695f2e`  
**Implementation merge:** `7763e1524f31e4c2c41b11acb200670c360f0fde`

## Implemented tranche

This tranche replaces the public `idt` placeholder and routes both `ddt` and `idt` through one continuous-operator construction path.

The construction snapshot records operator identity, owner, context, input and result dimensions, optional initial-condition path, stable integration-state identity, initialization policy, analysis applicability, and source span. The Scala-to-MLIR bridge serializes the same inventory and emits first-class `nodal.analog_ddt` and `nodal.analog_idt` operations for the executable legacy analog vertical slice.

The native dialect adds `nodal.analog_idt` and independently verifies the Increment 35 contract. Contracted operator identities must be owner-qualified and unique, and every `idt` state identity is derived from and owned by its operator. Older uncontracted `ddt` IR preserves the Increment 24 `NODAL-ANALOG-DDT-001` type-verification contract.

The analog constant pass may annotate a contracted `ddt` as zero only when its input is compiler-proven time invariant. It retains the authored operation and semantic identity. The pass removes no `idt` operation, and the verifier rejects fold or simplification metadata attached to `idt`.

The Verilog-A vertical slice accepts both operations and renders `ddt(input)`, `idt(input)`, and `idt(input, initial)`. A verified simplified derivative may render as `0.0` while its authored source-semantic operation remains in IR.

## Source semantics

- `ddt(D)` produces `D / time` and owns no state.
- `idt(D)` produces `D * time`, owns one stable state, and uses solver-selected initialization.
- `idt(D, initial)` uses fixed initialization; non-zero initial values must match `D * time`.
- Exact numeric zero is accepted as a dimension-polymorphic initial condition and recorded with the integral result dimension.
- Operators are legal in equations, contributions, and the legacy analog region only.
- Initial-equation, procedural, and out-of-region use is rejected.
- The bridge records the exact canonical six-analysis inventory.

## Verification matrix

Scala tests cover construction snapshots, inline literal payloads, source correlation, equation and contribution contexts, owner qualification, exact analysis applicability, deterministic unique state identities, fixed and solver-selected initialization, a typed non-zero initial condition, dimensional mismatch, and deterministic bridge serialization.

Native tests cover typed differential and integral dimensions, time-invariant derivative annotation, preservation of stateful integrals, compositional `ddt(idt(...))` Verilog-A rendering, legacy `ddt` compatibility, and stable rejection diagnostics for illegal context, missing or invalid contracts, owner mismatch, invalid dimensions, invalid analyses, invalid initial conditions, invalid state identity, duplicate operator identity, forged simplification, and attempted `idt` folding.

The accepted implementation head passed 25 pull-request workflows, including Core CI run `33890457304` and the dedicated Increment 35 matrix. Merge commit `7763e1524f31e4c2c41b11acb200670c360f0fde` then passed post-merge Core CI run `33892575717` and independent exact post-merge Increment 35 validation run `33892632854`.

A dedicated read-only Increment 35 workflow runs the Increment 24 compatibility checker, the validated Increment 34 predecessor checker, the Increment 35 repository and hardening tests, the full Scala build, the source-semantic witness, native compiler tests, invalid-diagnostic fixtures, backend rendering, and formatting checks. Temporary bootstrap workflows, staging fragments, repair scripts, and triggers are prohibited on accepted and closure-candidate heads.

## Honest boundary

Semantic equation and contribution contexts preserve continuous operators in the canonical construction and bridge inventories. First-class executable native operations are currently emitted by the established legacy analog vertical slice; full declarative residual-DAE lowering remains a later increment. Numerical solver behavior, state reset/reinitialization, event composition, inverse-operator cancellation, operator distribution, AC/noise-specific lowering, and full Verilog-AMS lowering are deferred.

## Closure state

Implementation and exact post-merge validation are complete. Draft closure PR #114 advances the roadmap and manifest through a separately checked closure-candidate state. The candidate deliberately leaves its own validation head and run unset until that exact pull-request head passes the complete workflow matrix.
