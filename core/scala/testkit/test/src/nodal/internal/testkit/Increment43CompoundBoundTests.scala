package nodal.internal.testkit

import nodal.*
import nodal.internal.bridge.*

import utest.*

final class Increment43CompoundCapture(onBody: () => Unit, initialLanes: Int) extends Module:
  val start: Param[Integer] = param(0.integer, range = 0 to 2)
  val lanes: Param[Integer] = param(initialLanes.integer, range = 2 to 5)
  val offset: Param[Integer] = param(1.integer, range = 1 to 3)
  val stride: Param[Integer] = param(1.integer, range = 1 to 2)
  val limit: Expr[Integer] = lanes * 2.integer + offset
  val increment: Expr[Integer] = stride + 1.integer
  val unrelated: Expr[Integer] = lanes + 99.integer

  hdlRange(start, limit, increment): _ =>
    onBody()
    val tap = node(Electrical)
    val _ = tap
    ()

final class Increment43RepeatedCompound(onBody: () => Unit) extends Module:
  val lanes: Param[Integer] = param(2.integer, range = 1 to 4)
  val limit: Expr[Integer] = lanes + 1.integer

  hdlRange(0, limit): _ =>
    onBody()
    val first = node(Electrical)
    val _ = first
    ()

  hdlRange(0, limit): _ =>
    onBody()
    hdlRange(0, limit): _ =>
      onBody()
      val nested = node(Electrical)
      val _ = nested
      ()

final class Increment43SignedCompound(onBody: () => Unit) extends Module:
  val count: Param[Integer] = param(3.integer, range = 2 to 4)
  val lower: Expr[Integer] = -count
  val upper: Expr[Integer] = (count * 3.integer - 1.integer) / 2.integer

  hdlRange(lower, upper): _ =>
    onBody()
    val tap = node(Electrical)
    val _ = tap
    ()

final class Increment43CompoundEmpty(onBody: () => Unit) extends Module:
  val count: Param[Integer] = param(2.integer, range = 1 to 4)
  val bound: Expr[Integer] = count + 1.integer

  hdlRange(bound, bound): _ =>
    onBody()
    ()

final class Increment43CompoundInvalid(form: Int, onBody: () => Unit) extends Module:
  val count: Param[Integer] = param(3.integer, range = 2 to 4)
  val wide: Param[Integer] = param(1.integer, range = 1 to Int.MaxValue)
  val upper: Expr[Integer] = form match
    case 0 => 10.integer / (count - 2.integer)
    case 1 => (wide * wide) * wide
    case 2 => wide + 1.integer
    case 3 => count - 3.integer
    case 4 => count + 1.integer
    case 5 => (count / (count - 2.integer)) - (count / (count - 2.integer))
    case _ => count + 1.integer
  val step: Expr[Integer] = if form == 6 then count - 2.integer else 1.integer

  hdlRange(0, upper, step, if form == 4 then 2 else Int.MaxValue): _ =>
    onBody()
    ()

final class Increment43CompoundIdentity(same: Boolean, onBody: () => Unit) extends Module:
  val first: Param[Integer] = param(1.integer, range = 0 to 4)
  val second: Param[Integer] = param(1.integer, range = 0 to 4)
  val difference: Expr[Integer] = if same then first - first else first - second

  hdlRange(0, difference): _ =>
    onBody()
    ()

final class Increment43CompoundDepth(depth: Int, shared: Boolean, onBody: () => Unit)
    extends Module:
  val bound: Expr[Integer] = (0 until depth).foldLeft(0.integer): (previous, _) =>
    if shared then previous + previous else -previous

  hdlRange(0, bound): _ =>
    onBody()
    ()

final class Increment43CompoundForeignChild extends Module:
  val count: Param[Integer] = param(2.integer, range = 1 to 4)
  val bound: Expr[Integer] = count + 1.integer

final class Increment43CompoundForeign(onBody: () => Unit) extends Module:
  val child = new Increment43CompoundForeignChild

  hdlRange(0, child.bound): _ =>
    onBody()
    ()

