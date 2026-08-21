#!/usr/bin/env python3
"""Apply the signed-type and staged-loop roadmap revision."""

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
    text = replace_once(text, "**Revision:** 1.9", "**Revision:** 1.10", "roadmap revision")

    fixed_anchor = (
        "- Use lossless finite-width arithmetic by default. Narrowing, wrap, truncation, "
        "saturation, checked resize, and signedness conversion require explicit intent."
    )
    fixed_extra = "\n" + "\n".join(
        (
            "- Preserve `Bits` as signless, `UInt` as unsigned, and `SInt` as two's-complement signed through ports, parameters, memories, expressions, optimization, and every Verilog-family backend; never let backend expression rules define Nodal signedness.",
            "- Distinguish ordinary Scala elaboration loops, symbolic structural `generate` loops, and bounded hardware-iteration loops. Dynamic or unbounded iteration must not acquire hidden latency or an inferred FSM.",
        )
    )
    text = insert_after(text, fixed_anchor, fixed_extra, "fixed signed/loop direction")

    public_anchor = (
        "- Provide explicit lossy numeric conversions such as truncate, wrap, saturate, and "
        "checked resize; never narrow or reinterpret signedness silently."
    )
    public_extra = "\n" + "\n".join(
        (
            "- Freeze exact `SInt` declaration/literal/parameter/memory/expression rules, numeric conversion versus bit reinterpretation, mixed-sign diagnostics, arithmetic/logical shifts, and portable Verilog/future SystemVerilog signed lowering in public API v0.3.",
            "- Keep ordinary Scala `for` for elaboration, reserve `generate(...)` for symbolic structural replication, and freeze a separate concise bounded hardware-loop operation plus collection `map`/`reduce` candidates. Reject runtime trip counts and unbounded `while` in the initial synthesizable contract.",
        )
    )
    text = insert_after(text, public_anchor, public_extra, "public signed/loop direction")

    section = """## Signed numeric and staged-loop architecture

The binding architecture is [ADR 0016](../architecture/0016-signed-types-and-staged-loops.md). The exact signed type, conversion, literal, backend, loop-category, lowering, verification, and freeze candidates are in [`signed-loop-api-v0.3-plan.md`](signed-loop-api-v0.3-plan.md), with a machine-readable candidate in [`signed-loop-api-v0.3-surface.json`](signed-loop-api-v0.3-surface.json).

Nodal adopts:

> **Signedness is a type contract; loop kind is a staging contract. Neither is inferred from backend syntax.**

The initial numeric distinction is `Bits(width)` for signless bit containers, `UInt(width)` for unsigned integers, and `SInt(width)` for two's-complement signed integers. Signedness survives parameters, ports, wires, registers, aggregates, memories, expressions, source maps, optimizations, and backend lowering. Mixed signed/unsigned arithmetic and comparisons require explicit conversion or a separately frozen lossless promotion; bit reinterpretation is distinct from numeric conversion.

Portable Verilog emits signed vectors, signed parameters/localparams, explicit sized negative literals, correct arithmetic shifts, and only the casts required by explicit Nodal semantics. Future SystemVerilog emits equivalent `logic signed` declarations and preserves signed packed fields, arrays, memories, functions, parameters, enums, and loop variables without replacing arbitrary-width `SInt` with `int`.

Loops have three categories:

1. ordinary Scala `for`/`foreach` executes during elaboration and accepts Scala values only;
2. `generate(...)` preserves structural repetition and symbolic parameter bounds into target HDL `genvar`/generate loops;
3. a distinct bounded hardware-loop candidate such as `loop(...)` describes repeated operations inside one combinational or clocked region and may lower deterministically to a procedural HDL `for` or verified unrolled operations.

A bounded hardware loop has a finite static/symbolic-static trip count. It cannot create modules or ports, use a runtime signal as its trip count, hide multiple cycles, or contain unbounded/data-dependent termination. Multi-cycle iteration uses explicit FSM/statechart, pipeline, stream, memory, or iterative-operation contracts.


"""
    text = insert_before(
        text,
        "## Enum and reusable FSM architecture",
        section,
        "signed/loop architecture section",
    )

    old_m3 = (
        "- **M3 — Digital/AMS preview:** implicit-domain digital state, native typed enums, "
        "reusable hierarchical/parallel FSMs, automatic fixed/valid/elastic pipelines, portable "
        "Verilog with open-source simulation/synthesis/equivalence and compiler-generated formal "
        "verification, CDC/RDC-safe clock/reset architecture, mixed-signal crossings, and "
        "Verilog-AMS emission."
    )
    new_m3 = (
        "- **M3 — Digital/AMS preview:** implicit-domain digital state, exact signed finite-width "
        "types, elaboration/generate/bounded hardware loops, native typed enums, reusable "
        "hierarchical/parallel FSMs, automatic fixed/valid/elastic pipelines, portable Verilog "
        "with open-source simulation/synthesis/equivalence and compiler-generated formal "
        "verification, CDC/RDC-safe clock/reset architecture, mixed-signal crossings, and "
        "Verilog-AMS emission."
    )
    text = replace_once(text, old_m3, new_m3, "M3 signed/loop scope")

    inc13_anchor = (
        "  - Compile lossless unsigned/signed arithmetic, symbolic width rules, explicit "
        "extend/truncate/wrap/saturate/checked-resize/signedness conversions, and negative "
        "implicit-narrowing fixtures."
    )
    inc13_new = (
        "  - Compile lossless unsigned/signed arithmetic, `Bits`/`UInt`/`SInt` declarations, "
        "signed/negative literals, signed parameters/localparams/memories/aggregates, symbolic "
        "width rules, numeric conversion versus reinterpretation, arithmetic/logical shifts, "
        "explicit extend/truncate/wrap/saturate/checked-resize/signedness conversions, and "
        "negative mixed-sign/implicit-narrowing fixtures."
    )
    text = replace_once(text, inc13_anchor, inc13_new, "Increment 13 signed candidates")

    inc13_generate = (
        "  - Compile and compare elaboration-only Scala values, symbolic `Param`/constant/width/"
        "range/generate values, and dynamic hardware values. Freeze candidate target "
        "`generate(...)` behavior and stage-mixing diagnostics."
    )
    inc13_generate_new = (
        "  - Compile and compare elaboration-only Scala values, symbolic `Param`/constant/width/"
        "range/generate values, and dynamic hardware values. Compile ordinary Scala elaboration "
        "loops, symbolic structural `generate(...)`, a distinct bounded hardware-loop candidate, "
        "and vector `map`/`reduce` forms; freeze stage/bound/body legality and dynamic/unbounded-loop diagnostics."
    )
    text = replace_once(text, inc13_generate, inc13_generate_new, "Increment 13 loop candidates")

    inc15_anchor = (
        "  - Freeze value stages and target generate; lossless numeric/width/signedness rules; "
        "explicit lossy conversions; directionless aggregates; exact connections/adapters; "
        "plain/`Valid`/`Stream`; physical quantities; memory/external effect contracts; native "
        "Scala enums; canonical enum ABI/safe decode/exhaustive selection; flat and reusable "
        "hierarchical/parallel/timed/bounded-recursive FSMs; local FSM encoding/illegal-state "
        "policies; `pipe`/`delay`; latency/throughput/ready policy; stage constraints; "
        "parameter-envelope scheduling; and schedule evidence."
    )
    inc15_new = (
        "  - Freeze value stages; ordinary Scala elaboration loops; symbolic target `generate`; "
        "bounded hardware iteration and collection operations; `Bits`/`UInt`/`SInt`; exact signed "
        "declaration/literal/parameter/memory/expression/shift/conversion/reinterpretation and "
        "Verilog-family lowering rules; lossless numeric/width semantics; explicit lossy "
        "conversions; directionless aggregates; exact connections/adapters; plain/`Valid`/`Stream`; "
        "physical quantities; memory/external effect contracts; native Scala enums; canonical enum "
        "ABI/safe decode/exhaustive selection; flat and reusable hierarchical/parallel/timed/"
        "bounded-recursive FSMs; local FSM encoding/illegal-state policies; `pipe`/`delay`; latency/"
        "throughput/ready policy; stage constraints; parameter-envelope scheduling; and schedule evidence."
    )
    text = replace_once(text, inc15_anchor, inc15_new, "Increment 15 signed/loop freeze")

    replacements = {
        "  - Add target-neutral modules, ports, symbols, instances, symbolic parameters, semantic enum types/cases/canonical encodings, FSM definitions/regions/states/transitions/actions/completion/encoding policies, domain requirements/bindings, clock/reset relationships, state ownership, timing provenance, and crossing operations/types. Reuse CIRCT only after semantic comparison.":
        "  - Add target-neutral modules, ports, symbols, instances, symbolic parameters, signless/unsigned/signed finite-width types and constants, structural generate regions, bounded hardware-iteration regions with typed induction variables/effects, semantic enum types/cases/canonical encodings, FSM definitions/regions/states/transitions/actions/completion/encoding policies, domain requirements/bindings, clock/reset relationships, state ownership, timing provenance, and crossing operations/types. Reuse CIRCT only after semantic comparison.",
        "  - Map parser, verifier, pass, backend, external-tool, enum encoding/decode/exhaustiveness, FSM graph/transition/recursion/illegal-state, domain-binding, CDC, RDC, gate/mux, and waiver diagnostics back to Scala locations and stable codes.":
        "  - Map parser, verifier, pass, backend, external-tool, signed literal/conversion/mixed-sign/width/shift errors, loop stage/bound/body/dependency/effect/profile errors, enum encoding/decode/exhaustiveness, FSM graph/transition/recursion/illegal-state, domain-binding, CDC, RDC, gate/mux, and waiver diagnostics back to Scala locations and stable codes.",
        "- [ ] **Increment 54 — Digital type, native enum ABI, and port layer**\n  - Add bit/logic, signed/unsigned vectors, integers, reals, nets/variables, directions, four-state policy, native Scala enum derivation, semantic enum types/cases, canonical sequential/one-hot/Gray/custom encodings, safe decode, exhaustive selection, enum aggregates/protocols/parameters/memories, ABI hashes, and compatible CIRCT/Nodal lowering.":
        "- [ ] **Increment 54 — Digital signed/unsigned type, literal, native enum ABI, and port layer**\n  - Add bit/logic, signless `Bits`, unsigned `UInt`, two's-complement `SInt`, exact signed/negative literals, signed parameters/localparams, signed aggregate fields and memory elements, numeric conversion versus bit reinterpretation, integers, reals, nets/variables, directions, four-state policy, native Scala enum derivation, semantic enum types/cases, canonical sequential/one-hot/Gray/custom encodings, safe decode, exhaustive selection, enum aggregates/protocols/parameters/memories, ABI hashes, and compatible CIRCT/Nodal lowering.",
        "- [ ] **Increment 55 — Digital combinational expressions and continuous assignments**\n  - Add arithmetic, logic, bitwise, comparisons, concatenation, extraction, conditionals, width/sign rules, and continuous assignment.":
        "- [ ] **Increment 55 — Digital expressions, bounded hardware iteration, and continuous assignments**\n  - Add arithmetic, logic, bitwise, comparisons, concatenation, extraction, conditionals, exact width/sign and mixed-sign rules, arithmetic/logical shifts, explicit signed casts/conversions, continuous assignment, typed hardware `map`/`zip`/`reduce`/`fold`/`scan`, and bounded hardware iteration with finite static/symbolic bounds, ordered effects, dependency/index/driver checks, and deterministic unrolled versus procedural-loop lowering candidates. Reject runtime trip counts, structural declarations, hidden multi-cycle behavior, and unbounded/data-dependent loops.",
        "  - Implement default-domain inheritance, typed named-domain binding, inferred clock/reset ports, symbolic parameters, domain-polymorphic modules, generate behavior, and deterministic variants only for material edge/reset differences.":
        "  - Implement default-domain inheritance, typed named-domain binding, inferred clock/reset ports, symbolic parameters, domain-polymorphic modules, structural `generate` regions with symbolic bounds/nested legal generation, deterministic index-aware hierarchy naming, and deterministic variants only for material edge/reset differences. Keep ordinary Scala loops elaboration-only and preserve native target generate instead of clone-per-value specialization.",
        "  - Emit the portable synthesizable Verilog profile with symbolic parameters/generate, hierarchy, flattened aggregates/protocols, canonical enum vectors and member `localparam`s, enum configuration parameters, flat/hierarchical/parallel FSM state and completion logic, clocks/resets, memories, CDC/RDC, automatic pipelines, black boxes, assertions/formal hooks, enum/FSM manifests, source maps, deterministic formatting, and exact golden fixtures.":
        "  - Emit the portable synthesizable Verilog profile with exact signed vector ports/wires/registers/parameters/localparams/memories/aggregate fields, explicitly sized signed literals, typed shifts/casts, structural `genvar` generate loops, bounded procedural `for` loops or verified unrolled equivalents, symbolic parameters/generate, hierarchy, flattened aggregates/protocols, canonical enum vectors and member `localparam`s, enum configuration parameters, flat/hierarchical/parallel FSM state and completion logic, clocks/resets, memories, CDC/RDC, automatic pipelines, black boxes, assertions/formal hooks, signed/loop/enum/FSM manifests, source maps, deterministic formatting, and exact golden fixtures.",
        "  - Extend the Scala simulation API with typed digital/aggregate/protocol access, clock/reset-domain stimulus, multiple clocks, randomized reset release, `Valid`/`Stream` drivers/monitors/scoreboards, stalls/bubbles, latency-aware checking, timeouts, caching, and artifacts.":
        "  - Extend the Scala simulation API with typed signed/unsigned/bit-container and aggregate/protocol access, clock/reset-domain stimulus, multiple clocks, randomized reset release, `Valid`/`Stream` drivers/monitors/scoreboards, signed boundary/shift/comparison checks, procedural-versus-unrolled loop differential fixtures, stalls/bubbles, latency-aware checking, timeouts, caching, and artifacts.",
        "  - Add RTL-to-optimized/netlist equivalence, including latency-aware fixed-pipeline and protocol-aware elastic checks.":
        "  - Add RTL-to-optimized/netlist equivalence, including signed width/extension/cast/shift checks, generate/procedural/unrolled-loop equivalence and index-bound properties, latency-aware fixed-pipeline, and protocol-aware elastic checks.",
        "  - Emit explicit inferred clock/reset ports; canonical enum localparams/vectors; flat, nested, parallel, timed, and bounded-procedure FSM state/action/completion logic; event processes lowered from high-level state and automatic schedules; fixed/valid/elastic pipeline registers and control; synchronizers/FIFOs; reset logic; gates/muxes; analog/digital declarations; disciplines; connect constructs; hierarchy; parameters; enum/FSM/latency/schedule metadata; and source maps.":
        "  - Emit explicit inferred clock/reset ports; signed digital vectors/parameters/literals/casts; structural generate and bounded procedural/unrolled loops; canonical enum localparams/vectors; flat, nested, parallel, timed, and bounded-procedure FSM state/action/completion logic; event processes lowered from high-level state and automatic schedules; fixed/valid/elastic pipeline registers and control; synchronizers/FIFOs; reset logic; gates/muxes; analog/digital declarations; disciplines; connect constructs; hierarchy; parameters; signed/loop/enum/FSM/latency/schedule metadata; and source maps.",
        "  - Preserve widths/signedness/overflow, symbolic parameters/generate, one-module-per-structure, hierarchy, clocks/resets/CDC/RDC, protocol ordering, latency/throughput/capacity, user-owned state, memories/effects, source maps, and portable-Verilog capabilities unless an explicit separately named transformation contract permits a verified change.":
        "  - Preserve widths/signedness/overflow, numeric-conversion versus reinterpretation, signed literal/shift/comparison semantics, elaboration/generate/hardware-loop category, iteration/reduction order, index bounds, deterministic unroll/procedural choice, symbolic parameters/generate, one-module-per-structure, hierarchy, clocks/resets/CDC/RDC, protocol ordering, latency/throughput/capacity, user-owned state, memories/effects, source maps, and portable-Verilog capabilities unless an explicit separately named transformation contract permits a verified change.",
        "  - Cover value staging and generate, numeric/width/overflow, aggregates/connections/protocols, quantities/effects, domains/CDC/RDC/reset, automatic pipelines, portable Verilog/backend inference, open-source verification, mixed-signal boundaries, plugin manifests/capabilities/lifecycle/loaders/adapters/trust/lockfiles, diagnostics, libraries, and migration.":
        "  - Cover value staging; Scala elaboration, symbolic generate, and bounded hardware loops; signed/unsigned/signless declarations/literals/conversions/backend mapping; numeric/width/overflow; aggregates/connections/protocols; quantities/effects; domains/CDC/RDC/reset; automatic pipelines; portable Verilog/backend inference; open-source verification; mixed-signal boundaries; plugin manifests/capabilities/lifecycle/loaders/adapters/trust/lockfiles; diagnostics; libraries; and migration.",
        "  - Reassess the current standards and tool support; map IR/plugin/backend coverage; evaluate native `typedef enum logic` emission, design-level enum packages/compile-order manifests, enum-typed ports/parameters/aggregates/memories, statechart lowering, and compatibility with portable-Verilog numeric mappings; identify required changes; and approve or reject implementation through a separate gate without speculating syntax into the stable API.":
        "  - Reassess the current standards and tool support; map IR/plugin/backend coverage; evaluate exact `logic signed`/signed parameter/localparam/packed-field/array/memory/function/loop-variable lowering and parity with portable Verilog, native `typedef enum logic` emission, design-level enum packages/compile-order manifests, enum-typed ports/parameters/aggregates/memories, structural generate and procedural-loop lowering, statechart lowering, and compatibility with portable-Verilog numeric mappings; identify required changes; and approve or reject implementation through a separate gate without speculating syntax into the stable API.",
    }
    for old, new in replacements.items():
        text = replace_once(text, old, new, f"roadmap replacement: {old[:50]}")

    ROADMAP.write_text(text, encoding="utf-8")


