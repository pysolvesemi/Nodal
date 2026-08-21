#!/usr/bin/env python3
"""Apply the core-semantics and pure-digital roadmap revision."""

from __future__ import annotations

import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ROADMAP = ROOT / "docs/roadmap/nodal-development-todo.md"


def map_increment(number: int) -> int:
    if 13 <= number <= 63:
        return number + 1
    if 64 <= number <= 86:
        return number + 4
    return number


def renumber_increment_lines(text: str) -> str:
    def rewrite_line(line: str) -> str:
        if "Increment" not in line:
            return line

        def replace_number(match: re.Match[str]) -> str:
            number = int(match.group(0))
            return str(map_increment(number))

        return re.sub(r"(?<![\w.])\d+(?![\w.])", replace_number, line)

    return "\n".join(rewrite_line(line) for line in text.split("\n"))


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
        raise SystemExit(f"{subject}: expected one regex match, found {count}: {pattern!r}")
    return updated


def update_roadmap() -> None:
    text = ROADMAP.read_text(encoding="utf-8")
    text = replace_once(
        text,
        "**Revision:** 1.3",
        "**Revision:** 1.4",
        subject="roadmap revision",
    )

    text = renumber_increment_lines(text)

    fixed_anchor = (
        "- Distinguish fixed-rate, valid-only, and elastic ready/valid pipelines in the "
        "type system. Insert and balance only pipeline-owned registers and protocol buffers "
        "inside an approved pipeline region."
    )
    fixed_replacement = fixed_anchor + "\n" + "\n".join(
        (
            "- Distinguish elaboration-only Scala values, symbolic HDL parameters/constants, and dynamic hardware values. Target-visible generation is explicit and never inferred from ordinary Scala control.",
            "- Use lossless finite-width arithmetic by default. Narrowing, wrap, truncation, saturation, checked resize, and signedness conversion require explicit intent.",
            "- Keep aggregate payloads directionless; apply direction at ports, use plain/`Valid`/`Stream` protocol types consistently, and require exact direct connections with typed adapters for intentional conversion.",
            "- Preserve physical dimensions for analog and mixed-signal quantities and reject incompatible equations before HDL generation without exposing verbose unit types in normal source.",
            "- Classify memory, external, analog, stateful, observational, and side-effecting operations explicitly so scheduling, optimization, and verification never guess latency or purity.",
            "- Classify complete designs as digital-only, analog-only, or mixed-signal. `Backend.Auto` selects the narrowest compatible backend, including portable Verilog for digital-only designs.",
            "- Verify generated pure-digital HDL through a pinned open-source matrix using Verilator, Icarus Verilog, Yosys, SBY, and optional cocotb interoperability.",
        )
    )
    text = replace_once(
        text,
        fixed_anchor,
        fixed_replacement,
        subject="fixed project direction",
    )

    public_anchor = (
        "- Provide a compact automatic-pipeline surface centered on `pipe`, `delay`, "
        "protocol-typed transactions, latency/throughput policies, automatic sideband "
        "alignment, and optional hard stage constraints. Do not expose node/link plumbing "
        "in ordinary datapath source."
    )
    public_replacement = public_anchor + "\n" + "\n".join(
        (
            "- Freeze value staging, lossless numeric/width rules, directionless aggregates, exact connections, physical quantities, memory/external effects, and automatic pipelines in one coherent public API v0.3 gate.",
            "- Provide explicit lossy numeric conversions such as truncate, wrap, saturate, and checked resize; never narrow or reinterpret signedness silently.",
            "- Treat `Valid[T]` and `Stream[T]` as general protocol types shared by ports, hierarchy, memories, simulation, and automatic pipelines.",
            "- Add `Backend.Auto` and `Backend.Verilog` for pure-digital output while retaining explicit `Backend.VerilogA` and `Backend.VerilogAMS` profiles.",
        )
    )
    text = replace_once(
        text,
        public_anchor,
        public_replacement,
        subject="public API direction",
    )

    core_semantics_section = r'''## Core semantic architecture

The binding architecture is [ADR 0009](../architecture/0009-core-semantic-contracts.md). The exact candidate, compile matrix, and unified freeze criteria are in [`core-semantics-api-v0.3-plan.md`](core-semantics-api-v0.3-plan.md), with a machine-readable candidate in [`core-semantics-api-v0.3-surface.json`](core-semantics-api-v0.3-surface.json).

Nodal adopts:

> **Explicit stage, lossless value semantics, exact connection, dimension-safe quantity, declared effect.**

### Value stages

Nodal distinguishes:

- ordinary Scala values used only during elaboration;
- symbolic `Param`/constant/width/range/generate values preserved in target HDL;
- dynamic ports, wires, registers, memories, protocols, and sampled analog values.

A symbolic parameter is not a Scala `Int`; a runtime signal cannot control hardware shape. Target-visible replication uses an explicit symbolic `generate(...)` construct rather than an ordinary Scala loop.

### Numeric and width policy

Ordinary finite-width arithmetic retains mathematically required result bits. Narrowing and signedness changes require explicit policy through candidates such as `extend`, `truncate`, `wrap`, `saturate`, `resizeChecked`, `toSigned`, and `toUnsigned`.

Assignment never silently truncates, wraps, saturates, or reinterprets. Automatic scheduling preserves the exact typed arithmetic graph and may not reassociate expressions or change overflow/rounding behavior.

### Directionless aggregates and protocols

Reusable aggregate payloads are directionless. `in(...)`, `out(...)`, and `inout(...)` apply direction at the boundary. Plain values, `Valid[T]`, and `Stream[T]` are shared transport types for ports, hierarchy, memories, simulation, and pipelines.

Direct connection is exact: no implicit resize, field loss, protocol conversion, domain crossing, or latency insertion. Intentional transformation uses a typed adapter/view contract.

### Physical quantities

Voltage, current, resistance, capacitance, time, frequency, charge, power, and dimensionless values retain physical dimensions through expressions and symbolic parameters. Addition/comparison require compatible dimensions; multiplication/division and `ddt`/`idt` derive dimensions. Unit mistakes fail before HDL generation.

### Effects, memories, and external operations

The compiler distinguishes pure combinational work from state, memory, analog contribution, events/observation, external operations, and side effects. Only pure or explicitly movable operations may be scheduled or retimed.

Memory declarations define read mode/latency, write masks, read-under-write, collision/ordering, domains, and initialization capability. External operations define type/protocol, latency, throughput, domain/reset, effect, ordering, and simulation/synthesis/formal models. Unknown behavior is a barrier, never a guessed default.


'''
    text = replace_once(
        text,
        "## Automatic pipeline architecture",
        core_semantics_section + "## Automatic pipeline architecture",
        subject="core semantic section",
    )

    pipeline_intro = (
        "The proposed architecture is [ADR 0008](../architecture/0008-automatic-pipeline-architecture.md). "
        "The candidate API, staged delivery plan, and freeze criteria are in "
        "[`automatic-pipeline-api-v0.3-plan.md`](automatic-pipeline-api-v0.3-plan.md), with a "
        "machine-readable candidate in "
        "[`automatic-pipeline-api-v0.3-surface.json`](automatic-pipeline-api-v0.3-surface.json)."
    )
    pipeline_replacement = pipeline_intro + (
        " The pipeline candidate depends on ADR 0009 and the core-semantics v0.3 plan; "
        "Increment 15 freezes both surfaces in one gate."
    )
    text = replace_once(
        text,
        pipeline_intro,
        pipeline_replacement,
        subject="pipeline semantic dependency",
    )

    text = replace_regex_once(
        text,
        r"Candidate controls are `pipe`.*?before scheduler implementation\.",
        "Candidate controls are `pipe`, `delay`, `Latency.Auto`, `Latency.Exact`, "
        "`Latency.Range`, `Throughput.EveryCycle`, ready-path policy, `stage(value)` as a "
        "hard cut, `sameStage { ... }`, and typed fixed/variable-latency operator contracts. "
        "Increment 14 compares exact pipeline forms against the Increment 13 semantic "
        "candidates; Increment 15 freezes the unified v0.3 surface and diagnostics before "
        "scheduler implementation.",
        subject="pipeline increment references",
        flags=re.DOTALL,
    )

    digital_section = r'''## Pure-digital backend and open-source verification

The binding architecture is [ADR 0010](../architecture/0010-digital-verilog-open-source-verification.md). The complete tool, capability, simulation, synthesis, equivalence, formal, and CI plan is in [`digital-verilog-open-source-verification-plan.md`](digital-verilog-open-source-verification-plan.md), with the public candidate in [`digital-backend-v0.3-surface.json`](digital-backend-v0.3-surface.json).

Nodal classifies each complete design as digital-only, analog-only, mixed-signal, or unsupported. The v0.3 backend candidate is:

```scala
Backend.Auto
Backend.Verilog
Backend.VerilogA
Backend.VerilogAMS
```

`Backend.Auto` selects portable Verilog for digital-only designs, Verilog-A for analog-only designs, and Verilog-AMS for mixed-signal designs. Selection is deterministic and recorded in the emission manifest; it never depends on locally installed tools.

The first digital profile is a conservative synthesizable Verilog-2005-style subset. High-level aggregates and protocols are flattened deterministically, while symbolic parameters, hierarchy, clocks/resets, CDC/RDC, memories, and automatic pipeline structures remain explicit and reviewable.

Open-source verification exercises generated HDL rather than a separate frontend model:

- Verilator for strong lint and fast compiled simulation;
- Icarus Verilog for independent event-driven parse/elaboration/simulation;
- Yosys for synthesis, structural checks, netlists, and equivalence;
- SBY for safety, cover, induction, and selected liveness proofs;
- optional cocotb interoperability alongside the primary Scala simulation API.

Required CI retains tool versions, commands, hashes, logs, waveforms, synthesis reports, equivalence results, and counterexamples. A future explicit SystemVerilog profile may be added separately; it cannot replace the portable Verilog path.


'''
    text = replace_once(
        text,
        "## Core and future library boundary",
        digital_section + "## Core and future library boundary",
        subject="digital backend section",
    )

    old_phase0_pattern = (
        r"\n- \[ \] \*\*Increment 14 — Automatic pipeline candidate prototypes and architecture comparison\*\*"
        r".*?(?=\n## Phase 1)"
    )
    new_phase0 = r'''
- [ ] **Increment 13 — Core semantic candidate prototypes and architecture comparison**
  - Use [ADR 0009](../architecture/0009-core-semantic-contracts.md), [`core-semantics-api-v0.3-plan.md`](core-semantics-api-v0.3-plan.md), and [`core-semantics-api-v0.3-surface.json`](core-semantics-api-v0.3-surface.json) as the mandatory candidate.
  - Compile and compare elaboration-only Scala values, symbolic `Param`/constant/width/range/generate values, and dynamic hardware values. Freeze candidate target `generate(...)` behavior and stage-mixing diagnostics.
  - Compile lossless unsigned/signed arithmetic, symbolic width rules, explicit extend/truncate/wrap/saturate/checked-resize/signedness conversions, and negative implicit-narrowing fixtures.
  - Compile directionless nested aggregates/vectors, exact port/connection semantics, typed adapters/views, and general plain/`Valid`/`Stream` protocols.
  - Compile dimension-safe analog quantities and negative unit equations without exposing verbose dimension types in ordinary source.
  - Compile explicit memory and external-operation contracts covering read latency, read-under-write, masks, ordering, domains, effects, throughput, and model availability. Reject unknown latency/effect in movable pipeline regions.
  - Include an external-library consumer using only public candidate APIs; keep frontend/backend behavior inert.

- [ ] **Increment 14 — Automatic pipeline candidate prototypes and architecture comparison**
  - Compile `pipe`, `delay`, plain/`Valid`/`Stream` protocols, exact/ranged/auto latency, throughput and ready-path policy, automatic sideband transport and reconvergence balancing, `stage`/`sameStage`, schedule inspection, parameter envelopes, and fixed/variable-latency operators against Increment 13 semantics.
  - Compare current Chisel `Pipe`/`ShiftRegister`/`Queue`/`Decoupled`, current SpinalHDL `Node`/`Payload`/`Link`/`Builder`, and CIRCT `pipeline`/ESI. Retain useful semantics without exposing lower-level graph plumbing.
  - Prove that arithmetic, aggregate, protocol, quantity, memory, effect, clock/reset, CDC/RDC, and native parameterized-module contracts remain unchanged by the candidate scheduler surface.

- [ ] **Increment 15 — Unified core semantics and automatic pipeline public API v0.3 freeze**
  - Publish `docs/design-gates/NodalCoreSemanticsPipelineApi-DG-v0.3.md`, migration notes, and an updated machine-readable public API manifest using ADRs 0009/0008 and both v0.3 candidate plans/surfaces.
  - Freeze value stages and target generate; lossless numeric/width/signedness rules; explicit lossy conversions; directionless aggregates; exact connections/adapters; plain/`Valid`/`Stream`; physical quantities; memory/external effect contracts; `pipe`/`delay`; latency/throughput/ready policy; stage constraints; parameter-envelope scheduling; and schedule evidence.
  - Freeze `Backend.Auto`, `Backend.Verilog`, design-kind reporting, and explicit synth/sim/formal digital profiles from ADR 0010 and the digital-backend candidate.
  - Add positive and negative compile contracts for every candidate category, including external-library use, stable diagnostic codes/source locations, and v0.1/v0.2 migration behavior.
  - Keep elaboration, scheduler, digital backend, and simulator behavior inert. Mark this increment `[x]` only when the unified gate, manifests, fixtures, diagnostics, and CI satisfy all linked exit criteria.
'''
    text = replace_regex_once(
        text,
        old_phase0_pattern,
        new_phase0,
        subject="phase 0 semantic/pipeline increments",
        flags=re.DOTALL,
    )

    digital_increments = r'''
- [ ] **Increment 65 — Digital-only classification, Backend.Auto, and portable Verilog backend**
  - Implement transitive digital-only/analog-only/mixed-signal classification, construct inventories, deterministic `Backend.Auto` selection, explicit capability rejection, and machine-readable selection evidence.
  - Emit the portable synthesizable Verilog profile with symbolic parameters/generate, hierarchy, flattened aggregates/protocols, clocks/resets, memories, CDC/RDC, automatic pipelines, black boxes, assertions/formal hooks, source maps, deterministic formatting, and exact golden fixtures.
  - Keep broad SystemVerilog optional and separately gated; portable Verilog remains required for open-source interoperability.

- [ ] **Increment 66 — Open-source digital lint, simulation, waveforms, and cocotb interoperability**
  - Pin and integrate Verilator and Icarus Verilog; run independent parse/elaboration, strong lint, fast compiled simulation, event-driven smoke simulation, normalized diagnostics, deterministic seeds, VCD/FST waveforms, and supported coverage.
  - Extend the Scala simulation API with typed digital/aggregate/protocol access, clock/reset-domain stimulus, multiple clocks, randomized reset release, `Valid`/`Stream` drivers/monitors/scoreboards, stalls/bubbles, latency-aware checking, timeouts, caching, and artifacts.
  - Add optional cocotb metadata/runner support for Icarus and Verilator without making Python or cocotb define Nodal semantics.

- [ ] **Increment 67 — Yosys synthesis/equivalence and SBY formal verification**
  - Pin and integrate Yosys, SBY, and selected solvers. Run hierarchy/process/memory checks, target-neutral synthesis, inferred-latch/loop/black-box diagnostics, normalized netlist emission, statistics, and parameter elaboration matrices.
  - Add RTL-to-optimized/netlist equivalence, including latency-aware fixed-pipeline and protocol-aware elastic checks.
  - Add bounded/unbounded safety, cover, and selected liveness property suites for registers, resets, `Valid`/`Stream`, FIFOs, handshakes, synchronizers, CDC/RDC wrappers, and automatic pipelines. Retain traces and counterexamples as CI evidence.
'''
    text = replace_once(
        text,
        "\n- [ ] **Increment 68 — Discrete real and mixed-signal net types**",
        "\n" + digital_increments + "\n- [ ] **Increment 68 — Discrete real and mixed-signal net types**",
        subject="digital verification increments",
    )

    text = text.replace(
        "## Phase 4 — Digital semantics, clock/reset domains, mixed signal, and Verilog-AMS",
        "## Phase 4 — Digital semantics, portable Verilog, open-source verification, mixed signal, and Verilog-AMS",
    )

    text = text.replace(
        "- **M0 — Foundation:** reproducible builds, CI, clock/reset and automatic-pipeline API freezes, frozen contracts, and enforced core/library boundaries.",
        "- **M0 — Foundation:** reproducible builds, CI, clock/reset plus unified core-semantics/automatic-pipeline API freezes, digital-backend selection contract, and enforced core/library boundaries.",
    )
    text = text.replace(
        "- **M3 — AMS preview:** implicit-domain digital state, automatic fixed/valid/elastic pipelines, CDC/RDC-safe clock/reset architecture, mixed-signal crossings, and Verilog-AMS emission.",
        "- **M3 — Digital/AMS preview:** implicit-domain digital state, automatic fixed/valid/elastic pipelines, portable Verilog with open-source simulation/synthesis/formal verification, CDC/RDC-safe clock/reset architecture, mixed-signal crossings, and Verilog-AMS emission.",
    )

    text = replace_regex_once(
        text,
        r"- \[ \] \*\*Increment 83 — Complete language reference and API documentation\*\*\n  - .*",
        "- [ ] **Increment 83 — Complete language reference and API documentation**\n"
        "  - Cover value staging and target generate, numeric/width/overflow rules, directionless aggregates, exact adapters/connections, physical quantities, effects/memory/external contracts, domains/CDC/RDC/reset, automatic pipeline protocols/policies/evidence, portable Verilog and backend inference, open-source simulation/synthesis/formal flows, mixed-signal boundaries, diagnostics, profiles, libraries, and migration.",
        subject="documentation increment",
    )
    text = replace_regex_once(
        text,
        r"- \[ \] \*\*Increment 87 — Performance and scalability benchmarks\*\*\n  - .*",
        "- [ ] **Increment 87 — Performance and scalability benchmarks**\n"
        "  - Benchmark construction, MLIR size, symbolic staging/type/dimension/effect analysis, pipeline graph construction and scheduling, parameter envelopes, sideband/elastic control, domain provenance and CDC/RDC, portable Verilog emission, Verilator/Icarus simulation, Yosys synthesis/equivalence, SBY proofs, pass time, memory, hierarchy, caching, and regression launch overhead.",
        subject="benchmark increment",
    )
    text = replace_regex_once(
        text,
        r"- \[ \] \*\*Increment 88 — Public API v1 review and compatibility policy\*\*\n  - .*",
        "- [ ] **Increment 88 — Public API v1 review and compatibility policy**\n"
        "  - Review v0.1/v0.2/v0.3 implementation experience, value staging, numeric safety, interfaces/protocols, quantities/effects, implicit domains, resets/crossings, automatic pipeline contracts, backend inference/portable Verilog, schedule stability, and low-level escape; approve only justified changes and define semantic versioning, deprecation, and source compatibility.",
        subject="API review increment",
    )
    text = replace_regex_once(
        text,
        r"- \[ \] \*\*Increment 89 — Nodal core preview release\*\*\n  - .*",
        "- [ ] **Increment 89 — Nodal core preview release**\n"
        "  - Publish the supported preview with frozen API revision, toolchain pins, portable Verilog/Verilog-A/Verilog-AMS capability matrices, open-source lint/simulation/synthesis/formal evidence, installation, examples, known limitations, library-author contract, and reproducible provenance.",
        subject="preview release increment",
    )

    references_anchor = "- CIRCT ESI channel buffers: <https://circt.llvm.org/docs/Dialects/ESI/>"
    references_replacement = references_anchor + "\n" + "\n".join(
        (
            "- Chisel width inference: <https://www.chisel-lang.org/docs/explanations/width-inference>",
            "- Chisel connectable API: <https://www.chisel-lang.org/docs/explanations/connectable>",
            "- SpinalHDL streams: <https://spinalhdl.github.io/SpinalDoc-RTD/master/SpinalHDL/Libraries/stream.html>",
            "- Verilator guide: <https://verilator.org/guide/latest/>",
            "- Icarus Verilog flags: <https://steveicarus.github.io/iverilog/usage/command_line_flags.html>",
            "- Yosys Verilog frontend: <https://yosyshq.readthedocs.io/projects/yosys/en/stable/cmd/index_frontends.html>",
            "- SBY formal verification: <https://yosyshq.readthedocs.io/projects/sby/en/stable/>",
            "- cocotb simulator support: <https://docs.cocotb.org/en/stable/simulator_support.html>",
        )
    )
    text = replace_once(
        text,
        references_anchor,
        references_replacement,
        subject="roadmap references",
    )

    numbers = [
        int(value)
        for value in re.findall(r"^- \[[ x]\] \*\*Increment (\d+) —", text, re.MULTILINE)
    ]
    if numbers != list(range(91)):
        raise SystemExit(f"roadmap increments are not continuous 0-90: {numbers}")

    completed = [
        int(value)
        for value in re.findall(r"^- \[x\] \*\*Increment (\d+) —", text, re.MULTILINE)
    ]
    if completed != list(range(12)):
        raise SystemExit(f"completed increments changed unexpectedly: {completed}")

    text = text.replace("No official reusable model/component library is implemented by Increments 0-90.", "No official reusable model/component library is implemented by Increments 0-90.")
    ROADMAP.write_text(text, encoding="utf-8")


