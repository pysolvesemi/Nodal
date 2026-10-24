package nodal

/** Public Interface, inout, AMS, and pipeline surface frozen by Increment 15. F54 captures typed
  * digital connectivity; scheduling, target lowering and simulation have separate implementation
  * owners.
  */

/** Directionless, storable record candidate. */
opaque type Struct <: Data = Bits

final case class StructField[A <: Data](name: String, dataType: DataType[A])

object Struct:
  def apply(name: String, fields: StructField[? <: Data]*): DataType[Struct] =
    CandidateRuntime.dataType[Struct]("Struct", name, fields.toSeq)

/** Non-storable connectivity kind. External libraries may define phantom interface identities. */
trait Interface

sealed trait InterfaceMember

object InterfaceMember:
  final case class Value[A <: Data](name: String, dataType: DataType[A])
      extends InterfaceMember
  final case class ValidChannel[A <: Data](name: String, payloadType: DataType[A])
      extends InterfaceMember
  final case class StreamChannel[A <: Data](name: String, payloadType: DataType[A])
      extends InterfaceMember
  final case class Nested[I <: Interface](name: String, definition: InterfaceType[I])
      extends InterfaceMember
  final case class DigitalResolved[A <: Bits, M <: DriveMode](
      name: String,
      dataType: DataType[A],
      mode: DriveMode.Value[M]
  ) extends InterfaceMember
  final case class Conservative[D <: Discipline](name: String, discipline: D)
      extends InterfaceMember
  final case class SignalFlow[D](name: String, dimension: String) extends InterfaceMember

  def value[A <: Data](name: String, dataType: DataType[A]): Value[A] =
    Value(name, dataType)

  def valid[A <: Data](name: String, payloadType: DataType[A]): InterfaceMember =
    ValidChannel(name, payloadType)

  def stream[A <: Data](name: String, payloadType: DataType[A]): InterfaceMember =
    StreamChannel(name, payloadType)

  def nested[I <: Interface](name: String, definition: InterfaceType[I]): Nested[I] =
    Nested(name, definition)

  def digitalInout[A <: Bits, M <: DriveMode](
      name: String,
      dataType: DataType[A],
      mode: DriveMode.Value[M]
  ): DigitalResolved[A, M] = DigitalResolved(name, dataType, mode)

  def terminal[D <: Discipline](name: String, discipline: D): Conservative[D] =
    Conservative(name, discipline)

  def analogSignal[D](name: String, dimension: String): InterfaceMember =
    SignalFlow[D](name, dimension)

final class InterfaceType[I <: Interface] private[nodal] (
    val name: String,
    val members: Seq[InterfaceMember],
    private[nodal] val roles: Vector[Role[?]] = Vector.empty,
    private[nodal] val constructionContext: Option[InterfaceDefinitionContext] = None
):
  /** Bind actual named role maps for nested Interface applications without global registration. */
  def withRoles(bindings: Role[?]*): InterfaceType[I] =
    new InterfaceType(name, members, roles ++ bindings, constructionContext)

object Interface:
  def apply[I <: Interface](name: String, members: InterfaceMember*): InterfaceType[I] =
    new InterfaceType(
      name,
      members.toSeq,
      constructionContext = ConstructionKernel.interfaceDefinitionContext()
    )

/** Stable named role identity. */
trait RoleKind

sealed trait MasterRole extends RoleKind
sealed trait SlaveRole extends RoleKind
sealed trait SourceRole extends RoleKind
sealed trait SinkRole extends RoleKind
sealed trait InitiatorRole extends RoleKind
sealed trait TargetRole extends RoleKind
sealed trait ControllerRole extends RoleKind
sealed trait PeripheralRole extends RoleKind
sealed trait DeviceRole extends RoleKind
sealed trait EnvironmentRole extends RoleKind
sealed trait MonitorRole extends RoleKind

/** Per-member access retained independently of source sugar. */
enum RoleAccess:
  case In(member: String)
  case Out(member: String)
  case Observe(member: String)
  case Master(member: String)
  case Slave(member: String)
  case Read(member: String)
  case Drive(member: String)
  case Connect(member: String)
  case Sense(member: String)
  case Contribute(member: String)
  case Nested(member: String, role: String)

object RoleAccess:
  private[nodal] def inverted(access: RoleAccess): RoleAccess = access match
    case RoleAccess.In(member) => RoleAccess.Out(member)
    case RoleAccess.Out(member) => RoleAccess.In(member)
    case RoleAccess.Observe(member) => RoleAccess.Observe(member)
    case RoleAccess.Master(member) => RoleAccess.Slave(member)
    case RoleAccess.Slave(member) => RoleAccess.Master(member)
    case RoleAccess.Read(member) => RoleAccess.Drive(member)
    case RoleAccess.Drive(member) => RoleAccess.Read(member)
    case RoleAccess.Connect(member) => RoleAccess.Connect(member)
    case RoleAccess.Sense(member) => RoleAccess.Contribute(member)
    case RoleAccess.Contribute(member) => RoleAccess.Sense(member)
    case RoleAccess.Nested(member, role) =>
      RoleAccess.Nested(member, invertedRoleName(role))

  private def invertedRoleName(role: String): String = role match
    case "master" => "slave"
    case "slave" => "master"
    case "source" => "sink"
    case "sink" => "source"
    case "initiator" => "target"
    case "target" => "initiator"
    case "controller" => "peripheral"
    case "peripheral" => "controller"
    case "device" => "environment"
    case "environment" => "device"
    case other => s"inverse($other)"

