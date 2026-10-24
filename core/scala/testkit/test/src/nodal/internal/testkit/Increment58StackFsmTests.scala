package nodal.internal.testkit

import nodal.*
import nodal.internal.bridge.*
import nodal_external.*
import utest.*

object Increment58StackFsmTests extends TestSuite:
  private def capture(profile: String = "trap"): ConstructionSnapshot =
    ConstructionKernel.inspect(new Increment58StackFsmSource(profile))
  private def rejected(part: String)(body: => Any): Unit =
    val failure = scala.util.Try(body).failed.get
    assert(failure.getMessage.contains(part))
  val tests = Tests:
    test("typed procedure graph retains authored guards and dynamic return without fake targets"):
      val graph = StackFsmGraphs.graph()
      assert(graph.guardSlots == Vector(
        StackFsmGraphs.emptyReturn,
        StackFsmGraphs.call,
        StackFsmGraphs.recurse,
        StackFsmGraphs.ret
      ))
      assert(graph.compositionCost.completionPossible)
      val snapshot = capture()
      val machine = snapshot.flatFsms.head
      assert(machine.transitions.map(_.kind) ==
        Vector("return", "call", "call", "return", "ordinary", "ordinary"))
      assert(machine.transitions.filter(_.kind == "return").forall(edge =>
        edge.destinationCase.isEmpty && edge.resumeCase.isEmpty
      ))
      assert(ScalaToMlirBridge.fromSnapshot(snapshot).text.contains("flat-fsm-v5"))
    test("capacity bounds and pointer widths are exact without frame initialization"):
      Vector("one" -> (1, 1), "trap" -> (2, 2), "maximum" -> (128, 8)).foreach:
        (profile, expected) =>
          val snapshot = capture(profile)
          val stack = snapshot.flatFsms.head.stack.get
          assert(stack.capacity == expected._1, stack.pointerWidth == expected._2)
          assert(stack.frameStorage.size == expected._1)
          val declarations = snapshot.modules.flatMap(_.declarations)
          stack.frameStorage.foreach: path =>
            val value = declarations.find(_.path == path).get
            assert(value.attributes.contains("initial" -> "none"))
            assert(!value.attributes.exists(_._1 == "initializer_policy"))
          assert(ScalaToMlirBridge.fromSnapshot(snapshot).text.contains("pointer_width"))
      Vector(-1, 0, 129).foreach(value =>
        rejected("capacity 1 through 128")(StackFsmGraphs.graph(value))
      )
    test("call and return reject without an admitted immutable stack"):
      val graph = StackFsmGraphs.graph()
      rejected("procedure kind or stack configuration")(
        FsmDef(StackFsmGraphs.owner, StackFsmMode.Main)(graph.fragments*)
      )
    test("one hot stack frames use exact local width rather than canonical enum width"):
      val snapshot = capture("one-hot")
      val machine = snapshot.flatFsms.head
      assert(machine.localWidth == 5)
      val frames = machine.stack.get.frameStorage.toSet
      assert(snapshot.modules.flatMap(_.declarations).filter(value => frames(value.path))
        .forall(_.dataType.contains("Bits(5)")))
      assert(ScalaToMlirBridge.fromSnapshot(snapshot).text.contains("flat-fsm-v5"))
    test("resetless pointer and frames stay deliberately uninitialized"):
      val snapshot = capture("resetless")
      val stack = snapshot.flatFsms.head.stack.get
      val paths = (stack.frameStorage :+ stack.pointerStorage).toSet
      assert(snapshot.modules.flatMap(_.declarations).filter(value => paths(value.path))
        .forall(_.attributes.contains("initial" -> "none")))
      assert(ScalaToMlirBridge.fromSnapshot(snapshot).text.contains("flat-fsm-v5"))
    test("formal mixed reset reuse conditionally initializes pointer but never frames"):
      val snapshot = ConstructionKernel.inspect(new Increment58RequiredStackFsmSource)
      assert(snapshot.flatFsms.size == 2)
      val declarations = snapshot.modules.flatMap(_.declarations)
      snapshot.flatFsms.foreach: machine =>
        val stack = machine.stack.get
        assert(declarations.find(_.path == stack.pointerStorage).get.attributes
          .contains("initializer_policy" -> "if_reset"))
        stack.frameStorage.foreach(path =>
          assert(!declarations.find(_.path == path).get.attributes
            .exists(_._1 == "initializer_policy"))
        )
      assert(ScalaToMlirBridge.fromSnapshot(
        snapshot
      ).text.linesIterator.count(_.contains("\"nodal.flat_fsm\"")) == 1)
    test("composition retains actual stack child and canonical completion summary"):
      val snapshot = ConstructionKernel.inspect(new Increment58ComposedStackFsmSource)
      assert(snapshot.flatFsms.size == 2)
      assert(snapshot.flatFsms.count(_.stack.nonEmpty) == 1)
      assert(snapshot.flatFsms.forall(_.composition.get.completionPossible))
      val text = ScalaToMlirBridge.fromSnapshot(snapshot).text
      assert(text.contains("lifecycle_mode = \"composed\""), text.contains("\"nodal.fsm_join\""))
    test("bridge independently rejects malformed stack roles and dynamic edge claims"):
      val source = capture()
      val machine = source.flatFsms.head
      val stack = machine.stack.get
      def reject(change: KernelFlatFsmSnapshot => KernelFlatFsmSnapshot): Unit =
        val error = scala.util.Try(ScalaToMlirBridge.fromSnapshot(
          source.copy(flatFsms = Vector(change(machine)))
        )).failed.get
        assert(error.isInstanceOf[BridgeException])
      reject(_.copy(stack = Some(stack.copy(capacity = 0))))
      reject(_.copy(stack = Some(stack.copy(pointerWidth = 3))))
      reject(_.copy(stack = Some(stack.copy(frameStorage = stack.frameStorage.take(1)))))
      reject(_.copy(stack =
        Some(stack.copy(frameStorage = Vector(stack.pointerStorage, stack.frameStorage.head)))
      ))
      reject(_.copy(stack = Some(stack.copy(overflow = Some("absent")))))
      reject(_.copy(stack = None))
      reject(_.copy(transitions =
        machine.transitions.updated(
          0,
          machine.transitions(0).copy(destinationCase = Some(machine.initialCase))
        )
      ))
      reject(_.copy(transitions =
        machine.transitions.updated(
          1,
          machine.transitions(1).copy(resumeCase = None)
        )
      ))
      reject(_.copy(transitions =
        machine.transitions.updated(
          0,
          machine.transitions(0).copy(kind = "invented")
        )
      ))
    test("bounded summaries match returns and never treat a cleared continuation as reachable"):
      import FlatFsmGraphContract.{Edge, Stack, validateControlStructure}
      def fail(message: String): Nothing = FsmDefinitionRuntime.fail(message)
      val priority = TransitionMode.Priority
      val recoveryOnly = validateControlStructure(
        Set("loop", "done"),
        "loop",
        Vector("loop" -> false, "done" -> true),
        Vector(Edge("loop", Some("loop"), priority)),
        Some(Stack(1, None, None, Some("done"))),
        fail
      )
      assert(!recoveryOnly.completionPossible)
      rejected("unreachable states under bounded call-return matching")(
        validateControlStructure(
          Set("main", "sub", "resume", "done"),
          "main",
          Vector("main" -> false, "sub" -> false, "resume" -> false, "done" -> true),
          Vector(
            Edge("main", Some("sub"), priority, "call", Some("resume")),
            Edge("sub", Some("sub"), priority, "call", Some("sub")),
            Edge("resume", Some("done"), priority)
          ),
          Some(Stack(2, Some("done"), None, None)),
          fail
        )
      )
      val states = Vector.tabulate(200)(index => index.toString -> false)
      rejected("65536 state-depth")(
        validateControlStructure(
          states.map(_._1).toSet,
          "0",
          states,
          states.map((id, _) => Edge(id, Some(((id.toInt + 1) % 200).toString), priority)),
          Some(Stack(128, None, None, None)),
          fail
        )
      )
