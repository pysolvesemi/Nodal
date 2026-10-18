# Foundation 43: compound generated-bound transport

**Status:** Implementation checkpoint; whole F-043 acceptance remains open.

The owning checklist and applicability decisions remain in the
[readiness record](increment43-readiness.md), the main Foundation roadmap and
the lightweight iteration amendment. This checkpoint completes transport of
captured compound bounds, not analog shapes, generated storage or target arrays.

## Shared implementation

The bridge reuses the existing static parameter-expression emitter for instance
overrides and generated bounds. Generated expressions use one module-local SSA
cache keyed by canonical expression identity. Shared and nested ranges retain
their source DAG without host unrolling, helper parameters or default
substitution. Missing, cyclic and over-depth expression snapshots reject.

Literal/direct-parameter ranges preserve the existing zero-operand bound
attributes. If any bound is compound, all three ordered bounds (lower,
upper-exclusive, step) become signless i64 SSA operands of `nodal.generate`.
The operation and parameter-model verifier share the exclusive-form check:
zero operands requires all three attributes; three operands prohibits those
attributes; every other arity rejects. Existing identities and count metadata
remain mandatory for the new form. Empty generated bodies retain an explicit
empty block, so a zero-operation body is not confused with a zero-block region.

The existing native `IntegerBoundsAnalysis` independently computes the finite
envelope and count. Only the generated SSA profile interprets public Integer
signless i64 intermediates as signed mathematical endpoints, matching existing
loop-literal spelling. General parameter default folding, exported interval
analysis and explicit unsigned/narrow storage rules remain unchanged. Checked
wide arithmetic, possible-zero divisors, signed overflow, direction, positive
step and the exact maximum-count contract remain enforced.

A separate bounded dependency walk uses the same native DAG and validates
module-static ownership, i64 types, supported arithmetic and structural
parameter dependencies. Fixed declarations follow their actual parameter-value
expression transitively; neither a default nor a declared interval hides an
ordinary dependency. Both walkers memoize source identities within the module
and reject excessive depth. This preserves the independent native trust boundary
without introducing another expression representation.

## Regression scope and reproduction

The 49 original count fixtures and their strict validator are preserved.
Their runner now accepts an explicit matrix/validator so the new SSA suite
shares process handling, ten-second per-case timeout, exact diagnostic checking,
two executions of every positive, deterministic output and hash recording.
The new 34-case matrix covers all six native integer operations, signed
intermediates, identical versus independent endpoints, nested/shared DAGs,
fixed transitive dependencies, depth, overflow, count/maxima, dynamic inputs,
ordinary parameters, possible-zero divisors and malformed/mixed forms.

The SSA output validator compares source-identified DAG nodes and ordered edges,
parameter references, literal values and generated bound identities independently
of temporary SSA names. Seven Python test methods exercise its preservation and
rejection controls, including renamed temporaries, changed operator/edges/values,
missing operands/definitions, wrong diagnostics and process failures.

The public compound suite retains the first ten construction regression groups.
Its interim unsupported-transport test becomes a positive deterministic transport
test while retaining the direct-parameter attribute witness. Additional tests
cover shared emission, missing/cyclic snapshot graphs, structural dependencies
and configured native success/rejection. Native-dependent branches only count
as executed when `NODAL_NODALC` is set to the freshly built compiler.

After installing the pinned toolchain and building:

```sh
ctest --test-dir out/native/release \
  -R 'nodal.native.(parameter-integer-bounds|iteration-envelope-integration|generate-count-integration|generate-ssa-integration)' \
  --output-on-failure
NODAL_NODALC="$PWD/out/native/release/bin/nodalc" \
  ./mill core.scala.testkit.test.testOnly \
  nodal.internal.testkit.Increment43CompoundBoundTests
python3 -m unittest discover -s tests/compiler -p test_increment43_generate_ssa.py
```

Local validation on October 5 passed the 141-test native suite, the 34-case SSA
matrix within it, 451 compiler Python tests and all 15 public compound groups
with the rebuilt native compiler configured. The predecessor Integer-expression
and count suites also passed. The compound run found and drove the explicit
empty-block repair; the repaired run passes signed, empty and shared-DAG cases.
Pinned Scala and native formatting passed. Local ClangTidy encountered a host
process/library error, so native lint remains a remote qualification obligation.
Actual source/tree identities and remote results belong in the PR checkpoint.
Affected remote qualification is Core CI, configured Bridge20 and Parameters29.
Local checks are repair evidence, not a substitute for those exact-head runs.
No old-head CI credit transfers to this source. Full CI and required independent
review wait for coherent whole-increment implementation.

This stage does not implement generated Verilog-A arrays, shape/index/slice
lowering, per-generated-instance storage or the final source/output witnesses.
The remaining main and descendant requirements, tool-qualification handoff,
review, integration, accepted-evidence closure and completion demonstration
remain required and unchecked. No independent OpenVAF or numerical result is
claimed by internal native acceptance.

## Captured ownership boundary repair

The next native ownership audit reproduced three invalid inputs accepted by the
qualified transport checkpoint: sibling regions with the same region identity,
distinct regions sharing an induction identity, and a nested region outside its
immediate parent's semantic path. The bridge already rejected those snapshots;
native parsing must not rely on that upstream check.

The existing module verifier now inventories captured region and induction keys
once per module. Nested module definitions keep independent inventories. The
generate verifier checks the immediate parent, canonical identity spelling and
nested path ownership. A captured region cannot be placed inside an unrelated
procedural loop or an uncaptured legacy region, and a captured child cannot lose
its identity contract. Legacy uncaptured generation remains supported with its
existing spelling and placement. The bridge also rejects trailing identity
whitespace rather than emitting a document that fails native validation.

This uses the existing IR ownership and verifiers; no new registry, path-derived
staging heuristic or duplicate hierarchy representation is introduced. The
inventory is one traversal with two string sets, linear in visited operations
plus identity bytes. A 256-sibling fixture exercises the inventory without
claiming a general performance benchmark or speedup.

The new 31-case native ownership matrix reuses the strict count runner. Ten
positive cases execute twice, retaining region/count, node-ownership and module
inventories; 21 negative cases require normal exit 1 and the exact ownership
diagnostic. Existing count and SSA fixture matrices and validators are unchanged.
Additional checker mutation tests reject lost nodes/modules, altered ownership
fields, crashes, timeouts and wrong diagnostics. A configured public Scala test
first accepts the repeated/nested node source, then forges region, induction and
parent identities in its emitted document and requires native rejection.

This repair strengthens the existing trust boundary before target lowering. It
does not implement arrays, generated lexical storage or target generation, and
does not complete a whole F-043 child. Exact candidate qualification and current
worker coordination remain in the PR checkpoint.

## Generated-effect capture boundary

The ownership repair also exposed a separate capture gap: `hdlRange` rejected
unknown generic operations after the body returned, but analog storage,
continuous-time operators, events and analog regions use dedicated inventories.
Those effects could therefore escape the generic operation-count check without
acquiring generated-instance ownership. Accepting them at this stage would
silently share state or place stateful behavior outside the generated region.

At that checkpoint, before generated-instance storage owned any inventory,
construction rejected them at the public capture boundary with
`NODAL-ITERATION-043-004` and the generated region path. The same fail-closed
boundary covers child modules, non-node declarations, domains, conservative
connections, user functions, transfer/noise/waveform operators, event controls,
and continuous or procedural analog regions. Generated analog nodes were the
only supported body effect, including the existing empty-body case; ordinary
construction outside `hdlRange` was unchanged.

Public Scala tests exercise generated variables, `ddt`, `cross`, continuous
analog blocks and analog procedures and require the exact diagnostic family and
generated semantic path. This was a restriction checkpoint, not implementation
of generated lexical state or legal Verilog-A generation. The bounded storage
checkpoint below replaces only the procedure/storage part with explicit
ownership and verification; target witnesses and the remaining restricted
effects stay open.

## Generated lexical-storage ownership checkpoint