def update_architecture_index() -> None:
    path = ROOT / "docs/architecture/README.md"
    text = path.read_text(encoding="utf-8")
    row = (
        "| [0015](0015-native-scala-enum-and-hierarchical-fsm.md) | Use native Scala 3 enums "
        "with stable canonical HDL encoding and typed reusable hierarchical/parallel FSM graphs "
        "with explicit reset, priority, recursion bounds, reports, and proof contracts. |"
    )
    addition = row + "\n" + (
        "| [0016](0016-signed-types-and-staged-loops.md) | Preserve signed finite-width type "
        "semantics and distinguish Scala elaboration, symbolic structural generate, and bounded "
        "hardware-iteration loops through verified Verilog-family lowering. |"
    )
    path.write_text(replace_once(text, row, addition, "architecture ADR 0016 row"), encoding="utf-8")


def update_core_plan() -> None:
    path = ROOT / "docs/roadmap/core-semantics-api-v0.3-plan.md"
    text = path.read_text(encoding="utf-8")
    text = insert_after(
        text,
        "**Enum/FSM architecture:** [ADR 0015](../architecture/0015-native-scala-enum-and-hierarchical-fsm.md)",
        "\n**Signed/loop architecture:** [ADR 0016](../architecture/0016-signed-types-and-staged-loops.md)",
        "core plan architecture metadata",
    )

    signed_section = """## Signed finite-width values and staged loops

[ADR 0016](../architecture/0016-signed-types-and-staged-loops.md), [`signed-loop-api-v0.3-plan.md`](signed-loop-api-v0.3-plan.md), and [`signed-loop-api-v0.3-surface.json`](signed-loop-api-v0.3-surface.json) define mandatory Increment 13 candidates.

`Bits` is signless, `UInt` is unsigned, and `SInt` is two's-complement signed. The v0.3 gate freezes exact signed declarations, literals, parameters/localparams, ports, memories, aggregate fields, arithmetic/comparison/shift result types, numeric conversion versus bit reinterpretation, mixed-sign diagnostics, and portable Verilog/future SystemVerilog mappings.

The gate also distinguishes:

- ordinary Scala loops, executed and fully elaborated during construction;
- symbolic structural `generate(...)`, preserved as target-visible hierarchy;
- a separate bounded hardware-loop operation, lowered deterministically to procedural HDL or verified unrolled operations.

Runtime trip counts, unbounded `while`, hidden multi-cycle loop synthesis, structural declarations inside procedural loops, and backend-defined signedness are outside the initial contract.


"""
    text = insert_before(text, "## Native enums and FSM/statechart semantics", signed_section, "core signed/loop section")

    text = insert_after(
        text,
        "- target generate with a non-static/non-symbolic legal bound;",
        "\n- bounded hardware loop with a runtime trip count, structural body, unbounded termination, illegal recurrence, or uncovered parameter envelope;",
        "core loop diagnostics",
    )

    text = insert_after(
        text,
        "- constant folding preserves width, signedness, overflow, and dimension semantics.",
        "\n- portable Verilog and future SystemVerilog signed declarations, parameters, literals, casts, shifts, arrays, and memories preserve the same target-neutral result;\n- `Bits` arithmetic and implicit mixed `UInt`/`SInt` arithmetic/comparison are errors;\n- numeric signedness conversion and bit reinterpretation remain separate operations.",
        "core signed rules",
    )

    positive = "- full-width unsigned and signed arithmetic;"
    positive_extra = "\n" + "\n".join(
        (
            "- signed ports/registers/parameters/memories, exact negative literals, numeric conversions, bit reinterpretations, and arithmetic/logical shifts;",
            "- Scala elaboration loops, symbolic structural generate loops, bounded hardware loops, and collection map/reduce candidates;",
        )
    )
    text = insert_after(text, positive, positive_extra, "core positive signed/loop matrix")

    negative = "- implicit narrowing or signedness change;"
    negative_extra = "\n" + "\n".join(
        (
            "- implicit `Bits` arithmetic, mixed `UInt`/`SInt` operation, ambiguous negative literal, or signed/logical shift mismatch;",
            "- symbolic parameter used as a Scala loop bound, runtime generate/hardware-loop bound, structural procedural-loop body, unbounded/data-dependent loop, or loop-carried combinational cycle;",
        )
    )
    text = insert_after(text, negative, negative_extra, "core negative signed/loop matrix")

    exit_anchor = "11. the machine-readable manifest, migration notes, diagnostics, and CI are green."
    exit_new = "\n".join(
        (
            "11. signed declaration/literal/parameter/memory/expression/conversion and Verilog-family mapping contracts are exact;",
            "12. elaboration, generate, and bounded hardware-loop contracts plus procedural/unrolled equivalence and dynamic-loop rejection are proven;",
            "13. the machine-readable manifest, migration notes, diagnostics, and CI are green.",
        )
    )
    text = replace_once(text, exit_anchor, exit_new, "core exit criteria")
    path.write_text(text, encoding="utf-8")


