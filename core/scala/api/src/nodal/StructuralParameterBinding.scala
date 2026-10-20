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
  private final case class RegionOwner(path: String, owner: String)

  /** A query owns one immutable lexical substitution environment. Formal domains are internal proof
    * inputs; callers must derive them from a checked collection contract, never a snapshot bound
    * attribute. Imported expressions are re-composed through this arithmetic owner.
    */
  final class Query(
      snapshot: ConstructionSnapshot,
      instance: Option[(String, KernelInstanceSnapshot)] = None,
      val scope: Option[String] = None,
      exact: Map[String, ResolvedDimensions] = Map.empty,
      domains: Map[String, IterationDomain.Bounds] = Map.empty,
      enclosing: Option[Query] = None,
      generatedDomains: Map[String, IterationDomain.Structural] = Map.empty,
      captures: Map[String, String] = Map.empty
  ):
    if snapshot.parameterExpressions.size > 65536 ||
      snapshot.digitalCollections.size + snapshot.digitalLoops.size +
        (snapshot.structuralIfs.size + snapshot.behavioralIfs.size) * 2 +
        snapshot.generatedRegions.count(_.digitalBody.nonEmpty) +
        snapshot.flatFsms.map(_.actions.size).sum +
        snapshot.enumSwitches.map(value => value.arms.size + 1).sum > 65536
    then
      invalid("static snapshot exceeds the bounded definition budget")
    private val sourceSnapshot = snapshot
    if enclosing.exists(parent => !(parent.sourceSnapshot eq snapshot)) then
      invalid("enclosing Integer context belongs to another immutable snapshot")
    private val modules: Map[String, KernelModuleSnapshot] = enclosing.map(_.modules).getOrElse(
      unique(snapshot.modules.map(value => value.path -> value), "module")
    )
    private val expressions: Map[String, KernelParameterExpressionSnapshot] =
      enclosing.map(_.expressions).getOrElse(
        unique(snapshot.parameterExpressions.map(value => value.path -> value), "expression")
      )
    private val conditionalArms: Vector[(String, KernelStructuralArmSnapshot)] =
      enclosing.map(_.conditionalArms).getOrElse(
        snapshot.structuralIfs.flatMap(value =>
          Vector(value.trueArm, value.falseArm).map(value.owner -> _)
        ) ++ snapshot.behavioralIfs.flatMap(value =>
          Vector(value.trueArm, value.falseArm).map(value.owner -> _)
        ) ++ snapshot.flatFsms.flatMap(machine =>
          machine.actions.map(action =>
            machine.owner ->
              KernelStructuralArmSnapshot(action.path, action.captures, action.arguments, None)
          )
        ) ++
          snapshot.enumSwitches.flatMap(value =>
            (value.arms :+ value.invalidArm).map(value.owner -> _)
          )
      )
    private val arguments
        : Map[String, (RegionOwner, KernelDigitalRegionArgument)] =
      enclosing.map(_.arguments).getOrElse(
        unique(
          snapshot.digitalCollections.flatMap(value =>
            value.arguments.map(argument =>
              argument.path -> (RegionOwner(value.path, value.owner), argument)
            )
          ) ++ snapshot.digitalLoops.flatMap(value =>
            value.arguments.map(argument =>
              argument.path -> (RegionOwner(value.path, value.owner), argument)
            )
          ) ++ conditionalArms.flatMap((owner, arm) =>
            arm.arguments.map(argument =>
              argument.path -> (RegionOwner(arm.path, owner), argument)
            )
          ) ++ snapshot.generatedRegions.flatMap(value =>
            value.digitalBody.toVector.flatMap(body =>
              body.arguments.map(argument =>
                argument.path -> (RegionOwner(value.path, value.owner), argument)
              )
            )
          ),
          "lexical region argument"
        )
      )
    private val bindingKeys =
      Vector(exact.keySet, domains.keySet, generatedDomains.keySet, captures.keySet)
    if bindingKeys.map(_.size).sum != bindingKeys.flatten.toSet.size ||
      bindingKeys.map(_.size).sum > 4098
    then
      invalid("overlapping or excessive scoped Integer bindings")
    (exact.keySet ++ domains.keySet ++ generatedDomains.keySet ++ captures.keySet).foreach: path =>
      val (collection, argument) =
        arguments.getOrElse(path, invalid("Integer binding is not an actual block argument"))
      if !scope.contains(collection.path) || argument.dataType != "Integer" ||
        Set("capture", "index_capture")(argument.role) ||
        (argument.role == "induction" && !generatedDomains.contains(path))
      then
        invalid("Integer binding belongs to another lexical scope or type")
      if domains.contains(path) && !argument.role.startsWith("left_width:") &&
        !argument.role.startsWith("right_width:")
      then
        invalid("only selected reduction width formals may receive a domain")
    domains.values.foreach: value =>
      if value.lower <= 0L || value.upper < value.lower then
        invalid("formal width domain must be positive and ordered")
    private val rawCaptures: Map[String, String] = enclosing.map(_.rawCaptures).getOrElse(
      (snapshot.digitalCollections.flatMap(value =>
        value.arguments.takeRight(value.captures.size).map(_.path).zip(value.captures)
      ) ++ conditionalArms.flatMap((_, arm) =>
        arm.arguments.takeRight(arm.captures.size).map(_.path).zip(arm.captures)
      ) ++ snapshot.generatedRegions.flatMap(value =>
        value.digitalBody.toVector.flatMap(body =>
          body.arguments.drop(1).map(_.path).zip(body.captures)
        )
      )).toMap
    )
    captures.foreach: (argument, source) =>
      if !rawCaptures.get(argument).contains(source) || enclosing.isEmpty then
        invalid("Integer capture differs from its actual parent operand")
    generatedDomains.foreach: (path, supplied) =>
      val generated = snapshot.generatedRegions.find(value =>
        value.induction == path &&
          scope.contains(value.path) &&
          value.digitalBody.exists(_.arguments.headOption.exists(_.path == path))
      )
        .getOrElse(invalid("Integer domain requires an actual Generate induction"))
      val parent =
        enclosing.getOrElse(invalid("Generate domain requires its enclosing static context"))
      val lower = parent.bounds(parent.resolve(generated.owner, generated.lower))
      val upper = parent.bounds(parent.resolve(generated.owner, generated.upperExclusive))
      val step = parent.bounds(parent.resolve(generated.owner, generated.step))
      val checked = IterationDomain.structural(
        lower,
        upper,
        step,
        generated.maximum,
        identicalBounds = parent.resolve(generated.owner, generated.lower) ==
          parent.resolve(generated.owner, generated.upperExclusive) ||
          (lower.lower == lower.upper && upper.lower == upper.upper && lower.lower >= upper.upper)
      )
        .fold(problem => invalid(problem.message), identity)
      if checked != supplied || checked.maximumTripCount != generated.maximumTripCount then
        invalid("Generate domain differs from its actual retained bound operands")

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
      ids.getOrElseUpdate(
        key, {
          if facts.size >= 65536 then invalid("Integer query exceeds the bounded node budget")
          val id = facts.size; facts += Fact(key, bounds); id
        }
      )

    def literal(value: Long): Int =
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
          val number =
            value.toLongOption.getOrElse(invalid("Integer literal exceeds signed 64 bits"))
          if value != number.toString then invalid("Integer literal is not canonical decimal")
          literal(number)
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

    def bounds(index: Int): IterationDomain.Bounds = facts(index).bounds

    def operation(index: Int): Option[(String, Vector[Int])] = facts(index).key match
      case Key.Apply(name, operands) => Some(name -> operands)
      case _ => None

    def exportValues(indices: Vector[Int]): ResolvedDimensions =
      exportReachable(indices, Map.empty)

    private def exportReachable(
        indices: Vector[Int],
        existing: Map[Int, String]
    ): ResolvedDimensions =
      // Export the reachable graph only. Copying the full query arena for every width binding
      // would make a wide record's context plan quadratic in unrelated static definitions.
      val mapped = mutable.HashMap.empty[Int, Int]
      val nodes = mutable.ArrayBuffer.empty[IntegerNode]
      def copy(index: Int, depth: Int): Int = mapped.getOrElseUpdate(
        index, {
          if depth > 512 then invalid("exported Integer proof exceeds the bounded depth")
          val fact = facts(index)
          val value = fact.key match
            case Key.Literal(value) =>
              IntegerNode(Some(value), None, None, Vector.empty, fact.bounds)
            case Key.Parameter(path) =>
              IntegerNode(None, Some(path), None, Vector.empty, fact.bounds)
            case Key.Apply(operation, operands) =>
              IntegerNode(
                None,
                None,
                Some(operation),
                operands.map(copy(_, depth + 1)),
                fact.bounds
              )
          val result = nodes.size
          nodes += value
          result
        }
      )
      val roots = indices.map(copy(_, 0))
      ResolvedDimensions(
        roots,
        nodes.toVector,
        existing.iterator.flatMap((original, path) =>
          mapped.get(original).map(_ -> path)
        ).toMap
      )

    def importValue(owner: String, value: ResolvedDimensions): Int =
      if value.dimensions.size != 1 || value.nodes.size > 65536 then
        invalid("exact Integer binding must contain one bounded expression")
      val imported = mutable.HashMap.empty[Int, Int]
      val visiting = mutable.HashSet.empty[Int]
      def node(index: Int): Int = imported.getOrElseUpdate(
        index, {
          if index < 0 || index >= value.nodes.size || visiting.size >= 512 || !visiting.add(index)
          then
            invalid("exact Integer binding is cyclic, absent or excessively deep")
          try
            val source = value.nodes(index)
            val result = (source.literal, source.parameter, source.operation) match
              case (Some(number), None, None) if source.operands.isEmpty => literal(number)
              case (None, Some(path), None) if source.operands.isEmpty =>
                arguments.get(path) match
                  case Some((collection, _)) =>
                    val parent = enclosing.getOrElse(
                      invalid("exact binding contains an unadmitted formal atom")
                    )
                    // A captured ancestor induction retains its original identity after passing
                    // through an arm. It must already be an admitted atom in the parent query;
                    // do not pretend that its original block argument belongs to this arm.
                    val checked = parent.ids.get(Key.Parameter(path)).getOrElse(
                      parent.resolve(collection.owner, path)
                    )
                    if parent.bounds(checked) != source.bounds then
                      invalid("formal atom bounds differ from enclosing proof")
                    // A parent's exact replacement must not be relabeled as an opaque atom.
                    val proof = parent.exportValues(Vector(checked))
                    if proof.nodes(proof.dimensions.head).parameter != Some(path) then
                      invalid("exact binding forged an enclosing atom")
                    intern(Key.Parameter(path), source.bounds)
                  case None => resolve(owner, path)
              case (None, None, Some(operation)) => compose(operation, source.operands.map(node))
              case _ => invalid("exact Integer binding has malformed node facts")
            if facts(result).bounds != source.bounds then
              invalid("exact Integer binding bounds differ from checked arithmetic")
            result
          finally { val _ = visiting.remove(index) }
        }
      )
      node(value.dimensions.head)

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
                val result = arguments.get(token) match
                  case Some((collection, argument)) =>
                    if defaults || collection.owner != owner || !scope.contains(collection.path) ||
                      argument.dataType != "Integer" || argument.role == "capture"
                    then invalid("Integer formal escaped its admitted lexical static scope")
                    captures.get(token).map(source =>
                      val parent = enclosing.get
                      importValue(owner, parent.exportValues(Vector(parent.resolve(owner, source))))
                    ).orElse(exact.get(token).map(importValue(owner, _))).orElse(
                      domains.get(token).map(bounds => intern(Key.Parameter(token), bounds))
                    ).orElse(generatedDomains.get(token).flatMap(IterationDomain.attainedBounds)
                      .map(bounds => intern(Key.Parameter(token), bounds))).getOrElse(invalid(
                      "Integer formal has no admitted exact binding or domain"
                    ))
                  case None => declaration(owner, token) match
                      case Some(value) => parameter(owner, value, defaults)
                      case None =>
                        val expression = expressions.getOrElse(
                          token,
                          invalid(s"Integer dependency '$token' has no retained identity")
                        )
                        if expression.owner != owner ||
                          expression.region.nonEmpty && expression.region != scope
                        then
                          invalid("Integer expression belongs to another module or lexical scope")
                        arithmetic(expression, defaults)
                memo.update(cacheKey, result)
                result
              finally { val _ = active.remove(cacheKey) }

    /** Validate every raw dependency, without inventing an attained value for empty Generate.
      * Numeric/type callers keep using resolve, which fails if an attained value is necessary.
      */
    def resolveIfAttained(owner: String, token: String): Option[Int] =
      val visited = mutable.HashMap.empty[String, Option[Int]]
      val visiting = mutable.HashSet.empty[String]
      def visit(path: String): Option[Int] = visited.getOrElseUpdate(
        path, {
          if visiting.size >= 128 || visited.size >= 65536 || !visiting.add(path) then
            invalid("static predicate dependency is cyclic or exceeds its finite budget")
          try
            captures.get(path) match
              case Some(source) =>
                val parent = enclosing.get
                parent.resolveIfAttained(owner, source).map(value =>
                  importValue(owner, parent.exportValues(Vector(value)))
                )
              case None if generatedDomains.contains(path) =>
                generatedDomains.get(path).flatMap(IterationDomain.attainedBounds)
                  .map(bounds => intern(Key.Parameter(path), bounds))
              case None => expressions.get(path) match
                  case Some(expression) if expression.literal.isEmpty =>
                    if expression.owner != owner ||
                      expression.region.nonEmpty && expression.region != scope ||
                      expression.dataType != "Integer" || expression.unit.exists(_.nonEmpty)
                    then
                      invalid("predicate Integer expression has invalid scope, type or units")
                    val operation = IterationDomain.staticArithmetic(expression.operation)
                      .getOrElse(invalid("predicate Integer expression has unsupported arithmetic"))
                    if expression.operands.size != operation.arity then
                      invalid("predicate Integer expression has wrong arity")
                    val operands = expression.operands.map(visit)
                    if operands.forall(_.nonEmpty) then
                      Some(compose(expression.operation, operands.flatten))
                    else None
                  case _ => Some(resolve(owner, path))
          finally visiting.remove(path)
        }
      )
      visit(token)

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
      exportReachable(dimensions, existing.toMap)

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

  /** Query-local reuse of immutable snapshot indices and exact occurrence substitutions. */
  final class InstanceQueries(snapshot: ConstructionSnapshot, sharedQuery: Option[Query] = None):
    private lazy val shared = sharedQuery.getOrElse(new Query(snapshot))
    private val modules = snapshot.modules.map(value => value.path -> value).toMap
    private val occurrences = snapshot.modules.flatMap(module =>
      module.instances.map(value => (module.path, value.path) -> value)
    ).toMap
    private val declarations = snapshot.modules.flatMap(module =>
      module.declarations.map(value => (module.path, value.path) -> value)
    ).toMap
    if occurrences.size != snapshot.modules.map(_.instances.size).sum ||
      declarations.size != snapshot.modules.map(_.declarations.size).sum
    then invalid("duplicate boundary occurrence or declaration identity")
    private val queries = mutable.HashMap.empty[(String, String), Query]
    def declaration(ownerPath: String, instancePath: String, portPath: String)
        : (KernelModuleSnapshot, KernelInstanceSnapshot, KernelDeclarationSnapshot) =
      val owner = modules.getOrElse(ownerPath, invalid("binding owner is missing"))
      val instance = occurrences.getOrElse(
        ownerPath -> instancePath,
        invalid("binding is not owned by the exact immediate instance")
      )
      val port = declarations.getOrElse(
        instance.childModule -> portPath,
        invalid("binding does not name the declared child port")
      )
      (owner, instance, port)
    def query(ownerPath: String, instancePath: String): Query =
      queries.getOrElseUpdate(
        ownerPath -> instancePath, {
          val instance = occurrences.getOrElse(
            ownerPath -> instancePath,
            invalid("binding is not owned by the exact immediate instance")
          )
          val result = new Query(snapshot, Some(ownerPath -> instance), enclosing = Some(shared))
          result.validateOverrides()
          result
        }
      )
    def resolveInterfaceDimensions(
        ownerPath: String,
        instancePath: String,
        childDeclarationPath: String
    ): ResolvedDimensions =
      val (owner, instance, count) =
        interfaceArray(declaration(ownerPath, instancePath, childDeclarationPath))
      val context = query(ownerPath, instancePath)
      val dimension = context.resolve(instance.childModule, count)
      context.positive(dimension)
      context.exportDimensions(owner.path, Vector(dimension))
    def validateInterfaceDimensions(
        ownerPath: String,
        instancePath: String,
        childDeclarationPath: String,
        actualDimensions: Vector[String]
    ): Unit =
      val (owner, instance, count) =
        interfaceArray(declaration(ownerPath, instancePath, childDeclarationPath))
      if actualDimensions.size != 1 then invalid("Interface array lost its declared rank")
      val context = query(ownerPath, instancePath)
      val expected = context.resolve(instance.childModule, count)
      val actual = context.resolve(owner.path, actualDimensions.head)
      context.positive(expected)
      context.positive(actual)
      if expected != actual then
        invalid("Interface array count is not the exact finalized child expression")

  private def instancePort(
      snapshot: ConstructionSnapshot,
      ownerPath: String,
      instancePath: String,
      portPath: String
  )
      : (KernelModuleSnapshot, KernelInstanceSnapshot, KernelDeclarationSnapshot) =
    val (owner, instance, port) =
      new InstanceQueries(snapshot).declaration(ownerPath, instancePath, portPath)
    if !Set("analog-input", "analog-output", "analog-inout").contains(port.kind) ||
      port.conservativeDimensions.isEmpty
    then invalid("instance array requires a conservative child boundary declaration")
    (owner, instance, port)

  private def interfaceArray(
      boundary: (KernelModuleSnapshot, KernelInstanceSnapshot, KernelDeclarationSnapshot)
  ): (KernelModuleSnapshot, KernelInstanceSnapshot, String) =
    val (owner, instance, port) = boundary
    val counts = port.attributes.collect { case ("count", value) => value }
    if port.kind != "interface-array" || counts.size != 1 ||
      port.conservativeDimensions.nonEmpty
    then invalid("Interface array requires its exact declared count")
    if port.generatedScope.nonEmpty || instance.generatedScope.nonEmpty then
      invalid("Interface array requires an explicit generated-family binding")
    (owner, instance, counts.head)

  /** Finalized count from the exact child declaration, not a caller-supplied shape. */
  def resolveInterfaceDimensions(
      snapshot: ConstructionSnapshot,
      ownerPath: String,
      instancePath: String,
      childDeclarationPath: String
  ): ResolvedDimensions =
    new InstanceQueries(snapshot).resolveInterfaceDimensions(
      ownerPath,
      instancePath,
      childDeclarationPath
    )

  def validateInterfaceDimensions(
      snapshot: ConstructionSnapshot,
      ownerPath: String,
      instancePath: String,
      childDeclarationPath: String,
      actualDimensions: Vector[String]
  ): Unit =
    new InstanceQueries(snapshot).validateInterfaceDimensions(
      ownerPath,
      instancePath,
      childDeclarationPath,
      actualDimensions
    )

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