The first storage checkpoint replaces one narrow part of that temporary effect
rejection. One existing `hdlRange` may now contain one existing
`analogProcedure` whose body is straight-line lexical variable declaration,
read and assignment. Construction records the immediate generated-region and
induction identities on the canonical procedural snapshot. The bridge requires
that pair to resolve to the same-module generated region, emits the analog
procedure inside that `nodal.generate`, and places the same ownership pair on
the analog wrapper, procedure and variable/read/assignment operations.

Native verification requires the generated analog wrapper to be an immediate
generate child, requires every procedural storage operation to match the
enclosing region and induction, and rejects forged, missing or escaped
ownership. Generated event controls, structured procedural control, continuous
operators, continuous analog regions, child modules, domains, connections and
non-node declarations retain `NODAL-ITERATION-043-004`. Multiple generated
procedures per module remain unsupported. Ordinary component-local analog
procedures remain unchanged.

This checkpoint represents per-generated-occurrence lexical storage in normalized
IR without host unrolling or shared enclosing storage. It does not yet lower the
storage to Verilog-A, enable generated events/operators, add array or slice
syntax, or complete any F-043 parent/descendant checkbox. Target generation,
source/index maps, scale/determinism evidence and independent output witnesses
remain required.

### Procedural semantic-region entry repair

Initial remote qualification exposed that the public `analogProcedure` wrapper
still entered the generic semantic-region rejection before the ownership-aware
procedural recorder could validate the bounded generated-storage profile. The
entry gate now exempts only the `Procedural` region kind. The recorder still
requires generated lexical storage and straight-line control, and the existing
bridge/native ownership checks remain unchanged. Generated equation, initial-
equation, contribution and continuous regions still reject at capture; focused
regression cases retain that fail-closed boundary.

### Generated declaration inventory repair

The public `Variable` constructor also passes through the generic declaration
registry before the procedural recorder sees it. That registry now gives only
an active generated-owned analog procedure provisional access to a variable
declaration. At generated-region completion the declaration remains accepted
only when the same procedure retained that exact object under the current
region and induction identities. A raw variable-kind declaration forged inside
an otherwise valid generated procedure therefore still rejects, as do variables
outside a procedure and every other generated declaration kind.

## Generated structured lexical-storage checkpoint

The next bounded storage checkpoint extends the same owned generated procedure
to the already-canonical Increment 34 conditional, case and runtime-bounded loop
tree. Construction still captures one procedure under one immediate generated
region and induction; it no longer rejects that procedure merely because its
retained assignments are structured rather than straight-line. It does not
unroll a branch or loop and does not create a second control-flow owner.

The bridge accepts structured control only after the existing generated-region,
variable-declaration and induction checks pass. Every emitted variable, read and
assignment carries the same generated ownership pair. At this checkpoint a
bridge-local recursive check continued to reject an event-controlled statement
even if a snapshot was forged after construction. Native verification admitted
only the established `analog_if`, `analog_case`, runtime/static analog-loop,
lexical-scope and break/continue operations under the generated procedure. It independently
checked the ownership attributes on each storage operation; event and continuous
operations remained outside the allowlist.

Public regression at this checkpoint covered one deterministic procedure
containing conditional, case and runtime-loop updates, forged event injection at
the bridge boundary, and forged assignment ownership at the configured native
boundary. The event checkpoint below replaces that temporary event prohibition;
the earlier straight-line case and every other capture-boundary negative remain.
This is still normalized-IR ownership evidence only: generated operators, legal
Verilog-A generation, arrays/slices, source/index maps and all F-043 acceptance
boxes remain open.

## Generated event-ownership checkpoint

The next bounded procedural checkpoint admits an analog event occurrence only
inside the already-owned generated analog procedure. Construction still rejects
an event created directly in a generated region, but permits the public event
and controlled-statement surfaces while the exact generated region and induction
own the active procedure. Reused event handles remain occurrences: each emitted
event expression and `nodal.analog_on` carries that procedure's ownership pair.

The bridge retains the existing event validation and source inventories, emits
the ownership pair on primitive/composed event expressions and their control,
and continues to require the procedure's same-module region, induction and
variable-declaration contract. Native event verification independently requires
both attributes, matches them to the immediate `nodal.generate` and owning
`nodal.analog_procedure`, and rejects detached, partial or forged ownership with
`NODAL-ITERATION-043-004`. Existing nesting, runtime-monitor placement, read,
history and event-body restrictions remain unchanged.

This is normalized-IR ownership evidence. It does not enable generated
continuous-time operators, contributions or nested event controls, and it does
not lower generated procedures, storage or events to Verilog-A. General slices,
legal target generation/source maps and all F-043 acceptance boxes remain open.

### Generated event operation-schema repair

Configured bridge qualification exposed that the textual bridge emitted the
generated ownership pair, but the native operation schema had not declared the
pair on event expressions, event composition or `nodal.analog_on`. Those fields
were consequently absent after parsing, and the independent native verifier
correctly rejected the generated event. The operation definitions now retain
the same optional pair already checked by the verifier. Focused Scala coverage
requires the pair on each operation's own authored line and again after native
parse/print normalization, rather than accepting a later operation's attributes
as evidence for an earlier event expression. Ordinary events continue to omit
the pair, and partial, detached or forged ownership remains rejected.

## Generated continuous-operator ownership checkpoint

The next bounded continuous checkpoint admits `ddt` and `idt` only inside an
explicit legacy `analog` region owned by the active `hdlRange`. Construction
records the immediate generated region and induction on both the analog region
and each canonical continuous-operator contract. The generated-region
reconciliation requires at least one such operator, permits no contribution,
and still rejects a direct operator or an empty continuous region.

The bridge requires the paired identities to resolve to the same-module
generated region, emits the analog region beneath that exact `nodal.generate`,
and preserves the pair on `nodal.analog`, `nodal.analog_ddt` and
`nodal.analog_idt`. Native verification independently matches every pair to the
immediate generate and analog owners. The generated analog allowlist remains
limited to literals, parameter references, basic numeric composition and the
two differential/integral operators; waveform, transfer, noise, equation,
contribution and target-lowering effects remain rejected.

This checkpoint proves normalized-IR occurrence ownership only. It does not
lower generated operators or storage to Verilog-A, add arrays or slices, or
complete any F-043 parent or descendant acceptance box.

## Generated waveform-operator ownership checkpoint

The next bounded operator checkpoint extends the same explicit generated
continuous-region ownership to the existing stateful `transition`, `slew` and
`absdelay` surfaces. Construction retains the canonical generated region and
finalized induction on each waveform contract. Bridge validation requires the
pair to match the immediately owning analog and generated regions, and native
verification repeats that proof after parse/print normalization.

The allowlist remains deliberately narrow. `abstime`, `boundStep`, equations,
contributions, transfer and noise effects are still rejected in generated
regions, as are detached, partial or forged ownership pairs. This checkpoint
does not lower generated waveform state or arrays to Verilog-A, implement
slices, or complete any F-043 parent or descendant acceptance box.

## Generated transfer/noise ownership checkpoints

Later bounded checkpoints extend the same exact generated continuous-region
ownership to the existing `laplaceNd`/`ziNd` transfer operators and
`whiteNoise`/`flickerNoise`/`tableNoise` sources. Construction, bridge and
native verification retain the immediate generated region and finalized
induction on every operation. Transfer state identities and noise-source
identities remain unique across nested generated analog regions. Direct,
partial, detached and forged forms continue to fail closed.

These checkpoints do not change the public operator surfaces or lower generated
state/noise to a target. Equations, contributions, arrays, general slices,
legal Verilog-A generation and source/index maps remain open at this point.

## Generated additive-contribution ownership checkpoint

The next bounded checkpoint admits the existing potential and flow `<+`
surfaces only inside an explicit legacy `analog` region owned by the active
`hdlRange`. It does not admit the separate `contributions` semantic region.
Construction resolves the canonical generated region and induction while
materializing both the analog-region snapshot and each contribution snapshot.

The bridge requires every generated contribution to carry the exact same pair
as its analog region and emits that pair on `nodal.contribute`. Native schemas
retain it, and the contribution verifier independently matches it to the
immediate `nodal.generate` and owning `nodal.analog`. Partial, detached or
forged ownership remains `NODAL-ITERATION-043-004`; direct ordinary
contributions continue to omit generated ownership.

