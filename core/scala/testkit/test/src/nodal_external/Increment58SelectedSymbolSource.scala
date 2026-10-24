package nodal_external
import nodal.*
final class Increment58SelectedLeaf extends Module:
  val required = ClockDomain.required("required")
  val input = in(UInt(8))
  val output = out(UInt(8))
  output := input
final class Increment58SelectedInstanceSource extends Module:
  val core = ClockDomain.external(
    "core",
    ClockEdge.Rising,
    ResetPolicy.None,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val input = in(UInt(8))
  val output = out(UInt(8))
  val lanes =
    for i <- hdlRange(0, 3) yield new Area:
      val _ = i
      val child = instance(new Increment58SelectedLeaf).domain(core)
      child(_.input) := input
  val selected = lanes(1).child
  output := selected(_.output)

final class Increment58WidthLeaf extends Module:
  val WIDTH = param(1.integer, range = 1 to 16)
  val required = ClockDomain.required("required")
  val input = in(UInt(WIDTH))
  val output = out(UInt(WIDTH))
  output := input
final class Increment58WidthSelectedSource(lower: Int = 0, step: Int = 1, wrong: Boolean = false)
    extends Module:
  val core = ClockDomain.external(
    "core",
    ClockEdge.Rising,
    ResetPolicy.None,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val output = out(UInt(lower + step + (if wrong then 2 else 1)))
  val lanes =
    for i <- hdlRange(lower, 8, step) yield new Area:
      val child = instance(new Increment58WidthLeaf).domain(core)
      child.param(_.WIDTH, i + 1.integer)
      child(_.input) := 0.U(i + 1.integer)
  val selected = lanes(1).child
  output := selected(_.output)
final class Increment58StateSelectedLeaf extends Module:
  val required = ClockDomain.required("required")
  val input = in(UInt(8))
  val output = out(UInt(8))
  val state = Reg(0.U(8))
  state := input
  output := state
final class Increment58TwoSelectedSource(stateful: Boolean = false, write: Boolean = false)
    extends Module:
  val core = ClockDomain.external(
    "core",
    ClockEdge.Rising,
    ResetPolicy.Sync,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val input = in(Vec(UInt(8), 3))
  val left = out(UInt(8))
  val right = out(UInt(8))
  val again = out(UInt(8))
  if stateful then
    val lanes =
      for i <- hdlRange(0, 3) yield new Area:
        val child = instance(new Increment58StateSelectedLeaf).domain(core)
        child(_.input) := input.at(i)
    val a = lanes(0).child
    val b = lanes(1).child
    left := a(_.output)
    right := b(_.output)
    val alias = lanes(0).child
    again := alias(_.output)
    if write then a(_.input) := input.at(0)
  else
    val lanes =
      for i <- hdlRange(0, 3) yield new Area:
        val child = instance(new Increment58SelectedLeaf).domain(core)
        child(_.input) := input.at(i)
    val a = lanes(0).child
    val b = lanes(1).child
    left := a(_.output)
    right := b(_.output)
    val alias = lanes(0).child
    again := alias(_.output)
    if write then a(_.input) := input.at(0)

sealed trait Increment58SelectedLink extends Interface
object Increment58SelectedContract:
  val producer = Role[SourceRole]("producer", RoleAccess.Out("data"))
  val consumer = Role[SinkRole]("consumer", RoleAccess.In("data"))
  val fixed = Interface[Increment58SelectedLink](
    "Increment58SelectedLink",
    InterfaceMember.value("data", UInt(8))
  )
    .withRoles(producer, consumer)

final class Increment58DirectSelectedInterface extends Module:
  val core = ClockDomain.external(
    "core",
    ClockEdge.Rising,
    ResetPolicy.None,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val input = in(Vec(UInt(8), 3))
  val lanes =
    for i <- hdlRange(0, 3) yield new Area:
      val endpoint = interfacePort(
        Increment58SelectedContract.fixed,
        Increment58SelectedContract.producer,
        "endpoint",
        core
      )
      endpoint.driveMember("data", input.at(i))
  val selected = lanes(1).endpoint
  selected.observeMember("data")

final class Increment58SelectedInterfaceLeaf extends Module:
  val required = ClockDomain.required("required")
  val input = in(UInt(8))
  val endpoint = interfacePort(
    Increment58SelectedContract.fixed,
    Increment58SelectedContract.producer,
    "endpoint",
    required
  )
  endpoint.driveMember("data", input)

final class Increment58ChildSelectedInterface extends Module:
  val core = ClockDomain.external(
    "core",
    ClockEdge.Rising,
    ResetPolicy.None,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val input = in(Vec(UInt(8), 3))
  val lanes =
    for i <- hdlRange(0, 3) yield new Area:
      val child = instance(new Increment58SelectedInterfaceLeaf).domain(core)
      child(_.input) := input.at(i)
      child(_.endpoint).observeMember("data")
  val selected = lanes(1).child
  val endpoint = selected(_.endpoint)
  endpoint.observeMember("data")

final class Increment58WidthSelectedInterfaceLeaf extends Module:
  val required = ClockDomain.required("required")
  val WIDTH = param(1.integer, range = 1 to 8)
  val input = in(UInt(WIDTH))
  val definition = Interface[Increment58SelectedLink](
    "WidthSelectedLink",
    InterfaceMember.value("data", UInt(WIDTH))
  )
    .withRoles(Increment58SelectedContract.producer)
  val endpoint =
    interfacePort(definition, Increment58SelectedContract.producer, "endpoint", required)
  endpoint.driveMember("data", input)

final class Increment58WidthSelectedInterface extends Module:
  val core = ClockDomain.external(
    "core",
    ClockEdge.Rising,
    ResetPolicy.None,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val lanes =
    for i <- hdlRange(0, 3) yield new Area:
      val child = instance(new Increment58WidthSelectedInterfaceLeaf).domain(core)
      child.param(_.WIDTH, i + 1.integer)
      child(_.input) := 0.U(i + 1.integer)
  val first = lanes(0).child
  val second = lanes(1).child
  val firstEndpoint = first(_.endpoint)
  val secondEndpoint = second(_.endpoint)
  firstEndpoint.observeMember("data")
  secondEndpoint.observeMember("data")

final class Increment58ConnectedSelectedInterface extends Module:
  val core = ClockDomain.external(
    "core",
    ClockEdge.Rising,
    ResetPolicy.None,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val input = in(Vec(UInt(8), 3))
  val lanes =
    for i <- hdlRange(0, 3) yield new Area:
      val producer = interfacePort(
        Increment58SelectedContract.fixed,
        Increment58SelectedContract.producer,
        "producer",
        core
      )
      val consumer = interfacePort(
        Increment58SelectedContract.fixed,
        Increment58SelectedContract.consumer,
        "consumer",
        core
      )
      producer.driveMember("data", input.at(i))
      producer.connectExact(consumer)
      consumer.observeMember("data")
  val selected = lanes(1).consumer
  selected.observeMember("data")

final class Increment58GuardedSelectedInterface extends Module:
  val core = ClockDomain.external(
    "core",
    ClockEdge.Rising,
    ResetPolicy.None,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val count = param(2.integer, range = 0 to 3)
  val mode = param(1.integer, range = 0 to 1)
  val input = in(Vec(UInt(8), 3))
  val output = out(Bool)
  val lanes =
    for i <- hdlRange(0, count) yield new Area:
      val endpoint = interfacePort(
        Increment58SelectedContract.fixed,
        Increment58SelectedContract.producer,
        "endpoint",
        core
      )
      endpoint.driveMember("data", input.at(i))
  if count > 0 then
    if mode == 1 then
      val selected = lanes(0).endpoint
      selected.observeMember("data")
      output := true.B
    else output := false.B
  else output := false.B

final class Increment58ArraySelectedInterface extends Module:
  val core = ClockDomain.external(
    "core",
    ClockEdge.Rising,
    ResetPolicy.None,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val input = in(Vec(Vec(UInt(8), 2), 3))
  val lanes =
    for i <- hdlRange(0, 3) yield new Area:
      val endpoint = interfaceArray(
        Increment58SelectedContract.fixed,
        Increment58SelectedContract.producer,
        2,
        "endpoint",
        core
      )
      endpoint.driveMember("data", input.at(i))
  val selected = lanes(1).endpoint
  selected.observeMember("data")

final class Increment58SelectedInterfaceWrite extends Module:
  val core = ClockDomain.external(
    "core",
    ClockEdge.Rising,
    ResetPolicy.None,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val lanes =
    for i <- hdlRange(0, 2) yield new Area:
      val _ = i
      val endpoint = interfacePort(
        Increment58SelectedContract.fixed,
        Increment58SelectedContract.producer,
        "endpoint",
        core
      )
      endpoint.driveMember("data", 0.U(8))
  val selected = lanes(1).endpoint
  selected.driveMember("data", 1.U(8))

final class Increment58SelectedFullFormalRangeSource extends Module:
  val core = ClockDomain.external(
    "core",
    ClockEdge.Rising,
    ResetPolicy.None,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val output = out(UInt(1))
  val lanes =
    for i <- hdlRange(0, 17) yield new Area:
      val child = instance(new Increment58WidthLeaf).domain(core)
      child.param(_.WIDTH, i + 1.integer)
      child(_.input) := 0.U(i + 1.integer)
  val selected = lanes(0).child
  output := selected(_.output)
