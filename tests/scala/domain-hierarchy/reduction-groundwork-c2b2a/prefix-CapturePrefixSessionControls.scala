package nodal.internal.testkit

import nodal.*
import scala.collection.mutable

/** SOURCE-ONLY DRAFT. Real Current-bound sessions and owner debits; reflection does not replace a
  * budget, owner, registry, source cache or walker. Only diagnostic callback fields are temporarily
  * set and restored. Budget retention uses the existing charge operation.
  */
object CapturePrefixSessionControls:
  import CapturePrefixDiagnostics.{call, field, set}
  private var mode = "prefix"
  private var callbacks = 0
  private var caught = false
  private var failedSession: AnyRef = null

  private def active(): ConstructionSession =
    call(ConstructionKernel, "active").asInstanceOf[Option[ConstructionSession]].get
  private def builder(session: ConstructionSession): SemanticOriginBuilder =
    field(session, "semanticOrigin").asInstanceOf[SemanticOriginBuilder]
  private def owner(session: ConstructionSession): AnyRef =
    field(session, "boundaryClosureOwner").asInstanceOf[Option[AnyRef]].get
  private def remaining(value: AnyRef): Long = field(value, "remaining").asInstanceOf[Long]
  private def capture(value: SemanticOriginBuilder): AnyRef = call(value, "captureSite")
  private def at(error: Throwable, name: String): Boolean =
    error.getStackTrace.exists(frame =>
      frame.getMethodName == name ||
        frame.getMethodName.endsWith("$$" + name) || frame.getMethodName.contains(name + "$")
    )
  private def original(error: Throwable): Throwable =
    if error.getClass.getSimpleName.contains("ResolutionWorkAborted") then
      original(call(error, "original").asInstanceOf[Throwable])
    else error
  private def reject(fragment: String)(body: => Any): Throwable =
    var failure: Throwable = null
    try body
    catch case caught: Throwable => failure = caught
    assert(failure != null, s"expected rejection: $fragment")
    assert(
      Option(failure.getMessage).exists(_.contains(fragment)),
      failure.toString + failure.getStackTrace.mkString("\n")
    )
    failure
  private def scopesOff(session: ConstructionSession): Unit =
    Vector("boundDomainAccountingActive", "snapshotAccountingActive", "publicationAccountingActive")
      .foreach(name => assert(field(session, name) == Boolean.box(false), name))

  private def gate(session: ConstructionSession, source: SemanticOriginBuilder, expected: Boolean)
      : Unit =
    val oldOwner = field(session, "boundaryClosureOwner")
    val oldSerial = field(session, "constructionAllocationSerial")
    val balance = oldOwner.asInstanceOf[Option[AnyRef]].map(remaining)
    assert(session.constructionCapturePrefixAllowed(source) == expected)
    assert(ConstructionKernel.constructionCapturePrefixAllowed(source) == expected)
    assert(field(session, "boundaryClosureOwner") eq oldOwner)
    assert(field(session, "constructionAllocationSerial") == oldSerial)
    assert(
      oldOwner.asInstanceOf[Option[AnyRef]].map(remaining) == balance,
      "read-only gate cannot create an owner, allocate, debit or reset"
    )

  def reserve(input: Expr[Vec[SInt]]): KernelExpr[Data] =
    val body = DigitalCollectionCallback.reductionBody(DigitalCollectionCallback.checked2(
      (left: Expr[SInt], _: Expr[SInt]) =>
        callbacks += 1
        left
    ))
    call(active(), "reserveReductionConstructionIntent", input, body, "reduce_left")
      .asInstanceOf[KernelExpr[Data]]

  /** No registry mutation is expected from a failed stack walk. Assert all retained identities,
    * source captures and allocation serial survive; poison is not hidden by rollback.
    */
  private def retainedState(session: ConstructionSession, closure: AnyRef): () => Unit =
    val serial = field(session, "constructionAllocationSerial")
    val identities = Vector(
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
    val captures = Vector(
      "modules",
      "domains",
      "declarations",
      "expressions",
      "instances",
      "operations"
    ).map: name =>
      val values = field(builder(session), name).asInstanceOf[mutable.ArrayBuffer[AnyRef]]
      val entries = values.toVector
      () =>
        assert(values.size == entries.size, name)
        assert(values.iterator.zip(entries).forall((a, b) => a eq b), name)
    val stacks = Vector(
      "moduleStack",
      "domainStack",
      "areaStack",
      "generationStack",
      "collectionStack",
      "instanceSelectionStack",
      "analogStack"
    ).map: name =>
      val values = field(session, name).asInstanceOf[mutable.ArrayBuffer[AnyRef]]
      val entries = values.toVector
      () =>
        assert(values.size == entries.size, name)
        assert(values.iterator.zip(entries).forall((a, b) => a eq b), name)
    val revision = field(closure, "revision")
    val builtRevision = field(closure, "builtRevision")
    () =>
      assert(field(session, "constructionAllocationSerial") == serial)
      (identities ++ maps ++ captures ++ stacks).foreach(check => check())
      assert(field(closure, "revision") == revision &&
        field(closure, "builtRevision") == builtRevision)
      assert(field(session, "directPortRequest") == None &&
        field(session, "directPortPermit") == None)

  private def poisonCapture(
      session: ConstructionSession,
      root: Module,
      source: SemanticOriginBuilder,
      expectPrefix: Boolean
  ): Unit =
    val closure = owner(session)
    val query = call(session, "currentBoundaryEpochQuery")
      .asInstanceOf[StructuralParameterBinding.Query]
    assert(query.checkedLiteral(root.getClass.getSimpleName, "3") == 3L)
    val epoch = field(closure, "epoch").asInstanceOf[Long]
    val previousWork = field(source, "resolutionWork")
    val previousLines = field(source, "resolutionSourceLines")
    val sourcePaths = field(source, "sourceCache")
      .asInstanceOf[mutable.Map[(String, String), Option[java.nio.file.Path]]]
    val oldSourcePaths = sourcePaths.toMap
    assert(previousWork == None && previousLines == None)
    // Prefix: initial capture debit 1 + iterator/buffer debit 2, then fail real frame debit 16.
    // Full path: initial capture debit 1, then fail original peek's real frame debit 16.
    val retain = if expectPrefix then 3L else 1L
    assert(remaining(closure) >= retain, "65536 budget must reach the requested capture seam")
    call(closure, "charge", Long.box(remaining(closure) - retain))
    assert(remaining(closure) == retain)
    val unchanged = retainedState(session, closure)
    val failure = reject("boundary closure work budget exhausted")(capture(source))
    assert(at(failure, "captureSite"), "must fail in actual captureSite, not preparation")
    assert(
      at(failure, "cachedConstructorPrefix") == expectPrefix,
      "actual thrown stack must identify the chosen collector"
    )
    assert(at(failure, "chargeWork"), "actual builder structural debit must fail")
    assert(
      !failure.getClass.getSimpleName.contains("ResolutionWorkAborted"),
      "borrowed meter failure must be unwrapped at the real captureSite boundary"
    )
    assert(field(source, "resolutionWork") eq previousWork)
    assert(field(source, "resolutionSourceLines") eq previousLines)
    assert(
      sourcePaths.toMap == oldSourcePaths,
      "failure precedes source discovery or cache mutation"
    )
    scopesOff(session)
    assert(remaining(closure) == 0L)
    assert(field(closure, "poisoned") == Boolean.box(true))
    assert(field(closure, "epoch").asInstanceOf[Long] > epoch)
    assert(field(session, "constructorFailure").asInstanceOf[Option[?]].nonEmpty)
    unchanged()
    reject("stale boundary snapshot epoch"):
      query.checkedLiteral(root.getClass.getSimpleName, "3")
    reject("poisoned")(call(session, "currentBoundaryEpochQuery"))
    reject("poisoned")(call(session, "drainIncrementalBoundaryClosure"))
    reject("poisoned")(call(closure, "verifyPublication"))
    reject("captured constructor failure")(call(session, "finish", root))
    unchanged()
    scopesOff(session)
    assert(callbacks == 0)
    failedSession = session
    caught = true
    println(s"PASS actual $mode capture collector=${if expectPrefix then "prefix" else "full"}: " +
      "debit exhaustion, meter restoration, poison, stale ticket and no rollback")

  /** A supplied callback is never proof of borrowed boundary authority. It is allowed to throw a
    * diagnostic sentinel; the real owner must not be debited or poisoned by it.
    */
  private def suppliedMeter(session: ConstructionSession, nested: Boolean): Unit =
    val source = builder(session)
    val closure = owner(session)
    val balance = remaining(closure)
    val oldWork = field(source, "resolutionWork")
    val oldLines = field(source, "resolutionSourceLines")
    val oldCanonical = field(source, "canonicalAccounting")
    val sentinel = new IllegalArgumentException("caller-owned capture callback")
    var frameChargeSeen = false
    var captureEntered = false
    var callbackObserved: AnyRef = null
    var linesObserved: AnyRef = null
    def refuseFrame(units: Int): Unit =
      if units == 16 then
        val stack = Thread.currentThread().getStackTrace.toVector
        assert(!stack.exists(_.getMethodName.contains("cachedConstructorPrefix")))
        assert(stack.exists(_.getMethodName.contains("captureSite")))
        frameChargeSeen = true
        throw sentinel
    val callback: Int => Unit = units =>
      if nested && !captureEntered then
        captureEntered = true
        callbackObserved = field(source, "resolutionWork")
        linesObserved = field(source, "resolutionSourceLines")
        assert(callbackObserved.asInstanceOf[Option[?]].nonEmpty)
        assert(linesObserved.asInstanceOf[Option[?]].nonEmpty)
        try capture(source)
        finally
          assert(field(source, "resolutionWork") eq callbackObserved)
          assert(field(source, "resolutionSourceLines") eq linesObserved)
      else refuseFrame(units)
    var failure: Throwable = null
    try
      if nested then source.resolveWithWork(callback)
      else
        val installed = Some(callback)
        set(source, "resolutionWork", installed)
        try capture(source)
        finally
          assert(field(source, "resolutionWork") eq installed)
          assert(field(source, "resolutionSourceLines") eq oldLines)
    catch case caught: Throwable => failure = caught
    finally if !nested then set(source, "resolutionWork", oldWork)
    assert(failure != null && (original(failure) eq sentinel) && frameChargeSeen)
    assert(field(source, "resolutionWork") eq oldWork)
    assert(field(source, "resolutionSourceLines") eq oldLines)
    assert(field(source, "canonicalAccounting") == oldCanonical)
    assert(remaining(closure) == balance && field(closure, "poisoned") == Boolean.box(false))
    assert(field(session, "constructorFailure") == None)
    scopesOff(session)
    println(s"PASS actual ${if nested then "nested resolveWithWork" else "caller callback"} " +
      "capture chooses original full collector and preserves its caller scope")

  def construction(root: CapturePrefixRoot): Unit =
    val session = active()
    assert(session ne failedSession)
    gate(session, builder(session), false)
    assert(field(session, "boundaryClosureOwner") == None)
    assert(root.result.pendingConstructionOwnedBy(session))
    call(session, "completeReductionSingleton", root.result, root.input, Vector(0))
    call(session, "beginIncrementalBoundaryClosure", root, Int.box(65536))
    assert(root.result.constructionFinalized && root.result.typeFinalized && callbacks == 0)
    gate(session, builder(session), true)
    val foreign = new SemanticOriginBuilder
    gate(session, foreign, false)
    mode match
      case "prefix" => poisonCapture(session, root, builder(session), expectPrefix = true)
      case "foreign" => poisonCapture(session, root, foreign, expectPrefix = false)
      case "callback" | "nested" =>
        suppliedMeter(session, nested = mode == "nested")
        // The following independent borrowed capture establishes recovery of the gate and scope.
        poisonCapture(session, root, builder(session), expectPrefix = true)
      case other => throw new AssertionError(other)

  def c1(root: CapturePrefixC1Root): Unit =
    val session = active()
    assert(field(session, "registeredConstructionAdmission") == Boolean.box(false))
    call(session, "beginIncrementalBoundaryClosure", root, Int.box(65536))
    assert(!call(owner(session), "hasConstructionWork").asInstanceOf[Boolean])
    gate(session, builder(session), false)
    poisonCapture(session, root, builder(session), expectPrefix = false)

  def ordinary(root: CapturePrefixOrdinaryRoot): Unit =
    assert(root != null)
    val session = active()
    assert(session ne failedSession)
    assert(field(session, "boundaryClosureOwner") == None)
    assert(field(session, "constructorFailure") == None)
    val source = builder(session)
    gate(session, source, false)
    val site = capture(source)
    assert(call(site, "span").asInstanceOf[Option[SourceSpan]].nonEmpty)
    assert(field(source, "resolutionWork") == None &&
      field(source, "resolutionSourceLines") == None)
    assert(field(session, "boundaryClosureOwner") == None && callbacks == 0)
    scopesOff(session)

  def main(args: Array[String]): Unit =
    val all = Vector("prefix", "c1", "foreign", "callback", "nested")
    assert(args.isEmpty || (args.length == 1 && (all :+ "ordinary").contains(args.head)))
    val unbound = new SemanticOriginBuilder
    assert(!ConstructionKernel.boundaryWorkActive)
    assert(!ConstructionKernel.constructionCapturePrefixAllowed(unbound))
    assert(field(unbound, "resolutionWork") == None)
    capture(unbound)
    assert(!ConstructionKernel.boundaryWorkActive && field(unbound, "resolutionWork") == None)
    val cases = args.headOption.filter(_ != "ordinary").map(Vector(_))
      .getOrElse(if args.headOption.contains("ordinary") then Vector.empty else all)
    ConstructionKernel.inspect(new CapturePrefixOrdinaryRoot)
    cases.foreach: selected =>
      mode = selected
      caught = false
      reject("captured constructor failure"):
        if mode == "c1" then ConstructionKernel.inspect(new CapturePrefixC1Root)
        else ConstructionKernel.inspect(new CapturePrefixRoot)
      assert(caught && callbacks == 0)
      ConstructionKernel.inspect(new CapturePrefixOrdinaryRoot)
      println(s"PASS $mode fresh owner-free ordinary-session recovery")
    println("PASS real-session capture gates; no typed17/final-publication budget claim")