This is normalized-IR ownership evidence only. Generated equations, target
arrays/generation, general slices, source/index maps and every F-043 acceptance
box remain open.

## Fixed generated-contribution Verilog-A checkpoint

The first target checkpoint lowers only the already-verified fixed literal
`hdlRange` profile whose generated analog body contains additive potential or
flow contributions and their supporting scalar expressions. The backend keeps
the canonical generated region and induction identities, requires the exact
ownership pair on the nested analog region and every contribution, and emits
one module-scope `genvar` plus one deterministic analog `for` loop. It does not
enumerate iterations or duplicate contribution bodies.

The target reparse gate recognizes only the emitted forward loop form, requires
the declared induction variable, signed literal bounds, a positive step and a
portable block label, and rejects nested or incomplete generated blocks. A
configured public witness crosses Scala construction, normalized MLIR and the
generated Verilog-A text; the native backend unit also exercises target
verification and reparse before publication.

This is a bounded source-generation witness, not final target qualification.
Symbolic bounds, generated storage, events, waveform/transfer/noise operators,
analog arrays, general slices, l-value selection and source/index maps remain
unsupported by this backend profile. Independent OpenVAF compilation and the
F-048/F-049/F-052 compile and numerical handoff remain required, and no F-043
parent or descendant checkbox is completed by this checkpoint.

## Direct symbolic generated-contribution target checkpoint

The next target checkpoint retains the same contribution-only profile while
allowing a generated lower, upper or step bound to name a direct structural
Integer parameter with a finite canonical range. The backend resolves the
parameter in the owning module, independently requires Integer type,
structural classification, finite signed-64 bounds, positive step and forward
direction for every legal setting, and emits the parameter name rather than its
default. The target reparse gate accepts only signed literals or parameters
declared by that module in each loop clause; an undeclared replacement rejects.

The public configured witness uses `hdlRange(0, lanes)` with `lanes` constrained
to `1 to 8` and retains one parameterized Verilog-A loop. It does not enumerate
legal parameter values or specialize the default. Compound expression bounds,
generated storage/events/operators, analog arrays, general slices, l-value
selection and source/index maps remain unsupported by this backend profile.
Independent OpenVAF compilation and the F-048/F-049/F-052 handoff remain
required, and every F-043 parent and descendant checkbox remains open.

## Compound symbolic generated-contribution target checkpoint

The bounded compound successor accepts the existing three-SSA generated-bound
form only for canonical signed-64 literal, structural Integer parameter and
`neg`/`add`/`sub`/`mul`/`div`/`mod` expression DAGs in the same module. The
backend reuses the canonical finite-range analysis and expression renderer,
then independently checks type, ownership, structural dependencies, operator
arity, cycles and depth before emitting the authored expression. Positive step
and forward direction still have to hold over every legal parameter setting;
defaults are never substituted and iterations are never eagerly expanded.

The independent target reparser recognizes only the emitted parenthesized
integer-expression grammar and requires every identifier to name a parameter
declared by the current module. Unknown names, unsupported operators and
unbalanced expressions fail closed. A configured public witness carries
`hdlRange(0, lanes + extra)` through Scala construction, normalized MLIR and a
single Verilog-A loop.

This checkpoint remains contribution-only. Generated storage, events,
waveform/transfer/noise target state, analog arrays, general slices, l-value
selection and source/index maps remain unsupported by this backend profile.
Independent OpenVAF compilation and the F-048/F-049/F-052 handoff remain
required, and every F-043 parent and descendant checkbox remains open.

## Generated region source/index-map checkpoint

Each captured generated region now retains a deterministic source-map entry at
the bridge boundary by reusing the canonical source span of its already-owned
induction expression. The module inventory therefore carries a distinct pair:
the generated-region identity maps the target loop, while the induction
identity maps the authored index. Nested and repeated regions keep separate
semantic paths and emit each mapping exactly once; no new source registry or
target naming engine is introduced.

Bridge validation fails closed when the induction has no canonical source-map
entry or when an explicitly supplied region mapping disagrees with it. Public
tests cover fixed deterministic emission, nested/repeated identity separation,
and the missing-induction forgery. This checkpoint does not add target array
syntax, general slices, l-value selection, implicit memories, eager unrolling,
or wider generated operator admission. Legal analog arrays and their target
layout remain required, and every F-043 parent and descendant checkbox remains
open.

## Generated analog-node array target checkpoint

One generated-object profile now reaches a legal Verilog-A array layout. An
`hdlRange` body containing one or more electrical analog nodes, with a nonempty
unit-step range and exact generated owner/induction identities, lowers to one
electrical array per declaration whose indices are the authored half-open bounds. A
symbolic upper bound remains a target parameter expression; the implementation
does not evaluate its default, clone scalar nodes, or infer variable memory.

Native validation rejects non-node body operations, compound or non-unit bounds,
non-electrical objects, missing source identity, and forged ownership before
publishing target text. The independent target reparser validates both array
bounds against the module's declared parameter namespace. A public Scala
witness retains the `nodal.generate`/`nodal.node` source ownership and compiles
to the single electrical array. General shaped terminals, slices, l-value
selection, generated instances/connections, and mixed node/effect bodies remain
unsupported; all F-043 parent and descendant acceptance boxes remain open.

The node-group extension reuses this renderer and the existing module namespace
validator. It retains every declaration's canonical source path and the shared
region/induction identity; no additional layout registry or per-lane expansion
is introduced. Each node is validated independently, including later nodes in
the body. Native witnesses reject a forged second owner or induction, duplicate
target name and missing second source path without publishing partial output.
The public two-node witness uses a nonzero lower bound and a symbolic upper
envelope, checking stable source identities and repeated identical IR/target
emission. Native body order is preserved; public declarations retain the bridge's
existing canonical source-path order. Work and output grow with declaration
count, not the parameter's lane count. This is a compiler-only checkpoint; the
existing internal target reparse is not independent simulator qualification.

## Generated analog-node contribution checkpoint

The next bounded profile composes the qualified node-array declaration with one
exactly-owned additive analog contribution in the same direct generated body.
The body is deliberately fixed to one electrical node, one branch and one
continuous analog region under a direct nonempty unit-step range. The branch
must connect that node to an existing root terminal, and the contribution must
target the same branch. Exact region and induction identities are retained on
the node, branch metadata, analog region, access metadata and contribution.

The bridge now creates a branch inside the generated region only when an access
depends on a declaration owned by that region. Existing generated contributions
whose endpoints are both root terminals retain their module-level branch and
unchanged target shape. The backend binds the generated terminal to
`name[induction]`, emits one electrical array declaration and one runtime target
loop, and never specializes the parameter default or expands lanes.

Native witnesses reject a forged branch owner, missing node source and a deeper
or wider direct body without publishing partial output. The public symbolic
witness checks deterministic construction and bridge output plus configured
Verilog-A containing exactly one indexed contribution. Multiple nodes in a
mixed body, generated connections or instances, compound/non-unit node-array
bounds, procedural state or events, shaped terminal arrays, general slices and
source-index selection remain unsupported. Every F-043 parent and descendant
acceptance box remains open, and independent simulator qualification plus the
F-048/F-049/F-052 handoff are still required.

## Generated analog-node group contribution checkpoint

The direct generated-node contribution profile now accepts a nonempty group of
exactly-owned electrical declarations followed by one exactly-owned branch and
one continuous region. Every declaration retains the shared range and induction
identity and becomes its own electrical array. Exactly one declaration may feed
the branch; the remaining declarations must stay direct, unused topology in
this bounded profile. The selected declaration becomes the single indexed
target of the existing additive contribution loop.

The extension reuses the qualified node-array renderer and branch/contribution
path. It does not introduce a second layout engine, specialize parameter
defaults, unroll lanes or infer storage. Native checks reject forged later-node
owner or induction identities, duplicate target names, missing or duplicate
source identities, and a second direct branch before publishing output. The
public nonzero-lower, symbolic-upper witness retains both source-map entries,
stable declaration order, one branch, one contribution and byte-identical
repeated IR/target output.

Multiple selected nodes or contributions, generated connections or instances,
compound/non-unit node-array bounds, procedural state or events, shaped
terminal arrays, general slices and source-index selection remain unsupported.
This remains compiler-only evidence; independent simulator qualification and
the F-048/F-049/F-052 handoff are required. Every F-043 parent and descendant
acceptance box remains open.

