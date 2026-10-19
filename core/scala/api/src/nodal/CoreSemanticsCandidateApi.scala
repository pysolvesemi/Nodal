package nodal

import scala.annotation.targetName

/** Public core semantic surface frozen by Increment 15. Implementation remains inert. */
opaque type SInt <: Data = Bits

object SInt extends DigitalNumericOperators[SInt]:
  def apply(width: Int): DataType[SInt] =
    CandidateRuntime.dataType[SInt]("SInt", width)

  def apply(width: Expr[Integer]): DataType[SInt] =
    CandidateRuntime.dataType[SInt]("SInt", width)

  extension (left: Expr[SInt])
    @targetName("sintAddition")
    def +(right: Expr[SInt]): Expr[SInt] =
      DigitalCombinationalContract.arithmetic("digital_add", left, right)

    @targetName("sintSubtraction")
    def -(right: Expr[SInt]): Expr[SInt] =
      DigitalCombinationalContract.arithmetic("digital_sub", left, right)

    @targetName("sintArithmeticShiftRight")
    def >>(amount: Int): Expr[SInt] =
      DigitalCombinationalContract.shift("digital_shr", left, amount)

extension (value: Int)
  def S(width: Int): Expr[SInt] = CandidateRuntime.literalSInt(value, width)
  def S(width: Expr[Integer]): Expr[SInt] = DigitalScalarContract.literal("SInt", value, width)

extension (left: Expr[UInt])
  @targetName("uintLogicalShiftRight")
  def >>(amount: Int): Expr[UInt] =
    DigitalCombinationalContract.shift("digital_shr", left, amount)

extension [A <: Data](value: Expr[A])
  def extend(width: Int): Expr[A] = DigitalScalarContract.resize(value, width, "extend")
  def truncate(width: Int): Expr[A] = DigitalScalarContract.resize(value, width, "truncate")
  def wrap(width: Int): Expr[A] = DigitalScalarContract.resize(value, width, "wrap")
  def saturate(width: Int): Expr[A] = DigitalScalarContract.resize(value, width, "saturate")
  def resizeChecked(width: Int): Expr[A] =
    DigitalScalarContract.resize(value, width, "resize_checked")
  def named(name: String): Expr[A] = DigitalMaterializationContract.named(value, name)
  def keep(reason: String): Expr[A] = DigitalMaterializationContract.keep(value, reason)

extension (value: Expr[UInt])
  def toSigned: Expr[SInt] = DigitalScalarContract.toSigned(value)
  def reinterpretSigned: Expr[SInt] = DigitalScalarContract.reinterpret(value, signed = true)

extension (value: Expr[SInt])
  def toUnsigned: Expr[UInt] = DigitalScalarContract.toUnsigned(value)
  def reinterpretUnsigned: Expr[UInt] = DigitalScalarContract.reinterpret(value, signed = false)

/** Static or symbolic dimension accepted by structural candidates. */
type Dimension = Int | Expr[Integer]

opaque type Vec[A <: Data] <: Data = Bits

object Vec:
  def apply[A <: Data](element: DataType[A], dimensions: Dimension*): DataType[Vec[A]] =
    CandidateRuntime.dataType[Vec[A]]("Vec", element, dimensions.toSeq)

  def fill(dataType: DataType[Vec[Real]])(value: Expr[Real]): Expr[Vec[Real]] =
    CandidateRuntime.shapeFill(dataType, value)

