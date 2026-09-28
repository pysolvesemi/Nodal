# Increment 42 accepted-evidence closure

**Date:** 2026-09-28

**Status:** Validated

## Accepted boundary

Increment 42 accepts the compiler-structural Verilog-A profile for analog
hierarchy and parameterized instances. Public Scala construction preserves
module, instance, parameter, conservative-terminal, connection, source and
ownership identity through normalized MLIR and deterministic named Verilog-A
instances. Compatible constructed definitions are reused without specializing
one module per actual value.

The accepted implementation head is
`fa921f95cc6aad9b1cb86e33edc7f09e53a7f4e4`, tree
`e9b74f0a8963ecec76b7e68406e104beda08bf04`, from implementation PR #134.
The verified squash merge is `98085f79aeaef3a5c7eeabfda462afa7299cbaf7`;
it has sole parent `c3ea7cbf0432de7143a08b4431d51708c307d67e`
and exactly the qualified tree.

All 31 pull-request workflows and the separate push Core workflow passed on
the exact accepted head, comprising 32 runs and 38 successful jobs. Push Core
run `36396069440` published successful `core-ci/required`; PR Core run
`36396075706` and the distinct Increment 17/33/34 runs `36396075697`,
`36396075748` and `36396075733` passed. Fresh review comment `5866190034`
reviewed `fa921f95cc` without a blocking finding, and all 12 review threads were
resolved before merge.

The squash message carried `[skip ci]`. Exhaustive all-event inventories for
the merge SHA on pages 1 and 2 contain zero runs. Post-merge CI is therefore
recorded as **skipped for a qualified-identical-tree merge**, not as an executed
or passing workflow. No run identifier is manufactured for that absence.

## Separate public target witness

Closure PR #135 added a read-only public witness without changing the accepted
implementation semantics. Exact closure checkpoint
`aa201f415a1d139948d24351c86862078cd101d2`, tree
`e785dcb4d061642573cdcba6b00cf5680573140e`, passed Core run
`36403338628` and Increment 41 run `36403361696` on attempt 1. Artifact
`10962330727` has ZIP SHA-256
`e2e39ff57c79904ba6987baf369e7bda09cb6aa375bda1d5bd055eaafa886ea8`.

The retained source identities and output hashes are:

- public hierarchy MLIR: `eb09715fc24d2049c8f2e145c554efa0fa1a22fcf5d8b040ecb964d01acc21eb`;
- normalized MLIR: `5de89d136c6a30fae9e1b39b2b802281fad7d72265039a9a256a78871910a9de`;
- generated Verilog-A: `d78386b05efc24318374a97cad60a78787dd88a1a96ad71ecb359e0d02fb86bd`.

The MLIR retains real source locations from
`Increment42PredecessorCombinations.scala`, one child instance, two instance
terminals, two conservative connections, the symbolic parameter override and
the parent-owned analog equation. The same artifact retains the complete native
hierarchy matrix: schema `nodal.increment42.native-hierarchy-matrix.v1`, 572
cases, zero failures.

## Actual public Scala demonstration

This is source from the accepted public fixture, not pseudocode:

```scala
final class HierarchyCombinationLeaf(gain: Param[Real] = 2.0) extends Module:
  def parameter: Param[Real] = gain
  val vin: Node[Electrical.type] = in(Electrical)
  val vout: Node[Electrical.type] = out(Electrical)

final class HierarchyEquationTop(rootGain: Param[Real] = 4.0) extends Module:
  val parameter: Param[Real] = rootGain
  val resistance: Param[Real] = param(1.0.kOhm)
  val vin: Node[Electrical.type] = in(Electrical)
  val vout: Node[Electrical.type] = out(Electrical)
  val child: HierarchyCombinationLeaf = new HierarchyCombinationLeaf(gain = rootGain)
  vin <> child.vin
  child.vout <> vout

  equations:
    equation(V(vin, vout), resistance * I(vin, vout))
```

## Actual generated Verilog-A demonstration

This is the exact retained target from artifact `10962330727`:

```verilog
/* Nodal backend framework v1
 * profile: verilog-a
 * check-profile: default
 * shaped-layout: scalar-or-flat
 * materialization: safe-inline
 * naming: semantic
 */
`include "constants.vams"
`include "disciplines.vams"

module HierarchyEquationTop(vin, vout);
  input vin;
  output vout;
  electrical vin, vout;
  parameter real resistance = 1000;
  parameter real rootGain = 4;
  child #(.gain(rootGain)) child_instance(.vin(vin), .vout(vout));
endmodule

module child(vin, vout);
  input vin;
  output vout;
  electrical vin, vout;
  parameter real gain = 2;
endmodule
```

## Limits retained

This is compiler and generated-target evidence. It is not independent OpenVAF
execution, numerical analog simulation, general Verilog-AMS acceptance or
synthesis. Non-default root actuals remain explicitly rejected because a
standalone library has no parent instance that can carry them. The reviewed
[later-tool handoff](increment42-later-tool-handoff.md) assigns independent
compile/load and numerical cases to Increments 48, 49 and 52 without making
those later increments reverse prerequisites for this compiler profile.

## Reproduction

```sh
mkdir -p evidence
./nodal core scala
./mill -i core.scala.testkit.test.runMain nodal.internal.testkit.Increment42MlirCheck "$PWD/evidence/increment42-public.mlir"
./nodal core native
nodalc=$(find out -type f -name nodalc -executable | head -1)
translate=$(find out -type f -name nodal-translate -executable | head -1)
"$nodalc" --pass-pipeline=builtin.module\(nodal-gate-default\) evidence/increment42-public.mlir > evidence/increment42-normalized.mlir
"$translate" --nodal-to-verilog-a evidence/increment42-normalized.mlir > evidence/increment42-public.va
sha256sum evidence/increment42-public.mlir evidence/increment42-normalized.mlir evidence/increment42-public.va
```

The machine-readable record is
`docs/implementation/increment42-accepted-evidence.json`, SHA-256
`bca7844471f7298c128bc2d015dd4158edefc198627a99dd36468a1855b30b52`, as
recorded by the fixture manifest and repository contract.
