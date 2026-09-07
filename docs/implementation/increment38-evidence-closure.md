# Increment 38 — Accepted mathematical and simulator function evidence

**Status:** Validated implementation; this separate evidence change has its own PR/CI gate.
**Implementation:** PR #126
**Accepted head:** `05047f4bb511ef19a812de6e8f08fc2e709cb5c8`
**Accepted tree:** `486b0cc6912005a427862af7592a2185c587705e`
**Implementation merge:** `e593a60eb6d6fdb9a505d0762c859934e041b92d`
**Post-merge Core CI:** `34090729148`
**Post-merge Increment 38:** `34090729146`

All 28 applicable PR workflows passed for the accepted head, including Core CI
`34077343955` and Increment 38 `34077344014`. Exact-head push qualification
`34077317999` passed independently. The downloaded qualification archive was
SHA-256 verified and its source independently reconstructed to the accepted Git tree.
The independent Codex review completed on `05047f4` without findings: summary
comment `5564322833`, positive reaction `491907273`, and no unresolved threads.

The exact implementation merge passed its own Core CI and Increment 38 workflows.
Its four retained Scala-derived MLIR and emitted Verilog-A witnesses are byte-identical
to the accepted-head outputs. Both the human record and manifest reference
[`increment38-accepted-evidence.json`](increment38-accepted-evidence.json), whose
SHA-256 is pinned by the repository checker. The evidence PR must pass its own
required checks; this record does not claim an anticipated evidence-PR merge.

## Executable coverage

The accepted qualification passed 123 Scala tests, 128 native CTest tests, and
112 native/source matrix cases. A local replay of the retained compiler tools
passed the same 112 cases; the compiler Python suite passed 305 tests. These
suites overlap and must not be added as an independent-test total. The separate
evidence change adds acceptance-state mutation coverage to the compiler Python suite.

Coverage includes every registry entry, numerical constant evaluation against an
independent Python math oracle, symbolic calls, closed analysis identities, arity,
real/Boolean kinds, physical dimensions, provable domain failures, nonfinite results,
forged query and parent fold claims, repeated normalization, backend spellings,
ordered event-controlled reads, source retention, and rejection without partial HDL.
Parameter defaults are not constant evidence. Constant parent expressions may
legitimately fold; spelling is checked on symbolic calls instead of requiring dead
constant calls to survive optimization.

## Reproduce the source-to-target demonstration

Source: `examples/continuousTimeApi/src/nodal/increment38fixture/Increment38ConstructionCheck.scala`.
The internal test driver compiles this separately authored public-API source; it does
not handwrite the demonstration MLIR or Verilog-A.

```sh
./nodal core scala
./nodal core native
mkdir -p .validation/increment38
./mill -i core.scala.testkit.test.runMain nodal.internal.testkit.Increment38MlirCheck "$PWD/.validation/increment38/public.mlir"
python3 tests/compiler/fixtures/increment38/run_native_matrix.py --nodalc "$PWD/out/native/release/bin/nodalc" --translate "$PWD/out/native/release/bin/nodal-translate" --source "$PWD/.validation/increment38/public.mlir"
out/native/release/bin/nodal-translate --nodal-to-verilog-a .validation/increment38/public.mlir > .validation/increment38/public.va
out/native/release/bin/nodal-translate --nodal-to-verilog-a .validation/increment38/public.mlir.events.mlir > .validation/increment38/events.va
```

Backend: Verilog-A; default check profile, scalar-or-flat layout, safe-inline
materialization and semantic naming. The workflow artifact is
`increment38-source-target-evidence`; its output paths are `public.va` and `events.va`.
Generated build outputs are not committed. The following blocks reproduce the
actual retained source/output rather than hypothetical target code.

### Continuous mathematical expressions

