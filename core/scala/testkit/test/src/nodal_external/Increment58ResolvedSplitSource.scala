package nodal_external

import nodal.*

final class Increment58ResolvedSplitLeaf(ownDriver: Boolean, inside: Boolean) extends Module:
  val required = ClockDomain.required("required")
  val width = param(4.integer, range = 2 to 8)
  val data = in(UInt(width))
  val enable = in(Bool)
  val written = out(UInt(width))
  val enabled = out(Bool)
  val pad = digitalInout(
    UInt(width),
    DriveMode.pushPull,
    InoutPlacement.TopLevelPin,
    ResolutionProfile.PortableBoundaryOnly,
    "pad"
  )
  if ownDriver then pad.drive(data, enable)
  if inside then
    val observed = pad.split
    written := observed.write
    enabled := observed.enable
  else
    written := data
    enabled := enable

final class Increment58ResolvedSplitSource(mode: String = "parent-driver") extends Module:
  val core = ClockDomain.external(
    "core",
    ClockEdge.Rising,
    ResetPolicy.None,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val width = param(4.integer, range = 2 to 8)
  val data = in(UInt(width))
  val enable = in(Bool)
  val written = out(UInt(width))
  val enabled = out(Bool)
  val child = instance(new Increment58ResolvedSplitLeaf(
    mode != "parent-driver" && mode != "zero" && mode != "release",
    mode == "parent-driver"
  )).domain(core)
  val selected = child(_.pad)
  if mode == "parent-driver" || mode == "two-drivers" then selected.drive(data, enable)
  if mode == "release" then selected.highZ()
  val observed = if mode == "parent-driver" then child(_.written) else selected.split.write
  written := observed
  enabled := (if mode == "parent-driver" then child(_.enabled) else selected.split.enable)
  child(_.data) := (if mode == "feedback" then observed else data)
  child(_.enable) := enable
  child.param(_.width, width)

final class Increment58ResolvedSplitLevelLeaf extends Module:
  val required = ClockDomain.required("required")
  val enable = in(Bool)
  val pad = digitalInout(
    Bits(4),
    DriveMode.openDrain,
    InoutPlacement.TopLevelPin,
    ResolutionProfile.PortableBoundaryOnly,
    "pad"
  )
  pad.driveLow(enable)

final class Increment58ResolvedSplitLevelSource extends Module:
  val core = ClockDomain.external(
    "core",
    ClockEdge.Rising,
    ResetPolicy.None,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val enable = in(Bool)
  val written = out(Bits(4))
  val enabled = out(Bool)
  val child = instance(new Increment58ResolvedSplitLevelLeaf).domain(core)
  child(_.enable) := enable
  val selected = child(_.pad).split
  written := selected.write
  enabled := selected.enable
