package nodal

/** Public Interface, inout, AMS, and pipeline surface frozen by Increment 15. Construction,
  * scheduling, lowering, resolution, topology, and simulation remain inert.
  */

/** Directionless, storable record candidate. */
opaque type Struct <: Data = Bits

final case class StructField[A <: Data](name: String, dataType: DataType[A])

object Struct:
  def apply(name: String, fields: StructField[? <: Data]*): DataType[Struct] =
    CandidateRuntime.statement(name, fields)
    Bits(1).asInstanceOf[DataType[Struct]]

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

  def value[A <: Data](name: String, dataType: DataType[A]): InterfaceMember =
    Value(name, dataType)

  def valid[A <: Data](name: String, payloadType: DataType[A]): InterfaceMember =
    ValidChannel(name, payloadType)

  def stream[A <: Data](name: String, payloadType: DataType[A]): InterfaceMember =
    StreamChannel(name, payloadType)

  def nested[I <: Interface](name: String, definition: InterfaceType[I]): InterfaceMember =
    Nested(name, definition)

  def digitalInout[A <: Bits, M <: DriveMode](
      name: String,
      dataType: DataType[A],
      mode: DriveMode.Value[M]
  ): InterfaceMember = DigitalResolved(name, dataType, mode)

  def terminal[D <: Discipline](name: String, discipline: D): InterfaceMember =
    Conservative(name, discipline)

  def analogSignal[D](name: String, dimension: String): InterfaceMember =
    SignalFlow[D](name, dimension)

final class InterfaceType[I <: Interface] private[nodal] (
    val name: String,
    val members: Seq[InterfaceMember]
)

object Interface:
  def apply[I <: Interface](name: String, members: InterfaceMember*): InterfaceType[I] =
    new InterfaceType(name, members.toSeq)

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

final class InterfacePort[I <: Interface, R <: RoleKind] private[nodal] (
    val definition: InterfaceType[I],
    val role: Role[R],
    val name: String,
    val domain: ClockDomain
)

final class InterfaceArray[I <: Interface, R <: RoleKind] private[nodal] (
    val definition: InterfaceType[I],
    val role: Role[R],
    val count: Dimension,
    val name: String,
    val domain: ClockDomain
)

def interfacePort[I <: Interface, R <: RoleKind](
    definition: InterfaceType[I],
    role: Role[R],
    name: String,
    domain: ClockDomain
): InterfacePort[I, R] = new InterfacePort(definition, role, name, domain)

def interfaceArray[I <: Interface, R <: RoleKind](
    definition: InterfaceType[I],
    role: Role[R],
    count: Dimension,
    name: String,
    domain: ClockDomain
): InterfaceArray[I, R] = new InterfaceArray(definition, role, count, name, domain)

extension [I <: Interface, R <: RoleKind](endpoint: InterfacePort[I, R])
  def inverted(using inverse: RoleInverse[R]): InterfacePort[I, inverse.Out] =
    val invertedRole = new Role[inverse.Out](
      inverse.inverse.name,
      endpoint.role.access.map(RoleAccess.inverted)
    )
    new InterfacePort(
      endpoint.definition,
      invertedRole,
      s"${endpoint.name}.inverse",
      endpoint.domain
    )

  def monitorView: InterfacePort[I, MonitorRole] =
    new InterfacePort(endpoint.definition, monitor, s"${endpoint.name}.monitor", endpoint.domain)

  def connectExact[OtherRole <: RoleKind](
      other: InterfacePort[I, OtherRole]
  )(using RoleConnection[R, OtherRole]): Unit = CandidateRuntime.statement(endpoint, other)

  def driveMember[A <: Data](member: String, value: Expr[A])(using RoleCanDrive[R]): Unit =
    CandidateRuntime.statement(endpoint, member, value)

  def observeMember(member: String): Unit = CandidateRuntime.statement(endpoint, member)

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

final class DigitalInout[A <: Bits, M <: DriveMode] private[nodal] (
    val dataType: DataType[A],
    val mode: DriveMode.Value[M],
    val placement: InoutPlacement,
    val profile: ResolutionProfile,
    val name: String
):
  def read: Expr[A] = CandidateRuntime.expr(this, "read")

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

extension [A <: Bits, M <: DriveMode](endpoint: DigitalInout[A, M])
  def drive(value: Expr[A], enable: Expr[Bool])(using ArbitraryDrive[M]): Unit =
    CandidateRuntime.statement(endpoint, value, enable)

  def highZ()(using ReleasableDrive[M]): Unit = CandidateRuntime.statement(endpoint, "high-z")

  def split: TriStateCarrier[A, M] =
    new TriStateCarrier(
      endpoint.read,
      CandidateRuntime.expr(endpoint, "write"),
      CandidateRuntime.expr(endpoint, "enable"),
      endpoint.mode
    )

