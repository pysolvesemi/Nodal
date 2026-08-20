# Scala build bootstrap

Increment 4 establishes Nodal's first executable build without freezing the Nodal language API.

## Pinned toolchain

| Tool | Pin | Policy |
| --- | --- | --- |
| Scala | 3.8.4 | Scala Next only; no Scala 2 cross-build or legacy source mode. |
| Mill | 1.1.7 | Repository-local bootstrap scripts and matching build header. |
| JVM | `zulu:25` | Managed by Mill for build evaluation, compilation, and tests. |
| uTest | 0.9.1 | Bootstrap smoke tests only. |

The repository wrappers download the version-specific official Mill bootstrap from Maven Central into a user cache. `.mill-version` and the `build.mill` header carry the same Mill pin.

## Module mapping

The Mill modules map one-to-one to the architectural descriptors in `core/modules.toml`:

```text
core.integrations
core.scala.api
core.scala.frontend
core.scala.bridge
core.scala.sim
core.scala.cli
core.scala.testkit
core.scala.testkit.test
```

The Scala dependency graph mirrors the checked architecture graph. `core.compiler` remains a native-only architectural module and is intentionally absent from the Scala build until Increment 6.

## Commands

```bash
./mill version
./mill __.compile
./mill core.scala.testkit.test
python3 scripts/check_scala_bootstrap.py
python3 scripts/check_architecture.py
python3 -m unittest discover -s tests/architecture -p 'test_*.py'
python3 -m unittest discover -s tests/build -p 'test_*.py'
```

On Windows, use `mill.bat` in place of `./mill`.

## Bootstrap namespace

All Increment 4 Scala sources live under `nodal.bootstrap`. They prove module wiring, Scala 3 language support, managed JDK 25 execution, and the frontend-to-textual-boundary shape. They are not the Nodal public API and create no compatibility commitment before the API design gate in Increments 10–12.
