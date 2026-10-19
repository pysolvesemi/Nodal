package nodal.internal.bridge

import nodal.*

import scala.collection.mutable

/** One bounded typed value DAG for enum and scalar consumers. Width and declaration ownership
  * remain in the existing module/type renderer; enum ABI policy remains in EnumMlir.
  */
private[nodal] object DigitalValueMlir:
  final case class ScalarType(kind: String, width: String, lower: Long, upper: Long)
  final case class Rendered(lines: Vector[String], values: Map[String, (String, String)])
  final case class Projection(lines: Vector[String], value: (String, String))
  private final case class Proof(constant: Option[BigInt], nonnegative: Boolean)
  private val Unknown = Proof(None, false)

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

  private def requiredWidth(kind: String, value: BigInt): Long =
    if kind == "SInt" then value.bitLength.toLong + 1L
    else if value >= 0 then math.max(1, value.bitLength).toLong
    else Long.MaxValue

  final class Plan(snapshot: ConstructionSnapshot, enums: EnumMlir.Plan):
    private val modules = snapshot.modules.map(value => value.path -> value).toMap
    private val declarations = snapshot.modules.flatMap(module =>
      module.declarations.map(value => value.path -> (module.path, value))
    ).toMap
    private val expressions = snapshot.digitalExpressions.map(value => value.path -> value).toMap
    private val byOwner = snapshot.digitalExpressions.groupBy(_.owner)
    private val shapeInputs =
      snapshot.shapeIndices.map(value => value.path -> (value.owner, value.input)) ++
        snapshot.shapeViews.map(value => value.path -> (value.owner, value.input))
    private val shapeByPath = shapeInputs.toMap
    private val assignmentsByOwner = snapshot.digitalAssignments.groupBy(_.owner)

    private def assignmentRoot(path: String, owner: String, depth: Int = 0): String =
      requireValid(depth <= 128, "digital assignment projection exceeds depth limit", path)
      shapeByPath.get(path) match
        case Some((shapeOwner, input)) =>
          requireValid(shapeOwner == owner, "digital assignment projection has another owner", path)
          assignmentRoot(input, owner, depth + 1)
        case None => path

    def boundaryRoot(path: String, owner: String): KernelDeclarationSnapshot =
      val root = assignmentRoot(path, owner)
      declarations.get(root).filter(_._1 == owner).map(_._2).getOrElse(
        fail("capability wiring requires an actual owned boundary declaration", path)
      )

    requireValid(
      snapshot.digitalAssignments.map(_.path).distinct.size == snapshot.digitalAssignments.size,
      "duplicate digital assignment identities",
      snapshot.root
    )
    snapshot.digitalAssignments.foreach: assignment =>
      requireValid(
        modules.contains(assignment.owner) && assignment.path.startsWith(assignment.owner + "."),
        "digital assignment has no exact module owner",
        assignment.path
      )
      Vector(assignment.target, assignment.value).foreach: input =>
        val root = assignmentRoot(input, assignment.owner)
        val owner = expressions.get(root).map(_.owner).orElse(declarations.get(root).map(_._1))
        requireValid(
          owner.contains(assignment.owner),
          "digital assignment operand has another owner",
          assignment.path
        )
      val target = declarations.get(assignmentRoot(assignment.target, assignment.owner)).map(_._2)
        .getOrElse(fail("digital assignment target requires an owned declaration", assignment.path))
      val valid = assignment.kind match
        case "continuous" => Set("wire", "output").contains(target.kind)
        case "register_next" => target.kind == "register"
        case _ => false
      requireValid(
        valid,
        "digital assignment kind differs from its exact sink kind",
        assignment.path
      )

    requireValid(
      snapshot.digitalExpressions.size <= 65536 &&
        expressions.size == snapshot.digitalExpressions.size,
      "duplicate or excessive digital expression identities",
      snapshot.root
    )
    snapshot.digitalExpressions.foreach: expression =>
      requireValid(
        modules.contains(expression.owner) && expression.path.startsWith(expression.owner + ".") &&
          !declarations.contains(expression.path) && expression.operands.size <= 4098,
        "digital expression has invalid module ownership, identity or operand count",
        expression.path
      )
      expression.operands.foreach: operand =>
        val owner =
          expressions.get(operand).map(_.owner).orElse(declarations.get(operand).map(_._1))
        requireValid(
          owner.contains(expression.owner),
          "digital operand has no declaration or belongs to another module",
          expression.path
        )

    def parameterDefault(
        declaration: KernelDeclarationSnapshot,
        reference: String,
        scalarType: (String, String) => Option[ScalarType]
    ): String =
      literalAttribute(
        reference,
        declaration.dataType.getOrElse(
          fail("digital parameter has no type", declaration.path)
        ),
        declarations(declaration.path)._1,
        declaration.path,
        scalarType
      )

    def literalAttribute(
        reference: String,
        expectedType: String,
        owner: String,
        path: String,
        scalarType: (String, String) => Option[ScalarType]
    ): String =
      if expectedType.startsWith("Enum(") then
        enums.literalAttribute(
          expressions.getOrElse(
            reference,
            fail("enum parameter value has no captured literal", path)
          ),
          expectedType,
          owner,
          path
        )
      else
        val literal = expressions.get(reference) match
          case Some(value) =>
            requireValid(
              value.owner == owner && value.dataType == expectedType,
              "digital parameter literal requires exact ownership and type",
              path
            )
            value
          case None =>
            // Scalar defaults historically carry their canonical literal spelling. The exact
            // declaration type supplies the proof; a default is never a width assumption.
            KernelDigitalExpressionSnapshot(
              path,
              owner,
              if expectedType == "Bool" then "boolean"
              else if expectedType == "Real" then "real_literal"
              else "integer",
              Vector.empty,
              expectedType,
              Some(reference),
              None,
              Vector.empty
            )
        scalarLiteral(literal, scalarType)._1

    private def expressionType(path: String): String =
      expressions.get(path).map(_.dataType).orElse(declarations.get(path).flatMap(_._2.dataType))
        .getOrElse(fail("digital operand requires an exact type", path))

    private def scalarLiteral(
        value: KernelDigitalExpressionSnapshot,
        scalarType: (String, String) => Option[ScalarType]
    ): (String, Proof) =
      requireValid(
        value.enumIdentity.isEmpty && value.caseIds.isEmpty && value.operands.isEmpty,
        "scalar literal cannot carry operands or enum facts",
        value.path
      )
      val literal = value.literal.getOrElse(fail("scalar literal is absent", value.path))
      value.dataType match
        case "Bool" =>
          requireValid(
            value.operation == "boolean" && (literal == "true" || literal == "false"),
            "invalid Boolean literal in digital closure",
            value.path
          )
          literal -> Unknown
        case "Real" =>
          requireValid(
            value.operation == "real_literal",
            "invalid real literal operation",
            value.path
          )
          val number = literal.toDoubleOption.filter(_.isFinite).getOrElse(
            fail("digital real literal must be finite", value.path)
          )
          s"${java.lang.Double.toString(number)} : f64" -> Unknown
        case dataType =>
          requireValid(
            value.operation == "integer" && literal.length <= 315654,
            "invalid or excessive integer literal",
            value.path
          )
          val number = scala.util.Try(BigInt(literal)).getOrElse(
            fail("digital integer literal is malformed", value.path)
          )
          requireValid(
            number.toString == literal,
            "integer literal must be canonical decimal",
            value.path
          )
          val info = if dataType == "Integer" then ScalarType("SInt", "64", 64, 64)
          else
            scalarType(dataType, value.path).getOrElse(
              fail("digital literal requires a scalar integer type", value.path)
            )
          requireValid(
            requiredWidth(info.kind, number) <= info.lower,
            "integer literal does not fit every legal declared width",
            value.path
          )
          val known = Option.when(number.bitLength <= 4096)(number)
          s"$number : i${math.max(64, number.bitLength + 1)}" -> Proof(known, number >= 0)

    private def scalarConversion(
        value: KernelDigitalExpressionSnapshot,
        sourceType: String,
        input: Proof,
        scalarType: (String, String) => Option[ScalarType],
        widthRelation: (ScalarType, ScalarType, Int, String) => Boolean
    ): Proof =
      val path = value.path
      requireValid(
        value.literal.isEmpty && value.enumIdentity.isEmpty && value.caseIds.isEmpty &&
          value.operands.size == 1 && DigitalScalarContract.Operations.contains(value.operation),
        "digital conversion requires one typed operand and a closed operation",
        path
      )
      val source = scalarType(sourceType, value.operands.head).getOrElse(
        fail("digital conversion requires an exact Bits, UInt or SInt operand", path)
      )
      if value.operation == "digital_to_real" then
        requireValid(
          source.kind == "UInt" && value.dataType == "Real" &&
            (source.upper <= 1023L || input.constant.exists(_.toDouble.isFinite)),
          "numeric Real conversion requires a finite unsigned numeric proof",
          path
        )
        Unknown
      else
        val result = scalarType(value.dataType, path).getOrElse(
          fail("digital conversion requires an exact digital scalar result", path)
        )
        val sameKind = source.kind == result.kind
        val sameWidth = widthRelation(source, result, 0, path)
        val valid = value.operation match
          case "digital_extend" => sameKind && result.lower >= source.upper
          case "digital_truncate" => sameKind && result.upper <= source.lower
          case "digital_wrap" | "digital_saturate" => sameKind
          case "digital_resize_checked" => sameKind &&
            (result.lower >= source.upper ||
              input.constant.exists(requiredWidth(result.kind, _) <= result.lower))
          case "digital_to_signed" => source.kind == "UInt" && result.kind == "SInt" &&
            widthRelation(source, result, 1, path)
          case "digital_to_unsigned" => source.kind == "SInt" && result.kind == "UInt" &&
            sameWidth && input.nonnegative
          case "digital_reinterpret_signed" => source.kind == "UInt" && result.kind == "SInt" &&
            sameWidth
          case "digital_reinterpret_unsigned" => source.kind == "SInt" && result.kind == "UInt" &&
            sameWidth
          case _ => false
        requireValid(
          valid,
          "digital conversion violates width, signedness or checked-value proof",
          path
        )
        val constant = input.constant.flatMap: number =>
          value.operation match
            case "digital_extend" | "digital_resize_checked" | "digital_to_signed" |
                "digital_to_unsigned" => Some(number)
            case _ if requiredWidth(result.kind, number) <= result.lower => Some(number)
            case _ if result.lower != result.upper || result.upper > 4096L => None
            case operation =>
              val width = result.upper.toInt
              val modulus = BigInt(1) << width
              if operation == "digital_saturate" then
                val lower = if result.kind == "SInt" then -(modulus >> 1) else BigInt(0)
                val upper = if result.kind == "SInt" then (modulus >> 1) - 1 else modulus - 1
                Some(number.max(lower).min(upper))
              else
                val bits = ((number % modulus) + modulus) % modulus
                Some(if result.kind == "SInt" && bits.testBit(width - 1) then bits - modulus
                else bits)
        val preservesNonnegative = Set(
          "digital_extend",
          "digital_resize_checked",
          "digital_saturate",
          "digital_to_signed",
          "digital_to_unsigned"
        ).contains(value.operation)
        Proof(
          constant,
          result.kind != "SInt" || constant.exists(_ >= 0) ||
            (preservesNonnegative && input.nonnegative)
        )

    /** Topological emission scales with unique nodes and edges, never with use-tree expansion. */
    def renderModule(
        module: KernelModuleSnapshot,
        nativeType: (String, String) => String,
        computedType: (String, String) => String,
        scalarType: (String, String) => Option[ScalarType],
        widthRelation: (ScalarType, ScalarType, Int, String) => Boolean,
        declarationValue: (
            KernelDeclarationSnapshot,
            String,
            String,
            Option[(String, String)]
        ) => Vector[String],
        declarationInitializer: KernelDeclarationSnapshot => Option[String],
        metadata: String => String,
        sourceLocation: String => String,
        projectionValue: (String, String => (String, String)) => Projection,
        declarationNativeType: (KernelDeclarationSnapshot, String) => String,
        specializedValue: (
            KernelDigitalExpressionSnapshot,
            String,
            String,
            Vector[(String, String)]
        ) => Projection,
        additionalRoots: Vector[String]
    ): Rendered =
      val lines = mutable.ArrayBuffer.empty[String]
      val values = mutable.HashMap.empty[String, (String, String)]
      val heights = mutable.HashMap.empty[String, Int]
      val proofs = mutable.HashMap.empty[String, Proof]
      val active = mutable.HashSet.empty[String]
      var ordinal = 0
      def exactUnsignedWidth(text: String, path: String, expected: Int): Boolean =
        scalarType(text, path).exists(info =>
          Set("Bits", "UInt").contains(info.kind) &&
            info.lower == expected.toLong && info.upper == expected.toLong
        )
      def emit(path: String, depth: Int): (String, String) =
        requireValid(depth <= 128, "digital expression graph exceeds depth limit", path)
        values.get(path) match
          case Some(value) =>
            requireValid(
              depth + heights(path) <= 128,
              "digital expression graph exceeds depth limit",
              path
            )
            value
          case None =>
            requireValid(active.add(path), "digital expression graph contains a cycle", path)
            val result = s"%digital_value_$ordinal"
            var actualResult = result
            ordinal += 1
            val sourceType = expressionType(path)
            val payloadType =
              if expressions.get(path).exists(_.operation != "shape_reference") then
                computedType(sourceType, path)
              else nativeType(sourceType, path)
            val dataType = declarations.get(path).map(value =>
              declarationNativeType(value._2, payloadType)
            ).getOrElse(payloadType)
            val unsigned = scalarType(sourceType, path).exists(_.kind != "SInt")
            val (text, height, proof) = expressions.get(path) match
              case Some(expression) =>
                val operands = expression.operands.map(emit(_, depth + 1))
                val height = expression.operands.map(heights).maxOption.getOrElse(-1) + 1
                val specialized =
                  DigitalInterfaceContract.ValueOperations.contains(expression.operation)
                if !specialized then
                  expression.operands.zip(operands).foreach: (input, actual) =>
                    requireValid(
                      actual._2 == nativeType(expressionType(input), input),
                      "a digital capability cannot be used as an ordinary typed value",
                      path
                    )
                if specialized then
                  val projected = specializedValue(expression, result, dataType, operands)
                  requireValid(
                    projected.value._2 == dataType,
                    "specialized digital result differs from its exact source type",
                    path
                  )
                  actualResult = projected.value._1
                  (projected.lines, height, Proof(None, unsigned))
                else if expression.operation == "shape_reference" then
                  requireValid(
                    expression.operands.isEmpty && expression.literal.isEmpty &&
                      expression.enumIdentity.isEmpty && expression.caseIds.isEmpty &&
                      shapeByPath.get(path).exists(_._1 == module.path),
                    "digital projection must name one authoritative owned shape record",
                    path
                  )
                  val dependencies = mutable.ArrayBuffer.empty[String]
                  val projected = projectionValue(
                    path,
                    dependency =>
                      requireValid(
                        shapeByPath(path)._2 == dependency,
                        "shape resolver requested a different immediate input",
                        path
                      )
                      dependencies += dependency
                      emit(dependency, depth + 1)
                  )
                  requireValid(
                    dependencies.toVector == Vector(shapeByPath(path)._2) &&
                      projected.value._2 == dataType,
                    "digital projection result or dependency differs from its exact source contract",
                    path
                  )
                  actualResult = projected.value._1
                  (projected.lines, dependencies.map(heights).max + 1, Proof(None, unsigned))
                else if expression.operation.startsWith("enum_") then
                  enums.validateExpression(
                    expression,
                    expression.operands.map(expressionType),
                    exactUnsignedWidth
                  )
                  (
                    Vector(enums.renderExpression(
                      expression,
                      result,
                      dataType,
                      operands,
                      sourceLocation(path)
                    )),
                    height,
                    Proof(None, unsigned)
                  )
                else if expression.operation.startsWith("digital_") then
                  requireValid(
                    expression.operands.size == 1,
                    "digital conversion requires one operand",
                    path
                  )
                  val proof = scalarConversion(
                    expression,
                    expressionType(expression.operands.head),
                    proofs(expression.operands.head),
                    scalarType,
                    widthRelation
                  )
                  val attributes =
                    s"{operator_name = ${quoted(expression.operation)}, metadata = ${metadata(path)}}"
                  (
                    Vector(operation(
                      "nodal.digital_expr",
                      result,
                      dataType,
                      operands,
                      attributes,
                      sourceLocation(path)
                    )),
                    height,
                    proof
                  )
                else
                  val (literal, proof) = scalarLiteral(expression, scalarType)
                  val attributes =
                    s"{value = $literal, spelling = ${quoted(expression.literal.get)}, metadata = ${metadata(path)}}"
                  (
                    Vector(operation(
                      "nodal.const_literal",
                      result,
                      dataType,
                      Vector.empty,
                      attributes,
                      sourceLocation(path)
                    )),
                    height,
                    proof
                  )
              case None =>
                val declaration = declarations(path)._2
                requireValid(
                  declaration.generatedScope.isEmpty,
                  "digital operand cannot escape a generated declaration scope",
                  path
                )
                val initialPath = declarationInitializer(declaration)
                initialPath.foreach: input =>
                  val inputOwner =
                    expressions.get(input).map(_.owner).orElse(declarations.get(input).map(_._1))
                  requireValid(
                    inputOwner.contains(module.path),
                    "digital initializer must belong to its exact module",
                    path
                  )
                  requireValid(
                    expressionType(input) == sourceType,
                    "digital initializer requires the exact storage type",
                    path
                  )
                val initial = initialPath.map(emit(_, depth + 1))
                val height = initialPath.map(heights(_) + 1).getOrElse(0)
                (
                  declarationValue(declaration, result, dataType, initial),
                  height,
                  Proof(None, unsigned)
                )
            lines ++= text
            val value = actualResult -> dataType
            values.update(path, value)
            heights.update(path, height)
            proofs.update(path, proof)
            active.remove(path)
            value
      byOwner.getOrElse(module.path, Vector.empty).sortBy(_.path).foreach(value =>
        emit(value.path, 0)
      )
      assignmentsByOwner.getOrElse(module.path, Vector.empty).sortBy(_.path).foreach: assignment =>
        Vector(assignment.target, assignment.value).foreach(path =>
          emit(assignmentRoot(path, module.path), 0)
        )
      additionalRoots.distinct.sorted.foreach: path =>
        val owner = expressions.get(path).map(_.owner).orElse(declarations.get(path).map(_._1))
        requireValid(
          owner.contains(module.path),
          "specialized digital root has another owner",
          path
        )
        emit(path, 0)
      module.declarations.filter(value => Set("wire", "register").contains(value.kind))
        .sortBy(_.path).foreach(value => emit(value.path, 0))
      Rendered(lines.toVector, values.toMap)