## Generated analog-node group contributions checkpoint

The same direct generated-node group may now retain a nonempty ordered set of
exactly-owned branches and additive contributions. Each branch selects one
distinct generated electrical declaration and one existing non-generated root
terminal. The target keeps one array per declaration and emits one indexed
equation per selected declaration inside the existing symbolic loop. Unselected
declarations remain legal only when they do not escape the direct body.

This extension reuses the qualified array, branch, expression and contribution
renderers. It does not add another ownership registry or target engine, unroll
the range, specialize a parameter default or infer memory. Native admission
rejects duplicate node selection, generated-to-generated branches, forged branch
ownership, duplicate contribution targets, missing branch contributions and
ambiguous or deeper bodies before publishing output. Public and native witnesses
retain declaration and contribution order and require byte-identical repeated
IR/target output.

General slices, shaped terminals, generated connections or instances, mixed
procedural/event storage, compound or non-unit node-array bounds and source-index
selection remain unsupported. Independent simulator qualification and the
F-048/F-049/F-052 handoff are still required. Every F-043 parent and descendant
acceptance box remains open.

## Compound generated analog-node array bounds checkpoint

The qualified generated-node group contribution profile now accepts the
existing canonical three-SSA bound form. Lower, upper-exclusive and step bounds
are resolved through the same finite Integer-bound analysis and target
expression renderer already used by compound generated contributions. The
range must remain provably nonempty and exactly unit-step for every legal
structural-parameter setting; defaults are not substituted and lanes are not
expanded.

Every electrical declaration keeps the same generated region, induction and
source identity and becomes a separate array with the authored symbolic bounds.
The existing branch and contribution path continues to index each selected
array with the canonical induction. No new public schema, storage engine,
ownership registry or array renderer is introduced. Native and configured
public witnesses cover two declarations, a nonzero or symbolic lower envelope,
ordered indexed equations, deterministic repeated output and independent target
reparse. Forged later-node ownership or induction, duplicate names, missing
source identity, unsafe steps and undeclared target-bound identifiers fail
without partial output.

This remains the direct node/branch/contribution body profile. General slices,
source-index selection, shaped terminals, generated connections or instances,
mixed procedural/event storage and non-unit ranges remain unsupported.
Independent simulator qualification and the F-048/F-049/F-052 handoff are still
required. Every F-043 parent and descendant acceptance box remains open.

## Strided generated analog-node source-index checkpoint

The qualified direct generated-node contribution profile now accepts a
provably positive non-unit `hdlRange` step without adding a slice or array API.
The authored induction remains the source index and continues to drive the
target loop. Because Verilog-A electrical arrays have contiguous declared
ranges, the backend derives one compact zero-based target index as
`(induction - lower) / step` and a ceiling-divided trip-count bound. Each legal
source iteration therefore owns exactly one target array element; skipped source
indices do not create extra floating nodes.

Unit-step output remains byte-compatible and keeps the authored array bounds.
The strided path reuses the existing finite Integer-bound analysis, target
expression grammar, node-array renderer and exact generated owner/induction
checks. It neither substitutes parameter defaults nor unrolls lanes. Native and
configured public witnesses cover a nonzero lower bound, symbolic upper bound,
two selected electrical arrays, ordered mapped contributions, repeated
deterministic output and independent target reparse. A zero or potentially
nonpositive step rejects before any target text is published.

This is source-index selection for the existing direct generated electrical
node profile only. It does not introduce general shaped-value indexing or
slices, shaped terminals, generated connections or instances, mixed
procedural/event storage, reverse ranges or wider generated bodies. Independent
simulator qualification and the F-048/F-049/F-052 handoff remain required.
Every F-043 parent and descendant acceptance box remains open.

## Shared generated analog-node branch checkpoint

One exactly-owned generated electrical declaration may now participate in more
than one direct owned branch and additive contribution. The declaration still
becomes one target array, while every authored branch retains its own root
terminal, orientation and contribution order. This removes the earlier
one-branch-per-node restriction without creating aliases, implicit connections,
duplicate arrays or per-use storage.

The backend validates every use of the generated node before rendering: all
uses must be direct branches in the same generated body, each branch must select
exactly one generated node plus one existing non-generated root terminal, and
every branch must receive exactly one owned additive contribution. A selected
node cannot escape through an access, nested region or unrelated operation.
Public and native witnesses retain source identity, a single array declaration,
two ordered equations, deterministic repeated output and independent target
reparse; escape and partial-output negatives remain fail closed.

Generated conservative connections and instances, shaped terminals, general
slices, mixed procedural/event bodies and wider aliases remain unsupported.
Independent simulator qualification and the F-048/F-049/F-052 handoff remain
required. Every F-043 parent and descendant acceptance box remains open.

## Peer generated analog-node branch checkpoint

Two exactly-owned electrical declarations in the same direct generated body may
now form one branch with both endpoints indexed by the canonical generated
induction. Each declaration remains a separate target array and the resulting
equation preserves the authored positive/negative endpoint order. This admits
the legal direct peer topology without flattening the arrays, introducing an
alias or creating per-use storage.

The backend reuses the qualified node-array, branch, source-map and contribution
renderers. Both endpoints must be known terminals; every generated endpoint must
belong to the same generated body and exact induction, and all generated-node
uses must remain direct owned branches. A branch that selects the same generated
element at both endpoints, forged peer ownership or induction, deeper escape or
missing contribution rejects before target publication. Public and native
witnesses retain two source identities, deterministic IR/target output, exact
indexed endpoint order and independent target reparse.

Cross-range connections, generated instances, shaped terminals, general slices,
mixed procedural/event bodies and wider aliases remain unsupported. Independent
simulator qualification and the F-048/F-049/F-052 handoff remain required.
Every F-043 parent and descendant acceptance box remains open.

## Generated analog-node ddt access checkpoint

One direct potential access on an exactly-owned generated electrical-node array
may now feed one owned `ddt` and the additive flow contribution on the same
branch. The node remains one symbolic target array, and both the access and
contribution select its element with the canonical generated induction. This
combines the already-qualified node-array and non-state-retaining `ddt`
renderers without adding another operator, array, range or storage owner.

The native boundary requires exact generated owner and induction metadata on
the node, branch, access, operator and contribution. The access has exactly one
direct use as operand zero of the owned `ddt`; the derivative must reach an
additive contribution without leaving the generated analog body. Forged access
ownership or induction and a second/direct escape reject before target
publication. Public and native witnesses retain deterministic IR/target text,
the indexed `V(...)` inside `ddt`, the indexed `I(...)` target and
independent target reparse.

This profile does not admit `idt` or waveform/event state on generated nodes,
cross-range access, shaped terminals, general slices, generated
connections/instances, or mixed procedural/event bodies. Independent simulator
qualification and the F-048/F-049/F-052 handoff remain required. Every F-043
parent and descendant acceptance box remains open.

## Generated analog-node memoryless access checkpoint

One exactly-owned access on a generated electrical-node branch may now feed
its additive contribution directly or through the existing memoryless
add/subtract/multiply/divide/negate expression chain. The existing direct
`ddt` input remains supported. This admits an ordinary resistor equation
`I(tap, p) <+ (V(tap, p) / R)` inside `hdlRange` while retaining symbolic
resistance and bounds, and compact source-index mapping for strided ranges.

The backend reuses the canonical one-contribution dependency validator and
expression renderer. Every access and intermediate memoryless result must have
exactly one use, stay in the same analog body and reach the owned contribution.
The one-access/one-branch profile, exact generated owner and induction checks,
and array/branch identity checks remain enforced. No expression is copied or
unrolled. Native witnesses cover direct and divided access, forged metadata,
a second use, an unsupported operator after arithmetic, deterministic output
and target reparse; the public resistor witness exercises the configured
Scala-to-IR-to-Verilog-A path and source maps with a symbolic strided bound.

This does not admit stateful operators after a generated access, arbitrary
fan-out, cross-range references, general slices, shaped terminals or generated
instances/connections. Internal target reparse is compiler evidence, not
independent simulator or numerical qualification. The F-048/F-049/F-052 handoff
and every F-043 acceptance obligation remain open.

