# Typed digital collection callbacks

These focused witnesses use the production posttyper callback phase, the real
public `map`/`zipWith`/`fold`/`scan`/`reduce` signatures, and the existing pinned
Scala compiler. The phase is registered in the existing Nodal constructor plugin;
there is no second plugin or separate callback compiler.
The `reduce` signature participates in frontend callback checking only in this
checkpoint; its widening recurrence implementation remains open. These witnesses
do not establish working reduction semantics.

Run after building the API and constructor plugin with the existing local Mill
owner. Pass the existing classpaths/artifacts and a JDK supporting the API's
`ScopedValue` use (the repository's pinned JDK 25 is suitable):

```sh
python3 tests/scala/digital-collections/run.py \
  --compiler-classpath "$COMPILER_CP" \
  --api-classpath "$API_CP" \
  --plugin-jar "$PLUGIN_JAR" \
  --java "$JAVA" \
  --out-parent .work/digital-collection-callbacks
```

The runner does not download tools, invoke Mill, run CI, or publish anything.
Outputs are isolated under `.work`. Each negative must report its expected
source-located diagnostic; a compiler crash or an unrelated failed compile does
not count. Positive compilation uses `-Ycheck:all` to validate the injected typed
Function1/Function2 wrappers. The runtime witness calls the public API both with
the real compiler transformation and from a separately compiled uninstrumented
factory; the latter must reject before its mutable callback executes.

## Supported frontend profile and ownership

The phase resolves the actual library symbols and indexes same-compilation
method/value bodies once. It accepts immutable captured hardware values, typed
combinational arithmetic, bitwise/comparison/selection/conversion and shape
operations, nested collection callbacks, immutable closure aliases, and pure
same-compilation helpers with statically closed dispatch. A helper's spelling is
irrelevant. Singleton initialization is checked when a callback first references
another same-compilation helper object. Runtime construction and native region
verification remain independent authorities for hardware effect/type/scope rules.

Unknown effects fail closed. Mutable/lazy Scala reads, writes, host branching,
loops, exceptions, arbitrary allocation, recursive helpers, open virtual
dispatch, unknown callback parameters, and unavailable separately compiled helper
bodies are unsupported. The diagnostic names distinguish those causes. The
current bounded profile visits at most 8192 typed nodes per root callback and
limits typed nesting and active helper depth to 128; an excessive graph reports
`NODAL-COLLECTION-CALLBACK-LIMIT`. Primitive host arithmetic may form static
literal widths; host control flow cannot change region topology.

The API helper contains private final callback carriers. Its package-private
factories are compiler-owned: authored references reject before transformation.
There is no public purity annotation or implementable marker. This is an ordinary
public source boundary, not a security promise against reflection, forged
bytecode, or deliberately spoofing internal implementation packages without the
compiler plugin. Source and native verifiers do not infer purity from source text
or fixture names. Wrapping evaluates the original callback expression once and
forwards the collection owner's single template invocation unchanged; it never
executes callbacks per lane.
