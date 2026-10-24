package nodal.internal.testkit

import java.nio.file.{Files, Path}
import java.time.Duration

import nodal.*
import nodal.internal.bridge.*
import utest.*

sealed trait HierarchyInterfaceLink extends Interface
sealed trait HierarchyInterfaceNested extends Interface
object HierarchyInterfaceContract:
  val producer = Role[SourceRole]("producer", RoleAccess.Master("request"), RoleAccess.In("ready"))
  val link = Interface[HierarchyInterfaceLink](
    "HierarchyInterfaceLink",
    InterfaceMember.valid("request", Bool),
    InterfaceMember.value("ready", Bool)
  )
    .withRoles(producer)
  val outer = Role[SourceRole]("outer", RoleAccess.Nested("link", "producer"))
  val nested = Interface[HierarchyInterfaceNested](
    "HierarchyInterfaceNested",
    InterfaceMember.nested("link", link)
  ).withRoles(outer)

final class HierarchyInterfaceLeaf extends Module:
  val required = ClockDomain.required("required")
  val port = interfacePort(
    HierarchyInterfaceContract.nested,
    HierarchyInterfaceContract.outer,
    "port",
    required
  )
  port.driveMember("link.request.payload", true.B)
  port.driveMember("link.request.valid", true.B)
  port.observeMember("link.ready")

final class HierarchyInterfaceParent(mode: String = "forward") extends Module:
  val core = ClockDomain.external(
    "core",
    ClockEdge.Rising,
    ResetPolicy.Sync,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val port = interfacePort(
    HierarchyInterfaceContract.nested,
    HierarchyInterfaceContract.outer,
    "port",
    core
  )
  val child = instance(new HierarchyInterfaceLeaf).domain(core)
  val projected = child(_.port)
  val repeated = child(_.port)
  require(projected eq repeated)
  mode match
    case "forward" => port.forwardExact(projected)
    case "drive" =>
      projected.driveMember("link.ready", true.B)
      projected.observeMember("link.request")
    case "write-output" => projected.driveMember("link.request.payload", true.B)
    case "duplicate" =>
      projected.driveMember("link.ready", true.B)
      repeated.driveMember("link.ready", false.B)
    case "raw" => port.forwardExact(child.module.port)
    case "wrong-instance" =>
      val other = instance(new HierarchyInterfaceLeaf).domain(core)
      other(_ => child.module.port).observeMember("link.request")
    case "view" => projected.monitorView.observeMember("link.request")
    case "local-forward" => port.forwardExact(port)
    case _ => ()

object Increment58InterfaceHierarchyTests extends TestSuite:
  private def capture(mode: String = "forward") =
    ConstructionKernel.inspect(new HierarchyInterfaceParent(mode))
  private def native(document: NodalMlirDocument, suffix: String): NativeCompilerResult =
    val compiler = Path.of(sys.env("NODAL_NODALC")).toAbsolutePath
    val directory =
      Path.of(sys.env("NODAL_ROOT"), ".work", "f58-interface-native", suffix).toAbsolutePath
    Files.createDirectories(directory)
    val _ = Files.writeString(directory.resolve("input.mlir"), document.text)
    val result = NativeCompilerClient.run(
      document,
      NativeCompilerRequest(
        compiler,
        Vector("--nodal-gate-default"),
        directory,
        timeout = Duration.ofSeconds(90)
      )
    )
    val _ = Files.writeString(directory.resolve("output.mlir"), result.standardOutput)
    val _ = Files.writeString(directory.resolve("diagnostics.txt"), result.standardError)
    result
  val tests = Tests:
    test("exact cached child endpoint retains declared nested role and actual binding"):
      val snapshot = capture()
      val boundary = snapshot.interfaceContracts.find(_.boundary.nonEmpty).get
      val child = snapshot.interfaceContracts.find(_.path == boundary.boundary.get.endpoint).get
      assert(
        boundary.role == child.role,
        boundary.members.map(_.access) == child.members.map(_.access)
      )
      assert(boundary.members.map(_.nestedRoles) == child.members.map(_.nestedRoles))
      assert(boundary.domain ==
        snapshot.modules.find(_.path == snapshot.root).get.domains.head.path)
      assert(child.domain != boundary.domain)
      assert(snapshot.interfaceContracts.count(_.boundary.nonEmpty) == 1)
    test("same role forwarding emits exact native projection not a duplicate endpoint"):
      val document = ScalaToMlirBridge.fromSnapshot(capture())
      assert(document.text.contains("nodal.interface_boundary"))
      assert(document.text.sliding("\"nodal.interface_instance\"".length)
        .count(_ == "\"nodal.interface_instance\"") == 2)
      assert(document.text.contains("nodal.interface_connect"))
    test("parent can drive child input and observe child output"):
      val snapshot = capture("drive")
      assert(snapshot.digitalConnectivity.exists(value =>
        value.access.contains("in") &&
          value.kind == "interface-drive"
      ))
      val _ = ScalaToMlirBridge.fromSnapshot(snapshot)
    test("output writes duplicate drivers raw foreign selections and role laundering reject"):
      Vector("write-output", "duplicate", "raw", "wrong-instance", "view", "local-forward")
        .foreach: mode =>
          val failure = scala.util.Try(capture(mode)).failed.get
          assert(failure.isInstanceOf[ConstructionException])
    test("bridge rejects forged child endpoint role and actual domain"):
      val original = capture()
      val endpoint = original.interfaceContracts.find(_.boundary.nonEmpty).get
      val changes = Vector(
        endpoint.copy(boundary = endpoint.boundary.map(_.copy(endpoint = endpoint.path))),
        endpoint.copy(role = "forged"),
        endpoint.copy(domain = "foreign.domain"),
        endpoint.copy(boundary = None),
        endpoint.copy(members = endpoint.members.drop(1))
      )
      changes.foreach: changed =>
        val altered = original.copy(interfaceContracts =
          original.interfaceContracts.map(value =>
            if value.path == endpoint.path then changed else value
          )
        )
        val failure = scala.util.Try(ScalaToMlirBridge.fromSnapshot(altered)).failed.get
        assert(failure.isInstanceOf[BridgeException])
    test("bridge compares unaccessed complete imported ABI against local identity"):
      val original = capture()
      val projected = original.interfaceContracts.find(_.boundary.nonEmpty).get
      val childPath = projected.boundary.get.endpoint
      def changed(member: KernelInterfaceMemberSnapshot): KernelInterfaceMemberSnapshot =
        if member.name == "link.ready" then member.copy(dataType = "UInt(8)") else member
      val altered = original.copy(
        interfaceContracts = original.interfaceContracts.map(endpoint =>
          if endpoint.path == childPath || endpoint.path == projected.path then
            endpoint.copy(
              members = endpoint.members.map(changed),
              roles = endpoint.roles.map(role => role.copy(members = role.members.map(changed)))
            )
          else endpoint
        ),
        digitalConnectivity =
          original.digitalConnectivity.filterNot(_.member.contains("link.ready"))
      )
      val failure = scala.util.Try(ScalaToMlirBridge.fromSnapshot(altered)).failed.get
      assert(failure.isInstanceOf[BridgeException])
    test("native executes exact forwarding and direct input-drive admission"):
      Vector("forward", "drive").foreach: mode =>
        native(ScalaToMlirBridge.fromSnapshot(capture(mode)), mode) match
          case success: NativeCompilerSuccess =>
            assert(success.normalizedMlir.contains("nodal.interface_boundary"))
          case failure: NativeCompilerFailure =>
            throw new java.lang.AssertionError(s"${failure.diagnostic}\n${failure.standardError}")
