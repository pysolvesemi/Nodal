#!/usr/bin/env python3
"""Make VerificationSession use the same transactional acceptance sequence as the pass."""

from __future__ import annotations

import argparse
from pathlib import Path

OLD = '''LogicalResult VerificationSession::accept(ModuleOp candidate, llvm::StringRef target) {
  if (failed(verifyNodalPipeline(candidate, target)))
    return failure();
  std::string text;
  llvm::raw_string_ostream stream(text);
  candidate.print(stream);
  stream << '\\n';
  stream.flush();
  acceptedIR = std::move(text);
  return success();
}'''

NEW = '''LogicalResult VerificationSession::accept(ModuleOp candidate, llvm::StringRef target) {
  if (failed(verifyNodalPipeline(candidate, target)))
    return failure();

  DictionaryAttr originalAttributes = candidate->getAttrDictionary();
  Builder builder(candidate.getContext());
  SmallVector<Attribute> stages;
  for (VerificationStage stage : kStages)
    stages.push_back(builder.getStringAttr(stringifyVerificationStage(stage)));
  candidate->setAttr("nodal.pipeline.normalized", builder.getBoolAttr(true));
  candidate->setAttr("nodal.pipeline.version", builder.getI64IntegerAttr(1));
  candidate->setAttr("nodal.pipeline.target",
                     builder.getStringAttr(effectiveTarget(candidate, target)));
  candidate->setAttr("nodal.pipeline.stages", builder.getArrayAttr(stages));

  if (failed(verifyNodalPipeline(candidate, target))) {
    candidate->setAttrs(originalAttributes);
    candidate.emitError()
        << "NODAL-VERIFY-TRANSACTION-002: verification session rejected "
           "post-normalization state and restored the candidate attributes";
    return failure();
  }

  std::string text;
  llvm::raw_string_ostream stream(text);
  candidate.print(stream);
  stream << '\\n';
  stream.flush();
  acceptedIR = std::move(text);
  return success();
}'''


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("root", type=Path)
    args = parser.parse_args()
    path = args.root.resolve() / "core/compiler/lib/Transforms/Verification.cpp"
    text = path.read_text(encoding="utf-8")
    if NEW in text:
        return 0
    if text.count(OLD) != 1:
        raise SystemExit("VerificationSession::accept source fragment mismatch")
    path.write_text(text.replace(OLD, NEW, 1), encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
