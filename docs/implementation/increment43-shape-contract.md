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
