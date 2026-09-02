package nodal

import scala.collection.mutable

/** Mutable construction bridge for the public Increment 34 control-flow builders.
  *
  * The bridge records an immutable source-semantic tree. It does not execute runtime branches,
  * unroll loops, form solver equations, or emit backend text.
  */
private[nodal] object AnalogControlFlowConstruction:
  import AnalogControlFlowRuntime.*

  final case class Snapshot(
      owner: String,
      root: Block,
      analysis: Result
  ):
    def remapOwner(newOwner: String): Snapshot =
      val canonical = newOwner.trim
      if canonical.isEmpty then
        AnalogControlFlowRuntime.fail(
          "NODAL-ANALOG-034-001",
          "control-flow owner must be non-empty"
        )

      def remapPath(value: String): String =
        if value == owner then canonical
        else if value.startsWith(s"$owner.") then
          s"$canonical${value.drop(owner.length)}"
        else value

      def remapRendered(value: String): String =
        val result = new StringBuilder(value.length)
        var cursor = 0
        while cursor < value.length do
          val matchIndex = value.indexOf(owner, cursor)
          if matchIndex < 0 then
            result.append(value.substring(cursor))
            cursor = value.length
          else
            val leftBoundary =
              matchIndex == 0 || !value.charAt(matchIndex - 1).isLetterOrDigit &&
                value.charAt(matchIndex - 1) != '_' && value.charAt(matchIndex - 1) != '$'
            val end = matchIndex + owner.length
            val rightBoundary = end == value.length || value.charAt(end) == '.'
            if leftBoundary && rightBoundary then
              result.append(value.substring(cursor, matchIndex))
              result.append(canonical)
              cursor = end
            else
              result.append(value.substring(cursor, end))
              cursor = end
        result.result()

      def remapCondition(value: Condition): Condition =
        value.copy(
          rendered = remapRendered(value.rendered),
          reads = value.reads.map(remapPath)
        )

      def remapSelector(value: Selector): Selector =
        value.copy(
          rendered = remapRendered(value.rendered),
          reads = value.reads.map(remapPath)
        )

      def remapBlock(value: Block): Block =
        value.copy(
          identity = remapPath(value.identity),
          statements = value.statements.map(remapStatement)
        )

      def remapStatement(value: Statement): Statement = value match
        case declaration: Statement.Declare =>
          declaration.copy(
            identity = remapPath(declaration.identity),
            variable = remapPath(declaration.variable),
            initializerReads = declaration.initializerReads.map(remapPath)
          )
        case assignment: Statement.Assign =>
          assignment.copy(
            identity = remapPath(assignment.identity),
            target = remapPath(assignment.target),
            reads = assignment.reads.map(remapPath)
          )
        case read: Statement.Read =>
          read.copy(
            identity = remapPath(read.identity),
            variable = remapPath(read.variable)
          )
        case scope: Statement.Scope =>
          scope.copy(
            identity = remapPath(scope.identity),
            body = remapBlock(scope.body)
          )
        case conditional: Statement.IfThenElse =>
          conditional.copy(
            identity = remapPath(conditional.identity),
            branches = conditional.branches.map: branch =>
              branch.copy(
                condition = remapCondition(branch.condition),
                body = remapBlock(branch.body)
              ),
            otherwise = conditional.otherwise.map(remapBlock)
          )
        case selection: Statement.CaseStatement =>
          selection.copy(
            identity = remapPath(selection.identity),
            selector = remapSelector(selection.selector),
            arms = selection.arms.map: arm =>
              arm.copy(body = remapBlock(arm.body)),
            default = selection.default.map(remapBlock)
          )
        case loop: Statement.Loop =>
          loop.copy(
            identity = remapPath(loop.identity),
            boundReads = loop.boundReads.map(remapPath),
            body = remapBlock(loop.body)
          )
        case exit: Statement.Break =>
          exit.copy(identity = remapPath(exit.identity))
        case next: Statement.Continue =>
          next.copy(identity = remapPath(next.identity))

      copy(
        owner = canonical,
        root = remapBlock(root),
        analysis = analysis.copy(
          definitelyInitialized = analysis.definitelyInitialized.map(remapPath)
        )
      )

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
    private val root = new MutableBlock(s"$owner.procedure", None)
    private val blocks = mutable.ArrayBuffer(root)
    private val frames = mutable.ArrayBuffer.empty[GroupFrame]
    private var controlSerial = 0
    private var lexicalSerial = 0
    private var structured = false

    def hasStructuredControl: scala.Boolean = structured

    def atRoot: scala.Boolean = blocks.size == 1

    private def current: MutableBlock = blocks.last

    private def nextIdentity(kind: String): String =
      val identity = s"$owner.${kind}_$controlSerial"
      controlSerial += 1
      identity

    private def nextLexicalIdentity(): String =
      val identity = s"$owner.scope_$lexicalSerial"
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

    def lexicalScope[A](
        source: Option[AnalogProceduralRuntime.Source]
    )(body: String => A): A =
      requireStatementPosition()
      val identity = nextLexicalIdentity()
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
        source: Option[AnalogProceduralRuntime.Source]
    )(body: String => A): A =
      requireStatementPosition()
      val identity = nextIdentity("loop")
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
          staticTripCount,
          source
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
      Snapshot(owner, frozen, AnalogControlFlowRuntime.analyze(frozen))

private[nodal] object AnalogControlFlowInspection:
  def inspect(
      top: => Module,
      options: EmitOptions = EmitOptions()
  ): AnalogControlFlowConstruction.Inspection =
    val construction = ConstructionKernel.inspect(top, options)
    AnalogControlFlowConstruction.Inspection(
      construction,
      AnalogProceduralConstruction.controlSnapshots
    )
