#!/usr/bin/env python3
"""Apply the shaped-value, materialization, naming, and quality roadmap revision."""

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
            f"{subject}: expected exactly one anchor, found {count}: {old[:140]!r}"
        )
    return text.replace(old, new, 1)


def insert_after(text: str, anchor: str, addition: str, subject: str) -> str:
    return replace_once(text, anchor, anchor + addition, subject)


def insert_before(text: str, anchor: str, addition: str, subject: str) -> str:
    return replace_once(text, anchor, addition + anchor, subject)


def update_roadmap() -> None:
    text = ROADMAP.read_text(encoding="utf-8")
    text = replace_once(text, "**Revision:** 1.10", "**Revision:** 1.11", "roadmap revision")

    fixed_anchor = (
        "- Keep aggregate payloads directionless; apply direction at ports, use plain/`Valid`/`Stream` "
        "protocol types consistently, and require exact direct connections with typed adapters for "
        "intentional conversion."
    )
    fixed_extra = "\n" + "\n".join(
        (
            "- Represent multidimensional structural values with semantic rank, parameterized dimensions, stable row-major indexing, and explicit target layouts. Keep `Vec` structural and `Mem` addressable; target unpacked-array syntax never defines memory semantics.",
            "- Map portable-Verilog multidimensional ports to deterministic flat packed carriers and future SystemVerilog ports to unpacked multidimensional arrays of packed elements by default, with explicit packed-layout interoperability when requested.",
            "- Keep pure combinational expressions in typed DAG form and inline compiler-generated single-use expressions whenever exact width/sign/four-state semantics permit. Materialize only for a declared reason and give every required net/state a deterministic semantic name.",
            "- Reject invalid hardware through mandatory staged construction, semantic-graph, MLIR, target-legalization, reparse, lint, and synthesis gates before an HDL artifact is accepted. Core safety verifiers cannot be disabled by plugins or optimization passes.",
        )
    )
    text = insert_after(text, fixed_anchor, fixed_extra, "fixed shaped/naming/quality direction")

    public_anchor = (
        "- Keep ordinary Scala `for` for elaboration, reserve `generate(...)` for symbolic structural "
        "replication, and freeze a separate concise bounded hardware-loop operation plus collection "
        "`map`/`reduce` candidates. Reject runtime trip counts and unbounded `while` in the initial "
        "synthesizable contract."
    )
    public_extra = "\n" + "\n".join(
        (
            "- Freeze a parameterized multidimensional `Vec` shape/index/flatten/reshape contract, explicit `Vec` versus `Mem` storage semantics, and target layout policies for portable-Verilog flat carriers and future-SystemVerilog unpacked/packed ports.",
            "- Freeze emission configuration candidates for safe expression inlining, readable/debug/tool-friendly materialization, semantic naming, source-span maps, and Fast/Default/Release quality profiles with typed waivers that cannot suppress mandatory safety checks.",
        )
    )
    text = insert_after(text, public_anchor, public_extra, "public shaped/naming/quality direction")

    architecture_sections = """## Multidimensional shaped-value and target-layout architecture

The binding architecture is [ADR 0017](../architecture/0017-semantic-multidimensional-values-and-target-layouts.md). Exact shape/layout candidates and freeze criteria are in [`shaped-values-naming-quality-v0.3-plan.md`](shaped-values-naming-quality-v0.3-plan.md), with a machine-readable candidate in [`shaped-values-naming-quality-v0.3-surface.json`](shaped-values-naming-quality-v0.3-surface.json).

Nodal adopts:

> **Shape is semantic, layout is explicit evidence, and target syntax never decides whether a value is a memory.**

A parameterized multidimensional `Vec` has static rank, positive elaboration/symbolic dimensions, zero-based row-major indexing, exact element type, and deterministic flatten/reshape formulas. `Vec` remains structural; `Mem` alone owns addressable-storage latency, ports, collision, initialization, and mapping semantics.

Portable Verilog lowers a shaped module boundary to one flat packed carrier plus verified element/index views. A flattened `Vec[SInt]` carrier is signless and signed element accesses use deterministic signed views because portable Verilog cannot declare each flattened element independently signed. Future SystemVerilog defaults to unpacked multidimensional ports of packed signed/unsigned elements and may use an explicit packed-dimensional layout for serialization/interoperability.


## Expression materialization and semantic naming architecture

The binding architecture is [ADR 0018](../architecture/0018-expression-materialization-and-semantic-naming.md). Nodal adopts:

> **Do not name an expression merely because the compiler has a node; name only storage, sharing, observability, legality, or an explicit user boundary.**

The default candidate inlines pure single-use expressions while preserving the exact typed operation tree. Shared/observable/target-required values are materialized with reason codes. All emitted state receives a deterministic name derived from explicit/source names, destination role, subsystem role, source origin, or stable digest—not traversal-number `_zz` chains. Expression-level source maps survive inlining.


## Mandatory pre-emission quality-gate architecture

The binding architecture is [ADR 0019](../architecture/0019-mandatory-pre-emission-hardware-quality-gates.md). Nodal adopts:

> **Reject invalid hardware at the highest semantic layer, reverify after every lowering, and accept emitted HDL only with retained evidence.**

Mandatory internal checks cover scope/hierarchy, connections/drivers, widths/signs/shapes, latches, combinational loops, state/reset, CDC/RDC, protocols, parameters/generate/loops, enums/FSMs, pipelines, memories/effects, units/analog/mixed signal, and target capability. Generated HDL is then reparsed and independently checked with the selected Verilator/Icarus/Yosys/OpenVAF/simulator profile. Failed or partial output is diagnostic-only, and plugins cannot disable core verifiers.


"""
    text = insert_before(
        text,
        "## Enum and reusable FSM architecture",
        architecture_sections,
        "shaped/naming/quality architecture sections",
    )

    m0_old = (
        "- **M0 — Foundation:** reproducible builds, CI, clock/reset plus unified core-semantics/"
        "automatic-pipeline API freezes, digital-backend selection contract, and enforced core/"
        "library boundaries."
    )
    m0_new = (
        "- **M0 — Foundation:** reproducible builds, CI, clock/reset plus unified core-semantics/"
        "automatic-pipeline API freezes, shaped-value/layout and naming/materialization contracts, "
        "mandatory quality-gate policy, digital-backend selection contract, and enforced core/"
        "library boundaries."
    )
    text = replace_once(text, m0_old, m0_new, "M0 milestone")

    m3_old = (
        "- **M3 — Digital/AMS preview:** implicit-domain digital state, exact signed finite-width "
        "types, elaboration/generate/bounded hardware loops, native typed enums, reusable "
        "hierarchical/parallel FSMs, automatic fixed/valid/elastic pipelines, portable Verilog "
        "with open-source simulation/synthesis/equivalence and compiler-generated formal "
        "verification, CDC/RDC-safe clock/reset architecture, mixed-signal crossings, and "
        "Verilog-AMS emission."
    )
    m3_new = (
        "- **M3 — Digital/AMS preview:** implicit-domain digital state, exact signed finite-width "
        "types, parameterized multidimensional shaped values, elaboration/generate/bounded hardware "
        "loops, native typed enums, reusable hierarchical/parallel FSMs, automatic fixed/valid/"
        "elastic pipelines, readable HDL without avoidable anonymous-wire chains, portable Verilog "
        "with mandatory internal checks plus open-source lint/simulation/synthesis/equivalence and "
        "compiler-generated formal verification, CDC/RDC-safe clock/reset architecture, mixed-signal "
        "crossings, and Verilog-AMS emission."
    )
    text = replace_once(text, m3_old, m3_new, "M3 milestone")

    m4_old = (
        "- **M4 — Scalable core release:** packaged compiler, complete reference, frozen plugin and "
        "target-HDL pass SPIs, deterministic extension/pass graphs, optimization proof evidence, "
        "conformance kits, library-author contract, and compatibility policy."
    )
    m4_new = (
        "- **M4 — Scalable core release:** packaged compiler, complete reference, frozen plugin and "
        "target-HDL pass SPIs, deterministic extension/pass graphs, optimization proof evidence, "
        "machine-readable check coverage and waiver inventory, conformance kits, library-author "
        "contract, and compatibility policy."
    )
    text = replace_once(text, m4_old, m4_new, "M4 milestone")

    inc13_anchor = (
        "  - Compile directionless nested aggregates/vectors, exact port/connection semantics, typed "
        "adapters/views, and general plain/`Valid`/`Stream` protocols."
    )
    inc13_extra = "\n" + "\n".join(
        (
            "  - Compile rank-one through rank-four `Vec` candidates with positive Scala/symbolic parameter dimensions, multidimensional indexing/slicing/flatten/reshape/map/zip/reduce, signed elements, exact shape connections, explicit `Vec` versus `Mem`, portable-Verilog flat layout, and future-SystemVerilog unpacked/packed layout policies.",
            "  - Compile `TemporaryPolicy`/`NamingPolicy`/`CheckProfile` candidates, safe inlining of pure expression chains, shared/observable/target-required materialization, explicit names versus keep/debug intent, deterministic sink-affinity register names, typed waivers, and negative latch/loop/driver/hierarchy/shape/profile fixtures from ADRs 0018-0019.",
        )
    )
    text = insert_after(text, inc13_anchor, inc13_extra, "Increment 13 shaped/naming/quality")

    inc15_old = (
        "  - Freeze value stages; ordinary Scala elaboration loops; symbolic target `generate`; "
        "bounded hardware iteration and collection operations; `Bits`/`UInt`/`SInt`; exact signed "
        "declaration/literal/parameter/memory/expression/shift/conversion/reinterpretation and "
        "Verilog-family lowering rules; lossless numeric/width semantics; explicit lossy conversions; "
        "directionless aggregates; exact connections/adapters; plain/`Valid`/`Stream`; physical "
        "quantities; memory/external effect contracts; native Scala enums; canonical enum ABI/safe "
        "decode/exhaustive selection; flat and reusable hierarchical/parallel/timed/bounded-recursive "
        "FSMs; local FSM encoding/illegal-state policies; `pipe`/`delay`; latency/throughput/ready "
        "policy; stage constraints; parameter-envelope scheduling; and schedule evidence."
    )
    inc15_new = (
        "  - Freeze value stages; ordinary Scala elaboration loops; symbolic target `generate`; "
        "bounded hardware iteration and collection operations; `Bits`/`UInt`/`SInt`; exact signed "
        "declaration/literal/parameter/memory/expression/shift/conversion/reinterpretation and "
        "Verilog-family lowering rules; lossless numeric/width semantics; explicit lossy conversions; "
        "directionless aggregates; parameterized multidimensional `Vec` shape/index/flatten/reshape "
        "and target layout; explicit `Vec` versus `Mem`; exact connections/adapters; plain/`Valid`/"
        "`Stream`; physical quantities; memory/external effect contracts; native Scala enums; canonical "
        "enum ABI/safe decode/exhaustive selection; flat and reusable hierarchical/parallel/timed/"
        "bounded-recursive FSMs; local FSM encoding/illegal-state policies; safe expression inlining, "
        "materialization reasons, semantic naming, source-span maps, Fast/Default/Release check "
        "profiles and typed waivers; `pipe`/`delay`; latency/throughput/ready policy; stage constraints; "
        "parameter-envelope scheduling; and schedule evidence."
    )
    text = replace_once(text, inc15_old, inc15_new, "Increment 15 freeze")

    replacements = {
        "- [ ] **Increment 16 — Elaboration, hierarchy, and lexical domain-context kernel**\n  - Implement deterministic module construction, ownership, lifecycle, default-domain requirements, lexical domain stack, single-domain inheritance, named multi-domain requirements, typed bindings, and root-domain validation without public Scala implicits, globals, thread-locals, or JVM identity.":
        "- [ ] **Increment 16 — Elaboration, hierarchy, shape, and lexical domain-context kernel**\n  - Implement deterministic module construction, ownership, lifecycle, shaped-value rank/dimension capture, structural `Vec` versus `Mem` intent, transactional construction close, default-domain requirements, lexical domain stack, single-domain inheritance, named multi-domain requirements, typed bindings, and root-domain validation without public Scala implicits, globals, thread-locals, or JVM identity.",
        "- [ ] **Increment 17 — Source locations and deterministic naming**\n  - Capture Scala source locations and define stable names for modules, declarations, domains, generated clock/reset ports, synchronizers, FIFOs, reset controllers, crossings, and anonymous expressions.":
        "- [ ] **Increment 17 — Source spans, semantic naming, and origin graph**\n  - Capture Scala declaration/member names and expression spans; build stable origin/sink-affinity metadata; define deterministic names for modules, declarations, shaped elements/views, domains, generated clock/reset ports, synchronizers, FIFOs, reset controllers, crossings, pipeline/FSM state, anonymous registers, and required temporaries. Prohibit traversal-counter-only normal names and retain expression-level source maps when nodes are inlined.",
        "  - Add target-neutral modules, ports, symbols, instances, symbolic parameters, signless/unsigned/signed finite-width types and constants, structural generate regions, bounded hardware-iteration regions with typed induction variables/effects, semantic enum types/cases/canonical encodings, FSM definitions/regions/states/transitions/actions/completion/encoding policies, domain requirements/bindings, clock/reset relationships, state ownership, timing provenance, and crossing operations/types. Reuse CIRCT only after semantic comparison.":
        "  - Add target-neutral modules, ports, symbols, instances, symbolic parameters, signless/unsigned/signed finite-width types and constants, ranked shaped types with symbolic dimensions, canonical index/flatten/layout and structural-storage metadata, expression origin/materialization/observability metadata, structural generate regions, bounded hardware-iteration regions with typed induction variables/effects, semantic enum types/cases/canonical encodings, FSM definitions/regions/states/transitions/actions/completion/encoding policies, domain requirements/bindings, clock/reset relationships, state ownership, timing provenance, and crossing operations/types. Reuse CIRCT only after semantic comparison.",
        "- [ ] **Increment 21 — Native parse, verify, and pass pipeline**\n  - Parse Nodal MLIR, run registered verifiers/passes, print normalized IR, and expose explicit lit/FileCheck-friendly pipelines.":
        "- [ ] **Increment 21 — Native parse, staged semantic verification, and pass pipeline**\n  - Parse Nodal MLIR; implement mandatory construction-closure, driver/assignment coverage, latch, combinational-cycle, hierarchy, width/sign/shape/layout/storage, parameter/generate/loop, enum/FSM, clock/reset/CDC/RDC, protocol/pipeline, memory/effect, analog/mixed-signal, and target-capability verifiers; run registered passes with analysis invalidation/reverification; print normalized IR; and expose explicit lit/FileCheck-friendly gate pipelines. Preserve the last accepted state transactionally on failure.",
        "  - Map parser, verifier, pass, backend, external-tool, signed literal/conversion/mixed-sign/width/shift errors, loop stage/bound/body/dependency/effect/profile errors, enum encoding/decode/exhaustiveness, FSM graph/transition/recursion/illegal-state, domain-binding, CDC, RDC, gate/mux, and waiver diagnostics back to Scala locations and stable codes.":
        "  - Map construction, driver/latch/cycle/hierarchy, shape/rank/layout/storage/index, materialization/naming/source-span, parser, verifier, pass, backend, external-tool, signed literal/conversion/mixed-sign/width/shift, loop stage/bound/body/dependency/effect/profile, enum encoding/decode/exhaustiveness, FSM graph/transition/recursion/illegal-state, domain-binding, CDC, RDC, gate/mux, protocol/pipeline, memory/effect, analog/mixed-signal, and waiver diagnostics back to Scala locations, hierarchy/index paths, and stable codes.",
        "  - Add translation registration, deterministic output handling, `verilog-a`/`verilog-ams` profiles, and explicit unsupported-feature errors.":
        "  - Add translation registration, deterministic output handling, profile-owned shaped-value layouts, expression materialization/naming and CheckProfile configuration, transactional target verification/reparse hooks, `verilog-a`/`verilog-ams` profiles, and explicit unsupported-feature errors.",
        "  - Prove byte-identical MLIR, HDL, domain manifests, and CDC/RDC reports across repeated builds and valid traversal orders.":
        "  - Prove byte-identical MLIR, HDL, shape/layout and storage manifests, materialization decisions/reasons, semantic names, expression source maps, check inventories/waivers, domain manifests, and CDC/RDC reports across repeated builds and valid traversal orders.",
        "- [ ] **Increment 43 — Arrays and elaboration-time generation**\n  - Implement fixed arrays, indexing/slices, Scala elaboration loops, target generate constructs, and static bounds.":
        "- [ ] **Increment 43 — Analog arrays, shaped values, and elaboration-time generation**\n  - Implement legal fixed/symbolic analog arrays under ADR 0017 shape/index rules, analog-object capability restrictions, indexing/slices, Scala elaboration loops, target generate constructs, static bounds, target layout checks, and explicit rejection of illegal analog flattening or memory inference.",
        "  - Add bit/logic, signless `Bits`, unsigned `UInt`, two's-complement `SInt`, exact signed/negative literals, signed parameters/localparams, signed aggregate fields and memory elements, numeric conversion versus bit reinterpretation, integers, reals, nets/variables, directions, four-state policy, native Scala enum derivation, semantic enum types/cases, canonical sequential/one-hot/Gray/custom encodings, safe decode, exhaustive selection, enum aggregates/protocols/parameters/memories, ABI hashes, and compatible CIRCT/Nodal lowering.":
        "  - Add bit/logic, signless `Bits`, unsigned `UInt`, two's-complement `SInt`, exact signed/negative literals, signed parameters/localparams, signed aggregate fields and memory elements, ranked parameterized `Vec` and nested shaped values, canonical row-major indexing/flattening and exact shape connections, structural storage versus `Mem`, numeric conversion versus bit reinterpretation, integers, reals, nets/variables, directions, four-state policy, native Scala enum derivation, semantic enum types/cases, canonical sequential/one-hot/Gray/custom encodings, safe decode, exhaustive selection, enum aggregates/protocols/parameters/memories, ABI hashes, and compatible CIRCT/Nodal lowering.",
        "  - Add arithmetic, logic, bitwise, comparisons, concatenation, extraction, conditionals, exact width/sign and mixed-sign rules, arithmetic/logical shifts, explicit signed casts/conversions, continuous assignment, typed hardware `map`/`zip`/`reduce`/`fold`/`scan`, and bounded hardware iteration with finite static/symbolic bounds, ordered effects, dependency/index/driver checks, and deterministic unrolled versus procedural-loop lowering candidates. Reject runtime trip counts, structural declarations, hidden multi-cycle behavior, and unbounded/data-dependent loops.":
        "  - Add typed expression DAGs with arithmetic, logic, bitwise, comparisons, concatenation, extraction, conditionals, multidimensional index/slice/flatten/reshape, exact width/sign/shape and mixed-sign rules, arithmetic/logical shifts, explicit signed casts/conversions, continuous assignment, typed hardware `map`/`zip`/`reduce`/`fold`/`scan`, safe single-use inlining, shared/observable/target-required materialization with reason codes, and bounded hardware iteration with finite static/symbolic bounds, ordered effects, dependency/index/driver checks, and deterministic unrolled versus procedural-loop lowering candidates. Reject runtime trip counts, structural declarations, hidden multi-cycle behavior, unbounded/data-dependent loops, accidental flat-carrier arithmetic, latches, and combinational cycles.",
        "  - Implement default-domain inheritance, typed named-domain binding, inferred clock/reset ports, symbolic parameters, domain-polymorphic modules, structural `generate` regions with symbolic bounds/nested legal generation, deterministic index-aware hierarchy naming, and deterministic variants only for material edge/reset differences. Keep ordinary Scala loops elaboration-only and preserve native target generate instead of clone-per-value specialization.":
        "  - Implement default-domain inheritance, typed named-domain binding, inferred clock/reset ports, symbolic parameters, domain-polymorphic modules, parameterized shaped ports/instances, structural `generate` regions with symbolic bounds/nested legal generation, deterministic index-aware hierarchy and sink-affinity state naming, and deterministic variants only for material edge/reset differences. Keep ordinary Scala loops elaboration-only and preserve native target generate instead of clone-per-value specialization.",
        "  - Emit the portable synthesizable Verilog profile with exact signed vector ports/wires/registers/parameters/localparams/memories/aggregate fields, explicitly sized signed literals, typed shifts/casts, structural `genvar` generate loops, bounded procedural `for` loops or verified unrolled equivalents, symbolic parameters/generate, hierarchy, flattened aggregates/protocols, canonical enum vectors and member `localparam`s, enum configuration parameters, flat/hierarchical/parallel FSM state and completion logic, clocks/resets, memories, CDC/RDC, automatic pipelines, black boxes, assertions/formal hooks, signed/loop/enum/FSM manifests, source maps, deterministic formatting, and exact golden fixtures.":
        "  - Emit the portable synthesizable Verilog profile with exact signed vector ports/wires/registers/parameters/localparams/memories/aggregate fields, explicitly sized signed literals, typed shifts/casts, parameterized multidimensional `Vec` ports as canonical flat packed carriers, verified row-major offset/slice/reshape formulas, deterministic signed element views, structural `Vec` versus `Mem` evidence, structural `genvar` generate loops, bounded procedural `for` loops or verified unrolled equivalents, symbolic parameters/generate, hierarchy, flattened aggregates/protocols, canonical enum vectors and member `localparam`s, enum configuration parameters, flat/hierarchical/parallel FSM state and completion logic, clocks/resets, memories, CDC/RDC, automatic pipelines, black boxes, assertions/formal hooks, safe expression inlining and semantic temporary/state naming, materialization/shape/storage/signed/loop/enum/FSM manifests, expression-level source maps, deterministic formatting, target reparse, and exact golden fixtures.",
        "  - Pin and integrate Verilator and Icarus Verilog; run independent parse/elaboration, strong lint, fast compiled simulation, event-driven smoke simulation, normalized diagnostics, deterministic seeds, VCD/FST waveforms, and supported coverage.":
        "  - Pin and integrate Verilator and Icarus Verilog; run independent parse/elaboration, strong lint, fast compiled simulation, event-driven smoke simulation, normalized diagnostics, deterministic seeds, VCD/FST waveforms, supported coverage, multidimensional flat-layout/index/reshape fixtures, signed-element-view tests, no-avoidable-anonymous-wire goldens, and source-map correlation for inlined expressions.",
        "  - Pin and integrate Yosys, SBY, and selected solvers. Run hierarchy/process/memory checks, target-neutral synthesis, inferred-latch/loop/black-box diagnostics, normalized netlist emission, statistics, and parameter elaboration matrices.":
        "  - Pin and integrate Yosys, SBY, and selected solvers. Run hierarchy/process/memory/driver checks, target-neutral synthesis, inferred-latch/combinational-loop/black-box diagnostics, structural-`Vec` unexpected-memory-inference audit, normalized netlist emission, statistics, and parameter/shape/layout/generate elaboration matrices.",
        "  - Add RTL-to-optimized/netlist equivalence, including signed width/extension/cast/shift checks, generate/procedural/unrolled-loop equivalence and index-bound properties, latency-aware fixed-pipeline, and protocol-aware elastic checks.":
        "  - Add RTL-to-optimized/netlist equivalence, including signed width/extension/cast/shift checks, multidimensional flatten/unpack/index/reshape and inline-versus-debug materialization equivalence, generate/procedural/unrolled-loop equivalence and index-bound properties, latency-aware fixed-pipeline, and protocol-aware elastic checks.",
        "  - Verify domain bindings, direct/combinational crossings, multi-bit misuse, pulses, reconvergence, reset release/reconvergence, generated clocks, gates/muxes, analog/digital legality, conversion loops, drivers, waivers, and profile restrictions.":
        "  - Verify domain bindings, direct/combinational crossings, multi-bit misuse, pulses, reconvergence, reset release/reconvergence, generated clocks, gates/muxes, analog/digital legality, conversion loops, aggregate/shaped driver paths, latches, combinational/ready loops, structural-storage intent, drivers, waivers, and profile restrictions.",
        "  - Emit explicit inferred clock/reset ports; signed digital vectors/parameters/literals/casts; structural generate and bounded procedural/unrolled loops; canonical enum localparams/vectors; flat, nested, parallel, timed, and bounded-procedure FSM state/action/completion logic; event processes lowered from high-level state and automatic schedules; fixed/valid/elastic pipeline registers and control; synchronizers/FIFOs; reset logic; gates/muxes; analog/digital declarations; disciplines; connect constructs; hierarchy; parameters; signed/loop/enum/FSM/latency/schedule metadata; and source maps.":
        "  - Emit explicit inferred clock/reset ports; signed digital vectors/parameters/literals/casts; parameterized multidimensional digital values using the portable flat ABI and signed element views; structural generate and bounded procedural/unrolled loops; canonical enum localparams/vectors; flat, nested, parallel, timed, and bounded-procedure FSM state/action/completion logic; event processes lowered from high-level state and automatic schedules; fixed/valid/elastic pipeline registers and control; synchronizers/FIFOs; reset logic; gates/muxes; analog/digital declarations; disciplines; connect constructs; hierarchy; parameters; safe expression inlining and deterministic semantic state/temporary names; shape/layout/storage/materialization/check/signed/loop/enum/FSM/latency/schedule metadata; expression-level source maps; and mandatory target verification/reparse evidence.",
        "  - Compile descriptors/manifests for target-neutral, digital, Verilog-A, Verilog-AMS, render-only, and reparse passes; stable pass IDs; target/profile/IR versions; extension points; ordering/conflicts; options; preservation/invalidation; proof classes; parameterization/source-map effects; profiles; native/process facets; and evidence artifacts.":
        "  - Compile descriptors/manifests for target-neutral, digital, Verilog-A, Verilog-AMS, render-only, and reparse passes; stable pass IDs; target/profile/IR versions; extension points; ordering/conflicts; options; preservation/invalidation; proof classes; parameterization, shaped-value/layout/storage, expression-materialization/naming/source-map, driver/latch/cycle/check-inventory effects; profiles; native/process facets; and evidence artifacts.",
        "  - Implement verified digital target IR using CIRCT where semantically appropriate plus Nodal-owned contracts, and typed Verilog-A/Verilog-AMS target IR preserving disciplines/nodes/branches/contributions/dimensions/continuous-time operators/events/noise/analyses/digital state/conversions/connect rules/capabilities/hierarchy/source maps.":
        "  - Implement verified digital target IR using CIRCT where semantically appropriate plus Nodal-owned shaped-value/layout/storage, expression-origin/materialization/naming, and mandatory-check contracts, and typed Verilog-A/Verilog-AMS target IR preserving disciplines/nodes/branches/contributions/dimensions/continuous-time operators/events/noise/analyses/digital state/conversions/connect rules/capabilities/hierarchy/source maps.",
        "  - Preserve widths/signedness/overflow, numeric-conversion versus reinterpretation, signed literal/shift/comparison semantics, elaboration/generate/hardware-loop category, iteration/reduction order, index bounds, deterministic unroll/procedural choice, symbolic parameters/generate, one-module-per-structure, hierarchy, clocks/resets/CDC/RDC, protocol ordering, latency/throughput/capacity, user-owned state, memories/effects, source maps, and portable-Verilog capabilities unless an explicit separately named transformation contract permits a verified change.":
        "  - Preserve widths/signedness/overflow, numeric-conversion versus reinterpretation, signed literal/shift/comparison semantics, ranked shapes/dimensions/index/flatten/layout and structural-storage class, expression tree/materialization/naming/observability and source spans, elaboration/generate/hardware-loop category, iteration/reduction order, index bounds, deterministic unroll/procedural choice, symbolic parameters/generate, one-module-per-structure, hierarchy, clocks/resets/CDC/RDC, protocol ordering, latency/throughput/capacity, user-owned state, memories/effects, mandatory check results, source maps, and portable-Verilog capabilities unless an explicit separately named transformation contract permits a verified change.",
        "  - Cover value staging; Scala elaboration, symbolic generate, and bounded hardware loops; signed/unsigned/signless declarations/literals/conversions/backend mapping; numeric/width/overflow; aggregates/connections/protocols; quantities/effects; domains/CDC/RDC/reset; automatic pipelines; portable Verilog/backend inference; open-source verification; mixed-signal boundaries; plugin manifests/capabilities/lifecycle/loaders/adapters/trust/lockfiles; diagnostics; libraries; and migration.":
        "  - Cover value staging; Scala elaboration, symbolic generate, and bounded hardware loops; signed/unsigned/signless declarations/literals/conversions/backend mapping; parameterized multidimensional `Vec`, shape/index/flatten/reshape, layout and `Vec`/`Mem`; expression inlining/materialization/naming/source maps; mandatory check profiles/inventory/waivers/transactional emission; numeric/width/overflow; aggregates/connections/protocols; quantities/effects; domains/CDC/RDC/reset; automatic pipelines; portable Verilog/backend inference; open-source verification; mixed-signal boundaries; plugin manifests/capabilities/lifecycle/loaders/adapters/trust/lockfiles; diagnostics; libraries; and migration.",
        "  - Benchmark construction, MLIR, semantic analyses, automatic pipelines, domains/CDC/RDC, portable Verilog, open-source verification, plugin manifest resolution, capability graphs, design-host contributions, native/process plugin overhead, cache behavior, pass time, memory, hierarchy, and regression launch.":
        "  - Benchmark construction, ranked shape algebra and parameter matrices, expression inlining/materialization and source-map size, naming stability, mandatory check phases/path reconstruction, MLIR, semantic analyses, automatic pipelines, domains/CDC/RDC, portable Verilog, open-source verification, plugin manifest resolution, capability graphs, design-host contributions, native/process plugin overhead, cache behavior, pass time, memory, hierarchy, and regression launch.",
        "  - Review v0.1/v0.2/v0.3 APIs and plugin SPI implementation experience, including capability identity/cardinality, phase contexts, native/process compatibility, trust, determinism, plugin/library boundaries, implicit domains, pipelines, backend inference, and low-level escape. Approve only justified changes and define semantic versioning/deprecation/source/SPI compatibility.":
        "  - Review v0.1/v0.2/v0.3 APIs and plugin SPI implementation experience, including shaped values/layout/storage, expression materialization/naming/source maps, check profiles/inventory/waivers, capability identity/cardinality, phase contexts, native/process compatibility, trust, determinism, plugin/library boundaries, implicit domains, pipelines, backend inference, and low-level escape. Approve only justified changes and define semantic versioning/deprecation/source/SPI compatibility.",
        "  - Publish the supported preview with frozen public API and plugin SPI revisions, toolchain pins, portable Verilog/Verilog-A/Verilog-AMS matrices, open-source verification evidence, plugin conformance kit, installation, examples, known limitations, library/plugin-author contracts, and reproducible provenance.":
        "  - Publish the supported preview with frozen public API and plugin SPI revisions, toolchain pins, shaped-value/layout and naming/materialization manifests, machine-readable mandatory-check coverage/waiver inventory, portable Verilog/Verilog-A/Verilog-AMS matrices, open-source verification evidence, plugin conformance kit, installation, examples, known limitations, library/plugin-author contracts, and reproducible provenance.",
        "  - Reassess the current standards and tool support; map IR/plugin/backend coverage; evaluate exact `logic signed`/signed parameter/localparam/packed-field/array/memory/function/loop-variable lowering and parity with portable Verilog, native `typedef enum logic` emission, design-level enum packages/compile-order manifests, enum-typed ports/parameters/aggregates/memories, structural generate and procedural-loop lowering, statechart lowering, and compatibility with portable-Verilog numeric mappings; identify required changes; and approve or reject implementation through a separate gate without speculating syntax into the stable API.":
        "  - Reassess the current standards and tool support; map IR/plugin/backend coverage; evaluate exact `logic signed`/signed parameter/localparam/packed-field/array/memory/function/loop-variable lowering and parity with portable Verilog; default unpacked multidimensional array ports of packed elements, optional multidimensional packed layouts, parameterized dimensions, tool/profile compatibility, wrapper/ABI manifests, and signed-element parity with flat portable carriers; native `typedef enum logic` emission, design-level enum packages/compile-order manifests, enum-typed ports/parameters/aggregates/memories; structural generate and procedural-loop lowering; statechart lowering; and compatibility with portable-Verilog numeric mappings; identify required changes; and approve or reject implementation through a separate gate without speculating syntax into the stable API.",
    }
    for old, new in replacements.items():
        text = replace_once(text, old, new, f"roadmap replacement: {old[:60]}")

    ref_anchor = "- CIRCT ESI channel buffers: <https://circt.llvm.org/docs/Dialects/ESI/>"
    ref_extra = "\n" + "\n".join(
        (
            "- Accellera SystemVerilog arrays and ports: <https://www.accellera.org/images/eda/vlog-pp/0438.html>",
            "- Yosys arrays and memories: <https://yosyshq.readthedocs.io/projects/yosys/en/stable/CHAPTER_Basics.html>",
            "- SpinalHDL design errors: <https://spinalhdl.github.io/SpinalDoc-RTD/master/SpinalHDL/Design%20errors/index.html>",
            "- CIRCT passes and combinational-cycle checks: <https://circt.llvm.org/docs/Passes/>",
        )
    )
    text = insert_after(text, ref_anchor, ref_extra, "roadmap references")

    ROADMAP.write_text(text, encoding="utf-8")


