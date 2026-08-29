# Public API v0.3 to Continuous-Time API v0.1

Continuous-time API v0.1 is an additive extension of the frozen Nodal public API
v0.3. Existing digital source remains valid and does not need migration.

## New import

The default import is unchanged:

```scala
import nodal.*
```

## Equation-oriented modeling

Use `equations` and `equation(...)` for unordered constraints:

```scala
equations:
  equation(
    path.potential,
    resistance * path.flow,
    EquationOptions(id = Some(EquationId("ohm-law")))
  )
```

Do not express an equation with `:=`. Procedural assignment remains a separate
Increment 33 contract.

## Contributions

Use `<+` or `contribution(...)` for additive potential/flow participation:

```scala
contributions:
  path.flow <+ path.potential / resistance
```

Contribution order is not priority.

## Components and structure

Use `PhysicalComponentContract` to declare partial/concrete ownership,
`localBalance` for component-local conservation evidence, and
`structuralParameter` only for finite, declared topology/equation/shape effects.

## State and analyses

Use `analogState` with an explicit initialization policy. Use
`AnalysisContext` and `EnvironmentContext` rather than uncontrolled globals.
Noise sources require stable `NoiseId` values and explicit correlation.

## Execution status

Increment 133 freezes compile-time contracts only. Existing runtime, simulator,
solver, and backend behavior does not change. Later increments will implement
the frozen semantics without requiring source changes inside this v0.1 surface.
