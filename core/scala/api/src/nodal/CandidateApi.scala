package nodal

import scala.annotation.targetName

/** Public marker hierarchy frozen by Nodal API v0.2. Implementations remain inert. */
sealed trait Data
sealed trait Real extends Data
sealed trait Integer extends Data
sealed trait Bool extends Data
sealed trait Bits extends Data
sealed trait UInt extends Bits
sealed trait Clock extends Data
sealed trait Reset extends Data

/** Backend-neutral expression placeholder used only to prove candidate source syntax. */
sealed trait Expr[+A <: Data]

/** Candidate declaration-time type descriptor. */
sealed trait DataType[+A <: Data]

case object Real extends DataType[Real]
case object Integer extends DataType[Integer]
case object Bool extends DataType[Bool]
case object Clock extends DataType[Clock]
case object Reset extends DataType[Reset]

object Bits:
  def apply(width: Int): DataType[Bits] = new WidthDataType[Bits](width)
  def apply(width: Expr[Integer]): DataType[Bits] = new WidthDataType[Bits](width)

object UInt:
  def apply(width: Int): DataType[UInt] = new WidthDataType[UInt](width)
  def apply(width: Expr[Integer]): DataType[UInt] = new WidthDataType[UInt](width)

private final class WidthDataType[A <: Data](width: Any) extends DataType[A]:
  CandidateRuntime.statement(width)

/** Candidate analog nature metadata. */
final class Nature private[nodal] (val name: String)

/** Candidate analog discipline marker. */
sealed trait Discipline

/** Candidate named discipline for user-defined discipline prototypes. */
final class NamedDiscipline private[nodal] (
    val name: String,
    val potential: Nature,
    val flow: Nature
) extends Discipline

case object Electrical extends Discipline

val Voltage: Nature = nature("voltage")
val Current: Nature = nature("current")

def nature(name: String): Nature = new Nature(name)

def discipline(name: String, potential: Nature, flow: Nature): NamedDiscipline =
  new NamedDiscipline(name, potential, flow)

/** Candidate module parameter. */
final class Param[A <: Data] private[nodal] (val default: Expr[A]) extends Expr[A]

/** Candidate digital signal or port. */
final class Signal[A <: Data] private[nodal] (dataType: DataType[A]) extends Expr[A]:
  CandidateRuntime.statement(dataType)

  infix def :=(value: Expr[A]): Unit = CandidateRuntime.statement(this, value)

/** Candidate elaboration-time variable visible to behavioral blocks. */
final class Variable[A <: Data] private[nodal] (
    dataType: DataType[A],
    initialValue: Expr[A]
) extends Expr[A]:
  CandidateRuntime.statement(dataType, initialValue)

  infix def :=(value: Expr[A]): Unit = CandidateRuntime.statement(this, value)

/** Candidate analog node or port. */
final class Node[D <: Discipline] private[nodal] (val discipline: D)

/** Frequency metadata carried by a clock-domain declaration. */
sealed trait Frequency

/** Phase metadata carried by a clock relationship. */
sealed trait Phase

private final class CandidateFrequency(value: Double, unit: String) extends Frequency:
  CandidateRuntime.statement(value, unit)

private final class CandidatePhase(value: Double, unit: String) extends Phase:
  CandidateRuntime.statement(value, unit)

object Phase:
  val Zero: Phase = new CandidatePhase(0.0, "deg")

enum ClockEdge:
  case Rising, Falling

enum ResetPolarity:
  case ActiveHigh, ActiveLow

sealed trait ResetPolicy

object ResetPolicy:
  case object None extends ResetPolicy
  case object Sync extends ResetPolicy
  case object Async extends ResetPolicy
  final case class AsyncAssertSyncRelease(stages: Int = 2) extends ResetPolicy

sealed trait ClockRelation

object ClockRelation:
  case object Same extends ClockRelation
  final case class Ratio(
      multiply: Int,
      divide: Int,
      phase: Phase = Phase.Zero
  ) extends ClockRelation
  final case class Synchronous(phaseKnown: Boolean = false) extends ClockRelation
  case object MutuallyExclusive extends ClockRelation
  case object Asynchronous extends ClockRelation
  case object Unknown extends ClockRelation

/** Lexically applied candidate clock/reset domain. */
final class ClockDomain private[nodal] (
    val name: String,
    val reset: Expr[Reset]
):
  def apply(body: => Unit): Unit = CandidateRuntime.block(body)