def update_digital_adr() -> None:
    path = ROOT / "docs/architecture/0010-digital-verilog-open-source-verification.md"
    text = path.read_text(encoding="utf-8")
    section = """## Signed values and staged loops

[ADR 0016](0016-signed-types-and-staged-loops.md) defines signed finite-width values and loop categories. Portable Verilog emits signed vectors/parameters/localparams, explicit signed literals, correct shifts/casts, structural generate loops, and bounded procedural loops or verified unrolled equivalents. Arbitrary-width `SInt` is never replaced with generic `integer`.

The digital tool matrix checks signed width/extension/comparison/shift behavior, mixed-sign rejection, signed memories and aggregate fields, symbolic parameterized generate, index bounds, and procedural-versus-unrolled loop equivalence. A dynamic or unbounded loop is rejected before HDL emission rather than converted into a hidden multi-cycle controller.


"""
    text = insert_before(text, "## Enum and FSM representation", section, "digital ADR signed/loop section")
    path.write_text(text, encoding="utf-8")


def update_digital_plan() -> None:
    path = ROOT / "docs/roadmap/digital-verilog-open-source-verification-plan.md"
    text = path.read_text(encoding="utf-8")
    text = insert_after(
        text,
        "- digital scalar/vector/aggregate ports and signals;",
        "\n- signless `Bits`, unsigned `UInt`, signed `SInt`, signed parameters/memories/aggregate fields, and explicit signed conversions/literals/shifts;\n- elaboration-expanded structure, symbolic structural generate loops, and bounded hardware iteration with deterministic procedural/unrolled lowering;",
        "digital classification signed/loop",
    )
    text = insert_after(
        text,
        "- modules, ports, parameters, local parameters, and named overrides;",
        "\n- signed vector ports/wires/registers/parameters/localparams/memories/aggregate fields, explicitly sized signed literals, typed shifts/casts, structural `genvar` loops, and bounded procedural `for` loops or verified unrolled equivalents;",
        "digital profile signed/loop",
    )

    section = """## Signed and loop lowering

The portable profile follows [ADR 0016](../architecture/0016-signed-types-and-staged-loops.md):

- `SInt(width)` becomes an exact-width signed vector, not an unbounded or generic integer;
- signedness is retained on ports, nets, registers, parameters, localparams, memories, aggregate fields, generated helpers, and source metadata;
- literals, extensions, comparisons, and arithmetic/logical shifts are emitted explicitly enough to avoid Verilog context-dependent changes;
- casts are generated only from explicit Nodal numeric conversion or bit reinterpretation;
- ordinary Scala loops are already elaborated and emit no loop syntax;
- symbolic structural generation emits deterministic `genvar`/`generate for` constructs;
- bounded hardware iteration emits a procedural `for` or verified unrolled operations according to a locked profile decision;
- runtime/unbounded loops and hidden multi-cycle iteration are unsupported in the initial portable profile.


"""
    text = insert_before(text, "## Enum and FSM lowering", section, "digital plan signed/loop section")

    text = insert_after(
        text,
        "- typed scalar/vector/aggregate signal access through emitted metadata;",
        "\n- typed `Bits`/`UInt`/`SInt` access, signed boundary values, memory elements, literals, shifts, comparisons, and loop-index/source-map reconstruction;",
        "digital simulation signed/loop",
    )
    text = insert_after(
        text,
        "- fixed-latency automatic pipelines against latency-aligned reference behavior.",
        "\n- signed expression lowering and procedural-versus-unrolled/generate-normalized loop implementations against the same typed reference semantics.",
        "digital equivalence signed/loop",
    )
    text = insert_after(
        text,
        "- no output before a valid input transaction.",
        "\n- signed extension/cast/shift correctness, loop index bounds, finite parameter envelopes, no out-of-range access, and no unintended loop-carried combinational recurrence.",
        "digital formal signed/loop",
    )
    path.write_text(text, encoding="utf-8")


