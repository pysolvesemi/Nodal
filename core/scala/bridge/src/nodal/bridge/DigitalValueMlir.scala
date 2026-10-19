package nodal.internal.bridge

import nodal.*

import scala.collection.mutable

/** One bounded typed value DAG for finite digital values and isolated collection callbacks. Width
  * and declaration ownership remain in the existing module/type renderer; enum ABI policy remains
  * in EnumMlir.
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
    private val collections = snapshot.digitalCollections.map(value => value.path -> value).toMap
    private val loops = snapshot.digitalLoops.map(value => value.path -> value).toMap
    private val iterationIndices = snapshot.iterationIndices.map(value => value.path -> value).toMap
    private val indicesByRegion = snapshot.iterationIndices.groupBy(_.region)
    private val loopsByOwner = snapshot.digitalLoops.groupBy(_.owner)
    private final case class Region(
        path: String,
        owner: String,
        parentRegion: Option[String],
        arguments: Vector[KernelDigitalRegionArgument],
        captures: Vector[String]
    )
    private val regions = (snapshot.digitalCollections.map(value =>
      Region(value.path, value.owner, value.parentRegion, value.arguments, value.captures)
    ) ++ snapshot.digitalLoops.map(value =>
      Region(value.path, value.owner, None, value.arguments, value.captures)
    )).map(value => value.path -> value).toMap
    private val arguments = regions.valuesIterator.flatMap(region =>
      region.arguments.map(argument => argument.path -> (region, argument))
    ).toMap
    private val byRegion = snapshot.digitalExpressions.groupBy(_.region)
    private val staticExpressions =
      snapshot.parameterExpressions.map(value => value.path -> value).toMap
    private val staticByRegion = snapshot.parameterExpressions.groupBy(_.region)
    private lazy val loopBoundQuery = new StructuralParameterBinding.Query(snapshot)
    private val childrenByRegion = snapshot.digitalCollections.groupBy(_.parentRegion)
    private val argumentsByRegion = regions.view.mapValues(_.arguments.map(_.path)).toMap
    private def bodyValuePaths(region: String): Vector[String] =
      val pending = mutable.ArrayDeque(region)
      val result = mutable.ArrayBuffer.empty[String]
      var scopes = 0
      while pending.nonEmpty do
        val current = pending.removeLast()
        scopes += 1
        requireValid(
          scopes <= 65536,
          "collection scope traversal exceeds its bounded graph",
          region
        )
        result ++= byRegion.getOrElse(Some(current), Vector.empty).map(_.path)
        result ++= argumentsByRegion.getOrElse(current, Vector.empty)
        pending ++= childrenByRegion.getOrElse(Some(current), Vector.empty).map(_.path)
      result.toVector

    def valueOwner(path: String): Option[String] =
      expressions.get(path).map(_.owner).orElse(staticExpressions.get(path).map(_.owner))
        .orElse(iterationIndices.get(path).map(_.owner))
        .orElse(declarations.get(path).map(_._1))
        .orElse(arguments.get(path).map(_._1.owner))

    def valueRegion(path: String): Option[String] =
      expressions.get(path).flatMap(_.region).orElse(staticExpressions.get(path).flatMap(_.region))
        .orElse(iterationIndices.get(path).map(_.region))
        .orElse(arguments.get(path).map(_._1.path))

    def requireVisible(
        path: String,
        owner: String,
        region: Option[String],
        consumer: String
    ): Unit =
      requireValid(
        valueOwner(path).contains(owner) && valueRegion(path) == region,
        "digital operand must belong to its exact lexical scope; free values need explicit captures",
        consumer
      )
    private val shapeInputs =
      snapshot.shapeIndices.map(value => value.path -> (value.owner, value.input)) ++
        snapshot.shapeViews.map(value => value.path -> (value.owner, value.input))
    private val shapeByPath = shapeInputs.toMap
    private val generatedShapePaths =
      snapshot.shapeIndices.filter(_.generatedScope.nonEmpty).map(_.path).toSet ++
        snapshot.shapeViews.filter(_.generatedScope.nonEmpty).map(_.path)
    private val assignmentsByRegion =
      snapshot.digitalAssignments.groupBy(value => value.owner -> value.region)
    private val selectorsByPath =
      (snapshot.shapeIndices.map(value => value.path -> value.indices) ++
        snapshot.shapeViews.map(value =>
          value.path -> value.slice.toVector.flatMap(slice =>
            Vector(slice.lower, slice.upperExclusive)
          )
        )).toMap

    requireValid(
      loops.size == snapshot.digitalLoops.size && loops.size <= 65536 &&
        regions.size == collections.size + loops.size &&
        iterationIndices.size == snapshot.iterationIndices.size && iterationIndices.size <= 65536,
      "duplicate or excessive digital loop, region or index identities",
      snapshot.root
    )
    snapshot.digitalLoops.foreach: loop =>
      requireValid(
        modules.contains(loop.owner) && loop.path.startsWith(loop.owner + ".") &&
          loop.sourceOrdinal >= 0 && loop.maximumTripCount >= 0 && loop.maximum.forall(_ >= 0) &&
          loop.captures.distinct.size == loop.captures.size &&
          loop.arguments.size == loop.captures.size + 1 && loop.arguments.size <= 4098 &&
          !expressions.contains(loop.path) && !declarations.contains(loop.path) &&
          !staticExpressions.contains(loop.path) && !iterationIndices.contains(loop.path),
        "digital loop requires canonical identity, bounds, captures and induction argument",
        loop.path
      )
      loop.generatedRegion.foreach: path =>
        requireValid(
          snapshot.generatedRegions.exists(value =>
            value.path == path && value.owner == loop.owner && value.parent.isEmpty &&
              value.lower == loop.lower &&
              value.upperExclusive == loop.upperExclusive && value.step == loop.step &&
              value.maximum == loop.maximum && value.maximumTripCount == loop.maximumTripCount
          ),
          "digital range partition must retain its exact source domain",
          loop.path
        )
      Vector(loop.lower, loop.upperExclusive, loop.step).foreach: input =>
        input.toLongOption match
          case Some(value) => requireValid(
              input == value.toString,
              "loop bound literal must be canonical signed i64",
              loop.path
            )
          case None => requireVisible(input, loop.owner, None, loop.path)
      loop.captures.foreach(requireVisible(_, loop.owner, None, loop.path))
      try
        val query = loopBoundQuery
        val lower = query.resolve(loop.owner, loop.lower)
        val upper = query.resolve(loop.owner, loop.upperExclusive)
        val step = query.resolve(loop.owner, loop.step)
        if loop.generatedRegion.isEmpty then
          requireValid(
            loop.lower == "0" && loop.step == "1" && query.bounds(upper).lower >= 0,
            "explicit loop requires lower zero, step one and a nonnegative count envelope",
            loop.path
          )
        // Walk the retained operands as well as the normalized value: even an inactive
        // choose branch may not hide a parameter-dependent source step.
        val stepVisited = mutable.HashSet.empty[String]
        val stepPending = mutable.ArrayDeque(loop.step)
        while stepPending.nonEmpty do
          val input = stepPending.removeHead()
          if input.toLongOption.isEmpty && stepVisited.add(input) then
            requireValid(
              !declarations.contains(input) && stepVisited.size <= 65536,
              "loop step requires a parameter-free invariant constant DAG",
              loop.path
            )
            staticExpressions.get(input).foreach(value => stepPending ++= value.operands)
        val stepGraph = query.exportValues(Vector(step))
        requireValid(
          stepGraph.nodes(stepGraph.dimensions.head).literal.nonEmpty,
          "loop step requires a proved invariant constant DAG",
          loop.path
        )
        val domain = IterationDomain.boundedDigital(
          query.bounds(lower),
          query.bounds(upper),
          query.bounds(step),
          loop.maximum,
          identicalBounds = lower == upper
        )
          .fold(problem => fail(problem.message, loop.path), identity)
        requireValid(
          domain.maximumTripCount == loop.maximumTripCount,
          "loop trip envelope differs from its actual static bound DAGs",
          loop.path
        )
      catch
        case error: IllegalArgumentException => fail(error.getMessage, loop.path)
      loop.arguments.zipWithIndex.foreach: (argument, index) =>
        val expectedType =
          if index == 0 then "Integer" else expressionType(loop.captures(index - 1))
        val role = if index == 0 then "induction"
        else if expectedType == "Integer" then "static_capture" else "capture"
        requireValid(
          argument.path == s"${loop.path}.arg$index" && argument.role == role &&
            argument.dataType == expectedType && expectedType != "Real" &&
            !expressions.contains(argument.path) && !declarations.contains(argument.path) &&
            !staticExpressions.contains(argument.path) && !iterationIndices.contains(argument.path),
          "loop argument must retain its canonical identity, role and exact capture type",
          argument.path
        )
      requireValid(
        byRegion.getOrElse(Some(loop.path), Vector.empty).size.toLong +
          staticByRegion.getOrElse(Some(loop.path), Vector.empty).size.toLong +
          indicesByRegion.getOrElse(loop.path, Vector.empty).size + loop.arguments.size <= 4098L,
        "loop body exceeds its bounded value budget",
        loop.path
      )
    loopsByOwner.foreach: (owner, values) =>
      val ordinals = values.map(_.sourceOrdinal) ++ assignmentsByRegion
        .getOrElse(owner -> None, Vector.empty).map(_.sourceOrdinal)
      requireValid(
        ordinals.distinct.size == ordinals.size,
        "module digital effects require distinct source ordinals",
        owner
      )
    private def assignmentRoot(
        path: String,
        owner: String,
        region: Option[String] = None,
        depth: Int = 0
    ): String =
      requireValid(depth <= 128, "digital assignment projection exceeds depth limit", path)
      requireVisible(path, owner, region, path)
      shapeByPath.get(path) match
        case Some((shapeOwner, input)) =>
          requireValid(shapeOwner == owner, "digital assignment projection has another owner", path)
          requireValid(
            !generatedShapePaths.contains(path),
            "digital assignment projection cannot use generated storage",
            path
          )
          assignmentRoot(input, owner, region, depth + 1)
        case None => arguments.get(path) match
            case Some((scope, argument)) =>
              val capture = scope.arguments.indexWhere(_.path == argument.path) -
                (scope.arguments.size - scope.captures.size)
              requireValid(
                loops.contains(scope.path) && capture >= 0 && capture < scope.captures.size,
                "digital assignment requires an actual loop capture of writable storage",
                path
              )
              assignmentRoot(scope.captures(capture), owner, scope.parentRegion, depth + 1)
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
        modules.contains(assignment.owner) && assignment.path.startsWith(assignment.owner + ".") &&
          assignment.sourceOrdinal >= 0 && assignment.region.forall(region =>
            loops.get(region).exists(_.owner == assignment.owner)
          ),
        "digital assignment has no exact module/loop owner or source order",
        assignment.path
      )
      Vector(assignment.target, assignment.value).foreach(input =>
        requireVisible(input, assignment.owner, assignment.region, assignment.path)
      )
      val target = declarations.get(
        assignmentRoot(assignment.target, assignment.owner, assignment.region)
      ).map(_._2).getOrElse(
        fail("digital assignment target requires an owned declaration", assignment.path)
      )
      val valid = assignment.kind match
        case "continuous" => Set("wire", "output").contains(target.kind)
        case "register_next" => assignment.region.isEmpty && target.kind == "register" &&
          assignment.target == target.path
        case _ => false
      requireValid(
        valid && target.generatedScope.isEmpty,
        "digital assignment kind differs from its exact sink kind or uses an unsupported projected register update",
        assignment.path
      )
    assignmentsByRegion.foreach:
      case ((owner, region), assignments) =>
        if region.nonEmpty then
          requireValid(
            assignments.map(_.sourceOrdinal).sorted == assignments.indices.toVector,
            "loop assignment source ordinals must be unique and contiguous from zero",
            region.get
          )
        else if loopsByOwner.contains(owner) || assignments.exists(_.sourceOrdinal != 0) then
          val ordinals = assignments.map(_.sourceOrdinal) ++
            loopsByOwner.getOrElse(owner, Vector.empty).map(_.sourceOrdinal)
          requireValid(
            ordinals.distinct.size == ordinals.size,
            "module digital effects require distinct source ordinals",
            owner
          )

    requireValid(
      snapshot.parameterExpressions.size <= 65536 &&
        staticExpressions.size == snapshot.parameterExpressions.size,
      "duplicate or excessive static expression identities",
      snapshot.root
    )
    snapshot.parameterExpressions.foreach: expression =>
      requireValid(
        !expressions.contains(expression.path) && !arguments.contains(expression.path) &&
          !declarations.contains(expression.path),
        "static and digital identities must be distinct",
        expression.path
      )
      expression.region.foreach: region =>
        val pure = expression.literal match
          case Some(_) => expression.operation == "integer" && expression.operands.isEmpty
          case None => IterationDomain.staticArithmetic(expression.operation).exists(_.arity ==
              expression.operands.size)
        requireValid(
          regions.get(region).exists(_.owner == expression.owner) &&
            expression.dataType == "Integer" && expression.unit.forall(_.isEmpty) && pure,
          "regional static expressions require an exact owner and pure Integer operation",
          expression.path
        )
        if loops.contains(region) then
          requireValid(
            expression.operands.forall(input =>
              staticExpressions.contains(input) ||
                arguments.get(input).exists(_._2.role == "static_capture")
            ),
            "loop-local statics require only local statics and exact static captures, never induction",
            expression.path
          )
      expression.operands.foreach: input =>
        if expression.region.nonEmpty || arguments.contains(input) ||
          staticExpressions.get(input).exists(_.region.nonEmpty)
        then
          requireVisible(input, expression.owner, expression.region, expression.path)

    requireValid(
      snapshot.digitalExpressions.size <= 65536 &&
        expressions.size == snapshot.digitalExpressions.size,
      "duplicate or excessive digital expression identities",
      snapshot.root
    )
    snapshot.digitalExpressions.foreach: expression =>
      DigitalMaterializationMlir.validate(expression)
      requireValid(
        DigitalAggregateContract.Operations.contains(expression.operation) ==
          expression.structure.nonEmpty,
        "only Struct operations carry schema evidence",
        expression.path
      )
      requireValid(
        modules.contains(expression.owner) && expression.path.startsWith(expression.owner + ".") &&
          !declarations.contains(expression.path) && expression.operands.size <= 4098,
        "digital expression has invalid module ownership, identity or operand count",
        expression.path
      )
      expression.region.foreach: region =>
        requireValid(
          regions.get(region).exists(_.owner == expression.owner),
          "digital expression has no exact lexical region owner",
          expression.path
        )
      expression.operands.foreach: operand =>
        requireVisible(operand, expression.owner, expression.region, expression.path)

    requireValid(
      snapshot.digitalCollections.size <= 65536 &&
        collections.size == snapshot.digitalCollections.size &&
        arguments.size == regions.valuesIterator.map(_.arguments.size).sum,
      "duplicate or excessive collection or argument identities",
      snapshot.root
    )
    snapshot.digitalCollections.foreach: collection =>
      val path = collection.path
      requireValid(
        modules.contains(collection.owner) && path.startsWith(collection.owner + ".") &&
          DigitalCollectionContract.Kinds.contains(collection.kind) &&
          collection.inputs.size == (if collection.kind == "zip_with" then 2 else 1) &&
          collection.seed.nonEmpty == Set("fold_left", "scan_left").contains(collection.kind) &&
          collection.captures.distinct.size == collection.captures.size &&
          collection.arguments.size <= 4098,
        "collection kind, identity, operand segments or argument count is invalid",
        path
      )
      requireValid(
        byRegion.getOrElse(Some(path), Vector.empty).size.toLong +
          staticByRegion.getOrElse(Some(path), Vector.empty).size.toLong +
          collection.arguments.size <= 4098L,
        "collection values, static expressions and arguments exceed the bounded region budget",
        path
      )
      val operands = collection.inputs ++ collection.seed ++ collection.captures
      requireValid(
        expressions.get(path).exists(value =>
          value.operation == DigitalCollectionContract.Operation &&
            value.owner == collection.owner && value.region == collection.parentRegion &&
            value.operands == operands && value.dataType == collection.dataType &&
            value.literal.isEmpty && value.enumIdentity.isEmpty && value.caseIds.isEmpty
        ),
        "collection must have one matching ordinary digital result record",
        path
      )
      collection.arguments.zipWithIndex.foreach: (argument, index) =>
        requireValid(
          argument.path == s"$path.arg$index" && !expressions.contains(argument.path) &&
            !declarations.contains(argument.path) && !staticExpressions.contains(argument.path),
          "collection argument identity must be canonical and distinct from all values",
          path
        )
      var parent = collection.parentRegion
      val ancestors = mutable.HashSet(path)
      while parent.nonEmpty do
        val identity = parent.get
        requireValid(
          ancestors.add(identity) && ancestors.size <= 128,
          "collection lexical owners contain a cycle or exceed depth limit",
          path
        )
        val outer = regions.getOrElse(
          identity,
          fail("collection parent region is absent", path)
        )
        requireValid(
          outer.owner == collection.owner,
          "collection parent has another module owner",
          path
        )
        parent = outer.parentRegion
      operands.foreach(requireVisible(_, collection.owner, collection.parentRegion, path))
      requireVisible(collection.yieldValue, collection.owner, Some(path), path)
    snapshot.digitalExpressions.filter(_.operation == DigitalCollectionContract.Operation).foreach:
      expression =>
        requireValid(
          collections.contains(expression.path),
          "collection result has no region record",
          expression.path
        )
    (snapshot.shapeIndices.map(value => (value.path, value.owner, value.input, value.region)) ++
      snapshot.shapeViews.map(value =>
        (value.path, value.owner, value.input, value.region)
      )).foreach: (path, owner, input, region) =>
      if expressions.contains(path) || region.nonEmpty then
        requireValid(
          expressions.get(path).exists(value =>
            value.operation == "shape_reference" && value.region == region
          ),
          "digital shape projection and expression must share one exact region",
          path
        )
        requireVisible(input, owner, region, path)

    private val indexDependencies = mutable.HashMap.empty[String, Boolean]
    private val activeIndices = mutable.HashSet.empty[String]
    def isIterationIndex(path: String): Boolean =
      iterationIndices.contains(path) || arguments.get(path).exists(value =>
        loops.contains(value._1.path) && value._2.role == "induction"
      )
    private def validateIterationIndex(path: String, region: String, depth: Int = 0): Boolean =
      requireValid(depth <= 128, "iteration index graph exceeds depth limit", path)
      requireVisible(path, loops(region).owner, Some(region), path)
      indexDependencies.getOrElseUpdate(
        path, {
          requireValid(activeIndices.add(path), "iteration index graph contains a cycle", path)
          try
            iterationIndices.get(path) match
              case Some(value) =>
                requireValid(
                  value.region == region && value.path.startsWith(value.owner + ".") &&
                    value.path.trim == value.path && Set("add", "sub").contains(value.operation) &&
                    value.operands.size == 2 && !expressions.contains(path) &&
                    !staticExpressions.contains(path) && !declarations.contains(path) &&
                    !arguments.contains(path),
                  "iteration index requires one actual affine add/sub DAG in its loop",
                  path
                )
                val dependencies = value.operands.map(validateIterationIndex(_, region, depth + 1))
                requireValid(
                  dependencies.count(identity) == 1 &&
                    (value.operation != "sub" || dependencies.head),
                  "iteration index must be induction plus/minus one static offset",
                  path
                )
                true
              case None if isIterationIndex(path) => true
              case None =>
                requireValid(
                  expressionType(path) == "Integer" &&
                    (staticExpressions.contains(path) ||
                      arguments.get(path).exists(_._2.role == "static_capture")),
                  "iteration offset must be a local static DAG or actual static capture",
                  path
                )
                staticExpressions.get(path).foreach: expression =>
                  requireValid(
                    expression.operands.forall(input =>
                      !validateIterationIndex(input, region, depth + 1)
                    ),
                    "loop-local static offset must not depend on induction",
                    path
                  )
                false
          finally { val _ = activeIndices.remove(path) }
        }
      )
    snapshot.iterationIndices.foreach: value =>
      requireValid(
        loops.get(value.region).exists(_.owner == value.owner),
        "iteration index has no exact loop owner",
        value.path
      )
      val _ = validateIterationIndex(value.path, value.region)

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
      expressions.get(reference).foreach: value =>
        requireValid(
          value.owner == owner && value.region.isEmpty,
          "digital parameter literal cannot escape its module lexical scope",
          path
        )
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

    def expressionType(path: String): String =
      expressions.get(path).map(_.dataType).orElse(staticExpressions.get(path).map(_.dataType))
        .orElse(declarations.get(path).flatMap(_._2.dataType))
        .orElse(arguments.get(path).map(_._2.dataType))
        .orElse(iterationIndices.get(path).map(_ => "Integer"))
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
          literal -> Proof(Some(if literal == "true" then BigInt(1) else BigInt(0)), true)
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

    /** Validate the untrusted snapshot independently of source construction. The existing value
      * schedule supplies proofs once per SSA node; width algebra stays with its canonical owner.
      */
    private def combinational(
        value: KernelDigitalExpressionSnapshot,
        inputs: Vector[Proof],
        scalarType: (String, String) => Option[ScalarType],
        widthRelation: (ScalarType, ScalarType, Int, String) => Boolean,
        widthFormula: (ScalarType, String, Vector[ScalarType], Long, String) => Boolean,
        exactValueType: (String, String, String) => Boolean
    ): Proof =
      val path = value.path
      val operation = value.operation
      val arity = operation match
        case "digital_neg" | "digital_not" | "bool_not" => 1
        case "digital_add" | "digital_sub" | "digital_mul" | "digital_eq" | "digital_ne" |
            "digital_lt" | "digital_le" | "digital_gt" | "digital_ge" | "digital_and" |
            "digital_or" | "digital_xor" | "digital_concat" | "digital_shl" | "digital_shr" |
            "digital_logical_shr" | "digital_div" | "digital_rem" | "digital_min" |
            "digital_max" | "bool_and" | "bool_or" => 2
        case "digital_extract" | "digital_select" => 3
        case _ => 0
      requireValid(
        arity != 0 && value.operands.size == arity && inputs.size == arity &&
          value.literal.isEmpty && value.enumIdentity.isEmpty && value.caseIds.isEmpty,
        "digital combinational expression requires an exact opcode, arity and ordinary value facts",
        path
      )
      val operandTypes = value.operands.map(expressionType)
      if operation == "digital_select" then
        val selected = value.dataType
        val finite = selected == "Bool" || scalarType(selected, path).nonEmpty ||
          selected.startsWith("Enum(") || selected.startsWith("Vec(") ||
          selected.startsWith("Struct(")
        requireValid(
          operandTypes.head == "Bool" && finite &&
            exactValueType(operandTypes(1), selected, path) &&
            exactValueType(operandTypes(2), selected, path),
          "digital selection requires Bool and identical complete finite branch types",
          path
        )
        val constant = inputs.head.constant.flatMap(number =>
          inputs(if number == 0 then 2 else 1).constant
        ).orElse(inputs(1).constant.filter(number => inputs(2).constant.contains(number)))
        val nonnegative = inputs.head.constant.map(number =>
          inputs(if number == 0 then 2 else 1).nonnegative
        ).getOrElse(inputs(1).nonnegative && inputs(2).nonnegative)
        Proof(constant, nonnegative || constant.exists(_ >= 0))
      else if DigitalCombinationalContract.ReachableBooleanOperations.contains(operation) then
        requireValid(
          value.dataType == "Bool" && operandTypes.forall(_ == "Bool"),
          "digital Boolean expression requires actual Bool operands and result",
          path
        )
        val constant = if operation == "bool_not" then
          inputs.head.constant.map(number => if number == 0 then BigInt(1) else BigInt(0))
        else
          for left <- inputs(0).constant; right <- inputs(1).constant yield
            val truth = if operation == "bool_and" then left != 0 && right != 0
            else left != 0 || right != 0
            if truth then BigInt(1) else BigInt(0)
        Proof(constant, true)
      else
        val sources = operandTypes.zip(value.operands).map: (dataType, operand) =>
          scalarType(dataType, operand).getOrElse(
            fail("digital scalar operation requires exact Bits, UInt or SInt operands", path)
          )
        val source = sources.head
        val comparison = Set(
          "digital_eq",
          "digital_ne",
          "digital_lt",
          "digital_le",
          "digital_gt",
          "digital_ge"
        ).contains(operation)
        val result = if comparison then
          requireValid(value.dataType == "Bool", "digital comparison requires a Bool result", path)
          ScalarType("Bool", "1", 1, 1)
        else
          scalarType(value.dataType, path).getOrElse(
            fail("digital scalar operation requires an exact bit-vector result", path)
          )
        def numeric(kind: String): Boolean = kind == "UInt" || kind == "SInt"
        def equalWidth(left: ScalarType, right: ScalarType): Boolean =
          widthRelation(left, right, 0, path)
        def literalWidth(number: Long): ScalarType =
          ScalarType("UInt", number.toString, number, number)
        def formula(operator: String, operands: Vector[ScalarType], increment: Long = 0L): Boolean =
          widthFormula(result, operator, operands, increment, path)
        def staticIndex(index: Int): Long =
          val operand = expressions.get(value.operands(index)).getOrElse(
            fail("digital shift and extraction indices require actual UInt literal operands", path)
          )
          requireValid(
            operand.operation == "integer" && sources(index).kind == "UInt" &&
              operand.operands.isEmpty && operand.literal.nonEmpty,
            "digital shift and extraction indices require actual UInt literal operands",
            path
          )
          inputs(index).constant.filter(number => number >= 0 && number <= Int.MaxValue)
            .map(_.toLong).getOrElse(
              fail("digital static index is outside the nonnegative Int range", path)
            )
        var amount = 0L
        var lower = 0L
        var upper = 0L
        val valid = operation match
          case "digital_add" | "digital_sub" =>
            numeric(source.kind) && sources(1).kind == source.kind &&
            result.kind == (if operation == "digital_sub" then "SInt" else source.kind) &&
            formula("static_integer_max", sources, 1L)
          case "digital_mul" =>
            numeric(source.kind) && sources(1).kind == source.kind && result.kind == source.kind &&
            formula("analog_add", sources)
          case "digital_div" | "digital_rem" =>
            requireValid(
              inputs(1).constant.exists(_ != 0),
              "division and remainder require an independently proven exact nonzero divisor",
              path
            )
            numeric(source.kind) && sources(1).kind == source.kind && result.kind == source.kind &&
            (if operation == "digital_div" && source.kind == "SInt" then
               widthRelation(source, result, 1, path)
             else equalWidth(source, result))
          case "digital_min" | "digital_max" =>
            numeric(source.kind) && sources(1).kind == source.kind && result.kind == source.kind &&
            formula("static_integer_max", sources)
          case "digital_neg" =>
            numeric(source.kind) && result.kind == "SInt" &&
            formula("analog_add", Vector(source, literalWidth(1)))
          case _ if comparison =>
            source.kind == sources(1).kind &&
            (numeric(source.kind) ||
              (Set("digital_eq", "digital_ne").contains(operation) &&
                source.kind == "Bits" && equalWidth(source, sources(1))))
          case "digital_and" | "digital_or" | "digital_xor" | "digital_not" =>
            sources.forall(other => other.kind == source.kind && equalWidth(source, other)) &&
            result.kind == source.kind && equalWidth(source, result)
          case "digital_concat" =>
            result.kind == "Bits" && formula("analog_add", sources)
          case "digital_shl" | "digital_shr" | "digital_logical_shr" =>
            amount = staticIndex(1)
            source.kind == result.kind &&
            (if operation == "digital_shl" && amount != 0 then
               formula("analog_add", Vector(source, literalWidth(amount)))
             else equalWidth(source, result))
          case "digital_extract" =>
            lower = staticIndex(1)
            upper = staticIndex(2)
            upper > lower && upper <= source.lower && result.kind == "Bits" &&
            equalWidth(result, literalWidth(upper - lower))
          case _ => false
        requireValid(
          valid,
          "digital combinational expression violates exact kind, width or static range proof",
          path
        )
        def at(index: Int): Option[BigInt] = inputs(index).constant
        def pair(function: (BigInt, BigInt) => BigInt): Option[BigInt] =
          for left <- at(0); right <- at(1) yield function(left, right)
        def test(function: (BigInt, BigInt) => Boolean): Option[BigInt] =
          pair((left, right) => if function(left, right) then BigInt(1) else BigInt(0))
        def fixedWidth(info: ScalarType): Option[Int] =
          Option.when(info.lower == info.upper && info.upper <= 4096)(info.upper.toInt)
        def bits(number: BigInt, width: Int): BigInt = number & ((BigInt(1) << width) - 1)
        def decoded(number: BigInt, width: Int): BigInt =
          val encoded = bits(number, width)
          if result.kind == "SInt" && encoded.testBit(width - 1) then
            encoded - (BigInt(1) << width)
          else encoded
        val constant = (operation match
          case "digital_add" => pair(_ + _)
          case "digital_sub" => pair(_ - _)
          case "digital_mul" => pair(_ * _)
          case "digital_div" => pair(_ / _)
          case "digital_rem" => pair(_ % _)
          case "digital_min" => pair(_.min(_))
          case "digital_max" => pair(_.max(_))
          case "digital_neg" => at(0).map(-_)
          case "digital_eq" => test(_ == _)
          case "digital_ne" => test(_ != _)
          case "digital_lt" => test(_ < _)
          case "digital_le" => test(_ <= _)
          case "digital_gt" => test(_ > _)
          case "digital_ge" => test(_ >= _)
          case "digital_and" => pair(_ & _)
          case "digital_or" => pair(_ | _)
          case "digital_xor" => pair(_ ^ _)
          case "digital_not" =>
            for number <- at(0); width <- fixedWidth(result) yield decoded(~number, width)
          case "digital_concat" =>
            for
              left <- at(0); right <- at(1)
              leftWidth <- fixedWidth(source); rightWidth <- fixedWidth(sources(1))
            yield (bits(left, leftWidth) << rightWidth) | bits(right, rightWidth)
          case "digital_extract" if upper - lower <= 4096 =>
            at(0).map(number => bits(number >> lower.toInt, (upper - lower).toInt))
          case "digital_shl" =>
            at(0).filter(number => amount <= 4096 || number == 0).map(_ << amount.toInt)
          case "digital_shr" => at(0).map(_ >> amount.toInt)
          case "digital_logical_shr" if amount == 0 => at(0)
          case "digital_logical_shr" if source.kind != "SInt" || inputs.head.nonnegative =>
            at(0).map(_ >> amount.toInt)
          case "digital_logical_shr" =>
            for number <- at(0); width <- fixedWidth(source)
            yield decoded(bits(number, width) >> amount.toInt, width)
          case _ => None
        ).filter(_.bitLength <= 4096)
        val nonnegative = result.kind != "SInt" || constant.exists(_ >= 0) ||
          (Set("digital_add", "digital_mul").contains(operation) && inputs.forall(_.nonnegative)) ||
          (Set("digital_shl", "digital_shr").contains(operation) && inputs.head.nonnegative) ||
          (operation == "digital_logical_shr" && (amount > 0 || inputs.head.nonnegative))
        Proof(constant, nonnegative)

    /** Topological emission scales with unique nodes and edges, never with use-tree expansion. */
    def renderModule(
        module: KernelModuleSnapshot,
        nativeType: (String, String) => String,
        computedType: (String, String) => String,
        scalarType: (String, String) => Option[ScalarType],
        widthRelation: (ScalarType, ScalarType, Int, String) => Boolean,
        widthFormula: (ScalarType, String, Vector[ScalarType], Long, String) => Boolean,
        exactValueType: (String, String, String) => Boolean,
        widthFamily: (String, String, String) => Boolean,
        structType: (String, String) => (String, Vector[(String, String)]),
        vectorType: (String, String) => (String, Vector[String]),
        declarationValue: (
            KernelDeclarationSnapshot,
            String,
            String,
            Option[(String, String)]
        ) => Vector[String],
        declarationInitializer: KernelDeclarationSnapshot => Option[String],
        metadata: (String, Boolean) => String,
        sourceLocation: String => String,
        projectionValue: (String, String => (String, String)) => Projection,
        declarationNativeType: (KernelDeclarationSnapshot, String) => String,
        specializedValue: (
            KernelDigitalExpressionSnapshot,
            String,
            String,
            Vector[(String, String)]
        ) => Projection,
        additionalRoots: Vector[String],
        staticValue: (String, Option[String], mutable.ArrayBuffer[String]) => (String, String),
        prepareType: (String, String, Option[String], mutable.ArrayBuffer[String]) => Unit,
        bindStaticArgument: (String, (String, String)) => Unit,
        withCollectionContexts: (KernelDigitalCollectionSnapshot, () => Unit) => Unit,
        reductionArguments: KernelDigitalCollectionSnapshot => Option[Vector[(String, String)]],
        collectionArgumentMatches: (
            KernelDigitalCollectionSnapshot,
            KernelDigitalRegionArgument,
            String
        ) => Boolean,
        collectionResultMatches: (KernelDigitalCollectionSnapshot, String) => Boolean,
        withLoopContext: (KernelDigitalLoopSnapshot, () => Unit) => Unit,
        renderAssignment: (
            KernelDigitalAssignmentSnapshot,
            (String, String),
            (String, String)
        ) => String,
        effectMetadata: (String, Int) => String
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
      def emit(
          path: String,
          depth: Int,
          region: Option[String],
          output: mutable.ArrayBuffer[String]
      ): (String, String) =
        requireValid(depth <= 128, "digital expression graph exceeds depth limit", path)
        requireVisible(path, module.path, region, path)
        if iterationIndices.contains(path) then
          values.getOrElseUpdate(
            path, {
              requireValid(active.add(path), "iteration index graph contains a cycle", path)
              val index = iterationIndices(path)
              val operands = index.operands.map(emit(_, depth + 1, region, output))
              requireValid(operands.forall(_._2 == "i64"), "iteration operands must be i64", path)
              val result = s"%digital_index_$ordinal"
              ordinal += 1
              output += operation(
                "nodal.iteration_index",
                result,
                "i64",
                operands,
                s"{operator_name = ${quoted(index.operation)}, metadata = ${metadata(path, false)}}",
                sourceLocation(path)
              )
              heights.update(path, index.operands.map(heights).max + 1)
              proofs.update(path, Unknown)
              active.remove(path)
              result -> "i64"
            }
          )
        else if staticExpressions.contains(path) ||
          declarations.get(path).exists(value =>
            value._2.kind == "parameter" && value._2.dataType.contains("Integer")
          )
        then
          val value = staticValue(path, region, output)
          values.update(path, value)
          heights.update(path, 0)
          proofs.update(path, Unknown)
          value
        else
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
              requireValid(
                region.isEmpty || (sourceType != "Integer" && sourceType != "Real"),
                "collection expressions require finite digital values",
                path
              )
              prepareType(sourceType, path, region, output)
              expressions.get(path).flatMap(_.structure).foreach(value =>
                prepareType(value.schemaType, path, region, output)
              )
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
                  val operands = expression.operands.map(emit(_, depth + 1, region, output))
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
                  if expression.operation == DigitalCollectionContract.Operation then
                    val collection = collections(path)
                    val vectors =
                      collection.inputs.map(input => vectorType(expressionType(input), path))
                    def shaped(element: String, dimensions: Vector[String]): String =
                      s"Vec($element;${dimensions.mkString("x")})"
                    if collection.kind == "zip_with" then
                      requireValid(
                        exactValueType(
                          shaped("Bool", vectors(0)._2),
                          shaped("Bool", vectors(1)._2),
                          path
                        ),
                        "collection zip requires identical complete dimensions",
                        path
                      )
                    val reduction = reductionArguments(collection)
                    val primary = reduction.getOrElse(collection.seed match
                      case Some(seed) =>
                        Vector(expressionType(seed) -> "accumulator", vectors.head._1 -> "element")
                      case None => vectors.zipWithIndex.map((value, index) =>
                          value._1 -> (if vectors.size == 1 then "element" else s"element$index")
                        ))
                    val expected = primary ++ collection.captures.map: input =>
                      val dataType = expressionType(input)
                      dataType -> (if dataType == "Integer" then "static_capture" else "capture")
                    requireValid(
                      collection.arguments.size == expected.size,
                      "collection block arguments do not match operand segments",
                      path
                    )
                    val local = mutable.ArrayBuffer.empty[String]
                    var blockArguments = Vector.empty[String]
                    var yielded = "" -> ""
                    val startOrdinal = ordinal
                    val bodyPaths = bodyValuePaths(path)
                    withCollectionContexts(
                      collection,
                      () => {
                        ordinal = startOrdinal
                        local.clear()
                        bodyPaths.foreach: identity =>
                          values.remove(identity)
                          heights.remove(identity)
                          proofs.remove(identity)
                        blockArguments = collection.arguments.zip(expected).zipWithIndex.map:
                          case ((argument, (expectedType, role)), index) =>
                            requireValid(
                              argument.role == role &&
                                collectionArgumentMatches(collection, argument, expectedType) &&
                                argument.dataType != "Real" &&
                                (argument.dataType != "Integer" || role == "static_capture" ||
                                  role.startsWith("left_width:") ||
                                  role.startsWith("right_width:")),
                              "collection block argument must retain the complete finite operand type and canonical role",
                              argument.path
                            )
                            val name = s"${result}_arg$index"
                            val argumentType = computedType(argument.dataType, argument.path)
                            values.update(argument.path, name -> argumentType)
                            if argument.dataType == "Integer" then
                              bindStaticArgument(argument.path, name -> argumentType)
                            val capture = collection.captures.lift(index - primary.size)
                            heights.update(argument.path, capture.map(heights(_) + 1).getOrElse(0))
                            proofs.update(
                              argument.path,
                              capture.map(proofs).getOrElse(Proof(
                                None,
                                scalarType(argument.dataType, argument.path).exists(_.kind !=
                                  "SInt")
                              ))
                            )
                            s"$name: $argumentType"
                        val bodyExpressions = byRegion.getOrElse(Some(path), Vector.empty)
                        // Retained unused static operations are part of the closed pure body too.
                        staticByRegion.getOrElse(Some(path), Vector.empty).sortBy(_.path).foreach(
                          value =>
                            staticValue(value.path, Some(path), local)
                        )
                        bodyExpressions.filterNot(value =>
                          DigitalCombinationalContract.ReachableBooleanOperations.contains(
                            value.operation
                          )
                        ).sortBy(_.path).foreach(value =>
                          emit(value.path, depth + 1, Some(path), local)
                        )
                        yielded = emit(collection.yieldValue, depth + 1, Some(path), local)
                        val yieldedType = expressionType(collection.yieldValue)
                        requireValid(
                          yieldedType != "Integer" && yieldedType != "Real",
                          "collection yield requires a finite digital value",
                          path
                        )
                        if reduction.isEmpty then
                          requireValid(
                            collectionResultMatches(collection, yieldedType),
                            "collection result must retain exact element type, complete seed type and every input dimension",
                            path
                          )
                        bodyExpressions.filter(value =>
                          DigitalCombinationalContract.ReachableBooleanOperations.contains(
                            value.operation
                          )
                        ).foreach(value =>
                          requireValid(
                            values.contains(value.path),
                            "ordinary Boolean operations require a reachable digital value root",
                            value.path
                          )
                        )
                        local +=
                          s"${quoted("nodal.collection_yield")}(${yielded._1}) <{metadata = ${metadata(path, false)}}> : (${yielded._2}) -> ()${sourceLocation(path)}"
                      }
                    )
                    val attributes =
                      s"{kind = ${quoted(collection.kind)}, region_id = ${quoted(path)}, " +
                        s"operandSegmentSizes = array<i32: ${collection.inputs.size}, ${collection.seed.size}, ${collection.captures.size}>, metadata = ${metadata(path, true)}}"
                    val block = s"^bb0(${blockArguments.mkString(", ")}):\n" + local.mkString("\n")
                    val nested = block.linesIterator.map("  " + _).mkString("\n")
                    val text =
                      s"$result = ${quoted("nodal.collection")}(${operands.map(_._1).mkString(", ")}) <$attributes> ({\n$nested\n}) : " +
                        s"(${operands.map(_._2).mkString(", ")}) -> $dataType${sourceLocation(path)}"
                    (
                      Vector(text),
                      math.max(height, heights(collection.yieldValue) + 1),
                      Proof(None, unsigned)
                    )
                  else if specialized then
                    requireValid(
                      region.isEmpty,
                      "interface effects are not pure collection operations",
                      path
                    )
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
                    val selectors = selectorsByPath.getOrElse(path, Vector.empty)
                      .filter(_.toLongOption.isEmpty)
                    val expectedDependencies = shapeByPath(path)._2 +: selectors
                    val dependencies = mutable.ArrayBuffer.empty[String]
                    val projected = projectionValue(
                      path,
                      dependency =>
                        requireValid(
                          expectedDependencies.contains(dependency),
                          "shape resolver requested a different immediate input",
                          path
                        )
                        dependencies += dependency
                        emit(dependency, depth + 1, region, output)
                    )
                    requireValid(
                      dependencies.toVector == expectedDependencies &&
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
                  else if DigitalAggregateContract.Operations.contains(expression.operation) then
                    val evidence = expression.structure.get
                    def finite(dataType: String): Boolean =
                      dataType == "Bool" || scalarType(dataType, path).nonEmpty ||
                        dataType.startsWith("Enum(") || dataType.startsWith("Vec(") ||
                        dataType.startsWith("Struct(")
                    requireValid(
                      finite(expression.dataType) &&
                        expression.operands.forall(input => finite(expressionType(input))),
                      "Struct operations cannot expose standalone Real or Integer leaves",
                      path
                    )
                    val schema = structType(evidence.schemaType, path)
                    requireValid(
                      expression.literal.isEmpty && expression.enumIdentity.isEmpty &&
                        expression.caseIds.isEmpty,
                      "Struct operations cannot carry scalar or enum facts",
                      path
                    )
                    val fieldAttributes = if expression.operation == "digital_struct_get" then
                      requireValid(
                        expression.operands.size == 1 && evidence.ordinal.nonEmpty &&
                          evidence.name.nonEmpty,
                        "Struct get requires one receiver and an exact member",
                        path
                      )
                      val receiver = expressionType(expression.operands.head)
                      val fields = structType(receiver, path)._2
                      val index = evidence.ordinal.get
                      requireValid(
                        widthFamily(evidence.schemaType, receiver, path) && index >= 0 &&
                          index < fields.size && fields(index)._1 == evidence.name.get &&
                          schema._2(index)._1 == evidence.name.get &&
                          exactValueType(fields(index)._2, expression.dataType, path),
                        "Struct get schema, ordinal, name or actual field result type differs",
                        path
                      )
                      s", field_ordinal = $index : i64, field_name = ${quoted(evidence.name.get)}"
                    else
                      val fields = structType(expression.dataType, path)._2
                      requireValid(
                        evidence.ordinal.isEmpty && evidence.name.isEmpty &&
                          widthFamily(evidence.schemaType, expression.dataType, path) &&
                          expression.operands.size == fields.size &&
                          fields.zip(expression.operands).forall((field, input) =>
                            exactValueType(field._2, expressionType(input), path)
                          ),
                        "Struct make requires the declared family and exact ordered field operands",
                        path
                      )
                      ""
                    val attributes = s"{operator_name = ${quoted(expression.operation)}, " +
                      s"struct_schema = ${nativeType(evidence.schemaType, path)}$fieldAttributes, " +
                      s"metadata = ${metadata(path, true)}}"
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
                      Proof(None, unsigned)
                    )
                  else if expression.operation.startsWith("digital_") ||
                    DigitalCombinationalContract.ReachableBooleanOperations.contains(
                      expression.operation
                    )
                  then
                    val proof =
                      if expression.operation == DigitalMaterializationContract.Operation then
                        requireValid(
                          exactValueType(
                            expressionType(expression.operands.head),
                            expression.dataType,
                            path
                          ) &&
                            (expression.dataType == "Bool" ||
                              scalarType(expression.dataType, path).nonEmpty ||
                              expression.dataType.startsWith("Enum(") ||
                              expression.dataType.startsWith("Vec(") ||
                              expression.dataType.startsWith("Struct(")),
                          "digital alias must preserve one exact complete finite value type",
                          path
                        )
                        proofs(expression.operands.head)
                      else if DigitalScalarContract.Operations.contains(expression.operation) then
                        requireValid(
                          expression.operands.size == 1,
                          "digital conversion requires one operand",
                          path
                        )
                        scalarConversion(
                          expression,
                          expressionType(expression.operands.head),
                          proofs(expression.operands.head),
                          scalarType,
                          widthRelation
                        )
                      else
                        combinational(
                          expression,
                          expression.operands.map(proofs),
                          scalarType,
                          widthRelation,
                          widthFormula,
                          exactValueType
                        )
                    val attributes =
                      s"{operator_name = ${quoted(expression.operation)}, metadata = ${metadata(path, true)}}"
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
                    val spelling = if expression.dataType == "Bool" then
                      if expression.literal.contains("true") then "1" else "0"
                    else expression.literal.get
                    val attributes =
                      s"{value = $literal, spelling = ${quoted(spelling)}, metadata = ${metadata(path, false)}}"
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
                      exactValueType(expressionType(input), sourceType, path),
                      "digital initializer requires the exact storage type",
                      path
                    )
                  val initial = initialPath.map(emit(_, depth + 1, region, output))
                  val height = initialPath.map(heights(_) + 1).getOrElse(0)
                  (
                    declarationValue(declaration, result, dataType, initial),
                    height,
                    Proof(None, unsigned)
                  )
              output ++= text
              val value = actualResult -> dataType
              values.update(path, value)
              heights.update(path, height)
              proofs.update(path, proof)
              active.remove(path)
              value
      val moduleExpressions = byOwner.getOrElse(module.path, Vector.empty).filter(_.region.isEmpty)
      moduleExpressions.filterNot(value =>
        DigitalCombinationalContract.ReachableBooleanOperations.contains(value.operation)
      ).sortBy(_.path).foreach(value => emit(value.path, 0, None, lines))
      additionalRoots.distinct.sorted.foreach: path =>
        val owner = expressions.get(path).map(_.owner).orElse(declarations.get(path).map(_._1))
        requireValid(
          owner.contains(module.path),
          "specialized digital root has another owner",
          path
        )
        emit(path, 0, None, lines)
      module.declarations.filter(value => Set("wire", "register").contains(value.kind))
        .sortBy(_.path).foreach(value => emit(value.path, 0, None, lines))
      val effects = assignmentsByRegion.getOrElse(module.path -> None, Vector.empty)
        .map(value => (value.sourceOrdinal, value.path, Left(value))) ++
        loopsByOwner.getOrElse(module.path, Vector.empty)
          .map(value => (value.sourceOrdinal, value.path, Right(value)))
      effects.sortBy(value => (value._1, value._2)).foreach:
        case (_, _, Left(assignment)) =>
          val target = emit(assignment.target, 0, None, lines)
          val value = emit(assignment.value, 0, None, lines)
          lines += renderAssignment(assignment, target, value)
        case (_, _, Right(loop)) =>
          val header = Vector(loop.lower, loop.upperExclusive, loop.step).map: path =>
            if path.toLongOption.nonEmpty then staticValue(path, None, lines)
            else emit(path, 0, None, lines)
          requireValid(
            header.forall(_._2 == "i64"),
            "loop header requires static i64 operands",
            loop.path
          )
          val captures = loop.captures.map(emit(_, 0, None, lines))
          val local = mutable.ArrayBuffer.empty[String]
          val blockArguments = loop.arguments.zipWithIndex.map: (argument, index) =>
            val name = s"%loop_${ordinal}_arg$index"
            val dataType = if index == 0 then "i64" else captures(index - 1)._2
            values.update(argument.path, name -> dataType)
            heights.update(
              argument.path,
              if index == 0 then 0 else heights(loop.captures(index - 1)) + 1
            )
            proofs.update(
              argument.path,
              if index == 0 then Unknown else proofs(loop.captures(index - 1))
            )
            if argument.dataType == "Integer" then
              bindStaticArgument(argument.path, name -> dataType)
            s"$name: $dataType"
          ordinal += 1
          withLoopContext(
            loop,
            () => {
              // Source order belongs to effects; each RHS DAG is materialized at its actual write.
              assignmentsByRegion.getOrElse(module.path -> Some(loop.path), Vector.empty)
                .sortBy(_.sourceOrdinal).foreach: assignment =>
                  val target = emit(assignment.target, 0, Some(loop.path), local)
                  val value = emit(assignment.value, 0, Some(loop.path), local)
                  local += renderAssignment(assignment, target, value)
              staticByRegion.getOrElse(Some(loop.path), Vector.empty).sortBy(_.path).foreach(
                value =>
                  emit(value.path, 0, Some(loop.path), local)
              )
              indicesByRegion.getOrElse(loop.path, Vector.empty).sortBy(_.path).foreach(value =>
                emit(value.path, 0, Some(loop.path), local)
              )
              byRegion.getOrElse(Some(loop.path), Vector.empty).sortBy(_.path).foreach(value =>
                emit(value.path, 0, Some(loop.path), local)
              )
            }
          )
          local += s"${quoted("nodal.hardware_loop_end")}() : () -> ()"
          // Transport follows the actual first SSA use, while source capture identities remain
          // associated with their original operand. Do not introduce a fake use for dead captures.
          val argumentNames = blockArguments.map(_.takeWhile(_ != ':'))
          val argumentOrdinals = argumentNames.zipWithIndex.toMap
          val capturePattern = "%loop_[0-9]+_arg[0-9]+(?![A-Za-z0-9_])".r
          val operandPattern = "\"nodal\\.[a-z0-9_]+\"\\(([^)]*)\\)".r
          val used = local.iterator.flatMap(line => operandPattern.findAllMatchIn(line))
            .flatMap(value => capturePattern.findAllIn(value.group(1)))
            .flatMap(argumentOrdinals.get).filter(_ != 0).toVector.distinct
          requireValid(
            used.size == captures.size,
            "loop capture has no actual body SSA use",
            loop.path
          )
          val captureOrder = used.map(_ - 1)
          val orderedArguments = blockArguments.head +: used.map(blockArguments)
          val operands = header ++ captureOrder.map(captures)
          val maximum = loop.maximum.map(value => s", maximum = $value : i64").getOrElse("")
          val attributes = s"{contract_version = \"bounded-digital-v1\", effect_policy = \"ordered\", " +
            s"region_id = ${quoted(loop.path)}, induction_path = ${quoted(loop.arguments.head.path)}, " +
            s"induction = \"i\"$maximum, " +
            s"metadata = ${effectMetadata(loop.path, loop.sourceOrdinal)}}"
          val block = s"^bb0(${orderedArguments.mkString(", ")}):\n" + local.mkString("\n")
          val nested = block.linesIterator.map("  " + _).mkString("\n")
          lines += s"${quoted("nodal.hardware_loop")}(${operands.map(_._1).mkString(", ")}) <$attributes> " +
            s"({\n$nested\n}) : (${operands.map(_._2).mkString(", ")}) -> ()${sourceLocation(loop.path)}"
      moduleExpressions.filter(value =>
        DigitalCombinationalContract.ReachableBooleanOperations.contains(value.operation)
      ).foreach(value =>
        requireValid(
          values.contains(value.path),
          "ordinary Boolean operations require a reachable digital value root",
          value.path
        )
      )
      Rendered(lines.toVector, values.iterator.filter((path, _) => valueRegion(path).isEmpty).toMap)
