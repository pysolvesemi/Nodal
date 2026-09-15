package nodal

import java.lang.ScopedValue
import java.util.IdentityHashMap
import scala.collection.mutable

/** Detached body recording makes failed definitions transactional. Only a successfully checked
  * immutable definition enters the owning construction session. No body expression leaks into the
  * caller's analog region, and no mutable registry is shared across elaborations.
  */
private[nodal] object AnalogUserFunctionRuntime:
  val CallPrefix = "analog_user_call_v1_"
  private val BodyPrefix = "analog_user_body_v1_"
  private val Current: ScopedValue[Body] = ScopedValue.newInstance[Body]()

  def fail(number: Int, message: String): Nothing =
    scala.util.Failure[Nothing](
      new ConstructionException(KernelDiagnostic(f"NODAL-ANALOG-041-$number%03d", message))
    ).get

  def rejectEffect(): Unit =
    if Current.isBound then
      fail(1, "analog function bodies permit only explicit inputs, locals and pure expressions")

  def capture(value: AnyRef): Boolean =
    if !Current.isBound then false
    else
      value match
        case expression: KernelExpr[?] => Current.get.record(expression)
        case _ => fail(1, "unsupported construction inside an analog function")
      true

  private[nodal] final case class ValueType(kind: String, dimension: String)
  private[nodal] final case class Node(
      operation: String,
      valueType: ValueType,
      operands: Vector[Int] = Vector.empty,
      name: String = "",
      literal: String = "",
      callee: String = "",
      source: Option[AnalogProceduralRuntime.Source] = None
  )
  private[nodal] final case class Definition(
      name: String,
      result: ValueType,
      nodes: Vector[Node],
      returned: Int,
      source: Option[AnalogProceduralRuntime.Source],
      returnSource: Option[AnalogProceduralRuntime.Source]
  ):
    def inputs: Vector[Node] = nodes.filter(_.operation == "input")
  private[nodal] final case class Snapshot(owner: String, definition: Definition)

  private val aliases = Map(
    "dimensionless" -> "1",
    "frequency" -> "time^-1",
    "charge" -> "current*time",
    "power" -> "current*voltage",
    "resistance" -> "current^-1*voltage",
    "capacitance" -> "current*time*voltage^-1"
  )
  def dimension(value: String): String =
    val normalized = aliases.getOrElse(value, value)
    if normalized == "1" then normalized
    else
      val parts = normalized.split("\\*", -1).toVector
      val factors = parts.map: part =>
        val pair = part.split("\\^", -1).toVector
        pair match
          case Vector(name) if name.matches("[a-z][a-z0-9_]*") => name -> 1
          case Vector(name, power) if name.matches("[a-z][a-z0-9_]*") =>
            val exponent = power.toIntOption.filter(n => n != 0 && n >= -64 && n <= 64)
              .getOrElse(fail(3, "invalid physical dimension exponent"))
            name -> exponent
          case _ => fail(3, "invalid physical dimension signature")
      if factors.map(_._1).distinct.size != factors.size || factors != factors.sortBy(_._1) then
        fail(3, "physical dimensions must have unique, canonical ordered factors")
      if factors.exists(_._1 == "unknown") then fail(3, "unknown function physical dimension")
      factors.map((name, power) => if power == 1 then name else s"$name^$power").mkString("*")

  private def combine(a: String, b: String, divide: Boolean): String =
    def powers(value: String): Map[String, Int] =
      if value == "1" then Map.empty
      else
        value.split("\\*").toVector.map: factor =>
          val pair = factor.split("\\^", 2)
          pair(0) -> (if pair.length == 1 then 1 else pair(1).toInt)
        .toMap
    val left = powers(a)
    val right = powers(b)
    val combined = (left.keySet ++ right.keySet).toVector.sorted.map: key =>
      key -> (left.getOrElse(key, 0) + (if divide then -1 else 1) * right.getOrElse(key, 0))
    val result = combined.filter(_._2 != 0)
      .map((name, exponent) => if exponent == 1 then name else s"$name^$exponent").mkString("*")
    dimension(if result.isEmpty then "1" else result)

  private def scalar(dataType: DataType[?], units: PhysicalDimension): ValueType =
    val kind = dataType match
      case Real => "real"
      case Integer => "integer"
      case _ => fail(3, "analog function arguments and returns must be Real or Integer")
    val result = ValueType(kind, dimension(units.name))
    if kind == "integer" && result.dimension != "1" then
      fail(3, "Integer function values are dimensionless")
    result

  private def descriptor(valueType: ValueType): KernelTypeDescriptor =
    val name = valueType.kind match
      case "real" => "Real"
      case "integer" => "Integer"
      case "boolean" => "Bool"
      case _ => fail(3, "invalid function scalar kind")
    KernelTypeDescriptor(name, Vector(valueType.dimension))

  private def identifier(name: String): Unit =
    if !name.matches("[A-Za-z_][A-Za-z0-9_]*") then fail(2, "invalid analog function identifier")

  final class Registry:
    private val definitions = mutable.LinkedHashMap.empty[(Long, String), Definition]

    def definition(module: Long, name: String): Definition =
      definitions.getOrElse(module -> name, fail(5, s"unresolved analog function '$name'"))

    def validate[A <: Data](function: AnalogFunction[A], module: Long): Definition =
      if !(function.registry eq this) || function.module != module then
        fail(5, "analog function call crosses a Module or elaboration boundary")
      val result = definition(module, function.definition.name)
      if !(result eq function.definition) then fail(5, "stale analog function handle")
      result

    def define[A <: Data](
        module: Long,
        name: String,
        dataType: DataType[A],
        units: PhysicalDimension,
        source: () => Option[AnalogProceduralRuntime.Source],
        build: AnalogFunctionBody => Expr[A]
    ): AnalogFunction[A] =
      rejectEffect()
      identifier(name)
      if definitions.contains(module -> name) then
        fail(2, "analog function overloading is unsupported")
      val resultType = scalar(dataType, units)
      val declarationSource = source()
      val body = new Body(this, module, name, source)
      var result: Option[Definition] = None
      try
        ScopedValue.where(Current, body).run(new Runnable:
          override def run(): Unit =
            val returned = build(new AnalogFunctionBody(body))
            result = Some(body.finish(resultType, returned, declarationSource)))
      finally body.close()
      val definition = result.getOrElse(fail(4, "analog function has no total return"))
      definitions.update(module -> name, definition)
      new AnalogFunction(this, module, definition, dataType)

    def snapshots(owner: Long => String): Vector[Snapshot] =
      definitions.toVector.map((key, value) => Snapshot(owner(key._1), value))
        .sortBy(value => (value.owner, value.definition.name))

  // User declaration path components are sanitized to [A-Za-z0-9_]; '@' is disjoint.
  def semanticPath(owner: String, name: String): String = s"$owner.@function.$name"

  def sourceMap(snapshots: Vector[Snapshot]): Vector[SourceMapEntry] =
    snapshots.flatMap: snapshot =>
      val definition = snapshot.definition
      val base = semanticPath(snapshot.owner, definition.name)
      val entries = Vector(base -> definition.source, s"$base.return" -> definition.returnSource) ++
        definition.nodes.zipWithIndex.map((node, index) => s"$base.value_$index" -> node.source)
      entries.flatMap: (path, source) =>
        source.map(value =>
          SourceMapEntry(
            path,
            SourceSpan(value.file, value.line, value.column, value.line, value.column)
          )
        )
    .sortBy(_.semanticPath)

  def checkArguments(definition: Definition, arguments: Vector[ValueType]): Unit =
    if arguments.size != definition.inputs.size then
      fail(5, "analog function argument count mismatch")
    if arguments != definition.inputs.map(_.valueType) then
      fail(3, "analog function argument scalar type or physical dimension mismatch")

  def expression[A <: Data](
      function: AnalogFunction[A],
      arguments: Vector[Expr[?]]
  ): KernelExpr[A] =
    new KernelExpr[A](
      arguments,
      Some(descriptor(function.definition.result)),
      operation = Some(CallPrefix + function.definition.name)
    )

  def call[A <: Data](function: AnalogFunction[A], arguments: Vector[Expr[?]]): Expr[A] =
    if Current.isBound then
      val body = Current.get
      val _ = body.registry.validate(function, body.module)
      val result = expression(function, arguments)
      body.record(result)
      result
    else ConstructionKernel.callUserFunction(function, arguments)

  final class Body(
      val registry: Registry,
      val module: Long,
      val name: String,
      source: () => Option[AnalogProceduralRuntime.Source]
  ):
    private val nodes = mutable.ArrayBuffer.empty[Node]
    private val ids = new IdentityHashMap[Expr[?], java.lang.Integer]()
    private val constants = mutable.ArrayBuffer.empty[Option[Double]]
    private val names = mutable.HashSet(name)
    private var closed = false

    def close(): Unit = closed = true
    private def active(): Unit =
      if closed || !Current.isBound || !(Current.get eq this) then
        fail(1, "analog function body handle escaped its lexical definition")
    private def index(value: Expr[?]): Int =
      Option(ids.get(value)).map(_.intValue).getOrElse(
        fail(6, "function captures an external value or uses a foreign/uninitialized local")
      )
    private def reserve(value: String): Unit =
      identifier(value)
      if names.contains(value) then fail(2, "duplicate function argument/local/return name")
      names += value
    private def constant(node: Node): Option[Double] =
      val values = node.operands.map(constants(_))
      def binary(f: (Double, Double) => Double): Option[Double] =
        for left <- values(0); right <- values(1) yield f(left, right)
      if node.operation == "div" && values(1).contains(0.0) then
        fail(8, "constant zero function denominator")
      val result = node.operation match
        case "literal" if node.valueType.kind == "boolean" =>
          Some(if node.literal == "true" then 1.0 else 0.0)
        case "literal" => node.literal.toDoubleOption
        case "local" => values.head
        case "add" => binary(_ + _)
        case "sub" => binary(_ - _)
        case "mul" => binary(_ * _)
        case "div" => binary(_ / _)
        case "neg" => values.head.map(-_)
        case "lt" => binary((a, b) => if a < b then 1.0 else 0.0)
        case "le" => binary((a, b) => if a <= b then 1.0 else 0.0)
        case "gt" => binary((a, b) => if a > b then 1.0 else 0.0)
        case "ge" => binary((a, b) => if a >= b then 1.0 else 0.0)
        case "and" => binary((a, b) => if a != 0.0 && b != 0.0 then 1.0 else 0.0)
        case "or" => binary((a, b) => if a != 0.0 || b != 0.0 then 1.0 else 0.0)
        case "not" => values.head.map(a => if a == 0.0 then 1.0 else 0.0)
        case "select" => values.head.flatMap(a => values(if a != 0.0 then 1 else 2))
        case "math" => AnalogFunctionContract.constant(node.callee, values)
        // Calls remain opaque; this is validation, not interprocedural folding.
        case _ => None
      if result.exists(value => !value.isFinite) then
        fail(8, "nonfinite constant function expression")
      if node.valueType.kind == "integer" && result.exists(value =>
          value < Int.MinValue.toDouble || value > Int.MaxValue.toDouble
        )
      then
        fail(8, "constant Integer function overflow requires an explicit supported policy")
      result

    private def append[A <: Data](node: Node, values: Vector[Expr[?]]): Expr[A] =
      val result = new KernelExpr[A](
        values,
        Some(descriptor(node.valueType)),
        operation = Some(BodyPrefix + node.operation)
      )
      val evaluated = constant(node)
      ids.put(result, java.lang.Integer.valueOf(nodes.size))
      nodes += node.copy(source = source())
      constants += evaluated
      result

    def input[A <: Data](name: String, dataType: DataType[A], units: PhysicalDimension): Expr[A] =
      active()
      if nodes.exists(_.operation != "input") then
        fail(2, "declare all function inputs before body expressions and locals")
      val valueType = scalar(dataType, units)
      reserve(name)
      append(Node("input", valueType, name = name), Vector.empty)

    def local[A <: Data](name: String, value: Expr[A]): Expr[A] =
      active()
      val input = index(value)
      val valueType = nodes(input).valueType
      if valueType.kind == "boolean" then fail(3, "named function locals must be Real or Integer")
      reserve(name)
      append(Node("local", valueType, Vector(input), name), Vector(value))

    def expression[A <: Data](operation: String, values: Vector[Expr[?]]): Expr[A] =
      active()
      val result = new KernelExpr[A](values, operation = Some(BodyPrefix + operation))
      record(result)
      result

    def record(expression: KernelExpr[?]): Unit =
      active()
      if ids.containsKey(expression) then fail(2, "function expression was recorded twice")
      val op = expression.operation.getOrElse(fail(1, "untyped expression inside analog function"))
      val pure = Set(
        "analog_add",
        "analog_sub",
        "analog_mul",
        "analog_div",
        "analog_neg",
        "real_gt",
        "real_ge",
        "real_lt",
        "real_le",
        "bool_and",
        "bool_or",
        "bool_not"
      )
      val operation = if op.startsWith(BodyPrefix) then op.stripPrefix(BodyPrefix)
      else if pure(op) then op.stripPrefix("analog_").stripPrefix("real_").stripPrefix("bool_")
      else if op.startsWith(AnalogFunctionRegistry.FunctionPrefix) then "math"
      else if op.startsWith(CallPrefix) then "call"
      else if expression.literal.nonEmpty then "literal"
      else fail(1, s"'$op' is not a pure function-body expression")
      val operands = if operation == "literal" then Vector.empty
      else
        expression.operands.map:
          case value: Expr[?] => index(value)
          case _ => fail(6, "function expression contains non-value capture")
      val types = operands.map(nodes(_).valueType)
      val callee = if operation == "call" then op.stripPrefix(CallPrefix)
      else if operation == "math" then op.stripPrefix(AnalogFunctionRegistry.FunctionPrefix)
      else ""
      def count(size: Int): Unit =
        if types.size != size then fail(3, "invalid function expression arity")
      def numeric(): Unit =
        if types.exists(t => t.kind != "real" && t.kind != "integer") ||
          types.map(_.kind).distinct.size != 1
        then fail(3, "function numeric operands require one exact scalar type")
      def same(): Unit =
        if types.distinct.size != 1 then fail(3, "function operands require equal physical types")
      val valueType = operation match
        case "literal" =>
          val literal = expression.literal.get
          literal.kind match
            case "real" =>
              if !literal.value.toDoubleOption.exists(_.isFinite) then
                fail(3, "nonfinite real literal")
              val units = expression.operands.lift(1).collect { case value: String =>
                value
              }.getOrElse("")
              val unitDimension = Map(
                "" -> "1",
                "V" -> "voltage",
                "A" -> "current",
                "s" -> "time",
                "Ohm" -> "current^-1*voltage",
                "F" -> "current*time*voltage^-1"
              )
                .getOrElse(units, fail(3, "unsupported function literal unit"))
              ValueType("real", unitDimension)
            case "integer"
                if literal.dataType.kind == "Integer" && literal.value.toIntOption.nonEmpty =>
              ValueType("integer", "1")
            case "boolean" if literal.value == "true" || literal.value == "false" =>
              ValueType("boolean", "1")
            case _ => fail(3, "unsupported function literal scalar type")
        case "add" | "sub" => count(2); numeric(); same(); types.head
        case "mul" | "div" =>
          count(2); numeric()
          if operation == "div" && types.head.kind != "real" then
            fail(
              3,
              "function division requires Real operands; implicit Integer truncation is forbidden"
            )
          ValueType(
            types.head.kind,
            combine(types(0).dimension, types(1).dimension, operation == "div")
          )
        case "neg" => count(1); numeric(); types.head
        case "gt" | "ge" | "lt" | "le" => count(2); numeric(); same(); ValueType("boolean", "1")
        case "and" | "or" | "not" =>
          count(if operation == "not" then 1 else 2)
          if types.exists(_ != ValueType("boolean", "1")) then fail(3, "Boolean operand required")
          ValueType("boolean", "1")
        case "select" =>
          count(3)
          if types.head != ValueType("boolean", "1") || types(1) != types(2) then
            fail(3, "select requires a Boolean guard and identical arm types")
          types(1)
        case "math" =>
          if types.exists(_.kind != "real") then
            fail(3, "mathematical functions require Real arguments")
          val _ = AnalogFunctionContract.constant(
            callee,
            operands.map(i =>
              if nodes(i).operation == "literal" then nodes(i).literal.toDoubleOption else None
            )
          )
          ValueType("real", AnalogFunctionContract.dimension(callee, types.map(_.dimension)))
        case "call" =>
          val target = registry.definition(module, callee)
          checkArguments(target, types)
          target.result
        case _ => fail(1, "unsupported pure analog function operation")
      val node = Node(
        operation,
        valueType,
        operands,
        literal = expression.literal.map(_.value).getOrElse(""),
        callee = callee
      )
      val evaluated = constant(node)
      ids.put(expression, java.lang.Integer.valueOf(nodes.size))
      nodes += node.copy(source = source())
      constants += evaluated

    def finish[A <: Data](
        result: ValueType,
        returned: Expr[A],
        source: Option[AnalogProceduralRuntime.Source]
    ): Definition =
      active()
      val id = index(returned)
      if nodes(id).valueType != result then
        fail(4, "analog function return type or dimension mismatch")
      if !nodes.exists(_.operation == "input") then
        fail(2, "analog functions require at least one input")
      Definition(name, result, nodes.toVector, id, source, this.source())
