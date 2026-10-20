package nodal

final class PositiveReusableArea(input: Expr[Bits]) extends Area:
  val copied = wire(Bits(8))
  copied := input
  val nested = new Area:
    val inverted = wire(Bits(8))
    inverted := ~input

final class PositiveAreaModule extends Module:
  val input = in(Bits(8))
  val output = out(Bits(8))
  def helper(value: Expr[Bits]): PositiveReusableArea =
    val local = new PositiveReusableArea(value)
    local
  val first = helper(input)
  val second = helper(input)
  val alias = first
  val group = new Area:
    val target = wire(Bits(8))
    val hostValue = 7
  assert(group.hostValue == 7)
  group.target := first.nested.inverted
  output := group.target

final class AreaBodyFailure extends Module:
  try
    val failed = new Area:
      val target = wire(Bool)
      target := true.B
      throw new IllegalArgumentException("Area body failed")
    val _ = failed
  catch case _: IllegalArgumentException => ()

final class ForeignAreaModule(previous: PositiveReusableArea) extends Module:
  val borrowed = previous

final class ArgumentArea(first: Int, second: Int) extends Area:
  val total = first + second

final class AreaArgumentFailure(named: Boolean) extends Module:
  private def failingArgument(): Int =
    val partial = wire(Bool)
    partial := true.B
    throw new IllegalArgumentException("Area argument failed")
  try
    val failed =
      if named then new ArgumentArea(second = failingArgument(), first = 1)
      else new ArgumentArea(failingArgument(), 2)
    val _ = failed
  catch case _: IllegalArgumentException => ()

final class AreaArgumentOrder extends Module:
  var order = Vector.empty[Int]
  private def actual(value: Int): Int =
    order = order :+ value
    value
  val group = new ArgumentArea(second = actual(2), first = actual(1))
  assert(order == Vector(2, 1))
  assert(group.total == 3)

final class DeepArea(remaining: Int) extends Area:
  val next = if remaining > 0 then Some(new DeepArea(remaining - 1)) else None

final class AreaDepthLimit extends Module:
  val group = new DeepArea(130)

final class AreaBehavioralMember extends Module:
  val clock = ClockDomain.external(
    "clock",
    ClockEdge.Rising,
    ResetPolicy.Sync,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val enabled = in(Bool)
  val output = out(Bool)
  clock:
    val group = new Area:
      val stored = Reg(false.B)
    when(enabled):
      group.stored := true.B
    output := group.stored

final class AreaGenericMembers extends Module:
  val groups = List(3, 5).map: value =>
    new Area:
      val hostValue = value
      val stored = wire(Bool)
      stored := true.B
  val first = groups(0)
  val second = groups(1)
  val output = out(Bool)
  assert(first.hostValue == 3)
  assert(second.hostValue == 5)
  output := first.stored

object AreaPositive:
  def main(args: Array[String]): Unit =
    val _ = args
    val first = ConstructionKernel.inspect(new PositiveAreaModule)
    val second = ConstructionKernel.inspect(new PositiveAreaModule)
    assert(first.areas.size == 5)
    assert(first.areas.exists(_.path.endsWith(".first")))
    assert(first.areas.exists(_.path.endsWith(".second")))
    assert(!first.areas.exists(_.path.endsWith(".local")))
    assert(!first.areas.exists(_.path.endsWith(".alias")))
    assert(first.modules.size == 1)
    assert(first.areas == second.areas)
    assert(first.names == second.names)
    assert(first.digitalAssignments.size == 6)
    val failure = scala.util.Try(ConstructionKernel.inspect(new AreaBodyFailure)).failed.get
    assert(failure.getMessage.contains("NODAL-CONSTRUCTOR-LIFECYCLE-021"))
    for named <- Vector(false, true) do
      val argumentFailure =
        scala.util.Try(ConstructionKernel.inspect(new AreaArgumentFailure(named))).failed.get
      assert(argumentFailure.getMessage.contains("NODAL-CONSTRUCTOR-LIFECYCLE-021"))
    assert(ConstructionKernel.inspect(new AreaArgumentOrder).areas.size == 1)
    val depth = scala.util.Try(ConstructionKernel.inspect(new AreaDepthLimit)).failed.get
    assert(depth.getMessage.contains("NODAL-AREA-058-LIMIT"))
    val behavioral = ConstructionKernel.inspect(new AreaBehavioralMember)
    assert(behavioral.areas.size == 1)
    assert(behavioral.digitalAssignments.count(_.kind == "register_next") == 1)
    val generic = ConstructionKernel.inspect(new AreaGenericMembers)
    assert(generic.areas.map(_.path.split('.').last).toSet == Set("first", "second"))
    assert(generic.digitalAssignments.size == 3)
    var previous = Option.empty[PositiveReusableArea]
    ConstructionKernel.capture(new PositiveAreaModule)(module =>
      previous = Some(module.first)
      Vector.empty
    )
    val foreign = scala.util.Try(
      ConstructionKernel.inspect(new ForeignAreaModule(previous.get))
    ).failed.get
    assert(foreign.getMessage.contains("NODAL-AREA-058-OWNER"))
    assert(ConstructionKernel.inspect(new PositiveAreaModule).areas.size == 5)
    println("AREA_LEXICAL_CONSTRUCTION_PASS")
