package nodal.internal.bridge

import nodal.*

/** Pure function serialization uses typed SSA, not embedded target-language body strings. */
private[nodal] object AnalogUserFunctionMlir:
  def validate(snapshot: ConstructionSnapshot): Unit =
    snapshot.analogFunctions.foreach: record =>
      val definition = record.definition
      if !definition.name.matches("[A-Za-z_][A-Za-z0-9_]*") ||
        definition.returned < 0 || definition.returned >= definition.nodes.size ||
        definition.inputs.isEmpty
      then AnalogUserFunctionRuntime.fail(2, "invalid function snapshot declaration or return")
      definition.nodes.zipWithIndex.foreach: (node, index) =>
        if node.operands.exists(operand => operand < 0 || operand >= index) then
          AnalogUserFunctionRuntime.fail(6, "function snapshot contains an unavailable operand")
        val _ = AnalogUserFunctionRuntime.dimension(node.valueType.dimension)
      if definition.nodes(definition.returned).valueType != definition.result then
        AnalogUserFunctionRuntime.fail(4, "function snapshot return type mismatch")

  def valueType(value: AnalogUserFunctionRuntime.ValueType): String =
    if value.kind == "boolean" then "i1"
    else s"!nodal.quantity<${quote(value.kind)}, ${quote(value.dimension)}>"

  def callType(value: AnalogUserFunctionRuntime.ValueType): String =
    if value.kind == "real" then "f64" else valueType(value)

  def inventory(snapshot: ConstructionSnapshot): String =
    snapshot.analogFunctions.sortBy(value => (value.owner, value.definition.name)).map: value =>
      val definition = value.definition
      s"{owner = ${quote(value.owner)}, name = ${quote(definition.name)}, contract_version = \"1\", " +
        s"return_type = ${valueType(definition.result)}, argument_types = " +
        definition.inputs.map(node => valueType(node.valueType)).mkString("[", ", ", "]") + "}"
    .mkString("[", ", ", "]")

  def renderModule(snapshot: ConstructionSnapshot, owner: String): Vector[String] =
    val sources = snapshot.sourceMap.map(value => value.semanticPath -> value.source).toMap
    snapshot.analogFunctions.filter(_.owner == owner).sortBy(_.definition.name).map: record =>
      val definition = record.definition
      val base = s"$owner.function_${definition.name}"
      def location(path: String): String = sources.get(path) match
        case Some(source) => s" loc(${quote(source.path)}:${source.line}:${source.column})"
        case None => " loc(unknown)"
      def metadata(path: String): String = s"{semantic_path = ${quote(path)}}"
      val body = definition.nodes.zipWithIndex.map: (node, index) =>
        val path = s"$base.value_$index"
        val inputs = node.operands.map(i => s"%function_value_$i").mkString(", ")
        val inputTypes =
          node.operands.map(i => valueType(definition.nodes(i).valueType)).mkString(", ")
        val attrs = Vector("metadata" -> metadata(path)) ++
          (if node.operation == "call" then
             Vector("callee" -> ("@" + quote(node.callee)), "contract_version" -> quote("1"))
           else
             Vector("kind" -> quote(node.operation)) ++
               (if node.name.nonEmpty then Vector("name" -> quote(node.name)) else Vector.empty) ++
               (if node.operation == "input" then Vector("argument_index" -> s"$index : i64")
                else Vector.empty) ++
               (if node.operation == "math" then Vector("function_id" -> quote(node.callee))
                else Vector.empty) ++
               (if node.operation == "literal" then
                  val literal = node.valueType.kind match
                    case "real" => s"${java.lang.Double.toString(node.literal.toDouble)} : f64"
                    case "integer" => s"${node.literal} : i32"
                    case "boolean" => node.literal
                    case _ => AnalogUserFunctionRuntime.fail(3, "invalid function literal type")
                  Vector("value" -> literal)
                else Vector.empty))
        val opcode = if node.operation == "call" then "nodal.analog_user_call"
        else "nodal.analog_function_value"
        s"%function_value_$index = ${quote(opcode)}($inputs) <" +
          attrs.sortBy(_._1).map((key, value) => s"$key = $value").mkString("{", ", ", "}") +
          s"> : ($inputTypes) -> ${valueType(node.valueType)}" + location(path)
      val returned = definition.returned
      val returnPath = s"$base.return"
      val returnLine = s"\"nodal.analog_function_return\"(%function_value_$returned) " +
        s"<{metadata = ${metadata(returnPath)}}> : (${valueType(definition.nodes(returned).valueType)}) -> ()" +
        location(returnPath)
      s"\"nodal.analog_user_function\"() <{sym_name = ${quote(definition.name)}, " +
        s"return_type = ${valueType(definition.result)}, contract_version = \"1\", metadata = ${metadata(base)}}> ({\n" +
        (body :+ returnLine).map("  " + _).mkString("\n") + "\n}) : () -> ()" + location(base)

  private def quote(value: String): String =
    val escaped = value.flatMap:
      case '\\' => "\\\\"
      case '"' => "\\\""
      case character if character.isControl => f"\\${character.toInt & 0xff}%02X"
      case character => character.toString
    s"\"$escaped\""
