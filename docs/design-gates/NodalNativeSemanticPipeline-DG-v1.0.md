# Nodal native semantic pipeline design gate v1.0

**Revision:** v1.0
**Status:** Approved
**Scope:** compiler-ir
**Public API:** unchanged at 0.3
**Approved authority:** standing Nodal increment implementation and merge authorization

## Decision

Increment 21 makes parsed Nodal MLIR pass through mandatory native semantic
gates before it can become accepted normalized IR. The gates are target-neutral,
registered with MLIR, deterministic, and incapable of being disabled by a
plugin or backend. This increment does not lower to CIRCT or emit HDL.

## Mandatory stage order

The default and release gates run the following stages in order:

1. construction closure and bridge-version compatibility;
2. driver identity plus assignment coverage;
3. latch freedom;
4. combinational-cycle freedom;
5. hierarchy resolution and recursion freedom;
6. finite-width, signedness, shape, layout, and storage consistency;
7. parameter bindings, structural generate bounds, and bounded loop progress;
8. enum identity, FSM references, priority uniqueness, and reachability;
9. clock/reset ownership, domain bindings, and CDC/RDC endpoint resolution;
10. Interface definitions, roles, members, protocol identity, and pipeline
    contracts;
11. memory latency/ordering and external-effect contracts;
12. conservative topology and explicit mixed-signal bridge requirements;
13. selected target-capability compatibility.

`nodal-gate-fast` is an explicit development subset covering construction,
hierarchy, types, parameters, domains, and capabilities. It is never an
acceptable release substitute. `nodal-gate-default` and `nodal-gate-release`
run all mandatory stages.

## Transaction contract

Every named gate clones its input module, runs all selected verifier passes and
the normalization pass on the clone, and commits the clone only after complete
success. A parse, verifier, pass, or normalization failure leaves the caller's
module unchanged.

`PipelineSession` retains the most recent accepted normalized module. Rejecting
a later candidate cannot replace, partially mutate, or invalidate that accepted
state. Analyses are preserved by read-only verifier passes, invalidated by the
normalization mutation, and the MLIR verifier runs after each pass.

## Normalized IR

Successful gates add deterministic module attributes recording pipeline
version, profile, stage inventory, and normalization version. `nodalc` prints
that accepted IR through its ordinary MLIR printer. The named pipelines are
available to command-line, lit, and FileCheck fixtures through
`--pass-pipeline=builtin.module(nodal-gate-...)`.

## Verification boundary

The stage implementation checks every semantic category represented by the
Increment 19 dialect and the complete retained inventories emitted by Increment
20. Where executable expression, assignment, memory, or analog-equation
operations are intentionally deferred, the bridge must carry explicit analysis
inventory or a stage guard; absence is not interpreted as proof of validity.

Cross-layer diagnostic remapping to final Scala-facing codes and richer
hierarchy/index paths remains Increment 22. CIRCT conversion, backend
legalization, target reparse, and HDL emission remain later increments.