final class Role[R <: RoleKind] private[nodal] (
    val name: String,
    val access: Seq[RoleAccess]
)

object Role:
  def apply[R <: RoleKind](name: String, access: RoleAccess*): Role[R] =
    new Role(name, access.toSeq)

val master: Role[MasterRole] = Role[MasterRole]("master")
val slave: Role[SlaveRole] = Role[SlaveRole]("slave")
val source: Role[SourceRole] = Role[SourceRole]("source")
val sink: Role[SinkRole] = Role[SinkRole]("sink")
val initiator: Role[InitiatorRole] = Role[InitiatorRole]("initiator")
val target: Role[TargetRole] = Role[TargetRole]("target")
val controller: Role[ControllerRole] = Role[ControllerRole]("controller")
val peripheral: Role[PeripheralRole] = Role[PeripheralRole]("peripheral")
val device: Role[DeviceRole] = Role[DeviceRole]("device")
val environment: Role[EnvironmentRole] = Role[EnvironmentRole]("environment")
val monitor: Role[MonitorRole] = Role[MonitorRole]("monitor")

/** Evidence for the small set of fully complementary digital roles. */
trait RoleInverse[R <: RoleKind]:
  type Out <: RoleKind
  def inverse: Role[Out]

object RoleInverse:
  given masterInverse: RoleInverse[MasterRole] with
    type Out = SlaveRole
    def inverse: Role[SlaveRole] = slave

  given slaveInverse: RoleInverse[SlaveRole] with
    type Out = MasterRole
    def inverse: Role[MasterRole] = master

  given sourceInverse: RoleInverse[SourceRole] with
    type Out = SinkRole
    def inverse: Role[SinkRole] = sink

  given sinkInverse: RoleInverse[SinkRole] with
    type Out = SourceRole
    def inverse: Role[SourceRole] = source

  given initiatorInverse: RoleInverse[InitiatorRole] with
    type Out = TargetRole
    def inverse: Role[TargetRole] = target

  given targetInverse: RoleInverse[TargetRole] with
    type Out = InitiatorRole
    def inverse: Role[InitiatorRole] = initiator

  given controllerInverse: RoleInverse[ControllerRole] with
    type Out = PeripheralRole
    def inverse: Role[PeripheralRole] = peripheral

  given peripheralInverse: RoleInverse[PeripheralRole] with
    type Out = ControllerRole
    def inverse: Role[ControllerRole] = controller

  given deviceInverse: RoleInverse[DeviceRole] with
    type Out = EnvironmentRole
    def inverse: Role[EnvironmentRole] = environment

  given environmentInverse: RoleInverse[EnvironmentRole] with
    type Out = DeviceRole
    def inverse: Role[DeviceRole] = device

/** Exact role-compatibility evidence. External libraries may publish additional instances. */
trait RoleConnection[A <: RoleKind, B <: RoleKind]

object RoleConnection:
  given masterSlave: RoleConnection[MasterRole, SlaveRole] with {}
  given slaveMaster: RoleConnection[SlaveRole, MasterRole] with {}
  given sourceSink: RoleConnection[SourceRole, SinkRole] with {}
  given sinkSource: RoleConnection[SinkRole, SourceRole] with {}
  given initiatorTarget: RoleConnection[InitiatorRole, TargetRole] with {}
  given targetInitiator: RoleConnection[TargetRole, InitiatorRole] with {}
  given controllerPeripheral: RoleConnection[ControllerRole, PeripheralRole] with {}
  given peripheralController: RoleConnection[PeripheralRole, ControllerRole] with {}
  given deviceEnvironment: RoleConnection[DeviceRole, EnvironmentRole] with {}
  given environmentDevice: RoleConnection[EnvironmentRole, DeviceRole] with {}

trait RoleCanDrive[R <: RoleKind]

object RoleCanDrive:
  given masterCanDrive: RoleCanDrive[MasterRole] with {}
  given slaveCanDrive: RoleCanDrive[SlaveRole] with {}
  given sourceCanDrive: RoleCanDrive[SourceRole] with {}
  given sinkCanDrive: RoleCanDrive[SinkRole] with {}
  given initiatorCanDrive: RoleCanDrive[InitiatorRole] with {}
  given targetCanDrive: RoleCanDrive[TargetRole] with {}
  given controllerCanDrive: RoleCanDrive[ControllerRole] with {}
  given peripheralCanDrive: RoleCanDrive[PeripheralRole] with {}
  given deviceCanDrive: RoleCanDrive[DeviceRole] with {}
  given environmentCanDrive: RoleCanDrive[EnvironmentRole] with {}

