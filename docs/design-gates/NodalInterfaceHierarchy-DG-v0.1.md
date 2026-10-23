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

The canonical construction artifact records the explicit declaration boundary,
complete formal member and role inventories, ordered actual member types and
typed connectivity references. It does not reconstruct those facts from the
flattened ABI names or substitute the emitted MLIR digest for construction
identity. These inventories are conditional, so unrelated historical artifacts
do not acquire empty fields. Actual source locations remain in the existing
source-map owner.

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
The first bounded resolved implementation stage selects scalar read, drive and
pass-through. It must explicitly reject a newly boundary-joined component with
split views, without changing strict local split verification. This temporary
capability boundary does not waive full cross-boundary split or standalone inout
support; both remain required continuation.

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

The scalar implementation uses an optional topology result on the existing
verified dependency collector. It exposes existing component Values, aliases,
read results, actual drivers and splits, plus complete resolved endpoint slots.
The existing occurrence graph qualifies those slots and substitutes exact child
relations through `HierarchyModel.childOccurrence`; it does not build a second
hierarchy. Unaccessed slots remain logical identities. Only represented existing
component nodes receive merged dependency edges, with deterministic one-way
aliases and original occurrence contexts retained. Joined aliases and read
results cannot independently reintroduce nominal-domain attribution.

Resolved role rights are absolute read/drive/connect capabilities, not ordinary
in/out direction inversion. The public scalar projection therefore preserves
`driveMember`, `observeMember` and `forwardExact` rights and the finalized symbolic
element type. A public typed read-to-expression adapter, standalone DigitalInout
projection, cross-boundary split transport and resolved target profiles remain
required. Native NetRead admission alone does not establish those public APIs or
HDL emission. Focused results and checkpoint acceptance remain in the readiness
record.

## Shared deferred expression type prerequisite

Boundary type substitution precedes a bounded finalization walk over the existing
captured expression DAG. An internal result-type intent retains exact operands
and delegates inference to the existing operation owner. `KernelExpr` no longer
exposes its authored provisional constructor descriptor as a field. Its canonical
accessor reflects the once-sealed final descriptor; construction and digital
snapshot publication reject unfinalized intent. The finalizer checks actual
module ownership, dependency membership, acyclicity and represented node/edge
budgets without creating another expression registry or replaying source bodies.

The first implementation supports exact type-copy paths, resolved read intent,
alias/name/keep, complement, unchanged-width shifts, Struct field selection, and
bitwise/select operations with retained final exact equality checks. Final types
reuse the same already-sealed boundary descriptors and parameter identity proof.
Equal bounds/defaults do not satisfy bitwise or selection type identity. The
bridge/native owners still independently check the published exact types.

Shape-changing pending expressions, scalar conversions and lexical region
materialization currently reject explicitly until their existing inference and
scope obligations are integrated with this same lifecycle. These are interim
required follow-ons, not a completed symbolic hierarchy or standalone inout
claim. The prerequisite itself allocates neither a resolved net nor a physical
port and does not establish target support for any resolution profile.

## Standalone resolved boundary identity

A standalone `DigitalInout` projection uses `nodal.net_boundary`, an alias of one
actual immediate child's direct typed `resolved_net`, not another declaration.
The existing Interface/resolved model validates a bounded child declaration
inventory once per imported definition, rejects ambiguous names and repeated
projections, and preserves the original mode, resolution profile and placement.
The shared contextual digital type comparator proves the exact instance-bound
element type. A parent's default value, nominal type spelling or metadata cannot
supply that proof. Local and imported declarations share one resolution-policy
validator.

The existing topology result represents standalone declarations and boundaries
with their actual operations, exact instance/declaration relation, an empty
Interface member path, and an already existing component Value. The existing
occurrence composer joins these facts without allocating replacement nets or a
second graph. Standalone declarations currently have no semantic domain
constraint field; boundary metadata cannot invent one. Actual driver-domain
provenance remains the existing occurrence-qualified dependency proof. Resolved
Interface member constraints remain intact when those components are joined.

