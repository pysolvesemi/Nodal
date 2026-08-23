#!/usr/bin/env python3
"""Apply the deterministic Increment 16 construction-kernel source changes."""

from __future__ import annotations

from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def replace_once(path: str, old: str, new: str) -> None:
    target = ROOT / path
    text = target.read_text(encoding="utf-8")
    if new in text:
        return
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"expected one anchor in {path}, found {count}: {old[:120]!r}")
    target.write_text(text.replace(old, new, 1), encoding="utf-8")


def append_once(path: str, marker: str, content: str) -> None:
    target = ROOT / path
    text = target.read_text(encoding="utf-8")
    if marker in text:
        return
    target.write_text(text.rstrip() + "\n" + content.lstrip("\n"), encoding="utf-8")


def patch_candidate_api() -> None:
    path = "core/scala/api/src/nodal/CandidateApi.scala"
    replace_once(
        path,
        """/** Backend-neutral expression placeholder used only to prove candidate source syntax. */
sealed trait Expr[+A <: Data]

/** Candidate declaration-time type descriptor. */
sealed trait DataType[+A <: Data]
""",
        """/** Backend-neutral expression node. Construction metadata remains private to Nodal. */
sealed trait Expr[+A <: Data]

private[nodal] final class KernelExpr[A <: Data](val operands: Vector[Any]) extends Expr[A]

/** Candidate declaration-time type descriptor. */
sealed trait DataType[+A <: Data]

private[nodal] final class KernelDataType[A <: Data](
    val kernelDescriptor: KernelTypeDescriptor
) extends DataType[A],
      KernelDescribedType
""",
    )
    replace_once(
        path,
        """object Bits:
  def apply(width: Int): DataType[Bits] = new WidthDataType[Bits](width)
  def apply(width: Expr[Integer]): DataType[Bits] = new WidthDataType[Bits](width)

object UInt:
  def apply(width: Int): DataType[UInt] = new WidthDataType[UInt](width)
  def apply(width: Expr[Integer]): DataType[UInt] = new WidthDataType[UInt](width)

private final class WidthDataType[A <: Data](width: Any) extends DataType[A]:
  CandidateRuntime.statement(width)
""",
        """object Bits:
  def apply(width: Int): DataType[Bits] = new WidthDataType[Bits]("Bits", width)
  def apply(width: Expr[Integer]): DataType[Bits] = new WidthDataType[Bits]("Bits", width)

object UInt:
  def apply(width: Int): DataType[UInt] = new WidthDataType[UInt]("UInt", width)
  def apply(width: Expr[Integer]): DataType[UInt] = new WidthDataType[UInt]("UInt", width)

private final class WidthDataType[A <: Data](kind: String, width: Any)
    extends DataType[A],
      KernelDescribedType:
  val kernelDescriptor = KernelTypeDescriptor(kind, Vector(width))
""",
    )
    replace_once(
        path,
        """/** Candidate module parameter. */
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
""",
        """/** Candidate module parameter. */
final class Param[A <: Data] private[nodal] (val default: Expr[A]) extends Expr[A]:
  CandidateRuntime.declare(
    this,
    KernelSignalKind.Parameter,
    attributes = Vector("default" -> default)
  )

/** Candidate digital signal or port. */
final class Signal[A <: Data] private[nodal] (
    val dataType: DataType[A],
    kind: KernelSignalKind
) extends Expr[A]:
  CandidateRuntime.declare(this, kind, dataType = Some(dataType))

  infix def :=(value: Expr[A]): Unit = CandidateRuntime.assign(this, value)

/** Candidate elaboration-time variable visible to behavioral blocks. */
final class Variable[A <: Data] private[nodal] (
    val dataType: DataType[A],
    val initialValue: Expr[A]
) extends Expr[A]:
  CandidateRuntime.declare(
    this,
    KernelSignalKind.Variable,
    dataType = Some(dataType),
    attributes = Vector("initial" -> initialValue)
  )

  infix def :=(value: Expr[A]): Unit = CandidateRuntime.assign(this, value)

/** Candidate analog node or port. */
final class Node[D <: Discipline] private[nodal] (
    val discipline: D,
    kind: KernelSignalKind
):
  CandidateRuntime.declare(
    this,
    kind,
    attributes = Vector("discipline" -> discipline)
  )
""",
    )
    replace_once(
        path,
        """/** Lexically applied candidate clock/reset domain. */
final class ClockDomain private[nodal] (
    val name: String,
    val reset: Expr[Reset]
):
  def apply(body: => Unit): Unit = CandidateRuntime.block(body)
""",
        """/** Lexically applied clock/reset domain owned by the active construction transaction. */
final class ClockDomain private[nodal] (
    val name: String,
    val reset: Expr[Reset],
    kind: KernelDomainKind
):
  CandidateRuntime.registerDomain(this, kind)

  def apply(body: => Unit): Unit = CandidateRuntime.domainBlock(this, body)
""",
    )
    replace_once(
        path,
        """    CandidateRuntime.statement(edge, reset, resetPolarity, frequency)
    new ClockDomain(name, CandidateRuntime.expr(name, reset))
""",
        """    CandidateRuntime.statement(edge, reset, resetPolarity, frequency)
    new ClockDomain(
      name,
      CandidateRuntime.expr(name, reset),
      KernelDomainKind.External
    )
""",
    )
    replace_once(
        path,
        """    CandidateRuntime.statement(clock, edge, policy, polarity, frequency)
    new ClockDomain(name, reset)

  def required(name: String = "default"): ClockDomain =
    new ClockDomain(name, CandidateRuntime.expr(name))
""",
        """    CandidateRuntime.statement(clock, edge, policy, polarity, frequency)
    new ClockDomain(name, reset, KernelDomainKind.Bound)

  def required(name: String = "default"): ClockDomain =
    new ClockDomain(
      name,
      CandidateRuntime.expr(name),
      KernelDomainKind.Required
    )
""",
    )
    replace_once(
        path,
        """    CandidateRuntime.statement(clock, from, relation)
    new ClockDomain(name, reset)
""",
        """    CandidateRuntime.statement(clock, from, relation)
    new ClockDomain(name, reset, KernelDomainKind.Generated)
""",
    )
    replace_once(
        path,
        """/** Candidate child-instance handle with typed selector-based access and overrides. */
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
""",
        """/** Child-instance handle with typed selector-based overrides and domain bindings. */
final class Instance[M <: Module] private[nodal] (private[nodal] val module: M):
  CandidateRuntime.attachInstance(this, module)

  def apply[A](select: M => A): A = select(module)

  def param[A <: Data](select: M => Param[A], value: Expr[A]): this.type =
    val parameter = select(module)
    CandidateRuntime.overrideParameter(this, parameter, value)
    this

  def domain(domain: ClockDomain): this.type =
    CandidateRuntime.bindDefaultDomain(this, domain)
    this

  def domain(select: M => ClockDomain, domain: ClockDomain): this.type =
    CandidateRuntime.bindNamedDomain(this, select(module), domain)
    this
""",
    )
    replace_once(
        path,
        """/** Short Verilog-AMS-like module construction candidate. */
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
""",
        """/** Short Verilog-AMS-like module construction surface backed by Increment 16. */
abstract class Module:
  CandidateRuntime.beginModule(this)

  protected final def param[A <: Data](default: Expr[A]): Param[A] = new Param(default)

  protected final def in[A <: Data](dataType: DataType[A]): Signal[A] =
    new Signal(dataType, KernelSignalKind.Input)

  protected final def out[A <: Data](dataType: DataType[A]): Signal[A] =
    new Signal(dataType, KernelSignalKind.Output)

  protected final def in[D <: Discipline](discipline: D): Node[D] =
    new Node(discipline, KernelSignalKind.AnalogInput)

  protected final def out[D <: Discipline](discipline: D): Node[D] =
    new Node(discipline, KernelSignalKind.AnalogOutput)

  protected final def inout[D <: Discipline](discipline: D): Node[D] =
    new Node(discipline, KernelSignalKind.AnalogInout)

  protected final def node[D <: Discipline](discipline: D): Node[D] =
    new Node(discipline, KernelSignalKind.AnalogNode)

  protected final def wire[A <: Data](dataType: DataType[A]): Signal[A] =
    new Signal(dataType, KernelSignalKind.Wire)
""",
    )
    replace_once(
        path,
        """  protected final def connect[A <: Data](left: Signal[A], right: Signal[A]): Unit =
    CandidateRuntime.statement(left, right)

  protected final def connect[D <: Discipline](left: Node[D], right: Node[D]): Unit =
    CandidateRuntime.statement(left, right)
""",
        """  protected final def connect[A <: Data](left: Signal[A], right: Signal[A]): Unit =
    CandidateRuntime.connectValues(left, right)

  protected final def connect[D <: Discipline](left: Node[D], right: Node[D]): Unit =
    CandidateRuntime.connectNodes(left, right)
""",
    )
    replace_once(
        path,
        """/** Domain-owned state candidate. */
final class Register[A <: Data] private[nodal] (
    initialValue: Option[Expr[A]],
    dataType: Option[DataType[A]]
) extends Expr[A]:
  CandidateRuntime.statement(initialValue, dataType)

  infix def :=(value: Expr[A]): Unit = CandidateRuntime.statement(this, value)
""",
        """/** Domain-owned state captured at its lexical construction point. */
final class Register[A <: Data] private[nodal] (
    val initialValue: Option[Expr[A]],
    val dataType: Option[DataType[A]]
) extends Expr[A]:
  CandidateRuntime.declare(
    this,
    KernelSignalKind.Register,
    dataType = dataType,
    domain = CandidateRuntime.currentDomain,
    attributes = Vector("initial" -> initialValue)
  )

  infix def :=(value: Expr[A]): Unit = CandidateRuntime.assign(this, value)
""",
    )
    replace_once(
        path,
        """    CandidateRuntime.statement(enable, testEnable)
    new ClockDomain(name, domain.reset)
""",
        """    CandidateRuntime.statement(enable, testEnable)
    new ClockDomain(name, domain.reset, KernelDomainKind.Generated)
""",
    )
    replace_once(
        path,
        """    CandidateRuntime.statement(select, domains)
    new ClockDomain(name, CandidateRuntime.expr(domains))
""",
        """    CandidateRuntime.statement(select, domains)
    new ClockDomain(
      name,
      CandidateRuntime.expr(domains),
      KernelDomainKind.Generated
    )
""",
    )
    replace_once(
        path,
        """private[nodal] object CandidateRuntime:
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
""",
        """private[nodal] object CandidateRuntime:
  def dataType[A <: Data](kind: String, arguments: Any*): DataType[A] =
    new KernelDataType[A](KernelTypeDescriptor(kind, arguments.toVector))

  def typeDescriptor(dataType: DataType[?]): KernelTypeDescriptor = dataType match
    case described: KernelDescribedType => described.kernelDescriptor
    case Real => KernelTypeDescriptor("Real")
    case Integer => KernelTypeDescriptor("Integer")
    case Bool => KernelTypeDescriptor("Bool")
    case Clock => KernelTypeDescriptor("Clock")
    case Reset => KernelTypeDescriptor("Reset")

  def beginModule(module: Module): Unit = ConstructionKernel.beginModule(module)

  def registerDomain(domain: ClockDomain, kind: KernelDomainKind): Unit =
    ConstructionKernel.registerDomain(domain, kind)

  def declare(
      value: AnyRef,
      kind: KernelSignalKind,
      dataType: Option[DataType[? <: Data]] = None,
      explicitName: Option[String] = None,
      domain: Option[ClockDomain] = None,
      attributes: Vector[(String, Any)] = Vector.empty
  ): Unit =
    ConstructionKernel.declare(value, kind, dataType, explicitName, domain, attributes)

  def expr[A <: Data](values: Any*): Expr[A] =
    val expression = new KernelExpr[A](values.toVector)
    ConstructionKernel.expression(expression)
    expression

  def statement(values: Any*): Unit = ConstructionKernel.operation("statement", values*)

  def assign(left: AnyRef, right: Any): Unit =
    ConstructionKernel.operation("assignment", left, right)

  def connectValues(left: AnyRef, right: AnyRef): Unit =
    ConstructionKernel.operation("value-connect", left, right)

  def connectNodes(left: AnyRef, right: AnyRef): Unit =
    ConstructionKernel.operation("node-connect", left, right)

  def attachInstance(instance: Instance[? <: Module], module: Module): Unit =
    ConstructionKernel.attachInstance(instance, module)

  def bindDefaultDomain(instance: Instance[?], domain: ClockDomain): Unit =
    ConstructionKernel.bindDefault(instance, domain)

  def bindNamedDomain(
      instance: Instance[?],
      requirement: ClockDomain,
      domain: ClockDomain
  ): Unit = ConstructionKernel.bindNamed(instance, requirement, domain)

  def overrideParameter(instance: Instance[?], parameter: Any, value: Any): Unit =
    ConstructionKernel.overrideParameter(instance, parameter, value)

  def currentDomain: Option[ClockDomain] = ConstructionKernel.currentDomain

  def domainBlock(domain: ClockDomain, body: => Unit): Unit =
    ConstructionKernel.domainBlock(domain)(body)

  def block(body: => Unit): Unit =
    ConstructionKernel.operation("block")
    ConstructionKernel.block(body)

  def block(event: Event, body: => Unit): Unit =
    ConstructionKernel.operation("event-block", event)
    ConstructionKernel.block(body)

  def event(values: Any*): Event =
    ConstructionKernel.operation("event", values*)
    new Event()
""",
    )


