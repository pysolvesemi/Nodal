# ADR 0024 — Minimal ASIC and advanced-I/O readiness boundary

**Status:** Accepted  
**Date:** 2026-08-23

## Context

Nodal's Foundation already owns the language/compiler semantics required for complex digital and mixed-signal hardware: typed values, hierarchy, symbolic parameters, memories, protocols, clock/reset domains, CDC/RDC, interfaces and inout, analog/AMS semantics, registers, formal hooks, plugins, and verification architecture.

Research against controller/PHY-class IP such as LPDDR exposed a smaller set of architecture seams that must be visible before later ASIC and memory-interface work begins:

- source-synchronous and multi-edge I/O relationships;
- ASIC timing/sign-off intent;
- low-power intent;
- DFT/DFx intent;
- process-specific hard-macro multi-view identity.

Implementing SDC, UPF, DFT insertion, custom PHY circuits, PDK flows, training engines, or LPDDR/DFI protocol libraries inside Foundation would make Foundation unnecessarily large and delay useful implementation work. These capabilities are better delivered by dependent tracks after the semantic identities and extension boundaries are stable.

## Decision

Foundation owns only the **minimal target-neutral readiness boundary** required to prevent later dependent tracks from depending on generated hierarchy strings, vendor syntax, or incompatible one-off metadata.

### 1. Source-synchronous and multi-edge identity seam

Nodal must be able to assign stable semantic identities and capability metadata to:

- forwarded clocks and strobes;
- launch/capture edge sets, including rise/fall and multi-edge relationships;
- source-synchronous data groups, byte lanes, and per-bit members;
- phase, delay, sampling-window, and training/calibration state references;
- gear/serialization relationships where a dependent implementation needs them.

Foundation does **not** freeze an LPDDR-specific API, implement DDR primitives, infer training, or define a particular PHY architecture.

### 2. ASIC implementation and sign-off intent seam

Nodal must preserve stable semantic targets and normalized evidence identities for future:

- primary/generated/forwarded clocks and I/O timing;
- timing exceptions and mode/corner contexts;
- multi-mode/multi-corner and PVT analysis;
- synthesis, STA, parasitic, and sign-off reports.

Foundation does **not** implement SDC generation, Liberty/SDF/SPEF ingestion, synthesis, STA, place-and-route, extraction, or timing closure.

### 3. Power-intent seam

Nodal must preserve stable identities and capability metadata for future:

- power domains and supply relationships;
- legal power states and transitions;
- isolation, level shifting, retention, and always-on intent;
- DVFS or operating-point association.

Foundation does **not** implement UPF generation/import, power-aware simulation, isolation/retention insertion, or power analysis.

### 4. DFT/DFx seam

Nodal must preserve stable semantic identities for future:

- scan/test modes and test overrides;
- memory BIST/repair endpoints;
- JTAG/boundary-scan access;
- loopback, calibration-test, and production-test observability;
- test-specific clock/reset/power/timing intent.

Foundation does **not** implement scan insertion, ATPG, MBIST generation, boundary-scan generation, or ATE flows.

### 5. Hard-macro and multi-view IP identity seam

A logical external/hard IP block may expose multiple tool-specific views while retaining one stable Nodal identity. Future adapters may associate that identity with RTL/AMS/formal models, Liberty, LEF, GDS/OASIS, SDF, SPEF, IBIS or equivalent I/O models, constraints, power intent, DFT collateral, and corner/version metadata.

Foundation does **not** parse every format, create custom analog layout, run DRC/LVS/extraction, or define a PDK.

## Dependent implementation tracks

Actual implementation is deliberately deferred to independently numbered tracks:

1. **ASIC Productivity and Sign-off Track**, numbering from Increment 1.
2. **Memory Interface IP and PHY Track**, numbering from Increment 1.

Memory standards such as DFI and LPDDR remain versioned libraries/profiles in the dependent track rather than core language semantics.

## Barrier policy

This ADR is fulfilled in Foundation by recording and validating these identities, ownership boundaries, capability seams, and track contracts. No SDC, UPF, DFT, custom-layout, PHY-training, or LPDDR implementation is required to release the Foundation barrier.

If later research discovers a genuinely new **core semantic identity** that cannot be expressed through these seams, that identity returns to Foundation. Vendor implementations, protocol libraries, device-specific algorithms, and sign-off adapters do not.

## Consequences

- Foundation remains small and can complete without waiting for full ASIC or LPDDR implementation.
- Complex controller/PHY development can evolve in dependent tracks without hard-coding vendor syntax into Nodal core.
- Source, constraint, power, DFT, hard-macro, verification, and generated-artifact correlation can share stable semantic identities.
- A behavioral AMS PHY model is not confused with a process-specific hardened PHY implementation.
- Future ASIC and memory-interface work may add APIs through their own design gates without reopening the core architecture unless a new core semantic category is truly required.
