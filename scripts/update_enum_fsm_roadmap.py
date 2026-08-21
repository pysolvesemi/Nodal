#!/usr/bin/env python3
"""Apply the native-enum and reusable-FSM roadmap revision."""

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


def insert_after(text: str, anchor: str, addition: str, subject: str) -> str:
    return replace_once(text, anchor, anchor + addition, subject)


def insert_before(text: str, anchor: str, addition: str, subject: str) -> str:
    return replace_once(text, anchor, addition + anchor, subject)


def update_roadmap() -> None:
    text = ROADMAP.read_text(encoding="utf-8")
    text = replace_once(text, "**Revision:** 1.8", "**Revision:** 1.9", "roadmap revision")

    fixed_anchor = (
        "- Use lossless finite-width arithmetic by default. Narrowing, wrap, truncation, "
        "saturation, checked resize, and signedness conversion require explicit intent."
    )
    fixed_addition = "\n" + "\n".join(
        (
            "- Use native Scala 3 enums as the preferred semantic declaration and derive typed hardware enum metadata; Scala ordinal never defines the HDL ABI.",
            "- Separate canonical enum interface encoding from local FSM storage encoding. Preserve one stable numeric mapping across portable Verilog localparams, Verilog-A/Verilog-AMS constants, and future SystemVerilog native enums.",
            "- Model control as typed FSM/statechart graphs with explicit reset, transition priority, illegal-state, hierarchy, parallel, timing, completion, recursion-bound, source-map, and proof contracts rather than mutable backend-style process objects.",
        )
    )
    text = insert_after(text, fixed_anchor, fixed_addition, "fixed enum/FSM direction")

    public_anchor = (
        "- Freeze value staging, lossless numeric/width rules, directionless aggregates, exact "
        "connections, physical quantities, memory/external effects, and automatic pipelines in "
        "one coherent public API v0.3 gate."
    )
    public_addition = "\n" + "\n".join(
        (
            "- Freeze native Scala enum derivation, canonical encoding/ABI, safe decode, exhaustive selection, local FSM encoding, flat FSM actions/transitions, reusable definitions, hierarchical/parallel/timed composition, and bounded recursion in the same v0.3 gate.",
            "- Emit enum members as non-overridable `localparam`s in portable Verilog and Verilog-AMS profiles; a future SystemVerilog profile emits native typed enums with the same explicit values and compile-order metadata.",
        )
    )
    text = insert_after(text, public_anchor, public_addition, "public enum/FSM direction")

    enum_section = """## Enum and reusable FSM architecture

The binding architecture is [ADR 0015](../architecture/0015-native-scala-enum-and-hierarchical-fsm.md). The exact enum, encoding, statechart, hierarchy, recursion, backend, report, and freeze candidates are in [`enum-fsm-api-v0.3-plan.md`](enum-fsm-api-v0.3-plan.md), with a machine-readable candidate in [`enum-fsm-api-v0.3-surface.json`](enum-fsm-api-v0.3-surface.json).

Nodal adopts:

> **Names define meaning, explicit encodings define ABI, typed statecharts define control, and every lowering preserves reviewable state identity.**

Preferred source direction:

```scala
enum ControlState derives HwEnum:
  case Idle, Load, Run, Error

val controller = fsm(
  initial = ControlState.Idle,
  encoding = FsmEncoding.Compact
):
  state(ControlState.Idle):
    on(start).goto(ControlState.Load)

  state(ControlState.Load):
    entry:
      count := 0.U
    active:
      count := count + 1.U
    exclusive:
      on(fault).goto(ControlState.Error)
      on(done).goto(ControlState.Run)

  state(ControlState.Error):
    terminal()
```

Exact spellings are compile candidates until Increment 15. Binding semantics are:

- native Scala enum case identity is semantic; Scala `ordinal` is never the HDL encoding contract;
- a canonical enum encoding defines ports, parameters, aggregates, memories, protocols, and library ABI;
- sparse/custom values are explicit and safe decode returns typed value plus validity;
- local FSM storage may use compact, one-hot, Gray, custom, or explicit locked Auto encoding without changing public enum values;
- portable Verilog and Verilog-AMS use vector/integer storage plus member `localparam`s; future SystemVerilog uses `typedef enum logic` with identical values;
- enum module configuration values remain overrideable parameters, while enum member meanings remain non-overridable localparams;
- flat manual enum-register FSMs and high-level statecharts lower into the same target-neutral IR;
- no hidden boot state is introduced; reset entry behavior and illegal-state recovery are explicit;
- transitions are mutually exclusive by default and ordered priority is opt-in;
- reusable definitions are immutable, typed, separately compilable, and free of accidental dynamic capture;
- nested submachines, parallel regions, typed completion, timed/protocol waits, finite structural recursion, and explicit bounded call/return stacks are analyzable graph constructs;
- unbounded structural or runtime recursion is rejected;
- graph verification covers coverage, reachability, dead ends, overlap, drivers, completion, join deadlock, recursion, encoding, domains, effects, and backend capability;
- hierarchy flattening, state minimization, recoding, and retiming are explicit verified optimization passes rather than frontend side effects;
- enum/FSM reports preserve state/case names, encoding maps, transitions, source locations, waveforms, coverage IDs, and formal counterexample reconstruction.


"""
    text = insert_before(
        text,
        "## Automatic pipeline architecture",
        enum_section,
        "enum/FSM architecture section",
    )

    m3_old = (
        "- **M3 — Digital/AMS preview:** implicit-domain digital state, automatic fixed/valid/elastic "
        "pipelines, portable Verilog with open-source simulation/synthesis/equivalence and "
        "compiler-generated formal verification, CDC/RDC-safe clock/reset architecture, "
        "mixed-signal crossings, and Verilog-AMS emission."
    )
    m3_new = (
        "- **M3 — Digital/AMS preview:** implicit-domain digital state, native typed enums, "
        "reusable hierarchical/parallel FSMs, automatic fixed/valid/elastic pipelines, portable "
        "Verilog with open-source simulation/synthesis/equivalence and compiler-generated formal "
        "verification, CDC/RDC-safe clock/reset architecture, mixed-signal crossings, and "
        "Verilog-AMS emission."
    )
    text = replace_once(text, m3_old, m3_new, "M3 enum/FSM scope")

    inc13_anchor = (
        "  - Include an external-library consumer using only public candidate APIs; keep "
        "frontend/backend behavior inert."
    )
    inc13_extra = "\n" + "\n".join(
        (
            "  - Compile native Scala 3 enum derivation, stable/custom canonical encodings, typed ports/parameters/aggregates/protocols, safe decode, exhaustive switch, portable-Verilog localparam mapping contracts, and future SystemVerilog native-enum contracts.",
            "  - Compile manual enum-register FSMs plus concise flat, reusable, nested, parallel, timed, finite-recursive, and explicit bounded-call-stack statechart candidates from [ADR 0015](../architecture/0015-native-scala-enum-and-hierarchical-fsm.md).",
        )
    )
    text = insert_after(text, inc13_anchor, inc13_extra, "Increment 13 enum/FSM candidates")

    inc15_anchor = (
        "  - Freeze value stages and target generate; lossless numeric/width/signedness rules; "
        "explicit lossy conversions; directionless aggregates; exact connections/adapters; "
        "plain/`Valid`/`Stream`; physical quantities; memory/external effect contracts; "
        "`pipe`/`delay`; latency/throughput/ready policy; stage constraints; parameter-envelope "
        "scheduling; and schedule evidence."
    )
    inc15_new = (
        "  - Freeze value stages and target generate; lossless numeric/width/signedness rules; "
        "explicit lossy conversions; directionless aggregates; exact connections/adapters; "
        "plain/`Valid`/`Stream`; physical quantities; memory/external effect contracts; native "
        "Scala enums; canonical enum ABI/safe decode/exhaustive selection; flat and reusable "
        "hierarchical/parallel/timed/bounded-recursive FSMs; local FSM encoding/illegal-state "
        "policies; `pipe`/`delay`; latency/throughput/ready policy; stage constraints; "
        "parameter-envelope scheduling; and schedule evidence."
    )
    text = replace_once(text, inc15_anchor, inc15_new, "Increment 15 enum/FSM freeze")

    replacements = {
        "  - Add target-neutral modules, ports, symbols, instances, symbolic parameters, domain requirements/bindings, clock/reset relationships, state ownership, timing provenance, and crossing operations/types. Reuse CIRCT only after semantic comparison.":
        "  - Add target-neutral modules, ports, symbols, instances, symbolic parameters, semantic enum types/cases/canonical encodings, FSM definitions/regions/states/transitions/actions/completion/encoding policies, domain requirements/bindings, clock/reset relationships, state ownership, timing provenance, and crossing operations/types. Reuse CIRCT only after semantic comparison.",
        "  - Map parser, verifier, pass, backend, external-tool, domain-binding, CDC, RDC, gate/mux, and waiver diagnostics back to Scala locations and stable codes.":
        "  - Map parser, verifier, pass, backend, external-tool, enum encoding/decode/exhaustiveness, FSM graph/transition/recursion/illegal-state, domain-binding, CDC, RDC, gate/mux, and waiver diagnostics back to Scala locations and stable codes.",
        "- [ ] **Increment 54 — Digital type and port layer**\n  - Add bit/logic, signed/unsigned vectors, integers, reals, nets/variables, directions, four-state policy, and compatible CIRCT lowering.":
        "- [ ] **Increment 54 — Digital type, native enum ABI, and port layer**\n  - Add bit/logic, signed/unsigned vectors, integers, reals, nets/variables, directions, four-state policy, native Scala enum derivation, semantic enum types/cases, canonical sequential/one-hot/Gray/custom encodings, safe decode, exhaustive selection, enum aggregates/protocols/parameters/memories, ABI hashes, and compatible CIRCT/Nodal lowering.",
        "- [ ] **Increment 56 — Implicit-domain synchronous state and register semantics**\n  - Implement `Reg`, `RegNext`, reset/uninitialized state, `when` priority, enables, state machines, memory-port ownership, and CIRCT sequential lowering without exposing normal `always` syntax.":
        "- [ ] **Increment 56 — Implicit-domain registers, enum state, and flat FSM semantics**\n  - Implement `Reg`, `RegNext`, reset/uninitialized state, enum registers, exhaustive switches, `when` priority, enables, manual FSMs, concise high-level flat FSMs, entry/active/exit/transition actions, exclusive/priority transitions, terminal/completion states, local compact/one-hot/Gray/custom/Auto encoding, illegal-state policies, graph diagnostics, memory-port ownership, and CIRCT/Nodal sequential lowering without exposing normal `always` syntax or hidden boot state.",
        "- [ ] **Increment 58 — Domain-aware digital hierarchy and parameterization**\n  - Implement default-domain inheritance, typed named-domain binding, inferred clock/reset ports, symbolic parameters, domain-polymorphic modules, generate behavior, and deterministic variants only for material edge/reset differences.":
        "- [ ] **Increment 58 — Domain-aware hierarchy, reusable statecharts, and bounded recursive control**\n  - Implement default-domain inheritance, typed named-domain binding, inferred clock/reset ports, symbolic parameters, domain-polymorphic modules, generate behavior, and deterministic variants only for material edge/reset differences.\n  - Implement immutable reusable `FsmDef`/fragment candidates, explicit runtime bindings, nested submachines, typed completion/cancellation, parallel join policies, timed/protocol-aware states, finite elaboration recursion, and explicit bounded call/return stack contracts with overflow/underflow, reset, domain, report, and proof metadata. Reject unbounded recursion and accidental dynamic capture.",
        "  - Emit the portable synthesizable Verilog profile with symbolic parameters/generate, hierarchy, flattened aggregates/protocols, clocks/resets, memories, CDC/RDC, automatic pipelines, black boxes, assertions/formal hooks, source maps, deterministic formatting, and exact golden fixtures.":
        "  - Emit the portable synthesizable Verilog profile with symbolic parameters/generate, hierarchy, flattened aggregates/protocols, canonical enum vectors and member `localparam`s, enum configuration parameters, flat/hierarchical/parallel FSM state and completion logic, clocks/resets, memories, CDC/RDC, automatic pipelines, black boxes, assertions/formal hooks, enum/FSM manifests, source maps, deterministic formatting, and exact golden fixtures.",
        "  - Preserve stable property IDs, source maps, domain/reset/parameter metadata, normalized tasks, and adapter evidence in forms compatible with [ADR 0014](../architecture/0014-target-neutral-formal-verification.md). Use portable hooks or sidecar harnesses without freezing a user-authored formal API or binding Nodal semantics to SVA/SBY syntax.":
        "  - Preserve stable property IDs, source maps, domain/reset/parameter metadata, normalized tasks, and adapter evidence in forms compatible with [ADR 0014](../architecture/0014-target-neutral-formal-verification.md). Generate core enum/FSM legality, one-hot, allowed-transition, reset-convergence, deadlock, completion, and bounded-stack checks. Use portable hooks or sidecar harnesses without freezing a user-authored formal API or binding Nodal semantics to SVA/SBY syntax.",
        "  - Emit explicit inferred clock/reset ports, event processes lowered from high-level state and automatic schedules, fixed/valid/elastic pipeline registers and control, synchronizers/FIFOs, reset logic, gates/muxes, analog/digital declarations, disciplines, connect constructs, hierarchy, parameters, latency/schedule metadata, and source maps.":
        "  - Emit explicit inferred clock/reset ports; canonical enum localparams/vectors; flat, nested, parallel, timed, and bounded-procedure FSM state/action/completion logic; event processes lowered from high-level state and automatic schedules; fixed/valid/elastic pipeline registers and control; synchronizers/FIFOs; reset logic; gates/muxes; analog/digital declarations; disciplines; connect constructs; hierarchy; parameters; enum/FSM/latency/schedule metadata; and source maps.",
        "  - Compile/check or simulate ADC/DAC models using implicit domains, automatically scheduled fixed and elastic digital datapaths, explicit sampling/drive, legal CDC, reset policies, parameter-envelope-safe scheduling, hierarchy, pipeline/CDC/RDC reports, and deterministic parameterized Verilog-AMS.":
        "  - Compile/check or simulate ADC/DAC models using implicit domains, typed mode/state enums, reusable hierarchical FSM control, automatically scheduled fixed and elastic digital datapaths, explicit sampling/drive, legal CDC, reset policies, parameter-envelope-safe scheduling, hierarchy, enum/FSM/pipeline/CDC/RDC reports, and deterministic parameterized Verilog-AMS.",
        "- [ ] **Increment 99 — Future SystemVerilog-AMS backend research gate**\n  - Reassess the current standard, map IR/plugin/backend coverage, identify required changes, and approve or reject implementation through a separate gate without speculating syntax into the stable API.":
        "- [ ] **Increment 99 — Future SystemVerilog/SystemVerilog-AMS backend research gate**\n  - Reassess the current standards and tool support; map IR/plugin/backend coverage; evaluate native `typedef enum logic` emission, design-level enum packages/compile-order manifests, enum-typed ports/parameters/aggregates/memories, statechart lowering, and compatibility with portable-Verilog numeric mappings; identify required changes; and approve or reject implementation through a separate gate without speculating syntax into the stable API.",
        "  - Publish passive property libraries for protocols, FIFOs, pipelines, resets, CDC/RDC wrappers, memories, and common control structures using only public APIs.":
        "  - Publish passive property libraries for enums, legal-state/transition FSMs, reusable statecharts, protocols, FIFOs, pipelines, resets, CDC/RDC wrappers, memories, and common control structures using only public APIs.",
    }
    for old, new in replacements.items():
        text = replace_once(text, old, new, f"roadmap replacement: {old[:45]}")

    ref_anchor = "- Chisel reset semantics: <https://www.chisel-lang.org/docs/explanations/reset>"
    ref_extra = "\n" + "\n".join(
        (
            "- Chisel enums: <https://www.chisel-lang.org/docs/explanations/chisel-enum>",
            "- Chisel FSM cookbook: <https://www.chisel-lang.org/docs/cookbooks/cookbook#how-do-i-create-a-finite-state-machine-fsm>",
            "- SpinalHDL enums: <https://spinalhdl.github.io/SpinalDoc-RTD/master/SpinalHDL/Data%20types/enum.html>",
            "- SpinalHDL FSM library: <https://spinalhdl.github.io/SpinalDoc-RTD/master/SpinalHDL/Libraries/fsm.html>",
        )
    )
    text = insert_after(text, ref_anchor, ref_extra, "enum/FSM references")

    ROADMAP.write_text(text, encoding="utf-8")


