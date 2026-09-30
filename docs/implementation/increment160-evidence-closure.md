# Increment 160 accepted-evidence closure

**Date:** 2026-09-29

**Status:** Validated

## Accepted boundary

Foundation 160 accepts the bounded construction-frontend modularization and
scalability baseline. Production retains one `ConstructionSession` and each
canonical mutable registry. The facade, records, session, stateless expression
facts and stateless Interface-layout helpers remain on the real construction
path with unchanged public syntax, identity, ordering, failure cleanup, bridge
schema and deterministic target behavior. Frozen Increment 16 validation
remains unchanged.

The accepted implementation head is
`a792cf2616936b78efac2079cba1749e38e504a2`, tree
`62d425c5954feeb13e661d86c78f90adf5959b1a`, from implementation PR
[#137](https://github.com/pysolvesemi/Nodal/pull/137). The verified squash merge
is `ee6e919510e95a8e267c676a9724ec1bae86f348`; it has sole parent
`60d56be8dfcd0835ef6ddfa85cf9189dfdb1bb68` and exactly the qualified
tree.

All 30 pull-request workflows and the separate push Core workflow passed on the
exact accepted head: 31 successful runs and 37 successful check runs. Push Core
[run 36588957739](https://github.com/pysolvesemi/Nodal/actions/runs/36588957739)
passed on attempt 2, including native job `109506326985`, required aggregate
`109529105560` and `core-ci/required`. PR Core
[run 36588963275](https://github.com/pysolvesemi/Nodal/actions/runs/36588963275)
also passed. Increment 34 correctly did not trigger because its paths were
unchanged; Core retained its predecessor witness.

The squash message carried `[skip ci]`. The exhaustive all-event inventory for
the merge SHA contains zero runs. Post-merge CI is therefore recorded as
**skipped for a qualified-identical-tree merge**, not as an executed or passing
workflow. No run identifier is manufactured for that absence.

## Targeted qualification and review

Exact repaired head `9b1b53987b23bf3c79d82ffc41481b86751ee4fd`
has the same accepted tree. Targeted Core
[run 36561903660](https://github.com/pysolvesemi/Nodal/actions/runs/36561903660)
passed on attempt 2, and all 14 specialized Increment 18-31 workflows passed on
attempt 1. Its retained successful parity artifact `11040216375` has ZIP
SHA-256
`e82c2777a2348d18ac24795cde443ce9c4141d90a228e2953afdd24d6039b55a`.

Independent Codex review comment
[5889249936](https://github.com/pysolvesemi/Nodal/pull/137#issuecomment-5889249936)
reviewed the final substantive fixture repair without findings. Earlier
completed reviews cover the unchanged production and qualification scope. The
final CI child changes no files and has the identical reviewed tree/base/diff.

## Retained differential and scale evidence

Final push artifact `11049554512` is 13,528,329 bytes; its downloaded ZIP
SHA-256 is
`df7cb86a20e5207f8f5e416b349b99ab239b856e7cc464a0e40a1ffc9b799950`
and its `results.json` SHA-256 is
`d4e88278041e33a60a4d767c6e90f9d255883b6e297465e9b4b8660e398ee774`.
API, upload and downloaded digests agree and the ZIP is CRC-clean.

The record reports `passed` for all 55 fixed cases: 41 accepted and 14
rejection/recovery cases, including 10 scale, 33 source-MLIR and 19
native/Verilog-A cases. It retains all 330 mandatory role/case trials across
three AB/BA/AB pairs, six fresh fixture compiles, six startup trials, 593 command
records, 912 passing measurements and 1,500 exact artifact records. Independent
rehashing matched every retained artifact. The experiment definition remains
byte-identical at SHA-256
`9a4604927592ab773c8930ac53012fbdb1c3da6365832792ad0d5a7f20026406`.

The predeclared larger-of relative/absolute budgets remain: warm 25%/10 ms;
cold, startup, compile and native 30%/100 ms; allocation 15%/64 KiB; and peak
RSS 20%/16 MiB. One bounded repeat at three MADs classifies noise as incomplete,
never as a pass. The successful final artifact needed no repeat.

## Actual public Scala demonstration

This is exact source from
`examples/continuousTimeApi/src/nodal/increment42fixture/Increment42PredecessorCombinations.scala`,
not pseudocode:

```scala
final class HierarchyEventTop(rootGain: Param[Real] = 4.0) extends Module:
  val parameter: Param[Real] = rootGain
  val vin: Node[Electrical.type] = in(Electrical)
  val vout: Node[Electrical.type] = out(Electrical)
  val child: HierarchyCombinationLeaf = new HierarchyCombinationLeaf(gain = rootGain)
  val held: Variable[Real] = variable(Real, 0.0.V)
  vin <> child.vin
  child.vout <> vout

  analogProcedure:
    on(initialStep):
      held := 1.0.V

  analog:
    V(vin, vout) <+ transition(held, 0.0.ns, 1.0.ns)
```

## Actual unchanged generated Verilog-A demonstration

This is the exact `hierarchy-event` target retained for all three baseline and
all three candidate trials in artifact `11049554512`. Every copy has SHA-256
`a94fc9fb2904d73a28c433817c09c2bf1373ab2a8a6650dd1dc8867d685ca612`:

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

module HierarchyEventTop(vin, vout);
  input vin;
  output vout;
  electrical vin, vout;
  parameter real rootGain = 4;
  child #(.gain(rootGain)) child_instance(.vin(vin), .vout(vout));
  real event_HierarchyEventTop_held = 0.0;
  real waveform_0;

  analog begin
    begin : event_HierarchyEventTop_procedure
      @(initial_step) begin
        event_HierarchyEventTop_held = 1.0;
      end
    end
    waveform_0 = transition(event_HierarchyEventTop_held, 0, 1e-09);
    V(vin, vout) <+ waveform_0;
  end
endmodule

module child(vin, vout);
  input vin;
  output vout;
  electrical vin, vout;
  parameter real gain = 2;
endmodule
```

## Limits and handoff

This acceptance is compiler/construction and generated-target evidence. It is
not numerical simulation, independent OpenVAF execution, general Verilog-AMS
acceptance, synthesis, or a broad frontier-performance claim. The late
equation-only numerical handoff remains with F-141 for legalization and with
F-134/F-135 for source/residual work; it is not relabeled as repaired.

The fixed workload definitions, exact artifacts, stage/resource samples and
predeclared budgets are handed to F-096 for later comprehensive 10K/100K/1M
profiling, profile-guided refinement and optional Rust evaluation. Those later
studies are not reverse prerequisites.

All 20 boxes in the sole-owner F-160 companion plan are supported. The added
F-160 prerequisite for F-043 is satisfied, establishing eligibility only:
F-043 remains open and unstarted.

## Reproduction

```sh
./nodal core scala
./nodal core native
python3 scripts/check_increment160.py
python3 -m unittest tests.compiler.test_increment160_parity tests.compiler.test_increment160_closure
python3 tests/compiler/fixtures/increment160/run_parity.py \
  --candidate-root "$PWD" \
  --baseline-root /absolute/path/to/accepted-cafd52e5-checkout \
  --out /new/empty/evidence/directory \
  --nodalc /absolute/path/to/nodalc \
  --translate /absolute/path/to/nodal-translate \
  --native-build-receipt /absolute/path/to/native-build.json
```

The machine-readable record is
`docs/implementation/increment160-accepted-evidence.json`; the separate
`tests/compiler/fixtures/increment160/closure-manifest.json` binds its digest,
this report and the immutable experiment definition.

