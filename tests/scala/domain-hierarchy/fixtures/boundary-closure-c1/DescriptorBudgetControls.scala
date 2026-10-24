package nodal.internal.testkit
import nodal.*
object DescriptorBudgetControls:
  var exhaust = false
  var route = "render"
  var ordinary = false
  var lazyInput = false
  var pulled = 0
  def lazyWide(): KernelTypeDescriptor = KernelTypeDescriptor(
    "Struct",
    Vector(
      "wide",
      LazyList.continually {
        pulled += 1
        StructField("f", SInt(2))
      }
    )
  )
  def wide(count: Int): KernelTypeDescriptor = KernelTypeDescriptor(
    "Struct",
    Vector(
      "wide",
      Vector.tabulate(count)(i => StructField("f" + i, SInt(2)))
    )
  )
  def canonical(descriptor: KernelTypeDescriptor): Unit = route match
    case "enum" => assert(DigitalEnumContract.sameType(
        descriptor,
        if lazyInput then lazyWide() else wide(100)
      ))
    case "map" => assert(DigitalReductionContract.mapWidths(descriptor)(identity).kind == "Struct")
    case "schema" => assert(DigitalReductionContract.schemaWork(descriptor) == 101)
    case _ => assert(false)
  def exercise(root: DescriptorBudgetRoot): Unit =
    import EpochControls.{invoke, field, rejects, session}
    val owner = session()
    if ordinary then
      assert(!ConstructionKernel.boundaryWorkActive)
      canonical(wide(100))
      return
    invoke(owner, "beginIncrementalBoundaryClosure", root, Int.box(65536))
    val closure = field(owner, "boundaryClosureOwner").asInstanceOf[Option[AnyRef]].get
    if route == "nested" then
      val sameSession = session()
      rejects("nested elaboration is outside incremental"):
        DescriptorBudgetFactory.run()
      assert(session() eq sameSession)
      return
    val initial = field(closure, "remaining").asInstanceOf[java.lang.Long].longValue()
    pulled = 0
    val descriptor = if lazyInput then lazyWide() else wide(if exhaust then 100 else 1)
    val dataType = CandidateRuntime.dataTypeFromDescriptor(descriptor)
    val expressions =
      field(owner, "expressionValues").asInstanceOf[scala.collection.mutable.Map[Any, Any]]
    val before = expressions.size
    if exhaust then
      invoke(closure, "charge", Long.box(initial - 20L))
      rejects("work budget exhausted"):
        if route == "render" then invoke(owner, "renderType", dataType, Long.box(0L))
        else canonical(descriptor)
      assert(field(closure, "remaining").asInstanceOf[java.lang.Long].longValue() >= 0L)
      if lazyInput then assert(pulled > 0 && pulled <= 20, pulled.toString)
    else
      val rendered = invoke(owner, "renderType", dataType, Long.box(0L)).toString
      assert(rendered.contains("f0"))
      assert(field(closure, "remaining").asInstanceOf[java.lang.Long].longValue() < initial)
    assert(expressions.size == before)
  def main(args: Array[String]): Unit =
    assert(args.isEmpty)
    for nextRoute <- Vector("enum", "map", "schema") do
      route = nextRoute
      assert(!ConstructionKernel.boundaryWorkActive)
      canonical(wide(100))
      ordinary = true
      DescriptorBudgetFactory.run()
    ordinary = false
    var count = 6
    for nextRoute <- Vector("render", "enum", "map", "schema") do
      route = nextRoute
      exhaust = true
      EpochControls.rejects("captured constructor failure")(DescriptorBudgetFactory.run())
      route = "render"
      exhaust = false
      DescriptorBudgetFactory.run()
      count += 2
    lazyInput = true
    for nextRoute <- Vector("render", "enum", "map", "schema") do
      route = nextRoute
      exhaust = true
      EpochControls.rejects("captured constructor failure")(DescriptorBudgetFactory.run())
      count += 1
    lazyInput = false
    route = "nested"
    EpochControls.rejects("captured constructor failure")(DescriptorBudgetFactory.run())
    route = "render"
    exhaust = false
    DescriptorBudgetFactory.run()
    count += 2
    println("PASS " + count + " descriptor owner budget/inert/recovery controls")
