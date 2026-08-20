# Continuous integration baseline

Increment 8 establishes one generic **Core CI** workflow for every increment
branch and every pull request into either protected permanent branch.

## Required gate

The stable required status check is:

```text
Core CI / required
```

It succeeds only when all three independent jobs pass:

1. `contracts` — architecture, toolchain-lock, native-bootstrap, developer
   command, formatting baseline, CI-policy checks, Python tests, and online
   provenance verification;
2. `scala` — the complete Scala 3 module compilation and Scala smoke tests;
3. `native` — locked CIRCT/LLVM installation, CMake configuration, native
   compilation, CTest, and `check-nodal-native`.

Local and CI behavior share the same public commands:

```bash
./nodal check --contracts-only
./nodal core scala
./nodal bootstrap --mode prebuilt --prefix /absolute/toolchain
./nodal core native --toolchain /absolute/toolchain
```

`./nodal check` remains the complete local gate. The `--contracts-only` option
lets CI split policy checks from expensive Scala and native builds while keeping
the command composition in one implementation.

## Trigger policy

Core CI runs on:

- every push to `dev`;
- every push to `main`;
- every push to `increment/**`;
- every pull request targeting `dev` or `main`;
- explicit manual dispatch.

Both protected branches require `Core CI / required` before merge.

## Cache policy

Only immutable or re-verifiable dependency downloads are cached:

```text
~/.cache/coursier
~/.cache/nodal/mill
~/.cache/nodal/downloads
```

The native archive cache remains checksum-verified by the Nodal bootstrap before
extraction. The following are generated build outputs and must never be cached:

```text
out/
.validation/
.native-build/
.toolchains/
$RUNNER_TEMP/nodal-native-toolchain
```

No CI cache is accepted as evidence that compilation or tests passed. Every job
rebuilds its outputs and reruns its checks.

## Formatting baseline

Increment 8 adds a dependency-free formatting baseline covering UTF-8, LF line
endings, final newlines, trailing whitespace, indentation tabs in structured
sources, and JSON parsing. Increment 9 will add language-specific Scalafmt,
Scalafix, ClangFormat, ClangTidy, and Markdown tooling.

Run it locally with:

```bash
python3 scripts/check_formatting_baseline.py
```

## Dependency report

The scheduled dependency workflow is **report only**. It reads the checked
toolchain lock, checks stable Scala, Mill, uTest, and CIRCT candidates, uploads
Markdown/JSON evidence with `actions/upload-artifact@v7`, and opens or refreshes
an issue only when candidates exist.

It has `contents: read` and `issues: write` permissions. It cannot commit, push,
edit the lock, or open an upgrade pull request. CIRCT candidates require a
manual compatible LLVM-submodule derivation and regenerated checksums.

The workflow uses modern action generations:

```text
actions/checkout@v6
actions/cache@v5
actions/upload-artifact@v7
```

GitHub schedules workflows from the repository default branch. After the
bootstrap integration, `dev` should become the default branch so weekly reports
and ordinary contributor pull requests use the active integration baseline.
`main` remains the protected milestone-release branch.

## Contract validation

```bash
python3 scripts/check_ci_baseline.py
python3 -m unittest discover -s tests/ci -p 'test_*.py'
```

The checker rejects unsafe cache paths, CI command duplication, dependency
workflows with content-write capability, weakened branch policy, missing
ownership, and incomplete required-job aggregation.
