package nodal

/** Compiler adapters and immutable local encoding policy. Capture belongs to ConstructionSession.
  */
private[nodal] object FlatFsmRuntime:
  val MaximumWidth = 4096
  val MaximumRecords = 65536
  val NextOperation = "flat_fsm_next"
  val TakenOperation = "flat_fsm_taken"
  val CompletedOperation = "flat_fsm_completed"
  val Operations: Set[String] = Set(NextOperation, TakenOperation, CompletedOperation)

  def fail(message: String, code: String = "NODAL-FSM-056-001"): Nothing =
    val failure = new ConstructionException(KernelDiagnostic(code, message))
    ConstructionKernel.poisonConstructor(failure, "flat FSM")
    scala.util.Failure[Nothing](failure).get

  def unchecked(body: () => Unit): Nothing =
    val _ = body
    fail(
      "action requires typed effect validation by the Nodal compiler plugin",
      "NODAL-SEQUENTIAL-056-MISSING"
    )

  def unsupported(): Nothing =
    fail("hierarchical, parallel and timed statecharts require F58", "NODAL-FSM-058-UNAVAILABLE")

  def definition[S](name: String, dataType: DataType[EnumValue[S]], evidence: HwEnum[S])
      : FsmDefinition[S] =
    if Option(name).forall(_.trim.isEmpty) then fail("machine definition requires a nonempty name")
    val canonical = DigitalEnumContract.abi(dataType)
    val standard = DigitalEnumContract.codeMap(evidence, "sequential")
    if canonical.identity != standard.identity ||
      canonical.caseIds != standard.cases.map(_.id).toSet
    then
      fail("canonical type belongs to a different enum")
    new FsmDefinition(name, dataType)

  def localCodes[S](encoding: FsmEncoding, custom: Option[FsmLocalEncoding[S]], evidence: HwEnum[S])
      : (String, ResolvedEnumCodeMap) =
    val policy = encoding match
      case FsmEncoding.Compact | FsmEncoding.Auto => "sequential"
      case FsmEncoding.OneHot => "one_hot"
      case FsmEncoding.Gray => "gray"
      case FsmEncoding.Custom => "custom"
    val result = if policy == "custom" then
      custom.map(_.codes).getOrElse(fail("Custom requires a typed FsmLocalEncoding payload"))
    else DigitalEnumContract.codeMap(evidence, policy)
    if result.width > MaximumWidth then fail("local state width exceeds 4096")
    val resolved = if encoding == FsmEncoding.Auto then "auto-compact-v1"
    else if policy == "sequential" then "compact" else policy
    resolved -> result

  def encodingName(value: FsmEncoding): String = value match
    case FsmEncoding.Compact => "compact"
    case FsmEncoding.OneHot => "one_hot"
    case FsmEncoding.Gray => "gray"
    case FsmEncoding.Auto => "auto"
    case FsmEncoding.Custom => "custom"

  def bits(value: BigInt, width: Int): Expr[Bits] =
    if value < 0 || value.bitLength > width || width < 1 || width > MaximumWidth then
      fail("local code does not fit its exact Bits width")
    val dataType = KernelTypeDescriptor("Bits", Vector(width))
    val result = new KernelExpr[Bits](
      Vector.empty,
      resultType = Some(dataType),
      literal = Some(KernelLiteral("integer", value.toString, dataType)),
      operation = Some("integer")
    )
    ConstructionKernel.expression(result)
    result

  def action[S](builder: StateBuilder[S], phase: String, body: () => Unit): Unit =
    ConstructionKernel.flatFsmAction(builder, phase, body)

  def transitionAction[S](
      builder: StateBuilder[S],
      condition: Expr[Bool],
      mode: TransitionMode,
      to: S,
      body: () => Unit
  ): Unit = ConstructionKernel.flatFsmTransition(builder, condition, mode, to, Some(body))

  def switchCase[S](builder: EnumSwitchBuilder[S], value: S, body: () => Unit): Unit =
    ConstructionKernel.enumSwitchCase(builder, value, body)

  def switchEnum[S](
      selector: Expr[EnumValue[S]],
      invalid: () => Unit,
      body: EnumSwitchBuilder[S] => Unit,
      evidence: HwEnum[S]
  ): Unit = ConstructionKernel.enumSwitch(selector, invalid, body, evidence)
