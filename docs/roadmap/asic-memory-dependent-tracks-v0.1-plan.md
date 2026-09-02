# Minimal ASIC, open physical-design, and memory-interface Foundation readiness v0.2

**Status:** Normative Foundation-only roadmap extension  
**Date:** 2026-08-23  
**Amended:** 2026-09-02  
**Roadmap revision:** 1.43  
**Architecture:** [ADR 0024](../architecture/0024-minimal-asic-advanced-io-readiness-boundary.md)  
**Foundation:** the main Nodal Foundation roadmap plus Foundation Increments 150-151 below  
**Implementation status:** deliberately outside this Foundation extension

## Intent

This document contains only the minimum Foundation architecture needed to avoid blocking future controller/PHY-class ASIC, low-power, and open-source physical-design work.

It does **not** define, schedule, authorize, or imply implementation increments for:

- ASIC synthesis, place-and-route, extraction, sign-off, or RTL-to-GDS execution;
- SDC, STA, MCMM, UPF, DFT insertion, PDK, DRC, LVS, or custom-layout flows;
- DFI or LPDDR libraries, controllers, training engines, behavioral devices, or hard PHYs;
- Yosys, OpenROAD, OpenROAD-flow-scripts, OpenLane, KLayout, commercial tools, or any specific foundry platform.

Future ASIC and memory-interface implementation may receive separately approved tracks whose numbering starts at 1. Low-power implementation is now defined by the separate [Low-Power Architecture and Power Intent Track](low-power-power-intent-v0.1-plan.md), also numbered from 1 and blocked until every Foundation increment is complete. This Foundation extension itself creates no dependent-track implementation work and does not change the Foundation barrier.

## Foundation TODO

- [x] **Foundation Increment 150 — Minimal ASIC, source-synchronous I/O, power, DFT, and hard-macro architecture readiness**
  - Accept [ADR 0024](../architecture/0024-minimal-asic-advanced-io-readiness-boundary.md).
  - Freeze only stable semantic identities, capability metadata, source correlation, ownership boundaries, and adapter seams for source-synchronous/multi-edge I/O, ASIC timing/sign-off intent, power intent, DFT/DFx intent, and hard-macro multi-view IP identity.
  - Preserve logical identities for forwarded clocks/strobes, launch/capture edges, lanes/bits, programmable phase/delay/training state references, timing/power/test targets, mode/corner contexts, and external hard-IP views without freezing LPDDR-specific syntax.
  - Keep SDC, Liberty/SDF/SPEF, STA, UPF, scan/ATPG/MBIST, JTAG generation, PDK/custom-layout flows, DRC/LVS/extraction, DDR training engines, DFI, LPDDR protocol libraries, and hard-PHY implementation outside Foundation.
  - Record that future protocol/device standards are versioned dependent libraries/profiles rather than core Nodal semantics.
  - This increment is architecture-only; no compiler/backend/vendor implementation is required for its completion.

- [x] **Foundation Increment 151 — Foundation-only scope correction and open RTL-to-GDS readiness seam**
  - Remove ASIC and memory-interface implementation checklists from this Foundation extension. Retain only architecture ownership, capability seams, and reserved future track names.
  - Clarify that Foundation supports a future open-source RTL-to-GDS flow through stable target identities and adapter contracts, not by implementing or selecting a physical-design toolchain.
  - Preserve stable identities and capability metadata for logical designs, generated RTL, clocks and constraints, technology/platform and PDK profiles, corners, standard-cell and hard-macro views, physical implementation stages, generated artifacts, normalized reports, source correlation, and provenance.
  - Recognize synthesis, floorplanning, power-grid construction, placement, clock-tree synthesis, routing, extraction, GDS/OASIS assembly, DRC, and LVS as future adapter stage identities without defining their algorithms, scripts, command lines, or tool-specific public APIs.
  - Keep Yosys, OpenROAD, OpenROAD-flow-scripts, OpenLane, KLayout, commercial tools, PDK packages, SDC emission, physical execution, and sign-off outside Foundation.
  - Reserve future **ASIC Productivity and Sign-off** and **Memory Interface IP and PHY** track names. Each may start at Increment 1 only after a separate roadmap is explicitly approved.
  - Route canonical Power Intent IR, UPF, reusable low-power primitives, power-aware verification, and technology mappings to the separately numbered Low-Power Architecture and Power Intent Track without adding them to Foundation.
  - This increment is architecture-only; no implementation TODO, compiler behavior, backend, plugin, library, tool adapter, or physical artifact is added.

