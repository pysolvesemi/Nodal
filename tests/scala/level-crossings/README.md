# Level-crossing frontend fixtures

Foundation 57's finite level form is module-owned construction with an explicit
owned destination; a surrounding `ClockDomain.apply` is optional. The public positive witness is
`core/scala/testkit/test/src/nodal_external/Increment57LevelCrossingSource.scala`.

The negative fixtures retain the existing callback/type boundaries:

- `BehavioralNegative.scala`: state allocation inside a behavioral callback,
  `NODAL-SEQUENTIAL-056`.
- `CollectionNegative.scala`: state allocation inside a pure collection callback,
  `NODAL-COLLECTION-CALLBACK`.
- `MultiBitNegative.scala` and `PulseNegative.scala`: public Bool signature type
  mismatch. A Pulse wrapper cannot be silently coerced into a level.
- `helper/Producer.scala` is compiled into a JAR before compiling
  `helper/ConsumerNegative.scala` against that JAR without producer sources.
  The helper still rejects as `NODAL-COLLECTION-CALLBACK`; its Expr return does
  not attest purity. The same helper remains valid at module construction scope.

Runtime provenance, source domain, stage bounds, direct causal reconvergence and
bridge mutation checks live in `Increment57LevelCrossingTests`. No CI, full-suite
qualification or broad timing/protocol proof is implied by these focused cases.
