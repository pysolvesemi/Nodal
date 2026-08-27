# Nodal natures and disciplines design gate v1.0

**Revision:** v1.0
**Status:** Approved
**Scope:** compiler-ir
**Public API:** unchanged at 0.3
**Approved authority:** standing Nodal increment implementation and merge authorization

## Decision

Increment 27 adds the first complete normalized declaration model for analog
natures and disciplines without assigning source-language or target-HDL
spelling to the public API.

A `nodal.nature` is a top-level symbol containing canonical unit text, one
access-function identifier, and a positive finite absolute tolerance. A
`nodal.discipline` is a top-level symbol containing a `continuous` or
`discrete` domain, one potential-nature reference, and an optional distinct
flow-nature reference. Missing flow denotes signal-flow semantics; potential
plus flow denotes conservative semantics.

`nodal.nature_import` and `nodal.discipline_import` are normalized local aliases.
Each alias carries canonical source provenance and a lowercase SHA-256 of the
resolved external definition. Import chains resolve to a direct declaration;
missing targets, wrong-kind targets, and cycles fail verification.

## Compatibility contract

- Nature identity is nominal after import resolution.
- Discipline aliases are transparent.
- Two discipline declarations are compatible only when their domains match,
  their canonical potential natures match, and either both omit flow or their
  canonical flow natures match.
- Equal unit strings or tolerances do not make distinct nature declarations
  compatible.
- Access-function identifiers are globally unique across direct nature
  declarations in one normalized MLIR module.
- Stable `NODAL-NATURE-*` and `NODAL-DISCIPLINE-*` diagnostics cover declaration,
  unit, access, tolerance, domain, association, import, provenance, and cycle
  failures.

## Explicitly deferred

- Scala public syntax, standard-library packaging, and external package loading;
- binding terminals, nodes, nets, and branches to declarations (Increment 28);
- unit-aware parameters, constants, and ranges (Increment 29);
- potential/flow access evaluation (Increment 31) and contribution semantics
  (Increment 32);
- Verilog-A/Verilog-AMS declaration emission and backend capability support;
- nature inheritance, derived natures, connect rules, and simulator-specific
  tolerance policy.

The existing Increment 25 RC vertical slice remains unchanged and fail-closed
for these new declaration operations until a later backend increment explicitly
owns their emission.
