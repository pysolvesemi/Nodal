#include "nodal/Backend/Backend.h"

#include "circt/Dialect/HW/HWDialect.h"
#include "mlir/IR/BuiltinAttributes.h"
#include "mlir/IR/DialectRegistry.h"
#include "mlir/IR/OwningOpRef.h"
#include "mlir/IR/SymbolTable.h"
#include "mlir/Tools/mlir-translate/Translation.h"
#include "nodal/Diagnostics/DiagnosticMapping.h"
#include "nodal/Dialect/Nodal/NodalDialect.h"

#include "llvm/ADT/ArrayRef.h"
#include "llvm/ADT/STLExtras.h"
#include "llvm/ADT/SmallVector.h"
#include "llvm/ADT/StringExtras.h"
#include "llvm/ADT/StringRef.h"
#include "llvm/Support/Casting.h"
#include "llvm/Support/ErrorHandling.h"
#include "llvm/Support/raw_ostream.h"

#include <string>

using namespace mlir;

namespace nodal {
namespace {

constexpr llvm::StringLiteral kVerilogAId = "verilog-a";
constexpr llvm::StringLiteral kVerilogATranslation = "nodal-to-verilog-a";
constexpr llvm::StringLiteral kVerilogAMSId = "verilog-ams";
constexpr llvm::StringLiteral kVerilogAMSTranslation = "nodal-to-verilog-ams";

const BackendProfile kVerilogAProfile{
    BackendKind::VerilogA,
    kVerilogAId,
    kVerilogATranslation,
    ShapedValueLayout::ScalarOrFlat,
    MaterializationPolicy::SafeInline,
    NamingPolicy::Semantic,
    GateProfile::Default,
    true,
    false,
};

const BackendProfile kVerilogAMSProfile{
    BackendKind::VerilogAMS,
    kVerilogAMSId,
    kVerilogAMSTranslation,
    ShapedValueLayout::FlatPacked,
    MaterializationPolicy::Readable,
    NamingPolicy::Semantic,
    GateProfile::Default,
    true,
    true,
};

llvm::StringRef symbolName(Operation *operation) {
  if (auto name = operation->getAttrOfType<StringAttr>(SymbolTable::getSymbolAttrName()))
    return name.getValue();
  return {};
}

constexpr llvm::StringLiteral kVerilogReservedIdentifiers[] = {
    "always",     "analog",       "and",         "assign",      "automatic",
    "begin",      "branch",       "buf",         "bufif0",      "bufif1",
    "case",       "casex",        "casez",       "cell",        "cmos",
    "config",     "deassign",     "default",     "defparam",    "design",
    "disable",    "discipline",   "edge",        "else",        "end",
    "endcase",    "endconfig",    "endfunction", "endgenerate", "endmodule",
    "endnature",  "endprimitive", "endspecify",  "endtable",    "endtask",
    "event",      "flow",         "for",         "force",       "forever",
    "fork",       "function",     "generate",    "genvar",      "ground",
    "highz0",     "highz1",       "if",          "ifnone",      "incdir",
    "include",    "initial",      "inout",       "input",       "instance",
    "integer",    "join",         "large",       "liblist",     "library",
    "localparam", "macromodule",  "medium",      "module",      "nand",
    "nature",     "negedge",      "nmos",        "nor",         "noshowcancelled",
    "not",        "notif0",       "notif1",      "or",          "output",
    "parameter",  "pmos",         "posedge",     "potential",   "primitive",
    "pull0",      "pull1",        "pulldown",    "pullup",      "rcmos",
    "real",       "realtime",     "reg",         "release",     "repeat",
    "rnmos",      "rpmos",        "rtran",       "rtranif0",    "rtranif1",
    "scalared",   "signed",       "small",       "specify",     "specparam",
    "strong0",    "strong1",      "supply0",     "supply1",     "table",
    "task",       "time",         "tran",        "tranif0",     "tranif1",
    "tri",        "tri0",         "tri1",        "triand",      "trior",
    "trireg",     "unsigned",     "use",         "vectored",    "wait",
    "wand",       "weak0",        "weak1",       "while",       "wire",
    "wor",        "xnor",         "xor",
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

FailureOr<GateProfile> parseCheckProfile(ModuleOp module, GateProfile defaultProfile) {
  Attribute raw = module->getAttr("nodal.backend.check_profile");
  if (!raw)
    return defaultProfile;
  auto value = llvm::dyn_cast<StringAttr>(raw);
  if (!value) {
    (void)emitMappedFailure(module.getOperation(), "NODAL-BACKEND-CONFIG-001",
                            "CheckProfile must be a string attribute");
    return failure();
  }
  if (value.getValue() == "fast")
    return GateProfile::Fast;
  if (value.getValue() == "default")
    return GateProfile::Default;
  if (value.getValue() == "release")
    return GateProfile::Release;
  (void)emitMappedFailure(module.getOperation(), "NODAL-BACKEND-CONFIG-001",
                          llvm::Twine("unknown CheckProfile '") + value.getValue() + "'");
  return failure();
}

LogicalResult requireOwnedSetting(ModuleOp module, llvm::StringRef attribute,
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

LogicalResult verifyDesignKind(ModuleOp module, const BackendProfile &profile) {
  llvm::StringRef kind = "target_neutral";
  if (Attribute raw = module->getAttr("nodal.target.profile")) {
    auto value = llvm::dyn_cast<StringAttr>(raw);
    if (!value)
      return emitMappedFailure(module.getOperation(), "NODAL-BACKEND-PROFILE-001",
                               "design kind must be a string attribute");
    kind = value.getValue();
  }

  if (kind == "analog" && profile.supportsAnalog)
    return success();
  if (kind == "mixed_signal" && profile.supportsMixedSignal)
    return success();

  return emitMappedFailure(module.getOperation(), "NODAL-BACKEND-PROFILE-001",
                           llvm::Twine("backend profile '") + profile.id +
                               "' does not accept design kind '" + kind + "'");
}

LogicalResult verifySupportedOperations(ModuleOp module, const BackendProfile &profile) {
  LogicalResult result = success();
  module.walk([&](Operation *operation) {
    if (failed(result) || operation == module.getOperation())
      return;
    llvm::StringRef name = operation->getName().getStringRef();
    if (name == "nodal.module")
      return;
    result = emitMappedFailure(operation, "NODAL-BACKEND-CAPABILITY-001",
                               llvm::Twine("operation '") + name +
                                   "' is not yet supported by profile '" + profile.id + "'");
  });
  return result;
}

LogicalResult collectDefinitions(ModuleOp module, llvm::SmallVectorImpl<Operation *> &definitions) {
  for (Operation &operation : module.getBody()->getOperations()) {
    if (operation.getName().getStringRef() != "nodal.module")
      continue;
    llvm::StringRef name = symbolName(&operation);
    if (!isIdentifier(name))
      return emitMappedFailure(&operation, "NODAL-BACKEND-NAMING-001",
                               llvm::Twine("module symbol '") + name +
                                   "' is not a portable Verilog-family identifier");
    definitions.push_back(&operation);
  }
  llvm::sort(definitions,
             [](Operation *lhs, Operation *rhs) { return symbolName(lhs) < symbolName(rhs); });
  return success();
}

void renderCandidate(llvm::ArrayRef<Operation *> definitions,
                     const BackendConfiguration &configuration, llvm::raw_ostream &output) {
  output << "/* Nodal backend framework v1\n";
  output << " * profile: " << configuration.profile->id << "\n";
  output << " * check-profile: " << stringifyGateProfile(configuration.checkProfile) << "\n";
  output << " * shaped-layout: " << stringifyShapedValueLayout(configuration.shapedValueLayout)
         << "\n";
  output << " * materialization: " << stringifyMaterializationPolicy(configuration.materialization)
         << "\n";
  output << " * naming: " << stringifyNamingPolicy(configuration.naming) << "\n";
  output << " */\n";
  output << "`include \"constants.vams\"\n";
  output << "`include \"disciplines.vams\"\n\n";

  for (Operation *definition : definitions) {
    output << "module " << symbolName(definition) << ";\n";
    output << "endmodule\n\n";
  }
}

size_t countModuleDeclarations(llvm::StringRef text) {
  llvm::SmallVector<llvm::StringRef, 32> lines;
  text.split(lines, '\n', -1, true);
  return llvm::count_if(lines, [](llvm::StringRef line) {
    line = line.trim();
    return line.starts_with("module ") && line.ends_with(";");
  });
}

size_t countExactLines(llvm::StringRef text, llvm::StringRef expected) {
  llvm::SmallVector<llvm::StringRef, 32> lines;
  text.split(lines, '\n', -1, true);
  return llvm::count_if(lines, [&](llvm::StringRef line) { return line.trim() == expected; });
}

class BuiltinTargetVerificationHooks final : public TargetVerificationHooks {
public:
  LogicalResult verifyTarget(llvm::StringRef candidate,
                             const BackendConfiguration &configuration) const final {
    if (candidate.empty() || !candidate.starts_with("/* Nodal backend framework v1\n"))
      return failure();
    if (!candidate.ends_with("\n") || candidate.contains('\r') || candidate.contains('\0'))
      return failure();

    std::string expectedProfile =
        (llvm::Twine(" * profile: ") + configuration.profile->id + "\n").str();
    if (!candidate.contains(expectedProfile))
      return failure();
    if (countModuleDeclarations(candidate) != countExactLines(candidate, "endmodule"))
      return failure();
    return success();
  }

  LogicalResult reparseTarget(llvm::StringRef candidate, const BackendConfiguration &) const final {
    llvm::SmallVector<llvm::StringRef, 32> lines;
    candidate.split(lines, '\n', -1, true);

    bool insideModule = false;
    bool sawModule = false;
    for (llvm::StringRef line : lines) {
      line = line.trim();
      if (line.empty() || line.starts_with("/*") || line.starts_with("*") ||
          line.starts_with("`include "))
        continue;
      if (line.starts_with("module ") && line.ends_with(";")) {
        if (insideModule)
          return failure();
        llvm::StringRef name = line.drop_front(sizeof("module ") - 1).drop_back().trim();
        if (!isIdentifier(name))
          return failure();
        insideModule = true;
        sawModule = true;
        continue;
      }
      if (line == "endmodule") {
        if (!insideModule)
          return failure();
        insideModule = false;
        continue;
      }
      return failure();
    }
    return sawModule && !insideModule ? success() : failure();
  }
};

void registerDialects(DialectRegistry &registry) {
  registry.insert<circt::hw::HWDialect, NodalDialect>();
}

LogicalResult translateVerilogA(ModuleOp module, llvm::raw_ostream &output) {
  return emitBackend(module, BackendKind::VerilogA, output);
}

LogicalResult translateVerilogAMS(ModuleOp module, llvm::raw_ostream &output) {
  return emitBackend(module, BackendKind::VerilogAMS, output);
}

} // namespace

const BackendProfile &getBackendProfile(BackendKind kind) {
  switch (kind) {
  case BackendKind::VerilogA:
    return kVerilogAProfile;
  case BackendKind::VerilogAMS:
    return kVerilogAMSProfile;
  }
  llvm_unreachable("unknown Nodal backend kind");
}

llvm::StringRef stringifyBackendKind(BackendKind kind) { return getBackendProfile(kind).id; }

llvm::StringRef stringifyShapedValueLayout(ShapedValueLayout layout) {
  switch (layout) {
  case ShapedValueLayout::ScalarOrFlat:
    return "scalar-or-flat";
  case ShapedValueLayout::FlatPacked:
    return "flat-packed";
  }
  llvm_unreachable("unknown Nodal shaped-value layout");
}

llvm::StringRef stringifyMaterializationPolicy(MaterializationPolicy policy) {
  switch (policy) {
  case MaterializationPolicy::SafeInline:
    return "safe-inline";
  case MaterializationPolicy::Readable:
    return "readable";
  }
  llvm_unreachable("unknown Nodal materialization policy");
}

llvm::StringRef stringifyNamingPolicy(NamingPolicy policy) {
  switch (policy) {
  case NamingPolicy::Semantic:
    return "semantic";
  }
  llvm_unreachable("unknown Nodal naming policy");
}

FailureOr<BackendConfiguration> resolveBackendConfiguration(ModuleOp module, BackendKind kind) {
  const BackendProfile &profile = getBackendProfile(kind);

  if (Attribute raw = module->getAttr("nodal.backend.profile")) {
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

  FailureOr<GateProfile> checkProfile = parseCheckProfile(module, profile.defaultCheckProfile);
  if (failed(checkProfile))
    return failure();

  if (failed(requireOwnedSetting(module, "nodal.backend.shaped_layout",
                                 stringifyShapedValueLayout(profile.shapedValueLayout),
                                 "shaped-value layout")) ||
      failed(requireOwnedSetting(module, "nodal.backend.materialization",
                                 stringifyMaterializationPolicy(profile.materialization),
                                 "expression materialization policy")) ||
      failed(requireOwnedSetting(module, "nodal.backend.naming",
                                 stringifyNamingPolicy(profile.naming),
                                 "semantic naming policy")) ||
      failed(verifyDesignKind(module, profile)))
    return failure();

  return BackendConfiguration{
      &profile, *checkProfile, profile.shapedValueLayout, profile.materialization, profile.naming,
  };
}

const TargetVerificationHooks &getBuiltinTargetVerificationHooks() {
  static const BuiltinTargetVerificationHooks hooks;
  return hooks;
}

LogicalResult emitBackend(ModuleOp module, BackendKind kind, llvm::raw_ostream &output,
                          const TargetVerificationHooks *hooks) {
  OwningOpRef<ModuleOp> working(llvm::cast<ModuleOp>(module.getOperation()->clone()));

  FailureOr<BackendConfiguration> configuration = resolveBackendConfiguration(*working, kind);
  if (failed(configuration))
    return failure();

  if (failed(runNodalPipelineTransaction(*working, configuration->checkProfile)) ||
      failed(verifySupportedOperations(*working, *configuration->profile)))
    return failure();

  llvm::SmallVector<Operation *, 8> definitions;
  if (failed(collectDefinitions(*working, definitions)))
    return failure();

  std::string candidate;
  llvm::raw_string_ostream candidateStream(candidate);
  renderCandidate(definitions, *configuration, candidateStream);
  candidateStream.flush();

  const TargetVerificationHooks &selectedHooks =
      hooks ? *hooks : getBuiltinTargetVerificationHooks();
  if (failed(selectedHooks.verifyTarget(candidate, *configuration)))
    return emitMappedFailure(working->getOperation(), "NODAL-BACKEND-VERIFY-001",
                             llvm::Twine("target verification hook rejected profile '") +
                                 configuration->profile->id + "'");
  if (failed(selectedHooks.reparseTarget(candidate, *configuration)))
    return emitMappedFailure(working->getOperation(), "NODAL-BACKEND-REPARSE-001",
                             llvm::Twine("target reparse hook rejected profile '") +
                                 configuration->profile->id + "'");

  output << candidate;
  return success();
}

void registerNodalBackendTranslations() {
  static TranslateFromMLIRRegistration verilogA(
      kVerilogATranslation,
      "Translate accepted Nodal IR to deterministic Verilog-A framework output", translateVerilogA,
      registerDialects);
  static TranslateFromMLIRRegistration verilogAMS(
      kVerilogAMSTranslation,
      "Translate accepted Nodal IR to deterministic Verilog-AMS framework output",
      translateVerilogAMS, registerDialects);
  (void)verilogA;
  (void)verilogAMS;
}

} // namespace nodal
