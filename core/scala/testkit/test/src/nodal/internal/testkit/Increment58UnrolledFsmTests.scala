package nodal.internal.testkit

import nodal.*
import nodal.internal.bridge.*
import nodal_external.*
import utest.*

object Increment58UnrolledFsmTests extends TestSuite:
  val tests = Tests:
    test("checked zero and maximum identity folds retain the actual graph"):
      val base = UnrolledFsmGraphs.leaf
      assert(FsmDef.unroll(0, base)((_, previous) => previous) eq base)
      assert(FsmDef.unroll(127, base)((_, previous) => previous) eq base)
      Vector(-1, 128).foreach: count =>
        val error = scala.util.Try(FsmDef.unroll(count, base)((_, previous) => previous)).failed.get
        assert(error.getMessage.contains("0 through 127"))
    test("named immutable steps expose exact authored child inventories"):
      val graph = UnrolledFsmGraphs.graph
      assert(graph.childSlots.size == 1)
      assert(graph.childSlots.head.definition.childSlots.size == 1)
      assert(graph.childSlots.head.definition.childSlots.head.definition.childSlots.isEmpty)
      assert(graph.compositionCost.occurrences == 3, graph.compositionCost.depth == 3)
      assert(graph.guardSlots.isEmpty, graph.actionSlots.isEmpty, graph.transferSlots.isEmpty)
    test("unrolled source keeps independent actual state and registered child joins"):
      val snapshot = ConstructionKernel.inspect(new Increment58UnrolledFsmSource)
      assert(snapshot.flatFsms.size == 3)
      assert(snapshot.flatFsms.map(_.storage).distinct.size == 3)
      assert(snapshot.flatFsms.flatMap(_.timerStorage).size == 1)
      val text = ScalaToMlirBridge.fromSnapshot(snapshot).text
      assert(text.linesIterator.count(_.contains("\"nodal.flat_fsm\"")) == 3)
      assert(text.linesIterator.count(_.contains("\"nodal.fsm_join\"")) == 2)
      val repeated = ScalaToMlirBridge.fromSnapshot(
        ConstructionKernel.inspect(new Increment58UnrolledFsmSource)
      )
      assert(text == repeated.text)
    test("transfer inventory retains typed carrier slots and excludes implementation guards"):
      val graph = FsmDef.unroll(1, UnrolledFsmGraphs.leaf)(UnrolledTransferGraphs.step)
      assert(graph.transferSlots.size == 1, graph.guardSlots.isEmpty)
      assert(graph.guards == graph.transferSlots.map(_.guard))
      val snapshot = ConstructionKernel.inspect(new Increment58UnrolledTransferFsmSource)
      assert(snapshot.flatFsms.size == 1, snapshot.flatFsms.head.transferEdges.size == 1)
      assert(ScalaToMlirBridge.fromSnapshot(snapshot).text.contains("\"nodal.fsm_transfer\""))
