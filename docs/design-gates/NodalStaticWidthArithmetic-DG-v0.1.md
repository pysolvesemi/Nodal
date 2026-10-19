# NodalStaticWidthArithmetic-DG v0.1

**Status:** Approved
**Scope:** shared static expression and width-proof contract
**Decision:** extend the canonical static Integer owner for finite reduction widths

## Authority and compatibility

The explicit all-unchecked-Foundation implementation authority recorded in
[NodalDigitalExpressions-DG v0.1](NodalDigitalExpressions-DG-v0.1.md) covers this
required F55 widening-reduction prerequisite. It does not claim separate user
selection of these internal opcodes. Public Integer literal construction and
ordinary parameter/storage interpretation retain their existing contracts.

## Contract and shared owners

Canonical static `ceil_log2` accepts only a proven positive integer envelope and
computes the least integer exponent whose power of two reaches its input.
Canonical `choose_zero(test, zero, nonzero)` selects by exact mathematical zero.
All operand DAGs are independently checked before either simplification or branch
selection. Unreachable branches cannot hide malformed ownership, units, cycles,
overflow or division by possible zero. Conservative bounds retain both branches
when zero membership is uncertain. Equal defaults do not establish identity.

Private checked signed-64 literal and arithmetic constructors support
compiler-derived counts and widths. A wider-than-public-Int source literal needs
an active same-module retained construction identity. Existing immutable source
and native parameter queries retain actual parameter and expression identities,
substitute finalized instance bindings, and share checked arithmetic. No runtime
digital operation, public general conditional or second width AST is introduced.

Native mathematical-signed-64 query mode is explicit and defaults to the existing
storage mode. Only actual width consumers opt in; a supplied effect label alone
cannot authorize a producer. The mode preserves ownership, static dimensionless
Integer typing, dependencies, cycle and resource checks. Final widths must still
be positive and satisfy the existing digital resource profile. Shape/storage
consumers do not gain the wider interpretation implicitly. Arbitrary block
arguments remain nonstatic; scoped reduction formals are a separate required
follow-on.

## Rendering, alternatives and limits

Generic expression rendering uses bounded threshold ternaries for `ceil_log2`,
without assuming `$clog2` support. Pre-expansion accounting includes every emitted
copy under existing aggregate byte, depth and node limits. Oversized expansion
rejects before text allocation. Current Verilog-A/AMS target admission explicitly
rejects both new operations, including direct render entry points. Generic text
construction does not establish downstream compiler support.

Rejected alternatives include default specialization, Int narrowing, floating
logarithms, lazy validation of discarded branches, a private reduction-only
interval engine, and silent support claims for an untested target dialect.
This change does not implement reductions, scoped formals or target lowering.

## Focused validation

Check powers of two and adjacent boundaries through signed-64 maximum; reject
zero/negative logarithm inputs, invalid branches, units, arity, runtime producers,
foreign/unowned wide literals and overflow. Retain independent symbolic identity,
finalized binding and existing max/shape/width predecessors. Verify actual public
source-to-native witnesses with the complete parameter verifier, including a
causal old-width-consumer rejection and new-width-consumer acceptance. Retain
explicit analog target refusal and pre-expansion budget negatives. Acceptance
requires integrated focused execution; staged tests alone do not close F55.
