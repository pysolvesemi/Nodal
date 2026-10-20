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
  child(_.payload) := payload
  child(_.enable) := enable
  if mode == "drive" then projected.drive(payload, enable)
  if mode == "feedback" then projected.drive(observed, enable)
  if late then child.param(_.width, actualWidth)
