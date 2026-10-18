# Nodal conservative node arrays design gate v0.1

**Status:** Approved
**Scope:** public-api
**Increment:** Foundation 43
**Decision date:** 2026-10-18

## Authority and compatibility

The owner authorized the Foundation implementation scope on 2026-10-18:

> start implementing all foundation unchecked increment items in nodal repo from increment 43 and continue until all foundation checklists are completed. Don't start another track just complete foundation track.

The exact additive signatures below are implementation decisions within this
approved scope, not separately owner-selected signatures. This gate supplements
the historical v0.3 freeze to implement F-043 conservative array ownership.
Existing scalar node, terminal, branch, connection and value-Vec APIs retain their
meaning. Update the maintained API inventory/reference with implementation;
historical acceptance evidence remains unchanged.

## Exact public surface and finite profile

Keep the scalar module constructor and add an unambiguous shaped overload:

```scala
protected final def node[D <: Discipline](discipline: D): Node[D]
protected final def node[D <: Discipline](
    discipline: D, dimension: Dimension, dimensions: Dimension*
): NodeArray[D]

final class NodeArray[D <: Discipline] private[nodal] (...):
  def at(indices: Dimension*): Node[D]
```

The first required dimension prevents ambiguity with the scalar overload. Named
arguments `discipline = Electrical, dimension = 2` retain ordinary Scala meaning.
`Dimension` remains `Int | Expr[Integer]`. NodeArray is neither Data nor Expr nor
Signal: a conservative endpoint is not a numeric value or digital port.

The initial production profile is module-local Electrical arrays declared outside
analog and generation regions. Dimensions are nonempty, positive over all legal
parameter values, and their total cardinality fits signed 64 bits. Static
parameter dimensions preserve their actual declaration/expression identities and
complete envelopes; default substitution is forbidden.

Selection requires exactly one static index per axis, with 0 <= index < extent
proved for every legal parameter setting. Both 0 and 0.integer are legal. Reject
wrong rank, negative/excessive/unproven bounds, runtime values, foreign ownership,
generated induction indices and unsupported disciplines. Selection may occur
inside or outside analog: it creates a structural endpoint reference, not a new
node declaration or an analog value expression. Existing two-terminal V/I and
contributions consume the selected Node. Existing scalar Node connections also
resolve selected endpoints through the shared conservative endpoint owner; an
array selection is not required to pretend it is a declaration. Selected local
endpoints may serve as existing immediate-child scalar terminal actuals; hierarchy ownership remains unchanged. Existing parent-only connection
target limitations must still reject explicitly. Bulk array connections remain
separate. This does not independently broaden one-terminal access support.

## Physical identity, aliases and source occurrences

One array declaration owns its discipline, dimensions and source identity.
The selected physical endpoint key is the array declaration identity plus the
full canonical index tuple. Normalize integer literals by value, parameters by
owning declaration identity, and compound expressions by exact captured root
identity. Equal defaults, equal intervals and equal rendered text are not identity;
no algebraic equivalence engine is introduced.

Repeated selections with the same key share one endpoint. Each authored use keeps
its own source occurrence separately. For distinct tuples on the same array,
prove distinction through at least one pair of disjoint axis intervals (including
unequal literals); otherwise reject potential aliasing. Two possibly coincident
nets must not silently become different topology nodes. Native verification and
normalization derive these keys from real operands/static roots, never a supplied
metadata string. Equivalent native selectors share a deterministic representative
before branch/probe grouping and connectivity, retaining merged use provenance.
Self-relations then follow the existing scalar endpoint semantics; do not add an
array-specific rejection solely because repeated selections denote the same net.

Scalar Node/Terminal/InstanceTerminal identities and immediate-child ownership
remain unchanged. Sparse selected elements do not establish complete coverage of
an array for complete component contracts. Reject an unsupported completeness
claim rather than treating the selected subset as all endpoints.

## Shared owners and alternatives

Use the existing declaration records/snapshots for shaped conservative ownership,
with typed shape payloads, and reuse static dimension proof and expression
inventories. Selected Node construction has an internal reference-origin path
that bypasses ordinary scalar declaration registration. The existing shape-index
owner captures selections; a canonical-key lookup is derived state, not another
authoritative declaration registry. Source-use identity stays distinct from
physical endpoint identity.

Native `nodal.node_array` is a direct-module declaration with name, source_path and
metadata, returning ShapedType whose element is electrical TerminalType. The
result type owns dimensions; do not duplicate an authoritative dimension list.
Reuse full-rank `nodal.shape_index` for scalar terminal selection and its shared
static bounds proof. Restrict conservative inputs to the owned array declaration
for this profile. Independently check direct ownership, type/discipline, positive
bounded dimensions, checked cardinality and canonical static roots.

Widening scalar NodeOp would mix generated scalar-node occurrence semantics with
source-selectable shaped ownership. Creating a scalar NodeOp for every selection
would invent declarations and lose alias identity. Reinterpreting Vec(Real) as
conservative ports would break analog/digital and physical-dimension boundaries.
All three alternatives are rejected. The new declaration and narrow endpoint
normalization integrate with the existing conservative connectivity owner,
without a second topology engine or lane enumeration.

## Target representation and retained obligations

Emit one compact zero-based electrical array with the product of dimensions as
its cardinality. The net-declaration grammar in
[Verilog-AMS 2.4, section 3.6.3 and Annex A.9.3](https://accellera.org/images/downloads/standards/v-ams/VAMS-LRM-2-4.pdf)
permits dimensions on net identifiers; this is distinct from a vector-net range
before the identifier. Internal reparse is not independent simulator compilation.
Map full-rank selections using logical row-major coordinates;
for dimensions D0,D1 and indices i0,i1 the ordinal is i0 * D1 + i1. Retain symbolic
expressions, source array identity, logical shape, index tuple, target name and
ordinal formula. Existing scalar terminal maps feed branch/access/contribution
rendering. Target parsing must understand the actual emitted array endpoint
syntax and still reject malformed, unknown or unsupported references.

Port arrays, conservative views/slices, generated selections/connections,
hierarchy array mappings and writable Real-array storage remain required F-043
work. This bounded owner/reference implementation does not complete those
children or the Foundation43 parent. Generic value-array target lowering is not
implied by accepting shaped conservative declarations.

## Focused acceptance plan

- Scalar overload regression and named shaped-overload construction.
- Fixed multidimensional and bounded symbolic dimension witnesses through the
  public API, native verification and actual deterministic Verilog-A emission.
- Selection inside/outside analog and repeated Int/Integer-literal references.
- Canonically equal symbolic references share one endpoint; independent equal-
  default roots do not. Disjoint intervals succeed; unresolved overlap rejects.
- Native duplicate selectors cannot invent physical nodes or bypass alias checks.
- Exact rank, bounds, owner, discipline, overflow and source-provenance negatives.
- Selected scalar-node connections preserve endpoint identity and inherited scalar
  self-relation semantics; existing scalar F42 hierarchy and generated-node output remain supported.
- No generic Vec(Real) promotion or unsupported complete-topology coverage.

Run focused local tests, affected regressions and a concise shared-owner review
under the prototype policy. No CI, full qualification or performance claim is
required. Record executed results in the existing implementation note; this gate
is a contract, not evidence that unexecuted checks passed.
