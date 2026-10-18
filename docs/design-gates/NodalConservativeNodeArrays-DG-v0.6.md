# Nodal conservative node arrays design gate v0.6

**Status:** Approved
**Scope:** public-api
**Scope:** core-library-boundary
**Increment:** Foundation 43
**Decision date:** 2026-10-18

## Authority and relationship

The owner's standing request is:

> start implementing all foundation unchecked increment items in nodal repo from increment 43 and continue until all foundation checklists are completed. Don't start another track just complete foundation track.

This is the implementation-selected generated-local declaration extension of
[v0.4](NodalConservativeNodeArrays-DG-v0.4.md) topology and
[v0.5](NodalConservativeNodeArrays-DG-v0.5.md) endpoint relations. No separately
chosen signature or scope waiver is attributed to the owner. Original
F-043.B.1–B.3, the linked lightweight hierarchy amendment and
[ADR 0017](../architecture/0017-semantic-multidimensional-values-and-target-layouts.md)
require generated-object ownership, analog shapes and supported target layouts.
The preceding probe repair is published as
`d13f032ef75e02a8ed196924b76ffb3791058993`.

## Selected contract

Use existing `node(Electrical, dimensions...)` inside `hdlRange`. A captured
local conservative array is declared in its exact immediate generated region,
with one fresh physical root per elaborated occurrence. It is not a module-owned
array whose storage or connectivity is shared across generated occurrences.
Fixed and symbolic dimensions use the existing module-owned immutable Integer
proofs, remain positive over every legal parameter setting, and retain checked
signed64 cardinality. Parameter defaults do not replace symbolic dimensions.
No induction-dependent or dynamic dimensions are introduced. Empty generated
domains retain a checked source template but produce no physical occurrences;
malformed types, shapes, identities and literal bounds still reject.

Keep the declaration in the existing generated-region declaration inventory.
The source path preserves the existing module-authored declaration/name protocol,
including collision suffixes; native metadata carries the exact generated
region/induction identity. Region membership and SSA ancestry are proof;
matching strings alone cannot confer ownership. Roots and their existing
`at`/`row`/`plane`/`slice`/`flatten`/`reshape` views are visible in their region and
legal descendants. Module, sibling and detached uses of a generated declaration
reject. Re-entering a generated definition does not reuse a prior occurrence's
root. Boundary ports remain module-owned; this extension does not admit boundary
array declarations inside generation.

Existing scalar and two-sided array relations consume the same physical maps and
coverage proofs. Generated local/local, local/module-visible and local/child
relations preserve actual declaration and use scopes. Child-port coverage is
still complete once per exact child occurrence; it is not accumulated across
iterations. Existing explicit alias-proof and resource limits remain. No new
current-access capability, broadcasting, truncation or ideal-wire equation is
introduced. The source-free probe contract from the previous repair applies to
selected local endpoints without escaping their declaration scope.

## Shared production ownership

ConstructionSession's existing generated-region declarations and ancestry rules
own capture. Bridge declaration emission is shared between module and generated
roots, with independently checked membership and effective shapes. Existing
ConservativeConnectivity/ShapeModel own native roots, projection maps, relations
and alias/coverage proofs. Extend the current structural scope representation
only as needed to expose its actual local declarations; do not build another
registry, array index engine or hierarchy representation.

An optional typed generated-scope field in immutable declaration snapshots is
derived from that membership at freeze and cross-checked against it at the bridge.
This transport evidence is not a second mutable owner. Reproducibility and source
maps derive generated scope from the same declaration membership. Preserve
ordinary module artifacts where no generated declaration exists. Reused child definitions remain reusable; per-occurrence ownership does
not mean per-lane definition specialization. Native verification independently
rejects forged owner pairs, source paths, dimensions, roots and escaped views.

## Target layout and compatibility

Use the existing v0.3 flattened row-major ascending Electrical-vector layout,
`electrical [0:C-1] name`, inside the actual structural generate block.
v0.3 already superseded the older local unpacked-net spelling; do not restore it.
Its declaration and hygienic name are local to that block; descendant maps reference that exact carrier. Preserve
symbolic extents and existing target arithmetic-width checks. Reuse shared layout
and declaration helpers rather than adding a separate generated-array backend.

A scope containing a generated local array must select structural lowering even
when all selections are constant and the scope needs no explicit induction SSA
argument. This classification must not migrate unrelated legacy analog loops to
a narrower signed32 structural profile. Scope-local names must avoid actual
visible declarations and compiler-generated carriers; source correspondence must
retain authored identity separately from any allocated target name.

The actual generated dialect must pass existing independent target structural
validation with lexical declarations, indices and nested scopes. Extend that
parser only for legal syntax required by this layout, preserving forged-name,
rank and bound rejection. The language/compiler baseline remains generic
VAMS2023 and the established Verilog-A/AMS target profiles. No downstream
simulator or OpenVAF electrical-array compilation is claimed by native acceptance
or target reparse. Existing consumer limitations remain a separate F48 handoff.

## Alternatives and finite acceptance

Rejected alternatives: lifting every generated declaration into one global array;
flattening occurrence and element dimensions into shared module storage; host
unrolling; inferring ownership from names; allowing module/sibling handles because
their element types match; and a second generated-array layout or proof engine.
The selected design reuses exact scope and shape owners and emits compact
structural declarations without per-lane materialization.

Focused positives cover fixed/symbolic arrays, constant/direct-induction selection,
views/relations in legal descendants, independent siblings, reusable child
bodies, empty/singleton domains, source-free probes and hygienic target names.
Negatives cover zero/invalid/foreign dimensions, induction-dependent shapes,
forged membership/owner/source metadata, boundary declarations in generation,
escaped roots/views, malformed target names/indices and unresolved alias proofs.
Retain directly affected module-array, generated topology, relation and probe
predecessors with actual public Scala/IR/VA/AMS examples and deterministic replay.

Review authored-structure traversal and output size proportionately. Repeated
proofs/scans may remain quadratic; no universal complexity or speedup claim is
made. Focused checks, architecture review and exact-tree publication use the
prototype no-CI policy. This gate does not close F43 or silently reclassify its
remaining original checklist obligations.
