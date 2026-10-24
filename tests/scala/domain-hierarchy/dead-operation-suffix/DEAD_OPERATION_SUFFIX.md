# Dead generated-operation suffix controls

This checkpoint removes only unused pure sourceSuffix computation for generated-operation
metadata in construction-exact semantic naming. Legacy mode forces the suffix at its old
location before branch probes. Exact mode pays for the lazy cell and retains all executed
suffix charges. File reads, source freshness, naming passes and the 65,536-unit Session
budget remain unchanged. There is no stem/locale/digest cache.

The three fixture sources are resolver-unit diagnostics, not production DSL hardware.
CandidateApi.scala is deliberately classified as an API-frame fixture; all captured spans
come from the real authored DeadSuffixRoot.scala constructor. The tests assert actual API
owner/operation and source span before testing metadata, fifo, pipe, delay, operation-fsm
and owner-fsm branches. They assert complete in-process SemanticOriginResult equality,
correct categories, absent exact metadata suffix work, actual used-suffix failure,
nested mode/work/source-scope restoration and legacy eager metadata failure. Callback
sentinels/counting are not Session budget exhaustion or publication acceptance.

The matched baseline/current diagnostic used the same compiled fixture classes. Six full
result serializations and six legacy charge traces were byte-identical across APIs.
Serialization sorts unordered maps/sets but preserves all values; in-process full equality
is asserted independently. Used branches each observed five suffix-charge callbacks, consistent with one computation
under the reviewed lazy-val semantics despite multiple FIFO consumers. The fixture does
not assert a hardcoded callback count or a separate runtime once-only property. Lazy evaluation may add an exception frame, so the contract is
original cause, order and real helper execution, not identical exception stack bytes.

## Reproduce

Prepare an external coherent source root with build.mill/core/scala/docs markers, all
97 effective inputs, and these exact fixture bytes at the manifest's virtual paths.
Source pin rows can use virtual_path relative to that root or repository-relative path.
The runner does not create or modify source trees, build the API, or run native tools.
It freshly compiles only these three fixtures, then executes all six resolver cases.

```sh
python3 -I -B tests/scala/domain-hierarchy/dead-operation-suffix/run_dead_operation_suffix.py \
  --repository-root "$REPO" --java "$JAVA" \
  --compiler-classpath "$COMPILER_JARS" --runtime-classpath "$RUNTIME_JARS" \
  --api-jar "$API_JAR" --bridge-jar "$PARAMETER_BRIDGE" --bridge-jar "$BASE_BRIDGE" \
  --effective-source-map "$EFFECTIVE_MAP" \
  --suffix-source-root "$SOURCE_ROOT" --suffix-source-pins "$SOURCE_PINS" \
  --output-root "$NEW_IGNORED_OUTPUT" --timeout 120
```

Inputs are explicit JARs and verified sources. The manifest pins the accepted API;
changing it requires matched evidence. The runner records input/command hashes and
results, preserves -Werror, and checks the strict 246808576-byte reserve before commands.
This is not a continuous resource-peak guarantee.

## Remaining limitation

The unchanged typed17 direct-proxy witness now completes final naming but still exhausts
the fixed budget during actual publication's ClockRelationshipModel.Index groupBy under
verifySequentialAssignments. This remains an expected-positive capacity failure, not an
accepted refusal or completed feature. The retained 16 compatibility groups pass and all 14
ordinary raw outputs remain byte-identical; those consumers were not recompiled.
Public callbacks, generalized contexts, late naming and full direct-proxy publication
remain outside this checkpoint. No relationship Index or budget change is included.
