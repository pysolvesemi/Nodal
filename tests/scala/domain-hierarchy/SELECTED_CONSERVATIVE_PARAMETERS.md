# Selected conservative parameters

This focused runner maintains the selected one-child and shared-anchor two-child conservative parameter
slices. It does not rebuild a native probe, discover toolchain artifacts, invoke
CI, certify unsupported parameter combinations, or execute held downstream
consumer/comparator/firtool work.

## Inputs and invocation

Use Python 3.11 or newer, with import isolation and bytecode disabled. Supply
matching already-built artifacts and the canonical source tree containing the
twelve Scala fixtures at `fixtures/selected-conservative-parameters` relative to
this guide. That source tree must retain `build.mill`, `core/scala`, and `docs`
markers. It may be a sealed tree or the actual checkout. The runner sets
`NODAL_WORKSPACE` and the subprocess working directory to that tree.

```sh
python3 -I -B tests/scala/domain-hierarchy/run_selected_conservative_parameters.py \
  --repository-root "$REPOSITORY" --source-root "$CANONICAL_SOURCE" \
  --compiler-classpath "$SCALA_COMPILER_CLASSPATH" \
  --runtime-classpath "$SCALA_RUNTIME_CLASSPATH" \
  --api-jar "$API_JAR" --bridge-jar "$BRIDGE_JAR" \
  --plugin-jar "$PLUGIN_JAR" --java "$JAVA" --probe "$CONSERVATIVE_PROBE" \
  --output-root "$REPOSITORY/.work/selected-parameters-fresh" --timeout 120
```

Classpath entries must be existing explicit files separated by the platform
classpath separator. No wildcard, classes directory, build output discovery, or
historical fixture classes are accepted. Runtime classpath should contain only
required Scala/runtime dependencies, not another compiler profile's Nodal
classes. Compiler classpath is used only to launch `dotty.tools.dotc.Main`.
Supply API/bridge support through repeatable `--api-support-jar` and
`--bridge-support-jar` options when the selected artifacts are overlays. The
runtime order is fresh fixture classes, bridge overlay and ordered bridge
support, API overlay and ordered API support, then runtime dependencies.

The source53 API composition requires unchanged Java annotation ABI support from
its accepted API artifact; the Scala-only API overlay is not a standalone JAR.
The selected bridge likewise uses its matching accepted bridge support artifact.
Supply these explicitly. Do not silently drop that support or replace it with
stale fixture/compiler classes. The caller owns matching source/artifact
composition; the runner hashes exact files and records ordered commands but
cannot prove a prebuilt binary was compiled from a supplied source tree.

Coordinate this runner with other local work: hold the repository-root
`.work/f58-mill.lock` and `.work/fifo-native-build.lock` externally for the
focused run. It does not acquire or rebuild shared artifacts. A stricter parent
disk reservation takes precedence over its 200 MiB minimum.

Output must not exist and must resolve strictly beneath the repository's ignored
root `.work`. All generated MLIR, compiler classes, stdout/stderr, input hashes,
command receipts, and summary stay there. Temporary directories, home, JVM temp
and JVM user-home are redirected there. The runner checks a 200 MiB free-disk
reserve before subprocesses and applies a per-subprocess timeout. Keep input
artifacts unchanged throughout execution. A nonzero status, signal, timeout,
missing anchor or missing diagnostic is a failure, never an inferred pass.

## Maintained assertions

Eight Scala entry points compile together with the matching plugin, unused warnings
and warnings-as-errors. They cover literal/default/alternate source construction,
five retained unsupported source forms plus recovery, eight forged snapshot forms, eight
duplicate-attribute order cases, duplicate-binding transport, and freshly authored
two-child topology. No generated two-child input is committed.

The retained literal native baseline makes 28 invocations: nine one-child verify/adapt/emit calls,
twelve named mutation emits, one duplicate-snapshot refusal, three two-child
verify/adapt/emit calls, and three independent fixed-override emit/transaction
checks. The original Scala topology and gain-binding assertions remain intact.
Two-child output checks retain gains 3/7, default gain 2, the gain equation,
nonunit occurrence normalization, two child instances/four terminals, fourteen
correspondence records, and six actual shared-top aliases (four generated and
two selected at ordinal 1). Assertions retain real parameter use in the
child equation, different actual/default values, nonunit induction, adapter
instance/terminal shape, eight correspondence records and flow directions,
malformed attachment/refusal cases, sixteen additional bindings and a late
unknown binding, and transactional fixed-override refusals on one-child and two-child inputs. Mutation
anchors are counted and selected occurrence symbols are derived from fresh MLIR.
This is a focused text-based boundary regression, not another semantic engine.

