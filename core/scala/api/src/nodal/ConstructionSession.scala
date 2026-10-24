package nodal

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.IdentityHashMap

import scala.collection.mutable

/** One mutable transaction. JVM identity is used only for transient lookup; stable paths use
  * hierarchy, explicit names, and deterministic local ordinals.
  */
private final class ConstructionSession(val options: EmitOptions, stimulusOnly: Boolean = false):
  import ConstructionExpressionFacts.{disciplineDimension, waveformContinuity, waveformStatic}

  def inferAnalogDimension(value: Any): AnalogDimension =
    ConstructionExpressionFacts.inferAnalogDimension(value)

  def waveformConstant(value: Any): Option[Double] =
    ConstructionExpressionFacts.waveformConstant(value)

  /** Consumer placement follows actual registered results, even when source shape wrappers hide
    * their rendered operation. The existing operator inventories remain the classification owner.
    */
  def requireProceduralSources(expressions: Vector[KernelExpr[?]]): Unit =
    val references = expressions.flatMap(value => Option(expressionIds.get(value))).toSet
    def reject(reference: ExpressionRef, code: String, role: String): Nothing =
      AnalogProceduralConstruction.requireContinuousContext(role)
      fail(
        code,
        s"$role is not permitted in analog procedural expressions",
        Some(expressionPath(reference))
      )
    continuousOperators.find(value => references.contains(value.reference)).foreach: value =>
      reject(
        value.reference,
        "NODAL-ANALOG-035-001",
        s"stateful or continuous-time operator '${value.operation}'"
      )
    waveformOperators.find(value =>
      references.contains(value._1) && value._2 != "analog_abstime"
    ).foreach: value =>
      reject(value._1, "NODAL-ANALOG-036-001", s"time/waveform operator '${value._2}'")
    transferOperators.find(value => references.contains(value._1)).foreach: value =>
      reject(value._1, "NODAL-ANALOG-040-001", s"stateful transfer operator '${value._2}'")
    noiseOperators.find(value => references.contains(value._1)).foreach: value =>
      reject(value._1, "NODAL-ANALOG-039-001", s"noise effect '${value._2}'")

  // The construction session is the sole mutable event/process owner. Event storage is still
  // an actual output declaration and uses the ordinary assignment/dependency inventories.
  private val eventProcesses = mutable.ArrayBuffer.empty[DigitalEventProcessRecord]
  private val eventStorage = mutable.HashMap.empty[DeclarationRef, DigitalEventProcessRecord]
  private var activeEventProcess: Option[DigitalEventProcessRecord] = None
  private val eventStateBits = mutable.HashMap.empty[Long, BigInt]

  private def eventFailure(message: String): Nothing =
    behavioralFailure(message, "NODAL-EVENT-057-001")

  private def eventProcessPath(process: DigitalEventProcessRecord): String =
    s"${modulePath(process.owner)}.event_process_${process.ordinal}"

  private def eventInput(value: Expr[?], owner: Long, bool: Boolean): DeclarationRef =
    val reference = Option(declarationIds.get(value)).getOrElse(
      eventFailure("event input requires an actual root input boundary, not a computed alias")
    )
    val declaration = records(reference.module).declarations(reference.index)
    if reference.module != owner || records(owner).parentAtConstruction.nonEmpty ||
      declaration.kind != KernelSignalKind.Input || declaration.domainCandidate.nonEmpty ||
      !declaration.dataType.exists(dataType =>
        if bool then CandidateRuntime.typeDescriptor(dataType) == KernelTypeDescriptor("Bool")
        else DigitalCombinationalContract.isValueTypeKind(CandidateRuntime.typeDescriptor(dataType))
      )
    then eventFailure("event input requires an unannotated root-owned digital input boundary")
    if !bool then
      val bits = shapeTypeFootprint(
        declaration.dataType.get,
        owner,
        cardinality = true,
        storageContext = true,
        bitPatternOnly = true,
        fixedBitPattern = true
      ).getOrElse(eventFailure("event capture requires a fixed whole digital bit pattern"))
      if bits < 1 || bits > DigitalEventContract.MaximumPayloadBits then
        eventFailure("event external capture fixed whole payload must contain 1 through 4096 bits")
    reference

  def digitalEvent(trigger: Expr[Bool], edge: Edge): Event =
    if activeEventProcess.nonEmpty then eventFailure("nested event construction is unsupported")
    if edge != Edge.Rising && edge != Edge.Falling then
      eventFailure("digital events require one rising or falling edge")
    val _ = eventInput(trigger, currentModule.handle, bool = true)
    new Event(digitalDefinition = Some(KernelDigitalEventDefinition(trigger, edge)))

  def digitalEventProcess(event: Event, body: () => Unit): Unit =
    DigitalEventCallback.requireChecked(body)
    if activeEventProcess.nonEmpty || currentDomain.nonEmpty || activeValueRegion.nonEmpty ||
      activeGeneratedRegion.nonEmpty || analogStack.nonEmpty || analogSemanticContext.nonEmpty
    then eventFailure("event process requires direct root construction without nested scopes")
    val module = currentModule
    val definition = event.digitalDefinition.getOrElse(
      eventFailure("digital event process requires an owned actual edge definition")
    )
    val _ = eventInput(definition.trigger, module.handle, bool = true)
    if eventProcesses.size >= DigitalEventContract.MaximumNodes then
      eventFailure("event process inventory exceeds 65536 nodes")
    val process = new DigitalEventProcessRecord(
      module.handle,
      eventProcesses.size,
      definition,
      semanticOrigin.captureSemanticSource()
    )
    eventProcesses += process
    activeEventProcess = Some(process)
    try
      ConstructionKernel.waveformForbidden(body())
      if process.targets.isEmpty then eventFailure("event process must update a tracked output")
    catch
      case failure: Throwable =>
        poisonConstructor(failure, "digital event process")
        scala.util.Failure[Nothing](failure).get
    finally activeEventProcess = None

  private def captureEventAssignment(values: Vector[Any]): Unit =
    activeEventProcess.foreach: process =>
      if values.size != 2 then eventFailure("event update requires one target and value")
      val target = Option(declarationIds.get(values.head.asInstanceOf[AnyRef])).getOrElse(
        eventFailure("event update requires one whole output; projected writes are unsupported")
      )
      val declaration = records(target.module).declarations(target.index)
      if target.module != process.owner || declaration.kind != KernelSignalKind.Output ||
        declaration.domainCandidate.nonEmpty || activeValueRegion.nonEmpty ||
        activeGeneratedRegion.nonEmpty
      then eventFailure("event update requires one unannotated whole root output")
      if eventStorage.contains(target) then
        eventFailure("event output has more than one writer or update")
      val bits = shapeTypeFootprint(
        declaration.dataType.getOrElse(eventFailure("event output has no actual type")),
        target.module,
        cardinality = true,
        storageContext = true,
        bitPatternOnly = true,
        fixedBitPattern = true
      ).getOrElse(eventFailure("event output requires a fixed whole digital bit pattern"))
      if bits < 1 || bits > DigitalEventContract.MaximumPayloadBits then
        eventFailure("event output fixed whole payload must contain 1 through 4096 bits")
      val total = eventStateBits.getOrElse(target.module, BigInt(0)) + bits
      if total > DigitalEventContract.MaximumModuleStateBits then
        eventFailure("module event state exceeds 262144 bits")
      eventStateBits.update(target.module, total)
      val source = values(1) match
        case expression: Expr[?] => expression
        case _ => eventFailure("event update requires an actual digital expression")
      if !CandidateRuntime.expressionDataType(source).exists(dataType =>
          DigitalEnumContract.sameType(
            CandidateRuntime.typeDescriptor(declaration.dataType.get),
            CandidateRuntime.typeDescriptor(dataType)
          )
        )
      then eventFailure("event update requires exactly matching recursive types")
      eventStorage.update(target, process)
      process.targets += target

  private def eventProcessSnapshots: Vector[KernelEventProcessSnapshot] =
    eventProcesses.toVector.map: process =>
      KernelEventProcessSnapshot(
        eventProcessPath(process),
        modulePath(process.owner),
        pathOf(process.definition.trigger).getOrElse(eventFailure("event trigger lost identity")),
        if process.definition.edge == Edge.Rising then "rising" else "falling",
        process.targets.toVector.map(declarationPath),
        process.externalCaptures.map(declarationPath)
      )

  private final case class ExecutableCrossing(
      input: Expr[?],
      destination: ClockDomain,
      stages: Int,
      kind: String,
      valid: Option[Expr[Bool]] = None,
      ready: Option[Expr[Bool]] = None,
      depth: Option[Int] = None,
      var results: Vector[(ExpressionRef, String)] = Vector.empty,
      var sourceDomain: Option[String] = None,
      var sourceEndpoint: Option[DomainRef] = None,
      var destinationDomain: Option[String] = None
  ):
    def sourceInputs: Vector[Expr[?]] = Vector(input) ++ valid
    def sampledInputs: Vector[Expr[?]] = sourceInputs ++ ready
    def identity(reference: ExpressionRef): ExpressionRef = results.headOption.map(_._1)
      .getOrElse(reference)
    def role(reference: ExpressionRef): String = results.find(_._1 == reference).map(_._2)
      .getOrElse("payload")
  private val executableCrossings = mutable.LinkedHashMap.empty[ExpressionRef, ExecutableCrossing]
  private var crossingStateBits = BigInt(0)
  private val pulseValues = new IdentityHashMap[Expr[Bool], java.lang.Boolean]()

  private def crossingFailure(message: String): Nothing =
    behavioralFailure(message, "NODAL-CDC-057-001")

  def classifyPulse(value: Expr[Bool]): Unit =
    if activeEventProcess.nonEmpty then
      eventFailure("Pulse classification is unsupported in event body")
    val _ = pulseValues.put(value, java.lang.Boolean.TRUE)

  private def streamTransaction[A](label: String)(body: => A): A =
    try
      closeBehavioralChain()
      if activeValueRegion.nonEmpty || activeGeneratedRegion.nonEmpty ||
        analogStack.nonEmpty || analogSemanticContext.nonEmpty
      then crossingFailure(s"$label requires module-level construction; callbacks are unsupported")
      body
    catch
      case failure: Throwable =>
        poisonConstructor(failure, label)
        scala.util.Failure[Nothing](failure).get

  private def ownedStreamValue(value: Expr[?], label: String, bool: Boolean = false): Unit =
    val reference = valueReference(value)
    val owner = reference match
      case value: DeclarationRef => value.module
      case value: ExpressionRef => value.module
    val dataType = CandidateRuntime.expressionDataType(value).getOrElse(
      crossingFailure(s"$label requires an actual owned digital value")
    )
    if owner != currentModule.handle ||
      (bool && CandidateRuntime.typeDescriptor(dataType) != KernelTypeDescriptor("Bool"))
    then
      crossingFailure(
        s"$label requires an actual owned ${if bool then "Bool" else "digital"} value"
      )

  private def newStreamReady(): Signal[Bool] =
    val ready = new Signal(Bool, KernelSignalKind.Wire)
    val default = false.B
    val reference = declarationIds.get(ready)
    val module = records(reference.module)
    val declaration = module.declarations(reference.index)
    module.declarations.update(
      reference.index,
      declaration.copy(attributes =
        declaration.attributes :+ ("stream_ready_default" -> default)
      )
    )
    ready

  def stream[A <: Data](payload: Expr[A], valid: Expr[Bool]): Stream[A] =
    streamTransaction("Stream"):
      ownedStreamValue(payload, "Stream payload")
      ownedStreamValue(valid, "Stream valid", bool = true)
      registerStream(payload, valid, newStreamReady())

  private def registerStream[A <: Data](
      payload: Expr[A],
      valid: Expr[Bool],
      ready: Signal[Bool]
  ): Stream[A] =
    val reference = declarationIds.get(ready)
    val module = records(reference.module)
    val declaration = module.declarations(reference.index)
    module.declarations(reference.index) = declaration.copy(attributes =
      declaration.attributes ++ Vector("stream_payload" -> payload, "stream_valid" -> valid)
    )
    new Stream(payload, valid, ready)

  /** Read-only transfer observation shares the existing carrier/ready declaration owner. */
  def fsmTransfer[A <: Data](carrier: Stream[A]): Expr[Bool] =
    streamTransaction("FSM transfer wait"):
      if carrier == null then
        FsmDefinitionRuntime.fail("transfer binding requires an actual Stream")
      ownedStreamValue(carrier.payload, "Stream payload")
      ownedStreamValue(carrier.valid, "Stream valid", bool = true)
      ownedStreamValue(carrier.ready, "Stream ready", bool = true)
      val tuple = fsmTransferTuple(carrier.valid, carrier.ready)
      if valueReference(tuple) != valueReference(carrier.payload) then
        FsmDefinitionRuntime.fail("transfer binding differs from its actual Stream carrier")
      // A binding retains explicit operands, never a captured predicate shared by machines.
      // Every occurrence rechecks these identities and creates its own actual predicate.
      new KernelExpr[Bool](
        Vector(carrier.valid, carrier.ready),
        resultType = Some(KernelTypeDescriptor("Bool")),
        operation = Some(FsmTimingRuntime.TransferOperation)
      )

  private def fsmTransferTuple(valid: Expr[Bool], ready: Expr[Bool]): Expr[?] =
    val reference = Option(
      declarationIds.get(ready)
    ).getOrElse(FsmDefinitionRuntime.fail("transfer ready requires its actual Stream wire"))
    if reference.module != currentModule.handle then
      FsmDefinitionRuntime.fail("transfer carrier belongs to another Module")
    val attributes = records(reference.module).declarations(reference.index).attributes
    val payload = attributes.collectFirst { case ("stream_payload", value: Expr[?]) => value }
    val actualValid = attributes.collectFirst { case ("stream_valid", value: Expr[?]) => value }
    if payload.isEmpty || !actualValid.exists(value =>
        valueReference(value) == valueReference(valid)
      ) || !attributes.exists(_._1 == "stream_ready_default")
    then
      FsmDefinitionRuntime.fail(
        "transfer predicate requires its exact existing Stream valid/ready pair"
      )
    payload.get

  def acceptStream[A <: Data](stream: Stream[A], ready: Expr[Bool]): Unit =
    streamTransaction("Stream.accept"):
      ownedStreamValue(stream.payload, "Stream payload")
      ownedStreamValue(stream.valid, "Stream valid", bool = true)
      ownedStreamValue(stream.readyWire, "Stream ready", bool = true)
      ownedStreamValue(ready, "Stream sink readiness", bool = true)
      val reference = declarationIds.get(stream.readyWire)
      if !records(reference.module).declarations(reference.index).attributes.exists(
          _._1 == "stream_ready_default"
        )
      then crossingFailure("Stream ready requires its actual owned wire")
      assignSignalSelection(stream.readyWire, ready)

  /** Finalize forward ready references through the ordinary physical assignment owner. There is no
    * separate connectivity graph: duplicate drivers reject and unattached sinks receive false.
    */
  private def finalizeStreamReady(): Unit =
    if boundaryWorkActive then
      closureCharge(records.size.toLong + operations.size.toLong)
      records.valuesIterator.foreach: module =>
        closureCharge(module.declarations.size.toLong)
        module.declarations.foreach(value => closureCharge(value.attributes.size.toLong))
    val readyDeclarations = records.valuesIterator.flatMap(_.declarations).filter(
      _.attributes.exists(_._1 == "stream_ready_default")
    ).map(value => value.reference -> value).toMap
    val counts = mutable.HashMap.empty[DeclarationRef, Int]
    operations.iterator.filter(operation =>
      Set("assignment", "value-connect")(operation.kind) && operation.values.size == 2
    ).foreach: operation =>
      operation.values.head match
        case value: AnyRef =>
          Option(declarationIds.get(value)).filter(readyDeclarations.contains).foreach: reference =>
            val count = counts.getOrElse(reference, 0) + 1
            if count > 1 then crossingFailure("Stream ready has more than one owner")
            counts.update(reference, count)
        case _ => ()
    closureCharge(readyDeclarations.size.toLong * readyDeclarations.size.toLong * 2L)
    readyDeclarations.toVector.sortBy((reference, _) =>
      (reference.module, reference.index)
    ).foreach: (reference, declaration) =>
      if !counts.contains(reference) then
        closureCharge(1L + declaration.attributes.size.toLong)
        val default = declaration.attributes.collectFirst:
          case ("stream_ready_default", value: Expr[?]) => value
        .get
        val ordinal = nextDigitalEffectOrdinal.getOrElse(reference.module, 0)
        nextDigitalEffectOrdinal.update(reference.module, ordinal + 1)
        val values = Vector(declaration.value, default)
        operations += Operation(reference.module, "assignment", values, sourceOrdinal = ordinal)
        semanticOrigin.captureOperation(reference.module, "assignment", values)

  def asyncFifo[A <: Data](input: Stream[A], to: ClockDomain, depth: Int): Stream[A] =
    streamTransaction("Cdc.fifo"):
      val owner = currentModule.handle
      if domainRef(to).module != owner || currentDomain.exists(domainRef(_).module != owner) then
        crossingFailure("Cdc.fifo destination and lexical domain must belong to the active Module")
      if !ExecutableCrossingContract.validDepth(depth) then
        crossingFailure("Cdc.fifo depth must be a power of two from 2 through 256")
      ownedStreamValue(input.payload, "Cdc.fifo payload")
      ownedStreamValue(input.valid, "Cdc.fifo valid", bool = true)
      val dataType = CandidateRuntime.expressionDataType(input.payload).get
      val bits = shapeTypeFootprint(
        dataType,
        owner,
        cardinality = true,
        storageContext = true,
        bitPatternOnly = true
      ).getOrElse(crossingFailure("Cdc.fifo requires a finite digital bit pattern"))
      val stateBits = ExecutableCrossingContract.fifoStateBits(bits, depth)
      if crossingStateBits + stateBits > ExecutableCrossingContract.MaximumStateBits then
        crossingFailure("Cdc.fifo generated state exceeds its expansion budget")
      val ready = newStreamReady()
      val operands = Vector(input.payload, input.valid, ready)
      val payload = new KernelExpr[A](
        operands,
        resultType = Some(CandidateRuntime.typeDescriptor(dataType)),
        operation = Some("cdc_fifo")
      )
      val valid = new KernelExpr[Bool](
        operands,
        resultType = Some(KernelTypeDescriptor("Bool")),
        operation = Some("cdc_fifo")
      )
      val sourceReady = new KernelExpr[Bool](
        operands,
        resultType = Some(KernelTypeDescriptor("Bool")),
        operation = Some("cdc_fifo")
      )
      val record = ExecutableCrossing(
        input.payload,
        to,
        2,
        "fifo",
        Some(input.valid),
        Some(ready),
        Some(depth)
      )
      record.results = Vector(payload -> "payload", valid -> "valid", sourceReady -> "ready")
        .map((value, role) => captureExpression(value).get -> role)
      record.results.foreach((reference, _) => executableCrossings.update(reference, record))
      crossingStateBits += stateBits
      acceptStream(input, sourceReady)
      registerStream(payload, valid, ready)

  def levelSync(bit: Expr[Bool], to: ClockDomain, stages: Int): Expr[Bool] =
    executableCrossing(bit, to, stages, "sync")

  def executableCrossing[A <: Data](
      input: Expr[A],
      to: ClockDomain,
      stages: Int,
      kind: String
  ): Expr[A] =
    try
      closeBehavioralChain()
      val label = "Cdc." + kind
      if activeValueRegion.nonEmpty || activeGeneratedRegion.nonEmpty ||
        analogStack.nonEmpty || analogSemanticContext.nonEmpty
      then crossingFailure(s"$label requires module-level construction; callbacks are unsupported")
      val owner = currentModule.handle
      if domainRef(to).module != owner || currentDomain.exists(domainRef(_).module != owner) then
        crossingFailure(
          s"$label destination and any active lexical domain must belong to the active Module"
        )
      if !ExecutableCrossingContract.validStages(kind, stages) then
        crossingFailure(s"$label stages must be between 2 and 32 (pulse and handshake require 2)")
      val reference = valueReference(input)
      val inputOwner = reference match
        case value: DeclarationRef => value.module
        case value: ExpressionRef => value.module
      val dataType = CandidateRuntime.expressionDataType(input).getOrElse(
        crossingFailure(s"$label input requires an actual owned digital value")
      )
      val descriptor = CandidateRuntime.typeDescriptor(dataType)
      if inputOwner != owner ||
        (!ExecutableCrossingContract.bitPatternPayload(kind) && descriptor != KernelTypeDescriptor(
          "Bool"
        ))
      then crossingFailure(s"$label input requires an actual owned Bool value")
      val bits = if ExecutableCrossingContract.bitPatternPayload(kind) then
        shapeTypeFootprint(
          dataType,
          owner,
          cardinality = true,
          storageContext = true,
          bitPatternOnly = true
        ).getOrElse(crossingFailure(s"$label requires a finite digital bit pattern"))
      else BigInt(1)
      val stateBits = ExecutableCrossingContract.stateBits(kind, bits, stages)
      if crossingStateBits + stateBits > ExecutableCrossingContract.MaximumStateBits then
        crossingFailure(s"$label generated state exceeds its expansion budget")
      val result = new KernelExpr[A](
        Vector(input),
        resultType = Some(descriptor),
        operation = Some("cdc_" + kind)
      )
      val captured = captureExpression(result).get
      executableCrossings.update(captured, ExecutableCrossing(input, to, stages, kind))
      crossingStateBits += stateBits
      result
    catch
      case failure: Throwable =>
        poisonConstructor(failure, "executable crossing")
        scala.util.Failure[Nothing](failure).get

  private def executableCrossingSnapshot(expression: KernelExpr[?])
      : Option[KernelExecutableCrossingSnapshot] = publicationProbe:
    if expression.operation.exists(ExecutableCrossingContract.Operations) then
      val record = executableCrossings.getOrElse(
        expressionIds.get(expression),
        crossingFailure("crossing requires its actual owned construction record")
      )
      val source = record.sourceEndpoint.getOrElse(
        crossingFailure("crossing source lost its exact local endpoint identity")
      )
      Some(KernelExecutableCrossingSnapshot(
        domainPath(source),
        domainPath(domainRef(record.destination)),
        record.stages,
        record.kind,
        ExecutableCrossingContract.contract(record.kind),
        ExecutableCrossingContract.obligations(record.kind),
        record.depth,
        Option.when(
          record.kind == "fifo"
        )(expressionPath(record.identity(expressionIds.get(expression)))),
        record.role(expressionIds.get(expression))
      ))
    else None

  private final case class ExecutableWaiver(
      input: Expr[?],
      destination: ClockDomain,
      id: String,
      reason: String,
      relation: ClockRelationshipModel.RelationIntent,
      var sourceEndpoint: Option[DomainRef] = None
  )
  private val executableWaivers = mutable.LinkedHashMap.empty[ExpressionRef, ExecutableWaiver]
  private val waiverIds = mutable.HashSet.empty[(Long, String)]

  def waive[A <: Data](input: Expr[A], to: ClockDomain, waiver: CdcWaiver): Expr[A] =
    streamTransaction("Cdc.waive"):
      val owner = currentModule.handle
      if domainRef(to).module != owner || currentDomain.exists(domainRef(_).module != owner) then
        crossingFailure("Cdc.waive destination and lexical domain must belong to the active Module")
      if waiver == null || waiver.relation == null then
        crossingFailure("Cdc.waive requires an actual typed waiver and relation")
      ExecutableWaiverContract.validateText(waiver.id, waiver.reason, crossingFailure)
      val relation = ClockRelationshipModel.intent(waiver.relation)
      ClockRelationshipModel.verifyBoundaryIntentFields(relation, crossingFailure)
      if !waiverIds.add(owner -> waiver.id) then
        crossingFailure("Cdc.waive id must be unique among authored waivers in one Module")
      ownedStreamValue(input, "Cdc.waive payload")
      val dataType = CandidateRuntime.expressionDataType(input).get
      val bits = shapeTypeFootprint(
        dataType,
        owner,
        cardinality = true,
        storageContext = true,
        bitPatternOnly = true,
        fixedBitPattern = true
      ).getOrElse(crossingFailure("consumer requires a fixed whole digital bit pattern"))
      if bits > ExecutableWaiverContract.MaximumPayloadBits then
        crossingFailure("Cdc.waive fixed whole payload must contain 1 through 4096 bits")
      val result = new KernelExpr[A](
        Vector(input),
        resultType = Some(CandidateRuntime.typeDescriptor(dataType)),
        operation = Some(ExecutableWaiverContract.Operation)
      )
      val captured = captureExpression(result).get
      executableWaivers.update(
        captured,
        ExecutableWaiver(input, to, waiver.id, waiver.reason, relation)
      )
      result

  private def executableWaiverSnapshot(expression: KernelExpr[?])
      : Option[KernelExecutableWaiverSnapshot] = publicationProbe:
    Option.when(expression.operation.contains(ExecutableWaiverContract.Operation)):
      val record = executableWaivers.getOrElse(
        expressionIds.get(expression),
        crossingFailure("waiver requires its actual owned construction record")
      )
      val source = record.sourceEndpoint.getOrElse(
        crossingFailure("waiver source lost its exact local endpoint identity")
      )
      KernelExecutableWaiverSnapshot(
        domainPath(source),
        domainPath(domainRef(record.destination)),
        record.id,
        record.reason,
        record.relation,
        ExecutableWaiverContract.Contract,
        ExecutableWaiverContract.Obligations
      )

  private final case class ClockRecord(
      kind: String,
      parents: Vector[ClockDomain],
      controls: Vector[Expr[Bool]],
      var destination: Option[ClockDomain] = None,
      var controlKinds: Vector[String] = Vector.empty,
      var controlDomains: Vector[Option[String]] = Vector.empty
  )
  private val clockRecords = mutable.LinkedHashMap.empty[ExpressionRef, ClockRecord]
  private val rawClockResets = mutable.Map.empty[(DeclarationRef, Long), Expr[Reset]]
  private var clockState = 0L
  private val provedClockIdentities = mutable.HashSet.empty[ExpressionRef]
  private var clockProofWork = 0L

  private def clockFailure(message: String): Nothing =
    behavioralFailure(message, "NODAL-CLOCK-057-001")

  private def clockTransaction[A](body: => A): A =
    try
      closeBehavioralChain()
      if activeValueRegion.nonEmpty || activeGeneratedRegion.nonEmpty ||
        analogStack.nonEmpty || analogSemanticContext.nonEmpty
      then clockFailure("owned clock structures require direct module-level construction")
      body
    catch
      case error: Throwable =>
        poisonConstructor(error, "owned clock construction")
        throw error

  /** A derived producer is distinct from every physical input and from every other producer. */
  private def clockIdentity(value: Expr[Clock], owner: Long, depth: Int = 0)
      : Either[(DeclarationRef, Long), ExpressionRef] =
    publicationCharge(1L)
    if depth > 128 then clockFailure("clock graph exceeds depth budget")
    if !CandidateRuntime.expressionDataType(value).exists(dataType =>
        CandidateRuntime.typeDescriptor(dataType) == KernelTypeDescriptor("Clock")
      )
    then
      clockFailure("clock proof requires exact scalar Clock capability, never a cast control")
    val reference = assignmentReference(value)
    reference match
      case expression: ExpressionRef if clockRecords.contains(expression) =>
        if expression.module != owner || expressionRegions.contains(expression) then
          clockFailure("owned clock producer is foreign or regional")
        if !provedClockIdentities(expression) then
          clockProofWork += 1
          if clockProofWork > 524288 then clockFailure("clock graph exceeds work budget")
          val record = clockRecords(expression)
          record.parents.foreach(parent =>
            clockIdentity(
              parent.clock.getOrElse(clockFailure("parent lacks actual Clock")),
              owner,
              depth + 1
            )
          )
          provedClockIdentities += expression
        Right(expression)
      case _ =>
        val physical = physicalControlIdentity(value)
        if physical._1.module != owner then clockFailure("clock boundary belongs to another module")
        Left(physical)

  private def clockResetSafe(value: Expr[Reset], receiving: ClockDomain): Boolean =
    boundDomainCharge()
    val memo = new IdentityHashMap[Expr[Reset], java.lang.Boolean]()
    val owner = domainRef(receiving).module
    val identity = clockIdentity(receiving.clock.get, owner)
    val edge = inheritedClockEdge(receiving)
    val synchronous = receiving.resetPolicy.contains(ResetPolicy.Sync) ||
      (receiving.resetPolicy.isEmpty && hasSynchronousResetAssertion(value))
    def sameClock(other: ClockDomain): Boolean =
      clockIdentity(
        other.clock.getOrElse(clockFailure("reset destination lacks Clock")),
        owner
      ) == identity &&
        inheritedClockEdge(other) == edge
    def visit(input: Expr[Reset], depth: Int): Boolean =
      boundDomainCharge()
      clockProofWork += 1
      if depth > 128 || clockProofWork > 524288 then
        clockFailure("parent reset proof exceeds budget")
      val actual = actualReset(input)
      Option(memo.get(actual)).map(_.booleanValue).getOrElse:
        val record = resetRecord(actual)
        val result = record.kind match
          case "sync" => record.destination.exists(sameClock)
          case "combine" => record.inputs.forall(visit(_, depth + 1))
          case "boundary" =>
            (record.release == "external" || record.release == "sync" && synchronous) &&
            record.destination.exists(sameClock)
          case _ => false
        memo.put(actual, java.lang.Boolean.valueOf(result))
        result
    visit(value, 0)

  /** Reuse raw physical assertion, never combine independently released reset chains. */
  private def rawClockAssertion(value: Expr[Reset]): Expr[Reset] =
    val memo = new IdentityHashMap[Expr[Reset], Expr[Reset]]()
    var work = 0
    def visit(input: Expr[Reset], depth: Int): Expr[Reset] =
      work += 1
      if depth > 128 || work > 524288 then
        clockFailure("clock reset assertion graph exceeds budget")
      val actual = actualReset(input)
      Option(memo.get(actual)).getOrElse:
        val record = resetRecord(actual)
        val result = record.kind match
          case "boundary" =>
            val identity = physicalControlIdentity(record.inputs.head)
            rawClockResets.get(identity) match
              case Some(previous) =>
                if resetRecord(previous).polarity != record.polarity then
                  clockFailure("clock reset roots have contradictory polarities")
                previous
              case None =>
                val raw = if record.release == "raw" then actual
                else makeReset(record.copy(release = "raw", destination = None), "reset_boundary")
                rawClockResets(identity) = raw
                raw
          case "sync" => visit(record.inputs.head, depth + 1)
          case "combine" => resetCombine(record.inputs.map(visit(_, depth + 1)))
          case _ => clockFailure("clock requires actual reset assertion roots")
        memo.put(actual, result)
        result
    visit(value, 0)

  private def ownedClock(
      kind: String,
      parents: Vector[ClockDomain],
      controls: Vector[Expr[Bool]],
      name: String
  ): ClockDomain = clockTransaction:
    val owner = currentModule.handle
    if parents.size != (if kind == "gate" then 1 else 2) ||
      parents.exists(domain => domainRef(domain).module != owner || domain.clock.isEmpty)
    then clockFailure("owned clock requires actual local parent domains (mux exactly two)")
    controls.foreach(ownedStreamValue(_, "clock control", bool = true))
    val clocks = parents.map(_.clock.get)
    val identities = clocks.map(clockIdentity(_, owner))
    if kind == "mux" && identities.distinct.size != 2 then
      clockFailure("glitchless mux requires two distinct actual clock parents")
    val edges = parents.map(inheritedClockEdge(_))
    if edges.exists(_.isEmpty) || edges.distinct.size != 1 then
      clockFailure("owned clocks require matching actual parent edges")
    val resets = parents.map(parent => Option(effectiveResets.get(parent)))
    parents.zip(resets).foreach: (parent, reset) =>
      if reset.exists(value => !clockResetSafe(value, parent)) then
        clockFailure("owned clock parent requires safe release for its actual clock and edge")
    if kind == "mux" && resets.exists(_.isEmpty) then
      clockFailure("glitchless mux requires common nonempty reset roots")
    if kind == "mux" then
      val lineage = new ResetLineageQuery
      val roots = resets.map(reset => lineage(reset.get).view.mapValues(_._1).toMap)
      if roots.head.isEmpty || roots.distinct.size != 1 then
        clockFailure("glitchless mux requires equal actual reset roots and polarities")
    val state = if kind == "gate" then 1L else 22L
    if clockState + resetState + state > 262144 then
      clockFailure("owned clock generated state exceeds expansion budget")
    clockState += state
    val assertion = resets.head.map(rawClockAssertion)
    val operands: Vector[Any] = clocks ++ controls ++ Option.when(kind == "mux")(assertion.get)
    val clock = new KernelExpr[Clock](
      operands,
      resultType = Some(KernelTypeDescriptor("Clock")),
      operation = Some("clock_" + kind)
    )
    val reference = captureExpression(clock).get
    val record = ClockRecord(kind, parents, controls)
    clockRecords(reference) = record
    val release = assertion.map(resetSync(_, None, 2))
    val result = new ClockDomain(
      name,
      release.getOrElse(parents.head.reset),
      KernelDomainKind.Generated,
      edge = edges.head,
      resetPolicy = Option.when(release.isEmpty)(ResetPolicy.None),
      resetPolarity = release.map(resetRecord(_).polarity).orElse(parents.head.resetPolarity),
      relation = Some(ClockRelation.Unknown),
      clock = Some(clock),
      parent = Some(parents.head)
    )
    record.destination = Some(result)
    result

  def generatedDomain(
      name: String,
      clock: Expr[Clock],
      from: ClockDomain,
      relation: ClockRelation,
      reset: Expr[Reset]
  ): ClockDomain = clockTransaction:
    if !stimulusOnly then
      val owner = currentModule.handle
      if domainRef(from).module != owner || from.clock.isEmpty then
        behavioralFailure(
          "relationship parent requires one actual owned domain",
          ClockRelationshipModel.Code
        )
      val source = clockIdentity(from.clock.get, owner)
      val destination = clockIdentity(clock, owner)
      relation match
        case ClockRelation.Same
            if source != destination && (source.isRight || destination.isRight) =>
          behavioralFailure(
            "distinct owned gate/mux producers cannot become Same",
            ClockRelationshipModel.Code
          )
        case ClockRelation.Ratio(multiply, divide, phase) =>
          if multiply <= 0 || divide <= 0 || !java.lang.Double.isFinite(phase.degrees) then
            behavioralFailure(
              "ratio requires positive int32 factors and finite phase",
              ClockRelationshipModel.Code
            )
        case _ => ()
    new ClockDomain(
      name,
      reset,
      KernelDomainKind.Generated,
      relation = Some(relation),
      clock = Some(clock),
      parent = Some(from),
      executableRelationship = !stimulusOnly
    )

  private def relationshipSnapshot(domain: DomainRecord): Option[KernelClockRelationshipSnapshot] =
    publicationCharge(1L)
    Option.when(domain.domain.executableRelationship):
      val actual = domain.domain
      val parent = actual.parent.get
      val owner = domain.reference.module
      val structural =
        clockIdentity(parent.clock.get, owner) == clockIdentity(actual.clock.get, owner)
      val intent = ClockRelationshipModel.intent(actual.relation.get)
      val kind = intent.kind
      val proof = ClockRelationshipModel.proof(kind, structural)
      KernelClockRelationshipSnapshot(
        domainPath(domain.reference) + ".relationship",
        domainPath(domainRef(parent)),
        domainPath(domain.reference),
        domainSourcePath(parent.clock.get),
        domainSourcePath(actual.clock.get),
        kind,
        ClockRelationshipModel.Contract,
        proof,
        ClockRelationshipModel.obligations(kind, proof),
        intent.multiply,
        intent.divide,
        intent.phase,
        intent.phaseKnown
      )

  private def relationshipIndex(): ClockRelationshipModel.Index =
    publicationCharge(1L)
    val actual = if stimulusOnly then Vector.empty
    else
      publicationElements(records.valuesIterator).flatMap(module =>
        publicationElements(module.domains)
      ).filter(value =>
        value.kind != KernelDomainKind.Required && value.domain.clock.nonEmpty
      ).toVector
    publicationCharge(actual.size.toLong)
    val endpoints = actual.map: record =>
      publicationCharge(1L)
      val domain = record.domain
      val identity = clockIdentity(domain.clock.get, record.reference.module)
      val physical = identity match
        case Left((reference, offset)) =>
          val path = declarationPath(reference)
          val suffix = offset.toString
          publicationCharge(10L + path.length.toLong + suffix.length.toLong)
          "physical:" + path + ":" + suffix
        case Right(reference) =>
          val path = expressionPath(reference)
          publicationCharge(8L + path.length.toLong)
          "derived:" + path
      ClockRelationshipModel.Endpoint(
        domainPath(record.reference),
        modulePath(record.reference.module),
        domainSourcePath(domain.clock.get),
        physical,
        identity.isLeft,
        inheritedClockEdge(domain).map(clockEdgeName).getOrElse("unknown"),
        domain.frequency.map(value => java.lang.Double.toString(value.hertz))
      )
    new ClockRelationshipModel.Index(
      endpoints,
      actual.flatMap(record => publicationProbe(relationshipSnapshot(record))),
      (message, path) => fail(ClockRelationshipModel.Code, message, Some(path)),
      publicationCharge
    )

  def clockGate(domain: ClockDomain, enable: Expr[Bool], testEnable: Expr[Bool], name: String)
      : ClockDomain =
    if stimulusOnly then
      CandidateRuntime.statement(enable, testEnable)
      new ClockDomain(name, domain.reset, KernelDomainKind.Generated, parent = Some(domain))
    else ownedClock("gate", Vector(domain), Vector(enable, testEnable), name)

  def clockMux(select: Expr[Bool], domains: Vector[ClockDomain], name: String): ClockDomain =
    if stimulusOnly then
      CandidateRuntime.statement(select, domains)
      new ClockDomain(name, CandidateRuntime.expr(domains), KernelDomainKind.Generated)
    else ownedClock("mux", domains, Vector(select), name)

  private def clockSnapshot(expression: KernelExpr[?]): Option[KernelClockSnapshot] =
    publicationProbe:
      clockRecords.get(expressionIds.get(expression)).map: record =>
        if record.controlKinds.size != record.controls.size then
          clockFailure("clock control provenance was not validated")
        KernelClockSnapshot(
          record.kind,
          publicationVector(record.parents).map(value => domainPath(domainRef(value))),
          domainPath(domainRef(record.destination.get)),
          clockEdgeName(inheritedClockEdge(record.parents.head).get),
          record.controlKinds,
          record.controlDomains,
          OwnedClockContract.contract(record.kind),
          OwnedClockContract.obligations(record.kind)
        )

  private final case class ResetRecord(
      kind: String,
      inputs: Vector[Expr[Reset]],
      polarity: ResetPolarity,
      release: String,
      var destination: Option[ClockDomain],
      stages: Int
  )
  private val resetRecords = new IdentityHashMap[Expr[Reset], ResetRecord]()
  private val resetBoundaries = new IdentityHashMap[Expr[Reset], Expr[Reset]]()
  private val physicalResetBoundaries = mutable.Map.empty[(DeclarationRef, Long), Expr[Reset]]

  private def physicalControlIdentity(value: Expr[?]): (DeclarationRef, Long) =
    publicationCharge(1L)
    import StructuralShapeCoordinates.{Coordinate, Projection}
    var reference = assignmentReference(value)
    var projections = Vector.empty[Projection]
    var depth = 0
    while reference.isInstanceOf[ExpressionRef] do
      depth += 1
      if depth > 128 then resetFailure("physical control projection exceeds depth budget")
      val expression = reference.asInstanceOf[ExpressionRef]
      if expressionRegions.contains(expression) then
        resetFailure("physical control projection must be module-owned")
      shapeIndices.get(expression) match
        case Some(index) =>
          if index.generatedScope.nonEmpty || index.conservative.nonEmpty then
            resetFailure("physical control projection has invalid scope")
          projections = Projection.Index(
            index.indices.map(generatedBound),
            index.resultDimensions.map(renderShapeDimension(_, expression.module))
          ) +: projections
          reference = index.input
        case None =>
          val view = shapeViews.getOrElse(
            expression,
            resetFailure("physical control requires an owned input or static projection")
          )
          if view.generatedScope.nonEmpty then
            resetFailure("physical control projection has invalid scope")
          projections = Projection.View(
            view.dimensions.map(renderShapeDimension(_, expression.module)),
            view.slice.map(slice =>
              KernelShapeSliceSnapshot(
                slice.axis,
                generatedBound(slice.lower),
                generatedBound(slice.upperExclusive)
              )
            )
          ) +: projections
          reference = view.input
    val root = reference.asInstanceOf[DeclarationRef]
    val declaration = records(root.module).declarations(root.index)
    val finalType = CandidateRuntime.expressionDataType(value).map(CandidateRuntime.typeDescriptor)
    if !finalType.exists(t => Set("Clock", "Reset")(t.kind) && t.arguments.isEmpty) ||
      records(root.module).generatedRegions.exists(_.declarations.contains(root)) ||
      projections.size > 128
    then resetFailure("physical control must be an unscoped scalar Clock or Reset")
    if declaration.kind != KernelSignalKind.Input then
      resetFailure("physical control root must be an exact owned input")
    val descriptor = CandidateRuntime.typeDescriptor(declaration.dataType.get)
    val dimensions = if descriptor.kind == "Vec" then
      descriptor.arguments(1).asInstanceOf[Seq[Any]].toVector.map(renderShapeDimension(
        _,
        root.module
      ))
    else Vector.empty
    val tokens = dimensions ++ projections.flatMap {
      case Projection.Index(indices, result) => indices ++ result
      case Projection.View(result, slice) =>
        result ++ slice.toVector.flatMap(v => Vector(v.lower, v.upperExclusive))
    }
    if tokens.exists(_.toLongOption.isEmpty) then
      resetFailure("physical control execution requires fixed literal shape coordinates")
    // The admitted publication profile proves direct scalar controls: both vectors are empty.
    // This debit covers the existing bounded empty-coordinate helper dispatch, not projected work.
    publicationCharge(1L)
    val region = StructuralShapeCoordinates.scalarRegion(dimensions, projections)
    (region.cardinality, region.rootOrdinal) match
      case (Coordinate.Literal(1), Coordinate.Literal(offset))
          if offset >= 0 && BigInt(offset) < dimensions.foldLeft(BigInt(1))((n, d) =>
            n * BigInt(d)
          ) => (root, offset)
      case _ => resetFailure("physical control projection must select one exact fixed scalar")

  private val effectiveResets = new IdentityHashMap[ClockDomain, Expr[Reset]]()
  private var resetState = 0L
  private def resetFailure(message: String): Nothing =
    behavioralFailure(message, "NODAL-RDC-057-001")

  private def resetScope(): Unit =
    closeBehavioralChain()
    if activeValueRegion.nonEmpty || activeGeneratedRegion.nonEmpty ||
      analogStack.nonEmpty || analogSemanticContext.nonEmpty
    then resetFailure("reset structures require direct module-level construction")

  private def actualReset(value: Expr[Reset]): Expr[Reset] =
    publicationCharge(1L)
    if resetRecords.containsKey(value) then value
    else
      Option(
        resetBoundaries.get(value)
      ).orElse(physicalResetBoundaries.get(physicalControlIdentity(value))).getOrElse(value)

  private def resetRecord(value: Expr[Reset]): ResetRecord =
    publicationCharge(1L)
    resetRecords.get(actualReset(value)) match
      case null =>
        resetFailure("reset requires an actual owned producer or declared reset boundary")
      case result => result

  private def hasSynchronousResetAssertion(value: Expr[Reset]): Boolean =
    val memo = new IdentityHashMap[Expr[Reset], java.lang.Boolean]()
    var work = 0
    def visit(input: Expr[Reset], depth: Int): Boolean =
      work += 1
      if depth > 128 || work > 524288 then resetFailure("reset ancestry budget exceeded")
      val actual = actualReset(input)
      Option(memo.get(actual)).map(_.booleanValue).getOrElse:
        val record = resetRecord(actual)
        val result = record.kind match
          case "boundary" => record.release == "sync"
          case "combine" => record.inputs.forall(visit(_, depth + 1))
          case _ => false
        memo.put(actual, java.lang.Boolean.valueOf(result))
        result
    visit(value, 0)

  private def makeReset(record: ResetRecord, operation: String): Expr[Reset] =
    val result = new KernelExpr[Reset](
      record.inputs ++ Option.when(operation == "rdc_sync")(record.stages).toVector,
      resultType = Some(KernelTypeDescriptor("Reset")),
      operation = Some(operation)
    )
    val _ = captureExpression(result)
    resetRecords.put(result, record)
    result

  def resetSync(reset: Expr[Reset], to: Option[ClockDomain], stages: Int): Expr[Reset] =
    if stimulusOnly then
      val result = new KernelExpr[Reset](
        Vector(reset) ++ to.toVector ++ Vector(stages),
        resultType = Some(KernelTypeDescriptor("Reset")),
        operation = Some("rdc_sync")
      )
      val _ = captureExpression(result)
      result
    else
      try
        resetScope()
        if stages < 2 || stages > 32 then
          resetFailure("reset synchronizer stages must be 2 through 32")
        if resetState + clockState + stages > 262144 then
          resetFailure("reset synchronization expansion budget exceeded")
        val input = actualReset(reset)
        val producer = resetRecord(input)
        val owner = valueReference(input) match
          case value: DeclarationRef => value.module
          case value: ExpressionRef => value.module
        if owner != currentModule.handle || to.exists(domainRef(_).module != owner) then
          resetFailure("reset synchronizer endpoints must belong to the active module")
        resetState += stages
        makeReset(
          ResetRecord("sync", Vector(input), producer.polarity, "sync", to, stages),
          "rdc_sync"
        )
      catch
        case error: Throwable =>
          poisonConstructor(error, "reset synchronization")
          throw error

  private final class ResetLineageQuery:
    private val memo =
      new IdentityHashMap[Expr[Reset], Map[(DeclarationRef, Long), (ResetPolarity, Expr[Reset])]]()
    def apply(
        value: Expr[Reset],
        depth: Int = 0
    ): Map[(DeclarationRef, Long), (ResetPolarity, Expr[Reset])] =
      if depth > 128 then resetFailure("reset ancestry budget exceeded")
      Option(memo.get(value)).getOrElse:
        val result = visit(value, depth)
        memo.put(value, result)
        result
    private var work = 0L
    private def visit(
        value: Expr[Reset],
        depth: Int
    ): Map[(DeclarationRef, Long), (ResetPolarity, Expr[Reset])] =
      work += 1
      if depth > 128 || work > 524288 then resetFailure("reset ancestry budget exceeded")
      val node = resetRecord(value)
      if node.kind == "boundary" then
        Map(physicalControlIdentity(node.inputs.head) -> (node.polarity, value))
      else
        val result =
          scala.collection.mutable.Map.empty[(DeclarationRef, Long), (ResetPolarity, Expr[Reset])]
        node.inputs.foreach(input =>
          apply(input, depth + 1).foreach { (root, fact) =>
            work += 1
            if work > 524288 then resetFailure("reset ancestry budget exceeded")
            result.get(root).foreach(previous =>
              if previous != fact then resetFailure("independent reset releases reconverge")
            )
            result(root) = fact
          }
        )
        if node.kind == "sync" then
          result.iterator.map((root, fact) => root -> (fact._1, value)).toMap
        else result.toMap

  def resetCombine(resets: Vector[Expr[Reset]]): Expr[Reset] =
    try
      resetScope()
      if resets.isEmpty || resets.size > 32 then
        resetFailure("reset composition requires 1 through 32 inputs")
      val inputs = resets.map(actualReset)
      inputs.foreach: value =>
        val owner = valueReference(value) match
          case v: DeclarationRef => v.module
          case v: ExpressionRef => v.module
        if owner != currentModule.handle then
          resetFailure("composition requires owned reset producers")
      val lineage = new ResetLineageQuery
      val seen =
        scala.collection.mutable.Map.empty[(DeclarationRef, Long), (ResetPolarity, Expr[Reset])]
      inputs.foreach(input =>
        lineage(input, 0).foreach { (root, fact) =>
          seen.get(root).foreach(previous =>
            if previous != fact then resetFailure("independent reset releases reconverge")
          )
          seen(root) = fact
        }
      )
      makeReset(
        ResetRecord("combine", inputs, ResetPolarity.ActiveHigh, "raw", None, 0),
        "reset_combine"
      )
    catch
      case error: Throwable =>
        poisonConstructor(error, "reset composition")
        throw error

  private def installDomainReset(domain: ClockDomain, kind: KernelDomainKind): Unit =
    if !stimulusOnly && kind != KernelDomainKind.Required && domain.clock.nonEmpty then
      domain.resetPolicy match
        case Some(ResetPolicy.None) => ()
        case Some(policy) =>
          if !resetRecords.containsKey(domain.reset) && physicalControlIdentity(
              domain.reset
            )._1.module != domainRef(domain).module
          then
            resetFailure("physical reset root belongs to another module")
          val polarity = domain.resetPolarity.getOrElse(resetFailure("reset polarity is required"))
          val known = Option(resetRecords.get(domain.reset)).orElse(
            Option(resetRecords.get(actualReset(domain.reset)))
          )
          known.foreach(record =>
            if record.polarity != polarity then
              resetFailure("physical reset has contradictory polarities")
          )
          val input = if known.nonEmpty then actualReset(domain.reset)
          else
            val release = policy match
              case ResetPolicy.Sync => "sync"
              case ResetPolicy.AsyncExternalRelease => "external"
              case _ => "raw"
            val boundary = makeReset(
              ResetRecord(
                "boundary",
                Vector(domain.reset),
                polarity,
                release,
                Option.when(release != "raw")(domain),
                0
              ),
              "reset_boundary"
            )
            resetBoundaries.put(domain.reset, boundary)
            physicalResetBoundaries(physicalControlIdentity(domain.reset)) = boundary
            boundary
          val result = policy match
            case ResetPolicy.AsyncAssertSyncRelease(stages) =>
              resetSync(input, Some(domain), stages)
            case _ => input
          effectiveResets.put(domain, result)
        case None =>
          val reset = actualReset(domain.reset)
          def bind(value: Expr[Reset], depth: Int): Unit =
            if depth > 128 then resetFailure("reset producer ancestry exceeds depth budget")
            val node = resetRecord(value)
            if node.kind == "sync" && node.destination.isEmpty then node.destination = Some(domain)
            if node.kind != "boundary" then node.inputs.foreach(bind(_, depth + 1))
          bind(reset, 0)
          effectiveResets.put(domain, reset)

  private def resetSnapshot(expression: KernelExpr[?]): Option[KernelResetSnapshot] =
    publicationProbe:
      Option(resetRecords.get(expression.asInstanceOf[Expr[Reset]])).map: record =>
        val inputs = publicationVector(record.inputs).map(domainSourcePath)
        val clock = if record.kind == "sync" then
          Vector(record.destination.flatMap(_.clock).map(domainSourcePath).getOrElse(
            resetFailure("construction-form reset synchronizer has no receiving domain")
          ))
        else Vector.empty
        KernelResetSnapshot(
          record.kind,
          resetPolarityName(record.polarity),
          record.release,
          record.destination.map(value => domainPath(domainRef(value))),
          record.stages,
          inputs ++ clock
        )

  private val userFunctions = new AnalogUserFunctionRuntime.Registry
  private var nextModule: Long = 0L
  private val moduleIds = new IdentityHashMap[AnyRef, java.lang.Long]()
  private val domainIds = new IdentityHashMap[AnyRef, DomainRef]()
  private val declarationIds = new IdentityHashMap[AnyRef, DeclarationRef]()
  private val expressionIds = new IdentityHashMap[AnyRef, ExpressionRef]()
  private val expressionRegions = mutable.HashMap.empty[ExpressionRef, DigitalValueRegionRecord]
  private val collectionRegions =
    mutable.LinkedHashMap.empty[ExpressionRef, DigitalValueRegionRecord]
  private val collectionArguments =
    mutable.HashMap.empty[ExpressionRef, (DigitalValueRegionRecord, Int)]
  private val collectionStack = mutable.ArrayBuffer.empty[DigitalValueRegionRecord]
  private val loopRegions = mutable.LinkedHashMap.empty[ExpressionRef, DigitalValueRegionRecord]
  private val staticPredicates = mutable.LinkedHashMap.empty[ExpressionRef, StaticPredicate]
  private val structuralIfs = mutable.LinkedHashMap.empty[ExpressionRef, StructuralIfRecord]
  private val structuralArms = mutable.LinkedHashMap.empty[ExpressionRef, DigitalValueRegionRecord]
  private val behavioralIfs = mutable.LinkedHashMap.empty[ExpressionRef, BehavioralIfRecord]
  private val behavioralArms = mutable.LinkedHashMap.empty[ExpressionRef, DigitalValueRegionRecord]
  private val flatFsms = mutable.LinkedHashMap.empty[ExpressionRef, FlatFsmRecord[?]]
  private val fsmActionRegions =
    mutable.LinkedHashMap.empty[ExpressionRef, DigitalValueRegionRecord]
  private val fsmResults = mutable.HashMap.empty[ExpressionRef, (FlatFsmRecord[?], String)]
  private val fsmObservations = new IdentityHashMap[AnyRef, FlatFsmObservation]()
  private val openFsms = mutable.ArrayBuffer.empty[FlatFsmRecord[?]]
  private var compositionObservationRoot: Option[ExpressionRef] = None
  private val privateFsmStorage = mutable.HashSet.empty[DeclarationRef]
  private var auxiliaryFsmStateBits = 0
  private def contextualFsmInitializer(declaration: DeclarationRecord): Boolean =
    privateFsmStorage(declaration.reference) &&
      declaration.attributes.contains("initializer_policy" -> "if_reset") &&
      declarationCandidate(declaration).exists: domain =>
        val reference = domainRef(domain)
        reference.module == declaration.reference.module &&
        records(reference.module).domains(reference.index).kind == KernelDomainKind.Required
  private val enumSwitches = mutable.LinkedHashMap.empty[ExpressionRef, EnumSwitchRecord[?]]
  private val enumSwitchArms = mutable.LinkedHashMap.empty[ExpressionRef, DigitalValueRegionRecord]
  private val behavioralChains =
    mutable.HashMap.empty[(Long, Option[ExpressionRef]), BehavioralChainCursor]
  private def allValueRegions =
    collectionRegions.valuesIterator ++ loopRegions.valuesIterator ++ structuralArms.valuesIterator ++
      behavioralArms.valuesIterator ++ fsmActionRegions.valuesIterator ++ enumSwitchArms.valuesIterator

  private def activeBehavioral: Option[DigitalValueRegionRecord] =
    collectionStack.reverseIterator.find(sequentialRegion)

  private def sequentialRegion(region: DigitalValueRegionRecord): Boolean =
    region.behavioralArm.nonEmpty || region.fsmAction.nonEmpty || region.switchArm.nonEmpty

  private def sequentialRoot(region: Option[DigitalValueRegionRecord]): Option[ExpressionRef] =
    region.flatMap: current =>
      current.behavioralArm.map(header => behavioralIfs(header.conditional).root)
        .orElse(current.fsmAction.map(_.machine))
        .orElse(current.switchArm.map(header => enumSwitches(header.switch).root))
        .orElse(sequentialRoot(current.parent))

  private def fsmActionRegion(record: FlatFsmRecord[?]): Option[DigitalValueRegionRecord] =
    collectionStack.reverseIterator.find(_.fsmAction.exists(_.machine == record.reference))

  private def validateFsmObservations(values: Vector[Any]): Option[FlatFsmObservation] =
    val pending = mutable.ArrayDeque.from(values)
    var result: Option[FlatFsmObservation] = None
    while pending.nonEmpty do
      pending.removeLast() match
        case reference: Expr[?] =>
          Option(fsmObservations.get(reference)).foreach: observation =>
            if (!observation.record.open && !compositionObservationRoot.exists(root =>
                observation.record.composition.exists(_.root == root)
              )) || observation.record.reference.module != currentModule.handle
            then
              FlatFsmRuntime.fail("machine observation escaped its open builder scope")
            result.foreach: previous =>
              if (previous.record ne observation.record) && !compositionObservationRoot.exists(
                  root =>
                    previous.record.composition.exists(
                      _.root == root
                    ) && observation.record.composition.exists(_.root == root)
                )
              then
                FlatFsmRuntime.fail("observations belong to different open machine scopes")
            result = Some(FlatFsmObservation(
              observation.record,
              observation.future || result.exists(_.future)
            ))
        case values: Seq[?] => pending ++= values
        case Some(value) => pending.append(value)
        case _ => ()
    result

  private def markFsmObservation[A <: Data](
      record: FlatFsmRecord[?],
      value: Expr[A],
      future: Boolean
  )
      : Expr[A] =
    fsmObservations.put(value, FlatFsmObservation(record, future))
    value

  private def requireFsm[S](builder: FsmBuilder[S]): FlatFsmRecord[S] =
    val record = builder.record
    if !record.open || record.reference.module != currentModule.handle ||
      !flatFsms.get(record.reference).contains(record) || !openFsms.lastOption.contains(record)
    then
      FlatFsmRuntime.fail("machine builder is closed or belongs to another construction")
    record

  private def requireFsmObservation[S](builder: FsmBuilder[S]): FlatFsmRecord[S] =
    val record = builder.record
    if compositionObservationRoot.exists(root => record.composition.exists(_.root == root)) &&
      record.reference.module == currentModule.handle && flatFsms.get(
        record.reference
      ).contains(record)
    then record
    else requireFsm(builder)

  private def requireFsmState[S](builder: StateBuilder[S]): FlatFsmRecord[S] =
    val record = requireFsm(builder.machine)
    if !builder.record.open || !record.states.contains(builder.record) || activeValueRegion.nonEmpty
    then
      FlatFsmRuntime.fail("state builder is closed or used outside its graph construction scope")
    record

  private def fsmTransaction[A](body: => A): A =
    try body
    catch
      case failure: Throwable =>
        poisonConstructor(failure, "flat FSM/action switch")
        scala.util.Failure[Nothing](failure).get

  private def allocateFlatFsm[S](
      definition: FsmDefinition[S],
      initial: S,
      encoding: FsmEncoding,
      custom: Option[FsmLocalEncoding[S]],
      illegalState: IllegalStatePolicy,
      evidence: HwEnum[S],
      control: Option[FsmControl],
      composed: Boolean
  ): FsmBuilder[S] =
    if activeValueRegion.nonEmpty || activeGeneratedRegion.nonEmpty || analogStack.nonEmpty ||
      analogSemanticContext.nonEmpty || openFsms.nonEmpty || flatFsms.size >= 4096
    then
      FlatFsmRuntime.fail("flat machine requires direct bounded digital Module construction")
    if Option(definition).isEmpty || Option(encoding).isEmpty || Option(illegalState).isEmpty ||
      custom.exists(value => Option(value).isEmpty)
    then
      FlatFsmRuntime.fail("machine configuration cannot be null")
    val module = currentModule
    val domain =
      currentDomain.getOrElse(FlatFsmRuntime.fail("machine requires an actual local clock domain"))
    val domainReference = domainRef(domain)
    val contextual = domainReference.module == module.handle &&
      module.domains(domainReference.index).kind == KernelDomainKind.Required
    if !contextual && (domain.clock.isEmpty || !domain.resetPolicy.exists(Set(
        ResetPolicy.None,
        ResetPolicy.Sync,
        ResetPolicy.Async
      ).contains))
    then
      FlatFsmRuntime.fail("machine requires a supported local binding or exact local requirement")
    val boundControl = control.map: value =>
      val _ = FsmDefinitionRuntime.control(value.restart, value.cancel, value.enable)
      val _ = validateFsmObservations(Vector(value.restart, value.cancel, value.enable))
      def bind(value: Expr[Bool]): Expr[Bool] =
        bindCollectionValue(value, None, module.handle).asInstanceOf[Expr[Bool]]
      new FsmControl(bind(value.restart), bind(value.cancel), bind(value.enable))
    val canonical = DigitalEnumContract.abi(definition.canonicalType)
    val initialId = DigitalEnumContract.selectedCase(initial, evidence, canonical)
    val (resolvedEncoding, local) = FlatFsmRuntime.localCodes(encoding, custom, evidence)
    if local.identity != canonical.identity || local.cases.map(_.id).toSet != canonical.caseIds then
      FlatFsmRuntime.fail("local encoding belongs to another canonical enum")
    val localType = CandidateRuntime.dataType[Bits]("Bits", local.width)
    val initialCode = local.cases.find(_.id == initialId).get.code
    val storage = if domain.resetPolicy.contains(ResetPolicy.None) then Reg.uninitialized(localType)
    else Reg(FlatFsmRuntime.bits(initialCode, local.width))
    val statusStorage = boundControl.map: _ =>
      if domain.resetPolicy.contains(ResetPolicy.None) then Reg.uninitialized(Bits(2))
      else Reg(FlatFsmRuntime.bits(0, 2))
    if contextual then
      (Vector(storage) ++ statusStorage).foreach: value =>
        val reference = declarationIds.get(value)
        val declaration = module.declarations(reference.index)
        module.declarations(reference.index) = declaration.copy(
          attributes = declaration.attributes :+ ("initializer_policy" -> "if_reset")
        )
    val reference = allocateExpressionReference(module)
    module.expressionCount += 1
    def projection[A <: Data](operation: String, dataType: KernelTypeDescriptor): KernelExpr[A] =
      val value =
        new KernelExpr[A](Vector(storage), resultType = Some(dataType), operation = Some(operation))
      val _ = captureExpression(value)
      value
    val next = projection[Bits](
      FlatFsmRuntime.NextOperation,
      KernelTypeDescriptor("Bits", Vector(local.width))
    )
    val taken = projection[Bool](FlatFsmRuntime.TakenOperation, KernelTypeDescriptor("Bool"))
    val lifecycle = boundControl.map(value =>
      FlatFsmLifecycleRecord(
        statusStorage.get,
        value,
        projection[Bool](FlatFsmRuntime.EnteredOperation, KernelTypeDescriptor("Bool")),
        projection[Bool](FlatFsmRuntime.ExitedOperation, KernelTypeDescriptor("Bool")),
        Option.when(composed)(projection[Bool](
          FlatFsmRuntime.EstablishmentOperation,
          KernelTypeDescriptor("Bool")
        ))
      )
    )
    val record = new FlatFsmRecord(
      reference,
      definition,
      evidence,
      canonical,
      local,
      initialId,
      encoding,
      resolvedEncoding,
      illegalState,
      domain,
      storage,
      next,
      taken,
      nextEffectOrdinal(),
      lifecycle
    )
    flatFsms.update(reference, record)
    fsmResults.update(expressionIds.get(next), record -> "next")
    fsmResults.update(expressionIds.get(taken), record -> "taken")
    lifecycle.foreach: value =>
      fsmResults.update(expressionIds.get(value.entered), record -> "entered")
      fsmResults.update(expressionIds.get(value.exited), record -> "exited")
      value.establishment.foreach(result =>
        fsmResults.update(expressionIds.get(result), record -> "establishment")
      )
    record.privateStorage.foreach(value => privateFsmStorage += declarationIds.get(value))
    new FsmBuilder(definition, initial, encoding, illegalState, record)

  private def captureAllocatedFlatFsm[S](
      builder: FsmBuilder[S],
      body: FsmBuilder[S] => Unit
  ): Unit =
    val record = builder.record
    semanticOrigin.captureExpression(
      record.reference.module,
      record.reference.index,
      record,
      record.privateStorage ++ record.controlInputs
    )
    openFsms += record
    try
      body(builder)
      FlatFsmGraphContract.validate(record, pathOf)
    finally
      record.open = false
      record.states.foreach(_.open = false)
      val removed = openFsms.remove(openFsms.size - 1)
      if removed ne record then FlatFsmRuntime.fail("machine builder stack is corrupt")

  def captureFlatFsm[S](
      definition: FsmDefinition[S],
      initial: S,
      encoding: FsmEncoding,
      custom: Option[FsmLocalEncoding[S]],
      illegalState: IllegalStatePolicy,
      body: FsmBuilder[S] => Unit,
      evidence: HwEnum[S],
      control: Option[FsmControl]
  ): Unit = fsmTransaction:
    captureAllocatedFlatFsm(
      allocateFlatFsm(
        definition,
        initial,
        encoding,
        custom,
        illegalState,
        evidence,
        control,
        false
      ),
      body
    )

  def captureFsmComposition[S](
      spec: FsmInstanceSpec[S],
      control: FsmControl,
      observe: FsmRun[S] => Unit
  ): Unit = fsmTransaction:
    val root = new FsmOccurrence(spec)
    val plan = mutable.ArrayBuffer[FsmOccurrence[?]](root)
    var cursor = 0
    def prepare[T](occurrence: FsmOccurrence[T]): Unit =
      FsmCompositionRuntime.validateBindings(occurrence.spec)
      val bySlot = occurrence.spec.bindings.children.map(value => value.slot -> value).toMap
      occurrence.children = occurrence.spec.definition.children.map: slot =>
        val binding = bySlot(slot)
        FsmChildOccurrence(binding.slot, new FsmOccurrence(binding.instance))
      occurrence.children.foreach(value => plan += value.occurrence)
    while cursor < plan.size do
      if plan.size > 4096 then FlatFsmRuntime.fail("composition exceeds 4096 occurrences")
      prepare(plan(cursor))
      cursor += 1
    if flatFsms.size.toLong + plan.size > 4096 then
      FlatFsmRuntime.fail("module machines exceed 4096 occurrences")
    def allocate[T](occurrence: FsmOccurrence[T]): Unit =
      val value = occurrence.spec
      occurrence.builder = Some(allocateFlatFsm(
        value.definition.definition,
        value.definition.initial,
        value.encoding,
        value.custom,
        value.illegalState,
        value.definition.evidence,
        Some(control),
        true
      ))
    plan.foreach(allocate)
    val rootReference = root.builder.get.record.reference
    def attach[T](value: FsmOccurrence[T]): Unit =
      val definition = value.spec.definition
      val record = value.builder.get.record
      val fragments = definition.fragments.map(fragment =>
        fragment.name -> fragment.states.map(state =>
          DigitalEnumContract.selectedCase(state.value, definition.evidence, record.canonical)
        )
      )
      record.composition = Some(new FlatFsmCompositionRecord(
        rootReference,
        definition.definition.name,
        definition.semanticHash,
        fragments,
        definition.compositionCost,
        definition.compositionCost.completionPossible
      ))
    plan.foreach(attach)
    def capture[T](occurrence: FsmOccurrence[T]): Unit =
      val spec = occurrence.spec
      val builder = occurrence.builder.get
      val record = builder.record
      val guards = spec.bindings.guards.map(value => value.slot -> value.value).toMap
      val actions = spec.bindings.actions.map(value => value.slot -> value.body).toMap
      val children =
        occurrence.children.map(value => value.slot -> value.occurrence.builder.get.record).toMap
      captureAllocatedFlatFsm(
        builder,
        machine =>
          spec.definition.stack.foreach(captureFlatFsmStack(machine, _))
          spec.definition.fragments.foreach: fragment =>
            fragment.states.foreach: state =>
              captureFlatFsmState(
                machine,
                state.value,
                stateBuilder =>
                  state.commands.foreach:
                    case edge: FsmDefinitionEdge[T] =>
                      captureFlatFsmTransition(
                        stateBuilder,
                        guards(edge.guard),
                        edge.mode,
                        edge.destination,
                        edge.action.map(actions)
                      )
                    case call: FsmDefinitionCall[T] =>
                      captureFlatFsmProcedure(
                        stateBuilder,
                        guards(call.guard),
                        call.mode,
                        Some(call.entry),
                        Some(call.resume)
                      )
                    case returning: FsmDefinitionReturn[T] =>
                      captureFlatFsmProcedure(
                        stateBuilder,
                        guards(returning.guard),
                        returning.mode,
                        None,
                        None
                      )
                    case timer: FsmDefinitionTimer[T] =>
                      captureFlatFsmTimer(stateBuilder, timer.cycles, timer.mode, timer.destination)
                    case transfer: FsmDefinitionTransfer[T] =>
                      captureFlatFsmTransferTransition(
                        stateBuilder,
                        guards(transfer.slot.guard),
                        transfer.mode,
                        transfer.destination
                      )
                    case action: FsmDefinitionAction[T] =>
                      captureFlatFsmAction(stateBuilder, action.phase, actions(action.slot))
                    case join: FsmDefinitionJoin[T] =>
                      val selected = join.child.toVector match
                        case values if values.nonEmpty => values
                        case _ => state.childSlots
                      val actual = selected.map(children)
                      val result = new KernelExpr[Bool](
                        actual.map(_.lifecycle.get.storage),
                        resultType = Some(KernelTypeDescriptor("Bool")),
                        operation = Some(FlatFsmRuntime.JoinOperation)
                      )
                      val _ = captureExpression(result)
                      record.composition.get.joins += FlatFsmJoinRecord(
                        result,
                        stateBuilder.record.caseId,
                        actual,
                        join.policy,
                        if join.child.nonEmpty then "one_child" else "all_children"
                      )
                      captureFlatFsmTransition(
                        stateBuilder,
                        result,
                        join.mode,
                        join.destination,
                        None
                      )
                  if state.isTerminal then captureFlatFsmTerminal(stateBuilder)
              )
      )
      spec.definition.fragments.flatMap(_.states).filter(_.childSlots.nonEmpty).foreach: state =>
        val ownerCase =
          DigitalEnumContract.selectedCase(state.value, record.evidence, record.canonical)
        val owned = state.childSlots.map(slot => (slot: AnyRef) -> children(slot))
        val lifecycle = record.lifecycle.get
        val operands = Vector(
          record.storage,
          lifecycle.storage,
          record.nextLocal,
          record.taken,
          lifecycle.entered,
          lifecycle.exited,
          lifecycle.establishment.get,
          lifecycle.control.enable
        )
        def result(operation: String, role: String): KernelExpr[Bool] =
          val value = new KernelExpr[Bool](
            operands,
            resultType = Some(KernelTypeDescriptor("Bool")),
            operation = Some(operation)
          )
          val _ = captureExpression(value)
          val ordinal = record.local.cases.indexWhere(_.id == ownerCase)
          fsmResults.update(expressionIds.get(value), record -> s"children.$ordinal.$role")
          value
        val childControl = new FsmControl(
          result(FlatFsmRuntime.RestartOperation, "restart"),
          result(FlatFsmRuntime.CancelOperation, "cancel"),
          result(FlatFsmRuntime.EnableOperation, "enable")
        )
        record.composition.get.groups += FlatFsmChildGroup(ownerCase, owned, childControl)
        owned.foreach: (slot, child) =>
          child.lifecycle.get.control = childControl
          child.composition.get.parent =
            Some((record, ownerCase, slot.asInstanceOf[FsmChild[T, ?]].name))
    plan.foreach(capture)
    compositionObservationRoot = Some(rootReference)
    try observe(new FsmRun(root.builder.get))
    finally compositionObservationRoot = None

  def fsmChild[P, C](builder: FsmBuilder[P], slot: FsmChild[P, C]): FsmChildRun[C] =
    val record = requireFsmObservation(builder)
    if slot == null || (slot.owner ne record.definition) || compositionObservationRoot.isEmpty then
      FsmDefinitionRuntime.fail(
        "child observation requires its exact composition callback and slot owner"
      )
    val child = record.composition.toVector.flatMap(_.groups).flatMap(_.children)
      .find((candidate, _) => candidate eq slot).map(_._2)
      .getOrElse(FsmDefinitionRuntime.fail("child observation names a missing or foreign slot"))
    // The exact typed slot is the identity witness for this one existential child record.
    val typed = child.asInstanceOf[FlatFsmRecord[C]]
    if typed.definition ne slot.definition.definition then
      FsmDefinitionRuntime.fail("child definition identity mismatch")
    var active: Expr[Bool] = true.B
    var current = child
    while current.composition.flatMap(_.parent).nonEmpty do
      val (parent, ownerCase, _) = current.composition.get.parent.get
      val code = parent.local.cases.find(_.id == ownerCase).get.code
      val owner = DigitalCombinationalContract.compare(
        "digital_eq",
        parent.storage,
        FlatFsmRuntime.bits(code, parent.local.width)
      )
      val running = DigitalCombinationalContract.compare(
        "digital_eq",
        parent.lifecycle.get.storage,
        FlatFsmRuntime.bits(1, 2)
      )
      active = SequentialRuntime.and(active, SequentialRuntime.and(owner, running))
      current = parent
    val observed = markFsmObservation(record, active, future = false)
    new FsmChildRun(
      observed,
      new FsmRun(new FsmBuilder(
        typed.definition,
        slot.definition.initial,
        typed.encoding,
        typed.illegalState,
        typed
      ))
    )

  def captureFlatFsmState[S](
      builder: FsmBuilder[S],
      value: S,
      body: StateBuilder[S] => Unit
  ): Unit =
    fsmTransaction:
      val record = requireFsm(builder)
      if activeValueRegion.nonEmpty || record.states.exists(_.open) then
        FlatFsmRuntime.fail("state declarations cannot nest or occur inside actions")
      val id = DigitalEnumContract.selectedCase(value, record.evidence, record.canonical)
      if record.states.exists(_.caseId == id) then FlatFsmRuntime.fail("machine repeats a state")
      val state = new FlatFsmStateRecord(id, record.nextOrdinal())
      record.states += state
      try body(new StateBuilder(builder, state))
      finally state.open = false

  def captureFlatFsmTerminal[S](builder: StateBuilder[S]): Unit =
    val _ = requireFsmState(builder)
    if builder.record.terminal then FlatFsmRuntime.fail("state repeats terminal declaration")
    builder.record.terminal = true

  private def captureFsmAction[S](
      record: FlatFsmRecord[S],
      phase: String,
      ownerCase: Option[String],
      transitionOrdinal: Option[Int],
      body: () => Unit
  ): Unit =
    if record.actions.size.toLong + record.transitions.size >= FlatFsmRuntime.MaximumRecords then
      FlatFsmRuntime.fail("machine transitions/actions exceed 65536 records")
    val header = FlatFsmActionHeader(
      record.reference,
      phase,
      ownerCase,
      transitionOrdinal,
      record.nextOrdinal()
    )
    val region = new DigitalValueRegionRecord(
      newScaffoldReference(currentModule),
      None,
      "fsm_action",
      Vector.empty,
      None,
      fsmAction = Some(header)
    )
    fsmActionRegions.update(region.reference, region)
    record.actions += region
    val _ = newCollectionArgument(
      region,
      CandidateRuntime.dataType[Bits]("Bits", record.local.width),
      "current_local"
    )
    val _ = newCollectionArgument(
      region,
      CandidateRuntime.dataType[Bits]("Bits", record.local.width),
      "next_local"
    )
    val _ = newCollectionArgument(region, Bool, "transition_taken")
    withBehavioralArm(region)(body())

  def captureFlatFsmAction[S](builder: StateBuilder[S], phase: String, body: () => Unit): Unit =
    fsmTransaction:
      val record = requireFsmState(builder)
      if !Set("entry", "active", "exit")(phase) then
        FlatFsmRuntime.fail("unknown state action phase")
      captureFsmAction(record, phase, Some(builder.record.caseId), None, body)

  def captureFlatFsmTransition[S](
      builder: StateBuilder[S],
      condition: Expr[Bool],
      mode: TransitionMode,
      to: S,
      body: Option[() => Unit]
  ): Unit = fsmTransaction:
    val record = requireFsmState(builder)
    val bound = captureFsmGuard(record, condition, mode)
    val destination = DigitalEnumContract.selectedCase(to, record.evidence, record.canonical)
    val transition =
      FlatFsmTransitionRecord(
        builder.record.caseId,
        Some(destination),
        mode,
        bound,
        record.nextOrdinal()
      )
    record.transitions += transition
    body.foreach(callback =>
      captureFsmAction(record, "transition", None, Some(transition.ordinal), callback)
    )

  def captureFlatFsmStack[S](builder: FsmBuilder[S], config: FsmStackConfig[S]): Unit =
    fsmTransaction:
      val record = requireFsm(builder)
      if record.stack.nonEmpty || record.states.nonEmpty || record.transitions.nonEmpty then
        FlatFsmRuntime.fail("stack must be configured once before machine states")
      if record.encoding == FsmEncoding.Gray then
        FlatFsmRuntime.fail("bounded dynamic return does not support Gray local encoding")
      val contract = FsmStackRuntime.contract(
        config,
        value =>
          DigitalEnumContract.selectedCase(value, record.evidence, record.canonical)
      )
      val width = FsmStackRuntime.pointerWidth(contract.capacity)
      val bits = width.toLong + contract.capacity.toLong * record.local.width
      if auxiliaryFsmStateBits + bits > FsmTimingRuntime.MaximumStateBits then
        FlatFsmRuntime.fail("FSM timer and stack private storage exceed 65536 actual bits")
      val pointer = if record.domain.resetPolicy.contains(ResetPolicy.None) then
        Reg.uninitialized(UInt(width))
      else Reg(0.U(width))
      val domain = domainRef(record.domain)
      val module = currentModule
      if module.domains(domain.index).kind == KernelDomainKind.Required then
        val reference = declarationIds.get(pointer)
        val declaration = module.declarations(reference.index)
        module.declarations(reference.index) = declaration.copy(
          attributes = declaration.attributes :+ ("initializer_policy" -> "if_reset")
        )
      val frames = Vector.fill(contract.capacity)(Reg.uninitialized(Bits(record.local.width)))
      record.stack = Some(FlatFsmStackRecord(contract, pointer, frames, width))
      (Vector(pointer) ++ frames).foreach(value => privateFsmStorage += declarationIds.get(value))
      auxiliaryFsmStateBits += bits.toInt

  def captureFlatFsmProcedure[S](
      builder: StateBuilder[S],
      condition: Expr[Bool],
      mode: TransitionMode,
      entry: Option[S],
      resume: Option[S]
  ): Unit = fsmTransaction:
    val record = requireFsmState(builder)
    if record.stack.isEmpty || entry.nonEmpty != resume.nonEmpty then
      FlatFsmRuntime.fail("call/return requires one admitted stack and exact entry/resume shape")
    val bound = captureFsmGuard(record, condition, mode)
    def caseId(value: S): String =
      DigitalEnumContract.selectedCase(value, record.evidence, record.canonical)
    record.transitions += FlatFsmTransitionRecord(
      builder.record.caseId,
      entry.map(caseId),
      mode,
      bound,
      record.nextOrdinal(),
      if entry.nonEmpty then "call" else "return",
      resume.map(caseId)
    )

  private def captureFsmGuard[S](
      record: FlatFsmRecord[S],
      condition: Expr[Bool],
      mode: TransitionMode
  ): Expr[Bool] =
    if mode == null || CandidateRuntime.expressionDataType(
        condition
      ).map(CandidateRuntime.typeDescriptor) !=
        Some(KernelTypeDescriptor("Bool"))
    then FlatFsmRuntime.fail("transition requires exact Bool and a known mode")
    val _ = validateFsmObservations(Vector(condition))
    if Option(fsmObservations.get(condition)).exists(_.future) then
      FlatFsmRuntime.fail("next-state/event feedback into transition selection is cyclic")
    if record.actions.size.toLong + record.transitions.size >= FlatFsmRuntime.MaximumRecords then
      FlatFsmRuntime.fail("machine transitions/actions exceed 65536 records")
    bindCollectionValue(condition, None, currentModule.handle).asInstanceOf[Expr[Bool]]

  def captureFlatFsmTimer[S](
      builder: StateBuilder[S],
      cycles: Int,
      mode: TransitionMode,
      to: S
  ): Unit = fsmTransaction:
    FsmTimingRuntime.cycles(cycles)
    val record = requireFsmState(builder)
    val storage = record.timer.getOrElse {
      if auxiliaryFsmStateBits.toLong + FsmTimingRuntime.Width > FsmTimingRuntime.MaximumStateBits
      then
        FsmDefinitionRuntime.fail("FSM timer state exceeds 65536 actual bits")
      val value = if record.domain.resetPolicy.contains(ResetPolicy.None) then
        Reg.uninitialized(UInt(FsmTimingRuntime.Width))
      else Reg(0.U(FsmTimingRuntime.Width))
      val reference = declarationIds.get(value)
      val domainReference = domainRef(record.domain)
      if records(
          domainReference.module
        ).domains(domainReference.index).kind == KernelDomainKind.Required
      then
        val module = records(reference.module)
        val declaration = module.declarations(reference.index)
        module.declarations(reference.index) = declaration.copy(attributes =
          declaration.attributes :+ ("initializer_policy" -> "if_reset")
        )
      record.timer = Some(value)
      auxiliaryFsmStateBits += FsmTimingRuntime.Width
      privateFsmStorage += reference
      value
    }
    val result = new KernelExpr[Bool](
      Vector(storage),
      resultType = Some(KernelTypeDescriptor("Bool")),
      operation = Some(FsmTimingRuntime.ElapsedOperation)
    )
    val _ = captureExpression(result)
    captureFlatFsmTransition(builder, result, mode, to, None)
    record.timerEdges += FlatFsmTimerEdge(record.transitions.last.ordinal, cycles, result)

  def captureFlatFsmTransferTransition[S](
      builder: StateBuilder[S],
      condition: Expr[Bool],
      mode: TransitionMode,
      to: S
  ): Unit = fsmTransaction:
    val record = requireFsmState(builder)
    val expression = condition match
      case value: KernelExpr[?]
          if value.operation.contains(
            FsmTimingRuntime.TransferOperation
          ) && value.operands.size == 2 => value
      case _ =>
        FsmDefinitionRuntime.fail("transfer edge requires an explicit actual Stream binding")
    val valid = expression.operands(0).asInstanceOf[Expr[Bool]]
    val ready = expression.operands(1).asInstanceOf[Expr[Bool]]
    val payload = fsmTransferTuple(valid, ready)
    val predicate = new KernelExpr[Bool](
      Vector(valid, ready),
      resultType = Some(KernelTypeDescriptor("Bool")),
      operation = Some(FsmTimingRuntime.TransferOperation)
    )
    val _ = captureExpression(predicate)
    captureFlatFsmTransition(builder, predicate, mode, to, None)
    record.transferEdges +=
      FlatFsmTransferEdge(record.transitions.last.ordinal, predicate, payload, valid, ready)

  private def flatFsmLocal[S](builder: FsmBuilder[S], next: Boolean): Expr[Bits] =
    val record = requireFsmObservation(builder)
    fsmActionRegion(record).map(_.arguments(if next then 1 else 0)._1.asInstanceOf[Expr[Bits]])
      .getOrElse(if next then record.nextLocal else record.storage)

  def flatFsmStateView[S](builder: FsmBuilder[S], next: Boolean): DecodedEnum[Expr[EnumValue[S]]] =
    val record = requireFsmObservation(builder)
    record.views.getOrElseUpdate(
      activeValueRegion.map(_.reference) -> next, {
        val local = flatFsmLocal(builder, next)
        val initial =
          enumLiteral(record.definition.canonicalType, builder.initial)(using record.evidence)
        val hostCases =
          record.evidence.declaration.get.cases.map(value => value.id -> value.value).toMap
        val leaves = record.local.cases.map: value =>
          val matches = DigitalCombinationalContract.compare(
            "digital_eq",
            local,
            FlatFsmRuntime.bits(value.code, record.local.width)
          )
          val canonicalCase = hostCases(value.id)
          val literal =
            enumLiteral(record.definition.canonicalType, canonicalCase)(using record.evidence)
          (matches, literal)
        def combine(start: Int, end: Int): (Expr[Bool], Expr[EnumValue[S]]) =
          if end - start == 1 then leaves(start)
          else
            val middle = start + (end - start) / 2
            val left = combine(start, middle)
            val right = combine(middle, end)
            (
              SequentialRuntime.or(left._1, right._1),
              DigitalCombinationalContract.select(left._1, left._2, right._2)
            )
        val (valid, candidate) = combine(0, leaves.size)
        val decoded = DigitalCombinationalContract.select(valid, candidate, initial)
        DecodedEnum(
          markFsmObservation(record, decoded, next),
          markFsmObservation(record, valid, next)
        )
      }
    )

  def flatFsmTaken[S](builder: FsmBuilder[S]): Expr[Bool] =
    val record = requireFsmObservation(builder)
    val value = fsmActionRegion(
      record
    ).map(_.arguments(2)._1.asInstanceOf[Expr[Bool]]).getOrElse(record.taken)
    markFsmObservation(record, value, future = true)

  def flatFsmEvent[S](builder: FsmBuilder[S], state: S, entering: Boolean): Expr[Bool] =
    val record = requireFsmObservation(builder)
    val view = flatFsmStateView(builder, entering)
    val matches = DigitalEnumContract.isOneOf(view.value, Vector(state), record.evidence)
    val event = record.lifecycle.map(value => if entering then value.entered else value.exited)
      .getOrElse(flatFsmTaken(builder))
    markFsmObservation(record, SequentialRuntime.and(event, matches), future = true)

  def flatFsmCompleted[S](builder: FsmBuilder[S]): Expr[Bool] =
    val record = requireFsmObservation(builder)
    val current = flatFsmStateView(builder, next = false)
    val membership = new KernelExpr[Bool](
      Vector(current.value, FlatFsmCompletionMetadata(record)),
      resultType = Some(KernelTypeDescriptor("Bool")),
      operation = Some(FlatFsmRuntime.CompletedOperation)
    )
    val _ = captureExpression(membership)
    val completed = SequentialRuntime.and(current.valid, membership)
    val result = if record.lifecycle.nonEmpty then
      SequentialRuntime.and(completed, flatFsmStatus(builder).value.isOneOf(FsmStatus.Completed))
    else completed
    markFsmObservation(record, result, future = false)

  def flatFsmStatus[S](builder: FsmBuilder[S]): DecodedEnum[Expr[EnumValue[FsmStatus]]] =
    val record = requireFsmObservation(builder)
    val lifecycle =
      record.lifecycle.getOrElse(FlatFsmRuntime.fail("ordinary machine has no lifecycle"))
    val storage = bindCollectionValue(lifecycle.storage, activeValueRegion, currentModule.handle)
      .asInstanceOf[Expr[Bits]]
    val decoded = decodeEnum(storage, EnumType[FsmStatus](), FsmStatus.Dormant)
    DecodedEnum(
      markFsmObservation(record, decoded.value, future = false),
      markFsmObservation(record, decoded.valid, future = false)
    )

  def captureEnumSwitch[S](
      selector: Expr[EnumValue[S]],
      invalid: () => Unit,
      body: EnumSwitchBuilder[S] => Unit,
      evidence: HwEnum[S]
  ): Unit = fsmTransaction:
    if analogStack.nonEmpty || analogSemanticContext.nonEmpty || activeGeneratedRegion.nonEmpty ||
      activeValueRegion.exists(
        !sequentialRegion(_)
      ) || collectionStack.size >= 128 || enumSwitches.size >= 65536
    then
      FlatFsmRuntime.fail("action switch requires a bounded digital sequential context")
    closeBehavioralChain()
    val module = currentModule
    val parent = activeValueRegion
    val canonical = DigitalEnumContract.abi(selector)
    val bound =
      bindCollectionValue(selector, parent, module.handle).asInstanceOf[Expr[EnumValue[S]]]
    val reference = allocateExpressionReference(module)
    module.expressionCount += 1
    val ordinal = parent.map: region =>
      val result = region.assignmentCount
      region.assignmentCount += 1
      result
    .getOrElse(nextEffectOrdinal())
    val record = new EnumSwitchRecord(
      reference,
      parent,
      bound,
      canonical,
      evidence,
      ordinal,
      sequentialRoot(parent).getOrElse(reference)
    )
    enumSwitches.update(reference, record)
    semanticOrigin.captureExpression(module.handle, reference.index, record, Vector(selector))
    try body(new EnumSwitchBuilder(record))
    finally record.open = false
    DigitalEnumContract.completeSelection(record.cases.map(_._1).toVector, canonical)
    (record.cases.map(_._2).toVector :+ invalid).zipWithIndex.foreach: (callback, index) =>
      val region = new DigitalValueRegionRecord(
        newScaffoldReference(module),
        parent,
        "enum_switch_arm",
        Vector.empty,
        None,
        switchArm = Some(EnumSwitchArmHeader(reference, index))
      )
      enumSwitchArms.update(region.reference, region)
      record.arms += region
      withBehavioralArm(region)(callback())

  def captureEnumSwitchCase[S](builder: EnumSwitchBuilder[S], value: S, body: () => Unit): Unit =
    val record = builder.record
    if !record.open || record.reference.module != currentModule.handle ||
      !enumSwitches.get(record.reference).contains(record) || activeValueRegion != record.parent
    then
      FlatFsmRuntime.fail("enum switch builder is closed or belongs to another lexical scope")
    val id = DigitalEnumContract.selectedCase(value, record.evidence, record.canonical)
    if record.cases.exists(_._1 == id) then FlatFsmRuntime.fail("enum switch repeats a case")
    record.cases += id -> body

  private def sharedArmSnapshot(region: DigitalValueRegionRecord): KernelStructuralArmSnapshot =
    KernelStructuralArmSnapshot(
      expressionPath(region.reference),
      region.captures.valuesIterator.map(value => pathOf(value._1).get).toVector,
      regionArguments(region),
      None
    )

  private def flatFsmSnapshots(): Vector[KernelFlatFsmSnapshot] = flatFsms.valuesIterator.map:
    record =>
      KernelFlatFsmSnapshot(
        expressionPath(record.reference),
        modulePath(record.reference.module),
        None,
        pathOf(record.domain).get,
        pathOf(record.storage).get,
        record.canonical.identity,
        FlatFsmRuntime.encodingName(record.encoding),
        record.resolvedEncoding,
        record.local.width,
        record.initialCase,
        record.local.cases.map(value => KernelFlatFsmCode(value.id, value.code.toString)),
        if record.illegalState == IllegalStatePolicy.Error then "error" else "recover_to_initial",
        record.states.toVector.map(value =>
          KernelFlatFsmState(value.caseId, value.terminal, value.ordinal)
        ),
        record.transitions.toVector.map(value =>
          KernelFlatFsmTransition(
            value.sourceCase,
            value.destinationCase,
            if value.mode == TransitionMode.Exclusive then "exclusive" else "priority",
            pathOf(value.guard).get,
            value.ordinal,
            value.kind,
            value.resumeCase
          )
        ),
        record.actions.toVector.map: region =>
          val header = region.fsmAction.get
          val arm = sharedArmSnapshot(region)
          KernelFlatFsmAction(
            arm.path,
            header.phase,
            header.ownerCase,
            header.transitionOrdinal,
            header.ordinal,
            arm.captures,
            arm.arguments
          )
        ,
        pathOf(record.nextLocal).get,
        pathOf(record.taken).get,
        record.sourceOrdinal,
        record.lifecycle.map(value =>
          KernelFlatFsmControl(
            pathOf(value.storage).get,
            pathOf(value.control.restart).get,
            pathOf(value.control.cancel).get,
            pathOf(value.control.enable).get,
            pathOf(value.entered).get,
            pathOf(value.exited).get
          )
        ),
        record.composition.map(value =>
          KernelFsmCompositionSnapshot(
            expressionPath(value.root),
            value.parent.map(parent => expressionPath(parent._1.reference)),
            value.parent.map(_._2),
            value.parent.map(_._3),
            pathOf(record.lifecycle.get.establishment.get).get,
            value.groups.toVector.map(group =>
              KernelFsmChildGroup(
                group.ownerCase,
                group.children.map(child => expressionPath(child._2.reference)),
                pathOf(group.control.restart).get,
                pathOf(group.control.cancel).get,
                pathOf(group.control.enable).get
              )
            ),
            value.joins.toVector.map(join =>
              KernelFsmJoinSnapshot(
                pathOf(join.result).get,
                join.ownerCase,
                join.children.map(child => expressionPath(child.reference)),
                join.policy,
                join.selection
              )
            ),
            value.definitionName,
            value.definitionHash,
            value.fragments,
            value.cost.occurrences,
            value.cost.records,
            value.cost.depth,
            value.completionPossible
          )
        ),
        record.timer.map(value => pathOf(value).get),
        record.timerEdges.toVector.map(value =>
          KernelFsmTimerEdge(value.ordinal, value.cycles, pathOf(value.guard).get)
        ),
        record.transferEdges.toVector.map(value =>
          KernelFsmTransferEdge(
            value.ordinal,
            pathOf(value.guard).get,
            pathOf(value.payload).get,
            pathOf(value.valid).get,
            pathOf(value.ready).get
          )
        ),
        record.stack.map(value =>
          KernelFsmStack(
            value.contract.capacity,
            value.pointerWidth,
            pathOf(value.pointer).get,
            value.frames.map(frame => pathOf(frame).get),
            value.contract.overflow,
            value.contract.underflow,
            value.contract.invalidFrame
          )
        )
      )
  .toVector

  private def enumSwitchSnapshots(): Vector[KernelEnumSwitchSnapshot] =
    enumSwitches.valuesIterator.map: record =>
      KernelEnumSwitchSnapshot(
        expressionPath(record.reference),
        modulePath(record.reference.module),
        record.parent.map(value => expressionPath(value.reference)),
        pathOf(record.selector).get,
        record.canonical.identity,
        record.cases.map(_._1).toVector,
        record.arms.dropRight(1).map(sharedArmSnapshot).toVector,
        sharedArmSnapshot(record.arms.last),
        record.sourceOrdinal,
        expressionPath(record.root)
      )
    .toVector

  private def behavioralFailure(message: String, code: String = "NODAL-SEQUENTIAL-056-001")
      : Nothing =
    val failure = new ConstructionException(KernelDiagnostic(code, message))
    poisonConstructor(failure, "behavioral guard")
    scala.util.Failure[Nothing](failure).get

  private def behavioralKey = currentModule.handle -> activeValueRegion.map(_.reference)

  private def closeBehavioralChain(): Unit =
    if moduleStack.nonEmpty then behavioralChains.remove(behavioralKey)

  private val iterationIndices = mutable.LinkedHashSet.empty[ExpressionRef]
  private val nextDigitalEffectOrdinal = mutable.HashMap.empty[Long, Int]

  private def activeLoop: Option[DigitalValueRegionRecord] =
    moduleStack.lastOption.flatMap(module =>
      collectionStack.reverseIterator.find(region =>
        region.isLoop && region.reference.module == module.handle
      )
    )

  private def nextEffectOrdinal(): Int =
    val owner = currentModule.handle
    val result = nextDigitalEffectOrdinal.getOrElse(owner, 0)
    nextDigitalEffectOrdinal.update(owner, result + 1)
    result
  // Query-local facts only. Raw KernelExpr identities and descriptors are never substituted.
  private var reductionExact = Map.empty[KernelExpr[?], Dimension]
  private var reductionDomains = Map.empty[KernelExpr[?], IterationDomain.Bounds]
  private var reductionProofWork = 0
  private var reductionChecking = false
  private var reductionProofBuilding = false

  private def chargeReduction(work: Int): Unit =
    if reductionChecking || reductionExact.nonEmpty || reductionDomains.nonEmpty then
      reductionProofWork += work
      if reductionProofWork > DigitalReductionContract.MaximumWork then
        collectionFailure("reduction body proof exceeds the 65536 analysis-work budget")

  private def withReductionContext[A](
      exact: Map[KernelExpr[?], Dimension],
      domains: Map[KernelExpr[?], IterationDomain.Bounds]
  )(body: => A): A =
    val previousExact = reductionExact
    val previousDomains = reductionDomains
    val previousChecking = reductionChecking
    reductionChecking = true
    reductionExact = exact
    reductionDomains = domains
    try body
    finally
      reductionExact = previousExact
      reductionDomains = previousDomains
      reductionChecking = previousChecking

  /** Endpoint formulas use the ordinary static builder/query, but are not source expressions. Their
    * transient registrations must not become names, provenance, captures or snapshots.
    */
  private def withReductionProofExpressions[A](body: => A): A =
    val module = currentModule
    val checkpoint = module.expressionCount
    val previous = reductionProofBuilding
    val previousChecking = reductionChecking
    reductionProofBuilding = true
    reductionChecking = true
    try body
    finally
      val temporary = expressionValues.iterator.filter((reference, _) =>
        reference.module == module.handle && reference.index >= checkpoint
      ).toVector
      temporary.foreach: (reference, expression) =>
        expressionIds.remove(expression)
        expressionValues.remove(reference)
        expressionRegions.remove(reference)
      reductionProofBuilding = previous
      reductionChecking = previousChecking

  private val instanceIds = new IdentityHashMap[AnyRef, InstanceRecord]()
  private val instanceObjects = new IdentityHashMap[AnyRef, Instance[? <: Module]]()
  private val records: mutable.LinkedHashMap[Long, ModuleRecord] = mutable.LinkedHashMap.empty
  private val moduleStack: mutable.ArrayBuffer[ModuleRecord] = mutable.ArrayBuffer.empty
  private val domainStack: mutable.ArrayBuffer[ClockDomain] = mutable.ArrayBuffer.empty
  private val operations: mutable.ArrayBuffer[Operation] = mutable.ArrayBuffer.empty
  private val expressionValues: mutable.LinkedHashMap[ExpressionRef, KernelExpr[?]] =
    mutable.LinkedHashMap.empty
  private val analogRegions: mutable.ArrayBuffer[AnalogRegionRecord] = mutable.ArrayBuffer.empty
  private val analogStack: mutable.ArrayBuffer[AnalogRegionRecord] = mutable.ArrayBuffer.empty
  private val analogSemanticRecorder = new AnalogEquationRuntime.Recorder
  private var analogSemanticContext: Option[AnalogSemanticContext] = None
  private val continuousOperators: mutable.ArrayBuffer[ContinuousOperatorRecord] =
    mutable.ArrayBuffer.empty
  private val waveformOperators: mutable.ArrayBuffer[(
      ExpressionRef,
      String,
      Vector[Expr[Real]],
      String,
      Vector[String],
      String,
      String,
      String
  )] =
    mutable.ArrayBuffer.empty
  private val transferOperators: mutable.ArrayBuffer[
    (ExpressionRef, String, Int, Int, Vector[Expr[Real]], String)
  ] = mutable.ArrayBuffer.empty
  private val noiseOperators
      : mutable.ArrayBuffer[(ExpressionRef, String, String, Vector[Expr[Real]], String)] =
    mutable.ArrayBuffer.empty
  private var waveformForbiddenDepth = 0
  private val semanticOrigin = new SemanticOriginBuilder
  private var semanticResult: Option[SemanticOriginResult] = None
  private val rootParameterBindings: mutable.ArrayBuffer[(Any, Any)] =
    mutable.ArrayBuffer.empty
  private val generationStack: mutable.ArrayBuffer[GeneratedRegionRecord] =
    mutable.ArrayBuffer.empty
  private val generatedOwnershipRecords =
    mutable.LinkedHashMap.empty[(String, String), GeneratedRegionRecord]
  private val shapeIndices = mutable.LinkedHashMap.empty[ExpressionRef, ShapeIndexRecord]
  private val shapeViews = mutable.LinkedHashMap.empty[ExpressionRef, ShapeViewRecord]
  private val nodeSelectionLookup = mutable.HashMap.empty[
    (DeclarationRef | ExpressionRef, Option[GeneratedRegionRecord]),
    mutable.LinkedHashMap[StructuralShapeCoordinates.Region, ExpressionRef]
  ]
  private val instanceSelectionStack = mutable.ArrayBuffer.empty[InstanceSelectionRecord]
  private val areas = mutable.ArrayBuffer.empty[AreaRecord]
  private val areaStack = mutable.ArrayBuffer.empty[AreaRecord]
  private val areaRecords = new java.util.IdentityHashMap[Area, AreaRecord]()
  private var constructorFailure: Option[(Throwable, String)] = None

  private def fail(code: String, message: String, path: Option[String] = None): Nothing =
    scala.util.Failure[Nothing](
      new ConstructionException(KernelDiagnostic(code, message, path))
    ).get

  private def moduleName(module: Module): String =
    val name = module.getClass.getSimpleName.stripSuffix("$")
    if name.isEmpty then "AnonymousModule" else name

  private def currentModule: ModuleRecord = moduleStack.lastOption.getOrElse(
    fail("NODAL-CONSTRUCT-016", "hardware construction has no active Module")
  )

  private def activeValueRegion: Option[DigitalValueRegionRecord] =
    moduleStack.lastOption.flatMap(module =>
      collectionStack.reverseIterator.find(_.reference.module == module.handle)
    )

  private def admitsPredicateStatics(region: DigitalValueRegionRecord): Boolean =
    var current = Option(region)
    var admitted = false
    var depth = 0
    while current.nonEmpty && !admitted do
      depth += 1
      if depth > 128 then collectionFailure("lexical static scope exceeds its depth budget")
      val value = current.get
      admitted = value.structuralArm.nonEmpty || sequentialRegion(value) || value.loop.exists(
        _.generated.nonEmpty
      )
      current = value.parent
    admitted

  private def rejectCollection(code: String, message: String, path: Option[String]): Nothing =
    val failure = new ConstructionException(KernelDiagnostic(code, message, path))
    poisonConstructor(failure, path.getOrElse("digital collection"))
    scala.util.Failure[Nothing](failure).get

  private def collectionFailure(message: String): Nothing =
    rejectCollection(
      "NODAL-COLLECTION-055-005",
      message,
      activeValueRegion.map(value => expressionPath(value.reference))
    )

  private def requireNoCollectionEffect(role: String, assignment: Boolean = false): Unit =
    if activeEventProcess.nonEmpty && !assignment then
      eventFailure(s"$role is not permitted inside a digital event process")
    collectionStack.reverseIterator.find(region =>
      moduleStack.lastOption.exists(_.handle == region.reference.module) &&
        !region.isLoop && !(assignment && region.permitsAssignment)
    ).foreach: region =>
      rejectCollection(
        "NODAL-COLLECTION-055-006",
        s"$role is not permitted in this lexical value region",
        Some(expressionPath(region.reference))
      )

  /** Generated storage is admitted only in an actual family body. Structural arms may enclose that
    * body, but cannot themselves acquire storage, behavioral or procedural allocation rights.
    */
  private def requireAreaFamilyScope(role: String, requireBody: Boolean): Unit =
    if activeEventProcess.nonEmpty then eventFailure(s"$role is not permitted in an event process")
    val module = currentModule.handle
    var current = activeValueRegion
    val visited = mutable.HashSet.empty[DigitalValueRegionRecord]
    if requireBody && !current.exists(_.loop.exists(_.generated.exists(_.areaFamily))) then
      collectionFailure(s"$role requires its actual generated Area body")
    while current.nonEmpty do
      val region = current.get
      if !visited.add(region) || visited.size > 128 || region.reference.module != module ||
        !(region.structuralArm.nonEmpty || region.loop.exists(_.generated.exists(_.areaFamily)))
      then
        collectionFailure(s"$role requires only actual generated and structural lexical ancestry")
      current = region.parent

  private def requireLoopEffect(role: String): Unit =
    activeLoop.filter(_.loop.get.generated.isEmpty).foreach: region =>
      rejectCollection(
        "NODAL-LOOP-055-006",
        s"$role is not permitted in a bounded digital loop",
        Some(expressionPath(region.reference))
      )

  private def valueReference(value: Expr[?]): DeclarationRef | ExpressionRef = publicationProbe:
    Option(declarationIds.get(value)).map(value => value: DeclarationRef | ExpressionRef)
      .orElse(Option(expressionIds.get(value)).map(value => value: DeclarationRef | ExpressionRef))
      .getOrElse(collectionFailure("collection value has no captured construction identity"))

  private def valueRegion(reference: DeclarationRef | ExpressionRef)
      : Option[DigitalValueRegionRecord] = publicationProbe:
    reference match
      case expression: ExpressionRef => expressionRegions.get(expression).orElse(
          records.get(expression.module).flatMap(_.generatedRegions.find(region =>
            region.owner == expression.module && region.induction == expression
          )).flatMap(_.digitalRegion)
        )
      case declaration: DeclarationRef =>
        instanceDigitalPorts.get(declaration).flatMap(_.scope)
          .orElse(instanceInterfacePorts.get(declaration).flatMap(_.scope)).orElse(
            records(declaration.module).generatedRegions.find(region =>
              region.areaFamily && region.declarations.contains(declaration)
            ).flatMap(_.digitalRegion)
          )

  private def generatedRegionFor(region: Option[DigitalValueRegionRecord])
      : Option[GeneratedRegionRecord] =
    var current = region
    var depth = 0
    while current.nonEmpty do
      depth += 1
      if depth > 128 then collectionFailure("generated lexical ancestry exceeds its depth budget")
      val generated = current.get.loop.flatMap(_.generated)
      if generated.nonEmpty then return generated
      current = current.get.parent
    None

  private def assignmentGeneratedScope(operation: Operation): Option[GeneratedRegionRecord] =
    operation.generatedScope.orElse(generatedRegionFor(operation.digitalRegion))

  /** Physical Integer values are storage data, never proof-time parameter atoms. Follow the
    * existing value/capture edges without interpreting a host default as a static value.
    */
  private def runtimeSequentialInteger(value: Expr[?]): Boolean =
    val memo = new IdentityHashMap[AnyRef, java.lang.Boolean]()
    var work = 0
    def visit(current: Expr[?], depth: Int): Boolean =
      Option(memo.get(current)).map(_.booleanValue).getOrElse:
        work += 1
        if work > 65536 || depth > 128 then
          collectionFailure("Integer capture origin exceeds its bounded graph")
        memo.put(current, java.lang.Boolean.FALSE)
        val result = valueReference(current) match
          case reference: DeclarationRef =>
            records(
              reference.module
            ).declarations(reference.index).kind != KernelSignalKind.Parameter
          case reference: ExpressionRef =>
            collectionArguments.get(reference) match
              case Some((region, ordinal)) => region.arguments(ordinal)._2 == "capture"
              case None => current match
                  case expression: KernelExpr[?]
                      if requireConstructionPayload(expression) && expression.literal.nonEmpty =>
                    false
                  case expression: KernelExpr[?]
                      if expression.operation.contains("generate_index") => false
                  case expression: KernelExpr[?]
                      if expression.operation.flatMap(IterationDomain.staticArithmetic).nonEmpty =>
                    expression.operands.collect { case child: Expr[?] => child }.exists(visit(
                      _,
                      depth + 1
                    ))
                  case _ => true
        memo.put(current, java.lang.Boolean.valueOf(result))
        result
    visit(value, 0)

  private def validateSequentialValueType(dataType: DataType[?], owner: Long): Unit =
    validateShapeType(dataType, owner, cardinality = true, storageContext = true)

  /** Private range partition scaffolding must not consume authored expression identities. Negative
    * indices are disjoint from the ordinary allocation stream and use the same reference
    * registries.
    */
  private def newScaffoldReference(module: ModuleRecord): ExpressionRef =
    if module.scaffoldExpressionCount == Int.MaxValue then
      collectionFailure("private iteration scaffold identity space is exhausted")
    val reference = allocateScaffoldReference(module)
    module.scaffoldExpressionCount += 1
    reference

  private def dataArgument(value: DigitalRegionArgument): KernelExpr[?] = value match
    case expression: KernelExpr[?] => expression
    case _ => collectionFailure("non-Data family argument cannot enter a Data-only consumer")

  private def dataCapture(value: DigitalCaptureValue): Expr[?] = value match
    case expression: Expr[?] => expression
    case _ => collectionFailure("non-Data family capture cannot enter a Data-only consumer")

  /** The ordinary formal signature is determined by registered inputs/seed, never captures. */
  private def collectionFormalSource(
      region: DigitalValueRegionRecord,
      position: Int,
      role: String
  ): Expr[?] =
    if !collectionRegions.get(region.reference).contains(region) ||
      region.regionKind != DigitalRegionKind.Collection ||
      !(DigitalCollectionContract.OrdinaryKinds(region.kind) || DigitalReductionContract.Kinds(
        region.kind
      ))
    then collectionFailure("deferred formal requires an actual registered collection")
    if DigitalReductionContract.Kinds(region.kind) then
      val (left, right) = reductionSignature(region, complete = false)
      val side = role match
        case "left" => 0
        case "right" => 1
        case _ => collectionFailure("reduction whole-value formal has an invalid role")
      val expected = left.size + right.size + side
      if position != expected then
        collectionFailure("reduction formal has the wrong original position")
      return region.inputs.head
    val signature = DigitalCollectionContract.constructionArguments(
      region.kind,
      region.inputs,
      region.seed
    )
    if position < 0 || position >= signature.size || signature(position)._2 != role then
      collectionFailure("deferred formal role or position differs from its actual signature")
    if role == "accumulator" then region.seed.get
    else region.inputs(if region.seed.nonEmpty then position - 1 else position)

  private def reductionSignature(region: DigitalValueRegionRecord, complete: Boolean)
      : (Vector[KernelExpr[?]], Vector[KernelExpr[?]]) =
    if !collectionRegions.get(region.reference).contains(region) ||
      region.regionKind != DigitalRegionKind.Collection ||
      !DigitalReductionContract.Kinds(
        region.kind
      ) || region.inputs.size != 1 || region.seed.nonEmpty
    then collectionFailure("deferred reduction requires its actual registered input and region")
    val widthCount = DigitalReductionContract.widths(
      CandidateRuntime.typeDescriptor(DigitalCollectionContract.vector(region.inputs.head)._1)
    ).size
    val roles = (0 until widthCount).map(index => s"left_width:$index").toVector ++
      (0 until widthCount).map(index => s"right_width:$index") ++ Vector("left", "right")
    val invalid = region.arguments.zipWithIndex.exists:
      case ((argument, role), index) =>
        val expression = dataArgument(argument)
        val registered = Option(expressionIds.get(expression)).exists(ref =>
          collectionArguments.get(ref).contains(region -> index) &&
            expressionRegions.get(ref).contains(region)
        )
        !registered || !expression.operation.contains(DigitalCollectionContract.Argument) ||
        expression.operands.nonEmpty || (if index < roles.size then
                                           role != roles(index) || (index < widthCount * 2 &&
                                             expression.resultDescriptor != Some(
                                               KernelTypeDescriptor("Integer")
                                             ))
                                         else
                                           !Set(
                                             "capture",
                                             "static_capture",
                                             "index_capture"
                                           )(role) ||
                                           !region.captures.valuesIterator.exists(
                                             _._2.eq(argument)
                                           ) ||
                                           (role != "capture" && expression.resultDescriptor != Some(
                                             KernelTypeDescriptor("Integer")
                                           )))
    if (complete && region.arguments.size < roles.size) || invalid then
      collectionFailure("reduction formal role or position differs from its original signature")
    (
      region.arguments.take(widthCount).map(value => dataArgument(value._1)).toVector,
      region.arguments.slice(widthCount, widthCount * 2).map(value =>
        dataArgument(value._1)
      ).toVector
    )

  private def reductionFormalType(region: DigitalValueRegionRecord, role: String)
      : KernelTypeDescriptor =
    val (left, right) = reductionSignature(region, complete = false)
    val initial =
      CandidateRuntime.typeDescriptor(DigitalCollectionContract.vector(region.inputs.head)._1)
    val formals = if role == "left" then left
    else if role == "right" then right
    else collectionFailure("reduction whole-value formal has an invalid role")
    if formals.size != DigitalReductionContract.widths(initial).size then
      collectionFailure("reduction whole-value formal lost its original width arguments")
    var index = 0
    DigitalReductionContract.mapWidths(initial): _ =>
      val result = formals(index).asInstanceOf[Expr[Integer]]
      index += 1
      result

  private def collectionFormalType(source: Expr[?], role: String): KernelTypeDescriptor =
    if role == "accumulator" then DigitalCollectionContract.descriptor(source)
    else CandidateRuntime.typeDescriptor(DigitalCollectionContract.vector(source)._1)

  private def requireRegisteredCollectionOperand(value: Expr[?], owner: Long): Unit =
    val actual = valueReference(value) match
      case reference: ExpressionRef =>
        reference.module == owner && expressionValues.get(reference).exists: original =>
          original.eq(value) || generatedMemberRecord(original).exists: member =>
            value match
              case signal: Signal[?] => signal.dataType.eq(member.dataType)
              case register: Register[?] => register.dataType.exists(_.eq(member.dataType))
              case _ => false
      case reference: DeclarationRef =>
        reference.module == owner && records(owner).declarations.lift(reference.index)
          .exists(_.value eq value)
    if !actual then collectionFailure("collection type operand has no exact registered identity")

  private def beforeRegionMutation(region: DigitalValueRegionRecord): Unit =
    boundaryClosureOwner.foreach(_.beforeRegionMutation(region))

  private def newCollectionArgument(
      region: DigitalValueRegionRecord,
      dataType: DataType[?],
      role: String,
      capture: Option[Expr[?]] = None,
      physicalControl: Boolean = false
  ): KernelExpr[?] =
    beforeRegionMutation(region)
    if region.arguments.size >= DigitalReductionContract.MaximumArguments then
      collectionFailure("collection signature exceeds the 4098 argument budget")
    val formal =
      Option.when(capture.isEmpty && (DigitalCollectionContract.OrdinaryKinds(region.kind) ||
        (DigitalReductionContract.Kinds(region.kind) && Set("left", "right")(role))))(
        collectionFormalSource(region, region.arguments.size, role)
      )
    val source = capture.orElse(formal)
    source.foreach(requireRegisteredCollectionOperand(_, region.reference.module))
    // Pending container dimensions do not make an independently complete element pending.
    // The collection result still depends on the original input and rechecks this formal.
    val eagerFormal = formal.nonEmpty && !DigitalReductionContract.Kinds(
      region.kind
    ) && moduleOwnedGeneratedMemberType(
      CandidateRuntime.typeDescriptor(dataType),
      region.reference.module
    )
    val intent = if eagerFormal then None
    else
      DeferredResultType.intent(source.toVector):
        if capture.nonEmpty then DigitalCollectionContract.descriptor(source.get)
        else if DigitalReductionContract.Kinds(region.kind) then reductionFormalType(region, role)
        else collectionFormalType(source.get, role)
    if physicalControl then requirePhysicalControlCapture(dataType, region)
    else if intent.nonEmpty then requireDeferredPayloadScope(Some(region), region.reference.module)
    else if sequentialRegion(region) then
      validateSequentialValueType(dataType, region.reference.module)
    else if CandidateRuntime.typeDescriptor(dataType).kind != "Integer" then
      DigitalCombinationalContract.validateValueType(CandidateRuntime.typeDescriptor(dataType))
    val argument = new KernelExpr[Data](
      Vector.empty,
      resultType = Some(CandidateRuntime.typeDescriptor(dataType)),
      operation = Some(DigitalCollectionContract.Argument),
      typeIntent = intent
    )
    val module = records(region.reference.module)
    val scaffold = role == "induction" && region.loop.exists(_.generated.nonEmpty)
    val reference = if scaffold then newScaffoldReference(module)
    else
      val result = allocateExpressionReference(module)
      module.expressionCount += 1
      result
    collectionArguments.update(reference, region -> region.arguments.size)
    region.arguments += argument -> role
    val _ =
      captureExpression(argument, retained = Some(reference), scopeOverride = Some(Some(region)))
    argument

  /** Non-Data family capability capture uses the same ordered lexical owner as Data. */
  private def bindGeneratedFamily(
      family: GeneratedRegionRecord,
      destination: Option[DigitalValueRegionRecord],
      create: Boolean = true
  ): GeneratedRegionRecord | GeneratedFamilyArgument =
    val source = family.digitalRegion.getOrElse(
      collectionFailure("family capture has no actual generated body")
    ).parent
    if source == destination then family
    else
      destination match
        case Some(region) if region.structuralArm.nonEmpty =>
          val parent = bindGeneratedFamily(family, region.parent, create)
          region.captures.get(parent) match
            case Some((source, argument: GeneratedFamilyArgument)) if argument.family eq family =>
              boundaryClosureOwner.foreach(_.verifyRegionCaptureReuse(
                source.eq(parent) && (argument.region eq region) &&
                  region.arguments.lift(argument.position).exists((actual, role) =>
                    (actual eq argument) && role == "family_capture"
                  )
              ))
              argument
            case Some(_) => collectionFailure("family capture cannot change its actual capability")
            case None if create =>
              beforeRegionMutation(region)
              if region.arguments.size >= DigitalReductionContract.MaximumArguments then
                collectionFailure("family capture exceeds the argument budget")
              val argument = new GeneratedFamilyArgument(region, region.arguments.size, family)
              region.arguments += argument -> "family_capture"
              region.captures.update(parent, parent -> argument)
              argument
            case None =>
              collectionFailure("family capture edge was not captured during construction")
        case _ => collectionFailure("family capability escaped its exact structural lexical scope")

  /** Resolve each free edge through every intervening region. This retains actual parent values as
    * capture operands and introduces a genuine typed block argument at each isolation boundary.
    */
  private def bindCollectionValue(
      value: Expr[?],
      destination: Option[DigitalValueRegionRecord],
      owner: Long,
      create: Boolean = true,
      predicateUse: Boolean = false,
      physicalControl: Boolean = false
  ): Expr[?] = publicationProbe:
    if destination.nonEmpty then
      value match
        case expression: KernelExpr[?] if !constructionPayloadAvailable(expression) =>
          collectionFailure("C2a pending construction cannot enter a digital region")
        case _ => ()
    if create then
      val _ = validateFsmObservations(Vector(value))
    val reference = valueReference(value)
    if create && valueRegion(reference).isEmpty && destination.nonEmpty then
      Option(fsmObservations.get(value)).filter(_.future).foreach: observation =>
        if fsmActionRegion(observation.record).nonEmpty then
          FlatFsmRuntime.fail(
            "next/event observations used in actions must be obtained from their action context"
          )
    val sourceOwner = reference match
      case declaration: DeclarationRef => declaration.module
      case expression: ExpressionRef => expression.module
    if sourceOwner != owner then collectionFailure("collection value belongs to another Module")
    val sourceRegion = valueRegion(reference)
    val generatedInduction = destination.filter(_.isLoop).flatMap(_.loop.get.generated)
      .exists(region => reference == region.induction)
    if generatedInduction then dataArgument(destination.get.arguments.head._1)
    else if sourceRegion == destination then value
    else
      destination match
        case None =>
          collectionFailure("collection argument or body value escaped its lexical region")
        case Some(region) =>
          if physicalControl then
            requirePhysicalControlCapture(
              CandidateRuntime.expressionDataType(value).getOrElse(
                collectionFailure("physical capture requires an exact control type")
              ),
              region
            )
          val parentValue =
            bindCollectionValue(value, region.parent, owner, create, predicateUse, physicalControl)
          val parentReference = valueReference(parentValue)
          region.captures.get(parentReference) match
            case Some((source: Expr[?], argument: KernelExpr[?])) =>
              boundaryClosureOwner.foreach(_.verifyRegionCaptureReuse(
                source.eq(parentValue) && Option(expressionIds.get(argument)).exists(ref =>
                  collectionArguments.get(ref).exists((owner, position) =>
                    (owner eq region) && region.arguments.lift(position)
                      .exists((actual, role) =>
                        (actual eq argument) &&
                          Set("capture", "static_capture", "index_capture")(role)
                      )
                  ) &&
                    expressionRegions.get(ref).exists(_ eq region)
                )
              ))
              argument
            case Some(_) => collectionFailure("ordinary capture cannot acquire a family capability")
            case None if create =>
              beforeRegionMutation(region)
              val dataType = CandidateRuntime.expressionDataType(parentValue).getOrElse(
                collectionFailure("collection capture requires an exact type")
              )
              val integer =
                CandidateRuntime.typeDescriptor(dataType) == KernelTypeDescriptor("Integer")
              val runtimeInteger = integer && runtimeSequentialInteger(parentValue)
              if runtimeInteger && (predicateUse || !sequentialRegion(region)) then
                collectionFailure(
                  "runtime Integer capture cannot supply static parameter or collection facts"
                )
              val isStatic = integer && !runtimeInteger
              val proceduralIndex = isStatic && loopAffine(parentValue)
                .exists(_._1.loop.get.generated.isEmpty)
              val pending = DeferredResultType.pending(parentValue)
              if pending then requireDeferredPayloadScope(Some(region), owner)
              if !isStatic && !pending && !physicalControl then
                validateShapeType(dataType, owner, cardinality = true, storageContext = true)
              else if isStatic && (!proceduralIndex || predicateUse) then
                val analysis = new StructuralBoundAnalysis(owner, signed64Literals = true)
                if predicateUse || (admitsPredicateStatics(region) && reductionExact.isEmpty &&
                    reductionDomains.isEmpty && !reductionChecking)
                then
                  val _ = analysis.predicateBound(parentValue.asInstanceOf[Expr[Integer]])
                else
                  val _ = analysis(parentValue.asInstanceOf[Expr[Integer]])
              region.fsmAction.foreach: header =>
                val machine = flatFsms(header.machine)
                if machine.captureCount >= 8192 then
                  FlatFsmRuntime.fail("machine action captures exceed 8192")
                machine.captureCount += 1
              region.switchArm.foreach: header =>
                val switch = enumSwitches(header.switch)
                if switch.captureCount >= 8192 then
                  FlatFsmRuntime.fail("switch captures exceed 8192")
                switch.captureCount += 1
              val argument = newCollectionArgument(
                region,
                dataType,
                if proceduralIndex then "index_capture"
                else if isStatic then "static_capture" else "capture",
                capture = Some(parentValue),
                physicalControl = physicalControl
              )
              region.captures.update(parentReference, parentValue -> argument)
              if !isStatic && !pending && !physicalControl then
                bindCollectionType(CandidateRuntime.typeDescriptor(dataType), Some(region), owner)
              argument
            case None => collectionFailure("collection free edge has no explicit capture argument")

  private def mapCollectionType(
      descriptor: KernelTypeDescriptor,
      region: Option[DigitalValueRegionRecord],
      owner: Long,
      create: Boolean
  ): KernelTypeDescriptor =
    closureCharge(1L)
    def map(value: Any): Any = closureDescriptorVisit:
      value match
        case expression: Expr[?]
            if CandidateRuntime.expressionDataType(expression)
              .exists(CandidateRuntime.typeDescriptor(_) == KernelTypeDescriptor("Integer")) =>
          // Module statics retain their normal parameter/reference semantics. Only crossing a
          // collection isolation boundary introduces a genuine static capture argument.
          if valueRegion(valueReference(expression)).nonEmpty then
            bindCollectionValue(expression, region, owner, create)
          else expression
        case dataType: DataType[?] => CandidateRuntime.dataTypeFromDescriptor(
            mapCollectionType(CandidateRuntime.typeDescriptor(dataType), region, owner, create)
          )
        case field: StructField[?] => StructField(
            field.name,
            CandidateRuntime.dataTypeFromDescriptor(mapCollectionType(
              CandidateRuntime.typeDescriptor(field.dataType),
              region,
              owner,
              create
            ))
          )
        case values: Seq[?] => closureElements(values).map(map).toVector
        case other => other
    descriptor.copy(arguments = closureElements(descriptor.arguments).map(map).toVector)

  private def bindCollectionType(
      descriptor: KernelTypeDescriptor,
      region: Option[DigitalValueRegionRecord],
      owner: Long
  ): Unit =
    val _ = mapCollectionType(descriptor, region, owner, create = true)

  /** A homogeneous nested collection may export a type formula, never a local SSA identity. Rebuild
    * only ordinary static arithmetic through explicit capture edges in its parent scope.
    */
  private def exportCollectionType(
      descriptor: KernelTypeDescriptor,
      region: DigitalValueRegionRecord
  ): KernelTypeDescriptor =
    val memo = mutable.HashMap.empty[KernelExpr[?], Expr[Integer]]
    var work = 0
    def exportWidth(value: Dimension, depth: Int): Dimension = value match
      case expression: KernelExpr[?]
          if Option(expressionIds.get(expression))
            .flatMap(expressionRegions.get).contains(region) =>
        if depth > 512 || work >= 65536 then
          collectionFailure("collection type export exceeds static work budget")
        memo.getOrElseUpdate(
          expression, {
            work += 1
            val source = region.captures.valuesIterator.find(_._2 eq expression).map(_._1)
            source match
              case Some(value) => value.asInstanceOf[Expr[Integer]]
              case None
                  if expression.operation.exists(IterationDomain.staticArithmetic(_).nonEmpty) =>
                CandidateRuntime.integerExpr(
                  expression.operation.get,
                  expression.operands.map(value =>
                    exportWidth(value.asInstanceOf[Expr[Integer]], depth + 1)
                      .asInstanceOf[Expr[Integer]]
                  )*
                )
              case _ =>
                collectionFailure("local collection width has no exportable static definition")
          }
        )
      case other => other
    DigitalReductionContract.mapWidths(descriptor)(exportWidth(_, 0))

  private def renderCollectionType(
      dataType: DataType[?],
      owner: Long,
      region: Option[DigitalValueRegionRecord]
  ): String =
    renderType(
      CandidateRuntime.dataTypeFromDescriptor(mapCollectionType(
        CandidateRuntime.typeDescriptor(dataType),
        region,
        owner,
        create = false
      )),
      owner
    )

  private def collectionOperandPath(
      value: Any,
      region: Option[DigitalValueRegionRecord],
      owner: Long
  )
      : Option[String] = publicationProbe:
    value match
      case expression: Expr[?] =>
        pathOf(bindCollectionValue(expression, region, owner, create = false))
      case _ => pathOf(value)

  /** Escapes are checked on all construction consumers, including assignment/initializer paths that
    * do not otherwise enter the digital expression closure.
    */
  private def validateCollectionDependencies(values: Vector[Any]): Unit =
    val _ = validateFsmObservations(values)
    val pending = mutable.ArrayDeque.from(values)
    while pending.nonEmpty do
      pending.removeLast() match
        case value: Expr[?] =>
          Option(expressionIds.get(value)).flatMap(expressionRegions.get).foreach: _ =>
            val _ = bindCollectionValue(value, activeValueRegion, currentModule.handle)
        case view: InterfaceTerminalView[?, ?] =>
          val _ = requireConservativeInterfaceScope(view.endpoint)
          val reference = Option(declarationIds.get(view)).getOrElse(
            fail("NODAL-INTERFACE-058-ANALOG", "conservative view belongs to another construction")
          )
          requireGeneratedDeclarationScope(reference)
        case values: Seq[?] => pending ++= values
        case Some(value) => pending.append(value)
        case _ => ()

  /** Follow only the actual induction and independently checked analysis-static offsets. Arithmetic
    * and overflow stay in IterationDomain; no parameter default or width formal is an index proof.
    */
  private def loopAffine(
      value: Any,
      depth: Int = 0
  ): Option[(DigitalValueRegionRecord, Vector[(String, Dimension)])] =
    if depth > 128 then collectionFailure("iteration selector exceeds the depth budget")
    value match
      case expression: KernelExpr[?] =>
        val reference = Option(expressionIds.get(expression))
        val induction = reference.flatMap(collectionArguments.get).collect:
          case (region, 0) if region.isLoop => region
        val generated = activeLoop.filter(_.loop.get.generated.exists(region =>
          reference.contains(region.induction)
        ))
        val captured = reference.flatMap(collectionArguments.get).flatMap: (region, _) =>
          region.captures.valuesIterator.find(_._2 eq expression).map(_._1)
        induction.orElse(generated) match
          case Some(region) => Some(region -> Vector.empty)
          case None if captured.nonEmpty => loopAffine(captured.get, depth + 1)
          case None
              if expression.operands.size == 2 &&
                Set("analog_add", "analog_sub").contains(expression.operation.getOrElse("")) =>
            val left = loopAffine(expression.operands(0), depth + 1)
            val right = loopAffine(expression.operands(1), depth + 1)
            def combine(
                loop: (DigitalValueRegionRecord, Vector[(String, Dimension)]),
                offset: Any,
                subtract: Boolean
            )
                : Option[(DigitalValueRegionRecord, Vector[(String, Dimension)])] =
              val dimension = offset.asInstanceOf[Dimension]
              val _ = new StructuralBoundAnalysis(
                loop._1.reference.module,
                "NODAL-LOOP-055-002",
                "iteration offset",
                signed64Literals = true
              )(dimension)
              Some(loop._1 -> (loop._2 :+ ((if subtract then "analog_sub"
                                            else "analog_add") -> dimension)))
            (left, right) match
              case (Some(loop), None) => combine(
                  loop,
                  expression.operands(1),
                  expression.operation.contains("analog_sub")
                )
              case (None, Some(loop)) if expression.operation.contains("analog_add") =>
                combine(loop, expression.operands(0), false)
              case (None, None) => None
              case _ =>
                collectionFailure("iteration selector requires one induction in affine order")
          case _ => None
      case _ => None

  /** Project only where the actual handle and ordinal definitions dominate. The resulting Data
    * value, not the non-Data family capability, crosses isolated callbacks by ordinary captures.
    */
  private def generatedMemberScope(
      expression: KernelExpr[?],
      member: GeneratedAreaMemberRecord
  ): Option[DigitalValueRegionRecord] =
    val firstFamily = member.selectionPrefix.headOption.map(_.family).getOrElse(member.family)
    val familyBody = firstFamily.digitalRegion.getOrElse(
      collectionFailure("generated member has no actual digital family body")
    )
    val ancestry = mutable.ArrayBuffer.empty[Option[DigitalValueRegionRecord]]
    var current = activeValueRegion
    while current.nonEmpty do
      if ancestry.contains(current) || ancestry.size >= 128 then
        collectionFailure("generated member lexical ancestry is cyclic or excessive")
      ancestry += current
      current = current.get.parent
    ancestry += None
    val dependencies = familyBody.parent +: expression.operands.collect:
      case value: Expr[?] => valueRegion(valueReference(value))
    val positions = dependencies.map(ancestry.indexOf)
    if positions.exists(_ < 0) then
      collectionFailure("generated member handle or ordinal escaped its lexical scope")
    val candidates = ancestry.take(positions.min + 1).reverse
    def capturable(scope: Option[DigitalValueRegionRecord]): Boolean =
      var current = scope
      var valid = true
      while current != familyBody.parent && valid do
        current match
          case Some(region) if region.structuralArm.nonEmpty => current = region.parent
          case _ => valid = false
      valid
    val destination = candidates.find(scope =>
      capturable(scope) &&
        generatedOrdinalValid(member.family, member.ordinal, scope, member.selectionPrefix)
    )
      .getOrElse(collectionFailure("generated member has no dominating structural existence scope"))
    val _ = bindGeneratedFamily(firstFamily, destination)
    destination

  private def collectionExpressionScope(
      expression: KernelExpr[?],
      retained: Option[ExpressionRef]
  ): Option[DigitalValueRegionRecord] =
    val current = activeValueRegion
    if current.isEmpty then
      validateCollectionDependencies(expression.operands)
      None
    else
      val operation = expression.operation.getOrElse("")
      val generatedDestination = generatedMemberRecord(expression).map(member =>
        generatedMemberScope(expression, member)
      )
      if expression.typeIntent.nonEmpty then
        val _ =
          requireDeferredPayloadScope(generatedDestination.getOrElse(current), currentModule.handle)
        if !expression.resultDescriptor.exists(DigitalCombinationalContract.isValueTypeKind) then
          collectionFailure("deferred lexical result requires a finite digital payload kind")
      val generatedAnalog =
        current.exists(region => region.isLoop && region.loop.get.generated.nonEmpty) &&
          (analogStack.nonEmpty || AnalogProceduralConstruction.generatedProcedureActive ||
            expression.resultDescriptor.exists(_.kind == "Real"))
      val knownArgument = retained.exists(collectionArguments.contains) &&
        operation == DigitalCollectionContract.Argument
      val staticInteger = expression.resultDescriptor.contains(KernelTypeDescriptor("Integer")) &&
        !knownArgument && (expression.literal.exists(_.kind == "integer") ||
          IterationDomain.staticArithmetic(operation).nonEmpty)
      val knownCollection = retained.exists(collectionRegions.contains) &&
        operation == DigitalCollectionContract.Operation
      val knownConditional = retained.exists(structuralIfs.contains) &&
        operation == StructuralIfRuntime.Operation
      val behavioralPredicate = operation == SequentialRuntime.PredicateOperation
      val knownGeneratedMember = generatedDestination.nonEmpty
      val pure = expression.literal.nonEmpty || operation.startsWith(DigitalEnumContract.Prefix) ||
        DigitalScalarContract.Operations.contains(operation) ||
        operation == DigitalMaterializationContract.Operation ||
        DigitalCombinationalContract.Operations.contains(operation) ||
        DigitalAggregateContract.Operations.contains(operation) ||
        DigitalCombinationalContract.ReachableBooleanOperations.contains(operation) ||
        Set("shape_index", "shape_view", "shape_slice").contains(operation) ||
        knownArgument || knownCollection || knownConditional || behavioralPredicate || knownGeneratedMember ||
        (operation == DigitalInterfaceContract.OrdinaryReadOperation && expression.interfaceMember.nonEmpty) || FlatFsmRuntime.Operations(
          operation
        )
      val rangeAnalog =
        current.exists(region => region.isLoop && region.loop.get.generated.nonEmpty) &&
          !pure && !staticInteger && !knownArgument
      if generatedAnalog || rangeAnalog then
        validateGeneratedDependencies(expression.operands)
        None
      else
        if !pure && !staticInteger then
          requireNoCollectionEffect(s"unsupported hardware expression '$operation'")
          requireLoopEffect(s"unsupported hardware expression '$operation'")
          collectionFailure(s"unsupported iteration expression '$operation'")
        if !staticInteger && !knownArgument && expression.typeIntent.isEmpty then
          val dataType = expression.resultDescriptor.getOrElse(
            collectionFailure("region body expression requires an exact type")
          )
          try
            if current.exists(sequentialRegion) then
              validateSequentialValueType(
                CandidateRuntime.dataTypeFromDescriptor(dataType),
                currentModule.handle
              )
            else DigitalCombinationalContract.validateValueType(dataType)
          catch
            case failure: ConstructionException =>
              rejectCollection(
                "NODAL-COLLECTION-055-006",
                s"unsupported region body value: ${failure.diagnostic.message}",
                current.map(value => expressionPath(value.reference))
              )
        val affine = if staticInteger then loopAffine(expression) else None
        if staticInteger && affine.isEmpty && expression.operands.exists(loopAffine(_).nonEmpty) &&
          !activeLoop.exists(_.loop.exists(_.generated.nonEmpty))
        then
          collectionFailure("iteration index supports only affine add/sub with a static offset")
        val localStaticDependency = expression.operands.collect { case value: Expr[?] => value }
          .exists(value => valueRegion(valueReference(value)).nonEmpty)
        val moduleStatic = staticInteger && !localStaticDependency && affine.isEmpty &&
          (!current.exists(_.isLoop) || current.exists(_.loop.exists(_.generated.nonEmpty)))
        val destination = generatedDestination.getOrElse(if moduleStatic then None else current)
        if !knownArgument && expression.typeIntent.isEmpty then
          expression.resultDescriptor.foreach(bindCollectionType(
            _,
            destination,
            currentModule.handle
          ))
        val runtimeOperands =
          if Set("shape_index", "shape_view", "shape_slice").contains(operation) then
            expression.operands.take(1)
          else expression.operands
        runtimeOperands.collect { case value: Expr[?] => value }.foreach: value =>
          val _ = bindCollectionValue(value, destination, currentModule.handle)
        destination

  private def newLoopRegion(header: DigitalLoopHeader): DigitalValueRegionRecord =
    if collectionStack.size >= 128 || collectionRegions.size + loopRegions.size >= 65536 then
      collectionFailure("iteration region inventory exceeds its finite budget")
    val module = currentModule
    val reference = if header.generated.nonEmpty then newScaffoldReference(module)
    else
      val result = allocateExpressionReference(module)
      module.expressionCount += 1
      result
    val region = new DigitalValueRegionRecord(
      reference,
      if header.generated.exists(_.areaFamily) then activeValueRegion else None,
      "bounded_loop",
      Vector.empty,
      None,
      Some(header)
    )
    loopRegions.update(reference, region)
    if header.generated.isEmpty then
      semanticOrigin.captureExpression(module.handle, reference.index, region, Vector.empty)
    collectionStack += region
    val _ = newCollectionArgument(region, Integer, "induction")
    region

  def captureDigitalLoop(bound: LoopBound)(body: Expr[Integer] => Unit): Unit =
    requireNoCollectionEffect("bounded loop")
    if activeLoop.nonEmpty || activeGeneratedRegion.nonEmpty || analogStack.nonEmpty ||
      analogSemanticContext.nonEmpty
    then
      collectionFailure("nested or analog-context digital loops are outside the finite profile")
    val (upper, maximum): (Dimension, Option[Int]) = bound match
      case LoopBound.Static(value) =>
        if value < 0 then collectionFailure("bounded loop requires a non-negative trip count")
        value -> None
      case LoopBound.Symbolic(value, maximum) => value -> Some(maximum)
    val analysis = new StructuralBoundAnalysis(
      currentModule.handle,
      "NODAL-LOOP-055-001",
      "bounded loop"
    )
    val count = analysis(upper)
    if count.bounds.lower < 0 then collectionFailure("bounded loop count may be negative")
    val domain = IterationDomain.boundedDigital(
      IterationDomain.Bounds(0, 0),
      count.bounds,
      IterationDomain.Bounds(1, 1),
      maximum
    )
      .fold(problem => collectionFailure(problem.message), identity)
    count.parameters.foreach(markStructuralParameter)
    val region = newLoopRegion(DigitalLoopHeader(
      0,
      upper,
      1,
      maximum,
      domain,
      nextEffectOrdinal()
    ))
    try body(region.arguments.head._1.asInstanceOf[Expr[Integer]])
    catch
      case failure: Throwable =>
        poisonConstructor(failure, expressionPath(region.reference))
        scala.util.Failure[Nothing](failure).get
    finally
      val removed = collectionStack.remove(collectionStack.size - 1)
      if removed ne region then collectionFailure("loop lexical scope stack is corrupt")

  private def validateDigitalLoopDomain(region: DigitalValueRegionRecord): Unit =
    val header = region.loop.get
    val analysis = new StructuralBoundAnalysis(
      region.reference.module,
      "NODAL-LOOP-055-001",
      "bounded loop"
    )
    if region.structuralGenerate then
      val generated = header.generated.getOrElse(
        collectionFailure("structural loop header lacks its actual Generate owner")
      )
      if generated.owner != region.reference.module ||
        !records(generated.owner).generatedRegions.exists(_ eq generated) ||
        !generated.digitalRegion.contains(region) || header.lower != generated.lower ||
        header.upperExclusive != generated.upperExclusive || header.step != generated.step ||
        header.maximum != generated.maximum
      then collectionFailure("structural loop header differs from its actual family")
      val inputs = Vector(header.lower, header.upperExclusive, header.step).map(analysis(_))
      val bounds = inputs.map(_.bounds)
      val checked = IterationDomain.structural(
        bounds(0),
        bounds(1),
        bounds(2),
        header.maximum,
        identicalBounds = inputs(0).identity == inputs(1).identity ||
          (bounds(0).lower == bounds(0).upper && bounds(1).lower == bounds(1).upper &&
            bounds(0).lower >= bounds(1).upper)
      ).fold(problem => collectionFailure(problem.message), identity)
      if checked != header.domain || checked != generated.domain then
        collectionFailure("structural loop domain differs from its canonical family proof")
    else
      if analysis(header.step).parameters.nonEmpty then
        collectionFailure("digital iteration step must be a parameter-independent fixed DAG")
      val _ = IterationDomain.boundedDigital(
        header.domain.lower,
        header.domain.upperExclusive,
        header.domain.step,
        header.maximum,
        analysis.sameIdentity(header.lower, header.upperExclusive)
      )
        .fold(problem => collectionFailure(problem.message), identity)

  private def retainedLoopRegions: Set[DigitalValueRegionRecord] =
    closureCharge(operations.size.toLong + expressionRegions.size.toLong + loopRegions.size.toLong)
    val usedRegions = operations.iterator.flatMap(_.digitalRegion).toSet ++
      expressionRegions.iterator.collect:
        case (reference, region)
            if region.isLoop && !collectionArguments.contains(reference) &&
              expressionValues.get(reference).exists(value =>
                requireConstructionPayload(value) && !value.resultDescriptor.exists(dataType =>
                  Set("Integer", "Real").contains(dataType.kind)
                ) &&
                  (DigitalValueConstruction.isDigitalValue(value) || shapeIndices.contains(
                    reference
                  ) ||
                    shapeViews.contains(reference))
              ) => region
    loopRegions.valuesIterator.filter(region =>
      !region.structuralGenerate && (region.loop.get.generated.isEmpty || usedRegions(region))
    ).toSet

  private def loopSnapshots(): Vector[KernelDigitalLoopSnapshot] =
    val retained = retainedLoopRegions
    loopRegions.valuesIterator.filter(retained).map: region =>
      validateDigitalLoopDomain(region)
      val header = region.loop.get
      KernelDigitalLoopSnapshot(
        expressionPath(region.reference),
        modulePath(region.reference.module),
        generatedBound(header.lower),
        generatedBound(header.upperExclusive),
        generatedBound(header.step),
        header.maximum,
        header.domain.maximumTripCount,
        region.captures.valuesIterator.map(value => pathOf(value._1).get).toVector,
        region.arguments.toVector.map((argument, role) =>
          KernelDigitalRegionArgument(
            pathOf(argument).get,
            renderCollectionType(
              CandidateRuntime.expressionDataType(dataArgument(argument)).get,
              region.reference.module,
              Some(region)
            ),
            role
          )
        ),
        header.sourceOrdinal,
        header.generated.map(generatedRegionPath)
      )
    .toVector

  private def iterationIndexSnapshots(): Vector[KernelIterationIndexSnapshot] =
    val retained = retainedLoopRegions
    iterationIndices.toVector.filter(reference =>
      // Snapshot after construction: follow the retained lexical owner, not the transient
      // active Generate stack used while admitting a newly authored selector.
      Iterator.iterate(Option(expressionRegions(reference)))(_.flatMap(_.parent))
        .take(129).collectFirst { case Some(region) if region.isLoop => region }.exists(retained)
    ).map: reference =>
      val expression = expressionValues(reference)
      val region = expressionRegions(reference)
      KernelIterationIndexSnapshot(
        expressionPath(reference),
        modulePath(reference.module),
        expressionPath(region.reference),
        if expression.operation.contains("analog_add") then "add" else "sub",
        expression.operands.map(operand =>
          collectionOperandPath(
            operand,
            Some(region),
            reference.module
          ).getOrElse(collectionFailure("iteration operand has no identity"))
        )
      )

  private def structuralFailure(message: String): Nothing =
    val failure = new ConstructionException(KernelDiagnostic("NODAL-STRUCTURAL-055-002", message))
    poisonConstructor(failure, "structural if")
    scala.util.Failure[Nothing](failure).get

  private def regionArguments(region: DigitalValueRegionRecord)
      : Vector[KernelDigitalRegionArgument] =
    region.arguments.toVector.map: (argument, role) =>
      KernelDigitalRegionArgument(
        pathOf(argument).get,
        argument match
          case _: GeneratedFamilyArgument => "GeneratedFamily"
          case value: KernelExpr[?] => renderCollectionType(
              CandidateRuntime.expressionDataType(value).get,
              region.reference.module,
              Some(region)
            ),
        role
      )

  /** The pre-sealing boundary query consumes only actual static/family dependencies. Payload
    * arguments remain on their original records and enter the complete snapshot after sealing. Keep
    * original argument identities even when the projected signature has sparse indices.
    */
  private def staticRegionSignature(region: DigitalValueRegionRecord)
      : (Vector[String], Vector[KernelDigitalRegionArgument]) =
    val captures = region.captures.valuesIterator.toVector
    val suffix = region.arguments.takeRight(captures.size)
    if suffix.size != captures.size || suffix.zip(captures).exists:
        case ((argument, _), (_, captured)) => argument.ne(captured)
    then collectionFailure("static query projection lost its exact ordered capture pairing")
    def retained(argument: DigitalRegionArgument): Boolean = argument match
      case _: GeneratedFamilyArgument => true
      case value: KernelExpr[?] =>
        requireConstructionPayload(
          value
        ) && value.resultDescriptor.contains(KernelTypeDescriptor("Integer"))
    val selected = region.arguments.toVector.filter((argument, _) => retained(argument))
    val arguments = selected.map: (argument, role) =>
      KernelDigitalRegionArgument(
        pathOf(argument).get,
        argument match
          case _: GeneratedFamilyArgument => "GeneratedFamily"
          case _: KernelExpr[?] => "Integer",
        role
      )
    val sources = captures.collect:
      case (source, argument) if retained(argument) => pathOf(source).get
    sources -> arguments

  def captureStaticPredicate(
      operation: String,
      operands: Vector[Any],
      structural: Boolean = true
  ): StaticPredicate =
    try
      if staticPredicates.size >= 65536 then
        structuralFailure("static predicate DAG exceeds 65536 nodes")
      val module = currentModule
      val comparison = Set("eq", "ne", "lt", "le", "gt", "ge").contains(operation)
      val logic = Set("and", "or", "not").contains(operation)
      if ((!comparison && !logic) || operands.size != (if operation == "not" then 1 else 2)) then
        structuralFailure("static predicate has an unknown operation or wrong arity")
      val bound = if comparison then
        val analysis = new StructuralBoundAnalysis(
          module.handle,
          "NODAL-STRUCTURAL-055-003",
          "structural predicate",
          signed64Literals = true
        )
        operands.map:
          case value: Expr[?]
              if CandidateRuntime.expressionDataType(value).exists(dataType =>
                CandidateRuntime.typeDescriptor(dataType) == KernelTypeDescriptor("Integer")
              ) =>
            val facts = analysis.predicateBound(value.asInstanceOf[Expr[Integer]])
            if structural then facts.toVector.flatMap(_.parameters).foreach(markStructuralParameter)
            bindCollectionValue(value, activeValueRegion, module.handle, predicateUse = true)
          case _ =>
            structuralFailure("comparison operands must be owned static Integer expressions")
      else
        operands.map:
          case value: StaticPredicate =>
            val reference = Option(expressionIds.get(value)).getOrElse(
              structuralFailure("predicate operand is detached from construction")
            )
            if reference.module != module.handle || !staticPredicates.contains(reference) ||
              expressionRegions.get(reference) != activeValueRegion
            then
              structuralFailure("predicate operand escaped its exact lexical region")
            value
          case _ => structuralFailure("static logic requires actual predicate operands")
      val predicate = new StaticPredicate(operation, bound)
      val reference =
        captureExpression(predicate, bound, scopeOverride = Some(activeValueRegion)).get
      staticPredicates.update(reference, predicate)
      predicate
    catch
      case failure: Throwable =>
        poisonConstructor(failure, "static predicate")
        scala.util.Failure[Nothing](failure).get

  def captureStructuralIf(predicate: StaticPredicate, hasResult: Boolean)(
      whenTrue: () => Option[Expr[?]],
      whenFalse: () => Option[Expr[?]]
  ): Option[Expr[?]] =
    if activeBehavioral.nonEmpty && !hasResult then
      behavioralFailure("effectful structural if is not permitted inside a behavioral update")
    val module = currentModule
    val parent = activeValueRegion
    try
      if analogStack.nonEmpty || analogSemanticContext.nonEmpty ||
        collectionStack.size >= 128 || structuralIfs.size * 2 + collectionRegions.size + loopRegions.size >= 65536
      then
        structuralFailure("structural if requires a bounded digital construction context")
      activeLoop.filter(_.loop.get.generated.nonEmpty).foreach: region =>
        if !region.structuralGenerate then
          beforeRegionMutation(region)
          region.structuralGenerate = true
      val predicateRef = Option(expressionIds.get(predicate)).getOrElse(
        structuralFailure("structural if predicate has no construction identity")
      )
      if predicateRef.module != module.handle || !staticPredicates.contains(predicateRef) ||
        expressionRegions.get(predicateRef) != parent
      then
        structuralFailure("structural if predicate belongs to another lexical scope")
      val reference = allocateExpressionReference(module)
      module.expressionCount += 1
      val ordinal = parent match
        case Some(region) =>
          val result = region.assignmentCount
          region.assignmentCount += 1
          result
        case None => nextEffectOrdinal()
      def arm(whenTrue: Boolean): DigitalValueRegionRecord =
        val value = new DigitalValueRegionRecord(
          newScaffoldReference(module),
          parent,
          "structural_arm",
          Vector.empty,
          None,
          structuralArm = Some(StructuralArmHeader(reference, whenTrue))
        )
        structuralArms.update(value.reference, value)
        value
      val record =
        new StructuralIfRecord(reference, parent, predicate, arm(true), arm(false), ordinal)
      structuralIfs.update(reference, record)
      if !hasResult then
        semanticOrigin.captureExpression(module.handle, reference.index, record, Vector(predicate))
      def capture(region: DigitalValueRegionRecord, callback: () => Option[Expr[?]])
          : Option[KernelTypeDescriptor] =
        collectionStack += region
        val yielded = try
          callback().map(value => bindCollectionValue(value, Some(region), module.handle))
        finally
          val removed = collectionStack.remove(collectionStack.size - 1)
          if removed ne region then structuralFailure("structural arm lexical stack is corrupt")
        if yielded.nonEmpty != hasResult then
          structuralFailure("structural arm yield arity differs from its result")
        beforeRegionMutation(region)
        region.yielded = yielded
        yielded.map: value =>
          val descriptor = DigitalCollectionContract.descriptor(value)
          if DeferredResultType.pending(value) then
            val _ = requireDeferredValueScope(Some(region), module.handle)
            if !DigitalCombinationalContract.isValueTypeKind(descriptor) then
              structuralFailure("deferred structural result requires a finite digital payload kind")
            descriptor
          else
            DigitalCombinationalContract.validateValueType(descriptor)
            val exported = exportCollectionType(descriptor, region)
            validateShapeType(
              CandidateRuntime.dataTypeFromDescriptor(exported),
              module.handle,
              cardinality = true,
              storageContext = true
            )
            exported
      // Deterministic authored order, once per template, never per lane or parameter setting.
      val trueType = capture(record.trueArm, whenTrue)
      val falseType = capture(record.falseArm, whenFalse)
      val yields = record.trueArm.yielded.toVector ++ record.falseArm.yielded.toVector
      val pending = yields.exists(DeferredResultType.pending)
      if trueType.size != falseType.size || (!pending && trueType.zip(falseType).exists(
          (left, right) =>
            !DigitalEnumContract.sameType(left, right)
        ))
      then
        structuralFailure(
          "structural if alternatives must yield exactly the same complete hardware ABI"
        )
      trueType.map: dataType =>
        val intent = DeferredResultType.intent(yields):
          val finalTypes = Vector(record.trueArm, record.falseArm).map: arm =>
            val actual = DigitalCollectionContract.descriptor(arm.yielded.get)
            DigitalCombinationalContract.validateValueType(actual)
            exportCollectionType(actual, arm)
          if !DigitalEnumContract.sameType(finalTypes.head, finalTypes(1)) then
            structuralFailure(
              "structural if alternatives must yield exactly the same complete hardware ABI"
            )
          finalTypes.head
        val captures = record.trueArm.captures.valuesIterator.map(_._1).toVector ++
          record.falseArm.captures.valuesIterator.map(_._1).toVector
        beforeRegionMutation(record.trueArm)
        beforeRegionMutation(record.falseArm)
        val result = new KernelExpr[Data](
          predicate +: captures,
          resultType = Some(dataType),
          operation = Some(StructuralIfRuntime.Operation),
          typeIntent = intent
        )
        record.result = Some(result)
        val _ = captureExpression(result, retained = Some(reference))
        result
    catch
      case failure: Throwable =>
        poisonConstructor(failure, "structural if")
        scala.util.Failure[Nothing](failure).get

  def captureBehavioralComparison(
      left: Expr[Integer],
      right: Expr[Integer],
      operation: String
  ): Expr[Bool] =
    val predicate = captureStaticPredicate(operation, Vector(left, right), structural = false)
    val expression = new KernelExpr[Bool](
      Vector(predicate),
      resultType = Some(KernelTypeDescriptor("Bool")),
      operation = Some(SequentialRuntime.PredicateOperation)
    )
    val _ = captureExpression(expression)
    expression

  private def withBehavioralArm(region: DigitalValueRegionRecord)(body: => Unit): Unit =
    collectionStack += region
    try body
    finally
      behavioralChains.remove(region.reference.module -> Some(region.reference))
      val removed = collectionStack.remove(collectionStack.size - 1)
      if removed ne region then behavioralFailure("behavioral lexical stack is corrupt")

  private def newBehavioralIf(
      condition: Expr[Bool],
      root: Option[ExpressionRef]
  ): BehavioralIfRecord =
    val module = currentModule
    val parent = activeValueRegion
    var ancestor = parent
    var depth = 0
    while ancestor.nonEmpty do
      depth += 1
      if depth >= 128 then behavioralFailure("behavioral lexical depth exceeds 128")
      ancestor = ancestor.get.parent
    if CandidateRuntime.expressionDataType(condition).map(CandidateRuntime.typeDescriptor) !=
        Some(KernelTypeDescriptor("Bool"))
    then behavioralFailure("behavioral guard requires an exact owned Bool value")
    val bound = bindCollectionValue(condition, parent, module.handle).asInstanceOf[Expr[Bool]]
    val reference = allocateExpressionReference(module)
    module.expressionCount += 1
    val ordinal = parent match
      case Some(region) =>
        val result = region.assignmentCount
        region.assignmentCount += 1
        result
      case None => nextEffectOrdinal()
    def arm(selected: Boolean): DigitalValueRegionRecord =
      val region = new DigitalValueRegionRecord(
        newScaffoldReference(module),
        parent,
        "behavioral_arm",
        Vector.empty,
        None,
        behavioralArm = Some(BehavioralArmHeader(reference, selected))
      )
      behavioralArms.update(region.reference, region)
      region
    val record = new BehavioralIfRecord(
      reference,
      parent,
      bound,
      arm(true),
      arm(false),
      ordinal,
      root.getOrElse(reference)
    )
    behavioralIfs.update(reference, record)
    semanticOrigin.captureExpression(module.handle, reference.index, record, Vector(condition))
    record

  def captureBehavioralBranch(
      kind: String,
      condition: Option[Expr[Bool]],
      body: () => Unit
  ): Unit =
    try
      val ownedAncestry = collectionStack.forall: region =>
        region.reference.module == currentModule.handle &&
          (sequentialRegion(region) || region.structuralArm.nonEmpty ||
            region.loop.flatMap(_.generated).exists(generated =>
              generated.areaFamily && generationStack.exists(_ eq generated)
            ))
      if analogStack.nonEmpty || analogSemanticContext.nonEmpty ||
        activeGeneratedRegion.exists(!_.areaFamily) || !ownedAncestry ||
        collectionStack.size >= 128 ||
        behavioralIfs.size >= 65536
      then behavioralFailure("behavioral update requires a bounded, owned sequential context")
      val key = behavioralKey
      kind match
        case "when" =>
          val outerRoot = sequentialRoot(activeValueRegion)
          val record = newBehavioralIf(
            condition.getOrElse(
              behavioralFailure("when requires a predicate")
            ),
            outerRoot
          )
          withBehavioralArm(record.trueArm)(body())
          behavioralChains.update(key, new BehavioralChainCursor(record.root, record))
        case "elsewhen" | "otherwise" =>
          val cursor = behavioralChains.remove(key).getOrElse(
            behavioralFailure("elsewhen/otherwise has no immediately preceding open when chain")
          )
          withBehavioralArm(cursor.tail.falseArm):
            if kind == "otherwise" then
              if condition.nonEmpty then behavioralFailure("otherwise cannot have a predicate")
              body()
            else
              val record = newBehavioralIf(
                condition.getOrElse(
                  behavioralFailure("elsewhen requires a predicate")
                ),
                Some(cursor.root)
              )
              withBehavioralArm(record.trueArm)(body())
              cursor.tail = record
          if kind == "elsewhen" then behavioralChains.update(key, cursor)
        case _ => behavioralFailure("unknown behavioral chain arm")
    catch
      case failure: Throwable =>
        poisonConstructor(failure, "behavioral guard")
        scala.util.Failure[Nothing](failure).get

  private def behavioralIfSnapshots(): Vector[KernelBehavioralIfSnapshot] =
    behavioralIfs.valuesIterator.map: record =>
      def arm(region: DigitalValueRegionRecord): KernelStructuralArmSnapshot =
        KernelStructuralArmSnapshot(
          expressionPath(region.reference),
          region.captures.valuesIterator.map(value => pathOf(value._1).get).toVector,
          regionArguments(region),
          None
        )
      KernelBehavioralIfSnapshot(
        expressionPath(record.reference),
        modulePath(record.reference.module),
        record.parent.map(region => expressionPath(region.reference)),
        pathOf(record.predicate).get,
        arm(record.trueArm),
        arm(record.falseArm),
        record.sourceOrdinal,
        expressionPath(record.root)
      )
    .toVector

  /** Validate actual pre-edge dependencies and update-root ownership, without evaluating guards. */
  private final case class InterfaceMemberSample(endpoint: DeclarationRef, member: String)

  private def verifySequentialAssignments(
      resolved: Map[DomainRef, String],
      interfaces: DigitalInterfaceContract.Capture,
      actualRoot: Long
  ): Unit =
    publicationCharge(1L)
    val relationships = relationshipIndex()
    final case class UpdateRoot(
        identity: Either[ExpressionRef, Int],
        guards: Map[ExpressionRef, Boolean]
    )
    val roots = mutable.HashMap.empty[DeclarationRef, mutable.ArrayBuffer[UpdateRoot]]
    var updateRootProofWork = 0
    def structuralGuards(region: Option[DigitalValueRegionRecord]): Map[ExpressionRef, Boolean] =
      val result = mutable.HashMap.empty[ExpressionRef, Boolean]
      var current = region
      var depth = 0
      while current.nonEmpty do
        depth += 1
        updateRootProofWork += 1
        if depth > 128 || updateRootProofWork > 262144 then
          behavioralFailure("update-root structural proof exceeds its bounded graph")
        current.get.structuralArm.foreach(header =>
          result.update(header.conditional, header.whenTrue)
        )
        current = current.get.parent
      result.toMap
    val guardPairs: Vector[(ExpressionRef, Expr[?])] =
      publicationElements(behavioralIfs.valuesIterator).map(value =>
        value.root -> value.predicate
      ).toVector ++
        publicationElements(flatFsms.valuesIterator).flatMap(record =>
          (record.controlInputs ++ record.transitions.map(_.guard)).map(value =>
            record.reference -> value
          )
        ).toVector ++
        publicationElements(enumSwitches.valuesIterator).map(record =>
          record.root -> record.selector
        ).toVector
    val guardsByRoot = guardPairs.groupMap(_._1)(_._2)
    publicationElements(flatFsms.valuesIterator).foreach(record =>
      record.privateStorage.foreach(value =>
        roots.update(
          declarationIds.get(value),
          mutable.ArrayBuffer(UpdateRoot(Left(record.reference), Map.empty))
        )
      )
    )
    val checkedGuards = mutable.HashSet.empty[(ExpressionRef, Long, String)]
    val fsmControlCache = mutable.HashMap.empty[FlatFsmActionHeader, Vector[Any]]
    var fsmControlEdges = 0L
    def fsmControls(header: FlatFsmActionHeader): Vector[Any] =
      fsmControlCache.getOrElseUpdate(
        header, {
          val values = flatFsms(header.machine).actionSampleDependencies(header)
          fsmControlEdges += values.size
          if fsmControlEdges > 262144 then
            crossingFailure("FSM causal sample controls exceed their edge budget")
          values
        }
      )
    val causalControlCache = mutable.HashMap.empty[ExpressionRef, Vector[Any]]
    var causalControlEdges = 0L
    def causalControls(region: Option[DigitalValueRegionRecord]): Vector[Any] =
      region.map: root =>
        causalControlCache.getOrElseUpdate(
          root.reference, {
            val values = mutable.ArrayBuffer.empty[Any]
            var current = region
            var depth = 0
            while current.nonEmpty do
              depth += 1
              if depth > 128 then crossingFailure("causal sample guards exceed their depth budget")
              current.get.structuralArm.foreach(header =>
                values += structuralIfs(header.conditional).predicate
              )
              current.get.behavioralArm.foreach(header =>
                values += behavioralIfs(header.conditional).predicate
              )
              current.get.switchArm.foreach(header =>
                values += enumSwitches(header.switch).selector
              )
              current.get.fsmAction.foreach(header => values ++= fsmControls(header))
              current = current.get.parent
            causalControlEdges += values.size
            if causalControlEdges > 262144 then
              crossingFailure("cached causal sample controls exceed their edge budget")
            values.toVector
          }
        )
      .getOrElse(Vector.empty)
    val eventTriggerReferences = publicationElements(eventProcesses.iterator).map(process =>
      declarationIds.get(process.definition.trigger)
    ).toSet
    val continuousWriteCounts = mutable.HashMap.empty[DeclarationRef, Int]
    val continuousDrivers = mutable.HashMap.empty[DeclarationRef, Vector[Any]]
    publicationElements(operations.iterator).filter(operation =>
      Set("assignment", "value-connect").contains(operation.kind) && operation.values.size == 2
    ).foreach: operation =>
      val target =
        digitalAssignmentRoot(
          operation.values.head,
          operation.owner,
          allowRootRegister = true,
          generatedScope = assignmentGeneratedScope(operation)
        )
      if operation.eventProcess.isEmpty &&
        records(target.module).declarations(target.index).kind != KernelSignalKind.Register
      then
        boundDomainCharge(3L) // count lookup/update and canonical declaration lookup
        continuousWriteCounts.update(target, continuousWriteCounts.getOrElse(target, 0) + 1)
        val declaration = records(target.module).declarations(target.index)
        // A projected target carries its actual selectors; revisiting the root is cut by the
        // shared sampler's identity worklist, not by an annotation or a synthetic source.
        boundDomainCharge() // direct target: no selector value or generated cone
        val selection =
          Option.when(operation.values.head.asInstanceOf[AnyRef] ne declaration.value)(
            operation.values.head
          ).toVector
        // Under the one-assignment proof the old driver vector, selection and causal controls
        // are empty: pay three concat dispatches, RHS singleton creation/visit, map read/write
        // and the None causal-control dispatch. No variable input scan is hidden in this unit.
        boundDomainCharge(8L)
        continuousDrivers.update(
          target,
          continuousDrivers.getOrElse(target, Vector.empty) ++ Vector(operation.values(1)) ++
            selection ++ causalControls(operation.digitalRegion)
        )
    val parentInputReferences = publicationElements(instanceDigitalPorts.iterator).collect {
      case (proxy, binding)
          if binding.generated.isEmpty && records(proxy.module).declarations(
            proxy.index
          ).kind == KernelSignalKind.InstanceInput => binding.port -> proxy
    }.toMap
    publicationElements(instanceDigitalPorts.iterator).collect {
      case (reference, binding)
          if binding.generated.isEmpty &&
            records(reference.module).declarations(
              reference.index
            ).kind == KernelSignalKind.InstanceInput => reference
    }.foreach: reference =>
      if continuousWriteCounts.getOrElse(reference, 0) != 1 then
        fail(
          "NODAL-HIERARCHY-057-001",
          "child digital input requires exactly one parent driver",
          Some(declarationPath(reference))
        )
    // Interface capture is the owner of resolved connectivity and member/driver rules.
    // Feed its actual edges into this same sampler rather than constructing a CDC-only net graph.
    val declarationsByPath = publicationElements(records.valuesIterator).flatMap(module =>
      publicationElements(module.declarations)
    ).map(value =>
      publicationProbe(declarationPath(value.reference) -> value.reference)
    ).toMap
    publicationCharge(declarationsByPath.size.toLong + expressionValues.size.toLong)
    val valuesByPath = publicationElements(records.valuesIterator).flatMap(module =>
      publicationElements(module.declarations)
    ).map(value =>
      publicationProbe(declarationPath(value.reference) -> value.value)
    ).toMap ++
      publicationElements(expressionValues.iterator).map((reference, value) =>
        publicationProbe(expressionPath(reference) -> value)
      )
    interfaces.connectivity.foreach: connection =>
      declarationsByPath.get(connection.target).foreach: target =>
        val dependencies = (connection.value.toVector ++ connection.enable.toVector ++
          connection.source.toVector).map(path =>
          valuesByPath.getOrElse(
            path,
            crossingFailure("Interface dependency has no actual captured value")
          )
        )
        continuousDrivers.update(
          target,
          continuousDrivers.getOrElse(target, Vector.empty) ++ dependencies
        )
      if connection.kind == "inout-pass-through" then
        connection.source.flatMap(declarationsByPath.get).foreach: source =>
          continuousDrivers.update(
            source,
            continuousDrivers.getOrElse(source, Vector.empty) :+
              valuesByPath(connection.target)
          )
    val interfaceMembers = interfaces.interfaces.flatMap(endpoint =>
      endpoint.members.map(member => member.path -> (endpoint, member))
    ).toMap
    val memberDependencies = interfaces.memberDependencies.map(value => value.path -> value).toMap
    val regionsByPath =
      publicationElements(allValueRegions).map(region =>
        expressionPath(region.reference) -> region
      ).toMap
    val originalInterfaceInputs = publicationElements(instanceInterfacePorts.iterator).collect {
      case (proxy, binding) if binding.generated.isEmpty && binding.instance.nonEmpty =>
        binding.port -> proxy
    }.toMap
    val domainsByPath = publicationElements(records.valuesIterator).flatMap(module =>
      publicationElements(module.domains)
    ).map(domain =>
      publicationProbe(domainPath(domain.reference) -> domain)
    ).toMap
    def behavioralRoot(region: Option[DigitalValueRegionRecord]): Option[ExpressionRef] =
      sequentialRoot(region)
    // One actual dependency traversal serves ordinary samples and strict crossing-source inference.
    // Verified results cut at their role-owned domain; all sampled edges remain in the record.
    final case class SampleFacts(
        domains: Set[String],
        sources: Set[DeclarationRef | ExpressionRef],
        crossings: Set[ExpressionRef],
        pulseClassified: Boolean = false,
        externalSources: Set[DeclarationRef] = Set.empty,
        localEndpoints: Set[DomainRef] = Set.empty,
        waivers: Set[ExpressionRef] = Set.empty,
        events: Set[DigitalEventProcessRecord] = Set.empty
    )
    val sampleCache =
      mutable.HashMap.empty[
        (Long, Boolean, Boolean, Boolean, Option[DigitalEventProcessRecord]),
        IdentityHashMap[AnyRef, SampleFacts]
      ]
    var cachedFacts = 0L
    def combinedFacts(
        values: Vector[Expr[?]],
        owner: Long,
        strict: Boolean,
        inferSourceOwnership: Boolean = false
    ): SampleFacts =
      val facts = values.map(sampleFacts(_, owner, strict, inferSourceOwnership))
      SampleFacts(
        facts.iterator.flatMap(_.domains).toSet,
        facts.iterator.flatMap(_.sources).toSet,
        facts.iterator.flatMap(_.crossings).toSet,
        facts.exists(_.pulseClassified),
        localEndpoints = facts.iterator.flatMap(_.localEndpoints).toSet,
        waivers = facts.iterator.flatMap(_.waivers).toSet,
        events = facts.iterator.flatMap(_.events).toSet
      )
    def exactSourceEndpoint(facts: SampleFacts, owner: Long, actual: String): DomainRef =
      // Physical sampling facts and authored local endpoint identity are separate. In particular,
      // two requirements can bind one actual without becoming interchangeable source symbols.
      val endpoints = if facts.localEndpoints.nonEmpty then facts.localEndpoints.toVector
      else
        records(owner).domains.filter(domain =>
          resolved.get(domain.reference).contains(actual)
        ).map(_.reference).toVector
      if endpoints.size != 1 || !resolved.get(endpoints.head).contains(actual) then
        crossingFailure("crossing source requires one exact local endpoint identity")
      endpoints.head
    def sourceDomain(reference: ExpressionRef, crossing: ExecutableCrossing): String =
      crossing.sourceDomain.getOrElse:
        // Infer through source-owned ready results in the same bounded worklist. A real payload
        // or valid anchor can establish ownership even when valid is fed by this FIFO's ready.
        val facts = combinedFacts(
          crossing.sourceInputs,
          reference.module,
          strict = true,
          inferSourceOwnership = true
        )
        if facts.domains.size != 1 then
          crossingFailure(s"Cdc.${crossing.kind} input must have exactly one known source domain")
        val result = facts.domains.head
        crossing.sourceDomain = Some(result)
        crossing.sourceEndpoint = Some(exactSourceEndpoint(facts, reference.module, result))
        result
    def sampleFacts(
        value: Any,
        owner: Long,
        strict: Boolean,
        inferSourceOwnership: Boolean = false,
        allowExternalControl: Boolean = false,
        eventSampling: Option[DigitalEventProcessRecord] = None
    ): SampleFacts =
      val cache = sampleCache.getOrElseUpdate(
        (owner, strict, inferSourceOwnership, allowExternalControl, eventSampling),
        new IdentityHashMap[AnyRef, SampleFacts]()
      )
      val root = value.asInstanceOf[AnyRef]
      Option(cache.get(root)).getOrElse:
        val pending =
          mutable.ArrayDeque((value, owner, true, Vector.empty[GeneratedSymbolSelectionRecord]))
        var activeOwner = owner
        var activeDomainVisible = true
        var activeSelections = Vector.empty[GeneratedSymbolSelectionRecord]
        var selectedOccurrenceSeen = false
        val sampledMembers =
          mutable.HashSet.empty[(DeclarationRef, String, Vector[GeneratedSymbolSelectionRecord])]
        var edges = 0
        def enqueue(
            values: IterableOnce[Any],
            context: Long = activeOwner,
            domainVisible: Boolean = activeDomainVisible,
            selections: Vector[GeneratedSymbolSelectionRecord] = activeSelections
        ): Unit =
          values.iterator.foreach: next =>
            edges += 1
            if edges > 262144 then
              behavioralFailure("sequential dependency edges exceed their budget")
            pending.append((next, context, domainVisible, selections))
        val visited =
          mutable.HashMap.empty[
            (Long, Boolean, Vector[GeneratedSymbolSelectionRecord]),
            IdentityHashMap[AnyRef, java.lang.Boolean]
          ]
        val domains = mutable.HashSet.empty[String]
        val localEndpoints = mutable.HashSet.empty[DomainRef]
        def retainEndpoint(initial: DomainRef): Unit =
          var endpoint = initial
          var depth = 0
          while endpoint.module != owner && selectedRequirementBindings.contains(endpoint) do
            depth += 1
            if depth > 128 then crossingFailure("source endpoint forwarding exceeds depth budget")
            endpoint = selectedRequirementBindings(endpoint)._1
          if endpoint.module == owner && activeDomainVisible then localEndpoints += endpoint
        def retainDeclarationEndpoint(declaration: DeclarationRecord): Unit =
          localDeclarationDomain(declaration, resolved).foreach(path =>
            retainEndpoint(domainsByPath(path).reference)
          )
        val externalSources = mutable.HashSet.empty[DeclarationRef]
        val sourceRoots = mutable.HashSet.empty[DeclarationRef | ExpressionRef]
        val crossings = mutable.HashSet.empty[ExpressionRef]
        val waivers = mutable.HashSet.empty[ExpressionRef]
        val events = mutable.HashSet.empty[DigitalEventProcessRecord]
        var count = 0
        var pulseClassified = false
        while pending.nonEmpty do
          val (current, context, domainVisible, selections) = pending.removeLast()
          activeOwner = context
          activeDomainVisible = domainVisible
          activeSelections = selections
          val seen =
            visited.getOrElseUpdate(
              (context, domainVisible, selections),
              new IdentityHashMap[AnyRef, java.lang.Boolean]()
            )
          current match
            case reference: AnyRef
                if Option(seen.put(reference, java.lang.Boolean.TRUE)).isEmpty =>
              count += 1
              if count > 65536 then
                behavioralFailure("sequential dependency graph exceeds its budget")
              if strict && reference.isInstanceOf[Expr[?]] && pulseValues.containsKey(reference)
              then
                pulseClassified = true
              val declarationRef = Option(declarationIds.get(reference))
              declarationRef.foreach: declarationRef =>
                if declarationRef.module != activeOwner then
                  behavioralFailure("sequential dependency belongs to another module")
                val declaration = records(activeOwner).declarations(declarationRef.index)
                if allowExternalControl && eventTriggerReferences(declarationRef) then
                  eventFailure("event trigger cannot control an ordinary owned clock")
                val drivers = continuousDrivers.getOrElse(declarationRef, Vector.empty)
                if eventSampling.nonEmpty && (declarationRef.module != owner ||
                    declaration.domainCandidate.nonEmpty ||
                    Set(
                      KernelSignalKind.Register,
                      KernelSignalKind.Memory,
                      KernelSignalKind.InstanceInput,
                      KernelSignalKind.InstanceOutput,
                      KernelSignalKind.DigitalInout
                    )(declaration.kind))
                then
                  eventFailure(
                    "event capture has clocked, foreign or unsupported storage provenance"
                  )
                instanceDigitalPorts.get(declarationRef) match
                  case _ if eventStorage.contains(declarationRef) =>
                    val process = eventStorage(declarationRef)
                    events += process
                    if strict && !eventSampling.contains(process) then
                      eventFailure(
                        "event-owned state cannot be sampled by another process or ordinary domain"
                      )
                    sourceRoots += declarationRef
                  case Some(binding) if binding.generated.nonEmpty =>
                    // This is a view of the original occurrence, never another child definition.
                    // Keep the actual ordered selection on every work item, including the return
                    // through the child's real parent input connection. Strict CDC/control roots
                    // require a separately qualified physical-source proof and remain closed.
                    if strict || inferSourceOwnership || allowExternalControl || eventSampling.nonEmpty
                    then
                      crossingFailure(
                        "selected generated Instance sampling has unsupported CDC/control provenance"
                      )
                    selectedOccurrenceSeen = true
                    val selection = binding.generated.get
                    val selected = activeSelections :+ selection
                    if selected.size > 128 then
                      crossingFailure(
                        "selected generated Instance dependency nesting exceeds its budget"
                      )
                    if declaration.kind == KernelSignalKind.InstanceOutput then
                      enqueue(
                        Vector(records(binding.port.module).declarations(binding.port.index).value),
                        binding.port.module,
                        selections = selected
                      )
                    else
                      val proxy = parentInputReferences.getOrElse(
                        binding.port,
                        crossingFailure(
                          "selected child input has no actual authored parent connection"
                        )
                      )
                      val original = instanceDigitalPorts(proxy)
                      if original.instance != binding.instance || original.generated.nonEmpty then
                        crossingFailure("selected child input differs from its actual occurrence")
                      enqueue(
                        Vector(records(proxy.module).declarations(proxy.index).value),
                        proxy.module,
                        selections = selected
                      )
                  case Some(binding) if declaration.kind == KernelSignalKind.InstanceOutput =>
                    enqueue(
                      Vector(records(binding.port.module).declarations(binding.port.index).value),
                      binding.port.module
                    )
                  case Some(_) => enqueue(drivers)
                  case None if parentInputReferences.contains(declarationRef) =>
                    retainDeclarationEndpoint(declaration)
                    val proxy = parentInputReferences(declarationRef)
                    enqueue(
                      Vector(records(proxy.module).declarations(proxy.index).value),
                      proxy.module
                    )
                  case None =>
                    if declaration.kind == KernelSignalKind.Input &&
                      records(declarationRef.module).parentAtConstruction.nonEmpty &&
                      declaration.dataType.exists(value =>
                        isClockedDigitalType(value) && !isBoundaryCapability(value)
                      )
                    then crossingFailure("child input dependency has no actual parent connection")
                    if Set(
                        KernelSignalKind.Register,
                        KernelSignalKind.Input,
                        KernelSignalKind.DigitalInout
                      )(declaration.kind)
                    then sourceRoots += declarationRef
                    if declaration.kind != KernelSignalKind.Register then enqueue(drivers)
                    // Output annotations never certify contents; only actual input/state roots do.
                    val sourceDomain =
                      if Set(KernelSignalKind.Wire, KernelSignalKind.Output)(declaration.kind) ||
                        drivers.nonEmpty && declaration.kind != KernelSignalKind.Register
                      then None
                      else declarationDomain(declaration, resolved)
                    if (strict || declaration.kind == KernelSignalKind.Output || declaration.kind == KernelSignalKind.Wire) && drivers.isEmpty && Set(
                        KernelSignalKind.Wire,
                        KernelSignalKind.Output,
                        KernelSignalKind.DigitalInout
                      )(declaration.kind)
                    then crossingFailure("wire/output dependency has no actual driver")
                    if activeDomainVisible then domains ++= sourceDomain
                    if sourceDomain.nonEmpty then retainDeclarationEndpoint(declaration)
                    val externalControl = allowExternalControl && sourceDomain.isEmpty &&
                      declaration.kind == KernelSignalKind.Input && declaration.dataType.exists(t =>
                        CandidateRuntime.typeDescriptor(t) == KernelTypeDescriptor("Bool")
                      )
                    val eventExternal = eventSampling.nonEmpty && sourceDomain.isEmpty &&
                      declaration.kind == KernelSignalKind.Input && drivers.isEmpty
                    if eventExternal then
                      val _ =
                        eventInput(declaration.value.asInstanceOf[Expr[?]], owner, bool = false)
                    if externalControl || eventExternal then externalSources += declarationRef
                    if sourceDomain.isEmpty && !externalControl && !eventExternal &&
                      (declaration.kind == KernelSignalKind.Register ||
                        (strict && drivers.isEmpty && declaration.kind != KernelSignalKind.Parameter))
                    then crossingFailure("input dependency has unknown or unresolved source domain")
              val expressionRef = Option(expressionIds.get(reference))
              expressionRef.foreach: expressionRef =>
                if expressionRef.module != activeOwner then
                  behavioralFailure("sequential expression belongs to another module")
                collectionArguments.get(expressionRef).foreach: (region, index) =>
                  val captured = region.captures.valuesIterator.find(_._2 eq reference)
                  captured match
                    case Some(value) => enqueue(Vector(value._1))
                    case None if region.regionKind == DigitalRegionKind.Collection =>
                      val role = region.arguments(index)._2
                      if DigitalReductionContract.Kinds(region.kind) then
                        // Width formals are static metadata. A used recurrence value retains
                        // the complete represented input/seed/capture dependency envelope.
                        if role == "left" || role == "right" then
                          enqueue(region.inputs ++ region.seed ++
                            region.captures.valuesIterator.map(_._1).toVector)
                      else if region.seed.nonEmpty && index == 0 then
                        enqueue(region.inputs ++ region.seed ++
                          region.captures.valuesIterator.map(_._1).toVector)
                      else
                        enqueue(Vector(region.inputs(if region.seed.nonEmpty then 0 else index)))
                    case _ => ()
                  region.fsmAction.foreach: header =>
                    val machine = flatFsms(header.machine)
                    if index == 0 then enqueue(Vector(machine.storage))
                    else if index == 1 || index == 2 then
                      enqueue(machine.futureSampleDependencies)
                fsmResults.get(expressionRef).foreach: (machine, _) =>
                  enqueue(machine.futureSampleDependencies)
                expressionValues.get(expressionRef).flatMap(generatedMemberRecord).foreach:
                  member =>
                    // A family projection is a use of the existing storage, not a new unclocked value.
                    enqueue(
                      Vector(records(member.target.module).declarations(member.target.index).value)
                    )
              if eventSampling.nonEmpty && expressionRef.exists(reference =>
                  executableWaivers.contains(reference) || executableCrossings.contains(
                    reference
                  ) ||
                    expressionValues.get(
                      reference
                    ).exists(resetRecords.containsKey) || clockRecords.contains(
                      reference
                    ) ||
                    fsmResults.contains(reference)
                )
              then
                eventFailure(
                  "event capture cannot contain clock/reset/crossing/waiver/FSM provenance"
                )
              if activeSelections.nonEmpty && expressionRef.exists(reference =>
                  executableWaivers.contains(reference) || executableCrossings.contains(
                    reference
                  ) ||
                    expressionValues.get(
                      reference
                    ).exists(resetRecords.containsKey) || clockRecords.contains(reference)
                )
              then
                crossingFailure(
                  "selected generated Instance cone has unsupported clock/reset/crossing/waiver provenance"
                )
              expressionRef.flatMap(executableWaivers.get) match
                case Some(waiver) =>
                  waivers += expressionRef.get
                  retainEndpoint(domainRef(waiver.destination))
                  val destination = resolved.getOrElse(
                    domainRef(waiver.destination),
                    crossingFailure("waiver destination has no actual resolved domain")
                  )
                  if activeDomainVisible then domains += destination
                  // Only permission traversal cuts here. The real input still carries every
                  // physical crossing/source/Pulse fact and remains a combinational dependency.
                  enqueue(Vector(waiver.input), domainVisible = false)
                case None =>
                  expressionRef.flatMap(executableCrossings.get) match
                    case Some(crossing) =>
                      if strict && crossing.kind == "pulse" then pulseClassified = true
                      crossings += crossing.identity(expressionRef.get)
                      sourceRoots += crossing.identity(expressionRef.get)
                      if crossing.role(expressionRef.get) == "ready" then
                        if inferSourceOwnership then enqueue(crossing.sourceInputs)
                        else
                          if activeDomainVisible then
                            domains += sourceDomain(expressionRef.get, crossing)
                          crossing.sourceEndpoint.foreach(retainEndpoint)
                      else
                        retainEndpoint(domainRef(crossing.destination))
                        val destination = resolved.getOrElse(
                          domainRef(crossing.destination),
                          crossingFailure("crossing destination has no actual resolved domain")
                        )
                        if activeDomainVisible then domains += destination
                    case None => reference match
                        case expression: KernelExpr[?] =>
                          if eventSampling.nonEmpty && (expression.operation.contains(
                              "memory_read"
                            ) ||
                              !expression.resultDescriptor.exists(dataType =>
                                DigitalCombinationalContract.isValueTypeKind(dataType) ||
                                  dataType.kind == "Integer"
                              ))
                          then
                            eventFailure("event capture has analog or unsupported value provenance")
                          if strict && !DigitalValueConstruction.isDigitalValue(expression) &&
                            !expression.resultDescriptor.contains(
                              KernelTypeDescriptor("Integer")
                            ) &&
                            !expressionRef.exists(reference =>
                              shapeIndices.contains(reference) || shapeViews.contains(reference) ||
                                collectionArguments.contains(reference)
                            )
                          then crossingFailure("input dependency has unsupported source provenance")
                          if expression.interfaceMember.nonEmpty then
                            val read = expression.interfaceMember.get
                            val endpoint = Option(
                              declarationIds.get(DigitalInterfaceContract.root(read.endpoint))
                            )
                              .getOrElse(crossingFailure(
                                "ordinary member read lost its actual endpoint"
                              ))
                            if endpoint.module != activeOwner then
                              crossingFailure("ordinary member read belongs to another occurrence")
                            enqueue(Vector(InterfaceMemberSample(endpoint, read.member)))
                          else if expression.operation.contains("memory_read") then
                            sourceRoots += expressionRef.get
                            val ports = expression.operands.collect {
                              case port: MemoryPortCapture =>
                                port
                            }
                            // Synchronous reads cut address provenance; asynchronous reads retain it.
                            // Every address also has its independent memory-domain sample below.
                            if ports.exists(_.memory.readLatency == 0) then
                              enqueue(expression.operands)
                            else enqueue(ports)
                          else
                            expressionRef.flatMap(collectionRegions.get) match
                              case Some(region) =>
                                // Match the canonical collection graph: only the actual yielded
                                // body and its used lane/capture arguments are runtime inputs.
                                enqueue(Vector(region.yielded.getOrElse(
                                  collectionFailure("sampled collection has no actual yield")
                                )))
                              case None => enqueue(expression.operands)
                        case sample: InterfaceMemberSample =>
                          if sample.endpoint.module != activeOwner then
                            crossingFailure(
                              "member dependency belongs to another actual occurrence"
                            )
                          if sampledMembers.add((sample.endpoint, sample.member, activeSelections))
                          then
                            val key = s"${declarationPath(sample.endpoint)}.${sample.member}"
                            val (endpoint, member) = interfaceMembers.getOrElse(
                              key,
                              crossingFailure(
                                "member dependency lost its exact complete endpoint inventory"
                              )
                            )
                            if member.protocol != "plain" || member.mode.nonEmpty ||
                              !Set("in", "out", "observe")(member.access)
                            then
                              crossingFailure(
                                "ordinary read cannot borrow protocol or capability provenance"
                              )
                            val dependency = memberDependencies.getOrElse(
                              key,
                              crossingFailure("ordinary member has no canonical dependency result")
                            )
                            dependency.effectRegions.foreach: path =>
                              val region = regionsByPath.getOrElse(
                                path,
                                crossingFailure("member driver lost its actual effect region")
                              )
                              if region.reference.module != activeOwner then
                                crossingFailure("member driver guard belongs to another occurrence")
                              enqueue(causalControls(Some(region)))
                            if dependency.terminal != key then
                              val (source, leaf) = interfaceMembers.getOrElse(
                                dependency.terminal,
                                crossingFailure("ordinary connection lost its exact source member")
                              )
                              val reference = declarationsByPath.getOrElse(
                                source.path,
                                crossingFailure("ordinary connection source has no declaration")
                              )
                              enqueue(Vector(InterfaceMemberSample(reference, leaf.name)))
                            else if dependency.values.nonEmpty then enqueue(dependency.values)
                            else
                              instanceInterfacePorts.get(sample.endpoint) match
                                case Some(binding) if binding.generated.nonEmpty =>
                                  if strict || inferSourceOwnership || allowExternalControl || eventSampling.nonEmpty
                                  then
                                    crossingFailure(
                                      "selected Interface read has unsupported CDC/control provenance"
                                    )
                                  selectedOccurrenceSeen = true
                                  val selected = activeSelections :+ binding.generated.get
                                  if selected.size > 128 then
                                    crossingFailure("selected Interface nesting exceeds its budget")
                                  enqueue(
                                    Vector(InterfaceMemberSample(binding.port, sample.member)),
                                    binding.port.module,
                                    selections = selected
                                  )
                                case Some(binding)
                                    if binding.instance.nonEmpty && member.access == "out" =>
                                  enqueue(
                                    Vector(InterfaceMemberSample(binding.port, sample.member)),
                                    binding.port.module
                                  )
                                case Some(_) => crossingFailure(
                                    "ordinary parent Interface member has no actual driver"
                                  )
                                case None
                                    if originalInterfaceInputs.contains(
                                      sample.endpoint
                                    ) && member.access == "in" =>
                                  val proxy = originalInterfaceInputs(sample.endpoint)
                                  val binding = instanceInterfacePorts(proxy)
                                  if activeSelections.nonEmpty && !binding.instance.get.generatedScope.contains(
                                      activeSelections.last.reference.family.generatedRegion
                                    )
                                  then
                                    crossingFailure(
                                      "selected child Interface input differs from its authored occurrence"
                                    )
                                  enqueue(
                                    Vector(InterfaceMemberSample(proxy, sample.member)),
                                    proxy.module
                                  )
                                case None =>
                                  val declaration = records(
                                    sample.endpoint.module
                                  ).declarations(sample.endpoint.index)
                                  val exported = declaration.value match
                                    case value: InterfacePort[?, ?] =>
                                      value.exported && !value.boundary && value.origin.isEmpty
                                    case value: InterfaceArray[?, ?] =>
                                      value.exported && !value.boundary && value.origin.isEmpty
                                    case _ => false
                                  val generated =
                                    records(sample.endpoint.module).generatedRegions.exists(
                                      _.declarations.contains(sample.endpoint)
                                    )
                                  if sample.endpoint.module != actualRoot || !exported || generated ||
                                    valueRegion(sample.endpoint).nonEmpty || member.access != "in"
                                  then
                                    crossingFailure(
                                      "undriven ordinary member is not an exported top-level input"
                                    )
                                  val domain = domainsByPath.getOrElse(
                                    endpoint.domain.getOrElse(crossingFailure(
                                      "top-level Interface input has no actual domain"
                                    )),
                                    crossingFailure(
                                      "top-level Interface input has no actual domain"
                                    )
                                  )
                                  sourceRoots += sample.endpoint
                                  if activeDomainVisible then
                                    domains += resolved.getOrElse(
                                      domain.reference,
                                      crossingFailure(
                                        "top-level Interface input domain is unresolved"
                                      )
                                    )
                                  retainEndpoint(domain.reference)
                        case memory: MemoryPortCapture =>
                          val declaration = memoryDeclaration(memory.memory, activeOwner)
                          declarationDomain(declaration, resolved) match
                            case Some(domain) =>
                              if activeDomainVisible then domains += domain
                              retainDeclarationEndpoint(declaration)
                            case None =>
                              crossingFailure("memory input has no resolved source domain")
                        case predicate: StaticPredicate => enqueue(predicate.operands)
                        case _: Expr[?]
                            if strict && declarationRef.isEmpty && expressionRef.isEmpty =>
                          crossingFailure("input dependency has no actual captured owner")
                        case _ => ()
            case _ => ()
        if selectedOccurrenceSeen && (crossings.nonEmpty || waivers.nonEmpty || events.nonEmpty)
        then
          crossingFailure(
            "selected generated Instance cone requires selection-qualified CDC/event proof"
          )
        val result = SampleFacts(
          domains.toSet,
          if strict then sourceRoots.toSet else Set.empty,
          crossings.toSet,
          pulseClassified,
          externalSources.toSet,
          localEndpoints.toSet,
          waivers.toSet,
          events.toSet
        )
        cachedFacts += result.domains.size.toLong + result.sources.size + result.crossings.size +
          result.localEndpoints.size + result.waivers.size + result.events.size
        if cachedFacts > 262144 then
          crossingFailure("cached sample facts exceed their bounded graph budget")
        cache.put(root, result)
        result
    val updatesByEventProcess =
      publicationElements(operations.iterator).filter(_.eventProcess.nonEmpty).toVector
        .groupBy(_.eventProcess.get)
    eventProcesses.foreach: process =>
      if records(process.owner).parentAtConstruction.nonEmpty then
        eventFailure("event-bearing module must be the actual selected root")
      val trigger = eventInput(process.definition.trigger, process.owner, bool = true)
      if continuousWriteCounts.getOrElse(trigger, 0) != 0 || eventStorage.contains(trigger) then
        eventFailure("event trigger must not have an internal driver or event owner")
      process.targets.foreach: target =>
        if continuousWriteCounts.getOrElse(target, 0) != 0 then
          eventFailure("event output has both continuous and event writers")
      val captures = mutable.HashSet.empty[DeclarationRef]
      updatesByEventProcess.getOrElse(process, Vector.empty).foreach: update =>
        val facts = sampleFacts(
          update.values(1),
          process.owner,
          strict = true,
          eventSampling = Some(process)
        )
        captures ++= facts.externalSources
        if captures.size > DigitalEventContract.MaximumCaptures then
          eventFailure("event external capture inventory exceeds 4096 inputs")
      process.externalCaptures =
        captures.toVector.sortBy(reference => (reference.module, reference.index))
    val lineageRootCache = mutable.HashMap.empty[ExpressionRef, Set[DeclarationRef | ExpressionRef]]
    var lineageWork = 0L
    def lineageRoots(boundary: ExpressionRef): Set[DeclarationRef | ExpressionRef] =
      lineageRootCache.getOrElseUpdate(
        boundary, {
          val roots = mutable.HashSet.empty[DeclarationRef | ExpressionRef]
          val pending = mutable.ArrayDeque.empty[DeclarationRef | ExpressionRef]
          def include(crossing: ExpressionRef): Unit =
            combinedFacts(
              executableCrossings(crossing).sampledInputs,
              crossing.module,
              strict = true
            )
              .sources.foreach(pending.append)
          include(boundary)
          while pending.nonEmpty do
            val root = pending.removeLast()
            lineageWork += 1
            if lineageWork > 262144 then crossingFailure("crossing lineage exceeds its edge budget")
            if roots.add(root) then
              root match
                case reference: ExpressionRef if executableCrossings.contains(reference) =>
                  include(reference)
                case _ => ()
          roots.toSet
        }
      )
    def verifyReconvergence(facts: SampleFacts): Unit =
      if facts.crossings.size > 1 then
        val firstBoundary = mutable.HashMap.empty[DeclarationRef | ExpressionRef, ExpressionRef]
        var lineageEdges = 0
        facts.crossings.toVector.sortBy(_.index).foreach: boundary =>
          val roots = lineageRoots(boundary)
          roots.foreach: root =>
            lineageEdges += 1
            if lineageEdges > 262144 then
              crossingFailure("crossing lineage exceeds its edge budget")
            firstBoundary.get(root).foreach: previous =>
              if previous != boundary then
                behavioralFailure(
                  "independent crossings with a shared source reconverge at one sample",
                  "NODAL-CDC-057-002"
                )
            firstBoundary.update(root, boundary)
    def dependencies(value: Any, owner: Long, targetDomain: String): Unit =
      val facts = sampleFacts(value, owner, strict = false)
      if facts.events.nonEmpty then
        eventFailure("ordinary sequential sampling cannot consume event-owned state")
      if facts.domains.exists(source => !relationships.permitsSampling(source, targetDomain)) then
        behavioralFailure("register update crosses an unrelated domain", "NODAL-CDC-001")
      verifyReconvergence(facts)
    val sampleGroups = mutable.HashMap.empty[Any, mutable.HashSet[ExpressionRef]]
    def dependenciesWithGuards(
        values: Vector[Any],
        owner: Long,
        targetDomain: String,
        region: Option[DigitalValueRegionRecord],
        group: Any
    ): Unit =
      val crossings = sampleGroups.getOrElseUpdate(group, mutable.HashSet.empty[ExpressionRef])
      def include(value: Any): Unit =
        dependencies(value, owner, targetDomain)
        crossings ++= sampleFacts(value, owner, strict = false).crossings
        if crossings.size > 65536 then
          crossingFailure("causal sample lineage exceeds its node budget")
      values.foreach(include)
      causalControls(region).foreach(include)
      verifyReconvergence(SampleFacts(Set.empty, Set.empty, crossings.toSet))
    clockRecords.foreach: (reference, clock) =>
      val kinds = Vector.newBuilder[String]
      val domains = Vector.newBuilder[Option[String]]
      clock.controls.foreach: control =>
        val facts =
          sampleFacts(control, reference.module, strict = true, allowExternalControl = true)
        verifyReconvergence(facts)
        if facts.pulseClassified then
          clockFailure("Pulse-classified value cannot control an owned clock")
        if facts.domains.size > 1 ||
          (facts.domains.nonEmpty && facts.externalSources.nonEmpty)
        then clockFailure("clock control has mixed or unresolved source provenance")
        if facts.domains.nonEmpty then
          val domain = facts.domains.head
          if clock.kind == "gate" && !relationships.permitsSampling(
              domain,
              domainPath(domainRef(clock.parents.head))
            )
          then
            clockFailure("gate control must belong to its actual parent domain")
          kinds += "domain"
          domains += Some(domain)
        else if facts.sources.isEmpty then
          kinds += "constant"
          domains += None
        else if facts.externalSources.size == 1 && facts.sources == facts.externalSources then
          kinds += "external"
          domains += None
        else
          clockFailure("clock control requires one owned external Bool boundary or actual domain")
      clock.controlKinds = kinds.result()
      clock.controlDomains = domains.result()
    executableWaivers.foreach: (reference, waiver) =>
      val facts = sampleFacts(waiver.input, reference.module, strict = true)
      if facts.waivers(reference) then
        crossingFailure("Cdc.waive participates in a combinational dependency cycle")
      verifyReconvergence(facts)
      if facts.domains.size != 1 then
        crossingFailure("Cdc.waive input must have exactly one known source domain")
      val source = facts.domains.head
      val destination = resolved.getOrElse(
        domainRef(waiver.destination),
        crossingFailure("Cdc.waive destination has no actual resolved domain")
      )
      waiver.sourceEndpoint = Some(exactSourceEndpoint(facts, reference.module, source))
      relationships.verifyBoundaryIntent(
        source,
        destination,
        waiver.relation,
        expressionPath(reference)
      )
    val commonResetLineage = new ResetLineageQuery
    val commonResetRoots = mutable.Map.empty[String, Map[(DeclarationRef, Long), ResetPolarity]]
    def resetEpochRoots(path: String, kind: String): Map[(DeclarationRef, Long), ResetPolarity] =
      commonResetRoots.getOrElseUpdate(
        path, {
          val domain = domainsByPath(path).domain
          val reset = Option(effectiveResets.get(domain)).getOrElse(
            crossingFailure(
              s"Cdc.$kind requires reset-bearing endpoints with common physical roots"
            )
          )
          commonResetLineage(reset).view.mapValues(_._1).toMap
        }
      )
    executableCrossings.iterator.filter((reference, crossing) =>
      crossing.identity(reference) == reference
    ).foreach: (reference, crossing) =>
      val facts = combinedFacts(crossing.sourceInputs, reference.module, strict = true)
      verifyReconvergence(facts)
      if crossing.kind != "pulse" && facts.pulseClassified then
        crossingFailure(
          "Pulse-classified input cannot be relabeled as a level, Gray or handshake value"
        )
      val sources = facts.domains
      if sources.size != 1 then
        crossingFailure(s"Cdc.${crossing.kind} input must have exactly one known source domain")
      val destination = resolved.getOrElse(
        domainRef(crossing.destination),
        crossingFailure(s"Cdc.${crossing.kind} destination has no actual resolved domain")
      )
      val source = sourceDomain(reference, crossing)
      val endpoint = exactSourceEndpoint(facts, reference.module, source)
      if !crossing.sourceEndpoint.contains(endpoint) then
        crossingFailure("crossing source endpoint changed during dependency inference")
      crossing.ready.foreach: ready =>
        val readyFacts = sampleFacts(ready, reference.module, strict = true)
        if readyFacts.domains.exists(_ != destination) then
          crossingFailure("Cdc.fifo destination ready must belong to its actual destination domain")
        verifyReconvergence(readyFacts)
      if source == destination then
        crossingFailure(s"Cdc.${crossing.kind} requires distinct source and destination domains")
      def localEndpoint(actual: String): Boolean =
        records(reference.module).domains.exists(domain =>
          resolved.get(domain.reference).contains(actual)
        )
      if !localEndpoint(source) || !localEndpoint(destination) then
        crossingFailure(
          s"Cdc.${crossing.kind} endpoints require exact local domains or bound requirements"
        )
      if ExecutableCrossingContract.commonResetEpoch(crossing.kind) then
        val sourceRoots = resetEpochRoots(source, crossing.kind)
        if sourceRoots.isEmpty || sourceRoots != resetEpochRoots(destination, crossing.kind) then
          crossingFailure(
            s"Cdc.${crossing.kind} requires common physical reset roots and polarities"
          )
      crossing.sourceDomain = Some(source)
      crossing.destinationDomain = Some(destination)
    publicationElements(records.valuesIterator).flatMap(module =>
      publicationElements(module.declarations)
    ).foreach: declaration =>
      publicationCharge(declaration.attributes.size.toLong)
      val values = declaration.attributes.collect:
        case ("stream_payload" | "stream_valid", value: Expr[?]) => value
      if values.nonEmpty then
        val facts = combinedFacts(values, declaration.reference.module, strict = true)
        if facts.domains.size != 1 then
          crossingFailure("Stream payload and valid require exactly one known source domain")
        verifyReconvergence(facts)
        val readyFacts = sampleFacts(declaration.value, declaration.reference.module, strict = true)
        if readyFacts.domains.exists(_ != facts.domains.head) then
          crossingFailure("Stream ready must belong to its actual carrier domain")
        verifyReconvergence(readyFacts)
    // Boundary observations are actual driver cones, not samples authorized by declared domains.
    // Each ordinary output remains independent; the Interface owner alone supplies protocol groups.
    publicationElements(records.valuesIterator).flatMap(module =>
      publicationElements(module.declarations)
    ).filter(declaration =>
      declaration.kind == KernelSignalKind.Output &&
        continuousWriteCounts.getOrElse(declaration.reference, 0) > 0
    ).foreach: declaration =>
      verifyReconvergence(sampleFacts(
        declaration.value,
        declaration.reference.module,
        strict = false
      ))
    publicationElements(expressionValues.iterator).filter(_._2.interfaceMember.nonEmpty).foreach:
      (reference, value) =>
        verifyReconvergence(sampleFacts(value, reference.module, strict = false))
    val ownersByPath = publicationElements(records.keysIterator).map(owner =>
      publicationProbe(modulePath(owner) -> owner)
    ).toMap
    interfaces.observationGroups.foreach: group =>
      val owner = ownersByPath.getOrElse(
        group.owner,
        crossingFailure("Interface observation lost its actual construction owner")
      )
      verifyReconvergence(combinedFacts(group.values, owner, strict = false))
    publicationCharge(operations.size.toLong)
    operations.zipWithIndex.foreach: (operation, index) =>
      publicationCharge(1L)
      if operation.kind == "assignment" && operation.values.size == 2 then
        val target =
          digitalAssignmentRoot(
            operation.values.head,
            operation.owner,
            allowRootRegister = true,
            generatedScope = assignmentGeneratedScope(operation)
          )
        val declaration = records(target.module).declarations(target.index)
        boundDomainCharge() // exact empty private-state lookup
        if privateFsmStorage(target) then
          FlatFsmRuntime.fail("private machine state cannot have external updates")
        if declaration.kind == KernelSignalKind.Register then
          val root = behavioralRoot(operation.digitalRegion).map(Left(_)).getOrElse(Right(index))
          val update = UpdateRoot(root, structuralGuards(operation.digitalRegion))
          val previousRoots = roots.getOrElseUpdate(target, mutable.ArrayBuffer.empty)
          previousRoots.foreach: previous =>
            updateRootProofWork += 1
            if updateRootProofWork > 262144 then
              behavioralFailure("update-root structural proof exceeds its bounded graph")
            val exclusive = previous.guards.exists((conditional, arm) =>
              update.guards.get(conditional).exists(_ != arm)
            )
            if previous.identity != root && !exclusive then
              behavioralFailure("one register has unrelated update roots", "NODAL-STATE-001")
          if !previousRoots.contains(update) then previousRoots += update
          val source = operation.values(1).asInstanceOf[Expr[?]]
          val exact =
            for
              targetType <- declaration.dataType
              sourceType <- CandidateRuntime.expressionDataType(source)
            yield DigitalEnumContract.sameType(
              CandidateRuntime.typeDescriptor(targetType),
              CandidateRuntime.typeDescriptor(sourceType),
              Some(new StructuralBoundAnalysis(
                operation.owner,
                "NODAL-DIGITAL-054-001",
                "register update type extent",
                signed64Literals = true
              ).sameIdentity)
            )
          if !exact.contains(true) then
            behavioralFailure("register update requires exactly matching types")
          val domain = declarationDomain(declaration, resolved).getOrElse(
            behavioralFailure("register update has no resolved domain", "NODAL-DOMAIN-001")
          )
          root.left.toOption.flatMap(flatFsms.get).foreach: machine =>
            val machineDomain = resolved.get(domainRef(machine.domain)).getOrElse(
              behavioralFailure("FSM root has no resolved domain", "NODAL-DOMAIN-001")
            )
            if domain != machineDomain then
              behavioralFailure(
                "FSM action target belongs to a different clock domain",
                "NODAL-CDC-001"
              )
          dependenciesWithGuards(
            Vector(source),
            operation.owner,
            domain,
            operation.digitalRegion,
            target
          )
          root.left.toOption.foreach: identity =>
            if checkedGuards.add((identity, operation.owner, domain)) then
              guardsByRoot.getOrElse(identity, Vector.empty).foreach(record =>
                dependencies(record, operation.owner, domain)
              )
    def memoryPort(
        capture: MemoryPortCapture,
        owner: Long,
        operands: Vector[Any],
        region: Option[DigitalValueRegionRecord],
        group: Any
    ): Unit =
      val declaration = memoryDeclaration(capture.memory, owner)
      val domain = declarationDomain(declaration, resolved).getOrElse(
        memoryFailure("memory port has no resolved domain")
      )
      capture.lexicalDomain.foreach: lexical =>
        if !resolved.get(domainRef(lexical)).contains(domain) then
          behavioralFailure("memory port is inside a foreign active domain", "NODAL-CDC-001")
      val root = sequentialRoot(region)
      root.flatMap(flatFsms.get).foreach: machine =>
        if !resolved.get(domainRef(machine.domain)).contains(domain) then
          behavioralFailure("FSM memory write belongs to a different clock domain", "NODAL-CDC-001")
      dependenciesWithGuards(operands, owner, domain, region, group)
      root.foreach: identity =>
        if checkedGuards.add((identity, owner, domain)) then
          guardsByRoot.getOrElse(identity, Vector.empty).foreach(dependencies(_, owner, domain))
    publicationElements(operations.iterator).filter(_.kind == "memory-write").foreach: operation =>
      memoryPort(
        operation.values.last.asInstanceOf[MemoryPortCapture],
        operation.owner,
        operation.values.slice(1, 4),
        operation.digitalRegion,
        operation
      )
    publicationElements(expressionValues).foreach: (reference, expression) =>
      if expression.operation.contains("memory_read") then
        val _ = memoryReadSnapshot(expression)
        memoryPort(
          expression.operands.last.asInstanceOf[MemoryPortCapture],
          reference.module,
          expression.operands.take(1),
          None,
          reference
        )
    flatFsms.valuesIterator.foreach: record =>
      val targetDomain = resolved.getOrElse(
        domainRef(record.domain),
        behavioralFailure("FSM requirement has no resolved occurrence domain")
      )
      guardsByRoot.getOrElse(
        record.reference,
        Vector.empty
      ).foreach(value =>
        dependenciesWithGuards(
          Vector(value),
          record.reference.module,
          targetDomain,
          None,
          declarationIds.get(record.storage)
        )
      )
    val usedMemories =
      publicationElements(operations.iterator).filter(_.kind == "memory-write").map(
        _.values.head.asInstanceOf[AnyRef]
      ).toSet ++ publicationElements(
        expressionValues.valuesIterator
      ).filter(_.operation.contains("memory_read")).map(
        _.operands.last.asInstanceOf[MemoryPortCapture].memory
      ).toSet
    publicationElements(records.valuesIterator).flatMap(module =>
      publicationElements(module.declarations)
    ).filter(declaration =>
      declaration.kind == KernelSignalKind.Register ||
        (declaration.kind == KernelSignalKind.Memory && usedMemories(declaration.value))
    ).foreach: declaration =>
      val target = declaration.reference
      val domain = declarationDomain(declaration, resolved).flatMap(domainsByPath.get).getOrElse(
        behavioralFailure("register update domain is not owned by this construction")
      )
      val behavioral = roots.get(target).exists(_.exists(_.identity.isLeft))
      if domain.domain.clock.isEmpty && (behavioral || declaration.kind == KernelSignalKind.Memory)
      then
        behavioralFailure("behavioral update requires an actual local Clock binding")
      declaration.attributes.collectFirst { case ("initial", Some(value: Expr[?])) => value }
        .foreach(dependencies(_, target.module, domainPath(domain.reference)))
      if domain.domain.clock.nonEmpty then
        def capability(value: Expr[?], expected: String): Unit =
          if CandidateRuntime.expressionDataType(value).map(CandidateRuntime.typeDescriptor) !=
              Some(KernelTypeDescriptor(expected))
          then behavioralFailure(s"local $expected binding requires its exact capability type")
          var reference = assignmentReference(value)
          val seen = mutable.HashSet.empty[ExpressionRef]
          while reference.isInstanceOf[ExpressionRef] do
            val expression = reference.asInstanceOf[ExpressionRef]
            if !seen.add(expression) || seen.size > 128 then
              behavioralFailure("clock/reset boundary projection is cyclic or exceeds its budget")
            reference = shapeIndices.get(expression).map(_.input)
              .orElse(shapeViews.get(expression).map(_.input)).getOrElse(
                behavioralFailure(
                  "executable clock/reset requires an actual input boundary or static projection"
                )
              )
          val root = reference.asInstanceOf[DeclarationRef]
          if root.module != domain.reference.module ||
            records(root.module).declarations(root.index).kind != KernelSignalKind.Input
          then
            behavioralFailure(
              "executable clock/reset input belongs to another module or is not an input"
            )
        val _ = clockIdentity(domain.domain.clock.get, domain.reference.module)
        if !domain.domain.resetPolicy.contains(ResetPolicy.None) then
          val effective = Option(effectiveResets.get(domain.domain)).getOrElse(
            behavioralFailure("executable reset requires an owned reset producer")
          )
          var work = 0
          def checkReset(value: Expr[Reset], depth: Int): Unit =
            work += 1
            if depth > 128 || work > 524288 then
              behavioralFailure("reset capability graph exceeds budget")
            val producer = resetRecord(value)
            if producer.kind == "boundary" then capability(producer.inputs.head, "Reset")
            else producer.inputs.foreach(checkReset(_, depth + 1))
          checkReset(effective, 0)
        if !Set(
            KernelDomainKind.Bound,
            KernelDomainKind.External,
            KernelDomainKind.Generated
          ).contains(domain.kind)
        then
          behavioralFailure("executable register requires a resolved physical domain")
        domain.domain.resetPolicy match
          case Some(ResetPolicy.None) =>
            if declaration.attributes.exists:
                case ("initial", Some(_: Expr[?])) => true
                case _ => false
            then
              if !contextualFsmInitializer(declaration) then
                behavioralFailure("resetless register cannot have a reset initializer")
          case Some(_) => ()
          case None if effectiveResets.containsKey(domain.domain) => ()
          case _ => behavioralFailure("executable register requires an explicit reset policy")

  private def staticPredicateSnapshots(): Vector[KernelStaticPredicateSnapshot] =
    staticPredicates.toVector.map: (reference, predicate) =>
      KernelStaticPredicateSnapshot(
        expressionPath(reference),
        modulePath(reference.module),
        expressionRegions.get(reference).map(value => expressionPath(value.reference)),
        predicate.operation,
        predicate.operands.map(value =>
          pathOf(value).getOrElse(
            structuralFailure("predicate operand lost its captured identity")
          )
        )
      )

  private def structuralIfSnapshots(staticDependenciesOnly: Boolean = false)
      : Vector[KernelStructuralIfSnapshot] =
    structuralIfs.valuesIterator.map: record =>
      def arm(region: DigitalValueRegionRecord): KernelStructuralArmSnapshot =
        val (captures, arguments) = if staticDependenciesOnly then staticRegionSignature(region)
        else
          region.captures.valuesIterator.map(value => pathOf(value._1).get).toVector ->
            regionArguments(region)
        KernelStructuralArmSnapshot(
          expressionPath(region.reference),
          captures,
          arguments,
          region.yielded.flatMap(pathOf)
        )
      KernelStructuralIfSnapshot(
        expressionPath(record.reference),
        modulePath(record.reference.module),
        record.parent.map(value => expressionPath(value.reference)),
        pathOf(record.predicate).get,
        arm(record.trueArm),
        arm(record.falseArm),
        record.result.flatMap(CandidateRuntime.expressionDataType).filter(dataType =>
          !staticDependenciesOnly ||
            CandidateRuntime.typeDescriptor(dataType) == KernelTypeDescriptor("Integer")
        ).map(renderType(_, record.reference.module)),
        record.sourceOrdinal
      )
    .toVector

  // Registered construction is intentionally narrower than future callback admission.
  // No payload or provisional type is evidence while this existing cell is pending.
  private def constructionPayloadAvailable(expression: KernelExpr[?]): Boolean =
    if expression.constructionFinalized then true
    else
      closureCharge(1L)
      val reference = Option(expressionIds.get(expression))
      if !expression.pendingConstructionOwnedBy(this) ||
        !reference.exists(ref => expressionValues.get(ref).exists(_ eq expression)) ||
        !expression.constructionKind.exists(DigitalReductionContract.Kinds) ||
        expression.constructionCallback.isEmpty
      then
        fail("NODAL-HIERARCHY-058-CONSTRUCTION", "unregistered or failed construction cell")
      false

  private def requireConstructionPayload(expression: KernelExpr[?]): Boolean =
    if !constructionPayloadAvailable(expression) then
      fail("NODAL-HIERARCHY-058-CONSTRUCTION", "pending construction cannot supply static metadata")
    true

  private var registeredConstructionAdmission = false

  private def ordinaryRootConstructionContext: Boolean =
    moduleStack.size == 1 && activeValueRegion.isEmpty && domainStack.isEmpty &&
      areaStack.isEmpty && generationStack.isEmpty && instanceSelectionStack.isEmpty &&
      analogStack.isEmpty && analogSemanticContext.isEmpty && activeEventProcess.isEmpty

  def reserveReductionConstructionIntent(
      input: Expr[?],
      body: DigitalCollectionCallback.ReductionBody,
      kind: String
  ): KernelExpr[Data] =
    AnalogUserFunctionRuntime.rejectEffect()
    if boundaryClosureOwner.nonEmpty || !ordinaryRootConstructionContext ||
      !DigitalReductionContract.Kinds(kind)
    then collectionFailure("C2a construction requires an open ordinary root Module context")
    val result = reserveRegisteredConstruction(input, body)
    result.registerConstructionIntent(this, kind)
    registeredConstructionAdmission = true
    bindDiagnosticRequest(result)

  /** Stage A/B primitive only: no production reduction branch invokes reservation yet. */
  def reserveReductionConstruction(
      input: Expr[?],
      body: DigitalCollectionCallback.ReductionBody
  ): KernelExpr[Data] =
    DigitalCollectionCallback.requireChecked(body.checked)
    val module = currentModule
    val bound = bindCollectionValue(input, activeValueRegion, module.handle)
    requireRegisteredCollectionOperand(bound, module.handle)
    val (element, _) = DigitalCollectionContract.vector(bound)
    val result =
      KernelExpr.reserve[Data](this, CandidateRuntime.typeDescriptor(element), Vector(bound), body)
    activeValueRegion.foreach: region =>
      if region.expressionCount >= 4098 then
        collectionFailure("collection body exceeds the 4098 expression budget")
      region.expressionCount += 1
    val reference = allocateExpressionReference(module)
    module.expressionCount += 1
    expressionIds.put(result, reference)
    expressionValues.update(reference, result)
    activeValueRegion.foreach(region => expressionRegions.update(reference, region))
    result.attachConstructionSource(
      this,
      semanticOrigin.reserveExpression(module.handle, reference.index, result, Vector(bound))
    )
    boundaryClosureOwner.foreach: owner =>
      owner.captured(reference, result)
      owner.markDirty()
    result

  private def completeReductionSingletonBody(
      result: KernelExpr[Data],
      input: Expr[Vec[Data]],
      indices: Vector[Dimension]
  ): Expr[Data] =
    val reference = Option(expressionIds.get(result)).getOrElse(
      collectionFailure("reserved result has no original expression identity")
    )
    try
      require(
        result.constructionKind.isEmpty || ordinaryRootConstructionContext,
        "registered construction requires its ordinary root context"
      )
      require(
        reference.module == currentModule.handle &&
          expressionRegions.get(reference) == activeValueRegion,
        "reserved result requires its original lexical scope"
      )
      require(
        result.constructionDependencies == Vector(input),
        "reserved result cannot change its actual input"
      )
      DeferredResultType.requireFinal(Vector(input), "singleton construction")
      val (_, dimensions) = DigitalCollectionContract.vector(input)
      val count = dimensions.reduce(StaticIntegerExpression.multiply64)
      val bounds =
        new StructuralBoundAnalysis(currentModule.handle, signed64Literals = true)(count).bounds
      if bounds.lower != 1 || bounds.upper != 1 then
        collectionFailure("reserved singleton requires exact count one")
      require(
        indices == dimensions.map(_ => 0),
        "reserved singleton requires the actual zero index"
      )
      CandidateRuntime.shapeIndex(input, indices, Some(result -> this))
    catch
      case failure: Throwable =>
        result.failConstruction(this)
        poisonConstructor(failure, expressionPath(reference))
        throw failure

  def captureCollection(
      kind: String,
      inputs: Vector[Expr[?]],
      seed: Option[Expr[?]]
  )(body: Vector[Expr[?]] => Expr[?]): Expr[?] =
    if activeGeneratedRegion.nonEmpty && activeLoop.isEmpty then
      fail(
        "NODAL-ITERATION-043-004",
        "digital collection in structural generation requires its digital profile"
      )
    if collectionStack.size >= 128 || collectionRegions.size + loopRegions.size >= 65536 then
      DigitalCollectionContract.fail(
        5,
        "collection nesting or region inventory exceeds its finite budget"
      )
    if analogStack.nonEmpty || analogSemanticContext.nonEmpty then
      DigitalCollectionContract.fail(
        6,
        "digital collection requires a combinational digital context"
      )
    if DigitalReductionContract.Kinds.contains(kind) then captureReduction(kind, inputs, seed)(body)
    else captureOrdinaryCollection(kind, inputs, seed)(body)

  private def captureOrdinaryCollection(
      kind: String,
      inputs: Vector[Expr[?]],
      seed: Option[Expr[?]]
  )(body: Vector[Expr[?]] => Expr[?]): Expr[?] =
    val module = currentModule
    val parent = activeValueRegion
    val boundInputs = inputs.map(bindCollectionValue(_, parent, module.handle))
    val boundSeed = seed.map(bindCollectionValue(_, parent, module.handle))
    (boundInputs ++ boundSeed).foreach(requireRegisteredCollectionOperand(_, module.handle))
    val argumentTypes =
      DigitalCollectionContract.constructionArguments(kind, boundInputs, boundSeed)
    (boundInputs ++ boundSeed).filterNot(DeferredResultType.pending).foreach: value =>
      validateShapeType(
        CandidateRuntime.expressionDataType(value).get,
        module.handle,
        cardinality = true,
        storageContext = true
      )
    val reference = allocateExpressionReference(module)
    module.expressionCount += 1
    val region = new DigitalValueRegionRecord(reference, parent, kind, boundInputs, boundSeed)
    collectionRegions.update(reference, region)
    collectionStack += region
    try
      val arguments =
        argumentTypes.map((dataType, role) => newCollectionArgument(region, dataType, role))
      arguments.filter(_.typeFinalized).foreach(argument =>
        bindCollectionType(argument.resultDescriptor.get, Some(region), module.handle)
      )
      val yielded = bindCollectionValue(body(arguments), Some(region), module.handle)
      val dependencies = boundInputs ++ boundSeed ++ Vector(yielded)
      val pending = dependencies.exists(DeferredResultType.pending)
      if pending then
        val _ = requireDeferredPayloadScope(Some(region), module.handle)
        if !DigitalCombinationalContract.isValueTypeKind(
            DigitalCollectionContract.descriptor(yielded)
          )
        then
          collectionFailure("deferred collection result requires a finite digital payload kind")
      else
        DigitalCombinationalContract.validateValueType(
          DigitalCollectionContract.descriptor(yielded)
        )
      val rawResultType =
        DigitalCollectionContract.constructionResult(kind, boundInputs.head, boundSeed, yielded)
      beforeRegionMutation(region)
      region.yielded = Some(yielded)
      collectionStack.remove(collectionStack.size - 1)
      def finalResultType(): KernelTypeDescriptor =
        val arguments = DigitalCollectionContract.arguments(kind, boundInputs, boundSeed)
        if arguments.size > region.arguments.size || arguments.zipWithIndex.exists:
            case ((dataType, role), index) =>
              val (argument, actualRole) = region.arguments(index)
              actualRole != role || !DigitalEnumContract.sameType(
                CandidateRuntime.typeDescriptor(dataType),
                dataArgument(argument).resultDescriptor.get
              )
        then
          collectionFailure("deferred collection argument signature differs from its actual inputs")
        (boundInputs ++ boundSeed).foreach: value =>
          validateShapeType(
            CandidateRuntime.expressionDataType(value).get,
            module.handle,
            cardinality = true,
            storageContext = true
          )
        DigitalCombinationalContract.validateValueType(
          DigitalCollectionContract.descriptor(yielded)
        )
        exportCollectionType(
          DigitalCollectionContract.result(kind, boundInputs.head, boundSeed, yielded),
          region
        )
      val intent = DeferredResultType.intent(dependencies)(finalResultType())
      val resultType =
        if pending then rawResultType else exportCollectionType(rawResultType, region)
      if !pending then
        validateShapeType(
          CandidateRuntime.dataTypeFromDescriptor(resultType),
          module.handle,
          cardinality = true,
          storageContext = true
        )
      val result = new KernelExpr[Data](
        boundInputs ++ boundSeed ++ region.captures.valuesIterator.map(_._1).toVector,
        resultType = Some(resultType),
        operation = Some(DigitalCollectionContract.Operation),
        typeIntent = intent
      )
      beforeRegionMutation(region)
      region.result = Some(result)
      val _ = captureExpression(result, retained = Some(reference))
      result
    catch
      case failure: Throwable =>
        poisonConstructor(failure, expressionPath(reference))
        scala.util.Failure[Nothing](failure).get
    finally
      if collectionStack.lastOption.contains(region) then
        collectionStack.remove(collectionStack.size - 1)

  /** Capture one dependent-width whole-value template, then discharge every reachable context. */
  private def captureReduction(
      kind: String,
      inputs: Vector[Expr[?]],
      seed: Option[Expr[?]]
  )(body: Vector[Expr[?]] => Expr[?]): Expr[?] =
    if inputs.size != 1 || seed.nonEmpty then
      collectionFailure("reduction requires one input and no seed")
    if collectionStack.exists(value => DigitalReductionContract.Kinds.contains(value.kind)) then
      collectionFailure("reducer-in-reducer nesting is outside the finite reduction profile")
    val module = currentModule
    val parent = activeValueRegion
    val input = bindCollectionValue(inputs.head, parent, module.handle)
    val (element, dimensions) = DigitalCollectionContract.vector(input)
    requireRegisteredCollectionOperand(input, module.handle)
    val pendingInput = DeferredResultType.pending(input)
    if pendingInput then
      if !moduleOwnedGeneratedMemberType(
          KernelTypeDescriptor("geometry", dimensions.toVector),
          module.handle
        )
      then
        collectionFailure(
          "pending reduction outer geometry requires a separately finalized lifecycle"
        )
      val schema =
        DigitalReductionContract.mapWidths(CandidateRuntime.typeDescriptor(element))(_ => 1)
      if !moduleOwnedGeneratedMemberType(schema, module.handle) then
        collectionFailure("pending reduction inner schema requires complete original dimensions")
      validateShapeType(
        CandidateRuntime.dataTypeFromDescriptor(schema),
        module.handle,
        cardinality = true,
        storageContext = true
      )
    else
      validateShapeType(
        CandidateRuntime.expressionDataType(input).get,
        module.handle,
        cardinality = true,
        storageContext = true
      )
    val initialType = CandidateRuntime.typeDescriptor(element)
    val count = dimensions.reduce(StaticIntegerExpression.multiply64)
    val countBounds =
      new StructuralBoundAnalysis(module.handle, signed64Literals = true)(count).bounds
    if countBounds.lower <= 0 then collectionFailure("reduction count must remain positive")
    if countBounds.upper == 1 then
      CandidateRuntime.shapeIndex(
        input.asInstanceOf[Expr[Vec[Data]]],
        dimensions.map(_ => 0)
      )
    else
      // Inventory and cost preflight precede formal allocation and the user's callback.
      val widths = DigitalReductionContract.widths(initialType)
      val contexts = DigitalReductionContract.contexts(
        countBounds.lower,
        countBounds.upper,
        kind == "reduce_balanced"
      )
      if BigInt(contexts.size) * BigInt(
          DigitalReductionContract.schemaWork(initialType) * 2 + 2
        ) > DigitalReductionContract.MaximumWork
      then
        collectionFailure("reduction context preflight exceeds the 65536 analysis-work budget")
      val reference = allocateExpressionReference(module)
      module.expressionCount += 1
      val region = new DigitalValueRegionRecord(reference, parent, kind, Vector(input), None)
      collectionRegions.update(reference, region)
      collectionStack += region
      val previousExact = reductionExact
      val previousDomains = reductionDomains
      reductionProofWork = 0
      try
        def formals(side: String): Vector[KernelExpr[?]] = widths.indices.map(index =>
          newCollectionArgument(region, Integer, s"${side}_width:$index")
        ).toVector
        val left = formals("left")
        val right = formals("right")
        val first = (left.zip(widths) ++ right.zip(widths)).toMap
        reductionExact = first
        reductionDomains = Map.empty
        def template(formals: Vector[KernelExpr[?]]): DataType[?] =
          var index = 0
          CandidateRuntime.dataTypeFromDescriptor(DigitalReductionContract.mapWidths(initialType):
            _ =>
              val value = formals(index).asInstanceOf[Expr[Integer]]
              index += 1
              value)
        val wholeLeft = newCollectionArgument(region, template(left), "left")
        val wholeRight = newCollectionArgument(region, template(right), "right")
        val yielded =
          bindCollectionValue(body(Vector(wholeLeft, wholeRight)), Some(region), module.handle)
        beforeRegionMutation(region)
        region.yielded = Some(yielded)
        collectionStack.remove(collectionStack.size - 1)
        reductionExact = previousExact
        reductionDomains = previousDomains
        val dependencies =
          Vector(input) ++ region.captures.valuesIterator.map(_._1).toVector ++ Vector(yielded)
        val intent =
          DeferredResultType.intent(dependencies)(reductionResultType(region, count, dimensions))
        val resultType =
          if intent.nonEmpty then initialType else reductionResultType(region, count, dimensions)
        val result = new KernelExpr[Data](
          Vector(input) ++ region.captures.valuesIterator.map(_._1).toVector,
          resultType = Some(resultType),
          operation = Some(DigitalCollectionContract.Operation),
          typeIntent = intent
        )
        beforeRegionMutation(region)
        region.result = Some(result)
        val _ = captureExpression(result, retained = Some(reference))
        result
      catch
        case failure: Throwable =>
          poisonConstructor(failure, expressionPath(reference))
          scala.util.Failure[Nothing](failure).get
      finally
        reductionExact = previousExact
        reductionDomains = previousDomains
        if collectionStack.lastOption.contains(region) then
          collectionStack.remove(collectionStack.size - 1)

  /** One transfer/context proof for eager and delayed reductions; the callback is never replayed.
    */
  private def reductionResultType(
      region: DigitalValueRegionRecord,
      count: Dimension,
      originalDimensions: Vector[Dimension]
  ): KernelTypeDescriptor =
    val module = records(region.reference.module)
    val kind = region.kind
    val (left, right) = reductionSignature(region, complete = true)
    if region.captures.size != region.arguments.size - left.size - right.size - 2 then
      collectionFailure("reduction capture inventory differs from its complete signature")
    region.captures.foreach: (key, edge) =>
      val source = dataCapture(edge._1)
      val argument = dataArgument(edge._2)
      requireRegisteredCollectionOperand(source, module.handle)
      if valueReference(source) != key || valueRegion(valueReference(source)) != region.parent ||
        !DigitalEnumContract.sameType(
          DigitalCollectionContract.descriptor(source),
          argument.resultDescriptor.get
        )
      then
        collectionFailure("reduction capture lost its actual parent identity or final type")
    val input = region.inputs.head
    DeferredResultType.requireFinal(
      Vector(input) ++ region.captures.valuesIterator.map(_._1),
      "reduction transfer"
    )
    val (element, dimensions) = DigitalCollectionContract.vector(input)
    val initialType = CandidateRuntime.typeDescriptor(element)
    val widths = DigitalReductionContract.widths(initialType)
    if dimensions != originalDimensions then
      collectionFailure("reduction geometry changed after its callback lifecycle decision")
    validateShapeType(
      CandidateRuntime.expressionDataType(input).get,
      module.handle,
      cardinality = true,
      storageContext = true
    )
    Vector(left, right).zipWithIndex.foreach: (formals, side) =>
      val whole =
        dataArgument(region.arguments(left.size + right.size + side)._1).resultDescriptor.get
      val actualWidths = DigitalReductionContract.widths(whole)
      if !DigitalEnumContract.sameWidthFamily(
          initialType,
          whole
        ) || actualWidths.size != formals.size ||
        actualWidths.zip(formals).exists((actual, expected) =>
          actual match
            case expression: Expr[?] => !expression.eq(expected)
            case _ => true
        )
      then
        collectionFailure("reduction whole-value formal lost its exact original width identities")
    val countBounds =
      new StructuralBoundAnalysis(module.handle, signed64Literals = true)(count).bounds
    if countBounds.lower <= 0 || countBounds.upper == 1 then
      collectionFailure("captured reduction lost its original multi-element geometry")
    val contexts = DigitalReductionContract.contexts(
      countBounds.lower,
      countBounds.upper,
      kind == "reduce_balanced"
    )
    val yielded = region.yielded.getOrElse(collectionFailure("reduction lost its original yield"))
    DeferredResultType.requireFinal(Vector(yielded), "reduction transfer yield")
    reductionProofWork = 0
    val yieldedType = DigitalCollectionContract.descriptor(yielded)
    if !DigitalEnumContract.sameWidthFamily(initialType, yieldedType) then
      collectionFailure(
        "reduction callback changes kind, record schema, enum ABI or inner dimensions"
      )
    val yieldedWidths = DigitalReductionContract.widths(yieldedType)
    val graph = new DigitalReductionContract.IntegerGraph[Dimension]:
      override def charge(work: Int): Unit = chargeReduction(work)
      def same(a: Dimension, b: Dimension): Boolean = (a, b) match
        case (x: Int, y: Int) => x == y
        case (x: Expr[?], y: Expr[?]) => x eq y
        case _ => false
      def operation(value: Dimension): Option[(String, Vector[Dimension])] = value match
        case expression: KernelExpr[?] if expression.literal.isEmpty =>
          expression.operation.filter(IterationDomain.staticArithmetic(_).nonEmpty).map(operation =>
            operation -> expression.operands.map(_.asInstanceOf[Expr[Integer]])
          )
        case _ => None
      def literal(value: Long): Dimension = StaticIntegerExpression.literal64(BigInt(value))
      def bounds(value: Dimension): (Long, Long) =
        val result =
          new StructuralBoundAnalysis(module.handle, signed64Literals = true)(value).bounds
        result.lower -> result.upper
      def add(a: Dimension, b: Dimension): Dimension = StaticIntegerExpression.add64(a, b)
      def subtract(a: Dimension, b: Dimension): Dimension =
        StaticIntegerExpression.subtract64(a, b)
      def multiply(a: Dimension, b: Dimension): Dimension =
        StaticIntegerExpression.multiply64(a, b)
      def maximum(a: Dimension, b: Dimension): Dimension = StaticIntegerExpression.maximum(a, b)
      def ceilLog2(value: Dimension): Dimension = StaticIntegerExpression.ceilLog2(value)
      def chooseZero(test: Dimension, zero: Dimension, nonzero: Dimension): Dimension =
        StaticIntegerExpression.chooseZero(test, zero, nonzero)
    val allFormals = (left ++ right).map(_.asInstanceOf[Dimension])
    val profiles = yieldedWidths.indices.map(index =>
      DigitalReductionContract.infer(
        yieldedWidths(index),
        left(index).asInstanceOf[Dimension],
        right(index).asInstanceOf[Dimension],
        allFormals,
        graph
      )
    ).toVector
    // Body inventory includes ordinary nested collections and all unused retained operations.
    def descendant(candidate: DigitalValueRegionRecord): Boolean =
      var current = Option(candidate)
      var found = false
      while current.nonEmpty && !found do
        found = current.get eq region
        current = current.get.parent
      found
    val actualBody = expressionValues.iterator.filter((ref, _) =>
      expressionRegions.get(ref).exists(descendant)
    ).toVector
    if BigInt(actualBody.size + widths.size * 2) * BigInt(contexts.size) >
        DigitalReductionContract.MaximumWork
    then
      collectionFailure("reduction body/context work exceeds the 65536 analysis-work budget")
    // Endpoints are ordinary static expressions. The universal domain, never an endpoint sample,
    // is used for every intermediate body width and operation obligation below.
    val balanced = kind == "reduce_balanced"
    def at(index: Int, size: Long): Dimension = DigitalReductionContract.formula(
      profiles(index),
      widths(index),
      graph.literal(size),
      balanced,
      graph
    )
    withReductionProofExpressions:
      contexts.foreach: (leftSize, rightSize) =>
        val exact = mutable.HashMap.empty[KernelExpr[?], Dimension]
        val domains = mutable.HashMap.empty[KernelExpr[?], IterationDomain.Bounds]
        def bind(
            formals: Vector[KernelExpr[?]],
            size: DigitalReductionContract.SizeRange
        ): Unit =
          profiles.indices.foreach: index =>
            profiles(index) match
              case DigitalReductionContract.Transfer.Invariant =>
                exact.update(formals(index), widths(index))
              case DigitalReductionContract.Transfer.Fixed(value) =>
                exact.update(formals(index), if size.upper == 1 then widths(index) else value)
              case _ if size.lower == size.upper =>
                exact.update(formals(index), at(index, size.lower))
              case _ =>
                val lower = graph.bounds(at(index, size.lower))._1
                val upper = graph.bounds(at(index, size.upper))._2
                domains.update(formals(index), IterationDomain.Bounds(lower, upper))
        bind(left, leftSize)
        bind(right, rightSize)
        withReductionContext(exact.toMap, domains.toMap):
          if !DigitalEnumContract.sameWidthFamily(initialType, yieldedType) then
            collectionFailure("reduction invariant inner shape changes in a reachable context")
          actualBody.foreach: (reference, expression) =>
            validateReductionStructure(reference, expression, module.handle)
            val descriptor =
              expression.resultDescriptor.getOrElse(
                collectionFailure("reduction body type is absent")
              )
            if descriptor.kind == "Integer" then
              // Raw static captures need only the i64 stage contract here. Each width,
              // shape and bounded consumer is checked independently in this same context.
              val _ = new StructuralBoundAnalysis(
                module.handle,
                signed64Literals = true,
                allowUnboundedParameters = true
              )(expression.asInstanceOf[Expr[Integer]])
            else
              validateShapeType(
                CandidateRuntime.dataTypeFromDescriptor(descriptor),
                module.handle,
                cardinality = true,
                storageContext = true
              )
              DigitalReductionContract.validateBodyValue(expression)
    var index = 0
    val resultType = DigitalReductionContract.mapWidths(initialType): _ =>
      val value =
        DigitalReductionContract.formula(profiles(index), widths(index), count, balanced, graph)
      index += 1
      value
    validateShapeType(
      CandidateRuntime.dataTypeFromDescriptor(resultType),
      module.handle,
      cardinality = true,
      storageContext = true
    )
    resultType

  /** Reuse the ordinary shape/collection contracts under each admitted width context. */
  private def validateReductionStructure(
      reference: ExpressionRef,
      expression: KernelExpr[?],
      owner: Long
  ): Unit =
    def input: Expr[Vec[Data]] = expression.operands.head.asInstanceOf[Expr[Vec[Data]]]
    shapeIndices.get(reference).foreach: index =>
      val _ = validateShapeIndexBounds(
        index.indices,
        DigitalCollectionContract.vector(input)._2,
        owner,
        expressionPath(reference)
      )
    shapeViews.get(reference).foreach: view =>
      val analysis = new StructuralBoundAnalysis(
        owner,
        if view.slice.nonEmpty then "NODAL-SHAPE-043-004" else "NODAL-SHAPE-043-003",
        "shape view"
      )
      val _ = validateShapeViewDimensions(
        DigitalCollectionContract.vector(input)._2,
        view.dimensions,
        view.slice,
        owner,
        expressionPath(reference),
        analysis
      )
    collectionRegions.get(reference).foreach: nested =>
      val expected = DigitalCollectionContract.arguments(nested.kind, nested.inputs, nested.seed)
      if !nested.arguments.take(expected.size).zip(expected).forall:
          case ((argument, _), (dataType, _)) =>
            DigitalEnumContract.sameType(
              dataArgument(argument).resultDescriptor.get,
              CandidateRuntime.typeDescriptor(dataType)
            )
      then
        collectionFailure(
          "nested collection argument type changes in a reachable reduction context"
        )
      val result = DigitalCollectionContract.result(
        nested.kind,
        nested.inputs.head,
        nested.seed,
        nested.yielded.get
      )
      if !DigitalEnumContract.sameType(result, expression.resultDescriptor.get) then
        collectionFailure("nested collection result type changes in a reachable reduction context")

    structuralIfs.get(reference).foreach: conditional =>
      Vector(conditional.trueArm, conditional.falseArm).foreach: arm =>
        val yielded =
          arm.yielded.getOrElse(collectionFailure("value conditional lost its reduction yield"))
        if !DigitalEnumContract.sameType(
            DigitalCollectionContract.descriptor(yielded),
            expression.resultDescriptor.get
          )
        then
          collectionFailure("structural alternative ABI changes in a reachable reduction context")

  private def collectionSnapshots(): Vector[KernelDigitalCollectionSnapshot] =
    collectionRegions.valuesIterator.map: region =>
      val result = region.result.getOrElse(collectionFailure("collection capture did not complete"))
      val owner = region.reference.module
      KernelDigitalCollectionSnapshot(
        expressionPath(region.reference),
        modulePath(owner),
        region.parent.map(value => expressionPath(value.reference)),
        region.kind,
        region.inputs.map(value => pathOf(value).get),
        region.seed.flatMap(pathOf),
        region.captures.valuesIterator.map(value => pathOf(value._1).get).toVector,
        region.arguments.toVector.map: (argument, role) =>
          KernelDigitalRegionArgument(
            pathOf(argument).get,
            renderCollectionType(
              CandidateRuntime.expressionDataType(dataArgument(argument)).get,
              owner,
              Some(region)
            ),
            role
          ),
        pathOf(region.yielded.get).get,
        renderCollectionType(CandidateRuntime.expressionDataType(result).get, owner, region.parent)
      )
    .toVector

  private def activeGeneratedRegion: Option[GeneratedRegionRecord] =
    moduleStack.lastOption.flatMap(module =>
      generationStack.reverseIterator.find(_.owner == module.handle)
    )

  private def generatedScopeSnapshot(region: Option[GeneratedRegionRecord])
      : Option[KernelGeneratedScope] =
    region.map(value =>
      KernelGeneratedScope(generatedRegionPath(value), expressionPath(value.induction))
    )

  def requireNoGeneratedEffect(role: String): Unit =
    requireNoCollectionEffect(role)
    requireLoopEffect(role)
    activeGeneratedRegion.foreach: region =>
      fail(
        "NODAL-ITERATION-043-004",
        s"$role is not permitted inside structural hdlRange until generated ownership and target lowering are available",
        Some(generatedRegionPath(region))
      )

  def generatedRegionActive: Boolean = activeGeneratedRegion.nonEmpty

  def generatedContinuousRegionActive: Boolean =
    activeGeneratedRegion.exists: generated =>
      analogStack.lastOption.exists(_.generatedOwnership.contains(
        generatedRegionPath(generated) -> expressionPath(generated.induction)
      ))

  def generatedRegionOwnership: Option[(String, String)] =
    activeGeneratedRegion.map: region =>
      generatedRegionPath(region) -> expressionPath(region.induction)

  private def moduleHandle(module: Module): Long =
    Option(moduleIds.get(module)).map(_.longValue).getOrElse(
      fail("NODAL-OWNERSHIP-017", "Module is outside this construction transaction")
    )

  private def domainRef(domain: ClockDomain): DomainRef =
    Option(domainIds.get(domain)).getOrElse(
      fail("NODAL-DOMAIN-020", "ClockDomain is outside this construction transaction")
    )

  private def activeArea: Option[AreaRecord] =
    moduleStack.lastOption.flatMap(module => areaStack.lastOption.filter(_.owner == module.handle))

  def allocateArea[A <: Area](source: SourceSpan)(body: () => A): A =
    if activeGeneratedRegion.exists(_.areaFamily) then
      requireAreaFamilyScope("Area declaration", requireBody = true)
      requireLoopEffect("Area declaration")
    else requireNoGeneratedEffect("Area declaration")
    if analogStack.nonEmpty || analogSemanticContext.nonEmpty then
      fail("NODAL-AREA-058-CONTEXT", "Area requires ordinary Module construction")
    if areaStack.size >= 128 || areas.size >= 65536 then
      fail("NODAL-AREA-058-LIMIT", "Area nesting or count exceeds its finite construction budget")
    val module = currentModule
    val record =
      new AreaRecord(module.handle, source, activeArea, currentDomain, activeGeneratedRegion)
    areas += record
    areaStack += record
    try
      val result = body()
      if !record.value.exists(_ eq result) || currentModule.handle != record.owner then
        fail("NODAL-AREA-058-LIFECYCLE", "Area allocation did not close its exact owned object")
      result
    catch
      case failure: Throwable =>
        poisonConstructor(failure, source.path)
        scala.util.Failure[Nothing](failure).get
    finally
      val removed = areaStack.remove(areaStack.size - 1)
      if removed ne record then
        fail("NODAL-AREA-058-LIFECYCLE", "Area lexical construction stack is corrupt")

  def beginArea(value: Area): Unit =
    val record = activeArea.filter(_.value.isEmpty).getOrElse(
      fail(
        "NODAL-AREA-058-MISSING",
        "Area allocation requires the matching Nodal compiler capture plugin"
      )
    )
    val schema = Option(value.getClass.getDeclaredAnnotation(classOf[AreaCaptureSchema]))
    if !schema.exists(value => Set(1, 2, 3)(value.version())) || areaRecords.containsKey(value) then
      fail("NODAL-AREA-058-MISSING", "Area class lacks the exact compiler-owned capture version")
    record.value = Some(value)
    areaRecords.put(value, record)

  private def areaRecord(value: Area): AreaRecord =
    val record = Option(areaRecords.get(value)).getOrElse(
      fail("NODAL-AREA-058-OWNER", "Area belongs to another construction transaction")
    )
    if currentModule.handle != record.owner then
      fail("NODAL-AREA-058-OWNER", "Area belongs to another Module")
    record

  private def areaName(name: String): String =
    if !name.matches("[A-Za-z_][A-Za-z0-9_]*") then
      fail("NODAL-AREA-058-NAME", "Area/member names require an unambiguous Scala identifier")
    name

  def bindArea(name: String, priority: Int, value: Area): Unit =
    val record = areaRecord(value)
    val selected = areaName(name)
    if priority < 0 || priority > 2 then
      fail("NODAL-AREA-058-NAME", "Area binder has an invalid lexical priority")
    if record.name.isEmpty || priority > record.namePriority then
      record.name.foreach(previous => record.aliases += previous)
      record.name = Some(selected)
      record.namePriority = priority
    else if !record.name.contains(selected) && !record.aliases.contains(selected) then
      record.aliases += selected

  def areaMember(owner: Area, name: String, value: Any, access: String): Unit =
    val record = areaRecord(owner)
    if !activeArea.contains(record) then
      fail("NODAL-AREA-058-LIFECYCLE", "Area members must be captured during their construction")
    val selected = areaName(name)
    if record.members.size >= 65536 then
      fail("NODAL-AREA-058-LIMIT", "Area member count exceeds its finite construction budget")
    if record.members.contains(selected) then
      fail("NODAL-AREA-058-MEMBER", "Area member identity was assigned twice")
    if !Set(
        "legacy",
        "host",
        "hidden",
        "area",
        "family",
        "read_only",
        "read_write",
        "symbol"
      )(access) ||
      (access == "read_write" && !value.isInstanceOf[Signal[?]] && !value.isInstanceOf[Register[
        ?
      ]]) ||
      (access == "read_only" && !value.isInstanceOf[Expr[?]]) ||
      (access == "family" && !value.isInstanceOf[GeneratedAreas[?]])
    then fail("NODAL-AREA-058-MEMBER", "member capture has incompatible static access metadata")
    record.members.update(selected, AreaMemberRecord(value, access))

  def areaMemberValue(owner: Area, name: String): Any =
    areaRecord(owner).members.getOrElse(
      areaName(name),
      fail("NODAL-AREA-058-MEMBER", "Area member has no compiler-captured eager value")
    ).value

  private def requireGeneratedFamily(value: GeneratedAreas[?]): GeneratedRegionRecord =
    def original(record: GeneratedRegionRecord): Unit =
      if record.owner != currentModule.handle ||
        !currentModule.generatedRegions.exists(_ eq record) ||
        !record.familyHandle.exists(_.generatedRegion eq record) || record.yieldedArea.isEmpty
      then fail("NODAL-AREA-058-OWNER", "generated family has no exact owned template")
    val record = value.generatedRegion
    original(record)
    val root = value.selectionPrefix.headOption.map(_.family).getOrElse(record)
    val lexicalParent = root.digitalRegion.getOrElse(
      fail("NODAL-AREA-058-OWNER", "generated family has no actual body")
    ).parent
    var scope = activeValueRegion
    var depth = 0
    while scope.nonEmpty && scope != lexicalParent do
      depth += 1
      if depth > 128 then fail("NODAL-AREA-058-LIMIT", "generated family scope exceeds depth")
      scope = scope.get.parent
    if scope != lexicalParent then
      fail("NODAL-AREA-058-OWNER", "generated family escaped its lexical existence scope")
    if value.selectionPrefix.isEmpty then
      if !record.familyHandle.exists(_ eq value) ||
        record.parentOrdinal != activeGeneratedRegion.map(_.ordinal)
      then
        fail(
          "NODAL-AREA-058-OWNER",
          "generated Area family escaped its exact Module/Generate scope"
        )
    else
      if value.selectionPrefix.size >= 128 then
        fail("NODAL-AREA-058-LIMIT", "generated family selection exceeds depth")
      var expected = value.selectionPrefix.head.family
      if expected.parentOrdinal != activeGeneratedRegion.map(_.ordinal) then
        fail("NODAL-AREA-058-OWNER", "selected generated family escaped its original handle scope")
      value.selectionPrefix.foreach: selection =>
        if selection.family ne expected then
          fail("NODAL-AREA-058-OWNER", "generated family selection chain is discontinuous")
        original(selection.family)
        val selected = generatedAreaPath(selection.family, selection.members.dropRight(1))
        val member = selected.members.getOrElse(
          selection.members.lastOption.getOrElse(
            fail("NODAL-AREA-058-MEMBER", "family selection has no member")
          ),
          fail("NODAL-AREA-058-MEMBER", "family selection has no captured member")
        )
        expected = generatedFamilyExportTarget(selection.family, member)
      if expected ne record then
        fail("NODAL-AREA-058-OWNER", "selected family disagrees with its actual export chain")
      val last = value.selectionPrefix.last
      validateGeneratedOrdinal(last.family, last.ordinal, value.selectionPrefix.dropRight(1))
    record

  def generatedAreas[A <: Area](
      lower: Int | Expr[Integer],
      upper: Int | Expr[Integer],
      step: Int | Expr[Integer],
      maximum: Option[Int]
  )(body: Expr[Integer] => A): GeneratedAreas[A] =
    val (value, record) =
      captureGeneratedRegion(lower, upper, step, maximum, areaFamily = true)(body)
    val captured = areaRecord(value)
    if !captured.generatedScope.contains(record) then
      fail(
        "NODAL-AREA-058-OWNER",
        "generated yield must be an Area constructed in its actual family"
      )
    record.yieldedArea = Some(captured)
    val family = new GeneratedAreas[A](record)
    record.familyHandle = Some(family)
    val _ = generatedAreaExports(record)
    family

  def bindGeneratedAreas(name: String, priority: Int, value: GeneratedAreas[?]): Unit =
    val record = requireGeneratedFamily(value)
    if value.selectionPrefix.isEmpty then
      bindArea(name, priority, record.yieldedArea.get.value.get)

  private def generatedOrdinalValid(
      record: GeneratedRegionRecord,
      ordinal: Int | Expr[Integer],
      scope: Option[DigitalValueRegionRecord],
      prefix: Vector[GeneratedFamilySelectionRecord]
  ): Boolean =
    val analysis = new StructuralBoundAnalysis(record.owner, "NODAL-AREA-058-INDEX", "Area ordinal")
    def check(record: GeneratedRegionRecord, ordinal: Int | Expr[Integer]): Boolean =
      val bounds = analysis(ordinal)
      val valid =
        IterationDomain.verifyOrdinal(analysis.selectedGeneratedDomain(record), bounds.bounds).fold(
          _ =>
            val index = analysis(ordinal)
            val zero = analysis(0)
            val coordinate = analysis.generatedCoordinate(record, index)
            val upper = analysis(record.upperExclusive.asInstanceOf[Dimension])
            val proof = new StructuralPredicateOrdering.Query[StaticPredicate, StructuralBound](
              predicate =>
                val reference = Option(expressionIds.get(predicate)).getOrElse(
                  fail("NODAL-AREA-058-INDEX", "ordering predicate has no actual owner")
                )
                if reference.module != record.owner || !staticPredicates.get(
                    reference
                  ).contains(predicate)
                then
                  fail("NODAL-AREA-058-INDEX", "ordering predicate has a foreign owner")
                if Set("eq", "ne", "lt", "le", "gt", "ge")(predicate.operation) then
                  val operands =
                    predicate.operands.map(value => analysis(value.asInstanceOf[Dimension]))
                  StructuralPredicateOrdering.Compare(predicate.operation, operands(0), operands(1))
                else
                  StructuralPredicateOrdering.Logic(
                    predicate.operation,
                    predicate.operands.map(_.asInstanceOf[StaticPredicate])
                  )
              ,
              _.bounds,
              (left, right) => left.identity == right.identity
            )
            def prove(left: StructuralBound, right: StructuralBound, inclusive: Boolean): Boolean =
              if proof.less(left, right, inclusive) then true
              else
                var current = scope
                val visited = mutable.HashSet.empty[DigitalValueRegionRecord]
                var proved = false
                while current.nonEmpty && !proved do
                  val region = current.get
                  if visited.size >= 128 || !visited.add(region) then
                    fail("NODAL-AREA-058-INDEX", "ordering lexical ancestry is cyclic or excessive")
                  region.structuralArm.foreach: arm =>
                    val conditional = structuralIfs(arm.conditional)
                    proved =
                      proof.under(conditional.predicate, !arm.whenTrue, left, right, inclusive)
                  current = region.parent
                proved
            prove(zero, index, true) && prove(coordinate, upper, false)
          ,
          _ => true
        )
      bounds.parameters.foreach(markStructuralParameter)
      valid

    val validPrefix = prefix.forall: selection =>
      val valid = check(selection.family, selection.ordinal)
      if valid then analysis.selectGeneratedInduction(selection.family, analysis(selection.ordinal))
      valid
    validPrefix && check(record, ordinal)

  private def validateGeneratedOrdinal(
      record: GeneratedRegionRecord,
      ordinal: Int | Expr[Integer],
      prefix: Vector[GeneratedFamilySelectionRecord]
  ): Unit =
    if !generatedOrdinalValid(record, ordinal, activeValueRegion, prefix) then
      fail(
        "NODAL-AREA-058-INDEX",
        "generated ordinal existence is not proved in its actual lexical scope"
      )

  def generatedAreaReference[A <: Area](family: GeneratedAreas[A], ordinal: Int | Expr[Integer])
      : GeneratedAreaRef[A] =
    val record = requireGeneratedFamily(family)
    validateGeneratedOrdinal(record, ordinal, family.selectionPrefix)
    new GeneratedAreaRef[A](family, ordinal, Vector.empty)

  private def generatedSelectedArea(value: GeneratedAreaRef[?])
      : (GeneratedRegionRecord, AreaRecord) =
    val family = requireGeneratedFamily(value.family)
    validateGeneratedOrdinal(family, value.ordinal, value.family.selectionPrefix)
    family -> generatedAreaPath(family, value.members)

  private def generatedAreaPath(
      family: GeneratedRegionRecord,
      members: Vector[String]
  ): AreaRecord =
    if members.size >= 128 then
      fail("NODAL-AREA-058-LIMIT", "generated member path exceeds depth")
    members.foldLeft(family.yieldedArea.get): (owner, name) =>
      owner.members.get(areaName(name)).filter(_.access == "area").map(_.value) match
        case Some(area: Area) =>
          val nested = areaRecord(area)
          if !nested.generatedScope.contains(family) then
            fail("NODAL-AREA-058-OWNER", "nested Area belongs to another generated family")
          nested
        case _ =>
          fail("NODAL-AREA-058-MEMBER", "generated reference does not name a captured nested Area")

  private def generatedFamilyExportTarget(family: GeneratedRegionRecord, member: AreaMemberRecord)
      : GeneratedRegionRecord =
    val target = member.value match
      case value: GeneratedAreas[?]
          if member.access == "family" && value.selectionPrefix.isEmpty &&
            value.generatedRegion.familyHandle.exists(_ eq value) => value.generatedRegion
      case _ =>
        fail("NODAL-AREA-058-EXPORT", "family export requires an exact original family handle")
    if target.owner != family.owner || !target.parentOrdinal.contains(family.ordinal) ||
      !target.digitalRegion.exists(_.parent == family.digitalRegion) ||
      !closureElements(
        records(family.owner).generatedRegions
      ).exists(_ eq target) || target.yieldedArea.isEmpty
    then fail("NODAL-AREA-058-OWNER", "family export is not an actual directly nested Generate")
    target

  def generatedAreaFamily[A <: Area](value: GeneratedAreaRef[?], name: String): GeneratedAreas[A] =
    val (family, selected) = generatedSelectedArea(value)
    val member = selected.members.getOrElse(
      areaName(name),
      fail("NODAL-AREA-058-MEMBER", "generated family member has no exact eager capture")
    )
    val target = generatedFamilyExportTarget(family, member)
    val prefix = value.family.selectionPrefix :+
      GeneratedFamilySelectionRecord(family, value.ordinal, value.members :+ name)
    val result = new GeneratedAreas[A](target, prefix)
    val _ = requireGeneratedFamily(result)
    result

  def generatedAreaNested[A <: Area](
      value: GeneratedAreaRef[?],
      name: String
  ): GeneratedAreaRef[A] =
    val nested =
      new GeneratedAreaRef[A](value.family, value.ordinal, value.members :+ areaName(name))
    val _ = generatedSelectedArea(nested)
    nested

  private def generatedExportTarget(family: GeneratedRegionRecord, member: AreaMemberRecord)
      : DeclarationRef =
    val target = member.value match
      case value: Expr[?] => Option(declarationIds.get(value)).getOrElse(
          fail(
            "NODAL-AREA-058-EXPORT",
            "first generated export profile requires an exact storage declaration"
          )
        )
      case _ => fail("NODAL-AREA-058-EXPORT", "generated export is not a typed storage value")
    if !closureElements(family.declarations).contains(target) ||
      !Set(
        KernelSignalKind.Wire,
        KernelSignalKind.Register
      )(records(target.module).declarations(target.index).kind) ||
      !Set("read_only", "read_write")(member.access)
    then
      fail(
        "NODAL-AREA-058-EXPORT",
        "generated export lacks actual family storage or typed access metadata"
      )
    target

  private def generatedInstanceExportTarget(
      family: GeneratedRegionRecord,
      member: AreaMemberRecord
  ): (Instance[?], InstanceRecord) =
    val value = member.value match
      case value: Instance[?] if member.access == "symbol" => value
      case _ => fail("NODAL-AREA-058-EXPORT", "instance export requires an exact captured Instance")
    val target = instanceRecord(value)
    if !closureElements(
        records(family.owner).instances
      ).exists(_ eq target) || !target.generatedScope.contains(family)
    then fail("NODAL-AREA-058-OWNER", "instance export belongs to another exact generated family")
    value -> target

  private def generatedInterfaceExportTarget(
      family: GeneratedRegionRecord,
      member: AreaMemberRecord
  ): (InterfaceEndpoint, DeclarationRef) =
    val value = member.value match
      case value: InterfaceEndpoint if member.access == "symbol" && !value.boundary => value
      case value: InterfaceValueRef[?] if member.access == "symbol" =>
        val endpoint = checkedInterfaceValueReference(value).endpoint
        if endpoint.boundary then
          fail("NODAL-AREA-058-EXPORT", "ordinary member export requires its original endpoint")
        endpoint
      case _ =>
        fail("NODAL-AREA-058-EXPORT", "Interface export requires an exact original endpoint")
    val reference = Option(declarationIds.get(value)).getOrElse(
      fail("NODAL-AREA-058-EXPORT", "Interface export has no actual declaration")
    )
    val declaration = records(reference.module).declarations(reference.index)
    if reference.module != family.owner || !closureElements(
        family.declarations
      ).contains(reference) ||
      !Set(KernelSignalKind.InterfacePort, KernelSignalKind.InterfaceArray)(declaration.kind) ||
      (value match
        case endpoint: InterfacePort[?, ?] => endpoint.origin.nonEmpty
        case array: InterfaceArray[?, ?] => array.origin.nonEmpty)
    then fail("NODAL-AREA-058-OWNER", "Interface export belongs to another exact generated family")
    value -> reference

  def generatedAreaInterface(value: GeneratedAreaRef[?], name: String): InterfaceEndpoint =
    requireAreaFamilyScope("generated Interface selection", requireBody = false)
    val (family, selected) = generatedSelectedArea(value)
    val member = selected.members.getOrElse(
      areaName(name),
      fail("NODAL-AREA-058-MEMBER", "Interface member has no exact eager capture")
    )
    val (endpoint, reference) = generatedInterfaceExportTarget(family, member)
    if ConstructionInterfaceLayout.leaves(
        endpoint.definition,
        endpoint.role,
        charge = closureCharge
      ).exists(leaf =>
        leaf.analogType.nonEmpty || leaf.mode.nonEmpty || leaf.dataType.exists(isBoundaryCapability)
      )
    then fail("NODAL-AREA-058-CAPABILITY", "selected Interface requires ordinary digital members")
    val root = value.family.selectionPrefix.headOption.map(_.family).getOrElse(family)
    val _ = bindGeneratedFamily(root, activeValueRegion)
    (value.family.selectionPrefix.map(_.ordinal) :+ value.ordinal).foreach:
      case ordinal: Expr[Integer] =>
        val _ = bindCollectionValue(ordinal, activeValueRegion, currentModule.handle)
      case _: Int => ()
    projectInterfaceEndpoint(
      endpoint,
      records(reference.module).declarations(reference.index).kind,
      InterfaceBoundaryRecord(
        None,
        reference,
        Some(GeneratedSymbolSelectionRecord(value, name)),
        activeValueRegion
      )
    )

  def generatedAreaValue[A <: Data](
      value: GeneratedAreaRef[?],
      name: String
  ): InterfaceValueRef[A] =
    val (_, selected) = generatedSelectedArea(value)
    val original = selected.members.getOrElse(
      areaName(name),
      fail("NODAL-AREA-058-MEMBER", "ordinary member reference has no exact eager capture")
    ).value match
      case reference: InterfaceValueRef[?] => reference
      case _ => fail("NODAL-AREA-058-EXPORT", "ordinary member export lacks its typed reference")
    val endpoint = generatedAreaInterface(value, name)
    rebindInterfaceValue(original, endpoint).asInstanceOf[InterfaceValueRef[A]]

  def generatedAreaInstance[M <: Module](value: GeneratedAreaRef[?], name: String)
      : GeneratedInstanceRef[M] =
    val (family, selected) = generatedSelectedArea(value)
    val member = selected.members.getOrElse(
      areaName(name),
      fail("NODAL-AREA-058-MEMBER", "instance member has no exact eager capture")
    )
    val (instance, _) = generatedInstanceExportTarget(family, member)
    new GeneratedInstanceRef[M](
      instance.asInstanceOf[Instance[M]],
      GeneratedSymbolSelectionRecord(value, name)
    )

  private def validateGeneratedInstanceSelection(view: GeneratedInstanceRef[?]): Unit =
    val selection = view.selection
    val (family, selected) = generatedSelectedArea(selection.reference)
    val member = selected.members.getOrElse(
      areaName(selection.member),
      fail("NODAL-AREA-058-MEMBER", "selected instance export is absent")
    )
    val (instance, _) = generatedInstanceExportTarget(family, member)
    if instance ne view.instance then
      fail("NODAL-AREA-058-OWNER", "selected instance does not match its exact original export")

  private def generatedAreaExports(family: GeneratedRegionRecord)
      : Vector[KernelGeneratedAreaExport] =
    val exports = Vector.newBuilder[KernelGeneratedAreaExport]
    var count = 0
    def exportPath(key: Vector[String]): String =
      if boundaryWorkActive then
        key.foreach(value => closureCharge(1L + value.length.toLong))
      key.mkString(".")
    def visit(record: AreaRecord, prefix: Vector[String]): Unit =
      closureCharge(1L)
      if prefix.size >= 128 then fail("NODAL-AREA-058-LIMIT", "generated export exceeds path depth")
      record.members.foreach: (name, member) =>
        closureCharge(1L + prefix.size.toLong + name.length.toLong)
        count += 1
        if count > 65536 then
          fail("NODAL-AREA-058-LIMIT", "generated export inventory exceeds budget")
        val key = prefix :+ name
        member.access match
          case "area" =>
            val area = member.value match
              case value: Area => value
              case _ => fail("NODAL-AREA-058-EXPORT", "nested export lacks an actual Area")
            val nested = Option(areaRecords.get(area)).getOrElse(
              fail("NODAL-AREA-058-OWNER", "generated export has no actual nested Area record")
            )
            if nested.owner != family.owner || !nested.generatedScope.contains(family) then
              fail("NODAL-AREA-058-OWNER", "generated nested export belongs to another family")
            visit(nested, key)
          case "family" =>
            val target = generatedFamilyExportTarget(family, member)
            closureCharge(1L)
            exports += KernelGeneratedAreaExport(
              exportPath(key),
              generatedRegionPath(target),
              "read_only",
              "family"
            )
          case "symbol" if member.value.isInstanceOf[Instance[?]] =>
            val (_, target) = generatedInstanceExportTarget(family, member)
            closureCharge(1L)
            exports += KernelGeneratedAreaExport(
              exportPath(key),
              instancePath(family.owner, target.ordinal),
              "read_only",
              "instance"
            )
          case "symbol"
              if member.value.isInstanceOf[InterfaceEndpoint] ||
                member.value.isInstanceOf[InterfaceValueRef[?]] =>
            val (_, target) = generatedInterfaceExportTarget(family, member)
            closureCharge(1L)
            exports += KernelGeneratedAreaExport(
              exportPath(key),
              declarationPath(target),
              "read_only",
              "interface"
            )
          case "read_only" | "read_write" =>
            val target = generatedExportTarget(family, member)
            closureCharge(1L)
            exports += KernelGeneratedAreaExport(
              exportPath(key),
              declarationPath(target),
              member.access
            )
          case "host"
              if member.value.isInstanceOf[GeneratedAreas[?]] &&
                record.value.flatMap(value =>
                  Option(value.getClass.getDeclaredAnnotation(classOf[AreaCaptureSchema]))
                )
                  .forall(_.version() != 3) =>
            fail(
              "NODAL-AREA-058-MISSING",
              "generated family exports require current compiler-owned capture metadata"
            )
          case "host" | "hidden" => ()
          case _ => fail(
              "NODAL-AREA-058-EXPORT",
              "generated export requires current supported member metadata"
            )
    family.yieldedArea.foreach(visit(_, Vector.empty))
    exports.result()

  def generatedAreaMember(value: GeneratedAreaRef[?], name: String, kind: String): Any =
    val (family, selected) = generatedSelectedArea(value)
    val member = selected.members.getOrElse(
      areaName(name),
      fail("NODAL-AREA-058-MEMBER", "generated member has no exact eager capture")
    )
    val target = generatedExportTarget(family, member)
    val declaration = records(target.module).declarations(target.index)
    val access = if kind == "read_only" then "read_only" else "read_write"
    if !Set("read_only", "signal", "register")(kind) ||
      (access == "read_write" && member.access != "read_write") ||
      (kind == "signal" && !member.value.isInstanceOf[Signal[?]]) ||
      (kind == "register" && !member.value.isInstanceOf[Register[?]])
    then
      fail(
        "NODAL-AREA-058-CAPABILITY",
        "generated projection cannot expand the exact exported capability"
      )
    val dataType = declaration.dataType.getOrElse(
      fail("NODAL-AREA-058-EXPORT", "generated storage has no exact type")
    ).asInstanceOf[DataType[Data]]
    val selectedType = new KernelInstanceDataType[Data](CandidateRuntime.typeDescriptor(dataType))
    // Preserve an already complete Module-owned type in pure callbacks. A regional token, even
    // one with singleton bounds, still requires the exact selected-boundary finalizer below.
    if moduleOwnedGeneratedMemberType(selectedType.formal, target.module) then
      selectedType.seal(selectedType.formal)
    val metadata =
      GeneratedAreaMemberRecord(
        family,
        value.ordinal,
        value.members :+ name,
        target,
        access,
        selectedType,
        value.family.selectionPrefix
      )
    val expression = new KernelExpr[Data](
      value.family.selectionPrefix.map(_.ordinal) ++ Vector(value.ordinal, metadata),
      resultType = Some(selectedType.formal),
      operation = Some(GeneratedAreaRuntime.MemberOperation),
      typeIntent = Option.when(!selectedType.finalized)(new KernelResultTypeIntent(
        Vector(metadata),
        () => {
          if !selectedType.finalized then
            fail("NODAL-AREA-058-TYPE", "selected storage type has not been sealed")
          selectedType.kernelDescriptor
        }
      ))
    )
    val _ = captureExpression(expression)
    kind match
      case "signal" =>
        new Signal(selectedType, KernelSignalKind.Wire, projection = Some(expression))
      case "register" => new Register(None, Some(selectedType), Some(expression))
      case _ => expression

  private def generatedMemberRecord(expression: KernelExpr[?]): Option[GeneratedAreaMemberRecord] =
    publicationProbe:
      if !constructionPayloadAvailable(expression) then None
      else
        expression.operands.lastOption.collect:
          case value: GeneratedAreaMemberRecord
              if expression.operation.contains(GeneratedAreaRuntime.MemberOperation) => value

  /** Eager reuse is a property of the complete original descriptor and actual token owners, not of
    * selected bounds or rendered names. No new type or dependency registry is introduced.
    */
  private def moduleOwnedGeneratedMemberType(descriptor: KernelTypeDescriptor, owner: Long)
      : Boolean =
    val visited = mutable.HashSet.empty[DeclarationRef | ExpressionRef]
    var work = 0
    def visit(value: Any, depth: Int): Boolean =
      work += 1
      if depth > 128 || work > 65536 then
        fail("NODAL-AREA-058-TYPE", "selected storage type exceeds structural budget")
      value match
        case expression: Expr[?] =>
          val reference = Option(declarationIds.get(expression))
            .map(value => value: DeclarationRef | ExpressionRef)
            .orElse(Option(expressionIds.get(expression))
              .map(value => value: DeclarationRef | ExpressionRef))
          reference.exists: actual =>
            val actualOwner = actual match
              case value: DeclarationRef => value.module
              case value: ExpressionRef => value.module
            actualOwner == owner && valueRegion(actual).isEmpty &&
            CandidateRuntime.expressionDataType(expression)
              .exists(CandidateRuntime.typeDescriptor(_) == KernelTypeDescriptor("Integer")) &&
            (!visited.add(actual) || (expression match
              case kernel: KernelExpr[?] => kernel.operands.forall(visit(_, depth + 1))
              case _ => true))
        case dataType: DataType[?] => visit(CandidateRuntime.typeDescriptor(dataType), depth + 1)
        case field: StructField[?] => visit(field.dataType, depth + 1)
        case value: KernelTypeDescriptor => value.arguments.forall(visit(_, depth + 1))
        case values: Seq[?] => values.forall(visit(_, depth + 1))
        case _ => true
    visit(descriptor, 0)

  private def requireGeneratedMemberTypeRecord(
      expression: KernelExpr[?],
      reference: ExpressionRef,
      scope: Option[DigitalValueRegionRecord]
  ): GeneratedAreaMemberRecord =
    val member = generatedMemberRecord(expression).getOrElse(
      fail("NODAL-AREA-058-TYPE", "selected storage type requires its original member record")
    )
    def registered(family: GeneratedRegionRecord): Unit =
      if family.owner != reference.module ||
        !records(
          reference.module
        ).generatedRegions.exists(_ eq family) || family.yieldedArea.isEmpty
      then fail("NODAL-AREA-058-TYPE", "selected storage has a foreign family owner")
    def exported(family: GeneratedRegionRecord, path: Vector[String]): AreaMemberRecord =
      registered(family)
      if path.isEmpty then fail("NODAL-AREA-058-TYPE", "selected storage export path is empty")
      generatedAreaPath(family, path.dropRight(1)).members.getOrElse(
        areaName(path.last),
        fail("NODAL-AREA-058-TYPE", "selected storage lost its original captured export")
      )
    var expected = member.selectionPrefix.headOption.map(_.family).getOrElse(member.family)
    member.selectionPrefix.foreach: selection =>
      if selection.family ne expected then
        fail("NODAL-AREA-058-TYPE", "selected storage family prefix is discontinuous")
      expected =
        generatedFamilyExportTarget(selection.family, exported(selection.family, selection.members))
    val actual = exported(member.family, member.members)
    val target = generatedExportTarget(member.family, actual)
    if (expected ne member.family) || target != member.target ||
      !(member.access == "read_only" || member.access == "read_write" && actual.access == "read_write") ||
      !expression.typeIntent.fold(
        member.dataType.finalized &&
          moduleOwnedGeneratedMemberType(member.dataType.formal, target.module)
      )(intent =>
        intent.dependencies.size == 1 && intent.dependencies.head.asInstanceOf[AnyRef].eq(member)
      ) || records(target.module).declarations(target.index).dataType.forall(value =>
        CandidateRuntime.typeDescriptor(value) != member.dataType.formal
      ) || !generatedOrdinalValid(member.family, member.ordinal, scope, member.selectionPrefix)
    then
      fail("NODAL-AREA-058-TYPE", "selected storage type lost its exact export or type obligation")
    // Reuse the original family/capture owner. This does not manufacture a storage SSA capture.
    val _ = generatedSelectionSnapshot(
      member.family,
      member.ordinal,
      member.members,
      member.access,
      member.selectionPrefix,
      scope
    )
    member

  def bindGeneratedMemberAlias(value: AnyRef, expression: Expr[?]): Unit = expression match
    case original: KernelExpr[?] if generatedMemberRecord(original).nonEmpty =>
      val reference = Option(expressionIds.get(original)).getOrElse(
        fail("NODAL-AREA-058-OWNER", "generated proxy has no canonical expression")
      )
      if reference.module != currentModule.handle || declarationIds.containsKey(
          value
        ) || expressionIds.containsKey(value)
      then
        fail("NODAL-AREA-058-OWNER", "generated proxy has a foreign or existing identity")
      expressionIds.put(value, reference)
      ()
    case _ => fail(
        "NODAL-AREA-058-CAPABILITY",
        "typed proxy requires an actual generated member expression"
      )

  private def generatedSelectionSnapshot(
      family: GeneratedRegionRecord,
      ordinal: Int | Expr[Integer],
      members: Vector[String],
      access: String,
      prefix: Vector[GeneratedFamilySelectionRecord],
      scope: Option[DigitalValueRegionRecord]
  ): KernelGeneratedMemberSnapshot =
    def bound(input: Int | Expr[Integer]): String =
      val actual = input match
        case value: Int => value
        case value: Expr[Integer] =>
          bindCollectionValue(
            value,
            scope,
            family.owner,
            create = false
          ).asInstanceOf[Expr[Integer]]
      generatedBound(actual)
    val root = prefix.headOption.map(_.family).getOrElse(family)
    val familyInput = bindGeneratedFamily(root, scope, create = false) match
      case _: GeneratedRegionRecord => None
      case argument: GeneratedFamilyArgument => pathOf(argument)
    KernelGeneratedMemberSnapshot(
      generatedRegionPath(family),
      bound(ordinal),
      members.mkString("."),
      access,
      prefix.map(value =>
        KernelGeneratedFamilySelection(
          generatedRegionPath(value.family),
          bound(value.ordinal),
          value.members.mkString(".")
        )
      ),
      familyInput
    )

  private def generatedSymbolSnapshot(
      selection: GeneratedSymbolSelectionRecord,
      scope: Option[DigitalValueRegionRecord]
  ): KernelGeneratedMemberSnapshot =
    val value = selection.reference
    generatedSelectionSnapshot(
      value.family.generatedRegion,
      value.ordinal,
      value.members :+ selection.member,
      "read_only",
      value.family.selectionPrefix,
      scope
    )

  private def generatedMemberSnapshot(expression: KernelExpr[?])
      : Option[KernelGeneratedMemberSnapshot] =
    generatedMemberRecord(expression).map: record =>
      val reference = Option(expressionIds.get(expression)).getOrElse(
        fail("NODAL-AREA-058-OWNER", "generated member snapshot has no captured expression")
      )
      generatedSelectionSnapshot(
        record.family,
        record.ordinal,
        record.members,
        record.access,
        record.selectionPrefix,
        expressionRegions.get(reference)
      )

  private def areaComponents(record: AreaRecord): Vector[String] =
    closureCharge(1L + record.name.fold(33L + record.source.path.length.toLong)(_.length.toLong))
    val local = record.name.getOrElse(
      "area_" + java.lang.Integer.toUnsignedString(
        (record.source.path + ":" + record.source.line + ":" + record.source.column).hashCode,
        16
      )
    )
    val parent = record.parent.map(areaComponents).getOrElse(Vector.empty)
    val family = record.generatedScope.filterNot(value =>
      record.parent.flatMap(_.generatedScope).contains(value)
    )
    closureCharge(parent.size.toLong + 1L + family.size.toLong)
    parent ++ family.toVector.map(value => s"generate_${value.ordinal}") :+ local

  private def prepareAreaNames(): Unit =
    closureCharge(areas.size.toLong)
    val paths = mutable.HashSet.empty[(Long, Vector[String])]
    areas.foreach: record =>
      closureCharge(record.declarations.size.toLong + record.instances.size.toLong +
        record.members.size.toLong)
      val components = areaComponents(record)
      if boundaryWorkActive then
        components.foreach(value => closureCharge(1L + value.length.toLong))
      if !paths.add(record.owner -> components) then
        fail("NODAL-AREA-058-NAME", "Area lexical names collide in the same Module")
      val ownedDeclarations = record.declarations.toSet
      val ownedInstances = record.instances.toSet
      record.members.foreach: (name, member) =>
        closureCharge(2L + name.length.toLong + math.max(
          name.length.toLong + 2L,
          6L
        ) + components.size.toLong)
        if boundaryWorkActive then
          components.foreach(value =>
            closureCharge(2L + value.length.toLong + math.max(value.length.toLong + 2L, 6L))
          )
        val value = member.value
        val ownedDeclaration = Option(declarationIds.get(value)).exists(ownedDeclarations.contains)
        val ownedInstance =
          Option(instanceObjects.get(value)).flatMap(value => Option(instanceIds.get(value)))
            .orElse(Option(instanceIds.get(value))).exists(ownedInstances.contains)
        if ownedDeclaration || ownedInstance then
          value match
            case reference: AnyRef =>
              semanticOrigin.captureLexicalName(reference, components :+ name)
            case _ => ()

  private def areaSnapshots(resolved: Map[DomainRef, String]): Vector[KernelAreaSnapshot] =
    closureCharge(areas.size.toLong)
    def path(record: AreaRecord): String =
      val components = Vector(modulePath(record.owner)) ++ areaComponents(record)
      if boundaryWorkActive then
        components.foreach(value => closureCharge(1L + value.length.toLong))
      components.mkString(".")
    def localDomain(record: AreaRecord): Option[String] =
      localDomainCandidate(record.owner, record.domain, path(record))
        .map(domain => domainPath(domainRef(domain))).orElse:
          val members = record.declarations.iterator.flatMap(reference =>
            localDeclarationDomain(
              records(reference.module).declarations(reference.index),
              resolved
            )
          ).toSet
          Option.when(members.size == 1)(members.head)
    areas.toVector.map: record =>
      closureCharge(1L + 2L * record.declarations.size.toLong + 2L * record.members.size.toLong)
      KernelAreaSnapshot(
        path(record),
        modulePath(record.owner),
        record.parent.map(path),
        record.source,
        localDomain(record),
        record.declarations.toVector.map(declarationPath),
        record.members.toVector.flatMap: (name, member) =>
          closureCharge(1L + name.length.toLong)
          val target = member.value match
            case value: InterfaceValueRef[?] =>
              pathOf(checkedInterfaceValueReference(value).endpoint)
            case area: Area => Option(areaRecords.get(area)).map(path)
            case family: GeneratedAreas[?] => Some(generatedRegionPath(family.generatedRegion))
            case instance: Instance[?] =>
              Option(instanceIds.get(instance)).map(record => modulePath(record.child))
            case _ => pathOf(member.value)
          target.map: value =>
            val ownerPath = path(record)
            closureCharge(2L + ownerPath.length.toLong + name.length.toLong)
            KernelAreaMemberSnapshot(name, ownerPath + "." + name, value)
        ,
        generatedScopeSnapshot(record.generatedScope)
      )

  def beginModule(module: Module): Unit =
    if activeGeneratedRegion.exists(_.areaFamily) then
      requireAreaFamilyScope("Module declaration", requireBody = true)
    else requireNoCollectionEffect("Module declaration")
    requireLoopEffect("Module declaration")
    if moduleIds.containsKey(module) then
      fail("NODAL-LIFECYCLE-016", "one Module entered construction twice")
    if moduleStack.size >= 129 || records.size >= 65536 then
      fail(
        "NODAL-HIERARCHY-057-002",
        "concrete hierarchy exceeds its finite occurrence/depth budget"
      )
    val handle = nextModule
    nextModule += 1
    val record = new ModuleRecord(
      handle,
      moduleName(module),
      moduleStack.lastOption.map(_.handle)
    )
    records += handle -> record
    moduleIds.put(module, java.lang.Long.valueOf(handle))
    semanticOrigin.captureModule(handle, module, record.className, record.parentAtConstruction)
    moduleStack += record

  def declareExternalModule(module: Module, name: String): Unit =
    val handle = moduleHandle(module)
    val record = records(handle)
    if moduleStack.lastOption.forall(_.handle != handle) || record.externalName.nonEmpty ||
      record.declarations.nonEmpty || record.domains.nonEmpty || record.instances.nonEmpty
    then fail("NODAL-EXTERNAL-058-001", "external flavor requires the exact new Module owner")
    ExternalModuleContract.validateName(name, record.className)
    record.externalName = Some(name)

  def externalInterfaceArray[I <: Interface, R <: RoleKind](
      module: ExternalModule,
      definition: InterfaceType[I],
      role: Role[R],
      count: Int,
      name: String,
      placement: InoutPlacement,
      profile: ResolutionProfile
  ): InterfaceArray[I, R] =
    val handle = moduleHandle(module)
    if currentModule.handle != handle || records(handle).externalName.isEmpty ||
      activeValueRegion.nonEmpty || generationStack.nonEmpty
    then
      fail(
        "NODAL-EXTERNAL-058-001",
        "external InterfaceArray requires its exact direct ExternalModule owner"
      )
    ExternalModuleContract.validateArray(definition, role, count, placement, profile)
    new InterfaceArray(
      definition,
      role,
      count,
      name,
      None,
      externalAttributes = Vector("placement" -> placement, "profile" -> profile)
    )

  private def externalInterfaceOrigin(reference: DeclarationRef): Option[DeclarationRef] =
    val binding = instanceInterfacePorts.get(reference)
    val original = binding.map(_.port).getOrElse(reference)
    val owner = records(original.module)
    val declaration = owner.declarations(original.index)
    Option.when(
      owner.externalName.nonEmpty && declaration.kind == KernelSignalKind.InterfaceArray &&
        !owner.generatedRegions.exists(_.declarations.contains(original)) && valueRegion(
          original
        ).isEmpty &&
        binding.forall(value =>
          value.generated.isEmpty && value.instance.exists(instance =>
            instance.child == original.module && instance.generatedScope.isEmpty
          )
        )
    )(original)

  def prepareConstructorAllocation(site: String): Int =
    constructorFailure.foreach: (_, firstSite) =>
      fail(
        "NODAL-CONSTRUCTOR-LIFECYCLE-018",
        s"construction transaction was already invalidated by a captured allocation at $firstSite",
        Some(site)
      )
    moduleStack.size

  def poisonConstructor(failure: Throwable, site: String): Unit =
    if constructorFailure.isEmpty then constructorFailure = Some(failure -> site)

  def abortConstructorAllocation(
      depth: Int,
      failure: Throwable,
      site: String,
      invalidate: Boolean
  ): Unit =
    if invalidate then poisonConstructor(failure, site)
    while moduleStack.size > depth do moduleStack.remove(moduleStack.size - 1)

  def constructorDepth: Int = moduleStack.size

  def bindConstructorParameters(
      module: Module,
      parameters: Vector[ConstructorCapturedParameter]
  ): Unit =
    val handle = moduleHandle(module)
    if moduleStack.lastOption.forall(_.handle != handle) then
      fail(
        "NODAL-CONSTRUCTOR-LIFECYCLE-019",
        "constructor parameters can only bind at the exact child Module.begin"
      )
    parameters.foreach: parameter =>
      registerDeclaration(
        parameter.carrier,
        KernelSignalKind.Parameter,
        CandidateRuntime.expressionDataType(parameter.default),
        Some(parameter.name),
        None,
        Vector(
          "default" -> parameter.default,
          "unit" -> CandidateRuntime.expressionUnit(parameter.default).getOrElse("")
        )
      )

  def registerDomain(domain: ClockDomain, kind: KernelDomainKind): Unit =
    requireNoCollectionEffect("domain declaration")
    closeBehavioralChain()
    requireNoGeneratedEffect(s"generated ${kind.label} domain declaration")
    val module = currentModule
    if domainIds.containsKey(domain) then
      fail("NODAL-DOMAIN-016", "one ClockDomain was registered twice")
    if module.domains.exists(_.name == domain.name) then
      fail("NODAL-DOMAIN-017", s"duplicate domain name '${domain.name}'")
    val reference = DomainRef(module.handle, module.domains.size)
    module.domains += DomainRecord(reference, domain, domain.name, kind)
    domainIds.put(domain, reference)
    if kind == KernelDomainKind.External then
      def boundary(value: Expr[?], expected: String): Unit =
        val source = Option(declarationIds.get(value)).getOrElse(
          behavioralFailure(s"local $expected binding requires an actual typed input boundary")
        )
        if source.module != module.handle then
          behavioralFailure(s"local $expected binding belongs to another module")
        val declaration = module.declarations(source.index)
        if declaration.kind != KernelSignalKind.Input ||
          !declaration.dataType.exists(value =>
            CandidateRuntime.typeDescriptor(value) == KernelTypeDescriptor(expected)
          )
        then behavioralFailure(s"local $expected binding requires an exact typed input boundary")
        declaration.domainCandidate.foreach: previous =>
          if previous ne domain then
            behavioralFailure(s"one $expected input cannot belong to two local domains")
        module.declarations.update(source.index, declaration.copy(domainCandidate = Some(domain)))
      domain.clock.foreach(boundary(_, "Clock"))
      if !domain.resetPolicy.contains(ResetPolicy.None) then boundary(domain.reset, "Reset")
    installDomainReset(domain, kind)
    semanticOrigin.captureDomain(module.handle, reference.index, domain, domain.name, kind.label)

  private def requireGeneratedDeclarationScope(reference: DeclarationRef): Unit =
    val generatedDeclaration =
      records(reference.module).generatedRegions.find(_.declarations.contains(reference))
    if generatedDeclaration.exists(region =>
        region.owner != currentModule.handle || !generationStack.exists(_ eq region)
      )
    then
      fail("NODAL-HIERARCHY-044", "local node escaped its generated lexical scope")

  private def validateGeneratedDependencies(values: Iterable[Any]): Unit =
    val pending = mutable.ArrayBuffer.from(values)
    val visited = new IdentityHashMap[AnyRef, java.lang.Boolean]()
    while pending.nonEmpty do
      pending.remove(pending.size - 1) match
        case expression: KernelExpr[?]
            if Option(visited.put(expression, java.lang.Boolean.TRUE)).isEmpty =>
          if expression.operation.contains("generate_index") then
            val reference = Option(expressionIds.get(expression))
            if !generationStack.exists(region =>
                region.owner == currentModule.handle && reference.contains(region.induction)
              )
            then
              fail(
                "NODAL-ITERATION-043-004",
                "generated induction dependency escaped its active owning Module/Generate scope"
              )
          else pending ++= expression.operands
        case node: Node[?] =>
          node.endpointReference.foreach: reference =>
            val selection = shapeIndices.get(reference)
            val scope = selection.flatMap(_.generatedScope)
              .orElse(deferredNodeArray(reference).flatMap(_._1.generatedScope))
            if scope.exists(region =>
                region.owner != currentModule.handle || !generationStack.exists(_ eq region)
              )
            then
              fail("NODAL-HIERARCHY-044", "selected endpoint escaped its generated lexical scope")
            selection.flatMap(_.conservative).foreach: selected =>
              selected.array match
                case declaration: DeclarationRef => requireGeneratedDeclarationScope(declaration)
                case _ => ()
        case expression: Expr[?] =>
          Option(declarationIds.get(expression)).foreach(requireGeneratedDeclarationScope)
        case _ => ()

  def registerDeclaration(
      value: AnyRef,
      kind: KernelSignalKind,
      dataType: Option[DataType[? <: Data]],
      explicitName: Option[String],
      domain: Option[ClockDomain],
      attributes: Vector[(String, Any)],
      conservativeDimensions: Vector[Dimension] = Vector.empty,
      interfaceBoundary: Option[InterfaceBoundaryRecord] = None
  ): Unit =
    registrationOwner(conservativeDimensions, interfaceBoundary).foreach: closure =>
      val proxy = admitPort(value, kind, dataType, explicitName, domain, attributes) ||
        interfaceBoundary.nonEmpty || (value match
          case endpoint: DigitalInout[?, ?] => endpoint.boundary && instanceSelectionStack.nonEmpty
          case _: InterfaceResolvedMember[?, ?] => true
          case _: InterfaceTerminalView[?, ?] => true
          case _ => false)
      if !proxy then closure.reject("incremental closure freezes authored declarations")
      closure.markDirty()
    closeBehavioralChain()
    val interfaceKind = Set(KernelSignalKind.InterfacePort, KernelSignalKind.InterfaceArray)(kind)
    val generatedConservativeView = value match
      case view: InterfaceTerminalView[?, ?] if kind == KernelSignalKind.ConservativeTerminal =>
        val _ = requireConservativeInterfaceScope(view.endpoint)
        activeGeneratedRegion.nonEmpty
      case _ => false
    val selectedInterface = interfaceKind && interfaceBoundary.exists(_.generated.nonEmpty)
    val generatedInterface = interfaceKind && activeGeneratedRegion.exists(_.areaFamily) &&
      (interfaceBoundary.isEmpty || interfaceBoundary.exists(binding =>
        binding.generated.nonEmpty || binding.instance.exists(
          _.generatedScope == activeGeneratedRegion
        )
      ))
    val selectedInstancePort =
      Set(KernelSignalKind.InstanceInput, KernelSignalKind.InstanceOutput)(kind) &&
        instanceSelectionStack.lastOption.exists(_.generated.nonEmpty)
    val generatedInstancePort =
      selectedInstancePort || (Set(
        KernelSignalKind.InstanceInput,
        KernelSignalKind.InstanceOutput
      )(kind) &&
        activeGeneratedRegion.exists(_.areaFamily) &&
        instanceSelectionStack.lastOption.exists(selected =>
          instanceRecord(selected).generatedScope == activeGeneratedRegion
        ))
    if selectedInterface then
      requireAreaFamilyScope(s"${kind.label} selection", requireBody = false)
    else if selectedInstancePort then
      requireAreaFamilyScope(s"${kind.label} selection", requireBody = false)
    else if activeGeneratedRegion.exists(_.areaFamily) &&
      (Set(
        KernelSignalKind.Wire,
        KernelSignalKind.Register
      )(kind) || generatedInstancePort || generatedInterface || generatedConservativeView)
    then requireAreaFamilyScope(s"${kind.label} declaration", requireBody = true)
    else requireNoCollectionEffect(s"${kind.label} declaration")
    requireLoopEffect(s"${kind.label} declaration")
    validateCollectionDependencies(attributes.map(_._2))
    val activeGeneratedVariable =
      kind == KernelSignalKind.Variable && AnalogProceduralConstruction.generatedProcedureActive
    val generatedAreaStorage = activeGeneratedRegion.exists(_.areaFamily) &&
      Set(KernelSignalKind.Wire, KernelSignalKind.Register)(kind)
    if kind != KernelSignalKind.AnalogNode && kind != KernelSignalKind.AnalogNodeArray &&
      !activeGeneratedVariable && !generatedAreaStorage && !generatedInstancePort && !generatedInterface && !selectedInterface && !generatedConservativeView
    then
      requireNoGeneratedEffect(s"generated ${kind.label} declaration")
    val module = currentModule
    value match
      case endpoint: InterfaceEndpoint if interfaceBoundary.isEmpty =>
        var examined = 0
        def visible(definition: InterfaceType[?], depth: Int): Unit =
          examined += 1
          if depth > 128 || examined > 65536 then
            fail("NODAL-INTERFACE-058-SCOPE", "Interface definition ancestry exceeds its budget")
          requireInterfaceDefinitionContext(definition, module.handle).foreach: context =>
            if !requireDeferredValueScope(
                activeValueRegion,
                module.handle
              ).exists(_ eq context.region)
            then
              fail(
                "NODAL-INTERFACE-058-SCOPE",
                "Interface definition escaped its actual lexical construction region"
              )
          definition.members.foreach:
            case nested: InterfaceMember.Nested[?] => visible(nested.definition, depth + 1)
            case _ => ()
        visible(endpoint.definition, 0)
      case _ => ()
    validateGeneratedDependencies(attributes.map(_._2))
    val initial = attributes.collectFirst { case ("initial", value) => value }.toVector.flatMap:
      case expression: Expr[?] => Vector(expression)
      case Some(expression: Expr[?]) => Vector(expression)
      case _ => Vector.empty
    val resolvedType = dataType.orElse(
      Option.when(kind == KernelSignalKind.Register)(initial.headOption
        .flatMap(CandidateRuntime.expressionDataType)).flatten
    )
    val digitalStorage =
      Set(KernelSignalKind.Wire, KernelSignalKind.Register, KernelSignalKind.Memory).contains(kind)
    val deferredInstanceType = resolvedType.exists:
      case deferred: KernelInstanceDataType[?] =>
        !deferred.finalized && (
          instanceSelectionStack.nonEmpty &&
            (Set(KernelSignalKind.InstanceInput, KernelSignalKind.InstanceOutput)(kind) ||
              (kind == KernelSignalKind.DigitalInout && (value match
                case endpoint: DigitalInout[?, ?] => endpoint.boundary
                case _ => false))) ||
            (kind == KernelSignalKind.DigitalInout && (value match
              case view: InterfaceResolvedMember[?, ?] =>
                val root = DigitalInterfaceContract.root(view.endpoint)
                Option(declarationIds.get(root)).exists(reference =>
                  reference.module == module.handle && instanceInterfacePorts.contains(reference)
                )
              case _ => false))
        )
      case _ => false
    resolvedType.filterNot(_ => deferredInstanceType).foreach(validateShapeType(
      _,
      module.handle,
      cardinality = kind == KernelSignalKind.Variable || digitalStorage,
      storageContext = digitalStorage
    ))
    if digitalStorage then
      val storageType = resolvedType.getOrElse(
        fail("NODAL-DIGITAL-054-010", "digital storage requires an exact element type")
      )
      initial.foreach: expression =>
        val initialType = CandidateRuntime.expressionDataType(expression).getOrElse(
          fail("NODAL-DIGITAL-054-010", "digital initializer requires an exact type")
        )
        if !DigitalEnumContract.sameType(
            CandidateRuntime.typeDescriptor(storageType),
            CandidateRuntime.typeDescriptor(initialType)
          )
        then fail("NODAL-DIGITAL-054-010", "digital initializer type differs from its storage")
      if kind == KernelSignalKind.Memory then
        if !attributes.exists:
            case ("readLatency", value: Int) => value >= 0
            case _ => false
        then fail("NODAL-DIGITAL-054-010", "memory read latency must be a nonnegative Integer")
        val depth = attributes.collectFirst { case ("depth", value) => value }.getOrElse(
          fail("NODAL-DIGITAL-054-010", "memory requires an owned positive depth")
        )
        val shaped =
          new KernelDataType[Data](KernelTypeDescriptor("Vec", Vector(storageType, Vector(depth))))
        validateShapeType(shaped, module.handle, cardinality = true, storageContext = true)
    value match
      case array: InterfaceArray[?, ?] if !array.boundary =>
        val shaped =
          new KernelDataType[Data](KernelTypeDescriptor("Vec", Vector(Bool, Vector(array.count))))
        validateShapeType(shaped, module.handle, cardinality = true)
      case _ => ()
    if declarationIds.containsKey(value) then
      fail("NODAL-OWNERSHIP-016", s"${kind.label} was registered twice")
    val reference = allocateDeclarationReference(module)
    module.declarations += DeclarationRecord(
      reference,
      value,
      kind,
      resolvedType,
      explicitName,
      domain.orElse(
        Option.when(
          Set(
            KernelSignalKind.Input,
            KernelSignalKind.Output,
            KernelSignalKind.DigitalInout
          ).contains(kind) &&
            resolvedType.exists(isClockedDigitalType)
        )(currentDomain).flatten
      ),
      attributes,
      conservativeDimensions
    )
    declarationIds.put(value, reference)
    activeArea.foreach(_.declarations += reference)
    semanticOrigin.captureDeclaration(
      module.handle,
      reference.index,
      value,
      kind.label,
      explicitName
    )
    generationStack.lastOption
      .filter(_.owner == module.handle)
      .filter(_ => !selectedInstancePort && !selectedInterface)
      .foreach(_.declarations += reference)

  def registerNodeArray(
      value: NodeArray[?],
      discipline: Discipline,
      dimensions: Vector[Dimension],
      kind: KernelSignalKind
  ): Unit =
    val module = currentModule
    if !conservativeArrayKinds.contains(
        kind
      ) || discipline != Electrical || dimensions.isEmpty || analogStack.nonEmpty ||
      analogSemanticContext.nonEmpty
    then
      fail(
        "NODAL-SHAPE-043-005",
        "node arrays require an owned Electrical declaration outside analog regions"
      )
    if kind != KernelSignalKind.AnalogNodeArray then
      requireNoGeneratedEffect("generated boundary-array declaration")
    val analysis =
      new StructuralBoundAnalysis(
        module.handle,
        "NODAL-SHAPE-043-005",
        "node-array dimension",
        signed64Literals = true
      )
    val bounds = dimensions.map(analysis(_))
    if bounds.exists(_.bounds.lower <= 0L) ||
      bounds.foldLeft(BigInt(1))((count, bound) => count * BigInt(bound.bounds.upper)) >
        BigInt(Long.MaxValue)
    then
      fail(
        "NODAL-SHAPE-043-005",
        "node arrays require positive finite dimensions and signed-64-bit element counts"
      )
    registerDeclaration(
      value,
      kind,
      None,
      None,
      None,
      CandidateRuntime.conservativeAttributes(discipline),
      dimensions
    )
    bounds.flatMap(_.parameters).distinct.foreach: parameter =>
      markStructuralParameterEffect(parameter, "shape")
      markStructuralParameterEffect(parameter, "rank")

  private val conservativePortKinds =
    Set(KernelSignalKind.AnalogInput, KernelSignalKind.AnalogOutput, KernelSignalKind.AnalogInout)
  private val conservativeArrayKinds = conservativePortKinds + KernelSignalKind.AnalogNodeArray

  private def instanceArrayRoot(reference: ExpressionRef)
      : Option[(Operation, InstanceArrayRootRecord)] =
    operations.iterator.flatMap(operation =>
      operation.values.collect {
        case root: InstanceArrayRootRecord if root.reference == reference => operation -> root
      }
    ).nextOption()

  private def deferredNodeArray(reference: ExpressionRef)
      : Option[(Operation, DeferredNodeArrayRecord)] =
    operations.iterator.flatMap(operation =>
      operation.values.collect {
        case record: DeferredNodeArrayRecord if record.reference == reference => operation -> record
      }
    ).nextOption()

  /** References are parent-owned declarations in the existing value/assignment registry. */
  private val instanceDigitalPorts =
    mutable.LinkedHashMap.empty[DeclarationRef, InstancePortBoundaryRecord]
  private val instanceDigitalInouts =
    mutable.LinkedHashMap.empty[DeclarationRef, (InstanceRecord, DeclarationRef)]
  private var occurrenceDomains = Map.empty[DomainRef, String]
  private val selectedRequirementBindings =
    mutable.LinkedHashMap.empty[DomainRef, (DomainRef, String, Long, Int)]

  private val instanceInterfacePorts =
    mutable.LinkedHashMap.empty[DeclarationRef, InterfaceBoundaryRecord]

  private val instanceInterfaceDimensions =
    mutable.LinkedHashMap.empty[DeclarationRef, Vector[Dimension]]
  private val instanceInterfaceTypes =
    mutable.LinkedHashMap.empty[DeclarationRef, Map[KernelTypeDescriptor, DataType[?]]]

  // One exact source-view registry. It records no physical net or independent role map.
  private val resolvedInterfaceViews = new IdentityHashMap[
    InterfaceEndpoint,
    mutable.LinkedHashMap[String, ResolvedDigitalCapability[?, ?]]
  ]()

  // Views share the existing declaration/capture owner, not a topology or role registry.
  private val conservativeInterfaceViews = new IdentityHashMap[
    InterfacePort[?, ?],
    mutable.LinkedHashMap[(String, String), ConservativeView[?, ?]]
  ]()

  private def conservativeMemberSnapshot(declaration: DeclarationRecord)
      : Option[KernelInterfaceMemberOrigin] = declaration.value match
    case view: InterfaceTerminalView[?, ?] =>
      val root = DigitalInterfaceContract.root(view.endpoint)
      Some(KernelInterfaceMemberOrigin(
        declarationPath(declarationIds.get(root)),
        view.member,
        view.endpoint.role.name
      ))
    case _ => None

  private def conservativeIntegerLiteral(value: Any): Option[Long] = value match
    case value: Int => Some(value.toLong)
    case value: KernelExpr[?] =>
      value.literal.filter(_.kind == "integer").flatMap(_.value.toLongOption)
    case _ => None

  /** The additive nested profile is exactly two original rectangular literal axes. */
  private def nestedConservativeFamily(family: GeneratedRegionRecord): Boolean =
    def axis(value: GeneratedRegionRecord): Boolean =
      value.areaFamily && value.digitalRegion.nonEmpty &&
        conservativeIntegerLiteral(value.lower).contains(0L) &&
        conservativeIntegerLiteral(value.step).contains(1L) &&
        conservativeIntegerLiteral(value.upperExclusive).exists(_ > 0L)
    family.parentOrdinal.flatMap(index => currentModule.generatedRegions.find(_.ordinal == index))
      .exists(parent =>
        (parent ne family) && parent.owner == family.owner && parent.parentOrdinal.isEmpty &&
          parent.digitalRegion.exists(_.parent.isEmpty) &&
          family.digitalRegion.exists(_.parent == parent.digitalRegion) && axis(parent) && axis(family)
      )

  /** Selected conservative views retain a real authored body boundary, not invented incidence. */
  private def requireSelectedConservativeChild(
      binding: InterfaceBoundaryRecord
  ): Option[DeclarationRef] =
    def invalid(message: String): Nothing = fail("NODAL-INTERFACE-058-ANALOG", message)
    val instance = binding.instance.getOrElse(
      invalid("selected conservative endpoint requires its child instance")
    )
    val selection = binding.generated.getOrElse(
      invalid("selected conservative endpoint requires its actual selection")
    )
    val (family, area) = generatedSelectedArea(selection.reference)
    val exported = area.members.getOrElse(
      selection.member,
      invalid("selected conservative child export is absent")
    )
    val (_, target) = generatedInstanceExportTarget(family, exported)
    val child = records(instance.child)
    val original = child.declarations.lift(
      binding.port.index
    ).getOrElse(invalid("selected conservative child endpoint is absent"))
    val endpoint = original.value match
      case value: InterfacePort[?, ?] => value
      case _ => invalid("selected conservative child requires a scalar Interface")
    val leaves =
      ConstructionInterfaceLayout.leaves(endpoint.definition, endpoint.role, charge = closureCharge)
    val nested = selection.reference.family.selectionPrefix.size == 1 &&
      nestedConservativeFamily(family)
    val literalOrdinal = selection.reference.ordinal match
      case _: Int => true
      case value: KernelExpr[?] => value.literal.exists(_.kind == "integer")
      case _ => false
    if (target ne instance) || !currentModule.instances.exists(_ eq instance) ||
      binding.port.module != instance.child || original.kind != KernelSignalKind.InterfacePort ||
      endpoint.boundary || endpoint.origin.nonEmpty ||
      activeGeneratedRegion.nonEmpty || activeValueRegion.nonEmpty || binding.scope.nonEmpty ||
      (!nested && (family.parentOrdinal.nonEmpty || family.digitalRegion.isEmpty ||
        family.digitalRegion.exists(_.parent.nonEmpty) ||
        selection.reference.family.selectionPrefix.nonEmpty)) || !literalOrdinal ||
      (nested && !selection.reference.family.selectionPrefix.forall(value =>
        conservativeIntegerLiteral(value.ordinal).exists(ordinal =>
          ordinal >= 0L && conservativeIntegerLiteral(value.family.upperExclusive).exists(ordinal < _)
        )
      )) ||
      !instance.generatedScope.contains(family) ||
      leaves.isEmpty || !leaves.forall(leaf =>
        leaf.protocol == "conservative" && leaf.mode.isEmpty && leaf.analogType.nonEmpty &&
          leaf.dataType.isEmpty && leaf.access == "connect"
      )
    then invalid("selected conservative child requires its exact constant scalar connect profile")
    // This is profile admission only. The existing override policy owns binding,
    // type, ownership and duplicate validation; the scalar materializer owns substitution.
    def realLiteral(value: Any): Boolean = value match
      case expression: KernelExpr[?] =>
        expression.literal.exists(literal =>
          literal.kind == "real" && literal.value.toDoubleOption.exists(_.isFinite)
        ) && CandidateRuntime.expressionUnit(expression).forall(_.isEmpty)
      case _ => false
    def ordinaryDimensionlessReal(parameter: DeclarationRecord): Boolean =
      parameter.kind == KernelSignalKind.Parameter && parameter.dataType.exists(
        CandidateRuntime.typeDescriptor(_) == KernelTypeDescriptor("Real")
      ) && parameter.attributes.iterator.map(_._1).toSet.size == parameter.attributes.size &&
        parameter.attributes.find(_._1 == "classification").forall(_._2 == "ordinary") &&
        parameter.attributes.find(_._1 == "unit").forall(_._2 == "")
    val parent = currentModule
    val generatedDeclarations = parent.generatedRegions.iterator.flatMap(_.declarations).toSet
    val analogExpressions = analogRegions.iterator.flatMap(_.expressions).toSet
    def parentReal(value: Any): Boolean =
      val heights = new IdentityHashMap[AnyRef, java.lang.Integer]()
      val active = new IdentityHashMap[AnyRef, java.lang.Boolean]()
      var work = 0
      // Admission follows canonical identities without evaluating arithmetic or reading defaults.
      // Charge every edge, even memo hits; retain subtree heights for shared deep paths.
      def visit(value: Any, depth: Int, before: Long): Int =
        work += 1
        if work > 65536 || depth >= 512 then
          invalid(
            "selected conservative parent Real expression exceeds its finite work/depth budget"
          )
        val (identity, reference): (AnyRef, DeclarationRef | ExpressionRef) = value match
          case parameter: Param[?] =>
            val reference = Option(declarationIds.get(parameter)).getOrElse(
              invalid("selected conservative parent parameter has no canonical identity")
            )
            if reference.module != parent.handle || generatedDeclarations.contains(reference) ||
              !parent.declarations.lift(reference.index).exists(declaration =>
                (declaration.reference eq reference) && (declaration.value eq parameter) &&
                  ordinaryDimensionlessReal(declaration)
              )
            then invalid("selected conservative parameter must be an exact ordinary parent Real")
            parameter -> reference
          case expression: KernelExpr[?] =>
            val reference = Option(expressionIds.get(expression)).getOrElse(
              invalid("selected conservative parent expression has no canonical identity")
            )
            if reference.module != parent.handle || reference.index < 0 ||
              !expressionValues.get(reference).exists(_ eq expression) ||
              expressionRegions.contains(reference) || analogExpressions.contains(reference) ||
              !expression.constructionFinalized ||
              !expression.resultDescriptor.contains(KernelTypeDescriptor("Real"))
            then invalid("selected conservative expression must be an exact module-owned Real")
            expression -> reference
          case _ => invalid("selected conservative expression has a non-static Real operand")
        val allocation = reference.constructionAllocation.getOrElse(
          invalid("selected conservative parent value lost its original allocation")
        )
        if (allocation.session ne this) || (allocation.module ne parent) ||
          allocation.serial >= before
        then invalid("selected conservative operands must precede their authored consumer")
        if active.containsKey(identity) then
          invalid("selected conservative parent expression is cyclic")
        Option(heights.get(identity)) match
          case Some(height) =>
            if depth + height.intValue >= 512 then
              invalid(
                "selected conservative parent Real expression exceeds its finite depth budget"
              )
            height.intValue
          case None =>
            active.put(identity, java.lang.Boolean.TRUE)
            val height = identity match
              case _: Param[?] => 0
              case expression: KernelExpr[?] if expression.literal.nonEmpty =>
                if !realLiteral(expression) ||
                  !expression.literal.exists(_.dataType == KernelTypeDescriptor("Real")) ||
                  !expression.operation.contains("real_literal") ||
                  !(expression.operands match
                    case Vector(number: Double, unit: String) =>
                      number.isFinite && unit.isEmpty && expression.literal.exists(
                        _.value == java.lang.Double.toString(number)
                      )
                    case _ => false
                  )
                then
                  invalid("selected conservative expression requires finite unitless Real literals")
                0
              case expression: KernelExpr[?] =>
                val arity = expression.operation match
                  case Some("analog_add" | "analog_sub" | "analog_mul") => 2
                  case Some("analog_neg") => 1
                  case _ => invalid("selected conservative parent Real operation is unsupported")
                if expression.operands.size != arity then
                  invalid("selected conservative parent Real operation has invalid arity")
                expression.operands.iterator.map(operand =>
                  visit(operand, depth + 1, allocation.serial) + 1
                ).max
              case _ => invalid("selected conservative parent Real identity is unsupported")
            active.remove(identity)
            heights.put(identity, java.lang.Integer.valueOf(height))
            height
      visit(value, 0, Long.MaxValue)
      true
    val parameters = child.declarations.filter(_.kind == KernelSignalKind.Parameter)
    if !parameters.forall(parameter =>
        ordinaryDimensionlessReal(parameter) &&
          parameter.attributes.find(_._1 == "default").exists(entry => realLiteral(entry._2))
      ) || !instance.parameterOverrides.forall((_, value) =>
        realLiteral(value) || (!nested && parentReal(value))
      )
    then
      invalid(
        "selected conservative child parameters require ordinary dimensionless Real literals " +
          "or parent Real arithmetic, with literal child defaults"
      )
    val analysis = new StructuralBoundAnalysis(
      currentModule.handle,
      "NODAL-INTERFACE-058-ANALOG",
      "selected conservative family",
      signed64Literals = true
    )
    val inputs = Vector(family.lower, family.upperExclusive, family.step).map(value =>
      analysis(value.asInstanceOf[Dimension])
    )
    val fixedFamily = inputs.forall(value =>
      value.parameters.isEmpty && value.bounds.lower == value.bounds.upper
    )
    def integerLiteral(value: Any, expected: Long): Boolean = value match
      case value: Int => value.toLong == expected
      case value: KernelExpr[?] =>
        value.literal.exists(literal =>
          literal.kind == "integer" && literal.value.toLongOption.contains(expected)
        )
      case _ => false
    // Admit source identity, not an arithmetic expression normalized to the same bounds.
    val symbolicExtent = family.upperExclusive match
      case parameter: Param[?]
          if integerLiteral(family.lower, 0L) && integerLiteral(family.step, 1L) =>
        Option(declarationIds.get(parameter)).filter: reference =>
          val declaration = records(reference.module).declarations(reference.index)
          reference.module == parent.handle &&
          (declaration.value.asInstanceOf[AnyRef] eq parameter) &&
          inputs(1).parameters == Vector(reference) && !generatedDeclarations.contains(reference) &&
          declaration.kind == KernelSignalKind.Parameter && declaration.dataType.exists(
            CandidateRuntime.typeDescriptor(_) == KernelTypeDescriptor("Integer")
          ) && declaration.attributes.iterator.map(
            _._1
          ).toSet.size == declaration.attributes.size &&
          declaration.attributes.contains("classification" -> "structural")
      case _ => None
    if (!fixedFamily && symbolicExtent.isEmpty) ||
      family.maximumTripCount <= 0 || inputs(2).bounds.lower <= 0 ||
      !IterationDomain.verifyOrdinal(
        analysis.selectedGeneratedDomain(family),
        analysis(selection.reference.ordinal).bounds
      ).isRight
    then
      invalid(
        "selected conservative child requires a fixed family or direct structural Integer extent and a universal literal ordinal"
      )
    val authenticBody = instanceInterfacePorts.exists: (reference, body) =>
      body.instance.exists(_ eq instance) && body.port == binding.port && body.generated.isEmpty &&
        body.scope == family.digitalRegion && reference.module == family.owner &&
        family.declarations.contains(reference)
    if !authenticBody then
      invalid("selected conservative child requires its authentic authored body Interface boundary")
    symbolicExtent

  private def requireConservativeInterfaceScope(endpoint: InterfacePort[?, ?]): DeclarationRef =
    val root = DigitalInterfaceContract.root(endpoint)
    val reference = Option(declarationIds.get(root)).getOrElse(
      fail("NODAL-INTERFACE-058-ANALOG", "conservative member requires an owned Interface")
    )
    if reference.module != currentModule.handle || (root.boundary && endpoint.origin.nonEmpty)
    then
      fail(
        "NODAL-INTERFACE-058-ANALOG",
        "conservative member belongs to another module, role view or selected family"
      )
    instanceInterfacePorts.get(
      reference
    ).filter(_.generated.nonEmpty).foreach(binding =>
      val _ = requireSelectedConservativeChild(binding)
    )
    requireGeneratedDeclarationScope(reference)
    activeGeneratedRegion match
      case None => requireNoGeneratedEffect("conservative Interface member selection")
      case Some(generated) =>
        requireAreaFamilyScope("conservative Interface member selection", requireBody = true)
        val boundary = instanceInterfacePorts.get(reference)
        val nested = nestedConservativeFamily(generated)
        val leaves = ConstructionInterfaceLayout.leaves(
          endpoint.definition,
          endpoint.role,
          charge = closureCharge
        )
        if (nested && (boundary.isEmpty || !leaves.forall(_.access == "connect"))) ||
          (!nested &&
            (generated.parentOrdinal.nonEmpty || activeValueRegion.exists(_.parent.nonEmpty))) ||
          generated.digitalRegion != activeValueRegion ||
          !generated.declarations.contains(reference) || leaves.isEmpty ||
          !leaves.forall(leaf =>
            leaf.protocol == "conservative" && leaf.mode.isEmpty &&
              leaf.analogType.nonEmpty && leaf.dataType.isEmpty
          ) ||
          boundary.exists(binding =>
            binding.generated.nonEmpty ||
              binding.scope != generated.digitalRegion || !binding.instance.exists(instance =>
                currentModule.instances.exists(_ eq instance) &&
                  instance.generatedScope.contains(
                    generated
                  ) && binding.port.module == instance.child
              )
          )
        then
          fail(
            "NODAL-INTERFACE-058-ANALOG",
            "generated conservative member requires its direct scalar endpoint and actual occurrence"
          )
    reference

  private def requireGeneratedConservativeConnection(values: Vector[Any]): Unit =
    val generated = activeGeneratedRegion.get
    requireAreaFamilyScope("conservative connection", requireBody = true)
    if values.size != 2 then
      fail("NODAL-INTERFACE-058-ANALOG", "conservative connection requires two endpoints")
    var local = false
    values.foreach:
      case view: InterfaceTerminalView[?, ?] =>
        val _ = requireConservativeInterfaceScope(view.endpoint)
        val reference = Option(declarationIds.get(view)).getOrElse(
          fail("NODAL-INTERFACE-058-ANALOG", "conservative view has no captured identity")
        )
        if view.permission != "connect" || !generated.declarations.contains(reference) then
          fail(
            "NODAL-INTERFACE-058-ANALOG",
            "connection requires the actual body-local connect view"
          )
        local = true
      case terminal: Terminal[?] =>
        val reference = Option(declarationIds.get(terminal)).getOrElse(
          fail("NODAL-INTERFACE-058-ANALOG", "outer terminal has no captured identity")
        )
        if reference.module != currentModule.handle ||
          currentModule.generatedRegions.exists(_.declarations.contains(reference))
        then
          fail(
            "NODAL-INTERFACE-058-ANALOG",
            "outer terminal must be an actual Module-owned endpoint"
          )
      case _ => fail(
          "NODAL-INTERFACE-058-ANALOG",
          "generated connection requires exact terminal capabilities"
        )
    if !local then
      fail(
        "NODAL-INTERFACE-058-ANALOG",
        "generated connection requires a body-local Interface endpoint"
      )

  def interfaceConservativeMember[D <: Discipline, A <: ConservativeAccess](
      endpoint: InterfacePort[?, ?],
      field: InterfaceMember.Conservative[D],
      through: Vector[InterfaceMember.Nested[?]],
      permission: String
  ): ConservativeView[D, A] =
    val reference = requireConservativeInterfaceScope(endpoint)
    if permission != "connect" && instanceInterfacePorts.get(reference).exists(_.generated.nonEmpty)
    then
      fail(
        "NODAL-INTERFACE-058-ANALOG",
        "selected conservative child permits only its exact connect capability"
      )
    val member = ConstructionInterfaceLayout.exactMemberPath(endpoint.definition, field, through)
    val leaves =
      ConstructionInterfaceLayout.leaves(endpoint.definition, endpoint.role, charge = closureCharge)
    val leaf =
      closureElements(leaves).find(_.name == member).getOrElse(
        fail("NODAL-INTERFACE-058-ANALOG", "conservative member is absent from its selected role")
      )
    if leaf.protocol != "conservative" ||
      (permission != "sense" && leaf.access != permission) ||
      !Set("connect", "sense", "contribute").contains(permission)
    then
      fail("NODAL-INTERFACE-058-ANALOG", "conservative member lacks its requested role capability")
    val known = Option(conservativeInterfaceViews.get(endpoint)).getOrElse:
      val created = mutable.LinkedHashMap.empty[(String, String), ConservativeView[?, ?]]
      conservativeInterfaceViews.put(endpoint, created)
      created
    known.getOrElseUpdate(
      member -> permission, {
        val view = new InterfaceTerminalView[D, A](endpoint, field, through, member, permission)
        registerDeclaration(
          view,
          KernelSignalKind.ConservativeTerminal,
          None,
          Some(s"conservative_member_${currentModule.declarations.size}"),
          None,
          CandidateRuntime.conservativeAttributes(field.discipline)
        )
        view
      }
    ).asInstanceOf[ConservativeView[D, A]]

  private def resolvedMemberSnapshot(declaration: DeclarationRecord)
      : Option[KernelInterfaceMemberOrigin] = declaration.value match
    case view: InterfaceResolvedMember[?, ?] =>
      val root = DigitalInterfaceContract.root(view.endpoint)
      Some(KernelInterfaceMemberOrigin(
        declarationPath(declarationIds.get(root)),
        view.member,
        view.endpoint.role.name
      ))
    case _ => None

  private def checkedInterfaceValueRecord(
      endpoint: InterfaceEndpoint,
      field: InterfaceMember.Value[?],
      through: Vector[InterfaceMember.Nested[?]]
  ): InterfaceValueReadRecord =
    val member = ConstructionInterfaceLayout.exactMemberPath(endpoint.definition, field, through)
    val leaves =
      ConstructionInterfaceLayout.leaves(endpoint.definition, endpoint.role, charge = closureCharge)
    val leaf =
      closureElements(leaves).find(_.name == member).getOrElse(
        fail("NODAL-INTERFACE-054-003", "ordinary read is absent from the selected role")
      )
    if leaf.protocol != "plain" || leaf.mode.nonEmpty || leaf.analogType.nonEmpty ||
      !Set("in", "out", "observe")(leaf.access) ||
      !DigitalCombinationalContract.isValueTypeKind(CandidateRuntime.typeDescriptor(field.dataType))
    then
      fail(
        "NODAL-INTERFACE-054-003",
        "ordinary read requires a storable digital Value and visible role"
      )
    InterfaceValueReadRecord(endpoint, field, through, member)

  private def requireInterfaceValueUse(endpoint: InterfaceEndpoint): DeclarationRef =
    val root = DigitalInterfaceContract.root(endpoint)
    val reference = Option(declarationIds.get(root)).getOrElse(
      fail("NODAL-INTERFACE-054-007", "ordinary read requires an actual owned Interface endpoint")
    )
    if reference.module != currentModule.handle then
      fail("NODAL-INTERFACE-054-007", "ordinary read belongs to another Module")
    requireGeneratedDeclarationScope(reference)
    valueRegion(reference).foreach: scope =>
      if !collectionStack.exists(_ eq scope) then
        fail("NODAL-INTERFACE-054-007", "ordinary read endpoint escaped its actual region")
    if activeValueRegion.nonEmpty then
      requireAreaFamilyScope("ordinary Interface read", requireBody = false)
    reference

  def interfaceValueReference[A <: Data](
      endpoint: InterfaceEndpoint,
      field: InterfaceMember.Value[?],
      through: Vector[InterfaceMember.Nested[?]]
  ): InterfaceValueRef[A] =
    val _ = requireInterfaceValueUse(endpoint)
    new InterfaceValueRef[A](checkedInterfaceValueRecord(endpoint, field, through))

  private def checkedInterfaceValueReference(value: InterfaceValueRef[?])
      : InterfaceValueReadRecord =
    val original = value.memberRecord
    val checked = checkedInterfaceValueRecord(original.endpoint, original.field, original.through)
    if checked.member != original.member then
      fail("NODAL-INTERFACE-054-003", "ordinary member reference lost its exact declared path")
    checked

  def interfaceValueRefRead(value: InterfaceValueRef[?]): Expr[?] =
    val record = checkedInterfaceValueReference(value)
    interfaceValueRead(record.endpoint, record.field, record.through)

  private def rebindInterfaceValue[A <: Data](
      original: InterfaceValueRef[A],
      endpoint: InterfaceEndpoint
  ): InterfaceValueRef[A] =
    val record = checkedInterfaceValueReference(original)
    if endpoint.definition ne record.endpoint.definition then
      fail("NODAL-INTERFACE-054-003", "ordinary member projection changed its original definition")
    if endpoint.role ne record.endpoint.role then
      fail("NODAL-INTERFACE-054-003", "ordinary member projection changed its actual role")
    val rebound = checkedInterfaceValueRecord(endpoint, record.field, record.through)
    if rebound.member != record.member then
      fail("NODAL-INTERFACE-054-003", "ordinary member projection changed its exact nested path")
    new InterfaceValueRef[A](rebound)

  def instanceInterfaceValue[A <: Data](value: InterfaceValueRef[A]): InterfaceValueRef[A] =
    val record = checkedInterfaceValueReference(value)
    val projected = record.endpoint match
      case port: InterfacePort[?, ?] => instanceInterfacePort(port)
      case array: InterfaceArray[?, ?] => instanceInterfaceArray(array)
    rebindInterfaceValue(value, projected)

  def interfaceValueRead(
      endpoint: InterfaceEndpoint,
      field: InterfaceMember.Value[?],
      through: Vector[InterfaceMember.Nested[?]]
  ): Expr[?] =
    val reference = requireInterfaceValueUse(endpoint)
    val root = DigitalInterfaceContract.root(endpoint)
    val read = checkedInterfaceValueRecord(endpoint, field, through)
    def actual(): KernelTypeDescriptor =
      val dimensions = interfaceDimensions(records(reference.module).declarations(reference.index))
      val dataType = interfaceActualType(reference, field.dataType)
      val result = if dimensions.isEmpty then dataType else Vec(dataType, dimensions*)
      val _ = shapeTypeFootprint(
        result,
        reference.module,
        cardinality = true,
        storageContext = true,
        bitPatternOnly = true,
        allowUnattained = true
      )
      CandidateRuntime.typeDescriptor(result)
    DigitalCombinationalContract.validateValueType(CandidateRuntime.typeDescriptor(field.dataType))
    val expression = new KernelExpr[Data](
      Vector(root),
      Some(CandidateRuntime.typeDescriptor(field.dataType)),
      operation = Some(DigitalInterfaceContract.OrdinaryReadOperation),
      typeIntent = Some(new KernelResultTypeIntent(Vector(root), () => actual())),
      interfaceMember = Some(read)
    )
    val _ = captureExpression(expression)
    expression

  def interfaceResolvedMember[A <: Bits, M <: DriveMode](
      endpoint: InterfacePort[?, ?],
      field: InterfaceMember.DigitalResolved[A, M],
      through: Vector[InterfaceMember.Nested[?]]
  ): ResolvedDigital[A, M] =
    interfaceResolvedCapability(endpoint, field, through).asInstanceOf[ResolvedDigital[A, M]]

  def interfaceResolvedArrayMember[A <: Bits, M <: DriveMode](
      endpoint: InterfaceArray[?, ?],
      field: InterfaceMember.DigitalResolved[A, M],
      through: Vector[InterfaceMember.Nested[?]]
  ): ResolvedDigitalArray[A, M] =
    interfaceResolvedCapability(endpoint, field, through).asInstanceOf[ResolvedDigitalArray[A, M]]

  private def interfaceResolvedCapability[A <: Bits, M <: DriveMode](
      endpoint: InterfaceEndpoint,
      field: InterfaceMember.DigitalResolved[A, M],
      through: Vector[InterfaceMember.Nested[?]]
  ): ResolvedDigitalCapability[?, ?] =
    requireNoGeneratedEffect("resolved Interface member selection")
    val root = DigitalInterfaceContract.root(endpoint)
    val reference = Option(declarationIds.get(root)).getOrElse(
      fail("NODAL-INTERFACE-054-007", "resolved member requires an actual owned Interface endpoint")
    )
    val roleView = endpoint match
      case port: InterfacePort[?, ?] => port.origin.nonEmpty
      case array: InterfaceArray[?, ?] => array.origin.nonEmpty
    if reference.module != currentModule.handle || activeValueRegion.nonEmpty ||
      (root.boundary && roleView) ||
      instanceInterfacePorts.get(reference).exists(_.generated.nonEmpty)
    then
      fail(
        "NODAL-INTERFACE-054-007",
        "resolved member endpoint belongs to another module, role view or selected family"
      )
    val member = ConstructionInterfaceLayout.resolvedMemberPath(endpoint.definition, field, through)
    val leaves =
      ConstructionInterfaceLayout.leaves(endpoint.definition, endpoint.role, charge = closureCharge)
    val leaf =
      closureElements(leaves).find(_.name == member).getOrElse(
        fail("NODAL-INTERFACE-054-003", "resolved member is absent from its selected role")
      )
    if leaf.protocol != "resolved" || !leaf.mode.contains(field.mode.name) then
      fail("NODAL-INTERFACE-054-003", "resolved member requires its actual resolved declaration")
    val known = Option(resolvedInterfaceViews.get(endpoint)).getOrElse:
      val created = mutable.LinkedHashMap.empty[String, ResolvedDigitalCapability[?, ?]]
      resolvedInterfaceViews.put(endpoint, created)
      created
    known.getOrElseUpdate(
      member, {
        val view: InterfaceResolvedMember[?, M] = endpoint match
          case port: InterfacePort[?, ?] =>
            val dataType: DataType[A] = if root.boundary then
              new KernelInstanceDataType[A](CandidateRuntime.typeDescriptor(field.dataType))
            else field.dataType
            new InterfaceResolvedView(dataType, field.mode, port, field, through, member)
          case array: InterfaceArray[?, ?] =>
            val formalData = Vec(field.dataType, array.formalCount)
            val formalEnable = Vec(Bool, array.formalCount)
            val dataType: DataType[Vec[A]] = if root.boundary then
              new KernelInstanceDataType[Vec[A]](CandidateRuntime.typeDescriptor(formalData))
            else formalData
            val enableType: DataType[Vec[Bool]] = if root.boundary then
              new KernelInstanceDataType[Vec[Bool]](CandidateRuntime.typeDescriptor(formalEnable))
            else formalEnable
            new InterfaceResolvedArrayView(
              dataType,
              enableType,
              field.mode,
              array,
              field,
              through,
              member
            )
        val name = s"resolved_member_${currentModule.declarations.size}"
        registerDeclaration(
          view,
          KernelSignalKind.DigitalInout,
          Some(view.dataType),
          Some(name),
          None,
          Vector("mode" -> field.mode.name)
        )
        view
      }
    )

  private def materializeResolvedInterfaceViews(): Unit =
    // Shape validation can refine existing declaration records. Retain only the
    // authored member-view inventory before invoking that shared mutation owner.
    closureCharge(records.size.toLong)
    val views = records.valuesIterator.flatMap(module =>
      closureCharge(module.declarations.size.toLong)
      module.declarations.iterator.flatMap(declaration =>
        declaration.value match
          case view: InterfaceResolvedMember[?, ?] => Some(module.handle -> view)
          case _ => None
      )
    ).toVector
    views.filter((_, view) => closureAccepts("resolved", view)).foreach: (owner, view) =>
      val endpoint = declarationIds.get(DigitalInterfaceContract.root(view.endpoint))
      val dimensions = interfaceDimensions(records(endpoint.module).declarations(endpoint.index))
      val actual = interfaceActualType(endpoint, view.declaration.dataType)
      def seal(dataType: DataType[?], resolved: DataType[?]): Unit = dataType match
        case deferred: KernelInstanceDataType[?] =>
          deferred.seal(CandidateRuntime.typeDescriptor(resolved))
          validateShapeType(deferred, owner, cardinality = false)
        case _ => ()
      seal(view.dataType, if dimensions.isEmpty then actual else Vec(actual, dimensions*))
      seal(view.enableDataType, if dimensions.isEmpty then Bool else Vec(Bool, dimensions*))

  // Only a typed, genuinely global immutable singleton initializer enters this scope.
  // Preserve all expression/capture ownership, and never affect another Module or region.
  private var globalInterfaceInitializers =
    List.empty[(ModuleRecord, Option[DigitalValueRegionRecord])]

  def initializeGlobalInterface[A](initializer: () => A): A =
    val previous = globalInterfaceInitializers
    moduleStack.lastOption.foreach: owner =>
      globalInterfaceInitializers = (owner -> activeValueRegion) :: previous
    try initializer()
    finally globalInterfaceInitializers = previous

  def interfaceDefinitionContext(): Option[InterfaceDefinitionContext] =
    if globalInterfaceInitializers.exists((owner, region) =>
        moduleStack.lastOption.exists(_ eq owner) && region == activeValueRegion
      )
    then None
    else captureInterfaceDefinitionContext()

  private def captureInterfaceDefinitionContext(): Option[InterfaceDefinitionContext] =
    activeValueRegion.filter(region =>
      requireDeferredValueScope(Some(region), currentModule.handle).forall(value =>
        value.structuralArm.nonEmpty || value.loop.exists(_.generated.nonEmpty)
      )
    ).map: region =>
      if region.assignmentCount >= 4098 then
        collectionFailure("regional Interface construction exceeds the 4098 operation budget")
      val ordinal = region.assignmentCount
      region.assignmentCount += 1
      InterfaceDefinitionContext(
        currentModule,
        region,
        ordinal,
        semanticOrigin.captureSemanticSource()
      )

  private def requireInterfaceDefinitionContext(definition: InterfaceType[?], owner: Long)
      : Option[InterfaceDefinitionContext] =
    definition.constructionContext.map: context =>
      if !records.get(
          owner
        ).exists(_ eq context.owner) || context.region.reference.module != owner ||
        !requireDeferredValueScope(Some(context.region), owner).forall(value =>
          value.structuralArm.nonEmpty || value.loop.exists(_.generated.nonEmpty)
        ) ||
        context.sourceOrdinal < 0 || context.sourceOrdinal >= context.region.assignmentCount
      then
        fail("NODAL-INTERFACE-058-SCOPE", "Interface definition lost its actual construction owner")
      context

  private def withInterfaceDefinitionScope[A](
      definition: InterfaceType[?],
      owner: Long
  )(body: => A): A =
    val context = requireInterfaceDefinitionContext(definition, owner)
    inValueScope(owner, context.map(_.region))(body)

  private def inValueScope[A](owner: Long, scope: Option[DigitalValueRegionRecord])(body: => A): A =
    val previousModules = moduleStack.toVector
    val previousRegions = collectionStack.toVector
    val previousGeneration = generationStack.toVector
    installValueScope(owner, scope)
    try body
    finally
      moduleStack.clear(); moduleStack ++= previousModules
      collectionStack.clear(); collectionStack ++= previousRegions
      generationStack.clear(); generationStack ++= previousGeneration

  private def interfaceActualType(reference: DeclarationRef, formal: DataType[?]): DataType[?] =
    instanceInterfaceTypes.get(reference).map(_.getOrElse(
      CandidateRuntime.typeDescriptor(formal),
      fail("NODAL-HIERARCHY-058-INTERFACE", "projected Interface member type was not finalized")
    )).getOrElse(formal)

  def instanceInterfacePort[I <: Interface, R <: RoleKind](
      value: InterfacePort[I, R]
  ): InterfacePort[I, R] =
    instanceInterfaceEndpoint(value, KernelSignalKind.InterfacePort)
      .asInstanceOf[InterfacePort[I, R]]

  def instanceInterfaceArray[I <: Interface, R <: RoleKind](
      value: InterfaceArray[I, R]
  ): InterfaceArray[I, R] =
    instanceInterfaceEndpoint(value, KernelSignalKind.InterfaceArray)
      .asInstanceOf[InterfaceArray[I, R]]

  private def instanceInterfaceEndpoint(
      value: InterfaceEndpoint,
      kind: KernelSignalKind
  ): InterfaceEndpoint =
    val selected = instanceSelectionStack.lastOption.getOrElse(
      fail("NODAL-HIERARCHY-058-INTERFACE", "child Interface requires an exact Instance selector")
    )
    val instance = instanceRecord(selected)
    if selected.generated.isEmpty then requireInstanceScope(instance)
    val port = Option(declarationIds.get(value)).getOrElse(
      fail("NODAL-HIERARCHY-058-INTERFACE", "selector requires an actual declared child Interface")
    )
    val declaration = records(port.module).declarations(port.index)
    val view = value match
      case endpoint: InterfacePort[?, ?] => endpoint.origin.nonEmpty
      case array: InterfaceArray[?, ?] => array.origin.nonEmpty
    if port.module != instance.child || declaration.kind != kind || view || value.boundary ||
      records(port.module).generatedRegions.exists(_.declarations.contains(port))
    then
      fail(
        "NODAL-HIERARCHY-058-INTERFACE",
        "selector requires its immediate child's direct Interface"
      )
    val leaves =
      ConstructionInterfaceLayout.leaves(value.definition, value.role, charge = closureCharge)
    val ordinaryConservativeArray =
      DigitalInterfaceContract.conservativeArrayProfile(value, leaves, closureCharge) &&
        selected.generated.isEmpty && instance.generatedScope.isEmpty &&
        activeGeneratedRegion.isEmpty && activeValueRegion.isEmpty && activeArea.isEmpty &&
        valueRegion(port).isEmpty &&
        !areas.exists(area => area.declarations.contains(port) || area.instances.exists(_ eq instance)) &&
        (value match
          case array: InterfaceArray[?, ?] => array.exported
          case _ => false)
    val generatedConservative =
      selected.generated.isEmpty && kind == KernelSignalKind.InterfacePort &&
        activeGeneratedRegion.exists(generated =>
          generated.areaFamily &&
            (nestedConservativeFamily(generated) ||
              (generated.parentOrdinal.isEmpty && activeValueRegion.forall(_.parent.isEmpty))) &&
            generated.digitalRegion == activeValueRegion && instance.generatedScope.contains(
              generated
            )
        ) &&
        leaves.nonEmpty && leaves.forall(leaf =>
          leaf.protocol == "conservative" && leaf.mode.isEmpty &&
            leaf.analogType.nonEmpty && leaf.dataType.isEmpty &&
            (activeGeneratedRegion.forall(_.parentOrdinal.isEmpty) || leaf.access == "connect")
        )
    val binding =
      InterfaceBoundaryRecord(Some(instance), port, selected.generated, activeValueRegion)
    val selectedConservative = selected.generated.nonEmpty && leaves.exists(_.analogType.nonEmpty)
    val symbolicExtent =
      if selectedConservative then requireSelectedConservativeChild(binding) else None
    if leaves.exists(leaf =>
        (((selected.generated.nonEmpty && !selectedConservative) || instance.generatedScope.nonEmpty && !generatedConservative && !selectedConservative) &&
          (leaf.analogType.nonEmpty || leaf.mode.nonEmpty)) ||
          (leaf.analogType.nonEmpty && (leaf.protocol != "conservative" ||
            kind == KernelSignalKind.InterfaceArray && !ordinaryConservativeArray)) || leaf.dataType.exists(
            isBoundaryCapability
          )
      )
    then
      fail(
        "NODAL-HIERARCHY-058-INTERFACE",
        "child Interface requires digital/resolved leaves or scalar conservative components"
      )
    val projection = projectInterfaceEndpoint(
      value,
      kind,
      binding
    )
    // Only a successfully admitted boundary adapts the symbolic family into conservative shape.
    symbolicExtent.foreach(markStructuralParameterEffect(_, "shape"))
    projection

  private def projectInterfaceEndpoint(
      value: InterfaceEndpoint,
      kind: KernelSignalKind,
      binding: InterfaceBoundaryRecord
  ): InterfaceEndpoint =
    val port = binding.port
    instanceInterfacePorts.iterator.collectFirst:
      case (reference, existing) if existing == binding =>
        records(
          reference.module
        ).declarations(reference.index).value.asInstanceOf[InterfaceEndpoint]
    .getOrElse:
      val base =
        binding.instance.map(instance => s"instance_${instance.ordinal}_interface_${port.index}")
          .getOrElse(s"generated_interface_${port.index}")
      val name = if binding.generated.isEmpty then base
      else s"${base}_selected_${currentModule.declarations.size}"
      val proxy = value match
        case array: InterfaceArray[?, ?] =>
          new InterfaceArray(
            array.definition,
            array.role,
            array.formalCount,
            name,
            array.domainOption,
            exported = false,
            boundary = true
          )
        case endpoint: InterfacePort[?, ?] =>
          new InterfacePort(
            endpoint.definition,
            endpoint.role,
            name,
            endpoint.domain,
            exported = false,
            boundary = true
          )
      registerDeclaration(
        proxy,
        kind,
        None,
        Some(name),
        None,
        Vector("interface" -> value.definition.name, "role" -> value.role.name),
        interfaceBoundary = Some(binding)
      )
      val reference = declarationIds.get(proxy)
      val owner = records(reference.module)
      owner.declarations.update(
        reference.index,
        owner.declarations(reference.index).copy(domainCandidate = None)
      )
      instanceInterfacePorts(reference) = binding
      proxy

  private def interfaceDimensions(declaration: DeclarationRecord): Vector[Dimension] =
    declaration.value match
      case array: InterfaceArray[?, ?] =>
        if array.boundary then
          instanceInterfaceDimensions.getOrElse(
            declaration.reference,
            fail(
              "NODAL-HIERARCHY-058-INTERFACE",
              "projected InterfaceArray dimensions remain unresolved"
            )
          )
        else Vector(array.formalCount)
      case _ => Vector.empty

  private def instanceInterfaceDomain(reference: DeclarationRef): Option[DomainRef] =
    instanceInterfacePorts.get(reference).flatMap: binding =>
      val port = binding.port
      val original = records(port.module).declarations(port.index)
      val domain = declarationCandidate(original).map(domainRef)
      if externalInterfaceOrigin(reference).nonEmpty then None
      else if binding.instance.isEmpty then domain
      else
        val required = domain.getOrElse(
          fail(
            "NODAL-HIERARCHY-058-INTERFACE",
            "child Interface has no declared domain requirement"
          )
        )
        Some(selectedRequirementBindings.get(required).map(_._1).getOrElse(
          fail(
            "NODAL-HIERARCHY-058-INTERFACE",
            "child Interface needs an actual instance domain binding"
          )
        ))

  private def interfaceBoundarySnapshot(owner: Long, binding: InterfaceBoundaryRecord)
      : KernelInterfaceBoundarySnapshot =
    KernelInterfaceBoundarySnapshot(
      binding.instance.map(instance => instancePath(owner, instance.ordinal)),
      declarationPath(binding.port),
      binding.generated.map(generatedSymbolSnapshot(_, binding.scope)),
      binding.scope.map(region => expressionPath(region.reference))
    )

  private def instanceDigitalPortBody[A <: Data](value: Signal[A]): Signal[A] =
    val selected = instanceSelectionStack.lastOption.getOrElse(
      fail("NODAL-HIERARCHY-057-001", "digital child reference requires an exact Instance selector")
    )
    val instance = instanceRecord(selected)
    if selected.generated.isEmpty then requireInstanceScope(instance)
    val port = Option(declarationIds.get(value)).getOrElse(
      fail("NODAL-HIERARCHY-057-001", "instance selector requires an actual child port")
    )
    val declaration = records(port.module).declarations(port.index)
    if port.module != instance.child ||
      !Set(KernelSignalKind.Input, KernelSignalKind.Output)(declaration.kind) ||
      !isClockedDigitalType(value.dataType) || isBoundaryCapability(value.dataType) ||
      (selected.generated.isEmpty && (activeValueRegion.nonEmpty || activeGeneratedRegion.nonEmpty) &&
        !activeGeneratedRegion.exists(_.areaFamily))
    then
      fail(
        "NODAL-HIERARCHY-057-001",
        "instance selector requires its immediate child's direct digital data port"
      )
    if activeGeneratedRegion.nonEmpty then
      requireAreaFamilyScope("Instance digital port selection", requireBody = true)
    instanceDigitalPorts.iterator.collectFirst:
      case (reference, binding)
          if (binding.instance eq instance) && binding.port == port &&
            binding.generated == selected.generated && binding.scope == activeValueRegion =>
        records(reference.module).declarations(reference.index).value.asInstanceOf[Signal[A]]
    .getOrElse:
      val kind = if declaration.kind == KernelSignalKind.Input then KernelSignalKind.InstanceInput
      else KernelSignalKind.InstanceOutput
      val proxy = new Signal(
        preparedDirectPortType(value.dataType),
        kind,
        Some(s"instance_${instance.ordinal}_port_${port.index}" +
          selected.generated.map(_ => s"_selected_${currentModule.declarations.size}").getOrElse(
            ""
          ))
      )
      val reference = completeDirectPortRegistration(proxy)
      val owner = records(reference.module)
      owner.declarations.update(
        reference.index,
        owner.declarations(reference.index).copy(domainCandidate = None)
      )
      instanceDigitalPorts(reference) =
        InstancePortBoundaryRecord(instance, port, selected.generated, activeValueRegion)
      proxy

  def instanceDigitalInout[A <: Bits, M <: DriveMode](value: DigitalInout[A, M])
      : DigitalInout[A, M] =
    val selected = instanceSelectionStack.lastOption.getOrElse(
      fail("NODAL-HIERARCHY-058-INOUT", "child resolved net requires an exact Instance selector")
    )
    val instance = instanceRecord(selected)
    requireInstanceScope(instance)
    val port = Option(declarationIds.get(value)).getOrElse(
      fail("NODAL-HIERARCHY-058-INOUT", "selector requires an actual declared child resolved net")
    )
    val declaration = records(port.module).declarations(port.index)
    if port.module != instance.child || declaration.kind != KernelSignalKind.DigitalInout ||
      value.boundary || activeValueRegion.nonEmpty || activeGeneratedRegion.nonEmpty
    then
      fail(
        "NODAL-HIERARCHY-058-INOUT",
        "selector requires its immediate child's direct resolved net"
      )
    instanceDigitalInouts.iterator.collectFirst:
      case (reference, (existing, target)) if (existing eq instance) && target == port =>
        records(reference.module).declarations(reference.index).value.asInstanceOf[DigitalInout[
          A,
          M
        ]]
    .getOrElse:
      val name = s"instance_${instance.ordinal}_net_${port.index}"
      val proxy = new DigitalInout(
        new KernelInstanceDataType[A](CandidateRuntime.typeDescriptor(value.dataType)),
        value.mode,
        value.placement,
        value.profile,
        name,
        boundary = true
      )
      registerDeclaration(
        proxy,
        KernelSignalKind.DigitalInout,
        Some(proxy.dataType),
        Some(name),
        None,
        Vector(
          "mode" -> value.mode.name,
          "placement" -> value.placement,
          "profile" -> value.profile
        )
      )
      val reference = declarationIds.get(proxy)
      val owner = records(reference.module)
      owner.declarations.update(
        reference.index,
        owner.declarations(reference.index).copy(domainCandidate = None)
      )
      instanceDigitalInouts(reference) = instance -> port
      proxy

  def withGeneratedInstanceSelection[A](view: GeneratedInstanceRef[?])(body: => A): A =
    validateGeneratedInstanceSelection(view)
    requireAreaFamilyScope("generated Instance selection", requireBody = false)
    val value = view.selection.reference
    val root =
      value.family.selectionPrefix.headOption.map(_.family).getOrElse(value.family.generatedRegion)
    val _ = bindGeneratedFamily(root, activeValueRegion)
    (value.family.selectionPrefix.map(_.ordinal) :+ value.ordinal).foreach:
      case ordinal: Expr[Integer] =>
        val _ = bindCollectionValue(ordinal, activeValueRegion, currentModule.handle)
      case _: Int => ()
    instanceSelectionStack += InstanceSelectionRecord(view.instance, Some(view.selection))
    try
      val result = body
      val reference = result match
        case value: InterfaceValueRef[?] =>
          Option(declarationIds.get(checkedInterfaceValueReference(value).endpoint))
        case value: AnyRef => Option(declarationIds.get(value))
        case _ => None
      if !reference.exists(reference =>
          instanceDigitalPorts.get(reference).exists(binding =>
            binding.generated.contains(view.selection) && binding.scope == activeValueRegion
          ) ||
            instanceInterfacePorts.get(reference).exists(binding =>
              binding.generated.contains(view.selection) && binding.scope == activeValueRegion
            )
        )
      then
        fail("NODAL-AREA-058-CAPABILITY", "selected Instance requires its exact typed direct port")
      result
    finally instanceSelectionStack.remove(instanceSelectionStack.size - 1)

  def withInstanceSelection[A](instance: Instance[?])(body: => A): A =
    requireInstanceScope(instanceRecord(instance))
    instanceSelectionStack += InstanceSelectionRecord(instance)
    try body
    finally instanceSelectionStack.remove(instanceSelectionStack.size - 1)

  /** Derived query-local contexts over the immutable pre-finalization snapshot. These do not
    * allocate occurrences or change the original template's formal bindings.
    */
  private final class InstanceTypeContext(
      snapshot: ConstructionSnapshot,
      queries: StructuralParameterBinding.InstanceQueries
  ):
    closureCharge(snapshot.generatedRegions.size.toLong + snapshot.structuralIfs.size.toLong * 3L +
      snapshot.staticPredicates.size.toLong)
    private val root = new StructuralParameterBinding.Query(snapshot, meter = closureMeter)
    private val families = snapshot.generatedRegions.map(value => value.path -> value).toMap
    private val arms = snapshot.structuralIfs.flatMap(value =>
      Vector(value.trueArm, value.falseArm).map(arm => arm.path -> (value, arm))
    ).toMap
    private val predicates = snapshot.staticPredicates.map(value => value.path -> value).toMap
    private val cache = mutable.HashMap.empty[String, StructuralParameterBinding.Query]
    private val active = mutable.HashSet.empty[String]
    private def lexical(scope: Option[String]): StructuralParameterBinding.Query =
      closureCharge(1L)
      scope.map: path =>
        cache.getOrElseUpdate(
          path, {
            if active.size >= 128 || !active.add(path) then
              fail("NODAL-HIERARCHY-058-TYPE", "instance type context is cyclic or exceeds depth")
            try
              families.get(path) match
                case Some(family) => lexical(family.lexicalParent).generatedBody(family)
                case None =>
                  val (conditional, arm) = arms.getOrElse(
                    path,
                    fail(
                      "NODAL-HIERARCHY-058-TYPE",
                      "instance type context requires an actual structural owner"
                    )
                  )
                  lexical(conditional.parentRegion).conditionalArm(conditional.parentRegion, arm)
            finally active.remove(path)
          }
        )
      .getOrElse(root)

    def query(owner: Long, binding: InstancePortBoundaryRecord): StructuralParameterBinding.Query =
      query(owner, binding.instance, binding.generated, binding.scope)

    def query(
        owner: Long,
        instance: InstanceRecord,
        generated: Option[GeneratedSymbolSelectionRecord],
        scope: Option[DigitalValueRegionRecord]
    )
        : StructuralParameterBinding.Query =
      val ownerPath = modulePath(owner)
      val occurrence = instancePath(owner, instance.ordinal)
      instance.generatedScope match
        case None => queries.query(ownerPath, occurrence)
        case Some(familyScope) =>
          val body = lexical(Some(generatedRegionPath(familyScope)))
          generated match
            case None => queries.generated(ownerPath, occurrence, body)
            case Some(selection) =>
              queries.selected(ownerPath, occurrence, selected(owner, selection, scope), body)

    def lexicalQuery(path: String): StructuralParameterBinding.Query = lexical(Some(path))

    def definitionQuery(
        owner: Long,
        definition: InterfaceType[?]
    ): StructuralParameterBinding.Query =
      lexical(requireInterfaceDefinitionContext(definition, owner).map(value =>
        expressionPath(value.region.reference)
      ))

    def selected(
        owner: Long,
        selection: GeneratedSymbolSelectionRecord,
        scope: Option[DigitalValueRegionRecord]
    ): StructuralParameterBinding.SelectedGeneratedContext =
      selected(owner, generatedSymbolSnapshot(selection, scope), scope)

    def selected(
        owner: Long,
        member: KernelGeneratedMemberSnapshot,
        scope: Option[DigitalValueRegionRecord]
    ): StructuralParameterBinding.SelectedGeneratedContext =
      val ownerPath = modulePath(owner)
      val use = scope.map(value => expressionPath(value.reference))
      val context = lexical(use).selectedGenerated(ownerPath)
      val query = context.query
      val proof = new StructuralPredicateOrdering.Query[String, Int](
        path =>
          val predicate = predicates.getOrElse(
            path,
            fail("NODAL-HIERARCHY-058-TYPE", "selected type predicate is absent")
          )
          if Set("eq", "ne", "lt", "le", "gt", "ge")(predicate.operation) then
            val original = lexical(predicate.region)
            val values = context.importValues(
              original,
              predicate.operands.map(original.resolve(ownerPath, _))
            )
            StructuralPredicateOrdering.Compare(predicate.operation, values(0), values(1))
          else StructuralPredicateOrdering.Logic(predicate.operation, predicate.operands)
        ,
        query.bounds,
        _ == _
      )
      def prove(left: Int, right: Int, inclusive: Boolean): Boolean =
        if proof.less(left, right, inclusive) then true
        else
          var current = use
          val visited = mutable.HashSet.empty[String]
          var result = false
          while current.nonEmpty && !result do
            closureCharge(1L)
            val path = current.get
            if visited.size >= 128 || !visited.add(path) then
              fail(
                "NODAL-HIERARCHY-058-TYPE",
                "selected type guard ancestry is cyclic or excessive"
              )
            arms.get(path).foreach: (conditional, arm) =>
              result = proof.under(
                conditional.predicate,
                arm.path == conditional.falseArm.path,
                left,
                right,
                inclusive
              )
            current = families.get(path).flatMap(_.lexicalParent)
              .orElse(arms.get(path).flatMap(_._1.parentRegion))
          result
      (member.selectionPrefix.map(value => value.family -> value.ordinal) :+
        (member.family -> member.ordinal)).foreach: (path, ordinal) =>
        val family = families.getOrElse(
          path,
          fail("NODAL-HIERARCHY-058-TYPE", "selected type family is absent")
        )
        context.admit(family, ordinal, lexical(family.lexicalParent), prove)
      context

  // Both digital ports and complete Interface payloads use this one contextual transform.
  // The batch shares a reachable-DAG export and one bounded recursive type budget.
  private def materializeInstanceTypeDescriptors(
      parent: Long,
      instance: InstanceRecord,
      port: DeclarationRef,
      formals: Vector[KernelTypeDescriptor],
      queries: StructuralParameterBinding.InstanceQueries,
      checkedQuery: Option[StructuralParameterBinding.Query] = None
  ): Vector[KernelTypeDescriptor] =
    val owner = modulePath(parent)
    val occurrencePath = instancePath(parent, instance.ordinal)
    val (_, occurrence, _) = queries.declaration(owner, occurrencePath, declarationPath(port))
    val query = checkedQuery.getOrElse(queries.query(owner, occurrencePath))
    materializeTypeDescriptors(
      parent,
      formals,
      (_, value) =>
        val id = query.resolve(occurrence.childModule, renderShapeDimension(value, port.module))
        query.positive(id)
        id
      ,
      values => query.exportDimensions(owner, values)
    )

  private def materializeTypeDescriptors(
      parent: Long,
      formals: Vector[KernelTypeDescriptor],
      resolveDimension: (KernelTypeDescriptor, Any) => Int,
      exportDimensions: Vector[Int] => StructuralParameterBinding.ResolvedDimensions
  ): Vector[KernelTypeDescriptor] =
    val dimensions = mutable.ArrayBuffer.empty[Int]
    var examined = 0
    def transform(
        descriptor: KernelTypeDescriptor,
        dimension: Any => Dimension,
        depth: Int
    ): KernelTypeDescriptor =
      closureCharge(1L)
      examined += 1
      if depth > 128 || examined > 65536 then
        fail("NODAL-HIERARCHY-057-001", "instance digital type exceeds structural budget")
      descriptor.kind match
        case "Bits" | "UInt" | "SInt" =>
          if descriptor.arguments.size != 1 then
            fail("NODAL-HIERARCHY-057-001", "instance scalar width is malformed")
          descriptor.copy(arguments = Vector(dimension(descriptor.arguments.head)))
        case "Vec" => descriptor.arguments match
            case Vector(element: DataType[?], counts: Seq[?]) =>
              descriptor.copy(arguments =
                Vector(
                  CandidateRuntime.dataTypeFromDescriptor(
                    transform(CandidateRuntime.typeDescriptor(element), dimension, depth + 1)
                  ),
                  closureElements(counts).map(dimension).toVector
                )
              )
            case _ => fail("NODAL-HIERARCHY-057-001", "instance vector type is malformed")
        case "Struct" => descriptor.arguments match
            case Vector(name: String, fields: Seq[?]) =>
              descriptor.copy(arguments =
                Vector(
                  name,
                  closureElements(fields).map:
                    case field: StructField[?] => StructField(
                        field.name,
                        CandidateRuntime.dataTypeFromDescriptor(
                          transform(
                            CandidateRuntime.typeDescriptor(field.dataType),
                            dimension,
                            depth + 1
                          )
                        )
                      )
                    case _ =>
                      fail("NODAL-HIERARCHY-057-001", "instance struct field is malformed")
                  .toVector
                )
              )
            case _ => fail("NODAL-HIERARCHY-057-001", "instance struct type is malformed")
        case _ => descriptor
    closureElements(formals).foreach: formal =>
      val _ = transform(
        formal,
        value =>
          closureCharge(1L)
          dimensions += resolveDimension(formal, value)
          1
        ,
        0
      )
    val previousModules = moduleStack.toVector
    moduleStack.clear()
    moduleStack += records(parent)
    try
      val actual = materializeStructuralDimensions(
        exportDimensions(closureElements(dimensions).toVector)
      ).iterator
      examined = 0
      closureElements(formals).map(formal =>
        transform(
          formal,
          _ => {
            closureCharge(1L)
            actual.next()
          },
          0
        )
      ).toVector
    finally
      moduleStack.clear()
      moduleStack ++= previousModules

  private def deferredValueScopeChain(
      scope: Option[DigitalValueRegionRecord],
      owner: Long,
      ordinaryPayload: Boolean
  ): Vector[DigitalValueRegionRecord] =
    val chain = mutable.ArrayBuffer.empty[DigitalValueRegionRecord]
    var current = scope
    while current.nonEmpty do
      val region = current.get
      val ordinary = ordinaryPayload && DigitalCollectionContract.OrdinaryKinds(region.kind) &&
        collectionRegions.get(region.reference).contains(region) &&
        region.regionKind == DigitalRegionKind.Collection &&
        region.inputs.size == (if region.kind == "zip_with" then 2 else 1) &&
        region.seed.nonEmpty == Set("fold_left", "scan_left")(region.kind)
      val reduction = ordinaryPayload && DigitalReductionContract.Kinds(region.kind) &&
        collectionRegions.get(region.reference).contains(region) && {
          val _ = reductionSignature(region, complete = false)
          true
        }
      if chain.size >= 128 || chain.contains(region) || region.reference.module != owner ||
        !(ordinary || reduction || region.behavioralArm.nonEmpty || region.structuralArm.nonEmpty ||
          region.loop.exists(_.generated.nonEmpty))
      then
        val message = if ordinaryPayload then
          "deferred payload requires an actual registered ordinary collection or structural region"
        else "deferred value requires actual structural or behavioral lexical ancestry"
        fail("NODAL-HIERARCHY-058-TYPE", message)
      chain += region
      current = region.parent
    chain.reverse.toVector

  private def requireDeferredValueScope(scope: Option[DigitalValueRegionRecord], owner: Long)
      : Vector[DigitalValueRegionRecord] =
    deferredValueScopeChain(scope, owner, ordinaryPayload = false)

  /** Only actual registered ordinary collections extend the deferred Data lifecycle. Formal and
    * seed obligations retain their exact operand roles; callback effects remain unchanged.
    */
  private def requireDeferredPayloadScope(scope: Option[DigitalValueRegionRecord], owner: Long)
      : Vector[DigitalValueRegionRecord] =
    deferredValueScopeChain(scope, owner, ordinaryPayload = true)

  private def installValueScopeChain(owner: Long, chain: Vector[DigitalValueRegionRecord]): Unit =
    moduleStack.clear(); moduleStack += records(owner)
    collectionStack.clear(); collectionStack ++= chain
    generationStack.clear(); generationStack ++= chain.flatMap(_.loop.flatMap(_.generated))

  private def installValueScope(owner: Long, scope: Option[DigitalValueRegionRecord]): Unit =
    installValueScopeChain(owner, requireDeferredValueScope(scope, owner))

  private def requirePhysicalControlCapture(
      dataType: DataType[?],
      region: DigitalValueRegionRecord
  ): Unit =
    val descriptor = CandidateRuntime.typeDescriptor(dataType)
    if !(descriptor == KernelTypeDescriptor("Clock") || descriptor == KernelTypeDescriptor(
        "Reset"
      )) ||
      !requireDeferredValueScope(Some(region), region.reference.module).forall(scope =>
        scope.structuralArm.nonEmpty || scope.loop.exists(_.generated.exists(_.areaFamily))
      )
    then collectionFailure("physical controls require exact scalar structural capture ancestry")

  private def actualDomainControls(
      actual: DomainRecord,
      physical: DomainRecord
  ): (Option[Expr[Clock]], Option[Expr[Reset]]) =
    boundDomainCharge()
    val reset = Option(effectiveResets.get(physical.domain)).map: value =>
      boundDomainCharge()
      if actual.kind == KernelDomainKind.Required then actual.domain.reset else value
    actual.domain.clock -> reset

  /** Final bindings, not eager binding calls, own the physical edges through isolated arms. */
  private def materializeInstanceControlCaptures(resolved: Map[DomainRef, String]): Unit =
    val byPath = records.valuesIterator.flatMap(module =>
      boundDomainCharge(1L + module.domains.size.toLong)
      module.domains
    ).map(domain =>
      boundDomainCharge()
      domainPath(domain.reference) -> domain
    ).toMap
    // Prepay copies plus restoration: exact profile cannot mutate these stacks.
    boundDomainCharge(
      3L * (moduleStack.size.toLong + collectionStack.size.toLong + generationStack.size.toLong)
    )
    val previousModules = moduleStack.toVector
    val previousRegions = collectionStack.toVector
    val previousGeneration = generationStack.toVector
    try
      records.valuesIterator.foreach: module =>
        boundDomainCharge()
        module.instances.foreach: instance =>
          boundDomainCharge()
          instance.generatedScope.foreach: family =>
            val scope = family.digitalRegion.getOrElse(
              collectionFailure("generated instance has no actual digital family body")
            )
            installValueScope(module.handle, Some(scope))
            records(instance.child).domains.filter(_.kind == KernelDomainKind.Required).foreach:
              requirement =>
                val (actualRef, _, owner, ordinal) =
                  selectedRequirementBindings(requirement.reference)
                if owner != module.handle || ordinal != instance.ordinal then
                  collectionFailure("physical binding lost its actual instance owner")
                val actual = records(actualRef.module).domains(actualRef.index)
                val (clock, reset) = actualDomainControls(actual, byPath(resolved(actualRef)))
                (clock.toVector ++ reset.toVector).foreach: value =>
                  val _ = bindCollectionValue(
                    value,
                    Some(scope),
                    module.handle,
                    physicalControl = true
                  )
    finally
      moduleStack.clear(); moduleStack ++= previousModules
      collectionStack.clear(); collectionStack ++= previousRegions
      generationStack.clear(); generationStack ++= previousGeneration

  /** Seal the existing captured DAG once, including exact capture-edge type-copy intents. */
  private def materializeDeferredResultTypes(): Unit =
    val incrementalOwner = boundaryClosureOwner.filter(_.incremental)
    val state = incrementalOwner.map(_.typeState).getOrElse(
      new java.util.IdentityHashMap[KernelExpr[?], java.lang.Integer]()
    )
    var work = incrementalOwner.map(_.typeWork).getOrElse(0)
    val previousModules = moduleStack.toVector
    val previousRegions = collectionStack.toVector
    val previousGeneration = generationStack.toVector
    def visit(expression: KernelExpr[?], depth: Int): Unit =
      closureCharge(1L)
      if depth > 128 then fail("NODAL-HIERARCHY-058-TYPE", "deferred result type graph is too deep")
      if !constructionPayloadAvailable(expression) then return
      val reference = Option.when(expression.typeIntent.nonEmpty &&
        !Option(state.get(expression)).contains(2))(Option(expressionIds.get(expression))).flatten
      val chain = reference.toVector.flatMap(ref =>
        deferredValueScopeChain(expressionRegions.get(ref), ref.module, ordinaryPayload = true)
      )
      chain.find(region => DigitalReductionContract.Kinds(region.kind)) match
        case Some(region)
            if expression.typeIntent.nonEmpty && !Option(state.get(expression)).contains(2) =>
          // Validate the original result edges before descendant inference appends checked
          // Integer type captures. The registered result owns the entire once-only transfer.
          region.result.collect {
            case result: KernelExpr[?]
                if result.typeIntent.nonEmpty &&
                  !Option(state.get(result)).exists(value => value == 1 || value == 2) => result
          }
            .foreach(visit(_, depth + 1))
          region.inputs.foreach:
            case input: KernelExpr[?] => visit(input, depth + 1)
            case _ => ()
          DeferredResultType.requireFinal(region.inputs, "reduction deferred body input")
          val (left, right) = reductionSignature(region, complete = true)
          val widths = DigitalReductionContract.widths(
            CandidateRuntime.typeDescriptor(DigitalCollectionContract.vector(region.inputs.head)._1)
          )
          withReductionContext((left.zip(widths) ++ right.zip(widths)).toMap, Map.empty):
            visitActual(expression, depth)
        case _ => visitActual(expression, depth)
    def visitActual(expression: KernelExpr[?], depth: Int): Unit =
      if depth > 128 || Option(state.get(expression)).contains(1) then
        fail("NODAL-HIERARCHY-058-TYPE", "deferred result type graph is cyclic or too deep")
      if !Option(state.get(expression)).contains(2) && expression.typeIntent.nonEmpty then
        work += 1
        if work > 65536 then
          fail("NODAL-HIERARCHY-058-TYPE", "deferred result type graph exceeds its finite budget")
        val reference = Option(expressionIds.get(expression)).getOrElse(
          fail("NODAL-HIERARCHY-058-TYPE", "deferred expression has no captured owner")
        )
        val scope = expressionRegions.get(reference)
        val scopeChain = requireDeferredPayloadScope(scope, reference.module)
        state.put(expression, 1)
        val intent = expression.typeIntent.get
        closureCharge(expression.operands.size.toLong + intent.dependencies.size.toLong)
        work += expression.operands.size + intent.dependencies.size
        if work > 65536 then
          fail("NODAL-HIERARCHY-058-TYPE", "deferred result type edges exceed their finite budget")
        val argumentSource = collectionArguments.get(reference).map: (region, position) =>
          val source = intent.dependencies match
            case Vector(value: Expr[?]) => value
            case _ =>
              fail("NODAL-HIERARCHY-058-TYPE", "deferred argument requires one exact source value")
          if !scope.contains(region) || !region.arguments.lift(position)
              .exists(_._1.eq(expression)) ||
            !expression.operation.contains(DigitalCollectionContract.Argument) ||
            expression.operands.nonEmpty
          then fail("NODAL-HIERARCHY-058-TYPE", "deferred argument lost its registered identity")
          val role = region.arguments(position)._2
          if role == "capture" then
            val edge = region.captures.get(valueReference(source))
            if !edge.exists((value, argument) => value.eq(source) && argument.eq(expression)) then
              fail(
                "NODAL-HIERARCHY-058-TYPE",
                "deferred capture differs from its exact parent edge"
              )
          else if !collectionFormalSource(region, position, role).eq(source) then
            fail(
              "NODAL-HIERARCHY-058-TYPE",
              "deferred formal differs from its actual input or seed"
            )
          requireRegisteredCollectionOperand(source, reference.module)
          if valueRegion(valueReference(source)) != region.parent then
            fail("NODAL-HIERARCHY-058-TYPE", "deferred argument source has the wrong lexical owner")
          source
        val conditional = structuralIfs.get(reference).filter(_.result.exists(_ eq expression))
        val yields = conditional.toVector.flatMap: record =>
          val arms = Vector(record.trueArm, record.falseArm)
          if !expression.operation.contains(
              StructuralIfRuntime.Operation
            ) || record.parent != scope ||
            intent.dependencies.size != 2 || arms.zipWithIndex.exists: (arm, ordinal) =>
              !structuralArms.get(arm.reference).contains(arm) || arm.parent != scope ||
                arm.reference.module != reference.module ||
                !arm.structuralArm.exists(header =>
                  header.conditional == reference && header.whenTrue == (ordinal == 0)
                ) ||
                !arm.yielded.exists(value =>
                  value.eq(intent.dependencies(ordinal).asInstanceOf[AnyRef]) &&
                    valueRegion(valueReference(value)).contains(arm)
                )
          then
            fail(
              "NODAL-HIERARCHY-058-TYPE",
              "deferred conditional lost its exact authored yield edges"
            )
          arms.flatMap(_.yielded)
        val collection = collectionRegions.get(reference).filter(_.result.exists(_ eq expression))
        val collectionYields = collection.toVector.flatMap: region =>
          val expectedOperands =
            region.inputs ++ region.seed ++ region.captures.valuesIterator.map(_._1).toVector
          val expectedDependencies = region.inputs ++ region.seed ++
            (if DigitalReductionContract.Kinds(region.kind) then
               region.captures.valuesIterator.map(_._1).toVector
             else Vector.empty) ++ region.yielded
          if !(DigitalCollectionContract.OrdinaryKinds(
              region.kind
            ) || DigitalReductionContract.Kinds(region.kind)) ||
            !expression.operation.contains(
              DigitalCollectionContract.Operation
            ) || region.parent != scope ||
            region.reference != reference || !region.yielded.exists(value =>
              valueRegion(
                valueReference(value)
              ).contains(region)
            ) || expectedDependencies.size != intent.dependencies.size ||
            expectedDependencies.zip(intent.dependencies).exists((left, right) =>
              !left.eq(right.asInstanceOf[AnyRef])
            ) ||
            expectedOperands.size != expression.operands.size ||
            expectedOperands.zip(expression.operands).exists((left, right) =>
              !left.asInstanceOf[AnyRef].eq(right.asInstanceOf[AnyRef])
            )
          then
            fail(
              "NODAL-HIERARCHY-058-TYPE",
              "deferred collection lost its exact registered inputs, captures or yield"
            )
          region.yielded
        val operands = new java.util.IdentityHashMap[AnyRef, java.lang.Boolean]()
        (expression.operands ++ argumentSource ++ yields ++ collectionYields).foreach(value =>
          operands.put(value.asInstanceOf[AnyRef], true)
        )
        if intent.dependencies.isEmpty || intent.dependencies.exists(dependency =>
            !operands.containsKey(dependency.asInstanceOf[AnyRef])
          )
        then fail("NODAL-HIERARCHY-058-TYPE", "type intent lost its exact operand dependency")
        val selectedStorage = generatedMemberRecord(expression).map: _ =>
          val member = inValueScope(reference.module, scope):
            requireGeneratedMemberTypeRecord(expression, reference, scope)
          if !member.dataType.finalized then
            fail("NODAL-AREA-058-TYPE", "selected storage descriptor remains unresolved")
          member
        intent.dependencies.foreach: dependency =>
          if !selectedStorage.exists(_ eq dependency.asInstanceOf[AnyRef]) &&
            !digitalOwnerOf(dependency).contains(modulePath(reference.module))
          then
            fail("NODAL-HIERARCHY-058-TYPE", "type intent operand belongs to another module")
          dependency match
            case value: KernelExpr[?] => visit(value, depth + 1)
            case _ => ()
          if argumentSource.isEmpty && conditional.isEmpty && collection.isEmpty then
            dependency match
              case value: Expr[?] =>
                val local = bindCollectionValue(value, scope, reference.module, create = false)
                local match
                  case argument: KernelExpr[?] if !(argument eq value) => visit(argument, depth + 1)
                  case _ => ()
              case _ => ()
        collection.foreach: region =>
          region.arguments.foreach((argument, _) => visit(dataArgument(argument), depth + 1))
          if DigitalReductionContract.Kinds(region.kind) then
            incrementalOwner match
              case Some(owner) => owner.descendants(region).foreach(visit(_, depth + 1))
              case None =>
                expressionValues.toVector.foreach: (bodyRef, bodyExpression) =>
                  closureCharge(1L)
                  var current = expressionRegions.get(bodyRef)
                  var budget = 0
                  while current.nonEmpty && !current.contains(region) do
                    closureCharge(1L)
                    budget += 1
                    if budget > 128 then collectionFailure("reduction body ancestry exceeds budget")
                    current = current.get.parent
                  if current.contains(region) then visit(bodyExpression, depth + 1)
        if incrementalOwner.exists(_.hasConstructionWork) then
          closureCharge(intent.dependencies.size.toLong)
        if incrementalOwner.exists(
            _.hasConstructionWork
          ) && intent.dependencies.exists(DeferredResultType.pending)
        then
          state.remove(expression)
          return
        DeferredResultType.requireFinal(intent.dependencies, "result type materialization")
        installValueScopeChain(reference.module, scopeChain)
        val actual = intent.resolve()
        if expression.operation.contains("shape_index") && !shapeIndices.contains(reference) then
          collectionFailure("deferred shape index requires its actual captured projection")
        shapeIndices.get(reference).foreach: index =>
          val input = expression.operands.headOption.collect { case value: Expr[?] => value }
            .getOrElse(collectionFailure("deferred shape index lost its actual input"))
          if !expression.operation.contains("shape_index") || valueReference(
              input
            ) != index.input ||
            expression.operands.tail != index.indices
          then
            collectionFailure("deferred shape index differs from its original input or indices")
          val (element, dimensions) = DigitalCollectionContract.vector(input)
          val _ = validateShapeIndexBounds(
            index.indices,
            dimensions,
            reference.module,
            expressionPath(reference)
          )
          val remaining = dimensions.drop(index.indices.size)
          val expected = if remaining.isEmpty then CandidateRuntime.typeDescriptor(element)
          else KernelTypeDescriptor("Vec", Vector(element, remaining))
          if remaining != index.resultDimensions || !DigitalEnumContract.sameType(actual, expected)
          then
            collectionFailure(
              "deferred shape index differs from its final exact geometry or element"
            )
        validateShapeType(
          CandidateRuntime.dataTypeFromDescriptor(actual),
          reference.module,
          cardinality = true,
          storageContext = scope.nonEmpty
        )
        bindCollectionType(actual, scope, reference.module)
        expression.sealResultType(actual)
        state.put(expression, 2)
    try
      expressionValues.valuesIterator.toVector.foreach(visit(_, 0))
      operations.iterator.filter(operation =>
        Set("assignment", "value-connect")(operation.kind)
      ).foreach: operation =>
        val hadObligation = operation.values.collect { case value: Expr[?] => value }.exists:
          value =>
            DeferredResultType.hasTypeObligation(value) || operation.digitalRegion.exists: region =>
              DeferredResultType.hasTypeObligation(
                bindCollectionValue(value, Some(region), operation.owner, create = false)
              )
        if hadObligation then
          val defer = incrementalOwner.exists(_.hasConstructionWork) && {
            closureCharge(operation.values.size.toLong)
            operation.values.exists(DeferredResultType.pending)
          }
          if !defer then
            installValueScope(operation.owner, operation.digitalRegion)
            DeferredResultType.requireFinal(operation.values, "assignment type materialization")
            validateAssignmentTypes(operation.values)
    finally
      incrementalOwner.foreach(_.typeWork = work)
      moduleStack.clear(); moduleStack ++= previousModules
      collectionStack.clear(); collectionStack ++= previousRegions
      generationStack.clear(); generationStack ++= previousGeneration
    if incrementalOwner.isEmpty && expressionValues.valuesIterator.exists(!_.typeFinalized) then
      fail("NODAL-HIERARCHY-058-TYPE", "unresolved result type cannot leave construction")

  private def materializeGeneratedMemberTypes(
      queries: StructuralParameterBinding.InstanceQueries,
      snapshot: ConstructionSnapshot
  ): Unit =
    closureCharge(expressionValues.size.toLong)
    val contexts = new InstanceTypeContext(snapshot, queries)
    val previousModules = moduleStack.toVector
    val previousRegions = collectionStack.toVector
    val previousGeneration = generationStack.toVector
    try
      expressionValues.toVector.filter((reference, _) =>
        closureAccepts("member", reference)
      ).foreach: (reference, expression) =>
        generatedMemberRecord(expression).foreach: _ =>
          val scope = expressionRegions.get(reference)
          installValueScope(reference.module, scope)
          val member = requireGeneratedMemberTypeRecord(expression, reference, scope)
          val selected =
            contexts.selected(reference.module, generatedMemberSnapshot(expression).get, scope)
          val original = contexts.lexicalQuery(generatedRegionPath(member.family))
          val owner = modulePath(reference.module)
          val actualType = materializeTypeDescriptors(
            reference.module,
            Vector(member.dataType.formal),
            (_, value) =>
              val id = original.resolve(owner, renderShapeDimension(value, reference.module))
              original.positive(id)
              val actual = selected.importValues(original, Vector(id)).head
              selected.query.positive(actual)
              actual
            ,
            values => selected.query.exportDimensions(owner, values)
          ).head
          if member.dataType.finalized then
            if !DigitalEnumContract.sameType(member.dataType.kernelDescriptor, actualType) then
              fail("NODAL-AREA-058-TYPE", "eager storage type differs from its checked selection")
          else member.dataType.seal(actualType)
          validateShapeType(member.dataType, reference.module, cardinality = true)
    finally
      moduleStack.clear(); moduleStack ++= previousModules
      collectionStack.clear(); collectionStack ++= previousRegions
      generationStack.clear(); generationStack ++= previousGeneration

  private def materializeInstanceDigitalTypes(
      queries: StructuralParameterBinding.InstanceQueries,
      snapshot: ConstructionSnapshot
  ): Unit =
    closureCharge(instanceDigitalPorts.size.toLong)
    val contexts = new InstanceTypeContext(snapshot, queries)
    val previousModules = moduleStack.toVector
    val previousRegions = collectionStack.toVector
    val previousGeneration = generationStack.toVector
    try
      instanceDigitalPorts.iterator.filter((reference, _) =>
        closureAccepts("digital", reference)
      ).foreach: (reference, boundary) =>
        installValueScope(reference.module, boundary.scope)
        val instance = boundary.instance
        val port = boundary.port
        val declaration = records(reference.module).declarations(reference.index)
        val deferred = declaration.dataType.collect { case value: KernelInstanceDataType[?] =>
          value
        }.getOrElse(fail("NODAL-HIERARCHY-057-001", "instance port lost its deferred type view"))
        deferred.seal(materializeInstanceTypeDescriptors(
          reference.module,
          instance,
          port,
          Vector(deferred.formal),
          queries,
          Some(contexts.query(reference.module, boundary))
        ).head)
        validateShapeType(deferred, reference.module, cardinality = false)
    finally
      moduleStack.clear(); moduleStack ++= previousModules
      collectionStack.clear(); collectionStack ++= previousRegions
      generationStack.clear(); generationStack ++= previousGeneration

  private def materializeInstanceDigitalInouts(queries: StructuralParameterBinding.InstanceQueries)
      : Unit =
    closureCharge(instanceDigitalInouts.size.toLong)
    instanceDigitalInouts.iterator.filter((reference, _) =>
      closureAccepts("inout", reference)
    ).foreach: (reference, boundary) =>
      val (instance, port) = boundary
      val declaration = records(reference.module).declarations(reference.index)
      val deferred = declaration.dataType.collect { case value: KernelInstanceDataType[?] => value }
        .getOrElse(fail(
          "NODAL-HIERARCHY-058-INOUT",
          "resolved boundary lost its deferred element type"
        ))
      deferred.seal(materializeInstanceTypeDescriptors(
        reference.module,
        instance,
        port,
        Vector(deferred.formal),
        queries
      ).head)
      validateShapeType(deferred, reference.module, cardinality = false)

  def nodeArrayUse[D <: Discipline](value: NodeArray[D]): NodeArray[D] =
    Option(declarationIds.get(value)) match
      case Some(port) if port.module != currentModule.handle =>
        val instance = instanceSelectionStack.lastOption.getOrElse(
          fail("NODAL-SHAPE-043-007", "child array use requires an exact Instance selector")
        )
        val record = instanceRecord(instance)
        requireInstanceScope(record)
        val declaration = records(port.module).declarations(port.index)
        if record.child != port.module || !conservativePortKinds.contains(declaration.kind) ||
          declaration.conservativeDimensions.isEmpty
        then
          fail("NODAL-SHAPE-043-007", "Instance selector requires its own declared array boundary")
        operations.iterator.filter(operation =>
          operation.owner == currentModule.handle &&
            operation.generatedScope == activeGeneratedRegion
        ).flatMap(_.values.collect {
          case root: InstanceArrayRootRecord
              if root.instanceOrdinal == record.ordinal && root.port == port =>
            root.value.asInstanceOf[NodeArray[D]]
        }).nextOption().getOrElse(new NodeArray(
          value.discipline,
          NodeArrayShapeIntent.InstancePort(instance.instance, value)
        ))
      case _ => value

  def registerInstanceNodeArray(
      value: NodeArray[?],
      instance: Instance[?],
      port: NodeArray[?]
  ): Unit =
    val occurrence = instanceRecord(instance)
    requireInstanceScope(occurrence)
    val declaration = Option(declarationIds.get(port)).getOrElse(
      fail("NODAL-SHAPE-043-007", "instance array root requires an actual declared boundary")
    )
    val record = records(declaration.module).declarations(declaration.index)
    if occurrence.child != declaration.module || !conservativePortKinds.contains(record.kind) ||
      record.conservativeDimensions.isEmpty || value.discipline != Electrical
    then
      fail("NODAL-SHAPE-043-007", "instance array root is not its exact Electrical boundary")
    val reference = captureExpression(value).getOrElse(
      fail("NODAL-SHAPE-043-007", "instance array requires an active module")
    )
    operation(
      "instance-node-array",
      new InstanceArrayRootRecord(value, reference, occurrence.ordinal, declaration)
    )

  private def nodeArrayDimensions(reference: DeclarationRef | ExpressionRef)
      : Option[Vector[Dimension]] =
    reference match
      case declaration: DeclarationRef =>
        Some(records(declaration.module).declarations(declaration.index)
          .conservativeDimensions.map(_.asInstanceOf[Dimension]))
      case expression: ExpressionRef => instanceArrayRoot(expression).flatMap(_._2.dimensions)
          .orElse(shapeViews.get(expression).map(_.dimensions))
          .orElse(shapeIndices.get(expression).filter(_.resultDimensions.nonEmpty)
            .map(_.resultDimensions.map(_.asInstanceOf[Dimension])))

  private def nodeArrayInput(array: NodeArray[?])
      : (DeclarationRef | ExpressionRef, DeclarationRef | ExpressionRef) =
    val module = currentModule.handle
    val input = Option[DeclarationRef | ExpressionRef](declarationIds.get(array))
      .orElse(Option(expressionIds.get(array))).getOrElse(
        fail("NODAL-SHAPE-043-006", "node-array reference has no owned captured identity")
      )
    val seen = mutable.HashSet.empty[ExpressionRef]
    def root(reference: DeclarationRef | ExpressionRef): DeclarationRef | ExpressionRef =
      reference match
        case declaration: DeclarationRef =>
          if declaration.module != module || !conservativeArrayKinds.contains(
              records(declaration.module).declarations(declaration.index).kind
            ) ||
            records(
              declaration.module
            ).declarations(declaration.index).conservativeDimensions.isEmpty
          then
            fail("NODAL-SHAPE-043-006", "node-array reference must retain its local owned root")
          requireGeneratedDeclarationScope(declaration)
          declaration
        case expression: ExpressionRef =>
          if expression.module != module || !seen.add(expression) then
            fail("NODAL-SHAPE-043-006", "node-array reference has a cyclic or foreign projection")
          instanceArrayRoot(expression) match
            case Some((operation, record)) =>
              requireInstanceScope(records(module).instances(record.instanceOrdinal))
              if operation.generatedScope != activeGeneratedRegion then
                fail("NODAL-HIERARCHY-044", "instance array escaped its exact generated scope")
              expression
            case None =>
              val scope = shapeViews.get(expression).flatMap(_.generatedScope)
                .orElse(shapeIndices.get(expression).flatMap(_.generatedScope))
                .orElse(deferredNodeArray(expression).flatMap(_._1.generatedScope))
              if scope.exists(region => !generationStack.exists(_ eq region)) then
                fail("NODAL-HIERARCHY-044", "node-array view escaped its generated scope")
              val parent = shapeViews.get(expression).map(_.input)
                .orElse(shapeIndices.get(
                  expression
                ).filter(_.resultDimensions.nonEmpty).map(_.input))
                .orElse(deferredNodeArray(expression).map(value =>
                  nodeArrayInput(value._2.input)._1
                ))
                .getOrElse(fail(
                  "NODAL-SHAPE-043-006",
                  "node-array reference has no shaped projection"
                ))
              root(parent)
    val owner = root(input)
    if array.discipline != Electrical || array.shapeIntent.knownDimensions.exists(dimensions =>
        nodeArrayDimensions(input).exists(_ != dimensions)
      )
    then
      fail("NODAL-SHAPE-043-006", "node-array reference changed discipline or captured dimensions")
    (input, owner)

  def registerNodeArrayProjection(value: NodeArray[?], origin: NodeArrayProjection): Unit =
    val (input, _) = nodeArrayInput(origin.input)
    if value.discipline != origin.input.discipline then
      fail("NODAL-SHAPE-043-006", "node-array view changed discipline")
    val operands = origin.indices ++ origin.reshape ++ origin.slice.toVector.flatMap(value =>
      Vector(value.lower, value.upperExclusive)
    )
    val analysis = new StructuralBoundAnalysis(
      currentModule.handle,
      "NODAL-SHAPE-043-006",
      "node-array view",
      signed64Literals = true
    )
    operands.foreach(nodeIndexBound(_, analysis))
    if nodeArrayDimensions(input).isEmpty then
      val reference = captureExpression(value, Vector(origin.input) ++ operands).get
      operation(
        "deferred-node-array",
        DeferredNodeArrayRecord(value, reference, origin.input, Some(origin))
      )
    else materializeNodeArrayProjection(value, origin, None)

  private def materializeNodeArrayProjection(
      value: NodeArray[?],
      origin: NodeArrayProjection,
      retained: Option[ExpressionRef]
  ): Unit =
    val (input, _) = nodeArrayInput(origin.input)
    val source = nodeArrayDimensions(input).getOrElse(
      fail("NODAL-SHAPE-043-006", "node-array projection input is unresolved")
    )
    val dimensions = value.shapeIntent.knownDimensions.getOrElse:
      if origin.indices.nonEmpty then source.drop(origin.indices.size)
      else if origin.flatten then
        CandidateRuntime.flattenShapeDimensions(source, signed64Literal = true)
      else
        origin.slice match
          case Some(slice) => CandidateRuntime.sliceShapeDimensions(
              source,
              slice.lower,
              slice.upperExclusive,
              slice.axis
            )
          case None => origin.reshape
    val module = currentModule.handle
    val path = pathOfReference(input)
    val code = if origin.slice.nonEmpty then "NODAL-SHAPE-043-004" else "NODAL-SHAPE-043-003"
    val analysis =
      new StructuralBoundAnalysis(module, code, "node-array view", signed64Literals = true)
    val parameters = if origin.indices.nonEmpty then
      if origin.indices.size >= source.size || dimensions != source.drop(origin.indices.size) then
        fail("NODAL-SHAPE-043-002", "node-array prefix result has incompatible dimensions")
      val indices = origin.indices.map(nodeIndexBound(_, analysis))
      val extents = source.take(origin.indices.size).map(analysis(_))
      origin.indices.indices.foreach: axis =>
        val valid = directGeneratedInduction(origin.indices(axis)) match
          case Some(region) => region.maximumTripCount == 0 || analysis.sameIdentity(
              region.upperExclusive.asInstanceOf[Dimension],
              source(axis)
            ) ||
            analysis(region.upperExclusive.asInstanceOf[Dimension]).bounds.upper <= extents(
              axis
            ).bounds.lower
          case None => indices(axis).bounds.lower >= 0 && indices(axis).bounds.upper < extents(
              axis
            ).bounds.lower
        if !valid then
          fail("NODAL-SHAPE-043-002", "node-array prefix is not valid for every legal extent")
      indices.flatMap(_.parameters)
    else validateShapeViewDimensions(source, dimensions, origin.slice, module, path, analysis)
    val reference = retained.getOrElse(captureExpression(
      value,
      Vector(origin.input) ++ origin.indices ++ dimensions
    ).get)
    if origin.indices.nonEmpty then
      shapeIndices.update(
        reference,
        ShapeIndexRecord(
          reference,
          input,
          origin.indices,
          dimensions,
          generatedScope = activeGeneratedRegion
        )
      )
    else
      shapeViews.update(
        reference,
        ShapeViewRecord(reference, input, dimensions, origin.slice, activeGeneratedRegion)
      )
    parameters.distinct.foreach(markStructuralParameterEffect(_, "shape"))

  private def directGeneratedInduction(value: Any): Option[GeneratedRegionRecord] = value match
    case expression: KernelExpr[?] if expression.operation.contains("generate_index") =>
      val reference = Option(expressionIds.get(expression)).getOrElse(
        fail("NODAL-SHAPE-043-006", "generated index has no captured identity")
      )
      val region = generationStack.find(value =>
        value.owner == currentModule.handle && value.induction == reference
      )
        .getOrElse(fail("NODAL-SHAPE-043-006", "generated index escaped its active owning scope"))
      if expression.resultDescriptor != Some(
          KernelTypeDescriptor("Integer")
        ) || expression.operands.nonEmpty
      then
        fail("NODAL-SHAPE-043-006", "generated index has invalid Integer provenance")
      Some(region)
    case _ => None

  private def nodeIndexBound(value: Dimension, analysis: StructuralBoundAnalysis): StructuralBound =
    directGeneratedInduction(value) match
      case None => analysis(value)
      case Some(region) =>
        val lower = analysis(region.lower.asInstanceOf[Dimension])
        val upper = analysis(region.upperExclusive.asInstanceOf[Dimension])
        val step = analysis(region.step.asInstanceOf[Dimension])
        if (region.maximumTripCount != 0 && lower.bounds.lower < 0) || step.bounds.lower <= 0 then
          fail(
            "NODAL-SHAPE-043-002",
            "generated node index requires nonnegative lower and positive step"
          )
        // An empty scope has no attained index. Keep a representable placeholder envelope;
        // its type, owner and shape checks still run before publishing the captured template.
        val maximum = if region.maximumTripCount == 0 then lower.bounds.upper
        else math.max(lower.bounds.upper, upper.bounds.upper - 1L)
        StructuralBound(
          value,
          IterationDomain.Bounds(lower.bounds.lower, maximum),
          (lower.parameters ++ upper.parameters ++ step.parameters).distinct
        )

  private def nodeSelectionMap(
      input: DeclarationRef | ExpressionRef,
      indices: Vector[Dimension]
  ): (DeclarationRef | ExpressionRef, StructuralShapeCoordinates.Region, Vector[(BigInt, BigInt)]) =
    val analysis = new StructuralBoundAnalysis(
      currentModule.handle,
      "NODAL-SHAPE-043-006",
      "node-array physical map",
      signed64Literals = true
    )
    val bounds = mutable.HashMap.empty[String, (BigInt, BigInt)]
    def token(value: Any): String =
      val dimension = value match
        case literal: Int => literal: Dimension
        case expression: Expr[?] => expression.asInstanceOf[Expr[Integer]]: Dimension
        case _ => fail("NODAL-SHAPE-043-006", "node-array map requires static Integer dimensions")
      val checked = nodeIndexBound(dimension, analysis)
      val identity = dimension match
        case literal: Int => literal.toString
        case parameter: Param[?] =>
          val ref = declarationIds.get(parameter); s"declaration:${ref.module}:${ref.index}"
        case expression: KernelExpr[?] => expression.literal match
            case Some(literal) => literal.value.toLong.toString
            case None =>
              val ref = expressionIds.get(expression)
              val kind = if expression.operation.contains("generate_index") then "induction"
              else "expression"
              s"$kind:${ref.module}:${ref.index}"
        case _ => fail("NODAL-SHAPE-043-006", "node-array map lacks canonical static identity")
      bounds.update(identity, BigInt(checked.bounds.lower) -> BigInt(checked.bounds.upper))
      identity
    val chain = mutable.ArrayBuffer.empty[StructuralShapeCoordinates.Projection]
    def collect(reference: DeclarationRef | ExpressionRef): DeclarationRef | ExpressionRef =
      reference match
        case declaration: DeclarationRef => declaration
        case expression: ExpressionRef if instanceArrayRoot(expression).nonEmpty => expression
        case expression: ExpressionRef =>
          shapeIndices.get(expression) match
            case Some(index) =>
              val root = collect(index.input)
              chain += StructuralShapeCoordinates.Projection.Index(
                index.indices.map(token),
                index.resultDimensions.map(token)
              )
              root
            case None =>
              val view = shapeViews(expression)
              val root = collect(view.input)
              chain += StructuralShapeCoordinates.Projection.View(
                view.dimensions.map(token),
                view.slice.map(selection =>
                  KernelShapeSliceSnapshot(
                    selection.axis,
                    token(selection.lower),
                    token(selection.upperExclusive)
                  )
                )
              )
              root
    val root = collect(input)
    val dimensions = nodeArrayDimensions(root).getOrElse(
      fail("NODAL-SHAPE-043-006", "physical array root is unresolved")
    ).map(token)
    chain += StructuralShapeCoordinates.Projection.Index(indices.map(token), Vector.empty)
    try
      val projections = chain.toVector
      val region = StructuralShapeCoordinates.scalarRegion(dimensions, projections)
      val axes = StructuralShapeCoordinates.rootCoordinates(dimensions, projections)
        .map(StructuralShapeCoordinates.bounds(_, bounds.apply))
      (root, region, axes)
    catch
      case error: StructuralShapeCoordinates.InvalidCoordinate =>
        fail("NODAL-SHAPE-043-006", error.getMessage)

  private def pathOfReference(reference: DeclarationRef | ExpressionRef): String = reference match
    case declaration: DeclarationRef => declarationPath(declaration)
    case expression: ExpressionRef => instanceArrayRoot(expression).map: (operation, root) =>
        val base = instancePath(
          operation.owner,
          root.instanceOrdinal
        ) + ".array_port_" + declarationName(root.port)
        base + operation.generatedScope.map(region =>
          s".in(${generatedRegionPath(region)})"
        ).getOrElse("")
      .getOrElse(expressionPath(expression))

  def registerNodeSelection(
      value: Node[?],
      array: NodeArray[?],
      indices: Vector[Dimension],
      retained: Option[ExpressionRef] = None
  ): ExpressionRef =
    val module = currentModule
    val (input, reference) = nodeArrayInput(array)
    if value.discipline != Electrical then
      fail("NODAL-SHAPE-043-006", "node-array selection requires Electrical capability")
    if indices.size != array.shapeIntent.rank then
      fail("NODAL-SHAPE-043-002", "node-array selection requires the complete declared rank")
    val analysis =
      new StructuralBoundAnalysis(
        module.handle,
        "NODAL-SHAPE-043-006",
        "node-array index",
        signed64Literals = true
      )
    val indexBounds = indices.map(nodeIndexBound(_, analysis))
    val dimensions = nodeArrayDimensions(input)
    if dimensions.isEmpty then
      val occurrence = captureExpression(value, Vector(array) ++ indices).get
      operation(
        "deferred-node-array",
        DeferredNodeArrayRecord(value, occurrence, array, None, indices)
      )
      return occurrence
    val dimensionBounds = dimensions.get.map(analysis(_))
    indices.indices.foreach: axis =>
      val index = indexBounds(axis)
      val dimension = dimensionBounds(axis)
      val valid = directGeneratedInduction(indices(axis)) match
        case Some(region) =>
          val upper = analysis(region.upperExclusive.asInstanceOf[Dimension])
          region.maximumTripCount == 0 ||
          analysis.sameIdentity(
            region.upperExclusive.asInstanceOf[Dimension],
            dimensions.get(axis)
          ) ||
          upper.bounds.upper <= dimension.bounds.lower
        case None => index.bounds.lower >= 0L && index.bounds.upper < dimension.bounds.lower
      if !valid then
        fail("NODAL-SHAPE-043-002", "node-array index is not valid for every legal extent")
    val (_, key, axes) = nodeSelectionMap(input, indices)
    val scopeKey = reference -> activeGeneratedRegion
    val existing = nodeSelectionLookup.get(scopeKey)
    val canonicalReference = existing.flatMap(_.get(key))
    if canonicalReference.isEmpty then
      existing.foreach(_.valuesIterator.foreach: previous =>
        val record = shapeIndices(previous)
        val (_, _, otherAxes) = nodeSelectionMap(record.input, record.indices)
        if !axes.zip(otherAxes).exists((left, right) => left._2 < right._1 || right._2 < left._1)
        then
          fail(
            "NODAL-SHAPE-043-006",
            "distinct node-array selectors may alias for a legal parameter setting"
          ))
    val occurrence = retained.getOrElse(captureExpression(
      value,
      Vector(array)
    ).getOrElse(fail("NODAL-SHAPE-043-006", "node-array selection requires an active Module")))
    val endpoint = canonicalReference match
      case Some(previous) =>
        val record = shapeIndices(previous)
        shapeIndices.update(
          previous,
          record.copy(sourceUses = record.sourceUses :+ (occurrence -> indices))
        )
        previous
      case None =>
        shapeIndices.update(
          occurrence,
          ShapeIndexRecord(
            occurrence,
            input,
            indices,
            Vector.empty,
            Some(ConservativeSelectionKey(reference, key)),
            Vector(occurrence -> indices),
            activeGeneratedRegion
          )
        )
        nodeSelectionLookup.getOrElseUpdate(
          scopeKey,
          mutable.LinkedHashMap.empty
        ).update(key, occurrence)
        occurrence
    indexBounds.flatMap(_.parameters).distinct.foreach(markStructuralParameterEffect(_, "shape"))
    expressionIds.put(value, endpoint)
    endpoint

  private def conservativeEndpointPath(index: ShapeIndexRecord): String =
    closureCharge(1L)
    closureCharge(index.indices.size.toLong)
    val indices = index.indices.map:
      case literal: Int => s"literal:$literal"
      case parameter: Param[?] => s"parameter:${declarationPath(declarationIds.get(parameter))}"
      case expression: KernelExpr[?] => expression.literal match
          case Some(literal) => s"literal:${literal.value.toLong}"
          case None =>
            val tag =
              if expression.operation.contains("generate_index") then "induction" else "expression"
            s"$tag:${expressionPath(expressionIds.get(expression))}"
      case _ => fail("NODAL-SHAPE-043-006", "node-array selector lacks canonical identity")
    val suffix =
      index.generatedScope.map(region => s".in(${generatedRegionPath(region)})").getOrElse("")
    val input = pathOfReference(index.input)
    if boundaryWorkActive then
      indices.foreach(value => closureCharge(1L + value.length.toLong))
    closureCharge(6L + input.length.toLong + suffix.length.toLong)
    s"$input.at(${indices.mkString(",")})$suffix"

  private def digitalWidthBound(descriptor: KernelTypeDescriptor, owner: Long): StructuralBound =
    digitalWidthFacts(descriptor, owner).getOrElse(
      fail("NODAL-DIGITAL-054-001", "empty Generate induction has no attained bound")
    )

  private def digitalWidthFacts(
      descriptor: KernelTypeDescriptor,
      owner: Long
  ): Option[StructuralBound] =
    if descriptor.arguments.size != 1 then
      fail("NODAL-DIGITAL-054-001", "digital scalar type requires exactly one width")
    val analysis = new StructuralBoundAnalysis(
      owner,
      "NODAL-DIGITAL-054-001",
      "digital width",
      signed64Literals = true
    )
    val bound = descriptor.arguments.head match
      case width: Int => analysis.facts(width)
      case width: Expr[?] => analysis.facts(width.asInstanceOf[Expr[Integer]])
      case _ => fail("NODAL-DIGITAL-054-001", "digital width requires a static Integer expression")
    if bound.exists(_.bounds.lower <= 0L) then
      fail(
        "NODAL-DIGITAL-054-001",
        "digital width must be positive for every legal parameter value"
      )
    analysis.parameterDependencies.foreach(markStructuralParameterEffect(_, "width"))
    bound

  def digitalLiteralWidthFacts(descriptor: KernelTypeDescriptor): Option[(Long, Long)] =
    digitalWidthFacts(descriptor, currentModule.handle).map: fact =>
      (fact.bounds.lower, fact.bounds.upper)

  def digitalWidthBounds(descriptor: KernelTypeDescriptor): (Long, Long) =
    val bounds = digitalWidthBound(descriptor, currentModule.handle).bounds
    (bounds.lower, bounds.upper)

  def sameStaticInteger(left: Dimension, right: Dimension): Boolean =
    new StructuralBoundAnalysis(
      currentModule.handle,
      "NODAL-DIGITAL-054-001",
      "digital type extent",
      signed64Literals = true
    ).sameIdentity(left, right)

  def digitalWidthEquals(descriptor: KernelTypeDescriptor, expected: Int): Boolean =
    // Equality to a literal is proven by a singleton domain, including each reduction context.
    // This does not equate distinct symbolic widths whose broader domains happen to agree.
    val bounds = digitalWidthBound(descriptor, currentModule.handle).bounds
    bounds.lower == expected.toLong && bounds.upper == expected.toLong

  private def validateShapeType(
      dataType: DataType[?],
      owner: Long,
      cardinality: Boolean,
      storageContext: Boolean = false
  ): Unit =
    val _ = shapeTypeFootprint(dataType, owner, cardinality, storageContext, allowUnattained = true)

  private def shapeTypeFootprint(
      dataType: DataType[?],
      owner: Long,
      cardinality: Boolean,
      storageContext: Boolean,
      bitPatternOnly: Boolean = false,
      fixedBitPattern: Boolean = false,
      allowUnattained: Boolean = false
  ): Option[BigInt] =
    // Outer absence is an unsupported type; inner absence is a checked empty template.
    final case class Footprint(maximumBits: Option[BigInt])
    val active = new java.util.IdentityHashMap[DataType[?], java.lang.Boolean]()
    var remaining = 65536
    def identifier(name: String): Unit =
      closureCharge(1L + name.length.toLong)
      if name.length > 1024 || !name.matches("[A-Za-z_][A-Za-z0-9_]*") then
        fail("NODAL-DIGITAL-054-004", "Struct and field names require bounded portable identifiers")
    def checkedFootprint(bits: BigInt): BigInt =
      if bits <= 0 || bits > BigInt(Long.MaxValue) then
        fail(
          "NODAL-DIGITAL-054-004",
          "digital type maximum bit footprint exceeds positive signed 64-bit bounds"
        )
      bits
    def visit(current: DataType[?], depth: Int, field: Boolean): Option[Footprint] =
      closureCharge(1L)
      chargeReduction(1)
      remaining -= 1
      if depth > 128 || remaining < 0 || active.containsKey(current) then
        fail(
          "NODAL-DIGITAL-054-004",
          "recursive type is cyclic or exceeds the supported structural budget"
        )
      active.put(current, java.lang.Boolean.TRUE)
      try
        val descriptor = CandidateRuntime.typeDescriptor(current)
        if bitPatternOnly && !Set("Bits", "UInt", "SInt", "Bool", "Enum", "Struct", "Vec")(
            descriptor.kind
          )
        then
          crossingFailure(
            "crossing requires Bool, bit-vector, Enum, or finite Vec/Struct bit patterns"
          )
        descriptor.kind match
          case "Bits" | "UInt" | "SInt" =>
            val facts = digitalWidthFacts(descriptor, owner)
            if fixedBitPattern && facts.forall(value => value.bounds.lower != value.bounds.upper)
            then
              crossingFailure("consumer requires a fixed whole digital bit pattern")
            Some(Footprint(facts.map(value => BigInt(value.bounds.upper))))
          case "Bool" => Some(Footprint(Some(BigInt(1))))
          case "Integer" | "Real" => if field then Some(Footprint(Some(BigInt(64)))) else None
          case "Struct" =>
            if descriptor.arguments.size != 2 then
              fail(
                "NODAL-DIGITAL-054-004",
                "Struct requires one name and an ordered field inventory"
              )
            val name = descriptor.arguments.head match
              case value: String => value
              case _ => fail("NODAL-DIGITAL-054-004", "Struct name is missing")
            identifier(name)
            val fields = descriptor.arguments(1) match
              case values: Seq[?] => closureElements(values).map:
                  case value: StructField[?] => value
                  case _ => fail("NODAL-DIGITAL-054-004", "Struct inventory contains a non-field")
                .toVector
              case _ => fail("NODAL-DIGITAL-054-004", "Struct field inventory is missing")
            // fields is now a Vector: size is O(1). Precharge the one distinct pass;
            // the preceding name-map pass is charged element by element.
            closureCharge(fields.size.toLong)
            if fields.isEmpty || fields.size > 65536 || closureElements(fields).map(
                _.name
              ).toVector.distinct.size != fields.size
            then
              fail("NODAL-DIGITAL-054-004", "Struct requires nonempty unique bounded fields")
            val fieldsFacts = closureElements(fields).map: member =>
              identifier(member.name)
              visit(member.dataType, depth + 1, field = true).getOrElse(
                fail(
                  "NODAL-DIGITAL-054-004",
                  "Struct fields require storable digital scalar or aggregate types"
                )
              )
            .toVector
            val maximum = if closureElements(fieldsFacts).forall(_.maximumBits.nonEmpty) then
              Some(closureElements(fieldsFacts).foldLeft(BigInt(0))((total, member) =>
                checkedFootprint(total + member.maximumBits.get)
              ))
            else None
            Some(Footprint(maximum))
          case "Vec" =>
            validateShapeDescriptor(descriptor, owner, cardinality, allowUnattained)
            val element = descriptor.arguments.head.asInstanceOf[DataType[?]]
            val dimensions = descriptor.arguments(1).asInstanceOf[Seq[Dimension]]
            val analysis =
              new StructuralBoundAnalysis(owner, "NODAL-SHAPE-043-001", "Vec dimension")
            val dimensionFacts = closureElements(dimensions).map(analysis.facts).toVector
            if fixedBitPattern && closureElements(dimensionFacts).exists(_.forall(value =>
                value.bounds.lower != value.bounds.upper
              ))
            then crossingFailure("consumer requires a fixed whole digital bit pattern")
            visit(element, depth + 1, field).map: elementFacts =>
              val maximum = if closureElements(dimensionFacts).forall(_.nonEmpty) then
                elementFacts.maximumBits.map(bits =>
                  checkedFootprint(
                    closureElements(dimensionFacts).foldLeft(bits)((total, bound) =>
                      total * BigInt(bound.get.bounds.upper)
                    )
                  )
                )
              else None
              Footprint(maximum)
          case "Enum" =>
            descriptor.arguments match
              case Vector(abi: ResolvedEnumAbi) => Some(Footprint(Some(BigInt(abi.width))))
              case _ => fail("NODAL-DIGITAL-054-004", "enum type has no resolved ABI")
          case _ => None
      finally active.remove(current)
    val footprint = visit(dataType, 0, field = storageContext)
    if storageContext && footprint.isEmpty then
      fail("NODAL-DIGITAL-054-010", "digital storage requires a directionless storable value type")
    if !allowUnattained && footprint.exists(_.maximumBits.isEmpty) then
      fail("NODAL-DIGITAL-054-001", "empty Generate induction has no attained bound")
    footprint.flatMap(_.maximumBits)

  private def validateShapeDescriptor(
      descriptor: KernelTypeDescriptor,
      owner: Long,
      cardinality: Boolean,
      allowUnattained: Boolean
  ): Unit =
    closureCharge(1L)
    val element = descriptor.arguments.headOption.collect:
      case candidate: DataType[?] => candidate
    val dimensions = descriptor.arguments.lift(1).toVector.flatMap:
      case values: Seq[?] => closureElements(values).toVector
      case _ => Vector.empty
    if element.isEmpty || dimensions.isEmpty then
      fail("NODAL-SHAPE-043-001", "Vec requires an element type and at least one dimension")

    val analysis = new StructuralBoundAnalysis(owner, "NODAL-SHAPE-043-001", "Vec dimension")
    val bounds = closureElements(dimensions).map: dimension =>
      val bound = dimension match
        case value: Int => analysis.facts(value)
        case value: Param[?] => analysis.facts(value.asInstanceOf[Expr[Integer]])
        case value: KernelExpr[?] => analysis.facts(value.asInstanceOf[Expr[Integer]])
        case _ =>
          fail(
            "NODAL-SHAPE-043-001",
            "Vec dimensions require a positive static Integer expression"
          )
      if !allowUnattained && bound.isEmpty then
        fail("NODAL-SHAPE-043-001", "empty Generate induction has no attained bound")
      if bound.exists(_.bounds.lower <= 0L) then
        fail(
          "NODAL-SHAPE-043-001",
          "Vec dimension must be positive for every legal parameter setting"
        )
      bound
    .toVector
    if cardinality && closureElements(bounds).forall(_.nonEmpty) &&
      closureElements(bounds).flatten.foldLeft(BigInt(1))((size, bound) =>
        size * BigInt(bound.bounds.upper)
      ) >
        BigInt(Long.MaxValue)
    then fail("NODAL-SHAPE-043-001", "Vec maximum element count exceeds signed 64-bit bounds")
    closureElements(analysis.parameterDependencies).foreach: reference =>
      markStructuralParameterEffect(reference, "shape")
      markStructuralParameterEffect(reference, "rank")

  private def minimumShapeExtent(
      value: Any,
      analysis: StructuralBoundAnalysis
  ): Long = value match
    case dimension: Int =>
      val lower = analysis(dimension).bounds.lower
      if lower <= 0L then
        fail("NODAL-SHAPE-043-002", "shape extent has no positive finite minimum")
      lower
    case dimension: Param[?] =>
      val lower = analysis(dimension.asInstanceOf[Expr[Integer]]).bounds.lower
      if lower <= 0L then
        fail("NODAL-SHAPE-043-002", "shape extent has no positive finite minimum")
      lower
    case dimension: KernelExpr[?] =>
      val lower = analysis(dimension.asInstanceOf[Expr[Integer]]).bounds.lower
      if lower <= 0L then
        fail("NODAL-SHAPE-043-002", "shape extent has no positive finite minimum")
      lower
    case _ => fail("NODAL-SHAPE-043-002", "shape extent has no proven finite minimum")

  private def structuralShapeInput[A <: Data](
      input: Expr[Vec[A]],
      owner: Long,
      code: String,
      retainedConstruction: Option[KernelExpr[?]] = None
  ): (DeclarationRef | ExpressionRef, Option[DataType[? <: Data]], String) =
    validateCollectionDependencies(Vector(input))
    activeValueRegion.foreach(region => bindCollectionValue(input, Some(region), owner))
    val reference: DeclarationRef | ExpressionRef = input match
      case candidate: AnyRef =>
        Option(declarationIds.get(candidate))
          .map(reference => reference: DeclarationRef | ExpressionRef)
          .orElse(
            Option(expressionIds.get(candidate)).map(reference =>
              reference: DeclarationRef | ExpressionRef
            )
          )
          .getOrElse(fail(code, "shape input has no structural identity"))
    val (inputOwner, path) = reference match
      case declaration: DeclarationRef => declaration.module -> declarationPath(declaration)
      case expression: ExpressionRef => expression.module -> expressionPath(expression)
    if inputOwner != owner then
      fail(code, "shape input must be owned by the active Module", Some(path))
    val dataType = reference match
      case declaration: DeclarationRef =>
        val record = records(declaration.module).declarations(declaration.index)
        if record.kind == KernelSignalKind.Input || record.kind == KernelSignalKind.Output ||
          record.kind == KernelSignalKind.Wire || record.kind == KernelSignalKind.Register ||
          (record.kind == KernelSignalKind.Variable &&
            CandidateRuntime.proceduralShapeSource(input))
        then
          record.dataType
        else
          retainedConstruction.map: destination =>
            closureCharge(12L + records(owner).instances.size.toLong)
            val destinationReference = Option(expressionIds.get(destination))
            val exactInput = destination.constructionDependencies match
              case Vector(value: AnyRef) => value eq input
              case _ => false
            if !destination.registeredConstructionOwnedBy(this) ||
              !destination.constructionFinalized ||
              !destination.constructionKind.exists(DigitalReductionContract.Kinds) ||
              destination.constructionCallback.isEmpty || !exactInput ||
              destination.constructionSourceSlot < 0 ||
              !destinationReference.exists(ref =>
                ref.module == owner &&
                  expressionValues.get(ref).exists(_ eq destination)
              ) ||
              (record.value ne input)
            then fail(code, "selected singleton lost its registered destination or actual input")
            val boundary = instanceDigitalPorts.getOrElse(
              declaration,
              fail(code, "selected singleton requires its canonical digital boundary")
            )
            val child = records(boundary.port.module).declarations(boundary.port.index)
            val expectedKind = child.kind match
              case KernelSignalKind.Input => KernelSignalKind.InstanceInput
              case KernelSignalKind.Output => KernelSignalKind.InstanceOutput
              case _ => fail(code, "selected singleton requires an immediate digital child port")
            if boundary.generated.nonEmpty || boundary.scope.nonEmpty ||
              boundary.instance.generatedScope.nonEmpty ||
              !records(owner).instances.exists(_ eq boundary.instance) ||
              boundary.port.module != boundary.instance.child || record.kind != expectedKind
            then fail(code, "selected singleton lost its exact root boundary provenance")
            val deferred = record.dataType.collect { case value: KernelInstanceDataType[?] =>
              value
            }
              .getOrElse(fail(code, "selected singleton lost its canonical deferred type"))
            if !deferred.finalized ||
              !CandidateRuntime.expressionDataType(input).exists(_ eq deferred) ||
              !child.dataType.exists(value =>
                DigitalEnumContract.sameType(
                  deferred.formal,
                  CandidateRuntime.typeDescriptor(value)
                )
              )
            then fail(code, "selected singleton requires its finalized canonical input type")
            boundaryClosureOwner.getOrElse(
              fail(code, "selected singleton requires verified boundary closure")
            ).requireVerifiedDigital(declaration, boundary)
            deferred
      case expression: ExpressionRef =>
        val lexical = CandidateRuntime.proceduralShapeSource(input)
        val dimensions = Option.when(lexical)(CandidateRuntime.expressionDataType(input).toVector
          .flatMap(dataType => CandidateRuntime.typeDescriptor(dataType).arguments.lift(1).toVector)
          .flatMap { case values: Seq[?] => values.toVector; case _ => Vector.empty })
          .orElse(expressionValues.get(expression).filter(value =>
            DigitalValueConstruction.isDigitalValue(value) || collectionArguments.contains(
              expression
            )
          ).flatMap(CandidateRuntime.expressionDataType).map(dataType =>
            CandidateRuntime.typeDescriptor(dataType).arguments.lift(1).toVector.flatMap:
              case values: Seq[?] => values.toVector
              case _ => Vector.empty
          ))
          .orElse(shapeViews.get(expression).map(_.dimensions))
          .orElse(shapeIndices.get(expression).flatMap: index =>
            if index.resultDimensions.nonEmpty then Some(index.resultDimensions)
            else
              expressionValues.get(expression).flatMap(CandidateRuntime.expressionDataType)
                .map(CandidateRuntime.typeDescriptor).filter(_.kind == "Vec").map(descriptor =>
                  descriptor.arguments.lift(1).toVector.flatMap:
                    case values: Seq[?] => values.toVector
                    case _ => Vector.empty
                ))
          .getOrElse(
            fail(code, "shape input is not a captured structural shaped result", Some(path))
          )
        val dataType = expressionValues.get(expression)
          .flatMap(CandidateRuntime.expressionDataType)
          .getOrElse(fail(code, "structural shape input has no captured type", Some(path)))
        val descriptor = CandidateRuntime.typeDescriptor(dataType)
        val actualDimensions = descriptor.arguments.lift(1).toVector.flatMap:
          case values: Seq[?] => values.toVector
          case _ => Vector.empty
        if descriptor.kind != "Vec" || actualDimensions != dimensions then
          fail(
            code,
            "structural shape input type disagrees with its captured dimensions",
            Some(path)
          )
        Some(dataType)
    (reference, dataType, path)

  def registerShapeIndex[A <: Data, B <: Data](
      expression: KernelExpr[B],
      input: Expr[Vec[A]],
      indices: Vector[Dimension]
  ): Unit =
    val module = currentModule
    val retained = Option(expressionIds.get(expression)).filter(_ =>
      expression.constructionDependencies.nonEmpty
    )
    def captureIndex(): Option[ExpressionRef] = retained match
      case Some(reference) =>
        require(expression.constructionFinalized, "shape index construction is not sealed")
        require(!shapeIndices.contains(reference), "shape index may register only once")
        captureExpression(expression, retained = Some(reference), completingReserved = true)
      case None => captureExpression(expression)
    val (inputReference, structuralInput, inputPath) =
      structuralShapeInput(
        input,
        module.handle,
        "NODAL-SHAPE-043-002",
        retained.flatMap(_ => Option.when(expression.constructionKind.nonEmpty)(expression))
      )
    structuralInput match
      case None =>
        // Preserve the previously exposed inert expression surface until shaped
        // internal storage has an owned SSA carrier and lowering contract.
        val _ = captureIndex()
      case Some(dataType) =>
        val descriptor = Option(CandidateRuntime.typeDescriptor(dataType))
          .filter(_.kind == "Vec")
          .getOrElse(
            fail(
              "NODAL-SHAPE-043-002",
              "indexed structural value must have Vec type",
              Some(inputPath)
            )
          )
        val dimensions = descriptor.arguments.lift(1).toVector.flatMap:
          case values: Seq[?] => values.toVector
          case _ => Vector.empty
        if indices.isEmpty || indices.size > dimensions.size then
          fail(
            "NODAL-SHAPE-043-002",
            "index rank is empty or exceeds shaped rank",
            Some(inputPath)
          )
        val resultDimensions = dimensions.drop(indices.size)
        val resultDescriptor = CandidateRuntime.expressionDataType(expression)
          .map(CandidateRuntime.typeDescriptor)
          .getOrElse(
            fail(
              "NODAL-SHAPE-043-002",
              "shape index result has no data type",
              Some(inputPath)
            )
          )
        val element = descriptor.arguments.headOption.collect:
          case candidate: DataType[?] => CandidateRuntime.typeDescriptor(candidate)
        .getOrElse(
          fail(
            "NODAL-SHAPE-043-002",
            "indexed structural value has no element type",
            Some(inputPath)
          )
        )
        if resultDimensions.isEmpty then
          if resultDescriptor != element then
            fail(
              "NODAL-SHAPE-043-002",
              "full-rank index result must match the shaped element type",
              Some(inputPath)
            )
        else
          val actualDimensions = resultDescriptor.arguments.lift(1).toVector.flatMap:
            case values: Seq[?] => values.toVector
            case _ => Vector.empty
          val resultElement = resultDescriptor.arguments.headOption.collect:
            case candidate: DataType[?] => CandidateRuntime.typeDescriptor(candidate)
          if resultDescriptor.kind != "Vec" || resultElement != Some(element) ||
            actualDimensions != resultDimensions
          then
            fail(
              "NODAL-SHAPE-043-002",
              "prefix index result must retain the exact remaining shaped dimensions",
              Some(inputPath)
            )
        validateShapeIndexBounds(indices, dimensions, module.handle, inputPath)
          .foreach(markStructuralParameterEffect(_, "shape"))
        activeValueRegion.foreach: region =>
          indices.collect { case value: Expr[?] => value }.foreach(value =>
            bindCollectionValue(value, Some(region), module.handle)
          )
        val reference = captureIndex().getOrElse(
          fail("NODAL-SHAPE-043-002", "shape index requires an active Module")
        )
        if !CandidateRuntime.proceduralShapeSource(input) then
          shapeIndices.update(
            reference,
            ShapeIndexRecord(reference, inputReference, indices, resultDimensions)
          )

  private def validateShapeIndexBounds(
      indices: Vector[Dimension],
      dimensions: Vector[Any],
      owner: Long,
      path: String
  ): Vector[DeclarationRef] =
    if indices.isEmpty || indices.size > dimensions.size then
      fail("NODAL-SHAPE-043-002", "index rank is empty or exceeds shaped rank", Some(path))
    val analysis = new StructuralBoundAnalysis(owner, "NODAL-SHAPE-043-002", "shape index")
    val extentAnalysis = new StructuralBoundAnalysis(owner, "NODAL-SHAPE-043-002", "shape extent")
    val parameters = mutable.ArrayBuffer.empty[DeclarationRef]
    indices.zip(dimensions).zipWithIndex.foreach:
      case ((index, dimension), axis) =>
        val minimum = minimumShapeExtent(dimension, extentAnalysis)
        loopAffine(index) match
          case Some((region, offsets)) =>
            if !activeLoop.contains(region) then
              collectionFailure("iteration selector escaped its owner")
            val header = region.loop.get
            val shiftedLower = analysis.shiftedBounds(header.domain.lower, offsets)
            val shiftedUpper = IterationDomain.attainedBounds(header.domain)
              .map(analysis.shiftedBounds(_, offsets))
            val related = analysis.shiftedAtMost(
              header.upperExclusive,
              offsets,
              dimension.asInstanceOf[Dimension]
            )
            if header.domain.maximumTripCount != 0 &&
              (shiftedLower.lower < 0 || (!related && shiftedUpper.exists(_.upper >= minimum)))
            then
              fail(
                "NODAL-SHAPE-043-002",
                s"iteration index is not in bounds for every legal shape at axis $axis",
                Some(path)
              )
            parameters ++= offsets.flatMap(offset => analysis(offset._2).parameters)
            parameters ++= analysis(header.lower).parameters
            parameters ++= analysis(header.upperExclusive).parameters
          case None =>
            val position = analysis(index)
            if position.bounds.lower < 0L || position.bounds.upper >= minimum then
              fail(
                "NODAL-SHAPE-043-002",
                s"index is not in bounds for every legal shape at axis $axis",
                Some(path)
              )
            parameters ++= position.parameters
    parameters.toVector.distinct

  private final case class ShapeViewFactor(
      literal: Option[Long],
      root: Option[String],
      maximum: Long,
      parameters: Vector[DeclarationRef]
  )

  private def shapeViewFactors(
      value: Any,
      owner: Long,
      path: String,
      analysis: StructuralBoundAnalysis
  ): Vector[ShapeViewFactor] = value match
    case literal: Int if literal > 0 =>
      Vector(ShapeViewFactor(Some(literal.toLong), None, literal.toLong, Vector.empty))
    case expression: KernelExpr[?]
        if requireConstructionPayload(
          expression
        ) && expression.literal.exists(_.kind == "integer") =>
      val value = expression.literal.flatMap(_.value.toLongOption).filter(_ > 0).getOrElse(
        fail("NODAL-SHAPE-043-003", "fixed shape-view dimensions must be positive", Some(path))
      )
      Vector(ShapeViewFactor(Some(value), None, value, Vector.empty))
    case parameter: Param[?] =>
      val reference = Option(declarationIds.get(parameter)).getOrElse(
        fail(
          "NODAL-SHAPE-043-003",
          "symbolic shape-view dimension has no declaration identity",
          Some(path)
        )
      )
      if reference.module != owner then
        fail(
          "NODAL-SHAPE-043-003",
          "symbolic shape-view dimension escapes its owning Module",
          Some(declarationPath(reference))
        )
      val bound = analysis(parameter.asInstanceOf[Expr[Integer]])
      if bound.bounds.lower <= 0L then
        fail(
          "NODAL-SHAPE-043-003",
          "symbolic shape-view dimension requires a positive finite parameter range",
          Some(declarationPath(reference))
        )
      Vector(
        ShapeViewFactor(
          None,
          Some(s"parameter:${reference.module}:${reference.index}"),
          bound.bounds.upper,
          bound.parameters
        )
      )
    case expression: KernelExpr[?] =>
      val reference = Option(expressionIds.get(expression)).getOrElse(
        fail(
          "NODAL-SHAPE-043-003",
          "compound shape-view dimension has no captured construction identity",
          Some(path)
        )
      )
      if reference.module != owner then
        fail(
          "NODAL-SHAPE-043-003",
          "compound shape-view dimension escapes its owning Module",
          Some(expressionPath(reference))
        )
      val bound = analysis(expression.asInstanceOf[Expr[Integer]])
      if bound.bounds.lower <= 0L then
        fail(
          "NODAL-SHAPE-043-003",
          "compound shape-view dimension requires a positive finite Integer proof",
          Some(expressionPath(reference))
        )
      if expression.operation.contains("analog_mul") then
        if expression.operands.size != 2 then
          fail(
            "NODAL-SHAPE-043-003",
            "multiplicative shape-view dimension has invalid arity",
            Some(expressionPath(reference))
          )
        expression.operands.flatMap:
          case parameter: Param[?] =>
            shapeViewFactors(parameter, owner, path, analysis)
          case child: KernelExpr[?] =>
            shapeViewFactors(child, owner, path, analysis)
          case _ =>
            fail(
              "NODAL-SHAPE-043-003",
              "multiplicative shape-view dimension has a non-static operand",
              Some(expressionPath(reference))
            )
      else
        Vector(
          ShapeViewFactor(
            None,
            Some(s"expression:${reference.module}:${reference.index}"),
            bound.bounds.upper,
            bound.parameters
          )
        )
    case _ =>
      fail(
        "NODAL-SHAPE-043-003",
        "shape-view dimensions require positive literals or bounded static Integer expressions",
        Some(path)
      )

  private def shapeViewSignature(
      dimensions: Vector[Any],
      owner: Long,
      path: String,
      analysis: StructuralBoundAnalysis
  ): (BigInt, Vector[String], BigInt, Vector[DeclarationRef]) =
    val factors = dimensions.flatMap(shapeViewFactors(_, owner, path, analysis))
    if factors.isEmpty then
      fail(
        "NODAL-SHAPE-043-003",
        "fixed shape view requires at least one result dimension",
        Some(path)
      )
    val literalProduct = factors.flatMap(_.literal).foldLeft(BigInt(1))(_ * _)
    val roots = factors.flatMap(_.root).sorted
    val worstCase =
      factors.foldLeft(BigInt(1))((product, factor) => product * BigInt(factor.maximum))
    val parameters = factors.flatMap(_.parameters).distinct
    (literalProduct, roots, worstCase, parameters)

  def registerShapeView[A <: Data](
      expression: KernelExpr[Vec[A]],
      input: Expr[Vec[A]],
      dimensions: Vector[Dimension],
      slice: Option[ShapeSliceSelection] = None
  ): Unit =
    val module = currentModule
    val code = if slice.nonEmpty then "NODAL-SHAPE-043-004" else "NODAL-SHAPE-043-003"
    val (inputReference, structuralInput, path) =
      structuralShapeInput(input, module.handle, code)
    if structuralInput.isEmpty then
      if slice.nonEmpty then
        fail(code, "shape slice requires a structural module port or shaped result", Some(path))
      val _ = captureExpression(expression)
    structuralInput.foreach: inputType =>
      val descriptor = Option(CandidateRuntime.typeDescriptor(inputType))
        .filter(_.kind == "Vec")
        .getOrElse(
          fail(
            code,
            "shape-view input must have Vec type",
            Some(path)
          )
        )
      val resultDescriptor = CandidateRuntime.expressionDataType(expression)
        .map(CandidateRuntime.typeDescriptor)
      val expectedDescriptor = KernelTypeDescriptor(
        "Vec",
        Vector(descriptor.arguments.head, dimensions)
      )
      if !resultDescriptor.contains(expectedDescriptor) then
        fail(
          code,
          "shape-view result must retain the element type and exact requested dimensions",
          Some(path)
        )
      val sourceDimensions = descriptor.arguments.lift(1).toVector.flatMap:
        case values: Seq[?] => values.toVector
        case _ => Vector.empty
      val analysis = new StructuralBoundAnalysis(
        module.handle,
        code,
        "shape view"
      )
      val parameters = validateShapeViewDimensions(
        sourceDimensions,
        dimensions,
        slice,
        module.handle,
        path,
        analysis
      )
      parameters.distinct.foreach(markStructuralParameterEffect(_, "shape"))
      val reference = captureExpression(expression).getOrElse(
        fail(code, "shape view requires an active Module")
      )
      if !CandidateRuntime.proceduralShapeSource(input) then
        shapeViews.update(reference, ShapeViewRecord(reference, inputReference, dimensions, slice))

  private def validateShapeViewDimensions(
      source: Vector[Any],
      dimensions: Vector[Dimension],
      slice: Option[ShapeSliceSelection],
      owner: Long,
      path: String,
      analysis: StructuralBoundAnalysis
  ): Vector[DeclarationRef] =
    val code = if slice.nonEmpty then "NODAL-SHAPE-043-004" else "NODAL-SHAPE-043-003"
    slice match
      case Some(selection) =>
        validateShapeSlice(selection, source, dimensions, path, analysis)
      case None =>
        val (sourceLiteralProduct, sourceRoots, sourceWorstCase, sourceParameters) =
          shapeViewSignature(source, owner, path, analysis)
        val (targetLiteralProduct, targetRoots, targetWorstCase, targetParameters) =
          shapeViewSignature(dimensions, owner, path, analysis)
        if sourceLiteralProduct != targetLiteralProduct || sourceRoots != targetRoots ||
          sourceWorstCase > BigInt(Long.MaxValue) || targetWorstCase > BigInt(Long.MaxValue)
        then
          fail(
            code,
            "shape view requires equal literal products and canonical static-expression root multisets within signed 64-bit bounds",
            Some(path)
          )
        sourceParameters ++ targetParameters

  private def validateShapeSlice(
      selection: ShapeSliceSelection,
      source: Vector[Any],
      result: Vector[Dimension],
      path: String,
      analysis: StructuralBoundAnalysis
  ): Vector[DeclarationRef] =
    def invalid(message: String): Nothing = fail("NODAL-SHAPE-043-004", message, Some(path))
    def dimension(value: Any): Dimension = value match
      case literal: Int => literal
      case expression: Expr[?] => expression.asInstanceOf[Expr[Integer]]
      case _ => invalid("shape slice dimension is not Integer data")
    def identity(value: Dimension): Either[Long, DeclarationRef | ExpressionRef] = value match
      case literal: Int => Left(literal.toLong)
      case parameter: Param[?] => Right(Option(declarationIds.get(parameter)).getOrElse(
          invalid("shape slice parameter has no captured identity")
        ))
      case expression: KernelExpr[?] => expression.literal match
          case Some(literal) => Left(literal.value.toLongOption.getOrElse(
              invalid("shape slice literal exceeds signed 64-bit bounds")
            ))
          case None => Right(Option(expressionIds.get(expression)).getOrElse(
              invalid("shape slice expression has no captured identity")
            ))
      case _ => invalid("shape slice value has no canonical static identity")
    if selection.axis < 0 || selection.axis >= source.size || result.size != source.size then
      invalid("shape slice axis or result rank does not match its input")
    val sourceDimensions = source.map(dimension)
    sourceDimensions.indices.filter(_ != selection.axis).foreach: axis =>
      if identity(sourceDimensions(axis)) != identity(result(axis)) then
        invalid("shape slice must retain every unselected dimension")
    val lower = analysis(selection.lower)
    val upper = analysis(selection.upperExclusive)
    val lengthDimension = result(selection.axis)
    val length = analysis(lengthDimension)
    val lowerIdentity = identity(selection.lower)
    val upperIdentity = identity(selection.upperExclusive)
    val lengthIdentity = identity(lengthDimension)
    val canonicalLength = (lowerIdentity, upperIdentity) match
      case (Left(lo), Left(hi)) =>
        val difference = BigInt(hi) - BigInt(lo)
        difference.isValidLong && lengthIdentity == Left(difference.toLong)
      case (Left(0L), _) => lengthIdentity == upperIdentity
      case _ => lengthDimension match
          case expression: KernelExpr[?]
              if expression.operation.contains("analog_sub") &&
                expression.operands.size == 2 =>
            identity(dimension(expression.operands(0))) == upperIdentity &&
            identity(dimension(expression.operands(1))) == lowerIdentity
          case _ => false
    if !canonicalLength || lower.bounds.lower < 0L || length.bounds.lower <= 0L then
      invalid(
        "shape slice requires nonnegative lower and canonical strictly positive upper-minus-lower length"
      )
    val sourceBounds = sourceDimensions.map(analysis(_))
    val resultBounds = result.map(analysis(_))
    if upperIdentity != identity(sourceDimensions(selection.axis)) &&
      upper.bounds.upper > sourceBounds(selection.axis).bounds.lower
    then
      invalid("shape slice upper bound is not valid for every legal input extent")
    Vector(sourceBounds, resultBounds).foreach: bounds =>
      if bounds.exists(_.bounds.lower <= 0L) ||
        bounds.foldLeft(BigInt(1))((count, bound) => count * BigInt(bound.bounds.upper)) >
          BigInt(Long.MaxValue)
      then
        invalid("shape slice requires positive dimensions and signed 64-bit maximum element counts")
    (sourceBounds ++ resultBounds ++ Vector(lower, upper, length)).flatMap(_.parameters)

  private def captureExpression(
      value: AnyRef,
      structuralOperands: Vector[Any] = Vector.empty,
      retained: Option[ExpressionRef] = None,
      scopeOverride: Option[Option[DigitalValueRegionRecord]] = None,
      completingReserved: Boolean = false
  ): Option[ExpressionRef] =
    moduleStack.lastOption.map: module =>
      value match
        case expression: KernelExpr[?] if expression.typeIntent.isEmpty =>
          DeferredResultType.requireFinal(expression.operands, "expression construction")
        case _ => ()
      if activeEventProcess.nonEmpty then
        value match
          case expression: KernelExpr[?] =>
            val operation = expression.operation.getOrElse("")
            val pure = expression.literal.nonEmpty ||
              operation.startsWith(DigitalEnumContract.Prefix) ||
              DigitalScalarContract.Operations(operation) ||
              operation == DigitalMaterializationContract.Operation ||
              DigitalCombinationalContract.Operations(operation) ||
              DigitalAggregateContract.Operations(operation) ||
              DigitalCombinationalContract.ReachableBooleanOperations(operation) ||
              Set(
                "shape_index",
                "shape_view",
                "shape_slice",
                DigitalCollectionContract.Operation,
                DigitalCollectionContract.Argument
              )(operation) ||
              (expression.resultDescriptor.contains(KernelTypeDescriptor("Integer")) &&
                IterationDomain.staticArithmetic(operation).nonEmpty)
            if !pure || !expression.resultDescriptor.exists(dataType =>
                DigitalCombinationalContract.isValueTypeKind(dataType) || dataType.kind == "Integer"
              )
            then eventFailure(s"unsupported hardware expression '$operation' in event process")
          case _ => eventFailure("non-value hardware construction is unsupported in event process")
      val observation = value match
        case expression: KernelExpr[?] => validateFsmObservations(expression.operands)
        case _ => validateFsmObservations(structuralOperands)
      if reductionProofBuilding then
        value match
          case expression: KernelExpr[?]
              if expression.resultDescriptor.exists(_.kind == "Integer") => ()
          case _ =>
            collectionFailure("reduction proof may construct only temporary static expressions")
      val scope = if reductionProofBuilding then None
      else
        scopeOverride.getOrElse(value match
          case expression: KernelExpr[?] => collectionExpressionScope(expression, retained)
          case _ =>
            requireNoCollectionEffect("non-value hardware construction")
            validateCollectionDependencies(structuralOperands)
            None)
      validateGeneratedDependencies(value match
        case expression: KernelExpr[?] => expression.operands
        case _ => structuralOperands)
      val reference = retained.getOrElse:
        val fresh = allocateExpressionReference(module)
        module.expressionCount += 1
        fresh
      if completingReserved then
        require(
          retained.nonEmpty && Option(expressionIds.get(value)) == retained,
          "completion must retain its original expression identity"
        )
      if !reductionProofBuilding && !completingReserved then
        activeValueRegion.foreach: region =>
          if region.expressionCount >= 4098 then
            collectionFailure("collection body exceeds the 4098 expression budget")
          region.expressionCount += 1
      expressionIds.put(value, reference)
      observation.foreach(fact => fsmObservations.put(value, fact))
      value match
        case expression: KernelExpr[?]
            if scope.nonEmpty &&
              expression.resultDescriptor.contains(KernelTypeDescriptor("Integer")) &&
              !collectionArguments.contains(reference) && loopAffine(expression).exists(value =>
                scope.exists(_.isLoop) || value._1.loop.get.generated.isEmpty
              ) =>
          iterationIndices += reference
        case _ => ()
      scope.foreach(region => expressionRegions.update(reference, region))
      val operands = value match
        case expression: KernelExpr[?] =>
          if !expressionValues.contains(reference) then boundaryClosureOwner.foreach(_.markDirty())
          expressionValues.update(reference, expression)
          boundaryClosureOwner.foreach(_.captured(reference, expression))
          analogStack.lastOption.foreach(_.expressions += reference)
          expression.operands
        case _ => structuralOperands
      // Private partition scaffold arguments have lexical paths, but no authored source identity.
      if !reductionProofBuilding && reference.index >= 0 then
        if completingReserved then
          val expression = value.asInstanceOf[KernelExpr[?]]
          semanticOrigin.completeReservedExpression(
            expression.constructionSourceSlot,
            module.handle,
            reference.index,
            value,
            operands
          )
        else semanticOrigin.captureExpression(module.handle, reference.index, value, operands)
      reference

  def registerExpression(value: AnyRef): Unit = value match
    case expression: KernelExpr[?]
        if expression.interfaceMember.nonEmpty || expression.operation.contains(
          DigitalInterfaceContract.OrdinaryReadOperation
        ) =>
      fail(
        "NODAL-INTERFACE-054-007",
        "ordinary member reads require the owned Interface.read entrypoint"
      )
    case expression: KernelExpr[?]
        if expression.operation.contains(ExecutableWaiverContract.Operation) =>
      crossingFailure("waivers require the owned Cdc.waive entrypoint")
    case expression: KernelExpr[?]
        if expression.operation.exists(Set("reset_boundary", "rdc_sync", "reset_combine")) =>
      resetFailure("reset producers require owned reset construction entrypoints")
    case expression: KernelExpr[?]
        if expression.operation.exists(ExecutableCrossingContract.Operations) =>
      crossingFailure(
        "crossings require the owned Cdc.sync entrypoint or matching Gray/Pulse/handshake entrypoint"
      )
    case expression: KernelExpr[?] if expression.operation.contains("memory_read") =>
      memoryFailure("memory reads require the owned Mem.read entrypoint")
    case expression: KernelExpr[?]
        if expression.operation.exists(operation =>
          operation.startsWith(
            DigitalEnumContract.Prefix
          ) || DigitalScalarContract.Operations.contains(operation) ||
            operation == DigitalMaterializationContract.Operation ||
            DigitalCombinationalContract.Operations.contains(operation) ||
            DigitalAggregateContract.Operations.contains(operation)
        ) =>
      if activeGeneratedRegion.nonEmpty && activeLoop.isEmpty then
        fail(
          "NODAL-ENUM-054-020",
          "digital expressions in generated scopes require the digital generation profile"
        )
      expression.operands.collect { case metadata: StructOperationMetadata => metadata.schema }
        .foreach: schema =>
          validateShapeType(schema, currentModule.handle, cardinality = true, storageContext = true)
          bindCollectionType(
            CandidateRuntime.typeDescriptor(schema),
            activeValueRegion,
            currentModule.handle
          )
      val _ = captureExpression(value)
    case expression: KernelExpr[?]
        if expression.operation.exists(_.startsWith(AnalogContextContract.Prefix)) =>
      val _ = AnalogContextContract.validate(expression)
      val _ = captureExpression(value)
    case expression: KernelExpr[?] if CandidateRuntime.proceduralShapeSource(expression) =>
      expression.operation match
        case Some("analog_array_fill") =>
          val dataType = CandidateRuntime.expressionDataType(expression).getOrElse(
            fail("NODAL-SHAPE-043-007", "array fill requires an exact Real Vec type")
          )
          val descriptor = CandidateRuntime.typeDescriptor(dataType)
          if descriptor.kind != "Vec" || descriptor.arguments.headOption != Some(Real) ||
            expression.operands.size != 1
          then fail("NODAL-SHAPE-043-007", "array fill requires scalar Real elements")
          validateShapeType(dataType, currentModule.handle, cardinality = true)
          val _ = captureExpression(value)
        case Some("shape_index") =>
          registerShapeIndex(
            expression.asInstanceOf[KernelExpr[Data]],
            expression.operands.head.asInstanceOf[Expr[Vec[Data]]],
            expression.operands.tail.map(_.asInstanceOf[Dimension])
          )
        case Some("shape_view" | "shape_slice") =>
          val dimensions = CandidateRuntime.expressionDataType(expression).toVector
            .flatMap(dataType =>
              CandidateRuntime.typeDescriptor(dataType).arguments.lift(1).toVector
            )
            .flatMap {
              case values: Seq[?] => values.toVector.map(_.asInstanceOf[Dimension]);
              case _ => Vector.empty
            }
          if expression.operation.contains("shape_slice") then
            fail("NODAL-SHAPE-043-007", "a slice must be captured by the typed slice constructor")
          registerShapeView(
            expression.asInstanceOf[KernelExpr[Vec[Data]]],
            expression.operands.head.asInstanceOf[Expr[Vec[Data]]],
            dimensions
          )
        case _ => val _ = captureExpression(value)
    case _ => val _ = captureExpression(value)

  def proceduralShapeDimension(value: Any): String =
    val owner = value match
      case reference: AnyRef => Option(declarationIds.get(reference)).map(_.module)
          .orElse(Option(
            expressionIds.get(reference)
          ).map(_.module)).getOrElse(currentModule.handle)
      case _ => currentModule.handle
    renderShapeDimension(value, owner)

  def registerContinuousOperator(
      value: KernelExpr[Real],
      operation: String,
      input: Expr[Real],
      initialValue: Option[Expr[Real]]
  ): Unit =
    val module = currentModule
    val context = analogSemanticContext match
      case Some(candidate) if candidate.module != module.handle =>
        fail(
          "NODAL-ANALOG-035-001",
          "continuous-time operator context belongs to another Module",
          Some(provisionalModulePath(module.handle))
        )
      case Some(candidate) =>
        candidate.kind match
          case AnalogEquationRuntime.RegionKind.Equation => "equation"
          case AnalogEquationRuntime.RegionKind.Contribution => "contribution"
          case AnalogEquationRuntime.RegionKind.InitialEquation =>
            fail(
              "NODAL-ANALOG-035-001",
              "ddt and idt are not legal in initial-equation regions",
              Some(provisionalModulePath(module.handle))
            )
          case AnalogEquationRuntime.RegionKind.Procedural =>
            fail(
              "NODAL-ANALOG-035-001",
              "ddt and idt are not legal in analog procedural regions",
              Some(provisionalModulePath(module.handle))
            )
      case None if analogStack.lastOption.exists(_.module == module.handle) =>
        "legacy-analog"
      case None =>
        fail(
          "NODAL-ANALOG-035-001",
          "ddt and idt require an equation, contribution, or legacy analog region",
          Some(provisionalModulePath(module.handle))
        )

    if operation != "analog_ddt" && operation != "analog_idt" then
      fail(
        "NODAL-ANALOG-035-002",
        s"unsupported continuous-time operator '$operation'",
        Some(provisionalModulePath(module.handle))
      )
    if operation == "analog_ddt" && initialValue.nonEmpty then
      fail(
        "NODAL-ANALOG-035-002",
        "ddt does not own an initial condition",
        Some(provisionalModulePath(module.handle))
      )

    val inputDimension = inferAnalogDimension(input)
    if inputDimension.isUnknown then
      fail(
        "NODAL-ANALOG-035-003",
        s"$operation input requires a known real physical dimension",
        pathOf(input)
      )
    val resultDimension =
      if operation == "analog_ddt" then inputDimension.divide(AnalogDimension.Time)
      else inputDimension.multiply(AnalogDimension.Time)
    if resultDimension.isUnknown then
      fail(
        "NODAL-ANALOG-035-003",
        s"$operation result dimension could not be canonicalized",
        pathOf(input)
      )

    initialValue.foreach: initial =>
      val initialDimension = inferAnalogDimension(initial)
      val compatible =
        initialDimension.isZero ||
          (!initialDimension.isUnknown && initialDimension.powers == resultDimension.powers)
      if !compatible then
        fail(
          "NODAL-ANALOG-035-004",
          s"idt initial condition dimension ${initialDimension.signature} does not match result dimension ${resultDimension.signature}",
          pathOf(initial).orElse(pathOf(input))
        )

    val reference = captureExpression(value).getOrElse(
      fail(
        "NODAL-ANALOG-035-002",
        "continuous-time operator has no active construction owner"
      )
    )
    continuousOperators += ContinuousOperatorRecord(
      reference,
      operation,
      input,
      initialValue,
      context,
      inputDimension,
      resultDimension
    )

  def withWaveformForbidden[A](body: => A): A =
    waveformForbiddenDepth += 1
    try body
    finally waveformForbiddenDepth -= 1

  def registerTransferOperator(
      value: KernelExpr[Real],
      kind: String,
      numeratorSize: Int,
      denominatorSize: Int,
      inputs: Vector[Expr[Real]]
  ): Unit =
    val module = currentModule
    if analogSemanticContext.nonEmpty ||
      !analogStack.lastOption.exists(_.module == module.handle) || waveformForbiddenDepth != 0
    then
      AnalogTransferContract.fail(1, "transfer state requires an unconditional analog region")
    def checkOwner(input: Any): Unit = input match
      case reference: AnyRef =>
        if Option(expressionIds.get(reference)).exists(_.module != module.handle) ||
          Option(declarationIds.get(reference)).exists(_.module != module.handle)
        then AnalogTransferContract.fail(2, "transfer operands must belong to their Module")
        input match
          case expression: KernelExpr[?] => expression.operands.foreach(checkOwner)
          case _ => ()
      case _ => ()
    inputs.foreach(checkOwner)
    val dimensions = inputs.map(inferAnalogDimension).map(_.signature)
    val result = AnalogTransferContract.validate(
      kind,
      numeratorSize,
      denominatorSize,
      inputs,
      dimensions,
      inputs.map(waveformConstant),
      inputs.map(waveformStatic)
    )
    val reference = captureExpression(value).getOrElse(
      AnalogTransferContract.fail(2, "transfer state has no construction owner")
    )
    transferOperators += ((reference, kind, numeratorSize, denominatorSize, inputs, result))

  def registerNoiseOperator(
      value: KernelExpr[Real],
      kind: String,
      label: String,
      inputs: Vector[Expr[Real]]
  ): Unit =
    val module = currentModule
    if analogSemanticContext.nonEmpty ||
      !analogStack.lastOption.exists(_.module == module.handle) || waveformForbiddenDepth != 0
    then
      AnalogNoiseContract.fail(
        1,
        "noise sources require an unconditional analog region; procedural and equation contexts are not supported"
      )
    def checkOwner(input: Any): Unit = input match
      case reference: AnyRef =>
        if Option(expressionIds.get(reference)).exists(_.module != module.handle) ||
          Option(declarationIds.get(reference)).exists(_.module != module.handle)
        then
          AnalogNoiseContract.fail(2, "noise operands must belong to the source Module")
        input match
          case expression: KernelExpr[?] =>
            if expression.operation.exists(_.startsWith(AnalogNoiseContract.Prefix)) then
              AnalogNoiseContract.fail(
                6,
                "a noise source cannot modulate another source in this profile"
              )
            expression.operands.foreach(checkOwner)
          case _ => ()
      case _ => ()
    inputs.foreach(checkOwner)
    val dimensions = inputs.map(inferAnalogDimension).map(_.signature)
    val result =
      AnalogNoiseContract.validate(kind, inputs, dimensions, inputs.map(waveformConstant))
    val reference = captureExpression(value).getOrElse(
      AnalogNoiseContract.fail(2, "noise source has no construction owner")
    )
    noiseOperators += ((reference, kind, label, inputs, result))

  def registerWaveformOperator(
      value: KernelExpr[Real],
      operation: String,
      inputs: Vector[Expr[Real]]
  ): Unit =
    val module = currentModule
    val context = analogSemanticContext match
      case Some(candidate)
          if operation == "analog_abstime" && candidate.module == module.handle &&
            candidate.kind == AnalogEquationRuntime.RegionKind.Procedural &&
            AnalogProceduralConstruction.procedureActive => "procedure"
      case Some(candidate)
          if candidate.module == module.handle &&
            candidate.kind == AnalogEquationRuntime.RegionKind.Equation => "equation"
      case Some(candidate)
          if candidate.module == module.handle &&
            candidate.kind == AnalogEquationRuntime.RegionKind.Contribution => "contribution"
      case None if analogStack.lastOption.exists(_.module == module.handle) => "legacy-analog"
      case _ => "illegal"
    if context == "illegal" || waveformForbiddenDepth != 0 then
      fail(
        "NODAL-ANALOG-036-001",
        "time/waveform operators require an unconditional continuous region"
      )
    val allowed = operation match
      case "analog_transition" => inputs.size >= 1 && inputs.size <= 5
      case "analog_slew" => inputs.size >= 1 && inputs.size <= 3
      case "analog_absdelay" => inputs.size == 2 || inputs.size == 3
      case "analog_abstime" => inputs.isEmpty
      case "analog_bound_step" => inputs.size == 1
      case _ => false
    if !allowed then fail("NODAL-ANALOG-036-002", "invalid time/waveform operator or arity")
    def checkOwner(input: Any): Unit = input match
      case reference: AnyRef =>
        val foreignExpression =
          Option(expressionIds.get(reference)).exists(_.module != module.handle)
        val foreignDeclaration =
          Option(declarationIds.get(reference)).exists(_.module != module.handle)
        if foreignExpression || foreignDeclaration then
          fail("NODAL-ANALOG-036-002", "waveform operands must belong to the operator Module")
        input match
          case expression: KernelExpr[?] => expression.operands.foreach(checkOwner)
          case _ => ()
      case _ => ()
    inputs.foreach(checkOwner)
    val dimensions = inputs.map(inferAnalogDimension)
    if dimensions.exists(_.isUnknown) then
      fail("NODAL-ANALOG-036-003", "waveform operand requires a known real dimension")
    val effect = operation == "analog_bound_step"
    val resultDimension =
      if effect then "none"
      else if operation == "analog_abstime" then "time"
      else dimensions.head.signature
    val inputContinuity =
      if operation == "analog_abstime" || effect then "none"
      else waveformContinuity(inputs.head)
    if operation == "analog_transition" &&
      !Set("constant", "piecewise-constant").contains(inputContinuity)
    then
      fail(
        "NODAL-ANALOG-036-005",
        "transition requires proven piecewise-constant input; use slew for continuous input"
      )
    inputs.zipWithIndex.foreach: (input, index) =>
      val timing = effect || (operation != "analog_slew" && index > 0)
      val rate = operation == "analog_slew" && index > 0
      val expected =
        if timing then AnalogDimension.Time
        else if rate then dimensions.head.divide(AnalogDimension.Time)
        else dimensions(index)
      // A dimensionless literal zero is accepted only for timing slots; a zero
      // carrying another physical dimension cannot disguise a unit mismatch.
      val zeroTime = timing && dimensions(index).powers.isEmpty &&
        waveformConstant(input).contains(0.0)
      if !zeroTime && dimensions(index).powers != expected.powers then
        fail(
          "NODAL-ANALOG-036-003",
          "time arguments require seconds and slew rates require input/time"
        )
      waveformConstant(input).foreach: number =>
        val badRange =
          if rate then (if index == 1 then number <= 0.0 else number >= 0.0)
          else if operation == "analog_absdelay" && index > 0 then number <= 0.0
          else timing && number < 0.0
        if !number.isFinite || badRange then
          fail("NODAL-ANALOG-036-004", "non-finite value or invalid waveform timing/rate range")
    if operation == "analog_absdelay" && inputs.size == 3 && !waveformStatic(inputs(2)) then
      fail("NODAL-ANALOG-036-007", "maximum delay must be a constant expression")
    val outputContinuity =
      if effect then "none"
      else waveformContinuity(value)
    val reference = captureExpression(value).getOrElse(
      fail("NODAL-ANALOG-036-002", "waveform operator has no construction owner")
    )
    waveformOperators +=
      ((
        reference,
        operation,
        inputs,
        context,
        dimensions.map(_.signature),
        resultDimension,
        inputContinuity,
        outputContinuity
      ))

  private def attachInstance(
      instance: Instance[? <: Module],
      childModule: Module,
      captured: Boolean
  ): Unit =
    if !moduleIds.containsKey(childModule) then
      fail(
        "NODAL-HIERARCHY-016",
        "child Module was constructed outside this construction transaction"
      )
    val childHandle = moduleHandle(childModule)
    if moduleStack.lastOption.forall(_.handle != childHandle) then
      fail(
        "NODAL-HIERARCHY-017",
        "instance(new Child) must immediately follow child construction"
      )
    val child = records(childHandle)
    moduleStack.remove(moduleStack.size - 1)
    val parent = currentModule
    if child.parentAtConstruction != Some(parent.handle) then
      fail("NODAL-HIERARCHY-018", "child construction owner does not match Instance owner")
    if child.attached then fail("NODAL-HIERARCHY-019", "child Module was attached twice")
    val record = new InstanceRecord(
      parent.instances.size,
      childHandle,
      domainStack.lastOption,
      captured,
      activeGeneratedRegion
    )
    parent.instances += stampAllocation(parent, record)
    child.attached = true
    instanceIds.put(instance, record)
    instanceObjects.put(childModule, instance)
    activeArea.foreach(_.instances += record)
    semanticOrigin.captureInstance(
      parent.handle,
      record.ordinal,
      childHandle,
      instance,
      childModule
    )

  def attachInstance(instance: Instance[? <: Module], childModule: Module): Unit =
    attachInstance(instance, childModule, captured = false)

  def instance[M <: Module](module: M, captured: Boolean): (Instance[M], Boolean) =
    Option(instanceObjects.get(module)) match
      case Some(existing) =>
        val record = instanceRecord(existing)
        val parent = currentModule
        if !parent.instances.exists(_ eq record) then
          fail(
            "NODAL-PARAMETER-BINDING-020",
            "only the owning parent Module can retrieve a captured child Instance"
          )
        requireInstanceScope(record)
        if !record.captured || captured then
          fail("NODAL-HIERARCHY-017", "child Module was attached twice")
        (existing.asInstanceOf[Instance[M]], false)
      case None =>
        val created = new Instance(module)
        attachInstance(created, module, captured)
        (created, true)

  private def requireInstanceScope(record: InstanceRecord): Unit =
    if !currentModule.instances.exists(
        _ eq record
      ) || record.generatedScope != activeGeneratedRegion
    then
      fail("NODAL-HIERARCHY-044", "Instance handle escaped its exact lexical Module/Generate scope")

  def validateInstanceSelection(instance: AnyRef): Unit =
    requireInstanceScope(instanceRecord(instance))

  private def instanceRecord(instance: AnyRef): InstanceRecord =
    val original = instance match
      case value: InstanceSelectionRecord => value.instance
      case value => value
    Option(instanceIds.get(original)).getOrElse(
      fail("NODAL-BINDING-016", "binding targets an unknown Instance")
    )

  def bindDefault(instance: AnyRef, domain: ClockDomain): Unit =
    val record = instanceRecord(instance)
    requireInstanceScope(record)
    if domainRef(domain).module != currentModule.handle then
      fail("NODAL-BINDING-020", "actual domain must belong to the owning parent Module")
    if record.defaultBinding.exists(_ ne domain) then
      fail("NODAL-BINDING-057-001", "conflicting repeated default domain binding")
    record.defaultBinding = Some(domain)
    record.defaultBindingSource = semanticOrigin.captureSemanticSource()

  def bindNamed(instance: AnyRef, requirement: ClockDomain, domain: ClockDomain): Unit =
    val record = instanceRecord(instance)
    requireInstanceScope(record)
    val requirementReference = domainRef(requirement)
    if requirementReference.module != record.child ||
      records(record.child).domains(requirementReference.index).kind != KernelDomainKind.Required
    then fail("NODAL-BINDING-019", "selector must name an exact requirement of the child Instance")
    if domainRef(domain).module != currentModule.handle then
      fail("NODAL-BINDING-020", "actual domain must belong to the owning parent Module")
    record.namedBindings.find(_._1 eq requirement) match
      case Some((_, previous)) if previous ne domain =>
        fail("NODAL-BINDING-057-001", "conflicting repeated named domain binding")
      case Some(_) => ()
      case None =>
        record.namedBindings += requirement -> domain
        semanticOrigin.captureSemanticSource().foreach(source =>
          record.namedBindingSources(requirement) = source
        )

  def connectNodeArrays(left: NodeArray[?], right: NodeArray[?]): Unit =
    if analogStack.nonEmpty || analogSemanticContext.nonEmpty then
      fail("NODAL-SHAPE-043-007", "conservative array relations require module scope")
    val first = nodeArrayInput(nodeArrayUse(left))._1
    val second = nodeArrayInput(nodeArrayUse(right))._1
    if left.discipline != Electrical || right.discipline != Electrical then
      fail("NODAL-SHAPE-043-007", "array relation requires matching Electrical capabilities")
    operation(
      "node-array-connect",
      ConservativeArrayBindingRecord(
        first,
        second,
        semanticOrigin.captureSemanticSource()
      )
    )

  def connectNodes(left: AnyRef, right: AnyRef): Unit =
    val parent = currentModule
    val portKinds = Set(
      KernelSignalKind.AnalogInput,
      KernelSignalKind.AnalogOutput,
      KernelSignalKind.AnalogInout
    )

    def endpoint(value: AnyRef): Node[?] =
      val selected = value match
        case node: Node[?] => node.endpointReference.map: reference =>
            shapeIndices.get(reference).flatMap(_.conservative).map(_.array)
              .orElse(deferredNodeArray(reference).map(value => nodeArrayInput(value._2.input)._2))
              .getOrElse(fail("NODAL-SHAPE-043-006", "selected node has no captured array root"))
        case _ => None
      val physicalRoot = selected.orElse(Option(declarationIds.get(value))).getOrElse(
        fail("NODAL-HIERARCHY-038", "connection endpoint is outside this construction transaction")
      )
      val reference = physicalRoot match
        case declaration: DeclarationRef => declaration
        case expression: ExpressionRef =>
          val (operation, root) = instanceArrayRoot(expression).getOrElse(
            fail("NODAL-SHAPE-043-006", "selected child node lost its qualified root")
          )
          requireInstanceScope(records(operation.owner).instances(root.instanceOrdinal))
          root.port
      val owner = records(reference.module)
      val declaration = owner.declarations(reference.index)
      val node = value match
        case candidate: Node[?] => candidate
        case _ =>
          fail("NODAL-HIERARCHY-039", "conservative connection requires a node or analog port")
      val local = reference.module == parent.handle &&
        (portKinds.contains(declaration.kind) || declaration.kind == KernelSignalKind.AnalogNode ||
          (selected.nonEmpty && declaration.kind == KernelSignalKind.AnalogNodeArray))
      // attachInstance alone marks a child attached, after recording it in its exact parent.
      val childPort = owner.attached && owner.parentAtConstruction.contains(parent.handle) &&
        portKinds.contains(declaration.kind)
      if childPort then
        parent.instances.find(_.child == reference.module).foreach(requireInstanceScope)
      if local then
        requireGeneratedDeclarationScope(reference)
        val selectionScope = value match
          case selectedNode: Node[?] =>
            selectedNode.endpointReference.flatMap(shapeIndices.get).flatMap(_.generatedScope)
          case _ => None
        if selectionScope.exists(region => !generationStack.exists(_ eq region)) then
          fail("NODAL-HIERARCHY-044", "selected endpoint escaped its generated lexical scope")
      if !local && !childPort then
        fail(
          "NODAL-HIERARCHY-040",
          "connection endpoint must be local or a declared port of an attached immediate child",
          Some(declarationPath(reference))
        )
      node

    def natures(discipline: Discipline): (Nature, Nature) = discipline match
      case Electrical => Voltage -> Current
      case named: NamedDiscipline => named.potential -> named.flow

    val leftNode = endpoint(left)
    val rightNode = endpoint(right)
    val (leftPotential, leftFlow) = natures(leftNode.discipline)
    val (rightPotential, rightFlow) = natures(rightNode.discipline)
    if !(leftPotential eq rightPotential) || !(leftFlow eq rightFlow) ||
      (leftPotential eq leftFlow)
    then
      fail(
        "NODAL-HIERARCHY-041",
        "conservative connection requires compatible potential and flow nature declarations"
      )
    val dimensionsKnown = Vector(true, false).forall: potential =>
      val leftDimension = disciplineDimension(leftNode.discipline, potential)
      val rightDimension = disciplineDimension(rightNode.discipline, potential)
      !leftDimension.isUnknown && !rightDimension.isUnknown &&
      leftDimension.powers == rightDimension.powers
    if !dimensionsKnown then
      fail("NODAL-HIERARCHY-042", "conservative connection dimensions could not be proven")
    operation("node-connect", left, right)

  private def overrideTypeSignature(value: Any, owner: Long): Option[String] =
    value match
      case expression: Expr[?] =>
        CandidateRuntime.expressionDataType(expression).map(renderType(_, owner))
      case _ => None

  private def overrideDimensionSignature(value: Any): Option[String] =
    val dimension = inferAnalogDimension(value)
    if dimension.isUnknown then None else Some(dimension.signature)

  private def overrideEvidence(
      parent: ModuleRecord,
      instance: InstanceRecord,
      target: DeclarationRecord,
      value: Any
  ): AnalogHierarchyOverridePolicy.Evidence =
    val referencedDeclarationOwners = mutable.ArrayBuffer.empty[Long]
    val expressionOwners = mutable.ArrayBuffer.empty[Long]
    val operations = mutable.ArrayBuffer.empty[String]
    val visited = new IdentityHashMap[AnyRef, java.lang.Boolean]()
    var dynamicDependency = false

    def declaration(reference: DeclarationRef): DeclarationRecord =
      records(reference.module).declarations(reference.index)

    val pending = mutable.ArrayBuffer[Iterator[Any]](Iterator.single(value))
    while pending.nonEmpty do
      val current = pending.last
      if !current.hasNext then pending.remove(pending.size - 1)
      else
        current.next() match
          case _: String | _: Int | _: Long | _: Double | _: Float | _: Boolean | _: BigInt => ()
          case parameter: Param[?] =>
            Option(declarationIds.get(parameter)) match
              case Some(reference) =>
                referencedDeclarationOwners += reference.module
                if declaration(reference).kind != KernelSignalKind.Parameter then
                  dynamicDependency = true
              case None => dynamicDependency = true
          case expression: KernelExpr[?] =>
            if Option(visited.put(expression, java.lang.Boolean.TRUE)).isEmpty then
              Option(expressionIds.get(expression)) match
                case Some(reference) => expressionOwners += reference.module
                case None if expression.literal.nonEmpty => ()
                case None => dynamicDependency = true
              if expression.literal.isEmpty then
                expression.operation match
                  case Some(operation) => operations += operation
                  case None => operations += "<unknown>"
              pending += expression.operands.iterator
          case reference: AnyRef =>
            Option(declarationIds.get(reference)) match
              case Some(declarationReference) =>
                referencedDeclarationOwners += declarationReference.module
                if declaration(declarationReference).kind != KernelSignalKind.Parameter then
                  dynamicDependency = true
              case None =>
                Option(expressionIds.get(reference)) match
                  case Some(expressionReference) => expressionOwners += expressionReference.module
                  case None => dynamicDependency = true
          case _ => ()

    val targetType = target.dataType.map(renderType(_, instance.child))
    val requiresDimension =
      target.dataType.exists: dataType =>
        Set("Real", "Bool").contains(CandidateRuntime.typeDescriptor(dataType).kind)

    AnalogHierarchyOverridePolicy.Evidence(
      parentOwner = parent.handle,
      duplicate = instance.parameterOverrides.exists:
        case (existing: AnyRef, _) =>
          Option(declarationIds.get(existing)).contains(target.reference)
        case _ => false,
      referencedDeclarationOwners = referencedDeclarationOwners.toVector,
      expressionOwners = expressionOwners.toVector,
      dynamicDependency = dynamicDependency,
      operations = operations.toVector,
      targetTypeSignature = targetType,
      valueTypeSignature = overrideTypeSignature(value, parent.handle),
      requiresDimension = requiresDimension,
      targetDimensionSignature =
        if requiresDimension then overrideDimensionSignature(target.value) else None,
      valueDimensionSignature =
        if requiresDimension then overrideDimensionSignature(value) else None
    )

  def overrideParameter(instance: AnyRef, parameter: Any, value: Any): Unit =
    val record = instanceRecord(instance)
    val parent = currentModule
    if !parent.instances.exists(_ eq record) then
      fail(
        "NODAL-PARAMETER-BINDING-020",
        "instance parameter override must be recorded by the owning parent Module"
      )
    requireInstanceScope(record)
    val targetReference = parameter match
      case reference: AnyRef =>
        Option(declarationIds.get(reference)).getOrElse(
          fail(
            "NODAL-PARAMETER-BINDING-016",
            "instance parameter override targets an unknown parameter",
            Some(instancePath(parent.handle, record.ordinal))
          )
        )
      case _ =>
        fail(
          "NODAL-PARAMETER-BINDING-016",
          "instance parameter override is not a declaration",
          Some(instancePath(parent.handle, record.ordinal))
        )
    if targetReference.module != record.child then
      fail(
        "NODAL-PARAMETER-BINDING-017",
        "instance parameter override targets another Module",
        Some(instancePath(parent.handle, record.ordinal))
      )
    val target = records(record.child).declarations(targetReference.index)
    if target.kind != KernelSignalKind.Parameter then
      fail(
        "NODAL-PARAMETER-BINDING-018",
        "instance parameter override target is not a child parameter",
        Some(instancePath(parent.handle, record.ordinal))
      )

    val evidence = overrideEvidence(parent, record, target, value)
    val checked =
      if record.generatedScope.nonEmpty &&
        target.dataType.exists(
          CandidateRuntime.typeDescriptor(_) == KernelTypeDescriptor("Integer")
        ) &&
        value.isInstanceOf[Expr[?]] &&
        CandidateRuntime.expressionDataType(value.asInstanceOf[Expr[?]]).exists(
          CandidateRuntime.typeDescriptor(_) == KernelTypeDescriptor("Integer")
        )
      then
        // requireInstanceScope above retains the exact active occurrence. The shared structural
        // inspector proves real induction/capture ancestry and every operand, not its spelling.
        validateGeneratedDependencies(Vector(value))
        val proof = new StructuralBoundAnalysis(
          parent.handle,
          "NODAL-HIERARCHY-037",
          "generated Integer override",
          signed64Literals = true
        ).facts(value.asInstanceOf[Expr[Integer]])
        AnalogHierarchyOverridePolicy.validateGeneratedIntegerFacts(evidence, proof.map(_.bounds))
      else AnalogHierarchyOverridePolicy.validate(evidence)
    checked match
      case Left(rejection) =>
        fail(
          rejection.code,
          rejection.message,
          Some(instancePath(parent.handle, record.ordinal))
        )
      case Right(()) =>
        record.parameterOverrides += parameter -> value

  def commitConstructorAllocation(
      module: Module,
      parameters: Vector[ConstructorCapturedParameter]
  ): Boolean =
    val handle = moduleHandle(module)
    val record = records(handle)
    record.parentAtConstruction match
      case Some(_) =>
        val (instance, attached) = this.instance(module, captured = true)
        parameters.foreach: parameter =>
          parameter.actual.foreach(actual =>
            overrideParameter(instance, parameter.carrier, actual)
          )
        attached
      case None =>
        if moduleStack.headOption.forall(_.handle != handle) then
          fail(
            "NODAL-CONSTRUCTOR-LIFECYCLE-020",
            "captured root allocation is not the active construction root"
          )
        parameters.foreach: parameter =>
          parameter.actual.foreach: actual =>
            val targetReference = Option(declarationIds.get(parameter.carrier)).getOrElse(
              fail(
                "NODAL-PARAMETER-BINDING-016",
                "root constructor binding targets an unknown parameter"
              )
            )
            val target = record.declarations(targetReference.index)
            val duplicate = rootParameterBindings.exists:
              case (existing: AnyRef, _) =>
                Option(declarationIds.get(existing)).contains(targetReference)
              case _ => false
            val synthetic = new InstanceRecord(-1, handle, None, captured = true)
            if duplicate then synthetic.parameterOverrides += parameter.carrier -> actual
            AnalogHierarchyOverridePolicy.validate(
              overrideEvidence(record, synthetic, target, actual)
            ) match
              case Left(rejection) =>
                fail(rejection.code, rejection.message, Some(record.className))
              case Right(()) =>
                rootParameterBindings += parameter.carrier -> actual
        false

  def withDomain[A](domain: ClockDomain)(body: => A): A =
    requireNoCollectionEffect("clock-domain context")
    requireLoopEffect("clock-domain context")
    if !domainIds.containsKey(domain) then
      fail("NODAL-DOMAIN-018", "lexical ClockDomain is outside this transaction")
    if domainRef(domain).module != currentModule.handle then
      fail("NODAL-DOMAIN-018", "lexical ClockDomain must belong to the exact active Module")
    domainStack += domain
    try body
    finally
      val removed = domainStack.remove(domainStack.size - 1)
      if removed ne domain then fail("NODAL-DOMAIN-019", "lexical domain stack is corrupt")

  def currentModulePath: String = provisionalModulePath(currentModule.handle)

  private final case class StructuralBound(
      value: Any,
      bounds: IterationDomain.Bounds,
      parameters: Vector[DeclarationRef],
      identity: Option[Int] = None
  )

  private def structuralRangeAttribute(
      declaration: DeclarationRecord,
      name: String
  ): Option[Int] =
    closureElements(declaration.attributes).collectFirst:
      case (key, value: Int) if key == name => value

  // This analysis traverses captured expressions, not a second expression graph. Its cache lives
  // only for one generated domain, and arithmetic stays with the shared IterationDomain owner.
  private final class StructuralBoundAnalysis(
      owner: Long,
      diagnosticCode: String = "NODAL-ITERATION-043-001",
      subject: String = "hdlRange",
      signed64Literals: Boolean = false,
      allowUnboundedParameters: Boolean = false
  ):
    private val cache = mutable.HashMap.empty[ExpressionRef, Option[StructuralBound]]
    private val observedParameters = mutable.LinkedHashSet.empty[DeclarationRef]
    def parameterDependencies: Vector[DeclarationRef] = closureElements(observedParameters).toVector
    private val active = mutable.HashSet.empty[ExpressionRef]
    private val identityIds = mutable.HashMap.empty[(Int, String, Vector[Int]), Int]
    private val identityKeys = mutable.ArrayBuffer.empty[(Int, String, Vector[Int])]
    // Selected-use facts are private to this query, never a template-domain mutation.
    private val selectedInductions = mutable.HashMap.empty[ExpressionRef, StructuralBound]
    private var work = 0

    private def intern(key: (Int, String, Vector[Int])): Int =
      closureCharge(1L + key._3.size.toLong)
      work += 1
      if work > 65536 then invalid("structural Integer query exceeds its finite work budget")
      identityIds.getOrElseUpdate(
        key, {
          val id = identityKeys.size
          identityKeys += key
          id
        }
      )

    private def parameterFact(
        parameter: Param[?],
        allowUnbounded: Boolean = false
    ): StructuralBound =
      closureCharge(1L)
      val fact = parameterBound(
        parameter,
        owner,
        diagnosticCode,
        subject,
        allowUnbounded || allowUnboundedParameters
      )
      val reference = fact.parameters.head
      observedParameters += reference
      fact.copy(identity =
        Some(intern((1, s"${reference.module}:${reference.index}", Vector.empty)))
      )

    /** Predicate admission shares the same raw Integer DAG and checked arithmetic. An empty actual
      * Generate has no attained value: retain None while still checking every raw operand and op.
      */
    private def generatedFact(
        expression: KernelExpr[?],
        generated: GeneratedRegionRecord
    ): Option[StructuralBound] =
      closureCharge(1L)
      selectedInductions.get(generated.induction) match
        case Some(selected) => return Some(selected)
        case None => ()
      if !closureElements(generationStack).contains(generated) then
        invalid("Generate induction escaped its actual active lexical scope")
      val domain =
        closureElements(
          loopRegions.valuesIterator
        ).find(_.loop.exists(_.generated.contains(generated)))
          .map(_.loop.get.domain).getOrElse(invalid(
            "Generate induction lacks its actual domain"
          ))
      IterationDomain.attainedBounds(domain).map(bounds =>
        StructuralBound(
          expression,
          bounds,
          Vector.empty,
          Some(intern((4, expressionPath(generated.induction), Vector.empty)))
        )
      )

    private val predicateCache = mutable.HashMap.empty[ExpressionRef, Option[StructuralBound]]
    private val predicateActive = mutable.HashSet.empty[ExpressionRef]
    def predicateBound(value: Expr[Integer]): Option[StructuralBound] =
      closureCharge(1L)
      value match
        case parameter: Param[?] =>
          // Analysis returns facts only. The consuming structural construct owns classification;
          // behavioral guards must retain ordinary parameter identity without changing topology.
          Some(parameterFact(parameter, allowUnbounded = true))
        case expression: KernelExpr[?] =>
          val reference = Option(expressionIds.get(expression)).getOrElse(
            invalid("structural predicate Integer has no captured identity")
          )
          if reference.module != owner || expression.resultDescriptor != Some(
              KernelTypeDescriptor("Integer")
            )
          then
            invalid("structural predicate Integer belongs to another module or type")
          predicateCache.get(reference) match
            case Some(result) => result
            case None =>
              if predicateActive.size >= 128 || predicateCache.size >= 65536 || !predicateActive.add(
                  reference
                )
              then
                invalid("static predicate Integer DAG is cyclic or exceeds its finite budget")
              try
                val result = collectionArguments.get(reference) match
                  case Some((region, _)) =>
                    closureElements(region.captures.valuesIterator).find(_._2 eq expression) match
                      case Some((source, _)) => predicateBound(source.asInstanceOf[Expr[Integer]])
                      case None
                          if region.loop.exists(_.generated.nonEmpty) &&
                            (region.arguments.head._1 eq expression) =>
                        generatedFact(expression, region.loop.get.generated.get)
                      case _ => invalid(
                          "procedural induction or unadmitted Integer formal in static predicate"
                        )
                  case None if expression.operation.contains("generate_index") =>
                    if expression.operands.nonEmpty then invalid("malformed Generate induction")
                    val generated = closureElements(
                      records(owner).generatedRegions
                    ).find(_.induction == reference)
                      .getOrElse(invalid("static index lacks an actual Generate owner"))
                    generatedFact(expression, generated)
                  case None if expression.literal.nonEmpty => Some(literalBound(expression))
                  case None =>
                    val operation = expression.operation.flatMap(IterationDomain.staticArithmetic)
                      .getOrElse(invalid("static predicate requires pure Integer arithmetic"))
                    if expression.operands.size != operation.arity then
                      invalid("static Integer operation has wrong arity")
                    val facts = closureElements(expression.operands).map:
                      case operand: Expr[?] => predicateBound(operand.asInstanceOf[Expr[Integer]])
                      case _ =>
                        invalid("static Integer operation has an untyped or physical-unit operand")
                    .toVector
                    if closureElements(facts).forall(_.nonEmpty) then
                      Some(compose(
                        expression.operation.get,
                        closureElements(facts).flatten.toVector,
                        expression,
                        Some(expressionPath(reference))
                      ))
                    else None
                predicateCache.update(reference, result)
                result
              finally predicateActive.remove(reference)
        case _ => invalid("structural predicate requires an owned Integer expression")

    def sameIdentity(left: Dimension, right: Dimension): Boolean =
      apply(left).identity == apply(right).identity

    private def invalid(message: String, path: Option[String] = None): Nothing =
      fail(diagnosticCode, message.replace("hdlRange", subject), path)

    private def expressionBound(expression: KernelExpr[?]): Option[StructuralBound] =
      closureCharge(1L)
      val reference = Option(expressionIds.get(expression)).getOrElse(
        invalid("compound hdlRange expression has no captured construction identity")
      )
      if reference.module != owner then
        invalid(
          "compound hdlRange expression escapes its owning Module",
          Some(expressionPath(reference))
        )
      if !expression.resultDescriptor.contains(KernelTypeDescriptor("Integer")) then
        invalid("hdlRange expression must have Integer type", Some(expressionPath(reference)))
      cache.get(reference) match
        case Some(result) => result
        case None =>
          if active.size >= 512 || !active.add(reference) then
            invalid("hdlRange expression is cyclic or exceeds the supported dependency depth")
          try
            chargeReduction(1)
            val result = reductionExact.get(expression) match
              case Some(replacement) => facts(replacement)
              case None if reductionDomains.contains(expression) =>
                Some(StructuralBound(
                  expression,
                  reductionDomains(expression),
                  Vector.empty,
                  Some(intern((3, expressionPath(reference), Vector.empty)))
                ))
              case None if collectionArguments.contains(reference) =>
                val (region, _) = collectionArguments(reference)
                closureElements(region.captures.valuesIterator).find(_._2 eq expression) match
                  case Some((source, _))
                      if expression.resultDescriptor.contains(KernelTypeDescriptor("Integer")) =>
                    facts(source.asInstanceOf[Expr[Integer]])
                  case None
                      if region.loop.exists(_.generated.nonEmpty) &&
                        (region.arguments.head._1 eq expression) =>
                    generatedFact(expression, region.loop.get.generated.get)
                  case _ => invalid("unadmitted collection Integer formal")
              case None if expression.operation.contains("generate_index") =>
                if expression.operands.nonEmpty then invalid("malformed Generate induction")
                val generated =
                  closureElements(records(owner).generatedRegions).find(_.induction == reference)
                    .getOrElse(invalid("static index lacks an actual Generate owner"))
                generatedFact(expression, generated)
              case None if expression.literal.nonEmpty => Some(literalBound(expression))
              case None =>
                val operation = expression.operation.flatMap(IterationDomain.staticArithmetic)
                  .getOrElse(invalid(
                    "hdlRange expression is not supported pure Integer arithmetic"
                  ))
                val arity = operation.arity
                if expression.operands.size != arity then
                  invalid("hdlRange Integer expression has invalid arity")
                val operands = closureElements(expression.operands).map:
                  case parameter: Param[?] =>
                    Some(parameterFact(parameter))
                  case child: KernelExpr[?] => expressionBound(child)
                  case _ => invalid("hdlRange expression has a non-static or untyped operand")
                .toVector
                if closureElements(operands).forall(_.nonEmpty) then
                  Some(compose(
                    expression.operation.get,
                    closureElements(operands).flatten.toVector,
                    expression,
                    Some(expressionPath(reference))
                  ))
                else None
            cache.update(reference, result)
            result
          finally
            val _ = active.remove(reference)

    private def compose(
        name: String,
        operands: Vector[StructuralBound],
        value: Any,
        path: Option[String] = None
    ): StructuralBound =
      val operation = IterationDomain.staticArithmetic(name).getOrElse(
        invalid("unsupported static Integer composition")
      )
      // Correlation is valid only for the very same captured operand. Equal intervals
      // on independent parameters are not an equality proof.
      val bounds =
        if operation == IterationDomain.Arithmetic.Subtract &&
          operands(0).identity == operands(1).identity
        then IterationDomain.Bounds(0L, 0L)
        else
          IterationDomain.arithmetic(operation, closureElements(operands).map(_.bounds).toVector)
            .fold(
              problem => invalid(problem.message, path),
              identity
            )
      val operandIds = closureElements(operands).map(_.identity.get).toVector
      val neutral = IterationDomain.neutralArithmeticOperand(
        operation,
        closureElements(operandIds).map(id =>
          val key = identityKeys(id)
          Option.when(key._1 == 0)(key._2.toLong)
        ).toVector
      )
      val key =
        if neutral.nonEmpty then identityKeys(operandIds(neutral.get))
        else if operation == IterationDomain.Arithmetic.Maximum &&
          operandIds(0) == operandIds(1)
        then identityKeys(operandIds.head)
        else if operation == IterationDomain.Arithmetic.ChooseZero &&
          identityKeys(operandIds.head)._1 == 0
        then
          identityKeys(operandIds(if identityKeys(operandIds.head)._2 == "0" then 1
          else 2))
        else if operation == IterationDomain.Arithmetic.ChooseZero &&
          operandIds(1) == operandIds(2)
        then identityKeys(operandIds(1))
        else if closureElements(operandIds).forall(id => identityKeys(id)._1 == 0) then
          (0, bounds.lower.toString, Vector.empty[Int])
        else (2, name, operandIds)
      val parameters = closureElements(operands)
        .flatMap(operand => closureElements(operand.parameters)).toVector
      closureCharge(parameters.size.toLong) // one distinct pass over the materialized Vector
      StructuralBound(
        value,
        bounds,
        parameters.distinct,
        Some(intern(key))
      )

    def selectedGeneratedDomain(record: GeneratedRegionRecord): IterationDomain.Structural =
      if record.owner != owner || !closureElements(
          records(owner).generatedRegions
        ).exists(_ eq record)
      then
        invalid("selected domain requires its actual original Generate")
      val inputs = Vector(record.lower, record.upperExclusive, record.step)
        .map(value => apply(value.asInstanceOf[Dimension]))
      val limits = inputs.map(_.bounds)
      val domain = IterationDomain.structural(
        limits(0),
        limits(1),
        limits(2),
        record.maximum,
        identicalBounds = inputs(0).identity == inputs(1).identity ||
          (limits(0).lower == limits(0).upper && limits(1).lower == limits(1).upper &&
            limits(0).lower >= limits(1).upper)
      )
        .fold(problem => invalid(problem.message), identity)
      if domain.maximumTripCount > record.maximumTripCount then
        invalid("selected domain exceeds its original template cardinality")
      domain

    def selectGeneratedInduction(record: GeneratedRegionRecord, ordinal: StructuralBound): Unit =
      if record.owner != owner || !closureElements(
          records(owner).generatedRegions
        ).exists(_ eq record) ||
        selectedInductions.size >= 128 || selectedInductions.contains(record.induction) ||
        cache.contains(record.induction) || predicateCache.contains(record.induction) ||
        closureElements(generationStack).contains(record) || !record.digitalRegion.exists(region =>
          region.loop.exists(_.generated.contains(record)) &&
            region.arguments.headOption.exists(value =>
              value._2 == "induction" && Option(expressionIds.get(value._1))
                .flatMap(collectionArguments.get).contains(region -> 0)
            )
        )
      then invalid("selected induction requires a fresh exact prior family proof")
      selectedInductions.update(record.induction, generatedCoordinate(record, ordinal))

    def generatedCoordinate(
        record: GeneratedRegionRecord,
        ordinal: StructuralBound
    ): StructuralBound =
      compose(
        "analog_add",
        Vector(
          apply(record.lower.asInstanceOf[Dimension]),
          compose(
            "analog_mul",
            Vector(ordinal, apply(record.step.asInstanceOf[Dimension])),
            ordinal.value
          )
        ),
        ordinal.value
      )

    def shiftedBounds(base: IterationDomain.Bounds, offsets: Vector[(String, Dimension)])
        : IterationDomain.Bounds =
      closureElements(offsets).foldLeft(base): (current, offset) =>
        IterationDomain.arithmetic(
          IterationDomain.staticArithmetic(offset._1).get,
          Vector(current, apply(offset._2).bounds)
        )
          .fold(problem => invalid(problem.message), identity)

    def shiftedAtMost(base: Dimension, offsets: Vector[(String, Dimension)], extent: Dimension)
        : Boolean =
      val shifted = closureElements(offsets).foldLeft(apply(base)): (current, offset) =>
        compose(offset._1, Vector(current, apply(offset._2)), base)
      def containsWithNonnegativeSuffix(value: Dimension, depth: Int): Boolean =
        closureCharge(1L)
        if depth > 128 then invalid("static selector relation exceeds its depth budget")
        if shifted.identity == apply(value).identity then true
        else
          value match
            case expression: KernelExpr[?] if expression.operands.size == 2 =>
              val left = expression.operands.head.asInstanceOf[Dimension]
              val right = expression.operands(1).asInstanceOf[Dimension]
              if expression.operation.contains("analog_add") then
                (apply(right).bounds.lower >= 0 && containsWithNonnegativeSuffix(
                  left,
                  depth + 1
                )) ||
                (apply(left).bounds.lower >= 0 && containsWithNonnegativeSuffix(right, depth + 1))
              else
                expression.operation.contains("analog_sub") && apply(right).bounds.upper <= 0 &&
                containsWithNonnegativeSuffix(left, depth + 1)
            case _ => false
      containsWithNonnegativeSuffix(extent, 0)

    private def literalBound(expression: KernelExpr[?]): StructuralBound =
      closureCharge(1L)
      val literal = expression.literal
        .filter(value =>
          value.kind == "integer" && value.dataType == KernelTypeDescriptor("Integer") &&
            expression.resultDescriptor.contains(KernelTypeDescriptor("Integer"))
        )
        .flatMap(value =>
          if signed64Literals then value.value.toLongOption
          else value.value.toIntOption.map(_.toLong)
        )
        .getOrElse(invalid("hdlRange literal must be a representable public Integer literal"))
      if !literal.isValidInt then
        val reference = Option(expressionIds.get(expression)).getOrElse(
          invalid("derived i64 literal requires an owned construction identity")
        )
        if reference.module != owner then
          invalid("derived i64 literal escapes its owning Module", Some(expressionPath(reference)))
      StructuralBound(
        expression,
        IterationDomain.Bounds(literal, literal),
        Vector.empty,
        Some(intern((0, literal.toString, Vector.empty)))
      )

    def apply(value: Int | Expr[Integer]): StructuralBound =
      facts(value).getOrElse(invalid("empty Generate induction has no attained bound"))

    // Validity consumers may retain checked absence; numerical consumers still use apply.
    def facts(value: Int | Expr[Integer]): Option[StructuralBound] =
      closureCharge(1L)
      value match
        case literal: Int =>
          Some(StructuralBound(
            literal,
            IterationDomain.Bounds(literal, literal),
            Vector.empty,
            Some(intern((0, literal.toString, Vector.empty)))
          ))
        case parameter: Param[?] => Some(parameterFact(parameter))
        // Preserve the existing by-value handling of a direct immutable literal. A literal inside
        // an expression graph, in contrast, needs a captured owner for canonical serialization.
        case expression: KernelExpr[?]
            if requireConstructionPayload(expression) && expression.literal.nonEmpty =>
          Some(literalBound(expression))
        case expression: KernelExpr[?] => expressionBound(expression)
        case _ => invalid("hdlRange bound must be a static Integer expression")

  private def parameterBound(
      parameter: Param[?],
      owner: Long,
      diagnosticCode: String,
      subject: String,
      allowUnbounded: Boolean
  ): StructuralBound =
    val reference = Option(declarationIds.get(parameter)).getOrElse(
      fail(
        diagnosticCode,
        s"symbolic $subject value must be a parameter in the active construction transaction"
      )
    )
    if reference.module != owner then
      fail(
        diagnosticCode,
        s"symbolic $subject value must be owned by the active Module",
        Some(declarationPath(reference))
      )
    val declaration = records(reference.module).declarations(reference.index)
    val parameterType = declaration.dataType.map(renderType(_, owner))
    if declaration.kind != KernelSignalKind.Parameter || !parameterType.contains("Integer") then
      fail(
        diagnosticCode,
        s"symbolic $subject value must be an integer parameter",
        Some(declarationPath(reference))
      )
    val lower = structuralRangeAttribute(declaration, "integer_range_lower").map(_.toLong)
      .orElse(Option.when(allowUnbounded)(Long.MinValue)).getOrElse(
        fail(
          diagnosticCode,
          s"symbolic $subject parameter requires a finite declared integer range",
          Some(declarationPath(reference))
        )
      )
    val upper = structuralRangeAttribute(declaration, "integer_range_upper").map(_.toLong)
      .orElse(Option.when(allowUnbounded)(Long.MaxValue)).getOrElse(
        fail(
          diagnosticCode,
          s"symbolic $subject parameter requires a finite declared integer range",
          Some(declarationPath(reference))
        )
      )
    if lower > upper then
      fail(
        diagnosticCode,
        s"symbolic $subject parameter range is not ordered",
        Some(declarationPath(reference))
      )
    StructuralBound(parameter, IterationDomain.Bounds(lower, upper), Vector(reference))

  // Replicating analog nodes changes topology; generate is a construct, not an effect.
  private def markStructuralParameterEffect(reference: DeclarationRef, effect: String): Unit =
    val module = records(reference.module)
    val declaration = module.declarations(reference.index)
    val previousEffects = declaration.attributes.collectFirst:
      case ("structural_effects", value: String) => value
    val effects =
      (previousEffects.toVector.flatMap(_.split(",")).map(_.trim).filter(_.nonEmpty) :+ effect)
        .distinct
        .sorted
        .mkString(",")
    val retained = declaration.attributes.filterNot: (name, _) =>
      name == "classification" || name == "structural_effects"
    module.declarations.update(
      reference.index,
      declaration.copy(
        attributes = retained ++ Vector(
          "classification" -> "structural",
          "structural_effects" -> effects
        )
      )
    )

  private def markStructuralParameter(reference: DeclarationRef): Unit =
    markStructuralParameterEffect(reference, "topology")

  def withGeneratedRegion(
      lower: Int | Expr[Integer],
      upperExclusive: Int | Expr[Integer],
      step: Int | Expr[Integer],
      maximum: Option[Int]
  )(body: Expr[Integer] => Unit): Unit =
    val _ = captureGeneratedRegion(lower, upperExclusive, step, maximum, areaFamily = false)(body)

  private def captureGeneratedRegion[A](
      lower: Int | Expr[Integer],
      upperExclusive: Int | Expr[Integer],
      step: Int | Expr[Integer],
      maximum: Option[Int],
      areaFamily: Boolean
  )(body: Expr[Integer] => A): (A, GeneratedRegionRecord) =
    if areaFamily then requireAreaFamilyScope("generated Area family", requireBody = false)
    else requireNoCollectionEffect("structural generation")
    if activeLoop.exists(_.loop.get.generated.isEmpty) then
      collectionFailure("nested effectful iteration is outside the bounded loop profile")
    val module = currentModule
    val analysis = new StructuralBoundAnalysis(module.handle)
    val lowerBound = analysis(lower)
    val upperBound = analysis(upperExclusive)
    val stepBound = analysis(step)
    val envelope = IterationDomain
      .structural(
        lowerBound.bounds,
        upperBound.bounds,
        stepBound.bounds,
        maximum,
        identicalBounds = analysis.sameIdentity(lower, upperExclusive) ||
          (lowerBound.bounds.lower == lowerBound.bounds.upper &&
            upperBound.bounds.lower == upperBound.bounds.upper &&
            lowerBound.bounds.lower >= upperBound.bounds.upper)
      )
      .fold(
        problem =>
          fail(
            problem.kind match
              case IterationDomain.ProblemKind.InvalidDirection => "NODAL-ITERATION-043-003"
              case IterationDomain.ProblemKind.InvalidStep => "NODAL-ITERATION-043-002"
              case _ => "NODAL-ITERATION-043-001",
            problem.message,
            Some(provisionalModulePath(module.handle))
          ),
        identity
      )

    Vector(lowerBound, upperBound, stepBound).flatMap(_.parameters).distinct.foreach(
      markStructuralParameter
    )

    def captureBound(value: Int | Expr[Integer]): Int | Expr[Integer] = value match
      case literal: Int => literal
      case expression: Expr[Integer] =>
        if areaFamily then
          bindCollectionValue(expression, activeValueRegion, module.handle)
            .asInstanceOf[Expr[Integer]]
        else expression
    val capturedLower = captureBound(lower)
    val capturedUpper = captureBound(upperExclusive)
    val capturedStep = captureBound(step)

    val induction = new KernelExpr[Integer](
      Vector.empty,
      resultType = Some(KernelTypeDescriptor("Integer")),
      operation = Some("generate_index")
    )
    val inductionReference = captureExpression(
      induction,
      scopeOverride = Option.when(areaFamily)(None)
    ).getOrElse(
      fail("NODAL-ITERATION-043-001", "hdlRange induction has no active construction owner")
    )
    val parentOrdinal = generationStack.lastOption
      .filter(_.owner == module.handle)
      .map(_.ordinal)
    val record = new GeneratedRegionRecord(
      module.handle,
      module.generatedRegions.size,
      parentOrdinal,
      inductionReference,
      capturedLower,
      capturedUpper,
      capturedStep,
      maximum,
      envelope,
      areaFamily
    )
    module.generatedRegions += record
    generationStack += record
    generatedOwnershipRecords.update(
      generatedRegionPath(record) -> expressionPath(record.induction),
      record
    )
    val nestedAreaFamily = areaFamily && activeLoop.exists(_.loop.exists(
      _.generated.exists(_.areaFamily)
    ))
    val digitalRegion = Option.when(
      (activeLoop.isEmpty || nestedAreaFamily) && analogStack.isEmpty
    )(
      newLoopRegion(DigitalLoopHeader(
        capturedLower,
        capturedUpper,
        capturedStep,
        maximum,
        envelope,
        (if areaFamily then activeValueRegion else None).map: parent =>
          val ordinal = parent.assignmentCount
          parent.assignmentCount += 1
          ordinal
        .getOrElse(nextEffectOrdinal()),
        Some(record)
      ))
    )
    record.digitalRegion = digitalRegion
    digitalRegion.foreach: region =>
      if region.structuralGenerate != areaFamily then
        beforeRegionMutation(region)
        region.structuralGenerate = areaFamily
    val domainCount = module.domains.size
    val analogRegionCount = analogRegions.size
    val continuousOperatorCount = continuousOperators.size
    val waveformOperatorCount = waveformOperators.size
    val transferOperatorCount = transferOperators.size
    val noiseOperatorCount = noiseOperators.size
    try
      val result = body(induction)
      val generatedContext =
        generatedRegionPath(record) -> expressionPath(record.induction)
      val unsupportedDeclaration = record.declarations.iterator
        .map(reference => module.declarations(reference.index))
        .find: declaration =>
          val localInstancePort = areaFamily &&
            Set(
              KernelSignalKind.InstanceInput,
              KernelSignalKind.InstanceOutput
            )(declaration.kind) &&
            instanceDigitalPorts.get(declaration.reference).exists: boundary =>
              module.instances.exists(_ eq boundary.instance) &&
                boundary.instance.generatedScope.contains(record) &&
                boundary.port.module == boundary.instance.child
          val localInterface = areaFamily &&
            Set(
              KernelSignalKind.InterfacePort,
              KernelSignalKind.InterfaceArray
            )(declaration.kind) &&
            (declaration.value match
              case endpoint: InterfaceEndpoint =>
                val original = !endpoint.boundary && (endpoint match
                  case value: InterfacePort[?, ?] => value.origin.isEmpty
                  case _: InterfaceArray[?, ?] => true)
                val child = instanceInterfacePorts.get(declaration.reference).exists(binding =>
                  binding.generated.isEmpty && binding.scope == record.digitalRegion &&
                    binding.instance.exists(instance =>
                      module.instances.exists(_ eq instance) &&
                        instance.generatedScope.contains(
                          record
                        ) && binding.port.module == instance.child
                    )
                )
                val leaves = ConstructionInterfaceLayout.leaves(
                  endpoint.definition,
                  endpoint.role,
                  charge = closureCharge
                )
                val conservative = endpoint match
                  case port: InterfacePort[?, ?]
                      if leaves.nonEmpty && leaves.forall(_.protocol == "conservative") =>
                    val _ = requireConservativeInterfaceScope(port)
                    true
                  case _ => false
                (original || child) && (conservative || leaves.forall(leaf =>
                  leaf.analogType.isEmpty && leaf.mode.isEmpty &&
                    leaf.dataType.forall(dataType => !isBoundaryCapability(dataType))
                ))
              case _ => false)
          val localConservativeView = declaration.value match
            case view: InterfaceTerminalView[?, ?] if areaFamily =>
              val _ = requireConservativeInterfaceScope(view.endpoint)
              declaration.kind == KernelSignalKind.ConservativeTerminal
            case _ => false
          !localConservativeView && !(areaFamily && Set(
            KernelSignalKind.Wire,
            KernelSignalKind.Register
          )(declaration.kind)) && !localInstancePort && !localInterface &&
          declaration.kind != KernelSignalKind.AnalogNode &&
          declaration.kind != KernelSignalKind.AnalogNodeArray &&
          !(declaration.kind == KernelSignalKind.Variable &&
            AnalogProceduralConstruction.retainsGeneratedVariable(
              declaration.value,
              generatedContext
            ))
      unsupportedDeclaration.foreach: declaration =>
        fail(
          "NODAL-ITERATION-043-004",
          s"generated object kind '${declaration.kind.label}' is not enabled by the current F-043 checkpoint",
          Some(declarationPath(declaration.reference))
        )
      val generatedAnalogRegions = analogRegions.drop(analogRegionCount)
        .filter(_.generatedOwnership.contains(generatedContext))
      val generatedOperators = continuousOperators.drop(continuousOperatorCount).filter: operator =>
        generatedAnalogRegions.exists(_.expressions.contains(operator.reference))
      val generatedWaveforms = waveformOperators.drop(waveformOperatorCount).filter:
        case (reference, _, _, _, _, _, _, _) =>
          generatedAnalogRegions.exists(_.expressions.contains(reference))
      val generatedTransfers = transferOperators.drop(transferOperatorCount).filter:
        case (reference, _, _, _, _, _) =>
          generatedAnalogRegions.exists(_.expressions.contains(reference))
      val generatedNoise = noiseOperators.drop(noiseOperatorCount).filter:
        case (reference, _, _, _, _) =>
          generatedAnalogRegions.exists(_.expressions.contains(reference))
      val generatedOperatorReferences = generatedOperators.map(_.reference).toSet
      val generatedWaveformReferences = generatedWaveforms.map(_._1).toSet
      val generatedTransferReferences = generatedTransfers.map(_._1).toSet
      val generatedNoiseReferences = generatedNoise.map(_._1).toSet
      val generatedExpressionReferences =
        generatedOperatorReferences ++ generatedWaveformReferences ++ generatedTransferReferences ++
          generatedNoiseReferences
      val supportedGeneratedAnalog =
        generatedAnalogRegions.nonEmpty &&
          generatedAnalogRegions.forall: analog =>
            analog.generatedOwnership.contains(generatedContext) &&
              (analog.contributions.nonEmpty ||
                analog.expressions.exists(reference =>
                  generatedExpressionReferences.contains(reference) ||
                    expressionValues.get(reference).exists(_.operation.exists(
                      _.startsWith(AnalogContextContract.Prefix)
                    ))
                )) &&
              analog.expressions.forall: reference =>
                !generatedExpressionReferences.contains(reference) ||
                  generatedOperators.exists(operator =>
                    operator.reference == reference &&
                      Set("analog_ddt", "analog_idt").contains(operator.operation)
                  ) || generatedWaveforms.exists(waveform =>
                    waveform._1 == reference &&
                      Set(
                        "analog_transition",
                        "analog_slew",
                        "analog_absdelay",
                        "analog_abstime",
                        "analog_bound_step"
                      )
                        .contains(waveform._2)
                  ) || generatedTransfers.exists(transfer =>
                    transfer._1 == reference &&
                      Set("laplace_nd", "zi_nd").contains(transfer._2)
                  ) || generatedNoise.exists(noise =>
                    noise._1 == reference &&
                      Set("white", "flicker", "table").contains(noise._2)
                  )
      val changedAnalog = generatedAnalogRegions.nonEmpty || generatedOperators.nonEmpty ||
        generatedWaveforms.nonEmpty || generatedTransfers.nonEmpty || generatedNoise.nonEmpty
      if digitalRegion.exists(_.structuralGenerate) &&
        (changedAnalog || (!areaFamily && record.declarations.nonEmpty) || module.domains.size != domainCount)
      then
        structuralFailure(
          "structural hdlRange requires one pure/continuous digital body; mixed effects need a later profile"
        )
      if changedAnalog && !supportedGeneratedAnalog then
        fail(
          "NODAL-ITERATION-043-004",
          "generated continuous regions require an owned ddt, idt, transition, slew, " +
            "absdelay, abstime, boundStep, laplaceNd, ziNd, noise expression, or additive " +
            "contribution",
          Some(generatedRegionPath(record))
        )
      if module.domains.size != domainCount then
        fail(
          "NODAL-ITERATION-043-004",
          "generated domains require their own lexical ownership contract",
          Some(provisionalModulePath(module.handle))
        )
      result -> record
    catch
      case failure: Throwable =>
        poisonConstructor(failure, generatedRegionPath(record))
        scala.util.Failure[Nothing](failure).get
    finally
      digitalRegion.foreach: region =>
        val removed = collectionStack.remove(collectionStack.size - 1)
        if removed ne region then collectionFailure("unified range lexical stack is corrupt")
      val removed = generationStack.remove(generationStack.size - 1)
      if removed ne record then
        fail("NODAL-ITERATION-043-004", "generated-region ownership stack is corrupt")

  def captureAnalogProceduralSource: Option[AnalogProceduralRuntime.Source] =
    semanticOrigin
      .captureSemanticSource()
      .map(source =>
        AnalogProceduralRuntime.Source(
          source.path,
          source.line,
          source.column
        )
      )

  def currentDomain: Option[ClockDomain] = domainStack.lastOption

  def withAnalogRegion[A](body: => A): A =
    requireNoCollectionEffect("analog region")
    requireLoopEffect("analog region")
    val module = currentModule
    val generatedOwnership = generationStack.lastOption
      .filter(_.owner == module.handle)
      .map(region => generatedRegionPath(region) -> expressionPath(region.induction))
    val record =
      new AnalogRegionRecord(
        module.handle,
        analogRegions.count(_.module == module.handle),
        generatedOwnership
      )
    analogRegions += record
    analogStack += record
    try body
    finally
      val removed = analogStack.remove(analogStack.size - 1)
      if removed ne record then fail("NODAL-ANALOG-LIFECYCLE-001", "analog region stack is corrupt")

  private def equationSource: AnalogEquationRuntime.SourceSpan =
    semanticOrigin.captureSemanticSource()
      .map(span => AnalogEquationRuntime.SourceSpan(span.path, span.line, span.column))
      .getOrElse(AnalogEquationRuntime.SourceSpan("unknown.scala", 1, 1))

  private def equationComponent(owner: String): Option[String] =
    analogSemanticRecorder.currentComponent.map: component =>
      if component.owner != owner then
        fail("NODAL-ANALOG-134-001", "physical component belongs to another Module", Some(owner))
      component.identity

  def withPhysicalComponent[A](contract: PhysicalComponentContract)(body: => A): A =
    requireNoGeneratedEffect("physical component")
    val owner = provisionalModulePath(currentModule.handle)
    if contract.name.trim.isEmpty then
      fail("NODAL-ANALOG-134-001", "physical component name must be non-empty", Some(owner))
    val record = AnalogEquationRuntime.ComponentRecord(
      s"$owner.component.${contract.name.trim}",
      owner,
      contract,
      equationSource
    )
    if analogSemanticRecorder.containsComponent(record.identity) then
      fail("NODAL-ANALOG-134-001", "duplicate physical component identity", Some(record.identity))
    analogSemanticRecorder.component(record)(body)

  def declareAnalogState(state: AnalogState): Unit =
    requireNoGeneratedEffect("analog state declaration")
    val owner = provisionalModulePath(currentModule.handle)
    val component = equationComponent(owner)
    if state.name.trim.isEmpty then
      fail("NODAL-ANALOG-134-001", "analog state name must be non-empty", Some(owner))
    val identity = s"${component.getOrElse(owner)}.state.${state.name.trim}"
    if analogSemanticRecorder.containsState(identity) then
      fail("NODAL-ANALOG-134-001", "duplicate analog state identity", Some(identity))
    val dimension = inferAnalogDimension(state)
    if dimension.isUnknown then
      fail(
        "NODAL-ANALOG-134-002",
        "analog state requires a known physical dimension",
        Some(identity)
      )
    val (initialization, initializer) = state.initialization match
      case StateInitialization.Fixed(value) => "fixed" -> Some(value)
      case StateInitialization.Guess(value) => "guess" -> Some(value)
      case StateInitialization.InitialEquation => "initial-equation" -> None
      case StateInitialization.SteadyState => "steady-state" -> None
      case StateInitialization.OperatingPoint => "operating-point" -> None
      case StateInitialization.SolverSelected => "solver-selected" -> None
    initializer.foreach: value =>
      if CandidateRuntime.expressionDataType(value).exists(_ != Real) ||
        inferAnalogDimension(value).signature != dimension.signature
      then
        fail(
          "NODAL-ANALOG-134-002",
          "state initializer has incompatible physical type",
          Some(identity)
        )
    analogSemanticRecorder.state(AnalogEquationRuntime.StateRecord(
      identity,
      owner,
      component,
      state,
      dimension.signature,
      initialization,
      initializer.map(value => analogExpression(value, dimension.canonical)),
      equationSource
    ))

  private def analogSemanticSnapshot(): AnalogEquationRuntime.Snapshot =
    val original = analogSemanticRecorder.snapshot
    if original.components.isEmpty && original.states.isEmpty then original
    else
      val owners = records.valuesIterator.map(record =>
        provisionalModulePath(record.handle) -> modulePath(record.handle)
      ).toMap
      def owner(path: String): String = owners.getOrElse(path, path)
      def identity(path: String, previousOwner: String): String =
        owner(previousOwner) + path.stripPrefix(previousOwner)
      original.copy(
        components = original.components.map(record =>
          record.copy(
            identity = identity(record.identity, record.owner),
            owner = owner(record.owner)
          )
        ),
        states = original.states.map(record =>
          record.copy(
            identity = identity(record.identity, record.owner),
            owner = owner(record.owner),
            component = record.component.map(identity(_, record.owner))
          )
        ),
        equations = original.equations.map: record =>
          val previousOwner = record.metadata.owner
          record.copy(
            identity = AnalogEquationRuntime.EquationIdentity(identity(
              record.identity.value,
              previousOwner
            )),
            metadata = record.metadata.copy(
              owner = owner(previousOwner),
              component = record.metadata.component.map(identity(_, previousOwner))
            )
          )
      )

  def withAnalogSemanticRegion[A](
      kind: AnalogEquationRuntime.RegionKind
  )(body: => A): A =
    val module = currentModule
    if analogSemanticContext.nonEmpty then
      fail(
        "NODAL-ANALOG-032-001",
        "analog semantic regions cannot overlap",
        Some(provisionalModulePath(module.handle))
      )
    analogSemanticContext = Some(AnalogSemanticContext(module.handle, kind))
    try
      analogSemanticRecorder.region(kind)(body) match
        case Right(value) => value
        case Left(error) =>
          fail(error.code, error.message, Some(provisionalModulePath(module.handle)))
    finally analogSemanticContext = None

  def recordAnalogEquation(
      left: Expr[Real],
      right: Expr[Real],
      options: EquationOptions
  ): Unit =
    requireNoCollectionEffect("analog equation or contribution")
    requireLoopEffect("analog equation or contribution")
    val owner = provisionalModulePath(currentModule.handle)
    val (leftDimension, rightDimension) = requireCompatibleDimensions(
      left,
      right,
      "NODAL-ANALOG-032-006",
      "equation operands"
    )
    val identity = equationIdentity(
      equationComponent(owner).getOrElse(owner),
      options.id.map(_.value),
      s"${renderAnalogValue(left)}===${renderAnalogValue(right)}"
    )
    val metadata = analogMetadata(
      owner,
      options.guard,
      options.analyses.values.map(analysisName),
      options.continuity
    )
    accept(
      analogSemanticRecorder.recordEquation(
        identity,
        analogExpression(left, leftDimension),
        analogExpression(right, rightDimension),
        metadata
      ),
      owner
    )

  def recordInitialAnalogEquation(
      left: Expr[Real],
      right: Expr[Real],
      options: InitialEquationOptions
  ): Unit =
    requireNoCollectionEffect("analog equation or contribution")
    requireLoopEffect("analog equation or contribution")
    val owner = provisionalModulePath(currentModule.handle)
    val (leftDimension, rightDimension) = requireCompatibleDimensions(
      left,
      right,
      "NODAL-ANALOG-032-006",
      "initial-equation operands"
    )
    val identity = equationIdentity(
      equationComponent(owner).getOrElse(owner),
      options.id.map(_.value),
      s"initial:${renderAnalogValue(left)}===${renderAnalogValue(right)}"
    )
    val metadata = analogMetadata(
      owner,
      options.guard,
      Set("initialization"),
      options.continuity
    )
    accept(
      analogSemanticRecorder.recordEquation(
        identity,
        analogExpression(left, leftDimension),
        analogExpression(right, rightDimension),
        metadata
      ),
      owner
    )

  def recordAnalogContribution(
      target: Expr[Real],
      value: Expr[Real],
      options: ContributionOptions
  ): Unit =
    requireNoCollectionEffect("analog equation or contribution")
    requireLoopEffect("analog equation or contribution")
    val owner = provisionalModulePath(currentModule.handle)
    val (targetDimension, valueDimension) = requireCompatibleDimensions(
      target,
      value,
      "NODAL-ANALOG-032-011",
      "contribution target and value"
    )
    val targetRecord = analogContributionTarget(target, owner).copy(dimension = targetDimension)
    val explicitIdentity = options.id.map(_.value)
    val identity = contributionIdentity(
      owner,
      explicitIdentity,
      s"${targetRecord.identity}<+${renderAnalogValue(value)}"
    )
    val metadata = analogMetadata(
      owner,
      options.guard,
      options.analyses.values.map(analysisName),
      options.continuity
    )
    accept(
      analogSemanticRecorder.recordContribution(
        identity,
        targetRecord,
        analogExpression(value, valueDimension),
        metadata
      ),
      owner
    )

  private def requireConservativeContributionRights(target: Any): Unit = target match
    case expression: KernelExpr[?] => expression.operands.foreach:
        case view: ConservativeView[?, ?] if view.permission != "contribute" =>
          fail("NODAL-INTERFACE-058-ANALOG", "contribution requires the actual contribute view")
        case _ => ()
    case _ => ()

  def recordShortAnalogContribution(target: Expr[Real], value: Expr[Real]): Unit =
    requireConservativeContributionRights(target)
    analogSemanticContext match
      case Some(_) => recordAnalogContribution(target, value, ContributionOptions())
      case None => operation("analog-contribute", target, value)

  private def accept[A](
      result: Either[AnalogEquationRuntime.Diagnostic, A],
      owner: String
  ): Unit = result match
    case Right(_) => ()
    case Left(error) => fail(error.code, error.message, Some(owner))

  private def equationIdentity(
      owner: String,
      explicit: Option[String],
      fallbackSeed: String
  ): AnalogEquationRuntime.EquationIdentity =
    val local = semanticIdentity("equation", explicit, fallbackSeed)
    AnalogEquationRuntime.EquationIdentity(s"$owner.$local")

  private def contributionIdentity(
      owner: String,
      explicit: Option[String],
      fallbackSeed: String
  ): AnalogEquationRuntime.ContributionIdentity =
    val local = semanticIdentity("contribution", explicit, fallbackSeed)
    AnalogEquationRuntime.ContributionIdentity(s"$owner.$local")

  private def semanticIdentity(
      kind: String,
      explicit: Option[String],
      fallbackSeed: String
  ): String = explicit match
    case Some(value) if value.trim.isEmpty =>
      val suffix = if kind == "equation" then "015" else "016"
      fail(s"NODAL-ANALOG-032-$suffix", s"$kind identity must be non-empty")
    case Some(value) => value.trim
    case None => s"${kind}_${stableSemanticDigest(fallbackSeed).take(16)}"

  private def stableSemanticDigest(value: String): String =
    MessageDigest
      .getInstance("SHA-256")
      .digest(value.getBytes(StandardCharsets.UTF_8))
      .map(byte => f"${byte & 0xff}%02x")
      .mkString

  private def analogMetadata(
      owner: String,
      guard: Option[Expr[Bool]],
      analyses: Set[String],
      continuity: ContinuityClass
  ): AnalogEquationRuntime.Metadata =
    val source = semanticOrigin
      .captureSemanticSource()
      .map(span => AnalogEquationRuntime.SourceSpan(span.path, span.line, span.column))
      .getOrElse(AnalogEquationRuntime.SourceSpan("unknown.scala", 1, 1))
    AnalogEquationRuntime.Metadata(
      owner,
      guard.map(value =>
        AnalogEquationRuntime.Expression(
          renderAnalogValue(value),
          "1",
          AnalogEquationRuntime.ValueKind.Boolean
        )
      ),
      analyses,
      continuityName(continuity),
      source,
      equationComponent(owner)
    )

  private def analysisName(kind: AnalysisKind): String = kind match
    case AnalysisKind.Initialization => "initialization"
    case AnalysisKind.Dc => "dc"
    case AnalysisKind.OperatingPoint => "operating-point"
    case AnalysisKind.Transient => "transient"
    case AnalysisKind.Ac => "ac"
    case AnalysisKind.Noise => "noise"

  private def continuityName(value: ContinuityClass): String = value match
    case ContinuityClass.Unspecified => "unspecified"
    case ContinuityClass.Discontinuous => "discontinuous"
    case ContinuityClass.C0 => "c0"
    case ContinuityClass.C1 => "c1"
    case ContinuityClass.C2 => "c2"

  private def analogExpression(
      value: Expr[?],
      dimension: String
  ): AnalogEquationRuntime.Expression =
    AnalogEquationRuntime.Expression(
      renderAnalogValue(value),
      dimension,
      AnalogEquationRuntime.ValueKind.Real,
      Some(value)
    )

  private def requireCompatibleDimensions(
      left: Expr[Real],
      right: Expr[Real],
      diagnostic: String,
      label: String
  ): (String, String) =
    val leftDimension = inferAnalogDimension(left)
    val rightDimension = inferAnalogDimension(right)
    if leftDimension.isUnknown || rightDimension.isUnknown then
      fail(
        diagnostic,
        s"$label require known physical dimensions",
        pathOf(left).orElse(pathOf(right))
      )
    val leftCanonical = leftDimension.canonical
    val rightCanonical = rightDimension.canonical
    if leftCanonical != rightCanonical then
      fail(
        diagnostic,
        s"$label have incompatible dimensions: $leftCanonical versus $rightCanonical",
        pathOf(left).orElse(pathOf(right))
      )
    leftCanonical -> rightCanonical

  def registerAnalogFunction(expression: KernelExpr[Real], id: String): Unit =
    // Infer before capture: a failed call cannot leave a partially accepted expression.
    val _ = inferAnalogDimension(expression)
    val _ = AnalogFunctionContract.constant(id, expression.operands.map(waveformConstant))
    registerExpression(expression)

  def defineUserFunction[A <: Data](
      name: String,
      resultType: DataType[A],
      units: PhysicalDimension,
      body: AnalogFunctionBody => Expr[A]
  ): AnalogFunction[A] =
    if analogStack.nonEmpty || analogSemanticContext.nonEmpty then
      AnalogUserFunctionRuntime.fail(1, "analog function declarations belong directly to a Module")
    userFunctions.define(
      currentModule.handle,
      name,
      resultType,
      units,
      () => captureAnalogProceduralSource,
      body
    )

  def callUserFunction[A <: Data](
      function: AnalogFunction[A],
      arguments: Vector[Expr[?]]
  ): Expr[A] =
    val module = currentModule
    if analogSemanticContext.nonEmpty || !analogStack.lastOption.exists(_.module == module.handle)
    then
      AnalogUserFunctionRuntime.fail(
        1,
        "analog function calls currently require an analog expression region"
      )
    val definition = userFunctions.validate(function, module.handle)
    def owned(value: Any): Unit = value match
      case reference: AnyRef =>
        if Option(expressionIds.get(reference)).exists(_.module != module.handle) ||
          Option(declarationIds.get(reference)).exists(_.module != module.handle)
        then AnalogUserFunctionRuntime.fail(5, "analog function argument belongs to another Module")
        value match
          case expression: KernelExpr[?] => expression.operands.foreach(owned)
          case _ => ()
      case _ => ()
    arguments.foreach: argument =>
      if !expressionIds.containsKey(argument) && !declarationIds.containsKey(argument) then
        AnalogUserFunctionRuntime.fail(5, "function call argument has no active construction owner")
      owned(argument)
    val types = arguments.map: argument =>
      val kind = CandidateRuntime.expressionDataType(argument) match
        case Some(Integer) => "integer"
        case Some(Real) | None => "real"
        case _ => AnalogUserFunctionRuntime.fail(3, "unsupported function argument scalar type")
      AnalogUserFunctionRuntime.ValueType(kind, inferAnalogDimension(argument).signature)
    AnalogUserFunctionRuntime.checkArguments(definition, types)
    val expression = AnalogUserFunctionRuntime.expression(function, arguments)
    registerExpression(expression)
    expression

  private def analogContributionTarget(
      target: Expr[Real],
      owner: String
  ): AnalogEquationRuntime.ContributionTarget = target match
    case expression: KernelExpr[?] =>
      val kind = expression.operation match
        case Some("potential_access") | Some("candidate-branch-potential") =>
          AnalogEquationRuntime.ContributionKind.Potential
        case Some("flow_access") | Some("candidate-branch-flow") =>
          AnalogEquationRuntime.ContributionKind.Flow
        case _ =>
          fail(
            "NODAL-ANALOG-133-005",
            "contribution target must be a potential or flow access",
            pathOf(target)
          )
      requireConservativeContributionRights(target)
      val (identity, orientation) = contributionTargetIdentity(expression.operands, owner)
      AnalogEquationRuntime.ContributionTarget(
        identity,
        kind,
        inferAnalogDimension(target).canonical,
        orientation
      )
    case _ =>
      fail(
        "NODAL-ANALOG-133-005",
        "contribution target must be a potential or flow access",
        pathOf(target)
      )

  private def contributionTargetIdentity(
      values: Vector[Any],
      owner: String
  ): (String, String) =
    val branchIdentity = values.collectFirst:
      case branch: Branch[?] =>
        val positive = renderAnalogValue(branch.positive)
        val negative = renderAnalogValue(branch.negative)
        val identity = branch.name
          .filter(_.trim.nonEmpty)
          .map(name => s"$owner.branch.${name.trim}")
          .getOrElse(s"$owner.branch.$positive->$negative")
        identity -> s"$positive->$negative"
    branchIdentity
      .orElse:
        val endpoints = values.collect:
          case node: Node[?] => renderAnalogValue(node)
          case terminal: Terminal[?] => renderAnalogValue(terminal)
          case view: ConservativeView[?, ?] => renderAnalogValue(view.conservativeIdentity)
        endpoints match
          case Vector(positive, negative, _*) =>
            Some(s"$owner.branch.$positive->$negative" -> s"$positive->$negative")
          case Vector(single) =>
            Some(s"$owner.branch.$single->reference" -> s"$single->reference")
          case _ => None
      .getOrElse:
        val rendered = values.map(renderAnalogValue).mkString("(", ",", ")")
        s"$owner.branch.$rendered" -> rendered

  /** F32 authored text is retained verbatim, but its tree expansion must be bounded before
    * materialization. Identity memoization makes the preflight follow shared expression DAGs once;
    * cached heights still check the full path when a dependency is reached at a greater depth.
    */
  private def renderAnalogValue(value: Any): String =
    val maximumCharacters = 1024L * 1024L
    val maximumDepth = 512
    final case class Rendering(
        prefix: String,
        children: Vector[Any] = Vector.empty,
        separator: String = "",
        suffix: String = ""
    )
    val memo = new IdentityHashMap[AnyRef, (Rendering, Long, Int)]()
    val active = new IdentityHashMap[AnyRef, java.lang.Boolean]()
    def invalid(): Nothing = fail(
      "NODAL-ANALOG-134-001",
      "authored analog expression exceeds the supported 1048576-character or 512-level limit, or is cyclic"
    )
    def describe(value: Any): Rendering = value match
      case expression: KernelExpr[?]
          if requireConstructionPayload(expression) && expression.literal.nonEmpty =>
        val literal = expression.literal.map(_.value).getOrElse("")
        val unit = expression.operands.lift(1).collect { case text: String => text }.getOrElse("")
        Rendering(if unit.isEmpty then literal else s"$literal $unit")
      case expression: KernelExpr[?] =>
        Rendering(
          expression.operation.getOrElse("expression") + "(",
          expression.operands,
          ",",
          ")"
        )
      case branch: Branch[?] =>
        branch.name.filter(_.trim.nonEmpty).map(Rendering(_)).getOrElse(
          Rendering("", Vector(branch.positive, branch.negative), "->")
        )
      case state: AnalogState => Rendering(s"state:${state.name}")
      case view: TerminalView[?, ?] => describe(view.terminal)
      case view: InterfaceTerminalView[?, ?] =>
        Rendering(pathOf(view).getOrElse(fail(
          "NODAL-INTERFACE-058-ANALOG",
          "unowned conservative view"
        )))
      case terminal: Terminal[?] =>
        Rendering(pathOf(terminal).getOrElse(s"terminal:${terminal.name}"))
      case node: Node[?] => Rendering(pathOf(node).getOrElse("analog-node"))
      case parameter: Param[?] => Rendering(pathOf(parameter).getOrElse("parameter"))
      case reference: AnyRef =>
        Rendering(pathOf(reference).getOrElse(stableClassName(reference)))
      case other => Rendering(other.toString)
    def preflight(value: Any, depth: Int): (Rendering, Long, Int) =
      if depth >= maximumDepth then invalid()
      val reference = value match
        case value: AnyRef => Some(value)
        case _ => None
      if reference.exists(active.containsKey) then invalid()
      reference.flatMap(value => Option(memo.get(value))) match
        case Some(result) =>
          if depth + result._3 >= maximumDepth then invalid()
          result
        case None =>
          reference.foreach(value => active.put(value, java.lang.Boolean.TRUE))
          try
            val rendering = describe(value)
            var size = rendering.prefix.length.toLong + rendering.suffix.length.toLong +
              rendering.separator.length.toLong * (rendering.children.size - 1).max(0)
            if size > maximumCharacters then invalid()
            var height = 0
            rendering.children.foreach: child =>
              val (_, childSize, childHeight) = preflight(child, depth + 1)
              if childSize > maximumCharacters - size then invalid()
              size += childSize
              height = height.max(childHeight + 1)
            val result = (rendering, size, height)
            reference.foreach(value => memo.put(value, result))
            result
          finally reference.foreach(active.remove)
    val (_, size, _) = preflight(value, 0)
    val rendered = new java.lang.StringBuilder(size.toInt)
    def append(value: Any): Unit =
      val rendering = value match
        case reference: AnyRef => Option(memo.get(reference)).map(_._1).getOrElse(describe(value))
        case _ => describe(value)
      rendered.append(rendering.prefix)
      rendering.children.zipWithIndex.foreach: (child, index) =>
        if index != 0 then rendered.append(rendering.separator)
        append(child)
      rendered.append(rendering.suffix)
    append(value)
    rendered.toString

  private def memoryFailure(message: String): Nothing =
    val failure = new ConstructionException(KernelDiagnostic(
      "NODAL-MEMORY-056-001",
      message,
      moduleStack.lastOption.map(value => provisionalModulePath(value.handle))
    ))
    poisonConstructor(failure, "memory port")
    scala.util.Failure[Nothing](failure).get

  private def memoryTransaction[A](body: => A): A =
    try body
    catch
      case failure: Throwable =>
        poisonConstructor(failure, "memory port")
        scala.util.Failure[Nothing](failure).get

  private def memoryDeclaration(memory: Mem[?], owner: Long): DeclarationRecord =
    val reference = Option(declarationIds.get(memory)).getOrElse(
      memoryFailure("memory port requires an actual captured declaration")
    )
    val declaration = records(reference.module).declarations(reference.index)
    if reference.module != owner || declaration.kind != KernelSignalKind.Memory ||
      (declaration.value ne memory) || domainRef(memory.domain).module != owner
    then memoryFailure("memory port and its declared domain must belong to the active Module")
    declaration

  private def memoryOperand(value: Expr[?], owner: Long): KernelTypeDescriptor =
    val reference = valueReference(value)
    val module = reference match
      case value: DeclarationRef => value.module
      case value: ExpressionRef => value.module
    if module != owner then memoryFailure("memory operand belongs to another Module")
    CandidateRuntime.expressionDataType(value).map(CandidateRuntime.typeDescriptor).getOrElse(
      memoryFailure("memory operand requires an exact captured type")
    )

  def memoryRead[A <: Data](memory: Mem[A], address: Expr[UInt]): Expr[A] = memoryTransaction:
    closeBehavioralChain()
    if activeValueRegion.nonEmpty || activeGeneratedRegion.nonEmpty || analogStack.nonEmpty ||
      analogSemanticContext.nonEmpty
    then
      memoryFailure(
        "memory read creation requires Module scope; capture an existing read into actions"
      )
    val owner = currentModule.handle
    val _ = memoryDeclaration(memory, owner)
    if memoryOperand(address, owner).kind != "UInt" then
      memoryFailure("memory address requires an exact UInt value")
    validateCollectionDependencies(Vector(address))
    validateGeneratedDependencies(Vector(address))
    val result = new KernelExpr[A](
      Vector(address, MemoryPortCapture(memory, currentDomain)),
      resultType = Some(CandidateRuntime.typeDescriptor(memory.element)),
      operation = Some("memory_read")
    )
    val _ = captureExpression(result)
    result

  def memoryWrite[A <: Data](
      memory: Mem[A],
      address: Expr[UInt],
      value: Expr[A],
      mask: Expr[Bits]
  ): Unit = memoryTransaction:
    val owner = currentModule.handle
    val _ = memoryDeclaration(memory, owner)
    if memoryOperand(address, owner).kind != "UInt" then
      memoryFailure("memory address requires an exact UInt value")
    if !DigitalEnumContract.sameType(
        memoryOperand(value, owner),
        CandidateRuntime.typeDescriptor(memory.element)
      )
    then memoryFailure("memory write value requires the exact declared recursive element type")
    if !Set("Bits", "UInt")(memoryOperand(mask, owner).kind) then
      memoryFailure("memory mask requires Bits or UInt")
    if collectionStack.exists(region => !sequentialRegion(region)) ||
      activeGeneratedRegion.nonEmpty || analogStack.nonEmpty || analogSemanticContext.nonEmpty
    then memoryFailure("memory write requires Module scope or a checked sequential action")
    captureOperation(
      "memory-write",
      Vector(memory, address, value, mask, MemoryPortCapture(memory, currentDomain)),
      permitMemory = true
    )

  private def memoryPortSnapshot(capture: MemoryPortCapture, owner: Long)
      : KernelMemoryPortSnapshot =
    val declaration = memoryDeclaration(capture.memory, owner)
    KernelMemoryPortSnapshot(
      declarationPath(declaration.reference),
      domainPath(domainRef(capture.memory.domain))
    )

  private def memoryReadSnapshot(expression: KernelExpr[?]): Option[KernelMemoryPortSnapshot] =
    publicationProbe:
      val metadata =
        publicationVector(expression.operands).collect { case value: MemoryPortCapture => value }
      if expression.operation.contains("memory_read") then
        val reference = expressionIds.get(expression)
        if metadata.size != 1 || expression.operands.size != 2 ||
          expression.operands.lastOption != metadata.headOption || expressionRegions.contains(
            reference
          )
        then memoryFailure("memory read has malformed operands or lexical ownership")
        Some(memoryPortSnapshot(metadata.head, reference.module))
      else
        if metadata.nonEmpty then
          memoryFailure("memory port metadata is attached to another operation")
        None

  private def memoryWriteRecords: Vector[(KernelMemoryWriteSnapshot, Operation)] =
    val writes = publicationElements(operations.iterator).filter(_.kind == "memory-write").toVector
    val bases = writes.groupMapReduce(_.owner)(_ => 1)(_ + _).map: (owner, count) =>
      owner -> Vector.tabulate(count)(ordinal => s"memory_write_$ordinal")
    val paths = semanticOrigin.auxiliaryPaths(semanticResult.get, bases)
    val ordinals = mutable.HashMap.empty[Long, Int]
    writes.iterator.map: operation =>
      val ordinal = ordinals.getOrElse(operation.owner, 0)
      ordinals.update(operation.owner, ordinal + 1)
      val owner = modulePath(operation.owner)
      val metadata = operation.values.last.asInstanceOf[MemoryPortCapture]
      val port = memoryPortSnapshot(metadata, operation.owner)
      def operand(index: Int): String = collectionOperandPath(
        operation.values(index),
        operation.digitalRegion,
        operation.owner
      ).getOrElse(memoryFailure("memory write operand has no captured identity"))
      KernelMemoryWriteSnapshot(
        paths(operation.owner)(ordinal),
        owner,
        port.memory,
        port.domain,
        operand(1),
        operand(2),
        operand(3),
        operation.digitalRegion.map(region => expressionPath(region.reference)),
        operation.sourceOrdinal
      ) -> operation
    .toVector

  def operation(kind: String, values: Any*): Unit =
    captureOperation(kind, values.toVector, permitMemory = false)

  private def validateAssignmentTypes(values: Vector[Any]): Unit =
    val exactType =
      for
        target <- values.headOption.collect { case value: Expr[?] => value }
        source <- values.lift(1).collect { case value: Expr[?] => value }
        targetType <- CandidateRuntime.expressionDataType(target)
        sourceType <- CandidateRuntime.expressionDataType(source)
      yield DigitalEnumContract.sameType(
        CandidateRuntime.typeDescriptor(targetType),
        CandidateRuntime.typeDescriptor(sourceType)
      )
    if !exactType.contains(true) then
      collectionFailure("digital assignment requires exactly matching recursive types")

  private def captureOperation(kind: String, values: Vector[Any], permitMemory: Boolean): Unit =
    validateCollectionDependencies(values)
    val generatedConservativeConnection =
      kind == "terminal-connect" && activeGeneratedRegion.nonEmpty
    if generatedConservativeConnection then requireGeneratedConservativeConnection(values)
    val interfaceEffect = Set("interface-connect", "interface-drive", "interface-observe")(kind)
    val interfaceReferences = if interfaceEffect then
      values.collect:
        case endpoint: InterfaceEndpoint =>
          val root: InterfaceEndpoint = endpoint match
            case value: InterfacePort[?, ?] => DigitalInterfaceContract.root(value)
            case value: InterfaceArray[?, ?] => DigitalInterfaceContract.root(value)
          val reference = Option(declarationIds.get(root)).getOrElse(
            fail("NODAL-INTERFACE-054-003", "Interface effect requires an actual captured endpoint")
          )
          if reference.module != currentModule.handle then
            fail("NODAL-INTERFACE-054-003", "Interface effect belongs to another Module")
          requireGeneratedDeclarationScope(reference)
          valueRegion(reference).foreach: scope =>
            if !collectionStack.exists(_ eq scope) then
              fail("NODAL-INTERFACE-054-003", "Interface endpoint escaped its exact use region")
          reference
    else Vector.empty
    if kind == "interface-connect" && values.exists {
        case endpoint: InterfaceEndpoint =>
          ConstructionInterfaceLayout.leaves(endpoint.definition, endpoint.role, charge = closureCharge)
            .exists(_.protocol == "conservative")
        case _ => false
      }
    then
      if activeGeneratedRegion.nonEmpty || activeValueRegion.nonEmpty || activeArea.nonEmpty ||
        values.size != 2 ||
        interfaceReferences.size != 2
      then fail("NODAL-INTERFACE-058-ANALOG", "conservative forwarding requires ordinary whole arrays")
      val endpoints = values.collect { case value: InterfaceEndpoint => value }
      endpoints.zip(interfaceReferences).foreach: (endpoint, reference) =>
        val leaves = ConstructionInterfaceLayout.leaves(
          endpoint.definition, endpoint.role, charge = closureCharge
        )
        if !DigitalInterfaceContract.conservativeArrayProfile(endpoint, leaves, closureCharge) ||
          valueRegion(reference).nonEmpty || areas.exists(_.declarations.contains(reference)) ||
          records(reference.module).generatedRegions.exists(_.declarations.contains(reference))
        then fail("NODAL-INTERFACE-058-ANALOG", "conservative forwarding lost its direct fixed array")
        instanceInterfacePorts.get(reference) match
          case None =>
            endpoint match
              case array: InterfaceArray[?, ?] if array.exported && !array.boundary => ()
              case _ => fail("NODAL-INTERFACE-058-ANALOG", "forwarding needs an exported local array")
          case Some(binding) =>
            if binding.generated.nonEmpty || binding.scope.nonEmpty ||
              !binding.instance.exists(instance =>
                currentModule.instances.exists(_ eq instance) && instance.generatedScope.isEmpty &&
                  !areas.exists(_.instances.exists(_ eq instance)) &&
                  instance.child == binding.port.module) || valueRegion(binding.port).nonEmpty ||
              areas.exists(_.declarations.contains(binding.port)) ||
              records(binding.port.module).generatedRegions.exists(_.declarations.contains(binding.port))
            then fail("NODAL-INTERFACE-058-ANALOG", "forwarding needs an ordinary immediate child")
            records(binding.port.module).declarations(binding.port.index).value match
              case array: InterfaceArray[?, ?]
                  if array.exported && !array.boundary && array.origin.isEmpty => ()
              case _ => fail("NODAL-INTERFACE-058-ANALOG", "forwarding child is not directly exported")
      if interfaceReferences.count(instanceInterfacePorts.contains) != 1 then
        fail("NODAL-INTERFACE-058-ANALOG", "forwarding requires one local and one child array")
    val orderedInterface = interfaceEffect && (activeValueRegion.nonEmpty ||
      interfaceReferences.exists(reference =>
        instanceInterfacePorts.get(reference).exists(_.generated.nonEmpty)
      ))
    if orderedInterface then
      requireAreaFamilyScope("Interface effect", requireBody = false)
      if kind == "interface-drive" && interfaceReferences.exists(reference =>
          instanceInterfacePorts.get(reference).exists(_.generated.nonEmpty)
        )
      then
        fail(
          "NODAL-AREA-058-CAPABILITY",
          "selected Interface writes require exact lane-qualified driver proof"
        )
    val digitalAssignment = Set("assignment", "value-connect").contains(kind) &&
      analogStack.isEmpty && analogSemanticContext.isEmpty &&
      !AnalogProceduralConstruction.generatedProcedureActive &&
      values.headOption.collect { case expression: Expr[?] => expression }
        .flatMap(CandidateRuntime.expressionDataType)
        .map(CandidateRuntime.typeDescriptor).exists(dataType =>
          DigitalCombinationalContract.isValueTypeKind(dataType) ||
            (activeBehavioral.nonEmpty && Set("Integer", "Real")(dataType.kind))
        )
    if activeEventProcess.nonEmpty then
      if !digitalAssignment then
        eventFailure(s"hardware '$kind' effect is unsupported in an event process")
      captureEventAssignment(values)
    else if digitalAssignment && eventStorage.nonEmpty && values.nonEmpty then
      val target =
        digitalAssignmentRoot(values.head, currentModule.handle, allowRootRegister = true)
      if eventStorage.contains(target) then
        eventFailure("event output also has a continuous writer")
    val memoryWrite = permitMemory && kind == "memory-write" && values.size == 5 &&
      values.headOption.exists(
        _.isInstanceOf[Mem[?]]
      ) && values.last.isInstanceOf[MemoryPortCapture]
    if kind == "memory-write" && !memoryWrite then memoryFailure("malformed memory write effect")
    closeBehavioralChain()
    if activeBehavioral.nonEmpty && !digitalAssignment && !memoryWrite then
      behavioralFailure(s"hardware '$kind' effect is not permitted in a behavioral update")
    if !generatedConservativeConnection then
      requireNoCollectionEffect(
        s"hardware '$kind' effect",
        assignment = digitalAssignment || memoryWrite || orderedInterface
      )
    val loop = Option.when(digitalAssignment)(activeLoop).flatten
    val effectRegion =
      Option.when(digitalAssignment || memoryWrite || orderedInterface)(
        activeValueRegion.filter(_.permitsAssignment)
      ).flatten
    if !digitalAssignment then requireLoopEffect(s"hardware '$kind' effect")
    loop.foreach: region =>
      if !effectRegion.nonEmpty || region.loop.get.generated != activeGeneratedRegion
      then
        collectionFailure("digital assignments require their exact loop lexical scope")
      validateDigitalLoopDomain(region)
    if orderedInterface then
      values.foreach:
        case value: Expr[?] =>
          val _ = bindCollectionValue(value, effectRegion, currentModule.handle)
        case _ => ()
    if memoryWrite then
      if collectionStack.exists(region => !sequentialRegion(region)) then
        memoryFailure("memory write cannot escape the sequential effect profile")
      effectRegion.foreach: region =>
        values.slice(1, 4).foreach:
          case value: Expr[?] =>
            val _ = bindCollectionValue(value, Some(region), currentModule.handle)
          case _ => memoryFailure("memory write requires exact typed operands")
    effectRegion.filter(_ => digitalAssignment).foreach: region =>
      if values.size != 2 then collectionFailure("digital assignment requires two operands")
      val target = digitalAssignmentRoot(
        values.head,
        currentModule.handle,
        allowRootRegister = activeBehavioral.nonEmpty
      )
      if activeBehavioral.nonEmpty &&
        (!sequentialRegion(region) ||
          records(target.module).declarations(target.index).kind != KernelSignalKind.Register)
      then
        behavioralFailure(
          "behavioral updates may assign only owned whole registers in behavioral arms"
        )
      if privateFsmStorage(target) then
        FlatFsmRuntime.fail("private machine state cannot be assigned by source actions")
      if values.exists(DeferredResultType.pending) then
        val _ = requireDeferredValueScope(Some(region), currentModule.handle)
      else validateAssignmentTypes(values)
      values.foreach:
        case expression: Expr[?] =>
          val _ = bindCollectionValue(expression, Some(region), currentModule.handle)
        case _ => collectionFailure("digital loop assignment requires exact typed operands")
    if kind == "statement" then
      val effect = moduleStack.lastOption.flatMap(module =>
        values.headOption.map(renderAny(_, module.handle))
      ).getOrElse(kind)
      analogSemanticRecorder.unsupported(s"unrepresented source effect: $effect")
    val generatedAnalogContribution =
      kind == "analog-contribute" && generatedContinuousRegionActive
    val structuralTopology = Set(
      "node-connect",
      "node-array-connect",
      "instance-node-array",
      "deferred-node-array"
    ).contains(kind) || generatedConservativeConnection
    if !generatedAnalogContribution && !structuralTopology && effectRegion.isEmpty &&
      activeEventProcess.isEmpty
    then
      requireNoGeneratedEffect(s"generated '$kind' effect")
    if kind == "assignment" || kind == "analog-assignment" then
      analogSemanticContext.foreach: context =>
        if context.kind != AnalogEquationRuntime.RegionKind.Procedural then
          fail(
            "NODAL-ANALOG-133-007",
            "procedural assignment is illegal in a declarative analog region",
            Some(provisionalModulePath(context.module))
          )
    if kind == "analog-contribute" then
      val region = analogStack.lastOption.getOrElse(
        fail("NODAL-ANALOG-LIFECYCLE-002", "analog contribution is outside an analog region")
      )
      if values.size != 2 then
        fail("NODAL-ANALOG-LIFECYCLE-003", "analog contribution requires target and value")
      region.contributions += ((values(0), values(1)))
    if !generatedAnalogContribution then
      val owner = moduleStack.lastOption.map(_.handle).getOrElse(
        fail("NODAL-LIFECYCLE-018", "operation requires an active Module")
      )
      val sourceOrdinal = effectRegion match
        case Some(region) =>
          if region.assignmentCount >= 4098 then
            collectionFailure("loop body exceeds the 4098 assignment budget")
          val ordinal = region.assignmentCount
          region.assignmentCount += 1
          ordinal
        case None =>
          if Set("assignment", "value-connect", "memory-write")(kind) || orderedInterface then
            nextEffectOrdinal()
          else 0
      val capturedValues = values.toVector
      val preparedSource =
        if kind == "assignment" && digitalAssignment && effectRegion.isEmpty && activeGeneratedRegion.isEmpty &&
          activeEventProcess.isEmpty && constructionCapturePrefixAllowed(semanticOrigin) &&
          boundaryClosureOwner.exists(_.boundDomainAccountingCandidate)
        then
          boundaryClosureOwner.map: closure =>
            closure.guard:
              semanticOrigin.prepareOperationSource(owner, kind, capturedValues)
        else None
      val captured = Operation(
        owner,
        kind,
        capturedValues,
        if effectRegion.nonEmpty then None else activeGeneratedRegion,
        if digitalAssignment || memoryWrite || orderedInterface || (structuralTopology && activeGeneratedRegion.nonEmpty)
        then
          preparedSource match
            case Some(prepared) =>
              try semanticOrigin.captureSemanticSource(prepared)
              catch case failure: Throwable => boundaryClosureOwner.get.guard(throw failure)
            case None => semanticOrigin.captureSemanticSource()
        else None,
        effectRegion,
        sourceOrdinal,
        activeEventProcess
      )
      operations += captured
      moduleStack.lastOption.foreach(module =>
        // Admission distinguishes analog storage effects; source naming retains
        // the shared assignment sink-affinity semantics for both kinds.
        preparedSource match
          case Some(prepared) if prepared.canReuse =>
            consumePreparedOperationSource(prepared, module.handle, kind, captured.values)
          case _ =>
            semanticOrigin.captureOperation(
              module.handle,
              if kind == "analog-assignment" then "assignment" else kind,
              captured.values
            )
      )

  private def consumePreparedOperationSource(
      prepared: semanticOrigin.PreparedOperationSource,
      module: Long,
      kind: String,
      values: Vector[Any]
  ): Unit =
    boundaryClosureOwner.get.guard:
      semanticOrigin.consumeOperationSource(prepared, module, kind, values)

  private def provisionalModulePath(handle: Long): String =
    closureCharge(1L)
    val record = records(handle)
    closureCharge(record.className.length.toLong)
    record.parentAtConstruction match
      case None => record.className
      case Some(parentHandle) =>
        val parent = records(parentHandle)
        val ordinal = closureElements(parent.instances)
          .find(_.child == handle)
          .map(_.ordinal)
          .orElse:
            if !record.attached && closureElements(moduleStack).exists(_.handle == handle) then
              Some(parent.instances.size)
            else None
          .getOrElse(
            fail("NODAL-HIERARCHY-020", "child Module has no Instance record")
          )
        closurePathAppend(provisionalModulePath(parentHandle), s".${record.className}_$ordinal")

  private def modulePath(handle: Long): String =
    semanticResult.flatMap(_.modulePaths.get(handle)).getOrElse(provisionalModulePath(handle))

  private def domainName(reference: DomainRef): String =
    semanticResult
      .flatMap(_.domainNames.get(reference.module -> reference.index))
      .getOrElse(records(reference.module).domains(reference.index).name)

  private def domainPath(reference: DomainRef): String =
    semanticResult
      .flatMap(_.domainPaths.get(reference.module -> reference.index))
      .getOrElse(s"${modulePath(reference.module)}.${domainName(reference)}")

  private def declarationName(reference: DeclarationRef): String =
    semanticResult
      .flatMap(_.declarationNames.get(reference.module -> reference.index))
      .getOrElse:
        val declaration = records(reference.module).declarations(reference.index)
        declaration.explicitName.getOrElse(
          s"${declaration.kind.label}_${reference.index}"
        )

  private def declarationPath(reference: DeclarationRef): String =
    semanticResult
      .flatMap(_.declarationPaths.get(reference.module -> reference.index))
      .getOrElse(s"${modulePath(reference.module)}.${declarationName(reference)}")

  private def expressionPath(reference: ExpressionRef): String =
    closureCharge(1L)
    fsmResults.get(reference).map((record, role) =>
      closurePathAppend(expressionPath(record.reference), s".$role")
    ).orElse(
      fsmActionRegions.get(reference).flatMap(_.fsmAction).map(header =>
        closurePathAppend(expressionPath(header.machine), s".action${header.ordinal}")
      )
    ).orElse(enumSwitchArms.get(reference).flatMap(_.switchArm).map(header =>
      closurePathAppend(expressionPath(header.switch), s".arm${header.index}")
    )).orElse(collectionArguments.get(reference).map: (region, ordinal) =>
      if ordinal == 0 && region.structuralGenerate then
        expressionPath(region.loop.get.generated.get.induction)
      else closurePathAppend(expressionPath(region.reference), s".arg$ordinal")).orElse(
      structuralArms.get(
        reference
      ).flatMap(_.structuralArm).map(arm =>
        closurePathAppend(
          expressionPath(arm.conditional),
          if arm.whenTrue then ".then" else ".else"
        )
      )
    )
      .orElse(behavioralArms.get(reference).flatMap(_.behavioralArm).map(arm =>
        closurePathAppend(
          expressionPath(arm.conditional),
          if arm.whenTrue then ".then" else ".else"
        )
      ))
      .orElse(loopRegions.get(reference).flatMap(region =>
        region.loop.flatMap(_.generated).map(record =>
          if region.structuralGenerate then generatedRegionPath(record)
          else closurePathAppend(generatedRegionPath(record), ".digital_loop")
        )
      ))
      .orElse(semanticResult.flatMap(_.expressionPaths.get(reference.module -> reference.index)))
      .getOrElse(closurePathAppend(modulePath(reference.module), s".expr_${reference.index}"))

  private def instancePath(parent: Long, ordinal: Int): String =
    semanticResult
      .flatMap(_.instancePaths.get(parent -> ordinal))
      .getOrElse(s"${modulePath(parent)}.instance_$ordinal")

  def pathOf(value: Any): Option[String] =
    closureCharge(1L)
    value match
      case view: TerminalView[?, ?] => pathOf(view.terminal)
      case family: GeneratedRegionRecord => Some(generatedRegionPath(family))
      case argument: GeneratedFamilyArgument =>
        Some(closurePathAppend(
          expressionPath(argument.region.reference),
          s".arg${argument.position}"
        ))
      case node: Node[?] if node.endpointReference.nonEmpty =>
        Option(expressionIds.get(node)).orElse(node.endpointReference).flatMap(
          shapeIndices.get
        ).map(conservativeEndpointPath)
      case reference: AnyRef =>
        Option(declarationIds.get(reference))
          .map(declarationPath)
          .orElse(Option(expressionIds.get(reference)).map(expressionPath))
          .orElse(Option(domainIds.get(reference)).map(domainPath))
          .orElse(Option(moduleIds.get(reference)).map(handle => modulePath(handle.longValue)))
      case _ => None
  private def selectedPath(value: AnyRef): Option[String] =
    if declarationIds.containsKey(value) || expressionIds.containsKey(value) ||
      domainIds.containsKey(value) || moduleIds.containsKey(value)
    then pathOf(value)
    else None

  private def selectedOperand(value: Any): KernelSelectedOperand = value match
    case text: String => KernelSelectedOperand.Literal("string", text)
    case value: Int => KernelSelectedOperand.Literal("integer", value.toString)
    case value: Long => KernelSelectedOperand.Literal("long", value.toString)
    case value: Double =>
      KernelSelectedOperand.Literal("real", java.lang.Double.toString(value))
    case value: Boolean => KernelSelectedOperand.Literal("boolean", value.toString)
    case reference: AnyRef if selectedPath(reference).nonEmpty =>
      KernelSelectedOperand.Reference(selectedPath(reference).get)
    case _: Expr[?] | _: ClockDomain | _: Module | _: Node[?] | _: NodeArray[?] =>
      fail(
        "NODAL-SELECTION-050-001",
        "selected expression references a foreign construction handle"
      )
    case _ => KernelSelectedOperand.Opaque("unsupported")

  def operationKinds: Vector[String] = operations.iterator.map(_.kind).toSet.toVector.sorted

  /** Called only after finish has resolved final names and structural aliases. */
  def selectedReference(value: AnyRef): KernelSelectedReference =
    if Option(expressionIds.get(value)).exists(expressionRegions.contains) then
      collectionFailure(
        "collection body value cannot escape through a selected construction result"
      )
    val path = selectedPath(value).getOrElse(
      fail("NODAL-SELECTION-050-001", "selected handle is outside this construction transaction")
    )
    Option(declarationIds.get(value)).map: reference =>
      val declaration = records(reference.module).declarations(reference.index)
      KernelSelectedReference(
        path,
        modulePath(reference.module),
        declaration.kind.label,
        declaration.dataType.map(renderType(_, reference.module))
      )
    .orElse(Option(domainIds.get(value)).map: reference =>
      KernelSelectedReference(
        path,
        modulePath(reference.module),
        "domain",
        None
      )).orElse(Option(expressionIds.get(value)).map: reference =>
      val expression = expressionValues.get(reference)
      KernelSelectedReference(
        path,
        modulePath(reference.module),
        "expression",
        expression.flatMap(CandidateRuntime.expressionDataType).map(renderType(
          _,
          reference.module
        )),
        expression.flatMap(_.operation),
        expression.toVector.flatMap(_.operands).map(selectedOperand)
      )).orElse(Option(moduleIds.get(value)).map: reference =>
      KernelSelectedReference(path, modulePath(reference.longValue), "module", None)).getOrElse(
      fail("NODAL-SELECTION-050-001", "selected handle has no canonical reference")
    )

  private def stableClassName(value: AnyRef): String =
    val name = value.getClass.getSimpleName.stripSuffix("$")
    if name.isEmpty then value.getClass.getName.split("\\.").last.stripSuffix("$") else name

  private def renderAny(value: Any, owner: Long): String = closureDescriptorVisit:
    value match
      case candidate if Option(candidate).isEmpty => "null"
      case text: String => text
      case boolean: Boolean => boolean.toString
      case integer: Int => integer.toString
      case long: Long => long.toString
      case double: Double => java.lang.Double.toString(double)
      case big: BigInt => big.toString
      case expression: KernelExpr[?]
          if requireConstructionPayload(expression) && expression.literal.nonEmpty =>
        expression.literal.map(_.value).getOrElse("")
      case discipline: NamedDiscipline => discipline.name
      case Electrical => "electrical"
      case mode: DriveMode.Value[?] => mode.name
      case placement: InoutPlacement => placement.toString
      case profile: ResolutionProfile => profile.toString
      case policy: ReadUnderWrite => policy.toString
      case ordering: MemoryOrdering => ordering.toString
      case dataType: DataType[?] => renderType(dataType, owner)
      case field: StructField[?] => s"${field.name}:${renderType(field.dataType, owner)}"
      case option: Option[?] => option.map(renderAny(_, owner)).getOrElse("none")
      case sequence: Seq[?] =>
        closureElements(sequence).map(renderAny(_, owner)).mkString("[", ",", "]")
      case set: Set[?] =>
        closureElements(set).map(renderAny(_, owner)).toVector.sortWith((left, right) =>
          closureCharge(1L)
          left.compareTo(right) < 0
        ).mkString("[", ",", "]")
      case reference: AnyRef =>
        Option(declarationIds.get(reference)).map(declarationPath)
          .orElse(
            Option(expressionIds.get(reference)).map(expressionPath)
          )
          .orElse(Option(domainIds.get(reference)).map(domainPath))
          .getOrElse(stableClassName(reference))
      case other => other.toString

  private def renderType(dataType: DataType[?], owner: Long): String = closureDescriptorVisit:
    val descriptor = CandidateRuntime.typeDescriptor(dataType)
    descriptor.kind match
      case "Enum" =>
        descriptor.arguments match
          case Vector(abi: ResolvedEnumAbi) => s"Enum(${abi.identity},${abi.width},${abi.hash})"
          case _ => fail("NODAL-ENUM-054-020", "enum type has no resolved ABI")
      case "Struct" =>
        val name = descriptor.arguments.headOption.collect { case value: String =>
          value
        }.getOrElse("Struct")
        val fields = descriptor.arguments.lift(1).toVector.flatMap:
          case values: Seq[?] => closureElements(values).collect:
              case field: StructField[?] =>
                s"${field.name}:${renderType(field.dataType, owner)}"
          case _ => Vector.empty
        s"Struct($name{${fields.mkString(",")}})"
      case "Vec" =>
        val element = descriptor.arguments.headOption.collect:
          case value: DataType[?] => renderType(value, owner)
        val dimensions = descriptor.arguments.lift(1).toVector.flatMap:
          case values: Seq[?] => closureElements(values).map(renderShapeDimension(_, owner))
          case _ => Vector.empty
        s"Vec(${element.getOrElse("unknown")};${dimensions.mkString("x")})"
      case kind @ ("Bits" | "UInt" | "SInt") =>
        if descriptor.arguments.size != 1 then
          fail("NODAL-DIGITAL-054-001", "digital scalar type requires exactly one width")
        s"$kind(${renderShapeDimension(descriptor.arguments.head, owner)})"
      case kind if descriptor.arguments.nonEmpty =>
        s"$kind(${closureElements(descriptor.arguments).map(renderAny(_, owner)).mkString(",")})"
      case kind => kind

  private def renderShapeDimension(value: Any, owner: Long): String =
    closureCharge(1L)
    value match
      case literal: Int => literal.toString
      case parameter: Param[?] =>
        Option(declarationIds.get(parameter)) match
          case Some(reference) if reference.module == owner => declarationName(reference)
          case Some(reference) =>
            fail(
              "NODAL-SHAPE-043-001",
              "symbolic Vec dimension escapes its owning Module",
              Some(declarationPath(reference))
            )
          case None => fail("NODAL-SHAPE-043-001", "symbolic Vec dimension has no identity")
      case expression: KernelExpr[?]
          if requireConstructionPayload(
            expression
          ) && expression.literal.exists(_.kind == "integer") =>
        expression.literal.map(_.value).getOrElse("")
      case expression: KernelExpr[?] =>
        Option(expressionIds.get(expression)) match
          case Some(reference) if reference.module == owner => expressionPath(reference)
          case Some(reference) =>
            fail(
              "NODAL-SHAPE-043-001",
              "compound Vec dimension escapes its owning Module",
              Some(expressionPath(reference))
            )
          case None =>
            fail("NODAL-SHAPE-043-001", "compound Vec dimension has no identity")
      case _ =>
        fail("NODAL-SHAPE-043-001", "Vec dimension has no canonical static spelling")

  private def resolveDomains(): Map[DomainRef, String] =
    val resolved = mutable.LinkedHashMap.empty[DomainRef, String]
    boundDomainCharge(selectedRequirementBindings.size.toLong + records.size.toLong * 2L)
    selectedRequirementBindings.clear()
    val roots = records.values.filter(_.parentAtConstruction.isEmpty).toVector
    if roots.size != 1 then fail("NODAL-ROOT-016", s"expected one root Module, found ${roots.size}")
    val pending = mutable.ArrayDeque((roots.head.handle, 0))
    val visited = mutable.HashSet.empty[Long]
    var edges = 0L
    while pending.nonEmpty do
      boundDomainCharge()
      val (handle, depth) = pending.removeHead()
      if depth > 128 || visited.size >= 65536 || !visited.add(handle) then
        fail("NODAL-HIERARCHY-057-002", "recursive or excessive concrete hierarchy")
      val module = records(handle)
      boundDomainCharge(module.domains.size.toLong)
      module.domains.filter(_.kind != KernelDomainKind.Required).foreach: domain =>
        boundDomainCharge()
        resolved.update(domain.reference, domainPath(domain.reference))
      if module.parentAtConstruction.isEmpty then
        boundDomainCharge(module.domains.size.toLong)
        module.domains.filter(_.kind == KernelDomainKind.Required).foreach: requirement =>
          fail(
            "NODAL-ROOT-DOMAIN-016",
            s"top-level domain requirement '${requirement.name}' is unbound",
            Some(modulePath(handle))
          )
      module.instances.foreach: instance =>
        boundDomainCharge()
        edges += 1
        if edges > 262144 then
          fail("NODAL-HIERARCHY-057-002", "hierarchy binding edge budget exceeded")
        val child = records(instance.child)
        boundDomainCharge(child.domains.size.toLong * 2L)
        val requirements = child.domains.filter(_.kind == KernelDomainKind.Required).toVector
        requirements.foreach: requirement =>
          boundDomainCharge(1L + instance.namedBindings.size.toLong)
          val named = instance.namedBindings.collectFirst:
            case (selected, actual) if selected eq requirement.domain => actual -> "named"
          val binding = named
            .orElse(if requirements.size == 1 then instance.defaultBinding.map(_ -> "default")
            else None)
            .orElse(if requirements.size == 1 then instance.lexicalDomain.map(_ -> "inherited")
            else None)
            .orElse(if requirements.size == 1 && module.domains.size == 1 then
              module.domains.headOption.map(_.domain -> "inherited")
            else None)
          binding match
            case Some((actual, origin)) =>
              val reference = domainRef(actual)
              if reference.module != handle then
                fail(
                  "NODAL-BINDING-020",
                  "actual domain must be an exact parent-owned domain or requirement"
                )
              val physical = resolved.getOrElse(
                reference,
                fail("NODAL-BINDING-020", "bound domain is not resolved in its parent occurrence")
              )
              boundDomainCharge(2L)
              selectedRequirementBindings(requirement.reference) =
                (reference, origin, handle, instance.ordinal)
              resolved.update(requirement.reference, physical)
            case None =>
              fail(
                "NODAL-CHILD-DOMAIN-016",
                s"child domain requirement '${requirement.name}' is unbound",
                Some(modulePath(instance.child))
              )
        pending.append((instance.child, depth + 1))
    if visited.size != records.size then
      fail("NODAL-HIERARCHY-057-002", "construction contains an unreachable module occurrence")
    val byPath = records.valuesIterator.flatMap(module =>
      boundDomainCharge(1L + module.domains.size.toLong)
      module.domains
    ).map(domain =>
      boundDomainCharge()
      domainPath(domain.reference) -> domain
    ).toMap
    boundDomainCharge(selectedRequirementBindings.size.toLong * 2L)
    selectedRequirementBindings.keysIterator.map(reference => resolved(reference)).toSet.foreach:
      path =>
        boundDomainCharge()
        val actual = byPath(path).domain
        Option(effectiveResets.get(actual)).foreach: reset =>
          boundDomainCharge()
          if !clockResetSafe(reset, actual) then
            fail(
              "NODAL-RDC-057-001",
              "instance actual reset does not release safely for its exact receiving clock",
              Some(path)
            )
    boundDomainCharge(resolved.size.toLong)
    occurrenceDomains = resolved.toMap
    occurrenceDomains

  /** An outer lexical domain supplies the instance binding, never a foreign child symbol. */
  private def localDomainCandidate(
      owner: Long,
      candidate: Option[ClockDomain],
      path: => String
  ): Option[ClockDomain] =
    candidate.map: domain =>
      if domainRef(domain).module == owner then domain
      else
        closureCharge(records(owner).domains.size.toLong)
        val requirements = records(owner).domains.filter(_.kind == KernelDomainKind.Required)
        if requirements.size != 1 then
          fail(
            "NODAL-DECL-DOMAIN-016",
            "inherited declaration requires one exact child-local requirement",
            Some(path)
          )
        requirements.head.domain

  private def declarationCandidate(declaration: DeclarationRecord): Option[ClockDomain] =
    publicationProbe:
      localDomainCandidate(
        declaration.reference.module,
        instanceInterfaceDomain(declaration.reference).map(reference =>
          records(reference.module).domains(reference.index).domain
        ).orElse(declaration.domainCandidate),
        declarationPath(declaration.reference)
      )

  private def localDeclarationDomain(
      declaration: DeclarationRecord,
      resolved: Map[DomainRef, String]
  ): Option[String] = publicationProbe:
    declarationCandidate(declaration).map(domain => domainPath(domainRef(domain))).orElse:
      declarationDomain(declaration, resolved).map: actual =>
        val local = records(declaration.reference.module).domains.filter(domain =>
          publicationProbe {
            val path = domainPath(domain.reference)
            publicationCharge(path.length.toLong + actual.length.toLong)
            val matches = path == actual || resolved.get(domain.reference).exists { value =>
              publicationCharge(value.length.toLong + actual.length.toLong)
              value == actual
            }
            if matches then publicationCharge(1L)
            matches
          }
        )
        if local.size != 1 then
          fail("NODAL-DECL-DOMAIN-016", "declaration requires one exact local domain identity")
        domainPath(local.head.reference)

  private def declarationDomain(
      declaration: DeclarationRecord,
      resolved: Map[DomainRef, String]
  ): Option[String] = publicationProbe(declarationCandidate(declaration)) match
    case Some(domain) =>
      resolved.get(domainRef(domain)).orElse(
        fail(
          "NODAL-DECL-DOMAIN-016",
          s"${declaration.kind.label} has an unresolved domain",
          Some(declarationPath(declaration.reference))
        )
      )
    case None if declaration.kind == KernelSignalKind.Register =>
      val module = records(declaration.reference.module)
      val choices =
        module.domains.flatMap(domain => resolved.get(domain.reference)).distinct.toVector
      choices match
        case Vector(single) => Some(single)
        case Vector() =>
          fail(
            "NODAL-STATE-DOMAIN-016",
            "state has no lexical or default domain",
            Some(declarationPath(declaration.reference))
          )
        case _ =>
          fail(
            "NODAL-MULTI-DOMAIN-016",
            "state in a multi-domain Module requires a lexical ClockDomain",
            Some(declarationPath(declaration.reference))
          )
    case None if declaration.dataType.exists(isBoundaryCapability) =>
      val actual = records(declaration.reference.module).domains.filter(domain =>
        publicationProbe {
          publicationCharge(domain.domain.clock.size.toLong + 1L)
          val controls = domain.domain.clock.toVector :+ domain.domain.reset
          val matches = controls.exists(value =>
            publicationProbe {
              capabilitySourceRoot(value).contains(declaration.reference)
            }
          )
          if matches then publicationCharge(1L)
          matches
        }
      )
      if actual.size == 1 then resolved.get(actual.head.reference)
      else if declaration.dataType.exists(value =>
          boundaryElementKind(value) == "Reset"
        )
      then
        actual.headOption.flatMap(value => resolved.get(value.reference))
      else None
    case None => None

  private def attribute(
      declaration: DeclarationRecord,
      name: String,
      owner: Long,
      default: String
  ): String = declaration.attributes.find(_._1 == name)
    .map(value => renderAny(value._2, owner))
    .getOrElse(default)

  private def resolvedNets(): Vector[KernelResolvedNetSnapshot] =
    val declarations = publicationElements(records.valuesIterator).flatMap(module =>
      publicationElements(
        module.declarations.iterator
      ).filter(_.kind == KernelSignalKind.DigitalInout)
        .map(module -> _)
    ).toVector
    // A per-snapshot reporting index, not another physical-net registry.
    val related = new IdentityHashMap[AnyRef, mutable.ArrayBuffer[String]]()
    declarations.foreach: (_, declaration) =>
      if related.put(declaration.value, mutable.ArrayBuffer.empty[String]) != null then
        fail("NODAL-INTERFACE-054-007", "resolved declaration identity is duplicated")
    val lastOperation = new IdentityHashMap[AnyRef, java.lang.Integer]()
    publicationElements(operations.iterator).zipWithIndex.foreach: (operation, ordinal) =>
      publicationElements(operation.values).foreach:
        case reference: AnyRef =>
          publicationCharge(1L)
          val kinds = related.get(reference)
          if kinds != null && !Option(lastOperation.get(reference)).contains(ordinal) then
            kinds += operation.kind
            lastOperation.put(reference, ordinal)
        case _ => ()
    declarations.map: (module, declaration) =>
      KernelResolvedNetSnapshot(
        declarationPath(declaration.reference),
        declaration.dataType.map(renderType(_, module.handle)).getOrElse("Bits"),
        attribute(declaration, "mode", module.handle, "unknown"),
        attribute(declaration, "placement", module.handle, ""),
        attribute(declaration, "profile", module.handle, ""),
        related.get(declaration.value).toVector,
        instanceDigitalInouts.get(declaration.reference).map: (instance, port) =>
          KernelInterfaceBoundarySnapshot(
            instancePath(module.handle, instance.ordinal),
            declarationPath(port)
          ),
        resolvedMemberSnapshot(declaration)
      )
    .sortBy(_.path)

  private def conservativeArrayBindingRecords
      : Vector[(KernelConservativeArrayBindingSnapshot, ConservativeArrayBindingRecord)] =
    val ordinals = mutable.HashMap.empty[Long, Int]
    publicationOperationVector.collect:
      case operation if operation.kind == "node-array-connect" =>
        val binding = operation.values match
          case Vector(value: ConservativeArrayBindingRecord) => value
          case _ => fail("NODAL-SHAPE-043-007", "array relation lost its typed captured payload")
        val ordinal = ordinals.getOrElse(operation.owner, 0)
        ordinals.update(operation.owner, ordinal + 1)
        val owner = modulePath(operation.owner)
        KernelConservativeArrayBindingSnapshot(
          s"$owner.array_binding_$ordinal",
          owner,
          pathOfReference(binding.left),
          pathOfReference(binding.right),
          generatedScopeSnapshot(operation.generatedScope)
        ) -> binding

  private def instanceArrayRootSnapshots(): Vector[KernelInstanceArrayRootSnapshot] =
    publicationOperationVector.flatMap: operation =>
      publicationCharge(operation.values.size.toLong)
      operation.values.collect:
        case root: InstanceArrayRootRecord =>
          KernelInstanceArrayRootSnapshot(
            pathOfReference(root.reference),
            modulePath(operation.owner),
            instancePath(operation.owner, root.instanceOrdinal),
            declarationPath(root.port),
            root.dimensions.getOrElse(fail(
              "NODAL-SHAPE-043-007",
              "instance array shape remains unresolved"
            ))
              .map(renderShapeDimension(_, operation.owner)),
            generatedScopeSnapshot(operation.generatedScope)
          )

  private def markInstanceShapeDependencies(instance: InstanceRecord, roots: Iterable[Any]): Unit =
    val visited = new IdentityHashMap[AnyRef, java.lang.Boolean]()
    def visit(value: Any): Unit = value match
      case parameter: Param[?]
          if Option(visited.put(parameter, java.lang.Boolean.TRUE)).isEmpty =>
        val reference = Option(declarationIds.get(parameter)).getOrElse(
          fail("NODAL-SHAPE-043-007", "instance shape dependency has no owned declaration")
        )
        markStructuralParameterEffect(reference, "shape")
        markStructuralParameterEffect(reference, "rank")
        if reference.module == instance.child then
          val overrideValue = instance.parameterOverrides.collectFirst:
            case (target: AnyRef, actual)
                if Option(declarationIds.get(target)).contains(reference) => actual
          visit(overrideValue.getOrElse(parameter.default))
      case expression: KernelExpr[?]
          if Option(visited.put(expression, java.lang.Boolean.TRUE)).isEmpty =>
        if !constructionPayloadAvailable(expression) then
          fail(
            "NODAL-HIERARCHY-058-CONSTRUCTION",
            "pending construction cannot supply a static shape"
          )
        expression.operands.foreach(visit)
      case _ => ()
    roots.foreach(visit)

  private def markConservativeBindingDependencies(): Unit =
    operations.foreach: operation =>
      boundDomainCharge(1L + operation.values.size.toLong)
      operation.values.foreach:
        case root: InstanceArrayRootRecord =>
          markInstanceShapeDependencies(
            records(operation.owner).instances(root.instanceOrdinal),
            records(root.port.module).declarations(root.port.index).conservativeDimensions
          )
        case _ => ()
    instanceInterfacePorts.foreach: (_, binding) =>
      boundDomainCharge()
      val port = binding.port
      records(port.module).declarations(port.index).value match
        case array: InterfaceArray[?, ?] =>
          binding.instance.foreach(markInstanceShapeDependencies(_, Vector(array.formalCount)))
        case _ => ()

  private def prepareInterfaceFormalTypes(): Vector[DataType[?]] =
    // Type validation records structural effects on parameter declarations. Capture
    // endpoint identities first, before those existing declaration entries mutate.
    val endpoints = records.valuesIterator.flatMap: module =>
      boundDomainCharge(1L + module.declarations.size.toLong)
      module.declarations.iterator.collect:
        case declaration
            if declaration.value.isInstanceOf[InterfaceEndpoint] &&
              !declaration.value.asInstanceOf[InterfaceEndpoint].boundary =>
          module.handle -> declaration.value.asInstanceOf[InterfaceEndpoint]
    .toVector
    endpoints.flatMap: (owner, endpoint) =>
      val leaves = ConstructionInterfaceLayout.leaves(
        endpoint.definition,
        endpoint.role,
        charge = closureCharge
      )
      leaves.foreach: leaf =>
        withInterfaceDefinitionScope(leaf.definition, owner):
          leaf.dataType.foreach(validateShapeType(_, owner, cardinality = true))
      leaves.flatMap(_.dataType)

  private def materializeInstanceInterfaceDimensions(
      queries: StructuralParameterBinding.InstanceQueries,
      snapshot: ConstructionSnapshot
  ): Unit =
    closureCharge(instanceInterfacePorts.size.toLong)
    val contexts = new InstanceTypeContext(snapshot, queries)
    instanceInterfacePorts.iterator.filter((reference, _) =>
      closureAccepts("interface", reference)
    ).foreach: (reference, binding) =>
      val port = binding.port
      val owner = records(reference.module)
      val declaration = owner.declarations(reference.index)
      val endpoint = declaration.value.asInstanceOf[InterfaceEndpoint]
      val formals = ConstructionInterfaceLayout.leaves(
        endpoint.definition,
        endpoint.role,
        charge = closureCharge
      )
        .flatMap(_.dataType).map(CandidateRuntime.typeDescriptor).distinct
      val previousModules = moduleStack.toVector
      val previousCollections = collectionStack.toVector
      val previousGenerations = generationStack.toVector
      installValueScope(reference.module, binding.scope)
      try
        val query = binding.instance.map(instance =>
          contexts.query(reference.module, instance, binding.generated, binding.scope)
        )
        val directContext = Option.when(binding.instance.isEmpty && binding.generated.nonEmpty)(
          contexts.selected(reference.module, binding.generated.get, binding.scope)
        )
        val actuals = binding.instance.map(instance =>
          materializeInstanceTypeDescriptors(
            reference.module,
            instance,
            port,
            formals,
            queries,
            query
          )
        ).getOrElse(directContext.map: selected =>
          val originalQueries =
            ConstructionInterfaceLayout.leaves(
              endpoint.definition,
              endpoint.role,
              charge = closureCharge
            )
              .flatMap(leaf =>
                leaf.dataType.map(value =>
                  CandidateRuntime.typeDescriptor(value) ->
                    contexts.definitionQuery(port.module, leaf.definition)
                )
              ).toMap
          materializeTypeDescriptors(
            reference.module,
            formals,
            (formal, value) =>
              val original = originalQueries(formal)
              val id =
                original.resolve(modulePath(port.module), renderShapeDimension(value, port.module))
              original.positive(id)
              val actual = selected.importValues(original, Vector(id)).head
              selected.query.positive(actual)
              actual
            ,
            values => selected.query.exportDimensions(modulePath(reference.module), values)
          )
        .getOrElse(formals)).map(CandidateRuntime.dataTypeFromDescriptor)
        actuals.foreach(validateShapeType(_, reference.module, cardinality = true))
        instanceInterfaceTypes(reference) = formals.zip(actuals).toMap
        if declaration.kind == KernelSignalKind.InterfaceArray then
          val original =
            records(port.module).declarations(port.index).value.asInstanceOf[InterfaceArray[?, ?]]
          val dimensions = query.map: query =>
            val token = renderShapeDimension(original.formalCount, port.module)
            val id = query.resolve(modulePath(port.module), token)
            query.positive(id)
            materializeStructuralDimensions(query.exportDimensions(
              modulePath(reference.module),
              Vector(id)
            ))
          .getOrElse(directContext.map: selected =>
            val originalScope = valueRegion(port).map(value => expressionPath(value.reference))
            val originalQuery = originalScope.map(
              contexts.lexicalQuery
            ).getOrElse(new StructuralParameterBinding.Query(snapshot, meter = closureMeter))
            val id = originalQuery.resolve(
              modulePath(port.module),
              renderShapeDimension(original.formalCount, port.module)
            )
            originalQuery.positive(id)
            val actual = selected.importValues(originalQuery, Vector(id)).head
            selected.query.positive(actual)
            materializeStructuralDimensions(selected.query.exportDimensions(
              modulePath(reference.module),
              Vector(actual)
            ))
          .getOrElse(Vector(original.formalCount)))
          instanceInterfaceDimensions(reference) = dimensions
          owner.declarations.update(
            reference.index,
            declaration.copy(attributes = declaration.attributes :+ ("count" -> dimensions.head))
          )
      finally
        moduleStack.clear(); moduleStack ++= previousModules
        collectionStack.clear(); collectionStack ++= previousCollections
        generationStack.clear(); generationStack ++= previousGenerations

  private def materializeStructuralDimensions(
      graph: StructuralParameterBinding.ResolvedDimensions
  ): Vector[Dimension] =
    val closure = boundaryClosureOwner.getOrElse(
      fail("NODAL-HIERARCHY-058-CLOSURE", "structural materialization requires its closure owner")
    )
    closure.meter.checkLive()
    closure.meter.consumeGraph(graph)
    val ownedValues = closure.structuralValues.getOrElse:
      closureCharge(
        records.valuesIterator.map(_.declarations.size.toLong).sum + expressionValues.size.toLong
      )
      val values = records.valuesIterator.flatMap(_.declarations.iterator)
        .map(value => declarationPath(value.reference) -> value.value).toMap ++
        expressionValues.iterator.map((reference, value) =>
          expressionPath(reference) -> value
        ).toMap
      closure.structuralValues = Some(values)
      values
    val values = mutable.HashMap.empty[Int, Dimension]
    def asExpression(value: Dimension): Expr[Integer] = value match
      case literal: Int => makeLiteral(literal.toLong)
      case expression: Expr[Integer] => expression
    def makeLiteral(value: Long): Expr[Integer] =
      val expression = new KernelExpr[Integer](
        Vector.empty,
        Some(KernelTypeDescriptor("Integer")),
        Some(KernelLiteral("integer", value.toString, KernelTypeDescriptor("Integer"))),
        Some("integer")
      )
      captureExpression(expression)
      expression
    def materialize(id: Int): Dimension =
      closure.meter.checkLive()
      closureCharge(1L)
      values.getOrElseUpdate(
        id,
        graph.existing.get(id).flatMap(path =>
          ownedValues.get(path)
        ) match
          case Some(value: Expr[?]) => value.asInstanceOf[Expr[Integer]]
          case _ =>
            val node = graph.nodes(id)
            node.literal match
              case Some(value) => if value.isValidInt then value.toInt else makeLiteral(value)
              case None => node.parameter match
                  case Some(path) => ownedValues.getOrElse(
                      path,
                      fail("NODAL-SHAPE-043-007", "substituted parent parameter is missing")
                    )
                      .asInstanceOf[Expr[Integer]]
                  case None =>
                    val expression = new KernelExpr[Integer](
                      node.operands.map(value => asExpression(materialize(value))),
                      Some(KernelTypeDescriptor("Integer")),
                      operation = node.operation
                    )
                    captureExpression(expression)
                    expression
      )
    graph.dimensions.map(materialize)

  private def materializeConservativeIntents(base: ConstructionSnapshot): Unit =
    closureCharge(operations.size.toLong)
    val captured = operations.toVector.filter(value => closureAccepts("conservative", value))
    def inScope[A](operation: Operation)(body: => A): A =
      val previousModules = moduleStack.toVector
      val previousGeneration = generationStack.toVector
      moduleStack.clear(); moduleStack += records(operation.owner)
      generationStack.clear()
      def ancestors(region: GeneratedRegionRecord): Vector[GeneratedRegionRecord] =
        region.parentOrdinal.map(index =>
          ancestors(records(operation.owner).generatedRegions(index))
        )
          .getOrElse(Vector.empty) :+ region
      operation.generatedScope.foreach(region => generationStack ++= ancestors(region))
      try body
      finally
        moduleStack.clear(); moduleStack ++= previousModules
        generationStack.clear(); generationStack ++= previousGeneration
    captured.foreach: operation =>
      operation.values.foreach:
        case root: InstanceArrayRootRecord => inScope(operation):
            val graph = try
              boundaryClosureOwner.filter(_.incremental) match
                case Some(closure) => StructuralParameterBinding.resolveDimensions(
                    closure.queries,
                    modulePath(operation.owner),
                    instancePath(operation.owner, root.instanceOrdinal),
                    declarationPath(root.port)
                  )
                case None => StructuralParameterBinding.resolveDimensions(
                    base,
                    modulePath(operation.owner),
                    instancePath(operation.owner, root.instanceOrdinal),
                    declarationPath(root.port)
                  )
            catch
              case error: IllegalArgumentException => fail("NODAL-SHAPE-043-007", error.getMessage)
            root.dimensions = Some(materializeStructuralDimensions(graph))
        case deferred: DeferredNodeArrayRecord => inScope(operation):
            deferred.projection match
              case Some(projection) => materializeNodeArrayProjection(
                  deferred.value.asInstanceOf[NodeArray[?]],
                  projection,
                  Some(deferred.reference)
                )
              case None => registerNodeSelection(
                  deferred.value.asInstanceOf[Node[?]],
                  deferred.input,
                  deferred.indices,
                  Some(deferred.reference)
                )
        case _ => ()

  private def finalizeConservativeArrayBindings(snapshot: ConstructionSnapshot): Unit =
    def merge[K, V](left: Map[K, V], right: Iterable[(K, V)]): Map[K, V] =
      if !publicationWorkActive then left ++ right
      else left ++ publicationElements(right)
    val declarations = snapshot.modules.flatMap(module =>
      publicationProbe {
        publicationCharge(module.declarations.size.toLong)
        module.declarations
      }
    )
    val declarationPairs = publicationVector(declarations).map(value =>
      publicationProbe(value.path -> value.conservativeDimensions)
    )
    val declarationMap =
      if !publicationWorkActive then declarationPairs.toMap
      else publicationElements(declarationPairs).toMap
    val dimensions = merge(
      merge(
        merge(
          declarationMap,
          publicationVector(snapshot.instanceArrayRoots).map(value =>
            publicationProbe(value.path -> value.dimensions)
          )
        ),
        publicationVector(snapshot.shapeViews).map(value =>
          publicationProbe(value.path -> value.dimensions)
        )
      ),
      publicationVector(snapshot.shapeIndices).map(value =>
        publicationProbe(value.path -> value.resultDimensions)
      )
    )
    try
      publicationElements(
        snapshot.instanceArrayRoots
      ).foreach(StructuralParameterBinding.validateRoot(snapshot, _))
      publicationElements(snapshot.conservativeArrayBindings).foreach: binding =>
        StructuralParameterBinding.validateRelation(
          snapshot,
          binding,
          dimensions.getOrElse(binding.left, Vector.empty),
          dimensions.getOrElse(binding.right, Vector.empty)
        )
    catch case error: IllegalArgumentException => fail("NODAL-SHAPE-043-007", error.getMessage)
    publicationElements(snapshot.modules).foreach: module =>
      publicationElements(module.instances).foreach: instance =>
        val child = snapshot.modules.find(_.path == instance.childModule).get
        child.declarations.filter(value =>
          conservativePortKinds.exists(_.label == value.kind) &&
            value.conservativeDimensions.nonEmpty
        ).foreach: port =>
          if !snapshot.instanceArrayRoots.exists(root =>
              root.instance == instance.path && root.port == port.path
            )
          then
            fail(
              "NODAL-HIERARCHY-043",
              "child array port requires complete incident coverage",
              Some(port.path)
            )

  private def topologyRecords(): Vector[(KernelTopologyEdge, Operation)] =
    val ordinals = mutable.HashMap.empty[Long, Int]
    val edges = publicationOperationVector.flatMap: operation =>
      if Set("node-connect", "terminal-connect", "inout-pass-through").contains(operation.kind) &&
        operation.values.size >= 2
      then
        if operation.kind == "terminal-connect" then
          operation.values.foreach:
            case view: InterfaceTerminalView[?, ?] if view.permission != "connect" =>
              fail("NODAL-INTERFACE-058-ANALOG", "connection requires the actual connect view")
            case _ => ()
        (pathOf(operation.values(0)), pathOf(operation.values(1))) match
          case (Some(left), Some(right)) =>
            val (first, second) =
              if operation.kind == "node-connect" && right < left then right -> left
              else left -> right
            val ordinal = ordinals.getOrElse(operation.owner, 0)
            ordinals.update(operation.owner, ordinal + 1)
            val selectedOperand = operation.values.exists:
              case view: InterfaceTerminalView[?, ?] =>
                val root = DigitalInterfaceContract.root(view.endpoint)
                Option(declarationIds.get(root)).exists(reference =>
                  reference.module == operation.owner &&
                    instanceInterfacePorts.get(reference).exists(_.generated.nonEmpty)
                )
              case _ => false
            val path = Option.when(operation.generatedScope.nonEmpty || selectedOperand)(
              s"${modulePath(operation.owner)}.connection_$ordinal"
            )
            Some(KernelTopologyEdge(
              modulePath(operation.owner),
              operation.kind,
              first,
              second,
              generatedScopeSnapshot(operation.generatedScope),
              path
            ) -> operation)
          case _ => None
      else None
    edges.sortBy((edge, _) => (edge.owner, edge.kind, edge.left, edge.right, edge.path))

  private def inheritedClockEdge(domain: ClockDomain, depth: Int = 0): Option[ClockEdge] =
    publicationCharge(1L)
    if depth > 128 then resetFailure("clock parent ancestry exceeds depth budget")
    domain.edge.orElse(domain.parent.flatMap(inheritedClockEdge(_, depth + 1)))

  private def clockEdgeName(edge: ClockEdge): String = edge match
    case ClockEdge.Rising => "rising"
    case ClockEdge.Falling => "falling"

  private def resetPolicyName(policy: ResetPolicy): String = policy match
    case ResetPolicy.None => "none"
    case ResetPolicy.Sync => "sync"
    case ResetPolicy.Async | ResetPolicy.AsyncExternalRelease => "async"
    case _: ResetPolicy.AsyncAssertSyncRelease => "async_assert_sync_release"

  private def resetPolicyAttributes(
      policy: ResetPolicy
  ): Vector[(String, String)] = policy match
    case ResetPolicy.AsyncAssertSyncRelease(stages) =>
      Vector("reset_stages" -> stages.toString)
    case _ => Vector.empty

  private def resetPolarityName(polarity: ResetPolarity): String = polarity match
    case ResetPolarity.ActiveHigh => "active_high"
    case ResetPolarity.ActiveLow => "active_low"

  private def relationAttributes(
      relation: ClockRelation
  ): Vector[(String, String)] = relation match
    case ClockRelation.Same => Vector("clock_relation" -> "alias")
    case ClockRelation.Ratio(multiply, divide, phase) =>
      Vector(
        "clock_relation" -> "ratio",
        "clock_multiply" -> multiply.toString,
        "clock_divide" -> divide.toString,
        "phase_degrees" -> java.lang.Double.toString(phase.degrees)
      )
    case ClockRelation.Synchronous(phaseKnown) =>
      Vector(
        "clock_relation" -> "synchronous",
        "clock_phase_known" -> phaseKnown.toString
      )
    case ClockRelation.MutuallyExclusive =>
      Vector("clock_relation" -> "mutually_exclusive")
    case ClockRelation.Asynchronous =>
      Vector("clock_relation" -> "asynchronous")
    case ClockRelation.Unknown => Vector("clock_relation" -> "unknown")

  private def domainSourcePath(value: AnyRef): String =
    publicationProbe(selectedPath(value)).getOrElse(
      fail("NODAL-DOMAIN-020", "ClockDomain source is outside this construction transaction")
    )

  private def snapshots(resolved: Map[DomainRef, String]): Vector[KernelModuleSnapshot] =
    snapshotCharge(1L)
    val byPath = snapshotElements(records.valuesIterator).flatMap(module =>
      snapshotElements(module.domains)
    ).map { domain =>
      snapshotCharge(1L)
      domainPath(domain.reference) -> domain
    }.toMap
    val demandedResets = mutable.HashSet.empty[DomainRef]
    snapshotElements(records.valuesIterator).flatMap(module =>
      snapshotElements(module.declarations)
    ).foreach: declaration =>
      if !snapshotProbe(
          contextualFsmInitializer(declaration)
        ) && snapshotElements(declaration.attributes).exists {
          case ("initial", Some(_: Expr[?])) => true
          case _ => false
        }
      then
        snapshotProbe(declarationCandidate(declaration)).foreach(domain =>
          demandedResets += domainRef(domain)
        )
    snapshotElements(selectedRequirementBindings.valuesIterator).foreach: (actual, _, _, _) =>
      if effectiveResets.containsKey(byPath(resolved(actual)).domain) then demandedResets += actual
    snapshotCharge(records.size.toLong)
    records.values.toVector.sortBy(record => snapshotProbe(modulePath(record.handle)))(using
      snapshotStringOrdering
    ).map: module =>
      snapshotCharge(1L)
      snapshotCharge(module.domains.size.toLong)
      val domains = module.domains.toVector.map: domain =>
        snapshotCharge(1L)
        publicationCharge(domain.domain.resetPolicy.size.toLong)
        val policyAttributes =
          publicationProbe(domain.domain.resetPolicy.toVector.flatMap(resetPolicyAttributes))
        // Exact publication admits only a direct external Sync domain: parent, relation and
        // clock-record metadata are absent. Its fixed assembly shares the domain visit debit;
        // charge each actual optional/source entry separately before constructing its vector.
        publicationCharge(
          domain.domain.resetPolarity.size.toLong + domain.domain.frequency.size.toLong +
            domain.domain.clock.size.toLong + 1L +
            (if effectiveResets.containsKey(domain.domain) then 1L else 0L)
        )
        val metadata =
          domain.domain.resetPolarity.orElse(Option(effectiveResets.get(domain.domain)).map(value =>
            resetRecord(value).polarity
          ))
            .map(value => Vector("reset_polarity" -> resetPolarityName(value)))
            .getOrElse(Vector.empty) ++
            domain.domain.relation
              .map(relationAttributes)
              .getOrElse(Vector.empty) ++
            policyAttributes ++
            domain.domain.frequency.toVector.map(value =>
              "frequency_hz" -> java.lang.Double.toString(value.hertz)
            ) ++
            domain.domain.parent.toVector.map(value =>
              "clock_parent" -> domainPath(domainRef(value))
            ) ++
            domain.domain.clock.toVector.flatMap(value =>
              clockRecords.get(expressionIds.get(value)).toVector.flatMap(_.parents.drop(1))
                .map(parent => "clock_parent_1" -> domainPath(domainRef(parent)))
            ) ++
            domain.domain.clock.toVector.map(value => "clock_source" -> domainSourcePath(value)) ++
            Vector("reset_source" -> domainSourcePath(domain.domain.reset)) ++
            Option(effectiveResets.get(domain.domain)).toVector.map(value =>
              "effective_reset_source" -> domainSourcePath(value)
            )
        KernelDomainSnapshot(
          domainPath(domain.reference),
          domainName(domain.reference),
          domain.kind.label,
          if domain.kind == KernelDomainKind.Required then resolved.get(domain.reference) else None,
          inheritedClockEdge(domain.domain).map(clockEdgeName),
          domain.domain.resetPolicy.map(resetPolicyName).orElse(
            Option(effectiveResets.get(domain.domain)).map(value =>
              if hasSynchronousResetAssertion(value) then
                "sync"
              else "async"
            )
          ),
          if domain.kind == KernelDomainKind.Required then Vector.empty
          else
            publicationCharge(metadata.size.toLong)
            metadata.sortBy(_._1)(using publicationStringOrdering)
          ,
          relationshipSnapshot(domain),
          Option.when(
            domain.kind == KernelDomainKind.Required
          )(domain.domain.clock.map(domainSourcePath)).flatten,
          Option.when(domain.kind == KernelDomainKind.Required && demandedResets(domain.reference))(
            domainSourcePath(domain.domain.reset)
          )
        )
      snapshotCharge(module.declarations.size.toLong)
      val declarations = module.declarations.toVector.map: declaration =>
        snapshotCharge(1L)
        val retainedAttributes = declaration.attributes ++
          Option.when(Set(
            KernelSignalKind.Register,
            KernelSignalKind.Memory
          ).contains(declaration.kind))(
            snapshotProbe(declarationCandidate(declaration)).toVector.map(domain =>
              "declared_domain" -> domainPath(domainRef(domain))
            )
          ).toVector.flatten
        KernelDeclarationSnapshot(
          snapshotProbe(declarationPath(declaration.reference)),
          declaration.kind.label,
          snapshotProbe(declarationName(declaration.reference)),
          declaration.dataType.map: dataType =>
            val typeOwner = dataType match
              case deferred: KernelInstanceDataType[?] if !deferred.finalized =>
                instanceDigitalPorts.get(declaration.reference).map(binding =>
                  binding.instance -> binding.port
                )
                  .orElse(
                    instanceDigitalInouts.get(declaration.reference)
                  ).orElse(declaration.value match
                    case view: InterfaceResolvedMember[?, ?] =>
                      instanceInterfacePorts.get(
                        declarationIds.get(DigitalInterfaceContract.root(view.endpoint))
                      ).flatMap(binding => binding.instance.map(_ -> binding.port))
                    case _ => None).map(_._2.module).getOrElse(
                    fail(
                      "NODAL-HIERARCHY-057-001",
                      "deferred type lost its exact child declaration"
                    )
                  )
              case _ => module.handle
            renderType(dataType, typeOwner)
          ,
          snapshotProbe(localDeclarationDomain(declaration, resolved)),
          retainedAttributes.map: (name, value) =>
            snapshotCharge(1L)
            val rendered =
              if name == "initial" && declaration.kind == KernelSignalKind.Register then
                value match
                  case Some(expression: Expr[?]) => pathOf(expression).getOrElse(
                      fail("NODAL-DIGITAL-054-010", "register initializer has no captured identity")
                    )
                  case expression: Expr[?] => pathOf(expression).getOrElse(
                      fail("NODAL-DIGITAL-054-010", "register initializer has no captured identity")
                    )
                  case None => "none"
                  case _ =>
                    fail("NODAL-DIGITAL-054-010", "register initializer is not a typed expression")
              else if Set("stream_payload", "stream_valid")(name) then
                value match
                  case expression: Expr[?] => pathOf(expression).getOrElse(
                      fail(
                        "NODAL-STREAM-057-001",
                        "Stream carrier lost its actual captured identity"
                      )
                    )
                  case _ => fail("NODAL-STREAM-057-001", "Stream carrier is not a typed expression")
              else if name == "count" && declaration.kind == KernelSignalKind.InterfaceArray then
                renderShapeDimension(value, module.handle)
              else renderAny(value, module.handle)
            name -> rendered
          ,
          declaration.conservativeDimensions.map(renderShapeDimension(_, module.handle)),
          snapshotProbe(generatedScopeSnapshot(module.generatedRegions.find(region =>
            region.declarations.contains(declaration.reference) &&
              (region.areaFamily || declaration.kind == KernelSignalKind.AnalogNodeArray)
          ))),
          snapshotProbe(declarationDomain(declaration, resolved)),
          instanceDigitalPorts.get(declaration.reference).map: boundary =>
            KernelInstancePortSnapshot(
              snapshotProbe(instancePath(module.handle, boundary.instance.ordinal)),
              snapshotProbe(declarationPath(boundary.port)),
              if declaration.kind == KernelSignalKind.InstanceInput then "input" else "output",
              boundary.generated.map(generatedSymbolSnapshot(_, boundary.scope)),
              boundary.generated.flatMap(_ =>
                boundary.scope.map(region => expressionPath(region.reference))
              )
            ),
          instanceInterfacePorts.get(declaration.reference).map: binding =>
            interfaceBoundarySnapshot(module.handle, binding),
          instanceDigitalInouts.get(declaration.reference).map: (instance, port) =>
            KernelInterfaceBoundarySnapshot(
              snapshotProbe(instancePath(module.handle, instance.ordinal)),
              declarationPath(port)
            ),
          snapshotProbe(resolvedMemberSnapshot(declaration)),
          snapshotProbe(conservativeMemberSnapshot(declaration))
        )
      snapshotCharge(module.instances.size.toLong)
      val instances = module.instances.toVector.map: instance =>
        snapshotCharge(1L)
        val child = records(instance.child)
        snapshotCharge(1L)
        boundDomainCharge(child.domains.size.toLong)
        val bindings = child.domains.filter(_.kind == KernelDomainKind.Required).map: domain =>
          boundDomainCharge()
          domain.name -> domainPath(selectedRequirementBindings(domain.reference)._1)
        val actualBindings =
          boundDomainCharge(child.domains.size.toLong)
          snapshotCharge(1L)
          child.domains.filter(_.kind == KernelDomainKind.Required).flatMap: requirement =>
            boundDomainCharge()
            val (actualRef, origin, _, _) = selectedRequirementBindings(requirement.reference)
            val actual = records(actualRef.module).domains(actualRef.index)
            val (clock, reset) = actualDomainControls(actual, byPath(resolved(actualRef)))
            boundDomainCharge(clock.size.toLong)
            clock.toVector.map: clock =>
              boundDomainCharge()
              KernelInstanceDomainBindingSnapshot(
                boundDomainBindingPath(
                  snapshotProbe(instancePath(
                    module.handle,
                    instance.ordinal
                  )),
                  requirement.name
                ),
                domainPath(requirement.reference),
                domainPath(actualRef),
                domainSourcePath(clock),
                reset.map(domainSourcePath),
                origin
              )
        snapshotCharge(instance.parameterOverrides.size.toLong)
        val parameters = instance.parameterOverrides.toVector.map:
          case (parameter, value) =>
            snapshotCharge(1L)
            val reference = parameter match
              case candidate: AnyRef =>
                Option(declarationIds.get(candidate)).getOrElse(
                  fail(
                    "NODAL-PARAMETER-BINDING-016",
                    "instance parameter override targets an unknown parameter",
                    Some(snapshotProbe(instancePath(module.handle, instance.ordinal)))
                  )
                )
              case _ =>
                fail(
                  "NODAL-PARAMETER-BINDING-016",
                  "instance parameter override is not a declaration",
                  Some(snapshotProbe(instancePath(module.handle, instance.ordinal)))
                )
            if reference.module != instance.child then
              fail(
                "NODAL-PARAMETER-BINDING-017",
                "instance parameter override targets another Module",
                Some(snapshotProbe(instancePath(module.handle, instance.ordinal)))
              )
            snapshotProbe(declarationName(reference)) -> renderAny(value, module.handle)
        KernelInstanceSnapshot(
          snapshotProbe(instancePath(module.handle, instance.ordinal)),
          snapshotProbe(modulePath(instance.child)),
          instance.lexicalDomain.flatMap(domain => resolved.get(domainRef(domain))), {
            snapshotCharge(bindings.size.toLong)
            bindings.toVector.sortBy(_._1)(using snapshotStringOrdering)
          },
          parameters.sortBy(_._1)(using snapshotStringOrdering),
          snapshotProbe(generatedScopeSnapshot(instance.generatedScope)), {
            snapshotCharge(actualBindings.size.toLong)
            actualBindings.toVector
          }
        )
      KernelModuleSnapshot(
        snapshotProbe(modulePath(module.handle)),
        module.className,
        domains,
        declarations,
        instances,
        module.externalName
      )

  private def generatedBound(value: Any): String = value match
    case literal: Int => literal.toString
    case parameter: Param[?] =>
      Option(declarationIds.get(parameter)).map(declarationPath).getOrElse(
        fail("NODAL-ITERATION-043-001", "generated bound parameter has no semantic identity")
      )
    case expression: KernelExpr[?]
        if requireConstructionPayload(
          expression
        ) && expression.literal.exists(_.kind == "integer") =>
      expression.literal.map(_.value).getOrElse(
        fail("NODAL-ITERATION-043-001", "generated integer literal has no value")
      )
    case expression: KernelExpr[?] =>
      Option(expressionIds.get(expression)).map(expressionPath).getOrElse(
        fail("NODAL-ITERATION-043-001", "generated expression has no captured semantic identity")
      )
    case _ =>
      fail("NODAL-ITERATION-043-001", "generated bound has no canonical symbolic representation")

  private def generatedRegionPath(record: GeneratedRegionRecord): String =
    closureCharge(1L)
    val module = records(record.owner)
    val local = s"generate_${record.ordinal}"
    record.digitalRegion.flatMap(_.parent).filter(_.structuralArm.nonEmpty) match
      case Some(arm) => closurePathAppend(expressionPath(arm.reference), s".$local")
      case None => record.parentOrdinal match
          case None => closurePathAppend(modulePath(record.owner), s".$local")
          case Some(parent) =>
            val parentRecord = module.generatedRegions(parent)
            closurePathAppend(generatedRegionPath(parentRecord), s".$local")

  private def generatedSnapshots(staticDependenciesOnly: Boolean = false)
      : Vector[KernelGeneratedRegionSnapshot] =
    closureCharge(records.size.toLong)
    // The module inventory's sort has at most quadratic comparisons; precharge it
    // independently even when every generated-region registry is empty.
    closureCharge(records.size.toLong * records.size.toLong * 2L)
    records.values.toVector
      .sortBy(record => snapshotProbe(modulePath(record.handle)))(using snapshotStringOrdering)
      .flatMap: module =>
        snapshotCharge(1L)
        module.generatedRegions.toVector.map: region =>
          KernelGeneratedRegionSnapshot(
            path = generatedRegionPath(region),
            owner = modulePath(region.owner),
            parent = region.parentOrdinal.map(index =>
              generatedRegionPath(module.generatedRegions(index))
            ),
            induction = expressionPath(region.induction),
            lower = generatedBound(region.lower),
            upperExclusive = generatedBound(region.upperExclusive),
            step = generatedBound(region.step),
            maximum = region.maximum,
            maximumTripCount = region.maximumTripCount,
            declarations = region.declarations.toVector.map(declarationPath),
            digitalBody = region.digitalRegion.filter(_.structuralGenerate).map(value =>
              val (captures, arguments) = if staticDependenciesOnly then
                staticRegionSignature(value)
              else
                value.captures.valuesIterator.map(capture => pathOf(capture._1).get).toVector ->
                  regionArguments(value)
              KernelDigitalGeneratedBody(
                captures,
                arguments,
                value.loop.get.sourceOrdinal,
                value.parent.map(parent => expressionPath(parent.reference))
              )
            ),
            yieldedArea = region.yieldedArea.map(value =>
              (Vector(modulePath(value.owner)) ++ areaComponents(value)).mkString(".")
            ),
            exports = generatedAreaExports(region)
          )

  private def canonicalGeneratedOwnership(
      ownership: (String, String)
  ): (String, String) =
    val region = generatedOwnershipRecords.getOrElse(
      ownership,
      fail(
        "NODAL-ITERATION-043-004",
        "generated procedural ownership has no canonical region record",
        Some(ownership._1)
      )
    )
    generatedRegionPath(region) -> expressionPath(region.induction)

  private def shapeIndexSnapshots(): Vector[KernelShapeIndexSnapshot] =
    shapeIndices.values.toVector.map: index =>
      val inputPath = index.input match
        case reference: DeclarationRef => declarationPath(reference)
        case reference: ExpressionRef => pathOfReference(reference)
      KernelShapeIndexSnapshot(
        path = if index.conservative.nonEmpty then conservativeEndpointPath(index)
        else expressionPath(index.reference),
        owner = modulePath(index.reference.module),
        input = expressionValues.get(index.reference).flatMap(_.operands.headOption)
          .flatMap(collectionOperandPath(
            _,
            expressionRegions.get(index.reference),
            index.reference.module
          ))
          .getOrElse(inputPath),
        indices = index.indices.map:
          case value: Expr[?] if expressionRegions.contains(index.reference) =>
            collectionOperandPath(
              value,
              expressionRegions.get(index.reference),
              index.reference.module
            ).get
          case value => generatedBound(value),
        resultDimensions =
          index.resultDimensions.map(renderShapeDimension(_, index.reference.module)),
        sourceUses = index.sourceUses.map(use => expressionPath(use._1)).sorted,
        generatedScope = generatedScopeSnapshot(index.generatedScope),
        region =
          expressionRegions.get(index.reference).map(value => expressionPath(value.reference))
      )

  private def shapeViewSnapshots(): Vector[KernelShapeViewSnapshot] =
    shapeViews.values.toVector.map: view =>
      val inputPath = view.input match
        case reference: DeclarationRef => declarationPath(reference)
        case reference: ExpressionRef => pathOfReference(reference)
      KernelShapeViewSnapshot(
        path = expressionPath(view.reference),
        owner = modulePath(view.reference.module),
        input = expressionValues.get(view.reference).flatMap(_.operands.headOption)
          .flatMap(collectionOperandPath(
            _,
            expressionRegions.get(view.reference),
            view.reference.module
          ))
          .getOrElse(inputPath),
        dimensions = view.dimensions.map(renderShapeDimension(_, view.reference.module)),
        slice = view.slice.map(selection =>
          KernelShapeSliceSnapshot(
            selection.axis,
            generatedBound(selection.lower),
            generatedBound(selection.upperExclusive)
          )
        ),
        generatedScope = generatedScopeSnapshot(view.generatedScope),
        region = expressionRegions.get(view.reference).map(value => expressionPath(value.reference))
      )

  private def publicationSourceIndex(entries: Vector[SourceMapEntry]) =
    val pairs = publicationVector(entries).map(entry =>
      publicationProbe(entry.semanticPath -> entry.source)
    )
    if !publicationWorkActive then pairs.toMap
    else publicationElements(pairs).toMap

  private def waiverSnapshots(
      sourceMap: Vector[SourceMapEntry]
  ): Vector[KernelWaiverSnapshot] =
    val sourceByPath = publicationSourceIndex(sourceMap)
    executableWaivers.toVector.map: (reference, waiver) =>
      val path = expressionPath(reference)
      KernelWaiverSnapshot(
        "cdc",
        waiver.id,
        waiver.reason,
        waiver.relation.kind,
        path,
        pathOf(waiver.input),
        Some(domainPath(domainRef(waiver.destination))),
        sourceByPath.get(path)
      )
    .sortBy(waiver => (waiver.semanticPath, waiver.id))

  private def continuousOperatorSnapshots(
      sourceMap: Vector[SourceMapEntry]
  ): Vector[KernelContinuousOperatorSnapshot] =
    val sourceByPath = publicationSourceIndex(sourceMap)
    publicationCharge(6L)
    val allAnalyses = Vector(
      "ac",
      "dc",
      "initialization",
      "noise",
      "operating-point",
      "transient"
    )
    continuousOperators.toVector.map: record =>
      val path = expressionPath(record.reference)
      val owner = modulePath(record.reference.module)
      val generatedOwnership = analogRegions.find(_.expressions.contains(record.reference))
        .flatMap(_.generatedOwnership)
        .map(canonicalGeneratedOwnership)
      val stateId =
        if record.operation == "analog_idt" then Some(s"$path.state") else None
      val initialization =
        if record.operation == "analog_ddt" then "none"
        else if record.initialCondition.nonEmpty then "fixed"
        else "solver-selected"
      KernelContinuousOperatorSnapshot(
        path = path,
        operation = record.operation,
        owner = owner,
        context = record.context,
        input = pathOf(record.input).getOrElse(
          fail(
            "NODAL-ANALOG-035-002",
            "continuous-time operator input has no semantic path",
            Some(path)
          )
        ),
        initialCondition = record.initialCondition.map: initial =>
          pathOf(initial).getOrElse(
            fail(
              "NODAL-ANALOG-035-002",
              "idt initial condition has no semantic path",
              Some(path)
            )
          ),
        inputDimension = record.inputDimension.signature,
        resultDimension = record.resultDimension.signature,
        stateId = stateId,
        initialization = initialization,
        analyses = allAnalyses,
        source = sourceByPath.get(path),
        generatedOwner = generatedOwnership.map(_._1),
        generatedInduction = generatedOwnership.map(_._2)
      )
    .sortBy(_.path)

  private def transferOperatorSnapshots(sourceMap: Vector[SourceMapEntry])
      : Vector[KernelTransferOperatorSnapshot] =
    val sources = publicationSourceIndex(sourceMap)
    transferOperators.toVector.map: (reference, kind, n, d, inputs, dimension) =>
      val path = expressionPath(reference)
      val generatedOwnership = analogRegions.find(_.expressions.contains(reference))
        .flatMap(_.generatedOwnership)
        .map(canonicalGeneratedOwnership)
      KernelTransferOperatorSnapshot(
        path,
        kind,
        modulePath(reference.module),
        n,
        d,
        inputs.map(input =>
          pathOf(input).getOrElse(
            AnalogTransferContract.fail(2, "transfer input has no semantic path")
          )
        ),
        dimension,
        sources.get(path),
        generatedOwnership.map(_._1),
        generatedOwnership.map(_._2)
      )
    .sortBy(_.path)

  private def noiseOperatorSnapshots(sourceMap: Vector[SourceMapEntry])
      : Vector[KernelNoiseOperatorSnapshot] =
    val sources = publicationSourceIndex(sourceMap)
    noiseOperators.toVector
      .sortBy: (reference, _, _, _, _) =>
        modulePath(reference.module) -> reference.index
      .map: (reference, kind, label, inputs, dimension) =>
        val path = expressionPath(reference)
        val generatedOwnership = analogRegions.find(_.expressions.contains(reference))
          .flatMap(_.generatedOwnership)
          .map(canonicalGeneratedOwnership)
        KernelNoiseOperatorSnapshot(
          path,
          kind,
          modulePath(reference.module),
          label,
          inputs.map(input =>
            pathOf(input).getOrElse(
              AnalogNoiseContract.fail(2, "noise input has no semantic path")
            )
          ),
          dimension,
          sources.get(path),
          generatedOwnership.map(_._1),
          generatedOwnership.map(_._2)
        )

  private def waveformOperatorSnapshots(
      sourceMap: Vector[SourceMapEntry]
  ): Vector[KernelWaveformOperatorSnapshot] =
    val sources = publicationSourceIndex(sourceMap)
    waveformOperators.toVector.map:
      case (
            reference,
            operation,
            inputs,
            context,
            dimensions,
            result,
            inputContinuity,
            outputContinuity
          ) =>
        val path = expressionPath(reference)
        val state = Set("analog_transition", "analog_slew", "analog_absdelay").contains(operation)
        val generatedOwnership = analogRegions.find(_.expressions.contains(reference))
          .flatMap(_.generatedOwnership)
          .map(canonicalGeneratedOwnership)
        KernelWaveformOperatorSnapshot(
          path,
          operation,
          modulePath(reference.module),
          context,
          inputs.map(input =>
            pathOf(input).getOrElse(
              fail("NODAL-ANALOG-036-002", "waveform input has no semantic path", Some(path))
            )
          ),
          dimensions,
          result,
          inputContinuity,
          outputContinuity,
          if state then Some(s"$path.state") else None,
          if operation == "analog_bound_step" then Vector("transient")
          else Vector("ac", "dc", "initialization", "noise", "operating-point", "transient"),
          sources.get(path),
          generatedOwnership.map(_._1),
          generatedOwnership.map(_._2)
        )
    .sortBy(_.path)

  private def structuralShapeExpressionRoots: Iterator[Any] =
    snapshotElements(shapeIndices.valuesIterator).flatMap(index =>
      snapshotElements(index.indices.iterator) ++ snapshotElements(
        index.resultDimensions.iterator
      ) ++
        snapshotElements(index.sourceUses.iterator).flatMap(use =>
          snapshotElements(use._2.iterator)
        )
    ) ++ snapshotElements(operations.iterator).flatMap(operation =>
      // Keep the original eager payload collect, including rejected payload entries.
      operation.values.collect {
        case value if { snapshotCharge(1L); value.isInstanceOf[InstanceArrayRootRecord] } =>
          value.asInstanceOf[InstanceArrayRootRecord]
      }
    )
      .flatMap(root =>
        snapshotCharge(1L)
        snapshotElements(root.dimensions.iterator).flatMap(dimensions =>
          snapshotElements(dimensions.iterator)
        )
      ) ++ snapshotElements(shapeViews.valuesIterator).flatMap(view =>
      snapshotElements(view.dimensions.iterator) ++ snapshotElements(view.slice.iterator).flatMap(
        selection =>
          snapshotElements(Iterator(selection.lower, selection.upperExclusive))
      )
    )

  private def analogSnapshots(): Vector[KernelAnalogRegionSnapshot] =
    // Endpoint selectors are structural uses even when authored lexically inside analog.
    // Keep a shared expression in the runtime inventory if any runtime consumer needs it.
    val structural = capturedExpressionClosure(
      structuralShapeExpressionRoots
    )
    analogRegions.toVector.sortBy(region => (modulePath(region.module), region.ordinal)).map:
      region =>
        val generatedOwnership = region.generatedOwnership.map(canonicalGeneratedOwnership)
        val runtimeRoots = region.expressions.iterator.filterNot(structural.contains)
          .flatMap(expressionValues.get) ++ region.contributions.iterator.flatMap:
          (target, value) => Iterator(target, value)
        val runtime = if structural.isEmpty then Set.empty[ExpressionRef]
        else capturedExpressionClosure(runtimeRoots)
        val expressions = region.expressions.distinct.toVector
          .filter(reference => !structural.contains(reference) || runtime.contains(reference))
          .map: reference =>
            val expression = expressionValues.getOrElse(
              reference,
              fail(
                "NODAL-ANALOG-SNAPSHOT-001",
                "analog expression reference has no captured value",
                Some(expressionPath(reference))
              )
            )
            val operandPaths =
              if expression.literal.nonEmpty then Vector.empty
              else
                expression.operands.toVector.map: operand =>
                  pathOf(operand).getOrElse(
                    fail(
                      "NODAL-ANALOG-SNAPSHOT-002",
                      s"analog expression operand '${renderAny(operand, reference.module)}' has no semantic path",
                      Some(expressionPath(reference))
                    )
                  )
            KernelAnalogExpressionSnapshot(
              expressionPath(reference),
              expression.operation.getOrElse("unsupported_generic"),
              operandPaths,
              expression.literal.map(_.value),
              expression.operands.lift(1).collect { case unit: String => unit }
            )
        val byPath = expressions.map(expression => expression.path -> expression).toMap
        val contributions = region.contributions.toVector.zipWithIndex.map:
          case ((target, value), index) =>
            val targetPath = pathOf(target).getOrElse(
              fail("NODAL-ANALOG-SNAPSHOT-003", "contribution target has no semantic path")
            )
            val valuePath = pathOf(value).getOrElse(
              fail("NODAL-ANALOG-SNAPSHOT-004", "contribution value has no semantic path")
            )
            val kind = byPath.get(targetPath).map(_.operation) match
              case Some("potential_access") => "potential"
              case Some("flow_access") => "flow"
              case _ =>
                fail(
                  "NODAL-ANALOG-SNAPSHOT-005",
                  "contribution target must be V(...) or I(...) access",
                  Some(targetPath)
                )
            KernelAnalogContributionSnapshot(
              s"${modulePath(region.module)}.analog_${region.ordinal}.contribution_$index",
              targetPath,
              valuePath,
              kind,
              generatedOwnership.map(_._1),
              generatedOwnership.map(_._2)
            )
        KernelAnalogRegionSnapshot(
          s"${modulePath(region.module)}.analog_${region.ordinal}",
          modulePath(region.module),
          expressions,
          contributions,
          generatedOwnership.map(_._1),
          generatedOwnership.map(_._2)
        )

  private def capturedExpressionClosure(roots: Iterator[Any]): Set[ExpressionRef] =
    if !exportAccountingActive then
      val reachable = mutable.LinkedHashSet.empty[ExpressionRef]
      val visited = new IdentityHashMap[AnyRef, java.lang.Boolean]()

      def visit(value: Any): Unit = value match
        case expression: KernelExpr[?] =>
          if Option(visited.put(expression, java.lang.Boolean.TRUE)).isEmpty then
            val reference = Option(expressionIds.get(expression)).getOrElse(
              fail(
                "NODAL-PARAMETER-BINDING-021",
                "static parameter expression has no captured semantic identity"
              )
            )
            if !constructionPayloadAvailable(expression) then
              fail(
                "NODAL-HIERARCHY-058-CONSTRUCTION",
                "pending construction cannot enter static closure"
              )
            reachable += reference
            if expression.literal.isEmpty then expression.operands.foreach(visit)
        case _: Param[?] => ()
        case _ => ()

      roots.foreach(visit)
      reachable.toSet
    else
      val reachable = mutable.LinkedHashSet.empty[ExpressionRef]
      val visited = new IdentityHashMap[AnyRef, java.lang.Boolean]()
      var stack = List.empty[Iterator[Any]]
      snapshotCharge(1L)
      stack = roots :: stack
      // The stack preserves recursive preorder without imposing a new DAG depth bound.
      while stack.nonEmpty do
        val current = stack.head
        if current.hasNext then
          // Every root/operand edge is charged before consumption, including memo hits.
          snapshotCharge(1L)
          current.next() match
            case expression: KernelExpr[?] =>
              if Option(visited.put(expression, java.lang.Boolean.TRUE)).isEmpty then
                val reference = Option(expressionIds.get(expression)).getOrElse(
                  fail(
                    "NODAL-PARAMETER-BINDING-021",
                    "static parameter expression has no captured semantic identity"
                  )
                )
                if !constructionPayloadAvailable(expression) then
                  fail(
                    "NODAL-HIERARCHY-058-CONSTRUCTION",
                    "pending construction cannot enter static closure"
                  )
                reachable += reference
                if expression.literal.isEmpty then
                  snapshotCharge(1L)
                  stack = expression.operands.iterator :: stack
            case _: Param[?] => ()
            case _ => ()
        else
          snapshotCharge(1L)
          stack = stack.tail
      snapshotCharge(reachable.size.toLong)
      reachable.toSet

  private def boundaryElementKind(dataType: DataType[?]): String =
    var current = dataType
    var depth = 0
    snapshotCharge(1L)
    var descriptor = CandidateRuntime.typeDescriptor(current)
    while descriptor.kind == "Vec" && depth <= 128 do
      current = descriptor.arguments.headOption.collect { case value: DataType[?] => value }
        .getOrElse(fail("NODAL-DIGITAL-054-018", "capability Vec has no element type"))
      snapshotCharge(1L)
      descriptor = CandidateRuntime.typeDescriptor(current)
      depth += 1
    if depth > 128 then fail("NODAL-DIGITAL-054-018", "capability type exceeds depth bound")
    descriptor.kind

  private def isBoundaryCapability(dataType: DataType[?]): Boolean =
    Set("Clock", "Reset").contains(boundaryElementKind(dataType))

  private def isClockedDigitalType(dataType: DataType[?]): Boolean =
    Set("Bool", "Bits", "UInt", "SInt", "Enum", "Struct", "Clock", "Reset")
      .contains(boundaryElementKind(dataType))

  private def capabilitySourceRoot(value: AnyRef): Option[DeclarationRef] =
    publicationCharge(1L)
    var current =
      Option(declarationIds.get(value)).map(identity[DeclarationRef | ExpressionRef])
        .orElse(Option(expressionIds.get(value)))
    var depth = 0
    while current.exists(_.isInstanceOf[ExpressionRef]) && depth < 128 do
      val reference = current.get.asInstanceOf[ExpressionRef]
      current = shapeIndices.get(reference).map(_.input)
        .orElse(shapeViews.get(reference).map(_.input))
      depth += 1
    current.collect { case reference: DeclarationRef => reference }

  private def capabilityDomain(
      declaration: DeclarationRecord,
      resolved: Map[DomainRef, String]
  ): String =
    val domains = records(declaration.reference.module).domains
    declarationDomain(declaration, resolved) match
      case Some(identity) =>
        domains.find(domain =>
          resolved.get(domain.reference).contains(identity) ||
            domainPath(domain.reference) == identity
        ).map(_.name).getOrElse(
          fail("NODAL-DIGITAL-054-018", "capability boundary has no local domain")
        )
      case None =>
        val actual = domains.filter(domain =>
          (domain.domain.clock.toVector :+ domain.domain.reset).exists(value =>
            capabilitySourceRoot(value).contains(declaration.reference)
          )
        )
        if actual.size == 1 then actual.head.name
        else if domains.size == 1 then domains.head.name
        else fail("NODAL-DIGITAL-054-018", "capability boundary requires an exact local domain")

  private def validateBoundarySource(
      value: Expr[?],
      target: DeclarationRecord,
      resolved: Map[DomainRef, String]
  ): Unit =
    var reference: DeclarationRef | ExpressionRef =
      Option(declarationIds.get(value)).map(identity[DeclarationRef | ExpressionRef])
        .orElse(Option(expressionIds.get(value)))
        .getOrElse(fail("NODAL-DIGITAL-054-018", "capability source has no captured identity"))
    val visited = mutable.HashSet.empty[ExpressionRef]
    while reference.isInstanceOf[ExpressionRef] do
      val expression = reference.asInstanceOf[ExpressionRef]
      if !visited.add(expression) || visited.size > 65536 then
        fail("NODAL-DIGITAL-054-018", "capability source projection is cyclic or oversized")
      reference = shapeIndices.get(expression).map(_.input)
        .orElse(shapeViews.get(expression).map(_.input)).getOrElse(
          fail("NODAL-DIGITAL-054-018", "capability source must be an input boundary projection")
        )
    val root = reference.asInstanceOf[DeclarationRef]
    val source = records(root.module).declarations(root.index)
    if root.module != target.reference.module || source.kind != KernelSignalKind.Input ||
      capabilityDomain(source, resolved) != capabilityDomain(target, resolved)
    then
      fail("NODAL-DIGITAL-054-018", "capability source requires a same-owner/domain input boundary")

  private def assignmentReference(value: Any): DeclarationRef | ExpressionRef = value match
    case reference: AnyRef =>
      Option(declarationIds.get(reference)).map(identity[DeclarationRef | ExpressionRef])
        .orElse(Option(expressionIds.get(reference))).getOrElse(
          fail("NODAL-DIGITAL-054-010", "digital assignment value has no captured identity")
        )
    case _ => fail("NODAL-DIGITAL-054-010", "digital assignment requires an actual typed value")

  /** Resolve only actual canonical shape edges, never an operation spelling or materialized alias.
    */
  private def digitalAssignmentRoot(
      value: Any,
      owner: Long,
      allowRootRegister: Boolean,
      generatedScope: Option[GeneratedRegionRecord] = activeGeneratedRegion
  ): DeclarationRef =
    boundDomainCharge()
    val selected = assignmentReference(value)
    var current = selected
    val visited = mutable.HashSet.empty[ExpressionRef]
    while current.isInstanceOf[ExpressionRef] do
      val reference = current.asInstanceOf[ExpressionRef]
      if reference.module != owner || expressionRegions.get(
          reference
        ).exists(!_.permitsAssignment) ||
        !visited.add(reference) || visited.size > 128
      then
        fail(
          "NODAL-DIGITAL-054-010",
          "digital assignment projection has foreign, cyclic or excessive scope"
        )
      val index = shapeIndices.get(reference)
      val view = shapeViews.get(reference)
      val projectionScope = index.flatMap(_.generatedScope).orElse(view.flatMap(_.generatedScope))
      if projectionScope.exists(region => !generatedScope.contains(region) || !region.areaFamily)
      then
        fail(
          "NODAL-DIGITAL-054-010",
          "digital assignment projection requires its actual generated scope"
        )
      val capture = collectionArguments.get(reference).filter(_._1.permitsAssignment).flatMap:
        (region, _) =>
          region.captures.valuesIterator.find(value =>
            Option(expressionIds.get(value._2)).contains(reference)
          ).map(value => valueReference(dataCapture(value._1)))
      val input = capture.orElse(index.map(_.input)).orElse(view.map(_.input)).getOrElse(
        fail(
          "NODAL-DIGITAL-054-010",
          "digital assignment target is not an actual storage projection"
        )
      )
      val expression = expressionValues.getOrElse(
        reference,
        fail("NODAL-DIGITAL-054-010", "digital assignment projection has no actual expression")
      )
      val operation = if index.nonEmpty then "shape_index"
      else if view.exists(_.slice.nonEmpty) then "shape_slice" else "shape_view"
      if capture.isEmpty && (!expression.operation.contains(operation) ||
          !expression.operands.headOption.exists(value => assignmentReference(value) == input))
      then
        fail(
          "NODAL-DIGITAL-054-010",
          "digital assignment projection differs from its actual input edge"
        )
      current = input
    val root = current.asInstanceOf[DeclarationRef]
    val declaration = records(root.module).declarations(root.index)
    if instanceDigitalPorts.get(root).exists(_.generated.nonEmpty) then
      fail(
        "NODAL-HIERARCHY-058-001",
        "selected generated Instance writes require selection-qualified assignment proof",
        Some(declarationPath(root))
      )
    val rootScope = records(root.module).generatedRegions.find(_.declarations.contains(root))
    val ownedScope = rootScope.isEmpty || rootScope.exists(region =>
      region.areaFamily && generatedScope.contains(region)
    )
    val writable = Set(
      KernelSignalKind.Wire,
      KernelSignalKind.Output,
      KernelSignalKind.InstanceInput
    ).contains(declaration.kind)
    val rootRegister = allowRootRegister && selected == root &&
      declaration.kind == KernelSignalKind.Register
    if root.module != owner || !ownedScope || (!writable && !rootRegister) then
      fail(
        "NODAL-DIGITAL-054-010",
        "digital assignment target requires an owned wire/output; projected register updates are unsupported"
      )
    root

  def assignSignalSelection(left: Expr[?], right: Expr[?]): Unit =
    requireNoCollectionEffect("digital selection assignment", assignment = true)
    if activeLoop.isEmpty && activeEventProcess.isEmpty then
      requireNoGeneratedEffect("digital selection assignment")
    validateCollectionDependencies(Vector(left, right))
    val owner = currentModule.handle
    val _ = digitalAssignmentRoot(left, owner, allowRootRegister = false)
    val source = assignmentReference(right)
    val sourceOwner = source match
      case value: DeclarationRef => value.module
      case value: ExpressionRef => value.module
    // validateCollectionDependencies above resolves real ancestry through the shared capture
    // registry. A parent value is legal through that explicit capture; sibling/escaped values fail.
    if sourceOwner != owner then
      fail(
        "NODAL-DIGITAL-054-010",
        "digital assignment source must belong to the exact module scope"
      )
    val exact =
      for
        targetType <- CandidateRuntime.expressionDataType(left)
        sourceType <- CandidateRuntime.expressionDataType(right)
      yield DigitalEnumContract.sameType(
        CandidateRuntime.typeDescriptor(targetType),
        CandidateRuntime.typeDescriptor(sourceType)
      )
    if !exact.contains(true) then
      fail(
        "NODAL-DIGITAL-054-010",
        "digital assignment requires exactly matching recursive storage types"
      )
    operation("assignment", left, right)

  private def digitalAssignmentRecords(
      resolved: Map[DomainRef, String]
  ): Vector[(KernelDigitalAssignmentSnapshot, Operation)] =
    val ordinals = mutable.HashMap.empty[Long, Int]
    publicationOperationVector.filter(operation =>
      Set("assignment", "value-connect").contains(operation.kind)
    ).map: operation =>
      if operation.generatedScope.nonEmpty || operation.values.size != 2 then
        fail("NODAL-DIGITAL-054-010", "digital assignment requires a direct-module sink and value")
      val target = digitalAssignmentRoot(
        operation.values.head,
        operation.owner,
        allowRootRegister = true,
        generatedScope = assignmentGeneratedScope(operation)
      )
      boundDomainCharge(2L) // ordinal map read and write
      val ordinal = ordinals.getOrElse(operation.owner, 0)
      ordinals.update(operation.owner, ordinal + 1)
      boundDomainCharge() // cached module path dispatch
      val owner = modulePath(operation.owner)
      boundDomainCharge(2L) // canonical declaration lookup and bounded scalar capability test
      val declaration = records(target.module).declarations(target.index)
      if declaration.dataType.exists(isBoundaryCapability) then
        if declaration.kind != KernelSignalKind.Output then
          fail("NODAL-DIGITAL-054-018", "capability assignment requires an output boundary")
        operation.values(1) match
          case value: Expr[?] => validateBoundarySource(value, declaration, resolved)
          case _ => fail("NODAL-DIGITAL-054-018", "capability assignment requires a typed source")
      // This scoped profile has exactly ordinal zero. Pay the emitted path characters and
      // the record/tuple constructions; existing operand-path probes remain unchanged.
      boundDomainCharge(owner.length.toLong + ".digital_assignment_0".length.toLong + 2L)
      KernelDigitalAssignmentSnapshot(
        s"$owner.digital_assignment_$ordinal",
        owner,
        collectionOperandPath(
          operation.values.head,
          operation.digitalRegion,
          operation.owner
        ).getOrElse(fail(
          "NODAL-DIGITAL-054-010",
          "digital assignment target has no captured identity"
        )),
        collectionOperandPath(
          operation.values(1),
          operation.digitalRegion,
          operation.owner
        ).getOrElse(fail(
          "NODAL-DIGITAL-054-010",
          "digital assignment value has no captured identity"
        )),
        if operation.eventProcess.nonEmpty then "event_update"
        else if declaration.kind == KernelSignalKind.Register then "register_next"
        else "continuous",
        operation.digitalRegion.map(value => expressionPath(value.reference)),
        operation.sourceOrdinal,
        operation.eventProcess.map(eventProcessPath)
      ) -> operation

  private def digitalOwnerOf(value: Any): Option[String] = value match
    case reference: AnyRef =>
      Option(declarationIds.get(reference)).map(_.module)
        .orElse(Option(expressionIds.get(reference)).map(_.module)).map(modulePath)
    case _ => None

  private def digitalValueSnapshots(
      interfaces: DigitalInterfaceContract.Capture,
      nets: Vector[KernelResolvedNetSnapshot]
  )
      : (Vector[ResolvedEnumAbi], Vector[KernelDigitalExpressionSnapshot]) =
    publicationCharge(1L + expressionValues.size.toLong)
    val expressions = publicationVector(expressionValues.toVector).map: (reference, expression) =>
      (expression, expressionPath(reference), modulePath(reference.module), reference.module)
    val contextualSplits = DigitalInterfaceContract.validateValues(
      publicationVector(expressions).map((expression, _, owner, _) => expression -> owner),
      interfaces,
      pathOf,
      digitalOwnerOf,
      publicationVector(nets).flatMap(net =>
        net.boundary.map(boundary => net.path -> boundary.endpoint)
      ),
      publicationElements(records.valuesIterator).filter(_.externalName.nonEmpty)
        .flatMap(_.declarations.iterator.map(declaration =>
          declarationPath(declaration.reference)
        )).toSet ++ interfaces.interfaces.filter(endpoint =>
        records.valuesIterator.exists(module =>
          module.externalName.nonEmpty && modulePath(module.handle) == endpoint.owner
        )
      ).flatMap(_.members.map(_.path))
    )
    val storageRoots = publicationElements(records.valuesIterator).flatMap(module =>
      publicationElements(module.declarations.iterator)
    )
      .filter(declaration =>
        Set(KernelSignalKind.Register, KernelSignalKind.Wire).contains(declaration.kind)
      )
      .flatMap(declaration =>
        publicationElements(declaration.attributes.iterator).collect {
          case ("initial", value) => value
        }
      ).flatMap:
        case expression: Expr[?] => Vector(expression)
        case Some(expression: Expr[?]) => Vector(expression)
        case _ => Vector.empty
    val assignmentRoots = publicationElements(operations.iterator).filter(operation =>
      Set("assignment", "value-connect").contains(operation.kind)
    ).flatMap(operation =>
      publicationVector(operation.values).collect:
        case expression: KernelExpr[?]
            if expression.literal.nonEmpty || Option(expressionIds.get(expression)).exists(
              reference =>
                shapeIndices.contains(reference) || shapeViews.contains(reference)
            ) || expression.operation.exists(operation =>
              operation.startsWith(
                DigitalEnumContract.Prefix
              ) || DigitalScalarContract.Operations.contains(
                operation
              ) || DigitalInterfaceContract.ValueOperations.contains(operation) ||
                operation == DigitalMaterializationContract.Operation ||
                DigitalCombinationalContract.Operations.contains(operation) ||
                DigitalAggregateContract.Operations.contains(operation) ||
                DigitalCombinationalContract.ReachableBooleanOperations.contains(operation)
            ) => expression
    )
    val (enums, values) = DigitalValueConstruction.snapshot(
      publicationElements(records.valuesIterator).flatMap(module =>
        publicationElements(module.declarations.iterator).flatMap(_.dataType)
      ).toVector ++ interfaces.dataTypes ++ flatFsms.valuesIterator.map(
        _.definition.canonicalType
      ).toVector ++
        enumSwitches.valuesIterator.map(record =>
          new KernelDataType[Data](KernelTypeDescriptor("Enum", Vector(record.canonical)))
        ).toVector,
      storageRoots.toVector ++ assignmentRoots.toVector ++
        publicationElements(
          operations.iterator
        ).filter(_.kind == "memory-write").flatMap(_.values.collect {
          case value: Expr[?] => value
        }).toVector ++ interfaces.valueRoots ++
        collectionRegions.valuesIterator.flatMap(_.yielded).toVector ++
        structuralArms.valuesIterator.flatMap(_.yielded).toVector ++
        behavioralIfs.valuesIterator.map(_.predicate).toVector ++
        flatFsms.valuesIterator.flatMap(record =>
          record.controlInputs ++ record.transitions.map(_.guard)
        ).toVector ++
        enumSwitches.valuesIterator.map(_.selector).toVector ++
        Option.when(!stimulusOnly)(
          publicationElements(records.valuesIterator).flatMap(module =>
            publicationElements(module.domains.iterator).filter(
              _.kind != KernelDomainKind.Required
            ).flatMap(_.domain.clock)
          ).toVector
        ).toVector.flatMap(values => publicationProbe(publicationVector(values))),
      (publicationElements(shapeIndices.keysIterator).map(reference =>
        val path = expressionPath(reference)
        publicationCharge(1L + path.length.toLong)
        path
      ) ++
        publicationElements(shapeViews.keysIterator).map(
          expressionPath
        )).toSet,
      expressions,
      (dataType, owner, region) =>
        publicationProbe:
          renderCollectionType(
            dataType,
            owner,
            region.flatMap(path =>
              allValueRegions
                .find(value => expressionPath(value.reference) == path)
            )
          )
      ,
      expression =>
        publicationProbe:
          Option(expressionIds.get(expression)).flatMap(expressionRegions.get)
            .map(value => expressionPath(value.reference))
      ,
      (expression, operand) =>
        val reference = expressionIds.get(expression)
        collectionOperandPath(operand, expressionRegions.get(reference), reference.module)
      ,
      collectionArguments.keysIterator.map(expressionPath).toSet,
      memoryReadSnapshot,
      executableCrossingSnapshot,
      executableWaiverSnapshot,
      resetSnapshot,
      clockSnapshot,
      generatedMemberSnapshot
    )
    val ordinaryReads = publicationVector(expressions).flatMap: (expression, path, _, _) =>
      expression.interfaceMember.map: read =>
        val endpoint = declarationIds.get(DigitalInterfaceContract.root(read.endpoint))
        val actual = ConstructionInterfaceLayout.exactMemberPath(
          read.endpoint.definition,
          read.field,
          read.through
        )
        if actual != read.member || !expression.operation.contains(
            DigitalInterfaceContract.OrdinaryReadOperation
          )
        then
          fail("NODAL-INTERFACE-054-003", "ordinary read lost its exact member witness")
        path -> KernelInterfaceMemberOrigin(
          declarationPath(endpoint),
          actual,
          read.endpoint.role.name
        )
    val ordinaryByPath = publicationVector(ordinaryReads).toMap
    // Registered regions own the complete final operand inventory, including Integer captures
    // created by deferred type inference after the original result expression was captured.
    val collectionOperands = collectionRegions.valuesIterator.map: region =>
      val result = region.result.collect { case expression: KernelExpr[?] => expression }
        .getOrElse(collectionFailure("final collection has no actual result"))
      if !Option(expressionIds.get(result)).contains(region.reference) ||
        !result.operation.contains(DigitalCollectionContract.Operation) || !result.typeFinalized
      then
        collectionFailure("final collection lost its registered result identity")
      expressionPath(region.reference) ->
        (region.inputs ++ region.seed ++ region.captures.valuesIterator.map(_._1)).map(value =>
          collectionOperandPath(value, region.parent, region.reference.module).getOrElse(
            collectionFailure("final collection operand lost its actual lexical identity")
          )
        )
    .toMap
    enums -> publicationVector(values).map(value =>
      publicationCharge(1L + value.path.length.toLong)
      value.copy(
        operands = collectionOperands.getOrElse(value.path, value.operands),
        hierarchyDriver = contextualSplits(value.path),
        interfaceMember = ordinaryByPath.get(value.path)
      )
    )

  private def parameterExpressionSnapshots(
      additionalTypes: Vector[DataType[?]]
  ): Vector[KernelParameterExpressionSnapshot] =
    val roots = snapshotElements(rootParameterBindings.iterator).map(_._2) ++
      snapshotElements(records.valuesIterator).flatMap(module =>
        snapshotElements(module.instances.iterator).flatMap(instance =>
          snapshotElements(instance.parameterOverrides.iterator).map(_._2)
        )
      )
    val defaultRoots = snapshotElements(records.valuesIterator).flatMap(module =>
      snapshotElements(module.declarations.iterator).filter(
        _.kind == KernelSignalKind.Parameter
      ).flatMap(_.attributes.collect {
        case (key, value) if { snapshotCharge(1L); key == "default" } => value
      })
    )
    val generatedRoots = snapshotElements(records.valuesIterator).flatMap(module =>
      snapshotElements(module.generatedRegions.iterator).flatMap(region =>
        snapshotElements(Iterator(region.lower, region.upperExclusive, region.step))
      )
    )
    def shapeDimensionRoots(dataType: DataType[?]): Vector[Any] = snapshotTraversal {
      snapshotCharge(1L)
      val descriptor = CandidateRuntime.typeDescriptor(dataType)
      if Set("Bits", "UInt", "SInt").contains(descriptor.kind) then descriptor.arguments
      else if descriptor.kind == "Struct" then
        snapshotCharge(descriptor.arguments.lift(1).size.toLong)
        descriptor.arguments.lift(1).toVector.flatMap:
          case fields: Seq[?] =>
            snapshotCharge(1L)
            val result = snapshotSeqVector(fields).flatMap: value =>
              snapshotCharge(1L)
              value match
                case field: StructField[?] =>
                  val roots = shapeDimensionRoots(field.dataType)
                  snapshotCharge(roots.size.toLong)
                  roots
                case _ => Vector.empty
            snapshotCharge(result.size.toLong)
            result
          case _ =>
            snapshotCharge(1L)
            Vector.empty
      else if descriptor.kind != "Vec" then Vector.empty
      else
        // Preserve the original eager recursion before inspecting dimensions.
        val nested = descriptor.arguments.headOption.collect:
          case element: DataType[?] => shapeDimensionRoots(element)
        snapshotCharge(descriptor.arguments.lift(1).size.toLong)
        val dimensions = descriptor.arguments.lift(1).toVector.flatMap:
          case values: Seq[?] =>
            snapshotCharge(1L)
            val copied = snapshotSeqVector(values)
            snapshotCharge(copied.size.toLong)
            copied
          case _ =>
            snapshotCharge(1L)
            Vector.empty
        val nestedRoots = nested.getOrElse(Vector.empty)
        if dimensions.nonEmpty && nestedRoots.nonEmpty then
          snapshotCharge(dimensions.size.toLong + nestedRoots.size.toLong)
        dimensions ++ nestedRoots
    }

    val shapeDimensionExpressionRoots = snapshotElements(records.valuesIterator).flatMap(module =>
      snapshotElements(module.declarations.iterator).flatMap(declaration =>
        val cardinalityRoots = declaration.kind match
          case KernelSignalKind.Memory => declaration.attributes.collect {
              case (key, value)
                  if { snapshotCharge(1L); key == "depth" } =>
                value
            }
          case KernelSignalKind.InterfaceArray =>
            declaration.attributes.collect {
              case (key, value)
                  if { snapshotCharge(1L); key == "count" } => value
            }
          case _ => Vector.empty
        snapshotElements(declaration.dataType.iterator).flatMap(dataType =>
          snapshotElements(shapeDimensionRoots(dataType).iterator)
        ) ++ snapshotElements(declaration.conservativeDimensions.iterator) ++
          snapshotElements(cardinalityRoots.iterator)
      )
    )
    val interfaceTypeRoots = snapshotElements(additionalTypes.iterator).flatMap(dataType =>
      snapshotElements(shapeDimensionRoots(dataType).iterator)
    )
    val expressionTypeRoots =
      snapshotElements(expressionValues.valuesIterator).filter(constructionPayloadAvailable)
        .flatMap(expression =>
          (snapshotElements(CandidateRuntime.expressionDataType(
            expression
          ).iterator) ++ snapshotElements(expression.operands.iterator).collect {
            case metadata: StructOperationMetadata => metadata.schema
          }).flatMap(dataType => snapshotElements(shapeDimensionRoots(dataType).iterator))
        )
    val lexicalShapeRoots =
      snapshotElements(expressionValues.valuesIterator).filter(constructionPayloadAvailable)
        .filter(CandidateRuntime.proceduralShapeSource(_)).flatMap: expression =>
          val expressionType = CandidateRuntime.expressionDataType(expression)
          snapshotCharge(expressionType.size.toLong)
          val dimensions = expressionType.toVector.flatMap(dataType =>
            snapshotCharge(1L)
            val roots = shapeDimensionRoots(dataType)
            snapshotCharge(roots.size.toLong)
            roots
          )
          snapshotElements(dimensions.iterator) ++
            (if expression.operation.contains("analog_array_fill") then Iterator.empty
             else
               // operands is a Vector; dropping its first entry retains the original operation.
               snapshotCharge(math.max(0, expression.operands.size - 1).toLong)
               snapshotElements(expression.operands.drop(1).iterator))
    val retainedLoops =
      retainedLoopRegions ++ snapshotElements(
        snapshotElements(loopRegions.valuesIterator).filter(_.structuralGenerate)
      )
    val predicateRoots =
      snapshotElements(snapshotElements(staticPredicates.valuesIterator).flatMap(predicate =>
        snapshotElements(predicate.operands.iterator)
      ) ++ snapshotElements(allValueRegions).flatMap(region =>
        snapshotElements(region.captures.valuesIterator).map(_._1)
      )).collect {
        case value: KernelExpr[?]
            if requireConstructionPayload(
              value
            ) && value.resultDescriptor.contains(KernelTypeDescriptor("Integer")) &&
              !runtimeSequentialInteger(value) =>
          snapshotCharge(1L)
          value
      }.toVector
    val loopStaticRoots =
      (snapshotElements(loopRegions.valuesIterator).filter(retainedLoops).flatMap(region =>
        snapshotElements(Iterator(
          region.loop.get.lower,
          region.loop.get.upperExclusive,
          region.loop.get.step
        )) ++
          snapshotElements(region.captures.valuesIterator).map(value =>
            dataCapture(value._1)
          ).filter(value =>
            CandidateRuntime.expressionDataType(value).exists(dataType =>
              CandidateRuntime.typeDescriptor(dataType) == KernelTypeDescriptor("Integer")
            )
          )
      ) ++ snapshotElements(expressionValues.iterator).collect:
        case (reference, value)
            if expressionRegions.get(reference).exists(retainedLoops) &&
              requireConstructionPayload(value) && value.resultDescriptor.contains(
                KernelTypeDescriptor("Integer")
              ) => value
      ).map(value => { snapshotCharge(1L); value }).toVector
    snapshotCharge(loopStaticRoots.size.toLong + predicateRoots.size.toLong)
    val loopStaticReferences =
      snapshotElements((loopStaticRoots ++ predicateRoots).iterator).collect {
        case expression: KernelExpr[?] if expressionIds.containsKey(expression) =>
          snapshotCharge(1L)
          expressionIds.get(expression)
      }.toSet
    val reachable = capturedExpressionClosure(
      snapshotElements(predicateRoots.iterator ++ loopStaticRoots.iterator ++ interfaceTypeRoots ++ expressionTypeRoots ++ lexicalShapeRoots ++ roots ++ defaultRoots ++ generatedRoots ++ structuralShapeExpressionRoots ++
        shapeDimensionExpressionRoots).collect:
        case expression: KernelExpr[?]
            if requireConstructionPayload(
              expression
            ) && (expression.literal.isEmpty || loopStaticReferences.contains(
              expressionIds.get(expression)
            )) &&
              !expression.operation.contains("generate_index") &&
              !expression.operation.exists(_.startsWith(DigitalEnumContract.Prefix)) =>
          expression
    )

    snapshotCharge(expressionValues.size.toLong)
    expressionValues.toVector.collect:
      case (reference, expression)
          if { snapshotCharge(1L); reachable.contains(reference) } &&
            !collectionArguments.contains(reference) &&
            (!iterationIndices.contains(
              reference
            ) || expressionRegions.get(reference).exists(_.structuralGenerate)) &&
            !expression.operation.contains("generate_index") =>
        if expression.operation.exists(operation =>
            operation.startsWith(AnalogContextContract.Prefix) ||
              operation.startsWith(
                AnalogFunctionRegistry.AnalysisPrefix
              ) || operation == "analog_abstime"
          )
        then
          AnalogContextContract.fail(
            4,
            "static parameter and shape expressions cannot read simulator context"
          )
        val path = expressionPath(reference)
        val dataType = CandidateRuntime.expressionDataType(expression)
          .map(renderType(_, reference.module))
          .getOrElse(
            fail(
              "NODAL-PARAMETER-BINDING-022",
              "static parameter expression type is unavailable",
              Some(path)
            )
          )
        val operands =
          if expression.literal.nonEmpty then Vector.empty
          else
            expression.operands.map: operand =>
              snapshotCharge(1L)
              val scoped = operand match
                case value: Expr[?] if expressionRegions.contains(reference) =>
                  bindCollectionValue(
                    value,
                    expressionRegions.get(reference),
                    reference.module,
                    create = false
                  )
                case other => other
              pathOf(scoped).getOrElse(
                fail(
                  "NODAL-PARAMETER-BINDING-023",
                  "static parameter expression operand has no semantic identity",
                  Some(path)
                )
              )
        KernelParameterExpressionSnapshot(
          path,
          snapshotProbe(modulePath(reference.module)),
          expression.operation.orElse(expression.literal.map(_.kind)).getOrElse(
            fail(
              "NODAL-PARAMETER-BINDING-024",
              "static parameter expression operation is unavailable",
              Some(path)
            )
          ),
          operands,
          dataType,
          expression.literal.map(_.value),
          expression.operands.lift(1).collect { case unit: String => unit }.filter(_.nonEmpty),
          expressionRegions.get(reference).map(value => expressionPath(value.reference))
        )

  private def classify(snapshot: ConstructionSnapshot): DesignKind =
    val kindValues = snapshot.modules.flatMap(module =>
      publicationProbe {
        val mapped =
          publicationVector(module.declarations).map(value => publicationProbe(value.kind))
        publicationVector(mapped)
      }
    )
    val kinds =
      if !publicationWorkActive then kindValues.toSet
      else publicationElements(kindValues).toSet
    publicationCharge(7L)
    val analogKinds = Set(
      "analog-input",
      "analog-output",
      "analog-inout",
      "analog-node",
      "analog-node-array",
      "conservative-terminal",
      "analog-signal"
    )
    val analog =
      publicationElements(snapshot.interfaceContracts).exists(endpoint =>
        publicationElements(endpoint.members).exists(member =>
          Set("conservative", "signal-flow").contains(member.protocol)
        )
      ) || publicationElements(
        kinds
      ).exists(analogKinds.contains) || snapshot.continuousOperators.nonEmpty ||
        snapshot.waveformOperators.nonEmpty || snapshot.noiseOperators.nonEmpty ||
        snapshot.transferOperators.nonEmpty || snapshot.analogFunctions.nonEmpty ||
        snapshot.analogRegions.nonEmpty ||
        snapshot.analogProcedural.nonEmpty || snapshot.analogSemantics.equations.nonEmpty ||
        snapshot.analogSemantics.contributions.nonEmpty || snapshot.analogSemantics.states.nonEmpty ||
        snapshot.analogSemantics.components.nonEmpty
    val storageKinds = if analog then
      publicationCharge(2L)
      Set("parameter", "variable")
    else Set.empty[String]
    val nonAnalog =
      if !publicationWorkActive then kinds -- analogKinds
      else kinds -- publicationElements(analogKinds)
    val digitalKinds =
      if !publicationWorkActive then nonAnalog -- storageKinds
      else nonAnalog -- publicationElements(storageKinds)
    val digital = digitalKinds.nonEmpty ||
      snapshot.interfaceAbi.nonEmpty ||
      snapshot.resolvedNets.nonEmpty
    (digital, analog) match
      case (true, true) => DesignKind.MixedSignal
      case (true, false) => DesignKind.DigitalOnly
      case (false, true) => DesignKind.AnalogOnly
      case _ => DesignKind.Unsupported

  // C1 prerequisite: one canonical owner for the original single-epoch phase.
  // Incremental activation, queues and epoch advance are deliberately not admitted here yet.
  private final class BoundaryClosureOwner(
      rootHandle: Long,
      private var resolved: Map[DomainRef, String],
      private var interfaceFormalTypes: Vector[DataType[?]]
  ):
    // Diagnostics must remain available after the meter is exhausted or poisoned.
    // Capture only this diagnostic site before enable; semantic names use canonical helpers.
    private val diagnosticRootPath = modulePath(rootHandle)
    private var drained = false
    var structuralValues: Option[Map[String, AnyRef]] = None

    // INSERT INSIDE BoundaryClosureOwner. Proposal only; not a separately compiled source.
    // Required existing owner members:
    //   incremental: Boolean
    //   charge(amount: Long): Unit              // global lifetime incremental budget
    //   reject(message: String): Nothing       // poison owner/session, retire epoch, preserve cause
    //   markDirty(): Unit                      // semantic revision + actual owning proof/type queue
    //
    // markDirty must also make a preverified region proof pending, including when no new
    // ExpressionRef is allocated. One shared revision can conservatively invalidate a
    // whole not-yet-verified closure pass; this does not authorize resealing verified nodes.
    // Owner reads regionSignatureRevision immediately before its complete region proof
    // pass, and freezes only if this version is unchanged, all jobs/descendants closed,
    // and those actual validators succeeded. A changed version means another drain/pass.
    // No ordinary single-epoch behavior, limits, or validation phase order is changed.

    private sealed trait RegionSignatureAtom
    private final case class RegionIdentity(value: AnyRef) extends RegionSignatureAtom
    private final case class RegionText(value: String) extends RegionSignatureAtom
    private final case class RegionNumber(value: Long) extends RegionSignatureAtom
    private final case class RegionBigInteger(value: BigInt) extends RegionSignatureAtom

    private val frozenRegionSignatures =
      new java.util.IdentityHashMap[DigitalValueRegionRecord, Vector[RegionSignatureAtom]]()
    private var signatureRevision = 0L

    def regionSignatureRevision: Long = signatureRevision

    /** Stronger reuse identity check is incremental-only; old mode remains unchanged. */
    def verifyRegionCaptureReuse(valid: => Boolean): Unit =
      if incremental then
        charge(1L)
        if !valid then reject("incremental capture reuse lost its exact parent edge or argument")

    /** Hook runs before allocation and side effects. Existing exact reuse skips it. */
    def beforeRegionMutation(region: DigitalValueRegionRecord): Unit =
      if incremental then
        charge(1L)
        if frozenRegionSignatures.containsKey(region) then
          reject("verified digital region signature cannot change in incremental C1")
        if signatureRevision == Long.MaxValue then reject("region signature revision is exhausted")
        signatureRevision += 1L
        markDirty()

    /** Flatten mutable type wrappers now, never retain them as equality evidence. */
    private def captureRegionSignature(region: DigitalValueRegionRecord)
        : Vector[RegionSignatureAtom] =
      val result = Vector.newBuilder[RegionSignatureAtom]
      def text(value: String): Unit =
        charge(1L + value.length.toLong / 64L)
        result += RegionText(value)
      def number(value: Long): Unit =
        charge(1L)
        result += RegionNumber(value)
      def identity(value: AnyRef): Unit =
        charge(1L)
        result += RegionIdentity(value)
      def sequence[A](tag: String, values: Iterator[A], depth: Int)(visit: (A, Int) => Unit): Unit =
        text(tag)
        values.foreach: value =>
          charge(1L) // actual sequence/edge traversal, including empty-valued entries
          visit(value, depth + 1)
        text("end:" + tag)
      def option[A](tag: String, value: Option[A], depth: Int)(visit: (A, Int) => Unit): Unit =
        text(tag)
        number(if value.isDefined then 1L else 0L)
        value.foreach(visit(_, depth + 1))
      def reference(value: DeclarationRef | ExpressionRef): Unit = value match
        case value: DeclarationRef =>
          text("declaration"); number(value.module); number(value.index.toLong)
        case value: ExpressionRef =>
          text("expression"); number(value.module); number(value.index.toLong)
      def regionLink(value: DigitalValueRegionRecord): Unit =
        text("digital-region"); identity(value); reference(value.reference)
      def familyLink(value: GeneratedRegionRecord): Unit =
        // This is the link to a separate canonical mutable owner, not a copy of its
        // declarations/yieldedArea/familyHandle registry. Authored facts have their own guard.
        text("generated-family"); identity(value)
        number(value.owner); number(value.ordinal.toLong); reference(value.induction)
        option("digital-body", value.digitalRegion, 0)((body, _) => regionLink(body))
      def familyArgument(value: GeneratedFamilyArgument): Unit =
        text("family-argument"); identity(value); regionLink(value.region)
        number(value.position.toLong); familyLink(value.family)
      def typed(value: Expr[?], depth: Int): Unit =
        if depth > 128 then reject("region type signature exceeds its depth budget")
        text("typed-value"); identity(value); reference(valueReference(value))
        value match
          case expression: KernelExpr[?] =>
            number(if expression.constructionFinalized then 1L else 0L)
            number(if expression.typeFinalized then 1L else 0L)
          case _ => ()
        // expressionDataType follows Param.default recursively. Debit/bound that chain
        // before invoking its normal non-parameter leaf implementation.
        var typeSource = value
        var typeDepth = depth
        while typeSource.isInstanceOf[Param[?]] do
          charge(1L)
          typeDepth += 1
          if typeDepth > 128 then reject("region parameter type chain exceeds its depth budget")
          typeSource = typeSource.asInstanceOf[Param[?]].default
        val descriptor =
          CandidateRuntime.expressionDataType(typeSource).map(CandidateRuntime.typeDescriptor)
            .getOrElse(reject("region signature value lost its actual type"))
        fact(descriptor, depth + 1)
      def fact(value: Any, depth: Int): Unit =
        charge(1L)
        if depth > 128 then reject("region type signature exceeds its depth budget")
        value match
          case value: Expr[?] => typed(value, depth)
          case value: StaticPredicate =>
            text("static-predicate"); identity(value)
            reference(Option(expressionIds.get(value)).getOrElse(
              reject("structural predicate lost its expression identity")
            ))
            text(value.operation)
            sequence("predicate-operands", value.operands.iterator, depth)(fact)
          case value: KernelTypeDescriptor =>
            text("descriptor"); text(value.kind)
            sequence("type-arguments", value.arguments.iterator, depth)(fact)
          case value: DataType[?] =>
            text("data-type"); fact(CandidateRuntime.typeDescriptor(value), depth + 1)
          case value: StructField[?] =>
            text("struct-field"); text(value.name); fact(value.dataType, depth + 1)
          case value: GeneratedFamilyArgument => familyArgument(value)
          case value: GeneratedRegionRecord => familyLink(value)
          case value: DigitalValueRegionRecord => regionLink(value)
          case value: DeclarationRef => reference(value)
          case value: ExpressionRef => reference(value)
          case value: ResolvedEnumAbi =>
            // ABI is immutable, but compare actual content instead of only a hash or rendered type.
            text("enum-abi"); text(value.identity); text(value.policy); number(value.width.toLong)
            sequence("enum-cases", value.cases.iterator, depth): (entry, nextDepth) =>
              text(entry.id); text(entry.name); fact(entry.code, nextDepth)
            text(value.hash)
          case value: Option[?] => option("option", value, depth)(fact)
          case value: Seq[?] => sequence("sequence", value.iterator, depth)(fact)
          case value: String => text("string"); text(value)
          case value: Int => text("int"); number(value.toLong)
          case value: Long => text("long"); number(value)
          case value: Boolean => text("boolean"); number(if value then 1L else 0L)
          case value: BigInt =>
            charge(1L + value.bitLength.toLong / 64L)
            result += RegionBigInteger(value)
          case _ => reject("unsupported value in exact digital region type signature")
      def capture(value: DigitalCaptureValue, depth: Int): Unit = value match
        case value: Expr[?] => typed(value, depth)
        case value: GeneratedFamilyArgument => familyArgument(value)
        case value: GeneratedRegionRecord => familyLink(value)
      def argument(value: DigitalRegionArgument, depth: Int): Unit = value match
        case value: KernelExpr[?] => typed(value, depth)
        case value: GeneratedFamilyArgument => familyArgument(value)

      regionLink(region)
      option("parent", region.parent, 0)((value, _) => regionLink(value))
      text(region.kind); text(region.regionKind.toString)
      number(if region.structuralGenerate then 1L else 0L)
      // O(1) owner lookups detect removal/replacement of a frozen canonical region,
      // including after the final epoch when no registry dirty hook has fired.
      Vector(
        collectionRegions,
        loopRegions,
        structuralArms,
        behavioralArms,
        fsmActionRegions,
        enumSwitchArms
      ).foreach: owner =>
        charge(1L)
        number(if owner.get(region.reference).exists(_ eq region) then 1L else 0L)
      sequence("inputs", region.inputs.iterator, 0)(typed)
      option("seed", region.seed, 0)(typed)
      option("loop", region.loop, 0): (header, depth) =>
        identity(header)
        fact(header.lower, depth); fact(header.upperExclusive, depth); fact(header.step, depth)
        fact(header.maximum, depth); number(header.sourceOrdinal.toLong)
        // Structural and Bounds are immutable, but copy all scalar proof inputs.
        number(header.domain.lower.lower); number(header.domain.lower.upper)
        number(header.domain.upperExclusive.lower); number(header.domain.upperExclusive.upper)
        number(header.domain.step.lower); number(header.domain.step.upper)
        fact(header.domain.maximum, depth); number(header.domain.maximumTripCount.toLong)
        option("generated-owner", header.generated, depth)((value, _) => familyLink(value))
      option("structural-arm", region.structuralArm, 0): (header, _) =>
        identity(header); reference(header.conditional); number(if header.whenTrue then 1L else 0L)
        val conditional = structuralIfs.getOrElse(
          header.conditional,
          reject("structural arm lost its canonical conditional")
        )
        identity(conditional)
        regionLink(conditional.trueArm); regionLink(conditional.falseArm)
        fact(conditional.predicate, 0)
        option("conditional-parent", conditional.parent, 0)((value, _) => regionLink(value))
        // StructuralIfRecord.result is not DigitalValueRegionRecord.result.
        option("conditional-result", conditional.result, 0)(typed)
      option("behavioral-arm", region.behavioralArm, 0): (header, _) =>
        identity(header); reference(header.conditional); number(if header.whenTrue then 1L else 0L)
      option("fsm-action", region.fsmAction, 0): (header, depth) =>
        identity(header); reference(header.machine); text(header.phase)
        fact(header.ownerCase, depth); fact(header.transitionOrdinal, depth)
        number(header.ordinal.toLong)
      option("switch-arm", region.switchArm, 0): (header, _) =>
        identity(header); reference(header.switch); number(header.index.toLong)
      text("arguments")
      region.arguments.iterator.zipWithIndex.foreach: (entry, position) =>
        charge(1L)
        val (value, role) = entry
        number(position.toLong); text(role); argument(value, 0)
        value match
          case expression: KernelExpr[?] =>
            val ref = Option(expressionIds.get(expression)).getOrElse(
              reject("region argument lost its expression identity")
            )
            charge(2L)
            if !collectionArguments.get(ref).exists((owner, ordinal) =>
                (owner eq region) && ordinal == position
              ) ||
              !expressionRegions.get(ref).exists(_ eq region)
            then reject("region argument lost its exact owning position")
            fact(expression.operation, 0)
            sequence("argument-operands", expression.operands.iterator, 0)(fact)
          case family: GeneratedFamilyArgument =>
            if (family.region ne region) || family.position != position then
              reject("family argument lost its exact owning position")
      text("end:arguments")
      sequence("captures", region.captures.iterator, 0): (entry, depth) =>
        val (key, (source, target)) = entry
        fact(key, depth); capture(source, depth); argument(target, depth)
      option("yielded", region.yielded, 0)(typed)
      option("result", region.result, 0)(typed)
      result.result()

    private def sameRegionSignature(
        expected: Vector[RegionSignatureAtom],
        actual: Vector[RegionSignatureAtom]
    ): Boolean =
      charge(1L)
      if expected.size != actual.size then false
      else
        expected.iterator.zip(actual.iterator).forall: (left, right) =>
          charge(1L)
          (left, right) match
            case (RegionIdentity(left), RegionIdentity(right)) => left eq right
            case (RegionText(left), RegionText(right)) =>
              charge(1L + math.max(left.length, right.length).toLong / 64L)
              left == right
            case (RegionNumber(left), RegionNumber(right)) => left == right
            case (RegionBigInteger(left), RegionBigInteger(right)) =>
              charge(1L + math.max(left.bitLength, right.bitLength).toLong / 64L)
              left == right
            case _ => false

    def verifyRegionSignatures(): Unit =
      if incremental then
        charge(1L) // even an empty/no-op drain is not an unmetered repeat
        val iterator = frozenRegionSignatures.entrySet().iterator()
        while iterator.hasNext do
          charge(1L)
          val entry = iterator.next()
          if !sameRegionSignature(entry.getValue, captureRegionSignature(entry.getKey)) then
            reject("verified digital region signature changed outside its canonical mutation hook")

    def freezeRegionSignatures(): Unit =
      if incremental then
        // Owner has just completed ALL actual region validators + descendants, and checked
        // unchanged regionSignatureRevision since that proof pass. Never call on eager
        // type finalization alone, and never overwrite an existing frozen signature.
        verifyRegionSignatures()
        if constructionMode then
          charge(expressionValues.size.toLong)
          if queue.nonEmpty || expressionValues.valuesIterator.exists(!_.typeFinalized) then
            reject("construction descendants and proofs must close before region freeze")
        allValueRegions.foreach: region =>
          charge(1L)
          if !frozenRegionSignatures.containsKey(region) then
            val frozen = captureRegionSignature(region)
            frozenRegionSignatures.put(region, frozen)

    // Explicitly opted in by an internal construction lifecycle, never by allocations.
    var incremental = false
    private var remaining = 65536L
    private var revision = 0L
    private var builtRevision = -1L
    private var epoch = 0L
    private var poisoned = false
    private var running = false
    private var frozenNames: Option[SemanticOriginResult] = None
    private var preparedNames: Option[(Long, SemanticOriginResult)] = None
    private val exportedGraphs = new java.util.IdentityHashMap[
      StructuralParameterBinding.ResolvedDimensions,
      java.lang.Long
    ]()
    private var currentSnapshot: Option[ConstructionSnapshot] = None
    private var currentQueries: Option[StructuralParameterBinding.InstanceQueries] = None
    private var currentMeter: StructuralParameterBinding.WorkMeter =
      StructuralParameterBinding.WorkMeter.Unmetered
    private var selectedJob: Option[(String, Any)] = None
    private val verified = mutable.LinkedHashMap.empty[(String, Any), AnyRef]
    private val queue = mutable.ArrayDeque.empty[(String, Any, AnyRef)]
    private val queued = mutable.HashSet.empty[(String, Any)]
    val typeState = new java.util.IdentityHashMap[KernelExpr[?], java.lang.Integer]()
    var typeWork = 0

    private var frozenStatic: Option[Vector[Any]] = None
    private val frozenDeclarations = mutable.LinkedHashMap.empty[DeclarationRef, DeclarationRecord]
    private val frozenExpressions = mutable.LinkedHashMap.empty[
      ExpressionRef,
      (KernelExpr[?], Vector[Any], Option[String], Option[KernelLiteral])
    ]
    private val descendantsByRegion = mutable.HashMap.empty[
      DigitalValueRegionRecord,
      mutable.LinkedHashMap[ExpressionRef, KernelExpr[?]]
    ]

    private def staticIdentity(): Vector[Any] =
      charge(1L + records.size.toLong + rootParameterBindings.size.toLong)
      Vector(rootParameterBindings.toVector) ++ records.valuesIterator.map: module =>
        charge(
          module.domains.size.toLong + module.instances.size.toLong + module.generatedRegions.size.toLong
        )
        val instances = module.instances.map: instance =>
          charge(instance.namedBindings.size.toLong + instance.parameterOverrides.size.toLong)
          (
            instance,
            instance.defaultBinding,
            instance.namedBindings.toVector,
            instance.parameterOverrides.toVector
          )
        (
          module,
          module.domains.toVector,
          instances.toVector,
          module.generatedRegions.toVector,
          module.externalName
        )
      .toVector

    private def verifyStatic(): Unit =
      frozenStatic.foreach: expected =>
        if staticIdentity() != expected then reject("frozen authored binding identity changed")
      charge(frozenDeclarations.size.toLong + frozenExpressions.size.toLong)
      frozenDeclarations.foreach: (reference, expected) =>
        if !records.get(
            reference.module
          ).flatMap(_.declarations.lift(reference.index)).contains(expected)
        then reject("frozen declaration changed")
      frozenExpressions.foreach: (reference, expected) =>
        charge(expected._2.size.toLong)
        if !expressionValues.get(reference).exists(value =>
            constructionPayloadAvailable(value) && (value eq expected._1) &&
              value.operands == expected._2 && value.operation == expected._3 &&
              value.literal == expected._4
          )
        then reject("frozen pure expression changed")

    private def freezeStatic(): Unit =
      verifyStatic()
      frozenStatic = Some(staticIdentity())
      records.valuesIterator.foreach(_.declarations.foreach(value =>
        charge(1L)
        frozenDeclarations.getOrElseUpdate(value.reference, value)
      ))
      expressionValues.foreach: (reference, value) =>
        charge(1L)
        if !constructionPayloadAvailable(value) then reject("cannot freeze pending construction")
        else
          charge(value.operands.size.toLong)
          frozenExpressions.getOrElseUpdate(
            reference,
            (value, value.operands, value.operation, value.literal)
          )

    private var constructionMode = false
    def hasConstructionWork: Boolean = constructionMode

    def captured(reference: ExpressionRef, expression: KernelExpr[?]): Unit = if incremental then
      if expression.constructionKind.nonEmpty then constructionMode = true
      var current = expressionRegions.get(reference)
      var depth = 0
      while current.nonEmpty do
        charge(1L)
        depth += 1
        if depth > 128 then reject("closure expression ancestry is cyclic or excessive")
        descendantsByRegion.getOrElseUpdate(current.get, mutable.LinkedHashMap.empty)
          .update(reference, expression)
        current = current.get.parent

    def descendants(region: DigitalValueRegionRecord): Vector[KernelExpr[?]] =
      val values =
        descendantsByRegion.get(region).map(_.valuesIterator.toVector).getOrElse(Vector.empty)
      charge(values.size.toLong)
      values

    def reject(message: String): Nothing =
      poisoned = true
      epoch += 1L
      val failure = new ConstructionException(KernelDiagnostic(
        "NODAL-HIERARCHY-058-CLOSURE",
        message,
        Some(diagnosticRootPath)
      ))
      poisonConstructor(failure, diagnosticRootPath)
      throw failure

    def charge(units: Long): Unit = if incremental then
      if poisoned then reject("boundary closure is poisoned")
      if units < 0L || units > remaining then reject("boundary closure work budget exhausted")
      remaining -= units

    def markDirty(): Unit = if incremental then
      charge(1L)
      if revision == Long.MaxValue then reject("boundary closure revision overflow")
      revision += 1L

    def meter: StructuralParameterBinding.WorkMeter = currentMeter
    def queries: StructuralParameterBinding.InstanceQueries =
      currentMeter.checkLive()
      currentQueries.get
    def accepts(kind: String, key: Any): Boolean =
      !incremental || selectedJob.contains(kind -> key)

    private[ConstructionSession] def guard[A](body: => A): A =
      if poisoned then reject("boundary closure is poisoned")
      try body
      catch
        case failure: Throwable =>
          poisoned = true
          epoch += 1L
          poisonConstructor(failure, diagnosticRootPath)
          throw failure

    // Conservative per-pass accounting is deliberately a bounded incremental profile,
    // not the existing 65536-record capacity promise. Every inventory is charged first.
    private def prechargeCanonicalPass(quadratic: Boolean = true): Unit =
      charge(1L + records.size.toLong + expressionValues.size.toLong + operations.size.toLong)
      var count = 1L + records.size.toLong + expressionValues.size.toLong + operations.size.toLong
      records.valuesIterator.foreach: module =>
        val size = module.declarations.size.toLong + module.instances.size.toLong +
          module.domains.size.toLong + module.generatedRegions.size.toLong
        charge(size)
        count += size
        module.declarations.foreach(value =>
          charge(value.attributes.size.toLong + value.conservativeDimensions.size.toLong)
        )
        module.instances.foreach(value =>
          charge(value.parameterOverrides.size.toLong +
            value.namedBindings.size.toLong)
        )
      expressionValues.valuesIterator.foreach: value =>
        val dependencies = if constructionPayloadAvailable(value) then value.operands
        else value.constructionDependencies
        charge(dependencies.size.toLong)
      operations.foreach(value => charge(value.values.size.toLong))
      allValueRegions.foreach: region =>
        charge(1L + region.arguments.size.toLong + region.captures.size.toLong + region.inputs.size)
      if quadratic then
        var row = 0L
        while row < count do
          charge(64L * count)
          row += 1L

    private def checkNames(next: SemanticOriginResult): Unit = frozenNames.foreach: old =>
      def same[K, V](before: Map[K, V], after: Map[K, V]): Unit =
        charge(before.size.toLong)
        before.foreach: (key, value) =>
          if !after.get(key).contains(value) then reject("frozen semantic identity changed")
      same(old.modulePaths, next.modulePaths)
      same(old.domainPaths, next.domainPaths)
      same(old.domainNames, next.domainNames)
      same(old.declarationPaths, next.declarationPaths)
      same(old.declarationNames, next.declarationNames)
      same(old.expressionPaths, next.expressionPaths)
      same(old.expressionNames, next.expressionNames)
      same(old.instancePaths, next.instancePaths)

    def finalNames(next: SemanticOriginResult): Unit = finalNames(next, false)

    private def finalNames(next: SemanticOriginResult, exactPublication: Boolean): Unit =
      if incremental then
        guard:
          checkNames(next)
          verifyRegionSignatures()
          verifyStatic()
          discover()
          if queue.nonEmpty || builtRevision != revision then
            reject("unclosed boundary records appeared before publication")
          prechargeCanonicalPass(quadratic = !exactPublication)

    def verifyPublication(): Unit = if incremental then
      guard:
        verifyRegionSignatures()
        verifyStatic()
        discover()
        charge(expressionValues.size.toLong)
        if queue.nonEmpty || builtRevision != revision ||
          expressionValues.valuesIterator.exists(!_.typeFinalized)
        then reject("unclosed canonical records remain at publication")

    private def snapshot(): ConstructionSnapshot =
      if exportAccountingActive then reject("snapshot accounting is not reentrant")
      val bound = if boundDomainAccountingCandidate then exactBoundDomainRaw() else None
      val exact = bound.nonEmpty || (constructionMode && exactSnapshotEligible())
      prechargeCanonicalPass(quadratic = !exact)
      val previous = snapshotAccountingActive
      snapshotAccountingActive = exact
      try withBoundDomainAccounting(bound.nonEmpty):
          bound.foreach(requireBoundDomainExport(_, publication = false))
          ConstructionSnapshot(
            root = snapshotProbe(modulePath(rootHandle)),
            modules = snapshots(resolved),
            parameterExpressions = parameterExpressionSnapshots(interfaceFormalTypes),
            generatedRegions = generatedSnapshots(staticDependenciesOnly = true),
            staticPredicates = snapshotProbe(staticPredicateSnapshots()),
            structuralIfs = snapshotProbe(structuralIfSnapshots(staticDependenciesOnly = true)),
            interfaceAbi = Vector.empty,
            resolvedNets = Vector.empty,
            topology = Vector.empty
          )
      finally snapshotAccountingActive = previous

    def resolveSemanticNames(): SemanticOriginResult =
      if constructionMode then
        semanticOrigin.resolveConstructionWithWork(units => charge(units.toLong))
      else semanticOrigin.resolveWithWork(units => charge(units.toLong))

    private def refresh(): Unit = if builtRevision != revision then
      // Retire every capability and actual-value lookup together before replacement.
      epoch += 1L
      structuralValues = None
      exportedGraphs.clear()
      val buildingRevision = revision
      val next = preparedNames.filter(_._1 == revision).map(_._2).getOrElse(
        resolveSemanticNames()
      )
      preparedNames = None
      checkNames(next)
      semanticResult = Some(next)
      frozenNames = Some(next)
      val base = snapshot()
      if revision != buildingRevision then
        reject("snapshot construction changed its canonical registry revision")
      val ticket = epoch
      currentMeter = new StructuralParameterBinding.WorkMeter:
        def checkLive(): Unit =
          if poisoned || ticket != epoch then reject("stale boundary snapshot epoch")
        def charge(units: Int): Unit =
          checkLive()
          BoundaryClosureOwner.this.charge(units.toLong)
        override def exported(graph: StructuralParameterBinding.ResolvedDimensions): Unit =
          checkLive()
          BoundaryClosureOwner.this.charge(1L)
          exportedGraphs.put(graph, java.lang.Long.valueOf(ticket))
        override def consumeGraph(graph: StructuralParameterBinding.ResolvedDimensions): Unit =
          checkLive()
          BoundaryClosureOwner.this.charge(1L)
          if !Option(exportedGraphs.get(graph)).exists(_.longValue() == ticket) then
            reject("structural graph belongs to a stale or foreign epoch")
      currentSnapshot = Some(base)
      currentQueries =
        Some(new StructuralParameterBinding.InstanceQueries(base, None, currentMeter))
      builtRevision = revision

    def requireVerifiedDigital(
        reference: DeclarationRef,
        record: InstancePortBoundaryRecord
    ): Unit =
      guard:
        charge(1L)
        if !verified.get("digital" -> reference).exists(_ eq record) then
          reject("selected singleton boundary has not completed canonical verification")

    private def enqueue(kind: String, key: Any, record: AnyRef): Unit =
      charge(1L)
      verified.get(kind -> key) match
        case Some(original) =>
          if original ne record then reject("verified boundary record identity changed")
        case None => if queued.add(kind -> key) then queue.append((kind, key, record))

    private def discover(): Unit =
      charge(1L + operations.size.toLong + instanceDigitalPorts.size.toLong +
        instanceDigitalInouts.size.toLong + instanceInterfacePorts.size.toLong +
        expressionValues.size.toLong + records.size.toLong)
      operations.foreach(value =>
        charge(value.values.size.toLong)
        if value.values.exists(item =>
            item.isInstanceOf[InstanceArrayRootRecord] ||
              item.isInstanceOf[DeferredNodeArrayRecord]
          )
        then enqueue("conservative", value, value)
      )
      instanceDigitalPorts.foreach((key, value) => enqueue("digital", key, value))
      instanceDigitalInouts.foreach((key, value) => enqueue("inout", key, value))
      instanceInterfacePorts.foreach((key, value) => enqueue("interface", key, value))
      expressionValues.foreach: (key, value) =>
        if !constructionPayloadAvailable(value) then
          charge(value.constructionDependencies.size.toLong)
          if !value.constructionDependencies.exists(DeferredResultType.pending) then
            enqueue("construction", key, value)
        else if generatedMemberRecord(value).nonEmpty then enqueue("member", key, value)
      records.valuesIterator.foreach: module =>
        charge(module.declarations.size.toLong)
        module.declarations.foreach: declaration =>
          declaration.value match
            case view: InterfaceResolvedMember[?, ?] => enqueue("resolved", view, view)
            case _ => ()

    def prepareIncrementalInputs(): Unit = guard:
      val bound = if boundDomainAccountingCandidate then exactBoundDomainRaw() else None
      bound match
        case Some(proof) =>
          prechargeCanonicalPass(quadratic = false)
          prepareAreaNames()
          val named = resolveSemanticNames()
          semanticResult = Some(named)
          preparedNames = Some(revision -> named)
          prepareBoundDomainInputs(proof)
          ()
        case None =>
          prechargeCanonicalPass()
          prepareAreaNames()
          val named = resolveSemanticNames()
          semanticResult = Some(named)
          preparedNames = Some(revision -> named)
          resolved = resolveDomains()
          materializeInstanceControlCaptures(resolved)
          markConservativeBindingDependencies()
          interfaceFormalTypes = prepareInterfaceFormalTypes()

    private def prepareBoundDomainInputs(proof: BoundDomainAccountingProof)
        : (Map[DomainRef, String], Vector[DataType[?]]) =
      withBoundDomainAccounting(enabled = true):
        resolved = resolveDomains()
        requireBoundDomainResolved(proof)
        materializeInstanceControlCaptures(resolved)
        markConservativeBindingDependencies()
        interfaceFormalTypes = prepareInterfaceFormalTypes()
        resolved -> interfaceFormalTypes

    def tryFinalBoundDomainInputs(): Option[(Map[DomainRef, String], Vector[DataType[?]])] =
      if !boundDomainAccountingCandidate then None
      else
        guard:
          exactBoundDomainRaw() match
            case None => None
            case Some(proof) =>
              prechargeCanonicalPass(quadratic = false)
              Some(prepareBoundDomainInputs(proof))

    // A disqualified profile retains the old fee; debit failures are never caught/refunded.
    private def bdChecked(test: => Boolean): Boolean =
      charge(1L)
      test

    private def bdEvery[A](values: IterableOnce[A])(test: A => Boolean): Boolean =
      charge(1L)
      val iterator = values.iterator
      while iterator.hasNext do
        charge(1L)
        if !test(iterator.next()) then return false
      true

    private def bdTextEqual(left: String, right: String): Boolean =
      charge(1L + left.length.toLong + right.length.toLong)
      left == right

    // Mechanical extraction of the existing publication hidden-owner block. In legacy
    // mode the two arguments evaluate exactly the original expressions at their original
    // positions. In new raw mode, derived bindings and digital ports are checked separately.
    private def exactConstructionHiddenOwnersEmpty(
        root: Module,
        requirementBindingsAbsent: => Boolean,
        digitalPortsAbsent: => Boolean,
        boundChild: Option[Module] = None,
        rootAssignmentOrdinal: Boolean = false
    ): Boolean =
      def checked(test: => Boolean): Boolean = bdChecked(test)
      val absent =
        checked(expressionRegions.isEmpty) && checked(collectionRegions.isEmpty) &&
          checked(collectionArguments.isEmpty) && checked(loopRegions.isEmpty) &&
          checked(structuralArms.isEmpty) && checked(behavioralArms.isEmpty) &&
          checked(fsmActionRegions.isEmpty) && checked(enumSwitchArms.isEmpty) &&
          checked(iterationIndices.isEmpty) && checked(staticPredicates.isEmpty) &&
          checked(structuralIfs.isEmpty) && checked(behavioralIfs.isEmpty) &&
          checked(flatFsms.isEmpty) && checked(fsmResults.isEmpty) && checked(
            enumSwitches.isEmpty
          ) &&
          checked(privateFsmStorage.isEmpty) && checked(fsmObservations.isEmpty) &&
          checked(openFsms.isEmpty) && checked(behavioralChains.isEmpty) &&
          checked(collectionStack.isEmpty) && checked(generationStack.isEmpty) &&
          checked(compositionObservationRoot.isEmpty) && checked(areas.isEmpty) &&
          checked(generatedOwnershipRecords.isEmpty) && checked(analogRegions.isEmpty) &&
          checked(continuousOperators.isEmpty) && checked(waveformOperators.isEmpty) &&
          checked(transferOperators.isEmpty) && checked(noiseOperators.isEmpty) &&
          checked(eventProcesses.isEmpty) && checked(eventStorage.isEmpty) &&
          checked(activeEventProcess.isEmpty) && checked(clockRecords.isEmpty) &&
          checked(requirementBindingsAbsent) && checked(instanceInterfacePorts.isEmpty) &&
          checked(instanceDigitalInouts.isEmpty) && checked(instanceInterfaceDimensions.isEmpty) &&
          checked(instanceInterfaceTypes.isEmpty) && checked(resolvedInterfaceViews.isEmpty) &&
          checked(conservativeInterfaceViews.isEmpty) && checked(
            globalInterfaceInitializers.isEmpty
          ) &&
          checked(interfaceFormalTypes.isEmpty) && checked(shapeViews.isEmpty)
      if !absent ||
        !checked(
          eventStateBits.isEmpty && executableCrossings.isEmpty && crossingStateBits == 0 && pulseValues.isEmpty
        ) ||
        !checked(
          executableWaivers.isEmpty && waiverIds.isEmpty && provedClockIdentities.isEmpty && rawClockResets.isEmpty
        ) ||
        !checked(clockState == 0L && resetState == 0L && auxiliaryFsmStateBits == 0) ||
        !checked(
          nodeSelectionLookup.isEmpty && instanceSelectionStack.isEmpty && areaStack.isEmpty && areaRecords.isEmpty
        ) ||
        !checked(
          domainStack.isEmpty && generationStack.isEmpty && analogStack.isEmpty && analogSemanticContext.isEmpty
        ) ||
        !checked((nextDigitalEffectOrdinal.isEmpty || (rootAssignmentOrdinal && {
          charge(2L)
          nextDigitalEffectOrdinal.size == 1 && nextDigitalEffectOrdinal.get(rootHandle).contains(1)
        })) && digitalPortsAbsent) ||
        !checked(
          reductionExact.isEmpty && reductionDomains.isEmpty && !reductionChecking && !reductionProofBuilding
        )
      then return false
      // These pure owner observers inspect 1 + 7 + 32 fixed fields, no lazy owner creation.
      charge(if boundChild.isEmpty then 40L else 8L)
      if !userFunctions.publicationEmpty || !analogSemanticRecorder.publicationEmpty ||
        !(boundChild match
          case None => AnalogProceduralConstruction.publicationEmpty(root)
          case Some(child) =>
            AnalogProceduralConstruction.boundPairPublicationEmpty(root, child, charge)
        )
      then return false
      true

    // Phase-local evidence consists only of canonical owner identities, never replacement
    // domain facts, snapshots, names, binding caches or type descriptors. Do not retain it
    // across a preparation/export phase or use it to skip the normal owner traversals.
    private final case class BoundDomainAccountingProof(
        rootObject: Module,
        childObject: Module,
        root: ModuleRecord,
        child: ModuleRecord,
        occurrence: InstanceRecord,
        actual: DomainRecord,
        requirement: DomainRecord,
        clock: DeclarationRef,
        reset: DeclarationRef,
        boundary: KernelExpr[?],
        singletonReference: ExpressionRef,
        singleton: KernelExpr[?],
        assignment: Option[(Operation, Int)]
    )

    // Zero-debit routing only, keeping eager/C1/no-intent/domain-free/root-local fees intact.
    // Every condition is re-established by charged raw eligibility before removing any fee.
    def boundDomainAccountingCandidate: Boolean =
      incremental && constructionMode && registeredConstructionAdmission &&
        records.size == 2 && records.get(rootHandle).exists(module =>
          module.domains.size == 1 && module.instances.size == 1 &&
            module.domains.head.kind == KernelDomainKind.External &&
            records.get(module.instances.head.child).exists(child =>
              child.domains.size == 1 && child.domains.head.kind == KernelDomainKind.Required
            )
        )

    // Same bounded literal grammar as exactPublicationEligible. Extraction/reuse by the
    // legacy method is optional only after old debit-order parity is demonstrated; this
    // new helper is not an alternative type resolver, and admits no parameter or alias.
    private def bdScalar(value: KernelTypeDescriptor, controls: Boolean = false): Boolean =
      charge(1L + value.kind.length.toLong + value.arguments.size.toLong)
      value.kind match
        case "Bool" => value.arguments.isEmpty
        case "Clock" | "Reset" => controls && value.arguments.isEmpty
        case "Bits" | "UInt" | "SInt" => value.arguments match
            case Vector(width: Int) => width > 0
            case _ => false
        case _ => false

    private def bdLiteralDescriptor(value: KernelTypeDescriptor): Boolean =
      charge(1L + value.kind.length.toLong + value.arguments.size.toLong)
      value.kind match
        case "Vec" => value.arguments match
            case Vector(element: DataType[?], dimensions: Seq[?]) =>
              !element.isInstanceOf[KernelInstanceDataType[?]] &&
              bdScalar(CandidateRuntime.typeDescriptor(element)) &&
              bdChecked(dimensions.nonEmpty) && bdEvery(dimensions) {
                case extent: Int => extent > 0
                case _ => false
              }
            case _ => false
        case _ => bdScalar(value)

    private def bdLiteralData(value: DataType[?]): Boolean = value match
      case _: KernelInstanceDataType[?] => false
      case _ => bdLiteralDescriptor(CandidateRuntime.typeDescriptor(value))

    /** This method must run in guard. None selects the old coarse route BEFORE exact work.
      * Exhaustion/poison throws through guard, never becomes None or a refunded fallback. No
      * semanticResult/name/domainPath/resolved/occurrenceDomains/binding-map reads occur. Pending
      * KernelExpr payloads and pending KernelInstanceDataType descriptors are opaque.
      */
    private def exactBoundDomainRaw(): Option[BoundDomainAccountingProof] =
      if !bdChecked(incremental && constructionMode && registeredConstructionAdmission) ||
        !bdChecked(records.size == 2 && moduleIds.size == 2 && records.contains(rootHandle))
      then return None
      val root = records(rootHandle)
      if !bdChecked(root.handle == rootHandle && root.attached &&
          root.parentAtConstruction.isEmpty && root.externalName.isEmpty) ||
        !bdChecked(root.domains.size == 1 && root.instances.size == 1 &&
          root.generatedRegions.isEmpty && root.scaffoldExpressionCount == 0) ||
        !bdChecked(rootParameterBindings.isEmpty && instanceIds.size == 1 &&
          instanceObjects.size == 1 && domainIds.size == 2) ||
        !bdChecked(expressionIds.size == expressionValues.size)
      then return None
      val occurrence = root.instances.head
      val childOption = records.get(occurrence.child)
      if !bdChecked(occurrence.ordinal == 0 && occurrence.child != rootHandle &&
          childOption.nonEmpty && occurrence.generatedScope.isEmpty &&
          occurrence.lexicalDomain.isEmpty && occurrence.namedBindings.isEmpty &&
          occurrence.namedBindingSources.isEmpty && occurrence.parameterOverrides.isEmpty)
      then return None
      val child = childOption.get
      if !bdChecked(child.handle == occurrence.child && child.attached &&
          child.parentAtConstruction.contains(rootHandle) && child.externalName.isEmpty) ||
        !bdChecked(child.instances.isEmpty && child.generatedRegions.isEmpty &&
          child.domains.size == 1 && child.scaffoldExpressionCount == 0) ||
        !bdChecked(declarationIds.size == root.declarations.size + child.declarations.size)
      then return None

      var rootObject = Option.empty[Module]
      var childObject = Option.empty[Module]
      charge(1L)
      val modules = moduleIds.entrySet().iterator()
      while modules.hasNext do
        charge(1L)
        val entry = modules.next()
        entry.getKey match
          case module: Module if entry.getValue.longValue == rootHandle && rootObject.isEmpty =>
            rootObject = Some(module)
          case module: Module if entry.getValue.longValue == child.handle && childObject.isEmpty =>
            childObject = Some(module)
          case _ => return None
      if !bdChecked(rootObject.nonEmpty && childObject.nonEmpty &&
          (rootObject.get ne childObject.get))
      then return None
      val instance = Option(instanceObjects.get(childObject.get))
      if !bdChecked(instance.nonEmpty && (instance.get.module eq childObject.get) &&
          Option(instanceIds.get(instance.get)).exists(_ eq occurrence))
      then return None
      // Cardinality + exact forward identities excludes rogue reverse-map entries.
      if !exactConstructionHiddenOwnersEmpty(
          rootObject.get,
          requirementBindingsAbsent = true,
          digitalPortsAbsent = true,
          boundChild = Some(childObject.get),
          rootAssignmentOrdinal = true
        )
      then return None
      if !bdChecked(directPortRequest.isEmpty && directPortPermit.isEmpty &&
          constructorFailure.isEmpty && waveformForbiddenDepth == 0) ||
        !bdChecked(moduleStack.isEmpty ||
          (moduleStack.size == 1 && (moduleStack.head eq root)))
      then return None

      val allocationSerials = mutable.HashSet.empty[Long]
      def allocated(value: AllocationIdentity, owner: ModuleRecord): Boolean =
        bdChecked(value.constructionAllocation.exists(stamp =>
          (stamp.session eq ConstructionSession.this) && (stamp.module eq owner) &&
            stamp.serial > 0L && stamp.serial <= constructionAllocationSerial &&
            stamp.region.isEmpty && stamp.generation.isEmpty && stamp.area.isEmpty &&
            allocationSerials.add(stamp.serial)
        ))
      if !allocated(occurrence, root) then return None
      val actual = root.domains.head
      val requirement = child.domains.head
      val domain = actual.domain
      val required = requirement.domain
      def domainIdentity(record: DomainRecord, owner: ModuleRecord): Boolean =
        bdChecked(record.reference.module == owner.handle && record.reference.index == 0 &&
          owner.domains.headOption.exists(_ eq record) &&
          Option(domainIds.get(record.domain)).exists(_ eq record.reference)) &&
          bdTextEqual(record.name, record.domain.name)
      if !domainIdentity(actual, root) || !domainIdentity(requirement, child) ||
        !bdChecked(actual.kind == KernelDomainKind.External &&
          requirement.kind == KernelDomainKind.Required && (domain ne required)) ||
        !bdChecked(occurrence.defaultBinding.exists(_ eq domain)) ||
        !bdChecked(domain.parent.isEmpty && domain.relation.isEmpty &&
          !domain.executableRelationship && domain.clock.nonEmpty &&
          domain.edge.exists(edge => edge == ClockEdge.Rising || edge == ClockEdge.Falling)) ||
        !bdChecked(domain.resetPolicy.contains(ResetPolicy.Sync) &&
          domain.resetPolarity.exists(value =>
            value == ResetPolarity.ActiveHigh ||
              value == ResetPolarity.ActiveLow
          ) && domain.frequency.nonEmpty) ||
        !bdEvery(domain.frequency)(value =>
          java.lang.Double.isFinite(value.hertz) && value.hertz > 0.0
        ) ||
        !bdChecked(required.parent.isEmpty && required.relation.isEmpty &&
          !required.executableRelationship && required.edge.isEmpty &&
          required.resetPolicy.isEmpty && required.resetPolarity.isEmpty &&
          required.frequency.isEmpty && required.clock.nonEmpty)
      then return None

      var externalMetadata = 0
      var frequencyMetadata = 0
      var assignment = Option.empty[Operation]
      if !bdEvery(operations) { operation =>
          charge(1L + operation.values.size.toLong + operation.kind.length.toLong)
          if operation.kind == "assignment" then
            bdChecked(assignment.isEmpty && operation.owner == rootHandle &&
              operation.values.size == 2 && operation.generatedScope.isEmpty &&
              operation.digitalRegion.isEmpty && operation.eventProcess.isEmpty &&
              operation.source.nonEmpty && operation.sourceOrdinal == 0) && {
              charge(1L) // retain the canonical candidate, not a copied operation graph
              assignment = Some(operation)
              true
            }
          else
            operation.owner == rootHandle && operation.kind == "metadata" &&
            operation.generatedScope.isEmpty && operation.digitalRegion.isEmpty &&
            operation.eventProcess.isEmpty && operation.source.isEmpty &&
            operation.sourceOrdinal == 0 && (operation.values match
              case Vector(
                    edge: ClockEdge,
                    policy: ResetPolicy,
                    polarity: ResetPolarity,
                    frequency: Frequency
                  ) =>
                val valid = domain.edge.contains(edge) && domain.resetPolicy.contains(policy) &&
                  domain.resetPolarity.contains(polarity) &&
                  domain.frequency.exists(_ eq frequency)
                if valid then externalMetadata += 1
                valid
              case Vector(hertz: Double, unit: String) =>
                val valid = bdTextEqual(unit, "Hz") && java.lang.Double.isFinite(hertz) &&
                  hertz > 0.0 && domain.frequency.exists(_.hertz == hertz)
                if valid then frequencyMetadata += 1
                valid
              case _ => false)
        } || !bdChecked(externalMetadata == 1 && frequencyMetadata <= 1 &&
          (if assignment.isEmpty then nextDigitalEffectOrdinal.isEmpty
           else
             nextDigitalEffectOrdinal.size == 1 &&
             nextDigitalEffectOrdinal.get(rootHandle).contains(1)))
      then return None
      // ClockDomain.required emits expressions, NOT a metadata operation. Any extra
      // child or unknown operation is rejected above instead of ignored by a whitelist.

      def directControl(value: Expr[?], kind: String): Option[DeclarationRef] =
        charge(1L)
        Option(declarationIds.get(value)).filter { reference =>
          bdChecked(reference.module == rootHandle) &&
          root.declarations.lift(reference.index).exists { declaration =>
            bdChecked((declaration.reference eq reference) && (declaration.value eq value) &&
              declaration.kind == KernelSignalKind.Input &&
              declaration.domainCandidate.exists(_ eq domain)) &&
            declaration.dataType.exists { dataType =>
              val descriptor = CandidateRuntime.typeDescriptor(dataType)
              charge(1L + descriptor.kind.length.toLong + descriptor.arguments.size.toLong)
              descriptor.kind == kind && descriptor.arguments.isEmpty &&
              !dataType.isInstanceOf[KernelInstanceDataType[?]]
            }
          }
        }
      val clock = directControl(domain.clock.get, "Clock")
      val reset = directControl(domain.reset, "Reset")
      if !bdChecked(clock.nonEmpty && reset.nonEmpty && (clock.get ne reset.get)) then return None
      val boundary = Option(effectiveResets.get(domain))
      if !bdChecked(effectiveResets.size == 1 && resetRecords.size == 1 &&
          resetBoundaries.size == 1 && physicalResetBoundaries.size == 1 && boundary.nonEmpty) ||
        !bdChecked(Option(resetBoundaries.get(domain.reset)).exists(_ eq boundary.get) &&
          physicalResetBoundaries.get(reset.get -> 0L).exists(_ eq boundary.get))
      then return None
      val resetFact = Option(resetRecords.get(boundary.get))
      if !bdChecked(resetFact.nonEmpty) then return None
      val fact = resetFact.get
      charge(1L + fact.inputs.size.toLong + fact.kind.length.toLong + fact.release.length.toLong)
      if fact.kind != "boundary" || fact.inputs.size != 1 ||
        !(fact.inputs.head eq domain.reset) || fact.stages != 0 ||
        fact.polarity != domain.resetPolarity.get || fact.release != "sync" ||
        !fact.destination.exists(_ eq domain)
      then return None

      val allowed = mutable.HashSet.empty[ExpressionRef]
      def expressionIdentity(value: KernelExpr[?], owner: ModuleRecord): Option[ExpressionRef] =
        charge(1L)
        val reference = Option(expressionIds.get(value))
        reference.filter { ref =>
          bdChecked(ref.module == owner.handle && ref.index >= 0 &&
            ref.index < owner.expressionCount && expressionValues.get(ref).exists(_ eq value)) &&
          allocated(ref, owner) && bdChecked(allowed.add(ref))
        }
      def ordinaryControl(
          value: Expr[?],
          owner: ModuleRecord,
          operation: String,
          descriptor: KernelTypeDescriptor,
          operand: Any
      ): Option[KernelExpr[?]] = value match
        case expression: KernelExpr[?] =>
          if expressionIdentity(expression, owner).isEmpty ||
            !bdChecked(expression.constructionFinalized && expression.typeFinalized &&
              expression.constructionKind.isEmpty && expression.constructionCallback.isEmpty &&
              expression.constructionDependencies.isEmpty && expression.constructionRequest.isEmpty)
          then None
          else
            charge(1L + expression.operands.size.toLong)
            if !bdChecked(expression.interfaceMember.isEmpty &&
                expression.materializationIntent.isEmpty && expression.typeIntent.isEmpty &&
                expression.literal.isEmpty && expression.operation.contains(operation) &&
                expression.resultDescriptor.contains(descriptor)) ||
              !bdChecked(expression.operands.size == 1)
            then None
            else
              val same = (expression.operands.head, operand) match
                case (left: String, right: String) => bdTextEqual(left, right)
                case (left: AnyRef, right: AnyRef) => bdChecked(left eq right)
                case _ => false
              Option.when(same)(expression)
        case _ => None
      val resetExpression = ordinaryControl(
        boundary.get,
        root,
        "reset_boundary",
        KernelTypeDescriptor("Reset"),
        domain.reset
      )
      if resetExpression.isEmpty ||
        ordinaryControl(
          required.clock.get,
          child,
          "required_clock",
          KernelTypeDescriptor("Clock"),
          required.name
        ).isEmpty ||
        ordinaryControl(
          required.reset,
          child,
          "required_reset",
          KernelTypeDescriptor("Reset"),
          required.name
        ).isEmpty
      then return None

      var proxyCount = 0
      def declarationIdentity(declaration: DeclarationRecord, owner: ModuleRecord): Boolean =
        bdChecked(declaration.reference.module == owner.handle &&
          owner.declarations.lift(declaration.reference.index).exists(_ eq declaration) &&
          Option(declarationIds.get(declaration.value)).exists(_ eq declaration.reference)) &&
          allocated(declaration.reference, owner) &&
          bdChecked(declaration.attributes.isEmpty && declaration.conservativeDimensions.isEmpty) &&
          bdChecked(declaration.value match
            case signal: Signal[?] => declaration.dataType.exists(_ eq signal.dataType)
            case _ => false)
      def declaredData(declaration: DeclarationRecord, owner: ModuleRecord): Boolean =
        if !declarationIdentity(declaration, owner) then false
        else if declaration.reference eq clock.get then true
        else if declaration.reference eq reset.get then true
        else if assignment.nonEmpty &&
          Set(KernelSignalKind.Output, KernelSignalKind.InstanceOutput).contains(declaration.kind)
        then false
        else
          declaration.kind match
            case KernelSignalKind.InstanceInput | KernelSignalKind.InstanceOutput =>
              proxyCount += 1
              bdChecked(owner eq root) && bdChecked(declaration.domainCandidate.isEmpty) &&
              instanceDigitalPorts.get(declaration.reference).exists { binding =>
                bdChecked((binding.instance eq occurrence) && binding.port.module == child.handle &&
                  binding.generated.isEmpty && binding.scope.isEmpty) &&
                child.declarations.lift(binding.port.index).exists { port =>
                  bdChecked((port.reference eq binding.port) &&
                    Option(declarationIds.get(port.value)).exists(_ eq binding.port) &&
                    ((port.kind == KernelSignalKind.Input &&
                      declaration.kind == KernelSignalKind.InstanceInput) ||
                      (port.kind == KernelSignalKind.Output &&
                        declaration.kind == KernelSignalKind.InstanceOutput))) &&
                  port.dataType.exists(bdLiteralData)
                } &&
                // Do not read formal, kernelDescriptor or resultDescriptor here.
                declaration.dataType.exists(_.isInstanceOf[KernelInstanceDataType[?]])
              }
            case KernelSignalKind.Input | KernelSignalKind.Output =>
              bdChecked(!instanceDigitalPorts.contains(declaration.reference)) &&
              bdChecked(declaration.domainCandidate.forall(candidate =>
                if owner eq root then candidate eq domain else candidate eq required
              )) &&
              declaration.dataType.exists(bdLiteralData)
            case _ => false
      if !bdEvery(root.declarations)(declaredData(_, root)) ||
        !bdEvery(child.declarations)(declaredData(_, child)) ||
        !bdChecked(proxyCount == instanceDigitalPorts.size)
      then return None
      // No separate entry scan is needed: each proxy's canonical key/record was checked;
      // equality of counts excludes extra keys and the declarations have unique references.

      var singleton = Option.empty[(ExpressionRef, KernelExpr[?])]
      if !bdEvery(expressionValues) { (reference, value) =>
          if allowed.contains(reference) then
            bdChecked(Option(expressionIds.get(value)).exists(_ eq reference))
          else if singleton.nonEmpty || expressionIdentity(value, root).isEmpty then false
          else
            singleton = Some(reference -> value)
            bdChecked(Option(expressionIds.get(value)).exists(_ eq reference)) &&
            bdChecked(value.registeredConstructionOwnedBy(ConstructionSession.this) &&
              value.constructionKind.exists(DigitalReductionContract.Kinds) &&
              value.constructionCallback.nonEmpty && value.constructionDependencies.size == 1) && {
              val input = value.constructionDependencies.head
              val inputRef = input match
                case expression: Expr[?] => Option(declarationIds.get(expression))
                case _ => None
              bdChecked(inputRef.nonEmpty && inputRef.get.module == rootHandle &&
                !instanceDigitalPorts.contains(inputRef.get)) && {
                val declaration = root.declarations.lift(inputRef.get.index)
                bdChecked(declaration.nonEmpty &&
                  (declaration.get.reference eq inputRef.get) &&
                  (declaration.get.value eq input.asInstanceOf[AnyRef]) &&
                  declaration.get.kind == KernelSignalKind.Input) &&
                declaration.get.dataType.exists { dataType =>
                  val descriptor = CandidateRuntime.typeDescriptor(dataType)
                  charge(1L + descriptor.arguments.size.toLong + descriptor.kind.length.toLong)
                  descriptor.kind == "Vec" && (descriptor.arguments match
                    case Vector(element: DataType[?], dimensions: Seq[?]) =>
                      bdScalar(CandidateRuntime.typeDescriptor(element)) && {
                        charge(1L)
                        val iterator = dimensions.iterator
                        val one = iterator.hasNext && { charge(1L); iterator.next() == 1 }
                        one && !iterator.hasNext
                      } && value.constructionRequest.exists { request =>
                        bdChecked((request.session eq ConstructionSession.this) &&
                          (request.module eq root) &&
                          (request.input eq input.asInstanceOf[Expr[?]]) &&
                          (request.inputReference eq inputRef.get) &&
                          (request.resultReference eq reference) &&
                          request.sourceSlot == value.constructionSourceSlot &&
                          request.frontier >= inputRef.get.constructionAllocation.get.serial &&
                          request.frontier < reference.constructionAllocation.get.serial) &&
                        bdChecked(request.roots.size == 1 &&
                          (request.roots.head.value eq declaration.get.value) &&
                          (request.roots.head.reference eq inputRef.get)) && {
                          if !value.constructionFinalized then
                            bdChecked(value.pendingConstructionOwnedBy(ConstructionSession.this) &&
                              !shapeIndices.contains(reference)) &&
                            bdChecked(semanticOrigin.retainedExpressionSlot(
                              value.constructionSourceSlot,
                              rootHandle,
                              reference.index,
                              value,
                              value.constructionDependencies
                            ))
                          else
                            charge(1L + value.operands.size.toLong)
                            bdChecked(value.typeFinalized && value.interfaceMember.isEmpty &&
                              value.materializationIntent.isEmpty && value.typeIntent.isEmpty &&
                              value.literal.isEmpty && value.operation.contains("shape_index") &&
                              value.operands.size == 2 &&
                              (value.operands.head.asInstanceOf[AnyRef] eq declaration.get.value) &&
                              value.operands(1) == 0) &&
                            bdChecked(value.resultDescriptor.contains(
                              CandidateRuntime.typeDescriptor(element)
                            )) &&
                            shapeIndices.get(reference).exists { index =>
                              charge(1L + index.indices.size.toLong +
                                index.resultDimensions.size.toLong + index.sourceUses.size.toLong)
                              (index.reference eq reference) && (index.input eq inputRef.get) &&
                              index.indices == Vector(0) && index.resultDimensions.isEmpty &&
                              index.sourceUses.isEmpty && index.conservative.isEmpty &&
                              index.generatedScope.isEmpty
                            } && bdChecked(semanticOrigin.retainedExpressionSlot(
                              value.constructionSourceSlot,
                              rootHandle,
                              reference.index,
                              value,
                              value.operands
                            ))
                        }
                      }
                    case _ => false)
                }
              }
            }
        } || !bdChecked(singleton.nonEmpty && allowed.size == expressionValues.size &&
          root.expressionCount == 2 && child.expressionCount == 2 &&
          shapeIndices.size == (if singleton.get._2.constructionFinalized then 1 else 0))
      then return None
      if assignment.nonEmpty then charge(2L) // optional identity/count evidence
      val proof = BoundDomainAccountingProof(
        rootObject.get,
        childObject.get,
        root,
        child,
        occurrence,
        actual,
        requirement,
        clock.get,
        reset.get,
        resetExpression.get,
        singleton.get._1,
        singleton.get._2,
        assignment.map(_ -> operations.size)
      )
      if assignment.nonEmpty && !boundAssignmentValid(proof, checkInventory = false) then None
      else Some(proof)

    // Only canonical identities and bounded scalar facts. No derived-map/name access is
    // needed for raw admission; the normal resolver rechecks its own binding later.
    private def boundAssignmentValid(
        proof: BoundDomainAccountingProof,
        checkInventory: Boolean
    ): Boolean = proof.assignment.forall { (operation, count) =>
      val present = !checkInventory || {
        charge(1L)
        val iterator = operations.iterator
        var found = false
        while iterator.hasNext do
          charge(1L)
          if iterator.next() eq operation then found = true
        bdChecked(found && operations.size == count)
      }
      if !present || !bdChecked(operation.owner == rootHandle &&
          operation.kind == "assignment" && operation.values.size == 2 &&
          operation.generatedScope.isEmpty && operation.digitalRegion.isEmpty &&
          operation.eventProcess.isEmpty && operation.source.nonEmpty &&
          operation.sourceOrdinal == 0 && nextDigitalEffectOrdinal.size == 1 &&
          nextDigitalEffectOrdinal.get(rootHandle).contains(1))
      then false
      else
        charge(2L)
        val targetValue = operation.values.head.asInstanceOf[AnyRef]
        val target = Option(declarationIds.get(targetValue))
        if !bdChecked(target.nonEmpty && target.get.module == rootHandle &&
            (operation.values(1).asInstanceOf[AnyRef] eq proof.singleton) &&
            proof.singleton.constructionFinalized && proof.singleton.typeFinalized)
        then false
        else
          val reference = target.get
          val declaration = proof.root.declarations.lift(reference.index)
          val binding = instanceDigitalPorts.get(reference)
          if !bdChecked(declaration.nonEmpty && binding.nonEmpty &&
              (declaration.get.reference eq reference) &&
              (declaration.get.value eq targetValue) &&
              declaration.get.kind == KernelSignalKind.InstanceInput &&
              (binding.get.instance eq proof.occurrence) &&
              binding.get.port.module == proof.child.handle && binding.get.generated.isEmpty &&
              binding.get.scope.isEmpty)
          then false
          else
            val childPort = proof.child.declarations.lift(binding.get.port.index)
            val input = proof.singleton.constructionRequest.get.inputReference
            val rootInput = input match
              case reference: DeclarationRef => proof.root.declarations.lift(reference.index)
              case _: ExpressionRef => None
            if !bdChecked(childPort.nonEmpty && rootInput.nonEmpty &&
                (childPort.get.reference eq binding.get.port) &&
                childPort.get.kind == KernelSignalKind.Input &&
                rootInput.get.reference.eq(input) &&
                rootInput.get.domainCandidate.exists(_ eq proof.actual.domain))
            then false
            else
              declaration.get.dataType match
                case Some(dataType: KernelInstanceDataType[?]) if dataType.finalized =>
                  val targetType = dataType.kernelDescriptor
                  val sourceType = proof.singleton.resultDescriptor
                  val authored = childPort.get.dataType.map(CandidateRuntime.typeDescriptor)
                  bdChecked(sourceType.nonEmpty && authored.nonEmpty) &&
                  bdScalar(targetType) && bdScalar(sourceType.get) && bdScalar(authored.get) &&
                  bdSameLiteralDescriptor(targetType, sourceType.get) &&
                  bdSameLiteralDescriptor(targetType, authored.get)
                case _ => false
    }

    /** Once raw exact work started, mismatches poison instead of selecting coarse fallback. Call
      * immediately after resolveDomains and again before snapshot/export. Derivation is still owned
      * by the unchanged resolver, which clears/rebuilds both maps every time.
      */
    private def requireBoundDomainResolved(proof: BoundDomainAccountingProof): Unit =
      def requireFact(test: => Boolean, message: String): Unit =
        if !bdChecked(test) then reject("bound-domain accounting: " + message)
      requireFact(
        records.get(rootHandle).exists(_ eq proof.root) &&
          records.get(proof.child.handle).exists(_ eq proof.child) &&
          Option(moduleIds.get(proof.rootObject)).exists(_.longValue == rootHandle) &&
          Option(moduleIds.get(proof.childObject)).exists(_.longValue == proof.child.handle),
        "module identity changed during preparation"
      )
      requireFact(
        proof.root.instances.size == 1 &&
          (proof.root.instances.head eq proof.occurrence) &&
          proof.occurrence.defaultBinding.exists(_ eq proof.actual.domain) &&
          proof.occurrence.namedBindings.isEmpty && proof.occurrence.lexicalDomain.isEmpty &&
          proof.occurrence.generatedScope.isEmpty,
        "actual occurrence binding changed during preparation"
      )
      requireFact(
        proof.root.domains.size == 1 && proof.child.domains.size == 1 &&
          (proof.root.domains.head eq proof.actual) &&
          (proof.child.domains.head eq proof.requirement) &&
          Option(domainIds.get(proof.actual.domain)).exists(_ eq proof.actual.reference) &&
          Option(domainIds.get(proof.requirement.domain)).exists(_ eq proof.requirement.reference),
        "domain identity changed during preparation"
      )
      requireFact(
        selectedRequirementBindings.size == 1 &&
          selectedRequirementBindings.headOption.exists(entry =>
            entry._1 eq proof.requirement.reference
          ),
        "derived binding inventory differs from the sole authored requirement"
      )
      requireFact(
        selectedRequirementBindings.get(proof.requirement.reference).exists {
          (actual, origin, owner, ordinal) =>
            (actual eq proof.actual.reference) && bdTextEqual(origin, "default") &&
            owner == rootHandle && ordinal == proof.occurrence.ordinal
        },
        "derived binding does not name the exact actual/occurrence/default edge"
      )
      requireFact(
        resolved.size == 2 && occurrenceDomains.size == 2,
        "derived domain inventory differs from the authored pair"
      )
      bdEvery(resolved.keysIterator) { reference =>
        requireFact(
          (reference eq proof.actual.reference) ||
            (reference eq proof.requirement.reference),
          "resolved map lost canonical key identity"
        )
        true
      }
      bdEvery(occurrenceDomains.keysIterator) { reference =>
        requireFact(
          (reference eq proof.actual.reference) ||
            (reference eq proof.requirement.reference),
          "occurrence map lost canonical key identity"
        )
        true
      }
      // Semantic naming is legal only now; charge its actual shared helper separately through
      // the new preparation/export meter, not merely this string-comparison debit.
      val physical = domainPath(proof.actual.reference)
      bdEvery(Vector(proof.actual.reference, proof.requirement.reference)) { reference =>
        requireFact(
          resolved.get(reference).exists(bdTextEqual(_, physical)) &&
            occurrenceDomains.get(reference).exists(bdTextEqual(_, physical)),
          "derived domain resolves to a stale or foreign physical path"
        )
        true
      }
      requireFact(
        Option(effectiveResets.get(proof.actual.domain)).exists(_ eq proof.boundary) &&
          Option(resetBoundaries.get(proof.actual.domain.reset)).exists(_ eq proof.boundary) &&
          physicalResetBoundaries.get(proof.reset -> 0L).exists(_ eq proof.boundary) &&
          !effectiveResets.containsKey(proof.requirement.domain),
        "derived physical reset identity changed"
      )
      requireFact(
        clockRecords.isEmpty && provedClockIdentities.isEmpty && rawClockResets.isEmpty,
        "resolution introduced an unsupported clock proof/alias"
      )
      if proof.assignment.nonEmpty && !boundAssignmentValid(proof, checkInventory = true) then
        reject("bound-domain accounting: assignment identity changed during preparation")
      // Raw proof is rerun once at the NEXT phase boundary; do not rerun here and then fall
      // back on None. The resolver has no user callbacks in this audited profile. Its ordinary
      // safety validation must still run and remains charged by the preparation owner.

    /** Export-only bounded formal check. Not called by initial raw eligibility/preparation. A
      * pending wrapper remains pending: never call kernelDescriptor/typeDescriptor on it. Reading
      * its immutable formal is required because current snapshots render that formal; do not
      * replace this proof with merely trusting the child's unrelated literal type.
      */
    private def requireBoundDomainExport(
        proof: BoundDomainAccountingProof,
        publication: Boolean
    ): Unit =
      requireBoundDomainResolved(proof)
      def requireFact(test: => Boolean, message: String): Unit =
        if !bdChecked(test) then reject("bound-domain accounting: " + message)
      val semantic = semanticResult.getOrElse(reject("bound-domain export has no semantic names"))
      bdEvery(Vector(proof.root, proof.child)) { module =>
        requireFact(semantic.modulePaths.contains(module.handle), "module name is unresolved")
        bdEvery(module.domains) { domain =>
          requireFact(
            semantic.domainPaths.contains(module.handle -> domain.reference.index) &&
              semantic.domainNames.contains(module.handle -> domain.reference.index),
            "domain name is unresolved"
          )
          true
        } && bdEvery(module.declarations) { declaration =>
          requireFact(
            semantic.declarationNames.contains(module.handle -> declaration.reference.index) &&
              semantic.declarationPaths.contains(module.handle -> declaration.reference.index),
            "declaration name is unresolved"
          )
          true
        }
      }
      requireFact(
        semantic.instancePaths.contains(rootHandle -> proof.occurrence.ordinal),
        "instance name is unresolved"
      )
      bdEvery(expressionValues) { (reference, _) =>
        requireFact(
          semantic.expressionPaths.contains(reference.module -> reference.index),
          "expression name is unresolved"
        )
        true
      }
      bdEvery(instanceDigitalPorts) { (reference, binding) =>
        val declaration = proof.root.declarations(reference.index)
        val original = proof.child.declarations(binding.port.index)
        val dataType = declaration.dataType.get.asInstanceOf[KernelInstanceDataType[?]]
        val authored = CandidateRuntime.typeDescriptor(original.dataType.get)
        // Charge/validate BOTH operand trees before structural descriptor comparison. The
        // grammar is one Vec layer of scalar + literal dimensions, never arbitrary metadata.
        requireFact(
          bdLiteralDescriptor(dataType.formal) && bdLiteralDescriptor(authored),
          "proxy formal is not bounded literal ordinary digital metadata"
        )
        requireFact(
          bdSameLiteralDescriptor(dataType.formal, authored),
          "proxy formal does not preserve its exact original child type"
        )
        if dataType.finalized then
          requireFact(
            bdLiteralDescriptor(dataType.kernelDescriptor) &&
              bdSameLiteralDescriptor(dataType.kernelDescriptor, authored),
            "final proxy type differs from its parameter-free child type"
          )
          requireFact(
            verified.get("digital" -> reference).exists(_ eq binding),
            "final proxy lacks its canonical boundary verification"
          )
        else if publication then reject("bound-domain publication contains a pending proxy")
        true
      }
      if publication then
        requireFact(
          proof.singleton.constructionFinalized && proof.singleton.typeFinalized,
          "publication contains pending registered construction"
        )
      ()

    // Both descriptors must have passed bdLiteralDescriptor. Avoid uncharged generic
    // recursive equals on DataType objects; compare this finite literal grammar directly.
    private def bdSameLiteralDescriptor(
        left: KernelTypeDescriptor,
        right: KernelTypeDescriptor
    ): Boolean =
      if !bdTextEqual(left.kind, right.kind) then false
      else if left.kind == "Vec" then
        (left.arguments, right.arguments) match
          case (Vector(le: DataType[?], ld: Seq[?]), Vector(re: DataType[?], rd: Seq[?])) =>
            bdSameLiteralDescriptor(
              CandidateRuntime.typeDescriptor(le),
              CandidateRuntime.typeDescriptor(re)
            ) && {
              charge(1L)
              val li = ld.iterator
              val ri = rd.iterator
              var same = true
              while same && li.hasNext && ri.hasNext do
                charge(1L)
                same = li.next() == ri.next()
              same && !li.hasNext && !ri.hasNext
            }
          case _ => false
      else
        charge(1L + left.arguments.size.toLong + right.arguments.size.toLong)
        left.arguments == right.arguments

    private def exactSnapshotEligible(): Boolean =
      def checked(test: => Boolean): Boolean =
        charge(1L)
        test
      def every[A](values: IterableOnce[A])(test: A => Boolean): Boolean =
        charge(1L)
        val iterator = values.iterator
        var valid = true
        while valid && iterator.hasNext do
          charge(1L)
          valid = test(iterator.next())
        valid
      def text(value: String): Boolean =
        charge(1L + value.length.toLong)
        true
      def dimension(value: Any): Boolean =
        charge(1L)
        value match
          case _: Int => true
          case expression: Expr[?] =>
            expressionIds.containsKey(expression) || declarationIds.containsKey(expression)
          case _ => false
      def descriptor(value: KernelTypeDescriptor, depth: Int): Boolean =
        charge(1L)
        // Same canonical descriptor depth bound; deeper inputs select the old route.
        if depth >= 128 then false
        else
          text(value.kind)
          charge(value.arguments.size.toLong)
          value.kind match
            case "Bool" | "Integer" => value.arguments.isEmpty
            case "Bits" | "UInt" | "SInt" =>
              value.arguments.size == 1 && dimension(value.arguments.head)
            case "Vec" => value.arguments match
                case Vector(element: DataType[?], dimensions: Seq[?]) =>
                  descriptor(CandidateRuntime.typeDescriptor(element), depth + 1) &&
                  every(dimensions)(dimension)
                case _ => false
            case _ => false
      def metadata(value: Any, depth: Int = 0): Boolean =
        charge(1L)
        if depth >= 128 then false
        else
          value match
            case null => true
            case value: String => text(value)
            case _: Int | _: Long | _: Double | _: Boolean => true
            case value: DataType[?] => descriptor(CandidateRuntime.typeDescriptor(value), depth + 1)
            case value: Expr[?] =>
              expressionIds.containsKey(value) || declarationIds.containsKey(value)
            case value: Option[?] => every(value)(metadata(_, depth + 1))
            case value: Seq[?] => every(value)(metadata(_, depth + 1))
            case _ => false
      val absent =
        checked(expressionRegions.isEmpty) && checked(collectionRegions.isEmpty) &&
          checked(collectionArguments.isEmpty) && checked(loopRegions.isEmpty) &&
          checked(structuralArms.isEmpty) && checked(behavioralArms.isEmpty) &&
          checked(fsmActionRegions.isEmpty) && checked(enumSwitchArms.isEmpty) &&
          checked(iterationIndices.isEmpty) && checked(staticPredicates.isEmpty) &&
          checked(structuralIfs.isEmpty) && checked(behavioralIfs.isEmpty) &&
          checked(flatFsms.isEmpty) && checked(fsmResults.isEmpty) && checked(
            enumSwitches.isEmpty
          ) &&
          checked(privateFsmStorage.isEmpty) && checked(fsmObservations.isEmpty) &&
          checked(openFsms.isEmpty) && checked(behavioralChains.isEmpty) &&
          checked(collectionStack.isEmpty) && checked(generationStack.isEmpty) &&
          checked(compositionObservationRoot.isEmpty) && checked(areas.isEmpty) &&
          checked(generatedOwnershipRecords.isEmpty) && checked(analogRegions.isEmpty) &&
          checked(continuousOperators.isEmpty) && checked(waveformOperators.isEmpty) &&
          checked(transferOperators.isEmpty) && checked(noiseOperators.isEmpty) &&
          checked(eventProcesses.isEmpty) && checked(eventStorage.isEmpty) &&
          checked(activeEventProcess.isEmpty) && checked(clockRecords.isEmpty) &&
          checked(resetRecords.isEmpty) && checked(resetBoundaries.isEmpty) &&
          checked(selectedRequirementBindings.isEmpty) && checked(instanceInterfacePorts.isEmpty) &&
          checked(instanceDigitalInouts.isEmpty) && checked(instanceInterfaceDimensions.isEmpty) &&
          checked(instanceInterfaceTypes.isEmpty) && checked(resolvedInterfaceViews.isEmpty) &&
          checked(conservativeInterfaceViews.isEmpty) && checked(
            globalInterfaceInitializers.isEmpty
          ) &&
          checked(interfaceFormalTypes.isEmpty) && checked(shapeViews.isEmpty)
      def moduleEligible(module: ModuleRecord): Boolean =
        checked(semanticResult.exists(_.modulePaths.contains(module.handle))) &&
          checked(module.domains.isEmpty) && checked(module.generatedRegions.isEmpty) &&
          every(module.declarations) { declaration =>
            val ordinary = declaration.kind match
              case KernelSignalKind.Parameter | KernelSignalKind.Input | KernelSignalKind.Output |
                  KernelSignalKind.InstanceInput | KernelSignalKind.InstanceOutput |
                  KernelSignalKind.Wire => true
              case _ => false
            ordinary &&
            checked(semanticResult.exists(_.declarationNames.contains(
              declaration.reference.module -> declaration.reference.index
            ))) && checked(semanticResult.exists(_.declarationPaths.contains(
              declaration.reference.module -> declaration.reference.index
            ))) && checked(declaration.domainCandidate.isEmpty) &&
            checked(declaration.conservativeDimensions.isEmpty) &&
            !declaration.value.isInstanceOf[InterfaceResolvedMember[?, ?]] &&
            !declaration.value.isInstanceOf[InterfaceTerminalView[?, ?]] &&
            every(declaration.dataType)(value =>
              descriptor(CandidateRuntime.typeDescriptor(value), 0)
            ) && every(declaration.attributes)((name, value) => text(name) && metadata(value))
          } && every(module.instances) { instance =>
            checked(
              semanticResult.exists(_.instancePaths.contains(module.handle -> instance.ordinal))
            ) &&
            checked(instance.lexicalDomain.isEmpty) && checked(instance.generatedScope.isEmpty) &&
            checked(instance.defaultBinding.isEmpty) && checked(instance.namedBindings.isEmpty) &&
            every(instance.parameterOverrides)((parameter, value) =>
              metadata(parameter) && metadata(value)
            )
          }
      absent && every(records.valuesIterator)(moduleEligible) &&
      every(instanceDigitalPorts.valuesIterator) { boundary =>
        checked(boundary.generated.isEmpty) && checked(boundary.scope.isEmpty) &&
        checked(boundary.instance.generatedScope.isEmpty)
      } && every(operations) { operation =>
        text(operation.kind) && operation.kind == "assignment" &&
        checked(operation.digitalRegion.isEmpty) && checked(operation.generatedScope.isEmpty) &&
        checked(operation.eventProcess.isEmpty) && every(operation.values)(metadata(_))
      } && every(rootParameterBindings)((parameter, value) =>
        metadata(parameter) && metadata(value)
      ) && every(expressionValues.valuesIterator) { expression =>
        if !constructionPayloadAvailable(expression) then true
        else
          every(expression.resultDescriptor)(descriptor(_, 0)) &&
          checked(expression.interfaceMember.isEmpty) &&
          every(expression.operation)(text) &&
          every(expression.literal)(value => text(value.kind) && text(value.value)) &&
          every(expression.operands)(metadata(_))
      } && every(shapeIndices.valuesIterator) { index =>
        checked(index.conservative.isEmpty) && checked(index.generatedScope.isEmpty) &&
        every(index.indices)(dimension) && every(index.resultDimensions)(metadata(_)) &&
        every(index.sourceUses)((_, dimensions) => every(dimensions)(dimension))
      }

    /** Charged accounting eligibility only; all canonical validators still execute. */
    private def exactPublicationEligible(root: Module): Boolean =
      def checked(test: => Boolean): Boolean =
        charge(1L)
        test
      def every[A](values: IterableOnce[A])(test: A => Boolean): Boolean =
        charge(1L)
        val iterator = values.iterator
        var valid = true
        while valid && iterator.hasNext do
          charge(1L)
          valid = test(iterator.next())
        valid
      def scalar(value: KernelTypeDescriptor): Boolean =
        charge(1L + value.kind.length.toLong + value.arguments.size.toLong)
        value.kind match
          case "Bool" | "Clock" | "Reset" => value.arguments.isEmpty
          case "Bits" | "UInt" | "SInt" => value.arguments match
              case Vector(width: Int) => width > 0
              case _ => false
          case _ => false
      def data(value: DataType[?]): Boolean =
        val descriptor = CandidateRuntime.typeDescriptor(value)
        charge(1L + descriptor.kind.length.toLong + descriptor.arguments.size.toLong)
        descriptor.kind match
          case "Vec" => descriptor.arguments match
              case Vector(element: DataType[?], dimensions: Seq[?]) =>
                !Set("Clock", "Reset")(CandidateRuntime.typeDescriptor(element).kind) &&
                scalar(CandidateRuntime.typeDescriptor(element)) &&
                every(dimensions) {
                  case extent: Int => extent > 0
                  case _ => false
                }
              case _ => false
          case _ => !Set("Clock", "Reset")(descriptor.kind) && scalar(descriptor)
      if !checked(incremental && constructionMode && registeredConstructionAdmission) ||
        !checked(records.size == 1 && moduleIds.size == 1 && records.contains(rootHandle)) ||
        !checked(Option(moduleIds.get(root)).exists(_.longValue == rootHandle))
      then return false
      val module = records(rootHandle)
      if !checked(
          module.attached && module.parentAtConstruction.isEmpty && module.externalName.isEmpty
        ) ||
        !checked(
          module.instances.isEmpty && module.generatedRegions.isEmpty && module.domains.size == 1
        ) ||
        !checked(
          rootParameterBindings.isEmpty && instanceIds.isEmpty && instanceObjects.isEmpty
        ) ||
        !checked(
          domainIds.size == 1 && declarationIds.size == module.declarations.size && expressionIds.size == expressionValues.size
        )
      then return false
      if !exactConstructionHiddenOwnersEmpty(
          root,
          selectedRequirementBindings.isEmpty,
          instanceDigitalPorts.isEmpty
        )
      then return false
      val domainRecord = module.domains.head
      val domain = domainRecord.domain
      if !checked(occurrenceDomains.size == 1 && resolved.size == 1 &&
          occurrenceDomains.get(domainRecord.reference) == resolved.get(domainRecord.reference) &&
          resolved.get(domainRecord.reference).contains(domainPath(domainRecord.reference)))
      then return false
      if !checked(domainRecord.reference == DomainRef(
          rootHandle,
          0
        ) && domainRecord.kind == KernelDomainKind.External) ||
        !checked(Option(domainIds.get(domain)).contains(domainRecord.reference)) ||
        !checked(
          domain.parent.isEmpty && domain.relation.isEmpty && !domain.executableRelationship
        ) ||
        !checked(domain.edge.exists(value =>
          value == ClockEdge.Rising || value == ClockEdge.Falling
        )) ||
        !checked(domain.resetPolicy.contains(
          ResetPolicy.Sync
        ) && domain.resetPolarity.nonEmpty && domain.clock.nonEmpty) ||
        !every(domain.frequency)(value =>
          java.lang.Double.isFinite(value.hertz) && value.hertz > 0.0
        )
      then return false
      var externalMetadata = 0
      var frequencyMetadata = 0
      val metadataValid = every(operations): operation =>
        charge(1L + operation.values.size.toLong)
        operation.owner == rootHandle && operation.kind == "metadata" &&
        operation.generatedScope.isEmpty && operation.digitalRegion.isEmpty &&
        operation.eventProcess.isEmpty && operation.source.isEmpty && operation.sourceOrdinal == 0 &&
        (operation.values match
          case Vector(
                edge: ClockEdge,
                policy: ResetPolicy,
                polarity: ResetPolarity,
                frequency: Frequency
              ) =>
            val valid = domain.edge.contains(edge) && domain.resetPolicy.contains(policy) &&
              domain.resetPolarity.contains(polarity) && domain.frequency.exists(_ eq frequency)
            if valid then externalMetadata += 1
            valid
          case Vector(hertz: Double, "Hz") =>
            val valid = java.lang.Double.isFinite(hertz) && hertz > 0.0 &&
              domain.frequency.exists(_.hertz == hertz)
            if valid then frequencyMetadata += 1
            valid
          case _ => false)
      if !metadataValid || externalMetadata != 1 || frequencyMetadata > 1 then return false
      def control(value: Expr[?], kind: String): Option[DeclarationRef] =
        charge(1L)
        Option(declarationIds.get(value)).filter: reference =>
          charge(1L)
          reference.module == rootHandle && module.declarations.lift(reference.index).exists:
            declaration =>
              charge(1L)
              (declaration.value eq value) && declaration.kind == KernelSignalKind.Input &&
              declaration.domainCandidate.exists(_ eq domain) && declaration.dataType.exists:
                dataType =>
                  val descriptor = CandidateRuntime.typeDescriptor(dataType)
                  charge(1L + descriptor.kind.length.toLong + descriptor.arguments.size.toLong)
                  descriptor.kind == kind && descriptor.arguments.isEmpty
      val clockReference = control(domain.clock.get, "Clock")
      val resetReference = control(domain.reset, "Reset")
      if clockReference.isEmpty || resetReference.isEmpty || clockReference == resetReference then
        return false
      val boundary = Option(effectiveResets.get(domain))
      if !checked(
          effectiveResets.size == 1 && resetRecords.size == 1 && resetBoundaries.size == 1 && physicalResetBoundaries.size == 1
        ) ||
        !checked(
          boundary.nonEmpty && Option(resetBoundaries.get(domain.reset)).exists(_ eq boundary.get)
        ) ||
        !checked(physicalResetBoundaries.get(resetReference.get -> 0L).exists(_ eq boundary.get))
      then return false
      val reset = boundary.get
      val resetRecord = Option(resetRecords.get(reset))
      if !checked(resetRecord.nonEmpty) then return false
      val resetFact = resetRecord.get
      charge(1L + resetFact.inputs.size.toLong)
      if resetFact.kind != "boundary" || resetFact.inputs.size != 1 ||
        !(resetFact.inputs.head eq domain.reset) || resetFact.stages != 0 ||
        resetFact.polarity != domain.resetPolarity.get || resetFact.release != "sync" ||
        !resetFact.destination.exists(_ eq domain)
      then return false
      val allowed = mutable.HashSet.empty[ExpressionRef]
      def expressionIdentity(value: KernelExpr[?], reference: ExpressionRef): Boolean =
        checked(
          reference.module == rootHandle && Option(expressionIds.get(value)).contains(reference) &&
            expressionValues.get(reference).exists(_ eq value)
        ) &&
          checked(
            value.constructionFinalized && value.typeFinalized && value.interfaceMember.isEmpty && value.materializationIntent.isEmpty
          ) &&
          checked(
            semanticResult.exists(_.expressionPaths.contains(reference.module -> reference.index))
          )
      reset match
        case value: KernelExpr[?] =>
          val reference = Option(expressionIds.get(value))
          if reference.isEmpty || !expressionIdentity(value, reference.get) then return false
          charge(1L + value.operands.size.toLong)
          if value.operation != Some("reset_boundary") || value.literal.nonEmpty ||
            value.operands != Vector(domain.reset) || value.resultDescriptor != Some(
              KernelTypeDescriptor("Reset")
            )
          then return false
          allowed += reference.get
        case _ => return false
      val declarationsValid = every(module.declarations): declaration =>
        checked(declaration.reference.module == rootHandle && Option(
          declarationIds.get(declaration.value)
        ).contains(declaration.reference)) &&
          checked(module.declarations.lift(declaration.reference.index).exists(_ eq declaration)) &&
          checked(
            declaration.kind == KernelSignalKind.Input && declaration.attributes.isEmpty && declaration.conservativeDimensions.isEmpty
          ) &&
          checked(declaration.domainCandidate.forall(_ eq domain)) &&
          checked(semanticResult.exists(result =>
            result.declarationNames.contains(
              rootHandle -> declaration.reference.index
            ) && result.declarationPaths.contains(rootHandle -> declaration.reference.index)
          )) &&
          declaration.dataType.exists(dataType =>
            if declaration.reference == clockReference.get || declaration.reference == resetReference.get
            then
              scalar(CandidateRuntime.typeDescriptor(dataType))
            else data(dataType)
          )
      if !declarationsValid || !checked(shapeIndices.nonEmpty) then return false
      val indicesValid = every(shapeIndices): (reference, index) =>
        charge(
          1L + index.indices.size.toLong + index.resultDimensions.size.toLong + index.sourceUses.size.toLong
        )
        index.reference == reference && index.conservative.isEmpty && index.generatedScope.isEmpty &&
        index.sourceUses.isEmpty && index.indices == Vector(0) && index.resultDimensions.isEmpty &&
        (index.input match
          case input: DeclarationRef if input.module == rootHandle =>
            val declaration = module.declarations.lift(input.index)
            val expression = expressionValues.get(reference)
            declaration.nonEmpty && expression.nonEmpty && {
              val value = expression.get
              val inputValue = declaration.get.value
              charge(1L + value.operands.size.toLong + value.constructionDependencies.size.toLong)
              expressionIdentity(
                value,
                reference
              ) && value.registeredConstructionOwnedBy(ConstructionSession.this) &&
              value.constructionKind.exists(
                DigitalReductionContract.Kinds
              ) && value.constructionCallback.nonEmpty &&
              value.constructionDependencies == Vector(inputValue) && value.operation.contains(
                "shape_index"
              ) &&
              value.literal.isEmpty && value.operands == Vector(inputValue, 0) &&
              scalar(value.resultDescriptor.getOrElse(KernelTypeDescriptor("invalid"))) &&
              semanticOrigin.retainedExpressionSlot(
                value.constructionSourceSlot,
                rootHandle,
                reference.index,
                value,
                value.operands
              ) &&
              declaration.get.dataType.exists: dataType =>
                val descriptor = CandidateRuntime.typeDescriptor(dataType)
                charge(1L + descriptor.arguments.size.toLong)
                val valid = descriptor.kind == "Vec" && (descriptor.arguments match
                  case Vector(element: DataType[?], dimensions: Seq[?]) =>
                    val iterator = dimensions.iterator
                    charge(1L)
                    val one = iterator.hasNext && { charge(1L); iterator.next() == 1 }
                    !iterator.hasNext && one && value.resultDescriptor.contains(
                      CandidateRuntime.typeDescriptor(element)
                    )
                  case _ => false)
                if valid then allowed += reference
                valid
            }
          case _ => false)
      indicesValid && every(expressionValues)((reference, value) =>
        checked(allowed.contains(reference)) && expressionIdentity(value, reference)
      ) && checked(allowed.size == expressionValues.size)

    def publication[A](root: Module, semantic: SemanticOriginResult)(body: => A): A =
      if !incremental || !constructionMode then
        finalNames(semantic)
        body
      else
        val bound = if boundDomainAccountingCandidate then guard(exactBoundDomainRaw()) else None
        val exact = bound.nonEmpty || guard(exactPublicationEligible(root))
        if !exact then
          finalNames(semantic)
          body
        else
          guard:
            withBoundDomainAccounting(bound.nonEmpty):
              bound.foreach(requireBoundDomainExport(_, publication = true))
              finalNames(semantic, exactPublication = true)
              val previous = publicationAccountingActive
              publicationAccountingActive = true
              try body
              finally publicationAccountingActive = previous

    def preparedAutomaticInputs(expectedRoot: Long)
        : (Map[DomainRef, String], Vector[DataType[?]]) = guard:
      charge(1L)
      if !incremental || !constructionMode || expectedRoot != rootHandle ||
        !boundaryClosureOwner.exists(_ eq this)
      then reject("automatic preparation belongs to another closure")
      verifyRegionSignatures()
      verifyStatic()
      discover()
      if !drained || running || selectedJob.nonEmpty || queue.nonEmpty || queued.nonEmpty ||
        builtRevision != revision
      then reject("automatic preparation changed after its closed construction drain")
      resolved -> interfaceFormalTypes

    def chargeFinalPreparation(): Unit = if incremental then
      guard:
        prechargeCanonicalPass()

    def enable(budget: Int): Unit =
      if drained || incremental || budget < 1 || budget > 65536 then
        reject("invalid incremental closure activation")
      incremental = true
      remaining = budget.toLong
      chargeConstructionActivation(this, expressionValues.size.toLong)
      expressionValues.foreach((reference, value) => captured(reference, value))

    private[ConstructionSession] def enableDiagnostic(root: Module, carrier: KernelExpr[?]): Unit =
      guard:
        if drained || incremental || !boundaryClosureOwner.exists(_ eq this) then
          reject("invalid diagnostic closure activation")
        val policy = requireDiagnosticRequest(root, carrier)
        incremental = true
        remaining = policy.limit.toLong
        chargeConstructionActivation(this, expressionValues.size.toLong)
        expressionValues.foreach((reference, value) => captured(reference, value))

    def queryForCurrentEpoch(): StructuralParameterBinding.Query = guard:
      if !incremental || currentSnapshot.isEmpty then reject("no incremental boundary snapshot")
      new StructuralParameterBinding.Query(currentSnapshot.get, meter = currentMeter)

    def drainIncremental(): Unit = guard:
      if !incremental || running then reject("boundary closure drain is not reentrant")
      running = true
      try
        verifyRegionSignatures()
        verifyStatic()
        discover()
        if !drained || queue.nonEmpty || builtRevision != revision then
          var closed = false
          while !closed do
            charge(1L)
            val previousRevision = revision
            val previousVerified = verified.size
            val previousReady = queue.size
            refresh()
            while queue.nonEmpty do
              val (kind, key, record) = queue.removeHead()
              queued.remove(kind -> key)
              refresh()
              selectedJob = Some(kind -> key)
              val base = currentSnapshot.get
              val queries = currentQueries.get
              kind match
                case "conservative" => materializeConservativeIntents(base)
                case "digital" => materializeInstanceDigitalTypes(queries, base)
                case "inout" => materializeInstanceDigitalInouts(queries)
                case "interface" => materializeInstanceInterfaceDimensions(queries, base)
                case "member" => materializeGeneratedMemberTypes(queries, base)
                case "resolved" => materializeResolvedInterfaceViews()
                case "construction" =>
                  val expression = record.asInstanceOf[KernelExpr[Data]]
                  if constructionPayloadAvailable(expression) then
                    reject("construction queue lost its pending cell")
                  val reference = expressionIds.get(expression)
                  if !ordinaryRootConstructionContext || currentModule.handle != rootHandle ||
                    reference.module != rootHandle || expressionRegions.contains(reference)
                  then reject("C2a construction lost its original root context")
                  val input = expression.constructionDependencies match
                    case Vector(value: Expr[?]) => value.asInstanceOf[Expr[Vec[Data]]]
                    case _ => reject("construction lost its actual input")
                  val (_, dimensions) = DigitalCollectionContract.vector(input)
                  completeReductionSingleton(expression, input, dimensions.map(_ => 0))
                  markDirty()
                case _ => reject("unknown boundary closure work")
              selectedJob = None
              verified.update(kind -> key, record)
              discover()
            // The incremental visitor charges every visit/edge, including cached entries.
            // Inventory scans remain prepaid; it does not run a whole snapshot/proof pass.
            prechargeCanonicalPass(quadratic = false)
            materializeDeferredResultTypes()
            discover()
            charge(expressionValues.size.toLong)
            val pendingTypes = expressionValues.valuesIterator.exists(!_.typeFinalized)
            if queue.isEmpty && !pendingTypes then
              val before = regionSignatureRevision
              // Empty proof registries cannot perform nested region/type proof traversals.
              // loopSnapshots still scans use inventories, charged at its shared helper.
              charge(records.size.toLong)
              val hasRegions = allValueRegions.hasNext ||
                records.valuesIterator.exists(_.generatedRegions.nonEmpty) ||
                flatFsms.nonEmpty || enumSwitches.nonEmpty || structuralIfs.nonEmpty ||
                behavioralIfs.nonEmpty
              prechargeCanonicalPass(quadratic = hasRegions)
              val _ = collectionSnapshots()
              val _ = loopSnapshots()
              val _ = structuralIfSnapshots()
              val _ = generatedSnapshots()
              val _ = behavioralIfSnapshots()
              val _ = flatFsmSnapshots()
              val _ = enumSwitchSnapshots()
              discover()
              closed = before == regionSignatureRevision && queue.isEmpty
              if closed then
                refresh()
                discover()
                charge(expressionValues.size.toLong)
                closed = queue.isEmpty && !expressionValues.valuesIterator.exists(!_.typeFinalized)
                if closed then
                  freezeRegionSignatures()
                  freezeStatic()
                  drained = true
            if !closed && revision == previousRevision && verified.size == previousVerified &&
              queue.size == previousReady
            then
              reject("boundary closure made no progress")
      finally
        selectedJob = None
        running = false

    def drainInitial(): Unit =
      if drained then
        fail("NODAL-HIERARCHY-058-CLOSURE", "initial boundary closure may drain only once")
      drained = true
      if operations.exists(_.kind == "instance-node-array") || instanceDigitalPorts.nonEmpty ||
        instanceInterfacePorts.nonEmpty || instanceDigitalInouts.nonEmpty ||
        expressionValues.valuesIterator.exists(generatedMemberRecord(_).nonEmpty)
      then
        val base = ConstructionSnapshot(
          root = modulePath(rootHandle),
          modules = snapshots(resolved),
          parameterExpressions = parameterExpressionSnapshots(interfaceFormalTypes),
          generatedRegions = generatedSnapshots(staticDependenciesOnly = true),
          staticPredicates = staticPredicateSnapshots(),
          structuralIfs = structuralIfSnapshots(staticDependenciesOnly = true),
          interfaceAbi = Vector.empty,
          resolvedNets = Vector.empty,
          topology = Vector.empty
        )
        materializeConservativeIntents(base)
        val boundaryQueries = new StructuralParameterBinding.InstanceQueries(base)
        materializeInstanceDigitalTypes(boundaryQueries, base)
        materializeInstanceDigitalInouts(boundaryQueries)
        materializeInstanceInterfaceDimensions(boundaryQueries, base)
        materializeGeneratedMemberTypes(boundaryQueries, base)
      materializeResolvedInterfaceViews()
      materializeDeferredResultTypes()

  private var boundaryClosureOwner: Option[BoundaryClosureOwner] = None

  private def closureAccepts(kind: String, key: Any): Boolean =
    boundaryClosureOwner.forall(_.accepts(kind, key))

  def boundaryWorkActive: Boolean = boundaryClosureOwner.exists(_.incremental)

  def chargeBoundaryWork(units: Long): Unit = closureCharge(units)

  def rejectNestedBoundaryElaboration(): Nothing = boundaryClosureOwner.filter(_.incremental) match
    case Some(owner) => owner.reject("nested elaboration is outside incremental boundary closure")
    case None => throw new IllegalStateException("nested boundary guard requires its active owner")

  // Scoped to an audited construction-mode snapshot, never an eager/C1-wide meter.
  private var snapshotAccountingActive = false
  private var publicationAccountingActive = false
  private def exportAccountingActive: Boolean =
    snapshotAccountingActive || publicationWorkActive

  def publicationAccountingEnabled: Boolean = publicationWorkActive
  def chargePublicationWork(units: Long): Unit = publicationCharge(units)
  private def publicationCharge(units: Long): Unit =
    if publicationWorkActive then closureCharge(units)
  private def publicationElements[A](values: IterableOnce[A]): Iterator[A] =
    val underlying = values.iterator
    if !publicationWorkActive then underlying
    else
      new Iterator[A]:
        def hasNext: Boolean = underlying.hasNext
        def next(): A =
          publicationCharge(1L)
          underlying.next()
  private def publicationOperationVector: Vector[Operation] =
    publicationCharge(operations.size.toLong * 2L)
    operations.toVector

  private def publicationVector[A](values: Vector[A]): Vector[A] =
    publicationCharge(values.size.toLong)
    values
  private def publicationProbe[A](body: => A): A =
    publicationCharge(1L)
    body
  private def publicationStringOrdering: Ordering[String] =
    if !publicationWorkActive then Ordering.String
    else
      new Ordering[String]:
        def compare(left: String, right: String): Int =
          publicationCharge(1L + left.length.toLong + right.length.toLong)
          left.compareTo(right)

  def chargeSnapshotWork(units: Long): Unit = snapshotCharge(units)

  private def snapshotCharge(units: Long): Unit =
    if exportAccountingActive then closureCharge(units)

  private def snapshotElements[A](values: IterableOnce[A]): Iterator[A] =
    val underlying = values.iterator
    if !exportAccountingActive then underlying
    else
      new Iterator[A]:
        def hasNext: Boolean = underlying.hasNext
        def next(): A =
          snapshotCharge(1L)
          underlying.next()

  private def snapshotSeqVector[A](values: Seq[A]): Vector[A] =
    if exportAccountingActive then snapshotElements(values).toVector else values.toVector

  private def snapshotTraversal[A](body: => A): A =
    if exportAccountingActive then closureDescriptorVisit(body) else body

  private def snapshotProbe[A](body: => A): A =
    snapshotCharge(1L)
    body

  private def snapshotStringOrdering: Ordering[String] =
    if !exportAccountingActive then Ordering.String
    else
      new Ordering[String]:
        def compare(left: String, right: String): Int =
          snapshotCharge(1L + left.length.toLong + right.length.toLong)
          left.compareTo(right)

  private def closureCharge(units: Long): Unit = boundaryClosureOwner.foreach(_.charge(units))

  private def closurePathAppend(prefix: String, suffix: String): String =
    closureCharge(1L + prefix.length.toLong + suffix.length.toLong)
    prefix + suffix

  private def closureElements[A](values: IterableOnce[A]): Iterator[A] =
    values.iterator.map: value =>
      closureCharge(1L)
      value

  private var closureDescriptorDepth = 0
  private def closureDescriptorVisit[A](body: => A): A = boundaryClosureOwner match
    case Some(owner) if owner.incremental =>
      owner.charge(1L)
      if closureDescriptorDepth >= 128 then
        owner.reject("recursive descriptor traversal exceeds the closure depth budget")
      closureDescriptorDepth += 1
      try body
      finally closureDescriptorDepth -= 1
    case _ => body

  private def closureMeter: StructuralParameterBinding.WorkMeter =
    boundaryClosureOwner.map(_.meter).getOrElse(StructuralParameterBinding.WorkMeter.Unmetered)

  private def enableIncrementalBoundaryClosure(root: Module, budget: Int): BoundaryClosureOwner =
    rejectLegacyDiagnosticActivation()
    if boundaryClosureOwner.nonEmpty then
      fail("NODAL-HIERARCHY-058-CLOSURE", "closure already exists")
    val closure = new BoundaryClosureOwner(moduleHandle(root), Map.empty, Vector.empty)
    boundaryClosureOwner = Some(closure)
    closure.enable(budget)
    closure

  // Private Session-only admission for the focused C1 harness. No collection calls this.
  def beginIncrementalBoundaryClosure(root: Module, budget: Int): Unit =
    val closure = enableIncrementalBoundaryClosure(root, budget)
    closure.prepareIncrementalInputs()
    closure.drainIncremental()

  def drainIncrementalBoundaryClosure(): Unit = boundaryClosureOwner.get.drainIncremental()

  def currentBoundaryEpochQuery(): StructuralParameterBinding.Query =
    boundaryClosureOwner.get.queryForCurrentEpoch()

  def finish(root: Module): (Emission, ConstructionSnapshot) =
    constructorFailure.foreach: (failure, site) =>
      fail(
        "NODAL-CONSTRUCTOR-LIFECYCLE-021",
        s"captured constructor failure invalidated this transaction: ${failure.getClass.getName}",
        Some(site)
      )
    val activateConstructionClosure =
      (registeredConstructionAdmission || diagnosticConstructionPolicy.nonEmpty) &&
        boundaryClosureOwner.isEmpty
    val automaticConstructionClosure =
      if activateConstructionClosure then
        Some(if diagnosticConstructionPolicy.nonEmpty then
          enableDiagnosticConstructionClosure(root, None)
        else enableIncrementalBoundaryClosure(root, 65536))
      else None
    closureCharge(expressionValues.size.toLong)
    val pendingConstruction = expressionValues.valuesIterator.exists(!_.constructionFinalized)
    if pendingConstruction then
      closureCharge(expressionValues.size.toLong)
      expressionValues.valuesIterator.foreach: expression =>
        if !expression.constructionFinalized then
          if !expression.pendingConstructionOwnedBy(this) then
            fail(
              "NODAL-HIERARCHY-058-CONSTRUCTION",
              "unsealed construction cannot leave this session"
            )
          val _ = constructionPayloadAvailable(expression)
    finalizeStreamReady()
    if areaStack.nonEmpty then
      fail("NODAL-AREA-058-LIFECYCLE", "construction closed with an active Area")
    prepareAreaNames()
    val rootHandle = moduleHandle(root)
    if moduleStack.size != 1 || moduleStack.last.handle != rootHandle then
      fail("NODAL-LIFECYCLE-017", "construction closed with an unattached child Module")
    if pendingConstruction || activateConstructionClosure then
      if domainStack.nonEmpty then
        fail("NODAL-LIFECYCLE-018", "construction closed with a lexical domain still active")
      val closure = boundaryClosureOwner.get
      closure.charge(records.size.toLong)
      records.valuesIterator.filterNot(_.attached).foreach: module =>
        fail("NODAL-HIERARCHY-021", s"Module '${module.className}' was not attached")
      closure.prepareIncrementalInputs()
      closure.drainIncremental()
      closure.charge(expressionValues.size.toLong)
      if expressionValues.valuesIterator.exists(!_.constructionFinalized) then
        fail("NODAL-HIERARCHY-058-CONSTRUCTION", "unsealed construction cannot leave this session")
    moduleStack.remove(moduleStack.size - 1)
    if domainStack.nonEmpty then
      fail("NODAL-LIFECYCLE-018", "construction closed with a lexical domain still active")
    closureCharge(records.size.toLong)
    records.values.filterNot(_.attached).foreach: module =>
      fail("NODAL-HIERARCHY-021", s"Module '${module.className}' was not attached")

    closureCharge(records.size.toLong)
    records.valuesIterator.filter(_.externalName.nonEmpty).foreach: module =>
      closureCharge(module.declarations.size.toLong)
      module.declarations.foreach: declaration =>
        closureCharge(declaration.attributes.size.toLong)
        val descriptor = declaration.dataType.map(CandidateRuntime.typeDescriptor)
        val scalar = descriptor.exists(value =>
          value.kind == "Bool" ||
            Set("Bits", "UInt", "SInt").contains(value.kind) && {
              val bounds = DigitalScalarContract.widthBounds(value)
              bounds._1 == bounds._2
            }
        )
        declaration.value match
          case array: InterfaceArray[?, ?]
              if externalInterfaceOrigin(declaration.reference).contains(declaration.reference) =>
            val count = array.formalCount match
              case value: Int => value
              case _ => fail(
                  "NODAL-EXTERNAL-058-001",
                  "external InterfaceArray requires fixed positive literal count"
                )
            ExternalModuleContract.validateArray(
              array.definition,
              array.role,
              count,
              array.externalAttributes.collectFirst { case ("placement", value: InoutPlacement) =>
                value
              }
                .getOrElse(fail(
                  "NODAL-EXTERNAL-058-001",
                  "external InterfaceArray lost placement"
                )),
              array.externalAttributes.collectFirst { case ("profile", value: ResolutionProfile) =>
                value
              }
                .getOrElse(fail("NODAL-EXTERNAL-058-001", "external InterfaceArray lost profile"))
            )
          case _ =>
            if declaration.kind != KernelSignalKind.DigitalInout || !scalar then
              fail(
                "NODAL-EXTERNAL-058-001",
                "external signature requires fixed scalar digital inout pins or exact resolved InterfaceArray",
                Some(declarationPath(declaration.reference))
              )
    val boundDomainInputs = if automaticConstructionClosure.isEmpty then
      boundaryClosureOwner.flatMap(_.tryFinalBoundDomainInputs())
    else None
    val (resolved, interfaceFormalTypes) = automaticConstructionClosure match
      case Some(prepared) => prepared.preparedAutomaticInputs(rootHandle)
      case None if boundDomainInputs.nonEmpty => boundDomainInputs.get
      case None =>
        boundaryClosureOwner.filter(_.incremental) match
          case Some(closure) => closure.chargeFinalPreparation()
          case None => semanticResult = Some(semanticOrigin.resolve())
        val resolved = resolveDomains()
        materializeInstanceControlCaptures(resolved)
        markConservativeBindingDependencies()
        resolved -> prepareInterfaceFormalTypes()
    val closure = boundaryClosureOwner.getOrElse:
      val created = new BoundaryClosureOwner(rootHandle, resolved, interfaceFormalTypes)
      boundaryClosureOwner = Some(created)
      created
    if closure.incremental then closure.drainIncremental() else closure.drainInitial()
    val semantic = if closure.incremental then
      closure.resolveSemanticNames()
    else semanticOrigin.resolve()
    closure.publication(root, semantic):
      semanticResult = Some(semantic)
      def exportMap[A, B](values: Vector[A])(f: A => B): Vector[B] =
        publicationVector(values).map(value => publicationProbe(f(value)))
      def exportFlatMap[A, B](values: Vector[A])(f: A => IterableOnce[B]): Vector[B] =
        if !publicationWorkActive then values.flatMap(f)
        else publicationElements(publicationElements(values).flatMap(f)).toVector
      def exportBufferFlatMap[A, B](values: mutable.ArrayBuffer[A])(
          f: A => IterableOnce[B]
      ): mutable.ArrayBuffer[B] =
        if !publicationWorkActive then values.flatMap(f)
        else publicationElements(publicationElements(values).flatMap(f)).to(mutable.ArrayBuffer)
      extension [A](left: Vector[A])
        def exportConcat(right: IterableOnce[A]): Vector[A] =
          if !publicationWorkActive then left ++ right
          else publicationVector(left) ++ publicationElements(right)

      val sourceByPath = publicationSourceIndex(semantic.sourceMap)
      val collectionArgumentSources =
        publicationElements(publicationElements(collectionArguments.keysIterator).flatMap:
          reference =>
            publicationCharge(1L)
            semantic.expressionPaths.get(
              reference.module -> reference.index
            ).flatMap(sourceByPath.get)
              .map(source => SourceMapEntry(expressionPath(reference), source))).toVector
      val sequentialRegionSources = publicationElements((
        publicationElements(fsmResults.keysIterator).map(reference =>
          publicationProbe(reference -> expressionPath(reference))
        ) ++
          publicationElements(fsmActionRegions.valuesIterator).map(region =>
            region.fsmAction.get.machine -> expressionPath(region.reference)
          ) ++
          publicationElements(enumSwitchArms.valuesIterator).map(region =>
            region.switchArm.get.switch -> expressionPath(region.reference)
          )
      ).flatMap: (reference, path) =>
        semantic.expressionPaths.get(reference.module -> reference.index).flatMap(sourceByPath.get)
          .map(source => SourceMapEntry(path, source))).toVector
      val rootSources =
        publicationElements(publicationElements(operations.iterator).flatMap(operation =>
          publicationCharge(operation.values.size.toLong)
          operation.values.collect {
            case root: InstanceArrayRootRecord => publicationProbe(root)
          }
        )
          .flatMap(root =>
            sourceByPath.get(expressionPath(root.reference)).map(source =>
              SourceMapEntry(pathOfReference(root.reference), source)
            )
          )).toVector
      val endpointSources = publicationElements(shapeIndices.valuesIterator).filter(index =>
        publicationProbe(index.conservative.nonEmpty)
      ).flatMap: index =>
        sourceByPath.get(expressionPath(index.sourceUses.head._1)).map(source =>
          SourceMapEntry(conservativeEndpointPath(index), source)
        )
      val interfaceCapture = DigitalInterfaceContract.capture(
        publicationElements(publicationElements(records.valuesIterator).flatMap(module =>
          publicationElements(module.declarations.iterator).map(declaration =>
            val validatedTypes = new java.util.IdentityHashMap[DataType[?], java.lang.Boolean]()
            DigitalInterfaceContract.Declaration(
              declaration.value,
              declarationPath(declaration.reference),
              modulePath(module.handle),
              publicationProbe(localDeclarationDomain(declaration, resolved)),
              exportMap(publicationProbe(interfaceDimensions(declaration)))(dimension =>
                renderShapeDimension(dimension, module.handle)
              ),
              dataType =>
                inValueScope(module.handle, valueRegion(declaration.reference)):
                  if !validatedTypes.containsKey(dataType) then
                    validateShapeType(dataType, module.handle, cardinality = true)
                    validatedTypes.put(dataType, java.lang.Boolean.TRUE)
                  renderType(dataType, module.handle)
              ,
              dataType =>
                inValueScope(module.handle, valueRegion(declaration.reference)):
                  validateShapeType(
                    dataType,
                    module.handle,
                    cardinality = true,
                    storageContext = true
                  )
              ,
              value =>
                if CandidateRuntime.expressionDataType(value).exists(isBoundaryCapability) then
                  validateBoundarySource(value, declaration, resolved),
              (left, right) =>
                inValueScope(module.handle, valueRegion(declaration.reference)):
                  val analysis = new StructuralBoundAnalysis(
                    module.handle,
                    "NODAL-DIGITAL-054-001",
                    "digital interface payload"
                  )
                  DigitalEnumContract.sameType(
                    CandidateRuntime.typeDescriptor(left),
                    CandidateRuntime.typeDescriptor(right),
                    Some(analysis.sameIdentity)
                  )
              ,
              instanceInterfacePorts.get(declaration.reference).map: binding =>
                interfaceBoundarySnapshot(module.handle, binding),
              dataType =>
                val dimensions = publicationProbe(interfaceDimensions(declaration))
                val actual = interfaceActualType(declaration.reference, dataType)
                if dimensions.isEmpty then actual
                else Vec(actual, dimensions*)
              ,
              dataType => interfaceActualType(declaration.reference, dataType),
              instanceInterfacePorts.get(declaration.reference).map: binding =>
                dataType => renderType(dataType, binding.port.module), {
                val dimensions = publicationProbe(interfaceDimensions(declaration))
                if dimensions.isEmpty then Bool else Vec(Bool, dimensions*)
              },
              definition =>
                val definitionOwner = instanceInterfacePorts.get(declaration.reference)
                  .map(_.port.module).getOrElse(module.handle)
                requireInterfaceDefinitionContext(definition, definitionOwner).map: context =>
                  KernelInterfaceDefinitionContextSnapshot(
                    "",
                    definition.name,
                    modulePath(definitionOwner),
                    expressionPath(context.region.reference),
                    context.sourceOrdinal
                  )
              ,
              Some((definition, dataType) =>
                val definitionOwner = instanceInterfacePorts.get(declaration.reference)
                  .map(_.port.module).getOrElse(module.handle)
                withInterfaceDefinitionScope(definition, definitionOwner):
                  validateShapeType(dataType, definitionOwner, cardinality = true)
                  renderType(dataType, definitionOwner)
              ),
              publicationProbe(externalInterfaceOrigin(declaration.reference)).map(reference =>
                publicationProbe(declarationPath(reference))
              )
            )
          )
        )).toVector,
        exportMap({
          publicationCharge(operations.size.toLong)
          operations.toVector
        })(operation =>
          DigitalInterfaceContract.Operation(
            operation.kind,
            if Set("interface-connect", "interface-drive", "interface-observe")(operation.kind) then
              operation.values.map:
                case value: Expr[?] => bindCollectionValue(
                    value,
                    operation.digitalRegion,
                    operation.owner,
                    create = false
                  )
                case value => value
            else operation.values,
            modulePath(operation.owner),
            operation.digitalRegion.map(region => expressionPath(region.reference)),
            Option.when(Set(
              "interface-connect",
              "interface-drive",
              "interface-observe"
            )(operation.kind) &&
              (operation.digitalRegion.nonEmpty || operation.values.exists:
                case endpoint: InterfaceEndpoint =>
                  val root = endpoint match
                    case value: InterfacePort[?, ?] => DigitalInterfaceContract.root(value)
                    case value: InterfaceArray[?, ?] => DigitalInterfaceContract.root(value)
                  Option(declarationIds.get(root)).exists(reference =>
                    instanceInterfacePorts.get(reference).exists(_.generated.nonEmpty)
                  )
                case _ => false))(operation.sourceOrdinal)
          )
        ),
        pathOf,
        value =>
          value match
            case reference: AnyRef =>
              Option(declarationIds.get(reference)).map(_.module)
                .orElse(Option(expressionIds.get(reference)).map(_.module)).map(modulePath)
            case _ => None
        ,
        publicationElements(publicationElements(expressionValues.iterator).collect {
          case (reference, expression: KernelExpr[?]) if expression.interfaceMember.nonEmpty =>
            expression -> modulePath(reference.module)
        }).toVector
      )
      val abi = interfaceCapture.abi
      val arrayBindings = publicationProbe(conservativeArrayBindingRecords)
      val topologyEdges = publicationProbe(topologyRecords())
      val recordedAnalogSemantics = publicationProbe(analogSemanticSnapshot())
      val analogSemantics =
        if recordedAnalogSemantics.components.isEmpty && recordedAnalogSemantics.states.isEmpty then
          recordedAnalogSemantics
        else
          recordedAnalogSemantics.copy(
            parameters = records.valuesIterator.flatMap: module =>
              module.declarations.iterator.collect:
                case declaration if declaration.value.isInstanceOf[Param[?]] =>
                  val parameter = declaration.value.asInstanceOf[Param[?]]
                  AnalogEquationRuntime.ParameterReference(
                    parameter,
                    modulePath(module.handle),
                    declarationPath(declaration.reference),
                    inferAnalogDimension(parameter).signature
                  )
            .toVector.sortBy(_.path)
          )
      publicationProbe(verifySequentialAssignments(resolved, interfaceCapture, rootHandle))
      val netSnapshots = publicationProbe(resolvedNets())
      val digitalCapture = publicationProbe(digitalValueSnapshots(interfaceCapture, netSnapshots))
      val modules = publicationProbe(snapshots(resolved))
      val assignmentRecords = publicationProbe(digitalAssignmentRecords(resolved))
      val assignmentSources = exportFlatMap(assignmentRecords): (assignment, record) =>
        boundDomainCharge(3L) // declaration identity/slot lookup and empty attributes dispatch
        val syntheticDefault =
          Option(declarationIds.get(record.values.head.asInstanceOf[AnyRef])).exists: reference =>
            records(reference.module).declarations(reference.index).attributes.exists:
              case ("stream_ready_default", value: Expr[?]) =>
                value eq record.values(1).asInstanceOf[AnyRef]
              case _ => false
        boundDomainCharge() // exact retained source; synthetic fallback is not evaluated
        val source = record.source.orElse(
          Option.when(syntheticDefault)(
            sourceByPath.get(assignment.target).orElse(sourceByPath.get(assignment.value))
          ).flatten
        )
        boundDomainCharge() // source entry construction; exportFlatMap owns visits/copies
        source.map(SourceMapEntry(assignment.path, _))
      // A member projection is generated from its actual endpoint declaration. Preserve any
      // more precise captured leaf span; otherwise retain that declaration, never invent a line.
      val interfaceMemberSources = exportFlatMap(interfaceCapture.interfaces): endpoint =>
        exportFlatMap(sourceByPath.get(endpoint.path).toVector): source =>
          endpoint.members.filterNot(member => sourceByPath.contains(member.path)).map(member =>
            SourceMapEntry(member.path, source)
          )
      val hierarchyBindingSources =
        publicationElements(publicationElements(selectedRequirementBindings.iterator).flatMap:
          case (requirement, (_, origin, owner, ordinal)) =>
            val instance = records(owner).instances(ordinal)
            val requirementRecord = records(requirement.module).domains(requirement.index)
            val source = if origin == "named" then
              instance.namedBindingSources.get(requirementRecord.domain)
            else if origin == "default" then instance.defaultBindingSource
            else sourceByPath.get(instancePath(owner, ordinal))
            source.map(span =>
              SourceMapEntry(
                boundDomainBindingPath(instancePath(owner, ordinal), requirementRecord.name),
                span
              )
            )).toVector
      publicationCharge(1L)
      val snapshot = ConstructionSnapshot(
        emitQuality = options.quality,
        digitalAssignments = exportMap(assignmentRecords)(_._1),
        eventProcesses = publicationProbe(eventProcessSnapshots),
        memoryWrites = exportMap(publicationProbe(memoryWriteRecords))(_._1),
        interfaceContracts = interfaceCapture.interfaces,
        digitalConnectivity = interfaceCapture.connectivity,
        enumDefinitions = digitalCapture._1,
        digitalExpressions = digitalCapture._2,
        digitalCollections = publicationProbe(collectionSnapshots()),
        digitalLoops = publicationProbe(loopSnapshots()),
        iterationIndices = publicationProbe(iterationIndexSnapshots()),
        staticPredicates = publicationProbe(staticPredicateSnapshots()),
        structuralIfs = publicationProbe(structuralIfSnapshots()),
        behavioralIfs = publicationProbe(behavioralIfSnapshots()),
        flatFsms = publicationProbe(flatFsmSnapshots()),
        enumSwitches = publicationProbe(enumSwitchSnapshots()),
        root = modulePath(rootHandle),
        rootParameterBindings = exportMap({
          publicationCharge(rootParameterBindings.size.toLong)
          rootParameterBindings.toVector
        }):
          case (parameter, value) =>
            val reference = parameter match
              case candidate: AnyRef => Option(declarationIds.get(candidate)).getOrElse(
                  fail(
                    "NODAL-PARAMETER-BINDING-016",
                    "root constructor binding targets an unknown parameter"
                  )
                )
              case _ =>
                fail(
                  "NODAL-PARAMETER-BINDING-016",
                  "root constructor binding is not a declaration"
                )
            declarationName(reference) -> renderAny(value, rootHandle),
        parameterExpressions =
          publicationProbe(parameterExpressionSnapshots(interfaceCapture.dataTypes)),
        modules = modules,
        interfaceAbi = abi,
        resolvedNets = netSnapshots,
        topology = exportMap(topologyEdges)(_._1),
        names = semantic.names,
        origins = semantic.origins,
        generatedNames = semantic.generatedNames,
        sourceMap =
          publicationVector(
            (semantic.sourceMap.exportConcat(exportBufferFlatMap(eventProcesses)(process =>
              process.source.map(source =>
                publicationProbe(SourceMapEntry(eventProcessPath(process), source))
              )
            )).exportConcat(hierarchyBindingSources).exportConcat(assignmentSources)
              .exportConcat(interfaceMemberSources).exportConcat(exportFlatMap(
                publicationProbe(memoryWriteRecords)
              )((write, record) =>
                record.source.map(source => publicationProbe(SourceMapEntry(write.path, source)))
              )).exportConcat(collectionArgumentSources).exportConcat(sequentialRegionSources)
              .exportConcat(rootSources).exportConcat(endpointSources).exportConcat(exportFlatMap(
                topologyEdges
              )((edge, record) =>
                edge.path.flatMap(path =>
                  record.source.map(source =>
                    publicationProbe(SourceMapEntry(path, source))
                  )
                )
              )).exportConcat(exportFlatMap(arrayBindings)((binding, record) =>
                record.source.map(source => publicationProbe(SourceMapEntry(binding.path, source)))
              )).exportConcat(publicationProbe(AnalogUserFunctionRuntime.sourceMap(
                publicationProbe(userFunctions.snapshots(modulePath))
              ))).exportConcat(publicationProbe(AnalogEquationRuntime.sourceMap(analogSemantics))))
          ).sortBy(entry => publicationProbe(entry.semanticPath))(using publicationStringOrdering),
        analogRegions = publicationProbe(analogSnapshots()),
        continuousOperators = publicationProbe(continuousOperatorSnapshots(semantic.sourceMap)),
        waveformOperators = publicationProbe(waveformOperatorSnapshots(semantic.sourceMap)),
        noiseOperators = publicationProbe(noiseOperatorSnapshots(semantic.sourceMap)),
        transferOperators = publicationProbe(transferOperatorSnapshots(semantic.sourceMap)),
        analogFunctions = publicationProbe(userFunctions.snapshots(modulePath)),
        analogSemantics = analogSemantics,
        analogProcedural = publicationProbe(AnalogProceduralConstruction.snapshots(
          module => modulePath(moduleHandle(module)),
          canonicalGeneratedOwnership
        )),
        waivers = publicationProbe(waiverSnapshots(semantic.sourceMap)),
        generatedRegions = publicationProbe(generatedSnapshots()),
        areas = publicationProbe(areaSnapshots(resolved)),
        shapeIndices = publicationProbe(shapeIndexSnapshots()),
        shapeViews = publicationProbe(shapeViewSnapshots()),
        conservativeArrayBindings = exportMap(arrayBindings)(_._1),
        instanceArrayRoots = publicationProbe(instanceArrayRootSnapshots())
      )
      publicationProbe(finalizeConservativeArrayBindings(snapshot))
      publicationProbe(ExternalModuleContract.validate(snapshot))
      val kind = publicationProbe(classify(snapshot))
      publicationCharge(1L)
      val report = DesignReport(
        designKind = kind,
        selectedBackend = options.backend,
        digitalProfile =
          if kind == DesignKind.AnalogOnly || kind == DesignKind.Unsupported then None
          else Some(options.digitalProfile),
        interfaceAbi = abi,
        sourceMap = snapshot.sourceMap,
        schedules = Vector.empty
      )
      publicationProbe(closure.verifyPublication())
      publicationProbe(Emission(Vector.empty, report) -> snapshot)

  // Metadata lives on canonical reference identities, never in a second provenance graph.
  private var constructionAllocationSerial = 0L
  private var constructionRequestWork = 0L

  private def requestCharge(units: Long = 1L): Unit = boundaryClosureOwner match
    case Some(owner) if owner.incremental => owner.charge(units)
    case _ =>
      if units < 0L || units > 65536L - constructionRequestWork then
        try collectionFailure("construction request work exceeds the session budget")
        catch
          case failure: Throwable =>
            poisonConstructor(failure, "construction request")
            throw failure
      constructionRequestWork += units

  private def stampAllocation[A <: AllocationIdentity](module: ModuleRecord, ref: A): A =
    if constructionAllocationSerial == Long.MaxValue then
      collectionFailure("canonical construction allocation serial is exhausted")
    constructionAllocationSerial += 1L
    ref.stampConstructionAllocation(ConstructionAllocation(
      this,
      module,
      constructionAllocationSerial,
      activeValueRegion,
      activeGeneratedRegion,
      activeArea
    ))
    ref

  private def requestReference(value: AnyRef): DeclarationRef | ExpressionRef =
    Option(declarationIds.get(value)).getOrElse(
      Option(expressionIds.get(value)).getOrElse(
        collectionFailure("construction request requires an existing canonical input")
      )
    )

  private def requireRequestIdentity(root: ConstructionRootIdentity, frontier: Long): Unit =
    requestCharge()
    val ref = root.reference
    val module = currentModule
    val stamp = ref.constructionAllocation.getOrElse(
      collectionFailure("construction input has no canonical allocation stamp")
    )
    if (stamp.session ne this) || stamp.serial > frontier || (stamp.module ne module) ||
      !records.get(stamp.module.handle).exists(_ eq stamp.module)
    then collectionFailure("construction input is later or belongs to another session")
    val canonical = ref match
      case declaration: DeclarationRef =>
        Option(declarationIds.get(root.value)).exists(_ eq declaration) &&
        declaration.module == stamp.module.handle &&
        stamp.module.declarations.lift(declaration.index).exists(record =>
          (record.reference eq declaration) && (record.value eq root.value)
        )
      case expression: ExpressionRef =>
        Option(expressionIds.get(root.value)).exists(_ eq expression) &&
        expression.module == stamp.module.handle &&
        expressionValues.get(expression).exists(_ eq root.value)
    if !canonical then collectionFailure("construction input lost its canonical identity")
    // Physical allocation context may differ from the canonical value scope for lifted static
    // expressions. Validate both, without treating a child declaration as a parent proxy.
    stamp.region.foreach: region =>
      if region.reference.module != module.handle || !allValueRegions.exists(candidate =>
          requestCharge()
          candidate eq region
        )
      then collectionFailure("construction input lost its allocation region")
    stamp.generation.foreach: generation =>
      if generation.owner != module.handle || !module.generatedRegions.exists(candidate =>
          requestCharge()
          candidate eq generation
        )
      then collectionFailure("construction input lost its allocation generation")
    stamp.area.foreach: area =>
      if area.owner != module.handle || !areas.exists(candidate =>
          requestCharge()
          candidate eq area
        )
      then collectionFailure("construction input lost its allocation Area")
      area.value.foreach: value =>
        requestCharge()
        if !Option(areaRecords.get(value)).exists(_ eq area) then
          collectionFailure("construction input lost its canonical Area value")
    // Prepay the existing valueRegion generated membership scans; do not build another index.
    requestCharge(1L + module.generatedRegions.size.toLong)
    module.generatedRegions.foreach: generation =>
      requestCharge(1L + generation.declarations.size.toLong)
    if valueRegion(ref).nonEmpty then
      collectionFailure("construction input is not available in its original root scope")

  /** Snapshot original value dependencies only. Finalized boundary types remain owned by C2a's
    * current-epoch materializer; they are not new authored input roots or frontier exemptions.
    */
  private def requestRoots(input: Expr[?], frontier: Long): Vector[ConstructionRootIdentity] =
    val result = Vector.newBuilder[ConstructionRootIdentity]
    var rootCount = 0L
    val active = new java.util.IdentityHashMap[AnyRef, java.lang.Boolean]()
    val done = new java.util.IdentityHashMap[AnyRef, java.lang.Boolean]()
    val pending = mutable.ArrayBuffer.empty[(Any, Boolean)]
    pending += input -> false
    def enter(identity: AnyRef, leaving: Boolean)(children: => Iterator[?]): Unit =
      if leaving then
        active.remove(identity)
        done.put(identity, java.lang.Boolean.TRUE)
      else
        if active.containsKey(identity) then
          collectionFailure("construction input dependency cycle")
        if !done.containsKey(identity) then
          active.put(identity, java.lang.Boolean.TRUE)
          pending += identity -> true
          pending += ConstructionRequestChildren(children) -> false
    while pending.nonEmpty do
      requestCharge()
      val (value, leaving) = pending.remove(pending.size - 1)
      value match
        case children: ConstructionRequestChildren =>
          if children.values.hasNext then
            requestCharge()
            val next = children.values.next()
            pending += children -> false
            pending += next -> false
        case expression: Expr[?] =>
          val identity = expression.asInstanceOf[AnyRef]
          if !leaving && !done.containsKey(identity) && !active.containsKey(identity) then
            val root = ConstructionRootIdentity(identity, requestReference(identity))
            requireRequestIdentity(root, frontier)
            requestCharge()
            result += root
            rootCount += 1L
          enter(identity, leaving):
            expression match
              case kernel: KernelExpr[?] =>
                if kernel.constructionFinalized then kernel.operands.iterator
                else
                  if !kernel.registeredConstructionOwnedBy(this) then
                    collectionFailure("construction input contains an orphan pending cell")
                  kernel.constructionDependencies.iterator
              case _ => Iterator.empty
        case sequence: Seq[?] => enter(sequence, leaving)(sequence.iterator)
        case optional: Option[?] => enter(optional, leaving)(optional.iterator)
        case _: Int | _: Long | _: Boolean | _: String | _: Double => ()
        // Type metadata is NOT frontier-certified: the unchanged canonical type/boundary proof
        // owns its dependencies, including later materialized widths for the same boundary.
        case _: DataType[?] => ()
        case _ => collectionFailure("construction input has unsupported root metadata")
    requestCharge(rootCount)
    result.result()

  private def reserveRegisteredConstruction(
      input: Expr[?],
      body: DigitalCollectionCallback.ReductionBody
  ): KernelExpr[Data] =
    DigitalCollectionCallback.requireChecked(body.checked)
    val module = currentModule
    val bound = bindCollectionValue(input, activeValueRegion, module.handle)
    requireRegisteredCollectionOperand(bound, module.handle)
    val frontier = constructionAllocationSerial
    val roots = requestRoots(bound, frontier)
    val result = reserveReductionConstruction(bound, body)
    require(
      result.constructionDependencies.head.asInstanceOf[AnyRef] eq bound,
      "registered reservation must retain its actual bound input"
    )
    result.attachConstructionRequest(ConstructionRequestContext(
      this,
      module,
      bound,
      requestReference(bound),
      expressionIds.get(result),
      frontier,
      roots,
      result.constructionSourceSlot
    ))
    result

  /** Both manual precompletion and queued completion use this exact identity check. */
  private def validateConstructionRequest(result: KernelExpr[?], input: Expr[?]): Boolean =
    if result.constructionKind.nonEmpty then
      val request = result.constructionRequest.getOrElse(
        collectionFailure("registered construction lost its request context")
      )
      if (request.session ne this) || !ordinaryRootConstructionContext ||
        (currentModule ne request.module) || !records.get(
          request.module.handle
        ).exists(_ eq request.module) ||
        (request.input ne input) || (requestReference(input) ne request.inputReference) ||
        request.sourceSlot != result.constructionSourceSlot
      then collectionFailure("registered construction lost its original request context")
      val resultRef = Option(expressionIds.get(result)).getOrElse(
        collectionFailure("registered construction lost its reserved identity")
      )
      val allocation = resultRef.constructionAllocation.getOrElse(
        collectionFailure("registered construction lost its result allocation")
      )
      if (resultRef ne request.resultReference) ||
        (allocation.session ne this) || (allocation.module ne request.module) ||
        allocation.serial <= request.frontier ||
        !expressionValues.get(resultRef).exists(_ eq result)
      then collectionFailure("registered construction changed its reserved identity")
      request.roots.foreach(root => requireRequestIdentity(root, request.frontier))
      val roots = requestRoots(input, request.frontier)
      if roots.size != request.roots.size then
        collectionFailure("registered construction changed its original input roots")
      var index = 0
      while index < roots.size do
        requestCharge()
        val actual = roots(index)
        val original = request.roots(index)
        if (actual.value ne original.value) || (actual.reference ne original.reference) then
          collectionFailure("registered construction changed its original input roots")
        index += 1
    true

  /** Installation itself is inside try. Only the three installed stacks are restored; allocation,
    * work, sealed payloads and canonical registries are deliberately never rolled back.
    */
  private def inConstructionRequest[A](result: KernelExpr[?])(body: => A): A =
    if result.constructionKind.isEmpty then body
    else
      val request = result.constructionRequest.getOrElse(
        collectionFailure("registered construction lost its request context")
      )
      val modules = moduleStack.toVector
      val collections = collectionStack.toVector
      val generations = generationStack.toVector
      try
        installValueScopeChain(request.module.handle, Vector.empty)
        // A real charged installation step also makes partial-install failure independently testable.
        requestCharge()
        body
      finally
        moduleStack.clear(); moduleStack ++= modules
        collectionStack.clear(); collectionStack ++= collections
        generationStack.clear(); generationStack ++= generations

  private def allocateExpressionReference(module: ModuleRecord): ExpressionRef =
    stampAllocation(module, ExpressionRef(module.handle, module.expressionCount))

  private def allocateScaffoldReference(module: ModuleRecord): ExpressionRef =
    stampAllocation(module, ExpressionRef(module.handle, -module.scaffoldExpressionCount - 1))

  private def allocateDeclarationReference(module: ModuleRecord): DeclarationRef =
    stampAllocation(module, DeclarationRef(module.handle, module.declarations.size))

  // Preserve the original JVM entrypoint and the authored body/source anchors above.
  def completeReductionSingleton(
      result: KernelExpr[Data],
      input: Expr[Vec[Data]],
      indices: Vector[Dimension]
  ): Expr[Data] =
    if result.constructionKind.isEmpty then completeReductionSingletonBody(result, input, indices)
    else
      def complete(): Expr[Data] =
        validateConstructionRequest(result, input)
        inConstructionRequest(result)(completeReductionSingletonBody(result, input, indices))
      try
        boundaryClosureOwner match
          case Some(owner) => owner.guard(complete())
          case None => complete()
      catch
        case failure: Throwable =>
          result.failConstruction(this)
          poisonConstructor(failure, "construction request")
          throw failure

  private def chargeConstructionActivation(owner: BoundaryClosureOwner, inventory: Long): Unit =
    owner.charge(constructionRequestWork)
    owner.charge(inventory)

  // Authority is scoped to this canonical selection, never returned to user code.
  private final class DirectPortTarget(
      val request: Option[ConstructionRequestContext],
      val selected: InstanceSelectionRecord,
      val instance: InstanceRecord,
      val parent: ModuleRecord,
      val port: DeclarationRef,
      val value: Signal[?],
      val dataType: DataType[?],
      val roots: Vector[ConstructionRootIdentity]
  ):
    val session: ConstructionSession = ConstructionSession.this
  private final class DirectPortPermit(
      val target: DirectPortTarget,
      val dataType: KernelInstanceDataType[?],
      val kind: KernelSignalKind,
      val name: String
  ):
    var consumed = false
    var value: Option[AnyRef] = None
    var reference: Option[DeclarationRef] = None

  private var directPortRequest: Option[DirectPortTarget] = None
  private var directPortPermit: Option[DirectPortPermit] = None

  private def directPortRootContext(selected: InstanceSelectionRecord): Boolean =
    moduleStack.size == 1 && activeValueRegion.isEmpty && domainStack.isEmpty &&
      areaStack.isEmpty && generationStack.isEmpty && analogStack.isEmpty &&
      analogSemanticContext.isEmpty && activeEventProcess.isEmpty &&
      instanceSelectionStack.size == 1 && (instanceSelectionStack.head eq selected) &&
      selected.generated.isEmpty && !reductionChecking && !reductionProofBuilding &&
      reductionExact.isEmpty && reductionDomains.isEmpty

  private def directPortFailure(message: String): Nothing =
    boundaryClosureOwner.filter(_.incremental) match
      case Some(owner) =>
        owner.guard(collectionFailure("direct Instance request authority: " + message))
      case None => collectionFailure("direct Instance request authority: " + message)

  private def validatePortAllocation(
      value: AnyRef,
      reference: DeclarationRef | ExpressionRef,
      module: ModuleRecord,
      frontier: Long
  ): Unit =
    requestCharge()
    val stamp = reference.constructionAllocation.getOrElse(
      directPortFailure("missing original allocation")
    )
    if (stamp.session ne this) || (stamp.module ne module) || stamp.serial > frontier ||
      stamp.region.nonEmpty || stamp.generation.nonEmpty || stamp.area.nonEmpty ||
      !records.get(module.handle).exists(_ eq module)
    then directPortFailure("later or non-root original allocation")
    val valid = reference match
      case ref: DeclarationRef =>
        Option(declarationIds.get(value)).exists(_ eq ref) && ref.module == module.handle &&
        module.declarations.lift(ref.index).exists(record =>
          (record.reference eq ref) && (record.value eq value)
        )
      case ref: ExpressionRef =>
        Option(expressionIds.get(value)).exists(_ eq ref) && ref.module == module.handle &&
        expressionValues.get(ref).exists(_ eq value)
    if !valid then directPortFailure("original canonical identity changed")

  /** Strict authored type roots only; substituted dimensions remain the boundary owner's proof. */
  private def directPortTypeRoots(
      dataType: DataType[?],
      child: ModuleRecord,
      frontier: Long
  ): Vector[ConstructionRootIdentity] =
    val active = new java.util.IdentityHashMap[AnyRef, java.lang.Boolean]()
    val done = new java.util.IdentityHashMap[AnyRef, java.lang.Boolean]()
    val pending = mutable.ArrayBuffer.empty[(Any, Boolean)]
    val roots = Vector.newBuilder[ConstructionRootIdentity]
    var count = 0L
    pending += dataType -> false
    def enter(value: AnyRef, leaving: Boolean)(children: => Iterator[?]): Unit =
      if leaving then
        active.remove(value)
        done.put(value, java.lang.Boolean.TRUE)
      else
        if active.containsKey(value) then directPortFailure("authored type-root cycle")
        if !done.containsKey(value) then
          active.put(value, java.lang.Boolean.TRUE)
          pending += value -> true
          pending += ConstructionRequestChildren(children) -> false
    while pending.nonEmpty do
      requestCharge()
      val (value, leaving) = pending.remove(pending.size - 1)
      value match
        case frame: ConstructionRequestChildren =>
          if frame.values.hasNext then
            requestCharge()
            pending += frame -> false
            pending += frame.values.next() -> false
        case _: KernelInstanceDataType[?] =>
          directPortFailure("nested substituted type is not an authored root")
        case data: DataType[?] =>
          enter(data, leaving):
            val descriptor = CandidateRuntime.typeDescriptor(data)
            requestCharge()
            descriptor.kind match
              case "Bool" if descriptor.arguments.isEmpty => Iterator.empty
              case "Bits" | "UInt" | "SInt" if descriptor.arguments.size == 1 =>
                descriptor.arguments.iterator
              case "Vec" if descriptor.arguments.size == 2 =>
                descriptor.arguments match
                  case Vector(_: DataType[?], _: Seq[?]) => descriptor.arguments.iterator
                  case _ => directPortFailure("malformed authored Vec")
              case "Struct" if descriptor.arguments.size == 2 =>
                descriptor.arguments match
                  case Vector(_: String, fields: Seq[?]) => fields.iterator
                  case _ => directPortFailure("malformed authored Struct")
              case _ => directPortFailure("unsupported authored direct-port type")
        case field: StructField[?] => enter(field, leaving)(Iterator.single(field.dataType))
        case expression: KernelExpr[?] =>
          if !leaving && !done.containsKey(expression) && !active.containsKey(expression) then
            val ref = Option(expressionIds.get(expression)).getOrElse(
              directPortFailure("authored dimension is not canonical")
            )
            validatePortAllocation(expression, ref, child, frontier)
            if !expression.constructionFinalized || !expression.typeFinalized ||
              !expression.resultDescriptor.exists(_.kind == "Integer")
            then directPortFailure("authored dimension is not a complete static Integer")
            roots += ConstructionRootIdentity(expression, ref)
            count += 1L
          enter(expression, leaving)(expression.operands.iterator)
        case _: Expr[?] => directPortFailure("parameter or alias type roots require a later proof")
        case sequence: Seq[?] => enter(sequence, leaving)(sequence.iterator)
        case _: Int | _: Long | _: Boolean | _: String => ()
        case _ => directPortFailure("unsupported authored type-root metadata")
    requestCharge(count)
    roots.result()

  private def validateDirectPortTarget(target: DirectPortTarget): Unit =
    requestCharge()
    val selected = instanceSelectionStack.lastOption.getOrElse(
      directPortFailure("missing actual selector")
    )
    if (target.session ne this) || (selected ne target.selected) ||
      (target.request.nonEmpty && !directPortRootContext(selected)) ||
      (currentModule ne target.parent) ||
      !Option(instanceIds.get(selected.instance)).exists(_ eq target.instance) ||
      target.instance.generatedScope.nonEmpty
    then directPortFailure("selector or parent occurrence changed")
    if !target.parent.instances.exists(instance =>
        requestCharge()
        instance eq target.instance
      )
    then directPortFailure("unattached or synthetic occurrence")
    val child = records.getOrElse(target.instance.child, directPortFailure("missing child"))
    val record = child.declarations.lift(target.port.index).getOrElse(
      directPortFailure("missing original child port")
    )
    if target.port.module != child.handle || (record.reference ne target.port) ||
      (record.value ne target.value) ||
      !Option(declarationIds.get(target.value)).exists(_ eq target.port) ||
      !record.dataType.exists(_ eq target.dataType) || (target.value.dataType ne target.dataType) ||
      !Set(KernelSignalKind.Input, KernelSignalKind.Output)(record.kind)
    then directPortFailure("original direct port identity changed")
    target.request.foreach: request =>
      val stamp = target.instance.constructionAllocation.getOrElse(
        directPortFailure("occurrence has no authored allocation")
      )
      if (request.session ne this) || (request.module ne target.parent) ||
        (stamp.session ne this) || (stamp.module ne target.parent) ||
        stamp.serial > request.frontier || stamp.region.nonEmpty ||
        stamp.generation.nonEmpty || stamp.area.nonEmpty
      then directPortFailure("occurrence is later or outside original authority")
      validatePortAllocation(target.value, target.port, child, request.frontier)
      val actual = directPortTypeRoots(target.dataType, child, request.frontier)
      if actual.size != target.roots.size then directPortFailure("authored type roots changed")
      var index = 0
      while index < actual.size do
        requestCharge()
        if (actual(index).value ne target.roots(index).value) ||
          (actual(index).reference ne target.roots(index).reference)
        then directPortFailure("authored type root identity changed")
        index += 1

  private def preparedDirectPortType[A <: Data](dataType: DataType[A]): KernelInstanceDataType[A] =
    directPortPermit match
      case Some(permit) =>
        if permit.consumed || (permit.target.dataType ne dataType) then
          directPortFailure("stale or mismatched prepared type")
        permit.dataType.asInstanceOf[KernelInstanceDataType[A]]
      case None => new KernelInstanceDataType[A](CandidateRuntime.typeDescriptor(dataType))

  private def admitPort(
      value: AnyRef,
      kind: KernelSignalKind,
      dataType: Option[DataType[? <: Data]],
      name: Option[String],
      domain: Option[ClockDomain],
      attributes: Vector[(String, Any)]
  ): Boolean =
    val direct = Set(KernelSignalKind.InstanceInput, KernelSignalKind.InstanceOutput)(kind)
    if directPortRequest.nonEmpty && !direct then
      directPortFailure("request scope does not authorize another declaration route")
    if !direct then false
    else if directPortRequest.isEmpty && (activeGeneratedRegion.nonEmpty ||
        instanceSelectionStack.lastOption.exists(_.generated.nonEmpty))
    then true // Existing non-request generated/Area paths are not migrated by this slice.
    else
      val permit = directPortPermit.getOrElse(directPortFailure("missing registration permit"))
      validateDirectPortTarget(permit.target)
      if permit.consumed || permit.value.nonEmpty || permit.reference.nonEmpty ||
        kind != permit.kind || !dataType.exists(_ eq permit.dataType) ||
        name != Some(permit.name) || domain.nonEmpty || attributes.nonEmpty
      then directPortFailure("consumed or mismatched registration permit")
      requestCharge()
      permit.consumed = true
      permit.value = Some(value)
      true

  private def completeDirectPortRegistration(value: Signal[?]): DeclarationRef =
    val reference = declarationIds.get(value)
    directPortPermit.foreach: permit =>
      if !permit.consumed || !permit.value.exists(_ eq value) || reference == null ||
        permit.reference.nonEmpty || reference.module != permit.target.parent.handle
      then directPortFailure("registration did not consume its exact permit")
      permit.reference = Some(reference)
      // Failure here retains the stamped canonical declaration, before boundary publication.
      requestCharge()
    reference

  private def guardedDirectPort[A <: Data](value: Signal[A]): Signal[A] =
    val closure = boundaryClosureOwner.getOrElse(directPortFailure("missing closure owner"))
    closure.guard:
      val selected = instanceSelectionStack.lastOption.getOrElse(
        directPortFailure("missing exact selector")
      )
      if directPortPermit.nonEmpty then directPortFailure("reentrant registration")
      val instance = instanceRecord(selected)
      val port =
        Option(declarationIds.get(value)).getOrElse(directPortFailure("unknown child port"))
      val target = directPortRequest.getOrElse(new DirectPortTarget(
        None,
        selected,
        instance,
        currentModule,
        port,
        value,
        value.dataType,
        Vector.empty
      ))
      if (target.value ne value) then directPortFailure("request target substituted")
      validateDirectPortTarget(target)
      val existing = instanceDigitalPorts.iterator.collectFirst:
        case (reference, binding) if {
              requestCharge();
              (binding.instance eq instance) &&
              (binding.port eq port) && binding.generated.isEmpty && binding.scope.isEmpty
            } =>
          reference -> binding
      existing.foreach: (reference, binding) =>
        val declaration = records(reference.module).declarations(reference.index)
        val proxy = declaration.value.asInstanceOf[Signal[?]]
        val childKind = records(port.module).declarations(port.index).kind
        val expectedKind = if childKind == KernelSignalKind.Input then
          KernelSignalKind.InstanceInput
        else KernelSignalKind.InstanceOutput
        if declaration.kind != expectedKind || (declaration.reference ne reference) ||
          !Option(declarationIds.get(proxy)).exists(_ eq reference) ||
          reference.module != target.parent.handle
        then directPortFailure("cached proxy lost its canonical mapping")
        val deferred = declaration.dataType.collect { case data: KernelInstanceDataType[?] => data }
          .getOrElse(directPortFailure("cached proxy lost its type owner"))
        if (proxy.dataType ne deferred) || !DigitalEnumContract.sameType(
            deferred.formal,
            CandidateRuntime.typeDescriptor(target.dataType)
          )
        then directPortFailure("cached proxy changed its original type")
        if target.request.nonEmpty then
          closure.verifyPublication()
          closure.requireVerifiedDigital(reference, binding)
          if !deferred.finalized then directPortFailure("cached request proxy is not finalized")
      val previous = directPortPermit
      try
        if existing.isEmpty then
          val child = records(port.module).declarations(port.index)
          val kind = if child.kind == KernelSignalKind.Input then KernelSignalKind.InstanceInput
          else KernelSignalKind.InstanceOutput
          directPortPermit = Some(new DirectPortPermit(
            target,
            new KernelInstanceDataType[A](CandidateRuntime.typeDescriptor(value.dataType)),
            kind,
            s"instance_${instance.ordinal}_port_${port.index}"
          ))
        val result = instanceDigitalPortBody(value)
        val reference = declarationIds.get(result)
        val binding = instanceDigitalPorts.getOrElse(
          reference,
          directPortFailure("construction did not publish its exact boundary")
        )
        if (binding.instance ne instance) || (binding.port ne port) ||
          binding.generated.nonEmpty || binding.scope.nonEmpty
        then directPortFailure("constructed boundary target changed")
        directPortPermit.foreach: permit =>
          if !permit.consumed || !permit.value.exists(_ eq result) ||
            !permit.reference.exists(_ eq reference)
          then directPortFailure("construction returned another registered object")
        result
      finally directPortPermit = previous

  // The public/legacy entrypoint stays unchanged; only incremental ordinary direct ports migrate.
  def instanceDigitalPort[A <: Data](value: Signal[A]): Signal[A] =
    if directPortRequest.nonEmpty then guardedDirectPort(value)
    else if boundaryClosureOwner.exists(_.incremental) && activeGeneratedRegion.isEmpty &&
      instanceSelectionStack.lastOption.exists(_.generated.isEmpty)
    then guardedDirectPort(value)
    else instanceDigitalPortBody(value)

  /** Fixed private selection operation, not an arbitrary authorized body or callback. */
  private[nodal] def selectConstructionRequestPort[A <: Data](
      result: KernelExpr[?],
      instance: Instance[?],
      port: Signal[A]
  ): Signal[A] =
    val closure = boundaryClosureOwner.filter(_.incremental).getOrElse(
      directPortFailure("request selection needs an incremental owner")
    )
    closure.guard:
      if directPortRequest.nonEmpty || directPortPermit.nonEmpty then
        directPortFailure("request selection is not reentrant")
      if !result.registeredConstructionOwnedBy(this) || !result.constructionFinalized ||
        !result.constructionKind.exists(DigitalReductionContract.Kinds) ||
        result.constructionCallback.isEmpty
      then directPortFailure("selection requires this Session's completed registered request")
      validateConstructionRequest(
        result,
        result.constructionRequest.getOrElse(
          directPortFailure("missing original request")
        ).input
      )
      closure.verifyPublication()
      val request = result.constructionRequest.get
      val occurrence = instanceRecord(instance)
      val reference = Option(declarationIds.get(port)).getOrElse(
        directPortFailure("target is not an actual child port")
      )
      val child = records.getOrElse(occurrence.child, directPortFailure("missing actual child"))
      val roots = directPortTypeRoots(port.dataType, child, request.frontier)
      val selected = InstanceSelectionRecord(instance)
      val target = new DirectPortTarget(
        Some(request),
        selected,
        occurrence,
        request.module,
        reference,
        port,
        port.dataType,
        roots
      )
      val previous = directPortRequest
      try
        directPortRequest = Some(target)
        instanceSelectionStack += selected
        validateDirectPortTarget(target)
        instanceDigitalPort(port)
      finally
        if instanceSelectionStack.lastOption.exists(_ eq selected) then
          instanceSelectionStack.remove(instanceSelectionStack.size - 1)
        directPortRequest = previous

  private def registrationOwner(
      dimensions: Vector[Dimension],
      boundary: Option[InterfaceBoundaryRecord]
  ): Option[BoundaryClosureOwner] =
    boundaryClosureOwner.filter(_.incremental).map: closure =>
      if directPortPermit.nonEmpty then
        requestCharge()
        if dimensions.nonEmpty || boundary.nonEmpty then
          directPortFailure("registration permit does not authorize boundary metadata")
      closure

  // Additional accounting only for the independently proved bound-domain profile.
  private var boundDomainAccountingActive = false
  private def publicationWorkActive: Boolean =
    publicationAccountingActive || boundDomainAccountingActive

  private def boundDomainCharge(units: Long = 1L): Unit =
    if boundDomainAccountingActive then closureCharge(units)

  private def withBoundDomainAccounting[A](enabled: Boolean)(body: => A): A =
    val previous = boundDomainAccountingActive
    boundDomainAccountingActive = previous || enabled
    try body
    finally boundDomainAccountingActive = previous

  private def boundDomainBindingPath(instance: String, requirement: String): String =
    if !boundDomainAccountingActive then instance + "." + requirement
    else
      boundDomainCharge(2L + instance.length.toLong + requirement.length.toLong)
      instance + "." + requirement

  // Capture optimization authority is exact builder ownership, never an effectful active lookup.
  def constructionCapturePrefixAllowed(builder: SemanticOriginBuilder): Boolean =
    (semanticOrigin eq builder) && registeredConstructionAdmission &&
      boundaryClosureOwner.exists(owner => owner.incremental && owner.hasConstructionWork)

  // A fixed private diagnostic allowance, not a public capacity or admission policy.
  private final class DiagnosticConstructionPolicy(val root: Module, val module: ModuleRecord):
    val name = "registered-construction-diagnostic-131072"
    val limit = 131072
    val requests = new java.util.IdentityHashMap[KernelExpr[?], DiagnosticRequestBinding]()
    var firstRequest: Option[KernelExpr[?]] = None

  private final class DiagnosticRequestBinding(
      val result: KernelExpr[?],
      val context: ConstructionRequestContext,
      val reference: ExpressionRef,
      val policy: DiagnosticConstructionPolicy
  )

  private var diagnosticConstructionPolicy: Option[DiagnosticConstructionPolicy] = None

  private def diagnosticPolicyGuard[A](body: => A): A =
    try
      boundaryClosureOwner match
        case Some(owner) => owner.guard(body)
        case None => body
    catch
      case failure: Throwable =>
        poisonConstructor(failure, "diagnostic construction work policy")
        throw failure

  /** Bind before the first request/debt/owner. This does not itself authorize activation. */
  private[nodal] def bindDiagnosticConstructionWork(root: Module): Unit = diagnosticPolicyGuard:
    val previousDebt = constructionRequestWork
    requestCharge(16L) // Fixed context/identity checks and the policy/map allocations.
    if !ConstructionKernel.ownsConstructionSession(this) || constructorFailure.nonEmpty ||
      diagnosticConstructionPolicy.nonEmpty || boundaryClosureOwner.nonEmpty ||
      registeredConstructionAdmission || previousDebt != 0L || !ordinaryRootConstructionContext
    then collectionFailure("diagnostic work policy requires a fresh ordinary root before requests")
    val module = currentModule
    if moduleHandle(root) != module.handle || module.parentAtConstruction.nonEmpty ||
      !records.get(module.handle).exists(_ eq module)
    then collectionFailure("diagnostic work policy belongs to another root")
    diagnosticConstructionPolicy = Some(new DiagnosticConstructionPolicy(root, module))

  private def bindDiagnosticRequest(result: KernelExpr[Data]): KernelExpr[Data] =
    diagnosticConstructionPolicy match
      case None => result
      case Some(policy) => diagnosticPolicyGuard:
          requestCharge(18L) // Fixed checks, binding insertion and first-carrier retention.
          val context = result.constructionRequest.getOrElse(
            collectionFailure("diagnostic carrier has no registered context")
          )
          val reference = Option(expressionIds.get(result)).getOrElse(
            collectionFailure("diagnostic carrier is not canonical")
          )
          if boundaryClosureOwner.nonEmpty || !result.registeredConstructionOwnedBy(this) ||
            (context.session ne this) || (context.module ne policy.module) ||
            (context.resultReference ne reference) || policy.requests.containsKey(result) ||
            !expressionValues.get(reference).exists(_ eq result)
          then collectionFailure("diagnostic carrier does not belong to the bound root policy")
          policy.requests.put(
            result,
            new DiagnosticRequestBinding(result, context, reference, policy)
          )
          if policy.firstRequest.isEmpty then policy.firstRequest = Some(result)
          result

  private def requireDiagnosticRequest(
      root: Module,
      result: KernelExpr[?]
  ): DiagnosticConstructionPolicy =
    requestCharge(40L) // Fixed policy/carrier/stamp/callback proof; no data-dependent traversal.
    val policy = diagnosticConstructionPolicy.getOrElse(
      collectionFailure("diagnostic work policy was not bound")
    )
    val binding = Option(policy.requests.get(result)).getOrElse(
      collectionFailure("diagnostic activation requires its actual registered carrier")
    )
    val context = result.constructionRequest.getOrElse(
      collectionFailure("diagnostic carrier lost its context")
    )
    val allocation = binding.reference.constructionAllocation.getOrElse(
      collectionFailure("diagnostic carrier lost its result allocation")
    )
    val callback = result.constructionCallback.getOrElse(
      collectionFailure("diagnostic carrier lost its checked callback")
    )
    DigitalCollectionCallback.requireChecked(callback.checked)
    if !ConstructionKernel.ownsConstructionSession(this) || constructorFailure.nonEmpty ||
      (policy.root ne root) || moduleHandle(root) != policy.module.handle ||
      !records.get(policy.module.handle).exists(_ eq policy.module) ||
      (binding.policy ne policy) || (binding.result ne result) || (binding.context ne context) ||
      (context.session ne this) || (context.module ne policy.module) ||
      (context.resultReference ne binding.reference) ||
      (allocation.session ne this) || (allocation.module ne policy.module) ||
      allocation.serial <= context.frontier ||
      !result.constructionKind.exists(DigitalReductionContract.Kinds) ||
      !Option(expressionIds.get(result)).exists(_ eq binding.reference) ||
      !expressionValues.get(binding.reference).exists(_ eq result) ||
      !result.registeredConstructionOwnedBy(this) ||
      (!result.pendingConstructionOwnedBy(this) && !result.constructionFinalized) ||
      context.sourceSlot != result.constructionSourceSlot ||
      (requestReference(context.input) ne context.inputReference) ||
      !result.constructionDependencies.headOption.exists:
        case value: AnyRef => value eq context.input
        case _ => false
    then collectionFailure("diagnostic activation lost canonical request/root/policy authority")
    policy

  private def rejectLegacyDiagnosticActivation(): Unit =
    if diagnosticConstructionPolicy.nonEmpty then
      diagnosticPolicyGuard:
        requestCharge()
        collectionFailure("legacy closure activation cannot promote a diagnostic work policy")

  private def enableDiagnosticConstructionClosure(
      root: Module,
      carrier: Option[KernelExpr[?]]
  ): BoundaryClosureOwner = diagnosticPolicyGuard:
    requestCharge(4L)
    if boundaryClosureOwner.nonEmpty then
      collectionFailure("diagnostic closure already exists")
    val policy = diagnosticConstructionPolicy.getOrElse(
      collectionFailure("diagnostic work policy was not bound")
    )
    val result = carrier.getOrElse:
      requestCharge(2L) // Read and check the deterministic first-bound carrier.
      policy.firstRequest.getOrElse(
        collectionFailure("diagnostic activation requires a registered request")
      )
    val closure = new BoundaryClosureOwner(moduleHandle(root), Map.empty, Vector.empty)
    boundaryClosureOwner = Some(closure)
    closure.enableDiagnostic(root, result)
    closure

  /** Fixed internal entry: no arbitrary budget or authorized callback body. */
  private[nodal] def beginDiagnosticConstructionClosure(
      root: Module,
      carrier: KernelExpr[?]
  ): Unit = diagnosticPolicyGuard:
    requestCharge()
    if !ordinaryRootConstructionContext then
      collectionFailure("diagnostic activation requires the ordinary root context")
    val closure = enableDiagnosticConstructionClosure(root, Some(carrier))
    closure.prepareIncrementalInputs()
    closure.drainIncremental()

  private[nodal] def diagnosticConstructionWorkPolicyTag: Option[String] =
    diagnosticConstructionPolicy.map(_.name)
