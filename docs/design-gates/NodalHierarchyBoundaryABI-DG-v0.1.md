# Nodal hierarchy boundary ABI design gate v0.1

**Status:** Approved bounded implementation contract; implementation in progress
**Scope:** compiler-boundary and target representation
**Owners:** required F-058.B.1, F-058.B.2, F-058.B.2.1 and F-058.B.2.2 continuation

## Purpose and scope boundary

This additive contract follows the concrete digital checkpoint in
[hierarchy execution v0.1](NodalHierarchyExecution-DG-v0.1.md).
That checkpoint implements real reusable digital modules and material
clock/reset variants. It does not establish symbolic instance ABI, generated
occurrence families, Interface target connections or layout-independent
field-vector adapters.

Read with [Interface hierarchy](NodalInterfaceHierarchy-DG-v0.1.md),
[Area hierarchy](NodalAreasHierarchy-DG-v0.1.md), and the
[portable Vec layout amendment](../roadmap/portable-verilog-vec-layout-v0.1-plan.md).
The existing roadmap owns all completion boxes. This contract neither closes them
nor adds a second completion ledger. Public emission configuration and complete
portable IEEE 1364-2005 emission remain F65-owned; a CIRCT/SystemVerilog witness
does not claim portable-Verilog qualification.

## One semantic authority, read-only adapter views

`HierarchyModel` remains the owner of definition, occurrence and actual domain
binding identity. `ParameterModel` owns finalized instance substitution and
all-legal integer-expression facts. `ShapeModel` and `DigitalTypeModel` own
shape/index and recursive storage identity. `DigitalInterfaceModel` owns
complete definitions, roles, access rights and resolved components. Generated
lexical captures remain with the existing generated-region and digital-region
owners.

A proposed `HierarchyBoundaryModel` is a bounded, query-local immutable view of
those owners. It must not build another occurrence graph, copy their semantic
proof rules, create a mutable global registry, persist stale certificates, or
derive identity from target names. Its lifetime cannot outlive or mutate the
source IR on which the owning queries depend. Failed collection publishes no
partial plan.

### Logical leaf record

For each complete endpoint, including members never accessed by source SSA,
retain:

- Exact declaration and module identity; the exact immediate instance and
  occurrence/family context when crossing a boundary.
- Full ordered member/field path and nominal Struct/enum identity. Path segments
  are semantic records, not underscore-concatenated emitted names.
- Ordered dimension groups at every enclosing array/field boundary. A sibling
  field's private dimensions never enter another leaf's shape.
- Original recursive Type and scalar kind, canonical enum ABI where applicable,
  and separate width and extent `InstantiatedIntegerExpression` facts. Bounds
  assist proofs but never replace the retained expression DAG.
- Declared direction, protocol, canonical selected/nested roles and
  boundary-local read/write orientation as separate facts.
- Source provenance and exact actual domain reference. Physical clock/reset
  identity is resolved through the existing occurrence owner, not copied into a
  nominal leaf label.
- Existing represented resolved-component identity where applicable.
  Conservative terminals, analog quantities and resolved digital nets remain
  distinct capabilities, never ordinary bit-pattern leaves.

Do not allocate a new resolved net for a projected member. Do not reinterpret
monitor/read-only or conservative access as permission to drive. A leaf with no
represented resolved component is not evidence that a new component may be
invented.

### Interface query seam

The proposed narrow Interface entry collects all endpoint facts once per
definition through one existing verified `DigitalInterfaceModel`:
`collectDigitalInterfaceEndpoints(module, out)`. It returns endpoint/declaration
identity, optional exact child instance, declaration owner, semantic definition,
selected role, actual bound domain and ordered dimensions. Its leaves retain
the actual `InterfaceMemberOp`, full path, original Type, protocol, canonical
role/access and nested role/array facts, analog references, and an existing
resolved-component Value only when represented.
Collection validates/builds once per local module definition and exports
endpoints in authored order; repeated projections or leaf visits do not rebuild
the whole Interface model. A resolved-component representative is selected
deterministically in authored order from the existing union-find component.
Absent means unrepresented, never permission to allocate a new net.
Keep declaration owner separate from domain owner: a projection's declaration
belongs to the child while its substituted actual domain belongs to the parent.
Definition and selected-role pointers retain their actual proof owners. Array
dimension tokens resolve in the declaration owner plus finalized instance
context, never by parent-scope string substitution. Authored-order traversal of
module/member operations and existing net indices determines output order and
representatives; pointer-map iteration does not.

