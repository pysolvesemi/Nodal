package nodal.internal.bridge

import nodal.AnalogProceduralRuntime

/** Deterministic bridge/reproducibility rendering for Increment 33 source semantics. */
private[nodal] object AnalogProceduralEvidence:
  private def escaped(value: String): String =
    val builder = new StringBuilder
    value.foreach:
      case '"' => builder.append("\\\"")
      case '\\' => builder.append("\\\\")
      case '\n' => builder.append("\\n")
      case '\r' => builder.append("\\r")
      case '\t' => builder.append("\\t")
      case character if character.isControl =>
        builder.append(f"\\u${character.toInt}%04x")
      case character => builder.append(character)
    builder.result()

  private def quoted(value: String): String = s"\"${escaped(value)}\""

  private def option(value: Option[String]): String = value.map(quoted).getOrElse("null")

  private def source(value: Option[AnalogProceduralRuntime.Source]): String = value match
    case Some(location) =>
      s"{\"file\":${quoted(location.file)},\"line\":${location.line},\"column\":${location.column}}"
    case None => "null"

  private def valueType(value: AnalogProceduralRuntime.ValueType): String =
    s"{\"kind\":${quoted(value.kind.label)},\"dimension\":${quoted(value.dimension)}}"

  private def renderedValue(value: AnalogProceduralRuntime.Value): String =
    val reads = value.reads.map(variable => quoted(variable.identity)).mkString("[", ",", "]")
    s"{\"rendered\":${quoted(value.rendered)},\"type\":${valueType(value.valueType)},\"reads\":$reads}"

  def canonicalJson(snapshots: Vector[AnalogProceduralRuntime.Snapshot]): String =
    snapshots
      .sortBy(_.owner)
      .map: snapshot =>
        val variables = snapshot.variables
          .sortBy(_.declarationOrder)
          .map: record =>
            val scope = record.variable.declarationScope.map(quoted).mkString("[", ",", "]")
            val initializer = record.initializer.map(renderedValue).getOrElse("null")
            s"{\"identity\":${quoted(record.variable.identity)},\"owner\":${quoted(record.variable.owner)},\"scope\":$scope,\"type\":${valueType(record.variable.valueType)},\"initializer\":$initializer,\"declaration_order\":${record.declarationOrder},\"source\":${source(record.source)}}"
          .mkString("[", ",", "]")
        val assignments = snapshot.assignments
          .sortBy(_.authoredOrder)
          .map: record =>
            val scope = record.scope.map(quoted).mkString("[", ",", "]")
            val analyses = record.analyses.sorted.map(quoted).mkString("[", ",", "]")
            val guard = record.guard.map(renderedValue).getOrElse("null")
            s"{\"identity\":${quoted(record.identity)},\"target\":${quoted(record.target.identity)},\"value\":${renderedValue(record.value)},\"authored_order\":${record.authoredOrder},\"scope\":$scope,\"guard\":$guard,\"analyses\":$analyses,\"source\":${source(record.source)}}"
          .mkString("[", ",", "]")
        s"{\"owner\":${quoted(snapshot.owner)},\"variables\":$variables,\"assignments\":$assignments}"
      .mkString("[", ",", "]")

  def mlirLines(snapshots: Vector[AnalogProceduralRuntime.Snapshot]): Vector[String] =
    snapshots.sortBy(_.owner).flatMap: snapshot =>
      val header =
        s"  nodal.bridge.analog_procedural owner = ${quoted(snapshot.owner)} {"
      val variables = snapshot.variables.sortBy(_.declarationOrder).map: record =>
        val initializer = record.initializer.map(_.rendered).map(quoted).getOrElse("none")
        s"    nodal.bridge.analog_variable identity = ${quoted(record.variable.identity)}, kind = ${quoted(record.variable.valueType.kind.label)}, dimension = ${quoted(record.variable.valueType.dimension)}, initializer = $initializer, declaration_order = ${record.declarationOrder}"
      val assignments = snapshot.assignments.sortBy(_.authoredOrder).map: record =>
        val guard = record.guard.map(_.rendered).map(quoted).getOrElse("none")
        val analyses = record.analyses.sorted.map(quoted).mkString("[", ", ", "]")
        s"    nodal.bridge.procedural_assign identity = ${quoted(record.identity)}, target = ${quoted(record.target.identity)}, value = ${quoted(record.value.rendered)}, kind = ${quoted(record.value.valueType.kind.label)}, dimension = ${quoted(record.value.valueType.dimension)}, authored_order = ${record.authoredOrder}, guard = $guard, analyses = $analyses"
      header +: (variables ++ assignments :+ "  }")

  def mlirText(snapshots: Vector[AnalogProceduralRuntime.Snapshot]): String =
    val lines = mlirLines(snapshots)
    if lines.isEmpty then "" else lines.mkString("\n", "\n", "\n")
