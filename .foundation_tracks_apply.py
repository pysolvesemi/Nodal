from __future__ import annotations

from pathlib import Path

ROOT = Path(__file__).resolve().parent
ROADMAP = ROOT / "docs/roadmap/nodal-development-todo.md"
ARCH = ROOT / "docs/architecture/README.md"
PLAN = "dependent-productivity-and-verification-tracks-v0.1-plan.md"
ADR = "0023-unified-hvl-native-sim-uvm-uvmms-architecture.md"


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected one occurrence, found {count}")
    return text.replace(old, new, 1)


roadmap = ROADMAP.read_text(encoding="utf-8")
roadmap = replace_once(roadmap, "**Revision:** 1.16", "**Revision:** 1.17", "roadmap revision")
roadmap = replace_once(
    roadmap,
    "# Incremental roadmap\n",
    "# Foundation track — Incremental roadmap\n\n"
    "The numbered roadmap below is the **Foundation track**. FPGA Productivity, Digital Verification, and Analog/Mixed-Signal Verification are dependent tracks with independent numbering that starts again at 1. Those dependent tracks remain implementation-blocked until every Foundation checkbox is complete. Architecture or public seams discovered while researching a dependent track must be added here rather than hidden in a vendor/tool implementation.\n\n",
    "foundation heading",
)

anchor = "## Deferred reusable library roadmap\n"
if "## Phase 10 — Foundation comments, FPGA-readiness, and HVL verification-readiness" in roadmap:
    raise SystemExit("Phase 10 already exists")

