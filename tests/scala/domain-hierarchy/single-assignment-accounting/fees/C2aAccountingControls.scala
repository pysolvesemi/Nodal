package nodal.internal.testkit

import nodal.*
import scala.collection.mutable

/** Internal scope controls, not public callback admission or scheduler-entry evidence. */
object C2aAccountingControls:
  private var failSnapshot = false
  private var failedSession: Option[AnyRef] = None
  private var caughtFailure = false
  private var callbacks = 0
  private var observed: AnyRef = null
  private def report(label: String): Unit =
    val owner = closure(observed).get
    println(s"FEE $label remaining=${remaining(owner)}")

  private def call(target: AnyRef, name: String, args: AnyRef*): AnyRef =
    val methods = target.getClass.getMethods.toVector ++ target.getClass.getDeclaredMethods
    val method = methods.find(value =>
      (value.getName == name || value.getName.endsWith("$$" + name)) &&
        value.getParameterCount == args.size
    ).getOrElse(throw new AssertionError(s"missing ${target.getClass.getName}.$name"))
    method.setAccessible(true)
    try method.invoke(target, args*)
    catch case failure: java.lang.reflect.InvocationTargetException => throw failure.getCause

  private def field(target: AnyRef, suffix: String): AnyRef =
    val fields = target.getClass.getDeclaredFields.filter(_.getName.endsWith(suffix))
    assert(fields.length == 1, s"missing/ambiguous ${target.getClass.getName}.$suffix")
    fields.head.setAccessible(true)
    fields.head.get(target)

  private def reject(fragment: String)(body: => Any): Throwable =
    val failure = scala.util.Try(body).failed.get
    assert(Option(failure.getMessage).exists(_.contains(fragment)), failure.toString)
    failure

  private def active(): Option[AnyRef] = call(ConstructionKernel, "active")
    .asInstanceOf[Option[AnyRef]]
  private def closure(session: AnyRef): Option[AnyRef] = field(session, "boundaryClosureOwner")
    .asInstanceOf[Option[AnyRef]]
  private def remaining(owner: AnyRef): Long = field(owner, "remaining").asInstanceOf[Long]
  private def scopeOff(session: AnyRef): Unit =
    assert(field(session, "snapshotAccountingActive") == java.lang.Boolean.FALSE)
    val builder = field(session, "semanticOrigin")
    assert(field(builder, "resolutionWork") == None)
    assert(field(builder, "resolutionSourceLines") == None)

  private def facade(): Unit =
    call(ConstructionKernel, "chargeSnapshotWork", Long.box(Long.MaxValue))

  private def noOwner(): Unit =
    assert(active().isEmpty)
    facade()
    assert(active().isEmpty, "snapshot facade must not create a Session")

  def ordinary(): Unit =
    val session = active().get
    assert(!failedSession.exists(_ eq session))
    assert(closure(session).isEmpty && field(session, "constructorFailure") == None)
    scopeOff(session)
    facade()
    assert(closure(session).isEmpty && field(session, "constructorFailure") == None)
    scopeOff(session)

  def reserve(input: Expr[Vec[SInt]]): KernelExpr[Data] =
    val session = active().get
    assert(closure(session).isEmpty)
    val body = DigitalCollectionCallback.reductionBody(DigitalCollectionCallback.checked2(
      (left: Expr[SInt], _: Expr[SInt]) =>
        callbacks += 1
        left
    ))
    val result = call(session, "reserveReductionConstructionIntent", input, body, "reduce_left")
      .asInstanceOf[KernelExpr[Data]]
    assert(!result.constructionFinalized && !result.typeFinalized)
    assert(result.pendingConstructionOwnedBy(session))
    assert(result.constructionKind.contains("reduce_left"))
    assert(result.constructionDependencies == Vector(input))
    assert(result.constructionCallback.contains(body))
    result

  def exercise(root: Module, input: Expr[Vec[SInt]], result: KernelExpr[Data], exact: Boolean)
      : Unit =
    val session = active().get
    observed = session
    val ids = field(session, "expressionIds")
      .asInstanceOf[java.util.IdentityHashMap[AnyRef, ExpressionRef]]
    val declarations = field(session, "declarationIds")
      .asInstanceOf[java.util.IdentityHashMap[AnyRef, DeclarationRef]]
    val boundaries = field(session, "instanceDigitalPorts")
      .asInstanceOf[mutable.Map[DeclarationRef, AnyRef]]
    assert(declarations.containsKey(input))
    assert(boundaries.contains(declarations.get(input)) == exact)
    assert(DeferredResultType.pending(input) == exact)
    val reference = ids.get(result)
    val slot = result.constructionSourceSlot
    val captures = field(field(session, "semanticOrigin"), "expressions")
      .asInstanceOf[mutable.ArrayBuffer[AnyRef]]
    val captureCount = captures.size
    val capture = captures(slot)
    val site = call(capture, "site")
    val source = call(site, "span").asInstanceOf[Option[SourceSpan]].get
    assert(source.path.endsWith("C2aAccountingControls.scala") && source.line > 0)
    if !exact then call(session, "completeReductionSingleton", result, input, Vector(0))
    call(session, "beginIncrementalBoundaryClosure", root, Int.box(65536))
    call(session, "drainIncrementalBoundaryClosure")
    val owner = closure(session).get
    assert(field(owner, "constructionMode") == java.lang.Boolean.TRUE)
    assert(field(owner, "drained") == java.lang.Boolean.TRUE)
    assert(ids.get(result) == reference && result.constructionSourceSlot == slot)
    assert(captures.size == captureCount)
    val completedCapture = captures(slot)
    assert(call(completedCapture, "value") eq result)
    assert(call(completedCapture, "module") == call(capture, "module"))
    assert(call(completedCapture, "index") == call(capture, "index"))
    assert(call(completedCapture, "site") == site)
    assert(call(completedCapture, "operands") == Vector(input, 0))
    assert(result.constructionFinalized && result.typeFinalized &&
      !DeferredResultType.pending(input))
    assert(result.operation.contains("shape_index") && result.operands == Vector(input, 0))
    val canonical = field(session, "expressionValues")
      .asInstanceOf[mutable.Map[ExpressionRef, KernelExpr[?]]]
    assert(canonical(reference) eq result)
    val indices = field(session, "shapeIndices").asInstanceOf[mutable.Map[ExpressionRef, AnyRef]]
    assert(indices.size == 1 && call(indices(reference), "indices") == Vector(0))
    assert(callbacks == 0)
    scopeOff(session)
    val beforeFacade = remaining(owner)
    facade()
    assert(remaining(owner) == beforeFacade)
    val beforeEligibility = remaining(owner)
    assert(call(owner, "exactSnapshotEligible") == Boolean.box(exact))
    val eligibilityCost = beforeEligibility - remaining(owner)
    println(
      s"FEE exercise exact=$exact poison=$failSnapshot eligibility=$eligibilityCost remaining=${remaining(owner)}"
    )
    assert(eligibilityCost > 0L)
    scopeOff(session)
    if failSnapshot then inject(session, owner, eligibilityCost)
    else assert(field(owner, "poisoned") == java.lang.Boolean.FALSE)

  private def inject(session: AnyRef, owner: AnyRef, eligibilityCost: Long): Unit =
    val query = call(session, "currentBoundaryEpochQuery")
      .asInstanceOf[StructuralParameterBinding.Query]
    assert(query.checkedLiteral("C2aAccountingRoot", "3") == 3L)
    val epoch = field(owner, "epoch").asInstanceOf[Long]
    val beforeLinear = remaining(owner)
    call(owner, "prechargeCanonicalPass", Boolean.box(false))
    val linearCost = beforeLinear - remaining(owner)
    assert(linearCost > 0L)
    val retained = eligibilityCost + linearCost + 2L
    assert(remaining(owner) >= retained)
    call(owner, "charge", Long.box(remaining(owner) - retained))
    assert(remaining(owner) == retained)
    val failure = reject("boundary closure work budget exhausted")(call(owner, "snapshot"))
    def at(name: String): Boolean = failure.getStackTrace.exists(frame =>
      frame.getMethodName == name || frame.getMethodName.endsWith("$$" + name)
    )
    assert(at("snapshot") && at("snapshots") && at("snapshotCharge") && at("next"))
    assert(!at("exactSnapshotEligible") && !at("prechargeCanonicalPass"))
    assert(remaining(owner) == 0L)
    scopeOff(session)
    assert(field(owner, "poisoned") == java.lang.Boolean.TRUE)
    assert(field(owner, "epoch").asInstanceOf[Long] > epoch)
    assert(field(session, "constructorFailure").asInstanceOf[Option[?]].nonEmpty)
    reject("stale boundary snapshot epoch")(query.checkedLiteral("C2aAccountingRoot", "3"))
    reject("poisoned")(call(session, "currentBoundaryEpochQuery"))
    scopeOff(session)
    failedSession = Some(session)
    caughtFailure = true

  def main(args: Array[String]): Unit =
    val selected = args.sameElements(Array("poison-recovery"))
    assert(args.isEmpty || selected)
    if !selected then
      noOwner()
      println("PASS accounting no-owner facade")
      ConstructionKernel.inspect(new C2aAccountingOrdinary)
      println("PASS accounting ordinary facade")
      ConstructionKernel.inspect(new C2aAccountingRoot)
      report("exact-profile-final")
      println("PASS accounting exact profile")
      ConstructionKernel.inspect(new C2aAccountingDomain)
      report("domain-fallback-final")
      println("PASS accounting domain fallback")
    failSnapshot = true
    reject("captured constructor failure"):
      ConstructionKernel.inspect(new C2aAccountingRoot)
    assert(caughtFailure && callbacks == 0)
    println("PASS accounting private snapshot restoration and real ticket poison")
    failSnapshot = false
    ConstructionKernel.inspect(new C2aAccountingOrdinary)
    noOwner()
    println("PASS accounting fresh ordinary recovery")
    println(s"PASS ${if selected then 2 else 6} C2a accounting controls")

final class C2aAccountingOrdinary extends Module:
  C2aAccountingControls.ordinary()

final class C2aAccountingRoot extends Module:
  val child = instance(new C2aLeaf(1))
  val input = child(_.data)
  val result = C2aAccountingControls.reserve(input)
  C2aAccountingControls.exercise(this, input, result, exact = true)

final class C2aAccountingDomain extends Module:
  val core = ClockDomain.external(
    "core",
    ClockEdge.Rising,
    ResetPolicy.Sync,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  var input: Signal[Vec[SInt]] = null
  core:
    input = in(Vec(SInt(2), 1))
  val result = C2aAccountingControls.reserve(input)
  C2aAccountingControls.exercise(this, input, result, exact = false)
