package nodal

import scala.collection.mutable

/** Mutable construction bridge for the public Increment 34 control-flow builders.
  *
  * The bridge records an immutable source-semantic tree. It does not execute runtime branches,
  * unroll loops, form solver equations, or emit backend text.
  */
private[nodal] object AnalogControlFlowConstruction:
  import AnalogControlFlowRuntime.*

  private def requireCanonicalOwner(value: String, role: String): String =
    val canonical = value.trim
    if canonical.isEmpty || canonical != value then
      AnalogControlFlowRuntime.fail(
        "NODAL-ANALOG-034-001",
        s"$role must be non-empty and canonical"
      )
    canonical

  final case class Snapshot(
      owner: String,
      root: Block,
      analysis: Result
  ):
    def mapEvents(transform: AnalogEventRuntime.Expression => AnalogEventRuntime.Expression)
        : Snapshot =
      def block(value: Block): Block = value.copy(statements = value.statements.map(statement))
      def statement(value: Statement): Statement = value match
        case event: Statement.EventControl =>
          event.copy(event = transform(event.event), body = block(event.body))
        case scope: Statement.Scope => scope.copy(body = block(scope.body))
        case conditional: Statement.IfThenElse => conditional.copy(
            branches = conditional.branches.map(branch => branch.copy(body = block(branch.body))),
            otherwise = conditional.otherwise.map(block)
          )
        case selection: Statement.CaseStatement => selection.copy(
            arms = selection.arms.map(arm => arm.copy(body = block(arm.body))),
            default = selection.default.map(block)
          )
        case loop: Statement.Loop => loop.copy(body = block(loop.body))
        case other => other
      copy(root = block(root))

    def mapRendered(transform: String => String): Snapshot =
      def block(value: Block): Block = value.copy(statements = value.statements.map(statement))
      def statement(value: Statement): Statement = value match
        case event: Statement.EventControl =>
          event.copy(event = event.event.remap(identity, transform), body = block(event.body))
        case scope: Statement.Scope => scope.copy(body = block(scope.body))
        case conditional: Statement.IfThenElse => conditional.copy(
            branches = conditional.branches.map(branch =>
              branch.copy(
                condition = branch.condition.copy(rendered = transform(branch.condition.rendered)),
                body = block(branch.body)
              )
            ),
            otherwise = conditional.otherwise.map(block)
          )
        case selection: Statement.CaseStatement => selection.copy(
            selector = selection.selector.copy(rendered = transform(selection.selector.rendered)),
            arms = selection.arms.map(arm => arm.copy(body = block(arm.body))),
            default = selection.default.map(block)
          )
        case loop: Statement.Loop => loop.copy(body = block(loop.body))
        case other => other
      copy(root = block(root))

    /** Rewrites only retained semantic references, including immutable selected-access payloads. */
    def mapReferences(path: String => String): Snapshot =
      def condition(value: Condition): Condition = value.copy(
        reads = value.reads.map(path),
        valueType = value.valueType.copy(dimensions = value.valueType.dimensions.map(path)),
        selectedReads = value.selectedReads.map(_.remap(path))
      )
      def selector(value: Selector): Selector = value.copy(
        reads = value.reads.map(path),
        selectedReads = value.selectedReads.map(_.remap(path))
      )
      def block(value: Block): Block = value.copy(
        identity = path(value.identity),
        statements = value.statements.map(statement)
      )
      def statement(value: Statement): Statement = value match
        case declaration: Statement.Declare => declaration.copy(
            identity = path(declaration.identity),
            variable = path(declaration.variable),
            initializerReads = declaration.initializerReads.map(path),
            storageVariable = declaration.storageVariable.map(_.remap(path)),
            initializerSelectedReads = declaration.initializerSelectedReads.map(_.remap(path))
          )
        case assignment: Statement.Assign => assignment.copy(
            identity = path(assignment.identity),
            target = path(assignment.target),
            reads = assignment.reads.map(path),
            storageTarget = assignment.storageTarget.map(_.remap(path)),
            selectedReads = assignment.selectedReads.map(_.remap(path))
          )
        case read: Statement.Read => read.copy(
            identity = path(read.identity),
            variable = path(read.variable),
            storageReference = read.storageReference.map(_.remap(path))
          )
        case event: Statement.EventControl => event.copy(
            identity = path(event.identity),
            event = event.event.remap(path, identity),
            body = block(event.body)
          )
        case scope: Statement.Scope =>
          scope.copy(identity = path(scope.identity), body = block(scope.body))
        case conditional: Statement.IfThenElse => conditional.copy(
            identity = path(conditional.identity),
            branches = conditional.branches.map(branch =>
              branch.copy(
                condition = condition(branch.condition),
                body = block(branch.body)
              )
            ),
            otherwise = conditional.otherwise.map(block)
          )
        case selection: Statement.CaseStatement => selection.copy(
            identity = path(selection.identity),
            selector = selector(selection.selector),
            arms = selection.arms.map(arm => arm.copy(body = block(arm.body))),
            default = selection.default.map(block)
          )
        case loop: Statement.Loop => loop.copy(
            identity = path(loop.identity),
            boundReads = loop.boundReads.map(path),
            boundValueType = loop.boundValueType.copy(
              dimensions = loop.boundValueType.dimensions.map(path)
            ),
            boundSelectedReads = loop.boundSelectedReads.map(_.remap(path)),
            body = block(loop.body)
          )
        case exit: Statement.Break => exit.copy(identity = path(exit.identity))
        case next: Statement.Continue => next.copy(identity = path(next.identity))
      val mapped = block(root)
      copy(owner = path(owner), root = mapped, analysis = AnalogControlFlowRuntime.analyze(mapped))

    def remapOwner(newOwner: String): Snapshot =
      val currentOwner = requireCanonicalOwner(owner, "control-flow source owner")
      val canonical = requireCanonicalOwner(newOwner, "control-flow destination owner")

      def remapPath(value: String): String =
        if value == currentOwner then canonical
        else if value.startsWith(s"$currentOwner.") then
          s"$canonical${value.drop(currentOwner.length)}"
        else value

      def remapRendered(value: String): String =
        val result = new StringBuilder(value.length)
        var cursor = 0
        while cursor < value.length do
          val matchIndex = value.indexOf(currentOwner, cursor)
          if matchIndex < 0 then
            result.append(value.substring(cursor))
            cursor = value.length
          else
            val leftBoundary =
              matchIndex == 0 || !value.charAt(matchIndex - 1).isLetterOrDigit &&
                value.charAt(matchIndex - 1) != '_' && value.charAt(matchIndex - 1) != '$'
            val end = matchIndex + currentOwner.length
            val rightBoundary = end == value.length || value.charAt(end) == '.'
            if leftBoundary && rightBoundary then
              result.append(value.substring(cursor, matchIndex))
              result.append(canonical)
              cursor = end
            else
              result.append(value.substring(cursor, end))
              cursor = end
        result.result()

      mapReferences(remapPath).mapRendered(remapRendered).copy(owner = canonical)

  final case class Inspection(
      construction: ConstructionSnapshot,
      controlFlow: Vector[Snapshot]
  )

  private final class MutableBlock(
      val identity: String,
      val source: Option[AnalogProceduralRuntime.Source]
  ):
    val statements: mutable.ArrayBuffer[Statement] = mutable.ArrayBuffer.empty

    def freeze: Block = Block(identity, statements.toVector, source)

  private sealed trait GroupFrame:
    def identity: String
    def parentDepth: Int

  private final class ConditionalFrame(
      val identity: String,
      val parentDepth: Int,
      val source: Option[AnalogProceduralRuntime.Source]
  ) extends GroupFrame:
    val branches: mutable.ArrayBuffer[ConditionalBranch] = mutable.ArrayBuffer.empty
    var otherwise: Option[Block] = None

  private final class CaseFrame(
      val identity: String,
      val parentDepth: Int,
      val selector: Selector,
      val source: Option[AnalogProceduralRuntime.Source]
  ) extends GroupFrame:
    val arms: mutable.ArrayBuffer[CaseArm] = mutable.ArrayBuffer.empty
    var default: Option[Block] = None

  final class Builder(val owner: String):
    private val canonicalOwner = requireCanonicalOwner(owner, "control-flow owner")
    private val root = new MutableBlock(s"$canonicalOwner.procedure", None)
    private val blocks = mutable.ArrayBuffer(root)
    private val frames = mutable.ArrayBuffer.empty[GroupFrame]
    private var controlSerial = 0
    private var lexicalSerial = 0
    private var structured = false

    def hasStructuredControl: scala.Boolean = structured

    def atRoot: scala.Boolean = blocks.size == 1

    private def current: MutableBlock = blocks.last

    private def nextIdentity(kind: String): String =
      val identity = s"$canonicalOwner.${kind}_$controlSerial"
      controlSerial += 1
      identity

    private def nextLexicalIdentity(): String =
      val identity = s"$canonicalOwner.scope_$lexicalSerial"
      lexicalSerial += 1
      identity

    private def requireStatementPosition(path: Option[String] = None): Unit =
      frames.lastOption.foreach: frame =>
        if blocks.size == frame.parentDepth then
          AnalogControlFlowRuntime.fail(
            "NODAL-ANALOG-034-015",
            "control-flow group bodies may contain only arms and an optional fallback",
            path.orElse(Some(frame.identity))
          )

    private def append(statement: Statement): Unit =
      requireStatementPosition(Some(statement.identity))
      current.statements += statement

    private def captureBlock[A](
        identity: String,
        source: Option[AnalogProceduralRuntime.Source]
    )(body: String => A): (Block, A) =
      val block = new MutableBlock(identity, source)
      blocks += block
      try
        val result = body(identity)
        block.freeze -> result
      finally blocks.remove(blocks.size - 1)

    def appendDeclaration(statement: Statement.Declare): Unit = append(statement)

    def appendAssignment(statement: Statement.Assign): Unit = append(statement)

    def appendRead(statement: Statement.Read): Unit = append(statement)

    def eventControl[A](
        event: AnalogEventRuntime.Expression,
        source: Option[AnalogProceduralRuntime.Source]
    )(body: String => A): A =
      requireStatementPosition()
      structured = true
      val identity = nextIdentity("event")
      val (block, result) = captureBlock(s"$identity.body", source)(body)
      append(Statement.EventControl(identity, event, block, source))
      result

    def lexicalScope[A](
        source: Option[AnalogProceduralRuntime.Source],
        identityOverride: Option[String] = None
    )(body: String => A): A =
      requireStatementPosition()
      val identity = identityOverride.getOrElse(nextLexicalIdentity())
      val (block, result) = captureBlock(s"$identity.body", source)(body)
      append(Statement.Scope(identity, block, source))
      result

    def conditional[A](
        source: Option[AnalogProceduralRuntime.Source]
    )(body: => A): A =
      requireStatementPosition()
      val frame = new ConditionalFrame(
        nextIdentity("if"),
        blocks.size,
        source
      )
      structured = true
      frames += frame
      var completed = false
      try
        val result = body
        completed = true
        result
      finally
        frames.remove(frames.size - 1)
        if completed then
          append(
            Statement.IfThenElse(
              frame.identity,
              frame.branches.toVector,
              frame.otherwise,
              frame.source
            )
          )

    def conditionalBranch[A](
        condition: Condition,
        first: scala.Boolean
    )(body: String => A): A =
      frames.lastOption match
        case Some(frame: ConditionalFrame) if blocks.size == frame.parentDepth =>
          if frame.otherwise.nonEmpty then
            AnalogControlFlowRuntime.fail(
              "NODAL-ANALOG-034-015",
              "conditional branch cannot follow analogOtherwise",
              Some(frame.identity)
            )
          if first && frame.branches.nonEmpty then
            AnalogControlFlowRuntime.fail(
              "NODAL-ANALOG-034-015",
              "analogWhen must be the first branch in analogConditional",
              Some(frame.identity)
            )
          if !first && frame.branches.isEmpty then
            AnalogControlFlowRuntime.fail(
              "NODAL-ANALOG-034-015",
              "analogElseWhen requires a preceding analogWhen branch",
              Some(frame.identity)
            )
          val index = frame.branches.size
          val (block, result) = captureBlock(
            s"${frame.identity}.branch_$index",
            condition.source
          )(body)
          frame.branches += ConditionalBranch(condition, block)
          result
        case _ =>
          AnalogControlFlowRuntime.fail(
            "NODAL-ANALOG-034-015",
            "analogWhen or analogElseWhen requires an active analogConditional group"
          )

    def conditionalOtherwise[A](
        source: Option[AnalogProceduralRuntime.Source]
    )(body: String => A): A =
      frames.lastOption match
        case Some(frame: ConditionalFrame) if blocks.size == frame.parentDepth =>
          if frame.otherwise.nonEmpty then
            AnalogControlFlowRuntime.fail(
              "NODAL-ANALOG-034-015",
              "analogConditional permits only one analogOtherwise branch",
              Some(frame.identity)
            )
          if frame.branches.isEmpty then
            AnalogControlFlowRuntime.fail(
              "NODAL-ANALOG-034-015",
              "analogOtherwise requires a preceding analogWhen branch",
              Some(frame.identity)
            )
          val (block, result) = captureBlock(s"${frame.identity}.otherwise", source)(body)
          frame.otherwise = Some(block)
          result
        case _ =>
          AnalogControlFlowRuntime.fail(
            "NODAL-ANALOG-034-015",
            "analogOtherwise requires an active analogConditional group"
          )

    def caseSelection[A](
        selector: Selector,
        source: Option[AnalogProceduralRuntime.Source]
    )(body: => A): A =
      requireStatementPosition()
      val frame = new CaseFrame(
        nextIdentity("case"),
        blocks.size,
        selector,
        source
      )
      structured = true
      frames += frame
      var completed = false
      try
        val result = body
        completed = true
        result
      finally
        frames.remove(frames.size - 1)
        if completed then
          append(
            Statement.CaseStatement(
              frame.identity,
              frame.selector,
              frame.arms.toVector,
              frame.default,
              frame.source
            )
          )

    def caseArm[A](
        labels: Vector[CaseLabel],
        source: Option[AnalogProceduralRuntime.Source]
    )(body: String => A): A =
      frames.lastOption match
        case Some(frame: CaseFrame) if blocks.size == frame.parentDepth =>
          if frame.default.nonEmpty then
            AnalogControlFlowRuntime.fail(
              "NODAL-ANALOG-034-015",
              "case arm cannot follow analogCaseDefault",
              Some(frame.identity)
            )
          val index = frame.arms.size
          val (block, result) = captureBlock(
            s"${frame.identity}.arm_$index",
            source
          )(body)
          frame.arms += CaseArm(labels, block)
          result
        case _ =>
          AnalogControlFlowRuntime.fail(
            "NODAL-ANALOG-034-015",
            "analogCaseArm requires an active analogCase group"
          )

    def caseDefault[A](
        source: Option[AnalogProceduralRuntime.Source]
    )(body: String => A): A =
      frames.lastOption match
        case Some(frame: CaseFrame) if blocks.size == frame.parentDepth =>
          if frame.default.nonEmpty then
            AnalogControlFlowRuntime.fail(
              "NODAL-ANALOG-034-015",
              "analogCase permits only one analogCaseDefault arm",
              Some(frame.identity)
            )
          val (block, result) = captureBlock(s"${frame.identity}.default", source)(body)
          frame.default = Some(block)
          result
        case _ =>
          AnalogControlFlowRuntime.fail(
            "NODAL-ANALOG-034-015",
            "analogCaseDefault requires an active analogCase group"
          )

    def loop[A](
        stage: LoopStage,
        minimumIterations: Int,
        maximumIterations: Int,
        boundReads: Set[String],
        boundValueType: AnalogProceduralRuntime.ValueType,
        staticTripCount: Option[Int],
        source: Option[AnalogProceduralRuntime.Source],
        boundSelectedReads: Vector[AnalogProceduralRuntime.StorageReference] = Vector.empty
    )(body: String => A): A =
      requireStatementPosition()
      val identity = nextIdentity("loop")
      val retainedStaticTripCount = stage match
        case LoopStage.Static =>
          val domain = IterationDomain.repeat(minimumIterations).fold(
            problem =>
              AnalogControlFlowRuntime.fail(
                "NODAL-ANALOG-034-008",
                problem.message,
                Some(identity)
              ),
            value => value
          )
          if maximumIterations < domain.tripCount then
            AnalogControlFlowRuntime.fail(
              "NODAL-ANALOG-034-008",
              "bounded loop requires 0 <= minimum <= maximum",
              Some(identity)
            )
          if maximumIterations != domain.tripCount || boundReads.nonEmpty ||
            boundSelectedReads.nonEmpty ||
            !staticTripCount.contains(domain.tripCount)
          then
            AnalogControlFlowRuntime.fail(
              "NODAL-ANALOG-034-009",
              "static loop requires one exact compile-time trip count",
              Some(identity)
            )
          Some(domain.tripCount)
        case LoopStage.RuntimeBounded => staticTripCount
      structured = true
      val (block, result) = captureBlock(s"$identity.body", source)(body)
      append(
        Statement.Loop(
          identity,
          stage,
          minimumIterations,
          maximumIterations,
          boundReads,
          block,
          boundValueType,
          retainedStaticTripCount,
          source,
          boundSelectedReads
        )
      )
      result

    def breakStatement(source: Option[AnalogProceduralRuntime.Source]): Unit =
      structured = true
      append(Statement.Break(nextIdentity("break"), source))

    def continueStatement(source: Option[AnalogProceduralRuntime.Source]): Unit =
      structured = true
      append(Statement.Continue(nextIdentity("continue"), source))

    def finish(): Snapshot =
      if frames.nonEmpty || blocks.size != 1 then
        AnalogControlFlowRuntime.fail(
          "NODAL-ANALOG-034-015",
          "control-flow construction ended with an open group or block"
        )
      val frozen = root.freeze
      Snapshot(canonicalOwner, frozen, AnalogControlFlowRuntime.analyze(frozen))

private[nodal] object AnalogControlFlowInspection:
  def inspect(
      top: => Module,
      options: EmitOptions = EmitOptions()
  ): AnalogControlFlowConstruction.Inspection =
    val construction = ConstructionKernel.inspect(top, options)
    AnalogControlFlowConstruction.Inspection(
      construction,
      construction.analogProcedural.flatMap(_.controlFlow)
    )
