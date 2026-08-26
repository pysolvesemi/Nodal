#!/usr/bin/env python3
"""Apply Increment 22 review fixes to the feature branch checkout."""

from __future__ import annotations

import re
from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: expected one match, found {count}")
    return text.replace(old, new, 1)


def patch_native_mapping(root: Path) -> None:
    path = root / "core/compiler/lib/Diagnostics/DiagnosticMapping.cpp"
    text = path.read_text(encoding="utf-8")

    text = replace_once(
        text,
        """  DiagnosticContext context;
  context.semanticPath = path.str();
  context.hierarchyPath = path.str();
  context.indexPath = indexFallback(path);
""",
        """  DiagnosticContext context;
  context.semanticPath = path.str();
  context.indexPath = indexFallback(path);
""",
        "remove invented inventory hierarchy",
    )
    text = replace_once(
        text,
        """    auto semantic = entry.getAs<StringAttr>("semantic_path");
    if (!semantic || semantic.getValue() != path)
      continue;
    auto source = entry.getAs<StringAttr>("source_path");
""",
        """    auto semantic = entry.getAs<StringAttr>("semantic_path");
    if (!semantic || semantic.getValue() != path)
      continue;
    if (auto hierarchy = entry.getAs<StringAttr>("hierarchy_path"))
      context.hierarchyPath = hierarchy.getValue().str();
    auto source = entry.getAs<StringAttr>("source_path");
""",
        "read explicit inventory hierarchy",
    )
    text = replace_once(
        text,
        """  if (FileLineColLoc file = findFileLocation(operation->getLoc())) {
""",
        """  FileLineColLoc file;
  for (Operation *current = operation; current && !file; current = current->getParentOp())
    file = findFileLocation(current->getLoc());
  if (file) {
""",
        "walk ancestors for source location",
    )
    path.write_text(text, encoding="utf-8")


def patch_scala_mapper(root: Path) -> None:
    path = root / "core/scala/bridge/src/nodal/bridge/NativeDiagnosticMapper.scala"
    text = path.read_text(encoding="utf-8")
    text = replace_once(
        text,
        """  private val SourceRange = raw"\\[source-range=([^\\]]+)\\]".r
""",
        """  private val SourceRange = raw"\\[source-range=([^\\]]+)\\]".r
  private val StagedInputPath =
    raw"""(?:[A-Za-z]:)?(?:[^\\s:]*[/\\\\])*input\\.mlir""".r
""",
        "add staged input path normalization",
    )
    text = replace_once(
        text,
        """    text.linesIterator
      .map(_.trim)
      .find(_.nonEmpty)
      .getOrElse(s"native compiler exited with status $exitCode")
""",
        """    text.linesIterator
      .map(_.trim)
      .find(_.nonEmpty)
      .map(line => StagedInputPath.replaceAllIn(line, "<bridge-input>"))
      .getOrElse(s"native compiler exited with status $exitCode")
""",
        "normalize fallback diagnostic message",
    )
    path.write_text(text, encoding="utf-8")


def patch_scala_tests(root: Path) -> None:
    path = root / "core/scala/testkit/test/src/nodal/internal/testkit/CrossLayerDiagnosticTests.scala"
    text = path.read_text(encoding="utf-8")
    text = replace_once(
        text,
        """      val parser = NativeDiagnosticMapper.classify(
        "input.mlir:1:4: error: expected operation name\\n",
        1
      )
""",
        """      val parser = NativeDiagnosticMapper.classify(
        "/tmp/nodal-scala-mlir-123456/input.mlir:1:4: error: expected operation name\\n",
        1
      )
""",
        "exercise staged parser path normalization",
    )
    text = replace_once(
        text,
        """      assert(parser.code == "NODAL-DIAGNOSTIC-PARSER-001")
      assert(pass.code == "NODAL-DIAGNOSTIC-PASS-001")
""",
        """      assert(parser.code == "NODAL-DIAGNOSTIC-PARSER-001")
      assert(parser.message.contains("<bridge-input>:1:4"))
      assert(!parser.message.contains("nodal-scala-mlir-123456"))
      assert(pass.code == "NODAL-DIAGNOSTIC-PASS-001")
""",
        "assert stable parser fallback message",
    )
    path.write_text(text, encoding="utf-8")


def patch_inventory_fixture(root: Path) -> None:
    path = root / "core/compiler/test/IR/diagnostic-mapping-inventory-invalid.mlir"
    text = path.read_text(encoding="utf-8")
    sections = [section.strip() for section in text.split("// -----") if section.strip()]
    if len(sections) != 8:
        raise RuntimeError(f"inventory fixture: expected 8 sections, found {len(sections)}")
    fixed: list[str] = []
    for index, section in enumerate(sections):
        if not section.startswith("module attributes {"):
            raise RuntimeError(f"inventory section {index}: unexpected start")
        if "\n} {\n}" in section[-12:]:
            fixed.append(section)
            continue
        if not section.endswith("}"):
            raise RuntimeError(f"inventory section {index}: missing attribute dictionary close")
        fixed.append(section[:-1].rstrip() + "\n} {\n}")
    path.write_text(
        "\n\n".join("// -----\n" + section for section in fixed) + "\n",
        encoding="utf-8",
    )


