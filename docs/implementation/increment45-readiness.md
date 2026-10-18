# Foundation 45 readiness

**Date:** 2026-10-18
**Status:** Complete; verified squash integration `6bc1d37ea5909de093a9e163c618df8018374c35` preserves the tested tree `afb91b27aa53626204dbad98fb9c63c396bc54ab`.
**Branch:** `increment/45-analog-canonicalization`
**Base:** `9e0e368019961f78977ad80550a21ea1956fd417`

F-044 is integrated as `cd7e8067998fd609fa891ecfb25598c1f117cbca` with exact tested
tree equality, followed by documentation-only closure. Apply the current prototype
policy: focused checks, directly affected predecessors and concise architecture
review, `[skip ci]`, no CI or hourly monitoring.

## Original required work and selected owners

F-045 requires safe folding, residual-preserving algebraic normalization, dead
declaration removal, branch/access normalization and deterministic common-expression
handling. The [v0.1 gate](../design-gates/NodalAnalogCanonicalization-DG-v0.1.md)
defines the finite implementation profile rather than treating existing generic
MLIR canonicalization as proof of these behaviors.

- `AnalogNumeric.cpp` owns constant facts and invalid arithmetic. Existing folding
  annotates authored operations; it does not itself implement all F-045 rewrites.
- The new target-neutral pass reuses the transactional Increment 21 pipeline and
  existing access/selector normalizers. It must not create another topology engine.
- Increment 32 source/native recorders preserve authored sides and residual intent.
  The bridge carries these as semantic inventory dictionaries, not executable
  equation SSA. Preserve and validate that inventory without inventing a string
  expression parser or taking over later DAE lowering.
- `AnalogUserFunctions.cpp` proves the small immutable literal-local dead-declaration
  case; the existing function backend independently verifies before target elision.
  Mutable storage's declaration order and textual read inventories are untouched.

## Concrete pre-implementation findings

A focused native witness with symbolic Real parameter `X` and `X / 0.0` currently
passes folding and numeric verification. `evaluateBinary` returns dynamic before
checking the denominator, and `verifyBinary` checks known zero only for two Integer
operands. Repair the shared owner and pair positive symbolic-denominator behavior
with exact positive/negative-zero regressions. This is not permission to reject
all authored symbolic division or to introduce unproved division in rewrites.

The numeric evaluator recursively recomputes shared DAG operands. Measure a bounded
shared diamond before claiming representative scale; use a scoped shared facts
cache if needed. The renderer caches strings but can still expand a repeated
expression textually, so no whole-pipeline linearity or automatic HDL compression
claim follows from native CSE.

F-041 forbids call deletion/CSE/inlining. F-033/F-034 require contiguous authored
storage declaration order and also carry reads in textual inventories. Generic
`use_empty()` DCE is therefore unsound. The new gate explicitly limits actual named
declaration elision to verified unused literal-initialized immutable function locals.

## Acceptance obligations

Execute exact native rewrite/provenance/transaction checks, shared
numeric regressions, function-local proof and target parser checks. Export actual
public Scala/normalized IR/VA, preserve equation/contribution evidence and repeat
passes to a deterministic fixed point. Record concrete F-048 compile and F-049/F-052
numerical handoff references/tolerances, including observations that distinguish
state or contribution changes. Executed compiler results are recorded below; numerical execution remains a later
tool handoff.

## Planned actual-source tool handoff

The public `AnalogCanonicalizationSource` fixture uses symbolic `gain=2` and
`offset=3`. Its two equal potential contributions must remain two contributions,
with summed reference `V(positive,negative)=2*(gain+offset)` volts (10 V at defaults).
The double-negation/literal-arithmetic branch has reference
`V(arithmetic,negative)=gain+6` volts (8 V). The observation branch retains two time
reads and two calls to an identity-returning function; its reference is
`V(observed,negative)=2*time_seconds+2*(gain+offset)` volts, 10–14 V over 0–2 s.
These references are planned checks, not executed simulation results.

For F-048, compile the actual normalized VA with a declared compatible tool and
keep source/tool hashes and failure classification. For F-049/F-052, use unloaded
outputs and a reference node; compare DC defaults and gain/offset parameter cases,
then transient 0–2 s with maximum step 0.01 s. Compare each output with absolute
error <= 1e-8 V + 1e-6 relative. Also compare to an explicitly authored unsimplified
two-contribution reference, so contribution merging/deletion cannot hide behind a
single algebraic reference. Inspect function local declarations and call counts
structurally; internal unused locals have no numerical observable of their own.

