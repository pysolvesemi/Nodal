from pathlib import Path


def replace_once(relative: str, old: str, new: str) -> None:
    path = Path(relative)
    text = path.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(
            f"expected one Increment 28 review-fix anchor in {relative}; found {count}"
        )
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


source = "core/compiler/lib/Dialect/Nodal/ConservativeConnectivity.cpp"
replace_once(
    source,
    '''    for (unsigned index : indices) {
      info.members.push_back(endpoints[index].value);
      info.memberPaths.push_back(endpoints[index].path);
      if (info.discipline.empty())
        info.discipline = endpoints[index].discipline;
      else if (info.discipline != endpoints[index].discipline)
        return endpoints[index].operation->emitOpError(
            "NODAL-CONNECTION-DISCIPLINE-001: normalized connection set contains "
            "incompatible disciplines");
    }
''',
    '''    for (unsigned index : indices) {
      info.members.push_back(endpoints[index].value);
      info.memberPaths.push_back(endpoints[index].path);
      if (info.discipline.empty()) {
        info.discipline = endpoints[index].discipline;
      } else if (info.discipline != endpoints[index].discipline) {
        FailureOr<bool> compatible = compatibleConservativeDisciplines(
            endpoints[index].operation, info.discipline, endpoints[index].discipline);
        if (failed(compatible) || !*compatible)
          return endpoints[index].operation->emitOpError(
              "NODAL-CONNECTION-DISCIPLINE-001: normalized connection set contains "
              "incompatible disciplines");
        if (endpoints[index].discipline < info.discipline)
          info.discipline = endpoints[index].discipline;
      }
    }
''',
)

unit = "core/compiler/test/Unit/ConservativeConnectivityTest.cpp"
replace_once(
    unit,
    '''  "nodal.discipline"() <{domain = "continuous", flow = @Current, metadata = {}, potential = @Voltage, sym_name = "electrical"}> : () -> ()
  "nodal.module"() <{metadata = {}, sym_name = "Fixture"}> ({
''',
    '''  "nodal.discipline"() <{domain = "continuous", flow = @Current, metadata = {}, potential = @Voltage, sym_name = "electrical"}> : () -> ()
  "nodal.discipline"() <{domain = "continuous", flow = @Current, metadata = {}, potential = @Voltage, sym_name = "electrical_equivalent"}> : () -> ()
  "nodal.module"() <{metadata = {}, sym_name = "Fixture"}> ({
''',
)
replace_once(
    unit,
    '''    %n = "nodal.terminal"() <{direction = "input", flow_orientation = "into_component", metadata = {}, name = "n", source_path = "Fixture.n"}> : () -> !nodal.terminal<"electrical">
''',
    '''    %n = "nodal.terminal"() <{direction = "input", flow_orientation = "into_component", metadata = {}, name = "n", source_path = "Fixture.n"}> : () -> !nodal.terminal<"electrical_equivalent">
''',
)
replace_once(
    unit,
    '''    "nodal.connect"(%n, %mid) <{connection_id = "n-mid", metadata = {}, source_path = "Fixture.connect.n-mid"}> : (!nodal.terminal<"electrical">, !nodal.terminal<"electrical">) -> ()
''',
    '''    "nodal.connect"(%n, %mid) <{connection_id = "n-mid", metadata = {}, source_path = "Fixture.connect.n-mid"}> : (!nodal.terminal<"electrical_equivalent">, !nodal.terminal<"electrical">) -> ()
''',
)
replace_once(
    unit,
    '''  bool partialIncomplete = false;
  bool outputDirectionKeptIndependent = false;
  bool moduleReferenceRetained = false;
''',
    '''  bool partialIncomplete = false;
  bool outputDirectionKeptIndependent = false;
  bool moduleReferenceRetained = false;
  bool compatibleRepresentativeDeterministic = false;
''',
)
replace_once(
    unit,
    '''  valid->walk([&](mlir::Operation *operation) {
    sets += llvm::isa<nodal::ConnectionSetOp>(operation);
    equalities += llvm::isa<nodal::PotentialEqualityOp>(operation);
''',
    '''  valid->walk([&](mlir::Operation *operation) {
    sets += llvm::isa<nodal::ConnectionSetOp>(operation);
    if (llvm::isa<nodal::ConnectionSetOp>(operation)) {
      bool hasEquivalent = false;
      bool hasMid = false;
      for (mlir::Value operand : operation->getOperands()) {
        mlir::Operation *definition = operand.getDefiningOp();
        auto name = definition ? definition->getAttrOfType<mlir::StringAttr>("name")
                               : mlir::StringAttr();
        hasEquivalent |= name && name.getValue() == "n";
        hasMid |= name && name.getValue() == "mid";
      }
      auto discipline = operation->getAttrOfType<mlir::FlatSymbolRefAttr>("discipline");
      if (hasEquivalent && hasMid && discipline && discipline.getValue() == "electrical")
        compatibleRepresentativeDeterministic = true;
    }
    equalities += llvm::isa<nodal::PotentialEqualityOp>(operation);
''',
)
replace_once(
    unit,
    '''  if (!moduleReferenceRetained)
    return fail("module-local reference identity was not retained");
  if (!partialIncomplete)
''',
    '''  if (!moduleReferenceRetained)
    return fail("module-local reference identity was not retained");
  if (!compatibleRepresentativeDeterministic)
    return fail("compatible discipline set did not select deterministic representative");
  if (!partialIncomplete)
''',
)

