package nodal.internal.testkit.drivendiagnostic

import nodal.*
import scala.collection.mutable

/** Reflection accesses internal seams and negative-case state. Positive proxy admission uses the
  * typed fixed selector, never a forged permit.
  */
object C2b2aControls:
  private var diagnostic = true
  private def initialBudget: Long = if diagnostic then 131072L else 65536L
  private var retainedSession: ConstructionSession = null
  private var retainedOwner: AnyRef = null
  private var atConstructorExit = 0L
  def bind(root: Module): Unit =
    retainedOwner = null
    atConstructorExit = initialBudget
    retainedSession = active().asInstanceOf[ConstructionSession]
    if diagnostic then
      retainedSession.bindDiagnosticConstructionWork(root)
      assert(retainedSession.diagnosticConstructionWorkPolicyTag.contains(
        "registered-construction-diagnostic-131072"
      ))
    else assert(retainedSession.diagnosticConstructionWorkPolicyTag.isEmpty)
  private def finished(): Unit =
    val owner = field(retainedSession, "boundaryClosureOwner").asInstanceOf[Option[AnyRef]].get
    assert(owner eq retainedOwner)
    val balance = remaining(owner)
    assert(balance >= 0L && balance <= atConstructorExit && balance < initialBudget)
    assert(field(owner, "poisoned") == Boolean.box(false))
    println(
      s"DIAGNOSTIC full finish policy=${retainedSession.diagnosticConstructionWorkPolicyTag} initial=$initialBudget remaining=$balance cumulativeDebit=${initialBudget -
          balance}"
    )

  var mode = "new_and_cached"
  private var callbacks = 0
  private var caught = false
  private var selectionCost = 0L
  private var foreignPermit: AnyRef = null
  private var halfCost = 0L
  private var halfInventory = Vector.empty[Long]

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
  private def construct(name: String, args: AnyRef*): AnyRef =
    val constructor = Class.forName(name).getDeclaredConstructors
      .find(_.getParameterCount == args.size).get
    constructor.setAccessible(true)
    constructor.newInstance(args*).asInstanceOf[AnyRef]
  private def reject(fragment: String)(body: => Any): Throwable =
    val failure = scala.util.Try(body).failed.get
    assert(Option(failure.getMessage).exists(_.contains(fragment)), failure.toString)
    failure
  private def active(): AnyRef = call(ConstructionKernel, "active").asInstanceOf[Option[AnyRef]].get
  private def serial(session: AnyRef): Long = field(session, "constructionAllocationSerial")
    .asInstanceOf[Long]
  private def remaining(owner: AnyRef): Long = field(owner, "remaining").asInstanceOf[Long]
  private def declarations(session: AnyRef): java.util.IdentityHashMap[AnyRef, DeclarationRef] =
    field(session, "declarationIds").asInstanceOf[java.util.IdentityHashMap[AnyRef, DeclarationRef]]
  private def bindings(session: AnyRef): mutable.Map[DeclarationRef, AnyRef] =
    field(session, "instanceDigitalPorts").asInstanceOf[mutable.Map[DeclarationRef, AnyRef]]
  private def captures(session: AnyRef): mutable.ArrayBuffer[AnyRef] =
    field(field(session, "semanticOrigin"), "declarations")
      .asInstanceOf[mutable.ArrayBuffer[AnyRef]]
  private def stack(session: AnyRef, name: String): mutable.ArrayBuffer[AnyRef] =
    field(session, name).asInstanceOf[mutable.ArrayBuffer[AnyRef]]
  private def select(session: AnyRef, root: C2b2aRoot): Signal[SInt] =
    session.asInstanceOf[ConstructionSession]
      .selectConstructionRequestPort(root.result, root.child, root.leaf.data)

  def reserve(input: Expr[Vec[SInt]]): KernelExpr[Data] =
    val body = DigitalCollectionCallback.reductionBody(DigitalCollectionCallback.checked2(
      (left: Expr[SInt], _: Expr[SInt]) =>
        callbacks += 1
        left
    ))
    call(active(), "reserveReductionConstructionIntent", input, body, "reduce_left")
      .asInstanceOf[KernelExpr[Data]]

  def exercise(root: C2b2aRoot): Unit =
    val session = active()
    val request = root.result.constructionRequest.get
    val instance = call(session, "instanceRecord", root.child)
    val occurrenceStamp = call(instance, "constructionAllocation").asInstanceOf[Option[AnyRef]].get
    assert(field(occurrenceStamp, "serial").asInstanceOf[Long] <= request.frontier)
    assert(declarations(session).get(root.leaf.data).constructionAllocation.get.serial <=
      request.frontier)
    call(session, "completeReductionSingleton", root.result, root.input, Vector(0))
    assert(root.result.constructionFinalized && callbacks == 0)
    if diagnostic then
      session.asInstanceOf[ConstructionSession].beginDiagnosticConstructionClosure(
        root,
        root.result
      )
    else session.asInstanceOf[ConstructionSession].beginIncrementalBoundaryClosure(root, 65536)
    call(session, "drainIncrementalBoundaryClosure")
    val owner = field(session, "boundaryClosureOwner").asInstanceOf[Option[AnyRef]].get
    retainedOwner = owner
    mode match
      case "new_and_cached" =>
        positive(session, owner, root)
        assignmentGates(session, owner, root)
      case "calibrate_half_state" | "post_registration_failure" => halfState(session, owner, root)
      case name if name.startsWith("permit_") => permitNegative(session, owner, root, name)
      case "cached_mapping" | "cached_target" | "cached_formal" =>
        val proxy = select(session, root)
        call(session, "drainIncrementalBoundaryClosure")
        val reference = declarations(session).get(proxy)
        mode match
          case "cached_mapping" => declarations(session).put(proxy, reference.copy())
          case "cached_target" =>
            val binding = bindings(session)(reference)
            set(binding, "port", declarations(session).get(root.leaf.data).copy())
          case "cached_formal" =>
            set(proxy.dataType, "formal", KernelTypeDescriptor("Bool", Vector.empty))
          case _ => ()
        poisoned(session, owner)(select(session, root))
      case "copied_unregistered" =>
        val copied = new KernelExpr[Data](Vector.empty)
        copied.attachConstructionRequest(request)
        assert(!copied.registeredConstructionOwnedBy(session))
        poisoned(session, owner):
          session.asInstanceOf[ConstructionSession]
            .selectConstructionRequestPort(copied, root.child, root.leaf.data)
      case "late_occurrence" =>
        val later = root.later.get
        poisoned(session, owner):
          session.asInstanceOf[ConstructionSession]
            .selectConstructionRequestPort(root.result, later, later.module.data)
      case "late_foreign_type_root" =>
        val late = root.lateWidth.get
        val ref = field(session, "expressionIds")
          .asInstanceOf[java.util.IdentityHashMap[AnyRef, ExpressionRef]].get(late)
        assert(ref.constructionAllocation.get.serial > request.frontier)
        // Deliberate type metadata corruption, not a fabricated child allocation.
        set(root.leaf.data.dataType, "kernelDescriptor", KernelTypeDescriptor("SInt", Vector(late)))
        poisoned(session, owner)(select(session, root))
      case other => throw new AssertionError(other)
    assert(callbacks == 0)
    retainedOwner = owner
    atConstructorExit = remaining(owner)

  private def positive(session: AnyRef, owner: AnyRef, root: C2b2aRoot): Unit =
    val before = serial(session)
    val sourceCount = captures(session).size
    val debit = remaining(owner)
    val proxy = select(session, root)
    selectionCost = debit - remaining(owner)
    assert(selectionCost > 1L)
    val reference = declarations(session).get(proxy)
    val stamp = reference.constructionAllocation.get
    val binding = bindings(session)(reference)
    assert(serial(session) == before + 1L && stamp.serial == before + 1L)
    assert((stamp.session eq session) &&
      (stamp.module eq root.result.constructionRequest.get.module))
    assert(call(binding, "port") eq declarations(session).get(root.leaf.data))
    assert(call(binding, "instance") eq call(session, "instanceRecord", root.child))
    assert(captures(session).size == sourceCount + 1)
    call(session, "drainIncrementalBoundaryClosure")
    assert(proxy.dataType.asInstanceOf[KernelInstanceDataType[SInt]].finalized)
    val count = captures(session).size
    val cached = select(session, root)
    assert(cached eq proxy)
    assert(declarations(session).get(cached) eq reference)
    assert(reference.constructionAllocation.get eq stamp)
    assert(bindings(session)(reference) eq binding)
    assert(serial(session) == before + 1L && captures(session).size == count)
    call(owner, "verifyPublication")
    // This deliberately fabricated permit is NEVER used for a successful registration.
    foreignPermit = makePermit(session, root)._2
    driveAndVerify(session, owner, root, proxy, reference)

  private var drivenTarget: DeclarationRef = null
  private var drivenSource: ExpressionRef = null
  private var drivenOrdinal = -1
  private var drivenSite: SourceSpan = null

  private def driveAndVerify(
      session: AnyRef,
      owner: AnyRef,
      root: C2b2aRoot,
      proxy: Signal[SInt],
      target: DeclarationRef
  ): Unit =
    val scalar = KernelTypeDescriptor("SInt", Vector(2))
    assert(root.result.constructionFinalized && root.result.typeFinalized)
    assert(CandidateRuntime.typeDescriptor(proxy.dataType) == scalar)
    assert(CandidateRuntime.expressionDataType(root.result).exists(value =>
      CandidateRuntime.typeDescriptor(value) == scalar
    ))
    assert(root.result.constructionDependencies.head.asInstanceOf[AnyRef] eq root.input)
    val domainIds = field(session, "domainIds")
      .asInstanceOf[java.util.IdentityHashMap[AnyRef, DomainRef]]
    val selected = field(session, "selectedRequirementBindings")
      .asInstanceOf[mutable.Map[DomainRef, (DomainRef, String, Long, Int)]]
    val core = domainIds.get(root.core)
    assert(core != null && selected(domainIds.get(root.leaf.required))._1 == core)
    val records = field(session, "records").asInstanceOf[mutable.Map[Long, AnyRef]]
    val inputRef = declarations(session).get(root.input)
    val inputs = field(records(inputRef.module), "declarations")
      .asInstanceOf[mutable.ArrayBuffer[AnyRef]]
    assert(call(inputs(inputRef.index), "domainCandidate").asInstanceOf[Option[AnyRef]]
      .exists(_ eq root.core))
    val source = field(session, "expressionIds")
      .asInstanceOf[java.util.IdentityHashMap[AnyRef, ExpressionRef]].get(root.result)
    assert(source != null && source.module == target.module && source.module == inputRef.module)
    val operations = field(session, "operations").asInstanceOf[mutable.ArrayBuffer[AnyRef]]
    val count = operations.size
    assert(!operations.exists(op => call(op, "kind") == "assignment"))
    val next = field(session, "nextDigitalEffectOrdinal").asInstanceOf[mutable.Map[Long, Int]]
      .getOrElse(target.module, 0)
    val allocation = serial(session)
    val declarationCount = declarations(session).size
    root.driveCompletedScalar(proxy)
    assert(operations.size == count + 1 && serial(session) == allocation)
    assert(declarations(session).size == declarationCount)
    val operation = operations.last
    assert(call(operation, "kind") == "assignment")
    assert(call(operation, "owner").asInstanceOf[Long] == target.module)
    val values = call(operation, "values").asInstanceOf[Vector[AnyRef]]
    assert(values.size == 2 && (values.head eq proxy) && (values(1) eq root.result))
    assert(call(operation, "sourceOrdinal").asInstanceOf[Int] == next)
    Vector("generatedScope", "digitalRegion", "eventProcess").foreach(name =>
      assert(call(operation, name) == None)
    )
    val site = call(operation, "source").asInstanceOf[Option[SourceSpan]].get
    assert(site.path.endsWith("C2b2aRoot.scala") && site.line > 0)
    assert(bindings(session)(target) eq bindings(session)(declarations(session).get(proxy)))
    assert(call(bindings(session)(target), "port") eq declarations(session).get(root.leaf.data))
    drivenTarget = target
    drivenSource = source
    drivenOrdinal = next
    drivenSite = site
    // The new canonical one-assignment proof must admit the actual driven record.
    // This diagnostic call is real paid work; it is not removed or refunded to fit.
    assert(call(owner, "guard", (() => call(owner, "exactBoundDomainRaw")))
      .asInstanceOf[Option[AnyRef]].nonEmpty)

  private var boundaryCase = "recovery"

  /** Explicit corruption probes only, each in a fresh Session. */
  private def assignmentGates(session: AnyRef, owner: AnyRef, root: C2b2aRoot): Unit =
    if boundaryCase == "recovery" then return
    val operations = field(session, "operations").asInstanceOf[mutable.ArrayBuffer[AnyRef]]
    val operation = operations.last
    val ordinals = field(session, "nextDigitalEffectOrdinal").asInstanceOf[mutable.Map[Long, Int]]
    val savedOrdinals = ordinals.toVector
    val beforeSerial = serial(session)
    val beforeDeclarations = declarations(session).size
    val beforeBindings = bindings(session).toVector
    val beforeCaptures = captures(session).toVector
    val beforeOperations = operations.toVector
    val query =
      call(session, "currentBoundaryEpochQuery").asInstanceOf[StructuralParameterBinding.Query]
    assert(query.checkedLiteral("C2b2aRoot", "3") == 3L)
    val proof = call(
      owner,
      "guard",
      (() => call(owner, "exactBoundDomainRaw"))
    ).asInstanceOf[Option[AnyRef]].get
    val before = remaining(owner)
    var restore: () => Unit = () => ()
    def replace(target: AnyRef, name: String, value: AnyRef): Unit =
      val old = field(target, name)
      set(target, name, value)
      restore = () => set(target, name, old)
    boundaryCase match
      case "ordinal_without_assignment" =>
        operations.remove(operations.size - 1)
        restore = () => { operations += operation; () }
      case "assignment_empty_ordinals" | "post_admission_ordinals" =>
        ordinals.clear()
        restore = () => { ordinals ++= savedOrdinals; () }
      case "extra_child_ordinal" =>
        ordinals.update(declarations(session).get(root.leaf.data).module, 1)
        restore = () => { ordinals.clear(); ordinals ++= savedOrdinals; () }
      case "wrong_literal" =>
        val proxy =
          field(operation, "values").asInstanceOf[Vector[AnyRef]].head.asInstanceOf[Signal[SInt]]
        replace(proxy.dataType, "resolved", Some(KernelTypeDescriptor("SInt", Vector(3))))
      case "pending_source" => replace(root.result, "constructionState", Int.box(1))
      case "output_declaration" | "register_declaration" =>
        val reference = declarations(session).get(root.input)
        val records = field(session, "records").asInstanceOf[mutable.Map[Long, AnyRef]]
        val entries =
          field(records(reference.module), "declarations").asInstanceOf[mutable.ArrayBuffer[AnyRef]]
        val kind = if boundaryCase == "output_declaration" then KernelSignalKind.Output
        else KernelSignalKind.Register
        replace(entries(reference.index), "kind", kind)
      case "composition_observation_owner" =>
        // Explicit typed owner-marker corruption using an existing canonical ExpressionRef.
        // This is not an organically authored FSM/composition observation.
        replace(session, "compositionObservationRoot", Some(drivenSource))
      case "wrong_binding" =>
        val binding = bindings(session)(drivenTarget)
        replace(binding, "port", declarations(session).get(root.leaf.data).copy())
      case "generated_scope" => replace(operation, "generatedScope", Some(null))
      case "event_process" => replace(operation, "eventProcess", Some(null))
      case "digital_region" => replace(operation, "digitalRegion", Some(null))
      case other => throw new AssertionError(other)
    if boundaryCase == "post_admission_ordinals" then
      val failure =
        reject("bound-domain accounting: assignment identity changed during preparation"):
          call(owner, "guard", (() => call(owner, "requireBoundDomainResolved", proof)))
      assert(failure.getStackTrace.exists(_.getMethodName.contains("requireBoundDomainResolved")))
      assert(field(owner, "poisoned") == Boolean.box(true))
      assert(field(session, "constructorFailure").asInstanceOf[Option[?]].nonEmpty)
      assert(remaining(owner) < before && ordinals.isEmpty)
      Vector(
        "boundDomainAccountingActive",
        "publicationAccountingActive",
        "snapshotAccountingActive"
      )
        .foreach(name => assert(field(session, name) == Boolean.box(false)))
      reject("stale boundary snapshot epoch")(query.checkedLiteral("C2b2aRoot", "3"))
      reject("poisoned")(call(owner, "verifyPublication"))
      reject("poisoned")(call(session, "drainIncrementalBoundaryClosure"))
      assert(serial(session) == beforeSerial && declarations(session).size == beforeDeclarations)
      assert(bindings(session).toVector == beforeBindings &&
        captures(session).toVector == beforeCaptures)
      assert(operations.toVector == beforeOperations && ordinals.isEmpty)
      caught = true
      println(
        s"PASS post-admission mismatch poisoned no fallback debit=${before - remaining(owner)}"
      )
    else
      try
        assert(call(owner, "guard", (() => call(owner, "exactBoundDomainRaw"))) == None)
        assert(remaining(owner) < before)
        assert(field(owner, "poisoned") == Boolean.box(false))
        assert(field(session, "constructorFailure").asInstanceOf[Option[?]].isEmpty)
        Vector(
          "boundDomainAccountingActive",
          "publicationAccountingActive",
          "snapshotAccountingActive"
        )
          .foreach(name => assert(field(session, name) == Boolean.box(false)))
      finally restore()
      assert(call(
        owner,
        "guard",
        (() => call(owner, "exactBoundDomainRaw"))
      ).asInstanceOf[Option[AnyRef]].nonEmpty)
      assert(serial(session) == beforeSerial && declarations(session).size == beforeDeclarations)
      println(s"PASS structural fallback $boundaryCase debit=${before - remaining(owner)}")

  private def verifyDrivenSnapshot(snapshot: ConstructionSnapshot): Unit =
    val names = field(retainedOwner, "frozenNames").asInstanceOf[Option[SemanticOriginResult]].get
    val assignments = snapshot.digitalAssignments
    assert(assignments.size == 1)
    val assignment = assignments.head
    assert(assignment.target == names.declarationPaths((drivenTarget.module, drivenTarget.index)))
    assert(assignment.value == names.expressionPaths((drivenSource.module, drivenSource.index)))
    assert(assignment.sourceOrdinal == drivenOrdinal && assignment.kind == "continuous")
    assert(assignment.region.isEmpty && assignment.eventProcess.isEmpty)
    assert(snapshot.sourceMap.exists(entry =>
      entry.semanticPath == assignment.path && entry.source == drivenSite
    ))
    assert(snapshot.digitalExpressions.exists(value =>
      value.path == assignment.value
    ))

  private def terminal(outcome: String): Unit =
    val owner = field(retainedSession, "boundaryClosureOwner").asInstanceOf[Option[AnyRef]]
    owner match
      case Some(value) =>
        val balance = remaining(value)
        println(
          s"DRIVEN DIAGNOSTIC $outcome policy=${retainedSession.diagnosticConstructionWorkPolicyTag} initial=$initialBudget remaining=$balance cumulativeDebit=${initialBudget -
              balance}"
        )
      case None =>
        println(
          s"DRIVEN DIAGNOSTIC $outcome policy=${retainedSession.diagnosticConstructionWorkPolicyTag} owner=absent debit=uninitialized"
        )

  private def inspectDriven(): ConstructionSnapshot =
    try
      val snapshot = ConstructionKernel.inspect(new C2b2aRoot)
      verifyDrivenSnapshot(snapshot)
      terminal("success")
      snapshot
    catch
      case original: Throwable =>
        try terminal("failure")
        catch case observation: Throwable => original.addSuppressed(observation)
        throw original

  private def poisoned(session: AnyRef, owner: AnyRef)(body: => Any): Unit =
    reject("direct Instance request authority")(body)
    assert(field(owner, "poisoned") == Boolean.box(true))
    assert(field(session, "constructorFailure").asInstanceOf[Option[?]].nonEmpty)
    assert(field(session, "directPortRequest") == None)
    assert(field(session, "directPortPermit") == None)
    caught = true

  /** Return a private-corruption fixture only; never call the canonical positive route with it. */
  private def makePermit(session: AnyRef, root: C2b2aRoot): (AnyRef, AnyRef) =
    val selected = InstanceSelectionRecord(root.child)
    val target = construct(
      session.getClass.getName + "$DirectPortTarget",
      session,
      Some(root.result.constructionRequest.get),
      selected,
      call(session, "instanceRecord", root.child),
      root.result.constructionRequest.get.module,
      declarations(session).get(root.leaf.data),
      root.leaf.data,
      root.leaf.data.dataType,
      Vector.empty
    )
    val instance = call(session, "instanceRecord", root.child)
    val name =
      s"instance_${call(instance, "ordinal")}_port_${declarations(session).get(root.leaf.data).index}"
    val permit = construct(
      session.getClass.getName + "$DirectPortPermit",
      target,
      new KernelInstanceDataType[SInt](CandidateRuntime.typeDescriptor(root.leaf.data.dataType)),
      KernelSignalKind.InstanceInput,
      name
    )
    selected -> permit

  private def permitNegative(session: AnyRef, owner: AnyRef, root: C2b2aRoot, name: String): Unit =
    val query = call(session, "currentBoundaryEpochQuery")
      .asInstanceOf[StructuralParameterBinding.Query]
    assert(query.checkedLiteral("C2b2aRoot", "3") == 3L)
    val epoch = field(owner, "epoch").asInstanceOf[Long]
    val revision = field(owner, "revision")
    val before = serial(session)
    val sourceCount = captures(session).size
    val size = declarations(session).size
    val (selected, local) = makePermit(session, root)
    val permit = if name == "permit_foreign" then foreignPermit else local
    var kind = KernelSignalKind.InstanceInput
    var dataType = call(local, "dataType")
    var explicitName = call(local, "name")
    var dimensions = Vector.empty[Dimension]
    var boundary = Option.empty[InterfaceBoundaryRecord]
    name match
      case "permit_consumed" => set(local, "consumed", Boolean.box(true))
      case "permit_wrong_kind" => kind = KernelSignalKind.InstanceOutput
      case "permit_wrong_type" => dataType = Bool
      case "permit_wrong_name" => explicitName = "not_the_port"
      case "permit_dimensions" => dimensions = Vector(2)
      case "permit_boundary" => boundary = Some(null) // Deliberately invalid nonempty metadata.
      case "permit_missing" | "permit_foreign" => ()
      case other => throw new AssertionError(other)
    if name != "permit_missing" then
      stack(session, "instanceSelectionStack") += selected
      set(session, "directPortPermit", Some(permit))
    try
      reject("direct Instance request authority"):
        call(
          session,
          "registerDeclaration",
          new Object,
          kind,
          Some(dataType),
          Some(explicitName),
          None,
          Vector.empty,
          dimensions,
          boundary
        )
      if name == "permit_dimensions" || name == "permit_boundary" then
        assert(field(local, "consumed") == Boolean.box(false))
      assert(serial(session) == before && captures(session).size == sourceCount)
      assert(declarations(session).size == size && field(owner, "revision") == revision)
      assert(field(owner, "poisoned") == Boolean.box(true))
      assert(field(owner, "epoch").asInstanceOf[Long] > epoch)
      reject("stale boundary snapshot epoch")(query.checkedLiteral("C2b2aRoot", "3"))
      caught = true
    finally
      set(session, "directPortPermit", None)
      if name != "permit_missing" then
        stack(session, "instanceSelectionStack").remove(
          stack(session, "instanceSelectionStack").size - 1
        )

  private def halfState(session: AnyRef, owner: AnyRef, root: C2b2aRoot): Unit =
    val query = call(session, "currentBoundaryEpochQuery")
      .asInstanceOf[StructuralParameterBinding.Query]
    assert(query.checkedLiteral("C2b2aRoot", "3") == 3L)
    val epoch = field(owner, "epoch").asInstanceOf[Long]
    val before = serial(session)
    val old = declarations(session).keySet.toArray.toVector
    val sourceCount = captures(session).size
    val bindingCount = bindings(session).size
    val stacks = Vector(
      "moduleStack",
      "domainStack",
      "areaStack",
      "generationStack",
      "collectionStack",
      "instanceSelectionStack",
      "analogStack"
    )
      .map(name => name -> stack(session, name).toVector)
    val inventory = Vector(before, old.size.toLong, sourceCount.toLong, bindingCount.toLong) ++
      stacks.map(_._2.size.toLong)
    if mode == "calibrate_half_state" then halfInventory = inventory
    else
      assert(inventory == halfInventory && halfCost > 1L && remaining(owner) >= halfCost)
      // The successful selector's final debit is completeDirectPortRegistration.requestCharge(1).
      call(owner, "charge", Long.box(remaining(owner) - halfCost + 1L))
    val balance = remaining(owner)
    val attempt = scala.util.Try(select(session, root))
    if mode == "calibrate_half_state" then
      val proxy = attempt.get
      halfCost = balance - remaining(owner)
      assert(halfCost > 1L)
      val reference = declarations(session).get(proxy)
      assert(serial(session) == before + 1L && captures(session).size == sourceCount + 1)
      assert(reference.constructionAllocation.get.serial == before + 1L)
      assert(call(bindings(session)(reference), "port") eq
        declarations(session).get(root.leaf.data))
      call(session, "drainIncrementalBoundaryClosure")
      assert(proxy.dataType.asInstanceOf[KernelInstanceDataType[SInt]].finalized)
      driveAndVerify(session, owner, root, proxy, reference)
      println(s"CALIBRATED same-route selector debit=$halfCost")
      return
    val failure = attempt.failed.get
    assert(Option(failure.getMessage).exists(_.contains("boundary closure work budget exhausted")))
    assert(failure.getStackTrace.exists(frame =>
      frame.getMethodName.endsWith("completeDirectPortRegistration")
    ))
    val added =
      declarations(session).keySet.toArray.toVector.filterNot(value => old.exists(_ eq value))
    assert(added.size == 1 && serial(session) == before + 1L)
    val proxy = added.head
    val reference = declarations(session).get(proxy)
    val stamp = reference.constructionAllocation.get
    assert(stamp.serial == before + 1L && (stamp.session eq session))
    assert(!bindings(session).contains(reference) && bindings(session).size == bindingCount)
    assert(captures(session).size == sourceCount + 1 && remaining(owner) == 0L)
    stacks.foreach: (name, saved) =>
      val restored = stack(session, name).toVector
      assert(restored.size == saved.size && restored.zip(saved).forall((a, b) => a eq b))
    assert(field(session, "directPortRequest") == None &&
      field(session, "directPortPermit") == None)
    assert(field(owner, "poisoned") == Boolean.box(true))
    assert(field(owner, "epoch").asInstanceOf[Long] > epoch)
    reject("stale boundary snapshot epoch")(query.checkedLiteral("C2b2aRoot", "3"))
    reject("poisoned")(select(session, root))
    reject("poisoned")(call(owner, "verifyPublication"))
    reject("poisoned")(call(session, "drainIncrementalBoundaryClosure"))
    assert(serial(session) == before + 1L && (declarations(session).get(proxy) eq reference))
    assert(reference.constructionAllocation.get eq stamp)
    caught = true

  def main(args: Array[String]): Unit =
    assert(args.length == 1)
    diagnostic = true
    boundaryCase = args.head
    if boundaryCase == "post_admission_ordinals" then
      reject("captured constructor failure")(inspectDriven())
      assert(caught)
      boundaryCase = "recovery"
      inspectDriven()
      finished()
      println("PASS fresh owner recovery after post-admission poison")
    else
      inspectDriven()
      finished()
