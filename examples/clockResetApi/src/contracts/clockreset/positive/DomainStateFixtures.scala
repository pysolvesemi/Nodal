package contracts.clockreset.positive

import nodal.*

final class Counter extends Module:
  val enable = in(Bool)
  val load = in(Bool)
  val input = in(UInt(8))
  val value = out(UInt(8))

  val count = Reg(0.U(8))
  val delayedEnable = RegNext(enable, false.B)
  val scratch = Reg.uninitialized(UInt(8))
  val delayedInput = RegNext.uninitialized(input)

  when(load):
    count := input
  elsewhen(delayedEnable):
    count := count + 1.U(8)
  otherwise:
    scratch := delayedInput

  value := count

final class ExplicitRootBinding extends Module:
  val core = ClockDomain.external(
    name = "core",
    edge = ClockEdge.Rising,
    reset = ResetPolicy.AsyncAssertSyncRelease(stages = 2),
    resetPolarity = ResetPolarity.ActiveLow,
    frequency = 100.MHz
  )

  core:
    instance(new Counter)

final class ExistingSignalBinding extends Module:
  val clockPin = in(Clock)
  val resetPin = in(Reset)
  val value = out(UInt(8))

  val core = ClockDomain.from(
    clock = clockPin,
    reset = resetPin,
    edge = ClockEdge.Rising,
    policy = ResetPolicy.Sync,
    polarity = ResetPolarity.ActiveHigh,
    frequency = 50.MHz,
    name = "boundCore"
  )

  core:
    val state = Reg(0.U(8))
    state := state + 1.U(8)
    value := state

final class ResetPolicyFixtures extends Module:
  val noReset = ClockDomain.external(
    name = "noReset",
    edge = ClockEdge.Rising,
    reset = ResetPolicy.None,
    resetPolarity = ResetPolarity.ActiveHigh,
    frequency = 25.MHz
  )
  val synchronous = ClockDomain.external(
    name = "synchronous",
    edge = ClockEdge.Rising,
    reset = ResetPolicy.Sync,
    resetPolarity = ResetPolarity.ActiveHigh,
    frequency = 50.MHz
  )
  val asynchronous = ClockDomain.external(
    name = "asynchronous",
    edge = ClockEdge.Falling,
    reset = ResetPolicy.Async,
    resetPolarity = ResetPolarity.ActiveLow,
    frequency = 75.MHz
  )
  val asyncAssertSyncRelease = ClockDomain.external(
    name = "asyncAssertSyncRelease",
    edge = ClockEdge.Rising,
    reset = ResetPolicy.AsyncAssertSyncRelease(stages = 3),
    resetPolarity = ResetPolarity.ActiveLow,
    frequency = 100.MHz
  )
