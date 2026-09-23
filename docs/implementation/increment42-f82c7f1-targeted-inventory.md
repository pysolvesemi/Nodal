# Increment 42 targeted inventory — f82c7f1

This file records dispatch scope only; it is not qualification or acceptance evidence.

## Exact identities

- PR: #134, feature `increment/42-analog-hierarchy`, target `dev`.
- Target: `c3ea7cbf0432de7143a08b4431d51708c307d67e`, tree `6edbf959d28104a1de62623e8b7e31170232bd64`.
- Candidate: `f82c7f1cf5c30631d6d5bf6f46f2088fa0f0341d`, tree `026ebe9432ab6698a473ce123e1fbca9140b27c5`.
- Candidate parent: `9bd5db950c7e103bf86807276f7dc0814b1a735b`.
- Repair scope: exact pinned clang-format 22.1.8 output for `core/compiler/lib/Transforms/Passes.cpp`; formatted SHA256 `c5a4e54be5ac8c517fe230d6c98f68cc2e19f4c66759a2d7c7246b51b5933a51`.
- Live target moved after the previous controller by one documentation-only `AGENTS.md` commit; implementation/workflow bytes are unchanged. This controller pins the new target instead of silently using stale target identity.

## Historical parent evidence retained, not reused as current-head qualification

On `9bd5db9`, Core `35857009774`, Increment 21 `35857023864`, Increment 22 `35857037858`, and Increment 23 `35857051476` failed at the pinned formatter after earlier applicable checks. Increment 41 `35857065536` passed and produced 137/137 CTests plus 559/559 hierarchy-matrix cases. Those results remain historical after the source-format repair.

Pinned formatter diagnosis `35874022738` authenticated the exact 9bd source and `.clang-format`, ran clang-format 22.1.8, and produced the repair bytes above. A bounded materializer run `35874344621` created only the unattached Git blob `0a5a96eb320e32548a83bccace1d555595f66b11`; the feature commit was created separately with an explicit parent/tree and non-force ref update.

## Fresh affected workflow allowlist

All dispatch payloads are exactly `{ "ref": "increment/42-analog-hierarchy" }`.

| Workflow ID | Path | SHA256 | Required jobs |
| --- | --- | --- | --- |
| 338626156 | `.github/workflows/ci.yml` | `52ee1f40bd55eebc97e699bdca854ab5b96a1a98159ef0f4b7a8f2b96f0dc124` | `contracts`, `scala`, `native`, `required` |
| 342183625 | `.github/workflows/increment-21-native-semantic-pipeline.yml` | `3a26ba42e2140b3f8802a7dec8572e01f97ba2fd9fb87939beef6aa42b125931` | `semantic-pipeline` |
| 342322970 | `.github/workflows/increment-22-cross-layer-diagnostics.yml` | `ab2e6d0e6bcc8b10ad69bc88b89b2398090346b7c13c932b3fa231bc93d53cbf` | `cross-layer-diagnostics` |
| 342865392 | `.github/workflows/increment-23-backend-framework.yml` | `6451dee023216644c33748ad149d4d636a95ab5c7fe5c0606d07c1eb4f812087` | `backend-framework` |
| 352989730 | `.github/workflows/increment-41-analog-functions.yml` | `70d0b2d803180a2104015e3706fb0de7b3617c52a5f46ada2b3a69c863b55ff4` | `functions` |

No full CI, merge, roadmap completion or public hierarchy completion is authorized by this targeted dispatch.