F-032 equation/initial-equation inventory is a separate construction/native
normalization witness. Its strings are retained as authored evidence; no target
or numerical equation-execution claim follows from an inventory-only record.

## Executed shared numeric repair

The zero-divisor repair and scoped evaluator cache pass 22 focused native cases,
including dynamic numerator divided by typed/legacy positive/negative zero,
computed/fixed zero, symbolic zero-default acceptance, integer promotion,
nonfinite arithmetic, a 64-level shared diamond, literal mutation and parameter
variability mutation between public API calls. Seventeen directly affected native
CTest cases and six selected mathematical-function cases passed against refreshed
binaries. The additional backend-fold-boundary case was rerun successfully after
refreshing `nodal-translate`; its earlier stale-binary result was not used as final
patch evidence.

For a shared-add diamond, 19 distinct SSA values took median 0.0987 s before and
0.0105 s after the scoped cache, measured over three separate tool invocations in
this shared GCC Release environment. Timings include process overhead. The
1,048,554 uncached recursive visits versus 19 successful cached node evaluations
are derived from the graph recurrence, not instrumentation. This is evidence for
the local shared-DAG repair, not a whole-pipeline throughput claim. Measurements
and input graphs are under `.work/analog-numeric-diamond/`.

The cache belongs to one public query or verifier/folder walk. Only successful
constant/dynamic results are cached; errors remain available for diagnostic replay.
No cache survives authored IR mutation. Folding changes only annotations ignored
by this evaluator. The recursion depth of a single long chain is not removed by
memoization; the 64-level witness is a bounded representative check.

## Actual public compiler demonstration

The maintained public fixture is
`examples/continuousTimeApi/src/nodal/increment45fixture/Increment45ConstructionCheck.scala`.
Five focused Scala cases pass, including deterministic fresh VA/AMS construction
and unchanged authored equation/contribution evidence. Pinned Scala formatting and
syntactic lint pass on all three new source/test/export files.

The actual emitted contribution model constructs two independently authored
`gain + offset` expressions, two equal potential contributions, `-(-gain)`, literal
`2 * 3`, repeated time reads and two calls to an identity-returning function.
The final native replay observes add operations 8→7, multiply 5→4 and negation 2→0;
the two divisions, two time observations, three calls (one in a function), four
contributions and two parameter references remain. Function-value SSA is retained;
only the proved unused literal-local declaration and assignment disappear from HDL.
Dynamic and call-fed unused local declarations remain. The final replay uses the frozen rebuilt tools and verifies their hashes before
and after execution.

Actual VA output includes:

```verilog
V(positive, negative) <+ ((gain + offset) * 1);
V(positive, negative) <+ ((gain + offset) * 1);
V(arithmetic, negative) <+ ((gain + 6) * 1);
```

The separate `AnalogCanonicalizationEquationInventory` witness retains its ordinary
and initialization-only equations plus two separately identified equal contribution
records under the standalone canonicalization pass. Its full default gate rejects
floating terminals because there are no executable equations. This is an explicit
inventory-preservation witness, not supported target equation lowering.

## Independent compiler result

OpenVAF-reloaded `20260616-3-g0e83f1ed` successfully compiled the actual generated
`canonicalization.va` into OSDI (exit 0). Command:

```sh
.work/f43-array-initializer-profile/openvaf-reloaded/openvaf-r .work/increment45/public/canonicalization.va -o .work/increment45/openvaf/canonicalization.osdi
```

VA SHA-256: `32a5ccfbc7313e5e9843158a5dfd8dec17a16940c73e99642d68a2034d327ed9`.
OSDI SHA-256: `0bbcdf601d1effb323697306bae4b5a7fce0dffa8773396605c79374ab5706bc`.
Tool SHA-256: `b117284d8f871dbf54609ab009cdff819f443d8d33064111b8b0782af5c7e931`.
Logs and version evidence are under `.work/increment45/openvaf/`. No simulator
loaded or executed this OSDI, and no numerical comparison is claimed. Final target
replay retained this source hash, so the independent compile applies to the exact
final emitted bytes.