The Interface owner does not instantiate another parameter or topology model.
The boundary view attaches existing hierarchy/parameter contexts and recursively
queries width/shape owners. Target code alone chooses physical names, carriers
and adapters.

## Exact instance ABI and symbolic parameters

Current raw child/parent Type equality is insufficient for an overridden child
width or shape. Add a narrow shared parent-child instance ABI query which:

1. Resolves the exact child formal and parent actual owners.
2. Uses finalized actual overrides and recursively dependent defaults through
   `resolveInstanceIntegerParameter` / `resolveInstanceIntegerExpression`.
3. Compares complete scalar kind, nominal enum/Struct identity, ordered field
   names, rank and every dimension/width expression under that substitution.
4. Requires existing all-legal constraints, positivity and bounded arithmetic.
   Equal defaults, equal bounds or a singleton envelope never establish identity.

Existing `equivalentDigitalStorageTypesAcross` compares admitted lexical
contexts within its module owner. It must not be passed unrelated owners as if
that already established an instance ABI. Reuse its recursive rules and the
existing `verifyInstanceShapeDimension` owner rather than implement a target-only
equivalence test.

The bridge must independently retain and validate exact formal/actual
relationships. Serialized metadata is not permission to bypass native proof.
An instance carries parameter expressions, not an evaluated set of preferred
values. Target module declarations preserve generic parameters, and every
material clock/reset variant preserves that same parameterization.

### Finalized source boundary types

`ConstructionSession` retains the exact child declaration and occurrence for
each cached digital port projection. A private deferred datatype is only a
view of that declaration, not another declaration, type registry or expression
engine. Its original formal descriptor is retained separately. After all
instance overrides are captured, the existing `StructuralParameterBinding.Query`
resolves each width and extent in the actual child-to-parent context. The shared
structural DAG materializer reuses owned parent parameters and expressions,
and the projection view is sealed exactly once before ordinary validation and
snapshot publication. Override-before-use and override-after-use therefore
have the same whole-value boundary semantics.

The initial source slice supports whole-value assignments. An unfinalized
foreign width is not permission to construct eager parent width-dependent
operations. No default evaluation or equal-bound approximation may turn that
foreign descriptor into parent-owned shape evidence. The bridge compares the
finalized projection and original child type through its existing recursive
type comparator with separate owners and the same canonical substitution
query. Even identical textual parameter names require this comparison.

The first bounded target extension admits parameterized whole-value
pass-through/alias boundaries and fixed-rank shaped data, followed by individually
admitted operations. It uses checked CIRCT parameter declarations/references/
expressions and parameterized integer/array carriers. Do not inject unchecked
verbatim parameter text, replace widths by overridable defaults, or clone one
module per numeric override. Parameter defaults and instance actuals remain
distinct roles. Every retained expression intermediate must fit the selected
target arithmetic model over the whole legal envelope.

The first CIRCT scalar/Vec transport uses signed 32-bit parameter attributes,
matching the pinned HW parameterized-integer textual width contract. Native
semantic expressions remain mathematical signed 64-bit. Every transported
parameter, default, retained expression node and new target intermediate must
fit signed 32-bit; wider envelopes fail transactionally instead of truncating.
This restriction is a bounded target profile, not a change to source Integer
semantics. Textual target round-trip and actual SystemVerilog export are required
in addition to in-memory target verification.

Bounded ordinary unitless Integer configuration parameters use the same exact
transport and substitution without acquiring structural effects. Kind inference
remains with `ParameterModel`; range constraints must be actual canonical
`parameter_constraint` operations, not bridge metadata. Unconstrained i64
configuration still exceeds this first target arithmetic profile. Runtime
Integer values remain outside the boundary-only profile.

The target representation also uses balanced binary bitwise reduction trees
when preserving source correlation. The pinned exporter was observed to omit
operands beyond the first two on attributed variadic bitwise operations. This
shared adapter retains every operand and its provenance, with linear `n-1`
operation cost and logarithmic depth; it does not change enum membership or
reset-combination semantics. Enum decode keeps the complete legal-code test,
the exact selected default, and separate validity of the original bits.

