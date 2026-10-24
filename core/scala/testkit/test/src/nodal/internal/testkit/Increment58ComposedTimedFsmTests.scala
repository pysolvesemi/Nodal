package nodal.internal.testkit

import nodal.*
import nodal.internal.bridge.*
import nodal_external.*
import utest.*

object Increment58ComposedTimedFsmTests extends TestSuite:
  val tests = Tests:
    test("composed timed children retain separate counters and canonical join ownership"):
      val snapshot = ConstructionKernel.inspect(new Increment58ComposedTimedFsmSource)
      assert(snapshot.flatFsms.size == 3, snapshot.flatFsms.forall(_.composition.nonEmpty))
      val children = snapshot.flatFsms.filter(_.composition.get.parent.nonEmpty)
      assert(children.flatMap(_.timerStorage).distinct.size == 2)
      assert(children.flatMap(_.timerEdges.map(_.cycles)).sorted == Vector(2, 3))
      assert(snapshot.flatFsms.flatMap(machine =>
        Vector(machine.storage, machine.control.get.storage) ++ machine.timerStorage
      ).distinct.size == 8)
      val text = ScalaToMlirBridge.fromSnapshot(snapshot).text
      assert(text.linesIterator.count(_.contains("contract_version = \"flat-fsm-v4\"")) == 2)
      assert(text.linesIterator.count(_.contains("lifecycle_mode = \"composed\"")) == 2)
      assert(text.linesIterator.count(_.contains("\"nodal.fsm_join\"")) == 1)
      assert(text.linesIterator.count(_.contains("\"nodal.fsm_activation\"")) == 1)
      assert(text == ScalaToMlirBridge.fromSnapshot(
        ConstructionKernel.inspect(new Increment58ComposedTimedFsmSource)
      ).text)
