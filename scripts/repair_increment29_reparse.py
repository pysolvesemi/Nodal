#!/usr/bin/env python3
"""Extend the fail-closed Verilog-A target reparse gate for native parameters."""
from __future__ import annotations

from pathlib import Path
from textwrap import dedent

ROOT = Path(__file__).resolve().parents[1]

backend_path = ROOT / "core/compiler/lib/Backend/AnalogVerticalSlice.cpp"
backend = backend_path.read_text(encoding="utf-8")
start = backend.index("LogicalResult reparseBackendTarget(")
end_marker = "\n}\n\n} // namespace nodal"
end = backend.index(end_marker, start) + 2
replacement = dedent(
    r'''
    LogicalResult reparseBackendTarget(llvm::StringRef candidate, const BackendConfiguration &) {
      auto validParameterDeclaration = [](llvm::StringRef line) {
        llvm::StringRef code = line;
        size_t comment = code.find("//");
        if (comment != llvm::StringRef::npos) {
          llvm::StringRef annotation = code.drop_front(comment + 2).trim();
          constexpr llvm::StringLiteral unitPrefix = "unit: ";
          if (!annotation.starts_with(unitPrefix))
            return false;
          llvm::StringRef unit = annotation.drop_front(unitPrefix.size()).trim();
          if (!validIdentifierList(unit))
            return false;
          code = code.take_front(comment).rtrim();
        }

        if (!code.ends_with(";"))
          return false;
        code = code.drop_back().trim();

        if (!code.consume_front("parameter real ") &&
            !code.consume_front("parameter integer "))
          return false;

        size_t equals = code.find(" = ");
        if (equals == llvm::StringRef::npos ||
            !validIdentifierList(code.take_front(equals).trim()))
          return false;

        llvm::StringRef tail = code.drop_front(equals + 3).trim();
        if (tail.empty())
          return false;

        size_t from = tail.find(" from ");
        size_t exclude = tail.find(" exclude ");
        if (from != llvm::StringRef::npos && exclude != llvm::StringRef::npos && exclude < from)
          return false;

        size_t initializerEnd = tail.size();
        if (from != llvm::StringRef::npos)
          initializerEnd = std::min(initializerEnd, from);
        if (exclude != llvm::StringRef::npos)
          initializerEnd = std::min(initializerEnd, exclude);
        llvm::StringRef initializer = tail.take_front(initializerEnd).trim();
        if (initializer.empty() || initializer.contains(';'))
          return false;

        if (from != llvm::StringRef::npos) {
          size_t rangeStart = from + sizeof(" from ") - 1;
          size_t rangeEnd = exclude == llvm::StringRef::npos ? tail.size() : exclude;
          if (rangeStart >= rangeEnd)
            return false;
          llvm::StringRef range = tail.slice(rangeStart, rangeEnd).trim();
          if (range.size() < 3 ||
              (range.front() != '[' && range.front() != '(') ||
              (range.back() != ']' && range.back() != ')'))
            return false;
          llvm::StringRef bounds = range.drop_front().drop_back();
          size_t colon = bounds.find(':');
          if (colon == llvm::StringRef::npos ||
              bounds.drop_front(colon + 1).contains(':') ||
              bounds.take_front(colon).trim().empty() ||
              bounds.drop_front(colon + 1).trim().empty())
            return false;
        }

        if (exclude != llvm::StringRef::npos &&
            tail.drop_front(exclude + sizeof(" exclude ") - 1).trim().empty())
          return false;
        return true;
      };

      llvm::SmallVector<llvm::StringRef, 64> lines;
      candidate.split(lines, '\n', -1, true);
      bool insideModule = false;
      bool insideAnalog = false;
      bool sawModule = false;
      for (llvm::StringRef raw : lines) {
        llvm::StringRef line = raw.trim();
        if (line.empty() || line.starts_with("/*") || line.starts_with("*") ||
            line.starts_with("`include "))
          continue;
        if (!insideModule && line.starts_with("module ") && line.ends_with(";")) {
          llvm::StringRef declaration = line.drop_front(sizeof("module ") - 1).drop_back();
          size_t open = declaration.find('(');
          if (open != llvm::StringRef::npos) {
            if (!declaration.ends_with(")") ||
                !validIdentifierList(declaration.slice(open + 1, declaration.size() - 1)))
              return failure();
            declaration = declaration.take_front(open);
          }
          if (!validIdentifierList(declaration))
            return failure();
          insideModule = true;
          sawModule = true;
          continue;
        }
        if (!insideModule)
          return failure();
        if (line == "analog begin") {
          if (insideAnalog)
            return failure();
          insideAnalog = true;
          continue;
        }
        if (line == "end") {
          if (!insideAnalog)
            return failure();
          insideAnalog = false;
          continue;
        }
        if (line == "endmodule") {
          if (insideAnalog)
            return failure();
          insideModule = false;
          continue;
        }
        if (insideAnalog) {
          if (!line.ends_with(";") || !line.contains("<+"))
            return failure();
          continue;
        }
        if ((line.starts_with("input ") || line.starts_with("output ") ||
             line.starts_with("inout ") || line.starts_with("electrical ")) &&
            line.ends_with(";") &&
            validIdentifierList(line.drop_front(line.find(' ') + 1).drop_back()))
          continue;
        if (validParameterDeclaration(line))
          continue;
        return failure();
      }
      return sawModule && !insideModule && !insideAnalog ? success() : failure();
    }
    '''
).strip()
backend_path.write_text(backend[:start] + replacement + backend[end:], encoding="utf-8")

model_path = ROOT / "core/compiler/lib/Dialect/Nodal/ParameterModel.cpp"
model = model_path.read_text(encoding="utf-8")
old = "bool splitSpelling(llvm::StringRef spelling, llvm::StringRef suffix, llvm::StringRef &numeric) {\n  if (!canonicalText(spelling))"
new = "bool splitSpelling(llvm::StringRef spelling, llvm::StringRef suffix, llvm::StringRef &numeric) {\n  if (!allowedSuffix(suffix) || !canonicalText(spelling))"
if model.count(old) != 1:
    raise RuntimeError("unexpected Increment 29 suffix validation anchor")
model_path.write_text(model.replace(old, new, 1), encoding="utf-8")