def update_architecture_index() -> None:
    path = ROOT / "docs/architecture/README.md"
    text = path.read_text(encoding="utf-8")
    row = (
        "| [0016](0016-signed-types-and-staged-loops.md) | Preserve signed finite-width type "
        "semantics and distinguish Scala elaboration, symbolic structural generate, and bounded "
        "hardware-iteration loops through verified Verilog-family lowering. |"
    )
    addition = row + "\n" + "\n".join(
        (
            "| [0017](0017-semantic-multidimensional-values-and-target-layouts.md) | Use semantic parameterized multidimensional shaped values, explicit structural-versus-memory intent, deterministic flattening, and target-aware Verilog/SystemVerilog layouts. |",
            "| [0018](0018-expression-materialization-and-semantic-naming.md) | Inline safe pure expressions, materialize only for declared reasons, and derive deterministic semantic names and expression-level source maps for required objects. |",
            "| [0019](0019-mandatory-pre-emission-hardware-quality-gates.md) | Require transactional staged internal verification and independent target lint/synthesis evidence before generated HDL is accepted. |",
        )
    )
    path.write_text(replace_once(text, row, addition, "architecture index"), encoding="utf-8")


def update_core_semantics_plan() -> None:
    path = ROOT / "docs/roadmap/core-semantics-api-v0.3-plan.md"
    text = path.read_text(encoding="utf-8")
    metadata_anchor = "**Signed/loop architecture:** [ADR 0016](../architecture/0016-signed-types-and-staged-loops.md)"
    metadata = metadata_anchor + "\n" + "\n".join(
        (
            "**Shaped-value architecture:** [ADR 0017](../architecture/0017-semantic-multidimensional-values-and-target-layouts.md)",
            "**Naming architecture:** [ADR 0018](../architecture/0018-expression-materialization-and-semantic-naming.md)",
            "**Quality-gate architecture:** [ADR 0019](../architecture/0019-mandatory-pre-emission-hardware-quality-gates.md)",
        )
    )
    text = replace_once(text, metadata_anchor, metadata, "core plan metadata")
    text = replace_once(
        text,
        "Freeze the language semantics that clock/reset domains, symbolic parameterization, native enums, reusable FSM/statecharts, reusable interfaces, analog equations, memories, external blocks, and automatic pipelines depend on.",
        "Freeze the language semantics that clock/reset domains, symbolic parameterization, native enums, reusable FSM/statecharts, parameterized multidimensional shaped values, reusable interfaces, expression materialization/naming, mandatory quality gates, analog equations, memories, external blocks, and automatic pipelines depend on.",
        "core plan goal",
    )

    section = """## Multidimensional shapes, target layouts, naming, and quality profiles

ADRs [0017](../architecture/0017-semantic-multidimensional-values-and-target-layouts.md), [0018](../architecture/0018-expression-materialization-and-semantic-naming.md), and [0019](../architecture/0019-mandatory-pre-emission-hardware-quality-gates.md), plus [`shaped-values-naming-quality-v0.3-plan.md`](shaped-values-naming-quality-v0.3-plan.md), define mandatory Increment 13 candidates.

Preferred shaped-value direction:

```scala
val matrix = in(Vec(SInt(width), rows, cols))
val element = matrix(row, col)
```

`Vec` has semantic rank/dimensions and structural storage. `Mem` remains explicit addressable storage. Portable Verilog uses a canonical flat carrier; future SystemVerilog defaults to unpacked multidimensional ports of packed elements. Flatten/index/reshape order is target independent.

Preferred emission configuration direction:

```scala
EmitOptions(
  temporaries = TemporaryPolicy.InlineSafe,
  naming = NamingPolicy.Semantic,
  checks = CheckProfile.Default
)
```

Safe single-use expressions inline without anonymous-wire chains. Required temporaries/state receive reasoned semantic names and expression-level source maps. Fast/Default/Release profiles all retain mandatory internal safety checks; external lint/synthesis/equivalence evidence increases by profile. Accidental latches, combinational loops, multiple drivers, hierarchy/scope, width/sign/shape, CDC/RDC, storage, effect, protocol, and target-legality errors prevent accepted emission.


"""
    text = insert_before(text, "## Native enums and FSM/statechart semantics", section, "core plan shaped section")

    text = replace_once(
        text,
        "- nested aggregate and vector rules;",
        "- nested aggregate and parameterized multidimensional `Vec` rank/dimension/index/flatten/reshape rules;",
        "core aggregate shape rule",
    )
    text = insert_after(
        text,
        "- parameterized schedules require finite envelopes.",
        "\n- shaped-value indexing/layout/storage and expression materialization/naming boundaries remain exact unless a separately verified transformation contract permits change.\n- every accepted emission passes the mandatory ADR 0019 internal and selected external quality gates.",
        "core pipeline shaped/quality relationship",
    )

    positive_anchor = "- directionless nested aggregates and vectors;"
    positive_extra = "\n" + "\n".join(
        (
            "- parameterized rank-one through rank-four `Vec`, exact shape/index/slice/flatten/reshape, signed elements, structural `Vec` versus `Mem`, and portable-Verilog/future-SystemVerilog layout candidates;",
            "- safe expression inlining, shared/observable/target-required materialization, semantic anonymous-register names, and expression-span source maps;",
            "- Fast/Default/Release check profiles and typed waiver metadata;",
        )
    )
    text = insert_after(text, positive_anchor, positive_extra, "core positive shaped matrix")

    negative_anchor = "- aggregate field mismatch or silent field loss;"
    negative_extra = "\n" + "\n".join(
        (
            "- runtime/zero/negative/overflowing shape dimension, rank/index/reshape mismatch, illegal target layout, implicit `Vec`/`Mem` conversion, or signed element loss during flattening;",
            "- forced unsafe inlining, traversal-counter-only normal names, duplicate semantic names, or unclassified required temporary;",
            "- disabled mandatory safety check, blanket waiver, accidental latch, combinational loop, multiple driver, undriven output, or unexpected memory inference;",
        )
    )
    text = insert_after(text, negative_anchor, negative_extra, "core negative shaped matrix")

    exit_anchor = "13. the machine-readable manifest, migration notes, diagnostics, and CI are green."
    exit_new = "\n".join(
        (
            "13. multidimensional shape/layout/storage and signed-element mappings are exact across portable Verilog and future SystemVerilog contracts;",
            "14. safe inlining removes avoidable anonymous-wire chains while required objects receive deterministic semantic names and source maps;",
            "15. mandatory check profiles, transactional emission, check inventory, and typed waiver boundaries are unambiguous;",
            "16. the machine-readable manifest, migration notes, diagnostics, and CI are green.",
        )
    )
    text = replace_once(text, exit_anchor, exit_new, "core exit criteria")

    ref_anchor = "- CIRCT ESI channels: <https://circt.llvm.org/docs/Dialects/ESI/>"
    ref_extra = "\n" + "\n".join(
        (
            "- Accellera SystemVerilog arrays and ports: <https://www.accellera.org/images/eda/vlog-pp/0438.html>",
            "- Yosys arrays and memories: <https://yosyshq.readthedocs.io/projects/yosys/en/stable/CHAPTER_Basics.html>",
            "- SpinalHDL design errors: <https://spinalhdl.github.io/SpinalDoc-RTD/master/SpinalHDL/Design%20errors/index.html>",
        )
    )
    text = insert_after(text, ref_anchor, ref_extra, "core plan references")
    path.write_text(text, encoding="utf-8")


