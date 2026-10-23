package nodal.internal.testkit
import nodal.*
import nodal_external.Increment58DeferredArithmeticLeaf
final class DeferredJoinPoisonedSecond extends Module:
  var trueCalls = 0
  var falseCalls = 0
  val width = param(4.integer, range = 2 to 8)
  val mode = param(0.integer, range = 0 to 1)
  val clock = ClockDomain.external(
    "clock",
    ClockEdge.Rising,
    ResetPolicy.None,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val input = in(UInt(width))
  val child = instance(new Increment58DeferredArithmeticLeaf).domain(clock)
  val selected = child(_.output)
  val predicate = ConstructionKernel.staticPredicate("eq", Vector(mode, 0.integer))
  val ignored = scala.util.Try(ConstructionKernel.structuralIf(predicate, true)(
    () => { trueCalls += 1; Some(~selected) },
    () => { falseCalls += 1; throw new IllegalArgumentException("poisoned second yield") }
  ))
  child(_.input) := input
  child.param(_.width, width)
final class DeferredJoinForeignYield extends Module:
  val width = param(4.integer, range = 2 to 8)
  val mode = param(0.integer, range = 0 to 1)
  val clock = ClockDomain.external(
    "clock",
    ClockEdge.Rising,
    ResetPolicy.None,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val input = in(UInt(width))
  val child = instance(new Increment58DeferredArithmeticLeaf).domain(clock)
  val selected = child(_.output)
  private var escaped: Expr[UInt] = selected
  val predicate = ConstructionKernel.staticPredicate("eq", Vector(mode, 0.integer))
  val result = ConstructionKernel.structuralIf(predicate, true)(
    () => { escaped = ~selected; Some(escaped) },
    () => Some(escaped)
  )
  child(_.input) := input
  child.param(_.width, width)

import nodal.internal.bridge.*
import nodal_external.*
import utest.*

object Increment58DeferredJoinTests extends TestSuite:
  private def capture(mode: String, late: Boolean, concrete: Boolean = false) =
    ConstructionKernel.inspect(new Increment58DeferredJoinSource(late, concrete, mode))
  private def equivalent(mode: String): Unit =
    for concrete <- Vector(false, true) do
      val early = ScalaToMlirBridge.fromSnapshot(capture(mode, false, concrete))
      val late = ScalaToMlirBridge.fromSnapshot(capture(mode, true, concrete))
      assert(early.text == late.text)
  val tests = Tests:
    test("direct structural results seal exact early and late parent types"):
      equivalent("direct")
    test("nested joins preserve their exact inner and outer yielded edges"):
      equivalent("nested")
      val snapshot = capture("nested", true)
      assert(snapshot.structuralIfs.size == 2)
    test("join in a behavioral arm feeds explicitly typed state"):
      equivalent("when")
    test("generated join stays within its actual family and behavioral scope"):
      equivalent("family")
    test("equal kind or equal default does not authorize differing complete branch ABI"):
      for concrete <- Vector(false, true); late <- Vector(false, true) do
        val failure = scala.util.Try(capture("mismatch", late, concrete)).failed.get
        assert(failure.isInstanceOf[ConstructionException])
        assert(failure.getMessage.contains("same complete hardware ABI"))
    test("bridge independently rejects an opposite arm yielded identity"):
      val snapshot = capture("direct", true)
      val actual = snapshot.structuralIfs.head
      val forged = snapshot.copy(structuralIfs =
        Vector(actual.copy(
          trueArm = actual.trueArm.copy(yieldValue = actual.falseArm.yieldValue)
        ))
      )
      val failure = scala.util.Try(ScalaToMlirBridge.fromSnapshot(forged)).failed.get
      assert(failure.isInstanceOf[BridgeException])
      assert(failure.getMessage.contains("scope"))
    test("captured second arm failure poisons once and arm values cannot escape"):
      var observed: Option[DeferredJoinPoisonedSecond] = None
      val poisoned = scala.util.Try(ConstructionKernel.inspect {
        val module = new DeferredJoinPoisonedSecond
        observed = Some(module)
        module
      }).failed.get
      assert(observed.exists(module => module.trueCalls == 1 && module.falseCalls == 1))
      assert(
        poisoned.getMessage.contains("captured constructor failure invalidated this transaction")
      )
      val escaped =
        scala.util.Try(ConstructionKernel.inspect(new DeferredJoinForeignYield)).failed.get
      assert(escaped.getMessage.contains("escaped its lexical region"))
    test("pure collection and procedural contexts cannot borrow join finalization"):
      for mode <- Vector("collection", "procedural") do
        val failure = scala.util.Try(ConstructionKernel.inspect(
          new Increment58DeferredJoinForbidden(mode)
        )).failed.get
        assert(failure.isInstanceOf[ConstructionException])
        assert(failure.getMessage.contains(
          "deferred value requires actual structural or behavioral lexical ancestry"
        ))
