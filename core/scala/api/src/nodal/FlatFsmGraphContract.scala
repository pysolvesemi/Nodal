package nodal

import scala.collection.mutable

/** Bounded conservative facts over the actual Bool/static predicate DAG. Unknown overlap rejects.
  */
private[nodal] object FlatFsmGraphContract:
  private final case class Fact(key: String, relation: String, value: BigInt)

  /** Shared structural admission for immutable definitions and actual instance graphs. */
  def validateStructure(
      cases: Set[String],
      initial: String,
      states: Vector[(String, Boolean)],
      transitions: Vector[(String, String, TransitionMode)],
      fail: String => Nothing
  ): Unit =
    if states.map(_._1).distinct.size != states.size || states.map(_._1).toSet != cases
    then
      fail("machine must declare every enum state exactly once")
    if !cases(initial) then fail("machine initial state is unknown")
    val outgoing = transitions.groupBy(_._1)
    transitions.foreach: (source, destination, _) =>
      if !cases(source) || !cases(destination) then fail("transition endpoint is unknown")
    states.foreach: (state, terminal) =>
      val edges = outgoing.getOrElse(state, Vector.empty)
      if terminal && edges.nonEmpty then fail("terminal state has outgoing transitions")
      if !terminal && edges.isEmpty then fail("nonterminal state has no outgoing transition")
      if edges.map(_._3).distinct.size > 1 then
        fail("one state mixes Exclusive and Priority transitions")
    val visited = mutable.HashSet(initial)
    val pending = mutable.ArrayDeque(initial)
    while pending.nonEmpty do
      outgoing.getOrElse(pending.removeHead(), Vector.empty).foreach: edge =>
        if visited.add(edge._2) then pending.append(edge._2)
    if visited.toSet != cases then
      fail("machine contains structurally unreachable states")

  final case class Edge(
      source: String,
      destination: Option[String],
      mode: TransitionMode,
      kind: String = "ordinary",
      resume: Option[String] = None
  )
  final case class Stack(
      capacity: Int,
      overflow: Option[String],
      underflow: Option[String],
      invalidFrame: Option[String]
  )
  final case class Structure(completionPossible: Boolean, work: Int)

  /** Bounded pushdown possibility summaries, never concrete stack-content enumeration. */
  def validateControlStructure(
      cases: Set[String],
      initial: String,
      states: Vector[(String, Boolean)],
      transitions: Vector[Edge],
      stack: Option[Stack],
      fail: String => Nothing
  ): Structure =
    if states.isEmpty || states.size > 4096 || cases.size > 4096 then
      fail("machine state inventory exceeds 4096")
    if transitions.size > FlatFsmRuntime.MaximumRecords then
      fail("machine exceeds 65536 transitions")
    if states.map(_._1).distinct.size != states.size || states.map(_._1).toSet != cases then
      fail("machine must declare every enum state exactly once")
    if !cases(initial) then fail("machine initial state is unknown")
    transitions.foreach: edge =>
      if !cases(edge.source) || !edge.destination.forall(cases) || !edge.resume.forall(cases) ||
        edge.mode == null ||
        !(edge.kind match
          case "ordinary" => edge.destination.nonEmpty && edge.resume.isEmpty
          case "call" => stack.nonEmpty && edge.destination.nonEmpty && edge.resume.nonEmpty
          case "return" => stack.nonEmpty && edge.destination.isEmpty && edge.resume.isEmpty
          case _ => false
        )
      then fail("transition endpoint, procedure kind or stack configuration is invalid")
    val outgoing = transitions.groupBy(_.source)
    states.foreach: (state, terminal) =>
      val edges = outgoing.getOrElse(state, Vector.empty)
      if terminal && edges.nonEmpty then fail("terminal state has outgoing transitions")
      if !terminal && edges.isEmpty then fail("nonterminal state has no outgoing transition")
      if edges.map(_.mode).distinct.size > 1 then
        fail("one state mixes Exclusive and Priority transitions")
    stack match
      case None =>
        validateStructure(
          cases,
          initial,
          states,
          transitions.map(edge => (edge.source, edge.destination.get, edge.mode)),
          fail
        )
        Structure(states.exists(_._2), 0)
      case Some(config) =>
        if config.capacity < 1 || config.capacity > 128 ||
          !Vector(config.overflow, config.underflow, config.invalidFrame).flatten.forall(cases)
        then fail("stack capacity or exact typed trap target is invalid")
        val count = states.size
        val levels = config.capacity + 1
        var work = 3L * count * levels + 2L * transitions.size * levels
        states.foreach: (_, terminal) =>
          if terminal then work += levels
        (0 until levels).foreach: depth =>
          transitions.foreach: edge =>
            work +=
              (edge.kind match
                case "ordinary" => 4
                case "call" if depth < config.capacity => 8
                case "call" => if config.overflow.nonEmpty then 2 else 0
                case "return" if depth > 0 => 1
                case "return" => if config.underflow.nonEmpty then 2 else 0
                case _ => 0)
        if work > FlatFsmRuntime.MaximumRecords then
          fail("stack structural summary exceeds 65536 state-depth and dependency work")
        // No worklist, reverse index or predicate arrays are allocated before preflight above.
        val index = states.map(_._1).zipWithIndex.toMap
        def node(state: String, depth: Int): Int = depth * count + index(state)
        def returning(state: String, depth: Int): Int = 2 * node(state, depth)
        def completing(state: String, depth: Int): Int = returning(state, depth) + 1
        final case class Rule(target: Int, var remaining: Int)
        val rules = mutable.ArrayBuffer.empty[Rule]
        val dependents = Array.fill(2 * count * levels)(mutable.ArrayBuffer.empty[Int])
        val proved = Array.fill(2 * count * levels)(false)
        val ready = mutable.ArrayDeque.empty[Int]
        def establish(predicate: Int): Unit =
          if !proved(predicate) then
            proved(predicate) = true
            ready.append(predicate)
        def rule(target: Int, dependencies: Int*): Unit =
          if dependencies.isEmpty then establish(target)
          else
            val id = rules.size
            rules += Rule(target, dependencies.size)
            dependencies.foreach(value => dependents(value) += id)
        (0 until levels).foreach: depth =>
          states.foreach: (state, terminal) =>
            if terminal then rule(completing(state, depth))
          transitions.foreach: edge =>
            val r = returning(edge.source, depth)
            val c = completing(edge.source, depth)
            edge.kind match
              case "ordinary" =>
                rule(r, returning(edge.destination.get, depth))
                rule(c, completing(edge.destination.get, depth))
              case "call" if depth < config.capacity =>
                val callee = returning(edge.destination.get, depth + 1)
                rule(r, callee, returning(edge.resume.get, depth))
                rule(c, completing(edge.destination.get, depth + 1))
                rule(c, callee, completing(edge.resume.get, depth))
              case "call" => config.overflow.foreach(to => rule(c, completing(to, 0)))
              case "return" if depth > 0 => rule(r)
              case "return" => config.underflow.foreach(to => rule(c, completing(to, 0)))
              case _ => fail("unknown admitted procedure edge")
        while ready.nonEmpty do
          dependents(ready.removeHead()).foreach: id =>
            val selected = rules(id)
            selected.remaining -= 1
            if selected.remaining == 0 then establish(selected.target)
        val reached = Array.fill(count * levels)(false)
        val pending = mutable.ArrayDeque.empty[(String, Int)]
        def visit(state: String, depth: Int): Unit =
          val id = node(state, depth)
          if !reached(id) then
            reached(id) = true
            pending.append(state -> depth)
        visit(initial, 0)
        config.invalidFrame.foreach(visit(_, 0))
        while pending.nonEmpty do
          val (state, depth) = pending.removeHead()
          outgoing.getOrElse(state, Vector.empty).foreach: edge =>
            edge.kind match
              case "ordinary" => visit(edge.destination.get, depth)
              case "call" if depth < config.capacity =>
                visit(edge.destination.get, depth + 1)
                if proved(returning(edge.destination.get, depth + 1)) then
                  visit(edge.resume.get, depth)
              case "call" => config.overflow.foreach(visit(_, 0))
              case "return" if depth == 0 => config.underflow.foreach(visit(_, 0))
              case "return" => ()
              case _ => fail("unknown admitted procedure edge")
        if states.indices.exists(state =>
            !(0 until levels).exists(depth => reached(depth * count + state))
          )
        then
          fail(
            "machine contains structurally unreachable states under bounded call-return matching"
          )
        Structure(proved(completing(initial, 0)), work.toInt)

  def validate(record: FlatFsmRecord[?], path: Any => Option[String]): Unit =
    def fail(message: String): Nothing = FlatFsmRuntime.fail(message)
    if record.transitions.size.toLong + record.actions.size > FlatFsmRuntime.MaximumRecords then
      fail("machine transitions/actions exceed 65536 records")
    val transitions = record.transitions.toVector
    val _ = validateControlStructure(
      record.canonical.caseIds,
      record.initialCase,
      record.states.toVector.map(state => state.caseId -> state.terminal),
      transitions.map(edge =>
        Edge(edge.sourceCase, edge.destinationCase, edge.mode, edge.kind, edge.resumeCase)
      ),
      record.stack.map(_.contract),
      fail
    )
    val outgoing = transitions.groupBy(_.sourceCase)
    if record.stack.nonEmpty && record.encoding == FsmEncoding.Gray then
      fail("bounded dynamic return does not support Gray local encoding")
    if record.encoding == FsmEncoding.Gray then
      val codes = record.local.cases.map(value => value.id -> value.code).toMap
      transitions.foreach: edge =>
        if edge.sourceCase != edge.destinationCase.get &&
          (codes(edge.sourceCase) ^ codes(edge.destinationCase.get)).bitCount != 1
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
          if expression.resultDescriptor.contains(KernelTypeDescriptor("Bool")) &&
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
