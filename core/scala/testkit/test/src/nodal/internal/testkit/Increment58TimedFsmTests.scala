package nodal.internal.testkit

import nodal.*
import nodal.internal.bridge.*
import nodal_external.*
import utest.*

object Increment58TimedFsmTests extends TestSuite:
  private def capture(profile: String = "timer"): ConstructionSnapshot =
    ConstructionKernel.inspect(new Increment58TimedFsmSource(profile))
  private def rejected(body: => Any): Unit =
    val error = scala.util.Try(body).failed.get
    assert(error.isInstanceOf[ConstructionException] || error.isInstanceOf[BridgeException])
  val tests = Tests:
    test("immutable reused timers retain separate exact private counters"):
      val snapshot = capture()
      assert(snapshot.flatFsms.size == 2)
      assert(snapshot.flatFsms.flatMap(_.timerStorage).distinct.size == 2)
      snapshot.flatFsms.foreach: machine =>
        assert(machine.timerEdges.map(_.cycles) == Vector(3))
        assert(machine.transferEdges.isEmpty)
        val storage =
          snapshot.modules.flatMap(_.declarations).find(_.path == machine.timerStorage.get).get
        assert(storage.dataType.contains("UInt(31)"), storage.domain.contains(machine.domain))
      val text = ScalaToMlirBridge.fromSnapshot(snapshot).text
      assert(text.linesIterator.count(_.contains("\"nodal.fsm_elapsed\"")) == 2)
      assert(text.linesIterator.count(_.contains("contract_version = \"flat-fsm-v4\"")) == 2)
      assert(text == ScalaToMlirBridge.fromSnapshot(capture()).text)
    test("one-cycle and legacy live timers share the same captured timing contract"):
      assert(capture("one").flatFsms.forall(_.timerEdges.map(_.cycles) == Vector(1)))
      val live = capture("live")
      assert(live.flatFsms.size == 1, live.flatFsms.head.control.isEmpty)
      assert(live.flatFsms.head.timerEdges.map(_.cycles) == Vector(3))
      assert(ScalaToMlirBridge.fromSnapshot(live).text.contains("lifecycle_mode = \"legacy\""))
    test("transfer wait retains exact carrier evidence without adding timer storage"):
      val snapshot = capture("transfer")
      assert(snapshot.flatFsms.size == 2)
      assert(snapshot.flatFsms.forall(_.timerStorage.isEmpty))
      val edges = snapshot.flatFsms.flatMap(_.transferEdges)
      assert(edges.size == 2, edges.map(_.payload).distinct.size == 1)
      assert(edges.map(_.valid).distinct.size == 1, edges.map(_.ready).distinct.size == 1)
      assert(edges.forall(edge => edge.valid != edge.ready))
      assert(ScalaToMlirBridge.fromSnapshot(snapshot).text.contains("\"nodal.fsm_transfer\""))
    test("invalid thresholds and foreign definition transfer slots reject"):
      rejected(TimedFsmGraphs.timer(0))
      rejected(TimedFsmGraphs.timer(-1))
      val other = FsmDefinition[TimedFsmMode]("other")
      val slot = FsmTransfer(other, "transfer")
      rejected(FsmDef(TimedFsmGraphs.owner, TimedFsmMode.Wait)(FsmFragment("foreign")(
        FsmState(TimedFsmMode.Wait).awaitTransfer(slot)(TimedFsmMode.Done),
        FsmState(TimedFsmMode.Done).terminal()
      )))
    test("a transfer cannot import a carrier from another construction"):
      var prior = Option.empty[Stream[UInt]]
      val _ = ConstructionKernel.inspect {
        val source = new Increment58TimedFsmSource("transfer")
        prior = source.exported
        source
      }
      assert(prior.nonEmpty)
      rejected(ConstructionKernel.inspect(new Increment58TimedFsmSource("transfer", prior)))
    test("resetless timer storage is not fabricated as initialized"):
      val snapshot = capture("resetless")
      snapshot.flatFsms.foreach: machine =>
        val storage =
          snapshot.modules.flatMap(_.declarations).find(_.path == machine.timerStorage.get).get
        assert(!storage.attributes.contains("initial"))
      assert(ScalaToMlirBridge.fromSnapshot(snapshot).text.contains("flat-fsm-v4"))
    test("bridge independently rejects changed timer thresholds and private storage"):
      val original = capture()
      val first = original.flatFsms.head
      rejected(ScalaToMlirBridge.fromSnapshot(original.copy(flatFsms =
        original.flatFsms.map(machine =>
          if machine.path == first.path then
            machine.copy(timerEdges = machine.timerEdges.map(_.copy(cycles = 0)))
          else machine
        )
      )))
      rejected(ScalaToMlirBridge.fromSnapshot(original.copy(flatFsms =
        original.flatFsms.map(machine =>
          if machine.path == first.path then machine.copy(timerStorage = Some(machine.storage))
          else machine
        )
      )))

    test("maximum positive threshold remains one bounded counter per occurrence"):
      val snapshot = capture("maximum")
      assert(snapshot.flatFsms.forall(_.timerEdges.map(_.cycles) == Vector(Int.MaxValue)))
      assert(snapshot.flatFsms.flatMap(_.timerStorage).distinct.size == 2)
      assert(ScalaToMlirBridge.fromSnapshot(snapshot).text.contains("2147483647"))
    test("formal required timers retain contextual initialization through mixed bindings"):
      val snapshot = ConstructionKernel.inspect(new Increment58RequiredTimedFsmSource)
      assert(snapshot.flatFsms.size == 2)
      val paths = snapshot.flatFsms.flatMap(_.timerStorage).toSet
      val timers = snapshot.modules.flatMap(_.declarations).filter(d => paths(d.path))
      assert(timers.size == 2)
      assert(timers.forall(_.attributes.contains("initializer_policy" -> "if_reset")))
      val text = ScalaToMlirBridge.fromSnapshot(snapshot).text
      assert(text.linesIterator.count(_.contains("\"nodal.flat_fsm\"")) == 1)
      assert(text.linesIterator.count(line =>
        line.contains("\"nodal.digital_storage\"") &&
          line.contains("initializer_policy = \"if_reset\"")
      ) == 3)
