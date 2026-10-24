# C1 trusted construction controls

This runner is a focused source-construction regression, not public reduction callback
admission, a capacity promise, backend/native evidence, or broad qualification. Its Scala
fixtures are trusted package-internal tests. They deliberately reflect private Session
entrypoints, corrupt a private capture registry in refusal tests, and debit an existing
owner budget in exhaustion tests. Those are harness capabilities, not supported user API.

## Explicit inputs

Use Python 3.11+ and the existing tested Scala 3.8.4/JDK 25 toolchain. The caller supplies
the compiler and runtime JAR classpaths, Java executable, primary API JAR, any separately
required API support JARs, matching coherent source root, unused git-ignored output
directory inside the repository, and a per-command timeout. No plugin is used or required.
No tool discovery, download, API build, native tool invocation, or CI is performed.

```sh
python3 -I -B tests/scala/domain-hierarchy/run_boundary_closure_c1.py \
  --repository-root "$REPOSITORY_ROOT" --source-root "$COHERENT_SOURCE_ROOT" \
  --compiler-classpath "$SCALA_COMPILER_CLASSPATH" \
  --runtime-classpath "$SCALA_RUNTIME_CLASSPATH" \
  --api-jar "$API_JAR" --java "$JAVA" --output-root "$FRESH_IGNORED_OUTPUT" \
  --timeout 120
```

Repeat `--api-support-jar PATH` only for genuine API dependencies. The primary API comes
first, then these explicit support artifacts and runtime JARs. No prebuilt fixture class
directory is accepted. All selected fixtures are freshly compiled together; fresh fixture
classes are first on each test JVM classpath. The flags are the existing focused-control
flags: `-color:never -deprecation -feature -unchecked -Wunused:all -Werror`.

## Source identity

`boundary_closure_c1.fixtures.json` maps maintained fixtures to their lookup-root
relative paths and pins their exact formatted SHA-256 bytes. Normal selective imports
keep the two Interface port declarations readable within the formatter's line width.
Actual physical source lines and bytes remain charged inputs; no historical source
identity is claimed after formatting or factoring. The runner
requires the supplied coherent source root to already contain exact matching files at
those paths. It neither creates that root nor rewrites/copies source, seeds caches, changes
budgets, adjusts names, or substitutes provenance. It compiles those exact lookup-root
files, uses that root as working directory and `NODAL_WORKSPACE`, and checks fixture hashes
before and after commands. Keep API sources coherent with the supplied API artifact. The
runner records API/source hashes but does not independently prove the JAR was built from
those sources; that provenance remains the caller's prerequisite.

The fixture locations in the lookup tree are intentionally retained under
`core/scala/api/test/src/nodal/internal/testkit/`, even for the three `package nodal` meter
fixtures. Moving their maintained copies does not authorize changing lookup paths or lines.
Source-size and source-discovery work are real charged inputs. A larger/different coherent
tree may legitimately exhaust the unchanged 65,536 incremental profile. Do not shrink the
tree selectively, alter fees, or relax a failing assertion to call that a pass.

## Selected cases

Default suites:

- `epoch`: 12 actual Session controls, including empty drain, second batch, stale query/
  graph tickets, authored freeze, monotone exhaustion, final-name identity, signature
  reuse/refusal, deliberate private corruption, and fresh-session recovery.
- `interface`: actual ExternalModule InterfaceArray counts 2 and 3, a new second-batch
  view, no reappend to the first count, empty-drain idempotence, and final publication.
- `guard`: canonical final-name validation failure poisons the owner, retires its query
  ticket, prevents publication, and permits a new independent Session. The predecessor
  observation mode is deliberately not run as a success case on the corrected API.
- `descriptor`: 20 owner-budget/inert/recovery checks, including lazy descriptor inputs,
  canonical schema/map/enum traversals, ordinary inertness, and nested-session refusal.

Use repeated `--suite NAME` for focused selection, or `--suite all` to include:

- `query-meter`: shared meter propagation, live cached consumers, ticket retirement,
  exact threshold and unchanged ordinary immutable-query behavior.
- `semantic-work`: direct private source-read accounting, raw UTF-8 bytes, growth refusal,
  callback failure restoration and ordinary resolver parity.
- `semantic-cache`: seven per-resolution cache groups, same-call reuse, cross-call reads,
  source-change refusal, nested restoration and ordinary uncached behavior.

The three meter suites exercise lower-level contracts directly and do not replace actual
Session integration evidence. Source-meter tests use temporary files only under the
runner-owned JVM temporary directory. Materializer i64 profile refusal/capture tests,
Area/generated discovery supplements, ordinary predecessor parity, and downstream native
targets are intentionally outside this initial runner, not silently counted as covered.

## Receipts and failure handling

Only the fresh ignored output tree is written. It contains classes, temporary/home/cache
directories, input hashes, exact command JSON, separate stdout/stderr, incremental result
records, and a final summary only after every selected suite succeeds. Each command has
the requested timeout. Unexpected exits, signals, missing expected completion text, source
changes and timeout are failures, not successful negative controls. The Scala suites catch
their intended construction refusals internally. The runner does not retry or reinterpret
a failing source fixture. Preserve logs when investigating a mismatch.

Static Python/manifest checks are not a successful Scala run. A successful invocation
qualifies only its exact API, support artifacts, selected fixtures and coherent lookup tree.
It does not establish a path-independent capacity or full pending-geometry guarantee.