## Layout-independent logical and physical maps

Internal packed/unpacked representation cannot select the external ABI.
Scalar-element Vec boundaries retain one one-dimensional packed carrier. The
recursive named-field-vector profile retains one packed carrier per scalar
leaf, each with its own width and ordered enclosing dimensions.

The target adapter plan records both directions:

- Logical endpoint/path/coordinates to physical carrier and bit slice.
- Physical carrier/slice back to the exact logical endpoint/path/coordinates.

Logical serialization remains zero-origin row-major, rightmost dimension
contiguous, with logical element zero in the low element slice. Physical
field-wise ports do not change the logical record serialization to field-major.
Dimension factors and expression provenance survive even when a backend
internally computes a total width. Signed elements retain element-level signed
semantics; a packed Vec carrier does not become one signed number.

Adapters add no state or latency. Driver direction, partial/full assignment,
four-state behavior and role permissions remain unchanged. Inout identity
requires an actual bidirectional net representation, not opposing continuous
assignments. Conservative connectivity continues through its own topology and
discipline/unit owner. Unsupported capabilities reject transactionally until
their actual target consumer exists.

### Explicit named-field target profile

The bounded CIRCT pass option `boundary-abi=named-fields` selects one uniform
external named-field-vector ABI for every selected module definition. Its default
is `legacy`, retaining existing fixed packed Struct CDC predecessors. Symbolic
Struct boundaries require `named-fields`; the pass must not choose a pin ABI
from the presence of a field read or another internal operation. The emitted
module records the selected profile in `nodal.boundary_abi`, and reproduction
commands retain the option. Source types and logical serialization do not change.

In the named profile, the target-only carrier is a HW Struct of complete logical
scalar-leaf vectors. Exact `digital_struct_get`/`digital_struct_make` adapters
select or reconstruct those leaves by the already verified nominal field path.
Physical module pins split/rejoin that same carrier. The inverse record retains
full nominal paths, scalar kind, element width, and ordered dimension groups at
their original field depths; it does not conflate sibling-private dimensions or
reinterpret a Vec-of-Struct logical serialization as field-major data. This
bounded stage does not expose the carrier itself as a logical packed bitstream.
Any later packed-stream consumer needs its actual interleaving inverse adapter.

The initial admitted operations are whole-value connections, aliases, wires and
exact Struct field construction/projection. Fixed Struct CDC in `named-fields`
remains a required adapter gap until its packed/leaf-vector inverse map is
implemented and tested. Keeping it rejected in this profile does not delete the
required work or alter its supported `legacy` behavior. Resolved/analog Interface
and generated-family target support remain separately open.

## Generated occurrence families

Existing `GeneratedRegionRecord` and
`KernelGeneratedScope(owner = generatedRegionPath, induction = expressionPath)`
are the source family authority; `InstanceRecord.generatedScope` references that
record. Reuse them rather than add per-lane instance IDs or clone a
representative Area. A generated Area retains one template and the same family
reference.

The native hierarchy extension must represent an actual lexical occurrence
family with its checked `GeneratedDomain`, exact captures, induction identity
and enclosing occurrence. It consumes `resolveStructuralInstance`,
`resolveInstanceModuleDefinition`, `resolveGeneratedDomain` and
`digitalGenerateIntegerContextAtUse`, and the existing shape iteration-image
proofs. All-legal family proof is not permission to enumerate the maximum number
of lanes.

A selection such as a static family index followed by a member path retains a
typed family/index/member projection chain. It resolves the original member or
instance declaration. It does not return a representative Area object, use
runtime hierarchical addressing, or manufacture foreign child SSA. Generated
scope restrictions in `HierarchyMlir` and `HierarchyModel` are relaxed only for
this checked contract, never by replacing an immediate-parent check with an
unqualified recursive lookup.

The target emits actual structural loop scopes containing real instances,
preserving one generic module body per material implementation profile. Empty,
singleton, nested and guarded existence cases keep their existing semantics.
An empty generate domain does not make a zero-sized Vec legal. Unsupported
family-dependent material clock/reset selection must reject rather than pick
the first lane's semantics.

## Bounded implementation order and ownership