private[nodal] sealed trait InterfaceEndpoint:
  def definition: InterfaceType[?]
  def role: Role[?]
  def name: String
  def domain: ClockDomain
  private[nodal] def domainOption: Option[ClockDomain] = Some(domain)
  private[nodal] def boundary: Boolean

final class InterfacePort[I <: Interface, R <: RoleKind] private[nodal] (
    val definition: InterfaceType[I],
    val role: Role[R],
    val name: String,
    val domain: ClockDomain,
    private[nodal] val exported: Boolean,
    private[nodal] val origin: Option[InterfacePort[I, ?]] = None,
    private[nodal] val boundary: Boolean = false
) extends InterfaceEndpoint:
  if exported then
    CandidateRuntime.declare(
      this,
      KernelSignalKind.InterfacePort,
      explicitName = Some(name),
      domain = Some(domain),
      attributes = Vector("interface" -> definition.name, "role" -> role.name)
    )

final class InterfaceArray[I <: Interface, R <: RoleKind] private[nodal] (
    val definition: InterfaceType[I],
    val role: Role[R],
    private[nodal] val formalCount: Dimension,
    val name: String,
    override private[nodal] val domainOption: Option[ClockDomain],
    private[nodal] val exported: Boolean = true,
    private[nodal] val boundary: Boolean = false,
    private[nodal] val origin: Option[InterfaceArray[I, ?]] = None,
    private[nodal] val externalAttributes: Vector[(String, Any)] = Vector.empty
) extends InterfaceEndpoint:
  def domain: ClockDomain = domainOption.getOrElse(
    DigitalInterfaceContract.fail(6, "declaration-only external InterfaceArray has no ClockDomain")
  )

  /** Local declared count. A projected count is finalized after all Instance overrides. */
  def count: Dimension =
    if origin.nonEmpty then return origin.get.count
    if boundary then
      DigitalInterfaceContract.fail(
        7,
        "projected InterfaceArray count is formal metadata, not a parent expression"
      )
    formalCount
  if exported then
    CandidateRuntime.declare(
      this,
      KernelSignalKind.InterfaceArray,
      explicitName = Some(name),
      domain = domainOption,
      attributes = Vector(
        "interface" -> definition.name,
        "role" -> role.name,
        "count" -> formalCount
      ) ++ externalAttributes
    )

def interfacePort[I <: Interface, R <: RoleKind](
    definition: InterfaceType[I],
    role: Role[R],
    name: String,
    domain: ClockDomain
): InterfacePort[I, R] = new InterfacePort(definition, role, name, domain, exported = true)

def interfaceArray[I <: Interface, R <: RoleKind](
    definition: InterfaceType[I],
    role: Role[R],
    count: Dimension,
    name: String,
    domain: ClockDomain
): InterfaceArray[I, R] = new InterfaceArray(definition, role, count, name, Some(domain))

/** A closed ordinary read capability bound to one exact endpoint and declared member. */
final class InterfaceValueRef[A <: Data] private[nodal] (
    private[nodal] val memberRecord: InterfaceValueReadRecord
):
  def read: Expr[A] =
    ConstructionKernel.interfaceValueRefRead(this).asInstanceOf[Expr[A]]

