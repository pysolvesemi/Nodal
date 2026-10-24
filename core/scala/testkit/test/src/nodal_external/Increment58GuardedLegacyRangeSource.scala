package nodal_external
import nodal.*

/** Actual plugin-compiled legacy foreach, never an imported writing helper. */
final class Increment58GuardedLegacyRangeSource(defaultMode: Int, lower: Int = 0,
    stride: Int = 1) extends Module, StructuralIfClock:
  val mode = param(defaultMode.integer, range = 0 to 1)
  val count = param(5.integer, range = 1 to 8)
  val first = in(Vec(UInt(8), count))
  val second = in(Vec(UInt(8), count))
  val output = out(Vec(UInt(8), count))
  val before = out(UInt(8))
  val after = out(UInt(8))
  if mode == 0 then
    before := first.at(0)
    hdlRange(lower, count, stride): i =>
      val selected: Expr[UInt] = if i == 0 then first.at(i) else second.at(i)
      output.writable.at(i) := selected
    after := second.at(0)
  else
    before := second.at(0)
    output := second
    after := first.at(0)

/** Preserve the formerly rejected empty-body/domain-binding source shape. */
final class Increment58GuardedLegacyEmptyClockSource extends Module:
  val choice = param(0.integer, range = 0 to 1)
  val clock = ClockDomain.external("clock", ClockEdge.Rising, ResetPolicy.Sync,
    ResetPolarity.ActiveHigh, 100.MHz)
  val enable = in(Bool)
  clock:
    if choice == 0 then
      hdlRange(0, 2): i =>
        val _ = i
    else ()

final class Increment58GuardedLegacyEmptyRangeSource extends Module, StructuralIfClock:
  val choice = param(0.integer, range = 0 to 1)
  val input = in(Bool)
  if choice == 0 then
    hdlRange(0, 0).foreach: i =>
      val selected = if i == 0 then !input else input
      val _ = selected
  else ()

/** Default selects the valid arm; the invalid alternative must still be checked. */
final class Increment58GuardedLegacyInvalidAlternative extends Module, StructuralIfClock:
  val choice = param(0.integer, range = 0 to 1)
  val first = in(Vec(UInt(8), 2))
  val second = in(Vec(UInt(9), 2))
  val output = out(Vec(UInt(8), 2))
  if choice == 0 then output := first
  else
    hdlRange(0, 2): i =>
      val selected: Expr[UInt] = if i == 0 then first.at(i) else second.at(i)
      output.writable.at(i) := selected

final class Increment58GuardedLegacyCaughtFailure extends Module, StructuralIfClock:
  val choice = param(0.integer, range = 0 to 1)
  val first = in(Vec(UInt(8), 2))
  val second = in(Vec(UInt(9), 2))
  val output = out(Vec(UInt(8), 2))
  val caught = scala.util.Try:
    if choice == 0 then
      hdlRange(0, 2): i =>
        val selected: Expr[UInt] = if i == 0 then first.at(i) else second.at(i)
        output.writable.at(i) := selected
    else output := first

final class Increment58GuardedLegacyRuntimeBound extends Module, StructuralIfClock:
  val choice = param(0.integer, range = 0 to 1)
  val bound = in(Integer)
  val input = in(Vec(UInt(8), 8))
  val output = out(Vec(UInt(8), 8))
  if choice == 0 then
    hdlRange(0, bound): i =>
      output.writable.at(i) := input.at(i)
  else output := input

final class Increment58GuardedLegacyBoundOwner extends Module:
  val count = param(3.integer, range = 1 to 8)

// Same-transaction foreign Module ownership, as in Increment43ForeignSliceBound.
final class Increment58GuardedLegacyForeignBound extends Module, StructuralIfClock:
  val child = new Increment58GuardedLegacyBoundOwner
  val choice = param(0.integer, range = 0 to 1)
  val input = in(Vec(UInt(8), 8))
  val output = out(Vec(UInt(8), 8))
  if choice == 0 then
    hdlRange(0, child.count): i =>
      output.writable.at(i) := input.at(i)
  else output := input
