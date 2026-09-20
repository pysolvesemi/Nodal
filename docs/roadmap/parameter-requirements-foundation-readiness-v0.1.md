# Parameter requirements: Foundation readiness supplement v0.1

**Status:** Normative roadmap extension; architecture checkpoints open  
**Created:** 2026-09-14  
**Foundation:** [Nodal development TODO](nodal-development-todo.md)  
**Contract and owner sub-checklists:** [Parameter requirements plan v0.1](parameter-requirements-v0.1-plan.md)  
**Machine-readable candidate:** [Parameter requirements surface](parameter-requirements-v0.1-surface.json)  
**Barrier registry:** [Dependent-track gate](dependent-track-gate-v0.1.json)

## Ownership, milestones, and history

This is a normative extension of the existing Foundation roadmap, using its
registered-supplement convention. It adds open, owner-linked obligations for
parameter-dependent `require`; it does not allocate or renumber global
increments, reopen accepted historical evidence, or approve a new public API.
The inspected integration baseline is `f1e847a9fc205541f8921bfaafd3e6b4e3164838`
(main roadmap revision 1.51). The numbered Foundation track and milestone M0
are not synonyms: the track also owns later compiler/backend implementation
and qualification milestones.

The PRF checkpoints below are architecture/API readiness work in the spirit of
M0. Complete them through the existing owner or an explicitly versioned API/IR
amendment. PRC-001 through PRC-008 in the linked plan are separate, still-open
follow-up sub-checklists of existing Foundation compiler/backend owners; their
implementation and applicable PRV acceptance evidence are later work, not a
condition for approving an architecture-only checkpoint. They nevertheless
remain obligations of those numbered Foundation owners and of complete
Foundation closure. Historical completed owners retain their original evidence;
these newly registered follow-ups need new evidence of their own.

All applicable PRF prerequisites must close before the corresponding PRC work
starts. A PRC parent closes only after all its children, prerequisites, and
assigned acceptance evidence close. Complete Foundation readiness includes this
supplement and the linked owner obligations, as well as every previously
registered Foundation requirement. Do not unblock a dependent track by checking
only the old main-roadmap rows. Conversely, no implementation in a dependent
FPGA, Digital Verification, AMS Verification, SQ, debugging, or physical-design
track is made a prerequisite of Foundation completion. There is no reverse
Foundation-to-dependent-implementation cycle.

Exact public overloads, diagnostic codes, bridge/IR schemas, and profile spellings
remain candidates until the appropriate versioned design gate is approved.
Documenting this plan is neither gate approval nor implementation verification.
The architecture is deliberately limited to configuration requirements, not a
complete runtime or temporal assertion language and not a symbolic-width rewrite.

## Existing owners to extend

| Concern | Existing Foundation ownership and contracts |
| --- | --- |
| Staging, source surface, arithmetic | 13-15; ADR 0009; core-semantics API v0.3 and its approved gate |
| Construction, identities, source diagnostics | 16-17, 22, 26; construction/source-origin and diagnostic gates; naming closure 153-157 |
| Native requirement model and bridge | 19-21, 29-30; parameter model v1.0; bridge compatibility 89 |
| Symbolic structure and shapes | Existing parameter envelopes in 29, staged-loop and shaped-value contracts; no relaxation through `require` |
| Backend capability and preservation | 23, 65, 72, 76, 83-86; ADRs 0005, 0013, 0019 |
| Portable execution, synthesis and core formal | 66-67; pure-digital verification plan; not the later complete user-authored formal language |
| SystemVerilog | 99 design gate, then 130 implementation; separate from required Verilog-2005 |
| AMS and tool adapters | 75-76, 78, 87-88; separate Verilog-A/Verilog-AMS capability qualification |
| Native simulator interface seams | 148 and its adapter architecture; no dependency on implementing a full HVL runtime |

## Architecture-only checkpoints

- [ ] **PRF-001 — Source staging and `require` API amendment** (owners 13-15)
  - [ ] **PRF-001.a:** Freeze the concrete, parameter-dependent, and runtime cases and the active structural-scope rule in the linked source contract.
  - [ ] **PRF-001.b:** Approve the typed `Expr[Bool]` overload family, Scala Boolean forwarding and lazy host-message behavior, structured source capture, and helper/import resolution through positive and negative compile contracts.
  - [ ] **PRF-001.c:** Freeze early invalid-default diagnostics without default-only folding, and precise runtime-signal rejection without inventing a runtime assertion API.

