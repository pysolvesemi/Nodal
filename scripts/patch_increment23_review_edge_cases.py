#!/usr/bin/env python3
"""Fix Increment 23 target-parser review edge cases and add regression coverage."""

from __future__ import annotations

import argparse
from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: expected one match, found {count}")
    return text.replace(old, new, 1)


def patch_backend(root: Path) -> None:
    path = root / "core/compiler/lib/Backend/Backend.cpp"
    text = path.read_text(encoding="utf-8")
    text = replace_once(
        text,
        '#include <string>\n',
        '#include <string>\n',
        "stable C++ include anchor",
    )

    old_identifier = '''bool isIdentifier(llvm::StringRef value) {
  if (value.empty() || !(llvm::isAlpha(value.front()) || value.front() == '_'))
    return false;
  for (char character : value.drop_front()) {
    if (!(llvm::isAlnum(character) || character == '_' || character == '$'))
      return false;
  }
  return true;
}
'''
    new_identifier = '''constexpr llvm::StringLiteral kVerilogReservedIdentifiers[] = {
    "always",      "analog",      "and",          "assign",       "automatic",
    "begin",       "branch",      "buf",          "bufif0",       "bufif1",
    "case",        "casex",       "casez",        "cell",         "cmos",
    "config",      "deassign",    "default",      "defparam",     "design",
    "disable",     "discipline",  "edge",         "else",         "end",
    "endcase",     "endconfig",   "endfunction",  "endgenerate",  "endmodule",
    "endnature",   "endprimitive", "endspecify",  "endtable",     "endtask",
    "event",       "flow",        "for",          "force",        "forever",
    "fork",        "function",    "generate",     "genvar",       "ground",
    "highz0",      "highz1",      "if",           "ifnone",       "incdir",
    "include",     "initial",     "inout",        "input",        "instance",
    "integer",     "join",        "large",        "liblist",      "library",
    "localparam",  "macromodule", "medium",       "module",       "nand",
    "nature",      "negedge",     "nmos",         "nor",          "noshowcancelled",
    "not",         "notif0",      "notif1",       "or",           "output",
    "parameter",   "pmos",        "posedge",      "potential",    "primitive",
    "pull0",       "pull1",       "pulldown",     "pullup",       "rcmos",
    "real",        "realtime",    "reg",          "release",      "repeat",
    "rnmos",       "rpmos",       "rtran",        "rtranif0",     "rtranif1",
    "scalared",    "signed",      "small",        "specify",      "specparam",
    "strong0",     "strong1",     "supply0",      "supply1",      "table",
    "task",        "time",        "tran",         "tranif0",      "tranif1",
    "tri",         "tri0",        "tri1",         "triand",       "trior",
    "trireg",      "unsigned",    "use",          "vectored",     "wait",
    "wand",        "weak0",       "weak1",        "while",        "wire",
    "wor",         "xnor",        "xor",
};

bool isIdentifier(llvm::StringRef value) {
  if (value.empty() || !(llvm::isAlpha(value.front()) || value.front() == '_'))
    return false;
  for (char character : value.drop_front()) {
    if (!(llvm::isAlnum(character) || character == '_' || character == '$'))
      return false;
  }
  return !llvm::is_contained(kVerilogReservedIdentifiers, value);
}
'''
    text = replace_once(text, old_identifier, new_identifier, "reserved identifier handling")

    old_check = '''FailureOr<GateProfile> parseCheckProfile(ModuleOp module, GateProfile defaultProfile) {
  auto value = module->getAttrOfType<StringAttr>("nodal.backend.check_profile");
  if (!value)
    return defaultProfile;
'''
    new_check = '''FailureOr<GateProfile> parseCheckProfile(ModuleOp module, GateProfile defaultProfile) {
  Attribute raw = module->getAttr("nodal.backend.check_profile");
  if (!raw)
    return defaultProfile;
  auto value = llvm::dyn_cast<StringAttr>(raw);
  if (!value) {
    (void)emitMappedFailure(module.getOperation(), "NODAL-BACKEND-CONFIG-001",
                            "CheckProfile must be a string attribute");
    return failure();
  }
'''
    text = replace_once(text, old_check, new_check, "typed check-profile parsing")

    old_owned = '''LogicalResult requireOwnedSetting(ModuleOp module, llvm::StringRef attribute,
                                  llvm::StringRef expected, llvm::StringRef label) {
  auto value = module->getAttrOfType<StringAttr>(attribute);
  if (!value || value.getValue() == expected)
    return success();
  return emitMappedFailure(module.getOperation(), "NODAL-BACKEND-CONFIG-002",
                           llvm::Twine(label) +
                               " is owned by the selected backend profile; expected '" + expected +
                               "', got '" + value.getValue() + "'");
}
'''
    new_owned = '''LogicalResult requireOwnedSetting(ModuleOp module, llvm::StringRef attribute,
                                  llvm::StringRef expected, llvm::StringRef label) {
  Attribute raw = module->getAttr(attribute);
  if (!raw)
    return success();
  auto value = llvm::dyn_cast<StringAttr>(raw);
  if (!value)
    return emitMappedFailure(module.getOperation(), "NODAL-BACKEND-CONFIG-002",
                             llvm::Twine(label) + " must be a string attribute");
  if (value.getValue() == expected)
    return success();
  return emitMappedFailure(module.getOperation(), "NODAL-BACKEND-CONFIG-002",
                           llvm::Twine(label) +
                               " is owned by the selected backend profile; expected '" + expected +
                               "', got '" + value.getValue() + "'");
}
'''
    text = replace_once(text, old_owned, new_owned, "typed profile-owned settings")

    old_kind = '''LogicalResult verifyDesignKind(ModuleOp module, const BackendProfile &profile) {
  llvm::StringRef kind = "target_neutral";
  if (auto value = module->getAttrOfType<StringAttr>("nodal.target.profile"))
    kind = value.getValue();
'''
    new_kind = '''LogicalResult verifyDesignKind(ModuleOp module, const BackendProfile &profile) {
  llvm::StringRef kind = "target_neutral";
  if (Attribute raw = module->getAttr("nodal.target.profile")) {
    auto value = llvm::dyn_cast<StringAttr>(raw);
    if (!value)
      return emitMappedFailure(module.getOperation(), "NODAL-BACKEND-PROFILE-001",
                               "design kind must be a string attribute");
    kind = value.getValue();
  }
'''
    text = replace_once(text, old_kind, new_kind, "typed design-kind setting")

    old_counter = '''size_t countOccurrences(llvm::StringRef text, llvm::StringRef needle) {
  size_t count = 0;
  size_t offset = 0;
  while (true) {
    size_t found = text.find(needle, offset);
    if (found == llvm::StringRef::npos)
      return count;
    ++count;
    offset = found + needle.size();
  }
}
'''
    new_counter = '''size_t countModuleDeclarations(llvm::StringRef text) {
  llvm::SmallVector<llvm::StringRef, 32> lines;
  text.split(lines, '\\n', -1, true);
  return llvm::count_if(lines, [](llvm::StringRef line) {
    line = line.trim();
    return line.starts_with("module ") && line.ends_with(";");
  });
}

size_t countExactLines(llvm::StringRef text, llvm::StringRef expected) {
  llvm::SmallVector<llvm::StringRef, 32> lines;
  text.split(lines, '\\n', -1, true);
  return llvm::count_if(lines,
                        [&](llvm::StringRef line) { return line.trim() == expected; });
}
'''
    text = replace_once(text, old_counter, new_counter, "structural statement counting")
    text = replace_once(
        text,
        '''    if (countOccurrences(candidate, "module ") != countOccurrences(candidate, "endmodule"))
      return failure();
''',
        '''    if (countModuleDeclarations(candidate) != countExactLines(candidate, "endmodule"))
      return failure();
''',
        "structural target balance check",
    )

    old_selected = '''  if (auto selected = module->getAttrOfType<StringAttr>("nodal.backend.profile")) {
    if (selected.getValue() != profile.id) {
      (void)emitMappedFailure(module.getOperation(), "NODAL-BACKEND-PROFILE-002",
                              llvm::Twine("translation '") + profile.translation +
                                  "' does not match requested backend profile '" +
                                  selected.getValue() + "'");
      return failure();
    }
  }
'''
    new_selected = '''  if (Attribute raw = module->getAttr("nodal.backend.profile")) {
    auto selected = llvm::dyn_cast<StringAttr>(raw);
    if (!selected) {
      (void)emitMappedFailure(module.getOperation(), "NODAL-BACKEND-PROFILE-002",
                              "backend profile must be a string attribute");
      return failure();
    }
    if (selected.getValue() != profile.id) {
      (void)emitMappedFailure(module.getOperation(), "NODAL-BACKEND-PROFILE-002",
                              llvm::Twine("translation '") + profile.translation +
                                  "' does not match requested backend profile '" +
                                  selected.getValue() + "'");
      return failure();
    }
  }
'''
    text = replace_once(text, old_selected, new_selected, "typed backend profile setting")
    path.write_text(text, encoding="utf-8")