final class Increment43PassedCompound(bound: Expr[Integer], onBody: () => Unit) extends Module:
  hdlRange(0, bound): _ =>
    onBody()
    ()

final class Increment43MalformedCompound(form: Int, onBody: () => Unit) extends Module:
  val count: Param[Integer] = param(2.integer, range = 1 to 4)
  val dynamic: Signal[Integer] = wire(Integer)
  val bound: Expr[Integer] =
    if form == 0 then dynamic + 1.integer
    else
      val expression = new KernelExpr[Integer](
        if form == 1 then Vector(count) else Vector(count, 1.integer),
        resultType = Some(KernelTypeDescriptor(if form == 2 then "Real" else "Integer")),
        operation = Some(if form == 3 then "unknown_integer_operation" else "analog_add")
      )
      ConstructionKernel.expression(expression)
      expression

  hdlRange(0, bound): _ =>
    onBody()
    ()

object Increment43CompoundBoundTests extends TestSuite:
  private def inspect(onBody: () => Unit, initialLanes: Int = 2): ConstructionSnapshot =
    ConstructionKernel.inspect(new Increment43CompoundCapture(onBody, initialLanes))

  private def failure(top: => Module, code: String): Unit =
    val error = scala.util.Try(ConstructionKernel.inspect(top))
      .failed.get.asInstanceOf[ConstructionException]
    assert(error.diagnostic.code == code)

  val tests: Tests = Tests:
    test("compound bounds capture once and retain canonical expressions rather than defaults"):
      var calls = 0
      val snapshot = inspect(() => calls += 1)
      assert(calls == 1)
      val region = snapshot.generatedRegions.head
      assert(snapshot.generatedRegions.size == 1)
      assert(region.maximumTripCount == 7)
      assert(region.declarations.size == 1)
      val expressions = snapshot.parameterExpressions
      val paths = expressions.map(_.path).toSet
      assert(paths.contains(region.upperExclusive), paths.contains(region.step))
      assert(expressions.size == 5)
      assert(expressions.count(_.operation == "analog_add") == 2)
      assert(expressions.count(_.operation == "analog_mul") == 1)
      assert(!expressions.exists(_.literal.contains("99")))
      assert(expressions.forall(_.owner == snapshot.root))
      val module = snapshot.modules.find(_.path == snapshot.root).get
      val parameters = module.declarations.filter(_.kind == "parameter")
      val expectedParameters = Set("start", "lanes", "offset", "stride")
      assert(parameters.map(_.name).toSet == expectedParameters)
      assert(parameters.forall(parameter =>
        parameter.attributes.toMap.get("classification").contains("structural")
      ))
      assert(parameters.forall(parameter =>
        parameter.attributes.toMap.get("structural_effects").contains("topology")
      ))
      val references = paths ++ parameters.map(_.path)
      assert(expressions.flatMap(_.operands).forall(references.contains))

    test("default changes retain the same conservative maximum and expression structure"):
      val first = inspect(() => (), 2)
      val second = inspect(() => (), 5)
      assert(first.generatedRegions == second.generatedRegions)
      assert(first.parameterExpressions == second.parameterExpressions)
      assert(first.modules != second.modules)

    test("repeated nested regions share source expression identity without host lane expansion"):
      var calls = 0
      val first = ConstructionKernel.inspect(new Increment43RepeatedCompound(() => calls += 1))
      val second = ConstructionKernel.inspect(new Increment43RepeatedCompound(() => ()))
      assert(calls == 3)
      assert(first == second)
      assert(first.generatedRegions.size == 3)
      assert(first.generatedRegions.count(_.parent.nonEmpty) == 1)
      assert(first.generatedRegions.forall(_.maximumTripCount == 5))
      assert(first.generatedRegions.flatMap(_.declarations).distinct.size == 2)
      assert(first.parameterExpressions.size == 2)
      assert(first.generatedRegions.map(_.upperExclusive).distinct.size == 1)

    test("signed negation multiplication subtraction and truncating division retain their DAG"):
      var calls = 0
      val snapshot = ConstructionKernel.inspect(new Increment43SignedCompound(() => calls += 1))
      assert(calls == 1)
      assert(snapshot.generatedRegions.head.maximumTripCount == 9)
      val operations = snapshot.parameterExpressions.map(_.operation).toSet
      assert(Set("analog_neg", "analog_mul", "analog_sub", "analog_div").subsetOf(operations))
      assert(snapshot.parameterExpressions.flatMap(_.literal).toSet == Set("1", "2", "3"))

    test("an identical compound endpoint is empty but its body is captured exactly once"):
      var calls = 0
      val snapshot = ConstructionKernel.inspect(new Increment43CompoundEmpty(() => calls += 1))
      val region = snapshot.generatedRegions.head
      assert(calls == 1, region.maximumTripCount == 0)
      assert(region.lower == region.upperExclusive)
      assert(snapshot.parameterExpressions.size == 2)

    test("invalid whole-domain arithmetic count direction and maxima reject before body effects"):
      for (form, code) <- Vector(
          0 -> "NODAL-ITERATION-043-001",
          1 -> "NODAL-ITERATION-043-001",
          2 -> "NODAL-ITERATION-043-001",
          3 -> "NODAL-ITERATION-043-003",
          4 -> "NODAL-ITERATION-043-001",
          5 -> "NODAL-ITERATION-043-001",
          6 -> "NODAL-ITERATION-043-002"
        )
      do
        var calls = 0
        failure(new Increment43CompoundInvalid(form, () => calls += 1), code)
        assert(calls == 0)
      assert(inspect(() => ()).generatedRegions.head.maximumTripCount == 7)

    test("only the same captured operand proves subtraction correlation"):
      var calls = 0
      val same = ConstructionKernel.inspect(
        new Increment43CompoundIdentity(true, () => calls += 1)
      )
      assert(calls == 1, same.generatedRegions.head.maximumTripCount == 0)
      failure(
        new Increment43CompoundIdentity(false, () => calls += 1),
        "NODAL-ITERATION-043-003"
      )
      assert(calls == 1)

    test("shared source DAGs stay linear and over-depth expressions fail before body effects"):
      var calls = 0
      val snapshot = ConstructionKernel.inspect(
        new Increment43CompoundDepth(200, true, () => calls += 1)
      )
      assert(calls == 1)
      assert(snapshot.parameterExpressions.size == 201)
      assert(snapshot.generatedRegions.head.maximumTripCount == 0)
      failure(
        new Increment43CompoundDepth(600, false, () => calls += 1),
        "NODAL-ITERATION-043-001"
      )
      assert(calls == 1)

    test("foreign and uncaptured compound expressions cannot acquire ownership from their use"):
      var calls = 0
      failure(new Increment43CompoundForeign(() => calls += 1), "NODAL-ITERATION-043-001")
      val unowned = 1.integer + 2.integer
      failure(new Increment43PassedCompound(unowned, () => calls += 1), "NODAL-ITERATION-043-001")
      assert(calls == 0)

    test("runtime operands wrong types unknown operators and malformed arities fail closed"):
      var calls = 0
      for form <- 0 to 3 do
        failure(new Increment43MalformedCompound(form, () => calls += 1), "NODAL-ITERATION-043-001")
      assert(calls == 0)

    test("the not-yet-implemented native compound transport remains explicitly rejected"):
      val snapshot = inspect(() => ())
      val error = scala.util.Try(ScalaToMlirBridge.fromSnapshot(snapshot))
        .failed.get.asInstanceOf[BridgeException]
      assert(error.diagnostic.code == "NODAL-BRIDGE-043")
      val legacy = ScalaToMlirBridge.lower(new Increment43SymbolicGeneratedNode)
      assert(legacy.text.contains("upper = @lanes"))
      assert(legacy.text.contains("maximum_trip_count = 4 : i64"))
