package nodal.internal.bridge

import nodal.*

import java.util.IdentityHashMap
import scala.collection.mutable

/** F134 source checkpoint. Traversal follows retained expression identity, never authored text. The
  * plan is local to one immutable construction snapshot and shared by inventory and emission.
  */
private[nodal] object AnalogEquationMlir:
  import AnalogEquationRuntime.*

  private val arithmetic = Set("analog_add", "analog_sub", "analog_mul", "analog_div", "analog_neg")
  private val units = Vector(
    ("V", "voltage"),
    ("A", "current"),
    ("Ohm", "current^-1*voltage"),
    ("F", "current*time*voltage^-1"),
    ("s", "time")
  )

  private def fail(message: String, path: String): Nothing =
    scala.util.Failure[Nothing](
      new BridgeException(BridgeDiagnostic("NODAL-ANALOG-134-001", message, Some(path)))
    ).get

  private def requireSource(condition: Boolean, message: String, path: String): Unit =
    if !condition then fail(message, path)

  private def signature(value: String): String = AnalogUserFunctionRuntime.dimension(value)

  private final case class Node(
      operation: String,
      dimension: AnalogDimension,
      operands: Vector[Int] = Vector.empty,
      literal: Option[KernelLiteral] = None,
      unit: String = "",
      state: Option[StateRecord] = None,
      parameter: Option[ParameterReference] = None,
      source: Option[SourceSpan] = None
  )
  private final case class TypedEquation(record: EquationRecord, left: Int, right: Int)
  private final case class ComponentPlan(
      record: ComponentRecord,
      states: Vector[StateRecord],
      equations: Vector[EquationRecord],
      nodes: Vector[Node],
      typedEquations: Vector[TypedEquation],
      unsupported: Vector[String]
  ):
    def typedStateIds: Vector[String] = nodes.flatMap(_.state.map(_.identity)).sorted
    def typedEquationIds: Vector[String] = typedEquations.map(_.record.identity.value).sorted
    def complete: Boolean = unsupported.isEmpty

  final class Plan(snapshot: ConstructionSnapshot):
    private val semantics = snapshot.analogSemantics
    val active: Boolean = semantics.components.nonEmpty || semantics.states.nonEmpty
    private val modules = snapshot.modules.map(module => module.path -> module).toMap
    private val states = new IdentityHashMap[AnalogState, StateRecord]()
    private val parameters = new IdentityHashMap[Param[?], ParameterReference]()
    private val components = semantics.components.map(value => value.identity -> value).toMap

    private def unique(values: Vector[String], role: String): Unit =
      requireSource(values.distinct.size == values.size, s"duplicate $role identity", snapshot.root)

    if active then
      unique(semantics.components.map(_.identity), "component")
      unique(semantics.states.map(_.identity), "state")
      unique(semantics.equations.map(_.identity.value), "equation")
      semantics.components.foreach: component =>
        requireSource(
          modules.contains(component.owner) && component.contract.name.trim.nonEmpty &&
            component.identity == s"${component.owner}.component.${component.contract.name.trim}",
          "physical component owner or identity is malformed",
          component.identity
        )
      semantics.states.foreach: state =>
        requireSource(
          !states.containsKey(state.state),
          "state object has duplicate declarations",
          state.identity
        )
        requireSource(
          modules.contains(state.owner) && state.component.forall(id =>
            components.get(id).exists(_.owner == state.owner)
          ),
          "state owner or component is malformed",
          state.identity
        )
        requireSource(
          state.identity ==
            s"${state.component.getOrElse(state.owner)}.state.${state.state.name.trim}" &&
            state.state.name.trim.nonEmpty,
          "state declaration identity is malformed",
          state.identity
        )
        requireSource(
          state.dimension ==
            ConstructionExpressionFacts.inferAnalogDimension(state.state).signature,
          "state declaration dimension differs from its actual object",
          state.identity
        )
        val (initialization, initializer) = state.state.initialization match
          case StateInitialization.Fixed(value) => "fixed" -> Some(value)
          case StateInitialization.Guess(value) => "guess" -> Some(value)
          case StateInitialization.InitialEquation => "initial-equation" -> None
          case StateInitialization.SteadyState => "steady-state" -> None
          case StateInitialization.OperatingPoint => "operating-point" -> None
          case StateInitialization.SolverSelected => "solver-selected" -> None
        requireSource(
          state.initialization == initialization &&
            state.initializer.flatMap(_.root) == initializer,
          "state initialization metadata differs from its actual declaration",
          state.identity
        )
        states.put(state.state, state)
      semantics.parameters.foreach: parameter =>
        requireSource(
          !parameters.containsKey(parameter.parameter) &&
            modules.get(parameter.owner).exists(_.declarations.exists(declaration =>
              declaration.path == parameter.path && declaration.kind == "parameter"
            )),
          "parameter reference has no exact declaration owner",
          parameter.path
        )
        parameters.put(parameter.parameter, parameter)
      semantics.equations.foreach: equation =>
        requireSource(
          modules.contains(equation.metadata.owner) && equation.metadata.component.forall(id =>
            components.get(id).exists(_.owner == equation.metadata.owner)
          ),
          "equation owner or component is malformed",
          equation.identity.value
        )
        equation.metadata.component.foreach: id =>
          requireSource(
            equation.identity.value.startsWith(id + "."),
            "equation identity is not component-qualified",
            equation.identity.value
          )

    private val statesByComponent = semantics.states.groupBy(_.component)
    private val equationsByComponent = semantics.equations.groupBy(_.metadata.component)
    private val programs =
      if !active then Vector.empty else semantics.components.sortBy(_.identity).map(build)
    private val uncoveredStates =
      semantics.states.filter(_.component.isEmpty).map(_.identity).sorted
    private val uncoveredEquations =
      semantics.equations.filter(_.metadata.component.isEmpty).map(_.identity.value).sorted
    private val unsupported = if !active then Vector.empty
    else
      (semantics.unsupported ++ programs.flatMap(_.unsupported) ++
        Option.when(semantics.components.isEmpty)("no explicit physical components") ++
        Option.when(snapshot.modules.size != 1 || !modules.contains(snapshot.root) ||
          snapshot.modules.exists(_.instances.nonEmpty))(
          "hierarchy is outside the scalar root profile"
        ) ++
        Option.when(snapshot.generatedRegions.nonEmpty)("generated source behavior") ++
        Option.when(
          snapshot.topology.nonEmpty || snapshot.conservativeArrayBindings.nonEmpty
        )("conservative topology") ++
        Option.when(
          snapshot.modules.exists(_.declarations.exists(_.kind != "parameter"))
        )("non-parameter module declarations") ++
        Option.when(
          snapshot.analogRegions.nonEmpty || snapshot.analogProcedural.nonEmpty
        )("other analog source regions") ++
        Option.when(semantics.contributions.nonEmpty)("additive contributions") ++
        Option.when(snapshot.continuousOperators.nonEmpty || snapshot.waveformOperators.nonEmpty ||
          snapshot.transferOperators.nonEmpty || snapshot.noiseOperators.nonEmpty ||
          snapshot.analogFunctions.nonEmpty)("other continuous source operators") ++
        Option.when(uncoveredStates.nonEmpty)("analog states outside physical components") ++
        Option.when(uncoveredEquations.nonEmpty)(
          "equations outside physical components"
        )).distinct.sorted

    private def build(component: ComponentRecord): ComponentPlan =
      val ownedStates =
        statesByComponent.getOrElse(Some(component.identity), Vector.empty).sortBy(_.identity)
      val equations = equationsByComponent.getOrElse(
        Some(component.identity),
        Vector.empty
      ).sortBy(_.identity.value)
      val nodes = mutable.ArrayBuffer.empty[Node]
      val memo = new IdentityHashMap[AnyRef, Option[Int]]()
      val visiting = new IdentityHashMap[AnyRef, java.lang.Boolean]()
      val reasons = mutable.LinkedHashSet.empty[String]
      var currentSource = component.source
      val contract = component.contract
      if contract.completeness != ComponentCompleteness.Concrete ||
        contract.topology != TopologyOwnership.Complete ||
        contract.localBalance != LocalBalancePolicy.Required ||
        contract.replaceable
      then reasons += s"unsupported component contract: ${component.identity}"

      def unsupported(reason: String): Option[Int] =
        reasons += s"${component.identity}: $reason"
        None

      def append(node: Node): Option[Int] =
        nodes += node.copy(source = Some(currentSource))
        Some(nodes.size - 1)

      def visit(value: AnyRef, depth: Int = 0): Option[Int] =
        requireSource(
          depth < 512 && !visiting.containsKey(value),
          "cyclic or excessively deep equation expression",
          component.identity
        )
        if memo.containsKey(value) then memo.get(value)
        else
          visiting.put(value, java.lang.Boolean.TRUE)
          val result = value match
            case state: AnalogState =>
              val record = Option(states.get(state)).getOrElse(fail(
                "state read has no declaration",
                component.identity
              ))
              requireSource(
                record.owner == component.owner,
                "state read belongs to another Module",
                record.identity
              )
              if !record.component.contains(component.identity) then
                unsupported("cross-component state read")
              else
                val initializer = record.initializer.map(expression =>
                  expression.root match
                    case Some(root) => visit(root, depth + 1)
                    case None => unsupported("state initializer has no typed root")
                )
                if initializer.exists(_.isEmpty) then None
                else
                  val dimension = ConstructionExpressionFacts.inferAnalogDimension(state)
                  initializer.flatten.foreach: index =>
                    requireSource(
                      nodes(index).dimension.signature == dimension.signature,
                      "state initializer dimension differs from its declaration",
                      record.identity
                    )
                  append(Node(
                    "state",
                    dimension,
                    initializer.flatten.toVector,
                    state = Some(record)
                  ))
            case parameter: Param[?] =>
              val record = Option(parameters.get(parameter)).getOrElse(fail(
                "parameter reference has no declaration",
                component.identity
              ))
              requireSource(
                record.owner == component.owner,
                "parameter belongs to another Module",
                record.path
              )
              requireSource(
                CandidateRuntime.expressionDataType(parameter).contains(Real),
                "source equation parameter is not scalar Real",
                record.path
              )
              val dimension = ConstructionExpressionFacts.inferAnalogDimension(parameter)
              requireSource(
                !dimension.isUnknown && dimension.signature == record.dimension,
                "parameter dimension differs from its declaration",
                record.path
              )
              append(Node("parameter", dimension, parameter = Some(record)))
            case expression: KernelExpr[?] =>
              requireSource(
                !CandidateRuntime.expressionDataType(expression).exists(_ != Real),
                "source equation expression is not scalar Real",
                component.identity
              )
              expression.literal match
                case Some(literal) =>
                  requireSource(
                    literal.kind == "real" && literal.value.toDoubleOption.exists(_.isFinite),
                    "source equation literal is not finite Real",
                    component.identity
                  )
                  val dimension = ConstructionExpressionFacts.inferAnalogDimension(expression)
                  requireSource(
                    !dimension.isUnknown,
                    "source equation literal has unknown units",
                    component.identity
                  )
                  append(Node(
                    "literal",
                    dimension,
                    literal = Some(literal),
                    unit = CandidateRuntime.expressionUnit(expression).getOrElse("")
                  ))
                case None if expression.operation.contains("candidate-analog-state") =>
                  expression.operands match
                    case Vector(state: AnalogState) => visit(state, depth + 1)
                    case _ =>
                      fail("state read has malformed declaration identity", component.identity)
                case None if expression.operation.exists(arithmetic.contains) =>
                  val operation = expression.operation.get
                  requireSource(
                    expression.operands.size == (if operation == "analog_neg" then 1 else 2),
                    "source arithmetic has malformed arity",
                    component.identity
                  )
                  val operands = expression.operands.map:
                    case operand: Expr[?] => visit(operand, depth + 1)
                    case _ =>
                      fail("source arithmetic operand is not an expression", component.identity)
                  if operands.exists(_.isEmpty) then None
                  else
                    val indices = operands.flatten
                    val left = nodes(indices.head).dimension
                    val dimension = operation match
                      case "analog_add" | "analog_sub" =>
                        left.compatibleAdd(nodes(indices(1)).dimension)
                      case "analog_mul" => left.multiply(nodes(indices(1)).dimension)
                      case "analog_div" => left.divide(nodes(indices(1)).dimension)
                      case _ => left
                    requireSource(
                      !dimension.isUnknown,
                      "source arithmetic has incompatible dimensions",
                      component.identity
                    )
                    append(Node(operation, dimension, indices))
                case None => unsupported(
                    s"unsupported expression operator ${expression.operation.getOrElse("unknown")}"
                  )
            case _: Expr[?] => unsupported("unsupported scalar expression source")
            case _ => fail("source expression contains a non-expression value", component.identity)
          visiting.remove(value)
          memo.put(value, result)
          result

      ownedStates.foreach: state =>
        currentSource = state.source
        visit(state.state)
      val typedEquations = equations.flatMap: equation =>
        currentSource = equation.metadata.source
        if equation.metadata.guard.nonEmpty then
          reasons += s"${component.identity}: guarded equation ${equation.identity.value}"
        val roots = Vector(equation.residual.authoredLeft, equation.residual.authoredRight).map:
          expression =>
            expression.root match
              case Some(root) =>
                val result = visit(root)
                result.foreach: index =>
                  requireSource(
                    nodes(index).dimension.signature == signature(expression.dimension),
                    "typed equation root differs from authored dimension",
                    equation.identity.value
                  )
                result
              case None => unsupported("equation has no typed root")
        if equation.metadata.guard.nonEmpty || roots.exists(_.isEmpty) then None
        else
          requireSource(
            nodes(roots(0).get).dimension.signature == nodes(roots(1).get).dimension.signature,
            "typed equation operands have incompatible dimensions",
            equation.identity.value
          )
          Some(TypedEquation(equation, roots(0).get, roots(1).get))
      ComponentPlan(
        component,
        ownedStates,
        equations,
        nodes.toVector,
        typedEquations,
        reasons.toVector.sorted
      )

    def inventory(emittedOwners: Set[String] = modules.keySet): String =
      val emitted = programs.filter(program => emittedOwners.contains(program.record.owner))
      val omitted = programs.filterNot(program => emittedOwners.contains(program.record.owner))
      val uncoveredComponents = omitted.map(_.record.identity)
      val missingStates = (uncoveredStates ++ omitted.flatMap(_.states.map(_.identity))).sorted
      val missingEquations =
        (uncoveredEquations ++ omitted.flatMap(_.equations.map(_.identity.value))).sorted
      val coverageReasons =
        (unsupported ++ Option.when(omitted.nonEmpty)(
          "source component occurrences share canonical module definitions"
        )).sorted
      dictionary(Vector(
        "version" -> quote("1"),
        "root" -> quote(snapshot.root),
        "component_ids" -> strings(programs.map(_.record.identity)),
        "state_ids" -> strings(semantics.states.map(_.identity).sorted),
        "equation_ids" -> strings(semantics.equations.map(_.identity.value).sorted),
        "typed_state_ids" -> strings(emitted.flatMap(_.typedStateIds).sorted),
        "typed_equation_ids" -> strings(emitted.flatMap(_.typedEquationIds).sorted),
        "uncovered_component_ids" -> strings(uncoveredComponents),
        "uncovered_state_ids" -> strings(missingStates),
        "uncovered_equation_ids" -> strings(missingEquations),
        "profile_complete" -> coverageReasons.isEmpty.toString,
        "unsupported" -> strings(coverageReasons),
        "states" -> array(semantics.states.sortBy(_.identity).map(state =>
          dictionary(Vector(
            "state_id" -> quote(state.identity),
            "owner" -> quote(state.owner),
            "component" -> quote(state.component.getOrElse("")),
            "dimension" -> quote(state.dimension),
            "initialization" -> quote(state.initialization),
            "has_initializer" -> state.initializer.nonEmpty.toString
          ))
        ))
      ))

    private def componentInventory(program: ComponentPlan): String =
      val component = program.record
      dictionary(Vector(
        "component_id" -> quote(component.identity),
        "owner" -> quote(component.owner),
        "name" -> quote(component.contract.name),
        "completeness" -> quote(component.contract.completeness.toString.toLowerCase),
        "topology" -> quote(component.contract.topology.toString.toLowerCase),
        "local_balance" -> quote(component.contract.localBalance match
          case LocalBalancePolicy.Required => "required"
          case LocalBalancePolicy.Explicit => "explicit"
          case LocalBalancePolicy.DeferredToConcrete => "deferred-to-concrete"),
        "replaceable" -> component.contract.replaceable.toString,
        "profile_complete" -> program.complete.toString,
        "unsupported" -> strings(program.unsupported),
        "state_ids" -> strings(program.states.map(_.identity)),
        "equation_ids" -> strings(program.equations.map(_.identity.value)),
        "typed_state_ids" -> strings(program.typedStateIds),
        "typed_equation_ids" -> strings(program.typedEquationIds),
        "metadata" -> metadata(component.identity, component.source)
      ))

    def renderModule(owner: String, parameterSymbols: Map[String, String]): Vector[String] =
      programs.filter(_.record.owner == owner).map(render(_, parameterSymbols))

    def canonical: String =
      val rendered = snapshot.modules.sortBy(_.path).flatMap: module =>
        renderModule(
          module.path,
          module.declarations.filter(_.kind == "parameter")
            .map(declaration => declaration.path -> declaration.path).toMap
        )
      inventory() + "\n" + rendered.mkString("\n")

    private def render(program: ComponentPlan, parameterSymbols: Map[String, String]): String =
      val component = program.record
      def value(index: Int): String = s"%source_value_$index"
      def valueType(index: Int): String = quantity(program.nodes(index).dimension.signature)
      val lines = program.nodes.zipWithIndex.map: (node, index) =>
        val path = node.state.map(_.identity).getOrElse(s"${component.identity}.value_$index")
        val source = node.state.map(_.source).orElse(node.source).getOrElse(component.source)
        val common = Vector("metadata" -> metadata(
          path,
          source,
          Option.when(node.unit.nonEmpty)("unit" -> quote(node.unit)).toVector
        ))
        val (name, attributes) = node.operation match
          case "literal" =>
            val magnitude = s"${node.literal.get.value} : f64"
            "nodal.real_literal" ->
              (Vector("value" -> magnitude) ++
                Option.when(node.unit.nonEmpty)(Vector(
                  "unit" -> ("@" + quote(unitSymbol(node.unit))),
                  "authored_value" -> magnitude
                )).toVector.flatten)
          case "parameter" =>
            val parameter = node.parameter.get
            "nodal.parameter_ref" -> Vector("parameter" ->
              ("@" + quote(parameterSymbols.getOrElse(
                parameter.path,
                fail("parameter symbol is unavailable", parameter.path)
              ))))
          case "state" =>
            val state = node.state.get
            "nodal.analog_unknown" -> Vector(
              "state_id" -> quote(state.identity),
              "owner" -> quote(state.owner),
              "component" -> quote(component.identity),
              "initialization" -> quote(state.initialization)
            )
          case operation => s"nodal.$operation" -> Vector.empty
        operation(
          name,
          Some(value(index)),
          node.operands.map(value),
          node.operands.map(valueType),
          Some(valueType(index)),
          attributes ++ common,
          source
        )
      val equationLines = program.typedEquations.map: typed =>
        val equation = typed.record
        operation(
          "nodal.analog_equation",
          None,
          Vector(value(typed.left), value(typed.right)),
          Vector(valueType(typed.left), valueType(typed.right)),
          None,
          Vector(
            "equation_id" -> quote(equation.identity.value),
            "owner" -> quote(equation.metadata.owner),
            "component" -> quote(component.identity),
            "initial_only" -> equation.initialOnly.toString,
            "analyses" -> strings(equation.metadata.analyses.toVector.sorted),
            "continuity" -> quote(equation.metadata.continuity),
            "metadata" -> metadata(
              equation.identity.value,
              equation.metadata.source,
              Vector(
                "authored_left" -> quote(equation.residual.authoredLeft.rendered),
                "authored_right" -> quote(equation.residual.authoredRight.rendered),
                "dimension" -> quote(equation.residual.authoredLeft.dimension)
              )
            )
          ),
          equation.metadata.source
        )
      val body = lines ++ equationLines
      val renderedBody =
        if body.isEmpty then "  ^bb0:"
        else body.map("  " + _).mkString("\n")
      s"\"nodal.analog\"() <{metadata = ${metadata(component.identity, component.source)}, " +
        s"source_equations = ${componentInventory(program)}}> ({\n" +
        renderedBody +
        "\n}) : () -> ()" + location(component.source)

  // Module symbols strip leading underscores, keeping this private top-level namespace disjoint.
  def unitSymbol(unit: String): String = s"_nodal_source_unit_$unit"
  def parameterUnit(plan: Plan, declaration: KernelDeclarationSnapshot): Option[String] =
    if !plan.active then None
    else
      declaration.attributes.toMap.get("unit").filter(_.nonEmpty).map: unit =>
        requireSource(
          units.exists(_._1 == unit),
          "unsupported source parameter unit",
          declaration.path
        )
        unitSymbol(unit)

  def unitDeclarations(plan: Plan): Vector[String] = if !plan.active then Vector.empty
  else
    units.map: (unit, dimension) =>
      s"\"nodal.unit\"() <{sym_name = ${quote(unitSymbol(unit))}, symbol = ${quote(unit)}, " +
        s"dimension = ${quote(dimension)}, scale = 1.0 : f64, native_suffix = \"\", " +
        s"metadata = {semantic_path = ${quote(s"std.${unitSymbol(unit)}")}}}> : () -> () loc(unknown)"

  private def operation(
      name: String,
      result: Option[String],
      operands: Vector[String],
      operandTypes: Vector[String],
      resultType: Option[String],
      attributes: Vector[(String, String)],
      source: SourceSpan
  ): String =
    result.map(_ + " = ").getOrElse("") + quote(name) + operands.mkString("(", ", ", ")") +
      " <" + dictionary(attributes) + "> : " + operandTypes.mkString("(", ", ", ")") +
      " -> " + resultType.getOrElse("()") + location(source)

  private def quantity(dimension: String): String =
    s"!nodal.quantity<\"real\", ${quote(dimension)}>"
  private def metadata(
      path: String,
      source: SourceSpan,
      extra: Vector[(String, String)] = Vector.empty
  ): String =
    dictionary(Vector(
      "semantic_path" -> quote(path),
      "source_file" -> quote(source.file),
      "source_line" -> s"${source.line} : i64",
      "source_column" -> s"${source.column} : i64"
    ) ++ extra)
  private def location(source: SourceSpan): String =
    s" loc(${quote(source.file)}:${source.line}:${source.column})"
  private def array(values: Vector[String]): String = values.mkString("[", ", ", "]")
  private def strings(values: Vector[String]): String = array(values.map(quote))
  private def dictionary(values: Vector[(String, String)]): String =
    values.sortBy(_._1).map((key, value) => s"$key = $value").mkString("{", ", ", "}")
  private def quote(value: String): String =
    val escaped = value.flatMap:
      case '\\' => "\\\\"
      case '"' => "\\\""
      case character if character.isControl => f"\\${character.toInt & 0xff}%02X"
      case character => character.toString
    s"\"$escaped\""
