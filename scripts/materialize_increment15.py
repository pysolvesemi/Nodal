#!/usr/bin/env python3
"""Materialize and close Increment 15's unified public API v0.3 freeze."""

from __future__ import annotations

import argparse
import json
import textwrap
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[1]
BRANCH = "increment/15-unified-public-api-v0.3-freeze"


def normalized(text: str) -> str:
    return textwrap.dedent(text).lstrip("\n").rstrip() + "\n"


def write_text(relative: str, content: str) -> None:
    path = ROOT / relative
    path.parent.mkdir(parents=True, exist_ok=True)
    rendered = normalized(content)
    if not path.exists() or path.read_text(encoding="utf-8") != rendered:
        path.write_text(rendered, encoding="utf-8")


def write_json(relative: str, value: dict[str, Any]) -> None:
    path = ROOT / relative
    path.parent.mkdir(parents=True, exist_ok=True)
    rendered = json.dumps(value, indent=2) + "\n"
    if not path.exists() or path.read_text(encoding="utf-8") != rendered:
        path.write_text(rendered, encoding="utf-8")


def replace_once_or_present(path: Path, old: str, new: str) -> None:
    text = path.read_text(encoding="utf-8")
    if new in text:
        return
    if text.count(old) != 1:
        raise RuntimeError(f"expected one replacement anchor in {path}: {old!r}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


def remove_once_or_absent(path: Path, block: str) -> None:
    text = path.read_text(encoding="utf-8")
    if block not in text:
        return
    if text.count(block) != 1:
        raise RuntimeError(f"expected one removable block in {path}")
    path.write_text(text.replace(block, "", 1), encoding="utf-8")


def diagnostics() -> list[dict[str, str]]:
    rows = [
        (
            "NODAL-NUM-015",
            "mixed-signedness-arithmetic",
            "typing",
            "UInt and SInt arithmetic requires an explicit signedness decision.",
            "Use toSigned, toUnsigned, or a reinterpretation operation deliberately.",
        ),
        (
            "NODAL-UNIT-015",
            "physical-dimension-mismatch",
            "typing",
            "Physical quantities with incompatible dimensions cannot be combined.",
            "Convert through an explicit dimensioned model or correct the expression.",
        ),
        (
            "NODAL-IFACE-015",
            "interface-stored-as-data",
            "typing",
            "Interface connectivity cannot be stored in a register or memory.",
            "Store a directionless Struct payload instead.",
        ),
        (
            "NODAL-PAYLOAD-015",
            "interface-used-as-payload",
            "typing",
            "An Interface endpoint cannot be used as a Valid or Stream payload.",
            "Define a storable Struct payload and keep connectivity separate.",
        ),
        (
            "NODAL-MONITOR-015",
            "monitor-drive",
            "typing",
            "A monitor role cannot drive an interface member.",
            "Use a driving role or keep the access observational.",
        ),
        (
            "NODAL-INVERT-015",
            "invalid-role-inversion",
            "typing",
            "The selected role has no legal automatic inverse.",
            "Declare an explicit complementary role or adapter module.",
        ),
        (
            "NODAL-INOUT-015",
            "illegal-open-drain-drive",
            "typing",
            "Open-drain endpoints cannot drive arbitrary values.",
            "Use driveLow(enable) and highZ().",
        ),
        (
            "NODAL-PROTOCOL-015",
            "protocol-or-interface-mismatch",
            "typing",
            "Exact connection requires one interface identity and complementary roles.",
            "Insert an explicit adapter module for protocol conversion.",
        ),
        (
            "NODAL-BACKEND-015",
            "unapproved-systemverilog-backend",
            "typing",
            "Backend.SystemVerilog is not part of public API v0.3.",
            "Use Backend.Auto or Backend.Verilog; await the separately gated backend.",
        ),
        (
            "NODAL-MIGRATION-015",
            "ordinary-always-removed",
            "typing",
            "Ordinary synchronous always(event) is not in the v0.3 public subset.",
            "Use ClockDomain, Reg or RegNext, and when/elsewhen/otherwise.",
        ),
        (
            "NODAL-DOMAIN-015",
            "pipeline-without-domain",
            "construction",
            "A pipeline region containing dynamic hardware has no lexical clock domain.",
            "Declare or inherit one ClockDomain before creating the region.",
        ),
        (
            "NODAL-CDC-015",
            "cross-domain-pipeline-capture",
            "construction",
            "A pipeline transform captures a value from another domain without a crossing.",
            "Use an explicit CDC or RDC primitive before entering the transaction.",
        ),
        (
            "NODAL-PIPE-015",
            "unrelated-live-pipeline-capture",
            "construction",
            "A dynamic value used by a transform did not enter through its transaction.",
            "Add the value to the transaction payload or make it static.",
        ),
        (
            "NODAL-LATENCY-015",
            "unbounded-published-auto-latency",
            "scheduling",
            "A published fixed-rate boundary cannot expose unconstrained automatic latency.",
            "Use Latency.Exact, Latency.Range, or keep the region internal.",
        ),
        (
            "NODAL-EXTERNAL-015",
            "external-model-or-effect-missing",
            "construction",
            "An external operation lacks required latency, effect, domain, or model evidence.",
            "Provide a complete ExternalContract or VariableLatencyContract.",
        ),
        (
            "NODAL-DRIVER-015",
            "multiple-ordinary-drivers",
            "construction",
            "An ordinary signal or state value has multiple unrelated drivers.",
            "Use one priority update root or a declared resolved digital net.",
        ),
        (
            "NODAL-RESOLVE-015",
            "unsupported-internal-resolution",
            "construction",
            "The selected backend profile cannot implement this internal resolved net.",
            "Use a boundary inout, split carrier, or technology-mapped adapter.",
        ),
        (
            "NODAL-BRIDGE-015",
            "implicit-mixed-signal-conversion",
            "construction",
            "Digital, conservative, and signal-flow values cannot convert implicitly.",
            "Use an explicit bridge with timing, threshold, quantization, and model metadata.",
        ),
        (
            "NODAL-LAYOUT-015",
            "backend-interface-layout-mismatch",
            "backend-selection",
            "The requested interface layout is unsupported by the selected backend.",
            "Use PortableFlattened or select a future separately approved capability.",
        ),
        (
            "NODAL-FLATTEN-015",
            "flattened-interface-name-collision",
            "backend-selection",
            "Two logical interface members map to the same emitted port name.",
            "Choose a unique flatten prefix or revise the logical member names.",
        ),
        (
            "NODAL-GENERATE-015",
            "unbounded-or-runtime-hardware-loop",
            "construction",
            "A target-visible loop has no static or bounded symbolic limit.",
            "Use Scala elaboration, generate, or LoopBound.Symbolic with a maximum.",
        ),
        (
            "NODAL-FSM-015",
            "invalid-fsm-contract",
            "construction",
            "An FSM has invalid encoding, transition, recursion, or illegal-state metadata.",
            "Provide canonical enum encoding and a finite verified statechart contract.",
        ),
    ]
    return [
        {
            "code": code,
            "name": name,
            "phase": phase,
            "severity": "error",
            "message": message,
            "suggestion": suggestion,
        }
        for code, name, phase, message, suggestion in rows
    ]


def public_manifest() -> dict[str, Any]:
    return {
        "schema": 1,
        "api_version": "0.3",
        "status": "frozen",
        "supersedes": "0.2",
        "historical_baselines": [
            "core/scala/api/public-api-v0.1.json",
            "core/scala/api/public-api-v0.2.json",
        ],
        "default_import": "import nodal.*",
        "source_package": "nodal",
        "design_gate": "docs/design-gates/NodalCoreSemanticsPipelineApi-DG-v0.3.md",
        "diagnostics_manifest": "core/scala/api/public-api-diagnostics-v0.3.json",
        "fixture_manifest": "tests/api/fixtures/increment15/manifest.json",
        "migration_notes": [
            "docs/migrations/public-api-v0.1-to-v0.3.md",
            "docs/migrations/public-api-v0.2-to-v0.3.md",
        ],
        "language_reference": "docs/language-reference/public-api-v0.3.md",
        "binding_architecture": [
            "docs/architecture/0007-implicit-clock-reset-domains.md",
            "docs/architecture/0008-automatic-pipeline-architecture.md",
            "docs/architecture/0009-core-semantic-contracts.md",
            "docs/architecture/0010-digital-verilog-open-source-verification.md",
            "docs/architecture/0015-native-scala-enum-and-hierarchical-fsm.md",
            "docs/architecture/0016-signed-types-and-staged-loops.md",
            "docs/architecture/0017-semantic-multidimensional-values-and-target-layouts.md",
            "docs/architecture/0018-expression-materialization-and-semantic-naming.md",
            "docs/architecture/0019-mandatory-pre-emission-hardware-quality-gates.md",
            "docs/architecture/0021-unified-struct-interface-role-and-inout-architecture.md",
        ],
        "candidate_inputs": [
            "docs/roadmap/core-semantics-api-v0.3-surface.json",
            "docs/roadmap/automatic-pipeline-api-v0.3-surface.json",
            "docs/roadmap/interface-role-inout-ams-v0.1-surface.json",
            "docs/roadmap/signed-loop-api-v0.3-surface.json",
            "docs/roadmap/shaped-values-naming-quality-v0.3-surface.json",
            "docs/roadmap/enum-fsm-api-v0.3-surface.json",
            "docs/roadmap/digital-backend-v0.3-surface.json",
        ],
        "source_files": [
            "core/scala/api/src/nodal/CandidateApi.scala",
            "core/scala/api/src/nodal/CoreSemanticsCandidateApi.scala",
            "core/scala/api/src/nodal/PipelineInterfaceCandidateApi.scala",
            "core/scala/api/src/nodal/CompilerApi.scala",
        ],
        "value_stages": {
            "elaboration": "ordinary Scala values and Scala control flow",
            "symbolic": "Param, Dimension, GenerateIndex, and symbolic expressions",
            "dynamic": "ports, wires, registers, memory data, protocol payloads, and sampled values",
            "target_replication": "generate(count)",
            "bounded_same_cycle_iteration": "loop(LoopBound)",
        },
        "numeric": {
            "types": ["Bool", "Bits", "UInt", "SInt", "Integer", "Real"],
            "lossless_by_default": True,
            "implicit_narrowing": False,
            "implicit_signedness_change": False,
            "explicit_resize": ["extend", "truncate", "wrap", "saturate", "resizeChecked"],
            "explicit_signedness": [
                "toSigned",
                "toUnsigned",
                "reinterpretSigned",
                "reinterpretUnsigned",
            ],
        },
        "shaped_values": {
            "structural_type": "Vec",
            "dimensions": "Int | Expr[Integer]",
            "operations": ["at", "flatten", "reshape", "map", "zip", "reduce"],
            "storage_type": "Mem",
            "portable_layout": "TargetLayout.PortableVerilogFlat",
            "future_layout_candidates": [
                "TargetLayout.SystemVerilogUnpacked",
                "TargetLayout.SystemVerilogPacked",
            ],
        },
        "values_and_connectivity": {
            "value_aggregate": "Struct",
            "value_is_directionless_and_storable": True,
            "connectivity_aggregate": "Interface",
            "interface_is_storable": False,
            "role_type": "Role",
            "roles": [
                "master",
                "slave",
                "source",
                "sink",
                "initiator",
                "target",
                "controller",
                "peripheral",
                "device",
                "environment",
                "monitor",
            ],
            "direct_connection": "connectExact",
            "adaptation": "explicit user-authored Module boundary",
            "implicit_adaptation": False,
            "interface_arrays": "interfaceArray",
            "supported_layout": "InterfaceLayout.PortableFlattened",
            "future_native_layout_candidate": "InterfaceLayout.FutureSystemVerilogNative",
            "removed_candidate_symbols": ["Aggregate", "AggregateField", "Flow", "via", "viewAs"],
        },
        "protocols": {
            "plain_fixed_rate": "Txn",
            "valid_only": "Valid",
            "elastic": "Stream",
            "implicit_conversion": False,
        },
        "pipeline": {
            "entry_points": ["pipe", "delay"],
            "latency": ["Latency.Auto", "Latency.Exact", "Latency.Range"],
            "throughput": "Throughput.EveryCycle",
            "ready_path": [
                "ReadyPath.Auto",
                "ReadyPath.Combinational",
                "ReadyPath.Registered",
            ],
            "constraints": ["stage", "sameStage"],
            "parameter_envelope": "ParameterEnvelope",
            "inspection": "inspectSchedule",
            "dynamic_values_must_enter_transaction": True,
            "single_domain": True,
            "general_hls": False,
        },
        "memory_and_external": {
            "memory": "Mem",
            "memory_policy": ["ReadUnderWrite", "MemoryOrdering"],
            "external_operation": "ExternalOp",
            "fixed_contract": "ExternalContract",
            "variable_contract": "VariableLatencyContract",
            "effect": "Effect",
            "model_availability": "ModelAvailability",
        },
        "quantities": {
            "type": "Quantity",
            "initial_dimensions": ["VoltageDimension", "CurrentDimension", "ResistanceDimension", "TimeDimension"],
            "dimension_mismatch_is_type_error": True,
        },
        "enum_and_fsm": {
            "enum_derivation": "derives HwEnum",
            "canonical_encoding": "enumEncoding",
            "safe_decode": "decodeEnum",
            "fsm": ["FsmDefinition", "fsm"],
            "encoding": "FsmEncoding",
            "transition": "TransitionMode",
            "illegal_state": "IllegalStatePolicy",
            "bounded_call_stack": True,
        },
        "clock_reset": {
            "retains_api_version": "0.2",
            "architecture": "implicit-local-domain-explicit-crossing-explicit-emitted-hdl",
            "ordinary_always_allowed": False,
            "state": ["Reg", "Reg.uninitialized", "RegNext", "RegNext.uninitialized"],
            "updates": ["when", "elsewhen", "otherwise"],
            "crossings": ["Cdc", "Rdc", "ResetController", "ClockGate", "ClockMux"],
        },
        "digital_inout": {
            "type": "DigitalInout",
            "separate_read_and_drive": True,
            "drive_modes": ["pushPull", "openDrain", "openSource", "readOnly", "passThrough"],
            "release": "highZ",
            "portable_carrier": "split",
            "hierarchy": "passThrough",
            "pad": "padAdapter",
            "silent_internal_mux_rewrite": False,
        },
        "ams_connectivity": {
            "terminal": "Terminal",
            "views": ["connectView", "senseView", "contributeView", "terminalMonitorView"],
            "signal_flow": "AnalogSignal",
            "bridges": ["MixedSignalBridge", "ConservativeSignalBridge"],
            "implicit_conversion": False,
        },
        "quality": {
            "temporary_policy": "TemporaryPolicy",
            "naming_policy": "NamingPolicy",
            "check_profile": "CheckProfile",
            "waiver": "CheckWaiver",
            "aggregate": "EmitQuality",
        },
        "compiler": {
            "entry_point": "Nodal.emit",
            "options": "EmitOptions",
            "result": "Emission",
            "report": "DesignReport",
            "design_kinds": ["DigitalOnly", "AnalogOnly", "MixedSignal", "Unsupported"],
            "backends": ["Backend.Auto", "Backend.Verilog", "Backend.VerilogA", "Backend.VerilogAMS"],
            "default_backend": "Backend.Auto",
            "auto_selection": {
                "DigitalOnly": "Backend.Verilog",
                "AnalogOnly": "Backend.VerilogA",
                "MixedSignal": "Backend.VerilogAMS",
            },
            "digital_profiles": [
                "DigitalProfile.Synthesis",
                "DigitalProfile.Simulation",
                "DigitalProfile.Formal",
            ],
            "portable_interface_abi": "InterfaceAbiEntry",
            "source_map": "SourceMapEntry",
            "schedule_evidence": "ScheduleInspection",
            "systemverilog_backend_public": False,
        },
        "library_author_subset": {
            "allowed": [
                "value and quantity types",
                "Struct",
                "Interface and Role",
                "Valid and Stream",
                "clock/reset domains and explicit crossings",
                "digital inout and conservative AMS connectivity",
                "pipeline, memory, external-operation, enum, and FSM contracts",
            ],
            "excluded": [
                "Backend",
                "EmitOptions",
                "Emission",
                "DesignReport",
                "Nodal.emit",
                "nodal.lowlevel.*",
                "nodal.internal.*",
                "nodal.bootstrap.*",
            ],
        },
        "compatibility": {
            "source_compatibility": "required across 0.3.x for listed frozen symbols",
            "binary_compatibility": "not promised before 1.0",
            "v0.1_analog_and_parameter_forms": "retained",
            "v0.2_clock_reset_forms": "retained",
            "v0.2_default_backend": "pin Backend.VerilogAMS to preserve the prior default",
            "v0.3_default_backend": "Backend.Auto",
            "breaking_change": "requires a new approved design gate and migration note",
            "additive_change": "allowed only when overload resolution and semantics remain unambiguous",
        },
        "implementation_status": {
            "surface_compiles": True,
            "scala_type_negative_fixtures_execute": True,
            "semantic_negative_fixtures_are_contract_only": True,
            "elaboration_implemented": False,
            "scheduler_implemented": False,
            "interface_ir_implemented": False,
            "resolution_topology_implemented": False,
            "digital_backend_implemented": False,
            "ams_backend_implemented": False,
            "simulator_implemented": False,
        },
        "roadmap_revision": "1.19",
        "next_implementation_increment": 16,
    }


def fixture_manifest() -> dict[str, Any]:
    type_negative = [
        ("mixed-signedness.scala", "NODAL-NUM-015"),
        ("quantity-mismatch.scala", "NODAL-UNIT-015"),
        ("interface-storage.scala", "NODAL-IFACE-015"),
        ("interface-payload.scala", "NODAL-PAYLOAD-015"),
        ("monitor-drive.scala", "NODAL-MONITOR-015"),
        ("monitor-inversion.scala", "NODAL-INVERT-015"),
        ("open-drain-drive.scala", "NODAL-INOUT-015"),
        ("protocol-mismatch.scala", "NODAL-PROTOCOL-015"),
        ("systemverilog-backend.scala", "NODAL-BACKEND-015"),
        ("ordinary-always.scala", "NODAL-MIGRATION-015"),
    ]
    semantic = [
        ("pipeline-without-domain.scala", "NODAL-DOMAIN-015"),
        ("cross-domain-pipeline.scala", "NODAL-CDC-015"),
        ("unrelated-live-capture.scala", "NODAL-PIPE-015"),
        ("unbounded-published-latency.scala", "NODAL-LATENCY-015"),
        ("external-contract-missing.scala", "NODAL-EXTERNAL-015"),
        ("multiple-drivers.scala", "NODAL-DRIVER-015"),
        ("unsupported-resolution.scala", "NODAL-RESOLVE-015"),
        ("implicit-bridge.scala", "NODAL-BRIDGE-015"),
        ("layout-backend-mismatch.scala", "NODAL-LAYOUT-015"),
        ("flattening-collision.scala", "NODAL-FLATTEN-015"),
        ("unbounded-hardware-loop.scala", "NODAL-GENERATE-015"),
        ("invalid-fsm-contract.scala", "NODAL-FSM-015"),
    ]
    return {
        "schema": 1,
        "increment": 15,
        "api_version": "0.3",
        "status": "preflight-freeze",
        "validation": {"pull_request": None, "dedicated_workflow_run": None},
        "positive_modules": [
            "examples.publicApiV03External.compile",
            "examples.publicApiV03.compile",
            "examples.publicApiV03Migration.compile",
        ],
        "scala_type_negative": [
            {
                "path": f"tests/api/fixtures/increment15/negative/{name}",
                "code": code,
                "mode": "scala-type-rejected",
            }
            for name, code in type_negative
        ],
        "semantic_negative": [
            {
                "path": f"tests/api/fixtures/increment15/semantic/{name}",
                "code": code,
                "mode": "semantic-contract",
            }
            for name, code in semantic
        ],
        "migration_modules": [
            "v0.1 analog/parameter/hierarchy source retained",
            "v0.2 clock/reset/domain/crossing source retained",
            "v0.2 backend default preserved by explicit Backend.VerilogAMS",
            "v0.3 default selects Backend.Auto",
        ],
        "frontend_behavior_inert": True,
        "scheduler_behavior_inert": True,
        "interface_ir_behavior_inert": True,
        "resolution_topology_behavior_inert": True,
        "backend_behavior_inert": True,
        "simulator_behavior_inert": True,
        "next_increment": 16,
    }


COMPILER_API = r'''
package nodal

/** Public HDL backend selection frozen by Nodal public API v0.3. */
enum Backend:
  case Auto, Verilog
  case VerilogA, VerilogAMS

/** Stable design classification reported independently of backend spelling. */
enum DesignKind:
  case DigitalOnly, AnalogOnly, MixedSignal, Unsupported

/** Explicit portable-Verilog intent. These profiles do not change source semantics. */
enum DigitalProfile:
  case Synthesis, Simulation, Formal

/** Backend-neutral emission options. Additive fields may be introduced compatibly. */
final case class EmitOptions(
    backend: Backend = Backend.Auto,
    digitalProfile: DigitalProfile = DigitalProfile.Synthesis,
    interfaceLayout: InterfaceLayoutPolicy = InterfaceLayoutPolicy(
      InterfaceLayout.PortableFlattened
    ),
    quality: EmitQuality = EmitQuality()
)

/** One generated source file, held in memory until the caller chooses where to write it. */
final case class EmittedFile(path: String, content: String)

/** Stable source span used by reports and later diagnostics. */
final case class SourceSpan(
    path: String,
    line: Int,
    column: Int,
    endLine: Int,
    endColumn: Int
)

/** Mapping from one semantic path to its originating Scala span. */
final case class SourceMapEntry(semanticPath: String, source: SourceSpan)

/** Logical Interface ABI entry, independent of flattened or future native target layout. */
final case class InterfaceAbiEntry(
    logicalPath: String,
    emittedPath: String,
    role: String,
    access: String,
    dataType: String,
    domain: String
)

/** Deterministic classification, ABI, source-map, and schedule evidence. */
final case class DesignReport(
    designKind: DesignKind = DesignKind.Unsupported,
    selectedBackend: Backend = Backend.Auto,
    digitalProfile: Option[DigitalProfile] = None,
    interfaceAbi: Vector[InterfaceAbiEntry] = Vector.empty,
    sourceMap: Vector[SourceMapEntry] = Vector.empty,
    schedules: Vector[ScheduleInspection] = Vector.empty
)

/** Deterministically ordered generated sources and their semantic report. */
final case class Emission(
    files: Vector[EmittedFile],
    report: DesignReport = DesignReport()
)

/** Stable public compiler entry point. Its implementation is intentionally deferred. */
object Nodal:
  def emit(top: => Module, options: EmitOptions = EmitOptions()): Emission =
    CandidateRuntime.statement(() => top, options)
    Emission(Vector.empty)
'''

GATE = r'''
# NodalCoreSemanticsPipelineApi-DG v0.3

**Status:** Approved  
**Scope:** public-api  
**API version:** 0.3  
**Decision:** Freeze unified core semantics, Interface/Role/inout, automatic pipeline, and backend selection  
**Approved by:** Repository owner instruction to implement Increment 15 on 2026-08-23

## Decision

Nodal public API v0.3 is frozen by this gate. The authoritative inventories are:

- [`public-api-v0.3.json`](../../core/scala/api/public-api-v0.3.json);
- [`public-api-diagnostics-v0.3.json`](../../core/scala/api/public-api-diagnostics-v0.3.json);
- [`tests/api/fixtures/increment15/manifest.json`](../../tests/api/fixtures/increment15/manifest.json);
- [`public-api-v0.3.md`](../language-reference/public-api-v0.3.md);
- the v0.1-to-v0.3 and v0.2-to-v0.3 migration notes.

The binding rule is:

> **Values are explicit and lossless, connectivity is role-typed, state belongs to a domain, pipeline intent is transactional, and target selection never changes source semantics.**

This gate freezes source forms, type distinctions, diagnostics, compatibility rules, reporting shapes,
and semantic obligations. It does not claim that elaboration, scheduling, Interface IR, resolution,
topology, MLIR lowering, HDL generation, simulation, synthesis, formal execution, or timing closure is
implemented.

The Increment 13 and Increment 14 candidate gates remain historical evidence. Where candidates differ,
this gate and `public-api-v0.3.json` are authoritative.

## Value stages, numbers, shapes, and storage

Ordinary Scala values and Scala `for` loops are elaboration-only. Target-visible symbolic replication is
`generate(count)`. Bounded same-cycle hardware iteration is `loop(LoopBound.Static(...))` or
`loop(LoopBound.Symbolic(..., maximum = ...))`. Runtime-unbounded hardware loops are rejected.

The finite-width value types are `Bool`, `Bits`, `UInt`, and `SInt`. Arithmetic is lossless by default.
There is no implicit narrowing or signedness conversion. The frozen explicit operations are `extend`,
`truncate`, `wrap`, `saturate`, `resizeChecked`, `toSigned`, `toUnsigned`, `reinterpretSigned`, and
`reinterpretUnsigned`.

`Vec` is structural shaped data with static or symbolic dimensions and supports `at`, `flatten`,
`reshape`, `map`, `zip`, and `reduce`. `Mem` is explicit addressable storage with depth, latency,
read-under-write, ordering, domain, and mask contracts. A `Vec` must not silently become a memory.

`Quantity[D]` carries a physical dimension. Initial source-visible dimensions are voltage, current,
resistance, and time. Incompatible addition is a type error. Future dimensions may be additive only when
they do not weaken existing dimensional checks.

## Struct, Interface, Role, and protocols

`Struct` is the sole frozen directionless value aggregate. It is storable and may be a `Valid` or
`Stream` payload. The experimental `Aggregate`/`AggregateField` spelling is rejected and removed from
the supported source surface.

`Interface` is non-storable connectivity. `Role[R]` selects named per-member access. The built-in role
identities are `master`, `slave`, `source`, `sink`, `initiator`, `target`, `controller`, `peripheral`,
`device`, `environment`, and `monitor`. Automatic inversion exists only for fully complementary digital
roles and preserves the interface-specific member-access map. Monitor access is read-only.

Direct connection is `connectExact` and requires one Interface identity plus complementary role
evidence. v0.3 deliberately does not freeze generic `via` or `viewAs` helpers. Protocol conversion,
field adaptation, resizing, buffering, latency changes, and domain conversion use an explicit
user-authored `Module` boundary. This keeps adaptation visible in hierarchy and source maps.

`Valid[T]` is the sole valid-only protocol. `Stream[T]` is ordered elastic ready/valid transport.
`Txn[T]` is a fixed-rate transaction wrapper for automatic pipeline regions. No implicit conversion
exists among plain, `Valid`, `Stream`, or Interface identities.

## Clock, reset, state, and crossings

The v0.2 clock/reset surface remains frozen unchanged: `Clock` and `Reset` are distinct from `Bool`;
`ClockDomain.external`, `from`, `required`, and `generated` define domain ownership; `Reg` and `RegNext`
define state; and `when`/`elsewhen`/`otherwise` define priority updates. Ordinary synchronous
`always(event)` remains outside the public subset.

CDC, RDC, reset combination, clock gates, and glitchless muxes remain explicit through `Cdc`, `Rdc`,
`ResetController`, `ClockGate`, and `ClockMux`. Automatic pipeline regions capture one lexical domain and
cannot hide a crossing.

## Automatic pipeline

`pipe` schedules an explicit transaction transform; `delay` expresses structural latency. Frozen policy
includes `Latency.Auto`, `Latency.Exact`, `Latency.Range`, `Throughput.EveryCycle`, and
`ReadyPath.Auto`/`Combinational`/`Registered`. `stage` is a hard boundary and `sameStage` is a local
co-location constraint. `ParameterEnvelope` requires one schedule valid for the complete legal
parameter range. `inspectSchedule` exposes reviewable evidence.

Every dynamic value used by a transform must enter through its transaction. Constants and parameters
remain static. Fixed-rate published interfaces require exact or bounded latency. Variable-latency
operators accept elastic `Stream` input. The scheduler may balance sidebands and reconvergence but may
not reassociate arithmetic, share resources silently, move effects, cross domains, specialize one module
per parameter value, or perform general HLS.

## Memory, external operations, enums, FSMs, naming, and checks

`ExternalOp`, `FixedLatencyOperator`, and `VariableLatencyOperator` require explicit latency,
initiation interval, effect, model availability, and domain contracts. Unknown effects or models are
barriers and errors where required.

Native Scala enums derive `HwEnum`; `enumEncoding` freezes canonical ABI values and `decodeEnum` is the
safe decode form. `FsmDefinition` and `fsm` cover flat, nested, parallel, timed, and bounded-call-stack
control. Canonical enum ABI is separate from local FSM storage encoding. Unbounded recursion is rejected.

`TemporaryPolicy`, `NamingPolicy`, `CheckProfile`, `CheckWaiver`, and `EmitQuality` freeze source-visible
quality policy. Mandatory safety checks cannot be disabled. Names, materialization reasons, source spans,
logical Interface ABI entries, and schedule evidence appear through `DesignReport` rather than backend
comments or traversal-counter names.

## Digital inout and AMS connectivity

`DigitalInout[A, M]` has a separate `read` view and drive state. Push-pull and open-source endpoints use
explicit value plus enable; open-drain uses `driveLow`; release is `highZ`. `split` exposes the portable
read/write/enable carrier. `passThrough` and `padAdapter` are explicit hierarchy and technology
boundaries. Multiple drivers exist only on declared resolved nets, and unsupported internal resolution
is an error rather than a silent mux rewrite.

Conservative `Terminal` connectivity is distinct from digital inout and directional `AnalogSignal` flow.
Terminal access is explicitly connect, sense, contribute, or monitor. `MixedSignalBridge` and
`ConservativeSignalBridge` require sample/update timing, thresholds, hysteresis, quantization, model
availability, and provenance. There is no implicit digital/analog or conservative/signal-flow conversion.

## Backends, layouts, and reports

The frozen backends are `Backend.Auto`, `Backend.Verilog`, `Backend.VerilogA`, and
`Backend.VerilogAMS`. `EmitOptions()` now defaults to `Backend.Auto`. Automatic selection is:

- digital-only -> portable `Backend.Verilog`;
- analog-only -> `Backend.VerilogA`;
- mixed-signal -> `Backend.VerilogAMS`.

The frozen design kinds are `DigitalOnly`, `AnalogOnly`, `MixedSignal`, and `Unsupported`. Portable
digital profiles are `Synthesis`, `Simulation`, and `Formal`. `InterfaceLayout.PortableFlattened` is the
supported v0.3 Interface ABI layout. `FutureSystemVerilogNative` remains a comparison value only and must
be rejected by an unsupported backend/profile combination.

`Backend.SystemVerilog` is not public v0.3 API. Native SystemVerilog interfaces/modports require the
separate future gate and must preserve the same logical Interface ABI as flattened output.

`Emission` returns deterministic in-memory files plus `DesignReport`, which carries design kind,
selected backend, digital profile, logical-to-emitted Interface ABI entries, source-map entries, and
schedule inspections. No filesystem write occurs unless the caller performs it.

## Compatibility and migration

v0.1 analog, parameter, hierarchy, and explicit Verilog-A/Verilog-AMS forms remain source compatible.
v0.2 clock/reset/domain/crossing forms remain source compatible. The semantic default for
`EmitOptions()` changes from Verilog-AMS to Auto; code requiring the old behavior must pass
`backend = Backend.VerilogAMS` explicitly.

Source compatibility is required across v0.3.x for every symbol listed in `public-api-v0.3.json`. Binary
compatibility is not promised before 1.0. A breaking source or semantic change requires a new approved
design gate and migration note. An additive overload is allowed only when resolution and semantics stay
unambiguous.

Reusable libraries use `import nodal.*` and may use language, protocol, domain, connectivity, pipeline,
memory, enum, and FSM contracts. Compiler selection/reporting, `Nodal.emit`, `nodal.lowlevel.*`,
`nodal.internal.*`, and bootstrap APIs remain outside the reusable-library subset.

## Rejected alternatives

- `Aggregate` as a second value aggregate beside `Struct`;
- `Flow` as a second valid-only core protocol beside `Valid`;
- implicit protocol, role, width, latency, or domain adaptation;
- direct variable-style assignment to resolved inout without drive state;
- automatic clock creation or silent CDC/RDC insertion;
- clone-per-parameter scheduling or backend specialization;
- raw SystemVerilog interface/modport syntax in the source API;
- raw CIRCT/MLIR graph operations in ordinary Nodal source;
- backend-dependent arithmetic, storage, protocol, or Interface semantics;
- timing estimates represented as timing closure.

## Validation and implementation boundary

The freeze is accepted only with positive native and external-library compilation, independent Scala
type-negative fixtures, semantic-contract fixtures with stable source-located diagnostics, migration
fixtures for v0.1/v0.2, Increment 13 and Increment 14 non-regression, and green Core CI.

All implementation behavior remains inert. Increment 16 is the first construction-kernel increment and
must implement against this gate rather than redesigning the public surface.
'''

MIGRATION_V02 = r'''
# Nodal public API v0.2 to v0.3 migration

Public API v0.3 retains the complete v0.2 clock/reset surface and adds frozen core semantics,
connectivity, pipeline, and backend selection. Most v0.2 source compiles unchanged.

## Backend default

The only intentional default change is emission selection:

```scala
// v0.2 default behavior
val legacy = EmitOptions(backend = Backend.VerilogAMS)

// v0.3 default behavior
val automatic = EmitOptions() // Backend.Auto
```

Pin `Backend.VerilogAMS` when an existing application requires the prior default. `Backend.Auto`
classifies the design and chooses portable Verilog, Verilog-A, or Verilog-AMS without changing language
semantics.

## Directionless payloads and connectivity

Use `Struct` for storable records and protocol payloads. Use `Interface` plus a named `Role` for
connectivity. Do not store an Interface endpoint or place one inside `Valid`/`Stream`.

Direct connections use `connectExact`. Any width, field, protocol, latency, or domain conversion is an
explicit adapter `Module`; v0.3 does not freeze generic implicit or view-based adaptation.

## Arithmetic and shaped values

Mixed signed/unsigned arithmetic now requires `toSigned`, `toUnsigned`, or an explicit reinterpretation.
Narrowing uses `truncate`, `wrap`, `saturate`, or `resizeChecked`. Structural multidimensional data uses
`Vec`; addressable storage uses `Mem`.

## Pipeline

All dynamic values read by a `pipe` transform must be members of its input transaction. Parameters and
constants remain static. Published fixed-rate boundaries use exact or bounded latency.

## No clock/reset migration required

`ClockDomain`, `Reg`, `RegNext`, `when`, `Cdc`, `Rdc`, `ClockGate`, and `ClockMux` retain their v0.2
spellings and contracts. Ordinary `always(event)` remains removed.
'''

MIGRATION_V01 = r'''
# Nodal public API v0.1 to v0.3 migration

This document combines the v0.1-to-v0.2 clock/reset migration with the unified v0.3 semantic freeze.

## Analog and parameters remain

The short analog forms remain valid:

```scala
final class Gain extends Module:
  val input = in(Electrical)
  val output = out(Electrical)
  val gain = param(2.0.real)

  analog:
    V(output) <+ gain * V(input)
```

Native symbolic parameters remain one target module per structure. v0.3 does not specialize or clone a
module for every parameter value.

## Replace ordinary synchronous always

v0.1 ordinary synchronous code:

```scala
always(clock.rising):
  state := next
```

migrates to v0.2/v0.3 domain-owned state:

```scala
val core = ClockDomain.required("core")

core:
  val state = Reg(resetValue)
  when(enable):
    state := next
```

Genuine analog `on(cross(...))` and `on(timer(...))` behavior remains. Only irreducible low-level event
processes use `nodal.lowlevel.process`, outside reusable libraries.

## Adopt v0.3 values, connectivity, and pipeline

Use `SInt` plus explicit conversions for signed arithmetic, `Vec` for structural shaped values, `Mem`
for storage, `Struct` for directionless records, and `Interface`/`Role` for connectivity. Use `Valid`,
`Stream`, or `Txn` according to protocol semantics. Adaptation and CDC/RDC remain explicit.

## Emission

`EmitOptions()` now selects `Backend.Auto`. Pass `Backend.VerilogAMS` explicitly to preserve the v0.1
emission default.
'''

REFERENCE = r'''
# Nodal public API v0.3

Public API v0.3 is the unified source contract for digital, analog, and mixed-signal construction.
Implementation remains staged after the freeze.

## Import and module

```scala
import nodal.*

final class Counter extends Module:
  val domain = ClockDomain.required("core")
  val width = param(16.integer)
  val enable = in(Bool)
  val input = in(UInt(width))
  val output = out(UInt(width))

  domain:
    val state = Reg(0.U(width))
    when(enable):
      state := input
    output := state
```

## Values

- `Bool`, `Bits`, `UInt`, and `SInt` are distinct finite-width kinds.
- narrowing and signedness changes are explicit;
- `Struct` is the storable directionless record;
- `Vec` is structural shaped data; `Mem` is addressable storage;
- `Quantity[D]` checks physical dimensions.

## Connectivity

```scala
sealed trait Link extends Interface

object Link:
  val definition = Interface[Link](
    "Link",
    InterfaceMember.stream("payload", UInt(32))
  )
  val sourceRole = Role[SourceRole]("source", RoleAccess.Master("payload"))
  val sinkRole = Role[SinkRole]("sink", RoleAccess.Slave("payload"))
```

`connectExact` is the only direct connection. Conversion uses an explicit adapter module. `Struct` and
`Interface` are never interchangeable.

## Pipeline

```scala
val scheduled = pipe(
  Txn(Input(a, b, tag)),
  PipelinePolicy(latency = Latency.Exact(2))
): current =>
  Result(stage(current.a + current.b), current.tag)
```

Every dynamic read enters through the transaction. `Valid` is valid-only and `Stream` is elastic.
`stage`, `sameStage`, `ParameterEnvelope`, and `inspectSchedule` make constraints and evidence explicit.

## Digital inout and AMS

Digital inout has separate read, drive, release, split-carrier, hierarchy, and pad operations.
Conservative terminals and directional analog signal-flow values remain distinct. Mixed-signal
conversion always uses an explicit bridge contract.

## Emission

```scala
val emission = Nodal.emit(
  new Counter,
  EmitOptions(
    backend = Backend.Auto,
    digitalProfile = DigitalProfile.Synthesis,
    interfaceLayout = InterfaceLayoutPolicy(InterfaceLayout.PortableFlattened)
  )
)
```

`Emission` carries deterministic in-memory files and a `DesignReport`. Native SystemVerilog is not a
v0.3 backend.
'''

EXTERNAL_SCALA = r'''
package external.v03

import nodal.*

sealed trait UnifiedLink extends Interface

object UnifiedLink:
  val definition: InterfaceType[UnifiedLink] = Interface[UnifiedLink](
    "UnifiedLink",
    InterfaceMember.value("tag", UInt(8)),
    InterfaceMember.stream("payload", UInt(32))
  )

  val sourceRole: Role[SourceRole] = Role[SourceRole](
    "source",
    RoleAccess.Out("tag"),
    RoleAccess.Master("payload")
  )

  val sinkRole: Role[SinkRole] = Role[SinkRole](
    "sink",
    RoleAccess.In("tag"),
    RoleAccess.Slave("payload")
  )

sealed trait LegacyLink extends Interface

object LegacyLink:
  val definition: InterfaceType[LegacyLink] = Interface[LegacyLink](
    "LegacyLink",
    InterfaceMember.valid("word", UInt(16))
  )

  val sourceRole: Role[SourceRole] = Role[SourceRole](
    "source",
    RoleAccess.Master("word")
  )

  val sinkRole: Role[SinkRole] = Role[SinkRole](
    "sink",
    RoleAccess.Slave("word")
  )

final case class ExternalRequest(data: Expr[UInt], tag: Expr[UInt])
final case class ExternalResponse(data: Expr[UInt], tag: Expr[UInt])

final class ReusableV03Pipeline extends Module:
  val domain = ClockDomain.required("external-v03")
  val width = param(32.integer)
  val input = in(UInt(width))
  val tag = in(UInt(8))
  val output = out(UInt(width))

  val sourcePort = interfacePort(
    UnifiedLink.definition,
    UnifiedLink.sourceRole,
    "source",
    domain
  )
  val sinkPort = interfacePort(
    UnifiedLink.definition,
    UnifiedLink.sinkRole,
    "sink",
    domain
  )
  sourcePort.connectExact(sinkPort)

  val scheduled = pipe(
    Txn(ExternalRequest(input, tag)),
    PipelinePolicy(latency = Latency.Exact(2))
  ): current =>
    ExternalResponse(stage(current.data + 1.U(width)), current.tag)

  output := scheduled.value.data
  ExternalEvidence.consume(scheduled.value.tag, sourcePort.monitorView)

/** v0.3 adaptation is a visible module boundary rather than an implicit view conversion. */
final class ExplicitV03Adapter extends Module:
  val domain = ClockDomain.required("external-adapter")
  val legacy = interfacePort(
    LegacyLink.definition,
    LegacyLink.sinkRole,
    "legacy",
    domain
  )
  val modern = interfacePort(
    UnifiedLink.definition,
    UnifiedLink.sourceRole,
    "modern",
    domain
  )
  ExternalEvidence.consume(legacy, modern)

object ExternalEvidence:
  def consume(values: Any*): Unit = values.foreach(_ => ())
'''

UNIFIED_SCALA = r'''
package contracts.v03

import external.v03.ExplicitV03Adapter
import external.v03.ReusableV03Pipeline
import external.v03.UnifiedLink
import nodal.*

enum UnifiedState derives HwEnum:
  case Idle, Active, Complete, Error

object UnifiedState:
  val canonical: EnumEncoding[UnifiedState] = enumEncoding(
    Idle -> BigInt(0),
    Active -> BigInt(1),
    Complete -> BigInt(2),
    Error -> BigInt(3)
  )

sealed trait LocalLink extends Interface

object LocalLink:
  val definition: InterfaceType[LocalLink] = Interface[LocalLink](
    "LocalLink",
    InterfaceMember.value("frame", Bool),
    InterfaceMember.valid("metadata", UInt(8)),
    InterfaceMember.stream("payload", UInt(32))
  )

  val sourceRole: Role[SourceRole] = Role[SourceRole](
    "source",
    RoleAccess.Out("frame"),
    RoleAccess.Master("metadata"),
    RoleAccess.Master("payload")
  )

  val sinkRole: Role[SinkRole] = Role[SinkRole](
    "sink",
    RoleAccess.In("frame"),
    RoleAccess.Slave("metadata"),
    RoleAccess.Slave("payload")
  )

  val monitorRole: Role[MonitorRole] = Role[MonitorRole](
    "monitor",
    RoleAccess.Observe("frame"),
    RoleAccess.Observe("metadata"),
    RoleAccess.Observe("payload")
  )

final case class ArithmeticInput(
    left: Expr[UInt],
    right: Expr[UInt],
    tag: Expr[UInt]
)

final case class ArithmeticResult(data: Expr[UInt], tag: Expr[UInt])

final class UnifiedPublicApiV03 extends Module:
  val domain = ClockDomain.required("unified-v03")
  val width = param(32.integer)

  val unsignedIn = in(UInt(width))
  val signedIn = in(SInt(width))
  val bitsIn = in(Bits(width))
  val tag = in(UInt(8))
  val enable = in(Bool)
  val packedWrite = in(Bits(8))

  val stateOut = out(UInt(width))
  val signedOut = out(SInt(width))
  val pipelineOut = out(UInt(width))

  domain:
    val state = Reg(0.U(width))
    when(enable):
      state := unsignedIn
    stateOut := state

  val signedResult = (signedIn + 1.S(width)).resizeChecked(16)
  signedOut := signedResult
  val converted = unsignedIn.toSigned
  val reinterpreted = unsignedIn.reinterpretSigned
  val restored = signedIn.toUnsigned

  val packetType = Struct(
    "Packet",
    StructField("data", UInt(width)),
    StructField("valid", Bool)
  )
  val packet = wire(packetType)
  val packetRegister = Reg(packet)
  packet := packetRegister

  val matrixType = Vec(SInt(8), 2, 4, width)
  val matrix = wire(matrixType)
  val element = matrix.at(0, 0, 0)
  val reshaped = matrix.flatten.reshape(8, width)
  val mapped = matrix.map(value => value + 1.S(8))
  val zipped = matrix.zip(mapped)
  val reduced = matrix.reduce((left, right) => left + right)

  generate(width): _ =>
    V03Evidence.consume("symbolic-generate")
  loop(LoopBound.Symbolic(width, maximum = 64)): _ =>
    V03Evidence.consume("bounded-loop")

  val memory = Mem(
    element = UInt(32),
    depth = width,
    readLatency = 1,
    readUnderWrite = ReadUnderWrite.OldData,
    ordering = MemoryOrdering.Ordered,
    domain = domain
  )
  val memoryData = memory.read(unsignedIn)
  memory.write(unsignedIn, memoryData, 15.U(4))

  val external = ExternalOp[UInt, UInt](
    name = "crc32",
    outputType = UInt(32),
    contract = ExternalContract(
      latency = 1,
      initiationInterval = 1,
      effect = Effect.Pure,
      models = Set(ModelAvailability.Simulation, ModelAvailability.Synthesis),
      domain = domain
    )
  )
  val externalResult = external(unsignedIn)

  val supply = 1.0.volts
  val reference = 0.8.volts
  val totalVoltage = supply + reference
  val resistance = 50.0.ohms

  val decoded = decodeEnum(bitsIn, UnifiedState.Error)
  val control = FsmDefinition[UnifiedState]("unified-control")
  fsm(
    definition = control,
    initial = UnifiedState.Idle,
    encoding = FsmEncoding.Compact,
    illegalState = IllegalStatePolicy.RecoverToInitial
  ): machine =>
    machine.state(UnifiedState.Idle): state =>
      state.on(enable)(UnifiedState.Active)
    machine.state(UnifiedState.Active): state =>
      state.after(2)(UnifiedState.Complete)
    machine.state(UnifiedState.Complete): state =>
      state.terminal()
    machine.state(UnifiedState.Error): state =>
      state.terminal()
    machine.parallel("status"):
      V03Evidence.consume("parallel")
    machine.boundedCallStack(4)

  val localSource = interfacePort(
    LocalLink.definition,
    LocalLink.sourceRole,
    "localSource",
    domain
  )
  val localSink = interfacePort(
    LocalLink.definition,
    LocalLink.sinkRole,
    "localSink",
    domain
  )
  localSource.connectExact(localSink)
  val localInverse = localSource.inverted
  val inverseAccess = localInverse.role.access
  val localMonitor = localSource.monitorView
  localMonitor.observeMember("payload")
  val localArray = interfaceArray(
    LocalLink.definition,
    LocalLink.sourceRole,
    width,
    "localArray",
    domain
  )

  val externalSource = interfacePort(
    UnifiedLink.definition,
    UnifiedLink.sourceRole,
    "externalSource",
    domain
  )
  val externalSink = interfacePort(
    UnifiedLink.definition,
    UnifiedLink.sinkRole,
    "externalSink",
    domain
  )
  externalSource.connectExact(externalSink)
  val reusable = instance(new ReusableV03Pipeline)
  val explicitAdapter = instance(new ExplicitV03Adapter)

  val gpio = digitalInout(
    Bits(8),
    DriveMode.pushPull,
    InoutPlacement.TopLevelPin,
    ResolutionProfile.PortableBoundaryOnly,
    "gpio"
  )
  val gpioRead = gpio.read
  gpio.drive(packedWrite, enable)
  gpio.highZ()
  val gpioCarrier = gpio.split

  val openDrain = digitalInout(
    Bits(1),
    DriveMode.openDrain,
    InoutPlacement.HierarchyPassThrough,
    ResolutionProfile.PortableBoundaryOnly,
    "openDrain"
  )
  openDrain.driveLow(enable)
  openDrain.highZ()
  val openDrainCarrier = openDrain.split
  val gpioPad = padAdapter(gpio, "GENERIC_GPIO_PAD")

  val vinP = terminal(Electrical, "vinP")
  val vinN = terminal(Electrical, "vinN")
  vinP.connectView.connectTo(vinN.connectView)
  val sensed = vinP.senseView.potential
  vinN.contributeView.contribute(0.0.real)

  val analogSource = AnalogSignal.source[VoltageDimension]("analogSource", "voltage")
  analogSource.driveAnalog(1.0.real)
  val bridge = BridgeContract(
    sampleTime = 1.0e-9.seconds,
    threshold = Some(0.5.volts),
    hysteresis = Some(0.02.volts),
    quantization = QuantizationPolicy.RoundNearest,
    models = Set(ModelAvailability.Simulation, ModelAvailability.Formal),
    provenance = "public-api-v0.3"
  )
  val sampled = MixedSignalBridge.sample(vinP.senseView, UInt(12), bridge)
  ConservativeSignalBridge.senseToSignal(vinP.senseView, analogSource, bridge)

  val policy = PipelinePolicy(
    latency = Latency.Exact(2),
    throughput = Throughput.EveryCycle,
    target = Some(500.MHz),
    ready = ReadyPath.Registered,
    envelopes = Seq(ParameterEnvelope(width, minimum = 8, maximum = 64))
  )
  val transaction = Txn(ArithmeticInput(unsignedIn, restored, tag))
  val scheduled = pipe(transaction, policy): current =>
    ArithmeticResult(stage(current.left + current.right), current.tag)
  val validResult = pipe(Valid(unsignedIn), policy): payload =>
    stage(payload + payload)
  val streamInput = Stream(unsignedIn)
  val streamResult = pipe(streamInput, policy): payload =>
    sameStage:
      payload + payload
  val delayed = scheduled.delay(1)
  val schedule = inspectSchedule(scheduled, "unified", policy)

  val fixed = FixedLatencyOperator[UInt, UInt](
    "fixed",
    UInt(32),
    ExternalContract(
      latency = 2,
      initiationInterval = 1,
      effect = Effect.Pure,
      models = Set(ModelAvailability.Simulation, ModelAvailability.Synthesis),
      domain = domain
    )
  )
  val fixedStream = fixed(streamInput)
  val variable = VariableLatencyOperator[UInt, UInt](
    "variable",
    UInt(width),
    VariableLatencyContract(
      minimumLatency = 2,
      maximumLatency = 12,
      capacity = 4,
      initiationInterval = 1,
      effect = Effect.Pure,
      models = Set(ModelAvailability.Simulation, ModelAvailability.Synthesis),
      domain = domain
    )
  )
  val variableStream = variable(streamInput)

  pipelineOut := scheduled.value.data

  val quality = EmitQuality(
    temporaries = TemporaryPolicy.InlineSafe,
    naming = NamingPolicy.Semantic,
    checks = CheckProfile.Release,
    waivers = Seq.empty
  )
  val options = EmitOptions(
    backend = Backend.Auto,
    digitalProfile = DigitalProfile.Synthesis,
    interfaceLayout = InterfaceLayoutPolicy(InterfaceLayout.PortableFlattened),
    quality = quality
  )
  val sourceSpan = SourceSpan("UnifiedV03.scala", 1, 1, 1, 10)
  val report = DesignReport(
    designKind = DesignKind.MixedSignal,
    selectedBackend = Backend.VerilogAMS,
    digitalProfile = Some(DigitalProfile.Simulation),
    interfaceAbi = Vector(
      InterfaceAbiEntry(
        "localSource.payload",
        "localSource_payload",
        "source",
        "master",
        "UInt(32)",
        domain.name
      )
    ),
    sourceMap = Vector(SourceMapEntry("UnifiedPublicApiV03", sourceSpan)),
    schedules = Vector(schedule)
  )
  val emission = Emission(Vector.empty, report)

  V03Evidence.consume(
    converted,
    reinterpreted,
    packet,
    element,
    reshaped,
    zipped,
    reduced,
    memoryData,
    externalResult,
    totalVoltage,
    resistance,
    decoded,
    UnifiedState.canonical,
    inverseAccess,
    localMonitor,
    localArray,
    reusable,
    explicitAdapter,
    gpioRead,
    gpioCarrier,
    openDrainCarrier,
    gpioPad,
    sensed,
    sampled,
    validResult,
    streamResult,
    delayed,
    fixedStream,
    variableStream,
    options,
    emission,
    LocalLink.monitorRole
  )

object V03Evidence:
  def consume(values: Any*): Unit = values.foreach(_ => ())
'''

MIGRATION_SCALA = r'''
package contracts.v03migration

import nodal.*

final class MigratedV01Analog extends Module:
  val input = in(Electrical)
  val output = out(Electrical)
  val gain = param(2.0.real)

  analog:
    V(output) <+ gain * V(input)

final class MigratedV02Clocked extends Module:
  val domain = ClockDomain.required("migrated-v02")
  val input = in(UInt(8))
  val asyncFlag = in(Bool)
  val enable = in(Bool)
  val output = out(UInt(8))

  domain:
    val synchronized = Cdc.sync(asyncFlag, to = domain)
    val state = Reg(0.U(8))
    when(enable && synchronized):
      state := input
    output := state

object MigrationContracts:
  val v01ExplicitBackend = EmitOptions(backend = Backend.VerilogAMS)
  val v02ExplicitBackend = EmitOptions(backend = Backend.VerilogAMS)
  val v03AutomaticBackend = EmitOptions()
  val v03PortableDigital = EmitOptions(
    backend = Backend.Verilog,
    digitalProfile = DigitalProfile.Synthesis
  )
  val analogEmission = Nodal.emit(new MigratedV01Analog, v01ExplicitBackend)
  val clockedEmission = Nodal.emit(new MigratedV02Clocked, v02ExplicitBackend)

  MigrationEvidence.consume(
    v03AutomaticBackend,
    v03PortableDigital,
    analogEmission,
    clockedEmission
  )

object MigrationEvidence:
  def consume(values: Any*): Unit = values.foreach(_ => ())
'''

TYPE_NEGATIVES = {
    "mixed-signedness.scala": r'''
        package contracts.v03negative

        import nodal.*

        final class MixedSignednessNegative extends Module:
          val unsigned = in(UInt(8))
          val signed = in(SInt(8))

          // diagnostic-anchor: NODAL-NUM-015
          val illegal = unsigned + signed
    ''',
    "quantity-mismatch.scala": r'''
        package contracts.v03negative

        import nodal.*

        val voltage = 1.0.volts
        val current = 1.0.amps

        // diagnostic-anchor: NODAL-UNIT-015
        val illegalQuantity = voltage + current
    ''',
    "interface-storage.scala": r'''
        package contracts.v03negative

        import nodal.*

        sealed trait StoredInterface extends Interface
        val storedType = Interface[StoredInterface](
          "StoredInterface",
          InterfaceMember.value("payload", UInt(8))
        )
        val storedDomain = ClockDomain.required("stored")
        val storedEndpoint = interfacePort(storedType, master, "stored", storedDomain)

        // diagnostic-anchor: NODAL-IFACE-015
        val illegalStorage = Reg(storedEndpoint)
    ''',
    "interface-payload.scala": r'''
        package contracts.v03negative

        import nodal.*

        sealed trait PayloadInterface extends Interface
        val payloadType = Interface[PayloadInterface](
          "PayloadInterface",
          InterfaceMember.value("payload", UInt(8))
        )
        val payloadDomain = ClockDomain.required("payload")
        val payloadEndpoint = interfacePort(payloadType, master, "payload", payloadDomain)

        // diagnostic-anchor: NODAL-PAYLOAD-015
        val illegalPayload = Valid(payloadEndpoint)
    ''',
    "monitor-drive.scala": r'''
        package contracts.v03negative

        import nodal.*

        sealed trait MonitorInterface extends Interface
        val monitorType = Interface[MonitorInterface](
          "MonitorInterface",
          InterfaceMember.value("status", Bool)
        )
        val monitorDomain = ClockDomain.required("monitor-drive")
        val monitorEndpoint = interfacePort(monitorType, monitor, "monitor", monitorDomain)

        // diagnostic-anchor: NODAL-MONITOR-015
        monitorEndpoint.driveMember("status", true.B)
    ''',
    "monitor-inversion.scala": r'''
        package contracts.v03negative

        import nodal.*

        sealed trait MonitorInverseInterface extends Interface
        val inverseType = Interface[MonitorInverseInterface](
          "MonitorInverseInterface",
          InterfaceMember.value("status", Bool)
        )
        val inverseDomain = ClockDomain.required("monitor-inverse")
        val inverseMonitor = interfacePort(inverseType, monitor, "monitor", inverseDomain)

        // diagnostic-anchor: NODAL-INVERT-015
        val illegalInverse = inverseMonitor.inverted
    ''',
    "open-drain-drive.scala": r'''
        package contracts.v03negative

        import nodal.*

        val openDrainEndpoint = digitalInout(
          Bits(1),
          DriveMode.openDrain,
          InoutPlacement.TopLevelPin,
          ResolutionProfile.PortableBoundaryOnly,
          "openDrain"
        )

        // diagnostic-anchor: NODAL-INOUT-015
        openDrainEndpoint.drive(1.U(1), true.B)
    ''',
    "protocol-mismatch.scala": r'''
        package contracts.v03negative

        import nodal.*

        sealed trait ValidInterface extends Interface
        sealed trait StreamInterface extends Interface
        val validType = Interface[ValidInterface](
          "ValidInterface",
          InterfaceMember.valid("payload", UInt(8))
        )
        val streamType = Interface[StreamInterface](
          "StreamInterface",
          InterfaceMember.stream("payload", UInt(8))
        )
        val protocolDomain = ClockDomain.required("protocol")
        val validMaster = interfacePort(validType, master, "valid", protocolDomain)
        val streamSlave = interfacePort(streamType, slave, "stream", protocolDomain)

        // diagnostic-anchor: NODAL-PROTOCOL-015
        validMaster.connectExact(streamSlave)
    ''',
    "systemverilog-backend.scala": r'''
        package contracts.v03negative

        import nodal.*

        // diagnostic-anchor: NODAL-BACKEND-015
        val illegalBackend = Backend.SystemVerilog
    ''',
    "ordinary-always.scala": r'''
        package contracts.v03negative

        import nodal.*

        // diagnostic-anchor: NODAL-MIGRATION-015
        always(true.B.rising):
          ()
    ''',
}

SEMANTIC_NEGATIVES = {
    "pipeline-without-domain.scala": ("NODAL-DOMAIN-015", "pipe(Txn(input), policy): identity"),
    "cross-domain-pipeline.scala": ("NODAL-CDC-015", "pipe(Txn(otherDomainValue), policy): identity"),
    "unrelated-live-capture.scala": ("NODAL-PIPE-015", "pipe(Txn(a), policy): payload => payload + liveB"),
    "unbounded-published-latency.scala": ("NODAL-LATENCY-015", "publish(pipe(Txn(a), PipelinePolicy()))"),
    "external-contract-missing.scala": ("NODAL-EXTERNAL-015", "opaqueExternal(a)"),
    "multiple-drivers.scala": ("NODAL-DRIVER-015", "wire := first; wire := second"),
    "unsupported-resolution.scala": ("NODAL-RESOLVE-015", "internalResolvedNet(Backend.Verilog)"),
    "implicit-bridge.scala": ("NODAL-BRIDGE-015", "digital := conservativeTerminal"),
    "layout-backend-mismatch.scala": ("NODAL-LAYOUT-015", "Backend.Verilog + FutureSystemVerilogNative"),
    "flattening-collision.scala": ("NODAL-FLATTEN-015", "flatten(a_b, a.b)"),
    "unbounded-hardware-loop.scala": ("NODAL-GENERATE-015", "loop(runtimeCount)"),
    "invalid-fsm-contract.scala": ("NODAL-FSM-015", "fsm(unboundedRecursiveStatechart)"),
}

CHECKER = r'''
#!/usr/bin/env python3
"""Validate Increment 15's unified Nodal public API v0.3 freeze."""

from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[1]
API_MANIFEST = Path("core/scala/api/public-api-v0.3.json")
DIAGNOSTICS = Path("core/scala/api/public-api-diagnostics-v0.3.json")
FIXTURES = Path("tests/api/fixtures/increment15/manifest.json")
GATE = Path("docs/design-gates/NodalCoreSemanticsPipelineApi-DG-v0.3.md")
ROADMAP = Path("docs/roadmap/nodal-development-todo.md")
COMPILER_API = Path("core/scala/api/src/nodal/CompilerApi.scala")
CORE_API = Path("core/scala/api/src/nodal/CoreSemanticsCandidateApi.scala")
INTERFACE_API = Path("core/scala/api/src/nodal/PipelineInterfaceCandidateApi.scala")
POSITIVE = Path("examples/publicApiV03/src/contracts/v03/UnifiedV03.scala")
EXTERNAL = Path("examples/publicApiV03External/src/external/v03/ReusableV03.scala")
MIGRATION = Path("examples/publicApiV03Migration/src/contracts/v03migration/V01V02Migration.scala")


@dataclass(frozen=True)
class Problem:
    code: str
    message: str

    def __str__(self) -> str:
        return f"{self.code}: {self.message}"


def read(path: Path, problems: list[Problem], code: str) -> str:
    try:
        return path.read_text(encoding="utf-8")
    except OSError as exc:
        problems.append(Problem(code, f"cannot read {path}: {exc}"))
        return ""


def read_json(path: Path, problems: list[Problem], code: str) -> dict[str, Any]:
    content = read(path, problems, code)
    try:
        value = json.loads(content)
    except json.JSONDecodeError as exc:
        problems.append(Problem(code, f"invalid JSON in {path}: {exc}"))
        return {}
    if not isinstance(value, dict):
        problems.append(Problem(code, f"{path} must contain a JSON object"))
        return {}
    return value


def require(
    text: str,
    fragments: tuple[str, ...],
    problems: list[Problem],
    code: str,
    subject: str,
) -> None:
    for fragment in fragments:
        if fragment not in text:
            problems.append(Problem(code, f"{subject} lacks: {fragment}"))


def nested(value: dict[str, Any], *keys: str) -> Any:
    current: Any = value
    for key in keys:
        if not isinstance(current, dict):
            return None
        current = current.get(key)
    return current


def expected_files(fixtures: dict[str, Any]) -> list[str]:
    files = [
        str(API_MANIFEST),
        str(DIAGNOSTICS),
        str(FIXTURES),
        str(GATE),
        str(ROADMAP),
        str(COMPILER_API),
        str(CORE_API),
        str(INTERFACE_API),
        str(POSITIVE),
        str(EXTERNAL),
        str(MIGRATION),
        "docs/migrations/public-api-v0.1-to-v0.3.md",
        "docs/migrations/public-api-v0.2-to-v0.3.md",
        "docs/language-reference/public-api-v0.3.md",
        "scripts/materialize_increment15.py",
        "scripts/check_increment15.py",
        "tests/api/test_increment15.py",
        ".github/workflows/increment-15-unified-api-freeze.yml",
    ]
    for key in ("scala_type_negative", "semantic_negative"):
        entries = fixtures.get(key)
        if isinstance(entries, list):
            files.extend(str(entry.get("path")) for entry in entries if isinstance(entry, dict))
    return files


def check_repository(root: Path) -> list[Problem]:
    root = root.resolve()
    problems: list[Problem] = []
    fixtures = read_json(root / FIXTURES, problems, "NODAL-INC15-001")
    for relative in expected_files(fixtures):
        if not (root / relative).is_file():
            problems.append(Problem("NODAL-INC15-002", f"missing Increment 15 file: {relative}"))

    manifest = read_json(root / API_MANIFEST, problems, "NODAL-INC15-003")
    diagnostics = read_json(root / DIAGNOSTICS, problems, "NODAL-INC15-004")
    compiler = read(root / COMPILER_API, problems, "NODAL-INC15-005")
    core = read(root / CORE_API, problems, "NODAL-INC15-006")
    interface = read(root / INTERFACE_API, problems, "NODAL-INC15-007")
    positive = read(root / POSITIVE, problems, "NODAL-INC15-008")
    external = read(root / EXTERNAL, problems, "NODAL-INC15-009")
    migration_fixture = read(root / MIGRATION, problems, "NODAL-INC15-010")
    gate = read(root / GATE, problems, "NODAL-INC15-011")
    roadmap = read(root / ROADMAP, problems, "NODAL-INC15-012")
    build = read(root / "build.mill", problems, "NODAL-INC15-013")
    migration_v01 = read(
        root / "docs/migrations/public-api-v0.1-to-v0.3.md",
        problems,
        "NODAL-INC15-014",
    )
    migration_v02 = read(
        root / "docs/migrations/public-api-v0.2-to-v0.3.md",
        problems,
        "NODAL-INC15-015",
    )
    reference = read(
        root / "docs/language-reference/public-api-v0.3.md",
        problems,
        "NODAL-INC15-016",
    )
    predecessor = read(root / "scripts/check_increment14.py", problems, "NODAL-INC15-017")

    validation = fixtures.get("validation")
    final_evidence = (
        isinstance(validation, dict)
        and isinstance(validation.get("pull_request"), int)
        and isinstance(validation.get("dedicated_workflow_run"), int)
    )

    require(
        compiler,
        (
            "enum Backend:",
            "case Auto, Verilog",
            "case VerilogA, VerilogAMS",
            "enum DesignKind:",
            "case DigitalOnly, AnalogOnly, MixedSignal, Unsupported",
            "enum DigitalProfile:",
            "backend: Backend = Backend.Auto",
            "final case class SourceSpan",
            "final case class SourceMapEntry",
            "final case class InterfaceAbiEntry",
            "final case class DesignReport",
            "interfaceAbi: Vector[InterfaceAbiEntry]",
            "sourceMap: Vector[SourceMapEntry]",
            "schedules: Vector[ScheduleInspection]",
            "Emission(Vector.empty)",
        ),
        problems,
        "NODAL-INC15-018",
        "compiler API",
    )
    require(
        core,
        (
            "opaque type SInt",
            "type Dimension = Int | Expr[Integer]",
            "opaque type Vec",
            "def generate(",
            "def loop(",
            "final class Mem",
            "final case class ExternalContract",
            "final class Quantity",
            "enum TemporaryPolicy",
            "enum CheckProfile",
            "trait HwEnum",
            "final class FsmDefinition",
        ),
        problems,
        "NODAL-INC15-019",
        "core semantic API",
    )
    require(
        interface,
        (
            "opaque type Struct",
            "trait Interface",
            "final class Role",
            "final class InterfacePort",
            "def interfaceArray",
            "def connectExact",
            "final class DigitalInout",
            "final class Terminal",
            "final class AnalogSignal",
            "object MixedSignalBridge",
            "final class Txn",
            "final case class PipelinePolicy",
            "def pipe",
            "def delay",
            "def inspectSchedule",
        ),
        problems,
        "NODAL-INC15-020",
        "Interface and pipeline API",
    )
    if final_evidence:
        if "opaque type Aggregate" in core or "AggregateField" in core:
            problems.append(
                Problem("NODAL-INC15-021", "final v0.3 source still exposes rejected Aggregate spelling")
            )
        existing_core_fixture = read(
            root / "examples/coreSemanticsApi/src/CoreSemanticsCandidates.scala",
            problems,
            "NODAL-INC15-022",
        )
        if "Aggregate(" in existing_core_fixture or "AggregateField(" in existing_core_fixture:
            problems.append(
                Problem("NODAL-INC15-023", "Increment 13 positive fixture was not migrated to Struct")
            )
        if "Struct(" not in existing_core_fixture:
            problems.append(
                Problem("NODAL-INC15-024", "Increment 13 positive fixture lacks selected Struct spelling")
            )

    expected_manifest = {
        ("schema",): 1,
        ("api_version",): "0.3",
        ("status",): "frozen",
        ("supersedes",): "0.2",
        ("default_import",): "import nodal.*",
        ("values_and_connectivity", "value_aggregate"): "Struct",
        ("values_and_connectivity", "connectivity_aggregate"): "Interface",
        ("values_and_connectivity", "direct_connection"): "connectExact",
        ("values_and_connectivity", "adaptation"): "explicit user-authored Module boundary",
        ("pipeline", "dynamic_values_must_enter_transaction"): True,
        ("compiler", "default_backend"): "Backend.Auto",
        ("compiler", "systemverilog_backend_public"): False,
        ("compatibility", "v0.3_default_backend"): "Backend.Auto",
        ("implementation_status", "elaboration_implemented"): False,
        ("implementation_status", "scheduler_implemented"): False,
        ("implementation_status", "interface_ir_implemented"): False,
        ("implementation_status", "digital_backend_implemented"): False,
        ("implementation_status", "ams_backend_implemented"): False,
        ("implementation_status", "simulator_implemented"): False,
        ("next_implementation_increment",): 16,
    }
    for path, expected in expected_manifest.items():
        value = nested(manifest, *path)
        if value != expected:
            problems.append(
                Problem(
                    "NODAL-INC15-025",
                    f"manifest {'.'.join(path)} is {value!r}, expected {expected!r}",
                )
            )
    removed = nested(manifest, "values_and_connectivity", "removed_candidate_symbols")
    for symbol in ("Aggregate", "AggregateField", "Flow", "via", "viewAs"):
        if not isinstance(removed, list) or symbol not in removed:
            problems.append(Problem("NODAL-INC15-026", f"manifest does not reject candidate: {symbol}"))

    if diagnostics.get("schema") != 1 or diagnostics.get("api_version") != "0.3":
        problems.append(Problem("NODAL-INC15-027", "diagnostic manifest identity is invalid"))
    if nested(diagnostics, "source_location", "required") is not True:
        problems.append(Problem("NODAL-INC15-028", "diagnostics must require source locations"))
    if nested(diagnostics, "source_location", "fields") != ["path", "line", "column", "span"]:
        problems.append(Problem("NODAL-INC15-029", "diagnostic source-location fields are invalid"))

    diagnostic_entries = diagnostics.get("diagnostics")
    diagnostic_codes: list[str] = []
    if not isinstance(diagnostic_entries, list):
        problems.append(Problem("NODAL-INC15-030", "diagnostic inventory must be a list"))
    else:
        for entry in diagnostic_entries:
            if not isinstance(entry, dict):
                problems.append(Problem("NODAL-INC15-031", "diagnostic entry is not an object"))
                continue
            code = entry.get("code")
            if isinstance(code, str):
                diagnostic_codes.append(code)
            if entry.get("severity") != "error":
                problems.append(Problem("NODAL-INC15-032", f"diagnostic {code!r} is not an error"))
            for field in ("name", "phase", "message", "suggestion"):
                if not isinstance(entry.get(field), str) or not entry[field]:
                    problems.append(Problem("NODAL-INC15-033", f"diagnostic {code!r} lacks {field}"))
    if len(diagnostic_codes) != len(set(diagnostic_codes)):
        problems.append(Problem("NODAL-INC15-034", "diagnostic codes are not unique"))

    if fixtures.get("schema") != 1 or fixtures.get("increment") != 15:
        problems.append(Problem("NODAL-INC15-035", "fixture manifest identity is invalid"))
    if fixtures.get("api_version") != "0.3" or fixtures.get("next_increment") != 16:
        problems.append(Problem("NODAL-INC15-036", "fixture manifest version/next increment is invalid"))
    for inert in (
        "frontend_behavior_inert",
        "scheduler_behavior_inert",
        "interface_ir_behavior_inert",
        "resolution_topology_behavior_inert",
        "backend_behavior_inert",
        "simulator_behavior_inert",
    ):
        if fixtures.get(inert) is not True:
            problems.append(Problem("NODAL-INC15-037", f"fixture manifest must keep {inert} true"))

    fixture_codes: list[str] = []
    for key, minimum, mode in (
        ("scala_type_negative", 10, "scala-type-rejected"),
        ("semantic_negative", 12, "semantic-contract"),
    ):
        entries = fixtures.get(key)
        if not isinstance(entries, list) or len(entries) < minimum:
            problems.append(Problem("NODAL-INC15-038", f"{key} inventory is incomplete"))
            continue
        for entry in entries:
            if not isinstance(entry, dict):
                problems.append(Problem("NODAL-INC15-039", f"{key} entry is not an object"))
                continue
            path = root / str(entry.get("path"))
            code = str(entry.get("code"))
            fixture_codes.append(code)
            if entry.get("mode") != mode:
                problems.append(Problem("NODAL-INC15-040", f"fixture {path} has wrong mode"))
            source = read(path, problems, "NODAL-INC15-041")
            if source.count(f"diagnostic-anchor: {code}") != 1:
                problems.append(Problem("NODAL-INC15-042", f"fixture lacks unique anchor: {path}"))
    if fixture_codes != diagnostic_codes:
        problems.append(
            Problem(
                "NODAL-INC15-043",
                f"fixture diagnostic order {fixture_codes!r} differs from manifest {diagnostic_codes!r}",
            )
        )

    require(
        positive,
        (
            "Struct(",
            "SInt(width)",
            "Vec(SInt(8), 2, 4, width)",
            "generate(width)",
            "LoopBound.Symbolic",
            "Mem(",
            "ExternalOp[UInt, UInt]",
            "enum UnifiedState derives HwEnum",
            "FsmDefinition[UnifiedState]",
            "LocalLink.sourceRole",
            ".connectExact(",
            ".inverted",
            "invertedPixelAccess" if False else "inverseAccess",
            "DriveMode.openDrain",
            ".driveLow(",
            "MixedSignalBridge.sample(",
            "pipe(transaction, policy)",
            "pipe(Valid(unsignedIn), policy)",
            "pipe(streamInput, policy)",
            "DesignKind.MixedSignal",
            "Backend.Auto",
            "DigitalProfile.Synthesis",
            "InterfaceAbiEntry(",
            "SourceMapEntry(",
            "schedules = Vector(schedule)",
            "ExplicitV03Adapter",
        ),
        problems,
        "NODAL-INC15-044",
        "unified positive fixture",
    )
    for forbidden_capture in ("stage(payload + b)", "payload + c"):
        if forbidden_capture in positive:
            problems.append(
                Problem("NODAL-INC15-045", f"positive pipeline captures live signal: {forbidden_capture}")
            )

    require(
        external,
        (
            "package external.v03",
            "import nodal.*",
            "sealed trait UnifiedLink extends Interface",
            "Role[SourceRole]",
            "Role[SinkRole]",
            "interfacePort(",
            ".connectExact(",
            "Txn(ExternalRequest(",
            "PipelinePolicy(latency = Latency.Exact(2))",
            "final class ExplicitV03Adapter extends Module",
        ),
        problems,
        "NODAL-INC15-046",
        "external v0.3 fixture",
    )
    for forbidden in (
        "nodal.internal",
        "nodal.frontend",
        "nodal.compiler",
        "nodal.scheduler",
        "CandidateRuntime",
        "Nodal.emit",
        "EmitOptions",
    ):
        if forbidden in external:
            problems.append(Problem("NODAL-INC15-047", f"external fixture uses excluded surface: {forbidden}"))

    require(
        migration_fixture,
        (
            "final class MigratedV01Analog extends Module",
            "analog:",
            "final class MigratedV02Clocked extends Module",
            "ClockDomain.required",
            "Reg(0.U(8))",
            "Cdc.sync",
            "EmitOptions(backend = Backend.VerilogAMS)",
            "val v03AutomaticBackend = EmitOptions()",
            "backend = Backend.Verilog",
        ),
        problems,
        "NODAL-INC15-048",
        "migration compile fixture",
    )
    require(
        migration_v01 + migration_v02,
        (
            "Backend.Auto",
            "Backend.VerilogAMS",
            "ClockDomain",
            "Struct",
            "Interface",
            "pipe",
            "ordinary synchronous always",
        ),
        problems,
        "NODAL-INC15-049",
        "migration notes",
    )
    require(
        reference,
        (
            "import nodal.*",
            "ClockDomain.required",
            "Struct",
            "Interface",
            "connectExact",
            "pipe(",
            "DigitalProfile.Synthesis",
            "InterfaceLayout.PortableFlattened",
            "Native SystemVerilog is not a",
        ),
        problems,
        "NODAL-INC15-050",
        "v0.3 language reference",
    )
    require(
        gate,
        (
            "**Status:** Approved",
            "**Scope:** public-api",
            "**API version:** 0.3",
            "Values are explicit and lossless",
            "`Struct` is the sole frozen directionless value aggregate",
            "v0.3 deliberately does not freeze",
            "Every dynamic value used by a transform must enter through its transaction",
            "Backend.Auto",
            "`Backend.SystemVerilog` is not public v0.3 API",
            "All implementation behavior remains inert",
            "Increment 16",
        ),
        problems,
        "NODAL-INC15-051",
        "unified design gate",
    )
    require(
        build,
        (
            "object publicApiV03External extends NodalScalaModule",
            "object publicApiV03 extends NodalScalaModule",
            "def moduleDeps = Seq(core.scala.api, publicApiV03External)",
            "object publicApiV03Migration extends NodalScalaModule",
        ),
        problems,
        "NODAL-INC15-052",
        "Mill build",
    )

    v01 = read_json(root / "core/scala/api/public-api-v0.1.json", problems, "NODAL-INC15-053")
    v02 = read_json(root / "core/scala/api/public-api-v0.2.json", problems, "NODAL-INC15-054")
    if v01.get("api_version") != "0.1" or v01.get("status") != "frozen":
        problems.append(Problem("NODAL-INC15-055", "v0.1 historical manifest changed identity"))
    if v02.get("api_version") != "0.2" or v02.get("status") != "frozen":
        problems.append(Problem("NODAL-INC15-056", "v0.2 historical manifest changed identity"))

    if final_evidence:
        checked = (
            "- [x] **Increment 15 — Unified core semantics, Interface/Role/inout, and automatic pipeline public API v0.3 freeze**"
            in roadmap
        )
        if not checked:
            problems.append(Problem("NODAL-INC15-057", "roadmap does not close Increment 15"))
        pull_request = validation["pull_request"]
        run_id = validation["dedicated_workflow_run"]
        if f"PR [#{pull_request}]" not in roadmap or f"[{run_id}]" not in roadmap:
            problems.append(Problem("NODAL-INC15-058", "roadmap lacks final PR/workflow evidence"))
        if "**Revision:** 1.19" not in roadmap:
            problems.append(Problem("NODAL-INC15-059", "roadmap revision is not 1.19"))
        if fixtures.get("status") != "validated-freeze":
            problems.append(Problem("NODAL-INC15-060", "fixture manifest is not finalized"))
        if 'line.startswith(("- [ ] **Increment 15 — ", "- [x] **Increment 15 — "))' not in predecessor:
            problems.append(Problem("NODAL-INC15-061", "Increment 14 checker is not successor-safe"))
    else:
        unchecked = (
            "- [ ] **Increment 15 — Unified core semantics, Interface/Role/inout, and automatic pipeline public API v0.3 freeze**"
            in roadmap
        )
        if not unchecked:
            problems.append(Problem("NODAL-INC15-062", "preflight roadmap must leave Increment 15 unchecked"))
        if not isinstance(validation, dict) or validation != {
            "pull_request": None,
            "dedicated_workflow_run": None,
        }:
            problems.append(Problem("NODAL-INC15-063", "preflight validation evidence is malformed"))
    increment16 = [line for line in roadmap.splitlines() if line.startswith("- [ ] **Increment 16 — ")]
    if len(increment16) != 1 or "kernel" not in increment16[0].lower():
        problems.append(Problem("NODAL-INC15-064", "roadmap does not leave one unchecked Increment 16"))
    return problems


def run_mill(root: Path, *targets: str) -> subprocess.CompletedProcess[str]:
    wrapper = root / ("mill.bat" if os.name == "nt" else "mill")
    return subprocess.run(
        [str(wrapper), *targets],
        cwd=root,
        check=False,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
    )


def check_compile_contracts(root: Path) -> list[Problem]:
    problems: list[Problem] = []
    fixtures = json.loads((root / FIXTURES).read_text(encoding="utf-8"))
    positive = run_mill(
        root,
        "examples.publicApiV03External.compile",
        "examples.publicApiV03.compile",
        "examples.publicApiV03Migration.compile",
    )
    if positive.returncode != 0:
        return [
            Problem(
                "NODAL-INC15-065",
                "positive v0.3 compilation failed:\n" + positive.stdout[-16000:],
            )
        ]

    injected = root / "examples/publicApiV03/src/__Increment15Negative.scala"
    if injected.exists():
        return [Problem("NODAL-INC15-066", f"refusing to overwrite {injected}")]
    try:
        for entry in fixtures["scala_type_negative"]:
            source_path = root / entry["path"]
            injected.write_text(source_path.read_text(encoding="utf-8"), encoding="utf-8")
            completed = run_mill(root, "examples.publicApiV03.compile")
            if completed.returncode == 0:
                problems.append(Problem("NODAL-INC15-067", f"negative fixture compiled: {entry['path']}"))
            elif injected.name not in completed.stdout:
                problems.append(
                    Problem(
                        "NODAL-INC15-068",
                        f"failure did not identify injected fixture: {entry['path']}",
                    )
                )
            injected.unlink(missing_ok=True)
    finally:
        injected.unlink(missing_ok=True)

    restored = run_mill(root, "examples.publicApiV03.compile")
    if restored.returncode != 0:
        problems.append(
            Problem(
                "NODAL-INC15-069",
                "positive v0.3 module did not recover after negative fixtures:\n"
                + restored.stdout[-16000:],
            )
        )
    return problems


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=ROOT)
    parser.add_argument("--compile-negative", action="store_true")
    args = parser.parse_args(argv)

    problems = check_repository(args.root)
    if not problems and args.compile_negative:
        problems.extend(check_compile_contracts(args.root.resolve()))
    for problem in problems:
        print(problem, file=sys.stderr)
    if problems:
        print(f"Increment 15 check failed with {len(problems)} problem(s)", file=sys.stderr)
        return 1
    print("Increment 15 check passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
'''

UNIT_TEST = r'''
from __future__ import annotations

import subprocess
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
CHECKER = ROOT / "scripts/check_increment15.py"


class Increment15FreezeTests(unittest.TestCase):
  def test_repository_matches_v03_freeze(self) -> None:
    completed = subprocess.run(
      [sys.executable, str(CHECKER), "--root", str(ROOT)],
      cwd=ROOT,
      check=False,
      text=True,
      stdout=subprocess.PIPE,
      stderr=subprocess.STDOUT,
    )
    self.assertEqual(completed.returncode, 0, completed.stdout)


if __name__ == "__main__":
  unittest.main()
'''


def prepare() -> None:
    write_text("core/scala/api/src/nodal/CompilerApi.scala", COMPILER_API)
    write_json("core/scala/api/public-api-v0.3.json", public_manifest())
    write_json(
        "core/scala/api/public-api-diagnostics-v0.3.json",
        {
            "schema": 1,
            "api_version": "0.3",
            "status": "frozen",
            "source_location": {
                "required": True,
                "fields": ["path", "line", "column", "span"],
            },
            "diagnostics": diagnostics(),
        },
    )
    write_json("tests/api/fixtures/increment15/manifest.json", fixture_manifest())
    write_text("docs/design-gates/NodalCoreSemanticsPipelineApi-DG-v0.3.md", GATE)
    write_text("docs/migrations/public-api-v0.1-to-v0.3.md", MIGRATION_V01)
    write_text("docs/migrations/public-api-v0.2-to-v0.3.md", MIGRATION_V02)
    write_text("docs/language-reference/public-api-v0.3.md", REFERENCE)
    write_text(
        "examples/publicApiV03External/src/external/v03/ReusableV03.scala",
        EXTERNAL_SCALA,
    )
    write_text("examples/publicApiV03/src/contracts/v03/UnifiedV03.scala", UNIFIED_SCALA)
    write_text(
        "examples/publicApiV03Migration/src/contracts/v03migration/V01V02Migration.scala",
        MIGRATION_SCALA,
    )
    for name, content in TYPE_NEGATIVES.items():
        write_text(f"tests/api/fixtures/increment15/negative/{name}", content)
    for name, (code, expression) in SEMANTIC_NEGATIVES.items():
        write_text(
            f"tests/api/fixtures/increment15/semantic/{name}",
            f"""
            package contracts.v03semantic

            // diagnostic-anchor: {code}
            // semantic-contract-only: {expression}
            """,
        )
    write_text("scripts/check_increment15.py", CHECKER)
    write_text("tests/api/test_increment15.py", UNIT_TEST)

    build = ROOT / "build.mill"
    build_marker = "object publicApiV03External extends NodalScalaModule"
    if build_marker not in build.read_text(encoding="utf-8"):
        with build.open("a", encoding="utf-8") as stream:
            stream.write('\n  object publicApiV03External extends NodalScalaModule:\n    def moduleDeps = Seq(core.scala.api)\n\n  object publicApiV03 extends NodalScalaModule:\n    def moduleDeps = Seq(core.scala.api, publicApiV03External)\n\n  object publicApiV03Migration extends NodalScalaModule:\n    def moduleDeps = Seq(core.scala.api)\n')

    core_api = ROOT / "core/scala/api/src/nodal/CoreSemanticsCandidateApi.scala"
    replace_once_or_present(
        core_api,
        """/** Compile-only public candidates for Increment 13. No elaboration or lowering semantics live here.
  */""",
        """/** Public core semantic surface frozen by Increment 15. Implementation remains inert. */""",
    )
    remove_once_or_absent(
        core_api,
        """/** Directionless aggregate candidate. */
opaque type Aggregate <: Data = Bits

final case class AggregateField[A <: Data](name: String, dataType: DataType[A])

object Aggregate:
  def apply(name: String, fields: AggregateField[? <: Data]*): DataType[Aggregate] =
    CandidateRuntime.statement(name, fields)
    Bits(1).asInstanceOf[DataType[Aggregate]]

""",
    )

    core_fixture = ROOT / "examples/coreSemanticsApi/src/CoreSemanticsCandidates.scala"
    text = core_fixture.read_text(encoding="utf-8")
    text = text.replace("val packetType = Aggregate(", "val packetType = Struct(")
    text = text.replace("AggregateField(", "StructField(")
    core_fixture.write_text(text, encoding="utf-8")

    interface_api = ROOT / "core/scala/api/src/nodal/PipelineInterfaceCandidateApi.scala"
    replace_once_or_present(
        interface_api,
        """/** Compile-only public candidates for Increment 14. No construction, scheduling, lowering,
  * resolution, topology, or simulation semantics live here.
  */""",
        """/** Public Interface, inout, AMS, and pipeline surface frozen by Increment 15.
  * Construction, scheduling, lowering, resolution, topology, and simulation remain inert.
  */""",
    )

    predecessor = ROOT / "scripts/check_increment14.py"
    replace_once_or_present(
        predecessor,
        """    increment15_lines = [
        line for line in roadmap.splitlines() if line.startswith("- [ ] **Increment 15 — ")
    ]""",
        """    increment15_lines = [
        line
        for line in roadmap.splitlines()
        if line.startswith(("- [ ] **Increment 15 — ", "- [x] **Increment 15 — "))
    ]""",
    )
    replace_once_or_present(
        predecessor,
        "roadmap does not leave one unchecked Increment 15 public API freeze",
        "roadmap does not retain one Increment 15 public API freeze",
    )

    codeowners = ROOT / ".github/CODEOWNERS"
    ownership_marker = "/core/scala/api/public-api-v0.3.json"
    if ownership_marker not in codeowners.read_text(encoding="utf-8"):
        with codeowners.open("a", encoding="utf-8") as stream:
            stream.write(
                normalized(
                    """
                    /core/scala/api/public-api-v0.3.json @pysolvesemi
                    /core/scala/api/public-api-diagnostics-v0.3.json @pysolvesemi
                    /scripts/check_increment15.py @pysolvesemi
                    /scripts/materialize_increment15.py @pysolvesemi
                    /docs/language-reference/public-api-v0.3.md @pysolvesemi
                    /docs/migrations/public-api-v0.1-to-v0.3.md @pysolvesemi
                    /docs/migrations/public-api-v0.2-to-v0.3.md @pysolvesemi
                    """
                )
            )

    gate_readme = ROOT / "docs/design-gates/README.md"
    gate_marker = "NodalCoreSemanticsPipelineApi-DG-v0.3.md"
    if gate_marker not in gate_readme.read_text(encoding="utf-8"):
        with gate_readme.open("a", encoding="utf-8") as stream:
            stream.write(
                normalized(
                    """

                    ## Unified public API v0.3

                    `NodalCoreSemanticsPipelineApi-DG-v0.3.md` is the authoritative unified
                    freeze for core semantics, Interface/Role/inout, automatic pipeline, and
                    backend selection. The Increment 13 and 14 gates remain candidate evidence.
                    """
                )
            )


def close(pull_request: int, workflow_run: int) -> None:
    prepare()
    manifest_path = ROOT / "tests/api/fixtures/increment15/manifest.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    validation = manifest.get("validation")
    if validation == {"pull_request": None, "dedicated_workflow_run": None}:
        manifest["status"] = "validated-freeze"
        manifest["validation"] = {
            "pull_request": pull_request,
            "dedicated_workflow_run": workflow_run,
        }
        manifest_path.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    elif not (
        isinstance(validation, dict)
        and isinstance(validation.get("pull_request"), int)
        and isinstance(validation.get("dedicated_workflow_run"), int)
    ):
        raise RuntimeError("Increment 15 validation evidence is partially populated")

    roadmap_path = ROOT / "docs/roadmap/nodal-development-todo.md"
    roadmap = roadmap_path.read_text(encoding="utf-8")
    unchecked = (
        "- [ ] **Increment 15 — Unified core semantics, Interface/Role/inout, and "
        "automatic pipeline public API v0.3 freeze**"
    )
    checked = unchecked.replace("[ ]", "[x]", 1)
    if unchecked in roadmap:
        if roadmap.count(unchecked) != 1:
            raise RuntimeError("Increment 15 roadmap anchor is not unique")
        roadmap = roadmap.replace(unchecked, checked, 1)
        if "**Revision:** 1.18" not in roadmap:
            raise RuntimeError("roadmap revision is not the expected 1.18 preflight")
        roadmap = roadmap.replace("**Revision:** 1.18", "**Revision:** 1.19", 1)
        evidence = (
            "  - Evidence: [`NodalCoreSemanticsPipelineApi-DG-v0.3.md`]"
            "(../design-gates/NodalCoreSemanticsPipelineApi-DG-v0.3.md), "
            "[`public-api-v0.3.json`](../../core/scala/api/public-api-v0.3.json), "
            "[`public-api-diagnostics-v0.3.json`]"
            "(../../core/scala/api/public-api-diagnostics-v0.3.json), "
            "[`public-api-v0.2-to-v0.3.md`]"
            "(../migrations/public-api-v0.2-to-v0.3.md), "
            "[`tests/api/fixtures/increment15/manifest.json`]"
            "(../../tests/api/fixtures/increment15/manifest.json), "
            "[`scripts/check_increment15.py`](../../scripts/check_increment15.py), "
            f"PR [#{pull_request}](https://github.com/pysolvesemi/Nodal/pull/{pull_request}), "
            f"and dedicated validation run [{workflow_run}]"
            f"(https://github.com/pysolvesemi/Nodal/actions/runs/{workflow_run}).\n"
        )
        marker = "\n## Phase 1 — Compiler vertical slice"
        if marker not in roadmap:
            raise RuntimeError("Phase 1 roadmap marker is missing")
        roadmap = roadmap.replace(marker, "\n" + evidence + marker, 1)
        roadmap_path.write_text(roadmap, encoding="utf-8")
    elif checked not in roadmap:
        raise RuntimeError("Increment 15 roadmap item is missing")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--mode", choices=("prepare", "close"), required=True)
    parser.add_argument("--pull-request", type=int)
    parser.add_argument("--workflow-run", type=int)
    args = parser.parse_args()

    if args.mode == "prepare":
        prepare()
    else:
        if args.pull_request is None or args.workflow_run is None:
            parser.error("--close requires --pull-request and --workflow-run")
        close(args.pull_request, args.workflow_run)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