1. Add the read-only logical boundary and exact instance ABI query, with
   independent owner/shape/role mutation tests.
2. Connect it to the existing `SequentialToCIRCT` path for actual parameterized
   whole-value boundaries and overrides. Prove definition sharing and emitted
   parameter identity before extending operations.
3. Add Interface physical adapters to that same target path, jointly with the
   Interface owner. Do not create a parallel emitter.
4. Extend the canonical hierarchy to generated families and the bridge to exact
   indexed member references, then emit retained HDL structural loops.

Native hierarchy/target owns Port/Struct parameter adapters and the target
consumer. Interface owns canonical full endpoint/role/net facts. Area owns
source family/member capture and coordinates the bridge family record. Each
change retains its mandatory predecessor proofs. Contextual-reset FSM
normalization remains a separate coordinated semantic extension, not an
incidental consequence of the boundary plan.

## Focused checks and honest limits

Positive witnesses must include distinct legal overrides sharing one definition,
nested forwarding, independently symbolic width and rank-two extents, nested
fields with sibling-private dimensions, deterministic collision-safe physical
names, and round-trip logical/physical maps. Actual target runs must demonstrate
parameters and overrides in emitted HDL, and later real loop bounds/instances
for generated families. Changing an override or loop bound must not silently
multiply module definitions or substitute source defaults.

Negative witnesses retain equal-default/different-symbol forgeries, transposed
dimensions or fields, missing/foreign instance bindings, out-of-envelope and
intermediate overflow, illegal generated capture/index/guard, role weakening,
and loss of inout/conservative identity. Unsupported target profiles must leave
the original IR and output publication unchanged.

Measure metadata work against represented definitions, DAG nodes, paths,
dimensions and generated families, not physical bit/lane count. Include a large
represented shape/family with bounded metadata and verify no expansion before
claiming the relevant profile.

The named-field Port/Struct slice has focused acceptance recorded in
[increment 58 readiness](../implementation/increment58-readiness.md), checkpoint
11. Interface adapters, generated target loops and complete fixed-Struct CDC
inverse adaptation remain open; primitive backend probes are not acceptance of
those production paths.

### Ordinary Interface consumer of the shared target plan

The bounded target extends the same logical-port inventory used by `PortOp` and
real child instances to complete ordinary Interface endpoints. A key is the exact
endpoint declaration plus complete member path, not a physical name or the first
member-access view. Selected-role directions remain unchanged; immediate-child
orientation is applied only at the instance connection. Monitor aliases therefore
do not redefine an input as an output. All unaccessed inputs still have pins;
unaccessed or undriven outputs fail instead of acquiring invented values. Because
current Interface instances have no private/internal endpoint discriminator, a
locally driven declared input is an explicitly unsupported physical profile.

`collectDigitalInterfaceEndpoints` supplies the complete borrowed contract once;
existing assignment-dependency roots supply canonical connect/drive aliases.
There is no second driver graph, Interface registry or target emitter. Child
formal payload and outer dimension tokens remain child-owned. Parent SSA values
use their independently checked actual type, and local endpoint dimensions wrap
the payload without merging away its nested Vec/Struct dimension groups.
The shared named-field split/join adapters, parameter DAG, operation budget and
collision-safe physical naming apply to ordinary ports and Interface leaves alike.
The module-level inverse map also retains definition, selected/nested roles,
member path, access, protocol and original outer dimensions once per logical port.

The initial physical profile is ordinary digital `in`/`out` members, including
whole symbolic arrays and Struct/Vec payloads. Resolved nets, standalone inout,
analog/conservative terminals, control Interfaces and declared monitor endpoints
still reject transactionally. The fixed-Struct CDC adapter gap under explicit
`boundary-abi=named-fields` remains unchanged. Generated helpers must consume this
same port plan rather than introduce a parallel boundary engine; generated loops
and their pinned-consumer limitations remain in the generated Area gate.

## Late-bound arithmetic inference continuation

The existing bounded result-type finalizer also admits direct-module arithmetic
whose operand type depends on a later child parameter binding. The arithmetic
owner retains exact operand dependencies and only an internal provisional kind;
it runs the same width, divisor, comparison and extraction checks once after the
canonical boundary substitution. Derived widths are ordinary parent-owned static
Integer DAG nodes, not evaluated defaults, duplicated type engines or a guessed
formal-to-actual envelope equivalence. Chained results finalize in dependency order.