```scala
import nodal.*

final class AnalogMathSource extends Module:
  val positive = inout(Electrical)
  val negative = inout(Electrical)
  val gain = param(2.0.real)
  analog:
    val input = V(positive, negative) / 1.0.V
    val nonlinear = AnalogMath.tanh(input * gain)
    val scaled = AnalogMath.sqrt(AnalogMath.abs(nonlinear)) * 1.0.V
    V(positive, negative) <+ scaled
    val _ = AnalogMath.log10(100.0.real)
    val _ = AnalogMath.sqrt(4.0.V * 4.0.V)
    val _ = AnalysisContext.active(AnalysisKind.Transient) &&
      !AnalysisContext.active(AnalysisKind.Ac) && true.B
```

Actual generated Verilog-A (`public.va`, complete module; header omitted):

```verilog
module AnalogMathSource(negative, positive);
  inout negative, positive;
  electrical negative, positive;
  parameter real gain = 2;

  analog begin
    V(positive, negative) <+ (sqrt(abs(tanh(((V(positive, negative) / 1) * gain)))) * 1);
  end
endmodule
```

The voltage is normalized before dimensionless mathematics; the gain remains a
symbolic parameter. The unused constant and query expressions in the source do
not require emitted statements. This is an expression-lowering witness, not a
claim of numerical convergence for a standalone physical circuit.

### Runtime analysis selection inside an event handler

```scala
import nodal.*

final class AnalogMathEventSource extends Module:
  val positive = inout(Electrical)
  val negative = inout(Electrical)
  val held = variable(Real, AnalogMath.sqrt(4.0.V * 4.0.V))
  analogProcedure:
    on(initialStep or timer(0.0.ns, 1.0.ns)):
      analogConditional:
        analogWhen(AnalysisContext.active(AnalysisKind.Transient)):
          held := AnalogMath.abs(V(positive, negative))
        analogOtherwise:
          held := 0.0.V
  analog:
    V(positive, negative) <+ transition(held, 0.0.ns, 1.0.ns)
```

Actual generated Verilog-A (`events.va`, complete module; header omitted):

```verilog
module AnalogMathEventSource(negative, positive);
  inout negative, positive;
  electrical negative, positive;
  real event_AnalogMathEventSource_held = sqrt((4.0 * 4.0));
  real waveform_0;

  analog begin
    begin : event_AnalogMathEventSource_procedure
      @(initial_step or timer(0.0, 1.0E-9)) begin
        if (analysis("tran")) begin
          event_AnalogMathEventSource_held = abs(V(positive, negative));
        end
        else begin
          event_AnalogMathEventSource_held = 0.0;
        end
      end
    end
    waveform_0 = transition(event_AnalogMathEventSource_held, 0, 1e-09);
    V(positive, negative) <+ waveform_0;
  end
endmodule
```

The initializer uses a unit-correct square root; event-controlled absolute value
preserves voltage units. The transient-analysis query remains runtime-dependent
and the resulting held voltage feeds the existing continuous transition operator.

## Retained witness hashes

| Artifact path | SHA-256 |
| --- | --- |
| `public.mlir` | `837e6cc5444e67701c959d8c65689bd6c2d446165a41c1f5d44b4e59598550d1` |
| `public.mlir.events.mlir` | `626ecf1ef30d2040513855e3ad9803ba0f486818fdc424edb9639d0dda5c5a00` |
| `public.va` | `e1d02568a7a1b900e57c111bdc46a0c51f928fcdf6827f88f640263f63c1be30` |
| `events.va` | `97cf507ec0f0fd84791fe9291d86deee4d1eec506801b9063d670360a21b4d1f` |

## Scope and closure safeguards

This closes the version-1 registry's 24 pure real mathematical functions and six
analysis queries in the supported compiler/Verilog-A profile. It does not claim
numerical simulator execution, general Verilog-AMS qualification, ordinary event-free
procedure lowering, stateful `limexp`, simulator tasks, noise, Laplace/Z, user-defined
functions, or environment access. Those remain separately scoped.

The checker rejects altered accepted or predecessor evidence, inconsistent manifest
references, disabled semantic obligations, unapproved scope expansion, premature or
ambiguous roadmap checkmarks, missing native registration, missing public witnesses,
and missing demonstrations. Roadmap revision 1.49 is a lower bound, not an upper bound
that blocks later increments. The historical Increment 37 acceptance identities remain
unchanged. Increment 39 — Noise operators is the next foundation increment.