object ClockDomain:
  def external(
      name: String,
      edge: ClockEdge,
      reset: ResetPolicy,
      resetPolarity: ResetPolarity,
      frequency: Frequency
  ): ClockDomain =
    CandidateRuntime.statement(edge, reset, resetPolarity, frequency)
    new ClockDomain(name, CandidateRuntime.expr(name, reset))

  def from(
      clock: Expr[Clock],
      reset: Expr[Reset],
      edge: ClockEdge,
      policy: ResetPolicy,
      polarity: ResetPolarity,
      frequency: Frequency,
      name: String = "bound"
  ): ClockDomain =
    CandidateRuntime.statement(clock, edge, policy, polarity, frequency)
    new ClockDomain(name, reset)

  def required(name: String = "default"): ClockDomain =
    new ClockDomain(name, CandidateRuntime.expr(name))

  def generated(
      name: String,
      clock: Expr[Clock],
      from: ClockDomain,
      relation: ClockRelation,
      reset: Expr[Reset]
  ): ClockDomain =
    CandidateRuntime.statement(clock, from, relation)
    new ClockDomain(name, reset)

/** Candidate child-instance handle with typed selector-based access and overrides. */
final class Instance[M <: Module] private[nodal] (private val module: M):
  def apply[A](select: M => A): A = select(module)

  def param[A <: Data](select: M => Param[A], value: Expr[A]): this.type =
    CandidateRuntime.statement(select(module), value)
    this

  def domain(domain: ClockDomain): this.type =
    CandidateRuntime.statement(domain)
    this

  def domain(select: M => ClockDomain, domain: ClockDomain): this.type =
    CandidateRuntime.statement(select(module), domain)
    this

/** Short Verilog-AMS-like module construction candidate. */
abstract class Module:
  protected final def param[A <: Data](default: Expr[A]): Param[A] = new Param(default)

  protected final def in[A <: Data](dataType: DataType[A]): Signal[A] =
    new Signal(dataType)

  protected final def out[A <: Data](dataType: DataType[A]): Signal[A] =
    new Signal(dataType)

  protected final def in[D <: Discipline](discipline: D): Node[D] =
    new Node(discipline)

  protected final def out[D <: Discipline](discipline: D): Node[D] =
    new Node(discipline)

  protected final def inout[D <: Discipline](discipline: D): Node[D] =
    new Node(discipline)

  protected final def node[D <: Discipline](discipline: D): Node[D] =
    new Node(discipline)

  protected final def wire[A <: Data](dataType: DataType[A]): Signal[A] =
    new Signal(dataType)

  protected final def variable[A <: Data](
      dataType: DataType[A],
      initialValue: Expr[A]
  ): Variable[A] = new Variable(dataType, initialValue)

  protected final def instance[M <: Module](module: M): Instance[M] =
    new Instance(module)

  protected final def connect[A <: Data](left: Signal[A], right: Signal[A]): Unit =
    CandidateRuntime.statement(left, right)

  protected final def connect[D <: Discipline](left: Node[D], right: Node[D]): Unit =
    CandidateRuntime.statement(left, right)

/** Domain-owned state candidate. */
final class Register[A <: Data] private[nodal] (
    initialValue: Expr[A] | Null,
    dataType: DataType[A] | Null
) extends Expr[A]:
  CandidateRuntime.statement(initialValue, dataType)

  infix def :=(value: Expr[A]): Unit = CandidateRuntime.statement(this, value)

object Reg:
  def apply[A <: Data](init: Expr[A]): Register[A] = new Register(init, null)

  def uninitialized[A <: Data](dataType: DataType[A]): Register[A] =
    new Register(null, dataType)

object RegNext:
  def apply[A <: Data](next: Expr[A], init: Expr[A]): Register[A] =
    val register = new Register(init, null)
    register := next
    register

  def uninitialized[A <: Data](next: Expr[A]): Register[A] =
    val register = new Register[A](null, null)
    register := next
    register

def when(condition: Expr[Bool])(body: => Unit): Unit =
  CandidateRuntime.block(CandidateRuntime.event(condition), body)

def elsewhen(condition: Expr[Bool])(body: => Unit): Unit =
  CandidateRuntime.block(CandidateRuntime.event(condition), body)

def otherwise(body: => Unit): Unit = CandidateRuntime.block(body)

/** Value carrying an explicit Gray-code proof for CDC. */
final class Gray[A <: Data] private[nodal] (val value: Expr[A])

object Gray:
  def apply[A <: Data](value: Expr[A]): Gray[A] = new Gray(value)

/** Source pulse value that must use pulse/toggle transfer semantics. */
final class Pulse private[nodal] (val value: Expr[Bool])

object Pulse:
  def apply(value: Expr[Bool]): Pulse = new Pulse(value)

