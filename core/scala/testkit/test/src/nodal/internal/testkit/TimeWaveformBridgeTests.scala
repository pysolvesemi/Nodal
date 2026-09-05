package nodal.internal.testkit

import nodal.*
import nodal.internal.bridge.ScalaToMlirBridge
import utest.*

object TimeWaveformBridgeTests extends TestSuite:
  val tests: Tests = Tests:
    test("waveform source lowers deterministically to all five native operators"):
      val first = ScalaToMlirBridge.lower(new TimeWaveformTop)
      val second = ScalaToMlirBridge.lower(new TimeWaveformTop)
      assert(first == second)
      assert(first.text.contains("nodal.bridge.waveform_operators"))
      Vector("transition", "slew", "absdelay", "abstime", "bound_step").foreach: op =>
        assert(first.text.contains(s"\"nodal.analog_$op\""))
      assert(first.text.contains("operator_contract = \"increment36\""))
      val task = first.text.linesIterator.find(_.contains("\"nodal.analog_bound_step\"")).get
      assert(task.trim.startsWith("\"nodal.analog_bound_step\""))
      assert(task.contains("-> ()"))
      assert(first.text.contains("time^-1*voltage"))
      assert(first.text.contains("TimeWaveformConstructionTests.scala"))

    test("source-only declarative inventories are analog and preserve scope"):
      val equations = ScalaToMlirBridge.lower(new TimeWaveformEquationTop).text
      val contributions = ScalaToMlirBridge.lower(new TimeWaveformContributionTop).text
      assert(equations.contains("context = \"equation\""))
      assert(contributions.contains("context = \"contribution\""))
      assert(equations.contains("nodal.target.profile = \"analog\""))

    test("bridge rejects duplicate waveform inventories"):
      val snapshot = ConstructionKernel.inspect(new TimeWaveformTop)
      val forged = snapshot.copy(waveformOperators =
        snapshot.waveformOperators ++ snapshot.waveformOperators.take(1)
      )
      val failure = scala.util.Try(ScalaToMlirBridge.fromSnapshot(forged)).failed.get
      assert(failure.getMessage.contains("NODAL-ANALOG-036-002"))
