# Increment 43 static shape and index contract

**Status:** bounded implementation checkpoint; no F-043 child is complete.

## Generated procedural assignment capture repair

The bounded generated lexical-storage checkpoint now routes an assignment made
through the public `Variable.:=` surface directly to the canonical analog
procedural recorder while an owned generated procedure is active. This avoids
misclassifying the same assignment as a generic generated effect before the
procedural owner can retain it. The generated-region inventory remains
fail-closed: a raw generic assignment still rejects at its capture boundary,
and a forged variable declaration is still rejected when the completed region
is reconciled with the exact retained procedural variable inventory.

This repair adds no new effect kind, structured procedural control, event or
operator placement, target lowering, eager unrolling, storage inference, or
roadmap completion. Every F-043 parent and descendant remains open.

This checkpoint turns `Vec` declaration dimensions from inert spelling into a
checked construction, bridge and native contract for fixed positive dimensions,
directly referenced bounded `Integer` parameters, and the bounded compound DAG
extension described below. A symbolic dimension is owned by the declaring
module, has a finite range whose lower endpoint is strictly positive, and marks
every canonical parameter dependency with `shape` and `rank` structural effects.

Zero-sized shapes remain illegal even though an empty `hdlRange` remains legal.
`Vec` remains structural data rather than implicit memory. General slices/views,
generated lexical storage, and Verilog-A array/generation lowering remain open
and must not be inferred from shaped port IR parsing.

Public tests cover deterministic fixed, direct-symbolic and compound snapshots,
bridge output, structural effect capture, configured native parsing when
`NODAL_NODALC` is available, and transactional rejection of zero, unbounded,
zero-capable and nonpositive compound dimensions.

## Compound static dimension DAG checkpoint

`Vec` declaration dimensions and their static indexing proof may now use a
canonical captured `Integer` expression composed of `add`, `sub`, `mul`, `div`
and `neg`. Construction reuses `StructuralBoundAnalysis` and the shared
`IterationDomain` arithmetic. It requires the expression lower bound to remain
strictly positive for every legal parameter setting, rejects overflow and
zero-capable division, retains same-identity subtraction correlation, and marks
all dependencies with the existing `shape` and `rank` effects. The serialized
dimension token is the expression's canonical semantic path, not an arithmetic
string or an evaluated default.

The bridge independently resolves that path in the existing parameter-expression
inventory, recomputes its interval, and emits the existing
`nodal.const_parameter_ref`, `nodal.const_literal` and `nodal.const_expr` DAG.
The shaped type retains the canonical expression path. Native verification finds
the directly owned static value by its semantic-path metadata, requires i64 type
and structural shape dependencies, and obtains the minimum through
`inferParameterIntegerBounds`. `nodal.shape_index` uses the same independently
verified minimum. No parallel registry, textual arithmetic parser, default
specialization or eager array expansion is introduced.

The declaration matrix has fourteen cases: four positives run twice and ten
exact-code rejections, including a 64-axis repeated compound path, missing DAG,
nonpositive result, ordinary dependency, wrong-typed carrier and a forged direct
parameter reference relabeled as a compound root. Three Python
mutation controls retain the dimension path, operator, dependency, envelope and
exact diagnostic behavior. Public Scala checks cover deterministic capture,
bridge SSA transport, configured native declaration and indexing execution, and
nonpositive construction rejection.

This extension deliberately does not admit compound factors to the current
reshape/view signature. General views and slices, target lowering, generated
storage and source/index maps for emitted Verilog-A remain later F43 work. No
parent or descendant F43 checkbox is complete.

## Native static indexing checkpoint

The existing `nodal.shape_index` operation now checks each zero-based index
against every legal shape, in addition to rank and exact element type. The
bounded native profile accepts index-typed `nodal.constant` operands. Negative,
out-of-range, unknown runtime and metadata-only claimed proofs fail with
`NODAL-SHAPE-043-002`. Malformed empty dimension tokens also fail normally.

For a symbolic extent, the verifier queries the existing parameter interval
analysis through its canonical declaration. It uses the lower bound of the
legal extent, preserves exclusive endpoints and intersecting constraints, and
never substitutes an overridable default. This is a narrow query interface to
the existing arithmetic owner, not another interval engine. Integer arithmetic,
parameter folding and storage semantics are unchanged.

The native matrix includes thirteen positives run twice and twenty-one
exact-code rejections. In addition to the literal cases, it covers a direct
ranged parameter, a parameter-minus-literal DAG and same-identity subtraction.
Expression negatives cover an unsafe upper endpoint, ordinary dependencies,
runtime values and a zero-capable divisor. The earlier first/last, singleton,
multiple-axis, nested-element, symbolic-extent, signed-extreme, 64-axis and
direct-port cases remain. Preservation checks retain the expression operators,
parameter references and literal spellings as well as operand order,
shape/element types, source identity and structural storage metadata. Mutation
controls reject changed expression operators in addition to the earlier loss,
type, value, axis, source, storage, abnormal-exit and diagnostic controls. SSA
renaming is allowed. The registered CTest retains a 60-second deadline and the
shared runner's deterministic two-execution check.

Within one index operation, symbolic minima are cached per distinct dimension;
repeated axes reuse the proof. Traversal is linear in rank plus the existing
bounded parameter analysis for each distinct symbolic extent. This statement
does not claim an optimization of unrelated module-wide shape verification.

This section records the native trust boundary; the following checkpoints add
bounded public `Vec.at` capture, bridge transport and static Integer expression
indices. Slices/views, generated lexical storage and legal target
array/generation lowering remain unfinished. Hand-authored MLIR must not be
reported as generated-Verilog-A evidence. All F43 parent and descendant
acceptance items remain open.

## Public static port indexing checkpoint

The existing public `Vec.at(...)` surface captures fixed literal indices on
fixed or directly bounded symbolic shaped input/output ports. Construction
checks rank and every literal against the minimum legal extent before publishing
the expression. Negative, end and rank-mismatched indices fail with
`NODAL-SHAPE-043-002`; no parameter default supplies an index proof.
Existing internal-wire `.at` calls retain their compatibility-only inert
expression capture and do not enter this port transport.

The snapshot retains one source-correlated index identity, its module-owned port
and ordered literals. The bridge independently rejects forged owner, input,
rank, or bounds and emits a typed `nodal.port_value`, index-typed constants and
the existing `nodal.shape_index`. Native verification resolves the direct port,
checks its exact shaped type, and then applies the existing all-legal-extents
index proof. This reuses the canonical declaration, parameter interval and
`shape_index` owners rather than adding another shape engine or registry.

This bounded profile does not yet accept internal shaped wires, slices/views,
assignments through indexed l-values, generated-instance storage, or target
array lowering. It is public Scala-to-normalized-IR evidence, not generated
Verilog-A acceptance. Every F43 parent and descendant item remains open.

## Static Integer expression index checkpoint

Public port indexing now also accepts a canonical static `Integer` index DAG
when its conservative interval is non-negative and strictly below the minimum
legal extent. Construction reuses the same captured-expression traversal and
`IterationDomain` arithmetic as compound `hdlRange` bounds. It supports direct
ranged parameters and pure `add`, `sub`, `mul`, `div` and `neg` DAGs, including
same-identity subtraction correlation. Zero-capable divisors, overflow,
unbounded parameters, unsupported/runtime operations, foreign ownership and
unsafe endpoints reject transactionally with `NODAL-SHAPE-043-002`. Parameters
that participate are marked with the existing `shape` structural effect.

Snapshots retain literal axes as canonical decimal strings and symbolic axes as
canonical declaration/expression paths. Static index expressions are roots of
the existing parameter-expression inventory; no parallel expression graph is
created. The bridge independently traverses that inventory with the shared
interval arithmetic, requires module-local structural shape dependencies, and
emits the existing `nodal.const_parameter_ref`, `nodal.const_literal` and
`nodal.const_expr` SSA DAG. Literal axes preserve their prior index-typed
`nodal.constant` form. The native `nodal.shape_index` verifier accepts exactly
those two carriers: a legacy index literal or an i64 static-expression DAG. For
i64, it independently checks direct-module ownership, structural shape
dependencies and `inferParameterIntegerBounds` before applying the all-legal-
extents bound.

Public tests exercise deterministic direct and compound expression transport,
configured native execution, construction rejection of an unsafe symbolic
endpoint, and bridge mutations for missing expression identity, changed
arithmetic and ordinary parameter classification. That checkpoint remains
indexing-only; the fixed view profile below is separate. Compound dimension
dimension DAGs are now shared with declaration extents as described above.
General slices/views, l-value indexing, generated storage and target
arrays/generation are still open, as are all parent and child F43 acceptance boxes.

## Fixed structural reshape view checkpoint

The already-frozen public `Vec.reshape(...)` surface now has a bounded production
path for fixed positive dimensions on module input/output ports. Construction
retains the source port, target dimensions and source-correlated expression
identity, and accepts the view only when independently computed source and
result element counts are equal and fit the supported signed 64-bit envelope.
Unequal fixed counts reject transactionally with `NODAL-SHAPE-043-003`.

The snapshot uses the existing construction expression identity and source map.
The bridge independently resolves the owned port, rechecks positive literal
dimensions and both products, and emits a typed `nodal.port_value` followed by
the existing `nodal.shape_view`. The view carries exact result dimensions,
`materialization = "view"`, source-mapped observability and structural storage
intent. Native verification recognizes that production materialization value
and independently requires shaped input/result types with equal element types,
an exact dimensions/result-type match, structural storage, source-mapped
observability and equal finite literal counts. The older `explicit_view`
fixture carrier is preserved unchanged for predecessor native tests.

A separate twelve-case native matrix runs four positives twice and requires
eight exact-code rejections for count mismatch, symbolic extents, attribute/type
forgeries and overflow. Three Python checker methods control inventory,
preservation and normal exact-diagnostic rejection. Public tests cover snapshot,
deterministic bridge output, four bridge forgeries and configured native
execution. The full native suite contains 145 tests at this checkpoint.

This is a structural view, not storage allocation, flattening, a memory, an
l-value slice or target lowering. At the fixed-only checkpoint, symbolic reshape
retained the exposed inert compatibility path; the bounded symbolic extension below replaces that path.
Internal shaped wires retain their prior inert path. Slice selection, compound
reshape factors, generated lexical storage and legal Verilog-A array/generation
lowering remain open. No F43 parent or descendant checkbox is complete.

## Bounded symbolic reshape and proof repair

Public port reshape supports directly owned bounded Integer parameter factors.
Construction, bridge and native verification independently compare the positive
literal product and the multiset of canonical parameter identities. Multiplicity
matters: `lanes * lanes` is not equal to `lanes`. Distinct parameters with the
same default and range are not interchangeable. Each boundary proves the maximum
element count across all legal parameter settings fits signed 64-bit metadata.

The published symbolic checkpoint required repair before qualification. Its
construction record still held Integer dimensions after the snapshot changed to
strings, preventing Scala compilation. Native proof used a set, lost repeated
factors, and did not validate symbolic ownership/ranges or their worst-case
product; the bridge also omitted the worst-case product bound. The repaired
path retains typed factors in the construction record and renders canonical
names only when the snapshot is finalized. Equality compares declaration
identities, avoiding temporary names captured before constructor naming. It uses
a multiset natively, and independently requires local structural Integer shape
parameters with positive finite ranges at both downstream boundaries. Native
bounds reuse `inferParameterIntegerBounds`; each distinct symbolic maximum is
cached within a view. No defaults are substituted and no array is expanded.

The strict native matrix now has 31 cases: nine positives run twice and twenty-two
exact-code rejections. It preserves the fixed tests and adds repeated factors,
distinct symbols, literal regrouping, 32 repeated axes, missing/foreign/ordinary
parameters, absent shape envelopes/ranges, zero-capable dimensions, dropped or
added factors and symbolic worst-case overflow. The former undeclared symbolic
positive is retained as a negative; its valid counterpart declares its parameter.
Four Python checker methods retain source/dimension/parameter/range metadata and
reject mutations, abnormal exits, empty output and unrelated diagnostics.

Public tests retain fixed reshape, add repeated symbolic factors and literal
regrouping, reject changed multiplicity, coincident defaults and overflow, mutate
bridge proof metadata, and execute fixed and symbolic documents with a configured
native compiler. These are Scala-to-IR witnesses; they do not produce target HDL.
Compound dimension DAGs are covered by the bounded extension below. General
slices/views, generated storage and legal Verilog-A arrays/generation remain
required unfinished work. No F43 checkbox is completed by this bounded repair.

### Materialization downgrade rejection

The native verifier accepts only `view` and the scalar-input legacy
`explicit_view` carrier. Changing a shaped-to-shaped operation to an unknown
materialization or relabeling it as `explicit_view` cannot skip the reshape
proof. The previous early return accepted both bypasses, including mismatched
element counts. Four added exact-code negatives cover unknown/whitespace tags,
legacy count bypass and legacy storage bypass; checker mutations independently
reject changed materialization. Existing scalar legacy fixtures remain covered
by the native predecessor suite. This repair changes no public API, parameter
evaluation, target layout or acceptance scope.

## Compound static reshape roots

The bounded structural reshape profile also accepts positive compound Integer
dimension DAGs when the source and result retain the same canonical root
identities and literal product. Construction reuses the existing
`StructuralBoundAnalysis`; the snapshot retains those expression paths in the
same parameter-expression inventory used by declarations, generated bounds and
indices. Distinct but coincident expressions are not treated as equal, and
defaults are never substituted.

The bridge independently proves each root through the shared static-bound
analysis, requires owned structural shape dependencies, emits the existing i64
constant-expression DAG, and enforces a signed-64-bit worst-case element count.
Native verification resolves each dimension path by `semantic_path`, requires a
direct-module `nodal.const_expr` DAG with structural shape dependencies, and
independently infers a strictly positive finite interval before comparing the
root multiset. Direct parameters and literals retain their established forms.

The strict native matrix adds two deterministic compound positives and five
exact-code negatives for distinct identities, missing roots, nonpositive
bounds, ordinary dependencies and a forged direct-reference root. Public tests
retain the expression inventory and configured-native path and reject a
separately captured coincident expression. This remains reshape-only: general
slices/views, l-value indexing, generated lexical storage and target
array/generation lowering remain open, and no F43 checkbox is completed.

### Compound-root dimension parsing repair

The first exact-head Scala and configured-bridge runs exposed an ambiguity in
the inherited internal `Vec(...;...x...)` snapshot spelling: splitting every
lowercase `x` also split canonical expression identities such as `.extent`.
The shared bridge parser now recognizes the longest owned declaration or static
expression identity before consuming an axis separator. Unknown or empty
dimensions still reach the existing fail-closed validation, and the snapshot
spelling for established literal and direct-parameter dimensions is unchanged.
The compound public witness explicitly retains an identity containing `x` so
this transport regression cannot silently return.

## Fixed structural flatten view checkpoint

The already-frozen public `Vec.flatten` surface now reuses the verified
structural `shape_view` path for fixed positive module-port shapes. Construction
computes the row-major element count without expanding elements, requires the
single result extent to fit the public signed 32-bit `Dimension` surface, and
records a source-correlated rank-one view. The bridge and native verifier then
independently apply the established equal-element-count, exact element-type,
structural-storage and source-mapped view checks. No new snapshot registry,
native operation or layout engine is introduced.

Public tests retain the exact `2 x 3` input and rank-one `6` result types,
deterministic bridge text and configured-native execution. The bounded symbolic
extension below replaces the prior compatibility-only inert symbolic path; this
fixed checkpoint does not pretend a parameter default is a fixed extent.

This is a bounded fixed flatten view, not general slicing, l-value selection,
target flattening, memory inference or generated-instance storage. Constant and
symbolic slices and row/plane views remain open, and no F-043 parent or
descendant checkbox is complete.

## Symbolic structural flatten multiplication proof