def patch_core_semantics() -> None:
    path = "core/scala/api/src/nodal/CoreSemanticsCandidateApi.scala"
    replace_once(
        path,
        """  def apply(width: Int): DataType[SInt] =
    CandidateRuntime.statement(width)
    Bits(width).asInstanceOf[DataType[SInt]]

  def apply(width: Expr[Integer]): DataType[SInt] =
    CandidateRuntime.statement(width)
    Bits(width).asInstanceOf[DataType[SInt]]
""",
        """  def apply(width: Int): DataType[SInt] =
    CandidateRuntime.dataType[SInt]("SInt", width)

  def apply(width: Expr[Integer]): DataType[SInt] =
    CandidateRuntime.dataType[SInt]("SInt", width)
""",
    )
    replace_once(
        path,
        """object Vec:
  def apply[A <: Data](element: DataType[A], dimensions: Dimension*): DataType[Vec[A]] =
    CandidateRuntime.statement(element, dimensions)
    Bits(1).asInstanceOf[DataType[Vec[A]]]
""",
        """object Vec:
  def apply[A <: Data](element: DataType[A], dimensions: Dimension*): DataType[Vec[A]] =
    CandidateRuntime.dataType[Vec[A]]("Vec", element, dimensions.toSeq)
""",
    )
    replace_once(
        path,
        """final class Mem[A <: Data] private[nodal] (
    val element: DataType[A],
    val depth: Dimension,
    val readLatency: Int,
    val readUnderWrite: ReadUnderWrite,
    val ordering: MemoryOrdering,
    val domain: ClockDomain
):
  def read(address: Expr[UInt]): Expr[A] = CandidateRuntime.expr(this, address)
""",
        """final class Mem[A <: Data] private[nodal] (
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
""",
    )


