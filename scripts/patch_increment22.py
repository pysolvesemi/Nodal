#!/usr/bin/env python3
"""Apply focused Increment 22 integrations to an existing Nodal checkout."""

from __future__ import annotations

import argparse
from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected one match, found {count}")
    return text.replace(old, new, 1)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("root", type=Path)
    args = parser.parse_args()
    root = args.root.resolve()

    diagnostic_path = root / "core/compiler/lib/Diagnostics/DiagnosticMapping.cpp"
    diagnostic = diagnostic_path.read_text(encoding="utf-8")
    diagnostic = replace_once(
        diagnostic,
        '#include "mlir/IR/BuiltinLocationAttributes.h"',
        '#include "mlir/IR/Location.h"',
        "pinned MLIR location header",
    )
    diagnostic_path.write_text(diagnostic, encoding="utf-8")

    passes_path = root / "core/compiler/lib/Transforms/Passes.cpp"
    passes = passes_path.read_text(encoding="utf-8")
    passes = replace_once(
        passes,
        '#include "nodal/Transforms/Passes.h"\n',
        '#include "nodal/Transforms/Passes.h"\n\n#include "nodal/Diagnostics/DiagnosticMapping.h"\n',
        "diagnostic include",
    )
    passes = replace_once(
        passes,
        '''LogicalResult emitFailure(Operation *operation, llvm::StringRef code,
                          const llvm::Twine &message) {
  operation->emitError() << code << ": " << message;
  return failure();
}
''',
        '''LogicalResult emitFailure(Operation *operation, llvm::StringRef code,
                          const llvm::Twine &message) {
  return emitMappedFailure(operation, code, message);
}
''',
        "mapped failure delegation",
    )
    passes = replace_once(
        passes,
        '''  manager.addPass(std::make_unique<VerifyCapabilitiesPass>());
}
''',
        '''  manager.addPass(createCrossLayerDiagnosticPass());
  manager.addPass(std::make_unique<VerifyCapabilitiesPass>());
}
''',
        "cross-layer pass insertion",
    )
    passes_path.write_text(passes, encoding="utf-8")

    bridge_path = root / "core/scala/bridge/src/nodal/bridge/ScalaToMlirBridge.scala"
    bridge = bridge_path.read_text(encoding="utf-8")
    bridge = replace_once(
        bridge,
        '''private[nodal] final case class BridgeDiagnostic(
    code: String,
    message: String,
    semanticPath: Option[String] = None
):
  override def toString: String = semanticPath match
    case Some(path) => s"$code: $message [$path]"
    case None => s"$code: $message"
''',
        '''private[nodal] final case class BridgeDiagnostic(
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
''',
        "BridgeDiagnostic context",
    )
    bridge_path.write_text(bridge, encoding="utf-8")

    client_path = root / "core/scala/bridge/src/nodal/bridge/NativeCompilerClient.scala"
    client = client_path.read_text(encoding="utf-8")
    client = replace_once(
        client,
        '''          else
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
''',
        '''          else
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
''',
        "native diagnostic classification",
    )
    client_path.write_text(client, encoding="utf-8")


if __name__ == "__main__":
    main()
