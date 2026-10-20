# Exact Interface hierarchy source checks

The public symbolic payload fixture is
`core/scala/testkit/test/src/nodal_external/Increment58InterfacePayloadSource.scala`.
Its focused source/bridge selector is
`nodal.internal.testkit.Increment58InterfacePayloadTests`.
`nodal.internal.testkit.Increment58InterfacePayloadNativeTests` separately replays
actual emitted MLIR through `--nodal-gate-default`; set `NODAL_ROOT` and
`NODAL_NODALC` to the repository and an explicitly selected matching compiler.
These checks do not establish Interface target emission.

`fixtures/negative/PrivateInstanceModule.scala` is deliberately outside the
positive test source tree. Compile it with pinned Scala 3.8.4, the current API
classpath and the constructor plugin. Compilation must fail at
`child.module.output` with E173 and the `private[nodal]` access diagnostic, not a
compiler crash or unrelated typing failure. The runtime source suite separately
checks that hiding a raw endpoint inside a public selector result wrapper does
not bypass exact boundary-provenance rejection.

A standalone negative invocation, using already-built artifacts, is:

```sh
mkdir -p .work/interface-hierarchy-negative
"$JAVA" -cp "$SCALA_COMPILER_CLASSPATH" dotty.tools.dotc.Main \
  -classpath "$SCALA_COMPILER_CLASSPATH:$NODAL_API_CLASSPATH" \
  -Xplugin:"$NODAL_PLUGIN_JAR" -Xplugin-require:nodal-constructor \
  -deprecation -feature -unchecked -Wunused:all -Werror -color:never \
  -d .work/interface-hierarchy-negative \
  tests/scala/interface-hierarchy/fixtures/negative/PrivateInstanceModule.scala
```

An expected nonzero exit alone is insufficient: inspect the exact source-located
access diagnostic. Keep all compiler output under ignored `.work`.
