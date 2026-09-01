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
          variables ++ assignments
    )

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
        wrappers ++ scopes ++ variables ++ assignments ++ reads
      .distinct
      .sortBy(identity)

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

  private def earliestAssignmentOrder(node: ScopeNode): Option[Int] =
    val local = node.assignments.iterator.map(_.authoredOrder)
    val nested = node.children.valuesIterator.flatMap(earliestAssignmentOrder)
    (local ++ nested).minOption

  private def earliestDeclarationOrder(node: ScopeNode): Option[Int] =
    val local = node.declarations.iterator.map(_.declarationOrder)
    val nested = node.children.valuesIterator.flatMap(earliestDeclarationOrder)
    (local ++ nested).minOption

  private def authoredSequence(event: Event): (Int, Int) = event match
    case DeclarationEvent(record) => (0, record.declarationOrder)
    case AssignmentEvent(record) => (1, record.authoredOrder)
    case ScopeEvent(scope) =>
      earliestAssignmentOrder(scope)
        .map(order => (1, order))
        .orElse(earliestDeclarationOrder(scope).map(order => (0, order)))
        .getOrElse((2, Int.MaxValue))

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
    val sorted = events.sortBy: event =>
      val (phase, sequence) = authoredSequence(event)
      event.source match
        case Some(source) =>
          (
            phase,
            sequence,
            event.category,
            source.file,
            source.line,
            source.column,
            event.identity
          )
        case None =>
          (
            phase,
            sequence,
            event.category,
            "",
            Int.MaxValue,
            Int.MaxValue,
            event.identity
          )

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