## Direct-parent Real continuation

The additional source positives forward parent default 5 to child default 2,
reuse a child with distinct parent defaults 5/9, and retain distinct source
identities when both parent defaults are 5. Snapshot identity, exact parent-body
reference/generated override transport, symbolic Verilog-A, deterministic
re-emission and conservative topology are asserted. The source expression and
foreign-actual refusals remain, with twenty forged parent snapshot controls for
exact owner inventory, declaration attributes and representation.

The selected API and immutable bridge admit only a directly referenced ordinary
dimensionless Real declaration belonging to the enclosing parent. They do not
resolve identity from a rendered name, path prefix, equal default or global
lookup. Existing literal child defaults, fixed scalar one-level topology and
other selected conservative restrictions remain. Native parameter classification
has the canonical lexical scope: an otherwise verified one-level Generate
directly inside a Module. It is not restricted to selected Interfaces or literal
family bounds. The independent conservative boundary still enforces the narrower
selected profile and rejects duplicate symbolic actuals and both equal and
unequal literal/SSA collisions.

The maintained continuation adds 29 native invocations, for 57 total: twelve
parent positive/determinism calls, two parent expression-default calls and fifteen
negative emits. Arbitrary actual expressions, generated-local references, foreign
owners and runtime/type/unit/classification mismatches remain refused. A generated
f64 block-argument mutation fails the existing operation profile, not an isolated
low-level Real classifier proof. A symbolic fixed formal fails selected Interface
admission; the retained literal fixed cases establish transactional backend
refusal. Excluding 5 from a child whose own default remains 2 checks that forwarded
parent defaults still reach the existing constraint owner. This is default-based
Real checking, not proof over every legal Real assignment. Native parent
expression defaults remain legal without adding Scala Real expression-default
transport or allowing expression actuals.

Ownership corruption must be represented as a retained discardable attribute on
ConstParameterRef, outside its inherent-properties dictionary. Unknown keys in
that dictionary can be discarded by generated property conversion, making such a
mutation ineffective. The corrected carrier is asserted explicitly; a successful
emit from the ineffective mutation is preserved as a harness failure, not counted
as negative coverage. Expected diagnostics identify the actual verifier stage
rather than assuming all failures reach ParameterModel or conservative adaptation.

The [native supplement](../../compiler/fixtures/increment58/parent-real-forwarding/README.md)
adds twelve separate general-Generate, generated-Integer and direct scalar
predecessor invocations through the existing shared probe. It is not included in
the 57 selected invocations.

## Checkpoint 68 validation status

Focused development evidence composes eight retained Scala mains and 49 native
prefix calls with eight separately corrected selected refusals: 57 selected
native invocations in total. A separate twelve-call native supplement also passes:
69 semantic native checks overall.
The corrected tail requires nonzero nonsignal status, the exact observed-stage
diagnostic and empty target output in every case. One additional diagnostic-only
normalization of the old ineffective ownership mutation confirms that its unknown
properties disappeared while the same parent reference remained the override
actual; it is not included in either the 57 or twelve semantic counts.

The exact preserved Scala classes were compiled in the earlier V3 run and replayed; candidate
2 did not freshly compile them. The corrected tail directly loads the maintained
runner's mutation definitions and proves unchanged input bytes for seven cases;
only the corrected retained-attribute ownership carrier changes. Source comparison
limits the portable selected runner changes to observed diagnostics, that carrier,
and stronger empty-target checks. Earlier compiler, expectation and carrier
failures remain recorded rather than relabeled as passes. A complete invocation
of the corrected portable selected runner, including its fresh compilation path,
was not run. Composing independently recorded portions is not a whole-run pass.

The three old-native52 comparisons in development evidence are historical only.
They are deliberately excluded from maintained invocation counts and require no
old binary dependency. Broader predecessor suites remain separate and are not
claimed by this runner.

Source location metadata is part of generated output. Relocating fixtures changes
paths/spans and full output hashes. Do not normalize metadata or claim byte parity
with historical exports. Review newly generated output and record the actual
source root; the input hashes and command records are run-local evidence only.
Keep generated receipts, logs, binaries, sealed snapshots and historical exports
out of commits. Only this guide, runner and authored Scala fixtures are maintained
source assets.