extension [I <: Interface, R <: RoleKind](endpoint: InterfacePort[I, R])
  /** Retain an exact member witness for checked instance or generated-family projection. */
  def valueRef[A <: Data](
      field: InterfaceMember.Value[A],
      through: InterfaceMember.Nested[?]*
  ): InterfaceValueRef[A] =
    ConstructionKernel.interfaceValueReference[A](endpoint, field, through.toVector)

  /** Read one exact ordinary Value declaration without changing endpoint orientation. */
  def read[A <: Data](
      field: InterfaceMember.Value[A],
      through: InterfaceMember.Nested[?]*
  ): Expr[A] =
    ConstructionKernel.interfaceValueRead(endpoint, field, through.toVector).asInstanceOf[Expr[A]]

  /** Select the exact conservative declaration and retain the actual role's rights. */
  def connectTerminal[D <: Discipline](
      field: InterfaceMember.Conservative[D],
      through: InterfaceMember.Nested[?]*
  ): ConservativeView[D, ConservativeAccess.Connect] =
    ConstructionKernel.interfaceConservativeMember[D, ConservativeAccess.Connect](
      endpoint,
      field,
      through.toVector,
      "connect"
    )

  def senseTerminal[D <: Discipline](
      field: InterfaceMember.Conservative[D],
      through: InterfaceMember.Nested[?]*
  ): ConservativeView[D, ConservativeAccess.Sense] =
    ConstructionKernel.interfaceConservativeMember[D, ConservativeAccess.Sense](
      endpoint,
      field,
      through.toVector,
      "sense"
    )

  def contributeTerminal[D <: Discipline](
      field: InterfaceMember.Conservative[D],
      through: InterfaceMember.Nested[?]*
  ): ConservativeView[D, ConservativeAccess.Contribute] =
    ConstructionKernel.interfaceConservativeMember[D, ConservativeAccess.Contribute](
      endpoint,
      field,
      through.toVector,
      "contribute"
    )

  /** Select an actual declared resolved member through its ordered declared nested fields. */
  def resolved[A <: Bits, M <: DriveMode](
      field: InterfaceMember.DigitalResolved[A, M],
      through: InterfaceMember.Nested[?]*
  ): ResolvedDigital[A, M] =
    ConstructionKernel.interfaceResolvedMember(endpoint, field, through.toVector)

  def inverted(using inverse: RoleInverse[R]): InterfacePort[I, inverse.Out] =
    DigitalInterfaceContract.requireInvertible(endpoint)
    val invertedRole = new Role[inverse.Out](
      inverse.inverse.name,
      endpoint.role.access.map(RoleAccess.inverted)
    )
    val view = new InterfacePort(
      endpoint.definition,
      invertedRole,
      s"${endpoint.name}.inverse",
      endpoint.domain,
      exported = false,
      origin = Some(endpoint)
    )
    ConstructionKernel.operation("interface-invert", endpoint, view)
    view

  def monitorView: InterfacePort[I, MonitorRole] =
    new InterfacePort(
      endpoint.definition,
      monitor,
      s"${endpoint.name}.monitor",
      endpoint.domain,
      exported = false,
      origin = Some(endpoint)
    )

  def connectExact[OtherRole <: RoleKind](
      other: InterfacePort[I, OtherRole]
  )(using RoleConnection[R, OtherRole]): Unit =
    ConstructionKernel.operation("interface-connect", endpoint, other)

  /** Forward an exported endpoint through one exact child boundary without changing its role. */
  def forwardExact(other: InterfacePort[I, R]): Unit =
    if DigitalInterfaceContract.root(endpoint).boundary ==
        DigitalInterfaceContract.root(other).boundary
    then DigitalInterfaceContract.fail(3, "forwardExact requires one local and one child endpoint")
    ConstructionKernel.operation("interface-connect", endpoint, other)

  def driveMember[A <: Data](member: String, value: Expr[A])(using RoleCanDrive[R]): Unit =
    ConstructionKernel.operation("interface-drive", endpoint, member, value)

  def observeMember(member: String): Unit =
    ConstructionKernel.operation("interface-observe", endpoint, member)

extension [I <: Interface, R <: RoleKind](endpoint: InterfaceArray[I, R])
  /** Retain the exact whole-array ordinary member without flattening the outer axis. */
  def valueRef[A <: Data](
      field: InterfaceMember.Value[A],
      through: InterfaceMember.Nested[?]*
  ): InterfaceValueRef[Vec[A]] =
    ConstructionKernel.interfaceValueReference[Vec[A]](endpoint, field, through.toVector)

  /** Read the exact whole outer family of one ordinary Value declaration. */
  def read[A <: Data](
      field: InterfaceMember.Value[A],
      through: InterfaceMember.Nested[?]*
  ): Expr[Vec[A]] =
    ConstructionKernel.interfaceValueRead(
      endpoint,
      field,
      through.toVector
    ).asInstanceOf[Expr[Vec[A]]]

  /** A whole-family resolved capability over this exact declared array member. */
  def resolved[A <: Bits, M <: DriveMode](
      field: InterfaceMember.DigitalResolved[A, M],
      through: InterfaceMember.Nested[?]*
  ): ResolvedDigitalArray[A, M] =
    ConstructionKernel.interfaceResolvedArrayMember(endpoint, field, through.toVector)

  def monitorView: InterfaceArray[I, MonitorRole] =
    new InterfaceArray(
      endpoint.definition,
      monitor,
      endpoint.formalCount,
      s"${endpoint.name}.monitor",
      endpoint.domainOption,
      exported = false,
      origin = Some(endpoint)
    )

  def connectExact[OtherRole <: RoleKind](
      other: InterfaceArray[I, OtherRole]
  )(using RoleConnection[R, OtherRole]): Unit =
    ConstructionKernel.operation("interface-connect", endpoint, other)

  /** Forward the whole structural array; no lane or member identity is flattened. */
  def forwardExact(other: InterfaceArray[I, R]): Unit =
    if endpoint.boundary == other.boundary then
      DigitalInterfaceContract.fail(3, "forwardExact requires one local and one child endpoint")
    ConstructionKernel.operation("interface-connect", endpoint, other)

  def driveMember[A <: Data](member: String, value: Expr[Vec[A]])(using RoleCanDrive[R]): Unit =
    ConstructionKernel.operation("interface-drive", endpoint, member, value)

  def observeMember(member: String): Unit =
    ConstructionKernel.operation("interface-observe", endpoint, member)

enum InterfaceLayout:
  case PortableFlattened
  case FutureSystemVerilogNative
  case Automatic

final case class InterfaceLayoutPolicy(
    layout: InterfaceLayout,
    flattenPrefix: Option[String] = None
)

/** First-class resolved digital inout candidate. */
sealed trait DriveMode

object DriveMode:
  final class Value[M <: DriveMode] private[nodal] (val name: String)

  sealed trait PushPull extends DriveMode
  sealed trait OpenDrain extends DriveMode
  sealed trait OpenSource extends DriveMode
  sealed trait ReadOnly extends DriveMode
  sealed trait PassThrough extends DriveMode

  val pushPull: Value[PushPull] = new Value("push-pull")
  val openDrain: Value[OpenDrain] = new Value("open-drain")
  val openSource: Value[OpenSource] = new Value("open-source")
  val readOnly: Value[ReadOnly] = new Value("read-only")
  val passThrough: Value[PassThrough] = new Value("pass-through")

