package nodal.internal.testkit

import java.nio.file.{Files, Path}

import nodal.*
import nodal.internal.bridge.*
import utest.*

sealed trait InterfaceObservationSourceLink extends Interface

object InterfaceObservationSourceContracts:
  val definition = Interface[InterfaceObservationSourceLink](
    "InterfaceObservationSourceLink",
    InterfaceMember.stream("request", Bool),
    InterfaceMember.valid("response", Bool),
    InterfaceMember.value("flag", Bool)
  )
  val producer = Role[SourceRole](
    "producer",
    RoleAccess.Master("request"),
    RoleAccess.Master("response"),
    RoleAccess.Out("flag")
  )
  val consumer = Role[SinkRole](
    "consumer",
    RoleAccess.Slave("request"),
    RoleAccess.Slave("response"),
    RoleAccess.In("flag")
  )

final class InterfaceObservationSource(mode: String) extends Module:
  val source = ClockDomain.external(
    "source",
    ClockEdge.Rising,
    ResetPolicy.Sync,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val destination = ClockDomain.external(
    "destination",
    ClockEdge.Rising,
    ResetPolicy.Sync,
    ResetPolarity.ActiveHigh,
    20.MHz
  )
  var input = Option.empty[Expr[Bool]]
  var independent = Option.empty[Expr[Bool]]
  source:
    val value = in(Bool)
    input = Some(value)
    val otherValue = in(Bool)
    independent = Some(otherValue)
  val endpoint = interfacePort(
    InterfaceObservationSourceContracts.definition,
    InterfaceObservationSourceContracts.producer,
    "endpoint",
    destination
  )
  val other = interfacePort(
    InterfaceObservationSourceContracts.definition,
    InterfaceObservationSourceContracts.producer,
    "other",
    destination
  )
  val sink = interfacePort(
    InterfaceObservationSourceContracts.definition,
    InterfaceObservationSourceContracts.consumer,
    "sink",
    destination
  )
  destination:
    val first = Cdc.sync(input.get, destination)
    val second = if mode.endsWith("shared") then first
    else
      Cdc.sync(
        if mode.endsWith("independent-sources") then independent.get else input.get,
        destination
      )
    if mode.startsWith("output") then
      val output = out(Bool)
      if mode == "output-separate" then
        val another = out(Bool)
        output := first
        another := second
      else if mode.startsWith("output-selector") then output := select(first, second, false.B)
      else if mode.startsWith("output-wire") then
        val value = wire(Bool)
        value := first && second
        output := value
      else output := first && second
    else if mode.startsWith("resolved") then
      val pad = digitalInout(
        UInt(1),
        DriveMode.pushPull,
        InoutPlacement.TopLevelPin,
        ResolutionProfile.PortableBoundaryOnly,
        "pad"
      )
      val alias = digitalInout(
        UInt(1),
        DriveMode.pushPull,
        InoutPlacement.HierarchyPassThrough,
        ResolutionProfile.PortableBoundaryOnly,
        "alias"
      )
      alias.drive(select(first, 1.U(1), 0.U(1)), second)
      passThrough(pad, alias)
    else if mode == "separate-ready" then
      endpoint.driveMember("request.payload", first)
      endpoint.inverted.driveMember("request.ready", second)
    else if mode == "separate-members" then
      endpoint.driveMember("request.payload", first)
      endpoint.driveMember("response.valid", second)
    else if mode == "separate-endpoints" then
      endpoint.driveMember("request.payload", first)
      other.driveMember("request.valid", second)
    else if mode.startsWith("member-selector") then
      endpoint.driveMember("flag", select(first, second, false.B))
    else
      endpoint.driveMember("request.payload", first)
      endpoint.driveMember("request.valid", second)
      if mode.startsWith("connected") then endpoint.connectExact(sink)
      if mode.endsWith("shared") then endpoint.monitorView.observeMember("request")

object Increment57InterfaceObservationSourceTests extends TestSuite:
  private def capture(mode: String) =
    ConstructionKernel.inspect(new InterfaceObservationSource(mode))
  private def reject(mode: String): Unit =
    val result = scala.util.Try(capture(mode))
    assert(result.isFailure, result.failed.get.getMessage.contains("NODAL-CDC-057-002"))
  val tests = Tests:
    test("unused protocol payload and valid reject independent shared-root chains"):
      reject("protocol")
      reject("connected")
    test("one reused chain survives protocol aliases and connected boundaries"):
      Vector("protocol-shared", "connected-shared").foreach(mode =>
        assert(ScalaToMlirBridge.fromSnapshot(capture(mode)).text.contains("nodal.crossing"))
      )
    test("ready unrelated members and endpoints remain independent observations"):
      Vector("separate-ready", "separate-members", "separate-endpoints").foreach(mode =>
        assert(capture(mode).digitalExpressions.count(_.crossing.nonEmpty) == 2)
      )
    test("independent source roots may share one protocol or selector observation"):
      Vector("protocol-independent-sources", "output-selector-independent-sources").foreach(mode =>
        assert(capture(mode).digitalExpressions.count(_.crossing.nonEmpty) == 2)
      )
    test("otherwise unused ordinary outputs are actual observed values"):
      reject("output")
      reject("output-wire")
      assert(capture("output-shared").digitalExpressions.count(_.crossing.nonEmpty) == 1)
      assert(capture("output-separate").digitalExpressions.count(_.crossing.nonEmpty) == 2)
    test("selectors retain causal crossing inputs at outputs and interface members"):
      reject("output-selector")
      reject("member-selector")
      assert(capture("output-selector-shared").digitalExpressions.count(_.crossing.nonEmpty) == 1)
      assert(capture("member-selector-shared").digitalExpressions.count(_.crossing.nonEmpty) == 1)
    test("resolved aliases observe real data and enable dependencies together"):
      reject("resolved")
      assert(capture("resolved-shared").digitalExpressions.count(_.crossing.nonEmpty) == 1)

object Increment57InterfaceObservationReplay:
  val positives: Vector[String] = Vector(
    "protocol-shared",
    "connected-shared",
    "separate-ready",
    "separate-members",
    "separate-endpoints",
    "output-shared",
    "output-separate",
    "output-selector-shared",
    "member-selector-shared",
    "resolved-shared",
    "protocol-independent-sources",
    "output-selector-independent-sources"
  )
  def sharedSourceMutation(snapshot: ConstructionSnapshot): ConstructionSnapshot =
    val crossings = snapshot.digitalExpressions.filter(_.crossing.nonEmpty)
    require(crossings.size == 2)
    snapshot.copy(digitalExpressions =
      snapshot.digitalExpressions.map(value =>
        if value.path == crossings.last.path then value.copy(operands = crossings.head.operands)
        else value
      )
    )
  def main(args: Array[String]): Unit =
    val directory = Files.createDirectories(Path.of(args(0)))
    positives.foreach: mode =>
      val snapshot = ConstructionKernel.inspect(new InterfaceObservationSource(mode))
      val document = ScalaToMlirBridge.fromSnapshot(snapshot)
      val _ = Files.writeString(directory.resolve(mode + ".mlir"), document.text)
    Vector("protocol", "output-selector").foreach: mode =>
      val snapshot =
        ConstructionKernel.inspect(new InterfaceObservationSource(mode + "-independent-sources"))
      val document = ScalaToMlirBridge.fromSnapshot(sharedSourceMutation(snapshot))
      val _ =
        Files.writeString(directory.resolve(mode + "-shared-source-negative.mlir"), document.text)
