# Conservative and pending-width regression fixtures

These fixtures preserve exact accepted source bytes. Relocation is not a fresh
execution claim: source-location metadata and exported hashes can change after
recompilation at a different path. Do not compile all fixture subdirectories
together.

## Selected-child conservative fixtures

Compile `selected-child-conservative/first` and
`selected-child-conservative/controls` separately, with separate outputs and
classpaths: they intentionally contain duplicate helper names. Each pair uses
Scala 3.8.4, the constructor-capture plugin, API and bridge, utest 0.9.1,
sourcecode 0.4.3-M5, test-interface 1.0 and collection-compat 2.13.0. Retain
`-Wunused:all -Werror` and pass the plugin through `-Xplugin`. Run
`nodal.internal.testkit.SelectedChildConservativeReplay` for the first pair and
`nodal.internal.testkit.RunSelectedChildControls` for controls, passing the
output directory as the sole argument. Store outputs below ignored `.work`.

The original successful pair compilations used final API `b16d1bbc`, bridge
`1ebe7884` and plugin `8d5aab70`. Their 28 source/bridge expectations are
separate from five public native verification/emission profiles. A stale
'Not yet compiled' comment is preserved as source text, not the acceptance state.

## Maintained and lifecycle fixtures

The canonical maintained suites are
`nodal.internal.testkit.Increment58PendingReductionTests` and
`nodal.internal.testkit.Increment58GeneratedConservativeInterfaceTests`, with
their source examples and replay under `core/scala/testkit/test/src`.
`generated-conservative/RunMaintained.scala` runs the exact three conservative
groups. Compile it alongside that suite. Compile and run
`reduction-construction-ab/LifecycleControls.scala` separately without the
constructor plugin; it exercises internal lifecycle primitives, not admission
of pending outer reduction callbacks.

Historical final-stack parity reused already compiled maintained test JARs
with final API/bridge substituted: reduction 26 checks and 14 identical exports,
A/B 11 checks, conservative three groups and five identical exports. Their
original compilation API hashes were respectively `f202e796`, `c9b70954` and
`9cf1d079`, with bridge `ca2a96fa`; reduction/conservative used plugin
`0d8fdfdd`, and A/B used no plugin. This is not a fresh compilation of all
maintained fixtures against the final stack or a global testkit pass.

## Fresh coherent source53 follow-up

After preservation, all five maintained sources and the small committed runner
were freshly compiled against the final API/bridge/plugin with warnings as
errors. Selected pairs and no-plugin A/B were also freshly compiled, in four
isolated compilation groups total. All six runtime groups passed. The exact
counts and native five-profile replay are recorded in checkpoint 54 readiness.
Source lookup used a coherent root of exact compiled sources and fixtures, not
mixed working-tree library text.

Fresh relocated MLIR and Verilog-A provenance/comments differ from historical
outputs. Do not feed these into the historical exact-hash profile or claim
byte parity. The maintained conservative adapter runner defaults to the
`coherent-source53` profile and keeps `historical` explicitly selectable.
