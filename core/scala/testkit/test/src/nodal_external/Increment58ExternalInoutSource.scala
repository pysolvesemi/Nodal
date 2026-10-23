package nodal_external
import nodal.*
final class Increment58ExternalPad extends ExternalModule("Increment58ExternalPad"):
  val pin = digitalInout(
    Bits(4),
    DriveMode.pushPull,
    InoutPlacement.BlackBoxPin,
    ResolutionProfile.PortableBoundaryOnly,
    "pin"
  )
final class Increment58ExternalBadBody extends ExternalModule("Increment58ExternalBadBody"):
  val pin = digitalInout(
    Bits(4),
    DriveMode.pushPull,
    InoutPlacement.BlackBoxPin,
    ResolutionProfile.PortableBoundaryOnly,
    "pin"
  )
  pin.highZ()
final class Increment58ExternalSource(mode: String = "read") extends Module:
  val core = ClockDomain.external(
    "core",
    ClockEdge.Rising,
    ResetPolicy.None,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val input = in(Bits(4))
  val enable = in(Bool)
  val output = out(Bits(4))
  val pad = digitalInout(
    Bits(4),
    DriveMode.pushPull,
    InoutPlacement.TopLevelPin,
    ResolutionProfile.PortableBoundaryOnly,
    "pad"
  )
  val child = instance(new Increment58ExternalPad)
  val selected = child(_.pin)
  passThrough(pad, selected)
  if mode != "read" then pad.drive(input, enable)
  output := (if mode == "split" then selected.split.write else selected.read)
final class Increment58ExternalBadSource extends Module:
  val child = instance(new Increment58ExternalBadBody)

final class Increment58RepeatedExternalSource extends Module:
  val first = instance(new Increment58ExternalPad)
  val second = instance(new Increment58ExternalPad)
  passThrough(first(_.pin), second(_.pin))
final class Increment58ExternalWidthPad(width: Int)
    extends ExternalModule("Increment58ExternalWidthPad"):
  val pin = digitalInout(
    Bits(width),
    DriveMode.pushPull,
    InoutPlacement.BlackBoxPin,
    ResolutionProfile.PortableBoundaryOnly,
    "pin"
  )
final class Increment58ConflictingExternalSource extends Module:
  val first = instance(new Increment58ExternalWidthPad(4))
  val second = instance(new Increment58ExternalWidthPad(8))

final class Increment58EmptyExternal extends ExternalModule("EmptyExternal")
final class Increment58WrongPlacementExternal extends ExternalModule("WrongPlacementExternal"):
  val pin = digitalInout(
    Bits(4),
    DriveMode.pushPull,
    InoutPlacement.TopLevelPin,
    ResolutionProfile.PortableBoundaryOnly,
    "pin"
  )
final class Increment58BadNameExternal extends ExternalModule("bad physical name"):
  val pin = digitalInout(
    Bits(4),
    DriveMode.pushPull,
    InoutPlacement.BlackBoxPin,
    ResolutionProfile.PortableBoundaryOnly,
    "pin"
  )
final class Increment58UnsupportedExternalSource(mode: String) extends Module:
  val child = mode match
    case "empty" => instance(new Increment58EmptyExternal)
    case "placement" => instance(new Increment58WrongPlacementExternal)
    case _ => instance(new Increment58BadNameExternal)
final class Increment58ExternalOpenDrainPad extends ExternalModule("ExternalOpenDrainPad"):
  val pin = digitalInout(
    Bits(4),
    DriveMode.openDrain,
    InoutPlacement.BlackBoxPin,
    ResolutionProfile.PortableBoundaryOnly,
    "pin"
  )
final class Increment58ExternalOpenDrainSource extends Module:
  val core = ClockDomain.external(
    "core",
    ClockEdge.Rising,
    ResetPolicy.None,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val enable = in(Bool)
  val observed = out(Bits(4))
  val pad = digitalInout(
    Bits(4),
    DriveMode.openDrain,
    InoutPlacement.TopLevelPin,
    ResolutionProfile.PortableBoundaryOnly,
    "pad"
  )
  val child = instance(new Increment58ExternalOpenDrainPad)
  passThrough(pad, child(_.pin))
  pad.driveLow(enable)
  observed := pad.read
final class Increment58LocalTaggedInoutSource extends Module:
  val core = ClockDomain.external(
    "core",
    ClockEdge.Rising,
    ResetPolicy.None,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val input = in(Bits(4))
  val enable = in(Bool)
  val output = out(Bits(4))
  val pin = digitalInout(
    Bits(4),
    DriveMode.pushPull,
    InoutPlacement.BlackBoxPin,
    ResolutionProfile.PortableBoundaryOnly,
    "pin"
  )
  pin.drive(input, enable)
  output := pin.split.write

final class Increment58LocalAliasedInoutSource extends Module:
  val core = ClockDomain.external(
    "core",
    ClockEdge.Rising,
    ResetPolicy.None,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val first = digitalInout(
    Bits(4),
    DriveMode.pushPull,
    InoutPlacement.TopLevelPin,
    ResolutionProfile.PortableBoundaryOnly,
    "first"
  )
  val second = digitalInout(
    Bits(4),
    DriveMode.pushPull,
    InoutPlacement.TopLevelPin,
    ResolutionProfile.PortableBoundaryOnly,
    "second"
  )
  val firstRead = out(Bits(4))
  val secondRead = out(Bits(4))
  passThrough(first, second)
  firstRead := first.read
  secondRead := second.read

final class Increment58IndependentExternalSource extends Module:
  val core = ClockDomain.external(
    "core",
    ClockEdge.Rising,
    ResetPolicy.None,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val firstPad = digitalInout(
    Bits(4),
    DriveMode.pushPull,
    InoutPlacement.TopLevelPin,
    ResolutionProfile.PortableBoundaryOnly,
    "first_pad"
  )
  val secondPad = digitalInout(
    Bits(4),
    DriveMode.pushPull,
    InoutPlacement.TopLevelPin,
    ResolutionProfile.PortableBoundaryOnly,
    "second_pad"
  )
  val firstRead = out(Bits(4))
  val secondRead = out(Bits(4))
  val first = instance(new Increment58ExternalPad)
  val second = instance(new Increment58ExternalPad)
  passThrough(firstPad, first(_.pin))
  passThrough(secondPad, second(_.pin))
  firstRead := firstPad.read
  secondRead := secondPad.read

sealed trait FixedResolvedInner extends Interface
sealed trait FixedResolvedOuter extends Interface
object FixedResolvedRoles:
  val inner = Role[SinkRole]("inner-connect", RoleAccess.Connect("pad"))
  val outer = Role[SinkRole]("connect", RoleAccess.Nested("bus", "inner-connect"))

final class Increment58FixedResolvedInterfaceLeaf extends Module:
  val required = ClockDomain.required("required")
  val data = in(UInt(4))
  val enable = in(Bool)
  val pad = InterfaceMember.digitalInout("pad", UInt(4), DriveMode.pushPull)
  val inner = Interface[FixedResolvedInner]("FixedResolvedInner", pad)
    .withRoles(FixedResolvedRoles.inner)
  val bus = InterfaceMember.nested("bus", inner)
  val definition = Interface[FixedResolvedOuter]("FixedResolvedOuter", bus)
    .withRoles(FixedResolvedRoles.outer)
  val connected = interfacePort(definition, FixedResolvedRoles.outer, "connected", required)
  val physical = digitalInout(
    UInt(4),
    DriveMode.pushPull,
    InoutPlacement.TopLevelPin,
    ResolutionProfile.PortableBoundaryOnly,
    "physical"
  )
  passThrough(connected.resolved(pad, bus), physical)
  physical.drive(data, enable)

final class Increment58FixedResolvedInterfaceSource extends Module:
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
  val pad = digitalInout(
    UInt(4),
    DriveMode.pushPull,
    InoutPlacement.TopLevelPin,
    ResolutionProfile.PortableBoundaryOnly,
    "pad"
  )
  val child = instance(new Increment58FixedResolvedInterfaceLeaf).domain(core)
  val selected = child(_.connected).resolved(child(_.pad), child(_.bus))
  passThrough(pad, selected)
  output := selected.read
  child(_.data) := data
  child(_.enable) := enable
