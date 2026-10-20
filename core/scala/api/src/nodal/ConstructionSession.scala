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
    readyDeclarations.toVector.sortBy((reference, _) =>
      (reference.module, reference.index)
    ).foreach: (reference, declaration) =>
      if !counts.contains(reference) then
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
      : Option[KernelExecutableCrossingSnapshot] =
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
      : Option[KernelExecutableWaiverSnapshot] =
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
    val actual = if stimulusOnly then Vector.empty
    else
      records.valuesIterator.flatMap(_.domains).filter(value =>
        value.kind != KernelDomainKind.Required && value.domain.clock.nonEmpty
      ).toVector
    val endpoints = actual.map: record =>
      val domain = record.domain
      val identity = clockIdentity(domain.clock.get, record.reference.module)
      val physical = identity match
        case Left((reference, offset)) => "physical:" + declarationPath(reference) + ":" + offset
        case Right(reference) => "derived:" + expressionPath(reference)
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
      actual.flatMap(relationshipSnapshot),
      (message, path) => fail(ClockRelationshipModel.Code, message, Some(path))
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
    clockRecords.get(expressionIds.get(expression)).map: record =>
      if record.controlKinds.size != record.controls.size then
        clockFailure("clock control provenance was not validated")
      KernelClockSnapshot(
        record.kind,
        record.parents.map(value => domainPath(domainRef(value))),
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
    if resetRecords.containsKey(value) then value
    else
      Option(
        resetBoundaries.get(value)
      ).orElse(physicalResetBoundaries.get(physicalControlIdentity(value))).getOrElse(value)

  private def resetRecord(value: Expr[Reset]): ResetRecord =
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
    Option(resetRecords.get(expression.asInstanceOf[Expr[Reset]])).map: record =>
      val inputs = record.inputs.map(domainSourcePath)
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
  private var timerStateBits = 0
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
    val reference = ExpressionRef(module.handle, module.expressionCount)
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
        definition.fragments.exists(_.states.exists(_.isTerminal))
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
    if Option(mode).isEmpty || CandidateRuntime.expressionDataType(
        condition
      ).map(CandidateRuntime.typeDescriptor) !=
        Some(KernelTypeDescriptor("Bool"))
    then FlatFsmRuntime.fail("transition requires exact Bool and a known mode")
    val _ = validateFsmObservations(Vector(condition))
    if Option(fsmObservations.get(condition)).exists(_.future) then
      FlatFsmRuntime.fail("next-state/event feedback into transition selection is cyclic")
    if record.actions.size.toLong + record.transitions.size >= FlatFsmRuntime.MaximumRecords then
      FlatFsmRuntime.fail("machine transitions/actions exceed 65536 records")
    val bound = bindCollectionValue(condition, None, currentModule.handle).asInstanceOf[Expr[Bool]]
    val destination = DigitalEnumContract.selectedCase(to, record.evidence, record.canonical)
    val transition =
      FlatFsmTransitionRecord(builder.record.caseId, destination, mode, bound, record.nextOrdinal())
    record.transitions += transition
    body.foreach(callback =>
      captureFsmAction(record, "transition", None, Some(transition.ordinal), callback)
    )

  def captureFlatFsmTimer[S](
      builder: StateBuilder[S],
      cycles: Int,
      mode: TransitionMode,
      to: S
  ): Unit = fsmTransaction:
    FsmTimingRuntime.cycles(cycles)
    val record = requireFsmState(builder)
    val storage = record.timer.getOrElse {
      if timerStateBits.toLong + FsmTimingRuntime.Width > FsmTimingRuntime.MaximumStateBits then
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
      timerStateBits += FsmTimingRuntime.Width
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
    val reference = ExpressionRef(module.handle, module.expressionCount)
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
            value.ordinal
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
  private val instanceSelectionStack = mutable.ArrayBuffer.empty[Instance[?]]
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
      !region.isLoop && !(assignment && region.permitsAssignment)
    ).foreach: region =>
      rejectCollection(
        "NODAL-COLLECTION-055-006",
        s"$role is not permitted in this lexical value region",
        Some(expressionPath(region.reference))
      )

  private def requireLoopEffect(role: String): Unit =
    activeLoop.filter(_.loop.get.generated.isEmpty).foreach: region =>
      rejectCollection(
        "NODAL-LOOP-055-006",
        s"$role is not permitted in a bounded digital loop",
        Some(expressionPath(region.reference))
      )

  private def valueReference(value: Expr[?]): DeclarationRef | ExpressionRef =
    Option(declarationIds.get(value)).map(value => value: DeclarationRef | ExpressionRef)
      .orElse(Option(expressionIds.get(value)).map(value => value: DeclarationRef | ExpressionRef))
      .getOrElse(collectionFailure("collection value has no captured construction identity"))

  private def valueRegion(reference: DeclarationRef | ExpressionRef)
      : Option[DigitalValueRegionRecord] =
    reference match
      case expression: ExpressionRef => expressionRegions.get(expression)
      case declaration: DeclarationRef =>
        records(declaration.module).generatedRegions.find(region =>
          region.areaFamily && region.declarations.contains(declaration)
        ).flatMap(_.digitalRegion)

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
                  case expression: KernelExpr[?] if expression.literal.nonEmpty => false
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
    val reference = ExpressionRef(module.handle, -module.scaffoldExpressionCount - 1)
    module.scaffoldExpressionCount += 1
    reference

  private def newCollectionArgument(
      region: DigitalValueRegionRecord,
      dataType: DataType[?],
      role: String
  ): KernelExpr[?] =
    if region.arguments.size >= DigitalReductionContract.MaximumArguments then
      collectionFailure("collection signature exceeds the 4098 argument budget")
    if sequentialRegion(region) then
      validateSequentialValueType(dataType, region.reference.module)
    else if CandidateRuntime.typeDescriptor(dataType).kind != "Integer" then
      DigitalCombinationalContract.validateValueType(CandidateRuntime.typeDescriptor(dataType))
    val argument = new KernelExpr[Data](
      Vector.empty,
      resultType = Some(CandidateRuntime.typeDescriptor(dataType)),
      operation = Some(DigitalCollectionContract.Argument)
    )
    val module = records(region.reference.module)
    val scaffold = role == "induction" && region.loop.exists(_.generated.nonEmpty)
    val reference = if scaffold then newScaffoldReference(module)
    else
      val result = ExpressionRef(module.handle, module.expressionCount)
      module.expressionCount += 1
      result
    collectionArguments.update(reference, region -> region.arguments.size)
    region.arguments += argument -> role
    val _ =
      captureExpression(argument, retained = Some(reference), scopeOverride = Some(Some(region)))
    argument

  /** Resolve each free edge through every intervening region. This retains actual parent values as
    * capture operands and introduces a genuine typed block argument at each isolation boundary.
    */
  private def bindCollectionValue(
      value: Expr[?],
      destination: Option[DigitalValueRegionRecord],
      owner: Long,
      create: Boolean = true,
      predicateUse: Boolean = false
  ): Expr[?] =
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
    if generatedInduction then destination.get.arguments.head._1
    else if sourceRegion == destination then value
    else
      destination match
        case None =>
          collectionFailure("collection argument or body value escaped its lexical region")
        case Some(region) =>
          val parentValue = bindCollectionValue(value, region.parent, owner, create, predicateUse)
          val parentReference = valueReference(parentValue)
          region.captures.get(parentReference) match
            case Some((_, argument)) => argument
            case None if create =>
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
              if !isStatic then
                validateShapeType(dataType, owner, cardinality = true, storageContext = true)
              else if !proceduralIndex || predicateUse then
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
                else if isStatic then "static_capture" else "capture"
              )
              region.captures.update(parentReference, parentValue -> argument)
              if !isStatic then
                bindCollectionType(CandidateRuntime.typeDescriptor(dataType), Some(region), owner)
              argument
            case None => collectionFailure("collection free edge has no explicit capture argument")

  private def mapCollectionType(
      descriptor: KernelTypeDescriptor,
      region: Option[DigitalValueRegionRecord],
      owner: Long,
      create: Boolean
  ): KernelTypeDescriptor =
    def map(value: Any): Any = value match
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
      case values: Seq[?] => values.map(map)
      case other => other
    descriptor.copy(arguments = descriptor.arguments.map(map))

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
      : Option[String] = value match
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
    ancestry(positions.min)

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
      val knownGeneratedMember = generatedMemberRecord(expression).nonEmpty
      val pure = expression.literal.nonEmpty || operation.startsWith(DigitalEnumContract.Prefix) ||
        DigitalScalarContract.Operations.contains(operation) ||
        operation == DigitalMaterializationContract.Operation ||
        DigitalCombinationalContract.Operations.contains(operation) ||
        DigitalAggregateContract.Operations.contains(operation) ||
        DigitalCombinationalContract.ReachableBooleanOperations.contains(operation) ||
        Set("shape_index", "shape_view", "shape_slice").contains(operation) ||
        knownArgument || knownCollection || knownConditional || behavioralPredicate || knownGeneratedMember || FlatFsmRuntime.Operations(
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
        if !staticInteger && !knownArgument then
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
        val destination = generatedMemberRecord(expression) match
          case Some(member) => generatedMemberScope(expression, member)
          case None => if moduleStatic then None else current
        if !knownArgument then
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
      val result = ExpressionRef(module.handle, module.expressionCount)
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
    val usedRegions = operations.iterator.flatMap(_.digitalRegion).toSet ++
      expressionRegions.iterator.collect:
        case (reference, region)
            if region.isLoop && !collectionArguments.contains(reference) &&
              expressionValues.get(reference).exists(value =>
                !value.resultDescriptor.exists(dataType =>
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
              CandidateRuntime.expressionDataType(argument).get,
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
        renderCollectionType(
          CandidateRuntime.expressionDataType(argument).get,
          region.reference.module,
          Some(region)
        ),
        role
      )

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
        region.structuralGenerate = true
      val predicateRef = Option(expressionIds.get(predicate)).getOrElse(
        structuralFailure("structural if predicate has no construction identity")
      )
      if predicateRef.module != module.handle || !staticPredicates.contains(predicateRef) ||
        expressionRegions.get(predicateRef) != parent
      then
        structuralFailure("structural if predicate belongs to another lexical scope")
      val reference = ExpressionRef(module.handle, module.expressionCount)
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
        region.yielded = yielded
        yielded.map: value =>
          val descriptor = DigitalCollectionContract.descriptor(value)
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
      if trueType.size != falseType.size || trueType.zip(falseType).exists((left, right) =>
          !DigitalEnumContract.sameType(left, right)
        )
      then
        structuralFailure(
          "structural if alternatives must yield exactly the same complete hardware ABI"
        )
      trueType.map: dataType =>
        val captures = record.trueArm.captures.valuesIterator.map(_._1).toVector ++
          record.falseArm.captures.valuesIterator.map(_._1).toVector
        val result = new KernelExpr[Data](
          predicate +: captures,
          resultType = Some(dataType),
          operation = Some(StructuralIfRuntime.Operation)
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
    val reference = ExpressionRef(module.handle, module.expressionCount)
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
  private def verifySequentialAssignments(
      resolved: Map[DomainRef, String],
      interfaces: DigitalInterfaceContract.Capture
  ): Unit =
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
      behavioralIfs.valuesIterator.map(value => value.root -> value.predicate).toVector ++
        flatFsms.valuesIterator.flatMap(record =>
          (record.controlInputs ++ record.transitions.map(_.guard)).map(value =>
            record.reference -> value
          )
        ).toVector ++
        enumSwitches.valuesIterator.map(record => record.root -> record.selector).toVector
    val guardsByRoot = guardPairs.groupMap(_._1)(_._2)
    flatFsms.valuesIterator.foreach(record =>
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
    val eventTriggerReferences = eventProcesses.iterator.map(process =>
      declarationIds.get(process.definition.trigger)
    ).toSet
    val continuousWriteCounts = mutable.HashMap.empty[DeclarationRef, Int]
    val continuousDrivers = mutable.HashMap.empty[DeclarationRef, Vector[Any]]
    operations.iterator.filter(operation =>
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
        continuousWriteCounts.update(target, continuousWriteCounts.getOrElse(target, 0) + 1)
        val declaration = records(target.module).declarations(target.index)
        // A projected target carries its actual selectors; revisiting the root is cut by the
        // shared sampler's identity worklist, not by an annotation or a synthetic source.
        val selection =
          Option.when(operation.values.head.asInstanceOf[AnyRef] ne declaration.value)(
            operation.values.head
          ).toVector
        continuousDrivers.update(
          target,
          continuousDrivers.getOrElse(target, Vector.empty) ++ Vector(operation.values(1)) ++
            selection ++ causalControls(operation.digitalRegion)
        )
    val parentInputReferences = instanceDigitalPorts.iterator.collect {
      case (proxy, (_, port))
          if records(proxy.module).declarations(
            proxy.index
          ).kind == KernelSignalKind.InstanceInput => port -> proxy
    }.toMap
    instanceDigitalPorts.keysIterator.filter(reference =>
      records(reference.module).declarations(reference.index).kind == KernelSignalKind.InstanceInput
    ).foreach: reference =>
      if continuousWriteCounts.getOrElse(reference, 0) != 1 then
        fail(
          "NODAL-HIERARCHY-057-001",
          "child digital input requires exactly one parent driver",
          Some(declarationPath(reference))
        )
    // Interface capture is the owner of resolved connectivity and member/driver rules.
    // Feed its actual edges into this same sampler rather than constructing a CDC-only net graph.
    val declarationsByPath = records.valuesIterator.flatMap(_.declarations).map(value =>
      declarationPath(value.reference) -> value.reference
    ).toMap
    val valuesByPath = records.valuesIterator.flatMap(_.declarations).map(value =>
      declarationPath(value.reference) -> value.value
    ).toMap ++
      expressionValues.iterator.map((reference, value) => expressionPath(reference) -> value)
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
    val domainsByPath = records.valuesIterator.flatMap(_.domains).map(domain =>
      domainPath(domain.reference) -> domain
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
        val pending = mutable.ArrayDeque((value, owner, true))
        var activeOwner = owner
        var activeDomainVisible = true
        var edges = 0
        def enqueue(
            values: IterableOnce[Any],
            context: Long = activeOwner,
            domainVisible: Boolean = activeDomainVisible
        ): Unit =
          values.iterator.foreach: next =>
            edges += 1
            if edges > 262144 then
              behavioralFailure("sequential dependency edges exceed their budget")
            pending.append((next, context, domainVisible))
        val visited =
          mutable.HashMap.empty[(Long, Boolean), IdentityHashMap[AnyRef, java.lang.Boolean]]
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
          val (current, context, domainVisible) = pending.removeLast()
          activeOwner = context
          activeDomainVisible = domainVisible
          val seen =
            visited.getOrElseUpdate(
              (context, domainVisible),
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
                  case Some((_, port)) if declaration.kind == KernelSignalKind.InstanceOutput =>
                    enqueue(
                      Vector(records(port.module).declarations(port.index).value),
                      port.module
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
                    resetRecords.containsKey(expressionValues(reference)) || clockRecords.contains(
                      reference
                    ) ||
                    fsmResults.contains(reference)
                )
              then
                eventFailure(
                  "event capture cannot contain clock/reset/crossing/waiver/FSM provenance"
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
                          if expression.operation.contains("memory_read") then
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
    val updatesByEventProcess = operations.iterator.filter(_.eventProcess.nonEmpty).toVector
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
    records.valuesIterator.flatMap(_.declarations).foreach: declaration =>
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
    records.valuesIterator.flatMap(_.declarations).filter(declaration =>
      declaration.kind == KernelSignalKind.Output &&
        continuousWriteCounts.getOrElse(declaration.reference, 0) > 0
    ).foreach: declaration =>
      verifyReconvergence(sampleFacts(
        declaration.value,
        declaration.reference.module,
        strict = false
      ))
    val ownersByPath = records.keysIterator.map(owner => modulePath(owner) -> owner).toMap
    interfaces.observationGroups.foreach: group =>
      val owner = ownersByPath.getOrElse(
        group.owner,
        crossingFailure("Interface observation lost its actual construction owner")
      )
      verifyReconvergence(combinedFacts(group.values, owner, strict = false))
    operations.zipWithIndex.foreach: (operation, index) =>
      if operation.kind == "assignment" && operation.values.size == 2 then
        val target =
          digitalAssignmentRoot(
            operation.values.head,
            operation.owner,
            allowRootRegister = true,
            generatedScope = assignmentGeneratedScope(operation)
          )
        val declaration = records(target.module).declarations(target.index)
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
    operations.iterator.filter(_.kind == "memory-write").foreach: operation =>
      memoryPort(
        operation.values.last.asInstanceOf[MemoryPortCapture],
        operation.owner,
        operation.values.slice(1, 4),
        operation.digitalRegion,
        operation
      )
    expressionValues.foreach: (reference, expression) =>
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
    val usedMemories = operations.iterator.filter(_.kind == "memory-write").map(
      _.values.head.asInstanceOf[AnyRef]
    ).toSet ++ expressionValues.valuesIterator.filter(_.operation.contains("memory_read")).map(
      _.operands.last.asInstanceOf[MemoryPortCapture].memory
    ).toSet
    records.valuesIterator.flatMap(_.declarations).filter(declaration =>
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

  private def structuralIfSnapshots(): Vector[KernelStructuralIfSnapshot] =
    structuralIfs.valuesIterator.map: record =>
      def arm(region: DigitalValueRegionRecord): KernelStructuralArmSnapshot =
        KernelStructuralArmSnapshot(
          expressionPath(region.reference),
          region.captures.valuesIterator.map(value => pathOf(value._1).get).toVector,
          regionArguments(region),
          region.yielded.flatMap(pathOf)
        )
      KernelStructuralIfSnapshot(
        expressionPath(record.reference),
        modulePath(record.reference.module),
        record.parent.map(value => expressionPath(value.reference)),
        pathOf(record.predicate).get,
        arm(record.trueArm),
        arm(record.falseArm),
        record.result.flatMap(CandidateRuntime.expressionDataType).map(renderType(
          _,
          record.reference.module
        )),
        record.sourceOrdinal
      )
    .toVector

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
    val argumentTypes = DigitalCollectionContract.arguments(kind, inputs, seed)
    val parent = activeValueRegion
    val boundInputs = inputs.map(bindCollectionValue(_, parent, module.handle))
    val boundSeed = seed.map(bindCollectionValue(_, parent, module.handle))
    (boundInputs ++ boundSeed).foreach: value =>
      validateShapeType(
        CandidateRuntime.expressionDataType(value).get,
        module.handle,
        cardinality = true,
        storageContext = true
      )
    val reference = ExpressionRef(module.handle, module.expressionCount)
    module.expressionCount += 1
    val region = new DigitalValueRegionRecord(reference, parent, kind, boundInputs, boundSeed)
    collectionRegions.update(reference, region)
    collectionStack += region
    try
      val arguments =
        argumentTypes.map((dataType, role) => newCollectionArgument(region, dataType, role))
      arguments.foreach(argument =>
        bindCollectionType(argument.resultDescriptor.get, Some(region), module.handle)
      )
      val yielded = bindCollectionValue(body(arguments), Some(region), module.handle)
      DigitalCombinationalContract.validateValueType(DigitalCollectionContract.descriptor(yielded))
      val rawResultType =
        DigitalCollectionContract.result(kind, boundInputs.head, boundSeed, yielded)
      region.yielded = Some(yielded)
      collectionStack.remove(collectionStack.size - 1)
      val resultType = exportCollectionType(rawResultType, region)
      validateShapeType(
        CandidateRuntime.dataTypeFromDescriptor(resultType),
        module.handle,
        cardinality = true,
        storageContext = true
      )
      val result = new KernelExpr[Data](
        boundInputs ++ boundSeed ++ region.captures.valuesIterator.map(_._1).toVector,
        resultType = Some(resultType),
        operation = Some(DigitalCollectionContract.Operation)
      )
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
      val reference = ExpressionRef(module.handle, module.expressionCount)
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
              expression.operation.filter(IterationDomain.staticArithmetic(_).nonEmpty).map(
                operation =>
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
        region.yielded = Some(yielded)
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
        collectionStack.remove(collectionStack.size - 1)
        reductionExact = previousExact
        reductionDomains = previousDomains
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
        val result = new KernelExpr[Data](
          Vector(input) ++ region.captures.valuesIterator.map(_._1).toVector,
          resultType = Some(resultType),
          operation = Some(DigitalCollectionContract.Operation)
        )
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
              argument.resultDescriptor.get,
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
              CandidateRuntime.expressionDataType(argument).get,
              owner,
              Some(region)
            ),
            role
          ),
        pathOf(region.yielded.get).get,
        renderType(CandidateRuntime.expressionDataType(result).get, owner)
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
      requireNoCollectionEffect("Area declaration")
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
        validateGeneratedOrdinal(selection.family, selection.ordinal)
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

  private def validateGeneratedOrdinal(
      record: GeneratedRegionRecord,
      ordinal: Int | Expr[Integer]
  ): Unit =
    val analysis = new StructuralBoundAnalysis(record.owner, "NODAL-AREA-058-INDEX", "Area ordinal")
    val bounds = analysis(ordinal)
    IterationDomain.verifyOrdinal(record.domain, bounds.bounds).fold(
      problem => fail("NODAL-AREA-058-INDEX", problem.message),
      identity
    )
    bounds.parameters.foreach(markStructuralParameter)

  def generatedAreaReference[A <: Area](family: GeneratedAreas[A], ordinal: Int | Expr[Integer])
      : GeneratedAreaRef[A] =
    val record = requireGeneratedFamily(family)
    validateGeneratedOrdinal(record, ordinal)
    new GeneratedAreaRef[A](family, ordinal, Vector.empty)

  private def generatedSelectedArea(value: GeneratedAreaRef[?])
      : (GeneratedRegionRecord, AreaRecord) =
    val family = requireGeneratedFamily(value.family)
    validateGeneratedOrdinal(family, value.ordinal)
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
      !records(family.owner).generatedRegions.exists(_ eq target) || target.yieldedArea.isEmpty
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
    if !family.declarations.contains(target) ||
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

  private def generatedAreaExports(family: GeneratedRegionRecord)
      : Vector[KernelGeneratedAreaExport] =
    val exports = Vector.newBuilder[KernelGeneratedAreaExport]
    var count = 0
    def visit(record: AreaRecord, prefix: Vector[String]): Unit =
      if prefix.size >= 128 then fail("NODAL-AREA-058-LIMIT", "generated export exceeds path depth")
      record.members.foreach: (name, member) =>
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
            exports += KernelGeneratedAreaExport(
              key.mkString("."),
              generatedRegionPath(target),
              "read_only",
              "family"
            )
          case "read_only" | "read_write" =>
            val target = generatedExportTarget(family, member)
            exports += KernelGeneratedAreaExport(
              key.mkString("."),
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
    val metadata =
      GeneratedAreaMemberRecord(
        family,
        value.ordinal,
        value.members :+ name,
        target,
        access,
        value.family.selectionPrefix
      )
    val expression = new KernelExpr[Data](
      value.family.selectionPrefix.map(_.ordinal) ++ Vector(value.ordinal, metadata),
      resultType = Some(CandidateRuntime.typeDescriptor(dataType)),
      operation = Some(GeneratedAreaRuntime.MemberOperation)
    )
    val _ = captureExpression(expression)
    kind match
      case "signal" => new Signal(dataType, KernelSignalKind.Wire, projection = Some(expression))
      case "register" => new Register(None, Some(dataType), Some(expression))
      case _ => expression

  private def generatedMemberRecord(expression: KernelExpr[?]): Option[GeneratedAreaMemberRecord] =
    expression.operands.lastOption.collect:
      case value: GeneratedAreaMemberRecord
          if expression.operation.contains(GeneratedAreaRuntime.MemberOperation) => value

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

  private def generatedMemberSnapshot(expression: KernelExpr[?])
      : Option[KernelGeneratedMemberSnapshot] =
    generatedMemberRecord(expression).map: record =>
      val reference = Option(expressionIds.get(expression)).getOrElse(
        fail("NODAL-AREA-058-OWNER", "generated member snapshot has no captured expression")
      )
      def bound(input: Int | Expr[Integer]): String =
        val ordinal = input match
          case value: Int => value
          case value: Expr[Integer] =>
            bindCollectionValue(
              value,
              expressionRegions.get(reference),
              record.family.owner,
              create = false
            ).asInstanceOf[Expr[Integer]]
        generatedBound(ordinal)
      KernelGeneratedMemberSnapshot(
        generatedRegionPath(record.family),
        bound(record.ordinal),
        record.members.mkString("."),
        record.access,
        record.selectionPrefix.map(value =>
          KernelGeneratedFamilySelection(
            generatedRegionPath(value.family),
            bound(value.ordinal),
            value.members.mkString(".")
          )
        )
      )

  private def areaComponents(record: AreaRecord): Vector[String] =
    val local = record.name.getOrElse(
      "area_" + java.lang.Integer.toUnsignedString(
        (record.source.path + ":" + record.source.line + ":" + record.source.column).hashCode,
        16
      )
    )
    record.parent.map(areaComponents).getOrElse(Vector.empty) :+ local

  private def prepareAreaNames(): Unit =
    val paths = mutable.HashSet.empty[(Long, Vector[String])]
    areas.foreach: record =>
      val components = areaComponents(record)
      if !paths.add(record.owner -> components) then
        fail("NODAL-AREA-058-NAME", "Area lexical names collide in the same Module")
      val ownedDeclarations = record.declarations.toSet
      val ownedInstances = record.instances.toSet
      record.members.foreach: (name, member) =>
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
    def path(record: AreaRecord): String =
      (Vector(modulePath(record.owner)) ++ areaComponents(record)).mkString(".")
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
      KernelAreaSnapshot(
        path(record),
        modulePath(record.owner),
        record.parent.map(path),
        record.source,
        localDomain(record),
        record.declarations.toVector.map(declarationPath),
        record.members.toVector.flatMap: (name, member) =>
          val target = member.value match
            case area: Area => Option(areaRecords.get(area)).map(path)
            case family: GeneratedAreas[?] => Some(generatedRegionPath(family.generatedRegion))
            case instance: Instance[?] =>
              Option(instanceIds.get(instance)).map(record => modulePath(record.child))
            case _ => pathOf(member.value)
          target.map(value => KernelAreaMemberSnapshot(name, path(record) + "." + name, value))
        ,
        generatedScopeSnapshot(record.generatedScope)
      )

  def beginModule(module: Module): Unit =
    requireNoCollectionEffect("Module declaration")
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
      conservativeDimensions: Vector[Dimension] = Vector.empty
  ): Unit =
    closeBehavioralChain()
    requireNoCollectionEffect(s"${kind.label} declaration")
    requireLoopEffect(s"${kind.label} declaration")
    validateCollectionDependencies(attributes.map(_._2))
    val activeGeneratedVariable =
      kind == KernelSignalKind.Variable && AnalogProceduralConstruction.generatedProcedureActive
    val generatedAreaStorage = activeGeneratedRegion.exists(_.areaFamily) &&
      Set(KernelSignalKind.Wire, KernelSignalKind.Register)(kind)
    if kind != KernelSignalKind.AnalogNode && kind != KernelSignalKind.AnalogNodeArray &&
      !activeGeneratedVariable && !generatedAreaStorage
    then
      requireNoGeneratedEffect(s"generated ${kind.label} declaration")
    val module = currentModule
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
        !deferred.finalized && instanceSelectionStack.nonEmpty &&
        (Set(KernelSignalKind.InstanceInput, KernelSignalKind.InstanceOutput)(kind) ||
          (kind == KernelSignalKind.DigitalInout && (value match
            case endpoint: DigitalInout[?, ?] => endpoint.boundary
            case _ => false)))
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
    val reference = DeclarationRef(module.handle, module.declarations.size)
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
    mutable.LinkedHashMap.empty[DeclarationRef, (InstanceRecord, DeclarationRef)]
  private val instanceDigitalInouts =
    mutable.LinkedHashMap.empty[DeclarationRef, (InstanceRecord, DeclarationRef)]
  private var occurrenceDomains = Map.empty[DomainRef, String]
  private val selectedRequirementBindings =
    mutable.LinkedHashMap.empty[DomainRef, (DomainRef, String, Long, Int)]

  private val instanceInterfacePorts =
    mutable.LinkedHashMap.empty[DeclarationRef, (InstanceRecord, DeclarationRef)]

  private val instanceInterfaceDimensions =
    mutable.LinkedHashMap.empty[DeclarationRef, Vector[Dimension]]
  private val instanceInterfaceTypes =
    mutable.LinkedHashMap.empty[DeclarationRef, Map[KernelTypeDescriptor, DataType[?]]]

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
    requireInstanceScope(instance)
    val port = Option(declarationIds.get(value)).getOrElse(
      fail("NODAL-HIERARCHY-058-INTERFACE", "selector requires an actual declared child Interface")
    )
    val declaration = records(port.module).declarations(port.index)
    val view = value match
      case endpoint: InterfacePort[?, ?] => endpoint.origin.nonEmpty
      case _: InterfaceArray[?, ?] => false
    if port.module != instance.child || declaration.kind != kind || view || value.boundary ||
      activeValueRegion.nonEmpty || activeGeneratedRegion.nonEmpty
    then
      fail(
        "NODAL-HIERARCHY-058-INTERFACE",
        "selector requires its immediate child's direct Interface"
      )
    val leaves = ConstructionInterfaceLayout.leaves(value.definition, value.role)
    if leaves.exists(leaf =>
        leaf.analogType.nonEmpty || leaf.dataType.exists(isBoundaryCapability) ||
          (leaf.mode.nonEmpty && kind == KernelSignalKind.InterfaceArray)
      )
    then
      fail(
        "NODAL-HIERARCHY-058-INTERFACE",
        "child Interface requires digital leaves and scalar resolved components"
      )
    instanceInterfacePorts.iterator.collectFirst:
      case (reference, (existing, target)) if (existing eq instance) && target == port =>
        records(
          reference.module
        ).declarations(reference.index).value.asInstanceOf[InterfaceEndpoint]
    .getOrElse:
      val name = s"instance_${instance.ordinal}_interface_${port.index}"
      val proxy = value match
        case array: InterfaceArray[?, ?] =>
          new InterfaceArray(
            array.definition,
            array.role,
            array.formalCount,
            name,
            array.domain,
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
        Vector("interface" -> value.definition.name, "role" -> value.role.name)
      )
      val reference = declarationIds.get(proxy)
      val owner = records(reference.module)
      owner.declarations.update(
        reference.index,
        owner.declarations(reference.index).copy(domainCandidate = None)
      )
      instanceInterfacePorts(reference) = instance -> port
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
    instanceInterfacePorts.get(reference).map: (_, port) =>
      val child = records(port.module).declarations(port.index)
      val required = declarationCandidate(child).map(domainRef).getOrElse(
        fail("NODAL-HIERARCHY-058-INTERFACE", "child Interface has no declared domain requirement")
      )
      selectedRequirementBindings.get(required).map(_._1).getOrElse(
        fail(
          "NODAL-HIERARCHY-058-INTERFACE",
          "child Interface needs an actual instance domain binding"
        )
      )

  def instanceDigitalPort[A <: Data](value: Signal[A]): Signal[A] =
    val selected = instanceSelectionStack.lastOption.getOrElse(
      fail("NODAL-HIERARCHY-057-001", "digital child reference requires an exact Instance selector")
    )
    val instance = instanceRecord(selected)
    requireInstanceScope(instance)
    val port = Option(declarationIds.get(value)).getOrElse(
      fail("NODAL-HIERARCHY-057-001", "instance selector requires an actual child port")
    )
    val declaration = records(port.module).declarations(port.index)
    if port.module != instance.child ||
      !Set(KernelSignalKind.Input, KernelSignalKind.Output)(declaration.kind) ||
      !isClockedDigitalType(value.dataType) || isBoundaryCapability(value.dataType) ||
      activeValueRegion.nonEmpty || activeGeneratedRegion.nonEmpty
    then
      fail(
        "NODAL-HIERARCHY-057-001",
        "instance selector requires its immediate child's direct digital data port"
      )
    instanceDigitalPorts.iterator.collectFirst:
      case (reference, (existing, target)) if (existing eq instance) && target == port =>
        records(reference.module).declarations(reference.index).value.asInstanceOf[Signal[A]]
    .getOrElse:
      val kind = if declaration.kind == KernelSignalKind.Input then KernelSignalKind.InstanceInput
      else KernelSignalKind.InstanceOutput
      val proxy = new Signal(
        new KernelInstanceDataType[A](CandidateRuntime.typeDescriptor(value.dataType)),
        kind,
        Some(s"instance_${instance.ordinal}_port_${port.index}")
      )
      val reference = declarationIds.get(proxy)
      val owner = records(reference.module)
      owner.declarations.update(
        reference.index,
        owner.declarations(reference.index).copy(domainCandidate = None)
      )
      instanceDigitalPorts(reference) = instance -> port
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

  def withInstanceSelection[A](instance: Instance[?])(body: => A): A =
    requireInstanceScope(instanceRecord(instance))
    instanceSelectionStack += instance
    try body
    finally instanceSelectionStack.remove(instanceSelectionStack.size - 1)

  // Both digital ports and complete Interface payloads use this one contextual transform.
  // The batch shares a reachable-DAG export and one bounded recursive type budget.
  private def materializeInstanceTypeDescriptors(
      parent: Long,
      instance: InstanceRecord,
      port: DeclarationRef,
      formals: Vector[KernelTypeDescriptor],
      queries: StructuralParameterBinding.InstanceQueries
  ): Vector[KernelTypeDescriptor] =
    val owner = modulePath(parent)
    val occurrencePath = instancePath(parent, instance.ordinal)
    val (_, occurrence, _) = queries.declaration(owner, occurrencePath, declarationPath(port))
    val query = queries.query(owner, occurrencePath)
    val dimensions = mutable.ArrayBuffer.empty[Int]
    var examined = 0
    def transform(
        descriptor: KernelTypeDescriptor,
        dimension: Any => Dimension,
        depth: Int
    ): KernelTypeDescriptor =
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
                  counts.toVector.map(dimension)
                )
              )
            case _ => fail("NODAL-HIERARCHY-057-001", "instance vector type is malformed")
        case "Struct" => descriptor.arguments match
            case Vector(name: String, fields: Seq[?]) =>
              descriptor.copy(arguments =
                Vector(
                  name,
                  fields.toVector.map:
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
                )
              )
            case _ => fail("NODAL-HIERARCHY-057-001", "instance struct type is malformed")
        case _ => descriptor
    formals.foreach: formal =>
      val _ = transform(
        formal,
        value =>
          val id = query.resolve(occurrence.childModule, renderShapeDimension(value, port.module))
          query.positive(id)
          dimensions += id
          1
        ,
        0
      )
    val previousModules = moduleStack.toVector
    moduleStack.clear()
    moduleStack += records(parent)
    try
      val actual = materializeStructuralDimensions(
        query.exportDimensions(owner, dimensions.toVector)
      ).iterator
      examined = 0
      formals.map(formal => transform(formal, _ => actual.next(), 0))
    finally
      moduleStack.clear()
      moduleStack ++= previousModules

  /** Seal exact result types through the already captured value DAG. There is no second expression
    * registry and no callback/body replay. Operation owners retain their inference.
    */
  private def materializeDeferredResultTypes(): Unit =
    val state = new java.util.IdentityHashMap[KernelExpr[?], java.lang.Integer]()
    var work = 0
    val previousModules = moduleStack.toVector
    def visit(expression: KernelExpr[?], depth: Int): Unit =
      if depth > 128 || Option(state.get(expression)).contains(1) then
        fail("NODAL-HIERARCHY-058-TYPE", "deferred result type graph is cyclic or too deep")
      if !Option(state.get(expression)).contains(2) && expression.typeIntent.nonEmpty then
        work += 1
        if work > 65536 then
          fail("NODAL-HIERARCHY-058-TYPE", "deferred result type graph exceeds its finite budget")
        val reference = Option(expressionIds.get(expression)).getOrElse(
          fail("NODAL-HIERARCHY-058-TYPE", "deferred expression has no captured owner")
        )
        if expressionRegions.contains(reference) then
          fail(
            "NODAL-HIERARCHY-058-TYPE",
            "deferred boundary expressions require direct module scope"
          )
        state.put(expression, 1)
        val intent = expression.typeIntent.get
        work += expression.operands.size + intent.dependencies.size
        if work > 65536 then
          fail("NODAL-HIERARCHY-058-TYPE", "deferred result type edges exceed their finite budget")
        val operands = new java.util.IdentityHashMap[AnyRef, java.lang.Boolean]()
        expression.operands.foreach(value => operands.put(value.asInstanceOf[AnyRef], true))
        if intent.dependencies.isEmpty || intent.dependencies.exists(dependency =>
            !operands.containsKey(dependency.asInstanceOf[AnyRef])
          )
        then fail("NODAL-HIERARCHY-058-TYPE", "type intent lost its exact operand dependency")
        intent.dependencies.foreach: dependency =>
          if !digitalOwnerOf(dependency).contains(modulePath(reference.module)) then
            fail("NODAL-HIERARCHY-058-TYPE", "type intent operand belongs to another module")
          dependency match
            case value: KernelExpr[?] => visit(value, depth + 1)
            case _ => ()
        DeferredResultType.requireFinal(intent.dependencies, "result type materialization")
        moduleStack.clear()
        moduleStack += records(reference.module)
        val actual = intent.resolve()
        validateShapeType(
          CandidateRuntime.dataTypeFromDescriptor(actual),
          reference.module,
          cardinality = true
        )
        expression.sealResultType(actual)
        state.put(expression, 2)
    try expressionValues.valuesIterator.toVector.foreach(visit(_, 0))
    finally
      moduleStack.clear()
      moduleStack ++= previousModules
    if expressionValues.valuesIterator.exists(!_.typeFinalized) then
      fail("NODAL-HIERARCHY-058-TYPE", "unresolved result type cannot leave construction")

  private def materializeInstanceDigitalTypes(
      queries: StructuralParameterBinding.InstanceQueries
  ): Unit =
    instanceDigitalPorts.foreach: (reference, boundary) =>
      val (instance, port) = boundary
      val declaration = records(reference.module).declarations(reference.index)
      val deferred = declaration.dataType.collect { case value: KernelInstanceDataType[?] =>
        value
      }.getOrElse(fail("NODAL-HIERARCHY-057-001", "instance port lost its deferred type view"))
      deferred.seal(materializeInstanceTypeDescriptors(
        reference.module,
        instance,
        port,
        Vector(deferred.formal),
        queries
      ).head)
      validateShapeType(deferred, reference.module, cardinality = false)

  private def materializeInstanceDigitalInouts(queries: StructuralParameterBinding.InstanceQueries)
      : Unit =
    instanceDigitalInouts.foreach: (reference, boundary) =>
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
          NodeArrayShapeIntent.InstancePort(instance, value)
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
    val input: DeclarationRef | ExpressionRef = Option(declarationIds.get(array))
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
    s"${pathOfReference(index.input)}.at(${indices.mkString(",")})$suffix"

  private def digitalWidthBound(descriptor: KernelTypeDescriptor, owner: Long): StructuralBound =
    if descriptor.arguments.size != 1 then
      fail("NODAL-DIGITAL-054-001", "digital scalar type requires exactly one width")
    val analysis = new StructuralBoundAnalysis(
      owner,
      "NODAL-DIGITAL-054-001",
      "digital width",
      signed64Literals = true
    )
    val bound = descriptor.arguments.head match
      case width: Int => analysis(width)
      case width: Expr[?] => analysis(width.asInstanceOf[Expr[Integer]])
      case _ => fail("NODAL-DIGITAL-054-001", "digital width requires a static Integer expression")
    if bound.bounds.lower <= 0L then
      fail(
        "NODAL-DIGITAL-054-001",
        "digital width must be positive for every legal parameter value"
      )
    bound.parameters.distinct.foreach(markStructuralParameterEffect(_, "width"))
    bound

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
    val _ = shapeTypeFootprint(dataType, owner, cardinality, storageContext)

  private def shapeTypeFootprint(
      dataType: DataType[?],
      owner: Long,
      cardinality: Boolean,
      storageContext: Boolean,
      bitPatternOnly: Boolean = false,
      fixedBitPattern: Boolean = false
  ): Option[BigInt] =
    val active = new java.util.IdentityHashMap[DataType[?], java.lang.Boolean]()
    var remaining = 65536
    def identifier(name: String): Unit =
      if name.length > 1024 || !name.matches("[A-Za-z_][A-Za-z0-9_]*") then
        fail("NODAL-DIGITAL-054-004", "Struct and field names require bounded portable identifiers")
    def checkedFootprint(bits: BigInt): BigInt =
      if bits <= 0 || bits > BigInt(Long.MaxValue) then
        fail(
          "NODAL-DIGITAL-054-004",
          "digital type maximum bit footprint exceeds positive signed 64-bit bounds"
        )
      bits
    def visit(current: DataType[?], depth: Int, field: Boolean): Option[BigInt] =
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
            val bounds = digitalWidthBound(descriptor, owner).bounds
            if fixedBitPattern && bounds.lower != bounds.upper then
              crossingFailure("consumer requires a fixed whole digital bit pattern")
            Some(BigInt(bounds.upper))
          case "Bool" => Some(BigInt(1))
          case "Integer" | "Real" => if field then Some(BigInt(64)) else None
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
              case values: Seq[?] => values.toVector.map:
                  case value: StructField[?] => value
                  case _ => fail("NODAL-DIGITAL-054-004", "Struct inventory contains a non-field")
              case _ => fail("NODAL-DIGITAL-054-004", "Struct field inventory is missing")
            if fields.isEmpty || fields.size > 65536 || fields.map(
                _.name
              ).distinct.size != fields.size
            then
              fail("NODAL-DIGITAL-054-004", "Struct requires nonempty unique bounded fields")
            val footprint = fields.foldLeft(BigInt(0)): (total, member) =>
              identifier(member.name)
              val bits = visit(member.dataType, depth + 1, field = true).getOrElse(
                fail(
                  "NODAL-DIGITAL-054-004",
                  "Struct fields require storable digital scalar or aggregate types"
                )
              )
              checkedFootprint(total + bits)
            Some(footprint)
          case "Vec" =>
            validateShapeDescriptor(descriptor, owner, cardinality)
            val element = descriptor.arguments.head.asInstanceOf[DataType[?]]
            val dimensions = descriptor.arguments(1).asInstanceOf[Seq[Dimension]]
            val analysis =
              new StructuralBoundAnalysis(owner, "NODAL-SHAPE-043-001", "Vec dimension")
            visit(element, depth + 1, field).map: bits =>
              checkedFootprint(dimensions.foldLeft(bits)((total, dimension) =>
                val bounds = analysis(dimension).bounds
                if fixedBitPattern && bounds.lower != bounds.upper then
                  crossingFailure("consumer requires a fixed whole digital bit pattern")
                total * BigInt(bounds.upper)
              ))
          case "Enum" =>
            descriptor.arguments match
              case Vector(abi: ResolvedEnumAbi) => Some(BigInt(abi.width))
              case _ => fail("NODAL-DIGITAL-054-004", "enum type has no resolved ABI")
          case _ => None
      finally active.remove(current)
    val footprint = visit(dataType, 0, field = storageContext)
    if storageContext && footprint.isEmpty then
      fail("NODAL-DIGITAL-054-010", "digital storage requires a directionless storable value type")
    footprint

  private def validateShapeDescriptor(
      descriptor: KernelTypeDescriptor,
      owner: Long,
      cardinality: Boolean
  ): Unit =
    val element = descriptor.arguments.headOption.collect:
      case candidate: DataType[?] => candidate
    val dimensions = descriptor.arguments.lift(1).toVector.flatMap:
      case values: Seq[?] => values.toVector
      case _ => Vector.empty
    if element.isEmpty || dimensions.isEmpty then
      fail("NODAL-SHAPE-043-001", "Vec requires an element type and at least one dimension")

    val analysis = new StructuralBoundAnalysis(owner, "NODAL-SHAPE-043-001", "Vec dimension")
    val bounds = dimensions.map: dimension =>
      val bound = dimension match
        case value: Int => analysis(value)
        case value: Param[?] => analysis(value.asInstanceOf[Expr[Integer]])
        case value: KernelExpr[?] => analysis(value.asInstanceOf[Expr[Integer]])
        case _ =>
          fail(
            "NODAL-SHAPE-043-001",
            "Vec dimensions require a positive static Integer expression"
          )
      if bound.bounds.lower <= 0L then
        fail(
          "NODAL-SHAPE-043-001",
          "Vec dimension must be positive for every legal parameter setting"
        )
      bound
    if cardinality &&
      bounds.foldLeft(BigInt(1))((size, bound) => size * BigInt(bound.bounds.upper)) >
        BigInt(Long.MaxValue)
    then fail("NODAL-SHAPE-043-001", "Vec maximum element count exceeds signed 64-bit bounds")
    bounds.flatMap(_.parameters).distinct.foreach: reference =>
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
      code: String
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
        else None
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
          .orElse(shapeIndices.get(expression).filter(_.resultDimensions.nonEmpty)
            .map(_.resultDimensions))
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
    val (inputReference, structuralInput, inputPath) =
      structuralShapeInput(input, module.handle, "NODAL-SHAPE-043-002")
    structuralInput match
      case None =>
        // Preserve the previously exposed inert expression surface until shaped
        // internal storage has an owned SSA carrier and lowering contract.
        val _ = captureExpression(expression)
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
        val reference = captureExpression(expression).getOrElse(
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
    case expression: KernelExpr[?] if expression.literal.exists(_.kind == "integer") =>
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
      scopeOverride: Option[Option[DigitalValueRegionRecord]] = None
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
        val fresh = ExpressionRef(module.handle, module.expressionCount)
        module.expressionCount += 1
        fresh
      if !reductionProofBuilding then
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
          expressionValues.update(reference, expression)
          analogStack.lastOption.foreach(_.expressions += reference)
          expression.operands
        case _ => structuralOperands
      // Private partition scaffold arguments have lexical paths, but no authored source identity.
      if !reductionProofBuilding && reference.index >= 0 then
        semanticOrigin.captureExpression(module.handle, reference.index, value, operands)
      reference

  def registerExpression(value: AnyRef): Unit = value match
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
        .foreach(validateShapeType(
          _,
          currentModule.handle,
          cardinality = true,
          storageContext = true
        ))
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
    parent.instances += record
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
    Option(instanceIds.get(instance)).getOrElse(
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

    def visit(candidate: Any): Unit = candidate match
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
          expression.operands.foreach(visit)
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

    visit(value)

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

    AnalogHierarchyOverridePolicy.validate(
      overrideEvidence(parent, record, target, value)
    ) match
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
    declaration.attributes.collectFirst:
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
    private val cache = mutable.HashMap.empty[ExpressionRef, StructuralBound]
    private val active = mutable.HashSet.empty[ExpressionRef]
    private val identityIds = mutable.HashMap.empty[(Int, String, Vector[Int]), Int]
    private val identityKeys = mutable.ArrayBuffer.empty[(Int, String, Vector[Int])]

    private def intern(key: (Int, String, Vector[Int])): Int = identityIds.getOrElseUpdate(
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
      val fact = parameterBound(
        parameter,
        owner,
        diagnosticCode,
        subject,
        allowUnbounded || allowUnboundedParameters
      )
      val reference = fact.parameters.head
      fact.copy(identity =
        Some(intern((1, s"${reference.module}:${reference.index}", Vector.empty)))
      )

    /** Predicate admission shares the same raw Integer DAG and checked arithmetic. An empty actual
      * Generate has no attained value: retain None while still checking every raw operand and op.
      */
    private val predicateCache = mutable.HashMap.empty[ExpressionRef, Option[StructuralBound]]
    private val predicateActive = mutable.HashSet.empty[ExpressionRef]
    def predicateBound(value: Expr[Integer]): Option[StructuralBound] = value match
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
              def generatedFact(generated: GeneratedRegionRecord): Option[StructuralBound] =
                if !generationStack.contains(generated) then
                  invalid("Generate induction escaped its actual active lexical scope")
                val domain =
                  loopRegions.valuesIterator.find(_.loop.exists(_.generated.contains(generated)))
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
              val result = collectionArguments.get(reference) match
                case Some((region, _)) =>
                  region.captures.valuesIterator.find(_._2 eq expression) match
                    case Some((source, _)) => predicateBound(source.asInstanceOf[Expr[Integer]])
                    case None
                        if region.loop.exists(_.generated.nonEmpty) &&
                          (region.arguments.head._1 eq expression) =>
                      generatedFact(region.loop.get.generated.get)
                    case _ => invalid(
                        "procedural induction or unadmitted Integer formal in static predicate"
                      )
                case None if expression.operation.contains("generate_index") =>
                  if expression.operands.nonEmpty then invalid("malformed Generate induction")
                  val generated = records(owner).generatedRegions.find(_.induction == reference)
                    .getOrElse(invalid("static index lacks an actual Generate owner"))
                  generatedFact(generated)
                case None if expression.literal.nonEmpty => Some(literalBound(expression))
                case None =>
                  val operation = expression.operation.flatMap(IterationDomain.staticArithmetic)
                    .getOrElse(invalid("static predicate requires pure Integer arithmetic"))
                  if expression.operands.size != operation.arity then
                    invalid("static Integer operation has wrong arity")
                  val facts = expression.operands.map:
                    case operand: Expr[?] => predicateBound(operand.asInstanceOf[Expr[Integer]])
                    case _ =>
                      invalid("static Integer operation has an untyped or physical-unit operand")
                  if facts.forall(_.nonEmpty) then
                    Some(compose(
                      expression.operation.get,
                      facts.flatten,
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

    private def expressionBound(expression: KernelExpr[?]): StructuralBound =
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
              case Some(replacement) => apply(replacement)
              case None if reductionDomains.contains(expression) =>
                StructuralBound(
                  expression,
                  reductionDomains(expression),
                  Vector.empty,
                  Some(intern((3, expressionPath(reference), Vector.empty)))
                )
              case None if collectionArguments.contains(reference) =>
                val (region, _) = collectionArguments(reference)
                region.captures.valuesIterator.find(_._2 eq expression) match
                  case Some((source, _))
                      if expression.resultDescriptor.contains(KernelTypeDescriptor("Integer")) =>
                    apply(source.asInstanceOf[Expr[Integer]])
                  case _ => invalid("unadmitted collection Integer formal")
              case None if expression.literal.nonEmpty => literalBound(expression)
              case None =>
                val operation = expression.operation.flatMap(IterationDomain.staticArithmetic)
                  .getOrElse(invalid(
                    "hdlRange expression is not supported pure Integer arithmetic"
                  ))
                val arity = operation.arity
                if expression.operands.size != arity then
                  invalid("hdlRange Integer expression has invalid arity")
                val operands = expression.operands.map:
                  case parameter: Param[?] =>
                    parameterFact(parameter)
                  case child: KernelExpr[?] => expressionBound(child)
                  case _ => invalid("hdlRange expression has a non-static or untyped operand")
                compose(
                  expression.operation.get,
                  operands,
                  expression,
                  Some(expressionPath(reference))
                )
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
          IterationDomain.arithmetic(operation, operands.map(_.bounds))
            .fold(
              problem => invalid(problem.message, path),
              identity
            )
      val operandIds = operands.map(_.identity.get)
      val key =
        if operation == IterationDomain.Arithmetic.Maximum &&
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
        else if operandIds.forall(id => identityKeys(id)._1 == 0) then
          (0, bounds.lower.toString, Vector.empty[Int])
        else (2, name, operandIds)
      StructuralBound(
        value,
        bounds,
        operands.flatMap(_.parameters).distinct,
        Some(intern(key))
      )

    def shiftedBounds(base: IterationDomain.Bounds, offsets: Vector[(String, Dimension)])
        : IterationDomain.Bounds =
      offsets.foldLeft(base): (current, offset) =>
        IterationDomain.arithmetic(
          IterationDomain.staticArithmetic(offset._1).get,
          Vector(current, apply(offset._2).bounds)
        )
          .fold(problem => invalid(problem.message), identity)

    def shiftedAtMost(base: Dimension, offsets: Vector[(String, Dimension)], extent: Dimension)
        : Boolean =
      val shifted = offsets.foldLeft(apply(base)): (current, offset) =>
        compose(offset._1, Vector(current, apply(offset._2)), base)
      def containsWithNonnegativeSuffix(value: Dimension, depth: Int): Boolean =
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

    def apply(value: Int | Expr[Integer]): StructuralBound = value match
      case literal: Int =>
        StructuralBound(
          literal,
          IterationDomain.Bounds(literal, literal),
          Vector.empty,
          Some(intern((0, literal.toString, Vector.empty)))
        )
      case parameter: Param[?] => parameterFact(parameter)
      // Preserve the existing by-value handling of a direct immutable literal. A literal inside
      // an expression graph, in contrast, needs a captured owner for canonical serialization.
      case expression: KernelExpr[?] if expression.literal.nonEmpty => literalBound(expression)
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
    requireNoCollectionEffect("structural generation")
    if activeLoop.exists(_.loop.get.generated.isEmpty) then
      collectionFailure("nested effectful iteration is outside the bounded loop profile")
    if areaFamily && activeValueRegion.exists(parent =>
        !parent.loop.exists(_.generated.exists(_.areaFamily))
      )
    then
      fail(
        "NODAL-AREA-058-OWNER",
        "generated Area family requires a direct Module or actual generated body in this profile"
      )
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
    val inductionReference = captureExpression(induction).getOrElse(
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
    digitalRegion.foreach(_.structuralGenerate = areaFamily)
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
          !(areaFamily && Set(
            KernelSignalKind.Wire,
            KernelSignalKind.Register
          )(declaration.kind)) &&
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

  def recordShortAnalogContribution(target: Expr[Real], value: Expr[Real]): Unit =
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
          case view: TerminalView[?, ?] => renderAnalogValue(view.terminal)
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
      case expression: KernelExpr[?] if expression.literal.nonEmpty =>
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
    val metadata = expression.operands.collect { case value: MemoryPortCapture => value }
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
    val writes = operations.iterator.filter(_.kind == "memory-write").toVector
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

  private def captureOperation(kind: String, values: Vector[Any], permitMemory: Boolean): Unit =
    validateCollectionDependencies(values)
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
    requireNoCollectionEffect(
      s"hardware '$kind' effect",
      assignment = digitalAssignment || memoryWrite
    )
    val loop = Option.when(digitalAssignment)(activeLoop).flatten
    val effectRegion =
      Option.when(digitalAssignment || memoryWrite)(
        activeValueRegion.filter(_.permitsAssignment)
      ).flatten
    if !digitalAssignment then requireLoopEffect(s"hardware '$kind' effect")
    loop.foreach: region =>
      if !effectRegion.nonEmpty || region.loop.get.generated != activeGeneratedRegion
      then
        collectionFailure("digital assignments require their exact loop lexical scope")
      validateDigitalLoopDomain(region)
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
        collectionFailure("digital loop assignment requires exactly matching recursive types")
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
    ).contains(kind)
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
          if Set("assignment", "value-connect", "memory-write")(kind) then nextEffectOrdinal()
          else 0
      val captured = Operation(
        owner,
        kind,
        values.toVector,
        if effectRegion.nonEmpty then None else activeGeneratedRegion,
        if digitalAssignment || memoryWrite || (structuralTopology && activeGeneratedRegion.nonEmpty)
        then
          semanticOrigin.captureSemanticSource()
        else None,
        effectRegion,
        sourceOrdinal,
        activeEventProcess
      )
      operations += captured
      moduleStack.lastOption.foreach(module =>
        // Admission distinguishes analog storage effects; source naming retains
        // the shared assignment sink-affinity semantics for both kinds.
        semanticOrigin.captureOperation(
          module.handle,
          if kind == "analog-assignment" then "assignment" else kind,
          captured.values
        )
      )

  private def provisionalModulePath(handle: Long): String =
    val record = records(handle)
    record.parentAtConstruction match
      case None => record.className
      case Some(parentHandle) =>
        val parent = records(parentHandle)
        val ordinal = parent.instances
          .find(_.child == handle)
          .map(_.ordinal)
          .orElse:
            if !record.attached && moduleStack.exists(_.handle == handle) then
              Some(parent.instances.size)
            else None
          .getOrElse(
            fail("NODAL-HIERARCHY-020", "child Module has no Instance record")
          )
        s"${provisionalModulePath(parentHandle)}.${record.className}_$ordinal"

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
    fsmResults.get(reference).map((record, role) =>
      s"${expressionPath(record.reference)}.$role"
    ).orElse(
      fsmActionRegions.get(reference).flatMap(_.fsmAction).map(header =>
        s"${expressionPath(header.machine)}.action${header.ordinal}"
      )
    ).orElse(enumSwitchArms.get(reference).flatMap(_.switchArm).map(header =>
      s"${expressionPath(header.switch)}.arm${header.index}"
    )).orElse(collectionArguments.get(reference).map: (region, ordinal) =>
      if ordinal == 0 && region.structuralGenerate then
        expressionPath(region.loop.get.generated.get.induction)
      else s"${expressionPath(region.reference)}.arg$ordinal").orElse(structuralArms.get(
      reference
    ).flatMap(_.structuralArm).map(arm =>
      s"${expressionPath(arm.conditional)}.${if arm.whenTrue then "then" else "else"}"
    ))
      .orElse(behavioralArms.get(reference).flatMap(_.behavioralArm).map(arm =>
        s"${expressionPath(arm.conditional)}.${if arm.whenTrue then "then" else "else"}"
      ))
      .orElse(loopRegions.get(reference).flatMap(region =>
        region.loop.flatMap(_.generated).map(record =>
          if region.structuralGenerate then generatedRegionPath(record)
          else s"${generatedRegionPath(record)}.digital_loop"
        )
      ))
      .orElse(semanticResult.flatMap(_.expressionPaths.get(reference.module -> reference.index)))
      .getOrElse(s"${modulePath(reference.module)}.expr_${reference.index}")

  private def instancePath(parent: Long, ordinal: Int): String =
    semanticResult
      .flatMap(_.instancePaths.get(parent -> ordinal))
      .getOrElse(s"${modulePath(parent)}.instance_$ordinal")

  def pathOf(value: Any): Option[String] = value match
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

  private def renderAny(value: Any, owner: Long): String = value match
    case candidate if Option(candidate).isEmpty => "null"
    case text: String => text
    case boolean: Boolean => boolean.toString
    case integer: Int => integer.toString
    case long: Long => long.toString
    case double: Double => java.lang.Double.toString(double)
    case big: BigInt => big.toString
    case expression: KernelExpr[?] if expression.literal.nonEmpty =>
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
    case sequence: Seq[?] => sequence.map(renderAny(_, owner)).mkString("[", ",", "]")
    case set: Set[?] => set.toVector.map(renderAny(_, owner)).sorted.mkString("[", ",", "]")
    case reference: AnyRef =>
      Option(declarationIds.get(reference)).map(declarationPath)
        .orElse(
          Option(expressionIds.get(reference)).map(expressionPath)
        )
        .orElse(Option(domainIds.get(reference)).map(domainPath))
        .getOrElse(stableClassName(reference))
    case other => other.toString

  private def renderType(dataType: DataType[?], owner: Long): String =
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
          case values: Seq[?] => values.collect:
              case field: StructField[?] =>
                s"${field.name}:${renderType(field.dataType, owner)}"
          case _ => Vector.empty
        s"Struct($name{${fields.mkString(",")}})"
      case "Vec" =>
        val element = descriptor.arguments.headOption.collect:
          case value: DataType[?] => renderType(value, owner)
        val dimensions = descriptor.arguments.lift(1).toVector.flatMap:
          case values: Seq[?] => values.map(renderShapeDimension(_, owner))
          case _ => Vector.empty
        s"Vec(${element.getOrElse("unknown")};${dimensions.mkString("x")})"
      case kind @ ("Bits" | "UInt" | "SInt") =>
        if descriptor.arguments.size != 1 then
          fail("NODAL-DIGITAL-054-001", "digital scalar type requires exactly one width")
        s"$kind(${renderShapeDimension(descriptor.arguments.head, owner)})"
      case kind if descriptor.arguments.nonEmpty =>
        s"$kind(${descriptor.arguments.map(renderAny(_, owner)).mkString(",")})"
      case kind => kind

  private def renderShapeDimension(value: Any, owner: Long): String = value match
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
    case expression: KernelExpr[?] if expression.literal.exists(_.kind == "integer") =>
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
    selectedRequirementBindings.clear()
    val roots = records.values.filter(_.parentAtConstruction.isEmpty).toVector
    if roots.size != 1 then fail("NODAL-ROOT-016", s"expected one root Module, found ${roots.size}")
    val pending = mutable.ArrayDeque((roots.head.handle, 0))
    val visited = mutable.HashSet.empty[Long]
    var edges = 0L
    while pending.nonEmpty do
      val (handle, depth) = pending.removeHead()
      if depth > 128 || visited.size >= 65536 || !visited.add(handle) then
        fail("NODAL-HIERARCHY-057-002", "recursive or excessive concrete hierarchy")
      val module = records(handle)
      module.domains.filter(_.kind != KernelDomainKind.Required).foreach: domain =>
        resolved.update(domain.reference, domainPath(domain.reference))
      if module.parentAtConstruction.isEmpty then
        module.domains.filter(_.kind == KernelDomainKind.Required).foreach: requirement =>
          fail(
            "NODAL-ROOT-DOMAIN-016",
            s"top-level domain requirement '${requirement.name}' is unbound",
            Some(modulePath(handle))
          )
      module.instances.foreach: instance =>
        edges += 1
        if edges > 262144 then
          fail("NODAL-HIERARCHY-057-002", "hierarchy binding edge budget exceeded")
        val child = records(instance.child)
        val requirements = child.domains.filter(_.kind == KernelDomainKind.Required).toVector
        requirements.foreach: requirement =>
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
    val byPath = records.valuesIterator.flatMap(_.domains).map(domain =>
      domainPath(domain.reference) -> domain
    ).toMap
    selectedRequirementBindings.keysIterator.map(reference => resolved(reference)).toSet.foreach:
      path =>
        val actual = byPath(path).domain
        Option(effectiveResets.get(actual)).foreach: reset =>
          if !clockResetSafe(reset, actual) then
            fail(
              "NODAL-RDC-057-001",
              "instance actual reset does not release safely for its exact receiving clock",
              Some(path)
            )
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
        val requirements = records(owner).domains.filter(_.kind == KernelDomainKind.Required)
        if requirements.size != 1 then
          fail(
            "NODAL-DECL-DOMAIN-016",
            "inherited declaration requires one exact child-local requirement",
            Some(path)
          )
        requirements.head.domain

  private def declarationCandidate(declaration: DeclarationRecord): Option[ClockDomain] =
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
  ): Option[String] =
    declarationCandidate(declaration).map(domain => domainPath(domainRef(domain))).orElse:
      declarationDomain(declaration, resolved).map: actual =>
        val local = records(declaration.reference.module).domains.filter(domain =>
          domainPath(domain.reference) == actual || resolved.get(domain.reference).contains(actual)
        )
        if local.size != 1 then
          fail("NODAL-DECL-DOMAIN-016", "declaration requires one exact local domain identity")
        domainPath(local.head.reference)

  private def declarationDomain(
      declaration: DeclarationRecord,
      resolved: Map[DomainRef, String]
  ): Option[String] = declarationCandidate(declaration) match
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
        (domain.domain.clock.toVector :+ domain.domain.reset).exists(value =>
          capabilitySourceRoot(value).contains(declaration.reference)
        )
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
    val nets = records.values.toVector.flatMap: module =>
      module.declarations.collect:
        case declaration if declaration.kind == KernelSignalKind.DigitalInout =>
          val related = operations.iterator
            .filter: operation =>
              operation.values.exists:
                case reference: AnyRef => reference eq declaration.value
                case _ => false
            .map(_.kind)
            .toVector
          KernelResolvedNetSnapshot(
            declarationPath(declaration.reference),
            declaration.dataType.map(renderType(_, module.handle)).getOrElse("Bits"),
            attribute(declaration, "mode", module.handle, "unknown"),
            attribute(declaration, "placement", module.handle, "unknown"),
            attribute(declaration, "profile", module.handle, "unknown"),
            related,
            instanceDigitalInouts.get(declaration.reference).map: (instance, port) =>
              KernelInterfaceBoundarySnapshot(
                instancePath(module.handle, instance.ordinal),
                declarationPath(port)
              )
          )
    nets.sortBy(_.path)

  private def conservativeArrayBindingRecords
      : Vector[(KernelConservativeArrayBindingSnapshot, ConservativeArrayBindingRecord)] =
    val ordinals = mutable.HashMap.empty[Long, Int]
    operations.toVector.collect:
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
    operations.toVector.flatMap: operation =>
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
        expression.operands.foreach(visit)
      case _ => ()
    roots.foreach(visit)

  private def markConservativeBindingDependencies(): Unit =
    operations.foreach: operation =>
      operation.values.foreach:
        case root: InstanceArrayRootRecord =>
          markInstanceShapeDependencies(
            records(operation.owner).instances(root.instanceOrdinal),
            records(root.port.module).declarations(root.port.index).conservativeDimensions
          )
        case _ => ()
    instanceInterfacePorts.foreach: (_, binding) =>
      val (instance, port) = binding
      records(port.module).declarations(port.index).value match
        case array: InterfaceArray[?, ?] =>
          markInstanceShapeDependencies(instance, Vector(array.formalCount))
        case _ => ()

  private def prepareInterfaceFormalTypes(): Vector[DataType[?]] =
    // Type validation records structural effects on parameter declarations. Capture
    // endpoint identities first, before those existing declaration entries mutate.
    val endpoints = records.valuesIterator.flatMap: module =>
      module.declarations.iterator.collect:
        case declaration
            if declaration.value.isInstanceOf[InterfaceEndpoint] &&
              !declaration.value.asInstanceOf[InterfaceEndpoint].boundary =>
          module.handle -> declaration.value.asInstanceOf[InterfaceEndpoint]
    .toVector
    endpoints.flatMap: (owner, endpoint) =>
      val types = ConstructionInterfaceLayout.leaves(endpoint.definition, endpoint.role)
        .flatMap(_.dataType)
      types.foreach(validateShapeType(_, owner, cardinality = true))
      types

  private def materializeInstanceInterfaceDimensions(
      queries: StructuralParameterBinding.InstanceQueries
  ): Unit =
    instanceInterfacePorts.foreach: (reference, binding) =>
      val (instance, port) = binding
      val owner = records(reference.module)
      val declaration = owner.declarations(reference.index)
      val endpoint = declaration.value.asInstanceOf[InterfaceEndpoint]
      val formals = ConstructionInterfaceLayout.leaves(endpoint.definition, endpoint.role)
        .flatMap(_.dataType).map(CandidateRuntime.typeDescriptor).distinct
      val actuals =
        materializeInstanceTypeDescriptors(reference.module, instance, port, formals, queries)
          .map(CandidateRuntime.dataTypeFromDescriptor)
      actuals.foreach(validateShapeType(_, reference.module, cardinality = true))
      instanceInterfaceTypes(reference) = formals.zip(actuals).toMap
      if declaration.kind == KernelSignalKind.InterfaceArray then
        val graph = try
          queries.resolveInterfaceDimensions(
            modulePath(reference.module),
            instancePath(reference.module, instance.ordinal),
            declarationPath(port)
          )
        catch
          case error: IllegalArgumentException =>
            fail("NODAL-HIERARCHY-058-INTERFACE", error.getMessage)
        val previousModules = moduleStack.toVector
        moduleStack.clear(); moduleStack += owner
        try
          val dimensions = materializeStructuralDimensions(graph)
          instanceInterfaceDimensions(reference) = dimensions
          owner.declarations.update(
            reference.index,
            declaration.copy(
              attributes = declaration.attributes :+ ("count" -> dimensions.head)
            )
          )
        finally
          moduleStack.clear(); moduleStack ++= previousModules

  // The finalized pre-materialization snapshot owns these paths. Cache its lookup once,
  // shared by conservative, digital-port and Interface dimensions.
  private var structuralMaterializationValues: Option[Map[String, AnyRef]] = None

  private def materializeStructuralDimensions(
      graph: StructuralParameterBinding.ResolvedDimensions
  ): Vector[Dimension] =
    val ownedValues = structuralMaterializationValues.getOrElse:
      val values = records.valuesIterator.flatMap(_.declarations.iterator)
        .map(value => declarationPath(value.reference) -> value.value).toMap ++
        expressionValues.iterator.map((reference, value) =>
          expressionPath(reference) -> value
        ).toMap
      structuralMaterializationValues = Some(values)
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
    def materialize(id: Int): Dimension = values.getOrElseUpdate(
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
    val captured = operations.toVector
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
              StructuralParameterBinding.resolveDimensions(
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
    val dimensions = snapshot.modules.flatMap(_.declarations).map(value =>
      value.path -> value.conservativeDimensions
    ).toMap ++
      snapshot.instanceArrayRoots.map(value => value.path -> value.dimensions) ++
      snapshot.shapeViews.map(value => value.path -> value.dimensions) ++
      snapshot.shapeIndices.map(value => value.path -> value.resultDimensions)
    try
      snapshot.instanceArrayRoots.foreach(StructuralParameterBinding.validateRoot(snapshot, _))
      snapshot.conservativeArrayBindings.foreach: binding =>
        StructuralParameterBinding.validateRelation(
          snapshot,
          binding,
          dimensions.getOrElse(binding.left, Vector.empty),
          dimensions.getOrElse(binding.right, Vector.empty)
        )
    catch case error: IllegalArgumentException => fail("NODAL-SHAPE-043-007", error.getMessage)
    snapshot.modules.foreach: module =>
      module.instances.foreach: instance =>
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
    val edges = operations.toVector.flatMap: operation =>
      if Set("node-connect", "terminal-connect", "inout-pass-through").contains(operation.kind) &&
        operation.values.size >= 2
      then
        (pathOf(operation.values(0)), pathOf(operation.values(1))) match
          case (Some(left), Some(right)) =>
            val (first, second) =
              if operation.kind == "node-connect" && right < left then right -> left
              else left -> right
            val ordinal = ordinals.getOrElse(operation.owner, 0)
            ordinals.update(operation.owner, ordinal + 1)
            val path = operation.generatedScope.map(_ =>
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

  private def domainSourcePath(value: AnyRef): String = selectedPath(value).getOrElse(
    fail("NODAL-DOMAIN-020", "ClockDomain source is outside this construction transaction")
  )

  private def snapshots(resolved: Map[DomainRef, String]): Vector[KernelModuleSnapshot] =
    val byPath = records.valuesIterator.flatMap(_.domains).map(domain =>
      domainPath(domain.reference) -> domain
    ).toMap
    val demandedResets = mutable.HashSet.empty[DomainRef]
    records.valuesIterator.flatMap(_.declarations).foreach: declaration =>
      if !contextualFsmInitializer(declaration) && declaration.attributes.exists {
          case ("initial", Some(_: Expr[?])) => true
          case _ => false
        }
      then declarationCandidate(declaration).foreach(domain => demandedResets += domainRef(domain))
    selectedRequirementBindings.valuesIterator.foreach: (actual, _, _, _) =>
      if effectiveResets.containsKey(byPath(resolved(actual)).domain) then demandedResets += actual
    records.values.toVector.sortBy(record => modulePath(record.handle)).map: module =>
      val domains = module.domains.toVector.map: domain =>
        val policyAttributes =
          domain.domain.resetPolicy.toVector.flatMap(resetPolicyAttributes)
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
          if domain.kind == KernelDomainKind.Required then Vector.empty else metadata.sortBy(_._1),
          relationshipSnapshot(domain),
          Option.when(
            domain.kind == KernelDomainKind.Required
          )(domain.domain.clock.map(domainSourcePath)).flatten,
          Option.when(domain.kind == KernelDomainKind.Required && demandedResets(domain.reference))(
            domainSourcePath(domain.domain.reset)
          )
        )
      val declarations = module.declarations.toVector.map: declaration =>
        val retainedAttributes = declaration.attributes ++
          Option.when(Set(
            KernelSignalKind.Register,
            KernelSignalKind.Memory
          ).contains(declaration.kind))(
            declarationCandidate(declaration).toVector.map(domain =>
              "declared_domain" -> domainPath(domainRef(domain))
            )
          ).toVector.flatten
        KernelDeclarationSnapshot(
          declarationPath(declaration.reference),
          declaration.kind.label,
          declarationName(declaration.reference),
          declaration.dataType.map: dataType =>
            val typeOwner = dataType match
              case deferred: KernelInstanceDataType[?] if !deferred.finalized =>
                instanceDigitalPorts.get(declaration.reference)
                  .orElse(
                    instanceDigitalInouts.get(declaration.reference)
                  ).map(_._2.module).getOrElse(
                    fail(
                      "NODAL-HIERARCHY-057-001",
                      "deferred type lost its exact child declaration"
                    )
                  )
              case _ => module.handle
            renderType(dataType, typeOwner)
          ,
          localDeclarationDomain(declaration, resolved),
          retainedAttributes.map: (name, value) =>
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
          generatedScopeSnapshot(module.generatedRegions.find(region =>
            region.declarations.contains(declaration.reference) &&
              (region.areaFamily || declaration.kind == KernelSignalKind.AnalogNodeArray)
          )),
          declarationDomain(declaration, resolved),
          instanceDigitalPorts.get(declaration.reference).map: (instance, port) =>
            KernelInstancePortSnapshot(
              instancePath(module.handle, instance.ordinal),
              declarationPath(port),
              if declaration.kind == KernelSignalKind.InstanceInput then "input" else "output"
            ),
          instanceInterfacePorts.get(declaration.reference).map: (instance, port) =>
            KernelInterfaceBoundarySnapshot(
              instancePath(module.handle, instance.ordinal),
              declarationPath(port)
            ),
          instanceDigitalInouts.get(declaration.reference).map: (instance, port) =>
            KernelInterfaceBoundarySnapshot(
              instancePath(module.handle, instance.ordinal),
              declarationPath(port)
            )
        )
      val instances = module.instances.toVector.map: instance =>
        val child = records(instance.child)
        val bindings = child.domains.filter(_.kind == KernelDomainKind.Required).map: domain =>
          domain.name -> domainPath(selectedRequirementBindings(domain.reference)._1)
        val actualBindings =
          child.domains.filter(_.kind == KernelDomainKind.Required).flatMap: requirement =>
            val (actualRef, origin, _, _) = selectedRequirementBindings(requirement.reference)
            val actual = records(actualRef.module).domains(actualRef.index)
            actual.domain.clock.toVector.map: clock =>
              val physical = byPath(resolved(actualRef))
              val reset = Option(effectiveResets.get(physical.domain)).map: value =>
                if actual.kind == KernelDomainKind.Required then
                  domainSourcePath(actual.domain.reset)
                else domainSourcePath(value)
              KernelInstanceDomainBindingSnapshot(
                instancePath(module.handle, instance.ordinal) + "." + requirement.name,
                domainPath(requirement.reference),
                domainPath(actualRef),
                domainSourcePath(clock),
                reset,
                origin
              )
        val parameters = instance.parameterOverrides.toVector.map:
          case (parameter, value) =>
            val reference = parameter match
              case candidate: AnyRef =>
                Option(declarationIds.get(candidate)).getOrElse(
                  fail(
                    "NODAL-PARAMETER-BINDING-016",
                    "instance parameter override targets an unknown parameter",
                    Some(instancePath(module.handle, instance.ordinal))
                  )
                )
              case _ =>
                fail(
                  "NODAL-PARAMETER-BINDING-016",
                  "instance parameter override is not a declaration",
                  Some(instancePath(module.handle, instance.ordinal))
                )
            if reference.module != instance.child then
              fail(
                "NODAL-PARAMETER-BINDING-017",
                "instance parameter override targets another Module",
                Some(instancePath(module.handle, instance.ordinal))
              )
            declarationName(reference) -> renderAny(value, module.handle)
        KernelInstanceSnapshot(
          instancePath(module.handle, instance.ordinal),
          modulePath(instance.child),
          instance.lexicalDomain.flatMap(domain => resolved.get(domainRef(domain))),
          bindings.toVector.sortBy(_._1),
          parameters.sortBy(_._1),
          generatedScopeSnapshot(instance.generatedScope),
          actualBindings.toVector
        )
      KernelModuleSnapshot(
        modulePath(module.handle),
        module.className,
        domains,
        declarations,
        instances
      )

  private def generatedBound(value: Any): String = value match
    case literal: Int => literal.toString
    case parameter: Param[?] =>
      Option(declarationIds.get(parameter)).map(declarationPath).getOrElse(
        fail("NODAL-ITERATION-043-001", "generated bound parameter has no semantic identity")
      )
    case expression: KernelExpr[?] if expression.literal.exists(_.kind == "integer") =>
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
    val module = records(record.owner)
    val local = s"generate_${record.ordinal}"
    record.parentOrdinal match
      case None => s"${modulePath(record.owner)}.$local"
      case Some(parent) =>
        val parentRecord = module.generatedRegions(parent)
        s"${generatedRegionPath(parentRecord)}.$local"

  private def generatedSnapshots(): Vector[KernelGeneratedRegionSnapshot] =
    records.values.toVector
      .sortBy(record => modulePath(record.handle))
      .flatMap: module =>
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
              KernelDigitalGeneratedBody(
                value.captures.valuesIterator.map(capture => pathOf(capture._1).get).toVector,
                regionArguments(value),
                value.loop.get.sourceOrdinal
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

  private def waiverSnapshots(
      sourceMap: Vector[SourceMapEntry]
  ): Vector[KernelWaiverSnapshot] =
    val sourceByPath = sourceMap.map(entry => entry.semanticPath -> entry.source).toMap
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
    val sourceByPath = sourceMap.map(entry => entry.semanticPath -> entry.source).toMap
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
    val sources = sourceMap.map(entry => entry.semanticPath -> entry.source).toMap
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
    val sources = sourceMap.map(entry => entry.semanticPath -> entry.source).toMap
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
    val sources = sourceMap.map(entry => entry.semanticPath -> entry.source).toMap
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
    shapeIndices.valuesIterator.flatMap(index =>
      index.indices.iterator ++ index.resultDimensions.iterator ++
        index.sourceUses.iterator.flatMap(_._2.iterator)
    ) ++ operations.iterator.flatMap(_.values.collect { case root: InstanceArrayRootRecord =>
      root
    })
      .flatMap(_.dimensions.iterator.flatten) ++ shapeViews.valuesIterator.flatMap(view =>
      view.dimensions.iterator ++ view.slice.iterator.flatMap(selection =>
        Iterator(selection.lower, selection.upperExclusive)
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
          reachable += reference
          if expression.literal.isEmpty then expression.operands.foreach(visit)
      case _: Param[?] => ()
      case _ => ()

    roots.foreach(visit)
    reachable.toSet

  private def boundaryElementKind(dataType: DataType[?]): String =
    var current = dataType
    var depth = 0
    var descriptor = CandidateRuntime.typeDescriptor(current)
    while descriptor.kind == "Vec" && depth <= 128 do
      current = descriptor.arguments.headOption.collect { case value: DataType[?] => value }
        .getOrElse(fail("NODAL-DIGITAL-054-018", "capability Vec has no element type"))
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
          ).map(value => valueReference(value._1))
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
    operations.toVector.filter(operation =>
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
      val ordinal = ordinals.getOrElse(operation.owner, 0)
      ordinals.update(operation.owner, ordinal + 1)
      val owner = modulePath(operation.owner)
      val declaration = records(target.module).declarations(target.index)
      if declaration.dataType.exists(isBoundaryCapability) then
        if declaration.kind != KernelSignalKind.Output then
          fail("NODAL-DIGITAL-054-018", "capability assignment requires an output boundary")
        operation.values(1) match
          case value: Expr[?] => validateBoundarySource(value, declaration, resolved)
          case _ => fail("NODAL-DIGITAL-054-018", "capability assignment requires a typed source")
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
    val expressions = expressionValues.toVector.map: (reference, expression) =>
      (expression, expressionPath(reference), modulePath(reference.module), reference.module)
    DigitalInterfaceContract.validateValues(
      expressions.map((expression, _, owner, _) => expression -> owner),
      interfaces,
      pathOf,
      digitalOwnerOf,
      nets.flatMap(net => net.boundary.map(boundary => net.path -> boundary.endpoint))
    )
    val storageRoots = records.valuesIterator.flatMap(_.declarations.iterator)
      .filter(declaration =>
        Set(KernelSignalKind.Register, KernelSignalKind.Wire).contains(declaration.kind)
      )
      .flatMap(_.attributes.iterator.collect { case ("initial", value) => value }).flatMap:
        case expression: Expr[?] => Vector(expression)
        case Some(expression: Expr[?]) => Vector(expression)
        case _ => Vector.empty
    val assignmentRoots = operations.iterator.filter(operation =>
      Set("assignment", "value-connect").contains(operation.kind)
    ).flatMap(_.values.collect:
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
          ) => expression)
    DigitalValueConstruction.snapshot(
      records.valuesIterator.flatMap(
        _.declarations.iterator.flatMap(_.dataType)
      ).toVector ++ interfaces.dataTypes ++ flatFsms.valuesIterator.map(
        _.definition.canonicalType
      ).toVector ++
        enumSwitches.valuesIterator.map(record =>
          new KernelDataType[Data](KernelTypeDescriptor("Enum", Vector(record.canonical)))
        ).toVector,
      storageRoots.toVector ++ assignmentRoots.toVector ++
        operations.iterator.filter(_.kind == "memory-write").flatMap(_.values.collect {
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
          records.valuesIterator.flatMap(
            _.domains.iterator.filter(_.kind != KernelDomainKind.Required).flatMap(_.domain.clock)
          ).toVector
        ).toVector.flatten,
      (shapeIndices.keysIterator.map(expressionPath) ++ shapeViews.keysIterator.map(
        expressionPath
      )).toSet,
      expressions,
      (dataType, owner, region) =>
        renderCollectionType(
          dataType,
          owner,
          region.flatMap(path =>
            allValueRegions
              .find(value => expressionPath(value.reference) == path)
          )
        ),
      expression =>
        Option(expressionIds.get(expression)).flatMap(expressionRegions.get)
          .map(value => expressionPath(value.reference)),
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

  private def parameterExpressionSnapshots(
      additionalTypes: Vector[DataType[?]]
  ): Vector[KernelParameterExpressionSnapshot] =
    val roots = rootParameterBindings.iterator.map(_._2) ++ records.valuesIterator.flatMap(
      _.instances.iterator.flatMap(_.parameterOverrides.iterator.map(_._2))
    )
    val defaultRoots = records.valuesIterator.flatMap(_.declarations.iterator.filter(
      _.kind == KernelSignalKind.Parameter
    ).flatMap(_.attributes.collect { case ("default", value) => value }))
    val generatedRoots = records.valuesIterator.flatMap(
      _.generatedRegions.iterator.flatMap(region =>
        Iterator(region.lower, region.upperExclusive, region.step)
      )
    )
    def shapeDimensionRoots(dataType: DataType[?]): Vector[Any] =
      val descriptor = CandidateRuntime.typeDescriptor(dataType)
      if Set("Bits", "UInt", "SInt").contains(descriptor.kind) then descriptor.arguments
      else if descriptor.kind == "Struct" then
        descriptor.arguments.lift(1).toVector.flatMap:
          case fields: Seq[?] => fields.toVector.flatMap:
              case field: StructField[?] => shapeDimensionRoots(field.dataType)
              case _ => Vector.empty
          case _ => Vector.empty
      else if descriptor.kind != "Vec" then Vector.empty
      else
        val nested = descriptor.arguments.headOption.collect:
          case element: DataType[?] => shapeDimensionRoots(element)
        val dimensions = descriptor.arguments.lift(1).toVector.flatMap:
          case values: Seq[?] => values.toVector
          case _ => Vector.empty
        dimensions ++ nested.getOrElse(Vector.empty)

    val shapeDimensionExpressionRoots = records.valuesIterator.flatMap(
      _.declarations.iterator.flatMap(declaration =>
        val cardinalityRoots = declaration.kind match
          case KernelSignalKind.Memory => declaration.attributes.collect { case ("depth", value) =>
              value
            }
          case KernelSignalKind.InterfaceArray =>
            declaration.attributes.collect { case ("count", value) => value }
          case _ => Vector.empty
        declaration.dataType.iterator.flatMap(shapeDimensionRoots) ++
          declaration.conservativeDimensions.iterator ++ cardinalityRoots.iterator
      )
    )
    val interfaceTypeRoots = additionalTypes.iterator.flatMap(shapeDimensionRoots)
    val expressionTypeRoots = expressionValues.valuesIterator.flatMap(expression =>
      (CandidateRuntime.expressionDataType(
        expression
      ).iterator ++ expression.operands.iterator.collect {
        case metadata: StructOperationMetadata => metadata.schema
      }).flatMap(shapeDimensionRoots)
    )
    val lexicalShapeRoots = expressionValues.valuesIterator
      .filter(CandidateRuntime.proceduralShapeSource(_)).flatMap: expression =>
        val dimensions =
          CandidateRuntime.expressionDataType(expression).toVector.flatMap(shapeDimensionRoots)
        dimensions.iterator ++
          (if expression.operation.contains("analog_array_fill") then Iterator.empty
           else expression.operands.drop(1).iterator)
    val retainedLoops =
      retainedLoopRegions ++ loopRegions.valuesIterator.filter(_.structuralGenerate)
    val predicateRoots = (staticPredicates.valuesIterator.flatMap(_.operands) ++
      allValueRegions.flatMap(_.captures.valuesIterator.map(_._1))).collect {
      case value: KernelExpr[?]
          if value.resultDescriptor.contains(KernelTypeDescriptor("Integer")) &&
            !runtimeSequentialInteger(value) => value
    }.toVector
    val loopStaticRoots = (loopRegions.valuesIterator.filter(retainedLoops).flatMap(region =>
      Iterator(region.loop.get.lower, region.loop.get.upperExclusive, region.loop.get.step) ++
        region.captures.valuesIterator.map(_._1).filter(value =>
          CandidateRuntime.expressionDataType(value).exists(dataType =>
            CandidateRuntime.typeDescriptor(dataType) == KernelTypeDescriptor("Integer")
          )
        )
    ) ++ expressionValues.iterator.collect:
      case (reference, value)
          if expressionRegions.get(reference).exists(retainedLoops) &&
            value.resultDescriptor.contains(KernelTypeDescriptor("Integer")) => value
    ).toVector
    val loopStaticReferences = (loopStaticRoots ++ predicateRoots).iterator.collect {
      case expression: KernelExpr[?] if expressionIds.containsKey(expression) =>
        expressionIds.get(expression)
    }.toSet
    val reachable = capturedExpressionClosure(
      (predicateRoots.iterator ++ loopStaticRoots.iterator ++ interfaceTypeRoots ++ expressionTypeRoots ++ lexicalShapeRoots ++ roots ++ defaultRoots ++ generatedRoots ++ structuralShapeExpressionRoots ++
        shapeDimensionExpressionRoots).collect:
        case expression: KernelExpr[?]
            if (expression.literal.isEmpty || loopStaticReferences.contains(
              expressionIds.get(expression)
            )) &&
              !expression.operation.contains("generate_index") &&
              !expression.operation.exists(_.startsWith(DigitalEnumContract.Prefix)) =>
          expression
    )

    expressionValues.toVector.collect:
      case (reference, expression)
          if reachable.contains(reference) &&
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
          modulePath(reference.module),
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
    val kinds = snapshot.modules.flatMap(_.declarations.map(_.kind)).toSet
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
      snapshot.interfaceContracts.exists(_.members.exists(member =>
        Set("conservative", "signal-flow").contains(member.protocol)
      )) || kinds.exists(analogKinds.contains) || snapshot.continuousOperators.nonEmpty ||
        snapshot.waveformOperators.nonEmpty || snapshot.noiseOperators.nonEmpty ||
        snapshot.transferOperators.nonEmpty || snapshot.analogFunctions.nonEmpty ||
        snapshot.analogRegions.nonEmpty ||
        snapshot.analogProcedural.nonEmpty || snapshot.analogSemantics.equations.nonEmpty ||
        snapshot.analogSemantics.contributions.nonEmpty || snapshot.analogSemantics.states.nonEmpty ||
        snapshot.analogSemantics.components.nonEmpty
    val storageKinds = if analog then Set("parameter", "variable") else Set.empty[String]
    val digital = (kinds -- analogKinds -- storageKinds).nonEmpty ||
      snapshot.interfaceAbi.nonEmpty ||
      snapshot.resolvedNets.nonEmpty
    (digital, analog) match
      case (true, true) => DesignKind.MixedSignal
      case (true, false) => DesignKind.DigitalOnly
      case (false, true) => DesignKind.AnalogOnly
      case _ => DesignKind.Unsupported

  def finish(root: Module): (Emission, ConstructionSnapshot) =
    constructorFailure.foreach: (failure, site) =>
      fail(
        "NODAL-CONSTRUCTOR-LIFECYCLE-021",
        s"captured constructor failure invalidated this transaction: ${failure.getClass.getName}",
        Some(site)
      )
    finalizeStreamReady()
    if areaStack.nonEmpty then
      fail("NODAL-AREA-058-LIFECYCLE", "construction closed with an active Area")
    prepareAreaNames()
    val rootHandle = moduleHandle(root)
    if moduleStack.size != 1 || moduleStack.last.handle != rootHandle then
      fail("NODAL-LIFECYCLE-017", "construction closed with an unattached child Module")
    moduleStack.remove(moduleStack.size - 1)
    if domainStack.nonEmpty then
      fail("NODAL-LIFECYCLE-018", "construction closed with a lexical domain still active")
    records.values.filterNot(_.attached).foreach: module =>
      fail("NODAL-HIERARCHY-021", s"Module '${module.className}' was not attached")

    semanticResult = Some(semanticOrigin.resolve())
    val resolved = resolveDomains()
    markConservativeBindingDependencies()
    val interfaceFormalTypes = prepareInterfaceFormalTypes()
    if operations.exists(_.kind == "instance-node-array") || instanceDigitalPorts.nonEmpty ||
      instanceInterfacePorts.nonEmpty || instanceDigitalInouts.nonEmpty
    then
      val base = ConstructionSnapshot(
        root = modulePath(rootHandle),
        modules = snapshots(resolved),
        parameterExpressions = parameterExpressionSnapshots(interfaceFormalTypes),
        interfaceAbi = Vector.empty,
        resolvedNets = Vector.empty,
        topology = Vector.empty
      )
      materializeConservativeIntents(base)
      val boundaryQueries = new StructuralParameterBinding.InstanceQueries(base)
      materializeInstanceDigitalTypes(boundaryQueries)
      materializeInstanceDigitalInouts(boundaryQueries)
      materializeInstanceInterfaceDimensions(boundaryQueries)
    materializeDeferredResultTypes()
    val semantic = semanticOrigin.resolve()
    semanticResult = Some(semantic)
    val sourceByPath = semantic.sourceMap.map(entry => entry.semanticPath -> entry.source).toMap
    val collectionArgumentSources = collectionArguments.keysIterator.flatMap: reference =>
      semantic.expressionPaths.get(reference.module -> reference.index).flatMap(sourceByPath.get)
        .map(source => SourceMapEntry(expressionPath(reference), source))
    .toVector
    val sequentialRegionSources = (
      fsmResults.keysIterator.map(reference => reference -> expressionPath(reference)) ++
        fsmActionRegions.valuesIterator.map(region =>
          region.fsmAction.get.machine -> expressionPath(region.reference)
        ) ++
        enumSwitchArms.valuesIterator.map(region =>
          region.switchArm.get.switch -> expressionPath(region.reference)
        )
    ).flatMap: (reference, path) =>
      semantic.expressionPaths.get(reference.module -> reference.index).flatMap(sourceByPath.get)
        .map(source => SourceMapEntry(path, source))
    .toVector
    val rootSources = operations.iterator.flatMap(_.values.collect {
      case root: InstanceArrayRootRecord => root
    })
      .flatMap(root =>
        sourceByPath.get(expressionPath(root.reference)).map(source =>
          SourceMapEntry(pathOfReference(root.reference), source)
        )
      ).toVector
    val endpointSources = shapeIndices.valuesIterator.filter(_.conservative.nonEmpty).flatMap:
      index =>
        sourceByPath.get(expressionPath(index.sourceUses.head._1)).map(source =>
          SourceMapEntry(conservativeEndpointPath(index), source)
        )
    val interfaceCapture = DigitalInterfaceContract.capture(
      records.valuesIterator.flatMap(module =>
        module.declarations.iterator.map(declaration =>
          val validatedTypes = new java.util.IdentityHashMap[DataType[?], java.lang.Boolean]()
          DigitalInterfaceContract.Declaration(
            declaration.value,
            declarationPath(declaration.reference),
            modulePath(module.handle),
            localDeclarationDomain(declaration, resolved),
            interfaceDimensions(declaration).map(renderShapeDimension(_, module.handle)),
            dataType =>
              if !validatedTypes.containsKey(dataType) then
                validateShapeType(dataType, module.handle, cardinality = true)
                validatedTypes.put(dataType, java.lang.Boolean.TRUE)
              renderType(dataType, module.handle)
            ,
            dataType =>
              validateShapeType(dataType, module.handle, cardinality = true, storageContext = true),
            value =>
              if CandidateRuntime.expressionDataType(value).exists(isBoundaryCapability) then
                validateBoundarySource(value, declaration, resolved),
            (left, right) =>
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
            instanceInterfacePorts.get(declaration.reference).map: (instance, port) =>
              KernelInterfaceBoundarySnapshot(
                instancePath(module.handle, instance.ordinal),
                declarationPath(port)
              ),
            dataType =>
              val dimensions = interfaceDimensions(declaration)
              val actual = interfaceActualType(declaration.reference, dataType)
              if dimensions.isEmpty then actual
              else Vec(actual, dimensions*)
            ,
            dataType => interfaceActualType(declaration.reference, dataType),
            instanceInterfacePorts.get(declaration.reference).map: (_, port) =>
              dataType => renderType(dataType, port.module), {
              val dimensions = interfaceDimensions(declaration)
              if dimensions.isEmpty then Bool else Vec(Bool, dimensions*)
            }
          )
        )
      ).toVector,
      operations.toVector.map(operation =>
        DigitalInterfaceContract.Operation(
          operation.kind,
          operation.values,
          modulePath(operation.owner)
        )
      ),
      pathOf,
      value =>
        value match
          case reference: AnyRef =>
            Option(declarationIds.get(reference)).map(_.module)
              .orElse(Option(expressionIds.get(reference)).map(_.module)).map(modulePath)
          case _ => None
    )
    val abi = interfaceCapture.abi
    val arrayBindings = conservativeArrayBindingRecords
    val topologyEdges = topologyRecords()
    val recordedAnalogSemantics = analogSemanticSnapshot()
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
    verifySequentialAssignments(resolved, interfaceCapture)
    val netSnapshots = resolvedNets()
    val digitalCapture = digitalValueSnapshots(interfaceCapture, netSnapshots)
    val modules = snapshots(resolved)
    val assignmentRecords = digitalAssignmentRecords(resolved)
    val assignmentSources = assignmentRecords.flatMap: (assignment, record) =>
      val syntheticDefault =
        Option(declarationIds.get(record.values.head.asInstanceOf[AnyRef])).exists: reference =>
          records(reference.module).declarations(reference.index).attributes.exists:
            case ("stream_ready_default", value: Expr[?]) =>
              value eq record.values(1).asInstanceOf[AnyRef]
            case _ => false
      val source = record.source.orElse(
        Option.when(syntheticDefault)(
          sourceByPath.get(assignment.target).orElse(sourceByPath.get(assignment.value))
        ).flatten
      )
      source.map(SourceMapEntry(assignment.path, _))
    // A member projection is generated from its actual endpoint declaration. Preserve any
    // more precise captured leaf span; otherwise retain that declaration, never invent a line.
    val interfaceMemberSources = interfaceCapture.interfaces.flatMap: endpoint =>
      sourceByPath.get(endpoint.path).toVector.flatMap: source =>
        endpoint.members.filterNot(member => sourceByPath.contains(member.path)).map(member =>
          SourceMapEntry(member.path, source)
        )
    val hierarchyBindingSources = selectedRequirementBindings.iterator.flatMap:
      case (requirement, (_, origin, owner, ordinal)) =>
        val instance = records(owner).instances(ordinal)
        val requirementRecord = records(requirement.module).domains(requirement.index)
        val source = if origin == "named" then
          instance.namedBindingSources.get(requirementRecord.domain)
        else if origin == "default" then instance.defaultBindingSource
        else sourceByPath.get(instancePath(owner, ordinal))
        source.map(span =>
          SourceMapEntry(instancePath(owner, ordinal) + "." + requirementRecord.name, span)
        )
    .toVector
    val snapshot = ConstructionSnapshot(
      emitQuality = options.quality,
      digitalAssignments = assignmentRecords.map(_._1),
      eventProcesses = eventProcessSnapshots,
      memoryWrites = memoryWriteRecords.map(_._1),
      interfaceContracts = interfaceCapture.interfaces,
      digitalConnectivity = interfaceCapture.connectivity,
      enumDefinitions = digitalCapture._1,
      digitalExpressions = digitalCapture._2,
      digitalCollections = collectionSnapshots(),
      digitalLoops = loopSnapshots(),
      iterationIndices = iterationIndexSnapshots(),
      staticPredicates = staticPredicateSnapshots(),
      structuralIfs = structuralIfSnapshots(),
      behavioralIfs = behavioralIfSnapshots(),
      flatFsms = flatFsmSnapshots(),
      enumSwitches = enumSwitchSnapshots(),
      root = modulePath(rootHandle),
      rootParameterBindings = rootParameterBindings.toVector.map:
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
      parameterExpressions = parameterExpressionSnapshots(interfaceCapture.dataTypes),
      modules = modules,
      interfaceAbi = abi,
      resolvedNets = netSnapshots,
      topology = topologyEdges.map(_._1),
      names = semantic.names,
      origins = semantic.origins,
      generatedNames = semantic.generatedNames,
      sourceMap =
        (semantic.sourceMap ++ eventProcesses.flatMap(process =>
          process.source.map(SourceMapEntry(eventProcessPath(process), _))
        ) ++ hierarchyBindingSources ++ assignmentSources ++ interfaceMemberSources ++ memoryWriteRecords.flatMap(
          (write, record) =>
            record.source.map(SourceMapEntry(write.path, _))
        ) ++ collectionArgumentSources ++ sequentialRegionSources ++ rootSources ++ endpointSources ++ topologyEdges.flatMap(
          (edge, record) =>
            edge.path.flatMap(path => record.source.map(SourceMapEntry(path, _)))
        ) ++ arrayBindings.flatMap((binding, record) =>
          record.source.map(SourceMapEntry(binding.path, _))
        ) ++ AnalogUserFunctionRuntime.sourceMap(
          userFunctions.snapshots(modulePath)
        ) ++ AnalogEquationRuntime.sourceMap(analogSemantics)).sortBy(_.semanticPath),
      analogRegions = analogSnapshots(),
      continuousOperators = continuousOperatorSnapshots(semantic.sourceMap),
      waveformOperators = waveformOperatorSnapshots(semantic.sourceMap),
      noiseOperators = noiseOperatorSnapshots(semantic.sourceMap),
      transferOperators = transferOperatorSnapshots(semantic.sourceMap),
      analogFunctions = userFunctions.snapshots(modulePath),
      analogSemantics = analogSemantics,
      analogProcedural = AnalogProceduralConstruction.snapshots(
        module => modulePath(moduleHandle(module)),
        canonicalGeneratedOwnership
      ),
      waivers = waiverSnapshots(semantic.sourceMap),
      generatedRegions = generatedSnapshots(),
      areas = areaSnapshots(resolved),
      shapeIndices = shapeIndexSnapshots(),
      shapeViews = shapeViewSnapshots(),
      conservativeArrayBindings = arrayBindings.map(_._1),
      instanceArrayRoots = instanceArrayRootSnapshots()
    )
    finalizeConservativeArrayBindings(snapshot)
    val kind = classify(snapshot)
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
    Emission(Vector.empty, report) -> snapshot
