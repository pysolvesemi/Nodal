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
    val lower = math.max(0, currentIndex - 8)
    val upper = math.min(lines.size - 1, currentIndex + 8)
    val indexes = (lower to upper).sortBy: index =>
      (math.abs(index - currentIndex), if index <= currentIndex then 0 else 1)
    indexes.iterator
      .flatMap: index =>
        declarationPattern.findFirstMatchIn(lines(index)).map: matched =>
          index -> matched.group(1)
      .nextOption()
      .filter(_._2 != "_")
''',
        "nearby binding lookup",
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
      .iterate(workingDirectory)(path => path.getParent)
      .takeWhile(_ != null)
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

    SOURCE.write_text(text, encoding="utf-8")


if __name__ == "__main__":
    main()
