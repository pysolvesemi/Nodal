package nodal.internal.bridge

import nodal.*

import scala.collection.mutable

/** Authoritative textual-MLIR serialization for Increment 33.
  *
  * This renderer consumes the same ConstructionSnapshot used by the ordinary bridge. It does not
  * maintain a sidecar semantic model.
  */
private[nodal] object AnalogProceduralMlir:
  private final case class ScopeNode(
      path: Vector[String],
      declarations: mutable.ArrayBuffer[AnalogProceduralRuntime.VariableRecord] =
        mutable.ArrayBuffer.empty,
      assignments: mutable.ArrayBuffer[AnalogProceduralRuntime.AssignmentRecord] =
        mutable.ArrayBuffer.empty,
      children: mutable.LinkedHashMap[String, ScopeNode] = mutable.LinkedHashMap.empty
  )

  def renderModule(
      snapshot: ConstructionSnapshot,
      owner: String
  ): Vector[String] =
    snapshot.analogProcedural
      .filter(_.owner == owner)
      .sortBy(_.owner)
      .zipWithIndex
      .map((program, index) => renderProgram(program, index))

  def inventory(snapshot: ConstructionSnapshot): String =
    array(
      snapshot.analogProcedural
        .sortBy(_.owner)
        .flatMap: program =>
          val variables = program.variables.sortBy(_.declarationOrder).map: record =>
            dictionary(
              Vector(
                "kind" -> quoted("variable"),
                "identity" -> quoted(record.variable.identity),
                "owner" -> quoted(program.owner),
                "declaration_order" -> integer(record.declarationOrder),
                "operation_order" -> integer(record.operationOrder),
                "scalar_kind" -> quoted(record.variable.valueType.kind.label),
                "dimension" -> quoted(canonicalDimension(record.variable.valueType.dimension)),
                "scope" -> array(record.variable.declarationScope.map(quoted)),
                "initialized" -> boolean(record.initializer.nonEmpty),
                "initializer" -> quoted(record.initializer.map(_.rendered).getOrElse("")),
                "initializer_reads" -> array(
                  record.initializer.toVector.flatMap(_.reads).map(value =>
                    quoted(value.identity)
                  )
                )
              ) ++ sourceFields(record.source)
            )
          val assignments = program.assignments.sortBy(_.authoredOrder).map: record =>
            dictionary(
              Vector(
                "kind" -> quoted("assignment"),
                "identity" -> quoted(record.identity),
                "owner" -> quoted(program.owner),
                "authored_order" -> integer(record.authoredOrder),
                "operation_order" -> integer(record.operationOrder),
                "target" -> quoted(record.target.identity),
                "value" -> quoted(record.value.rendered),
                "value_kind" -> quoted(record.value.valueType.kind.label),
                "value_dimension" -> quoted(
                  canonicalDimension(record.value.valueType.dimension)
                ),
                "scope" -> array(record.scope.map(quoted)),
                "reads" -> array(record.value.reads.map(value => quoted(value.identity))),
                "analyses" -> array(record.analyses.sorted.map(quoted)),
                "guard" -> quoted(record.guard.map(_.rendered).getOrElse(""))
              ) ++ sourceFields(record.source)
            )
          variables ++ assignments ++ structuredInventory(program)
    )

  private def eventSources(
      event: AnalogEventRuntime.Expression,
      path: String
  ): Vector[(String, Option[AnalogProceduralRuntime.Source])] =
    Vector(path -> event.source) ++ event.alternatives.zipWithIndex.flatMap: (child, index) =>
      eventSources(child, s"$path.alternative_$index")

  private def structuredInventory(
      program: AnalogProceduralRuntime.Snapshot
  ): Vector[String] =
    program.controlFlow.toVector.flatMap: control =>
      val result = mutable.ArrayBuffer.empty[String]

      def add(
          kind: String,
          identity: String,
          source: Option[AnalogProceduralRuntime.Source]
      ): Unit =
        result += dictionary(
          Vector(
            "kind" -> quoted(kind),
            "identity" -> quoted(identity),
            "owner" -> quoted(program.owner)
          ) ++ sourceFields(source)
        )

      def visitBlock(block: AnalogControlFlowRuntime.Block): Unit =
        add("control_block", block.identity, block.source)
        block.statements.foreach:
          case value: AnalogControlFlowRuntime.Statement.Declare =>
            add("control_declaration", value.identity, value.source)
          case value: AnalogControlFlowRuntime.Statement.Assign =>
            add("control_assignment", value.identity, value.source)
          case value: AnalogControlFlowRuntime.Statement.Read =>
            add("control_read", value.identity, value.source)
          case value: AnalogControlFlowRuntime.Statement.EventControl =>
            add("control_event", value.identity, value.source)
            eventSources(value.event, s"${value.identity}.event").foreach: (path, source) =>
              add("event_expression", path, source)
            visitBlock(value.body)
          case value: AnalogControlFlowRuntime.Statement.Scope =>
            add("control_scope", value.identity, value.source)
            visitBlock(value.body)
          case value: AnalogControlFlowRuntime.Statement.IfThenElse =>
            add("control_if", value.identity, value.source)
            value.branches.zipWithIndex.foreach: (branch, index) =>
              add(
                "control_if_condition",
                s"${value.identity}.condition_$index",
                branch.condition.source
              )
              visitBlock(branch.body)
            value.otherwise.foreach(visitBlock)
          case value: AnalogControlFlowRuntime.Statement.CaseStatement =>
            add("control_case", value.identity, value.source)
            add("control_case_selector", s"${value.identity}.selector", value.selector.source)
            value.arms.foreach(arm => visitBlock(arm.body))
            value.default.foreach(visitBlock)
          case value: AnalogControlFlowRuntime.Statement.Loop =>
            add("control_loop", value.identity, value.source)
            visitBlock(value.body)
          case value: AnalogControlFlowRuntime.Statement.Break =>
            add("control_break", value.identity, value.source)
          case value: AnalogControlFlowRuntime.Statement.Continue =>
            add("control_continue", value.identity, value.source)

      visitBlock(control.root)
      program.controlExpressions
        .sortBy(value => (value.identity, value.role))
        .foreach: expression =>
          result += dictionary(
            Vector(
              "kind" -> quoted("control_expression"),
              "identity" -> quoted(expression.identity),
              "role" -> quoted(expression.role),
              "owner" -> quoted(program.owner),
              "value" -> quoted(expression.value.rendered),
              "value_kind" -> quoted(expression.value.valueType.kind.label),
              "value_dimension" -> quoted(
                canonicalDimension(expression.value.valueType.dimension)
              ),
              "reads" -> array(expression.value.reads.map(value => quoted(value.identity)))
            ) ++ sourceFields(expression.source)
          )
      result.toVector

  def sourceMapEntries(snapshot: ConstructionSnapshot): Vector[String] =
    snapshot.analogProcedural
      .sortBy(_.owner)
      .flatMap: program =>
        def canonicalScope(scope: Vector[String]): Vector[String] =
          if scope.headOption.contains("procedure") then scope
          else Vector("procedure") ++ scope

        val allSources =
          (program.variables.flatMap(_.source) ++ program.assignments.flatMap(_.source))
            .sortBy(source => (source.file, source.line, source.column))
        val wrappers = allSources.headOption.toVector.flatMap: source =>
          Vector(
            sourceMapEntry(s"${program.owner}.analogProcedural", source),
            sourceMapEntry(s"${program.owner}.analogProcedure", source)
          )
        val scopePaths =
          (program.variables.map(record => canonicalScope(record.variable.declarationScope)) ++
            program.assignments.map(record => canonicalScope(record.scope)))
            .flatMap(scope => (2 to scope.size).map(scope.take).toVector)
            .distinct
            .sortBy(_.mkString("."))
        val scopes = scopePaths.flatMap: scope =>
          val sources =
            (program.variables
              .filter(record => canonicalScope(record.variable.declarationScope).startsWith(scope))
              .flatMap(_.source) ++
              program.assignments
                .filter(record => canonicalScope(record.scope).startsWith(scope))
                .flatMap(_.source))
              .sortBy(source => (source.file, source.line, source.column))
          sources.headOption.map(source =>
            sourceMapEntry(s"${program.owner}.${scope.mkString(".")}", source)
          )
        val variables = program.variables.flatMap(record =>
          record.source.map(sourceMapEntry(record.variable.identity, _))
        )
        val assignments = program.assignments.flatMap(record =>
          record.source.map(sourceMapEntry(record.identity, _))
        )
        val reads = program.assignments.flatMap: record =>
          record.source.toVector.flatMap: source =>
            record.value.reads.indices.map: index =>
              sourceMapEntry(s"${record.identity}.read_$index", source)
        wrappers ++ scopes ++ variables ++ assignments ++ reads ++
          structuredSourceMapEntries(program)
      .distinct
      .sortBy(identity)

  private def structuredSourceMapEntries(
      program: AnalogProceduralRuntime.Snapshot
  ): Vector[String] =
    program.controlFlow.toVector.flatMap: control =>
      val entries = mutable.ArrayBuffer.empty[String]

      def add(path: String, source: Option[AnalogProceduralRuntime.Source]): Unit =
        source.foreach(value => entries += sourceMapEntry(path, value))

      def visitBlock(block: AnalogControlFlowRuntime.Block): Unit =
        add(block.identity, block.source)
        block.statements.foreach:
          case value: AnalogControlFlowRuntime.Statement.Declare =>
            add(value.identity, value.source)
          case value: AnalogControlFlowRuntime.Statement.Assign =>
            add(value.identity, value.source)
            program.controlExpressions
              .find(expression =>
                expression.identity == value.identity &&
                  expression.role == "assignment-value"
              )
              .foreach: expression =>
                expression.value.reads.indices.foreach: index =>
                  add(s"${value.identity}.read_$index", expression.source.orElse(value.source))
          case value: AnalogControlFlowRuntime.Statement.Read =>
            add(value.identity, value.source)
          case value: AnalogControlFlowRuntime.Statement.EventControl =>
            add(value.identity, value.source)
            eventSources(value.event, s"${value.identity}.event").foreach: (path, source) =>
              add(path, source)
            visitBlock(value.body)
          case value: AnalogControlFlowRuntime.Statement.Scope =>
            add(value.identity, value.source)
            visitBlock(value.body)
          case value: AnalogControlFlowRuntime.Statement.IfThenElse =>
            add(value.identity, value.source)
            value.branches.zipWithIndex.foreach: (branch, index) =>
              add(s"${value.identity}.condition_$index", branch.condition.source)
              visitBlock(branch.body)
            value.otherwise.foreach(visitBlock)
          case value: AnalogControlFlowRuntime.Statement.CaseStatement =>
            add(value.identity, value.source)
            add(s"${value.identity}.selector", value.selector.source)
            value.arms.foreach(arm => visitBlock(arm.body))
            value.default.foreach(visitBlock)
          case value: AnalogControlFlowRuntime.Statement.Loop =>
            add(value.identity, value.source)
            program.controlExpressions
              .find(expression =>
                expression.identity == value.identity && expression.role == "loop-bound"
              )
              .foreach(expression => add(s"${value.identity}.bound", expression.source))
            visitBlock(value.body)
          case value: AnalogControlFlowRuntime.Statement.Break =>
            add(value.identity, value.source)
          case value: AnalogControlFlowRuntime.Statement.Continue =>
            add(value.identity, value.source)

      visitBlock(control.root)
      entries.toVector

  private def sourceMapEntry(
      semanticPath: String,
      source: AnalogProceduralRuntime.Source
  ): String =
    dictionary(
      Vector(
        "semantic_path" -> quoted(semanticPath),
        "source_path" -> quoted(source.file),
        "source_line" -> integer(source.line),
        "source_column" -> integer(source.column),
        "source_end_line" -> integer(source.line),
        "source_end_column" -> integer(source.column)
      )
    )

  private def renderProgram(
      program: AnalogProceduralRuntime.Snapshot,
      programIndex: Int
  ): String =
    program.controlFlow match
      case Some(control) => renderStructuredProgram(program, programIndex, control)
      case None => renderFlatProgram(program, programIndex)

  private def renderFlatProgram(
      program: AnalogProceduralRuntime.Snapshot,
      programIndex: Int
  ): String =
    val root = ScopeNode(Vector("procedure"))
    val variables = program.variables.sortBy(_.declarationOrder)
    val assignments = program.assignments.sortBy(_.authoredOrder)
    val variableHandles = variables.map(record =>
      record.variable.identity -> s"%procedural_${programIndex}_variable_${record.declarationOrder}"
    ).toMap
    val variableTypes = variables.map(record =>
      record.variable.identity -> variableType(record.variable)
    ).toMap

    def nodeFor(scope: Vector[String]): ScopeNode =
      val relative =
        if scope.headOption.contains("procedure") then scope.drop(1) else scope
      relative.foldLeft(root): (node, segment) =>
        node.children.getOrElseUpdate(
          segment,
          ScopeNode(node.path :+ segment)
        )

    variables.foreach(record => nodeFor(record.variable.declarationScope).declarations += record)
    assignments.foreach(record => nodeFor(record.scope).assignments += record)

    val procedureSource = (variables.flatMap(_.source) ++ assignments.flatMap(_.source))
      .sortBy(source => (source.file, source.line, source.column))
      .headOption
    val procedurePath = s"${program.owner}.analogProcedure"
    val procedureBody = renderScopeBody(
      root,
      program,
      programIndex,
      variableHandles,
      variableTypes
    )
    val procedure = operation(
      "nodal.analog_procedure",
      attributes = Vector(
        "owner" -> quoted(program.owner),
        "metadata" -> metadata(procedurePath, procedureSource)
      ),
      regions = Vector(procedureBody),
      source = procedureSource
    )
    val analogPath = s"${program.owner}.analogProcedural"
    operation(
      "nodal.analog",
      attributes = Vector("metadata" -> metadata(analogPath, procedureSource)),
      regions = Vector(procedure),
      source = procedureSource
    )

  private def renderStructuredProgram(
      program: AnalogProceduralRuntime.Snapshot,
      programIndex: Int,
      control: AnalogControlFlowConstruction.Snapshot
  ): String =
    val variables = program.variables.sortBy(_.declarationOrder)
    val variableRecords = variables.map(record => record.variable.identity -> record).toMap
    val variableHandles = variables.map(record =>
      record.variable.identity ->
        s"%procedural_${programIndex}_variable_${record.declarationOrder}"
    ).toMap
    val variableTypes = variables.map(record =>
      record.variable.identity -> variableType(record.variable)
    ).toMap
    val expressions = program.controlExpressions
      .groupBy(value => value.identity -> value.role)
      .view
      .mapValues(_.head)
      .toMap
    var readSerial = 0
    var eventSerial = 0
    var authoredAssignmentOrder = 0

    def expression(
        identity: String,
        role: String
    ): AnalogProceduralRuntime.ControlExpressionRecord =
      expressions.getOrElse(
        identity -> role,
        scala.util.Failure[AnalogProceduralRuntime.ControlExpressionRecord](
          new IllegalArgumentException(
            s"structured analog operation '$identity' is missing '$role' payload"
          )
        ).get
      )

    def label(value: AnalogControlFlowRuntime.CaseLabel): String = value match
      case AnalogControlFlowRuntime.CaseLabel.Integer(number) => s"integer:$number"
      case AnalogControlFlowRuntime.CaseLabel.Boolean(boolean) => s"boolean:$boolean"

    def renderReads(
        statementIdentity: String,
        reads: Vector[AnalogProceduralRuntime.Variable],
        source: Option[AnalogProceduralRuntime.Source]
    ): (Vector[String], Vector[String], Vector[String]) =
      val lines = mutable.ArrayBuffer.empty[String]
      val values = mutable.ArrayBuffer.empty[String]
      val types = mutable.ArrayBuffer.empty[String]
      reads.zipWithIndex.foreach: (read, index) =>
        val result = s"%procedural_${programIndex}_structured_read_$readSerial"
        readSerial += 1
        val resultType = valueType(read.valueType)
        val readPath = s"$statementIdentity.read_$index"
        lines += operation(
          "nodal.analog_variable_read",
          results = Vector(result),
          operands = Vector(variableHandles(read.identity)),
          operandTypes = Vector(variableTypes(read.identity)),
          resultTypes = Vector(resultType),
          attributes = Vector(
            "read_id" -> quoted(readPath),
            "owner" -> quoted(program.owner),
            "metadata" -> metadata(readPath, source)
          ),
          source = source
        )
        values += result
        types += resultType
      (lines.toVector, values.toVector, types.toVector)

    def renderEvent(event: AnalogEventRuntime.Expression, path: String): (Vector[String], String) =
      val nested = event.alternatives.zipWithIndex.map: (child, index) =>
        renderEvent(child, s"$path.alternative_$index")
      val result = s"%procedural_${programIndex}_event_$eventSerial"
      eventSerial += 1
      val attrs = Vector(
        "event_id" -> quoted(path),
        "owner" -> quoted(program.owner),
        "contract" -> quoted(AnalogEventContract.Version),
        "name" -> quoted(event.name),
        "metadata" -> metadata(path, event.source)
      )
      val primitive = if event.operation == "analog_event_or" then
        operation(
          "nodal.analog_event_or",
          results = Vector(result),
          operands = nested.map(_._2),
          operandTypes = nested.map(_ => "!nodal.analog_event"),
          resultTypes = Vector("!nodal.analog_event"),
          attributes = attrs,
          source = event.source
        )
      else
        val arguments = event.arguments.map: argument =>
          dictionary(Vector(
            "slot" -> integer(argument.slot),
            "value" -> quoted(argument.value.rendered),
            "kind" -> quoted(argument.value.valueType.kind.label),
            "dimension" -> quoted(canonicalDimension(argument.value.valueType.dimension)),
            "reads" -> array(argument.value.reads.map(read => quoted(read.identity)))
          ))
        operation(
          s"nodal.${event.operation}",
          results = Vector(result),
          resultTypes = Vector("!nodal.analog_event"),
          attributes = attrs ++ Vector(
            "arguments" -> array(arguments),
            "analyses" -> array(event.analyses.map(quoted)),
            "event_reads" -> array(event.reads.toVector.sorted.map(quoted))
          ),
          source = event.source
        )
      (nested.flatMap(_._1) :+ primitive, result)

    def renderBlock(block: AnalogControlFlowRuntime.Block): String =
      block.statements.flatMap:
        case declaration: AnalogControlFlowRuntime.Statement.Declare =>
          val record = variableRecords.getOrElse(
            declaration.variable,
            scala.util.Failure[AnalogProceduralRuntime.VariableRecord](
              new IllegalArgumentException(
                s"structured declaration '${declaration.variable}' has no variable record"
              )
            ).get
          )
          Vector(renderDeclaration(record, program, variableHandles))

        case assignment: AnalogControlFlowRuntime.Statement.Assign =>
          val payload = expression(assignment.identity, "assignment-value")
          val (readLines, readValues, readTypes) =
            renderReads(assignment.identity, payload.value.reads, assignment.source)
          val order = authoredAssignmentOrder
          authoredAssignmentOrder += 1
          val assign = operation(
            "nodal.analog_assign",
            operands = Vector(variableHandles(assignment.target)) ++ readValues,
            operandTypes = Vector(variableTypes(assignment.target)) ++ readTypes,
            attributes = Vector(
              "statement_id" -> quoted(assignment.identity),
              "authored_order" -> integer(order),
              "operation_order" -> integer(order),
              "owner" -> quoted(program.owner),
              "value_kind" -> quoted(payload.value.valueType.kind.label),
              "value_dimension" -> quoted(
                canonicalDimension(payload.value.valueType.dimension)
              ),
              "analyses" -> array(Vector(quoted("dc"), quoted("transient"))),
              "guard_present" -> boolean(false),
              "guard_value" -> quoted(""),
              "guard_kind" -> quoted(""),
              "guard_dimension" -> quoted(""),
              "guard_reads" -> array(Vector.empty),
              "metadata" -> metadata(
                assignment.identity,
                assignment.source,
                Vector(
                  "scope" -> array(Vector(quoted(block.identity))),
                  "value" -> quoted(payload.value.rendered)
                )
              )
            ),
            source = assignment.source
          )
          readLines :+ assign

        case read: AnalogControlFlowRuntime.Statement.Read =>
          val variable = variableRecords.getOrElse(
            read.variable,
            scala.util.Failure[AnalogProceduralRuntime.VariableRecord](
              new IllegalArgumentException(
                s"structured read '${read.variable}' has no variable record"
              )
            ).get
          ).variable
          val result = s"%procedural_${programIndex}_structured_read_$readSerial"
          readSerial += 1
          Vector(
            operation(
              "nodal.analog_variable_read",
              results = Vector(result),
              operands = Vector(variableHandles(variable.identity)),
              operandTypes = Vector(variableTypes(variable.identity)),
              resultTypes = Vector(valueType(variable.valueType)),
              attributes = Vector(
                "read_id" -> quoted(read.identity),
                "owner" -> quoted(program.owner),
                "metadata" -> metadata(read.identity, read.source)
              ),
              source = read.source
            )
          )

        case event: AnalogControlFlowRuntime.Statement.EventControl =>
          val (expressions, handle) = renderEvent(event.event, s"${event.identity}.event")
          expressions :+ operation(
            "nodal.analog_on",
            operands = Vector(handle),
            operandTypes = Vector("!nodal.analog_event"),
            attributes = Vector(
              "statement_id" -> quoted(event.identity),
              "owner" -> quoted(program.owner),
              "metadata" -> metadata(event.identity, event.source)
            ),
            regions = Vector("^bb0:\n" + renderBlock(event.body)),
            source = event.source
          )

        case scope: AnalogControlFlowRuntime.Statement.Scope =>
          Vector(
            operation(
              "nodal.analog_scope",
              attributes = Vector(
                "scope_id" -> quoted(scope.identity),
                "owner" -> quoted(program.owner),
                "metadata" -> metadata(scope.identity, scope.source)
              ),
              regions = Vector(renderBlock(scope.body)),
              source = scope.source
            )
          )

        case conditional: AnalogControlFlowRuntime.Statement.IfThenElse =>
          val arms = conditional.branches.zipWithIndex.map: (branch, index) =>
            val condition = branch.condition
            operation(
              "nodal.analog_if_arm",
              attributes = Vector(
                "arm_id" -> quoted(branch.body.identity),
                "owner" -> quoted(program.owner),
                "is_else" -> boolean(false),
                "stage" -> quoted(condition.stage.toString.toLowerCase),
                "condition_value" -> quoted(condition.rendered),
                "condition_kind" -> quoted(condition.valueType.kind.label),
                "condition_dimension" -> quoted(
                  canonicalDimension(condition.valueType.dimension)
                ),
                "condition_reads" -> array(condition.reads.toVector.sorted.map(quoted)),
                "static_value_present" -> boolean(condition.staticValue.nonEmpty),
                "static_value" -> boolean(condition.staticValue.getOrElse(false)),
                "metadata" -> metadata(
                  s"${conditional.identity}.condition_$index",
                  condition.source
                )
              ),
              regions = Vector(renderBlock(branch.body)),
              source = condition.source.orElse(branch.body.source)
            )
          val otherwise = conditional.otherwise.toVector.map: body =>
            operation(
              "nodal.analog_if_arm",
              attributes = Vector(
                "arm_id" -> quoted(body.identity),
                "owner" -> quoted(program.owner),
                "is_else" -> boolean(true),
                "stage" -> quoted("else"),
                "condition_value" -> quoted(""),
                "condition_kind" -> quoted(""),
                "condition_dimension" -> quoted(""),
                "condition_reads" -> array(Vector.empty),
                "static_value_present" -> boolean(false),
                "static_value" -> boolean(false),
                "metadata" -> metadata(body.identity, body.source)
              ),
              regions = Vector(renderBlock(body)),
              source = body.source
            )
          Vector(
            operation(
              "nodal.analog_if",
              attributes = Vector(
                "statement_id" -> quoted(conditional.identity),
                "owner" -> quoted(program.owner),
                "metadata" -> metadata(conditional.identity, conditional.source)
              ),
              regions = Vector((arms ++ otherwise).mkString("\n")),
              source = conditional.source
            )
          )

        case selection: AnalogControlFlowRuntime.Statement.CaseStatement =>
          val selector = selection.selector
          val arms = selection.arms.map: arm =>
            operation(
              "nodal.analog_case_arm",
              attributes = Vector(
                "arm_id" -> quoted(arm.body.identity),
                "owner" -> quoted(program.owner),
                "is_default" -> boolean(false),
                "labels" -> array(arm.labels.map(value => quoted(label(value)))),
                "metadata" -> metadata(arm.body.identity, arm.body.source)
              ),
              regions = Vector(renderBlock(arm.body)),
              source = arm.body.source
            )
          val default = selection.default.toVector.map: body =>
            operation(
              "nodal.analog_case_arm",
              attributes = Vector(
                "arm_id" -> quoted(body.identity),
                "owner" -> quoted(program.owner),
                "is_default" -> boolean(true),
                "labels" -> array(Vector.empty),
                "metadata" -> metadata(body.identity, body.source)
              ),
              regions = Vector(renderBlock(body)),
              source = body.source
            )
          val staticSelector = selector.staticValue.map(label).getOrElse("")
          Vector(
            operation(
              "nodal.analog_case",
              attributes = Vector(
                "statement_id" -> quoted(selection.identity),
                "owner" -> quoted(program.owner),
                "selector_value" -> quoted(selector.rendered),
                "selector_kind" -> quoted(selector.kind.label),
                "selector_dimension" -> quoted(canonicalDimension(selector.dimension)),
                "selector_reads" -> array(selector.reads.toVector.sorted.map(quoted)),
                "static_value_present" -> boolean(selector.staticValue.nonEmpty),
                "static_value" -> quoted(staticSelector),
                "metadata" -> metadata(
                  s"${selection.identity}.selector",
                  selector.source.orElse(selection.source)
                )
              ),
              regions = Vector((arms ++ default).mkString("\n")),
              source = selection.source.orElse(selector.source)
            )
          )

        case loop: AnalogControlFlowRuntime.Statement.Loop =>
          val stage = loop.stage match
            case AnalogControlFlowRuntime.LoopStage.Static => "static"
            case AnalogControlFlowRuntime.LoopStage.RuntimeBounded => "runtime"
          val bound = expressions.get(loop.identity -> "loop-bound")
          Vector(
            operation(
              "nodal.analog_loop",
              attributes = Vector(
                "statement_id" -> quoted(loop.identity),
                "owner" -> quoted(program.owner),
                "stage" -> quoted(stage),
                "minimum_iterations" -> integer(loop.minimumIterations),
                "maximum_iterations" -> integer(loop.maximumIterations),
                "bound_value" -> quoted(
                  bound.map(_.value.rendered).getOrElse(
                    loop.staticTripCount.map(_.toString).getOrElse("")
                  )
                ),
                "bound_kind" -> quoted(loop.boundValueType.kind.label),
                "bound_dimension" -> quoted(
                  canonicalDimension(loop.boundValueType.dimension)
                ),
                "bound_reads" -> array(loop.boundReads.toVector.sorted.map(quoted)),
                "static_trip_count_present" -> boolean(loop.staticTripCount.nonEmpty),
                "static_trip_count" -> integer(loop.staticTripCount.getOrElse(0)),
                "metadata" -> metadata(loop.identity, loop.source)
              ),
              regions = Vector(renderBlock(loop.body)),
              source = loop.source
            )
          )

        case value: AnalogControlFlowRuntime.Statement.Break =>
          Vector(
            operation(
              "nodal.analog_break",
              attributes = Vector(
                "statement_id" -> quoted(value.identity),
                "owner" -> quoted(program.owner),
                "metadata" -> metadata(value.identity, value.source)
              ),
              source = value.source
            )
          )

        case value: AnalogControlFlowRuntime.Statement.Continue =>
          Vector(
            operation(
              "nodal.analog_continue",
              attributes = Vector(
                "statement_id" -> quoted(value.identity),
                "owner" -> quoted(program.owner),
                "metadata" -> metadata(value.identity, value.source)
              ),
              source = value.source
            )
          )
      .mkString("\n")

    val allSources =
      (program.variables.flatMap(_.source) ++
        program.controlExpressions.flatMap(_.source) ++
        control.root.source.toVector)
        .sortBy(value => (value.file, value.line, value.column))
    val procedureSource = allSources.headOption
    val procedurePath = s"${program.owner}.analogProcedure"
    val procedure = operation(
      "nodal.analog_procedure",
      attributes = Vector(
        "owner" -> quoted(program.owner),
        "metadata" -> metadata(procedurePath, procedureSource)
      ),
      regions = Vector(renderBlock(control.root)),
      source = procedureSource
    )
    val analogPath = s"${program.owner}.analogProcedural"
    operation(
      "nodal.analog",
      attributes = Vector("metadata" -> metadata(analogPath, procedureSource)),
      regions = Vector(procedure),
      source = procedureSource
    )

  private sealed trait Event:
    def source: Option[AnalogProceduralRuntime.Source]
    def category: Int
    def order: Int
    def identity: String

  private final case class DeclarationEvent(
      record: AnalogProceduralRuntime.VariableRecord
  ) extends Event:
    def source = record.source
    def category = 0
    def order = record.declarationOrder
    def identity = record.variable.identity

  private final case class AssignmentEvent(
      record: AnalogProceduralRuntime.AssignmentRecord
  ) extends Event:
    def source = record.source
    def category = 1
    def order = record.authoredOrder
    def identity = record.identity

  private final case class ScopeEvent(scope: ScopeNode) extends Event:
    def source = earliestSource(scope)
    def category = 2
    def order = scope.path.mkString(".").hashCode
    def identity = scope.path.mkString(".")

  private def earliestSource(
      node: ScopeNode
  ): Option[AnalogProceduralRuntime.Source] =
    val local = node.declarations.flatMap(_.source) ++ node.assignments.flatMap(_.source)
    val nested = node.children.values.flatMap(child => earliestSource(child).toVector)
    (local ++ nested)
      .sortBy(value => (value.file, value.line, value.column))
      .headOption

  private final case class OperationSpan(first: Int, last: Int)

  private def declarationOperationOrder(
      record: AnalogProceduralRuntime.VariableRecord
  ): Int =
    if record.operationOrder >= 0 then record.operationOrder
    else record.declarationOrder * 2

  private def assignmentOperationOrder(
      record: AnalogProceduralRuntime.AssignmentRecord
  ): Int =
    if record.operationOrder >= 0 then record.operationOrder
    else record.authoredOrder * 2 + 1

  private def scopeOperationOrders(node: ScopeNode): Vector[Int] =
    node.declarations.iterator.map(declarationOperationOrder).toVector ++
      node.assignments.iterator.map(assignmentOperationOrder).toVector ++
      node.children.valuesIterator.flatMap(child =>
        scopeOperationOrders(child)
      ).toVector

  private def operationSpan(event: Event): OperationSpan = event match
    case DeclarationEvent(record) =>
      val order = declarationOperationOrder(record)
      OperationSpan(order, order)
    case AssignmentEvent(record) =>
      val order = assignmentOperationOrder(record)
      OperationSpan(order, order)
    case ScopeEvent(scope) =>
      val orders = scopeOperationOrders(scope)
      require(orders.nonEmpty, "procedural lexical scope contains no operations")
      OperationSpan(orders.min, orders.max)

  private def deterministicEventKey(
      event: Event
  ): (Int, Int, Int, String, Int, Int, Int, String) =
    val span = operationSpan(event)
    event.source match
      case Some(source) =>
        (
          span.first,
          span.last,
          0,
          source.file,
          source.line,
          source.column,
          event.category,
          event.identity
        )
      case None =>
        (
          span.first,
          span.last,
          1,
          "",
          Int.MaxValue,
          Int.MaxValue,
          event.category,
          event.identity
        )

  private def orderEvents(events: Vector[Event]): Vector[Event] =
    val spans = events.map(operationSpan)
    for
      leftIndex <- events.indices
      rightIndex <- (leftIndex + 1) until events.size
    do
      val left = spans(leftIndex)
      val right = spans(rightIndex)
      require(
        left.last < right.first || right.last < left.first,
        "procedural operation ranges overlap across lexical events"
      )
    events.sortBy(deterministicEventKey)

  private def renderScopeBody(
      node: ScopeNode,
      program: AnalogProceduralRuntime.Snapshot,
      programIndex: Int,
      variableHandles: Map[String, String],
      variableTypes: Map[String, String]
  ): String =
    val events: Vector[Event] =
      node.declarations.toVector.map(DeclarationEvent.apply) ++
        node.assignments.toVector.map(AssignmentEvent.apply) ++
        node.children.values.toVector.map(ScopeEvent.apply)
    val sorted = orderEvents(events)

    sorted.flatMap:
      case DeclarationEvent(record) =>
        Vector(renderDeclaration(record, program, variableHandles))
      case AssignmentEvent(record) =>
        renderAssignment(record, program, programIndex, variableHandles, variableTypes)
      case ScopeEvent(scope) =>
        val scopeSource = earliestSource(scope)
        val scopeId = scope.path.lastOption.getOrElse("scope")
        val scopePath = s"${program.owner}.${scope.path.mkString(".")}"
        Vector(
          operation(
            "nodal.analog_scope",
            attributes = Vector(
              "scope_id" -> quoted(scopeId),
              "owner" -> quoted(program.owner),
              "metadata" -> metadata(scopePath, scopeSource)
            ),
            regions = Vector(
              renderScopeBody(
                scope,
                program,
                programIndex,
                variableHandles,
                variableTypes
              )
            ),
            source = scopeSource
          )
        )
    .mkString("\n")

  private def renderDeclaration(
      record: AnalogProceduralRuntime.VariableRecord,
      program: AnalogProceduralRuntime.Snapshot,
      variableHandles: Map[String, String]
  ): String =
    val initializer = record.initializer
    operation(
      "nodal.analog_variable",
      results = Vector(variableHandles(record.variable.identity)),
      resultTypes = Vector(variableType(record.variable)),
      attributes = Vector(
        "identity" -> quoted(record.variable.identity),
        "owner" -> quoted(program.owner),
        "declaration_order" -> integer(record.declarationOrder),
        "operation_order" -> integer(record.operationOrder),
        "initialized" -> boolean(initializer.nonEmpty),
        "initializer_value" -> quoted(initializer.map(_.rendered).getOrElse("")),
        "initializer_kind" -> quoted(
          initializer.map(_.valueType.kind.label).getOrElse("")
        ),
        "initializer_dimension" -> quoted(
          initializer
            .map(value => canonicalDimension(value.valueType.dimension))
            .getOrElse("")
        ),
        "initializer_reads" -> array(
          initializer.toVector.flatMap(_.reads).map(value => quoted(value.identity))
        ),
        "metadata" -> metadata(record.variable.identity, record.source)
      ),
      source = record.source
    )

  private def renderAssignment(
      record: AnalogProceduralRuntime.AssignmentRecord,
      program: AnalogProceduralRuntime.Snapshot,
      programIndex: Int,
      variableHandles: Map[String, String],
      variableTypes: Map[String, String]
  ): Vector[String] =
    val readLines = mutable.ArrayBuffer.empty[String]
    val readValues = mutable.ArrayBuffer.empty[String]
    val readTypes = mutable.ArrayBuffer.empty[String]
    record.value.reads.zipWithIndex.foreach: (read, index) =>
      val result = s"%procedural_${programIndex}_read_${record.authoredOrder}_$index"
      val resultType = valueType(read.valueType)
      val readPath = s"${record.identity}.read_$index"
      readLines += operation(
        "nodal.analog_variable_read",
        results = Vector(result),
        operands = Vector(variableHandles(read.identity)),
        operandTypes = Vector(variableTypes(read.identity)),
        resultTypes = Vector(resultType),
        attributes = Vector(
          "read_id" -> quoted(readPath),
          "owner" -> quoted(program.owner),
          "metadata" -> metadata(readPath, record.source)
        ),
        source = record.source
      )
      readValues += result
      readTypes += resultType

    val guard = record.guard
    val assignment = operation(
      "nodal.analog_assign",
      operands = Vector(variableHandles(record.target.identity)) ++ readValues,
      operandTypes = Vector(variableTypes(record.target.identity)) ++ readTypes,
      attributes = Vector(
        "statement_id" -> quoted(record.identity),
        "authored_order" -> integer(record.authoredOrder),
        "operation_order" -> integer(record.operationOrder),
        "owner" -> quoted(program.owner),
        "value_kind" -> quoted(record.value.valueType.kind.label),
        "value_dimension" -> quoted(
          canonicalDimension(record.value.valueType.dimension)
        ),
        "analyses" -> array(record.analyses.sorted.map(quoted)),
        "guard_present" -> boolean(guard.nonEmpty),
        "guard_value" -> quoted(guard.map(_.rendered).getOrElse("")),
        "guard_kind" -> quoted(guard.map(_.valueType.kind.label).getOrElse("")),
        "guard_dimension" -> quoted(
          guard.map(value => canonicalDimension(value.valueType.dimension)).getOrElse("")
        ),
        "guard_reads" -> array(
          guard.toVector.flatMap(_.reads).map(value => quoted(value.identity))
        ),
        "metadata" -> metadata(
          record.identity,
          record.source,
          Vector(
            "scope" -> array(record.scope.map(quoted)),
            "value" -> quoted(record.value.rendered)
          )
        )
      ),
      source = record.source
    )
    readLines.toVector :+ assignment

  private def variableType(variable: AnalogProceduralRuntime.Variable): String =
    s"""!nodal.variable<${quoted(variable.valueType.kind.label)}, ${quoted(
        canonicalDimension(variable.valueType.dimension)
      )}>"""

  private def valueType(value: AnalogProceduralRuntime.ValueType): String =
    value.kind match
      case AnalogProceduralRuntime.ScalarKind.Boolean => "i1"
      case AnalogProceduralRuntime.ScalarKind.Integer =>
        s"""!nodal.quantity<${quoted("integer")}, ${quoted(canonicalDimension(value.dimension))}>"""
      case AnalogProceduralRuntime.ScalarKind.Real =>
        s"""!nodal.quantity<${quoted("real")}, ${quoted(canonicalDimension(value.dimension))}>"""

  private def canonicalDimension(value: String): String =
    if value == "dimensionless" then "1" else value

  private def operation(
      name: String,
      results: Vector[String] = Vector.empty,
      operands: Vector[String] = Vector.empty,
      attributes: Vector[(String, String)],
      regions: Vector[String] = Vector.empty,
      operandTypes: Vector[String] = Vector.empty,
      resultTypes: Vector[String] = Vector.empty,
      source: Option[AnalogProceduralRuntime.Source]
  ): String =
    require(results.size == resultTypes.size, s"$name result arity")
    require(operands.size == operandTypes.size, s"$name operand arity")
    val resultPrefix =
      if results.isEmpty then "" else s"${results.mkString(", ")} = "
    val regionText = regions.map: region =>
      if region.isEmpty then " ({\n})"
      else s""" ({
${indent(region, 2)}
})"""
    val resultSignature = resultTypes match
      case Vector() => "()"
      case Vector(single) => single
      case many => s"(${many.mkString(", ")})"
    s"""$resultPrefix"$name"(${operands.mkString(", ")}) <${dictionary(attributes)}>""" +
      regionText.mkString +
      s" : (${operandTypes.mkString(", ")}) -> $resultSignature" +
      location(source)

  private def metadata(
      semanticPath: String,
      source: Option[AnalogProceduralRuntime.Source],
      additional: Vector[(String, String)] = Vector.empty
  ): String =
    dictionary(
      Vector("semantic_path" -> quoted(semanticPath)) ++
        source.toVector.flatMap(value =>
          Vector(
            "source_path" -> quoted(value.file),
            "source_line" -> integer(value.line),
            "source_column" -> integer(value.column),
            "source_end_line" -> integer(value.line),
            "source_end_column" -> integer(value.column)
          )
        ) ++ additional
    )

  private def sourceFields(
      source: Option[AnalogProceduralRuntime.Source]
  ): Vector[(String, String)] =
    source.toVector.flatMap(value =>
      Vector(
        "source_path" -> quoted(value.file),
        "source_line" -> integer(value.line),
        "source_column" -> integer(value.column),
        "source_end_line" -> integer(value.line),
        "source_end_column" -> integer(value.column)
      )
    )

  private def location(source: Option[AnalogProceduralRuntime.Source]): String =
    source match
      case Some(value) =>
        s""" loc(${quoted(value.file)}:${value.line}:${value.column})"""
      case None => " loc(unknown)"

  private def dictionary(entries: Iterable[(String, String)]): String =
    entries.toVector
      .sortBy(_._1)
      .map((key, value) => s"$key = $value")
      .mkString("{", ", ", "}")

  private def array(values: Iterable[String]): String =
    values.mkString("[", ", ", "]")

  private def quoted(value: String): String =
    val escaped = value.flatMap:
      case '\\' => "\\\\"
      case '"' => "\\\""
      case '\n' => "\\0A"
      case '\r' => "\\0D"
      case '\t' => "\\09"
      case character if character.isControl =>
        f"\\${character.toInt & 0xff}%02X"
      case character => character.toString
    s"\"$escaped\""

  private def integer(value: Int): String = s"$value : i64"

  private def boolean(value: Boolean): String = value.toString

  private def indent(text: String, spaces: Int): String =
    val prefix = " " * spaces
    text.linesIterator.map(line => prefix + line).mkString("\n")
