package nodal_external

import nodal.*

sealed trait TypedResolvedInner extends Interface
sealed trait TypedResolvedOuter extends Interface
object TypedResolvedRoles:
  val innerConnect = Role[SinkRole]("inner-connect", RoleAccess.Connect("pad"))
  val innerDrive = Role[SourceRole]("inner-drive", RoleAccess.Drive("pad"))
  val connect = Role[SinkRole]("connect", RoleAccess.Nested("bus", "inner-connect"))
  val drive = Role[SourceRole]("drive", RoleAccess.Nested("bus", "inner-drive"))

final class Increment58TypedResolvedMemberLeaf extends Module:
  val required = ClockDomain.required("required")
  val width = param(4.integer, range = 2 to 8)
  val data = in(UInt(width))
  val enable = in(Bool)
  val pad = InterfaceMember.digitalInout("pad", UInt(width), DriveMode.pushPull)
  val inner = Interface[TypedResolvedInner]("TypedResolvedInner", pad)
    .withRoles(TypedResolvedRoles.innerConnect, TypedResolvedRoles.innerDrive)
  val bus = InterfaceMember.nested("bus", inner)
  val definition = Interface[TypedResolvedOuter]("TypedResolvedOuter", bus)
    .withRoles(TypedResolvedRoles.connect, TypedResolvedRoles.drive)
  val connected = interfacePort(definition, TypedResolvedRoles.connect, "connected", required)
  val driven = interfacePort(definition, TypedResolvedRoles.drive, "driven", required)
  val physical = digitalInout(
    UInt(width),
    DriveMode.pushPull,
    InoutPlacement.TopLevelPin,
    ResolutionProfile.PortableBoundaryOnly,
    "physical"
  )
  passThrough(connected.resolved(pad, bus), physical)
  physical.drive(data, enable)

final class Increment58TypedResolvedMemberSource(mode: String = "read", late: Boolean = true)
    extends Module:
  val core = ClockDomain.external(
    "core",
    ClockEdge.Rising,
    ResetPolicy.None,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val actualWidth = param(4.integer, range = 2 to 8)
  val otherWidth = param(4.integer, range = 2 to 8)
  val data = in(UInt(actualWidth))
  val other = in(UInt(otherWidth))
  val enable = in(Bool)
  val output = out(UInt(actualWidth))
  val child = instance(new Increment58TypedResolvedMemberLeaf).domain(core)
  if !late then child.param(_.width, actualWidth)
  val endpoint = child(_.connected)
  val pad = child(_.pad)
  val bus = child(_.bus)
  val selected = endpoint.resolved(pad, bus)
  val selectedAgain = endpoint.resolved(pad, bus)
  val observed = mode match
    case "wrong-field" => endpoint.resolved(pad.copy(), bus).read
    case "wrong-nested" => endpoint.resolved(pad, bus.copy()).read
    case "missing-nested" => endpoint.resolved(pad).read
    case "raw" => child(value => Some(value.connected)).get.resolved(pad, bus).read
    case "drive-cannot-read" => child(_.driven).resolved(pad, bus).read
    case "connect-cannot-drive" =>
      selected.drive(data, enable)
      selected.read
    case "copy" => (~selected.read).named("observed").keep("typed member read")
    case "wrong-type" => selected.read & other
    case "drive" =>
      val driver = child(_.driven).resolved(pad, bus)
      driver.drive(data, enable)
      driver.highZ()
      selected.read
    case "pass-through" =>
      val local = digitalInout(
        UInt(actualWidth),
        DriveMode.pushPull,
        InoutPlacement.HierarchyPassThrough,
        ResolutionProfile.PortableBoundaryOnly,
        "local"
      )
      passThrough(selected, local)
      local.read
    case "split" => selected.split.write
    case _ => selected.read
  output := observed
  child(_.data) := (if mode == "feedback" then observed else data)
  child(_.enable) := enable
  if late then child.param(_.width, actualWidth)

final class Increment58ResolvedDomainSource(sameDomain: Boolean = true) extends Module:
  val first = ClockDomain.external(
    "first",
    ClockEdge.Rising,
    ResetPolicy.None,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val second = ClockDomain.external(
    "second",
    ClockEdge.Rising,
    ResetPolicy.None,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val field = InterfaceMember.digitalInout("pad", UInt(4), DriveMode.pushPull)
  val definition = Interface[TypedResolvedInner]("TypedResolvedDomain", field)
    .withRoles(TypedResolvedRoles.innerConnect)
  val left = interfacePort(definition, TypedResolvedRoles.innerConnect, "left", first)
  val right = interfacePort(
    definition,
    TypedResolvedRoles.innerConnect,
    "right",
    if sameDomain then first else second
  )
  val middle = digitalInout(
    UInt(4),
    DriveMode.pushPull,
    InoutPlacement.HierarchyPassThrough,
    ResolutionProfile.PortableBoundaryOnly,
    "middle"
  )
  passThrough(left.resolved(field), middle)
  passThrough(middle, right.resolved(field))

final class Increment58LocalResolvedMemberSource extends Module:
  val core = ClockDomain.external(
    "core",
    ClockEdge.Rising,
    ResetPolicy.None,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val data = in(UInt(4))
  val enable = in(Bool)
  val output = out(UInt(4))
  val pad = InterfaceMember.digitalInout("pad", UInt(4), DriveMode.pushPull)
  val definition = Interface[TypedResolvedInner]("TypedLocalResolved", pad)
    .withRoles(TypedResolvedRoles.innerDrive)
  val endpoint = interfacePort(definition, TypedResolvedRoles.innerDrive, "endpoint", core)
  endpoint.resolved(pad).drive(data, enable)
  val observed = endpoint.monitorView.resolved(pad)
  output := observed.split.write
