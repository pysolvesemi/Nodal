# Nodal Interface hierarchy design gate v0.1

**Status:** First concrete digital admission slice implemented; full B.1 execution pending
**Scope:** public-api and compiler-boundary
**Baseline:** `615aad86775611bb8234e95ebd5371f16c4868ca`

## Authority and boundary

The owner's instruction to implement every remaining Foundation item authorizes
the required F-058.B.1 implementation under the prototype policy. This gate
selects a bounded extension of existing typed Instance selection. It does not
claim the owner separately selected every private representation, nor that the
required interface, array, inout and analog profiles already execute.

Preserve `instance(child)(_.endpoint)`, role-qualified interface connection,
explicit inout pass-through and conservative connectivity. No foreign child
member becomes a legal parent operand merely because Scala can reference it.
Existing direct local Interface semantics, role maps and ABI remain unchanged.
The [F58 readiness record](../implementation/increment58-readiness.md) owns
implementation decisions and results; all B.1 obligations remain required.

## Exact boundary capability

An Instance selector may return a parent-owned projection of an actual exported
Interface or resolved-net endpoint of that exact immediate child. The projection
retains the child instance, declaration, selected role, ordered nested member
identities, dimensions, exact type and actual domain binding. Repeating selection
reuses the same capability and physical identity. Selecting an internal endpoint,
different child, detached transaction or descendant directly rejects.

The existing construction transaction owns these projections. The Interface
owner still expands complete roles and nested members. No second role engine,
implicit inversion of a user's selected role, foreign SSA capture, or second
resolved-net declaration may substitute for the boundary record.

Input/output permissions are interpreted relative to the boundary: a child's
input is parent-drivable and its output is parent-readable. Store the declared
role and boundary orientation separately; do not manufacture a different role
map or mutate the canonical child contract. Monitor/read-only and analog access
rights are never elevated by crossing a boundary. Duplicate drivers still reject.

## Domains, topology and ABI

Resolve the child's declared requirement through that exact instance's actual
binding. Equal names, frequencies or serialized domain strings are insufficient.
Child-output dependency queries follow actual child drivers and crossing
provenance through the canonical HierarchyModel occurrence context. Root output
observation must not stop at the projection or trust its nominal domain.

Resolved pass-through joins the same physical resolved component; it is neither
a directional assignment nor a newly allocated driver. Preserve exact drive mode,
resolution profile and placement legality, including explicit top-level and
black-box boundaries. Conservative terminals retain their separate connection
and discipline/unit/flow owner. Do not implement analog or resolved members as
ordinary digital payloads.

Logical Interface paths and selected nested role identities remain stable.
Physical boundary maps identify the actual child declaration and member, with
explicit structural indices and parameter substitution where supported. Preserve
the inverse map and per-leaf direction/type. A generated index is a structural
identity, not runtime hierarchical addressing. Packed/unpacked internal views do
not change scalar packed or named-field-vector external serialization.

## Independent admission and execution

Bridge and native ingress independently resolve the actual child declaration,
exact selected role maps, shape and binding. Forged metadata cannot certify a
projection. Target lowering must consume that verified boundary and emit real
connections; uncalled helpers or ABI-only descriptions are not implementation.

Focused checks cover nested protocol directions, forwarding and fan-out, actual
domain substitution, missing/foreign/wrong-instance references, shape/role
mutation, multiple drivers, sibling isolation, resolved alias identity and
conservative topology. Retain directly affected F54/F57 predecessors and actual
generated output for newly executable profiles. Symbolic arrays, nested
generation and parameter overrides need their own positive and boundary cases;
they cannot be claimed from a concrete scalar witness.

## Additive first digital slice

