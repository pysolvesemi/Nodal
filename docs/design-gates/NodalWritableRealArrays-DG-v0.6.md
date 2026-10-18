# Nodal writable Real arrays design gate v0.6

**Status:** Approved
**Scope:** core-library-boundary
**Increment:** Foundation 43
**Decision date:** 2026-10-18

## Authority and relationship

The owner's standing Foundation implementation request is:

> start implementing all foundation unchecked increment items in nodal repo from increment 43 and continue until all foundation checklists are completed. Don't start another track just complete foundation track.

This implementation-selected extension changes no public signature. It follows
[v0.3](NodalWritableRealArrays-DG-v0.3.md) declaration initialization and
[conservative scopes v0.4](NodalConservativeNodeArrays-DG-v0.4.md), published as
`6098797e7d666636af49a5bc7cab5280703616b1`. No separate signature choice is
attributed to the owner. Required remaining Foundation work is not closed here.

## Contract

An initialized shaped Real persistent root in an actual generated analog procedure
has one distinct declaration per elaborated structural occurrence. Resolve the
same actual owned uniform static fill/projection graph, destination cardinality,
shape bounds, units and module-owned static scalar dependencies as v0.3. Parameter
references stay symbolic. A static fill's initializer is emitted at the structural
scope declaration, never as an analog/event entry assignment. Reused reusable
child definitions do not share these roots between occurrences.

Derive declaration lifetime from the caller's actual structural Generate and
procedure ancestry. A source IR attribute or textual owner is not authority.
Keep explicit analog-iteration and structural-occurrence rendering contexts;
legacy analog-loop preparation must continue rejecting declaration initialization
that it cannot place correctly. Lexical local initialization remains a copy at
each reached entry. Mutable reads, probes, time, analysis, stateful operators and
unsupported function dependencies cannot become static through fill/view wrappers.

Pure generated state with no child topology also needs structural declaration
layout when this initializer requires it. Select this on actual declaration needs,
not dummy topology or an unused induction argument. Existing successful direct
signed64/no-argument paths retain their behavior. Newly admitted initialized
structural storage uses the existing checked signed32 target scope profile and
must reject bounds/count/intermediate/final-update overflow transactionally.

The existing source procedural recorder must distinguish actual generated scopes
within one module. Ordinary module procedures retain their existing single-owner
contract; each generated region has at most one procedure. Derive the recorder
from actual module and generated-region identity, preserve lexical scope on child
construction/return, and keep sibling variable/control identities distinct after
snapshot remapping. Do not clone module definitions or permit escaped sibling
storage. Failed captures preserve existing rollback and session validity rules.

Reuse existing native shape, storage, definite assignment, placement and generated
ownership validators; target preparation does not replace them. Preserve authored
analog ordering after generate elaboration and one actual procedure execution per
source scope. Empty domains retain source validation even though no physical
occurrence is elaborated. Nested scopes preserve distinct identities.

## Architecture and alternatives

Extend the existing generated event-storage preparation seam with a derived
lifetime context. Reuse owned fill/projection analysis, declaration-only producer
peeling, target shape layout and replicated initializer grammar. No parallel
parameter/count/initializer engine or per-lane declaration expansion.

Rejected: delete all generated initializer rejection (wrong legacy lifetime);
assign at every analog evaluation (reinitializes state); share one module root
(alias between occurrences); add fake child instance (changes topology); eagerly
capture a live source (changes source effects); accept an asserted lifetime flag
(forged native input bypass).

## Compatibility and evidence

VAMS2023 §6.6.1 supplies per-generated-instance declaration scope and §6.9.1
preserves textual/unrolling order of analog blocks. Existing declaration-initializer
syntax contract remains, including checked replication count and static scalar
expression. Generic VAMS2023 language-structural target validation is distinct from
OpenVAF's already measured unsupported Real arrays; no external compile or dynamic
simulation success is claimed.

Required focused cases: static/symbolic/singleton/multiple/nested initialized roots,
static parameter fills and views, distinct generated storage identity, mixed child
and persistent state, declaration initialization versus local entry-copy, exact
repeat/default-gate/target reparse, empty capture, malformed/foreign ownership,
live initializer and zero/overflow rejection, unaffected legacy generated/scalar/
event/writable outputs. Final commands/results belong in existing implementation
notes. This does not close F43 or its other remaining compositions.
