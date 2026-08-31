package nodal

import java.util.IdentityHashMap

import scala.collection.mutable

/** Construction-session adapter that connects the frozen public API to the Increment 33 semantic
  * recorder. It mirrors module ownership only for source-semantic retention; the existing
  * ConstructionKernel remains authoritative for hierarchy and general expression construction.
  */
private[nodal] object AnalogProceduralConstruction:
  private final case class PendingVariable(
      value: AnyRef,
      dataType: DataType[? <: Data],
      initializer: Option[Expr[? <: Data]],
      declarationOrder: Int
  )

  private final class ModuleState(
      val module: Module,
      val owner: String,
      val recorder: AnalogProceduralRuntime.Recorder
  ):
    val pending = mutable.ArrayBuffer.empty[PendingVariable]
    val variables = new IdentityHashMap[AnyRef, AnalogProceduralRuntime.Variable]()
    var childSerial = 0
    var statementSerial = 0
    var scopeSerial = 0
    var procedureDepth = 0

  private final class State:
    val stack = mutable.ArrayBuffer.empty[ModuleState]
    val modules = mutable.ArrayBuffer.empty[ModuleState]

  private val current = new ThreadLocal[State]

  private def state: State =
    var value = current.get()
    if value == null then
      value = new State
      current.set(value)
    value

  private def moduleName(module: Module): String =
    val value = module.getClass.getSimpleName.stripSuffix("$")
    if value.isEmpty then "AnonymousModule" else value

  private def activeModule: ModuleState =
    state.stack.lastOption.getOrElse(
      throw new AnalogProceduralRuntime.Failure(
        AnalogProceduralRuntime.Diagnostic(
          "NODAL-ANALOG-033-003",
          "procedural variable construction has no active component"
        )
      )
    )

  def reset(): Unit = current.remove()

  def beginModule(module: Module): Unit =
    val parent = state.stack.lastOption
    val localName = moduleName(module)
    val owner = parent match
      case Some(value) =>
        val serial = value.childSerial
        value.childSerial += 1
        s"${value.owner}.$localName#$serial"
      case None => localName
    val moduleState = new ModuleState(
      module,
      owner,
      new AnalogProceduralRuntime.Recorder(owner)
    )
    state.modules += moduleState
    state.stack += moduleState

  def attachInstance(module: Module): Unit =
    val stack = state.stack
    if stack.nonEmpty && (stack.last.module eq module) && stack.size > 1 then
      stack.remove(stack.size - 1)

  def declareVariable[A <: Data](
      value: Variable[A],
      dataType: DataType[A],
      initializer: Option[Expr[A]]
  ): Unit =
    val module = activeModule
    module.pending += PendingVariable(
      value,
      dataType,
      initializer,
      module.pending.size
    )

  private def scalarKind(
      dataType: DataType[? <: Data]
  ): AnalogProceduralRuntime.ScalarKind = CandidateRuntime.typeDescriptor(dataType).kind match
    case "Integer" => AnalogProceduralRuntime.ScalarKind.Integer
    case "Bool" => AnalogProceduralRuntime.ScalarKind.Boolean
    case "Real" => AnalogProceduralRuntime.ScalarKind.Real
    case other =>
      throw new AnalogProceduralRuntime.Failure(
        AnalogProceduralRuntime.Diagnostic(
          "NODAL-ANALOG-033-019",
          s"unsupported procedural variable scalar kind '$other'"
        )
      )

  private def dimensionFromUnit(unit: String): String = unit match
    case "" => "dimensionless"
    case "V" => "voltage"
    case "A" => "current"
    case "Ohm" => "resistance"
    case "F" => "capacitance"
    case "s" => "time"
    case other => other

  private def collectReads(value: Any): Vector[AnalogProceduralRuntime.Variable] =
    val result = mutable.LinkedHashSet.empty[AnalogProceduralRuntime.Variable]
    def visit(candidate: Any): Unit = candidate match
      case reference: AnyRef =>
        state.modules.iterator
          .flatMap(module => Option(module.variables.get(reference)))
          .foreach(result += _)
        reference match
          case expression: KernelExpr[?] => expression.operands.foreach(visit)
          case _ => ()
      case iterable: Iterable[?] => iterable.foreach(visit)
      case _ => ()
    visit(value)
    result.toVector

  private def valueType(
      expression: Expr[? <: Data],
      fallback: Option[AnalogProceduralRuntime.ValueType] = None
  ): AnalogProceduralRuntime.ValueType =
    val reads = collectReads(expression)
    val kind = CandidateRuntime
      .expressionDataType(expression)
      .map(scalarKind)
      .orElse(reads.headOption.map(_.valueType.kind))
      .orElse(fallback.map(_.kind))
      .getOrElse(AnalogProceduralRuntime.ScalarKind.Real)
    val dimension = CandidateRuntime
      .expressionUnit(expression)
      .map(dimensionFromUnit)
      .filterNot(_ == "dimensionless")
      .orElse(reads.headOption.map(_.valueType.dimension))
      .orElse(fallback.map(_.dimension))
      .getOrElse("dimensionless")
    AnalogProceduralRuntime.ValueType(kind, dimension)

  private def rendered(expression: Expr[? <: Data]): String = expression match
    case value: KernelExpr[?] =>
      value.literal.map(_.value).orElse(value.operation).getOrElse("expression")
    case _ => "expression"

  private def semanticValue(
      expression: Expr[? <: Data],
      fallback: Option[AnalogProceduralRuntime.ValueType] = None
  ): AnalogProceduralRuntime.Value = AnalogProceduralRuntime.Value(
    rendered(expression),
    valueType(expression, fallback),
    collectReads(expression)
  )

  private def materializeVariables(module: ModuleState): Unit =
    module.pending.foreach: pending =>
      if module.variables.get(pending.value) == null then
        val initialValue = pending.initializer.map: initializer =>
          semanticValue(initializer)
        val inferredDimension = initialValue
          .map(_.valueType.dimension)
          .getOrElse("dimensionless")
        val valueType = AnalogProceduralRuntime.ValueType(
          scalarKind(pending.dataType),
          inferredDimension
        )
        val variable = module.recorder.declare(
          s"variable_${pending.declarationOrder}",
          valueType,
          initialValue
        )
        module.variables.put(pending.value, variable)

  def procedure[A](body: => A): A =
    val module = activeModule
    module.procedureDepth += 1
    try
      module.recorder.procedure:
        materializeVariables(module)
        body
    finally module.procedureDepth -= 1

  def lexicalScope[A](body: => A): A =
    val module = activeModule
    if module.procedureDepth == 0 then body
    else
      val identity = s"block_${module.scopeSerial}"
      module.scopeSerial += 1
      module.recorder.scope(identity)(body)

  def assign[A <: Data](target: Variable[A], expression: Expr[A]): Unit =
    val module = activeModule
    if module.procedureDepth == 0 then
      throw new AnalogProceduralRuntime.Failure(
        AnalogProceduralRuntime.Diagnostic(
          "NODAL-ANALOG-033-008",
          "analog variable assignment requires an active procedural region"
        )
      )
    materializeVariables(module)
    val variable = Option(module.variables.get(target)).getOrElse(
      throw new AnalogProceduralRuntime.Failure(
        AnalogProceduralRuntime.Diagnostic(
          "NODAL-ANALOG-033-017",
          "procedural assignment target is not registered in the active component"
        )
      )
    )
    val statement = s"statement_${module.statementSerial}"
    module.statementSerial += 1
    module.recorder.assign(
      statement,
      variable,
      semanticValue(expression, Some(variable.valueType))
    )

  def snapshots: Vector[AnalogProceduralRuntime.Snapshot] =
    Option(current.get()).toVector.flatMap(_.modules.map(_.recorder.snapshot))
