package nodal.internal.testkit

import nodal.*
import utest.*

final class DigitalOnReviewSource extends Module:
  val clock = in(Bool)
  val result = out(Bool)
  var calls = 0
  on(clock.rising):
    calls += 1
    result := true.B
  on(clock.falling):
    calls += 1
    result := false.B

final class DigitalOnReviewBody(body: () => Unit) extends Module:
  on(true.B.rising):
    body()

final class DigitalInAnalogReviewSource extends Module:
  analogProcedure:
    on(true.B.rising):
      ()

final class AnalogOnOutsideReviewSource extends Module:
  on(initialStep):
    ()

final class LegacyConditionalReviewSource extends Module:
  val state = variable(Real, 0.0.real)
  analogProcedure:
    when(true.B):
      state := 1.0.real
    elsewhen(false.B):
      state := 2.0.real
    otherwise:
      state := 3.0.real

object AnalogEventReviewTests extends TestSuite:
  private def failure(top: => Module): String =
    scala.util.Try(ConstructionKernel.inspect(top)).failed.get.getMessage

  val tests: Tests = Tests:
    test("public digital rising and falling controls retain their candidate path"):
      val snapshot = ConstructionKernel.inspect {
        val source = new DigitalOnReviewSource
        assert(source.calls == 2)
        source
      }
      assert(snapshot.analogProcedural.isEmpty, snapshot.analogRegions.isEmpty)
      assert(Nodal.emit(new DigitalOnReviewSource).report.designKind == DesignKind.DigitalOnly)

    test("legacy conditional block construction is not a digital event process"):
      val snapshot = ConstructionKernel.inspect(new LegacyConditionalReviewSource)
      assert(snapshot.analogProcedural.nonEmpty)

    test("digital on keeps continuous waveform restrictions"):
      assert(failure(new DigitalOnReviewBody(() => {
        analog:
          val _ = abstime
      })).contains("NODAL-ANALOG-036-001"))

    test("analog and digital control contexts cannot be silently interchanged"):
      assert(failure(new AnalogOnOutsideReviewSource).contains("NODAL-ANALOG-037-001"))
      assert(failure(new DigitalInAnalogReviewSource).contains("NODAL-ANALOG-037-001"))
      assert(failure(new AnalogEventBodySource(() => {
        on(initialStep):
          on(true.B.rising):
            ()
      })).contains("NODAL-ANALOG-037-007"))

    test("the shared crossing contract accepts exactly the closed direction set"):
      val waveform = AnalogEventContract.Argument(0, "real", "voltage", Some(1.0))
      for direction <- Vector(-1.0, 0.0, 1.0) do
        AnalogEventContract.validate(
          "analog_cross",
          Vector(waveform, AnalogEventContract.Argument(1, "integer", "1", Some(direction)))
        )
      for direction <- Vector(None, Some(-2.0), Some(2.0), Some(0.5)) do
        val result = scala.util.Try(AnalogEventContract.validate(
          "analog_cross",
          Vector(waveform, AnalogEventContract.Argument(1, "integer", "1", direction))
        ))
        assert(result.failed.get.getMessage.contains("NODAL-ANALOG-037-004"))
