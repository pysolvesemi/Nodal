package nodal.internal.testkit

import nodal.*
import nodal.internal.bridge.*
import utest.*

final class StaticIntegerMaximumSource(defaultWidth: Int) extends Module:
  val left = param(defaultWidth.integer, range = 1 to 9)
  val right = param(4.integer, range = 3 to 6)
  val maximum = StaticIntegerExpression.maximum(left, right)
  val different = StaticIntegerExpression.maximum(left, left)
  private def unsigned(width: Dimension): DataType[UInt] = width match
    case literal: Int => UInt(literal)
    case expression: Expr[Integer] => UInt(expression)
  val first = wire(unsigned(StaticIntegerExpression.add(maximum, 1)))
  val second = wire(unsigned(different))

object StaticIntegerExpressionTests extends TestSuite:
  val tests: Tests = Tests:
    test("maximum and addition fold only source literal pairs with checked overflow"):
      assert(StaticIntegerExpression.maximum(Int.MinValue, Int.MaxValue) == Int.MaxValue)
      assert(StaticIntegerExpression.add(3, 4) == 7)
      val error = scala.util.Try(StaticIntegerExpression.add(Int.MaxValue, 1)).failed.get
      assert(error.isInstanceOf[ConstructionException])
      assert(error.asInstanceOf[ConstructionException].diagnostic.code ==
        "NODAL-PARAMETER-BINDING-022")

    test("static maximum retains symbolic identity independently of parameter defaults"):
      val first = ConstructionKernel.inspect(new StaticIntegerMaximumSource(4))
      val changed = ConstructionKernel.inspect(new StaticIntegerMaximumSource(7))
      assert(first.parameterExpressions == changed.parameterExpressions)
      val maxima = first.parameterExpressions.filter(_.operation == "static_integer_max")
      assert(maxima.size == 2)
      assert(maxima.forall(value => value.literal.isEmpty && value.dataType == "Integer"))
      assert(maxima.head.operands == Vector(s"${first.root}.left", s"${first.root}.right"))
      assert(!StructuralParameterBinding.sameInteger(
        first,
        first.root,
        maxima(0).path,
        maxima(1).path
      ))
      assert(StructuralParameterBinding.sameInteger(
        first,
        first.root,
        maxima(1).path,
        s"${first.root}.left"
      ))
      assert(StructuralParameterBinding.sameIntegerOperation(
        first,
        first.root,
        s"${first.root}.left",
        "static_integer_max",
        maxima(1).operands
      ))
      assert(!StructuralParameterBinding.sameInteger(
        first,
        first.root,
        maxima(0).path,
        s"${first.root}.left"
      ))
      val sum = first.parameterExpressions.find(_.operation == "analog_add").get
      assert(StructuralParameterBinding.sameIntegerOperation(
        first,
        first.root,
        sum.path,
        "static_integer_max",
        maxima(0).operands,
        increment = 1L
      ))
      assert(!StructuralParameterBinding.sameIntegerOperation(
        first,
        first.root,
        sum.path,
        "static_integer_max",
        maxima(1).operands,
        increment = 1L
      ))
      assert(StructuralParameterBinding.defaultInteger(first, first.root, maxima(0).path) == 4L)
      assert(StructuralParameterBinding.defaultInteger(changed, changed.root, maxima(0).path) == 7L)

    test("static maximum bridge retains owned Integer DAG and rejects forged types and arity"):
      val snapshot = ConstructionKernel.inspect(new StaticIntegerMaximumSource(4))
      val text = ScalaToMlirBridge.fromSnapshot(snapshot).text
      assert(text.contains("operator_name = \"max\""))
      assert(text.contains("(i64, i64) -> i64"))
      assert(text.contains("parameter = @left"), text.contains("parameter = @right"))
      for forge <- Vector[KernelParameterExpressionSnapshot => KernelParameterExpressionSnapshot](
          value => value.copy(operands = value.operands.take(1)),
          value => value.copy(dataType = "Real")
        )
      do
        val forged = snapshot.copy(parameterExpressions = snapshot.parameterExpressions.map:
          case value if value.operation == "static_integer_max" => forge(value)
          case value => value)
        assert(scala.util.Try(
          ScalaToMlirBridge.fromSnapshot(forged)
        ).failed.get.isInstanceOf[BridgeException])
