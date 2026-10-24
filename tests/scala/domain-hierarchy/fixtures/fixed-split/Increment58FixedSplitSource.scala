package nodal_external

import nodal.*

final class Increment58FixedSplitReader extends Module:
  val required = ClockDomain.required("required")
  val pad = digitalInout(
    Bits(4),
    DriveMode.pushPull,
    InoutPlacement.TopLevelPin,
    ResolutionProfile.PortableBoundaryOnly,
    "pad"
  )
  val written = out(Bits(4))
  val enabled = out(Bool)
  val observed = out(Bits(4))
  val view = pad.split
  written := view.write
  enabled := view.enable
  observed := view.read

final class Increment58FixedSplitWriter extends Module:
  val required = ClockDomain.required("required")
  val data = in(Bits(4))
  val enable = in(Bool)
  val pad = digitalInout(
    Bits(4),
    DriveMode.pushPull,
    InoutPlacement.TopLevelPin,
    ResolutionProfile.PortableBoundaryOnly,
    "pad"
  )
  pad.drive(data, enable)

final class Increment58FixedSplitDown extends Module:
  val core = ClockDomain.external(
    "core",
    ClockEdge.Rising,
    ResetPolicy.None,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val data = in(Bits(4))
  val enable = in(Bool)
  val written = out(Bits(4))
  val enabled = out(Bool)
  val observed = out(Bits(4))
  val pad = digitalInout(
    Bits(4),
    DriveMode.pushPull,
    InoutPlacement.TopLevelPin,
    ResolutionProfile.PortableBoundaryOnly,
    "pad"
  )
  val reader = instance(new Increment58FixedSplitReader).domain(core)
  passThrough(pad, reader(_.pad))
  pad.drive(data, enable)
  written := reader(_.written)
  enabled := reader(_.enabled)
  observed := reader(_.observed)

final class Increment58FixedSplitUp extends Module:
  val core = ClockDomain.external(
    "core",
    ClockEdge.Rising,
    ResetPolicy.None,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val data = in(Bits(4))
  val enable = in(Bool)
  val written = out(Bits(4))
  val enabled = out(Bool)
  val observed = out(Bits(4))
  val pad = digitalInout(
    Bits(4),
    DriveMode.pushPull,
    InoutPlacement.TopLevelPin,
    ResolutionProfile.PortableBoundaryOnly,
    "pad"
  )
  val writer = instance(new Increment58FixedSplitWriter).domain(core)
  passThrough(pad, writer(_.pad))
  writer(_.data) := data
  writer(_.enable) := enable
  val view = pad.split
  written := view.write
  enabled := view.enable
  observed := view.read

final class Increment58FixedSplitSibling extends Module:
  val core = ClockDomain.external(
    "core",
    ClockEdge.Rising,
    ResetPolicy.None,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val data = in(Bits(4))
  val enable = in(Bool)
  val written = out(Bits(4))
  val enabled = out(Bool)
  val observed = out(Bits(4))
  val pad = digitalInout(
    Bits(4),
    DriveMode.pushPull,
    InoutPlacement.TopLevelPin,
    ResolutionProfile.PortableBoundaryOnly,
    "pad"
  )
  val writer = instance(new Increment58FixedSplitWriter).domain(core)
  val reader = instance(new Increment58FixedSplitReader).domain(core)
  passThrough(pad, writer(_.pad))
  passThrough(pad, reader(_.pad))
  writer(_.data) := data
  writer(_.enable) := enable
  written := reader(_.written)
  enabled := reader(_.enabled)
  observed := reader(_.observed)

final class Increment58FixedSplitReused extends Module:
  val core = ClockDomain.external(
    "core",
    ClockEdge.Rising,
    ResetPolicy.None,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val firstData = in(Bits(4))
  val firstEnable = in(Bool)
  val firstWritten = out(Bits(4))
  val firstEnabled = out(Bool)
  val firstObserved = out(Bits(4))
  val firstPad = digitalInout(
    Bits(4),
    DriveMode.pushPull,
    InoutPlacement.TopLevelPin,
    ResolutionProfile.PortableBoundaryOnly,
    "firstPad"
  )
  val first = instance(new Increment58FixedSplitReader).domain(core)
  passThrough(firstPad, first(_.pad))
  firstPad.drive(firstData, firstEnable)
  firstWritten := first(_.written)
  firstEnabled := first(_.enabled)
  firstObserved := first(_.observed)
  val secondData = in(Bits(4))
  val secondEnable = in(Bool)
  val secondWritten = out(Bits(4))
  val secondEnabled = out(Bool)
  val secondObserved = out(Bits(4))
  val secondPad = digitalInout(
    Bits(4),
    DriveMode.pushPull,
    InoutPlacement.TopLevelPin,
    ResolutionProfile.PortableBoundaryOnly,
    "secondPad"
  )
  val second = instance(new Increment58FixedSplitReader).domain(core)
  passThrough(secondPad, second(_.pad))
  secondPad.drive(secondData, secondEnable)
  secondWritten := second(_.written)
  secondEnabled := second(_.enabled)
  secondObserved := second(_.observed)

final class Increment58FixedSplitTwoPinReader extends Module:
  val required = ClockDomain.required("required")
  val leftPad = digitalInout(
    Bits(4),
    DriveMode.pushPull,
    InoutPlacement.TopLevelPin,
    ResolutionProfile.PortableBoundaryOnly,
    "leftPad"
  )
  val leftWritten = out(Bits(4))
  val leftEnabled = out(Bool)
  val leftObserved = out(Bits(4))
  val leftView = leftPad.split
  leftWritten := leftView.write
  leftEnabled := leftView.enable
  leftObserved := leftView.read
  val rightPad = digitalInout(
    Bits(4),
    DriveMode.pushPull,
    InoutPlacement.TopLevelPin,
    ResolutionProfile.PortableBoundaryOnly,
    "rightPad"
  )
  val rightWritten = out(Bits(4))
  val rightEnabled = out(Bool)
  val rightObserved = out(Bits(4))
  val rightView = rightPad.split
  rightWritten := rightView.write
  rightEnabled := rightView.enable
  rightObserved := rightView.read

final class Increment58FixedSplitVariants extends Module:
  val core = ClockDomain.external(
    "core",
    ClockEdge.Rising,
    ResetPolicy.None,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val joinedData = in(Bits(4))
  val joinedEnable = in(Bool)
  val leftData = in(Bits(4))
  val leftEnable = in(Bool)
  val rightData = in(Bits(4))
  val rightEnable = in(Bool)
  val firstLeftWritten = out(Bits(4))
  val firstLeftEnabled = out(Bool)
  val firstLeftObserved = out(Bits(4))
  val firstLeftPad = digitalInout(
    Bits(4),
    DriveMode.pushPull,
    InoutPlacement.TopLevelPin,
    ResolutionProfile.PortableBoundaryOnly,
    "firstLeftPad"
  )
  val firstRightWritten = out(Bits(4))
  val firstRightEnabled = out(Bool)
  val firstRightObserved = out(Bits(4))
  val firstRightPad = digitalInout(
    Bits(4),
    DriveMode.pushPull,
    InoutPlacement.TopLevelPin,
    ResolutionProfile.PortableBoundaryOnly,
    "firstRightPad"
  )
  val secondLeftWritten = out(Bits(4))
  val secondLeftEnabled = out(Bool)
  val secondLeftObserved = out(Bits(4))
  val secondLeftPad = digitalInout(
    Bits(4),
    DriveMode.pushPull,
    InoutPlacement.TopLevelPin,
    ResolutionProfile.PortableBoundaryOnly,
    "secondLeftPad"
  )
  val secondRightWritten = out(Bits(4))
  val secondRightEnabled = out(Bool)
  val secondRightObserved = out(Bits(4))
  val secondRightPad = digitalInout(
    Bits(4),
    DriveMode.pushPull,
    InoutPlacement.TopLevelPin,
    ResolutionProfile.PortableBoundaryOnly,
    "secondRightPad"
  )
  val first = instance(new Increment58FixedSplitTwoPinReader).domain(core)
  val second = instance(new Increment58FixedSplitTwoPinReader).domain(core)
  passThrough(firstLeftPad, first(_.leftPad))
  passThrough(firstRightPad, first(_.rightPad))
  passThrough(firstLeftPad, firstRightPad)
  passThrough(secondLeftPad, second(_.leftPad))
  passThrough(secondRightPad, second(_.rightPad))
  firstLeftPad.drive(joinedData, joinedEnable)
  secondLeftPad.drive(leftData, leftEnable)
  secondRightPad.drive(rightData, rightEnable)
  firstLeftWritten := first(_.leftWritten)
  firstLeftEnabled := first(_.leftEnabled)
  firstLeftObserved := first(_.leftObserved)
  firstRightWritten := first(_.rightWritten)
  firstRightEnabled := first(_.rightEnabled)
  firstRightObserved := first(_.rightObserved)
  secondLeftWritten := second(_.leftWritten)
  secondLeftEnabled := second(_.leftEnabled)
  secondLeftObserved := second(_.leftObserved)
  secondRightWritten := second(_.rightWritten)
  secondRightEnabled := second(_.rightEnabled)
  secondRightObserved := second(_.rightObserved)
