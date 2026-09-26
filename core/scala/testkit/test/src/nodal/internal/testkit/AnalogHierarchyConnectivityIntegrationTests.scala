package nodal.internal.testkit

import nodal.*

import scala.compiletime.testing.typeCheckErrors

import utest.*

final class ConnectivityIntegrationChild extends Module:
  val vin: Node[Electrical.type] = in(Electrical)
  val vout: Node[Electrical.type] = out(Electrical)
  val internal: Node[Electrical.type] = node(Electrical)

final class ConnectivityIntegrationTop(form: Int) extends Module:
  val vin: Node[Electrical.type] = in(Electrical)
  val vout: Node[Electrical.type] = out(Electrical)
  val amp: ConnectivityIntegrationChild = new ConnectivityIntegrationChild
  val handle: Instance[ConnectivityIntegrationChild] = instance(amp)

  form match
    case 0 =>
      connect(vin, handle(_.vin))
      connect(handle(_.vout), vout)
    case 1 =>
      vin <> amp.vin
      amp.vout <> vout
    case 2 =>
      amp.vin <> vin
      vout <> amp.vout
    case _ =>
      connect[Electrical.type](vin, handle(_.vin))
      connect[Electrical.type](handle(_.vout), vout)

final class InternalConnectionIntegrationTop extends Module:
  val vin: Node[Electrical.type] = in(Electrical)
  val amp: ConnectivityIntegrationChild = new ConnectivityIntegrationChild
  val handle: Instance[ConnectivityIntegrationChild] = instance(amp)
  vin <> amp.internal

final class SiblingInternalConnectionIntegrationTop extends Module:
  val left: Instance[ConnectivityIntegrationChild] = instance(new ConnectivityIntegrationChild)
  val right: Instance[ConnectivityIntegrationChild] = instance(new ConnectivityIntegrationChild)
  left(_.vin) <> right(_.internal)

final class SiblingPortConnectionIntegrationTop extends Module:
  val left: Instance[ConnectivityIntegrationChild] = instance(new ConnectivityIntegrationChild)
  val right: Instance[ConnectivityIntegrationChild] = instance(new ConnectivityIntegrationChild)
  left(_.vout) <> right(_.vin)

final class NestedConnectivityIntegrationChild extends Module:
  val leaf: Instance[ConnectivityIntegrationChild] = instance(new ConnectivityIntegrationChild)
  val descendantPort: Node[Electrical.type] = leaf(_.vin)

final class DescendantConnectionIntegrationTop extends Module:
  val vin: Node[Electrical.type] = in(Electrical)
  val child: Instance[NestedConnectivityIntegrationChild] =
    instance(new NestedConnectivityIntegrationChild)
  vin <> child(_.descendantPort)

final class CapturedConnectionIntegrationTop(capture: Node[Electrical.type] => Unit) extends Module:
  val pin: Node[Electrical.type] = in(Electrical)
  capture(pin)

final class DetachedConnectionIntegrationTop(detached: Node[Electrical.type]) extends Module:
  val pin: Node[Electrical.type] = in(Electrical)
  connect(pin, detached)

final class NamedDisciplineConnectionIntegrationTop(incompatible: Boolean) extends Module:
  val first: NamedDiscipline = discipline("first", Voltage, Current)
  val second: NamedDiscipline =
    if incompatible then discipline("second", Current, Voltage)
    else discipline("second", Voltage, Current)
  val left: Node[NamedDiscipline] = node(first)
  val right: Node[NamedDiscipline] = node(second)
  left <> right

final class MixedCompatibleConnectionIntegrationTop(form: Int) extends Module:
  val equivalent: NamedDiscipline = discipline("equivalent", Voltage, Current)
  val left: Node[Electrical.type] = node(Electrical)
  val right: Node[NamedDiscipline] = node(equivalent)
  form match
    case 0 => connect(left, right)
    case 1 => left <> right
    case 2 => right <> left
    case _ => connect[Discipline](left, right)

