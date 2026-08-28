#!/usr/bin/env python3
"""Apply focused Increment 29 native/compiler compatibility repairs."""

from __future__ import annotations

import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def replace_once(relative: str, old: str, new: str) -> None:
    path = ROOT / relative
    text = path.read_text(encoding="utf-8")
    if text.count(old) != 1:
        raise RuntimeError(f"expected one anchor in {relative}: {old!r}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


replace_once(
    "core/compiler/lib/Dialect/Nodal/ParameterSemantics.cpp",
    "spelling.getValue().contains_insensitive('e');",
    "(spelling.getValue().contains('e') || spelling.getValue().contains('E'));",
)
replace_once(
    "core/compiler/lib/Dialect/Nodal/ParameterSemantics.cpp",
    "if (!parameters.contains(dependency))",
    "if (parameters.find(dependency) == parameters.end())",
)

path = ROOT / "core/compiler/lib/Dialect/Nodal/ParameterSemantics.cpp"
text = path.read_text(encoding="utf-8")
pattern = re.compile(
    r'''      if \(renderings\) \{\n        const char left = includeLower \? '\\[' : '\\(';\n        const char right = includeUpper \? '\\]' : '\\)';\n        renderings->push_back\(\(llvm::Twine\("from "\) \+ left \+ lower->rendered \+ ":" \+\n                               upper->rendered \+ right\)\n                                  \.str\(\)\);\n      \}'''
)
replacement = '''      if (renderings) {
        std::string rendered = "from ";
        rendered.push_back(includeLower ? '[' : '(');
        rendered += lower->rendered;
        rendered += ':';
        rendered += upper->rendered;
        rendered.push_back(includeUpper ? ']' : ')');
        renderings->push_back(std::move(rendered));
      }'''
text, count = pattern.subn(replacement, text, count=1)
if count != 1:
    raise RuntimeError("could not repair range rendering construction")
path.write_text(text, encoding="utf-8")

replace_once(
    "core/compiler/lib/Transforms/Passes.cpp",
    '''            if (!type || !bindingFits(binding.getValue(), type.getValue()))
              result = emitFailure(operation, "NODAL-VERIFY-PARAMETER-004",
''',
    '''            if (!type ||
                (!llvm::isa<DictionaryAttr>(binding.getValue()) &&
                 !bindingFits(binding.getValue(), type.getValue())))
              result = emitFailure(operation, "NODAL-VERIFY-PARAMETER-004",
''',
)

replace_once(
    "docs/design-gates/NodalParametersUnits-DG-v1.0.md",
    "References are module-local, declaration order is not semantic, cycles are\nrejected, and references to non-parameters are rejected as dynamic values.\n",
    "References are module-local, declaration order is not semantic, cycles are\nrejected, and references to non-parameters are rejected as dynamic values. In\nthis contract, dynamic values are not parameter declarations.\n",
)

print("Increment 29 focused repair applied")