fixture = "core/compiler/test/IR/electrical-connectivity.mlir"
replace_once(
    fixture,
    '''  "nodal.discipline"() <{domain = "continuous", flow = @Current, metadata = {}, potential = @Voltage, sym_name = "electrical"}> : () -> ()
  "nodal.discipline_import"() <{definition_hash = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef", metadata = {}, source = "std://disciplines/electrical@2023", sym_name = "electrical_alias", target = @electrical}> : () -> ()
''',
    '''  "nodal.discipline"() <{domain = "continuous", flow = @Current, metadata = {}, potential = @Voltage, sym_name = "electrical"}> : () -> ()
  "nodal.discipline"() <{domain = "continuous", flow = @Current, metadata = {}, potential = @Voltage, sym_name = "electrical_equivalent"}> : () -> ()
  "nodal.discipline_import"() <{definition_hash = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef", metadata = {}, source = "std://disciplines/electrical@2023", sym_name = "electrical_alias", target = @electrical}> : () -> ()
''',
)
replace_once(
    fixture,
    '''    %p_alias = "nodal.terminal"() <{direction = "inout", flow_orientation = "into_component", metadata = {}, name = "p_alias", source_path = "ElectricalNetwork.p_alias"}> : () -> !nodal.terminal<"electrical_alias">
''',
    '''    %p_alias = "nodal.terminal"() <{direction = "inout", flow_orientation = "into_component", metadata = {}, name = "p_alias", source_path = "ElectricalNetwork.p_alias"}> : () -> !nodal.terminal<"electrical_equivalent">
''',
)
replace_once(
    fixture,
    '''    "nodal.alias"(%p, %p_alias) <{alias_id = "input-alias", metadata = {}, source_path = "ElectricalNetwork.alias.input"}> : (!nodal.terminal<"electrical">, !nodal.terminal<"electrical_alias">) -> ()
''',
    '''    "nodal.alias"(%p, %p_alias) <{alias_id = "input-alias", metadata = {}, source_path = "ElectricalNetwork.alias.input"}> : (!nodal.terminal<"electrical">, !nodal.terminal<"electrical_equivalent">) -> ()
''',
)

