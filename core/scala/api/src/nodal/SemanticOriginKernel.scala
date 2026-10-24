package nodal

import java.lang.StackWalker
import java.lang.reflect.{Field, Modifier}
import java.nio.charset.StandardCharsets
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.{FileVisitResult, Files, Path, SimpleFileVisitor}
import java.security.MessageDigest
import java.util.IdentityHashMap

import scala.collection.mutable
import scala.jdk.CollectionConverters.*

private[nodal] final case class KernelNameSnapshot(
    semanticPath: String,
    name: String,
    category: String,
    provenance: String,
    source: Option[SourceSpan],
    aliases: Vector[String] = Vector.empty
)

private[nodal] final case class KernelOriginSnapshot(
    id: String,
    semanticPath: String,
    kind: String,
    operation: String,
    source: Option[SourceSpan],
    parents: Vector[String],
    sink: Option[String],
    inlined: Boolean
)

private[nodal] final case class KernelGeneratedNameSnapshot(
    category: String,
    name: String,
    owner: String,
    origin: String
)

private[nodal] final case class SemanticOriginResult(
    modulePaths: Map[Long, String],
    domainPaths: Map[(Long, Int), String],
    domainNames: Map[(Long, Int), String],
    declarationPaths: Map[(Long, Int), String],
    declarationNames: Map[(Long, Int), String],
    expressionPaths: Map[(Long, Int), String],
    expressionNames: Map[(Long, Int), String],
    instancePaths: Map[(Long, Int), String],
    names: Vector[KernelNameSnapshot],
    origins: Vector[KernelOriginSnapshot],
    generatedNames: Vector[KernelGeneratedNameSnapshot],
    sourceMap: Vector[SourceMapEntry]
)

private final case class KernelSourceSite(
    span: Option[SourceSpan],
    apiOwner: String,
    apiOperation: String,
    bindingName: Option[String]
):
  def fingerprint: String =
    val location = span
      .map(source =>
        s"${source.path}:${source.line}:${source.column}:${source.endLine}:${source.endColumn}"
      )
      .getOrElse("unknown")
    s"$location|$apiOwner|$apiOperation|${bindingName.getOrElse("")}"

private final case class OriginModuleCapture(
    handle: Long,
    module: Module,
    className: String,
    parent: Option[Long],
    site: KernelSourceSite
)

private final case class OriginDomainCapture(
    module: Long,
    index: Int,
    domain: ClockDomain,
    name: String,
    kind: String,
    site: KernelSourceSite
)

private final case class OriginDeclarationCapture(
    module: Long,
    index: Int,
    value: AnyRef,
    kind: String,
    explicitName: Option[String],
    site: KernelSourceSite
)

private final case class OriginExpressionCapture(
    module: Long,
    index: Int,
    value: AnyRef,
    operands: Vector[Any],
    site: KernelSourceSite
)

private final case class OriginInstanceCapture(
    parent: Long,
    ordinal: Int,
    child: Long,
    instance: AnyRef,
    childModule: Module,
    site: KernelSourceSite
)

private final case class OriginOperationCapture(
    module: Long,
    kind: String,
    values: Vector[Any],
    site: KernelSourceSite
)

private final case class NameCandidate(
    key: String,
    base: String,
    provenance: String
)

private final case class ResolvedName(name: String, provenance: String)

