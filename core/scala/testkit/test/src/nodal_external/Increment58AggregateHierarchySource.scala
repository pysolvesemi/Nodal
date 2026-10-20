package nodal_external

import nodal.*

final class Increment58AggregateHierarchyLeaf extends Module:
  val required = ClockDomain.required("required")
  val width: Param[Integer] = param(5.integer, range = 3 to 9)
  val count: Param[Integer] = param(2.integer, range = 1 to 4)
  val inner = Struct(
    "HierarchyInner",
    StructField("tag", Bits(3)),
    StructField("samples", Vec(UInt(width), count))
  )
  val payload = Struct(
    "HierarchyPayload",
    StructField("ready", Bool),
    StructField("nested", Vec(inner, 2)),
    StructField("signed", SInt(width))
  )
  val inputData = in(payload)
  val outputData = out(payload)
  outputData := inputData
  val readyValue = out(Bool)
  val signedValue = out(SInt(width))
  readyValue := inputData.field(payload.member(StructField("ready", Bool)))
  signedValue := inputData.field(payload.member(StructField("signed", SInt(width))))

final class Increment58AggregateHierarchySource(late: Boolean = true) extends Module:
  val core = ClockDomain.external(
    "core",
    ClockEdge.Rising,
    ResetPolicy.None,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val width: Param[Integer] = param(4.integer, range = 2 to 8)
  val count: Param[Integer] = param(2.integer, range = 1 to 4)
  val actualWidth = width + 1.integer
  val inner = Struct(
    "HierarchyInner",
    StructField("tag", Bits(3)),
    StructField("samples", Vec(UInt(actualWidth), count))
  )
  val payload = Struct(
    "HierarchyPayload",
    StructField("ready", Bool),
    StructField("nested", Vec(inner, 2)),
    StructField("signed", SInt(actualWidth))
  )
  val inputData = in(payload)
  val outputData = out(payload)
  val readyValue = out(Bool)
  val signedValue = out(SInt(actualWidth))
  val child = instance(new Increment58AggregateHierarchyLeaf).domain(core)
  if !late then
    child.param(_.width, actualWidth)
    child.param(_.count, count)
  child(_.inputData) := inputData
  outputData := child(_.outputData)
  readyValue := child(_.readyValue)
  signedValue := child(_.signedValue)
  if late then
    child.param(_.width, actualWidth)
    child.param(_.count, count)
