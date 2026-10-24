package nodal_external
import nodal.*
sealed trait Increment58TargetInterfaceLink extends Interface
object Increment58TargetInterfaceContract:
  val producer = Role[SourceRole]("producer", RoleAccess.Out("data"))
  val definition = Interface[Increment58TargetInterfaceLink](
    "Increment58TargetInterfaceLink",
    InterfaceMember.value("data", UInt(8))
  ).withRoles(producer)
final class Increment58TargetInterfaceLeaf extends Module:
  val required = ClockDomain.required("required")
  val input = in(UInt(8))
  val endpoint = interfacePort(
    Increment58TargetInterfaceContract.definition,
    Increment58TargetInterfaceContract.producer,
    "endpoint",
    required
  )
  endpoint.driveMember("data", input)
final class Increment58SelectedInterfaceForwardSource extends Module:
  val core = ClockDomain.external(
    "core",
    ClockEdge.Rising,
    ResetPolicy.None,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val input = in(Vec(UInt(8), 3))
  val first = interfacePort(
    Increment58TargetInterfaceContract.definition,
    Increment58TargetInterfaceContract.producer,
    "first",
    core
  )
  val second = interfacePort(
    Increment58TargetInterfaceContract.definition,
    Increment58TargetInterfaceContract.producer,
    "second",
    core
  )
  val alias = interfacePort(
    Increment58TargetInterfaceContract.definition,
    Increment58TargetInterfaceContract.producer,
    "alias",
    core
  )
  val lanes =
    for i <- hdlRange(0, 3) yield new Area:
      val child = instance(new Increment58TargetInterfaceLeaf).domain(core)
      child(_.input) := input.at(i)
  val firstChild = lanes(0).child
  val secondChild = lanes(1).child
  val aliasChild = lanes(0).child
  first.forwardExact(firstChild(_.endpoint))
  second.forwardExact(secondChild(_.endpoint))
  alias.forwardExact(aliasChild(_.endpoint))
