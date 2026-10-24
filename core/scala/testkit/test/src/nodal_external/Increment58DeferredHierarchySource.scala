package nodal_external

import nodal.*

final class Increment58DeferredHierarchyLeaf extends Module:
  val required = ClockDomain.required("required")
  val formalWidth = param(4.integer, range = 2 to 8)
  val inputData = in(UInt(formalWidth))
  val outputData = out(UInt(formalWidth))
  val payload = Struct("DeferredPayload", StructField("data", UInt(formalWidth)))
  val aggregateInput = in(payload)
  val aggregateOutput = out(payload)
  outputData := inputData
  aggregateOutput := aggregateInput

final class Increment58DeferredHierarchySource(mode: String = "copy", late: Boolean = true)
    extends Module:
  val core = ClockDomain.external(
    "core",
    ClockEdge.Rising,
    ResetPolicy.None,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val actualWidth = param(4.integer, range = 2 to 8)
  val otherWidth = param(4.integer, range = 2 to 8)
  val inputData = in(UInt(actualWidth))
  val otherData = in(UInt(otherWidth))
  val outputData =
    out(if mode == "arithmetic" then UInt(actualWidth + 1.integer) else UInt(actualWidth))
  val condition = in(Bool)
  val payload = Struct("DeferredPayload", StructField("data", UInt(actualWidth)))
  val aggregateInput = in(payload)
  val child = instance(new Increment58DeferredHierarchyLeaf).domain(core)
  if !late then child.param(_.formalWidth, actualWidth)
  val projected = child(_.outputData)
  val result = mode match
    case "copy" => (~projected >> 1).named("copied").keep("finalized child value")
    case "bitwise" => projected & inputData
    case "select" => select(condition, projected, inputData)
    case "field" =>
      child(_.aggregateOutput).field(payload.member(StructField("data", UInt(actualWidth))))
    case "wrong-bitwise" => projected & otherData
    case "wrong-select" => select(condition, projected, otherData)
    case "arithmetic" => projected + inputData
    case _ => projected
  child(_.inputData) := inputData
  child(_.aggregateInput) := aggregateInput
  outputData := result
  if late then child.param(_.formalWidth, actualWidth)
