package nodal_external

import nodal.*

object Increment58NamedCdcPayload:
  val samples = Vec(UInt(3), 2)
  val cell = Struct(
    "NamedCdcCell",
    StructField("flag", Bool),
    StructField("samples", samples),
    StructField("signed", SInt(2))
  )
  val rows = Vec(cell, 2)
  val payload = Struct(
    "NamedCdcPayload",
    StructField("rows", rows),
    StructField("marker", Bool)
  )

/** Unequal nested leaves expose row-major versus field-major CDC serialization. */
final class Increment58NamedCdcSource(mode: String) extends Module:
  import Increment58NamedCdcPayload.*
  require(Set("gray", "handshake", "fifo").contains(mode))
  val srcClock = in(Clock)
  val dstClock = in(Clock)
  val reset = in(Reset)
  val source = ClockDomain.from(
    srcClock,
    reset,
    ClockEdge.Rising,
    ResetPolicy.AsyncAssertSyncRelease(2),
    ResetPolarity.ActiveHigh,
    100.MHz,
    name = "source"
  )
  val destination = ClockDomain.from(
    dstClock,
    reset,
    ClockEdge.Rising,
    ResetPolicy.AsyncAssertSyncRelease(2),
    ResetPolarity.ActiveHigh,
    20.MHz,
    name = "destination"
  )
  var sampled = Option.empty[Expr[Struct]]
  var incoming = Option.empty[Stream[Struct]]
  var ready = Option.empty[Signal[Bool]]
  source:
    val inputValue = in(payload)
    sampled = Some(inputValue)
    if mode == "fifo" then
      val inputValid = in(Bool)
      val inputReady = out(Bool)
      incoming = Some(Stream(inputValue, inputValid))
      ready = Some(inputReady)
  val crossedStream =
    if mode == "fifo" then Some(Cdc.fifo(incoming.get, destination, 4)) else None
  val crossed = mode match
    case "gray" => Cdc.gray(Gray(sampled.get), destination, 2)
    case "handshake" => Cdc.handshake(sampled.get, destination)
    case _ => crossedStream.get.payload
  if mode == "fifo" then ready.get := incoming.get.ready
  destination:
    val outputValue = out(payload)
    outputValue := crossed
    if mode == "fifo" then
      val outputValid = out(Bool)
      val outputReady = in(Bool)
      outputValid := crossedStream.get.valid
      crossedStream.get.accept(outputReady)
    val selectedRows = crossed.field(payload.member(StructField("rows", rows)))
    val first = selectedRows.at(0.integer)
    val second = selectedRows.at(1.integer)
    val firstFlag = out(Bool)
    val secondFlag = out(Bool)
    val firstLow = out(UInt(3))
    val firstHigh = out(UInt(3))
    val secondLow = out(UInt(3))
    val secondHigh = out(UInt(3))
    val firstSigned = out(SInt(2))
    val secondSigned = out(SInt(2))
    val marker = out(Bool)
    firstFlag := first.field(cell.member(StructField("flag", Bool)))
    secondFlag := second.field(cell.member(StructField("flag", Bool)))
    firstLow := first.field(cell.member(StructField("samples", samples))).at(0.integer)
    firstHigh := first.field(cell.member(StructField("samples", samples))).at(1.integer)
    secondLow := second.field(cell.member(StructField("samples", samples))).at(0.integer)
    secondHigh := second.field(cell.member(StructField("samples", samples))).at(1.integer)
    firstSigned := first.field(cell.member(StructField("signed", SInt(2))))
    secondSigned := second.field(cell.member(StructField("signed", SInt(2))))
    marker := crossed.field(payload.member(StructField("marker", Bool)))