def update_markdown_references(path: Path) -> None:
    text = path.read_text(encoding="utf-8")
    text = renumber_increment_lines(text)
    path.write_text(text, encoding="utf-8")


def update_linked_documents() -> None:
    adr7 = ROOT / "docs/architecture/0007-implicit-clock-reset-domains.md"
    update_markdown_references(adr7)

    clock_plan = ROOT / "docs/roadmap/clock-reset-api-v0.2-plan.md"
    update_markdown_references(clock_plan)

    adr8 = ROOT / "docs/architecture/0008-automatic-pipeline-architecture.md"
    text = adr8.read_text(encoding="utf-8")
    text = renumber_increment_lines(text)
    text = replace_once(
        text,
        "- **Status:** Proposed",
        "- **Status:** Accepted",
        subject="ADR 0008 status",
    )
    semantic_note = (
        "\nThe pipeline architecture depends on [ADR 0009](0009-core-semantic-contracts.md): "
        "the scheduler consumes explicit value stages, lossless numeric semantics, "
        "directionless protocol payloads, exact connections, physical dimensions, and "
        "declared memory/external effects. Increment 15 freezes both public surfaces in one "
        "v0.3 gate.\n"
    )
    context_anchor = (
        "Nodal starts from a target-neutral MLIR layer and can expose a small "
        "transaction-oriented API while preserving a rich graph for scheduling, "
        "verification, reports, and deterministic Verilog-AMS generation."
    )
    text = replace_once(
        text,
        context_anchor,
        context_anchor + semantic_note,
        subject="ADR 0008 semantic dependency",
    )
    text = text.replace(
        "- deterministic schedule, latency, capacity, and critical-path reports;\n- normalized IR and golden Verilog-AMS;",
        "- deterministic schedule, latency, capacity, and critical-path reports;\n- normalized IR plus golden Verilog-AMS and portable Verilog for digital-only fixtures;",
    )
    adr8.write_text(text, encoding="utf-8")

    pipeline_plan = ROOT / "docs/roadmap/automatic-pipeline-api-v0.3-plan.md"
    text = pipeline_plan.read_text(encoding="utf-8")
    text = renumber_increment_lines(text)
    text = replace_once(
        text,
        "**Status:** Candidate for Increment 14 comparison and Increment 15 freeze  ",
        "**Status:** Candidate for Increment 14 comparison and unified Increment 15 freeze  ",
        subject="pipeline plan status",
    )
    machine_anchor = (
        "**Machine-readable candidate:** "
        "[`automatic-pipeline-api-v0.3-surface.json`](automatic-pipeline-api-v0.3-surface.json)"
    )
    machine_replacement = machine_anchor + (
        "  \n**Semantic foundation:** [ADR 0009](../architecture/0009-core-semantic-contracts.md), "
        "[`core-semantics-api-v0.3-plan.md`](core-semantics-api-v0.3-plan.md), and "
        "[`core-semantics-api-v0.3-surface.json`](core-semantics-api-v0.3-surface.json)  \n"
        "**Unified gate:** `NodalCoreSemanticsPipelineApi-DG-v0.3.md`"
    )
    text = replace_once(
        text,
        machine_anchor,
        machine_replacement,
        subject="pipeline plan semantic header",
    )
    goal_anchor = (
        "The API is for deterministic hardware pipelining, not general high-level synthesis."
    )
    goal_replacement = goal_anchor + (
        " It inherits Increment 13's staged-value, lossless-numeric, directionless-aggregate, "
        "exact-connection, physical-quantity, memory, and effect contracts; the pipeline API "
        "is not frozen independently of those semantics."
    )
    text = replace_once(
        text,
        goal_anchor,
        goal_replacement,
        subject="pipeline plan goal dependency",
    )
    text = text.replace(
        "- normalized IR and golden Verilog-AMS;",
        "- normalized IR, golden Verilog-AMS, and golden portable Verilog for digital-only fixtures;",
    )
    text = text.replace(
        "Increment 15 is complete only when:",
        "The unified Increment 15 gate is complete only when:",
    )
    pipeline_plan.write_text(text, encoding="utf-8")

    clock_surface_path = ROOT / "docs/roadmap/clock-reset-api-v0.2-surface.json"
    clock_surface = json.loads(clock_surface_path.read_text(encoding="utf-8"))
    clock_surface["roadmap_revision"] = "1.4"
    clock_surface_path.write_text(
        json.dumps(clock_surface, indent=2, sort_keys=False) + "\n",
        encoding="utf-8",
    )


def validate_json() -> None:
    for path in sorted((ROOT / "docs/roadmap").glob("*.json")):
        json.loads(path.read_text(encoding="utf-8"))


def main() -> None:
    update_roadmap()
    update_linked_documents()
    validate_json()
    print("core semantics, digital backend, and roadmap update applied")


if __name__ == "__main__":
    main()
