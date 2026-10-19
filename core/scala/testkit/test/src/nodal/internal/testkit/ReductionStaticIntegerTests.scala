package nodal.internal.testkit

import nodal.*
import nodal.internal.bridge.*
import utest.*

final class ReductionStaticIntegerSource(defaultCount: Int) extends Module:
  val count = param(defaultCount.integer, range = 1 to 9)
  val otherCount = param(defaultCount.integer, range = 1 to 9)
  val width = param(4.integer, range = 1 to 9)
  val logarithm = StaticIntegerExpression.ceilLog2(count)
  val otherLogarithm = StaticIntegerExpression.ceilLog2(otherCount)
  val chosen = StaticIntegerExpression.chooseZero(count - 1.integer, width, 8)
  val sameBranches = StaticIntegerExpression.chooseZero(count, width, width)
  val identical = wire(UInt(sameBranches))
  val logarithmic = wire(UInt(StaticIntegerExpression.add64(logarithm, 1)))
  val selected = wire(UInt(chosen))
  val independent = wire(UInt(StaticIntegerExpression.add64(otherLogarithm, 1)))

final class ReductionDerivedIntegerSource extends Module:
  val wide = StaticIntegerExpression.literal64(BigInt(Long.MaxValue))
  val width = StaticIntegerExpression.add64(Int.MaxValue, 1)
  val product = StaticIntegerExpression.multiply64(Int.MaxValue, Int.MaxValue)
  val first = wire(UInt(wide))
  val second = wire(UInt(width))
  val third = wire(UInt(product))
  val negativeTest = wire(UInt(StaticIntegerExpression.chooseZero(
    StaticIntegerExpression.literal64(BigInt(Long.MinValue)),
    -1,
    4
  )))

final class ReductionSignedWidthSource extends Module:
  val minimum = StaticIntegerExpression.literal64(BigInt(Long.MinValue))
  val width = StaticIntegerExpression.chooseZero(minimum, -1, 4)
  val result = wire(UInt(width))

final class ReductionDerivedLoopSource extends Module:
  val bound = StaticIntegerExpression.literal64(BigInt(Int.MaxValue) + 1)
  hdlRange(bound, bound)(_ => ())

final class ReductionInvalidBranchSource(kind: Int) extends Module:
  val count = param(1.integer, range = 1 to 3)
  val invalid = kind match
    case 0 => 1.integer / 0.integer
    case 1 => StaticIntegerExpression.ceilLog2(0)
    case 2 => -1.integer
    case _ =>
      StaticIntegerExpression.add64(StaticIntegerExpression.literal64(BigInt(Long.MaxValue)), 1)
  val test = if kind == 2 then count - 1.integer else 0.integer
  val output = wire(UInt(StaticIntegerExpression.chooseZero(test, 4, invalid)))