def update_architecture_index() -> None:
    path = ROOT / "docs/architecture/README.md"
    text = path.read_text(encoding="utf-8")
    lines = text.splitlines()
    index = next(
        (i for i, line in enumerate(lines) if line.startswith("| [0014](")),
        None,
    )
    if index is None:
        raise SystemExit("architecture index lacks ADR 0014 row")
    row = (
        "| [0015](0015-native-scala-enum-and-hierarchical-fsm.md) | Use native Scala 3 "
        "enums with stable canonical HDL encoding and typed reusable hierarchical/parallel FSM "
        "graphs with explicit reset, priority, recursion bounds, reports, and proof contracts. |"
    )
    if any(line.startswith("| [0015](") for line in lines):
        raise SystemExit("architecture index already contains ADR 0015")
    lines.insert(index + 1, row)
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def update_core_semantics_plan() -> None:
    path = ROOT / "docs/roadmap/core-semantics-api-v0.3-plan.md"
    text = path.read_text(encoding="utf-8")
    text = insert_after(
        text,
        "**Architecture:** [ADR 0009](../architecture/0009-core-semantic-contracts.md)",
        "\n**Enum/FSM architecture:** [ADR 0015](../architecture/0015-native-scala-enum-and-hierarchical-fsm.md)",
        "core plan architecture metadata",
    )
    text = replace_once(
        text,
        "Freeze the language semantics that clock/reset domains, symbolic parameterization, reusable interfaces, analog equations, memories, external blocks, and automatic pipelines depend on.",
        "Freeze the language semantics that clock/reset domains, symbolic parameterization, native enums, reusable FSM/statecharts, reusable interfaces, analog equations, memories, external blocks, and automatic pipelines depend on.",
        "core plan goal",
    )

    enum_section = """## Native enums and FSM/statechart semantics

[ADR 0015](../architecture/0015-native-scala-enum-and-hierarchical-fsm.md), [`enum-fsm-api-v0.3-plan.md`](enum-fsm-api-v0.3-plan.md), and [`enum-fsm-api-v0.3-surface.json`](enum-fsm-api-v0.3-surface.json) define the mandatory Increment 13 candidates.

Preferred enum direction:

```scala
enum Mode derives HwEnum:
  case Idle, Read, Write, Error

val mode = in(Mode.hw)
val state = Reg(Mode.Idle)
```

The v0.3 gate freezes semantic case identity, canonical ABI encoding, sparse/custom maps, safe decode, exhaustive selection, ports/parameters/aggregates/protocols/memories, and target mappings. Scala ordinal is never the HDL code.

Portable Verilog and Verilog-AMS emit vector/integer storage plus member `localparam`s. Future SystemVerilog emits native typed enums with identical explicit values. Enum configuration parameters remain overrideable module parameters; enum member meanings remain non-overridable.

Preferred FSM direction:

```scala
val controller = fsm(initial = ControlState.Idle):
  state(ControlState.Idle):
    on(start).goto(ControlState.Run)
  state(ControlState.Run):
    exclusive:
      on(done).goto(ControlState.Idle)
      on(fault).goto(ControlState.Error)
```

The gate freezes flat/manual and high-level FSM semantics, entry/active/exit/transition actions, reset/no-hidden-boot behavior, exclusive versus priority transitions, illegal-state policy, local storage encoding independent of enum ABI, typed status, reusable immutable definitions, nested/parallel/timed machines, finite structural recursion, explicit bounded runtime call stacks, graph diagnostics, reports, source maps, and formal readiness.


"""
    text = insert_before(text, "## Directionless aggregates", enum_section, "core plan enum/FSM section")

    text = insert_after(
        text,
        "- parameterized schedules require finite envelopes.",
        "\n- enum codes and FSM state/action/transition/completion boundaries remain exact typed scheduling barriers unless a separately verified transformation contract permits movement or recoding.",
        "pipeline enum/FSM relationship",
    )

    positive_anchor = "- full-width unsigned and signed arithmetic;"
    positive_extra = "\n" + "\n".join(
        (
            "- native Scala enums with default and sparse/custom encodings;",
            "- enum ports/parameters/aggregates/vectors/memories/protocols, safe decode, and exhaustive selection;",
            "- manual and high-level flat FSMs plus reusable nested/parallel/timed/finite-recursive definitions and bounded-stack candidates;",
        )
    )
    text = insert_after(text, positive_anchor, positive_extra, "core plan positive enum/FSM matrix")

    negative_anchor = "- implicit narrowing or signedness change;"
    negative_extra = "\n" + "\n".join(
        (
            "- Scala ordinal used as enum ABI or invalid/duplicate enum code;",
            "- implicit bits-to-enum/cross-enum conversion or ignored sparse-decode validity;",
            "- non-exhaustive switch, overlapping transitions, missing initial state, hidden boot assumption, invalid local encoding, or unbounded FSM recursion;",
        )
    )
    text = insert_after(text, negative_anchor, negative_extra, "core plan negative enum/FSM matrix")

    exit_anchor = "9. the machine-readable manifest, migration notes, diagnostics, and CI are green."
    exit_replacement = "\n".join(
        (
            "9. native enum ABI, safe decode, exhaustive selection, portable localparam mapping, and future SystemVerilog numeric parity are proven;",
            "10. flat and reusable hierarchical/parallel/timed/bounded-recursive FSM candidates have unambiguous reset, transition, encoding, report, and diagnostic contracts;",
            "11. the machine-readable manifest, migration notes, diagnostics, and CI are green.",
        )
    )
    text = replace_once(text, exit_anchor, exit_replacement, "core plan exit criteria")

    ref_anchor = "- CIRCT ESI channels: <https://circt.llvm.org/docs/Dialects/ESI/>"
    ref_extra = "\n" + "\n".join(
        (
            "- Chisel enums: <https://www.chisel-lang.org/docs/explanations/chisel-enum>",
            "- Chisel FSM cookbook: <https://www.chisel-lang.org/docs/cookbooks/cookbook#how-do-i-create-a-finite-state-machine-fsm>",
            "- SpinalHDL enums: <https://spinalhdl.github.io/SpinalDoc-RTD/master/SpinalHDL/Data%20types/enum.html>",
            "- SpinalHDL FSM library: <https://spinalhdl.github.io/SpinalDoc-RTD/master/SpinalHDL/Libraries/fsm.html>",
        )
    )
    text = insert_after(text, ref_anchor, ref_extra, "core plan references")
    path.write_text(text, encoding="utf-8")


