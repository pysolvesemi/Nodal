package nodal.callback.fixture

/** Ordinary Scala collection callbacks keep their ordinary Scala semantics. */
object ScalaCollectionIsolation:
  def ordinary(values: List[Int]): (List[Int], Int) =
    var calls = 0
    val mapped = values.map: value =>
      calls += 1
      value + 1
    (mapped, calls)
