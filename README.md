# Increment 34 native dataflow v8

```text
workflow_run=33712805701
controller_sha=403c92128fc2a6b3430ad2de2fd5423e1f8d2fe2
feature_base=777fb1afa87f576c858d20001023fa5f7ce41dbe
feature_branch=increment/34-analog-control-flow-v1
published_sha=a095aa3832ee9f26d80cbaa6eb8252a743d05376
```

| Gate | Result | Exit |
|---|---:|---:|
| `scripts-compile` | PASS | 0 |
| `apply-inc34_patch_scala` | PASS | 0 |
| `apply-inc34_patch_native` | PASS | 0 |
| `apply-inc34_patch_contracts` | PASS | 0 |
| `apply-inc34_post_repair` | PASS | 0 |
| `apply-inc34_patch_native_dataflow` | PASS | 0 |
| `apply-inc34_patch_native_dataflow_repair` | PASS | 0 |
| `apply-inc34_patch_native_dataflow_portability` | PASS | 0 |
| `apply-inc34_patch_native_dataflow_contract_repair` | PASS | 0 |
| `native-bootstrap` | PASS | 0 |
| `lint-bootstrap` | PASS | 0 |
| `scala-format` | PASS | 0 |
| `cpp-format` | PASS | 0 |
| `checker-python` | PASS | 0 |
| `increment33-contract` | PASS | 0 |
| `increment34-contract` | PASS | 0 |
| `increment34-mutations` | PASS | 0 |
| `diff-check` | PASS | 0 |
| `scala-core` | PASS | 0 |
| `native-core` | PASS | 0 |
| `runtime-witness` | PASS | 0 |
| `construction-witness` | PASS | 0 |
| `direct-native-dataflow` | PASS | 0 |
| `repository-style` | PASS | 0 |
| `lease-guard` | PASS | 0 |

## Candidate diff
```text
 .../include/nodal/Dialect/Nodal/NodalOps.td        | 103 +++
 core/compiler/lib/Dialect/Nodal/NodalOps.cpp       | 765 +++++++++++++++++++++
 core/compiler/test/CMakeLists.txt                  |  48 ++
 .../src/nodal/bridge/AnalogProceduralMlir.scala    | 497 ++++++++++++-
 .../internal/testkit/ScalaToMlirBridgeTests.scala  |  48 ++
 .../increment34-analog-control-flow.md             |  31 +-
 scripts/check_increment34.py                       | 177 ++++-
 tests/compiler/fixtures/increment34/README.md      |  16 +-
 tests/compiler/fixtures/increment34/manifest.json  |  38 +-
 tests/compiler/test_increment34.py                 |  85 ++-
 10 files changed, 1764 insertions(+), 44 deletions(-)
```

**PASS — published exact head `a095aa3832ee9f26d80cbaa6eb8252a743d05376` to PR #109.**
