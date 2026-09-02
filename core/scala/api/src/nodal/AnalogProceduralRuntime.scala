package nodal

import scala.collection.mutable

/** Compiler-owned source-semantic model for Increment 33 analog variables and ordered assignment.
  *
  * This runtime deliberately stops before control-flow, solver, or backend execution. It preserves
  * authored declaration and statement order so later compiler stages can lower the program without
  * conflating `:=` with equations or additive contributions.
  */
private[nodal] object AnalogProceduralRuntime:
  enum ScalarKind(val label: String):
    case Integer extends ScalarKind("integer")
    case Real extends ScalarKind("real")
    case Boolean extends ScalarKind("boolean")

  final case class ValueType(kind: ScalarKind, dimension: String):
    require(dimension.nonEmpty, "procedural value dimension must be non-empty")

  final case class Source(file: String, line: Int, column: Int):
    require(file.nonEmpty, "procedural source file must be non-empty")
    require(line >= 1, "procedural source line must be positive")
    require(column >= 1, "procedural source column must be positive")

  final case class Variable(
      identity: String,
      owner: String,
      declarationScope: Vector[String],
      valueType: ValueType
  )

  final case class Value(
      rendered: String,
      valueType: ValueType,
      reads: Vector[Variable] = Vector.empty
  ):
    require(rendered.nonEmpty, "procedural value spelling must be non-empty")

  final case class VariableRecord(
      variable: Variable,
      initializer: Option[Value],
      source: Option[Source],
      declarationOrder: Int,
      operationOrder: Int = -1
  )

  final case class AssignmentRecord(
      identity: String,
      target: Variable,
      value: Value,
      authoredOrder: Int,
      scope: Vector[String],
      guard: Option[Value],
      analyses: Vector[String],
      source: Option[Source],
      operationOrder: Int = -1
  )

  final case class Snapshot(
      owner: String,
      variables: Vector[VariableRecord],
      assignments: Vector[AssignmentRecord]
  )

  final case class Diagnostic(code: String, message: String, path: Option[String] = None):
    override def toString: String = path match
      case Some(value) => s"$code: $message [$value]"
      case None => s"$code: $message"

  final class Failure(val diagnostic: Diagnostic)
      extends IllegalArgumentException(diagnostic.toString)

  private[nodal] def reject(diagnostic: Diagnostic): Nothing =
    scala.util.Failure[Nothing](new Failure(diagnostic)).get

  private final case class MutableVariable(record: VariableRecord, var initialized: Boolean)

  final class Recorder(val owner: String):
    require(owner.nonEmpty, "procedural owner must be non-empty")

    private val variables = mutable.LinkedHashMap.empty[String, MutableVariable]
    private val assignments = mutable.ArrayBuffer.empty[AssignmentRecord]
    private val statements = mutable.HashSet.empty[String]
    private var procedureActive = false
    private var procedureSeen = false
    private var scopeStack = Vector.empty[String]
    private var scopeSerial = 0

    private val knownAnalyses = Set(
      "initialization",
      "operating-point",
      "dc",
      "transient",
      "ac",
      "noise"
    )

    private def fail(code: String, message: String, path: Option[String] = None): Nothing =
      reject(Diagnostic(code, message, path))

    private def requireProcedure(path: Option[String] = None): Unit =
      if !procedureActive then
        fail(
          "NODAL-ANALOG-033-008",
          "analog procedural operation requires an active procedural region",
          path
        )

    private def isVisible(variable: Variable): Boolean =
      scopeStack.startsWith(variable.declarationScope)

    private def resolve(variable: Variable): MutableVariable =
      if variable.owner != owner then
        fail(
          "NODAL-ANALOG-033-009",
          s"variable '${variable.identity}' belongs to component '${variable.owner}', not '$owner'",
          Some(variable.identity)
        )
      val state = variables.getOrElse(
        variable.identity,
        fail(
          "NODAL-ANALOG-033-017",
          s"unknown procedural variable '${variable.identity}'",
          Some(variable.identity)
        )
      )
      if !isVisible(variable) then
        fail(
          "NODAL-ANALOG-033-010",
          s"procedural variable '${variable.identity}' is outside its lexical scope",
          Some(variable.identity)
        )
      state

    private def compatible(source: ScalarKind, destination: ScalarKind): Boolean =
      source == destination ||
        (source == ScalarKind.Integer && destination == ScalarKind.Real)

    private def validateInitializer(variable: Variable, initializer: Value): Unit =
      if !compatible(initializer.valueType.kind, variable.valueType.kind) then
        fail(
          "NODAL-ANALOG-033-004",
          s"initializer kind '${initializer.valueType.kind.label}' is incompatible with '${variable.valueType.kind.label}'",
          Some(variable.identity)
        )
      if initializer.valueType.dimension != variable.valueType.dimension then
        fail(
          "NODAL-ANALOG-033-005",
          s"initializer dimension '${initializer.valueType.dimension}' does not match '${variable.valueType.dimension}'",
          Some(variable.identity)
        )

    private def validateReads(value: Value): Unit = value.reads.foreach: read =>
      val state = resolve(read)
      if !state.initialized then
        fail(
          "NODAL-ANALOG-033-011",
          s"procedural variable '${read.identity}' is read before initialization or an earlier assignment",
          Some(read.identity)
        )

    def procedure[A](body: => A): A =
      if procedureActive then
        fail("NODAL-ANALOG-033-018", "nested analog procedural regions are not supported")
      if procedureSeen then
        fail(
          "NODAL-ANALOG-033-020",
          "multiple analog procedural regions per component are deferred"
        )
      procedureSeen = true
      procedureActive = true
      scopeStack = Vector("procedure")
      try body
      finally
        scopeStack = Vector.empty
        procedureActive = false

    def scope[A](name: String)(body: => A): A =
      requireProcedure()
      if name.trim.isEmpty then
        fail("NODAL-ANALOG-033-016", "procedural lexical scope identity must be non-empty")
      scopeSerial += 1
      val stableName = s"${name.trim}#$scopeSerial"
      scopeStack = scopeStack :+ stableName
      try body
      finally scopeStack = scopeStack.dropRight(1)

    def declare(
        identity: String,
        valueType: ValueType,
        initializer: Option[Value] = None,
        source: Option[Source] = None
    ): Variable =
      requireProcedure(Option(identity).filter(_.nonEmpty))
      if identity.trim.isEmpty then
        fail("NODAL-ANALOG-033-001", "procedural variable identity must be non-empty")
      val canonical = s"$owner.${scopeStack.mkString(".")}.${identity.trim}"
      if variables.contains(canonical) then
        fail(
          "NODAL-ANALOG-033-002",
          s"duplicate procedural variable identity '$canonical'",
          Some(canonical)
        )
      if valueType.kind == ScalarKind.Boolean && valueType.dimension != "dimensionless" then
        fail(
          "NODAL-ANALOG-033-019",
          "Boolean procedural variables must be dimensionless",
          Some(canonical)
        )
      val variable = Variable(canonical, owner, scopeStack, valueType)
      initializer.foreach(validateInitializer(variable, _))
      initializer.foreach(validateReads)
      val record = VariableRecord(
        variable,
        initializer,
        source,
        variables.size,
        variables.size + assignments.size
      )
      variables += canonical -> MutableVariable(record, initializer.nonEmpty)
      variable

    def read(variable: Variable): Value =
      requireProcedure(Some(variable.identity))
      val state = resolve(variable)
      if !state.initialized then
        fail(
          "NODAL-ANALOG-033-011",
          s"procedural variable '${variable.identity}' is read before initialization or an earlier assignment",
          Some(variable.identity)
        )
      Value(variable.identity, variable.valueType, Vector(variable))

    def reference(variable: Variable): Value =
      Value(variable.identity, variable.valueType, Vector(variable))

    def assign(
        identity: String,
        target: Variable,
        value: Value,
        guard: Option[Value] = None,
        analyses: Set[String] = Set("dc", "transient"),
        source: Option[Source] = None
    ): Unit =
      requireProcedure(Option(identity).filter(_.nonEmpty))
      if identity.trim.isEmpty then
        fail("NODAL-ANALOG-033-006", "procedural statement identity must be non-empty")
      val canonicalStatement = s"$owner.${identity.trim}"
      if statements.contains(canonicalStatement) then
        fail(
          "NODAL-ANALOG-033-007",
          s"duplicate procedural statement identity '$canonicalStatement'",
          Some(canonicalStatement)
        )
      val targetState = resolve(target)
      validateReads(value)
      if !compatible(value.valueType.kind, target.valueType.kind) then
        fail(
          "NODAL-ANALOG-033-012",
          s"assigned kind '${value.valueType.kind.label}' is incompatible with '${target.valueType.kind.label}'",
          Some(canonicalStatement)
        )
      if value.valueType.dimension != target.valueType.dimension then
        fail(
          "NODAL-ANALOG-033-013",
          s"assigned dimension '${value.valueType.dimension}' does not match '${target.valueType.dimension}'",
          Some(canonicalStatement)
        )
      guard.foreach: condition =>
        validateReads(condition)
        if condition.valueType != ValueType(ScalarKind.Boolean, "dimensionless") then
          fail(
            "NODAL-ANALOG-033-014",
            "procedural assignment guard must be a dimensionless Boolean value",
            Some(canonicalStatement)
          )
      val canonicalAnalyses = analyses.iterator.map(_.trim).filter(_.nonEmpty).toSet
      if canonicalAnalyses.isEmpty || !canonicalAnalyses.subsetOf(knownAnalyses) then
        fail(
          "NODAL-ANALOG-033-015",
          s"invalid procedural analysis applicability: ${canonicalAnalyses.toVector.sorted.mkString(",")}",
          Some(canonicalStatement)
        )
      assignments += AssignmentRecord(
        canonicalStatement,
        target,
        value,
        assignments.size,
        scopeStack,
        guard,
        canonicalAnalyses.toVector.sorted,
        source,
        variables.size + assignments.size
      )
      statements += canonicalStatement
      targetState.initialized = true

    def snapshot: Snapshot = Snapshot(
      owner,
      variables.valuesIterator.map(_.record).toVector,
      assignments.toVector
    )
