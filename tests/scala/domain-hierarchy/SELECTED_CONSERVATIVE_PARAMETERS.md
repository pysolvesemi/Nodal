# Selected conservative literal parameters

This focused runner maintains the selected one-child and shared-anchor two-child conservative parameter
slices. It does not rebuild a native probe, discover toolchain artifacts, invoke
CI, certify unsupported parameter combinations, or execute held downstream
consumer/comparator/firtool work.

## Inputs and invocation

Use Python 3.11 or newer, with import isolation and bytecode disabled. Supply
matching already-built artifacts and the canonical source tree containing the
nine Scala fixtures at `fixtures/selected-conservative-parameters` relative to
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

Six Scala entry points compile together with the matching plugin, unused warnings
and warnings-as-errors. They cover literal/default/alternate source construction,
six unsupported source forms plus recovery, eight forged snapshot forms, eight
duplicate-attribute order cases, duplicate-binding transport, and freshly authored
two-child topology. No generated two-child input is committed.

The native phase makes 28 invocations: nine one-child verify/adapt/emit calls,
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
