package nodal.internal.testkit

import nodal.*
import scala.collection.mutable

/** Reflection accesses internal seams and negative-case state. Positive proxy admission uses the
  * typed fixed selector, never a forged permit.
  */
object C2b2aControls:
  var mode = "new_and_cached"
  private var callbacks = 0
  private var caught = false
  private var selectionCost = 0L
  private var foreignPermit: AnyRef = null

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
    call(session, "beginIncrementalBoundaryClosure", root, Int.box(65536))
    call(session, "drainIncrementalBoundaryClosure")
    val owner = field(session, "boundaryClosureOwner").asInstanceOf[Option[AnyRef]].get
    mode match
      case "new_and_cached" => positive(session, owner, root)
      case "post_registration_failure" => halfState(session, owner, root)
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
    assert(selectionCost > 1L && remaining(owner) >= selectionCost)
    call(owner, "charge", Long.box(remaining(owner) - selectionCost + 1L))
    val failure = reject("boundary closure work budget exhausted")(select(session, root))
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
    assert(args.isEmpty)
    ConstructionKernel.inspect(new C2b2aRoot)
    println("PASS C2b2a new proxy, drain and finalized cache reuse")
    val negative = Vector(
      "copied_unregistered",
      "late_occurrence",
      "late_foreign_type_root",
      "cached_mapping",
      "cached_target",
      "cached_formal",
      "permit_missing",
      "permit_consumed",
      "permit_foreign",
      "permit_wrong_kind",
      "permit_wrong_type",
      "permit_wrong_name",
      "permit_dimensions",
      "permit_boundary",
      "post_registration_failure"
    )
    negative.foreach: name =>
      mode = name
      caught = false
      reject("captured constructor failure")(ConstructionKernel.inspect(new C2b2aRoot))
      assert(caught && callbacks == 0)
      println(s"PASS C2b2a $name")
    mode = "new_and_cached"
    ConstructionKernel.inspect(new C2b2aRoot)
    assert(callbacks == 0)
    println("PASS C2b2a fresh recovery")