/** Minimal stream carrier used only to freeze the asynchronous-FIFO crossing shape. */
final class Stream[A <: Data] private[nodal] (val payload: Expr[A])

object Stream:
  def apply[A <: Data](payload: Expr[A]): Stream[A] = new Stream(payload)

final case class CdcWaiver(
    id: String,
    reason: String,
    relation: ClockRelation
)

object Cdc:
  def sync(bit: Expr[Bool], to: ClockDomain, stages: Int = 2): Expr[Bool] =
    CandidateRuntime.expr(bit, to, stages)

  def gray[A <: Data](
      grayValue: Gray[A],
      to: ClockDomain,
      stages: Int = 2
  ): Expr[A] = CandidateRuntime.expr(grayValue, to, stages)

  def pulse(pulse: Pulse, to: ClockDomain): Expr[Bool] =
    CandidateRuntime.expr(pulse, to)

  def handshake[A <: Data](payload: Expr[A], to: ClockDomain): Expr[A] =
    CandidateRuntime.expr(payload, to)

  def fifo[A <: Data](
      stream: Stream[A],
      to: ClockDomain,
      depth: Int = 4
  ): Stream[A] =
    CandidateRuntime.statement(to, depth)
    stream

  def waive[A <: Data](
      value: Expr[A],
      to: ClockDomain,
      waiver: CdcWaiver
  ): Expr[A] = CandidateRuntime.expr(value, to, waiver)

object Rdc:
  /** Domain-construction form: the receiving generated domain supplies the destination. */
  def sync(reset: Expr[Reset], stages: Int): Expr[Reset] =
    CandidateRuntime.expr(reset, stages)

  /** Existing-domain transfer form: the destination is explicit. */
  def sync(
      reset: Expr[Reset],
      to: ClockDomain,
      stages: Int = 2
  ): Expr[Reset] = CandidateRuntime.expr(reset, to, stages)

object ResetController:
  def combine(resets: Expr[Reset]*): Expr[Reset] = CandidateRuntime.expr(resets)

object ClockGate:
  def apply(
      domain: ClockDomain,
      enable: Expr[Bool],
      testEnable: Expr[Bool] = false.B,
      name: String = "gated"
  ): ClockDomain =
    CandidateRuntime.statement(enable, testEnable)
    new ClockDomain(name, domain.reset)

object ClockMux:
  def glitchless(
      select: Expr[Bool],
      domains: Seq[ClockDomain],
      name: String = "selected"
  ): ClockDomain =
    CandidateRuntime.statement(select, domains)
    new ClockDomain(name, CandidateRuntime.expr(domains))

/** Candidate event handle. */
final class Event private[nodal] ()

enum Edge:
  case Either, Rising, Falling

def analog(body: => Unit): Unit = CandidateRuntime.block(body)

def initial(body: => Unit): Unit = CandidateRuntime.block(body)

def on(event: Event)(body: => Unit): Unit = CandidateRuntime.block(event, body)

def V[D <: Discipline](node: Node[D]): Expr[Real] = CandidateRuntime.expr(node)

def V[D <: Discipline](positive: Node[D], negative: Node[D]): Expr[Real] =
  CandidateRuntime.expr(positive, negative)

def I[D <: Discipline](node: Node[D]): Expr[Real] = CandidateRuntime.expr(node)

def I[D <: Discipline](positive: Node[D], negative: Node[D]): Expr[Real] =
  CandidateRuntime.expr(positive, negative)

def ddt(value: Expr[Real]): Expr[Real] = CandidateRuntime.expr(value)

def idt(value: Expr[Real]): Expr[Real] = CandidateRuntime.expr(value)

def idt(value: Expr[Real], initialValue: Expr[Real]): Expr[Real] =
  CandidateRuntime.expr(value, initialValue)

def cross(value: Expr[Real], edge: Edge = Edge.Either): Event =
  CandidateRuntime.event(value, edge)

def timer(start: Expr[Real]): Event = CandidateRuntime.event(start)

def timer(start: Expr[Real], period: Expr[Real]): Event =
  CandidateRuntime.event(start, period)

def transition(
    value: Expr[Real],
    delay: Expr[Real],
    rise: Expr[Real],
    fall: Expr[Real]
): Expr[Real] = CandidateRuntime.expr(value, delay, rise, fall)

def toUInt(value: Expr[Real], width: Int): Expr[UInt] =
  CandidateRuntime.expr(value, width)

def toUInt(value: Expr[Real], width: Expr[Integer]): Expr[UInt] =
  CandidateRuntime.expr(value, width)

