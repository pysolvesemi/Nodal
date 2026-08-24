#!/usr/bin/env python3
"""Apply the bounded Increment 17 source-origin review closure."""

from __future__ import annotations

from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "core/scala/api/src/nodal/SemanticOriginKernel.scala"


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if new in text:
        return text
    if text.count(old) != 1:
        raise RuntimeError(f"{label} anchor is not unique")
    return text.replace(old, new, 1)


def main() -> None:
    text = SOURCE.read_text(encoding="utf-8")

    text = replace_once(
        text,
        '''  private val declarationPattern =
    raw"\\b(?:lazy\\s+val|val|var)\\s+([A-Za-z_][A-Za-z0-9_]*)".r
''',
        '''  private val declarationPattern =
    """^\\s*(?:(?:private(?:\\[[^\\]]+\\])?|protected(?:\\[[^\\]]+\\])?|final|lazy|override|inline|transparent)\\s+)*(?:val|var)\\s+([A-Za-z_][A-Za-z0-9_]*)\\b""".r
''',
        "declaration pattern",
    )

    text = replace_once(
        text,
        '''    val userIndex = frames.indexWhere(isUserFrame)
    val user = if userIndex >= 0 then Some(frames(userIndex)) else None
''',
        '''    val userFrames = frames.zipWithIndex.filter: (frame, _) =>
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
''',
        "user-frame selection",
    )

    text = replace_once(
        text,
        '''  private def bindingNear(
      lines: Vector[String],
      currentIndex: Int
  ): Option[(Int, String)] =
    val lower = math.max(0, currentIndex - 5)
    val candidates = (currentIndex to lower by -1).iterator.flatMap: index =>
      declarationPattern.findFirstMatchIn(lines(index)).map: matched =>
        index -> matched.group(1)
    candidates.nextOption().filter(_._2 != "_")
''',
        '''  private def bindingNear(
      lines: Vector[String],
      currentIndex: Int
  ): Option[(Int, String)] =
    declarationPattern
      .findFirstMatchIn(lines(currentIndex))
      .map(matched => currentIndex -> matched.group(1))
      .filter(_._2 != "_")
''',
        "exact source-span binding lookup",
    )

    text = replace_once(
        text,
        '''  private def ownerTypeName(ownerClass: String): Option[String] =
    ownerClass
      .split("\\\\.")
      .lastOption
      .toVector
      .flatMap(_.split("\\\\$").toVector)
      .find(segment => segment.nonEmpty && segment != "package")
      .map(cleanIdentifier(_, "source"))
''',
        '''  private def ownerTypeName(ownerClass: String): Option[String] =
    ownerClass
      .split("\\\\.")
      .lastOption
      .flatMap(
        _.split("\\\\$").find(segment => segment.nonEmpty && segment != "package")
      )
      .map(_.takeWhile(character => Character.isJavaIdentifierPart(character)))
      .filter(_.nonEmpty)
''',
        "owner type extraction",
    )

    text = replace_once(
        text,
        '''  private def sourceIdentityScore(path: Path, ownerClass: String): Int =
    try
      val source = Files.readString(path)
      val packages = sourcePackagePattern
        .findAllMatchIn(source)
        .map(_.group(1))
        .toSet
      val packageScore = ownerPackage(ownerClass)
        .filter(packages.contains)
        .map(_ => 100)
        .getOrElse(0)
      val typeScore = ownerTypeName(ownerClass)
        .map: typeName =>
          val declaration =
            (
              "(?m)^\\\\s*(?:(?:final|sealed|abstract|case)\\\\s+)*" +
                "(?:class|object|trait|enum)\\\\s+" +
                java.util.regex.Pattern.quote(typeName) +
                "\\\\b"
            ).r
          if declaration.findFirstIn(source).nonEmpty then 50 else 0
        .getOrElse(0)
      val pathScore = ownerPackage(ownerClass)
        .map(_.replace('.', '/'))
        .filter(packagePath => repositoryPath(path).contains(packagePath))
        .map(_ => 10)
        .getOrElse(0)
      packageScore + typeScore + pathScore
    catch case _: Exception => 0
''',
        '''  private def sourceIdentityScore(path: Path, ownerClass: String): Int =
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
              "(?m)^\\\\s*(?:(?:final|sealed|abstract|case)\\\\s+)*" +
                "(?:class|object|trait|enum)\\\\s+" +
                java.util.regex.Pattern.quote(typeName) +
                "\\\\b"
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
''',
        "source identity scoring",
    )

    text = replace_once(
        text,
        '''  private def locateSource(fileName: String, ownerClass: String): Option[Path] =
    val root = Path.of(System.getProperty("user.dir", ".")).toAbsolutePath.normalize()
''',
        '''  private val repositoryRoot: Path =
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
''',
        "repository root discovery",
    )

    text = replace_once(
        text,
        '''  private def repositoryPath(path: Path): String =
    val root = Path.of(System.getProperty("user.dir", ".")).toAbsolutePath.normalize()
''',
        '''  private def repositoryPath(path: Path): String =
    val root = repositoryRoot
''',
        "repository-relative path root",
    )

    text = replace_once(
        text,
        '''  private def preferredBinding(
      members: IdentityHashMap[AnyRef, String],
      value: AnyRef,
      site: KernelSourceSite
  ): Option[String] =
    site.bindingName
      .map(cleanIdentifier(_, "value"))
      .orElse(Option(members.get(value)).map(cleanIdentifier(_, "value")))
''',
        '''  private def sourceLines(
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
''',
        "context-aware binding helpers",
    )

    text = replace_once(
        text,
        '''      val direct = preferredBinding(members, capture.instance, capture.site)
        .orElse(Option(members.get(capture.childModule)))
''',
        '''      val direct = instanceBinding(capture.site)
        .orElse(memberBinding(members, capture.instance))
        .orElse(memberBinding(members, capture.childModule))
''',
        "instance source binding",
    )

    text = replace_once(
        text,
        '''      capture.explicitName
        .map(cleanIdentifier(_, capture.kind))
        .orElse(preferredBinding(members, capture.value, capture.site))
        .foreach(name => directDeclarationNames.put(capture.value, name))
''',
        '''      capture.explicitName
        .map(cleanIdentifier(_, capture.kind))
        .orElse(declarationBinding(capture.site, capture.kind))
        .orElse(memberBinding(members, capture.value))
        .foreach(name => directDeclarationNames.put(capture.value, name))
''',
        "direct declaration source binding",
    )

    text = replace_once(
        text,
        '''          val binding = preferredBinding(members, capture.value, capture.site)
''',
        '''          val binding = declarationBinding(capture.site, capture.kind)
            .orElse(memberBinding(members, capture.value))
''',
        "declaration allocation binding",
    )

    text = replace_once(
        text,
        '''        val binding = preferredBinding(members, capture.value, capture.site)
''',
        '''        val binding = expressionBinding(capture.site)
          .orElse(memberBinding(members, capture.value))
''',
        "expression allocation binding",
    )

    SOURCE.write_text(text, encoding="utf-8")


if __name__ == "__main__":
    main()