The already-frozen `Vec.flatten` surface now retains a rank-one structural view
for positive symbolic and compound source shapes. Construction forms one
canonical Integer multiplication DAG from the authored dimension objects and
records that root as the result dimension. It does not evaluate a parameter
default, enumerate elements or introduce a second shape registry.

Cardinality equality is deliberately narrower than general algebra. At
construction, bridge and native trust boundaries, associative multiplication
nodes are decomposed into their positive literal factors plus the multiset of
owned parameter or non-multiplicative compound-expression roots. Repeated roots
remain repeated, literal regrouping is canonical, and an independently captured
addition/subtraction/division root is still identity-sensitive. This proves
`Vec(Real, lanes, 2).flatten` has one dimension represented by the captured
`lanes * 2` root without equating arbitrary coincident expressions.

All three boundaries independently require module ownership, structural shape
dependencies, positive finite bounds and a signed-64-bit worst-case count.
Construction reuses `StructuralBoundAnalysis`; the bridge reuses its snapshot
static-bound analysis; native follows the emitted `nodal.const_expr` operands
and reuses `inferParameterIntegerBounds`. Cycles, over-depth graphs, malformed
multiplication arity, missing factors, nonpositive leaves and overflow fail
closed with the existing shape-view diagnostic families.

Public tests cover direct symbolic, repeated-factor and compound-root flatten,
deterministic bridge text, bridge mutations and configured native execution.
The strict native matrix adds three deterministic positives and two exact-code
rejections; its mutation checker changes multiplication to addition and replaces
one factor without accepting changed output. Fixed flatten and every inherited
reshape case remain intact.

This remains structural Scala-to-IR view evidence. It does not implement
constant/symbolic slices, row/plane views, l-value selection, generated lexical
storage, Verilog-A arrays/generation or target source/index maps, and it does not
complete any F-043 parent or descendant checkbox.

## Chained structural view checkpoint

The frozen `reshape` and `flatten` surfaces now accept the result of an earlier
verified structural view. Construction records the immediate predecessor view
identity rather than collapsing the chain back to its port or inventing a new
aggregate registry. Each step independently proves equal canonical cardinality
with the existing literal/static-expression signature, structural parameter
effects, ownership and signed-64-bit worst-case checks. A generic expression
that merely claims `shape_view` spelling but has no captured view record remains
rejected.

The bridge validates views in capture order, requires every expression input to
be an earlier view in the same module, and rejects missing, reordered, cyclic or
cross-owner chains. It emits one `nodal.port_value` at the root followed by SSA-
connected `nodal.shape_view` operations. Native verification therefore checks
each actual shaped-to-shaped edge with the existing type, dimensions, storage,
source-map and cardinality contract; no bridge-only proof replaces that trust
boundary.

Public tests cover fixed reshape-to-reshape, symbolic reshape-to-flatten, an
unequal-count construction rejection, reordered snapshot rejection, retained
immediate-input paths, deterministic emission and configured native execution.
The implementation traverses the recorded chain once at bridge validation and
emission and does not enumerate elements or specialize parameter defaults.

This checkpoint does not add row/plane/slice syntax, l-value selection, target
array lowering, generated lexical storage or Verilog-A generation. Those
requirements and every F-043 parent/descendant checkbox remain open.

## Structural view indexing checkpoint

The frozen public `Vec.at(...)` surface now accepts an earlier verified
structural reshape or flatten result in the same module. Construction retains
that immediate view identity, checks rank and every literal/static Integer
index against the view's result dimensions, and records the index only after
the predecessor has passed the existing cardinality, ownership and parameter
proofs. A generic expression that merely has `Vec` type is not promoted to a
structural carrier, and internal shaped wires keep their compatibility-only
inert path.

The bridge independently requires the input identity to name a captured view
owned by the same module, uses its verified result dimensions for the all-legal-
extents index proof, and emits `nodal.shape_index` directly from the already
emitted `nodal.shape_view` SSA result. It does not reread the root port or
collapse the view chain. Native verification therefore checks the actual
shaped SSA edge with the existing `shape_index` verifier. Fixed and symbolic
public witnesses cover deterministic capture/emission, exact view-to-index SSA
connectivity, configured native execution, bounds rejection, and missing-view
snapshot rejection.

This is element indexing across an existing structural view, not a row/plane
or general slice result, l-value selection, target array lowering, generated
lexical storage or Verilog-A generation. Those requirements and every F-043
parent/descendant checkbox remain open.

## Structural row and plane view checkpoint

The reserved public `Vec.row(index)` and `Vec.plane(index)` surfaces now retain
rank-reducing structural views for rank-two and rank-three values respectively.
They reuse the existing `shape_index` construction record, bridge owner and
native operation: the selected leading axis is proven against every legal
extent while the result carries the exact unselected dimensions and original
element type. A row or plane may consume either a module port or an earlier
verified structural reshape/flatten view without rereading or collapsing its
immediate SSA predecessor.

Construction rejects empty, excessive or surface-incompatible rank, unsafe
literal/static Integer indices and any result type that does not retain the
exact remaining axes. The snapshot records those result dimensions explicitly,
so the bridge independently rejects a scalar relabeling or a forged prefix
shape. Native verification repeats the rank, element-type, remaining-dimension
and all-legal-extents bounds checks on the actual shaped SSA edge. The native
matrix covers fixed row/plane, symbolic leading extents, empty/excessive rank,
wrong remaining dimensions and wrong result element type.

This bounded checkpoint does not add constant or symbolic range slices,
l-value selection, storage allocation, eager element expansion, target array
lowering, Verilog-A generation or source/index maps. Those requirements and
every F-043 parent/descendant checkbox remain open.

## Composing structural prefix selections and views

Rank-reducing `row`/`plane` results now compose with later structural
`reshape`/`flatten` and further index selections. Fixed and symbolic extents
retain their original proofs; the bridge emits each operation from its immediate
SSA predecessor rather than returning to the root port. Scalar selections,
foreign owners and generic expressions merely carrying a shaped type cannot
be promoted into structural carriers.

Construction keeps the existing capture records in insertion-ordered keyed
maps. The bridge derives one dependency schedule from those records, with
input edges and original intra-kind capture-order edges. An iterative indegree
queue rejects missing predecessors, cycles and reversed same-kind snapshots;
validation and emission consume the same schedule. This avoids a second semantic
registry or native schema change. Scheduling requires O(V + E) temporary space
and O(V log V) time; preexisting per-operation declaration lookup scans remain.
No end-to-end performance improvement is claimed.

Focused local checks passed: five new methods and eight affected predecessors
in `Increment43ShapeContractTests`, thirteen total with zero failures. Seven
composed graphs also passed configured `nodalc` verification, including a
130-operation alternating chain. Checks cover fixed/symbolic composition,
exact SSA dependencies, repeat determinism, missing/reordered/cyclic inputs,
scalar carriers, foreign ownership and forged dimensions/element types.
Pinned Scalafmt and whitespace checks passed. These results do not close
F-043's general range slices, l-value selection, target array layouts or
other unfinished children; no CI was run.

### Reproduce the structural composition checks

The local wrapper selects pinned Mill 1.1.7 and isolates caches under `.work`.
With the pinned native targets built, the exact acceptance command was:

```sh
export NODAL_NODALC="$PWD/.work/native-release/bin/nodalc"
export NODAL_TRANSLATE="$PWD/.work/native-release/bin/nodal-translate"
.work/scala-validation/mill-jvm.sh core.scala.testkit.test.testOnly nodal.internal.testkit.Increment43ShapeContractTests -- 'nodal.internal.testkit.Increment43ShapeContractTests.{composed structural shapes preserve immediate deterministic SSA edges,composed structural shapes traverse long alternating chains without expansion,composed structural shapes reject uncaptured foreign and forged typed carriers,composed structural shapes reject malformed dependency snapshots,configured native accepts composed structural shape edges,public row and plane views retain exact remaining dimensions,public row and plane views reject wrong rank bounds and forged result shape,chained reshape and flatten retain structural view ownership,indexing a structural view retains its immediate SSA predecessor,fixed and symbolic flatten reuse the structural shape-view proof,bridge emits and independently validates fixed structural reshape,bridge rejects forged symbolic reshape proofs and worst-case overflow,configured native accepts public fixed and symbolic reshape transport}'
```

## Read-only structural range slices

The [additive slice gate](../design-gates/NodalStructuralSlices-DG-v0.1.md)
selects `slice(lower, upperExclusive, axis = 0)` under the owner's Foundation
implementation request. It is a half-open, unit-stride, rank-preserving alias
view, with a zero-based static axis. Literal zero is a valid endpoint. Empty,
reversed, negative and unproven ranges reject; zero-sized shaped values remain
illegal. Fixed and bounded symbolic endpoints retain their exact canonical
length: literal difference, the upper identity for literal-zero lower, or the
ordered subtraction DAG. Defaults and coincident intervals are not identities.

The existing shape-view record and registry own one optional typed slice payload.
Capture validates before registering a slice or marking structural parameter
effects. The bridge's existing dependency schedule retains immediate SSA edges
through slices, rows/planes, reshape/flatten and indexing. The native
`nodal.shape_slice` operation independently proves endpoint/length provenance,
remaining axes, element type, signed-64 cardinality and source ownership. It uses
the existing parameter bounds and static-dependency helpers. Relaxing reshape
cardinality equality or pretending a slice is a rank-reducing index was rejected;
neither preserves the actual range/alias contract.

Supported sources are module structural Vec ports and captured non-scalar
structural results, including Real/f64 and existing supported scalar element
types. Only interval-safe inequalities and exact source-extent identity are
accepted; arbitrary correlated symbolic inequalities remain conservative
rejections. No element expansion, storage allocation, new interval engine or
parallel mutable shape registry is introduced. Per-operation native identity
and uniqueness scans and repeated proof setup may be quadratic across a large
module; the dependency scheduler's complexity is not a whole-pipeline bound.
No measured speedup is claimed.

Native-verified structural IR is not target array lowering. The public mixed
digital/analog target witness rejects with `NODAL-VERIFY-ANALOG-004` for its
missing explicit bridge before layout lowering; this is not evidence for a
slice-specific target capability diagnostic. Writable/l-value selections, overlap/driver rules,
shaped conservative terminals and remaining target layout/source-map obligations
stay open under F-043. This checkpoint cannot complete the parent or those
required children.

### Focused slice acceptance and reproduction

Nine public slice methods passed with both native tools configured (69 seconds),
including seven independently native-verified public graphs. Six directly affected
composition/reshape/flatten predecessors passed (64 seconds). The native slice
matrix passed 46 cases (13 positives repeated and 33 exact-diagnostic negatives);
its slice/index/view integration tests passed 3/3, the shape-contract predecessor
passed 1/1, and the Python fixture/checker tests passed 15/15. Pinned formatting,
API JSON, additive gate/document checks and whitespace checks passed. No CI ran.

The exact Scala selectors used were:

```sh
export NODAL_NODALC="$PWD/.work/native-release/bin/nodalc"
export NODAL_TRANSLATE="$PWD/.work/native-release/bin/nodal-translate"
.work/scala-validation/mill-jvm.sh core.scala.testkit.test.testOnly nodal.internal.testkit.Increment43ShapeSliceTests -- 'nodal.internal.testkit.Increment43ShapeSliceTests.{fixed slices preserve rank singleton extents and immediate source identity,symbolic slices retain canonical subtraction and full extent identities,slice chains compose through plane row reshape flatten and scalar selection,invalid slice bounds owners and element carriers reject before publication,caught slice failures publish neither a view nor structural endpoint effects,bridge independently rejects forged slice shape bounds length and source graphs,slice boundaries preserve signed64 counts and reject foreign or coincident identities,configured mixed-signal target rejects missing explicit bridge before layout lowering,configured native verifies public fixed symbolic and composed slice transport}'
.work/scala-validation/mill-jvm.sh core.scala.testkit.test.testOnly nodal.internal.testkit.Increment43ShapeContractTests -- 'nodal.internal.testkit.Increment43ShapeContractTests.{composed structural shapes preserve immediate deterministic SSA edges,composed structural shapes reject malformed dependency snapshots,configured native accepts composed structural shape edges,fixed and symbolic flatten reuse the structural shape-view proof,bridge rejects forged symbolic reshape proofs and worst-case overflow,configured native accepts public fixed and symbolic reshape transport}'
```

Native acceptance used the built targets and focused CTest selectors:

```sh
ctest --test-dir .work/native-release --output-on-failure -R '^nodal.native.shape-(slice|index|view)-integration$'
ctest --test-dir .work/native-release --output-on-failure -R '^nodal.native.shape-contract-integration$'
python3 -m unittest tests.compiler.test_increment43_shape_slice tests.compiler.test_increment43_shape_index tests.compiler.test_increment43_shape_view tests.compiler.test_increment43_shape_contract
```

## Conservative endpoint-array ownership work

The [node-array gate](../design-gates/NodalConservativeNodeArrays-DG-v0.1.md)
records the next coordinated production contract. Conservative arrays are owned
node declarations, not Vec(Real) ports. Static scalar selections retain one
physical endpoint identity plus separate authored source occurrences. The shared
declaration/shape/index owners, native conservative connectivity and actual target
layout are extended together. The focused results below describe this finite
profile, not completion of all F-043 array obligations.

The implementation uses a distinct shaped conservative declaration instead of
widening scalar NodeOp, so existing generated scalar-node occurrence ownership
remains intact. Shape type dimensions are authoritative. Existing static-bound
proof helpers resolve positive extents and checked total cardinality for native
verification and backend rendering; a second dimension parser/interval engine is
not appropriate. Canonical alias normalization precedes connectivity and probe
grouping, while read-only verification reconstructs identity from actual operands.
The native comparison of selected endpoint tuples is O(S² × rank) for S authored
selections; it does not enumerate the array cardinality. Static proofs and source
identity scans retain their existing costs. There is no general speedup claim.
Equivalent selectors merge provenance and remove only newly dead validated legacy
index literals associated with the removed selector; this is not general DCE.
Existing potential/self-connection behavior and oriented-flow self-access rejection
remain scalar rules after canonicalization, not array-specific substitutes.
Selected endpoints compose with existing immediate-child scalar terminals; they
do not create a new hierarchy-array ABI or relax unsupported connection lowering.

Target reparse checks emitted structural syntax, declared arrays and legal static
references. Per-axis semantic bounds remain the responsibility of native/target
lowering validation. It must not claim a second proof of arbitrary correlated
symbolic row-major bounds or introduce an unrelated algebra engine for that claim.

### Focused node-array acceptance

Nineteen public node-array methods passed with the current native tools and the
actual default gate pipeline (67 seconds). Six public witnesses retain source
MLIR, normalized MLIR, Verilog-A and construction artifacts: fixed, symbolic,
inline literal, inline compound, child-local declarations and a parent selected
endpoint connected to an immediate-child scalar terminal. All six normalized
documents and targets were byte-identical on repeat. The symbolic target
keeps `rows` and `lane`, using the index `((lane * 3) + 2)` rather than defaults.
Repeated selections share physical endpoints while preserving authored source spans.

The dedicated native node-array unit passed 37 checks; the existing conservative
connectivity unit and all four affected shape integration tests also passed. The
backend unit executable passed with its scalar/generated predecessors plus new
fixed/symbolic, alias, hierarchy, source-owner and target-negative cases. Shared
hierarchy syntax passed 62 checks. Actual fixed/symbolic translator emission and
fixed Verilog-AMS emission passed; fixed repeated CLI emission was byte-identical.
A trillion-element shape remained one compact declaration: this checks absence of
compiler lane expansion, not simulator capacity or a performance improvement.

Review found and fixed two concrete trust-boundary cases: deduplicated selectors
could leave legacy index literals that the backend rejected, and an RHS-only bare
array access could evade bracket-only target checks. Exact regressions now cover
both, together with malformed scalar/array hierarchy actuals and embedded NULs.
Independent OpenVAF compilation and numerical simulation were not run.

Exact primary commands (outputs remain under ignored `.work`):

