from pathlib import Path


def replace_once(relative: str, old: str, new: str) -> None:
    path = Path(relative)
    text = path.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(
            f"expected one Increment 28 reconciliation anchor in {relative}; found {count}"
        )
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


passes = "core/compiler/lib/Transforms/Passes.cpp"
replace_once(
    passes,
    '#include "nodal/Diagnostics/DiagnosticMapping.h"\n'
    '#include "nodal/Dialect/Nodal/NodalOps.h"\n',
    '#include "nodal/Diagnostics/DiagnosticMapping.h"\n'
    '#include "nodal/Dialect/Nodal/ConservativeConnectivity.h"\n'
    '#include "nodal/Dialect/Nodal/NodalOps.h"\n',
)
replace_once(
    passes,
    '    bool digital = false;\n'
    '    bool analog = false;\n'
    '    bool bridge = false;\n'
    '    owner->walk([&](Operation *operation) {\n',
    '    bool digital = false;\n'
    '    bool analog = false;\n'
    '    bool bridge = false;\n'
    '    const bool partial = isPartialPhysicalComponent(owner);\n'
    '    owner->walk([&](Operation *operation) {\n',
)
replace_once(
    passes,
    '      if (name == "nodal.terminal" || name == "nodal.node" || name == "nodal.branch" ||\n'
    '          name == "nodal.access" || name == "nodal.analog" || name == "nodal.real_literal" ||\n'
    '          name == "nodal.parameter_ref" || name == "nodal.analog_add" ||\n'
    '          name == "nodal.analog_sub" || name == "nodal.analog_mul" || name == "nodal.analog_div" ||\n'
    '          name == "nodal.analog_ddt" || name == "nodal.contribute")\n',
    '      if (name == "nodal.component_contract" || name == "nodal.terminal" ||\n'
    '          name == "nodal.node" || name == "nodal.connect" || name == "nodal.alias" ||\n'
    '          name == "nodal.reference" || name == "nodal.branch" ||\n'
    '          name == "nodal.connection_set" || name == "nodal.potential_equality" ||\n'
    '          name == "nodal.reference_potential" || name == "nodal.flow_conservation" ||\n'
    '          name == "nodal.access" || name == "nodal.analog" ||\n'
    '          name == "nodal.real_literal" || name == "nodal.parameter_ref" ||\n'
    '          name == "nodal.analog_add" || name == "nodal.analog_sub" ||\n'
    '          name == "nodal.analog_mul" || name == "nodal.analog_div" ||\n'
    '          name == "nodal.analog_ddt" || name == "nodal.contribute")\n',
)
replace_once(
    passes,
    '      if ((name == "nodal.terminal" || name == "nodal.node") && operation->getNumResults() == 1 &&\n'
    '          operation->getResult(0).use_empty() &&\n',
    '      if ((name == "nodal.terminal" || name == "nodal.node") && operation->getNumResults() == 1 &&\n'
    '          operation->getResult(0).use_empty() && !partial &&\n',
)
replace_once(
    passes,
    '    const bool analog =\n'
    '        name == "nodal.terminal" || name == "nodal.node" || name == "nodal.branch" ||\n'
    '        name == "nodal.access" || name == "nodal.bridge" || name == "nodal.analog" ||\n'
    '        name == "nodal.real_literal" || name == "nodal.parameter_ref" ||\n'
    '        name == "nodal.analog_add" || name == "nodal.analog_sub" || name == "nodal.analog_mul" ||\n'
    '        name == "nodal.analog_div" || name == "nodal.analog_ddt" || name == "nodal.contribute";\n',
    '    const bool analog =\n'
    '        name == "nodal.component_contract" || name == "nodal.terminal" ||\n'
    '        name == "nodal.node" || name == "nodal.connect" || name == "nodal.alias" ||\n'
    '        name == "nodal.reference" || name == "nodal.branch" ||\n'
    '        name == "nodal.connection_set" || name == "nodal.potential_equality" ||\n'
    '        name == "nodal.reference_potential" || name == "nodal.flow_conservation" ||\n'
    '        name == "nodal.access" || name == "nodal.bridge" || name == "nodal.analog" ||\n'
    '        name == "nodal.real_literal" || name == "nodal.parameter_ref" ||\n'
    '        name == "nodal.analog_add" || name == "nodal.analog_sub" ||\n'
    '        name == "nodal.analog_mul" || name == "nodal.analog_div" ||\n'
    '        name == "nodal.analog_ddt" || name == "nodal.contribute";\n',
)
replace_once(
    passes,
    'class NormalizePipelinePass final\n'
    '    : public PassWrapper<NormalizePipelinePass, OperationPass<mlir::ModuleOp>> {\n',
    'class MaterializeConservativeConnectivityPass final\n'
    '    : public PassWrapper<MaterializeConservativeConnectivityPass,\n'
    '                         OperationPass<mlir::ModuleOp>> {\n'
    'public:\n'
    '  MLIR_DEFINE_EXPLICIT_INTERNAL_INLINE_TYPE_ID(MaterializeConservativeConnectivityPass)\n'
    '\n'
    '  llvm::StringRef getArgument() const final {\n'
    '    return "nodal-materialize-conservative-connectivity";\n'
    '  }\n'
    '  llvm::StringRef getDescription() const final {\n'
    '    return "Build deterministic conservative connection sets and conservation equations";\n'
    '  }\n'
    '\n'
    '  void runOnOperation() final {\n'
    '    if (failed(materializeConservativeConnectivity(getOperation())))\n'
    '      signalPassFailure();\n'
    '  }\n'
    '};\n'
    '\n'
    'class NormalizePipelinePass final\n'
    '    : public PassWrapper<NormalizePipelinePass, OperationPass<mlir::ModuleOp>> {\n',
)
replace_once(
    passes,
    '  manager.enableVerifier(true);\n'
    '  addVerifierPasses(manager, profile);\n',
    '  manager.enableVerifier(true);\n'
    '  manager.addPass(std::make_unique<MaterializeConservativeConnectivityPass>());\n'
    '  addVerifierPasses(manager, profile);\n',
)
replace_once(
    passes,
    'void registerNodalPasses() {\n'
    '  static PassRegistration<VerifyConstructionPass> construction;\n',
    'void registerNodalPasses() {\n'
    '  static PassRegistration<MaterializeConservativeConnectivityPass> connectivity;\n'
    '  static PassRegistration<VerifyConstructionPass> construction;\n',
)
replace_once(
    passes,
    '  (void)construction;\n'
    '  (void)drivers;\n',
    '  (void)connectivity;\n'
    '  (void)construction;\n'
    '  (void)drivers;\n',
)

