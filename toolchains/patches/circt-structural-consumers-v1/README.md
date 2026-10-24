# CIRCT structural consumer repair

This managed profile combines the existing recursive InOut parameter repair
with two independent dominance-aware fixes in the exact pinned CIRCT consumer:

- `PrettifyVerilog` does not sink an instance across uses in an SSA region.
  Graph-module instance sinking remains available and reports actual changes,
  so the pass does not incorrectly preserve analyses after reordering.
- `PrepareForEmission` keeps an already valid single-use instance assignment in
  its SSA-region position. Moving that assignment before a later destination
  definition had created a forward lvalue reference and an incorrect one-way
  spill. Graph-module cyclic-dependency ordering is unchanged; generic lvalue
  spilling is not modified.

The original `circt-inout-parameters-v1` profile is unchanged and remains a
separate identity. Neither the CIRCT/LLVM pin nor the base archive changes.
The new schema-2 profile is `circt-structural-consumers-v1`. It rebuilds the
complete pinned `CIRCTHW`, `CIRCTSVTransforms` and `CIRCTExportVerilog` components
(17, 9 and 6 translation units) in that fixed order. The 39-file inventory
includes the original implementation headers, canonical CMake inventories and
existing HW tests. Manifest entries contain no commands or arbitrary build
components. The existing installer owns all fetching, patching and compilation.

## Explicit selection

Generated Instance target consumers use the combined profile. The older HW-only
profile must not be labeled as supporting the new SSA consumer fixes. Existing
base/HW-only workflows remain available for their previous supported features.
The initial combined profile supports Linux x86_64 prebuilt installations.

```sh
python3 scripts/bootstrap_native_toolchain.py install \
  --overlay circt-structural-consumers-v1 --prefix /path/to/fresh/combined
python3 scripts/bootstrap_native_toolchain.py verify \
  --toolchain /path/to/fresh/combined --overlay circt-structural-consumers-v1
cmake -S . -B .work/native-structural \
  -DNODAL_NATIVE_TOOLCHAIN=/path/to/fresh/combined
```

Use this same prefix's `bin/firtool` for default-optimized downstream export.
There is no `--disable-opt` requirement, fake SSA dependency, emitted-IR/SV
rewrite, system-wide library override or mutation of an existing installation.
The installer extracts the exact base archive into a fresh stage and publishes
only after all component builds and identity checks succeed. Failed builds
leave no destination; `--force` cannot replace a base or invalid prefix.

## Focused checks

```sh
python3 tests/toolchain/test_native_overlay.py
python3 tests/toolchain/test_native_structural_overlay.py
python3 tests/toolchain/check_structural_consumers.py \
  --toolchain /path/to/fresh/combined --work .work/structural-consumer-probes
```

The consumer probes verify/reparse the actual transformed IR and export with
ordinary optimized firtool. They check early and late lvalue definition orders,
two instance results in an SSA generate case, and graph-region forward result
use. Slang imports the unchanged emitted SV and arcilator executes five small
parameter configurations using offsets from its actual state map. These are
explicitly two-state checks, not startup-X or four-state equivalence claims.
Icarus in the current environment rejects CIRCT's legal named generate-block
spelling; output is not rewritten to hide that consumer limitation.

Independent Nodal source tests cover guarded/multifield generated children and
retain real helpers/loops rather than per-lane cloning. The combined profile
also preserves the symbolic resolved InOut utility and its separate four-state
read/drive/split waveforms. This repair does not admit unsupported Nodal
operations, generated physical resolved routes or analog capabilities.

## Reproducible identity

The checked manifest owns the exact base lock, source pin, patch, recipe and
before/after source checksums. Schema 2 requires the exact ordered three-component
catalog and exact three-output map, rejecting unknown, duplicate or missing
components. Installed output hashes and the build receipt are verified through
the existing shared state owner, including CMake's exact-prefix check. Receipts
retain compiler/tool hashes, ABI flags, commands and all output identities.
A changed source, recipe, patch, output or stale receipt fails closed. The
original HW-only profile and its accepted cache remain distinguishable.
