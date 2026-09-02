package nodal

import scala.collection.mutable

/** Structured analog control-flow model introduced by Increment 34.
  *
  * The first tranche is deliberately source-semantic. It preserves conditionals, case selection,
  * bounded loops, break/continue, authored identities, and branch-sensitive definite assignment
  * without executing a solver or lowering control flow to a target HDL.
  */
private[nodal] object AnalogControlFlowRuntime:
  enum Stage:
    case Static, Runtime

  enum LoopStage:
    case Static, RuntimeBounded

  enum CaseLabel:
    case Integer(value: Long)
    case Boolean(value: scala.Boolean)

  final case class Diagnostic(code: String, message: String, path: Option[String] = None):
    override def toString: String = path match
      case Some(value) => s"$code: $message [$value]"
      case None => s"$code: $message"

  final class Failure(val diagnostic: Diagnostic)
      extends IllegalArgumentException(diagnostic.toString)

  private def fail(code: String, message: String, path: Option[String] = None): Nothing =
    scala.util.Failure[Nothing](new Failure(Diagnostic(code, message, path))).get

  final case class Condition(
      rendered: String,
      reads: Set[String],
      stage: Stage,
      staticValue: Option[scala.Boolean],
      valueType: AnalogProceduralRuntime.ValueType
  )

  object Condition:
    def runtime(rendered: String, reads: Set[String] = Set.empty): Condition =
      Condition(
        rendered,
        reads,
        Stage.Runtime,
        None,
        AnalogProceduralRuntime.ValueType(
          AnalogProceduralRuntime.ScalarKind.Boolean,
          "dimensionless"
        )
      )

    def static(value: scala.Boolean, rendered: String = "static-condition"): Condition =
      Condition(
        rendered,
        Set.empty,
        Stage.Static,
        Some(value),
        AnalogProceduralRuntime.ValueType(
          AnalogProceduralRuntime.ScalarKind.Boolean,
          "dimensionless"
        )
      )

  final case class Selector(
      rendered: String,
      reads: Set[String],
      kind: AnalogProceduralRuntime.ScalarKind,
      dimension: String,
      staticValue: Option[CaseLabel] = None
  )

  object Selector:
    def runtimeInteger(rendered: String, reads: Set[String] = Set.empty): Selector =
      Selector(
        rendered,
        reads,
        AnalogProceduralRuntime.ScalarKind.Integer,
        "dimensionless"
      )

    def runtimeBoolean(rendered: String, reads: Set[String] = Set.empty): Selector =
      Selector(
        rendered,
        reads,
        AnalogProceduralRuntime.ScalarKind.Boolean,
        "dimensionless"
      )

    def staticInteger(value: Long, rendered: String = "static-selector"): Selector =
      Selector(
        rendered,
        Set.empty,
        AnalogProceduralRuntime.ScalarKind.Integer,
        "dimensionless",
        Some(CaseLabel.Integer(value))
      )

    def staticBoolean(
        value: scala.Boolean,
        rendered: String = "static-selector"
    ): Selector =
      Selector(
        rendered,
        Set.empty,
        AnalogProceduralRuntime.ScalarKind.Boolean,
        "dimensionless",
        Some(CaseLabel.Boolean(value))
      )

  final case class Block(identity: String, statements: Vector[Statement])

  final case class ConditionalBranch(condition: Condition, body: Block)

  final case class CaseArm(labels: Vector[CaseLabel], body: Block)

  sealed trait Statement:
    def identity: String

  object Statement:
    final case class Assign(
        identity: String,
        target: String,
        reads: Set[String] = Set.empty
    ) extends Statement

    final case class Read(identity: String, variable: String) extends Statement

    final case class IfThenElse(
        identity: String,
        branches: Vector[ConditionalBranch],
        otherwise: Option[Block]
    ) extends Statement

    final case class CaseStatement(
        identity: String,
        selector: Selector,
        arms: Vector[CaseArm],
        default: Option[Block]
    ) extends Statement

    final case class Loop(
        identity: String,
        stage: LoopStage,
        minimumIterations: Int,
        maximumIterations: Int,
        boundReads: Set[String],
        body: Block
    ) extends Statement

    final case class Break(identity: String) extends Statement

    final case class Continue(identity: String) extends Statement

  final case class Result(
      definitelyInitialized: Set[String],
      retainedControlNodes: Int
  )

  private final case class Flow(
      normal: Option[Set[String]],
      breaks: Vector[Set[String]],
      continues: Vector[Set[String]]
  )

  def analyze(
      root: Block,
      initiallyInitialized: Set[String] = Set.empty
  ): Result =
    val identities = mutable.HashSet.empty[String]
    validateBlock(root, identities, Vector.empty)
    val flow = analyzeBlock(root, initiallyInitialized, 0)
    Result(flow.normal.getOrElse(Set.empty), identities.size)

  private def validateIdentity(identity: String, identities: mutable.Set[String]): Unit =
    val canonical = identity.trim
    if canonical.isEmpty then
      fail("NODAL-ANALOG-034-001", "control-flow identity must be non-empty")
    if identities.contains(canonical) then
      fail(
        "NODAL-ANALOG-034-001",
        s"duplicate control-flow identity '$canonical'",
        Some(canonical)
      )
    identities += canonical

  private def validateCondition(condition: Condition, path: String): Unit =
    if condition.rendered.trim.isEmpty then
      fail(
        "NODAL-ANALOG-034-002",
        "control-flow condition spelling must be non-empty",
        Some(path)
      )
    val expected = AnalogProceduralRuntime.ValueType(
      AnalogProceduralRuntime.ScalarKind.Boolean,
      "dimensionless"
    )
    if condition.valueType != expected then
      fail(
        "NODAL-ANALOG-034-002",
        "control-flow condition must be a dimensionless Boolean value",
        Some(path)
      )
    condition.stage match
      case Stage.Static if condition.staticValue.isEmpty =>
        fail(
          "NODAL-ANALOG-034-003",
          "static condition requires a compile-time Boolean value",
          Some(path)
        )
      case Stage.Runtime if condition.staticValue.nonEmpty =>
        fail(
          "NODAL-ANALOG-034-003",
          "runtime condition cannot carry a compile-time selected value",
          Some(path)
        )
      case _ => ()

  private def labelKind(label: CaseLabel): AnalogProceduralRuntime.ScalarKind = label match
    case CaseLabel.Integer(_) => AnalogProceduralRuntime.ScalarKind.Integer
    case CaseLabel.Boolean(_) => AnalogProceduralRuntime.ScalarKind.Boolean

  private def labelKey(label: CaseLabel): String = label match
    case CaseLabel.Integer(value) => s"integer:$value"
    case CaseLabel.Boolean(value) => s"boolean:$value"

  private def validateSelector(selector: Selector, path: String): Unit =
    if selector.rendered.trim.isEmpty then
      fail(
        "NODAL-ANALOG-034-005",
        "case selector spelling must be non-empty",
        Some(path)
      )
    val legalKind =
      selector.kind == AnalogProceduralRuntime.ScalarKind.Integer ||
        selector.kind == AnalogProceduralRuntime.ScalarKind.Boolean
    if !legalKind || selector.dimension != "dimensionless" then
      fail(
        "NODAL-ANALOG-034-005",
        "case selector must be a dimensionless integer or Boolean value",
        Some(path)
      )
    selector.staticValue.foreach: value =>
      if labelKind(value) != selector.kind then
        fail(
          "NODAL-ANALOG-034-007",
          "static case selector value does not match selector kind",
          Some(path)
        )

  private def validateBlock(
      block: Block,
      identities: mutable.Set[String],
      loopStack: Vector[LoopStage]
  ): Unit =
    validateIdentity(block.identity, identities)
    block.statements.foreach:
      case assignment: Statement.Assign =>
        validateIdentity(assignment.identity, identities)
        if assignment.target.trim.isEmpty then
          fail(
            "NODAL-ANALOG-034-014",
            "control-flow assignment target must be non-empty",
            Some(assignment.identity)
          )
      case read: Statement.Read =>
        validateIdentity(read.identity, identities)
        if read.variable.trim.isEmpty then
          fail(
            "NODAL-ANALOG-034-014",
            "control-flow read target must be non-empty",
            Some(read.identity)
          )
      case conditional: Statement.IfThenElse =>
        validateIdentity(conditional.identity, identities)
        if conditional.branches.isEmpty then
          fail(
            "NODAL-ANALOG-034-015",
            "conditional requires at least one branch",
            Some(conditional.identity)
          )
        conditional.branches.zipWithIndex.foreach: (branch, index) =>
          validateCondition(branch.condition, s"${conditional.identity}.condition_$index")
          validateBlock(branch.body, identities, loopStack)
        conditional.otherwise.foreach(validateBlock(_, identities, loopStack))
      case selection: Statement.CaseStatement =>
        validateIdentity(selection.identity, identities)
        validateSelector(selection.selector, selection.identity)
        if selection.arms.isEmpty then
          fail(
            "NODAL-ANALOG-034-015",
            "case statement requires at least one explicit arm",
            Some(selection.identity)
          )
        val labels = mutable.HashSet.empty[String]
        selection.arms.foreach: arm =>
          if arm.labels.isEmpty then
            fail(
              "NODAL-ANALOG-034-015",
              "case arm requires at least one label",
              Some(selection.identity)
            )
          arm.labels.foreach: label =>
            if labelKind(label) != selection.selector.kind then
              fail(
                "NODAL-ANALOG-034-007",
                "case label kind does not match selector kind",
                Some(selection.identity)
              )
            val key = labelKey(label)
            if labels.contains(key) then
              fail(
                "NODAL-ANALOG-034-006",
                s"duplicate case label '$key'",
                Some(selection.identity)
              )
            labels += key
          validateBlock(arm.body, identities, loopStack)
        selection.default.foreach(validateBlock(_, identities, loopStack))
      case loop: Statement.Loop =>
        validateIdentity(loop.identity, identities)
        if loop.minimumIterations < 0 ||
          loop.maximumIterations < 0 ||
          loop.minimumIterations > loop.maximumIterations
        then
          fail(
            "NODAL-ANALOG-034-008",
            "bounded loop requires 0 <= minimum <= maximum",
            Some(loop.identity)
          )
        loop.stage match
          case LoopStage.Static
              if loop.minimumIterations != loop.maximumIterations ||
                loop.boundReads.nonEmpty =>
            fail(
              "NODAL-ANALOG-034-009",
              "static loop requires one exact compile-time trip count",
              Some(loop.identity)
            )
          case LoopStage.RuntimeBounded if loop.maximumIterations == 0 =>
            fail(
              "NODAL-ANALOG-034-008",
              "runtime loop requires a positive finite maximum",
              Some(loop.identity)
            )
          case _ => ()
        validateBlock(loop.body, identities, loopStack :+ loop.stage)
      case exit: Statement.Break =>
        validateIdentity(exit.identity, identities)
        if loopStack.lastOption != Some(LoopStage.RuntimeBounded) then
          fail(
            "NODAL-ANALOG-034-010",
            "break is legal only in the nearest runtime-bounded loop",
            Some(exit.identity)
          )
      case next: Statement.Continue =>
        validateIdentity(next.identity, identities)
        if loopStack.lastOption != Some(LoopStage.RuntimeBounded) then
          fail(
            "NODAL-ANALOG-034-011",
            "continue is legal only in the nearest runtime-bounded loop",
            Some(next.identity)
          )

  private def checkReads(reads: Set[String], state: Set[String], path: String): Unit =
    val missing = reads.filterNot(state.contains).toVector.sorted
    if missing.nonEmpty then
      fail(
        "NODAL-ANALOG-034-004",
        "control-flow path reads variables before definite initialization: " +
          missing.mkString(","),
        Some(path)
      )

  private def intersectAll(states: Vector[Set[String]]): Set[String] =
    states.headOption match
      case None => Set.empty
      case Some(first) => states.tail.foldLeft(first)(_ intersect _)

  private def mergeAlternatives(flows: Vector[Flow]): Flow =
    val normalStates = flows.flatMap(_.normal)
    Flow(
      Option.when(normalStates.nonEmpty)(intersectAll(normalStates)),
      flows.flatMap(_.breaks),
      flows.flatMap(_.continues)
    )

  private def analyzeBlock(
      block: Block,
      input: Set[String],
      loopDepth: Int
  ): Flow =
    var normal: Option[Set[String]] = Some(input)
    val breaks = mutable.ArrayBuffer.empty[Set[String]]
    val continues = mutable.ArrayBuffer.empty[Set[String]]
    block.statements.foreach: statement =>
      normal.foreach: state =>
        val next = analyzeStatement(statement, state, loopDepth)
        normal = next.normal
        breaks ++= next.breaks
        continues ++= next.continues
    Flow(normal, breaks.toVector, continues.toVector)

  private def analyzeConditional(
      conditional: Statement.IfThenElse,
      input: Set[String],
      loopDepth: Int
  ): Flow =
    val alternatives = mutable.ArrayBuffer.empty[Flow]
    var unmatchedPath = true
    conditional.branches.zipWithIndex.foreach: (branch, index) =>
      if unmatchedPath then
        checkReads(
          branch.condition.reads,
          input,
          s"${conditional.identity}.condition_$index"
        )
        branch.condition.stage match
          case Stage.Static =>
            if branch.condition.staticValue.contains(true) then
              alternatives += analyzeBlock(branch.body, input, loopDepth)
              unmatchedPath = false
          case Stage.Runtime =>
            alternatives += analyzeBlock(branch.body, input, loopDepth)
    if unmatchedPath then
      alternatives +=
        conditional.otherwise
          .map(analyzeBlock(_, input, loopDepth))
          .getOrElse(Flow(Some(input), Vector.empty, Vector.empty))
    mergeAlternatives(alternatives.toVector)

  private def analyzeCase(
      selection: Statement.CaseStatement,
      input: Set[String],
      loopDepth: Int
  ): Flow =
    checkReads(selection.selector.reads, input, selection.identity)
    selection.selector.staticValue match
      case Some(value) =>
        selection.arms
          .find(_.labels.contains(value))
          .map(arm => analyzeBlock(arm.body, input, loopDepth))
          .orElse(selection.default.map(analyzeBlock(_, input, loopDepth)))
          .getOrElse(Flow(Some(input), Vector.empty, Vector.empty))
      case None =>
        val alternatives = selection.arms.map: arm =>
          analyzeBlock(arm.body, input, loopDepth)
        val withDefault = alternatives :+
          selection.default
            .map(analyzeBlock(_, input, loopDepth))
            .getOrElse(Flow(Some(input), Vector.empty, Vector.empty))
        mergeAlternatives(withDefault)

  private def analyzeLoop(
      loop: Statement.Loop,
      input: Set[String],
      loopDepth: Int
  ): Flow =
    checkReads(loop.boundReads, input, loop.identity)
    if loop.maximumIterations == 0 then
      Flow(Some(input), Vector.empty, Vector.empty)
    else
      val body = analyzeBlock(loop.body, input, loopDepth + 1)
      val exits = mutable.ArrayBuffer.empty[Set[String]]
      if loop.minimumIterations == 0 then exits += input
      body.normal.foreach(exits += _)
      exits ++= body.breaks
      exits ++= body.continues
      Flow(
        Option.when(exits.nonEmpty)(intersectAll(exits.toVector)),
        Vector.empty,
        Vector.empty
      )

  private def analyzeStatement(
      statement: Statement,
      input: Set[String],
      loopDepth: Int
  ): Flow = statement match
    case assignment: Statement.Assign =>
      checkReads(assignment.reads, input, assignment.identity)
      Flow(Some(input + assignment.target), Vector.empty, Vector.empty)
    case read: Statement.Read =>
      checkReads(Set(read.variable), input, read.identity)
      Flow(Some(input), Vector.empty, Vector.empty)
    case conditional: Statement.IfThenElse =>
      analyzeConditional(conditional, input, loopDepth)
    case selection: Statement.CaseStatement =>
      analyzeCase(selection, input, loopDepth)
    case loop: Statement.Loop =>
      analyzeLoop(loop, input, loopDepth)
    case _: Statement.Break =>
      if loopDepth == 0 then
        fail(
          "NODAL-ANALOG-034-010",
          "break is outside a runtime-bounded loop"
        )
      Flow(None, Vector(input), Vector.empty)
    case _: Statement.Continue =>
      if loopDepth == 0 then
        fail(
          "NODAL-ANALOG-034-011",
          "continue is outside a runtime-bounded loop"
        )
      Flow(None, Vector.empty, Vector(input))
