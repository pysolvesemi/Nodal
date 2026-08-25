#!/usr/bin/env python3
"""Materialize Increment 22 on a clean branch rooted at authoritative dev."""

from __future__ import annotations

import json
import textwrap
from pathlib import Path


def normalized(text: str) -> str:
    return textwrap.dedent(text).strip("\n") + "\n"


def write(root: Path, relative: str, text: str) -> None:
    path = root / relative
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(normalized(text), encoding="utf-8")


def replace_once(root: Path, relative: str, old: str, new: str) -> None:
    path = root / relative
    text = path.read_text(encoding="utf-8")
    if new in text:
        return
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{relative}: expected one anchor, found {count}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


def append_once(root: Path, relative: str, marker: str, text: str) -> None:
    path = root / relative
    current = path.read_text(encoding="utf-8")
    if marker in current:
        return
    path.write_text(current.rstrip() + "\n\n" + normalized(text), encoding="utf-8")


def main() -> None:
    import argparse

    parser = argparse.ArgumentParser()
    parser.add_argument("root", type=Path)
    args = parser.parse_args()
    root = args.root.resolve()

    write(
        root,
        "core/compiler/include/nodal/Conversion/NodalToCirct.h",
        r'''
        #ifndef NODAL_CONVERSION_NODALTOCIRCT_H
        #define NODAL_CONVERSION_NODALTOCIRCT_H

        #include <memory>

        namespace mlir {
        class Pass;
        }

        namespace nodal {

        std::unique_ptr<mlir::Pass> createNodalCirctPreflightPass();
        std::unique_ptr<mlir::Pass> createNodalCirctLegalizePass();
        void registerNodalToCirctPasses();

        } // namespace nodal

        #endif // NODAL_CONVERSION_NODALTOCIRCT_H
        ''',
    )

    write(
        root,
        "core/compiler/lib/Conversion/CMakeLists.txt",
        r'''
        add_mlir_library(NodalToCirct
          NodalToCirct.cpp

          ADDITIONAL_HEADER_DIRS
            "${NODAL_REPOSITORY_ROOT}/core/compiler/include/nodal/Conversion"

          LINK_LIBS PUBLIC
            CIRCTHW
            MLIRIR
            MLIRPass
            MLIRTransforms
            NodalDialect
        )
        ''',
    )

    write(
        root,
        "core/compiler/lib/Conversion/NodalToCirct.cpp",
        r'''
        #include "nodal/Conversion/NodalToCirct.h"

        #include "circt/Dialect/HW/HWDialect.h"
        #include "circt/Dialect/HW/HWOps.h"
        #include "mlir/IR/BuiltinDialect.h"
        #include "mlir/IR/BuiltinOps.h"
        #include "mlir/IR/BuiltinTypes.h"
        #include "mlir/IR/Diagnostics.h"
        #include "mlir/IR/DialectRegistry.h"
        #include "mlir/IR/OperationSupport.h"
        #include "mlir/Pass/Pass.h"
        #include "mlir/Pass/PassManager.h"
        #include "mlir/Transforms/DialectConversion.h"
        #include "nodal/Dialect/Nodal/NodalDialect.h"
        #include "nodal/Dialect/Nodal/NodalOps.h"
        #include "nodal/Dialect/Nodal/NodalTypes.h"

        #include "llvm/ADT/APInt.h"
        #include "llvm/ADT/SmallVector.h"
        #include "llvm/ADT/StringMap.h"
        #include "llvm/ADT/StringRef.h"
        #include "llvm/ADT/StringSet.h"
        #include "llvm/Support/CommandLine.h"

        #include <memory>
        #include <string>

        using namespace mlir;

        namespace {

        constexpr llvm::StringLiteral kStrategy = "nodal-to-circt-skeleton-v1";
        constexpr llvm::StringLiteral kProfile = "circt-hw-core";

        Type convertFiniteType(Type type) {
          MLIRContext *context = type.getContext();
          if (auto bits = llvm::dyn_cast<nodal::BitsType>(type))
            return IntegerType::get(context, bits.getWidth());
          if (auto value = llvm::dyn_cast<nodal::UIntType>(type))
            return IntegerType::get(context, value.getWidth(), IntegerType::Unsigned);
          if (auto value = llvm::dyn_cast<nodal::SIntType>(type))
            return IntegerType::get(context, value.getWidth(), IntegerType::Signed);
          return {};
        }

        LogicalResult verifyConvertibleConstant(nodal::ConstantOp operation) {
          if (operation->getNumResults() != 1)
            return operation.emitOpError(
                "[NODAL-CIRCT-022-001] requires exactly one result");
          Type converted = convertFiniteType(operation->getResult(0).getType());
          if (!converted)
            return operation.emitOpError(
                "[NODAL-CIRCT-022-002] result type is outside the Increment 22 "
                "finite-width conversion subset");
          if (!operation->getAttrOfType<IntegerAttr>("value"))
            return operation.emitOpError(
                "[NODAL-CIRCT-022-003] finite-width CIRCT conversion requires "
                "an integer value attribute");
          return success();
        }

        struct PreflightInventory {
          llvm::StringMap<unsigned> operationCounts;
          unsigned convertibleConstants = 0;
          unsigned deferredOperations = 0;
        };

        FailureOr<PreflightInventory> runPreflight(ModuleOp module,
                                                    bool rejectDeferred) {
          PreflightInventory inventory;
          WalkResult walkResult = module.walk([&](Operation *operation) -> WalkResult {
            StringRef dialect = operation->getName().getDialectNamespace();
            if (dialect != nodal::NodalDialect::getDialectNamespace())
              return WalkResult::advance();

            ++inventory.operationCounts[operation->getName().getStringRef()];
            if (auto constant = llvm::dyn_cast<nodal::ConstantOp>(operation)) {
              if (failed(verifyConvertibleConstant(constant)))
                return WalkResult::interrupt();
              ++inventory.convertibleConstants;
              return WalkResult::advance();
            }

            ++inventory.deferredOperations;
            if (rejectDeferred) {
              operation->emitError()
                  << "[NODAL-CIRCT-022-004] operation '"
                  << operation->getName()
                  << "' is intentionally deferred by the Increment 22 legalizer "
                     "skeleton";
              return WalkResult::interrupt();
            }
            return WalkResult::advance();
          });
          if (walkResult.wasInterrupted())
            return failure();
          return inventory;
        }

        class NodalToCirctTypeConverter final : public TypeConverter {
        public:
          NodalToCirctTypeConverter() {
            addConversion([](Type type) -> Type { return type; });
            addConversion([](nodal::BitsType type) -> Type {
              return IntegerType::get(type.getContext(), type.getWidth());
            });
            addConversion([](nodal::UIntType type) -> Type {
              return IntegerType::get(type.getContext(), type.getWidth(),
                                      IntegerType::Unsigned);
            });
            addConversion([](nodal::SIntType type) -> Type {
              return IntegerType::get(type.getContext(), type.getWidth(),
                                      IntegerType::Signed);
            });
          }
        };

        class NodalConstantToHW final
            : public OpConversionPattern<nodal::ConstantOp> {
        public:
          using OpConversionPattern::OpConversionPattern;

          LogicalResult
          matchAndRewrite(nodal::ConstantOp operation, OpAdaptor,
                          ConversionPatternRewriter &rewriter) const override {
            Type converted =
                getTypeConverter()->convertType(operation->getResult(0).getType());
            auto integerType = llvm::dyn_cast_or_null<IntegerType>(converted);
            auto sourceValue = operation->getAttrOfType<IntegerAttr>("value");
            if (!integerType || !sourceValue)
              return rewriter.notifyMatchFailure(
                  operation, "constant is outside the finite-width subset");

            llvm::APInt value = sourceValue.getValue();
            const unsigned width = integerType.getWidth();
            if (llvm::isa<nodal::SIntType>(operation->getResult(0).getType()))
              value = value.sextOrTrunc(width);
            else
              value = value.zextOrTrunc(width);

            OperationState state(operation.getLoc(),
                                 circt::hw::ConstantOp::getOperationName());
            state.addAttribute("value", rewriter.getIntegerAttr(integerType, value));
            state.addTypes(integerType);
            Operation *replacement = rewriter.create(state);
            rewriter.replaceOp(operation, replacement->getResults());
            return success();
          }
        };

        class NodalCirctPreflightPass final
            : public PassWrapper<NodalCirctPreflightPass,
                                 OperationPass<ModuleOp>> {
        public:
          MLIR_DEFINE_EXPLICIT_INTERNAL_INLINE_TYPE_ID(NodalCirctPreflightPass)

          StringRef getArgument() const final { return "nodal-circt-preflight"; }
          StringRef getDescription() const final {
            return "Inventory and validate the Increment 22 Nodal-to-CIRCT subset";
          }

          Option<bool> rejectDeferred{
              *this, "reject-deferred",
              llvm::cl::desc("Reject Nodal operations without Increment 22 patterns"),
              llvm::cl::init(false)};

          void runOnOperation() final {
            if (failed(runPreflight(getOperation(), rejectDeferred)))
              signalPassFailure();
          }
        };

        class NodalCirctLegalizePass final
            : public PassWrapper<NodalCirctLegalizePass,
                                 OperationPass<ModuleOp>> {
        public:
          MLIR_DEFINE_EXPLICIT_INTERNAL_INLINE_TYPE_ID(NodalCirctLegalizePass)

          StringRef getArgument() const final { return "nodal-circt-legalize"; }
          StringRef getDescription() const final {
            return "Transactionally lower the supported Nodal subset to CIRCT HW";
          }

          void getDependentDialects(DialectRegistry &registry) const final {
            registry.insert<circt::hw::HWDialect>();
          }

          void runOnOperation() final {
            ModuleOp module = getOperation();
            if (failed(runPreflight(module, false))) {
              signalPassFailure();
              return;
            }

            OwningOpRef<ModuleOp> candidate(
                llvm::cast<ModuleOp>(module->clone()));
            MLIRContext *context = &getContext();
            NodalToCirctTypeConverter typeConverter;
            RewritePatternSet patterns(context);
            patterns.add<NodalConstantToHW>(typeConverter, context);

            ConversionTarget target(*context);
            target.addLegalDialect<BuiltinDialect, circt::hw::HWDialect,
                                   nodal::NodalDialect>();
            target.addIllegalOp<nodal::ConstantOp>();

            if (failed(applyPartialConversion(*candidate, target,
                                              std::move(patterns)))) {
              module.emitError(
                  "[NODAL-CIRCT-022-005] transactional CIRCT legalization "
                  "failed; the input module was not modified");
              signalPassFailure();
              return;
            }

            Builder builder(context);
            (*candidate)->setAttr("nodal.circt.strategy",
                                  builder.getStringAttr(kStrategy));
            (*candidate)->setAttr("nodal.circt.profile",
                                  builder.getStringAttr(kProfile));
            (*candidate)->setAttr(
                "nodal.circt.converted_operations",
                builder.getArrayAttr(
                    {builder.getStringAttr("nodal.constant->hw.constant")}));
            (*candidate)->setAttr(
                "nodal.circt.deferred_policy",
                builder.getStringAttr("retain-explicit-nodal-operations"));

            module->setAttrs((*candidate)->getAttrs());
            module.getBodyRegion().takeBody(candidate->getRegion(0));
          }
        };

        } // namespace

        std::unique_ptr<Pass> nodal::createNodalCirctPreflightPass() {
          return std::make_unique<NodalCirctPreflightPass>();
        }

        std::unique_ptr<Pass> nodal::createNodalCirctLegalizePass() {
          return std::make_unique<NodalCirctLegalizePass>();
        }

        void nodal::registerNodalToCirctPasses() {
          PassRegistration<NodalCirctPreflightPass>();
          PassRegistration<NodalCirctLegalizePass>();
          PassPipelineRegistration<>(
              "nodal-circt-legalize-pipeline",
              "Run Increment 22 CIRCT preflight and transactional legalization",
              [](OpPassManager &manager) {
                manager.addPass(nodal::createNodalCirctPreflightPass());
                manager.addPass(nodal::createNodalCirctLegalizePass());
              });
        }
        ''',
    )

    replace_once(
        root,
        "core/compiler/lib/CMakeLists.txt",
        "add_subdirectory(Dialect)\nadd_subdirectory(Support)\n",
        "add_subdirectory(Dialect)\nadd_subdirectory(Conversion)\nadd_subdirectory(Support)\n",
    )

    replace_once(
        root,
        "core/compiler/tools/nodalc/nodalc.cpp",
        '#include "nodal/Dialect/Nodal/NodalDialect.h"\n',
        '#include "nodal/Conversion/NodalToCirct.h"\n#include "nodal/Dialect/Nodal/NodalDialect.h"\n',
    )
    replace_once(
        root,
        "core/compiler/tools/nodalc/nodalc.cpp",
        "  mlir::DialectRegistry registry;\n",
        "  nodal::registerNodalToCirctPasses();\n\n  mlir::DialectRegistry registry;\n",
    )
    replace_once(
        root,
        "core/compiler/tools/nodalc/CMakeLists.txt",
        "    NodalDialect\n",
        "    NodalDialect\n    NodalToCirct\n",
    )

    write(
        root,
        "core/compiler/test/IR/circt-legalizer.mlir",
        r'''
        module {
          %zero = "nodal.constant"() <{
            metadata = {semantic_path = "Top.zero"},
            value = 7 : i64
          }> : () -> !nodal.uint<8>
        }
        ''',
    )
    write(
        root,
        "core/compiler/test/IR/circt-legalizer-invalid.mlir",
        r'''
        module {
          %zero = "nodal.constant"() <{
            metadata = {semantic_path = "Top.real"},
            value = 1.0 : f64
          }> : () -> f64
        }
        ''',
    )
    write(
        root,
        "core/compiler/test/IR/circt-legalizer-deferred.mlir",
        r'''
        module {
          "nodal.placeholder"() <{label = "deferred"}> : () -> ()
        }
        ''',
    )

    append_once(
        root,
        "core/compiler/test/CMakeLists.txt",
        "nodal.native.circt-legalizer",
        r'''
        add_test(
          NAME nodal.native.circt-preflight
          COMMAND nodalc --nodal-circt-preflight
            "${CMAKE_CURRENT_SOURCE_DIR}/IR/circt-legalizer.mlir"
        )

        add_test(
          NAME nodal.native.circt-legalizer
          COMMAND nodalc --nodal-circt-legalize
            "${CMAKE_CURRENT_SOURCE_DIR}/IR/circt-legalizer.mlir"
        )
        set_tests_properties(nodal.native.circt-legalizer PROPERTIES
          PASS_REGULAR_EXPRESSION "hw.constant"
        )

        add_test(
          NAME nodal.native.circt-legalizer-pipeline
          COMMAND nodalc
            "--pass-pipeline=builtin.module(nodal-circt-preflight,nodal-circt-legalize)"
            "${CMAKE_CURRENT_SOURCE_DIR}/IR/circt-legalizer.mlir"
        )
        set_tests_properties(nodal.native.circt-legalizer-pipeline PROPERTIES
          PASS_REGULAR_EXPRESSION "nodal.circt.strategy"
        )

        add_test(
          NAME nodal.native.circt-legalizer-invalid
          COMMAND nodalc --nodal-circt-legalize
            "${CMAKE_CURRENT_SOURCE_DIR}/IR/circt-legalizer-invalid.mlir"
        )
        set_tests_properties(nodal.native.circt-legalizer-invalid PROPERTIES
          WILL_FAIL TRUE
        )

        add_test(
          NAME nodal.native.circt-preflight-reject-deferred
          COMMAND nodalc "--nodal-circt-preflight=reject-deferred=1"
            "${CMAKE_CURRENT_SOURCE_DIR}/IR/circt-legalizer-deferred.mlir"
        )
        set_tests_properties(nodal.native.circt-preflight-reject-deferred PROPERTIES
          WILL_FAIL TRUE
        )
        ''',
    )

    write(
        root,
        "core/compiler/test/Unit/CirctLegalizerTest.cpp",
        r'''
        #include "nodal/Conversion/NodalToCirct.h"

        #include "circt/Dialect/HW/HWDialect.h"
        #include "mlir/IR/BuiltinOps.h"
        #include "mlir/IR/DialectRegistry.h"
        #include "mlir/Parser/Parser.h"
        #include "mlir/Pass/PassManager.h"
        #include "nodal/Dialect/Nodal/NodalDialect.h"

        #include "llvm/ADT/StringRef.h"
        #include "llvm/Support/raw_ostream.h"

        namespace {

        int fail(llvm::StringRef message) {
          llvm::errs() << "NODAL-CIRCT-LEGALIZER-TEST: " << message << '\n';
          return 1;
        }

        } // namespace

        int main() {
          mlir::DialectRegistry registry;
          registry.insert<nodal::NodalDialect, circt::hw::HWDialect>();
          mlir::MLIRContext context(registry);

          auto module = mlir::parseSourceString<mlir::ModuleOp>(
              R"mlir(module {
                %value = "nodal.constant"() <{metadata = {}, value = 7 : i64}>
                  : () -> !nodal.uint<8>
              })mlir",
              &context);
          if (!module)
            return fail("valid legalization fixture did not parse");

          mlir::PassManager manager(&context);
          manager.addPass(nodal::createNodalCirctPreflightPass());
          manager.addPass(nodal::createNodalCirctLegalizePass());
          if (mlir::failed(manager.run(*module)))
            return fail("legalizer pipeline rejected the supported subset");

          bool foundConstant = false;
          module->walk([&](mlir::Operation *operation) {
            foundConstant |= operation->getName().getStringRef() == "hw.constant";
          });
          if (!foundConstant)
            return fail("nodal.constant was not converted to hw.constant");
          if (!module->getAttrOfType<mlir::StringAttr>("nodal.circt.strategy"))
            return fail("accepted conversion lacks strategy metadata");

          auto invalid = mlir::parseSourceString<mlir::ModuleOp>(
              R"mlir(module {
                %value = "nodal.constant"() <{metadata = {}, value = 1.0 : f64}>
                  : () -> f64
              })mlir",
              &context);
          if (!invalid)
            return fail("invalid legalization fixture did not parse locally");
          mlir::PassManager invalidManager(&context);
          invalidManager.addPass(nodal::createNodalCirctLegalizePass());
          if (mlir::succeeded(invalidManager.run(*invalid)))
            return fail("unsupported constant was accepted");
          if (invalid->getAttr("nodal.circt.strategy"))
            return fail("failed conversion published acceptance metadata");

          return 0;
        }
        ''',
    )

    append_once(
        root,
        "core/compiler/test/Unit/CMakeLists.txt",
        "nodal-circt-legalizer-unit-tests",
        r'''
        add_executable(nodal-circt-legalizer-unit-tests
          CirctLegalizerTest.cpp
        )

        llvm_update_compile_flags(nodal-circt-legalizer-unit-tests)

        target_link_libraries(nodal-circt-legalizer-unit-tests
          PRIVATE
            NodalToCirct
            NodalDialect
            CIRCTHW
            MLIRIR
            MLIRParser
            MLIRPass
            MLIRSupport
            LLVMSupport
        )

        add_test(
          NAME nodal.native.circt-legalizer-unit
          COMMAND nodal-circt-legalizer-unit-tests
        )
        ''',
    )
    replace_once(
        root,
        "core/compiler/test/CMakeLists.txt",
        "    nodal-dialect-unit-tests\n",
        "    nodal-dialect-unit-tests\n    nodal-circt-legalizer-unit-tests\n",
    )

    write(
        root,
        "docs/design-gates/NodalCirctConversionStrategy-DG-v1.0.md",
        r'''
        # Nodal CIRCT conversion strategy design gate v1.0

        **Revision:** v1.0
        **Status:** Approved
        **Scope:** compiler-conversion
        **Public API:** unchanged at 0.3
        **Approved authority:** standing Nodal increment implementation and merge authorization

        ## Decision

        Increment 22 establishes a target-neutral, transactional conversion seam
        between verified Nodal MLIR and selected CIRCT dialects. Nodal semantics
        remain authoritative. CIRCT operations are introduced only when their
        semantics exactly match the verified source operation and the selected
        capability profile.

        The first executable subset converts finite-width `nodal.constant`
        operations to `hw.constant`. All other Nodal operations remain explicit
        and are listed as deferred rather than approximated, erased, or silently
        reinterpreted.

        ## Binding strategy

        - `nodal-circt-preflight` inventories the input and validates every
          operation for which Increment 22 publishes a conversion pattern.
        - `nodal-circt-legalize` runs conversion on a clone and commits the clone
          only after the conversion target succeeds.
        - `nodal-circt-legalize-pipeline` exposes the same sequence through the
          normal MLIR textual pass-pipeline syntax.
        - Converted finite-width values preserve width and signedness intent.
        - Failed conversion cannot publish strategy metadata or partially replace
          the accepted input module.
        - Deferred Nodal operations remain legal by default; strict preflight can
          reject them for FileCheck/lit-style capability tests.
        - The pass records strategy, profile, converted-operation, and deferred
          policy metadata deterministically.

        ## Explicitly deferred

        Module/port lowering, hierarchy materialization, domain and sequential
        lowering, interfaces, resolved nets, conservative connectivity, FSMs,
        memories, scheduling, analog equations, backend layouts, and HDL emission
        remain assigned to later roadmap increments. No fallback to untyped text
        or clone-per-parameter specialization is permitted.
        ''',
    )

    write(
        root,
        "docs/implementation/increment22-circt-conversion-legalizer.md",
        r'''
        # Increment 22 — CIRCT conversion strategy and legalizer skeleton

        Increment 22 adds the first registered Nodal-to-CIRCT conversion library.
        The implementation deliberately starts with one exact operation mapping:
        finite-width `nodal.constant` to CIRCT `hw.constant`.

        The preflight pass inventories Nodal operations, verifies convertible
        constants, and can reject all deferred operations when a strict capability
        gate is requested. The legalizer applies patterns to a cloned builtin
        module and only replaces the original module after successful partial
        conversion. It publishes deterministic strategy metadata on success and
        leaves no acceptance metadata after failure.

        The permanent tests exercise direct pass flags, textual pass pipelines,
        generic normalized IR, strict deferred-operation rejection, unsupported
        type rejection, and the native C++ pass API. Public Scala API v0.3 and the
        verified Nodal IR semantics are unchanged.
        ''',
    )

    manifest = {
        "increment": 22,
        "status": "implemented-awaiting-evidence",
        "public_api": "0.3",
        "strategy": "nodal-to-circt-skeleton-v1",
        "profile": "circt-hw-core",
        "converted_operations": ["nodal.constant->hw.constant"],
        "deferred_policy": "retain-explicit-nodal-operations",
        "evidence": {},
    }
    write(
        root,
        "tests/compiler/fixtures/increment22/manifest.json",
        json.dumps(manifest, indent=2) + "\n",
    )

    write(
        root,
        "scripts/check_increment22.py",
        r'''
        #!/usr/bin/env python3
        """Validate Increment 22 CIRCT conversion strategy and legalizer skeleton."""

        from __future__ import annotations

        import argparse
        import json
        import re
        import sys
        from dataclasses import dataclass
        from pathlib import Path


        @dataclass(frozen=True)
        class Problem:
            code: str
            message: str

            def __str__(self) -> str:
                return f"{self.code}: {self.message}"


        EXPECTED_FILES = (
            "core/compiler/include/nodal/Conversion/NodalToCirct.h",
            "core/compiler/lib/Conversion/CMakeLists.txt",
            "core/compiler/lib/Conversion/NodalToCirct.cpp",
            "core/compiler/test/IR/circt-legalizer.mlir",
            "core/compiler/test/IR/circt-legalizer-invalid.mlir",
            "core/compiler/test/IR/circt-legalizer-deferred.mlir",
            "core/compiler/test/Unit/CirctLegalizerTest.cpp",
            "docs/design-gates/NodalCirctConversionStrategy-DG-v1.0.md",
            "docs/implementation/increment22-circt-conversion-legalizer.md",
            "tests/compiler/fixtures/increment22/manifest.json",
            "tests/compiler/test_increment22.py",
            "scripts/check_increment22.py",
            ".github/workflows/increment-22-circt-conversion-legalizer.yml",
        )

        TEMPORARY_FILES = (
            "scripts/materialize_increment22.py",
            ".github/workflows/increment-22-materialize.yml",
            ".github/workflows/increment-22-finalize.yml",
            ".github/workflows/increment-22-supervisor.yml",
        )


        def read(path: Path, problems: list[Problem], code: str) -> str:
            try:
                return path.read_text(encoding="utf-8")
            except OSError as exc:
                problems.append(Problem(code, f"cannot read {path}: {exc}"))
                return ""


        def require(text: str, fragments: tuple[str, ...], problems: list[Problem],
                    code: str, subject: str) -> None:
            for fragment in fragments:
                if fragment not in text:
                    problems.append(Problem(code, f"{subject} lacks: {fragment}"))


        def revision(text: str) -> tuple[int, ...]:
            match = re.search(r"^\*\*Revision:\*\* ([0-9.]+)$", text, re.MULTILINE)
            return tuple(int(part) for part in match.group(1).split(".")) if match else ()


        def check_repository(root: Path) -> list[Problem]:
            root = root.resolve()
            problems: list[Problem] = []
            for relative in EXPECTED_FILES:
                if not (root / relative).is_file():
                    problems.append(Problem("NODAL-INC22-001", f"missing file: {relative}"))
            for relative in TEMPORARY_FILES:
                if (root / relative).exists():
                    problems.append(Problem("NODAL-INC22-002", f"temporary file remains: {relative}"))

            implementation = read(
                root / "core/compiler/lib/Conversion/NodalToCirct.cpp",
                problems, "NODAL-INC22-003")
            driver = read(root / "core/compiler/tools/nodalc/nodalc.cpp",
                          problems, "NODAL-INC22-004")
            cmake = read(root / "core/compiler/tools/nodalc/CMakeLists.txt",
                         problems, "NODAL-INC22-004")
            tests = read(root / "core/compiler/test/CMakeLists.txt",
                         problems, "NODAL-INC22-005")
            workflow = read(
                root / ".github/workflows/increment-22-circt-conversion-legalizer.yml",
                problems, "NODAL-INC22-006")
            gate = read(
                root / "docs/design-gates/NodalCirctConversionStrategy-DG-v1.0.md",
                problems, "NODAL-INC22-007")
            roadmap = read(root / "docs/roadmap/nodal-development-todo.md",
                           problems, "NODAL-INC22-008")

            require(implementation, (
                "nodal-circt-preflight",
                "nodal-circt-legalize",
                "nodal-circt-legalize-pipeline",
                "NodalToCirctTypeConverter",
                "NodalConstantToHW",
                "circt::hw::ConstantOp",
                "applyPartialConversion",
                "module->clone()",
                "takeBody",
                "NODAL-CIRCT-022-002",
                "nodal.circt.strategy",
                "retain-explicit-nodal-operations",
            ), problems, "NODAL-INC22-003", "conversion implementation")
            require(driver, ("registerNodalToCirctPasses",), problems,
                    "NODAL-INC22-004", "nodalc driver")
            require(cmake, ("NodalToCirct",), problems,
                    "NODAL-INC22-004", "nodalc link target")
            require(tests, (
                "nodal.native.circt-preflight",
                "nodal.native.circt-legalizer",
                "nodal.native.circt-legalizer-pipeline",
                "nodal.native.circt-legalizer-invalid",
            ), problems, "NODAL-INC22-005", "native tests")
            require(workflow, (
                "increment-22/circt-conversion-legalizer",
                "check_increment22.py",
                "./nodal core native",
                "nodal-circt-legalize-pipeline",
                "permissions:\n  contents: read",
            ), problems, "NODAL-INC22-006", "permanent workflow")
            if "contents: write" in workflow or "materialize_increment22" in workflow:
                problems.append(Problem("NODAL-INC22-006", "permanent workflow must be read-only"))
            require(gate, (
                "**Status:** Approved",
                "**Scope:** compiler-conversion",
                "**Public API:** unchanged at 0.3",
                "finite-width `nodal.constant`",
                "runs conversion on a clone",
            ), problems, "NODAL-INC22-007", "design gate")

            manifest_path = root / "tests/compiler/fixtures/increment22/manifest.json"
            try:
                manifest = json.loads(read(manifest_path, problems, "NODAL-INC22-008"))
            except json.JSONDecodeError as exc:
                problems.append(Problem("NODAL-INC22-008", f"invalid manifest: {exc}"))
                manifest = {}
            if manifest.get("increment") != 22:
                problems.append(Problem("NODAL-INC22-008", "manifest increment must be 22"))
            if manifest.get("strategy") != "nodal-to-circt-skeleton-v1":
                problems.append(Problem("NODAL-INC22-008", "strategy mismatch"))
            if manifest.get("public_api") != "0.3":
                problems.append(Problem("NODAL-INC22-008", "public API must remain 0.3"))

            status = manifest.get("status")
            evidence = manifest.get("evidence", {})
            title = "Increment 22 — CIRCT conversion strategy and legalizer skeleton"
            unchecked = f"- [ ] **{title}**" in roadmap
            checked = f"- [x] **{title}**" in roadmap
            if status == "implemented-awaiting-evidence":
                if not unchecked or revision(roadmap) < (1, 25):
                    problems.append(Problem("NODAL-INC22-008", "pre-evidence roadmap state is invalid"))
            elif status == "validated-circt-legalizer-skeleton":
                if not checked or revision(roadmap) < (1, 26):
                    problems.append(Problem("NODAL-INC22-008", "validated roadmap state is invalid"))
                for field in ("pull_request", "dedicated_run", "core_ci_run"):
                    if not isinstance(evidence.get(field), int):
                        problems.append(Problem("NODAL-INC22-008", f"missing evidence: {field}"))
            else:
                problems.append(Problem("NODAL-INC22-008", f"unexpected status: {status!r}"))
            return problems


        def main(argv: list[str] | None = None) -> int:
            parser = argparse.ArgumentParser(description=__doc__)
            parser.add_argument("--root", type=Path,
                                default=Path(__file__).resolve().parents[1])
            args = parser.parse_args(argv)
            problems = check_repository(args.root)
            for problem in problems:
                print(problem, file=sys.stderr)
            if problems:
                print(f"Increment 22 check failed with {len(problems)} problem(s)",
                      file=sys.stderr)
                return 1
            print("Increment 22 check passed")
            return 0


        if __name__ == "__main__":
            raise SystemExit(main())
        ''',
    )

    write(
        root,
        "tests/compiler/test_increment22.py",
        r'''
        from __future__ import annotations

        import importlib.util
        import shutil
        import sys
        import tempfile
        import unittest
        from pathlib import Path

        ROOT = Path(__file__).resolve().parents[2]
        SCRIPT = ROOT / "scripts/check_increment22.py"
        SPEC = importlib.util.spec_from_file_location("check_increment22", SCRIPT)
        assert SPEC and SPEC.loader
        CHECKER = importlib.util.module_from_spec(SPEC)
        sys.modules[SPEC.name] = CHECKER
        SPEC.loader.exec_module(CHECKER)

        SUPPORT_FILES = (
            "core/compiler/tools/nodalc/nodalc.cpp",
            "core/compiler/tools/nodalc/CMakeLists.txt",
            "core/compiler/test/CMakeLists.txt",
            "docs/roadmap/nodal-development-todo.md",
        )


        class Increment22CheckerTests(unittest.TestCase):
            def temporary_repository(self):
                temporary = tempfile.TemporaryDirectory()
                root = Path(temporary.name)
                for relative in dict.fromkeys(CHECKER.EXPECTED_FILES + SUPPORT_FILES):
                    source = ROOT / relative
                    destination = root / relative
                    destination.parent.mkdir(parents=True, exist_ok=True)
                    shutil.copy2(source, destination)
                return temporary, root

            def codes(self, root: Path) -> set[str]:
                return {problem.code for problem in CHECKER.check_repository(root)}

            def test_repository_contract(self) -> None:
                self.assertEqual(CHECKER.check_repository(ROOT), [])

            def test_rejects_missing_transactional_clone(self) -> None:
                temporary, root = self.temporary_repository()
                self.addCleanup(temporary.cleanup)
                path = root / "core/compiler/lib/Conversion/NodalToCirct.cpp"
                path.write_text(path.read_text(encoding="utf-8").replace(
                    "module->clone()", "module.getOperation()"), encoding="utf-8")
                self.assertIn("NODAL-INC22-003", self.codes(root))

            def test_rejects_writable_workflow(self) -> None:
                temporary, root = self.temporary_repository()
                self.addCleanup(temporary.cleanup)
                path = root / ".github/workflows/increment-22-circt-conversion-legalizer.yml"
                path.write_text(path.read_text(encoding="utf-8").replace(
                    "contents: read", "contents: write"), encoding="utf-8")
                self.assertIn("NODAL-INC22-006", self.codes(root))

            def test_rejects_silent_deferred_erasure(self) -> None:
                temporary, root = self.temporary_repository()
                self.addCleanup(temporary.cleanup)
                path = root / "core/compiler/lib/Conversion/NodalToCirct.cpp"
                path.write_text(path.read_text(encoding="utf-8").replace(
                    "retain-explicit-nodal-operations", "erase-deferred"), encoding="utf-8")
                self.assertIn("NODAL-INC22-003", self.codes(root))


        if __name__ == "__main__":
            unittest.main()
        ''',
    )

    write(
        root,
        ".github/workflows/increment-22-circt-conversion-legalizer.yml",
        r'''
        name: Increment 22 CIRCT Conversion Legalizer

        on:
          push:
            branches:
              - increment/22-circt-conversion-legalizer
          pull_request:
            branches:
              - dev
          workflow_dispatch:

        permissions:
          contents: read

        concurrency:
          group: increment-22-${{ github.workflow }}-${{ github.ref }}
          cancel-in-progress: true

        jobs:
          circt-conversion-legalizer:
            name: increment-22/circt-conversion-legalizer
            runs-on: ubuntu-24.04
            timeout-minutes: 120

            steps:
              - name: Check out repository
                uses: actions/checkout@v6
                with:
                  fetch-depth: 0

              - name: Restore locked native download cache
                uses: actions/cache@v5
                with:
                  path: ~/.cache/nodal/downloads
                  key: nodal-native-download-${{ runner.os }}-${{ hashFiles('toolchains/lock.json', 'toolchains/checksums/*.sha256') }}

              - name: Install locked native and lint toolchains
                run: |
                  ./nodal bootstrap --mode prebuilt \
                    --prefix "${RUNNER_TEMP}/nodal-native-toolchain"
                  ./nodal style bootstrap \
                    --prefix "${RUNNER_TEMP}/nodal-lint-toolchain"

              - name: Validate structural and predecessor contracts
                run: |
                  python3 scripts/check_increment20.py
                  python3 scripts/check_increment21.py
                  python3 scripts/check_increment22.py
                  python3 -m unittest discover -s tests/compiler -p 'test_*.py'
                  ./nodal check --contracts-only --online-toolchain \
                    --lint-toolchain "${RUNNER_TEMP}/nodal-lint-toolchain" \
                    --base-ref origin/dev
                  git diff --check

              - name: Build and test native compiler
                run: |
                  ./nodal core native \
                    --toolchain "${RUNNER_TEMP}/nodal-native-toolchain" \
                    --lint-toolchain "${RUNNER_TEMP}/nodal-lint-toolchain"

              - name: Prove direct and textual legalizer pipelines
                run: |
                  compiler=out/native/release/bin/nodalc
                  "${compiler}" --nodal-circt-legalize \
                    core/compiler/test/IR/circt-legalizer.mlir \
                    | tee /tmp/circt-legalized.mlir
                  grep -F 'hw.constant' /tmp/circt-legalized.mlir
                  grep -F 'nodal.circt.strategy' /tmp/circt-legalized.mlir

                  "${compiler}" \
                    "--pass-pipeline=builtin.module(nodal-circt-preflight,nodal-circt-legalize)" \
                    core/compiler/test/IR/circt-legalizer.mlir \
                    | tee /tmp/circt-pipeline.mlir
                  grep -F 'hw.constant' /tmp/circt-pipeline.mlir

                  if "${compiler}" --nodal-circt-legalize \
                      core/compiler/test/IR/circt-legalizer-invalid.mlir \
                      >/tmp/circt-invalid.log 2>&1; then
                    echo 'unsupported CIRCT conversion fixture was accepted' >&2
                    exit 1
                  fi
                  grep -F 'NODAL-CIRCT-022-002' /tmp/circt-invalid.log
                  git diff --check
        ''',
    )

    append_once(
        root,
        ".github/CODEOWNERS",
        "# Increment 22 CIRCT conversion strategy and legalizer skeleton.",
        r'''
        # Increment 22 CIRCT conversion strategy and legalizer skeleton.
        /core/compiler/include/nodal/Conversion/ @pysolvesemi
        /core/compiler/lib/Conversion/ @pysolvesemi
        /scripts/check_increment22.py @pysolvesemi
        /tests/compiler/test_increment22.py @pysolvesemi
        /tests/compiler/fixtures/increment22/ @pysolvesemi
        /.github/workflows/increment-22-circt-conversion-legalizer.yml @pysolvesemi
        /docs/design-gates/NodalCirctConversionStrategy-DG-v1.0.md @pysolvesemi
        /docs/implementation/increment22-circt-conversion-legalizer.md @pysolvesemi
        ''',
    )


if __name__ == "__main__":
    main()
