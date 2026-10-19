package nodal.internal.testkit

import nodal.*
import utest.*

enum InterfacePayloadMode derives HwEnum:
  case Idle, Active

sealed trait InterfaceCaptureLink extends Interface
sealed trait InterfaceCaptureNested extends Interface

object InterfaceCaptureContracts:
  val payload = Struct(
    "InterfaceCapturePayload",
    StructField("mode", EnumType[InterfacePayloadMode]()),
    StructField("data", Vec(SInt(8), 2))
  )
  val producer =
    Role[SourceRole]("producer", RoleAccess.Master("request"), RoleAccess.Slave("response"))
  val consumer =
    Role[SinkRole]("consumer", RoleAccess.Slave("request"), RoleAccess.Master("response"))
  val link = Interface[InterfaceCaptureLink](
    "InterfaceCaptureLink",
    InterfaceMember.valid("request", payload),
    InterfaceMember.stream("response", UInt(8))
  )
  val nested = Interface[InterfaceCaptureNested](
    "InterfaceCaptureNested",
    InterfaceMember.nested("link", link.withRoles(producer, consumer)),
    InterfaceMember.value("enable", Bool)
  )
  val outer =
    Role[SourceRole]("outer", RoleAccess.Nested("link", "producer"), RoleAccess.Out("enable"))

