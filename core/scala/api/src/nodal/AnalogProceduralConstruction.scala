package nodal

import java.util.IdentityHashMap

import scala.collection.mutable

/** Construction-session adapter that connects the frozen public API to the Increment 33 procedural
  * recorder and the Increment 34 structured control-flow builder.
  *
  * Increment 33 remains authoritative for straight-line procedures. Once an explicit Increment 34
  * control construct is observed, assignments and declarations are retained in the structured
  * statement tree instead of being flattened into the straight-line assignment inventory.
  */
private[nodal] object AnalogProceduralConstruction:
  private final case class PendingVariable(
      value: AnyRef,
      dataType: DataType[? <: Data],
      initializer: Option[Expr[? <: Data]],
      declarationOrder: Int,
      source: Option[AnalogProceduralRuntime.Source]
  )

  private final class ModuleState(
      val module: Module,
      val owner: String,
      val recorder: AnalogProceduralRuntime.Recorder
  ):
    val pending = mutable.ArrayBuffer.empty[PendingVariable]
    val variables = new IdentityHashMap[AnyRef, AnalogProceduralRuntime.Variable]()
    val variableRecords = mutable.ArrayBuffer.empty[AnalogProceduralRuntime.VariableRecord]
    val controlExpressions =
      mutable.ArrayBuffer.empty[AnalogProceduralRuntime.ControlExpressionRecord]
    var statementSerial = 0
    var scopeSerial = 0
    var procedureDepth = 0
    val eventBindings = mutable.LinkedHashMap.empty[String, AnyRef]
    var eventDepth = 0
    var controlBuilder: Option[AnalogControlFlowConstruction.Builder] = None
    var controlSnapshot: Option[AnalogControlFlowConstruction.Snapshot] = None
    var controlScope: Vector[String] = Vector.empty

  private final class State:
    val stack = mutable.ArrayBuffer.empty[ModuleState]
    val modules = mutable.ArrayBuffer.empty[ModuleState]
    var finalizedControlSnapshots = Vector.empty[AnalogControlFlowConstruction.Snapshot]

  private val current = new ThreadLocal[State]

  private def state: State = Option(current.get()).getOrElse:
    val value = new State
    current.set(value)
    value

  private def activeModule: ModuleState =
    state.stack.lastOption.getOrElse(
      AnalogProceduralRuntime.reject(
        AnalogProceduralRuntime.Diagnostic(
          "NODAL-ANALOG-033-003",
          "procedural variable construction has no active component"
        )
      )
    )

  private def activeBuilder(module: ModuleState): AnalogControlFlowConstruction.Builder =
    module.controlBuilder.getOrElse(
      AnalogControlFlowRuntime.fail(
        "NODAL-ANALOG-034-015",
        "analog control flow requires an active analogProcedure"
      )
    )

  def reset(): Unit = current.remove()

  def requireContinuousContext(role: String): Unit =
    if Option(current.get()).exists(_.stack.lastOption.exists(_.eventDepth > 0)) then
      AnalogEventRuntime.fail(7, s"$role is not permitted inside an analog event-controlled body")

  def event(
      operation: String,
      arguments: Vector[Expr[? <: Data]],
      analyses: Vector[String] = Vector.empty
  ): Event =
    val module = activeModule
    new Event(Some(KernelAnalogEventDefinition(
      module.module,
      operation,
      arguments.zipWithIndex.map((value, slot) => slot -> value),
      analyses,
      Vector.empty,
      ConstructionKernel.captureAnalogProceduralSource
    )))

  def composeEvents(left: Event, right: Event): Event =
    val module = activeModule
    val definitions = Vector(left, right).map: event =>
      event.analogDefinition.getOrElse(
        AnalogEventRuntime.fail(6, "analog event OR cannot contain digital edge handles")
      )
    if definitions.exists(definition => definition.owner ne module.module) then
      AnalogEventRuntime.fail(5, "analog event OR cannot cross component ownership")
    new Event(Some(KernelAnalogEventDefinition(
      module.module,
      "analog_event_or",
      Vector.empty,
      Vector.empty,
      Vector(left, right),
      ConstructionKernel.captureAnalogProceduralSource
    )))

  private def renderEventValue(module: ModuleState, value: Any): String = value match
    case expression: KernelExpr[?] if expression.literal.nonEmpty => renderValue(module, expression)
    case expression: KernelExpr[?] =>
      s"${expression.operation.getOrElse("expression")}(${expression.operands.map(renderEventValue(module, _)).mkString(",")})"
    case reference: AnyRef if findVariableOwner(reference).nonEmpty =>
      resolveVariable(module, reference).identity
    case reference: AnyRef =>
      val token = s"@event_reference_${module.eventBindings.size}@"
      module.eventBindings.update(token, reference)
      token
    case other => other.toString

  private def finalizeEvents(module: ModuleState): Unit =
    def resolved(value: String): String =
      module.eventBindings.foldLeft(value): (text, entry) =>
        val (token, reference) = entry
        val path = ConstructionKernel.analogReferencePath(reference).getOrElse(
          AnalogEventRuntime.fail(5, "event expression references an unregistered declaration")
        )
        text.replace(token, path)
    module.controlSnapshot = module.controlSnapshot.map: snapshot =>
      snapshot.mapEvents(_.remap(identity, resolved))

  private def freezeEvent(module: ModuleState, event: Event): AnalogEventRuntime.Expression =
    val definition = event.analogDefinition.getOrElse(
      AnalogEventRuntime.fail(6, "analog on requires an analog event, not a digital edge")
    )
    if definition.owner ne module.module then
      AnalogEventRuntime.fail(
        5,
        "analog event belongs to another component or construction session"
      )
    val result = AnalogEventRuntime.Expression(
      definition.operation,
      definition.arguments.map: (slot, expression) =>
        AnalogEventRuntime.Argument(
          slot,
          semanticValue(module, expression, None).copy(rendered =
            renderEventValue(module, expression)
          ),
          ConstructionKernel.analogEventConstant(expression)
        ),
      definition.analyses,
      definition.alternatives.map(freezeEvent(module, _)),
      definition.source,
      definition.name
    )
    result.validate()
    result

  def eventControl[A](event: Event)(body: => A): A =
    val module = activeModule
    if module.procedureDepth == 0 then
      AnalogEventRuntime.fail(1, "analog on requires an active analogProcedure")
    if module.eventDepth > 0 then
      AnalogEventRuntime.fail(7, "analog event controls cannot be nested")
    materializeVariables(module)
    val expression = freezeEvent(module, event)
    val source = ConstructionKernel.captureAnalogProceduralSource
    activeBuilder(module).eventControl(expression, source): identity =>
      module.eventDepth += 1
      try withControlScope(module, identity)(body)
      finally module.eventDepth -= 1

  def beginModule(module: Module, owner: String): Unit =
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
      initializer: Option[Expr[A]],
      source: Option[AnalogProceduralRuntime.Source]
  ): Unit =
    val module = activeModule
    module.pending += PendingVariable(
      value,
      dataType,
      initializer,
      module.pending.size,
      source
    )
    if module.procedureDepth > 0 then materializeVariables(module)

  private def scalarKind(
      dataType: DataType[? <: Data]
  ): AnalogProceduralRuntime.ScalarKind = CandidateRuntime.typeDescriptor(dataType).kind match
    case "Integer" => AnalogProceduralRuntime.ScalarKind.Integer
    case "Bool" => AnalogProceduralRuntime.ScalarKind.Boolean
    case "Real" => AnalogProceduralRuntime.ScalarKind.Real
    case other =>
      AnalogProceduralRuntime.reject(
        AnalogProceduralRuntime.Diagnostic(
          "NODAL-ANALOG-033-019",
          s"unsupported procedural variable scalar kind '$other'"
        )
      )

  private def compatible(
      source: AnalogProceduralRuntime.ScalarKind,
      destination: AnalogProceduralRuntime.ScalarKind
  ): scala.Boolean =
    source == destination ||
      (source == AnalogProceduralRuntime.ScalarKind.Integer &&
        destination == AnalogProceduralRuntime.ScalarKind.Real)

  private def registeredIdentity(module: ModuleState, value: AnyRef): Option[String] =
    Option(module.variables.get(value)).map(_.identity).orElse:
      module.pending
        .find(pending => pending.value eq value)
        .map(pending => s"${module.owner}.variable_${pending.declarationOrder}")

  private def findVariableOwner(value: AnyRef): Option[ModuleState] =
    state.modules.find: module =>
      module.variables.containsKey(value) || module.pending.exists(_.value eq value)

  private def requireVisible(
      module: ModuleState,
      variable: AnalogProceduralRuntime.Variable
  ): Unit =
    if variable.owner != module.owner then
      AnalogProceduralRuntime.reject(
        AnalogProceduralRuntime.Diagnostic(
          "NODAL-ANALOG-033-009",
          s"variable '${variable.identity}' belongs to component '${variable.owner}', not '${module.owner}'",
          Some(variable.identity)
        )
      )
    if !module.controlScope.startsWith(variable.declarationScope) then
      AnalogProceduralRuntime.reject(
        AnalogProceduralRuntime.Diagnostic(
          "NODAL-ANALOG-033-010",
          s"procedural variable '${variable.identity}' is outside its lexical scope",
          Some(variable.identity)
        )
      )

  private def resolveVariable(
      module: ModuleState,
      value: AnyRef
  ): AnalogProceduralRuntime.Variable =
    Option(module.variables.get(value)) match
      case Some(variable) =>
        requireVisible(module, variable)
        variable
      case None =>
        findVariableOwner(value) match
          case Some(owner) if owner ne module =>
            val identity = registeredIdentity(owner, value)
            AnalogProceduralRuntime.reject(
              AnalogProceduralRuntime.Diagnostic(
                "NODAL-ANALOG-033-009",
                s"procedural variable belongs to component '${owner.owner}', not '${module.owner}'",
                identity
              )
            )
          case _ =>
            AnalogProceduralRuntime.reject(
              AnalogProceduralRuntime.Diagnostic(
                "NODAL-ANALOG-033-017",
                "procedural variable is not registered in the active component"
              )
            )

  private def collectReads(
      module: ModuleState,
      value: Any
  ): Vector[AnalogProceduralRuntime.Variable] =
    val result = mutable.LinkedHashSet.empty[AnalogProceduralRuntime.Variable]

    def visit(candidate: Any): Unit = candidate match
      case iterable: Iterable[?] => iterable.foreach(visit)
      case reference: AnyRef =>
        findVariableOwner(reference) match
          case Some(owner) if owner eq module =>
            val variable = resolveVariable(module, reference)
            result += variable
          case Some(owner) =>
            val identity = registeredIdentity(owner, reference)
            AnalogProceduralRuntime.reject(
              AnalogProceduralRuntime.Diagnostic(
                "NODAL-ANALOG-033-009",
                s"procedural read belongs to component '${owner.owner}', not '${module.owner}'",
                identity
              )
            )
          case None =>
            reference match
              case expression: KernelExpr[?] => expression.operands.foreach(visit)
              case _ => ()
      case _ => ()

    visit(value)
    result.toVector

  private def valueType(
      module: ModuleState,
      expression: Expr[? <: Data],
      fallback: Option[AnalogProceduralRuntime.ValueType]
  ): AnalogProceduralRuntime.ValueType =
    val reads = collectReads(module, expression)
    val kind = CandidateRuntime
      .expressionDataType(expression)
      .map(scalarKind)
      .orElse(reads.headOption.map(_.valueType.kind))
      .orElse(fallback.map(_.kind))
      .getOrElse(AnalogProceduralRuntime.ScalarKind.Real)
    val dimension = ConstructionKernel
      .analogDimension(expression)
      .getOrElse("unknown")
    AnalogProceduralRuntime.ValueType(kind, dimension)

  private def renderValue(module: ModuleState, value: Any): String = value match
    case expression: KernelExpr[?] if expression.literal.nonEmpty =>
      val literal = expression.literal.map(_.value).getOrElse("literal")
      val unit = expression.operands.lift(1).collect { case text: String => text }
      unit.filter(_.nonEmpty).map(value => s"$literal $value").getOrElse(literal)
    case expression: KernelExpr[?] =>
      val operation = expression.operation.getOrElse("expression")
      val operands = expression.operands.map(renderValue(module, _)).mkString(",")
      s"$operation($operands)"
    case reference: AnyRef =>
      registeredIdentity(module, reference)
        .orElse(
          findVariableOwner(reference).flatMap(owner => registeredIdentity(owner, reference))
        )
        .getOrElse(reference.getClass.getSimpleName.stripSuffix("$"))
    case other => other.toString

  private def semanticValue(
      module: ModuleState,
      expression: Expr[? <: Data],
      fallback: Option[AnalogProceduralRuntime.ValueType]
  ): AnalogProceduralRuntime.Value =
    val reads = collectReads(module, expression)
    AnalogProceduralRuntime.Value(
      renderValue(module, expression),
      valueType(module, expression, fallback),
      reads
    )

  private def validateInitializer(
      variable: AnalogProceduralRuntime.Variable,
      initializer: AnalogProceduralRuntime.Value
  ): Unit =
    if !compatible(initializer.valueType.kind, variable.valueType.kind) then
      AnalogProceduralRuntime.reject(
        AnalogProceduralRuntime.Diagnostic(
          "NODAL-ANALOG-033-004",
          s"initializer kind '${initializer.valueType.kind.label}' is incompatible with '${variable.valueType.kind.label}'",
          Some(variable.identity)
        )
      )
    if initializer.valueType.dimension != variable.valueType.dimension then
      AnalogProceduralRuntime.reject(
        AnalogProceduralRuntime.Diagnostic(
          "NODAL-ANALOG-033-005",
          s"initializer dimension '${initializer.valueType.dimension}' does not match '${variable.valueType.dimension}'",
          Some(variable.identity)
        )
      )

  private def validateAssignment(
      identity: String,
      target: AnalogProceduralRuntime.Variable,
      value: AnalogProceduralRuntime.Value
  ): Unit =
    if !compatible(value.valueType.kind, target.valueType.kind) then
      AnalogProceduralRuntime.reject(
        AnalogProceduralRuntime.Diagnostic(
          "NODAL-ANALOG-033-012",
          s"assigned kind '${value.valueType.kind.label}' is incompatible with '${target.valueType.kind.label}'",
          Some(identity)
        )
      )
    if value.valueType.dimension != target.valueType.dimension then
      AnalogProceduralRuntime.reject(
        AnalogProceduralRuntime.Diagnostic(
          "NODAL-ANALOG-033-013",
          s"assigned dimension '${value.valueType.dimension}' does not match '${target.valueType.dimension}'",
          Some(identity)
        )
      )

  private def captureDeclaration(
      module: ModuleState,
      record: AnalogProceduralRuntime.VariableRecord,
      local: scala.Boolean
  ): Unit =
    module.controlBuilder.foreach: builder =>
      builder.appendDeclaration(
        AnalogControlFlowRuntime.Statement.Declare(
          s"${record.variable.identity}.declaration",
          record.variable.identity,
          record.initializer.nonEmpty,
          record.initializer.toVector.flatMap(_.reads).map(_.identity).toSet,
          local,
          record.source
        )
      )

  private def materializeWithRecorder(
      module: ModuleState,
      pending: PendingVariable
  ): Unit =
    val initialValue = pending.initializer.map: initializer =>
      semanticValue(module, initializer, None)
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
      initialValue,
      pending.source
    )
    val record = module.recorder.snapshot.variables
      .find(_.variable.identity == variable.identity)
      .getOrElse(
        scala.util.Failure[AnalogProceduralRuntime.VariableRecord](
          new IllegalStateException("procedural declaration was not retained by its recorder")
        ).get
      )
    module.variables.put(pending.value, variable)
    module.variableRecords += record
    captureDeclaration(
      module,
      record,
      local = module.controlBuilder.exists(builder => !builder.atRoot)
    )

  private def materializeWithControlFlow(
      module: ModuleState,
      pending: PendingVariable,
      builder: AnalogControlFlowConstruction.Builder
  ): Unit =
    val initialValue = pending.initializer.map: initializer =>
      semanticValue(module, initializer, None)
    val inferredDimension = initialValue
      .map(_.valueType.dimension)
      .getOrElse("dimensionless")
    val valueType = AnalogProceduralRuntime.ValueType(
      scalarKind(pending.dataType),
      inferredDimension
    )
    val identity =
      s"${module.owner}.${module.controlScope.mkString(".")}.variable_${pending.declarationOrder}"
    val variable = AnalogProceduralRuntime.Variable(
      identity,
      module.owner,
      module.controlScope,
      valueType
    )
    if valueType.kind == AnalogProceduralRuntime.ScalarKind.Boolean &&
      valueType.dimension != "dimensionless"
    then
      AnalogProceduralRuntime.reject(
        AnalogProceduralRuntime.Diagnostic(
          "NODAL-ANALOG-033-019",
          "Boolean procedural variables must be dimensionless",
          Some(identity)
        )
      )
    initialValue.foreach(validateInitializer(variable, _))
    val record = AnalogProceduralRuntime.VariableRecord(
      variable,
      initialValue,
      pending.source,
      pending.declarationOrder,
      pending.declarationOrder
    )
    module.variables.put(pending.value, variable)
    module.variableRecords += record
    captureDeclaration(module, record, local = !builder.atRoot)

  private def materializeVariables(module: ModuleState): Unit =
    module.pending.foreach: pending =>
      if !module.variables.containsKey(pending.value) then
        module.controlBuilder match
          case Some(builder) if builder.hasStructuredControl =>
            materializeWithControlFlow(module, pending, builder)
          case _ => materializeWithRecorder(module, pending)

  def registeredDimension(value: AnyRef): Option[String] =
    Option(current.get()).toVector
      .flatMap(_.modules.iterator)
      .flatMap(module => Option(module.variables.get(value)))
      .map(_.valueType.dimension)
      .headOption

  def procedure[A](body: => A): A =
    val module = activeModule
    module.procedureDepth += 1
    try
      module.recorder.procedure:
        val builder = new AnalogControlFlowConstruction.Builder(module.owner)
        module.controlBuilder = Some(builder)
        module.controlSnapshot = None
        module.controlScope = Vector("procedure")
        materializeVariables(module)
        val result = body
        val snapshot = builder.finish()
        if builder.hasStructuredControl then module.controlSnapshot = Some(snapshot)
        result
    finally
      module.controlBuilder = None
      module.controlScope = Vector.empty
      module.procedureDepth -= 1

  private def withControlScope[A](
      module: ModuleState,
      identity: String
  )(body: => A): A =
    val localIdentity = identity.stripPrefix(s"${module.owner}.")
    module.controlScope = module.controlScope :+ localIdentity
    try body
    finally module.controlScope = module.controlScope.dropRight(1)

  def lexicalScope[A](body: => A): A =
    val module = activeModule
    if module.procedureDepth == 0 then body
    else
      val builder = activeBuilder(module)
      val source = ConstructionKernel.captureAnalogProceduralSource
      if builder.hasStructuredControl then
        builder.lexicalScope(source): builderIdentity =>
          withControlScope(module, builderIdentity)(body)
      else
        val recorderIdentity = s"block_${module.scopeSerial}"
        module.scopeSerial += 1
        val stableScope = s"$recorderIdentity#${module.scopeSerial}"
        val builderIdentity =
          s"${module.owner}.${(module.controlScope :+ stableScope).mkString(".")}"
        builder.lexicalScope(source, Some(builderIdentity)): _ =>
          module.controlScope = module.controlScope :+ stableScope
          try module.recorder.scope(recorderIdentity)(body)
          finally module.controlScope = module.controlScope.dropRight(1)

  def assign[A <: Data](
      target: Variable[A],
      expression: Expr[A],
      source: Option[AnalogProceduralRuntime.Source]
  ): Unit =
    val module = activeModule
    if module.procedureDepth == 0 then
      AnalogProceduralRuntime.reject(
        AnalogProceduralRuntime.Diagnostic(
          "NODAL-ANALOG-033-008",
          "analog variable assignment requires an active procedural region"
        )
      )
    materializeVariables(module)
    val variable = resolveVariable(module, target)
    val statement = s"${module.owner}.statement_${module.statementSerial}"
    module.statementSerial += 1
    val value = semanticValue(module, expression, Some(variable.valueType))
    validateAssignment(statement, variable, value)
    val builder = activeBuilder(module)
    builder.appendAssignment(
      AnalogControlFlowRuntime.Statement.Assign(
        statement,
        variable.identity,
        value.reads.map(_.identity).toSet,
        source
      )
    )
    module.controlExpressions += AnalogProceduralRuntime.ControlExpressionRecord(
      statement,
      "assignment-value",
      value,
      source
    )
    if !builder.hasStructuredControl then
      module.recorder.assign(
        statement.stripPrefix(s"${module.owner}."),
        variable,
        value,
        source = source
      )

  private def runtimeCondition(
      module: ModuleState,
      expression: Expr[Bool],
      source: Option[AnalogProceduralRuntime.Source]
  ): AnalogControlFlowRuntime.Condition =
    val semantic = semanticValue(module, expression, None)
    AnalogControlFlowRuntime.Condition(
      semantic.rendered,
      semantic.reads.map(_.identity).toSet,
      AnalogControlFlowRuntime.Stage.Runtime,
      None,
      semantic.valueType,
      source
    )

  def conditional[A](body: => A): A =
    val module = activeModule
    activeBuilder(module).conditional(ConstructionKernel.captureAnalogProceduralSource)(body)

  def conditionalBranch[A](
      expression: Expr[Bool],
      first: scala.Boolean
  )(body: => A): A =
    val module = activeModule
    val source = ConstructionKernel.captureAnalogProceduralSource
    val value = runtimeCondition(module, expression, source)
    activeBuilder(module).conditionalBranch(value, first): identity =>
      withControlScope(module, identity)(body)

  def staticConditionalBranch[A](
      value: scala.Boolean,
      first: scala.Boolean
  )(body: => A): A =
    val module = activeModule
    val source = ConstructionKernel.captureAnalogProceduralSource
    activeBuilder(module).conditionalBranch(
      AnalogControlFlowRuntime.Condition.static(value, source = source),
      first
    ): identity =>
      withControlScope(module, identity)(body)

  def conditionalOtherwise[A](body: => A): A =
    val module = activeModule
    activeBuilder(module).conditionalOtherwise(
      ConstructionKernel.captureAnalogProceduralSource
    ): identity =>
      withControlScope(module, identity)(body)

  private def selector(
      module: ModuleState,
      expression: Expr[? <: Data],
      staticValue: Option[AnalogControlFlowRuntime.CaseLabel],
      source: Option[AnalogProceduralRuntime.Source]
  ): AnalogControlFlowRuntime.Selector = staticValue match
    case Some(AnalogControlFlowRuntime.CaseLabel.Integer(value)) =>
      AnalogControlFlowRuntime.Selector.staticInteger(value, source = source)
    case Some(AnalogControlFlowRuntime.CaseLabel.Boolean(value)) =>
      AnalogControlFlowRuntime.Selector.staticBoolean(value, source = source)
    case None =>
      val semantic = semanticValue(module, expression, None)
      AnalogControlFlowRuntime.Selector(
        semantic.rendered,
        semantic.reads.map(_.identity).toSet,
        semantic.valueType.kind,
        semantic.valueType.dimension,
        source = source
      )

  def integerCase[A](expression: Expr[Integer])(body: => A): A =
    val module = activeModule
    val source = ConstructionKernel.captureAnalogProceduralSource
    activeBuilder(module).caseSelection(selector(module, expression, None, source), source)(body)

  def booleanCase[A](expression: Expr[Bool])(body: => A): A =
    val module = activeModule
    val source = ConstructionKernel.captureAnalogProceduralSource
    activeBuilder(module).caseSelection(selector(module, expression, None, source), source)(body)

  def staticIntegerCase[A](value: Int)(body: => A): A =
    val module = activeModule
    val source = ConstructionKernel.captureAnalogProceduralSource
    activeBuilder(module).caseSelection(
      AnalogControlFlowRuntime.Selector.staticInteger(value.toLong, source = source),
      source
    )(body)

  def staticBooleanCase[A](value: scala.Boolean)(body: => A): A =
    val module = activeModule
    val source = ConstructionKernel.captureAnalogProceduralSource
    activeBuilder(module).caseSelection(
      AnalogControlFlowRuntime.Selector.staticBoolean(value, source = source),
      source
    )(body)

  def integerCaseArm[A](labels: Vector[Int])(body: => A): A =
    val module = activeModule
    val values = labels.map(value => AnalogControlFlowRuntime.CaseLabel.Integer(value.toLong))
    activeBuilder(module).caseArm(
      values,
      ConstructionKernel.captureAnalogProceduralSource
    ): identity =>
      withControlScope(module, identity)(body)

  def booleanCaseArm[A](labels: Vector[scala.Boolean])(body: => A): A =
    val module = activeModule
    val values = labels.map(value => AnalogControlFlowRuntime.CaseLabel.Boolean(value))
    activeBuilder(module).caseArm(
      values,
      ConstructionKernel.captureAnalogProceduralSource
    ): identity =>
      withControlScope(module, identity)(body)

  def caseDefault[A](body: => A): A =
    val module = activeModule
    activeBuilder(module).caseDefault(ConstructionKernel.captureAnalogProceduralSource): identity =>
      withControlScope(module, identity)(body)

  def staticLoop[A](iterations: Int)(body: => A): A =
    val module = activeModule
    val source = ConstructionKernel.captureAnalogProceduralSource
    activeBuilder(module).loop(
      AnalogControlFlowRuntime.LoopStage.Static,
      iterations,
      iterations,
      Set.empty,
      AnalogProceduralRuntime.ValueType(
        AnalogProceduralRuntime.ScalarKind.Integer,
        "dimensionless"
      ),
      Some(iterations),
      source
    ): identity =>
      withControlScope(module, identity)(body)

  def runtimeLoop[A](
      iterations: Expr[Integer],
      minimumIterations: Int,
      maximumIterations: Int
  )(body: => A): A =
    val module = activeModule
    val source = ConstructionKernel.captureAnalogProceduralSource
    val bound = semanticValue(module, iterations, None)
    activeBuilder(module).loop(
      AnalogControlFlowRuntime.LoopStage.RuntimeBounded,
      minimumIterations,
      maximumIterations,
      bound.reads.map(_.identity).toSet,
      bound.valueType,
      None,
      source
    ): identity =>
      module.controlExpressions += AnalogProceduralRuntime.ControlExpressionRecord(
        identity.stripSuffix(".body"),
        "loop-bound",
        bound,
        source
      )
      withControlScope(module, identity)(body)

  def breakStatement(): Unit =
    activeBuilder(activeModule).breakStatement(
      ConstructionKernel.captureAnalogProceduralSource
    )

  def continueStatement(): Unit =
    activeBuilder(activeModule).continueStatement(
      ConstructionKernel.captureAnalogProceduralSource
    )

  private def remapSnapshot(
      snapshot: AnalogProceduralRuntime.Snapshot,
      owner: String
  ): AnalogProceduralRuntime.Snapshot =
    val oldOwner = snapshot.owner

    def remapIdentity(identity: String): String =
      if identity == oldOwner then owner
      else if identity.startsWith(s"$oldOwner.") then
        s"$owner${identity.drop(oldOwner.length)}"
      else identity

    val variablesByIdentity =
      snapshot.variables
        .map: record =>
          val value = record.variable.copy(
            identity = remapIdentity(record.variable.identity),
            owner = owner,
            declarationScope = record.variable.declarationScope.map(remapIdentity)
          )
          record.variable.identity -> value
        .toMap

    def remapVariable(
        value: AnalogProceduralRuntime.Variable
    ): AnalogProceduralRuntime.Variable =
      variablesByIdentity.getOrElse(
        value.identity,
        value.copy(
          identity = remapIdentity(value.identity),
          owner = owner,
          declarationScope = value.declarationScope.map(remapIdentity)
        )
      )

    def remapValue(
        value: AnalogProceduralRuntime.Value
    ): AnalogProceduralRuntime.Value =
      value.copy(reads = value.reads.map(remapVariable))

    snapshot.copy(
      owner = owner,
      variables = snapshot.variables.map: record =>
        record.copy(
          variable = remapVariable(record.variable),
          initializer = record.initializer.map(remapValue)
        ),
      assignments = snapshot.assignments.map: assignment =>
        assignment.copy(
          identity = remapIdentity(assignment.identity),
          target = remapVariable(assignment.target),
          value = remapValue(assignment.value),
          guard = assignment.guard.map(remapValue)
        ),
      controlFlow = snapshot.controlFlow.map(_.remapOwner(owner)),
      controlExpressions = snapshot.controlExpressions.map: expression =>
        expression.copy(
          identity = remapIdentity(expression.identity),
          value = remapValue(expression.value)
        )
    )

  def snapshots(
      resolveOwner: Module => String
  ): Vector[AnalogProceduralRuntime.Snapshot] =
    Option(current.get()).toVector.flatMap: value =>
      value.modules.foreach(finalizeEvents)
      value.finalizedControlSnapshots = value.modules.iterator
        .flatMap(module =>
          module.controlSnapshot.map(_.remapOwner(resolveOwner(module.module)))
        )
        .toVector
      value.modules.flatMap: module =>
        val owner = resolveOwner(module.module)
        val sourceSnapshot =
          if module.controlSnapshot.nonEmpty then
            AnalogProceduralRuntime.Snapshot(
              module.owner,
              module.variableRecords.toVector,
              Vector.empty,
              module.controlSnapshot,
              module.controlExpressions.toVector
            )
          else module.recorder.snapshot
        val retained = remapSnapshot(sourceSnapshot, owner)
        Option.when(
          retained.variables.nonEmpty || retained.assignments.nonEmpty ||
            retained.controlFlow.nonEmpty || retained.controlExpressions.nonEmpty
        )(retained)

  def controlSnapshots: Vector[AnalogControlFlowConstruction.Snapshot] =
    Option(current.get()).map(_.finalizedControlSnapshots).getOrElse(Vector.empty)