predecessor = "scripts/check_increment27.py"
replace_once(
    predecessor,
    '    "tests/compiler/fixtures/increment27/manifest.json",\n'
    '    "tests/compiler/test_increment27.py",\n',
    '    "tests/compiler/fixtures/increment27/manifest.json",\n'
    '    "tests/compiler/fixtures/increment28/manifest.json",\n'
    '    "tests/compiler/test_increment27.py",\n',
)
replace_once(
    predecessor,
    '    increment28_open = "- [ ] **Increment 28 — Electrical nodes, nets, and branches**" in roadmap\n'
    '    status = manifest.get("status")\n',
    '    increment28_open = "- [ ] **Increment 28 — Electrical nodes, nets, and branches**" in roadmap\n'
    '    increment28_done = "- [x] **Increment 28 — Electrical nodes, nets, and branches**" in roadmap\n'
    '    status = manifest.get("status")\n',
)
successor_new = "\n".join([
    '    if increment28_open:',
    '        pass',
    '    elif increment28_done:',
    '        if not increment27_done:',
    '            problems.append(',
    '                Problem("NODAL-INC27-016", "Increment 28 cannot close before Increment 27")',
    '            )',
    '        successor_path = root / "tests/compiler/fixtures/increment28/manifest.json"',
    '        try:',
    '            successor = json.loads(read(successor_path, problems, "NODAL-INC27-016"))',
    '        except json.JSONDecodeError as exc:',
    '            problems.append(',
    '                Problem(',
    '                    "NODAL-INC27-016",',
    '                    f"invalid Increment 28 successor manifest: {exc}",',
    '                )',
    '            )',
    '            successor = {}',
    '        if successor.get("increment") != 28 or successor.get("public_api") != "0.3":',
    '            problems.append(',
    '                Problem(',
    '                    "NODAL-INC27-016",',
    '                    "Increment 28 successor identity/public API mismatch",',
    '                )',
    '            )',
    '        if successor.get("status") != "validated-electrical-connectivity":',
    '            problems.append(',
    '                Problem(',
    '                    "NODAL-INC27-016",',
    '                    "checked Increment 28 lacks validated successor evidence",',
    '                )',
    '            )',
    '        successor_evidence = successor.get("evidence", {})',
    '        for field in ("pull_request", "dedicated_run", "core_ci_run"):',
    '            if not isinstance(successor_evidence.get(field), int):',
    '                problems.append(',
    '                    Problem(',
    '                        "NODAL-INC27-016",',
    '                        f"Increment 28 successor lacks integer evidence field: {field}",',
    '                    )',
    '                )',
    '        if rev < (1, 36):',
    '            problems.append(',
    '                Problem(',
    '                    "NODAL-INC27-016",',
    '                    "checked Increment 28 requires roadmap revision 1.36 or later",',
    '                )',
    '            )',
    '    else:',
    '        problems.append(',
    '            Problem(',
    '                "NODAL-INC27-016",',
    '                "Increment 28 roadmap state is neither open nor validated",',
    '            )',
    '        )',
    '    return problems',
]) + "\n"
replace_once(
    predecessor,
    '    if not increment28_open:\n'
    '        problems.append(Problem("NODAL-INC27-016", "Increment 28 must remain unchecked"))\n'
    '    return problems\n',
    successor_new,
)