def patch_interface_api() -> None:
    path = "core/scala/api/src/nodal/PipelineInterfaceCandidateApi.scala"
    replace_once(
        path,
        """object Struct:
  def apply(name: String, fields: StructField[? <: Data]*): DataType[Struct] =
    CandidateRuntime.statement(name, fields)
    Bits(1).asInstanceOf[DataType[Struct]]
""",
        """object Struct:
  def apply(name: String, fields: StructField[? <: Data]*): DataType[Struct] =
    CandidateRuntime.dataType[Struct]("Struct", name, fields.toSeq)
""",
    )
    replace_once(
        path,
        """final class InterfacePort[I <: Interface, R <: RoleKind] private[nodal] (
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
""",
        """final class InterfacePort[I <: Interface, R <: RoleKind] private[nodal] (
    val definition: InterfaceType[I],
    val role: Role[R],
    val name: String,
    val domain: ClockDomain,
    private[nodal] val exported: Boolean
):
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
    val count: Dimension,
    val name: String,
    val domain: ClockDomain
):
  CandidateRuntime.declare(
    this,
    KernelSignalKind.InterfaceArray,
    explicitName = Some(name),
    domain = Some(domain),
    attributes = Vector(
      "interface" -> definition.name,
      "role" -> role.name,
      "count" -> count
    )
  )
""",
    )
    replace_once(
        path,
        "): InterfacePort[I, R] = new InterfacePort(definition, role, name, domain)\n",
        "): InterfacePort[I, R] = new InterfacePort(definition, role, name, domain, exported = true)\n",
    )
    replace_once(
        path,
        """    new InterfacePort(
      endpoint.definition,
      invertedRole,
      s"${endpoint.name}.inverse",
      endpoint.domain
    )
""",
        """    new InterfacePort(
      endpoint.definition,
      invertedRole,
      s"${endpoint.name}.inverse",
      endpoint.domain,
      exported = false
    )
""",
    )
    replace_once(
        path,
        """  def monitorView: InterfacePort[I, MonitorRole] =
    new InterfacePort(endpoint.definition, monitor, s"${endpoint.name}.monitor", endpoint.domain)
""",
        """  def monitorView: InterfacePort[I, MonitorRole] =
    new InterfacePort(
      endpoint.definition,
      monitor,
      s"${endpoint.name}.monitor",
      endpoint.domain,
      exported = false
    )
""",
    )
    replace_once(
        path,
        """  def connectExact[OtherRole <: RoleKind](
      other: InterfacePort[I, OtherRole]
  )(using RoleConnection[R, OtherRole]): Unit = CandidateRuntime.statement(endpoint, other)
""",
        """  def connectExact[OtherRole <: RoleKind](
      other: InterfacePort[I, OtherRole]
  )(using RoleConnection[R, OtherRole]): Unit =
    ConstructionKernel.operation("interface-connect", endpoint, other)
""",
    )
    replace_once(
        path,
        """final class DigitalInout[A <: Bits, M <: DriveMode] private[nodal] (
    val dataType: DataType[A],
    val mode: DriveMode.Value[M],
    val placement: InoutPlacement,
    val profile: ResolutionProfile,
    val name: String
):
  def read: Expr[A] = CandidateRuntime.expr(this, "read")
""",
        """final class DigitalInout[A <: Bits, M <: DriveMode] private[nodal] (
    val dataType: DataType[A],
    val mode: DriveMode.Value[M],
    val placement: InoutPlacement,
    val profile: ResolutionProfile,
    val name: String
):
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

  def read: Expr[A] = CandidateRuntime.expr(this, "read")
""",
    )
    replace_once(
        path,
        """extension [A <: Bits, M <: DriveMode](endpoint: DigitalInout[A, M])
  def drive(value: Expr[A], enable: Expr[Bool])(using ArbitraryDrive[M]): Unit =
    CandidateRuntime.statement(endpoint, value, enable)

  def highZ()(using ReleasableDrive[M]): Unit = CandidateRuntime.statement(endpoint, "high-z")
""",
        """extension [A <: Bits, M <: DriveMode](endpoint: DigitalInout[A, M])
  def drive(value: Expr[A], enable: Expr[Bool])(using ArbitraryDrive[M]): Unit =
    ConstructionKernel.operation("inout-drive", endpoint, value, enable)

  def highZ()(using ReleasableDrive[M]): Unit =
    ConstructionKernel.operation("inout-high-z", endpoint)
""",
    )
    replace_once(
        path,
        """extension [A <: Bits](endpoint: DigitalInout[A, DriveMode.OpenDrain])
  def driveLow(enable: Expr[Bool]): Unit = CandidateRuntime.statement(endpoint, enable, "low")

extension [A <: Bits](endpoint: DigitalInout[A, DriveMode.OpenSource])
  def driveHigh(enable: Expr[Bool]): Unit = CandidateRuntime.statement(endpoint, enable, "high")
""",
        """extension [A <: Bits](endpoint: DigitalInout[A, DriveMode.OpenDrain])
  def driveLow(enable: Expr[Bool]): Unit =
    ConstructionKernel.operation("inout-drive-low", endpoint, enable)

extension [A <: Bits](endpoint: DigitalInout[A, DriveMode.OpenSource])
  def driveHigh(enable: Expr[Bool]): Unit =
    ConstructionKernel.operation("inout-drive-high", endpoint, enable)
""",
    )
    replace_once(
        path,
        """def passThrough[A <: Bits, M <: DriveMode](
    outer: DigitalInout[A, M],
    inner: DigitalInout[A, M]
): Unit = CandidateRuntime.statement(outer, inner)
""",
        """def passThrough[A <: Bits, M <: DriveMode](
    outer: DigitalInout[A, M],
    inner: DigitalInout[A, M]
): Unit = ConstructionKernel.operation("inout-pass-through", outer, inner)
""",
    )
    replace_once(
        path,
        """final class Terminal[D <: Discipline] private[nodal] (
    val discipline: D,
    val name: String
)
""",
        """final class Terminal[D <: Discipline] private[nodal] (
    val discipline: D,
    val name: String
):
  CandidateRuntime.declare(
    this,
    KernelSignalKind.ConservativeTerminal,
    explicitName = Some(name),
    attributes = Vector("discipline" -> discipline)
  )
""",
    )
    replace_once(
        path,
        """  def connectTo[B <: ConservativeAccess](
      other: TerminalView[D, B]
  )(using CanConnect[A], CanConnect[B]): Unit = CandidateRuntime.statement(view, other)
""",
        """  def connectTo[B <: ConservativeAccess](
      other: TerminalView[D, B]
  )(using CanConnect[A], CanConnect[B]): Unit =
    ConstructionKernel.operation("terminal-connect", view.terminal, other.terminal)
""",
    )
    replace_once(
        path,
        """final class AnalogSignal[D, R <: AnalogDirection] private[nodal] (
    val name: String,
    val dimension: String
)
""",
        """final class AnalogSignal[D, R <: AnalogDirection] private[nodal] (
    val name: String,
    val dimension: String
):
  CandidateRuntime.declare(
    this,
    KernelSignalKind.AnalogSignal,
    explicitName = Some(name),
    attributes = Vector("dimension" -> dimension)
  )
""",
    )


