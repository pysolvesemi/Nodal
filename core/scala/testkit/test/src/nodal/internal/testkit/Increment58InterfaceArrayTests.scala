package nodal.internal.testkit

import java.nio.file.{Files, Path}
import java.time.Duration

import nodal.*
import nodal.internal.bridge.*
import utest.*

sealed trait HierarchyArrayLink extends Interface
object HierarchyArrayContract:
  val producer = Role[SourceRole]("producer", RoleAccess.Out("data"))
  val consumer = Role[SinkRole]("consumer", RoleAccess.In("data"))
  val link =
    Interface[HierarchyArrayLink]("HierarchyArrayLink", InterfaceMember.value("data", Bool))
      .withRoles(producer, consumer)

final class HierarchyArrayLeaf extends Module:
  val required = ClockDomain.required("required")
  val n = param(2.integer, range = 1 to 8)
  val input = in(Vec(Bool, n))
  val output = interfaceArray(
    HierarchyArrayContract.link,
    HierarchyArrayContract.producer,
    n,
    "output",
    required
  )
  val incoming = interfaceArray(
    HierarchyArrayContract.link,
    HierarchyArrayContract.consumer,
    n,
    "incoming",
    required
  )
  output.driveMember("data", input)
  incoming.observeMember("data")

final class HierarchyArrayParent(mode: String = "late") extends Module:
  val core = ClockDomain.external(
    "core",
    ClockEdge.Rising,
    ResetPolicy.Sync,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val p = param(3.integer, range = 2 to 4)
  val count = p + 1.integer
  val input = in(Vec(Bool, count))
  val wrong = in(Vec(Bool, p))
  val output = interfaceArray(
    HierarchyArrayContract.link,
    HierarchyArrayContract.producer,
    count,
    "output",
    core
  )
  val child = instance(new HierarchyArrayLeaf).domain(core)
  if mode == "early" then child.param(_.n, count)
  val projected = child(_.output)
  val incoming = child(_.incoming)
  val repeated = child(_.output)
  require(projected eq repeated)
  if mode != "early" then child.param(_.n, count)
  child(_.input) := input
  mode match
    case "write-output" => projected.driveMember("data", input)
    case "wrong-shape" => incoming.driveMember("data", wrong)
    case "raw" => output.forwardExact(child.module.output)
    case "foreign-count" =>
      val _ = in(Vec(Bool, projected.count))
    case "wrong-instance" =>
      val other = instance(new HierarchyArrayLeaf).domain(core).param(_.n, count)
      other(_ => child.module.output).observeMember("data")
    case _ =>
      output.forwardExact(projected)
      incoming.driveMember("data", input)
      projected.observeMember("data")

final class HierarchyResolvedArrayAttempt extends Module:
  val core = ClockDomain.external(
    "core",
    ClockEdge.Rising,
    ResetPolicy.Sync,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val definition = Interface[HierarchyArrayLink](
    "HierarchyResolvedArray",
    InterfaceMember.digitalInout("pad", Bits(8), DriveMode.pushPull)
  )
  val endpoint = interfaceArray(
    definition,
    Role[SinkRole]("reader", RoleAccess.Read("pad")),
    2,
    "endpoint",
    core
  )
  endpoint.observeMember("pad")

object Increment58InterfaceArrayTests extends TestSuite:
  private def capture(mode: String = "late") =
    ConstructionKernel.inspect(new HierarchyArrayParent(mode))
  val tests = Tests:
    test("whole arrays retain exact cached endpoint and finalized dependent shape"):
      val snapshot = capture()
      val projected = snapshot.interfaceContracts.filter(_.boundary.nonEmpty)
      assert(projected.size == 2)
      projected.foreach: endpoint =>
        val boundary = endpoint.boundary.get
        StructuralParameterBinding.validateInterfaceDimensions(
          snapshot,
          endpoint.owner,
          boundary.instance.get,
          boundary.endpoint,
          endpoint.dimensions
        )
        assert(endpoint.dimensions.size == 1)
        assert(endpoint.members.map(_.name) == Vector("data"))
        assert(endpoint.members.forall(_.dataType == "Bool"))
      snapshot.interfaceContracts.foreach: endpoint =>
        val declaration = snapshot.modules.flatMap(_.declarations).find(_.path == endpoint.path).get
        assert(declaration.attributes.toMap.apply("count") == endpoint.dimensions.head)
      assert(snapshot.interfaceAbi.count(_.logicalPath.contains("[")) == 5)
    test("early and late overrides produce actual symbolic bridge dimensions"):
      Vector("early", "late").foreach: mode =>
        val snapshot = capture(mode)
        val document = ScalaToMlirBridge.fromSnapshot(snapshot)
        assert(document.text.contains("nodal.interface_boundary"))
        assert(document.text.contains("nodal.shaped"))
        assert(snapshot.interfaceContracts.filter(_.boundary.nonEmpty).forall(_.dimensions.head !=
          "2"))
    test("output writes wrong shape foreign count and nonexact selectors reject"):
      Vector("write-output", "wrong-shape", "raw", "foreign-count", "wrong-instance").foreach:
        mode =>
          assert(scala.util.Try(capture(mode)).failed.get.isInstanceOf[ConstructionException])
      val supported = ConstructionKernel.inspect(new HierarchyResolvedArrayAttempt)
      val endpoint = supported.interfaceContracts.find(_.path.endsWith(".endpoint")).get
      assert(endpoint.dimensions == Vector("2"), endpoint.members.forall(_.protocol == "resolved"))
      assert(ScalaToMlirBridge.fromSnapshot(supported).text.contains("\"nodal.interface_observe\""))
    test("bridge independently rejects forged substituted count and rank"):
      val snapshot = capture()
      val endpoint = snapshot.interfaceContracts.find(_.boundary.nonEmpty).get
      Vector(Vector("2"), endpoint.dimensions ++ endpoint.dimensions).foreach: dimensions =>
        val changed = snapshot.copy(
          interfaceContracts = snapshot.interfaceContracts.map(value =>
            if value.path == endpoint.path then value.copy(dimensions = dimensions) else value
          ),
          modules = snapshot.modules.map(module =>
            module.copy(declarations =
              module.declarations.map(value =>
                if value.path == endpoint.path then
                  value.copy(attributes =
                    value.attributes.map((key, old) =>
                      key -> (if key == "count" then dimensions.head else old)
                    )
                  )
                else value
              )
            )
          )
        )
        assert(scala.util.Try(
          ScalaToMlirBridge.fromSnapshot(changed)
        ).failed.get.isInstanceOf[BridgeException])
      val duplicate = snapshot.copy(modules =
        snapshot.modules.map(module =>
          module.copy(
            declarations = module.declarations.map(value =>
              if value.path == endpoint.path then
                value.copy(attributes = value.attributes :+ ("count" -> endpoint.dimensions.head))
              else value
            )
          )
        )
      )
      assert(scala.util.Try(
        ScalaToMlirBridge.fromSnapshot(duplicate)
      ).failed.get.isInstanceOf[BridgeException])
    test("actual symbolic array source replays through native default semantic gate"):
      Vector("early", "late").foreach: mode =>
        val document = ScalaToMlirBridge.fromSnapshot(capture(mode))
        val directory =
          Path.of(
            sys.env("NODAL_ROOT"),
            ".work",
            "f58-selected-interface-predecessors-arrays",
            mode
          ).toAbsolutePath
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
