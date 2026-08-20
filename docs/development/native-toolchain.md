# Native compiler toolchain

The native Nodal compiler will be built against the immutable toolchain in
[`toolchains/lock.json`](../../toolchains/lock.json).

## Bootstrap commands

```bash
python3 scripts/check_native_toolchain.py
python3 scripts/check_native_toolchain.py --online
python3 scripts/bootstrap_native_toolchain.py status
python3 scripts/bootstrap_native_toolchain.py plan --mode auto
python3 scripts/bootstrap_native_toolchain.py plan --mode source
```

Install a verified prebuilt toolchain where available:

```bash
python3 scripts/bootstrap_native_toolchain.py install --mode prebuilt
```

Use the verified full-source archive as the portable fallback:

```bash
python3 scripts/bootstrap_native_toolchain.py install --mode source
```

The prebuilt and source paths create the same managed installation contract:
the required LLVM, MLIR, and CIRCT CMake package files plus `circt-opt` and
`mlir-opt`, accompanied by a `.nodal-toolchain.json` manifest tied to the
checked-in lock digest.

Increment 6 will add the Nodal CMake project and `nodalc`; this increment does
not build the full upstream toolchain in normal CI.