extension [A <: Bits](endpoint: DigitalInout[A, DriveMode.OpenDrain])
  def driveLow(enable: Expr[Bool]): Unit = CandidateRuntime.statement(endpoint, enable, "low")

extension [A <: Bits](endpoint: DigitalInout[A, DriveMode.OpenSource])
  def driveHigh(enable: Expr[Bool]): Unit = CandidateRuntime.statement(endpoint, enable, "high")

def passThrough[A <: Bits, M <: DriveMode](
    outer: DigitalInout[A, M],
    inner: DigitalInout[A, M]
): Unit = CandidateRuntime.statement(outer, inner)

def padAdapter[A <: Bits, M <: DriveMode](
    endpoint: DigitalInout[A, M],
    cell: String
): PadAdapter[A, M] = new PadAdapter(endpoint, cell)

/** Conservative AMS terminal candidate, distinct from digital inout and signal flow. */
final class Terminal[D <: Discipline] private[nodal] (
    val discipline: D,
    val name: String
)

sealed trait ConservativeAccess

object ConservativeAccess:
  sealed trait Connect extends ConservativeAccess
  sealed trait Sense extends ConservativeAccess
  sealed trait Contribute extends ConservativeAccess
  sealed trait Monitor extends ConservativeAccess

final class TerminalView[D <: Discipline, A <: ConservativeAccess] private[nodal] (
    val terminal: Terminal[D]
)

trait CanConnect[A <: ConservativeAccess]

object CanConnect:
  given connectAccess: CanConnect[ConservativeAccess.Connect] with {}

trait CanSense[A <: ConservativeAccess]

object CanSense:
  given connectSense: CanSense[ConservativeAccess.Connect] with {}
  given senseAccess: CanSense[ConservativeAccess.Sense] with {}
  given contributeSense: CanSense[ConservativeAccess.Contribute] with {}
  given monitorSense: CanSense[ConservativeAccess.Monitor] with {}

trait CanContribute[A <: ConservativeAccess]

object CanContribute:
  given contributeAccess: CanContribute[ConservativeAccess.Contribute] with {}

def terminal[D <: Discipline](discipline: D, name: String): Terminal[D] =
  new Terminal(discipline, name)

extension [D <: Discipline](endpoint: Terminal[D])
  def connectView: TerminalView[D, ConservativeAccess.Connect] = new TerminalView(endpoint)
  def senseView: TerminalView[D, ConservativeAccess.Sense] = new TerminalView(endpoint)
  def contributeView: TerminalView[D, ConservativeAccess.Contribute] = new TerminalView(endpoint)
  def terminalMonitorView: TerminalView[D, ConservativeAccess.Monitor] = new TerminalView(endpoint)

extension [D <: Discipline, A <: ConservativeAccess](view: TerminalView[D, A])
  def connectTo[B <: ConservativeAccess](
      other: TerminalView[D, B]
  )(using CanConnect[A], CanConnect[B]): Unit = CandidateRuntime.statement(view, other)

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
      source: TerminalView[D, A],
      outputType: DataType[O],
      contract: BridgeContract
  )(using CanSense[A]): Expr[O] = CandidateRuntime.expr(source, outputType, contract)

  def drive[I <: Data, D <: Discipline, A <: ConservativeAccess](
      input: Expr[I],
      destination: TerminalView[D, A],
      contract: BridgeContract
  )(using CanContribute[A]): Unit = CandidateRuntime.statement(input, destination, contract)

object ConservativeSignalBridge:
  def senseToSignal[
      D <: Discipline,
      A <: ConservativeAccess,
      Q,
      R <: AnalogDirection
  ](
      source: TerminalView[D, A],
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
  CandidateRuntime.statement(policy)
  Stream(transform(input.payload))

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
    CandidateRuntime.statement(value, cycles)
    value

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

  def apply(input: Stream[I]): Stream[O] = Stream(CandidateRuntime.expr(this, input))

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
  def apply(input: Stream[I]): Stream[O] = Stream(CandidateRuntime.expr(this, input))

object VariableLatencyOperator:
  def apply[I <: Data, O <: Data](
      name: String,
      outputType: DataType[O],
      contract: VariableLatencyContract
  ): VariableLatencyOperator[I, O] = new VariableLatencyOperator(name, outputType, contract)