def update_digital_architecture() -> None:
    path = ROOT / "docs/architecture/0010-digital-verilog-open-source-verification.md"
    text = path.read_text(encoding="utf-8")
    section = """## Enum and FSM representation

[ADR 0015](0015-native-scala-enum-and-hierarchical-fsm.md) defines semantic enums and typed reusable statecharts. Portable Verilog emits enum members as module-local `localparam`s, stores values in vectors/integers, and retains enum/FSM manifests and source maps. Local FSM recoding does not change canonical enum values at module boundaries.

The digital verification matrix checks legal enum encodings, safe decode, exhaustive selection, localparam/value parity, state reachability, allowed transitions, one-hot/Gray/custom encoding contracts, illegal-state behavior, hierarchy/parallel completion, bounded-stack safety, reset convergence, and equivalence across verified recoding or flattening passes.


"""
    text = insert_before(text, "## Open-source verification stack", section, "digital ADR enum/FSM section")
    path.write_text(text, encoding="utf-8")


def update_digital_plan() -> None:
    path = ROOT / "docs/roadmap/digital-verilog-open-source-verification-plan.md"
    text = path.read_text(encoding="utf-8")
    text = insert_after(
        text,
        "- digital scalar/vector/aggregate ports and signals;",
        "\n- native semantic enums, canonical enum encodings, safe decode, and manual/high-level FSM/statechart constructs;",
        "digital plan classification enum/FSM",
    )
    text = insert_after(
        text,
        "- modules, ports, parameters, local parameters, and named overrides;",
        "\n- enum member localparams, vector/integer enum storage, enum configuration parameters, and flat/hierarchical/parallel FSM state/action/completion logic;",
        "digital plan portable enum/FSM",
    )
    section = """## Enum and FSM lowering

The portable profile follows [ADR 0015](../architecture/0015-native-scala-enum-and-hierarchical-fsm.md):

- enum members are non-overridable module-local `localparam`s;
- enum ports/signals/memories use canonical vectors or integers;
- enum-valued module configuration remains an overrideable parameter with legal-value metadata;
- local FSM compact/one-hot/Gray/custom/Auto encoding is implementation metadata and cannot change public enum ABI;
- nested/parallel/timed/bounded-procedure FSMs lower to explicit state, completion, counter, join, and stack logic;
- state/case names, transitions, encodings, source maps, and coverage IDs remain in sidecar manifests;
- future SystemVerilog native enum emission is a separate capability gate but must preserve the same numeric values.


"""
    text = insert_before(text, "## Capability profiles", section, "digital plan enum/FSM section")
    text = insert_after(
        text,
        "- transaction IDs and latency-aware pipeline checking;",
        "\n- typed enum signal access, legal-value checking, semantic state/transition traces, nested/parallel machine status, and state/transition coverage;",
        "digital simulation enum/FSM",
    )
    text = insert_after(
        text,
        "- fixed-latency automatic pipelines against latency-aligned reference behavior.",
        "\n- enum/FSM behavior before and after approved recoding, hierarchy flattening, minimization, or synthesis, aligned by semantic state/transition identity rather than raw encoded bits.",
        "digital equivalence enum/FSM",
    )
    text = insert_after(
        text,
        "- no output before a valid input transaction.",
        "\n- legal enum/state encoding, one-hot invariants, allowed FSM transition relations, reset convergence, no unintended deadlock, nested/parallel completion, and bounded call-stack overflow/underflow safety.",
        "digital formal enum/FSM",
    )
    text = insert_after(
        text,
        "- aggregate/protocol flattening;",
        "\n- canonical enum/localparam lowering and manual/flat/hierarchical/parallel/timed/bounded-procedure FSM lowering;",
        "digital backend delivery enum/FSM",
    )
    path.write_text(text, encoding="utf-8")


