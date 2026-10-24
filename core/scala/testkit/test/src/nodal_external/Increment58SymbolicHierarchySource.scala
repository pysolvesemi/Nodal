package nodal_external

import nodal.*

final class Increment58SymbolicHierarchyLeaf(vector: Boolean) extends Module:
  val required = ClockDomain.required("required")
  val width: Param[Integer] = param(4.integer, range = 2 to 8)
  val count: Param[Integer] = param(2.integer, range = 1 to 4)
  val inputData = in(if vector then Vec(UInt(width), count) else UInt(width))
  val outputData = out(if vector then Vec(UInt(width), count) else UInt(width))
  outputData := inputData

final class Increment58SymbolicHierarchySource(vector: Boolean = false, late: Boolean = true)
    extends Module:
  val core = ClockDomain.external(
    "core",
    ClockEdge.Rising,
    ResetPolicy.None,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val width: Param[Integer] = param(4.integer, range = 2 to 8)
  val count: Param[Integer] = param(2.integer, range = 1 to 4)
  val other: Param[Integer] = param(4.integer, range = 2 to 8)
  val inputData = in(if vector then Vec(UInt(width), count) else UInt(width))
  val outputData = out(if vector then Vec(UInt(width), count) else UInt(width))
  val child = instance(new Increment58SymbolicHierarchyLeaf(vector)).domain(core)
  if !late then
    child.param(_.width, width)
    child.param(_.count, count)
  child(_.inputData) := inputData
  outputData := child(_.outputData)
  if late then
    child.param(_.width, width)
    child.param(_.count, count)
