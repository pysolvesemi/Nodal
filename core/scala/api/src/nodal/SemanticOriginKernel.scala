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
    source: Option[SourceSpan]
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

private[nodal] final class SemanticOriginBuilder:
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

  private val internalSourceFiles = Set(
    "ElaborationConstructionKernel.scala",
    "SemanticOriginKernel.scala"
  )
  private val apiSourceFiles = Set(
    "CandidateApi.scala",
    "ContinuousTimeCandidateApi.scala",
    "CoreSemanticsCandidateApi.scala",
    "PipelineInterfaceCandidateApi.scala",
    "CompilerApi.scala"
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
    val frames = walker.walk[java.util.List[StackWalker.StackFrame]](stream =>
      stream.limit(96).toList
    ).asScala.toVector
    val userFrames = frames.zipWithIndex.filter: (frame, _) =>
      isUserFrame(frame)
    val userCandidate = userFrames
      .find: (frame, _) =>
        frame.getMethodName == "<init>" &&
          Option(frame.getFileName)
            .flatMap(fileName => locateSource(fileName, frame.getClassName))
            .nonEmpty
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
    KernelSourceSite(
      located.map(_._1),
      api.map(frame => normalizeOwner(frame.getClassName)).getOrElse("user"),
      api.map(frame => normalizeOperation(frame.getMethodName)).getOrElse("construct"),
      located.flatMap(_._2)
    )

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
    val cacheKey = fileName -> ownerClass
    val source = sourceCache.getOrElseUpdate(
      cacheKey,
      locateSource(fileName, ownerClass)
    )
    source match
      case Some(path) =>
        try
          val lines = Files.readAllLines(path).asScala.toVector
          val currentIndex = math.min(line - 1, math.max(0, lines.size - 1))
          val binding = bindingNear(lines, currentIndex)
          val startIndex = binding.map(_._1).getOrElse(currentIndex)
          val startText = lines.lift(startIndex).getOrElse("")
          val endText = lines.lift(currentIndex).getOrElse(startText)
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
            SourceSpan(fileName, line, 1, line, 1) -> None
      case None => SourceSpan(fileName, line, 1, line, 1) -> None

  private def bindingNear(
      lines: Vector[String],
      currentIndex: Int
  ): Option[(Int, String)] =
    declarationPattern
      .findFirstMatchIn(lines(currentIndex))
      .map(matched => currentIndex -> matched.group(1))
      .filter(_._2 != "_")

  private val sourcePackagePattern =
    (raw"(?m)^\s*package\s+([A-Za-z_][A-Za-z0-9_.]*)(?:\s*:\s*)?" + "$").r

  private def ownerPackage(ownerClass: String): Option[String] =
    val separator = ownerClass.lastIndexOf('.')
    if separator <= 0 then None else Some(ownerClass.substring(0, separator))

  private def ownerTypeName(ownerClass: String): Option[String] =
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
      val source = Files.readString(path)
      val ownerPackageValue = ownerPackage(ownerClass)
      val ownerTypeValue = ownerTypeName(ownerClass)
      val packages = sourcePackagePattern
        .findAllMatchIn(source)
        .map(_.group(1))
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

  private def locateSource(fileName: String, ownerClass: String): Option[Path] =
    val root = repositoryRoot
    if !Files.isDirectory(root) then None
    else
      val candidates = mutable.ArrayBuffer.empty[Path]
      try
        val _ = Files.walkFileTree(
          root,
          new SimpleFileVisitor[Path]:
            override def preVisitDirectory(
                directory: Path,
                attributes: BasicFileAttributes
            ): FileVisitResult =
              if directory != root &&
                attributes.isDirectory &&
                ignoredSourceSegments.contains(directory.getFileName.toString)
              then FileVisitResult.SKIP_SUBTREE
              else FileVisitResult.CONTINUE

            override def visitFile(
                file: Path,
                attributes: BasicFileAttributes
            ): FileVisitResult =
              if attributes.isRegularFile && file.getFileName.toString == fileName
              then candidates += file
              FileVisitResult.CONTINUE

            override def visitFileFailed(
                _file: Path,
                _exception: java.io.IOException
            ): FileVisitResult =
              FileVisitResult.CONTINUE

            override def postVisitDirectory(
                _directory: Path,
                _exception: java.io.IOException
            ): FileVisitResult =
              FileVisitResult.CONTINUE
        )
      catch case _: java.io.IOException => ()
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
    path.iterator().asScala.map(_.toString).toSet

  private def repositoryPath(path: Path): String =
    val root = repositoryRoot
    val relative =
      try root.relativize(path.toAbsolutePath.normalize()).toString
      catch case _: IllegalArgumentException => path.getFileName.toString
    relative.replace('\\', '/')

  private def cleanIdentifier(raw: String, fallback: String): String =
    val normalized = raw
      .replaceAll("[^A-Za-z0-9_]+", "_")
      .replaceAll("_+", "_")
      .stripPrefix("_")
      .stripSuffix("_")
    val selected = if normalized.isEmpty then fallback else normalized
    if selected.headOption.exists(_.isDigit) then s"n_$selected" else selected

  private def generatedStem(raw: String): String =
    cleanIdentifier(
      raw
        .replaceAll("([a-z0-9])([A-Z])", "$1_$2")
        .toLowerCase,
      "generated"
    )

  private def lowerInitial(raw: String): String =
    raw.headOption match
      case Some(head) => head.toLower.toString + raw.drop(1)
      case None => raw

  private def stableDigest(parts: String*): String =
    val bytes = MessageDigest
      .getInstance("SHA-256")
      .digest(parts.mkString("\u001f").getBytes(StandardCharsets.UTF_8))
    bytes.iterator.map(byte => f"${byte & 0xff}%02x").mkString

  private def sourceSuffix(site: KernelSourceSite): String =
    site.span match
      case Some(source) =>
        val file = Path.of(source.path).getFileName.toString.stripSuffix(".scala")
        s"${generatedStem(file)}_l${source.line}_${stableDigest(site.fingerprint).take(8)}"
      case None => stableDigest(site.fingerprint).take(12)

  private def memberFields(clazz: Class[?]): Vector[Field] =
    val classes = Iterator
      .iterate(Option(clazz))(_.flatMap(current => Option(current.getSuperclass)))
      .takeWhile(_.nonEmpty)
      .flatten
      .toVector
    classes
      .flatMap(_.getDeclaredFields.toVector)
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
    modules.sortBy(_.handle).foreach: capture =>
      memberFields(capture.module.getClass).foreach: field =>
        try
          if field.trySetAccessible() then
            Option(field.get(capture.module)).foreach: value =>
              val candidate = cleanIdentifier(field.getName, "member")
              Option(discovered.get(value)) match
                case Some(existing) =>
                  if candidate < existing then discovered.put(value, candidate)
                case None => discovered.put(value, candidate)
        catch
          case _: ReflectiveOperationException => ()
          case _: RuntimeException => ()
    discovered

  private def sourceLines(
      site: KernelSourceSite
  ): Option[(Vector[String], Int)] =
    site.span.flatMap: source =>
      val capturedPath = Path.of(source.path)
      val path =
        if capturedPath.isAbsolute then capturedPath.normalize()
        else repositoryRoot.resolve(capturedPath).normalize()
      if !Files.isRegularFile(path) then None
      else
        try
          val lines = Files.readAllLines(path).asScala.toVector
          if lines.isEmpty then None
          else
            val observedIndex = math.max(0, math.min(lines.size - 1, source.endLine - 1))
            Some(lines -> observedIndex)
        catch case _: Exception => None

  private def declarationContext(lines: Vector[String], index: Int): String =
    val tail = lines.slice(index, math.min(lines.size, index + 5))
    val continuation = tail.headOption.toVector ++
      tail.drop(1).takeWhile(line => declarationPattern.findFirstMatchIn(line).isEmpty)
    continuation.mkString(" ")

  private def bindingForTokens(
      site: KernelSourceSite,
      tokens: Vector[String],
      radius: Int
  ): Option[String] =
    sourceLines(site).flatMap: (lines, observedIndex) =>
      val lower = math.max(0, observedIndex - radius)
      val upper = math.min(lines.size - 1, observedIndex + radius)
      val indexes = (lower to upper).sortBy: index =>
        (math.abs(index - observedIndex), if index <= observedIndex then 0 else 1)
      indexes.iterator
        .flatMap: index =>
          declarationPattern.findFirstMatchIn(lines(index)).flatMap: matched =>
            val context = declarationContext(lines, index)
            if tokens.isEmpty || tokens.exists(context.contains) then
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
      case "analog-node" => Vector("node(")
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
    bindingForTokens(site, tokens, if tokens.nonEmpty then 12 else 0)

  private def instanceBinding(site: KernelSourceSite): Option[String] =
    bindingForTokens(site, Vector("instance("), 12)

  private def expressionBinding(site: KernelSourceSite): Option[String] =
    bindingForTokens(site, Vector.empty, 0)

  private def memberBinding(
      members: IdentityHashMap[AnyRef, String],
      value: AnyRef
  ): Option[String] =
    Option(members.get(value)).map(cleanIdentifier(_, "value"))

  private def allocate(
      candidates: Vector[NameCandidate],
      reserved: Set[String] = Set.empty
  ): Map[String, ResolvedName] =
    val used = mutable.HashSet.from(reserved)
    val resolved = mutable.HashMap.empty[String, ResolvedName]
    candidates.sortBy(candidate => (candidate.base, candidate.key)).foreach: candidate =>
      val base = cleanIdentifier(candidate.base, "value")
      var salt = 0
      var selected = base
      while used.contains(selected) do
        selected = s"${base}_${stableDigest(candidate.key, salt.toString).take(8)}"
        salt += 1
      used += selected
      resolved.update(candidate.key, ResolvedName(selected, candidate.provenance))
    resolved.toMap

  private def objectPath(
      paths: IdentityHashMap[AnyRef, String],
      value: Any
  ): Option[String] = value match
    case option: Option[?] => option.iterator.flatMap(objectPath(paths, _)).nextOption()
    case sequence: Seq[?] => sequence.iterator.flatMap(objectPath(paths, _)).nextOption()
    case reference: AnyRef => Option(paths.get(reference))
    case _ => None

  private def referencedPaths(
      paths: IdentityHashMap[AnyRef, String],
      value: Any
  ): Vector[String] = value match
    case option: Option[?] => option.toVector.flatMap(referencedPaths(paths, _))
    case sequence: Seq[?] => sequence.toVector.flatMap(referencedPaths(paths, _))
    case reference: AnyRef => Option(paths.get(reference)).toVector
    case _ => Vector.empty

  private def renderIndex(value: Any): String = value match
    case integer: Int => integer.toString
    case long: Long => long.toString
    case text: String => generatedStem(text)
    case sequence: Seq[?] => sequence.map(renderIndex).mkString("_")
    case _ => "value"

  private def explicitExpressionName(
      capture: OriginExpressionCapture
  ): Option[String] =
    if capture.operands.lastOption.contains("named") then
      capture.operands.dropRight(1).lastOption.collect:
        case name: String => cleanIdentifier(name, "expression")
    else None

  private def shapeName(
      capture: OriginExpressionCapture,
      localNames: IdentityHashMap[AnyRef, String]
  ): Option[String] =
    val operation = generatedStem(capture.site.apiOperation)
    val shaped = Set("at", "flatten", "reshape", "map", "zip", "reduce")
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
    s"origin-${stableDigest(kind, path, operation, location, parents.mkString("|"), sink.getOrElse("")).take(24)}"

  def resolve(): SemanticOriginResult =
    val members = discoverMemberNames()
    val moduleByHandle = modules.iterator.map(capture => capture.handle -> capture).toMap
    val instanceByChild = instances.iterator.map(capture => capture.child -> capture).toMap
    val instanceCandidates = instances.toVector.map: capture =>
      val childClass = moduleByHandle(capture.child).className
      val direct = instanceBinding(capture.site)
        .orElse(memberBinding(members, capture.instance))
        .orElse(memberBinding(members, capture.childModule))
      val base = direct.getOrElse(
        s"${lowerInitial(cleanIdentifier(childClass, "module"))}_${sourceSuffix(capture.site)}"
      )
      NameCandidate(
        s"${capture.parent}:${capture.ordinal}",
        base,
        if direct.nonEmpty then "scala-declaration" else "source-digest"
      )
    val instanceResolved = instances
      .groupBy(_.parent)
      .toVector
      .sortBy(_._1)
      .flatMap: (_, captures) =>
        allocate(captures.toVector.map: capture =>
          instanceCandidates.find(_.key == s"${capture.parent}:${capture.ordinal}").get).toVector
      .toMap

    val modulePaths = mutable.HashMap.empty[Long, String]
    def modulePath(handle: Long): String =
      modulePaths.getOrElseUpdate(
        handle,
        moduleByHandle(handle).parent match
          case None => cleanIdentifier(moduleByHandle(handle).className, "Module")
          case Some(parent) =>
            val instance = instanceByChild(handle)
            val local = instanceResolved(s"${instance.parent}:${instance.ordinal}").name
            s"${modulePath(parent)}.$local"
      )
    modules.foreach(capture => modulePath(capture.handle))

    val objectPaths = new IdentityHashMap[AnyRef, String]()
    val objectLocalNames = new IdentityHashMap[AnyRef, String]()
    modules.foreach: capture =>
      val path = modulePaths(capture.handle)
      objectPaths.put(capture.module, path)
      objectLocalNames.put(
        capture.module,
        path.split("\\.").lastOption.getOrElse(path)
      )
    instances.foreach: capture =>
      val resolved = instanceResolved(s"${capture.parent}:${capture.ordinal}")
      val path = s"${modulePaths(capture.parent)}.${resolved.name}_instance"
      objectPaths.put(capture.instance, path)
      objectLocalNames.put(capture.instance, resolved.name)

    val domainPaths = mutable.HashMap.empty[(Long, Int), String]
    val domainNames = mutable.HashMap.empty[(Long, Int), String]
    val domainProvenance = mutable.HashMap.empty[(Long, Int), String]
    domains.groupBy(_.module).toVector.sortBy(_._1).foreach: (module, captures) =>
      val reserved = instances
        .filter(_.parent == module)
        .map(capture => instanceResolved(s"${capture.parent}:${capture.ordinal}").name)
        .toSet
      val allocated = allocate(
        captures.toVector.map: capture =>
          NameCandidate(
            s"${capture.module}:${capture.index}",
            cleanIdentifier(capture.name, "domain"),
            "explicit-domain"
          ),
        reserved
      )
      captures.foreach: capture =>
        val resolved = allocated(s"${capture.module}:${capture.index}")
        val key = capture.module -> capture.index
        val path = s"${modulePaths(capture.module)}.${resolved.name}"
        domainNames.update(key, resolved.name)
        domainPaths.update(key, path)
        domainProvenance.update(key, resolved.provenance)
        objectPaths.put(capture.domain, path)
        objectLocalNames.put(capture.domain, resolved.name)

    val directDeclarationNames = new IdentityHashMap[AnyRef, String]()
    declarations.foreach: capture =>
      capture.explicitName
        .map(cleanIdentifier(_, capture.kind))
        .orElse(declarationBinding(capture.site, capture.kind))
        .orElse(memberBinding(members, capture.value))
        .foreach(name => directDeclarationNames.put(capture.value, name))

    val sinkHints = new IdentityHashMap[AnyRef, String]()
    operations.foreach: operation =>
      if Set("assignment", "value-connect", "interface-connect").contains(operation.kind) &&
        operation.values.size >= 2
      then
        val left = operation.values.head
        val sink = left match
          case reference: AnyRef =>
            Option(directDeclarationNames.get(reference))
              .orElse(Option(objectLocalNames.get(reference)))
          case _ => None
        sink.foreach: name =>
          operation.values.drop(1).foreach:
            case reference: AnyRef =>
              if !sinkHints.containsKey(reference) then sinkHints.put(reference, name)
            case _ => ()

    val declarationPaths = mutable.HashMap.empty[(Long, Int), String]
    val declarationNames = mutable.HashMap.empty[(Long, Int), String]
    val declarationProvenance = mutable.HashMap.empty[(Long, Int), String]
    declarations.groupBy(_.module).toVector.sortBy(_._1).foreach: (module, captures) =>
      val reserved =
        instances
          .filter(_.parent == module)
          .map(capture => instanceResolved(s"${capture.parent}:${capture.ordinal}").name)
          .toSet ++
          domains.filter(_.module == module).map(capture =>
            domainNames(capture.module -> capture.index)
          )
      val claimedBindings = mutable.HashSet.empty[String]
      val allocated = allocate(
        captures.toVector.map: capture =>
          val explicit = capture.explicitName.map(cleanIdentifier(_, capture.kind))
          val binding = declarationBinding(capture.site, capture.kind)
            .orElse(memberBinding(members, capture.value))
            .filter(claimedBindings.add)
          val sink = Option(sinkHints.get(capture.value)).map(name => s"${name}_source")
          val selected = explicit
            .map(_ -> "explicit")
            .orElse(binding.map(_ -> "scala-declaration"))
            .orElse(sink.map(_ -> "sink-affinity"))
            .getOrElse(
              s"${generatedStem(capture.kind)}_${sourceSuffix(capture.site)}" -> "source-digest"
            )
          NameCandidate(
            s"${capture.module}:${capture.index}",
            selected._1,
            selected._2
          )
        ,
        reserved.toSet
      )
      captures.foreach: capture =>
        val key = capture.module -> capture.index
        val resolved = allocated(s"${capture.module}:${capture.index}")
        val path = s"${modulePaths(capture.module)}.${resolved.name}"
        declarationNames.update(key, resolved.name)
        declarationPaths.update(key, path)
        declarationProvenance.update(key, resolved.provenance)
        objectPaths.put(capture.value, path)
        objectLocalNames.put(capture.value, resolved.name)

    operations.foreach: operation =>
      if Set("assignment", "value-connect", "interface-connect").contains(operation.kind) &&
        operation.values.size >= 2
      then
        objectPath(objectPaths, operation.values.head).foreach: sink =>
          operation.values.drop(1).foreach:
            case reference: AnyRef =>
              if !sinkHints.containsKey(reference) then sinkHints.put(reference, sink)
              else if !Option(sinkHints.get(reference)).exists(_.contains(".")) then
                sinkHints.put(reference, sink)
            case _ => ()

    val expressionPaths = mutable.HashMap.empty[(Long, Int), String]
    val expressionNames = mutable.HashMap.empty[(Long, Int), String]
    val expressionProvenance = mutable.HashMap.empty[(Long, Int), String]
    expressions.groupBy(_.module).toVector.sortBy(_._1).foreach: (module, captures) =>
      val used = mutable.HashSet.empty[String]
      used ++=
        instances
          .filter(_.parent == module)
          .map(capture => instanceResolved(s"${capture.parent}:${capture.ordinal}").name)
      used ++= domains.filter(_.module == module).map(capture =>
        domainNames(capture.module -> capture.index)
      )
      used ++= declarations.filter(_.module == module).map(capture =>
        declarationNames(capture.module -> capture.index)
      )
      captures.toVector.sortBy(_.index).foreach: capture =>
        val explicit = explicitExpressionName(capture)
        val binding = expressionBinding(capture.site)
          .orElse(memberBinding(members, capture.value))
        val shaped = shapeName(capture, objectLocalNames)
        val sink = Option(sinkHints.get(capture.value)).map: path =>
          val local = path.split("\\.").lastOption.getOrElse(path)
          if local.endsWith("_next") then local else s"${local}_value"
        val operation = generatedStem(capture.site.apiOperation)
        val selected = explicit
          .map(_ -> "explicit")
          .orElse(binding.map(_ -> "scala-declaration"))
          .orElse(shaped.map(_ -> "shaped-view"))
          .orElse(sink.map(_ -> "sink-affinity"))
          .getOrElse(
            s"${operation}_${sourceSuffix(capture.site)}" -> "source-digest"
          )
        val key = s"${capture.module}:${capture.index}"
        val base = cleanIdentifier(selected._1, "expression")
        var salt = 0
        var name = base
        while used.contains(name) do
          name = s"${base}_${stableDigest(key, salt.toString).take(8)}"
          salt += 1
        used += name
        val mapKey = capture.module -> capture.index
        val path = s"${modulePaths(capture.module)}.$name"
        expressionNames.update(mapKey, name)
        expressionPaths.update(mapKey, path)
        expressionProvenance.update(mapKey, selected._2)
        objectPaths.put(capture.value, path)
        objectLocalNames.put(capture.value, name)

    val nameSnapshots = mutable.ArrayBuffer.empty[KernelNameSnapshot]
    modules.foreach: capture =>
      val path = modulePaths(capture.handle)
      val name = path.split("\\.").lastOption.getOrElse(path)
      val provenance =
        if capture.parent.isEmpty then "module-class"
        else
          val instance = instanceByChild(capture.handle)
          instanceResolved(s"${instance.parent}:${instance.ordinal}").provenance
      nameSnapshots += KernelNameSnapshot(path, name, "module", provenance, capture.site.span)
    instances.foreach: capture =>
      val resolved = instanceResolved(s"${capture.parent}:${capture.ordinal}")
      nameSnapshots += KernelNameSnapshot(
        s"${modulePaths(capture.parent)}.${resolved.name}_instance",
        resolved.name,
        "instance",
        resolved.provenance,
        capture.site.span
      )
    domains.foreach: capture =>
      val key = capture.module -> capture.index
      nameSnapshots += KernelNameSnapshot(
        domainPaths(key),
        domainNames(key),
        "domain",
        domainProvenance(key),
        capture.site.span
      )
    declarations.foreach: capture =>
      val key = capture.module -> capture.index
      nameSnapshots += KernelNameSnapshot(
        declarationPaths(key),
        declarationNames(key),
        capture.kind,
        declarationProvenance(key),
        capture.site.span
      )
    expressions.foreach: capture =>
      val key = capture.module -> capture.index
      nameSnapshots += KernelNameSnapshot(
        expressionPaths(key),
        expressionNames(key),
        "expression",
        expressionProvenance(key),
        capture.site.span
      )

    val origins = mutable.ArrayBuffer.empty[KernelOriginSnapshot]
    modules.foreach: capture =>
      val path = modulePaths(capture.handle)
      val parents = capture.parent.map(modulePaths).toVector
      val operation = s"${capture.site.apiOwner}.${capture.site.apiOperation}"
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
      val path =
        s"${modulePaths(capture.parent)}.${instanceResolved(s"${capture.parent}:${capture.ordinal}").name}_instance"
      val parents = Vector(modulePaths(capture.parent), modulePaths(capture.child))
      val operation = s"${capture.site.apiOwner}.${capture.site.apiOperation}"
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
      val key = capture.module -> capture.index
      val path = domainPaths(key)
      val parents = Vector(modulePaths(capture.module))
      val operation =
        s"${capture.site.apiOwner}.${capture.site.apiOperation}:${capture.kind}"
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
      val key = capture.module -> capture.index
      val path = declarationPaths(key)
      val parents = Vector(modulePaths(capture.module))
      val sink = Option(sinkHints.get(capture.value)).filter(_.contains("."))
      val operation = s"${capture.site.apiOwner}.${capture.site.apiOperation}"
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
      val key = capture.module -> capture.index
      val path = expressionPaths(key)
      val parents = capture.operands
        .flatMap(referencedPaths(objectPaths, _))
        .filterNot(_ == path)
        .distinct
        .sorted
      val sink = Option(sinkHints.get(capture.value)).filter(_.contains("."))
      val operation = s"${capture.site.apiOwner}.${capture.site.apiOperation}"
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
      val key = capture.module -> capture.index
      val owner = modulePaths(capture.module)
      val stem = generatedStem(domainNames(key))
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
      val key = capture.module -> capture.index
      if capture.kind == "register" && declarationProvenance(key) == "source-digest" then
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
      val key = capture.module -> capture.index
      val owner = generatedStem(capture.site.apiOwner)
      val operation = generatedStem(capture.site.apiOperation)
      val path = expressionPaths(key)
      val module = modulePaths(capture.module)
      if expressionProvenance(key) == "source-digest" then
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
      if (owner.contains("cdc") || owner.contains("rdc")) &&
        Set("sync", "gray", "pulse", "handshake", "waive").contains(operation)
      then
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
      if owner.contains("reset_controller") || owner.contains("resetcontroller") then
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
      if Set("delay", "stage").contains(operation) then
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
    operations.zipWithIndex.foreach: (capture, index) =>
      val owner = generatedStem(capture.site.apiOwner)
      val operation = generatedStem(capture.site.apiOperation)
      val module = modulePaths(capture.module)
      val suffix = sourceSuffix(capture.site)
      if operation == "fifo" then
        generated +=
          ((
            NameCandidate(s"fifo:${capture.module}:$index", s"fifo_$suffix", "fifo"),
            module,
            s"$module.fifo_$suffix",
            capture.site
          ))
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
      if operation == "pipe" || operation == "delay" then
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
      if operation == "fsm" || owner.contains("fsm") then
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

    val generatedSnapshots = generated
      .groupBy(_._2)
      .toVector
      .sortBy(_._1)
      .flatMap: (owner, values) =>
        val allocated = allocate(values.map(_._1).toVector)
        values.map: (candidate, _, origin, _) =>
          val resolved = allocated(candidate.key)
          KernelGeneratedNameSnapshot(
            resolved.provenance,
            resolved.name,
            owner,
            origin
          )
      .sortBy(entry => (entry.owner, entry.category, entry.name))
      .toVector

    val sortedNames = nameSnapshots.toVector.sortBy(entry =>
      (entry.semanticPath, entry.category, entry.name)
    )
    val sortedOrigins = origins.toVector.sortBy(entry =>
      (entry.semanticPath, entry.kind, entry.id)
    )
    val sourceMap = sortedNames
      .flatMap(entry => entry.source.map(SourceMapEntry(entry.semanticPath, _)))
      .groupBy(_.semanticPath)
      .toVector
      .sortBy(_._1)
      .map(_._2.head)

    val instancePaths = instances.iterator.map: capture =>
      val resolved = instanceResolved(s"${capture.parent}:${capture.ordinal}")
      (capture.parent -> capture.ordinal) ->
        s"${modulePaths(capture.parent)}.${resolved.name}_instance"

    SemanticOriginResult(
      modulePaths.toMap,
      domainPaths.toMap,
      domainNames.toMap,
      declarationPaths.toMap,
      declarationNames.toMap,
      expressionPaths.toMap,
      expressionNames.toMap,
      instancePaths.toMap,
      sortedNames,
      sortedOrigins,
      generatedSnapshots,
      sourceMap
    )