`instance(child)(_.endpoint)` preserves `InterfacePort[I, R]` and the actual
selected role. `local.forwardExact(projected)` accepts the same role type and
requires exactly one child projection; existing `connectExact` keeps its
opposite-role evidence. A parent may drive only the child's declared input
leaves. Repeated selection reuses one projection. The first implementation
rejects alternate projected role views, generated endpoints, arrays and
analog/resolved/control leaves rather than claiming those required profiles.
`nodal.interface_boundary` names the actual instance and child endpoint; no
new Interface definition, native endpoint or resolved net represents it.
The native occurrence graph substitutes actual input/output dependencies.
Native target lowering is still required before B.1 completion.

## Required continuation and shared owners

The next source slice adds structural `InterfaceArray` selection through the
existing `Instance` capability and construction snapshot, not a parallel array
owner. An array selection retains the actual child declaration, ordered shape,
selected role, exact actual parameter/domain binding and explicit index tokens.
An indexed member is a view of that same endpoint. Repeated selection must reuse
its identity; indexing must prove rank, bounds, structural scope and generated
existence through the existing shape/parameter contracts. Runtime indices and
direct descendant references remain invalid. Concrete indexing is not evidence
for symbolic parameter substitution or generated arrays.

`PipelineInterfaceCandidateApi`, `ConstructionSession`/`ConstructionRecords`,
`ConstructionInterfaceLayout` and `InterfaceMlir` own source capture and boundary
transport. Native `ShapeModel`, `ParameterModel` and `HierarchyModel` own shape,
actual substitution and occurrence identity. Extend their narrow projection
interfaces rather than enumerate parameter values, flatten symbolic arrays or
copy role expansion. The Area owner must coordinate structural-scope records;
the hierarchy target owner must coordinate physical boundary adapters.

Resolved members and `DigitalInout` need a boundary capability for an existing
physical component. `DigitalInterfaceModel` remains the owner of equivalence
components and actual driver identities, extended with exact occurrence context.
Pass-through unions components and allocates neither a net nor a driver.
Preserve drive mode, profile, placement, read-only rights and split-driver
uniqueness across repeated aliases. Directed data dependency edges alone cannot
represent this topology.

Conservative Interface members must use `ConservativeConnectivity`'s existing
endpoint, structural shape, discipline/nature and flow-incidence proofs.
`TerminalView` retains its connect/sense/contribute evidence across the boundary.
Signal-flow analog retains its separate directional owner. Neither family is
represented as a digital payload or resolved digital net.

Actual digital emission belongs to the shared `SequentialToCIRCT` hierarchy
module/port plan. It must consume the verified complete leaf ABI, preserve the
inverse logical/physical mapping and connect actual child ports. Resolved
emission requires an explicit supported resolution/placement profile; analog
emission uses its applicable backend. Source-to-target demonstrations for newly
supported profiles, including structural arrays, resolved pass-through and
conservative topology, remain required. Admission-only success and transactional
target rejection do not complete B.1 or F58.

## Whole-array substitution and complete logical endpoint query

The next bounded array slice retains a single `InterfaceArray` capability and
one member value with an outer structural shape. `forwardExact`, `connectExact`,
`driveMember(Expr[Vec[A]])` and observation operate on the whole array. They do
not enumerate lanes, invent indexed endpoint declarations or authorize an
`at` shortcut. A projected public `count` is not available as a parent-owned
expression: the child formal remains private metadata until finalized instance
overrides have been applied. Early and late overrides use the same final query.

`StructuralParameterBinding.Query` is the source authority for exact count
substitution. Its `resolveInterfaceDimensions` reads the actual child declaration,
not a caller-supplied shape; `validateInterfaceDimensions` independently checks
bridge transport against the finalized instance. Conservative dimensions,
digital port types and Interface counts reuse the construction session's single
structural-expression materializer. Equal defaults or range bounds do not prove
identity. The native boundary stores parent-context actual dimensions separately
and verifies each against the original child dimension through `ShapeModel`'s
exact instance proof. Generated-family binding, indexed views and symbolic
payload-width substitution are separate required capabilities, not consequences
of whole-array count support.

