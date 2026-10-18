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

  def renderModule(snapshot: ConstructionSnapshot, owner: String): Vector[String] =
    renderModule(
      snapshot,
      owner,
      identity =>
        throw new IllegalArgumentException(
          s"procedural shaped storage requires a static-value emitter for '$identity'"
        )
    )

  def renderModule(
      snapshot: ConstructionSnapshot,
      owner: String,
      staticValue: String => (String, String)
  ): Vector[String] =
    snapshot.analogProcedural
      .zipWithIndex
      .collect:
        case (program, index) if program.owner == owner && program.generatedOwner.isEmpty =>
          renderProgram(program, index, staticValue)

  def renderGenerated(
      snapshot: ConstructionSnapshot,
      owner: String,
      generatedOwner: String,
      staticValue: String => (String, String)
  ): Vector[String] =
    snapshot.analogProcedural.zipWithIndex.collect:
      case (program, index)
          if program.owner == owner && program.generatedOwner.contains(generatedOwner) =>
        renderProgram(program, index, staticValue)

  private def generatedAttributes(
      program: AnalogProceduralRuntime.Snapshot
  ): Vector[(String, String)] =
    program.generatedOwner.toVector.map(value => "generated_owner" -> quoted(value)) ++
      program.generatedInduction.toVector.map(value =>
        "generated_induction" -> quoted(value)
      )

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
                "generated_owner" -> quoted(program.generatedOwner.getOrElse("")),
                "generated_induction" -> quoted(program.generatedInduction.getOrElse("")),
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
              ) ++ shapeFields(record.variable.valueType, "dimensions") ++
                sourceFields(record.source)
            )
          val assignments = program.assignments.sortBy(_.authoredOrder).map: record =>
            dictionary(
              Vector(
                "kind" -> quoted("assignment"),
                "identity" -> quoted(record.identity),
                "owner" -> quoted(program.owner),
                "generated_owner" -> quoted(program.generatedOwner.getOrElse("")),
                "generated_induction" -> quoted(program.generatedInduction.getOrElse("")),
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
              ) ++ shapeFields(record.value.valueType, "value_dimensions") ++
                shapeFields(record.target.valueType, "target_dimensions") ++
                Option.when(record.target.isInstanceOf[AnalogProceduralRuntime.StorageSelection])(
                  "target_root" -> quoted(record.target.root.identity)
                ).toVector ++ sourceFields(record.source)
            )
          variables ++ assignments ++ structuredInventory(program) ++
            storageOccurrences(program).map: occurrence =>
              dictionary(Vector(
                "kind" -> quoted(occurrence.kind),
                "identity" -> quoted(occurrence.path),
                "owner" -> quoted(program.owner),
                "dimensions" -> array(occurrence.dimensions.map(quoted)),
                "storage_root" -> quoted(occurrence.root.getOrElse("")),
                "authored_path" -> quoted(occurrence.authored.getOrElse(""))
              ) ++ generatedAttributes(program) ++ sourceFields(occurrence.source))
    )

  private def shapeFields(value: AnalogProceduralRuntime.ValueType, key: String)
      : Vector[(String, String)] =
    Option.when(value.dimensions.nonEmpty)(key -> array(value.dimensions.map(quoted))).toVector

  private final case class StorageOccurrence(
      kind: String,
      path: String,
      dimensions: Vector[String],
      source: Option[AnalogProceduralRuntime.Source],
      root: Option[String] = None,
      authored: Option[String] = None
  )

  /** Inventory and source maps are derived from the same statement-local producer/access payloads.
    * These are provenance records, not storage declarations or an independent view registry.
    */
  private def storageOccurrences(program: AnalogProceduralRuntime.Snapshot)
      : Vector[StorageOccurrence] =
    import AnalogProceduralRuntime.*
    val result = Vector.newBuilder[StorageOccurrence]
    val liveSources = mutable.HashSet.empty[String]
    def projection(
        projection: StorageProjection,
        path: String,
        source: Option[Source],
        root: Option[String]
    ): Unit =
      val (kind, authored, dimensions) = projection match
        case index: KernelShapeIndexSnapshot =>
          ("storage_index", index.path, index.resultDimensions)
        case view: KernelShapeViewSnapshot =>
          (
            if view.slice.nonEmpty then "storage_slice" else "storage_view",
            view.path,
            view.dimensions
          )
      result += StorageOccurrence(kind, path, dimensions, source, root, Some(authored))
    def reference(reference: StorageReference, path: String, source: Option[Source]): Unit =
      reference match
        case selection: StorageSelection => selection.projections.zipWithIndex.foreach:
            (value, index) =>
              projection(value, s"$path.projection_$index", source, Some(selection.root.identity))
        case _ => ()
    def reads(reads: Vector[StorageReference], path: String, source: Option[Source]): Unit =
      reads.distinct.zipWithIndex.foreach: (read, index) =>
        val readPath = s"$path.read_$index"
        reference(read, s"$readPath.storage", source)
        result += StorageOccurrence(
          "storage_snapshot",
          readPath,
          read.valueType.dimensions,
          source,
          Some(read.root.identity),
          Some(read.identity)
        )
    def references(
        values: Vector[StorageReference],
        path: String,
        source: Option[Source]
    ): Unit =
      values.distinct.zipWithIndex.foreach: (read, index) =>
        val referencePath = s"$path.reference_$index"
        reference(read, s"$referencePath.storage", source)
        result += StorageOccurrence(
          "storage_reference",
          referencePath,
          read.valueType.dimensions,
          source,
          Some(read.root.identity),
          Some(read.identity)
        )
    def liveOccurrences(inputs: Vector[Value], path: String, source: Option[Source]): Boolean =
      val graph = deriveLiveScalars(inputs)
      if graph.requiresSharing then
        references(inputs.flatMap(_.allReads).distinct, s"$path.live", source)
        graph.fills.zipWithIndex.foreach: (fill, index) =>
          result += StorageOccurrence(
            "live_fill",
            s"$path.live_fill_$index",
            Vector.empty,
            source,
            authored = Some(fill.identity)
          )
      graph.requiresSharing
    def eventArguments(event: AnalogEventRuntime.Expression): Vector[Value] =
      event.arguments.map(_.value) ++ event.alternatives.flatMap(eventArguments)
    def eventReferences(event: AnalogEventRuntime.Expression, path: String, shared: Boolean): Unit =
      event.arguments.foreach(argument =>
        value(argument.value, s"$path.argument_${argument.slot}.source", event.source, false)
      )
      if !shared && event.arguments.exists(argument => needsReferences(argument.value.allReads))
      then
        event.arguments.foreach: argument =>
          references(argument.value.allReads, s"$path.argument_${argument.slot}", event.source)
      event.alternatives.zipWithIndex.foreach: (child, index) =>
        eventReferences(child, s"$path.alternative_$index", shared)
    def value(
        inputValue: Value,
        path: String,
        source: Option[Source],
        captureReads: Boolean = true
    ): Unit =
      if captureReads || liveSources.add(inputValue.rendered) then
        inputValue.source match
          case Some(ShapedSource.Read(read)) =>
            if captureReads then reads(Vector(read), path, source)
          case Some(ShapedSource.Fill(input)) =>
            scalar(input, path, source, captureReads)
            result += StorageOccurrence("array_fill", path, inputValue.valueType.dimensions, source)
          case Some(ShapedSource.View(input, selected)) =>
            value(input, s"$path.input", source, captureReads)
            projection(selected, path, source, None)
          case None => scalar(inputValue, path, source, captureReads)
    def scalar(
        input: Value,
        path: String,
        source: Option[Source],
        captureReads: Boolean = true
    ): Unit =
      input.source match
        case Some(_: ShapedSource.View) => value(input, s"$path.value", source, captureReads)
        case _ =>
          if captureReads then reads(scalarReads(input), path, source)
          input.scalarSources.distinctBy(_.rendered).zipWithIndex.foreach: (leaf, index) =>
            value(leaf, s"$path.scalar_source_$index", source, captureReads)
    def assignment(
        target: StorageReference,
        input: Value,
        path: String,
        source: Option[Source]
    ): Unit =
      if target.isInstanceOf[StorageSelection] || usesStorage(input) then
        reference(target, s"$path.target", source)
        if target.valueType.dimensions.nonEmpty then value(input, s"$path.value", source)
        else scalar(input, path, source)
    program.variables.foreach: record =>
      record.initializer.filter(input =>
        record.variable.valueType.dimensions.nonEmpty || usesStorage(input)
      )
        .foreach(input => value(input, s"${record.variable.identity}.initializer", record.source))
    program.assignments.foreach(record =>
      assignment(record.target, record.value, record.identity, record.source)
    )
    val variables =
      program.variables.map(record => record.variable.identity -> record.variable).toMap
    val expressions =
      program.controlExpressions.map(record => (record.identity -> record.role) -> record).toMap
    def visit(block: AnalogControlFlowRuntime.Block): Unit = block.statements.foreach:
      case item: AnalogControlFlowRuntime.Statement.Assign =>
        expressions.get(item.identity -> "assignment-value").foreach(record =>
          assignment(
            item.storageTarget.getOrElse(variables(item.target)),
            record.value,
            item.identity,
            item.source
          )
        )
      case item: AnalogControlFlowRuntime.Statement.Read =>
        item.storageReference.filter(_.isInstanceOf[StorageSelection]).foreach(reference =>
          reads(Vector(reference), item.identity, item.source)
        )
      case item: AnalogControlFlowRuntime.Statement.EventControl =>
        liveSources.clear()
        val path = s"${item.identity}.event"
        val shared = liveOccurrences(eventArguments(item.event), path, item.source)
        eventReferences(item.event, path, shared)
        visit(item.body)
      case item: AnalogControlFlowRuntime.Statement.Scope => visit(item.body)
      case item: AnalogControlFlowRuntime.Statement.IfThenElse =>
        item.branches.zipWithIndex.foreach: (branch, index) =>
          val path = s"${item.identity}.condition_$index"
          liveSources.clear()
          branch.condition.sourceValue.foreach(input =>
            value(input, s"$path.source", branch.condition.source, false)
          )
          val shared =
            branch.condition.staticValue.isEmpty && branch.condition.sourceValue.exists(input =>
              liveOccurrences(Vector(input), path, branch.condition.source)
            )
          val selected = branch.condition.sourceValue.map(input =>
            input.allReads.distinct
          ).getOrElse(branch.condition.selectedReads)
          if !shared && needsReferences(selected) then
            references(
              selected,
              s"${item.identity}.condition_$index",
              branch.condition.source
            )
          visit(branch.body)
        item.otherwise.foreach(visit)
      case item: AnalogControlFlowRuntime.Statement.CaseStatement =>
        liveSources.clear()
        item.selector.sourceValue.foreach(input =>
          value(input, s"${item.identity}.selector.source", item.selector.source, false)
        )
        val shared = item.selector.staticValue.isEmpty && item.selector.sourceValue.exists(input =>
          liveOccurrences(
            Vector(input),
            s"${item.identity}.selector",
            item.selector.source.orElse(item.source)
          )
        )
        val selected = item.selector.sourceValue.map(input =>
          input.allReads.distinct
        ).getOrElse(item.selector.selectedReads)
        if !shared && needsReferences(selected) then
          references(
            selected,
            s"${item.identity}.selector",
            item.selector.source.orElse(item.source)
          )
        item.arms.foreach(arm => visit(arm.body)); item.default.foreach(visit)
      case item: AnalogControlFlowRuntime.Statement.Loop =>
        val bound = expressions.get(item.identity -> "loop-bound")
        liveSources.clear()
        val shared =
          item.stage == AnalogControlFlowRuntime.LoopStage.RuntimeBounded && bound.exists(record =>
            liveOccurrences(
              Vector(record.value),
              s"${item.identity}.loop-bound",
              record.source.orElse(item.source)
            )
          )
        bound.foreach(record =>
          value(record.value, s"${item.identity}.loop-bound.source", record.source, false)
        )
        val selected = bound.map(record =>
          record.value.allReads.distinct
        ).getOrElse(item.boundSelectedReads)
        if !shared && needsReferences(selected) then
          references(
            selected,
            s"${item.identity}.loop-bound",
            expressions.get(item.identity -> "loop-bound").flatMap(_.source).orElse(item.source)
          )
        visit(item.body)
      case _ => ()
    program.controlFlow.foreach(control => visit(control.root))
    result.result()

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
          val reads = if needsReferences(expression.value.allReads) then
            expression.value.allReads.distinct
          else expression.value.reads
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
              "reads" -> array(reads.map(value => quoted(value.identity)))
            ) ++ shapeFields(expression.value.valueType, "value_dimensions") ++
              sourceFields(expression.source)
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
            sourceMapEntry(
              s"${program.identityPrefix.getOrElse(program.owner)}.analogProcedural",
              source
            ),
            sourceMapEntry(
              s"${program.identityPrefix.getOrElse(program.owner)}.analogProcedure",
              source
            )
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
            sourceMapEntry(
              s"${program.identityPrefix.getOrElse(program.owner)}.${scope.mkString(".")}",
              source
            )
          )
        val variables = program.variables.flatMap(record =>
          record.source.map(sourceMapEntry(record.variable.identity, _))
        )
        val assignments = program.assignments.flatMap(record =>
          record.source.map(sourceMapEntry(record.identity, _))
        )
        val reads = program.assignments.filterNot(record =>
          record.target.isInstanceOf[AnalogProceduralRuntime.StorageSelection] ||
            usesStorage(record.value)
        ).flatMap: record =>
          record.source.toVector.flatMap: source =>
            record.value.reads.indices.map: index =>
              sourceMapEntry(s"${record.identity}.read_$index", source)
        wrappers ++ scopes ++ variables ++ assignments ++ reads ++
          structuredSourceMapEntries(program) ++ storageOccurrences(program).flatMap(occurrence =>
            occurrence.source.map(sourceMapEntry(occurrence.path, _))
          )
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
              .filterNot(expression => usesStorage(expression.value))
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

  /** A statement-local emitter; all semantic payloads come from the procedural snapshot. */
  private final class StorageEmission(
      program: AnalogProceduralRuntime.Snapshot,
      programIndex: Int,
      handles: Map[String, String],
      staticValue: String => (String, String)
  ):
    import AnalogProceduralRuntime.*
    require(
      program.variables.map(_.variable.identity).distinct.size == program.variables.size,
      "procedural storage roots must have unique identities"
    )
    private var serial = 0
    private def fresh(): String =
      val result = s"%procedural_${programIndex}_storage_$serial"
      serial += 1
      result
    private def attrs(
        path: String,
        source: Option[Source],
        extra: Vector[(String, String)] = Vector.empty
    )
        : Vector[(String, String)] =
      Vector("owner" -> quoted(program.owner)) ++ generatedAttributes(program) ++
        Vector("metadata" -> metadata(path, source, extra))
    private def ensureDimensions(dimensions: Vector[String]): Unit =
      dimensions.filter(_.toLongOption.isEmpty).distinct.foreach(staticValue)
    def replaceLeaves(text: String, bindings: Vector[(String, String)]): String =
      AnalogProceduralRuntime.replaceScalarLeaves(text, bindings)

    /** A fill is evaluated once per consuming declaration/assignment, never across consumers. */
    private final class Captures:
      val fills = mutable.HashMap.empty[String, (String, String, String)]

    private def project(
        projection: StorageProjection,
        input: String,
        inputType: String,
        inputPath: String,
        elementType: String,
        path: String,
        writable: Boolean,
        source: Option[Source]
    ): (Vector[String], String, String, String) =
      val (authored, dimensions) = projection match
        case index: KernelShapeIndexSnapshot => index.path -> index.resultDimensions
        case view: KernelShapeViewSnapshot => view.path -> view.dimensions
      ensureDimensions(dimensions)
      val result = fresh()
      val resultType = shapedType(dimensions, elementType)
      val extra = Vector(
        "input_path" -> quoted(inputPath),
        "authored_path" -> quoted(authored),
        "storage" -> quoted(if writable then "procedural" else "structural"),
        "access" -> quoted(if writable then "read_write" else "read_only")
      )
      val line = projection match
        case index: KernelShapeIndexSnapshot =>
          val indices = index.indices.map(staticValue)
          operation(
            "nodal.shape_index",
            results = Vector(result),
            operands = input +: indices.map(_._1),
            operandTypes = inputType +: indices.map(_._2),
            resultTypes = Vector(resultType),
            attributes = attrs(path, source, extra),
            source = source
          )
        case view: KernelShapeViewSnapshot if view.slice.nonEmpty =>
          val slice = view.slice.get
          val bounds =
            Vector(slice.lower, slice.upperExclusive, view.dimensions(slice.axis)).map(staticValue)
          operation(
            "nodal.shape_slice",
            results = Vector(result),
            operands = input +: bounds.map(_._1),
            operandTypes = inputType +: bounds.map(_._2),
            resultTypes = Vector(resultType),
            attributes = Vector(
              "axis" -> integer(slice.axis),
              "dimensions" -> quoted(dimensions.mkString(",")),
              "origin" -> quoted(inputPath),
              "observability" -> quoted("source_mapped")
            ) ++ attrs(path, source, extra),
            source = source
          )
        case _: KernelShapeViewSnapshot =>
          operation(
            "nodal.shape_view",
            results = Vector(result),
            operands = Vector(input),
            operandTypes = Vector(inputType),
            resultTypes = Vector(resultType),
            attributes = Vector(
              "dimensions" -> quoted(dimensions.mkString(",")),
              "origin" -> quoted(inputPath),
              "materialization" -> quoted("view"),
              "observability" -> quoted("source_mapped")
            ) ++ attrs(path, source, extra),
            source = source
          )
      (Vector(line), result, resultType, path)

    def reference(reference: StorageReference, path: String, source: Option[Source])
        : (Vector[String], String, String, String) =
      val root = reference.root
      val canonical = program.variables.find(_.variable.identity == root.identity).map(_.variable)
      require(
        canonical.contains(root) && root.owner == program.owner,
        "procedural storage reference has a forged or missing root"
      )
      AnalogStorageCoverage.normalize(reference)
      ensureDimensions(root.valueType.dimensions)
      val rootHandle = handles(root.identity)
      reference match
        case _: Variable => (Vector.empty, rootHandle, variableType(root), root.identity)
        case selection: StorageSelection =>
          val element =
            variableType(root.copy(valueType = root.valueType.copy(dimensions = Vector.empty)))
          selection.projections.zipWithIndex.foldLeft((
            Vector.empty[String],
            rootHandle,
            variableType(root),
            root.identity
          )):
            case ((lines, input, inputType, inputPath), (projection, index)) =>
              val next = project(
                projection,
                input,
                inputType,
                inputPath,
                element,
                s"$path.projection_$index",
                writable = true,
                source
              )
              (lines ++ next._1, next._2, next._3, next._4)

    def reads(values: Vector[StorageReference], path: String, source: Option[Source])
        : (Vector[String], Vector[String], Vector[String], Vector[(String, String)]) =
      val lines = Vector.newBuilder[String]
      val outputs = Vector.newBuilder[String]
      val types = Vector.newBuilder[String]
      val bindings = Vector.newBuilder[(String, String)]
      require(
        values.groupBy(_.identity).values.forall(_.distinct.size == 1),
        "one read identity cannot describe different storage references"
      )
      values.distinct.zipWithIndex.foreach: (read, index) =>
        val readPath = s"$path.read_$index"
        val storage = reference(read, s"$readPath.storage", source)
        lines ++= storage._1
        val result = fresh()
        val resultType = valueType(read.valueType)
        lines += operation(
          "nodal.analog_variable_read",
          results = Vector(result),
          operands = Vector(storage._2),
          operandTypes = Vector(storage._3),
          resultTypes = Vector(resultType),
          attributes = Vector("read_id" -> quoted(readPath)) ++ attrs(readPath, source),
          source = source
        )
        outputs += result
        types += resultType
        bindings += read.identity -> storage._4
      (lines.result(), outputs.result(), types.result(), bindings.result())

    /** Bind current scalar storage without evaluating a numeric read. Projection aliases are pure
      * and may precede their control owner; the source expression remains at its evaluation site.
      */
    def references(values: Vector[StorageReference], path: String, source: Option[Source])
        : (Vector[String], Vector[String], Vector[String], Vector[(String, String)]) =
      require(
        values.groupBy(_.identity).values.forall(_.distinct.size == 1),
        "one reference identity cannot describe different storage accesses"
      )
      val emitted = values.distinct.zipWithIndex.map: (read, index) =>
        require(read.valueType.dimensions.isEmpty, "a source reference must be scalar storage")
        val storage = reference(read, s"$path.reference_$index.storage", source)
        (storage._1, storage._2, storage._3, read.identity -> storage._4)
      (
        emitted.flatMap(_._1),
        emitted.map(_._2),
        emitted.map(_._3),
        emitted.map(_._4)
      )

    private def scalar(
        input: Value,
        path: String,
        source: Option[Source],
        captures: Captures
    ): (Vector[String], Vector[String], Vector[String], Vector[(String, String)]) =
      input.source match
        case Some(_: ShapedSource.View) =>
          val result = value(input, s"$path.value", source, captures)
          (
            result._1,
            Vector(result._2),
            Vector(result._3),
            Vector(input.rendered -> result._4)
          )
        case _ =>
          val direct = reads(scalarReads(input), path, source)
          val nested = input.scalarSources.distinctBy(_.rendered).zipWithIndex.map: (leaf, index) =>
            val result = value(leaf, s"$path.scalar_source_$index", source, captures)
            (result._1, result._2, result._3, leaf.rendered -> result._4)
          (
            direct._1 ++ nested.flatMap(_._1),
            direct._2 ++ nested.map(_._2),
            direct._3 ++ nested.map(_._3),
            direct._4 ++ nested.map(_._4)
          )

    private def value(
        value: Value,
        path: String,
        source: Option[Source],
        captures: Captures
    ): (Vector[String], String, String, String) =
      value.source match
        case Some(_: ShapedSource.Fill) =>
          captures.fills.get(value.rendered) match
            case Some((result, resultType, resultPath)) =>
              (Vector.empty, result, resultType, resultPath)
            case None =>
              val result = emitValue(value, path, source, captures)
              captures.fills(value.rendered) = (result._2, result._3, result._4)
              result
        case _ => emitValue(value, path, source, captures)

    private def emitValue(
        value: Value,
        path: String,
        source: Option[Source],
        captures: Captures
    ): (Vector[String], String, String, String) =
      ensureDimensions(value.valueType.dimensions)
      value.source match
        case Some(ShapedSource.Read(read)) =>
          require(
            read.valueType == value.valueType && value.reads.forall(_ == read),
            "snapshot type disagrees with its storage reference"
          )
          val emitted = reads(Vector(read), path, source)
          (emitted._1, emitted._2.head, emitted._3.head, s"$path.read_0")
        case Some(ShapedSource.Fill(scalar)) =>
          require(
            value.valueType.dimensions.nonEmpty && scalar.valueType.dimensions.isEmpty &&
              scalar.valueType.kind == ScalarKind.Real && value.valueType.kind == ScalarKind.Real &&
              scalar.valueType.dimension == value.valueType.dimension &&
              value.reads.forall(scalar.allReads.contains),
            "array fill must preserve its scalar Real type and physical dimension"
          )
          val emitted = this.scalar(scalar, path, source, captures)
          val result = fresh()
          val resultType = valueType(value.valueType)
          val line = operation(
            "nodal.analog_array_fill",
            results = Vector(result),
            operands = emitted._2,
            operandTypes = emitted._3,
            resultTypes = Vector(resultType),
            attributes = Vector("value" -> quoted(replaceLeaves(scalar.rendered, emitted._4))) ++
              attrs(path, source),
            source = source
          )
          (emitted._1 :+ line, result, resultType, path)
        case Some(ShapedSource.View(input, projection)) =>
          val (projectionOwner, inputPath, outputPath) = projection match
            case index: KernelShapeIndexSnapshot => (index.owner, index.input, index.path)
            case view: KernelShapeViewSnapshot => (view.owner, view.input, view.path)
          require(
            projectionOwner == program.owner && inputPath == input.rendered &&
              outputPath == value.rendered && input.valueType.dimensions.nonEmpty &&
              input.valueType.kind == value.valueType.kind &&
              input.valueType.dimension == value.valueType.dimension &&
              value.reads.forall(input.allReads.contains),
            "immutable projection must preserve its owned shaped input element type"
          )
          val checked = StorageSelection(
            Variable(
              inputPath,
              projectionOwner,
              Vector.empty,
              input.valueType
            ),
            Vector(projection)
          )
          require(
            checked.valueType == value.valueType,
            "immutable projection has inconsistent output dimensions"
          )
          AnalogStorageCoverage.normalize(checked)
          val emitted = this.value(input, s"$path.input", source, captures)
          val element = valueType(value.valueType.copy(dimensions = Vector.empty))
          val projected = project(
            projection,
            emitted._2,
            emitted._3,
            emitted._4,
            element,
            path,
            writable = false,
            source
          )
          require(
            projected._3 == valueType(value.valueType),
            "snapshot projection type is inconsistent"
          )
          (emitted._1 ++ projected._1, projected._2, projected._3, projected._4)
        case _ => throw new IllegalArgumentException("shaped value has no typed producer")

    def declaration(record: VariableRecord): Vector[String] =
      val variable = record.variable
      require(
        program.variables.count(_.variable.identity == variable.identity) == 1 &&
          variable.owner == program.owner,
        "declaration has a forged or duplicate storage root"
      )
      val scalarVariable = variable.valueType.dimensions.isEmpty
      if scalarVariable && variable.declarationScope.size <= 1 then
        // The bridge has validated the original shape chain and static root restrictions. Keep
        // persistent static initialization in the legacy form, without runtime captures/reset.
        val initializer = record.initializer.map(deriveUniformScalar(_).value)
        require(
          initializer.forall(_.allReads.isEmpty),
          "persistent scalar initialization cannot capture mutable storage"
        )
        Vector(renderDeclaration(record.copy(initializer = initializer), program, handles))
      else
        val directSnapshot = record.initializer.exists(_.source.exists {
          case ShapedSource.Read(_: StorageSelection) => true
          case _: ShapedSource.View => true
          case _ => false
        })
        val expressionForm = scalarVariable && !directSnapshot
        if scalarVariable then
          require(
            variable.declarationScope.size > 1 && record.initializer.nonEmpty &&
              record.initializer.forall(input =>
                input.valueType.dimensions.isEmpty &&
                  input.valueType.dimension == variable.valueType.dimension &&
                  (input.valueType.kind == variable.valueType.kind ||
                    variable.valueType.kind == ScalarKind.Real &&
                    input.valueType.kind == ScalarKind.Integer)
              ),
            "scalar captured initializer requires a compatible value in a local scope"
          )
        ensureDimensions(variable.valueType.dimensions)
        val captures = new Captures
        val emitted = record.initializer.map: input =>
          val path = s"${variable.identity}.initializer"
          if expressionForm then scalar(input, path, record.source, captures)
          else
            val result = value(input, path, record.source, captures)
            require(
              result._3 == valueType(variable.valueType),
              "snapshot initializer has a mismatched shape or physical dimension"
            )
            (result._1, Vector(result._2), Vector(result._3), Vector.empty[(String, String)])
        val initializer = record.initializer.filter(_ => expressionForm)
        val bindings = emitted.toVector.flatMap(_._4)
        val line = operation(
          "nodal.analog_variable",
          results = Vector(handles(variable.identity)),
          operands = emitted.toVector.flatMap(_._2),
          operandTypes = emitted.toVector.flatMap(_._3),
          resultTypes = Vector(variableType(variable)),
          attributes = Vector(
            "identity" -> quoted(variable.identity),
            "owner" -> quoted(program.owner)
          ) ++ generatedAttributes(program) ++
            Option.when(expressionForm)(
              "initializer_form" -> quoted("expression")
            ).toVector ++ Vector(
              "declaration_order" -> integer(record.declarationOrder),
              "operation_order" -> integer(record.operationOrder),
              "initialized" -> boolean(emitted.nonEmpty),
              "initializer_value" -> quoted(initializer.map(input =>
                replaceLeaves(input.rendered, bindings)
              ).getOrElse("")),
              "initializer_kind" -> quoted(initializer.map(_.valueType.kind.label).getOrElse("")),
              "initializer_dimension" -> quoted(initializer.map(input =>
                canonicalDimension(input.valueType.dimension)
              ).getOrElse("")),
              "initializer_reads" -> array(Vector.empty),
              "metadata" -> metadata(
                variable.identity,
                record.source,
                record.authoredPath.toVector.map(path => "authored_path" -> quoted(path))
              )
            ),
          source = record.source
        )
        emitted.toVector.flatMap(_._1) :+ line

    def assignment(record: AssignmentRecord): Vector[String] =
      require(
        !record.guard.exists(usesStorage),
        "assignment guards do not support shaped-storage reads in this checkpoint"
      )
      val target = reference(record.target, s"${record.identity}.target", record.source)
      val shaped = record.target.valueType.dimensions.nonEmpty
      require(
        record.target.valueType.dimensions == record.value.valueType.dimensions &&
          record.target.valueType.dimension == record.value.valueType.dimension &&
          (record.target.valueType.kind == record.value.valueType.kind ||
            !shaped && record.target.valueType.kind == ScalarKind.Real &&
            record.value.valueType.kind == ScalarKind.Integer),
        "assignment requires exact shape and physical dimension with compatible element kind"
      )
      val captures = new Captures
      val emitted = if shaped then
        val result = value(record.value, s"${record.identity}.value", record.source, captures)
        (result._1, Vector(result._2), Vector(result._3), Vector.empty[(String, String)])
      else scalar(record.value, record.identity, record.source, captures)
      val metadataFields = Vector("scope" -> array(record.scope.map(quoted))) ++
        Option.unless(shaped)("value" ->
          quoted(replaceLeaves(record.value.rendered, emitted._4))).toVector
      val attributes = Vector(
        "statement_id" -> quoted(record.identity),
        "authored_order" -> integer(record.authoredOrder),
        "operation_order" -> integer(record.operationOrder),
        "owner" -> quoted(program.owner)
      ) ++ generatedAttributes(program) ++
        Option.when(shaped)("value_form" -> quoted("snapshot")).toVector ++ Vector(
          "value_kind" -> quoted(record.value.valueType.kind.label),
          "value_dimension" -> quoted(canonicalDimension(record.value.valueType.dimension)),
          "analyses" -> array(record.analyses.sorted.map(quoted)),
          "guard_present" -> boolean(record.guard.nonEmpty),
          "guard_value" -> quoted(record.guard.map(_.rendered).getOrElse("")),
          "guard_kind" -> quoted(record.guard.map(_.valueType.kind.label).getOrElse("")),
          "guard_dimension" -> quoted(record.guard.map(value =>
            canonicalDimension(value.valueType.dimension)
          ).getOrElse("")),
          "guard_reads" ->
            array(record.guard.toVector.flatMap(_.reads).map(read => quoted(read.identity))),
          "metadata" -> metadata(record.identity, record.source, metadataFields)
        )
      val line = operation(
        "nodal.analog_assign",
        operands = target._2 +: emitted._2,
        operandTypes = target._3 +: emitted._3,
        attributes = attributes,
        source = record.source
      )
      target._1 ++ emitted._1 :+ line

  private def shapedType(dimensions: Vector[String], element: String): String =
    if dimensions.isEmpty then element
    else s"!nodal.shaped<${quoted(dimensions.mkString(","))}, $element>"

  private def needsReferences(
      reads: Vector[AnalogProceduralRuntime.StorageReference]
  ): Boolean =
    reads.exists(_.isInstanceOf[AnalogProceduralRuntime.StorageSelection])

  /** Only leaves consumed by this scalar spelling are direct captures. Reads underneath retained
    * fill sources belong to those producers, not to the enclosing expression's operand inventory.
    */
  private def scalarReads(input: AnalogProceduralRuntime.Value)
      : Vector[AnalogProceduralRuntime.StorageReference] =
    val reads = input.allReads.distinct
    val consumed = AnalogProceduralRuntime.scalarLeafOccurrences(
      input.rendered,
      reads.map(_.identity) ++ input.scalarSources.map(_.rendered)
    ).toSet
    reads.filter(read => consumed.contains(read.identity))

  private def usesStorage(value: AnalogProceduralRuntime.Value): Boolean =
    value.valueType.dimensions.nonEmpty || value.scalarSources.nonEmpty ||
      value.allReads.exists(_.isInstanceOf[AnalogProceduralRuntime.StorageSelection]) ||
      value.source.exists {
        case _: AnalogProceduralRuntime.ShapedSource.Fill => true
        case _: AnalogProceduralRuntime.ShapedSource.View => true
        case _ => false
      }

  private def renderProgram(
      program: AnalogProceduralRuntime.Snapshot,
      programIndex: Int,
      staticValue: String => (String, String)
  ): String =
    program.controlFlow match
      case Some(control) => renderStructuredProgram(program, programIndex, control, staticValue)
      case None => renderFlatProgram(program, programIndex, staticValue)

  private def renderFlatProgram(
      program: AnalogProceduralRuntime.Snapshot,
      programIndex: Int,
      staticValue: String => (String, String)
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
    val storage = new StorageEmission(program, programIndex, variableHandles, staticValue)

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
    val procedurePath = s"${program.identityPrefix.getOrElse(program.owner)}.analogProcedure"
    val procedureBody = renderScopeBody(
      root,
      program,
      programIndex,
      variableHandles,
      variableTypes,
      storage
    )
    val procedure = operation(
      "nodal.analog_procedure",
      attributes = Vector(
        "owner" -> quoted(program.owner)
      ) ++ generatedAttributes(program) ++ Vector(
        "metadata" -> metadata(procedurePath, procedureSource)
      ),
      regions = Vector(procedureBody),
      source = procedureSource
    )
    val analogPath = s"${program.identityPrefix.getOrElse(program.owner)}.analogProcedural"
    operation(
      "nodal.analog",
      attributes = generatedAttributes(program) ++ Vector(
        "metadata" -> metadata(analogPath, procedureSource)
      ),
      regions = Vector(procedure),
      source = procedureSource
    )

  private def renderStructuredProgram(
      program: AnalogProceduralRuntime.Snapshot,
      programIndex: Int,
      control: AnalogControlFlowConstruction.Snapshot,
      staticValue: String => (String, String)
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
    val storage = new StorageEmission(program, programIndex, variableHandles, staticValue)
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
        reads: Vector[AnalogProceduralRuntime.StorageReference],
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
            "owner" -> quoted(program.owner)
          ) ++ generatedAttributes(program) ++ Vector(
            "metadata" -> metadata(readPath, source)
          ),
          source = source
        )
        values += result
        types += resultType
      (lines.toVector, values.toVector, types.toVector)

    def requireScalarControl(
        reads: Set[String],
        references: Vector[AnalogProceduralRuntime.StorageReference],
        role: String
    ): Unit =
      val typed = needsReferences(references)
      require(
        references.forall(reference =>
          reference.valueType.dimensions.isEmpty &&
            variableRecords.get(reference.root.identity).exists(_.variable == reference.root) &&
            reference.owner == program.owner
        ) && references.groupBy(_.identity).values.forall(_.distinct.size == 1) &&
          (if typed then reads == references.map(_.identity).toSet
           else
             reads.forall(identity =>
               variableRecords.get(identity).exists(_.variable.valueType.dimensions.isEmpty)
             )),
        s"$role requires complete owned scalar storage references"
      )

    def controlReferences(
        reads: Set[String],
        references: Vector[AnalogProceduralRuntime.StorageReference],
        path: String,
        source: Option[AnalogProceduralRuntime.Source],
        role: String
    ): (Vector[String], Vector[String], Vector[String], Vector[(String, String)]) =
      requireScalarControl(reads, references, role)
      if needsReferences(references) then storage.references(references, path, source)
      else (Vector.empty, Vector.empty, Vector.empty, Vector.empty)

    def referenceAttributes(
        references: Vector[AnalogProceduralRuntime.StorageReference]
    ): Vector[(String, String)] =
      if needsReferences(references) then
        Vector("reference_form" -> quoted("storage")) ++ generatedAttributes(program)
      else Vector.empty

    def referenceReads(
        reads: Set[String],
        bindings: Vector[(String, String)]
    ): String =
      val labels = bindings.toMap
      array(reads.toVector.map(read => labels.getOrElse(read, read)).sorted.map(quoted))

    type References = (Vector[String], Vector[String], Vector[String], Vector[(String, String)])
    final case class LiveEmission(
        values: Vector[AnalogProceduralRuntime.Value],
        definitions: Vector[String],
        references: Vector[References]
    )
    var liveSerial = 0
    def liveEmission(
        originals: Vector[AnalogProceduralRuntime.Value],
        path: String,
        source: Option[AnalogProceduralRuntime.Source]
    ): Option[LiveEmission] =
      import AnalogProceduralRuntime.*
      val graph = deriveLiveScalars(originals)
      Option.when(graph.requiresSharing):
        val reads = originals.flatMap(_.allReads).distinct
        requireScalarControl(reads.map(_.identity).toSet, reads, "live scalar consumer")
        val aliases = storage.references(reads, s"$path.live", source)
        val labels = graph.fills.zipWithIndex.map((fill, index) =>
          fill.identity -> s"$path.live_fill_$index"
        )
        val handles = graph.fills.map: fill =>
          val result = s"%procedural_${programIndex}_live_fill_$liveSerial"
          liveSerial += 1
          val resultType =
            s"!nodal.analog_deferred<${quoted("real")}, ${quoted(canonicalDimension(fill.body.valueType.dimension))}>"
          fill.identity -> (result -> resultType)
        val bindings = aliases._4 ++ labels
        val actuals = aliases._4.map(_._1).zip(aliases._2.zip(aliases._3)).toMap ++ handles
        require(
          actuals.size == aliases._4.size + handles.size,
          "live fill and storage labels must be distinct"
        )
        def references(value: Value): References =
          val used = scalarLeafOccurrences(value.rendered, actuals.keys.toVector).distinct
          val selected = used.map(actuals)
          (Vector.empty, selected.map(_._1), selected.map(_._2), bindings)
        val definitions = graph.fills.map: fill =>
          val selected = references(fill.body)
          val result = actuals(fill.identity)
          val identity = labels.toMap.apply(fill.identity)
          operation(
            "nodal.analog_live_fill",
            results = Vector(result._1),
            operands = selected._2,
            operandTypes = selected._3,
            resultTypes = Vector(result._2),
            attributes = Vector(
              "identity" -> quoted(identity),
              "value" -> quoted(storage.replaceLeaves(fill.body.rendered, bindings)),
              "owner" -> quoted(program.owner)
            ) ++ generatedAttributes(program) ++ Vector(
              "metadata" -> metadata(
                identity,
                source,
                Vector("authored_path" -> quoted(fill.identity))
              )
            ),
            source = source
          )
        LiveEmission(graph.roots, aliases._1 ++ definitions, graph.roots.map(references))

    def liveAttributes(emitted: References): Vector[(String, String)] =
      if emitted._3.exists(_.startsWith("!nodal.analog_deferred<")) then
        Vector("reference_form" -> quoted("live")) ++ generatedAttributes(program)
      else Vector.empty

    def liveCondition(condition: AnalogControlFlowRuntime.Condition, live: Option[LiveEmission])
        : AnalogControlFlowRuntime.Condition =
      if condition.staticValue.nonEmpty then condition
      else
        condition.sourceValue.fold(condition): original =>
          val derived = live.map(
            _.values.head
          ).getOrElse(AnalogProceduralRuntime.deriveUniformScalar(original).value)
          condition.copy(
            rendered = derived.rendered,
            reads = derived.allReads.map(_.identity).toSet,
            valueType = derived.valueType,
            selectedReads = derived.allReads.distinct
          )

    def liveSelector(selector: AnalogControlFlowRuntime.Selector, live: Option[LiveEmission])
        : AnalogControlFlowRuntime.Selector =
      if selector.staticValue.nonEmpty then selector
      else
        selector.sourceValue.fold(selector): original =>
          val derived = live.map(
            _.values.head
          ).getOrElse(AnalogProceduralRuntime.deriveUniformScalar(original).value)
          selector.copy(
            rendered = derived.rendered,
            reads = derived.allReads.map(_.identity).toSet,
            kind = derived.valueType.kind,
            dimension = derived.valueType.dimension,
            selectedReads = derived.allReads.distinct
          )

    def renderEvent(
        event: AnalogEventRuntime.Expression,
        path: String,
        live: Map[String, (AnalogProceduralRuntime.Value, References)]
    ): (Vector[String], String) =
      val nested = event.alternatives.zipWithIndex.map: (child, index) =>
        renderEvent(child, s"$path.alternative_$index", live)
      val result = s"%procedural_${programIndex}_event_$eventSerial"
      eventSerial += 1
      val attrs = Vector(
        "event_id" -> quoted(path),
        "owner" -> quoted(program.owner),
        "contract" -> quoted(AnalogEventContract.Version),
        "name" -> quoted(event.name)
      ) ++ generatedAttributes(program) ++ Vector(
        "metadata" -> metadata(path, event.source)
      )
      val primitive = if event.operation == "analog_event_or" then
        Vector(operation(
          "nodal.analog_event_or",
          results = Vector(result),
          operands = nested.map(_._2),
          operandTypes = nested.map(_ => "!nodal.analog_event"),
          resultTypes = Vector("!nodal.analog_event"),
          attributes = attrs,
          source = event.source
        ))
      else
        // Structural erasure follows bridge validation. These scalar spellings remain inside
        // the monitor expression; only non-evaluating storage aliases may precede it.
        val values = event.arguments.map(argument =>
          argument.copy(value =
            live.get(s"$path.argument_${argument.slot}").map(_._1)
              .getOrElse(AnalogProceduralRuntime.deriveUniformScalar(argument.value).value)
          )
        )
        val typed =
          live.nonEmpty || values.exists(argument => needsReferences(argument.value.allReads))
        val references = values.map: argument =>
          val reads = argument.value.allReads.distinct
          require(
            argument.value.valueType.dimensions.isEmpty,
            "an analog event argument must have a scalar result"
          )
          requireScalarControl(reads.map(_.identity).toSet, reads, "analog event")
          live.get(s"$path.argument_${argument.slot}").map(_._2).getOrElse:
            if typed then
              storage.references(reads, s"$path.argument_${argument.slot}", event.source)
            else (Vector.empty, Vector.empty, Vector.empty, Vector.empty)
        val arguments = values.zip(references).map: (argument, emitted) =>
          val labels = emitted._4.toMap
          val reads = if typed then argument.value.allReads.distinct else argument.value.reads
          dictionary(Vector(
            "slot" -> integer(argument.slot),
            "value" -> quoted(storage.replaceLeaves(argument.value.rendered, emitted._4)),
            "kind" -> quoted(argument.value.valueType.kind.label),
            "dimension" -> quoted(canonicalDimension(argument.value.valueType.dimension)),
            "reads" -> array(reads.map(read =>
              quoted(labels.getOrElse(read.identity, read.identity))
            ))
          ))
        // Different slots retain their own projection provenance. A direct scalar root may appear
        // in several slots, but is supplied only once to this primitive consumer.
        val operands = references.flatMap(emitted => emitted._2.zip(emitted._3)).distinct
        val eventReads =
          if typed then
            values.zip(references).flatMap { (argument, emitted) =>
              val labels = emitted._4.toMap
              argument.value.allReads.map(read => labels.getOrElse(read.identity, read.identity))
            }.toSet
          else values.flatMap(_.value.allReads.map(_.identity)).toSet
        references.flatMap(_._1) :+ operation(
          s"nodal.${event.operation}",
          results = Vector(result),
          operands = operands.map(_._1),
          operandTypes = operands.map(_._2),
          resultTypes = Vector("!nodal.analog_event"),
          attributes = attrs ++
            Option.when(typed && operands.nonEmpty)("reference_form" -> quoted(
              if operands.exists(_._2.startsWith("!nodal.analog_deferred<")) then "live"
              else "storage"
            )).toVector ++ Vector(
              "arguments" -> array(arguments),
              "analyses" -> array(event.analyses.map(quoted)),
              "event_reads" -> array(eventReads.toVector.sorted.map(quoted))
            ),
          source = event.source
        )
      (nested.flatMap(_._1) ++ primitive, result)

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
          if record.variable.valueType.dimensions.nonEmpty then storage.declaration(record)
          else if record.initializer.exists(usesStorage) then storage.declaration(record)
          else Vector(renderDeclaration(record, program, variableHandles))

        case assignment: AnalogControlFlowRuntime.Statement.Assign =>
          val payload = expression(assignment.identity, "assignment-value")
          val order = authoredAssignmentOrder
          authoredAssignmentOrder += 1
          val target =
            assignment.storageTarget.getOrElse(variableRecords(assignment.target).variable)
          if target.isInstanceOf[AnalogProceduralRuntime.StorageSelection] ||
            usesStorage(payload.value)
          then
            storage.assignment(AnalogProceduralRuntime.AssignmentRecord(
              assignment.identity,
              target,
              payload.value,
              order,
              Vector(block.identity),
              None,
              Vector("dc", "transient"),
              assignment.source,
              order
            ))
          else
            val (readLines, readValues, readTypes) =
              renderReads(assignment.identity, payload.value.reads, assignment.source)
            val assign = operation(
              "nodal.analog_assign",
              operands = Vector(variableHandles(assignment.target)) ++ readValues,
              operandTypes = Vector(variableTypes(assignment.target)) ++ readTypes,
              attributes = Vector(
                "statement_id" -> quoted(assignment.identity),
                "authored_order" -> integer(order),
                "operation_order" -> integer(order),
                "owner" -> quoted(program.owner)
              ) ++ generatedAttributes(program) ++ Vector(
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
          if read.storageReference.exists(_.isInstanceOf[AnalogProceduralRuntime.StorageSelection])
          then
            storage.reads(read.storageReference.toVector, read.identity, read.source)._1
          else
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
                  "owner" -> quoted(program.owner)
                ) ++ generatedAttributes(program) ++ Vector(
                  "metadata" -> metadata(read.identity, read.source)
                ),
                source = read.source
              )
            )
        case event: AnalogControlFlowRuntime.Statement.EventControl =>
          val path = s"${event.identity}.event"
          def arguments(event: AnalogEventRuntime.Expression, path: String)
              : Vector[(String, AnalogProceduralRuntime.Value)] =
            event.arguments.map(argument => s"$path.argument_${argument.slot}" -> argument.value) ++
              event.alternatives.zipWithIndex.flatMap((child, index) =>
                arguments(child, s"$path.alternative_$index")
              )
          val inputs = arguments(event.event, path)
          val live = liveEmission(inputs.map(_._2), path, event.source)
          val roots =
            live.map(emitted => inputs.map(_._1).zip(emitted.values.zip(emitted.references)).toMap)
              .getOrElse(Map.empty)
          val (expressions, handle) = renderEvent(event.event, path, roots)
          live.toVector.flatMap(_.definitions) ++ expressions :+ operation(
            "nodal.analog_on",
            operands = Vector(handle),
            operandTypes = Vector("!nodal.analog_event"),
            attributes = Vector(
              "statement_id" -> quoted(event.identity),
              "owner" -> quoted(program.owner)
            ) ++ generatedAttributes(program) ++ Vector(
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
          // The if region contains arms only. Emit pure aliases before the whole group, without
          // evaluating any predicate or moving a numeric read out of its first-match arm.
          val lives = conditional.branches.zipWithIndex.map: (branch, index) =>
            if branch.condition.staticValue.nonEmpty then None
            else
              branch.condition.sourceValue.flatMap(value =>
                liveEmission(
                  Vector(value),
                  s"${conditional.identity}.condition_$index",
                  branch.condition.source
                )
              )
          val conditions = conditional.branches.zip(lives).map((branch, live) =>
            liveCondition(branch.condition, live)
          )
          val references = conditions.zipWithIndex.map: (condition, index) =>
            require(
              condition.valueType.dimensions.isEmpty &&
                (condition.stage == AnalogControlFlowRuntime.Stage.Runtime ||
                  condition.reads.isEmpty && condition.selectedReads.isEmpty),
              "an analog condition must be scalar and static conditions cannot read storage"
            )
            lives(index).map(live => live.references.head.copy(_1 = live.definitions)).getOrElse:
              controlReferences(
                condition.reads,
                condition.selectedReads,
                s"${conditional.identity}.condition_$index",
                condition.source,
                "analog condition"
              )
          val arms = conditional.branches.zipWithIndex.map: (branch, index) =>
            val condition = conditions(index)
            val emitted = references(index)
            operation(
              "nodal.analog_if_arm",
              operands = emitted._2,
              operandTypes = emitted._3,
              attributes =
                (if lives(index).nonEmpty then liveAttributes(emitted)
                 else referenceAttributes(condition.selectedReads)) ++ Vector(
                  "arm_id" -> quoted(branch.body.identity),
                  "owner" -> quoted(program.owner),
                  "is_else" -> boolean(false),
                  "stage" -> quoted(condition.stage.toString.toLowerCase),
                  "condition_value" ->
                    quoted(condition.staticValue.map(_.toString).getOrElse(
                      storage.replaceLeaves(condition.rendered, emitted._4)
                    )),
                  "condition_kind" -> quoted(condition.valueType.kind.label),
                  "condition_dimension" -> quoted(
                    canonicalDimension(condition.valueType.dimension)
                  ),
                  "condition_reads" -> referenceReads(condition.reads, emitted._4),
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
          references.flatMap(_._1) :+ operation(
            "nodal.analog_if",
            attributes = Vector(
              "statement_id" -> quoted(conditional.identity),
              "owner" -> quoted(program.owner),
              "metadata" -> metadata(conditional.identity, conditional.source)
            ),
            regions = Vector((arms ++ otherwise).mkString("\n")),
            source = conditional.source
          )

        case selection: AnalogControlFlowRuntime.Statement.CaseStatement =>
          val live = if selection.selector.staticValue.nonEmpty then None
          else
            selection.selector.sourceValue.flatMap(value =>
              liveEmission(
                Vector(value),
                s"${selection.identity}.selector",
                selection.selector.source.orElse(selection.source)
              )
            )
          val selector = liveSelector(selection.selector, live)
          require(
            selector.staticValue.isEmpty || selector.reads.isEmpty && selector.selectedReads.isEmpty,
            "a static analog selector cannot read storage"
          )
          val references = live.map(emitted =>
            emitted.references.head.copy(_1 = emitted.definitions)
          ).getOrElse(controlReferences(
            selector.reads,
            selector.selectedReads,
            s"${selection.identity}.selector",
            selector.source.orElse(selection.source),
            "analog selector"
          ))
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
          references._1 :+ operation(
            "nodal.analog_case",
            operands = references._2,
            operandTypes = references._3,
            attributes =
              (if live.nonEmpty then liveAttributes(references)
               else referenceAttributes(selector.selectedReads)) ++ Vector(
                "statement_id" -> quoted(selection.identity),
                "owner" -> quoted(program.owner),
                "selector_value" -> quoted(selector.staticValue.map(value =>
                  label(value).split(":", 2).last
                ).getOrElse(storage.replaceLeaves(selector.rendered, references._4))),
                "selector_kind" -> quoted(selector.kind.label),
                "selector_dimension" -> quoted(canonicalDimension(selector.dimension)),
                "selector_reads" -> referenceReads(selector.reads, references._4),
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

        case loop: AnalogControlFlowRuntime.Statement.Loop =>
          val stage = loop.stage match
            case AnalogControlFlowRuntime.LoopStage.Static => "static"
            case AnalogControlFlowRuntime.LoopStage.RuntimeBounded => "runtime"
          val originalBound = expressions.get(loop.identity -> "loop-bound")
          val live = if loop.stage == AnalogControlFlowRuntime.LoopStage.Static then None
          else
            originalBound.flatMap(record =>
              liveEmission(
                Vector(record.value),
                s"${loop.identity}.loop-bound",
                record.source.orElse(loop.source)
              )
            )
          val bound = originalBound.map(record =>
            record.copy(value =
              live.map(
                _.values.head
              ).getOrElse(AnalogProceduralRuntime.deriveUniformScalar(record.value).value)
            )
          )
          val boundReferences =
            bound.map(_.value.allReads.distinct).getOrElse(loop.boundSelectedReads)
          val boundReads =
            bound.map(_.value.allReads.map(_.identity).toSet).getOrElse(loop.boundReads)
          require(
            loop.boundValueType.dimensions.isEmpty &&
              bound.forall(_.value.valueType.dimensions.isEmpty) &&
              (loop.stage == AnalogControlFlowRuntime.LoopStage.RuntimeBounded ||
                boundReads.isEmpty && boundReferences.isEmpty),
            "an analog loop bound must be scalar and static bounds cannot read storage"
          )
          val references = live.map(emitted =>
            emitted.references.head.copy(_1 = emitted.definitions)
          ).getOrElse(controlReferences(
            boundReads,
            boundReferences,
            s"${loop.identity}.loop-bound",
            bound.flatMap(_.source).orElse(loop.source),
            "analog loop bound"
          ))
          references._1 :+ operation(
            "nodal.analog_loop",
            operands = references._2,
            operandTypes = references._3,
            attributes =
              (if live.nonEmpty then liveAttributes(references)
               else referenceAttributes(boundReferences)) ++ Vector(
                "statement_id" -> quoted(loop.identity),
                "owner" -> quoted(program.owner),
                "stage" -> quoted(stage),
                "minimum_iterations" -> integer(loop.minimumIterations),
                "maximum_iterations" -> integer(loop.maximumIterations),
                "bound_value" -> quoted(
                  bound.map(value => storage.replaceLeaves(value.value.rendered, references._4))
                    .getOrElse(loop.staticTripCount.map(_.toString).getOrElse(""))
                ),
                "bound_kind" -> quoted(loop.boundValueType.kind.label),
                "bound_dimension" -> quoted(
                  canonicalDimension(loop.boundValueType.dimension)
                ),
                "bound_reads" -> referenceReads(boundReads, references._4),
                "static_trip_count_present" -> boolean(loop.staticTripCount.nonEmpty),
                "static_trip_count" -> integer(loop.staticTripCount.getOrElse(0)),
                "metadata" -> metadata(loop.identity, loop.source)
              ),
            regions = Vector(renderBlock(loop.body)),
            source = loop.source
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
    val procedurePath = s"${program.identityPrefix.getOrElse(program.owner)}.analogProcedure"
    val procedure = operation(
      "nodal.analog_procedure",
      attributes = Vector(
        "owner" -> quoted(program.owner)
      ) ++ generatedAttributes(program) ++ Vector(
        "metadata" -> metadata(procedurePath, procedureSource)
      ),
      regions = Vector(renderBlock(control.root)),
      source = procedureSource
    )
    val analogPath = s"${program.identityPrefix.getOrElse(program.owner)}.analogProcedural"
    operation(
      "nodal.analog",
      attributes = generatedAttributes(program) ++ Vector(
        "metadata" -> metadata(analogPath, procedureSource)
      ),
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
      variableTypes: Map[String, String],
      storage: StorageEmission
  ): String =
    val events: Vector[Event] =
      node.declarations.toVector.map(DeclarationEvent.apply) ++
        node.assignments.toVector.map(AssignmentEvent.apply) ++
        node.children.values.toVector.map(ScopeEvent.apply)
    val sorted = orderEvents(events)

    sorted.flatMap:
      case DeclarationEvent(record) =>
        if record.variable.valueType.dimensions.nonEmpty then storage.declaration(record)
        else if record.initializer.exists(usesStorage) then storage.declaration(record)
        else Vector(renderDeclaration(record, program, variableHandles))
      case AssignmentEvent(record) =>
        if record.target.isInstanceOf[AnalogProceduralRuntime.StorageSelection] ||
          usesStorage(record.value)
        then
          storage.assignment(record)
        else renderAssignment(record, program, programIndex, variableHandles, variableTypes)
      case ScopeEvent(scope) =>
        val scopeSource = earliestSource(scope)
        val scopeId = scope.path.lastOption.getOrElse("scope")
        val scopePath =
          s"${program.identityPrefix.getOrElse(program.owner)}.${scope.path.mkString(".")}"
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
                variableTypes,
                storage
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
        "owner" -> quoted(program.owner)
      ) ++ generatedAttributes(program) ++ Vector(
        "declaration_order" -> integer(record.declarationOrder),
        "operation_order" -> integer(record.operationOrder),
        "initialized" -> boolean(initializer.nonEmpty),
        "initializer_value" ->
          quoted(initializer.map(_.rendered).getOrElse("")),
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
        "metadata" -> metadata(
          record.variable.identity,
          record.source,
          record.authoredPath.toVector.map(path => "authored_path" -> quoted(path))
        )
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
          "owner" -> quoted(program.owner)
        ) ++ generatedAttributes(program) ++ Vector(
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
        "owner" -> quoted(program.owner)
      ) ++ generatedAttributes(program) ++ Vector(
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
    shapedType(
      variable.valueType.dimensions,
      s"""!nodal.variable<${quoted(variable.valueType.kind.label)}, ${quoted(
          canonicalDimension(variable.valueType.dimension)
        )}>"""
    )

  private def valueType(value: AnalogProceduralRuntime.ValueType): String =
    val element = value.kind match
      case AnalogProceduralRuntime.ScalarKind.Boolean => "i1"
      case AnalogProceduralRuntime.ScalarKind.Integer =>
        s"""!nodal.quantity<${quoted("integer")}, ${quoted(canonicalDimension(value.dimension))}>"""
      case AnalogProceduralRuntime.ScalarKind.Real =>
        s"""!nodal.quantity<${quoted("real")}, ${quoted(canonicalDimension(value.dimension))}>"""
    shapedType(value.dimensions, element)

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
