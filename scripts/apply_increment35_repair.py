#!/usr/bin/env python3
from __future__ import annotations

import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def read(relative: str) -> str:
    path = ROOT / relative
    if not path.is_file():
        raise RuntimeError(f"missing required file: {relative}")
    return path.read_text(encoding="utf-8")


def write(relative: str, content: str) -> None:
    path = ROOT / relative
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8")


def replace_literal(relative: str, old: str, new: str) -> None:
    text = read(relative)
    count = text.count(old)
    if count != 1:
        raise RuntimeError(
            f"{relative}: expected one literal anchor, found {count}: {old!r}"
        )
    write(relative, text.replace(old, new, 1))


def replace_between(relative: str, start: str, end: str, replacement: str) -> None:
    text = read(relative)
    first = text.find(start)
    second = text.find(end, first + len(start)) if first >= 0 else -1
    if first < 0 or second < 0:
        raise RuntimeError(
            f"{relative}: missing section marker: start={start!r}, end={end!r}"
        )
    if text.find(start, first + 1) >= 0:
        raise RuntimeError(f"{relative}: duplicate section marker {start!r}")
    write(relative, text[:first] + replacement + text[second:])


def repair_analog_numeric() -> None:
    relative = "core/compiler/lib/Dialect/Nodal/AnalogNumeric.cpp"
    text = read(relative)
    owner_marker = '"continuous-time operator identity and owner must be non-empty");'
    if text.count(owner_marker) != 1:
        raise RuntimeError("continuous operator owner diagnostic marker is not unique")
    owner_end = text.index(owner_marker) + len(owner_marker)
    owner_check = '''
  const llvm::StringRef operatorIdentity = operatorId.getValue();
  const llvm::StringRef ownerIdentity = owner.getValue();
  if (!operatorIdentity.starts_with(ownerIdentity) ||
      !operatorIdentity.drop_front(ownerIdentity.size()).starts_with("."))
    return emitMappedFailure(
        operation, "NODAL-ANALOG-035-002",
        "continuous-time operator identity must be owned by its declared owner");'''
    write(relative, text[:owner_end] + owner_check + text[owner_end:])

    verify_ddt = '''LogicalResult verifyDdt(Operation *operation) {
  const bool contracted =
      static_cast<bool>(operation->getAttrOfType<StringAttr>("operator_contract"));
  if (operation->getNumOperands() != 1 || operation->getNumResults() != 1)
    return emitMappedFailure(
        operation,
        contracted ? llvm::StringRef("NODAL-ANALOG-035-002")
                   : llvm::StringRef("NODAL-ANALOG-DDT-001"),
        "ddt requires one input and one result");

  if (!contracted) {
    if (operation->getOperand(0).getType().isF64() &&
        operation->getResult(0).getType().isF64())
      return success();
    auto input = getAnalogNumericTypeInfo(operation->getOperand(0).getType());
    if (failed(input) || input->kind != AnalogNumericKind::Real)
      return emitMappedFailure(operation, "NODAL-ANALOG-DDT-001",
                               "typed ddt requires a real quantity input");
    auto dimension = combineAnalogDimensions(input->dimension, "time", true);
    if (failed(dimension) ||
        !semanticTypeMatches(operation->getResult(0).getType(),
                             AnalogNumericKind::Real, *dimension))
      return emitMappedFailure(operation, "NODAL-ANALOG-DDT-001",
                               "ddt result must subtract one time exponent");
    return success();
  }

  auto input = getAnalogNumericTypeInfo(operation->getOperand(0).getType());
  auto result = getAnalogNumericTypeInfo(operation->getResult(0).getType());
  if (failed(input) || failed(result) ||
      input->kind != AnalogNumericKind::Real ||
      result->kind != AnalogNumericKind::Real)
    return emitMappedFailure(operation, "NODAL-ANALOG-035-003",
                             "ddt requires real quantity input and result");

  std::string inputDimension = input->dimension;
  std::string resultDimension;
  if (input->legacyF64 && result->legacyF64) {
    inputDimension = textAttr(operation, "input_dimension").str();
    resultDimension = textAttr(operation, "result_dimension").str();
    auto dimension = combineAnalogDimensions(inputDimension, "time", true);
    if (failed(dimension) || resultDimension != *dimension)
      return emitMappedFailure(operation, "NODAL-ANALOG-035-003",
                               "ddt result must subtract one time exponent");
  } else {
    if (input->legacyF64 || result->legacyF64)
      return emitMappedFailure(operation, "NODAL-ANALOG-035-003",
                               "contracted ddt cannot mix legacy f64 and typed quantities");
    auto dimension = combineAnalogDimensions(input->dimension, "time", true);
    if (failed(dimension) || result->dimension != *dimension)
      return emitMappedFailure(operation, "NODAL-ANALOG-035-003",
                               "ddt result must subtract one time exponent");
    resultDimension = *dimension;
  }
  if (failed(verifyContinuousContract(operation, false, inputDimension,
                                      resultDimension)))
    return failure();
  return verifyDdtSimplification(operation, resultDimension);
}

'''
    replace_between(
        relative,
        "LogicalResult verifyDdt(Operation *operation) {",
        "LogicalResult verifyIdt(Operation *operation) {",
        verify_ddt,
    )

    verify_model = '''LogicalResult verifyAnalogNumericModel(mlir::ModuleOp module) {
  LogicalResult result = success();
  llvm::StringSet<> continuousOperatorIds;
  llvm::StringSet<> continuousStateIds;
  module.walk([&](Operation *operation) {
    if (failed(result))
      return;
    const llvm::StringRef operationName = operation->getName().getStringRef();
    if ((operationName == "nodal.analog_ddt" ||
         operationName == "nodal.analog_idt") &&
        operation->getAttr("operator_contract")) {
      if (auto operatorId = operation->getAttrOfType<StringAttr>("operator_id")) {
        if (!operatorId.getValue().trim().empty() &&
            !continuousOperatorIds.insert(operatorId.getValue()).second) {
          result = emitMappedFailure(
              operation, "NODAL-ANALOG-035-002",
              "continuous-time operator identity must be unique");
          return;
        }
      }
      if (auto stateId = operation->getAttrOfType<StringAttr>("state_id")) {
        if (!stateId.getValue().trim().empty() &&
            !continuousStateIds.insert(stateId.getValue()).second) {
          result = emitMappedFailure(
              operation, "NODAL-ANALOG-035-005",
              "integral state identity must be unique");
          return;
        }
      }
    }
    for (Type type : operation->getOperandTypes()) {
      if (auto quantity = llvm::dyn_cast<QuantityType>(type)) {
        if (!isCanonicalDimensionSignature(quantity.getDimension())) {
          result = emitMappedFailure(operation, "NODAL-ANALOG-DIMENSION-001",
                                     "operand quantity has a non-canonical dimension");
          return;
        }
      }
    }
    for (Type type : operation->getResultTypes()) {
      if (auto quantity = llvm::dyn_cast<QuantityType>(type)) {
        if (!isCanonicalDimensionSignature(quantity.getDimension())) {
          result = emitMappedFailure(operation, "NODAL-ANALOG-DIMENSION-001",
                                     "result quantity has a non-canonical dimension");
          return;
        }
      }
    }
    result = verifyAnalogNumericOperation(operation);
  });
  return result;
}

'''
    replace_between(
        relative,
        "LogicalResult verifyAnalogNumericModel(mlir::ModuleOp module) {",
        "LogicalResult foldAnalogNumericConstants(mlir::ModuleOp module) {",
        verify_model,
    )