def toReal(value: Expr[UInt]): Expr[Real] = CandidateRuntime.expr(value)

object lowlevel:
  def process(event: Event)(body: => Unit): Unit = CandidateRuntime.block(event, body)

extension (left: Expr[Real])
  def +(right: Expr[Real]): Expr[Real] = CandidateRuntime.expr(left, right)
  def -(right: Expr[Real]): Expr[Real] = CandidateRuntime.expr(left, right)
  def *(right: Expr[Real]): Expr[Real] = CandidateRuntime.expr(left, right)
  def /(right: Expr[Real]): Expr[Real] = CandidateRuntime.expr(left, right)
  def unary_- : Expr[Real] = CandidateRuntime.expr(left)
  def >(right: Expr[Real]): Expr[Bool] = CandidateRuntime.expr(left, right)
  def >=(right: Expr[Real]): Expr[Bool] = CandidateRuntime.expr(left, right)
  def <(right: Expr[Real]): Expr[Bool] = CandidateRuntime.expr(left, right)
  def <=(right: Expr[Real]): Expr[Bool] = CandidateRuntime.expr(left, right)
  infix def <+(value: Expr[Real]): Unit = CandidateRuntime.statement(left, value)

extension (left: Expr[UInt])
  @targetName("uintAddition")
  def +(right: Expr[UInt]): Expr[UInt] = CandidateRuntime.expr(left, right)

extension (left: Expr[Bool])
  def &&(right: Expr[Bool]): Expr[Bool] = CandidateRuntime.expr(left, right)
  def ||(right: Expr[Bool]): Expr[Bool] = CandidateRuntime.expr(left, right)
  def unary_! : Expr[Bool] = CandidateRuntime.expr(left)
  def rising: Event = CandidateRuntime.event(left, Edge.Rising)
  def falling: Event = CandidateRuntime.event(left, Edge.Falling)

extension (value: Double)
  def real: Expr[Real] = realLiteral(value, "")
  def V: Expr[Real] = realLiteral(value, "V")
  def A: Expr[Real] = realLiteral(value, "A")
  def Ohm: Expr[Real] = realLiteral(value, "Ohm")
  def kOhm: Expr[Real] = realLiteral(value * 1.0e3, "Ohm")
  def F: Expr[Real] = realLiteral(value, "F")
  def pF: Expr[Real] = realLiteral(value * 1.0e-12, "F")
  def s: Expr[Real] = realLiteral(value, "s")
  def ns: Expr[Real] = realLiteral(value * 1.0e-9, "s")
  def MHz: Frequency = new CandidateFrequency(value, "MHz")
  def deg: Phase = new CandidatePhase(value, "deg")

extension (value: Int)
  def real: Expr[Real] = realLiteral(value.toDouble, "")
  def integer: Expr[Integer] = CandidateRuntime.expr(value)
  def U(width: Int): Expr[UInt] = CandidateRuntime.expr(value, width)
  def U(width: Expr[Integer]): Expr[UInt] = CandidateRuntime.expr(value, width)
  def V: Expr[Real] = realLiteral(value.toDouble, "V")
  def A: Expr[Real] = realLiteral(value.toDouble, "A")
  def Ohm: Expr[Real] = realLiteral(value.toDouble, "Ohm")
  def kOhm: Expr[Real] = realLiteral(value.toDouble * 1.0e3, "Ohm")
  def F: Expr[Real] = realLiteral(value.toDouble, "F")
  def pF: Expr[Real] = realLiteral(value.toDouble * 1.0e-12, "F")
  def s: Expr[Real] = realLiteral(value.toDouble, "s")
  def ns: Expr[Real] = realLiteral(value.toDouble * 1.0e-9, "s")
  def MHz: Frequency = new CandidateFrequency(value.toDouble, "MHz")
  def deg: Phase = new CandidatePhase(value.toDouble, "deg")

extension (value: Boolean)
  def B: Expr[Bool] = CandidateRuntime.expr(value)

private[nodal] def realLiteral(value: Double, unit: String): Expr[Real] =
  CandidateRuntime.expr(value, unit)

private[nodal] object CandidateRuntime:
  private object PlaceholderExpr extends Expr[Nothing]

  def expr[A <: Data](values: Any*): Expr[A] =
    values.foreach(_ => ())
    PlaceholderExpr

  def statement(values: Any*): Unit = values.foreach(_ => ())

  def block(body: => Unit): Unit = statement(() => body)

  def block(event: Event, body: => Unit): Unit = statement(event, () => body)

  def event(values: Any*): Event =
    values.foreach(_ => ())
    new Event()
