# Increment 41 — Accepted user-defined analog-function evidence

**Status:** Validated compiler/Verilog-A profile

## Accepted implementation and merge

Implementation PR #132 was accepted at `3ef30a40e2f81c5938e37cfbad7ad38c9146c3c2`
with source tree `37b88463606400f43e4090a9c631745cd2b88ad6`. All 31 PR workflows
passed before merge. Required Core CI `35532859513` and dedicated Increment 41
qualification `35532859216` passed on the exact accepted tree. The tested PR merge
was `66546465a4ddd1bb0e34cda14130a26a9ff50499`; its tree matched the accepted
head exactly.

The actual squash merge is `4d979879d9ee1edd2413068f33ea1a2e4b357e6f`, with the same
tree and preserved `dev` parent `a69c6ba85f4f5be5b397ac8b9ddabfbd16944b8d`. Exact
post-merge Core CI `35538979449` and Increment 41 `35538979384` passed. The
post-merge compatibility runs for Increments 36–40 (`35538979493`, `35538979458`,
`35538979351`, `35538979442`, `35538979365`) also passed.

This separate closure records acceptance and guards it with regression tests; it
does not change production compiler behavior. The immutable machine-readable
record is `docs/implementation/increment41-accepted-evidence.json`, SHA-256
`177082f5edac85be80541384d94529c6fd8f82fe1cdbc39cd87b771398d81f32`. Increment 40 accepted evidence remains pinned as the predecessor.

## Review and capability boundaries

The direct implementation-agent review covered the exact accepted head and found
no additional blocking defect after the source-map collision repair and forged-call
attribute hardening. The only review thread is resolved and outdated. This is
not independent automated review.

The accepted profile is module-local pure scalar Real/Integer functions with
explicit typed and dimensioned inputs, initialized immutable locals, one total
return, nested nonrecursive calls, and deterministic dependency-ordered
Verilog-A function emission. Recursion, overloads, implicit captures, mutable
locals, early returns, state/time/analysis/topology effects, unsupported argument
shapes, cross-module calls, implicit numeric conversion, forged call attributes,
and invalid constant subgraphs are rejected. Native verification, operation-identity
rendering, source-map namespace isolation, target reparse, and no-partial-HDL
failure handling are part of the accepted compiler contract.

This is compiler/structural qualification, not numerical analog simulation.
General Verilog-AMS behavior, synthesis, output/inout/string/array arguments,
procedural/equation/event calls, cross-module calls, interprocedural optimization,
mutable locals, loops, and early returns remain outside this profile.

## Executed validation and artifact identities

The exact accepted PR source passed 146 Scala tests, 134 CTest targets, five
dedicated Increment 41 Python tests, 61 native/public-source/target cases, and the
broader exact-head matrix containing 348 compiler Python regression tests. Counts
overlap and are not additive.

The exact implementation merge independently passed fresh Core Scala/native/contracts
jobs and the dedicated Increment 41 workflow. Its retained artifact records the
actual merge commit and accepted tree, and reproduces the same public MLIR and
Verilog-A byte-for-byte.

| Artifact | ID | Archive SHA-256 |
|---|---:|---|
| Accepted PR source/target | 10612428754 | `94a727cd99a7b14f3384ab8fe4f6111df87849a57bac81f02e7472fbc7d15457` |
| Exact implementation merge | 10613274703 | `1aff854ece06fed827eb1edb1fcb155426e9d4c0537cdb0666249a435340f219` |

Witness hashes, identical in the accepted PR and implementation-merge artifacts:

- `public.mlir`: `63d08015b4ac3ad1133f250f7de030fad61c8ef9e4f0467b34a55d638200df0a`
- `public.va`: `a09529f6f2e80ad8d7e26f5c1e5037d78628536ea83f797de4f5b1e2cd1c254f`

## Public Scala demonstration

Source: `examples/continuousTimeApi/src/nodal/increment41fixture/Increment41ConstructionCheck.scala`.
The excerpt is the complete public module, excluding the package declaration and
standalone runner.

