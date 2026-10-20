package nodal.internal.testkit

import java.nio.file.{Files, Path}

import contracts.clockreset.positive.LowLevelEventFixture
import nodal.*
import nodal.internal.bridge.*
import utest.*

final class OwnedEventBool extends Module:
  val trigger = in(Bool)
  val result = out(Bool)
  lowlevel.process(trigger.rising):
    result := true.B

final class OwnedEventPair extends Module:
  val trigger = in(Bool)
  val seed = in(Bool)
  val input = in(Bool)
  val first = out(Bool)
  val second = out(Bool)
  lowlevel.process(trigger.rising):
    first := select(seed, input, second)
    second := select(seed, !input, first)

final class OwnedEventU8Pair(rising: Boolean) extends Module:
  val trigger = in(Bool)
  val seed = in(Bool)
  val first = in(UInt(8))
  val second = in(UInt(8))
  val left = out(UInt(8))
  val right = out(UInt(8))
  val selected = if rising then trigger.rising else trigger.falling
  lowlevel.process(selected):
    left := select(seed, first, right)
    right := select(seed, second, left)

final class OwnedEventPayload[A <: Data](dataType: DataType[A]) extends Module:
  val trigger = in(Bool)
  val input = in(dataType)
  val result = out(dataType)
  lowlevel.process(trigger.falling):
    result := input

final class OwnedEventMap extends Module:
  val trigger = in(Bool)
  val input = in(Vec(Bool, 4))
  val result = out(Vec(Bool, 4))
  lowlevel.process(trigger.rising):
    result := input.map(value => !value)

final class OwnedEventScale(count: Int) extends Module:
  val trigger = in(Bool)
  for _ <- 0 until count do
    val output = out(Bool)
    lowlevel.process(trigger.rising):
      output := true.B

final class OwnedEventSelectedHeader(rising: Boolean) extends Module:
  val trigger = in(Bool)
  val output = out(Bool)
  val selected = if rising then trigger.rising else trigger.falling
  lowlevel.process(selected):
    output := true.B

object OwnedEventConstants:
  val one = 1
  val two = one + 1

final class OwnedEventConstantRead extends Module:
  val trigger = in(Bool)
  val result = out(UInt(8))
  lowlevel.process(trigger.rising):
    result := OwnedEventConstants.two.U(8)

final class OwnedEventStateBudget(count: Int) extends Module:
  val trigger = in(Bool)
  val input = in(Bits(4096))
  for _ <- 0 until count do
    val output = out(Bits(4096))
    lowlevel.process(trigger.rising):
      output := input

final class OwnedEventCaptureBudget(count: Int) extends Module:
  val trigger = in(Bool)
  val result = out(Bool)
  var layer: Vector[Expr[Bool]] = Vector.fill(count)(in(Bool))
  while layer.size > 1 do
    layer = layer.grouped(2).map(pair =>
      if pair.size == 1 then pair.head else pair.head || pair(1)
    ).toVector
  val combined = layer.head
  lowlevel.process(trigger.rising):
    result := combined

final class OwnedEventRegister extends Module:
  val trigger = in(Bool)
  val result = out(Bool)
  lowlevel.process(trigger.rising):
    val state = Reg(false.B)
    result := state

final class OwnedEventNested extends Module:
  val trigger = in(Bool)
  val result = out(Bool)
  lowlevel.process(trigger.rising):
    lowlevel.process(trigger.falling):
      result := true.B

final class OwnedEventConditional(enabled: Boolean) extends Module:
  val trigger = in(Bool)
  val result = out(Bool)
  lowlevel.process(trigger.rising):
    if enabled then result := true.B

final class OwnedEventHelperConditional(enabled: Boolean) extends Module:
  val trigger = in(Bool)
  val result = out(Bool)
  private def update(): Unit = if enabled then result := true.B
  lowlevel.process(trigger.rising):
    update()

