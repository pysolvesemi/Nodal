package nodal_external

import nodal.*

sealed trait HierarchyResolvedLink extends Interface
object HierarchyResolvedContract:
  val driver = Role[SourceRole]("driver", RoleAccess.Drive("pad"))
  val connector = Role[SinkRole]("connector", RoleAccess.Connect("pad"))
  def link(width: Expr[Integer]): InterfaceType[HierarchyResolvedLink] =
    Interface[HierarchyResolvedLink](
      "HierarchyResolvedLink",
      InterfaceMember.digitalInout("pad", UInt(width), DriveMode.pushPull)
    ).withRoles(driver, connector)

final class HierarchyResolvedLeaf(active: Boolean) extends Module:
  val required = ClockDomain.required("required")
  val width = param(2.integer, range = 1 to 8)
  val data = in(UInt(width))
  val driven = interfacePort(
    HierarchyResolvedContract.link(width),
    HierarchyResolvedContract.driver,
    "driven",
    required
  )
  val passed = interfacePort(
    HierarchyResolvedContract.link(width),
    HierarchyResolvedContract.connector,
    "passed",
    required
  )
  val lanes = interfaceArray(
    HierarchyResolvedContract.link(width),
    HierarchyResolvedContract.connector,
    2,
    "lanes",
    required
  )
  if active then
    driven.driveMember("pad", data)
    driven.monitorView.observeMember("pad")
    passed.observeMember("pad")

final class HierarchyResolvedParent(mode: String = "ok") extends Module:
  val core = ClockDomain.external(
    "core",
    ClockEdge.Rising,
    ResetPolicy.None,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val actualWidth = param(3.integer, range = 1 to 4)
  val other = param(3.integer, range = 1 to 4)
  val data = in(UInt(actualWidth))
  val wrong = in(UInt(other))
  val passed = interfacePort(
    HierarchyResolvedContract.link(actualWidth),
    HierarchyResolvedContract.connector,
    "passed",
    core
  )
  val child = instance(new HierarchyResolvedLeaf(mode != "empty")).domain(core)
  val driven = child(_.driven)
  val projected = child(_.passed)
  child.param(_.width, actualWidth)
  require(projected eq child(_.passed))
  mode match
    case "array" =>
      child(_.data) := data
      val _ = child(_.lanes)
    case "wrong-type" => driven.driveMember("pad", wrong)
    case "connect-cannot-drive" => projected.driveMember("pad", data)
    case "drive-cannot-read" => driven.observeMember("pad")
    case "raw" => passed.forwardExact(child(value => Some(value.passed)).get)
    case "empty" => ()
    case _ =>
      child(_.data) := data
      driven.driveMember("pad", data)
      passed.forwardExact(projected)
      projected.observeMember("pad")

sealed trait HierarchyPlainScalarLink extends Interface
object HierarchyPlainScalarContract:
  val producer = Role[SourceRole]("producer", RoleAccess.Out("data"))
  def link(width: Expr[Integer]): InterfaceType[HierarchyPlainScalarLink] =
    Interface[HierarchyPlainScalarLink](
      "HierarchyPlainScalarLink",
      InterfaceMember.value("data", UInt(width))
    ).withRoles(producer)

final class HierarchyPlainScalarLeaf extends Module:
  val required = ClockDomain.required("required")
  val width = param(2.integer, range = 1 to 8)
  val data = in(UInt(width))
  val output = interfacePort(
    HierarchyPlainScalarContract.link(width),
    HierarchyPlainScalarContract.producer,
    "output",
    required
  )
  output.driveMember("data", data)

final class HierarchyPlainScalarParent extends Module:
  val core = ClockDomain.external(
    "core",
    ClockEdge.Rising,
    ResetPolicy.None,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val width = param(3.integer, range = 1 to 4)
  val data = in(UInt(width))
  val output = interfacePort(
    HierarchyPlainScalarContract.link(width),
    HierarchyPlainScalarContract.producer,
    "output",
    core
  )
  val child = instance(new HierarchyPlainScalarLeaf).domain(core)
  val projected = child(_.output)
  child.param(_.width, width)
  child(_.data) := data
  output.forwardExact(projected)
