package nodal_external

import nodal.*

/** Actual public construction; no kernel values, forged snapshots or native text. */
final class DriverScalarSource(mode: String) extends Module:
  val core = ClockDomain.external(
    "core",
    ClockEdge.Rising,
    ResetPolicy.Sync,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val input = in(SInt(8))
  val alternate = in(SInt(8))
  val first = wire(SInt(8))
  val second = wire(SInt(8))
  val output = out(SInt(8))
  mode match
    case "multiple" =>
      first := input
      first := alternate
    case "self" => first := first
    case "two-cycle" =>
      first := second
      second := first
    case "alias-cycle" => first := first.named("feedback").keep("feedback edge")
    case "alias-positive" =>
      first := input.named("input_alias").keep("input edge")
      output := first
    case "undriven" => output := first
    case "unused" => output := input
    case "acyclic" =>
      first := input
      second := first
      output := second
    case _ => ()

final class DriverRegisterSource(mode: String) extends Module:
  val core = ClockDomain.external(
    "core",
    ClockEdge.Rising,
    ResetPolicy.Sync,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val input = in(SInt(8))
  val pending = wire(SInt(8))
  val state = Reg(0.S(8))
  mode match
    case "undriven" => state := pending
    case "driven" =>
      pending := input
      state := pending
    case "self" => state := state
    case _ => ()

final class DriverVecSource(mode: String) extends Module:
  val core = ClockDomain.external(
    "core",
    ClockEdge.Rising,
    ResetPolicy.Sync,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val input = in(Vec(SInt(8), 2))
  val lanes = wire(Vec(SInt(8), 2))
  val first = lanes.at(0)
  val second = lanes.at(1)
  val firstOutput = out(SInt(8))
  val secondOutput = out(SInt(8))
  val output = out(Vec(SInt(8), 2))
  mode match
    case "driven-lane-reads" =>
      lanes := input
      firstOutput := first
      secondOutput := second
      output := lanes
    case "alias-input" =>
      lanes := input.named("vector_input").keep("input aggregate")
      firstOutput := first
      secondOutput := second
    case "undriven-lane" => firstOutput := first
    case _ => ()

sealed trait DriverInterfaceLink extends Interface

final class DriverInterfaceSource(mode: String) extends Module:
  val core = ClockDomain.external(
    "core",
    ClockEdge.Rising,
    ResetPolicy.Sync,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val input = in(Bits(8))
  val definition = Interface[DriverInterfaceLink](
    "DriverInterface",
    InterfaceMember.value("data", Bits(8))
  )
  val source = interfacePort(
    definition,
    Role[SourceRole]("source", RoleAccess.Out("data")),
    "source",
    core
  )
  val sink = interfacePort(
    definition,
    Role[SinkRole]("sink", RoleAccess.In("data")),
    "sink",
    core
  )
  source.connectExact(sink)
  if mode == "driven" then source.driveMember("data", input)
  if mode == "feedback" then source.inverted.connectExact(sink.inverted)
  sink.observeMember("data")

final class DriverNetSource(mode: String) extends Module:
  val core = ClockDomain.external(
    "core",
    ClockEdge.Rising,
    ResetPolicy.Sync,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val input = in(Bits(8))
  val alternate = in(Bits(8))
  val enable = in(Bool)
  val pad = digitalInout(
    Bits(8),
    DriveMode.pushPull,
    InoutPlacement.TopLevelPin,
    ResolutionProfile.PortableBoundaryOnly,
    "pad"
  )
  val inner = digitalInout(
    Bits(8),
    DriveMode.pushPull,
    InoutPlacement.HierarchyPassThrough,
    ResolutionProfile.PortableBoundaryOnly,
    "inner"
  )
  mode match
    case "feedback" => pad.drive(pad.read, enable)
    case "pass-through-feedback" =>
      passThrough(pad, inner)
      inner.drive(pad.read, enable)
    case "two-net-feedback" =>
      pad.drive(inner.read, enable)
      inner.drive(pad.read, enable)
    case "acyclic" => pad.drive(input, enable)
    case "multiple-drivers" =>
      pad.drive(input, enable)
      pad.drive(alternate, enable)
    case "pass-through" =>
      passThrough(pad, inner)
      inner.drive(input, enable)
    case "aliases-only" => passThrough(pad, inner)
    case _ => ()
