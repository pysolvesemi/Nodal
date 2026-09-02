# Increment 34 native dataflow v7

```text
workflow_run=33674517041
controller_sha=e2ba893d6a0ec92a741cb9dff56d4534c981e805
feature_base=604dd738c6acff5191d6f851155d3fcad103531f
feature_branch=increment/34-analog-control-flow-v1
```

| Gate | Result | Exit |
|---|---:|---:|
| `scripts-compile` | PASS | 0 |
| `apply-inc34_patch_scala` | FAIL | 1 |
| `apply-inc34_patch_native` | PASS | 0 |
| `apply-inc34_patch_contracts` | PASS | 0 |
| `apply-inc34_post_repair` | FAIL | 1 |
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
| `increment34-contract` | FAIL | 1 |
| `increment34-mutations` | FAIL | 1 |
| `diff-check` | PASS | 0 |
| `scala-core` | PASS | 0 |
| `native-core` | PASS | 0 |
| `runtime-witness` | PASS | 0 |
| `construction-witness` | PASS | 0 |
| `direct-native-dataflow` | PASS | 0 |
| `repository-style` | FAIL | 1 |

## Candidate diff
```text
 .../workflows/increment-34-analog-control-flow.yml |  31 +-
 .../include/nodal/Dialect/Nodal/NodalOps.td        | 103 +++
 core/compiler/lib/Dialect/Nodal/NodalOps.cpp       | 759 +++++++++++++++++++++
 core/compiler/test/CMakeLists.txt                  |  48 ++
 .../src/nodal/bridge/AnalogProceduralMlir.scala    | 491 ++++++++++++-
 .../increment34-analog-control-flow.md             |  31 +-
 scripts/check_increment34.py                       | 179 ++++-
 tests/compiler/fixtures/increment34/README.md      |  16 +-
 tests/compiler/fixtures/increment34/manifest.json  |  38 +-
 tests/compiler/test_increment34.py                 |  85 ++-
 10 files changed, 1736 insertions(+), 45 deletions(-)
```

**FAIL — PR #109 was not modified. See the per-gate logs in this branch.**