extension [A <: Data](value: Expr[Vec[A]])
  def at(indices: Dimension*): Expr[A] = CandidateRuntime.shapeIndex(value, indices.toVector)
  def row(index: Dimension): Expr[Vec[A]] = CandidateRuntime.shapeRow(value, index)
  def plane(index: Dimension): Expr[Vec[A]] = CandidateRuntime.shapePlane(value, index)
  def slice(lower: Dimension, upperExclusive: Dimension, axis: Int = 0): Expr[Vec[A]] =
    CandidateRuntime.shapeSlice(value, lower, upperExclusive, axis)
  def flatten: Expr[Vec[A]] = CandidateRuntime.shapeFlatten(value)
  def reshape(dimensions: Dimension*): Expr[Vec[A]] =
    CandidateRuntime.shapeView(value, dimensions.toVector)
  def map[B <: Data](function: Expr[A] => Expr[B]): Expr[Vec[B]] =
    DigitalCollectionContract.map(value, function)
  def zipWith[B <: Data, C <: Data](other: Expr[Vec[B]])(
      function: (Expr[A], Expr[B]) => Expr[C]
  ): Expr[Vec[C]] = DigitalCollectionContract.zipWith(value, other, function)
  def fold[B <: Data](initial: Expr[B])(
      function: (Expr[B], Expr[A]) => Expr[B]
  ): Expr[B] = DigitalCollectionContract.fold(value, initial, function, scan = false)
    .asInstanceOf[Expr[B]]
  def scan[B <: Data](initial: Expr[B])(
      function: (Expr[B], Expr[A]) => Expr[B]
  ): Expr[Vec[B]] = DigitalCollectionContract.fold(value, initial, function, scan = true)
    .asInstanceOf[Expr[Vec[B]]]
  def zip[B <: Data](other: Expr[Vec[B]]): Expr[Vec[A]] =
    val _ = other
    DigitalCollectionContract.fail(
      4,
      "inert zip cannot represent both operands; migrate to zipWith",
      ConstructionKernel.referencePath(value)
    )
  def reduce(function: (Expr[A], Expr[A]) => Expr[A]): Expr[A] =
    DigitalCollectionContract.reduce(value, function, balanced = false)
  def reduceBalancedTree(function: (Expr[A], Expr[A]) => Expr[A]): Expr[A] =
    DigitalCollectionContract.reduce(value, function, balanced = true)

/** General valid-only transport candidate. */
final class Valid[A <: Data] private[nodal] (val payload: Expr[A])

object Valid:
  def apply[A <: Data](payload: Expr[A]): Valid[A] = new Valid(payload)

/** Explicit structural-generation candidate; ordinary Scala loops remain elaboration-only. */
final class GenerateIndex private[nodal] ()

def generate(count: Dimension)(body: GenerateIndex => Unit): Unit =
  CandidateRuntime.statement(count, body)

/** Bounded same-cycle hardware iteration candidate, distinct from generate and Scala for. */
final class LoopIndex private[nodal] (val value: Expr[Integer])

sealed trait LoopBound
object LoopBound:
  final case class Static(value: Int) extends LoopBound
  final case class Symbolic(value: Expr[Integer], maximum: Int) extends LoopBound

def loop(bound: LoopBound)(body: LoopIndex => Unit): Unit =
  DigitalIterationCallback.requireLoop(body)
  ConstructionKernel.digitalLoop(bound)(value => body(new LoopIndex(value)))

/** Target-layout candidates for shaped values. */
enum TargetLayout:
  case PortableVerilogFlat
  case SystemVerilogUnpacked
  case SystemVerilogPacked

final case class LayoutPolicy(layout: TargetLayout)

/** Explicit memory contracts. */
enum ReadUnderWrite:
  case OldData, NewData, NoChange, Undefined

enum MemoryOrdering:
  case Ordered, IndependentPorts

final class Mem[A <: Data] private[nodal] (
    val element: DataType[A],
    val depth: Dimension,
    val readLatency: Int,
    val readUnderWrite: ReadUnderWrite,
    val ordering: MemoryOrdering,
    val domain: ClockDomain
):
  CandidateRuntime.declare(
    this,
    KernelSignalKind.Memory,
    dataType = Some(element),
    domain = Some(domain),
    attributes = Vector(
      "depth" -> depth,
      "readLatency" -> readLatency,
      "readUnderWrite" -> readUnderWrite,
      "ordering" -> ordering
    )
  )

  def read(address: Expr[UInt]): Expr[A] = CandidateRuntime.expr(this, address)
  def write(address: Expr[UInt], data: Expr[A], mask: Expr[Bits]): Unit =
    CandidateRuntime.statement(this, address, data, mask)

object Mem:
  def apply[A <: Data](
      element: DataType[A],
      depth: Dimension,
      readLatency: Int,
      readUnderWrite: ReadUnderWrite,
      ordering: MemoryOrdering,
      domain: ClockDomain
  ): Mem[A] = new Mem(element, depth, readLatency, readUnderWrite, ordering, domain)

/** Explicit external-operation contracts. */
enum Effect:
  case Pure, ReadOnly, Stateful, SideEffecting

enum ModelAvailability:
  case Simulation, Synthesis, Formal

