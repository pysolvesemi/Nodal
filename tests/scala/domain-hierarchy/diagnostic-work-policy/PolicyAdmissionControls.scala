package nodal.internal.testkit.diagnosticpolicy.admission

import nodal.*

/** Source-only private-policy controls. Reflection observes state or invokes pre-existing internal
  * seams; it never changes a budget/remaining field or fabricates a positive authority carrier.
  */
object PolicyAdmissionControls:
  private var mode = "donor"
  private var session: ConstructionSession = null
  private var savedRoot: PolicyRoot = null
  private var savedCarrier: KernelExpr[Data] = null
  private var caught = false
  private var callbackCount = 0
  private val tag = Some("registered-construction-diagnostic-131072")

  private def call(target: AnyRef, name: String, args: AnyRef*): AnyRef =
    val method = (target.getClass.getMethods.toVector ++ target.getClass.getDeclaredMethods)
      .find(m =>
        (m.getName == name || m.getName.endsWith("$$" + name)) &&
          m.getParameterCount == args.size
      ).getOrElse(throw new AssertionError(name))
    method.setAccessible(true)
    try method.invoke(target, args*)
    catch case failure: java.lang.reflect.InvocationTargetException => throw failure.getCause
  private def field(target: AnyRef, name: String): AnyRef =
    val fields = target.getClass.getDeclaredFields.filter(_.getName.endsWith(name))
    assert(fields.length == 1, name)
    fields.head.setAccessible(true)
    fields.head.get(target)
  private def owner: Option[AnyRef] =
    field(session, "boundaryClosureOwner").asInstanceOf[Option[AnyRef]]
  private def debt: Long = field(session, "constructionRequestWork").asInstanceOf[Long]
  private def remaining(value: AnyRef): Long = field(value, "remaining").asInstanceOf[Long]
  private def rejected(fragment: String)(body: => Any): Throwable =
    val failure = scala.util.Try(body).failed.get
    assert(Option(failure.getMessage).exists(_.contains(fragment)), failure.toString)
    failure
  private def fail(fragment: String)(body: => Any): Unit =
    val oldOwner = owner
    val before = oldOwner.map(remaining)
    val beforeDebt = debt
    rejected(fragment)(body)
    assert(field(session, "constructorFailure").asInstanceOf[Option[?]].nonEmpty)
    oldOwner.foreach: original =>
      assert(owner.exists(_ eq original))
      assert(debt == beforeDebt)
      assert(remaining(original) <= before.get)
      assert(field(original, "poisoned") == Boolean.box(true))
    if oldOwner.isEmpty then
      owner.foreach: rejectedOwner =>
        assert(field(rejectedOwner, "incremental") == Boolean.box(false))
        assert(remaining(rejectedOwner) == 65536L)
        assert(field(rejectedOwner, "poisoned") == Boolean.box(true))
    caught = true

  def start(root: PolicyRoot): Unit =
    session = call(ConstructionKernel, "active").asInstanceOf[Option[ConstructionSession]].get
    assert(owner.isEmpty && debt == 0L)
    if mode != "late_bind" && mode != "legacy_65537" then
      if mode == "foreign_root_bind" then
        fail("NODAL-OWNERSHIP-017")(session.bindDiagnosticConstructionWork(savedRoot))
      else session.bindDiagnosticConstructionWork(root)
    if mode == "duplicate_bind" then
      fail("fresh ordinary root")(session.bindDiagnosticConstructionWork(root))

  private def reserve(input: Expr[Vec[SInt]], registered: Boolean): KernelExpr[Data] =
    val body = DigitalCollectionCallback.reductionBody(DigitalCollectionCallback.checked2(
      (left: Expr[SInt], _: Expr[SInt]) =>
        callbackCount += 1
        left
    ))
    if registered then
      session.reserveReductionConstructionIntent(input, body, "reduce_left")
    else session.reserveReductionConstruction(input, body)

  def exercise(root: PolicyRoot, input: Expr[Vec[SInt]]): Unit =
    if caught then return
    mode match
      case "no_request" => () // Real automatic finish must reject policy-only activation.
      case "ordinary_carrier" =>
        fail("actual registered carrier"):
          session.beginDiagnosticConstructionClosure(root, new KernelExpr[Data](Vector.empty))
      case "legacy_65537" =>
        // Legacy enable rejects without initializing 65537. It predates policy guard, so do not
        // assert the diagnostic constructorFailure behavior here.
        rejected("invalid incremental closure activation"):
          session.beginIncrementalBoundaryClosure(root, 65537)
        assert(owner.nonEmpty && remaining(owner.get) == 65536L)
        assert(field(owner.get, "poisoned") == Boolean.box(true))
        caught = true
        throw new IllegalStateException("legacy cap rejected")
      case "preowner_cap" =>
        val before = debt
        fail("construction request work exceeds the session budget"):
          call(session, "requestCharge", Long.box(65537L))
        assert(owner.isEmpty && debt == before && before == 16L)
      case "stage_ab" =>
        val ab = reserve(input, false)
        assert(!ab.registeredConstructionOwnedBy(session))
        fail("actual registered carrier")(session.beginDiagnosticConstructionClosure(root, ab))
      case _ =>
        val carrier = reserve(input, true)
        if mode == "late_bind" then
          fail("fresh ordinary root")(session.bindDiagnosticConstructionWork(root))
        else if mode == "copied_metadata" then
          val copy = new KernelExpr[Data](Vector.empty)
          copy.attachConstructionRequest(carrier.constructionRequest.get)
          assert(!copy.registeredConstructionOwnedBy(session))
          fail("actual registered carrier")(session.beginDiagnosticConstructionClosure(root, copy))
        else if mode == "foreign_carrier" then
          fail("actual registered carrier"):
            session.beginDiagnosticConstructionClosure(root, savedCarrier)
        else if mode == "legacy_mixing" then
          fail("cannot promote")(session.beginIncrementalBoundaryClosure(root, 65536))
        else
          call(session, "completeReductionSingleton", carrier, input, Vector(0))
          val preActivationDebt = debt
          assert(preActivationDebt > 0L && preActivationDebt <= 65536L)
          if mode == "exact_debt" then
            val regions = field(session, "expressionRegions")
              .asInstanceOf[scala.collection.mutable.Map[?, ?]]
            assert(regions.isEmpty)
            val inventory = field(session, "expressionValues")
              .asInstanceOf[scala.collection.mutable.Map[?, ?]].size.toLong
            val activated =
              call(session, "enableDiagnosticConstructionClosure", root, Some(carrier))
            assert(owner.exists(_ eq activated))
            assert(debt >= preActivationDebt && debt <= 65536L)
            assert(remaining(activated) == 131072L - debt - inventory)
            val transferredDebt = debt
            val balance = remaining(activated)
            call(activated, "prepareIncrementalInputs")
            session.drainIncrementalBoundaryClosure()
            assert(owner.exists(_ eq activated) && remaining(activated) < balance)
            assert(debt == transferredDebt)
          else session.beginDiagnosticConstructionClosure(root, carrier)
          val original = owner.get
          assert(debt >= preActivationDebt && debt <= 65536L)
          assert(131072L - remaining(original) >= debt)
          assert(session.diagnosticConstructionWorkPolicyTag == tag)
          val before = remaining(original)
          session.drainIncrementalBoundaryClosure()
          assert(owner.exists(_ eq original) && remaining(original) < before)
          val query = session.currentBoundaryEpochQuery()
          assert(query.checkedLiteral("PolicyRoot", "3") == 3L)
          mode match
            case "repeat_activation" | "exact_debt" =>
              fail("already exists")(session.beginDiagnosticConstructionClosure(root, carrier))
            case "bind_after_spend" =>
              fail("fresh ordinary root")(session.bindDiagnosticConstructionWork(root))
            case "legacy_after_spend" =>
              fail("cannot promote")(session.beginIncrementalBoundaryClosure(root, 65536))
            case "descriptor_depth" =>
              def nested(depth: Int): AnyRef =
                call(
                  session,
                  "closureDescriptorVisit",
                  (
                      () =>
                        if depth == 0 then null else nested(depth - 1)
                  )
                )
              fail("recursive descriptor traversal exceeds the closure depth budget"):
                nested(128)
              assert(remaining(original) > 0L)
              assert(field(session, "closureDescriptorDepth").asInstanceOf[Int] == 0)
            case "donor" =>
              savedRoot = root
              savedCarrier = carrier
            case other => throw new AssertionError(other)
          if caught then
            rejected("stale boundary snapshot epoch")(query.checkedLiteral("PolicyRoot", "3"))
            assert(owner.exists(_ eq original) && remaining(original) <= before)
            assert(session.diagnosticConstructionWorkPolicyTag == tag)
    assert(callbackCount == 0)

  def main(args: Array[String]): Unit =
    assert(args.isEmpty)
    ConstructionKernel.inspect(new PolicyRoot)
    val donorSession = session
    val donorOwner = owner.get
    assert(remaining(donorOwner) >= 0L && remaining(donorOwner) < 131072L)
    println(
      s"PASS donor full finish policy=${session.diagnosticConstructionWorkPolicyTag.get} remaining=${remaining(donorOwner)} cumulativeDebit=${131072L - remaining(donorOwner)}"
    )
    val cases = Vector(
      "no_request",
      "ordinary_carrier",
      "stage_ab",
      "copied_metadata",
      "foreign_carrier",
      "foreign_root_bind",
      "duplicate_bind",
      "late_bind",
      "legacy_65537",
      "legacy_mixing",
      "repeat_activation",
      "bind_after_spend",
      "legacy_after_spend",
      "descriptor_depth",
      "preowner_cap",
      "exact_debt"
    )
    cases.foreach: name =>
      mode = name
      caught = false
      val failure = scala.util.Try(ConstructionKernel.inspect(new PolicyRoot)).failed.get
      assert(session ne donorSession)
      if name == "no_request" then
        assert(Option(failure.getMessage).exists(_.contains("requires a registered request")))
        assert(owner.isEmpty)
        assert(field(session, "constructorFailure").asInstanceOf[Option[?]].nonEmpty)
      else assert(caught, failure.toString)
      assert(callbackCount == 0)
      println(s"PASS diagnostic policy $name")
    mode = "donor"
    caught = false
    ConstructionKernel.inspect(new PolicyRoot)
    assert(session ne donorSession)
    assert(callbackCount == 0)
    println("PASS diagnostic policy fresh-session recovery")

final class PolicyRoot extends Module:
  PolicyAdmissionControls.start(this)
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
  PolicyAdmissionControls.exercise(this, input)
