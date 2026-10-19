package nodal.internal.testkit

import nodal.*
import nodal.internal.bridge.*
import utest.*

object Increment57FsmCrossingTests extends TestSuite:
  private def capture(phase: String, reuse: Boolean): ConstructionSnapshot =
    ConstructionKernel.inspect(new nodal_external.Increment57FsmCrossingSource(phase, reuse))

  val tests: Tests = Tests:
    test("FSM implicit selected phase controls join the actual target sample"):
      Vector("transition", "entry", "exit", "priority").foreach: phase =>
        val rejected = scala.util.Try(capture(phase, reuse = false))
        assert(rejected.failed.get.getMessage.contains("NODAL-CDC-057-002"))
        assert(capture(phase, reuse = true).flatFsms.size == 1)
    test("FSM future action observations retain actual guard dependencies"):
      val rejected = scala.util.Try(capture("active-taken", reuse = false))
      assert(rejected.failed.get.getMessage.contains("NODAL-CDC-057-002"))
      assert(capture("active-taken", reuse = true).flatFsms.size == 1)
    test("FSM active action does not acquire unrelated transition guard lineage"):
      assert(capture("active", reuse = false).flatFsms.size == 1)
      assert(capture("active", reuse = true).flatFsms.size == 1)
    test("FSM implicit controls group with the exact memory effect"):
      val rejected = scala.util.Try(capture("memory", reuse = false))
      assert(rejected.failed.get.getMessage.contains("NODAL-CDC-057-002"))
      assert(capture("memory", reuse = true).memoryWrites.size == 1)
    test("explicit destination permits owned module-level crossing construction"):
      val snapshot =
        ConstructionKernel.inspect(new nodal_external.Increment57ModuleLevelCrossingSource)
      assert(snapshot.digitalExpressions.count(_.crossing.nonEmpty) == 1)
      assert(ScalaToMlirBridge.fromSnapshot(snapshot).text.contains("contract = \"level_v1\""))