enum InoutPlacement:
  case TopLevelPin
  case BlackBoxPin
  case HierarchyPassThrough
  case PadCellBoundary
  case InternalResolvedNet

enum ResolutionProfile:
  case PortableBoundaryOnly
  case FullResolvedSimulation
  case TechnologyMapped

/** Closed internal carrier shared by scalar and whole Interface-array views. */
private[nodal] sealed trait ResolvedDigitalCapability[A <: Data, M <: DriveMode]:
  val dataType: DataType[A]
  val mode: DriveMode.Value[M]
  def enableDataType: DataType[?]
  final def read: Expr[A] =
    DigitalInterfaceContract.netExpression("digital_net_read", this, dataType)

/** A scalar resolved capability. Standalone declarations remain scalar only. */
sealed trait ResolvedDigital[A <: Bits, M <: DriveMode] extends ResolvedDigitalCapability[A, M]:
  final def enableDataType: DataType[Bool] = Bool

/** A whole actual InterfaceArray member, with pointwise data and enable geometry. */
sealed trait ResolvedDigitalArray[A <: Bits, M <: DriveMode]
    extends ResolvedDigitalCapability[Vec[A], M]:
  val enableDataType: DataType[Vec[Bool]]

private[nodal] sealed trait InterfaceResolvedMember[A <: Data, M <: DriveMode]
    extends ResolvedDigitalCapability[A, M]:
  val endpoint: InterfaceEndpoint
  val declaration: InterfaceMember.DigitalResolved[?, M]
  val through: Vector[InterfaceMember.Nested[?]]
  val member: String

private[nodal] final class InterfaceResolvedView[A <: Bits, M <: DriveMode](
    val dataType: DataType[A],
    val mode: DriveMode.Value[M],
    val endpoint: InterfacePort[?, ?],
    val declaration: InterfaceMember.DigitalResolved[A, M],
    val through: Vector[InterfaceMember.Nested[?]],
    val member: String
) extends ResolvedDigital[A, M] with InterfaceResolvedMember[A, M]

private[nodal] final class InterfaceResolvedArrayView[A <: Bits, M <: DriveMode](
    val dataType: DataType[Vec[A]],
    val enableDataType: DataType[Vec[Bool]],
    val mode: DriveMode.Value[M],
    val endpoint: InterfaceArray[?, ?],
    val declaration: InterfaceMember.DigitalResolved[A, M],
    val through: Vector[InterfaceMember.Nested[?]],
    val member: String
) extends ResolvedDigitalArray[A, M] with InterfaceResolvedMember[Vec[A], M]

final class DigitalInout[A <: Bits, M <: DriveMode] private[nodal] (
    val dataType: DataType[A],
    val mode: DriveMode.Value[M],
    val placement: InoutPlacement,
    val profile: ResolutionProfile,
    val name: String,
    private[nodal] val boundary: Boolean = false
) extends ResolvedDigital[A, M]:
  if !boundary then
    CandidateRuntime.declare(
      this,
      KernelSignalKind.DigitalInout,
      dataType = Some(dataType),
      explicitName = Some(name),
      attributes = Vector(
        "mode" -> mode.name,
        "placement" -> placement,
        "profile" -> profile
      )
    )

trait ArbitraryDrive[M <: DriveMode]

object ArbitraryDrive:
  given pushPullDrive: ArbitraryDrive[DriveMode.PushPull] with {}
  given openSourceDrive: ArbitraryDrive[DriveMode.OpenSource] with {}

trait ReleasableDrive[M <: DriveMode]

object ReleasableDrive:
  given pushPullRelease: ReleasableDrive[DriveMode.PushPull] with {}
  given openDrainRelease: ReleasableDrive[DriveMode.OpenDrain] with {}
  given openSourceRelease: ReleasableDrive[DriveMode.OpenSource] with {}
  given passThroughRelease: ReleasableDrive[DriveMode.PassThrough] with {}

final class TriStateCarrier[A <: Bits, M <: DriveMode] private[nodal] (
    val read: Expr[A],
    val write: Expr[A],
    val enable: Expr[Bool],
    val mode: DriveMode.Value[M]
)

final class TriStateArrayCarrier[A <: Bits, M <: DriveMode] private[nodal] (
    val read: Expr[Vec[A]],
    val write: Expr[Vec[A]],
    val enable: Expr[Vec[Bool]],
    val mode: DriveMode.Value[M]
)

final class PadAdapter[A <: Bits, M <: DriveMode] private[nodal] (
    val endpoint: DigitalInout[A, M],
    val cell: String
)

def digitalInout[A <: Bits, M <: DriveMode](
    dataType: DataType[A],
    mode: DriveMode.Value[M],
    placement: InoutPlacement,
    profile: ResolutionProfile,
    name: String
): DigitalInout[A, M] = new DigitalInout(dataType, mode, placement, profile, name)

