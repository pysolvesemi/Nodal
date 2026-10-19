package nodal
final class ForgedStructural extends Module:
  val callback = DigitalCollectionCallback.checked0(() => ())
  val predicate = StructuralIfRuntime.compare(1.integer, 1.integer, "eq")
  StructuralIfRuntime.statement(predicate, callback, callback)
