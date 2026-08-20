# Native compiler bootstrap

Increment 6 establishes the first out-of-tree Nodal native compiler build on the
locked LLVM/MLIR/CIRCT package from Increment 5. It intentionally defines no
Nodal dialect operations or language semantics.

## Install or locate the locked toolchain

Install the checked prebuilt package on a supported host:

```bash
python3 scripts/bootstrap_native_toolchain.py install --mode auto --json
```

Or require an existing managed installation:

```bash
python3 scripts/bootstrap_native_toolchain.py status --require --json
```

Set the returned prefix:

```bash
export NODAL_NATIVE_TOOLCHAIN=/absolute/path/to/nodal-native-2026.08.20-<host>
```

CMake rejects a directory that lacks `.nodal-toolchain.json`, has different
CIRCT/LLVM commits, or is missing the exported CIRCT, MLIR, and LLVM packages.

The exported LLVM definitions also select the libstdc++ C++11 ABI used by the
locked binaries. Some upstream binary packages combine several `-D` flags in a
single CMake list element, so Nodal tokenizes every exported element, removes
duplicates, captures the locked ABI value, and then lets `HandleLLVMOptions`
apply that ABI macro exactly once. This prevents silent host-default ABI drift
and avoids malformed or repeated compiler definitions. The Increment 6 workflow
prints the compile command and treats an ABI macro redefinition as a failure.

## Configure, build, and test

```bash
cmake --preset native-release
cmake --build --preset native-release
out/native/release/bin/nodalc --version
ctest --preset native-release
cmake --build out/native/release --target check-nodal-native
```

The first compiler executable is an MLIR-style driver. It links the installed
CIRCT `hw` dialect and CIRCT version support, but it does not yet register a
Nodal dialect. `nodalc --version` reports:

```text
nodalc 0.0.0-dev
Nodal native toolchain: nodal-native-2026.08.20
CIRCT release: firtool-1.154.0
CIRCT commit: 87898a876f730a2ebc607dc9b83da487cba49119
LLVM/MLIR commit: b1c56fb53a9c76d6b045ede49083b647ae049ffe
LLVM package: 23.0.0git
```

CIRCT's own linked-library version is printed after this block.

## Native tests

The native test target contains:

- a unit executable checking generated version metadata and the linked CIRCT
  release;
- a CTest invocation of `nodalc --version`;
- the aggregate `check-nodal-native` target.

Language parsing, Nodal operations, TableGen definitions, compiler passes, and
backend semantics remain deferred to later increments.
