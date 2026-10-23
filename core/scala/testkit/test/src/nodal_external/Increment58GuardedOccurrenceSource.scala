package nodal_external

import nodal.*

final class Increment58GuardedOccurrenceLeaf extends Module:
  val required = ClockDomain.required("required")
  val input = in(UInt(8))
  val output = out(UInt(8))
  output := input

final class Increment58GuardedDefaultOccurrenceSource extends Module:
  val ENABLE = param(1.integer, range = 0 to 1)
  val LANES = param(3.integer, range = 1 to 1000000)
  val core = ClockDomain.external(
    "core",
    ClockEdge.Rising,
    ResetPolicy.Sync,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val input = in(Vec(UInt(8), LANES))
  val output = out(Vec(UInt(8), LANES))
  val first = out(UInt(8))
  core:
    if ENABLE == 1 then
      val lanes =
        for i <- hdlRange(0, LANES) yield new Area:
          private val child = instance(new Increment58GuardedOccurrenceLeaf).domain(core)
          child(_.input) := input.at(i)
          val stored = wire(UInt(8))
          stored := child(_.output)
          output.writable.at(i) := stored
      first := lanes(0).stored
    else
      output := input
      first := 0.U(8)

final class Increment58GuardedNamedOccurrenceSource extends Module:
  val ENABLE = param(1.integer, range = 0 to 1)
  val LANES = param(3.integer, range = 1 to 1000000)
  val core = ClockDomain.external(
    "core",
    ClockEdge.Rising,
    ResetPolicy.Sync,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val input = in(Vec(UInt(8), LANES))
  val output = out(Vec(UInt(8), LANES))
  val first = out(UInt(8))
  core:
    if ENABLE == 1 then
      val lanes =
        for i <- hdlRange(0, LANES) yield new Area:
          private val child =
            instance(new Increment58GuardedOccurrenceLeaf).domain(_.required, core)
          child(_.input) := input.at(i)
          val stored = wire(UInt(8))
          stored := child(_.output)
          output.writable.at(i) := stored
      first := lanes(0).stored
    else
      output := input
      first := 0.U(8)

final class Increment58GuardedInheritedOccurrenceSource extends Module:
  val ENABLE = param(1.integer, range = 0 to 1)
  val LANES = param(3.integer, range = 1 to 1000000)
  val core = ClockDomain.external(
    "core",
    ClockEdge.Rising,
    ResetPolicy.Sync,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val input = in(Vec(UInt(8), LANES))
  val output = out(Vec(UInt(8), LANES))
  val first = out(UInt(8))
  core:
    if ENABLE == 1 then
      val lanes =
        for i <- hdlRange(0, LANES) yield new Area:
          private val child = instance(new Increment58GuardedOccurrenceLeaf)
          child(_.input) := input.at(i)
          val stored = wire(UInt(8))
          stored := child(_.output)
          output.writable.at(i) := stored
      first := lanes(0).stored
    else
      output := input
      first := 0.U(8)

final class Increment58GuardedResetlessOccurrenceSource extends Module:
  val ENABLE = param(1.integer, range = 0 to 1)
  val LANES = param(3.integer, range = 1 to 1000000)
  val core = ClockDomain.external(
    "core",
    ClockEdge.Rising,
    ResetPolicy.None,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val input = in(Vec(UInt(8), LANES))
  val output = out(Vec(UInt(8), LANES))
  val first = out(UInt(8))
  core:
    if ENABLE == 1 then
      val lanes =
        for i <- hdlRange(0, LANES) yield new Area:
          private val child = instance(new Increment58GuardedOccurrenceLeaf).domain(core)
          child(_.input) := input.at(i)
          val stored = wire(UInt(8))
          stored := child(_.output)
          output.writable.at(i) := stored
      first := lanes(0).stored
    else
      output := input
      first := 0.U(8)

final class Increment58GuardedLateNamedOccurrenceSource extends Module:
  val ENABLE = param(1.integer, range = 0 to 1)
  val LANES = param(3.integer, range = 1 to 1000000)
  val core = ClockDomain.external(
    "core",
    ClockEdge.Rising,
    ResetPolicy.Sync,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val other = ClockDomain.external(
    "other",
    ClockEdge.Falling,
    ResetPolicy.None,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  other:
    val input = in(Vec(UInt(8), LANES))
    val output = out(Vec(UInt(8), LANES))
    val first = out(UInt(8))
    core:
      if ENABLE == 1 then
        val lanes =
          for i <- hdlRange(0, LANES) yield new Area:
            private val child = instance(new Increment58GuardedOccurrenceLeaf).domain(core)
            child(_.input) := input.at(i)
            val stored = wire(UInt(8))
            stored := child(_.output)
            output.writable.at(i) := stored
            child.domain(_.required, other)
        first := lanes(0).stored
      else
        output := input
        first := 0.U(8)

final class Increment58GuardedNestedGuardOccurrenceSource extends Module:
  val MODE = param(1.integer, range = 0 to 1)
  val ENABLE = param(1.integer, range = 0 to 1)
  val LANES = param(3.integer, range = 1 to 1000000)
  val core = ClockDomain.external(
    "core",
    ClockEdge.Rising,
    ResetPolicy.Sync,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val input = in(Vec(UInt(8), LANES))
  val output = out(Vec(UInt(8), LANES))
  val first = out(UInt(8))
  core:
    if ENABLE == 1 then
      if MODE == 1 then
        val lanes =
          for i <- hdlRange(0, LANES) yield new Area:
            private val child = instance(new Increment58GuardedOccurrenceLeaf).domain(core)
            child(_.input) := input.at(i)
            val stored = wire(UInt(8))
            stored := child(_.output)
            output.writable.at(i) := stored
        first := lanes(0).stored
      else
        output := input
        first := 0.U(8)
    else
      output := input
      first := 0.U(8)

final class Increment58GuardedRepeatedRootOccurrenceSource extends Module:
  val ENABLE = param(1.integer, range = 0 to 1)
  val LANES = param(3.integer, range = 1 to 1000000)
  val core = ClockDomain.external(
    "core",
    ClockEdge.Rising,
    ResetPolicy.Sync,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val input = in(Vec(UInt(8), LANES))
  val output = out(Vec(UInt(8), LANES))
  val first = out(UInt(8))
  core:
    if ENABLE == 1 then
      val lanes =
        for i <- hdlRange(0, LANES) yield new Area:
          private val child = instance(new Increment58GuardedOccurrenceLeaf).domain(core)
          private val second = instance(new Increment58GuardedOccurrenceLeaf).domain(core)
          child(_.input) := input.at(i)
          second(_.input) := input.at(i)
          val stored = wire(UInt(8))
          stored := child(_.output) ^ second(_.output)
          output.writable.at(i) := stored
      first := lanes(0).stored
    else
      output := input
      first := 0.U(8)

final class Increment58GuardedForwardedMiddle extends Module:
  val ENABLE = param(1.integer, range = 0 to 1)
  val LANES = param(3.integer, range = 1 to 1000000)
  val core = ClockDomain.required("core")
  val input = in(Vec(UInt(8), LANES))
  val output = out(Vec(UInt(8), LANES))
  val first = out(UInt(8))
  core:
    if ENABLE == 1 then
      val lanes =
        for i <- hdlRange(0, LANES) yield new Area:
          private val child = instance(new Increment58GuardedOccurrenceLeaf).domain(core)
          child(_.input) := input.at(i)
          val stored = wire(UInt(8))
          stored := child(_.output)
          output.writable.at(i) := stored
      first := lanes(0).stored
    else
      output := input
      first := 0.U(8)

final class Increment58GuardedRequiredForwardingOccurrenceSource extends Module:
  val core = ClockDomain.external(
    "core",
    ClockEdge.Rising,
    ResetPolicy.Sync,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val input = in(Vec(UInt(8), 3))
  val output = out(Vec(UInt(8), 3))
  val first = out(UInt(8))
  val child = instance(new Increment58GuardedForwardedMiddle).domain(core).param(_.LANES, 3.integer)
  child(_.input) := input
  output := child(_.output)
  first := child(_.first)

final class Increment58GuardedNestedFamilyOccurrenceSource extends Module:
  val ENABLE = param(1.integer, range = 0 to 1)
  val LANES = param(3.integer, range = 1 to 1000000)
  val core = ClockDomain.external(
    "core",
    ClockEdge.Rising,
    ResetPolicy.Sync,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val input = in(Vec(UInt(8), 2, LANES))
  val output = out(Vec(UInt(8), 2, LANES))
  val first = out(UInt(8))
  core:
    if ENABLE == 1 then
      val rows =
        for r <- hdlRange(0, 2) yield new Area:
          val lanes =
            for i <- hdlRange(0, LANES) yield new Area:
              private val child = instance(new Increment58GuardedOccurrenceLeaf).domain(core)
              child(_.input) := input.row(r).at(i)
              val stored = wire(UInt(8))
              stored := child(_.output)
              output.writable.row(r).at(i) := stored
      first := rows(1).lanes(0).stored
    else
      output := input
      first := 0.U(8)