def update_digital_adr() -> None:
    path = ROOT / "docs/architecture/0010-digital-verilog-open-source-verification.md"
    text = path.read_text(encoding="utf-8")
    section = """## Shaped values, readable HDL, and mandatory quality gates

[ADR 0017](0017-semantic-multidimensional-values-and-target-layouts.md) defines parameterized multidimensional `Vec` and target layouts. Portable Verilog uses canonical flat packed carriers; future SystemVerilog may use unpacked multidimensional ports of packed elements. `Vec` remains structural and `Mem` remains explicit addressable storage. Yosys evidence audits unexpected memory inference.

[ADR 0018](0018-expression-materialization-and-semantic-naming.md) keeps pure single-use expression DAG nodes inline where exact semantics permit. Required shared, signed-element, procedural, observable, or tool-limited values receive deterministic semantic names. Debug/materialized and inline profiles are equivalence-checked.

[ADR 0019](0019-mandatory-pre-emission-hardware-quality-gates.md) requires internal driver/latch/cycle/hierarchy/type/shape/domain/protocol/storage/effect verification before rendering, target reparse after rendering, and the selected Verilator/Icarus/Yosys evidence before accepting the emission.


"""
    text = insert_before(text, "## Signed values and staged loops", section, "digital ADR shaped section")
    text = insert_after(
        text,
        "- deterministic flattening of structured payloads and protocols at module boundaries;",
        "\n- canonical flat carriers for parameterized multidimensional shaped ports, signed element views, and shape/layout/storage sidecar manifests;\n- safe expression inlining, semantic state/temporary names, expression-span source maps, and materialization reports;",
        "digital ADR portable profile shaped bullets",
    )
    text = insert_after(
        text,
        "- report cells, memories, wires, and inferred latches;",
        "\n- audit combinational cycles, multiple drivers, structural-`Vec` unexpected memory inference, and materialization/layout profile contracts;",
        "digital ADR synthesis quality",
    )
    path.write_text(text, encoding="utf-8")


