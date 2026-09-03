package nodal

import scala.collection.mutable

/** Structured analog control-flow model introduced by Increment 34.
  *
  * The model preserves conditionals, case selection, bounded loops, break/continue, lexical
  * declarations, authored identities, source provenance, and branch-sensitive definite assignment
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

  private[nodal] def fail(
      code: String,
      message: String,
      path: Option[String] = None
  ): Nothing =
    scala.util.Failure[Nothing](new Failure(Diagnostic(code, message, path))).get

  final case class Condition(
      rendered: String,
      reads: Set[String],
      stage: Stage,
      staticValue: Option[scala.Boolean],
      valueType: AnalogProceduralRuntime.ValueType,
      source: Option[AnalogProceduralRuntime.Source] = None
  )

  object Condition:
    def runtime(
        rendered: String,
        reads: Set[String] = Set.empty,
        source: Option[AnalogProceduralRuntime.Source] = None
    ): Condition =
      Condition(
        rendered,
        reads,
        Stage.Runtime,
        None,
        AnalogProceduralRuntime.ValueType(
          AnalogProceduralRuntime.ScalarKind.Boolean,
          "dimensionless"
        ),
        source
      )

    def static(
        value: scala.Boolean,
        rendered: String = "static-condition",
        source: Option[AnalogProceduralRuntime.Source] = None
    ): Condition =
      Condition(
        rendered,
        Set.empty,
        Stage.Static,
        Some(value),
        AnalogProceduralRuntime.ValueType(
          AnalogProceduralRuntime.ScalarKind.Boolean,
          "dimensionless"
        ),
        source
      )

  final case class Selector(
      rendered: String,
      reads: Set[String],
      kind: AnalogProceduralRuntime.ScalarKind,
      dimension: String,
      staticValue: Option[CaseLabel] = None,
      source: Option[AnalogProceduralRuntime.Source] = None
  )

  object Selector:
    def runtimeInteger(
        rendered: String,
        reads: Set[String] = Set.empty,
        source: Option[AnalogProceduralRuntime.Source] = None
    ): Selector =
      Selector(
        rendered,
        reads,
        AnalogProceduralRuntime.ScalarKind.Integer,
        "dimensionless",
        source = source
      )

    def runtimeBoolean(
        rendered: String,
        reads: Set[String] = Set.empty,
        source: Option[AnalogProceduralRuntime.Source] = None
    ): Selector =
      Selector(
        rendered,
        reads,
        AnalogProceduralRuntime.ScalarKind.Boolean,
        "dimensionless",
        source = source
      )

    def staticInteger(
        value: Long,
        rendered: String = "static-selector",
        source: Option[AnalogProceduralRuntime.Source] = None
    ): Selector =
      Selector(
        rendered,
        Set.empty,
        AnalogProceduralRuntime.ScalarKind.Integer,
        "dimensionless",
        Some(CaseLabel.Integer(value)),
        source
      )

    def staticBoolean(
        value: scala.Boolean,
        rendered: String = "static-selector",
        source: Option[AnalogProceduralRuntime.Source] = None
    ): Selector =
      Selector(
        rendered,
        Set.empty,
        AnalogProceduralRuntime.ScalarKind.Boolean,
        "dimensionless",
        Some(CaseLabel.Boolean(value)),
        source
      )

  final case class Block(
      identity: String,
      statements: Vector[Statement],
      source: Option[AnalogProceduralRuntime.Source] = None
  )

  final case class ConditionalBranch(condition: Condition, body: Block)

  final case class CaseArm(labels: Vector[CaseLabel], body: Block)

  sealed trait Statement:
    def identity: String
    def source: Option[AnalogProceduralRuntime.Source]

  object Statement:
    final case class Declare(
        identity: String,
        variable: String,
        initialized: scala.Boolean,
        initializerReads: Set[String] = Set.empty,
        local: scala.Boolean = false,
        source: Option[AnalogProceduralRuntime.Source] = None
    ) extends Statement

    final case class Assign(
        identity: String,
        target: String,
        reads: Set[String] = Set.empty,
        source: Option[AnalogProceduralRuntime.Source] = None
    ) extends Statement

    final case class Read(
        identity: String,
        variable: String,
        source: Option[AnalogProceduralRuntime.Source] = None
    ) extends Statement

    final case class Scope(
        identity: String,
        body: Block,
        source: Option[AnalogProceduralRuntime.Source] = None
    ) extends Statement

    final case class IfThenElse(
        identity: String,
        branches: Vector[ConditionalBranch],
        otherwise: Option[Block],
        source: Option[AnalogProceduralRuntime.Source] = None
    ) extends Statement

    final case class CaseStatement(
        identity: String,
        selector: Selector,
        arms: Vector[CaseArm],
        default: Option[Block],
        source: Option[AnalogProceduralRuntime.Source] = None
    ) extends Statement

    final case class Loop(
        identity: String,
        stage: LoopStage,
        minimumIterations: Int,
        maximumIterations: Int,
        boundReads: Set[String],
        body: Block,
        boundValueType: AnalogProceduralRuntime.ValueType =
          AnalogProceduralRuntime.ValueType(
            AnalogProceduralRuntime.ScalarKind.Integer,
            "dimensionless"
          ),
        staticTripCount: Option[Int] = None,
        source: Option[AnalogProceduralRuntime.Source] = None
    ) extends Statement

    final case class Break(
        identity: String,
        source: Option[AnalogProceduralRuntime.Source] = None
    ) extends Statement

    final case class Continue(
        identity: String,
        source: Option[AnalogProceduralRuntime.Source] = None
    ) extends Statement

  final case class Result(
      definitelyInitialized: Set[String],
      retainedControlNodes: Int,
      reachableNormalExit: scala.Boolean = true
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
    val declarations = mutable.HashSet.from(initiallyInitialized)
    initiallyInitialized.foreach: identity =>
      validateVariable(identity, "initially initialized variable", root.identity)
    validateBlock(
      root,
      identities,
      declarations,
      initiallyInitialized,
      Vector.empty,
      isRoot = true
    )
    val flow = analyzeBlock(root, initiallyInitialized, 0, retainLocals = true)
    Result(
      flow.normal.getOrElse(Set.empty),
      identities.size,
      flow.normal.nonEmpty
    )

  private def validateIdentity(identity: String, identities: mutable.Set[String]): Unit =
    val canonical = identity.trim
    if canonical.isEmpty then
      fail("NODAL-ANALOG-034-001", "control-flow identity must be non-empty")
    if canonical != identity then
      fail(
        "NODAL-ANALOG-034-001",
        "control-flow identity must already be canonical",
        Some(identity)
      )
    if identities.contains(canonical) then
      fail(
        "NODAL-ANALOG-034-001",
        s"duplicate control-flow identity '$canonical'",
        Some(canonical)
      )
    identities += canonical

  private def validateVariable(value: String, label: String, path: String): Unit =
    if value.trim.isEmpty || value.trim != value then
      fail(
        "NODAL-ANALOG-034-014",
        s"$label must be a non-empty canonical variable identity",
        Some(path)
      )

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
      case Stage.Static
          if condition.staticValue.isEmpty || condition.reads.nonEmpty =>
        fail(
          "NODAL-ANALOG-034-003",
          "static condition requires a compile-time Boolean value without dynamic reads",
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
    selector.staticValue match
      case Some(_) if selector.reads.nonEmpty =>
        fail(
          "NODAL-ANALOG-034-003",
          "static case selector cannot contain dynamic reads",
          Some(path)
        )
      case Some(value) if labelKind(value) != selector.kind =>
        fail(
          "NODAL-ANALOG-034-007",
          "static case selector value does not match selector kind",
          Some(path)
        )
      case _ => ()

  private def requireNonEmptyBlock(block: Block, label: String, path: String): Unit =
    if block.statements.isEmpty then
      fail(
        "NODAL-ANALOG-034-015",
        s"$label must contain at least one statement",
        Some(path)
      )

  private def requireVisibleReferences(
      references: Iterable[String],
      visible: Set[String],
      label: String,
      path: String
  ): Unit =
    references.foreach: reference =>
      validateVariable(reference, label, path)
    val missing = references.filterNot(visible.contains).toVector.distinct.sorted
    if missing.nonEmpty then
      fail(
        "NODAL-ANALOG-034-014",
        s"$label references variables outside their declaration scope: ${missing.mkString(",")}",
        Some(path)
      )

  private def validateBlock(
      block: Block,
      identities: mutable.Set[String],
      declarations: mutable.Set[String],
      visibleAtEntry: Set[String],
      loopStack: Vector[LoopStage],
      isRoot: scala.Boolean
  ): Unit =
    validateIdentity(block.identity, identities)
    var visible = visibleAtEntry
    block.statements.foreach:
      case declaration: Statement.Declare =>
        validateIdentity(declaration.identity, identities)
        validateVariable(declaration.variable, "declaration variable", declaration.identity)
        requireVisibleReferences(
          declaration.initializerReads,
          visible,
          "declaration initializer",
          declaration.identity
        )
        if declarations.contains(declaration.variable) then
          fail(
            "NODAL-ANALOG-034-014",
            s"duplicate control-flow variable '${declaration.variable}'",
            Some(declaration.identity)
          )
        declarations += declaration.variable
        if isRoot && declaration.local then
          fail(
            "NODAL-ANALOG-034-014",
            "root declaration cannot be marked local",
            Some(declaration.identity)
          )
        if !isRoot && !declaration.local then
          fail(
            "NODAL-ANALOG-034-014",
            "nested declaration must be block-local",
            Some(declaration.identity)
          )
        visible = visible + declaration.variable
      case assignment: Statement.Assign =>
        validateIdentity(assignment.identity, identities)
        requireVisibleReferences(
          Vector(assignment.target) ++ assignment.reads.toVector,
          visible,
          "control-flow assignment",
          assignment.identity
        )
      case read: Statement.Read =>
        validateIdentity(read.identity, identities)
        requireVisibleReferences(
          Vector(read.variable),
          visible,
          "control-flow read",
          read.identity
        )
      case scope: Statement.Scope =>
        validateIdentity(scope.identity, identities)
        validateBlock(
          scope.body,
          identities,
          declarations,
          visible,
          loopStack,
          isRoot = false
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
          val conditionPath = s"${conditional.identity}.condition_$index"
          validateCondition(branch.condition, conditionPath)
          requireVisibleReferences(
            branch.condition.reads,
            visible,
            "conditional condition",
            conditionPath
          )
          requireNonEmptyBlock(
            branch.body,
            "conditional branch",
            s"${conditional.identity}.branch_$index"
          )
          validateBlock(
            branch.body,
            identities,
            declarations,
            visible,
            loopStack,
            isRoot = false
          )
        conditional.otherwise.foreach: alternative =>
          requireNonEmptyBlock(alternative, "conditional else branch", conditional.identity)
          validateBlock(
            alternative,
            identities,
            declarations,
            visible,
            loopStack,
            isRoot = false
          )
      case selection: Statement.CaseStatement =>
        validateIdentity(selection.identity, identities)
        validateSelector(selection.selector, selection.identity)
        requireVisibleReferences(
          selection.selector.reads,
          visible,
          "case selector",
          selection.identity
        )
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
        selection.arms.zipWithIndex.foreach: (arm, index) =>
          requireNonEmptyBlock(arm.body, "case arm", s"${selection.identity}.arm_$index")
          validateBlock(
            arm.body,
            identities,
            declarations,
            visible,
            loopStack,
            isRoot = false
          )
        selection.default.foreach: alternative =>
          requireNonEmptyBlock(alternative, "case default arm", selection.identity)
          validateBlock(
            alternative,
            identities,
            declarations,
            visible,
            loopStack,
            isRoot = false
          )
      case loop: Statement.Loop =>
        validateIdentity(loop.identity, identities)
        val expectedBoundType = AnalogProceduralRuntime.ValueType(
          AnalogProceduralRuntime.ScalarKind.Integer,
          "dimensionless"
        )
        if loop.boundValueType != expectedBoundType then
          fail(
            "NODAL-ANALOG-034-008",
            "bounded loop requires a dimensionless integer bound",
            Some(loop.identity)
          )
        requireVisibleReferences(
          loop.boundReads,
          visible,
          "loop bound",
          loop.identity
        )
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
                loop.boundReads.nonEmpty ||
                !loop.staticTripCount.contains(loop.minimumIterations) =>
            fail(
              "NODAL-ANALOG-034-009",
              "static loop requires one exact compile-time trip count",
              Some(loop.identity)
            )
          case LoopStage.RuntimeBounded
              if loop.maximumIterations == 0 || loop.staticTripCount.nonEmpty =>
            fail(
              "NODAL-ANALOG-034-008",
              "runtime loop requires a positive finite maximum and a dynamic bound",
              Some(loop.identity)
            )
          case _ => ()
        validateBlock(
          loop.body,
          identities,
          declarations,
          visible,
          loopStack :+ loop.stage,
          isRoot = false
        )
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

  private def removeLocals(flow: Flow, locals: Set[String]): Flow =
    if locals.isEmpty then flow
    else
      Flow(
        flow.normal.map(_ -- locals),
        flow.breaks.map(_ -- locals),
        flow.continues.map(_ -- locals)
      )

  private def analyzeBlock(
      block: Block,
      input: Set[String],
      loopDepth: Int,
      retainLocals: scala.Boolean = false
  ): Flow =
    var normal: Option[Set[String]] = Some(input)
    val breaks = mutable.ArrayBuffer.empty[Set[String]]
    val continues = mutable.ArrayBuffer.empty[Set[String]]
    val locals = mutable.HashSet.empty[String]
    block.statements.foreach: statement =>
      statement match
        case declaration: Statement.Declare if declaration.local =>
          locals += declaration.variable
        case _ => ()
      normal.foreach: state =>
        val next = analyzeStatement(statement, state, loopDepth)
        normal = next.normal
        breaks ++= next.breaks
        continues ++= next.continues
    val flow = Flow(normal, breaks.toVector, continues.toVector)
    if retainLocals then flow else removeLocals(flow, locals.toSet)

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
    case declaration: Statement.Declare =>
      checkReads(declaration.initializerReads, input, declaration.identity)
      val output =
        if declaration.initialized then input + declaration.variable
        else input - declaration.variable
      Flow(Some(output), Vector.empty, Vector.empty)
    case assignment: Statement.Assign =>
      checkReads(assignment.reads, input, assignment.identity)
      Flow(Some(input + assignment.target), Vector.empty, Vector.empty)
    case read: Statement.Read =>
      checkReads(Set(read.variable), input, read.identity)
      Flow(Some(input), Vector.empty, Vector.empty)
    case scope: Statement.Scope =>
      analyzeBlock(scope.body, input, loopDepth)
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