final case class ExternalContract(
    latency: Int,
    initiationInterval: Int,
    effect: Effect,
    models: Set[ModelAvailability],
    domain: ClockDomain
)

final class ExternalOp[I <: Data, O <: Data] private[nodal] (
    val name: String,
    val outputType: DataType[O],
    val contract: ExternalContract
):
  def apply(input: Expr[I]): Expr[O] = CandidateRuntime.expr(name, input, contract)

object ExternalOp:
  def apply[I <: Data, O <: Data](
      name: String,
      outputType: DataType[O],
      contract: ExternalContract
  ): ExternalOp[I, O] = new ExternalOp(name, outputType, contract)

/** Dimension-safe quantity prototype kept separate from ordinary source-visible Real internals. */
sealed trait VoltageDimension
sealed trait CurrentDimension
sealed trait ResistanceDimension
sealed trait TimeDimension

final class Quantity[D] private[nodal] (val siValue: Double):
  CandidateRuntime.metadata(siValue)
  require(java.lang.Double.isFinite(siValue), "quantity requires a finite SI value")

object Quantity:
  extension [D](left: Quantity[D])
    @targetName("quantityAddition")
    def +(right: Quantity[D]): Quantity[D] =
      CandidateRuntime.metadata(left, right)
      new Quantity[D](left.siValue + right.siValue)

extension (value: Double)
  def volts: Quantity[VoltageDimension] = new Quantity(value)
  def amps: Quantity[CurrentDimension] = new Quantity(value)
  def ohms: Quantity[ResistanceDimension] = new Quantity(value)
  def seconds: Quantity[TimeDimension] = new Quantity(value)

/** Materialization, naming, and mandatory-check candidates. */
enum TemporaryPolicy:
  case InlineSafe, Readable, Debug

enum NamingPolicy:
  case Semantic, SourcePreferred, DebugStable

enum CheckProfile:
  case Fast, Default, Release

final case class CheckWaiver(id: String, reason: String, check: String)

final case class EmitQuality(
    temporaries: TemporaryPolicy = TemporaryPolicy.InlineSafe,
    naming: NamingPolicy = NamingPolicy.Semantic,
    checks: CheckProfile = CheckProfile.Default,
    waivers: Seq[CheckWaiver] = Seq.empty
)

enum FsmEncoding:
  case Compact, OneHot, Gray, Auto

enum TransitionMode:
  case Exclusive, Priority

enum IllegalStatePolicy:
  case Error, RecoverToInitial

final class FsmDefinition[S] private[nodal] (val name: String)

object FsmDefinition:
  def apply[S](name: String)(using evidence: HwEnum[S]): FsmDefinition[S] =
    val _ = evidence
    new FsmDefinition(name)

final class FsmBuilder[S] private[nodal] (
    val definition: FsmDefinition[S],
    val initial: S,
    val encoding: FsmEncoding,
    val illegalState: IllegalStatePolicy
):
  def state(value: S)(body: StateBuilder[S] => Unit): Unit =
    CandidateRuntime.statement(value, body)
  def parallel(name: String)(body: => Unit): Unit = CandidateRuntime.statement(name, body)
  def submachine(name: String, definition: FsmDefinition[S]): Unit =
    CandidateRuntime.statement(name, definition)
  def boundedCallStack(depth: Int): Unit = CandidateRuntime.statement(depth)

final class StateBuilder[S] private[nodal] ():
  def entry(body: => Unit): Unit = CandidateRuntime.statement(body)
  def active(body: => Unit): Unit = CandidateRuntime.statement(body)
  def exit(body: => Unit): Unit = CandidateRuntime.statement(body)
  def on(condition: Expr[Bool], mode: TransitionMode = TransitionMode.Exclusive)(to: S): Unit =
    CandidateRuntime.statement(condition, mode, to)
  def after(cycles: Int)(to: S): Unit = CandidateRuntime.statement(cycles, to)
  def terminal(): Unit = CandidateRuntime.statement("terminal")

def fsm[S](
    definition: FsmDefinition[S],
    initial: S,
    encoding: FsmEncoding = FsmEncoding.Compact,
    illegalState: IllegalStatePolicy = IllegalStatePolicy.Error
)(body: FsmBuilder[S] => Unit)(using evidence: HwEnum[S]): Unit =
  val _ = evidence
  val builder = new FsmBuilder(definition, initial, encoding, illegalState)
  CandidateRuntime.statement(builder, body)