final class OwnedEventByNameWrapper(enabled: Boolean) extends Module:
  val trigger = in(Bool)
  val result = out(Bool)
  private def run(event: Event)(body: => Unit): Unit = lowlevel.process(event)(body)
  run(trigger.rising):
    if enabled then result := true.B

final class OwnedEventChoosingMatch(choice: Int) extends Module:
  val trigger = in(Bool)
  val result = out(Bool)
  lowlevel.process(trigger.rising):
    choice match
      case 0 => result := true.B
      case _ => result := false.B

final class OwnedEventDiscardBookkeeping extends Module:
  val trigger = in(Bool)
  val result = out(Bool)
  var calls = 0
  lowlevel.process(trigger.rising):
    val _ = { calls += 1 }
    result := true.B

final class OwnedEventMissingCertificate extends Module:
  val trigger = in(Bool)
  val result = out(Bool)
  CandidateRuntime.digitalEventBlock(trigger.rising, result := true.B)

enum OwnedEventMode derives HwEnum:
  case Idle, Run

final class OwnedEventEnum extends Module:
  val trigger = in(Bool)
  val modeType = EnumType[OwnedEventMode]()
  val result = out(modeType)
  lowlevel.process(trigger.rising):
    result := enumLiteral(modeType, OwnedEventMode.Run)

final class OwnedEventParameterlessHelper extends Module:
  val trigger = in(Bool)
  val result = out(Bool)
  private def update: Unit = result := true.B
  lowlevel.process(trigger.rising):
    update

final class OwnedEventCaughtRejection(enabled: Boolean) extends Module:
  val trigger = in(Bool)
  val result = out(Bool)
  try
    lowlevel.process(trigger.rising):
      if enabled then result := true.B
  catch case _: ConstructionException => ()
  result := false.B

final class OwnedEventForeignInitializer extends Module:
  val trigger = in(Bool)
  val result = out(Bool)
  EventControlForeignHooks.target = result
  lowlevel.process(trigger.rising):
    EventControlForeignInitializer.update

final class OwnedEventLoop extends Module:
  val trigger = in(Bool)
  val result = out(Bool)
  lowlevel.process(trigger.rising):
    var count = 0
    while count < 1 do
      count += 1
      result := true.B

final class OwnedEventRepeatedWrite extends Module:
  val trigger = in(Bool)
  val result = out(Bool)
  lowlevel.process(trigger.rising):
    result := true.B
    result := false.B

final class OwnedEventContinuousWriter extends Module:
  val trigger = in(Bool)
  val result = out(Bool)
  lowlevel.process(trigger.rising):
    result := true.B
  result := false.B

final class OwnedEventForeignProcess extends Module:
  val trigger = in(Bool)
  val first = out(Bool)
  val second = out(Bool)
  lowlevel.process(trigger.rising):
    first := true.B
  lowlevel.process(trigger.falling):
    second := first

final class OwnedEventChild extends Module:
  val child = instance(new OwnedEventBool)

final class OwnedEventComputedTrigger extends Module:
  val trigger = in(Bool)
  val result = out(Bool)
  lowlevel.process((!trigger).rising):
    result := true.B

final class OwnedEventProjectedWrite extends Module:
  val trigger = in(Bool)
  val result = out(Vec(Bool, 2))
  lowlevel.process(trigger.rising):
    result.writable.at(0) := true.B

