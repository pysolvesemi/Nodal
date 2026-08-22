package contracts.clockreset.positive

import nodal.*

final class CrossingFixtures extends Module:
  val source = ClockDomain.required("source")
  val destination = ClockDomain.required("destination")
  val level = in(Bool)
  val pulseInput = in(Bool)
  val payload = in(UInt(8))

  val synchronizedLevel = Cdc.sync(level, to = destination, stages = 2)
  val synchronizedGray = Cdc.gray(Gray(payload), to = destination, stages = 2)
  val synchronizedPulse = Cdc.pulse(Pulse(pulseInput), to = destination)
  val synchronizedPayload = Cdc.handshake(payload, to = destination)
  val synchronizedStream = Cdc.fifo(Stream(payload), to = destination, depth = 4)
  val waivedPayload = Cdc.waive(
    payload,
    to = destination,
    waiver = CdcWaiver(
      id = "CDC-001",
      reason = "fixture proves source-located waiver shape",
      relation = ClockRelation.Synchronous(phaseKnown = true)
    )
  )

  val synchronizedReset = Rdc.sync(source.reset, to = destination, stages = 2)
  val combinedReset = ResetController.combine(source.reset, destination.reset)

final class ClockStructureFixtures extends Module:
  val enable = in(Bool)
  val testEnable = in(Bool)
  val select = in(Bool)

  val source = ClockDomain.external(
    name = "source",
    edge = ClockEdge.Rising,
    reset = ResetPolicy.Sync,
    resetPolarity = ResetPolarity.ActiveHigh,
    frequency = 100.MHz
  )
  val alternate = ClockDomain.external(
    name = "alternate",
    edge = ClockEdge.Rising,
    reset = ResetPolicy.Sync,
    resetPolarity = ResetPolarity.ActiveHigh,
    frequency = 100.MHz
  )

  val gated = ClockGate(
    source,
    enable,
    testEnable = testEnable,
    name = "gated"
  )
  val selected = ClockMux.glitchless(
    select,
    domains = Seq(gated, alternate),
    name = "selected"
  )