def patch_operation_fixture(root: Path) -> None:
    path = root / "core/compiler/test/IR/diagnostic-mapping-operation-invalid.mlir"
    text = path.read_text(encoding="utf-8")
    child_location = ' -> () loc("src/Top.scala":70:5)'
    if child_location in text:
        text = replace_once(
            text,
            child_location,
            " -> ()",
            "move open-drain location to ancestor",
        )
    ancestor_location = '  }) : () -> () loc("src/Top.scala":70:5)\n}'
    if ancestor_location not in text:
        tail = "  }) : () -> ()\n}"
        position = text.rfind(tail)
        if position < 0:
            raise RuntimeError("open-drain ancestor location: tail not found")
        text = text[:position] + ancestor_location + text[position + len(tail) :]
    path.write_text(text, encoding="utf-8")


def patch_workflow(root: Path) -> None:
    path = root / ".github/workflows/increment-22-cross-layer-diagnostics.yml"
    text = path.read_text(encoding="utf-8")
    text = replace_once(
        text,
        """                      if missing:
                          print(combined)
                          raise SystemExit(
                              f"{fixture.name} section {index}: missing {missing}"
                          )
                      print(f"verified {fixture.name} section {index}: {required[0]}")
""",
        """                      if missing:
                          print(combined)
                          raise SystemExit(
                              f"{fixture.name} section {index}: missing {missing}"
                          )
                      if (
                          required[0] == "NODAL-INTERFACE-ROLE-002"
                          and "[hierarchy-path=" in combined
                      ):
                          print(combined)
                          raise SystemExit(
                              "inventory-only diagnostics must not invent hierarchy context"
                          )
                      print(f"verified {fixture.name} section {index}: {required[0]}")
""",
        "assert inventory hierarchy omission",
    )
    path.write_text(text, encoding="utf-8")


def patch_checker(root: Path) -> None:
    path = root / "scripts/check_increment22.py"
    text = path.read_text(encoding="utf-8")
    text = replace_once(
        text,
        """            "nodal-verify-cross-layer-diagnostics",
            "markAllAnalysesPreserved",
        ) + REQUIRED_CODES[:15],
""",
        """            "nodal-verify-cross-layer-diagnostics",
            "markAllAnalysesPreserved",
            "current && !file",
        ) + REQUIRED_CODES[:15],
""",
        "require ancestor source lookup",
    )
    text = replace_once(
        text,
        """        "native diagnostic mapper",
    )
    require(
        passes,
""",
        """        "native diagnostic mapper",
    )
    if "context.hierarchyPath = path.str();" in native:
        problems.append(
            Problem(
                "NODAL-INC22-004",
                "inventory-only diagnostics invent hierarchy context from semantic paths",
            )
        )
    require(
        passes,
""",
        "reject invented inventory hierarchy",
    )
    text = replace_once(
        text,
        """            "index-path",
            "source-range",
        ),
""",
        """            "index-path",
            "source-range",
            "StagedInputPath",
            "<bridge-input>",
        ),
""",
        "require temporary path sanitization",
    )
    path.write_text(text, encoding="utf-8")


def patch_checker_tests(root: Path) -> None:
    path = root / "tests/compiler/test_increment22.py"
    text = path.read_text(encoding="utf-8")
    anchor = """    def test_rejects_premature_roadmap_closure(self) -> None:
"""
    methods = """    def test_rejects_invented_inventory_hierarchy(self) -> None:
        temporary, root = self.temporary_repository()
        self.addCleanup(temporary.cleanup)
        path = root / "core/compiler/lib/Diagnostics/DiagnosticMapping.cpp"
        path.write_text(
            path.read_text(encoding="utf-8").replace(
                "  context.semanticPath = path.str();\\n  context.indexPath",
                "  context.semanticPath = path.str();\\n  context.hierarchyPath = path.str();\\n  context.indexPath",
            ),
            encoding="utf-8",
        )
        self.assertIn("NODAL-INC22-004", self.codes(root))

    def test_rejects_missing_ancestor_source_lookup(self) -> None:
        temporary, root = self.temporary_repository()
        self.addCleanup(temporary.cleanup)
        path = root / "core/compiler/lib/Diagnostics/DiagnosticMapping.cpp"
        path.write_text(
            path.read_text(encoding="utf-8").replace(
                "current && !file",
                "current && false",
            ),
            encoding="utf-8",
        )
        self.assertIn("NODAL-INC22-004", self.codes(root))

    def test_rejects_unsanitized_staged_input_path(self) -> None:
        temporary, root = self.temporary_repository()
        self.addCleanup(temporary.cleanup)
        path = root / "core/scala/bridge/src/nodal/bridge/NativeDiagnosticMapper.scala"
        path.write_text(
            path.read_text(encoding="utf-8").replace(
                "<bridge-input>",
                "<temporary-input>",
            ),
            encoding="utf-8",
        )
        self.assertIn("NODAL-INC22-006", self.codes(root))

"""
    if methods.strip() not in text:
        text = replace_once(text, anchor, methods + anchor, "add review regression tests")
    path.write_text(text, encoding="utf-8")


def main() -> None:
    root = Path(__file__).resolve().parents[1]
    patch_native_mapping(root)
    patch_scala_mapper(root)
    patch_scala_tests(root)
    patch_inventory_fixture(root)
    patch_operation_fixture(root)
    patch_workflow(root)
    patch_checker(root)
    patch_checker_tests(root)


if __name__ == "__main__":
    main()