This profile covers numeric arithmetic, negation, comparison, concatenation,
constant half-open extraction and expanding constant left shift. Bitwise/select
retain exact whole-type equality. An out-of-envelope extraction, absent exact
nonzero divisor proof or stale bridge result still rejects at its canonical owner.
Conversions, aggregate construction and lexical-region result materialization
remain separate required continuations until their same-owner checks are accepted.

Source/native support does not imply every target operation is implemented.
Computed symbolic arithmetic remains outside the current symbolic boundary target
profile. Fixed-width derived arithmetic must use the already-proved target width
and signedness rather than treating a parameter-expression descriptor as zero
bits. The concrete demonstration covers addition, multiplication, chained results,
negation, concatenation, extraction and comparison through unchanged generated
SystemVerilog; division and left-shift are not claimed by that demonstration.

### Scalar conversion and Struct continuation

The same finalizer now also delegates deferred scalar conversion to the existing
`DigitalScalarContract` policy and deferred Struct construction to the existing
`DigitalAggregateContract` schema/field checks. Only exact operand dependencies
are retained; no source body is replayed. Conversion width direction, representable
value, signedness and finite-Real restrictions still apply to the sealed actual
type. Struct member identity, nominal name, field order and complete field types
remain independently checked; equal parameter envelopes do not establish identity.
Early/late actual binding is equivalent for both symbolic and concrete profiles.

This remains direct-module result inference. Lexical-region finalization and
inferred-storage lifecycles are not widened by this change. The all-conversion
Struct witness passes native gates, but default packed aggregate projection and
named-field saturation target limits still reject explicitly. Native semantic
acceptance therefore does not claim that entire witness executes as emitted HDL.

## Fixed named-field CDC inverse adaptation

The earlier fixed-Struct CDC gap is now implemented for exact fixed payloads
under explicit `boundary-abi=named-fields`. The target derives one immutable
per-type permutation from the existing boundary leaf inventory and canonical
fixed type/shape queries. It checks nominal field paths, independent dimension
groups, actual target leaf widths and complete bit coverage before emission;
serialized adapter metadata is an output record, never type or layout authority.
The logical order remains recursive row-major with authored Struct fields least
significant first. Concatenating physical leaf vectors in field order is not an
acceptable substitute for the inverse when a Vec contains Structs.

The adapter packs each actual crossing input into the existing packed CDC
executor and unpacks its payload result. Gray, handshake and asynchronous FIFO
state, latency, reset behavior and runtime monitors are unchanged; Boolean control
results are not adapted. The same permutation supports direct, fixed contiguous
`ShapeIndex` observations using the canonical proved selection interval. Dynamic,
symbolic and noncontiguous indexing remain outside this target slice. Truly
symbolic CDC payload layouts still reject transactionally.

Architecture review: this is a target-only physical adapter, not a second shape,
type, driver or hierarchy model. Plans are cached by exact original type within
the existing module lowering. Fixed scalar segments are bounded by the existing
4096-bit payload limit; extracts, binary concatenations and leaf construction
are charged to the existing target operation budget before mutation. It adds no
module specialization or register. Emission independently checks the actual
value widths against the plan. No general symbolic-layout CDC or performance
improvement is claimed.

Focused acceptance comprises 157 sequential target cases (all 149 predecessors
plus eight causal layout/index cases), 39 hierarchy target cases and 31 symbolic
target cases. Native checks pin independent logical offsets for a 19-bit payload:
cell zero occupies bits 0–8, cell one bits 9–17, marker bit 18; each cell places
flag at 0, two UInt3 samples at 1–6 and SInt2 at 7–8. Distinct input packing and
independent output-state decoding prevent a symmetric wrong inverse from passing.
The maintained actual Scala source `Increment58NamedCdcSource.scala` passes all
three full gate→CIRCT→unchanged SystemVerilog→four-state Icarus profiles, including
independent typed field observations. Illegal Gray changes and FIFO stalled
payload changes separately trigger their retained monitors; they are expected
negative checks, not legal transport passes.

