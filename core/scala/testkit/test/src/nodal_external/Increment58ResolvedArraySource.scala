package nodal_external
import nodal.*
sealed trait Increment58ResolvedArrayLink extends Interface
object Increment58ResolvedArrayRoles:
  val connect = Role[SinkRole]("connect", RoleAccess.Connect("pad"))
  val drive = Role[SourceRole]("drive", RoleAccess.Drive("pad"))
final class Increment58ResolvedArrayLocal(mode: String) extends Module:
  val core = ClockDomain.external(
    "core",
    ClockEdge.Rising,
    ResetPolicy.None,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val count = param(3.integer, range = 1 to 8)
  val data = in(Vec(UInt(4), count))
  val enable = in(Vec(Bool, count))
  val wrongEnable = in(Vec(Bool, 2))
  val wrongRank = in(Vec(Bool, 2, 2))
  val output = out(Vec(UInt(4), count))
  val pad = InterfaceMember.digitalInout("pad", UInt(4), DriveMode.pushPull)
  val definition = Interface[Increment58ResolvedArrayLink](
    "Increment58ResolvedArrayLink",
    pad
  ).withRoles(Increment58ResolvedArrayRoles.connect, Increment58ResolvedArrayRoles.drive)
  val connected =
    interfaceArray(definition, Increment58ResolvedArrayRoles.connect, count, "connected", core)
  val driven =
    interfaceArray(definition, Increment58ResolvedArrayRoles.drive, count, "driven", core)
  val read = connected.resolved(pad)
  require(read eq connected.resolved(pad))
  mode match
    case "drive" => driven.resolved(pad).drive(data, enable)
    case "split" | "split-alias" =>
      driven.resolved(pad).drive(data, enable)
      val view = driven.monitorView
      val selected = view.resolved(pad)
      require(selected eq view.resolved(pad))
      val carrier = selected.split
      val readOut = out(Vec(UInt(4), count))
      val writeOut = out(Vec(UInt(4), count))
      val enableOut = out(Vec(Bool, count))
      readOut := carrier.read
      writeOut := carrier.write
      enableOut := carrier.enable
      if mode == "split-alias" then
        val aliasOut = out(Vec(UInt(4), count))
        aliasOut := driven.monitorView.resolved(pad).split.write
    case "monitor-drive" => driven.monitorView.resolved(pad).drive(data, enable)
    case "multiple-split" =>
      driven.resolved(pad).drive(data, enable)
      driven.resolved(pad).drive(data, enable)
      val _ = driven.monitorView.resolved(pad).split.write
    case "released-split" =>
      driven.resolved(pad).highZ()
      val _ = driven.monitorView.resolved(pad).split.write
    case "release" => driven.resolved(pad).highZ()
    case "wrong-enable" => driven.resolved(pad).drive(data, wrongEnable)
    case "wrong-rank" => driven.resolved(pad).drive(data, wrongRank)
    case "wrong-field" => val _ = connected.resolved(pad.copy()).read
    case "read-drive" => val _ = driven.resolved(pad).read
    case "drive-connect" => read.drive(data, enable)
    case "split-empty" => val _ = read.split.write
    case _ => ()
  output := read.read
final class Increment58ResolvedArrayChild extends Module:
  val required = ClockDomain.required("required")
  val count = param(2.integer, range = 1 to 8)
  val width = param(2.integer, range = 1 to 8)
  val pad = InterfaceMember.digitalInout("pad", UInt(width), DriveMode.pushPull)
  val definition = Interface[Increment58ResolvedArrayLink](
    "Increment58ResolvedArrayLink",
    pad
  ).withRoles(Increment58ResolvedArrayRoles.connect, Increment58ResolvedArrayRoles.drive)
  val connected =
    interfaceArray(definition, Increment58ResolvedArrayRoles.connect, count, "connected", required)
  val driven =
    interfaceArray(definition, Increment58ResolvedArrayRoles.drive, count, "driven", required)
final class Increment58ResolvedArrayParent(wrong: Boolean = false) extends Module:
  val core = ClockDomain.external(
    "core",
    ClockEdge.Rising,
    ResetPolicy.None,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val count = param(3.integer, range = 1 to 8)
  val width = param(4.integer, range = 1 to 8)
  val foreignWidth = param(4.integer, range = 1 to 8)
  val data = in(Vec(UInt(width), count))
  val enable = in(Vec(Bool, count))
  val output = out(Vec(UInt(width), count))
  val child = instance(new Increment58ResolvedArrayChild).domain(core)
  val pad = child(_.pad)
  val read = child(_.connected).resolved(pad)
  val driven = child(_.driven).resolved(pad)
  output := read.read
  driven.drive(data, enable)
  child.param(_.count, count)
  child.param(_.width, if wrong then foreignWidth else width)
final class Increment58ResolvedArrayLow extends Module:
  val core = ClockDomain.external(
    "core",
    ClockEdge.Rising,
    ResetPolicy.None,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val count = param(3.integer, range = 1 to 8)
  val enable = in(Vec(Bool, count))
  val output = out(Vec(Bits(4), count))
  val pad = InterfaceMember.digitalInout("pad", Bits(4), DriveMode.openDrain)
  val definition = Interface[Increment58ResolvedArrayLink](
    "Increment58ResolvedArrayLow",
    pad
  ).withRoles(Increment58ResolvedArrayRoles.drive)
  val driven =
    interfaceArray(definition, Increment58ResolvedArrayRoles.drive, count, "driven", core)
  driven.resolved(pad).driveLow(enable)
  output := driven.monitorView.resolved(pad).read
final class Increment58ResolvedArrayHigh extends Module:
  val core = ClockDomain.external(
    "core",
    ClockEdge.Rising,
    ResetPolicy.None,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val enable = in(Vec(Bool, 3))
  val output = out(Vec(Bits(4), 3))
  val pad = InterfaceMember.digitalInout("pad", Bits(4), DriveMode.openSource)
  val definition = Interface[Increment58ResolvedArrayLink](
    "Increment58ResolvedArrayHigh",
    pad
  ).withRoles(Increment58ResolvedArrayRoles.drive)
  val driven = interfaceArray(definition, Increment58ResolvedArrayRoles.drive, 3, "driven", core)
  driven.resolved(pad).driveHigh(enable)
  output := driven.monitorView.resolved(pad).read
final class Increment58ResolvedArrayPass(wrong: String) extends Module:
  val core = ClockDomain.external(
    "core",
    ClockEdge.Rising,
    ResetPolicy.None,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val other = if wrong == "domain" then
    ClockDomain.external(
      "other",
      ClockEdge.Rising,
      ResetPolicy.None,
      ResetPolarity.ActiveHigh,
      100.MHz
    )
  else core
  val count = param(3.integer, range = 1 to 8)
  val foreignCount = param(3.integer, range = 1 to 8)
  val output = out(Vec(UInt(4), count))
  val pad = InterfaceMember.digitalInout("pad", UInt(4), DriveMode.pushPull)
  val definition = Interface[Increment58ResolvedArrayLink](
    "Increment58ResolvedArrayPass",
    pad
  ).withRoles(Increment58ResolvedArrayRoles.connect)
  val left = interfaceArray(definition, Increment58ResolvedArrayRoles.connect, count, "left", core)
  val right = interfaceArray(
    definition,
    Increment58ResolvedArrayRoles.connect,
    if wrong == "shape" then foreignCount else count,
    "right",
    if wrong == "domain" then other else core
  )
  passThrough(left.resolved(pad), right.resolved(pad))
  output := left.resolved(pad).read