addition = f"""## Phase 10 — Foundation comments, FPGA-readiness, and HVL verification-readiness

Detailed rationale and dependent-track plans are in [`{PLAN}`]({PLAN}). Verification backend ownership is defined by [ADR 0023](../architecture/{ADR}). Foundation adds only the architecture/public seams needed to prevent later FPGA/UVM/UVM-MS work from being blocked by core design limitations; dependent-track implementations remain outside Foundation.

- [ ] **Foundation Increment 143 — Comment/documentation IR architecture and public API gate**
  - Freeze automatic ScalaDoc/unambiguous leading-comment capture plus an explicit target-neutral comment/documentation API for guaranteed placement.
  - Define stable Comment IR anchors, propagation/orphan policy, directive separation, and semantic-versus-presentation hashing.

- [ ] **Foundation Increment 144 — Scala source-comment capture and Comment IR propagation**
  - Implement Scala 3 source/comment extraction, explicit comment APIs, stable anchors, deterministic propagation, source correlation, and ambiguity/directive diagnostics.

- [ ] **Foundation Increment 145 — Verilog-family comment and documentation lowering**
  - Emit the same Comment IR deterministically to Verilog, SystemVerilog, Verilog-A, and Verilog-AMS plus documentation/source-map manifests without changing semantic HDL identity.

- [ ] **Foundation Increment 146 — FPGA productivity architecture readiness**
  - Freeze reusable-IP requirements, board/platform resources, project implementation intent, portable Constraint IR, stable semantic targets, vendor capability/tool-adapter seams, constraint coverage, normalized reports, debug identities, and build/program provenance.
  - Do not implement vendor constraints, board libraries, FPGA builds/programming, timing-closure exploration, or debug insertion in Foundation.

- [ ] **Foundation Increment 147 — Nodal HVL Verification Semantic IR and public API architecture gate**
  - Freeze target-neutral tests/scenarios, transactions, processes/events/time, drivers/monitors/agents, scoreboards/reference models, constrained stimulus, deterministic replay, functional coverage, properties/checks, register bindings, reusable VIP packaging, and AMS verification extensions.
  - Bind verification endpoints to logical Interface/Register identities rather than generated HDL hierarchy strings.

- [ ] **Foundation Increment 148 — Native verification runtime and generated-SystemVerilog IR readiness**
  - Freeze the native Nodal verification scheduler/runtime contract independently of UVM and the simulator-adapter boundary needed for direct Verilator/Icarus and future open mixed-signal execution.
  - Define a verification-SystemVerilog IR sufficient for generated UVM/VIP classes, interfaces/virtual interfaces, clocking blocks, dynamic containers, processes/events/mailboxes/semaphores, constraints/randomization, covergroups, properties, and DPI/VPI shims.
  - Do not implement complete HVL runtime or UVM generation in Foundation.

- [ ] **Foundation Increment 149 — UVM/UVM-MS projection and vendor-profile architecture readiness**
  - Accept ADR 0023 and freeze Verification Semantic IR -> UVM/UVM-MS projections while keeping `nodal sim` independent of generated UVM.
  - Freeze UVM component/TLM/factory/config/phase/objection/RAL mappings, UVM-MS structural/class bridge identities, vendor-neutral common source, and thin VCS/Questa/Xcelium profile seams.
  - Confine unavoidable vendor `ifdef`s to generated adapter/include units; do not scatter them through common VIP logic.

## Foundation completion barrier

> **Blocked:** no FPGA Productivity, Digital Verification, or Analog/Mixed-Signal Verification implementation increment may start until every Foundation increment is complete, including any Foundation item appended after Increment 149 before the barrier is released.

Research and feasibility work may continue while blocked. Any newly discovered core architecture requirement belongs in Foundation.

## FPGA Productivity Track — blocked by Foundation; numbering restarts

See [`{PLAN}`]({PLAN}) for detailed scope.

- [ ] **FPGA Increment 1 — FPGA public platform/resource/constraint API gate**
- [ ] **FPGA Increment 2 — Board/device/resource database and binding**
- [ ] **FPGA Increment 3 — Portable timing and I/O constraint engine**
- [ ] **FPGA Increment 4 — AMD Vivado constraint/build backend**
- [ ] **FPGA Increment 5 — Intel Quartus and open-source FPGA backends**
- [ ] **FPGA Increment 6 — Additional vendor profiles and constraint coverage**
- [ ] **FPGA Increment 7 — Reproducible build, program, artifact, and normalized reporting**
- [ ] **FPGA Increment 8 — Timing-closure feedback and bounded design-space exploration**
- [ ] **FPGA Increment 9 — Vendor primitive/IP abstraction and debug instrumentation**
- [ ] **FPGA Increment 10 — Board bring-up, IP packaging, HIL, and ecosystem qualification**

## Digital Verification Track — blocked by Foundation; numbering restarts

Nodal HVL is canonical. Native/open-source execution and generated UVM are sibling projections of one Verification Semantic IR; generated UVM is not the simulation foundation.

- [ ] **Digital Verification Increment 1 — Nodal HVL native digital simulation vertical slice**
- [ ] **Digital Verification Increment 2 — Scenarios, sequences, constrained stimulus, and replay**
- [ ] **Digital Verification Increment 3 — Agents, drivers, monitors, scoreboards, and reference models**
- [ ] **Digital Verification Increment 4 — Functional coverage and verification reporting**
- [ ] **Digital Verification Increment 5 — Properties, protocol checks, and register-model verification**
- [ ] **Digital Verification Increment 6 — Verification SystemVerilog and digital UVM generation**
- [ ] **Digital Verification Increment 7 — Commercial simulator profiles**
- [ ] **Digital Verification Increment 8 — Cross-backend semantic parity**
- [ ] **Digital Verification Increment 9 — Reusable digital VIP qualification**
- [ ] **Digital Verification Increment 10 — Scale, performance, compatibility, and verification release gate**

## Analog/Mixed-Signal Verification Track — blocked by Foundation; numbering restarts

This track is separate from Digital Verification but reuses its target-neutral transaction/component concepts and the Foundation AMS semantics. UVM-MS generation is a backend, not native mixed-signal simulation.

- [ ] **AMS Verification Increment 1 — Nodal HVL native mixed-signal simulation vertical slice**
- [ ] **AMS Verification Increment 2 — Analog/mixed-signal agents, drivers, monitors, and scoreboards**
- [ ] **AMS Verification Increment 3 — PVT, sweeps, stochastic stimulus, and deterministic replay**
- [ ] **AMS Verification Increment 4 — Analog measurements and functional coverage**
- [ ] **AMS Verification Increment 5 — Mixed-signal properties and register/control interaction**
- [ ] **AMS Verification Increment 6 — UVM-MS generation from Verification IR**
- [ ] **AMS Verification Increment 7 — Commercial mixed-signal simulator profiles**
- [ ] **AMS Verification Increment 8 — Native versus UVM-MS semantic parity**
- [ ] **AMS Verification Increment 9 — Reusable UVM-MS VIP qualification**
- [ ] **AMS Verification Increment 10 — Scale, portability, and mixed-signal verification release gate**

"""
roadmap = replace_once(roadmap, anchor, addition + anchor, "dependent-track insertion")

