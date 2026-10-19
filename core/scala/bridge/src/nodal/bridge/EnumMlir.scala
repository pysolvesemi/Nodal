package nodal.internal.bridge

import nodal.*

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** Validates the immutable source boundary before emitting real enum definitions and SSA values.
  * This plan is local to one snapshot; the source ABI record remains the semantic owner.
  */
private[nodal] object EnumMlir:
  private def fail(message: String, path: String): Nothing =
    scala.util.Failure[Nothing](
      new BridgeException(BridgeDiagnostic("NODAL-BRIDGE-054-ENUM", message, Some(path)))
    ).get

  private def requireValid(condition: Boolean, message: String, path: String): Unit =
    if !condition then fail(message, path)

  private def quoted(value: String): String =
    val escaped = value.flatMap:
      case '\\' => "\\\\"
      case '"' => "\\\""
      case character if character.isControl => f"\\${character.toInt & 0xff}%02X"
      case character => character.toString
    s"\"$escaped\""

  private def dictionary(values: (String, String)*): String =
    values.sortBy(_._1).map((key, value) => s"$key = $value").mkString("{", ", ", "}")

  private def integer(value: BigInt): String =
    // Preserve unsigned magnitudes without allocating the declared (possibly padded) width.
    s"$value : i${math.max(64, value.bitLength + 1)}"

  private def operation(
      name: String,
      result: String,
      resultType: String,
      operands: Vector[(String, String)],
      attributes: String,
      sourceLocation: String
  ): String =
    s"$result = ${quoted(name)}(${operands.map(_._1).mkString(", ")}) <$attributes> : " +
      s"(${operands.map(_._2).mkString(", ")}) -> $resultType$sourceLocation"

  final class Plan(enumDefinitions: Vector[ResolvedEnumAbi], root: String):
    private val definitions = enumDefinitions.sortBy(_.identity)
    private val byIdentity = definitions.map(value => value.identity -> value).toMap
    private val symbols = definitions.map: value =>
      val bytes = MessageDigest.getInstance("SHA-256")
        .digest(value.identity.getBytes(StandardCharsets.UTF_8))
      value.identity -> ("enum_" + bytes.map(byte => f"${byte & 0xff}%02x").mkString)
    private val symbolByIdentity = symbols.toMap

    requireValid(
      byIdentity.size == definitions.size,
      "duplicate semantic enum identity in snapshot",
      root
    )
    definitions.foreach(validateDefinition)

    private def validateDefinition(value: ResolvedEnumAbi): Unit =
      val path = value.identity
      requireValid(
        path.nonEmpty && path == path.trim && path.length <= 4096 &&
          !path.exists(character =>
            character.isControl || character == ',' || character == '(' ||
              character == ')'
          ) &&
          value.width > 0 && value.width <= DigitalEnumContract.MaximumWidth &&
          value.cases.nonEmpty && value.cases.size <= DigitalEnumContract.MaximumCases,
        "enum requires a canonical identity, bounded width and nonempty bounded case list",
        path
      )
      val names = value.cases.map(_.name)
      requireValid(
        names.distinct.size == names.size && value.cases.map(_.id).distinct.size == names.size &&
          value.cases.map(_.code).distinct.size == names.size &&
          value.cases.forall(entry =>
            entry.name.nonEmpty && entry.name.length <= 1024 &&
              !entry.name.exists(_.isControl) &&
              entry.id == s"$path.${DigitalEnumContract.identityComponent(entry.name)}" &&
              entry.code >= 0 && entry.code.bitLength <= value.width
          ),
        "enum names, qualified case identities and unsigned fitting codes must be unique",
        path
      )
      requireValid(
        value.cases.foldLeft(0L)((total, entry) => total + math.max(1, entry.code.bitLength)) <=
          DigitalEnumContract.MaximumCodeBits,
        "enum code magnitudes exceed the finite metadata budget",
        path
      )
      val count = value.cases.size
      val compactWidth = math.max(1, BigInt(count - 1).bitLength)
      val validPolicy = value.policy match
        case "custom" => true
        case "sequential" => value.width == compactWidth &&
          value.cases.zipWithIndex.forall((entry, index) => entry.code == index)
        case "gray" => value.width == compactWidth &&
          value.cases.zipWithIndex.forall((entry, index) => entry.code == (index ^ (index >> 1)))
        case "one_hot" => value.width == count &&
          value.cases.zipWithIndex.forall((entry, index) => entry.code == (BigInt(1) << index))
        case _ => false
      requireValid(validPolicy, "enum encoding policy disagrees with exact width or codes", path)
      requireValid(
        value.hash.matches("[0-9a-f]{64}") &&
          DigitalEnumContract.abiHash(value.identity, value.width, value.cases) == value.hash,
        "enum ABI hash differs from its canonical identity, width and case/code pairs",
        path
      )

    private def definition(identity: String, path: String): ResolvedEnumAbi =
      byIdentity.getOrElse(identity, fail("enum definition is absent from snapshot", path))

    private def sourceType(value: ResolvedEnumAbi): String =
      s"Enum(${value.identity},${value.width},${value.hash})"

    def parseType(text: String, path: String): String =
      requireValid(text.startsWith("Enum(") && text.endsWith(")"), "malformed enum type", path)
      val fields = text.drop(5).dropRight(1).split(",", -1).toVector
      requireValid(fields.size == 3, "enum type requires identity, width and ABI hash", path)
      val value = definition(fields.head, path)
      requireValid(
        text == sourceType(value),
        "enum type differs from its exact definition ABI",
        path
      )
      s"!nodal.enum<${quoted(symbolByIdentity(value.identity))}, ${value.width}>"

    private def metadata(value: ResolvedEnumAbi, path: Option[String]): String = dictionary(
      (Vector(
        "abi_version" -> "1 : i64",
        "semantic_type" -> quoted(value.identity),
        "abi_hash" -> quoted(value.hash)
      ) ++ path.toVector.map(value => "semantic_path" -> quoted(value)))*
    )

    private def sourceLocation(source: Option[SourceSpan]): String = source match
      case Some(value) => s" loc(${quoted(value.path)}:${value.line}:${value.column})"
      case None => " loc(unknown)"

    def renderDefinitions: Vector[String] = definitions.map: value =>
      val cases = value.cases.map: entry =>
        val attributes = dictionary(
          "sym_name" -> quoted(DigitalEnumContract.identityComponent(entry.name)),
          "value" -> integer(entry.code),
          "metadata" ->
            dictionary("case_id" -> quoted(entry.id), "display_name" -> quoted(entry.name))
        )
        s"  \"nodal.enum_case\"() <$attributes> : () -> ()${sourceLocation(entry.source)}"
      val attributes = dictionary(
        "sym_name" -> quoted(symbolByIdentity(value.identity)),
        "underlying_type" -> s"!nodal.uint<${value.width}>",
        "encoding" -> quoted(value.policy),
        "metadata" -> metadata(value, None)
      )
      s"\"nodal.enum\"() <$attributes> ({\n${cases.mkString("\n")}\n}) : () -> ()${sourceLocation(value.source)}"

    def literalAttribute(
        expression: KernelDigitalExpressionSnapshot,
        expectedType: String,
        owner: String,
        path: String
    ): String =
      val reference = expression.path
      requireValid(
        expression.owner == owner &&
          expression.operation == "enum_literal" && expression.operands.isEmpty &&
          expression.literal.isEmpty && expression.caseIds.size == 1 &&
          expectedType == expression.dataType,
        "enum parameter value must be an exact owned enum literal of the declared type",
        path
      )
      val value = definition(
        expression.enumIdentity.getOrElse(fail("enum default has no semantic identity", reference)),
        reference
      )
      requireValid(
        expression.dataType == sourceType(value),
        "enum parameter default semantic identity differs from its exact type",
        reference
      )
      val _ = parseType(expression.dataType, expression.path)
      val entry = value.cases.find(_.id == expression.caseIds.head).getOrElse(
        fail("enum parameter default case does not belong to its definition", reference)
      )
      integer(entry.code)

    def validateExpression(
        value: KernelDigitalExpressionSnapshot,
        operandTypes: Vector[String],
        exactUnsignedWidth: (String, String, Int) => Boolean
    ): Unit =
      val path = value.path
      requireValid(value.operation.startsWith("enum_"), "operation is not an enum operation", path)
      requireValid(value.literal.isEmpty, "enum operation cannot carry a scalar literal", path)
      val abi = definition(
        value.enumIdentity.getOrElse(fail("enum operation has no semantic identity", path)),
        path
      )
      val expected = sourceType(abi)
      val ids = value.caseIds
      requireValid(
        ids.distinct.size == ids.size && ids.forall(abi.caseIds.contains),
        "enum operation case IDs must be unique and belong to the exact enum",
        path
      )
      def exactInput: Boolean = operandTypes.headOption.contains(expected)
      def bitsInput: Boolean = operandTypes.headOption.exists(input =>
        exactUnsignedWidth(input, value.operands.head, abi.width)
      )
      val valid = value.operation match
        case "enum_literal" => operandTypes.isEmpty && ids.size == 1 && value.dataType == expected
        case "enum_decode" => operandTypes.size == 1 && bitsInput && ids.size == 1 &&
          value.dataType == expected
        case "enum_as_bits" => operandTypes.size == 1 && exactInput && ids.isEmpty &&
          value.dataType == s"Bits(${abi.width})"
        case "enum_is_valid" => operandTypes.size == 1 && (exactInput || bitsInput) &&
          ids.isEmpty && value.dataType == "Bool"
        case "enum_eq" => operandTypes == Vector(expected, expected) && ids.isEmpty &&
          value.dataType == "Bool"
        case "enum_is_one_of" => operandTypes.size == 1 && exactInput && value.dataType == "Bool"
        case "enum_select" => exactInput && ids == abi.cases.map(_.id) &&
          operandTypes.size == ids.size + 2 && operandTypes.tail.forall(_ == value.dataType)
        case _ => false
      requireValid(
        valid,
        "enum operation has invalid exact types, arity, default or coverage",
        path
      )

    def renderExpression(
        expression: KernelDigitalExpressionSnapshot,
        result: String,
        resultType: String,
        operands: Vector[(String, String)],
        location: String
    ): String =
      val identity = expression.enumIdentity.getOrElse(
        fail("enum operation has no semantic identity", expression.path)
      )
      val abi = definition(identity, expression.path)
      operation(
        "nodal.enum_expr",
        result,
        resultType,
        operands,
        dictionary(
          "operator_name" -> quoted(expression.operation),
          "enum_ref" -> s"@${symbolByIdentity(identity)}",
          "case_ids" -> expression.caseIds.map(quoted).mkString("[", ", ", "]"),
          "metadata" -> metadata(abi, Some(expression.path))
        ),
        location
      )