def update_json_surfaces() -> None:
    roadmap_dir = ROOT / "docs/roadmap"
    for path in sorted(roadmap_dir.glob("*.json")):
        data = json.loads(path.read_text(encoding="utf-8"))
        if "roadmap_revision" in data:
            data["roadmap_revision"] = "1.9"
        path.write_text(json.dumps(data, indent=2) + "\n", encoding="utf-8")

    core_path = roadmap_dir / "core-semantics-api-v0.3-surface.json"
    core = json.loads(core_path.read_text(encoding="utf-8"))
    core.setdefault("documents", {})["enum_fsm_plan"] = "docs/roadmap/enum-fsm-api-v0.3-plan.md"
    core["enum_fsm"] = {
        "architecture": "docs/architecture/0015-native-scala-enum-and-hierarchical-fsm.md",
        "preferred_enum": "enum E derives HwEnum",
        "preferred_type_use": "E.hw",
        "canonical_encoding_separate_from_fsm_storage_encoding": True,
        "portable_verilog_members": "localparam",
        "future_systemverilog_members": "typedef enum logic with identical explicit values",
        "safe_decode_required": True,
        "exhaustive_switch_required": True,
        "flat_and_reusable_statecharts": True,
        "nested_parallel_timed_and_bounded_recursive": True,
        "unbounded_recursion": False,
        "formal_freeze_increment": 15,
    }
    core["required_evidence"].insert(1, "enum-fsm-candidate-compilation")
    core_path.write_text(json.dumps(core, indent=2) + "\n", encoding="utf-8")

    digital_path = roadmap_dir / "digital-backend-v0.3-surface.json"
    digital = json.loads(digital_path.read_text(encoding="utf-8"))
    digital["enum_fsm"] = {
        "architecture": "docs/architecture/0015-native-scala-enum-and-hierarchical-fsm.md",
        "portable_verilog_enum": "localparam plus vector/integer storage",
        "enum_configuration": "module parameter with canonical numeric value",
        "future_systemverilog_enum": "native typedef enum logic",
        "local_fsm_recoding_preserves_public_enum_abi": True,
        "hierarchical_parallel_timed_bounded_procedure_lowering": True,
        "required_proofs": [
            "legal encoding",
            "safe decode",
            "allowed transition relation",
            "reset convergence",
            "deadlock and completion",
            "bounded stack safety",
        ],
    }
    digital_path.write_text(json.dumps(digital, indent=2) + "\n", encoding="utf-8")

    formal_path = roadmap_dir / "formal-verification-v0.1-surface.json"
    formal = json.loads(formal_path.read_text(encoding="utf-8"))
    formal["enum_fsm_readiness"] = {
        "semantic_state_identity_preserved": True,
        "compiler_generated_properties_in_increment_67": [
            "legal enum and state encoding",
            "one-hot invariant",
            "allowed transitions",
            "reset convergence",
            "no unintended deadlock",
            "nested and parallel completion",
            "bounded stack overflow and underflow",
        ],
        "user_authored_properties_deferred_to_formal_gate": True,
    }
    formal_path.write_text(json.dumps(formal, indent=2) + "\n", encoding="utf-8")


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

    required = (
        "**Revision:** 1.9",
        "## Enum and reusable FSM architecture",
        "Increment 54 — Digital type, native enum ABI, and port layer",
        "Increment 56 — Implicit-domain registers, enum state, and flat FSM semantics",
        "Increment 58 — Domain-aware hierarchy, reusable statecharts, and bounded recursive control",
        "Increment 99 — Future SystemVerilog/SystemVerilog-AMS backend research gate",
        "No official reusable model/component library or production plugin is implemented by Increments 0-113.",
    )
    for fragment in required:
        if fragment not in text:
            raise SystemExit(f"roadmap lacks required fragment: {fragment}")

    for relative in (
        "docs/architecture/0015-native-scala-enum-and-hierarchical-fsm.md",
        "docs/roadmap/enum-fsm-api-v0.3-plan.md",
        "docs/roadmap/enum-fsm-api-v0.3-surface.json",
    ):
        if not (ROOT / relative).is_file():
            raise SystemExit(f"missing durable enum/FSM document: {relative}")

    print("enum/FSM roadmap update validated")


def main() -> None:
    update_roadmap()
    update_architecture_index()
    update_core_semantics_plan()
    update_digital_architecture()
    update_digital_plan()
    update_json_surfaces()
    validate()


if __name__ == "__main__":
    main()
