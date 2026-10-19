package nodal_external

import nodal.*

trait WritableSignalClock:
  this: Module =>
  val core = ClockDomain.external(
    "core",
    ClockEdge.Rising,
    ResetPolicy.Sync,
    ResetPolarity.ActiveHigh,
    100.MHz
  )

/** Actual public authoring: only signals can introduce the private write capability. */
final class WritableSignalLanes(mode: String) extends Module, WritableSignalClock:
  val input = in(Vec(UInt(8), 2))
  val lanes = wire(Vec(UInt(8), 2))
  val output = out(Vec(UInt(8), 2))
  val scalar = out(UInt(8))
  mode match
    case "disjoint" =>
      lanes.writable.at(0) := input.at(0)
      lanes.writable.at(1) := input.at(1)
      output := lanes
    case "output" =>
      output.writable.at(0) := input.at(0)
      output.writable.at(1) := input.at(1)
    case "chain" =>
      lanes.writable.at(0) := input.at(0)
      lanes.writable.at(1) := lanes.writable.at(0).value
      output := lanes
    case "overlap" =>
      lanes.writable.at(0) := input.at(0)
      lanes.writable.at(0) := input.at(1)
    case "whole-overlap" =>
      lanes := input
      lanes.writable.at(1) := input.at(0)
    case "cycle" =>
      lanes.writable.at(0) := lanes.at(1)
      lanes.writable.at(1) := lanes.at(0)
    case "uncovered" =>
      lanes.writable.at(0) := input.at(0)
      scalar := lanes.at(1)
    case "input" => input.writable.at(0) := input.at(1)
    case "bounds" => lanes.writable.at(2) := input.at(0)
    case "width" => lanes.writable.at(0) := 1.U(7)
    case _ => ()

final class WritableSignalViews(mode: String) extends Module, WritableSignalClock:
  val input = in(Vec(UInt(8), 2, 3))
  val cells = wire(Vec(UInt(8), 2, 3))
  val output = out(Vec(UInt(8), 2, 3))
  val scalar = out(UInt(8))
  mode match
    case "rows" =>
      cells.writable.row(0) := input.row(0)
      cells.writable.row(1) := input.row(1)
      output := cells
    case "slices" =>
      cells.writable.slice(0, 1) := input.slice(0, 1)
      cells.writable.slice(1, 2) := input.slice(1, 2)
      output := cells
    case "columns" =>
      cells.writable.slice(0, 1, axis = 1) := input.slice(0, 1, axis = 1)
      cells.writable.slice(1, 3, axis = 1) := input.slice(1, 3, axis = 1)
      output := cells
    case "flatten" =>
      cells.writable.flatten.at(4) := input.at(0, 0)
      scalar := cells.at(1, 1)
    case "reshape" =>
      cells.writable.reshape(3, 2).row(1).at(0) := input.at(0, 0)
      scalar := cells.at(0, 2)
    case "composed" =>
      cells.writable.slice(1, 2).flatten.reshape(3, 1).at(2, 0) := input.at(0, 0)
      scalar := cells.at(1, 2)
    case "slice-bounds" => cells.writable.slice(1, 3) := input.slice(0, 2)
    case "reshape-size" => cells.writable.reshape(5).at(0) := input.at(0, 0)
    case "rank" => cells.writable.at(0) := input.at(0, 0)
    case _ => ()

final class WritableSignalPlanes extends Module, WritableSignalClock:
  val input = in(Vec(UInt(8), 2, 2, 3))
  val cells = wire(Vec(UInt(8), 2, 2, 3))
  val output = out(Vec(UInt(8), 2, 2, 3))
  cells.writable.plane(0) := input.plane(0)
  cells.writable.plane(1).row(0) := input.plane(1).row(0)
  cells.writable.plane(1).row(1) := input.plane(1).row(1)
  output := cells

final class WritableSignalSymbolic(mode: String) extends Module, WritableSignalClock:
  val count = param(4.integer, range = 2 to 20)
  val width = param(8.integer, range = 4 to 12)
  val index = param(0.integer, range = 0 to 1)
  val other = param(0.integer, range = 0 to 1)
  val input = in(UInt(width))
  val rows = in(Vec(UInt(width), count, 2))
  val output = out(Vec(UInt(width), count, 2))
  val rowOutput = out(Vec(UInt(width), 2))
  mode match
    case "selector" => output.writable.at(index, 1) := input
    case "row" =>
      output.writable.row(0) := rows.row(0)
      rowOutput := output.row(0)
    case "unknown-overlap" =>
      output.writable.at(index, 1) := input
      output.writable.at(other, 1) := input
    case "parameter-identity" =>
      val incompatible = in(UInt(count))
      output.writable.at(index, 1) := incompatible
    case _ => ()

final class WritableSignalRootPredecessor extends Module, WritableSignalClock:
  val input = in(UInt(8))
  val cells = wire(UInt(8))
  val state = Reg(0.U(8))
  val output = out(UInt(8))
  cells := input
  state := cells
  output.writable := state

final class WritableSignalChild extends Module, WritableSignalClock:
  val cells = wire(Vec(UInt(8), 2))
  val escaped = cells.writable.at(0)

final class WritableSignalForeign extends Module, WritableSignalClock:
  val input = in(UInt(8))
  val child = instance(new WritableSignalChild)
  child(_.escaped) := input

final class WritableSignalAggregate(mismatch: Boolean) extends Module, WritableSignalClock:
  val packet = Struct(
    "WritablePacket",
    StructField("payload", UInt(8)),
    StructField("flags", Vec(Bool, 2))
  )
  val other = Struct(
    "OtherPacket",
    StructField("payload", UInt(8)),
    StructField("flags", Vec(Bool, 2))
  )
  val input = in(Vec(if mismatch then other else packet, 2))
  val output = out(Vec(packet, 2))
  output.writable.at(0) := input.at(0)
  output.writable.at(1) := input.at(1)

/** Symbolic partial storage is legal when every actually observed region is driven. */
final class WritableSignalSymbolicCovered(row: Boolean) extends Module, WritableSignalClock:
  val count = param(4.integer, range = 2 to 20)
  val width = param(8.integer, range = 4 to 12)
  val index = param(0.integer, range = 0 to 1)
  val input = in(UInt(width))
  val rows = in(Vec(UInt(width), count, 2))
  val scratch = wire(Vec(UInt(width), count, 2))
  val output = out(UInt(width))
  val rowOutput = out(Vec(UInt(width), 2))
  if row then
    scratch.writable.row(0) := rows.row(0)
    rowOutput := scratch.row(0)
  else
    scratch.writable.at(index, 1) := input
    output := scratch.at(index, 1)
