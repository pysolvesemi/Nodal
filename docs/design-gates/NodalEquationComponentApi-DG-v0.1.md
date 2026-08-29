# Nodal Equation and Physical-Component API — v0.1

**Status:** Approved

**Scope:** public-api

**Increment:** 133

**Checkpoint:** Equation/component prerequisite for Increment 32

**Compatibility base:** Nodal public API v0.3

## Purpose

This gate freezes the source-visible contract required before Nodal may implement
first-class analog equations and contribution semantics. It is intentionally
solver-neutral and backend-neutral. Approval authorizes the compile-time surface
and the later Increment 32 source-semantic implementation; it does not authorize
equation normalization, causal orientation, residual/DAE construction, solving,
or Verilog-A/Verilog-AMS lowering.

## Binding surface

The public package remains `nodal`, imported with:

```scala
import nodal.*
```

The checkpoint freezes these declarations and blocks:

```scala
equations:
  equation(
    left,
    right,
    EquationOptions(
      id = Some(EquationId("ohm-law")),
      guard = None,
      analyses = AnalysisApplicability.All,
      continuity = ContinuityClass.C1
    )
  )

initialEquations:
  initialEquation(
    state,
    initialValue,
    EquationOptions(
      id = Some(EquationId("initial-state")),
      analyses = AnalysisApplicability.only(AnalysisKind.Initialization)
    )
  )

contributions:
  branch.flow <+ branch.potential / resistance
  contribution(
    branch.potential,
    sourceValue,
    ContributionOptions(id = Some(ContributionId("source")))
  )

analogProcedure:
  temporary := expression
```

`equations`, `initialEquations`, `contributions`, and `analogProcedure` are
distinct semantic regions. Their operations must not be interchanged merely
because a target language can print them using similar syntax.

## Unordered equation semantics

An equation is an unordered equality constraint, not an assignment.

- Both authored sides are preserved with source and hierarchy provenance.
- `EquationId`, when supplied, is a stable semantic identity and must be unique
  within its owning physical-component scope.
- Physical dimensions of both sides must be compatible.
- Guards, analysis applicability, and continuity metadata are semantic inputs.
- Canonical residual intent is conceptually `left - right = 0`; this does not
  grant permission to orient the equation or divide by an expression.
- Source order does not define priority or execution order.
- Reassociation, elimination, orientation, or division requires a separately
  approved and verified transformation.
- A target unable to represent the equation must reject it with a stable
  capability diagnostic instead of silently approximating it.

## Initial-equation semantics

`initialEquation` is an equation used only for initialization and consistent
initial-condition analysis.

- Initialization equations remain distinct from runtime procedural
  initialization.
- The analysis set must resolve to `Initialization`.
- Conflicting or duplicate initialization constraints are diagnosed.
- Initial equations may constrain user-declared state, operator-created state,
  parameters where permitted, and algebraic unknowns.
- Later initialization solving is owned by Increments 135 and 136.

## Additive contribution semantics

A contribution is additive participation in a conservative potential or flow
quantity, not a procedural assignment.

- `<+` and `contribution(...)` represent the same additive semantic category.
- Contribution targets are potential/flow accesses or equivalent branch
  accesses approved by the discipline contract.
- Contributions accumulate independently of source order.
- `ContributionId`, when supplied, is stable and unique in its owner.
- Guards, analyses, continuity, physical dimensions, branch orientation, and
  source provenance are retained.
- Explicit equations, generated conservative-connection equations, and
  contributions coexist; no category implicitly replaces another.
- Duplicate source text does not imply duplicate semantic identity.
- Contributions are not converted to assignments before topology and residual
  construction.

## Procedural-assignment separation

`:=` is an ordered procedural assignment and is not an equation or
contribution.

- It is legal only in an approved procedural region.
- It does not add a residual equation or a conservative contribution.
- Increment 33 owns procedural variables, assignment ordering, initialization,
  scopes, and read-before-write diagnostics.
- Increment 32 must reject procedural assignment in equation/contribution
  regions and reject equations/contributions in procedural-only contexts.

## Branch and terminal contract

A `Branch[D]` has explicit positive-to-negative orientation.

- `potential` is measured positive relative to negative.
- `flow` is positive from the positive terminal into the branch.
- Named and implicit branches preserve stable identity.
- Reversing terminals creates the reverse orientation; it is not an aliasing
  rewrite.
- Conservative connection sets generate potential-equality and signed
  flow-conservation equations separately from user equations.

## Physical components and local balance

`PhysicalComponentContract` freezes partial versus concrete ownership.

- `Partial` components may defer complete topology and balance to a concrete
  descendant.
- `Concrete` components must satisfy their declared topology and local-balance
  policy.
- `localBalance` records a stable balance identity and the participating
  terminal flows.
- `TopologyOwnership.Extensible` allows legal extension; `Complete` requires the
  component to close its declared topology.
- Replaceable components preserve their public terminal, parameter, equation,
  and validity contracts.

## Structural parameters

`StructuralParameter` is distinct from an ordinary analog parameter.

- It may affect topology, component count, equation count, shape, or rank only
  inside its declared finite `StructuralEnvelope`.
- Dynamic analog values cannot become structural parameters.
- Envelope-dependent unsupported topology changes are diagnosed before target
  execution.
- Structure generation remains deterministic and source mapped.

## Stable diagnostics

The binding diagnostic inventory is
`core/scala/api/public-api-continuous-time-diagnostics-v0.1.json`. At minimum it
covers region misuse, dimension mismatch, duplicate identities, invalid
contribution targets, incomplete concrete components, invalid structural
envelopes, unsafe orientation/division, and unsupported target capability.

## Accepted alternatives

The implementation may use function calls rather than symbolic operators, and
may lower blocks through any internal representation, provided the observable
contract above and stable source identities are preserved.

A backend may print an equation in target-native form or a proven equivalent
residual form. Such printing does not change the source-semantic category.

## Rejected alternatives

The following are rejected:

- treating equations as left-to-right assignments;
- using source order as equation or contribution priority;
- lowering `<+` as last-writer-wins assignment;
- silently orienting equations for convenience;
- dividing by a possibly zero expression during legalization;
- treating conservative connections as directional signal flow;
- merging equations or noise/contribution identities from textual similarity;
- allowing runtime values to change topology without an explicit capability;
- silently dropping unsupported equations or contributions.

## Compatibility impact

This is an additive extension to public API v0.3. Existing digital and earlier
analog APIs retain their source and binary contracts. The new compile-time
surface is frozen at v0.1; later incompatible spelling changes require a new
version and migration note.

## Required tests

- internal and external public-API consumers compile;
- type-negative fixtures reject non-real equations/contributions,
  cross-discipline branches, and non-structural parameter kinds;
- semantic fixtures cover every stable diagnostic contract;
- formatting, package visibility, contribution policy, Scala compilation,
  native tests, and prior increment workflows remain green;
- temporary materializers and writable workflows are absent from the accepted
  head.

## Approval evidence

Approved by the project owner through the standing increment approval and the
explicit instruction on August 29, 2026 to finish and merge Increment 133.

## Deferred implementation

Frontend recording, deterministic equation/contribution identity allocation,
region verification, topology expansion, residual/DAE construction,
initialization solving, target legalization, simulation, and backend emission
remain owned by later increments.
