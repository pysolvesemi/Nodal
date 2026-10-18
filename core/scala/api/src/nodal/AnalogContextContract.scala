package nodal

/** Independent source validation of the shared simulator-context registry. Expressions execute at
  * their consumer and never carry an elaboration-time default.
  */
private[nodal] object AnalogContextContract:
  val Version = AnalogFunctionRegistry.Version
  val Prefix = AnalogFunctionRegistry.ContextPrefix

  def fail(number: Int, message: String): Nothing =
    scala.util.Failure[Nothing](
      new ConstructionException(KernelDiagnostic(f"NODAL-ANALOG-044-$number%03d", message))
    ).get

  private def descriptor(id: String): AnalogFunctionRegistry.ContextEntry =
    AnalogFunctionRegistry.contextEntries.find(_.id == id)
      .getOrElse(fail(1, s"unknown simulator context query '$id'"))

  def entry(id: String): AnalogFunctionRegistry.ContextEntry =
    val value = descriptor(id)
    if !Set("supported", "conditional-standard").contains(value.availability) then
      fail(1, s"simulator context '$id' is not a scalar context operation")
    value

  def validate(expression: KernelExpr[?]): AnalogFunctionRegistry.ContextEntry =
    val id = expression.operation.filter(_.startsWith(Prefix))
      .map(_.stripPrefix(Prefix)).getOrElse(fail(1, "invalid simulator context identity"))
    val value = entry(id)
    if expression.operands.nonEmpty then fail(2, "simulator context query requires zero operands")
    if expression.resultType != Some(KernelTypeDescriptor("Real")) then
      fail(3, "simulator context query requires a Real result")
    if expression.literal.nonEmpty then
      fail(4, "simulator context query cannot carry a constant value")
    value

  def query(id: String): Expr[Real] =
    CandidateRuntime.analogExpr(Prefix + entry(id).id)

  def unavailable(
      id: String,
      selector: Option[String] = None,
      dimension: Option[PhysicalDimension] = None
  ): Nothing =
    val value = descriptor(id)
    if value.selectorKind == "none" && selector.nonEmpty ||
      value.selectorKind != "none" && !selector.exists(_.matches("[A-Za-z_][A-Za-z0-9_.:-]*")) ||
      (value.dimension == "supplied") != dimension.nonEmpty ||
      dimension.exists(value => ConstructionExpressionFacts.inferAnalogDimension(value).isUnknown)
    then fail(1, s"invalid simulator context request '$id'")
    fail(5, s"simulator context '$id' has no supported target capability binding")

  def containsQuery(value: Any): Boolean =
    val visited = new java.util.IdentityHashMap[AnyRef, java.lang.Boolean]()
    def visit(value: Any): Boolean = value match
      case expression: KernelExpr[?] if !visited.containsKey(expression) =>
        visited.put(expression, java.lang.Boolean.TRUE)
        expression.operation.exists(operation =>
          operation.startsWith(Prefix) ||
            operation.startsWith(AnalogFunctionRegistry.AnalysisPrefix) ||
            operation == "analog_abstime"
        ) || expression.operands.exists(visit)
      case _ => false
    visit(value)