```sh
export NODAL_NODALC="$PWD/.work/native-release/bin/nodalc"
export NODAL_TRANSLATE="$PWD/.work/native-release/bin/nodal-translate"
.work/scala-validation/mill-jvm.sh core.scala.testkit.test.testOnly nodal.internal.testkit.Increment43NodeArrayTests -- nodal.internal.testkit.Increment43NodeArrayTests
ctest --test-dir .work/native-release --output-on-failure -R '^nodal.native.(node-array|conservative-connectivity)-unit$'
ctest --test-dir .work/native-release --output-on-failure -R '^nodal.native.shape-(contract|index|view|slice)-integration$'
.work/native-release/core/compiler/test/Unit/nodal-backend-unit-tests
.work/native-release/core/compiler/test/Unit/nodal-hierarchy-syntax-unit-tests
.work/native-release/bin/nodal-translate --nodal-to-verilog-a .work/f43-backend-array-fixed.mlir -o .work/f43-backend-array-fixed.va
.work/native-release/bin/nodal-translate --nodal-to-verilog-a .work/f43-backend-array-symbolic.mlir -o .work/f43-backend-array-symbolic.va
```

Eleven directly affected Scala predecessors passed (hierarchy 3, scalar RC 2,
artifacts 2, shape/index 2 and slices 2). Pinned Scala/C++ formatting, changed
Markdown/link checks, additive gate/contribution policy, API JSON parsing and
whitespace checks passed. No CI was run. Exact predecessor selectors were:

```sh
.work/scala-validation/mill-jvm.sh core.scala.testkit.test.testOnly nodal.internal.testkit.AnalogHierarchyConnectivityIntegrationTests -- 'nodal.internal.testkit.AnalogHierarchyConnectivityIntegrationTests.{direct ports and reversed operator share explicit and typed connect topology,public Scala visibility does not permit child or sibling internal endpoints,an endpoint from another construction transaction is rejected}'
.work/scala-validation/mill-jvm.sh core.scala.testkit.test.testOnly nodal.internal.testkit.RcVerticalSliceTests -- 'nodal.internal.testkit.RcVerticalSliceTests.{Scala RC compiles through nodalc to exact Verilog-A,unsupported analog operator fails before native launch}'
.work/scala-validation/mill-jvm.sh core.scala.testkit.test.testOnly nodal.internal.testkit.ReproducibilityContractTests -- 'nodal.internal.testkit.ReproducibilityContractTests.{canonical artifacts survive repeated construction and valid traversal orders,verified MLIR HDL and manifest are byte-identical across work directories}'
.work/scala-validation/mill-jvm.sh core.scala.testkit.test.testOnly nodal.internal.testkit.Increment43ShapeContractTests -- 'nodal.internal.testkit.Increment43ShapeContractTests.{configured native accepts composed structural shape edges,bridge rejects forged static-index ownership rank and bounds}'
.work/scala-validation/mill-jvm.sh core.scala.testkit.test.testOnly nodal.internal.testkit.Increment43ShapeSliceTests -- 'nodal.internal.testkit.Increment43ShapeSliceTests.{caught slice failures publish neither a view nor structural endpoint effects,configured native verifies public fixed symbolic and composed slice transport}'
```

## Writable Real storage checkpoint

The additive writable-storage contract is recorded in
[`NodalWritableRealArrays-DG-v0.1`](../design-gates/NodalWritableRealArrays-DG-v0.1.md).
This checkpoint adds a capability wrapper over actual procedural variables,
lexical typed projections, explicit immutable reads and exact-shaped fill/copy
values. It does not make arbitrary expression views writable. Each consuming
assignment snapshots its RHS before writing any destination lane; a reused
construction expression is evaluated at its new procedural use.

Native `AnalogStorageModel` derives ownership, source maps and coverage from
actual SSA roots and projections, without a parallel declaration registry.
Reshape preserves ordinal order. Prefix selections and leading-axis slices use
a small sum-of-products normal form over actual static identities to recognize
contiguous aliases, including symbolic row/slice equivalence. Other selections
retain their validated projection ancestry; fixed contiguous regions additionally
use checked endpoint/cardinality proofs. No default parameter substitution,
lane enumeration or general symbolic algebra is used. Whole initialization and
finite proven literal writes remain separate coverage facts.

The target map program retains each source projection and uses an ordinal stage
per projection rather than recursively expanding composed indexing formulas.
Shape semantics use checked signed-64 cardinality; target declarations and all
index intermediates separately require the supported signed-32 target range.
These are compactness and resource bounds, not measured performance claims.
Scala region equality and ordering use exact locally interned DAG keys rather
than recursively expanding shared coordinate subexpressions. Keys retain complete
static tokens and do not rely on hash collision resistance or a persistent
registry. Repeated proof setup, static-identity lookup, ancestor recomputation
and retained ancestor key strings can still cost more than linear time or memory
in authored depth; recursive traversal also retains an authored-depth resource
limit.

Persistent root shaped initializer emission and selected-array reads in
control/event payloads remain explicit capability rejections in this checkpoint.
The former needs declaration-equivalent lifetime semantics, and the latter needs
actual typed bindings that preserve reevaluation and short-circuit behavior.
Event-local initialization and array operations within existing scalar-controlled
bodies do not remove those remaining F43 requirements. No Foundation parent
checkbox is closed by this checkpoint.

### Focused writable-storage native acceptance

The shared descriptor passed 21 checks, including symbolic row versus flattened
leading-slice coverage, distinct sibling regions, lexical escape and forged
ownership, trillion-lane compactness, signed-64 maximum and overflow rejection.
The independent native semantic matrix passed 41 cases, with exact negative
diagnostics, deterministic roundtrips and the actual default gate pipeline for
every positive. Fifteen selected registered native tests passed, including the
writable matrix and affected procedural, event and shape predecessors.

The writable backend unit passed fixed and symbolic overlapping copies,
noncontiguous composed maps, generated lexical storage, dimensioned Real,
compound extents, 48 mapping stages and signed-32 maximum compact storage.
Four independently native-valid unsupported target cases produced causal
rejections while preserving sentinel output. Five actual translator CLI outputs
were byte-identical to the unit outputs; mapped Verilog-AMS emission also passed.
Strict event syntax passed 179 checks, with noise 24 and transfer 40; the existing
scalar/generated/node-array backend executable passed its assertions.

The target profile currently requires an event-containing procedure. Ordinary
event-free shaped storage remains a capability rejection and required F43 work.
The finite target reparser validates emitted grammar and scalar/array roles; it
does not replace native correlated symbolic bounds proofs. No simulator or
numerical qualification was run.

```sh
PYTHONPATH=.work/python-tools .work/python-tools/bin/cmake --build .work/native-release --target nodalc nodal-translate nodal-storage-model-unit-tests nodal-backend-unit-tests nodal-writable-array-backend-unit-tests nodal-event-reparse-unit-tests nodal-noise-reparse-unit-tests nodal-transfer-reparse-unit-tests -j 3
.work/native-release/core/compiler/test/Unit/nodal-storage-model-unit-tests
.work/native-release/core/compiler/test/Unit/nodal-writable-array-backend-unit-tests .work/writable-array-backend
.work/native-release/core/compiler/test/Unit/nodal-event-reparse-unit-tests
.work/native-release/core/compiler/test/Unit/nodal-noise-reparse-unit-tests
.work/native-release/core/compiler/test/Unit/nodal-transfer-reparse-unit-tests
.work/native-release/core/compiler/test/Unit/nodal-backend-unit-tests
```

```sh
PYTHONDONTWRITEBYTECODE=1 python3 tests/compiler/fixtures/increment43/run_writable_array_matrix.py --nodalc .work/native-release/bin/nodalc --work-dir .work/writable-native-final-matrix
ctest --test-dir .work/native-release --output-on-failure -R '^nodal.native.(writable-array-integration|analog-events-matrix|analog-event-direction-review|analog-procedural-(roundtrip|rejects-read|rejects-owner|rejects-type)|analog-control-flow-(roundtrip|rejects-missing-else|rejects-zero-trip|rejects-continue-path|rejects-forward-reference)|shape-(index|view|slice)-integration)$'
for name in fixed symbolic mapped generated deep; do
  .work/native-release/bin/nodal-translate --nodal-to-verilog-a .work/writable-array-backend/$name.mlir -o .work/writable-array-backend/$name.cli.va
  cmp .work/writable-array-backend/$name.va .work/writable-array-backend/$name.cli.va
done
```

Focused review found and fixed unchecked generic-expression fallback after
Integer arithmetic rejection. The target parser now validates nested checked
Integer operations inside comparisons, ternaries and function arguments without
retrying invalid numeric expressions. In this finite emitted profile, invalid
numeric dead ternary branches also reject in strict Integer context. Plain overflow and division-by-zero
regressions cover the earlier gap; valid Boolean forms and Real arithmetic remain
covered. Generated induction names are reserved alongside authored names, with
three adversarial carrier/counter/map-name collision witnesses. An affected
module-level classification condition now sends every genvar declaration through
the existing genvar grammar, including authored names with the temporary prefix.

Inspecting real public artifacts exposed an inherited source-capture defect:
constructor call-site preference could relabel an earlier event-local variable
when a later variable copied its value. The shared capture-site resolver now
prefers a nearer lexical-body frame from the exact same user class, retaining
the constructor fallback and unrelated helper behavior. A regression checks
separate `cells` and `copied` authored paths and their exact declaration lines;
actual storage identities remain distinct throughout.

### Focused writable-storage public acceptance

Thirteen configured public methods passed with both current native tools,
including nine positive models through the default semantic pipeline and
byte-identical repeated Verilog-A emission. The persistent-root target-negative
case checks its exact lifetime diagnostic. Public cases cover deferred overlap,
symbolic quantity units, noncontiguous maps, complete literal initialization,
nested fill projection, branch intersection, generated lexical ownership and
symbolic row/slice aliases in both directions. The tracked 25-level coverage
case checks exact DAG equality, sharing independence and near-equal rejection.
Five retained public witness triplets contain source MLIR, normalized MLIR and
Verilog-A for fixed overlap, symbolic voltage, noncontiguous, generated and
nested-fill cases. Source names and declaration lines were checked after the
capture-site repair. These are compiler structural checks, not numerical models
validated by a simulator.

```sh
export NODAL_NODALC="$PWD/.work/native-release/bin/nodalc"
export NODAL_TRANSLATE="$PWD/.work/native-release/bin/nodal-translate"
.work/scala-validation/mill-jvm.sh core.scala.testkit.test.testOnly nodal.internal.testkit.Increment43WritableArrayTests -- nodal.internal.testkit.Increment43WritableArrayTests
```

Eleven directly affected Scala predecessor methods passed: procedural construction
3, control-flow construction 3, bridge chronology 2, immutable shape composition
2 and signed-64/identity slice boundaries 1. After the shared source-capture fix,
five SemanticOrigin methods and three scalar source/serialization methods passed.
Pinned Scala/C++ formatting, API JSON parsing, changed Markdown/link checks,
additive gate/contribution policy and whitespace checks passed. No CI was run.

```sh
.work/scala-validation/mill-jvm.sh core.scala.testkit.test.testOnly nodal.internal.testkit.SemanticOriginTests -- 'nodal.internal.testkit.SemanticOriginTests.{semantic names replace traversal-counter-only names,expression source maps survive inlined origins,same-basename source files use owner context,authored declaration names outrank unrelated aliases,inherited allocations retain the declaring constructor source}'
.work/scala-validation/mill-jvm.sh core.scala.testkit.test.testOnly nodal.internal.testkit.ScalaToMlirBridgeTests -- 'nodal.internal.testkit.ScalaToMlirBridgeTests.{"analog procedural IR retains order, source locations, and serialization",nested procedural scopes preserve declaration and assignment chronology,initializing assignments precede dependent declarations independent of provenance}'
```

## Event-independent procedural admission

The next required F43 extension is governed by
[`NodalWritableRealArrays-DG-v0.2`](../design-gates/NodalWritableRealArrays-DG-v0.2.md).
The shared native captured-expression validator applies to ordinary and
event-containing procedures: initializer/assignment values, structured conditions,
case selectors and loop bounds. Legacy guarded assignments retain their existing
native inventory checks and target rejection; this extension does not claim full
guard-source validation or new guarded assignment lowering. Event placement
checks remain separate. Previously,
event-free payloads bypassed this validator; focused before-change probes accepted
missing source payloads, wrong/unused captures, wrong initializer units and forged
static conditions/loops. Those inherited acceptances are not semantic evidence.
Canonical positive fixture repairs preserve their intended procedural behavior.

Target admission now uses owner/control/storage restrictions shared with ordinary
procedure rendering instead of generated operation-count recognition. Ordinary statements retain authored execution order in the same analog
process, using the existing fresh snapshots and compact map/copy loops. Existing
bounded-loop/history/operator restrictions remain; no artificial event wrapper
or root-initializer reset is introduced. Persistent initializer language-profile
compatibility is a separate unresolved target contract.

### Concrete F48 array consumer handoff

A focused consumer probe on 2026-10-18 establishes a known tool limitation,
separate from the compiler structural acceptance above. Official OpenVAF 23.5.0
and OpenVAF-reloaded 20260616-3-g0e83f1ed both compiled a scalar declaration/control
model to OSDI, but rejected uninitialized, list-initialized, literal-replicated
and symbolic-replicated Real array declarations at `[` with exit 65. Actual
retained public `fixed-overlap` and `symbolic-voltage` emitted witnesses also
failed at their Real array declarations with both tools. No array model compiled
successfully and no simulation was run. These results are not a replication-only
failure or a blanket claim about other tool versions/vendors.

Tool executable SHA256 identities:

- OpenVAF 23.5.0: `6918195bc6cca54016095923bea190f7a1d96dd8b062104c602e8c28578cb5e3`
- Reloaded: `b117284d8f871dbf54609ab009cdff819f443d8d33064111b8b0782af5c7e931`

Actual public target SHA256 identities from the writable checkpoint:

- Fixed overlap: `6554b61ff2f278197eafe57f45a96f06335fa2795f419d0ad4540ea308872077`
- Symbolic voltage: `6194f9d9d1f5c6515f9568321fc293fe7e9731ac7006abb9ed6133d6dc94ef51`

Sources remain the public models in `Increment43WritableArrayTests.scala`;
retained source/normalized MLIR/target triplets and exact tool/input hashes,
commands, exit codes and diagnostics are under
`.work/f43-array-initializer-profile/` and the writable witness directory.
Each consumer invocation was `<pinned-openvaf> <actual-source.va> -o <case.osdi>`.
F48 must regenerate from its reviewed source revision and preserve these blocked
classifications until an accepting tool/profile is actually demonstrated.

