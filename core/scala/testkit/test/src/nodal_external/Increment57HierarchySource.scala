package nodal_external

import nodal.*

/** Concrete data ports keep the child's local requirement distinct from each occurrence binding. */
final class Increment57HierarchyLeaf(initialized: Boolean = true) extends Module:
  val required = ClockDomain.required("required")
  var inputPort = Option.empty[Signal[Bool]]
  var outputPort = Option.empty[Signal[Bool]]
  required:
    val input = in(Bool)
    val output = out(Bool)
    val state = if initialized then Reg(false.B) else Reg.uninitialized(Bool)
    state := input
    output := state
    inputPort = Some(input)
    outputPort = Some(output)

final class Increment57HierarchyForward extends Module:
  val required = ClockDomain.required("required")
  val input = in(Bool)
  val output = out(Bool)
  val nested = instance(new Increment57HierarchyLeaf).domain(required)
  nested(_.inputPort.get) := input
  output := nested(_.outputPort.get)

final class Increment57HierarchySource(binding: String = "default", nested: Boolean = false)
    extends Module:
  val root = ClockDomain.external(
    "root",
    ClockEdge.Rising,
    ResetPolicy.AsyncAssertSyncRelease(2),
    ResetPolarity.ActiveLow,
    100.MHz
  )
  var inputValue = Option.empty[Signal[Bool]]
  root:
    val input = in(Bool)
    inputValue = Some(input)
  val output = out(Bool)
  if nested then
    val child = instance(new Increment57HierarchyForward).domain(root)
    child(_.input) := inputValue.get
    output := child(_.output)
  else
    var childValue = Option.empty[Instance[Increment57HierarchyLeaf]]
    if binding == "inherited" then
      root:
        childValue = Some(instance(new Increment57HierarchyLeaf))
    else
      val child = instance(new Increment57HierarchyLeaf)
      if binding == "named" then child.domain(_.required, root)
      else child.domain(root)
      childValue = Some(child)
    val child = childValue.get
    child(_.inputPort.get) := inputValue.get
    output := child(_.outputPort.get)

final class Increment57HierarchyCrossingLeaf extends Module:
  val source = ClockDomain.required("source")
  val destination = ClockDomain.required("destination")
  var inputPort = Option.empty[Signal[Bool]]
  var outputPort = Option.empty[Signal[Bool]]
  source:
    val input = in(Bool)
    inputPort = Some(input)
  destination:
    val output = out(Bool)
    val crossed = Cdc.sync(inputPort.get, to = destination, stages = 2)
    val state = Reg(false.B)
    state := crossed
    output := crossed
    outputPort = Some(output)

final class Increment57HierarchyCrossingSource(reconverge: Boolean = false) extends Module:
  val source = ClockDomain.external(
    "source",
    ClockEdge.Rising,
    ResetPolicy.Sync,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val destination = ClockDomain.external(
    "destination",
    ClockEdge.Falling,
    ResetPolicy.Sync,
    ResetPolarity.ActiveLow,
    20.MHz
  )
  var inputValue = Option.empty[Signal[Bool]]
  source:
    val input = in(Bool)
    inputValue = Some(input)
  val first = instance(new Increment57HierarchyCrossingLeaf)
    .domain(_.source, source).domain(_.destination, destination)
  val second = instance(new Increment57HierarchyCrossingLeaf)
    .domain(_.source, source).domain(_.destination, destination)
  first(_.inputPort.get) := inputValue.get
  second(_.inputPort.get) := inputValue.get
  destination:
    val state = Reg(false.B)
    state :=
      (if reconverge then first(_.outputPort.get) && second(_.outputPort.get)
       else first(_.outputPort.get))
    val output = out(Bool)
    output := state

final class Increment57HierarchyParentCrossingSource extends Module:
  val source = ClockDomain.external(
    "source",
    ClockEdge.Rising,
    ResetPolicy.Sync,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val destination = ClockDomain.external(
    "destination",
    ClockEdge.Falling,
    ResetPolicy.Sync,
    ResetPolarity.ActiveLow,
    20.MHz
  )
  var inputValue = Option.empty[Signal[Bool]]
  source:
    val input = in(Bool)
    inputValue = Some(input)
  val crossed = Cdc.sync(inputValue.get, to = destination, stages = 2)
  val child = instance(new Increment57HierarchyLeaf).domain(destination)
  child(_.inputPort.get) := crossed
  destination:
    val output = out(Bool)
    output := child(_.outputPort.get)