def repair_backend_and_contracts() -> None:
    replace_literal(
        "core/compiler/lib/Backend/AnalogVerticalSlice.cpp",
        '"nodal.analog_ddt",\n    "nodal.contribute",',
        '"nodal.analog_ddt",\n    "nodal.analog_idt",\n    "nodal.contribute",',
    )
    replace_literal(
        "scripts/check_increment35.py",
        '            "current*time",\n',
        '            "inputDimension.multiply(AnalogDimension.Time)",\n',
    )
    replace_literal(
        "scripts/check_increment35.py",
        '(\'name == "nodal.analog_idt"\', \'llvm::Twine("idt(")\', "nodal.simplified"),',
        '(\'name == "nodal.analog_idt"\', \'"nodal.analog_idt",\', \'llvm::Twine("idt(")\', "nodal.simplified"),',
    )

    manifest_path = ROOT / "tests/compiler/fixtures/increment35/manifest.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    manifest["semantics"]["legacy_ddt_diagnostic_compatibility"] = True
    manifest["semantics"]["native_operator_identity_uniqueness"] = True
    manifest["integration"]["bootstrap_scaffolding_removed"] = True
    manifest_path.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")

    catalog_path = ROOT / "core/compiler/diagnostics-v0.1.json"
    catalog = json.loads(catalog_path.read_text(encoding="utf-8"))
    diagnostics = [f"NODAL-ANALOG-035-{index:03d}" for index in range(1, 9)]
    catalog["families"]["analog-differential-integral"] = diagnostics
    if "NODAL-ANALOG-035-" not in catalog["preserved_prefixes"]:
        catalog["preserved_prefixes"].append("NODAL-ANALOG-035-")
    catalog_path.write_text(json.dumps(catalog, indent=2) + "\n", encoding="utf-8")


def remove_temporary_fragments() -> None:
    for relative in (
        "scripts/bootstrap_increment35.part00",
        "scripts/bootstrap_increment35.part01",
        "scripts/bootstrap_increment35.part02",
        "scripts/bootstrap_increment35.part03",
        "scripts/bootstrap_increment35.part04",
        "scripts/bootstrap_increment35.part05",
        "scripts/bootstrap_increment35.part06",
        "tests/compiler/fixtures/increment35/bootstrap-revision.txt",
    ):
        path = ROOT / relative
        if path.exists():
            path.unlink()


def main() -> None:
    repair_analog_numeric()
    repair_backend_and_contracts()
    remove_temporary_fragments()
    Path(__file__).unlink()


if __name__ == "__main__":
    main()