final class OwnedEventSample extends Module:
  val trigger = in(Bool)
  val result = out(Bool)
  lowlevel.process(trigger.rising):
    result := true.B
  val domain = ClockDomain.external(
    "ordinary",
    ClockEdge.Rising,
    ResetPolicy.None,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  domain:
    val state = Reg(false.B)
    state := result.named("event_alias")

object Increment57EventSourceTests extends TestSuite:
  private def rejected(body: => Any, text: String): Unit =
    val error = scala.util.Try(body).failed.get
    assert(error.getMessage.contains(text))

  val tests: Tests = Tests:
    test("root Bool event has owned update identity, exact source, no invented domain"):
      val frozen = ConstructionKernel.inspect(new LowLevelEventFixture)
      assert(frozen.eventProcesses.head.storageTargets == Vector("LowLevelEventFixture.observed"))
      val snapshot = ConstructionKernel.inspect(new OwnedEventBool)
      val process = snapshot.eventProcesses.head
      assert(snapshot.eventProcesses.size == 1)
      assert(process.trigger == "OwnedEventBool.trigger", process.edge == "rising")
      assert(process.storageTargets == Vector("OwnedEventBool.result"))
      assert(process.externalCaptures.isEmpty)
      assert(snapshot.modules.head.domains.isEmpty)
      assert(snapshot.digitalAssignments.head.kind == "event_update")
      assert(snapshot.digitalAssignments.head.eventProcess.contains(process.path))
      assert(snapshot.sourceMap.exists(value =>
        value.semanticPath == process.path &&
          value.source.path.endsWith("Increment57EventSourceTests.scala")
      ))
      val text = ScalaToMlirBridge.lower(new OwnedEventBool).text
      assert(text.contains("\"nodal.event_process\""), text.contains("event_process = @"))
      assert(text.contains("contract = \"root_event_v1\""))
      assert(text.contains("domain = @none"), !text.contains("\"nodal.domain\""))
      assert(text == ScalaToMlirBridge.lower(new OwnedEventBool).text)

    test("same-process old state stays separate from exact external capture inventory"):
      val snapshot = ConstructionKernel.inspect(new OwnedEventPair)
      val process = snapshot.eventProcesses.head
      assert(process.storageTargets == Vector("OwnedEventPair.first", "OwnedEventPair.second"))
      assert(process.externalCaptures.toSet == Set("OwnedEventPair.seed", "OwnedEventPair.input"))
      assert(snapshot.digitalAssignments.forall(_.eventProcess.contains(process.path)))
      val text = ScalaToMlirBridge.lower(new OwnedEventPair).text
      assert(text.contains("simultaneous_pre_event"), text.contains("unspecified"))

    test("falling finite whole payload preserves existing ABI"):
      val scalar = ScalaToMlirBridge.lower(new OwnedEventPayload(UInt(8))).text
      val vector = ScalaToMlirBridge.lower(new OwnedEventPayload(Vec(Bool, 4))).text
      assert(scalar.contains("edge = \"falling\""), vector.contains("edge = \"falling\""))
      val mapped = ConstructionKernel.inspect(new OwnedEventMap)
      assert(mapped.eventProcesses.head.externalCaptures == Vector("OwnedEventMap.input"))
      assert(ScalaToMlirBridge.lower(new OwnedEventMap).text.contains("\"nodal.collection\""))
      rejected(ConstructionKernel.inspect(new OwnedEventPayload(UInt(4097))), "4096")
      val nominal = ConstructionKernel.inspect(new OwnedEventEnum)
      assert(nominal.enumDefinitions.size == 1)
      assert(nominal.eventProcesses.head.externalCaptures.isEmpty)
      assert(ScalaToMlirBridge.fromSnapshot(nominal).text.contains("!nodal.enum"))

    test("independent process inventory grows linearly and remains deterministic"):
      val small = ConstructionKernel.inspect(new OwnedEventScale(8))
      val large = ConstructionKernel.inspect(new OwnedEventScale(32))
      assert(small.eventProcesses.size == 8, large.eventProcesses.size == 32)
      assert(large.digitalAssignments.size == 32)
      assert(large.eventProcesses.flatMap(_.storageTargets).distinct.size == 32)
      assert(large.eventProcesses.forall(_.externalCaptures.isEmpty))
      val first = ScalaToMlirBridge.fromSnapshot(large).text
      assert(first == ScalaToMlirBridge.lower(new OwnedEventScale(32)).text)
      assert(
        ConstructionKernel.inspect(new OwnedEventSelectedHeader(false)).eventProcesses.head.edge ==
          "falling"
      )

    test("whole payload, module state and external capture budgets have exact boundaries"):
      assert(ConstructionKernel.inspect(new OwnedEventStateBudget(64)).eventProcesses.size == 64)
      rejected(ConstructionKernel.inspect(new OwnedEventStateBudget(65)), "262144")
      val captured = ConstructionKernel.inspect(new OwnedEventCaptureBudget(4096))
      assert(captured.eventProcesses.head.externalCaptures.size == 4096)
      rejected(ConstructionKernel.inspect(new OwnedEventCaptureBudget(4097)), "4096 inputs")

    test("untracked state, child construction and invalid triggers reject"):
      rejected(ConstructionKernel.inspect(new OwnedEventRegister), "event")
      rejected(ConstructionKernel.inspect(new OwnedEventNested), "event")
      rejected(ConstructionKernel.inspect(new OwnedEventChild), "root")
      rejected(ConstructionKernel.inspect(new OwnedEventComputedTrigger), "actual root input")
      rejected(ConstructionKernel.inspect(new OwnedEventProjectedWrite), "whole output")

    test("authored control survives Scala elaboration as a rejection"):
      rejected(ConstructionKernel.inspect(new OwnedEventConditional(true)), "NODAL-EVENT")
      rejected(ConstructionKernel.inspect(new OwnedEventHelperConditional(true)), "NODAL-EVENT")
      rejected(ConstructionKernel.inspect(new OwnedEventLoop), "NODAL-EVENT")
      rejected(ConstructionKernel.inspect(new OwnedEventByNameWrapper(true)), "by-name")
      rejected(ConstructionKernel.inspect(new OwnedEventChoosingMatch(0)), "Match")
      rejected(
        ConstructionKernel.inspect(new OwnedEventMissingCertificate),
        "NODAL-EVENT-057-MISSING"
      )
      val discarded = ConstructionKernel.inspect {
        val top = new OwnedEventDiscardBookkeeping
        assert(top.calls == 1)
        top
      }
      assert(discarded.eventProcesses.size == 1)
      assert(ConstructionKernel.inspect(new OwnedEventParameterlessHelper).eventProcesses.size == 1)
      assert(ConstructionKernel.inspect(new OwnedEventConstantRead).eventProcesses.size == 1)
      rejected(
        ConstructionKernel.inspect(new OwnedEventCaughtRejection(true)),
        "captured constructor failure"
      )
      rejected(
        ConstructionKernel.inspect(new OwnedEventForeignInitializer),
        "initialization summary"
      )

    test("output writer ownership and event sampling remain quarantined"):
      rejected(ConstructionKernel.inspect(new OwnedEventRepeatedWrite), "writer")
      rejected(ConstructionKernel.inspect(new OwnedEventContinuousWriter), "writer")
      rejected(ConstructionKernel.inspect(new OwnedEventForeignProcess), "another process")
      rejected(ConstructionKernel.inspect(new OwnedEventSample), "event-owned")

    test("forged or absent bridge process association fails closed"):
      val snapshot = ConstructionKernel.inspect(new OwnedEventBool)
      rejected(
        ScalaToMlirBridge.fromSnapshot(snapshot.copy(eventProcesses = Vector.empty)),
        "process"
      )
      val assignment = snapshot.digitalAssignments.head
      rejected(
        ScalaToMlirBridge.fromSnapshot(snapshot.copy(digitalAssignments =
          Vector(assignment.copy(eventProcess = None))
        )),
        "process"
      )

object Increment57EventSourceExport:
  def main(args: Array[String]): Unit =
    val destination = Path.of(args.head)
    Files.createDirectories(destination)
    Vector(
      "frozen" -> (() => new LowLevelEventFixture),
      "rising" -> (() => new OwnedEventU8Pair(true)),
      "falling" -> (() => new OwnedEventU8Pair(false)),
      "aggregate" -> (() => new OwnedEventPayload(Vec(Bool, 4))),
      "map" -> (() => new OwnedEventMap)
    ).foreach: (name, factory) =>
      val text = ScalaToMlirBridge.lower(factory()).text
      Files.writeString(destination.resolve(name + ".mlir"), text)
