package nodal.internal.testkit

import java.nio.file.{Files, Path}
import java.time.Duration

import nodal.*
import nodal.internal.bridge.*
import utest.*

final class InterfaceSymbolicBridgeSource extends Module:
  val core = ClockDomain.external(
    "core",
    ClockEdge.Rising,
    ResetPolicy.Sync,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val width = param(8.integer, range = 1 to 16)
  val count = param(2.integer, range = 1 to 8)
  val input = in(UInt(width))
  val definition = Interface[InterfaceCaptureLink](
    "SymbolicInterfacePayload",
    InterfaceMember.value("data", UInt(width))
  )
  val source = Role[SourceRole]("source", RoleAccess.Out("data"))
  val endpoint = interfacePort(definition, source, "endpoint", core)
  val lanes = interfaceArray(definition, source, count + 1.integer, "lanes", core)
  val inverted = endpoint.inverted
  endpoint.driveMember("data", input)
  inverted.observeMember("data")

final class InterfaceCombinationalWidthSource(
    invalid: Boolean = false,
    concreteBoundary: Boolean = false
) extends Module:
  val core = ClockDomain.external(
    "core",
    ClockEdge.Rising,
    ResetPolicy.Sync,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val width = param(4.integer, range = 1 to 8)
  val other = param(4.integer, range = 1 to 8)
  val input = in(
    if concreteBoundary then UInt(1) else UInt(if invalid then other else width)
  )
  val enable = in(Bool)
  val definition = Interface[InterfaceCaptureLink](
    "CombinationalPayload",
    InterfaceMember.value("data", UInt(width + 1.integer))
  )
  val endpoint = interfacePort(
    definition,
    Role[SourceRole]("source", RoleAccess.Out("data")),
    "endpoint",
    core
  )
  val pad = digitalInout(
    UInt(width + 1.integer),
    DriveMode.pushPull,
    InoutPlacement.TopLevelPin,
    ResolutionProfile.PortableBoundaryOnly,
    "pad"
  )
  // Keep the child boundary concrete without treating a parameter default as its exact width.
  val operand =
    if concreteBoundary then
      select(
        input === 1.U(1),
        1.U(if invalid then other else width),
        0.U(if invalid then other else width)
      )
    else input
  val sum = operand + operand
  endpoint.driveMember("data", sum)
  pad.drive(sum, enable)

final class InterfaceCombinationalChildSource extends Module:
  val core = ClockDomain.external(
    "core",
    ClockEdge.Rising,
    ResetPolicy.Sync,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val input = in(UInt(1))
  val enable = in(Bool)
  val child = instance(new InterfaceCombinationalWidthSource(concreteBoundary = true))
  child(_.input) := input
  child(_.enable) := enable

final class InterfaceUnconnectedCombinationalChildSource extends Module:
  val child = instance(new InterfaceCombinationalWidthSource)

final class InterfaceMixedDeclarationBridgeSource extends Module:
  val core = ClockDomain.external(
    "core",
    ClockEdge.Rising,
    ResetPolicy.Sync,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val definition = Interface[InterfaceCaptureLink](
    "MixedElectricalDeclaration",
    InterfaceMember.terminal("pin", Electrical),
    InterfaceMember.analogSignal[Voltage.type]("sense", "voltage"),
    InterfaceMember.value("enabled", Bool)
  )
  val endpoint = interfacePort(
    definition,
    Role[SourceRole](
      "controller",
      RoleAccess.Connect("pin"),
      RoleAccess.In("sense"),
      RoleAccess.Out("enabled")
    ),
    "endpoint",
    core
  )
  endpoint.monitorView.observeMember("enabled")

final class InterfaceOnlyWidthSource(invalid: Boolean) extends Module:
  val core = ClockDomain.external(
    "core",
    ClockEdge.Rising,
    ResetPolicy.Sync,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val width = param(4.integer, range = (if invalid then -1 to 8 else 1 to 8))
  val definition = Interface[InterfaceCaptureLink](
    "InterfaceOnlyWidth",
    InterfaceMember.value("payload", Vec(UInt(width + 1.integer), 2))
  )
  val endpoint = interfacePort(
    definition,
    Role[SourceRole]("source", RoleAccess.Out("payload")),
    "endpoint",
    core
  )

final class InterfaceForeignWidthOwner extends Module:
  val width = param(4.integer, range = 1 to 8)

final class InterfaceForeignWidthSource extends Module:
  val core = ClockDomain.external(
    "core",
    ClockEdge.Rising,
    ResetPolicy.Sync,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val childModule = new InterfaceForeignWidthOwner
  val child = instance(childModule)
  val definition = Interface[InterfaceCaptureLink](
    "ForeignInterfaceWidth",
    InterfaceMember.value("payload", UInt(childModule.width))
  )
  val endpoint = interfacePort(
    definition,
    Role[SourceRole]("source", RoleAccess.Out("payload")),
    "endpoint",
    core
  )

final class InterfaceResolvedDriversBridgeSource extends Module:
  val core = ClockDomain.external(
    "core",
    ClockEdge.Rising,
    ResetPolicy.Sync,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val first = in(UInt(8))
  val second = 3.U(8)
  val definition = Interface[InterfaceCaptureLink](
    "ResolvedInterfaceDrivers",
    InterfaceMember.digitalInout("pad", UInt(8), DriveMode.pushPull)
  )
  val endpoint =
    interfacePort(definition, Role[SourceRole]("driver", RoleAccess.Drive("pad")), "endpoint", core)
  endpoint.driveMember("pad", first)
  endpoint.driveMember("pad", second)

final class InterfaceRepeatedWidthBridgeSource extends Module:
  val left = instance(new InterfaceOnlyWidthSource(false))
  val right = instance(new InterfaceOnlyWidthSource(false))

final class InterfaceLocalRoleChild(output: Boolean) extends Module:
  val core = ClockDomain.external(
    "core",
    ClockEdge.Rising,
    ResetPolicy.Sync,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val definition =
    Interface[InterfaceCaptureLink]("LocalRoleDefinition", InterfaceMember.value("data", Bool))
  val endpoint = interfacePort(
    definition,
    Role[SourceRole]("local", if output then RoleAccess.Out("data") else RoleAccess.In("data")),
    "endpoint",
    core
  )

final class InterfaceLocalRolesBridgeSource extends Module:
  val left = instance(new InterfaceLocalRoleChild(true))
  val right = instance(new InterfaceLocalRoleChild(false))

final class InterfaceCapabilitiesBridgeSource extends Module:
  val core = ClockDomain.external(
    "core",
    ClockEdge.Rising,
    ResetPolicy.Sync,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val definition = Interface[InterfaceCaptureLink](
    "InterfaceCapabilities",
    InterfaceMember.value("clock", Clock),
    InterfaceMember.value("reset", Reset),
    InterfaceMember.value("clocks", Vec(Clock, 2)),
    InterfaceMember.value("resets", Vec(Reset, 2))
  )
  val source = Role[SourceRole](
    "source",
    RoleAccess.Out("clock"),
    RoleAccess.Out("reset"),
    RoleAccess.Out("clocks"),
    RoleAccess.Out("resets")
  )
  val sink = Role[SinkRole](
    "sink",
    RoleAccess.In("clock"),
    RoleAccess.In("reset"),
    RoleAccess.In("clocks"),
    RoleAccess.In("resets")
  )
  val left = interfacePort(definition, source, "left", core)
  val right = interfacePort(definition, sink, "right", core)
  core {
    val clock = in(Clock)
    val reset = in(Reset)
    val clocks = in(Vec(Clock, 2))
    val resets = in(Vec(Reset, 2))
    left.driveMember("clock", clock)
    left.driveMember("reset", reset)
    left.driveMember("clocks", clocks)
    left.driveMember("resets", resets)
  }
  left.connectExact(right)
  right.observeMember("clock")
  right.observeMember("reset")

final class InterfaceInvalidCapabilityPayloadSource(stream: Boolean) extends Module:
  val core = ClockDomain.external(
    "core",
    ClockEdge.Rising,
    ResetPolicy.Sync,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val definition = Interface[InterfaceCaptureLink](
    "InvalidCapabilityPayload",
    if stream then InterfaceMember.stream("payload", Reset)
    else InterfaceMember.valid("payload", Vec(Clock, 2))
  )
  val endpoint = interfacePort(
    definition,
    Role[SourceRole]("source", RoleAccess.Master("payload")),
    "endpoint",
    core
  )

final class InterfaceCapabilityDriveSource(mode: String) extends Module:
  val core = ClockDomain.external(
    "core",
    ClockEdge.Rising,
    ResetPolicy.Sync,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val other = ClockDomain.external(
    "other",
    ClockEdge.Rising,
    ResetPolicy.Sync,
    ResetPolarity.ActiveHigh,
    50.MHz
  )
  val definition =
    Interface[InterfaceCaptureLink]("CapabilityDrive", InterfaceMember.value("clock", Clock))
  val endpoint =
    interfacePort(definition, Role[SourceRole]("source", RoleAccess.Out("clock")), "endpoint", core)
  if mode == "foreign-domain" then
    other {
      val input = in(Clock)
      endpoint.driveMember("clock", input)
    }
  else
    core {
      if mode == "output-root" then
        val output = out(Clock)
        endpoint.driveMember("clock", output)
      else
        val input = in(Clock)
        endpoint.driveMember("clock", input)
    }

object Increment54InterfaceBridgeTests extends TestSuite:
  private def rejected(value: ConstructionSnapshot): String =
    val error = scala.util.Try(ScalaToMlirBridge.fromSnapshot(value)).failed.get
    assert(error.isInstanceOf[BridgeException])
    error.asInstanceOf[BridgeException].diagnostic.code

  private def native(document: NodalMlirDocument, directory: Path): NativeCompilerResult =
    val compiler = sys.env.getOrElse(
      "NODAL_NODALC",
      scala.util.Failure[Nothing](new java.lang.AssertionError("configured nodalc is required")).get
    )
    NativeCompilerClient.run(
      document,
      NativeCompilerRequest(
        Path.of(compiler).toAbsolutePath,
        Vector.empty,
        directory,
        timeout = Duration.ofSeconds(90)
      )
    )

  private def verified(document: NodalMlirDocument, directory: Path): String =
    native(document, directory) match
      case result: NativeCompilerSuccess => result.normalizedMlir
      case failure: NativeCompilerFailure =>
        scala.util.Failure[Nothing](
          new java.lang.AssertionError(s"${failure.diagnostic}\n${failure.standardError}")
        ).get

  val tests: Tests = Tests:
    test("complete roles nested definitions enum payloads and actual member edges lower"):
      val captured = ConstructionKernel.inspect(new InterfaceCaptureSource("ok"))
      val document = ScalaToMlirBridge.fromSnapshot(captured)
      assert(document == ScalaToMlirBridge.lower(new InterfaceCaptureSource("ok")))
      Vector(
        "interface",
        "interface_role",
        "interface_member",
        "interface_instance",
        "interface_abi",
        "member_access",
        "interface_connect",
        "interface_drive",
        "interface_observe"
      ).foreach(name => assert(document.text.contains(s"\"nodal.$name\"")))
      assert(document.text.contains("interface_abi_version = 1 : i64"))
      assert(document.text.contains("nested_roles = [{path = \"link\", role = \"producer\"}]"))
      assert(document.text.contains("!nodal.enum<"))
      assert(document.text.contains("dimensions = [\"3\"]"))
      assert(document.text.contains("role = \"monitor\""))
      assert(captured.interfaceContracts.forall(_.roles.nonEmpty))
      val observed = captured.digitalConnectivity.filter(_.kind == "interface-observe")
      assert(observed.forall(_.endpointRole.contains("monitor")))

    test("resolved values use actual net handles drivers and conditional operands"):
      val document = ScalaToMlirBridge.lower(new InterfaceInoutSource("ok"))
      Vector(
        "resolved_net",
        "net_read",
        "net_driver",
        "net_drive",
        "net_release",
        "net_pass_through",
        "net_split"
      ).foreach(name =>
        assert(document.text.contains(s"\"nodal.$name\""))
      )
      assert(document.text.contains("!nodal.resolved<\"push_pull\", !nodal.bits<8>>"))
      assert(document.text.contains("profile = \"portable_boundary_only\""))
      assert(!document.text.contains("operator_name = \"digital_net_"))
      val levels = ScalaToMlirBridge.lower(new InterfaceLowHighSource)
      assert(levels.text.contains("\"nodal.net_drive_level\""))
      assert(levels.text.contains("level = \"low\""), levels.text.contains("level = \"high\""))

    test("resolved interface members retain multiple actual drivers unlike ordinary leaves"):
      val captured = ConstructionKernel.inspect(new InterfaceResolvedDriversBridgeSource)
      assert(captured.digitalConnectivity.count(_.kind == "interface-drive") == 2)
      val literal = captured.digitalExpressions.find(_.literal.contains("3")).get
      assert(captured.digitalConnectivity.exists(_.value.contains(literal.path)))
      val document = ScalaToMlirBridge.fromSnapshot(captured)
      assert(document.text.linesIterator.count(_.contains("\"nodal.interface_drive\"")) == 2)
      val ordinary = scala.util.Try(
        ConstructionKernel.inspect(new InterfaceCaptureSource("duplicate-drive"))
      ).failed.get
      assert(ordinary.isInstanceOf[ConstructionException])
      assert(ordinary.asInstanceOf[ConstructionException].diagnostic.code ==
        "NODAL-INTERFACE-054-010")

    test("bridge rejects incomplete maps forged leaf authority and lost endpoint identity"):
      val captured = ConstructionKernel.inspect(new InterfaceCaptureSource("ok"))
      val selected = captured.interfaceContracts.head
      val role = selected.roles.head
      val invalidRoles = selected.roles.updated(0, role.copy(members = role.members.drop(1)))
      val altered = captured.copy(interfaceContracts =
        captured.interfaceContracts.map(endpoint =>
          if endpoint.path == selected.path then endpoint.copy(roles = invalidRoles) else endpoint
        )
      )
      assert(rejected(altered) == "NODAL-BRIDGE-054-INTERFACE")
      val drive = captured.digitalConnectivity.find(_.kind == "interface-drive").get
      val connection = captured.digitalConnectivity.find(_.kind == "interface-connect").get
      assert(rejected(captured.copy(digitalConnectivity =
        captured.digitalConnectivity :+
          connection.copy(path = connection.path + "_duplicate")
      )) == "NODAL-BRIDGE-054-INTERFACE")
      Vector(
        drive.copy(endpointRole = None),
        drive.copy(endpointRole = Some("absent")),
        drive.copy(access = Some("in")),
        drive.copy(dataType = Some("Bits(8)")),
        drive.copy(target = "foreign.member")
      ).foreach: invalid =>
        val changed = captured.copy(digitalConnectivity =
          captured.digitalConnectivity.map(effect =>
            if effect.path == drive.path then invalid else effect
          )
        )
        assert(rejected(changed) == "NODAL-BRIDGE-054-INTERFACE")
      assert(rejected(captured.copy(digitalConnectivity =
        captured.digitalConnectivity :+
          drive.copy(path = drive.path + "_duplicate")
      )) == "NODAL-BRIDGE-054-INTERFACE")

    test("bridge independently rejects absent ambiguous and wrong-owner net driver projections"):
      val captured = ConstructionKernel.inspect(new InterfaceInoutSource("through"))
      val driver = captured.digitalConnectivity.find(_.kind == "inout-drive").get
      val missing = captured.copy(digitalConnectivity =
        captured.digitalConnectivity.filterNot(_.path == driver.path)
      )
      assert(rejected(missing) == "NODAL-BRIDGE-054-INTERFACE")
      val disconnected = captured.copy(digitalConnectivity =
        captured.digitalConnectivity.filterNot(_.kind == "inout-pass-through")
      )
      assert(rejected(disconnected) == "NODAL-BRIDGE-054-INTERFACE")
      val ambiguous = captured.copy(digitalConnectivity =
        captured.digitalConnectivity :+
          driver.copy(path = driver.path + "_second")
      )
      assert(rejected(ambiguous) == "NODAL-BRIDGE-054-INTERFACE")
      val foreign = captured.copy(digitalConnectivity =
        captured.digitalConnectivity.map(effect =>
          if effect.path == driver.path then effect.copy(owner = "foreign") else effect
        )
      )
      assert(rejected(foreign) == "NODAL-BRIDGE-054-INTERFACE")

    test("symbolic payload widths array dimensions and complete inverse roles remain structural"):
      val snapshot = ConstructionKernel.inspect(new InterfaceSymbolicBridgeSource)
      val dimensions = snapshot.interfaceContracts.find(_.path.endsWith(".lanes")).get.dimensions
      assert(dimensions.size == 1, snapshot.parameterExpressions.exists(_.path == dimensions.head))
      val document = ScalaToMlirBridge.fromSnapshot(snapshot)
      assert(document.text.contains("!nodal.digital<\"uint\", \"width\">"))
      assert(document.text.contains(s"dimensions = [\"${dimensions.head}\"]"))
      assert(document.text.contains("\"nodal.const_expr\""))
      assert(!document.text.contains("lane_0"))
      assert(document == ScalaToMlirBridge.lower(new InterfaceSymbolicBridgeSource))

    test("combinational symbolic payloads retain canonical type through interface and net drives"):
      val captured = ConstructionKernel.inspect(new InterfaceCombinationalWidthSource)
      val document = ScalaToMlirBridge.fromSnapshot(captured)
      assert(document.text.contains("digital_add"))
      assert(document.text.contains("nodal.interface_drive"))
      assert(document.text.contains("nodal.net_drive"))
      val directory = Path.of(".work", "increment55-interface-payload").toAbsolutePath
      Files.createDirectories(directory)
      assert(verified(document, directory).contains("nodal.interface_drive"))
      val child = ScalaToMlirBridge.lower(new InterfaceCombinationalChildSource)
      val childDirectory = directory.resolve("child")
      Files.createDirectories(childDirectory)
      assert(verified(child, childDirectory).contains("nodal.interface_drive"))
      assert(child.text.contains("digital_select"))
      assert(child.text.contains("nodal.instance_port"))

      val unconnected = scala.util.Try(
        ConstructionKernel.inspect(new InterfaceUnconnectedCombinationalChildSource)
      ).failed.get
      assert(unconnected.isInstanceOf[ConstructionException])
      assert(unconnected.asInstanceOf[ConstructionException].diagnostic.code ==
        "NODAL-CDC-057-001")

      val rejected = scala.util.Try(
        ConstructionKernel.inspect(new InterfaceCombinationalWidthSource(true))
      ).failed.get
      assert(rejected.isInstanceOf[ConstructionException])
      assert(rejected.asInstanceOf[ConstructionException].diagnostic.code ==
        "NODAL-INTERFACE-054-008")

    test("interface-only payload widths retain owned compound roots and reject invalid bounds"):
      val captured = ConstructionKernel.inspect(new InterfaceOnlyWidthSource(false))
      assert(captured.parameterExpressions.exists(_.operation == "analog_add"))
      val width = captured.modules.head.declarations.find(_.name == "width").get
      assert(
        width.attributes.toMap.get("structural_effects").exists(_.split(",").contains("width"))
      )
      assert(ScalaToMlirBridge.fromSnapshot(captured).text.contains("\"nodal.const_expr\""))
      val invalid =
        scala.util.Try(ConstructionKernel.inspect(new InterfaceOnlyWidthSource(true))).failed.get
      assert(invalid.isInstanceOf[ConstructionException])
      assert(invalid.asInstanceOf[ConstructionException].diagnostic.code == "NODAL-DIGITAL-054-001")
      val foreign =
        scala.util.Try(ConstructionKernel.inspect(new InterfaceForeignWidthSource)).failed.get
      assert(foreign.isInstanceOf[ConstructionException])
      assert(foreign.asInstanceOf[ConstructionException].diagnostic.code == "NODAL-DIGITAL-054-001")

    test("module-local interface identities retain occurrence-owned compound widths and roles"):
      val captured = ConstructionKernel.inspect(new InterfaceRepeatedWidthBridgeSource)
      assert(captured.interfaceContracts.size == 2)
      val owners = captured.interfaceContracts.map(_.owner)
      assert(owners.distinct.size == 2)
      assert(owners.forall(owner => captured.parameterExpressions.exists(_.owner == owner)))
      assert(ScalaToMlirBridge.fromSnapshot(captured).text.contains("\"nodal.interface\""))
      val roles = ConstructionKernel.inspect(new InterfaceLocalRolesBridgeSource)
      assert(roles.interfaceContracts.map(_.members.head.access).toSet == Set("in", "out"))
      assert(ScalaToMlirBridge.fromSnapshot(roles).text.contains("\"nodal.interface_role\""))

    test("Clock and Reset interface capabilities retain distinct exact boundary types"):
      val captured = ConstructionKernel.inspect(new InterfaceCapabilitiesBridgeSource)
      val document = ScalaToMlirBridge.fromSnapshot(captured)
      assert(document.text.contains("!nodal.capability<\"clock\">"))
      assert(document.text.contains("!nodal.capability<\"reset\">"))
      assert(document.text.contains("!nodal.shaped<\"2\", !nodal.capability<\"clock\">>"))
      assert(captured.digitalConnectivity.count(_.kind == "interface-drive") == 4)
      Vector(false, true).foreach: stream =>
        val error = scala.util.Try(
          ConstructionKernel.inspect(new InterfaceInvalidCapabilityPayloadSource(stream))
        ).failed.get
        assert(error.isInstanceOf[ConstructionException])
        assert(error.asInstanceOf[ConstructionException].diagnostic.code == "NODAL-DIGITAL-054-010")
      val connection = captured.digitalConnectivity.find(_.kind == "interface-connect").get
      val changed = captured.copy(digitalConnectivity =
        captured.digitalConnectivity.map(effect =>
          if effect.path == connection.path then effect.copy(dataType = Some("Bool")) else effect
        )
      )
      assert(rejected(changed) == "NODAL-BRIDGE-054-INTERFACE")

    test("interface capability drives require actual input roots in the endpoint domain"):
      val captured = ConstructionKernel.inspect(new InterfaceCapabilityDriveSource("ok"))
      val document = ScalaToMlirBridge.fromSnapshot(captured)
      assert(document.text.contains("\"nodal.interface_drive\""))
      Vector("foreign-domain", "output-root").foreach: mode =>
        val error = scala.util.Try(
          ConstructionKernel.inspect(new InterfaceCapabilityDriveSource(mode))
        ).failed.get
        assert(error.isInstanceOf[ConstructionException])
        assert(error.asInstanceOf[ConstructionException].diagnostic.code == "NODAL-DIGITAL-054-018")
      val effect = captured.digitalConnectivity.find(_.kind == "interface-drive").get
      val module = captured.modules.find(_.path == captured.root).get
      val input =
        module.declarations.find(declaration => effect.value.contains(declaration.path)).get
      val wrongRoot = captured.copy(modules =
        captured.modules.map(value =>
          if value.path != module.path then value
          else
            value.copy(declarations =
              value.declarations.map(declaration =>
                if declaration.path == input.path then declaration.copy(kind = "output")
                else declaration
              )
            )
        )
      )
      assert(rejected(wrongRoot).startsWith("NODAL-BRIDGE"))
      val other = module.domains.find(_.name == "other").get
      val wrongDomain = captured.copy(modules =
        captured.modules.map(value =>
          if value.path != module.path then value
          else
            value.copy(declarations =
              value.declarations.map(declaration =>
                if declaration.path == input.path then declaration.copy(domain = Some(other.path))
                else declaration
              )
            )
        )
      )
      assert(rejected(wrongDomain).startsWith("NODAL-BRIDGE"))
      Vector(
        "Reset" -> "NODAL-BRIDGE-054-INTERFACE",
        "Struct(InvalidCapability{clock:Clock})" -> "NODAL-BRIDGE-054"
      ).foreach: (dataType, diagnostic) =>
        val changed = captured.copy(modules =
          captured.modules.map(value =>
            if value.path != module.path then value
            else
              value.copy(declarations =
                value.declarations.map(declaration =>
                  if declaration.path == input.path then declaration.copy(dataType = Some(dataType))
                  else declaration
                )
              )
          )
        )
        assert(rejected(changed) == diagnostic)

    test("mixed analog declarations preserve canonical identity and exact dimensions"):
      val snapshot = ConstructionKernel.inspect(new InterfaceMixedDeclarationBridgeSource)
      val endpoint = snapshot.interfaceContracts.head
      val pin = endpoint.members.find(_.name == "pin").get
      assert(pin.dataType == "Terminal(electrical)")
      assert(pin.analogAttributes.toMap == Map(
        "discipline" -> "electrical",
        "potential_nature" -> "voltage",
        "flow_nature" -> "current"
      ))
      assert(endpoint.members.find(_.name == "sense").get.analogAttributes ==
        Vector("dimension" -> "voltage"))
      val document = ScalaToMlirBridge.fromSnapshot(snapshot)
      assert(document.text.contains("!nodal.terminal<\"electrical\">"))
      assert(document.text.contains("analog = {dimension = \"voltage\"}"))
      assert(document.text.contains("\"nodal.discipline\""))
      assert(Nodal.emit(new InterfaceMixedDeclarationBridgeSource).report.designKind ==
        DesignKind.MixedSignal)
      assert(document.text.contains("nodal.target.profile = \"mixed_signal\""))

    test("native independently rejects malformed role access and missing split drivers"):
      val directory = Path.of(".work", "increment54-interface-bridge-negative").toAbsolutePath
      Files.createDirectories(directory)
      val interfaces = ScalaToMlirBridge.lower(new InterfaceCaptureSource("ok"))
      val badRole = interfaces.copy(text =
        interfaces.text.replaceFirst(
          "access = \"out\"",
          "access = \"drive\""
        )
      )
      assert(native(badRole, directory).isInstanceOf[NativeCompilerFailure])
      val resolved = ScalaToMlirBridge.lower(new InterfaceInoutSource("ok"))
      val missingDriver = resolved.copy(text =
        resolved.text.linesIterator
          .filterNot(_.contains("\"nodal.net_drive\"")).mkString("\n")
      )
      assert(native(missingDriver, directory).isInstanceOf[NativeCompilerFailure])

    test("actual interface and resolved source verifies and round trips through native"):
      val directory = Path.of(".work", "increment54-interface-bridge").toAbsolutePath
      Files.createDirectories(directory)
      Vector[() => Module](
        () => new InterfaceCaptureSource("ok"),
        () => new InterfaceInoutSource("ok"),
        () => new InterfaceInoutSource("through"),
        () => new InterfaceLowHighSource,
        () => new InterfaceSymbolicBridgeSource,
        () => new InterfaceMixedDeclarationBridgeSource,
        () => new InterfaceOnlyWidthSource(false),
        () => new InterfaceResolvedDriversBridgeSource,
        () => new InterfaceNestedInverseSource(true),
        () => new InterfaceRepeatedWidthBridgeSource,
        () => new InterfaceLocalRolesBridgeSource,
        () => new InterfaceCapabilitiesBridgeSource,
        () => new InterfaceCapabilityDriveSource("ok")
      ).zipWithIndex.foreach: (construct, index) =>
        val document = ScalaToMlirBridge.lower(construct())
        Files.writeString(directory.resolve(s"source-$index.mlir"), document.text)
        val normalized = verified(document, directory)
        Files.writeString(directory.resolve(s"normalized-$index.mlir"), normalized)
        assert(normalized == verified(document.copy(text = normalized), directory))
