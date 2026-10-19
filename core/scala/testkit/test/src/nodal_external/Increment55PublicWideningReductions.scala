package nodal_external

import nodal.*

trait ReductionClock:
  this: Module =>
  val root = ClockDomain.external(
    "root",
    ClockEdge.Rising,
    ResetPolicy.Sync,
    ResetPolarity.ActiveHigh,
    100.MHz
  )

final class PublicWideningSums extends Module, ReductionClock:
  val width = param(4.integer, range = 2 to 8)
  val count = param(5.integer, range = 1 to 1000000)
  val input = in(Vec(UInt(width), count))
  val left = input.reduce((a, b) => a + b)
  val balanced = input.reduceBalancedTree((a, b) => a + b)
  val product = input.reduceBalancedTree((a, b) => a * b)
  val fixed = input.reduceBalancedTree((a, b) => (a + b).wrap(8))

final class PublicOrderedReductions extends Module, ReductionClock:
  val bits = in(Vec(Bits(3), 5))
  val signed = in(Vec(SInt(4), 5))
  val leftBits = bits.reduce((a, b) => a.concat(b))
  val treeBits = bits.reduceBalancedTree((a, b) => a.concat(b))
  val leftSigned = signed.reduce((a, b) => a - b)
  val treeSigned = signed.reduceBalancedTree((a, b) => a - b)

enum ReductionMode derives HwEnum:
  case Idle, Busy

final class PublicRecordReductions extends Module, ReductionClock:
  val width = param(4.integer, range = 2 to 8)
  val tagWidth = param(3.integer, range = 1 to 7)
  val payloadWidth = param(5.integer, range = 2 to 9)
  val inner = param(2.integer, range = 1 to 4)
  val count = param(5.integer, range = 1 to 1000000)
  val score = StructField("score", SInt(width))
  val tag = StructField("tag", Bits(tagWidth))
  val payload = StructField("payload", Vec(UInt(payloadWidth), inner))
  val valid = StructField("valid", Bool)
  val bit = StructField("bit", Bits(1))
  val mode = StructField("mode", EnumType[ReductionMode]())
  val schema = Struct("Candidate", score, tag, payload, valid, bit, mode)
  val scoreKey = schema.member(score)
  val tagKey = schema.member(tag)
  val payloadKey = schema.member(payload)
  val validKey = schema.member(valid)
  val bitKey = schema.member(bit)
  val modeKey = schema.member(mode)
  val input = in(Vec(schema, count))
  val winner = input.reduceBalancedTree: (left, right) =>
    select(left.field(scoreKey) >= right.field(scoreKey), left, right)
  val combined = input.reduceBalancedTree: (left, right) =>
    val takeLeft = left.field(scoreKey) >= right.field(scoreKey)
    schema.value(
      scoreKey.bind(left.field(scoreKey) + right.field(scoreKey)),
      tagKey.bind(select(takeLeft, left.field(tagKey), right.field(tagKey))),
      payloadKey.bind(select(takeLeft, left.field(payloadKey), right.field(payloadKey))),
      validKey.bind(select(takeLeft, left.field(validKey), right.field(validKey))),
      bitKey.bind(select(takeLeft, left.field(bitKey), right.field(bitKey))),
      modeKey.bind(select(takeLeft, left.field(modeKey), right.field(modeKey)))
    )
  val named = combined.named("combined_record").keep("whole record")

final class PublicNestedReduction extends Module, ReductionClock:
  val width = param(4.integer, range = 2 to 8)
  val inner = param(2.integer, range = 1 to 4)
  val input = in(Vec(Vec(UInt(width), inner), 5))
  val result = input.reduceBalancedTree: (left, right) =>
    left.zipWith(right)((a, b) => a + b)
  val wrapped = in(Vec(Vec(UInt(width), 5), 2))
  val rows = wrapped.map(row => row.reduce((a, b) => a + b))

final class PublicCoupledReduction extends Module, ReductionClock:
  val width = param(4.integer, range = 2 to 8)
  val otherWidth = param(4.integer, range = 2 to 8)
  val a = StructField("a", SInt(width))
  val b = StructField("b", SInt(otherWidth))
  val schema = Struct("Coupled", a, b)
  val aKey = schema.member(a)
  val bKey = schema.member(b)
  val input = in(Vec(schema, 5))
  val result = input.reduceBalancedTree: (left, right) =>
    schema.value(aKey.bind(left.field(aKey) + right.field(bKey)), bKey.bind(left.field(bKey)))

final class PublicBadSymbolicReduction extends Module, ReductionClock:
  val count = param(2.integer, range = 1 to 1000000)
  val input = in(Vec(UInt(4), count))
  val result = input.reduceBalancedTree((left, right) => (left ^ right).wrap(8))
