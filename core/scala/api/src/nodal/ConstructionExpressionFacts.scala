package nodal

private final case class AnalogDimension(
    powers: Map[String, Int],
    isZero: Boolean = false,
    isUnknown: Boolean = false
):
  private def normalized(values: Map[String, Int]): Map[String, Int] =
    values.filter(_._2 != 0)

  def multiply(other: AnalogDimension): AnalogDimension =
    if isUnknown || other.isUnknown then AnalogDimension.Unknown
    else
      val keys = powers.keySet ++ other.powers.keySet
      AnalogDimension(
        normalized(
          keys.iterator
            .map(key => key -> (powers.getOrElse(key, 0) + other.powers.getOrElse(key, 0)))
            .toMap
        ),
        isZero = isZero || other.isZero
      )

  def divide(other: AnalogDimension): AnalogDimension =
    if isUnknown || other.isUnknown then AnalogDimension.Unknown
    else
      val keys = powers.keySet ++ other.powers.keySet
      AnalogDimension(
        normalized(
          keys.iterator
            .map(key => key -> (powers.getOrElse(key, 0) - other.powers.getOrElse(key, 0)))
            .toMap
        ),
        isZero = isZero
      )

  def compatibleAdd(other: AnalogDimension): AnalogDimension =
    if isUnknown || other.isUnknown then AnalogDimension.Unknown
    else if isZero then other.copy(isZero = other.isZero && isZero)
    else if other.isZero then copy(isZero = isZero && other.isZero)
    else if powers == other.powers then copy(isZero = isZero && other.isZero)
    else AnalogDimension.Unknown

  def signature: String =
    if isUnknown then "unknown"
    else if powers.isEmpty then "1"
    else
      powers.toVector
        .sortBy(_._1)
        .map: (name, exponent) =>
          if exponent == 1 then name else s"$name^$exponent"
        .mkString("*")

  def canonical: String =
    if isUnknown then "unknown"
    else
      powers match
        case values if values.isEmpty => "dimensionless"
        case values if values == Map("voltage" -> 1) => "voltage"
        case values if values == Map("current" -> 1) => "current"
        case values if values == Map("time" -> 1) => "time"
        case values if values == Map("time" -> -1) => "frequency"
        case values if values == Map("temperature" -> 1) => "temperature"
        case values if values == Map("current" -> 1, "time" -> 1) => "charge"
        case values if values == Map("voltage" -> 1, "current" -> 1) => "power"
        case values if values == Map("voltage" -> 1, "current" -> -1) => "resistance"
        case values
            if values == Map("current" -> 1, "time" -> 1, "voltage" -> -1) =>
          "capacitance"
        case values =>
          values.toVector
            .sortBy(_._1)
            .map: (name, exponent) =>
              if exponent == 1 then name else s"$name^$exponent"
            .mkString("*")

private object AnalogDimension:
  val Unknown: AnalogDimension = AnalogDimension(Map.empty, isUnknown = true)
  val Dimensionless: AnalogDimension = AnalogDimension(Map.empty)
  val Zero: AnalogDimension = AnalogDimension(Map.empty, isZero = true)
  val Voltage: AnalogDimension = AnalogDimension(Map("voltage" -> 1))
  val Current: AnalogDimension = AnalogDimension(Map("current" -> 1))
  val Time: AnalogDimension = AnalogDimension(Map("time" -> 1))
  val Temperature: AnalogDimension = AnalogDimension(Map("temperature" -> 1))

