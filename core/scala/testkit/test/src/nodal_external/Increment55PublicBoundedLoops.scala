package nodal_external

import nodal.*

trait BoundedLoopClock:
  this: Module =>
  val root = ClockDomain.external(
    "root",
    ClockEdge.Rising,
    ResetPolicy.Sync,
    ResetPolarity.ActiveHigh,
    100.MHz
  )

final class PublicIndependentLoop extends Module, BoundedLoopClock:
  val input = in(Vec(UInt(8), 4))
  val output = out(Vec(UInt(8), 4))
  loop(LoopBound.Static(4)): i =>
    output.writable.at(i.value) := input.at(i.value)

final class PublicSymbolicLoop extends Module, BoundedLoopClock:
  val count = param(2.integer, range = 1 to 1000000)
  val input = in(Vec(SInt(8), count))
  val output = out(Vec(SInt(8), count))
  loop(LoopBound.Symbolic(count, 1000000)): i =>
    output.writable.at(i.value) := input.at(i.value)

final class PublicSeededLoop extends Module, BoundedLoopClock:
  val count = param(2.integer, range = 0 to 1000000)
  val seed = in(UInt(8))
  val cells = wire(Vec(UInt(8), count + 1.integer))
  val output = out(Vec(UInt(8), count + 1.integer))
  cells.writable.at(0) := seed
  loop(LoopBound.Symbolic(count, 1000000)): i =>
    cells.writable.at(i.value + 1.integer) := (cells.at(i.value) + 1.U(8)).wrap(8)
  output := cells

final class PublicStridedLoop extends Module, BoundedLoopClock:
  val input = in(Vec(UInt(8), 8))
  val output = out(Vec(UInt(8), 8))
  hdlRange(0, 8, 2): i =>
    output.writable.at(i) := input.at(i)
    output.writable.at(i + 1.integer) := input.at(i + 1.integer)

final class PublicNestedCollectionLoop extends Module, BoundedLoopClock:
  val increment = in(UInt(8))
  val input = in(Vec(Vec(UInt(8), 3), 4))
  val output = out(Vec(Vec(UInt(8), 3), 4))
  loop(LoopBound.Static(4)): i =>
    output.writable.at(i.value) := input.at(i.value).map(value =>
      (value + increment).wrap(8)
    )

final class PublicMixedRange extends Module, BoundedLoopClock:
  val left = in(Electrical)
  val right = out(Electrical)
  val input = in(Vec(UInt(8), 4))
  val output = out(Vec(UInt(8), 4))
  hdlRange(0, 4): i =>
    analog:
      I(left, right) <+ V(left, right) / 2.0.Ohm
    output.writable.at(i) := input.at(i)

final class PublicEmptyLoop extends Module, BoundedLoopClock:
  val input = in(Vec(UInt(8), 1))
  val scratch = wire(Vec(UInt(8), 1))
  loop(LoopBound.Static(0)): i =>
    scratch.writable.at(i.value) := input.at(i.value)

final class PublicMissingLaneLoop extends Module, BoundedLoopClock:
  val input = in(Vec(UInt(8), 4))
  val output = out(Vec(UInt(8), 4))
  loop(LoopBound.Static(3)): i =>
    output.writable.at(i.value) := input.at(i.value)

final class PublicMaxOnlyLoop extends Module, BoundedLoopClock:
  val count = param(2.integer)
  val input = in(Vec(UInt(8), 4))
  val output = out(Vec(UInt(8), 4))
  loop(LoopBound.Symbolic(count, 4)): i =>
    output.writable.at(i.value) := input.at(i.value)

final class PublicStaticOffsetLoop extends Module, BoundedLoopClock:
  val count = param(2.integer, range = 1 to 8)
  val offset = param(1.integer, range = 0 to 4)
  val input = in(Vec(UInt(8), count + offset))
  val output = out(Vec(UInt(8), count))
  loop(LoopBound.Symbolic(count, 8)): index =>
    output.writable.at(index.value) := input.at(index.value + offset)

