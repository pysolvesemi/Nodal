package nodal.internal.testkit

import java.nio.file.{Files, Path}
import java.time.Duration

import nodal.*
import nodal.internal.bridge.*
import nodal_external.HierarchyPayloadParent
import utest.*

object Increment58InterfacePayloadTests extends TestSuite:
  private def capture(mode: String = "late") =
    ConstructionKernel.inspect(new HierarchyPayloadParent(mode))
  private def rejected(snapshot: ConstructionSnapshot): Unit =
    val failure = scala.util.Try(ScalaToMlirBridge.fromSnapshot(snapshot)).failed.get
    assert(failure.isInstanceOf[BridgeException])
  val tests = Tests:
    test("formal roles remain child-owned while complete actual payloads are parent-owned"):
      val snapshot = capture()
      val projections = snapshot.interfaceContracts.filter(_.boundary.nonEmpty)
      assert(projections.size == 3)
      projections.foreach: endpoint =>
        val child = snapshot.interfaceContracts.find(_.path == endpoint.boundary.get.endpoint).get
        assert(endpoint.members.map(_.dataType) == child.members.map(_.dataType))
        assert(endpoint.actualMemberTypes.map(_._1) == endpoint.members.map(_.name))
        assert(endpoint.actualMemberTypes.find(_._1 == "request.payload").get._2 !=
          child.members.find(_.name == "request.payload").get.dataType)
      val text = ScalaToMlirBridge.fromSnapshot(snapshot).text
      assert(
        text.contains("actual_types"),
        text.contains("nodal.interface_boundary"),
        text.contains("nodal.struct")
      )
    test("early and late overrides retain scalar and whole-array actual type proofs"):
      Vector("early", "late").foreach: mode =>
        val snapshot = capture(mode)
        assert(snapshot.interfaceContracts.filter(
          _.boundary.nonEmpty
        ).forall(_.actualMemberTypes.nonEmpty))
        assert(ScalaToMlirBridge.fromSnapshot(snapshot).text.contains("actual_types"))
    test("source rejects equal-bound wrong payload output writes and foreign capture"):
      Vector("wrong-type", "write-output", "raw").foreach: mode =>
        val failure = scala.util.Try(capture(mode)).failed.get
        assert(failure.isInstanceOf[ConstructionException])
        if mode == "raw" then
          assert(
            failure.asInstanceOf[ConstructionException].diagnostic.code ==
              "NODAL-INTERFACE-054-003",
            failure.getMessage.contains("forwardExact requires one local and one child endpoint")
          )
    test("bridge verifies unaccessed complete actual types without effects"):
      val snapshot = capture("no-access")
      assert(snapshot.digitalConnectivity.isEmpty)
      assert(ScalaToMlirBridge.fromSnapshot(snapshot).text.contains("actual_types"))
      val endpoint = snapshot.interfaceContracts.find(_.boundary.nonEmpty).get
      val payload = endpoint.actualMemberTypes.find(_._1 == "request.payload").get._2
      val wrongPayload = snapshot.modules.find(_.path == snapshot.root).get.declarations
        .find(_.name == "wrong").get.dataType.get
      val mutations = Vector(
        Vector.empty,
        endpoint.actualMemberTypes :+ endpoint.actualMemberTypes.head,
        endpoint.actualMemberTypes.map((name, value) =>
          name -> (if name == "request.payload" then "Bits(1)" else value)
        ),
        endpoint.actualMemberTypes.reverse,
        endpoint.actualMemberTypes.map((name, value) =>
          name -> (if name == "request.payload" then wrongPayload else value)
        )
      )
      assert(payload.startsWith("Struct("))
      mutations.foreach: actual =>
        rejected(snapshot.copy(interfaceContracts =
          snapshot.interfaceContracts.map(value =>
            if value.path == endpoint.path then value.copy(actualMemberTypes = actual) else value
          )
        ))
    test("changed finalized binding cannot certify the retained complete actual payload"):
      val snapshot = capture("no-access")
      val changed = snapshot.copy(modules =
        snapshot.modules.map(module =>
          if module.path != snapshot.root then module
          else
            module.copy(instances =
              module.instances.map(instance =>
                instance.copy(parameterBindings = instance.parameterBindings.map:
                  case ("width", _) => "width" -> "other"
                  case value => value)
              )
            )
        )
      )
      rejected(changed)
object Increment58InterfacePayloadNativeTests extends TestSuite:
  val tests = Tests:
    test("actual payload source replays through the native default semantic gate"):
      Vector("early", "late", "no-access").foreach: mode =>
        val document = ScalaToMlirBridge.fromSnapshot(
          ConstructionKernel.inspect(new HierarchyPayloadParent(mode))
        )
        val directory =
          Path.of(sys.env("NODAL_ROOT"), ".work", "f58-interface-payload", mode).toAbsolutePath
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
            assert(success.normalizedMlir.contains("actual_types"))
          case failure: NativeCompilerFailure =>
            throw new java.lang.AssertionError(s"${failure.diagnostic}\n${failure.standardError}")
