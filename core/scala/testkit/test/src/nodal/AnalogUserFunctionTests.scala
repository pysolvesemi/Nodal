package nodal.internal.testkit

import nodal.*
import nodal.increment41fixture.FunctionAmplifier
import nodal.internal.bridge.ScalaToMlirBridge
import utest.*

final class FunctionDefinitionBody(body: () => Unit) extends Module:
  body()

final class FunctionCallBody(body: AnalogFunction[Real] => Unit) extends Module:
  val identity = AnalogFunction("identitySignal", Real): f =>
    f.input("signal", Real)
  analog:
    body(identity)

final class FunctionCaptureParameter extends Module:
  val gain = param(2.0.real)
  val scale = AnalogFunction("scaleSignal", Real): f =>
    val signal = f.input("signal", Real)
    signal * gain

final class FunctionBodyParameter extends Module:
  val illegal = AnalogFunction("illegalParameter", Real): f =>
    val signal = f.input("signal", Real)
    val _ = param(signal)
    signal

final class FunctionOutsideCall extends Module:
  val identity = AnalogFunction("identitySignal", Real): f =>
    f.input("signal", Real)
  val result = identity(1.0.real)

final class FunctionNestedDefinition extends Module:
  val outer = AnalogFunction("outerSignal", Real): f =>
    val signal = f.input("signal", Real)
    val _ = AnalogFunction("innerSignal", Real): g =>
      g.input("signal", Real)
    signal

final class FunctionTransaction extends Module:
  val rejected = scala.util.Try:
    AnalogFunction("repairSignal", Real): f =>
      val signal = f.input("signal", Real)
      f.local("signal", signal)
  require(rejected.isFailure)
  val repaired = AnalogFunction("repairSignal", Real): f =>
    f.input("signal", Real)
  analog:
    val _ = repaired(1.0.real)