## Named local proof and native attribute boundary

Sixty focused dead-local cases pass after rebuilding the dialect hook and tools.
The native proof checks exact Real/Integer literal typing and finiteness, use count,
original SSA/source retention, idempotence and complete original function validity.
Used, input-dependent, expression-fed, select-fed and call-fed locals remain.
Mutable storage and function calls remain. Unused invalid division, overflow,
mathematical domains and invalid selected branches still reject. Reserved target
names are checked before elision.

The exact namespaced marker is checked by the dialect operation-attribute hook,
including outer `builtin.module` and foreign operations. False/string/forged true
markers reject rather than relying solely on a nested Nodal module scan. The
standalone canonicalizer and target function renderer also validate the proof.
This closes a discovered malformed-input boundary; a marker is not authority to
omit a declaration. Evidence: `.work/analog-dead-local-attribute-build.log` and
`.work/dead-local-probes/wrong-top-level-fixed.err`.


## Final focused acceptance and original checklist reconciliation

The canonicalizer passes 34 cases: same-block CSE with separate source origins,
exact operand/type/semantic-attribute/scope boundaries, Real-only double negation,
retained Integer negation and `x/x`/`x-x`/signed-zero counterexamples, effect and
contribution preservation, inventory ownership/reuse/family identities, invalid
inventory/journal rejection, transaction rollback and fixed points. Seven focused
fresh-binary CTests pass: canonicalization, dead-local, semantic-pipeline, all three
gate profiles and numeric backend folding. These overlap the individual Unit
counts and are not additional independent semantic cases.

Final public replay passes both target profiles with the exact graph counts above,
complete-gate fixed points and direct/normalized target equality. Final tools:
`nodalc` SHA-256 `dc81e68d9c9f2424caefccb212adb48d2c812884c2d0d1c2251d8c77385ce113`;
`nodal-translate` SHA-256
`2b57d95d13cf224eaaf17207364f0b151923616593a1973a9b2ea32fcd1b4dc8`.
Reproduction:

```sh
.work/scala-validation/mill-jvm.sh core.scala.testkit.test.testOnly nodal.internal.testkit.Increment45CanonicalizationTests
.work/scala-validation/mill-jvm.sh core.scala.testkit.test.runMain nodal.internal.testkit.Increment45MlirCheck .work/increment45/public
.work/increment45/public/replay-native.sh
```

Five maintained predecessor fixtures (zero-power independent noise, Laplace,
sampled transfer, time/waveform and differential/integral operators) preserve exact
effect/contribution multiplicity, reach a fixed point and emit ten VA/AMS outputs.
The explicit VA fixture configuration is changed to the actual AMS profile for AMS
replay; passing incompatible VA layout/materialization settings is not counted as
an operator failure or success. The F-044 public replay also passes all ten supported
target outputs and four exact inherited capability negatives, with unchanged scalar
VA bytes. Commands/logs: `.work/increment45/effects/check.py`,
`.work/increment45/effects/result.log`, and
`.work/increment45/f44-target-predecessors.log`.

The flat journal scale witnesses use 64/256 repeated scalar nodes, producing
65/257 records and 28,228/111,713 serialized bytes, approximately 1/3 ms in this
shared Release environment. Timing is illustrative, not an acceptance threshold.
The second pass is byte-identical. Hash lookup is never iterated to select a
representative; first source order determines it. Journal entries contain local
source/operand records, never expanded expression trees. Cloning within the
already-transactional gate adds module-copy work; no whole-pipeline speedup is
claimed. Native ownership/marker and scalar-effect reviews found no remaining
concrete blocker after the focused repairs.

A.1/A.2 retain residual/provenance and reuse the existing pipeline. B is satisfied
by shared safe folding, finite Real algebraic normalization, exact pure CSE,
actual verified literal-local target declaration removal and existing access/branch
normalization. C covers causal negatives and affected predecessors. D retains the
actual compiler demonstration, exact independent compile and declared numerical
handoff. E separates mandatory validated normalization from unproved optional
identities. F measures bounded DAG behavior and deterministic fixed points.
A–G and the parent are complete after verified squash integration `6bc1d37ea5909de093a9e163c618df8018374c35`.
The integrated tree is exactly the focused-tested tree above; no production
changes were introduced by this documentation-only closure.
No CI, broad qualification or numerical simulation was executed.