def patch_compiler_api() -> None:
    replace_once(
        "core/scala/api/src/nodal/CompilerApi.scala",
        """/** Stable public compiler entry point. Its implementation is intentionally deferred. */
object Nodal:
  def emit(top: => Module, options: EmitOptions = EmitOptions()): Emission =
    CandidateRuntime.statement(() => top, options)
    Emission(Vector.empty)
""",
        """/** Stable public compiler entry point. Increment 16 implements construction only. */
object Nodal:
  def emit(top: => Module, options: EmitOptions = EmitOptions()): Emission =
    ConstructionKernel.emit(top, options)
""",
    )


def patch_checks() -> None:
    replace_once(
        "scripts/check_increment15.py",
        '            "Emission(Vector.empty)",\n',
        '            "object Nodal:",\n',
    )
    replace_once(
        "scripts/check_increment15.py",
        """    increment16 = [line for line in roadmap.splitlines() if line.startswith("- [ ] **Increment 16 — ")]
    if len(increment16) != 1 or "kernel" not in increment16[0].lower():
        problems.append(Problem("NODAL-INC15-064", "roadmap does not leave one unchecked Increment 16"))
""",
        """    increment16 = [
        line
        for line in roadmap.splitlines()
        if line.startswith(("- [ ] **Increment 16 — ", "- [x] **Increment 16 — "))
    ]
    if len(increment16) != 1 or "kernel" not in increment16[0].lower():
        problems.append(Problem("NODAL-INC15-064", "roadmap does not retain one Increment 16 kernel"))
""",
    )
    replace_once(
        "scripts/nodal.py",
        '        _python(root, "check_increment12.py", "--compile-negative"),\n',
        """        _python(root, "check_increment12.py", "--compile-negative"),
        _python(root, "check_increment13.py", "--compile-negative"),
        _python(root, "check_increment14.py", "--compile-negative"),
        _python(root, "check_increment15.py", "--compile-negative"),
        _python(root, "check_increment16.py"),
""",
    )


def patch_ownership() -> None:
    append_once(
        ".github/CODEOWNERS",
        "/core/scala/api/src/nodal/ElaborationConstructionKernel.scala",
        """
/core/scala/api/src/nodal/ElaborationConstructionKernel.scala @pysolvesemi
/scripts/check_increment16.py @pysolvesemi
/scripts/apply_increment16.py @pysolvesemi
/tests/api/fixtures/increment16/manifest.json @pysolvesemi
/.github/workflows/increment-16-construction-kernel.yml @pysolvesemi
""",
    )


def cleanup_obsolete() -> None:
    obsolete = ROOT / "scripts/materialize_increment16.py"
    if obsolete.exists():
        obsolete.unlink()


def main() -> int:
    patch_candidate_api()
    patch_core_semantics()
    patch_interface_api()
    patch_compiler_api()
    patch_checks()
    patch_ownership()
    cleanup_obsolete()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
