package nodal_external

import nodal.*

sealed trait HierarchyPayloadLink extends Interface
object HierarchyPayloadContract:
  val producer = Role[SourceRole]("producer", RoleAccess.Master("request"))
  val consumer = Role[SinkRole]("consumer", RoleAccess.Slave("request"))
  def payload(width: Expr[Integer]): DataType[Struct] = Struct(
    "HierarchyPayload",
    StructField("tag", Bits(width)),
    StructField("lanes", Vec(UInt(width), 2))
  )
  def link(width: Expr[Integer]): InterfaceType[HierarchyPayloadLink] =
    Interface[HierarchyPayloadLink](
      "HierarchyPayloadLink",
      InterfaceMember.valid("request", payload(width))
    ).withRoles(producer, consumer)

final class HierarchyPayloadLeaf(active: Boolean) extends Module:
  val required = ClockDomain.required("required")
  val width = param(2.integer, range = 1 to 8)
  val count = param(2.integer, range = 1 to 4)
  val data = in(HierarchyPayloadContract.payload(width))
  val valid = in(Bool)
  val arrayData = in(Vec(HierarchyPayloadContract.payload(width), count))
  val arrayValid = in(Vec(Bool, count))
  val output = interfacePort(
    HierarchyPayloadContract.link(width),
    HierarchyPayloadContract.producer,
    "output",
    required
  )
  val incoming = interfacePort(
    HierarchyPayloadContract.link(width),
    HierarchyPayloadContract.consumer,
    "incoming",
    required
  )
  val array = interfaceArray(
    HierarchyPayloadContract.link(width),
    HierarchyPayloadContract.producer,
    count,
    "array",
    required
  )
  if active then
    output.driveMember("request.payload", data)
    output.driveMember("request.valid", valid)
    array.driveMember("request.payload", arrayData)
    array.driveMember("request.valid", arrayValid)
    incoming.observeMember("request")

final class HierarchyPayloadParent(mode: String = "late") extends Module:
  val core = ClockDomain.external(
    "core",
    ClockEdge.Rising,
    ResetPolicy.None,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val p = param(3.integer, range = 2 to 4)
  val other = param(3.integer, range = 2 to 4)
  val n = param(2.integer, range = 1 to 3)
  val width = p + 1.integer
  val data = in(HierarchyPayloadContract.payload(width))
  val wrong = in(HierarchyPayloadContract.payload(other + 1.integer))
  val valid = in(Bool)
  val arrayData = in(Vec(HierarchyPayloadContract.payload(width), n))
  val arrayValid = in(Vec(Bool, n))
  val output = interfacePort(
    HierarchyPayloadContract.link(width),
    HierarchyPayloadContract.producer,
    "output",
    core
  )
  val array = interfaceArray(
    HierarchyPayloadContract.link(width),
    HierarchyPayloadContract.producer,
    n,
    "array",
    core
  )
  val child = instance(new HierarchyPayloadLeaf(mode != "no-access")).domain(core)
  if mode == "early" then
    child.param(_.width, width)
    child.param(_.count, n)
  val projected = child(_.output)
  val incoming = child(_.incoming)
  val projectedArray = child(_.array)
  require(projected eq child(_.output))
  if mode != "early" then
    child.param(_.width, width)
    child.param(_.count, n)
  if mode != "no-access" then
    child(_.data) := data
    child(_.valid) := valid
    child(_.arrayData) := arrayData
    child(_.arrayValid) := arrayValid
    mode match
      case "wrong-type" => incoming.driveMember("request.payload", wrong)
      case "write-output" => projected.driveMember("request.payload", data)
      case "raw" => output.forwardExact(child(value => Some(value.output)).get)
      case _ =>
        incoming.driveMember("request.payload", data)
        incoming.driveMember("request.valid", valid)
        output.forwardExact(projected)
        array.forwardExact(projectedArray)
        projected.observeMember("request")
