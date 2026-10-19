# Increment 55 readiness and implementation

Status: complete after verified squash `eee3d7ed9c9fb39e3bf79ff7c554cc0a90da632e` (PR151). Base
`ec307cef5e11e2a852131fb8e16aa93195f397e4` is the verified F54 documentation closure.
Branch: `increment/55-digital-expressions-iteration`.

## Original obligations and owners

The original F55 checklist requires typed combinational expressions, exact width/
sign/shape rules, bounded staged iteration, collection operations, continuous
assignments, cycle/driver/effect checks and deterministic materialization/lowering
choices. ADRs 0016/0017/0018 and the approved unified `hdlRange` amendment remain
binding. Explicit `loop(LoopBound)` is procedural-only; common `hdlRange` staging
is selected by actual effects, not a new family of required public range names.

Linked shaped-value BT-01/02/03 obligations include ordered adjacent-pair balanced
reduction, odd carry, singleton bypass, symbolic sizes/widths, growing intermediate
types and whole aggregate callbacks. They do not redefine left reduce/fold order.
F58 shares structural integration; F65/66/67 own later target emission/execution/
proof consumers. No full later parent is a reverse prerequisite.

The approved [Area/symbolic-conditional amendment](../roadmap/areas-symbolic-conditionals-v0.1-plan.md#existing-owners-and-new-descendants)
also assigns **F-055.B.1.1** here: structural parameter/index predicates, Boolean
composition and statement/value-producing `if/else` with exact branch results and
lexical lifetime. This child is implemented and focused-tested by the structural checkpoint below,
following the arithmetic, collection, reduction and bounded-loop checkpoints. Its
minimum additive syntax/gate dependency
is F-058.A.1; complete Area/hierarchy and mixed sequential integration remain with
F58/F159. Ordinary Scala equality, default-value branch selection, a runtime mux or
analog procedural control is not evidence of this structural conditional feature.

## Initial architecture and first implementation (historical checkpoint)

Reuse F54 DigitalValueConstruction, DigitalValueModel, storage/assignment and the
single bridge value schedule. Existing shape and ParameterModel owners retain
identity, checked arithmetic and parameter substitution. A canonical static maximum
is required by the approved lossless width formulas; do not approximate it with
sum widths or evaluate a parameter default. Source/native scalar implementation
starts at the additive [F55 gate](../design-gates/NodalDigitalExpressions-DG-v0.1.md).

Existing source `map`, `reduce`, `loop`, arithmetic and materialization candidates
still contain inert calls. The frozen `zip(other): Expr[Vec[A]]` cannot retain B;
its implementation needs an explicit compatible migration plus a genuine typed
combiner/pair representation. LoopIndex requires actual scoped induction access.
No placeholder, forged zero induction or discarded pair field satisfies F55.

## Initial pending decisions and focused validation (historical checkpoint)

ADR 0016 requires division/remainder and minimum/maximum. The approved additive
[arithmetic gate](../design-gates/NodalDigitalArithmetic-DG-v0.2.md) defines exact
width/sign rules and a proven-nonzero-constant divisor profile; implementation
remains pending. Frozen shifts take Int amounts, so runtime shift overloads are
not an additional F55 prerequisite and remain explicitly unsupported. Symbolic
width-changing reductions require genuine type recurrence, not a fixed-width
accumulator with hidden truncation. Collection, loop and materialization contracts
will be specified before their implementation depends on them.

Run focused positive/negative/boundary checks and directly affected regressions;
retain actual Scala→IR artifacts and concise architecture/scale review. No CI,
hourly monitor, full qualification or unsupported target-execution claims.

## Focused implementation progress

The shared static integer owner now carries `max` through source capture, interval
analysis, bridge constant expressions and native parameter/override identity.
The focused native parameter-model executable passes, including its predecessor
cases and new maximum bounds, type/arity/owner/unit, overflow and override checks.
Rendering is preflight-bounded by expanded bytes, graph depth and unique nodes:
an eight-level shared maximum produces exactly 305,831 bytes; forty-level shared
maximum/add graphs, 513 unary levels and cycles reject before text expansion.
This is a finite checked rendering profile, not an unbounded scaling claim.
Raw logs are retained under `.work/increment55/static-max-render-*.log`.

The first digital scalar checkpoint passes 36 focused source cases (F55 operators,
F54 conversions/enums and static maximum), 21 bridge/predecessor cases, and native
DigitalValue 203/203, DigitalType 81/81, DigitalStorage 66/66, EnumValue 68/68 and
the parameter-model executable. Nine preserved actual Scala-generated source and
normalized artifacts replay with the final native binary and retain byte-identical,
idempotent normalized output. Commands and raw logs are under `.work/increment55/`.

Equivalent symbolic widths use canonical ordered static DAG identity across source,
select, enum-select and assignment. Maximum idempotence applies only to the same
actual expression identity; distinct parameters with coincident bounds remain
unequal. No parameter default is substituted. Shared width/type queries retain
nominal aggregate and enum identity. A focused review caught enum width admission
into scalar operators; the repaired scalar guard rejects enum arithmetic and
conversions while shared enum validation preserves legal constants and enum
expressions as selection data. Sparse-code negative and select-positive tests
exercise that boundary.

Formatting, focused new-file lint, capability-registry generation and diff checks
pass. The syntactic lint replay of shared construction files still reports ten
pre-existing return/throw diagnostics in unchanged code; no suppression was added.
This checkpoint does not close any F55 checkbox. Collection operations, loops,
materialization and the remaining original/amendment obligations remain open.

The next collection tranche follows the approved
[typed collection gate](../design-gates/NodalDigitalCollections-DG-v0.1.md): map,
zipWith, exact homogeneous fold and seed-excluded, shape-preserving scan. A real
isolated SSA region retains callback arguments, captures and yield. Widening
reduce/balanced-tree recurrence, bounded loop writes and unified hdlRange
integration remain open; homogeneous fold is not a substitute. Callback-effect
validation is implemented in the collection checkpoint below.

The scalar checkpoint identified a directly affected follow-on: interface-drive
and resolved-net payload gates compared raw native type spellings. The collection
checkpoint below repairs those gates through canonical same-owner identity and
adds a distinct causal Interface witness; a scalar assignment positive alone does
not establish Interface drive support.

## Typed collection checkpoint

The real public map/zipWith/fold/scan path now captures one typed lexical body,
explicit input/seed/capture edges, actual arguments and a yield. Source, bridge and
native verification reuse their existing type, width, shape and SSA owners.
Collection callbacks are checked by one phase in the existing Scala plugin;
private runtime carriers refuse an uninstrumented callback before execution.
Same-compilation pure helpers and immutable captures are supported. Unknown host
effects, mutable/lazy state, host topology, recursive/open-dispatch helpers and
unavailable helper bodies reject. This is a finite checked profile, not a general
Scala purity proof or a reflection security boundary.

Native collection checks pass 76/76, with scalar/enum/type/storage/interface and
capability regressions. Affected F43 index/view/slice checks pass 40/40, 43/43 and
46/46. A broad computed-source admission initially weakened an existing slice
negative; the repaired shared provenance gate retains that rejection. Public
source checks pass 11/11 and bridge checks pass 13/13, including ten actual
source-to-native idempotent roundtrips. Selected predecessor checks pass: scalar
bridge 9/9, digital bridge 5/5, enum bridge 9/9, composed shape 4/4, slice 2/2
and Interface payload 1/1. The Interface case verifies both root and child drives
and the exact distinct-parameter negative. Its first child attempt failed solely
because the test omitted its working directory; the corrected test uses the
existing diagnostic-preserving verifier helper. A checked-conversion
witness exposed missing constant facts across immutable collection captures; the
repair follows actual verified capture operands, never element or accumulator
arguments. A positive fold seed cannot prove every later accumulator positive.
The exact old-binary failing IR now verifies, and the public nested-capture and
forged-negative-literal regression passes; shared scalar checks pass 203/203. The standalone callback harness
passes 18 exact source-located negatives, typed positives, bounded traversal,
public runtime capture and refusal before uninstrumented callback effects.

The current collection body supports literal local selectors, symbolic input
shapes/element widths, nested collections and whole stored aggregate values.
Module-static nonliteral selector captures are explicitly unsupported at this
boundary; root runtime Real/Integer values and unit-bearing analog producers are
not admitted. Input cardinality does not cause per-lane callback execution or SSA
expansion. Source/native traversal budgets remain explicit; repeated nested
signature/type queries are not claimed to make the whole pipeline linear.

The directly affected Interface/resolved-net payload gates now reuse canonical
same-owner type equivalence instead of raw symbolic type spellings. Clock/Reset
boundary types and actual driver/domain/provenance checks remain strict. Source
final-capture type comparison uses the actual declaration's module owner, including
child modules; the public root/child witness passes the final replay.

Widening reduce/balanced-tree recurrence, bounded loop effects and unified hdlRange,
materialization, and the arithmetic v0.2 follow-on remain required open work.

### Executed public collection witness

The source-to-native replay uses `DigitalCollectionMapSource` in
`Increment55DigitalCollectionTests.scala`, with its explicit `DigitalCollectionClock`
fixture. Its actual body includes:

```scala
val rows = param(2.integer, range = 1 to 1000000)
val input = in(Vec(UInt(4), rows, 3))
val mapped = input.map(value => value + 1.U(4))
val narrowed = mapped.map(value => value.truncate(4))
val selected = narrowed.at(0, 0)
val output = out(UInt(4))
output := selected
```

The verified result retains symbolic dimensions, two typed collection regions,
the widening addition and explicit truncation, followed by the actual selected
output assignment. It is a source/normalized-IR witness; digital target execution
remains with the later target owners. Repeated child instances are also replayed,
retaining their distinct source-significant constructor/domain metadata rather
than forcing a false reusable-definition equivalence.

Focused replay uses `Increment55DigitalCollectionTests` and
`Increment55DigitalCollectionBridgeTests` through the existing Mill test owner,
the native `nodal-digital-collection-model-unit-tests` and
`nodal-digital-value-model-unit-tests` targets, and the callback runner documented
in `tests/scala/digital-collections/README.md`. The final native replay binary has
SHA-256 `0a68932fae99f49e638a07b37b69fc730a1aad10feb90e5ad33a44d379808627`.
Raw logs and the ten source/normalized pairs remain under `.work/increment55`.
Focused source/native architecture review found no remaining concrete defect in
this checkpoint. Formatting, affected syntactic lint, generated capability
registry consistency, Markdown and whitespace checks pass, with the ten inherited
shared construction lint diagnostics described above unchanged. No F55 checklist
child is closed by this intermediate checkpoint.

## Remaining shared owners after collection integration

The required loop/assignment tranche must add global ordinary wiring checks;
current per-assignment checks alone do not prove absence of competing wire/output
writers, undriven consumed regions or combinational feedback. The shared storage,
Interface and Shape owners must cover those cases, including Interface/net-only
feedback, before F55 closure. Isolated staged implementations are not integrated
acceptance evidence.

BT record witnesses also require typed Struct field reads and whole-value
construction. Existing whole-record selection with an external flag does not
establish score-based winner or shared cross-field callbacks. The extension uses
canonical semantic schema identity and an explicit width-family relation while
preserving exact instantiated ABI equality. It does not invent host-object
identity or a global schema registry. Widening reductions must prove actual
intermediate types, singleton and odd-carry behavior over legal count/width
envelopes; a final width formula alone is insufficient.

## Bounded arithmetic checkpoint

The approved [arithmetic v0.2 gate](../design-gates/NodalDigitalArithmetic-DG-v0.2.md)
is integrated through the existing scalar source, bridge and native owners.
Same-kind UInt/SInt division and remainder accept an arbitrary runtime numerator
and an independently proven exact nonzero divisor. This includes computed and
explicitly captured immutable divisors. A parameter default alone is not proof;
zero, unproved runtime divisors and cross-kind operands reject. SInt quotient
width is numerator width plus one, preserving minimum/-1; remainder retains the
numerator width and sign under truncation toward zero. UInt results retain
numerator width. Min/max use exact same-kind mathematical comparison and canonical
maximum operand width. Enum values do not enter this scalar arithmetic profile.
No latency or numerical fallback is invented.

Native checks pass scalar values 330/330, collection 76/76 and enum values 68/68.
The seven-suite Scala replay passes 62/62, including arithmetic 8/8 and collection
bridge 13/13. Actual public arithmetic source and normalized native artifacts are
retained with hashes under `.work/increment55-arithmetic`; final witness digests
are in `.work/increment55/arithmetic-final-witnesses.sha256`. The native replay
binary SHA-256 is
`ee723e0bbe66db3bdb8f9413f343f3ff8a5479753a923d5db2b3c12b89310ae9`.
Three directly affected source-origin predecessor tests also pass. Focused
formatting and syntactic lint pass for all changed Scala files.

A directly affected source-discovery defect was discovered during replay:
repository scratch copies under `.work` participated in authored-source matching.
Equal-scoring copies made source locations unavailable and changed child-definition
provenance/canonicalization. The shared locator now excludes only the detected
repository's root `.work` subtree. Authored nested `.work` directories and explicit
external roots retain discovery; absent source still reports an unavailable span.
The new unique-file regression demonstrably failed under the old policy and now
passes. The original repeated-child assertions pass unchanged; no semantic
metadata was stripped and no module-count assertion was weakened. Focused review
found no remaining concrete defect in the arithmetic or locator delta. F55 and its
linked reduction/iteration obligations remain open.

## Target-neutral materialization checkpoint

The approved [materialization v0.1 gate](../design-gates/NodalDigitalMaterialization-DG-v0.1.md)
preserves the frozen naming, keep and quality forms through typed aliases and one
native actual-use inventory. Shared values and explicit keep boundaries require
materialization; source naming and Debug policy add their documented reasons.
The report preserves exact types, lexical identities and individual operand edges,
with stale-report rejection and transactional replacement. It does not emit wires
or establish target-specific optimization equivalence. F65/72 must add their actual
type, signed-view, depth, procedural and tool-legality requirements before emission.

Integrated native checks pass materialization 40/40, scalar value 330/330,
collection 76/76 and enum value 68/68. The combined Scala replay passes 78 cases,
including seven new source/bridge/native materialization cases. The actual default
normalization gate is invoked explicitly; parse-only acceptance is not counted as
planning evidence. Additional naming predecessors pass 3/3 and reproducibility
predecessors pass 2/3. The directly affected native RcFilter normalization,
Verilog-A/golden and manifest-repeatability case also passes 1/1 with the rebuilt
translator. Source and normalized witnesses are retained under
`.work/increment55/materialization-final-witnesses`. The pinned native binary
SHA-256 is `c5e65026bc32a14762aac8e78deaac4a17f24e53ae80f75c7fa0302cb18d827a`.

The remaining reproducibility fixture fails on its CDC-waived Bool assignment:
its RHS has no value in the typed digital schedule, before planning runs. Rebuilding
all bridge sources from the preceding checkpoint reproduces the exact
`NODAL-BRIDGE-054-ENUM` failure at
`ReproducibilityDomainFixture.digital_assignment_0` against the current captured
fixture. That is a bridge control, not a full historical API replay. The failing
case is retained and is not claimed passed; no fabricated CDC value is added.

Fresh private reproducibility receipts use schema v2 for requested quality,
provenance-only source decisions and normalized-artifact inventory references.
Historical fixtures/receipts and analog decisions are unchanged. Requested checks
or waivers are not relabeled as executed checks. The reviewed planner preserves
actual SSA operand occurrences, source aliases and complete types without arithmetic
reassociation; target-specific legality and physical emission remain pending.
Focused formatting and whitespace checks pass. Syntactic lint retains exactly
three unchanged baseline diagnostics: ConstructionSession's existing return/throw
and ReproducibilityContract's existing throw. No new lint violation is introduced;
the full lint invocation is not labeled passing. No F55 parent or child is closed
by this checkpoint.

## Shared static width prerequisite checkpoint

The approved [static width arithmetic gate](../design-gates/NodalStaticWidthArithmetic-DG-v0.1.md)
extends the existing static Integer owner with checked `ceil_log2`, `choose_zero`
and private signed-64 derived arithmetic. Actual width consumers explicitly select
mathematical signed-64 proof mode; ordinary parameter/storage interpretation keeps
its existing default. Final positive width/resource checks, actual parameter
identity and full operand-DAG validation remain mandatory. Generic rendering is
bounded before expansion, and current analog targets explicitly reject the new
primitives. This is a required widening-reduction prerequisite, not a reduction
implementation or downstream compilation claim.

Integrated focused checks pass 41 Scala cases, including the actual static-width
source, arithmetic/materialization predecessors and four selected exact predecessor
cases. Native ParameterModel passes; integer bounds 58/58, nonzero 44/44, shape
45/45, digital types 91/91, target capabilities 74/74 and materialization 40/40 pass.
Ten fresh source/native controls preserve symbolic identities and complete parameter
verification. A compiler with the new shared engine but old width consumers rejects
the signed intermediate witness; the integrated width consumers accept it. The
same live archive independently tests default storage-mode rejection versus explicit
width-mode acceptance. No default value is used as all-legal proof.

The preceding normalized materialization artifact still verifies without running a
repairing planner; its hash-only forgery rejects with
`NODAL-MATERIALIZATION-055-001`. Generic expression rendering succeeds for the
finite primitives while both capability and direct-render analog paths reject them.
The rebuilt native compiler SHA-256 is
`e2deb038e30a9082b6b473ee5dd164c69e98dd8a009bb9fbad0e446d32d158b4`.
Exact commands, artifact hashes and logs are retained under
`.work/increment55/reduction-static-stage/integrated-results`.

Focused formatting passes; syntactic lint introduces no new diagnostic and retains
ConstructionSession's two unchanged return/throw findings. Focused architecture
review found no concrete defect. The implementation reuses the existing static
DAG, checked interval arithmetic and immutable identity/substitution owners rather
than adding a reduction-specific evaluator. Expansion accounting rejects oversized
text before rendering; this is a resource bound, not a whole-pipeline speedup claim.

F55 and its linked BT children remain open. Scoped formal-width environments, typed
Struct value integration, driver validation and actual reduction/loop semantics
remain required follow-on work.

## Typed Struct value prerequisite checkpoint

The approved [Struct value gate](../design-gates/NodalDigitalStructValues-DG-v0.1.md)
adds private typed member/binding handles and real record get/make operations to the
existing digital value DAG. Equivalent reconstructed semantic schemas remain
compatible; host-object identity is not an ABI. Construction/projection may use the
checked width-family relation, while each resulting complete type retains actual
width expressions and all ordinary assignment/layout equality stays exact.
Whole-record selection and one shared cross-field predicate are actual DAG cases,
not separate per-field callbacks. Materialization metadata/classification and source
origin handling are integrated through their existing owners.

Integrated checks pass six tracked-source Struct cases and 101 affected source
predecessors. Ten additional selected checks produce nine passes and the same
retained CDC receipt failure documented in the materialization checkpoint; it is
not counted as passing. Native value 362/362, type 103/103, storage 68/68,
collection 76/76, materialization 40/40, enum 68/68, integer bounds 58/58 and target
capability 74/74 pass; ParameterModel also passes. Formatting is clean. Syntactic
lint retains the same three unchanged ConstructionSession/ReproducibilityContract
findings and introduces no new diagnostic.

Actual tracked source spans and native source maps retain the authored fixture
path. InlineSafe, Readable and Debug native replays each retain 59 inventory
records, including 28 gets and two makes. Actual SSA inspection confirms the two
whole-record winners and shared comparison inputs for cross-field construction,
including callback ownership. All three outputs normalize idempotently; a
same-typed changed operand under the old inventory rejects. Schema-only static
width expressions are included in the existing digital type-root schedule, fixing
a captured-but-unemitted metadata dependency discovered by the integrated witness.

Source MLIR SHA-256 is
`1a5732cd09054b03d132d5f11e9434e2f5ecce0fd1b122ca40353709bd0e85df`;
normalized MLIR SHA-256 is
`7d97f91d82bd049d6d6fcd7ba00b0551dca46bc6dc4a41c282fdf41de464ba69`.
The native compiler SHA-256 is
`7e276d8d1d94329de74785e3c55a72ad8ae3f94680b746193bcd58de49d0f3b4`.
Exact commands, frozen source/test hashes and all policy artifacts are retained
under `.work/increment55/struct-value-stage/integrated-results`.

The architecture preserves exact complete ABI and existing source Bool/native
Bits<1> representation, with no generative schema registry or per-field callback
replay. Bounded shared type/DAG traversal is reused; representative sharing checks
do not establish a whole-pipeline scaling claim. These are verified native MLIR
witnesses, not generated HDL. F55/BT remains open for scoped reduction proof/capture,
driver validation and bounded writable iteration.

## Combinational driver prerequisite checkpoint

The approved [driver gate](../design-gates/NodalDigitalDrivers-DG-v0.1.md) integrates
actual SSA/storage dependencies with canonical Shape and Interface owners.
Consumed ordinary storage requires physical driver coverage; conflicting writers
and combinational feedback reject, including standalone Interface/net graphs.
The existing F54 consumed-undriven wire fixture receives a real exact-type driver;
independent causal undriven negatives remain required.

Fresh integrated replay passes 890 native checks and 63 selected Scala cases.
The 21 actual public graphs comprise 11 idempotent positives and ten exact-cause
negatives; graph counts are not additional test-case counts. Native storage 103,
Interface 151, Shape region 55, materialization 40, value 362, type 103 and
collection 76 all pass. The Scala selection covers driver/source admission, F54
storage and Interface, materialization, Struct, collections and six Shape
predecessors. Formatting, changed-file syntactic lint and whitespace checks pass.
Historical isolated stage results remain separate from these integrated results. Public readonly Vec projections remain readonly until the
separate writable Signal capability is integrated. Register-next intent, bounded
loop ordering/cross-iteration proof and widening reductions remain open. No F55
checklist is closed by this design checkpoint.

The affected Interface replay exposed a Struct-prerequisite bridge regression:
exact type comparison unconditionally selected storage validation, rejecting valid
Clock/Reset Interface boundary drives. The shared comparator now accepts an
explicit boundary validation context only at Interface effect rendering; ordinary
values, width-family comparisons and nested Struct fields retain storage
restrictions. Actual source-root/domain provenance checks are unchanged. The
strengthened 14-case Interface suite passes scalar/Vec Clock and Reset drives,
cross-kind and nested-storage negatives, and all 13 native source round trips.
An earlier contended run also recorded a process-group status timeout; it is
retained as a failed attempt. Frozen artifact replays are idempotent and the
unchanged assertion passes on focused replay, without changing native output or
loosening the shared runner timeout.

The frozen native compiler SHA-256 is
`8ad2acdb89dc06a205de58f1d74bf16992ce49b71187f53cb15ecc81815cdb48`.
Exact commands, final source hashes, positive/negative artifacts and failed-attempt
logs are retained under `.work/increment55/driver-live`. Fresh Struct replay keeps
28 gets, two makes and 59 materialization records; changed-edge stale inventory
rejects. The architecture uses iterative actual-SSA expansion and cycle detection,
with bounded canonical physical-region queries. Same-root comparisons may be
quadratic in authored regions; no physical lane enumeration or general scaling
claim is introduced. F55 and its linked BT children remain open.

A final review found that skipping register_next as a writer also omitted its
consumed RHS. A synthetic consumer root now traverses that actual RHS without
adding a register write/feedback edge. The undriven-RHS regression fails against
the preceding implementation (105/106) and passes after the repair; driven RHS
and registered self-read remain valid. Rebuilt native suites pass 893/893, with
storage increased to 106. The eight directly affected Scala cases pass again,
now containing 24 actual public graphs (13 positives, 11 exact-cause negatives).
The earlier 63-case selection remains recorded separately; it was not silently
reclassified as rerun after this final graph-root repair.

Final repaired compiler SHA-256 is
`bdd1465e7571e6cbfaea10b0857df4d39930267dc8f9823fc769df51517d8be7`.
The `register-rhs-*` logs in the same local evidence directory retain the causal
before/after check, fresh source artifacts, native replay and unchanged-binary
hash. Register priority and execution remain F56-owned; consumed-source
definedness is part of this F55 combinational graph contract.

## Writable Signal selection checkpoint

The approved [writable selection gate](../design-gates/NodalWritableSignals-DG-v0.1.md)
adds Signal.writable and a private SignalSelection with value plus canonical shape
views. It supplies actual selected assignment operands while preserving readonly
Expr projections. Source, bridge and native checks retain the original wire/output
root, direction, lexical/module ownership, exact complete ABI and all-legal bounds.
The driver graph independently proves coverage, overlap and dependencies.

Fresh integrated checks pass 84 selected Scala cases and 905 native checks.
The 19 actual public source→bridge→native graphs include 14 positives and five
exact-cause driver negatives; positives normalize idempotently. Native storage
passes all 118 cases, including the 106 published driver predecessors.
The maintained callback harness passes its typed positives, 20 source-located
callback negatives, two private/computed capability negatives and traversal/runtime
predecessors. Isolated stage results remain separately labeled historical evidence. Pure collection callbacks still
forbid writes; register projections and computed/alias roots gain no write
capability. Bounded loop induction and cross-iteration proof remain required work.

The actual public surface supports, for example,
`lanes.writable.at(0) := input.at(0)` and the corresponding lane 1 assignment,
followed by `output := lanes`. Native acceptance requires actual disjoint physical
regions and complete output-read coverage. Rows, planes, slices, noncontiguous
columns, composed views, symbolic bounds/widths and whole Struct lanes are tested.
Forged root/direction/shape records, overlap/cycles/uncovered reads, wrong ABI and
projected register updates reject at their independent owners. Ordinary root
register_next and its consumed-RHS coverage remain passing.

Formatting and whitespace checks pass. Syntactic lint retains exactly ten
unchanged ConstructionSession/ElaborationConstructionKernel baseline diagnostics,
with no new diagnostic; full lint is not reported passing. Fresh Struct replay
retains shared predicates, materialization inventory, idempotence and stale-edge
rejection. The frozen native compiler SHA-256 is
`fc0e88ac549f23feb24444eec58c0a2506a3f6f282e0c9ed5cc8b3849f839a8b`.
Exact source hashes, commands and artifacts are retained under
`.work/increment55/writable-signal-stage/live`.

The architecture adds a private capability over existing shape/assignment owners,
not another coordinate engine or writable Expr type. Source ancestry is bounded
and proportional to authored projection depth per assignment; downstream physical
proof budgets remain unchanged. No target execution or whole-pipeline scaling
claim is made. Widening reduction proof/integration and bounded hardware iteration
remain open, as do the F55 parent and all linked BT acceptance decisions.

## Widening reduction checkpoint

The approved [reduction gate](../design-gates/NodalDigitalReductions-DG-v0.1.md)
preserves ordered reduce and adds adjacent-pair reduceBalancedTree with odd carry,
singleton bypass and one actual whole-value callback DAG. Private checked formal
contexts preserve raw width identity while validating every reachable intermediate
body/type obligation. Exact complete ABI, inner shapes, enum identity and source
Bool semantics remain binding. Finite transfer and proof-resource limits are
explicit; no parameter default or first/final-only sampling establishes validity.

The native integration builds successfully and passes 1,155 focused cases across
12 reduction/context/collection/type/value/enum/shape/storage/Interface/
materialization/bounds/nonzero binaries, plus ParameterModel. The initial
coordinated Scala replay passes 143 groups covering reductions, arithmetic,
writable storage, driver graphs, collections, Struct/materialization, interfaces
and selected shape predecessors.

An additional enum replay found that requiring semantic identity equality against
a literal ABI width rejected a lawful singleton-domain parameter. Source and
bridge now reuse checked singleton-domain width proof at every actual reduction
context. The final repaired-tree replay passes all 56 reduction/enum/writable
source, bridge and native groups, including 35 public witnesses with normalization
round trips, seven exact forged-IR outcomes and a new N=2-valid/N=3-forged-later
context enum rejection for both topologies. Counts overlap the initial replay;
they are not added as distinct tests. The separate expanded isolated predecessor
selection remains 54/55, with the existing CDC receipt failure documented above;
that fixture is unchanged and is not counted as passed.

A focused native review also found a causal first-valid/later-changing inner Vec
shape defect in the stage. The same forged artifact passed its frozen prior
verifier and rejects after universal paired-template/yield shape checks; the
first-context N=2 control remains valid. Both controls pass in the rebuilt live
reduction suite. Exact source hashes, commands and failed-attempt logs remain under
`.work/increment55/reduction-live`. Frozen native compiler SHA-256 is
`79244afa9124682549e7d7484efcb21324476f41d4c3a3f19c3f7fec8f8d2b55`.

The public callback harness passes typed positives, 20 source-located negatives,
bounded traversal, two capability negatives, the actual runtime path and
uninstrumented rejection. Formatting and whitespace checks pass. Syntactic lint
retains exactly three unchanged baseline return/throw diagnostics in the affected
files, with no new finding; full lint is not reported passing.

The actual public `PublicWideningSums` fixture includes:

```scala
val width = param(4.integer, range = 2 to 8)
val count = param(5.integer, range = 1 to 1000000)
val input = in(Vec(UInt(width), count))
val left = input.reduce((a, b) => a + b)
val balanced = input.reduceBalancedTree((a, b) => a + b)
val product = input.reduceBalancedTree((a, b) => a * b)
val fixed = input.reduceBalancedTree((a, b) => (a + b).wrap(8))
```

Whole-record witnesses preserve nominal fields, nested vectors, enum/Bool values
and one shared selection predicate. Counts 5 and 1,000,000 both retain one callback,
one region, two digital snapshot values and eight static nodes; emitted graphs
retain one collection, one digital expression and five constant expressions.
Singletons invoke no callback. Proof-only query arithmetic no longer enters
source/name metadata; the same symbolic witness text decreases from 2,025,424 to
116,926 bytes and whole-record text from 924,052 to 167,525 bytes. These are bounded
representation measurements, not timing or whole-pipeline linearity claims.

Architecture review: shared ParameterModel/StructuralParameterBinding arithmetic,
actual typed capture/formal identities and canonical complete-type comparators
remain authoritative. Universal checks reuse existing value/enum/shape/collection
rules; source callback replay and per-lane expansion are absent. Read-only exact
prefix/tree/body queries invalidate with mutation. Resource bounds and downstream
F58/F65 lowering ownership remain explicit in the gate. Bounded hardware iteration
and the F55/BT acceptance decisions remain open.

The loop checkpoint below completes the previously recorded shared output-
observability repair. DigitalAssignmentModel now adds final whole-output coverage
for roots participating in executable ordinary or loop writes, including empty
loops, while preserving untouched declaration-only fixtures. Fully driven output
positives and exact missing-lane negatives pass; internal SSA readers are no
longer required to establish this observable obligation.


## Bounded hardware iteration checkpoint

The approved [bounded-loop gate](../design-gates/NodalBoundedDigitalLoops-DG-v0.1.md)
retains one captured body and actual ordered effects. Unified `hdlRange` shares
its checked structural domain with a digital partition; explicit `loop` retains
procedural iteration. The canonical native loop verifier, physical region-image
proof and assignment graph jointly check bounds, lexical captures, write overlap,
read coverage and causal recurrence. Independent type/value checks still validate
empty bodies. Target-neutral plans choose checked unrolling only within eight
exact invariant iterations and bounded expansion resources; otherwise they retain
a procedural plan. These are planning records, not executed digital HDL.

The maintained public `PublicSymbolicLoop` witness uses:

```scala
val count = param(2.integer, range = 1 to 1000000)
val input = in(Vec(SInt(8), count))
val output = out(Vec(SInt(8), count))
loop(LoopBound.Symbolic(count, 1000000)): i =>
  output.writable.at(i.value) := input.at(i.value)
```

Its verified IR retains one `nodal.hardware_loop`, actual bound/capture operands,
a real induction argument and one typed assignment. The maximum is a checked
whole-domain promise, not a clamp or a request to expand a million lanes.

A required nested-reduction repair carries static loop-local width expressions
through the existing checked ParameterModel environment. The induction argument
never becomes a positive width formal. Actual mapped operands and independently
recomputed materialization reports retain their lexical type obligations. Fixed
left/tree and symbolic-width nested reductions pass, while forged width DAGs
reject. No source formula rewrite or parameter-default specialization is used.

The coherent live native replay passes 1,424 counted cases across 16 binaries,
plus ParameterModel. The maintained bridge suite passes all 11 groups with both
native tools configured, including 18 public witnesses, repeated byte-identical
planning, three causal loop negatives and whole-model target rejection. Source
loop/domain/arithmetic groups pass. The maintained callback harnesses pass 21
collection and 31 iteration source negatives, typed/runtime/budget controls,
separate-JAR constructor/factory reuse and missing/stale/dependency-summary
rejections. The producer and consumer independently validate the private effect
summary; allocation metadata alone grants no callback capability. The exact
compiler ABI assumes coherent plugin-produced libraries, not arbitrary forged
classfiles. Older allocation-only libraries must be rebuilt for attested ranges;
their ordinary outside-range construction remains supported.

The selected F43 waveform ownership replay exposed an inherited assertion that
mistook canonical path-sorted metadata for authored body order. An exact published
source/API/plugin/bridge control reproduces it. The repaired assertion checks
canonical metadata order, exact authored binding correspondence, ordered analog
body operations, region/induction ownership and forged-owner rejection separately.
Historical execution is not relabeled passing. A separate real compatibility
regression consumed two authored expression identities for an unused range-loop
candidate. Private partition scaffolding now uses a checked disjoint identity
allocation in the same construction registry and derives lexical paths from the
actual generated owner. It creates no phantom authored origin. The exact same-
source-root waveform MLIR again matches the published-API control byte for byte
(22,431 bytes), including state IDs. The corresponding names/origins/generated
names/source-map/region/waveform inventory projection also matches exactly
(10,587 bytes). Digital/mixed/explicit sibling scope uniqueness and determinism
remain checked. Raw controls, commands and focused results are
retained under `.work/increment55/loop-live` and the related isolated checkpoint
directories.

The expanded affected predecessor selection initially passes 69/70 groups: the
remaining forged generated-storage case rejects correctly with the new precise
scope diagnostic, while its test still expected the former direct-module-only
wording. The assertion is updated to that exact semantic rejection without
changing the forged input; the final replay is recorded with the checkpoint.
The explicitly configured writable-admission replay additionally exposed two old
partial-output positives invalidated by the required output-observability repair.
Their original source bodies remain as exact missing-coverage negatives. New
symbolic selector/row positives use internal storage with complete coverage of
every observed region and fully driven output values; neither defaults nor
implicit zero-fill supply the formerly missing output lanes. The final repaired-tree
replay passes all 25 loop/source/bridge/writable groups, all 70 selected F55
predecessor groups, 25 domain/range predecessors, 26 migrated boundary groups and
the focused native-backed waveform group. These selections overlap and are not
summed as distinct cases. Both callback harnesses pass again on the final API.
The frozen native compiler SHA-256 is
`b0b4afac4f454527a3fb2a06d368ce0bbec17c44b2dd926805617a9f113f9c35`.

Architecture review: the shared ParameterModel, ShapeModel and assignment graph
own domain, physical-image and dependency semantics. Loop planners consume those
verified representations; source callbacks are never replayed per lane. Captures,
proof visits, recursion, body size and expansion have explicit checked budgets.
Small and million-count source captures retain one loop, two arguments, six
expressions, three selectors, one iteration index and three assignments. Forty
captures measured 9.94 and 12.63 seconds respectively (247,308 KiB combined peak);
these are representation observations, not a speedup or whole-pipeline complexity
claim. Noncontiguous or multi-axis images beyond the finite proof profile reject.
The existing unrelated CDC receipt limitation above remains unchanged. Focused
syntactic lint over all changed Scala paths retains exactly 28 identical baseline
findings and introduces none; full lint is not claimed passing. Formatting,
Markdown, generated capability registry and whitespace checks pass.

At this historical bounded-loop checkpoint, F55 remained open for the mandatory
structural predicate/branch amendment and final reconciliation. The following
checkpoint implements that required feature. Full Area/hierarchy and downstream
digital execution remain with their owning increments.

## Structural predicate amendment checkpoint

The published bounded-iteration checkpoint is
`538fbc9cce950c62eaf56234f8db450860539d0a`. The original amendment
[F-055.B.1.1](../roadmap/areas-symbolic-conditionals-v0.1-plan.md#existing-owners-and-new-descendants)
is required before F55 closure and is now implemented. The approved
[NodalStructuralIf-DG v0.1](../design-gates/NodalStructuralIf-DG-v0.1.md)
records the minimum F58 gate dependency, ordinary Scala `if` syntax, explicit
read-only result typing and compatibility limits. The production paths now retain
actual predicates, isolated alternatives, captures and both yield edges. Final
focused results are recorded below; verified branch integration is recorded below.

Implementation is divided at existing semantic owners: the frontend captures
closed parameter/index predicates without invoking host equality or evaluating
parameter defaults; the existing construction session owns both branch captures
and lexical lifetime; isolated native regions independently verify predicates,
captures and complete result ABI. Shared ParameterModel, DigitalTypeModel,
ShapeModel and the assignment graph continue to own static facts, type identity,
physical images and drivers. A narrow neutral region-scope utility may factor the
existing collection/loop checks; no second type, shape or mutable capture registry
is introduced.

A structural index must come from actual Generate ownership. A procedural loop
argument cannot become static by carrying a generated-region label. Opposite
branches exclude each other only for the same generated occurrence: writes to the
same physical lane from different occurrences can still conflict. The finite
initial proof profile therefore requires checked per-occurrence images and
same-occurrence overlap proofs, retaining guard context through dependency and
coverage analysis. Both arms remain structurally and type-correct even when an
invariant predicate proves one arm inactive. Unsupported guard/image proofs must
reject rather than erase possible writers.

Focused acceptance must cover parameter overrides, actual generated indices,
Boolean composition, nested/else-if results, exact aggregate/enum ABI, host
Boolean compatibility, branch-local escape, read-only joins, complementary write
coverage, missing coverage and real or infeasible cycles. Inferred writable joins
must produce the gate's precise Expr-ascription diagnostic. Cross-lane writes
under opposite arms require a causal duplicate-driver negative. Resource limits
apply before expansion; no exhaustive parameter enumeration or whole-pipeline
linearity is claimed. The implemented positive and negative cases run through the maintained source,
bridge and independent native suites. Final affected replay is recorded below.

The final original-checklist reconciliation will retain compiler-boundary public
Scala/IR demonstrations and explicit downstream cases for 65/66 (branch and lane
mapping under parameter overrides), 67 (driver/coverage and equivalence of legal
unrolled versus preserved plans), and 72 (whole mixed-design capability boundaries).
Those future executions are not current passing results. Full Area/hierarchy,
generated Area results and behavioral `when` remain their existing owners; this
checkpoint does not complete their parents or F-058.A.1.

### Concrete downstream handoff cases

The maintained source fixtures are inputs for later consumers, rather than
claims that those consumers have run:

- F65/66: carry the static, symbolic, empty, strided and seeded dependency cases
  from `Increment55DigitalLoopBridgeTests`, including its configured native
  planning test, into strict target parse and independent lane-value simulation.
  Compare preserved and legally unrolled plans using the same source, bounds and
  expected values; retain the whole-model mixed-target rejection control.
- F65/66: carry the public widening witnesses in
  `Increment55WideningReductionNativeTests` into signed/unsigned arithmetic and
  whole-record simulations. Preserve left versus adjacent-pair balanced grouping,
  singleton bypass, odd carry and parameter-dependent complete result widths.
  The forged final/intermediate-width controls must remain rejected before target
  execution.
- F67: use `Increment55DigitalDriverTests` and the bounded-loop guard/coverage
  witnesses for independent missing-driver, duplicate-driver and combinational
  cycle checks. Use `Increment55DigitalMaterializationTests` aliases, keep intents
  and actual-edge reports to check preservation and equivalence without inferring
  source identity from emitted spelling or reassociating arithmetic.
- F72: reuse the whole-model target-boundary controls from
  `Increment55DigitalLoopBridgeTests` when a real AMS digital consumer exists.
  Analog-only compilation of a selected leaf is not evidence of faithful mixed
  model lowering. Carry `Increment55StructuralIfBridgeTests` parameter/default,
  generated-lane, procedural-loop, missing-alternative, duplicate-driver and
  feasible/infeasible-cycle cases through that same whole-model boundary.

These test files reside in `core/scala/testkit/test/src/nodal/internal/testkit/`.
The handoff preserves source identities, exact parameters, plan reasons and
independent expected behavior. It does not add a later full-tool campaign as an
earlier acceptance prerequisite.

## Final structural checkpoint acceptance

The integrated tree passes 107 maintained Scala tests across structural source,
bridge, materialization and directly affected loop, collection, widening-reduction,
writable-selection and driver suites. Both native tools are explicitly configured;
the structural bridge executes 16 accepted public models and five causal rejected
models, with no optional-native skips. Both alternatives, actual Generate domains,
procedural selector captures, complete result types and source identities remain
in verified output. The final native replay passes 1,527 counted cases across 18
suites plus ParameterModel (19 executables total). This includes 69 structural
cases and 34 guarded-assignment cases. Selections overlap earlier checkpoints and
are not added to historical totals.

The maintained frontend harness also passes against the actual live API/plugin:
eight successful compilation commands, 19 expected compiler rejections, separate
producer/consumer proof substitutions, ordinary host runtime and bytecode checks.
The direct predicate-write, erased equality-evidence-write and separate-JAR
write-helper negatives close a discovered effect-profile hole: predicate/evidence
validation is pure, while supported arm bodies retain continuous-write capability.
Generic enum constants in native arms now use the canonical enum membership owner;
legal sparse codes pass and illegal width-fitting codes reject.

Actual source integration additionally checks parameter-only structural branches
inside procedural loops, arm-local affine selectors, ordered conditional events,
value-producing alternatives with continuous writes and pure callback restrictions.
Procedural index captures never become structural predicate/type facts. A retained
conditional makes the existing planner conservatively preserve the procedural
loop rather than apply an unroller that cannot retain its complete region contract.
The final default materialization pipeline records statement-only choices even
when its value-decision list is empty, through the existing quality opt-in.

Affected replay found an inherited analog range-query admission gap. The exact
published-baseline plugin reproduces four errors on unchanged F44 source. The
shared range profile now admits only the already-supported temperature, nominal
temperature and time queries; pure digital alternatives still reject them. The
original F44 example strictly compiles and runs. Historical failed execution is
not relabeled passing. Shared static capture transport also supersedes the old
literal-only collection-selector rejection: the unchanged parameter-selector
source now verifies through the native boundary, and an omitted real capture is
an explicit negative. Its query uses the actual collection context, never a
parameter default or representative reducer width.

Architecture review confirms one capture registry, one neutral lexical-region
owner, shared static/type/image proofs and one guard-aware assignment graph.
Guard contexts retain actual branch identities and generated-occurrence relations;
complementary arms cannot erase cross-lane conflicts or feasible cycles. All
alternatives remain type/effect checked, including inactive/empty supports.
Materialization records actual predicate/capture/yield edges and recomputes attached
reports. No additional arithmetic reassociation, name-based identity inference or
speculative optimizer is needed. The guarded graph has checked limits of 4,096
contexts, 128 levels and 262,144 guard-work units, alongside existing graph and
shape budgets. This is a finite proof profile, not arbitrary Boolean theorem
solving or a whole-pipeline linearity claim.

Two versus 1,000,000 generated lanes retain exactly two predicates, two conditions,
six digital values, two assignments, four selectors and six Generate arguments.
The isolated observation used 174,900 KiB peak JVM RSS and about 5.23 seconds for
the combined captures; it establishes compact representation, not a speedup.
All 51 changed Scala files pass formatting. Focused syntactic lint introduces no
new finding; the runtime comparison retains nine inherited findings versus ten
on the baseline, and the parent-updated predecessor test is clean. Full lint is
not claimed passing. Markdown, generated registry and whitespace checks pass.
The earlier unrelated CDC receipt limitation remains explicitly unchanged.

Exact final commands, binary hashes, logs and preserved public artifacts are under
`.work/increment55/structural-if-live/`; failed setup and intermediate runs remain
separate. The final native compiler SHA-256 is
`4cd1e5e3d5cb7a41aba6ec2339bc8ca8c5f0aa533642b5c87f60224515cd8331`.
No CI, broad qualification, target execution or full F58/BT cross-owner closure is
claimed. Original F55 A–F and amendment B.1.1 are complete at the implemented
compiler boundary; G and the parent are complete after verified integration.

### Actual public source and IR demonstration

The current increment does not affect generated Verilog-*.
F55 supplies verified digital IR and deterministic lowering/materialization plans;
F65–67 own target HDL emission, simulation and equivalence. Whole-model unsupported
target controls reject rather than emitting a partial model.

This is the maintained `PublicStructuralValue` source in
`core/scala/testkit/test/src/nodal_external/Increment55PublicStructuralIf.scala`:

```scala
final class PublicStructuralValue(val defaultMode: Int) extends Module, StructuralIfClock:
  val MODE = param(defaultMode.integer)
  val first = in(Bits(8))
  val second = in(Bits(8))
  val output = out(Bits(8))
  val selected: Expr[Bits] = if MODE == 0 then ~first else second
  output := selected
```

`StructuralIfClock` is the fixture's explicit external domain. The bridge lowers
this source with both default 0 and default 1 while retaining the symbolic predicate
and both read-only alternatives. The configured maintained test command is:

```sh
NODAL_NODALC=/absolute/path/to/nodalc NODAL_TRANSLATE=/absolute/path/to/nodal-translate \
  ./mill --jobs 1 core.scala.testkit.test.testOnly \
  nodal.internal.testkit.Increment55StructuralIfBridgeTests
```

The native invocation uses
`--pass-pipeline=builtin.module(nodal-plan-digital-loops,nodal-plan-digital-materialization)`.
The test writes `value-default0.source.mlir` and `value-default0.planned.mlir` under
its working directory's `.work/increment55-structural-if-bridge/`. The final local
replay preserves those files under
`.work/increment55/structural-if-live/final-witnesses/`. The following is a verbatim
excerpt of the actual generated source IR, including both yielded edges:

```mlir
    %digital_if_1 = "nodal.structural_if"(%static_predicate_0, %digital_value_2, %digital_value_3) <{contract_version = "structural-if-v1", region_id = "PublicStructuralValue.selected_56ecdd40", operandSegmentSizes = array<i32: 1, 1, 1>, metadata = {bridge_schema = "nodal.scala-to-mlir", bridge_version = 1 : i64, digital_source = {aliases = [], name = "selected_56ecdd40", provenance = "scala-declaration"}, semantic_path = "PublicStructuralValue.selected_56ecdd40", source_end_column = 66 : i64, source_end_line = 20 : i64}}> ({
      ^bb0(%structural_arm_4_arg0: !nodal.bits<8>):
      %digital_value_5 = "nodal.digital_expr"(%structural_arm_4_arg0) <{operator_name = "digital_not", metadata = {bridge_schema = "nodal.scala-to-mlir", bridge_version = 1 : i64, digital_source = {aliases = [], name = "selected_5d6d4b57", provenance = "scala-declaration"}, semantic_path = "PublicStructuralValue.selected_5d6d4b57", source_end_column = 66 : i64, source_end_line = 20 : i64}}> : (!nodal.bits<8>) -> !nodal.bits<8> loc("core/scala/testkit/test/src/nodal_external/Increment55PublicStructuralIf.scala":20:3)
      "nodal.structural_yield"(%digital_value_5) <{metadata = {bridge_schema = "nodal.scala-to-mlir", bridge_version = 1 : i64, semantic_path = "PublicStructuralValue.selected_56ecdd40.then.yield", source_end_column = 66 : i64, source_end_line = 20 : i64}}> : (!nodal.bits<8>) -> () loc("core/scala/testkit/test/src/nodal_external/Increment55PublicStructuralIf.scala":20:3)
    }, {
      ^bb0(%structural_arm_6_arg0: !nodal.bits<8>):
      "nodal.structural_yield"(%structural_arm_6_arg0) <{metadata = {bridge_schema = "nodal.scala-to-mlir", bridge_version = 1 : i64, semantic_path = "PublicStructuralValue.selected_56ecdd40.else.yield", source_end_column = 66 : i64, source_end_line = 20 : i64}}> : (!nodal.bits<8>) -> () loc("core/scala/testkit/test/src/nodal_external/Increment55PublicStructuralIf.scala":20:3)
    }) : (!nodal.static_predicate, !nodal.bits<8>, !nodal.bits<8>) -> !nodal.bits<8> loc("core/scala/testkit/test/src/nodal_external/Increment55PublicStructuralIf.scala":20:3)
```

## Verified integration

PR151 squash `eee3d7ed9c9fb39e3bf79ff7c554cc0a90da632e` integrates exact tested tree
`8f0fbbb76698c6961edb6b09e518a93a9a1ca779` onto the verified F54 closure base.
The source branch head was `00e43eb98b87dd5219d7268a30f093dd130f90eb`.
The squash retains `[skip ci]`; no CI was launched. F55 and G are complete.
The actual source/IR demonstration and finite limitations above remain the
completion record; later F56/F58/BT/target consumers are not claimed complete.
