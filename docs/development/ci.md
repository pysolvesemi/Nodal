# Continuous integration baseline

Nodal uses one generic **Core CI** workflow for every increment branch and every
pull request into either protected permanent branch.

## Required gate

The stable required status check is:

```text
Core CI / required
```

It succeeds only when all independent jobs pass:

1. `contracts` — architecture, formatting, lint, Markdown, package visibility,
   contribution policy, toolchain locks, Python tests, and online provenance;
2. `scala` — complete Scala 3 module compilation and smoke tests;
3. `native` — locked CIRCT/LLVM installation, CMake build, CTest, and ClangTidy.

Local and CI behavior share the same public commands:

```bash
./nodal style bootstrap --prefix /absolute/lint-toolchain
./nodal check --contracts-only --online-toolchain \
  --lint-toolchain /absolute/lint-toolchain --base-ref origin/dev
./nodal core scala
./nodal bootstrap --mode prebuilt --prefix /absolute/native-toolchain
./nodal core native --toolchain /absolute/native-toolchain \
  --lint-toolchain /absolute/lint-toolchain
```

## Trigger policy

Core CI runs on pushes to `dev`, `main`, and `increment/**`, pull requests into
`dev` or `main`, and manual dispatch. Both protected branches require
`Core CI / required` before merge.

## Diff-aware policy checks

The contracts job checks out full history and resolves a base ref:

- pull requests compare against `origin/$GITHUB_BASE_REF`;
- increment-branch pushes compare against `origin/dev`;
- permanent-branch pushes compare against the previous commit.

That diff drives the design-gate policy without making ordinary style checks
dependent on GitHub metadata. Pull-request events additionally validate branch,
title, body-section, and promotion rules.

## Cache policy

GitHub Actions uses `actions/cache@v5` only for immutable or re-verifiable
dependency downloads:

```text
~/.cache/coursier
~/.cache/nodal/mill
~/.cache/nodal/downloads
```

The generated build outputs and installed toolchains are never cached as
validation evidence:

```text
out/
.validation/
.native-build/
.toolchains/
$RUNNER_TEMP/nodal-native-toolchain
$RUNNER_TEMP/nodal-lint-toolchain
```

The native archive cache remains checksum-verified before extraction. Every job
rebuilds outputs and reruns checks. Cache restoration can improve performance,
but it never substitutes for validation evidence from the current commit.

## Language-specific gates

The contracts job installs the pinned native lint tools and runs:

```bash
./nodal style check --base-ref <resolved-base>
```

This includes Scalafmt, Scalafix, ClangFormat, Markdown structure and links,
Scala package ownership, pull-request policy, and design-gate enforcement.
ClangTidy runs in the native job after `compile_commands.json` is available.

## Dependency report

The scheduled dependency workflow is **report only**. It uploads Markdown/JSON
evidence with `actions/upload-artifact@v7` and may open or refresh an issue. It
cannot commit, push, edit a lock, or open an automatic upgrade pull request.

## Contract validation

```bash
python3 scripts/check_ci_baseline.py
python3 scripts/check_increment9.py
python3 -m unittest discover -s tests/ci -p 'test_*.py'
python3 -m unittest discover -s tests/lint -p 'test_*.py'
```
