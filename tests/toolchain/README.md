# Native toolchain tests

These tests validate the immutable LLVM/MLIR/CIRCT lock, release checksums,
source-build plan, managed prebuilt discovery, install manifest, and archive
extraction safety. They use only Python's standard library and do not download
or build the native toolchain.

`test_native_overlay.py` exercises the optional checksum-owned CIRCT repair:
exact revision/patch/source identity, closed paths, supported host/mode,
transactional fresh-prefix installation, idempotence, output/receipt tampering,
and exact-prefix verification without discovery fallback. It mocks the native
build and network boundary; it is not itself an HDL or compiler acceptance run.
See [the repair profile](../../toolchains/patches/circt-inout-parameters-v1/README.md)
for explicit bootstrap and consumer selection.
