# Checkpoint 61 groundwork controls

The final maintained recipe freshly compiled both fixture groups and passed all four
named mains. This accepts only the scoped groundwork below, not complete direct-proxy
publication. The matched runs used prefix-api.jar SHA-256
`ac76f8cecd0482b42358071fbfbd3d597e211b606eeab86d2868b5773ad9a44c`.

## Scope

Default and only selectable suites:

- `prefix-oracle`: 16 synthetic selector diagnostics; not authored hardware evidence.
- `prefix-sessions`: real-session capture gates, metering failure/poison/recovery,
  caller callback and nested-resolution fallback; no typed17/final-publication claim.
- `prefix-source`: private locateSpan freshness and scope restoration. Changes/deletes
  only a newly created disposable file under the new output runtime directory.
- `bound-poison-recovery`: exact bound-pair observer seam and actual preparation,
  snapshot, publication exhaustion, poison/no rollback and fresh ordinary recovery.

The bound fixture also contains negative/parity entry points, but those groups fail
at final naming after establishing their seams. They are deliberately not selectable.
`pending-typed17/C2b2aRoot.scala` and `pending-typed17/C2b2aControls.scala` preserve the exact pair-fixed root/control witness bytes;
full typed17 still fails and is never compiled or executed by this runner. A green
summary here cannot imply typed17, final naming, whole reduction, native execution,
or broader public API acceptance. No budgets or production admission policy change.

`SOURCE-ONLY DRAFT` comments in original fixture files describe their historical
creation state, not the later evidence status. They are retained byte-for-byte to
preserve source line anchors. The old bound-controls-draft README is not copied: its
uncompiled/unexecuted wording is stale. This document supplies the current limits.

## Assets and lookup identity

`reduction_groundwork_c2b2a.fixtures.json` maps bundled exact fixture bytes to their
original source paths, SHA-256 values and virtual source paths. Passing prefix fixtures
compile from `lookup-prefix-controls-v5/tests/scala/domain-hierarchy/fixtures/reduction-proxy-bound/`;
synthetic source assets resolve at `prefixdiag/a/PrefixFixture.scala` and
`prefixdiag/b/PrefixFixture.scala`. Bound fixtures compile from the same virtual
fixture directory beneath `lookup-bound-controls`. These are exactly the existing
matched roots relative to the repository, not shortened replacement source paths.

The API/effective-source-map and full lookup pin manifests are explicit external inputs, supplied by CLI rather than copied. Their hashes are
recorded per run; the API hash remains fixed in the fixture manifest. The runner verifies all
selected lookup pins, every effective source-map projection, and the
bundled/lookup fixture equality. It never creates or edits a lookup tree, hard link,
production source, API, bridge, compiler, native backend, or frontend plugin.
Changing fixture virtual paths, fixture bytes or the accepted API requires an
explicitly reviewed new manifest. A new coherent root/source map requires fresh
matching evidence, not a silent path rewrite. The pending typed17 asset mapping is documentation only.

## Invocation

From a checkout with the explicit inputs prepared as described below, invoke the
maintained runner directly. Each variable denotes a verified input path or colon-separated
list of JAR paths; OUTPUT must be a new git-ignored directory inside REPO.

```sh
python3 -I -B tests/scala/domain-hierarchy/reduction-groundwork-c2b2a/run_reduction_groundwork_c2b2a.py \
  --repository-root "$REPO" --java "$JAVA" \
  --compiler-classpath "$COMPILER_JARS" --runtime-classpath "$RUNTIME_JARS" \
  --api-jar "$API_JAR" --bridge-jar "$PARAMETER_BRIDGE" --bridge-jar "$BASE_BRIDGE" \
  --effective-source-map "$EFFECTIVE_MAP" \
  --prefix-source-root "$PREFIX_ROOT" --prefix-source-pins "$PREFIX_PINS" \
  --bound-source-root "$BOUND_ROOT" --bound-source-pins "$BOUND_PINS" \
  --output-root "$OUTPUT" --timeout 120
```

The ignored historical `groundwork-maintained-final.recipe.json` records the exact
acceptance argument vector, not a required clean-checkout asset. The maintained runner
has no dependency on that recipe or historical scratch directories. Repeated `--suite`
selects a subset of the four named suites; no `all` mode includes pending tests.

Like `run_reduction_context_c2b1.py`, the runner uses isolated Python without bytecode,
explicit file/JAR inputs, recorded SHA-256 input hashes, sanitized environment,
completion markers, command/stdout/stderr/result records, and a strict 246808576-byte pre-command
disk reserve. Fixture groups compile separately into new empty class directories;
no prebuilt fixture classes enter the classpath. It preserves the matched compiler
flags (`-color:never -Wunused:all -Werror`) and repository working directory while
setting NODAL_WORKSPACE separately to each supplied coherent lookup root. Reserve checks are
before commands, not a continuous disk-peak guarantee. Only fixture compilation and
these four mains are run. The final maintained execution performed two fresh fixture
compiles and four successful named mains; it did not rebuild the API.

## Clean-checkout inputs

Supply `--effective-source-map` and, for each selected group,
`--prefix-source-root`/`--prefix-source-pins` or
`--bound-source-root`/`--bound-source-pins`. Roots may be externally prepared; the
runner does not require historical scratch directories. The supplied command pins
the existing exact roots for current acceptance. A portable pin list is a JSON array
of `{ "virtual_path": "relative/path", "sha256": "..." }` entries relative to
the supplied root. Legacy `{ "path": "repository/relative/path", "sha256": "..." }`
rows are accepted only when they lie beneath that exact supplied root. The effective
map uses virtual source path keys and objects containing `sha256`; historical origin
paths are provenance only and are not needed to run. All roots must include the
coherent build.mill/core/scala/docs markers, effective sources and mapped fixtures.