def update_json_surfaces() -> None:
    roadmap_dir = ROOT / "docs/roadmap"
    for path in sorted(roadmap_dir.glob("*.json")):
        data = json.loads(path.read_text(encoding="utf-8"))
        if "roadmap_revision" in data:
            data["roadmap_revision"] = "1.10"
        path.write_text(json.dumps(data, indent=2) + "\n", encoding="utf-8")

    core_path = roadmap_dir / "core-semantics-api-v0.3-surface.json"
    core = json.loads(core_path.read_text(encoding="utf-8"))
    core.setdefault("documents", {})["signed_loop_plan"] = "docs/roadmap/signed-loop-api-v0.3-plan.md"
    core["signed_and_loops"] = {
        "architecture": "docs/architecture/0016-signed-types-and-staged-loops.md",
        "types": ["Bits", "UInt", "SInt", "Bool"],
        "mixed_signedness_implicit": False,
        "numeric_conversion_distinct_from_reinterpretation": True,
        "portable_verilog_signed_vectors_parameters_memories": True,
        "loop_categories": ["scala-elaboration", "symbolic-generate", "bounded-hardware-iteration"],
        "runtime_or_unbounded_loop_initial_support": False,
        "formal_freeze_increment": 15,
    }
    if "signed-loop-candidate-compilation" not in core["required_evidence"]:
        core["required_evidence"].insert(1, "signed-loop-candidate-compilation")
    core_path.write_text(json.dumps(core, indent=2) + "\n", encoding="utf-8")

    digital_path = roadmap_dir / "digital-backend-v0.3-surface.json"
    digital = json.loads(digital_path.read_text(encoding="utf-8"))
    digital["signed_and_loops"] = {
        "architecture": "docs/architecture/0016-signed-types-and-staged-loops.md",
        "portable_verilog_signed": "signed vectors parameters localparams memories and fields",
        "future_systemverilog_signed": "logic signed and equivalent exact-width constructs",
        "generate": "genvar generate for",
        "bounded_iteration": ["procedural for", "verified unroll"],
        "dynamic_trip_count": False,
        "unbounded_while": False,
        "required_checks": [
            "signed width extension comparison shift and cast parity",
            "mixed-sign negative fixtures",
            "parameterized generate",
            "procedural versus unrolled equivalence",
            "index bounds and no out-of-range access",
        ],
    }
    digital_path.write_text(json.dumps(digital, indent=2) + "\n", encoding="utf-8")

    pass_path = roadmap_dir / "target-hdl-optimization-pass-v0.1-surface.json"
    target_pass = json.loads(pass_path.read_text(encoding="utf-8"))
    target_pass["signed_loop_preservation"] = {
        "signed_type_width_cast_shift_and_literal": True,
        "loop_category_and_iteration_order": True,
        "symbolic_generate": True,
        "unroll_reroll_requires_declared_effect_and_proof": True,
    }
    pass_path.write_text(json.dumps(target_pass, indent=2) + "\n", encoding="utf-8")


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
        "**Revision:** 1.10",
        "## Signed numeric and staged-loop architecture",
        "Increment 54 — Digital signed/unsigned type, literal, native enum ABI, and port layer",
        "Increment 55 — Digital expressions, bounded hardware iteration, and continuous assignments",
        "structural `genvar` generate loops",
        "Future SystemVerilog/SystemVerilog-AMS backend research gate",
        "No official reusable model/component library or production plugin is implemented by Increments 0-113.",
    )
    for fragment in required:
        if fragment not in text:
            raise SystemExit(f"roadmap lacks required fragment: {fragment}")

    for relative in (
        "docs/architecture/0016-signed-types-and-staged-loops.md",
        "docs/roadmap/signed-loop-api-v0.3-plan.md",
        "docs/roadmap/signed-loop-api-v0.3-surface.json",
    ):
        if not (ROOT / relative).is_file():
            raise SystemExit(f"missing signed/loop document: {relative}")

    print("signed/loop roadmap update validated")


def main() -> None:
    update_roadmap()
    update_architecture_index()
    update_core_plan()
    update_digital_adr()
    update_digital_plan()
    update_json_surfaces()
    validate()


if __name__ == "__main__":
    main()
