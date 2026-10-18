# Foundation 43: captured generate-count verification

**Status:** Unqualified implementation checkpoint. No F-043 child is completed.

## Owning layer and contract

`ParameterModel.cpp` independently checks the count of captured `hdlRange`
regions using its existing `IntegerBoundsAnalysis`. It does not substitute a
parameter default, create another expression graph, expand iterations or change
Integer arithmetic operators. The source construction and bridge retain their
existing representations.

A region carrying `region_id`, `induction_path`, `maximum_trip_count` or
`declared_maximum` opts into the captured count contract. Both region identities
and the count metadata must then be present. The count fields are signless i64
attributes holding nonnegative values that fit the current 32-bit public loop
metadata. Other attribute types, negative values and wider values are rejected.

The native verifier recomputes the positive-step, half-open outer-envelope count
from the minimum lower bound, maximum upper bound and minimum step. Equal bound
identities retain the existing always-empty case, but do not bypass step or
finite-bound validation. `maximum_trip_count` must equal this canonical
conservative result. An optional `declared_maximum` must contain the whole
result; it never clamps it. The full int64 endpoint span is handled without
signed-subtraction or ceiling-rounding overflow.

These checks apply to literal, symbolic, empty and nested captured regions.
Legacy native directional loops without the capture/count contract retain their
existing behavior, including negative steps. A count limit is per region, not
a new aggregate budget across nested products or all modules. Empty iterations
are not permission to construct zero-sized shapes.

Existing structural-parameter, ownership, type, finite-range, step and direction
checks remain active. The current diagnostic families are reused:
`NODAL-ITERATION-043-001` for invalid count/envelope metadata and
`NODAL-ITERATION-043-002` for nonpositive steps in the captured profile. Unknown
ownership and prior direction failures are not relabeled as count failures.

## Regressions and reproduction

The new real-compiler matrix contains 49 cases: 18 expected positives and 31
expected negatives. Positives run twice and must retain region/induction
identities, bounds, count metadata and the number of generated regions with
identical normalized output. A rejection requires normal exit 1 and exactly the
expected diagnostic family. Crashes, other exit codes and timeouts are failures.
The matrix is registered as `nodal.native.generate-count-integration` in CTest.

`Increment43GenerateCountTests` uses existing public symbolic and literal node
fixtures plus a public explicitly bounded fixture. An unconditional test checks
that mutations select the actual region path and preserve all other lines. The
configured native test first accepts each original document, then rejects
understated, overstated, malformed and insufficient-maximum metadata. This test
must run with the newly built `NODAL_NODALC`; its unconfigured branch is not
native evidence.

After building with the repository-pinned toolchain:

```sh
ctest --test-dir out/native/release \
  -R 'nodal.native.(parameter-integer-bounds|iteration-envelope-integration|generate-count-integration)' \
  --output-on-failure
NODAL_NODALC="$PWD/out/native/release/bin/nodalc" \
  ./mill core.scala.testkit.test.testOnly \
  nodal.internal.testkit.Increment43GenerateCountTests
```

Retain the inherited suites and run the applicable exact-head targeted workflows
before any whole-increment qualification. This checkpoint does not provide
arbitrary expression-bound transport, arrays, index/slice lowering,
per-generated-instance storage or generated Verilog-A array witnesses. Those
requirements, independent review, full final-head CI and accepted-evidence
closure remain open. Internal native checks are not independent OpenVAF or
numerical qualification.
