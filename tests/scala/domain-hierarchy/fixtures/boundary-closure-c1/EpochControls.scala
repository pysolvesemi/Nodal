package nodal.internal.testkit
import nodal.*
object EpochControls:
  var mode = "noop"
  def invoke(target: AnyRef, name: String, args: AnyRef*): AnyRef =
    val methods = target.getClass.getDeclaredMethods.filter(value =>
      (value.getName == name || value.getName.endsWith("$$" + name)) &&
        value.getParameterCount == args.size
    )
    require(methods.length == 1, "ambiguous or missing reflected method: " + name)
    val method = methods.head
    method.setAccessible(true)
    try method.invoke(target, args*)
    catch case failure: java.lang.reflect.InvocationTargetException => throw failure.getCause
  def field(target: AnyRef, suffix: String): AnyRef =
    val member = target.getClass.getDeclaredFields.find(_.getName.endsWith(suffix)).get
    member.setAccessible(true)
    member.get(target)
  def session(): AnyRef = invoke(ConstructionKernel, "active").asInstanceOf[Option[AnyRef]].get
  def rejects(fragment: String)(body: => Any): Unit =
    val failure = scala.util.Try(body).failed.get
    assert(failure.getMessage.contains(fragment), failure.toString)
  def exercise(root: EpochRoot, child: Instance[EpochLeaf], first: Signal[SInt]): Unit =
    val owner = session()
    invoke(owner, "beginIncrementalBoundaryClosure", root, Int.box(65536))
    val closure = field(owner, "boundaryClosureOwner").asInstanceOf[Option[AnyRef]].get
    val query =
      invoke(owner, "currentBoundaryEpochQuery").asInstanceOf[StructuralParameterBinding.Query]
    val before = field(closure, "currentSnapshot")
    val spent = field(closure, "remaining").asInstanceOf[java.lang.Long].longValue()
    mode match
      case "noop" =>
        invoke(owner, "drainIncrementalBoundaryClosure")
        assert(field(closure, "currentSnapshot") eq before)
        assert(field(closure, "remaining").asInstanceOf[java.lang.Long].longValue() < spent)
        assert(query.checkedLiteral("EpochRoot", "3") == 3L)
      case "new" | "stale" | "stalegraph" =>
        val graph = query.exportValues(Vector(query.literal(2L)))
        val firstType = CandidateRuntime.typeDescriptor(first.dataType)
        val second = child(_.q)
        invoke(owner, "drainIncrementalBoundaryClosure")
        assert(!(field(closure, "currentSnapshot") eq before))
        assert(CandidateRuntime.typeDescriptor(first.dataType) == firstType)
        assert(CandidateRuntime.typeDescriptor(second.dataType) ==
          CandidateRuntime.typeDescriptor(SInt(3)))
        if mode == "stale" then
          rejects("stale boundary snapshot epoch"):
            query.checkedLiteral("EpochRoot", "3")
        if mode == "stalegraph" then
          rejects("stale or foreign epoch"):
            invoke(owner, "materializeStructuralDimensions", graph)
      case "authored" => rejects("freezes authored declarations")(root.newAuthored())
      case "budget" =>
        val left = field(closure, "remaining").asInstanceOf[java.lang.Long].longValue()
        invoke(closure, "charge", Long.box(left))
        rejects("work budget exhausted")(child(_.q))
      case "names" =>
        val source = field(owner, "semanticOrigin").asInstanceOf[SemanticOriginBuilder]
        source.captureLexicalName(child.module.q, Vector("changed_after_last_epoch"))
      case _ => assert(false)
  def exerciseMap(root: EpochMapRoot, captured: Expr[SInt], foreign: Expr[?]): Unit =
    val owner = session()
    invoke(owner, "beginIncrementalBoundaryClosure", root, Int.box(65536))
    val closure = field(owner, "boundaryClosureOwner").asInstanceOf[Option[AnyRef]].get
    println("MAP remaining after initial drain=" + field(closure, "remaining"))
    val regions =
      field(owner, "collectionRegions").asInstanceOf[scala.collection.mutable.Map[Any, AnyRef]]
    val region = regions.values.head
    val captures =
      field(region, "captures").asInstanceOf[scala.collection.mutable.Map[Any, (AnyRef, AnyRef)]]
    val arguments =
      field(region, "arguments").asInstanceOf[scala.collection.mutable.ArrayBuffer[Any]]
    val count = arguments.size
    val ownerId = invoke(invoke(region, "reference"), "module")
    mode match
      case "reuse" =>
        val edge = captures.values.find(_._1 eq captured).get._2
        val actual = invoke(
          owner,
          "bindCollectionValue",
          captured,
          Some(region),
          ownerId,
          Boolean.box(true),
          Boolean.box(false),
          Boolean.box(false)
        )
        assert(actual eq edge)
        assert(arguments.size == count)
        invoke(owner, "drainIncrementalBoundaryClosure")
      case "signature" =>
        rejects("verified digital region signature cannot change"):
          invoke(
            owner,
            "bindCollectionValue",
            foreign,
            Some(region),
            ownerId,
            Boolean.box(true),
            Boolean.box(false),
            Boolean.box(false)
          )
        assert(arguments.size == count)
      case "tamper" =>
        val (key, (_, argument)) = captures.head
        captures.update(key, foreign -> argument)
        rejects("changed outside its canonical mutation hook"):
          invoke(owner, "drainIncrementalBoundaryClosure")
      case _ => assert(false)
  def main(args: Array[String]): Unit =
    assert(args.isEmpty)
    var count = 0
    for next <- Vector("noop", "new", "stale", "stalegraph", "authored", "budget", "names", "noop")
    do
      mode = next
      if Set("stale", "stalegraph", "authored", "budget")(next) then
        rejects("captured constructor failure")(EpochFactories.root())
      else if next == "names" then
        rejects("frozen semantic identity changed")(EpochFactories.root())
      else EpochFactories.root()
      count += 1
      println("PASS epoch " + next)
    for next <- Vector("reuse", "signature", "tamper", "reuse") do
      mode = next
      if next == "reuse" then EpochFactories.mapped()
      else rejects("captured constructor failure")(EpochFactories.mapped())
      count += 1
      println("PASS epoch " + next)
    println("PASS " + count + " Session epoch controls")
