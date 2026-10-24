package nodal.internal.testkit.diagnosticpolicy.exportscan

import nodal.*
import scala.collection.mutable

/** SOURCE-ONLY DRAFT. Internal accounting controls, not public callback-admission evidence. Every
  * owner/session is genuine. Calibration only debits the existing owner. No budget, remaining
  * balance, registry, ticket, or allocation counter is reset or refunded.
  */
object C2b2aBoundControls:
  def bind(root: Module): Unit =
    active().asInstanceOf[ConstructionSession].bindDiagnosticConstructionWork(root)
  private var mode = "parity"
  private var callbacks = 0
  private var caught = false
  private var failedSession: AnyRef = null

  private def call(target: AnyRef, name: String, args: AnyRef*): AnyRef =
    val method = (target.getClass.getMethods.toVector ++ target.getClass.getDeclaredMethods)
      .find(m =>
        (m.getName == name || m.getName.endsWith("$$" + name)) &&
          m.getParameterCount == args.size
      ).getOrElse(throw new AssertionError(s"missing ${target.getClass.getName}.$name"))
    method.setAccessible(true)
    try method.invoke(target, args*)
    catch case failure: java.lang.reflect.InvocationTargetException => throw failure.getCause

  private def member(target: AnyRef, suffix: String): java.lang.reflect.Field =
    val matches = target.getClass.getDeclaredFields.filter(_.getName.endsWith(suffix))
    assert(matches.length == 1, s"missing/ambiguous ${target.getClass.getName}.$suffix")
    matches.head.setAccessible(true)
    matches.head
  private def field(target: AnyRef, suffix: String): AnyRef = member(target, suffix).get(target)
  private def set(target: AnyRef, suffix: String, value: AnyRef): Unit =
    member(target, suffix).set(target, value)
  private def reject(fragment: String)(body: => Any): Throwable =
    val failure = scala.util.Try(body).failed.get
    assert(
      Option(failure.getMessage).exists(_.contains(fragment)),
      failure.toString + failure.getStackTrace.mkString("\n")
    )
    failure
  private def at(failure: Throwable, name: String): Boolean =
    failure.getStackTrace.exists(frame =>
      frame.getMethodName == name || frame.getMethodName.endsWith("$$" + name)
    )
  private def active(): AnyRef = call(ConstructionKernel, "active")
    .asInstanceOf[Option[AnyRef]].get
  private def remaining(owner: AnyRef): Long = field(owner, "remaining").asInstanceOf[Long]
  private def serial(session: AnyRef): Long =
    field(session, "constructionAllocationSerial").asInstanceOf[Long]
  private def declarations(session: AnyRef): java.util.IdentityHashMap[AnyRef, DeclarationRef] =
    field(session, "declarationIds").asInstanceOf[java.util.IdentityHashMap[AnyRef, DeclarationRef]]
  private def scopesOff(session: AnyRef): Unit =
    Vector("boundDomainAccountingActive", "snapshotAccountingActive", "publicationAccountingActive")
      .foreach(name => assert(field(session, name) == Boolean.box(false), name))
  private def guarded[A](owner: AnyRef)(body: => A): A =
    call(owner, "guard", (() => body)).asInstanceOf[A]
  private def boundScope[A](session: AnyRef, enabled: Boolean)(body: => A): A =
    call(session, "withBoundDomainAccounting", Boolean.box(enabled), (() => body))
      .asInstanceOf[A]
  private def measured[A](owner: AnyRef)(body: => A): (A, Long) =
    val before = remaining(owner)
    val result = body
    val cost = before - remaining(owner)
    assert(cost > 0L, "calibration must debit real work")
    result -> cost
  private def raw(owner: AnyRef): Option[AnyRef] =
    guarded(owner)(call(owner, "exactBoundDomainRaw").asInstanceOf[Option[AnyRef]])
  private def retain(owner: AnyRef, units: Long): Unit =
    assert(
      units > 0L && remaining(owner) >= units,
      s"calibration exceeds bounded owner budget: need $units, have ${remaining(owner)}"
    )
    call(owner, "charge", Long.box(remaining(owner) - units))
    assert(remaining(owner) == units)

  def reserve(input: Expr[Vec[SInt]]): KernelExpr[Data] =
    val body = DigitalCollectionCallback.reductionBody(DigitalCollectionCallback.checked2(
      (left: Expr[SInt], _: Expr[SInt]) =>
        callbacks += 1
        left
    ))
    call(active(), "reserveReductionConstructionIntent", input, body, "reduce_left")
      .asInstanceOf[KernelExpr[Data]]

  /** Capture identities before exhaustion. Checking these after poison proves no registry or
    * allocation rollback, including the successful typed proxy registration made above it.
    */
  private def retainedState(session: AnyRef, owner: AnyRef): () => Unit =
    val oldSerial = serial(session)
    val identityMaps = Vector(
      "declarationIds",
      "expressionIds",
      "moduleIds",
      "domainIds",
      "instanceIds",
      "instanceObjects"
    ).map: name =>
      val map = field(session, name).asInstanceOf[java.util.IdentityHashMap[AnyRef, AnyRef]]
      val entries = map.keySet.toArray.toVector.map(key => key -> map.get(key))
      () =>
        assert(map.size == entries.size, name)
        entries.foreach((key, value) => assert(map.get(key) eq value, name))
    val maps = Vector(
      "records",
      "expressionValues",
      "instanceDigitalPorts",
      "shapeIndices",
      "selectedRequirementBindings"
    ).map: name =>
      val map = field(session, name).asInstanceOf[mutable.Map[Any, AnyRef]]
      val entries = map.toVector
      () =>
        assert(map.size == entries.size, name)
        entries.foreach((key, value) => assert(map.get(key).exists(_ eq value), name))
    val origin = field(session, "semanticOrigin")
    val buffers = Vector("declarations", "expressions").map: name =>
      val buffer = field(origin, name).asInstanceOf[mutable.ArrayBuffer[AnyRef]]
      val entries = buffer.toVector
      () =>
        assert(buffer.size == entries.size, name)
        assert(buffer.iterator.zip(entries.iterator).forall((a, b) => a eq b), name)
    val stacks = Vector(
      "moduleStack",
      "domainStack",
      "areaStack",
      "generationStack",
      "collectionStack",
      "instanceSelectionStack",
      "analogStack"
    ).map: name =>
      val buffer = field(session, name).asInstanceOf[mutable.ArrayBuffer[AnyRef]]
      val entries = buffer.toVector
      () =>
        assert(buffer.size == entries.size, name)
        assert(buffer.iterator.zip(entries.iterator).forall((a, b) => a eq b), name)
    val occurrenceDomains = field(session, "occurrenceDomains")
    val revision = field(owner, "revision")
    val builtRevision = field(owner, "builtRevision")
    () =>
      assert(serial(session) == oldSerial)
      (identityMaps ++ maps ++ buffers ++ stacks).foreach(check => check())
      assert(field(session, "occurrenceDomains") eq occurrenceDomains)
      assert(field(owner, "revision") == revision && field(owner, "builtRevision") == builtRevision)
      assert(field(session, "directPortRequest") == None &&
        field(session, "directPortPermit") == None)

  private def proceduralState(): AnyRef =
    field(AnalogProceduralConstruction, "current").asInstanceOf[ThreadLocal[AnyRef]].get()

  private def observers(session: AnyRef, owner: AnyRef, root: C2b2aBoundRoot): Unit =
    val state = proceduralState()
    assert(state != null)
    val modules = field(state, "modules").asInstanceOf[mutable.ArrayBuffer[AnyRef]].toVector
    val stack = field(state, "stack").asInstanceOf[mutable.ArrayBuffer[AnyRef]].toVector
    assert(modules.size == 2 && stack.size == 1)
    assert(modules.count(value => call(value, "module") eq root) == 1)
    assert(modules.count(value => call(value, "module") eq root.leaf) == 1)
    assert(call(stack.head, "module") eq root)
    val unchanged = retainedState(session, owner)
    val (_, debit) = measured(owner):
      assert(AnalogProceduralConstruction.boundPairPublicationEmpty(
        root,
        root.leaf,
        units => { call(owner, "charge", Long.box(units)); () }
      ))
    assert(debit == 68L, s"exactly two retained ModuleState visits, got $debit")
    assert(
      !AnalogProceduralConstruction.publicationEmpty(root),
      "the old single-root observer must not broaden to the retained child"
    )
    assert(proceduralState() eq state)
    assert(field(state, "modules").asInstanceOf[mutable.ArrayBuffer[AnyRef]].toVector == modules)
    assert(field(state, "stack").asInstanceOf[mutable.ArrayBuffer[AnyRef]].toVector == stack)
    unchanged()
    scopesOff(session)
    println(
      "PASS bound pair observer seam: exact pair true, legacy root false, retained owners unchanged"
    )

  /** Corrupt one existing retained child field, never fabricate/append a State or ModuleState.
    * Equal negative raw/entry-point debits prove fallback occurs before linear/exact preparation.
    */
  private def negativeObserver(session: AnyRef, owner: AnyRef, root: C2b2aBoundRoot): Unit =
    val state = proceduralState()
    val modules = field(state, "modules").asInstanceOf[mutable.ArrayBuffer[AnyRef]]
    val child = modules.find(value => call(value, "module") eq root.leaf).get
    val original = field(child, "controlScope")
    assert(original == Vector.empty)
    val unchanged = retainedState(session, owner)
    set(child, "controlScope", Vector("test_only_nonempty_child"))
    try
      assert(!AnalogProceduralConstruction.boundPairPublicationEmpty(
        root,
        root.leaf,
        units => { call(owner, "charge", Long.box(units)); () }
      ))
      assert(!AnalogProceduralConstruction.publicationEmpty(root))
      assert(call(owner, "boundDomainAccountingCandidate") == Boolean.box(true))
      val (proof, rawCost) = measured(owner)(raw(owner))
      assert(proof.isEmpty)
      val (result, routedCost) = measured(owner):
        call(owner, "tryFinalBoundDomainInputs").asInstanceOf[Option[AnyRef]]
      assert(
        result.isEmpty && routedCost == rawCost,
        "negative pair must return None before precharge or bound-domain preparation"
      )
      assert(field(owner, "poisoned") == Boolean.box(false))
      assert(field(session, "constructorFailure") == None)
      unchanged()
      scopesOff(session)
    finally set(child, "controlScope", original)
    assert(proceduralState() eq state)
    assert(raw(owner).nonEmpty, "restored real child should regain exact eligibility")
    println("PASS bound pair retained-child negative falls back before exact preparation")

  /** Exporter-seam diagnostic only: two complete vectors from ONE capture and ONE resolved map. No
    * second finish, renamed-root normalization, JSON omission, or source/location scrubbing.
    */
  private def rawParity(session: AnyRef, owner: AnyRef, root: C2b2aBoundRoot): Unit =
    val resolved = field(session, "occurrenceDomains")
    val proof = raw(owner).get
    guarded(owner):
      boundScope(session, true):
        call(owner, "requireBoundDomainExport", proof, Boolean.box(false))
    scopesOff(session)
    val unchanged = retainedState(session, owner)
    val rawModules = boundScope(session, false):
      assert(field(session, "boundDomainAccountingActive") == Boolean.box(false))
      call(session, "snapshots", resolved).asInstanceOf[Vector[KernelModuleSnapshot]]
    val (chargedModules, debit) = measured(owner):
      guarded(owner):
        boundScope(session, true):
          assert(field(session, "boundDomainAccountingActive") == Boolean.box(true))
          assert(field(session, "snapshotAccountingActive") == Boolean.box(false))
          assert(field(session, "publicationAccountingActive") == Boolean.box(false))
          call(session, "snapshots", resolved).asInstanceOf[Vector[KernelModuleSnapshot]]
    assert(
      debit > 0L && rawModules == chargedModules,
      "same-capture complete raw module vectors differ across bound accounting scope"
    )
    assert(rawModules.size == 2)
    val parent = rawModules.find(_.instances.nonEmpty).get
    val child = rawModules.find(_.path != parent.path).get
    val actual = parent.domains.find(_.kind == "external").get
    val required = child.domains.find(_.kind == "required").get
    val occurrence = parent.instances.head
    val binding = occurrence.actualBindings.head
    assert(required.binding.contains(actual.path) && required.clockProjection.nonEmpty)
    assert(occurrence.bindings == Vector(required.name -> actual.path))
    assert(occurrence.actualBindings.size == 1)
    assert(binding.path == occurrence.path + "." + required.name)
    assert(binding.requirement == required.path && binding.actual == actual.path)
    assert(binding.origin == "default" && binding.clock.nonEmpty && binding.reset.nonEmpty)
    val metadata = actual.attributes.toMap
    assert(metadata.get("clock_source").contains(binding.clock))
    assert(metadata.get("effective_reset_source") == binding.reset)
    val projectedClock = call(session, "selectedPath", root.leaf.required.clock.get)
      .asInstanceOf[Option[String]]
    assert(required.clockProjection == projectedClock && projectedClock.nonEmpty)
    // This fixture has no required-local reset demand. Preserve the complete optional field.
    assert(required.resetProjection.isEmpty && required.attributes.isEmpty)
    assert(parent.declarations.exists(_.instancePort.exists(_.port ==
      child.declarations
        .find(_.name == "data").get.path)))
    unchanged()
    scopesOff(session)
    println("PASS same-capture exporter-seam diagnostic: complete module vector raw parity")

  private def heldTicket(session: AnyRef, root: C2b2aBoundRoot)
      : StructuralParameterBinding.Query =
    val query = call(session, "currentBoundaryEpochQuery")
      .asInstanceOf[StructuralParameterBinding.Query]
    assert(query.checkedLiteral(root.getClass.getSimpleName, "3") == 3L)
    query

  private def poisonChecks(
      session: AnyRef,
      owner: AnyRef,
      root: C2b2aBoundRoot,
      query: StructuralParameterBinding.Query,
      epoch: Long,
      unchanged: () => Unit
  ): Unit =
    scopesOff(session)
    assert(remaining(owner) == 0L)
    assert(field(owner, "poisoned") == Boolean.box(true))
    assert(field(owner, "epoch").asInstanceOf[Long] > epoch)
    assert(field(session, "constructorFailure").asInstanceOf[Option[?]].nonEmpty)
    unchanged()
    reject("stale boundary snapshot epoch"):
      query.checkedLiteral(root.getClass.getSimpleName, "3")
    reject("poisoned")(call(session, "currentBoundaryEpochQuery"))
    reject("poisoned")(call(session, "drainIncrementalBoundaryClosure"))
    reject("poisoned")(call(owner, "verifyPublication"))
    reject("captured constructor failure")(call(session, "finish", root))
    unchanged()
    scopesOff(session)
    assert(callbacks == 0)
    failedSession = session
    caught = true

  private def exhaustion(session: AnyRef, owner: AnyRef, root: C2b2aBoundRoot): Unit =
    val query = heldTicket(session, root)
    val epoch = field(owner, "epoch").asInstanceOf[Long]
    val (proofOption, gate) = measured(owner)(raw(owner))
    val proof = proofOption.get
    val (_, linear) = measured(owner):
      guarded(owner)(call(owner, "prechargeCanonicalPass", Boolean.box(false)))
    assert(field(owner, "drained") == Boolean.box(true))
    val semantic = field(owner, "frozenNames").asInstanceOf[Option[SemanticOriginResult]].get
    val (exportCost, namesCost) = mode match
      case "preparation" => 0L -> 0L
      case "snapshot" | "publication" =>
        val (_, exportCost) = measured(owner):
          guarded(owner):
            boundScope(session, true):
              call(owner, "requireBoundDomainExport", proof, Boolean.box(mode == "publication"))
        val names = if mode == "publication" then
          val (_, cost) = measured(owner):
            guarded(owner):
              boundScope(session, true):
                call(owner, "finalNames", semantic, Boolean.box(true))
          cost
        else 0L
        exportCost -> names
      case other => throw new AssertionError(other)
    scopesOff(session)
    val retained = mode match
      case "preparation" => gate + linear
      // Root snapshotProbe + snapshots' initial charge, then the FIRST snapshotElements.next.
      case "snapshot" => gate + linear + exportCost + 2L
      // publicationVector and map probes consume 2*N; fail the first real pairs iterator visit.
      case "publication" =>
        assert(semantic.sourceMap.nonEmpty)
        gate + exportCost + namesCost + 2L * semantic.sourceMap.size
      case other => throw new AssertionError(other)
    retain(owner, retained)
    val unchanged = retainedState(session, owner)
    var bodyEntered = false
    val failure = reject("boundary closure work budget exhausted"):
      mode match
        case "preparation" => call(owner, "tryFinalBoundDomainInputs")
        case "snapshot" => guarded(owner)(call(owner, "snapshot"))
        case "publication" =>
          val body = () =>
            bodyEntered = true
            assert(field(session, "boundDomainAccountingActive") == Boolean.box(true))
            assert(field(session, "publicationAccountingActive") == Boolean.box(true))
            assert(field(session, "snapshotAccountingActive") == Boolean.box(false))
            call(session, "publicationSourceIndex", semantic.sourceMap)
          call(owner, "publication", root, semantic, body)
        case other => throw new AssertionError(other)
    assert(!at(failure, "exactBoundDomainRaw") && !at(failure, "prechargeCanonicalPass"))
    assert(!at(failure, "requireBoundDomainExport") && !at(failure, "requireBoundDomainResolved"))
    mode match
      case "preparation" =>
        assert(at(failure, "tryFinalBoundDomainInputs") && at(failure, "prepareBoundDomainInputs"))
        assert(at(failure, "resolveDomains") && at(failure, "boundDomainCharge"))
        assert(!bodyEntered)
      case "snapshot" =>
        assert(at(failure, "snapshot") && at(failure, "snapshots"))
        assert(at(failure, "snapshotCharge") && at(failure, "next"))
        assert(!bodyEntered)
      case "publication" =>
        assert(bodyEntered && at(failure, "publication") && at(failure, "publicationSourceIndex"))
        assert(at(failure, "publicationCharge") && at(failure, "next"))
      case other => throw new AssertionError(other)
    poisonChecks(session, owner, root, query, epoch, unchanged)
    println(
      s"PASS bound $mode actual traversal exhaustion, scope restoration, poison and no rollback"
    )

  /** Real nested descriptor traversed by the actual parameter snapshot exporter. The extra
    * descriptor is supplied through its existing additionalTypes input, not registered as hardware.
    */
  private def descriptorDepth(session: AnyRef, owner: AnyRef, root: C2b2aBoundRoot): Unit =
    val query = heldTicket(session, root)
    val epoch = field(owner, "epoch").asInstanceOf[Long]
    val semantic = field(owner, "frozenNames").asInstanceOf[Option[SemanticOriginResult]].get
    var nested: DataType[Data] = new KernelDataType[Data](KernelTypeDescriptor("SInt", Vector(2)))
    (0 until 129).foreach: _ =>
      nested = new KernelDataType[Data](KernelTypeDescriptor("Vec", Vector(nested, Vector(1))))
    val unchanged = retainedState(session, owner)
    val before = remaining(owner)
    var entered = false
    val failure = reject("recursive descriptor traversal exceeds the closure depth budget"):
      val body = () =>
        entered = true
        assert(field(session, "publicationAccountingActive") == Boolean.box(true))
        call(session, "parameterExpressionSnapshots", Vector(nested))
      call(owner, "publication", root, semantic, body)
    assert(entered && failure.getStackTrace.exists(_.getMethodName.contains("shapeDimensionRoots")))
    val trace = failure.getStackTrace
    println(
      s"DEPTH traceLength=${trace.length} near=${at(failure, "closureDescriptorVisit")} outer=${at(failure, "parameterExpressionSnapshots")}"
    )
    println("DEPTH head=" + trace.take(4).map(_.getMethodName).mkString(","))
    println("DEPTH tail=" + trace.takeRight(4).map(_.getMethodName).mkString(","))
    assert(at(failure, "closureDescriptorVisit"))
    assert(remaining(owner) > 0L && remaining(owner) < before)
    assert(field(session, "closureDescriptorDepth").asInstanceOf[Int] == 0)
    assert(field(owner, "poisoned") == Boolean.box(true))
    assert(field(owner, "epoch").asInstanceOf[Long] > epoch)
    scopesOff(session)
    unchanged()
    reject("stale boundary snapshot epoch")(query.checkedLiteral(root.getClass.getSimpleName, "3"))
    reject("captured constructor failure")(call(session, "finish", root))
    assert(callbacks == 0)
    failedSession = session
    caught = true
    println("PASS diagnostic real descriptor exporter depth refusal with positive balance")

  def exercise(root: C2b2aBoundRoot): Unit =
    val session = active()
    assert(session ne failedSession)
    assert(field(session, "constructorFailure") == None)
    scopesOff(session)
    assert(root.result.pendingConstructionOwnedBy(session))
    call(session, "completeReductionSingleton", root.result, root.input, Vector(0))
    session.asInstanceOf[ConstructionSession].beginDiagnosticConstructionClosure(root, root.result)
    call(session, "drainIncrementalBoundaryClosure")
    val owner = field(session, "boundaryClosureOwner").asInstanceOf[Option[AnyRef]].get
    val before = serial(session)
    // Exact typed request authority; no forged permit and no public Instance selector lambda.
    val proxy = session.asInstanceOf[ConstructionSession]
      .selectConstructionRequestPort(root.result, root.child, root.leaf.data)
    val reference = declarations(session).get(proxy)
    val stamp = reference.constructionAllocation.get
    assert(stamp.serial == before + 1L && serial(session) == before + 1L)
    assert((stamp.session eq session) &&
      (stamp.module eq root.result.constructionRequest.get.module))
    call(session, "drainIncrementalBoundaryClosure")
    assert(proxy.dataType.asInstanceOf[KernelInstanceDataType[SInt]].finalized)
    assert(root.result.constructionFinalized && root.result.typeFinalized && callbacks == 0)
    assert(field(owner, "drained") == Boolean.box(true) &&
      field(owner, "poisoned") == Boolean.box(false))
    assert(field(owner, "builtRevision") == field(owner, "revision"))
    assert(call(owner, "boundDomainAccountingCandidate") == Boolean.box(true))
    assert(raw(owner).nonEmpty)
    observers(session, owner, root)
    mode match
      case "descriptor-depth" => descriptorDepth(session, owner, root)
      case "parity" => rawParity(session, owner, root)
      case "negative" => negativeObserver(session, owner, root)
      case "preparation" | "snapshot" | "publication" => exhaustion(session, owner, root)
      case other => throw new AssertionError(other)
    assert(callbacks == 0)

  def ordinaryRecovery(): Unit =
    val session = active()
    assert(session ne failedSession)
    assert(field(session, "boundaryClosureOwner") == None)
    assert(field(session, "constructorFailure") == None)
    scopesOff(session)
    assert(callbacks == 0)

  private def failureCase(phase: String): Unit =
    mode = phase
    caught = false
    reject("captured constructor failure")(ConstructionKernel.inspect(new C2b2aBoundRoot))
    assert(caught && callbacks == 0)
    // A real new ordinary Session must still finish normally after the poisoned transaction.
    // This does not claim the independent bound-profile final naming budget is repaired.
    ConstructionKernel.inspect(new C2b2aBoundOrdinary)
    println(s"PASS bound $phase fresh ordinary-session recovery")

  def main(args: Array[String]): Unit =
    val failures = Vector("preparation", "snapshot", "publication", "descriptor-depth")
    assert(args.isEmpty ||
      (args.length == 1 &&
        (failures ++ Vector("parity", "negative", "poison-recovery")).contains(args.head)))
    val selected = args.headOption
    selected match
      case Some(phase) if failures.contains(phase) => failureCase(phase)
      case Some("poison-recovery") => failures.foreach(failureCase)
      case Some(profile) =>
        mode = profile
        ConstructionKernel.inspect(new C2b2aBoundRoot)
        println(s"PASS bound $profile ordinary final finish")
      case None =>
        mode = "parity"
        ConstructionKernel.inspect(new C2b2aBoundRoot)
        println("PASS bound parity ordinary final finish")
        mode = "negative"
        ConstructionKernel.inspect(new C2b2aBoundRoot)
        println("PASS bound negative ordinary final finish")
        failures.foreach(failureCase)
    assert(callbacks == 0)
