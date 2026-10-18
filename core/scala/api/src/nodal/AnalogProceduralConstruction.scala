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
    var procedureComplete = false
    var generatedContext: Option[(String, String)] = None
    val eventBindings = mutable.LinkedHashMap.empty[String, AnyRef]
    var eventDepth = 0
    var controlBuilder: Option[AnalogControlFlowConstruction.Builder] = None
    var controlSnapshot: Option[AnalogControlFlowConstruction.Snapshot] = None
    var controlScope: Vector[String] = Vector.empty

  private final class State:
    val stack = mutable.ArrayBuffer.empty[ModuleState]
    val modules = mutable.ArrayBuffer.empty[ModuleState]

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

  def withSession[A](body: => A): A =
    val previous = Option(current.get())
    current.set(new State)
    try body
    finally
      previous match
        case Some(value) => current.set(value)
        case None => current.remove()

  def requireContinuousContext(role: String): Unit =
    if Option(current.get()).exists(_.stack.lastOption.exists(_.eventDepth > 0)) then
      AnalogEventRuntime.fail(7, s"$role is not permitted inside an analog event-controlled body")

  def generatedProcedureActive: Boolean =
    Option(current.get()).exists(_.stack.lastOption.exists: module =>
      module.procedureDepth > 0 && module.generatedContext.nonEmpty)

  def retainsGeneratedVariable(value: AnyRef, context: (String, String)): Boolean =
    Option(current.get()).exists(_.stack.lastOption.exists: module =>
      val retained = Option(module.variables.get(value))
      module.procedureComplete && module.generatedContext.contains(context) &&
      retained.exists(variable => module.variableRecords.exists(_.variable == variable)))

  def requireDigitalEventContext(): Unit =
    requireContinuousContext("digital event process")
    if Option(current.get()).exists(_.stack.lastOption.exists(_.procedureDepth > 0)) then
      AnalogEventRuntime.fail(1, "digital event processes cannot enter an analogProcedure")

  def event(
      operation: String,
      arguments: Vector[Expr[? <: Data]],
      analyses: Vector[String] = Vector.empty
  ): Event =
    val module = activeModule
    ConstructionKernel.generatedRegionOwnership.foreach: context =>
      if module.procedureDepth == 0 || !module.generatedContext.contains(context) then
        ConstructionKernel.requireNoGeneratedEffect(s"analog event '$operation'")
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

  private def referenceToken(
      module: ModuleState,
      value: AnyRef,
      dimension: Boolean = false
  ): String =
    val prefix = if dimension then "@shape_dimension_" else "@event_reference_"
    module.eventBindings.collectFirst:
      case (token, reference) if token.startsWith(prefix) && (reference eq value) => token
    .getOrElse:
      val token = s"$prefix${module.eventBindings.size}@"
      module.eventBindings.update(token, value)
      token

  private def shapeToken(module: ModuleState, value: Any): String = value match
    case literal: Int => literal.toString
    case expression: KernelExpr[?] if expression.literal.exists(_.kind == "integer") =>
      expression.literal.get.value
    case reference: AnyRef => referenceToken(module, reference, dimension = true)
    case _ => AnalogProceduralRuntime.reject(AnalogProceduralRuntime.Diagnostic(
        "NODAL-SHAPE-043-007",
        "procedural shape requires captured static Integer values"
      ))

  private def shapeDimensions(module: ModuleState, dataType: DataType[?]): Vector[String] =
    val descriptor = CandidateRuntime.typeDescriptor(dataType)
    if descriptor.kind != "Vec" then Vector.empty
    else
      descriptor.arguments.lift(1).toVector.flatMap:
        case dimensions: Seq[?] => dimensions.map(shapeToken(module, _))
        case _ => Vector.empty

  private def projection(
      module: ModuleState,
      expression: KernelExpr[?],
      input: String
  ): KernelShapeIndexSnapshot | KernelShapeViewSnapshot =
    if ConstructionKernel.analogReferencePath(expression).isEmpty then
      AnalogProceduralRuntime.reject(AnalogProceduralRuntime.Diagnostic(
        "NODAL-SHAPE-043-007",
        "procedural projection has no captured expression identity"
      ))
    val path = referenceToken(module, expression)
    val dimensions = CandidateRuntime.expressionDataType(expression).toVector
      .flatMap(shapeDimensions(module, _))
    expression.operation match
      case Some("shape_index") => KernelShapeIndexSnapshot(
          path,
          module.owner,
          input,
          expression.operands.tail.map(shapeToken(module, _)),
          dimensions
        )
      case Some("shape_view") => KernelShapeViewSnapshot(path, module.owner, input, dimensions)
      case Some("shape_slice") => KernelShapeViewSnapshot(
          path,
          module.owner,
          input,
          dimensions,
          Some(KernelShapeSliceSnapshot(
            expression.operands(4).asInstanceOf[Int],
            shapeToken(module, expression.operands(1)),
            shapeToken(module, expression.operands(2))
          ))
        )
      case _ => AnalogProceduralRuntime.reject(AnalogProceduralRuntime.Diagnostic(
          "NODAL-SHAPE-043-007",
          "unsupported procedural shape projection"
        ))

  private def storageReference(
      module: ModuleState,
      value: Any,
      depth: Int = 0
  ): Option[AnalogProceduralRuntime.StorageReference] =
    if depth >= 512 then
      AnalogProceduralRuntime.reject(AnalogProceduralRuntime.Diagnostic(
        "NODAL-SHAPE-043-007",
        "procedural projection exceeds the supported dependency depth"
      ))
    value match
      case variable: Variable[?] => Some(resolveVariable(module, variable))
      case expression: KernelExpr[?]
          if expression.operation.exists(
            Set("shape_index", "shape_view", "shape_slice").contains
          ) =>
        expression.operands.headOption.flatMap(storageReference(module, _, depth + 1)).map: input =>
          val previous = input match
            case selection: AnalogProceduralRuntime.StorageSelection => selection.projections
            case _ => Vector.empty
          AnalogProceduralRuntime.StorageSelection(
            input.root,
            previous :+ projection(module, expression, input.identity)
          )
      case _ => None

  private def renderEventValue(module: ModuleState, value: Any): String = value match
    case expression: KernelExpr[?] if storageReference(module, expression).nonEmpty =>
      storageReference(module, expression).get.identity
    case expression: KernelExpr[?] if expression.literal.nonEmpty => renderValue(module, expression)
    case expression: KernelExpr[?] if CandidateRuntime.proceduralShapeSource(expression) =>
      referenceToken(module, expression)
    case expression: KernelExpr[?] =>
      s"${expression.operation.getOrElse("expression")}(${expression.operands.map(renderEventValue(module, _)).mkString(",")})"
    case reference: AnyRef if findVariableOwner(reference).nonEmpty =>
      resolveVariable(module, reference).identity
    case reference: AnyRef =>
      referenceToken(module, reference)
    case other => other.toString

  private def finalizeEvents(module: ModuleState): Unit =
    def resolved(value: String): String =
      module.eventBindings.foldLeft(value): (text, entry) =>
        val (token, reference) = entry
        val path = (if token.startsWith("@shape_dimension_") then
                      Some(ConstructionKernel.proceduralShapeDimension(reference))
                    else ConstructionKernel.analogReferencePath(reference)).getOrElse(
          AnalogEventRuntime.fail(5, "event expression references an unregistered declaration")
        )
        text.replace(token, path)
    module.controlSnapshot = module.controlSnapshot.map: snapshot =>
      snapshot.mapRendered(resolved)

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
    definition.arguments.zip(result.arguments).foreach: (authored, argument) =>
      requireScalarControl(authored._2, argument.value)
    def fillOccurrences(event: AnalogEventRuntime.Expression): Vector[String] =
      event.arguments.flatMap(argument =>
        AnalogProceduralRuntime.deriveUniformScalar(argument.value).fillOccurrences
      ) ++ event.alternatives.flatMap(fillOccurrences)
    requireSingleLiveFill(fillOccurrences(result))
    result.validate()
    result

  def eventControl[A](event: Event)(body: => A): A =
    val module = activeModule
    ConstructionKernel.generatedRegionOwnership.foreach: context =>
      if module.procedureDepth == 0 || !module.generatedContext.contains(context) then
        ConstructionKernel.requireNoGeneratedEffect("analog event-controlled region")
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
    ConstructionKernel.generatedRegionOwnership.foreach: context =>
      if module.procedureDepth == 0 || !module.generatedContext.contains(context) then
        ConstructionKernel.requireNoGeneratedEffect("generated analog variable declaration")
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
    case "Vec" if CandidateRuntime.typeDescriptor(dataType).arguments.headOption == Some(Real) =>
      AnalogProceduralRuntime.ScalarKind.Real
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
  ): Vector[AnalogProceduralRuntime.StorageReference] =
    val result = mutable.LinkedHashSet.empty[AnalogProceduralRuntime.StorageReference]

    def visit(candidate: Any): Unit = candidate match
      case expression: KernelExpr[?] if storageReference(module, expression).nonEmpty =>
        result += storageReference(module, expression).get
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
    AnalogProceduralRuntime.ValueType(
      kind,
      dimension,
      CandidateRuntime.expressionDataType(expression).toVector.flatMap(shapeDimensions(module, _))
    )

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
    requireProceduralSource(expression)
    val memo = new IdentityHashMap[Expr[? <: Data], AnalogProceduralRuntime.Value]()
    val active = new IdentityHashMap[Expr[? <: Data], java.lang.Boolean]()
    def capture(
        expression: Expr[? <: Data],
        fallback: Option[AnalogProceduralRuntime.ValueType],
        depth: Int
    ): AnalogProceduralRuntime.Value =
      if depth >= 512 || active.containsKey(expression) then
        AnalogProceduralRuntime.reject(AnalogProceduralRuntime.Diagnostic(
          "NODAL-SHAPE-043-007",
          "procedural scalar source is cyclic or exceeds the supported dependency depth"
        ))
      Option(memo.get(expression)).getOrElse:
        active.put(expression, java.lang.Boolean.TRUE)
        try
          val reads = collectReads(module, expression)
          val dataType = valueType(module, expression, fallback)
          val shaped = expression match
            case fill: KernelExpr[?] if fill.operation.contains("analog_array_fill") =>
              val scalar = capture(fill.operands.head.asInstanceOf[Expr[Real]], None, depth + 1)
              if scalar.valueType.dimensions.nonEmpty ||
                scalar.valueType.kind != AnalogProceduralRuntime.ScalarKind.Real
              then
                AnalogProceduralRuntime.reject(AnalogProceduralRuntime.Diagnostic(
                  "NODAL-SHAPE-043-007",
                  "array fill requires one scalar Real value"
                ))
              Some(AnalogProceduralRuntime.ShapedSource.Fill(scalar))
            case other
                if storageReference(module, other).exists(reference =>
                  reference.valueType.dimensions.nonEmpty ||
                    reference.isInstanceOf[AnalogProceduralRuntime.StorageSelection]
                ) =>
              Some(AnalogProceduralRuntime.ShapedSource.Read(storageReference(module, other).get))
            case view: KernelExpr[?] if CandidateRuntime.proceduralShapeSource(view) =>
              val input = capture(view.operands.head.asInstanceOf[Expr[Data]], None, depth + 1)
              Some(AnalogProceduralRuntime.ShapedSource.View(
                input,
                projection(module, view, input.rendered)
              ))
            case _ => None
          if dataType.dimensions.nonEmpty && shaped.isEmpty then
            AnalogProceduralRuntime.reject(AnalogProceduralRuntime.Diagnostic(
              "NODAL-SHAPE-043-007",
              "shaped procedural values require fill or an owned variable view"
            ))
          val leaves = mutable.LinkedHashMap.empty[String, AnalogProceduralRuntime.Value]
          val visited = new IdentityHashMap[KernelExpr[?], java.lang.Boolean]()
          def visit(candidate: Any, leafDepth: Int): Unit =
            if leafDepth >= 512 then
              AnalogProceduralRuntime.reject(AnalogProceduralRuntime.Diagnostic(
                "NODAL-SHAPE-043-007",
                "procedural scalar source exceeds the supported dependency depth"
              ))
            candidate match
              case leaf: KernelExpr[?] if !visited.containsKey(leaf) =>
                visited.put(leaf, java.lang.Boolean.TRUE)
                if CandidateRuntime.proceduralShapeSource(leaf) &&
                  storageReference(module, leaf).isEmpty
                then
                  val retained = capture(leaf, None, leafDepth)
                  if retained.valueType.dimensions.nonEmpty then
                    AnalogProceduralRuntime.reject(AnalogProceduralRuntime.Diagnostic(
                      "NODAL-SHAPE-043-007",
                      "a nested procedural shape source must be a scalar projection"
                    ))
                  leaves.update(retained.rendered, retained)
                else leaf.operands.foreach(visit(_, leafDepth + 1))
              case values: Iterable[?] => values.foreach(visit(_, leafDepth + 1))
              case _ => ()
          if shaped.isEmpty then
            expression match
              case authored: KernelExpr[?] => authored.operands.foreach(visit(_, depth + 1))
              case _ => ()
          val result = AnalogProceduralRuntime.Value(
            renderEventValue(module, expression),
            dataType,
            reads,
            shaped,
            leaves.values.toVector
          )
          memo.put(expression, result)
          result
        finally active.remove(expression)
    capture(expression, fallback, 0)

  /** A retained expression still executes at its consumer. Check the actual dependencies before
    * shape capture can replace a fill or projection with a reference token.
    */
  private def requireProceduralSource(expression: Expr[? <: Data]): Unit =
    val heights = new IdentityHashMap[KernelExpr[?], java.lang.Integer]()
    val active = new IdentityHashMap[KernelExpr[?], java.lang.Boolean]()
    val dependencies = Vector.newBuilder[KernelExpr[?]]
    def invalidDepth(): Nothing =
      AnalogProceduralRuntime.reject(AnalogProceduralRuntime.Diagnostic(
        "NODAL-SHAPE-043-007",
        "procedural scalar source is cyclic or exceeds the supported dependency depth"
      ))
    def visit(candidate: Any, depth: Int): Int =
      if depth >= 512 then invalidDepth()
      candidate match
        case authored: KernelExpr[?] =>
          if active.containsKey(authored) then invalidDepth()
          Option(heights.get(authored)) match
            case Some(height) =>
              if depth + height.intValue >= 512 then invalidDepth()
              height.intValue
            case None =>
              active.put(authored, java.lang.Boolean.TRUE)
              try
                if authored.operation.exists(_.startsWith(AnalogUserFunctionRuntime.CallPrefix))
                then
                  AnalogUserFunctionRuntime.fail(
                    1,
                    "analog function calls are not permitted in analog procedural expressions"
                  )
                dependencies += authored
                val height = authored.operands.foldLeft(0): (height, operand) =>
                  height.max(visit(operand, depth + 1) + 1)
                heights.put(authored, java.lang.Integer.valueOf(height))
                height
              finally active.remove(authored)
        case values: Iterable[?] =>
          values.foldLeft(0)((height, value) => height.max(visit(value, depth + 1) + 1))
        case _ => 0
    visit(expression, 0)
    ConstructionKernel.requireProceduralSources(dependencies.result())

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
    if initializer.valueType.dimensions != variable.valueType.dimensions then
      AnalogProceduralRuntime.reject(AnalogProceduralRuntime.Diagnostic(
        "NODAL-SHAPE-043-007",
        "array assignment requires exact canonical shape",
        Some(variable.identity)
      ))
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
      target: AnalogProceduralRuntime.StorageReference,
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
    if value.valueType.dimensions != target.valueType.dimensions then
      AnalogProceduralRuntime.reject(AnalogProceduralRuntime.Diagnostic(
        "NODAL-SHAPE-043-007",
        "array assignment requires exact canonical shape",
        Some(target.identity)
      ))
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
          record.initializer.toVector.flatMap(_.allReads).map(_.identity).toSet,
          local,
          record.source,
          storageVariable = Some(record.variable),
          initializerSelectedReads = record.initializer.toVector.flatMap(_.allReads)
        )
      )

  private def validateStorageInitializer(
      module: ModuleState,
      value: AnalogProceduralRuntime.Value
  ): Unit =
    val selected = value.allReads.exists(_.root.valueType.dimensions.nonEmpty)
    if module.controlBuilder.exists(_.atRoot) &&
      (selected || value.valueType.dimensions.nonEmpty && value.allReads.nonEmpty)
    then
      AnalogProceduralRuntime.reject(AnalogProceduralRuntime.Diagnostic(
        "NODAL-SHAPE-043-007",
        "persistent array initializers require a static fill without storage reads"
      ))

  private def materializeWithRecorder(
      module: ModuleState,
      pending: PendingVariable
  ): Unit =
    val initialValue = pending.initializer.map: initializer =>
      semanticValue(module, initializer, None)
    initialValue.foreach(validateStorageInitializer(module, _))
    val inferredDimension = initialValue
      .map(_.valueType.dimension)
      .getOrElse("dimensionless")
    val valueType = AnalogProceduralRuntime.ValueType(
      scalarKind(pending.dataType),
      inferredDimension,
      shapeDimensions(module, pending.dataType)
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
    initialValue.foreach(validateStorageInitializer(module, _))
    val inferredDimension = initialValue
      .map(_.valueType.dimension)
      .getOrElse("dimensionless")
    val valueType = AnalogProceduralRuntime.ValueType(
      scalarKind(pending.dataType),
      inferredDimension,
      shapeDimensions(module, pending.dataType)
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

  /** A completed procedure proves persistent, event-only storage before a continuous read. */
  def isEventHeldVariable(value: AnyRef): Boolean =
    import AnalogControlFlowRuntime.*
    def constant(expression: Any): Boolean = expression match
      case _: Param[?] => true
      case expr: KernelExpr[?] if CandidateRuntime.proceduralShapeSource(expr) =>
        expr.operands.headOption.exists(constant)
      case expr: KernelExpr[?] => expr.literal.nonEmpty ||
        ((Set("analog_add", "analog_sub", "analog_mul", "analog_div", "analog_neg")
          .contains(expr.operation.getOrElse("")) || expr.operation.exists(
          _.startsWith(AnalogFunctionRegistry.FunctionPrefix)
        )) && expr.operands.forall(constant))
      case _ => false
    Option(current.get()).exists: session =>
      session.modules.exists: module =>
        Option(module.variables.get(value)).exists: variable =>
          val initializer = module.pending.find(_.value eq value).flatMap(_.initializer)
          def writes(block: Block, controlled: Boolean): Vector[Boolean] =
            block.statements.flatMap:
              case assign: Statement.Assign if assign.target == variable.identity =>
                Vector(controlled)
              case event: Statement.EventControl => writes(event.body, true)
              case scope: Statement.Scope => writes(scope.body, controlled)
              case conditional: Statement.IfThenElse =>
                conditional.branches.flatMap(branch => writes(branch.body, controlled)) ++
                  conditional.otherwise.toVector.flatMap(writes(_, controlled))
              case selection: Statement.CaseStatement =>
                selection.arms.flatMap(arm => writes(arm.body, controlled)) ++
                  selection.default.toVector.flatMap(writes(_, controlled))
              case loop: Statement.Loop => writes(loop.body, controlled)
              case _ => Vector.empty
          module.procedureComplete && (module.module eq activeModule.module) &&
          initializer.exists(initial =>
            constant(initial) && {
              val retained = semanticValue(module, initial, None)
              retained.valueType.dimensions.isEmpty &&
              AnalogProceduralRuntime.deriveUniformScalar(retained).value.reads.isEmpty
            }
          ) && module.controlSnapshot.exists: snapshot =>
            val rootDeclaration = snapshot.root.statements.exists:
              case declaration: Statement.Declare => declaration.variable == variable.identity
              case _ => false
            val updates = writes(snapshot.root, false)
            rootDeclaration && updates.nonEmpty && updates.forall(identity)

  def procedure[A](body: => A): A =
    val module = activeModule
    val generatedContext = ConstructionKernel.generatedRegionOwnership
    generatedContext.foreach: context =>
      module.generatedContext match
        case Some(existing) if existing != context =>
          ConstructionKernel.requireNoGeneratedEffect(
            "analog procedures spanning generated ownership"
          )
        case _ => module.generatedContext = Some(context)
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
        if generatedContext.nonEmpty && module.variableRecords.isEmpty then
          ConstructionKernel.requireNoGeneratedEffect(
            "analog procedural region without generated lexical storage"
          )
        if builder.hasStructuredControl then module.controlSnapshot = Some(snapshot)
        module.procedureComplete = true
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
  ): Unit = assignSelection(target, expression, source)

  def assignSelection[A <: Data](
      target: Expr[A],
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
    val variable = storageReference(module, target).getOrElse(
      AnalogProceduralRuntime.reject(AnalogProceduralRuntime.Diagnostic(
        "NODAL-SHAPE-043-007",
        "assignment target is not an owned procedural variable"
      ))
    )
    val statement = s"${module.owner}.statement_${module.statementSerial}"
    module.statementSerial += 1
    val value = semanticValue(module, expression, Some(variable.valueType))
    validateAssignment(statement, variable, value)
    val builder = activeBuilder(module)
    builder.appendAssignment(
      AnalogControlFlowRuntime.Statement.Assign(
        statement,
        variable.root.identity,
        value.allReads.map(_.identity).toSet,
        source,
        storageTarget = Some(variable),
        selectedReads = value.allReads.distinct
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

  private def requireScalarControl(
      expression: Expr[? <: Data],
      value: AnalogProceduralRuntime.Value
  ): Unit =
    def unsupported(message: String): Nothing =
      AnalogProceduralRuntime.reject(AnalogProceduralRuntime.Diagnostic(
        "NODAL-SHAPE-043-007",
        message
      ))
    if value.valueType.dimensions.nonEmpty ||
      value.allReads.exists(_.valueType.dimensions.nonEmpty)
    then unsupported("control and event expressions require scalar storage references")
    // Derive only from retained actual leaves. The bridge independently validates every static
    // shape proof before serialization; this check admits the source form without dropping the
    // authored dependency tree or duplicating an underlying fill scalar at a live evaluation site.
    val derived = AnalogProceduralRuntime.deriveUniformScalar(value)
    requireSingleLiveFill(derived.fillOccurrences)
    val visited = new IdentityHashMap[KernelExpr[?], java.lang.Boolean]()
    def visit(candidate: Any, depth: Int): Unit = candidate match
      case authored: KernelExpr[?] if !visited.containsKey(authored) =>
        if depth >= 512 then unsupported("control expression exceeds supported dependency depth")
        visited.put(authored, java.lang.Boolean.TRUE)
        authored.operands.foreach(visit(_, depth + 1))
      case values: Iterable[?] => values.foreach(visit(_, depth + 1))
      case _ => ()
    visit(expression, 0)

  private def requireSingleLiveFill(occurrences: Vector[String]): Unit =
    if occurrences.distinct.size != occurrences.size then
      AnalogProceduralRuntime.reject(AnalogProceduralRuntime.Diagnostic(
        "NODAL-SHAPE-043-007",
        "repeated shared-fill sources in one live control or event expression are not yet supported"
      ))

  private def retainedScalarSource(
      value: AnalogProceduralRuntime.Value
  ): Option[AnalogProceduralRuntime.Value] =
    Option.when(
      value.scalarSources.nonEmpty ||
        value.source.exists(_.isInstanceOf[AnalogProceduralRuntime.ShapedSource.View])
    )(value)

  private def runtimeCondition(
      module: ModuleState,
      expression: Expr[Bool],
      source: Option[AnalogProceduralRuntime.Source]
  ): AnalogControlFlowRuntime.Condition =
    val semantic = semanticValue(module, expression, None)
    requireScalarControl(expression, semantic)
    AnalogControlFlowRuntime.Condition(
      semantic.rendered,
      semantic.allReads.map(_.identity).toSet,
      AnalogControlFlowRuntime.Stage.Runtime,
      None,
      semantic.valueType,
      source,
      selectedReads = semantic.allReads.distinct,
      sourceValue = retainedScalarSource(semantic)
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
      requireScalarControl(expression, semantic)
      AnalogControlFlowRuntime.Selector(
        semantic.rendered,
        semantic.allReads.map(_.identity).toSet,
        semantic.valueType.kind,
        semantic.valueType.dimension,
        source = source,
        selectedReads = semantic.allReads.distinct,
        sourceValue = retainedScalarSource(semantic)
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
    requireScalarControl(iterations, bound)
    activeBuilder(module).loop(
      AnalogControlFlowRuntime.LoopStage.RuntimeBounded,
      minimumIterations,
      maximumIterations,
      bound.allReads.map(_.identity).toSet,
      bound.valueType,
      None,
      source,
      boundSelectedReads = bound.allReads.distinct
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

  private def remapRendered(value: String, oldOwner: String, owner: String): String =
    val pattern =
      ("(?<![A-Za-z0-9_$])" + java.util.regex.Pattern.quote(oldOwner) +
        "(?=\\.|$)").r
    pattern.replaceAllIn(value, _ => java.util.regex.Matcher.quoteReplacement(owner))

  private def resolvePayloads(
      module: ModuleState,
      snapshot: AnalogProceduralRuntime.Snapshot
  ): AnalogProceduralRuntime.Snapshot =
    def text(value: String): String =
      module.eventBindings.foldLeft(value): (rendered, entry) =>
        val (token, reference) = entry
        val path = (if token.startsWith("@shape_dimension_") then
                      Some(ConstructionKernel.proceduralShapeDimension(reference))
                    else ConstructionKernel.analogReferencePath(reference)).getOrElse(
          AnalogEventRuntime.fail(5, "procedural expression references an unregistered declaration")
        )
        rendered.replace(token, path)
    def value(input: AnalogProceduralRuntime.Value): AnalogProceduralRuntime.Value =
      input.remap(text, text)
    snapshot.copy(
      variables = snapshot.variables.map: record =>
        val declaration = module.pending.find(pending =>
          Option(module.variables.get(pending.value)).exists(_.identity == record.variable.identity)
        )
        record.copy(
          variable = record.variable.copy(valueType =
            record.variable.valueType.copy(
              dimensions = record.variable.valueType.dimensions.map(text)
            )
          ),
          initializer = record.initializer.map(value),
          authoredPath =
            declaration.flatMap(pending => ConstructionKernel.analogReferencePath(pending.value))
        )
      ,
      assignments = snapshot.assignments.map(record =>
        record.copy(
          target = record.target.remap(text),
          value = value(record.value),
          guard = record.guard.map(value)
        )
      ),
      controlFlow = snapshot.controlFlow.map(_.mapRendered(text).mapReferences(text)),
      controlExpressions =
        snapshot.controlExpressions.map(record => record.copy(value = value(record.value)))
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
      value.remap(remapIdentity, remapRendered(_, oldOwner, owner))

    snapshot.copy(
      owner = owner,
      generatedOwner = snapshot.generatedOwner.map(remapIdentity),
      generatedInduction = snapshot.generatedInduction.map(remapIdentity),
      variables = snapshot.variables.map: record =>
        record.copy(
          variable = remapVariable(record.variable),
          initializer = record.initializer.map(remapValue)
        ),
      assignments = snapshot.assignments.map: assignment =>
        assignment.copy(
          identity = remapIdentity(assignment.identity),
          target = assignment.target.remap(remapIdentity),
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
      resolveOwner: Module => String,
      resolveGeneratedOwnership: ((String, String)) => (String, String)
  ): Vector[AnalogProceduralRuntime.Snapshot] =
    Option(current.get()).toVector.flatMap: value =>
      value.modules.foreach(finalizeEvents)
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
        val generatedContext = module.generatedContext.map(resolveGeneratedOwnership)
        val contextual = sourceSnapshot.copy(
          generatedOwner = generatedContext.map(_._1),
          generatedInduction = generatedContext.map(_._2)
        )
        val retained = remapSnapshot(resolvePayloads(module, contextual), owner)
        Option.when(
          retained.variables.nonEmpty || retained.assignments.nonEmpty ||
            retained.controlFlow.nonEmpty || retained.controlExpressions.nonEmpty
        )(retained)
