#!/usr/bin/env python3
"""Apply the deferred user-authored formal-verification roadmap update."""

from __future__ import annotations

import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ROADMAP = ROOT / "docs/roadmap/nodal-development-todo.md"


def replace_once(text: str, old: str, new: str, subject: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(
            f"{subject}: expected exactly one anchor, found {count}: {old[:120]!r}"
        )
    return text.replace(old, new, 1)


def update_roadmap() -> None:
    text = ROADMAP.read_text(encoding="utf-8")
    text = replace_once(text, "**Revision:** 1.7", "**Revision:** 1.8", "roadmap revision")

    fixed_anchor = (
        "- Verify generated pure-digital HDL through a pinned open-source matrix using "
        "Verilator, Icarus Verilog, Yosys, SBY, and optional cocotb interoperability."
    )
    fixed_extra = "\n" + "\n".join(
        (
            "- Keep future user-authored formal properties target-neutral and domain-aware in Nodal IR; do not make raw SVA strings, SBY files, or one solver define public semantics.",
            "- Separate formal property authoring, target lowering, harness generation, and proof-engine execution so formal-only constructs cannot silently alter synthesizable behavior or ordinary simulation.",
            "- Require explicit property IDs, clock/reset semantics, assumption scope, symbolic environment, proof task, result state, source mapping, vacuity/constraint evidence, and counterexample provenance before reporting a formal result.",
        )
    )
    text = replace_once(text, fixed_anchor, fixed_anchor + fixed_extra, "fixed formal direction")

    public_anchor = (
        "- Keep Scala/native in-process plugins trusted and explicitly enabled; prefer "
        "process isolation for external tools and long-lived transform/backend integrations."
    )
    public_extra = "\n" + "\n".join(
        (
            "- Reserve a separately versioned future formal-verification API for assert/assume/cover, sampled history, symbolic values, harnesses, contracts, and proof tasks; exact names remain deferred to its design gate.",
            "- Keep Scala runtime assertions, simulation assertions, formal properties, and explicitly synthesized checkers distinct unless a frozen inclusion policy intentionally shares one invariant.",
            "- Keep proof-engine options behind normalized task/adaptor contracts; installing a formal adapter or property library never executes a proof or changes `Backend.Auto`.",
        )
    )
    text = replace_once(text, public_anchor, public_anchor + public_extra, "public formal direction")

    formal_section = """## Future formal-verification architecture

The binding architecture is [ADR 0014](../architecture/0014-target-neutral-formal-verification.md). The deferred public API, property-IR, harness, task, adapter, evidence, replay, and conformance plan is in [`formal-verification-v0.1-plan.md`](formal-verification-v0.1-plan.md), with a machine-readable candidate in [`formal-verification-v0.1-surface.json`](formal-verification-v0.1-surface.json).

Nodal adopts:

> **Author properties in Nodal semantics, preserve them in typed IR, lower only to declared tool capabilities, and retain proof and counterexample evidence.**

The existing architecture is already scalable because MLIR is authoritative; clock/reset domains, CDC/RDC, protocols, parameters, effects, and source locations are explicit; the digital backend has a formal profile; tool adapters are versioned and isolated; and proof evidence participates in manifests and caches.

The remaining future-facing contract is the user-authored property layer. It is intentionally deferred and must remain independent of SVA and SBY spelling. The future gate covers:

- assert, assume, cover, property IDs/groups, and explicit simulation/formal inclusion;
- lexical or explicit clock domains, sampled edges, reset enable/disable, and history validity;
- `past`/edge/change/stability/init/history-validity operations and a bounded typed temporal subset;
- symbolic sequence/constants, initial assumptions, fairness, parameter cases, and legal environment contracts;
- sidecar or embedded harnesses, stable verification exports, memory/black-box/external-operation models, and compositional require/ensure contracts;
- BMC, prove/induction, cover, and capability-gated liveness tasks through pluggable formal adapters;
- per-property proven/failed/covered/inconclusive/unsupported/timeout/tool-error states;
- vacuity, over-constraint, assumption, coverage, counterexample, source-map, and replay evidence.

Nodal may selectively reuse CIRCT `verif` and `ltl` operations when the pinned revision preserves the frozen Nodal semantics. Nodal-owned formal operations remain valid where CIRCT or a selected runner lacks a required capability.

Increment 67 remains limited to Yosys/SBY integration, compiler-generated hooks, equivalence, and core property suites. It must preserve the target-neutral property seam but does not freeze or implement a user-authored formal API. The deferred formal phase may be pulled forward once its listed prerequisites are complete; it is not required for the initial core preview or the AMS-to-FPGA milestone.


"""
    text = replace_once(
        text,
        "## AMS-to-FPGA approximation architecture",
        formal_section + "## AMS-to-FPGA approximation architecture",
        "formal architecture section",
    )

    core_old = (
        "- `core/` contains the language/API, plugin SPI and resolver, construction frontend, "
        "MLIR bridge/compiler, diagnostics, built-in backends, simulation API, adapters, and mandatory tests."
    )
    core_new = (
        "- `core/` contains the language/API, plugin SPI and resolver, construction frontend, "
        "MLIR bridge/compiler, diagnostics, built-in backends, simulation API, future formal "
        "property/harness/task services, adapters, and mandatory tests."
    )
    text = replace_once(text, core_old, core_new, "core boundary formal readiness")

    structure_old = """│   │   ├── sim/                   # Simulation/regression API
│   │   └── testkit/               # Core fixtures and test support"""
    structure_new = """│   │   ├── sim/                   # Simulation/regression API
│   │   ├── formal/                # Deferred property, harness, task, and trace services
│   │   └── testkit/               # Core fixtures and test support"""
    text = replace_once(text, structure_old, structure_new, "target structure formal module")

    m3_old = (
        "- **M3 — Digital/AMS preview:** implicit-domain digital state, automatic fixed/valid/elastic "
        "pipelines, portable Verilog with open-source simulation/synthesis/formal verification, "
        "CDC/RDC-safe clock/reset architecture, mixed-signal crossings, and Verilog-AMS emission."
    )
    m3_new = (
        "- **M3 — Digital/AMS preview:** implicit-domain digital state, automatic fixed/valid/elastic "
        "pipelines, portable Verilog with open-source simulation/synthesis/equivalence and "
        "compiler-generated formal verification, CDC/RDC-safe clock/reset architecture, "
        "mixed-signal crossings, and Verilog-AMS emission."
    )
    text = replace_once(text, m3_old, m3_new, "M3 formal scope")

    m5 = (
        "- **M5 — FPGA-accelerated AMS validation:** explicit sampled/fixed-point approximation, "
        "four-level reference evidence, open FPGA implementation, HIL runtime, and a published "
        "capability/limitations matrix."
    )
    m6 = (
        "\n- **M6 — User-authored formal verification extension:** frozen formal property API, "
        "target-neutral property IR, compositional harness/contracts, pluggable proof engines, "
        "vacuity/coverage, typed counterexample replay, property libraries, and conformance evidence."
    )
    text = replace_once(text, m5, m5 + m6, "M6 formal milestone")

    inc67_old = """- [ ] **Increment 67 — Yosys synthesis/equivalence and SBY formal verification**
  - Pin and integrate Yosys, SBY, and selected solvers. Run hierarchy/process/memory checks, target-neutral synthesis, inferred-latch/loop/black-box diagnostics, normalized netlist emission, statistics, and parameter elaboration matrices.
  - Add RTL-to-optimized/netlist equivalence, including latency-aware fixed-pipeline and protocol-aware elastic checks.
  - Add bounded/unbounded safety, cover, and selected liveness property suites for registers, resets, `Valid`/`Stream`, FIFOs, handshakes, synchronizers, CDC/RDC wrappers, and automatic pipelines. Retain traces and counterexamples as CI evidence.
"""
    inc67_new = """- [ ] **Increment 67 — Yosys synthesis/equivalence and core SBY formal-readiness infrastructure**
  - Pin and integrate Yosys, SBY, and selected solvers. Run hierarchy/process/memory checks, target-neutral synthesis, inferred-latch/loop/black-box diagnostics, normalized netlist emission, statistics, and parameter elaboration matrices.
  - Add RTL-to-optimized/netlist equivalence, including latency-aware fixed-pipeline and protocol-aware elastic checks.
  - Add compiler-generated bounded/unbounded safety, cover, and selected liveness property suites for registers, resets, `Valid`/`Stream`, FIFOs, handshakes, synchronizers, CDC/RDC wrappers, and automatic pipelines. Retain traces and counterexamples as CI evidence.
  - Preserve stable property IDs, source maps, domain/reset/parameter metadata, normalized tasks, and adapter evidence in forms compatible with [ADR 0014](../architecture/0014-target-neutral-formal-verification.md). Use portable hooks or sidecar harnesses without freezing a user-authored formal API or binding Nodal semantics to SVA/SBY syntax.
"""
    text = replace_once(text, inc67_old, inc67_new, "Increment 67 formal readiness")

    phase7 = """## Phase 7 — Deferred, independently schedulable user-authored formal verification

This phase is deliberately outside the initial core, plugin, and AMS-to-FPGA milestones. It may be pulled forward after Increments 15, 19-23, 54-67, 82, and 87 provide the public semantic, IR, backend, core-proof, compiler-plugin, and formal-adapter prerequisites. No formal implementation is performed by this roadmap update.

- [ ] **Increment 109 — Formal verification architecture gate and public API v0.1 contracts**
  - Use [ADR 0014](../architecture/0014-target-neutral-formal-verification.md), [`formal-verification-v0.1-plan.md`](formal-verification-v0.1-plan.md), and [`formal-verification-v0.1-surface.json`](formal-verification-v0.1-surface.json) as the mandatory architecture and candidate.
  - Compile and compare concise formal context, assert/assume/cover, property IDs/groups, sampled-value operators, bounded temporal forms, symbolic values, harness, contract, and task configuration candidates.
  - Freeze clock/reset, combinational, cross-domain, parameter, memory, black-box, assumption-scope, vacuity, result-state, source-map, and simulation/formal-inclusion semantics.
  - Publish `NodalFormalVerification-DG-v0.1.md`, machine-readable frozen API/task surfaces, compatibility policy, stable diagnostics, and positive/negative external-consumer fixtures. Keep execution/lowering inert until approval.

- [ ] **Increment 110 — Target-neutral formal property IR, verifier, and lowering framework**
  - Implement formal test/harness, property, symbolic-value, sampled-history, bounded-temporal, contract, enable/reset, and formal-model operations with stable IDs and source maps.
  - Selectively reuse CIRCT `verif` and `ltl` through verified conversions; retain Nodal-owned operations where semantics differ or capabilities are missing.
  - Add property/domain/reset/type/capability verification, deterministic parse/print, normalized reports, and portable immediate/monitor/sidecar lowering.
  - Prove formal-only constructs cannot affect ordinary synthesis/simulation artifacts without explicit inclusion.

- [ ] **Increment 111 — Formal harnesses, symbolic environments, compositional contracts, and model abstractions**
  - Implement DUT wrappers, symbolic sequence/constants, initial assumptions, legal clock/reset generation, stable verification exports, property groups, and reusable harness composition.
  - Implement exact or explicitly abstracted memory, external-operation, and black-box formal models with soundness and waiver reporting.
  - Implement require/ensure contract checking/application, assume-guarantee composition, parameter matrices/envelopes, conservative multi-clock handling, and hidden-assumption/over-constraint diagnostics.

- [ ] **Increment 112 — Pluggable proof execution, proof modes, and normalized evidence**
  - Implement SBY/Yosys as the required open-source formal adapter through the common process/evidence protocol, including BMC, prove/induction, cover, selected liveness, solver, timeout, and resource controls.
  - Add an out-of-tree mock or second formal-adapter conformance fixture so public semantics are not coupled to SBY files.
  - Normalize per-property/task results, commands, logs, traces, proof metadata, source maps, cache keys, and reproduction commands; reject unsupported capabilities before execution.
  - Preserve inconclusive, timeout, cancellation, unsupported, and tool-error states without reporting them as proof success.

- [ ] **Increment 113 — Property libraries, vacuity/coverage, counterexample replay, documentation, and conformance**
  - Publish passive property libraries for protocols, FIFOs, pipelines, resets, CDC/RDC wrappers, memories, and common control structures using only public APIs.
  - Implement assumption consistency, antecedent/scenario cover goals, supported vacuity checks, over-constraint reports, and defined property/scenario coverage metrics.
  - Replay normalized counterexamples/covers through the Scala simulation API with typed transactions, domain timelines, source annotations, and VCD/FST waveforms.
  - Publish tutorials, adapter/property-library author guides, capability matrices, known limitations, conformance suites, and the M6 reproducible formal-verification extension package.


"""
    text = replace_once(
        text,
        "## Deferred reusable library roadmap",
        phase7 + "## Deferred reusable library roadmap",
        "deferred formal phase",
    )

    text = replace_once(
        text,
        "No official reusable model/component library or production plugin is implemented by Increments 0-108.",
        "No official reusable model/component library or production plugin is implemented by Increments 0-113.",
        "deferred library range",
    )

    ref_anchor = "- CIRCT ESI channel buffers: <https://circt.llvm.org/docs/Dialects/ESI/>"
    ref_extra = "\n" + "\n".join(
        (
            "- SpinalHDL formal verification: <https://spinalhdl.github.io/SpinalDoc-RTD/master/SpinalHDL/Formal%20verification/index.html>",
            "- CIRCT Verif dialect: <https://circt.llvm.org/docs/Dialects/Verif/>",
            "- CIRCT LTL dialect: <https://circt.llvm.org/docs/Dialects/LTL/>",
        )
    )
    text = replace_once(text, ref_anchor, ref_anchor + ref_extra, "formal references")

    ROADMAP.write_text(text, encoding="utf-8")


def update_architecture_index() -> None:
    path = ROOT / "docs/architecture/README.md"
    text = path.read_text(encoding="utf-8")
    row = (
        "| [0013](0013-structured-hdl-optimization-pass-architecture.md) | Use structured "
        "digital/Verilog-A/Verilog-AMS target IR, explicit locked pass pipelines, declared "
        "semantic effects, mandatory re-verification, and digital/AMS-appropriate proof evidence. |"
    )
    addition = row + "\n" + (
        "| [0014](0014-target-neutral-formal-verification.md) | Preserve future user-authored "
        "formal properties in target-neutral, domain-aware IR and execute them through "
        "capability-checked proof-engine adapters with retained evidence. |"
    )
    text = replace_once(text, row, addition, "architecture index ADR 0014")

    paragraph = (
        "ADR 0012 owns general plugin discovery, resolution, loading, trust, lifecycle, and "
        "provenance. ADR 0013 layers the target-HDL-specific structured representations, pass "
        "profiles, preservation rules, and proof obligations on that common plugin foundation. "
        "Installing either a plugin or pass is inert until the project resolves and explicitly "
        "selects it in a locked plan."
    )
    paragraph_new = paragraph + "\n\n" + (
        "ADR 0014 specializes ADR 0010 and ADR 0012 for deferred user-authored formal "
        "verification: Nodal owns property/domain/reset/task semantics, while CIRCT lowering, "
        "SBY/Yosys, and future engines remain capability-checked implementation and adapter layers."
    )
    text = replace_once(text, paragraph, paragraph_new, "architecture relationship paragraph")
    path.write_text(text, encoding="utf-8")


def update_digital_adr() -> None:
    path = ROOT / "docs/architecture/0010-digital-verilog-open-source-verification.md"
    text = path.read_text(encoding="utf-8")
    anchor = (
        "Nodal may emit portable assertions/assumptions/covers or sidecar formal harnesses. "
        "The formal profile stays within the supported open-source frontend subset and reports "
        "unsupported property syntax explicitly."
    )
    addition = anchor + "\n\n" + """### Deferred user-authored formal property architecture

[ADR 0014](0014-target-neutral-formal-verification.md) owns the future public property, clock/reset, sampled-history, symbolic-environment, harness/contract, task, vacuity, counterexample, and replay semantics. Formal properties remain target-neutral Nodal IR and may selectively lower to CIRCT `verif`/`ltl`, portable Yosys-compatible hooks, sidecar harnesses, or future capability-gated targets.

Increment 67 provides compiler-generated formal hooks, equivalence, core property suites, SBY/Yosys integration, source mapping, and evidence readiness only. It must not freeze a public formal DSL or make SVA/SBY syntax part of Nodal semantics. The deferred public formal phase is independent of the initial core and AMS milestones.
"""
    text = replace_once(text, anchor, addition, "digital ADR formal specialization")
    path.write_text(text, encoding="utf-8")


def update_digital_plan() -> None:
    path = ROOT / "docs/roadmap/digital-verilog-open-source-verification-plan.md"
    text = path.read_text(encoding="utf-8")
    text = replace_once(
        text,
        "**Public API freeze:** Increment 15 unified v0.3 gate",
        "**Public API freeze:** Increment 15 unified v0.3 backend-profile gate  \n**Future user-authored formal gate:** Increment 109",
        "digital plan formal gate metadata",
    )

    sby_anchor = "## SBY formal plan\n\nRequired formal tasks include:"
    sby_new = (
        "## SBY formal plan\n\nIncrement 67 covers compiler-generated and core-library formal "
        "readiness; it does not freeze a user-authored formal API.\n\nRequired internal formal tasks include:"
    )
    text = replace_once(text, sby_anchor, sby_new, "SBY internal formal scope")

    future_section = """## Future user-authored formal verification boundary

[ADR 0014](../architecture/0014-target-neutral-formal-verification.md), [`formal-verification-v0.1-plan.md`](formal-verification-v0.1-plan.md), and [`formal-verification-v0.1-surface.json`](formal-verification-v0.1-surface.json) reserve the deferred public formal capability.

The architecture keeps these layers separate:

- target-neutral property authoring and domain/reset semantics;
- Nodal/CIRCT formal IR and capability verification;
- portable immediate/monitor/sidecar or future SVA lowering;
- SBY/Yosys and future commercial/research proof-engine adapters;
- normalized proof, vacuity, coverage, counterexample, and replay evidence.

Increment 67 must retain stable property/source/domain/task metadata and proof-adapter evidence for compiler-generated properties, but it must not expose raw SVA strings, SBY files, or engine options as public language semantics. User-authored assert/assume/cover, sampled history, symbolic values, harnesses, contracts, and task APIs remain inert until Increment 109's design gate is approved.

"""
    text = replace_once(
        text,
        "## Differential regression",
        future_section + "## Differential regression",
        "digital plan future formal section",
    )

    text = replace_once(
        text,
        "### Synthesis, equivalence, and formal",
        "### Synthesis, equivalence, and core formal readiness",
        "digital delivery formal heading",
    )

    deferred_delivery = """### Deferred user-authored formal verification

After the public formal gate, implement target-neutral properties, harness/contracts, symbolic values, sampled history, bounded temporal semantics, pluggable proof tasks, vacuity/coverage, typed counterexample replay, and property-library conformance through Increments 109-113. This work is not a prerequisite for the initial digital/AMS preview.

"""
    text = replace_once(
        text,
        "## Exit criteria",
        deferred_delivery + "## Exit criteria",
        "digital plan deferred formal delivery",
    )
    path.write_text(text, encoding="utf-8")


def update_json_surfaces() -> None:
    path = ROOT / "docs/roadmap/digital-backend-v0.3-surface.json"
    data = json.loads(path.read_text(encoding="utf-8"))
    data["roadmap_revision"] = "1.8"
    data["roadmap_last_increment"] = 113
    data.pop("formal_freeze_increment", None)
    data["backend_profile_freeze_increment"] = 15
    data["user_authored_formal_gate_increment"] = 109
    data["documents"]["formal_architecture"] = (
        "docs/architecture/0014-target-neutral-formal-verification.md"
    )
    data["documents"]["formal_plan"] = "docs/roadmap/formal-verification-v0.1-plan.md"
    data["documents"]["formal_surface"] = (
        "docs/roadmap/formal-verification-v0.1-surface.json"
    )
    data["formal_readiness"] = {
        "increment_67_scope": "compiler-generated hooks, equivalence, core property suites, SBY/Yosys integration, traces, source maps, and evidence",
        "public_property_api_in_increment_67": False,
        "target_neutral_property_ir_required": True,
        "raw_sva_strings_define_public_semantics": False,
        "sby_files_define_public_semantics": False,
        "proof_engines_use_tool_adapter_plugins": True,
        "deferred_public_increments": [109, 110, 111, 112, 113],
    }
    path.write_text(json.dumps(data, indent=2) + "\n", encoding="utf-8")

    policy_path = ROOT / ".github/branch-policy.json"
    policy = json.loads(policy_path.read_text(encoding="utf-8"))
    policy["milestone_promotions"]["M6"] = "after Increment 113"
    policy_path.write_text(json.dumps(policy, indent=2) + "\n", encoding="utf-8")


def validate() -> None:
    for path in sorted((ROOT / "docs/roadmap").glob("*.json")):
        json.loads(path.read_text(encoding="utf-8"))

    text = ROADMAP.read_text(encoding="utf-8")
    numbers = [
        int(value)
        for value in re.findall(
            r"^- \[[ x]\] \*\*Increment (\d+) —",
            text,
            flags=re.MULTILINE,
        )
    ]
    if numbers != list(range(114)):
        raise SystemExit(f"increment numbering mismatch: {numbers[-15:]}")

    for fragment in (
        "**Revision:** 1.8",
        "Increment 67 — Yosys synthesis/equivalence and core SBY formal-readiness infrastructure",
        "Increment 109 — Formal verification architecture gate and public API v0.1 contracts",
        "Increment 113 — Property libraries, vacuity/coverage, counterexample replay, documentation, and conformance",
        "M6 — User-authored formal verification extension",
        "No official reusable model/component library or production plugin is implemented by Increments 0-113.",
    ):
        if fragment not in text:
            raise SystemExit(f"roadmap lacks required fragment: {fragment}")

    print("future formal-verification roadmap update validated")


def main() -> None:
    update_roadmap()
    update_architecture_index()
    update_digital_adr()
    update_digital_plan()
    update_json_surfaces()
    validate()


if __name__ == "__main__":
    main()
