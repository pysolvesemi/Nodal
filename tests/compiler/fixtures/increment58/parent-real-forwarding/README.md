# Direct-parent Real forwarding regressions

This native-only supplement uses ten maintained MLIR fixtures and the existing
[shared conservative probe](../generated-conservative-adapter/README.md) for twelve
bounded invocations. It neither builds nor discovers artifacts, compiles Scala,
loads historical binaries, installs tools, invokes firtool/CI, nor runs broad
qualification. Use Python 3.11 or newer and a matching prebuilt extended probe.

## Invocation

From the repository root:

```sh
python3 -I -B tests/compiler/fixtures/increment58/parent-real-forwarding/run_supplement.py \
  --repository-root "$REPOSITORY" --probe "$CONSERVATIVE_PROBE" \
  --output-root "$REPOSITORY/.work/parent-real-native-fresh" --timeout 120
```

The output must be new and strictly inside the repository's ignored `.work`.
Hold the existing repository Scala/native locks externally and obey the stricter
caller resource reservation; the runner requires a 235 MiB free-space reserve.
It records exact input hashes and commands, redirects temporary/home paths, retains
timeout output, and requires expected statuses, causal diagnostics and an empty
failure aggregate. Generated receipts and target outputs are run-local evidence,
not maintained fixtures. Inputs and the explicit probe must remain unchanged.
There is no dependency on scratch origins or historical acceptance manifests.

## Selected cases

- `general-parent-real`: a parent ordinary dimensionless Real parameter with
  default 5 directly feeds a child formal with default 2 inside a one-level
  Generate. No Interface is present. Canonical parameter verification must pass.
- `general-foreign-parent`: unresolved local parameter identity is refused by
  existing ConstParameterRef verification. This is not independent classifier
  coverage.
- `general-nested-parent`, `general-runtime-parent`, `general-integer-parent`:
  reject the respective lexical-owner, runtime-value and f64-formal/type mismatch
  at the generated override admission boundary.
- `general-excluded-parent-default`: operation verification passes, then canonical
  ParameterModel rejects forwarded default 5 against the child's exclusion while
  its own default 2 remains valid. Both invocations are required.
- `integer-finite-signed`, `integer-checked-empty`: retain signed all-lane and
  checked-empty generated-Integer success.
- `integer-all-lane-not-default`: reject arithmetic invalid for another legal
  generated lane even though the declaration default would be valid.
- `scalar-direct-forwarding`: preserve direct analog forwarding through both named
  emission and MaterializedScalar. The latter performs two emissions on the same
  source and checks exact source preservation and target determinism.

The canonical shared classifier proves exact override/instance/formal linkage,
module-body parent ownership, ordinary f64 declaration/type/unit profile and
reference order before the family. It only classifies the representation; fixed
formal, evaluation, normalization, constraint and canonical-binding checks remain
with ParameterModel. The Integer all-lane branch is unchanged. Independent selected
conservative checks still require their existing topology, literal child defaults,
unique symbolic formals and absence of literal/SSA collisions. The selected
[Scala continuation](../../../../scala/domain-hierarchy/SELECTED_CONSERVATIVE_PARAMETERS.md)
maintains that narrower path separately.

## Source origins and limits

The signed Integer fixture and checked-empty/non-default mutations derive from
`checkFiniteConstructorBindingPurpose` in
[ParameterModelTest.cpp](../../../../../core/compiler/test/Unit/ParameterModelTest.cpp),
HEAD67 lines 1051-1074 and 1133-1140. The scalar fixture derives from `model(3,
false)` and its assertions in
[ScalarHierarchyMaterializationTest.cpp](../../../../../core/compiler/test/Unit/ScalarHierarchyMaterializationTest.cpp),
HEAD67 lines 17-104 and 134-165. These are authored source fixtures extracted and
adapted for the shared probe, not preserved generated target outputs. The six
general-Generate fixtures are explicit focused inputs for the new admission path.

These invocations do not run the complete C++ unit suites or separately assert
Integer query/fact-null internals. Real constraints retain default-based checking,
not an all-legal Real envelope. The general native profile is an otherwise verified
one-level Generate directly inside Module; it is not restricted to selected
Interfaces or literal family bounds. There is no new public API, ExtendedAPI,
capacity, hierarchy flattening or arbitrary-expression feature.

## Checkpoint 68 status

The scratch supplement completed all twelve semantic invocations successfully,
including two internal materialized emissions. Exact probe and fixture bytes are
preserved here through canonical mappings. The maintained portable runner removes
only scratch origins/fixture-hash receipt dependencies, replacing them with a
script-relative exact fixture inventory while retaining runtime before/after input
hashing. Its CASES and complete invocation/assertion tail are byte-identical to the
executed supplement. This is source equivalence for the semantic checks, not a
claim that the adapted portable entry point was executed end to end.

Selected-path evidence separately composes eight retained Scala mains, 49 native
prefix calls and eight corrected tail refusals, for 57 selected native invocations
and 69 semantic native checks including this twelve-call supplement.
A ninth diagnostic-only call normalizes the old ineffective ownership-property
mutation and confirms removal of the unknown keys without changing the parent
reference/override linkage. It is excluded from both semantic counts. The corrected
retained-attribute mutation refuses with empty target output and its expected
stage diagnostic. Neither a complete corrected selected run nor a fresh candidate-2
Scala compilation is claimed. Earlier failures remain retained.