ref_anchor = "- Accellera Verilog-AMS standards: <https://www.accellera.org/downloads/standards/v-ams>\n"
refs = (
    "- IEEE SystemVerilog 1800-2023 via Accellera/IEEE: <https://www.accellera.org/downloads/ieee>\n"
    "- Accellera UVM / IEEE 1800.2 reference implementation: <https://www.accellera.org/downloads/standards/uvm>\n"
    "- Accellera UVM-MS 1.0: <https://www.accellera.org/downloads/standards/uvm-ms>\n"
)
if refs not in roadmap:
    roadmap = replace_once(roadmap, ref_anchor, ref_anchor + refs, "verification references")
ROADMAP.write_text(roadmap, encoding="utf-8")

arch = ARCH.read_text(encoding="utf-8")
row22 = "| [0022](0022-layered-continuous-time-hybrid-dae-architecture.md) | Preserve source analog intent through layered semantic, topology, hybrid equation, analysis, and solver representations with explicit analog islands, state, events, capabilities, and model-validity evidence. |\n"
row23 = "| [0023](0023-unified-hvl-native-sim-uvm-uvmms-architecture.md) | Author verification once in target-neutral Nodal HVL/Verification IR, execute it through the native simulation runtime or project it to UVM/UVM-MS, and isolate simulator/vendor differences in thin capability profiles. |\n"
if row23 not in arch:
    arch = replace_once(arch, row22, row22 + row23, "ADR 0023 row")
para22 = "ADR 0022 specializes the analog semantic, AMS backend, optimization, simulator-adapter, and quality-gate boundaries for continuous-time compilation. It keeps source constructs, topology, hybrid equations, analysis projections, solver callbacks, and target HDL distinct while preserving stable state, event, noise, environment, capability, validity, and source evidence.\n"
para23 = "\nADR 0023 specializes the simulation, Interface/Register, property, AMS, plugin/tool-adapter, and generated-language boundaries for verification. Nodal HVL and Verification Semantic IR remain authoritative; native/open-source simulation and generated UVM/UVM-MS are capability-checked sibling projections with vendor differences isolated in thin profiles.\n"
if para23 not in arch:
    arch = replace_once(arch, para22, para22 + para23, "ADR 0023 paragraph")
ARCH.write_text(arch, encoding="utf-8")

required = (
    "**Revision:** 1.17",
    "# Foundation track — Incremental roadmap",
    "Foundation Increment 143",
    "Foundation Increment 149",
    "FPGA Productivity Track — blocked by Foundation; numbering restarts",
    "Digital Verification Track — blocked by Foundation; numbering restarts",
    "Analog/Mixed-Signal Verification Track — blocked by Foundation; numbering restarts",
    "Digital Verification Increment 10",
    "AMS Verification Increment 10",
    PLAN,
)
for fragment in required:
    if fragment not in roadmap:
        raise SystemExit(f"roadmap lacks {fragment!r}")

if "[x] **Foundation Increment 143" in roadmap:
    raise SystemExit("roadmap update must not claim comment implementation complete")
if "[x] **FPGA Increment" in roadmap or "[x] **Digital Verification Increment" in roadmap or "[x] **AMS Verification Increment" in roadmap:
    raise SystemExit("dependent tracks must remain blocked/unchecked")

for path in (ROADMAP, ARCH):
    text = path.read_text(encoding="utf-8")
    if not text.endswith("\n"):
        raise SystemExit(f"{path} lacks final newline")

print("Foundation/dependent track roadmap materialization passed")