The maintained `tests/scala/domain-hierarchy/run_named_cdc_target.py` records the
explicit ABI, tool/input/output hashes and consumer configuration. Pinned
firtool 1.154.0 uses its official
`disallowPackedStructAssignments,disallowLocalVariables` lowering options for
Icarus compatibility. Initial Icarus failures on packed-struct assignment patterns
and automatic local variables are retained as historical failed attempts; no
emitted DUT or assertion is edited to obtain the passing runs. Commands and the
small actual-output demonstration are documented beside the runner. Electrical
CDC timing/skew obligations and all broader unresolved boundary profiles remain
unchanged.

### Lexical statement-result finalization

The same bounded result-type finalizer admits existing pending finite digital
value intents in ordinary structural statements, behavioral arms and actual
Generate bodies. Capture arguments copy only the exact immediate parent edge's
final type through the existing capture registry. Finalization revisits the real
lexical ancestry and complete type dependencies, seals each result once, and
rechecks affected assignments with the same exact recursive-type rule used for
eager assignments. No source body is replayed, provisional type is published,
parameter envelope is substituted for identity, or second inference engine exists.
The existing depth 128 and work 65536 limits remain authoritative; capture-edge
lookup uses the existing identity key rather than a reverse scan per argument.

The bounded profile uses explicit storage types. Early/late child binding must
produce identical source transport. Equal-default, equal-envelope foreign widths,
forged capture edges, pure collection and procedural-loop contexts still reject.
Structural branch-result joins, collection result inference and inferred-storage
lifecycles remain separate required continuations. Source/native acceptance does
not claim target support for every operation that can carry an intent.


### Deferred structural result joins

The lexical result-type owner extends to ordinary structural value conditionals.
Each actual StructuralIfRecord retains its true and false yielded edges, captured
once in authored order. Only this exact record/result pairing may add those two
closed dependencies to the existing result-type intent. The bounded finalizer
checks the actual arm registry, owner, parent, arm polarity and yielded identity,
finalizes each edge in its own lexical scope, then uses the existing complete ABI
export and same-type query to seal the parent result. Raw arm values are not
captured into the parent merely because they have compatible types. No second
inference engine, snapshot format, branch replay or parameter sampling is added.

The maintained positive profiles are direct, nested, behavioral and generated
conditional results with explicit storage types and early/late actual child
bindings. Equal default/envelope or payload kind does not authorize mismatched
branch ABIs. Opposite-arm forgery, escaped values, poisoned second-arm capture,
pure collections and procedural contexts retain causal rejection. Collection
result inference and inferred-storage lifecycles remain separate continuations.

Before shared promotion, strict private source checks passed 41 focused groups:
DeferredJoin 8, Lexical 8, DeferredHierarchy 5, DeferredArithmetic 4, DeferredValue 6
and StructuralIf 10. Sixteen actual maintained-name source replays passed the
full default native gate on frozen compiler
`93a1af6b101831196cc9a4d954330a80601dc941cb59ff965ae55f1d720c7e9c`;
each early/late pair has byte-identical transport. These are private-overlay
prototype results, not yet matched shared-source or target execution acceptance.

## Scalar resolved physical boundary consumer

The bounded target profile consumes the successful existing hierarchy dependency
query and the same complete logical-port plan used for ordinary digital ports.
It does not create a second connectivity graph. Each physical endpoint retains
its exact occurrence, actual declaration, optional immediate child, logical
member path, original type/mode/profile/placement and canonical selected role.
Unaccessed declared pins remain present. Missing represented SSA values do not
mean a constant or authorize another semantic net.

Only already-proven local component aliases share a local carrier. A parent join
must not short otherwise independent ports inside a reused child definition.
Ordinary roots may expose fixed scalar portable top-level/black-box resolved pins
and scalar resolved Interface leaves. Actual module arguments and instance ports
use `hw.inout`, reads use `sv.read_inout`, and owned conditional drives use native
Z/mux/assign operations. Driver declarations remain compile-time capabilities.
Release is real high impedance, not zero. An unconnected child pin has a physical
wire correlated to that exact pin, not a newly fabricated Nodal net.

A checked declaration-only external module becomes `hw.module.extern`, with its
original exact physical name and complete authored pin signature. No ordinary
body or zero-driving stub is emitted. Native signature proof and final target
physical-name collision checking both apply. A signature-only external root is
not an executable entry. Unknown external contributions remain unknown and
cannot establish split-driver uniqueness.

