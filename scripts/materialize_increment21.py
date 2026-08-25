#!/usr/bin/env python3
"""Materialize Increment 21 only when authoritative dev has not closed it."""

from __future__ import annotations

import json
import textwrap
from pathlib import Path


def norm(text: str) -> str:
    return textwrap.dedent(text).strip("\n") + "\n"


def write(root: Path, relative: str, text: str) -> None:
    path = root / relative
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(norm(text), encoding="utf-8")


def replace_once(root: Path, relative: str, old: str, new: str) -> None:
    path = root / relative
    text = path.read_text(encoding="utf-8")
    if new in text:
        return
    if text.count(old) != 1:
        raise RuntimeError(f"{relative}: expected one anchor, found {text.count(old)}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


def append_once(root: Path, relative: str, marker: str, text: str) -> None:
    path = root / relative
    current = path.read_text(encoding="utf-8")
    if marker in current:
        return
    path.write_text(current.rstrip() + "\n\n" + norm(text), encoding="utf-8")


def main() -> None:
    import argparse
    parser = argparse.ArgumentParser()
    parser.add_argument("root", type=Path)
    args = parser.parse_args()
    root = args.root.resolve()

    write(
        root,
        "core/compiler/include/nodal/Transforms/NodalVerification.h",
        r'''
        #ifndef NODAL_TRANSFORMS_NODALVERIFICATION_H
        #define NODAL_TRANSFORMS_NODALVERIFICATION_H

        #include <memory>

        namespace mlir {
        class Pass;
        }

        namespace nodal {

        std::unique_ptr<mlir::Pass> createNodalVerifyStagePass();
        std::unique_ptr<mlir::Pass> createNodalGateCheckPass();
        std::unique_ptr<mlir::Pass> createNodalTransactionalGatePass();
        void registerNodalVerificationPasses();

        } // namespace nodal

        #endif // NODAL_TRANSFORMS_NODALVERIFICATION_H
        ''',
    )

    write(
        root,
        "core/compiler/lib/Transforms/CMakeLists.txt",
        r'''
        add_mlir_library(NodalVerification
          NodalVerification.cpp

          ADDITIONAL_HEADER_DIRS
            "${NODAL_REPOSITORY_ROOT}/core/compiler/include/nodal/Transforms"

          LINK_LIBS PUBLIC
            MLIRIR
            MLIRPass
            MLIRTransforms
            NodalDialect
        )
        ''',
    )

    write(
        root,
        "core/compiler/lib/Transforms/NodalVerification.cpp",
        r'''
        #include "nodal/Transforms/NodalVerification.h"

        #include "mlir/IR/BuiltinAttributes.h"
        #include "mlir/IR/BuiltinOps.h"
        #include "mlir/IR/Diagnostics.h"
        #include "mlir/IR/SymbolTable.h"
        #include "mlir/IR/Verifier.h"
        #include "mlir/Pass/Pass.h"
        #include "mlir/Pass/PassManager.h"
        #include "nodal/Dialect/Nodal/NodalOps.h"
        #include "nodal/Dialect/Nodal/NodalTypes.h"

        #include "llvm/ADT/DenseMap.h"
        #include "llvm/ADT/SmallVector.h"
        #include "llvm/ADT/StringMap.h"
        #include "llvm/ADT/StringRef.h"
        #include "llvm/ADT/StringSet.h"
        #include "llvm/Support/CommandLine.h"

        #include <functional>
        #include <memory>
        #include <optional>
        #include <string>

        using namespace mlir;

        namespace {

        constexpr llvm::StringLiteral kPipeline = "nodal-native-gate-v1";

        enum class Stage {
          Construction,
          Hierarchy,
          Drivers,
          Types,
          Parameters,
          EnumFsm,
          Domains,
          Protocols,
          Effects,
          Analog,
          Target,
        };

        struct StageDescriptor {
          Stage stage;
          llvm::StringLiteral name;
        };

        constexpr StageDescriptor kStages[] = {
            {Stage::Construction, "construction"},
            {Stage::Hierarchy, "hierarchy"},
            {Stage::Drivers, "drivers-latches-cycles"},
            {Stage::Types, "width-sign-shape-layout-storage"},
            {Stage::Parameters, "parameters-generate-loops"},
            {Stage::EnumFsm, "enum-fsm"},
            {Stage::Domains, "clock-reset-cdc-rdc"},
            {Stage::Protocols, "protocol-pipeline"},
            {Stage::Effects, "memory-effects"},
            {Stage::Analog, "analog-mixed-signal"},
            {Stage::Target, "target-capability"},
        };

        std::optional<Stage> parseStage(StringRef value) {
          for (const StageDescriptor &descriptor : kStages)
            if (descriptor.name == value)
              return descriptor.stage;
          return std::nullopt;
        }

        StringRef stageName(Stage stage) {
          for (const StageDescriptor &descriptor : kStages)
            if (descriptor.stage == stage)
              return descriptor.name;
          llvm_unreachable("unknown verification stage");
        }

        LogicalResult error(Operation *operation, StringRef code,
                            const Twine &message) {
          operation->emitError() << '[' << code << "] " << message;
          return failure();
        }

        LogicalResult verifyConstruction(ModuleOp module) {
          if (failed(mlir::verify(module)))
            return error(module, "NODAL-VERIFY-CONSTRUCTION-001",
                         "parsed IR or operation-local verification failed");
          if (auto schema = module->getAttrOfType<StringAttr>("nodal.bridge.schema")) {
            if (schema.getValue() != "nodal.scala-to-mlir")
              return error(module, "NODAL-VERIFY-CONSTRUCTION-002",
                           "unsupported Scala bridge schema");
            auto version =
                module->getAttrOfType<IntegerAttr>("nodal.bridge.version");
            if (!version || version.getInt() != 1)
              return error(module, "NODAL-VERIFY-CONSTRUCTION-003",
                           "unsupported Scala bridge schema version");
          }
          return success();
        }

        struct ModuleInfo {
          nodal::ModuleOp operation;
          llvm::StringSet<> parameters;
          llvm::StringSet<> domains;
          llvm::StringSet<> requirements;
        };

        llvm::StringMap<ModuleInfo> collectModules(ModuleOp root) {
          llvm::StringMap<ModuleInfo> modules;
          root.walk([&](nodal::ModuleOp operation) {
            StringRef name = SymbolTable::getSymbolName(operation).getValue();
            ModuleInfo info{operation, {}, {}, {}};
            operation.getBody().walk([&](Operation *nested) {
              if (nested->getParentOp() != operation)
                return;
              if (auto parameter = llvm::dyn_cast<nodal::ParameterOp>(nested))
                info.parameters.insert(
                    SymbolTable::getSymbolName(parameter).getValue());
              else if (auto domain = llvm::dyn_cast<nodal::DomainOp>(nested))
                info.domains.insert(SymbolTable::getSymbolName(domain).getValue());
              else if (auto requirement =
                           llvm::dyn_cast<nodal::DomainRequirementOp>(nested))
                info.requirements.insert(
                    SymbolTable::getSymbolName(requirement).getValue());
            });
            modules.try_emplace(name, std::move(info));
          });
          return modules;
        }

        LogicalResult verifyHierarchy(ModuleOp root) {
          auto modules = collectModules(root);
          bool failedCheck = false;
          root.walk([&](nodal::InstanceOp instance) {
            auto reference =
                instance->getAttrOfType<FlatSymbolRefAttr>("module");
            if (!reference || !modules.contains(reference.getValue())) {
              error(instance, "NODAL-VERIFY-HIERARCHY-001",
                    "instance references an unknown module");
              failedCheck = true;
              return;
            }
            const ModuleInfo &child = modules.find(reference.getValue())->second;
            auto parameterBindings =
                instance->getAttrOfType<DictionaryAttr>("parameter_bindings");
            if (parameterBindings) {
              for (NamedAttribute binding : parameterBindings) {
                if (!child.parameters.contains(binding.getName().getValue())) {
                  error(instance, "NODAL-VERIFY-HIERARCHY-002",
                        "instance binds an unknown child parameter");
                  failedCheck = true;
                }
              }
            }
            auto domainBindings =
                instance->getAttrOfType<DictionaryAttr>("domain_bindings");
            if (domainBindings) {
              for (NamedAttribute binding : domainBindings) {
                if (!child.requirements.contains(binding.getName().getValue()) &&
                    binding.getName().getValue() != "default") {
                  error(instance, "NODAL-VERIFY-HIERARCHY-003",
                        "instance binds an unknown child domain requirement");
                  failedCheck = true;
                }
              }
            }
          });

          llvm::StringMap<llvm::SmallVector<std::string>> graph;
          root.walk([&](nodal::ModuleOp module) {
            StringRef owner = SymbolTable::getSymbolName(module).getValue();
            module.getBody().walk([&](nodal::InstanceOp instance) {
              if (instance->getParentOp() != module)
                return;
              if (auto reference =
                      instance->getAttrOfType<FlatSymbolRefAttr>("module"))
                graph[owner].push_back(reference.getValue().str());
            });
          });
          llvm::StringSet<> active;
          llvm::StringSet<> complete;
          std::function<bool(StringRef)> visit = [&](StringRef name) {
            if (active.contains(name))
              return false;
            if (complete.contains(name))
              return true;
            active.insert(name);
            for (const std::string &child : graph[name])
              if (!visit(child))
                return false;
            active.erase(name);
            complete.insert(name);
            return true;
          };
          for (const auto &entry : modules)
            if (!visit(entry.getKey())) {
              error(root, "NODAL-VERIFY-HIERARCHY-004",
                    "recursive module-instantiation cycle detected");
              failedCheck = true;
              break;
            }
          return failure(failedCheck);
        }

        LogicalResult verifyDrivers(ModuleOp root) {
          llvm::StringSet<> driverIds;
          bool failedCheck = false;
          root.walk([&](nodal::NetDriverOp driver) {
            auto identity = driver->getAttrOfType<StringAttr>("driver_id");
            if (!identity || identity.getValue().empty() ||
                !driverIds.insert(identity.getValue()).second) {
              error(driver, "NODAL-VERIFY-DRIVER-001",
                    "resolved-net driver identity is empty or duplicated");
              failedCheck = true;
            }
            if (driver->getResult(0).use_empty()) {
              error(driver, "NODAL-VERIFY-DRIVER-002",
                    "declared resolved-net driver is never used");
              failedCheck = true;
            }
          });
          root.walk([&](Operation *operation) {
            if (auto latch =
                    operation->getAttrOfType<BoolAttr>("nodal.explicit_latch")) {
              if (!latch.getValue()) {
                error(operation, "NODAL-VERIFY-LATCH-001",
                      "implicit latch intent is forbidden");
                failedCheck = true;
              }
            }
            if (auto cycle = operation->getAttrOfType<BoolAttr>(
                    "nodal.combinational_cycle")) {
              if (cycle.getValue()) {
                error(operation, "NODAL-VERIFY-CYCLE-001",
                      "combinational cycle marker is forbidden");
                failedCheck = true;
              }
            }
          });
          return failure(failedCheck);
        }

        LogicalResult verifyTypes(ModuleOp root) {
          bool failedCheck = false;
          root.walk([&](Operation *operation) {
            auto inspect = [&](Type type) {
              if (auto shaped = llvm::dyn_cast<nodal::ShapedType>(type)) {
                SmallVector<StringRef> dimensions;
                shaped.getDimensions().split(dimensions, ',');
                if (dimensions.empty()) {
                  error(operation, "NODAL-VERIFY-SHAPE-001",
                        "shaped value must have non-empty rank");
                  failedCheck = true;
                }
                for (StringRef dimension : dimensions) {
                  dimension = dimension.trim();
                  int64_t numeric = 0;
                  if (!dimension.getAsInteger(10, numeric) && numeric <= 0) {
                    error(operation, "NODAL-VERIFY-SHAPE-002",
                          "numeric shaped dimension must be positive");
                    failedCheck = true;
                  }
                }
              }
            };
            for (Type type : operation->getOperandTypes())
              inspect(type);
            for (Type type : operation->getResultTypes())
              inspect(type);
          });
          return failure(failedCheck);
        }

        LogicalResult verifyParameters(ModuleOp root) {
          bool failedCheck = false;
          root.walk([&](Operation *operation) {
            if (!llvm::isa<nodal::GenerateOp, nodal::HardwareLoopOp>(operation))
              return;
            auto lower = operation->getAttrOfType<IntegerAttr>("lower");
            auto upper = operation->getAttrOfType<IntegerAttr>("upper");
            auto step = operation->getAttrOfType<IntegerAttr>("step");
            if (step && step.getInt() == 0) {
              error(operation, "NODAL-VERIFY-LOOP-001",
                    "generate or hardware-loop step must not be zero");
              failedCheck = true;
            }
            if (lower && upper && step) {
              if ((lower.getInt() < upper.getInt() && step.getInt() < 0) ||
                  (lower.getInt() > upper.getInt() && step.getInt() > 0)) {
                error(operation, "NODAL-VERIFY-LOOP-002",
                      "constant loop bounds cannot terminate with the selected step");
                failedCheck = true;
              }
            }
          });
          return failure(failedCheck);
        }

        LogicalResult verifyEnumFsm(ModuleOp root) {
          bool failedCheck = false;
          root.walk([&](nodal::FsmOp fsm) {
            llvm::StringSet<> states;
            fsm.getBody().walk([&](nodal::FsmStateOp state) {
              states.insert(SymbolTable::getSymbolName(state).getValue());
            });
            fsm.getBody().walk([&](nodal::FsmTransitionOp transition) {
              auto destination =
                  transition->getAttrOfType<FlatSymbolRefAttr>("destination");
              if (!destination || !states.contains(destination.getValue())) {
                error(transition, "NODAL-VERIFY-FSM-001",
                      "FSM transition references an unknown state");
                failedCheck = true;
              }
            });
          });
          return failure(failedCheck);
        }

        LogicalResult verifyDomains(ModuleOp root) {
          bool failedCheck = false;
          root.walk([&](nodal::ModuleOp module) {
            llvm::StringSet<> domains;
            llvm::StringSet<> requirements;
            module.getBody().walk([&](Operation *operation) {
              if (operation->getParentOp() != module)
                return;
              if (auto domain = llvm::dyn_cast<nodal::DomainOp>(operation))
                domains.insert(SymbolTable::getSymbolName(domain).getValue());
              else if (auto requirement =
                           llvm::dyn_cast<nodal::DomainRequirementOp>(operation))
                requirements.insert(
                    SymbolTable::getSymbolName(requirement).getValue());
            });
            module.getBody().walk([&](Operation *operation) {
              if (operation->getParentOp() != module)
                return;
              auto checkReference = [&](StringRef attribute, StringRef code) {
                if (auto reference =
                        operation->getAttrOfType<FlatSymbolRefAttr>(attribute)) {
                  if (!domains.contains(reference.getValue()) &&
                      !requirements.contains(reference.getValue())) {
                    error(operation, code, "domain reference is unresolved");
                    failedCheck = true;
                  }
                }
              };
              checkReference("domain", "NODAL-VERIFY-DOMAIN-001");
              checkReference("source_domain", "NODAL-VERIFY-CDC-001");
              checkReference("destination_domain", "NODAL-VERIFY-CDC-002");
              checkReference("source", "NODAL-VERIFY-DOMAIN-002");
              checkReference("destination", "NODAL-VERIFY-DOMAIN-003");
              checkReference("actual", "NODAL-VERIFY-DOMAIN-004");
              checkReference("requirement", "NODAL-VERIFY-DOMAIN-005");
            });
          });
          return failure(failedCheck);
        }

        LogicalResult verifyProtocols(ModuleOp root) {
          llvm::StringMap<llvm::StringSet<>> interfaceRoles;
          root.walk([&](nodal::InterfaceOp interfaceOperation) {
            StringRef name =
                SymbolTable::getSymbolName(interfaceOperation).getValue();
            llvm::StringSet<> roles;
            interfaceOperation.getBody().walk([&](nodal::InterfaceRoleOp role) {
              roles.insert(SymbolTable::getSymbolName(role).getValue());
            });
            interfaceRoles.try_emplace(name, std::move(roles));
          });
          bool failedCheck = false;
          root.walk([&](nodal::InterfaceInstanceOp instance) {
            auto definition =
                instance->getAttrOfType<FlatSymbolRefAttr>("definition");
            auto role = instance->getAttrOfType<StringAttr>("role");
            if (!definition || !interfaceRoles.contains(definition.getValue())) {
              error(instance, "NODAL-VERIFY-PROTOCOL-001",
                    "interface instance references an unknown definition");
              failedCheck = true;
              return;
            }
            if (!role || !interfaceRoles.find(definition.getValue())
                              ->second.contains(role.getValue())) {
              error(instance, "NODAL-VERIFY-PROTOCOL-002",
                    "interface instance selects an unknown role");
              failedCheck = true;
            }
          });
          llvm::StringSet<> abiPaths;
          root.walk([&](nodal::InterfaceAbiOp abi) {
            auto path = abi->getAttrOfType<StringAttr>("logical_path");
            if (!path || path.getValue().empty() ||
                !abiPaths.insert(path.getValue()).second) {
              error(abi, "NODAL-VERIFY-PROTOCOL-003",
                    "logical Interface ABI path is empty or duplicated");
              failedCheck = true;
            }
          });
          return failure(failedCheck);
        }

        LogicalResult verifyEffects(ModuleOp root) {
          bool failedCheck = false;
          root.walk([&](Operation *operation) {
            if (auto effect =
                    operation->getAttrOfType<StringAttr>("memory_effect")) {
              if (!llvm::is_contained(
                      {StringRef("read"), StringRef("write"),
                       StringRef("read_write"), StringRef("external")},
                      effect.getValue())) {
                error(operation, "NODAL-VERIFY-EFFECT-001",
                      "unknown memory or external effect contract");
                failedCheck = true;
              }
            }
          });
          return failure(failedCheck);
        }

        LogicalResult verifyAnalog(ModuleOp root) {
          bool failedCheck = false;
          root.walk([&](nodal::BridgeOp bridge) {
            auto kind = bridge->getAttrOfType<StringAttr>("kind");
            auto source = bridge->getAttrOfType<StringAttr>("source_domain");
            auto destination =
                bridge->getAttrOfType<StringAttr>("destination_domain");
            if (!kind || kind.getValue().empty() || !source ||
                source.getValue().empty() || !destination ||
                destination.getValue().empty()) {
              error(bridge, "NODAL-VERIFY-ANALOG-001",
                    "mixed-signal bridge must carry kind and both domains");
              failedCheck = true;
            }
          });
          return failure(failedCheck);
        }

        LogicalResult verifyTarget(ModuleOp root, StringRef profile) {
          if (profile == "source-neutral")
            return success();
          if (profile != "circt-hw-core")
            return error(root, "NODAL-VERIFY-TARGET-001",
                         "unknown target capability profile");
          bool failedCheck = false;
          root.walk([&](Operation *operation) {
            if (llvm::isa<nodal::TerminalOp, nodal::NodeOp, nodal::BranchOp,
                          nodal::AccessOp, nodal::BridgeOp>(operation)) {
              error(operation, "NODAL-VERIFY-TARGET-002",
                    "circt-hw-core does not support analog or mixed-signal operations");
              failedCheck = true;
            }
          });
          return failure(failedCheck);
        }

        LogicalResult runStage(ModuleOp module, Stage stage,
                               StringRef targetProfile) {
          switch (stage) {
          case Stage::Construction:
            return verifyConstruction(module);
          case Stage::Hierarchy:
            return verifyHierarchy(module);
          case Stage::Drivers:
            return verifyDrivers(module);
          case Stage::Types:
            return verifyTypes(module);
          case Stage::Parameters:
            return verifyParameters(module);
          case Stage::EnumFsm:
            return verifyEnumFsm(module);
          case Stage::Domains:
            return verifyDomains(module);
          case Stage::Protocols:
            return verifyProtocols(module);
          case Stage::Effects:
            return verifyEffects(module);
          case Stage::Analog:
            return verifyAnalog(module);
          case Stage::Target:
            return verifyTarget(module, targetProfile);
          }
          llvm_unreachable("unhandled stage");
        }

        LogicalResult runAllStages(ModuleOp module, StringRef targetProfile) {
          for (const StageDescriptor &descriptor : kStages)
            if (failed(runStage(module, descriptor.stage, targetProfile)))
              return failure();
          return success();
        }

        class NodalVerifyStagePass final
            : public PassWrapper<NodalVerifyStagePass, OperationPass<ModuleOp>> {
        public:
          MLIR_DEFINE_EXPLICIT_INTERNAL_INLINE_TYPE_ID(NodalVerifyStagePass)
          StringRef getArgument() const final { return "nodal-verify-stage"; }
          StringRef getDescription() const final {
            return "Run one named mandatory Nodal semantic verification stage";
          }
          Option<std::string> stage{*this, "stage", llvm::cl::desc("Stage name"),
                                    llvm::cl::init("construction")};
          Option<std::string> targetProfile{
              *this, "target-profile", llvm::cl::desc("Capability profile"),
              llvm::cl::init("source-neutral")};
          void runOnOperation() final {
            std::optional<Stage> selected = parseStage(stage);
            if (!selected) {
              error(getOperation(), "NODAL-VERIFY-PIPELINE-001",
                    Twine("unknown verification stage '") + stage + "'");
              signalPassFailure();
              return;
            }
            if (failed(runStage(getOperation(), *selected, targetProfile)))
              signalPassFailure();
          }
        };

        class NodalGateCheckPass final
            : public PassWrapper<NodalGateCheckPass, OperationPass<ModuleOp>> {
        public:
          MLIR_DEFINE_EXPLICIT_INTERNAL_INLINE_TYPE_ID(NodalGateCheckPass)
          StringRef getArgument() const final { return "nodal-gate-check"; }
          StringRef getDescription() const final {
            return "Run every mandatory Nodal semantic verification stage read-only";
          }
          Option<std::string> targetProfile{
              *this, "target-profile", llvm::cl::desc("Capability profile"),
              llvm::cl::init("source-neutral")};
          void runOnOperation() final {
            if (failed(runAllStages(getOperation(), targetProfile)))
              signalPassFailure();
          }
        };

        class NodalTransactionalGatePass final
            : public PassWrapper<NodalTransactionalGatePass,
                                 OperationPass<ModuleOp>> {
        public:
          MLIR_DEFINE_EXPLICIT_INTERNAL_INLINE_TYPE_ID(NodalTransactionalGatePass)
          StringRef getArgument() const final { return "nodal-transactional-gate"; }
          StringRef getDescription() const final {
            return "Verify, normalize acceptance metadata, reverify, and commit atomically";
          }
          Option<std::string> targetProfile{
              *this, "target-profile", llvm::cl::desc("Capability profile"),
              llvm::cl::init("source-neutral")};
          void runOnOperation() final {
            ModuleOp module = getOperation();
            OwningOpRef<ModuleOp> candidate(
                llvm::cast<ModuleOp>(module->clone()));
            if (failed(runAllStages(*candidate, targetProfile))) {
              error(module, "NODAL-VERIFY-TRANSACTION-001",
                    "candidate failed before acceptance; previous state retained");
              signalPassFailure();
              return;
            }

            Builder builder(&getContext());
            SmallVector<Attribute> stages;
            for (const StageDescriptor &descriptor : kStages)
              stages.push_back(builder.getStringAttr(descriptor.name));
            (*candidate)->setAttr("nodal.verification.pipeline",
                                  builder.getStringAttr(kPipeline));
            (*candidate)->setAttr("nodal.verification.target_profile",
                                  builder.getStringAttr(targetProfile));
            (*candidate)->setAttr("nodal.verification.stages",
                                  builder.getArrayAttr(stages));
            (*candidate)->setAttr("nodal.verification.accepted",
                                  builder.getBoolAttr(true));

            if (failed(runAllStages(*candidate, targetProfile))) {
              error(module, "NODAL-VERIFY-TRANSACTION-002",
                    "candidate failed reverification; previous state retained");
              signalPassFailure();
              return;
            }

            module->setAttrs((*candidate)->getAttrs());
            module.getBodyRegion().takeBody(candidate->getRegion(0));
          }
        };

        } // namespace

        std::unique_ptr<Pass> nodal::createNodalVerifyStagePass() {
          return std::make_unique<NodalVerifyStagePass>();
        }
        std::unique_ptr<Pass> nodal::createNodalGateCheckPass() {
          return std::make_unique<NodalGateCheckPass>();
        }
        std::unique_ptr<Pass> nodal::createNodalTransactionalGatePass() {
          return std::make_unique<NodalTransactionalGatePass>();
        }
        void nodal::registerNodalVerificationPasses() {
          PassRegistration<NodalVerifyStagePass>();
          PassRegistration<NodalGateCheckPass>();
          PassRegistration<NodalTransactionalGatePass>();
          PassPipelineRegistration<>(
              "nodal-gate-normalize",
              "Run the Increment 21 transactional Nodal semantic gate",
              [](OpPassManager &manager) {
                manager.addPass(nodal::createNodalTransactionalGatePass());
              });
        }
        ''',
    )

    replace_once(root, "core/compiler/lib/CMakeLists.txt",
                 "add_subdirectory(Dialect)\nadd_subdirectory(Support)\n",
                 "add_subdirectory(Dialect)\nadd_subdirectory(Transforms)\nadd_subdirectory(Support)\n")
    replace_once(root, "core/compiler/tools/nodalc/nodalc.cpp",
                 '#include "nodal/Dialect/Nodal/NodalDialect.h"\n',
                 '#include "nodal/Dialect/Nodal/NodalDialect.h"\n#include "nodal/Transforms/NodalVerification.h"\n')
    replace_once(root, "core/compiler/tools/nodalc/nodalc.cpp",
                 "  mlir::DialectRegistry registry;\n",
                 "  nodal::registerNodalVerificationPasses();\n\n  mlir::DialectRegistry registry;\n")
    replace_once(root, "core/compiler/tools/nodalc/CMakeLists.txt",
                 "    NodalDialect\n",
                 "    NodalDialect\n    NodalVerification\n")

    write(root, "core/compiler/test/IR/native-gate-valid.mlir", r'''
        module {
          "nodal.module"() <{metadata = {}, sym_name = "Top"}> ({
            "nodal.domain"() <{edge = "rising", metadata = {}, reset_policy = "sync", sym_name = "core"}> : () -> ()
            "nodal.port"() <{direction = "input", domain = @core, metadata = {}, sym_name = "input", type = !nodal.uint<8>}> : () -> ()
          }) : () -> ()
        }
    ''')
    write(root, "core/compiler/test/IR/native-gate-invalid-hierarchy.mlir", r'''
        module {
          "nodal.module"() <{metadata = {}, sym_name = "Top"}> ({
            "nodal.instance"() <{domain_bindings = {}, metadata = {}, module = @Missing, parameter_bindings = {}, sym_name = "missing"}> : () -> ()
          }) : () -> ()
        }
    ''')
    write(root, "core/compiler/test/IR/native-gate-invalid-driver.mlir", r'''
        module {
          "nodal.module"() <{metadata = {}, sym_name = "Top"}> ({
            %net = "nodal.resolved_net"() <{metadata = {}, name = "irq"}> : () -> !nodal.resolved<"push_pull", !nodal.bits<1>>
            %driver = "nodal.net_driver"(%net) <{driver_id = "Top.irq", metadata = {}}> : (!nodal.resolved<"push_pull", !nodal.bits<1>>) -> !nodal.driver<!nodal.bits<1>>
          }) : () -> ()
        }
    ''')
    write(root, "core/compiler/test/IR/native-gate-invalid-target.mlir", r'''
        module {
          "nodal.module"() <{metadata = {}, sym_name = "Top"}> ({
            %terminal = "nodal.terminal"() <{metadata = {}, name = "vin"}> : () -> !nodal.terminal<"electrical">
          }) : () -> ()
        }
    ''')

    append_once(root, "core/compiler/test/CMakeLists.txt",
                "nodal.native.transactional-gate", r'''
        add_test(
          NAME nodal.native.gate-check
          COMMAND nodalc --nodal-gate-check
            "${CMAKE_CURRENT_SOURCE_DIR}/IR/native-gate-valid.mlir"
        )

        add_test(
          NAME nodal.native.transactional-gate
          COMMAND nodalc --nodal-transactional-gate
            "${CMAKE_CURRENT_SOURCE_DIR}/IR/native-gate-valid.mlir"
        )
        set_tests_properties(nodal.native.transactional-gate PROPERTIES
          PASS_REGULAR_EXPRESSION "nodal.verification.accepted"
        )

        add_test(
          NAME nodal.native.gate-textual-pipeline
          COMMAND nodalc "--pass-pipeline=builtin.module(nodal-transactional-gate)"
            "${CMAKE_CURRENT_SOURCE_DIR}/IR/native-gate-valid.mlir"
        )
        set_tests_properties(nodal.native.gate-textual-pipeline PROPERTIES
          PASS_REGULAR_EXPRESSION "nodal.verification.pipeline"
        )

        add_test(
          NAME nodal.native.gate-invalid-hierarchy
          COMMAND nodalc --nodal-gate-check
            "${CMAKE_CURRENT_SOURCE_DIR}/IR/native-gate-invalid-hierarchy.mlir"
        )
        set_tests_properties(nodal.native.gate-invalid-hierarchy PROPERTIES WILL_FAIL TRUE)

        add_test(
          NAME nodal.native.gate-invalid-driver
          COMMAND nodalc --nodal-gate-check
            "${CMAKE_CURRENT_SOURCE_DIR}/IR/native-gate-invalid-driver.mlir"
        )
        set_tests_properties(nodal.native.gate-invalid-driver PROPERTIES WILL_FAIL TRUE)

        add_test(
          NAME nodal.native.gate-invalid-target
          COMMAND nodalc "--nodal-gate-check=target-profile=circt-hw-core"
            "${CMAKE_CURRENT_SOURCE_DIR}/IR/native-gate-invalid-target.mlir"
        )
        set_tests_properties(nodal.native.gate-invalid-target PROPERTIES WILL_FAIL TRUE)
    ''')

    write(root, "core/compiler/test/Unit/NativeVerificationTest.cpp", r'''
        #include "nodal/Transforms/NodalVerification.h"

        #include "mlir/IR/BuiltinOps.h"
        #include "mlir/IR/DialectRegistry.h"
        #include "mlir/Parser/Parser.h"
        #include "mlir/Pass/PassManager.h"
        #include "nodal/Dialect/Nodal/NodalDialect.h"

        #include "llvm/ADT/StringRef.h"
        #include "llvm/Support/raw_ostream.h"

        namespace {
        int fail(llvm::StringRef message) {
          llvm::errs() << "NODAL-NATIVE-VERIFICATION-TEST: " << message << '\n';
          return 1;
        }
        }

        int main() {
          mlir::DialectRegistry registry;
          registry.insert<nodal::NodalDialect>();
          mlir::MLIRContext context(registry);

          auto valid = mlir::parseSourceString<mlir::ModuleOp>(
              R"mlir(module {
                "nodal.module"() <{metadata = {}, sym_name = "Top"}> ({
                  "nodal.domain"() <{edge = "rising", metadata = {}, reset_policy = "sync", sym_name = "core"}> : () -> ()
                }) : () -> ()
              })mlir", &context);
          if (!valid)
            return fail("valid fixture did not parse");
          mlir::PassManager manager(&context);
          manager.addPass(nodal::createNodalTransactionalGatePass());
          if (mlir::failed(manager.run(*valid)))
            return fail("valid fixture failed the transactional gate");
          if (!valid->getAttrOfType<mlir::BoolAttr>("nodal.verification.accepted"))
            return fail("accepted fixture lacks acceptance metadata");

          auto invalid = mlir::parseSourceString<mlir::ModuleOp>(
              R"mlir(module {
                "nodal.module"() <{metadata = {}, sym_name = "Top"}> ({
                  "nodal.instance"() <{domain_bindings = {}, metadata = {}, module = @Missing, parameter_bindings = {}, sym_name = "bad"}> : () -> ()
                }) : () -> ()
              })mlir", &context);
          if (!invalid)
            return fail("invalid fixture did not parse locally");
          mlir::PassManager invalidManager(&context);
          invalidManager.addPass(nodal::createNodalTransactionalGatePass());
          if (mlir::succeeded(invalidManager.run(*invalid)))
            return fail("invalid fixture passed the transactional gate");
          if (invalid->getAttr("nodal.verification.accepted"))
            return fail("failed candidate published acceptance metadata");
          return 0;
        }
    ''')

    append_once(root, "core/compiler/test/Unit/CMakeLists.txt",
                "nodal-native-verification-unit-tests", r'''
        add_executable(nodal-native-verification-unit-tests
          NativeVerificationTest.cpp
        )
        llvm_update_compile_flags(nodal-native-verification-unit-tests)
        target_link_libraries(nodal-native-verification-unit-tests
          PRIVATE
            NodalVerification
            NodalDialect
            MLIRIR
            MLIRParser
            MLIRPass
            MLIRSupport
            LLVMSupport
        )
        add_test(
          NAME nodal.native.verification-unit
          COMMAND nodal-native-verification-unit-tests
        )
    ''')
    replace_once(root, "core/compiler/test/CMakeLists.txt",
                 "    nodal-dialect-unit-tests\n",
                 "    nodal-dialect-unit-tests\n    nodal-native-verification-unit-tests\n")

    write(root, "docs/design-gates/NodalNativeVerificationPipeline-DG-v1.0.md", r'''
        # Nodal native semantic verification pipeline design gate v1.0

        **Revision:** v1.0
        **Status:** Approved
        **Scope:** compiler-verification
        **Public API:** unchanged at 0.3
        **Approved authority:** standing Nodal increment implementation and merge authorization

        Increment 21 establishes mandatory staged native verification before a
        parsed Nodal module becomes accepted compiler state. The ordered stages
        cover construction, hierarchy, drivers/latches/cycles, types/shapes,
        parameters/generate/loops, enum/FSM, domains and crossings, protocols,
        effects, analog/mixed-signal structure, and target capability.

        `nodal-transactional-gate` runs every stage on a clone, publishes
        deterministic acceptance metadata, reverifies the clone, and replaces the
        input only after success. Failed candidates retain the previous input and
        cannot publish accepted-state metadata. Passes preserve no stale analyses.

        Cross-layer Scala diagnostic rendering, CIRCT conversion, scheduling,
        backend lowering, and HDL emission remain deferred.
    ''')
    write(root, "docs/implementation/increment21-native-verification-pipeline.md", r'''
        # Increment 21 — native parse and staged semantic verification

        The native compiler now exposes one named stage pass, a complete read-only
        gate, a transactional gate, and a textual `nodal-gate-normalize` pipeline.
        Local dialect verification remains the first layer; the new passes add
        whole-design hierarchy, driver, domain, protocol, target, and related
        checks that require module-wide context.

        Acceptance metadata records the exact pipeline version, ordered stages,
        target profile, and accepted state. The gate verifies both before and
        after metadata normalization and commits a cloned candidate only after all
        checks succeed.
    ''')

    manifest = {
        "increment": 21,
        "status": "implemented-awaiting-evidence",
        "public_api": "0.3",
        "pipeline": "nodal-native-gate-v1",
        "stages": [
            "construction", "hierarchy", "drivers-latches-cycles",
            "width-sign-shape-layout-storage", "parameters-generate-loops",
            "enum-fsm", "clock-reset-cdc-rdc", "protocol-pipeline",
            "memory-effects", "analog-mixed-signal", "target-capability"
        ],
        "evidence": {},
    }
    write(root, "tests/compiler/fixtures/increment21/manifest.json",
          json.dumps(manifest, indent=2) + "\n")

    write(root, "scripts/check_increment21.py", r'''
        #!/usr/bin/env python3
        from __future__ import annotations
        import argparse, json, re, sys
        from dataclasses import dataclass
        from pathlib import Path

        @dataclass(frozen=True)
        class Problem:
            code: str
            message: str
            def __str__(self): return f"{self.code}: {self.message}"

        EXPECTED_FILES = (
            "core/compiler/include/nodal/Transforms/NodalVerification.h",
            "core/compiler/lib/Transforms/CMakeLists.txt",
            "core/compiler/lib/Transforms/NodalVerification.cpp",
            "core/compiler/test/Unit/NativeVerificationTest.cpp",
            "docs/design-gates/NodalNativeVerificationPipeline-DG-v1.0.md",
            "docs/implementation/increment21-native-verification-pipeline.md",
            "tests/compiler/fixtures/increment21/manifest.json",
            "tests/compiler/test_increment21.py",
            "scripts/check_increment21.py",
            ".github/workflows/increment-21-native-verification-pipeline.yml",
        )
        TEMPORARY_FILES = (
            "scripts/materialize_increment21.py",
            ".github/workflows/increment-21-finalize.yml",
            ".github/workflows/increment-21-supervisor.yml",
        )

        def read(path, problems, code):
            try: return path.read_text(encoding="utf-8")
            except OSError as exc:
                problems.append(Problem(code, f"cannot read {path}: {exc}")); return ""

        def require(text, fragments, problems, code, subject):
            for fragment in fragments:
                if fragment not in text:
                    problems.append(Problem(code, f"{subject} lacks: {fragment}"))

        def revision(text):
            match = re.search(r"^\*\*Revision:\*\* ([0-9.]+)$", text, re.M)
            return tuple(map(int, match.group(1).split('.'))) if match else ()

        def check_repository(root):
            root = root.resolve(); problems = []
            for relative in EXPECTED_FILES:
                if not (root / relative).is_file():
                    problems.append(Problem("NODAL-INC21-001", f"missing file: {relative}"))
            for relative in TEMPORARY_FILES:
                if (root / relative).exists():
                    problems.append(Problem("NODAL-INC21-002", f"temporary file remains: {relative}"))
            implementation = read(root / "core/compiler/lib/Transforms/NodalVerification.cpp", problems, "NODAL-INC21-003")
            driver = read(root / "core/compiler/tools/nodalc/nodalc.cpp", problems, "NODAL-INC21-004")
            cmake = read(root / "core/compiler/tools/nodalc/CMakeLists.txt", problems, "NODAL-INC21-004")
            workflow = read(root / ".github/workflows/increment-21-native-verification-pipeline.yml", problems, "NODAL-INC21-005")
            roadmap = read(root / "docs/roadmap/nodal-development-todo.md", problems, "NODAL-INC21-006")
            require(implementation, (
                "nodal-verify-stage", "nodal-gate-check", "nodal-transactional-gate",
                "nodal-gate-normalize", "module->clone()", "takeBody",
                "NODAL-VERIFY-CONSTRUCTION-001", "NODAL-VERIFY-HIERARCHY-001",
                "NODAL-VERIFY-DRIVER-001", "NODAL-VERIFY-SHAPE-001",
                "NODAL-VERIFY-LOOP-001", "NODAL-VERIFY-FSM-001",
                "NODAL-VERIFY-DOMAIN-001", "NODAL-VERIFY-PROTOCOL-001",
                "NODAL-VERIFY-EFFECT-001", "NODAL-VERIFY-ANALOG-001",
                "NODAL-VERIFY-TARGET-001", "nodal.verification.accepted",
            ), problems, "NODAL-INC21-003", "verification implementation")
            require(driver, ("registerNodalVerificationPasses",), problems, "NODAL-INC21-004", "nodalc")
            require(cmake, ("NodalVerification",), problems, "NODAL-INC21-004", "nodalc CMake")
            require(workflow, (
                "increment-21/native-verification-pipeline", "check_increment21.py",
                "./nodal core native", "nodal-gate-normalize", "permissions:\n  contents: read",
            ), problems, "NODAL-INC21-005", "workflow")
            if "contents: write" in workflow:
                problems.append(Problem("NODAL-INC21-005", "permanent workflow must be read-only"))
            manifest_path = root / "tests/compiler/fixtures/increment21/manifest.json"
            try: manifest = json.loads(read(manifest_path, problems, "NODAL-INC21-006"))
            except json.JSONDecodeError as exc:
                problems.append(Problem("NODAL-INC21-006", f"invalid manifest: {exc}")); manifest = {}
            if manifest.get("increment") != 21 or manifest.get("public_api") != "0.3":
                problems.append(Problem("NODAL-INC21-006", "manifest identity mismatch"))
            status = manifest.get("status"); evidence = manifest.get("evidence", {})
            title = "Increment 21 — Native parse, staged semantic verification, and pass pipeline"
            unchecked = f"- [ ] **{title}**" in roadmap; checked = f"- [x] **{title}**" in roadmap
            if status == "implemented-awaiting-evidence":
                if not unchecked or revision(roadmap) < (1, 24):
                    problems.append(Problem("NODAL-INC21-006", "pre-evidence roadmap state invalid"))
            elif status in {"validated-native-verification-pipeline", "validated-staged-semantic-pipeline"}:
                if not checked or revision(roadmap) < (1, 25):
                    problems.append(Problem("NODAL-INC21-006", "validated roadmap state invalid"))
                for field in ("pull_request", "dedicated_run", "core_ci_run"):
                    if not isinstance(evidence.get(field), int):
                        problems.append(Problem("NODAL-INC21-006", f"missing evidence: {field}"))
            else: problems.append(Problem("NODAL-INC21-006", f"unexpected status: {status!r}"))
            return problems

        def main(argv=None):
            parser = argparse.ArgumentParser(); parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1]); args = parser.parse_args(argv)
            problems = check_repository(args.root)
            for problem in problems: print(problem, file=sys.stderr)
            if problems: return 1
            print("Increment 21 check passed"); return 0
        if __name__ == "__main__": raise SystemExit(main())
    ''')

    write(root, "tests/compiler/test_increment21.py", r'''
        from __future__ import annotations
        import importlib.util, shutil, sys, tempfile, unittest
        from pathlib import Path
        ROOT = Path(__file__).resolve().parents[2]
        SPEC = importlib.util.spec_from_file_location("check_increment21", ROOT / "scripts/check_increment21.py")
        assert SPEC and SPEC.loader
        CHECKER = importlib.util.module_from_spec(SPEC); sys.modules[SPEC.name] = CHECKER; SPEC.loader.exec_module(CHECKER)
        SUPPORT_FILES = ("core/compiler/tools/nodalc/nodalc.cpp", "core/compiler/tools/nodalc/CMakeLists.txt", "docs/roadmap/nodal-development-todo.md")
        class Increment21Tests(unittest.TestCase):
            def repository(self):
                temporary = tempfile.TemporaryDirectory(); root = Path(temporary.name)
                for relative in dict.fromkeys(CHECKER.EXPECTED_FILES + SUPPORT_FILES):
                    destination = root / relative; destination.parent.mkdir(parents=True, exist_ok=True); shutil.copy2(ROOT / relative, destination)
                return temporary, root
            def codes(self, root): return {problem.code for problem in CHECKER.check_repository(root)}
            def test_repository(self): self.assertEqual(CHECKER.check_repository(ROOT), [])
            def test_rejects_nontransactional_gate(self):
                temporary, root = self.repository(); self.addCleanup(temporary.cleanup)
                path = root / "core/compiler/lib/Transforms/NodalVerification.cpp"; path.write_text(path.read_text().replace("module->clone()", "module.getOperation()")); self.assertIn("NODAL-INC21-003", self.codes(root))
            def test_rejects_writable_workflow(self):
                temporary, root = self.repository(); self.addCleanup(temporary.cleanup)
                path = root / ".github/workflows/increment-21-native-verification-pipeline.yml"; path.write_text(path.read_text().replace("contents: read", "contents: write")); self.assertIn("NODAL-INC21-005", self.codes(root))
        if __name__ == "__main__": unittest.main()
    ''')

    write(root, ".github/workflows/increment-21-native-verification-pipeline.yml", r'''
        name: Increment 21 Native Verification Pipeline
        on:
          push:
            branches: [increment/21-native-verification-pipeline]
          pull_request:
            branches: [dev]
          workflow_dispatch:
        permissions:
          contents: read
        concurrency:
          group: increment-21-${{ github.workflow }}-${{ github.ref }}
          cancel-in-progress: true
        jobs:
          native-verification:
            name: increment-21/native-verification-pipeline
            runs-on: ubuntu-24.04
            timeout-minutes: 120
            steps:
              - uses: actions/checkout@v6
                with: {fetch-depth: 0}
              - uses: actions/cache@v5
                with:
                  path: ~/.cache/nodal/downloads
                  key: nodal-native-download-${{ runner.os }}-${{ hashFiles('toolchains/lock.json', 'toolchains/checksums/*.sha256') }}
              - name: Install locked toolchains
                run: |
                  ./nodal bootstrap --mode prebuilt --prefix "${RUNNER_TEMP}/nodal-native-toolchain"
                  ./nodal style bootstrap --prefix "${RUNNER_TEMP}/nodal-lint-toolchain"
              - name: Validate contracts
                run: |
                  python3 scripts/check_increment20.py
                  python3 scripts/check_increment21.py
                  python3 -m unittest discover -s tests/compiler -p 'test_*.py'
                  ./nodal check --contracts-only --online-toolchain \
                    --lint-toolchain "${RUNNER_TEMP}/nodal-lint-toolchain" --base-ref origin/dev
                  git diff --check
              - name: Build and test
                run: |
                  ./nodal core scala
                  ./nodal core native --toolchain "${RUNNER_TEMP}/nodal-native-toolchain" \
                    --lint-toolchain "${RUNNER_TEMP}/nodal-lint-toolchain"
              - name: Prove textual gate pipelines
                run: |
                  compiler=out/native/release/bin/nodalc
                  "${compiler}" --nodal-transactional-gate core/compiler/test/IR/native-gate-valid.mlir | tee /tmp/gate.mlir
                  grep -F 'nodal.verification.accepted' /tmp/gate.mlir
                  "${compiler}" "--pass-pipeline=builtin.module(nodal-transactional-gate)" core/compiler/test/IR/native-gate-valid.mlir | grep -F 'nodal.verification.pipeline'
                  if "${compiler}" --nodal-gate-check core/compiler/test/IR/native-gate-invalid-hierarchy.mlir >/tmp/invalid.log 2>&1; then exit 1; fi
                  grep -F 'NODAL-VERIFY-HIERARCHY-001' /tmp/invalid.log
    ''')


if __name__ == "__main__": main()
