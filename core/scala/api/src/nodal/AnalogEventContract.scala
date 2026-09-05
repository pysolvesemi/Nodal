package nodal

/** Versioned analog-event argument contract. Dimensions and constants are derived by construction,
  * not supplied by callers of the public API. Native verification independently repeats these
  * rules.
  */
private[nodal] object AnalogEventContract:
  val Version = "increment37"

  final case class Argument(
      slot: Int,
      kind: String,
      dimension: String,
      constant: Option[Double]
  )

  private def fail(number: Int, message: String): Nothing =
    scala.util.Failure[Nothing](
      new ConstructionException(KernelDiagnostic(f"NODAL-ANALOG-037-$number%03d", message))
    ).get

  def validate(
      operation: String,
      arguments: Vector[Argument],
      analyses: Vector[String] = Vector.empty
  ): Unit =
    val maximum = operation match
      case "analog_cross" => 5
      case "analog_above" | "analog_timer" => 4
      case "analog_initial_step" | "analog_final_step" => 0
      case other => fail(2, s"unknown analog event '$other'")
    if analyses.distinct != analyses then fail(8, "duplicate analysis filters are not canonical")
    val slots = arguments.map(_.slot)
    if slots != arguments.indices.toVector || slots.exists(slot => slot < 0 || slot >= maximum) ||
      (maximum > 0 && !slots.headOption.contains(0))
    then fail(2, "analog event arguments have invalid arity, order, or omitted required value")
    if maximum == 0 then
      if arguments.nonEmpty then fail(2, "analysis lifecycle events do not take numeric arguments")
      if analyses.exists(name => !name.matches("[A-Za-z][A-Za-z0-9_]*")) then
        fail(8, "analysis names must be nonempty identifiers, not target source text")
    else if analyses.nonEmpty then
      fail(8, "only initialStep and finalStep accept an analysis filter")

    val timeSlots = operation match
      case "analog_cross" => Set(2)
      case "analog_above" => Set(1)
      case "analog_timer" => Set(0, 1, 2)
      case _ => Set.empty[Int]
    val toleranceSlots = operation match
      case "analog_cross" => Set(2, 3)
      case "analog_above" => Set(1, 2)
      case "analog_timer" => Set(2)
      case _ => Set.empty[Int]
    val integerSlots = operation match
      case "analog_cross" => Set(1, 4)
      case "analog_above" | "analog_timer" => Set(3)
      case _ => Set.empty[Int]
    val expressionTolerance = operation match
      case "analog_cross" => Some(3 -> 2)
      case "analog_above" => Some(2 -> 1)
      case _ => None

    if operation == "analog_cross" && slots.exists(Set(2, 3)) && !slots.contains(1) then
      fail(2, "cross tolerances require an explicit direction")
    expressionTolerance.foreach: (expressionSlot, timeSlot) =>
      if slots.contains(expressionSlot) && !slots.contains(timeSlot) then
        fail(2, "expression tolerance requires an explicit time tolerance")

    arguments.foreach: argument =>
      if Set("unknown", "").contains(argument.dimension) then
        fail(3, "event arguments require a known physical dimension")
      val expectedKind = if integerSlots(argument.slot) then "integer" else "real"
      if argument.kind != expectedKind then
        fail(3, s"$operation argument ${argument.slot} requires $expectedKind")
      if argument.constant.exists(value => !java.lang.Double.isFinite(value)) then
        fail(4, s"$operation argument ${argument.slot} must be finite")
      if toleranceSlots(argument.slot) && argument.constant.exists(_ < 0.0) then
        fail(4, s"$operation tolerance must be nonnegative; zero requests simulator defaults")
      if integerSlots(argument.slot) && argument.dimension != "1" then
        fail(3, s"$operation integer argument must be dimensionless")
      if timeSlots(argument.slot) && argument.dimension != "time" &&
        !(argument.dimension == "1" && argument.constant.contains(0.0))
      then fail(3, s"$operation timing argument requires seconds")
      if expressionTolerance.exists(_._1 == argument.slot) &&
        arguments.headOption.exists(_.dimension != argument.dimension)
      then fail(3, s"$operation expression tolerance must match the monitored dimension")

    // Timer periods deliberately have no positivity constraint: <= 0 means one-shot.
    // Dynamic tolerances and times deliberately remain unknown under parameter overrides.