extension [A <: Bits, M <: DriveMode](endpoint: ResolvedDigital[A, M])
  def drive(value: Expr[A], enable: Expr[Bool])(using ArbitraryDrive[M]): Unit =
    ConstructionKernel.operation("inout-drive", endpoint, value, enable)

  def highZ()(using ReleasableDrive[M]): Unit =
    ConstructionKernel.operation("inout-high-z", endpoint)

  def split: TriStateCarrier[A, M] =
    new TriStateCarrier(
      endpoint.read,
      DigitalInterfaceContract.netExpression("digital_net_write_view", endpoint, endpoint.dataType),
      DigitalInterfaceContract.netExpression("digital_net_enable_view", endpoint, Bool),
      endpoint.mode
    )

extension [A <: Bits](endpoint: ResolvedDigital[A, DriveMode.OpenDrain])
  def driveLow(enable: Expr[Bool]): Unit =
    ConstructionKernel.operation("inout-drive-low", endpoint, enable)

extension [A <: Bits](endpoint: ResolvedDigital[A, DriveMode.OpenSource])
  def driveHigh(enable: Expr[Bool]): Unit =
    ConstructionKernel.operation("inout-drive-high", endpoint, enable)

def passThrough[A <: Bits, M <: DriveMode](
    outer: ResolvedDigital[A, M],
    inner: ResolvedDigital[A, M]
): Unit = ConstructionKernel.operation("inout-pass-through", outer, inner)

extension [A <: Bits, M <: DriveMode](endpoint: ResolvedDigitalArray[A, M])
  def drive(value: Expr[Vec[A]], enable: Expr[Vec[Bool]])(using ArbitraryDrive[M]): Unit =
    ConstructionKernel.operation("inout-drive", endpoint, value, enable)

  def highZ()(using ReleasableDrive[M]): Unit =
    ConstructionKernel.operation("inout-high-z", endpoint)

  def split: TriStateArrayCarrier[A, M] =
    new TriStateArrayCarrier(
      endpoint.read,
      DigitalInterfaceContract.netExpression("digital_net_write_view", endpoint, endpoint.dataType),
      DigitalInterfaceContract.netExpression(
        "digital_net_enable_view",
        endpoint,
        endpoint.enableDataType
      ),
      endpoint.mode
    )

extension [A <: Bits](endpoint: ResolvedDigitalArray[A, DriveMode.OpenDrain])
  def driveLow(enable: Expr[Vec[Bool]]): Unit =
    ConstructionKernel.operation("inout-drive-low", endpoint, enable)

extension [A <: Bits](endpoint: ResolvedDigitalArray[A, DriveMode.OpenSource])
  def driveHigh(enable: Expr[Vec[Bool]]): Unit =
    ConstructionKernel.operation("inout-drive-high", endpoint, enable)

def passThrough[A <: Bits, M <: DriveMode](
    outer: ResolvedDigitalArray[A, M],
    inner: ResolvedDigitalArray[A, M]
): Unit = ConstructionKernel.operation("inout-pass-through", outer, inner)

def padAdapter[A <: Bits, M <: DriveMode](
    endpoint: DigitalInout[A, M],
    cell: String
): PadAdapter[A, M] = new PadAdapter(endpoint, cell)

/** Conservative AMS terminal candidate, distinct from digital inout and signal flow. */
final class Terminal[D <: Discipline] private[nodal] (
    val discipline: D,
    val name: String
):
  CandidateRuntime.declare(
    this,
    KernelSignalKind.ConservativeTerminal,
    explicitName = Some(name),
    attributes = CandidateRuntime.conservativeAttributes(discipline)
  )

sealed trait ConservativeAccess

trait CanConnect[A <: ConservativeAccess]
trait CanSense[A <: ConservativeAccess]
trait CanContribute[A <: ConservativeAccess]

object ConservativeAccess:
  sealed trait Connect extends ConservativeAccess
  object Connect:
    given connectAccess: CanConnect[Connect] with {}
    given connectSense: CanSense[Connect] with {}

  sealed trait Sense extends ConservativeAccess
  object Sense:
    given senseAccess: CanSense[Sense] with {}

  sealed trait Contribute extends ConservativeAccess
  object Contribute:
    given contributeSense: CanSense[Contribute] with {}
    given contributeAccess: CanContribute[Contribute] with {}

  sealed trait Monitor extends ConservativeAccess
  object Monitor:
    given monitorSense: CanSense[Monitor] with {}

/** A capability over one existing endpoint, never a fabricated standalone terminal. */
sealed trait ConservativeView[D <: Discipline, A <: ConservativeAccess]:
  def discipline: D
  private[nodal] def permission: String
  private[nodal] def conservativeIdentity: AnyRef

final class TerminalView[D <: Discipline, A <: ConservativeAccess] private[nodal] (
    val terminal: Terminal[D],
    private[nodal] val permission: String
) extends ConservativeView[D, A]:
  def discipline: D = terminal.discipline
  private[nodal] def conservativeIdentity: AnyRef = terminal

