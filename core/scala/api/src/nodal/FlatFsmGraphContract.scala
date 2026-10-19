package nodal

import scala.collection.mutable

/** Bounded conservative facts over the actual Bool/static predicate DAG. Unknown overlap rejects.
  */
private[nodal] object FlatFsmGraphContract:
  private final case class Fact(key: String, relation: String, value: BigInt)

  def validate(record: FlatFsmRecord[?], path: Any => Option[String]): Unit =
    def fail(message: String): Nothing = FlatFsmRuntime.fail(message)
    val states = record.states.toVector
    val transitions = record.transitions.toVector
    if states.map(_.caseId).distinct.size != states.size ||
      states.map(_.caseId).toSet != record.canonical.caseIds
    then
      fail("machine must declare every enum state exactly once")
    if transitions.size.toLong + record.actions.size > FlatFsmRuntime.MaximumRecords then
      fail("machine transitions/actions exceed 65536 records")
    val outgoing = transitions.groupBy(_.sourceCase)
    states.foreach: state =>
      val edges = outgoing.getOrElse(state.caseId, Vector.empty)
      if state.terminal && edges.nonEmpty then fail("terminal state has outgoing transitions")
      if !state.terminal && edges.isEmpty then fail("nonterminal state has no outgoing transition")
      if edges.map(_.mode).distinct.size > 1 then
        fail("one state mixes Exclusive and Priority transitions")
    val visited = mutable.HashSet(record.initialCase)
    val pending = mutable.ArrayDeque(record.initialCase)
    while pending.nonEmpty do
      outgoing.getOrElse(pending.removeHead(), Vector.empty).foreach: edge =>
        if !record.canonical.caseIds(edge.destinationCase) then
          fail("transition destination is unknown")
        if visited.add(edge.destinationCase) then pending.append(edge.destinationCase)
    if visited.toSet != record.canonical.caseIds then
      fail("machine contains structurally unreachable states")
    if record.encoding == FsmEncoding.Gray then
      val codes = record.local.cases.map(value => value.id -> value.code).toMap
      transitions.foreach: edge =>
        if edge.sourceCase != edge.destinationCase &&
          (codes(edge.sourceCase) ^ codes(edge.destinationCase)).bitCount != 1
        then
          fail("Gray encoding requires every non-self transition to change exactly one bit")

    var work = 0
    def charge(): Unit =
      work += 1
      if work > 65536 then fail("Exclusive guard proof exceeds 65536 work; use explicit Priority")
    def key(value: Any): String =
      path(value).getOrElse(fail("guard fact has no actual value identity"))
    def constant(value: Any): Option[BigInt] = value match
      case expression: KernelExpr[?] => expression.literal.filter(_.kind == "integer")
          .flatMap(value => scala.util.Try(BigInt(value.value)).toOption)
      case _ => None
    def invert(relation: String): String = relation match
      case "eq" => "ne"
      case "ne" => "eq"
      case "lt" => "ge"
      case "le" => "gt"
      case "gt" => "le"
      case "ge" => "lt"
      case _ => fail("unknown guard relation")
    def reverse(relation: String): String = relation match
      case "lt" => "gt"
      case "le" => "ge"
      case "gt" => "lt"
      case "ge" => "le"
      case other => other
    def combine(left: Vector[Vector[Fact]], right: Vector[Vector[Fact]]): Vector[Vector[Fact]] =
      left.flatMap(a =>
        right.map: b =>
          charge()
          a ++ b
      )
    def clauses(value: Any, negated: Boolean, depth: Int): Vector[Vector[Fact]] =
      charge()
      if depth > 128 then fail("guard proof exceeds lexical depth")
      val (operation, operands) = value match
        case expression: KernelExpr[?] =>
          if expression.resultType.contains(KernelTypeDescriptor("Bool")) &&
            expression.literal.nonEmpty
          then
            val truth = expression.literal.get.value match
              case "true" | "1" => true
              case "false" | "0" => false
              case _ => fail("Bool guard literal is malformed")
            (if truth != negated then "true" else "false", Vector.empty[Any])
          else (expression.operation.getOrElse(""), expression.operands)
        case predicate: StaticPredicate => (predicate.operation, predicate.operands)
        case _ => ("", Vector.empty[Any])
      operation match
        case "true" => Vector(Vector.empty)
        case "false" => Vector.empty
        case "behavioral_predicate" if operands.size == 1 =>
          clauses(operands.head, negated, depth + 1)
        case "bool_not" | "not" if operands.size == 1 => clauses(operands.head, !negated, depth + 1)
        case "bool_and" | "and" | "bool_or" | "or" if operands.size == 2 =>
          val left = clauses(operands(0), negated, depth + 1)
          val right = clauses(operands(1), negated, depth + 1)
          val conjunction = Set("bool_and", "and")(operation) != negated
          if conjunction then combine(left, right) else left ++ right
        case raw
            if Set(
              "eq",
              "ne",
              "lt",
              "le",
              "gt",
              "ge",
              "digital_eq",
              "digital_ne",
              "digital_lt",
              "digital_le",
              "digital_gt",
              "digital_ge"
            )(raw) && operands.size == 2 =>
          val relation = raw.stripPrefix("digital_")
          val normalized = if negated then invert(relation) else relation
          val numeric =
            constant(operands(1)).map(value => Fact(key(operands(0)), normalized, value))
              .orElse(constant(operands(0)).map(value =>
                Fact(key(operands(1)), reverse(normalized), value)
              ))
          Vector(Vector(numeric.getOrElse(Fact(key(value), "eq", if negated then 0 else 1))))
        case _ => Vector(Vector(Fact(key(value), "eq", if negated then 0 else 1)))
    def contradictory(facts: Vector[Fact]): Boolean =
      facts.groupBy(_.key).valuesIterator.exists: selected =>
        var lower: Option[BigInt] = None
        var upper: Option[BigInt] = None
        val excluded = mutable.HashSet.empty[BigInt]
        selected.foreach: fact =>
          charge()
          def low(value: BigInt): Unit = lower = Some(lower.fold(value)(_.max(value)))
          def high(value: BigInt): Unit = upper = Some(upper.fold(value)(_.min(value)))
          fact.relation match
            case "eq" => low(fact.value); high(fact.value)
            case "ne" => excluded += fact.value
            case "lt" => high(fact.value - 1)
            case "le" => high(fact.value)
            case "gt" => low(fact.value + 1)
            case "ge" => low(fact.value)
            case _ => ()
        lower.zip(upper).exists((a, b) => a > b || (a == b && excluded(a)))
    outgoing.valuesIterator.filter(_.headOption.exists(_.mode == TransitionMode.Exclusive)).foreach:
      edges =>
        val expanded = edges.map(edge => clauses(edge.guard, false, 0))
        edges.indices.foreach: right =>
          (0 until right).foreach: left =>
            charge()
            val disjoint =
              expanded(left).forall(a => expanded(right).forall(b => contradictory(a ++ b)))
            if !disjoint then fail("Exclusive transition guards may overlap; use explicit Priority")