private object ConstructionExpressionFacts:
  // This check is deliberately structural: parameter defaults are not substituted for
  // symbolic overrides, and time-dependent operators are never classified as constants.
  def waveformStatic(value: Any): Boolean = value match
    case _: Param[?] => true
    case expression: KernelExpr[?] =>
      expression.literal.nonEmpty ||
      ((Set("analog_add", "analog_sub", "analog_mul", "analog_div", "analog_neg")
        .contains(expression.operation.getOrElse("")) || expression.operation.exists(
        _.startsWith(AnalogFunctionRegistry.FunctionPrefix)
      )) &&
        expression.operands.forall(waveformStatic))
    case _ => false

  def waveformConstant(value: Any): Option[Double] = value match
    case expression: KernelExpr[?]
        if expression.operation.exists(
          _.startsWith(AnalogFunctionRegistry.FunctionPrefix)
        ) =>
      AnalogFunctionContract.constant(
        expression.operation.get.stripPrefix(AnalogFunctionRegistry.FunctionPrefix),
        expression.operands.map(waveformConstant)
      )
    case expression: KernelExpr[?] =>
      expression.literal.filter(_.kind == "real").flatMap(_.value.toDoubleOption).orElse:
        expression.operands.map(waveformConstant) match
          case Vector(Some(a), Some(b)) => expression.operation match
              case Some("analog_add") => Some(a + b)
              case Some("analog_sub") => Some(a - b)
              case Some("analog_mul") => Some(a * b)
              case Some("analog_div") => Some(a / b)
              case _ => None
          case Vector(Some(a)) if expression.operation.contains("analog_neg") => Some(-a)
          case _ => None
    case _ => None

  def waveformContinuity(value: Any): String =
    if waveformStatic(value) then "constant"
    else
      value match
        case variable: Variable[?] if AnalogProceduralConstruction.isEventHeldVariable(variable) =>
          "piecewise-constant"
        case expression: KernelExpr[?] => expression.operation match
            case Some("analog_transition") => "continuous"
            case Some("analog_slew") if expression.operands.size > 1 => "continuous"
            case Some("analog_slew") => waveformContinuity(expression.operands.head)
            // A changing transport delay can introduce jumps even for a smooth input.
            case Some("analog_absdelay") => "unknown"
            case Some("analog_abstime") => "continuous"
            case Some("analog_select") if expression.operands.drop(1).forall(waveformStatic) =>
              "piecewise-constant"
            case _ => "unknown"
        case _ => "unknown"

  private def inferBooleanExpressionDimension(
      expression: KernelExpr[?]
  ): AnalogDimension =
    expression.operation match
      case Some("real_gt") | Some("real_ge") | Some("real_lt") |
          Some("real_le") =>
        expression.operands match
          case Vector(left, right) =>
            val compatible = inferAnalogDimension(left)
              .compatibleAdd(inferAnalogDimension(right))
            if compatible.isUnknown then AnalogDimension.Unknown
            else AnalogDimension.Dimensionless
          case _ => AnalogDimension.Unknown
      case Some("bool_and") | Some("bool_or") =>
        val dimensions = expression.operands.map(inferAnalogDimension)
        if dimensions.nonEmpty && dimensions.forall(isDimensionlessBoolean) then
          AnalogDimension.Dimensionless
        else AnalogDimension.Unknown
      case Some("bool_not") =>
        expression.operands match
          case Vector(operand)
              if isDimensionlessBoolean(inferAnalogDimension(operand)) =>
            AnalogDimension.Dimensionless
          case _ => AnalogDimension.Unknown
      case _ => AnalogDimension.Dimensionless

  private def isDimensionlessBoolean(dimension: AnalogDimension): Boolean =
    !dimension.isUnknown && dimension.powers.isEmpty

  def inferAnalogDimension(value: Any): AnalogDimension = value match
    case expression: KernelExpr[?]
        if expression.literal.exists(value =>
          value.kind == "integer" && value.dataType.kind == "Integer"
        ) =>
      AnalogDimension.Dimensionless
    case expression: KernelExpr[?]
        if expression.operation.exists(_.startsWith(AnalogUserFunctionRuntime.CallPrefix)) =>
      expression.resultType.flatMap(_.arguments.headOption).collect { case units: String =>
        namedAnalogDimension(units)
      }.getOrElse(AnalogDimension.Unknown)
    case expression: KernelExpr[?]
        if expression.operation.exists(_.startsWith(AnalogTransferContract.Prefix)) =>
      expression.operands.headOption.map(inferAnalogDimension).getOrElse(AnalogDimension.Unknown)
    case expression: KernelExpr[?]
        if expression.operation.exists(_.startsWith(AnalogNoiseContract.Prefix)) =>
      val index =
        if expression.operation.contains(AnalogNoiseContract.Prefix + "table") then 1 else 0
      val density = expression.operands.lift(index).map(inferAnalogDimension)
        .getOrElse(AnalogDimension.Unknown)
      namedAnalogDimension(AnalogNoiseContract.resultDimension(density.signature))
    case expression: KernelExpr[?]
        if expression.operation.exists(
          _.startsWith(AnalogFunctionRegistry.FunctionPrefix)
        ) =>
      val id = expression.operation.get.stripPrefix(AnalogFunctionRegistry.FunctionPrefix)
      val dimensions = expression.operands.map: operand =>
        operand match
          case value: Expr[?] if !CandidateRuntime.expressionDataType(value).exists(_ != Real) =>
            inferAnalogDimension(value).signature
          case _ => "unknown"
      namedAnalogDimension(AnalogFunctionContract.dimension(id, dimensions))
    case parameter: Param[?] => inferAnalogDimension(parameter.default)
    case state: AnalogState => physicalDimension(state.dimension)

    case variable: Variable[?] =>
      AnalogProceduralConstruction
        .registeredDimension(variable)
        .map(namedAnalogDimension)
        .getOrElse(AnalogDimension.Unknown)
    case expression: KernelExpr[?]
        if expression.resultType.exists(_.kind == "Bool") =>
      inferBooleanExpressionDimension(expression)
    case expression: KernelExpr[?] =>
      expression.literal match
        case Some(literal) if literal.kind == "real" =>
          val unit =
            expression.operands.lift(1).collect { case value: String => value }.getOrElse("")
          val zero = literal.value.toDoubleOption.contains(0.0)
          unitDimension(unit, zero)
        case Some(_) => AnalogDimension.Dimensionless
        case None =>
          expression.operation match
            case Some("analog_add") | Some("analog_sub") =>
              expression.operands.map(inferAnalogDimension).reduceOption(_.compatibleAdd(_))
                .getOrElse(AnalogDimension.Unknown)
            case Some("analog_mul") =>
              expression.operands.map(inferAnalogDimension).reduceOption(_.multiply(_))
                .getOrElse(AnalogDimension.Unknown)
            case Some("analog_div") =>
              expression.operands match
                case Vector(left, right) =>
                  inferAnalogDimension(left).divide(inferAnalogDimension(right))
                case _ => AnalogDimension.Unknown
            case Some("analog_neg") =>
              expression.operands.headOption.map(
                inferAnalogDimension
              ).getOrElse(AnalogDimension.Unknown)
            case Some("analog_select") =>
              expression.operands.drop(1).map(inferAnalogDimension).reduceOption(_.compatibleAdd(_))
                .getOrElse(AnalogDimension.Unknown)
            case Some("analog_ddt") =>
              expression.operands.headOption.map(inferAnalogDimension)
                .map(_.divide(AnalogDimension.Time))
                .getOrElse(AnalogDimension.Unknown)
            case Some("analog_idt") =>
              expression.operands.headOption.map(inferAnalogDimension)
                .map(_.multiply(AnalogDimension.Time))
                .getOrElse(AnalogDimension.Unknown)
            case Some("potential_access") | Some("candidate-branch-potential") =>
              accessDimension(expression.operands, potential = true)
            case Some("flow_access") | Some("candidate-branch-flow") =>
              accessDimension(expression.operands, potential = false)
            case Some("candidate-analog-state") =>
              expression.operands.collectFirst { case state: AnalogState =>
                physicalDimension(state.dimension)
              }.getOrElse(AnalogDimension.Unknown)
            case Some("analog_abstime") => AnalogDimension.Time
            case Some("candidate-analysis-time") => AnalogDimension.Time
            case Some("candidate-analysis-frequency") =>
              AnalogDimension.Dimensionless.divide(AnalogDimension.Time)
            case Some("candidate-environment-temperature") |
                Some("candidate-environment-nominal-temperature") =>
              AnalogDimension.Temperature
            case Some("candidate-operating-condition") | Some("candidate-sweep-coordinate") =>
              expression.operands.collectFirst { case dimension: PhysicalDimension =>
                physicalDimension(dimension)
              }.getOrElse(AnalogDimension.Unknown)
            case _ =>
              expression.operands.headOption.map(inferAnalogDimension)
                .getOrElse(AnalogDimension.Unknown)
    case dimension: PhysicalDimension => physicalDimension(dimension)
    case _ => AnalogDimension.Unknown

  private def accessDimension(values: Vector[Any], potential: Boolean): AnalogDimension =
    val discipline = values.collectFirst:
      case branch: Branch[?] => branch.positive.discipline
      case node: Node[?] => node.discipline
      case terminal: Terminal[?] => terminal.discipline
      case view: TerminalView[?, ?] => view.terminal.discipline
    discipline.map(disciplineDimension(_, potential)).getOrElse:
      if potential then AnalogDimension.Voltage else AnalogDimension.Current

  def disciplineDimension(
      discipline: Discipline,
      potential: Boolean
  ): AnalogDimension = discipline match
    case Electrical => if potential then AnalogDimension.Voltage else AnalogDimension.Current
    case named: NamedDiscipline =>
      natureDimension(if potential then named.potential else named.flow)

  private def natureDimension(nature: Nature): AnalogDimension =
    nature.name.trim.toLowerCase match
      case "voltage" | "potential" => AnalogDimension.Voltage
      case "current" | "flow" => AnalogDimension.Current
      case "temperature" => AnalogDimension.Temperature
      case _ => AnalogDimension.Unknown

  private def namedAnalogDimension(value: String): AnalogDimension =
    value.trim match
      case "dimensionless" | "1" => AnalogDimension.Dimensionless
      case "voltage" => AnalogDimension.Voltage
      case "current" => AnalogDimension.Current
      case "time" => AnalogDimension.Time
      case "frequency" => AnalogDimension.Dimensionless.divide(AnalogDimension.Time)
      case "temperature" => AnalogDimension.Temperature
      case "charge" => AnalogDimension.Current.multiply(AnalogDimension.Time)
      case "power" => AnalogDimension.Voltage.multiply(AnalogDimension.Current)
      case "resistance" => AnalogDimension.Voltage.divide(AnalogDimension.Current)
      case "capacitance" =>
        AnalogDimension.Current
          .multiply(AnalogDimension.Time)
          .divide(AnalogDimension.Voltage)
      case "" | "unknown" => AnalogDimension.Unknown
      case signature =>
        val factors = signature.split("\\*").toVector
        val parsed = factors.map: factor =>
          factor.split("\\^", 2).toVector match
            case Vector(name) if name.nonEmpty => Some(name -> 1)
            case Vector(name, exponent) if name.nonEmpty =>
              exponent.toIntOption.filter(_ != 0).map(value => name -> value)
            case _ => None
        if factors.nonEmpty && parsed.forall(_.nonEmpty) then
          val powers = parsed.flatten
            .groupMapReduce(_._1)(_._2)(_ + _)
            .filter(_._2 != 0)
          AnalogDimension(powers)
        else AnalogDimension.Unknown
  private def physicalDimension(value: PhysicalDimension): AnalogDimension =
    value.name.trim.toLowerCase match
      case "dimensionless" => AnalogDimension.Dimensionless
      case "voltage" => AnalogDimension.Voltage
      case "current" => AnalogDimension.Current
      case "charge" => AnalogDimension.Current.multiply(AnalogDimension.Time)
      case "temperature" => AnalogDimension.Temperature
      case "time" => AnalogDimension.Time
      case "frequency" => AnalogDimension.Dimensionless.divide(AnalogDimension.Time)
      case "power" => AnalogDimension.Voltage.multiply(AnalogDimension.Current)
      case _ => AnalogDimension.Unknown

  private def unitDimension(unit: String, zero: Boolean): AnalogDimension =
    val dimension = unit match
      case "" => AnalogDimension.Dimensionless
      case "V" => AnalogDimension.Voltage
      case "A" => AnalogDimension.Current
      case "Ohm" => AnalogDimension.Voltage.divide(AnalogDimension.Current)
      case "F" =>
        AnalogDimension.Current
          .multiply(AnalogDimension.Time)
          .divide(AnalogDimension.Voltage)
      case "s" => AnalogDimension.Time
      case _ => AnalogDimension.Unknown
    if zero then dimension.copy(isZero = true) else dimension
