package nodal

/** Immutable analog event expressions. A definition is evaluated by its enclosing controlled
  * statement; reusing a source handle creates a separate monitored occurrence at each control.
  */
private[nodal] object AnalogEventRuntime:
  final case class Argument(
      slot: Int,
      value: AnalogProceduralRuntime.Value,
      constant: Option[Double]
  )

  final case class Expression(
      operation: String,
      arguments: Vector[Argument] = Vector.empty,
      analyses: Vector[String] = Vector.empty,
      alternatives: Vector[Expression] = Vector.empty,
      source: Option[AnalogProceduralRuntime.Source] = None,
      name: String = ""
  ):
    def reads: Set[String] =
      arguments.flatMap(_.value.reads.map(_.identity)).toSet ++ alternatives.flatMap(_.reads)

    def hasMonitor: scala.Boolean =
      Set("analog_cross", "analog_above", "analog_timer").contains(operation) ||
        alternatives.exists(_.hasMonitor)

    def remap(
        path: String => String,
        rendered: String => String
    ): Expression =
      copy(
        arguments = arguments.map: argument =>
          argument.copy(value =
            argument.value.copy(
              rendered = rendered(argument.value.rendered),
              reads = argument.value.reads.map: variable =>
                variable.copy(
                  identity = path(variable.identity),
                  owner = path(variable.owner),
                  declarationScope = variable.declarationScope.map(path)
                )
            )
          ),
        alternatives = alternatives.map(_.remap(path, rendered))
      )

    def validate(): Unit =
      if name.nonEmpty && !name.matches("[A-Za-z_][A-Za-z0-9_]*") then
        fail(2, "event name must be a semantic identifier")
      if operation == "analog_event_or" then
        if arguments.nonEmpty || analyses.nonEmpty || alternatives.size < 2 then
          fail(6, "event OR requires at least two analog events and no numeric arguments")
        alternatives.foreach(_.validate())
      else
        if alternatives.nonEmpty then fail(6, "a primitive event cannot own event alternatives")
        AnalogEventContract.validate(
          operation,
          arguments.map: argument =>
            val dimension = argument.value.valueType.dimension match
              case "dimensionless" | "zero" => "1"
              case value => value
            AnalogEventContract.Argument(
              argument.slot,
              argument.value.valueType.kind.label,
              dimension,
              argument.constant
            )
          ,
          analyses
        )

  private[nodal] def fail(number: Int, message: String): Nothing =
    scala.util.Failure[Nothing](
      new ConstructionException(KernelDiagnostic(f"NODAL-ANALOG-037-$number%03d", message))
    ).get

private[nodal] final case class KernelAnalogEventDefinition(
    owner: Module,
    operation: String,
    arguments: Vector[(Int, Expr[? <: Data])],
    analyses: Vector[String],
    alternatives: Vector[Event],
    source: Option[AnalogProceduralRuntime.Source],
    name: String = ""
)