## Architecture readiness boundary

### Source-synchronous and advanced-I/O identity

Foundation may identify forwarded clocks and strobes, launch/capture edge sets, source-synchronous data groups, lanes and bits, phase/delay/sampling-window references, training/calibration state references, and serialization relationships. It does not define DDR primitives, training algorithms, or an LPDDR-specific source API.

### ASIC timing, implementation, and sign-off identity

Foundation may identify clocks, constraints, timing targets, mode/corner/PVT contexts, implementation stages, physical views, reports, and evidence. It does not emit SDC, execute synthesis or STA, perform physical design, or claim timing closure.

### Open-source RTL-to-GDS integration seam

A future adapter may bind the same stable Nodal identities to an open-source flow consuming generated RTL, constraints, technology libraries, LEF/GDS views, and a selected platform/PDK profile. Foundation preserves:

- logical module, instance, port, net, clock, domain, constraint, register, memory, interface, and hard-macro identities;
- technology/platform, PDK, library, cell, macro, view, corner, and operating-mode identities;
- synthesis, floorplan, power-grid, placement, CTS, routing, extraction, stream-out, DRC, and LVS stage identities;
- netlist, DEF, LEF, Liberty, SDF, SPEF, GDS/OASIS, report, log, checkpoint, and verification-result artifact identities;
- capability negotiation, deterministic configuration/provenance hashes, normalized result categories, and source correlation.

These are architecture seams only. Foundation neither requires nor executes a particular open-source or commercial flow.

### Power-intent identity

Foundation may identify power domains, supplies, legal states and transitions, isolation, level shifting, retention, always-on intent, and operating-point associations. It does not implement UPF, insertion, power-aware simulation, or power analysis.

The [Low-Power Architecture and Power Intent Track](low-power-power-intent-v0.1-plan.md) owns those implementations after the Foundation barrier. Its canonical Power Intent IR binds to these stable Foundation identities; its completion is not required to close Foundation.

### DFT/DFx identity

Foundation may identify scan/test modes, overrides, MBIST/repair endpoints, JTAG/boundary-scan access, loopback/calibration-test observability, and test-specific timing/power targets. It does not implement scan insertion, ATPG, MBIST, boundary-scan generation, or ATE flows.

### Hard-macro and multi-view identity

One logical external or hard-IP identity may reference applicable RTL, AMS, formal, Liberty, LEF, GDS/OASIS, SDF, SPEF, I/O-model, constraint, power, DFT, corner, and version metadata. Foundation does not parse every format, create layouts, define PDKs, or execute DRC/LVS/extraction.

## Dependent-track registration

- **Low-Power Architecture and Power Intent Track** — a normative roadmap is defined in [`low-power-power-intent-v0.1-plan.md`](low-power-power-intent-v0.1-plan.md); numbering starts at Increment 1; implementation is blocked until every Foundation increment is complete.
- **ASIC Productivity and Sign-off Track** — future numbering starts at Increment 1; no TODO is defined here.
- **Memory Interface IP and PHY Track** — future numbering starts at Increment 1; no TODO is defined here.

The Low-Power registration contains no Foundation checkbox or authorization to begin implementation. The two reserved names contain no increment list, checkbox, prerequisite chain, implementation commitment, or authorization to start work. Their exact scope must be proposed and approved later in separate roadmap changes.

## Foundation barrier clarification

For this extension, Foundation completion requires only the architecture evidence recorded by Foundation Increments 150-151. It does not require an RTL-to-GDS run, generated GDS, a supported PDK, SDC/UPF/DFT implementation, a low-power primitive library, power-aware simulation, an LPDDR controller, a PHY, or any external-tool integration.

A new power-intent standard, memory standard, DFI revision, foundry, PDK, physical-design flow, or EDA tool should normally be handled by a future library, profile, plugin, or dependent-track roadmap. Foundation is reopened only for a genuinely missing target-neutral semantic identity that cannot be represented through ADR 0024 and this readiness boundary.
