package nodal.internal.testkit

import nodal.*
import scala.collection.mutable

/** Trusted package-internal lifecycle controls. Checked2 is the existing validated-carrier seam,
  * not evidence that arbitrary public callbacks or deferred nonsingleton bodies are admitted.
  */
object C2aControls:
  private var mode = "auto"
  private var kind = "reduce_left"
  private var callbackCalls = 0
  var count = 1
  def keepUnused: Boolean = mode == "unused" || mode == "duplicate"
  def scopedDrain: Boolean = mode == "scoped"

  private final case class Reservation(
      session: AnyRef,
      input: Expr[Vec[SInt]],
      result: KernelExpr[Data],
      body: DigitalCollectionCallback.ReductionBody,
      reference: ExpressionRef,
      slot: Int,
      capture: AnyRef,
      site: AnyRef,
      source: SourceSpan
  )
  private var last: Option[Reservation] = None
  private var caughtPostSeal = false
  private var caughtLate = false
  private var caughtScoped = false
  private var ordinaryIndex: Option[(KernelExpr[SInt], ExpressionRef)] = None

  private def call(target: AnyRef, name: String, arguments: AnyRef*): AnyRef =
    val methods = target.getClass.getMethods.toVector ++ target.getClass.getDeclaredMethods
    val method = methods.find(value =>
      (value.getName == name || value.getName.endsWith("$$" + name)) &&
        value.getParameterCount == arguments.size
    ).getOrElse(throw new AssertionError(s"missing ${target.getClass.getName}.$name"))
    method.setAccessible(true)
    try method.invoke(target, arguments*)
    catch case failure: java.lang.reflect.InvocationTargetException => throw failure.getCause

  private def field(target: AnyRef, suffix: String): AnyRef =
    val matches = target.getClass.getDeclaredFields.filter(_.getName.endsWith(suffix))
    assert(matches.length == 1, s"ambiguous or missing ${target.getClass.getName}.$suffix")
    matches.head.setAccessible(true)
    matches.head.get(target)

  private def reject(fragment: String)(run: => Any): Throwable =
    val failure = scala.util.Try(run).failed.get
    assert(Option(failure.getMessage).exists(_.contains(fragment)), failure.toString)
    failure

  private def session(): AnyRef =
    call(ConstructionKernel, "active").asInstanceOf[Option[AnyRef]].get

  private def body(): DigitalCollectionCallback.ReductionBody =
    DigitalCollectionCallback.reductionBody(DigitalCollectionCallback.checked2(
      (left: Expr[SInt], _: Expr[SInt]) =>
        callbackCalls += 1
        left
    ))

  private def identities(owner: AnyRef): java.util.IdentityHashMap[AnyRef, ExpressionRef] =
    field(owner, "expressionIds").asInstanceOf[java.util.IdentityHashMap[AnyRef, ExpressionRef]]

  private def origins(owner: AnyRef): mutable.ArrayBuffer[AnyRef] =
    field(field(owner, "semanticOrigin"), "expressions")
      .asInstanceOf[mutable.ArrayBuffer[AnyRef]]

  private def indices(owner: AnyRef): mutable.Map[ExpressionRef, AnyRef] =
    field(owner, "shapeIndices").asInstanceOf[mutable.Map[ExpressionRef, AnyRef]]

  private def closure(owner: AnyRef): Option[AnyRef] =
    field(owner, "boundaryClosureOwner").asInstanceOf[Option[AnyRef]]

  private def remaining(owner: AnyRef): Long = field(owner, "remaining").asInstanceOf[Long]

  private def payload(value: KernelExpr[?]): Option[KernelExpressionPayload] =
    field(value, "payload").asInstanceOf[Option[KernelExpressionPayload]]

  private def clean(owner: AnyRef): Unit =
    val builder = field(owner, "semanticOrigin")
    assert(field(builder, "resolutionWork") == None)
    assert(field(builder, "resolutionSourceLines") == None)

  private def unchangedIdentity(value: Reservation): Unit =
    assert(identities(value.session).get(value.result) == value.reference)
    val canonical = field(value.session, "expressionValues")
      .asInstanceOf[mutable.Map[ExpressionRef, KernelExpr[?]]]
    assert(canonical.get(value.reference).exists(_ eq value.result))
    assert(value.result.constructionSourceSlot == value.slot)
    val captures = origins(value.session)
    assert(captures.count(item => call(item, "value") eq value.result) == 1)
    val capture = captures(value.slot)
    assert(call(capture, "value") eq value.result)
    assert(call(capture, "site") == value.site)
    assert(value.result.constructionDependencies == Vector(value.input))
    assert(value.result.constructionCallback.contains(value.body))
    assert(callbackCalls == 0)

  private def reservedPayloadGate(result: KernelExpr[?]): Unit =
    assert(!result.constructionFinalized && !result.typeFinalized)
    assert(DeferredResultType.pending(result) && DeferredResultType.hasTypeObligation(result))
    assert(payload(result).isEmpty)
    reject("not complete")(result.operands)
    reject("not complete")(result.literal)
    reject("not complete")(result.operation)
    reject("not complete")(result.materializationIntent)
    reject("not complete")(result.typeIntent)
    reject("not complete")(result.interfaceMember)

  def reserve(input: Expr[Vec[SInt]]): KernelExpr[Data] =
    reserveActual(input, selected = true, sourceFile = "C2aRoot.scala")

  def reserveLocal(input: Expr[Vec[SInt]]): KernelExpr[Data] =
    reserveActual(input, selected = false, sourceFile = "C2aManualRoot.scala")

  def reserveAssigned(input: Expr[Vec[SInt]]): KernelExpr[Data] =
    reserveActual(input, selected = true, sourceFile = "C2aAssignedRoot.scala")

  private def reserveActual(
      input: Expr[Vec[SInt]],
      selected: Boolean,
      sourceFile: String
  ): KernelExpr[Data] =
    val owner = session()
    assert(closure(owner).isEmpty)
    assert(DeferredResultType.pending(input) == selected)
    val declarations = field(owner, "declarationIds")
      .asInstanceOf[java.util.IdentityHashMap[AnyRef, DeclarationRef]]
    val boundaries = field(owner, "instanceDigitalPorts")
      .asInstanceOf[mutable.Map[DeclarationRef, AnyRef]]
    assert(declarations.containsKey(input))
    assert(boundaries.contains(declarations.get(input)) == selected)
    val checked = body()
    val result = (if mode == "orphan" then
                    call(owner, "reserveReductionConstruction", input, checked)
                  else call(owner, "reserveReductionConstructionIntent", input, checked, kind))
      .asInstanceOf[KernelExpr[Data]]
    reservedPayloadGate(result)
    assert(result.constructionKind == (if mode == "orphan" then None else Some(kind)))
    assert(result.pendingConstructionOwnedBy(owner) == (mode != "orphan"))
    val reference = identities(owner).get(result)
    val slot = result.constructionSourceSlot
    val capture = origins(owner)(slot)
    val site = call(capture, "site")
    val source = call(site, "span").asInstanceOf[Option[SourceSpan]].get
    assert(source.path.endsWith(sourceFile) && source.line > 0)
    val reservation =
      Reservation(owner, input, result, checked, reference, slot, capture, site, source)
    last = Some(reservation)
    unchangedIdentity(reservation)
    assert(call(capture, "operands") == Vector(input))
    result

  def checkStillPending(): Unit = reservedPayloadGate(last.get.result)

  def completeBeforeFinish(): Unit =
    val value = last.get
    assert(closure(value.session).isEmpty)
    assert(field(value.session, "domainStack").asInstanceOf[mutable.ArrayBuffer[?]].isEmpty)
    val completedResult = call(
      value.session,
      "completeReductionSingleton",
      value.result,
      value.input,
      Vector(0)
    )
    assert(completedResult eq value.result)
    completed(value, 1)
    assert(closure(value.session).isEmpty, "manual completion must not prepare the boundary owner")

  def refuseScopedDrain(root: C2aManualRoot): Unit =
    val value = last.get
    val domains = field(value.session, "domainStack").asInstanceOf[mutable.ArrayBuffer[AnyRef]]
    val originalDomains = domains.toVector
    val originalState = field(value.result, "constructionState")
    assert(originalDomains.nonEmpty && closure(value.session).isEmpty)
    assert(root.result eq value.result)
    assert(root.input eq value.input)
    val failure = reject("C2a construction lost its original root context"):
      call(value.session, "beginIncrementalBoundaryClosure", root, Int.box(65536))
    assert(failure.getStackTrace.exists(_.getMethodName.startsWith("drainIncremental")))
    assert(!failure.getStackTrace.exists(_.getMethodName == "completeReductionSingleton"))
    assert(domains.toVector == originalDomains)
    assert(field(value.result, "constructionState") == originalState)
    reservedPayloadGate(value.result)
    unchangedIdentity(value)
    assert(origins(value.session)(value.slot) eq value.capture)
    assert(!indices(value.session).contains(value.reference))
    val _ = poisoned(value)
    caughtScoped = true

  private def completed(value: Reservation, expectedIndices: Int): Unit =
    unchangedIdentity(value)
    assert(value.result.constructionFinalized && value.result.typeFinalized)
    assert(!DeferredResultType.pending(value.input))
    assert(value.result.operation.contains("shape_index"))
    assert(value.result.operands == Vector(value.input, 0))
    assert(value.result.resultDescriptor.contains(KernelTypeDescriptor("SInt", Vector(2))))
    val record = indices(value.session)(value.reference)
    assert(call(record, "reference") == value.reference)
    assert(call(record, "indices") == Vector(0))
    assert(indices(value.session).size == expectedIndices)
    assert(call(origins(value.session)(value.slot), "operands") == value.result.operands)
    assert(field(value.session, "collectionRegions").asInstanceOf[mutable.Map[Any, Any]].isEmpty)
    assert(field(value.session, "constructorFailure") == None)

  private def begin(root: C2aRoot): Unit =
    call(last.get.session, "beginIncrementalBoundaryClosure", root, Int.box(65536))

  private def emptyDrain(value: Reservation): Unit =
    val owner = closure(value.session).get
    val snapshot = field(owner, "currentSnapshot").asInstanceOf[Option[AnyRef]].get
    val record = indices(value.session)(value.reference)
    val capture = origins(value.session)(value.slot)
    val oldRemaining = remaining(owner)
    call(value.session, "drainIncrementalBoundaryClosure")
    call(value.session, "drainIncrementalBoundaryClosure")
    assert(closure(value.session).get eq owner)
    assert(field(owner, "currentSnapshot").asInstanceOf[Option[AnyRef]].get eq snapshot)
    assert(indices(value.session)(value.reference) eq record)
    assert(origins(value.session)(value.slot) eq capture)
    assert(remaining(owner) >= 0L && remaining(owner) < oldRemaining)
    completed(value, 1)
    clean(value.session)

  private def poisoned(value: Reservation): AnyRef =
    val owner = closure(value.session).get
    assert(field(owner, "poisoned") == java.lang.Boolean.TRUE)
    assert(field(value.session, "constructorFailure").asInstanceOf[Option[?]].nonEmpty)
    reject("poisoned")(call(value.session, "currentBoundaryEpochQuery"))
    clean(value.session)
    owner

  def exercise(root: C2aRoot): Unit =
    val value = last.get
    assert(root.result eq value.result)
    assert(root.input eq value.input)
    root.unusedInput.foreach: alias =>
      assert(alias.constructionFinalized && !alias.typeFinalized)
      assert(alias.operation.contains("digital_alias"))
      assert(alias.materializationIntent.contains(KernelDigitalIntent.Named("unused_input")))
      assert(alias.operands == Vector(root.input))
      assert(alias.typeIntent.exists(_.dependencies == Vector(root.input)))
      assert(!value.result.constructionDependencies.contains(alias))
    root.unused.foreach: unused =>
      val alias = root.unusedInput.get
      assert(unused.constructionFinalized && !unused.typeFinalized)
      assert(unused.operands == Vector(alias, 0))
      assert(unused.typeIntent.exists(_.dependencies == Vector(alias)))
      val record = indices(value.session)(identities(value.session).get(unused))
      assert(call(record, "input") == identities(value.session).get(alias))
      assert(call(record, "indices") == Vector(0))
      assert(!value.result.constructionDependencies.contains(unused))
    mode match
      case "explicit" =>
        begin(root)
        completed(value, 1)
        emptyDrain(value)
      case "inert" =>
        begin(root)
        completed(value, 1)
        val ordinary = CandidateRuntime.shapeIndex(root.input, Vector(0))
          .asInstanceOf[KernelExpr[SInt]]
        val reference = identities(value.session).get(ordinary)
        assert(reference != value.reference)
        assert(ordinary.constructionFinalized && ordinary.typeFinalized)
        assert(ordinary.constructionKind.isEmpty && ordinary.constructionDependencies.isEmpty)
        assert(ordinary.typeIntent.isEmpty && ordinary.operation.contains("shape_index"))
        assert(ordinary.operands == Vector(root.input, 0))
        assert(!indices(value.session).contains(reference))
        call(value.session, "drainIncrementalBoundaryClosure")
        completed(value, 1)
        assert(!indices(value.session).contains(reference))
        ordinaryIndex = Some(ordinary -> reference)
      case "late" =>
        begin(root)
        completed(value, 1)
        val previousIds = identities(value.session).size()
        val previousOrigins = origins(value.session).size
        reject("open ordinary root Module context"):
          call(value.session, "reserveReductionConstructionIntent", root.input, body(), kind)
        assert(identities(value.session).size() == previousIds)
        assert(origins(value.session).size == previousOrigins)
        assert(field(value.session, "constructorFailure").asInstanceOf[Option[?]].nonEmpty)
        unchangedIdentity(value)
        caughtLate = true
      case "duplicate" =>
        val originalRef = identities(value.session).get(root.unused.get)
        val actualRecord = indices(value.session)(originalRef)
        assert(call(actualRecord, "reference") == originalRef)
        assert(call(actualRecord, "input") == identities(value.session).get(root.unusedInput.get))
        assert(call(actualRecord, "indices") == Vector(0))
        assert(!indices(value.session).contains(value.reference))
        // Deliberate registry corruption, using an existing canonical record, never a fake schema.
        // The duplicate key is the negative stimulus; no successful snapshot may contain it.
        indices(value.session).update(value.reference, actualRecord)
        val failure = reject("shape index may register only once")(begin(root))
        assert(failure.getStackTrace.exists(_.getMethodName == "registerShapeIndex"))
        assert(failure.getStackTrace.exists(_.getMethodName == "completeReductionSingleton"))
        val sealedPayload = payload(value.result).get
        assert(sealedPayload.operation.contains("shape_index"))
        assert(sealedPayload.operands == Vector(root.input, 0))
        assert(!value.result.constructionFinalized && !value.result.typeFinalized)
        reject("not complete")(value.result.operation)
        unchangedIdentity(value)
        assert(indices(value.session)(value.reference) eq actualRecord)
        assert(origins(value.session)(value.slot) eq value.capture)
        val _ = poisoned(value)
        caughtPostSeal = true
      case _ => ()

  private def prepare(
      nextMode: String,
      nextKind: String = "reduce_left",
      nextCount: Int = 1
  ): Unit =
    mode = nextMode
    kind = nextKind
    count = nextCount
    callbackCalls = 0
    caughtPostSeal = false
    caughtLate = false
    caughtScoped = false
    ordinaryIndex = None
    last = None

  private def published(
      snapshot: ConstructionSnapshot,
      unused: Boolean = false,
      scheduled: Boolean = true
  ): AnyRef =
    val value = last.get
    completed(value, if unused then 2 else 1)
    val owner = closure(value.session).get
    assert(field(owner, "incremental") == java.lang.Boolean.TRUE)
    assert(field(owner, "poisoned") == java.lang.Boolean.FALSE)
    assert(remaining(owner) >= 0L && remaining(owner) < 65536L)
    val verified = field(owner, "verified").asInstanceOf[mutable.Map[(String, Any), AnyRef]]
    assert(verified.get("construction" -> value.reference).exists(_ eq value.result) == scheduled)
    val path = call(value.session, "expressionPath", value.reference).toString
    val record = snapshot.shapeIndices.find(_.path == path).get
    assert(record.indices == Vector("0") && record.resultDimensions.isEmpty)
    assert(snapshot.shapeIndices.size == (if unused then 2 else 1))
    assert(snapshot.digitalCollections.isEmpty)
    assert(snapshot.sourceMap.exists(entry =>
      entry.semanticPath == path && entry.source == value.source
    ))
    if unused then
      val expressions = field(value.session, "expressionValues")
        .asInstanceOf[mutable.Map[ExpressionRef, KernelExpr[?]]]
      val aliases =
        expressions.valuesIterator.filter(_.operation.contains("digital_alias")).toVector
      val derived = expressions.valuesIterator.filter(expression =>
        (expression ne value.result) && expression.operation.contains("shape_index")
      ).toVector
      assert(aliases.size == 1 && derived.size == 1)
      val alias = aliases.head
      val projection = derived.head
      assert(alias.materializationIntent.contains(KernelDigitalIntent.Named("unused_input")))
      assert(alias.operands == Vector(value.input))
      assert(alias.typeIntent.exists(_.dependencies == Vector(value.input)))
      assert(projection.operands == Vector(alias, 0))
      assert(projection.typeIntent.exists(_.dependencies == Vector(alias)))
      val aliasRef = identities(value.session).get(alias)
      val projectionRef = identities(value.session).get(projection)
      assert(call(indices(value.session)(projectionRef), "input") == aliasRef)
      assert(call(indices(value.session)(projectionRef), "reference") == projectionRef)
      assert(call(indices(value.session)(projectionRef), "indices") == Vector(0))
      val aliasPath = call(value.session, "expressionPath", aliasRef).toString
      val projectionPath = call(value.session, "expressionPath", projectionRef).toString
      assert(snapshot.shapeIndices.find(_.path == projectionPath).exists(_.input == aliasPath))
      assert(snapshot.digitalExpressions.exists(expression =>
        expression.path == aliasPath && expression.operation == "digital_alias"
      ))
      val state = field(owner, "typeState")
        .asInstanceOf[java.util.IdentityHashMap[KernelExpr[?], java.lang.Integer]]
      val frozen = field(owner, "frozenExpressions")
        .asInstanceOf[mutable.Map[ExpressionRef, Product]]
      Vector(alias, projection).foreach: expression =>
        val reference = identities(value.session).get(expression)
        assert(expression.typeFinalized)
        assert(expressions(reference) eq expression)
        assert(!value.result.operands.contains(expression))
        assert(!value.result.constructionDependencies.contains(expression))
        assert(state.get(expression) == Int.box(2))
        assert(frozen(reference).productElement(0).asInstanceOf[AnyRef] eq expression)
    clean(value.session)
    assert(call(ConstructionKernel, "active") == None)
    owner

  def main(args: Array[String]): Unit =
    if args.sameElements(Array("assignment-fees")) then
      prepare("assignment")
      val snapshot = C2aFactory.assigned()
      val owner = published(snapshot)
      assert(snapshot.modules.flatMap(_.domains).isEmpty)
      assert(snapshot.digitalAssignments.size == 3)
      assert(!field(last.get.session, "boundDomainAccountingActive").asInstanceOf[Boolean])
      println(
        s"PASS domain-free actual assignment fee parity remaining=${remaining(owner)} assignments=${snapshot.digitalAssignments.size}"
      )
      return
    assert(args.isEmpty, "this focused harness always executes every named case")
    var checks = 0
    def check(name: String)(run: => Unit): Unit =
      run
      checks += 1
      println("PASS " + name)

    for reductionKind <- Vector("reduce_left", "reduce_balanced") do
      check(s"$reductionKind automatic selected singleton, payload gate and original identity"):
        prepare("auto", reductionKind)
        val _ = published(C2aFactory.capture())
      check(s"$reductionKind explicit drain and two empty drains are idempotent"):
        prepare("explicit", reductionKind)
        val _ = published(C2aFactory.capture())

    check("ordinary finalized selected index remains inert without construction admission"):
      prepare("inert")
      val snapshot = C2aFactory.capture()
      val _ = published(snapshot)
      val (ordinary, reference) = ordinaryIndex.get
      assert(identities(last.get.session).get(ordinary) == reference)
      assert(ordinary.constructionKind.isEmpty && ordinary.typeFinalized)
      assert(!indices(last.get.session).contains(reference))
      val path = call(last.get.session, "expressionPath", reference).toString
      assert(!snapshot.shapeIndices.exists(_.path == path))

    check("unused actual derived input expression remains a finalized retained proof"):
      prepare("unused")
      val _ = published(C2aFactory.capture(), unused = true)

    check("manually completed registered singleton initializes its real domain before publication"):
      prepare("manual")
      val snapshot = C2aFactory.manual()
      val owner = published(snapshot, scheduled = false)
      val domains = snapshot.modules.flatMap(_.domains)
      assert(domains.size == 1 && domains.head.kind == "external")
      val declaration = snapshot.modules.flatMap(_.declarations).find(_.name == "input").get
      assert(declaration.domain.contains(domains.head.path))
      assert(declaration.resolvedDomain.contains(domains.head.path))
      assert(field(owner, "resolved").asInstanceOf[Map[Any, String]].values.toSet ==
        Set(domains.head.path))

    check(
      "live ClockDomain drain refuses before sealing, poisons, and permits fresh-domain recovery"
    ):
      prepare("scoped")
      reject("captured constructor failure")(C2aFactory.manual())
      assert(caughtScoped && callbackCalls == 0)
      val failed = last.get
      val failedOwner = poisoned(failed)
      assert(field(failed.session, "domainStack").asInstanceOf[mutable.ArrayBuffer[?]].isEmpty)
      assert(payload(failed.result).isEmpty && !indices(failed.session).contains(failed.reference))
      prepare("manual")
      val snapshot = C2aFactory.manual()
      val owner = published(snapshot, scheduled = false)
      assert(last.get.session ne failed.session)
      assert(owner ne failedOwner)
      assert(field(failedOwner, "poisoned") == java.lang.Boolean.TRUE)
      assert(snapshot.modules.flatMap(_.domains).size == 1)
      assert(field(owner, "resolved").asInstanceOf[Map[Any, String]].nonEmpty)

    check(
      "normal output assignment consumes the registered pending result and publishes its identity"
    ):
      prepare("assignment")
      val snapshot = C2aFactory.assigned()
      val _ = published(snapshot)
      val assignments = snapshot.digitalAssignments
      assert(assignments.size == 3)
      val path = call(last.get.session, "expressionPath", last.get.reference).toString
      val consumers = assignments.filter(_.value == path)
      assert(consumers.size == 1)
      val assignment = consumers.head
      val declarations = snapshot.modules.flatMap(_.declarations)
      assert(declarations.exists(declaration =>
        declaration.path == assignment.target && declaration.name == "output" &&
          declaration.kind == "output" && declaration.dataType.contains("SInt(2)")
      ))
      val selectedInput = snapshot.shapeIndices.find(_.path == path).get.input
      val selected = declarations.find(_.path == selectedInput).get.instancePort.get
      assert(selected.direction == "output" && selected.selection.isEmpty &&
        selected.region.isEmpty)
      val childData = declarations.find(_.path == selected.port).get
      assert(childData.name == "data" && childData.kind == "output")
      val childDrivers = assignments.filter(_.target == childData.path)
      assert(childDrivers.size == 1)
      val childDriver = childDrivers.head
      val childSource = declarations.find(_.path == childDriver.value).get
      assert(childSource.name == "source" && childSource.kind == "input" &&
        childSource.instancePort.isEmpty)
      val parentPorts = declarations.filter(_.instancePort.exists(port =>
        port.instance == selected.instance && port.port == childSource.path &&
          port.direction == "input" && port.selection.isEmpty && port.region.isEmpty
      ))
      assert(parentPorts.size == 1)
      val parentDrivers = assignments.filter(_.target == parentPorts.head.path)
      assert(parentDrivers.size == 1)
      val parentDriver = parentDrivers.head
      val driver = declarations.find(_.path == parentDriver.value).get
      assert(driver.name == "driver" && driver.kind == "input" && driver.instancePort.isEmpty)
      assert(driver.dataType.nonEmpty &&
        Vector(childData, childSource, parentPorts.head).forall(_.dataType == driver.dataType))
      assert(parentDriver.owner == assignment.owner && childDriver.owner != assignment.owner)
      assert(assignments.map(_.path).toSet ==
        Set(assignment.path, childDriver.path, parentDriver.path))

    check("old unregistered reserve refuses publication without inventing an intent"):
      prepare("orphan")
      reject("unsealed construction")(C2aFactory.capture())
      val value = last.get
      unchangedIdentity(value)
      assert(closure(value.session).isEmpty && payload(value.result).isEmpty)
      assert(!value.result.constructionFinalized)
      clean(value.session)

    for reductionKind <- Vector("reduce_left", "reduce_balanced") do
      check(s"$reductionKind nonsingleton refuses before callback or payload sealing"):
        prepare("auto", reductionKind, nextCount = 2)
        reject("exact count one")(C2aFactory.capture())
        val value = last.get
        unchangedIdentity(value)
        assert(payload(value.result).isEmpty && !value.result.constructionFinalized)
        assert(!indices(value.session).contains(value.reference))
        val _ = poisoned(value)

    check(
      "reservation after closure admission is rejected before new identity or source allocation"
    ):
      prepare("late")
      reject("captured constructor failure")(C2aFactory.capture())
      assert(caughtLate && callbackCalls == 0)
      clean(last.get.session)

    var failedSession: AnyRef = null
    var failedOwner: AnyRef = null
    check("real duplicate shape-index key fails after sealing, poisons and refuses publication"):
      prepare("duplicate")
      reject("captured constructor failure")(C2aFactory.capture())
      assert(caughtPostSeal && callbackCalls == 0)
      failedSession = last.get.session
      failedOwner = poisoned(last.get)

    check("fresh same-JVM session publishes after the post-seal transaction was poisoned"):
      prepare("auto")
      val owner = published(C2aFactory.capture())
      assert(last.get.session ne failedSession)
      assert(owner ne failedOwner)
      assert(field(failedOwner, "poisoned") == java.lang.Boolean.TRUE)

    assert(checks == 15)
    println(s"PASS $checks C2a singleton construction controls; callbacks=$callbackCalls")
