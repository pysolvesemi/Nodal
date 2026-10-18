package nodal

import scala.collection.mutable

/** Query-local exact Integer substitution for finalized conservative child bindings. Declaration
  * and expression snapshots remain authoritative; interned IDs are never serialized.
  */
private[nodal] object StructuralParameterBinding:
  private def invalid(message: String): Nothing =
    throw new IllegalArgumentException(s"NODAL-SHAPE-043-007: $message")

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
          val operation = expression.operation match
            case "analog_add" => IterationDomain.Arithmetic.Add
            case "analog_sub" => IterationDomain.Arithmetic.Subtract
            case "analog_mul" => IterationDomain.Arithmetic.Multiply
            case "analog_div" => IterationDomain.Arithmetic.Divide
            case "analog_neg" => IterationDomain.Arithmetic.Negate
            case _ => invalid("structural expression is not supported pure Integer arithmetic")
          val arity = if operation == IterationDomain.Arithmetic.Negate then 1 else 2
          if expression.operands.size != arity then invalid("Integer expression has invalid arity")
          val operands = expression.operands.map(resolve(expression.owner, _, defaults))
          val bounds = IterationDomain.arithmetic(operation, operands.map(facts(_).bounds))
            .fold(problem => invalid(problem.message), identity)
          if operands.forall(index => facts(index).key.isInstanceOf[Key.Literal]) then
            literal(bounds.lower)
          else intern(Key.Apply(expression.operation, operands), bounds)

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

  def defaultInteger(snapshot: ConstructionSnapshot, owner: String, reference: String): Long =
    new Query(snapshot, None).checkedLiteral(owner, reference)

  def validate(
      snapshot: ConstructionSnapshot,
      binding: KernelConservativeArrayBindingSnapshot,
      actualDimensions: Vector[String]
  ): Unit =
    val owner =
      snapshot.modules.find(_.path == binding.owner).getOrElse(invalid("binding owner is missing"))
    val instance = owner.instances.find(
      _.path == binding.instance
    ).getOrElse(invalid("binding is not owned by the exact immediate instance"))
    val child = snapshot.modules.find(
      _.path == instance.childModule
    ).getOrElse(invalid("child definition is missing"))
    val port = child.declarations.find(
      _.path == binding.port
    ).getOrElse(invalid("binding does not name the declared child port"))
    if !Set("analog-input", "analog-output", "analog-inout").contains(port.kind) ||
      port.conservativeDimensions.isEmpty
    then
      invalid("whole array binding requires a conservative child boundary declaration")
    if actualDimensions.isEmpty || actualDimensions.size != port.conservativeDimensions.size then
      invalid("array binding rank does not match the declared child shape")
    val query = new Query(snapshot, Some(owner.path -> instance))
    query.validateOverrides()
    port.conservativeDimensions.zip(actualDimensions).foreach: (formal, actual) =>
      val expected = query.resolve(child.path, formal)
      val supplied = query.resolve(owner.path, actual)
      query.positive(expected)
      query.positive(supplied)
      if expected != supplied then
        invalid("array binding extent is not the exact substituted child expression")
