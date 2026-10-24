package nodal.internal.testkit

import nodal.*
import scala.collection.mutable.ArrayBuffer

/** Counting-callback comparisons are resolver diagnostics, not fixed-budget Session acceptance. */
object NamingControls:
  var childModule: Module = null
  private var rootModule: Module = null
  private var instanceValue: AnyRef = null
  private var actualBuilder: SemanticOriginBuilder = null
  private var failedSession: AnyRef = null
  private var caught = false
  private var calls = 0

  private def field(target: AnyRef, suffix: String): AnyRef =
    val fields = target.getClass.getDeclaredFields.filter(_.getName.endsWith(suffix))
    assert(fields.length == 1, suffix)
    fields.head.setAccessible(true)
    fields.head.get(target)

  private def call(target: AnyRef, name: String, args: AnyRef*): AnyRef =
    val methods = target.getClass.getDeclaredMethods.toVector ++ target.getClass.getMethods
    val method = methods.find(m =>
      (m.getName == name || m.getName.endsWith("$$" + name)) && m.getParameterCount == args.size
    ).get
    method.setAccessible(true)
    try method.invoke(target, args*)
    catch case error: java.lang.reflect.InvocationTargetException => throw error.getCause

  private def active(): AnyRef = call(ConstructionKernel, "active").asInstanceOf[Option[AnyRef]].get
  private def off(builder: SemanticOriginBuilder): Unit =
    assert(field(builder, "canonicalAccounting") == java.lang.Boolean.FALSE)
    assert(field(builder, "resolutionWork") == None)
    assert(field(builder, "resolutionSourceLines") == None)

  private def reject(text: String)(body: => Any): Throwable =
    val error = scala.util.Try(body).failed.get
    assert(Option(error.getMessage).exists(_.contains(text)), error.toString)
    error

  def retain(root: Module, instance: AnyRef): Unit =
    rootModule = root
    instanceValue = instance
    actualBuilder = field(active(), "semanticOrigin").asInstanceOf[SemanticOriginBuilder]

  private def compare(builder: SemanticOriginBuilder): SemanticOriginResult =
    val oldCharges = ArrayBuffer.empty[Int]
    val newCharges = ArrayBuffer.empty[Int]
    val old = builder.resolveWithWork(n => { oldCharges += n; () })
    off(builder)
    val current = builder.resolveConstructionWithWork(n => { newCharges += n; () })
    off(builder)
    assert(current == old, "all raw maps/names/origins/generatedNames/sourceMap must match")
    assert(oldCharges.nonEmpty && newCharges.nonEmpty)
    println(
      s"diagnostic work old=${oldCharges.map(_.toLong).sum} exact=${newCharges.map(_.toLong).sum}"
    )
    current

  // These are canonical resolver records with identity-only values, not typed Session hardware.
  // Every capture obtains its actual source site through the existing capture owner.
  private def add(builder: SemanticOriginBuilder, index: Int, value: AnyRef, operands: Vector[Any])
      : Unit =
    builder.captureExpression(0L, index, value, operands)

  private def fixture(): SemanticOriginBuilder =
    val builder = new SemanticOriginBuilder
    builder.captureModule(0L, rootModule, "NamingParityRoot", None)
    builder.captureModule(1L, childModule, "NamingParityLeaf", Some(0L))
    builder.captureInstance(0L, 0, 1L, instanceValue, childModule)
    val left = new Object
    val right = new Object
    builder.captureDeclaration(0L, 0, left, "input", Some("overlap"))
    builder.captureDeclaration(0L, 1, right, "input", Some("overlap"))
    builder.captureDeclaration(1L, 0, new Object, "input", Some("overlap"))
    add(builder, 0, new Object, Vector.fill(96)(left) ++ Vector.fill(32)(right))
    val explicit = new Object
    val sink = new Object
    add(builder, 1, explicit, Vector(left, right, "explicit_expression", "named"))
    add(builder, 2, sink, Vector(left))
    builder.captureOperation(0L, "assignment", Vector(left, sink))
    builder.captureOperation(0L, "assignment", Vector(right, explicit))
    val captures = field(builder, "expressions").asInstanceOf[ArrayBuffer[AnyRef]]
    val operands = call(captures.head, "operands").asInstanceOf[Vector[Any]]
    val records = Vector("modules", "instances", "declarations", "expressions", "operations")
      .map(name => field(builder, name).asInstanceOf[ArrayBuffer[?]].size).sum
    assert(operands.size == 128 && operands.size > records)
    builder

  private def nesting(): Unit =
    val builder = new SemanticOriginBuilder
    var nested = false
    val inner = ArrayBuffer.empty[Int]
    builder.resolveConstructionWithWork: _ =>
      if !nested then
        nested = true
        assert(field(builder, "canonicalAccounting") == java.lang.Boolean.TRUE)
        val outerWork = field(builder, "resolutionWork")
        val outerCache = field(builder, "resolutionSourceLines")
        builder.resolveWithWork(n =>
          assert(field(builder, "canonicalAccounting") == java.lang.Boolean.FALSE)
          inner += n
          ()
        )
        assert(field(builder, "canonicalAccounting") == java.lang.Boolean.TRUE)
        assert(field(builder, "resolutionWork") eq outerWork)
        assert(field(builder, "resolutionSourceLines") eq outerCache)
    assert(nested && inner.toVector == Vector(128, 64))
    off(builder)
    val sentinel = new IllegalArgumentException("canonical scan sentinel")
    var charged = 0
    val error = scala.util.Try(builder.resolveConstructionWithWork(_ =>
      charged += 1
      throw sentinel
    )).failed.get
    assert((error eq sentinel) && charged == 1)
    off(builder)
    assert(builder.resolve() == builder.resolveConstructionWithWork(_ => ()))

  def poison(root: Module, source: Expr[Vec[SInt]]): Unit =
    val session = active()
    assert(session ne failedSession)
    val body = DigitalCollectionCallback.reductionBody(DigitalCollectionCallback.checked2(
      (left: Expr[SInt], _: Expr[SInt]) =>
        calls += 1
        left
    ))
    val result = call(session, "reserveReductionConstructionIntent", source, body, "reduce_left")
      .asInstanceOf[KernelExpr[Data]]
    call(session, "completeReductionSingleton", result, source, Vector(0))
    call(session, "beginIncrementalBoundaryClosure", root, Int.box(65536))
    call(session, "drainIncrementalBoundaryClosure")
    val owner = field(session, "boundaryClosureOwner").asInstanceOf[Option[AnyRef]].get
    val query = call(session, "currentBoundaryEpochQuery")
      .asInstanceOf[StructuralParameterBinding.Query]
    assert(query.checkedLiteral("NamingPoisonRoot", "3") == 3L)
    val builder = field(session, "semanticOrigin").asInstanceOf[SemanticOriginBuilder]
    def remaining = field(owner, "remaining").asInstanceOf[Long]
    var atScan = false
    val error = reject("boundary closure work budget exhausted"):
      builder.resolveConstructionWithWork: units =>
        val scan =
          Thread.currentThread.getStackTrace.exists(_.getMethodName.contains("discoverMemberNames"))
        if scan && !atScan then
          assert(remaining > 0L && units > 0)
          atScan = true
          call(owner, "charge", Long.box(remaining))
          assert(remaining == 0L)
        call(owner, "charge", Long.box(units.toLong))
        ()
    assert(atScan && error.getStackTrace.exists(_.getMethodName.contains("discoverMemberNames")))
    assert(error.getStackTrace.exists(_.getMethodName.contains("canonicalVisit")))
    off(builder)
    assert(field(owner, "poisoned") == java.lang.Boolean.TRUE && remaining == 0L)
    assert(field(session, "constructorFailure").asInstanceOf[Option[?]].nonEmpty)
    reject("stale boundary snapshot epoch")(query.checkedLiteral("NamingPoisonRoot", "3"))
    reject("poisoned")(call(session, "currentBoundaryEpochQuery"))
    assert(calls == 0)
    failedSession = session
    caught = true

  def main(args: Array[String]): Unit =
    ConstructionKernel.inspect(new NamingParityRoot)
    val real = compare(actualBuilder)
    assert(real.modulePaths.size == 2 && real.names.nonEmpty && real.origins.nonEmpty)
    assert(real.names.exists(_.provenance == "explicit"))
    assert(real.names.exists(_.provenance == "shaped-view"))
    assert(real.names.exists(_.provenance == "scala-declaration"))
    println("PASS naming real hierarchy raw result parity")
    val unit = compare(fixture())
    assert(unit.declarationNames(0L -> 0) != unit.declarationNames(0L -> 1))
    assert(unit.declarationNames.values.count(_.startsWith("overlap_")) >= 1)
    assert(unit.declarationNames(1L -> 0) == "overlap")
    assert(unit.expressionNames(0L -> 1) == "explicit_expression")
    assert(unit.names.find(_.semanticPath == unit.expressionPaths(0L -> 1)).get.provenance ==
      "explicit")
    assert(unit.names.find(_.semanticPath == unit.expressionPaths(0L -> 2)).get.provenance ==
      "sink-affinity")
    assert(unit.generatedNames.exists(_.category == "temporary"))
    val parent = unit.origins.find(_.semanticPath == unit.expressionPaths(0L -> 0)).get
    assert(parent.parents.size == 2 && parent.parents.distinct == parent.parents)
    println("PASS naming collision generated temporary shared oversized operands raw parity")
    nesting()
    println("PASS naming nested legacy mode and entry sentinel scope restoration")
    reject("captured constructor failure")(ConstructionKernel.inspect(new NamingPoisonRoot))
    assert(caught && calls == 0)
    println("PASS naming actual Session canonical scan exhaustion poison and stale ticket")
    ConstructionKernel.inspect(new NamingRecoveryRoot)
    println("PASS naming fresh ordinary recovery")
