#!/usr/bin/env python3
"""Synchronize and complete the Increment 22 cross-layer diagnostic integration."""

from __future__ import annotations

import argparse
import re
from pathlib import Path


def ensure_replace(text: str, old: str, new: str, label: str) -> str:
    if new in text:
        return text
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected one old anchor, found {count}")
    return text.replace(old, new, 1)


def qualify_builtin_modules(text: str) -> str:
    text = re.sub(
        r"(?<![:A-Za-z0-9_])ModuleOp(?![A-Za-z0-9_])",
        "mlir::ModuleOp",
        text,
    )
    return text.replace(".getBody().getOperations()", ".getBody()->getOperations()")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("root", type=Path)
    args = parser.parse_args()
    root = args.root.resolve()

    diagnostic_path = root / "core/compiler/lib/Diagnostics/DiagnosticMapping.cpp"
    diagnostic = diagnostic_path.read_text(encoding="utf-8")
    diagnostic = ensure_replace(
        diagnostic,
        '#include "mlir/IR/BuiltinLocationAttributes.h"',
        '#include "mlir/IR/Location.h"',
        "pinned MLIR location header",
    )
    diagnostic = diagnostic.replace(
        """FileLineColLoc findFileLocation(Location location) {
  if (!location)
    return {};
""",
        """FileLineColLoc findFileLocation(Location location) {
""",
        1,
    )
    diagnostic = qualify_builtin_modules(diagnostic)
    diagnostic_path.write_text(diagnostic, encoding="utf-8")

    passes_path = root / "core/compiler/lib/Transforms/Passes.cpp"
    passes = passes_path.read_text(encoding="utf-8")
    passes = ensure_replace(
        passes,
        '#include "nodal/Transforms/Passes.h"\n',
        '#include "nodal/Transforms/Passes.h"\n\n#include "nodal/Diagnostics/DiagnosticMapping.h"\n',
        "diagnostic include",
    )
    passes = ensure_replace(
        passes,
        """LogicalResult emitFailure(Operation *operation, llvm::StringRef code,
                          const llvm::Twine &message) {
  operation->emitError() << code << ": " << message;
  return failure();
}
""",
        """LogicalResult emitFailure(Operation *operation, llvm::StringRef code,
                          const llvm::Twine &message) {
  return emitMappedFailure(operation, code, message);
}
""",
        "mapped failure delegation",
    )
    passes = ensure_replace(
        passes,
        """  manager.addPass(std::make_unique<VerifyCapabilitiesPass>());
}
""",
        """  manager.addPass(createCrossLayerDiagnosticPass());
  manager.addPass(std::make_unique<VerifyCapabilitiesPass>());
}
""",
        "cross-layer diagnostic pass insertion",
    )
    passes = qualify_builtin_modules(passes)
    passes = passes.replace("accepted->clone()", "accepted.get()->clone()")
    passes_path.write_text(passes, encoding="utf-8")

    bridge_path = root / "core/scala/bridge/src/nodal/bridge/ScalaToMlirBridge.scala"
    bridge = bridge_path.read_text(encoding="utf-8")
    bridge = ensure_replace(
        bridge,
        """private[nodal] final case class BridgeDiagnostic(
    code: String,
    message: String,
    semanticPath: Option[String] = None
):
  override def toString: String = semanticPath match
    case Some(path) => s"$code: $message [$path]"
    case None => s"$code: $message"
""",
        """private[nodal] final case class BridgeDiagnostic(
    code: String,
    message: String,
    semanticPath: Option[String] = None,
    hierarchyPath: Option[String] = None,
    indexPath: Option[String] = None,
    sourceRange: Option[String] = None
):
  override def toString: String =
    val context = Vector(
      semanticPath.map(value => s"[semantic-path=$value]"),
      hierarchyPath.map(value => s"[hierarchy-path=$value]"),
      indexPath.map(value => s"[index-path=$value]"),
      sourceRange.map(value => s"[source-range=$value]")
    ).flatten
    if context.isEmpty then s"$code: $message"
    else s"$code: $message ${context.mkString(" ")}"
""",
        "BridgeDiagnostic context fields",
    )
    bridge_path.write_text(bridge, encoding="utf-8")

    client_path = root / "core/scala/bridge/src/nodal/bridge/NativeCompilerClient.scala"
    client = client_path.read_text(encoding="utf-8")
    client = ensure_replace(
        client,
        """          else
            result = Some(
              NativeCompilerFailure(
                BridgeDiagnostic(
                  "NODAL-BRIDGE-PROCESS-007",
                  s"native compiler exited with status $exitCode"
                ),
                command,
                standardOutput,
                standardError,
                Some(exitCode)
              )
            )
""",
        """          else
            val mapped = NativeDiagnosticMapper.classify(standardError, exitCode)
            val diagnostic =
              if mapped.code == "NODAL-DIAGNOSTIC-EXTERNAL-001" then
                BridgeDiagnostic(
                  "NODAL-BRIDGE-PROCESS-007",
                  s"native compiler exited with status $exitCode"
                )
              else mapped
            result = Some(
              NativeCompilerFailure(
                diagnostic,
                command,
                standardOutput,
                standardError,
                Some(exitCode)
              )
            )
""",
        "native diagnostic classification",
    )
    client_path.write_text(client, encoding="utf-8")

    workflow_path = root / ".github/workflows/increment-22-cross-layer-diagnostics.yml"
    workflow = workflow_path.read_text(encoding="utf-8")
    marker = "      - name: Prove mapped Interface inout and AMS diagnostics\n"
    explicit_fixtures = (
        "      # Explicit fixture inventory for structural contracts:\n"
        "      # core/compiler/test/IR/diagnostic-mapping-inventory-invalid.mlir\n"
        "      # core/compiler/test/IR/diagnostic-mapping-operation-invalid.mlir\n"
    )
    if "diagnostic-mapping-inventory-invalid.mlir" not in workflow:
        if workflow.count(marker) != 1:
            raise SystemExit("workflow fixture inventory anchor is not unique")
        workflow = workflow.replace(marker, explicit_fixtures + marker, 1)
    workflow_path.write_text(workflow, encoding="utf-8")


if __name__ == "__main__":
    main()
