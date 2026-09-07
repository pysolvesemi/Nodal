package nodal.internal.testkit

import nodal.*
import nodal.increment38fixture.{AnalogMathEventSource, AnalogMathSource}
import nodal.internal.bridge.ScalaToMlirBridge
import utest.*

final class AnalogFunctionBody(body: () => Unit) extends Module:
  analog:
    body()

final class AnalogFunctionParameter extends Module:
  val value = param(-1.0.real)
  analog:
    val _ = AnalogMath.sqrt(value)

object AnalogFunctionTests extends TestSuite:
  private def reject(body: => Unit, diagnostic: String): Unit =
    val failure = scala.util.Try(ConstructionKernel.inspect(new AnalogFunctionBody(() => body)))
      .failed.get.asInstanceOf[ConstructionException]
    assert(failure.diagnostic.code == diagnostic)

  val tests: Tests = Tests:
    test("registry identities, arities, and spellings are unique and versioned"):
      val entries = AnalogFunctionRegistry.entries
      assert(entries.size == 24, entries.map(_.id).distinct.size == entries.size)
      assert(entries.forall(e => e.arity == 1 || e.arity == 2))
      assert(AnalogFunctionContract.entry("log10").verilogA == "log")
      assert(AnalogFunctionRegistry.analysisTargets("operating_point") == "static")
      assert(AnalogFunctionRegistry.Version == "1")

    test("constant evaluators cover every registered entry"):
      AnalogFunctionRegistry.entries.foreach: entry =>
        val input = if entry.id == "acosh" then 2.0 else 0.5
        val arguments = Vector.fill(entry.arity)(Some(input))
        assert(AnalogFunctionContract.constant(
          entry.id,
          arguments
        ).exists(java.lang.Double.isFinite))
      assert(AnalogFunctionContract.constant("sqrt", Vector(Some(9.0))).contains(3.0))
      assert(AnalogFunctionContract.constant("log10", Vector(Some(100.0))).contains(2.0))
      assert(AnalogFunctionContract.constant("pow", Vector(Some(-2.0), Some(3.0))).contains(-8.0))
      assert(AnalogFunctionContract.constant("hypot", Vector(Some(3.0), Some(4.0))).contains(5.0))
      assert(AnalogFunctionContract.constant(
        "asinh",
        Vector(Some(1.0e300))
      ).exists(java.lang.Double.isFinite))

    test("proven invalid domains and nonfinite results fail before capture"):
      reject({ val _ = AnalogMath.sqrt(-1.0.real) }, "NODAL-ANALOG-038-004")
      reject({ val _ = AnalogMath.ln(0.0.real) }, "NODAL-ANALOG-038-004")
      reject({ val _ = AnalogMath.pow(0.0.real, 0.0.real) }, "NODAL-ANALOG-038-004")
      reject({ val _ = AnalogMath.pow(-2.0.real, 0.5.real) }, "NODAL-ANALOG-038-004")
      reject({ val _ = AnalogMath.atanh(1.0.real) }, "NODAL-ANALOG-038-004")
      reject({ val _ = AnalogMath.exp(1000.0.real) }, "NODAL-ANALOG-038-004")

    test("physical units are not erased, even for zero-valued arguments"):
      reject({ val _ = AnalogMath.sin(0.0.V) }, "NODAL-ANALOG-038-003")
      reject({ val _ = AnalogMath.max(0.0.V, 0.0.A) }, "NODAL-ANALOG-038-003")
      reject({ val _ = AnalogMath.sqrt(1.0.V) }, "NODAL-ANALOG-038-003")
      reject({ val _ = AnalogMath.pow(1.0.V, 2.0.real) }, "NODAL-ANALOG-038-003")
      reject({ val _ = AnalogMath.abs(true.B.asInstanceOf[Expr[Real]]) }, "NODAL-ANALOG-038-003")
      val _ = ConstructionKernel.inspect(new AnalogFunctionBody(() =>
        val _ = AnalogMath.sqrt(4.0.V * 4.0.V)
        val _ = AnalogMath.atan2(1.0.V, 2.0.V)
      ))

    test("symbolic parameters and analysis queries are never elaboration constants"):
      val document = ScalaToMlirBridge.lower(new AnalogFunctionParameter)
      assert(document.text.contains("nodal.parameter_ref"))
      assert(document.text.contains("function_id = \"sqrt\""))
      assert(AnalogFunctionContract.constant("sqrt", Vector(None)).isEmpty)
      assert(AnalogFunctionContract.constant("pow", Vector(Some(2.0), None)).isEmpty)

    test("public continuous and procedural witnesses are stable"):
      val math = ScalaToMlirBridge.lower(new AnalogMathSource)
      val event = ScalaToMlirBridge.lower(new AnalogMathEventSource)
      assert(math == ScalaToMlirBridge.lower(new AnalogMathSource))
      assert(event == ScalaToMlirBridge.lower(new AnalogMathEventSource))
      assert(math.text.contains("registry_version = \"1\""))
      assert(event.text.contains("analog_analysis_v1_transient()"))