object ReductionStaticIntegerTests extends TestSuite:
  import IterationDomain.{Arithmetic, Bounds}

  private def checked(operation: Arithmetic, operands: Bounds*): Bounds =
    IterationDomain.arithmetic(operation, operands.toVector).toOption.get

  private def parameter(owner: String, name: String): KernelDeclarationSnapshot =
    KernelDeclarationSnapshot(
      s"$owner.$name",
      "parameter",
      name,
      Some("Integer"),
      None,
      Vector("default" -> "1", "integer_range_lower" -> "1", "integer_range_upper" -> "8")
    )

  private def instanceFixture: ConstructionSnapshot =
    val parent = KernelModuleSnapshot(
      "P",
      "Parent",
      Vector.empty,
      Vector(parameter("P", "A"), parameter("P", "B")),
      Vector("a" -> "P.A", "b" -> "P.B", "one" -> "1", "three" -> "3").map: (name, actual) =>
        KernelInstanceSnapshot(s"P.$name", "C", None, Vector.empty, Vector("N" -> actual))
    )
    val child = KernelModuleSnapshot(
      "C",
      "Child",
      Vector.empty,
      Vector(
        parameter("C", "N"),
        KernelDeclarationSnapshot(
          "C.port",
          "analog-input",
          "port",
          Some("Real"),
          None,
          Vector.empty,
          Vector("C.choice")
        )
      ),
      Vector.empty
    )
    def expression(name: String, operation: String, operands: String*) =
      KernelParameterExpressionSnapshot(
        s"C.$name",
        "C",
        operation,
        operands.toVector,
        "Integer",
        None,
        None
      )
    ConstructionSnapshot(
      "P",
      modules = Vector(parent, child),
      interfaceAbi = Vector.empty,
      resolvedNets = Vector.empty,
      topology = Vector.empty,
      parameterExpressions = Vector(
        expression("test", "analog_sub", "C.N", "1"),
        expression("log", "static_integer_ceil_log2", "C.N"),
        expression("wide", "analog_add", "C.log", "5"),
        expression("choice", "static_integer_choose_zero", "C.test", "5", "C.wide")
      )
    )

  val tests: Tests = Tests:
    test("ceil_log2 uses exact signed64 integer endpoints and rejects any nonpositive setting"):
      Vector(1L -> 0L, 2L -> 1L, 3L -> 2L, 4L -> 2L, Long.MaxValue -> 63L).foreach:
        (value, expected) =>
          assert(checked(Arithmetic.CeilLog2, Bounds(value, value)) == Bounds(expected, expected))
      assert(checked(Arithmetic.CeilLog2, Bounds(1L, Long.MaxValue)) == Bounds(0L, 63L))
      Vector(Bounds(0L, 0L), Bounds(-1L, -1L), Bounds(0L, 9L), Bounds(Long.MinValue, 1L))
        .foreach: invalid =>
          assert(IterationDomain.arithmetic(Arithmetic.CeilLog2, Vector(invalid)).isLeft)

    test("choose_zero selects only proven reachable intervals and otherwise contains both"):
      val zero = Bounds(2L, 9L)
      val nonzero = Bounds(-7L, 4L)
      assert(checked(Arithmetic.ChooseZero, Bounds(0L, 0L), zero, nonzero) == zero)
      assert(checked(Arithmetic.ChooseZero, Bounds(1L, Long.MaxValue), zero, nonzero) == nonzero)
      assert(checked(Arithmetic.ChooseZero, Bounds(Long.MinValue, -1L), zero, nonzero) == nonzero)
      Vector(Bounds(-1L, 0L), Bounds(0L, 1L), Bounds(-1L, 1L)).foreach: test =>
        assert(checked(Arithmetic.ChooseZero, test, zero, nonzero) == Bounds(-7L, 9L))
      assert(checked(
        Arithmetic.ChooseZero,
        Bounds(-1L, 1L),
        Bounds(Long.MinValue, Long.MinValue),
        Bounds(Long.MaxValue, Long.MaxValue)
      ) ==
        Bounds(Long.MinValue, Long.MaxValue))
      for operation <- Arithmetic.values; arity <- 0 to 4 if arity != operation.arity do
        assert(IterationDomain.arithmetic(operation, Vector.fill(arity)(Bounds(1L, 2L))).isLeft)

    test("compiler-derived literals and arithmetic keep i64 while public literal add stays Int"):
      val captured = ConstructionKernel.capture(new ReductionDerivedIntegerSource): module =>
        assert(
          DigitalScalarContract.widthBounds(CandidateRuntime.typeDescriptor(UInt(module.wide))) ==
            (Long.MaxValue, Long.MaxValue)
        )
        assert(
          DigitalScalarContract.widthBounds(CandidateRuntime.typeDescriptor(UInt(module.width))) ==
            (Int.MaxValue.toLong + 1L, Int.MaxValue.toLong + 1L)
        )
        Vector(module.first, module.second, module.third, module.negativeTest)
      val types = captured.snapshot.modules.flatMap(_.declarations).flatMap(_.dataType)
      assert(types.exists(_.contains(Long.MaxValue.toString)))
      Vector(BigInt(Long.MaxValue) + 1, BigInt(Long.MinValue) - 1).foreach: value =>
        assert(scala.util.Try(StaticIntegerExpression.literal64(value)).isFailure)
      val unowned = StaticIntegerExpression.literal64(BigInt(Long.MaxValue))
      assert(scala.util.Try(DigitalScalarContract.widthBounds(
        CandidateRuntime.typeDescriptor(UInt(unowned))
      )).isFailure)
      assert(scala.util.Try(ConstructionKernel.capture(new ReductionStaticIntegerSource(1)): _ =>
        val _ = DigitalScalarContract.widthBounds(CandidateRuntime.typeDescriptor(UInt(unowned)))
        Vector.empty).isFailure)
      assert(scala.util.Try(StaticIntegerExpression.add(Int.MaxValue, 1)).isFailure)
      assert(StaticIntegerExpression.add(3, 4) == 7)
      assert(scala.util.Try(ConstructionKernel.inspect(new ReductionDerivedLoopSource)).isFailure)
      val text = ScalaToMlirBridge.fromSnapshot(captured.snapshot).text
      assert(
        text.contains(Long.MaxValue.toString),
        text.contains("operator_name = \"choose_zero\"")
      )

    test("default changes do not alter the retained canonical static expression identities"):
      val first = ConstructionKernel.inspect(new ReductionStaticIntegerSource(1))
      val changed = ConstructionKernel.inspect(new ReductionStaticIntegerSource(3))
      assert(first.parameterExpressions == changed.parameterExpressions)
      val logs = first.parameterExpressions.filter(_.operation == "static_integer_ceil_log2")
      val choice = first.parameterExpressions.find(_.operation == "static_integer_choose_zero").get
      val sameBranches =
        first.parameterExpressions.filter(_.operation == "static_integer_choose_zero").last
      assert(StructuralParameterBinding.sameInteger(
        first,
        first.root,
        sameBranches.path,
        s"${first.root}.width"
      ))
      assert(logs.size == 2)
      assert(!StructuralParameterBinding.sameInteger(first, first.root, logs(0).path, logs(1).path))
      assert(!StructuralParameterBinding.sameInteger(
        first,
        first.root,
        choice.path,
        s"${first.root}.width"
      ))
      assert(StructuralParameterBinding.defaultInteger(first, first.root, choice.path) == 4L)
      assert(StructuralParameterBinding.defaultInteger(changed, changed.root, choice.path) == 8L)
      val text = ScalaToMlirBridge.fromSnapshot(first).text
      assert(
        text.contains("operator_name = \"ceil_log2\""),
        text.contains("operator_name = \"choose_zero\"")
      )
      assert(text.contains("(i64, i64, i64) -> i64"))

    test("unreachable invalid branch DAGs reject before choosing a width"):
      (0 to 3).foreach: kind =>
        assert(scala.util.Try(
          ConstructionKernel.inspect(new ReductionInvalidBranchSource(kind))
        ).isFailure)
      val original = ConstructionKernel.inspect(new ReductionStaticIntegerSource(1))
      val choice =
        original.parameterExpressions.find(_.operation == "static_integer_choose_zero").get
      val branch = choice.operands(2)
      val alterations =
        Vector[KernelParameterExpressionSnapshot => KernelParameterExpressionSnapshot](
          _.copy(owner = "foreign"),
          _.copy(
            operation = "analog_add",
            literal = None,
            operands = Vector(Long.MaxValue.toString, "1")
          ),
          _.copy(dataType = "Real"),
          _.copy(unit = Some("V")),
          _.copy(operation = "static_integer_ceil_log2", literal = None, operands = Vector("0")),
          _.copy(
            operation = "static_integer_choose_zero",
            literal = None,
            operands = Vector("0", "1")
          ),
          value =>
            value.copy(operation = "analog_add", literal = None, operands = Vector(value.path, "1"))
        )
      alterations.foreach: alter =>
        val forged = original.copy(parameterExpressions = original.parameterExpressions.map:
          value =>
            if value.path == branch then alter(value)
            else if value.path == choice.path then
              value.copy(operands = value.operands.updated(0, "0"))
            else value)
        assert(scala.util.Try(StructuralParameterBinding.sameInteger(
          forged,
          forged.root,
          choice.path,
          s"${forged.root}.width"
        )).isFailure)
        assert(scala.util.Try(
          ScalaToMlirBridge.fromSnapshot(forged)
        ).failed.get.isInstanceOf[BridgeException])

    test(
      "finalized instances substitute every branch without replacing independent parameters by defaults"
    ):
      val snapshot = instanceFixture
      def resolved(instance: String) =
        StructuralParameterBinding.resolveDimensions(snapshot, "P", s"P.$instance", "C.port")
      val a = resolved("a")
      val b = resolved("b")
      val one = resolved("one")
      val three = resolved("three")
      assert(a.nodes.exists(_.parameter.contains("P.A")))
      assert(b.nodes.exists(_.parameter.contains("P.B")))
      assert(!a.nodes.exists(_.parameter.contains("P.B")))
      assert(a.nodes(a.dimensions.head).operation.contains("static_integer_choose_zero"))
      assert(a.nodes(a.dimensions.head).bounds == Bounds(5L, 8L))
      assert(one.nodes(one.dimensions.head).literal.contains(5L))
      assert(three.nodes(three.dimensions.head).literal.contains(7L))
      val alwaysZero = snapshot.copy(parameterExpressions = snapshot.parameterExpressions.map:
        value =>
          if value.path == "C.test" then value.copy(operands = Vector("C.N", "C.N")) else value)
      val zero = StructuralParameterBinding.resolveDimensions(alwaysZero, "P", "P.a", "C.port")
      assert(zero.nodes(zero.dimensions.head).bounds == Bounds(5L, 5L))
      assert(zero.nodes(zero.dimensions.head).operation.contains("static_integer_choose_zero"))
      val bad = snapshot.copy(parameterExpressions = snapshot.parameterExpressions.map: value =>
        if value.path == "C.wide" then
          value.copy(operation = "analog_div", operands = Vector("1", "0"))
        else value)
      assert(scala.util.Try(StructuralParameterBinding.resolveDimensions(
        bad,
        "P",
        "P.one",
        "C.port"
      )).isFailure)
