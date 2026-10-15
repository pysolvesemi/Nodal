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
