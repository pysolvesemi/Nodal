package nodal.internal.testkit

import nodal.*
import nodal.internal.bridge.*
import nodal_external.*
import utest.*

object Increment58LexicalDeferredTests extends TestSuite:
  private def capture(mode: String, late: Boolean = true, wrong: Boolean = false) =
    ConstructionKernel.inspect(new Increment58LexicalDeferredSource(mode, late, wrong))
  private def equivalent(mode: String): Unit =
    val early = ScalaToMlirBridge.fromSnapshot(capture(mode, false))
    val late = ScalaToMlirBridge.fromSnapshot(capture(mode))
    assert(early.text == late.text)
  val tests = Tests:
    test("direct module predecessor retains exact early and late types"):
      equivalent("direct")
    test("behavioral capture finalizes once before explicit register assignment"):
      equivalent("when")
      val snapshot = capture("when")
      assert(snapshot.behavioralIfs.head.trueArm.arguments.nonEmpty)
    test("ordinary structural statement preserves parent actual captured type"):
      equivalent("structural")
      val snapshot = capture("structural")
      assert(snapshot.structuralIfs.head.trueArm.arguments.nonEmpty)
    test("generated body and nested when use exact lexical capture edges"):
      equivalent("family")
      val snapshot = capture("family")
      assert(snapshot.generatedRegions.size == 1, snapshot.behavioralIfs.size == 1)
    test("equal default and envelope do not authorize final assignment types"):
      for mode <- Vector("when", "structural", "family"); late <- Vector(false, true) do
        val failure = scala.util.Try(capture(mode, late, true)).failed.get
        assert(failure.isInstanceOf[ConstructionException])
        assert(failure.getMessage.contains("exactly matching recursive types"))
    test("bridge independently rejects replacement by an unrelated child capture"):
      val captured = ConstructionKernel.capture(new Increment58LexicalDeferredForgerySource)(module =>
        Vector(module.selected, module.unrelated))
      val snapshot = captured.snapshot
      val selected = captured.references(0).path
      val foreign = captured.references(1).path
      val actual = snapshot.behavioralIfs.head
      assert(actual.trueArm.captures.contains(selected))
      val arm = actual.trueArm.copy(captures = actual.trueArm.captures.map(value =>
        if value == selected then foreign else value))
      val forged = snapshot.copy(behavioralIfs = Vector(actual.copy(trueArm = arm)))
      val failure = scala.util.Try(ScalaToMlirBridge.fromSnapshot(forged)).failed.get
      assert(failure.isInstanceOf[BridgeException])
      assert(failure.getMessage.contains("capture must preserve its exact complete type"))
    test("pure collections and procedural loops cannot borrow lexical finalization"):
      for mode <- Vector("collection", "procedural") do
        val failure = scala.util.Try(ConstructionKernel.inspect(
          new Increment58LexicalDeferredForbiddenSource(mode))).failed.get
        assert(failure.isInstanceOf[ConstructionException])
        assert(failure.getMessage.contains("deferred value requires actual structural or behavioral lexical ancestry"))
    test("structural result join remains a distinct unsupported continuation"):
      for late <- Vector(false, true) do
        val failure = scala.util.Try(capture("join", late)).failed.get
        assert(failure.isInstanceOf[ConstructionException])
        assert(failure.getMessage.contains("symbolic digital width value must be owned by the active Module"))
