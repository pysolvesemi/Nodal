package nodal_external

import nodal.*

final class Increment58SelectedInputLeaf extends Module:
  val required = ClockDomain.required("required")
  val input = in(UInt(8))
  val output = out(UInt(8))
  output := input

/** No generated-body input connection: both physical lanes are driven after selection. */
final class Increment58SelectedInputTwo(mode: String = "two") extends Module:
  val core = ClockDomain.external(
    "core", ClockEdge.Rising, ResetPolicy.None, ResetPolarity.ActiveHigh, 100.MHz
  )
  val first = in(UInt(8))
  val second = in(UInt(8))
  val left = out(UInt(8))
  val right = out(UInt(8))
  val lanes =
    for _lane <- hdlRange(0, 2) yield new Area:
      val _ = _lane
      val child = instance(new Increment58SelectedInputLeaf).domain(core)
  val a = lanes(0).child
  val b = lanes(1).child
  a(_.input) := first
  if mode != "missing" then b(_.input) := second
  if mode == "duplicate-alias" then
    val alias = lanes(0.integer).child
    alias(_.input) := second
  if mode == "output-write" then a(_.output) := first
  left := a(_.output)
  right := b(_.output)

/** A scalar Bool boundary makes tuple-specific parent drivers directly observable. */
final class Increment58SelectedInputBoolLeaf extends Module:
  val required = ClockDomain.required("required")
  val input = in(Bool)
  val output = out(Bool)
  output := input

/** Two independent literal axes; every child input is connected only after selection. */
final class Increment58SelectedInputNestedTwo(mode: String = "nested-two") extends Module:
  val core = ClockDomain.external(
    "core", ClockEdge.Rising, ResetPolicy.None, ResetPolarity.ActiveHigh, 100.MHz
  )
  val foreign = ClockDomain.external(
    "foreign", ClockEdge.Rising, ResetPolicy.None, ResetPolarity.ActiveHigh, 100.MHz
  )
  val outerLower = if mode == "nested-nonunit" then 2 else 0
  val outerLimit = if mode == "nested-nonunit" then 6 else 2
  val outerStep = if mode == "nested-nonunit" then 2 else 1
  val innerLower = if mode == "nested-nonunit" then 3 else 0
  val innerLimit = if mode == "nested-nonunit" then 9 else 2
  val innerStep = if mode == "nested-nonunit" then 3 else 1
  val source = if mode == "nested-foreign" then foreign else core
  source:
    val fourth = in(Bool)
    core:
      val first = in(Bool)
      val second = in(Bool)
      val third = in(Bool)
      val output00 = out(Bool)
      val output01 = out(Bool)
      val output10 = out(Bool)
      val output11 = out(Bool)
      val outer =
        for _row <- hdlRange(outerLower, outerLimit, outerStep) yield new Area:
          val _ = _row
          val inner =
            for _column <- hdlRange(innerLower, innerLimit, innerStep) yield new Area:
              val _ = _column
              val child = instance(new Increment58SelectedInputBoolLeaf).domain(core)
      val lanes00 = outer(0).inner(0).child
      val lanes01 = outer(0).inner(1).child
      val lanes10 = outer(1).inner(0).child
      val lanes11 = outer(1).inner(1).child
      if mode == "nested-reverse" then
        lanes11(_.input) := fourth
        lanes10(_.input) := third
        lanes01(_.input) := second
        lanes00(_.input) := first
      else
        lanes00(_.input) := first
        lanes01(_.input) := second
        lanes10(_.input) := third
        if mode != "nested-missing" then lanes11(_.input) := fourth
      if mode == "nested-duplicate-alias" then
        val alias = outer(0.integer).inner(1.integer).child
        alias(_.input) := third
      output00 := lanes00(_.output)
      output01 := lanes01(_.output)
      output10 := lanes10(_.output)
      output11 := lanes11(_.output)

/** A discarded runtime expression is still owned by the outer generated body. */
final class Increment58SelectedInputOuterBool extends Module:
  val core = ClockDomain.external(
    "core", ClockEdge.Rising, ResetPolicy.None, ResetPolarity.ActiveHigh, 100.MHz
  )
  core:
    val first = in(Bool)
    val second = in(Bool)
    val third = in(Bool)
    val fourth = in(Bool)
    val output00 = out(Bool)
    val output01 = out(Bool)
    val output10 = out(Bool)
    val output11 = out(Bool)
    val outer =
      for _row <- hdlRange(0, 2) yield new Area:
        val _ = _row
        val _ = first && second
        val inner =
          for _column <- hdlRange(0, 2) yield new Area:
            val _ = _column
            val child = instance(new Increment58SelectedInputBoolLeaf).domain(core)
    val lanes00 = outer(0).inner(0).child
    val lanes01 = outer(0).inner(1).child
    val lanes10 = outer(1).inner(0).child
    val lanes11 = outer(1).inner(1).child
    lanes00(_.input) := first
    lanes01(_.input) := second
    lanes10(_.input) := third
    lanes11(_.input) := fourth
    output00 := lanes00(_.output)
    output01 := lanes01(_.output)
    output10 := lanes10(_.output)
    output11 := lanes11(_.output)
