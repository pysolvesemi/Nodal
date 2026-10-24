package nodal_external

import nodal.*

final class Increment58StandaloneInoutLeaf extends Module:
  val required = ClockDomain.required("required")
  val width = param(4.integer, range = 2 to 8)
  val payload = in(UInt(width))
  val enable = in(Bool)
  val pad = digitalInout(
    UInt(width),
    DriveMode.pushPull,
    InoutPlacement.TopLevelPin,
    ResolutionProfile.PortableBoundaryOnly,
    "pad"
  )
  pad.drive(payload, enable)

final class Increment58StandaloneInoutSource(mode: String = "read", late: Boolean = true)
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
  val payload = in(UInt(actualWidth))
  val other = in(UInt(otherWidth))
  val enable = in(Bool)
  val output = out(UInt(actualWidth))
  val child = instance(new Increment58StandaloneInoutLeaf).domain(core)
  if !late then child.param(_.width, actualWidth)
  val projected = child(_.pad)
  val selectedAgain = child(_.pad)
  if mode == "split" then projected.drive(payload, enable)
  val observed = mode match
    case "copy" => (~projected.read).named("sample").keep("actual boundary read")
    case "bitwise" => projected.read & payload
    case "wrong-bitwise" => projected.read & other
    case "split" => projected.split.write
    case "pass-through" =>
      val local = digitalInout(
        UInt(actualWidth),
        DriveMode.pushPull,
        InoutPlacement.HierarchyPassThrough,
        ResolutionProfile.PortableBoundaryOnly,
        "local"
      )
      passThrough(projected, local)
      local.read
    case _ => projected.read
  output := observed
  child(_.payload) := (if mode == "cross-feedback" then observed else payload)
  child(_.enable) := enable
  if mode == "drive" then projected.drive(payload, enable)
  if mode == "feedback" then projected.drive(observed, enable)
  if late then child.param(_.width, actualWidth)

final class Increment58StandaloneCapabilityLeaf extends Module:
  val required = ClockDomain.required("required")
  val enable = in(Bool)
  val low = digitalInout(
    Bits(4),
    DriveMode.openDrain,
    InoutPlacement.TopLevelPin,
    ResolutionProfile.PortableBoundaryOnly,
    "low"
  )
  val high = digitalInout(
    Bits(4),
    DriveMode.openSource,
    InoutPlacement.TopLevelPin,
    ResolutionProfile.PortableBoundaryOnly,
    "high"
  )
  val sense = digitalInout(
    Bits(4),
    DriveMode.readOnly,
    InoutPlacement.TopLevelPin,
    ResolutionProfile.PortableBoundaryOnly,
    "sense"
  )
  low.driveLow(enable)
  high.driveHigh(enable)

final class Increment58StandaloneCapabilitiesSource extends Module:
  val core = ClockDomain.external(
    "core",
    ClockEdge.Rising,
    ResetPolicy.None,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val enable = in(Bool)
  val lowValue = out(Bits(4))
  val highValue = out(Bits(4))
  val sensedValue = out(Bits(4))
  val child = instance(new Increment58StandaloneCapabilityLeaf).domain(core)
  val low = child(_.low)
  val high = child(_.high)
  val sense = child(_.sense)
  child(_.enable) := enable
  low.driveLow(enable)
  low.highZ()
  high.driveHigh(enable)
  high.highZ()
  lowValue := low.read
  highValue := high.read
  sensedValue := sense.read

final class Increment58StandaloneOwnershipSource(mode: String) extends Module:
  val core = ClockDomain.external(
    "core",
    ClockEdge.Rising,
    ResetPolicy.None,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val first = instance(new Increment58StandaloneCapabilityLeaf).domain(core)
  val second = instance(new Increment58StandaloneCapabilityLeaf).domain(core)
  val local = digitalInout(
    Bits(4),
    DriveMode.openDrain,
    InoutPlacement.TopLevelPin,
    ResolutionProfile.PortableBoundaryOnly,
    "local"
  )
  val output = out(Bits(4))
  val selected = mode match
    case "local" => first(_ => local)
    case "sibling" => first(_ => second(value => Some(value.low)).get)
    case "wrapped" => first(value => Some(value.low)).get
    case _ => first(_.low)
  first(_.enable) := true.B
  second(_.enable) := true.B
  output := selected.read

final class Increment58ResolvedSnapshotScaleSource(count: Int) extends Module:
  val core = ClockDomain.external(
    "core",
    ClockEdge.Rising,
    ResetPolicy.None,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val payload = in(Bits(4))
  val enable = in(Bool)
  val output = out(Bits(4))
  val nets = Vector.tabulate(count)(index =>
    digitalInout(
      Bits(4),
      DriveMode.pushPull,
      InoutPlacement.TopLevelPin,
      ResolutionProfile.PortableBoundaryOnly,
      s"pad_$index"
    )
  )
  nets.foreach(_.highZ())
  nets.foreach(_.drive(payload, enable))
  nets.foreach(_.highZ())
  for index <- 1 until count do passThrough(nets(index - 1), nets(index))
  output := nets.last.read

final class Increment58StandaloneMiddle extends Module:
  val required = ClockDomain.required("required")
  val child = instance(new Increment58StandaloneCapabilityLeaf).domain(required)
  child(_.enable) := true.B

final class Increment58StandaloneDescendantSource extends Module:
  val core = ClockDomain.external(
    "core",
    ClockEdge.Rising,
    ResetPolicy.None,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val ancestor = instance(new Increment58StandaloneMiddle).domain(core)
  val output = out(Bits(4))
  output := ancestor(_.child(_.low)).read
