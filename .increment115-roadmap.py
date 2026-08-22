from __future__ import annotations

import json
from pathlib import Path

ROOT = Path(__file__).resolve().parent
ROADMAP = ROOT / "docs/roadmap/nodal-development-todo.md"
SURFACE = ROOT / "docs/roadmap/register-factory-v0.1-surface.json"

content = ROADMAP.read_text(encoding="utf-8")

revision_before = "**Revision:** 1.13\n"
revision_after = "**Revision:** 1.14\n"
if revision_before not in content:
    raise SystemExit("expected roadmap revision 1.13 was not found")
content = content.replace(revision_before, revision_after, 1)

fixed_anchor = (
    "- Preserve symbolic parameters through elaboration, IR, hierarchy, optimization, "
    "and native parameterized Verilog-A/Verilog-AMS emission. Do not clone one module "
    "per parameter value.\n"
)
fixed_insert = fixed_anchor + (
    "- Define control/status registers through one canonical, bus-neutral Register IR. "
    "Keep immutable register ABI definitions, physical register-block instances, "
    "committed-access semantics, APB/AXI4-Lite/custom transports, and generated software "
    "or integration artifacts as separate layers.\n"
    "- Permit one authoritative register-map source per block: native Scala DSL, supported "
    "SystemRDL 2.0, or versioned Nodal YAML/JSON. Treat IEEE 1685-2022 IP-XACT as later "
    "integration interchange and CSV/spreadsheets only as explicit conversion inputs; all "
    "frontends normalize into the same canonical Register IR.\n"
    "- Emit fixed register offsets, field positions, masks, reset values, and access "
    "encodings as width-safe non-overridable Verilog `localparam`s/constants by default. "
    "Emit HDL `parameter`s only for explicit Nodal architectural variability, use "
    "block-relative decode by default, and keep an optional absolute-base wrapper explicit.\n"
)
if "one canonical, bus-neutral Register IR" not in content:
    if fixed_anchor not in content:
        raise SystemExit("fixed-project-direction insertion anchor was not found")
    content = content.replace(fixed_anchor, fixed_insert, 1)

api_anchor = (
    "- Any incompatible public API change after a freeze requires a new versioned design "
    "gate and migration note.\n"
)
api_insert = (
    "- Reserve a separately versioned register-factory API gate for immutable `RegisterMap` "
    "definitions, physical `RegisterBlock` bindings, typed field handles, orthogonal "
    "software/hardware/collision policies, committed-access endpoints, and Scala 3 "
    "transport adapters. Exact spellings remain deferred to Increment 116.\n"
    "- Keep register authoring independent of a concrete access bus. APB, AXI4-Lite, and "
    "custom buses attach through capability-checked adapters; multiple access paths to one "
    "physical bank require an explicit arbiter/router.\n"
    "- Require equivalent Scala/SystemRDL/YAML descriptions to produce equivalent canonical "
    "Register IR and ABI hashes. Generated headers, UVM models, documentation, SystemRDL, "
    "IP-XACT, and other views never become hidden competing sources.\n"
    + api_anchor
)
if "Reserve a separately versioned register-factory API gate" not in content:
    if api_anchor not in content:
        raise SystemExit("public-API insertion anchor was not found")
    content = content.replace(api_anchor, api_insert, 1)

