package nodal.internal.testkit

import java.nio.file.{Files, Path}

import nodal.*
import nodal.internal.bridge.*
import utest.*

final class OwnedEventConstantMap extends Module:
  val trigger = in(Bool)
  val input = in(Vec(Bool, 4))
  val result = out(Vec(Bool, 4))
  lowlevel.process(trigger.rising):
    result := input.map(_ => true.B)

final class OwnedEventCapturedMap extends Module:
  val trigger = in(Bool)
  val input = in(Vec(Bool, 4))
  val observed = in(Bool)
  val result = out(Vec(Bool, 4))
  lowlevel.process(trigger.rising):
    result := input.map(_ => observed)

final class OwnedEventForeignCapturedMap extends Module:
  val trigger = in(Bool)
  val input = in(Vec(Bool, 4))
  val ordinary = ClockDomain.external(
    "ordinary",
    ClockEdge.Rising,
    ResetPolicy.None,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  var sourceValue = Option.empty[Signal[Bool]]
  ordinary:
    sourceValue = Some(in(Bool))
  val observed = sourceValue.get
  val result = out(Vec(Bool, 4))
  lowlevel.process(trigger.rising):
    result := input.map(_ => observed)

final class OrdinaryConstantMapSampling(used: Boolean) extends Module:
  val source = ClockDomain.external(
    "source",
    ClockEdge.Rising,
    ResetPolicy.None,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val destination = ClockDomain.external(
    "destination",
    ClockEdge.Rising,
    ResetPolicy.None,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  var sourceValue = Option.empty[Signal[Vec[Bool]]]
  source:
    sourceValue = Some(in(Vec(Bool, 4)))
  val input = sourceValue.get
  val mapped = if used then input.map(value => value) else input.map(_ => true.B)
  destination:
    val state = Reg.uninitialized(Vec(Bool, 4))
    state := mapped

object Increment57EventCollectionSourceTests extends TestSuite:
  val tests = Tests:
    test("constant map excludes unused input but retains real free captures"):
      val constant = ConstructionKernel.inspect(new OwnedEventConstantMap)
      assert(constant.eventProcesses.head.externalCaptures.isEmpty)
      assert(ScalaToMlirBridge.fromSnapshot(constant).text.contains("nodal.collection"))
      val root = constant.modules.head
      val attributed = root.copy(declarations =
        root.declarations.map(value =>
          if value.name == "input" then value.copy(resolvedDomain = Some("invented")) else value
        )
      )
      val invalid = scala.util.Try(ScalaToMlirBridge.fromSnapshot(
        constant.copy(modules = Vector(attributed))
      )).failed.get
      assert(invalid.getMessage.contains("domain"))
      val captured = ConstructionKernel.inspect(new OwnedEventCapturedMap)
      assert(captured.eventProcesses.head.externalCaptures ==
        Vector("OwnedEventCapturedMap.observed"))
      val error =
        scala.util.Try(ConstructionKernel.inspect(new OwnedEventForeignCapturedMap)).failed.get
      assert(error.getMessage.contains("clocked"))
    test("unused seed and dead pure capture retain operands without sampling them"):
      val folded = ConstructionKernel.inspect(new OwnedEventConstantFold)
      assert(folded.eventProcesses.head.externalCaptures.isEmpty)
      assert(ScalaToMlirBridge.fromSnapshot(folded).text.contains("fold_left"))
      val dead = ConstructionKernel.inspect(new OwnedEventDeadCaptureMap)
      assert(dead.eventProcesses.head.externalCaptures.isEmpty)
      assert(ScalaToMlirBridge.fromSnapshot(dead).text.contains("nodal.collection"))
      val alias = ConstructionKernel.inspect(new OwnedEventAliasConstantMap)
      assert(alias.eventProcesses.head.externalCaptures.isEmpty)
      assert(ScalaToMlirBridge.fromSnapshot(alias).text.contains("nodal.collection"))
      val unused = ConstructionKernel.inspect(new OwnedEventUnusedRootInput)
      assert(unused.eventProcesses.head.externalCaptures.isEmpty)
      assert(ScalaToMlirBridge.fromSnapshot(unused).text.contains("domain = @none"))
      val root = unused.modules.head
      val malformed = root.copy(declarations =
        root.declarations.map(value =>
          if value.name == "unused" then value.copy(dataType = Some("Vec(Real;4)")) else value
        )
      )
      val invalid = scala.util.Try(ScalaToMlirBridge.fromSnapshot(
        unused.copy(modules = Vector(malformed))
      )).failed.get
      assert(invalid.getMessage.contains("finite"))
      val oversized =
        scala.util.Try(ConstructionKernel.inspect(new OwnedEventOversizedCapture)).failed.get
      assert(oversized.getMessage.contains("4096 bits"))

    test("ordinary sampling uses the same actual yielded collection dependency graph"):
      val constant = ConstructionKernel.inspect(new OrdinaryConstantMapSampling(false))
      assert(constant.digitalAssignments.size == 1)
      val error =
        scala.util.Try(ConstructionKernel.inspect(new OrdinaryConstantMapSampling(true))).failed.get
      assert(error.getMessage.contains("domain"))

object Increment57EventCollectionExport:
  def main(args: Array[String]): Unit =
    val destination = Files.createDirectories(Path.of(args.head))
    val _ = Files.writeString(
      destination.resolve("constant_map.mlir"),
      ScalaToMlirBridge.lower(new OwnedEventConstantMap).text
    )
    val _ = Files.writeString(
      destination.resolve("captured_map.mlir"),
      ScalaToMlirBridge.lower(new OwnedEventCapturedMap).text
    )

final class OwnedEventConstantFold extends Module:
  val trigger = in(Bool)
  val input = in(Vec(Bool, 4))
  val seed = in(Bool)
  val result = out(Bool)
  lowlevel.process(trigger.rising):
    result := input.fold(seed)((_, _) => true.B)

final class OwnedEventDeadCaptureMap extends Module:
  val trigger = in(Bool)
  val input = in(Vec(Bool, 4))
  val observed = in(Bool)
  val result = out(Vec(Bool, 4))
  lowlevel.process(trigger.rising):
    result := input.map: value =>
      value && observed
      true.B

object Increment57EventUnusedOperandExport:
  def main(args: Array[String]): Unit =
    val destination = Files.createDirectories(Path.of(args.head))
    val _ = Files.writeString(
      destination.resolve("constant_fold.mlir"),
      ScalaToMlirBridge.lower(new OwnedEventConstantFold).text
    )
    val _ = Files.writeString(
      destination.resolve("dead_capture_map.mlir"),
      ScalaToMlirBridge.lower(new OwnedEventDeadCaptureMap).text
    )
    val _ = Files.writeString(
      destination.resolve("alias_constant_map.mlir"),
      ScalaToMlirBridge.lower(new OwnedEventAliasConstantMap).text
    )
    val _ = Files.writeString(
      destination.resolve("unused_root_input.mlir"),
      ScalaToMlirBridge.lower(new OwnedEventUnusedRootInput).text
    )

final class OwnedEventAliasConstantMap extends Module:
  val trigger = in(Bool)
  val input = in(Vec(Bool, 4))
  val result = out(Vec(Bool, 4))
  lowlevel.process(trigger.rising):
    result := input.slice(0, 4).map(_ => true.B)

final class OwnedEventUnusedRootInput extends Module:
  val trigger = in(Bool)
  val unused = in(UInt(8192))
  val result = out(Bool)
  lowlevel.process(trigger.rising):
    result := true.B

final class OwnedEventOversizedCapture extends Module:
  val trigger = in(Bool)
  val input = in(Vec(Bool, 4097))
  val result = out(Bool)
  lowlevel.process(trigger.rising):
    result := input.at(0)
