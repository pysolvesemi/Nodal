# ADR 0027: Separate HVL execution eligibility from generated-profile capabilities

- **Status:** Accepted (roadmap and architecture refinement only)
- **Date:** 2026-09-05
- **Amends:** ADR 0023 and ADR 0025
- **Scope:** HVL capability boundaries, complete capture, future library packaging, generated IR ownership, dependency metadata and release qualification

## Decision

Live Nodal HVL remains the default host-side Scala experience. Generated testbench restrictions never weaken otherwise-supported live operations. This is not a promise that live execution implements every generated-only Verilog-TB or UVM methodology operation.

A live caller may use a captured component only when all its required common and profile-specific operations have qualified live implementations. Similarly, successful capture does not imply Verilog-TB, UVM, UVM-MS or standalone eligibility. Rejection of one profile does not invalidate a different qualified profile.

The complete captured program is **common Verification Semantic IR plus declared typed profile-extension operations**. Extensions are versioned first-class operations with types, symbols, effects, scheduling requirements, source spans, stable identities, verifiers and serialization/round-trip tests. Portable Core stays target-neutral. Unknown extensions may be preserved for inspection, but execution and lowering fail without matching qualified support. Opaque callbacks, generated source strings, and a trace from one live run are not substitutes for complete capture.

## Library and IR boundaries

Common transaction, protocol, check, scoreboard, coverage and result semantics do not depend on profile libraries. Independent VTB and UVM libraries depend on the common layer. Explicit target-specific wrappers and single-profile VIP packages are valid; duplicating common intent is discouraged, not solved through a universal base implementation class.

VTB lowers through Procedural HDL Testbench IR. UVM lowers through Verification SystemVerilog IR. Neither lowers through or inherits from the other. Shared low-level utilities are allowed when they introduce no target-implementation dependency. UVM-MS may compose qualified UVM capabilities plus AMS capabilities without inheriting procedural Verilog limits.

## Qualification examples

These are required future conformance cases, not implemented language APIs:

| Case | Live | VTB | UVM |
| --- | --- | --- | --- |
| Host Scala reference model using an external library | Qualified live fixture | Rejected | Rejected |
| Common deterministic transaction and check | Qualified | Qualified | Qualified |
| Captured UVM factory override without a live implementation | Rejected | Rejected | Qualified |
| Captured VTB-specific module/task hook without another implementation | Rejected | Qualified | Rejected |

Every accepted mode still depends on the selected simulator's declared capabilities. Add negative cases for a live call to the UVM-only component, extension loss in serialization, unsupported methods hidden behind a wrapper, reactive-test replay substitution, and accidental common-to-profile dependencies.

## Replay and parity

Precomputation is legal only when it preserves the selected test's semantics. Future DUT-dependent decisions must remain reactive in the target or an explicitly declared companion runtime. A recorded trace is an explicit replay artifact, not proof that the original reactive test is portable to different DUT behavior.

XPAR compares the explicit shared semantic intersection for each qualified profile pair. A target-specific feature outside that intersection is inapplicable, not passed and not a parity failure. Claiming a shared capability and then failing its comparison is a failure; an exclusion cannot hide a supported-case regression.

## Dependencies and releases

The [HVL roadmap](../roadmap/nodal-hvl-simulation-v0.1-plan.md) and [surface](../roadmap/nodal-hvl-simulation-v0.1-surface.json) define resolved dependency nodes and separate gates for live, analog-live, mixed-signal-live, capture, VTB, UVM, Verilog-AMS TB, open AMS harness, UVM-MS and profile-pair parity.

Commercial breadth and aggregate track completion do not block open live, VTB or open-harness releases. Claiming an executable UVM/Verilog-AMS/UVM-MS release still requires actual simulator qualification for that selected profile. Source emission, a skipped licensed run, or a missing tool never counts as execution evidence.

## Foundation and history

All dependent implementation remains blocked until every Foundation increment is accepted with evidence. Foundation 147-149 remain responsible for detailed API and semantic acceptance, including complete extension capture and legality fixtures; this amendment does not close them. Existing Increment 152 evidence remains historical proof of its earlier architecture scope, not proof of newly added implementation.

The [approved consistency design gate](../design-gates/NodalHvlCapabilityConsistency-DG-v0.1.md) records this roadmap-only decision. The repository checker validates documentation/metadata consistency, not future HVL functionality.
