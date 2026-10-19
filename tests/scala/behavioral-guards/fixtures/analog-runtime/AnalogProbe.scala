package nodal.behavioral.fixture
import nodal.*
import _root_.analog.guard.fixture.*
final class AnalogDirect extends Module:
  val state = variable(Real, 0.0.real)
  analogProcedure:
    when(true.B) { state := 1.0.real }
    elsewhen(false.B) { state := 2.0.real }
    otherwise { state := 3.0.real }
final class AnalogLexicalHost extends Module:
  var order = 0
  analogProcedure:
    when({ order = order * 10 + 1; true.B }) { order = order * 10 + 2 }
    def nested(): Unit =
      when({ order = order * 10 + 3; true.B }) { order = order * 10 + 4 }
    nested()
  assert(order == 1234)
final class AnalogSeparate extends Module:
  val state = variable(Real, 0.0.real)
  analogProcedure { AnalogHelpers.assign(true.B, state) }
final class AnalogWrapped extends Module:
  val state = variable(Real, 0.0.real)
  AnalogHelpers.wrapped(true.B, state)
final class AnalogPure extends Module:
  val state = variable(Real, 0.0.real)
  analogProcedure { AnalogHelpers.pure(true.B) }
final class DigitalPure extends Module:
  val flag = in(Bool)
  AnalogHelpers.pure(flag)
final class AnalogOutside extends Module:
  val state = variable(Real, 0.0.real)
  AnalogHelpers.assign(true.B, state)
final class AnalogPredicateOutside extends Module:
  val state = variable(Real, 0.0.real)
  AnalogHelpers.predicateEffect(state)
final class AnalogPredicateInside extends Module:
  val state = variable(Real, 0.0.real)
  analogProcedure { AnalogHelpers.predicateEffect(state) }
object EscapedAnalogCalls:
  var predicates = 0
  var bodies = 0
final class EscapedAnalogHelper extends Module:
  val state = variable(Real, 0.0.real)
  var escaped: () => Unit = () => ()
  analogProcedure:
    def later(): Unit =
      when({ EscapedAnalogCalls.predicates += 1; true.B }) {
        EscapedAnalogCalls.bodies += 1
        state := 1.0.real
      }
    escaped = () => later()
  escaped()
final class EscapedAnalogFunction extends Module:
  var escaped: () => Unit = () => ()
  analogProcedure:
    escaped = () =>
      when({ EscapedAnalogCalls.predicates += 1; true.B }) {
        EscapedAnalogCalls.bodies += 1
      }
  escaped()
object AnalogProbe:
  def main(arguments: Array[String]): Unit =
    val _ = arguments
    val direct = ConstructionKernel.inspect(new AnalogDirect)
    assert(direct.behavioralIfs.isEmpty)
    assert(direct.analogProcedural.flatMap(_.assignments).size == 3)
    val lexical = ConstructionKernel.inspect(new AnalogLexicalHost)
    assert(lexical.behavioralIfs.isEmpty)
    val separate = ConstructionKernel.inspect(new AnalogSeparate)
    assert(separate.behavioralIfs.isEmpty)
    assert(separate.analogProcedural.flatMap(_.assignments).size == 3)
    assert(AnalogCalls.calls == 3)
    val wrapped = ConstructionKernel.inspect(new AnalogWrapped)
    assert(wrapped.behavioralIfs.isEmpty)
    assert(wrapped.analogProcedural.flatMap(_.assignments).size == 1)
    val pureAnalog = ConstructionKernel.inspect(new AnalogPure)
    assert(pureAnalog.behavioralIfs.isEmpty)
    val pureDigital = ConstructionKernel.inspect(new DigitalPure)
    assert(pureDigital.behavioralIfs.size == 1)
    val outside = scala.util.Try(ConstructionKernel.inspect(new AnalogOutside)).failed.get
    assert(outside.asInstanceOf[ConstructionException].diagnostic.code ==
      "NODAL-SEQUENTIAL-056-MISSING")
    assert(AnalogCalls.calls == 3)
    val predicateOutside =
      scala.util.Try(ConstructionKernel.inspect(new AnalogPredicateOutside)).failed.get
    assert(predicateOutside.asInstanceOf[ConstructionException].diagnostic.code ==
      "NODAL-SEQUENTIAL-056-MISSING")
    assert(AnalogCalls.predicates == 0 && AnalogCalls.calls == 3)
    val predicateInside = ConstructionKernel.inspect(new AnalogPredicateInside)
    assert(predicateInside.behavioralIfs.isEmpty)
    assert(predicateInside.analogProcedural.flatMap(_.assignments).size == 1)
    assert(AnalogCalls.predicates == 1 && AnalogCalls.calls == 4)
    List(() => new EscapedAnalogHelper, () => new EscapedAnalogFunction).foreach: construct =>
      val escaped = scala.util.Try(ConstructionKernel.inspect(construct())).failed.get
      assert(escaped.asInstanceOf[ConstructionException].diagnostic.code ==
        "NODAL-SEQUENTIAL-056-MISSING")
      assert(
        EscapedAnalogCalls.predicates == 0 && EscapedAnalogCalls.bodies == 0,
        "escaped analog predicate/body executed before context rejection"
      )
    println("ANALOG_GUARD_COMPATIBILITY_PASS direct-separate-wrapped-pure fail-closed-outside")
