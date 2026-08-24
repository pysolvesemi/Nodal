#!/usr/bin/env python3
"""Run the frozen Increment 17 payload and apply review-closure repairs."""

from __future__ import annotations

from pathlib import Path

import materialize_increment17_payload as payload

ROOT = Path(__file__).resolve().parents[1]


def replace_once(path: Path, old: str, new: str, label: str) -> None:
    text = path.read_text(encoding="utf-8")
    if new in text:
        return
    if text.count(old) != 1:
        raise RuntimeError(f"{label} anchor is not unique")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


def append_once(path: Path, marker: str, content: str) -> None:
    text = path.read_text(encoding="utf-8")
    if marker not in text:
        path.write_text(text.rstrip() + "\n\n" + content.strip() + "\n", encoding="utf-8")


def main() -> None:
    payload.main()

    documentation = ROOT / "docs/implementation/increment17-source-origin-naming.md"
    text = documentation.read_text(encoding="utf-8")
    for marker in (
        "Traversal ordinals are never emitted as a normal name.",
        "All expression-level source-map entries remain present when nodes are inlined.",
        (
            "Source files sharing a basename are resolved with owner package and "
            "top-level type context."
        ),
    ):
        if marker not in text:
            text = text.rstrip() + "\n\n" + marker + "\n"
    documentation.write_text(text, encoding="utf-8")

    source = ROOT / "core/scala/api/src/nodal/SemanticOriginKernel.scala"
    text = source.read_text(encoding="utf-8")

    old = """  private def isUserFrame(frame: StackWalker.StackFrame): Boolean =
    Option(frame.getFileName).exists: fileName =>
      fileName.endsWith(".scala") &&
      !internalSourceFiles.contains(fileName) &&
      !apiSourceFiles.contains(fileName)
"""
    new = """  private def isUserFrame(frame: StackWalker.StackFrame): Boolean =
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
"""
    if new not in text:
        if text.count(old) != 1:
            raise RuntimeError("user-frame selection anchor is not unique")
        text = text.replace(old, new, 1)

    old = """  private val sourceCache: mutable.HashMap[String, Option[Path]] = mutable.HashMap.empty
"""
    new = """  private val sourceCache: mutable.HashMap[(String, String), Option[Path]] =
    mutable.HashMap.empty
"""
    if new not in text:
        if text.count(old) != 1:
            raise RuntimeError("source-cache anchor is not unique")
        text = text.replace(old, new, 1)

    old = """    val located = user.flatMap: frame =>
      Option(frame.getFileName).map(fileName =>
        locateSpan(fileName, frame.getLineNumber)
      )
"""
    new = """    val located = user.flatMap: frame =>
      Option(frame.getFileName).map(fileName =>
        locateSpan(fileName, frame.getClassName, frame.getLineNumber)
      )
"""
    if new not in text:
        if text.count(old) != 1:
            raise RuntimeError("source-site owner-context anchor is not unique")
        text = text.replace(old, new, 1)

    old = """  private def locateSpan(fileName: String, observedLine: Int): (SourceSpan, Option[String]) =
    val line = math.max(1, observedLine)
    val source = sourceCache.getOrElseUpdate(fileName, locateSource(fileName))
"""
    new = """  private def locateSpan(
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
"""
    if new not in text:
        if text.count(old) != 1:
            raise RuntimeError("source-span lookup anchor is not unique")
        text = text.replace(old, new, 1)

    old = """  private def locateSource(fileName: String): Option[Path] =
    val root = Path.of(System.getProperty("user.dir", ".")).toAbsolutePath.normalize()
    if !Files.isDirectory(root) then None
    else
      val stream = Files.walk(root)
      try
        stream
          .iterator()
          .asScala
          .filter(path => Files.isRegularFile(path) && path.getFileName.toString == fileName)
          .filter(path => sourceSegments(path).intersect(ignoredSourceSegments).isEmpty)
          .toVector
          .sortBy(path => (path.getNameCount, repositoryPath(path)))
          .headOption
      finally stream.close()
"""
    new = """  private val sourcePackagePattern =
    raw"(?m)^\\s*package\\s+([A-Za-z_][A-Za-z0-9_.]*)(?:\\s*:\\s*)?$".r

  private def ownerPackage(ownerClass: String): Option[String] =
    val separator = ownerClass.lastIndexOf('.')
    if separator <= 0 then None else Some(ownerClass.substring(0, separator))

  private def ownerTypeName(ownerClass: String): Option[String] =
    ownerClass
      .split("\\\\.")
      .lastOption
      .toVector
      .flatMap(_.split("\\\\$").toVector)
      .find(segment => segment.nonEmpty && segment != "package")
      .map(cleanIdentifier(_, "source"))

  private def sourceIdentityScore(path: Path, ownerClass: String): Int =
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

  private def locateSource(fileName: String, ownerClass: String): Option[Path] =
    val root = Path.of(System.getProperty("user.dir", ".")).toAbsolutePath.normalize()
    if !Files.isDirectory(root) then None
    else
      val stream = Files.walk(root)
      try
        val candidates = stream
          .iterator()
          .asScala
          .filter(path => Files.isRegularFile(path) && path.getFileName.toString == fileName)
          .filter(path => sourceSegments(path).intersect(ignoredSourceSegments).isEmpty)
          .toVector
          .sortBy(path => (path.getNameCount, repositoryPath(path)))
        candidates match
          case Vector() => None
          case Vector(single) => Some(single)
          case _ =>
            val scored = candidates.map(path => path -> sourceIdentityScore(path, ownerClass))
            val bestScore = scored.map(_._2).max
            val winners = scored.collect:
              case (path, score) if score == bestScore => path
            if bestScore > 0 && winners.size == 1 then winners.headOption else None
      finally stream.close()
"""
    if new not in text:
        if text.count(old) != 1:
            raise RuntimeError("owner-aware source-location anchor is not unique")
        text = text.replace(old, new, 1)

    source.write_text(text, encoding="utf-8")

    predecessor = ROOT / "scripts/check_increment16.py"
    replace_once(
        predecessor,
        """import argparse
from pathlib import Path

import check_increment16_frozen as frozen
""",
        """import argparse
import sys
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parent
if str(SCRIPT_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPT_DIR))

import check_increment16_frozen as frozen
""",
        "Increment 16 checker import",
    )

    duplicate_alpha = (
        ROOT
        / "core/scala/testkit/test/src/nodal/duplicate/alpha/DuplicateSource.scala"
    )
    duplicate_alpha.parent.mkdir(parents=True, exist_ok=True)
    duplicate_alpha.write_text(
        """package nodal.internal.testkit.duplicate.alpha

import nodal.*

final class DuplicateOriginAlpha extends Module:
  val alphaSignal: Signal[UInt] = wire(UInt(8))
""",
        encoding="utf-8",
    )
    duplicate_beta = (
        ROOT
        / "core/scala/testkit/test/src/nodal/duplicate/beta/DuplicateSource.scala"
    )
    duplicate_beta.parent.mkdir(parents=True, exist_ok=True)
    duplicate_beta.write_text(
        """package nodal.internal.testkit.duplicate.beta

import nodal.*

final class DuplicateOriginBeta extends Module:
  val betaSignal: Signal[UInt] = wire(UInt(8))
""",
        encoding="utf-8",
    )

    tests = ROOT / "core/scala/testkit/test/src/nodal/SemanticOriginTests.scala"
    replace_once(
        tests,
        """import nodal.*

import utest.*
""",
        """import nodal.*
import nodal.internal.testkit.duplicate.alpha.DuplicateOriginAlpha
import nodal.internal.testkit.duplicate.beta.DuplicateOriginBeta

import utest.*
""",
        "duplicate-basename test imports",
    )
    append_once(
        tests,
        'test("same-basename source files use owner context")',
        """    test("same-basename source files use owner context"):
      val alpha = ConstructionKernel.inspect(new DuplicateOriginAlpha)
      val beta = ConstructionKernel.inspect(new DuplicateOriginBeta)

      assert(alpha.sourceMap.exists(entry =>
        entry.source.path.endsWith("duplicate/alpha/DuplicateSource.scala")
      ))
      assert(beta.sourceMap.exists(entry =>
        entry.source.path.endsWith("duplicate/beta/DuplicateSource.scala")
      ))
      assert(!alpha.sourceMap.exists(entry =>
        entry.source.path.endsWith("duplicate/beta/DuplicateSource.scala")
      ))
      assert(!beta.sourceMap.exists(entry =>
        entry.source.path.endsWith("duplicate/alpha/DuplicateSource.scala")
      ))""",
    )

    checker = ROOT / "scripts/check_increment17.py"
    replace_once(
        checker,
        """            "inlined = true",
            '"clock-port"',
""",
        """            "inlined = true",
            "ownerPackage",
            "ownerTypeName",
            "sourceIdentityScore",
            "fileName -> ownerClass",
            "locateSource(fileName, ownerClass)",
            '"clock-port"',
""",
        "owner-aware checker coverage",
    )
    replace_once(
        checker,
        """            "SemanticOriginTop.child",
            "pixel_sum",
""",
        """            "SemanticOriginTop.child",
            "pixel_sum",
            "same-basename source files use owner context",
            "duplicate/alpha/DuplicateSource.scala",
            "duplicate/beta/DuplicateSource.scala",
""",
        "duplicate-basename checker coverage",
    )
    replace_once(
        checker,
        """            "expression-level source-map entries remain present",
            "Public API v0.3 remains unchanged",
""",
        """            "expression-level source-map entries remain present",
            "owner package and top-level type context",
            "Public API v0.3 remains unchanged",
""",
        "documentation checker coverage",
    )

    (ROOT / ".increment17-materialize-trigger").unlink(missing_ok=True)
    Path(__file__).unlink(missing_ok=True)


if __name__ == "__main__":
    main()