enum BoundedLoopMode derives HwEnum:
  case Idle, Busy

final class PublicNominalLoop extends Module, BoundedLoopClock:
  val mode = EnumType[BoundedLoopMode]()
  val schema = Struct("LoopPayload", StructField("mode", mode), StructField("value", SInt(8)))
  val modes = in(Vec(mode, 2))
  val records = in(Vec(schema, 2))
  val modeOutput = out(Vec(mode, 2))
  val recordOutput = out(Vec(schema, 2))
  loop(LoopBound.Static(2)): index =>
    modeOutput.writable.at(index.value) := modes.at(index.value)
    recordOutput.writable.at(index.value) := records.at(index.value)

final class PublicLocalStaticOffsetLoop extends Module, BoundedLoopClock:
  val count = param(2.integer, range = 1 to 8)
  val offset = param(1.integer, range = 0 to 4)
  val input = in(Vec(UInt(8), count + (offset + 1.integer)))
  val output = out(Vec(UInt(8), count))
  loop(LoopBound.Symbolic(count, 8)): index =>
    val position = offset + 1.integer
    output.writable.at(index.value) := input.at(index.value + position)

final class PublicNegativeOriginLoop extends Module, BoundedLoopClock:
  val input = in(Vec(UInt(8), 2))
  val output = out(Vec(UInt(8), 2))
  hdlRange(-2, 0): index =>
    output.writable.at(index + 2.integer) := input.at(index + 2.integer)

final class PublicRepeatedScalarLoop extends Module, BoundedLoopClock:
  val input = in(UInt(8))
  val output = out(UInt(8))
  loop(LoopBound.Static(2))(_ => output := input)

final class PublicEmptyOutputLoop extends Module, BoundedLoopClock:
  val input = in(Vec(UInt(8), 1))
  val output = out(Vec(UInt(8), 1))
  loop(LoopBound.Static(0)): index =>
    output.writable.at(index.value) := input.at(index.value)

final class PublicSymbolicWidthLoop extends Module, BoundedLoopClock:
  val width = param(8.integer, range = 1 to 32)
  val input = in(Vec(UInt(width), 2))
  val output = out(Vec(UInt(width), 2))
  loop(LoopBound.Static(2)): index =>
    output.writable.at(index.value) := input.at(index.value)

final class PublicTwoLoopPartitions extends Module, BoundedLoopClock:
  val input = in(Vec(UInt(8), 4))
  val output = out(Vec(UInt(8), 4))
  loop(LoopBound.Static(2)): index =>
    output.writable.at(index.value) := input.at(index.value)
  loop(LoopBound.Static(2)): index =>
    output.writable.at(index.value + 2.integer) := input.at(index.value + 2.integer)

final class PublicUnusedStructuralIndexRange extends Module:
  hdlRange(0, 4): index =>
    val unused = index + 1.integer
    val _ = unused
    val tap = node(Electrical)
    val _ = tap

final class PublicNestedLeftReductionLoop extends Module, BoundedLoopClock:
  val input = in(Vec(Vec(UInt(4), 3), 2))
  val output = out(Vec(UInt(6), 2))
  loop(LoopBound.Static(2)): index =>
    output.writable.at(index.value) := input.at(index.value).reduce(_ + _)

final class PublicNestedTreeReductionLoop extends Module, BoundedLoopClock:
  val input = in(Vec(Vec(UInt(4), 3), 2))
  val output = out(Vec(UInt(6), 2))
  loop(LoopBound.Static(2)): index =>
    output.writable.at(index.value) := input.at(index.value).reduceBalancedTree(_ + _)

final class PublicSymbolicNestedTreeReductionLoop extends Module, BoundedLoopClock:
  val width = param(4.integer, range = 1 to 16)
  val input = in(Vec(Vec(UInt(width), 3), 2))
  val output = out(Vec(UInt(width + 2.integer), 2))
  loop(LoopBound.Static(2)): index =>
    output.writable.at(index.value) := input.at(index.value).reduceBalancedTree(_ + _)
