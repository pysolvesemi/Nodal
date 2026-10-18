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

  final case class ValueType(
      kind: ScalarKind,
      dimension: String,
      dimensions: Vector[String] = Vector.empty
  ):
    require(dimension.nonEmpty, "procedural value dimension must be non-empty")

  final case class Source(file: String, line: Int, column: Int):
    require(file.nonEmpty, "procedural source file must be non-empty")
    require(line >= 1, "procedural source line must be positive")
    require(column >= 1, "procedural source column must be positive")

  sealed trait StorageReference:
    def root: Variable
    def identity: String
    def valueType: ValueType
    def owner: String = root.owner
    def declarationScope: Vector[String] = root.declarationScope
    def remap(path: String => String): StorageReference

  type StorageProjection = KernelShapeIndexSnapshot | KernelShapeViewSnapshot

  final case class Variable(
      identity: String,
      override val owner: String,
      override val declarationScope: Vector[String],
      valueType: ValueType
  ) extends StorageReference:
    def root: Variable = this
    def remap(path: String => String): Variable = copy(
      identity = path(identity),
      owner = path(owner),
      declarationScope = declarationScope.map(path),
      valueType = valueType.copy(dimensions = valueType.dimensions.map(path))
    )

  final case class StorageSelection(root: Variable, projections: Vector[StorageProjection])
      extends StorageReference:
    require(projections.nonEmpty, "storage selection requires a projection")
    def identity: String = projections.last match
      case value: KernelShapeIndexSnapshot => value.path
      case value: KernelShapeViewSnapshot => value.path
    def valueType: ValueType = root.valueType.copy(dimensions = projections.last match
      case value: KernelShapeIndexSnapshot => value.resultDimensions
      case value: KernelShapeViewSnapshot => value.dimensions)
    def remap(path: String => String): StorageSelection =
      copy(root = root.remap(path), projections = projections.map(remapProjection(_, path)))

  private[nodal] def remapProjection(
      projection: StorageProjection,
      path: String => String
  ): StorageProjection = projection match
    case value: KernelShapeIndexSnapshot => value.copy(
        path = path(value.path),
        owner = path(value.owner),
        input = path(value.input),
        indices = value.indices.map(path),
        resultDimensions = value.resultDimensions.map(path),
        sourceUses = value.sourceUses.map(path)
      )
    case value: KernelShapeViewSnapshot => value.copy(
        path = path(value.path),
        owner = path(value.owner),
        input = path(value.input),
        dimensions = value.dimensions.map(path),
        slice = value.slice.map(slice =>
          slice.copy(
            lower = path(slice.lower),
            upperExclusive = path(slice.upperExclusive)
          )
        )
      )

  sealed trait ShapedSource
  object ShapedSource:
    final case class Fill(scalar: Value) extends ShapedSource
    final case class Read(reference: StorageReference) extends ShapedSource
    final case class View(value: Value, projection: StorageProjection) extends ShapedSource

  final case class Value(
      rendered: String,
      valueType: ValueType,
      reads: Vector[StorageReference] = Vector.empty,
      source: Option[ShapedSource] = None,
      scalarSources: Vector[Value] = Vector.empty
  ):
    require(rendered.nonEmpty, "procedural value spelling must be non-empty")

    /** Typed leaves supplement the flattened list, preserving first occurrence order without
      * re-expanding shared source DAGs. Read multiplicity is not evaluation multiplicity.
      */
    def allReads: Vector[StorageReference] =
      val result = mutable.LinkedHashSet.empty[StorageReference]
      val heights = new java.util.IdentityHashMap[Value, java.lang.Integer]()
      val active = new java.util.IdentityHashMap[Value, java.lang.Boolean]()
      def invalid(): Nothing = reject(Diagnostic(
        "NODAL-SHAPE-043-007",
        "retained value is cyclic or exceeds the supported dependency depth"
      ))
      def visit(value: Value, depth: Int): Int =
        if depth >= 512 || active.containsKey(value) then invalid()
        Option(heights.get(value)) match
          case Some(height) =>
            if depth + height.intValue >= 512 then invalid()
            height.intValue
          case None =>
            active.put(value, java.lang.Boolean.TRUE)
            try
              result ++= value.reads
              var height = 0
              def child(value: Value): Unit =
                height = height.max(visit(value, depth + 1) + 1)
              value.scalarSources.foreach(child)
              value.source.foreach:
                case ShapedSource.Fill(scalar) => child(scalar)
                case ShapedSource.Read(reference) => result += reference
                case ShapedSource.View(input, _) => child(input)
              heights.put(value, java.lang.Integer.valueOf(height))
              height
            finally active.remove(value)
      visit(this, 0)
      result.toVector

    def remap(path: String => String, spelling: String => String): Value =
      val memo = new java.util.IdentityHashMap[Value, (Value, Int)]()
      val active = new java.util.IdentityHashMap[Value, java.lang.Boolean]()
      def invalid(): Nothing = reject(Diagnostic(
        "NODAL-SHAPE-043-007",
        "retained value is cyclic or exceeds the supported dependency depth"
      ))
      def visit(value: Value, depth: Int): (Value, Int) =
        if depth >= 512 || active.containsKey(value) then invalid()
        Option(memo.get(value)) match
          case Some(result) =>
            if depth + result._2 >= 512 then invalid()
            result
          case None =>
            active.put(value, java.lang.Boolean.TRUE)
            try
              var height = 0
              def child(value: Value): Value =
                val (mapped, childHeight) = visit(value, depth + 1)
                height = height.max(childHeight + 1)
                mapped
              val mapped = value.copy(
                rendered = spelling(value.rendered),
                valueType = value.valueType.copy(dimensions = value.valueType.dimensions.map(path)),
                reads = value.reads.map(_.remap(path)),
                source = value.source.map {
                  case ShapedSource.Fill(scalar) => ShapedSource.Fill(child(scalar))
                  case ShapedSource.Read(reference) => ShapedSource.Read(reference.remap(path))
                  case ShapedSource.View(input, projection) =>
                    ShapedSource.View(child(input), remapProjection(projection, path))
                },
                scalarSources = value.scalarSources.map(child)
              )
              val result = mapped -> height
              memo.put(value, result)
              result
            finally active.remove(value)
      visit(this, 0)._1

  /** Derived serialization only. Callers must first validate the retained shape graph, including
    * owned static indices, bounds and units. Occurrences deliberately retain repeated fill
    * identities so live consumers can enforce their once-only evaluation contract across a complete
    * expression.
    */
  final case class ScalarDerivation(value: Value, fillOccurrences: Vector[String])

  private def scalarLeafPattern(leaves: Vector[String]): scala.util.matching.Regex =
    val boundary = "[A-Za-z0-9_.$#@]"
    ("(?<!" + boundary + ")(?:" +
      leaves.distinct.sortBy(value => (-value.length, value))
        .map(java.util.regex.Pattern.quote).mkString("|") + ")(?!" + boundary + ")").r

  /** Canonical-source leaf matching shared with capture emission; this does not parse scalar
    * syntax.
    */
  def scalarLeafOccurrences(text: String, leaves: Vector[String]): Vector[String] =
    if leaves.isEmpty then Vector.empty
    else scalarLeafPattern(leaves).findAllIn(text).toVector

  /** Replace exact source leaves in one pass, never a prefix or a newly inserted expression. */
  def replaceScalarLeaves(text: String, bindings: Vector[(String, String)]): String =
    val grouped = bindings.groupBy(_._1)
    require(
      grouped.values.forall(_.map(_._2).distinct.size == 1),
      "one captured source leaf cannot bind different storage accesses"
    )
    if bindings.isEmpty then text
    else
      val replacements = grouped.view.mapValues(_.head._2).toMap
      scalarLeafPattern(replacements.keys.toVector).replaceAllIn(
        text,
        matched => java.util.regex.Matcher.quoteReplacement(replacements(matched.matched))
      )

  def deriveUniformScalar(input: Value): ScalarDerivation =
    val active = new java.util.IdentityHashMap[Value, java.lang.Boolean]()
    val memo = new java.util.IdentityHashMap[Value, ScalarDerivation]()
    val sources = mutable.HashMap.empty[String, Value]
    val activeSources = mutable.HashSet.empty[String]
    val equalities = new java.util.IdentityHashMap[
      Value,
      java.util.IdentityHashMap[Value, java.lang.Boolean]
    ]()
    // Values form a retained DAG. Compare exact payloads without recursive case-class equality,
    // which re-expands every shared child when the same authored fill has rebuilt wrappers.
    def sameSource(left: Value, right: Value): Boolean =
      val pending = mutable.ArrayBuffer(left -> right)
      val visited = new java.util.IdentityHashMap[
        Value,
        java.util.IdentityHashMap[Value, java.lang.Boolean]
      ]()
      val pairs = mutable.ArrayBuffer.empty[(Value, Value)]
      def lookup(
          table: java.util.IdentityHashMap[
            Value,
            java.util.IdentityHashMap[Value, java.lang.Boolean]
          ],
          first: Value,
          second: Value
      ): Option[Boolean] = Option(table.get(first)).flatMap(values =>
        Option(values.get(second)).map(_.booleanValue)
      )
      def save(
          table: java.util.IdentityHashMap[
            Value,
            java.util.IdentityHashMap[Value, java.lang.Boolean]
          ],
          first: Value,
          second: Value,
          result: Boolean
      ): Unit =
        val values = Option(table.get(first)).getOrElse:
          val created = new java.util.IdentityHashMap[Value, java.lang.Boolean]()
          table.put(first, created)
          created
        values.put(second, java.lang.Boolean.valueOf(result))
      var equal = true
      while pending.nonEmpty && equal do
        val (first, second) = pending.remove(pending.size - 1)
        if (first ne second) && lookup(visited, first, second).isEmpty then
          lookup(equalities, first, second) match
            case Some(result) => equal = result
            case None =>
              save(visited, first, second, true)
              pairs += first -> second
              equal = first.rendered == second.rendered && first.valueType == second.valueType &&
                first.reads == second.reads && first.scalarSources.size == second.scalarSources.size
              if equal then
                pending ++= first.scalarSources.zip(second.scalarSources)
                (first.source, second.source) match
                  case (None, None) => ()
                  case (Some(ShapedSource.Read(a)), Some(ShapedSource.Read(b))) => equal = a == b
                  case (Some(ShapedSource.Fill(a)), Some(ShapedSource.Fill(b))) => pending += a -> b
                  case (Some(ShapedSource.View(a, x)), Some(ShapedSource.View(b, y))) =>
                    equal = x == y
                    pending += a -> b
                  case _ => equal = false
      if equal then pairs.foreach((first, second) => save(equalities, first, second, true))
      else save(equalities, left, right, false)
      equal
    def invalid(message: String): Nothing = reject(Diagnostic("NODAL-SHAPE-043-007", message))
    def enter(value: Value, depth: Int): Unit =
      if depth >= 512 || active.containsKey(value) then
        invalid("scalar source is cyclic or exceeds the supported dependency depth")
      active.put(value, java.lang.Boolean.TRUE)
      if value.source.nonEmpty then
        if activeSources.contains(value.rendered) then
          invalid("retained scalar source identities form a cycle")
        sources.get(value.rendered).foreach: existing =>
          if !sameSource(existing, value) then
            invalid("one retained source identity describes conflicting values")
        sources.update(value.rendered, value)
        activeSources += value.rendered
    def leave(value: Value): Unit =
      active.remove(value)
      if value.source.nonEmpty then activeSources -= value.rendered
    def uniform(value: Value, depth: Int): ScalarDerivation =
      enter(value, depth)
      try
        if value.scalarSources.nonEmpty then
          invalid("a retained shape node cannot carry independent scalar source attachments")
        value.source match
          case Some(ShapedSource.Fill(scalar)) =>
            if value.valueType.dimensions.isEmpty || scalar.valueType.dimensions.nonEmpty ||
              value.valueType.kind != ScalarKind.Real || scalar.valueType.kind != ScalarKind.Real ||
              value.valueType.dimension != scalar.valueType.dimension
            then invalid("uniform scalar derivation requires an exact typed Real fill")
            val derived = scalarValue(scalar, depth + 1)
            derived.copy(fillOccurrences = value.rendered +: derived.fillOccurrences)
          case Some(ShapedSource.View(parent, projection)) =>
            val (path, inputPath) = projection match
              case selected: KernelShapeIndexSnapshot => selected.path -> selected.input
              case selected: KernelShapeViewSnapshot => selected.path -> selected.input
            if inputPath != parent.rendered || path != value.rendered || path == inputPath ||
              value.valueType.kind != parent.valueType.kind ||
              value.valueType.dimension != parent.valueType.dimension
            then
              invalid(
                "uniform scalar derivation requires the actual retained view input and result"
              )
            uniform(parent, depth + 1)
          case _ => invalid("a deferred scalar projection must descend from an actual uniform fill")
      finally leave(value)
    def scalarValue(value: Value, depth: Int): ScalarDerivation =
      if depth >= 512 then invalid("scalar source exceeds the supported dependency depth")
      Option(memo.get(value)) match
        case Some(derived) => derived
        case None =>
          if value.valueType.dimensions.nonEmpty then
            invalid("uniform scalar derivation requires a scalar consumer")
          val derived = value.source match
            case Some(_: ShapedSource.View) =>
              val result = uniform(value, depth)
              if result.value.valueType != value.valueType then
                invalid("derived scalar disagrees with its retained projection type")
              result
            case Some(_: ShapedSource.Fill) =>
              invalid("a scalar consumer cannot directly contain a shaped fill")
            case _ =>
              enter(value, depth)
              try
                if value.source.nonEmpty && value.scalarSources.nonEmpty then
                  invalid(
                    "a retained scalar read cannot carry independent scalar source attachments"
                  )
                val grouped = value.scalarSources.groupBy(_.rendered)
                if grouped.values.exists(_.size != 1) then
                  invalid("scalar source attachments must have distinct source identities")
                val occurrences = scalarLeafOccurrences(value.rendered, grouped.keys.toVector)
                if occurrences.toSet != grouped.keySet then
                  invalid(
                    "scalar source attachment is not used by its complete consumer expression"
                  )
                val leaves = value.scalarSources.map: source =>
                  if !source.source.exists(_.isInstanceOf[ShapedSource.View]) then
                    invalid("a scalar source attachment must retain its actual projection chain")
                  source.rendered -> scalarValue(source, depth + 1)
                val byToken = leaves.toMap
                val reads = value.reads ++ leaves.flatMap(_._2.value.reads) ++
                  value.source.toVector.collect { case ShapedSource.Read(reference) => reference }
                ScalarDerivation(
                  value.copy(
                    rendered = replaceScalarLeaves(
                      value.rendered,
                      leaves.map((token, result) => token -> result.value.rendered)
                    ),
                    reads = reads.distinct,
                    source = None,
                    scalarSources = Vector.empty
                  ),
                  occurrences.flatMap(token => byToken(token).fillOccurrences)
                )
              finally leave(value)
          memo.put(value, derived)
          derived
    scalarValue(input, 0)

  final case class VariableRecord(
      variable: Variable,
      initializer: Option[Value],
      source: Option[Source],
      declarationOrder: Int,
      operationOrder: Int = -1,
      authoredPath: Option[String] = None
  )

  final case class AssignmentRecord(
      identity: String,
      target: StorageReference,
      value: Value,
      authoredOrder: Int,
      scope: Vector[String],
      guard: Option[Value],
      analyses: Vector[String],
      source: Option[Source],
      operationOrder: Int = -1
  )

  /** Typed value retained for an operation nested in structured analog control flow.
    *
    * `identity` is the stable statement or loop identity from the structured tree. `role` makes the
    * attachment explicit rather than requiring consumers to infer meaning from the identity name.
    */
  final case class ControlExpressionRecord(
      identity: String,
      role: String,
      value: Value,
      source: Option[Source]
  ):
    require(identity.nonEmpty, "control expression identity must be non-empty")
    require(role.nonEmpty, "control expression role must be non-empty")

  /** Canonical procedural program retained by the construction snapshot.
    *
    * Straight-line Increment 33 programs populate `assignments`. Structured Increment 34 programs
    * populate `controlFlow` and deliberately leave `assignments` empty so runtime branches are not
    * represented as one unconditional sequence. `controlExpressions` retains typed assignment and
    * loop-bound values keyed by the stable identities in that tree.
    */
  final case class Snapshot(
      owner: String,
      variables: Vector[VariableRecord],
      assignments: Vector[AssignmentRecord],
      controlFlow: Option[AnalogControlFlowConstruction.Snapshot] = None,
      controlExpressions: Vector[ControlExpressionRecord] = Vector.empty,
      generatedOwner: Option[String] = None,
      generatedInduction: Option[String] = None
  )

  final case class Diagnostic(code: String, message: String, path: Option[String] = None):
    override def toString: String = path match
      case Some(value) => s"$code: $message [$value]"
      case None => s"$code: $message"

  final class Failure(val diagnostic: Diagnostic)
      extends IllegalArgumentException(diagnostic.toString)

  private[nodal] def reject(diagnostic: Diagnostic): Nothing =
    scala.util.Failure[Nothing](new Failure(diagnostic)).get

  private final case class MutableVariable(record: VariableRecord)

  final class Recorder(val owner: String):
    require(owner.nonEmpty, "procedural owner must be non-empty")

    private val variables = mutable.LinkedHashMap.empty[String, MutableVariable]
    private var coverage = AnalogStorageCoverage.State.empty
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

    private def resolve(reference: StorageReference): MutableVariable =
      val variable = reference.root
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
      if state.record.variable != variable then
        fail(
          "NODAL-ANALOG-033-017",
          "procedural storage reference disagrees with its declaration",
          Some(reference.identity)
        )
      AnalogStorageCoverage.normalize(reference)
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
      if initializer.valueType.dimensions != variable.valueType.dimensions then
        fail(
          "NODAL-ANALOG-033-004",
          "initializer shape must exactly match the procedural variable",
          Some(variable.identity)
        )
      if variable.valueType.dimensions.nonEmpty &&
        initializer.valueType.kind != variable.valueType.kind
      then
        fail(
          "NODAL-ANALOG-033-004",
          "shaped initialization requires exact element kind",
          Some(variable.identity)
        )
      if initializer.valueType.dimension != variable.valueType.dimension then
        fail(
          "NODAL-ANALOG-033-005",
          s"initializer dimension '${initializer.valueType.dimension}' does not match '${variable.valueType.dimension}'",
          Some(variable.identity)
        )

    private def validateValueSource(value: Value): Unit =
      def invalid(): Nothing = fail(
        "NODAL-ANALOG-033-004",
        "procedural shaped source disagrees with its exact shape, kind, or physical dimension"
      )
      value.scalarSources.foreach(validateValueSource)
      value.source match
        case None => if value.valueType.dimensions.nonEmpty then invalid()
        case Some(ShapedSource.Fill(scalar)) =>
          validateValueSource(scalar)
          if value.valueType.dimensions.isEmpty || scalar.valueType.dimensions.nonEmpty ||
            value.valueType.kind != ScalarKind.Real || scalar.valueType.kind != ScalarKind.Real ||
            value.valueType.dimension != scalar.valueType.dimension
          then invalid()
        case Some(ShapedSource.Read(reference)) =>
          if value.valueType != reference.valueType then invalid()
        case Some(ShapedSource.View(input, projection)) =>
          validateValueSource(input)
          val (owner, parent) = projection match
            case index: KernelShapeIndexSnapshot => (index.owner, index.input)
            case view: KernelShapeViewSnapshot => (view.owner, view.input)
          val selected = StorageSelection(
            Variable(parent, owner, Vector.empty, input.valueType),
            Vector(projection)
          )
          if input.valueType.dimensions.isEmpty || value.valueType != selected.valueType then
            invalid()
          AnalogStorageCoverage.normalize(selected)

    private def validateReads(value: Value): Unit =
      validateValueSource(value)
      value.allReads.distinct.foreach: read =>
        resolve(read)
        if !coverage.contains(read) then
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
      if valueType.dimensions.nonEmpty && valueType.kind != ScalarKind.Real then
        fail(
          "NODAL-ANALOG-033-004",
          "shaped procedural storage requires Real elements",
          Some(canonical)
        )
      val variable = Variable(canonical, owner, scopeStack, valueType)
      AnalogStorageCoverage.normalize(variable)
      initializer.foreach(validateInitializer(variable, _))
      initializer.foreach(validateReads)
      val record = VariableRecord(
        variable,
        initializer,
        source,
        variables.size,
        variables.size + assignments.size
      )
      variables += canonical -> MutableVariable(record)
      coverage = if initializer.nonEmpty then coverage + canonical else coverage - canonical
      variable

    def read(variable: StorageReference): Value =
      requireProcedure(Some(variable.identity))
      resolve(variable)
      if !coverage.contains(variable) then
        fail(
          "NODAL-ANALOG-033-011",
          s"procedural variable '${variable.identity}' is read before initialization or an earlier assignment",
          Some(variable.identity)
        )
      Value(
        variable.identity,
        variable.valueType,
        Vector(variable),
        Some(ShapedSource.Read(variable))
      )

    def reference(variable: StorageReference): Value =
      Value(
        variable.identity,
        variable.valueType,
        Vector(variable),
        Some(ShapedSource.Read(variable))
      )

    def assign(
        identity: String,
        target: StorageReference,
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
      resolve(target)
      validateReads(value)
      if !compatible(value.valueType.kind, target.valueType.kind) then
        fail(
          "NODAL-ANALOG-033-012",
          s"assigned kind '${value.valueType.kind.label}' is incompatible with '${target.valueType.kind.label}'",
          Some(canonicalStatement)
        )
      if value.valueType.dimensions != target.valueType.dimensions ||
        target.valueType.dimensions.nonEmpty && value.valueType.kind != target.valueType.kind
      then
        fail(
          "NODAL-ANALOG-033-012",
          "assignment requires exact shape and element kind",
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
      coverage = coverage.write(target)

    def snapshot: Snapshot = Snapshot(
      owner,
      variables.valuesIterator.map(_.record).toVector,
      assignments.toVector
    )