def patch_unit_test(root: Path) -> None:
    path = root / "core/compiler/test/Unit/BackendTest.cpp"
    text = path.read_text(encoding="utf-8")
    fixture_anchor = '''constexpr llvm::StringLiteral kUnsupportedModule = R"mlir(
'''
    fixtures = r'''constexpr llvm::StringLiteral kReservedModule = R"mlir(
module attributes {
  nodal.backend.profile = "verilog-a",
  nodal.target.profile = "analog"
} {
  "nodal.module"() <{metadata = {root = true}, sym_name = "input"}> ({
  ^bb0:
  }) : () -> ()
}
)mlir";

constexpr llvm::StringLiteral kEndmoduleSubstringModule = R"mlir(
module attributes {
  nodal.backend.profile = "verilog-a",
  nodal.target.profile = "analog"
} {
  "nodal.module"() <{metadata = {root = true}, sym_name = "myendmoduleBlock"}> ({
  ^bb0:
  }) : () -> ()
}
)mlir";

constexpr llvm::StringLiteral kMalformedLayout = R"mlir(
module attributes {
  nodal.backend.profile = "verilog-a",
  nodal.backend.shaped_layout = 1 : i64,
  nodal.target.profile = "analog"
} {
  "nodal.module"() <{metadata = {root = true}, sym_name = "Top"}> ({
  ^bb0:
  }) : () -> ()
}
)mlir";

'''
    if "kReservedModule" not in text:
        text = replace_once(text, fixture_anchor, fixtures + fixture_anchor, "backend edge fixtures")

    main_anchor = '''  auto unsupported = parse(context, kUnsupportedModule);
'''
    assertions = r'''  auto reserved = parse(context, kReservedModule);
  if (!reserved)
    return fail("could not parse the reserved-keyword fixture");
  std::string reservedOutput = "sentinel";
  llvm::raw_string_ostream reservedStream(reservedOutput);
  if (mlir::succeeded(
          nodal::emitBackend(*reserved, nodal::BackendKind::VerilogA, reservedStream)))
    return fail("reserved Verilog module name was accepted");
  reservedStream.flush();
  if (reservedOutput != "sentinel")
    return fail("reserved-name failure published partial output");

  auto substring = parse(context, kEndmoduleSubstringModule);
  if (!substring)
    return fail("could not parse the endmodule-substring fixture");
  std::string substringOutput;
  llvm::raw_string_ostream substringStream(substringOutput);
  if (mlir::failed(
          nodal::emitBackend(*substring, nodal::BackendKind::VerilogA, substringStream)))
    return fail("valid identifier containing endmodule was rejected");
  substringStream.flush();
  if (substringOutput.find("module myendmoduleBlock;") == std::string::npos)
    return fail("endmodule-substring module was not emitted");

  auto malformed = parse(context, kMalformedLayout);
  if (!malformed)
    return fail("could not parse the malformed-layout fixture");
  std::string malformedOutput = "sentinel";
  llvm::raw_string_ostream malformedStream(malformedOutput);
  if (mlir::succeeded(
          nodal::emitBackend(*malformed, nodal::BackendKind::VerilogA, malformedStream)))
    return fail("non-string profile-owned layout was accepted");
  malformedStream.flush();
  if (malformedOutput != "sentinel")
    return fail("malformed profile setting published partial output");

'''
    if "reserved Verilog module name was accepted" not in text:
        text = replace_once(text, main_anchor, assertions + main_anchor, "backend edge assertions")
    path.write_text(text, encoding="utf-8")


