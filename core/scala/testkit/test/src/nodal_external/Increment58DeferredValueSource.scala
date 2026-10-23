package nodal_external

import nodal.*

final class Increment58DeferredValueLeaf extends Module:
  val required = ClockDomain.required("required")
  val width = param(4.integer, range = 2 to 8)
  val input = in(UInt(width))
  val output = out(UInt(width))
  output := input

final class Increment58DeferredValueSource(
    late: Boolean = true,
    mode: String = "valid",
    concrete: Boolean = false
) extends Module:
  val clock = ClockDomain.external(
    "clock",
    ClockEdge.Rising,
    ResetPolicy.None,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val width = if concrete then 4.integer else param(4.integer, range = 3 to 6)
  val otherWidth = if concrete then 5.integer else param(4.integer, range = 3 to 6)
  val input = in(UInt(width))
  val extended = out(UInt(6))
  val truncated = out(UInt(3))
  val wrapped = out(UInt(3))
  val saturated = out(UInt(3))
  val checked = out(UInt(6))
  val signed = out(SInt(width + 1.integer))
  val unsigned = out(UInt(width + 1.integer))
  val reinterpreted = out(SInt(width))
  val roundtrip = out(UInt(width))
  val arithmetic = out(SInt((width + 1.integer) + 1.integer))
  val data = StructField("data", UInt(width))
  val bits = StructField("bits", Bits(3))
  val payload = Struct("DeferredConstructed", data, bits)
  val other = Struct("OtherConstructed", data, bits)
  val differentWidth = Struct("DeferredConstructed", StructField("data", UInt(otherWidth)), bits)
  val dataKey = payload.member(data)
  val bitsKey = payload.member(bits)
  val aggregate = out(if mode == "wrong-width" then differentWidth else payload)
  val extracted = out(UInt(width))
  val child = instance(new Increment58DeferredValueLeaf).domain(clock)
  if !late then child.param(_.width, width)
  val selected = child(_.output)
  extended := selected.extend(if mode == "extend" then 5 else 6)
  truncated := selected.truncate(if mode == "truncate" then 4 else 3)
  wrapped := selected.wrap(3)
  saturated := selected.saturate(3)
  checked := selected.resizeChecked(if mode == "checked" then 3 else 6)
  signed := selected.toSigned
  unsigned :=
    (if mode == "negative" then selected.reinterpretSigned else selected.toSigned).toUnsigned
  reinterpreted := selected.reinterpretSigned
  roundtrip := selected.reinterpretSigned.reinterpretUnsigned
  arithmetic := (selected + input).toSigned
  val made = mode match
    case "foreign-schema" =>
      payload.value(other.member(data).bind(selected), bitsKey.bind(selected.extract(0, 3)))
    case "foreign-width" => payload.value(
        differentWidth.member(StructField("data", UInt(otherWidth))).bind(selected),
        bitsKey.bind(selected.extract(0, 3))
      )
    case "order" => payload.value(bitsKey.bind(selected.extract(0, 3)), dataKey.bind(selected))
    case _ => payload.value(dataKey.bind(selected), bitsKey.bind(selected.extract(0, 3)))
  aggregate := made
  extracted := made.field(dataKey)
  child(_.input) := input
  if late then child.param(_.width, width)

final class Increment58DeferredRealLeaf extends Module:
  val required = ClockDomain.required("required")
  val width = param(4.integer, range = 2 to 2048)
  val input = in(UInt(width))
  val output = out(UInt(width))
  output := input

final class Increment58DeferredRealSource(actual: Int) extends Module:
  val clock = ClockDomain.external(
    "clock",
    ClockEdge.Rising,
    ResetPolicy.None,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val input = in(UInt(actual))
  val child = instance(new Increment58DeferredRealLeaf).domain(clock)
  val converted = toReal(child(_.output))
  child(_.input) := input
  child.param(_.width, actual.integer)
