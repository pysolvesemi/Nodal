package nodal.internal.testkit

import nodal.*
import nodal.internal.bridge.*
import nodal_external.*
import utest.*

object Increment58ComposedFsmTests extends TestSuite:
  private def capture(profile: String = "all"): ConstructionSnapshot =
    ConstructionKernel.inspect(new Increment58ComposedFsmSource(profile))
  private def rejected(body: => Any): Unit =
    val failure = scala.util.Try(body).failed.get
    assert(failure.isInstanceOf[ConstructionException] || failure.isInstanceOf[BridgeException])

  val tests = Tests:
    test("immutable child slots retain exact identity and separate actual storage"):
      val snapshot = capture()
      assert(snapshot.flatFsms.size == 3)
      assert(snapshot.flatFsms.forall(_.composition.nonEmpty))
      val parent = snapshot.flatFsms.find(_.composition.get.parent.isEmpty).get
      assert(parent.composition.get.groups.size == 1)
      assert(parent.composition.get.groups.head.children.size == 2)
      assert(snapshot.flatFsms.flatMap(value =>
        Vector(value.storage, value.control.get.storage)
      ).distinct.size == 6)
      assert(snapshot.flatFsms.count(_.composition.get.slot.contains("first")) == 1)
      assert(snapshot.flatFsms.count(_.composition.get.slot.contains("second")) == 1)

    test("bridge emits one executor per occurrence and closed joins activation"):
      val text = ScalaToMlirBridge.fromSnapshot(capture()).text
      assert(text.linesIterator.count(_.contains("\"nodal.flat_fsm\"")) == 3)
      assert(text.linesIterator.count(_.contains("\"nodal.fsm_join\"")) == 1)
      assert(text.linesIterator.count(_.contains("\"nodal.fsm_activation\"")) == 1)
      assert(text.linesIterator.count(_.contains("contract_version = \"flat-fsm-v3\"")) == 3)
      assert(text == ScalaToMlirBridge.fromSnapshot(capture()).text)

    test("Any and ordered completion preserve policies while Exclusive overlap rejects"):
      val any = ScalaToMlirBridge.fromSnapshot(capture("any")).text
      val priority = ScalaToMlirBridge.fromSnapshot(capture("priority")).text
      assert(any.contains("mode = \"any\""))
      assert(priority.linesIterator.count(_.contains("\"nodal.fsm_join\"")) == 2)
      assert(priority.contains("selection = \"one_child\""))
      rejected(capture("exclusive"))

    test("same enum foreign slot and repeated ownership reject"):
      val foreignOwner = FsmDefinition[ComposedFsmMode]("foreign")
      val foreign = FsmChild(foreignOwner, "first", ComposedFsmGraphs.leaf)
      rejected(FsmDef(ComposedFsmGraphs.parentOwner, ComposedFsmMode.Work)(FsmFragment("foreign")(
        FsmState(ComposedFsmMode.Work).children(foreign).onAllComplete()(ComposedFsmMode.Done),
        FsmState(ComposedFsmMode.Done).terminal()
      )))
      rejected(FsmState(ComposedFsmMode.Work).children(
        ComposedFsmGraphs.first,
        ComposedFsmGraphs.first
      ))
      rejected(FsmDef(
        ComposedFsmGraphs.parentOwner,
        ComposedFsmMode.Work
      )(FsmFragment("terminal-child")(
        FsmState(
          ComposedFsmMode.Work
        ).children(ComposedFsmGraphs.first).onAllComplete()(ComposedFsmMode.Done),
        FsmState(ComposedFsmMode.Done).children(ComposedFsmGraphs.second).terminal()
      )))

    test("forged actual child control owner and completion mode reject in bridge"):
      val original = capture()
      val parent = original.flatFsms.find(_.composition.get.parent.isEmpty).get
      val child = original.flatFsms.find(_.composition.get.parent.nonEmpty).get
      rejected(ScalaToMlirBridge.fromSnapshot(original.copy(flatFsms =
        original.flatFsms.map(value =>
          if value.path == child.path then
            value.copy(control =
              value.control.map(control => control.copy(restart = control.enable))
            )
          else value
        )
      )))
      rejected(ScalaToMlirBridge.fromSnapshot(original.copy(flatFsms =
        original.flatFsms.map(value =>
          if value.path == parent.path then
            value.copy(composition =
              value.composition.map(composition =>
                composition.copy(joins = composition.joins.map(_.copy(policy = "eventually")))
              )
            )
          else value
        )
      )))

    test("nested ownership and observations retain one separate register pair per occurrence"):
      val snapshot = capture("nested")
      assert(snapshot.flatFsms.size == 4)
      val root = snapshot.flatFsms.find(_.composition.get.parent.isEmpty).get
      assert(root.composition.get.occurrences == 4, root.composition.get.depth == 3)
      assert(ScalaToMlirBridge.fromSnapshot(
        snapshot
      ).text.linesIterator.count(_.contains("\"nodal.fsm_activation\"")) == 2)
      rejected(capture("escape"))

    test("artifact keeps immutable provenance and independently checked bounded facts"):
      val snapshot = capture()
      val root = snapshot.flatFsms.find(_.composition.get.parent.isEmpty).get
      val facts = root.composition.get
      val children = snapshot.flatFsms.filter(_.composition.get.parent.nonEmpty)
      assert(facts.occurrences == 3, facts.depth == 2, facts.completionPossible)
      assert(children.map(_.composition.get.definitionHash).distinct.size == 1)
      val artifact = ReproducibilityContract.canonicalSnapshot(snapshot)
      assert(
        artifact.contains("structural_completion_possible"),
        artifact.contains(facts.definitionHash)
      )
      assert(artifact.contains("storage-may-be-retained-or-uninitialized"))
      val forged = snapshot.copy(flatFsms =
        snapshot.flatFsms.map(machine =>
          if machine.path == root.path then
            machine.copy(composition = Some(facts.copy(occurrences = 1)))
          else machine
        )
      )
      rejected(ScalaToMlirBridge.fromSnapshot(forged))
      assert(ReproducibilityContract.canonicalSnapshot(forged) != artifact)

    test("guard-insensitive completion admission rejects impossible All but allows Any loser"):
      val owner = FsmDefinition[ComposedFsmMode]("nonterminating")
      val next = FsmGuard(owner, "next")
      val background = FsmDef(owner, ComposedFsmMode.Work)(FsmFragment("loop")(
        FsmState(ComposedFsmMode.Work).on(next)(ComposedFsmMode.Done),
        FsmState(ComposedFsmMode.Done).on(next)(ComposedFsmMode.Work)
      ))
      val parent = FsmDefinition[ComposedFsmMode]("join-proof")
      val finite = FsmChild(parent, "finite", ComposedFsmGraphs.leaf)
      val forever = FsmChild(parent, "forever", background)
      val work = FsmState(ComposedFsmMode.Work).children(finite, forever)
      rejected(FsmDef(parent, ComposedFsmMode.Work)(FsmFragment("all")(
        work.onAllComplete()(ComposedFsmMode.Done),
        FsmState(ComposedFsmMode.Done).terminal()
      )))
      val any = FsmDef(parent, ComposedFsmMode.Work)(FsmFragment("any")(
        work.onAnyComplete()(ComposedFsmMode.Done),
        FsmState(ComposedFsmMode.Done).terminal()
      ))
      assert(any.children.size == 2)

    test("shared immutable reuse charges every finite occurrence before capture"):
      def parent(child: FsmDef[ComposedFsmMode], ordinal: Int): FsmDef[ComposedFsmMode] =
        val owner = FsmDefinition[ComposedFsmMode](s"bounded-$ordinal")
        val first = FsmChild(owner, "first", child)
        val second = FsmChild(owner, "second", child)
        FsmDef(owner, ComposedFsmMode.Work)(FsmFragment("join")(
          FsmState(ComposedFsmMode.Work).children(
            first,
            second
          ).onAllComplete()(ComposedFsmMode.Done),
          FsmState(ComposedFsmMode.Done).terminal()
        ))
      var definition = ComposedFsmGraphs.leaf
      (1 to 11).foreach(index => definition = parent(definition, index))
      assert(definition.compositionCost.occurrences == 4095)
      rejected(parent(definition, 12))

    test("repeated joins charge every selected child before expanded operand capture"):
      val owner = FsmDefinition[ComposedFsmMode]("join-budget")
      val children = Vector.tabulate(256)(index =>
        FsmChild(owner, s"child-$index", ComposedFsmGraphs.leaf)
      )
      def definition(joins: Int): FsmDef[ComposedFsmMode] =
        val initial = FsmState(ComposedFsmMode.Work).children(children*)
        val work = (0 until joins).foldLeft(initial)((state, _) =>
          state.onAllComplete(TransitionMode.Priority)(ComposedFsmMode.Done)
        )
        FsmDef(owner, ComposedFsmMode.Work)(FsmFragment("bounded")(
          work,
          FsmState(ComposedFsmMode.Done).terminal()
        ))
      val positive = definition(128)
      assert(positive.compositionCost.occurrences == 257)
      assert(positive.compositionCost.records > 256 * 128)
      val failure = scala.util.Try(definition(256)).failed.get
      assert(failure.isInstanceOf[ConstructionException])
      assert(failure.getMessage.contains("including join operands"))
      val snapshot = capture()
      val parent = snapshot.flatFsms.find(_.composition.get.parent.isEmpty).get
      val facts = parent.composition.get
      val hugeJoin =
        facts.joins.head.copy(children = Vector.fill(65536)(facts.joins.head.children.head))
      val forged = snapshot.copy(flatFsms =
        snapshot.flatFsms.map(machine =>
          if machine.path == parent.path then
            machine.copy(composition = Some(facts.copy(joins = Vector(hugeJoin))))
          else machine
        )
      )
      val bridgeFailure = scala.util.Try(ScalaToMlirBridge.fromSnapshot(forged)).failed.get
      assert(bridgeFailure.isInstanceOf[BridgeException])
      assert(bridgeFailure.getMessage.contains("bounded join operand inventory"))
