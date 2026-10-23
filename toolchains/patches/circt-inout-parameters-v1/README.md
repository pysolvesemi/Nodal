# CIRCT recursive InOut parameter repair

This optional managed profile repairs `evaluateParametricType` and
`isParametricType` in the exact pinned CIRCT HW owner. Both recurse through
`InOutType` while preserving its capability wrapper. A child pin with width `W`
therefore substitutes its exact parent binding `W = P`; it is not compared using
the child token in the parent owner. Missing bindings and unequal payload types
remain errors. The patch includes focused upstream-style C++ and MLIR tests.

The base release, CIRCT/LLVM revision pair and `toolchains/lock.json` do not
change. `manifest.json` owns the exact lock digest, base shared-library hash,
patch hash, and before/after hashes of every required official source file.
The installer extracts the checksum-verified prebuilt archive into a fresh
staging prefix and builds only the complete 17-file `CIRCTHW` component. It uses
the original imported dependency closure and `llvm-config` ABI flags. The
manifest supplies no commands; the bounded build procedure belongs to
`scripts/native_toolchain/overlay.py`.

## Explicit selection

The initial repair profile supports Linux x86_64 prebuilt installations only.
It needs the host C++ compiler, CMake, Ninja and `patch`. Other hosts and source
mode fail explicitly; they are not silently treated as repaired.

```sh
python3 scripts/bootstrap_native_toolchain.py install \
  --overlay circt-inout-parameters-v1 --prefix /path/to/fresh/repaired-prefix
python3 scripts/bootstrap_native_toolchain.py verify \
  --toolchain /path/to/fresh/repaired-prefix \
  --overlay circt-inout-parameters-v1
cmake -S . -B .work/native-repaired \
  -DNODAL_NATIVE_TOOLCHAIN=/path/to/fresh/repaired-prefix
```

Use that same prefix's `bin/firtool` for downstream export. A missing `--prefix`
selects a separate cache name suffixed by the overlay identifier, never the base
cache entry. `--force` does not replace an existing base or invalid destination
when installing an overlay. An identical validated repair is an idempotent
no-op. Build failure leaves no destination. The original installation remains
available for comparisons.

The ordinary base profile remains suitable for its existing supported target
features. Symbolic resolved target transport requires the actual recursive
InOut utility capability and fails early with this profile name when absent.
The target probes the linked utility directly, so an incompatible runtime
library override cannot gain support merely through a build-time manifest.
There is no target-side cast, default width, IR rewrite or automatic external
installation. Symbolic resolved target admission and its tests are a separate
consumer change; the repair itself does not broaden Nodal semantics.

## Identity and validation

The installed manifest records the checked profile identity and the exact
rebuilt library/build-receipt hashes. The receipt retains source identities,
commands, compiler and tool hashes/versions, ABI flags and the output hash.
Discovery reports the overlay identity. CMake validates only its exact selected
prefix through the shared state owner, without falling back to another cache.
Removing a profile identity while retaining its receipts is rejected. Source,
patch, output or receipt mismatch fails closed.

No pre-existing prefix is modified, and no system-wide library search path is
changed. Reproduction fixes the source and ABI inputs; the receipt identifies
the actual compiler and output rather than claiming identical binary bytes
across different host compilers. Build receipts and generated files are local
artifacts, not checked-in evidence bundles.
