from __future__ import annotations

import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parent


def read(relative: str) -> str:
    return (ROOT / relative).read_text(encoding="utf-8")


def write(relative: str, content: str) -> None:
    (ROOT / relative).write_text(content, encoding="utf-8")


def replace_once(text: str, old: str, new: str, *, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected one occurrence, found {count}")
    return text.replace(old, new, 1)


def replace_section(
    text: str,
    start_pattern: str,
    end_pattern: str,
    replacement: str,
    *,
    label: str,
) -> str:
    pattern = re.compile(f"{start_pattern}.*?(?={end_pattern})", re.DOTALL)
    updated, count = pattern.subn(replacement.rstrip() + "\n\n", text, count=1)
    if count != 1:
        raise SystemExit(f"{label}: expected one section, replaced {count}")
    return updated


def insert_after_heading(text: str, heading: str, addition: str, *, marker: str) -> str:
    if marker in text:
        return text
    anchor = heading + "\n"
    if text.count(anchor) != 1:
        raise SystemExit(f"heading not unique: {heading}")
    return text.replace(anchor, anchor + addition.rstrip() + "\n", 1)


roadmap_path = "docs/roadmap/nodal-development-todo.md"
roadmap = read(roadmap_path)
roadmap = replace_once(roadmap, "**Revision:** 1.14", "**Revision:** 1.15", label="roadmap revision")
roadmap = replace_once(roadmap, "**Updated:** 2026-08-22", "**Updated:** 2026-08-23", label="roadmap date")

fixed_anchor = (
    "- Distinguish fixed-rate, valid-only, and elastic ready/valid pipelines in the type system. "
    "Insert and balance only pipeline-owned registers and protocol buffers inside an approved pipeline region.\n"
)
fixed_addition = """- Distinguish directionless storable `Struct` values from non-storable connectivity `Interface`s; never hide boundary direction or connectivity roles inside reusable value fields.
- Apply named `Role`s at interface boundaries. Provide concise `master`/`slave` and `monitor` behavior for `Valid`/`Stream` while retaining a generic role model for request/response, controller/peripheral, device/environment, and AMS access.
- Support first-class digital `inout` through explicit typed read/drive/high-impedance semantics, resolved-net identity, open-drain/push-pull modes, black-box and hierarchical pass-through, and capability-checked internal tri-state use; never silently rewrite unsupported resolution into a mux.
- Keep digital resolved `inout`, conservative AMS terminals, directional analog signal-flow values, and discrete real nets as distinct semantic categories. Require explicit bridges for every analog/digital or conservative/signal-flow conversion.
- Preserve one logical Interface ABI through IR and emit deterministic flattened Verilog/Verilog-A/Verilog-AMS ports plus an optional future native SystemVerilog interface/modport representation with proven flat/native parity.
"""
if "Distinguish directionless storable `Struct` values" not in roadmap:
    roadmap = replace_once(
        roadmap,
        fixed_anchor,
        fixed_anchor + fixed_addition,
        label="fixed interface direction",
    )

increment14 = """- [ ] **Increment 14 — Automatic pipeline, Interface/Role, and inout candidate prototypes and architecture comparison**
  - Use [ADR 0021](../architecture/0021-unified-struct-interface-role-and-inout-architecture.md), [`interface-role-inout-ams-v0.1-plan.md`](interface-role-inout-ams-v0.1-plan.md), and [`interface-role-inout-ams-v0.1-surface.json`](interface-role-inout-ams-v0.1-surface.json) as mandatory candidates alongside ADR 0008.
  - Compile and compare directionless storable `Struct` values versus non-storable `Interface` connectivity, named roles, legal digital role inversion, monitor views, nested request/response roles, `master`/`slave` `Valid` and `Stream`, exact role-compatible connection, symbolic interface arrays, and external reusable interfaces.
  - Compile first-class digital `inout` candidates with explicit read/drive/enable semantics, push-pull/open-drain modes, high impedance, split internal tri-state carriers, top-level/black-box pins, hierarchy pass-through, pad adapters, and profile-aware internal resolved-net restrictions.
  - Compile conservative-terminal-only and mixed digital/analog interface candidates with explicit connect/sense/contribute/monitor access, directional analog signal-flow values, and no implicit analog/digital or conservative/signal-flow conversion.
  - Compile `pipe`, `delay`, plain/`Valid`/`Stream` protocols, exact/ranged/auto latency, throughput and ready-path policy, automatic sideband transport and reconvergence balancing, `stage`/`sameStage`, schedule inspection, parameter envelopes, and fixed/variable-latency operators against Increment 13 semantics.
  - Compare current Chisel aggregate/connectable/protocol forms, current SpinalHDL Bundle/Interface/IMasterSlave/Stream/Flow/Analog/inout forms, current SystemVerilog interface/modport/net semantics, and CIRCT `pipeline`/ESI. Retain useful semantics without exposing lower-level graph plumbing or backend syntax.
  - Add compile-positive and negative contracts for role completeness, monitor drive, incompatible roles, protocol mismatch, interface storage, invalid inversion, multiple ordinary drivers, illegal open-drain drive, unsupported internal tri-state, discipline mismatch, sense-only contribution, implicit bridge conversion, flattening collision, and parameter-envelope layout conflict.
  - Prove that arithmetic, aggregate, protocol, interface ABI, inout resolution, quantity, memory, effect, clock/reset, CDC/RDC, native parameterized-module, and AMS topology contracts remain unchanged by the candidate scheduler surface.
"""
roadmap = replace_section(
    roadmap,
    r"- \[ \] \*\*Increment 14 —",
    r"- \[ \] \*\*Increment 15 —",
    increment14,
    label="Increment 14",
)

increment15 = """- [ ] **Increment 15 — Unified core semantics, Interface/Role/inout, and automatic pipeline public API v0.3 freeze**
  - Publish `docs/design-gates/NodalCoreSemanticsPipelineApi-DG-v0.3.md`, migration notes, and an updated machine-readable public API manifest using ADRs 0009/0008/0021 and the core, pipeline, and interface candidate plans/surfaces.
  - Freeze value stages; ordinary Scala elaboration loops; symbolic target `generate`; bounded hardware iteration and collection operations; `Bits`/`UInt`/`SInt`; exact signed declaration/literal/parameter/memory/expression/shift/conversion/reinterpretation and Verilog-family lowering rules; lossless numeric/width semantics; explicit lossy conversions; parameterized multidimensional `Vec` shape/index/flatten/reshape and target layout; explicit `Vec` versus `Mem`; physical quantities; memory/external effect contracts; native Scala enums; canonical enum ABI/safe decode/exhaustive selection; flat and reusable hierarchical/parallel/timed/bounded-recursive FSMs; local FSM encoding/illegal-state policies; safe expression inlining, materialization reasons, semantic naming, source-span maps, Fast/Default/Release check profiles and typed waivers; `pipe`/`delay`; latency/throughput/ready policy; stage constraints; parameter-envelope scheduling; and schedule evidence.
  - Freeze directionless storable `Struct` versus non-storable `Interface`, generic named `Role`, `master`/`slave`/`monitor`, nested roles, full `Valid`/`Stream` ownership, exact interface connection/adapters, interface arrays, logical Interface ABI/source mapping, deterministic flattening, and external-library extension rules.
  - Freeze first-class digital `inout` read/drive/high-impedance/resolution semantics, initial push-pull/open-drain modes, split-tristate boundary adapters, black-box/hierarchy pass-through, multiple-driver restrictions, profile-aware internal tri-state capability, and stable diagnostics. Keep digital inout distinct from conservative terminals and directional analog signal-flow values.
  - Freeze conservative boundary terminal versus internal node/branch semantics, analog role access, mixed-signal interfaces, explicit bridge requirements, continuous-time island/domain provenance, and backend capability obligations without exposing SystemVerilog or simulator-specific syntax in the source API.
  - Freeze `Backend.Auto`, `Backend.Verilog`, design-kind reporting, explicit synth/sim/formal digital profiles, portable flattened interface ABI, and future native SystemVerilog interface/modport parity requirements from ADR 0010, ADR 0021, and the digital-backend candidate.
  - Add positive and negative compile contracts for every candidate category, including external-library use, stable diagnostic codes/source locations, v0.1/v0.2 migration behavior, role/inout/AMS-interface misuse, and native-versus-flat layout candidates.
  - Keep elaboration, scheduler, interface IR, resolution/topology analysis, digital/AMS backends, and simulator behavior inert. Mark this increment `[x]` only when the unified gate, manifests, fixtures, diagnostics, and CI satisfy all linked exit criteria.
"""
roadmap = replace_section(
    roadmap,
    r"- \[ \] \*\*Increment 15 —",
    r"## Phase 1 — Compiler vertical slice",
    increment15,
    label="Increment 15",
)

roadmap = insert_after_heading(
    roadmap,
    "- [ ] **Increment 16 — Elaboration, hierarchy, shape, and lexical domain-context kernel**",
    "  - Add deterministic `Struct`/`Interface` kind ownership, interface construction close, exported-role requirements, recursive role expansion, interface storage rejection, resolved-net endpoint registration, conservative-terminal topology ownership, and logical Interface ABI paths without globals or JVM identity.",
    marker="interface construction close",
)
roadmap = insert_after_heading(
    roadmap,
    "- [ ] **Increment 19 — Core MLIR module, port, parameter, and domain model**",
    "  - Add canonical Interface IR definitions/instances/roles/member access, full `Valid`/`Stream` channel identity, logical interface ABI metadata, digital resolved-net/read/driver/drive-mode operations, conservative terminal/node/branch/access operations, and explicit mixed-signal bridge operations while keeping target layouts separate.",
    marker="canonical Interface IR definitions",
)
roadmap = insert_after_heading(
    roadmap,
    "- [ ] **Increment 22 — Cross-layer diagnostic mapping**",
    "  - Include stable interface/role/inout/AMS codes for unstorable interfaces, missing roles/members, incompatible roles, monitor drive, invalid inversion, multiple ordinary drivers, illegal open-drain drive, unsupported resolution, hierarchy-pass-through failure, discipline/access mismatch, implicit bridge conversion, and interface-layout collisions.",
    marker="stable interface/role/inout/AMS codes",
)
roadmap = insert_after_heading(
    roadmap,
    "- [ ] **Increment 54 — Digital signed/unsigned type, literal, native enum ABI, and port layer**",
    "  - Implement directionless storable `Struct`, non-storable digital `Interface`, named `Role`, plain/`Valid`/`Stream` interface members, scalar/vector resolved-net types, typed digital inout endpoints, read/drive/high-impedance semantics, drive modes, and exact port/member ABI identity.",
    marker="typed digital inout endpoints",
)
roadmap = insert_after_heading(
    roadmap,
    "- [ ] **Increment 58 — Domain-aware hierarchy, reusable statecharts, and bounded recursive control**",
    "  - Propagate selected roles, nested interface members, domain provenance, resolved-net identity, black-box/top-level inout pass-through, conservative-terminal topology, symbolic interface arrays, and stable logical-to-physical interface paths through hierarchy.",
    marker="resolved-net identity, black-box/top-level",
)
roadmap = insert_after_heading(
    roadmap,
    "- [ ] **Increment 59 — Pipeline transaction graph, latency provenance, and IR contract**",
    "  - Preserve logical interface roles and ABI while extracting plain/`Valid`/`Stream` transaction graphs; protocol scheduling may insert pipeline-owned storage but cannot change role ownership, inout resolution, AMS topology, or explicit bridge semantics.",
    marker="cannot change role ownership",
)
roadmap = insert_after_heading(
    roadmap,
    "- [ ] **Increment 65 — Digital-only classification, Backend.Auto, and portable Verilog backend**",
    "  - Deterministically flatten nested `Interface`/`Struct`/`Valid`/`Stream` members, emit logical Interface ABI/source-map manifests, and lower supported digital inout to net-typed ports plus explicit width-safe tri-state assignments. Reject analog members and profile-unsupported internal resolved nets without silent mux conversion.",
    marker="logical Interface ABI/source-map manifests",
)
roadmap = insert_after_heading(
    roadmap,
    "- [ ] **Increment 66 — Open-source digital lint, simulation, waveforms, and cocotb interoperability**",
    "  - Add typed interface-role drivers/monitors and digital inout high-Z/readback/contention/open-drain/hierarchy tests, with logical Interface ABI metadata for Scala simulation, cocotb, waveforms, and source correlation.",
    marker="digital inout high-Z/readback",
)
roadmap = insert_after_heading(
    roadmap,
    "- [ ] **Increment 67 — Yosys synthesis/equivalence and core SBY formal-readiness infrastructure**",
    "  - Verify flattened interface connectivity, full `Valid`/`Stream` ownership, top-level/black-box tri-state synthesis where supported, split-tristate boundary equivalence, internal resolved-net capability rejection, driver exclusivity assumptions, and native-versus-flat interface parity hooks.",
    marker="split-tristate boundary equivalence",
)
roadmap = insert_after_heading(
    roadmap,
    "- [ ] **Increment 68 — Discrete real and mixed-signal net types**",
    "  - Separate directional analog signal-flow values and discrete real/wreal-like resolution from conservative terminals and digital four-state inout; record interface role access and portability for each category.",
    marker="directional analog signal-flow values",
)
roadmap = insert_after_heading(
    roadmap,
    "- [ ] **Increment 69 — Analog/digital access and conversion semantics**",
    "  - Expose conversions only through typed interface bridge endpoints carrying physical dimensions, source/destination domains, thresholds, hysteresis, quantization, timing, transition, resolution, and model availability.",
    marker="typed interface bridge endpoints",
)
roadmap = insert_after_heading(
    roadmap,
    "- [ ] **Increment 70 — Connect modules and connect rules**",
    "  - Integrate conservative `Terminal`/`Node`/`Branch` interface members, connect/sense/contribute role access, topology preservation, discipline conversion, and deterministic wrapper/interface ABI mapping.",
    marker="connect/sense/contribute role access",
)
roadmap = insert_after_heading(
    roadmap,
    "- [ ] **Increment 71 — Mixed-domain, CDC/RDC, and scheduling verifier**",
    "  - Verify interface role completeness, monitor access, nested-role connections, resolved-net drivers/contention, inout hierarchy, open-drain legality, conservative-terminal access/topology, and no implicit digital/analog/conservative/signal-flow conversion.",
    marker="interface role completeness",
)
roadmap = insert_after_heading(
    roadmap,
    "- [ ] **Increment 72 — Complete Verilog-AMS backend skeleton**",
    "  - Flatten logical mixed-signal interfaces deterministically, including protocol leaves, resolved digital inout nets, discipline-qualified terminals, signal-flow values, and explicit bridges; emit the same Interface ABI/source-map manifest used by portable Verilog.",
    marker="Flatten logical mixed-signal interfaces",
)
roadmap = insert_after_heading(
    roadmap,
    "- [ ] **Increment 77 — UVM-MS interoperability hooks**",
    "  - Generate role-aware interface/agent metadata, monitor views, flattened/native wrapper maps, resolved-inout access, conservative-terminal access, and logical Interface ABI correlation without making UVM-MS define Nodal semantics.",
    marker="role-aware interface/agent metadata",
)
roadmap = insert_after_heading(
    roadmap,
    "- [ ] **Increment 92 — Complete language, plugin SPI, and API documentation**",
    "  - Document `Struct` versus `Interface`, generic roles, `master`/`slave`/`monitor`, full `Valid`/`Stream`, digital inout/resolved-net/tri-state/open-drain/pad patterns, conservative terminals, signal-flow analog values, mixed-signal bridges, flattened/native backend layouts, and Interface ABI compatibility.",
    marker="digital inout/resolved-net/tri-state",
)
roadmap = insert_after_heading(
    roadmap,
    "- [ ] **Increment 96 — Performance and scalability benchmarks**",
    "  - Benchmark deep/nested interfaces, symbolic interface arrays, role expansion, logical ABI/source-map size, flattening, wrapper generation, resolved-driver graphs, conservative topology graphs, and mixed-signal interface verification.",
    marker="resolved-driver graphs",
)
roadmap = insert_after_heading(
    roadmap,
    "- [ ] **Increment 99 — Future SystemVerilog/SystemVerilog-AMS backend research gate**",
    "  - Evaluate native SystemVerilog `interface`/`modport`, nested interfaces, parameters, monitor roles, resolved `inout`, per-instance flatten overrides, wrapper/compile-order manifests, and exact semantic/ABI parity with portable flattened Verilog and Verilog-AMS representations.",
    marker="native SystemVerilog `interface`/`modport`",
)

increment124_section = """
## Phase 8 — Cross-cutting Interface, Role, digital inout, and AMS connectivity closure

This independently schedulable phase closes the cross-layer architecture accepted by ADR 0021. It does not replace the foundational implementation assigned to Increments 14-22, 54-77, and 99; it integrates and qualifies those pieces as one public connectivity system.

- [x] **Increment 124 — Interface, Role, AMS, and digital inout architecture roadmap contract**
  - Accept [ADR 0021](../architecture/0021-unified-struct-interface-role-and-inout-architecture.md), the staged [`interface-role-inout-ams-v0.1-plan.md`](interface-role-inout-ams-v0.1-plan.md), and the machine-readable [`interface-role-inout-ams-v0.1-surface.json`](interface-role-inout-ams-v0.1-surface.json).
  - Freeze the semantic separation among directionless storable `Struct`, non-storable connectivity `Interface`, named `Role`, digital resolved inout, conservative AMS terminals, directional analog signal-flow values, and explicit mixed-signal bridges.
  - Record `master`/`slave` as convenience roles over a generic role model, `Valid` as the canonical valid-only protocol, monitor read-only access, and explicit non-invertible AMS/shared roles.
  - Record first-class digital inout read/drive/high-impedance/resolution, push-pull/open-drain, black-box/hierarchy/pad use, split internal tri-state carriers, and capability-checked internal resolution with no silent mux rewrite.
  - Record one logical Interface ABI with deterministic portable Verilog/Verilog-A/Verilog-AMS flattening and future native SystemVerilog interface/modport parity. Keep exact public API and implementation assigned to Increment 14/15 and later implementation increments.
  - Evidence: [`0021-unified-struct-interface-role-and-inout-architecture.md`](../architecture/0021-unified-struct-interface-role-and-inout-architecture.md), [`interface-role-inout-ams-v0.1-plan.md`](interface-role-inout-ams-v0.1-plan.md), and [`interface-role-inout-ams-v0.1-surface.json`](interface-role-inout-ams-v0.1-surface.json).

- [ ] **Increment 125 — Canonical Interface IR, role expansion, source maps, and ABI manifest**
  - Implement interface/role definitions, member identity, recursive role expansion, exact connection compatibility, interface storage prohibition, parameterized member paths, source maps, diagnostics, canonical manifests, ABI hashes, and compatibility classification.
  - Integrate with construction close, Nodal MLIR, cross-layer diagnostics, plugin metadata, caches, and deterministic parse/print.

- [ ] **Increment 126 — Digital Struct/Interface/Role and full Valid/Stream implementation**
  - Implement directionless `Struct`, nested digital `Interface`, named roles, legal complementary-role derivation, monitor views, plain/`Valid`/`Stream`, transfer/stall/bubble semantics, exact connection, typed adapters, domain provenance, hierarchy propagation, and external protocol-interface conformance.

- [ ] **Increment 127 — Digital inout, resolved nets, tri-state, open-drain, pads, and black-box hierarchy**
  - Implement typed read/drive endpoints, driver states/enables, `0/1/Z/X` resolution, push-pull/open-drain/open-source modes, readback, pull/pad metadata, hierarchy pass-through, black-box connectivity, and split-tristate boundary adapters.
  - Add profile-aware internal tri-state restrictions, contention diagnostics/properties, Verilator/Icarus tests, Yosys synthesis/equivalence where supported, and negative fixtures.

- [ ] **Increment 128 — Conservative AMS terminals, signal-flow values, and mixed-signal roles**
  - Implement boundary `Terminal`, internal `Node`, `Branch`, discipline/nature/dimension checks, connect/sense/contribute/monitor access, directional analog signal-flow values, mixed interfaces, bridge endpoints, continuous-time island graphs, and no-implicit-conversion verification.

- [ ] **Increment 129 — Flattened Verilog, Verilog-A, and Verilog-AMS interface lowering**
  - Emit deterministic flattened names/ports/terminals for nested interfaces, protocols, shaped payloads, resolved inouts, conservative terminals, signal-flow values, and bridges.
  - Generate wrappers, Interface ABI/source-map manifests, profile diagnostics, and exact golden fixtures.

- [ ] **Increment 130 — Native SystemVerilog interface/modport backend and wrapper parity**
  - After Increment 99 approval, emit native interfaces/modports, nested interfaces, parameters, monitor roles, inout nets, and per-instance flatten overrides.
  - Generate native/flat wrappers and prove logical ABI, simulation, synthesis, compile-order, and source-map parity across supported tool profiles.

- [ ] **Increment 131 — Interface metadata, verification agents, scale, and external qualification**
  - Generate Scala simulation, cocotb, UVM/UVM-MS, waveform, IP-XACT, and documentation metadata from the logical Interface ABI.
  - Add role/inout/AMS checkers, large nested-interface performance, parameter matrices, deterministic output, compatibility diff, and external reusable interface/library qualification.

"""
if "**Increment 124 — Interface, Role, AMS, and digital inout architecture roadmap contract**" not in roadmap:
    roadmap = replace_once(
        roadmap,
        "## Deferred reusable library roadmap\n",
        increment124_section + "## Deferred reusable library roadmap\n",
        label="Increment 124-131 insertion",
    )

reference_anchor = "- SpinalHDL streams: <https://spinalhdl.github.io/SpinalDoc-RTD/master/SpinalHDL/Libraries/stream.html>\n"
reference_addition = """- SpinalHDL SystemVerilog Interface/modport support: <https://spinalhdl.github.io/SpinalDoc-RTD/master/SpinalHDL/Structuring/interfacing_with_sv.html>
- SpinalHDL Analog/inout support: <https://spinalhdl.github.io/SpinalDoc-RTD/master/SpinalHDL/Other%20language%20features/analog_inout.html>
- SpinalHDL TriState guidance: <https://spinalhdl.github.io/SpinalDoc-RTD/master/SpinalHDL/Libraries/IO/tristate.html>
"""
if "SpinalHDL SystemVerilog Interface/modport support" not in roadmap:
    roadmap = replace_once(
        roadmap,
        reference_anchor,
        reference_anchor + reference_addition,
        label="interface references",
    )
write(roadmap_path, roadmap)

architecture_index_path = "docs/architecture/README.md"
architecture_index = read(architecture_index_path)
row_anchor = "| [0020](0020-canonical-register-factory-and-transport-adapters.md) | Define register ABI once in canonical Register IR, separate physical register blocks from transports, support Scala/SystemRDL/YAML/IP-XACT frontends, and emit fixed register ABI symbols as non-overridable Verilog constants. |\n"
row_addition = "| [0021](0021-unified-struct-interface-role-and-inout-architecture.md) | Separate directionless `Struct` values from connectivity `Interface`s, use generic named roles with master/slave convenience, support explicit digital resolved inout, preserve conservative AMS terminals, and retain one logical Interface ABI across flattened and native backends. |\n"
if "[0021](0021-unified-struct-interface-role-and-inout-architecture.md)" not in architecture_index:
    architecture_index = replace_once(
        architecture_index,
        row_anchor,
        row_anchor + row_addition,
        label="ADR 0021 row",
    )
paragraph_anchor = "ADR 0020 specializes the core semantic, clock/reset, quality-gate, and plugin boundaries for control/status registers. Register authoring frontends and transport adapters normalize around one canonical model; neither a bus protocol nor a generated software/interchange view may redefine the register ABI.\n"
paragraph_addition = "\nADR 0021 specializes the core semantic, clock/reset, AMS, backend-profile, and quality-gate boundaries for reusable connectivity. It keeps storable values, protocol interfaces, digital resolved inout, conservative terminals, directional analog signal flow, mixed-signal bridges, and backend physical layouts distinct while preserving one logical Interface ABI.\n"
if "ADR 0021 specializes" not in architecture_index:
    architecture_index = replace_once(
        architecture_index,
        paragraph_anchor,
        paragraph_anchor + paragraph_addition,
        label="ADR 0021 paragraph",
    )
write(architecture_index_path, architecture_index)

core_plan_path = "docs/roadmap/core-semantics-api-v0.3-plan.md"
core_plan = read(core_plan_path)
if "**Interface/role/inout architecture:**" not in core_plan:
    core_plan = replace_once(
        core_plan,
        "**Quality-gate architecture:** [ADR 0019](../architecture/0019-mandatory-pre-emission-hardware-quality-gates.md)\n",
        "**Quality-gate architecture:** [ADR 0019](../architecture/0019-mandatory-pre-emission-hardware-quality-gates.md)\n**Interface/role/inout architecture:** [ADR 0021](../architecture/0021-unified-struct-interface-role-and-inout-architecture.md)\n",
        label="core plan header",
    )

interface_core_section = """## Struct, Interface, Role, protocol, and inout semantics

[ADR 0021](../architecture/0021-unified-struct-interface-role-and-inout-architecture.md), [`interface-role-inout-ams-v0.1-plan.md`](interface-role-inout-ams-v0.1-plan.md), and [`interface-role-inout-ams-v0.1-surface.json`](interface-role-inout-ams-v0.1-surface.json) define mandatory Increment 14 candidates and Increment 15 freeze obligations.

A directionless `Struct` is a storable/copyable hardware value:

```scala
final case class Pixel(width: Int) extends Struct:
  val red   = UInt(width)
  val green = UInt(width)
  val blue  = UInt(width)
```

A connectivity `Interface` is not storable and applies a named role at a module boundary:

```scala
final case class VideoInterface(width: Int) extends Interface:
  val pixels = Stream(Pixel(width))
  val start  = Bool()

  role source:
    master(pixels)
    out(start)

  role sink:
    slave(pixels)
    in(start)

  role monitor:
    observe(pixels, start)
```

The same role system supports `master`/`slave`, `source`/`sink`, `initiator`/`target`, `controller`/`peripheral`, `device`/`environment`, monitor, and stable user-defined roles. Automatic inversion is legal only for fully complementary digital access.

The common transport types remain:

```scala
T
Valid[T]
Stream[T]
```

`Valid[T]` carries payload plus validity without backpressure. `Stream[T]` carries ordered ready/valid transport with backpressure. Master drives `valid`/`payload` and receives `ready`; slave is the exact inverse. `Valid` is the canonical valid-only type.

Digital inout is first class and separates sensing from driving:

```scala
val gpio = inout(Bits(8))
val sampled = gpio.read
gpio.drive(writeData, enable = outputEnable)
```

The v0.3 gate freezes typed read/drive/high-impedance/resolution semantics, push-pull/open-drain modes, top-level/black-box/hierarchy/pad use, split internal tri-state carriers, multiple-driver restrictions, and capability-checked internal resolved nets. Unsupported resolution is rejected rather than silently rewritten to a mux.

Conservative AMS terminals remain distinct from digital inout and directional analog signal-flow values. Mixed-signal interfaces explicitly declare connect/sense/contribute/monitor access and use typed bridges for every analog/digital or conservative/signal-flow conversion.

Every backend preserves one logical Interface ABI. Portable Verilog and Verilog-AMS flatten deterministically; a future approved SystemVerilog backend may emit native interface/modport syntax only with proven semantic and ABI parity.

The v0.3 gate must freeze:

- exact `Struct`, `Interface`, and `Role` public spellings and type relationships;
- deterministic member identity, nested roles, role selection, inversion, and monitor access;
- exact `Valid`/`Stream` ownership and connection behavior;
- interface storage prohibition and exact adapters/views;
- digital inout endpoint, resolution, drive mode, hierarchy, and profile rules;
- conservative terminal versus node/branch and directional signal-flow distinctions;
- mixed-signal role access and explicit bridge requirements;
- logical Interface ABI/source maps and flat/native target layout contracts;
- stable diagnostics and external-library extension boundaries.

"""
core_plan = replace_section(
    core_plan,
    r"## Directionless aggregates",
    r"## Exact connections and adapters",
    interface_core_section,
    label="core plan interface section",
)

if "- storable nested `Struct` payloads;" not in core_plan:
    core_plan = replace_once(
        core_plan,
        "- directionless nested aggregates and vectors;\n",
        "- directionless nested aggregates and vectors;\n- storable nested `Struct` payloads and non-storable nested `Interface`s;\n- named master/slave/source/sink/controller/peripheral/device/environment/monitor roles;\n- full `Valid`/`Stream` role ownership, nested request/response interfaces, and exact role-compatible connections;\n- digital inout read/drive/high-impedance, push-pull/open-drain, split-tristate boundary, black-box, pad, and hierarchy candidates;\n- conservative-terminal-only and mixed-signal interfaces with explicit analog access and bridges;\n- portable flattened and future native SystemVerilog interface/modport layout candidates;\n",
        label="core positive interface matrix",
    )
if "- storing an `Interface`" not in core_plan:
    negative_anchor = "- incompatible aggregate shape or field names;\n"
    negative_addition = """- storing an `Interface`, embedding direction/connectivity in a `Struct`, or exporting an interface without a role;
- incompatible roles, missing members, monitor drive, invalid inversion, or implicit protocol/domain/latency adaptation;
- direct inout assignment without explicit drive state, multiple ordinary drivers, illegal open-drain drive, or unsupported internal resolution;
- implicit digital-inout/conservative-terminal, conservative/signal-flow, or analog/digital conversion;
- discipline/access mismatch, sense-only contribution, flattening collision, or parameter-envelope interface-layout conflict;
"""
    core_plan = replace_once(
        core_plan,
        negative_anchor,
        negative_anchor + negative_addition,
        label="core negative interface matrix",
    )
write(core_plan_path, core_plan)

core_surface_path = "docs/roadmap/core-semantics-api-v0.3-surface.json"
core_surface = json.loads(read(core_surface_path))
core_surface["roadmap_revision"] = "1.15"
documents = core_surface.setdefault("documents", {})
documents["interface_role_inout_adr"] = "docs/architecture/0021-unified-struct-interface-role-and-inout-architecture.md"
documents["interface_role_inout_plan"] = "docs/roadmap/interface-role-inout-ams-v0.1-plan.md"
documents["interface_role_inout_surface"] = "docs/roadmap/interface-role-inout-ams-v0.1-surface.json"
core_surface["aggregates"] = {
    "value_kind": "Struct",
    "value_is_directionless": True,
    "value_is_storable": True,
    "connectivity_kind": "Interface",
    "interface_is_storable": False,
    "role_kind": "Role",
    "generic_named_roles": True,
    "built_in_convenience_roles": [
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
        "monitor"
    ],
    "automatic_inverse_only_for_complementary_digital_access": True,
    "boundary_direction": ["in", "out", "inout"],
    "transport": ["plain", "Valid", "Stream"],
    "canonical_valid_only_type": "Valid",
    "direct_connection": "exact",
    "adapter_candidates": ["via", "viewAs"],
    "logical_interface_abi_required": True,
    "formal_freeze_increment": 15
}
core_surface["digital_inout"] = {
    "first_class": True,
    "separate_read_and_drive": True,
    "minimum_resolution": ["0", "1", "Z", "X-or-contention"],
    "drive_modes": ["push-pull-tristate", "open-drain", "open-source", "pass-through"],
    "multiple_drivers_only_on_resolved_nets": True,
    "split_internal_tristate_preferred_for_portable_synthesis": True,
    "top_level_black_box_pad_hierarchy_use": True,
    "silent_internal_inout_to_mux": False,
    "profile_aware_internal_resolution": True,
    "formal_freeze_increment": 15
}
core_surface["ams_interface"] = {
    "conservative_terminal_distinct_from_digital_inout": True,
    "terminal_node_branch_distinction": True,
    "directional_signal_flow_distinct": True,
    "access": ["connect", "sense", "contribute", "monitor"],
    "mixed_signal_interface": True,
    "explicit_bridge_required": True,
    "future_native_systemverilog_interface_requires_separate_gate": True,
    "formal_freeze_increment": 15
}
required = core_surface.setdefault("required_evidence", [])
for item in (
    "interface-role-inout-ams-candidate-compilation",
    "digital-inout-negative-fixtures",
    "mixed-signal-interface-negative-fixtures",
    "logical-interface-abi-layout-candidates",
):
    if item not in required:
        required.append(item)
write(core_surface_path, json.dumps(core_surface, indent=2) + "\n")
