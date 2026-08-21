#!/usr/bin/env python3
"""Apply the structured target-HDL optimization-pass roadmap revision."""

from __future__ import annotations

import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ROADMAP = ROOT / "docs/roadmap/nodal-development-todo.md"


def map_increment(number: int) -> int:
    if 83 <= number <= 104:
        return number + 4
    return number


def renumber_increment_lines(text: str) -> str:
    def rewrite_line(line: str) -> str:
        if "Increment" not in line:
            return line

        def replace_number(match: re.Match[str]) -> str:
            return str(map_increment(int(match.group(0))))

        return re.sub(r"(?<![\w.])\d+(?![\w.])", replace_number, line)

    return "\n".join(rewrite_line(line) for line in text.split("\n"))


def replace_once(text: str, old: str, new: str, *, subject: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{subject}: expected one anchor, found {count}: {old[:140]!r}")
    return text.replace(old, new, 1)


def update_roadmap() -> None:
    text = ROADMAP.read_text(encoding="utf-8")
    text = replace_once(
        text,
        "**Revision:** 1.6",
        "**Revision:** 1.7",
        subject="roadmap revision",
    )
    text = renumber_increment_lines(text)

    fixed_anchor = (
        "- Make plugin graph, artifact, option, phase, pass, process-protocol, trust, and "
        "toolchain hashes part of deterministic build manifests and cache keys."
    )
    fixed_addition = fixed_anchor + "\n" + "\n".join(
        (
            "- Support plug-and-play Verilog, Verilog-A, and Verilog-AMS optimization through structured target-IR passes layered on the plugin SPI; arbitrary semantic raw-text filters are not an optimization contract.",
            "- Require every target-HDL pass to declare target/profile/IR compatibility, semantic preservation and analysis invalidation, parameter/hierarchy/source-map effects, determinism, and proof/validation obligations.",
            "- Keep backend selection separate from optimization profile selection. Installing a pass never executes it, changes `Backend.Auto`, or alters generated hardware without an explicit locked pipeline.",
            "- Preserve symbolic parameters, one-module-per-structure, clock/reset and CDC/RDC, protocols/latency, physical dimensions, contributions/events/noise/connect rules, and source provenance through target optimization unless an explicit separately verified transformation contract says otherwise.",
        )
    )
    text = replace_once(
        text,
        fixed_anchor,
        fixed_addition,
        subject="fixed target pass direction",
    )

    public_anchor = (
        "- Plugins may add namespaced, approved extensions but cannot override core language "
        "semantics, safety verifiers, width/unit/domain rules, or silently participate in "
        "`Backend.Auto`."
    )
    public_addition = public_anchor + "\n" + "\n".join(
        (
            "- Add a separately versioned target-HDL optimization-pass SPI with stable pass IDs, explicit extension points/profiles, normalized options, locked deterministic ordering, structured digital/analog/AMS IR, and proof-carrying evidence.",
            "- Provide explicit optimization profiles such as none/canonical/portable/simulation/synthesis/formal/FPGA/custom while keeping the exact profile and pass graph visible in `EmitOptions`, project configuration, lockfiles, manifests, reports, and cache keys.",
            "- Semantic post-render transforms must reparse into the approved target representation, restore source/capability metadata, run mandatory verification, and satisfy the pass proof obligation; render-only plugins may change formatting but not parsed meaning.",
        )
    )
    text = replace_once(
        text,
        public_anchor,
        public_addition,
        subject="public target pass direction",
    )

    pass_section = r'''## Target-HDL optimization-pass architecture

The binding architecture is [ADR 0013](../architecture/0013-structured-hdl-optimization-pass-architecture.md). The complete pass descriptor, extension-point, structured-IR, preservation, proof, profile, diagnostics, and conformance plan is in [`target-hdl-optimization-pass-v0.1-plan.md`](target-hdl-optimization-pass-v0.1-plan.md), with a machine-readable candidate in [`target-hdl-optimization-pass-v0.1-surface.json`](target-hdl-optimization-pass-v0.1-surface.json).

Nodal adopts:

> **Optimize structured target IR, declare semantic effects, reverify every boundary, and retain proof evidence.**

The target-pass layer is separate from backend selection and builds on the general plugin SPI. Pass kinds are target-neutral Nodal IR, digital target IR, Verilog-A target IR, Verilog-AMS target IR, render-only, and verified reparse passes.

Binding rules are:

- installed passes never execute automatically and never silently join an optimization profile;
- pass identity is a stable ID/version, not an implementation class name;
- pass order comes from locked extension points, dependencies, before/after constraints, conflicts, and deterministic resolution—not discovery or shared-library order;
- digital target IR selectively reuses CIRCT `hw`/`comb`/`seq`/`sv` plus Nodal-owned contracts;
- Verilog-A/Verilog-AMS target IR remains typed and preserves natures, disciplines, nodes, branches, access functions, dimensions, parameters, contributions, continuous-time operators, events/tolerances, noise/analysis identity, digital state, conversions, connect rules, capabilities, hierarchy, and source maps;
- raw semantic text rewriting is rejected unless output is reparsed, reverified, remapped, and proven under the same contract as a structured pass;
- every pass declares required/preserved/invalidated analyses and effects on types/widths, signedness/overflow, parameters/generate, hierarchy/ports, domains/CDC/RDC, protocols/latency, memories/effects, dimensions, contributions/events/noise/connect rules, mixed-signal provenance, source maps, and backend capabilities;
- ordinary profiles preserve symbolic parameters and one module per structural implementation; specialization is explicit, receives distinct identity, and requires case-specific equivalence;
- digital optimization uses Yosys equivalence, parameter matrices, latency/protocol-aware checks, SBY, and Verilator/Icarus regression as required;
- analog/AMS optimization uses typed equation/contribution/event/noise/connect-rule invariants plus required DC/AC/transient/noise/event differential validation and explicit rejection when no sound method exists;
- `Backend.Auto` selects only a backend; a separately explicit optimization profile selects a versioned pass pipeline;
- pass pipeline, options, IR versions, before/after hashes, source-map changes, analysis invalidation, proof evidence, tool versions/commands, and deterministic pipeline hash participate in manifests, provenance, release evidence, and caches.

Candidate profiles are none, canonical, portable, simulation, synthesis, formal, FPGA, and custom. Exact names, configuration APIs, descriptors, extension points, diagnostics, and lockfile fields are frozen by Increment 83 before execution is implemented.


'''
    text = replace_once(
        text,
        "## Core, plugin, and future library boundary",
        pass_section + "## Core, plugin, and future library boundary",
        subject="target pass roadmap section",
    )

    text = replace_once(
        text,
        "## Phase 5 — Plugins, extensibility, scale, documentation, and release",
        "## Phase 5 — Plugins, target-HDL optimization, extensibility, scale, documentation, and release",
        subject="phase 5 title",
    )

    insertion_anchor = "- [ ] **Increment 87 — Backend and external tool-adapter plugins**"
    new_increments = r'''- [ ] **Increment 83 — Target-HDL optimization pass gate and SPI v0.1 contracts**
  - Use [ADR 0013](../architecture/0013-structured-hdl-optimization-pass-architecture.md), [`target-hdl-optimization-pass-v0.1-plan.md`](target-hdl-optimization-pass-v0.1-plan.md), and [`target-hdl-optimization-pass-v0.1-surface.json`](target-hdl-optimization-pass-v0.1-surface.json) as the mandatory architecture and candidate.
  - Compile descriptors/manifests for target-neutral, digital, Verilog-A, Verilog-AMS, render-only, and reparse passes; stable pass IDs; target/profile/IR versions; extension points; ordering/conflicts; options; preservation/invalidation; proof classes; parameterization/source-map effects; profiles; native/process facets; and evidence artifacts.
  - Publish `NodalTargetHdlOptimizationPass-DG-v0.1.md`, a machine-readable frozen pass SPI, pass/profile lockfile schemas, compatibility/trust policy, and positive/negative fixtures with stable diagnostics.
  - Prove installation changes no output, `Backend.Auto` remains independent, raw semantic text cannot bypass reparse/reverification, and frontend/backend/pass execution remains inert until later increments.

- [ ] **Increment 84 — Structured target IR and deterministic optimization pass manager**
  - Implement verified digital target IR using CIRCT where semantically appropriate plus Nodal-owned contracts, and typed Verilog-A/Verilog-AMS target IR preserving disciplines/nodes/branches/contributions/dimensions/continuous-time operators/events/noise/analyses/digital state/conversions/connect rules/capabilities/hierarchy/source maps.
  - Implement deterministic locked pass resolution/execution, native/process loading through Increment 82, analysis invalidation/recomputation, mandatory target verification, transactional crash-safe acceptance, render-only and verified reparse boundaries, source-map updates, diagnostics, pass reports, cache/provenance integration, and pass/pipeline inspection commands.
  - Add out-of-tree target-pass fixtures and declaration-order/load-order permutation tests producing identical verified target IR, HDL, diagnostics, reports, and pipeline hashes.

- [ ] **Increment 85 — Digital Verilog optimization plugins and equivalence/formal proof matrix**
  - Implement built-in/reference plugins for parameter-aware constant/dead-logic cleanup, mux/logic/process/memory/generate normalization, safe common-subexpression elimination, hierarchy/portability cleanup, pipeline-owned bounded retiming, explicit synthesis attributes/target mapping, and locked external Yosys pass pipelines.
  - Preserve widths/signedness/overflow, symbolic parameters/generate, one-module-per-structure, hierarchy, clocks/resets/CDC/RDC, protocol ordering, latency/throughput/capacity, user-owned state, memories/effects, source maps, and portable-Verilog capabilities unless an explicit separately named transformation contract permits a verified change.
  - Require Verilator/Icarus differential regression, Yosys combinational/sequential and latency/protocol-aware equivalence, parameter-envelope matrices, selected SBY properties, deterministic before/after reports, and exact golden/profile fixtures.

- [ ] **Increment 86 — Verilog-A/Verilog-AMS optimization plugins and semantic validation**
  - Implement built-in/reference plugins for dimension-safe constant/parameter folding, dead declaration removal, branch/access/contribution canonicalization, approved algebraic identities with domain/singularity checks, event-condition simplification preserving direction/tolerance, connect-rule/discipline portability rewriting, and deterministic render normalization.
  - Prohibit unapproved contribution deletion/reordering, equation reassociation across discontinuities/singularities, movement of continuous-time/delay/Laplace/Z operators, changes to event timing/tolerance/initialization/noise/analysis identity, silent approximation, mixed-signal scheduling changes, and raw semantic text substitution.
  - Require typed target-IR invariants and normalized equivalence for approved rewrites plus relevant DC/AC/transient/noise/event differential suites, cross-tool portability evidence, source-map/provenance checks, deterministic golden fixtures, and explicit rejection where no sound validation method exists.

'''
    text = replace_once(
        text,
        insertion_anchor,
        new_increments + insertion_anchor,
        subject="target pass increments",
    )

    m4_anchor = (
        "- **M4 — Scalable core release:** packaged compiler, complete reference, frozen plugin "
        "SPI, deterministic extension graph, conformance kit, library-author contract, and "
        "compatibility policy."
    )
    m4_replacement = (
        "- **M4 — Scalable core release:** packaged compiler, complete reference, frozen plugin "
        "and target-HDL pass SPIs, deterministic extension/pass graphs, optimization proof "
        "evidence, conformance kits, library-author contract, and compatibility policy."
    )
    text = replace_once(text, m4_anchor, m4_replacement, subject="M4 pass milestone")

    reference_anchor = (
        "- MLIR dialect definitions: <https://mlir.llvm.org/docs/DefiningDialects/>"
    )
    reference_addition = reference_anchor + "\n" + "\n".join(
        (
            "- MLIR pass plugin API: <https://github.com/llvm/llvm-project/blob/main/mlir/include/mlir/Tools/Plugins/PassPlugin.h>",
            "- MLIR dialect plugin API: <https://github.com/llvm/llvm-project/blob/main/mlir/include/mlir/Tools/Plugins/DialectPlugin.h>",
        )
    )
    text = replace_once(
        text,
        reference_anchor,
        reference_addition,
        subject="target pass references",
    )

    ROADMAP.write_text(text, encoding="utf-8")


def update_architecture_index() -> None:
    path = ROOT / "docs/architecture/README.md"
    text = path.read_text(encoding="utf-8")
    anchor = (
        "| [0012](0012-versioned-capability-plugin-architecture.md) | Use explicit manifests, "
        "a typed capability graph, deterministic phases, local design hosts, separate "
        "Scala/native/process boundaries, lockfiles, trust policy, and retained plugin "
        "provenance. |"
    )
    replacement = anchor + "\n" + (
        "| [0013](0013-structured-hdl-optimization-pass-architecture.md) | Use structured "
        "digital/Verilog-A/Verilog-AMS target IR, explicit locked pass pipelines, declared "
        "semantic effects, mandatory re-verification, and digital/AMS-appropriate proof "
        "evidence. |"
    )
    text = replace_once(text, anchor, replacement, subject="ADR 0013 index")
    path.write_text(text, encoding="utf-8")


def update_plugin_documents() -> None:
    adr = ROOT / "docs/architecture/0012-versioned-capability-plugin-architecture.md"
    text = renumber_increment_lines(adr.read_text(encoding="utf-8"))
    anchor = (
        "Native plugins cannot silently weaken core verifiers. Core verification runs after "
        "plugin transformations at required boundaries."
    )
    addition = anchor + "\n\n" + (
        "Target-specific Verilog, Verilog-A, and Verilog-AMS optimization passes use the "
        "separate structured pass contract in [ADR 0013]"
        "(0013-structured-hdl-optimization-pass-architecture.md). The general plugin SPI owns "
        "manifest resolution, loading, trust, phases, and provenance; the target-pass SPI owns "
        "structured target representations, pass extension points, semantic preservation, "
        "render/reparse boundaries, optimization profiles, and proof obligations."
    )
    text = replace_once(text, anchor, addition, subject="ADR 0012 target pass layering")
    follow_anchor = (
        "- Increment 82 implements MLIR pass/dialect/analysis plugin loading and named compiler "
        "extension points.\n"
        "- Increment 87 implements backend registration and the out-of-process tool-adapter "
        "protocol."
    )
    follow_replacement = (
        "- Increment 82 implements MLIR pass/dialect/analysis plugin loading and named compiler "
        "extension points.\n"
        "- Increments 83-86 freeze and implement structured target-HDL optimization passes, "
        "deterministic pass management, digital equivalence/formal evidence, and "
        "Verilog-A/Verilog-AMS semantic validation.\n"
        "- Increment 87 implements backend registration and the out-of-process tool-adapter "
        "protocol."
    )
    text = replace_once(text, follow_anchor, follow_replacement, subject="ADR 0012 follow-up")
    reference_anchor = (
        "- MLIR standalone plugin example: "
        "<https://github.com/llvm/llvm-project/blob/main/mlir/examples/standalone/standalone-plugin/standalone-plugin.cpp>"
    )
    text = replace_once(
        text,
        reference_anchor,
        reference_anchor
        + "\n- Nodal target-HDL pass architecture: "
        + "[ADR 0013](0013-structured-hdl-optimization-pass-architecture.md)",
        subject="ADR 0012 target pass reference",
    )
    adr.write_text(text, encoding="utf-8")

    plan = ROOT / "docs/roadmap/plugin-spi-v0.1-plan.md"
    text = renumber_increment_lines(plan.read_text(encoding="utf-8"))
    compiler_anchor = (
        "The initial native loader wraps MLIR pass/dialect plugin APIs and validates a Nodal "
        "manifest before registration."
    )
    compiler_addition = compiler_anchor + "\n\n" + (
        "Target-specific Verilog, Verilog-A, and Verilog-AMS optimization uses the layered "
        "contract in [ADR 0013]"
        "(../architecture/0013-structured-hdl-optimization-pass-architecture.md) and "
        "[`target-hdl-optimization-pass-v0.1-plan.md`]"
        "(target-hdl-optimization-pass-v0.1-plan.md). The general plugin SPI supplies loading, "
        "trust, compatibility, phases, lockfiles, and provenance; the target-pass SPI supplies "
        "structured target IR, pass descriptors/profiles, semantic preservation, and proof "
        "obligations."
    )
    text = replace_once(
        text,
        compiler_anchor,
        compiler_addition,
        subject="plugin plan target pass layering",
    )
    incremental_anchor = "### Increment 87 — Backend and external tool-adapter plugins"
    incremental_note = (
        "Target-HDL optimization delivery is defined separately by Increments 83-86 in "
        "[`target-hdl-optimization-pass-v0.1-plan.md`]"
        "(target-hdl-optimization-pass-v0.1-plan.md).\n\n"
    )
    text = replace_once(
        text,
        incremental_anchor,
        incremental_note + incremental_anchor,
        subject="plugin plan target pass increments",
    )
    reference_anchor = (
        "- MLIR standalone plugin example: "
        "<https://github.com/llvm/llvm-project/blob/main/mlir/examples/standalone/standalone-plugin/standalone-plugin.cpp>"
    )
    text = replace_once(
        text,
        reference_anchor,
        reference_anchor
        + "\n- Nodal target-HDL optimization pass plan: "
        + "[`target-hdl-optimization-pass-v0.1-plan.md`]"
        + "(target-hdl-optimization-pass-v0.1-plan.md)",
        subject="plugin plan target pass reference",
    )
    plan.write_text(text, encoding="utf-8")

    surface_path = ROOT / "docs/roadmap/plugin-spi-v0.1-surface.json"
    surface = json.loads(surface_path.read_text(encoding="utf-8"))
    surface["roadmap_revision"] = "1.7"
    surface["compiler_plugins"]["target_hdl_pass_spi"] = {
        "architecture": "docs/architecture/0013-structured-hdl-optimization-pass-architecture.md",
        "plan": "docs/roadmap/target-hdl-optimization-pass-v0.1-plan.md",
        "surface": "docs/roadmap/target-hdl-optimization-pass-v0.1-surface.json",
        "separate_structured_target_contract": True,
        "raw_semantic_text_filters": False,
        "mandatory_reverification": True,
        "proof_obligation_required": True,
    }
    surface["implementation_increments"] = {
        "79": "SPI gate and contract fixtures",
        "80": "manifest resolver, capability graph, lockfile, and CLI",
        "81": "local design composition host",
        "82": "native compiler plugin loader and generic extension points",
        "83": "target HDL optimization pass gate and contracts",
        "84": "structured target IR and deterministic pass manager",
        "85": "digital Verilog optimization plugins and proof matrix",
        "86": "Verilog A and Verilog AMS optimization plugins and semantic validation",
        "87": "backend and external tool-adapter plugins",
        "88": "packaging, trust, provenance, caching, conformance, and reference plugins",
    }
    surface_path.write_text(
        json.dumps(surface, indent=2, sort_keys=False) + "\n",
        encoding="utf-8",
    )


def update_digital_and_ams_documents() -> None:
    digital_adr = ROOT / "docs/architecture/0010-digital-verilog-open-source-verification.md"
    text = renumber_increment_lines(digital_adr.read_text(encoding="utf-8"))
    anchor = (
        "Yosys-based equivalence checks compare:\n\n- deterministic generated RTL before "
        "and after approved optimization;"
    )
    replacement = (
        "Approved plug-and-play digital optimization passes follow [ADR 0013]"
        "(0013-structured-hdl-optimization-pass-architecture.md): they operate on verified "
        "structured digital target IR or through locked reparse adapters, preserve symbolic "
        "parameters/domains/protocols/source maps, and carry explicit equivalence/formal "
        "obligations.\n\n"
        + anchor
    )
    text = replace_once(text, anchor, replacement, subject="digital ADR target pass integration")
    digital_adr.write_text(text, encoding="utf-8")

    digital_plan = ROOT / "docs/roadmap/digital-verilog-open-source-verification-plan.md"
    text = renumber_increment_lines(digital_plan.read_text(encoding="utf-8"))
    anchor = "## Open-source toolchain"
    section = (
        "## Target-HDL optimization integration\n\n"
        "Digital optimization passes use [ADR 0013]"
        "(../architecture/0013-structured-hdl-optimization-pass-architecture.md) and "
        "[`target-hdl-optimization-pass-v0.1-plan.md`]"
        "(target-hdl-optimization-pass-v0.1-plan.md). Generated RTL optimization is explicit, "
        "locked, parameter/source-map preserving, and verified with the same Verilator, "
        "Icarus, Yosys equivalence, parameter-matrix, latency/protocol-aware, and SBY evidence "
        "required by the selected pass profile. Installing a pass does not alter the digital "
        "backend or `Backend.Auto`.\n\n"
    )
    text = replace_once(text, anchor, section + anchor, subject="digital plan target pass section")
    digital_plan.write_text(text, encoding="utf-8")

    ams_adr = ROOT / "docs/architecture/0011-ams-fpga-approximation-validation.md"
    text = renumber_increment_lines(ams_adr.read_text(encoding="utf-8"))
    anchor = (
        "Passing FPGA hardware does not erase a discretization or quantization mismatch."
    )
    addition = anchor + "\n\n" + (
        "Any plug-and-play Verilog-A/Verilog-AMS optimization used on the authoritative AMS "
        "reference follows [ADR 0013]"
        "(0013-structured-hdl-optimization-pass-architecture.md). It must preserve typed "
        "equation/contribution/event/noise/connect-rule semantics, symbolic parameters, source "
        "maps, and the declared simulator profile, with required invariant and differential "
        "evidence. FPGA approximation remains an explicit separate transformation and cannot be "
        "introduced by an optimization pass."
    )
    text = replace_once(text, anchor, addition, subject="AMS ADR target pass integration")
    ams_adr.write_text(text, encoding="utf-8")

    ams_plan = ROOT / "docs/roadmap/ams-fpga-validation-plan.md"
    text = renumber_increment_lines(ams_plan.read_text(encoding="utf-8"))
    anchor = (
        "The compiler-owned approximation semantics are not plugins. FPGA targets, "
        "place/route, bitstream, programmer, board, and HIL integrations use the explicit "
        "tool-adapter plugin protocol from [ADR 0012]"
        "(../architecture/0012-versioned-capability-plugin-architecture.md) and "
        "[`plugin-spi-v0.1-plan.md`](plugin-spi-v0.1-plan.md)."
    )
    replacement = anchor + "\n\n" + (
        "Optimization of the authoritative Verilog-A/Verilog-AMS reference or the generated "
        "digital approximation uses [ADR 0013]"
        "(../architecture/0013-structured-hdl-optimization-pass-architecture.md) and "
        "[`target-hdl-optimization-pass-v0.1-plan.md`]"
        "(target-hdl-optimization-pass-v0.1-plan.md). Such passes cannot silently introduce "
        "discretization, fixed-point conversion, specialization, or changed validation claims."
    )
    text = replace_once(text, anchor, replacement, subject="AMS plan target pass integration")
    ams_plan.write_text(text, encoding="utf-8")

    ams_surface_path = ROOT / "docs/roadmap/ams-fpga-validation-surface.json"
    surface = json.loads(ams_surface_path.read_text(encoding="utf-8"))
    surface["roadmap_revision"] = "1.7"
    surface["formal_gate_increment"] = 100
    surface["documents"]["target_hdl_pass_plan"] = (
        "docs/roadmap/target-hdl-optimization-pass-v0.1-plan.md"
    )
    surface["optimization_pass_boundary"] = {
        "reference_ams_uses_structured_pass_spi": True,
        "generated_digital_approximation_uses_structured_pass_spi": True,
        "optimization_may_introduce_approximation": False,
        "optimization_may_change_claims_silently": False,
    }
    surface["increments"] = {
        "100": "capability gate and API contracts",
        "101": "analog normalization and sampled state IR",
        "102": "solver and recurrence generation",
        "103": "range fixed point quantization and error analysis",
        "104": "multi rate event and real time scheduling",
        "105": "synthesizable FPGA approximation backend",
        "106": "differential equivalence and formal validation ladder",
        "107": "open source FPGA implementation and target evidence",
        "108": "hardware in the loop vertical slices and capability matrix",
    }
    ams_surface_path.write_text(
        json.dumps(surface, indent=2, sort_keys=False) + "\n",
        encoding="utf-8",
    )


def update_branch_policy() -> None:
    path = ROOT / ".github/branch-policy.json"
    data = json.loads(path.read_text(encoding="utf-8"))
    data["milestone_promotions"]["M4"] = "after Increment 98"
    data["milestone_promotions"]["M5"] = "after Increment 108"
    path.write_text(json.dumps(data, indent=2, sort_keys=False) + "\n", encoding="utf-8")


def validate() -> None:
    for path in sorted((ROOT / "docs/roadmap").glob("*.json")):
        json.loads(path.read_text(encoding="utf-8"))

    roadmap = ROADMAP.read_text(encoding="utf-8")
    headings = [
        int(match.group(1))
        for match in re.finditer(r"^- \[[ x]\] \*\*Increment (\d+) —", roadmap, re.MULTILINE)
    ]
    expected = list(range(0, 109))
    if headings != expected:
        raise SystemExit(
            f"roadmap increment sequence mismatch: expected 0..108, got {headings[:5]}...{headings[-5:]}"
        )

    for required in (
        "**Revision:** 1.7",
        "Increment 83 — Target-HDL optimization pass gate and SPI v0.1 contracts",
        "Increment 84 — Structured target IR and deterministic optimization pass manager",
        "Increment 85 — Digital Verilog optimization plugins and equivalence/formal proof matrix",
        "Increment 86 — Verilog-A/Verilog-AMS optimization plugins and semantic validation",
        "Increment 108 — Hardware-in-the-loop runtime, vertical slices, and capability matrix",
    ):
        if required not in roadmap:
            raise SystemExit(f"roadmap missing required target-pass content: {required}")


def main() -> None:
    update_roadmap()
    update_architecture_index()
    update_plugin_documents()
    update_digital_and_ams_documents()
    update_branch_policy()
    validate()
    print("target-HDL optimization-pass roadmap update applied")


if __name__ == "__main__":
    main()