def update_digital_plan() -> None:
    path = ROOT / "docs/roadmap/digital-verilog-open-source-verification-plan.md"
    text = path.read_text(encoding="utf-8")
    text = insert_after(
        text,
        "- digital scalar/vector/aggregate ports and signals;",
        "\n- parameterized multidimensional structural `Vec` values with exact shape/index/layout metadata and explicit `Mem` storage contracts;",
        "digital classification shaped values",
    )
    text = insert_after(
        text,
        "- flattened aggregate and protocol fields with stable names;",
        "\n- flat packed carriers for shaped ports, deterministic row-major offsets, signed element views, structural-storage manifests, safe expression inlining, semantic state/temporary names, and expression-span source maps;",
        "digital required shaped constructs",
    )

    section = """## Shaped-value, materialization, and quality lowering

The portable profile follows ADRs [0017](../architecture/0017-semantic-multidimensional-values-and-target-layouts.md), [0018](../architecture/0018-expression-materialization-and-semantic-naming.md), and [0019](../architecture/0019-mandatory-pre-emission-hardware-quality-gates.md):

- parameterized multidimensional module ports flatten to one canonical packed carrier;
- signed elements use deterministic signed views rather than treating the whole carrier as one signed number;
- structural `Vec` and addressable `Mem` remain distinct in IR and synthesis reports;
- pure single-use expression trees inline when exact Verilog typing permits;
- shared/observable/target-required values materialize with stable semantic names and reason codes;
- anonymous registers derive names from source, sink, role, and stable origin;
- mandatory internal checks run before render, then the target is reparsed and independently linted/synthesized by the selected profile;
- failed partial output is diagnostic-only.

Future SystemVerilog defaults to unpacked multidimensional ports of packed elements and supports an explicit packed-dimensional layout. It must preserve the same Nodal row-major ABI and signed element semantics.


"""
    text = insert_before(text, "## Capability profiles", section, "digital plan shaped section")

    text = insert_after(
        text,
        "- typed scalar/vector/aggregate signal access through emitted metadata;",
        "\n- typed multidimensional access reconstructed from flat portable carriers, including signed elements, bounds, slices, reshape, and layout manifests;",
        "digital simulation shaped access",
    )
    text = insert_after(
        text,
        "7. records inferred latches, memories, cells, widths, and black boxes;",
        "\n8. audits combinational loops, multiple drivers, structural-`Vec` unexpected memory inference, and shaped-port/layout legality;",
        "digital Yosys quality",
    )
    text = text.replace("8. emits a normalized synthesized Verilog netlist;", "9. emits a normalized synthesized Verilog netlist;", 1)
    text = text.replace("9. optionally maps to selected FPGA/ASIC targets in later increments.", "10. optionally maps to selected FPGA/ASIC targets in later increments.", 1)

    text = insert_after(
        text,
        "- enum/FSM behavior before and after approved recoding, hierarchy flattening, minimization, or synthesis, aligned by semantic state/transition identity rather than raw encoded bits.",
        "\n- shaped flatten/unpack/index/reshape behavior and signed element views across portable/future layouts.\n- inline-safe versus readable/debug materialization profiles, aligned by typed expression origin rather than generated temporary names.",
        "digital equivalence shaped/materialization",
    )
    text = insert_after(
        text,
        "- legal enum/state encoding, one-hot invariants, allowed FSM transition relations, reset convergence, no unintended deadlock, nested/parallel completion, and bounded call-stack overflow/underflow safety.",
        "\n- multidimensional index bounds and flatten/reshape equivalence, structural-storage invariants, no accidental latch/combinational loop/multiple driver, and accepted-emission gate completeness.",
        "digital formal quality",
    )
    path.write_text(text, encoding="utf-8")


