package nodal.internal.testkit

import nodal.*
import nodal.internal.bridge.*
import nodal_external.*
import utest.*

final class OwnedClockCallbackCapture(caught: Boolean) extends Module:
  val root = ClockDomain.external(
    "root",
    ClockEdge.Rising,
    ResetPolicy.None,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val values = in(Vec(Bool, 1))
  val _ = ConstructionKernel.collection("map", Vector(values), None): arguments =>
    if caught then
      try { val _ = ClockGate(root, true.B) }
      catch case _: Throwable => ()
    else
      val _ = ClockGate(root, true.B)
    arguments.head

object Increment57OwnedClockTests extends TestSuite:
  private def capture(kind: String = "gate", resetless: Boolean = false) =
    ConstructionKernel.inspect(new Increment57OwnedClockSource(
      kind,
      ClockEdge.Rising,
      ResetPolarity.ActiveHigh,
      resetless
    ))
  private def rejects(snapshot: ConstructionSnapshot): Unit =
    assert(scala.util.Try(ScalaToMlirBridge.fromSnapshot(snapshot)).isFailure)
  val tests = Tests:
    test("gate owns actual clock and independent output release"):
      val s = capture()
      val gate = s.digitalExpressions.find(_.clock.nonEmpty).get
      assert(gate.operands.size == 3, gate.dataType == "Clock")
      assert(s.digitalExpressions.count(_.reset.exists(_.kind == "sync")) == 2)
      val text = ScalaToMlirBridge.fromSnapshot(s).text
      assert(text.contains("nodal.clock_gate"), text.contains("gate_v1"))
      assert(s.modules.head.declarations.count(_.dataType.contains("Clock")) == 2)
    test("resetless gate adds no invented reset or initialization"):
      val s = capture(resetless = true)
      assert(!s.digitalExpressions.exists(_.reset.nonEmpty))
      assert(ScalaToMlirBridge.fromSnapshot(s).text.contains("nodal.clock_gate"))
    test("mux retains both parents and one common raw assertion"):
      val s = capture("mux")
      val mux = s.digitalExpressions.find(_.clock.nonEmpty).get
      assert(mux.operands.size == 4, mux.clock.get.parents.size == 2)
      assert(s.digitalExpressions.count(_.reset.exists(_.kind == "sync")) == 3)
      val text = ScalaToMlirBridge.fromSnapshot(s).text
      assert(text.contains("nodal.clock_mux"), text.contains("second_parent"))
      assert(text.contains("except_reset_assertion"))
    test("both edges and polarities preserve source contracts"):
      for
        edge <- Vector(ClockEdge.Rising, ClockEdge.Falling)
        polarity <- Vector(ResetPolarity.ActiveHigh, ResetPolarity.ActiveLow)
        kind <- Vector("gate", "mux")
      do
        val s = ConstructionKernel.inspect(new Increment57OwnedClockSource(kind, edge, polarity))
        assert(ScalaToMlirBridge.fromSnapshot(s).text.contains("nodal.clock_" + kind))
    test("nested gates and shaped parents remain actual distinct producers"):
      for module <- Vector(
          () =>
            new Increment57OwnedClockSource(
              "mux",
              ClockEdge.Rising,
              ResetPolarity.ActiveHigh,
              nested = true
            ),
          () => new Increment57ShapedOwnedClockSource
        )
      do
        assert(ScalaToMlirBridge.fromSnapshot(
          ConstructionKernel.inspect(module())
        ).text.contains("nodal.clock_mux"))
    test("source rejects parent arity identity edge roots and missing reset"):
      for mode <- Vector("one", "three", "alias", "edge", "roots", "resetless") do
        assert(scala.util.Try(
          ConstructionKernel.inspect(new Increment57ClockNegativeSource(mode))
        ).isFailure)
    test("source rejects mixed Pulse and unrelated gate control"):
      for mode <- Vector("mixed", "pulse", "gate_foreign") do
        assert(scala.util.Try(
          ConstructionKernel.inspect(new Increment57ClockNegativeSource(mode))
        ).isFailure)
    test("caught failures and callback effects poison construction"):
      assert(scala.util.Try(
        ConstructionKernel.inspect(new Increment57ClockNegativeSource("caught"))
      ).isFailure)
      for caught <- Vector(false, true) do
        assert(scala.util.Try(
          ConstructionKernel.inspect(new OwnedClockCallbackCapture(caught))
        ).isFailure)
    test("control provenance remains constant external or actual parent"):
      for (mode, expected) <-
          Vector("constant" -> "constant", "external" -> "external", "parent" -> "domain")
      do
        val s = ConstructionKernel.inspect(new Increment57ClockProvenanceSource(mode))
        assert(s.digitalExpressions.find(_.clock.nonEmpty).get.clock.get.controlKinds.head ==
          expected)
        assert(ScalaToMlirBridge.fromSnapshot(s).text.contains("nodal.clock_gate"))
    test("external controls do not acquire inferred domain authority"):
      for mode <- Vector("multiple_external", "mixed_external", "unsafe_data") do
        assert(scala.util.Try(
          ConstructionKernel.inspect(new Increment57ClockProvenanceSource(mode))
        ).isFailure)
    test("identical gates remain independently owned"):
      val s = ConstructionKernel.inspect(new Increment57ClockProvenanceSource("parent"))
      val gates = s.digitalExpressions.filter(_.clock.nonEmpty)
      assert(gates.size == 2, gates.map(_.path).distinct.size == 2)
      assert(gates.map(_.clock.get.destination).distinct.size == 2)
      assert(s.digitalExpressions.count(_.reset.exists(_.kind == "sync")) == 3)
    test("forged schema and actual operand contracts reject"):
      val s = capture("mux")
      val e = s.digitalExpressions.find(_.clock.nonEmpty).get
      val c = e.clock.get
      for changed <- Vector(
          e.copy(clock = None),
          e.copy(dataType = "Bool"),
          e.copy(region = Some("foreign")),
          e.copy(clock = Some(c.copy(contract = "gate_v1"))),
          e.copy(clock = Some(c.copy(parents = c.parents.reverse))),
          e.copy(clock = Some(c.copy(edge = "falling"))),
          e.copy(clock = Some(c.copy(obligations = Vector.empty))),
          e.copy(operands = e.operands.updated(1, e.operands.head)),
          e.copy(operands = e.operands.updated(0, e.path))
        )
      do
        rejects(s.copy(digitalExpressions =
          s.digitalExpressions.map(x => if x.path == e.path then changed else x)
        ))
    test("resetless mux aliases cannot bypass reset-time protection"):
      val s = capture("mux")
      val e = s.digitalExpressions.find(_.clock.nonEmpty).get
      val m = s.modules.head
      val original = m.domains.find(_.path == e.clock.get.destination).get
      val alias = original.copy(
        path = m.path + ".alias",
        name = "alias",
        kind = "bound",
        resetPolicy = Some("none"),
        attributes = original.attributes.filterNot(_._1 == "effective_reset_source")
      )
      rejects(s.copy(modules =
        s.modules.map(x => if x.path == m.path then x.copy(domains = x.domains :+ alias) else x)
      ))
