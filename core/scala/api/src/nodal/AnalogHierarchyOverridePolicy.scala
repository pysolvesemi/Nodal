package nodal

/** Pure validation policy for Increment 42 instance-parameter overrides.
  *
  * The construction transaction remains responsible for deriving these facts from
  * exact declaration, expression, module, and instance identities. This helper owns
  * only the fail-closed policy so the eventual integration does not duplicate the
  * accepted staticness/type/unit rules.
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
    if evidence.duplicate then
      Left(
        Rejection(
          "NODAL-HIERARCHY-030",
          "one child parameter may be overridden only once per Instance"
        )
      )
    else if
      evidence.referencedDeclarationOwners.exists(_ != evidence.parentOwner) ||
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
      evidence.operations.find(operation => !acceptedStaticOperations.contains(operation)) match
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
                      s"real parameter override dimension $valueDimension does not match $targetDimension"
                    )
                  )
                case (None, _) | (_, None) =>
                  Left(
                    Rejection(
                      "NODAL-HIERARCHY-035",
                      "real parameter override dimension could not be proven"
                    )
                  )
                case _ => Right(())
            case _ => Right(())