def patch_checker(root: Path) -> None:
    path = root / "scripts/check_increment23.py"
    text = path.read_text(encoding="utf-8")
    anchor = '''    if "  output << candidate;\\n  return success();" not in backend:
'''
    checks = '''    for fragment in (
        "kVerilogReservedIdentifiers",
        '"input"',
        "Attribute raw = module->getAttr(attribute)",
        "countModuleDeclarations",
        "countExactLines",
    ):
        if fragment not in backend:
            problems.append(
                Problem(
                    "NODAL-INC23-004",
                    f"backend target parser lacks review contract: {fragment}",
                )
            )
    if "countOccurrences" in backend:
        problems.append(
            Problem(
                "NODAL-INC23-004",
                "backend target verification uses substring occurrence counting",
            )
        )

'''
    if "backend target parser lacks review contract" not in text:
        text = replace_once(text, anchor, checks + anchor, "review checker insertion")
    path.write_text(text, encoding="utf-8")


def patch_checker_tests(root: Path) -> None:
    path = root / "tests/compiler/test_increment23.py"
    text = path.read_text(encoding="utf-8")
    anchor = '''    def test_rejects_missing_backend_diagnostic(self) -> None:
'''
    tests = '''    def test_rejects_missing_reserved_keyword_check(self) -> None:
        temporary, root = self.temporary_repository()
        self.addCleanup(temporary.cleanup)
        path = root / "core/compiler/lib/Backend/Backend.cpp"
        path.write_text(
            path.read_text(encoding="utf-8").replace('    "input",', '    "input_removed",', 1),
            encoding="utf-8",
        )
        self.assertIn("NODAL-INC23-004", self.codes(root))

    def test_rejects_substring_terminator_counting(self) -> None:
        temporary, root = self.temporary_repository()
        self.addCleanup(temporary.cleanup)
        path = root / "core/compiler/lib/Backend/Backend.cpp"
        path.write_text(
            path.read_text(encoding="utf-8") + "\\n// countOccurrences\\n",
            encoding="utf-8",
        )
        self.assertIn("NODAL-INC23-004", self.codes(root))

    def test_rejects_untyped_profile_owned_setting(self) -> None:
        temporary, root = self.temporary_repository()
        self.addCleanup(temporary.cleanup)
        path = root / "core/compiler/lib/Backend/Backend.cpp"
        path.write_text(
            path.read_text(encoding="utf-8").replace(
                "Attribute raw = module->getAttr(attribute)",
                "auto raw = module->getAttrOfType<StringAttr>(attribute)",
                1,
            ),
            encoding="utf-8",
        )
        self.assertIn("NODAL-INC23-004", self.codes(root))

'''
    if "test_rejects_missing_reserved_keyword_check" not in text:
        text = replace_once(text, anchor, tests + anchor, "review checker tests")
    path.write_text(text, encoding="utf-8")


def patch_docs(root: Path) -> None:
    path = root / "docs/implementation/increment23-backend-framework.md"
    text = path.read_text(encoding="utf-8")
    anchor = '''## Validation
'''
    note = '''## Target parser edge contracts

Portable module identifiers exclude Verilog-family and AMS reserved words. Target
verification counts complete module declaration and `endmodule` lines rather
than substrings, so legal names such as `myendmoduleBlock` remain valid. Every
profile-owned module attribute is type-checked before defaults are considered;
a present non-string attribute is malformed configuration, not an absent value.

'''
    if "## Target parser edge contracts" not in text:
        text = replace_once(text, anchor, note + anchor, "review documentation")
    path.write_text(text, encoding="utf-8")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("root", type=Path)
    args = parser.parse_args()
    root = args.root.resolve()
    patch_backend(root)
    patch_unit_test(root)
    patch_checker(root)
    patch_checker_tests(root)
    patch_docs(root)


if __name__ == "__main__":
    main()
