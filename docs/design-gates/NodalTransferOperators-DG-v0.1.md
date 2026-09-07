# Nodal transfer operators design gate v0.1

**Increment:** 40
**Status:** Approved
**Scope:** public-api

## Surface and supported representation

The initial portable compiler profile exposes `laplaceNd(input, numerator,
denominator)` and three `ziNd(input, numerator, denominator, interval[,
transition[, start]])` overloads. Inputs, coefficients, and timing arguments use
`Expr[Real]`; coefficient arrays use immutable `Seq[Expr[Real]]`. No raw HDL,
string expression, sampled Scala evaluation, or simulator callback defines the
operator. Array lengths are concrete construction-time values; their elements
may retain legal scalar symbolic parameters.

This is the numerator/denominator (ND) representation for both continuous-time
and sampled rational filters. Zero/pole (`zp`, `zd`, `np`) representations,
vector-parameter carriers, optional Laplace tolerance, and explicit initialization
are not in v0.1. They are not silently converted, approximated, or ignored.

## Coefficients, dimensions, and denominator legality

Coefficients are ordered from index zero upward. Laplace index k multiplies s^k;
sampled index k multiplies z^-k. Arrays must be nonempty and are preserved in the
authored order, including zero and trailing-zero coefficients. No degree trimming,
monic normalization, coefficient reordering, pole cancellation, or symbolic
parameter specialization is performed.

The normalized v0.1 transfer has dimensionless gain and preserves the input's
physical dimension. Both Laplace coefficient arrays require time^k at index k;
all sampled coefficients are dimensionless. Scaling by a dimensioned gain remains
an ordinary explicit expression outside the operator. A zero-valued coefficient
still requires its correct physical dimension.

Coefficients must be real, analysis-static expressions: literals, scalar
parameters, and supported pure arithmetic/math over those values. Dynamic probes,
analysis queries, time, noise, transfer state, and other runtime values cannot be
coefficients. Known constant values must be finite. Symbolic expressions are kept
symbolic; a parameter default is never substituted to prove a property of every
override. Finiteness over arbitrary symbolic arithmetic envelopes is not claimed.

Denominator coefficient d0 must be a proven, finite, nonzero constant. Negative
d0 and a zero numerator are permitted. All-zero denominators and d0=0 reject;
closed-loop integrator reasoning and symbolic d0 range proofs are outside v0.1.
No pole stability, model convergence, or numerical conditioning proof is implied.

## Sample timing and defaults

The interval is mandatory and proven positive. An explicitly supplied transition
must also be proven positive in the branch-safe portable profile. An explicit
start is proven nonnegative. All three have time dimensions. Parameters without
whole-envelope proofs and dynamic timing reject, even when their defaults pass.

Omitted transition and start arguments remain omitted. Explicit zero transition
is rejected, rather than confused with omission or silently clamped. This is
stricter than the language's general zero-transition form, whose direct branch
assignment is restricted. No automatic digital register, clock, pipeline, or
hardware-synthesis interpretation is added by `ziNd`.

## State, ownership, contexts, and optimization

Each call creates one effectful `nodal.analog_transfer` with an operator identity,
state identity, owning module, versioned coefficient segmentation, coefficient
order, dimensions, and source metadata. Equal independent calls have distinct
state. A reused result references one evaluated state; zero and unused filters
remain effects. Folding, common-subexpression elimination, and dead-expression
removal cannot merge, erase, duplicate, or replace filter state.

Creation is supported only in unconditional `analog` regions. Equation,
contribution-block, initialization, procedural/event, and conditional-creation
contexts reject. Ordinary contribution consumers of an already created filter
remain supported. Inputs and all reachable definitions must belong to the owning
module. Cascading filters uses the first filter's single materialized result.

Native verification independently checks the shape, type, dimensions, static
coefficient expressions, denominator, timing, ownership, state identity, duplicate
identity, and illegal folding claims. Advisory metadata is not trusted as proof.
The bridge rejects missing, duplicated, orphaned, or corrupt transfer inventories.

## Target contract and qualification boundary

Verilog-A emits one private real temporary and one filter call per owned state,
using explicit `'{...}` coefficient literals. Names are deterministic and avoid
all authored identifiers. Full Scala lexical-binder retention remains assigned to
Foundation 153-157; this increment does not claim it.

Emission is followed by an independent expression/array/timing grammar check.
That grammar does not widen procedural/event legality. Tests cover source replay,
native parse/print, malformed IR, optimization idempotence, state preservation,
coefficient ordering, symbolic coefficients, shared results, independent calls,
zero/unused filters, cascades, naming collisions, and malformed target calls.

The acceptance is compiler/structural only. Numerical AC/transient simulation,
solver qualification, filter stability, general Verilog-AMS, synthesis, and
transfer-function equivalence against an external solver remain separate work.
Required Core CI, dedicated source/native evidence, review, merge, and exact
post-merge qualification are prerequisites for marking Increment 40 complete.

## Diagnostics

`NODAL-ANALOG-040-001` reports unsupported creation contexts; `002` reports
malformed form/shape/inventory/identity/ownership; `003` reports real-type or
physical-dimension errors; `004` reports known finite/domain/timing violations;
`005` reports dynamic coefficients; `006` reports unsupported policies or
unproven denominator/timing requirements. Earlier mandatory type/context
verifiers may reject malformed input before the transfer-specific diagnostic.
`NODAL-ANALOG-FOLD-001` rejects fabricated folding evidence.

## Standards reference

Accellera Verilog-AMS 2023, clauses 4.5.11, 4.5.12, and 4.5.14:
[official LRM](https://www.accellera.org/images/downloads/standards/v-ams/VAMS-LRM-2023.pdf).
Nodal's ND-only, normalized-units, nonzero-d0, branch-safe timing profile is an
explicit implementation subset, not a statement that the standard prohibits the
deferred forms.
