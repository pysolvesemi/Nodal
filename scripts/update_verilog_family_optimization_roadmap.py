#!/usr/bin/env python3
"""Apply the Verilog-family optimization-pass roadmap revision."""

from __future__ import annotations

import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ROADMAP = ROOT / "docs/roadmap/nodal-development-todo.md"

# The original preparation workflow replaces this candidate row with the exact
# row currently present in the architecture index before this script runs.
ARCHITECTURE_0012_ROW = "| [0012](0012-versioned-capability-plugin-architecture.md) | Use a manifest-first, versioned typed-capability graph with local design hosts, deterministic phases, lockfiles, native/process compatibility, and separate plugin/library boundaries. |"


def replace_once(text: str, old: str, new: str, subject: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(
            f"{subject}: expected exactly one anchor, found {count}: {old[:100]!r}"
        )
    return text.replace(old, new, 1)


def insert_before(text: str, anchor: str, addition: str, subject: str) -> str:
    return replace_once(text, anchor, addition + anchor, subject)


def shift_increment_lines(
    text: str,
    threshold: int = 85,
    delta: int = 4,
) -> str:
    """Shift numbers only on prose lines that explicitly mention Increment(s)."""

    def rewrite(line: str) -> str:
        if "Increment" not in line:
            return line

        def replace_number(match: re.Match[str]) -> str:
            value = int(match.group(0))
            if value >= threshold:
                return str(value + delta)
            return str(value)

        return re.sub(r"(?<![\w.])\d+(?![\w.])", replace_number, line)

    return "\n".join(rewrite(line) for line in text.split("\n"))


def update_roadmap() -> None:
    text = ROADMAP.read_text(encoding="utf-8")
    text = replace_once(text, "**Revision:** 1.6", "**Revision:** 1.7", "revision")
    text = shift_increment_lines(text)

    fixed_anchor = (
        "- Make plugin graph, artifact, option, phase, pass, process-protocol, trust, "
        "and toolchain hashes part of deterministic build manifests and cache keys."
    )
    fixed_addition = "\n" + "\n".join(
        (
            "- Support plug-and-play Verilog-family optimization through versioned typed IR and declared extension points, not arbitrary semantic text filters.",
            "- Require semantic passes to declare IR versions, target family/profile, pass kind/effect, analyses, ordering, determinism, source-map behavior, and proof obligations.",
            "- Preserve parameters, module identity, widths/signs, domains/CDC/RDC, protocols/latency, pipeline transactions, quantities, analog contributions/events, and mixed-signal boundaries unless an explicitly gated effect permits change.",
            "- Execute semantic optimization transactionally: accept a candidate only after structural, semantic, equivalence/differential/formal, source-map, and capability validation.",
            "- Integrate Yosys scripts/plugins as pinned external optimization engines with re-import and equivalence evidence; external tools do not define Nodal semantics.",
        )
    )
    text = replace_once(
        text,
        fixed_anchor,
        fixed_anchor + fixed_addition,
        "fixed optimization direction",
    )

    public_anchor = (
        "- Keep Scala/native in-process plugins trusted and explicitly enabled; prefer "
        "process isolation for external tools and long-lived transform/backend integrations."
    )
    public_addition = "\n" + "\n".join(
        (
            "- Configure optimization pipelines through compiler/emission options and locked project profiles, not hidden model-library side effects.",
            "- Add a versioned optimization-pass configuration/evidence surface centered on `OptimizationPipeline`, `PassRef`, stable pass IDs, target families, stages, effects, and verification policies.",
            "- Restrict direct post-emission text plugins to non-semantic formatting and packaging; reparse and fully verify any legacy semantic text transform before transactional acceptance.",
        )
    )
    text = replace_once(
        text,
        public_anchor,
        public_anchor + public_addition,
        "public optimization direction",
    )

    architecture_section = """## Verilog-family optimization pass architecture

The binding architecture is [ADR 0013](../architecture/0013-versioned-verilog-family-optimization-passes.md). The complete target-IR, pass, extension-point, proof, Yosys-integration, rollback, source-map, and conformance plan is in [`verilog-family-optimization-pass-plan.md`](verilog-family-optimization-pass-plan.md), with a machine-readable candidate in [`verilog-family-optimization-pass-surface.json`](verilog-family-optimization-pass-surface.json).

Nodal adopts:

> **Optimize the highest valid typed representation, declare every semantic effect, verify every mutation, and commit only a validated candidate.**

Optimization layers are target-neutral Nodal IR, digital Nodal/CIRCT IR, versioned typed Verilog/Verilog-A/Verilog-AMS target IR, and emitted text. Semantic changes are prohibited in direct text filters; a legacy text optimizer must run out of process, be reparsed, fully verified, source-mapped, and accepted transactionally.

A pass has a stable ID/version and declares its input/output representation, extension point, target family/profile, kind, semantic effect, analyses, ordering, determinism, trust/isolation, source-map behavior, and proof obligations. Ambiguous non-commuting order is an error.

Default passes preserve symbolic parameters and one-module-per-structure generation, finite-width semantics, clock/reset and CDC/RDC, protocols and published latency, automatic-pipeline transaction identity, quantities/disciplines, analog state/contributions/events, mixed-signal boundaries, memories/effects, profile legality, stable names, and source origins. Specialization is explicit and separately manifested.

Digital verification may use CIRCT LEC, Yosys equivalence, Verilator/Icarus differential simulation, SBY, parameter-envelope matrices, and latency/protocol-aware checks. Verilog-A/AMS optimization uses typed equation/contribution/event/connect verification, narrowly scoped symbolic equivalence where sound, OpenVAF compilation, and bounded differential simulation with explicit envelopes and tolerances.

Yosys scripts and dynamically loaded Yosys plugins are invoked through pinned adapters with exact build/plugin/script/order/options hashes and re-import/equivalence evidence. Pass graphs, tools, proof policies, IR hashes, source maps, and evidence participate in lockfiles, cache keys, manifests, and release provenance.

"""
    text = insert_before(
        text,
        "## Core, plugin, and future library boundary",
        architecture_section,
        "optimization architecture section",
    )

    text = replace_once(
        text,
        "## Phase 5 — Plugins, extensibility, scale, documentation, and release",
        "## Phase 5 — Plugins, optimization passes, extensibility, scale, documentation, and release",
        "phase 5 title",
    )

    increments = """- [ ] **Increment 85 — Verilog-family optimization pass architecture gate and contracts**
  - Use [ADR 0013](../architecture/0013-versioned-verilog-family-optimization-passes.md), [`verilog-family-optimization-pass-plan.md`](verilog-family-optimization-pass-plan.md), and [`verilog-family-optimization-pass-surface.json`](verilog-family-optimization-pass-surface.json) as the mandatory architecture and candidate.
  - Compile pass IDs/descriptors, target families, typed IR versions, extension points, pass kinds/effects, profiles, ordering, analysis preservation/invalidation, proof policies, evidence, and compiler/emission configuration candidates.
  - Freeze parameterization, module identity, width/sign, clock/reset/CDC/RDC, protocol/latency, pipeline, quantity/effect, analog/event, mixed-signal, source-map, determinism, trust, lockfile, specialization, and transactional rollback rules.
  - Publish `NodalVerilogFamilyOptimizationPass-DG-v0.1.md`, schemas, diagnostics, and positive/negative external-plugin fixtures. Keep pass execution inert until approval.

- [ ] **Increment 86 — Versioned target HDL IR and transactional pass manager**
  - Implement deterministic parse/print/verify for approved Verilog, Verilog-A, and Verilog-AMS target representations.
  - Preserve target constructs, symbolic parameters/generate, analog/mixed-signal semantics, stable node IDs, origin chains, capability annotations, and deterministic round trips.
  - Implement pass registration, extension-point validation, total-order resolution, analysis preservation/invalidation, immutable candidate execution, rollback, tracing, evidence, provenance, and cache keys.

- [ ] **Increment 87 — Digital Verilog optimization plugins and Yosys interoperability**
  - Implement target-neutral/digital/Verilog passes at approved points, including parameter/generate-safe structural optimization and portable-profile legalization.
  - Integrate pinned Yosys scripts and dynamically loaded Yosys plugins through the external adapter protocol with exact tool/plugin/script/order/options/input/output hashes and re-import results.
  - Require Nodal/CIRCT verification plus configured equivalence, differential simulation, SBY, parameter-envelope, pipeline-latency, protocol, reset, and CDC/RDC evidence before accepting replacements.
  - Publish reference digital passes, canonical-versus-implementation artifact rules, and invalid-pass fixtures.

- [ ] **Increment 88 — Verilog-A/Verilog-AMS optimization plugins and bounded validation**
  - Implement typed target passes for approved canonicalization, optimization, legalization, instrumentation, and formatting points.
  - Preserve units/dimensions, natures/disciplines, nodes/branches, state/initialization, contributions/events, parameters/hierarchy, connect/resolution, domains, protocols, sampling/drive boundaries, profiles, and source origins.
  - Add narrowly scoped symbolic/equation equivalence where sound, normalized equation evidence, OpenVAF compilation, ngspice/optional second-tool differential regression, digital-partition equivalence, and explicit envelope/tolerance/limitation reporting.
  - Publish reference Verilog-A/AMS passes and negative proof, source-map, parameterization, event, and portability fixtures.

"""
    text = insert_before(
        text,
        "- [ ] **Increment 89 — Versioned IR and bridge compatibility**",
        increments,
        "optimization increments",
    )

    old_m4 = (
        "- **M4 — Scalable core release:** packaged compiler, complete reference, frozen "
        "plugin SPI, deterministic extension graph, conformance kit, library-author contract, "
        "and compatibility policy."
    )
    new_m4 = (
        "- **M4 — Scalable core release:** packaged compiler, complete reference, frozen "
        "plugin and Verilog-family optimization-pass SPIs, deterministic extension/pass "
        "graphs, transactional verification, conformance kits, library-author contract, "
        "and compatibility policy."
    )
    text = replace_once(text, old_m4, new_m4, "M4 milestone")

    ref_anchor = (
        "- Yosys Verilog frontend: "
        "<https://yosyshq.readthedocs.io/projects/yosys/en/stable/cmd/index_frontends.html>"
    )
    extra_refs = "\n" + "\n".join(
        (
            "- Yosys pass framework: <https://github.com/YosysHQ/yosys>",
            "- Yosys plugins: <https://github.com/YosysHQ/yosys-plugins>",
            "- CIRCT passes: <https://circt.llvm.org/docs/Passes/>",
            "- MLIR pass plugin API: <https://github.com/llvm/llvm-project/blob/main/mlir/include/mlir/Tools/Plugins/PassPlugin.h>",
        )
    )
    text = replace_once(text, ref_anchor, ref_anchor + extra_refs, "references")
    ROADMAP.write_text(text, encoding="utf-8")


def update_architecture_index() -> None:
    path = ROOT / "docs/architecture/README.md"
    text = path.read_text(encoding="utf-8")
    row = ARCHITECTURE_0012_ROW
    addition = row + "\n" + (
        "| [0013](0013-versioned-verilog-family-optimization-passes.md) | Use versioned "
        "typed IR, declared semantic effects, transactional verification, and retained "
        "provenance for plug-and-play Verilog-family optimization passes. |"
    )
    path.write_text(replace_once(text, row, addition, "architecture index"), encoding="utf-8")


def update_plugin_documents() -> None:
    adr = ROOT / "docs/architecture/0012-versioned-capability-plugin-architecture.md"
    text = shift_increment_lines(adr.read_text(encoding="utf-8"))
    section = """## Verilog-family optimization pass integration

[ADR 0013](0013-versioned-verilog-family-optimization-passes.md) specializes the common resolver, manifest, lockfile, trust, native/process loading, ordering, provenance, caching, and conformance machinery for typed target-family optimization passes. It adds versioned target HDL IR, pass effects/invariants, transactional rollback, family-specific proof policies, Yosys interoperability, and restrictions on semantic text filters.

Optimization passes are compiler or isolated process facets. They are not model libraries and do not execute merely because a reusable model artifact is installed.

"""
    text = insert_before(text, "## Consequences", section, "plugin ADR integration")
    adr.write_text(text, encoding="utf-8")

    plan = ROOT / "docs/roadmap/plugin-spi-v0.1-plan.md"
    text = plan.read_text(encoding="utf-8")
    goal = "- independent packaging, compatibility, trust, provenance, and deterministic caching."
    text = replace_once(
        text,
        goal,
        goal + "\n- a shared foundation for versioned Verilog-family optimization passes defined by ADR 0013.",
        "plugin plan goal",
    )
    section = """## Verilog-family optimization pass integration

ADR 0013 and [`verilog-family-optimization-pass-plan.md`](verilog-family-optimization-pass-plan.md) reuse manifest-first discovery, stable IDs, lockfiles, native/process compatibility, extension-point ordering, trust, provenance, caching, and conformance. Optimization facets additionally declare typed IR versions, target family/profile, pass kind/effect, mandatory invariants, rollback, source-map behavior, and proof obligations.

Yosys-backed passes use the common process-adapter envelope. Loaded plugins, scripts, order, options, tool build, artifacts, and equivalence evidence are locked and retained. Verilog-A/AMS passes use typed target representations and bounded differential evidence rather than unstructured text mutation.

"""
    text = insert_before(text, "## Backend and tool adapters", section, "plugin plan integration")
    plan.write_text(text, encoding="utf-8")


def update_digital_documents() -> None:
    adr = ROOT / "docs/architecture/0010-digital-verilog-open-source-verification.md"
    text = adr.read_text(encoding="utf-8")
    section = """## Plug-in digital optimization

ADR 0013 defines the plug-and-play optimization boundary. Target-neutral and CIRCT passes run before portable-Verilog emission where possible. Pinned Yosys scripts/plugins may replace canonical RTL only after re-import, profile verification, and configured equivalence/formal/differential checks. Canonical RTL and optimized implementation/netlist artifacts remain distinct when structure or parameterization is not preserved.

"""
    adr.write_text(insert_before(text, "## Consequences", section, "digital ADR"), encoding="utf-8")

    plan = ROOT / "docs/roadmap/digital-verilog-open-source-verification-plan.md"
    text = plan.read_text(encoding="utf-8")
    section = """## Plug-in optimization pipelines

Digital optimization uses ADR 0013 and [`verilog-family-optimization-pass-plan.md`](verilog-family-optimization-pass-plan.md). Yosys-backed pipelines are pinned by tool build, plugin hashes, script/order, options, seeds, IR/artifact hashes, and proof evidence. Nodal reparses and verifies optimized output; Yosys does not define Nodal width, reset, domain, protocol, latency, parameter, or source-map semantics.

"""
    plan.write_text(insert_before(text, "## CI tiers", section, "digital plan"), encoding="utf-8")


def update_shifted_documents() -> None:
    for relative in (
        "docs/architecture/0011-ams-fpga-approximation-validation.md",
        "docs/roadmap/ams-fpga-validation-plan.md",
    ):
        path = ROOT / relative
        path.write_text(shift_increment_lines(path.read_text(encoding="utf-8")), encoding="utf-8")


def update_json() -> None:
    roadmap_dir = ROOT / "docs/roadmap"
    for path in sorted(roadmap_dir.glob("*.json")):
        data = json.loads(path.read_text(encoding="utf-8"))
        if "roadmap_revision" in data:
            data["roadmap_revision"] = "1.7"
        path.write_text(json.dumps(data, indent=2) + "\n", encoding="utf-8")

    ams_path = roadmap_dir / "ams-fpga-validation-surface.json"
    data = json.loads(ams_path.read_text(encoding="utf-8"))
    data["formal_gate_increment"] = int(data["formal_gate_increment"]) + 4
    data["increments"] = {
        str(int(key) + 4): value for key, value in data.get("increments", {}).items()
    }
    ams_path.write_text(json.dumps(data, indent=2) + "\n", encoding="utf-8")

    plugin_path = roadmap_dir / "plugin-spi-v0.1-surface.json"
    data = json.loads(plugin_path.read_text(encoding="utf-8"))
    data["verilog_family_optimization"] = {
        "architecture": "docs/architecture/0013-versioned-verilog-family-optimization-passes.md",
        "plan": "docs/roadmap/verilog-family-optimization-pass-plan.md",
        "surface": "docs/roadmap/verilog-family-optimization-pass-surface.json",
        "formal_gate_increment": 85,
        "semantic_text_filters_allowed": False,
    }
    plugin_path.write_text(json.dumps(data, indent=2) + "\n", encoding="utf-8")

    digital_path = roadmap_dir / "digital-backend-v0.3-surface.json"
    data = json.loads(digital_path.read_text(encoding="utf-8"))
    data["optimization_pass_plan"] = {
        "architecture": "docs/architecture/0013-versioned-verilog-family-optimization-passes.md",
        "plan": "docs/roadmap/verilog-family-optimization-pass-plan.md",
        "semantic_post_emit_text_filters": False,
        "yosys_is_external_engine_not_language_semantics": True,
    }
    digital_path.write_text(json.dumps(data, indent=2) + "\n", encoding="utf-8")


def update_branch_policy() -> None:
    path = ROOT / ".github/branch-policy.json"
    data = json.loads(path.read_text(encoding="utf-8"))
    data["milestone_promotions"]["M4"] = "after Increment 98"
    data["milestone_promotions"]["M5"] = "after Increment 108"
    path.write_text(json.dumps(data, indent=2) + "\n", encoding="utf-8")


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
    if numbers != list(range(109)):
        raise SystemExit(f"increment numbering mismatch: {numbers[-12:]}")
    required = (
        "**Revision:** 1.7",
        "Increment 85 — Verilog-family optimization pass architecture gate and contracts",
        "Increment 88 — Verilog-A/Verilog-AMS optimization plugins and bounded validation",
        "Increment 108 — Hardware-in-the-loop runtime, vertical slices, and capability matrix",
    )
    for fragment in required:
        if fragment not in text:
            raise SystemExit(f"roadmap lacks required fragment: {fragment}")
    print("Verilog-family optimization roadmap update validated")


def main() -> None:
    update_roadmap()
    update_architecture_index()
    update_plugin_documents()
    update_digital_documents()
    update_shifted_documents()
    update_json()
    update_branch_policy()
    validate()


if __name__ == "__main__":
    main()