## Peer generated analog-node memoryless access checkpoint

The qualified peer-node branch and one-use memoryless access profiles now have
an explicit public composition witness: a symbolic, positive-stride generated
resistor connects two exactly-owned electrical arrays and retains both indexed
endpoints in its flow target and potential expression. The source induction
continues to drive the target loop, while the existing compact source-index map
selects the corresponding element of each Verilog-A electrical array.

No backend admission rule is widened. The composition still requires exactly
one owned branch, one generated access and one additive contribution path;
both nodes, the branch, the access and the contribution retain the same exact
generated owner and induction. Native evidence covers deterministic emission,
independent target reparse, forged peer owner/induction and a second access,
with rejection before partial output. The public witness retains both source
maps, symbolic resistance and bounds, repeated deterministic IR/target text and
configured native-backed lowering.

This remains a direct peer branch with a memoryless arithmetic path. It does not
add cross-range references, fan-out, stateful operators, general slices, shaped
terminals, generated connections/instances, default specialization or
unrolling. Independent simulator qualification, the F-048/F-049/F-052 handoff
and every F-043 acceptance obligation remain open.

## Peer generated analog-node ddt access checkpoint

The qualified peer-node branch and direct generated-node `ddt` access profiles
now have an explicit composition witness. Two exactly-owned electrical arrays
form one direct branch; its potential access feeds one exactly-owned derivative,
and the additive flow contribution retains the same indexed positive and
negative endpoints. The source induction remains the target index and no lane
is cloned or specialized.

No backend admission rule or public API is widened. The composition reuses the
canonical generated-node array, peer branch, access, `ddt`, contribution and
source-map owners. Native validation covers the positive target and rejects a
forged peer owner, peer induction, source identity, access owner or induction,
operator owner or induction, direct escape and a second access before any
partial target is published. Repeated emission must be byte-identical and pass
the independent target reparser. The configured public Scala witness retains
both node source maps plus exact branch, access, operator and contribution
ownership through normalized IR and Verilog-A.

This remains one direct peer branch with one potential access and one `ddt`.
It does not admit `idt` or other state-retaining operators after a generated
access, arbitrary fan-out, cross-range references, general slices, shaped
terminals, generated connections/instances, default specialization or
unrolling. Internal target reparse is compiler evidence, not independent
simulator or numerical qualification. The F-048/F-049/F-052 handoff and every
F-043 acceptance obligation remain open.

The same bounded composition now exercises an authored positive stride. Both
electrical arrays use the compact generated-instance domain while the target
loop retains the authored induction. Branch endpoints and the derivative's
potential access select each compact array with the shared source-index map
`((generate_0_index - 1) / 2)`. Owner, induction and cardinality mutations keep
the fail-closed behavior above; no second mapping registry or expression engine
is introduced.

This positive-stride witness is still one direct generated range and one direct
peer branch. It does not define a general slice signature, cross-range mapping,
negative step, nested generated operator graph or stateful operator profile.
Those profiles and their independent qualification remain open under the
existing F-043 checklist.

## Generated analog-node idt ownership checkpoint

One exactly-owned potential access on a generated electrical-node branch may
now pass through a one-use memoryless dimension-conversion path into one owned
`idt`, whose result directly feeds the additive contribution on that branch.
This admits an indexed potential accumulated from its corresponding voltage
rate while retaining the target loop as the only generated-instance domain.

The backend reuses the canonical one-use expression traversal and the existing
Increment 35 operator/state contract. The access path must stay in the same
generated analog body, traverse at least one supported memoryless expression,
and enter operand zero of exactly one `idt`. The `idt` independently retains the
exact range owner and induction, an operator-owned `state_id`, and one path to
the contribution. Direct dimensionally-invalid access-to-`idt`, forged access
or operator ownership, forged state identity, escape and duplicate access all
reject before target publication. Native evidence also requires deterministic
emission and independent target reparse.

The public witness retains its generated-node and induction source maps,
normalized access/arithmetic/operator ownership, fixed initial value and
configured Verilog-A with one indexed node array and one per-occurrence `idt`
expression. This is simulator-owned analog integration state scoped by the
generated target occurrence; it does not introduce a language memory, shared
module variable, array-flattening store or second state registry.

This bounded path does not admit arbitrary operator graphs or fan-out,
operator-to-operator chains, peer/cross-range state, general slices, shaped
terminals, generated connections/instances, default specialization or eager
unrolling. Independent simulator qualification, the F-048/F-049/F-052 handoff
and every F-043 acceptance obligation remain open.

## Peer generated analog-node idt composition checkpoint

The qualified peer-node branch and generated-node `idt` profiles now have an
explicit composition witness. Two exactly-owned electrical arrays form one
direct branch; its potential access traverses the required one-use memoryless
dimension-conversion path into one owned `idt`, and the additive potential
contribution retains the same indexed positive and negative endpoints. A
positive source stride continues to use the existing compact source-index map
for both arrays while the authored induction remains the target loop variable.

No backend admission rule, public API or schema is widened. The composition
reuses the canonical node-array, peer-branch, access, expression, `idt`, state,
contribution and source-map owners. Native evidence rejects a forged peer owner,
peer induction or peer source identity before partial target publication. The
public witness retains both source mappings, exact operator ownership and state
identity, deterministic normalized IR and Verilog-A, and independently reparses
the target.

This remains one direct generated range, one peer branch and one stateful
operator path. It does not define general slices, cross-range references,
arbitrary operator graphs, fan-out, shaped terminals, generated connections or
instances, default specialization, eager unrolling, or language memory.
Independent simulator qualification, the F-048/F-049/F-052 handoff and every
F-043 acceptance obligation remain open.

## Generated analog-node idt result-path checkpoint

The generated-node `idt` profile now permits its exactly-owned result to
traverse the existing one-use memoryless add/subtract/multiply/divide/negate
expression path before reaching its additive contribution. The stateful
operator still has one result use, one contribution destination and the exact
generated range owner, induction and operator-owned state identity. This admits
a dimension-preserving scale after integration without copying state or
creating another generated occurrence.

The backend reuses the canonical one-contribution dependency traversal and
expression renderer; no second operator graph or state registry is introduced.
The previous direct-result form remains supported. Native evidence covers the
direct predecessor, a divided result, fan-out rejection before partial output,
deterministic repeated emission and independent target reparse. A configured
public positive-stride peer-node witness retains both source-index mappings,
the pre-`idt` dimension-conversion path, fixed initial value and one post-`idt`
memoryless scale in normalized IR and generated Verilog-A.

This remains one direct generated range, one branch, one access and one
stateful operator occurrence. It does not admit result fan-out, a second
stateful operator, cross-range references, general slices, shaped terminals,
generated connections or instances, default specialization, eager unrolling or
language memory. Independent simulator qualification, the F-048/F-049/F-052
handoff and every F-043 acceptance obligation remain open.


## Independent generated analog-node equations checkpoint

A generated body can now retain independent memoryless equations on distinct
exactly-owned branches, including two branches sharing one generated node.
The canonical one-use expression walker checks the exact destination branch
for each access; each branch has at most one access and exactly one additive
contribution. Existing compact source-index mapping, scalar expression rendering,
source identity and range ownership remain authoritative. The change reuses
those owners rather than introducing an equation graph or array storage engine.

The public positive-stride witness has one node array and two resistor equations,
with deterministic normalized IR, source maps and configured target reparse.
Native coverage verifies authored equation order and repeated emission, and
rejects swapped destinations, duplicate branch access and forged access ownership
or induction without partial output. Stateful access still requires exactly one
access on one branch; the two-branch `ddt` negative preserves that boundary.

The raw public bridge witness contains four accesses: two flow handles used to
construct contribution targets and two potential reads in the resistor values.
The existing normalization removes the unused target handles, leaving exactly
two potential accesses for backend consumption. The test checks both stages
explicitly; the one-access-per-branch backend contract applies after normalization.

This does not admit fan-out, cross-branch expression dependencies, broader
stateful graphs, general slices, shaped terminals, generated connections or
instances, default specialization, eager unrolling or language memory. All
F-043 acceptance obligations and the F-048/F-049/F-052 handoff remain open.