roadmap_marker = "## Deferred reusable library roadmap\n"
register_track = """- [x] **Increment 115 — Register factory architecture and roadmap contract**
  - Accept [ADR 0020](../architecture/0020-canonical-register-factory-and-transport-adapters.md), the staged [`register-factory-v0.1-plan.md`](register-factory-v0.1-plan.md), and the machine-readable [`register-factory-v0.1-surface.json`](register-factory-v0.1-surface.json).
  - Freeze register-definition-first separation: immutable bus-neutral `RegisterMap`, independent physical `RegisterBlock`, one clock/reset domain per physical bank, one exactly-once committed-access endpoint, and APB/AXI4-Lite/custom transport adapters that cannot redefine register semantics.
  - Record SystemRDL 2.0 as the primary standards-based register interchange, versioned safe Nodal YAML/JSON as a convenience frontend, IEEE 1685-2022 IP-XACT as later integration interchange, and CSV/spreadsheets only as explicit conversion inputs.
  - Freeze generated-Verilog policy: fixed register ABI symbols use width-safe non-overridable `localparam`s/constants; only explicit Nodal architectural variability becomes an HDL `parameter`; relative-offset decode is the default and any absolute-base wrapper is explicit.
  - Keep exact public API, canonical IR, parsers, bus adapters, RTL lowering, and artifact generators unimplemented and assigned to Increments 116-123.

- [ ] **Increment 116 — Register factory public API candidates and design gate**
  - Prototype concise Scala 3 `RegisterMap`, `RegisterBlock`, register/field definitions, typed handles, hardware bindings, arrays/submaps/windows/aliases, snapshots/commits, software/hardware/collision policies, and transport binding.
  - Compare alternatives against mature bus-slave/register-interface facilities while preserving Nodal's stronger register-definition-first separation.
  - Freeze exact imports, names, construction rules, diagnostics, extension points, external-library subset, and compile-positive/negative fixtures in `NodalRegisterFactory-DG-v0.1` before implementation.

- [ ] **Increment 117 — Canonical Register IR, source maps, verifier, and ABI manifest**
  - Implement target-neutral Register IR for blocks, registers, fields, hierarchy, geometry, policies, side effects, hardware bindings, domains, accesses, and transport capabilities.
  - Add deterministic IDs/source locations, canonical JSON, semantic hashing, overlap/alignment/address-width/reserved-region checks, parameter-envelope verification, and ABI lock/diff classification.

- [ ] **Increment 118 — SystemRDL 2.0 and Nodal YAML/JSON frontends**
  - Implement safe versioned `nodal-registers/v1` YAML/JSON parsing with deterministic explicit imports, controlled roots, cycle detection, schema migration diagnostics, and source spans; prohibit arbitrary tags, executable templates, and order-dependent semantics.
  - Implement the supported SystemRDL 2.0 import subset and deterministic export, diagnose unsupported/lossy mappings, and prove equivalent Scala/SystemRDL/YAML definitions normalize to equivalent Register IR and ABI hashes.

- [ ] **Increment 119 — Canonical access endpoint and APB3/APB4 adapter**
  - Implement register storage/update semantics, relative-address decode, read muxes, byte enables, errors, side effects, reset behavior, and the exactly-once committed request/response endpoint.
  - Implement APB3/APB4 setup/access, wait-state, strobe/protection/error handling with open-source simulation, formal protocol/semantic checks, lint, and synthesis evidence.

- [ ] **Increment 120 — AXI4-Lite and custom transport conformance**
  - Implement AXI4-Lite address/data buffering and pairing, backpressure, strobes, protection, responses, ordering, reset, and declared outstanding policy without committing incomplete transactions.
  - Freeze and implement the public custom-transport capability/conformance contract and an explicit multi-access arbiter/router; reject implicit dual attachment.

- [ ] **Increment 121 — Portable Verilog register lowering and parameterized geometry**
  - Emit deterministic relative-offset decode, width-safe named `localparam`s, masks, reset/access constants, storage, side effects, and readable hierarchy; never expose fixed offsets as externally overridable parameters.
  - Emit HDL parameters only for explicit Nodal symbolic configuration, support an explicit optional absolute-base wrapper, validate repeated/parameterized maps over declared envelopes, and prove RTL/artifact configuration consistency.

- [ ] **Increment 122 — Artifact generators, IP-XACT, and software ABI flows**
  - Generate canonical JSON/ABI hashes, C/C++ headers, Rust metadata or PAC input, CMSIS-SVD, UVM RAL/RALF, Markdown/HTML, SystemRDL, and IEEE 1685-2022 IP-XACT register/memory-map views.
  - Preserve stable identities, source provenance, semantic hashes, resolved parameter configuration, and explicit loss diagnostics in every artifact; add cross-artifact equivalence and compatibility-report tests.

- [ ] **Increment 123 — Register factory verification, scale, and reusable adapter/library qualification**
  - Generate semantic verification for reset/access/side-effect/collision/byte-enable/multiword/array/hierarchy/snapshot/commit/illegal-access behavior while keeping concurrent/temporal properties verification-only under Increment 114.
  - Add large-map performance, deterministic output, Verilator/Icarus, Yosys quality/equivalence, custom-adapter conformance, and one external reusable register-map qualification using only public contracts.
  - Publish user, adapter-author, SystemRDL/YAML migration, artifact, and SoC-integration documentation.

"""
if "**Increment 115 — Register factory architecture and roadmap contract**" not in content:
    if roadmap_marker not in content:
        raise SystemExit("roadmap insertion marker was not found")
    content = content.replace(roadmap_marker, register_track + roadmap_marker, 1)

old_deferred = (
    "No official reusable model/component library or production plugin is implemented by "
    "Increments 0-114. After the core API, extension surface, packaging model, and preview "
    "release are proven, independently approved library/plugin roadmaps may populate "
    "`libraries/`, `plugins/`, or separate repositories while preserving the public-core "
    "dependency contract."
)
new_deferred = (
    "No official reusable model/component library or production plugin is implemented by "
    "Increment 115. Increments 116-123 define the future core register-factory and "
    "qualification track; they do not populate `libraries/` yet. After the core API, "
    "extension surface, packaging model, and preview release are proven, independently "
    "approved library/plugin roadmaps may populate `libraries/`, `plugins/`, or separate "
    "repositories while preserving the public-core dependency contract."
)
if old_deferred not in content:
    raise SystemExit("deferred-library paragraph was not found")
content = content.replace(old_deferred, new_deferred, 1)

required = (
    "**Revision:** 1.14",
    "**Revision:** 1.12",
    "Increment 115 — Register factory architecture and roadmap contract",
    "Increment 118 — SystemRDL 2.0 and Nodal YAML/JSON frontends",
    "fixed register ABI symbols use width-safe non-overridable `localparam`s/constants",
    "register-factory-v0.1-surface.json",
)
for fragment in required:
    if fragment not in content:
        raise SystemExit(f"roadmap lacks required fragment: {fragment}")

ROADMAP.write_text(content, encoding="utf-8")

surface = json.loads(SURFACE.read_text(encoding="utf-8"))
if surface.get("surface_version") != "0.1":
    raise SystemExit("register factory surface version is not 0.1")
if surface.get("generated_verilog", {}).get("fixed_abi_symbols_externally_overridable") is not False:
    raise SystemExit("fixed ABI symbols must remain non-overridable")
if surface.get("authoring_frontends", {}).get("nodal_yaml_json", {}).get("schema_id") != "nodal-registers/v1":
    raise SystemExit("YAML/JSON schema identity is not frozen")

print("Increment 115 roadmap materialization passed")
