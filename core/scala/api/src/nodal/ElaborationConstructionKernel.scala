package nodal

import java.lang.ScopedValue

private[nodal] object ConstructionKernel:
  private val Current: ScopedValue[ConstructionSession] =
    ScopedValue.newInstance[ConstructionSession]()

  private def active: Option[ConstructionSession] =
    AnalogUserFunctionRuntime.rejectEffect()
    if Current.isBound then Some(Current.get) else None

  private def elaborate[M <: Module](
      top: => M,
      options: EmitOptions,
      stimulusOnly: Boolean = false
  )(select: M => Vector[AnyRef]): KernelSelectedCapture =
    ConstructorCaptureRuntime.withSession:
      AnalogProceduralConstruction.withSession:
        val session = new ConstructionSession(options, stimulusOnly)
        var result: Option[KernelSelectedCapture] = None
        ScopedValue.where(Current, session).run(
          new Runnable:
            override def run(): Unit =
              val root = top
              val selected = select(root)
              if stimulusOnly && session.operationKinds.exists(_ != "metadata") then
                failConstructor(
                  "SIM-DOMAIN-CAPTURE",
                  "stimulus harness cannot contain executable source effects",
                  "stimulus"
                )
              val (emission, snapshot) = session.finish(root)
              result = Some(KernelSelectedCapture(
                emission,
                snapshot,
                selected.map(session.selectedReference),
                session.operationKinds
              ))
        )
        result.getOrElse(
          scala.util.Failure[KernelSelectedCapture](
            new IllegalStateException("construction transaction did not publish a result")
          ).get
        )

  def emit(top: => Module, options: EmitOptions): Emission =
    elaborate(top, options)(_ => Vector.empty).emission

  def inspect(top: => Module, options: EmitOptions = EmitOptions()): ConstructionSnapshot =
    elaborate(top, options)(_ => Vector.empty).snapshot

  /** Selection runs within construction; only canonical immutable facts leave the transaction. */
  def capture[M <: Module](
      top: => M,
      options: EmitOptions = EmitOptions()
  )(select: M => Vector[AnyRef]): KernelSelectedCapture = elaborate(top, options)(select)

  /** Internal descriptor-only capture; never executable reset evidence. */
  def captureStimulus[M <: Module](top: => M)(select: M => Vector[AnyRef]): KernelSelectedCapture =
    elaborate(top, EmitOptions(), stimulusOnly = true)(select)

  def failConstructor(code: String, message: String, site: String): Nothing =
    scala.util.Failure[Nothing](
      new ConstructionException(KernelDiagnostic(code, message, Some(site)))
    ).get

  def prepareConstructorAllocation(site: String): Int = active match
    case Some(session) => session.prepareConstructorAllocation(site)
    case None =>
      failConstructor(
        "NODAL-CONSTRUCTOR-LIFECYCLE-022",
        "captured Module allocation requires an active construction transaction",
        site
      )

  def poisonConstructor(failure: Throwable, site: String): Unit =
    active.foreach(_.poisonConstructor(failure, site))

  def abortConstructorAllocation(
      depth: Int,
      failure: Throwable,
      site: String,
      invalidate: Boolean
  ): Unit =
    active.foreach(_.abortConstructorAllocation(depth, failure, site, invalidate))

  def constructorDepth: Int = active.map(_.constructorDepth).getOrElse(0)

  def bindConstructorParameters(
      module: Module,
      parameters: Vector[ConstructorCapturedParameter]
  ): Unit =
    active match
      case Some(session) => session.bindConstructorParameters(module, parameters)
      case None =>
        failConstructor(
          "NODAL-CONSTRUCTOR-LIFECYCLE-022",
          "constructor parameter binding requires an active construction transaction",
          module.getClass.getName
        )

  def commitConstructorAllocation(
      module: Module,
      parameters: Vector[ConstructorCapturedParameter]
  ): Boolean = active match
    case Some(session) => session.commitConstructorAllocation(module, parameters)
    case None =>
      failConstructor(
        "NODAL-CONSTRUCTOR-LIFECYCLE-022",
        "captured Module commit requires an active construction transaction",
        module.getClass.getName
      )

  def instance[M <: Module](module: M): (Instance[M], Boolean) = active match
    case Some(session) => session.instance(module, captured = false)
    case None =>
      failConstructor(
        "NODAL-CONSTRUCTOR-LIFECYCLE-022",
        "child Instance creation requires an active construction transaction",
        module.getClass.getName
      )

  def beginModule(module: Module): Unit = active.foreach(_.beginModule(module))

  def currentModulePath: String = active
    .map(_.currentModulePath)
    .getOrElse(
      scala.util.Failure[String](
        new IllegalStateException(
          "procedural module construction has no active transaction"
        )
      ).get
    )

  def requireNoGeneratedEffect(role: String): Unit =
    active.foreach(_.requireNoGeneratedEffect(role))

  def generatedRegionOwnership: Option[(String, String)] =
    active.flatMap(_.generatedRegionOwnership)

  def generatedRegion(
      lower: Int | Expr[Integer],
      upperExclusive: Int | Expr[Integer],
      step: Int | Expr[Integer],
      maximum: Option[Int]
  )(body: Expr[Integer] => Unit): Unit =
    active match
      case Some(session) =>
        session.withGeneratedRegion(lower, upperExclusive, step, maximum)(body)
      case None =>
        scala.util.Failure[Unit](
          new IllegalStateException("hdlRange requires an active Module")
        ).get

  def captureAnalogProceduralSource: Option[AnalogProceduralRuntime.Source] =
    active.flatMap(_.captureAnalogProceduralSource)

  def analogDimension(value: Any): Option[String] =
    active.map(_.inferAnalogDimension(value).canonical)

  def requireProceduralSources(expressions: Vector[KernelExpr[?]]): Unit =
    active.foreach(_.requireProceduralSources(expressions))

  def referencePath(value: Any): Option[String] = active.flatMap(_.pathOf(value))

  def analogReferencePath(value: Any): Option[String] = referencePath(value)

  def proceduralShapeDimension(value: Any): String =
    active.getOrElse(throw new IllegalStateException("shape requires active construction"))
      .proceduralShapeDimension(value)

  def analogEventConstant(value: Any): Option[Double] = value match
    case expression: KernelExpr[?] if expression.literal.exists(_.kind == "integer") =>
      expression.literal.flatMap(_.value.toDoubleOption)
    case _ => active.flatMap(_.waveformConstant(value))
  def classifyPulse(value: Expr[Bool]): Unit = active.foreach(_.classifyPulse(value))

  def levelSync(bit: Expr[Bool], to: ClockDomain, stages: Int): Expr[Bool] = active match
    case Some(session) => session.levelSync(bit, to, stages)
    case None => scala.util.Failure[Nothing](new IllegalStateException(
        "NODAL-CDC-057-001: Cdc.sync requires an active Module and lexical ClockDomain"
      )).get

  def graySync[A <: Data](value: Expr[A], to: ClockDomain, stages: Int): Expr[A] =
    active.getOrElse(throw new IllegalStateException("Cdc.gray requires active construction"))
      .executableCrossing(value, to, stages, "gray")

  def pulseSync(value: Expr[Bool], to: ClockDomain): Expr[Bool] =
    active.getOrElse(throw new IllegalStateException("Cdc.pulse requires active construction"))
      .executableCrossing(value, to, 2, "pulse")

  def handshakeSnapshot[A <: Data](value: Expr[A], to: ClockDomain): Expr[A] =
    active.getOrElse(throw new IllegalStateException("Cdc.handshake requires active construction"))
      .executableCrossing(value, to, 2, "handshake")

  def waive[A <: Data](value: Expr[A], to: ClockDomain, waiver: CdcWaiver): Expr[A] =
    active.getOrElse(throw new IllegalStateException("Cdc.waive requires active construction"))
      .waive(value, to, waiver)

  def stream[A <: Data](payload: Expr[A], valid: Expr[Bool]): Stream[A] =
    active.getOrElse(throw new IllegalStateException("Stream requires active construction"))
      .stream(payload, valid)

  def acceptStream[A <: Data](stream: Stream[A], ready: Expr[Bool]): Unit =
    active.getOrElse(throw new IllegalStateException("Stream.accept requires active construction"))
      .acceptStream(stream, ready)

  def asyncFifo[A <: Data](stream: Stream[A], to: ClockDomain, depth: Int): Stream[A] =
    active.getOrElse(throw new IllegalStateException("Cdc.fifo requires active construction"))
      .asyncFifo(stream, to, depth)

  def unsupportedStreamConsumer(name: String): Nothing =
    val failure = new IllegalStateException(
      s"NODAL-PIPELINE-059-UNSUPPORTED: $name has no executable elastic Stream contract"
    )
    poisonConstructor(failure, name)
    throw failure

  def resetSync(reset: Expr[Reset], to: Option[ClockDomain], stages: Int): Expr[Reset] =
    active.getOrElse(throw new IllegalStateException("Rdc.sync requires active construction"))
      .resetSync(reset, to, stages)

  def resetCombine(resets: Vector[Expr[Reset]]): Expr[Reset] =
    active.getOrElse(
      throw new IllegalStateException("ResetController requires active construction")
    )
      .resetCombine(resets)

  def clockGate(domain: ClockDomain, enable: Expr[Bool], testEnable: Expr[Bool], name: String)
      : ClockDomain =
    active.getOrElse(throw new IllegalStateException("ClockGate requires active construction"))
      .clockGate(domain, enable, testEnable, name)

  def clockMux(select: Expr[Bool], domains: Vector[ClockDomain], name: String): ClockDomain =
    active.getOrElse(throw new IllegalStateException("ClockMux requires active construction"))
      .clockMux(select, domains, name)

  def generatedDomain(
      name: String,
      clock: Expr[Clock],
      from: ClockDomain,
      relation: ClockRelation,
      reset: Expr[Reset]
  ): ClockDomain =
    active match
      case Some(session) => session.generatedDomain(name, clock, from, relation, reset)
      case None => new ClockDomain(
          name,
          reset,
          KernelDomainKind.Generated,
          relation = Some(relation),
          clock = Some(clock),
          parent = Some(from)
        )

  def registerDomain(domain: ClockDomain, kind: KernelDomainKind): Unit =
    active.foreach(_.registerDomain(domain, kind))

  def declare(
      value: AnyRef,
      kind: KernelSignalKind,
      dataType: Option[DataType[? <: Data]] = None,
      explicitName: Option[String] = None,
      domain: Option[ClockDomain] = None,
      attributes: Vector[(String, Any)] = Vector.empty
  ): Unit = active.foreach(
    _.registerDeclaration(value, kind, dataType, explicitName, domain, attributes)
  )

  def expression(value: AnyRef): Unit =
    if !AnalogUserFunctionRuntime.capture(value) then active.foreach(_.registerExpression(value))

  def digitalLoop(bound: LoopBound)(body: Expr[Integer] => Unit): Unit = active match
    case Some(session) => session.captureDigitalLoop(bound)(body)
    case None =>
      scala.util.Failure[Nothing](new IllegalStateException("loop requires an active Module")).get

  def staticPredicate(operation: String, operands: Vector[Any]): StaticPredicate = active match
    case Some(session) => session.captureStaticPredicate(operation, operands)
    case None => scala.util.Failure[Nothing](new IllegalStateException(
        "structural predicate requires an active Module"
      )).get

  def behavioralComparison(
      left: Expr[Integer],
      right: Expr[Integer],
      operation: String
  ): Expr[Bool] = active match
    case Some(session) => session.captureBehavioralComparison(left, right, operation)
    case None => SequentialRuntime.missing()

  def behavioralBranch(kind: String, condition: Option[Expr[Bool]], body: () => Unit): Unit =
    try
      DigitalCollectionCallback.requireSequentialChecked(body)
      active match
        case Some(session) => session.captureBehavioralBranch(kind, condition, body)
        case None => SequentialRuntime.missing()
    catch
      case failure: Throwable =>
        poisonConstructor(failure, "behavioral guard")
        scala.util.Failure[Nothing](failure).get

  private def flatFsmSession: ConstructionSession =
    active.getOrElse(FlatFsmRuntime.fail("flat FSM and action switch require an active Module"))

  def flatFsm[S](
      definition: FsmDefinition[S],
      initial: S,
      encoding: FsmEncoding,
      custom: Option[FsmLocalEncoding[S]],
      illegalState: IllegalStatePolicy,
      body: FsmBuilder[S] => Unit,
      evidence: HwEnum[S]
  ): Unit = flatFsmSession.captureFlatFsm(
    definition,
    initial,
    encoding,
    custom,
    illegalState,
    body,
    evidence
  )

  def flatFsmState[S](builder: FsmBuilder[S], value: S, body: StateBuilder[S] => Unit): Unit =
    flatFsmSession.captureFlatFsmState(builder, value, body)

  def flatFsmTerminal[S](builder: StateBuilder[S]): Unit =
    flatFsmSession.captureFlatFsmTerminal(builder)

  private def checkedFsmAction(body: () => Unit): Unit =
    try DigitalCollectionCallback.requireSequentialChecked(body)
    catch
      case failure: Throwable =>
        poisonConstructor(failure, "flat FSM action")
        scala.util.Failure[Nothing](failure).get

  def flatFsmAction[S](builder: StateBuilder[S], phase: String, body: () => Unit): Unit =
    checkedFsmAction(body)
    flatFsmSession.captureFlatFsmAction(builder, phase, body)

  def flatFsmTransition[S](
      builder: StateBuilder[S],
      condition: Expr[Bool],
      mode: TransitionMode,
      to: S,
      body: Option[() => Unit]
  ): Unit =
    body.foreach(checkedFsmAction)
    flatFsmSession.captureFlatFsmTransition(builder, condition, mode, to, body)

  def flatFsmStateView[S](builder: FsmBuilder[S], next: Boolean): DecodedEnum[Expr[EnumValue[S]]] =
    flatFsmSession.flatFsmStateView(builder, next)

  def flatFsmEvent[S](builder: FsmBuilder[S], state: S, entering: Boolean): Expr[Bool] =
    flatFsmSession.flatFsmEvent(builder, state, entering)

  def flatFsmTaken[S](builder: FsmBuilder[S]): Expr[Bool] = flatFsmSession.flatFsmTaken(builder)

  def flatFsmCompleted[S](builder: FsmBuilder[S]): Expr[Bool] =
    flatFsmSession.flatFsmCompleted(builder)

  def enumSwitch[S](
      selector: Expr[EnumValue[S]],
      invalid: () => Unit,
      body: EnumSwitchBuilder[S] => Unit,
      evidence: HwEnum[S]
  ): Unit =
    checkedFsmAction(invalid)
    flatFsmSession.captureEnumSwitch(selector, invalid, body, evidence)

  def enumSwitchCase[S](builder: EnumSwitchBuilder[S], value: S, body: () => Unit): Unit =
    checkedFsmAction(body)
    flatFsmSession.captureEnumSwitchCase(builder, value, body)

  def structuralIf(predicate: StaticPredicate, hasResult: Boolean)(
      whenTrue: () => Option[Expr[?]],
      whenFalse: () => Option[Expr[?]]
  ): Option[Expr[?]] = active match
    case Some(session) => session.captureStructuralIf(predicate, hasResult)(whenTrue, whenFalse)
    case None => scala.util.Failure[Nothing](new IllegalStateException(
        "structural if requires an active Module"
      )).get

  def collection(
      kind: String,
      inputs: Vector[Expr[?]],
      seed: Option[Expr[?]]
  )(body: Vector[Expr[?]] => Expr[?]): Expr[?] = active match
    case Some(session) => session.captureCollection(kind, inputs, seed)(body)
    case None => DigitalCollectionContract.fail(1, "collection requires an active Module")

  def declareNodeArray(
      value: NodeArray[?],
      discipline: Discipline,
      dimensions: Vector[Dimension],
      kind: KernelSignalKind
  ): Unit =
    active.getOrElse(throw new IllegalStateException("node array requires an active Module"))
      .registerNodeArray(value, discipline, dimensions, kind)

  def projectNodeArray(value: NodeArray[?], origin: NodeArrayProjection): Unit =
    active.getOrElse(throw new IllegalStateException("node array view requires an active Module"))
      .registerNodeArrayProjection(value, origin)

  def selectNodeArray(
      value: Node[?],
      array: NodeArray[?],
      indices: Vector[Dimension]
  ): ExpressionRef =
    active.getOrElse(
      throw new IllegalStateException("node array selection requires an active Module")
    )
      .registerNodeSelection(value, array, indices)

  def shapeIndex[A <: Data, B <: Data](
      expression: KernelExpr[B],
      input: Expr[Vec[A]],
      indices: Vector[Dimension]
  ): Unit = active match
    case Some(session) => session.registerShapeIndex(expression, input, indices)
    case None => ()

  def shapeView[A <: Data](
      expression: KernelExpr[Vec[A]],
      input: Expr[Vec[A]],
      dimensions: Vector[Dimension],
      slice: Option[ShapeSliceSelection] = None
  ): Unit = active match
    case Some(session) => session.registerShapeView(expression, input, dimensions, slice)
    case None => ()

  def defineUserFunction[A <: Data](
      name: String,
      resultType: DataType[A],
      dimension: PhysicalDimension,
      body: AnalogFunctionBody => Expr[A]
  ): AnalogFunction[A] = active match
    case Some(session) =>
      session.requireNoGeneratedEffect("analog function definition")
      session.defineUserFunction(name, resultType, dimension, body)
    case None => AnalogUserFunctionRuntime.fail(1, "analog function requires an active Module")

  def callUserFunction[A <: Data](
      function: AnalogFunction[A],
      arguments: Vector[Expr[?]]
  ): Expr[A] = active match
    case Some(session) =>
      session.requireNoGeneratedEffect("analog function call")
      session.callUserFunction(function, arguments)
    case None => AnalogUserFunctionRuntime.fail(1, "analog function call requires an active Module")

  def analogFunction(value: KernelExpr[Real], id: String): Unit =
    if !AnalogUserFunctionRuntime.capture(value) then
      active.foreach: session =>
        session.requireNoGeneratedEffect("analog function expression")
        session.registerAnalogFunction(value, id)

  def continuousOperator(
      value: KernelExpr[Real],
      operation: String,
      input: Expr[Real],
      initialValue: Option[Expr[Real]]
  ): Unit =
    active.foreach: session =>
      if !session.generatedContinuousRegionActive then
        session.requireNoGeneratedEffect(s"continuous-time operator '$operation'")
      session.registerContinuousOperator(value, operation, input, initialValue)

  def transferOperator(
      value: KernelExpr[Real],
      kind: String,
      numeratorSize: Int,
      denominatorSize: Int,
      inputs: Vector[Expr[Real]]
  ): Unit = active match
    case Some(session) =>
      if !session.generatedContinuousRegionActive then
        session.requireNoGeneratedEffect(s"analog transfer operator '$kind'")
      session.registerTransferOperator(value, kind, numeratorSize, denominatorSize, inputs)
    case None => AnalogTransferContract.fail(1, "transfer state requires an active Module")

  def noiseOperator(
      value: KernelExpr[Real],
      kind: String,
      label: String,
      inputs: Vector[Expr[Real]]
  ): Unit = active match
    case Some(session) =>
      if !session.generatedContinuousRegionActive then
        session.requireNoGeneratedEffect(s"analog noise operator '$kind'")
      session.registerNoiseOperator(value, kind, label, inputs)
    case None => AnalogNoiseContract.fail(1, "noise sources require an active Module construction")

  def waveformOperator(
      value: KernelExpr[Real],
      operation: String,
      inputs: Vector[Expr[Real]]
  ): Unit =
    active.foreach: session =>
      val ownedGeneratedWaveform =
        session.generatedContinuousRegionActive &&
          Set(
            "analog_transition",
            "analog_slew",
            "analog_absdelay",
            "analog_abstime",
            "analog_bound_step"
          ).contains(operation)
      if !ownedGeneratedWaveform &&
        !(operation == "analog_abstime" && AnalogProceduralConstruction.generatedProcedureActive)
      then session.requireNoGeneratedEffect(s"analog waveform operator '$operation'")
      session.registerWaveformOperator(value, operation, inputs)

  def waveformForbidden[A](body: => A): A = active match
    case Some(session) => session.withWaveformForbidden(body)
    case None => body

  def instanceDigitalPort[A <: Data](value: Signal[A]): Signal[A] =
    active.getOrElse(
      throw new IllegalStateException("instance selection requires active construction")
    ).instanceDigitalPort(value)

  def withInstanceSelection[A](instance: Instance[?])(body: => A): A =
    active.getOrElse(
      throw new IllegalStateException("instance selection requires active construction")
    )
      .withInstanceSelection(instance)(body)

  def nodeArrayUse[D <: Discipline](value: NodeArray[D]): NodeArray[D] =
    active.getOrElse(throw new IllegalStateException("node array requires active construction"))
      .nodeArrayUse(value)

  def declareInstanceNodeArray(
      value: NodeArray[?],
      instance: Instance[?],
      port: NodeArray[?]
  ): Unit =
    active.getOrElse(throw new IllegalStateException("instance array requires active construction"))
      .registerInstanceNodeArray(value, instance, port)

  def validateInstanceSelection(instance: Instance[?]): Unit =
    active.foreach(_.validateInstanceSelection(instance))

  def attachInstance(instance: Instance[? <: Module], child: Module): Unit =
    active.foreach(_.attachInstance(instance, child))

  def bindDefault(instance: Instance[?], domain: ClockDomain): Unit =
    active.foreach(_.bindDefault(instance, domain))

  def bindNamed(instance: Instance[?], requirement: ClockDomain, domain: ClockDomain): Unit =
    active.foreach(_.bindNamed(instance, requirement, domain))

  def connectNodeArrays(left: NodeArray[?], right: NodeArray[?]): Unit =
    active.getOrElse(throw new IllegalStateException("array binding requires an active Module"))
      .connectNodeArrays(left, right)

  def connectNodes(left: AnyRef, right: AnyRef): Unit =
    active.foreach(_.connectNodes(left, right))

  def overrideParameter(instance: Instance[?], parameter: Any, value: Any): Unit =
    active.foreach(_.overrideParameter(instance, parameter, value))

  def domainBlock[A](domain: ClockDomain)(body: => A): A = active match
    case Some(session) => session.withDomain(domain)(body)
    case None => body

  def digitalEvent(trigger: Expr[Bool], edge: Edge): Event = active match
    case Some(session) => session.digitalEvent(trigger, edge)
    case None => new Event(digitalDefinition = Some(KernelDigitalEventDefinition(trigger, edge)))

  def digitalEventProcess(event: Event, body: () => Unit): Unit = active match
    case Some(session) => session.digitalEventProcess(event, body)
    case None =>
      scala.util.Failure[Nothing](new ConstructionException(KernelDiagnostic(
        "NODAL-EVENT-057-001",
        "digital event process requires an active construction transaction"
      ))).get

  def block[A](body: => A): A = body

  def analogBlock[A](body: => A): A = active match
    case Some(session) =>
      if !session.generatedRegionActive then
        session.requireNoGeneratedEffect("analog continuous region")
      session.withAnalogRegion(body)
    case None => body

  def physicalComponent[A](contract: PhysicalComponentContract)(body: => A): A = active match
    case Some(session) => session.withPhysicalComponent(contract)(body)
    case None => body

  def declareAnalogState(state: AnalogState): Unit = active.foreach(_.declareAnalogState(state))

  def analogSemanticBlock[A](
      kind: AnalogEquationRuntime.RegionKind
  )(body: => A): A = active match
    case Some(session) =>
      val ownedGeneratedProcedure =
        kind == AnalogEquationRuntime.RegionKind.Procedural &&
          AnalogProceduralConstruction.generatedProcedureActive
      if !ownedGeneratedProcedure then
        session.requireNoGeneratedEffect(s"analog ${kind.toString.toLowerCase} region")
      session.withAnalogSemanticRegion(kind)(body)
    case None => body

  def analogEquation(
      left: Expr[Real],
      right: Expr[Real],
      options: EquationOptions
  ): Unit = active.foreach(_.recordAnalogEquation(left, right, options))

  def initialAnalogEquation(
      left: Expr[Real],
      right: Expr[Real],
      options: InitialEquationOptions
  ): Unit = active.foreach(_.recordInitialAnalogEquation(left, right, options))

  def analogContribution(
      target: Expr[Real],
      value: Expr[Real],
      options: ContributionOptions
  ): Unit = active.foreach(_.recordAnalogContribution(target, value, options))

  def shortAnalogContribution(target: Expr[Real], value: Expr[Real]): Unit =
    active.foreach(_.recordShortAnalogContribution(target, value))

  def digitalWidthBounds(descriptor: KernelTypeDescriptor): Option[(Long, Long)] =
    active.map(_.digitalWidthBounds(descriptor))

  def sameStaticInteger(left: Dimension, right: Dimension): Option[Boolean] =
    active.map(_.sameStaticInteger(left, right))

  def digitalWidthEquals(descriptor: KernelTypeDescriptor, expected: Int): Boolean =
    descriptor.arguments == Vector(expected) ||
      active.exists(_.digitalWidthEquals(descriptor, expected))

  def currentDomain: Option[ClockDomain] = active.flatMap(_.currentDomain)

  def assignSignalSelection(left: Expr[?], right: Expr[?]): Unit =
    active.foreach(_.assignSignalSelection(left, right))

  def memoryRead[A <: Data](memory: Mem[A], address: Expr[UInt]): Expr[A] = active match
    case Some(session) => session.memoryRead(memory, address)
    case None => scala.util.Failure[Nothing](new ConstructionException(KernelDiagnostic(
        "NODAL-MEMORY-056-001",
        "memory read requires an active owning Module"
      ))).get

  def memoryWrite[A <: Data](
      memory: Mem[A],
      address: Expr[UInt],
      value: Expr[A],
      mask: Expr[Bits]
  ): Unit = active match
    case Some(session) => session.memoryWrite(memory, address, value, mask)
    case None => scala.util.Failure[Nothing](new ConstructionException(KernelDiagnostic(
        "NODAL-MEMORY-056-001",
        "memory write requires an active owning Module"
      ))).get

  def operation(kind: String, values: Any*): Unit = active.foreach(_.operation(kind, values*))