checker = "scripts/check_increment28.py"
replace_once(
    checker,
    '''    ".github/workflows/increment-28-close.yml",
)
''',
    '''    ".github/workflows/increment-28-close.yml",
    "scripts/.increment28_review_fix.py",
    ".github/workflows/increment-28-review-fix.yml",
)
''',
)
replace_once(
    checker,
    '''            "sets.unite",
            "nodal::ConnectionSetOp::getOperationName",
''',
    '''            "sets.unite",
            "endpoints[index].operation, info.discipline, endpoints[index].discipline",
            "endpoints[index].discipline < info.discipline",
            "nodal::ConnectionSetOp::getOperationName",
''',
)
replace_once(
    checker,
    '''            "module-local reference identity was not retained",
            "port direction incorrectly changed conservative flow orientation",
''',
    '''            "module-local reference identity was not retained",
            "compatible discipline set did not select deterministic representative",
            "port direction incorrectly changed conservative flow orientation",
''',
)
replace_once(
    checker,
    '''            'declaration_kind = "implicit"',
        ),
''',
    '''            'declaration_kind = "implicit"',
            'sym_name = "electrical_equivalent"',
            '!nodal.terminal<"electrical_equivalent">',
        ),
''',
)
replace_once(
    checker,
    '''            "Residual DAE construction",
        ),
''',
    '''            "lexicographically smallest canonical discipline symbol",
            "Residual DAE construction",
        ),
''',
)
replace_once(
    checker,
    '''            "public API v0.3 unchanged",
            "fail-closed",
''',
    '''            "public API v0.3 unchanged",
            "distinct compatible discipline declarations",
            "fail-closed",
''',
)
replace_once(
    checker,
    '''    if manifest.get("normalization_pass") != "nodal-materialize-conservative-connectivity":
        problems.append(Problem("NODAL-INC28-019", "manifest pass identity mismatch"))
    if manifest.get("component_contract") != {"partial": "extensible", "concrete": "local"}:
''',
    '''    if manifest.get("normalization_pass") != "nodal-materialize-conservative-connectivity":
        problems.append(Problem("NODAL-INC28-019", "manifest pass identity mismatch"))
    if manifest.get("discipline_representative") != "lexicographically-smallest-compatible-canonical-symbol":
        problems.append(
            Problem("NODAL-INC28-019", "manifest discipline representative mismatch")
        )
    if manifest.get("component_contract") != {"partial": "extensible", "concrete": "local"}:
''',
)

tests = "tests/compiler/test_increment28.py"
replace_once(
    tests,
    '''    def test_rejects_discardable_generated_attributes(self) -> None:
''',
    '''    def test_rejects_missing_compatible_discipline_selection(self) -> None:
        temporary, root = self.temporary_repository()
        self.addCleanup(temporary.cleanup)
        path = root / "core/compiler/lib/Dialect/Nodal/ConservativeConnectivity.cpp"
        path.write_text(
            path.read_text(encoding="utf-8").replace(
                "endpoints[index].operation, info.discipline, endpoints[index].discipline",
                "endpoints[index].operation, info.discipline, info.discipline",
                1,
            ),
            encoding="utf-8",
        )
        self.assertIn("NODAL-INC28-005", self.codes(root))

    def test_rejects_discardable_generated_attributes(self) -> None:
''',
)

manifest = "tests/compiler/fixtures/increment28/manifest.json"
replace_once(
    manifest,
    '''  "normalization_pass": "nodal-materialize-conservative-connectivity",
  "port_directions": [
''',
    '''  "normalization_pass": "nodal-materialize-conservative-connectivity",
  "discipline_representative": "lexicographically-smallest-compatible-canonical-symbol",
  "port_directions": [
''',
)

gate = "docs/design-gates/NodalElectricalConnectivity-DG-v1.0.md"
replace_once(
    gate,
    '''- Discipline aliases are accepted through canonical Increment 27 compatibility.
- Connections and aliases reject incompatible disciplines.
''',
    '''- Discipline aliases and distinct declarations with compatible canonical domain,
  potential nature, and flow nature are accepted through Increment 27 compatibility.
- A mixed-compatible connection set uses the lexicographically smallest canonical
  discipline symbol as its deterministic generated representative.
- Connections and aliases reject incompatible disciplines.
''',
)

implementation = "docs/implementation/increment28-electrical-connectivity.md"
replace_once(
    implementation,
    '''uses union-find to construct connection sets, sorts members by retained source
path, derives deterministic hash-based symbols, and regenerates normalized
potential and flow equations. Generated ODS inherent attributes are installed
''',
    '''uses union-find to construct connection sets, sorts members by retained source
path, accepts distinct compatible discipline declarations, selects the
lexicographically smallest canonical discipline symbol as each set's stable
representative, derives deterministic hash-based symbols, and regenerates
normalized potential and flow equations. Generated ODS inherent attributes are installed
''',
)
replace_once(
    implementation,
    '''- canonical discipline aliases;
- deterministic and idempotent set generation;
''',
    '''- canonical discipline aliases and distinct compatible discipline declarations;
- deterministic compatible-discipline representative selection;
- deterministic and idempotent set generation;
''',
)