```scala
import nodal.*

/** Public-only source witness; qualification retains actual native output separately. */
final class FunctionAmplifier extends Module:
  val stimulus = inout(Electrical)
  val amplified = inout(Electrical)
  val shaped = inout(Electrical)
  val reference = inout(Electrical)
  val gain = param(2.0.real)
  val mode = param(1.integer)

  val affine = AnalogFunction("affineSignal", Real, PhysicalDimension.Voltage): f =>
    val signal = f.input("signal", Real, PhysicalDimension.Voltage)
    val factor = f.input("factor", Real)
    val offset = f.input("offset", Real, PhysicalDimension.Voltage)
    val scaled = f.local("scaled", signal * factor)
    scaled + offset

  val limited = AnalogFunction("limitSignal", Real, PhysicalDimension.Voltage): f =>
    val signal = f.input("signal", Real, PhysicalDimension.Voltage)
    val ceiling = f.input("ceiling", Real, PhysicalDimension.Voltage)
    val adjusted = f.local("adjusted", affine(signal, 1.0.real, 0.0.V))
    f.select(adjusted > ceiling, ceiling, adjusted)

  val incrementCount = AnalogFunction("incrementCount", Integer): f =>
    val count = f.input("count", Integer)
    val nextCount = f.local("nextCount", f.add(count, 1.integer))
    nextCount

  val chooseGain = AnalogFunction("chooseGain", Real): f =>
    val selector = f.input("selector", Integer)
    val low = f.input("low", Real)
    val high = f.input("high", Real)
    f.select(f.lessThan(selector, 2.integer), low, high)

  val half = AnalogFunction("halfSignal", Real, PhysicalDimension.Voltage): f =>
    val signal = f.input("signal", Real, PhysicalDimension.Voltage)
    // The emitted literal expression must remain real division, not 1 / 2.
    signal * (1.0.real / 2.0.real)

  val curve = AnalogFunction("curveSignal", Real): f =>
    val signal = f.input("signal", Real)
    val square = f.local("square", signal * signal)
    AnalogMath.sqrt(square + 1.0.real)

  analog:
    val selectedGain = chooseGain(incrementCount(mode), 1.0.real, gain)
    val linear = affine(V(stimulus, reference), selectedGain, 0.0.V)
    V(amplified, reference) <+ half(limited(linear, 2.0.V))
    val curved = curve(V(stimulus, reference) / 1.0.V) * 1.0.V
    V(shaped, reference) <+ laplaceNd(curved, Seq(1.0.real), Seq(1.0.real, 1.0e-3.s))
```

## Actual generated Verilog-A

Output: `public.va` in the retained artifacts. Only the backend header comment is
omitted; declarations, identifiers, expressions and statements are unchanged.

```verilog
`include "constants.vams"
`include "disciplines.vams"

module FunctionAmplifier(amplified, reference, shaped, stimulus);
  inout amplified, reference, shaped, stimulus;
  electrical amplified, reference, shaped, stimulus;
  parameter real gain = 2;
  parameter integer mode = 1;
  real transfer_0;

  analog function real affineSignal;
    input signal, factor, offset;
    real signal;
    real factor;
    real offset;
    real scaled;
    begin
      scaled = (signal * factor);
      affineSignal = (scaled + offset);
    end
  endfunction

  analog function real chooseGain;
    input selector, low, high;
    integer selector;
    real low;
    real high;
    begin
      chooseGain = ((selector < 2) ? low : high);
    end
  endfunction

  analog function real curveSignal;
    input signal;
    real signal;
    real square;
    begin
      square = (signal * signal);
      curveSignal = sqrt((square + 1.0));
    end
  endfunction

  analog function real halfSignal;
    input signal;
    real signal;
    begin
      halfSignal = (signal * (1.0 / 2.0));
    end
  endfunction

  analog function integer incrementCount;
    input count;
    integer count;
    integer nextCount;
    begin
      nextCount = (count + 1);
      incrementCount = nextCount;
    end
  endfunction

  analog function real limitSignal;
    input signal, ceiling;
    real signal;
    real ceiling;
    real adjusted;
    begin
      adjusted = affineSignal(signal, 1.0, 0.0);
      limitSignal = ((adjusted > ceiling) ? ceiling : adjusted);
    end
  endfunction

  analog begin
    transfer_0 = laplace_nd((curveSignal((V(stimulus, reference) / 1)) * 1), '{1}, '{1, 0.001});
    V(amplified, reference) <+ halfSignal(limitSignal(affineSignal(V(stimulus, reference), chooseGain(incrementCount(mode), 1, gain), 0), 2));
    V(shaped, reference) <+ transfer_0;
  end
endmodule
```

The target contains six native functions: dimensioned Real functions, Integer
arguments/results, initialized locals, a nested function call, preserved real
`1.0 / 2.0` division, and a function result consumed by `laplace_nd`. Calls are
not substituted by forged value attributes. This output is Verilog-A, not
synthesizable digital Verilog.

## Reproduction

```sh
mkdir -p evidence
./nodal core scala
./mill -i examples.continuousTimeApi.runMain nodal.increment41fixture.Increment41ConstructionCheck
./mill -i core.scala.testkit.test.runMain nodal.internal.testkit.Increment41MlirCheck "$PWD/evidence/public.mlir"
./nodal core native
out/native/release/bin/nodal-translate --nodal-to-verilog-a evidence/public.mlir > evidence/public.va
python3 tests/compiler/fixtures/increment41/run_native_matrix.py \
  --nodalc out/native/release/bin/nodalc \
  --translate out/native/release/bin/nodal-translate \
  --source evidence/public.mlir
```

Use the repository-pinned native and lint toolchains. Accepted historical
source/target identities remain immutable when later increments evolve current
fixtures or output.
