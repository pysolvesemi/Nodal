package nodal.internal.testkit

import nodal.*
import nodal.increment37fixture.AnalogEventSource
import utest.*

final class AnalogEventBodySource(body: () => Unit) extends Module:
  analogProcedure:
    body()

final class AnalogEventConditionalInitialization extends Module:
  val held = variable(Real)
  val sink = variable(Real, 0.0.real)
  analogProcedure:
    on(initialStep):
      held := 1.0.real
    sink := held

final class AnalogEventRuntimePlacement extends Module:
  val enable = variable(Bool, true.B)
  analogProcedure:
    analogConditional:
      analogWhen(enable):
        on(cross(1.0.V)):
          ()

final class AnalogEventBadDefault extends Module:
  val zero = param(0.0.real)
  analogProcedure:
    on(timer(zero)):
      ()

final class AnalogEventUninitializedRead extends Module:
  val value = variable(Real)
  analogProcedure:
    on(cross(value)):
      ()

final class AnalogEventIllegalContribution extends Module:
  val p = inout(Electrical)
  val n = inout(Electrical)
  analogProcedure:
    on(initialStep):
      V(p, n) <+ 1.0.V

object AnalogEventConstructionTests extends TestSuite:
  private def failure(top: => Module): String =
    scala.util.Try(ConstructionKernel.inspect(top)).failed.get.getMessage

  private def invalid(body: => Unit, code: String): Unit =
    assert(failure(new AnalogEventBodySource(() => body)).contains(code))

  val tests: Tests = Tests:
    test("public event-only designs are classified as analog"):
      val report = Nodal.emit(new AnalogEventBodySource(() => on(initialStep) { () })).report
      assert(report.designKind == DesignKind.AnalogOnly, report.digitalProfile.isEmpty)
      assert(Nodal.emit(new AnalogEventSource).report.designKind == DesignKind.AnalogOnly)

    test("all event arities and controlled statements are retained deterministically"):
      val first = ConstructionKernel.inspect(new AnalogEventSource)
      val second = ConstructionKernel.inspect(new AnalogEventSource)
      assert(first == second)
      val statements = first.analogProcedural.head.controlFlow.get.root.statements
      val events =
        statements.collect { case value: AnalogControlFlowRuntime.Statement.EventControl => value }
      assert(events.size == 20)
      assert(events.filter(_.event.operation == "analog_cross").map(_.event.arguments.size) ==
        Vector(1, 2, 3, 4, 5, 4))
      assert(events.exists(_.body.statements.isEmpty))
      assert(events.last.event.analyses == Vector("tran"))
      assert(events.exists(_.event.name == "threshold_crossing"))
      assert(events.head.event.arguments.head.value.rendered.contains("AnalogEventSource.positive"))
      assert(
        events.head.event.arguments.head.value.rendered.contains("AnalogEventSource.threshold")
      )
      assert(!events.head.event.arguments.head.value.rendered.contains("@event_reference_"))
      assert(events.head.event.source.nonEmpty)

    test("event writes are conditional even for initialStep"):
      assert(failure(new AnalogEventConditionalInitialization).contains("NODAL-ANALOG-034-004"))

    test("event reads must be initialized before monitoring"):
      assert(failure(new AnalogEventUninitializedRead).contains("NODAL-ANALOG-034-004"))

    test("runtime monitor placement and nested events are rejected"):
      assert(failure(new AnalogEventRuntimePlacement).contains("NODAL-ANALOG-037-001"))
      invalid(on(initialStep) { on(finalStep) { () } }, "NODAL-ANALOG-037-007")

    test("event bodies reject contributions filters and unimplemented effects"):
      assert(failure(new AnalogEventIllegalContribution).contains("NODAL-ANALOG-037-007"))
      invalid(on(initialStep) { val _ = transition(1.0.V) }, "NODAL-ANALOG-037-007")
      invalid(on(initialStep) { val _ = ddt(1.0.V) }, "NODAL-ANALOG-037-007")
      invalid(on(initialStep) { boundStep(1.0.ns) }, "NODAL-ANALOG-037-007")
      invalid(on(initialStep) { initial { () } }, "NODAL-ANALOG-037-007")

    test("tolerances reject wrong units negative values and nonfinite arithmetic"):
      invalid(on(timer(0.0.V)) { () }, "NODAL-ANALOG-037-003")
      invalid(on(cross(1.0.V, Edge.Rising, 1.0.ns, 1.0.A)) { () }, "NODAL-ANALOG-037-003")
      invalid(on(above(1.0.V, -1.0.ns)) { () }, "NODAL-ANALOG-037-004")
      invalid(on(timer(Double.PositiveInfinity.ns)) { () }, "NODAL-ANALOG-037-004")
      invalid(on(timer(1.0.ns / 0.0.real)) { () }, "NODAL-ANALOG-037-004")

    test("parameter default zero is not a proof of zero time"):
      assert(failure(new AnalogEventBadDefault).contains("NODAL-ANALOG-037-003"))

    test("zero tolerances and nonpositive one-shot periods remain valid"):
      val _ = ConstructionKernel.inspect(new AnalogEventBodySource(() => {
        on(timer(-1.0.ns, -2.0.ns, 0.0.real)) { () }
        on(above(1.0.V, 0.0.real, 0.0.V)) { () }
      }))

    test("analysis filters reject duplicates and target text injection"):
      invalid(on(initialStep("tran", "tran")) { () }, "NODAL-ANALOG-037-008")
      invalid(on(finalStep("tran\"); injected(")) { () }, "NODAL-ANALOG-037-008")

    test("digital edges cannot enter analog event composition"):
      invalid(on(initialStep or true.B.rising) { () }, "NODAL-ANALOG-037-006")

    test("reusing an event handle retains separate controlled occurrences"):
      val snapshot = ConstructionKernel.inspect(new AnalogEventBodySource(() => {
        val edge = cross(1.0.V)
        on(edge) { () }
        on(edge) { () }
      }))
      val events = snapshot.analogProcedural.head.controlFlow.get.root.statements.collect:
        case event: AnalogControlFlowRuntime.Statement.EventControl => event
      assert(events.size == 2, events.map(_.identity).distinct.size == 2)
