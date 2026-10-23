package nodal

/** Pure validation policy for Increment 42 instance-parameter overrides.
  *
  * The construction transaction remains responsible for deriving these facts from exact
  * declaration, expression, module, and instance identities. This helper owns only the fail-closed
  * policy so the eventual integration does not duplicate the accepted staticness/type/unit rules.
  */
private[nodal] object AnalogHierarchyOverridePolicy:
  final case class Evidence(
      parentOwner: Long,
      duplicate: Boolean,
      referencedDeclarationOwners: Vector[Long],
      expressionOwners: Vector[Long],
      dynamicDependency: Boolean,
      operations: Vector[String],
      targetTypeSignature: Option[String],
      valueTypeSignature: Option[String],
      requiresDimension: Boolean,
      targetDimensionSignature: Option[String],
      valueDimensionSignature: Option[String]
  )

  final case class Rejection(code: String, message: String)

  private val acceptedStaticOperations = Set(
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

  def validate(evidence: Evidence): Either[Rejection, Unit] =
    validate(evidence, generatedInteger = false)

  /** Only the construction owner supplies a bound obtained from the actual generated Integer DAG.
    * This is not generic type-binding authority; occurrence queries still check every formal over
    * the complete original template envelope before any selected specialization.
    */
  def validateGeneratedInteger(
      evidence: Evidence,
      provedBounds: IterationDomain.Bounds
  ): Either[Rejection, Unit] =
    validateGeneratedIntegerFacts(evidence, Some(provedBounds))

  /** Absence is supplied only after the construction owner's original DAG and exact generated
    * occurrence have been checked. It does not denote a value or a formal default.
    */
  def validateGeneratedIntegerFacts(
      evidence: Evidence,
      provedBounds: Option[IterationDomain.Bounds]
  ): Either[Rejection, Unit] =
    if !evidence.targetTypeSignature.contains("Integer") ||
      !evidence.valueTypeSignature.contains("Integer") ||
      provedBounds.exists(bounds =>
        bounds.lower < Long.MinValue || bounds.upper > Long.MaxValue ||
          bounds.lower > bounds.upper
      )
    then
      Left(Rejection(
        "NODAL-HIERARCHY-037",
        "generated override requires a proven signed Integer DAG"
      ))
    else validate(evidence, generatedInteger = true)

  private def validate(
      evidence: Evidence,
      generatedInteger: Boolean
  ): Either[Rejection, Unit] =
    if evidence.duplicate then
      Left(
        Rejection(
          "NODAL-HIERARCHY-030",
          "one child parameter may be overridden only once per Instance"
        )
      )
    else if evidence.referencedDeclarationOwners.exists(_ != evidence.parentOwner) ||
      evidence.expressionOwners.exists(_ != evidence.parentOwner)
    then
      Left(
        Rejection(
          "NODAL-HIERARCHY-031",
          "symbolic override may reference only values owned by the parent Module"
        )
      )
    else if evidence.dynamicDependency then
      Left(
        Rejection(
          "NODAL-HIERARCHY-032",
          "symbolic override may not depend on signals, variables, state, or child declarations"
        )
      )
    else
      evidence.operations.find(operation =>
        !generatedInteger && !acceptedStaticOperations.contains(operation)
      ) match
        case Some(operation) =>
          Left(
            Rejection(
              "NODAL-HIERARCHY-037",
              s"symbolic override operation '$operation' is not analysis-static"
            )
          )
        case None =>
          (evidence.targetTypeSignature, evidence.valueTypeSignature) match
            case (Some(targetType), Some(valueType)) if targetType != valueType =>
              Left(
                Rejection(
                  "NODAL-HIERARCHY-034",
                  s"instance parameter override type $valueType does not match $targetType"
                )
              )
            case (None, _) | (_, None) =>
              Left(
                Rejection(
                  "NODAL-HIERARCHY-033",
                  "instance parameter override type could not be proven"
                )
              )
            case _ if evidence.requiresDimension =>
              (evidence.targetDimensionSignature, evidence.valueDimensionSignature) match
                case (Some(targetDimension), Some(valueDimension))
                    if targetDimension != valueDimension =>
                  Left(
                    Rejection(
                      "NODAL-HIERARCHY-036",
                      s"parameter override dimension $valueDimension does not match $targetDimension"
                    )
                  )
                case (None, _) | (_, None) =>
                  Left(
                    Rejection(
                      "NODAL-HIERARCHY-035",
                      "parameter override dimension could not be proven"
                    )
                  )
                case _ => Right(())
            case _ => Right(())
