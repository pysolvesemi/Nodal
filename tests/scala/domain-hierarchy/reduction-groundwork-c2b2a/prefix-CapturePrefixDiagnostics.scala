package nodal.internal.testkit

import nodal.*
import java.lang.StackWalker.StackFrame
import java.nio.file.{Files, Path}
import scala.collection.mutable
import scala.jdk.CollectionConverters.*

/** SOURCE-ONLY DRAFT. Synthetic stack streams are selector/accounting diagnostics, NOT authored
  * hardware, constructor execution, publication, or final-budget evidence. No production test seam:
  * all selection/classification/source helpers are the real private SemanticOriginBuilder methods.
  * The short selector adapter copies only their existing wiring.
  */
object CapturePrefixDiagnostics:
  private[testkit] def call(target: AnyRef, name: String, args: AnyRef*): AnyRef =
    val matches = (target.getClass.getMethods.toVector ++ target.getClass.getDeclaredMethods)
      .distinct.filter(m =>
        (m.getName == name || m.getName.endsWith("$$" + name)) &&
          m.getParameterCount == args.size
      )
    assert(matches.size == 1, s"missing/ambiguous ${target.getClass.getName}.$name")
    val method = matches.head
    method.setAccessible(true)
    try method.invoke(target, args*)
    catch case failure: java.lang.reflect.InvocationTargetException => throw failure.getCause

  private[testkit] def member(target: AnyRef, suffix: String): java.lang.reflect.Field =
    val fields = target.getClass.getDeclaredFields.filter(_.getName.endsWith(suffix))
    assert(fields.length == 1, s"missing/ambiguous ${target.getClass.getName}.$suffix")
    fields.head.setAccessible(true)
    fields.head
  private[testkit] def field(target: AnyRef, suffix: String): AnyRef =
    member(target, suffix).get(target)
  private[testkit] def set(target: AnyRef, suffix: String, value: AnyRef): Unit =
    member(target, suffix).set(target, value)

  /** None is cached-negative; absence is unknown. Do not flatten the nested option. */
  private def cache(builder: SemanticOriginBuilder)
      : mutable.Map[(String, String), Option[Path]] =
    field(builder, "sourceCache").asInstanceOf[mutable.Map[(String, String), Option[Path]]]

  private final case class Frame(owner: String, file: String, method: String, line: Int = 1)
      extends StackFrame:
    def getClassName: String = owner
    def getFileName: String = file
    def getMethodName: String = method
    def getLineNumber: Int = line
    def getByteCodeIndex: Int = -1
    def isNativeMethod: Boolean = false
    def getDeclaringClass: Class[?] =
      throw new AssertionError("synthetic diagnostic must not claim a declaring JVM class")
    def toStackTraceElement: StackTraceElement = new StackTraceElement(owner, method, file, line)

  private final case class Spec(
      name: String,
      frames: Vector[StackFrame],
      entries: Map[(String, String), Option[Path]],
      retained: Int,
      selectedOwner: Option[String],
      binding: Option[String],
      apiOwner: String = "user",
      operation: String = "construct",
      fallback: Boolean = false
  )

  private def isUser(builder: SemanticOriginBuilder, frame: StackFrame): Boolean =
    call(builder, "isUserFrame", frame).asInstanceOf[Boolean]
  private def isApi(builder: SemanticOriginBuilder, frame: StackFrame): Boolean =
    call(builder, "isApiFrame", frame).asInstanceOf[Boolean]

  /** Original captureSite selector wiring, not another source-discovery/normalization engine.
    * Return an actual KernelSourceSite instance, retaining every raw optional field.
    */
  private def select(builder: SemanticOriginBuilder, frames: Vector[StackFrame])
      : (AnyRef, Option[String]) =
    val users = frames.zipWithIndex.filter((frame, _) => isUser(builder, frame))
    val constructor = users.find: (frame, _) =>
      frame.getMethodName == "<init>" && Option(frame.getFileName).flatMap(file =>
        call(builder, "locateSource", file, frame.getClassName).asInstanceOf[Option[Path]]
      ).nonEmpty
    val candidate = constructor.map: ctor =>
      users.find((frame, index) =>
        index < ctor._2 &&
          frame.getClassName == ctor._1.getClassName
      ).getOrElse(ctor)
    .orElse(users.headOption)
    val index = candidate.map(_._2).getOrElse(-1)
    val api = if index > 0 then frames.take(index).reverseIterator.find(isApi(builder, _)) else None
    val located = candidate.flatMap: (frame, _) =>
      Option(frame.getFileName).map(file =>
        call(builder, "locateSpan", file, frame.getClassName, Int.box(frame.getLineNumber))
          .asInstanceOf[(SourceSpan, Option[String])]
      )
    val capture = builder.getClass.getDeclaredMethods.find(_.getName.endsWith("captureSite")).get
    val constructorMethod = capture.getReturnType.getDeclaredConstructors.head
    constructorMethod.setAccessible(true)
    val site = constructorMethod.newInstance(
      located.map(_._1),
      api.map(frame => call(builder, "normalizeOwner", frame.getClassName))
        .getOrElse("user"),
      api.map(frame => call(builder, "normalizeOperation", frame.getMethodName))
        .getOrElse("construct"),
      located.flatMap(_._2)
    ).asInstanceOf[AnyRef]
    site -> candidate.map(_._1.getClassName)

  private def frameWork(frame: StackFrame): Long = 16L + 4L *
    (frame.getClassName.length.toLong + Option(frame.getFileName).fold(0L)(_.length.toLong) +
      frame.getMethodName.length.toLong + 1L)

  private def run(spec: Spec, root: Path): Unit =
    def builder(): SemanticOriginBuilder =
      val result = new SemanticOriginBuilder
      assert(
        field(result, "repositoryRoot") == root,
        "launch under the pinned isolated lookup root; no reflected source-root replacement"
      )
      cache(result) ++= spec.entries
      result
    val prefixBuilder = builder()
    val fullBuilder = builder()
    val charges = mutable.ArrayBuffer.empty[Int]
    val counting: Int => Unit = units => { charges += units; () }
    val previousWork = field(prefixBuilder, "resolutionWork")
    val previousLines = field(prefixBuilder, "resolutionSourceLines")
    set(prefixBuilder, "resolutionWork", Some(counting))
    val before = cache(prefixBuilder).toMap
    var consumed = 0
    val stream = spec.frames.asJava.stream().peek(_ => consumed += 1)
    val retained = try
      call(prefixBuilder, "cachedConstructorPrefix", stream)
        .asInstanceOf[java.util.List[StackFrame]].asScala.toVector
    finally
      stream.close()
      set(prefixBuilder, "resolutionWork", previousWork)
    assert(
      cache(prefixBuilder).toMap == before,
      s"${spec.name}: walking must not populate sourceCache, including unknown keys"
    )
    assert(field(prefixBuilder, "resolutionSourceLines") eq previousLines)
    assert(consumed == spec.retained && retained.size == spec.retained, spec.name)
    assert(
      retained.zip(spec.frames).forall((left, right) => left eq right),
      "prefix retains the original frame identities and order, including its constructor"
    )
    val fullFrames = spec.frames.take(96)
    val fullSite = select(fullBuilder, fullFrames)
    val prefixSite = select(prefixBuilder, retained)
    assert(prefixSite == fullSite, s"${spec.name}: complete raw KernelSourceSite/owner mismatch")
    assert(prefixSite._2 == spec.selectedOwner, spec.name)
    assert(call(prefixSite._1, "bindingName") == spec.binding, spec.name)
    assert(call(prefixSite._1, "apiOwner") == spec.apiOwner, spec.name)
    assert(call(prefixSite._1, "apiOperation") == spec.operation, spec.name)
    if spec.fallback then
      val span = call(prefixSite._1, "span").asInstanceOf[Option[SourceSpan]].get
      val selected = retained.find(frame => spec.selectedOwner.contains(frame.getClassName)).get
      assert(
        span == SourceSpan(
          selected.getFileName,
          math.max(1, selected.getLineNumber),
          1,
          math.max(1, selected.getLineNumber),
          1
        ),
        spec.name
      )
    val prefixUnits = charges.iterator.map(_.toLong).sum
    val fullUnits = fullFrames.iterator.map(frameWork).sum
    assert(charges.headOption.contains(2), "real prefix iterator/buffer debit")
    assert(
      prefixUnits >= retained.iterator.map(frameWork).sum + 2L,
      "new decisions are additional charged work, including fallback"
    )
    if spec.retained < 96 && fullFrames.size == 96 then
      assert(
        prefixUnits < fullUnits,
        "this deep diagnostic must demonstrate actual omitted suffix work"
      )
    if spec.retained == 96 then
      assert(
        prefixUnits > fullUnits,
        "fallback has real extra classification cost; do not claim a universal saving"
      )
    assert(field(prefixBuilder, "resolutionWork") eq previousWork)
    println(s"PASS synthetic diagnostic ${spec.name}: visited=$consumed full=${fullFrames.size} " +
      s"prefixUnits=$prefixUnits fullFrameUnits=$fullUnits rawSite=${prefixSite._1}")

  def main(args: Array[String]): Unit =
    assert(args.length == 1, "one pinned isolated lookup-root argument is required")
    val root = Path.of(args(0)).toAbsolutePath.normalize()
    val sourceA = root.resolve("prefixdiag/a/PrefixFixture.scala")
    val sourceB = root.resolve("prefixdiag/b/PrefixFixture.scala")
    assert(Files.isRegularFile(sourceA) && Files.isRegularFile(sourceB))
    def line(path: Path, text: String): Int =
      val lines = Files.readAllLines(path).asScala
      val found = lines.zipWithIndex.filter(_._1.contains(text))
      assert(found.size == 1, s"unique literal fixture source marker required: $text")
      found.head._2 + 1
    val ownerA = "prefixdiag.a.Outer$Inner$Leaf"
    val ownerB = "prefixdiag.b.Outer$Inner$Leaf"
    val file = "PrefixFixture.scala"
    val ctorA = Frame(ownerA, file, "<init>", line(sourceA, "val constructorA"))
    val lexicalA = Frame(ownerA, file, "body$1", line(sourceA, "val lexicalA"))
    val ctorB = Frame(ownerB, file, "<init>", line(sourceB, "val constructorB"))
    val lexicalB = Frame(ownerB, file, "body$1", line(sourceB, "val lexicalB"))
    val missing = Frame("prefixdiag.Absent$Nested", "AbsentPrefixSource.scala", "<init>", 7)
    val apiFar = Frame("nodal.Unused$", "CandidateApi.scala", "ignored")
    val apiNear = Frame("nodal.Candidate$Signal$", "CandidateApi.scala", "add$extension")
    val tail = Vector.tabulate[StackFrame](120)(index =>
      Frame("java.synthetic.UnusedSuffix", "Unused.java", "invoke" + index)
    )
    def deep(head: StackFrame*): Vector[StackFrame] = head.toVector ++ tail
    val a = (file -> ownerA) -> Some(sourceA)
    val b = (file -> ownerB) -> Some(sourceB)
    val no = (missing.file -> missing.owner) -> None
    val cases = Vector(
      Spec(
        "cached-positive-includes-constructor",
        deep(apiNear, ctorA),
        Map(a),
        2,
        Some(ownerA),
        Some("constructorA"),
        "Signal",
        "add"
      ),
      Spec(
        "earlier-cached-none-then-positive",
        deep(missing, apiNear, ctorA),
        Map(no, a),
        3,
        Some(ownerA),
        Some("constructorA"),
        "Signal",
        "add"
      ),
      Spec(
        "earlier-unknown-disables-later-positive",
        deep(missing, apiNear, ctorA),
        Map(a),
        96,
        Some(ownerA),
        Some("constructorA"),
        "Signal",
        "add"
      ),
      Spec(
        "all-cached-none",
        deep(missing, ctorA),
        Map(no, (file -> ownerA) -> None),
        96,
        Some(missing.owner),
        None,
        fallback = true
      ),
      Spec("no-constructor", deep(lexicalA), Map(a), 96, Some(ownerA), Some("lexicalA")),
      Spec(
        "missing-source-fallback",
        deep(missing),
        Map.empty,
        96,
        Some(missing.owner),
        None,
        fallback = true
      ),
      Spec(
        "same-class-lexical-before-constructor-nearest-api",
        deep(apiFar, apiNear, lexicalA, ctorA),
        Map(a),
        4,
        Some(ownerA),
        Some("lexicalA"),
        "Signal",
        "add"
      ),
      Spec(
        "different-class-earlier-user-is-not-lexical-owner",
        deep(lexicalB, apiNear, ctorA),
        Map(a, b),
        3,
        Some(ownerA),
        Some("constructorA"),
        "Signal",
        "add"
      ),
      Spec(
        "full-nested-owner-key-and-basename-collision",
        deep(ctorB, ctorA),
        Map(a, b),
        1,
        Some(ownerB),
        Some("constructorB")
      ),
      Spec(
        "outer-owner-alias-cannot-certify-nested-constructor",
        deep(ctorA, ctorB),
        Map((file -> "prefixdiag.a.Outer") -> Some(sourceA), b),
        96,
        Some(ownerA),
        Some("constructorA")
      ),
      Spec(
        "other-owner-same-basename-cannot-certify",
        deep(ctorA, ctorB),
        Map(b),
        96,
        Some(ownerA),
        Some("constructorA")
      ),
      Spec(
        "nonuser-constructors-do-not-disable-stopping",
        deep(
          Frame("scala.NonUser", file, "<init>"),
          Frame("nodal.Internal", "ConstructionSession.scala", "<init>"),
          Frame("native.NoFile", null, "<init>"),
          ctorA
        ),
        Map(a),
        4,
        Some(ownerA),
        Some("constructorA")
      ),
      Spec("no-user-frames", tail, Map.empty, 96, None, None),
      Spec(
        "stream-end-before-cap",
        Vector(apiNear, ctorA),
        Map(a),
        2,
        Some(ownerA),
        Some("constructorA"),
        "Signal",
        "add"
      ),
      Spec(
        "positive-at-frame96",
        tail.take(95) :+ ctorA,
        Map(a),
        96,
        Some(ownerA),
        Some("constructorA")
      ),
      Spec("constructor-after96-not-observed", tail.take(96) :+ ctorA, Map(a), 96, None, None)
    )
    cases.foreach(run(_, root))
    println(s"PASS ${cases.size} synthetic selector diagnostics, NOT authored-hardware evidence")
