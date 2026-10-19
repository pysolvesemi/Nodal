package nodal_external

import nodal.*

trait StructuralIfClock:
  this: Module =>
  val root = ClockDomain.external(
    "root",
    ClockEdge.Rising,
    ResetPolicy.Sync,
    ResetPolarity.ActiveHigh,
    100.MHz
  )

final class PublicStructuralValue(val defaultMode: Int) extends Module, StructuralIfClock:
  val MODE = param(defaultMode.integer)
  val first = in(Bits(8))
  val second = in(Bits(8))
  val output = out(Bits(8))
  val selected: Expr[Bits] = if MODE == 0 then ~first else second
  output := selected

final class PublicStructuralWrites extends Module, StructuralIfClock:
  val MODE = param(0.integer)
  val first = in(UInt(8))
  val second = in(UInt(8))
  val output = out(UInt(8))
  if MODE == 0 then output := first else output := second

final class PublicStructuralNested extends Module, StructuralIfClock:
  val MODE = param(1.integer, range = 0 to 3)
  val first = in(SInt(8))
  val second = in(SInt(8))
  val output = out(SInt(8))
  val selected: Expr[SInt] =
    if MODE == 0 || MODE >= 2 && !(MODE != 3) then first
    else if MODE < 2 then second else first
  output := selected

final class PublicStructuralGenerate(val laneCount: Int) extends Module, StructuralIfClock:
  val first = in(Vec(Bits(8), laneCount))
  val second = in(Vec(Bits(8), laneCount))
  val output = out(Vec(Bits(8), laneCount))
  hdlRange(0, laneCount): i =>
    val laneData = first.at(i)
    val selected = if i == 0 then ~laneData else laneData
    if i == 0 then output.writable.at(i) := selected
    else output.writable.at(i) := second.at(i)

final class PublicStructuralGeneratedValue extends Module, StructuralIfClock:
  val LANES = param(2.integer, range = 1 to 1000000)
  val first = in(Vec(UInt(8), LANES))
  val second = in(Vec(UInt(8), LANES))
  val output = out(Vec(UInt(8), LANES))
  hdlRange(0, LANES): i =>
    val selected: Expr[UInt] = if i == 0 then first.at(i) else second.at(i)
    output.writable.at(i) := selected

enum StructuralIfMode derives HwEnum:
  case Idle, Busy

final class PublicStructuralNominal extends Module, StructuralIfClock:
  val MODE = param(0.integer)
  val mode = EnumType[StructuralIfMode]()
  val schema = Struct("ConditionalPayload", StructField("mode", mode), StructField("word", UInt(8)))
  val first = in(schema)
  val second = in(schema)
  val output = out(schema)
  val selected: Expr[Struct] = if MODE == 0 then first else second
  output := selected
  val enumFirst = in(mode)
  val enumSecond = in(mode)
  val enumOutput = out(mode)
  val enumSelected: Expr[EnumValue[StructuralIfMode]] = if MODE != 0 then enumFirst else enumSecond
  enumOutput := enumSelected

final class PublicStructuralBoolVec extends Module, StructuralIfClock:
  val MODE = param(0.integer)
  val first = in(Vec(Bool, 3))
  val second = in(Vec(Bool, 3))
  val output = out(Vec(Bool, 3))
  val selected: Expr[Vec[Bool]] = if MODE == 0 then first else second
  output := selected
  val flag = in(Bool)
  val flagOutput = out(Bool)
  val selectedFlag: Expr[Bool] = if MODE == 0 then !flag else flag
  flagOutput := selectedFlag

final class PublicStructuralMissingElse extends Module, StructuralIfClock:
  val MODE = param(0.integer)
  val input = in(UInt(8))
  val output = out(UInt(8))
  if MODE == 0 then output := input

final class PublicStructuralDuplicate extends Module, StructuralIfClock:
  val MODE = param(0.integer)
  val input = in(UInt(8))
  val output = out(UInt(8))
  if MODE == 0 then
    output := input
    output := input
  else output := input

final class PublicStructuralIndependent extends Module, StructuralIfClock:
  val MODE = param(0.integer)
  val input = in(UInt(8))
  val output = out(UInt(8))
  if MODE == 0 then output := input
  if MODE != 0 then output := input

final class PublicStructuralCrossLane extends Module, StructuralIfClock:
  val input = in(UInt(8))
  val output = out(UInt(8))
  hdlRange(0, 2): i =>
    if i == 0 then output := input else output := input

final class PublicStructuralWrongWidth extends Module, StructuralIfClock:
  val MODE = param(0.integer)
  val first = in(Bits(8))
  val second = in(Bits(9))
  val selected: Expr[Bits] = if MODE == 0 then first else second

final class PublicStructuralProceduralIndex extends Module, StructuralIfClock:
  val input = in(UInt(8))
  val output = out(UInt(8))
  loop(LoopBound.Static(1)): i =>
    if i.value == 0 then output := input else output := input

final class PublicStructuralHostControl extends Module:
  var calls = 0
  if true then calls += 1 else calls += 100

final class PublicStructuralEmptyGenerate extends Module, StructuralIfClock:
  val input = in(Bool)
  hdlRange(0, 0): i =>
    val selected = if i == 0 then !input else input
    val _ = selected

final class PublicStructuralBranchCycle extends Module, StructuralIfClock:
  val MODE = param(0.integer)
  val input = in(UInt(8))
  val x = wire(UInt(8))
  val y = wire(UInt(8))
  val output = out(UInt(8))
  if MODE == 0 then
    x := y
    y := input
  else
    y := x
    x := input
  output := x

final class PublicStructuralFeasibleCycle extends Module, StructuralIfClock:
  val MODE = param(0.integer)
  val input = in(UInt(8))
  val x = wire(UInt(8))
  val y = wire(UInt(8))
  val output = out(UInt(8))
  if MODE == 0 then
    x := y
    y := x
  else
    y := input
    x := input
  output := x

final class PublicStructuralReduction extends Module, StructuralIfClock:
  val MODE = param(0.integer)
  val input = in(Vec(UInt(4), 3))
  val output = out(UInt(6))
  val reduced = input.reduce: (left, right) =>
    if MODE == 0 then left + right else left + right
  output := reduced

final class PublicStructuralReductionWrongABI extends Module, StructuralIfClock:
  val MODE = param(0.integer)
  val input = in(Vec(UInt(4), 3))
  val output = out(UInt(6))
  val reduced = input.reduce: (left, right) =>
    if MODE == 0 then left + right else right + right
  output := reduced

final class PublicStructuralLoopValue extends Module, StructuralIfClock:
  val MODE = param(0.integer)
  val input = in(Vec(Bits(8), 2))
  val output = out(Vec(Bits(8), 2))
  loop(LoopBound.Static(2)): i =>
    val selected = if MODE == 0 then ~input.at(i.value) else input.at(i.value)
    output.writable.at(i.value) := selected

final class PublicStructuralLoopStatement extends Module, StructuralIfClock:
  val MODE = param(0.integer)
  val input = in(Vec(Bits(8), 2))
  val output = out(Vec(Bits(8), 2))
  loop(LoopBound.Static(2)): i =>
    if MODE == 0 then output.writable.at(i.value + 0.integer) := ~input.at(i.value)
    else output.writable.at(i.value) := input.at(i.value)
