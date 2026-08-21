#!/usr/bin/env python3
"""Apply the Verilog-family optimization-pass roadmap revision."""

from __future__ import annotations

import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ROADMAP = ROOT / "docs/roadmap/nodal-development-todo.md"


def replace_once(text: str, old: str, new: str, *, subject: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{subject}: expected one anchor, found {count}: {old[:100]!r}")
    return text.replace(old, new, 1)


def shift_increment_references(text: str, *, threshold: int = 85, delta: int = 4) -> str:
    """Shift roadmap increment numbers only on lines that mention Increment(s)."""

    def rewrite(line: str) -> str:
        if "Increment" not in line:
            return line

        def repl(match: re.Match[str]) -> str:
            value = int(match.group(0))
            return str(value + delta if value >= threshold else value)

        return re.sub(r"(?<![\w.])\d+(?![\w.])", repl, line)

    return "\n".join(rewrite(line) for line in text.split("\n"))


def update_roadmap() -> None:
    text = ROADMAP.read_text(encoding="utf-8")
    text = replace_once(
        text,
        "**Revision:** 1.6",
        "**Revision:** 1.7",
        subject="roadmap revision",
    )

    # Shift the existing post-plugin roadmap before inserting the new 85-88 block.
    text = shift_increment_references(text)

    fixed_anchor = (
        "- Make plugin graph, artifact, option, phase, pass, process-protocol, trust, "
        "and toolchain hashes part of deterministic build manifests and cache keys."
    )
    fixed_replacement = fixed_anchor + "\n" + "\n".join(
        (
            "- Support plug-and-play Verilog-family optimization through versioned typed IR and declared extension points, not arbitrary semantic text filters.",
            "- Require every semantic pass to declare input/output IR versions, target family/profile, pass kind, semantic effect, preserved/invalidated analyses, ordering, determinism, source-map behavior, and proof obligations.",
            "- Preserve symbolic parameters, module identity, widths/signedness, domains/CDC/RDC, protocols/latency, automatic-pipeline transaction semantics, physical quantities, analog contributions/events, and mixed-signal boundaries unless an explicitly gated effect permits a change.",
            "- Execute semantic optimization transactionally: verify a candidate through structural, semantic, equivalence/differential/formal, source-map, and capability checks before replacing the last accepted compiler state.",
            "- Integrate Yosys pass scripts/plugins as pinned external optimization engines with re-import and equivalence evidence; Yosys and other external tools do not define Nodal semantics.",
        )
    )
    text = replace_once(
        text,
        fixed_anchor,
        fixed_replacement,
        subject="fixed plugin direction",
    )

    public_anchor = (
        "- Keep Scala/native in-process plugins trusted and explicitly enabled; prefer "
        "process isolation for external tools and long-lived transform/backend integrations."
    )
    public_replacement = public_anchor + "\n" + "\n".join(
        (
            "- Configure optimization pipelines through compiler/emission options and locked project profiles, not hidden model-library side effects.",
            "- Add a versioned optimization-pass configuration/evidence surface centered on `OptimizationPipeline`, `PassRef`, stable pass IDs, target families, stages, semantic effects, and verification policies.",
            "- Restrict post-emission text plugins to formatting, comments, banners, source-map finalization, and packaging. Reparse and fully verify any legacy semantic text transform before transactional acceptance.",
        )
    )
    text = replace_once(
        text,
        public_anchor,
        public_replacement,
        subject="public plugin direction",
    )

    optimization_section = r'''## Verilog-family optimization pass architecture

The binding architecture is [ADR 0013](../architecture/0013-versioned-verilog-family-optimization-passes.md). The complete target-IR, pass, extension-point, proof, Yosys-integration, rollback, source-map, and conformance plan is in [`verilog-family-optimization-pass-plan.md`](verilog-family-optimization-pass-plan.md), with a machine-readable candidate in [`verilog-family-optimization-pass-surface.json`](verilog-family-optimization-pass-surface.json).

Nodal adopts:

> **Optimize the highest valid typed representation, declare every semantic effect, verify every mutation, and commit only a validated candidate.**

Optimization layers are:

1. target-neutral Nodal IR for backend-independent semantic optimization;
2. digital Nodal/CIRCT IR for digital structure and implementation preparation;
3. versioned typed `nodal-hdl-verilog`, `nodal-hdl-veriloga`, and `nodal-hdl-verilogams` representations for target-specific legalization/optimization/instrumentation;
4. emitted text only for non-semantic formatting, comments, banners, source-map finalization, and packaging.

A semantic external text transform must run out of process, be reparsed into a supported typed representation, pass all core verifiers and family-specific proof/differential policies, preserve provenance/source maps, and be accepted transactionally.

Directional compiler configuration:

```scala
val optimization = OptimizationPipeline(
  profile = OptimizationProfile.Custom,
  passes = Seq(
    PassRef("org.nodal.opt.verilog.constant-fold"),
    PassRef(
      "com.acme.opt.verilog.clock-gate",
      options = Map("minimum-width" -> 4)
    )
  ),
  verification = VerificationPolicy.Required
)

Nodal.emit(
  new DigitalTop,
  EmitOptions(
    backend = Backend.Verilog,
    optimization = optimization
  )
)
```

Exact names are frozen by Increment 85. Binding rules are:

- pass identity is a stable globally qualified ID/version, independent of Scala classes or native symbols;
- each pass declares input/output representation/version, extension point, target family/profile, pass kind, semantic effect, analyses, ordering, determinism, trust/isolation, source-map behavior, and proof obligations;
- ambiguous order among non-commuting passes is an error; discovery/load/filesystem order never defines hardware;
- default passes preserve symbolic parameters and one-module-per-structure generation; specialization is explicit, locked, and separately manifested;
- semantic passes preserve widths/signedness/overflow, clock/reset/CDC/RDC, protocols/latency, pipeline transaction identity, units/disciplines, analog contributions/events, mixed-signal boundaries, memories/effects, target profile, names, and source origins unless an approved effect says otherwise;
- candidate IR is structurally and semantically verified, proof/differential/formal checked, source-map validated, and only then committed; crash/timeout/malformed/proof failure leaves the previous accepted state intact;
- digital Verilog verification may use CIRCT LEC, Yosys equivalence, Verilator/Icarus differential simulation, SBY, parameter-envelope matrices, and latency/protocol-aware checks;
- Verilog-A/AMS optimization uses typed equation/contribution/event/connect verification, narrowly scoped symbolic equivalence where sound, OpenVAF compilation, and bounded differential simulation with explicit envelopes/tolerances;
- Yosys scripts and dynamically loaded Yosys plugins are invoked through pinned adapters with exact build/plugin/script/order/options hashes and re-import/equivalence evidence;
- optimization pass graphs, options, tools, proof policies, input/output hashes, source maps, and evidence participate in lockfiles, cache keys, build manifests, and release provenance.

The optimization-pass gate freezes target representations, extension points, pass kinds/effects, mandatory invariants, transactional rollback, verification ladders, source-map contracts, stable diagnostics, and external-consumer fixtures before implementation.


'''
    text = replace_once(
        text,
        "## Core, plugin, and future library boundary",
        optimization_section + "## Core, plugin, and future library boundary",
        subject="optimization architecture section",
    )

    text = replace_once(
        text,
        "## Phase 5 — Plugins, extensibility, scale, documentation, and release",
        "## Phase 5 — Plugins, optimization passes, extensibility, scale, documentation, and release",
        subject="phase 5 title",
    )

    new_increments = r'''- [ ] **Increment 85 — Verilog-family optimization pass architecture gate and contracts**
  - Use [ADR 0013](../architecture/0013-versioned-verilog-family-optimization-passes.md), [`verilog-family-optimization-pass-plan.md`](verilog-family-optimization-pass-plan.md), and [`verilog-family-optimization-pass-surface.json`](verilog-family-optimization-pass-surface.json) as the mandatory architecture and candidate.
  - Compile pass descriptors/IDs, target families, typed IR versions, extension points, pass kinds/effects, pipeline profiles, ordering, analysis preservation/invalidation, proof policies, evidence, and compiler/emission configuration candidates.
  - Freeze parameterization, module identity, width/sign, clock/reset/CDC/RDC, protocol/latency, pipeline, quantity/effect, analog/event, mixed-signal, source-map, determinism, trust, lockfile, specialization, and transactional rollback rules.
  - Publish `NodalVerilogFamilyOptimizationPass-DG-v0.1.md`, schemas, stable diagnostics, and complete positive/negative external-plugin fixtures. Keep pass execution inert until the gate is approved.

- [ ] **Increment 86 — Versioned target HDL IR and transactional pass manager**
  - Implement deterministic parse/print/verify for approved `nodal-hdl-verilog/1`, `nodal-hdl-veriloga/1`, and `nodal-hdl-verilogams/1` representations or their gated equivalents.
  - Preserve target constructs, symbolic parameters/generate, analog/mixed-signal semantics, stable node IDs, origin chains, capability annotations, and deterministic round trips.
  - Implement pass registration, extension-point validation, total-order resolution, analysis preservation/invalidation, immutable candidate execution, rollback, pass tracing, diagnostics, evidence, provenance, and cache keys. Align built-in passes with the same model.

- [ ] **Increment 87 — Digital Verilog optimization plugins and Yosys interoperability**
  - Implement target-neutral/digital/Verilog plugin passes at approved points, including parameter/generate-safe structural optimizations and portable-profile legalization.
  - Integrate pinned Yosys scripts and dynamically loaded Yosys plugins through the external adapter protocol; retain exact Yosys/plugin/script/order/options/input/output hashes and re-import results.
  - Require Nodal/CIRCT verification plus configured Yosys/CIRCT equivalence, Verilator/Icarus differential simulation, SBY, parameter-envelope, pipeline-latency, protocol, reset, and CDC/RDC evidence before accepting replacements.
  - Publish reference digital optimization plugins, canonical-versus-implementation artifact rules, and invalid-pass conformance fixtures.

- [ ] **Increment 88 — Verilog-A/Verilog-AMS optimization plugins and bounded validation**
  - Implement typed Verilog-A/AMS target passes for approved canonicalization, optimization, legalization, instrumentation, and formatting points.
  - Preserve units/dimensions, natures/disciplines, nodes/branches, state/initialization, contributions/events, parameters/hierarchy, connect/resolution, domains, protocols, sampling/drive boundaries, profiles, and source origins.
  - Add narrowly scoped symbolic/equation equivalence where sound, normalized equation evidence, OpenVAF compilation, ngspice/optional second-tool differential regression, digital-partition equivalence, and explicit envelope/tolerance/limitation reporting.
  - Publish reference Verilog-A/AMS passes and negative proof, source-map, parameterization, event, and portability fixtures.

'''
    text = replace_once(
        text,
        "- [ ] **Increment 89 — Versioned IR and bridge compatibility**",
        new_increments + "- [ ] **Increment 89 — Versioned IR and bridge compatibility**",
        subject="optimization implementation increments",
    )

    old_m4 = (
        "- **M4 — Scalable core release:** packaged compiler, complete reference, frozen "
        "plugin SPI, deterministic extension graph, conformance kit, library-author contract, "
        "and compatibility policy."
    )
    new_m4 = (
        "- **M4 — Scalable core release:** packaged compiler, complete reference, frozen "
        "plugin and Verilog-family optimization-pass SPIs, deterministic extension/pass graphs, "
        "transactional verification, conformance kits, library-author contract, and compatibility policy."
    )
    text = replace_once(text, old_m4, new_m4, subject="M4 milestone")

    refs_anchor = (
        "- Yosys Verilog frontend: <https://yosyshq.readthedocs.io/projects/yosys/en/stable/cmd/index_frontends.html>"
    )
    refs_replacement = refs_anchor + "\n" + "\n".join(
        (
            "- Yosys pass framework: <https://github.com/YosysHQ/yosys>",
            "- Yosys plugins: <https://github.com/YosysHQ/yosys-plugins>",
            "- CIRCT passes: <https://circt.llvm.org/docs/Passes/>",
            "- MLIR pass plugin API: <https://github.com/llvm/llvm-project/blob/main/mlir/include/mlir/Tools/Plugins/PassPlugin.h>",
        )
    )
    text = replace_once(text, refs_anchor, refs_replacement, subject="optimization references")

    ROADMAP.write_text(text, encoding="utf-8")


def update_architecture_index() -> None:
    path = ROOT / "docs/architecture/README.md"
    text = path.read_text(encoding="utf-8")
    anchor = (
        "| [0012](0012-versioned-capability-plugin-architecture.md) | Use a manifest-first, "
        "versioned typed-capability graph with local design hosts, deterministic phases, "
        "lockfiles, native/process compatibility, and separate plugin/library boundaries. |"
    )
    addition = anchor + "\n" + (
        "| [0013](0013-versioned-verilog-family-optimization-passes.md) | Use versioned typed IR, "
        "declared semantic effects, transactional verification, and retained provenance for "
        "plug-and-play Verilog/Verilog-A/Verilog-AMS optimization passes. |"
    )
    text = replace_once(text, anchor, addition, subject="architecture index")
    path.write_text(text, encoding="utf-8")


def update_plugin_adr() -> None:
    path = ROOT / "docs/architecture/0012-versioned-capability-plugin-architecture.md"
    text = shift_increment_references(path.read_text(encoding="utf-8"))
    section = r'''## Verilog-family optimization pass integration

[ADR 0013](0013-versioned-verilog-family-optimization-passes.md) specializes this common plugin architecture for target-family optimization passes.

The shared resolver, manifest, lockfile, trust, native/process loading, extension-point ordering, provenance, caching, and conformance machinery remains owned by ADR 0012. ADR 0013 additionally freezes:

- versioned typed Verilog/Verilog-A/Verilog-AMS target representations;
- target-specific extension points and entry/exit invariants;
- pass kinds and semantic-effect classes;
- parameterization, hierarchy, latency, protocol, quantity, event, and source-map preservation;
- transactional candidate acceptance and rollback;
- digital equivalence/formal and analog/AMS bounded differential policies;
- Yosys plugin/script interoperability through pinned external adapters;
- restrictions on semantic post-emission text filters.

Optimization passes are `CompilerPlugin` or isolated process facets. They are not model libraries and do not execute merely because a reusable model artifact is installed.


'''
    text = replace_once(
        text,
        "## Consequences",
        section + "## Consequences",
        subject="plugin ADR optimization integration",
    )
    follow_anchor = (
        "- Increment 84 completes packaging, trust, provenance, cache integration, "
        "conformance tests, and out-of-tree reference plugins."
    )
    follow_replacement = follow_anchor + "\n" + "\n".join(
        (
            "- Increment 85 freezes the Verilog-family optimization pass SPI and target representation contracts.",
            "- Increment 86 implements the transactional target HDL pass manager.",
            "- Increment 87 implements digital Verilog/Yosys optimization and equivalence.",
            "- Increment 88 implements Verilog-A/AMS optimization and bounded validation.",
        )
    )
    text = replace_once(text, follow_anchor, follow_replacement, subject="plugin ADR follow-up")
    path.write_text(text, encoding="utf-8")


def update_plugin_plan() -> None:
    path = ROOT / "docs/roadmap/plugin-spi-v0.1-plan.md"
    text = path.read_text(encoding="utf-8")
    goal_anchor = "- independent packaging, compatibility, trust, provenance, and deterministic caching."
    goal_replacement = goal_anchor + "\n" + (
        "- a shared foundation for versioned Verilog/Verilog-A/Verilog-AMS optimization passes "
        "defined by [ADR 0013](../architecture/0013-versioned-verilog-family-optimization-passes.md)."
    )
    text = replace_once(text, goal_anchor, goal_replacement, subject="plugin plan goal")

    integration = r'''## Verilog-family optimization pass integration

The common SPI does not treat target HDL passes as arbitrary compiler callbacks. [ADR 0013](../architecture/0013-versioned-verilog-family-optimization-passes.md) and [`verilog-family-optimization-pass-plan.md`](verilog-family-optimization-pass-plan.md) define the specialization.

Optimization pass facets reuse:

- manifest-first discovery and immutable plugin plans;
- stable IDs, versions, capabilities, conflicts, qualifiers, and lockfiles;
- native exact-build or process-isolated compatibility;
- named extension-point ordering;
- analysis preservation/invalidation;
- trust, checksums, provenance, caching, and conformance.

They additionally declare typed input/output representations, target family/profile, pass kind, semantic effect, mandatory invariants, transactional rollback, source-map behavior, and family-specific proof obligations.

Yosys-backed passes use the common out-of-process adapter envelope. Loaded Yosys plugin binaries, scripts, pass order, options, tool build, artifacts, and equivalence evidence are locked and retained. Verilog-A/AMS passes use Nodal's typed target representations and bounded differential evidence rather than unstructured text mutation.


'''
    text = replace_once(
        text,
        "## Backend and tool adapters",
        integration + "## Backend and tool adapters",
        subject="plugin plan optimization section",
    )

    delivery_anchor = (
        "### Increment 84 — Packaging, trust, provenance, caching, and conformance\n\n"
        "- [ ] Define coordinated Scala/native/process bundles, platform variants, Maven/native/process artifact metadata, checksums/signatures, licenses, and SBOMs.\n"
        "- [ ] Implement trust policies, offline resolution, process limits, and explicit in-process enablement.\n"
        "- [ ] Integrate plugin graphs with caching and release provenance.\n"
        "- [ ] Publish the plugin conformance kit and out-of-tree reference design/pass/dialect/backend/tool plugins.\n"
        "- [ ] Prove deterministic load-order permutations and compatibility failure behavior."
    )
    delivery_replacement = delivery_anchor + "\n\n" + r'''### Increments 85-88 — Verilog-family optimization pass specialization

- [ ] Continue under [`verilog-family-optimization-pass-plan.md`](verilog-family-optimization-pass-plan.md) without changing the common resolver/lockfile/trust model.
- [ ] Freeze typed target representations, target extension points, semantic effects, transactional rollback, proof policies, and source-map contracts.
- [ ] Implement digital Verilog/Yosys optimization and Verilog-A/AMS bounded-validation paths as separately testable plugin facets.
'''
    text = replace_once(text, delivery_anchor, delivery_replacement, subject="plugin plan delivery")
    path.write_text(text, encoding="utf-8")


def update_digital_documents() -> None:
    adr = ROOT / "docs/architecture/0010-digital-verilog-open-source-verification.md"
    text = adr.read_text(encoding="utf-8")
    section = r'''## Plug-in digital optimization

[ADR 0013](0013-versioned-verilog-family-optimization-passes.md) defines the plug-and-play optimization boundary for generated digital RTL. Target-neutral and CIRCT passes run before portable-Verilog emission where possible. Yosys pass scripts and dynamically loaded Yosys plugins run through pinned external adapters and may replace canonical RTL only after re-import, profile verification, and the configured equivalence/formal/differential policy passes.

The canonical generated Nodal RTL and an optimized implementation/netlist remain distinct artifacts when source-level structure or parameterization is not preserved.


'''
    text = replace_once(text, "## Consequences", section + "## Consequences", subject="digital ADR optimization")
    adr.write_text(text, encoding="utf-8")

    plan = ROOT / "docs/roadmap/digital-verilog-open-source-verification-plan.md"
    text = plan.read_text(encoding="utf-8")
    section = r'''## Plug-in optimization pipelines

Digital optimization uses [ADR 0013](../architecture/0013-versioned-verilog-family-optimization-passes.md) and [`verilog-family-optimization-pass-plan.md`](verilog-family-optimization-pass-plan.md).

A Yosys-backed optimization pipeline is pinned by Yosys build, loaded plugin hashes, script/pass order, normalized options, seeds, input/output hashes, and proof evidence. Nodal reparses and verifies optimized output; Yosys does not define Nodal width, reset, domain, protocol, latency, parameter, or source-map semantics.

Canonical Nodal RTL, optimized RTL, and synthesized netlists use separate artifact identities and source/equivalence evidence.


'''
    text = replace_once(text, "## CI tiers", section + "## CI tiers", subject="digital plan optimization")
    plan.write_text(text, encoding="utf-8")


def update_shifted_documents() -> None:
    for relative in (
        "docs/architecture/0011-ams-fpga-approximation-validation.md",
        "docs/roadmap/ams-fpga-validation-plan.md",
    ):
        path = ROOT / relative
        path.write_text(
            shift_increment_references(path.read_text(encoding="utf-8")),
            encoding="utf-8",
        )


def update_json_surfaces() -> None:
    roadmap_dir = ROOT / "docs/roadmap"
    for path in sorted(roadmap_dir.glob("*.json")):
        data = json.loads(path.read_text(encoding="utf-8"))
        if "roadmap_revision" in data:
            data["roadmap_revision"] = "1.7"
        path.write_text(json.dumps(data, indent=2) + "\n", encoding="utf-8")

    ams_path = roadmap_dir / "ams-fpga-validation-surface.json"
    ams = json.loads(ams_path.read_text(encoding="utf-8"))
    if int(ams.get("formal_gate_increment", 0)) >= 85:
        ams["formal_gate_increment"] = int(ams["formal_gate_increment"]) + 4
    increments = ams.get("increments", {})
    ams["increments"] = {
        str(int(key) + 4 if int(key) >= 85 else int(key)): value
        for key, value in increments.items()
    }
    ams["roadmap_revision"] = "1.7"
    ams_path.write_text(json.dumps(ams, indent=2) + "\n", encoding="utf-8")

    plugin_path = roadmap_dir / "plugin-spi-v0.1-surface.json"
    plugin = json.loads(plugin_path.read_text(encoding="utf-8"))
    plugin["roadmap_revision"] = "1.7"
    plugin["verilog_family_optimization"] = {
        "architecture_adr": "docs/architecture/0013-versioned-verilog-family-optimization-passes.md",
        "plan": "docs/roadmap/verilog-family-optimization-pass-plan.md",
        "surface": "docs/roadmap/verilog-family-optimization-pass-surface.json",
        "formal_gate_increment": 85,
        "uses_common_resolver_lockfile_trust_and_provenance": True,
        "semantic_text_filters_allowed": False,
    }
    plugin_path.write_text(json.dumps(plugin, indent=2) + "\n", encoding="utf-8")

    digital_path = roadmap_dir / "digital-backend-v0.3-surface.json"
    digital = json.loads(digital_path.read_text(encoding="utf-8"))
    digital["roadmap_revision"] = "1.7"
    digital["optimization_pass_plan"] = {
        "architecture": "docs/architecture/0013-versioned-verilog-family-optimization-passes.md",
        "plan": "docs/roadmap/verilog-family-optimization-pass-plan.md",
        "semantic_post_emit_text_filters": False,
        "yosys_is_external_engine_not_language_semantics": True,
    }
    digital_path.write_text(json.dumps(digital, indent=2) + "\n", encoding="utf-8")


def update_branch_policy() -> None:
    path = ROOT / ".github/branch-policy.json"
    data = json.loads(path.read_text(encoding="utf-8"))
    promotions = data["milestone_promotions"]
    promotions["M4"] = "after Increment 98"
    promotions["M5"] = "after Increment 108"
    path.write_text(json.dumps(data, indent=2) + "\n", encoding="utf-8")


def validate() -> None:
    for path in sorted((ROOT / "docs/roadmap").glob("*.json")):
        json.loads(path.read_text(encoding="utf-8"))

    text = ROADMAP.read_text(encoding="utf-8")
    numbers = [
        int(value)
        for value in re.findall(r"^- \[[ x]\] \*\*Increment (\d+) —", text, flags=re.MULTILINE)
    ]
    expected = list(range(109))
    if numbers != expected:
        raise SystemExit(
            f"roadmap increment numbering mismatch: expected 0..108, got {numbers[:5]}...{numbers[-5:]}"
        )
    required = (
        "**Revision:** 1.7",
        "Increment 85 — Verilog-family optimization pass architecture gate and contracts",
        "Increment 88 — Verilog-A/Verilog-AMS optimization plugins and bounded validation",
        "Increment 108 — Hardware-in-the-loop runtime, vertical slices, and capability matrix",
        "0013-versioned-verilog-family-optimization-passes.md",
        "verilog-family-optimization-pass-plan.md",
        "verilog-family-optimization-pass-surface.json",
    )
    for fragment in required:
        if fragment not in text:
            raise SystemExit(f"roadmap missing required fragment: {fragment}")
    print("Verilog-family optimization roadmap update validated")


def main() -> None:
    update_roadmap()
    update_architecture_index()
    update_plugin_adr()
    update_plugin_plan()
    update_digital_documents()
    update_shifted_documents()
    update_json_surfaces()
    update_branch_policy()
    validate()


if __name__ == "__main__":
    main()
