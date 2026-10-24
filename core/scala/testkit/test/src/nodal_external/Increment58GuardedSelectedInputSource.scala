package nodal_external

import nodal.*

/** Existing scalar child boundary; no template input writer. */
final class Increment58GuardedSelectedInputLeaf extends Module:
  val required = ClockDomain.required("required")
  val input = in(Bool)
  val output = out(Bool)
  output := input

final class Increment58GuardedSelectedInputTwo(defaultChoice: Int = 0) extends Module:
  val choice = param(defaultChoice.integer, range = 0 to 1)
  val core = ClockDomain.external(
    "core", ClockEdge.Rising, ResetPolicy.None, ResetPolarity.ActiveHigh, 100.MHz
  )
  val first = in(Bool)
  val second = in(Bool)
  if choice == 0 then
    val lanes = for i <- hdlRange(0, 2) yield new Area:
      val _ = i
      val child = instance(new Increment58GuardedSelectedInputLeaf).domain(core)
    val a = lanes(0).child
    val b = lanes(1).child
    a(_.input) := first
    b(_.input) := second
  else ()

final class Increment58GuardedSelectedInputMissing extends Module:
  val choice = param(0.integer, range = 0 to 1)
  val core = ClockDomain.external(
    "core", ClockEdge.Rising, ResetPolicy.None, ResetPolarity.ActiveHigh, 100.MHz
  )
  val first = in(Bool)
  val second = in(Bool)
  if choice == 0 then
    val lanes = for i <- hdlRange(0, 2) yield new Area:
      val _ = i
      val child = instance(new Increment58GuardedSelectedInputLeaf).domain(core)
    val a = lanes(0).child
    val b = lanes(1).child
    a(_.input) := first
    val _ = b
  else ()

final class Increment58GuardedSelectedInputDuplicate extends Module:
  val choice = param(0.integer, range = 0 to 1)
  val core = ClockDomain.external(
    "core", ClockEdge.Rising, ResetPolicy.None, ResetPolarity.ActiveHigh, 100.MHz
  )
  val first = in(Bool)
  val second = in(Bool)
  if choice == 0 then
    val lanes = for i <- hdlRange(0, 2) yield new Area:
      val _ = i
      val child = instance(new Increment58GuardedSelectedInputLeaf).domain(core)
    val a = lanes(0).child
    val b = lanes(1).child
    a(_.input) := first
    b(_.input) := second
    val alias = lanes(0.integer).child
    alias(_.input) := second
  else ()

final class Increment58GuardedSelectedInputLocalRhs extends Module:
  val choice = param(0.integer, range = 0 to 1)
  val core = ClockDomain.external(
    "core", ClockEdge.Rising, ResetPolicy.None, ResetPolarity.ActiveHigh, 100.MHz
  )
  val first = in(Bool)
  val second = in(Bool)
  if choice == 0 then
    val lanes = for i <- hdlRange(0, 2) yield new Area:
      val _ = i
      val child = instance(new Increment58GuardedSelectedInputLeaf).domain(core)
    val a = lanes(0).child
    val b = lanes(1).child
    a(_.input) := !first
    b(_.input) := second
  else ()

final class Increment58GuardedSelectedInputOtherArm extends Module:
  val choice = param(0.integer, range = 0 to 1)
  val core = ClockDomain.external(
    "core", ClockEdge.Rising, ResetPolicy.None, ResetPolarity.ActiveHigh, 100.MHz
  )
  val first = in(Bool)
  val second = in(Bool)
  if choice == 0 then
    val lanes = for i <- hdlRange(0, 2) yield new Area:
      val _ = i
      val child = instance(new Increment58GuardedSelectedInputLeaf).domain(core)
    val a = lanes(0).child
    val b = lanes(1).child
    a(_.input) := first
    b(_.input) := second
  else
    val unused = !first
    val _ = unused

final class Increment58GuardedSelectedInputElse extends Module:
  val choice = param(0.integer, range = 0 to 1)
  val core = ClockDomain.external(
    "core", ClockEdge.Rising, ResetPolicy.None, ResetPolarity.ActiveHigh, 100.MHz
  )
  val first = in(Bool)
  val second = in(Bool)
  if choice == 0 then ()
  else
    val lanes = for i <- hdlRange(0, 2) yield new Area:
      val _ = i
      val child = instance(new Increment58GuardedSelectedInputLeaf).domain(core)
    val a = lanes(0).child
    val b = lanes(1).child
    a(_.input) := first
    b(_.input) := second