final class InterfaceCaptureSource(mode: String) extends Module:
  val core = ClockDomain.external(
    "core",
    ClockEdge.Rising,
    ResetPolicy.Sync,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val input = in(UInt(8))
  val valid = in(Bool)
  val left =
    interfacePort(InterfaceCaptureContracts.link, InterfaceCaptureContracts.producer, "left", core)
  val right =
    interfacePort(InterfaceCaptureContracts.link, InterfaceCaptureContracts.consumer, "right", core)
  val nested =
    interfacePort(InterfaceCaptureContracts.nested, InterfaceCaptureContracts.outer, "nested", core)
  val lanes = interfaceArray(
    InterfaceCaptureContracts.link,
    InterfaceCaptureContracts.producer,
    3,
    "lanes",
    core
  )
  mode match
    case "ok" =>
      left.connectExact(right)
      right.driveMember("response.payload", input)
      left.driveMember("request.valid", valid)
      left.monitorView.observeMember("response")
    case "wrong-type" => left.driveMember("request.valid", input)
    case "wrong-access" => left.driveMember("response.payload", input)
    case "duplicate-drive" =>
      right.driveMember("response.payload", input)
      right.driveMember("response.payload", input)
    case "protocol-parent" => left.driveMember("request", input)
    case "unknown-observe" => left.observeMember("absent")
    case _ => ()

final class InterfaceInoutSource(mode: String) extends Module:
  val core = ClockDomain.external(
    "core",
    ClockEdge.Rising,
    ResetPolicy.Sync,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val enable = in(Bool)
  val input = in(Bits(8))
  val pad = digitalInout(
    Bits(8),
    DriveMode.pushPull,
    InoutPlacement.TopLevelPin,
    ResolutionProfile.PortableBoundaryOnly,
    "pad"
  )
  val inner = digitalInout(
    Bits(8),
    DriveMode.pushPull,
    InoutPlacement.HierarchyPassThrough,
    ResolutionProfile.PortableBoundaryOnly,
    "inner"
  )
  val sampled = pad.read
  val split = pad.split
  mode match
    case "ok" =>
      pad.drive(input, enable)
      pad.highZ()
      passThrough(pad, inner)
    case "ambiguous" =>
      pad.drive(input, enable)
      inner.drive(input, enable)
      passThrough(pad, inner)
    case "through" =>
      inner.drive(input, enable)
      passThrough(pad, inner)
    case "wrong-type" => pad.drive(1.U(8), enable)
    case "wrong-width" => pad.drive(1.U(7), enable)
    case "foreign" => pad.drive(InterfaceForeignValue.value, enable)
    case _ => ()

object InterfaceForeignValue:
  val value: Expr[Bits] = new KernelExpr[Bits](
    Vector.empty,
    Some(KernelTypeDescriptor("Bits", Vector(8))),
    operation = Some("foreign")
  )

final class InterfaceLowHighSource extends Module:
  val core = ClockDomain.external(
    "core",
    ClockEdge.Rising,
    ResetPolicy.Sync,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val enable = in(Bool)
  val low = digitalInout(
    Bits(4),
    DriveMode.openDrain,
    InoutPlacement.TopLevelPin,
    ResolutionProfile.PortableBoundaryOnly,
    "low"
  )
  val high = digitalInout(
    Bits(4),
    DriveMode.openSource,
    InoutPlacement.TopLevelPin,
    ResolutionProfile.PortableBoundaryOnly,
    "high"
  )
  val lowSplit = low.split
  low.driveLow(enable)
  low.highZ()
  high.driveHigh(enable)

final class InterfaceInternalResolutionSource extends Module:
  val net = digitalInout(
    Bits(8),
    DriveMode.pushPull,
    InoutPlacement.InternalResolvedNet,
    ResolutionProfile.PortableBoundaryOnly,
    "net"
  )

final class InterfaceUnusedInvalidInverseSource extends Module:
  val core = ClockDomain.external(
    "core",
    ClockEdge.Rising,
    ResetPolicy.Sync,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val child = Interface[InterfaceCaptureLink](
    "ResolvedChild",
    InterfaceMember.digitalInout("pad", Bits(8), DriveMode.pushPull)
  ).withRoles(
    Role[SourceRole]("source", RoleAccess.Connect("pad")),
    Role[SinkRole]("sink", RoleAccess.Connect("pad"))
  )
  val outer =
    Interface[InterfaceCaptureNested]("ResolvedOuter", InterfaceMember.nested("child", child))
  val port = interfacePort(
    outer,
    Role[SourceRole]("source", RoleAccess.Nested("child", "source")),
    "port",
    core
  )
  val unused = port.inverted

final class InterfaceConflictingRoleSource extends Module:
  val core = ClockDomain.external(
    "core",
    ClockEdge.Rising,
    ResetPolicy.Sync,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val definition = InterfaceCaptureContracts.link.withRoles(
    InterfaceCaptureContracts.producer,
    Role[SourceRole]("producer", RoleAccess.Slave("request"), RoleAccess.Master("response"))
  )
  val port = interfacePort(definition, InterfaceCaptureContracts.consumer, "port", core)

final class InterfaceNestedInverseSource(complementary: Boolean) extends Module:
  val core = ClockDomain.external(
    "core",
    ClockEdge.Rising,
    ResetPolicy.Sync,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val child = Interface[InterfaceCaptureLink](
    "NestedInverseChild",
    InterfaceMember.value("data", Bool)
  ).withRoles(
    Role[MasterRole]("master", RoleAccess.Out("data")),
    Role[SlaveRole](
      "slave",
      if complementary then RoleAccess.In("data") else RoleAccess.Out("data")
    )
  )
  val definition =
    Interface[InterfaceCaptureNested]("NestedInverseParent", InterfaceMember.nested("child", child))
  val endpoint = interfacePort(
    definition,
    Role[MasterRole]("master", RoleAccess.Nested("child", "master")),
    "endpoint",
    core
  )
  val inverse = endpoint.inverted
  val restored = inverse.inverted
  inverse.observeMember("child.data")
  restored.observeMember("child.data")

object Increment54InterfaceSourceTests extends TestSuite:
  private def failure(body: => Any): String =
    scala.util.Try(body).failed.get.asInstanceOf[ConstructionException].diagnostic.code

  val tests: Tests = Tests:
    test("nested actual roles and protocol directions preserve exact payload ABI"):
      val snapshot = ConstructionKernel.inspect(new InterfaceCaptureSource("ok"))
      val nested = snapshot.interfaceContracts.find(_.path.endsWith(".nested")).get
      val request = nested.members.find(_.name == "link.request.payload").get
      val response = nested.members.find(_.name == "link.response.ready").get
      assert(request.role == "producer", request.access == "out")
      assert(request.nestedRoles == Vector("link" -> "producer"))
      assert(response.access == "out", response.protocol == "stream")
      assert(snapshot.enumDefinitions.exists(_.identity.endsWith("InterfacePayloadMode")))
      assert(snapshot.interfaceContracts.find(_.path.endsWith(".lanes")).get.dimensions ==
        Vector("3"))
      assert(snapshot.digitalConnectivity.count(_.kind == "interface-observe") == 3)
      val drive = snapshot.digitalConnectivity.find(value =>
        value.member.contains("response.payload") && value.kind == "interface-drive"
      ).get
      assert(drive.value.exists(_.endsWith(".input")), drive.dataType.contains("UInt(8)"))
      assert(snapshot == ConstructionKernel.inspect(new InterfaceCaptureSource("ok")))

    test("drive member requires exact leaf access and type"):
      assert(failure(ConstructionKernel.inspect(new InterfaceCaptureSource("wrong-type"))) ==
        "NODAL-INTERFACE-054-008")
      assert(failure(ConstructionKernel.inspect(new InterfaceCaptureSource("wrong-access"))) ==
        "NODAL-INTERFACE-054-003")
      assert(failure(ConstructionKernel.inspect(new InterfaceCaptureSource("protocol-parent"))) ==
        "NODAL-INTERFACE-054-003")
      assert(failure(ConstructionKernel.inspect(new InterfaceCaptureSource("unknown-observe"))) ==
        "NODAL-INTERFACE-054-003")
      assert(failure(ConstructionKernel.inspect(new InterfaceCaptureSource("duplicate-drive"))) ==
        "NODAL-INTERFACE-054-010")

    test("resolved reads drivers releases and split retain actual typed edges"):
      val snapshot = ConstructionKernel.inspect(new InterfaceInoutSource("ok"))
      val drive = snapshot.digitalConnectivity.find(_.kind == "inout-drive").get
      val release = snapshot.digitalConnectivity.find(_.kind == "inout-high-z").get
      assert(drive.target.endsWith(".pad"), drive.value.exists(_.endsWith(".input")))
      assert(drive.enable.exists(_.endsWith(".enable")), drive.dataType.contains("Bits(8)"))
      assert(release.value.isEmpty, release.enable.isEmpty, release.target == drive.target)
      val expressions = snapshot.digitalExpressions.filter(value =>
        DigitalInterfaceContract.ValueOperations.contains(value.operation)
      )
      assert(expressions.map(_.operation).toSet == DigitalInterfaceContract.ValueOperations)
      assert(expressions.forall(_.operands == Vector(drive.target)))
      assert(expressions.find(_.operation == "digital_net_enable_view").get.dataType == "Bool")
      assert(snapshot.digitalConnectivity.find(
        _.kind == "inout-pass-through"
      ).get.source.exists(_.endsWith(".inner")))

    test("resolved drivers reject type aliasing foreign owners and unsupported placement"):
      assert(failure(ConstructionKernel.inspect(new InterfaceInoutSource("wrong-type"))) ==
        "NODAL-INTERFACE-054-008")
      assert(failure(ConstructionKernel.inspect(new InterfaceInoutSource("wrong-width"))) ==
        "NODAL-INTERFACE-054-008")
      assert(failure(ConstructionKernel.inspect(new InterfaceInoutSource("foreign"))) ==
        "NODAL-INTERFACE-054-007")
      assert(failure(ConstructionKernel.inspect(new InterfaceInternalResolutionSource)) ==
        "NODAL-INTERFACE-054-009")

    test("split resolves one conditional driver across pass-through and rejects ambiguity"):
      val through = ConstructionKernel.inspect(new InterfaceInoutSource("through"))
      assert(through.digitalConnectivity.count(_.kind == "inout-drive") == 1)
      assert(failure(ConstructionKernel.inspect(new InterfaceInoutSource("empty"))) ==
        "NODAL-INTERFACE-054-012")
      assert(failure(ConstructionKernel.inspect(new InterfaceInoutSource("ambiguous"))) ==
        "NODAL-INTERFACE-054-012")

    test("constrained low high and release are separate effects"):
      val effects = ConstructionKernel.inspect(new InterfaceLowHighSource).digitalConnectivity
      assert(effects.map(_.kind) == Vector("inout-drive-low", "inout-high-z", "inout-drive-high"))
      assert(effects.filter(_.kind != "inout-high-z").forall(_.enable.nonEmpty))
      assert(effects.forall(_.value.isEmpty))

    test("nested inversion proves actual complementary maps and preserves double inversion"):
      assert(failure(ConstructionKernel.inspect(new InterfaceNestedInverseSource(false))) ==
        "NODAL-INTERFACE-054-004")
      val snapshot = ConstructionKernel.inspect(new InterfaceNestedInverseSource(true))
      val observations = snapshot.digitalConnectivity.filter(_.kind == "interface-observe")
      assert(observations.map(_.access) == Vector(Some("in"), Some("out")))
      assert(observations.map(_.endpointRole) == Vector(Some("slave"), Some("master")))
      assert(observations.forall(_.target.endsWith(".endpoint.child.data")))

    test("unused nested inversion cannot hide resolved capabilities"):
      assert(failure(ConstructionKernel.inspect(new InterfaceUnusedInvalidInverseSource)) ==
        "NODAL-INTERFACE-054-004")

    test("nested names never infer an unbound role and duplicate bindings must agree"):
      assert(failure(ConstructionKernel.inspect(new InterfaceConflictingRoleSource)) ==
        "NODAL-INTERFACE-054-002")
      val missing = Interface[InterfaceCaptureNested](
        "Missing",
        InterfaceMember.nested("link", InterfaceCaptureContracts.link)
      )
      val role = Role[SourceRole]("outer", RoleAccess.Nested("link", "initiator"))
      assert(failure(ConstructionInterfaceLayout.leaves(missing, role)) ==
        "NODAL-INTERFACE-054-002")
      val leaves = ConstructionInterfaceLayout.leaves(
        InterfaceCaptureContracts.nested,
        InterfaceCaptureContracts.outer
      )
      assert(leaves.find(_.name == "link.response.payload").get.access == "in")
      val monitor =
        ConstructionInterfaceLayout.leaves(InterfaceCaptureContracts.nested, nodal.monitor)
      assert(monitor.forall(_.access == "observe"))