The official [OpenVAF compliance description](https://openvaf.semimod.de/docs/details/verilog-a-standard/)
identifies its Verilog-AMS 2.4 analog subset. Language support and tool support
must remain distinct: [VAMS 2.4](https://accellera.org/images/downloads/standards/v-ams/VAMS-LRM-2-4.pdf)
section 3.4.8, pages 32–33, already demonstrates replicated mutable Real array
declaration initialization; section 4.2.14, pages 60–61, specifies assignment
pattern replication. This syntax is not newly introduced by the 2023 edition.
Current public backend selection names Verilog-A/Verilog-AMS, not OpenVAF, and
contains no enforced tool-profile attribute. A future explicit OpenVAF request
must use pinned capability detection/fail-closed consumer classification before
launch, rather than assume generic language emission implies tool acceptance.
F43 D.1/D.2 and the lightweight handoff policy keep later tool execution from
becoming a reverse dependency of compiler structural work.

### Focused event-free native and target acceptance

The shared semantic matrix passed 49 cases with exact roundtrip and actual
default-gate positives. Six previously accepted forged event-free payloads now
reject with exact `NODAL-ANALOG-037-010`. Thirty-two directly affected scalar and
control-flow native tests passed, plus 21 descriptor and 37 node-array checks.
Matched-tool event predecessors passed 48 semantic checks, 18 lowering cases
and 52 direction cases. The old invalid-condition expectation now pins the
shared validator's earlier precise rejection; the old guarded ordinary fixture
still rejects for guard/analysis legalization with unchanged-output checks.

The inherited backend executable passed unchanged. Writable target tests passed
ordinary fixed, symbolic and generated bodies, native-valid guard/analysis
negatives with exact diagnostics/sentinels, and preserved generated loop/history
rejections. Event syntax 179, noise 24 and transfer 40 checks passed. Three new
ordinary artifacts were byte-identical through actual CLI emission; symbolic
ordinary Verilog-AMS emission/reparse also passed. Generated runtime control
still has a finite single-nonnested-loop/topology profile; this checkpoint does
not claim general generated-control support.

```sh
source .work/nodal-toolchain-env.sh
PYTHONDONTWRITEBYTECODE=1 python3 tests/compiler/fixtures/increment43/run_writable_array_matrix.py --nodalc .work/native-release/bin/nodalc --work-dir .work/event-free-native-after
ctest --test-dir .work/native-release --output-on-failure -R '^nodal.native.analog-(procedural|control-flow)-'
ctest --test-dir .work/native-release --output-on-failure -R '^nodal.native.(analog-events-matrix|analog-event-direction-review)$'
.work/native-release/core/compiler/test/Unit/nodal-storage-model-unit-tests
.work/native-release/core/compiler/test/Unit/nodal-node-array-unit-tests
PYTHONPATH=.work/python-tools .work/python-tools/bin/cmake --build .work/native-release --target nodal-translate nodal-backend-unit-tests nodal-writable-array-backend-unit-tests nodal-event-reparse-unit-tests nodal-noise-reparse-unit-tests nodal-transfer-reparse-unit-tests -j 3
.work/native-release/core/compiler/test/Unit/nodal-writable-array-backend-unit-tests .work/event-free-backend
.work/native-release/core/compiler/test/Unit/nodal-backend-unit-tests .work/event-free-backend
.work/native-release/core/compiler/test/Unit/nodal-event-reparse-unit-tests
.work/native-release/core/compiler/test/Unit/nodal-noise-reparse-unit-tests
.work/native-release/core/compiler/test/Unit/nodal-transfer-reparse-unit-tests
for name in ordinary-fixed ordinary-symbolic ordinary-generated; do
  .work/native-release/bin/nodal-translate --nodal-to-verilog-a .work/event-free-backend/$name.mlir -o .work/event-free-backend/$name.cli.va
  cmp .work/event-free-backend/$name.va .work/event-free-backend/$name.cli.va
done
```

Five final configured public methods passed against the matched native tools:
three ordinary positive models with repeated identical target emission, five
construction negatives, two persistent-root target negatives and the previous
nine-event-model integration predecessor. Actual source/normalized MLIR and
Verilog-A triplets are retained under
`.work/scala-validation/writable-array-ordinary-witnesses/`. Assertions inspect
actual operations rather than duplicated module inventories and pin deferred
read-before-write order, symbolic noncontiguous mapping and owned generated
declaration placement. No event or reset wrapper is synthesized. Focused
architecture review found no blocker; formatting, changed document/gate and
whitespace checks passed. No CI or simulator qualification was run.

```sh
.work/scala-validation/mill-jvm.sh core.scala.testkit.test.testOnly nodal.internal.testkit.Increment43WritableArrayTests -- 'nodal.internal.testkit.Increment43WritableArrayTests.{ordinary arrays retain source order and typed event-free snapshots,ordinary arrays native verify and emit without synthesized events,ordinary array placement and captured reads retain causal rejection,persistent root fill remains explicit target capability rejection,public fixed and symbolic event arrays native verify and emit deterministically}'
```

## Ordinary persistent shaped declaration initialization

This target extension is governed by
[`NodalWritableRealArrays-DG-v0.3`](../design-gates/NodalWritableRealArrays-DG-v0.3.md).
It uses VAMS2.4 constant replicated declaration assignment for non-generated
procedure roots emitted at module scope, including roots of event-containing
procedures. This preserves declaration initialization timing rather than adding
an event reset or an assignment on every analog evaluation. Independent
elaboration/analysis/sweep behavior remains governed by the language and selected
consumer; persistent does not mean once forever.

The target derives the static scalar from the native-verified uniform fill and
actual SSA projection chain, with replication count from the destination shape.
Initializer-only carrier suppression follows actual uses. Shared native-valid
materializations remain, and snapshot freshness is not broadened merely to
manufacture sharing. Existing signed target bounds, static/no-mutable-read
initialization proofs and exact units/shapes remain unchanged. Generated root
initialization still rejects pending its separate per-occurrence lifetime work;
lexical/event-local initialization retains authored scope-entry execution.

The concrete OpenVAF failures above remain applicable: this is language-structural
lowering, not a successful independent array compile or simulator qualification.

Focused native validation passed: the strict event/declaration parser runs
274 cases (179 inherited and 95 new), the writable and inherited backend units
pass, and noise/transfer parsers retain 24/40 passing cases. Nine CLI outputs
match the unit outputs exactly (six Verilog-A and three Verilog-AMS). Two
generated-root CLI negatives retain the lifetime diagnostic and emit no output.
The writable unit covers fixed and symbolic quantity roots, event-containing
roots, view destination cardinality, native-valid shared scalar materialization,
mutable-read/freshness rejection and signed-32 boundaries. No numeric simulator
claim follows from these structural checks.

```sh
PYTHONPATH=.work/python-tools .work/python-tools/bin/cmake --build .work/native-release --target nodal-translate nodal-backend-unit-tests nodal-writable-array-backend-unit-tests nodal-event-reparse-unit-tests nodal-noise-reparse-unit-tests nodal-transfer-reparse-unit-tests -j 3
.work/native-release/core/compiler/test/Unit/nodal-writable-array-backend-unit-tests .work/persistent-initializer-backend
for target in nodal-event-reparse-unit-tests nodal-noise-reparse-unit-tests nodal-transfer-reparse-unit-tests nodal-backend-unit-tests; do
  .work/native-release/core/compiler/test/Unit/$target
done
bash .work/check-persistent-initializer-cli.sh
```

Architecture review confirms that the declaration path follows actual fill/view
SSA edges, keeps the destination cardinality and preserves shared ordinary
consumers. The suppression worklist is linear in its candidate use edges; this
is not a whole-pipeline complexity claim. The target parser uses an iterative
identity comparison, folds only fully concrete checked arithmetic and never
substitutes parameter defaults. Correlated symbolic positivity remains the
native verifier's responsibility. Generated initialization, selected control/
event references, conservative port-array mappings and broader generated
state/topology remain open F43 work.

Five configured public methods passed against the matched native tools. Three
actual public models cover ordinary fixed roots, roots in event-containing
procedures and symbolic quantity/view-backed roots; each uses its matching
backend profile and repeated identical Verilog-A/Verilog-AMS output. Assertions
pin module-scope declaration placement, destination count/value, absence of
synthetic runtime resets and retained read/copy/mutate order. Generated-root
causal rejection and the ordinary/event-array predecessor selections also pass.
An initial fixture incorrectly passed a Verilog-A-profile document to the AMS
translator; the expected profile rejection was corrected by rendering the
snapshot for each requested backend, then rerunning all five methods. It was
not treated as semantic acceptance.

```sh
export NODAL_NODALC="$PWD/.work/native-release/bin/nodalc"
export NODAL_TRANSLATE="$PWD/.work/native-release/bin/nodal-translate"
.work/scala-validation/mill-jvm.sh core.scala.testkit.test.testOnly nodal.internal.testkit.Increment43WritableArrayTests -- 'nodal.internal.testkit.Increment43WritableArrayTests.{persistent root fills retain static units and structural view sources,persistent root fills emit compact static declarations without runtime resets,generated persistent root fill retains a causal target boundary,ordinary arrays native verify and emit without synthesized events,public fixed and symbolic event arrays native verify and emit deterministically}'
```

Logs are retained under `.work/persistent-initializer-*` and
`.work/scala-validation/logs/writable-array-persistent-final.log`. Focused
read-only architecture review found no blocker. No CI, full qualification or
independent simulator acceptance was run for this checkpoint.

## Live selected-storage control and event references

This shared extension follows
[`NodalWritableRealArrays-DG-v0.4`](../design-gates/NodalWritableRealArrays-DG-v0.4.md).
Controls and primitive event owners use an explicit storage-reference form with
actual scalar handles for every mutable source leaf when selected storage is
involved. Legacy zero-operand scalar source forms retain their semantics. The
native canonical source parser derives kind, units and leaf sets from the actual
handles, with no missing-binding fallback to textual selected-path lookup.
Existing storage ownership and selected-region definite assignment remain the
single sources of truth. Constructing pure projection aliases does not load data.

Target maps contain only proven static coordinates; numeric loads remain inline
at their existing consumers. All if-arm map setup precedes the whole first-match
chain, avoiding statements between else-if arms. Case and loop maps precede those
statements; the existing loop limit is still evaluated once at each entry.
Primitive and OR-composed monitor maps are emitted at the actual consuming on
statement (or the existing unused monitor), without adding reset events or
captured Real temporaries. A distinct map belongs to each consumer/actual handle;
no two selected leaves share the second leaf's final coordinate accidentally.
Event relocation barriers derive observed roots from handles rather than assuming
selected leaf labels are declaration identities. Handler writes do not establish
unconditional following initialization.

This implementation does not create a public Real-to-Integer cast or broaden
user-function placement. Native direct selected-reference Integer bounds and
public composition through existing scalar Integer storage are distinguished.
Pure fill/view-derived scalar control sources, compound selected scalar
initializers, generated persistent initialization, conservative port mappings and
broader generated object/state support remain open where not yet implemented.

The native implementation passed 47 focused typed-control cases, including positive
plain/default-gate verification and deterministic round trips, and exact negative
diagnostic sets. Directly affected predecessors passed: writable storage 49,
scalar/control 32, storage descriptors 21 and conservative node arrays 37.
Matched event and direction CTests also passed. Native lexical escape coverage
remains in the descriptor tests; a textual out-of-scope SSA fixture is rejected
by MLIR parsing and is not counted as a semantic-verifier test.

```sh
source .work/nodal-toolchain-env.sh
PYTHONPATH=.work/python-tools .work/python-tools/bin/cmake --build .work/native-release --target nodalc nodal-storage-model-unit-tests nodal-node-array-unit-tests -j 4
PYTHONDONTWRITEBYTECODE=1 ctest --test-dir .work/native-release -V --output-on-failure -R '^nodal.native.typed-control-integration$'
ctest --test-dir .work/native-release --output-on-failure -R '^nodal.native.analog-(procedural|control-flow)-'
PYTHONDONTWRITEBYTECODE=1 python3 tests/compiler/fixtures/increment43/run_writable_array_matrix.py --nodalc .work/native-release/bin/nodalc --work-dir .work/typed-control-writable-predecessors
.work/native-release/core/compiler/test/Unit/nodal-storage-model-unit-tests
.work/native-release/core/compiler/test/Unit/nodal-node-array-unit-tests
PYTHONDONTWRITEBYTECODE=1 ctest --test-dir .work/native-release --output-on-failure -R '^nodal.native.(analog-events-matrix|analog-event-direction-review)$'
```

Focused read-only native/backend review found no blocker in binding completeness,
selected initialization, actual-root event barriers, static map placement or live
numeric leaves. The review was read-only; actual target/public execution results
are recorded below.

The target backend and writable backend units passed, including live/order,
short-circuit/else-if placement, used/unused event composition, generated typed
controls and deep compact maps. The strict target parser passed 308 cases (34
new live-reference cases); noise/transfer parsers retain 24/40 passing cases.
Nine CLI outputs match unit outputs exactly. Existing target grammar already
supports these expression placements, so no production parser widening was
needed. An initially empty authored arm fixture was corrected to contain a real
write; the inherited empty-arm admission rule was preserved.

```sh
PYTHONPATH=.work/python-tools .work/python-tools/bin/cmake --build .work/native-release --target nodal-translate nodal-backend-unit-tests nodal-writable-array-backend-unit-tests nodal-event-reparse-unit-tests nodal-noise-reparse-unit-tests nodal-transfer-reparse-unit-tests -j 3
.work/native-release/core/compiler/test/Unit/nodal-writable-array-backend-unit-tests .work/live-control-backend
for target in nodal-backend-unit-tests nodal-event-reparse-unit-tests nodal-noise-reparse-unit-tests nodal-transfer-reparse-unit-tests; do
  .work/native-release/core/compiler/test/Unit/$target
done
bash .work/check-live-control-cli.sh
```

Native and backend logs remain under `.work/typed-control-*` and
`.work/live-control-*`; raw outputs are not new acceptance ledgers. The new native
CTest disables Python bytecode generation, keeping transient outputs in `.work`.

The two new public suites passed 11 grouped methods against both configured
native tools. The final nested-loop fixture then added its planned break arm;
its exact affected target method passed again with nearest-loop break/continue,
selected reads after mutation and bound-on-entry semantics. Six actual public
models emit deterministic Verilog-A and Verilog-AMS: mixed/reused conditions,
public Integer-loop composition, cross/above/timer and reused OR monitors,
partial-lane event-local reads, symbolic noncontiguous views and supported
generated lexical storage. Separate AMS CLI replay matches all six retained
outputs. Source/normalized IR and actual targets are under
`.work/scala-validation/storage-reference-witnesses/`.

```sh
export NODAL_NODALC="$PWD/.work/native-release/bin/nodalc"
export NODAL_TRANSLATE="$PWD/.work/native-release/bin/nodal-translate"
.work/scala-validation/mill-jvm.sh core.scala.testkit.test.testOnly nodal.internal.testkit.Increment43StorageReferenceTests nodal.internal.testkit.Increment43StorageReferenceFailureTests
.work/scala-validation/mill-jvm.sh core.scala.testkit.test.testOnly nodal.internal.testkit.Increment43StorageReferenceTests -- 'nodal.internal.testkit.Increment43StorageReferenceTests.live conditions and loop body predicates native verify and emit deterministically'
python3 .work/scala-validation/write-storage-reference-witnesses.py
```

Actual source in `Increment43StorageReferenceTests.scala` reuses a predicate
across an intervening selected write:

```scala
val reused = (flag && (cells.at(0) > 0.0.real)) || (cells.at(1) > 0.0.real)
```

Its emitted predicates contain distinct inline `cells[...reference_1_map]` and
`cells[...reference_2_map]` leaves under `&&`/`||`; the later consumer has its own
coordinate names and observes the authored write. No Real capture declaration is
inserted for predicate-only or monitor-only models. This is structural emission
and reparse evidence, not numerical simulator execution. Pure uniform-fill
scalarization and compound selected scalar initialization remain separate open
source-consumer work.

Nine directly affected public scalar/control/event and former-boundary
predecessors passed. The two former selected-control negatives now assert actual
typed-reference emission; their compound-initializer negative remains causal.
Pinned formatting passed for all eight changed Scala files and affected native
files; changed Markdown and whitespace checks passed. Exact predecessor
selectors are retained in `.work/scala-validation/run-storage-reference-tests.sh`
and logs in `storage-reference-predecessors.log` and
`storage-reference-boundary-predecessors.log`. No CI/full qualification or
independent consumer/simulator success is claimed.

### Compound lexical initialization and checked uniform scalar sources

The next source-consumer extension is governed by
[NodalWritableRealArrays-DG-v0.5](../design-gates/NodalWritableRealArrays-DG-v0.5.md).
It keeps one typed procedural Value graph and the existing immutable capture,
storage descriptor and structured definite-assignment owners. Nested scalar
leaves retain their actual uniform Fill/View source; original records are checked
before a derived live expression may erase pure structural shape history.
Bounds, ownership, units and actual source/result identities are obligations of
that source/bridge boundary. The native boundary independently checks the emitted
scalar expression and actual references; it cannot certify erased shape history
from provenance alone.

Compound lexical scalar initialization uses the additive
`initializer_form = "expression"` on the existing variable operation. Its actual
immutable operands, exact parsed read inventory, incoming DA and statement-local
capture corridor are checked before the destination becomes initialized. Dispatch
precedes the old one-operand copy form, so a one-capture expression still retains
its arithmetic. Legacy declaration forms remain available. Persistent roots,
shaped destinations and uninitialized variables cannot use the new form.

Statement captures share a fill by its checked retained identity within that
consumer; two separately authored equal-text fills remain distinct. Live control
and event expressions instead substitute the scalar only at its reached leaf,
without an eager numeric prelude. The bounded live profile permits one occurrence
per retained fill across the complete consumer, including composed monitor
arguments. Repeated shared-fill live consumers reject and remain required open
work: correct demand-time reuse needs a lifetime-aware lazy binding, not eager
function arguments or a cached value shared between reevaluations.

The scoped implementation passes the focused checks below. It adds no
independent downstream consumer claim and does not complete Foundation 43.

The native implementation passed 79 writable-storage semantic checks: the 49
predecessors plus 30 new initializer cases (11 positives with deterministic
roundtrip/default-gate verification and 19 exact causal diagnostic negatives).
These include scalar-only incoming DA, selected and uniform-fill captures,
Boolean and unit-preserving results, generated/event locals, and malformed
form/type/inventory/order/owner boundaries. The structured DA path is selected
for scalar-only expression declarations too; empty textual inventory cannot
hide their actual capture reads. The 32 scalar/control CTests and 47-case live
selected-control predecessor also passed. Public source/bridge integration results are recorded below.

```sh
source .work/nodal-toolchain-env.sh
PYTHONPATH=.work/python-tools .work/python-tools/bin/cmake --build .work/native-release --target nodalc nodal-translate nodal-writable-array-backend-unit-tests -j 3
PYTHONDONTWRITEBYTECODE=1 python3 tests/compiler/fixtures/increment43/run_writable_array_matrix.py --nodalc .work/native-release/bin/nodalc --work-dir .work/initializer-expression-native-matrix
PYTHONDONTWRITEBYTECODE=1 ctest --test-dir .work/native-release --output-on-failure -R '^nodal.native.(analog-(procedural|control-flow)-.*|typed-control-integration)$'
```

Native logs remain in `.work/initializer-expression-native-matrix.log` and
`.work/initializer-expression-predecessors.log`. The native production change
reuses parsed expressions, immutable capture checks and structured DA rather
than adding another evaluator or proof registry. The existing per-capture
corridor walk remains; no whole-pipeline linearity or measured speedup is claimed.

The writable backend unit passed its inherited checks plus 44 positive
profile/lifetime witnesses and 24 rejection fixtures in both VA/AMS profiles.
Each positive repeats emission deterministically and reparses the target;
negatives pin causal diagnostics and preserve caller-owned output sentinels.
Twelve retained CLI outputs match the unit outputs byte-for-byte. Shared-fill
output evaluates its scalar source once before both immutable projections and
the compound local assignment; later mutation remains later. A one-capture
actual Verilog-A expression is:

```verilog
event_Arrays_local = (event_Arrays_firstCapture + 1.0);
```

```sh
source .work/nodal-toolchain-env.sh
.work/native-release/core/compiler/test/Unit/nodal-writable-array-backend-unit-tests .work/initializer-expression-backend-unit
```

Inputs, actual VA/AMS outputs, CLI parity and logs remain under
`.work/initializer-expression-backend-unit/`. This is compiler emission/reparse
evidence, not independent simulator acceptance. The generic backend and event
parser binaries were rebuilt but not separately executed for this checkpoint.

A focused read-only review of the native/backend diff found no concrete blocker
in form restrictions, exact capture/type validation, incoming DA, ownership,
statement corridors or arithmetic-preserving scope-entry rendering. That review
did not execute tests or review Scala's retained-shape erasure; its assurance is
limited to the inspected native/backend paths.

The two new public suites passed 12 grouped methods, and 18 directly affected
public predecessors passed with both native tools configured. Eight actual
public models cover compound selected/Boolean/dimensioned local initialization,
generated locals, statement fill sharing and later reuse, symbolic live control,
cross/timer/OR monitors, static scalar roots, mixed storage/uniform leaves and
nested fills. All emit Verilog-A and Verilog-AMS; 16 direct translator repeats
match exactly. Actual source/normalized MLIR and targets are retained in
`.work/scala-validation/uniform-scalar-witnesses/`.

```sh
export NODAL_NODALC="$PWD/.work/native-release/bin/nodalc"
export NODAL_TRANSLATE="$PWD/.work/native-release/bin/nodal-translate"
.work/scala-validation/mill-jvm.sh core.scala.testkit.test.testOnly nodal.internal.testkit.Increment43UniformScalarTests nodal.internal.testkit.Increment43ScalarSourceBridgeTests
bash .work/scala-validation/run-uniform-scalar-tests.sh
python3 .work/scala-validation/write-uniform-scalar-witnesses.py
```

The script retains exact affected predecessor selectors; final logs are
`uniform-scalar-final.log`, `uniform-scalar-witnesses-final.log` and
`uniform-scalar-witness-repeat.log` under `.work/scala-validation/logs/`.
All 12 changed Scala files pass pinned formatting. Changed native formatting,
Markdown structure/local links and whitespace checks pass.

Focused source review/testing caught and repaired a wrapper effect gap: already
captured stateful or unsupported user-call results cannot become legal procedural
sources merely through fill/view selection. The authored expression DAG is
checked against the existing ConstructionSession operator inventories through a
narrow kernel query; no copied operator-family whitelist or second effect registry
is added. Exact causal negatives cover initialization, assignment, control and
event consumers. An inherited bridge diagnostic-order regression was repaired
without admitting incomplete storage-reference inventories.

Invocation-local identity memoization keeps retained-Value read collection,
remapping and exact source comparison from repeatedly expanding shared DAGs.
A 40-layer helper case and the existing 512-depth rejection exercise that narrow
contract. Inherited authored-expression traversal and rendered arithmetic can
still expand; operator inventory scans also remain per consumer. These are not
whole-pipeline linearity or performance measurements. Repeated shared-fill live
consumption remains explicitly rejected/open, and unused public event handles
are not serialized, so no unused-monitor public witness is claimed. No CI or
full qualification was run.

### Language edition boundary for composed conservative views

The next local conservative-view extension follows
[NodalConservativeNodeArrays-DG-v0.2](../design-gates/NodalConservativeNodeArrays-DG-v0.2.md).
Its shared coordinate derivation is being extracted from existing storage mapping
without transferring storage rights/DA into conservative connectivity. Current
local unpacked-net representation remains; ports and whole-array instance
mapping need the subsequent explicit layout/binding extension.

The generic repository language baseline is Verilog-AMS 2023, as stated in the
README and original analog-event gate. Localparam/net syntax also found in the
2.4 LRM is useful construct-level compatibility evidence, not a global language
version selection. In particular, 2023 section 5.10.3 permits dynamic tolerance
arguments, whereas 2.4 sections 4.5.14 and 5.10.3 treat those tolerance slots as
analysis-start constants. No global event semantic change follows from the
OpenVAF 2.4 compatibility subset. A future tool/edition capability profile must
state this distinction explicitly rather than silently freezing live tolerances.
See the authoritative [2023 LRM](https://www.accellera.org/images/downloads/standards/v-ams/VAMS-LRM-2023.pdf)
and [2.4 LRM](https://accellera.org/images/downloads/standards/v-ams/VAMS-LRM-2-4.pdf).

The native map extraction passes 178 focused node-array checks, the 21-case
storage-descriptor suite and all 79 writable-storage predecessors. The neutral
ShapeModel owner derives ordinal/coordinate facts; AnalogStorageModel retains
Variable/procedure/generated rights and DA, while ConservativeConnectivity
requires the actual local Electrical NodeArray and structural provenance.
Conservative scalar identity retains actual symbolic atoms even when a singleton
storage region may legitimately collapse to Whole. Direct root-axis disjointness
proofs remain available when flat ordinal intervals are coarser.

A 32-view native witness exercises composed mapping within the existing
512-projection resource bound. Per-operation verification and literal-prefix
resolution still revisit portions of the chain; no whole-pipeline linear-time or
measured speedup claim follows from the shared extraction. Native logs are
`.work/conservative-views-node-array-unit.log`,
`.work/conservative-views-storage-unit.log` and
`.work/conservative-views-writable-predecessor.log`. Backend/public target
integration also passes as recorded below.

Focused native review found no blocker in capability separation, actual-root
physical keys, conservative possible-alias rejection or preservation of storage
coverage. A follow-on symbolic slice/flatten witness exposed a precision gap at
a fixed intermediate reshape. Checked re-radix recovery now handles actual
literal intermediate coordinates before resuming symbolic root strides; opaque
parameter/expression terms are not canceled from coincident bounds. The reviewer
also inspected that fix without finding a concrete issue. Review was read-only,
with no test execution or Scala/backend assurance implied.

The shared target mapper preserves all 80 retained writable-unit MLIR/VA/AMS
artifacts byte-for-byte against the preceding checkpoint. Its affected writable
unit, event reparser (308 cases) and hierarchy syntax (62 cases) pass. The
conservative BackendTest passes composed fixed/symbolic mapping, scalar child
binding, a 48-view compact chain, constant dependency/overflow/range negatives
and transactional target-width rejection. Final public integration and reproduction commands are recorded below.

```sh
source .work/nodal-toolchain-env.sh
PYTHONPATH=.work/python-tools .work/python-tools/bin/cmake --build .work/native-release --target nodalc nodal-translate nodal-node-array-unit-tests nodal-storage-model-unit-tests nodal-backend-unit-tests nodal-writable-array-backend-unit-tests nodal-event-reparse-unit-tests nodal-hierarchy-syntax-unit-tests -j 3
.work/native-release/core/compiler/test/Unit/nodal-node-array-unit-tests
.work/native-release/core/compiler/test/Unit/nodal-storage-model-unit-tests
.work/native-release/core/compiler/test/Unit/nodal-backend-unit-tests
.work/native-release/core/compiler/test/Unit/nodal-writable-array-backend-unit-tests .work/conservative-views-writable-backend
.work/native-release/core/compiler/test/Unit/nodal-event-reparse-unit-tests
.work/native-release/core/compiler/test/Unit/nodal-hierarchy-syntax-unit-tests
bash .work/check-conservative-views-cli.sh
python3 .work/check-conservative-views-writable-parity.py
```

The 48-slice target witness emits 49 constant map stages, one electrical array
declaration and fewer than 30,000 output bytes. This is a measured fixture bound,
not a universal complexity claim. Existing direct signed64 node-array emission,
including a trillion-lane compact declaration, remains unchanged. New symbolic
compositions that need Integer stages retain checked signed32 stage limits;
proved fixed maps may bypass stages after native source verification.

For root shape `[2,COUNT]`, slicing the final axis to `[1,2]`, flattening and
selecting logical index 1 produces this actual Verilog-A map:

```verilog
parameter integer COUNT = 4 from [2:8];
electrical tap[0:((2 * COUNT) - 1)];
localparam integer node_map_tap_0_0 = 1;
localparam integer node_map_tap_0_1 = ((node_map_tap_0_0 * COUNT) + ((node_map_tap_0_0 % 1) + 1));
```

The contribution then uses `tap[node_map_tap_0_1]`; the parameter remains
symbolic. Actual normalized input and VA/AMS output are under
`.work/conservative-views-symbolic*`. The strict parser checks all reachable
structural Integer localparam dependencies with an iterative stack, declaration
order, concrete bounds and overflow, while unrelated scalar initializer grammar
remains unchanged. Ordinary Integer parameters remain symbolic regardless of
defaults. Correlated all-parameter mapping remains a native proof obligation.

The actual public `Increment43NoncontiguousNodeViews` witness in
`Increment43NodeViewTests.scala` retains a symbolic final-axis slice:

```scala
val cols: Param[Integer] = param(5.integer, range = 4 to 6)
val taps = node(Electrical, 3, cols)
val selected = taps.slice(1, 3, axis = 1).flatten.at(3)
val other = taps.at(0, 0)
analog:
  I(selected, p) <+ V(other, p) / 1.kOhm
```

Its actual generated Verilog-A contains:

```verilog
parameter integer cols = 5 from [4:6];
electrical taps[0:((3 * cols) - 1)];
localparam integer node_map_taps_0_0 = 3;
localparam integer node_map_taps_0_1 = (((node_map_taps_0_0 / 2) * cols) + ((node_map_taps_0_0 % 2) + 1));
analog begin
  I(taps[node_map_taps_0_1], p) <+ (V(taps[0], p) / 1000);
end
```

The source, normalized IR and actual VA/AMS files for nine public models are
retained under `.work/node-view-public/`; this excerpt comes from
`Increment43NoncontiguousNodeViews.va`, not a handwritten target example.

The final public run passes seven new grouped methods and 13 directly affected
predecessors with both native tools configured. The predecessors include direct
node/scalar-child consumers, storage coverage/DAG keys, slice transport and the
unchanged Vec flatten boundary. Nine public sources pass plain native verification
and the actual default gate. All nine emit VA and AMS with exact repeated output
in each profile (18 target/repeat pairs total).

```sh
export NODAL_NODALC="$PWD/.work/native-release/bin/nodalc"
export NODAL_TRANSLATE="$PWD/.work/native-release/bin/nodal-translate"
bash .work/scala-validation/run-node-view-tests.sh
bash .work/scala-validation/run-node-view-predecessors.sh
python3 .work/scala-validation/write-node-view-witnesses.py --ams
python3 .work/scala-validation/check-node-view-api.py
```

Exact leaf selectors remain in those two scripts. Logs are
`.work/scala-validation/node-views-final.log`, `node-views-predecessors.log` and
`node-views-witness-ams.log`; native public replay is recorded in
`.work/conservative-views-public-native-final.log`. The API inventory/reference
consistency check, pinned formatting for all eight changed Scala files, affected
native formatting, changed Markdown/local links, JSON parsing and whitespace
checks pass. No CI or independent consumer/simulator qualification was run.

Actual integration exposed and repaired two bridge seams: synthetic index
constants no longer impersonate their projection's unique semantic path (source
locations remain), and inline prefix/view arithmetic is included in the existing
static-dependency owner while shared live runtime uses remain retained. The
neutral Scala helper recovers literal coordinates across fixed intermediate
reshapes for physical-key parity; storage DA keys retain their own meaning.
NodeArray's signed64 flatten uses an explicit capability parameter on the shared
dimension builder; the pre-existing Vec limit remains unchanged. The public
50,000-by-50,000 witness selects a fixed physical endpoint without map stages,
and overflow remains a causal rejection.

The shared view/selection profile is complete for this checkpoint. Boundary
arrays, exact whole-array instance mappings, source-generated topology and other
remaining F43 capabilities are still open; this does not close the increment.

## Conservative boundary arrays and finalized instance shapes (v0.3)

The [additive gate](../design-gates/NodalConservativeNodeArrays-DG-v0.3.md)
extends the published local-view checkpoint `9ab2380`. It selects one integrated
boundary-array/whole-child binding path. Its focused public/native/target
integration checks pass; the remaining Foundation 43 profiles stay open.

The shared ParameterModel query resolves an actual instance's Integer formal
through its finalized literal/SSA overrides and recursively retained default
DAGs. Parent symbolic declarations remain identity-bearing atoms, including
singleton ranges; their declaration defaults never prove shape equality. The
query-local immutable DAG shares checked interval arithmetic with existing
parameter bounds analysis. ShapeModel consumes it for exact extent equality;
physical conservative selection identity remains a separate contract.

Each used child formal checks its effective range and exclusions against the
whole substituted actual envelope. Inclusive equality may be proven by exact
ordered DAG correspondence; other unproved correlations reject. The existing
scalar default check also now evaluates dependent Integer constraints in the
finalized instance environment, rather than at unrelated child declaration
defaults. This does not impose finite symbolic envelopes on the old scalar
profile. Independently retained numeric default attributes remain transport,
not instance shape proof.

The parameter-model unit executable passes the new dependent-default/override
fixture, actual-parent identity and ownership checks, range/fixed/conflict/cycle
negatives, and addition/multiplication/zero-divisor boundaries, together with
its inherited cases. The final parameter replay also rejects a forged
cross-module default operand.
The native binding matrix passes 49/49 cases through real instance/binding
verification, with positive roundtrips and the actual default gate. Existing
NodeArray and descriptor predecessors pass 178/178 and 21/21 respectively; ten
actual public sources pass plain native verification and the default gate.

```sh
cmake --build .work/native-release --target nodal-parameter-model-unit-tests \
  nodal-node-array-unit-tests -j 3
.work/native-release/core/compiler/test/Unit/nodal-parameter-model-unit-tests
.work/native-release/core/compiler/test/Unit/nodal-node-array-unit-tests
```

Logs are `.work/boundary-parameter-build.log`,
`.work/boundary-parameter-test-final.log`,
`.work/boundary-node-array-predecessor.log`,
`.work/boundary-array-native-shadow-fixed.log` and
`.work/boundary-array-public-native.log`. A focused read-only architecture
review found no blocker in the parameter query/arithmetic extraction. Query-local
memoization avoids enumerating lanes or cloning reusable definitions, but
per-formal module scans and repeated subtree comparison can still be quadratic
in authored graph size. No whole-pipeline linearity or speedup is claimed. The
existing 512-active-expression resource boundary remains explicit; this is not
an unbounded symbolic algebra engine. No CI or independent target consumer was
run for these checks.

### Target layout, adapter and actual public example

Owned local/boundary conservative arrays now use one ascending vector layout,
with row-major logical index zero at the left boundary. The earlier unpacked-net
examples above remain historical output of v0.2. Existing synthesized source-range
nets retain their nonzero lower/index semantics when moved to vector declaration
syntax; no coordinate shift is implied. Direct signed64 layouts remain supported.
Only adapters requiring genvar/Integer stages use the explicit portable signed32
carrier limit.

Whole actuals use whole vectors; proved contiguous views use ascending part-selects.
Noncontiguous views reuse the shared projection map in a structural generate loop,
joining each actual lane to a bridge lane through two named formal expressions
that refer to the same single internal electrical net. The child itself is
instantiated once. This preserves physical connectivity without numerical wire
equations, per-lane source expansion or a second mapping engine. A two-million-lane
focused witness remains one compact loop; that is an output-structure observation,
not a measured performance claim.

Actual source from `Increment43BoundaryArrayTests.scala`:

```scala
final class Increment43GridArrayChild extends Module:
  val rows: Param[Integer] = param(2.integer, range = 2 to 5)
  val p = inout(Electrical, rows, 2)
  analog:
    I(p.at(0, 0), p.at(0, 1)) <+ V(p.at(0, 0), p.at(0, 1)) / 1.kOhm

final class Increment43NoncontiguousArrayBinding extends Module:
  val rows: Param[Integer] = param(2.integer, range = 2 to 5)
  val taps = node(Electrical, rows, 4)
  val child = instance(new Increment43GridArrayChild)
  val actual = taps.slice(1, 3, axis = 1).flatten.reshape(rows, 2)
  connect(actual, child(_.p))
  child.param(_.rows, rows)
```

Actual generated Verilog-A below omits only the framework header/includes and
the long `nodal.array_binding` JSON comment. The comment preserves the actual
binding/formal/root/projection/stage correspondence; it is not parser proof.

```verilog
module nodal_alias_electrical(.lhs(n), .rhs(n));
  inout n;
  electrical n;
endmodule

module Increment43NoncontiguousArrayBinding;
  parameter integer rows = 2 from [2:5];
  electrical [0:((rows * 4) - 1)] taps;
  electrical [0:((rows * 2) - 1)] binding_child_instance_p_bus;
  genvar binding_child_instance_p_lane;
  generate
    for (binding_child_instance_p_lane = 0; binding_child_instance_p_lane < (rows * 2); binding_child_instance_p_lane = binding_child_instance_p_lane + 1) begin : binding_child_instance_p_map
      localparam integer binding_child_instance_p_stage_0 = (((binding_child_instance_p_lane / 2) * 4) + ((binding_child_instance_p_lane % 2) + 1));
      nodal_alias_electrical link(.lhs(taps[binding_child_instance_p_stage_0]), .rhs(binding_child_instance_p_bus[binding_child_instance_p_lane]));
    end
  endgenerate
  child #(.rows(rows)) child_instance(.p(binding_child_instance_p_bus));
endmodule

module child(p);
  parameter integer rows = 2 from [2:5];
  inout [0:((rows * 2) - 1)] p;
  electrical [0:((rows * 2) - 1)] p;

  analog begin
    I(p[0], p[1]) <+ (V(p[0], p[1]) / 1000);
  end
endmodule
```

The exact source/IR artifacts are under `.work/boundary-array-public-final/`; final
VA/AMS outputs and parity variants are under
`.work/boundary-arrays-public-final-target/`. The actual generation command is:

```sh
.work/native-release/bin/nodal-translate --nodal-to-verilog-a \
  .work/boundary-array-public-final/Increment43NoncontiguousArrayBinding.va.source.mlir \
  -o .work/boundary-arrays-public-final-target/Increment43NoncontiguousArrayBinding.va.target
python3 .work/check-boundary-array-targets.py \
  .work/boundary-array-public-final .work/boundary-arrays-public-final-target
bash .work/check-boundary-backend-focused.sh
```

All ten final public models pass both target profiles: 20 source/profile cases
with exact direct/repeat/default-gate-without-debug-location/renamed-transport
parity. The boundary backend passes 53 focused checks, hierarchy syntax 138 and
event reparse 308. Affected backend/writable aggregates pass; all 80 writable
predecessor artifacts remain byte-identical. The selected port-current witness
emits `I(sense) <+ I(<p[4]>);` through verified partial derived-connectivity
records, preserving port incidence separately from physical node aliases.

Focused integration found and repaired two defects rather than weakening tests:
map division/remainder now reject dense divisor intervals containing zero even
when endpoints are nonzero, and target correspondence now uses retained authored
source ranges instead of nondeterministic temporary MLIR parser filenames.
Comment-only mutation acceptance and actual connection mutation rejection keep
metadata non-authoritative. A focused read-only review found no further blocker
in the adapter/parser/provenance delta. These are language/compiler structural
results under the generic VAMS2023 contract, not independent simulator acceptance.
The earlier concrete OpenVAF Real-array failures remain a separate F48 handoff;
no new conservative-vector/helper consumer result is claimed here.

A final ownership audit reproduced a module-name shadowing defect: a local
instance or parameter could mask the top-level child definition, rejecting a
valid binding while missing an absent binding. Module targets now resolve only
typed definitions in the enclosing builtin-module namespace. Four byte-identical
before/after controls verify both valid and missing-binding cases. The final
49-case matrix, 178 NodeArray checks, connectivity unit and three affected public
native/default-gate sources pass with matched rebuilt tools. Evidence is in
`.work/boundary-module-shadow-before`,
`.work/boundary-array-native-shadow-fixed.log`,
`.work/boundary-array-connectivity-predecessor.log` and
`.work/boundary-module-shadow-public.log`.

The final public Scala suite passes 8/8 methods and all 16 selected directly
affected predecessor methods pass. After the module-resolution repair, the two
native/target public methods were replayed with matched tools: all 20 model/profile
outputs and three causal malformed-native cases still pass. API surface checks
pass 34/34; pinned formatting and changed-document checks pass. Exact selectors
and logs are recorded in `.work/scala-validation/boundary-array-scala-handoff.md`,
with the fresh-tool run in
`.work/scala-validation/run-boundary-array-final-replay.sh`. Two deterministic
construction artifacts and 30 retained-reference reconstruction checks also pass.

The affected predecessor replay exposed inherited assertion drift: generated
induction symbols had changed under the earlier lexical naming implementation,
and one declaration count omitted an existing scalar boundary. Repairs resolve
the actual symbol from its exact region identity, retain authored node/source-line
separation and the stride/row-major equations, and assert one generated vector
plus one scalar boundary. These are newly executed semantic checks, not a
retroactive claim about historical runs.

This checkpoint does not complete F43. Source-generated child instances and
connections, generated persistent-state declaration lifetime, partial child-array
bindings/broader joins, cross-range storage, remaining operator/state/result
fan-out and shared-live-fill demand lowering remain open where required.

## Source-generated structural scopes (v0.4)

The [v0.4 gate](../design-gates/NodalConservativeNodeArrays-DG-v0.4.md) extends
published boundary-array checkpoint `b534451`. The selected path reuses existing
`hdlRange` capture and Generate IR for child templates, conservative connections,
local scalar nodes, nested rectangular scopes and owned array/view selection by
an actual visible induction value. Whole child-array bindings and invariant
parameter overrides reuse v0.3. Focused source/native/target acceptance is complete for this selected contract;
remaining required F43 compositions stay open.

The shared ParameterModel owner now distinguishes three namespaces: module
parameters, exact lexical Module/Generate instance symbols and top-level module
definitions. Generated instance substitution retains the actual enclosing Module
and reads overrides only from the instance's direct lexical block. Invariant
actual expressions remain module-owned. Generate's SymbolTable trait therefore
does not hide a module parameter or allow a same-named Instance to serve as one;
the affected waveform parameter query uses the typed module namespace too.

`resolveGeneratedDomain` derives its bounds and captured count from the existing
per-Generate validator and checked unsigned-distance count helper. It carries the
actual optional i64 block argument, immutable canonical bound DAGs and conservative
attained source-index bounds. Signed-i64 interpretation is local to generated
bounds, preserving negative source origins without widening general parameter
arithmetic. Legacy directional validation remains separate from the positive
induction profile. Proven always-empty domains have no attained coordinates;
type, ownership, shape and child-binding obligations still apply. Pure legacy
generated analog/state bodies do not acquire unused induction arguments or
silently move into the newer signed32 structural target profile.

Connectivity derives one lexical scope at a time from actual SSA and symbols;
generated templates are not nodes in the module-global union-find. Static array
map identity, instance occurrence and local port incidence remain distinct.
The hierarchy graph retains its existing typed definition index and per-scope
endpoint indexes. Standalone scope lookup still scans a block; repeated parameter
queries/DAG comparison can remain quadratic. No whole-pipeline linearity or
measured speedup is claimed. No new mutable registry, parameter/count engine,
per-lane source expansion or per-instance cloned module definition is introduced.

The target layout uses structural generated scopes for declarations and instance
statements. Source induction is distinct from an occurrence ordinal, and the
new target path checks signed32 bounds/intermediates/updates. Existing direct
signed64 paths remain intact. VAMS2023 §6.6.1 defines per-instance declaration
scope and implicit localparam identity; §6.9.1 concatenates analog blocks after
generate elaboration in textual/unrolling order. Mixed local analog/nested-scope
emission must preserve that order and execute each actual procedure once. A scoped
declaration location does not by itself authorize generated shaped persistent
initialization; that lifetime work remains explicit.

The shared parameter/domain unit passes its new scope/induction/override/count and
waveform-namespace checks together with inherited cases. A focused read-only review
of this frozen delta found no blocker in bound/count reuse, empty/stride handling,
namespace separation or invariant override ownership. Query failures preserve the
original causal diagnostic; an outer generic wrapper no longer obscures a forged
count with an additional unrelated code. This is not a broad proof campaign.

### Actual generated-topology witness

`Increment43GeneratedTopologyTests.scala` contains the public Bank example:

```scala
val count: Param[Integer] = param(3.integer, range = 2 to 5)
val resistance: Param[Real] = param(2.kOhm)
val taps = inout(Electrical, count)
val common = inout(Electrical)
hdlRange(0, count): i =>
  val element = instance(new Increment43TopologyCell)
  element.param(_.resistance, resistance)
  connect(taps.at(i), element(_.p))
  element(_.n) <> common
```

The actual bridge output has one `nodal.generate` with `upper = @count`,
`maximum_trip_count = 5`, and one i64 block argument. `nodal.shape_index` consumes
that argument and the owned `taps` terminal array. The child instance, its parameter
override, terminal handles and two connections are inside that same region, with
matching generated owner/induction identities. The reusable child definition is
outside it. The exported IR is retained as
`.work/generated-topology-public-final/Increment43TopologyBank.va.source.mlir`.
The emitted target includes this exact compact body:

```verilog
localparam integer node_map_taps_0_0 = hdl_range_increment43_generated_topo_dc37e5f6;
element #(.resistance(resistance)) element_instance(.n(common), .p(taps[node_map_taps_0_0]));
```

Its enclosing structural loop starts at zero, compares the same allocated genvar
with `count`, and increments by one. The child contains
`I(p, n) <+ (V(p, n) / resistance);`. The parameter remains symbolic; neither the
callback nor the target is expanded at its default value. The complete output is
`.work/generated-topology-public-target-final/Increment43TopologyBank.va.target`.
The million-occurrence witness likewise retains one structural template; this is
a size/representation check, not a timing claim.

### Focused shared/native acceptance

The final native matrix passes 50/50 cases, including 24 positive cases with the
actual default gate and stable repeated output. The affected boundary matrix
passes 49/49, NodeArray unit 187 checks, storage unit 21 checks, and the parameter
unit passes its new and inherited checks. All 26 final public sources (13 models
with VA/AMS profile metadata) pass the actual native default gate and byte-identical
replay. The source models cover nested axes, local joins, source origins/strides,
owned views, separately captured equal static DAGs, whole child-array bindings,
reusable child definitions, empty/singleton/million domains, sibling scopes and
coexisting selected analog contributions.

Commands, run from the repository with the matched local toolchain:

```sh
PYTHONDONTWRITEBYTECODE=1 python3 tests/compiler/fixtures/increment43/run_generated_topology_matrix.py --nodalc .work/native-release/bin/nodalc --work-dir .work/generated-topology-native-final
PYTHONDONTWRITEBYTECODE=1 python3 tests/compiler/fixtures/increment43/run_boundary_array_matrix.py --nodalc .work/native-release/bin/nodalc --work-dir .work/generated-topology-boundary-native
PYTHONDONTWRITEBYTECODE=1 NODAL_PUBLIC_NATIVE_OUTPUT=.work/generated-topology-public-native-final python3 .work/check-generated-topology-public.py
.work/native-release/core/compiler/test/Unit/nodal-node-array-unit-tests
.work/native-release/core/compiler/test/Unit/nodal-storage-model-unit-tests
.work/native-release/core/compiler/test/Unit/nodal-parameter-model-unit-tests
```

Per-case commands/results are retained in the corresponding `.work/` summary JSON
and `generated-topology-{native-final,boundary-native,public-native-final,node-final,storage-final,parameter-final}.log`.
The first public integration replay found that generated-local literal index
carriers needed the existing use-aware proof path. The repair shares literal
extraction and actual lexical dominance checks; independent sibling/forward SSA
and invalid-literal-in-empty-domain negatives remain enforced. No module hoisting
or default substitution was used to bypass those obligations.

### Focused target acceptance and architecture

The final generated-topology backend unit passes 107 checks; directly affected
boundary, hierarchy-syntax and event-reparse units pass 54, 236 and 308 checks.
Existing backend, writable-array backend and matched connectivity aggregate units
pass. Eighty writable predecessor artifacts remain byte-identical. Native-fixture
target preflight passes 48 cases: 46 positive VA/AMS render/repeat cases and two
expected inherited domain-capability rejections, not claimed target successes.

All 26 actual public profile outputs are byte-identical across direct translation,
repeat, native default-gate serialization without debug locations, and renamed
transport input. Thirty-two source-scope/induction/target-loop records and two
generated binding source/adapter-scope records are checked against the actual IR.
Generated binding metadata retains `generated_scope` for its optional adapter loop;
`source_scope` separately identifies the verified enclosing Generate. Hygienic
instance spelling uses `target_instance` only when allocation changes the authored
symbol. Comments remain provenance, never authorization for native ownership or
for target grammar acceptance.

```sh
bash .work/check-generated-topology-focused.sh
PYTHONDONTWRITEBYTECODE=1 python3 .work/check-generated-topology-targets.py
PYTHONDONTWRITEBYTECODE=1 python3 .work/check-boundary-array-targets.py .work/generated-topology-public-final .work/generated-topology-public-target-final
PYTHONDONTWRITEBYTECODE=1 python3 .work/check-generated-topology-correspondence.py
```

Exact build/replay commands, per-unit logs and binary hashes are in
`.work/generated-topology-backend-handoff.md`; aggregate results are
`.work/generated-topology-focused-final.log`,
`.work/generated-topology-native-targets.log`,
`.work/generated-topology-public-target-final.log`,
`.work/generated-topology-correspondence.log` and
`.work/generated-topology-writable-parity.log`.

Focused independent source review found no concrete blocker in shared domain and
namespace proofs, lexical backend state, actual-SSA index mapping, authored analog
ordering, target hierarchy parsing or allocated-instance provenance. The review
ran no additional tests. Parser negatives retain concrete malformed syntax,
bounds, overflow and ownership cases. Two former adapter-only grammar negatives
(starting at one and stepping by two) are now lawful structural loops; they were
replaced with genuinely invalid origin/update cases while exact zero/unit-stride
adapter-emission equations remain asserted. This does not claim a target parser
can reconstruct source intent from an independently modified lawful loop.

This is generic VAMS2023 structural compiler acceptance. It is not external
simulator or OpenVAF acceptance. Existing generated procedure/operator/history
restrictions remain explicit. Generated persistent shaped declaration lifetime,
partial child-array bindings/broader joins, per-occurrence parameters/richer
induction where required, cross-range storage, remaining operator/state/result
fan-out and repeated shared-live-fill demand lowering remain unfinished F43 work.
No parent checklist is closed by this checkpoint.

The directly affected Scala predecessor replay exposed two genuine compatibility
issues during integration. The shared bridge bounds query now takes the required
structural effect: Generate domains require `topology`, whereas actual shape
queries still require `shape`. A valid legacy topology-only parameter must not
acquire a new shape requirement; forged missing-topology records still reject.
Parameter override lookup also retains its existing owning-parent check before
the new generated-scope guard, preserving the established scalar diagnostic.
New source-span capture is limited to the generated operations that consume it,
without a performance claim.

The compound-range predecessor additionally contained incidental SSA-prefix and
old induction-spelling assertions. Its replacement checks resolve actual bound
operands/defining operations, both parameter references and their addition, then
assert the exact symbolic loop using the verified Generate induction identity.
The prior failed logs are retained; these repairs do not retroactively establish
historical test validity or relax symbolic/compact-generation semantics.

All 16 selected Scala predecessors pass with both matched tools: constructor and
owning-parent behavior (2), scalar connectivity/visibility (2), legacy ranges (3),
deep generation (2), NodeArray ownership/signed64 cardinality/aliases (3), boundary
substitution and actual dual-target emission (2), reusable scalar hierarchy (1)
and retained artifact reconstruction (1). Exact selectors are in
`.work/scala-validation/run-generated-topology-predecessors.sh`; successful logs
are `.work/scala-validation/generated-topology-predecessor-{constructor-2,connectivity-2,range-3,deep-2,node-3,boundary-2,hierarchy-target-1,artifacts-1}.log`.
Pinned formatting passes all ten changed Scala files. Focused API/reference checks
pass without a signature or API-inventory change. Contribution policy, changed
Markdown structure/links, C++ pinned formatting and `git diff --check` pass.

The new public suite passes 8/8 methods. After the compatibility repairs, four
affected capture/scope/effect/dual-target methods pass again with the final tools.
All 26 source, 26 normalized IR and 26 target files remain byte-identical to the
prior completed artifact set. Logs are
`.work/scala-validation/generated-topology-public-final.log`,
`.work/scala-validation/generated-topology-compatibility-final.log` and
`.work/scala-validation/generated-topology-compatibility-final-run.log`; exact
selectors, source inventory and limits are in
`.work/scala-validation/generated-topology-scala-handoff.md`. No CI or full
qualification suite was run.

## Generated persistent shaped declarations (v0.6)

Generated structural topology is published as
`6098797e7d666636af49a5bc7cab5280703616b1`, with exact tested-tree equality,
expected-head ancestry and zero Actions runs. The next required lifetime change
is governed by [writable v0.6](../design-gates/NodalWritableRealArrays-DG-v0.6.md).
Existing native validation already admits owned static-fill initialization of a
generated shaped persistent root. The remaining limitation is target placement:
a legacy analog-iteration body cannot establish one declaration per elaborated
structural occurrence. Removing its rejection globally would change lifetime.

The implementation therefore derives structural layout from actual initialized
shaped procedure roots and distinguishes the structural-occurrence preparation
caller from the old analog-iteration caller. Static expression/shape proof,
actual storage ownership, definite assignment and declaration-only producer
peeling remain shared. No new native lifetime attribute is trusted. Pure state
must work without a dummy child instance or an unused induction argument;
existing successful direct signed64 target profiles retain their dispatch.
Focused source/native/target acceptance is complete for this selected contract.

Sibling/nested witnesses exposed a shared-owner prerequisite: both source
procedural construction and the native module verifier still treated every
procedure in a component as one scope. The source repair selects the existing
recorder by actual module and generated context, retaining distinct identities
through snapshot remapping. Native counting now partitions by actual nearest
Generate (or Module for ordinary procedures), never by an asserted owner string.
One procedure per actual scope remains enforced with `NODAL-ANALOG-033-020`;
ordinary duplicate behavior is preserved. Per-procedure definite assignment and
actual generated ownership verification remain unchanged.

A symbolic topology-only bound also exposed a target-width helper's accidental
shape capability dependency. Integer target-width checking must resolve the
actual direct module parameter, while the shared native domain and shape owners
retain their separate topology/shape obligations. This is not permission to use
parameter defaults or bypass structural-effect validation.

### Focused native/backend lifetime acceptance

The generated-persistent backend unit passes 151 checks on the matched native and
translator build. These cover fixed/symbolic static fills, topology-only compound
count, empty/singleton/million compact scopes, sibling/nested and reused-child
storage, projected uniform values, shared runtime fill users, event writes and
local entry-copy ordering. Positives repeat, pass actual native default-gate
roundtrip and target reparse. Direct preparation rejects a sibling/ancestor scope
or the legacy context before producing declarations. Same-scope duplicate
procedures, forged ownership, live initializers, zero shapes and signed32
bounds/count/intermediate/final-update failures remain explicit.

The affected topology unit passes 107 checks; event-array and hierarchy parsers
pass 308 and 236 checks. Existing writable/scalar backend aggregates pass. The
ordinary duplicate-procedure fixture independently retains the original
`NODAL-ANALOG-033-020` text. Eighty writable and 36 topology source/target artifacts
remain byte-identical. No target parser change was needed: structural declarations
already reuse the checked replicated-initializer grammar.

```sh
PYTHONPATH=.work/python-tools .work/python-tools/bin/cmake --build .work/native-release --target nodalc nodal-translate nodal-generated-persistent-backend-unit-tests nodal-writable-array-backend-unit-tests nodal-generated-topology-backend-unit-tests nodal-event-reparse-unit-tests nodal-hierarchy-syntax-unit-tests nodal-backend-unit-tests -j3
bash .work/check-generated-persistent-focused.sh
```

Exact commands, per-unit results and tool hashes are recorded in
`.work/generated-persistent-backend-handoff.md`; build and aggregate logs are
`.work/generated-persistent-matched-build.log` and
`.work/generated-persistent-focused.log`. Artifact comparison is
`.work/generated-persistent-predecessor-parity.log`. Focused independent production
review found no blocker in actual-root dispatch, verified lifetime context,
declaration placement or native per-scope procedure counting. That review ran no
tests. Expanded public source acceptance is recorded below.

### Actual symbolic generated declaration

The public `Increment43PersistentGeneratedSymbolic` witness retains separate
symbolic topology and shape parameters and a unit-bearing static fill:

```scala
val count = param(3.integer, range = 1 to 5)
val lanes = param(2.integer, range = 2 to 4)
val level = param(1.25.V)
hdlRange(1, count + 1.integer, 2): _ =>
  analogProcedure:
    val cells = variable(
      Vec(Real, lanes, 2),
      Vec.fill(Vec(Real, lanes, 3))(level).slice(1, 3, axis = 1)
    )
    cells.writable.at(0, 1) := cells.at(0, 0) + 1.0.V
    on(initialStep):
      val copied = variable(Vec(Real, lanes, 2), cells)
      copied.writable.at(0, 0) := 2.0.V
```

The actual IR keeps the static fill, owned slice and initialized shaped root in
one generated procedure; its generated owner is the actual enclosing region.
No unused induction operand is required. The emitted structural scope has the
symbolic `count + 1` upper bound, source origin one and stride two. Before its
analog body it declares:

```verilog
real event_Increment43PersistentGeneratedSymbolic_cells[0:((lanes * 2) - 1)] = '{(lanes * 2){level}};
```

The local `copied` initialization remains a reached event-body copy rather than
another persistent declaration initializer. Complete source, normalized IR and
target artifacts are under `.work/generated-persistent-public-final`; independent
target outputs are under `.work/generated-persistent-public-target-final`.

All 20 exported positive profiles (ten models, VA/AMS) pass actual native default
gate and byte-identical native replay. All 20 also pass exact direct/repeat/default
serialization/renamed-transport target parity. The positive native selector is
explicitly limited to the positive model inventory. The intentional probe
initializer negative is retained separately in `.work/generated-persistent-negative`
and independently rejects with `NODAL-ANALOG-037-009`.

```sh
PYTHONDONTWRITEBYTECODE=1 NODAL_PUBLIC_NATIVE_SOURCE=.work/generated-persistent-public-final NODAL_PUBLIC_NATIVE_GLOB='Increment43*.source.mlir' NODAL_PUBLIC_NATIVE_OUTPUT=.work/generated-persistent-public-native-final python3 .work/check-generated-topology-public.py
PYTHONDONTWRITEBYTECODE=1 python3 .work/check-boundary-array-targets.py .work/generated-persistent-public-final .work/generated-persistent-public-target-final
```

Logs are `.work/generated-persistent-public-native-final.log` (with exact per-case
commands in its output directory's `summary.json`) and
`.work/generated-persistent-public-target-final.log`. This evidence establishes
compiler structure and transactional rejection, not simulator time-domain behavior
or OpenVAF array support. Final configured Scala assertions and affected recorder
predecessors pass as recorded below.

Focused source review also found no blocker in exact module/generated recorder
selection, same-scope reentry rejection, isolated escaped handles/DA, or identity
prefix transport and bridge validation. First-state adoption excludes existing
ordinary pending storage and any started procedure, including empty or failed
invocations. Semantic owner remains the module; the optional internal prefix only
keeps procedure/variable/statement/control identities distinct. Existing source
scope lookup scans can be quadratic across many scopes; no scaling improvement
is claimed. No native lifetime flag, second procedural registry or module cloning
was added.

The configured public suite passes 5/5 methods, including ten models in both VA
and AMS with exact declaration/scope/order/repeat assertions and the causal
probe-root native negative. Log:
`.work/scala-validation/generated-persistent-public-final.log`. An isolated
second-scope control-expression reconstruction check was then added to the
identity method without changing the 20 exported target fixtures. The final
five-method suite passes again, including that reconstruction check, in
`.work/scala-validation/generated-persistent-public-final-2.log`. All 16 selected
predecessors pass: procedural ownership/restoration/DA (9), conditional DA (2),
bridge ordering/serialization (2), empty event block (1), and generated arrays (2).
Exact commands/selectors are in
`.work/scala-validation/run-generated-persistent-predecessors.sh`; final evidence
and artifact inventory are in
`.work/scala-validation/generated-persistent-scala-handoff.md`. Seven changed Scala
files pass pinned formatting; API/reference checks pass without a public signature
or inventory change. Changed Markdown, contribution policy, pinned C++ formatting
and Git diff checks pass. No CI or broad qualification suite was run.

F43 remains open. The next concrete shared-layer target is conservative array
connection relations and exact child-region coverage for selected child-port views
and bulk joins. Remaining legal generated-array/storage/operator/evaluation
compositions must be assessed against the original checklist and shared contracts;
these successful lifetime paths do not close the parent checklist.

## Conservative endpoint relations and exact child coverage

The generated persistent lifetime checkpoint is published as
`be57bd4cd206fd6f8b0a57af3462cce57c1b0d4a`, with tree
`923e4b0cb55b4559cfda4409a0f1c3ccb0978138`, parent
`6098797e7d666636af49a5bc7cab5280703616b1` and zero Actions runs.
The next integrated change follows
[NodalConservativeNodeArrays-DG-v0.5](../design-gates/NodalConservativeNodeArrays-DG-v0.5.md).
It generalizes the existing connectivity relation rather than adding a separate
local-join or partial-binding engine. Source child views retain actual instance
identity and defer effective dimensions until finalized overrides; native
verification independently resolves complete roots and exact child-region
coverage; the backend maps both relation endpoints over a shared logical ordinal.

The selected coverage certificates are exact root-coordinate boxes and proven
contiguous ordinal intervals. Columns cannot be approximated by enclosing flat
intervals. Required alias queries that exceed the finite proof profile must fail
explicitly, without assuming distinct nets or enumerating symbolic lanes.
Focused implementation and final predecessor replay are recorded below. Fixed
generated local array declarations remain subsequent required work; retained
shared-live-fill demand still needs an explicit obligation/lifetime decision
against the original checklist and approved gates. This extension is not F43
closure.

### Open generated probe-placement repair

The relation review exposed an inherited native normalization defect: an
otherwise valid source-free flow access inside a nested Generate can cause
PotentialFlowAccess to insert its derived `nodal.probe` in the enclosing Module.
The probe operands are defined inside the Generate, so the default gate fails SSA
dominance. The retained reproducer is
`.work/conservative-relation-nested-before/nested_singleton_family_distinct_lanes.mlir`;
`.work/conservative-relation-control-before.log` records the failure. Repair the
actual access-group/probe placement owner next, preserving generated scope and
source-free probe semantics. This required generated-flow work remains open.

The relation alias regression deliberately keeps its real flow consumer: an outer
repeated Generate plus an inner singleton must not claim that shared array lanes
are physically distinct. A separate selection-only singleton control isolates the
relation-family proof. That control does not establish source-free generated-flow
support or resolve the probe-placement defect. Neither F43 nor its generated-flow
obligation is closed by the relation checkpoint.

### Relation implementation and shared ownership

The source keeps declared, exact instance-port and transform shape intents in the
existing operation inventory. Child rank is known at capture; dimensions resolve
after all overrides, including dependent defaults and missing derived parent
Integer DAGs. `flatten` therefore cannot manufacture a parent expression from a
raw child formal. Required shape dependencies remain strict. Optional reuse
candidate discovery no longer evaluates unrelated Integer expressions: a fixed
binding with unrelated unbounded `p` and default `q = p + 1` causally failed
`NODAL-SHAPE-043-007` before repair and passes after it. The bridge independently
checks exact full roots, scoped projections, corresponding relation shapes and
actual incidence; exact region partition coverage belongs to the native owner.

ConservativeConnectivity replaces the asymmetric whole-binding interpretation
with two actual endpoints. ParameterModel independently substitutes the complete
child type; ShapeModel derives exact boxes or contiguous intervals, checks exact
once-only child coverage and maps scalar points through the same actual chains.
Coverage cut equality and scalar selector identity stay distinct: scalar queries
retain opaque actual SSA selector provenance. Coordinate boxes are preferred
before ordinal conversion, preserving the simple symbolic `[2,N]` row partition
without inventing `N+N == 2*N` algebraic rewriting.

Physical alias queries feed both topology and potential/flow grouping. Visible
ancestor joins are retained; other nonempty generated families and relevant
internal child true-net shortcuts produce conservative uncertainty, never
fabricated distinct nets. Every repeated enclosing Generate is considered against
actual root declaration scopes, including a singleton inner range. Child
summaries consider only explicit connection/alias operations, not conductive
device equations. Verified injective tree wiring preserves independent same-root
lanes. Repeated-scope components preserve that proof only when they are
injective acyclic trees with exactly one ancestor-shared physical root and fresh
per-occurrence remaining roots. Child shortcut summaries, cycles/parallel maps
or another shared root prevent that exemption. This retains existing generated
disjoint-column wiring without admitting the nested repeated-family short.
Unresolved cross-child correspondence, cycles or other shared generated families
remain explicit capability boundaries. Proven empty occurrences are `Inert`, not
a proof that physical nodes differ. Unknown, invalid and exhausted required
queries reject transactionally.

Coverage uses at most 200,000 query work units and 16,384 memoized states; scalar
alias traversal uses 16,384 steps and 1,024 derived states. Fixed ordinal intervals
split into at most `2*rank+1` boxes. These are explicit proof resource boundaries,
not physical lane limits. Authored cut/rank combinations may be combinatorial,
while repeated source/definition scans and comparison calls can remain quadratic
or worse in authored structure. No whole-pipeline linearity or measured speedup
is claimed. The implementation does not expand symbolic lanes or add a second
mutable topology registry, storage DA engine or general algebra solver.
Focused source and native/backend architecture review found no remaining concrete
blocker after the unrelated-parent-default and nested-family findings were fixed.
The final fresh-tree compatibility delta was independently inspected. Review
was read-only; the executed checks below belong to the implementation replay.

The backend allocates each full child bus once and maps both relation sides over
one logical ordinal with the existing true-net alias helper. Scalar partitions
use the same bus. A validated derived potential-equality record for a partial
owner is admitted only after actual endpoint equality proof; no numerical
contribution is added. Whole direct substitutions retain their identity-map
optimization. A contiguous part-select shortcut requires a direct root endpoint
with one use; a shared projected child view instead keeps the full bus so later
scalar selection cannot become an invalid selection of a part-select.

### Focused relation acceptance and actual output

The six new public methods pass: four capture/ownership/forged-root methods and
two configured native/target methods. All 14 selected boundary/generated
predecessor methods also pass; the six native-containing methods were replayed
on the final matched tools. Exact selectors are retained in
`.work/scala-validation/conservative-relations-validation.sh`; final replay logs
are `.work/scala-validation/conservative-relations-native-replay.log`,
`.work/scala-validation/conservative-relations-boundary-native-replay.log` and
`.work/scala-validation/conservative-relations-generated-native-replay.log`.
Eight models produce 16 actual VA/AMS
profiles: adjacent child slices, dependent-default columns with late overrides,
missing derived parent-DAG materialization, child/child joins, scalar complete
partitions, noncontiguous local joins, generated partitions and two-million-lane
compact relations. Exact missing/overlapping coverage negatives retain
`NODAL-SHAPE-043-009`. Configured logs are
`.work/scala-validation/conservative-relations-capture-final.log` and
`.work/scala-validation/conservative-relations-native-final.log`.

Native focused results: relation matrix 54, region unit 45, node-array unit 187,
storage unit 21, parameter/connectivity units, boundary predecessors 49 and
generated-topology predecessors 50 all pass. Backend results: new relations 140,
boundary arrays 54, generated topology 107, generated persistent state 151,
hierarchy parser 236, event reparser 308 and the affected aggregate backend pass.
The nested-family before-change unexpected success is retained alongside the
exact rejection after repair; the symbolic row false rejection now passes without
broad algebra changes. The final affected generated predecessor exposed an
overconservative family rejection for `taps.at(lane, 0)` and `taps.at(lane, 1)`
wired to fresh child ports. The shared fresh-tree proof above restores that
unchanged positive; three causal native controls and all 26 generated predecessor
profile inputs pass plain native and default gates, while the repeated-family
short still rejects. A runner typo rejects rather than succeeding with zero
selected cases. The formerly rejected child projection is now a positive; the
unchanged malformed child body retains its exact earlier structural diagnostic.

Same-source target repeats remain byte-identical including comments. One
inherited equivalence test for differently authored direct/composed views now
compares exact semantic HDL while independently asserting each truthful source
record; their distinct authored provenance cannot correctly have identical
comments. Forged potential records, removed source relations and independent
left/right target mutations reject transactionally. Removing correspondence
comments does not grant or remove semantic target acceptance.

Reproduction commands and complete selected unit lists are retained in
`.work/conservative-relations-native-handoff.md` and
`.work/conservative-relations-backend-handoff.md`:

```sh
PYTHONDONTWRITEBYTECODE=1 python3 tests/compiler/fixtures/increment43/run_conservative_relation_matrix.py --nodalc .work/native-release/bin/nodalc --work-dir .work/conservative-relation-native-final
bash .work/check-conservative-relations-backend.sh
PYTHONDONTWRITEBYTECODE=1 NODAL_PUBLIC_NATIVE_SOURCE=.work/conservative-relations-public-final NODAL_PUBLIC_NATIVE_GLOB='Increment43*.source.mlir' NODAL_PUBLIC_NATIVE_OUTPUT=.work/conservative-relations-public-native-final python3 .work/check-generated-topology-public.py
PYTHONDONTWRITEBYTECODE=1 python3 .work/check-boundary-array-targets.py .work/conservative-relations-public-final .work/conservative-relations-public-target-final
```

All 16 actual public sources pass the default gate and stable native replay.
Their generated targets pass exact direct/repeat/default-gate/renamed-transport
parity. Actual source/normalized/target files and per-case logs are in those named
output directories. Matched tool hashes are:

- `nodalc`: `d3d1103e9935431179c1de259479495f85f3a59afe214e7763ed67bae4b73397`
- `nodal-translate`: `85f25a51fdeb78f1a4bf1c64a2853c4292de8929b459c2af542735fad6ef4447`

The two-million-lane public target is 6,583 bytes; the million-occurrence generated
partition target is 3,836 bytes. These are compact output-size observations,
not runtime measurements. New mapped carriers retain the signed32 structural
Integer target profile; independently valid direct signed64 paths remain.
Acceptance is the selected generic VAMS2023 compiler/structural profile. No
simulator, OpenVAF electrical-vector or alias-helper execution is claimed. The
previously executed Real-array OpenVAF limitation remains a distinct F48 handoff.

For an actual source example, `Increment43RelationSplit` in
`core/scala/testkit/test/src/nodal/internal/testkit/Increment43ConservativeRelationTests.scala`
captures two child slices before the final override:

```scala
val taps = node(Electrical, 4)
val child = instance(new Increment43RelationChild)
val lower = child(_.p.slice(0, 2))
val upper = child(_.p).slice(2, 4)
taps.slice(0, 2) <> lower
upper <> taps.slice(2, 4)
child.param(_.count, 4.integer)
```

Its actual `Increment43RelationSplit.va.target` declares one full
`electrical [0:(4 - 1)] binding_child_instance_p_bus`, emits two compact two-lane
alias maps (offsets zero and two), and binds the child once:

```verilog
child #(.count(4)) child_instance(.p(binding_child_instance_p_bus));
```

The complete generated module, including both actual map expressions and source
records, is retained with the public target artifacts. The child definition and
its physical equations are emitted once; no per-lane module specialization is
introduced. F43 remains open, with the generated-probe repair above as the next
concrete shared-scope implementation.

Changed native files pass pinned clang-format22.1.8; changed Scala formatting and
API inventory/reference checks pass. The four changed Markdown files, exact
intended-file contribution-policy check and Git diff checks pass. The gate uses
separate scope markers required by the policy parser. No CI or broad
qualification was executed, and no F43 checklist state is changed.

## Generated source-free probe placement

The conservative relation checkpoint is published as
`9f04db02e054bba18ab5a0580f245fe5d05a5084`, exact tested tree
`0f829b2b2e2a424dfc8e8993c835226d83880ebe`, parent
`be57bd4cd206fd6f8b0a57af3462cce57c1b0d4a`, with zero Actions runs.
The next required F-043.B.2/B.3 repair addresses the retained nested singleton
flow failure described above. Existing F31 physical source-free branch semantics
and the approved generated-topology ownership contract remain authoritative;
this is an internal shared-owner repair, not a new public signature.

PotentialFlowAccess currently groups across a module without retaining active
occurrence support and appends every derived probe to the module body. ProbeOp
also requires that parent, and the structural backend rejects generated records.
Changing only the insertion point or splitting physical groups by lexical scope
would leave semantic gaps. The selected correction separates physical grouping
from checked support/placement. Named branches retain actual branch SSA identity;
implicit groups reuse conservative physical endpoint proofs. Same invariant
endpoints used across scopes must retain their existing mixed-kind and sourcing
rules. Proven-empty occurrences contribute no active access kind or sourcing,
while their source structure still verifies. Maybe-empty sourcing and overlapping
families require a proof within the existing domain/connectivity profile or an
explicit capability rejection.

Derived records must use actual dominating subjects at their validated
Module/Generate support, preserve canonical provenance, and verify independently
against the reconstructed source groups. Normalization must plan before replacing
records and remain idempotent. Generated target admission consumes that native
contract and adds no numerical constraint or duplicate physical branch. Existing
shared domain, endpoint and generated ownership owners are reused; no second
connectivity or conditional-count engine is introduced. Focused implementation
and positive/negative target checks are in progress; no result is claimed yet.

The new cross-occurrence endpoint entry point independently validates each SSA
reference at its own use and proves each physical point in that context. Exact
relation edges are limited to common structural ancestors; non-common generated
wiring remains a family in the existing uncertainty traversal. Same-use queries
use that same engine. No coordinate arithmetic or root registry is duplicated.
Activity facts come from ParameterModel's existing checked bound, direction and
captured-count analysis: identical bounds or disjoint ordered envelopes prove
empty/nonempty support, otherwise activity remains maybe-empty. Legacy
no-induction negative-step ranges retain their direction; explicit induction
domains keep the positive-step rule. This adds no trip-count enumeration and
makes no universal alias/support or scaling claim.

### Focused probe result and compatibility

The retained nested singleton flow fixture now passes the actual default gate,
normalization repeat and normalized replay. The new public nested selected-flow
model independently reproduced the pre-change dominance failure, exit 1 on a
module-level probe using a generated branch. Its exact source hashes and failure
are retained in `.work/generated-probes-before/`. The native reproducer is now
tracked as
[`generated-probe-nested-singleton.mlir`](../../tests/compiler/fixtures/increment43/generated-probe-nested-singleton.mlir).

Focused native acceptance passes 39 cases: 27 source cases and 12 independent
mutations of accepted normalized records. These cover generated/nested/empty
supports, source-free kinds, named versus implicit branches, same invariant
selected endpoints in siblings, uncertain overlapping families, guaranteed versus
maybe-empty sourcing, and exact moved/missing/duplicate/forged records. Legacy
uncaptured no-argument negative-step generation retains native acceptance:
probe-specific ownership requires absent generated attributes for that actual
legacy scope, while captured scopes require the exact pair. No arbitrary
identity is synthesized. The target still has its separate existing range
capabilities; this does not claim negative-step HDL support.

The shared-query NodeArray unit passes 191 assertions, including real invalid
sibling/foreign/null-use and ambiguous-induction API controls. Existing parameter
checks, the 54-case conservative relation matrix and four targeted F31
normalization/reference/mixed-kind/forged-provenance predecessors pass (seven
selected CTests). The new backend unit passes 102 checks; affected generated-topology 107,
conservative-relation 140 and aggregate backend checks pass. Signed64 singleton
coordinates retain the legacy target layout without bypassing the captured trip
count contract. Direct renderer mutations reject transactionally. The three new
public Scala methods pass (one capture, two configured integration), producing
four actual VA/AMS profiles. The observed source branches remain source-free;
their values feed a distinct output contribution. Both repeated flow reads and
the potential read retain actual semantic-path provenance. Active mixed-kind
invariant sibling access rejects `NODAL-PROBE-KIND-001`; the proved-empty sibling
contributes no intent. Unproved support reports registered
`NODAL-PROBE-SCOPE-001`, without claiming a conditional-domain solver.

All four exported public inputs pass independent default-gate/replay and exact
direct/repeat/default-gate/renamed-transport target parity. Inputs, normalized IR,
targets and logs are retained under `.work/generated-probes-public-final`,
`.work/generated-probes-public-native-final` and
`.work/generated-probes-public-target-final`; their hashes are recorded in
`.work/generated-probes-final-validation.json`. Final matched tool hashes:

- `nodalc`: `2d090605f08551d95a5ffed6c2adde5ed7fd1634417197c3a976ea5d0b0300d1`
- `nodal-translate`: `b5c575629bd8469697216540b36805f0af4108ffdf3fb5c5e74173407376507d`

```sh
PYTHONDONTWRITEBYTECODE=1 python3 tests/compiler/fixtures/increment43/run_generated_probe_placement_matrix.py --nodalc .work/native-release/bin/nodalc --output-root .work/generated-probe-placement-native
.work/native-release/core/compiler/test/Unit/nodal-generated-probe-backend-unit-tests
PYTHONDONTWRITEBYTECODE=1 NODAL_PUBLIC_NATIVE_SOURCE=.work/generated-probes-public-final NODAL_PUBLIC_NATIVE_OUTPUT=.work/generated-probes-public-native-final python3 .work/check-generated-topology-public.py
PYTHONDONTWRITEBYTECODE=1 python3 .work/check-boundary-array-targets.py .work/generated-probes-public-final .work/generated-probes-public-target-final
```

Focused architecture review found no remaining concrete issue in physical
identity versus support placement, activity facts, exact owner/dominance checks,
record inventory or target admission. This shares the prior endpoint/parameter
owners and adds no emitted equation, duplicate physical registry or count engine.
Query-local activity caching avoids repeated activity analysis within one plan;
physical grouping and repeated connectivity traversals can still be quadratic or
worse. No whole-pipeline scaling improvement is claimed. Probe replacement occurs
after complete planning; earlier in-place selector/reference normalization is
not rolled back. The existing clone/output boundary remains transactional.

For actual public source, `Increment43NestedSelectedProbes` in
`core/scala/testkit/test/src/nodal/internal/testkit/Increment43GeneratedProbeTests.scala`
uses existing selected terminals inside nested `hdlRange` calls:

```scala
analog:
  I(output, common) <+ I(current, common) + I(current, common) + V(voltage, common) / 1.kOhm
```

Its actual Verilog-A target contains the following statement inside the nested
structural loops, with the three row-major map constants declared in that scope:

```verilog
I(outputs[node_map_outputs_2_0], common) <+ ((I(currents[node_map_currents_0_0], common) + I(currents[node_map_currents_0_0], common)) + (V(voltages[node_map_voltages_1_0], common) / 1000));
```

Two checked native probe intents remain inside the generated owner; no extra
zero constraint is inserted into the target. This is generic compiler/structural
VA/AMS acceptance, not simulator execution. Generated local conservative-array
declarations are the next required F43 implementation. F43 remains open.

Exact Scala selectors and commands are in
`.work/scala-validation/generated-probes-scala-handoff.md`; native/backend
commands, case inventories and finite limits are in
`.work/generated-probe-placement-test-handoff.md` and
`.work/generated-probe-backend-handoff.md`. Changed Scala/native formatting,
Markdown/link checks, diagnostic-catalog JSON parsing and diff checks pass.
No checklist state changes or CI executions are part of this repair.
