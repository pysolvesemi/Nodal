package nodal.behavioral.fixture
import nodal.*
final class UncheckedGuard extends Module:
  val flag = in(Bool)
  Uninstrumented.capture(flag)
final class UncheckedDirectGuard extends Module:
  val flag = in(Bool)
  Uninstrumented.direct(flag)
final class WrongProfileGuard extends Module:
  val flag = in(Bool)
  Uninstrumented.wrongProfile(flag)
final class CheckedGuard extends Module:
  val MODE = param(0.integer)
  val flag = in(Bool)
  val input = in(UInt(8))
  val root = ClockDomain.external(
    "root",
    ClockEdge.Rising,
    ResetPolicy.Sync,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  root {
    val state = Reg(0.U(8))
    val nestedState = Reg(0.U(8))
    when((MODE == 0) && flag) { state := input }
    elsewhen(flag) { state := 0.U(8) }
    otherwise { state := 1.U(8) }
    when(flag) {
      when(MODE != 0) { nestedState := input }
    }
  }
final class CheckedStructuralValueGuard extends Module:
  val MODE = param(0.integer)
  val flag = in(Bool)
  val input = in(UInt(8))
  val root = ClockDomain.external(
    "root",
    ClockEdge.Rising,
    ResetPolicy.Sync,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  root {
    val state = Reg(0.U(8))
    when(flag) {
      val selected: Expr[UInt] = if MODE == 0 then input else 0.U(8)
      state := selected
    }
  }
final class HostControlModule extends Module:
  var left = 0
  var right = 0
  val choose = true
  if choose then left += 1 else right += 1
  assert(left == 1 && right == 0)
object RuntimeProbe:
  def main(arguments: Array[String]): Unit =
    val _ = arguments
    val failure = scala.util.Try(ConstructionKernel.inspect(new UncheckedGuard)).failed.get
    assert(failure.asInstanceOf[ConstructionException].diagnostic.code ==
      "NODAL-SEQUENTIAL-056-MISSING")
    List(() => new UncheckedDirectGuard, () => new WrongProfileGuard).foreach: construct =>
      val rejected = scala.util.Try(ConstructionKernel.inspect(construct())).failed.get
      assert(rejected.asInstanceOf[ConstructionException].diagnostic.code ==
        "NODAL-SEQUENTIAL-056-MISSING")
    assert(Uninstrumented.calls == 0)
    val snapshot = ConstructionKernel.inspect(new CheckedGuard)
    assert(snapshot.structuralIfs.isEmpty)
    assert(snapshot.behavioralIfs.size == 4)
    snapshot.behavioralIfs.foreach: branch =>
      List(branch.path, branch.predicate).foreach: path =>
        val source = snapshot.sourceMap.find(_.semanticPath == path).get.source
        assert(source.path.endsWith("RuntimeProbe.scala") && source.line > 0)
    assert(snapshot.modules.flatMap(_.declarations).count(_.kind == "register") == 2)
    val parameter = snapshot.modules.flatMap(_.declarations).find(_.name == "MODE").get
    assert(!parameter.attributes.toMap.contains("classification"))
    assert(snapshot.staticPredicates.map(_.operation).toSet == Set("eq", "ne"))
    assert(snapshot.digitalExpressions.exists(_.operation == "bool_and"))
    assert(snapshot.digitalExpressions.count(_.operation == "behavioral_predicate") == 2)
    val structural = ConstructionKernel.inspect(new CheckedStructuralValueGuard)
    assert(structural.structuralIfs.size == 1 && structural.behavioralIfs.size == 1)
    assert(structural.structuralIfs.head.parentRegion.nonEmpty)
    ConstructionKernel.inspect(new HostControlModule)
    var left = 0
    var right = 0
    if true then left += 1 else right += 1
    assert(left == 1 && right == 0)
    println(
      "BEHAVIORAL_GUARD_RUNTIME_PASS uninstrumented-rejected-before-callback host-if-preserved"
    )
