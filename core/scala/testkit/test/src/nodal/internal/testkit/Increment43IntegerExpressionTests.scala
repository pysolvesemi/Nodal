package nodal.internal.testkit

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration

import scala.compiletime.testing.typeCheckErrors

import nodal.*
import nodal.internal.bridge.*

import utest.*

final class Increment43IntegerExpressionChild extends Module:
  val sum: Param[Integer] = param(0.integer)
  val difference: Param[Integer] = param(0.integer)
  val product: Param[Integer] = param(0.integer)
  val quotient: Param[Integer] = param(0.integer)
  val negative: Param[Integer] = param(0.integer)
  val repeated: Param[Integer] = param(0.integer)
  val cancellation: Param[Integer] = param(0.integer)

final class Increment43IntegerExpressionTop(defaultCount: Int) extends Module:
  val count: Param[Integer] = param(defaultCount.integer)
  val divisor: Param[Integer] = param(2.integer)
  val shared: Expr[Integer] = count + 1.integer
  val child: Instance[Increment43IntegerExpressionChild] =
    instance(new Increment43IntegerExpressionChild)
  child.param(_.sum, shared)
  child.param(_.difference, count - 9.integer)
  child.param(_.product, shared * count)
  child.param(_.quotient, -count / divisor)
  child.param(_.negative, -count)
  child.param(_.repeated, shared * shared)
  child.param(_.cancellation, shared - shared)

final class Increment43ForeignIntegerExpression extends Module:
  val left: Instance[Increment43IntegerExpressionChild] =
    instance(new Increment43IntegerExpressionChild)
  val right: Instance[Increment43IntegerExpressionChild] =
    instance(new Increment43IntegerExpressionChild)
  left.param(_.sum, right(_.sum) + 1.integer)

final class Increment43DynamicIntegerExpression extends Module:
  val runtimeCount: Signal[Integer] = wire(Integer)
  val child: Instance[Increment43IntegerExpressionChild] =
    instance(new Increment43IntegerExpressionChild)
  child.param(_.sum, runtimeCount + 1.integer)

final class Increment43InvalidIntegerExpression(form: Int) extends Module:
  val zero: Param[Integer] = param(0.integer)
  val child: Instance[Increment43IntegerExpressionChild] =
    instance(new Increment43IntegerExpressionChild)
  val expression: Expr[Integer] = form match
    case 0 => 1.integer / zero
    case 1 => Int.MaxValue.integer * Int.MaxValue.integer * Int.MaxValue.integer
    case _ => -(Int.MaxValue.integer * Int.MaxValue.integer * Int.MaxValue.integer)
  child.param(_.sum, expression)