private[nodal] final class InterfaceTerminalView[D <: Discipline, A <: ConservativeAccess](
    val endpoint: InterfacePort[?, ?],
    val declaration: InterfaceMember.Conservative[D],
    val through: Vector[InterfaceMember.Nested[?]],
    val member: String,
    val permission: String
) extends ConservativeView[D, A]:
  def discipline: D = declaration.discipline
  private[nodal] def conservativeIdentity: AnyRef = this

object CanConnect {}
object CanSense {}
object CanContribute {}

def terminal[D <: Discipline](discipline: D, name: String): Terminal[D] =
  new Terminal(discipline, name)

extension [D <: Discipline](endpoint: Terminal[D])
  def connectView: TerminalView[D, ConservativeAccess.Connect] =
    new TerminalView(endpoint, "connect")
  def senseView: TerminalView[D, ConservativeAccess.Sense] = new TerminalView(endpoint, "sense")
  def contributeView: TerminalView[D, ConservativeAccess.Contribute] =
    new TerminalView(endpoint, "contribute")
  def terminalMonitorView: TerminalView[D, ConservativeAccess.Monitor] =
    new TerminalView(endpoint, "sense")

extension [D <: Discipline, A <: ConservativeAccess](view: ConservativeView[D, A])
  def connectTo[B <: ConservativeAccess](
      other: ConservativeView[D, B]
  )(using CanConnect[A], CanConnect[B]): Unit =
    ConstructionKernel.operation(
      "terminal-connect",
      view.conservativeIdentity,
      other.conservativeIdentity
    )

  def potential(using CanSense[A]): Expr[Real] = CandidateRuntime.expr(view, "potential")

  def flow(using CanSense[A]): Expr[Real] = CandidateRuntime.expr(view, "flow")

  def contribute(value: Expr[Real])(using CanContribute[A]): Unit =
    CandidateRuntime.statement(view, value)

/** Directional analog signal-flow candidate. */
sealed trait AnalogDirection

object AnalogDirection:
  sealed trait Source extends AnalogDirection
  sealed trait Sink extends AnalogDirection
  sealed trait Monitor extends AnalogDirection

final class AnalogSignal[D, R <: AnalogDirection] private[nodal] (
    val name: String,
    val dimension: String
):
  CandidateRuntime.declare(
    this,
    KernelSignalKind.AnalogSignal,
    explicitName = Some(name),
    attributes = Vector("dimension" -> dimension)
  )

trait CanDriveAnalog[R <: AnalogDirection]

object CanDriveAnalog:
  given sourceDrive: CanDriveAnalog[AnalogDirection.Source] with {}

trait CanSampleAnalog[R <: AnalogDirection]

object CanSampleAnalog:
  given sinkSample: CanSampleAnalog[AnalogDirection.Sink] with {}
  given monitorSample: CanSampleAnalog[AnalogDirection.Monitor] with {}

object AnalogSignal:
  def source[D](name: String, dimension: String): AnalogSignal[D, AnalogDirection.Source] =
    new AnalogSignal(name, dimension)

  def sink[D](name: String, dimension: String): AnalogSignal[D, AnalogDirection.Sink] =
    new AnalogSignal(name, dimension)

  def monitor[D](name: String, dimension: String): AnalogSignal[D, AnalogDirection.Monitor] =
    new AnalogSignal(name, dimension)

extension [D, R <: AnalogDirection](signal: AnalogSignal[D, R])
  def driveAnalog(value: Expr[Real])(using CanDriveAnalog[R]): Unit =
    CandidateRuntime.statement(signal, value)

  def sampleAnalog(using CanSampleAnalog[R]): Expr[Real] =
    CandidateRuntime.expr(signal)

enum QuantizationPolicy:
  case Exact
  case RoundNearest
  case Saturate

final case class BridgeContract(
    sampleTime: Quantity[TimeDimension],
    threshold: Option[Quantity[VoltageDimension]],
    hysteresis: Option[Quantity[VoltageDimension]],
    quantization: QuantizationPolicy,
    models: Set[ModelAvailability],
    provenance: String
)

object MixedSignalBridge:
  def sample[D <: Discipline, A <: ConservativeAccess, O <: Data](
      source: ConservativeView[D, A],
      outputType: DataType[O],
      contract: BridgeContract
  )(using CanSense[A]): Expr[O] = CandidateRuntime.expr(source, outputType, contract)

  def drive[I <: Data, D <: Discipline, A <: ConservativeAccess](
      input: Expr[I],
      destination: ConservativeView[D, A],
      contract: BridgeContract
  )(using CanContribute[A]): Unit = CandidateRuntime.statement(input, destination, contract)

object ConservativeSignalBridge:
  def senseToSignal[
      D <: Discipline,
      A <: ConservativeAccess,
      Q,
      R <: AnalogDirection
  ](
      source: ConservativeView[D, A],
      destination: AnalogSignal[Q, R],
      contract: BridgeContract
  )(using CanSense[A], CanDriveAnalog[R]): Unit =
    destination.driveAnalog(source.potential)
    CandidateRuntime.statement(contract)

/** Fixed-rate transaction wrapper used by the transaction-lambda pipeline candidate. */
final class Txn[A] private[nodal] (val value: A)

