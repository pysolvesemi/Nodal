package nodal

import scala.annotation.targetName

/** Compile-only public candidates for Increment 13. No elaboration or lowering semantics live here.
  */
opaque type SInt <: Data = Bits

object SInt:
  def apply(width: Int): DataType[SInt] =
    CandidateRuntime.statement(width)
    Bits(width).asInstanceOf[DataType[SInt]]

  def apply(width: Expr[Integer]): DataType[SInt] =
    CandidateRuntime.statement(width)
    Bits(width).asInstanceOf[DataType[SInt]]

  extension (left: Expr[SInt])
    @targetName("sintAddition")
    def +(right: Expr[SInt]): Expr[SInt] = CandidateRuntime.expr(left, right)

    @targetName("sintSubtraction")
    def -(right: Expr[SInt]): Expr[SInt] = CandidateRuntime.expr(left, right)

    @targetName("sintArithmeticShiftRight")
    def >>(amount: Int): Expr[SInt] = CandidateRuntime.expr(left, amount)

extension (value: Int)
  def S(width: Int): Expr[SInt] = CandidateRuntime.expr(value, width)
  def S(width: Expr[Integer]): Expr[SInt] = CandidateRuntime.expr(value, width)

extension (left: Expr[UInt])
  @targetName("uintLogicalShiftRight")
  def >>(amount: Int): Expr[UInt] = CandidateRuntime.expr(left, amount)

extension [A <: Data](value: Expr[A])
  def extend(width: Int): Expr[A] = CandidateRuntime.expr(value, width, "extend")
  def truncate(width: Int): Expr[A] = CandidateRuntime.expr(value, width, "truncate")
  def wrap(width: Int): Expr[A] = CandidateRuntime.expr(value, width, "wrap")
  def saturate(width: Int): Expr[A] = CandidateRuntime.expr(value, width, "saturate")
  def resizeChecked(width: Int): Expr[A] = CandidateRuntime.expr(value, width, "checked")
  def named(name: String): Expr[A] = CandidateRuntime.expr(value, name, "named")
  def keep(reason: String): Expr[A] = CandidateRuntime.expr(value, reason, "keep")

extension (value: Expr[UInt])
  def toSigned: Expr[SInt] = CandidateRuntime.expr(value, "toSigned")
  def reinterpretSigned: Expr[SInt] = CandidateRuntime.expr(value, "reinterpretSigned")

extension (value: Expr[SInt])
  def toUnsigned: Expr[UInt] = CandidateRuntime.expr(value, "toUnsigned")
  def reinterpretUnsigned: Expr[UInt] = CandidateRuntime.expr(value, "reinterpretUnsigned")

/** Static or symbolic dimension accepted by structural candidates. */
type Dimension = Int | Expr[Integer]

opaque type Vec[A <: Data] <: Data = Bits

object Vec:
  def apply[A <: Data](element: DataType[A], dimensions: Dimension*): DataType[Vec[A]] =
    CandidateRuntime.statement(element, dimensions)
    Bits(1).asInstanceOf[DataType[Vec[A]]]

extension [A <: Data](value: Expr[Vec[A]])
  def at(indices: Dimension*): Expr[A] = CandidateRuntime.expr(value, indices)
  def flatten: Expr[Vec[A]] = CandidateRuntime.expr(value, "flatten")
  def reshape(dimensions: Dimension*): Expr[Vec[A]] =
    CandidateRuntime.expr(value, dimensions, "reshape")
  def map[B <: Data](function: Expr[A] => Expr[B]): Expr[Vec[B]] =
    CandidateRuntime.expr(value, function, "map")
  def zip[B <: Data](other: Expr[Vec[B]]): Expr[Vec[A]] =
    CandidateRuntime.expr(value, other, "zip")
  def reduce(function: (Expr[A], Expr[A]) => Expr[A]): Expr[A] =
    CandidateRuntime.expr(value, function, "reduce")

/** Directionless aggregate candidate. */
opaque type Aggregate <: Data = Bits

final case class AggregateField[A <: Data](name: String, dataType: DataType[A])

object Aggregate:
  def apply(name: String, fields: AggregateField[? <: Data]*): DataType[Aggregate] =
    CandidateRuntime.statement(name, fields)
    Bits(1).asInstanceOf[DataType[Aggregate]]

/** General valid-only transport candidate. */
final class Valid[A <: Data] private[nodal] (val payload: Expr[A])

object Valid:
  def apply[A <: Data](payload: Expr[A]): Valid[A] = new Valid(payload)

/** Explicit structural-generation candidate; ordinary Scala loops remain elaboration-only. */
final class GenerateIndex private[nodal] ()

def generate(count: Dimension)(body: GenerateIndex => Unit): Unit =
  CandidateRuntime.statement(count, body)

/** Bounded same-cycle hardware iteration candidate, distinct from generate and Scala for. */
final class LoopIndex private[nodal] ()

sealed trait LoopBound
object LoopBound:
  final case class Static(value: Int) extends LoopBound
  final case class Symbolic(value: Expr[Integer], maximum: Int) extends LoopBound

def loop(bound: LoopBound)(body: LoopIndex => Unit): Unit =
  CandidateRuntime.statement(bound, body)

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

final class Quantity[D] private[nodal] (value: Double):
  CandidateRuntime.statement(value)

object Quantity:
  extension [D](left: Quantity[D])
    @targetName("quantityAddition")
    def +(right: Quantity[D]): Quantity[D] =
      CandidateRuntime.statement(left, right)
      left

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

/** Native Scala enum derivation and canonical hardware encoding candidate. */
trait HwEnum[A]

object HwEnum:
  def derived[A]: HwEnum[A] = new HwEnum[A] {}

final case class EnumEncoding[A](values: Map[A, BigInt])

def enumEncoding[A](values: (A, BigInt)*)(using HwEnum[A]): EnumEncoding[A] =
  EnumEncoding(values.toMap)

final case class DecodedEnum[A](value: A, valid: Expr[Bool])

def decodeEnum[A](bits: Expr[Bits], default: A)(using HwEnum[A]): DecodedEnum[A] =
  DecodedEnum(default, CandidateRuntime.expr(bits, default))

enum FsmEncoding:
  case Compact, OneHot, Gray, Auto

enum TransitionMode:
  case Exclusive, Priority

enum IllegalStatePolicy:
  case Error, RecoverToInitial

final class FsmDefinition[S] private[nodal] (val name: String)

object FsmDefinition:
  def apply[S](name: String)(using HwEnum[S]): FsmDefinition[S] = new FsmDefinition(name)

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
)(body: FsmBuilder[S] => Unit)(using HwEnum[S]): Unit =
  val builder = new FsmBuilder(definition, initial, encoding, illegalState)
  CandidateRuntime.statement(builder, body)
