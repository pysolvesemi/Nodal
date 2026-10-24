package nodal.internal.testkit

import java.nio.file.{Files, Path}
import java.time.Duration

import nodal.*
import nodal.internal.bridge.*
import nodal_external.{HierarchyPlainScalarParent, HierarchyResolvedParent}
import utest.*

object Increment58ResolvedInterfaceTests extends TestSuite:
  private def capture(mode: String = "ok") =
    ConstructionKernel.inspect(new HierarchyResolvedParent(mode))
  private def bridgeRejects(snapshot: ConstructionSnapshot): Unit =
    val error = scala.util.Try(ScalaToMlirBridge.fromSnapshot(snapshot)).failed.get
    assert(error.isInstanceOf[BridgeException])
  val tests = Tests:
    test("resolved child projections preserve formal modes and complete actual element types"):
      val snapshot = capture()
      val endpoints = snapshot.interfaceContracts.filter(_.boundary.nonEmpty)
      assert(endpoints.size == 2)
      endpoints.foreach: endpoint =>
        val child = snapshot.interfaceContracts.find(_.path == endpoint.boundary.get.endpoint).get
        assert(endpoint.members.map(_.mode) == Vector(Some("push-pull")))
        assert(endpoint.members.map(_.dataType) == child.members.map(_.dataType))
        assert(endpoint.actualMemberTypes.map(_._1) == Vector("pad"))
        assert(endpoint.actualMemberTypes.head._2 != endpoint.members.head.dataType)
      assert(snapshot.resolvedNets.isEmpty)
    test("drive and pass-through use existing child capabilities without allocating fresh nets"):
      val snapshot = capture()
      assert(snapshot.digitalConnectivity.count(_.kind == "interface-drive") == 2)
      assert(snapshot.digitalConnectivity.count(_.kind == "interface-connect") == 1)
      val text = ScalaToMlirBridge.fromSnapshot(snapshot).text
      assert(text.contains("nodal.interface_boundary"), text.contains("actual_types"))
      assert(text.contains("!nodal.resolved<\"push_pull\""))
      assert(!text.contains("\"nodal.resolved_net\""))
      // A shape witness is not a synthetic Bool member in an otherwise UInt-only ABI.
      val ordinary = ScalaToMlirBridge.fromSnapshot(
        ConstructionKernel.inspect(new HierarchyPlainScalarParent)
      ).text
      assert(
        ordinary.contains("nodal.interface_boundary"),
        ordinary.contains("nodal.interface_connect")
      )
    test("absolute resolved rights reject wrong types foreign captures and scalar-array confusion"):
      Vector(
        "wrong-type" -> "NODAL-INTERFACE-054-008",
        "connect-cannot-drive" -> "NODAL-INTERFACE-054-003",
        "drive-cannot-read" -> "NODAL-INTERFACE-054-003",
        "raw" -> "NODAL-INTERFACE-054-003"
      ).foreach: (mode, code) =>
        val error = scala.util.Try(capture(mode)).failed.get
        assert(error.isInstanceOf[ConstructionException])
        assert(error.asInstanceOf[ConstructionException].diagnostic.code == code)
        if mode == "raw" then
          assert(
            error.getMessage.contains("forwardExact requires one local and one child endpoint")
          )
    test("resolved child arrays retain complete actual member and dimension inventory"):
      val snapshot = capture("array")
      val array = snapshot.interfaceContracts.find(value =>
        value.owner == snapshot.root && value.boundary.exists(_.endpoint.endsWith(".lanes"))
      ).get
      assert(array.dimensions == Vector("2"))
      assert(array.members.forall(_.protocol == "resolved"))
      assert(ScalaToMlirBridge.fromSnapshot(snapshot).text.contains("\"nodal.interface_boundary\""))
    test("bridge independently rejects changed resolved mode and complete actual payload"):
      val snapshot = capture("empty")
      val endpoint = snapshot.interfaceContracts.find(_.boundary.nonEmpty).get
      val changed = endpoint.copy(
        members = endpoint.members.map(_.copy(mode = Some("open-drain"))),
        roles = endpoint.roles.map(role =>
          role.copy(
            members = role.members.map(_.copy(mode = Some("open-drain")))
          )
        )
      )
      bridgeRejects(snapshot.copy(interfaceContracts =
        snapshot.interfaceContracts.map(value =>
          if value.path == endpoint.path then changed else value
        )
      ))
      bridgeRejects(snapshot.copy(interfaceContracts =
        snapshot.interfaceContracts.map(value =>
          if value.path == endpoint.path then
            value.copy(actualMemberTypes = Vector("pad" -> "UInt(1)"))
          else value
        )
      ))
    test("unaccessed resolved boundary remains a logical identity with no invented driver"):
      val snapshot = capture("empty")
      assert(snapshot.digitalConnectivity.isEmpty, snapshot.resolvedNets.isEmpty)
      val text = ScalaToMlirBridge.fromSnapshot(snapshot).text
      assert(text.contains("nodal.interface_boundary"))
      assert(!text.contains("\"nodal.interface_drive\""))
      assert(!text.contains("\"nodal.resolved_net\""))

object Increment58ResolvedInterfaceNativeTests extends TestSuite:
  val tests = Tests:
    test("actual resolved public source replays through the native default semantic gate"):
      Vector("ok", "empty").foreach: mode =>
        val document = ScalaToMlirBridge.fromSnapshot(
          ConstructionKernel.inspect(new HierarchyResolvedParent(mode))
        )
        val directory = Path.of(sys.env("NODAL_ROOT"), ".work", "f58-resolved-boundary", mode)
          .toAbsolutePath
        Files.createDirectories(directory)
        val _ = Files.writeString(directory.resolve("input.mlir"), document.text)
        val result = NativeCompilerClient.run(
          document,
          NativeCompilerRequest(
            Path.of(sys.env("NODAL_NODALC")).toAbsolutePath,
            Vector("--nodal-gate-default"),
            directory,
            timeout = Duration.ofSeconds(90)
          )
        )
        val _ = Files.writeString(directory.resolve("output.mlir"), result.standardOutput)
        val _ = Files.writeString(directory.resolve("diagnostics.txt"), result.standardError)
        result match
          case success: NativeCompilerSuccess =>
            assert(success.normalizedMlir.contains("nodal.interface_boundary"))
          case failure: NativeCompilerFailure =>
            throw new java.lang.AssertionError(s"${failure.diagnostic}\n${failure.standardError}")