final class DistinctNatureConnectionIntegrationTop extends Module:
  val first: NamedDiscipline = discipline("first", Voltage, Current)
  val second: NamedDiscipline = discipline("second", nature("voltage"), Current)
  val left: Node[NamedDiscipline] = node(first)
  val right: Node[NamedDiscipline] = node(second)
  connect(left, right)

final class UnknownDimensionConnectionIntegrationTop extends Module:
  val potential: Nature = nature("unknown_dimension")
  val first: NamedDiscipline = discipline("first", potential, Current)
  val second: NamedDiscipline = discipline("second", potential, Current)
  val left: Node[NamedDiscipline] = node(first)
  val right: Node[NamedDiscipline] = node(second)
  left <> right

final class ConnectionOrientationIntegrationTop(reverse: Boolean) extends Module:
  val a: Node[Electrical.type] = node(Electrical)
  val b: Node[Electrical.type] = node(Electrical)
  if reverse then b <> a else a <> b

  val z: Terminal[Electrical.type] = terminal(Electrical, "z")
  val t: Terminal[Electrical.type] = terminal(Electrical, "t")
  z.connectView.connectTo(t.connectView)

  val padZ: DigitalInout[Bits, DriveMode.PushPull] = digitalInout(
    Bits(1),
    DriveMode.pushPull,
    InoutPlacement.TopLevelPin,
    ResolutionProfile.FullResolvedSimulation,
    "padZ"
  )
  val padA: DigitalInout[Bits, DriveMode.PushPull] = digitalInout(
    Bits(1),
    DriveMode.pushPull,
    InoutPlacement.HierarchyPassThrough,
    ResolutionProfile.FullResolvedSimulation,
    "padA"
  )
  passThrough(padZ, padA)

  analog:
    V(a, b) <+ 2.0.V
    I(a, b) <+ 1.0.A

