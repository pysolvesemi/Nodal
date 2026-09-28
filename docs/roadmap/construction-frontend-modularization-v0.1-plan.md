# Construction frontend modularization and scalability baseline

**Revision:** 0.1
**Approved scheduling and scope:** 2026-09-24
**Status:** Planned; implementation, measurements and acceptance remain open.
**Track and identity:** Foundation Increment 160 (F-160).

## Authority and execution order

The owner approved a dedicated, bounded maintainability refactor after Increment
42 and before Increment 43, with broader performance and Rust work remaining in
96. This document records that roadmap decision; it does not start the refactor
or claim an implementation design gate has already been approved.

Read with the [main Foundation roadmap](nodal-development-todo.md), the
[lightweight hierarchy amendment](lightweight-hierarchy-iteration-v0.1-plan.md),
[CONTRIBUTING.md](../../CONTRIBUTING.md) and [AGENTS.md](../../AGENTS.md).

The main roadmap currently numbers Foundation through 159. Allocate the next
stable ID, **160**, without renumbering 43 or any other existing increment.
The execution order is:

```text
42: complete and accept hierarchy, including its required frontend integration
 -> 160: complete and accept construction frontend modularization
 -> 43: start arrays, generated-object ownership and target-visible generation

96: later comprehensive profiling, further optimization and Rust evaluation
```

This is an explicit supplementary prerequisite for existing **F-043.A/B** and
its implementation descendants: 43 implementation starts only after F-160 is
accepted. All original 43 obligations and other prerequisites remain required.
Research and planning may continue; no 43 implementation is authorized here.

F-160 depends on fully accepted 42, not merely a helper checkpoint, source merge
or passing targeted run. It does not depend on 43, 96, 159 or later simulators.
42 does not depend on 160: keep extractions genuinely necessary for 42 in 42,
then use its qualified behavior as the refactoring baseline. Do not move unfinished
42 features into 160 or make refactoring a workaround for an incomplete feature.

F-160 is part of the existing **all-Foundation completion barrier**, not a new
track or exception. This companion is the sole editable status owner of F-160
and its children. Main-file and other amendment checkboxes remain where they
are; the roadmap index links the documents instead of duplicating their states.
The dependency is a normative roadmap rule, not an executable scheduler change.

## Bounded scope and invariants

Review `core/scala/api/src/nodal/ElaborationConstructionKernel.scala` and the
accepted 42 integration before choosing extraction boundaries. Retain the Scala
frontend and existing MLIR/C++ compiler. Reduce coupling by responsibility, not
by an arbitrary file-length target or a requirement to split every helper.

Possible boundaries are the entry-point facade, session lifecycle, canonical
records and ownership, hierarchy/parameter binding, expression/type/dimension
facts, analog capture/connectivity and immutable snapshot assembly. These are
candidates, not a mandated class/file count or a new service/plugin framework.

Keep one construction transaction and one authoritative owner for each mutable
registry. Extracted components use narrow internal interfaces or explicit handles;
they must not duplicate module/expression maps, identity allocation or lifecycle
state. No new globals, reflection-based ownership, string-name identity, dependency
cycles or duplicate elaborator. Every extracted component stays on the real path.

Preserve the accepted public syntax, constructor/default/explicit-form behavior,
object evaluation count, caller/local/index identities, declaration and instance
ownership, parameter DAGs, physical dimensions, domains, conservative topology,
analog state/events/functions, validation ordering and failed-construction cleanup.
Preserve the bridge schema, authoritative MLIR semantics, supported capability
profile and deterministic output. This is not an API or semantic redesign.

No Rust migration, replacement compiler IR, general optimization framework,
new hierarchy API, parallel elaboration redesign or generation feature is in
scope. A later need for any such change requires its own measured rationale and
applicable approval. Transport difficulty is not performance evidence, and the
first extraction still needs a safe, byte-preserving edit of existing source.

## Foundation checklist

