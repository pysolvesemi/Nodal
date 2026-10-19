# NodalDigitalMaterialization-DG v0.1

**Status:** Approved
**Scope:** public-api and compiler contract
**API version:** frozen v0.3 naming/quality forms, additive verified intent transport
**Decision:** one native target-neutral materialization inventory over actual SSA uses

## Authority and compatibility

The owner's explicit all-unchecked-Foundation implementation request covers the
required F55 naming, sharing and materialization work. Exact request/publication
authority is recorded in [NodalDigitalExpressions-DG v0.1](NodalDigitalExpressions-DG-v0.1.md).
This is standing increment authority, not a claim that the owner separately chose
these internal report fields. Preserve `.named(String)`, `.keep(String)` and the
frozen InlineSafe/Readable/Debug policies. Do not add the earlier candidate
ToolFriendly spelling or change analog materialization policy.

## Exact contract

A named/keep digital expression is one typed alias with its actual input and the
same complete type. Names are portable identifier stems of at most 255 UTF-8
bytes; keep reasons are nonempty trimmed text of at most 1024 UTF-8 bytes without
ASCII control characters. Name is source intent; keep is an observability
boundary. Chained aliases retain separate identities. The existing source-name
owner allocates names and retains aliases; no second allocator or expression
registry is introduced. Unsupported root Real/Integer and capability values
remain with their existing owners. Proof facts may follow a checked alias without
changing evaluation or exact arithmetic.

The native owner counts actual operand occurrences, including repeated operands
of one consumer, projections, collection captures and yields. It does not count
only distinct consumers, infer iteration multiplicity, duplicate shared producers
or substitute parameter defaults. In fixed order, reasons are explicit-keep,
shared-value, source-name under Readable/Debug, and debug-anchor under Debug.
Any reason requires materialization; otherwise one-use pure results are inline
candidates and zero-use results are unused candidates. No node is deleted or
reassociated by this planning pass.

The immutable report records actual inputs and consumer/operand edges, types,
lexical ownership, aliases, policy, ordered reasons and a canonical SHA-256 hash.
It explicitly says target-neutral and target requirements pending. Recompute and
reject stale or forged inventories before replacement. Compute every module's
report before attaching any; repeated planning is idempotent. Existing native
value, collection, scope and capability verification remains mandatory.
Identity collision checks cover actual admitted digital/static-index producers and
operations with actual digital operands. Unrelated analog-only provenance does not
gain a digital naming policy. These checks establish current-graph consistency,
not authenticity after wholesale replacement of the original authored identities.

Limits are 65536 candidates, 1048576 visited operations/edges and 128 lexical
region levels, with bounded text and unique semantic identities. Expected query
cost is bounded by graph traversal plus deterministic sorting, without recursive
DAG expansion. This is an algorithmic bound, not measured whole-pipeline speedup.

Source-only reproducibility retains intent and says not-run. Successful native
normalization points to the hash-bound inventory in the normalized artifact.
Neither historical source inlined flags nor requested checks/waivers establish
applied optimization or executed checks. Historical evidence and existing analog
ownership remain intact.

## Target boundary and alternatives

ADR 0018 still requires exact typed-tree preservation. F65/72 must use this
inventory and add their own actual typing, signed-view, depth, procedural-boundary
and tool-legality reasons before emission. A target may strengthen an inline
candidate; it cannot silently discard keep/sharing obligations. This checkpoint
claims neither physical wire emission nor target optimization/equivalence results.

Rejected alternatives: duplicated source/native policy engines, all-node wires,
blind inlining, field-by-field aggregate reconstruction, alias-as-opcode naming
heuristics, stale-report overwrite and invented target receipts. Later source
name collision improvements remain with F153–157.

## Focused acceptance

Exercise typed aliases and proof forwarding, exact symbolic/aggregate/enum types,
source binder plus explicit name retention, source-only receipts, quality
transport, single/repeated/shared/unused uses, callback scopes, malformed intent,
stale same-count changed-consumer edges, transactional multi-module rejection,
canonical parse/reprint and a bounded shared DAG. Run affected value/collection,
source-name and reproducibility cases. All F55 closure claims depend on actual
integrated focused results, not this gate or isolated staged tests.