`collectDigitalInterfaceEndpoints` exposes complete immutable logical facts from
one verified existing Interface model, including unaccessed leaves in authored
order. It reports actual declaration owner separately from domain owner and
projection instance. Dimensions remain the original child declaration tokens,
interpreted in that owner and optional exact instance context. Selected roles,
nested roles/interfaces and protocol identity are never inferred from names.
Resolved leaves report an existing deterministic authored-order component value
when one is represented; absence does not authorize allocation of a new net.
Conservative and signal-flow metadata remain distinct capabilities.

The shared hierarchy boundary plan will consume this query once per source
definition and derive physical names/layout and inverse paths in the target
owner. The collector itself is not Interface target execution. Until that actual
consumer and emission exist, arrays and endpoint inspection do not close B.1.

## Contextual symbolic payload contract

Symbolic payload instantiation reuses the digital port descriptor materializer,
`StructuralParameterBinding.InstanceQueries` and its existing Integer proof.
One batch of complete formal leaf descriptors is transformed per exact selected
endpoint, with one reachable expression export and a shared structural budget.
The construction snapshot retains the original child-owned members and every
formal role map. A separate, complete ordered `actualMemberTypes` inventory
records parent-owned payload types. Outer InterfaceArray dimensions remain a
separate shape layer; multiplying them into a payload width is not an ABI proof.

The bridge independently checks every actual type through the existing digital
type comparator and exact instance context, even when no member is accessed.
Native `actual_types` metadata carries the same explicit complete inventory.
`DigitalTypeModel` proves each formal-to-actual type and compares full imported
Interface definitions using two exact instance contexts in one parent. The
Interface model's imported comparison cache is keyed by both definition and
instance, while its existing accumulated role owner still checks sibling-only
roles. Equal names, defaults or range bounds cannot establish contextual type
identity. Missing, duplicated, reordered or changed actual members reject.

Repeated recursive type comparisons use a query-local memo in that same model,
keyed by both exact types and both exact instance contexts. A null instance means
the model's actual parent owner, not an unknown context. Each represented member
still consumes the existing structural budget, and the memo lives only as long
as the immutable model query. It delegates every new pair to `DigitalTypeModel`;
it does not persist proof results across IR mutation or replace type validation.

The logical endpoint collector still reports original formal types together
with the actual declaration owner and exact instance; it never presents parent
substitution tokens as child-owned facts. Ordinary member SSA uses the verified
actual payload type. No new Interface definition, protocol expansion engine or
parameter registry is introduced. Actual target adapters must consume this
collector through the shared hierarchy boundary owner before emission can be
claimed. Resolved, analog/conservative, standalone inout, generated and indexed
endpoint capabilities remain required separate work. Focused validation and
publication status belong to the readiness record, not this contract section.

## Resolved topology continuation contract

Resolved hierarchy support is not enabled by the ordinary payload projection.
The existing Interface model must retain local components, aliases, actual
conditional drivers and split obligations. The existing hierarchy dependency
owner qualifies those facts by occurrence and joins only explicit immediate-child
endpoint relations. Driver identity is the occurrence plus authored driver, not
the shared definition's operation alone. A pass-through relation adds neither
a driver nor a pair of causal dependency edges. After union, aliases point to
one deterministic component representative; actual read-to-own-driver feedback
must still be detected.

An unaccessed child resolved leaf may have no represented SSA value. Its exact
declaration and member path remain a logical endpoint identity, not permission
to allocate a replacement net. Domain constraints survive standalone net aliases
and must resolve to the same contextual domain through `HierarchyModel`.

A split requires exactly one actual conditional driver after boundary union.
Local verification cannot silently accept a missing driver, nor can a child
with one local driver conceal another parent driver. Any deferred external
obligation must have an explicit closed-root consumer and must not weaken
independent verification of a local component. Source capture must extend its
existing path-based union and split owner with the same exact boundary relation.
These obligations, their causal tests and supported-profile target emission
remain required before resolved hierarchy acceptance.
