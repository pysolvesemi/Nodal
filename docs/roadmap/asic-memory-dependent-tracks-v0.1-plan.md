# Minimal ASIC/memory-interface Foundation extension and dependent tracks v0.1

**Status:** Normative roadmap extension  
**Date:** 2026-08-23  
**Architecture:** [ADR 0024](../architecture/0024-minimal-asic-advanced-io-readiness-boundary.md)  
**Foundation:** the main Nodal Foundation roadmap plus Foundation Increment 150 below

## Intent

This plan closes the minimum architecture gap required for future controller/PHY-class ASIC work without turning Foundation into an implementation program.

The main roadmap already states that any Foundation item appended after Foundation Increment 149 before barrier release belongs to Foundation. This document appends exactly one such item: Foundation Increment 150.

The implementation work is split into two dependent tracks, each restarting at Increment 1:

- **ASIC Productivity and Sign-off Track**;
- **Memory Interface IP and PHY Track**.

Both tracks remain implementation-blocked until the Foundation completion barrier opens. Research and feasibility work may proceed while blocked.

## Minimal Foundation addition

- [x] **Foundation Increment 150 — Minimal ASIC, source-synchronous I/O, power, DFT, and hard-macro architecture readiness**
  - Accept [ADR 0024](../architecture/0024-minimal-asic-advanced-io-readiness-boundary.md).
  - Freeze only stable semantic identities, capability metadata, source correlation, ownership boundaries, and adapter seams for source-synchronous/multi-edge I/O, ASIC timing/sign-off intent, power intent, DFT/DFx intent, and hard-macro multi-view IP identity.
  - Preserve logical identities for forwarded clocks/strobes, launch/capture edges, lanes/bits, programmable phase/delay/training state references, timing/power/test targets, mode/corner contexts, and external hard-IP views without freezing LPDDR-specific syntax.
  - Keep SDC, Liberty/SDF/SPEF, STA, UPF, scan/ATPG/MBIST, JTAG generation, PDK/custom-layout flows, DRC/LVS/extraction, DDR training engines, DFI, LPDDR protocol libraries, and hard-PHY implementation outside Foundation.
  - Record that future protocol/device standards are versioned dependent libraries/profiles rather than core Nodal semantics.
  - This increment is architecture-only; no compiler/backend/vendor implementation is required for its completion.

## Foundation barrier clarification

Foundation completion does **not** require implementation of ASIC sign-off flows, low-power insertion, DFT insertion, custom PHY circuits, process design kits, DDR training, DFI, LPDDR controllers, or LPDDR PHYs.

After Foundation is complete, the two tracks below may proceed independently except for their explicit local prerequisites.

---

# ASIC Productivity and Sign-off Track — blocked by Foundation

Numbering restarts at 1. This track implements generic ASIC productivity/sign-off capabilities and remains independent of any one memory standard.

- [ ] **ASIC Increment 1 — ASIC implementation-intent public API and capability gate**
  - Freeze project/mode/corner/constraint/power/test/hard-macro configuration APIs against Foundation semantic identities.
  - Define portable versus vendor-specific capability boundaries and explicit raw-tool escape policy.

- [ ] **ASIC Increment 2 — Portable ASIC Constraint IR and SDC projection**
  - Implement primary/generated/forwarded clocks, source-synchronous I/O timing, clock groups, uncertainty/jitter, min/max delays, explicit false/multicycle paths, case analysis, and stable semantic target resolution.
  - Generate deterministic SDC where supported; never infer unsafe exceptions heuristically.

- [ ] **ASIC Increment 3 — STA, MCMM, Liberty/SDF/SPEF adapters and normalized timing evidence**
  - Implement mode/corner/PVT matrices, Liberty/SDF/SPEF association, STA execution adapters, unconstrained/stale-target checks, critical-path source correlation, and normalized sign-off reports.

- [ ] **ASIC Increment 4 — Power-intent IR and UPF interoperability**
  - Implement power domains, supplies, legal states/transitions, isolation, level shifting, retention, always-on, DVFS associations, UPF projection/import where supported, and power-intent coverage diagnostics.

- [ ] **ASIC Increment 5 — DFT/DFx intent and production-test integration**
  - Implement typed scan/test modes, test overrides, MBIST/repair endpoints, JTAG/boundary-scan seams, loopback/calibration-test observability, test-mode timing/power constraints, and adapter contracts for external DFT tools.

- [ ] **ASIC Increment 6 — Hard-macro and process multi-view integration**
  - Bind one logical IP identity to applicable RTL/AMS/formal, Liberty, LEF, GDS/OASIS, SDF, SPEF, I/O-model, power, DFT, constraint, corner, and version views.
  - Add capability/profile selection, consistency checks, source correlation, provenance, and explicit unsupported-view diagnostics.

- [ ] **ASIC Increment 7 — Synthesis and physical-design handoff**
  - Add reproducible synthesis/implementation adapter contracts, hierarchy preservation/flattening policy, clock/power/test handoff, hard-macro placement interfaces, normalized area/power/timing results, and retained tool provenance.

