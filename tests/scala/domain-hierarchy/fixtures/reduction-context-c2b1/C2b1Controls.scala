package nodal.internal.testkit

import nodal.*
import scala.collection.mutable

/** Source-only focused C2b.1 controls. Reflection is diagnostic/corruption-only, not API admission.
  * Every budget change uses the real owner's monotone charge; no allowance is reset or refunded.
  */
object C2b1Controls:
  private var mode = "root_frontier_identity"
  private var callbacks = 0
  private var caught = false
  private var previous: Option[ConstructionRequestContext] = None

  private def call(target: AnyRef, name: String, args: AnyRef*): AnyRef =
    val method = (target.getClass.getMethods.toVector ++ target.getClass.getDeclaredMethods)
      .find(m =>
        (m.getName == name || m.getName.endsWith("$$" + name)) &&
          m.getParameterCount == args.size
      )
      .getOrElse(throw new AssertionError(s"missing ${target.getClass.getName}.$name"))
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
    assert(Option(failure.getMessage).exists(_.contains(fragment)), failure.toString)
    failure
  private def active(): AnyRef = call(ConstructionKernel, "active").asInstanceOf[Option[AnyRef]].get
  private def serial(session: AnyRef): Long = field(session, "constructionAllocationSerial")
    .asInstanceOf[Long]
  private def work(session: AnyRef): Long = field(session, "constructionRequestWork")
    .asInstanceOf[Long]
  private def remaining(owner: AnyRef): Long = field(owner, "remaining").asInstanceOf[Long]
  private def captures(session: AnyRef): mutable.ArrayBuffer[AnyRef] =
    field(field(session, "semanticOrigin"), "expressions").asInstanceOf[mutable.ArrayBuffer[AnyRef]]
  private def expressions(session: AnyRef): java.util.IdentityHashMap[AnyRef, ExpressionRef] =
    field(session, "expressionIds").asInstanceOf[java.util.IdentityHashMap[AnyRef, ExpressionRef]]
  private def declarations(session: AnyRef): java.util.IdentityHashMap[AnyRef, DeclarationRef] =
    field(session, "declarationIds").asInstanceOf[java.util.IdentityHashMap[AnyRef, DeclarationRef]]
  private def validate(session: AnyRef, result: KernelExpr[Data], input: Expr[?]): Unit =
    assert(call(session, "validateConstructionRequest", result, input) == Boolean.box(true))
  private def complete(session: AnyRef, result: KernelExpr[Data], input: Expr[?]): Unit =
    assert(call(session, "completeReductionSingleton", result, input, Vector(0)) eq result)

  def reserve(input: Expr[Vec[SInt]]): KernelExpr[Data] =
    val body = DigitalCollectionCallback.reductionBody(DigitalCollectionCallback.checked2(
      (left: Expr[SInt], _: Expr[SInt]) =>
        callbacks += 1
        left
    ))
    call(active(), "reserveReductionConstructionIntent", input, body, "reduce_left")
      .asInstanceOf[KernelExpr[Data]]

  def exercise(root: Module, input: Expr[Vec[SInt]], result: KernelExpr[Data]): Unit =
    val session = active()
    val request = result.constructionRequest.get
    assert(request.session eq session)
    assert(request.input eq input)
    assert(request.inputReference eq declarations(session).get(input))
    assert(request.resultReference eq expressions(session).get(result))
    assert(result.constructionDependencies.head.asInstanceOf[AnyRef] eq input)
    assert(request.sourceSlot == result.constructionSourceSlot)
    assert(request.roots.exists(r => (r.value eq input) && (r.reference eq request.inputReference)))
    assert(request.roots.forall(_.reference.constructionAllocation.get.serial <= request.frontier))
    val allocation = request.resultReference.constructionAllocation.get
    assert((allocation.session eq session) && (allocation.module eq request.module))
    assert(request.frontier < allocation.serial && serial(session) >= allocation.serial)
    val savedCapture = captures(session)(request.sourceSlot)
    val count = captures(session).size
    val beforeSerial = serial(session)
    mode match
      case "root_frontier_identity" =>
        validate(session, result, input)
        assert(serial(session) == beforeSerial)
      case "allocation_reuse" => reuse(session, input, request)
      case "root_walk_shared_deep" => walk(session, input, result, cyclic = false)
      case "root_walk_seq_option_cycle" => walk(session, input, result, cyclic = true)
      case "root_walk_expression_cycle" =>
        val saved = result.constructionDependencies
        val before = work(session)
        try
          set(result, "constructionDependencies", Vector(result))
          reject("construction input dependency cycle"):
            call(session, "requestRoots", result, Long.box(serial(session)))
          assert(work(session) > before && !result.constructionFinalized)
          // collectionFailure poisons the actual Session, even for this private walk probe.
          assert(field(session, "constructorFailure").asInstanceOf[Option[?]].nonEmpty)
          caught = true
        finally set(result, "constructionDependencies", saved)
      case "install_failure" => installFailure(root, session, input, result)
      case other if other.startsWith("tamper_") => tamper(session, input, result, other)
      case other => throw new AssertionError(other)
    if !caught then
      complete(session, result, input)
      assert(result.operation.contains("shape_index") && result.operands == Vector(input, 0))
      assert(result.typeFinalized && result.constructionFinalized)
      assert(expressions(session).get(result) eq request.resultReference)
      assert(result.constructionRequest.get eq request)
      assert(result.constructionSourceSlot == request.sourceSlot)
      val current = captures(session)(request.sourceSlot)
      assert(call(current, "value") eq result)
      assert(call(current, "site") == call(savedCapture, "site"))
      assert(call(current, "index") == call(savedCapture, "index"))
      assert(call(current, "module") == call(savedCapture, "module"))
      val indices = field(session, "shapeIndices").asInstanceOf[mutable.Map[ExpressionRef, AnyRef]]
      assert(call(indices(request.resultReference), "indices") == Vector(0))
      if mode != "allocation_reuse" then assert(captures(session).size == count)
      call(session, "beginIncrementalBoundaryClosure", root, Int.box(65536))
      val shapeCount = indices.size
      val captureCount = captures(session).size
      val afterSerial = serial(session)
      call(session, "drainIncrementalBoundaryClosure")
      assert(indices.size == shapeCount && captures(session).size == captureCount)
      assert(serial(session) == afterSerial)
      assert(callbacks == 0)
      previous = Some(request)

  private def reuse(session: AnyRef, input: Expr[Vec[SInt]], request: ConstructionRequestContext)
      : Unit =
    val reference = declarations(session).get(input)
    val stamp = reference.constructionAllocation.get
    val before = serial(session)
    val bound = call(
      session,
      "bindCollectionValue",
      input,
      None,
      Long.box(reference.module),
      Boolean.box(true),
      Boolean.box(false),
      Boolean.box(false)
    )
    assert(bound eq input)
    assert((declarations(session).get(input) eq reference) && serial(session) == before)
    assert(reference.constructionAllocation.get eq stamp)
    val record = request.module.declarations(reference.index)
    assert(record.copy().reference eq reference)
    assert(record.copy().reference.constructionAllocation.get eq stamp)
    reject("only once")(reference.stampConstructionAllocation(stamp))
    assert(serial(session) == before)
    val alias = input.named("c2b1_alias").asInstanceOf[KernelExpr[Vec[SInt]]]
    val aliasRef = expressions(session).get(alias)
    val aliasStamp = aliasRef.constructionAllocation.get
    assert(aliasStamp.serial > before && serial(session) == before + 1L)
    assert(call(session, "requestReference", alias) eq aliasRef)
    assert(aliasRef.constructionAllocation.get eq aliasStamp)
    assert(serial(session) == before + 1L)

  private def walk(session: AnyRef, input: Expr[?], result: KernelExpr[Data], cyclic: Boolean)
      : Unit =
    val original = result.constructionDependencies
    def roots(dependencies: Vector[Any]): (Vector[ConstructionRootIdentity], Long) =
      set(result, "constructionDependencies", dependencies)
      val before = work(session)
      val found = call(session, "requestRoots", result, Long.box(serial(session)))
        .asInstanceOf[Vector[ConstructionRootIdentity]]
      assert(work(session) > before)
      found -> (work(session) - before)
    try
      val (single, oneCost) = roots(Vector(input))
      val shared = Vector(Some(input), input)
      val (many, manyCost) = roots(Vector(shared, shared))
      assert(single.map(_.reference) == many.map(_.reference) && manyCost > oneCost)
      // 256 Seq-plus-Option layers exceed the old depth128 restriction without consuming
      // the owner's unrelated snapshot/publication budget as a stress benchmark.
      var nested: Any = input
      var depth = 0
      while depth < 256 do
        nested = Vector(Some(nested))
        depth += 1
      val (deep, deepCost) = roots(Vector(nested))
      assert(deep.map(_.reference) == single.map(_.reference) && deepCost > manyCost)
      if cyclic then
        // Canonical Scala immutable Seq interface, with an actual Seq -> Option -> Seq cycle.
        // A mutable.ArrayBuffer is deliberately not used: Scala 3 unqualified Seq is immutable.
        val cycle = new scala.collection.immutable.AbstractSeq[Any]:
          def length: Int = 1
          def apply(index: Int): Any =
            if index != 0 then throw new IndexOutOfBoundsException(index.toString)
            Some(this)
          override def iterator: Iterator[Any] = Iterator.single(Some(this))
        set(result, "constructionDependencies", Vector(cycle))
        val before = work(session)
        reject("construction input dependency cycle"):
          call(session, "requestRoots", result, Long.box(serial(session)))
        assert(work(session) > before && !result.constructionFinalized)
        // Restoring metadata does not clear collectionFailure's captured constructor failure.
        assert(field(session, "constructorFailure").asInstanceOf[Option[?]].nonEmpty)
        caught = true
    finally set(result, "constructionDependencies", original)

  private def tamper(
      session: AnyRef,
      input: Expr[Vec[SInt]],
      result: KernelExpr[Data],
      kind: String
  )
      : Unit =
    val request = result.constructionRequest.get
    val reference = declarations(session).get(input)
    val slot = result.constructionSourceSlot
    var testInput: Expr[?] = input
    val expected = kind match
      case "tamper_input" =>
        testInput = input.named("wrong_input")
        "lost its original request context"
      case "tamper_equal_reference" =>
        val equal = reference.copy()
        assert(equal == reference && (equal ne reference))
        declarations(session).put(input, equal)
        "lost its original request context"
      case "tamper_foreign_session" =>
        set(result, "request", Some(request.copy(session = previous.get.session)))
        "lost its original request context"
      case "tamper_foreign_module" =>
        set(result, "request", Some(request.copy(module = previous.get.module)))
        "lost its original request context"
      case "tamper_source_slot" =>
        set(result, "reservedSourceSlot", Some(slot + 1))
        "lost its original request context"
      case "tamper_late_root" =>
        val late = input.named("late_root").asInstanceOf[KernelExpr[Vec[SInt]]]
        val ref = expressions(session).get(late)
        assert(ref.constructionAllocation.get.serial > request.frontier)
        set(
          result,
          "request",
          Some(request.copy(roots =
            request.roots :+
              ConstructionRootIdentity(late, ref)
          ))
        )
        "construction input is later or belongs to another session"
      case other => throw new AssertionError(other)
    try
      reject(expected)(complete(session, result, testInput))
      assert(!result.constructionFinalized && callbacks == 0)
      assert(field(session, "constructorFailure").asInstanceOf[Option[?]].nonEmpty)
      caught = true
    finally
      declarations(session).put(input, reference)
      set(result, "request", Some(request))
      set(result, "reservedSourceSlot", Some(slot))

  private def installFailure(
      root: Module,
      session: AnyRef,
      input: Expr[?],
      result: KernelExpr[Data]
  )
      : Unit =
    val owner = call(session, "enableIncrementalBoundaryClosure", root, Int.box(65536))
    call(owner, "prepareIncrementalInputs")
    call(owner, "refresh")
    val query = call(session, "currentBoundaryEpochQuery")
      .asInstanceOf[StructuralParameterBinding.Query]
    assert(query.checkedLiteral("C2b1Root", "3") == 3L)
    val epoch = field(owner, "epoch").asInstanceOf[Long]
    val stacks = Vector("moduleStack", "collectionStack", "generationStack")
      .map(name => name -> field(session, name).asInstanceOf[mutable.ArrayBuffer[AnyRef]].toVector)
    val untouched = Vector("domainStack", "areaStack", "instanceSelectionStack", "analogStack")
      .map(name => name -> field(session, name).asInstanceOf[mutable.ArrayBuffer[AnyRef]].toVector)
    val sourceCount = captures(session).size
    val allocation = serial(session)
    val reference = expressions(session).get(result)
    val shapeCount = field(session, "shapeIndices").asInstanceOf[mutable.Map[?, ?]].size
    val before = remaining(owner)
    validate(session, result, input)
    val validationCharge = before - remaining(owner)
    assert(validationCharge > 0L)
    // Diagnostic validation is paid for. Leave exactly its measured repeat cost, so the next
    // charge fails in inConstructionRequest AFTER installValueScopeChain has performed mutation.
    assert(remaining(owner) >= validationCharge)
    call(owner, "charge", Long.box(remaining(owner) - validationCharge))
    assert(remaining(owner) == validationCharge)
    val failure = reject("boundary closure work budget exhausted"):
      call(session, "completeReductionSingleton", result, input, Vector(0))
    def at(name: String): Boolean = failure.getStackTrace.exists(frame =>
      frame.getMethodName == name || frame.getMethodName.endsWith("$$" + name)
    )
    assert(at("inConstructionRequest") && at("completeReductionSingleton"))
    assert(!at("validateConstructionRequest") && !at("completeReductionSingletonBody"))
    assert(remaining(owner) == 0L && serial(session) == allocation)
    (stacks ++ untouched).foreach: (name, saved) =>
      val restored = field(session, name).asInstanceOf[mutable.ArrayBuffer[AnyRef]].toVector
      assert(restored.size == saved.size && restored.zip(saved).forall((a, b) => a eq b))
    assert(captures(session).size == sourceCount && (expressions(session).get(result) eq reference))
    assert(field(session, "shapeIndices").asInstanceOf[mutable.Map[?, ?]].size == shapeCount)
    assert(!result.constructionFinalized && !result.pendingConstructionOwnedBy(session))
    assert(field(result, "constructionState") == Int.box(3))
    assert(result.constructionSourceSlot == result.constructionRequest.get.sourceSlot)
    assert(result.constructionRequest.get.resultReference eq reference)
    assert(callbacks == 0 && field(owner, "poisoned") == Boolean.box(true))
    assert(field(owner, "epoch").asInstanceOf[Long] > epoch)
    reject("stale boundary snapshot epoch")(query.checkedLiteral("C2b1Root", "3"))
    reject("poisoned")(call(owner, "verifyPublication"))
    reject("poisoned")(call(session, "drainIncrementalBoundaryClosure"))
    assert(serial(session) == allocation && remaining(owner) == 0L)
    caught = true

  def main(args: Array[String]): Unit =
    assert(args.isEmpty)
    val positive = Vector("root_frontier_identity", "allocation_reuse", "root_walk_shared_deep")
    val negative = Vector(
      "tamper_input",
      "tamper_equal_reference",
      "tamper_foreign_session",
      "tamper_foreign_module",
      "tamper_source_slot",
      "tamper_late_root",
      "root_walk_seq_option_cycle",
      "root_walk_expression_cycle",
      "install_failure"
    )
    positive.foreach: name =>
      mode = name
      caught = false
      ConstructionKernel.inspect(new C2b1Root)
      println(s"PASS C2b.1 $name")
    negative.foreach: name =>
      mode = name
      caught = false
      reject("captured constructor failure")(ConstructionKernel.inspect(new C2b1Root))
      assert(caught && callbacks == 0)
      println(s"PASS C2b.1 $name")
    mode = "root_frontier_identity"
    caught = false
    ConstructionKernel.inspect(new C2b1Root)
    assert(callbacks == 0)
    println("PASS C2b.1 fresh recovery")