- [ ] **Foundation Increment 160 - Construction frontend modularization and scalability baseline**
  - [ ] **F-160.A - Architecture, baseline and bounded plan**
    - [ ] **F-160.A.1** Re-read live instructions, the accepted 42 source/tree and evidence, the complete 43 dependency boundary and actual construction consumers. Map responsibilities, dependency direction and each registry's sole owner. Select and justify a bounded production-used extraction set; preserve existing stable IDs and capability limits.
    - [ ] **F-160.A.2** Record the exact before-state fixtures, source/toolchain identities, internal interface contracts, invariants and work packages. Obtain any versioned design gate required by protected paths before implementation; roadmap approval is not automatic approval of an unspecified API/boundary change. Set measurement methodology and justified regression budgets before judging results.
  - [ ] **F-160.B - Production modularization**
    - [ ] **F-160.B.1** Separate the selected facade, lifecycle, records/ownership, hierarchy, expression, analog and snapshot responsibilities into focused components with narrow interfaces. Keep a single coordinated transaction and canonical registry ownership; no duplicate state, circular imports or uncalled extraction facades. Do not require every suggested component to become a separate file.
    - [ ] **F-160.B.2** Integrate every extracted component into the actual construction path, preserving public entry points, ordering, source provenance and the existing bridge. Remove superseded duplicate internal paths only with parity evidence. Publish coherent behavior-preserving checkpoints, not a broken multi-file intermediate state.
  - [ ] **F-160.C - Correctness, failure safety and predecessor regression**
    - [ ] **F-160.C.1** Exercise accepted 42 constructors/defaults, symbolic/explicit overrides, direct child ports, conservative connect/operator parity and fixed replication, plus relevant existing domain, expression and analog operator/function cases. Preserve type/unit/static-effect, duplicate and foreign/internal/detached ownership rejection and source-located diagnostics.
    - [ ] **F-160.C.2** Test failed-construction cleanup and successful fresh elaboration after failure, constructor/factory evaluation counts, nested/isolated sessions and supported separate-compilation behavior. Preserve lifecycle and transaction isolation without introducing global state or claiming unsupported parallelism.
  - [ ] **F-160.D - Differential evidence and applicable validation**
    - [ ] **F-160.D.1** Run identical public Scala fixtures through the retained qualified baseline and the refactored candidate with the same pinned tools/options. Compare construction records, normalized IR, generated HDL and diagnostic identities. Require byte-identical deterministic IR/HDL and stable semantic/source paths; investigate every difference rather than normalize it away or replace expected outputs to pass.
    - [ ] **F-160.D.2** Retain separate baseline/candidate source, tool, command, result and artifact hashes. Execute affected public/frontend/bridge/native and available target checks, preserving their original strength. A required unavailable lane remains blocked; internal reparse or old-head receipts are not fresh execution. Reuse existing per-profile validation owners without a reverse dependency on entire later tool-integration increments.
  - [ ] **F-160.E - Maintainability and output review.** Review the selected boundary map, coupling and production call paths. Show that hierarchy/ownership changes can be reasoned about without unrelated operator logic, and that no duplicate semantic model or registry was introduced. Require output parity, not new HDL optimization or a file-size quota; separately approve any intentional externally visible change instead of hiding it in the refactor.
  - [ ] **F-160.F - Proportionate scalability and compatibility baseline**
    - [ ] **F-160.F.1** Measure representative small and large deep/wide/repeated hierarchies, shared expression DAGs and symbolic parameter combinations before and after. Separate Scala compilation/startup, cold/warm construction, snapshot/serialization, bridge/native and external-tool time; retain allocation/GC, peak memory, environment and repeated-run variability where available. Keep workloads semantically identical and record resource-limited cases honestly.
    - [ ] **F-160.F.2** Compare results against the predeclared methodology/budgets; resolve unexplained material regressions or obtain an explicit justified review decision. Require no arbitrary speedup and make no frontier-performance claim. Hand the workload definitions and baseline to 96; completing the full 10K/100K/1M program or a Rust prototype is not a prerequisite of 160 or 43.
  - [ ] **F-160.G - Qualification, evidence and acceptance**
    - [ ] **F-160.G.1** Complete affected targeted-first and full applicable qualification, review, verified integration and any separate accepted-evidence closure under current contribution policy. Keep historical accepted records immutable, update required manifest references without creating a second status ledger, and keep this parent open until all children and closure obligations are satisfied.
    - [ ] **F-160.G.2** Deliver the boundary/dependency documentation, before/after validation and measurement record, reproduction commands, capability limits and actual public Scala/unchanged generated-HDL demonstration. Record verified merge/source/tree identities and explicitly establish that 43's added prerequisite is satisfied; a file split or a green helper test alone cannot close 160.

All checkboxes above start open. Adding this plan does not mark any part of 42,
43, 96 or 160 complete and does not change historical acceptance.

## Ownership split with Increment 96

The initial maintainability extraction and its bounded regression baseline have
one owner: **F-160**. The existing **F-096.B.2.2** retains its stable ID and open
state but now covers follow-on profile-guided refinement, consuming the accepted
160 boundaries and measurements rather than repeating the extraction checklist.

96 keeps the comprehensive 10K/100K/1M declaration benchmark families, broader
algorithm/data-layout/parallel-service investigation, native-boundary overhead
and optional Rust comparison/adoption decision under F-096.B.2.1-.4. Its work
must still prove correctness parity; a faster reduced-scope result is not an
improvement. Neither 42 nor 43 waits for that whole performance study or a Rust
decision. No speed advantage is inferred from implementation language alone.

## Publication and implementation limits

This is the owner's authorized documentation-only update on the existing
Increment 42 branch. It adds the later work and dependency without starting a
new increment, creating a branch, changing compiler code or running CI. The
main roadmap's existing numbered entries, historical evidence and states remain
unchanged; this linked plan supplies the new F-160 entry and 43 prerequisite.
`AGENTS.md`, language design gates and existing qualification requirements are
unchanged. The CI waiver applies only to this roadmap publication, not to future
refactoring implementation or closure. This documentation update does not affect
generated Verilog-* and is not an executed refactoring demonstration.