- [ ] **ASIC Increment 8 — Timing/power/physical feedback and bounded optimization loop**
  - Map implementation feedback to Nodal modules, paths, pipelines, domains, interfaces, memories, and hard macros.
  - Support explicit bounded design-space exploration without silently mutating the accepted design or changing protocol/latency contracts.

- [ ] **ASIC Increment 9 — Sign-off artifact manifests, reproducibility, and ecosystem adapters**
  - Define complete implementation/sign-off manifests, tool/version/options hashes, constraint/power/DFT coverage, result normalization, cache/provenance rules, and optional commercial/open adapter profiles.

- [ ] **ASIC Increment 10 — Representative complex ASIC qualification gate**
  - Qualify a multi-clock, multi-power-domain subsystem containing hard macros, CDC/RDC, source-synchronous I/O, registers, memories, formal properties, SDC, UPF, DFT intent, and normalized synthesis/STA evidence.
  - Publish supported capability/limitations matrices without requiring one particular commercial toolchain.

---

# Memory Interface IP and PHY Track — blocked by Foundation

Numbering restarts at 1. This track develops reusable controller/PHY abstractions and versioned memory-standard libraries without moving protocol-specific semantics into Nodal core.

- [ ] **Memory Interface Increment 1 — Memory-interface architecture and public library/API gate**
  - Freeze the controller/PHY/hard-PHY/behavioral-model partition, source-synchronous lane abstractions, training/calibration contracts, firmware-visible state, versioned protocol-profile model, and library packaging rules.
  - Keep DFI/LPDDR versions explicit and independently versioned.

- [ ] **Memory Interface Increment 2 — Generic source-synchronous lane, edge, delay, and gearbox primitives**
  - Implement reusable forwarded-clock/strobe, rise/fall launch/capture, lane/bit grouping, serializer/deserializer, gearbox, phase/delay control, capture-window, and calibration-state primitives.
  - Keep them generic enough for memory and other source-synchronous interfaces.

- [ ] **Memory Interface Increment 3 — Versioned DFI interface and adapter libraries**
  - Implement selected DFI revisions as versioned Nodal libraries with typed roles, timing relationships, training/control/status channels, capability negotiation, adapters, monitors, and conformance fixtures.

- [ ] **Memory Interface Increment 4 — LPDDR protocol, timing, mode-register, refresh, and low-power libraries**
  - Implement selected LPDDR generations as versioned profiles covering legal commands, timings, initialization, mode registers, refresh/self-refresh/power-down, frequency-set behavior, and explicit unsupported-feature diagnostics.

- [ ] **Memory Interface Increment 5 — Controller building blocks and memory-side integration**
  - Implement reusable arbitration, bank/rank/channel scheduling, command queues, read/write ordering, refresh, QoS, low-power coordination, ECC/parity/CRC/RAS hooks, AXI/CHI or approved host adapters, and performance counters.

- [ ] **Memory Interface Increment 6 — Digital PHY utility and training architecture**
  - Implement training/calibration sequencers, per-lane/per-bit delay state, read/write leveling, eye/VREF search abstractions, impedance/ZQ coordination, frequency-state training, retraining, drift handling, firmware control/status, and deterministic diagnostics.

- [ ] **Memory Interface Increment 7 — Behavioral AMS PHY and memory-device models**
  - Build portable behavioral Verilog-AMS/Nodal models for PHY analog behavior and representative memory-device interactions with declared validity envelopes, PVT/noise/event behavior, and capability-limited simulation profiles.
  - Do not present behavioral models as custom-layout hard-PHY implementation.

- [ ] **Memory Interface Increment 8 — Process-specific hard-PHY wrapper and multi-view integration**
  - Integrate external/hardened PHY views through the ASIC hard-macro identity model, including digital utility/training handoff, pads, clocks/strobes, power/test modes, constraints, corners, simulation models, and sign-off collateral.

- [ ] **Memory Interface Increment 9 — Reusable VIP, protocol/training checks, coverage, and error injection**
  - Add native Nodal HVL plus generated UVM/UVM-MS projections for controller/DFI/PHY traffic, timing/protocol checks, training coverage, scoreboard/reference models, DRAM behavior, fault/error injection, and deterministic replay.

- [ ] **Memory Interface Increment 10 — LPDDR-class end-to-end qualification vertical slice**
  - Qualify a parameterized controller plus digital PHY utility/training subsystem and behavioral or external hard-PHY boundary across multiple clocks/resets/power modes, DFI, register control, training state, source-synchronous lanes, simulation, formal, synthesis, ASIC constraints, power intent, DFT intent, and scalability evidence.
  - Publish compile/runtime/memory/generated-RTL/source-map/regression metrics and a capability/limitations matrix.

## Scope boundary

These tracks are intentionally future implementation roadmaps. They do not change the current next Foundation implementation increment, and they do not require Nodal core to become an LPDDR-specific language.

A later memory standard, PHY technology, foundry flow, or vendor tool should normally extend these tracks/libraries/adapters rather than add another Foundation item. Foundation is reopened only for a genuinely missing target-neutral semantic identity that cannot be represented through ADR 0024 and the existing core architecture.