private[nodal] final class SemanticOriginBuilder private (
    areaNames: Option[(() => Map[Long, String], Long => Unit) => Vector[KernelNameSnapshot]]
):
  // Retain the existing JVM constructor, its accounting, and all resolver entry points.
  def this() = this(None)
  def this(project: (() => Map[Long, String], Long => Unit) => Vector[KernelNameSnapshot]) =
    this(Some(project))

  // Scoped to one synchronous resolution; the caller owns the cumulative epoch budget.
  // ControlThrowable deliberately bypasses existing best-effort Exception catches.
  private final class ResolutionWorkAborted(val original: Throwable)
      extends scala.util.control.ControlThrowable
  private var resolutionWork: Option[Int => Unit] = None
  private final case class SourceFileStamp(
      size: Long,
      modified: java.nio.file.attribute.FileTime,
      key: Option[AnyRef],
      regular: Boolean
  )
  private final case class ResolutionSourceLines(
      stamp: SourceFileStamp,
      lines: Vector[String]
  )
  // Immutable entries and map snapshots; lifetime is exactly one metered call.
  // Attribute equality detects observable changes, not cryptographic immutability.
  private var resolutionSourceLines: Option[Map[Path, ResolutionSourceLines]] = None

  private def chargeWork(units: Long = 1L): Unit =
    resolutionWork.foreach: charge =>
      var remaining = units
      while remaining > 0L do
        val part = math.min(remaining, Int.MaxValue.toLong).toInt
        try charge(part)
        catch case failure: Throwable => throw new ResolutionWorkAborted(failure)
        remaining -= part

  private def chargeSquare(size: Long, multiplier: Int = 1): Unit =
    if resolutionWork.nonEmpty then
      var row = 0L
      while row < size do
        chargeWork(size * multiplier.toLong)
        row += 1L

  /** Same eager resolver and namespace as resolve(), with caller-owned work accounting. Canonical
    * record scans/sorts are conservatively prepaid by 64*N*N + 128*N units, where N is the total
    * captured record count plus one. This covers the fixed passes, nested namespace scans, and up
    * to four generated entries per captured record. Reflection, source bytes, recursive
    * collections, ancestry and collision retries are additional charges, not assumed to fit the
    * canonical-record bound. Units measure structural work, not elapsed time inside user
    * collection/reflection implementations or filesystem operations. Source lines are reused only
    * within this call after attribute revalidation; no cross-epoch cache or proof ownership.
    */
  def resolveWithWork(charge: Int => Unit): SemanticOriginResult =
    resolveScoped(charge, exactCanonical = false)

  /** Construction-only accounting; same resolver, namespace and invocation-local source cache. */
  def resolveConstructionWithWork(charge: Int => Unit): SemanticOriginResult =
    resolveScoped(charge, exactCanonical = true)

  private def resolveScoped(charge: Int => Unit, exactCanonical: Boolean): SemanticOriginResult =
    val previous = resolutionWork
    val previousLines = resolutionSourceLines
    val previousCanonical = canonicalAccounting
    resolutionWork = Some(charge)
    resolutionSourceLines = Some(Map.empty)
    canonicalAccounting = exactCanonical
    try resolve()
    catch case aborted: ResolutionWorkAborted => throw aborted.original
    finally
      canonicalAccounting = previousCanonical
      resolutionSourceLines = previousLines
      resolutionWork = previous

  // True only for one construction-mode resolution, never inherited by the legacy entry.
  private var canonicalAccounting = false

  private def canonicalVisit(): Unit =
    if canonicalAccounting then chargeWork()

  private def canonicalProbe[A](body: => A): A =
    canonicalVisit()
    body

  private def canonicalCopy[A](units: Long)(body: => A): A =
    if canonicalAccounting then chargeWork(units)
    body

  private def canonicalIterator[A](values: Iterator[A]): Iterator[A] =
    if !canonicalAccounting then values
    else
      new Iterator[A]:
        def hasNext: Boolean = values.hasNext
        def next(): A =
          canonicalVisit()
          values.next()

  private def canonicalVector[A](values: Iterable[A]): Vector[A] =
    if !canonicalAccounting then values.toVector
    else canonicalIterator(values.iterator).toVector

  private def canonicalVector[A](values: Option[A]): Vector[A] =
    if !canonicalAccounting then values.toVector
    else canonicalIterator(values.iterator).toVector

  private def canonicalSort[A, K](values: Vector[A])(key: A => K)(using
      order: Ordering[K]
  ): Vector[A] =
    if !canonicalAccounting then values.sortBy(key)
    else
      // The canonical inputs are Vectors: size is constant-time. Pay before the sort buffer.
      chargeWork(values.size.toLong)
      val counted = new Ordering[K]:
        def compare(left: K, right: K): Int =
          canonicalVisit()
          order.compare(left, right)
      values.sortBy(value => canonicalProbe(key(value)))(using counted)

  private def canonicalMap[K, V](values: Iterable[(K, V)]): Map[K, V] =
    if !canonicalAccounting then values.toMap
    else canonicalIterator(values.iterator).toMap

  private def canonicalDistinct(values: Vector[String]): Vector[String] =
    if !canonicalAccounting then values.distinct
    else canonicalIterator(canonicalIterator(values.iterator).distinct).toVector

  private def canonicalSet[A](values: Iterable[A]): Set[A] =
    if !canonicalAccounting then values.toSet
    else canonicalIterator(values.iterator).toSet

  private def canonicalUnion[A](left: Set[A], right: Iterable[A]): Set[A] =
    if !canonicalAccounting then left ++ right
    else left ++ canonicalIterator(right.iterator)

  private def canonicalAdd[A](target: mutable.Set[A], values: Iterable[A]): Unit =
    if !canonicalAccounting then target ++= values
    else target ++= canonicalIterator(values.iterator)

  private def canonicalFlatMap[A, B](values: Vector[A])(body: A => IterableOnce[B]): Vector[B] =
    if !canonicalAccounting then values.flatMap(body)
    else
      canonicalIterator(values.iterator)
        .flatMap(value => canonicalIterator(body(value).iterator)).toVector

  private val walker =
    StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE)
  private val sourceCache: mutable.HashMap[(String, String), Option[Path]] =
    mutable.HashMap.empty
  private val modules: mutable.ArrayBuffer[OriginModuleCapture] = mutable.ArrayBuffer.empty
  private val domains: mutable.ArrayBuffer[OriginDomainCapture] = mutable.ArrayBuffer.empty
  private val declarations: mutable.ArrayBuffer[OriginDeclarationCapture] =
    mutable.ArrayBuffer.empty
  private val expressions: mutable.ArrayBuffer[OriginExpressionCapture] =
    mutable.ArrayBuffer.empty
  private val instances: mutable.ArrayBuffer[OriginInstanceCapture] = mutable.ArrayBuffer.empty
  private val operations: mutable.ArrayBuffer[OriginOperationCapture] =
    mutable.ArrayBuffer.empty
  private val lexicalNames = new IdentityHashMap[AnyRef, String]()

  /** Exact compiler-captured lexical binders outrank reflection and nearby-source heuristics. */
  def captureLexicalName(value: AnyRef, components: Vector[String]): Unit =
    if !lexicalNames.containsKey(value) then
      lexicalNames.put(value, components.map(cleanIdentifier(_, "member")).mkString("_"))

  private val internalSourceFiles = Set(
    "ElaborationConstructionKernel.scala",
    "ConstructionRecords.scala",
    "ConstructionSession.scala",
    "ConstructionExpressionFacts.scala",
    "ConstructionInterfaceLayout.scala",
    "DigitalAggregateContract.scala",
    "AnalogUserFunctionRuntime.scala",
    "StructuralIfRuntime.scala",
    "SequentialRuntime.scala",
    "FlatFsmRuntime.scala",
    "FsmDefinitionRuntime.scala",
    "Area.scala",
    "SemanticOriginKernel.scala"
  )
  private val apiSourceFiles = Set(
    "CandidateApi.scala",
    "DigitalAggregateApi.scala",
    "AnalogUserFunctionApi.scala",
    "ContinuousTimeCandidateApi.scala",
    "CoreSemanticsCandidateApi.scala",
    "PipelineInterfaceCandidateApi.scala",
    "CompilerApi.scala",
    "FsmDefinitionApi.scala",
    "FsmCompositionApi.scala",
    "FsmTimingApi.scala",
    "GeneratedAreaApi.scala"
  )
  private val ignoredSourceSegments = Set(
    ".git",
    ".bloop",
    ".bsp",
    ".mill",
    ".scala-build",
    ".validation",
    "out",
    "target"
  )
  private val declarationPattern =
    """^\s*(?:(?:private(?:\[[^\]]+\])?|protected(?:\[[^\]]+\])?|final|lazy|override|inline|transparent)\s+)*(?:val|var)\s+([A-Za-z_][A-Za-z0-9_]*)\b""".r

  def captureModule(
      handle: Long,
      module: Module,
      className: String,
      parent: Option[Long]
  ): Unit =
    modules += OriginModuleCapture(handle, module, className, parent, captureSite())

  def captureDomain(
      module: Long,
      index: Int,
      domain: ClockDomain,
      name: String,
      kind: String
  ): Unit =
    domains += OriginDomainCapture(module, index, domain, name, kind, captureSite())

  def captureDeclaration(
      module: Long,
      index: Int,
      value: AnyRef,
      kind: String,
      explicitName: Option[String]
  ): Unit =
    declarations += OriginDeclarationCapture(
      module,
      index,
      value,
      kind,
      explicitName,
      captureSite()
    )

  def captureExpression(
      module: Long,
      index: Int,
      value: AnyRef,
      operands: Vector[Any]
  ): Unit =
    expressions += OriginExpressionCapture(
      module,
      index,
      value,
      operands,
      captureSite()
    )

  def reserveExpression(
      module: Long,
      index: Int,
      value: AnyRef,
      operands: Vector[Any]
  ): Int =
    val slot = expressions.size
    captureExpression(module, index, value, operands)
    slot

  def completeReservedExpression(
      slot: Int,
      module: Long,
      index: Int,
      value: AnyRef,
      operands: Vector[Any]
  ): Unit =
    val capture = expressions(slot)
    require(
      capture.module == module && capture.index == index && (capture.value eq value),
      "reserved expression lost its original source capture"
    )
    expressions(slot) = capture.copy(operands = operands)

  /** Read-only check against the canonical retained source slot, no capture/replay. */
  def retainedExpressionSlot(
      slot: Int,
      module: Long,
      index: Int,
      value: AnyRef,
      operands: Vector[Any]
  ): Boolean =
    slot >= 0 && slot < expressions.size && {
      val capture = expressions(slot)
      capture.module == module && capture.index == index && (capture.value eq value) &&
      capture.operands == operands
    }

  def captureInstance(
      parent: Long,
      ordinal: Int,
      child: Long,
      instance: AnyRef,
      childModule: Module
  ): Unit =
    instances += OriginInstanceCapture(
      parent,
      ordinal,
      child,
      instance,
      childModule,
      captureSite()
    )

  // A single synchronous operation owns this result. It is neither a cache nor a
  // naming capability: only this builder can create or consume its private state.
  final class PreparedOperationSource private[SemanticOriginBuilder] (
      private[SemanticOriginBuilder] val module: Long,
      private[SemanticOriginBuilder] val kind: String,
      private[SemanticOriginBuilder] val values: Vector[Any]
  ):
    private[SemanticOriginBuilder] val builder = SemanticOriginBuilder.this
    private[SemanticOriginBuilder] var sourceFallback = false
    private[SemanticOriginBuilder] var site: Option[KernelSourceSite] = None
    private[SemanticOriginBuilder] var lines = Map.empty[Path, ResolutionSourceLines]
    private[SemanticOriginBuilder] var prefixComplete = false
    private[SemanticOriginBuilder] var sourceTextRead = false
    private[SemanticOriginBuilder] var reusable = false
    private[SemanticOriginBuilder] var consumed = false
    def canReuse: Boolean = reusable

  private var preparingOperation: Option[PreparedOperationSource] = None

  def prepareOperationSource(module: Long, kind: String, values: Vector[Any])
      : PreparedOperationSource =
    // Session calls this only under its exact registered-construction owner guard.
    ConstructionKernel.chargeBoundaryWork(12L)
    new PreparedOperationSource(module, kind, values)

  // Same one-frame wrapper as the original no-argument source capture. Never
  // put a guard, callback or Option traversal around this stack-sensitive call.
  def captureSemanticSource(prepared: PreparedOperationSource): Option[SourceSpan] =
    val previous = preparingOperation
    preparingOperation = Some(prepared)
    try
      val site = captureSite()
      prepared.site = Some(site)
      site.span
    finally preparingOperation = previous

  def consumeOperationSource(
      prepared: PreparedOperationSource,
      module: Long,
      kind: String,
      values: Vector[Any]
  ): Unit =
    // Consumed even when identity, freshness or budget validation fails. The
    // Session guard preserves its already appended operation and poisons the owner.
    if prepared.consumed then throw new IllegalStateException("operation source already consumed")
    prepared.consumed = true
    ConstructionKernel.chargeBoundaryWork(8L + kind.length.toLong)
    if !(prepared.builder eq this) || prepared.module != module || prepared.kind != kind ||
      !(prepared.values eq values) ||
      !ConstructionKernel.constructionCapturePrefixAllowed(this)
    then throw new IllegalStateException("operation source authority changed")
    if !prepared.reusable then throw new IllegalStateException("operation source is not reusable")
    else
      val previousWork = resolutionWork
      val previousLines = resolutionSourceLines
      resolutionWork = Some(units => ConstructionKernel.chargeBoundaryWork(units.toLong))
      resolutionSourceLines = Some(Map.empty)
      try
        chargeWork(3L)
        prepared.lines.foreach: (path, original) =>
          chargeWork(3L)
          val current = readSourceLines(path)
          chargeWork(7L)
          val currentEntry = resolutionSourceLines.flatMap(_.get(path))
          if !currentEntry.exists(_.stamp == original.stamp) || current.size != original.lines.size
          then sourceChanged()
          var index = 0
          while index < current.size do
            val left = original.lines(index)
            val right = current(index)
            chargeWork(3L + left.length.toLong + right.length.toLong)
            if left != right then sourceChanged()
            index += 1
        chargeWork(2L)
        operations += OriginOperationCapture(module, kind, values, prepared.site.get)
      catch case aborted: ResolutionWorkAborted => throw aborted.original
      finally
        resolutionSourceLines = previousLines
        resolutionWork = previousWork

  def captureOperation(module: Long, kind: String, values: Vector[Any]): Unit =
    operations += OriginOperationCapture(module, kind, values, captureSite())

  /** Capture the current user-facing Scala source span for a semantic operation.
    *
    * Increment 32 uses the same stack-walk policy as declarations and expressions so public
    * equation/contribution records retain source provenance instead of manufacturing synthetic
    * locations in their standalone witness.
    */
  def captureSemanticSource(): Option[SourceSpan] = captureSite().span

  private def captureSite(): KernelSourceSite =
    // Source capture may occur during structural materialization, outside resolveWithWork.
    // Reuse its existing meter if present; otherwise borrow only the active boundary debit.
    // No owner, budget, or transaction-wide source-bytes cache is created here.
    val previousWork = resolutionWork
    val previousLines = resolutionSourceLines
    val installed = resolutionWork.isEmpty && ConstructionKernel.boundaryWorkActive
    if installed then
      resolutionWork = Some(units => ConstructionKernel.chargeBoundaryWork(units.toLong))
      resolutionSourceLines = Some(Map.empty)
    try
      chargeWork() // Before stack-walk/stream/vector allocation.
      val frames = walker.walk[java.util.List[StackWalker.StackFrame]](stream =>
        if installed && ConstructionKernel.constructionCapturePrefixAllowed(this) then
          cachedConstructorPrefix(stream)
        else if resolutionWork.isEmpty then stream.limit(96).toList
        else
          stream.limit(96).peek(frame =>
            // Fixed bounded stack passes plus actual frame strings used by selection.
            chargeWork(16L)
            if resolutionWork.nonEmpty then
              chargeWork(4L *
                (frame.getClassName.length.toLong +
                  Option(frame.getFileName).fold(0L)(_.length.toLong) +
                  frame.getMethodName.length.toLong + 1L))
          ).toList
      ).asScala.toVector
      val userFrames = frames.zipWithIndex.filter: (frame, _) =>
        isUserFrame(frame)
      val locatedConstructor = (candidate: (StackWalker.StackFrame, Int)) =>
        val (frame, _) = candidate
        frame.getMethodName == "<init>" &&
        Option(frame.getFileName)
          .flatMap(fileName => locateSource(fileName, frame.getClassName))
          .nonEmpty
      val userCandidate = userFrames.find(locatedConstructor).map: constructor =>
        // A lexical body executes before its owning constructor returns. Its nearest
        // same-class frame carries the authored statement location; the constructor
        // frame only identifies the call that entered the body.
        userFrames.find: candidate =>
          candidate._2 < constructor._2 &&
            candidate._1.getClassName == constructor._1.getClassName
        .getOrElse(constructor)
      .orElse(userFrames.headOption)
      val userIndex = userCandidate.map(_._2).getOrElse(-1)
      val user = userCandidate.map(_._1)
      val api =
        if userIndex > 0 then frames.take(userIndex).reverseIterator.find(isApiFrame)
        else None
      val located = user.flatMap: frame =>
        Option(frame.getFileName).map(fileName =>
          locateSpan(fileName, frame.getClassName, frame.getLineNumber)
        )
      preparingOperation.foreach: prepared =>
        chargeWork(5L)
        val observed = resolutionSourceLines.getOrElse(Map.empty)
        prepared.reusable = installed && prepared.prefixComplete && !prepared.sourceTextRead &&
          !prepared.sourceFallback &&
          located.nonEmpty && observed.nonEmpty
        if prepared.reusable then
          chargeWork(observed.size.toLong + 1L)
          prepared.lines = observed
      KernelSourceSite(
        located.map(_._1),
        api.map(frame => normalizeOwner(frame.getClassName)).getOrElse("user"),
        api.map(frame => normalizeOperation(frame.getMethodName)).getOrElse("construct"),
        located.flatMap(_._2)
      )
    catch case aborted: ResolutionWorkAborted if installed => throw aborted.original
    finally
      if installed then
        resolutionSourceLines = previousLines
        resolutionWork = previousWork

  private def isUserFrame(frame: StackWalker.StackFrame): Boolean =
    val owner = frame.getClassName
    Option(frame.getFileName).exists: fileName =>
      fileName.endsWith(".scala") &&
        !internalSourceFiles.contains(fileName) &&
        !apiSourceFiles.contains(fileName) &&
        !owner.startsWith("scala.") &&
        !owner.startsWith("java.") &&
        !owner.startsWith("jdk.") &&
        !owner.startsWith("sun.") &&
        !owner.startsWith("utest.") &&
        !owner.startsWith("mill.")

  private def isApiFrame(frame: StackWalker.StackFrame): Boolean =
    Option(frame.getFileName).exists(apiSourceFiles.contains)

  private def normalizeOwner(raw: String): String =
    chargeWork(raw.length.toLong + 1L)
    val simple = raw.split("\\.").lastOption.getOrElse(raw)
    val cleaned = simple
      .replace("$package", "")
      .stripSuffix("$")
      .split("\\$")
      .filter(_.nonEmpty)
      .lastOption
      .getOrElse("api")
    cleanIdentifier(cleaned, "api")

  private def normalizeOperation(raw: String): String =
    chargeWork(raw.length.toLong + 1L)
    val cleaned =
      if raw == "<init>" then "construct"
      else
        raw
          .replace("$extension", "")
          .replaceAll("\\$default\\$.*$", "")
          .replaceAll("[^A-Za-z0-9_]+", "_")
    cleanIdentifier(cleaned, "operation")

  private def locateSpan(
      fileName: String,
      ownerClass: String,
      observedLine: Int
  ): (SourceSpan, Option[String]) =
    val line = math.max(1, observedLine)
    val source = locateSource(fileName, ownerClass)
    source match
      case Some(path) =>
        try
          val lines = readSourceLines(path)
          val currentIndex = math.min(line - 1, math.max(0, lines.size - 1))
          val binding = bindingNear(lines, currentIndex)
          val startIndex = binding.map(_._1).getOrElse(currentIndex)
          val startText = lines.lift(startIndex).getOrElse("")
          val endText = lines.lift(currentIndex).getOrElse(startText)
          chargeWork(startText.length.toLong + 1L)
          val startColumn = startText.indexWhere(character => !character.isWhitespace) match
            case -1 => 1
            case value => value + 1
          val endColumn = math.max(1, endText.length + 1)
          SourceSpan(
            repositoryPath(path),
            startIndex + 1,
            startColumn,
            currentIndex + 1,
            endColumn
          ) -> binding.map(_._2)
        catch
          case _: Exception =>
            preparingOperation.foreach: prepared =>
              chargeWork()
              prepared.sourceFallback = true
            SourceSpan(fileName, line, 1, line, 1) -> None
      case None =>
        preparingOperation.foreach: prepared =>
          chargeWork()
          prepared.sourceFallback = true
        SourceSpan(fileName, line, 1, line, 1) -> None

  private def bindingNear(
      lines: Vector[String],
      currentIndex: Int
  ): Option[(Int, String)] =
    if resolutionWork.nonEmpty then chargeWork(lines(currentIndex).length.toLong + 1L)
    declarationPattern
      .findFirstMatchIn(lines(currentIndex))
      .map(matched => currentIndex -> matched.group(1))
      .filter(_._2 != "_")

  private val sourcePackagePattern =
    (raw"(?m)^\s*package\s+([A-Za-z_][A-Za-z0-9_.]*)(?:\s*:\s*)?" + "$").r

  private def ownerPackage(ownerClass: String): Option[String] =
    chargeWork(ownerClass.length.toLong + 1L)
    val separator = ownerClass.lastIndexOf('.')
    if separator <= 0 then None else Some(ownerClass.substring(0, separator))

  private def ownerTypeName(ownerClass: String): Option[String] =
    chargeWork(ownerClass.length.toLong + 1L)
    ownerClass
      .split("\\.")
      .lastOption
      .flatMap(
        _.split("\\$").find(segment => segment.nonEmpty && segment != "package")
      )
      .map(_.takeWhile(character => Character.isJavaIdentifierPart(character)))
      .filter(_.nonEmpty)

  private def sourceIdentityScore(path: Path, ownerClass: String): Int =
    try
      val source =
        if resolutionWork.isEmpty then Files.readString(path)
        else readSourceText(path)
      // Fixed package/type regex passes; structural character accounting, not CPU time.
      chargeWork(source.length.toLong * 4L + 1L)
      val ownerPackageValue = ownerPackage(ownerClass)
      val ownerTypeValue = ownerTypeName(ownerClass)
      val packages = sourcePackagePattern
        .findAllMatchIn(source)
        .map: matched =>
          chargeWork(matched.group(1).length.toLong + 1L)
          matched.group(1)
        .toSet
      val packageScore = ownerPackageValue
        .filter(packages.contains)
        .map(_ => 500)
        .getOrElse(0)
      val typeScore = ownerTypeValue
        .map: typeName =>
          val declaration =
            (
              "(?m)^\\s*(?:(?:final|sealed|abstract|case)\\s+)*" +
                "(?:class|object|trait|enum)\\s+" +
                java.util.regex.Pattern.quote(typeName) +
                "\\b"
            ).r
          if declaration.findFirstIn(source).nonEmpty then 1000 else 0
        .getOrElse(0)
      val pathScore = ownerPackageValue
        .map(_.replace('.', '/'))
        .filter: packagePath =>
          val candidate = repositoryPath(path)
          candidate.contains(s"/$packagePath/") ||
          candidate.startsWith(s"$packagePath/")
        .map(_ => 250)
        .getOrElse(0)
      packageScore + typeScore + pathScore
    catch case _: Exception => 0

  private val repositoryRoot: Path =
    val workingDirectory =
      Path.of(System.getProperty("user.dir", ".")).toAbsolutePath.normalize()
    val environmentRoots = Vector(
      "NODAL_WORKSPACE",
      "GITHUB_WORKSPACE",
      "BUILD_WORKSPACE_DIRECTORY"
    ).flatMap: name =>
      Option(System.getenv(name)).flatMap: value =>
        scala.util.Try(Path.of(value).toAbsolutePath.normalize()).toOption
    val ancestorRoots = Iterator
      .iterate(Option(workingDirectory))(
        _.flatMap(path => Option(path.getParent))
      )
      .takeWhile(_.nonEmpty)
      .flatten
      .toVector
    (environmentRoots ++ ancestorRoots)
      .distinct
      .find: candidate =>
        Files.isRegularFile(candidate.resolve("build.mill")) &&
          Files.isDirectory(candidate.resolve("core/scala")) &&
          Files.isDirectory(candidate.resolve("docs"))
      .getOrElse(workingDirectory)

  // Constructor selection and span lookup share one transaction-owned cache, including misses.
  // Synthesized region captures must not re-walk the source tree at every dependency edge.
  private def locateSource(fileName: String, ownerClass: String): Option[Path] =
    chargeWork(fileName.length.toLong + ownerClass.length.toLong + 1L)
    sourceCache.getOrElseUpdate(
      fileName -> ownerClass, {
        val found = findSource(fileName, ownerClass)
        chargeWork(fileName.length.toLong + ownerClass.length.toLong + 1L)
        found.foreach(chargeSourcePath)
        found
      }
    )

  private def chargeSourcePath(path: Path): Unit =
    if resolutionWork.nonEmpty then
      chargeWork(path.getNameCount.toLong + path.toString.length.toLong + 1L)

  private def findSource(fileName: String, ownerClass: String): Option[Path] =
    val root = repositoryRoot
    chargeSourcePath(root)
    chargeWork(5L) // Root directory and repository marker checks.
    if !Files.isDirectory(root) then None
    else
      val candidates = mutable.ArrayBuffer.empty[Path]
      // Ignore only this repository's generated scratch subtree. An explicitly selected
      // external root may itself live under .work, and authored nested .work paths remain valid.
      val generatedScratch = Option.when(
        Files.isRegularFile(root.resolve("build.mill")) &&
          Files.isDirectory(root.resolve("core/scala")) && Files.isDirectory(root.resolve("docs"))
      )(root.resolve(".work"))
      try
        val _ = Files.walkFileTree(
          root,
          new SimpleFileVisitor[Path]:
            override def preVisitDirectory(
                directory: Path,
                attributes: BasicFileAttributes
            ): FileVisitResult =
              chargeSourcePath(directory)
              if directory != root &&
                attributes.isDirectory &&
                (generatedScratch.contains(directory) ||
                  ignoredSourceSegments.contains(directory.getFileName.toString))
              then FileVisitResult.SKIP_SUBTREE
              else FileVisitResult.CONTINUE

            override def visitFile(
                file: Path,
                attributes: BasicFileAttributes
            ): FileVisitResult =
              chargeSourcePath(file)
              if attributes.isRegularFile && file.getFileName.toString == fileName
              then
                chargeWork()
                candidates += file
              FileVisitResult.CONTINUE

            override def visitFileFailed(
                _file: Path,
                _exception: java.io.IOException
            ): FileVisitResult =
              chargeWork()
              FileVisitResult.CONTINUE

            override def postVisitDirectory(
                _directory: Path,
                _exception: java.io.IOException
            ): FileVisitResult =
              chargeWork()
              FileVisitResult.CONTINUE
        )
      catch case _: java.io.IOException => ()
      if resolutionWork.nonEmpty then
        chargeWork(candidates.size.toLong * 8L)
        candidates.foreach: path =>
          // Sorting comparisons can revisit every candidate path; prepaid conservatively.
          chargeWork(candidates.size.toLong * (path.toString.length.toLong + 1L))
      val ordered = candidates.toVector
        .filter(path => sourceSegments(path).intersect(ignoredSourceSegments).isEmpty)
        .sortBy(path => (path.getNameCount, repositoryPath(path)))
      ordered match
        case Vector() => None
        case Vector(single) => Some(single)
        case _ =>
          val scored = ordered.map(path => path -> sourceIdentityScore(path, ownerClass))
          val bestScore = scored.map(_._2).max
          val winners = scored.collect:
            case (path, score) if score == bestScore => path
          if bestScore > 0 && winners.size == 1 then winners.headOption else None

  private def sourceSegments(path: Path): Set[String] =
    chargeSourcePath(path)
    path.iterator().asScala.map(_.toString).toSet

  private def repositoryPath(path: Path): String =
    chargeSourcePath(path)
    chargeSourcePath(repositoryRoot)
    val root = repositoryRoot
    val relative =
      try root.relativize(path.toAbsolutePath.normalize()).toString
      catch case _: IllegalArgumentException => path.getFileName.toString
    relative.replace('\\', '/')

  private def cleanIdentifier(raw: String, fallback: String): String =
    chargeWork(raw.length.toLong + 1L)
    val normalized = raw
      .replaceAll("[^A-Za-z0-9_]+", "_")
      .replaceAll("_+", "_")
      .stripPrefix("_")
      .stripSuffix("_")
    val selected = if normalized.isEmpty then fallback else normalized
    if selected.headOption.exists(_.isDigit) then s"n_$selected" else selected

  private def generatedStem(raw: String): String =
    cleanIdentifier(
      canonicalCopy(raw.length.toLong + 1L)(raw
        .replaceAll("([a-z0-9])([A-Z])", "$1_$2")
        .toLowerCase),
      "generated"
    )

  private def lowerInitial(raw: String): String = canonicalCopy(raw.length.toLong + 1L):
    raw.headOption match
      case Some(head) => head.toLower.toString + raw.drop(1)
      case None => raw

  private def stableDigest(parts: String*): String =
    parts.foreach(part => chargeWork(part.length.toLong + 1L))
    val bytes = MessageDigest
      .getInstance("SHA-256")
      .digest(parts.mkString("\u001f").getBytes(StandardCharsets.UTF_8))
    bytes.iterator.map(byte => f"${byte & 0xff}%02x").mkString

  private def sourceFingerprint(site: KernelSourceSite): String =
    canonicalCopy(
      64L + site.span.fold(0L)(_.path.length.toLong) + site.apiOwner.length.toLong +
        site.apiOperation.length.toLong + site.bindingName.fold(0L)(_.length.toLong)
    )(site.fingerprint)

  private def sourceSuffix(site: KernelSourceSite): String =
    site.span match
      case Some(source) =>
        val file = canonicalCopy(source.path.length.toLong + 1L)(
          Path.of(source.path).getFileName.toString.stripSuffix(".scala")
        )
        s"${generatedStem(file)}_l${source.line}_${stableDigest(sourceFingerprint(site)).take(8)}"
      case None => stableDigest(sourceFingerprint(site)).take(12)

  private def memberFields(clazz: Class[?]): Vector[Field] =
    val classes = Iterator
      .iterate(Option(clazz))(_.flatMap: current =>
        chargeWork()
        Option(current.getSuperclass))
      .takeWhile(_.nonEmpty)
      .flatten
      .toVector
    val fields = classes.flatMap: current =>
      chargeWork()
      val declared = current.getDeclaredFields
      chargeWork(declared.length.toLong)
      declared.toVector
    chargeSquare(fields.size.toLong)
    fields
      .filterNot(field =>
        field.isSynthetic ||
          Modifier.isStatic(field.getModifiers) ||
          field.getName.startsWith("$") ||
          field.getName.contains("$outer") ||
          field.getName.startsWith("bitmap$")
      )
      .sortBy(field => (field.getDeclaringClass.getName, field.getName))

  private def discoverMemberNames(): IdentityHashMap[AnyRef, String] =
    val discovered = new IdentityHashMap[AnyRef, String]()
    val semanticObjects = new IdentityHashMap[AnyRef, java.lang.Boolean]()
    val preferredNames = new IdentityHashMap[AnyRef, String]()
    modules.foreach(capture =>
      canonicalProbe(semanticObjects.put(capture.module, java.lang.Boolean.TRUE))
    )
    domains.foreach(capture =>
      canonicalProbe(semanticObjects.put(capture.domain, java.lang.Boolean.TRUE))
    )
    declarations.foreach: capture =>
      canonicalVisit()
      semanticObjects.put(capture.value, java.lang.Boolean.TRUE)
      declarationBinding(capture.site, capture.kind).foreach: name =>
        preferredNames.put(capture.value, name)
    expressions.foreach(capture =>
      canonicalProbe(semanticObjects.put(capture.value, java.lang.Boolean.TRUE))
    )
    instances.foreach: capture =>
      canonicalVisit()
      semanticObjects.put(capture.instance, java.lang.Boolean.TRUE)
      semanticObjects.put(capture.childModule, java.lang.Boolean.TRUE)
      instanceBinding(capture.site).foreach: name =>
        preferredNames.put(capture.instance, name)

    def retain(value: AnyRef, candidate: String): Unit =
      canonicalVisit()
      if semanticObjects.containsKey(value) then
        val preferred = Option(preferredNames.get(value))
        Option(discovered.get(value)) match
          case Some(existing) =>
            if preferred.contains(candidate) ||
              (!preferred.contains(existing) && candidate < existing)
            then discovered.put(value, candidate)
          case None => discovered.put(value, candidate)

    def visit(value: Any, candidate: String): Unit =
      chargeWork(candidate.length.toLong + 1L)
      value match
        case option: Option[?] =>
          option.foreach(element => visit(element, candidate))
        case indexed: IndexedSeq[?] =>
          if resolutionWork.isEmpty then
            indexed.zipWithIndex.foreach: (element, index) =>
              visit(element, s"${candidate}_$index")
          else
            indexed.iterator.zipWithIndex.foreach: (element, index) =>
              visit(element, s"${candidate}_$index")
        case list: List[?] =>
          if resolutionWork.isEmpty then
            list.zipWithIndex.foreach: (element, index) =>
              visit(element, s"${candidate}_$index")
          else
            list.iterator.zipWithIndex.foreach: (element, index) =>
              visit(element, s"${candidate}_$index")
        case array: Array[?] =>
          if resolutionWork.isEmpty then
            array.zipWithIndex.foreach: (element, index) =>
              visit(element, s"${candidate}_$index")
          else
            array.iterator.zipWithIndex.foreach: (element, index) =>
              visit(element, s"${candidate}_$index")
        case reference: AnyRef => retain(reference, candidate)
        case _ => ()

    val orderedModules =
      if !canonicalAccounting then modules.sortBy(_.handle)
      else canonicalSort(canonicalVector(modules))(_.handle)
    orderedModules.foreach: capture =>
      canonicalVisit()
      memberFields(capture.module.getClass).foreach: field =>
        chargeWork()
        try
          if field.trySetAccessible() then
            Option(field.get(capture.module)).foreach: value =>
              val candidate = cleanIdentifier(field.getName, "member")
              visit(value, candidate)
        catch
          case _: ReflectiveOperationException => ()
          case _: RuntimeException => ()
    discovered

  private def sourceChanged(): Nothing =
    throw new ResolutionWorkAborted(new IllegalStateException(
      "semantic source changed during metered resolution"
    ))

  private def sourceStamp(path: Path): SourceFileStamp =
    chargeWork() // Stat operation; paid before filesystem access.
    val attributes = Files.readAttributes(path, classOf[BasicFileAttributes])
    SourceFileStamp(
      attributes.size(),
      attributes.lastModifiedTime(),
      Option(attributes.fileKey()),
      attributes.isRegularFile()
    )

  private def readSourceLines(path: Path): Vector[String] =
    if resolutionWork.isEmpty then Files.readAllLines(path).asScala.toVector
    else
      // No realpath or filesystem walk. Pay lexical path work before normalization.
      chargeWork(path.getNameCount.toLong + 1L)
      chargeWork(path.toString.length.toLong + 1L)
      val key = path.normalize()
      chargeWork() // Map lookup.
      val cached = resolutionSourceLines.flatMap(_.get(key))
      val before =
        try sourceStamp(key)
        catch
          case _: java.io.IOException if cached.nonEmpty => sourceChanged()
          case _: SecurityException if cached.nonEmpty => sourceChanged()
      cached match
        case Some(entry) =>
          if entry.stamp != before then sourceChanged()
          entry.lines
        case None =>
          if !before.regular then Vector.empty
          else
            val lines = readBoundedSourceLines(key, before.size)
            val after =
              try sourceStamp(key)
              catch
                case _: java.io.IOException => sourceChanged()
                case _: SecurityException => sourceChanged()
            if before != after then sourceChanged()
            // First-read bytes bound retained text; pay retained key and entry before storage.
            chargeWork(key.getNameCount.toLong + key.toString.length.toLong + 1L)
            chargeWork()
            resolutionSourceLines = resolutionSourceLines.map(
              _.updated(key, ResolutionSourceLines(before, lines))
            )
            lines

  // Prepay advertised UTF-8 bytes and a separate EOF probe (no size+1 overflow).
  // The stream refuses growth; matching metadata is still only a best-effort snapshot.
  private def readBoundedSourceLines(path: Path, advertised: Long): Vector[String] =
    withBoundedSourceReader(path, advertised)(_.lines().iterator().asScala.toVector)

  private def readSourceText(path: Path): String =
    preparingOperation.foreach: prepared =>
      chargeWork()
      prepared.sourceTextRead = true
    chargeSourcePath(path)
    val before = sourceStamp(path)
    if !before.regular then throw new java.io.IOException("source is not a regular file")
    val text = withBoundedSourceReader(path, before.size): reader =>
      val result = new java.lang.StringBuilder
      val buffer = new Array[Char](8192)
      var count = reader.read(buffer)
      while count != -1 do
        val _ = result.append(buffer, 0, count)
        count = reader.read(buffer)
      result.toString
    val after =
      try sourceStamp(path)
      catch
        case _: java.io.IOException => sourceChanged()
        case _: SecurityException => sourceChanged()
    if before != after then sourceChanged()
    text

  private def withBoundedSourceReader[A](path: Path, advertised: Long)(
      read: java.io.BufferedReader => A
  ): A =
    chargeWork(advertised)
    chargeWork()
    val input = new java.io.FilterInputStream(Files.newInputStream(path)):
      private var remaining = advertised
      private var checkedEnd = false

      private def checkEnd(): Int =
        if !checkedEnd then
          checkedEnd = true
          if in.read() != -1 then
            throw new ResolutionWorkAborted(new IllegalStateException(
              "semantic source grew beyond its prepaid byte bound"
            ))
        -1

      override def read(): Int =
        if remaining == 0L then checkEnd()
        else
          val value = in.read()
          if value >= 0 then remaining -= 1L
          value

      override def read(bytes: Array[Byte], offset: Int, length: Int): Int =
        if length == 0 then 0
        else if remaining == 0L then checkEnd()
        else
          val count = in.read(bytes, offset, math.min(length.toLong, remaining).toInt)
          if count > 0 then remaining -= count.toLong
          count

    val reader = new java.io.BufferedReader(new java.io.InputStreamReader(
      input,
      StandardCharsets.UTF_8.newDecoder()
    ))
    var primary: Option[Throwable] = None
    try read(reader)
    catch
      case failure: Throwable =>
        primary = Some(failure)
        throw failure
    finally
      try reader.close()
      catch
        case closing: Throwable => primary match
            case Some(failure) => if closing ne failure then failure.addSuppressed(closing)
            case None => throw closing

  private def sourceLines(
      site: KernelSourceSite
  ): Option[(Vector[String], Int)] =
    site.span.flatMap: source =>
      if resolutionWork.nonEmpty then chargeWork(source.path.length.toLong + 1L)
      val capturedPath = Path.of(source.path)
      val path =
        if resolutionWork.nonEmpty then
          chargeWork(repositoryRoot.toString.length.toLong + source.path.length.toLong +
            capturedPath.getNameCount.toLong + 1L)
          if capturedPath.isAbsolute then capturedPath else repositoryRoot.resolve(capturedPath)
        else if capturedPath.isAbsolute then capturedPath.normalize()
        else repositoryRoot.resolve(capturedPath).normalize()
      if resolutionWork.isEmpty && !Files.isRegularFile(path) then None
      else
        try
          val lines = readSourceLines(path)
          if lines.isEmpty then None
          else
            val observedIndex = math.max(0, math.min(lines.size - 1, source.endLine - 1))
            Some(lines -> observedIndex)
        catch case _: Exception => None

  // Parser accounting charges each visited line/context character for the fixed
  // declaration regex and each token search. These are structural scan units,
  // assuming this bounded, fixed regex; not a universal regex-engine CPU bound.
  private def declarationContext(lines: Vector[String], index: Int): String =
    chargeWork(5L) // At most five line references copied by slice.
    val tail = lines.slice(index, math.min(lines.size, index + 5))
    val continuation = tail.headOption.toVector ++
      tail.drop(1).takeWhile: line =>
        chargeWork(line.length.toLong + 1L)
        declarationPattern.findFirstMatchIn(line).isEmpty
    if resolutionWork.nonEmpty then
      continuation.foreach(line => chargeWork(line.length.toLong + 1L))
    continuation.mkString(" ")

  private def bindingForTokens(
      site: KernelSourceSite,
      tokens: Vector[String],
      radius: Int
  ): Option[String] =
    sourceLines(site).flatMap: (lines, observedIndex) =>
      val lower = math.max(0, observedIndex - radius)
      val upper = math.min(lines.size - 1, observedIndex + radius)
      chargeSquare(upper.toLong - lower.toLong + 1L)
      val indexes = (lower to upper).sortBy: index =>
        (math.abs(index - observedIndex), if index <= observedIndex then 0 else 1)
      indexes.iterator
        .flatMap: index =>
          chargeWork(lines(index).length.toLong + 1L)
          declarationPattern.findFirstMatchIn(lines(index)).flatMap: matched =>
            val context = declarationContext(lines, index)
            if tokens.isEmpty || tokens.exists(token =>
                chargeWork(context.length.toLong + token.length.toLong + 1L)
                context.contains(token)
              )
            then
              Some(matched.group(1))
            else None
        .nextOption()
        .flatMap: name =>
          if name == "_" then None else Some(cleanIdentifier(name, "value"))

  private def declarationTokens(kind: String, site: KernelSourceSite): Vector[String] =
    kind match
      case "register" =>
        val owner = generatedStem(site.apiOwner)
        if owner.contains("reg_next") || owner.contains("regnext") then Vector("RegNext(")
        else Vector("Reg(")
      case "parameter" => Vector("param(")
      case "input" | "analog-input" => Vector("in(")
      case "output" | "analog-output" => Vector("out(")
      case "analog-inout" => Vector("inout(")
      case "analog-node" | "analog-node-array" => Vector("node(")
      case "wire" => Vector("wire(")
      case "variable" => Vector("variable(")
      case "memory" => Vector("Mem(")
      case "interface-port" => Vector("interfacePort(")
      case "interface-array" => Vector("interfaceArray(")
      case "digital-inout" => Vector("digitalInout(")
      case "conservative-terminal" => Vector("terminal(")
      case "analog-signal" => Vector("AnalogSignal.")
      case _ => Vector.empty

  private def declarationBinding(site: KernelSourceSite, kind: String): Option[String] =
    val tokens = declarationTokens(kind, site)
    site.bindingName
      .map(cleanIdentifier(_, kind))
      .orElse(bindingForTokens(site, tokens, if tokens.nonEmpty then 12 else 0))

  private def instanceBinding(site: KernelSourceSite): Option[String] =
    bindingForTokens(site, Vector("instance("), 12)
      .orElse(site.bindingName.map(cleanIdentifier(_, "instance")))

  private def expressionBinding(site: KernelSourceSite): Option[String] =
    bindingForTokens(site, Vector.empty, 0)

  private def memberBinding(
      members: IdentityHashMap[AnyRef, String],
      value: AnyRef
  ): Option[String] =
    Option(lexicalNames.get(value)).orElse(Option(members.get(value)))
      .map(cleanIdentifier(_, "value"))

  private def allocate(
      candidates: Vector[NameCandidate],
      reserved: Set[String] = Set.empty
  ): Map[String, ResolvedName] =
    val used =
      if !canonicalAccounting then mutable.HashSet.from(reserved)
      else mutable.HashSet.from(canonicalIterator(reserved.iterator))
    val resolved = mutable.HashMap.empty[String, ResolvedName]
    canonicalSort(candidates)(candidate => (candidate.base, candidate.key)).foreach: candidate =>
      canonicalVisit()
      val base = cleanIdentifier(candidate.base, "value")
      var salt = 0
      var selected = base
      while canonicalProbe(used.contains(selected)) do
        chargeWork()
        selected = s"${base}_${stableDigest(candidate.key, salt.toString).take(8)}"
        salt += 1
      used += selected
      resolved.update(candidate.key, ResolvedName(selected, candidate.provenance))
    canonicalMap(resolved)

  /** Allocate snapshot-only effect identities in the completed semantic namespace. Authored
    * declarations and expressions keep their names regardless of whether they precede an effect.
    * Reuse the canonical allocator; this creates no second reservation registry.
    */
  def auxiliaryPaths(
      semantic: SemanticOriginResult,
      bases: Map[Long, Vector[String]]
  ): Map[Long, Vector[String]] =
    if bases.isEmpty then Map.empty
    else
      val owners = semantic.modulePaths.map(_.swap)
      val paths = semantic.modulePaths.valuesIterator ++ semantic.domainPaths.valuesIterator ++
        semantic.declarationPaths.valuesIterator ++ semantic.expressionPaths.valuesIterator ++
        semantic.instancePaths.valuesIterator
      val namespaces = paths.flatMap: path =>
        val boundary = path.lastIndexOf('.')
        owners.get(path.take(boundary)).map(owner => owner -> path.drop(boundary + 1))
      .toVector.groupMap(_._1)(_._2).view.mapValues(_.toSet).toMap
      bases.map: (module, names) =>
        val prefix = semantic.modulePaths(module) + "."
        val allocated = allocate(
          names.zipWithIndex.map((base, index) =>
            NameCandidate(index.toString, base, "auxiliary-effect")
          ),
          namespaces.getOrElse(module, Set.empty)
        )
        module -> names.indices.map(index => prefix + allocated(index.toString).name).toVector

  private def objectPath(
      paths: IdentityHashMap[AnyRef, String],
      value: Any
  ): Option[String] =
    chargeWork()
    value match
      case option: Option[?] => option.iterator.flatMap(objectPath(paths, _)).nextOption()
      case sequence: Seq[?] => sequence.iterator.flatMap(objectPath(paths, _)).nextOption()
      case reference: AnyRef => Option(paths.get(reference))
      case _ => None

  private def referencedPaths(
      paths: IdentityHashMap[AnyRef, String],
      value: Any
  ): Vector[String] =
    chargeWork()
    value match
      case option: Option[?] =>
        if !canonicalAccounting then option.toVector.flatMap(referencedPaths(paths, _))
        else canonicalFlatMap(canonicalVector(option))(referencedPaths(paths, _))
      case sequence: Seq[?] =>
        if resolutionWork.isEmpty then sequence.toVector.flatMap(referencedPaths(paths, _))
        else if !canonicalAccounting then
          sequence.iterator.flatMap(referencedPaths(paths, _)).toVector
        else
          canonicalIterator(canonicalIterator(sequence.iterator).flatMap: value =>
            canonicalIterator(referencedPaths(paths, value).iterator)).toVector
      case reference: AnyRef => canonicalVector(Option(paths.get(reference)))
      case _ => Vector.empty

  private def renderIndex(value: Any): String =
    chargeWork()
    value match
      case integer: Int => integer.toString
      case long: Long => long.toString
      case text: String => generatedStem(text)
      case sequence: Seq[?] =>
        if resolutionWork.isEmpty then sequence.map(renderIndex).mkString("_")
        else if !canonicalAccounting then sequence.iterator.map(renderIndex).mkString("_")
        else
          canonicalIterator(sequence.iterator).map: value =>
            val rendered = renderIndex(value)
            chargeWork(rendered.length.toLong + 1L)
            rendered
          .mkString("_")
      case _ => "value"

  private def explicitExpressionName(
      capture: OriginExpressionCapture
  ): Option[String] =
    val typed = capture.value match
      case expression: KernelExpr[?] if expression.constructionFinalized =>
        expression.materializationIntent.collect:
          case KernelDigitalIntent.Named(name) => name
      case _ => None
    typed.orElse:
      if capture.operands.lastOption.contains("named") then
        canonicalCopy(
          capture.operands.size.toLong
        )(capture.operands.dropRight(1)).lastOption.collect:
          case name: String => cleanIdentifier(name, "expression")
      else None

  private def shapeName(
      capture: OriginExpressionCapture,
      localNames: IdentityHashMap[AnyRef, String]
  ): Option[String] =
    val operation = generatedStem(capture.site.apiOperation)
    val shaped =
      Set("at", "row", "plane", "flatten", "reshape", "map", "zip", "reduce", "reduceBalancedTree")
    if !shaped.contains(operation) then None
    else
      capture.operands.headOption.flatMap:
        case reference: AnyRef =>
          Option(localNames.get(reference)).map: base =>
            val suffix =
              if operation == "at" then
                capture.operands.lift(1).map(renderIndex).filter(_.nonEmpty).map("_" + _)
                  .getOrElse("")
              else ""
            s"${base}_${operation}$suffix"
        case _ => None

  private def originId(
      kind: String,
      path: String,
      operation: String,
      source: Option[SourceSpan],
      parents: Vector[String],
      sink: Option[String]
  ): String =
    val location = source
      .map(span =>
        s"${span.path}:${span.line}:${span.column}:${span.endLine}:${span.endColumn}"
      )
      .getOrElse("unknown")
    if canonicalAccounting then
      canonicalIterator(parents.iterator).foreach(parent => chargeWork(parent.length.toLong + 1L))
    s"origin-${stableDigest(kind, path, operation, location, parents.mkString("|"), sink.getOrElse("")).take(24)}"

  def resolve(): SemanticOriginResult =
    // A filter callback charges its accepted output before the collection inserts it.
    def canonicalSelect(keep: Boolean): Boolean =
      if keep then canonicalVisit()
      keep

    if resolutionWork.nonEmpty then
      val count = 1L + modules.size + domains.size + declarations.size +
        expressions.size + instances.size + operations.size
      chargeWork(128L * count)
      if !canonicalAccounting then chargeSquare(count, 64)
      // Operand vectors are not bounded by the number of capture records.
      expressions.foreach(capture => canonicalProbe(chargeWork(capture.operands.size.toLong)))
      operations.foreach(capture => canonicalProbe(chargeWork(capture.values.size.toLong * 4L)))
    val members = discoverMemberNames()
    val moduleByHandle = canonicalIterator(
      modules.iterator.map(capture => canonicalProbe(capture.handle -> capture))
    ).toMap
    val instanceByChild = canonicalIterator(
      instances.iterator.map(capture => canonicalProbe(capture.child -> capture))
    ).toMap
    val instanceCandidates = canonicalVector(instances).map: capture =>
      canonicalVisit() // Candidate callback.
      canonicalVisit() // Candidate-vector output.
      val childClass = moduleByHandle(capture.child).className
      val direct = memberBinding(members, capture.instance)
        .orElse(memberBinding(members, capture.childModule))
        .orElse(instanceBinding(capture.site))
      val base = direct.getOrElse(
        s"${lowerInitial(cleanIdentifier(childClass, "module"))}_${sourceSuffix(capture.site)}"
      )
      NameCandidate(
        s"${capture.parent}:${capture.ordinal}",
        base,
        if direct.nonEmpty then "scala-declaration" else "source-digest"
      )
    val instanceGroups = canonicalSort(canonicalVector(instances.groupBy(capture =>
      canonicalProbe(canonicalCopy(1L)(capture.parent))
    )))(_._1)
    val instanceResolved = canonicalMap(canonicalFlatMap(instanceGroups): (_, captures) =>
      val candidates = canonicalVector(captures).map: capture =>
        canonicalVisit()
        canonicalVisit()
        instanceCandidates.find(candidate =>
          canonicalProbe(
            candidate.key == s"${capture.parent}:${capture.ordinal}"
          )
        ).get
      canonicalVector(allocate(candidates)))

    val modulePaths = mutable.HashMap.empty[Long, String]
    def modulePath(handle: Long): String =
      chargeWork()
      modulePaths.getOrElseUpdate(
        handle,
        moduleByHandle(handle).parent match
          case None => cleanIdentifier(moduleByHandle(handle).className, "Module")
          case Some(parent) =>
            val instance = instanceByChild(handle)
            val local = instanceResolved(s"${instance.parent}:${instance.ordinal}").name
            s"${modulePath(parent)}.$local"
      )
    modules.foreach(capture => canonicalProbe(modulePath(capture.handle)))

    val objectPaths = new IdentityHashMap[AnyRef, String]()
    val objectLocalNames = new IdentityHashMap[AnyRef, String]()
    modules.foreach: capture =>
      canonicalVisit()
      val path = modulePaths(capture.handle)
      canonicalProbe(objectPaths.put(capture.module, path))
      canonicalProbe(objectLocalNames.put(
        capture.module,
        canonicalCopy(path.length.toLong)(path.split("\\.")).lastOption.getOrElse(path)
      ))
    instances.foreach: capture =>
      canonicalVisit()
      val resolved = instanceResolved(s"${capture.parent}:${capture.ordinal}")
      val path = s"${modulePaths(capture.parent)}.${resolved.name}_instance"
      canonicalProbe(objectPaths.put(capture.instance, path))
      canonicalProbe(objectLocalNames.put(capture.instance, resolved.name))

    val domainPaths = mutable.HashMap.empty[(Long, Int), String]
    val domainNames = mutable.HashMap.empty[(Long, Int), String]
    val domainProvenance = mutable.HashMap.empty[(Long, Int), String]
    canonicalSort(canonicalVector(domains.groupBy(capture =>
      canonicalProbe(canonicalCopy(1L)(capture.module))
    )))(_._1).foreach: (module, captures) =>
      canonicalVisit()
      val reserved = canonicalSet(instances
        .filter(capture => canonicalProbe(canonicalSelect(capture.parent == module)))
        .map(capture =>
          canonicalProbe(canonicalCopy(1L)(
            instanceResolved(s"${capture.parent}:${capture.ordinal}").name
          ))
        ))
      val allocated = allocate(
        canonicalVector(captures).map: capture =>
          canonicalVisit()
          canonicalVisit()
          NameCandidate(
            s"${capture.module}:${capture.index}",
            cleanIdentifier(capture.name, "domain"),
            "explicit-domain"
          )
        ,
        reserved
      )
      captures.foreach: capture =>
        canonicalVisit()
        val resolved = allocated(s"${capture.module}:${capture.index}")
        val key = capture.module -> capture.index
        val path = s"${modulePaths(capture.module)}.${resolved.name}"
        canonicalProbe(domainNames.update(key, resolved.name))
        canonicalProbe(domainPaths.update(key, path))
        canonicalProbe(domainProvenance.update(key, resolved.provenance))
        canonicalProbe(objectPaths.put(capture.domain, path))
        canonicalProbe(objectLocalNames.put(capture.domain, resolved.name))

    val directDeclarationNames = new IdentityHashMap[AnyRef, String]()
    declarations.foreach: capture =>
      canonicalVisit()
      capture.explicitName
        .map(name => canonicalProbe(cleanIdentifier(name, capture.kind)))
        .orElse(memberBinding(members, capture.value))
        .orElse(declarationBinding(capture.site, capture.kind))
        .foreach(name => canonicalProbe(directDeclarationNames.put(capture.value, name)))

    val sinkHints = new IdentityHashMap[AnyRef, String]()
    operations.foreach: operation =>
      canonicalVisit()
      if canonicalProbe(canonicalCopy(3L)(
          Set("assignment", "value-connect", "interface-connect")
        ).contains(operation.kind)) && canonicalProbe(operation.values.size >= 2)
      then
        val left = operation.values.head
        val sink = left match
          case reference: AnyRef =>
            Option(directDeclarationNames.get(reference))
              .orElse(Option(objectLocalNames.get(reference)))
          case _ => None
        sink.foreach: name =>
          canonicalVisit()
          canonicalCopy(operation.values.size.toLong - 1L)(operation.values.drop(1)).foreach:
            value =>
              canonicalVisit()
              value match
                case reference: AnyRef =>
                  if canonicalProbe(!sinkHints.containsKey(reference)) then
                    canonicalProbe(sinkHints.put(reference, name))
                case _ => ()

    val declarationPaths = mutable.HashMap.empty[(Long, Int), String]
    val declarationNames = mutable.HashMap.empty[(Long, Int), String]
    val declarationProvenance = mutable.HashMap.empty[(Long, Int), String]
    canonicalSort(canonicalVector(declarations.groupBy(capture =>
      canonicalProbe(canonicalCopy(1L)(capture.module))
    )))(_._1).foreach: (module, captures) =>
      canonicalVisit()
      val reserved = canonicalUnion(
        canonicalSet(instances
          .filter(capture => canonicalProbe(canonicalSelect(capture.parent == module)))
          .map(capture =>
            canonicalProbe(canonicalCopy(1L)(
              instanceResolved(s"${capture.parent}:${capture.ordinal}").name
            ))
          )),
        domains.filter(capture => canonicalProbe(canonicalSelect(capture.module == module)))
          .map(capture =>
            canonicalProbe(canonicalCopy(1L)(
              domainNames(capture.module -> capture.index)
            ))
          )
      )
      val claimedBindings = mutable.HashSet.empty[String]
      val allocated = allocate(
        canonicalVector(captures).map: capture =>
          canonicalVisit()
          canonicalVisit()
          val explicit =
            capture.explicitName.map(name => canonicalProbe(cleanIdentifier(name, capture.kind)))
          val binding =
            if explicit.nonEmpty then None
            else
              memberBinding(members, capture.value)
                .orElse(declarationBinding(capture.site, capture.kind))
                .filter(name => canonicalProbe(claimedBindings.add(name)))
          val sink =
            Option(sinkHints.get(capture.value)).map(name => canonicalProbe(s"${name}_source"))
          val selected = explicit
            .map(name => canonicalProbe(name -> "explicit"))
            .orElse(binding.map(name => canonicalProbe(name -> "scala-declaration")))
            .orElse(sink.map(name => canonicalProbe(name -> "sink-affinity")))
            .getOrElse(
              s"${generatedStem(capture.kind)}_${sourceSuffix(capture.site)}" -> "source-digest"
            )
          NameCandidate(
            capture.index.toString,
            selected._1,
            selected._2
          )
        ,
        canonicalSet(reserved)
      )
      captures.foreach: capture =>
        canonicalVisit()
        val key = capture.module -> capture.index
        // This allocator is already scoped to one module. Collision suffixes
        // must not encode its session-wide occurrence handle.
        val resolved = allocated(capture.index.toString)
        val path = s"${modulePaths(capture.module)}.${resolved.name}"
        canonicalProbe(declarationNames.update(key, resolved.name))
        canonicalProbe(declarationPaths.update(key, path))
        canonicalProbe(declarationProvenance.update(key, resolved.provenance))
        canonicalProbe(objectPaths.put(capture.value, path))
        canonicalProbe(objectLocalNames.put(capture.value, resolved.name))

    operations.foreach: operation =>
      canonicalVisit()
      if canonicalProbe(canonicalCopy(3L)(
          Set("assignment", "value-connect", "interface-connect")
        ).contains(operation.kind)) && canonicalProbe(operation.values.size >= 2)
      then
        objectPath(objectPaths, operation.values.head).foreach: sink =>
          canonicalVisit()
          canonicalCopy(operation.values.size.toLong - 1L)(operation.values.drop(1)).foreach:
            value =>
              canonicalVisit()
              value match
                case reference: AnyRef =>
                  if canonicalProbe(!sinkHints.containsKey(reference)) then
                    canonicalProbe(sinkHints.put(reference, sink))
                  else if !Option(sinkHints.get(reference)).exists(value =>
                      canonicalProbe(
                        canonicalCopy(value.length.toLong)(value.contains("."))
                      )
                    )
                  then
                    canonicalProbe(sinkHints.put(reference, sink))
                case _ => ()

    val expressionPaths = mutable.HashMap.empty[(Long, Int), String]
    val expressionNames = mutable.HashMap.empty[(Long, Int), String]
    val expressionProvenance = mutable.HashMap.empty[(Long, Int), String]
    canonicalSort(canonicalVector(expressions.groupBy(capture =>
      canonicalProbe(canonicalCopy(1L)(capture.module))
    )))(_._1).foreach: (module, captures) =>
      canonicalVisit()
      val used = mutable.HashSet.empty[String]
      canonicalAdd(
        used,
        instances
          .filter(capture => canonicalProbe(canonicalSelect(capture.parent == module)))
          .map(capture =>
            canonicalProbe(canonicalCopy(1L)(
              instanceResolved(s"${capture.parent}:${capture.ordinal}").name
            ))
          )
      )
      canonicalAdd(
        used,
        domains.filter(capture => canonicalProbe(canonicalSelect(capture.module == module)))
          .map(capture =>
            canonicalProbe(canonicalCopy(1L)(
              domainNames(capture.module -> capture.index)
            ))
          )
      )
      canonicalAdd(
        used,
        declarations.filter(capture => canonicalProbe(canonicalSelect(capture.module == module)))
          .map(capture =>
            canonicalProbe(canonicalCopy(1L)(
              declarationNames(capture.module -> capture.index)
            ))
          )
      )
      canonicalSort(canonicalVector(captures))(_.index).foreach: capture =>
        canonicalVisit()
        val explicit = explicitExpressionName(capture)
        val binding = expressionBinding(capture.site)
          .orElse(memberBinding(members, capture.value))
        val shaped = shapeName(capture, objectLocalNames)
        val sink = Option(sinkHints.get(capture.value)).map: path =>
          canonicalVisit()
          val local =
            canonicalCopy(path.length.toLong)(path.split("\\.")).lastOption.getOrElse(path)
          if canonicalProbe(canonicalCopy(math.min(
              local.length.toLong,
              "_next".length.toLong
            ))(local.endsWith("_next")))
          then local
          else s"${local}_value"
        val operation = generatedStem(capture.site.apiOperation)
        val selected = explicit
          .map(name => canonicalProbe(name -> "explicit"))
          .orElse(binding.map(name => canonicalProbe(name -> "scala-declaration")))
          .orElse(shaped.map(name => canonicalProbe(name -> "shaped-view")))
          .orElse(sink.map(name => canonicalProbe(name -> "sink-affinity")))
          .getOrElse(
            s"${operation}_${sourceSuffix(capture.site)}" -> "source-digest"
          )
        // Collision allocation is local to this module. A session-wide module handle
        // must not change otherwise identical reusable definition-local identities.
        val key = capture.index.toString
        val base = cleanIdentifier(selected._1, "expression")
        var salt = 0
        var name = base
        while canonicalProbe(used.contains(name)) do
          chargeWork()
          name = s"${base}_${stableDigest(key, salt.toString).take(8)}"
          salt += 1
        canonicalProbe(used += name)
        val mapKey = capture.module -> capture.index
        val path = s"${modulePaths(capture.module)}.$name"
        canonicalProbe(expressionNames.update(mapKey, name))
        canonicalProbe(expressionPaths.update(mapKey, path))
        canonicalProbe(expressionProvenance.update(mapKey, selected._2))
        canonicalProbe(objectPaths.put(capture.value, path))
        canonicalProbe(objectLocalNames.put(capture.value, name))

    val nameSnapshots = mutable.ArrayBuffer.empty[KernelNameSnapshot]
    modules.foreach: capture =>
      canonicalVisit()
      val path = modulePaths(capture.handle)
      val name = canonicalCopy(path.length.toLong)(path.split("\\.")).lastOption.getOrElse(path)
      val provenance =
        if capture.parent.isEmpty then "module-class"
        else
          val instance = instanceByChild(capture.handle)
          instanceResolved(s"${instance.parent}:${instance.ordinal}").provenance
      canonicalVisit()
      nameSnapshots += KernelNameSnapshot(path, name, "module", provenance, capture.site.span)
    instances.foreach: capture =>
      canonicalVisit()
      val resolved = instanceResolved(s"${capture.parent}:${capture.ordinal}")
      canonicalVisit()
      nameSnapshots += KernelNameSnapshot(
        s"${modulePaths(capture.parent)}.${resolved.name}_instance",
        resolved.name,
        "instance",
        resolved.provenance,
        capture.site.span
      )
    domains.foreach: capture =>
      canonicalVisit()
      val key = capture.module -> capture.index
      canonicalVisit()
      nameSnapshots += KernelNameSnapshot(
        domainPaths(key),
        domainNames(key),
        "domain",
        domainProvenance(key),
        capture.site.span
      )
    declarations.foreach: capture =>
      canonicalVisit()
      val key = capture.module -> capture.index
      canonicalVisit()
      nameSnapshots += KernelNameSnapshot(
        declarationPaths(key),
        declarationNames(key),
        capture.kind,
        declarationProvenance(key),
        capture.site.span
      )
    expressions.foreach: capture =>
      canonicalVisit()
      val key = capture.module -> capture.index
      canonicalVisit()
      nameSnapshots += KernelNameSnapshot(
        expressionPaths(key),
        expressionNames(key),
        "expression",
        expressionProvenance(key),
        capture.site.span,
        aliases = capture.value match
          case expression: KernelExpr[?]
              if canonicalProbe(expression.constructionFinalized) &&
                expression.materializationIntent.exists(intent =>
                  canonicalProbe(
                    intent.isInstanceOf[KernelDigitalIntent.Named]
                  )
                ) =>
            val bound = canonicalVector(expressionBinding(capture.site).orElse(memberBinding(
              members,
              capture.value
            )))
            val explicit = canonicalVector(explicitExpressionName(capture))
            val aliases = canonicalCopy(bound.size.toLong + explicit.size.toLong)(bound ++ explicit)
            canonicalSort(canonicalDistinct(aliases))(identity)
          case _ => Vector.empty
      )

    val origins = mutable.ArrayBuffer.empty[KernelOriginSnapshot]
    modules.foreach: capture =>
      canonicalVisit()
      val path = modulePaths(capture.handle)
      val parents =
        canonicalVector(capture.parent.map(parent => canonicalProbe(modulePaths(parent))))
      val operation = s"${capture.site.apiOwner}.${capture.site.apiOperation}"
      canonicalVisit()
      origins += KernelOriginSnapshot(
        originId("module", path, operation, capture.site.span, parents, None),
        path,
        "module",
        operation,
        capture.site.span,
        parents,
        None,
        inlined = false
      )
    instances.foreach: capture =>
      canonicalVisit()
      val path =
        s"${modulePaths(capture.parent)}.${instanceResolved(s"${capture.parent}:${capture.ordinal}").name}_instance"
      val parents =
        canonicalCopy(2L)(Vector(modulePaths(capture.parent), modulePaths(capture.child)))
      val operation = s"${capture.site.apiOwner}.${capture.site.apiOperation}"
      canonicalVisit()
      origins += KernelOriginSnapshot(
        originId("instance", path, operation, capture.site.span, parents, None),
        path,
        "instance",
        operation,
        capture.site.span,
        parents,
        None,
        inlined = false
      )
    domains.foreach: capture =>
      canonicalVisit()
      val key = capture.module -> capture.index
      val path = domainPaths(key)
      val parents = canonicalCopy(1L)(Vector(modulePaths(capture.module)))
      val operation =
        s"${capture.site.apiOwner}.${capture.site.apiOperation}:${capture.kind}"
      canonicalVisit()
      origins += KernelOriginSnapshot(
        originId("domain", path, operation, capture.site.span, parents, None),
        path,
        "domain",
        operation,
        capture.site.span,
        parents,
        None,
        inlined = false
      )
    declarations.foreach: capture =>
      canonicalVisit()
      val key = capture.module -> capture.index
      val path = declarationPaths(key)
      val parents = canonicalCopy(1L)(Vector(modulePaths(capture.module)))
      val sink = Option(sinkHints.get(capture.value)).filter(value =>
        canonicalProbe(canonicalCopy(value.length.toLong)(value.contains(".")))
      )
      val operation = s"${capture.site.apiOwner}.${capture.site.apiOperation}"
      canonicalVisit()
      origins += KernelOriginSnapshot(
        originId(capture.kind, path, operation, capture.site.span, parents, sink),
        path,
        capture.kind,
        operation,
        capture.site.span,
        parents,
        sink,
        inlined = false
      )
    expressions.foreach: capture =>
      canonicalVisit()
      val key = capture.module -> capture.index
      val path = expressionPaths(key)
      val referenced = canonicalFlatMap(capture.operands)(referencedPaths(objectPaths, _))
      chargeSquare(referenced.size.toLong)
      val filtered = referenced.filterNot(value =>
        canonicalProbe {
          val drop = value == path
          if !drop then canonicalVisit()
          drop
        }
      )
      val parents = canonicalSort(canonicalDistinct(filtered))(identity)
      val sink = Option(sinkHints.get(capture.value)).filter(value =>
        canonicalProbe(canonicalCopy(value.length.toLong)(value.contains(".")))
      )
      val operation = s"${capture.site.apiOwner}.${capture.site.apiOperation}"
      canonicalVisit()
      origins += KernelOriginSnapshot(
        originId("expression", path, operation, capture.site.span, parents, sink),
        path,
        "expression",
        operation,
        capture.site.span,
        parents,
        sink,
        inlined = true
      )

    val generated = mutable.ArrayBuffer.empty[(NameCandidate, String, String, KernelSourceSite)]
    domains.foreach: capture =>
      canonicalVisit()
      val key = capture.module -> capture.index
      val owner = modulePaths(capture.module)
      val stem = generatedStem(domainNames(key))
      canonicalVisit()
      generated +=
        ((
          NameCandidate(
            s"clock-port:${capture.module}:${capture.index}",
            s"${stem}_clock",
            "clock-port"
          ),
          owner,
          domainPaths(key),
          capture.site
        ))
      canonicalVisit()
      generated +=
        ((
          NameCandidate(
            s"reset-port:${capture.module}:${capture.index}",
            s"${stem}_reset",
            "reset-port"
          ),
          owner,
          domainPaths(key),
          capture.site
        ))
    declarations.foreach: capture =>
      canonicalVisit()
      val key = capture.module -> capture.index
      if canonicalProbe(capture.kind == "register") &&
        canonicalProbe(declarationProvenance(key) == "source-digest")
      then
        canonicalVisit()
        generated +=
          ((
            NameCandidate(
              s"anonymous-register:${capture.module}:${capture.index}",
              s"${declarationNames(key)}_register",
              "anonymous-register"
            ),
            modulePaths(capture.module),
            declarationPaths(key),
            capture.site
          ))
    expressions.foreach: capture =>
      canonicalVisit()
      val key = capture.module -> capture.index
      val owner = generatedStem(capture.site.apiOwner)
      val operation = generatedStem(capture.site.apiOperation)
      val path = expressionPaths(key)
      val module = modulePaths(capture.module)
      if canonicalProbe(expressionProvenance(key) == "source-digest") then
        canonicalVisit()
        generated +=
          ((
            NameCandidate(
              s"temporary:${capture.module}:${capture.index}",
              s"${expressionNames(key)}_temporary",
              "temporary"
            ),
            module,
            path,
            capture.site
          ))
      if (canonicalProbe(canonicalCopy(owner.length.toLong)(owner.contains("cdc"))) ||
          canonicalProbe(canonicalCopy(owner.length.toLong)(owner.contains("rdc")))) &&
        canonicalProbe(canonicalCopy(4L)(Set(
          "sync",
          "gray",
          "pulse",
          "handshake"
        )).contains(operation))
      then
        canonicalVisit()
        generated +=
          ((
            NameCandidate(
              s"synchronizer:${capture.module}:${capture.index}",
              s"${expressionNames(key)}_synchronizer",
              "synchronizer"
            ),
            module,
            path,
            capture.site
          ))
        canonicalVisit()
        generated +=
          ((
            NameCandidate(
              s"crossing:${capture.module}:${capture.index}",
              s"${expressionNames(key)}_crossing",
              "crossing"
            ),
            module,
            path,
            capture.site
          ))
      if canonicalProbe(canonicalCopy(owner.length.toLong)(owner.contains("cdc"))) &&
        canonicalProbe(operation == "waive")
      then
        canonicalVisit()
        generated +=
          ((
            NameCandidate(
              s"waiver:${capture.module}:${capture.index}",
              s"${expressionNames(key)}_waiver",
              "waiver"
            ),
            module,
            path,
            capture.site
          ))
      if canonicalProbe(canonicalCopy(owner.length.toLong)(owner.contains("reset_controller"))) ||
        canonicalProbe(canonicalCopy(owner.length.toLong)(owner.contains("resetcontroller")))
      then
        canonicalVisit()
        generated +=
          ((
            NameCandidate(
              s"reset-controller:${capture.module}:${capture.index}",
              s"${expressionNames(key)}_reset_controller",
              "reset-controller"
            ),
            module,
            path,
            capture.site
          ))
      if canonicalProbe(canonicalCopy(2L)(Set("delay", "stage")).contains(operation)) then
        canonicalVisit()
        generated +=
          ((
            NameCandidate(
              s"pipeline-state:${capture.module}:${capture.index}",
              s"${expressionNames(key)}_pipeline_state",
              "pipeline-state"
            ),
            module,
            path,
            capture.site
          ))
    canonicalCopy(operations.size.toLong)(operations.zipWithIndex).foreach: (capture, index) =>
      canonicalVisit()
      val owner = generatedStem(capture.site.apiOwner)
      val operation = generatedStem(capture.site.apiOperation)
      val module = modulePaths(capture.module)
      canonicalVisit() // The exact path pays for the lazy suffix cell before allocation.
      lazy val suffix = sourceSuffix(capture.site)
      if !canonicalAccounting then
        val _ = suffix // Preserve legacy eager evaluation before the branch probes.
      if canonicalProbe(operation == "fifo") then
        canonicalVisit()
        generated +=
          ((
            NameCandidate(s"fifo:${capture.module}:$index", s"fifo_$suffix", "fifo"),
            module,
            s"$module.fifo_$suffix",
            capture.site
          ))
        canonicalVisit()
        generated +=
          ((
            NameCandidate(
              s"crossing-operation:${capture.module}:$index",
              s"fifo_crossing_$suffix",
              "crossing"
            ),
            module,
            s"$module.fifo_$suffix",
            capture.site
          ))
      if canonicalProbe(operation == "pipe") || canonicalProbe(operation == "delay") then
        canonicalVisit()
        generated +=
          ((
            NameCandidate(
              s"pipeline-operation:${capture.module}:$index",
              s"pipeline_state_$suffix",
              "pipeline-state"
            ),
            module,
            s"$module.pipeline_state_$suffix",
            capture.site
          ))
      if canonicalProbe(operation == "fsm") ||
        canonicalProbe(canonicalCopy(owner.length.toLong)(owner.contains("fsm")))
      then
        canonicalVisit()
        generated +=
          ((
            NameCandidate(
              s"fsm-state:${capture.module}:$index",
              s"fsm_state_$suffix",
              "fsm-state"
            ),
            module,
            s"$module.fsm_state_$suffix",
            capture.site
          ))

    val generatedGroups = canonicalSort(canonicalVector(generated.groupBy(value =>
      canonicalProbe(canonicalCopy(1L)(value._2))
    )))(_._1)
    val generatedEntries = canonicalFlatMap(generatedGroups): (owner, values) =>
      val allocated = allocate(canonicalVector(values.map(value =>
        canonicalProbe(canonicalCopy(1L)(value._1))
      )))
      values.map: (candidate, _, origin, _) =>
        canonicalVisit()
        canonicalVisit()
        val resolved = allocated(candidate.key)
        KernelGeneratedNameSnapshot(
          resolved.provenance,
          resolved.name,
          owner,
          origin
        )
    val generatedSnapshots = canonicalVector(canonicalSort(generatedEntries)(entry =>
      (entry.owner, entry.category, entry.name)
    ))

    val sortedHardwareNames = canonicalSort(canonicalVector(nameSnapshots))(entry =>
      (entry.semanticPath, entry.category, entry.name)
    )
    // Evaluate the session-owned projection inside this invocation's work scope. The map
    // contains this resolution's final module identities, never a cached session result.
    val lexicalAreaNames = areaNames match
      case None => Vector.empty
      case Some(project) =>
        // A no-Area session must not copy/charge a map it will never inspect.
        lazy val paths =
          chargeWork(modulePaths.size.toLong)
          modulePaths.toMap
        project(() => paths, units => chargeWork(units))
    if resolutionWork.nonEmpty && lexicalAreaNames.nonEmpty then
      val added = lexicalAreaNames.size.toLong
      val count = 1L + modules.size + domains.size + declarations.size +
        expressions.size + instances.size + operations.size
      chargeWork(128L * added)
      if !canonicalAccounting then
        // 64 * ((count + added)^2 - count^2), paid row by row before merge/sort.
        // Do not form a potentially overflowing squared/product total.
        var row = 0L
        while row < count do
          chargeWork(64L * added)
          row += 1L
        row = 0L
        while row < added do
          chargeWork(64L * (count + added))
          row += 1L
    val sortedNames = if lexicalAreaNames.isEmpty then sortedHardwareNames
    else
      val size = sortedHardwareNames.size.toLong + lexicalAreaNames.size.toLong
      chargeWork(size) // Merged vector before allocation.
      val merged = sortedHardwareNames ++ lexicalAreaNames
      chargeWork(size) // Sort buffer before allocation.
      val tupleOrder = summon[Ordering[(String, String, String)]]
      val ordering = new Ordering[(String, String, String)]:
        def compare(left: (String, String, String), right: (String, String, String)): Int =
          chargeWork(1L + left._1.length.toLong + left._2.length.toLong +
            left._3.length.toLong + right._1.length.toLong + right._2.length.toLong +
            right._3.length.toLong)
          tupleOrder.compare(left, right)
      merged.sortBy(entry =>
        chargeWork(1L)
        (entry.semanticPath, entry.category, entry.name)
      )(using ordering)
    val sortedOrigins = canonicalSort(canonicalVector(origins))(entry =>
      (entry.semanticPath, entry.kind, entry.id)
    )
    // Area allocation sources stay on their name rows. Adding them to sourceMap could
    // collide with later session auxiliary paths, which use the hardware namespace.
    val sourceEntries = canonicalFlatMap(sortedHardwareNames)(entry =>
      entry.source.map(source => canonicalProbe(SourceMapEntry(entry.semanticPath, source)))
    )
    val sourceGroups = canonicalSort(canonicalVector(sourceEntries.groupBy(entry =>
      canonicalProbe(canonicalCopy(1L)(entry.semanticPath))
    )))(_._1)
    val sourceMap = sourceGroups.map(group => canonicalProbe(canonicalCopy(1L)(group._2.head)))

    val instancePaths = instances.iterator.map: capture =>
      canonicalVisit()
      val resolved = instanceResolved(s"${capture.parent}:${capture.ordinal}")
      (capture.parent -> capture.ordinal) ->
        s"${modulePaths(capture.parent)}.${resolved.name}_instance"

    SemanticOriginResult(
      canonicalMap(modulePaths),
      canonicalMap(domainPaths),
      canonicalMap(domainNames),
      canonicalMap(declarationPaths),
      canonicalMap(declarationNames),
      canonicalMap(expressionPaths),
      canonicalMap(expressionNames),
      canonicalIterator(instancePaths).toMap,
      sortedNames,
      sortedOrigins,
      generatedSnapshots,
      sourceMap
    )

  /** The old selector only consumes frames through its first located user constructor. Existing
    * sourceCache entries can certify that prefix without moving any filesystem lookup into the
    * walk. A missing earlier constructor key permanently disables stopping for this invocation.
    * Span reads and all downstream selection still run unchanged after the walk.
    */
  private def cachedConstructorPrefix(stream: java.util.stream.Stream[StackWalker.StackFrame])
      : java.util.List[StackWalker.StackFrame] =
    chargeWork(2L) // Iterator and retained frame buffer.
    val iterator = stream.limit(96).iterator()
    val frames = new java.util.ArrayList[StackWalker.StackFrame]()
    var mayStop = true
    var complete = false
    while !complete && iterator.hasNext do
      val frame = iterator.next()
      // Same per-visited-frame work as the original metered full walk.
      chargeWork(16L)
      val owner = frame.getClassName
      val file = Option(frame.getFileName)
      val method = frame.getMethodName
      chargeWork(4L *
        (owner.length.toLong + file.fold(0L)(_.length.toLong) +
          method.length.toLong + 1L))
      frames.add(frame)
      if mayStop then
        // Additional classification/key work is real even when it proves fallback.
        chargeWork(2L + owner.length.toLong + file.fold(0L)(_.length.toLong) +
          method.length.toLong)
        if isUserFrame(frame) && method == "<init>" then
          file.foreach: name =>
            chargeWork(1L + name.length.toLong + owner.length.toLong)
            sourceCache.get(name -> owner) match
              case Some(Some(_)) => complete = true
              case Some(None) => ()
              case None => mayStop = false
    preparingOperation.foreach: prepared =>
      chargeWork(2L)
      // Only a positive stop before the old 96-frame bound can be reused.
      // The caller preserves the original first-capture wrapper depth; fallback
      // also calls the original second-capture wrapper directly. Baseline bytecode
      // adds four visible frames for that second call (Session lambda, two
      // JProcedure1.apply bridges and Option.foreach): 80 + 4 stays below 96.
      prepared.prefixComplete = complete && frames.size <= 80
    frames
