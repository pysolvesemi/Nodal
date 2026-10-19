package nodal

import scala.collection.mutable

/** Query-local exact Integer substitution for finalized conservative child bindings. Declaration
  * and expression snapshots remain authoritative; interned IDs are never serialized.
  */
private[nodal] object StructuralParameterBinding:
  private def invalid(message: String): Nothing =
    scala.util.Failure[Nothing](
      new IllegalArgumentException(s"NODAL-SHAPE-043-007: $message")
    ).get

  final case class IntegerNode(
      literal: Option[Long],
      parameter: Option[String],
      operation: Option[String],
      operands: Vector[Int],
      bounds: IterationDomain.Bounds
  )
  final case class ResolvedDimensions(
      dimensions: Vector[Int],
      nodes: Vector[IntegerNode],
      existing: Map[Int, String]
  )

  private enum Key:
    case Literal(value: Long)
    case Parameter(path: String)
    case Apply(operation: String, operands: Vector[Int])

  private final case class Fact(key: Key, bounds: IterationDomain.Bounds)

  private final class Query(
      snapshot: ConstructionSnapshot,
      instance: Option[(String, KernelInstanceSnapshot)]
  ):
    private val modules = unique(snapshot.modules.map(value => value.path -> value), "module")
    private val expressions =
      unique(snapshot.parameterExpressions.map(value => value.path -> value), "expression")
    private val facts = mutable.ArrayBuffer.empty[Fact]
    private val ids = mutable.HashMap.empty[Key, Int]
    private val memo = mutable.HashMap.empty[(String, String, Boolean), Int]
    private val active = mutable.HashSet.empty[(String, String, Boolean)]
    private val overrides = instance.map(value =>
      unique(value._2.parameterBindings, "parameter override")
    ).getOrElse(Map.empty)

    private def unique[A](values: Vector[(String, A)], role: String): Map[String, A] =
      if values.map(_._1).distinct.size != values.size then invalid(s"duplicate $role identity")
      values.toMap

    private def intern(key: Key, bounds: IterationDomain.Bounds): Int =
      ids.getOrElseUpdate(key, { val id = facts.size; facts += Fact(key, bounds); id })

    private def literal(value: Long): Int =
      intern(Key.Literal(value), IterationDomain.Bounds(value, value))

    private def module(owner: String): KernelModuleSnapshot =
      modules.getOrElse(owner, invalid("Integer expression has no owned module"))

    private def declaration(owner: String, token: String): Option[KernelDeclarationSnapshot] =
      val candidates =
        module(owner).declarations.filter(value => value.path == token || value.name == token)
      if candidates.size > 1 then invalid("ambiguous parameter declaration")
      candidates.headOption

    private def attributes(value: KernelDeclarationSnapshot): Map[String, String] =
      unique(value.attributes, "parameter attribute")

    private def interval(
        owner: String,
        declaration: KernelDeclarationSnapshot,
        defaults: Boolean
    ): IterationDomain.Bounds =
      val attrs = attributes(declaration)
      val lower = attrs.get("integer_range_lower").map(resolve(owner, _, defaults))
      val upper = attrs.get("integer_range_upper").map(resolve(owner, _, defaults))
      (lower, upper) match
        case (Some(lo), Some(hi)) =>
          val low = facts(lo).bounds
          val high = facts(hi).bounds
          // Every substituted value must satisfy every legal constraint setting. Correlation
          // beyond exact DAG identity is deliberately not assumed by this finite proof.
          if low.upper > high.lower then invalid("formal Integer range is not provably ordered")
          IterationDomain.Bounds(low.upper, high.lower)
        case (None, None) => IterationDomain.Bounds(Long.MinValue, Long.MaxValue)
        case _ => invalid("formal Integer range requires both bounds")

    private def checkFormal(
        owner: String,
        declaration: KernelDeclarationSnapshot,
        value: Int,
        defaults: Boolean
    ): Unit =
      val allowed = interval(owner, declaration, defaults)
      val actual = facts(value).bounds
      if actual.lower < allowed.lower || actual.upper > allowed.upper then
        invalid(s"actual Integer envelope escapes formal '${declaration.path}' range")
      val attrs = attributes(declaration)
      if attrs.keys.exists(_.startsWith("exclude")) then
        invalid("parameter exclusion proof is unavailable for this captured constraint form")

    private def parameter(
        owner: String,
        declaration: KernelDeclarationSnapshot,
        defaults: Boolean
    ): Int =
      if declaration.kind != "parameter" || !declaration.dataType.contains("Integer") then
        invalid("structural extent requires an owned Integer parameter")
      val attrs = attributes(declaration)
      val selectedInstance = instance.filter(_._2.childModule == owner)
      if defaults then
        val result = resolve(
          owner,
          attrs.getOrElse("default", invalid("parameter default is missing")),
          defaults = true
        )
        checkFormal(owner, declaration, result, defaults = true)
        result
      else
        selectedInstance match
          case Some((parent, _)) =>
            val overrideValue = overrides.get(declaration.name)
            if overrideValue.nonEmpty &&
              (attrs.get("classification").contains("fixed") ||
                attrs.get("overridable").contains("false"))
            then
              invalid("fixed parameter cannot be overridden")
            val result = overrideValue match
              case Some(value) => resolve(parent, value, defaults = false)
              case None => resolve(
                  owner,
                  attrs.getOrElse("default", invalid("parameter default is missing")),
                  defaults = false
                )
            checkFormal(owner, declaration, result, defaults = false)
            result
          case None =>
            val bounds = interval(owner, declaration, defaults = false)
            intern(Key.Parameter(declaration.path), bounds)

    private def arithmetic(expression: KernelParameterExpressionSnapshot, defaults: Boolean): Int =
      if expression.dataType != "Integer" || expression.unit.exists(_.nonEmpty) then
        invalid("structural expression must be dimensionless Integer")
      expression.literal match
        case Some(value) =>
          if expression.operation != "integer" || expression.operands.nonEmpty then
            invalid("forged Integer literal expression")
          literal(value.toLongOption.getOrElse(invalid("Integer literal exceeds signed 64 bits")))
        case None =>
          compose(
            expression.operation,
            expression.operands.map(resolve(expression.owner, _, defaults))
          )

    def compose(operationName: String, operands: Vector[Int]): Int =
      val operation = IterationDomain.staticArithmetic(operationName).getOrElse(
        invalid("structural expression is not supported pure Integer arithmetic")
      )
      val arity = operation.arity
      if operands.size != arity then invalid("Integer expression has invalid arity")
      val bounds =
        if operation == IterationDomain.Arithmetic.Subtract && operands(0) == operands(1) then
          IterationDomain.Bounds(0L, 0L)
        else
          IterationDomain.arithmetic(operation, operands.map(facts(_).bounds))
            .fold(problem => invalid(problem.message), identity)
      simplifiedOperand(operation, operands) match
        case Some(index) => index
        case None if operands.forall(index => facts(index).key.isInstanceOf[Key.Literal]) =>
          literal(bounds.lower)
        case None => intern(Key.Apply(operationName, operands), bounds)

    private def simplifiedOperand(operation: IterationDomain.Arithmetic, operands: Vector[Int])
        : Option[Int] =
      if operation == IterationDomain.Arithmetic.Maximum && operands(0) == operands(1) then
        Some(operands(0))
      else if operation == IterationDomain.Arithmetic.ChooseZero then
        facts(operands(0)).key match
          case Key.Literal(value) => Some(operands(if value == 0L then 1 else 2))
          case _ if operands(1) == operands(2) => Some(operands(1))
          case _ => None
      else None

    def resolve(owner: String, token: String, defaults: Boolean = false): Int =
      token.toLongOption match
        case Some(value) if value.toString == token => literal(value)
        case Some(_) => invalid("Integer token is not canonical")
        case None =>
          val cacheKey = (owner, token, defaults)
          memo.get(cacheKey) match
            case Some(value) => value
            case None =>
              if active.size >= 512 || !active.add(cacheKey) then
                invalid("cyclic or excessively deep Integer dependency")
              try
                val result = declaration(owner, token) match
                  case Some(value) => parameter(owner, value, defaults)
                  case None =>
                    val expression = expressions.getOrElse(
                      token,
                      invalid(s"Integer dependency '$token' has no retained identity")
                    )
                    if expression.owner != owner then
                      invalid("Integer expression belongs to another module")
                    arithmetic(expression, defaults)
                memo.update(cacheKey, result)
                result
              finally { val _ = active.remove(cacheKey) }

    def checkedLiteral(owner: String, reference: String): Long =
      facts(resolve(owner, reference, defaults = true)).key match
        case Key.Literal(value) => value
        case _ => invalid("declaration default is not a checked Integer constant")

    def positive(index: Int): Unit =
      if facts(index).bounds.lower <= 0 then invalid("effective shape extent must remain positive")

    def exportDimensions(owner: String, dimensions: Vector[Int]): ResolvedDimensions =
      // Reuse is an identity lookup over facts already proved for the selected instance.
      // Unrelated Integer defaults do not acquire shape-envelope obligations merely because
      // they are present in the parent. Required consumers still go through resolve above.
      val existing = mutable.LinkedHashMap.empty[Int, String]
      val matched = mutable.HashMap.empty[String, Option[Int]]
      val visiting = mutable.HashSet.empty[String]
      def candidate(token: String): Option[Int] = matched.getOrElseUpdate(
        token,
        if visiting.size >= 512 || !visiting.add(token) then None
        else
          try
            token.toLongOption.filter(_.toString == token).flatMap(value =>
              ids.get(Key.Literal(value))
            )
              .orElse(declaration(owner, token).filter(value =>
                value.kind == "parameter" && value.dataType.contains("Integer")
              )
                .flatMap(value => ids.get(Key.Parameter(value.path))))
              .orElse(expressions.get(token).filter(value =>
                value.owner == owner &&
                  value.dataType == "Integer" && value.unit.forall(_.isEmpty)
              ).flatMap: expression =>
                expression.literal match
                  case Some(value)
                      if expression.operation == "integer" && expression.operands.isEmpty =>
                    value.toLongOption.flatMap(value => ids.get(Key.Literal(value)))
                  case Some(_) => None
                  case None =>
                    val operands = expression.operands.map(candidate)
                    if operands.exists(_.isEmpty) then None
                    else
                      val inputs = operands.flatten
                      ids.get(Key.Apply(expression.operation, inputs)).orElse:
                        val operation = IterationDomain.staticArithmetic(expression.operation)
                        operation.filter(op => inputs.size == op.arity)
                          .flatMap(op => simplifiedOperand(op, inputs))
                          .orElse(operation.filter(op => inputs.size == op.arity)
                            .filter(_ =>
                              inputs.forall(index => facts(index).key.isInstanceOf[Key.Literal])
                            )
                            .flatMap(op =>
                              IterationDomain.arithmetic(op, inputs.map(facts(_).bounds)).toOption
                            )
                            .flatMap(value => ids.get(Key.Literal(value.lower)))))
          finally { val _ = visiting.remove(token) }
      )
      (module(owner).declarations.filter(value =>
        value.kind == "parameter" && value.dataType.contains("Integer")
      ).map(_.path) ++
        snapshot.parameterExpressions.filter(_.owner == owner).map(_.path)).foreach: path =>
        candidate(path).foreach(id => existing.getOrElseUpdate(id, path))
      ResolvedDimensions(
        dimensions,
        facts.toVector.map: fact =>
          fact.key match
            case Key.Literal(value) =>
              IntegerNode(Some(value), None, None, Vector.empty, fact.bounds)
            case Key.Parameter(path) =>
              IntegerNode(None, Some(path), None, Vector.empty, fact.bounds)
            case Key.Apply(operation, operands) =>
              IntegerNode(None, None, Some(operation), operands, fact.bounds)
        ,
        existing.toMap
      )

    def validateOverrides(): Unit = instance.foreach: (_, value) =>
      val child = module(value.childModule)
      overrides.keys.foreach: name =>
        val declaration = child.declarations.find(
          _.name == name
        ).getOrElse(invalid("override names an unknown child parameter"))
        if declaration.kind != "parameter" then
          invalid("override names a non-parameter declaration")
        // Non-Integer overrides retain the inherited hierarchy verifier; structural dependencies
        // cannot consume them because resolve independently checks their scalar type.
        if declaration.dataType.contains("Integer") then {
          val _ = resolve(child.path, declaration.path)
        }

  def sameInteger(
      snapshot: ConstructionSnapshot,
      owner: String,
      left: String,
      right: String
  ): Boolean =
    val query = new Query(snapshot, None)
    query.resolve(owner, left) == query.resolve(owner, right)

  /** Compare a retained result with checked composition in the same query-local identity space.
    * Operation names are the captured static Integer opcodes, not renderer spellings.
    */
  def sameIntegerOperation(
      snapshot: ConstructionSnapshot,
      owner: String,
      resultToken: String,
      operation: String,
      operandTokens: Vector[String],
      increment: Long = 0L
  ): Boolean =
    val query = new Query(snapshot, None)
    val result = query.resolve(owner, resultToken)
    val composed = query.compose(operation, operandTokens.map(query.resolve(owner, _)))
    val expected = if increment == 0L then composed
    else query.compose("analog_add", Vector(composed, query.resolve(owner, increment.toString)))
    result == expected

  def defaultInteger(snapshot: ConstructionSnapshot, owner: String, reference: String): Long =
    new Query(snapshot, None).checkedLiteral(owner, reference)

  private def instancePort(
      snapshot: ConstructionSnapshot,
      ownerPath: String,
      instancePath: String,
      portPath: String
  ): (KernelModuleSnapshot, KernelInstanceSnapshot, KernelDeclarationSnapshot) =
    val owner =
      snapshot.modules.find(_.path == ownerPath).getOrElse(invalid("binding owner is missing"))
    val instance = owner.instances.find(_.path == instancePath)
      .getOrElse(invalid("binding is not owned by the exact immediate instance"))
    val child = snapshot.modules.find(_.path == instance.childModule)
      .getOrElse(invalid("child definition is missing"))
    val port = child.declarations.find(_.path == portPath)
      .getOrElse(invalid("binding does not name the declared child port"))
    if !Set("analog-input", "analog-output", "analog-inout").contains(port.kind) ||
      port.conservativeDimensions.isEmpty
    then
      invalid("instance array requires a conservative child boundary declaration")
    (owner, instance, port)

  def resolveDimensions(
      snapshot: ConstructionSnapshot,
      owner: String,
      instance: String,
      port: String
  ): ResolvedDimensions =
    val (parent, occurrence, declaration) = instancePort(snapshot, owner, instance, port)
    val query = new Query(snapshot, Some(parent.path -> occurrence))
    query.validateOverrides()
    val dimensions =
      declaration.conservativeDimensions.map(query.resolve(occurrence.childModule, _))
    dimensions.foreach(query.positive)
    query.exportDimensions(parent.path, dimensions)

  def validateRoot(snapshot: ConstructionSnapshot, root: KernelInstanceArrayRootSnapshot): Unit =
    val (owner, instance, port) = instancePort(snapshot, root.owner, root.instance, root.port)
    if root.generatedScope != instance.generatedScope then
      invalid("instance array crossed its exact instance scope")
    if root.dimensions.size != port.conservativeDimensions.size then
      invalid("instance array lost full declared rank")
    val query = new Query(snapshot, Some(owner.path -> instance))
    query.validateOverrides()
    port.conservativeDimensions.zip(root.dimensions).foreach: (formal, actual) =>
      val expected = query.resolve(instance.childModule, formal)
      val supplied = query.resolve(owner.path, actual)
      query.positive(expected)
      query.positive(supplied)
      if expected != supplied then
        invalid("instance array extent is not the exact finalized child expression")

  def validateRelation(
      snapshot: ConstructionSnapshot,
      relation: KernelConservativeArrayBindingSnapshot,
      left: Vector[String],
      right: Vector[String]
  ): Unit =
    if left.isEmpty || left.size != right.size then
      invalid("array relation requires equal nonempty rank")
    val query = new Query(snapshot, None)
    left.zip(right).foreach: (a, b) =>
      val first = query.resolve(relation.owner, a)
      val second = query.resolve(relation.owner, b)
      query.positive(first)
      query.positive(second)
      if first != second then
        invalid("array relation extents are not the same exact finalized expression")