## Repeated sibling generated-node array checkpoint

Two independent top-level generated ranges now have an actual public
Scala-to-normalized-IR-to-Verilog-A witness. Each range owns one electrical
node declaration, its own canonical region and induction identities, and its
own source-map entries. One range uses a symbolic positive stride and the
existing compact array bound; the other uses a separate symbolic unit-step
bound. Repeated construction, lowering and target emission must remain
byte-identical.

Construction snapshots retain the induction and declaration source mappings;
the canonical bridge derives a missing region source entry from its induction.
The witness checks unique snapshot induction/declaration mappings and exactly
one serialized source-map entry for each region, induction and declaration.

No backend admission rule, schema or public API is widened. The witness uses
the already-qualified direct node-array profile and verifies that the two
ranges retain distinct declarations, source identities and target arrays in
source order. Existing native alias and orphan-identity negatives remain the
fail-closed evidence for repeated ranges, while configured target generation
provides the actual public counterpart.

This does not admit nested target generation, cross-range references, shaped
terminals, general slices, generated connections or instances, contributions
between sibling ranges, default specialization or eager unrolling. Those
profiles, independent simulator qualification, the F-048/F-049/F-052 handoff
and every F-043 acceptance obligation remain open.

## Repeated sibling generated-node equation checkpoint

Two independent top-level generated ranges now retain separate memoryless
equations through the actual public Scala-to-normalized-IR-to-Verilog-A path.
The first range preserves a symbolic positive stride and compact source-index
map; the second preserves an independent symbolic unit-step bound. Each range
owns one electrical array, branch, potential read, flow target and additive
resistor contribution, with distinct region, induction, declaration and source
identities.

No backend admission rule, schema or public API is widened. The witness composes
the already-qualified sibling-array and one-use memoryless-equation profiles,
requires byte-identical repeated construction/lowering/target output, and checks
the two authored target loops and equations separately. A configured native
negative rewrites only a second-range operation owner to the first range and
requires exact ownership rejection before target publication. Existing raw and
normalized access-count contracts remain unchanged.

This does not admit cross-range references or contributions, nested target
generation, general slices, shaped terminals, generated connections or
instances, stateful fan-out, default specialization or eager unrolling.
Independent simulator qualification, the F-048/F-049/F-052 handoff and every
F-043 acceptance obligation remain open.

## Repeated sibling generated-node idt checkpoint

Two independent top-level generated ranges now retain separate stateful `idt`
equations through the actual public Scala-to-normalized-IR-to-Verilog-A path.
The first range keeps its symbolic positive stride and compact source-index map;
the second keeps its independent symbolic unit-step bound. Each range owns one
electrical array, branch, potential read, flow target, `idt` operator and
additive contribution, with distinct region, induction, declaration and state
identities.

No backend admission rule, schema, state registry or public API is widened. The
witness composes the qualified sibling-array and generated-node `idt` profiles,
requires byte-identical repeated construction, lowering and target output, and
checks both authored target loops and stateful equations. A configured native
negative rewrites only the second `idt` owner to the first range and requires
the existing exact-ownership verifier to reject it before target publication.
Existing source-map, raw/normalized access-count and single-state-path contracts
remain unchanged.

The raw bridge form has four potential accesses, two in each independent
equation: the authored voltage-rate read before `idt` and the owned `idt` result
read that feeds its contribution. Flow contribution targets remain direct branch
operands rather than `nodal.access` operations. The witness checks both access
owners and inductions exactly twice, preserving sibling separation without
inventing flow-access records.

This does not admit cross-range references or contributions, shared or escaping
state, operator fan-out, nested target generation, general slices, shaped
terminals, generated connections or instances, default specialization or eager
unrolling. Internal target reparse remains compiler evidence rather than
independent numerical simulation. The F-048/F-049/F-052 handoff and every
F-043 acceptance obligation remain open.

## Nested generated analog-node layout checkpoint

One bounded two-dimensional generated topology profile now reaches the existing
Verilog-A electrical-array target. A node-only top-level `hdlRange` may contain
one node-only child `hdlRange`; the child declaration retains both canonical
region/induction identities and lowers to one explicit row-major electrical
array whose extent is the product of the two proved finite iteration counts.
The existing one-dimensional layout remains byte-compatible, including authored
unit-step bounds and compact positive-stride bounds.

This uses the canonical generated-bound analysis and node-array renderer. It
does not substitute parameter defaults, enumerate either range, create language
memory or introduce a second shape/index registry. Both ranges must be provably
nonempty with positive steps, the child node must be unused topology with exact
owner/induction/source identity, and nesting is deliberately limited to two
target-visible dimensions. A forged child owner, deeper nesting, mixed bodies or
any node escape rejects before target publication.

The native witness requires deterministic repeated output and independent target
reparse. The public witness retains all three region/induction source mappings,
the direct sibling array and the nested row-major array through actual
Scala-to-normalized-IR-to-Verilog-A generation. Nested contributions or operator
state, cross-range references, general slices, shaped terminals, generated
connections or instances, default specialization and eager unrolling remain
unsupported. Independent simulator qualification, the F-048/F-049/F-052 handoff
and every F-043 acceptance obligation remain open.

An additional scale witness uses independent symbolic envelopes and positive
strides on both nested dimensions. It requires the target extent to remain the
product of the two canonical ceiling-divided iteration counts, with no default
specialization or unrolling. Every region, induction and declaration appears
exactly once in the serialized source map; replacing only the child's induction
with its parent's identity must fail before target publication. The native
literal counterpart independently reparses the same two-stride row-major form.

## Nested generated analog-node equation checkpoint

The bounded two-dimensional layout now composes with one already-qualified
child-owned memoryless equation. The outer range contains exactly one child
range; the child owns one electrical declaration, one branch to an existing
root terminal, one potential read and one additive resistor contribution. Both
ranges retain their authored positive strides and canonical source identities.

The existing node-array layout and one-use contribution dependency walker remain
the semantic owners. The target declares one row-major electrical array whose
extent is the product of the two independently proved iteration counts, emits
the two authored loops, and maps the child occurrence with

`outer_index * inner_count + inner_index`.

Each index term reuses the canonical compact source-index formula for its own
range. No parameter default is substituted, no lane is enumerated and no second
shape, expression or storage registry is introduced. Native evidence requires
deterministic repeated output, independent target reparse and rejection of a
child branch whose owner or induction is replaced by its parent. The public
configured witness retains both region/induction mappings, the child declaration,
branch, access and contribution through Scala construction, normalized IR and
generated Verilog-A.

This profile is deliberately limited to one child-owned one-use memoryless
equation. It does not admit sibling or cross-range contributions, nested
stateful operators, fan-out, a general slice/index signature, shaped terminals,
generated connections or instances, default specialization or eager unrolling.
Independent simulator qualification, the F-048/F-049/F-052 handoff and every
F-043 acceptance obligation remain open.

## Nested peer generated analog-node equation checkpoint

The bounded two-dimensional child equation now admits a second exactly-owned
electrical declaration as the negative endpoint of its one direct memoryless
branch. Both child nodes retain distinct canonical source identities, share the
same child owner and induction, and lower to separate product-extent electrical
arrays. The branch potential read and flow contribution select both arrays with
the same existing row-major index expression.

This is a narrow admission change at the nested profile gate. It reuses the
general generated-node renderer, canonical dependency validation, source map
inventory and target reparser; it does not add an API, schema, storage engine or
second index registry. Native and configured public evidence require exact
declaration order, byte-identical repeated output, independent target reparse
and rejection of a second node whose owner, induction or source identity is
forged before any target text is published.

The profile remains exactly one child range, at most two child-owned nodes, one
direct branch, one potential read, one memoryless arithmetic path and one
contribution. It does not admit multiple branches, cross-range references,
nested peer `ddt`/`idt`, operator chains, fan-out, a general slice signature,
generated connections or instances, reverse ranges, default specialization or
eager unrolling. Independent simulator qualification, the F-048/F-049/F-052
handoff and every F-043 acceptance obligation remain open.

