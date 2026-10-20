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

This contract records no executed acceptance for these next-stage features.
