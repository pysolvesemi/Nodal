# Public API prototype outcome

Increment 10 evaluated source ergonomics with compile-only prototypes. Increment 11 has now frozen the accepted v0.1 source shape in `docs/design-gates/NodalPublicApi-DG-v0.1.md` and `core/scala/api/public-api-v0.1.json`.

The Scala files remain intentionally non-functional: they compile the source contract but do not yet elaborate hardware, build MLIR, emit HDL, or simulate behavior. Later increments implement those semantics behind the frozen API.

## Accepted outcome

| Concern | Frozen v0.1 form | Rejected primary alternative |
| --- | --- | --- |
| Module base | `Module` | `NodalComponent`, `NodalModule` |
| Parameters | `Param` and `.param(_.field, value)` | `Parameter`, string-keyed maps |
| HDL parameter policy | preserve parameter declarations and named overrides | silent Scala-only specialization |
| Parameterized widths | `UInt(width: Expr[Integer])`, `Bits(width: Expr[Integer])` | constructor-only `Int` widths |
| Analog block | `analog:` | explicit builder object |
| Contribution | `<+` | primary `contribute(...)` call |
| Potential and flow | `V(...)`, `I(...)` | verbose accessor objects |
| Ports | direct `input`, `output`, `inout` | mandatory wrapper bundles |
| Events | `on(cross(...))`, `on(timer(...))` | generalized event-builder hierarchy |
| Backend entry | `Nodal.emit(top, Backend.VerilogA/VerilogAMS, path)` | backend objects inside model source |
| External reuse | separate module using `import nodal.*` | privileged internal API or early `libraries/` implementation |

## Parameterized generation proof shape

The compile examples now distinguish HDL parameters from Scala elaboration values:

```scala
final class Adc extends Module:
  val width = param(12.integer)
  val code = output(UInt(width))
```

Typed hierarchy preserves named overrides:

```scala
private val adc = instance(new Adc).param(_.width, width)
```

The frozen contract requires the Verilog-A and Verilog-AMS backends to retain supported `Param` declarations and named overrides by default rather than generating one specialized module per value.

## Build check

Run the complete source experiment with:

```bash
./nodal core scala
```

The `__.compile` portion includes `examples.externalLibrary` and `examples.publicApiCandidates`. The external module depends only on `core.scala.api` and has no dependency on frontend, bridge, simulator, CLI, testkit, native compiler, or future `libraries/` code.

## Non-functional boundary

`CandidateApi.scala` currently contains inert placeholders only. It intentionally does not define ownership, deterministic naming, source locations, connectivity validation, expression semantics, event scheduling, domain checking, diagnostics, lowering, or backend behavior.

The controlling public contract is now `NodalPublicApi-DG-v0.1.md`; the older candidate gate remains historical evidence and does not control future implementation.