object Txn:
  def apply[A](value: A): Txn[A] = new Txn(value)

sealed trait Latency

object Latency:
  case object Auto extends Latency
  final case class Exact(cycles: Int) extends Latency
  final case class Range(minimum: Int, maximum: Int) extends Latency

sealed trait Throughput

object Throughput:
  case object EveryCycle extends Throughput

sealed trait ReadyPath

object ReadyPath:
  case object Auto extends ReadyPath
  case object Combinational extends ReadyPath
  case object Registered extends ReadyPath

enum EnvelopeSchedule:
  case WorstCase
  case Minimum
  case Explicit

final case class ParameterEnvelope(
    parameter: Expr[Integer],
    minimum: Int,
    maximum: Int
)

final case class PipelinePolicy(
    latency: Latency = Latency.Auto,
    throughput: Throughput = Throughput.EveryCycle,
    target: Option[Frequency] = None,
    ready: ReadyPath = ReadyPath.Auto,
    envelopes: Seq[ParameterEnvelope] = Seq.empty,
    scheduleFor: EnvelopeSchedule = EnvelopeSchedule.WorstCase
)

final case class ScheduleInspection(
    region: String,
    latency: Latency,
    throughput: Throughput,
    ready: ReadyPath,
    envelopes: Seq[ParameterEnvelope],
    scheduleHash: Option[String]
)

def pipe[I, O](input: Txn[I], policy: PipelinePolicy)(transform: I => O): Txn[O] =
  CandidateRuntime.statement(policy)
  Txn(transform(input.value))

def pipe[A <: Data, B <: Data](
    input: Expr[A],
    policy: PipelinePolicy
)(transform: Expr[A] => Expr[B]): Expr[B] =
  CandidateRuntime.statement(policy)
  transform(input)

def pipe[A <: Data, B <: Data](
    input: Valid[A],
    policy: PipelinePolicy
)(transform: Expr[A] => Expr[B]): Valid[B] =
  CandidateRuntime.statement(policy)
  Valid(transform(input.payload))

def pipe[A <: Data, B <: Data](
    input: Stream[A],
    policy: PipelinePolicy
)(transform: Expr[A] => Expr[B]): Stream[B] =
  val _ = (input, policy, transform)
  ConstructionKernel.unsupportedStreamConsumer("pipe")

extension [A](transaction: Txn[A])
  def delay(cycles: Int): Txn[A] =
    CandidateRuntime.statement(transaction, cycles)
    transaction

extension [A <: Data](value: Expr[A])
  def delay(cycles: Int): Expr[A] = CandidateRuntime.expr(value, cycles)

extension [A <: Data](value: Valid[A])
  def delay(cycles: Int): Valid[A] =
    CandidateRuntime.statement(value, cycles)
    value

extension [A <: Data](value: Stream[A])
  def delay(cycles: Int): Stream[A] =
    val _ = (value, cycles)
    ConstructionKernel.unsupportedStreamConsumer("Stream.delay")

def stage[A <: Data](value: Expr[A]): Expr[A] = CandidateRuntime.expr(value, "stage")

def sameStage[A](body: => A): A =
  CandidateRuntime.statement(() => body)
  body

def inspectSchedule(
    value: Any,
    region: String,
    policy: PipelinePolicy
): ScheduleInspection =
  CandidateRuntime.statement(value)
  ScheduleInspection(
    region = region,
    latency = policy.latency,
    throughput = policy.throughput,
    ready = policy.ready,
    envelopes = policy.envelopes,
    scheduleHash = None
  )

final class FixedLatencyOperator[I <: Data, O <: Data] private[nodal] (
    val name: String,
    val outputType: DataType[O],
    val contract: ExternalContract
):
  def apply(input: Expr[I]): Expr[O] = CandidateRuntime.expr(this, input)

  def apply(input: Valid[I]): Valid[O] = Valid(CandidateRuntime.expr(this, input))

  def apply(input: Stream[I]): Stream[O] =
    val _ = input
    ConstructionKernel.unsupportedStreamConsumer(name)

object FixedLatencyOperator:
  def apply[I <: Data, O <: Data](
      name: String,
      outputType: DataType[O],
      contract: ExternalContract
  ): FixedLatencyOperator[I, O] = new FixedLatencyOperator(name, outputType, contract)

final case class VariableLatencyContract(
    minimumLatency: Int,
    maximumLatency: Int,
    capacity: Int,
    initiationInterval: Int,
    effect: Effect,
    models: Set[ModelAvailability],
    domain: ClockDomain
)

final class VariableLatencyOperator[I <: Data, O <: Data] private[nodal] (
    val name: String,
    val outputType: DataType[O],
    val contract: VariableLatencyContract
):
  def apply(input: Stream[I]): Stream[O] =
    val _ = input
    ConstructionKernel.unsupportedStreamConsumer(name)

object VariableLatencyOperator:
  def apply[I <: Data, O <: Data](
      name: String,
      outputType: DataType[O],
      contract: VariableLatencyContract
  ): VariableLatencyOperator[I, O] = new VariableLatencyOperator(name, outputType, contract)
