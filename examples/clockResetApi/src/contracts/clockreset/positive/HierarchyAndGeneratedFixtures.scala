package contracts.clockreset.positive

import nodal.*

final class AsyncBridge extends Module:
  val writeDomain = ClockDomain.required("write")
  val readDomain = ClockDomain.required("read")
  val writeData = in(UInt(8))
  val readData = out(UInt(8))

  writeDomain:
    val writeState = Reg(0.U(8))
    writeState := writeData

  readDomain:
    val readState = Reg(0.U(8))
    readState := Cdc.handshake(writeData, to = readDomain)
    readData := readState

final class GeneratedDomainFixture extends Module:
  val pllClock = in(Clock)
  val select = in(Bool)

  val core = ClockDomain.external(
    name = "core",
    edge = ClockEdge.Rising,
    reset = ResetPolicy.AsyncAssertSyncRelease(stages = 2),
    resetPolarity = ResetPolarity.ActiveLow,
    frequency = 100.MHz
  )

  val pixel = ClockDomain.generated(
    name = "pixel",
    clock = pllClock,
    from = core,
    relation = ClockRelation.Ratio(multiply = 3, divide = 2, phase = 0.deg),
    reset = Rdc.sync(core.reset, stages = 2)
  )

  val same = ClockRelation.Same
  val synchronous = ClockRelation.Synchronous(phaseKnown = false)
  val mutuallyExclusive = ClockRelation.MutuallyExclusive
  val asynchronous = ClockRelation.Asynchronous
  val unknown = ClockRelation.Unknown

  val bridge =
    instance(new AsyncBridge)
      .domain(_.writeDomain, core)
      .domain(_.readDomain, pixel)

  val counter = instance(new Counter).domain(pixel)
