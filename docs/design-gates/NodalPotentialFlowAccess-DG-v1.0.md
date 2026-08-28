# Nodal potential and flow access design gate v1.0

**Status:** Approved  
**Scope:** compiler IR, access-function resolution, probe normalization, diagnostics, and Verilog-A/Verilog-AMS rendering  
**Public API:** unchanged at 0.3  
**Roadmap owner:** Increment 31

## Decision

Increment 31 makes potential and flow access a compiler-owned semantic
operation. Access-function spelling is resolved from the canonical potential or
flow nature of a continuous discipline; the backend must not infer semantics
from the conventional electrical names `V` and `I`.

The generic access identifiers `potential` and `flow` are always available as
target-neutral semantic spellings. A discipline-specific spelling such as `V`,
`I`, `Temperature`, or `HeatFlow` is legal only when it matches the `access`
attribute of the corresponding canonical nature after import resolution.

Every new access result is a real Nodal quantity:

```text
!nodal.quantity<"real", canonical-nature-dimension>
```

Increment 31 extends `nodal.nature` with an optional canonical `dimension`
attribute. The attribute becomes mandatory when the nature participates in a
new typed access. Existing pre-Increment-31 declarations without this attribute
remain valid for fixtures that do not construct a new typed access.

## Access forms

The compiler preserves four distinct source-semantic forms.

1. **Branch access** — access a named or already-normalized implicit
   `!nodal.branch`.
2. **One-terminal access** — form an unnamed branch between one conservative
   terminal and the canonical global reference for its discipline.
3. **Two-terminal access** — form an oriented unnamed branch from the first
   terminal to the second.
4. **Port-flow access** — observe the total flow entering a local module port.
   This is distinct from one-terminal branch access and is rendered with the
   Verilog-AMS angle-delimited form.

The existing `nodal.access` operation remains the canonical branch access and
retains legacy compatibility. Increment 31 adds source-semantic
`nodal.terminal_access` for one- and two-terminal forms,
`nodal.port_flow_access` for local total-port flow, and compiler-owned
`nodal.probe` records for normalized source-free probe classification.

New access operations carry both:

- `kind = "potential" | "flow"` as the target-neutral semantic class; and
- `function` as the authored generic or discipline-specific access identifier.

The verifier requires the two to agree. For legacy `nodal.access` operations
without `function`, the existing `kind` contract remains accepted so earlier
Increment 24, 25, and 30 fixtures continue to parse. No new producer emits the
legacy form.

## Nature and discipline resolution

For every access, the compiler shall:

- resolve the branch or terminal discipline through hash-pinned discipline
  imports;
- require the canonical discipline domain to be `continuous`;
- select its potential nature for potential access or its flow nature for flow
  access;
- reject flow access when the discipline has no flow nature;
- resolve nature imports before checking access identity, dimension, and
  tolerance provenance;
- require a canonical nature dimension and use it as the result quantity
  dimension;
- preserve canonical discipline, nature, access-function, source path, branch
  orientation, and reference provenance.

A two-terminal form requires compatible terminal disciplines. Its orientation
is exactly the authored operand order. A one-terminal form records the
discipline-specific global reference identity rather than inventing a
user-visible ground terminal. A flow access with two terminal operands rejects
identical endpoints.

## Probe semantics

Access values are dynamic observations and are never constant-folded.

A branch with no potential or flow contribution is source-free. If a
source-free branch is accessed:

- potential-only use classifies it as a potential probe;
- flow-only use classifies it as a flow probe;
- using both potential and flow on the same source-free branch is illegal.

The normalized `nodal.probe` record retains the classification and the
constraint intent required by later equation construction:

- a potential probe implies zero branch flow;
- a flow probe implies zero branch potential.

Increment 31 records this intent and validates probe legality. Increment 32
owns first-class equation/contribution accumulation and residual construction.

A branch that already has a source contribution may have both quantities read.
Increment 31 uses contribution presence only to distinguish source versus probe;
it does not define additive contribution semantics.

Port-flow access is an observational total-port query. It does not create a
branch probe, is legal only for a local boundary terminal with a flow nature,
and cannot appear as the target of a contribution.

## Validation and diagnostics

The initial stable diagnostics are:

- `NODAL-ACCESS-FORM-001` — invalid subject kind or operand count;
- `NODAL-ACCESS-DISCIPLINE-001` — missing, unresolved, incompatible, or
  non-continuous discipline;
- `NODAL-ACCESS-NATURE-001` — required potential/flow nature is absent or
  unresolved;
- `NODAL-ACCESS-FUNCTION-001` — access identifier does not match the resolved
  nature or generic semantic class;
- `NODAL-ACCESS-DIMENSION-001` — nature dimension is absent, non-canonical, or
  inconsistent with the result type;
- `NODAL-ACCESS-REFERENCE-001` — one-terminal global reference cannot be
  established;
- `NODAL-ACCESS-PORT-001` — invalid local port-flow access;
- `NODAL-PROBE-KIND-001` — source-free branch mixes potential and flow probes;
- `NODAL-PROBE-PROVENANCE-001` — generated probe classification is forged or
  inconsistent.

Diagnostics retain source range, hierarchy path, canonical discipline/nature,
access function, branch or terminal identity, and operand-form provenance.

## Backend boundary

Verilog-A and Verilog-AMS rendering occurs only after access verification and
normalization.

- branch access renders `function(branch)`;
- one-terminal access renders `function(node)`;
- two-terminal access renders `function(positive, negative)`;
- local port-flow access renders `function(<port>)`;
- generic authored `potential` or `flow` spellings remain generic;
- discipline-specific authored spellings remain exact when legal;
- backend hard-coding of `V` or `I` is prohibited for new typed access.

The backend must preserve operand order, one-terminal reference intent, port
delimiter form, and source mapping. Target rendering cannot repair an invalid
discipline, access identity, or quantity dimension.

## Compatibility and deferrals

The public Scala API remains v0.3. Increment 31 freezes compiler semantics and
machine-readable contracts; public Scala access syntax remains deferred to its
own API gate.

This increment does not implement general equations, contribution accumulation,
procedural analog assignment, hierarchical cross-instance probes, vector
branches, analysis-dependent access, noise, events, analog state, solver
lowering, or public standard-library component APIs. Increment 32 owns
first-class equations and contribution interaction. Later continuous-time
increments own state, events, analysis, and solver semantics.
