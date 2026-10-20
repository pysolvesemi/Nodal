package nodal_external

import nodal.*

/** Actual public owned-clock witness, not a descriptor or handwritten native fixture. */
final class Increment57OwnedClockSource(
    kind: String,
    edge: ClockEdge,
    polarity: ResetPolarity,
    resetless: Boolean = false,
    nested: Boolean = false
) extends Module:
  val srcClock = in(Clock)
  val alternateClock = in(Clock)
  val reset = in(Reset)
  val enable = in(Bool)
  val testEnable = in(Bool)
  val select = in(Bool)
  val first = ClockDomain.from(
    srcClock,
    reset,
    edge,
    if resetless then ResetPolicy.None else ResetPolicy.AsyncAssertSyncRelease(2),
    polarity,
    100.MHz,
    "first"
  )
  val result = if kind == "gate" then ClockGate(first, enable, testEnable, "result")
  else
    val second = ClockDomain.from(
      alternateClock,
      reset,
      edge,
      ResetPolicy.AsyncAssertSyncRelease(2),
      polarity,
      67.MHz,
      "second"
    )
    val left = if nested then ClockGate(first, enable, testEnable, "gated0") else first
    val right = if nested then ClockGate(second, enable, testEnable, "gated1") else second
    ClockMux.glitchless(select, Seq(left, right), "result")
  result:
    val done = out(Bool)
    val state = if resetless then Reg.uninitialized(Bool) else Reg(false.B)
    state := true.B
    done := state

final class Increment57ShapedOwnedClockSource extends Module:
  val clocks = in(Vec(Clock, 2))
  val resets = in(Vec(Reset, 2))
  val select = in(Bool)
  val first = ClockDomain.from(
    clocks.at(0),
    resets.at(1),
    ClockEdge.Rising,
    ResetPolicy.AsyncAssertSyncRelease(2),
    ResetPolarity.ActiveHigh,
    100.MHz,
    "first"
  )
  val second = ClockDomain.from(
    clocks.slice(1, 2).at(0),
    resets.slice(1, 2).at(0),
    ClockEdge.Rising,
    ResetPolicy.AsyncAssertSyncRelease(2),
    ResetPolarity.ActiveHigh,
    67.MHz,
    "second"
  )
  val result = ClockMux.glitchless(select, Seq(first, second), "result")
  result:
    val done = out(Bool)
    val state = Reg(false.B)
    state := true.B
    done := state

final class Increment57ClockNegativeSource(mode: String) extends Module:
  val srcClock = in(Clock)
  val alternateClock = in(Clock)
  val reset = in(Reset)
  val otherReset = in(Reset)
  val first = ClockDomain.from(
    srcClock,
    reset,
    ClockEdge.Rising,
    ResetPolicy.AsyncAssertSyncRelease(2),
    ResetPolarity.ActiveHigh,
    100.MHz,
    "first"
  )
  val second = ClockDomain.from(
    if mode == "alias" then srcClock else alternateClock,
    if mode == "roots" then otherReset else reset,
    if mode == "edge" then ClockEdge.Falling else ClockEdge.Rising,
    if mode == "resetless" then ResetPolicy.None else ResetPolicy.AsyncAssertSyncRelease(2),
    ResetPolarity.ActiveHigh,
    67.MHz,
    "second"
  )
  var left: Option[Expr[Bool]] = None
  var right: Option[Expr[Bool]] = None
  first:
    val signal = in(Bool)
    left = Some(signal)
  second:
    val signal = in(Bool)
    right = Some(signal)
  val control = if mode == "mixed" then left.get && right.get else left.get
  if mode == "pulse" then
    val _ = Pulse(control)
  if mode == "gate_foreign" then
    val _ = ClockGate(first, right.get)
  else if mode == "caught" then
    try { val _ = ClockMux.glitchless(control, Seq(first), "bad") }
    catch case _: IllegalArgumentException => ()
  else
    val parents = if mode == "one" then Seq(first)
    else if mode == "three" then Seq(first, second, first)
    else Seq(first, second)
    val _ = ClockMux.glitchless(control, parents, "result")

final class Increment57ClockProvenanceSource(mode: String) extends Module:
  val root = ClockDomain.external(
    "root",
    ClockEdge.Rising,
    ResetPolicy.AsyncAssertSyncRelease(2),
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val external = in(Bool)
  val extraExternal = in(Bool)
  if mode == "constant" then
    val _ = ClockGate(root, true.B, false.B)
  else if mode == "external" then
    val _ = ClockGate(root, external && true.B)
  else if mode == "multiple_external" then
    val _ = ClockGate(root, external && extraExternal)
  else
    root:
      val enable = in(Bool)
      val control = if mode == "mixed_external" then enable && external else enable
      val first = ClockGate(root, control, name = "first")
      val second = ClockGate(root, control, name = "second")
      if mode == "unsafe_data" then
        first:
          val state = Reg(false.B)
          state := enable
      if mode == "nested" then
        val _ = ClockGate(first, true.B, name = "nested")
      val _ = second
