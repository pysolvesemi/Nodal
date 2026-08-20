# Nodal toolchain lock

`toolchains/lock.json` is the machine-readable source of truth for Nodal's
host-language and native-compiler bootstrap pins.

Increment 5 pins the first native compiler baseline to the latest published
CIRCT release available on 2026-08-20:

| Component | Locked value |
| --- | --- |
| CIRCT release | `firtool-1.154.0` |
| CIRCT commit | `87898a876f730a2ebc607dc9b83da487cba49119` |
| LLVM/MLIR commit | `b1c56fb53a9c76d6b045ede49083b647ae049ffe` |
| LLVM package version at that commit | `23.0.0git` |
| CMake minimum | `3.20.0` |
| CMake preferred | `3.31.0` |
| Ninja minimum | `1.10.0` |
| C++ language baseline | C++17 |

The LLVM/MLIR commit is not selected independently. It is the exact `llvm`
submodule pointer stored by the locked CIRCT release commit. CIRCT documents
that this submodule is the LLVM revision it has tested and may contain MLIR
changes needed by CIRCT. Therefore Nodal does not substitute a numerically
similar LLVM release or follow either repository's default branch.

Authoritative upstream references:

- <https://github.com/llvm/circt/releases/tag/firtool-1.154.0>
- <https://github.com/llvm/circt/commit/87898a876f730a2ebc607dc9b83da487cba49119>
- <https://github.com/llvm/llvm-project/commit/b1c56fb53a9c76d6b045ede49083b647ae049ffe>
- <https://github.com/llvm/circt#submodules>

## Integrity policy

Every downloadable archive is tied to the exact CIRCT release URL and a
checked-in SHA-256 file under `toolchains/checksums/`.

The lock checker rejects:

- abbreviated or malformed Git SHAs;
- `main`, `master`, `HEAD`, `latest`, or another mutable branch/ref;
- an LLVM commit different from the CIRCT submodule pointer;
- release URLs not containing the exact locked tag and filename;
- missing, malformed, duplicated, or inconsistent checksums;
- relaxed lock policies;
- Scala/Mill/JDK pins that disagree with the existing Scala build.

Run the offline check:

```bash
python3 scripts/check_native_toolchain.py
```

Also verify the release tag, CIRCT submodule pointer, LLVM commit, and
upstream `.sha256` assets:

```bash
python3 scripts/check_native_toolchain.py --online
```

The online mode accepts `GITHUB_TOKEN` to avoid anonymous API rate limits.

## Prebuilt discovery

Nodal accepts only a managed installation containing
`.nodal-toolchain.json` whose lock digest and component commits match the
checked-in lock. Discovery order is:

1. `--toolchain <path>`;
2. `NODAL_NATIVE_TOOLCHAIN`;
3. `.toolchains/native/<lock-id>-<host>`;
4. `$NODAL_NATIVE_TOOLCHAIN_HOME/<lock-id>-<host>`;
5. `$XDG_CACHE_HOME/nodal/toolchains/<lock-id>-<host>` or the equivalent
   directory below `~/.cache`.

Inspect discovery and the fallback plan:

```bash
python3 scripts/bootstrap_native_toolchain.py status
python3 scripts/bootstrap_native_toolchain.py status --json
```

Require an already installed toolchain:

```bash
python3 scripts/bootstrap_native_toolchain.py status --require
```

The managed install must provide:

```text
bin/circt-opt
bin/mlir-opt
lib/cmake/circt/CIRCTConfig.cmake
lib/cmake/mlir/MLIRConfig.cmake
lib/cmake/llvm/LLVMConfig.cmake
```

Windows executable suffixes are handled by the discovery validator, although
the initial checked prebuilt asset set covers Linux x86-64, macOS x86-64, and
macOS arm64.

## Installing a prebuilt release

Preview the exact asset, URL, checksum, and destination without changing the
host:

```bash
python3 scripts/bootstrap_native_toolchain.py plan --mode auto
python3 scripts/bootstrap_native_toolchain.py install --mode auto --dry-run
```

Install the locked full shared release asset on a supported host:

```bash
python3 scripts/bootstrap_native_toolchain.py install --mode prebuilt
```

The bootstrapper downloads to a user cache, verifies SHA-256 before
extraction, rejects archive path traversal, normalizes the release archive,
and writes the managed install manifest.

## Source-build fallback

The lock also pins `circt-full-sources.tar.gz`, which contains the matching
CIRCT and LLVM/MLIR sources. Preview the exact CMake/Ninja plan:

```bash
python3 scripts/bootstrap_native_toolchain.py plan --mode source
```

Build and install from the verified source archive:

```bash
python3 scripts/bootstrap_native_toolchain.py install --mode source
```

The source fallback configures the unified build with:

```text
LLVM_ENABLE_PROJECTS=mlir
LLVM_TARGETS_TO_BUILD=host
LLVM_ENABLE_ASSERTIONS=ON
LLVM_EXTERNAL_PROJECTS=circt
CIRCT_BUILD_TOOLS=ON
CIRCT_INCLUDE_TESTS=OFF
CIRCT_SLANG_FRONTEND_ENABLED=OFF
CIRCT_BINDINGS_PYTHON_ENABLED=OFF
```

Increment 5 validates the lock, discovery, installer, and dry-run source
plan. Increment 6 will consume this toolchain to build the first native
`nodalc` executable.

## Upgrade rule

A toolchain upgrade must be a reviewed change that updates together:

1. the CIRCT release tag and full commit;
2. the LLVM submodule commit and LLVM lock;
3. every affected release asset URL and SHA-256 file;
4. offline and online lock tests;
5. compatibility evidence from the native build and tests.

Automated jobs may report a newer release, but they must never silently
rewrite this lock.