object AnalogHierarchyConnectivityIntegrationTests extends TestSuite:
  private def failure(top: => Module): ConstructionException =
    scala.util.Try(ConstructionKernel.inspect(top)).failed.get.asInstanceOf[ConstructionException]

  val tests: Tests = Tests:
    test("direct ports and reversed operator share explicit and typed connect topology"):
      val snapshots = Vector(0, 1, 2, 3).map: form =>
        ConstructionKernel.inspect(new ConnectivityIntegrationTop(form))
      assert(snapshots.map(_.topology).distinct.size == 1)
      assert(snapshots.head.topology.count(_.kind == "node-connect") == 2)
      assert(snapshots.forall(_.modules.find(_.path == "ConnectivityIntegrationTop")
        .exists(_.instances.size == 1)))

    test("parent can connect ports of two attached immediate children"):
      val snapshot = ConstructionKernel.inspect(new SiblingPortConnectionIntegrationTop)
      assert(snapshot.topology.count(_.kind == "node-connect") == 1)

    test("public Scala visibility does not permit child or sibling internal endpoints"):
      assert(failure(new InternalConnectionIntegrationTop).diagnostic.code == "NODAL-HIERARCHY-040")
      assert(
        failure(new SiblingInternalConnectionIntegrationTop).diagnostic.code ==
          "NODAL-HIERARCHY-040"
      )

    test("an exported descendant reference is not an immediate child port"):
      assert(
        failure(new DescendantConnectionIntegrationTop).diagnostic.code == "NODAL-HIERARCHY-040"
      )

    test("an endpoint from another construction transaction is rejected"):
      var detached: Option[Node[Electrical.type]] = None
      val _ = ConstructionKernel.inspect(new CapturedConnectionIntegrationTop(pin =>
        detached = Some(pin)
      ))
      assert(
        failure(new DetachedConnectionIntegrationTop(detached.get)).diagnostic.code ==
          "NODAL-HIERARCHY-038"
      )

    test("distinct compatible named disciplines share their declared natures"):
      val snapshot = ConstructionKernel.inspect(new NamedDisciplineConnectionIntegrationTop(false))
      assert(snapshot.topology.count(_.kind == "node-connect") == 1)

    test("Electrical and compatible named discipline use the same public conservative operation"):
      val snapshots = Vector(0, 1, 2, 3).map: form =>
        ConstructionKernel.inspect(new MixedCompatibleConnectionIntegrationTop(form))
      assert(snapshots.map(_.topology).distinct.size == 1)
      assert(snapshots.head.topology.count(_.kind == "node-connect") == 1)

    test("incompatible or only name-matching nature declarations are rejected"):
      assert(
        failure(new NamedDisciplineConnectionIntegrationTop(true)).diagnostic.code ==
          "NODAL-HIERARCHY-041"
      )
      assert(
        failure(new DistinctNatureConnectionIntegrationTop).diagnostic.code == "NODAL-HIERARCHY-041"
      )

    test("unknown conservative dimensions fail closed"):
      assert(
        failure(new UnknownDimensionConnectionIntegrationTop).diagnostic.code ==
          "NODAL-HIERARCHY-042"
      )

    test("node reversal preserves branch access and existing terminal and digital directions"):
      val first = ConstructionKernel.inspect(new ConnectionOrientationIntegrationTop(false))
      val reversed = ConstructionKernel.inspect(new ConnectionOrientationIntegrationTop(true))
      assert(first.topology == reversed.topology)
      val operations = Set("potential_access", "flow_access")
      val accesses = first.analogRegions.flatMap(_.expressions)
        .filter(expression => operations.contains(expression.operation))
      val reversedAccesses = reversed.analogRegions.flatMap(_.expressions)
        .filter(expression => operations.contains(expression.operation))
      val expectedOperands = Vector(
        "ConnectionOrientationIntegrationTop.a",
        "ConnectionOrientationIntegrationTop.b"
      )
      assert(accesses.size == 2)
      assert(accesses.forall(_.operands == expectedOperands))
      val firstPairs = accesses.map(expression => expression.operation -> expression.operands).toSet
      val reversedPairs = reversedAccesses
        .map(expression => expression.operation -> expression.operands).toSet
      assert(firstPairs == reversedPairs)
      assert(first.topology.exists(edge =>
        edge.kind == "terminal-connect" && edge.left.endsWith(".z") && edge.right.endsWith(".t")
      ))
      assert(first.topology.exists(edge =>
        edge.kind == "inout-pass-through" && edge.left.endsWith(".padZ") &&
        edge.right.endsWith(".padA")
      ))

    test("digital operands and private child members fail Scala type checking"):
      val digitalErrors = typeCheckErrors("""
        import nodal.*
        final class InvalidDigitalNodeConnection extends Module:
          val left = in(UInt(8))
          val right = out(UInt(8))
          left <> right
      """)
      val privateErrors = typeCheckErrors("""
        import nodal.*
        final class PrivateNodeChild extends Module:
          private val hidden = in(Electrical)
        final class InvalidPrivateNodeConnection extends Module:
          val pin = in(Electrical)
          val amp = new PrivateNodeChild
          val handle = instance(amp)
          pin <> amp.hidden
      """)
      assert(digitalErrors.exists(_.message.contains("<>")))
      assert(privateErrors.exists(_.message.contains("hidden")))

    test("failed connection construction leaves the next transaction clean"):
      assert(failure(new InternalConnectionIntegrationTop).diagnostic.code == "NODAL-HIERARCHY-040")
      assert(
        failure(new NamedDisciplineConnectionIntegrationTop(true)).diagnostic.code ==
          "NODAL-HIERARCHY-041"
      )
      val recovered = ConstructionKernel.inspect(new ConnectivityIntegrationTop(1))
      assert(recovered.topology.count(_.kind == "node-connect") == 2)