Two public local pins belonging to one proven component retain both port names.
The pinned Icarus consumer rejects exported `sv.alias`, so the bounded consumer
uses a fixed standard `tran` primitive with exactly two equal-width typed InOut
operands. The target owns this template and collision-checked instance name;
there is no user-supplied text, interpolated source name, paired assignment or
post-export rewrite. Fixed widths and primitive bit counts are charged to the
existing target limits. Other carrier/read/drive/extern operations remain native
CIRCT operations. The failed alias consumer probe remains separate from actual
Nodal output evidence.

The maintained `tests/scala/domain-hierarchy/run_resolved_target.py` checks actual
public source replays through the full gate, target lowering, unchanged emitted
SV and four-state simulation. Its seven profiles cover inward and outward drive,
all 16 four-bit patterns, release to Z, full and partial contention X, open-drain
pullup/release, joined versus independently reused externs, typed multi-public-pin
aliases and nested-role resolved Interface transport. The separate authored
external model supplies the unknown environment; it is never a replacement DUT.
Receipts retain input, source, compiler, exporter, simulator and output hashes,
exact commands, physical pin checks and the explicit boundary ABI.

This checkpoint excludes symbolic resolved widths, resolved Interface arrays,
internal/hierarchy-pass-through/technology-specific placements, generated
resolved occurrence transport and analog/control pins. Local and cross-occurrence
split data/enable sidebands were an explicit refusal at the scalar-pin checkpoint;
the separate fixed-scalar consumer below does not substitute resolved reads. Ordinary Interface, named
Struct CDC and generated storage target predecessors remain unchanged. No
physical timing/electrical certification or broader resolved profile is claimed.

## Fixed scalar resolved split transport

The physical consumer now also consumes the successful, transactional
`DigitalResolvedSplitBinding` facts from the existing occurrence dependency
composer. Exact actual driver identity, component/domain closure, and uniqueness
remain native-model obligations. Opaque external contributors and zero or multiple
actual conditional drivers cannot establish a split. The target does not count
physical assignments to infer uniqueness or create a second connectivity graph.

A bounded derived route walks the existing occurrence parent links to their least
common ancestor. It transports the original driver data and enable on ordinary
logical ports through the same module/instance planner; a level driver supplies
its exact low/high value and actual enable. No pin read, register, latency, or
foreign SSA value replaces a sideband. The physical inout and four-state read
continue independently. Local split views use the same route without unnecessary
boundary ports. The operation inventory is indexed once per actual definition;
route paths, operation costs, and total relative signature bytes are bounded.

Material signatures include each definition-relative route and selected child
ABI. Incoming routes are keyed by local split uses, not an arbitrary first
occurrence or a globally numbered driver. Two independently driven instances of
one reader reuse one body when their relative routes agree. When one occurrence
of a two-pin reader has both pins connected to one driver and another has two
independent drivers, the target emits the required two sideband ABI variants.
Neither variant shorts the original reader's pins internally. Actual local alias
facts, not global joins, still own local electrical connections.

The standalone public fixtures under
`tests/scala/domain-hierarchy/fixtures/fixed-split/` and maintained
`run_split_target.py` reproduce parent-to-child, child-to-parent, sibling-LCA,
independent reuse, and different-relative-ABI routes. Strict source replay uses
the real constructor plugin and matched shared classes, followed by the full
native gate, CIRCT, unchanged SystemVerilog, and four-state simulation. Independent
checks compare `split.write` and `split.enable` with the original driver while
`split.read` follows a separate reference net, including Z, full and partial X,
release, and asymmetric independent inputs. The five profiles retain exactly
2/2/3/2/3 physical module bodies and add no state. Focused native cases include
64 independent readers sharing one implementation, exact independent actuals,
transactional type/zero-driver/two-driver refusal, and opaque external refusal.

This is the fixed Bits/UInt/SInt scalar profile. Existing symbolic split sources
remain required next, unchanged; no default or maximum width is substituted.
Symbolic/array resolved payloads, generated selection-qualified routes,
internal/hierarchy-pass-through placements, and technology-specific electrical
profiles remain open. The successful fixed subset does not close those broader
requirements or claim physical timing/electrical certification.
