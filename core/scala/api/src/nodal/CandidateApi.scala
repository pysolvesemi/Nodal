package nodal

import java.nio.file.Path
import scala.annotation.targetName

/** Frozen v0.1 source-level data hierarchy. Runtime semantics are implemented later. */
sealed trait Data
sealed trait Real extends Data
sealed trait Integer extends Data
sealed trait Bool extends Data
sealed trait Bits extends Data
sealed trait UInt extends Bits

/** Backend-neutral expression placeholder used by the frozen v0.1 source API. */
sealed trait Expr[+A <: Data]

/** Frozen v0.1 declaration-time type descriptor. */
sealed trait DataType[+A <: Data]

case object Real extends DataType[Real]
case object Integer extends DataType[Integer]
case object Bool extends DataType[Bool]

object Bits:
  def apply(width: Int): DataType[Bits] = new WidthDataType[Bits](width)
  def apply(width: Expr[Integer]): DataType[Bits] = new WidthDataType[Bits](width)

object UInt:
  def apply(width: Int): DataType[UInt] = new WidthDataType[UInt](width)
  def apply(width: Expr[Integer]): DataType[UInt] = new WidthDataType[UInt](width)

private final class WidthDataType[A <: Data](width: Any) extends DataType[A]:
  FrozenApiRuntime.statement(width)

/** Frozen v0.1 analog nature metadata. */
final class Nature private[nodal] (val name: String)

/** Frozen v0.1 analog discipline marker. */
sealed trait Discipline

/** Frozen v0.1 named discipline. */
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

/** Frozen v0.1 HDL parameter reference.
  *
  * A Param is preserved as a Verilog-A or Verilog-AMS parameter by default. It is not a Scala
  * elaboration constant and it may participate in backend-constant expressions and widths.
  */
final class Param[A <: Data] private[nodal] (val default: Expr[A]) extends Expr[A]

/** Frozen v0.1 digital signal or port. */
final class Signal[A <: Data] private[nodal] (dataType: DataType[A]) extends Expr[A]:
  FrozenApiRuntime.statement(dataType)

  infix def :=(value: Expr[A]): Unit = FrozenApiRuntime.statement(this, value)

/** Frozen v0.1 behavioral variable. */
final class Variable[A <: Data] private[nodal] (
    dataType: DataType[A],
    initialValue: Expr[A]
) extends Expr[A]:
  FrozenApiRuntime.statement(dataType, initialValue)

  infix def :=(value: Expr[A]): Unit = FrozenApiRuntime.statement(this, value)

/** Frozen v0.1 analog node or port. */
final class Node[D <: Discipline] private[nodal] (val discipline: D)

/** Frozen v0.1 child-instance handle with typed access and named parameter overrides. */
final class Instance[M <: Module] private[nodal] (private val module: M):
  def apply[A](select: M => A): A = select(module)

  def param[A <: Data](select: M => Param[A], value: Expr[A]): this.type =
    FrozenApiRuntime.statement(select(module), value)
    this

/** Short Verilog-AMS-like module construction API frozen at v0.1. */
abstract class Module:
  protected final def param[A <: Data](default: Expr[A]): Param[A] = new Param(default)

  protected final def input[A <: Data](dataType: DataType[A]): Signal[A] =
    new Signal(dataType)

  protected final def output[A <: Data](dataType: DataType[A]): Signal[A] =
    new Signal(dataType)

  protected final def input[D <: Discipline](discipline: D): Node[D] =
    new Node(discipline)

  protected final def output[D <: Discipline](discipline: D): Node[D] =
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
    FrozenApiRuntime.statement(left, right)

  protected final def connect[D <: Discipline](left: Node[D], right: Node[D]): Unit =
    FrozenApiRuntime.statement(left, right)

/** Frozen v0.1 event handle. */
final class Event private[nodal] ()

enum Edge:
  case Either, Rising, Falling

enum Backend:
  case VerilogA, VerilogAMS

/** Frozen v0.1 backend entry point. */
object Nodal:
  def emit(top: => Module, backend: Backend, outputDirectory: Path): Unit =
    FrozenApiRuntime.statement(() => top, backend, outputDirectory)

def analog(body: => Unit): Unit = FrozenApiRuntime.block(body)

def initial(body: => Unit): Unit = FrozenApiRuntime.block(body)

def always(body: => Unit): Unit = FrozenApiRuntime.block(body)

def always(event: Event)(body: => Unit): Unit = FrozenApiRuntime.block(event, body)

def on(event: Event)(body: => Unit): Unit = FrozenApiRuntime.block(event, body)

def V[D <: Discipline](node: Node[D]): Expr[Real] = FrozenApiRuntime.expr(node)

def V[D <: Discipline](positive: Node[D], negative: Node[D]): Expr[Real] =
  FrozenApiRuntime.expr(positive, negative)

def I[D <: Discipline](node: Node[D]): Expr[Real] = FrozenApiRuntime.expr(node)

def I[D <: Discipline](positive: Node[D], negative: Node[D]): Expr[Real] =
  FrozenApiRuntime.expr(positive, negative)