object Increment43IntegerExpressionTests extends TestSuite:
  private def delete(path: Path): Unit =
    if Files.isDirectory(path) then
      val stream = Files.list(path)
      try
        val iterator = stream.iterator()
        while iterator.hasNext do delete(iterator.next())
      finally stream.close()
    val _ = Files.deleteIfExists(path)

  private def failure(top: => Module): ConstructionException =
    scala.util.Try(ConstructionKernel.inspect(top)).failed.get.asInstanceOf[ConstructionException]

  private def digest(text: String): String =
    MessageDigest.getInstance("SHA-256")
      .digest(text.getBytes(StandardCharsets.UTF_8))
      .map(value => f"${value & 0xff}%02x").mkString

  private def request(executable: String, directory: Path): NativeCompilerRequest =
    NativeCompilerRequest(
      executable = Path.of(executable).toAbsolutePath,
      arguments = Vector("--pass-pipeline=builtin.module(nodal-verify-parameters)"),
      workingDirectory = directory,
      timeout = Duration.ofSeconds(30)
    )

  val tests: Tests = Tests:
    test("integer arithmetic retains canonical typed expression nodes"):
      val snapshot = ConstructionKernel.inspect(new Increment43IntegerExpressionTop(7))
      val expressions = snapshot.parameterExpressions
      val operations = expressions.filter(_.literal.isEmpty)
      assert(
        operations.map(_.operation).toSet == Set(
          "analog_add",
          "analog_sub",
          "analog_mul",
          "analog_div",
          "analog_neg"
        )
      )
      assert(expressions.forall(_.dataType == "Integer"))
      assert(expressions.forall(_.owner == snapshot.root))
      assert(operations.forall(_.operands.nonEmpty))
      assert(expressions.count(_.operation == "analog_add") == 1)
      assert(expressions.exists(_.literal.contains("9")))
      val countPath = s"${snapshot.root}.count"
      assert(operations.exists(_.operands.contains(countPath)))

    test("shared integer expressions remain one DAG node rather than repeated copies"):
      val snapshot = ConstructionKernel.inspect(new Increment43IntegerExpressionTop(7))
      val shared = snapshot.parameterExpressions.find(_.operation == "analog_add").get
      val repeated = snapshot.parameterExpressions.find(expression =>
        expression.operation == "analog_mul" &&
          expression.operands == Vector(shared.path, shared.path)
      )
      assert(repeated.nonEmpty)
      val cancellation = snapshot.parameterExpressions.find(expression =>
        expression.operation == "analog_sub" &&
          expression.operands == Vector(shared.path, shared.path)
      )
      assert(cancellation.nonEmpty)
      assert(
        snapshot.parameterExpressions.map(_.path).distinct.size ==
          snapshot.parameterExpressions.size
      )

    test("integer capture is deterministic and independent of parameter default values"):
      val first = ConstructionKernel.inspect(new Increment43IntegerExpressionTop(7))
      val second = ConstructionKernel.inspect(new Increment43IntegerExpressionTop(7))
      val changed = ConstructionKernel.inspect(new Increment43IntegerExpressionTop(11))
      assert(first == second)
      assert(first.parameterExpressions == changed.parameterExpressions)
      val firstCount = first.modules.find(_.path == first.root).get.declarations
        .find(_.name == "count").get
      val changedCount = changed.modules.find(_.path == changed.root).get.declarations
        .find(_.name == "count").get
      assert(firstCount.attributes.toMap.apply("default") == "7")
      assert(changedCount.attributes.toMap.apply("default") == "11")

    test("bridge serializes the existing integer constant DAG without default substitution"):
      val first = ScalaToMlirBridge.lower(new Increment43IntegerExpressionTop(7))
      val second = ScalaToMlirBridge.lower(new Increment43IntegerExpressionTop(7))
      assert(first == second)
      for operation <- Vector("add", "sub", "mul", "div", "neg") do
        assert(first.text.contains(s"operator_name = \"$operation\""))
      assert(first.text.contains("parameter = @count"))
      assert(first.text.contains("parameter = @divisor"))
      assert(first.text.contains("\"nodal.parameter_override\""))
      assert(first.text.contains("(i64, i64) -> i64"))
      assert(
        first.text.sliding("operator_name = \"add\"".length)
          .count(_ == "operator_name = \"add\"") == 1
      )

    test("integer syntax does not introduce implicit Real UInt or host conversions"):
      val real = typeCheckErrors("import nodal.*; val bad = 1.integer + 1.0.real")
      val uint = typeCheckErrors("import nodal.*; val bad = 1.integer + 1.U(8)")
      val host = typeCheckErrors("import nodal.*; val bad: Int = 1.integer + 2.integer")
      assert(real.nonEmpty)
      assert(uint.nonEmpty)
      assert(host.nonEmpty)

    test("integer expression dependencies retain foreign-owner rejection"):
      assert(
        failure(new Increment43ForeignIntegerExpression).diagnostic.code ==
          "NODAL-HIERARCHY-031"
      )
      assert(ConstructionKernel.inspect(new Increment43IntegerExpressionTop(7)).modules.nonEmpty)

    test("integer expressions do not turn runtime signals into static parameter values"):
      assert(
        failure(new Increment43DynamicIntegerExpression).diagnostic.code ==
          "NODAL-HIERARCHY-032"
      )
      assert(ConstructionKernel.inspect(new Increment43IntegerExpressionTop(7)).modules.nonEmpty)

    test("configured native compiler checks arithmetic against independent literal bindings"):
      sys.env.get("NODAL_NODALC") match
        case None => assert(true)
        case Some(executable) =>
          val directory = Files.createTempDirectory("nodal-increment43-integer-")
          try
            for count <- Vector(7, 11) do
              val document = ScalaToMlirBridge.lower(new Increment43IntegerExpressionTop(count))
              val nativeRequest = request(executable, directory)
              NativeCompilerClient.run(document, nativeRequest) match
                case success: NativeCompilerSuccess =>
                  assert(success.normalizedMlir.contains("\"nodal.const_expr\""))
                case rejected: NativeCompilerFailure =>
                  scala.Predef.assert(
                    false,
                    s"${rejected.diagnostic}\n${rejected.standardError}\nGenerated input:\n${document.text}"
                  )
              val expected = Vector(
                "sum" -> (count + 1),
                "difference" -> (count - 9),
                "product" -> ((count + 1) * count),
                "quotient" -> (-count / 2),
                "negative" -> -count,
                "repeated" -> ((count + 1) * (count + 1)),
                "cancellation" -> 0
              )
              val marker = "parameter_bindings = {}"
              assert(document.text.sliding(marker.length).count(_ == marker) == 1)
              for wrong <- Vector(false, true) do
                val bindings = expected.map: (name, value) =>
                  val literal = if wrong && name == "quotient" then value - 1 else value
                  s"$name = $literal : i64"
                val text = document.text.replace(
                  marker,
                  s"parameter_bindings = {${bindings.mkString(", ")}}"
                )
                val checked = document.copy(text = text, sha256 = digest(text))
                NativeCompilerClient.run(checked, nativeRequest) match
                  case success: NativeCompilerSuccess =>
                    assert(!wrong)
                    assert(success.normalizedMlir.contains("\"nodal.parameter_override\""))
                  case rejected: NativeCompilerFailure =>
                    assert(wrong)
                    assert(rejected.exitCode.contains(1))
                    assert(rejected.diagnostic.code == "NODAL-PARAMETER-OVERRIDE-001")
          finally delete(directory)

    test("configured native compiler rejects zero division and signed integer overflow"):
      sys.env.get("NODAL_NODALC") match
        case None => assert(true)
        case Some(executable) =>
          val directory = Files.createTempDirectory("nodal-increment43-integer-invalid-")
          try
            for form <- 0 until 3 do
              val document = ScalaToMlirBridge.lower(new Increment43InvalidIntegerExpression(form))
              NativeCompilerClient.run(document, request(executable, directory)) match
                case rejected: NativeCompilerFailure =>
                  assert(rejected.exitCode.contains(1))
                  assert(rejected.diagnostic.code == "NODAL-PARAMETER-OVERRIDE-001")
                case success: NativeCompilerSuccess =>
                  scala.Predef.assert(
                    false,
                    s"invalid integer arithmetic accepted: ${success.normalizedMlir}"
                  )
          finally delete(directory)