The nested profile gate also enforces that boundary directly: a two-node child
body is accepted only when it has no `ddt` or `idt`. Native negative witnesses
derive both unsupported peer-operator forms from the qualified one-node
fixtures and require rejection without partial target publication. The existing
one-node nested `ddt`/`idt` paths and two-node memoryless path remain unchanged.

## Nested generated analog-node group equations checkpoint

The bounded two-dimensional child may now retain two exactly-owned electrical
declarations and two independent direct memoryless equations. Each child node
forms one branch to an existing root terminal, has one potential read, and
receives one additive contribution on that same branch. The declarations share
the child owner and induction while retaining distinct source identities.

This admission reuses the existing product-extent row-major layout, compact
source-index formulas, generated branch inventory and one-use dependency
walker. The target declares one array per node, emits the two authored loops,
and indexes both equations with the same canonical child occurrence. No
parameter default is substituted, no occurrence is unrolled, and no second
ownership, expression, shape or storage engine is introduced.

Native and configured public witnesses retain declaration, branch and equation
order, byte-identical repeated IR and target output, compact source maps and
independent target reparse. A forged second branch owner, forged second access
owner or contribution redirected to the other branch rejects before any target
text is published.

The profile remains one direct child range with at most two child-owned nodes
and no more branches than nodes. Stateful operators remain restricted to the
qualified one-node, one-branch, one-access path. Cross-range dependencies,
fan-out, general slices, shaped terminals, generated connections or instances,
reverse ranges, default specialization and eager unrolling remain unsupported.
Independent simulator qualification, the F-048/F-049/F-052 handoff and every
F-043 acceptance obligation remain open.

## Nested generated analog-node group idt checkpoint

The qualified two-node, two-branch nested group now admits exactly one
child-owned `idt` path while the other branch remains an independent direct
memoryless equation. The stateful branch retains one potential access, one
memoryless dimension conversion, one operator-owned state identity and one
potential contribution to that same branch. Both electrical arrays retain the
shared child owner and induction plus distinct declaration/source identities.

This is a bounded composition of the existing group-equation and nested `idt`
profiles. It reuses the product-extent row-major layout, compact source-index
formulas, one-use dependency walker, generated branch inventory and target
reparser. The `idt` remains simulator-owned state per generated occurrence; no
language memory, state array, second storage registry, default specialization
or host-lane unrolling is introduced. Native and configured public witnesses
require deterministic IR/Verilog-A, independent target reparse, exact source
maps and rejection of forged operator owner, induction, state identity or
contribution destination before any target text is published.

The profile remains one direct child range, two nodes, two branches, two
accesses, two contributions and exactly one `idt`. A second stateful operator,
operator chain, fan-out, sibling/cross-range state, general slices, shaped
terminals, generated connections or instances, reverse ranges and eager
unrolling remain unsupported. Independent simulator qualification, the
F-048/F-049/F-052 handoff and every F-043 acceptance obligation remain open.

## Nested generated analog-node group ddt checkpoint

The same qualified two-node, two-branch nested group now admits exactly one
child-owned direct `ddt` path while the other branch remains an independent
direct memoryless equation. The derivative branch retains one potential access,
one owned operator and one flow contribution to that same branch. Both
electrical arrays keep the shared child owner and induction plus distinct
declaration and source identities.

This is the bounded derivative counterpart of the group `idt` profile. It
reuses the product-extent row-major layout, compact source-index formulas,
one-use dependency walker, generated branch inventory and target reparser. No
state array, language memory, second expression engine, parameter-default
specialization or host-lane unrolling is introduced. Native and configured
public witnesses require deterministic IR and Verilog-A, exact source maps,
independent target reparse and fail-closed rejection of a forged operator owner,
induction or contribution destination. A second operator across the peer branch
is also rejected before any target text is published.

The profile remains one direct child range, two nodes, two branches, two
accesses, two contributions and exactly one `ddt`. Operator chains, fan-out,
sibling or cross-range dependencies, general slices, shaped terminals,
generated connections or instances, reverse ranges, eager unrolling and wider
operator admission remain unsupported. Independent simulator qualification,
the F-048/F-049/F-052 handoff and every F-043 acceptance obligation remain open.

## Nested generated analog-node idt checkpoint

The bounded two-dimensional child equation now admits exactly one previously
qualified generated-node `idt` path. The child-owned potential access traverses
one memoryless dimension-conversion expression into one child-owned `idt`; its
result feeds the additive contribution on the same branch. The operator retains
the exact child region and induction plus its canonical operator-owned
`state_id`. The outer range owns no declaration, branch, operator or state.

The target continues to use one product-extent row-major electrical array and
the two authored positive-stride loops. Both the indexed access and contribution
reuse the existing flattened occurrence expression; the `idt` remains one
simulator-owned stateful expression per generated occurrence. No language
memory, state array, second storage registry, default specialization or eager
unrolling is introduced. Native evidence rejects forged child operator owner,
induction or state identity before partial publication and requires deterministic
emission plus independent target reparse. The public witness preserves the two
range source maps, child declaration, access, operator, state and contribution
through Scala construction, normalized IR and configured Verilog-A generation.

This remains one child range, one node, one branch, one access, one `idt` and one
contribution. It does not admit nested operator chains, fan-out, sibling or
cross-range state, a general slice/index signature, shaped terminals, generated
connections or instances, reverse ranges, default specialization or eager
unrolling. Independent simulator qualification, the F-048/F-049/F-052 handoff
and every F-043 acceptance obligation remain open.

## Nested generated analog-node ddt checkpoint

The same bounded two-dimensional child equation now composes with exactly one
previously qualified direct generated-node `ddt` path. The child-owned potential
access feeds the child-owned derivative directly; its result may traverse the
existing one-use memoryless contribution path on the same branch. The outer
range owns no declaration, branch, access or operator.

The target reuses the product-extent row-major electrical array, both authored
positive-stride loops and the canonical flattened occurrence expression. No
lane is enumerated, no parameter default is substituted and no second shape,
expression or operator registry is introduced. Native evidence rejects a `ddt`
whose owner or induction is replaced by its parent before partial target
publication, and requires deterministic repeated emission plus independent
target reparse. The public witness retains both range source maps, the child
declaration, access, operator and contribution through Scala construction,
normalized IR and configured Verilog-A generation.

This remains one child range, one node, one branch, one access, one `ddt` and
one contribution. It does not admit an operator chain, fan-out, sibling or
cross-range operators, a general slice/index signature, shaped terminals,
generated connections or instances, reverse ranges, default specialization or
eager unrolling. Independent simulator qualification, the F-048/F-049/F-052
handoff and every F-043 acceptance obligation remain open.

## Nested generated analog-node mixed operator checkpoint

The bounded two-node, two-branch nested group now composes exactly one
child-owned `ddt` path with exactly one child-owned `idt` path on the other
branch. Each operator consumes the sole potential access for its own branch and
feeds the additive contribution back to that exact branch. The integral keeps
its canonical operator-owned state identity; the derivative remains
non-state-retaining. Both electrical declarations retain the shared child owner
and induction plus distinct declaration and source identities.

This is a narrow composition of the already-qualified nested group `ddt` and
`idt` profiles. It reuses the product-extent row-major layout, compact
source-index formulas, one-use dependency walker, generated branch inventory,
operator renderers and target reparser. It does not add a public API, schema,
state array, language memory, shape/index registry or expression engine.
Parameter defaults are not substituted and generated occurrences are not
unrolled.

Native and configured public witnesses require exactly one operator of each
kind, distinct branch destinations, exact owner/induction/state identities,
complete source maps, byte-identical repeated IR and target output, and
independent target reparse. Forged derivative ownership or induction, forged
integral state and a redirected contribution reject before target publication.
The predecessor two-`ddt` negative remains rejected, so this checkpoint does
not silently admit arbitrary same-kind operator groups.

The profile remains one direct child range, two nodes, two branches, two
accesses, two contributions, one `ddt` and one `idt`. Operator chains, fan-out,
wider or deeper generated groups, sibling or cross-range dependencies, general
slices, shaped terminals, generated connections or instances, reverse ranges,
default specialization and eager unrolling remain unsupported. Independent
simulator qualification, the F-048/F-049/F-052 handoff and every F-043
acceptance obligation remain open.

