package nodal.internal.testkit

import java.util.IdentityHashMap

import nodal.*
import utest.*

private sealed trait ObservationContract extends Interface
private sealed trait ObservationNestedContract extends Interface

/** Exercise the Interface owner's canonical facts independently of a sampling consumer. */
private final class InterfaceObservationFixture:
  val owner = "root"
  private val paths = new IdentityHashMap[AnyRef, String]()
  private val declarations =
    scala.collection.mutable.ArrayBuffer.empty[DigitalInterfaceContract.Declaration]
  private val operations =
    scala.collection.mutable.ArrayBuffer.empty[DigitalInterfaceContract.Operation]
  val domain = ClockDomain.external(
    "external",
    ClockEdge.Rising,
    ResetPolicy.None,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  private def render(dataType: DataType[?]): String =
    val descriptor = CandidateRuntime.typeDescriptor(dataType)
    if descriptor.arguments.isEmpty then descriptor.kind
    else s"${descriptor.kind}(${descriptor.arguments.mkString(",")})"
  private def declare(value: AnyRef, name: String): Unit =
    val path = s"$owner.$name"
    paths.put(value, path)
    declarations += DigitalInterfaceContract.Declaration(
      value,
      path,
      owner,
      Some("untrusted_endpoint_domain"),
      Vector.empty,
      render,
      _ => (),
      _ => (),
      (left, right) =>
        CandidateRuntime.typeDescriptor(left) == CandidateRuntime.typeDescriptor(right)
    )
  def endpoint[I <: Interface, R <: RoleKind](
      definition: InterfaceType[I],
      role: Role[R],
      name: String
  )
      : InterfacePort[I, R] =
    val value = new InterfacePort(definition, role, name, domain, exported = true)
    declare(value, name)
    value
  def net(name: String): DigitalInout[UInt, DriveMode.PushPull] =
    val value = digitalInout(
      UInt(8),
      DriveMode.pushPull,
      InoutPlacement.HierarchyPassThrough,
      ResolutionProfile.PortableBoundaryOnly,
      name
    )
    declare(value, name)
    value
  def expression[A <: Data](name: String, dataType: DataType[A]): Expr[A] =
    val value =
      new KernelExpr[A](Vector.empty, resultType = Some(CandidateRuntime.typeDescriptor(dataType)))
    paths.put(value, s"$owner.$name")
    value
  def effect(kind: String, values: Any*): Unit =
    operations += DigitalInterfaceContract.Operation(kind, values.toVector, owner)
  def capture: DigitalInterfaceContract.Capture = DigitalInterfaceContract.capture(
    declarations.toVector,
    operations.toVector,
    value =>
      value match
        case reference: AnyRef => Option(paths.get(reference))
        case _ => None,
    value =>
      value match
        case reference: AnyRef if paths.containsKey(reference) => Some(owner)
        case _ => None
  )

object Increment57InterfaceObservationTests extends TestSuite:
  private val producer = Role[SourceRole](
    "producer",
    RoleAccess.Master("request"),
    RoleAccess.Master("response"),
    RoleAccess.Out("flag")
  )
  private val consumer = Role[SinkRole](
    "consumer",
    RoleAccess.Slave("request"),
    RoleAccess.Slave("response"),
    RoleAccess.In("flag")
  )
  private val definition = Interface[ObservationContract](
    "ObservationContract",
    InterfaceMember.stream("request", Bool),
    InterfaceMember.valid("response", Bool),
    InterfaceMember.value("flag", Bool)
  ).withRoles(producer, consumer)

  val tests = Tests:
    test(
      "forward protocol leaves form one observation but ready members and endpoints stay separate"
    ):
      val f = new InterfaceObservationFixture
      val left = f.endpoint(definition, producer, "left")
      val right = f.endpoint(definition, consumer, "right")
      val unused = f.endpoint(definition, producer, "unused")
      val payload = f.expression("payload", Bool)
      val valid = f.expression("valid", Bool)
      val ready = f.expression("ready", Bool)
      val flag = f.expression("flag", Bool)
      f.effect("interface-drive", left, "request.payload", payload)
      f.effect("interface-drive", left, "request.valid", valid)
      f.effect("interface-drive", right, "request.ready", ready)
      f.effect("interface-drive", left, "response.valid", flag)
      f.effect("interface-drive", left, "flag", flag)
      f.effect("interface-observe", unused.monitorView, "request")
      val groups = f.capture.observationGroups.map(group => group.path -> group.values).toMap
      assert(groups.keySet == Set(
        "root.left.request",
        "root.right.request.ready",
        "root.left.response",
        "root.left.flag"
      ))
      assert(groups("root.left.request").toSet == Set(payload, valid))
      assert(groups("root.right.request.ready") == Vector(ready))
      assert(groups("root.left.response") == Vector(flag))
      assert(groups("root.left.flag") == Vector(flag))

    test("directed connections retain per endpoint observations without reversing sources"):
      val f = new InterfaceObservationFixture
      val left = f.endpoint(definition, producer, "left")
      val right = f.endpoint(definition, consumer, "right")
      val other = f.endpoint(definition, consumer, "other")
      val payload = f.expression("payload", Bool)
      val valid = f.expression("valid", Bool)
      val ready = f.expression("ready", Bool)
      f.effect("interface-drive", left, "request.payload", payload)
      f.effect("interface-drive", left, "request.valid", valid)
      f.effect("interface-drive", right, "request.ready", ready)
      f.effect("interface-connect", left, right)
      // A distinct, unrelated endpoint must not join the connected protocol group.
      f.effect("interface-drive", other, "request.ready", valid)
      val groups = f.capture.observationGroups.map(group => group.path -> group.values.toSet).toMap
      assert(groups("root.left.request") == Set(payload, valid))
      assert(groups("root.right.request") == Set(payload, valid))
      assert(groups("root.left.request.ready") == Set(ready))
      assert(groups("root.right.request.ready") == Set(ready))
      assert(groups("root.other.request.ready") == Set(valid))
      assert(!groups.contains("root.other.request"))

    test("nested protocol identity is canonical and a reused result is not copied"):
      val f = new InterfaceObservationFixture
      val nested = Interface[ObservationNestedContract](
        "ObservationNestedContract",
        InterfaceMember.nested("link", definition)
      )
      val role = Role[SourceRole]("outer", RoleAccess.Nested("link", "producer"))
      val endpoint = f.endpoint(nested, role, "endpoint")
      val value = f.expression("value", Bool)
      f.effect("interface-drive", endpoint, "link.request.payload", value)
      f.effect("interface-drive", endpoint, "link.request.valid", value)
      f.effect("interface-observe", endpoint.monitorView, "link.request")
      f.effect("interface-observe", endpoint.monitorView, "link.request.valid")
      val groups = f.capture.observationGroups
      assert(groups.size == 1, groups.head.path == "root.endpoint.link.request")
      assert(groups.head.owner == f.owner, groups.head.values == Vector(value))

    test("physical resolved aliases retain every actual data and enable driver exactly once"):
      val f = new InterfaceObservationFixture
      val first = f.net("first")
      val second = f.net("second")
      val third = f.net("third")
      val unused = f.net("unused")
      val data = f.expression("data", UInt(8))
      val otherData = f.expression("other_data", UInt(8))
      val enable = f.expression("enable", Bool)
      val otherEnable = f.expression("other_enable", Bool)
      f.effect("inout-drive", first, data, enable)
      f.effect("inout-drive", third, otherData, otherEnable)
      f.effect("inout-pass-through", second, first)
      f.effect("inout-pass-through", third, second)
      f.effect("inout-high-z", unused)
      val groups = f.capture.observationGroups
      assert(groups.size == 1, groups.head.path == "root.first")
      assert(groups.head.values.toSet == Set(data, enable, otherData, otherEnable))

    test("independent resolved members remain separate from coherent protocol observations"):
      val f = new InterfaceObservationFixture
      val definition = Interface[ObservationContract](
        "ResolvedObservationContract",
        InterfaceMember.digitalInout("pad", UInt(8), DriveMode.pushPull),
        InterfaceMember.digitalInout("other", UInt(8), DriveMode.pushPull)
      )
      val role = Role[SourceRole]("driver", RoleAccess.Drive("pad"), RoleAccess.Drive("other"))
      val endpoint = f.endpoint(definition, role, "endpoint")
      val first = f.expression("first", UInt(8))
      val second = f.expression("second", UInt(8))
      f.effect("interface-drive", endpoint, "pad", first)
      f.effect("interface-drive", endpoint, "pad", second)
      f.effect("interface-drive", endpoint, "other", first)
      val groups = f.capture.observationGroups.map(group => group.path -> group.values.toSet).toMap
      assert(groups("root.endpoint.pad") == Set(first, second))
      assert(groups("root.endpoint.other") == Set(first))

    test("undriven observed inputs are not invented local sources"):
      val f = new InterfaceObservationFixture
      val endpoint = f.endpoint(definition, consumer, "external")
      f.effect("interface-observe", endpoint.monitorView, "request")
      assert(f.capture.observationGroups.isEmpty)

    test("one forward source may fan out without joining independent endpoint observations"):
      val f = new InterfaceObservationFixture
      val definition = Interface[ObservationContract](
        "FanoutObservationContract",
        InterfaceMember.valid("data", Bool)
      )
      val producer = Role[SourceRole]("producer", RoleAccess.Master("data"))
      val consumer = Role[SinkRole]("consumer", RoleAccess.Slave("data"))
      val source = f.endpoint(definition, producer, "source")
      val first = f.endpoint(definition, consumer, "first")
      val second = f.endpoint(definition, consumer, "second")
      val value = f.expression("value", Bool)
      f.effect("interface-drive", source, "data.payload", value)
      f.effect("interface-drive", source, "data.valid", value)
      f.effect("interface-connect", source, first)
      f.effect("interface-connect", source, second)
      val groups = f.capture.observationGroups
      assert(groups.map(_.path).toSet ==
        Set("root.source.data", "root.first.data", "root.second.data"))
      assert(groups.forall(_.values == Vector(value)))

    test("ordinary directed cycles cannot manufacture a driven observation"):
      val f = new InterfaceObservationFixture
      val left = f.endpoint(definition, producer, "left")
      val right = f.endpoint(definition, consumer, "right")
      f.effect("interface-connect", left, right)
      f.effect("interface-connect", left.inverted, right.inverted)
      val failure = scala.util.Try(f.capture).failed.get
      assert(failure.getMessage.contains("NODAL-INTERFACE-054-011"))

    test("repeated physical aliases resolve once without expanding driver sets per alias"):
      val f = new InterfaceObservationFixture
      val nets = Vector.tabulate(128)(index => f.net(s"net_$index"))
      val data = f.expression("data", UInt(8))
      val enables = Vector.tabulate(128)(index => f.expression(s"enable_$index", Bool))
      nets.zip(enables).foreach((net, enable) => f.effect("inout-drive", net, data, enable))
      nets.tail.foreach(net => f.effect("inout-pass-through", nets.head, net))
      val groups = f.capture.observationGroups
      assert(groups.size == 1, groups.head.values.size == 129)
      assert(groups.head.values.toSet == enables.toSet + data)
