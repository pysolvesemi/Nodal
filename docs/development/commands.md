# Unified developer commands

Increment 7 defines one stable command surface for local development and CI:

```text
nodal
```

Use `./nodal` on Linux and macOS and `nodal.bat` on Windows. Both wrappers call
the same Python standard-library implementation in `scripts/nodal.py`; build
logic is not duplicated in the wrappers or CI workflows.

## Command contract

### Bootstrap the locked native toolchain

```bash
./nodal bootstrap
./nodal bootstrap --mode prebuilt --prefix /absolute/toolchain/path
./nodal bootstrap --mode source --jobs 8
./nodal bootstrap --dry-run --json
```

This delegates to the checked and checksum-verified native toolchain installer.
It never selects an unpinned system LLVM, MLIR, or CIRCT installation.

### Build and test Scala core

```bash
./nodal core scala
```

This compiles every Scala module through the repository Mill wrapper and runs
the Scala core smoke tests.

### Build and test native core

```bash
./nodal core native
./nodal core native --toolchain /absolute/toolchain/path
```

The command requires a managed installation matching `toolchains/lock.json`,
configures the `native-release` CMake preset, builds `nodalc`, runs CTest, and
runs the aggregate `check-nodal-native` target.

### Run the complete core check

```bash
./nodal check
./nodal check --online-toolchain
./nodal check --toolchain /absolute/toolchain/path
```

The full check runs architecture, Scala bootstrap, native lock, native compiler,
developer-command, formatting-baseline, and CI-baseline contracts; all Python
unit-test suites; the Scala core build/tests; and the native core build/tests.
`--online-toolchain` additionally checks the pinned upstream release and
checksum provenance.

A native toolchain must already be installed. This separation keeps downloads
explicit:

```bash
./nodal bootstrap
./nodal check
```

For CI job decomposition or a fast local policy pass, run only contracts and
Python suites:

```bash
./nodal check --contracts-only
./nodal check --contracts-only --online-toolchain
```

This skips Scala and native compilation; it does not weaken any contract check.

### Diagnose the toolchain

```bash
./nodal toolchain doctor
./nodal toolchain doctor --online --require
./nodal toolchain doctor --toolchain /absolute/toolchain/path
```

The doctor validates the lock, optionally verifies online provenance, checks
Python/CMake/Ninja availability, and reports the selected managed installation.

### Clean generated outputs

```bash
./nodal clean
./nodal clean --dry-run
./nodal clean --toolchains
```

The default removes generated build, validation, BSP, and IDE-index outputs but
preserves downloaded toolchains. `--toolchains` additionally removes only the
repository-local `.toolchains/` path. The cleaner refuses paths resolving
outside the repository.

## Reserved library namespace

The command namespace is reserved now so future reusable packages can receive
independent checks without changing core command names:

```bash
./nodal library check <library-id>
```

No library is implemented in the current roadmap. Until a future library
roadmap approves the behavior, the command exits with:

```text
NODAL-DEV-004
```

The unified command does not create, discover, build, or depend on a
`libraries/` directory. The architectural direction remains one-way:

```text
future libraries -> published Nodal core APIs
Nodal core       -X-> future libraries
```

## CI rule

GitHub Actions invokes the same `./nodal` commands documented above. Workflows
must not duplicate the underlying Mill, CMake, CTest, or toolchain-bootstrap
command sequences. This keeps local and CI behavior aligned as the repository
grows.

The generic Core CI workflow uses:

```bash
./nodal check --contracts-only --online-toolchain
./nodal core scala
./nodal bootstrap --mode prebuilt --prefix <runner-toolchain>
./nodal core native --toolchain <runner-toolchain>
```

## Contract validation

The command surface is guarded by both structural and behavioral tests:

```bash
python3 scripts/check_developer_commands.py
python3 -m unittest discover -s tests/developer -p 'test_*.py'
```

The structural checker verifies wrappers, command namespaces, documentation,
CI delegation, and the absence of a core-to-library dependency. Behavioral
tests exercise argument forwarding, Scala and native command plans, complete
and contracts-only check composition, deterministic toolchain discovery, safe
cleaning, toolchain diagnostics, and the reserved library response.
