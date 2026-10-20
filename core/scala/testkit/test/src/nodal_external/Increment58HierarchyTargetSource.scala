package nodal_external

import nodal.*

/** One reusable storage definition; each instance must retain independent live state. */
final class Increment58HierarchyTargetLeaf(initialized: Boolean = true) extends Module:
  val required = ClockDomain.required("required")
  var inputPort = Option.empty[Signal[Bool]]
  var outputPort = Option.empty[Signal[Bool]]
  required:
    val inputData = in(Bool)
    val outputData = out(Bool)
    val stored = if initialized then Reg(false.B) else Reg.uninitialized(Bool)
    stored := inputData
    outputData := stored
    inputPort = Some(inputData)
    outputPort = Some(outputData)

/** Forwarded formal capabilities do not introduce another register or physical clock. */
final class Increment58HierarchyTargetForward extends Module:
  val required = ClockDomain.required("required")
  val inputData = in(Bool)
  val outputData = out(Bool)
  val leaf = instance(new Increment58HierarchyTargetLeaf).domain(required)
  leaf(_.inputPort.get) := inputData
  outputData := leaf(_.outputPort.get)

final class Increment58HierarchyTargetSource(profile: String) extends Module:
  val first = ClockDomain.external(
    "first",
    ClockEdge.Rising,
    ResetPolicy.Sync,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val second = ClockDomain.external(
    "second",
    if profile == "material" then ClockEdge.Falling else ClockEdge.Rising,
    if profile == "resetless" then ResetPolicy.None else ResetPolicy.Sync,
    if profile == "material" then ResetPolarity.ActiveLow else ResetPolarity.ActiveHigh,
    25.MHz
  )
  first:
    val firstData = in(Bool)
    val firstResult = out(Bool)
    val firstChild = instance(new Increment58HierarchyTargetLeaf).domain(first)
    firstChild(_.inputPort.get) := firstData
    firstResult := firstChild(_.outputPort.get)
  second:
    val secondData = in(Bool)
    val secondResult = out(Bool)
    if profile == "nested" then
      val secondChild = instance(new Increment58HierarchyTargetForward).domain(_.required, second)
      secondChild(_.inputData) := secondData
      secondResult := secondChild(_.outputData)
    else
      val secondChild =
        instance(new Increment58HierarchyTargetLeaf(profile != "resetless"))
          .domain(_.required, second)
      secondChild(_.inputPort.get) := secondData
      secondResult := secondChild(_.outputPort.get)
