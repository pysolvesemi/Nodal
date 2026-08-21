#!/usr/bin/env python3
from __future__ import annotations

import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ROADMAP = ROOT / "docs/roadmap/nodal-development-todo.md"
POLICY = ROOT / ".github/branch-policy.json"
CLOCK_PLAN = ROOT / "docs/roadmap/clock-reset-api-v0.2-plan.md"
CLOCK_SURFACE = ROOT / "docs/roadmap/clock-reset-api-v0.2-surface.json"
CLOCK_ADR = ROOT / "docs/architecture/0007-implicit-clock-reset-domains.md"


def one(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected one anchor, found {count}")
    return text.replace(old, new, 1)


def shift(match: re.Match[str]) -> str:
    number = int(match.group(1))
    if number >= 56:
        number += 8
    elif number >= 13:
        number += 2
    return f"Increment {number}"


text = ROADMAP.read_text(encoding="utf-8")
text = one(text, "**Revision:** 1.2", "**Revision:** 1.3", "revision")
text = one(
    text,
    "- Prefer clock enables over user-created clocks. Generated clocks, physical clock gates, clock muxes, and reset trees require explicit primitives carrying relationship, mapping, and timing metadata.\n",
    "- Prefer clock enables over user-created clocks. Generated clocks, physical clock gates, clock muxes, and reset trees require explicit primitives carrying relationship, mapping, and timing metadata.\n"
    "- Treat automatic pipelining as deterministic scheduling of an explicit feed-forward transaction graph, not opaque HLS. Never silently change arithmetic, ordering, protocol, clock/reset domains, resource sharing, side effects, or parameterized module identity.\n"
    "- Distinguish fixed-rate, valid-only, and elastic ready/valid pipelines in the type system. Insert and balance only pipeline-owned registers and protocol buffers inside an approved pipeline region.\n",
    "fixed direction",
)
text = one(
    text,
    "- Do not copy backend event-process syntax into ordinary synchronous source.\n",
    "- Do not copy backend event-process syntax into ordinary synchronous source.\n"
    "- Provide a compact automatic-pipeline surface centered on `pipe`, `delay`, protocol-typed transactions, latency/throughput policies, automatic sideband alignment, and optional hard stage constraints. Do not expose node/link plumbing in ordinary datapath source.\n",
    "public API direction",
)

section = r'''
## Automatic pipeline architecture

The proposed architecture is [ADR 0008](../architecture/0008-automatic-pipeline-architecture.md). The candidate API, staged delivery plan, and freeze criteria are in [`automatic-pipeline-api-v0.3-plan.md`](automatic-pipeline-api-v0.3-plan.md), with a machine-readable candidate in [`automatic-pipeline-api-v0.3-surface.json`](automatic-pipeline-api-v0.3-surface.json).

Nodal adopts:

> **Explicit transaction semantics, automatic stage placement, reviewable schedules.**

Directional source:

```scala
val result = pipe(
  input = Txn(a = a, b = b, c = c, tag = tag),
  target = 500.MHz,
  latency = Latency.Auto,
) { x =>
  Result(data = (x.a + x.b) * x.c, tag = x.tag)
}
```

The compiler automatically balances reconvergent operands and delays `tag` to the result transaction. `value.delay(3)` remains the simple explicit-delay form.

The protocol type defines transport semantics:

- plain transaction: fixed-rate, one transaction each active cycle;
- `Valid[T]`: bubbles without backpressure;
- `Stream[T]`: elastic ready/valid with backpressure.

Rules to freeze in public API v0.3:

- all dynamic inputs enter through one typed transaction and are sampled together;
- automatic scheduling is initially acyclic, feed-forward, single-domain, and initiation-interval one;
- arithmetic, ordering, widths, rounding, exceptions, and resource ownership are preserved exactly;
- sidebands, predicates, tags, and valid state are transported automatically only to their uses;
- fixed/valid published interfaces expose exact or bounded latency;
- elastic interfaces expose minimum latency, capacity, throughput, fall-through, and ready-path behavior;
- payload registers are resetless by default while validity/control state follows the current domain reset contract;
- CDC/RDC, analog sampling, memories, user state, side effects, commit barriers, and hard stage anchors are scheduling barriers;
- frequency-driven scheduling requires an applicable versioned timing model and never claims timing closure from an estimate;
- timing-affecting symbolic parameters require a finite envelope and one envelope-safe schedule so native parameterized HDL remains one module; silent clone-per-value specialization is forbidden;
- schedule reports and hashes make inserted stages, buffers, alignment delays, model inputs, and microarchitecture changes reviewable;
- general HLS, loop pipelining, silent sharing, arithmetic reassociation, and algorithm rewriting are outside the initial contract.

Candidate controls are `pipe`, `delay`, `Latency.Auto`, `Latency.Exact`, `Latency.Range`, `Throughput.EveryCycle`, ready-path policy, `stage(value)` as a hard cut, `sameStage { ... }`, and typed fixed/variable-latency operator contracts. Increment 13 compares exact Scala forms; Increment 14 freezes the accepted surface and diagnostics before scheduler implementation.
'''
text = one(text, "\n## Core and future library boundary", section + "\n\n## Core and future library boundary", "architecture section")

# Renumber all existing unchecked-roadmap references before adding new entries.
text = re.sub(r"\bIncrement (\d+)\b", shift, text)
text = text.replace("Increments 0-78", "Increments 0-86")

text = one(
    text,
    "- **M0 — Foundation:** reproducible builds, CI, domain-aware public API freeze, and enforced core/library boundaries.",
    "- **M0 — Foundation:** reproducible builds, CI, clock/reset and automatic-pipeline API freezes, frozen contracts, and enforced core/library boundaries.",
    "M0",
)
text = one(
    text,
    "- **M3 — AMS preview:** implicit-domain digital state, CDC/RDC-safe clock/reset architecture, mixed-signal crossings, and Verilog-AMS emission.",
    "- **M3 — AMS preview:** implicit-domain digital state, automatic fixed/valid/elastic pipelines, CDC/RDC-safe clock/reset architecture, mixed-signal crossings, and Verilog-AMS emission.",
    "M3",
)

early = r'''
- [ ] **Increment 13 — Automatic pipeline candidate prototypes and architecture comparison**
  - Add compile-only candidates for `pipe`, `delay`, plain/`Valid`/`Stream` protocols, exact/ranged/auto latency, throughput and ready-path policy, automatic sideband transport and reconvergence balancing, `stage`/`sameStage` constraints, schedule inspection, parameter envelopes, and fixed/variable-latency operator declarations.
  - Compare current Chisel `Pipe`/`ShiftRegister`/`Queue`/`Decoupled`, current SpinalHDL `Node`/`Payload`/`Link`/`Builder`, and CIRCT `pipeline`/ESI. Retain their useful semantics without exposing lower-level graph plumbing as Nodal's ordinary API.

- [ ] **Increment 14 — Automatic pipeline public API v0.3 freeze and contract fixtures**
  - Use [ADR 0008](../architecture/0008-automatic-pipeline-architecture.md), [`automatic-pipeline-api-v0.3-plan.md`](automatic-pipeline-api-v0.3-plan.md), and [`automatic-pipeline-api-v0.3-surface.json`](automatic-pipeline-api-v0.3-surface.json) as the mandatory architecture and candidate.
  - Publish `NodalAutomaticPipelineApi-DG-v0.3.md`, a migration note, and a machine-readable frozen public surface. Freeze protocol types, `pipe`/`delay`, latency/throughput/ready-path semantics, input capture, automatic alignment, anchors, reset priority, one-domain restriction, parameter-envelope behavior, published latency, schedule stability, side-effect barriers, and diagnostics before scheduler implementation.
  - Add positive fixtures for fixed-rate, valid-only, elastic, exact/ranged/internal-auto latency, sideband/reconvergence alignment, hard constraints, envelope-safe parameterized HDL, fixed/variable-latency operators, and external-library use.
  - Add negative fixtures for missing domains, hidden CDC/RDC, live external reads, protocol conversion, missing timing models, impossible latency, side effects, ready loops, unbounded timing parameters, clone-per-value requests, and conflicting constraints. Freeze stable codes and source locations.
'''
text = one(text, "\n## Phase 1 — Compiler vertical slice", "\n" + early + "\n## Phase 1 — Compiler vertical slice", "early increments")

hierarchy = "- [ ] **Increment 57 — Domain-aware digital hierarchy and parameterization**\n  - Implement default-domain inheritance, typed named-domain binding, inferred clock/reset ports, symbolic parameters, domain-polymorphic modules, generate behavior, and deterministic variants only for material edge/reset differences."
implementation = hierarchy + r'''

- [ ] **Increment 58 — Pipeline transaction graph, latency provenance, and IR contract**
  - Represent fixed-rate, valid-only, and elastic regions as single-domain feed-forward transaction graphs with protocol tokens, transaction identity, stage/latency variables, sideband demand, reconvergence constraints, exact/ranged latency, hard anchors, reset/control policy, parameter envelopes, and operation delay/latency metadata. Document selective CIRCT reuse.

- [ ] **Increment 59 — Fixed-rate and valid-only automatic scheduling**
  - Schedule acyclic II=1 datapaths under exact/ranged/auto latency and target-period constraints; insert pipeline-owned registers, balance operands and sidebands, propagate `Valid` bubbles, preserve finite-width semantics, and emit deterministic schedules, reports, normalized IR, and golden Verilog-AMS.

- [ ] **Increment 60 — Elastic automatic pipeline and backpressure synthesis**
  - Lower `Stream[T]` regions to full-throughput ready/valid stages with elastic registers, skid buffers, registered-ready cuts, bubble/stall propagation, capacity accounting, ready-loop checks, stall-stability assertions, and proofs of no loss, duplication, or reordering.

- [ ] **Increment 61 — Timing/resource models and target-driven partitioning**
  - Add versioned generic, FPGA, ASIC, simulator, and user operation models covering width/sign-dependent delay, fixed multi-cycle latency, implementation choices, resource preferences, uncertainty, and finite parameter envelopes. Implement target scheduling with infeasibility diagnostics and optional synthesis-feedback import without claiming timing closure from estimates.

- [ ] **Increment 62 — Pipeline controls, anchors, memories, and multi-cycle units**
  - Freeze and implement typed flush/cancel/replay and commit barriers, reset/stall/enable priority, named hard cuts, same-stage groups, synchronous memory latency/ordering, fixed-latency blocks, and elastic wrappers for variable-latency units. Reject or isolate side effects that cannot move safely.

- [ ] **Increment 63 — Hierarchical composition, schedule stability, and bounded retiming**
  - Compose regions/modules through explicit latency/protocol contracts, generate stable stage names and schedule hashes, diagnose latency drift, export reports/debug mappings, and retime only pipeline-owned registers inside declared boundaries—not across user state, CDC/RDC, analog boundaries, memories, side effects, parameter-envelope barriers, or observability anchors.
'''
text = one(text, hierarchy, implementation, "implementation increments")

text = one(
    text,
    "- [ ] **Increment 68 — Complete Verilog-AMS backend skeleton**\n  - Emit explicit inferred clock/reset ports, event processes lowered from high-level state, synchronizers/FIFOs, reset logic, gates/muxes, analog/digital declarations, disciplines, connect constructs, hierarchy, parameters, source maps, and metadata.",
    "- [ ] **Increment 68 — Complete Verilog-AMS backend skeleton**\n  - Emit explicit inferred clock/reset ports, event processes lowered from high-level state and automatic schedules, fixed/valid/elastic pipeline registers and control, synchronizers/FIFOs, reset logic, gates/muxes, analog/digital declarations, disciplines, connect constructs, hierarchy, parameters, latency/schedule metadata, and source maps.",
    "backend",
)
text = one(
    text,
    "- [ ] **Increment 69 — ADC and DAC mixed-signal vertical slices**\n  - Compile/check or simulate ADC/DAC models using implicit domains, `Reg` state, explicit sampling/drive, legal CDC, reset policies, parameters, hierarchy, reports, and deterministic parameterized Verilog-AMS.",
    "- [ ] **Increment 69 — ADC and DAC mixed-signal vertical slices**\n  - Compile/check or simulate ADC/DAC models using implicit domains, automatically scheduled fixed and elastic digital datapaths, explicit sampling/drive, legal CDC, reset policies, parameter-envelope-safe scheduling, hierarchy, pipeline/CDC/RDC reports, and deterministic parameterized Verilog-AMS.",
    "vertical slice",
)
text = one(
    text,
    "- [ ] **Increment 79 — Complete language reference and API documentation**\n  - Cover syntax, semantics, domains, CDC/RDC, reset, analog/mixed-signal boundaries, diagnostics, profiles, reports/constraints, simulators, libraries, and migration.",
    "- [ ] **Increment 79 — Complete language reference and API documentation**\n  - Cover syntax, semantics, domains, CDC/RDC, reset, automatic pipeline protocols/policies, latency/throughput and parameter-envelope contracts, stage controls, schedule reports/diagnostics, analog/mixed-signal boundaries, profiles, constraints, simulators, libraries, and migration.",
    "language reference",
)
text = one(
    text,
    "- [ ] **Increment 83 — Performance and scalability benchmarks**\n  - Benchmark construction, MLIR size, domain-provenance propagation, CDC/RDC analysis, report generation, pass time, memory, HDL, multi-domain hierarchy, caching, and simulation launch.",
    "- [ ] **Increment 83 — Performance and scalability benchmarks**\n  - Benchmark construction, MLIR size, pipeline graph construction, scheduling/model lookup, parameter-envelope analysis, sideband and elastic-control generation, schedule reporting, domain provenance, CDC/RDC analysis, pass time, memory, HDL, multi-domain/pipeline hierarchy, caching, and simulation launch.",
    "benchmarks",
)
text = one(
    text,
    "- [ ] **Increment 84 — Public API v1 review and compatibility policy**\n  - Review v0.1/v0.2 implementation experience, implicit domains, resets, crossings, and low-level escape; approve only justified changes and define semantic versioning/deprecation/source compatibility.",
    "- [ ] **Increment 84 — Public API v1 review and compatibility policy**\n  - Review v0.1/v0.2/v0.3 implementation experience, implicit domains, resets, crossings, automatic pipeline protocol/latency/parameter semantics, schedule stability, stage controls, and low-level escape; approve only justified changes and define semantic versioning, deprecation, and source compatibility.",
    "API review",
)

references = "- SpinalHDL clock-crossing diagnostics: <https://spinalhdl.github.io/SpinalDoc-RTD/master/SpinalHDL/Design%20errors/clock_crossing_violation.html>\n- Verilog-AMS standards: <https://accellera.org/downloads/standards/v-ams>"
references_new = "- SpinalHDL clock-crossing diagnostics: <https://spinalhdl.github.io/SpinalDoc-RTD/master/SpinalHDL/Design%20errors/clock_crossing_violation.html>\n- Chisel `Pipe`, `ShiftRegister`, `Queue`, and ready/valid API: <https://www.chisel-lang.org/api/latest/chisel3/util/>\n- SpinalHDL pipeline library: <https://spinalhdl.github.io/SpinalDoc-RTD/master/SpinalHDL/Libraries/Pipeline/index.html>\n- CIRCT pipeline dialect: <https://circt.llvm.org/docs/Dialects/Pipeline/>\n- CIRCT ESI channel buffers: <https://circt.llvm.org/docs/Dialects/ESI/>\n- Verilog-AMS standards: <https://accellera.org/downloads/standards/v-ams>"
text = one(text, references, references_new, "references")

numbers = [int(value) for value in re.findall(r"^- \[[ x]\] \*\*Increment (\d+) —", text, re.MULTILINE)]
if numbers != list(range(87)):
    raise SystemExit(f"roadmap numbering mismatch: {numbers}")
checked = [int(value) for value in re.findall(r"^- \[x\] \*\*Increment (\d+) —", text, re.MULTILINE)]
if checked != list(range(12)):
    raise SystemExit(f"completed set changed: {checked}")
ROADMAP.write_text(text, encoding="utf-8")

policy = json.loads(POLICY.read_text(encoding="utf-8"))
policy["milestone_promotions"] = {
    "M0": "after Increment 14",
    "M1": "after Increment 25",
    "M2": "after Increment 52",
    "M3": "after Increment 74",
    "M4": "after Increment 85"
}
POLICY.write_text(json.dumps(policy, indent=2) + "\n", encoding="utf-8")

clock_plan = CLOCK_PLAN.read_text(encoding="utf-8")
clock_replacements = {
    "### Increment 13 — Elaboration and lexical domain context": "### Increment 15 — Elaboration and lexical domain context",
    "### Increment 14 — Source locations and deterministic domain naming": "### Increment 16 — Source locations and deterministic domain naming",
    "### Increment 16 — Target-neutral domain IR": "### Increment 18 — Target-neutral domain IR",
    "### Increment 19 — Cross-layer diagnostics": "### Increment 21 — Cross-layer diagnostics",
    "### Increment 53 — Register and next-state semantics": "### Increment 55 — Register and next-state semantics",
    "### Increment 54 — Domain, CDC/RDC, gate, mux, and escape implementation": "### Increment 56 — Domain, CDC/RDC, gate, mux, and escape implementation",
    "### Increment 55 — Domain-aware hierarchy": "### Increment 57 — Domain-aware hierarchy",
    "### Increments 57, 59, and 60 — Mixed-signal verification and backend": "### Increments 65, 67, and 68 — Mixed-signal verification and backend",
    "### Increment 61 — ADC/DAC proof": "### Increment 69 — ADC/DAC proof"
}
for old, new in clock_replacements.items():
    clock_plan = one(clock_plan, old, new, f"clock plan {old}")
CLOCK_PLAN.write_text(clock_plan, encoding="utf-8")

clock_adr = CLOCK_ADR.read_text(encoding="utf-8")
clock_adr = one(clock_adr, "- Increment 13 implements elaboration, hierarchy, and lexical domain context.", "- Increment 15 implements elaboration, hierarchy, and lexical domain context.", "clock ADR 13")
clock_adr = one(clock_adr, "- Increment 16 adds target-neutral domain and crossing constructs to Nodal MLIR.", "- Increment 18 adds target-neutral domain and crossing constructs to Nodal MLIR.", "clock ADR 16")
clock_adr = one(clock_adr, "- Increments 53-55 implement state, CDC/RDC, and domain-aware hierarchy.", "- Increments 55-57 implement state, CDC/RDC, and domain-aware hierarchy.", "clock ADR 53")
clock_adr = one(clock_adr, "- Increments 57, 59, and 60 implement mixed-signal transfer, verification, and Verilog-AMS lowering.", "- Increments 65, 67, and 68 implement mixed-signal transfer, verification, and Verilog-AMS lowering.", "clock ADR 57")
clock_adr = one(clock_adr, "- Increment 61 proves the architecture with ADC and DAC vertical slices.", "- Increment 69 proves the architecture with ADC and DAC vertical slices.", "clock ADR 61")
CLOCK_ADR.write_text(clock_adr, encoding="utf-8")

surface = json.loads(CLOCK_SURFACE.read_text(encoding="utf-8"))
surface["roadmap_revision"] = "1.3"
CLOCK_SURFACE.write_text(json.dumps(surface, indent=2) + "\n", encoding="utf-8")

print("automatic pipeline roadmap v2 applied")
