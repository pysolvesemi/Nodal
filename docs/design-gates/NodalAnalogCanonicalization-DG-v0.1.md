# Analog canonicalization design gate v0.1

**Increment:** 45
**Status:** Approved
**Scope:** core-library-boundary

## Contract and ownership

Add a target-neutral, transactional analog canonicalization pass to the existing
Increment 21 native pipeline. Reuse Increment 30/38 numeric validation/folding,
Increment 32 authored equation/contribution records and the existing conservative
access/selector normalizers. No causal equation orientation, residual elimination,
new division, contribution reordering, topology change or state movement is allowed.
The source API does not change.

The finite scalar profile uses same-block, ordered-operand, exact typed expression
equivalence and same-type Real double negation. Dynamic Integer double negation
remains unchanged because an intermediate negation may overflow its target range. It does not reassociate floating-point
arithmetic, cancel expressions, simplify `x/x`, assume nonzero symbolic defaults,
or use real addition-by-zero rules that change signed zero. Dynamic simulator
observations, conservative accesses, user calls, state, noise, transfer/waveform
operators and procedural consumers remain outside the rewrite effect boundary.
An operation is never made pure merely because it has no SSA users.

Native validation precedes rewriting, including invalid unused arithmetic. The
existing shared numeric owner must reject a statically proved zero denominator
with a dynamic numerator, while retaining authored division by a genuinely symbolic
runtime denominator. This does not authorize introducing division by such a value.

## Provenance and residual preservation

Retain a deterministic bounded rewrite record containing original local operation
identity, type, source metadata/location, operand identities, rule and replacement.
Do not serialize recursively expanded expression trees. Repeated passes must reach
a fixed point without duplicating provenance. Only explicit source-location/path
metadata may be omitted from expression-equivalence comparison; semantic owner,
dimension, continuity and other semantic attributes remain significant.

The existing `nodal.bridge.analog_semantics` inventory is retained unchanged.
Validate its frozen record schema, identities, ownership/applicability and residual
flags independently, without parsing rendered expression strings. These records
are authored-side evidence, not executable typed equation SSA. Native validation
cannot infer expression dimensions from those strings. Later DAE/equation lowering
remains with its original roadmap owners.

## Finite dead-declaration extension to Increment 41

Increment 41's default function representation and output remain unchanged unless
the new pass proves a named immutable local dead. The first supported case is a
local with no SSA uses, initialized directly by a verified finite Real/Integer
literal of exactly the same type. Preserve the original local, initializer, name,
source metadata and location in native IR; add a derived true marker
`nodal.canonical.dead_local`. Target emission may omit its declaration and literal
initialization only after independently rechecking the proof and original body.

False/non-Boolean markers, wrong operation kinds, used locals, mismatched types,
nonliteral or call-fed initializers reject. Original invalid names and expressions
remain errors even when a local would otherwise be dead. The pass does not remove
function definitions, inputs, calls, mutable variables or target-visible parameters.
This is the narrowly authorized F-045 extension to F-041's former no-body-rewrite
boundary; it does not authorize interprocedural folding, call CSE or inlining.

## Alternatives and focused checks

Reject generic DCE/CSE over the whole dialect: target-visible parameters, authored
storage declaration order, textual read inventories and effectful unused operations
make that unsafe. Reuse shared numeric facts rather than create another evaluator.
A query/pass-scoped facts cache may avoid repeated traversal of shared expression
DAGs, but must not survive mutation or change diagnostics. Backend inlining can
still grow textual output; SSA CSE alone is not an HDL size improvement claim.

Check exact CSE and double-negation positives, distinct operand/type/owner/scope
negatives, preserved residual/contribution multiplicity, state/query/call retention,
known-zero divisor rejection, literal-local proof and forged-marker rejection.
Check failed-transaction input equality, deterministic fixed points, bounded shared
DAG/chain behavior, actual public Scala to native to VA witnesses and directly
affected predecessors. Compiler structure and independent compilation are distinct
from numerical simulation. No CI or exhaustive qualification is required.

## Approval basis

The owner's explicit request covers implementation of all unchecked Foundation
increments from 43 and subsequent public commits/pushes. F-045 specifically requires
safe normalization and dead declaration removal; this additive finite contract
implements that obligation while preserving the earlier effect and residual
semantics. This is not a claim of separate user selection of internal marker names
or completed validation. Completion follows focused checks and verified integration.