def update_json_surfaces() -> None:
    roadmap_dir = ROOT / "docs/roadmap"
    for path in sorted(roadmap_dir.glob("*.json")):
        data = json.loads(path.read_text(encoding="utf-8"))
        if "roadmap_revision" in data:
            data["roadmap_revision"] = "1.11"
        path.write_text(json.dumps(data, indent=2) + "\n", encoding="utf-8")

    core_path = roadmap_dir / "core-semantics-api-v0.3-surface.json"
    core = json.loads(core_path.read_text(encoding="utf-8"))
    core.setdefault("documents", {})["shaped_values_naming_quality_plan"] = (
        "docs/roadmap/shaped-values-naming-quality-v0.3-plan.md"
    )
    core["shaped_values_naming_quality"] = {
        "shape_architecture": "docs/architecture/0017-semantic-multidimensional-values-and-target-layouts.md",
        "naming_architecture": "docs/architecture/0018-expression-materialization-and-semantic-naming.md",
        "quality_architecture": "docs/architecture/0019-mandatory-pre-emission-hardware-quality-gates.md",
        "parameterized_multidimensional_Vec": True,
        "Vec_is_structural_and_Mem_is_addressable": True,
        "portable_verilog_layout": "flat packed carrier",
        "future_systemverilog_layout": "unpacked multidimensional array of packed elements",
        "safe_expression_inlining": True,
        "semantic_state_and_temporary_names": True,
        "mandatory_internal_checks": True,
        "transactional_accepted_emission": True,
        "formal_freeze_increment": 15,
    }
    evidence = core.setdefault("required_evidence", [])
    for item in (
        "shaped-value-layout-candidate-compilation",
        "inline-materialization-naming-equivalence",
        "mandatory-quality-gate-negative-fixtures",
    ):
        if item not in evidence:
            evidence.insert(1, item)
    core_path.write_text(json.dumps(core, indent=2) + "\n", encoding="utf-8")

    digital_path = roadmap_dir / "digital-backend-v0.3-surface.json"
    digital = json.loads(digital_path.read_text(encoding="utf-8"))
    digital["shaped_values_naming_quality"] = {
        "portable_verilog_multidimensional_port": "flat packed carrier",
        "future_systemverilog_default": "unpacked multidimensional port",
        "future_systemverilog_optional": "packed multidimensional layout",
        "signed_flat_elements": "signed element views",
        "unexpected_Vec_memory_inference_audited": True,
        "temporary_default": "InlineSafe",
        "traversal_counter_names_normal": False,
        "check_profiles": ["Fast", "Default", "Release"],
        "mandatory_safety_checks_disableable": False,
    }
    digital_path.write_text(json.dumps(digital, indent=2) + "\n", encoding="utf-8")

    signed_path = roadmap_dir / "signed-loop-api-v0.3-surface.json"
    signed = json.loads(signed_path.read_text(encoding="utf-8"))
    signed["multidimensional_signed_elements"] = {
        "semantic_element_type": "SInt",
        "portable_flat_carrier": "signless",
        "portable_element_access": "signed element view",
        "future_systemverilog_element": "packed signed element in unpacked dimensions",
        "whole_flat_carrier_is_one_signed_integer": False,
    }
    signed_path.write_text(json.dumps(signed, indent=2) + "\n", encoding="utf-8")

    enum_path = roadmap_dir / "enum-fsm-api-v0.3-surface.json"
    enum_surface = json.loads(enum_path.read_text(encoding="utf-8"))
    enum_surface["shaped_value_integration"] = {
        "enum_in_Vec": True,
        "parameterized_dimensions": True,
        "canonical_encoding_preserved_across_layout": True,
    }
    enum_path.write_text(json.dumps(enum_surface, indent=2) + "\n", encoding="utf-8")

    pass_path = roadmap_dir / "target-hdl-optimization-pass-v0.1-surface.json"
    target_pass = json.loads(pass_path.read_text(encoding="utf-8"))
    target_pass["shaped_values_naming_quality"] = {
        "preserve_shape_layout_storage_by_default": True,
        "preserve_expression_origin_and_source_spans": True,
        "declare_materialization_and_naming_effects": True,
        "mandatory_core_reverification": True,
        "cannot_disable_latch_cycle_driver_domain_checks": True,
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

    for fragment in (
        "**Revision:** 1.11",
        "## Multidimensional shaped-value and target-layout architecture",
        "## Expression materialization and semantic naming architecture",
        "## Mandatory pre-emission quality-gate architecture",
        "Increment 21 — Native parse, staged semantic verification, and pass pipeline",
        "Increment 43 — Analog arrays, shaped values, and elaboration-time generation",
        "Future SystemVerilog/SystemVerilog-AMS backend research gate",
        "No official reusable model/component library or production plugin is implemented by Increments 0-113.",
    ):
        if fragment not in text:
            raise SystemExit(f"roadmap lacks required fragment: {fragment}")

    for relative in (
        "docs/architecture/0017-semantic-multidimensional-values-and-target-layouts.md",
        "docs/architecture/0018-expression-materialization-and-semantic-naming.md",
        "docs/architecture/0019-mandatory-pre-emission-hardware-quality-gates.md",
        "docs/roadmap/shaped-values-naming-quality-v0.3-plan.md",
        "docs/roadmap/shaped-values-naming-quality-v0.3-surface.json",
    ):
        if not (ROOT / relative).is_file():
            raise SystemExit(f"missing durable shaped/naming/quality document: {relative}")

    print("shaped values, naming, and quality roadmap update validated")


def main() -> None:
    update_roadmap()
    update_architecture_index()
    update_core_semantics_plan()
    update_digital_adr()
    update_digital_plan()
    update_json_surfaces()
    validate()


if __name__ == "__main__":
    main()