def ddt(value: Expr[Real]): Expr[Real] = FrozenApiRuntime.expr(value)

def idt(value: Expr[Real]): Expr[Real] = FrozenApiRuntime.expr(value)

def idt(value: Expr[Real], initialValue: Expr[Real]): Expr[Real] =
  FrozenApiRuntime.expr(value, initialValue)

def cross(value: Expr[Real], edge: Edge = Edge.Either): Event =
  FrozenApiRuntime.event(value, edge)

def timer(start: Expr[Real]): Event = FrozenApiRuntime.event(start)

def timer(start: Expr[Real], period: Expr[Real]): Event =
  FrozenApiRuntime.event(start, period)

def transition(
    value: Expr[Real],
    delay: Expr[Real],
    rise: Expr[Real],
    fall: Expr[Real]
): Expr[Real] = FrozenApiRuntime.expr(value, delay, rise, fall)

def toUInt(value: Expr[Real], width: Int): Expr[UInt] =
  FrozenApiRuntime.expr(value, width)

def toUInt(value: Expr[Real], width: Expr[Integer]): Expr[UInt] =
  FrozenApiRuntime.expr(value, width)

extension (left: Expr[Real])
  def +(right: Expr[Real]): Expr[Real] = FrozenApiRuntime.expr(left, right)
  def -(right: Expr[Real]): Expr[Real] = FrozenApiRuntime.expr(left, right)
  def *(right: Expr[Real]): Expr[Real] = FrozenApiRuntime.expr(left, right)
  def /(right: Expr[Real]): Expr[Real] = FrozenApiRuntime.expr(left, right)
  def unary_- : Expr[Real] = FrozenApiRuntime.expr(left)
  def >(right: Expr[Real]): Expr[Bool] = FrozenApiRuntime.expr(left, right)
  def >=(right: Expr[Real]): Expr[Bool] = FrozenApiRuntime.expr(left, right)
  def <(right: Expr[Real]): Expr[Bool] = FrozenApiRuntime.expr(left, right)
  def <=(right: Expr[Real]): Expr[Bool] = FrozenApiRuntime.expr(left, right)
  infix def <+(value: Expr[Real]): Unit = FrozenApiRuntime.statement(left, value)

extension (left: Expr[Integer])
  @targetName("integerExpressionAdd")
  def +(right: Expr[Integer]): Expr[Integer] = FrozenApiRuntime.expr(left, right)
  @targetName("integerExpressionSubtract")
  def -(right: Expr[Integer]): Expr[Integer] = FrozenApiRuntime.expr(left, right)
  @targetName("integerExpressionMultiply")
  def *(right: Expr[Integer]): Expr[Integer] = FrozenApiRuntime.expr(left, right)
  @targetName("integerExpressionDivide")
  def /(right: Expr[Integer]): Expr[Integer] = FrozenApiRuntime.expr(left, right)
  infix def **(right: Expr[Integer]): Expr[Integer] = FrozenApiRuntime.expr(left, right)

  @targetName("integerExpressionToReal")
  def toReal: Expr[Real] = FrozenApiRuntime.expr(left)

extension (left: Expr[UInt])
  @targetName("unsignedExpressionToReal")
  def toReal: Expr[Real] = FrozenApiRuntime.expr(left)

extension (left: Expr[Bool])
  def &&(right: Expr[Bool]): Expr[Bool] = FrozenApiRuntime.expr(left, right)
  def ||(right: Expr[Bool]): Expr[Bool] = FrozenApiRuntime.expr(left, right)
  def unary_! : Expr[Bool] = FrozenApiRuntime.expr(left)
  def rising: Event = FrozenApiRuntime.event(left, Edge.Rising)
  def falling: Event = FrozenApiRuntime.event(left, Edge.Falling)

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

extension (value: Int)
  def real: Expr[Real] = realLiteral(value.toDouble, "")
  def integer: Expr[Integer] = FrozenApiRuntime.expr(value)
  def U(width: Int): Expr[UInt] = FrozenApiRuntime.expr(value, width)
  def V: Expr[Real] = realLiteral(value.toDouble, "V")
  def A: Expr[Real] = realLiteral(value.toDouble, "A")
  def Ohm: Expr[Real] = realLiteral(value.toDouble, "Ohm")
  def kOhm: Expr[Real] = realLiteral(value.toDouble * 1.0e3, "Ohm")
  def F: Expr[Real] = realLiteral(value.toDouble, "F")
  def pF: Expr[Real] = realLiteral(value.toDouble * 1.0e-12, "F")
  def s: Expr[Real] = realLiteral(value.toDouble, "s")
  def ns: Expr[Real] = realLiteral(value.toDouble * 1.0e-9, "s")

extension (value: Boolean)
  def B: Expr[Bool] = FrozenApiRuntime.expr(value)

private[nodal] def realLiteral(value: Double, unit: String): Expr[Real] =
  FrozenApiRuntime.expr(value, unit)

private[nodal] object FrozenApiRuntime:
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