- [ ] **PRF-002 — Typed requirement, provenance, and compatibility seam** (owners 16-22, 29-30, 89)
  - [ ] **PRF-002.a:** Specify the first-class backend-neutral operation, typed predicate DAG, scoped declaration identities, independent/shared dependency graph, message/source span, owner and stable requirement identity.
  - [ ] **PRF-002.b:** Specify structural/generate applicability, hierarchy rebinding, validation stages, backend capability requirements, parser/printer and bridge versioning, and mandatory native verification.
  - [ ] **PRF-002.c:** Reserve rejection contracts for forged/stale references, wrong types, cycles, illegal scope escape and runtime dependencies; existing snapshots or metadata-only inventories are not proof of enforcement.

- [ ] **PRF-003 — Proof classification and construction safety** (owners 21, 29-30 and existing width/shape/loop owners)
  - [ ] **PRF-003.a:** Distinguish universally true, universally false, mixed-validity, and unproved predicates; preserve default-result and proof-domain provenance separately, and forbid circular/self-assumption proofs.
  - [ ] **PRF-003.b:** Permit retention and emission of supported symbolic predicates without Cartesian enumeration or a whole-domain proof; retain type, arithmetic and scope checks.
  - [ ] **PRF-003.c:** Freeze the boundary between deferred configuration validation and checks needed to build a correct graph now. No default-driven structure, negative-width reliance, silent clamping, or implicit weakening of structural envelopes.

- [ ] **PRF-004 — Backend, synthesis, and formal contract** (owners 23, 65, 67, 72, 76, 99, 130)
  - [ ] **PRF-004.a:** Specify time-zero parameter-binding checks, deterministic names/source attribution, fail-unless-definitively-true behavior, and no changes to functional hardware or public ports/parameters.
  - [ ] **PRF-004.b:** Freeze separate SystemVerilog, portable Verilog, Verilog-A and Verilog-AMS capability decisions and explicit rejection for unsupported lowerings; preserve `Backend.Auto` behavior.
  - [ ] **PRF-004.c:** Specify the `ifndef SYNTHESIS` convention and its lack of synthesis-time enforcement, synthesis-adapter define verification, and a distinct formal path that never automatically assumes a requirement.

- [ ] **PRF-005 — Qualification and runner interface contracts** (owners 66-67, 75, 78, 87-88, 148)
  - [ ] **PRF-005.a:** Review PRV-001 through PRV-015 and the evidence schema, including one generated HDL artifact reused across override configurations and real assertion execution.
  - [ ] **PRF-005.b:** Define deterministic non-success results for simulation failures, assertion-enable canaries, startup/evaluation completion, macro/language-mode validation, adapter crash/timeout handling and unsupported capabilities.
  - [ ] **PRF-005.c:** Separate emission/IR evidence, actual simulator execution, Yosys hardware equivalence, formal handling and AMS qualification; approve interface obligations only, not tool pass claims.

- [ ] **PRF-006 — Readiness and ownership review** (Foundation readiness owner)
  - [ ] **PRF-006.a:** Approve required versioned API/IR/profile amendments with linked evidence for PRF-001 through PRF-005; reconcile them with the approved v0.3 and parameter-model gates without editing away history.
  - [ ] **PRF-006.b:** Verify that each PRC/PRV obligation has the existing owner and prerequisite recorded in the plan/surface/registry, and that no later backend implementation has accidentally become an M0 architecture gate.
  - [ ] **PRF-006.c:** Retain all existing increment identifiers and prerequisite edges; do not close Foundation or unblock dependent tracks until the complete registered barrier is satisfied.

## Change boundary

All new checkpoints remain unchecked. This documentation-only update contains no
Scala/C++/MLIR implementation, public API manifest change, workflow change, test
execution, or generated HDL change. Existing source and tests were inspected,
not modified or run. The intended HDL in the companion plan is illustrative,
not actual output from the current compiler.