object AnalogUserFunctionTests extends TestSuite:
  private def code(top: => Module): String =
    scala.util.Try(ConstructionKernel.inspect(top)).failed.get
      .asInstanceOf[ConstructionException].diagnostic.code

  private def reject(body: => Unit, expected: String): Unit =
    assert(code(new FunctionDefinitionBody(() => body)) == expected)

  val tests: Tests = Tests:
    test("public scalar typed functions retain source declarations calls locals and dimensions"):
      val snapshot = ConstructionKernel.inspect(new FunctionAmplifier)
      assert(snapshot.analogFunctions.size == 6)
      assert(snapshot.analogFunctions.forall(_.definition.source.nonEmpty))
      assert(snapshot.analogFunctions.forall(_.definition.nodes.forall(_.source.nonEmpty)))
      assert(snapshot.sourceMap.exists(_.semanticPath.contains("function_affineSignal.value_")))
      val source = ScalaToMlirBridge.fromSnapshot(snapshot)
      assert(source == ScalaToMlirBridge.lower(new FunctionAmplifier))
      assert(source.text.contains("nodal.bridge.analog_functions"))
      assert(source.text.contains("nodal.analog_user_call"))
      assert(source.text.contains("kind = \"local\""))
      assert(source.text.contains("!nodal.quantity<\"integer\", \"1\">"))
      assert(source.text.contains("!nodal.quantity<\"real\", \"voltage\">"))

    test("names and input ordering are unambiguous with no overloading"):
      reject(
        {
          val _ = AnalogFunction("duplicate", Real): f =>
            f.input("x", Real)
          val _ = AnalogFunction("duplicate", Real): f =>
            f.input("x", Real)
        },
        "NODAL-ANALOG-041-002"
      )
      reject(
        {
          val _ = AnalogFunction("bad-name", Real): f =>
            f.input("x", Real)
        },
        "NODAL-ANALOG-041-002"
      )
      reject(
        {
          val _ = AnalogFunction("duplicateLocal", Real): f =>
            val x = f.input("x", Real)
            f.local("x", x)
        },
        "NODAL-ANALOG-041-002"
      )
      reject(
        {
          val _ = AnalogFunction("lateInput", Real): f =>
            val x = f.input("x", Real)
            val _ = x + 1.0.real
            f.input("y", Real)
        },
        "NODAL-ANALOG-041-002"
      )
      reject(
        {
          val _ = AnalogFunction("noInputs", Real): _ =>
            1.0.real
        },
        "NODAL-ANALOG-041-002"
      )

    test("return and call types and physical dimensions are checked"):
      reject(
        {
          val _ = AnalogFunction("wrongReturn", Real, PhysicalDimension.Voltage): f =>
            f.input("x", Real)
        },
        "NODAL-ANALOG-041-004"
      )
      reject(
        {
          val _ = AnalogFunction("badInteger", Integer, PhysicalDimension.Voltage): f =>
            f.input("x", Integer)
        },
        "NODAL-ANALOG-041-003"
      )
      reject(
        {
          val _ = AnalogFunction("badBool", Bool): f =>
            f.input("x", Bool)
        },
        "NODAL-ANALOG-041-003"
      )
      assert(code(new FunctionCallBody(f => { val _ = f() })) == "NODAL-ANALOG-041-005")
      assert(code(new FunctionCallBody(f => { val _ = f(1.integer) })) == "NODAL-ANALOG-041-003")
      assert(code(new FunctionCallBody(f => { val _ = f(1.0.V) })) == "NODAL-ANALOG-041-003")
      assert(code(new FunctionCallBody(f => { val _ = f(1.0.real, 2.0.real) })) ==
        "NODAL-ANALOG-041-005")

    test("explicit inputs replace captures and bodies cannot create state or effects"):
      assert(code(new FunctionCaptureParameter) == "NODAL-ANALOG-041-006")
      assert(code(new FunctionBodyParameter) == "NODAL-ANALOG-041-001")
      assert(code(new FunctionOutsideCall) == "NODAL-ANALOG-041-001")
      assert(code(new FunctionNestedDefinition) == "NODAL-ANALOG-041-001")
      for effect <- Vector[Expr[Real] => Expr[Real]](
          x => ddt(x),
          x => idt(x),
          x => absdelay(x, 1.0.s),
          _ => abstime,
          x => laplaceNd(x, Seq(1.0.real), Seq(1.0.real)),
          x => { val _ = AnalysisContext.active(AnalysisKind.Transient); x }
        )
      do
        reject(
          {
            val _ = AnalogFunction("illegalEffect", Real): f =>
              effect(f.input("x", Real))
          },
          "NODAL-ANALOG-041-001"
        )

    test("failed definition is transactional and escaped body handles are closed"):
      val snapshot = ConstructionKernel.inspect(new FunctionTransaction)
      assert(snapshot.analogFunctions.size == 1)
      assert(snapshot.analogFunctions.head.definition.nodes.size == 1)
      var escaped: Option[AnalogFunctionBody] = None
      val _ = ConstructionKernel.inspect(new FunctionDefinitionBody(() =>
        val _ = AnalogFunction("escapeCheck", Real): f =>
          escaped = Some(f)
          f.input("x", Real)
      ))
      val failure = scala.util.Try(escaped.get.input("outside", Real)).failed.get
        .asInstanceOf[ConstructionException]
      assert(failure.diagnostic.code == "NODAL-ANALOG-041-001")

    test("function handles cannot cross construction sessions"):
      var escaped: Option[AnalogFunction[Real]] = None
      val _ = ConstructionKernel.inspect(new FunctionDefinitionBody(() =>
        escaped = Some(AnalogFunction("oldSignal", Real)(f => f.input("x", Real)))
      ))
      assert(code(new FunctionCallBody(_ => { val _ = escaped.get(1.0.real) })) ==
        "NODAL-ANALOG-041-005")

    test("known invalid constant subgraphs are rejected without folding calls"):
      reject(
        {
          val _ = AnalogFunction("zeroDivide", Real): f =>
            val x = f.input("x", Real)
            val zero = f.local("zero", 2.0.real - 2.0.real)
            x / zero
        },
        "NODAL-ANALOG-041-008"
      )
      reject(
        {
          val _ = AnalogFunction("overflow", Integer): f =>
            val _ = f.input("x", Integer)
            f.add(Int.MaxValue.integer, 1.integer)
        },
        "NODAL-ANALOG-041-008"
      )
      reject(
        {
          val _ = AnalogFunction("invalidRoot", Real): f =>
            val _ = f.input("x", Real)
            AnalogMath.sqrt(f.local("negative", 1.0.real - 2.0.real))
        },
        "NODAL-ANALOG-038-004"
      )

    test("bridge rejects orphaned definitions invalid return references and lexical captures"):
      val snapshot = ConstructionKernel.inspect(new FunctionAmplifier)
      val first = snapshot.analogFunctions.head
      val bad = Vector(
        snapshot.copy(analogFunctions = snapshot.analogFunctions :+ first),
        snapshot.copy(analogFunctions = Vector(first.copy(owner = "missing"))),
        snapshot.copy(analogFunctions =
          Vector(first.copy(definition = first.definition.copy(returned = -1)))
        ),
        snapshot.copy(analogFunctions =
          Vector(first.copy(definition =
            first.definition.copy(
              nodes = first.definition.nodes.updated(
                0,
                first.definition.nodes.head.copy(operands = Vector(999))
              )
            )
          ))
        )
      )
      bad.foreach: value =>
        val failure = scala.util.Try(ScalaToMlirBridge.fromSnapshot(value)).failed.get
        assert(failure.getMessage.contains("NODAL-ANALOG-041-"))
