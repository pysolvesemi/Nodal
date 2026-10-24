package nodal.internal.testkit

import nodal.*

object PublicationControls:
  private var observed = false
  private var failed: AnyRef = null
  private def call(target: AnyRef, name: String, args: AnyRef*): AnyRef =
    val methods = target.getClass.getMethods.toVector ++ target.getClass.getDeclaredMethods
    val method = methods.find(m =>
      (m.getName == name || m.getName.endsWith("$$" + name)) && m.getParameterCount == args.size
    ).get
    method.setAccessible(true)
    try method.invoke(target, args*)
    catch case e: java.lang.reflect.InvocationTargetException => throw e.getCause
  private def field(target: AnyRef, name: String): AnyRef =
    val selected = target.getClass.getDeclaredFields.filter(_.getName.endsWith(name))
    assert(selected.length == 1)
    selected.head.setAccessible(true)
    selected.head.get(target)
  private def remaining(owner: AnyRef): Long = field(owner, "remaining").asInstanceOf[Long]
  private def off(session: AnyRef): Unit =
    assert(field(session, "publicationAccountingActive") == java.lang.Boolean.FALSE)
    assert(field(session, "snapshotAccountingActive") == java.lang.Boolean.FALSE)
  private def reject(fragment: String)(body: => Any): Throwable =
    val error = scala.util.Try(body).failed.get
    assert(
      Option(error.getMessage).exists(_.contains(fragment)),
      error.toString + error.getStackTrace.mkString("\n")
    )
    error
  def exercise(root: Module, input: Expr[Vec[SInt]], inject: Boolean): Unit =
    val session = call(ConstructionKernel, "active").asInstanceOf[Option[AnyRef]].get
    assert(session ne failed)
    val result = C2aAccountingControls.reserve(input)
    call(session, "completeReductionSingleton", result, input, Vector(0))
    call(session, "beginIncrementalBoundaryClosure", root, Int.box(65536))
    val owner = field(session, "boundaryClosureOwner").asInstanceOf[Option[AnyRef]].get
    off(session)
    val before = remaining(owner)
    assert(call(owner, "exactPublicationEligible", root) == Boolean.box(inject))
    val eligibility = before - remaining(owner)
    assert(eligibility > 0)
    off(session)
    if inject then
      val semantic = field(owner, "frozenNames").asInstanceOf[Option[SemanticOriginResult]].get
      assert(semantic.sourceMap.size >= 2)
      val query = call(session, "currentBoundaryEpochQuery")
        .asInstanceOf[StructuralParameterBinding.Query]
      assert(query.checkedLiteral(root.getClass.getSimpleName, "1") == 1L)
      val epoch = field(owner, "epoch").asInstanceOf[Long]
      val beforeNames = remaining(owner)
      call(owner, "finalNames", semantic, Boolean.box(true))
      val names = beforeNames - remaining(owner)
      val retained = eligibility + names + 2L * semantic.sourceMap.size + 1L
      assert(names > 0 && remaining(owner) >= retained)
      call(owner, "charge", Long.box(remaining(owner) - retained))
      assert(remaining(owner) == retained)
      val body = () =>
        assert(field(session, "publicationAccountingActive") == java.lang.Boolean.TRUE)
        call(session, "publicationSourceIndex", semantic.sourceMap)
      val error = reject("boundary closure work budget exhausted"):
        call(owner, "publication", root, semantic, body)
      assert(error.getStackTrace.exists(_.getMethodName.contains("publicationSourceIndex")))
      assert(error.getStackTrace.exists(_.getMethodName == "next"))
      off(session)
      assert(remaining(owner) == 0L && field(owner, "poisoned") == java.lang.Boolean.TRUE)
      assert(field(owner, "epoch").asInstanceOf[Long] > epoch)
      assert(field(session, "constructorFailure").asInstanceOf[Option[?]].nonEmpty)
      reject("stale boundary snapshot epoch")(query.checkedLiteral(
        root.getClass.getSimpleName,
        "1"
      ))
      failed = session
      observed = true
  def main(args: Array[String]): Unit =
    assert(args.isEmpty)
    assert(!ConstructionKernel.publicationAccountingEnabled)
    ConstructionKernel.chargePublicationWork(Long.MaxValue)
    ConstructionKernel.inspect(new PublicationFallback)
    println("PASS publication ineligible profile before scope")
    reject("captured constructor failure"):
      ConstructionKernel.inspect(new PublicationPoison)
    assert(observed)
    println("PASS publication scan exhaustion restores scope and poisons owner/ticket/publication")
    ConstructionKernel.inspect(new C2aAccountingOrdinary)
    assert(!ConstructionKernel.publicationAccountingEnabled)
    ConstructionKernel.chargePublicationWork(Long.MaxValue)
    println("PASS publication fresh ordinary recovery and no-owner facade")

final class PublicationFallback extends Module:
  val input = in(Vec(SInt(2), 1))
  PublicationControls.exercise(this, input, false)

final class PublicationPoison extends Module:
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
  PublicationControls.exercise(this, input, true)