This native seam did not by itself establish the public selector or emission.
The subsequent source carrier uses the shared once-sealed boundary descriptor
lifecycle, preserves the exact relation in snapshots and reproducibility artifacts,
and renders the alias operation. The initial boundary-joined split rejection is
superseded by the checked logical continuation below, not by a physical transport
claim. Generated carriers and supported-profile target emission remain required.
Resolved operations remain target-unsupported until the target owner supplies the
corresponding physical proof.

## Typed resolved Interface member capability

The additive scalar selector consumes the existing typed `DigitalResolved`
declaration and an ordered sequence of actual `Nested` declarations. Factory
return types narrow to those existing case classes while remaining subtypes of
`InterfaceMember`. Capture validates object identity at every nesting step and
reuses complete canonical role expansion. Caller strings, copied same-name
fields and caller-supplied types do not create member authority. This avoids a
parallel public witness hierarchy; the ordinary Interface API currently has
only effect selectors, and a storable `StructMember` cannot represent net roles.

A sealed shared `ResolvedDigital[A, M]` capability exposes element type, mode and
read semantics. Existing `DigitalInout` implements it and retains its public
placement/profile and factory types. A private Interface member view implements
the same capability with exact origin but no fabricated standalone placement.
Existing drive, release, level, split and pass-through methods delegate through
the same construction operation path. Physical pad adapters remain standalone
only. The source Interface contract remains the sole mode and role policy owner;
static mode evidence never grants a role's absent read, drive or connect right.

The construction session owns view reuse and contextual type finalization. An
immutable snapshot origin retains the actual Interface endpoint, complete named
member path and selected role, separately from standalone boundary identity.
Bridge ingress independently checks that origin against the complete formal and
actual Interface contract and emits existing `member_access`, not a new net or
boundary. Local observation and split checks reuse their existing component union.
Native Interface topology and hierarchy occurrence composition remain unchanged.

The capability does not by itself imply physical split transport,
indexed/generated member selection, analog/conservative projection or resolved
physical emission. Those remain required obligations. Focused acceptance is recorded in readiness after
actual public source, forged-ingress and full native-gate checks execute.


## Closed-root conditional split proof

Existing `split` now supports direct concrete hierarchy components with exactly
one actual conditional driver. Source and bridge preserve their existing local
component proof before joining independently checked child-boundary identities.
Only a locally driverless split receives the closed `hierarchy_driver` intent.
Bridge ingress derives that obligation again; it cannot be asserted on unrelated
expressions, a locally driven component or an unjoined endpoint. Zero drivers,
release-only components, multiple drivers and unconditional Interface writers
cannot fabricate split data or enable. Historical local strict checks remain.

Native admission independently demands closed-root proof even without clocks or
sequential uses. The existing occurrence-qualified component union counts every
actual driver and split, including otherwise unused ones. All joined splits,
including locally proved splits, recheck the final unique-driver condition.
Dependencies then follow the original driver data and enable in their exact
occurrence; a checked low/high driver contributes a constant level but retains
its actual enable dependency. Reused definition pointers do not merge distinct
occurrences. Unreachable obligations, missing actual boundaries and causal
cross-occurrence feedback reject rather than becoming domainless constants.

The existing hierarchy dependency query may return immutable successful
`DigitalResolvedSplitBinding` facts after all cycle and sampling checks pass.
These retain split, driver and component occurrences, original operations and
values, and an optional checked level. They introduce neither a second graph nor
parent-owned copies of foreign SSA. Source artifacts conditionally retain the
intent; absent fields preserve predecessor artifacts. Focused source and full
native gates cover both directions, late symbolic widths, nested typed members,
level/local splits, forged intent, repeated definitions and causal feedback.

Union-find and the existing node/edge budgets bound represented endpoints,
drivers, splits and at most two dependency edges per split. No bits, lanes,
parameter settings or transitive provenance sets are enumerated. The Interface
bridge compresses alias paths within its existing query-local map. These are
structural growth observations, not a measured speedup claim.

This logical proof does not emit bidirectional nets or driver sidebands. The
shared target adapter must preserve one physical inout identity and separately
route proved split data/enable where necessary. Generated occurrence families
must retain multiplicity and reject split until selection-qualified component
proof exists. True top-level and declaration-only external inout transport need
actual four-state generated-output witnesses, including released Z, open-drain
pullup and contended X; two-state simulation alone cannot establish them.
