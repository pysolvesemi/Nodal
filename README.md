# Increment 34 structured compiler-IR diagnostic

```text
controller_sha=6b16eb5d204b2f68eecfc98919d042502698d37b
controller_branch=automation/inc34-structured-ir-diagnostic-v3
feature_branch=increment/34-analog-control-flow-v1
feature_base=604dd738c6acff5191d6f851155d3fcad103531f
run_id=33672686387
```

## Gate results

| Gate | Result | Exit |
|---|---:|---:|
| `patch-python-compile` | PASS | 0 |
| `patch-scala` | FAIL | 1 |
| `patch-native` | PASS | 0 |
| `patch-contracts` | PASS | 0 |
| `patch-post-repair` | FAIL | 1 |
| `scala-format` | SKIP | 1 |
| `cpp-format` | SKIP | 1 |
| `increment33-contract` | PASS | 0 |
| `increment34-contract` | FAIL | 1 |
| `increment34-mutations` | FAIL | 1 |
| `diff-check` | PASS | 0 |
| `scala-core` | PASS | 0 |
| `native-core` | SKIP | 1 |
| `runtime-witness` | PASS | 0 |
| `construction-witness` | PASS | 0 |
| `native-direct-roundtrip` | FAIL | 1 |
| `repository-style` | SKIP | 1 |

## Candidate diff

```text
 .../workflows/increment-34-analog-control-flow.yml |  31 +-
 .../include/nodal/Dialect/Nodal/NodalOps.td        | 103 +++++
 core/compiler/lib/Dialect/Nodal/NodalOps.cpp       | 368 ++++++++++++++++
 core/compiler/test/CMakeLists.txt                  |  36 ++
 .../src/nodal/bridge/AnalogProceduralMlir.scala    | 486 ++++++++++++++++++++-
 .../increment34-analog-control-flow.md             |  33 +-
 scripts/check_increment34.py                       | 135 +++++-
 tests/compiler/fixtures/increment34/README.md      |  14 +-
 tests/compiler/fixtures/increment34/manifest.json  |  35 +-
 tests/compiler/test_increment34.py                 |  51 ++-
 10 files changed, 1247 insertions(+), 45 deletions(-)
```

**Outcome: FAIL. The feature branch was not modified. Exact logs are retained on the diagnostic-results branch.**
