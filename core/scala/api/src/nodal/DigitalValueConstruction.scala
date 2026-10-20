package nodal

import java.util.IdentityHashMap

import scala.collection.mutable

/** Captures reachable digital value graphs, enum ABI and actual operand edges once per construction
  * transaction.
  */
private[nodal] object DigitalValueConstruction:
  def isDigitalValue(expression: KernelExpr[?]): Boolean =
    expression.literal.nonEmpty || expression.operation.exists(operation =>
      operation.startsWith(DigitalEnumContract.Prefix) ||
        DigitalScalarContract.Operations.contains(operation) ||
        operation == DigitalMaterializationContract.Operation ||
        DigitalCombinationalContract.Operations.contains(operation) ||
        DigitalAggregateContract.Operations.contains(operation) ||
        DigitalCombinationalContract.ReachableBooleanOperations.contains(operation) ||
        DigitalInterfaceContract.ValueOperations.contains(operation) ||
        (operation == DigitalCollectionContract.Operation ||
          operation == StructuralIfRuntime.Operation ||
          operation == SequentialRuntime.PredicateOperation || operation == "memory_read" ||
          ExecutableCrossingContract.Operations(operation) ||
          OwnedClockContract.Operations(operation) ||
          Set("reset_boundary", "rdc_sync", "reset_combine").contains(operation) ||
          FlatFsmRuntime.Operations(operation))
    )

  def snapshot(
      declarationTypes: Vector[DataType[?]],
      valueRoots: Vector[Expr[?]],
      projectionPaths: Set[String],
      expressions: Vector[(KernelExpr[?], String, String, Long)],
      renderType: (DataType[?], Long, Option[String]) => String,
      regionOf: KernelExpr[?] => Option[String],
      operandPath: (KernelExpr[?], Any) => Option[String],
      argumentPaths: Set[String],
      memoryPort: KernelExpr[?] => Option[KernelMemoryPortSnapshot],
      crossing: KernelExpr[?] => Option[KernelExecutableCrossingSnapshot],
      reset: KernelExpr[?] => Option[KernelResetSnapshot],
      clock: KernelExpr[?] => Option[KernelClockSnapshot]
  ): (Vector[ResolvedEnumAbi], Vector[KernelDigitalExpressionSnapshot]) =
    def fail(message: String): Nothing = DigitalEnumContract.fail(20, message)
    val definitions = mutable.LinkedHashMap.empty[String, ResolvedEnumAbi]
    def register(abi: ResolvedEnumAbi): Unit =
      if abi.width <= 0 || abi.width > DigitalEnumContract.MaximumWidth || abi.cases.isEmpty ||
        abi.cases.size > DigitalEnumContract.MaximumCases ||
        abi.cases.map(_.id).distinct.size != abi.cases.size ||
        abi.cases.map(_.code).distinct.size != abi.cases.size ||
        abi.cases.exists(value => value.code < 0 || value.code.bitLength > abi.width) ||
        abi.cases.iterator.map(_.code.bitLength.toLong).sum > DigitalEnumContract.MaximumCodeBits
      then fail("malformed resolved enum ABI")
      if DigitalEnumContract.abiHash(abi.identity, abi.width, abi.cases) != abi.hash then
        fail("resolved enum ABI hash does not match its cases")
      definitions.get(abi.identity) match
        case Some(previous) if previous.hash != abi.hash =>
          fail(s"enum '${abi.identity}' has conflicting hardware encodings in one design")
        case _ => val _ = definitions.getOrElseUpdate(abi.identity, abi)
      ()

    val visitedTypes = new IdentityHashMap[DataType[?], java.lang.Integer]()
    val types = mutable.ArrayDeque.empty[(DataType[?], Int)]
    declarationTypes.foreach(value => types.append((value, 0)))
    expressions.foreach: (expression, _, _, _) =>
      CandidateRuntime.expressionDataType(expression).foreach(value => types.append((value, 0)))
      expression.operands.collect { case metadata: EnumOperationMetadata => metadata }
        .foreach(value => register(value.abi))
      expression.operands.collect { case metadata: StructOperationMetadata => metadata }
        .foreach(value => types.append((value.schema, 0)))
    var typeNodes = 0
    while types.nonEmpty do
      val (current, depth) = types.removeLast()
      if depth > 128 then fail("enum-containing type nesting exceeds 128")
      if Option(visitedTypes.get(current)).forall(_.intValue < depth) then
        visitedTypes.put(current, depth)
        typeNodes += 1
        if typeNodes > 65536 then fail("enum-containing type graph exceeds 65536 nodes")
        val descriptor = CandidateRuntime.typeDescriptor(current)
        descriptor.arguments.foreach:
          case abi: ResolvedEnumAbi => register(abi)
          case nested: DataType[?] => types.append((nested, depth + 1))
          case fields: Seq[?] => fields.foreach:
              case field: StructField[?] => types.append((field.dataType, depth + 1))
              case nested: DataType[?] => types.append((nested, depth + 1))
              case _ => ()
          case _ => ()

    val byExpression = new IdentityHashMap[KernelExpr[?], (String, String, Long)]()
    expressions.foreach: (expression, path, owner, handle) =>
      byExpression.put(expression, (path, owner, handle))
    val reachable = new IdentityHashMap[KernelExpr[?], java.lang.Integer]()
    val pending = mutable.ArrayDeque.empty[(KernelExpr[?], Int)]
    expressions.foreach: (expression, _, _, _) =>
      if expression.operation.exists(operation =>
          operation.startsWith(DigitalEnumContract.Prefix) ||
            DigitalScalarContract.Operations.contains(operation) ||
            operation == DigitalMaterializationContract.Operation ||
            DigitalCombinationalContract.Operations.contains(operation) ||
            DigitalAggregateContract.Operations.contains(operation) ||
            DigitalInterfaceContract.ValueOperations.contains(operation) ||
            (operation == DigitalCollectionContract.Operation ||
              operation == StructuralIfRuntime.Operation ||
              operation == SequentialRuntime.PredicateOperation || operation == "memory_read" ||
              ExecutableCrossingContract.Operations(operation) ||
              OwnedClockContract.Operations(operation) ||
              (Set("reset_boundary", "rdc_sync", "reset_combine").contains(operation) &&
                reset(expression).nonEmpty) ||
              FlatFsmRuntime.Operations(operation))
        )
      then
        pending.append((expression, 0))
    valueRoots.foreach:
      case expression: KernelExpr[?] => pending.append((expression, 0))
      case _ => ()
    var nodes = 0
    while pending.nonEmpty do
      val (expression, depth) = pending.removeLast()
      if depth > 128 then fail("digital expression graph depth exceeds 128")
      if Option(reachable.get(expression)).forall(_.intValue < depth) then
        reachable.put(expression, depth)
        nodes += 1
        if nodes > 65536 then fail("digital expression graph exceeds 65536 nodes")
        if !byExpression.containsKey(expression) then fail("digital operand has no captured owner")
        if expression.literal.isEmpty && !argumentPaths.contains(byExpression.get(expression)._1)
        then
          val operands = if projectionPaths.contains(byExpression.get(expression)._1) then
            expression.operands.take(1)
          else expression.operands
          operands.foreach:
            case operand: KernelExpr[?]
                if !operand.resultType.contains(KernelTypeDescriptor("Integer")) =>
              pending.append((operand, depth + 1))
            case _ => ()

    val snapshots = expressions.collect:
      case (expression, path, owner, handle)
          if reachable.containsKey(expression) && !argumentPaths.contains(path) =>
        val completion = expression.operands.collect { case value: FlatFsmCompletionMetadata =>
          value
        }
        val metadata = expression.operands.collect { case value: EnumOperationMetadata => value } ++
          completion.map(value =>
            EnumOperationMetadata(
              value.record.canonical,
              value.record.states.filter(_.terminal).map(_.caseId).toVector.sorted
            )
          )
        val structure = expression.operands.collect { case value: StructOperationMetadata => value }
        val isStruct = expression.operation.exists(DigitalAggregateContract.Operations.contains)
        if isStruct != structure.nonEmpty || structure.size > 1 ||
          (isStruct && expression.operands.lastOption != structure.headOption)
        then fail("Struct operation requires one final schema evidence record")
        val isEnum = expression.operation.exists(_.startsWith(DigitalEnumContract.Prefix)) ||
          completion.nonEmpty
        val isProjection = projectionPaths.contains(path)
        if expression.operation.contains(DigitalMaterializationContract.Operation) !=
            expression.materializationIntent.nonEmpty
        then fail("one typed intent is required exactly on a digital alias")
        expression.materializationIntent.foreach(DigitalMaterializationContract.validate)
        if isEnum &&
          (metadata.size != 1 ||
            (completion.isEmpty && expression.operands.lastOption != metadata.headOption) ||
            (completion.nonEmpty &&
              (completion.size != 1 || expression.operands.lastOption != completion.headOption)))
        then
          fail("enum expression requires one final resolved ABI record")
        if !isEnum && !isProjection && !isDigitalValue(expression)
        then
          fail("operand expression is outside the implemented typed digital profile")
        if !isEnum && metadata.nonEmpty then fail("non-enum expression carries enum metadata")
        val operands = if reset(expression).nonEmpty then reset(expression).get.inputs
        else if expression.literal.nonEmpty || isProjection then Vector.empty
        else
          expression.operands.filterNot(value =>
            value.isInstanceOf[EnumOperationMetadata] ||
              value.isInstanceOf[StructOperationMetadata] ||
              value.isInstanceOf[FlatFsmCompletionMetadata] || value.isInstanceOf[MemoryPortCapture]
          ).map: operand =>
            operand match
              case candidate: KernelExpr[?] =>
                val captured = Option(byExpression.get(candidate)).getOrElse(
                  fail("digital operand has no captured owner")
                )
                if captured._2 != owner then fail("digital operand belongs to another module")
              case _ => ()
            operandPath(
              expression,
              operand
            ).getOrElse(fail("digital operand has no semantic identity"))
        val dataType = CandidateRuntime.expressionDataType(expression).getOrElse(
          fail("digital operand has no exact data type")
        )
        KernelDigitalExpressionSnapshot(
          path,
          owner,
          if isProjection then "shape_reference"
          else if completion.nonEmpty then "enum_is_one_of"
          else
            expression.operation.orElse(expression.literal.map(_.kind)).getOrElse(
              fail("digital operand has no operation")
            )
          ,
          reset(expression).map(_.inputs).getOrElse(operands),
          renderType(dataType, handle, regionOf(expression)),
          expression.literal.map(_.value),
          metadata.headOption.map(_.abi.identity),
          metadata.headOption.map(_.caseIds).getOrElse(Vector.empty),
          regionOf(expression),
          materializationIntent = expression.materializationIntent,
          structure = structure.headOption.map(value =>
            KernelStructOperationSnapshot(
              renderType(value.schema, handle, None),
              value.ordinal,
              value.name
            )
          ),
          memory = memoryPort(expression),
          crossing = crossing(expression),
          reset = reset(expression),
          clock = clock(expression)
        )
    (definitions.values.toVector.sortBy(_.identity), snapshots)
