# Increment 55 readiness and implementation

Status: in progress; every F-055 child and parent remains open. Base
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

## Shared architecture and first implementation

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

## Pending decisions and focused validation

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
