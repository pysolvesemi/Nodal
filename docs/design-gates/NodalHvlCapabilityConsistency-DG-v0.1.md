# Nodal HVL Capability Consistency — DG v0.1

**Status:** Approved — roadmap and architecture refinement only
**Date:** 2026-09-05
**Scope:** Foundation Increment 147 roadmap refinement; future core/library packaging and HVL capability boundaries
**Approval:** User requested completion of the reviewed roadmap inconsistencies.
**Architecture:** [ADR 0027](../architecture/0027-hvl-execution-projection-capability-contract.md)

## Approved decisions

Retain two execution classes, independent profile capability sets, complete common-plus-typed-extension capture, independent live eligibility, one-way common/profile library dependencies, separate VTB/UVM IRs and implementations, common-subset-only parity, resolved dependency references and independent per-profile releases.

Generated-target restrictions cannot weaken otherwise-supported live Scala. Generated-only UVM and VTB extensions are not automatically live-callable. Actual simulator execution evidence is required before an executable generated profile is qualified; optional vendor breadth is distinct from that minimum.

## Change boundary

This approves documentation, machine-readable roadmap metadata and repository consistency checks. It changes no frozen Scala API, compiler semantics, runtime, generator, simulator adapter or VIP implementation. Foundation 147-149 stay unchecked. Completed Increment 152 evidence and unrelated roadmap completion states are preserved.

## Acceptance evidence

Run `python3 scripts/check_hvl_roadmap.py`, `python3 scripts/test_hvl_roadmap.py`, repository Markdown/link checks, contribution policy and required Core CI on the exact PR head. The checker exercises roadmap invariants, including mutation rejection; it does not claim that deferred HVL capability fixtures execute today. PR/check metadata carries immutable validation and merge identities.
