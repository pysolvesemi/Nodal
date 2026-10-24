package nodal
import scala.collection.mutable
import java.nio.file.{Files, Path}
object F:
  private object Done extends scala.util.control.ControlThrowable
  private var session: ConstructionSession = null
  private var builder: AnyRef = null
  private var values = Vector.empty[Any]
  private var module = 0L
  private var depth = 0
  private var mode = "ordinary"
  private var observed = false
  private def call(target: AnyRef, name: String, args: AnyRef*): AnyRef =
    val m = (target.getClass.getMethods.toVector ++ target.getClass.getDeclaredMethods)
      .find(m =>
        (m.getName == name || m.getName.endsWith("$$" + name)) && m.getParameterCount == args.size
      ).get
    m.setAccessible(true)
    try m.invoke(target, args*)
    catch case e: java.lang.reflect.InvocationTargetException => throw e.getCause
  private def field(target: AnyRef, name: String): AnyRef =
    val f = target.getClass.getDeclaredFields.filter(_.getName.endsWith(name))
    assert(f.length == 1, name); f.head.setAccessible(true); f.head.get(target)
  private def ops(target: AnyRef): mutable.ArrayBuffer[AnyRef] =
    field(target, "operations").asInstanceOf[mutable.ArrayBuffer[AnyRef]]
  def bind(root: Module): Unit =
    session = call(ConstructionKernel, "active").asInstanceOf[Option[ConstructionSession]].get
    session.bindDiagnosticConstructionWork(root)
  def reserve(input: Expr[Vec[SInt]]): KernelExpr[Data] =
    val body = DigitalCollectionCallback.reductionBody(DigitalCollectionCallback.checked2(
      (left: Expr[SInt], _: Expr[SInt]) => left
    ))
    call(
      session,
      "reserveReductionConstructionIntent",
      input,
      body,
      "reduce_left"
    ).asInstanceOf[KernelExpr[Data]]
  def probe(): Unit =
    val token = call(builder, "prepareOperationSource", Long.box(module), "assignment", values)
    val site = call(builder, "captureSemanticSource", token)
    println(
      s"PROBE depth=$depth prefix=${field(token, "prefixComplete")} reusable=${call(token, "canReuse")} site=$site"
    )
    observed = true
    throw Done
  def exercise(root: R): Unit =
    val _ = call(session, "completeReductionSingleton", root.result, root.input, Vector(0))
    session.beginDiagnosticConstructionClosure(root, root.result)
    val _ = call(session, "drainIncrementalBoundaryClosure")
    val proxy = session.selectConstructionRequestPort(root.result, root.child, root.leaf.data)
    val _ = call(session, "drainIncrementalBoundaryClosure")
    builder = field(session, "semanticOrigin")
    values = Vector[Any](proxy, root.result)
    module = field(
      session,
      "declarationIds"
    ).asInstanceOf[java.util.IdentityHashMap[AnyRef, DeclarationRef]].get(proxy).module
    if mode == "probe" then root.probeDepth(depth)
    if mode == "cache_missing" then
      field(
        builder,
        "sourceCache"
      ).asInstanceOf[mutable.Map[(String, String), Option[Path]]].clear()
    val path = Path.of(
      sys.env("NODAL_WORKSPACE"),
      "tests/scala/domain-hierarchy/fixtures/driven-diagnostic/R.scala"
    )
    val backup = path.resolveSibling("R.scala.hidden")
    if mode == "source_missing" then
      assert(Files.getAttribute(path, "unix:nlink").asInstanceOf[Number].longValue == 1L)
      val _ = Files.move(path, backup)
    try
      val first = ops(session).size
      val second = ops(builder).size
      root.driveCompletedScalar(proxy, depth)
      assert(root.unwound == depth)
      assert(ops(session).size == first + 1 && ops(builder).size == second + 1)
      println(
        s"CAPTURE mode=$mode depth=$depth first=${call(ops(session).last, "source")} second=${call(ops(builder).last, "site")}"
      )
      observed = true
    finally
      if mode == "source_missing" then
        val _ = Files.move(backup, path)
    // Source-capture parity only: do not claim downstream publication from this probe.
    throw Done
  def main(args: Array[String]): Unit =
    assert(args.length == 2)
    mode = args(0); depth = args(1).toInt
    try
      val _ = ConstructionKernel.inspect(new R)
      throw new AssertionError("expected capture-only termination")
    catch case Done => ()
    assert(observed)
