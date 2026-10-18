# Analog semantic lint design gate v0.1

**Increment:** 46
**Status:** Approved
**Scope:** core-library-boundary

## Contract and owners

Add read-only source-semantic analog lint to the existing transactional pipeline.
Existing mandatory topology, discipline, branch, dimension, numeric, event and
parameter verifiers remain authoritative errors. A lint must never repair a model,
reorient an equation, divide a residual, suppress a verifier, or alter emitted
mathematics. The public source API is unchanged.

A shared collector derives ordered issues from actual verified operations. Mapped
warnings reuse the diagnostic source/hierarchy context owner. The Scala process
mapper selects an actual error and its own context; an earlier warning must not
be reported as the cause of a failed compilation. Warning-only abnormal process
termination remains a process failure. Stable unprefixed legacy diagnostics remain
supported. Successful compiler stderr retains advisories.

The initial finite risk rules cover denominators whose nonzero value is unproved,
proven inactive monitor events, runtime value-discontinuous floor/ceiling functions,
and lifecycle filters outside the existing portable analysis registry. Constants
come from existing semantic evaluators, never symbolic defaults or mutable
initializers. A constant-positive above event is not classified unreachable, and a
nonpositive timer period remains a valid one-shot timer. Lint does not introduce
new argument evaluation or expand shared live-fill expressions. This finite risk
profile inspects numeric SSA, monitor arguments and complete resolved live graphs.
General non-live procedural control/initializer source bodies are not traversed by
this checkpoint; extending them requires shared owner enumeration rather than a
second field-dispatch/parser implementation.

## Structural-analysis boundary

F-046.A explicitly distinguishes source checks from F-135 whole-island analysis.
The current F-032 equation inventory retains authored strings and residual flags;
it is not typed executable equation SSA. F-135 owns logical hierarchy flattening,
residual construction, unknown classification, incidence matching, local component
and whole-island balance, singularity and initialization structure.

Current equation/initial-equation inventory presence must therefore produce an explicit analysis-unavailable
advisory rather than an invented equation count or a balanced/nonsingular claim.
Existing source ownership, residual-orientation and divided flags still validate.
This limitation is visible, and does not discharge the later F-135 obligations.
A minimum typed F-134 representation and F-135 structural-analysis checkpoint is
required before F-046 can claim genuine bounded imbalance/singularity detection;
the full later increment parents remain open beyond that dependency checkpoint.
The F-046 completion record must reconcile this boundary against each original
checklist category; merely emitting an advisory is not proof of structural safety.

## Alternatives and compatibility

Reuse mandatory semantic owners instead of duplicating their topology or unit
engines. Keep warnings out of executable IR and do not add persisted suppression
markers. An unordered lookup may support deduplication but may not choose issue
order. Repeated independent compiler invocations may each report their advisories;
a single pass reports each operation/category once.

Reject parsing authored equation strings into a second expression language, naive
equation/terminal counts, treating default values as parameter-envelope proofs,
and blanket warnings that claim a valid model is erroneous. No general waiver API,
DAE solver, simulator certification or numerical rewrite is added.

## Focused validation

Pair each new rule with a valid near-boundary control. Preserve known-zero division
errors, constant/dynamic parameter distinction, event initialization behavior,
existing topology/unit/branch negatives and hierarchy ownership. Check warning then
error process mapping, isolated source contexts, deterministic issue ordering,
unchanged IR/target bytes, sparse/deep topology and long source paths. Retain an
actual public Scala-to-IR-to-Verilog-A witness and explicit later tool handoff.

## Approval evidence

The owner's explicit instruction is to implement all unchecked Foundation items
starting at Increment 43 and complete only that track. This additive private
boundary implements F-046 under that authority and the 2026-10-18 prototype policy;
it does not claim the owner separately selected a signature. Focused local checks,
architecture review and verified integration remain required for completion.
