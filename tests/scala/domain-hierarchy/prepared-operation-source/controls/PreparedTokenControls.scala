package nodal.internal.testkit.drivendiagnostic

import nodal.*
import scala.collection.mutable
import java.nio.file.{Files, Path}

/** Private corruption seams are not organic source programs or public admission routes. */
object PreparedTokenControls:
  var mode = "positive"
  private var session: ConstructionSession = null
  private var owner: AnyRef = null
  private var assignmentCost = 0L
  private var observed = false
  private def call(target: AnyRef, name: String, args: AnyRef*): AnyRef =
    val method = (target.getClass.getMethods.toVector ++ target.getClass.getDeclaredMethods)
      .find(m =>
        (m.getName == name || m.getName.endsWith("$$" + name)) &&
          m.getParameterCount == args.size
      ).getOrElse(throw new AssertionError(name))
    method.setAccessible(true)
    try method.invoke(target, args*)
    catch case failure: java.lang.reflect.InvocationTargetException => throw failure.getCause
  private def member(target: AnyRef, suffix: String): java.lang.reflect.Field =
    val matches = target.getClass.getDeclaredFields.filter(_.getName.endsWith(suffix))
    assert(matches.length == 1, suffix)
    matches.head.setAccessible(true)
    matches.head
  private def field(target: AnyRef, suffix: String): AnyRef = member(target, suffix).get(target)
  private def set(target: AnyRef, suffix: String, value: AnyRef): Unit =
    member(target, suffix).set(target, value)
  private def balance: Long = field(owner, "remaining").asInstanceOf[Long]
  private def charge(units: Long): Unit =
    assert(units >= 0)
    val _ = call(owner, "charge", Long.box(units))
  private def ops(target: AnyRef): mutable.ArrayBuffer[AnyRef] =
    field(target, "operations").asInstanceOf[mutable.ArrayBuffer[AnyRef]]
  private def reject(fragment: String)(body: => Any): Throwable =
    val failure = scala.util.Try(body).failed.get
    assert(Option(failure.getMessage).exists(_.contains(fragment)), failure.toString)
    failure
  def bind(root: Module): Unit =
    session = call(ConstructionKernel, "active").asInstanceOf[Option[ConstructionSession]].get
    session.bindDiagnosticConstructionWork(root)
  def reserve(input: Expr[Vec[SInt]]): KernelExpr[Data] =
    val body = DigitalCollectionCallback.reductionBody(DigitalCollectionCallback.checked2(
      (left: Expr[SInt], _: Expr[SInt]) => left
    ))
    call(session, "reserveReductionConstructionIntent", input, body, "reduce_left")
      .asInstanceOf[KernelExpr[Data]]

  def exercise(root: C2b2aRoot): Unit =
    val _ = call(session, "completeReductionSingleton", root.result, root.input, Vector(0))
    session.beginDiagnosticConstructionClosure(root, root.result)
    val _ = call(session, "drainIncrementalBoundaryClosure")
    owner = field(session, "boundaryClosureOwner").asInstanceOf[Option[AnyRef]].get
    val proxy = session.selectConstructionRequestPort(root.result, root.child, root.leaf.data)
    val _ = call(session, "drainIncrementalBoundaryClosure")
    val declarations = field(session, "declarationIds")
      .asInstanceOf[java.util.IdentityHashMap[AnyRef, DeclarationRef]]
    val module = declarations.get(proxy).module
    val builder = field(session, "semanticOrigin")
    val query = call(session, "currentBoundaryEpochQuery")
      .asInstanceOf[StructuralParameterBinding.Query]
    val epoch = field(owner, "epoch").asInstanceOf[Long]
    val beforeSession = ops(session).size
    val beforeOrigin = ops(builder).size
    val next = field(session, "nextDigitalEffectOrdinal").asInstanceOf[mutable.Map[Long, Int]]
    val ordinal = next.getOrElse(module, 0)
    if mode == "positive" || mode == "budget_first" || mode == "budget_second" then
      if mode == "budget_first" then charge(balance - 1L)
      if mode == "budget_second" then
        assert(assignmentCost > 20L)
        charge(balance - assignmentCost + 10L)
      val before = balance
      val outcome = scala.util.Try(root.driveCompletedScalar(proxy))
      if mode == "positive" then
        outcome.get
        assignmentCost = before - balance
        assert(ops(session).size == beforeSession + 1 && ops(builder).size == beforeOrigin + 1)
        assert(next(module) == ordinal + 1)
        println(s"PASS actual assignment debit=$assignmentCost")
        return
      val failure = outcome.failed.get
      assert(
        Option(failure.getMessage).exists(_.contains("boundary closure work budget exhausted"))
      )
      assert(next(module) == ordinal + 1)
      assert(ops(builder).size == beforeOrigin)
      if mode == "budget_first" then assert(ops(session).size == beforeSession)
      else
        assert(ops(session).size == beforeSession + 1)
        val production = Path.of(sys.env("NODAL_WORKSPACE"))
          .resolve("core/scala/api/src/nodal/SemanticOriginKernel.scala")
        val lines = Files.readAllLines(production)
        val comparisonLine = (0 until lines.size).find(index =>
          lines.get(index).trim == "chargeWork(3L + left.length.toLong + right.length.toLong)"
        ).get + 1
        assert(failure.getStackTrace.exists(frame =>
          frame.getMethodName.contains("consumeOperationSource") &&
            frame.getFileName == "SemanticOriginKernel.scala" &&
            frame.getLineNumber == comparisonLine
        ))
        println(s"PASS exhaustion at paid decoded-line comparison line=$comparisonLine")
    else
      // This direct paired call is an explicitly synthetic seam, not a Session assignment.
      val values = Vector[Any](proxy, root.result)
      val prepared = call(builder, "prepareOperationSource", Long.box(module), "assignment", values)
      val _ = call(builder, "captureSemanticSource", prepared)
      assert(call(prepared, "canReuse") == Boolean.box(true))
      def consume(
          token: AnyRef = prepared,
          target: Long = module,
          kind: String = "assignment",
          operands: Vector[Any] = values
      ): AnyRef =
        call(session, "consumePreparedOperationSource", token, Long.box(target), kind, operands)
      mode match
        case "wrong_module" => reject("authority changed")(consume(target = module + 1))
        case "wrong_kind" => reject("authority changed")(consume(kind = "value-connect"))
        case "wrong_values" =>
          val copied = Vector[Any](proxy, root.result)
          assert(!(copied eq values))
          reject("authority changed")(consume(operands = copied))
        case "foreign_builder" =>
          val constructor =
            builder.getClass.getDeclaredConstructors.find(_.getParameterCount == 0).get
          constructor.setAccessible(true)
          val foreign = constructor.newInstance().asInstanceOf[AnyRef]
          set(prepared, "builder", foreign)
          reject("authority changed")(consume())
        case "consumed" =>
          val _ = consume()
          assert(ops(builder).size == beforeOrigin + 1)
          reject("already consumed")(consume())
        case "freshness_content" | "freshness_missing" | "freshness_nonregular" =>
          val observations = field(prepared, "lines").asInstanceOf[Map[Path, AnyRef]]
          assert(observations.size == 1)
          val path = observations.keys.head
          assert(path.getFileName.toString == "C2b2aRoot.scala")
          assert(
            Files.getAttribute(path, "unix:nlink").asInstanceOf[Number].longValue == 1L,
            "freshness fixture must be a private copy, never a borrowed hardlink"
          )
          val original = Files.readAllBytes(path)
          val modified = Files.getLastModifiedTime(path)
          val backup = path.resolveSibling(path.getFileName.toString + ".token-backup")
          assert(!Files.exists(backup))
          try
            if mode == "freshness_content" then
              val changed = original.clone()
              val index = changed.indexOf('a'.toByte)
              assert(index >= 0)
              changed(index) = 'b'.toByte
              val _ = Files.write(path, changed)
              val _ = Files.setLastModifiedTime(path, modified)
              assert(Files.size(path) == original.length &&
                Files.getLastModifiedTime(path) == modified)
              reject("semantic source changed")(consume())
            else
              val _ = Files.move(path, backup)
              if mode == "freshness_nonregular" then
                val _ = Files.createDirectory(path)
              val result = scala.util.Try(consume())
              assert(result.isFailure)
          finally
            if mode == "freshness_content" then
              val _ = Files.write(path, original)
              val _ = Files.setLastModifiedTime(path, modified)
            else
              if Files.isDirectory(path) then Files.delete(path)
              val _ = Files.move(backup, path)
          assert(java.util.Arrays.equals(Files.readAllBytes(path), original))
        case other => throw new AssertionError(other)
      assert(field(prepared, "consumed") == Boolean.box(true))
      assert(ops(session).size == beforeSession)
      assert(ops(builder).size == beforeOrigin + (if mode == "consumed" then 1 else 0))
    assert(field(owner, "poisoned") == Boolean.box(true))
    assert(field(owner, "epoch").asInstanceOf[Long] > epoch)
    reject("stale boundary snapshot epoch")(query.checkedLiteral("C2b2aRoot", "3"))
    reject("poisoned")(call(owner, "verifyPublication"))
    reject("poisoned")(call(session, "drainIncrementalBoundaryClosure"))
    observed = true
    println(s"PASS negative $mode balance=$balance")

  def main(args: Array[String]): Unit =
    assert(args.length == 1)
    val requested = args(0)
    mode = "positive"
    val calibration = ConstructionKernel.inspect(new C2b2aRoot)
    assert(calibration.digitalAssignments.size == 1)
    mode = requested
    if mode != "positive" then
      observed = false
      val outcome = scala.util.Try(ConstructionKernel.inspect(new C2b2aRoot))
      assert(observed && outcome.isFailure, s"negative did not refuse publication: $requested")
      mode = "positive"
      val recovery = ConstructionKernel.inspect(new C2b2aRoot)
      assert(recovery.digitalAssignments.size == 1)
      println("PASS fresh recovery")
