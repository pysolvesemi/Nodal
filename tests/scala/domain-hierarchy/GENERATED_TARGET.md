# Generated child target profiles

`Increment58GeneratedOccurrenceTargetReplay` exports actual bounded public Scala
source from `Increment58GeneratedOccurrenceTargetSource.scala`. Each authored
family contains a real child instance. Inputs carry lane-distinct values, not a
broadcast constant. The scalar child preserves a WIDTH formal; the state child
checks actual clock/reset behavior. Struct profiles preserve named nested leaves
and Vec dimensions, nested families select row 1/lane 0, and the nonunit family
selects ordinal 1 at coordinate 4. Repeated width-4/width-8 families share one
parameterized child definition rather than making lane-specific copies.

`run_generated_target.py` checks the native gate, actual helper/module/instance
counts, real target loops, optimized verified SV export, Slang import and
arcilator behavior. It obtains physical names, widths and offsets from the
emitted DUT and never rewrites that SV. Four causal native mutations check forged
family/induction ownership, duplicate child-input drivers and missing controls.
`GeneratedOccurrenceCIRCTTest.cpp` additionally checks transactional refusal,
physical output slots without parent-side views, symbolic template preservation
at the carrier bound, and the immediately exceeded bound. Exact-empty native
cases retain the original loop/helper/child and reject malformed ownership or
ambiguous authored writers without inventing executable dependency edges.

Use already-built artifacts and the managed `circt-structural-consumers-v1`
capability profile. A base CIRCT prefix without its SSA-region consumer repairs
is not an interchangeable acceptance environment.

```sh
mkdir -p .work/generated-child-inputs
flock .work/f58-mill.lock sh .work/cache/mill/download/1.1.7 \
  --no-daemon core.scala.testkit.test.runMain \
  nodal.internal.testkit.Increment58GeneratedOccurrenceTargetReplay \
  .work/generated-child-inputs
LD_LIBRARY_PATH="$CIRCT/lib" python3 tests/scala/domain-hierarchy/run_generated_target.py \
  --nodalc "$NODALC" --toolchain "$CIRCT" --cxx "$CXX" \
  --inputs .work/generated-child-inputs --output .work/generated-child-target
```

Paths for tools are explicit; no download or full suite is triggered by this
runner. Outputs must be fresh directories under ignored `.work`. Each receipt
contains exact commands, compiler/input/SV hashes and configurations.

The `empty` profile checks a zero-trip loop while retaining its authored helper
and child. Canonical authored-template routing remains distinct from executable
dependency edges, which correctly have no attained writes. Invoking the runner
without `--profile` includes all ten profiles. Selected Instance/Interface views,
induction-varying physical carriers, analog, resolved endpoints and CDC are not
accepted by these invariant body-local tests. No startup-X or exhaustive
simulation claim is made. Executed scope/results belong in the existing
increment readiness record, not in the presence of a fixture alone.
