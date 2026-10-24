package nodal

/** Standalone focused controls. Compile against the modified API, then run this object's main. Does
  * not invoke a bridge/native compiler, mutate production, or depend on utest.
  */
object StructuralQueryMeterControls:
  import StructuralParameterBinding.*

  private final class Meter extends WorkMeter:
    val stale = new IllegalStateException("stale query epoch")
    val exhausted = new IllegalStateException("shared query budget exhausted")
    var live = true
    var spent = 0L
    var limit = Long.MaxValue
    def checkLive(): Unit = if !live then throw stale
    def charge(units: Int): Unit =
      assert(units > 0)
      if units.toLong > limit - spent then throw exhausted
      spent += units.toLong

  private def failsWith(expected: Throwable)(body: => Any): Unit =
    val result = scala.util.Try(body)
    assert(result.isFailure)
    assert(result.failed.get eq expected)

  private def fixture(): (ConstructionSnapshot, KernelGeneratedRegionSnapshot) =
    val family = KernelGeneratedRegionSnapshot(
      path = "root/g",
      owner = "root",
      parent = None,
      induction = "root/g/i",
      lower = "0",
      upperExclusive = "4",
      step = "1",
      maximum = None,
      maximumTripCount = 4,
      declarations = Vector.empty,
      digitalBody = Some(KernelDigitalGeneratedBody(
        Vector.empty,
        Vector(KernelDigitalRegionArgument("root/g/i", "Integer", "induction")),
        0
      ))
    )
    val occurrence = KernelInstanceSnapshot("root/c", "child", None, Vector.empty)
    val root = KernelModuleSnapshot(
      "root",
      "Root",
      Vector.empty,
      Vector.empty,
      Vector(occurrence)
    )
    val child = KernelModuleSnapshot("child", "Child", Vector.empty, Vector.empty, Vector.empty)
    val sum = KernelParameterExpressionSnapshot(
      "root/sum",
      "root",
      "analog_add",
      Vector("2", "3"),
      "Integer",
      None,
      None
    )
    (
      ConstructionSnapshot(
        root = "root",
        parameterExpressions = Vector(sum),
        modules = Vector(root, child),
        interfaceAbi = Vector.empty,
        resolvedNets = Vector.empty,
        topology = Vector.empty,
        generatedRegions = Vector(family)
      ),
      family
    )

  def main(args: Array[String]): Unit =
    val (snapshot, family) = fixture()
    val ordinary = new Query(snapshot)
    val sum = ordinary.resolve("root", "root/sum")
    assert(ordinary.bounds(sum) == IterationDomain.Bounds(5L, 5L))
    val ordinaryProof = ordinary.exportValues(Vector(sum))
    // Source and constructor-descriptor controls for both retained constructor arities.
    val oldQuery = new Query(
      snapshot,
      None,
      None,
      Map.empty,
      Map.empty,
      None,
      Map.empty,
      Map.empty
    )
    val oldInstances = new InstanceQueries(snapshot, None)
    assert(oldQuery.bounds(oldQuery.resolve("root", "root/sum")) == ordinary.bounds(sum))
    assert(oldInstances.query("root", "root/c").checkedLiteral("root", "5") == 5L)
    assert(classOf[Query].getConstructors.exists(_.getParameterCount == 8))
    assert(classOf[InstanceQueries].getConstructors.exists(_.getParameterCount == 2))

    val meter = new Meter
    val query = new Query(snapshot, meter = meter)
    val resolved = query.resolve("root", "root/sum")
    assert(query.exportValues(Vector(resolved)) == ordinaryProof)
    val beforeCached = meter.spent
    assert(query.resolve("root", "root/sum") == resolved)
    assert(meter.spent > beforeCached)
    val body = query.generatedBody(family)
    val beforeBody = meter.spent
    val induction = body.resolve("root", family.induction)
    assert(body.bounds(induction) == IterationDomain.Bounds(0L, 3L))
    assert(meter.spent > beforeBody)
    val selected = query.selectedGenerated("root")
    selected.admit(family, "1", query, (_, _, _) => false)
    val coordinate = selected.importValues(body, Vector(induction)).head
    assert(query.bounds(coordinate) == IterationDomain.Bounds(1L, 1L))
    val instances = new InstanceQueries(snapshot, Some(query), meter)
    val cachedInstance = instances.query("root", "root/c")
    assert(instances.query("root", "root/c") eq cachedInstance)

    // Revocation must reject real cached consumers, not only creation of fresh queries.
    meter.live = false
    failsWith(meter.stale)(query.resolve("root", "root/sum"))
    failsWith(meter.stale)(query.bounds(resolved))
    failsWith(meter.stale)(query.operation(resolved))
    failsWith(meter.stale)(query.exportValues(Vector(resolved)))
    failsWith(meter.stale)(query.exportDimensions("root", Vector(resolved)))
    failsWith(meter.stale)(query.importValue("root", ordinaryProof))
    failsWith(meter.stale)(body.resolve("root", family.induction))
    failsWith(meter.stale)(selected.importValues(body, Vector(induction)))
    failsWith(meter.stale)(selected.admit(family, "1", query, (_, _, _) => false))
    failsWith(meter.stale)(instances.query("root", "root/c"))
    failsWith(meter.stale)(cachedInstance.bounds(0))
    // Independent default-mode consumers remain ordinary immutable snapshot queries.
    assert(ordinary.resolve("root", "root/sum") == sum)

    val accounting = new Meter
    val first = new Query(snapshot, meter = accounting)
    val constructionSpend = accounting.spent
    val firstValue = first.literal(9L)
    val beforeSecond = accounting.spent
    val second = new Query(snapshot, meter = accounting)
    assert(accounting.spent - beforeSecond == constructionSpend)
    val secondValue = second.literal(9L)
    accounting.limit = accounting.spent + 1L
    assert(first.bounds(firstValue) == IterationDomain.Bounds(9L, 9L))
    failsWith(accounting.exhausted)(second.bounds(secondValue))
    // Exact constructor threshold is deterministic, and zero budget fails before indexing.
    val exact = new Meter
    exact.limit = constructionSpend
    val exactQuery = new Query(snapshot, meter = exact)
    assert(exact.spent == constructionSpend)
    failsWith(exact.exhausted)(exactQuery.literal(1L))
    val zero = new Meter
    zero.limit = 0L
    failsWith(zero.exhausted)(new Query(snapshot, meter = zero))
    println("StructuralQueryMeterControls: passed")
