package nodal.internal.testkit

import nodal.*
import scala.collection.mutable

object LifecycleControls:
  var callbackCalls = 0
  def body(): DigitalCollectionCallback.ReductionBody =
    DigitalCollectionCallback.reductionBody(DigitalCollectionCallback.checked2(
      (left: Expr[SInt], _: Expr[SInt]) =>
        callbackCalls += 1
        left
    ))
  def session(): AnyRef =
    val method = ConstructionKernel.getClass.getDeclaredMethod("active")
    method.setAccessible(true)
    method.invoke(ConstructionKernel).asInstanceOf[Option[AnyRef]].get
  def invoke(target: AnyRef, name: String, args: AnyRef*): AnyRef =
    try target.getClass.getMethods.find(_.getName == name).get.invoke(target, args*)
    catch case failure: java.lang.reflect.InvocationTargetException => throw failure.getCause
  def field(target: AnyRef, suffix: String): AnyRef =
    val member = target.getClass.getDeclaredFields.find(_.getName.endsWith(suffix)).get
    member.setAccessible(true)
    member.get(target)
  def reject(message: String)(run: => Any): Unit =
    val error = scala.util.Try(run).failed.get
    assert(error.getMessage.contains(message), error.toString)
  final class Singleton(fail: Boolean = false, leavePending: Boolean = false, count: Int = 1)
      extends Module:
    val input = in(Vec(SInt(2), count))
    val owner = session()
    val result = invoke(owner, "reserveReductionConstruction", input, body())
      .asInstanceOf[KernelExpr[Data]]
    assert(result.constructionCallback.nonEmpty)
    assert(!result.constructionFinalized && !result.typeFinalized)
    reject("not complete")(result.operation)
    val identities = field(owner, "expressionIds")
      .asInstanceOf[java.util.IdentityHashMap[AnyRef, ExpressionRef]]
    val original = identities.get(result)
    val source = field(owner, "semanticOrigin")
    val origins = field(source, "expressions").asInstanceOf[mutable.ArrayBuffer[AnyRef]]
    val sourceBefore = origins.last
    val sourceSiteBefore = invoke(sourceBefore, "site")
    if !leavePending then
      val completed = scala.util.Try(invoke(
        owner,
        "completeReductionSingleton",
        result,
        input,
        Vector(if fail then 1 else 0)
      ))
      if fail then assert(completed.isFailure)
      else
        assert(completed.get eq result)
        assert(identities.get(result) == original)
        assert(result.operation.contains("shape_index"))
        assert(result.constructionFinalized && result.typeFinalized)
        val indices = field(owner, "shapeIndices").asInstanceOf[mutable.Map[ExpressionRef, AnyRef]]
        assert(indices.contains(original))
        val regions = field(owner, "collectionRegions").asInstanceOf[mutable.Map[Any, Any]]
        assert(regions.isEmpty)
        assert(callbackCalls == 0)
        val sourceAfter = origins.find(value => invoke(value, "value") eq result).get
        assert(invoke(sourceAfter, "site") == sourceSiteBefore)
        assert(origins.count(value => invoke(value, "value") eq result) == 1)
        assert(invoke(sourceAfter, "operands") == result.operands)
        assert(indices.size == 1)
  final class Eager(count: Int, explode: Boolean = false) extends Module:
    val input = in(Vec(SInt(2), count))
    val function = DigitalCollectionCallback.checked2((left: Expr[SInt], _: Expr[SInt]) =>
      callbackCalls += 1
      if explode then throw new IllegalArgumentException("eager failure")
      left
    )
    val result = DigitalCollectionContract.reduce(input, function, false)
    assert(callbackCalls == (if count == 1 then 0 else 1))
  def main(args: Array[String]): Unit =
    assert(args.isEmpty)
    var checks = 0
    def check(name: String)(run: => Unit): Unit =
      run; checks += 1; println("PASS " + name)
    check("unchecked carrier refused without callback"):
      callbackCalls = 0
      reject("typed effect validation"):
        DigitalCollectionCallback.reductionBody((left: Expr[SInt], _: Expr[SInt]) =>
          callbackCalls += 1
          left
        )
      assert(callbackCalls == 0)
    check("checked carrier identity retained"):
      val original = DigitalCollectionCallback.checked2((left: Expr[SInt], _: Expr[SInt]) => left)
      val carrier = DigitalCollectionCallback.reductionBody(original)
      assert(carrier.checked eq original)
    check("one-shot construction cell"):
      val owner = new Object
      val result =
        KernelExpr.reserve[Data](owner, KernelTypeDescriptor("Bool"), Vector.empty, body())
      val value = KernelExpressionPayload(
        Vector.empty,
        Some(KernelTypeDescriptor("Bool")),
        None,
        Some("shape_index"),
        None,
        None,
        None
      )
      assert(!result.typeFinalized)
      reject("not complete")(result.operands)
      reject("another session")(result.beginConstruction(new Object))
      reject("seal only once")(result.sealConstruction(owner, value))
      result.beginConstruction(owner)
      reject("begin only once")(result.beginConstruction(owner))
      result.sealConstruction(owner, value)
      assert(result.typeFinalized)
      reject("seal only once")(result.sealConstruction(owner, value))
      val failed =
        KernelExpr.reserve[Data](owner, KernelTypeDescriptor("Bool"), Vector.empty, body())
      failed.failConstruction(owner)
      reject("begin only once")(failed.beginConstruction(owner))
      reject("not complete")(failed.operation)
    check("real singleton identity registries and original source"):
      callbackCalls = 0
      val snapshot = ConstructionKernel.inspect(new Singleton)
      assert(snapshot.shapeIndices.size == 1)
      assert(snapshot.digitalCollections.isEmpty)
      assert(callbackCalls == 0)
    check("unsealed reservation cannot publish"):
      reject("unsealed construction"):
        ConstructionKernel.inspect(new Singleton(leavePending = true))
    check("caught registration failure poisons session"):
      reject("captured constructor failure"):
        ConstructionKernel.inspect(new Singleton(fail = true))
    check("nonsingleton cannot use singleton completion"):
      reject("exact count one"):
        ConstructionKernel.inspect(new Singleton(count = 2))
    check("fresh same-JVM recovery"):
      callbackCalls = 0
      val snapshot = ConstructionKernel.inspect(new Singleton)
      assert(snapshot.shapeIndices.size == 1)
    check("eager singleton zero callbacks"):
      callbackCalls = 0
      val _ = ConstructionKernel.inspect(new Eager(1))
    check("eager nonsingleton one callback"):
      callbackCalls = 0
      val _ = ConstructionKernel.inspect(new Eager(3))
    check("eager callback exception timing unchanged"):
      callbackCalls = 0
      reject("eager failure"):
        ConstructionKernel.inspect(new Eager(3, explode = true))
      assert(callbackCalls == 1)
    println(s"PASS $checks lifecycle primitive controls")