## Nested peer generated analog-node ddt checkpoint

The bounded two-dimensional peer-node equation now admits exactly one
child-owned `ddt` path on its single branch between two child-owned electrical
declarations. The potential access, derivative and additive flow contribution
retain the exact child region and induction; both branch endpoints use the same
canonical row-major occurrence index and keep distinct declaration/source
identities.

This is a narrow composition of the qualified nested peer memoryless equation
and nested single-node derivative profiles. It reuses the product-extent array
layout, compact source-index formula, one-use dependency walker, branch owner,
operator renderer and target reparser. It adds no API, schema, state storage,
language memory, alternate expression/index engine, parameter-default
specialization or host-lane unrolling.

Native and configured public witnesses require deterministic normalized IR and
Verilog-A, both indexed peer endpoints, complete source maps and independent
target reparse. Forged peer declaration owner, induction or source identity,
forged derivative ownership and a redirected contribution reject before target
publication. The peer `idt` predecessor remains rejected, so this admission does
not silently create peer state or a general operator graph.

At this checkpoint the profile remained one direct child range, two nodes, one
branch, one access, one `ddt` and one contribution. Peer `idt`, a second branch or operator,
operator chains, fan-out, wider/deeper groups, cross-range references, general
slices, shaped terminals, generated connections or instances, reverse ranges,
default specialization and eager unrolling remain unsupported. Independent
simulator qualification, the F-048/F-049/F-052 handoff and every F-043
acceptance obligation remain open.

## Nested peer generated analog-node idt checkpoint

The bounded two-dimensional peer-node equation now also admits exactly one
child-owned `idt` state path on its single branch between two child-owned
electrical declarations. The potential access, integral and additive potential
contribution retain the exact child region and induction; both branch endpoints
use the same canonical row-major occurrence index, and the integral retains its
canonical operator-owned state identity.

This is a narrow composition of the qualified nested peer memoryless equation
and nested single-node integral profiles. It reuses the product-extent array
layout, compact source-index formula, one-use dependency walker, branch owner,
stateful operator renderer and target reparser. It adds no API, schema, language
memory or state array, alternate expression/index/storage engine,
parameter-default specialization or host-lane unrolling.

Native and configured public witnesses require deterministic normalized IR and
Verilog-A, both indexed peer endpoints, exact operator/state ownership, complete
source maps and independent target reparse. Forged peer declaration owner,
induction or source identity, forged integral owner/induction/state identity and
a redirected contribution reject before target publication. The profile remains
one direct child range, two nodes, one branch, one access, one `idt` and one
contribution; this does not admit a general stateful operator graph.

A second branch, operator or state, operator chains, fan-out, wider/deeper
groups, sibling or cross-range state, general slices, shaped terminals,
generated connections or instances, reverse ranges, default specialization and
eager unrolling remain unsupported. Independent simulator qualification, the
F-048/F-049/F-052 handoff and every F-043 acceptance obligation remain open.

## Nested peer generated analog-node idt result-path checkpoint

The qualified nested peer-node `idt` result may now traverse one existing
memoryless scale before its additive potential contribution. Both electrical
arrays retain the same canonical row-major occurrence index, while the
integral keeps its exact child region, induction and operator-owned state
identity. The scale is part of the one-use result path; it does not create or
copy state.

No backend admission rule, public API or schema is widened by this composition
witness. It reuses the canonical generated-node layout, peer branch, one-use
dependency walker, stateful operator renderer, source-map inventory and target
reparser. Native evidence retains the direct predecessor, requires
byte-identical repeated output and independently reparses the scaled target. A
second use of the scaled result must reject before any target text is
published. The configured public witness preserves both node source identities,
both positive-stride dimensions and the single post-`idt` division from Scala
construction through normalized IR and generated Verilog-A.

The profile remains one direct child range, two nodes, one branch, one access,
one `idt`, one memoryless result scale and one contribution. Result fan-out, a
second operator or state, wider/deeper groups, sibling or cross-range state,
general slices, shaped terminals, generated connections or instances, reverse
ranges, default specialization and eager unrolling remain unsupported.
Independent simulator qualification, the F-048/F-049/F-052 handoff and every
F-043 acceptance obligation remain open.

## Nested peer generated analog-node ddt result-path checkpoint

The qualified nested peer-node `ddt` result retains its already-authored
capacitance scale before the additive flow contribution. Both electrical arrays
use the same canonical row-major occurrence index, while the potential access,
derivative and contribution retain the exact child region and induction. The
scale has one result use and does not add operator state.

No backend admission rule, public API or schema is widened. The evidence reuses
the canonical generated-node layout, peer branch, one-use dependency walker,
operator renderer, source-map inventory and target reparser. Native validation
retains the direct `ddt` predecessor, requires byte-identical repeated output
and reparses the scaled target independently. A second use of the scaled result
must reject before any target text is published. The configured public witness
preserves both node source identities, both positive-stride dimensions and the
single post-`ddt` capacitance multiplication from Scala construction through
normalized IR and generated Verilog-A.

The profile remains one direct child range, two nodes, one branch, one access,
one `ddt`, one memoryless result scale and one contribution. Result fan-out, a
second operator or state, wider/deeper groups, sibling or cross-range effects,
general slices, shaped terminals, generated connections or instances, reverse
ranges, default specialization and eager unrolling remain unsupported.
Independent simulator qualification, the F-048/F-049/F-052 handoff and every
F-043 acceptance obligation remain open.

## Nested unit-step occurrence-index repair

Nested generated electrical arrays have zero-based product extents. Their
row-major selections must therefore compose zero-based occurrence ordinals,
even when an authored unit-step range starts above or below zero. The previous
layout reused the direct-array index for such a dimension, which could select
the wrong element or exceed the nested array bounds.

The existing shared layout now retains both the authored array index and the
occurrence ordinal. A unit-step ordinal subtracts the lower bound; a strided
ordinal already performs that subtraction and division. Only nested equation
composition uses the ordinal. Direct one-dimensional unit-step declarations
and selections retain their authored bounds, and existing strided and
zero-origin output is preserved. No capture, ownership, API, schema, storage,
profile-admission or source-map contract changes.

Native regressions cover each unit-step dimension separately, both together,
negative origins, zero origins and singleton dimensions, with exact target
indices/extents, deterministic repeat emission and target reparse. Public
symbolic-envelope witnesses cover both unit steps and each mixed-stride case,
unique region/induction/declaration source maps and direct-array compatibility.
This is an indexing correctness repair, not independent simulator evidence or
F-043 acceptance; all remaining checklist and later-tool obligations stay open.

Local differential evidence: the unchanged new native regression fails against
the immediate predecessor's layout implementation and passes after the repair.
An independent evaluation of the actual emitted expressions enumerated all
143 occurrences across the six native cases and found exact zero-based,
non-overlapping coverage of each declared extent. The predecessor's first
mixed-stride case instead selected indices 3 through 23 for a 21-element array.
This is compiler/index evidence, not analog numerical simulation.

## Negative-origin nested peer idt index checkpoint

The corrected zero-based occurrence ordinal is also exercised through the
already-qualified nested peer `idt` result path. Both authored dimensions use
unit steps with negative lower bounds, both electrical arrays use the same
zero-based product index, and the integral retains its exact generated owner,
induction and operator-owned state identity before one memoryless division.

The independent native regression mutates the accepted scaled-`idt` fixture to
the literal ranges `[-2, 2)` and `[-3, 1)`, requires the exact 16-element array
extent and row-major index, reparses the target and repeats emission byte for
byte. Its existing result-fan-out rejection remains in the same matrix. The
configured public witness keeps symbolic upper envelopes, requires unique
region, induction and declaration source identities, rejects a forged state
identity before target publication, and retains exact generated Verilog-A.

This checkpoint composes existing bounds, peer-node, stateful-operator and
single-scale contracts. It does not admit a second state or result use, wider
or deeper generation, cross-range storage, general slices, shaped terminals,
generated connections or instances, reverse ranges, default specialization or
eager unrolling. It is compiler/index and storage-ownership evidence, not an
independent simulator result or F-043 acceptance. Every F-043 parent and
descendant obligation remains open.
