package nodal

/** A named lexical group of hardware in the enclosing Module, never a module boundary. */
abstract class Area extends Selectable:
  ConstructionKernel.beginArea(this)

  /** Scala's structural member typing reads exact compiler-captured members, not reflection. */
  final def selectDynamic(name: String): Any = ConstructionKernel.areaMemberValue(this, name)

  protected final def wire[A <: Data](dataType: DataType[A]): Signal[A] =
    new Signal(dataType, KernelSignalKind.Wire)

  protected final def instance[M <: Module](module: M): Instance[M] =
    CandidateRuntime.instance(module)

  protected final def connect[A <: Data](left: Signal[A], right: Signal[A]): Unit =
    CandidateRuntime.connectValues(left, right)

/** Compiler-owned lifecycle and member capture. The construction session owns every mutable fact. */
private[nodal] object AreaCaptureRuntime:
  def guard[A](site: String, body: () => A): A =
    ConstructorCaptureRuntime.guard(site, body)

  def allocate[A <: Area](
      source: String,
      line: Int,
      column: Int,
      endLine: Int,
      endColumn: Int,
      body: () => A
  ): A =
    ConstructionKernel.allocateArea(
      SourceSpan(source, line, column, endLine, endColumn)
    )(body)

  def bind[A <: Area](name: String, priority: Int, value: A): A =
    ConstructionKernel.bindArea(name, priority, value)
    value

  def member[A](owner: Area, name: String, value: A): A =
    ConstructionKernel.areaMember(owner, name, value)
    value
