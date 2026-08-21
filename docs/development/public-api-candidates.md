# Public API candidate prototypes

> **Superseded by Increment 11:** the accepted surface is frozen by [`NodalPublicApi-DG-v0.1.md`](../design-gates/NodalPublicApi-DG-v0.1.md). This page remains the comparison record.

Increment 10 evaluates source ergonomics before Nodal commits to a public contract. The files compile, but they do not elaborate hardware, build MLIR, emit HDL, or simulate behavior.

## What is being tested

The candidate matrix exercises analog primitives, hierarchy, parameter override, analog events, digital ports, ADC/DAC boundaries, and a mixed-signal behavioral shape. It also compiles an external reusable module in a separate build module that depends only on `core.scala.api`.

Run the complete experiment with:

```bash
./nodal core scala
```

The `__.compile` portion includes both `examples.externalLibrary` and `examples.publicApiCandidates`. The external module has no dependency on frontend, bridge, simulator, CLI, testkit, native compiler, or future `libraries/` code.

## Candidate comparison

| Concern | Candidate retained for compilation | Alternative deferred or rejected |
| --- | --- | --- |
| Module base | `Module` | `NodalComponent`, `NodalModule` |
| Parameters | `Param` and `.param(_.field, value)` | `Parameter`, string-keyed maps |
| Analog block | `analog:` | explicit builder object |
| Contribution | `<+` | primary `contribute(...)` call |
| Potential and flow | `V(...)`, `I(...)` | verbose accessor objects |
| Ports | direct `input`, `output`, `inout` | mandatory wrapper bundles |
| Events | `on(cross(...))`, `on(timer(...))` | generalized event-builder hierarchy |
| External reuse | separate module using `import nodal.*` | privileged internal API or early `libraries/` implementation |

## Non-functional boundary

`CandidateApi.scala` contains inert placeholders only. It intentionally does not define ownership, deterministic naming, source locations, connectivity validation, expression semantics, event scheduling, domain checking, diagnostics, lowering, or backends. Those decisions remain gated by later roadmap increments.

Do not treat successful compilation as semantic approval. The controlling record is `docs/design-gates/NodalPublicApiCandidates-DG-v0.1.md`, and Increment 11 may change or reject any candidate before freezing v0.1.
