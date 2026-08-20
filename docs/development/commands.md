# Unified developer commands

Nodal exposes one stable command surface for local development and CI:

```text
nodal
```

Use `./nodal` on Linux and macOS and `nodal.bat` on Windows. Both wrappers call
the same Python standard-library implementation in `scripts/nodal.py`.

## Native compiler toolchain

```bash
./nodal bootstrap
./nodal bootstrap --mode prebuilt --prefix /absolute/toolchain/path
./nodal bootstrap --mode source --jobs 8
./nodal bootstrap --dry-run --json
```

The command installs or validates the checksum-locked LLVM/MLIR/CIRCT stack.

## Style and lint toolchain

```bash
./nodal style bootstrap
./nodal style bootstrap --prefix /absolute/lint/path
./nodal style bootstrap --dry-run --json
```

This creates an isolated environment containing the exact clang-format and
clang-tidy versions in `toolchains/lint-lock.json`. Scalafmt and Scalafix are
resolved through the pinned Mill build.

Check all language and contribution rules:

```bash
./nodal style check
./nodal style check --lint-toolchain /absolute/lint/path
./nodal style check --base-ref origin/dev
```

Apply deterministic source rewrites:

```bash
./nodal style fix
./nodal style fix --lint-toolchain /absolute/lint/path
```

`style fix` applies Scalafmt, Scalafix, and ClangFormat only. Markdown,
visibility, pull-request, and design-gate violations must be corrected
explicitly.

## Build and test Scala core

```bash
./nodal core scala
```

This compiles every Scala module through the repository Mill wrapper and runs
the Scala smoke tests.

## Build, lint, and test native core

```bash
./nodal core native
./nodal core native --toolchain /absolute/toolchain/path
./nodal core native --lint-toolchain /absolute/lint/path
```

The command configures the `native-release` CMake preset, builds `nodalc`, runs
CTest and `check-nodal-native`, and runs ClangTidy when a validated lint
toolchain is selected.

## Complete core gate

```bash
./nodal check
./nodal check --online-toolchain
./nodal check --toolchain /absolute/toolchain/path
./nodal check --lint-toolchain /absolute/lint/path
./nodal check --base-ref origin/dev
```

The full check runs architecture, toolchain, compiler, developer-command, CI,
formatting, lint, Markdown, package-visibility, contribution-policy, Scala, and
native validation. Install both toolchains before running it:

```bash
./nodal bootstrap
./nodal style bootstrap
./nodal check --base-ref origin/dev
```

For CI job decomposition or a fast local policy pass:

```bash
./nodal check --contracts-only --lint-toolchain /absolute/lint/path
./nodal check --contracts-only --online-toolchain --base-ref origin/dev
```

This skips Scala compilation and the native build, but still runs Scalafmt,
Scalafix, ClangFormat, Markdown, visibility, and contribution-policy checks.

## Diagnose the native toolchain

```bash
./nodal toolchain doctor
./nodal toolchain doctor --online --require
./nodal toolchain doctor --toolchain /absolute/toolchain/path
```

## Clean generated outputs

```bash
./nodal clean
./nodal clean --dry-run
./nodal clean --toolchains
```

The default preserves downloaded toolchains. `--toolchains` additionally removes
only the repository-local `.toolchains/` path. The cleaner refuses paths
resolving outside the repository.

## Reserved library namespace

```bash
./nodal library check <library-id>
```

No library is implemented in the current roadmap. The command currently exits
with `NODAL-DEV-004` and does not create or consume a `libraries/` directory.

## CI rule

GitHub Actions invokes the same public commands. Workflows must not duplicate
the underlying Mill, CMake, CTest, formatter, linter, or bootstrap sequences.
The generic workflow uses:

```bash
./nodal style bootstrap --prefix <runner-lint-toolchain>
./nodal check --contracts-only --online-toolchain \
  --lint-toolchain <runner-lint-toolchain> --base-ref <base>
./nodal core scala
./nodal bootstrap --mode prebuilt --prefix <runner-native-toolchain>
./nodal core native --toolchain <runner-native-toolchain> \
  --lint-toolchain <runner-lint-toolchain>
```

## Contract validation

```bash
python3 scripts/check_developer_commands.py
python3 -m unittest discover -s tests/developer -p 'test_*.py'
```
