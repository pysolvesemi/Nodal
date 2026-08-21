#!/usr/bin/env python3
"""Apply the scalable Nodal plugin architecture roadmap revision."""

from __future__ import annotations

import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ROADMAP = ROOT / "docs/roadmap/nodal-development-todo.md"


def replace_once(text: str, old: str, new: str, *, subject: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{subject}: expected one anchor, found {count}: {old[:120]!r}")
    return text.replace(old, new, 1)


def replace_regex_once(
    text: str,
    pattern: str,
    replacement: str,
    *,
    subject: str,
    flags: int = 0,
) -> str:
    updated, count = re.subn(pattern, replacement, text, count=1, flags=flags)
    if count != 1:
        raise SystemExit(f"{subject}: expected one match, found {count}: {pattern!r}")
    return updated


def shift_increment_references(text: str, start: int, end: int, delta: int) -> str:
    pattern = re.compile(r"\b(Increment(?:s)?)\s+(\d+)(?:([–-])(\d+))?")

    def repl(match: re.Match[str]) -> str:
        label = match.group(1)
        first = int(match.group(2))
        separator = match.group(3)
        last_text = match.group(4)
        if not (start <= first <= end):
            return match.group(0)
        first += delta
        if last_text is None:
            return f"{label} {first}"
        last = int(last_text)
        if start <= last <= end:
            last += delta
        return f"{label} {first}{separator}{last}"

    return pattern.sub(repl, text)


def update_roadmap() -> None:
    text = ROADMAP.read_text(encoding="utf-8")
    text = replace_once(
        text,
        "**Revision:** 1.5",
        "**Revision:** 1.6",
        subject="roadmap revision",
    )

    fixed_anchor = (
        "- Reuse portable Verilog, automatic pipelines, clock/reset domains, CDC/RDC, "
        "Verilator/Icarus, Yosys, SBY, and an open Yosys+nextpnr target for FPGA "
        "approximation validation; vendor flows remain optional adapters."
    )
    fixed_replacement = fixed_anchor + "\n" + "\n".join(
        (
            "- Separate passive reusable libraries from executable plugins. Installing a model library must not implicitly execute or enable a plugin.",
            "- Resolve plugins from explicit manifests into a versioned typed capability graph and lockfile before loading Scala classes, native libraries, or external processes.",
            "- Use local `DesignHost` scopes, stable capability IDs, explicit cardinality/qualifiers, append-only contributions, and deterministic phases instead of concrete-plugin lookup, global service registries, mutable cross-plugin access, or public retain/release ordering.",
            "- Wrap MLIR pass/dialect plugin mechanisms with Nodal SPI, IR, toolchain-build, namespace, analysis-preservation, and mandatory re-verification contracts.",
            "- Run simulator, synthesis, formal, FPGA, programmer, board, and HIL adapters through a versioned out-of-process plugin protocol with normalized artifacts and retained provenance.",
            "- Make plugin graph, artifact, option, phase, pass, process-protocol, trust, and toolchain hashes part of deterministic build manifests and cache keys.",
        )
    )
    text = replace_once(
        text,
        fixed_anchor,
        fixed_replacement,
        subject="fixed plugin direction",
    )

    public_anchor = (
        "- Freeze the AMS-to-FPGA capability profile, solver/numeric/envelope contracts, "
        "claims language, diagnostics, and validation evidence through a dedicated "
        "post-preview design gate before implementation."
    )
    public_replacement = public_anchor + "\n" + "\n".join(
        (
            "- Add a separately versioned plugin SPI candidate covering `DesignPlugin`, local `DesignHost`, stable `CapabilityKey`/`ContributionKey`, plugin descriptors/manifests, immutable `PluginPlan`, backend IDs, and process-adapter descriptors.",
            "- Keep plugin identity independent of Scala implementation classes. Consumers depend on stable capability IDs and interfaces, never `host[ConcretePlugin]` or implicit first-provider selection.",
            "- Require explicit project plugin configuration, compatibility resolution, lockfiles, checksums/trust policy, and offline locked mode; do not scan arbitrary classpaths or directories for executable extensions.",
            "- Plugins may add namespaced, approved extensions but cannot override core language semantics, safety verifiers, width/unit/domain rules, or silently participate in `Backend.Auto`.",
            "- Keep Scala/native in-process plugins trusted and explicitly enabled; prefer process isolation for external tools and long-lived transform/backend integrations.",
        )
    )
    text = replace_once(
        text,
        public_anchor,
        public_replacement,
        subject="public plugin direction",
    )

    plugin_section = r'''## Plugin and extension architecture

The binding architecture is [ADR 0012](../architecture/0012-versioned-capability-plugin-architecture.md). The complete SPI, manifest, capability, lifecycle, compatibility, packaging, and conformance plan is in [`plugin-spi-v0.1-plan.md`](plugin-spi-v0.1-plan.md), with a machine-readable candidate in [`plugin-spi-v0.1-surface.json`](plugin-spi-v0.1-surface.json).

Nodal adopts:

> **Explicit plugin plan, typed capability graph, deterministic phases, isolated extension boundaries, retained provenance.**

VexiiRiscv proves that an almost-empty hardware host can compose a large architecture from plugins, typed services, and phased contributions. Nodal retains local host composition, optional/multiple services, and late aggregation, but replaces runtime class identity, mutable cross-plugin access, manual retain/release ordering, and in-process-only loading with versioned manifests, stable capability keys, deterministic resolution, lockfiles, phase contexts, and separate Scala/native/process boundaries.

Plugin categories are:

- `DesignPlugin`: local configurable design/subsystem composition through public Nodal APIs;
- `FrontendPlugin`: approved namespaced metadata, external-operation, attribute, lint, diagnostic, and helper descriptors;
- `CompilerPlugin`: MLIR passes, analyses, dialects, verifiers, and named compiler extension points;
- `BackendPlugin`: explicitly selected output backend and capability profile;
- `ToolAdapterPlugin`: out-of-process simulator, synthesis, formal, FPGA, programmer, board, HIL, waveform, or reporting integration.

A reusable model library is not a plugin. It remains passive source/data content. A project may publish a separately enabled companion plugin with its own identity and compatibility contract.

Directional design-composition shape:

```scala
object FetchService extends CapabilityKey[FetchApi](
  id = "com.example.cpu.fetch",
  version = 1,
  cardinality = ExactlyOne
)

object DecodeRules extends ContributionKey[DecodeRule](
  id = "com.example.cpu.decode-rules",
  version = 1
)

final class BranchPlugin(config: BranchConfig) extends DesignPlugin:
  override val descriptor =
    plugin("com.example.nodal.branch", version = "1.0.0")
      .requires(FetchService)
      .contributes(DecodeRules)

  override def declare(ctx: DeclareContext): Unit =
    ctx.contribute(DecodeRules, branchRules(config))

  override def elaborate(ctx: ElaborateContext): Unit =
    val fetch = ctx.require(FetchService)
    // Construct hardware through public Nodal APIs.

val subsystem = DesignHost(
  plugins = Seq(FetchPlugin(...), DecodePlugin(...), BranchPlugin(...))
).build()
```

Exact syntax is deferred to Increment 79. Binding rules are:

- plugin and capability identities are stable globally qualified strings with independent versions;
- provider cardinality, qualifier, conflicts, replacements, options, and compatibility are resolved before executable code loads;
- a local host owns one immutable plugin plan and capability scope; nested hosts import/export capabilities explicitly;
- design contributions are typed, append-only, source-located, and closed at declared phases;
- discovery and resolution execute no plugin code;
- lifecycle phases are `discover`, `resolve`, `configure`, `declare`, `elaborate`, `transform`, `verify`, `emit`, `run`, and `report`;
- native plugins wrap MLIR pass/dialect plugin APIs and require exact pinned Nodal/LLVM/MLIR/CIRCT build compatibility;
- out-of-process transforms and tool adapters use versioned protocols and cannot leave partially accepted compiler state after crash, timeout, or malformed output;
- third-party backends are explicit in SPI v0.1 and do not silently join `Backend.Auto`;
- plugin graph/order/options/artifact/toolchain/process hashes participate in build manifests, provenance, release evidence, and cache invalidation;
- classpath scanning, process-global hosts, concrete-plugin lookup, direct mutable access, public retain/release ordering, core-semantic override, and hidden core-to-plugin dependency are rejected.

The plugin SPI gate freezes manifest and lockfile schemas, capability cardinality, local design-host behavior, native/process compatibility, diagnostics, trust classes, and extension boundaries before implementation.


'''
    text = replace_once(
        text,
        "## Core and future library boundary",
        plugin_section + "## Core, plugin, and future library boundary",
        subject="plugin architecture section",
    )

    boundary_pattern = (
        r"## Core, plugin, and future library boundary\n.*?"
        r"(?=\n## Target scalable repository structure)"
    )
    boundary_replacement = r'''## Core, plugin, and future library boundary

```text
user project
    ├── depends directly on Nodal core
    ├── may select zero or more passive Nodal libraries
    └── may explicitly enable zero or more executable Nodal plugins

libraries ───────────────► published core APIs
plugins ─────────────────► published core SPI/APIs
plugins ── optional ─────► published libraries
core ──X─────────────────► libraries or plugins
libraries ──X────────────► plugin implementations
```

- `core/` contains the language/API, plugin SPI and resolver, construction frontend, MLIR bridge/compiler, diagnostics, built-in backends, simulation API, adapters, and mandatory tests.
- `libraries/` is reserved for optional passive reusable models, interfaces, helpers, and verification packages.
- `plugins/` is reserved for optional executable extension bundles or conformance fixtures; production plugins may live in independent repositories.
- A core-only project must compile with no library or plugin checkout/artifact.
- Future libraries and plugins receive no privileged access to `internal`, frontend, compiler, backend, or simulator implementation packages beyond their approved SPI/API.
- Installing a library does not enable executable plugin code. A companion plugin has a distinct artifact identity and explicit project configuration.
- Each library or plugin owns independent source roots, tests, documentation, semantic version, compatibility range, license/provenance, and publication metadata.
'''
    text = replace_regex_once(
        text,
        boundary_pattern,
        boundary_replacement,
        subject="core plugin library boundary",
        flags=re.DOTALL,
    )

    structure_anchor = "├── libraries/                     # Reserved for future optional packages"
    structure_replacement = (
        structure_anchor
        + "\n├── plugins/                       # Reserved for optional executable extension bundles"
    )
    text = replace_once(
        text,
        structure_anchor,
        structure_replacement,
        subject="repository plugin root",
    )
    text = text.replace(
        "├── packaging/                     # Core and future independent library publication",
        "├── packaging/                     # Core, library, and plugin publication/provenance",
        1,
    )
    text = text.replace(
        "Empty future-library directories are not committed merely as placeholders.",
        "Empty future-library or plugin directories are not committed merely as placeholders.",
        1,
    )

    text = text.replace(
        "- **M4 — Scalable core release:** packaged compiler, complete reference, stable extension points, library-author contract, and compatibility policy.",
        "- **M4 — Scalable core release:** packaged compiler, complete reference, frozen plugin SPI, deterministic extension graph, conformance kit, library-author contract, and compatibility policy.",
        1,
    )

    phase6_match = re.search(
        r"## Phase 6 — FPGA-accelerated AMS approximation and hardware validation\n.*?(?=\n## Deferred reusable library roadmap)",
        text,
        flags=re.DOTALL,
    )
    if phase6_match is None:
        raise SystemExit("could not find Phase 6 block")
    shifted_phase6 = shift_increment_references(phase6_match.group(0), 91, 99, 5)

    new_phase5 = r'''## Phase 5 — Plugins, extensibility, scale, documentation, and release

- [ ] **Increment 79 — Plugin architecture gate and SPI v0.1 contracts**
  - Use [ADR 0012](../architecture/0012-versioned-capability-plugin-architecture.md), [`plugin-spi-v0.1-plan.md`](plugin-spi-v0.1-plan.md), and [`plugin-spi-v0.1-surface.json`](plugin-spi-v0.1-surface.json) as the mandatory architecture and candidate.
  - Compile plugin descriptors/manifests, stable plugin/capability IDs, versions, cardinalities, qualifiers, `DesignPlugin`, local `DesignHost`, typed services/contributions, phase contexts, backend IDs, native/process descriptors, and library-versus-plugin separation.
  - Publish `NodalPluginSpi-DG-v0.1.md`, manifest/lockfile schemas, compatibility/trust policy, machine-readable frozen SPI, and positive/negative fixtures with stable diagnostics.
  - Keep loaders and plugin execution inert. Mark this increment `[x]` only after every SPI freeze criterion in the detailed plan passes CI.

- [ ] **Increment 80 — Manifest resolver, capability graph, lockfile, and plugin CLI**
  - Implement manifest-only discovery without code execution; validate SPI/core/API/IR/bridge/toolchain ranges, provided/required capability versions, cardinality, qualifiers, conflicts, replacements, platform artifacts, trust, and option schemas.
  - Resolve a canonical immutable plugin plan, reject ambiguity and cycles, generate `nodal.plugins.lock`, and include graph/artifact/options hashes in build manifests and cache keys.
  - Add `nodal plugins list/resolve/check/graph/explain/lock/inspect` with human and machine-readable evidence plus offline locked mode.

- [ ] **Increment 81 — Local design composition host and typed contribution system**
  - Implement local `DesignHost` scopes, phase-specific contexts, stable capability keys, exactly-one/optional/many/qualified providers, contribution sets/sequences, close phases, nested explicit import/export, stable plugin instance qualifiers, names, and provenance.
  - Prohibit process-global registries, concrete-plugin lookup, direct mutable cross-plugin access, public retain/release ordering, implicit first-provider selection, and undeclared contributions.
  - Add configurable digital/mixed-signal subsystem fixtures, multiple instances, nested hosts, conflict/cycle diagnostics, and declaration-order permutation tests producing identical IR/HDL/reports.

- [ ] **Increment 82 — Native compiler plugin loader and versioned extension points**
  - Wrap MLIR pass and dialect plugin APIs with Nodal manifest validation, exact native ABI/toolchain-build matching, plugin-owned namespaces, analysis preservation/invalidation, named versioned pipeline extension points, normalized pass evidence, and mandatory core re-verification.
  - Add out-of-process transform protocol for isolated/longer-lived extensions, with versioned IR exchange, diagnostics, output hashes, cancellation, timeout, crash, and malformed-response handling.
  - Provide out-of-tree pass, analysis, dialect, verifier, and transform fixtures using no private core APIs.

- [ ] **Increment 83 — Backend and external tool-adapter plugins**
  - Implement explicit third-party backend registration, capability profiles, options/artifact/source-map contracts, deterministic selection, and rejection before translation. Keep plugin backends out of `Backend.Auto` by default.
  - Implement one versioned out-of-process adapter/evidence protocol for simulators, synthesis, formal, FPGA place/route, bitstreams, programmers, boards, HIL, waveforms, and reporters.
  - Migrate built-in external adapters to the common protocol while preserving licensing-safe CI and the rule that external tools never define language semantics.

- [ ] **Increment 84 — Plugin packaging, trust, provenance, caching, and conformance**
  - Define coordinated Scala/Maven, native-platform, process-executable, schema/support-file, checksum/signature, license, SBOM, and provenance packaging with explicit trusted-Scala/trusted-native/process-isolated policies.
  - Integrate plugin graphs, artifacts, options, pass order, external commands, and outputs into incremental caching, release provenance, reproducibility, and offline resolution.
  - Publish a plugin conformance kit plus out-of-tree design, frontend-lint, MLIR pass/dialect, backend, and tool-adapter reference plugins. Prove compatibility failures, crash isolation, load-order determinism, and no hidden core dependency.

- [ ] **Increment 85 — Versioned IR and bridge compatibility**
  - Add Nodal dialect/bridge/plugin-plan version metadata, supported upgrades, old-version fixtures, plugin extension-point compatibility, and explicit unknown-future-version rejection.

- [ ] **Increment 86 — Incremental build and compiler caching**
  - Cache construction, normalized MLIR, plugin resolution/transforms, native compilation, reports, and backend/tool outputs by content/toolchain/profile/plugin-plan hashes with proven invalidation.

- [ ] **Increment 87 — Future library architecture and publication contract**
  - Define passive library module conventions, Maven coordinates, resources, independent versions, core ranges, conflicts, licenses, offline use, and external public-API-only fixtures. Keep executable companion plugins separately packaged and explicitly enabled.

- [ ] **Increment 88 — Complete language, plugin SPI, and API documentation**
  - Cover value staging and generate, numeric/width/overflow, aggregates/connections/protocols, quantities/effects, domains/CDC/RDC/reset, automatic pipelines, portable Verilog/backend inference, open-source verification, mixed-signal boundaries, plugin manifests/capabilities/lifecycle/loaders/adapters/trust/lockfiles, diagnostics, libraries, and migration.

- [ ] **Increment 89 — Tutorials, plugin-author guides, and cross-project reuse examples**
  - Add progressive analog/AMS/domain tutorials, patterns/anti-patterns, standalone external consumers, and out-of-tree design/compiler/backend/tool plugin author tutorials with conformance commands.

- [ ] **Increment 90 — Cross-platform core and plugin packaging**
  - Produce checksummed core Scala/native bundles for supported Linux/macOS first, Windows strategy and source fallback, plus plugin bundle/platform conventions and stable hooks for independently published libraries/plugins.

- [ ] **Increment 91 — Reproducible release, provenance, plugin lockfiles, and SBOM**
  - Add release automation, checksums/signatures where possible, dependency/plugin SBOM, plugin lockfile and graph provenance, toolchain/pass/adapter evidence, license inventory, and rebuild verification.

- [ ] **Increment 92 — Performance and scalability benchmarks**
  - Benchmark construction, MLIR, semantic analyses, automatic pipelines, domains/CDC/RDC, portable Verilog, open-source verification, plugin manifest resolution, capability graphs, design-host contributions, native/process plugin overhead, cache behavior, pass time, memory, hierarchy, and regression launch.

- [ ] **Increment 93 — Public API and plugin SPI v1 review**
  - Review v0.1/v0.2/v0.3 APIs and plugin SPI implementation experience, including capability identity/cardinality, phase contexts, native/process compatibility, trust, determinism, plugin/library boundaries, implicit domains, pipelines, backend inference, and low-level escape. Approve only justified changes and define semantic versioning/deprecation/source/SPI compatibility.

- [ ] **Increment 94 — Nodal core preview release**
  - Publish the supported preview with frozen public API and plugin SPI revisions, toolchain pins, portable Verilog/Verilog-A/Verilog-AMS matrices, open-source verification evidence, plugin conformance kit, installation, examples, known limitations, library/plugin-author contracts, and reproducible provenance.

- [ ] **Increment 95 — Future SystemVerilog-AMS backend research gate**
  - Reassess the current standard, map IR/plugin/backend coverage, identify required changes, and approve or reject implementation through a separate gate without speculating syntax into the stable API.
'''

    phase_pattern = r"## Phase 5 — Extensibility, scale, documentation, and release\n.*?(?=\n## Deferred reusable library roadmap)"
    text = replace_regex_once(
        text,
        phase_pattern,
        new_phase5 + "\n" + shifted_phase6,
        subject="plugin-aware Phase 5 and shifted Phase 6",
        flags=re.DOTALL,
    )

    text = text.replace(
        "No official reusable model/component library is implemented by Increments 0-99.",
        "No official reusable model/component library or production plugin is implemented by Increments 0-104.",
        1,
    )
    text = text.replace(
        "independently approved library roadmaps may populate `libraries/` or separate repositories",
        "independently approved library/plugin roadmaps may populate `libraries/`, `plugins/`, or separate repositories",
        1,
    )

    reference_anchor = "- Verilog-AMS standards: <https://accellera.org/downloads/standards/v-ams>"
    plugin_references = "\n".join(
        (
            "- VexiiRiscv plugin host: <https://github.com/SpinalHDL/VexiiRiscv/blob/dev/src/main/scala/vexiiriscv/VexiiRiscv.scala>",
            "- VexiiRiscv typed plugin services: <https://github.com/SpinalHDL/VexiiRiscv/blob/dev/src/main/scala/vexiiriscv/execute/BranchPlugin.scala>",
            "- SpinalHDL PluginHost: <https://github.com/SpinalHDL/SpinalHDL/blob/dev/lib/src/main/scala/spinal/lib/misc/plugin/Host.scala>",
            "- SpinalHDL FiberPlugin lifecycle: <https://github.com/SpinalHDL/SpinalHDL/blob/dev/lib/src/main/scala/spinal/lib/misc/plugin/Fiber.scala>",
            "- MLIR pass plugin API: <https://github.com/llvm/llvm-project/blob/main/mlir/include/mlir/Tools/Plugins/PassPlugin.h>",
            "- MLIR dialect plugin API: <https://github.com/llvm/llvm-project/blob/main/mlir/include/mlir/Tools/Plugins/DialectPlugin.h>",
        )
    )
    text = replace_once(
        text,
        reference_anchor,
        plugin_references + "\n" + reference_anchor,
        subject="plugin references",
    )

    numbers = [
        int(match.group(1))
        for match in re.finditer(r"^- \[[ x]\] \*\*Increment (\d+) —", text, flags=re.MULTILINE)
    ]
    expected = list(range(105))
    if numbers != expected:
        raise SystemExit(
            f"roadmap numbering mismatch: got {numbers[:20]}...{numbers[-20:]}, expected 0-104"
        )
    for completed in range(12):
        if f"- [x] **Increment {completed} —" not in text:
            raise SystemExit(f"completed Increment {completed} changed or disappeared")
    if "- [ ] **Increment 12 —" not in text:
        raise SystemExit("Increment 12 is no longer the first unchecked roadmap item")

    ROADMAP.write_text(text, encoding="utf-8")


def update_plugin_integration_notes() -> None:
    adr10 = ROOT / "docs/architecture/0010-digital-verilog-open-source-verification.md"
    text = adr10.read_text(encoding="utf-8")
    note = r'''## Plugin adapter boundary

[ADR 0012](0012-versioned-capability-plugin-architecture.md) standardizes external simulator, synthesis, formal, FPGA, waveform, and reporting integrations as versioned out-of-process tool-adapter plugins. Built-in adapters use the same manifest, capability, process, evidence, trust, and provenance envelope as third-party adapters.

Adapter selection is explicit and locked. Installing an adapter must not change `Backend.Auto`, language semantics, or required verification profiles silently.

'''
    text = replace_once(text, "## CI tiers", note + "## CI tiers", subject="digital adapter note")
    adr10.write_text(text, encoding="utf-8")

    digital_plan = ROOT / "docs/roadmap/digital-verilog-open-source-verification-plan.md"
    text = digital_plan.read_text(encoding="utf-8")
    quote = "> **Infer the narrowest compatible backend, verify generated HDL through independent open-source tools, and retain evidence.**"
    replacement = quote + (
        "\n\nExternal simulators, synthesis/formal tools, FPGA flows, waveform tools, and reporters "
        "use the versioned tool-adapter plugin protocol from [ADR 0012](../architecture/0012-versioned-capability-plugin-architecture.md) "
        "and [`plugin-spi-v0.1-plan.md`](plugin-spi-v0.1-plan.md). Built-in adapters remain explicit, locked, and semantically equivalent to third-party adapters."
    )
    text = replace_once(text, quote, replacement, subject="digital plan plugin note")
    digital_plan.write_text(text, encoding="utf-8")

    adr11 = ROOT / "docs/architecture/0011-ams-fpga-approximation-validation.md"
    text = adr11.read_text(encoding="utf-8")
    text = shift_increment_references(text, 91, 99, 5)
    note = r'''## Plugin adapter boundary

The approximation transformation, sampled-state IR, solver semantics, numeric/error analysis, and validation ladder remain core compiler semantics. FPGA target databases, place/route flows, bitstream packers, programmers, board runtimes, external ADC/DAC profiles, and HIL transports use the versioned tool-adapter plugin protocol from [ADR 0012](0012-versioned-capability-plugin-architecture.md).

A plugin target cannot weaken the declared approximation envelope, error budget, deadline proof, or claims language.

'''
    text = replace_once(text, "## Consequences", note + "## Consequences", subject="AMS FPGA plugin note")
    adr11.write_text(text, encoding="utf-8")

    ams_plan = ROOT / "docs/roadmap/ams-fpga-validation-plan.md"
    text = ams_plan.read_text(encoding="utf-8")
    text = shift_increment_references(text, 91, 99, 5)
    quote = "> **Reference AMS semantics, explicit approximation contract, bounded evidence, synthesizable realization.**"
    replacement = quote + (
        "\n\nThe compiler-owned approximation semantics are not plugins. FPGA targets, place/route, "
        "bitstream, programmer, board, and HIL integrations use the explicit tool-adapter plugin "
        "protocol from [ADR 0012](../architecture/0012-versioned-capability-plugin-architecture.md) "
        "and [`plugin-spi-v0.1-plan.md`](plugin-spi-v0.1-plan.md)."
    )
    text = replace_once(text, quote, replacement, subject="AMS plan plugin note")
    ams_plan.write_text(text, encoding="utf-8")

    surface_path = ROOT / "docs/roadmap/ams-fpga-validation-surface.json"
    surface = json.loads(surface_path.read_text(encoding="utf-8"))
    surface["roadmap_revision"] = "1.6"
    surface["formal_gate_increment"] = 96
    surface["plugin_boundary"] = {
        "core_semantics": [
            "approximation transformation",
            "sampled state IR",
            "solver and recurrence",
            "fixed point and error analysis",
            "validation ladder"
        ],
        "tool_adapter_plugins": [
            "FPGA target",
            "place and route",
            "bitstream",
            "programmer",
            "board runtime",
            "external ADC/DAC profile",
            "HIL transport"
        ],
        "architecture": "docs/architecture/0012-versioned-capability-plugin-architecture.md"
    }
    increments = surface.get("increments", {})
    shifted: dict[str, object] = {}
    for key, value in increments.items():
        number = int(key)
        shifted[str(number + 5 if 91 <= number <= 99 else number)] = value
    surface["increments"] = shifted
    surface_path.write_text(json.dumps(surface, indent=2) + "\n", encoding="utf-8")


def validate_json() -> None:
    for path in sorted((ROOT / "docs/roadmap").glob("*.json")):
        json.loads(path.read_text(encoding="utf-8"))


def normalize_markdown() -> None:
    paths = [
        ROADMAP,
        ROOT / "docs/architecture/0010-digital-verilog-open-source-verification.md",
        ROOT / "docs/architecture/0011-ams-fpga-approximation-validation.md",
        ROOT / "docs/roadmap/digital-verilog-open-source-verification-plan.md",
        ROOT / "docs/roadmap/ams-fpga-validation-plan.md",
    ]
    for path in paths:
        text = path.read_text(encoding="utf-8")
        path.write_text("\n".join(line.rstrip() for line in text.splitlines()) + "\n", encoding="utf-8")


def main() -> None:
    update_roadmap()
    update_plugin_integration_notes()
    normalize_markdown()
    validate_json()
    print("plugin architecture roadmap revision applied")


if __name__ == "__main__":
    main()
