# Foundation 160 readiness and implementation record

**Date:** 2026-09-29
**Status:** Readiness recorded; implementation and qualification in progress.
**Scope:** Foundation 160, construction frontend modularization only.
**Branch:** `increment/160-construction-frontend-modularization`, targeting `dev`.

## Authority, baseline and dependency

The owner requested "start next increment in foundation roadmap" on 2026-09-29.
The [roadmap index](../roadmap/README.md) and the approved
[F-160 companion plan](../roadmap/construction-frontend-modularization-v0.1-plan.md)
make accepted F-042 -> accepted F-160 -> start F-043 the required order.
The companion remains the sole editable parent/child status owner. The unrelated
historical low-power roadmap PR #106 does not own this Foundation increment.

The refreshed integration baseline is
`cafd52e5b7ea0d63b1eb503d281cf801bedd6808`, tree
`f14dc1e38e08c07243fda52e61378b95c7bd19d9`. Its parent is the accepted F-042
closure merge `47989a9eb20cdf6fb1374e9e66652bbc4ad7b77f`, tree
`a2ed2b081e9b515b9aabfb12c80d59b0305ec59a`, from merged PR #136.
The accepted implementation and historical execution evidence remain in
[F-042 closure](increment42-evidence-closure.md); they are the behavior baseline,
not execution evidence for a new F-160 candidate.

Readiness inspected current AGENTS.md, CONTRIBUTING.md, the design-gate policy,
the complete F-160 and F-043 checklists and their lightweight-hierarchy amendment,
the accepted F-042 implementation/closure records, actual frontend consumers,
bridge/native and backend checks, public fixtures, source-review contracts and
workflow definitions. No existing F-160 modularization branch or PR was found.

## Child applicability

No obligation is removed, weakened or transferred by this review. All current
F-160 obligations are required now within the accepted compiler profile.

| ID | Required deliverable and acceptance boundary |
| --- | --- |
| F-160.A | Aggregate of the boundary audit and pinned baseline/methodology. |
| F-160.A.1 | This baseline, consumer/owner map and justified extraction set. |
| F-160.A.2 | Protected-path gate, exact baseline/fixture/tool identities, immutable experiment definition and predeclared regression budgets before judging results. |
| F-160.B | Aggregate of production extraction and integration. |
| F-160.B.1 | Extract records, facade, expression facts and Interface layout; retain one session as the mutable transaction owner. |
| F-160.B.2 | Preserve all facade consumers and publication ordering; replace extracted bodies with real calls and remove the superseded bodies. |
| F-160.C | Aggregate of correctness and failure-safety coverage. |
| F-160.C.1 | Run existing hierarchy/domain/operator cases, ownership/type/unit/static-effect negatives and source-located diagnostic checks. |
| F-160.C.2 | Preserve exact-once evaluation, poisoned-construction cleanup, fresh/nested sessions and existing separate-compilation tests. |
| F-160.D | Aggregate of differential artifacts and executed validation. |
| F-160.D.1 | Identical public fixtures must produce identical complete construction records, normalized IR, HDL and diagnostic/source identities. |
| F-160.D.2 | Retain separate baseline/candidate identities and actual commands, results and hashes; execute affected frontend/bridge/native/target checks. |
| F-160.E | Review actual calls, ownership and coupling; no new optimization or public behavior change is required. |
| F-160.F | Aggregate of the bounded scale baseline and its assessment. |
| F-160.F.1 | Measure paired small/large hierarchy, repeated-instance, shared-expression and symbolic-binding workloads with stage/resource separation. |
| F-160.F.2 | Assess the predeclared budgets, resolve material regressions and hand reproducible workloads to F-096. |
| F-160.G | Aggregate of qualification and accepted completion. |
| F-160.G.1 | Targeted-first CI, independent Codex review, full applicable qualification, verified integration and required evidence closure. |
| F-160.G.2 | Deliver boundary, parity, scale and reproduction records plus actual unchanged Scala-to-HDL demonstration and verified prerequisite satisfaction for F-043. |

F-043 arrays/generation, F-096 comprehensive optimization/Rust evaluation and
F-159 future iteration semantics remain with their named owners. Independent
OpenVAF/numerical qualification remains with 48/49/52 as in the accepted F-042
profile. None is a reverse prerequisite. No required unavailable lane receives
pass or non-applicability credit. Digital-only fixtures provide construction and
diagnostic coverage without asserting support in the analog target profile.

## Production owner map and selected design

The inspected kernel is 3,092 lines. Size motivates inspection, not acceptance.
One `ConstructionSession` already owns mutable construction state correctly.
The chosen extraction preserves that ownership and removes two independent
semantic responsibilities from its orchestration.

| Component | Responsibility and permitted dependencies |
| --- | --- |
| `ElaborationConstructionKernel.scala` | Existing `ConstructionKernel` facade, immutable ScopedValue session binding and unchanged entry signatures. Creates one session per elaboration. |
| `ConstructionRecords.scala` | Existing internal record and immutable snapshot types with identical names, fields, defaults and visibility. No registry or lifecycle. |
| `ConstructionSession.scala` | Sole owner of module/domain/declaration/expression/instance identity maps; ordered records, stacks, operations, constructor failure state, origin builder and final publication. Coordinates hierarchy, analog capture, validation and snapshot assembly. |
| `ConstructionExpressionFacts.scala` | Stateless dimension arithmetic/inference, waveform staticness/constant/continuity and discipline facts. Reuses existing operator contracts and scoped procedural facts; no memoization or second expression registry. |
| `ConstructionInterfaceLayout.scala` | Stateless role/member/protocol expansion using a narrow type-rendering callback. Owns no session or registry. |

The existing consumers remain `CandidateRuntime`, `Nodal.emit`, constructor
instrumentation, `ScalaToMlirBridge`, `ReproducibilityContract`, analog control
and procedural construction, analog function/noise/transfer APIs and package
testkit inspection. No frontend/bridge schema or public manifest changes are
needed. The exact protected-path compatibility contract is recorded in
[the F-160 design gate](../design-gates/NodalConstructionModularization-DG-v0.1.md).

Alternative one-file retention keeps unrelated dimension and Interface details
inside transaction reasoning. A new mutable hierarchy/analog service framework
would require broad registry views or callbacks in both directions. A wholesale
snapshot assembler would copy or expose final identity maps without a measured
need. The selected stateless helpers provide useful boundaries with narrow
interfaces; remaining shared orchestration is deliberate.

`captureExpression` retains ordinal allocation, identity insertion, expression
registration, active analog-region insertion and origin capture in that order.
Interface lookup retains duplicate-member, role-completeness, domain and access
validation before expansion. Constructor/procedural session nesting, failed
construction cleanup and final validation-before-publication remain unchanged.

The source-location builder excludes implementation filenames explicitly. Add
only the new internal construction filenames to its existing central list; do
not broadly exclude package `nodal`, which also contains public fixtures.
Historical source contracts must inspect the actual extracted production source
through one explicit successor inventory. Preserve frozen checker bytes and
historical evidence. Retain each required/forbidden rule and mutation coverage;
neither comment fragments nor missing-body exemptions establish parity.

## Baseline identities and differential protocol

| Baseline file | SHA-256 |
| --- | --- |
| `ElaborationConstructionKernel.scala` | `2b27ef700daf914b5e7c25cf91b79b15d8d753c6c021c3c3bb3a7b20c9ea9a7a` |
| `SemanticOriginKernel.scala` | `4907360753833589509008ba79201316d26f5b37d3384abb0607ec7078b5006a` |
| `build.mill` | `839568b74c691a3e28b4c20cb6d848a15bdfafbb9fff09024bdc303e6c3a7761` |
| `toolchains/lock.json` | `c00620463fc06a9a7684076b8c77e52e8cbca1ad4cf14c6940f7d4a01022b239` |
| `toolchains/lint-lock.json` | `89a5f2f08963473dd4f76e8a3b8f89ad1f221301a3b06150488735b3b5d86c70` |

Use Scala 3.8.4, Mill 1.1.7 and managed Zulu JDK 25, with unchanged native/lint
locks. Pin the harness and workload bytes before running either version. Apply
identical test-only harness sources at identical repository-relative paths in
separate baseline/candidate checkouts, recording this overlay separately from
the compiler source identity. Set `NODAL_WORKSPACE` to each actual checkout so
source discovery cannot accidentally resolve the other checkout.

Reuse constructor/default/explicit/automatic/domain/factory/replication cases,
typed symbolic overrides, the four conservative connection forms, KernelTop,
and the accepted hierarchy equation/event/function/repeated/nested sources.
Include relevant differential/integral, waveform, noise, transfer and user
function fixtures. Retain existing negative and separate-compilation suites.

Serialize the full `ConstructionSnapshot` recursively, including all product
field names, Option values and ordered sequences. Sort only inherently unordered
maps/sets; reject unhandled values rather than hiding them with arbitrary
`toString`. The existing reproducibility serialization is additional evidence:
it omits some newer fields and is not a replacement for the full record.
Compare artifact bytes exactly, without path stripping, output rewriting,
renaming or updating expected results to accept a difference.

Use `ScalaToMlirBridge.fromSnapshot`, then the actual locked native verifier and
Verilog-A translator for target-capable fixtures. Record separate construction,
canonical record, source MLIR, normalized MLIR, generated target and diagnostic
artifacts with hashes, options and source identities. Preserve failed artifacts.
Internal reparse remains compiler validation, not independent analog simulation.

## Predeclared scale methodology and budgets

This methodology is recorded before obtaining before/after measurements. It
requires no speedup and makes no universal performance claim.

| Family | Small / large | Construction contract |
| --- | --- | --- |
| Deep hierarchy | depth 4 / 24 | Fixed accepted hierarchy; no new generation. |
| Wide hierarchy | 8 / 128 children | Distinct accepted child instances. |
| Repeated definitions | 8 / 256 leaves | Reusable compatible definitions, stable instance paths. |
| Shared expression DAG | 16 / 256 uses | Same shared expression and ordered consumers. |
| Symbolic bindings | 8 / 128 bindings | Existing typed static parameter overrides. |

Run three paired fresh-JVM trials in baseline/candidate, candidate/baseline,
baseline/candidate order. Record the first cold iteration, two warmups and five
measured warm iterations per case. Keep JVM options and workloads identical,
run construction sequentially and retain individual samples.

Time compilation/wrapper/JVM startup separately. Bracket public construction
inside inspection; total inspection minus constructor body is explicitly named
lifecycle/validation/snapshot time, not pure snapshot assembly. Separately time
full-record serialization, Scala-to-MLIR bridge, native verification and target
translation. Capture thread allocations, GC counts/time, JVM memory-pool peaks,
process peak RSS and environment where the platform exposes them. Record
unavailable counters and resource-limited cases honestly.

The executable experiment is
[`experiment.json`](../../tests/compiler/fixtures/increment160/experiment.json).
It fixes 55 public-source cases, including the ten scale workloads above.
Ordinary Mill compilation and dependency resolution are retained as setup
commands. The compared compilation boundary is consumer-fixture compilation:
each paired trial starts a fresh pinned-JDK `dotc` process on the identical two
probe/workload sources, with resolved compiler dependencies, the production
constructor plugin and a new empty output directory. Record the emitted class
inventory and execute those classes first on the runtime classpath.
This does not measure a clean rebuild of the frontend compiler itself.
Measure a separate empty-workload JVM startup once per role and pair. These
six compilation and six startup observations are required alongside the
construction trials; setup cache differences receive no performance credit.

Separately built constructor-plugin JARs contain different absolute source
paths in TASTy and its class-file linkage identifiers, even with identical
plugin sources, compiler dependencies, JDK and build configuration. Preserve
both original setup JAR records. Copy the original baseline JAR byte for byte
into the experiment's tooling directory and use that single immutable plugin
for both roles' provider setup and all timed compilations. Record its original
source/tool provenance and recheck the original JARs and shared copy; do not
normalize or reconstruct any JAR.

The plugin also captures the raw compiler input path in constructor failure
sites. Compile with repository-relative source arguments at each actual
checkout, which preserves identical diagnostic paths without rewriting output.
Mill's `core.scala.testkit.test.allSourceFiles` supplies the complete 37-file
inventory: 35 unchanged accepted provider sources and the two identical
overlays. Recompile all 35 providers once per role in unmeasured setup, then
compile only the two overlays in each timed trial. Place the fresh provider
classes before the original testkit classes on both the compiler and runtime
classpaths; the newly timed overlay classes remain first at runtime. Retain
and recheck the complete source and output-class inventories. These setup
repairs preserve the workload, exact artifact comparisons and budgets below.

The candidate median must stay within baseline plus the larger of:

- warm-stage wall time: 25 percent or 10 milliseconds;
- cold/startup/compile/native time: 30 percent or 100 milliseconds;
- allocated bytes: 15 percent or 64 KiB;
- process peak RSS: 20 percent or 16 MiB.

These floors avoid percentage claims about timer noise and tiny workloads.
Report GC counts without percentages when the baseline has no events. If a
budget exceedance overlaps within three median absolute deviations, classify it
as noisy/inconclusive and repeat the same paired experiment once. Never convert
noise into an automatic pass. An unexplained persistent material regression
remains open for repair or an explicit justified review decision. The original
samples and predeclared budgets remain retained.

The operational harness smoke took about 224 seconds for one 55-case pass on
the local host. This is a capacity observation, not a before/after performance
result: six such passes suggest about 22.4 minutes per epoch, or 44.8 minutes
with the one permitted noisy repeat. Core therefore gives the new comparison
step 60 minutes, including its predecessor checks, setup and clean fixture
compilation. The original native validation keeps an absolute 50-minute
deadline established before checkout and cache restoration; bootstrap and
build commands consume its remaining time and fail if it expires. Only the
F-160 branch/PR receives a 115-minute outer job allowance for those two bounded
phases and artifact handling. Other branches retain the original 50-minute
outer limit. No workload, per-command timeout or regression budget is relaxed.

## Publication, qualification and continuation

Publish this focused readiness/gate commit with `[skip ci]`, create one draft
PR and enable one hourly continuation before implementation. This documentation
commit does not run CI or claim runtime evidence. Recheck live refs before each
publication; the active worker records a bounded checkpoint so a continuation
does not compete with current edits.

The implementation candidate requires an exact-head affected-workflow inventory
before launch. Core owns shared contracts, all Scala suites and the constructor
separate-compilation checks. RC/target parity and accepted F-042 public target
coverage also require their real specialized execution; Core alone is not that
evidence. Some historical 16/17/32/33/34/35 workflows are not dispatchable:
map their required checks to actual shared targeted execution and preserve
their applicable full-PR jobs. Never dispatch a zero-job surrogate.

Run independent Codex review alongside targeting; repair confirmed findings and
finish affected targeting before full CI. Preserve all unchanged successful
same-head runs. Merge/closure follow the current verified squash policy with
`[skip ci]` and tested-tree equality; no predecessor result is relabeled as
current execution. Keep F-160 open and its continuation enabled until accepted
evidence, the actual unchanged HDL demonstration and F-043 eligibility are
complete.

## Inherited equation-output limitation

A [review posted after the F-042 closure merge](https://github.com/pysolvesemi/Nodal/pull/136#discussion_r4123215969)
observed that its `HierarchyEquationTop` demonstration contains no executable
analog equation body. The accepted
[equation/contribution gate](../design-gates/NodalAnalogEquationContribution-DG-v1.0.md)
explicitly defers equation-to-target legalization and Verilog-A/Verilog-AMS
lowering. The current [Foundation roadmap](../roadmap/nodal-development-todo.md)
assigns that capability to F-141.B.2, with source/residual work in F-134/F-135.

For F-160, the equation fixture is a structural-record, hierarchy and exact
output-parity witness. Its unchanged target cannot establish the numerical
`H42-EQUATION-DC` handoff. That later numerical case needs a reviewed,
behavior-bearing companion using the already supported contribution API, or
the separately owned equation legalizer. The 48/49/52 validation owners must
establish that prerequisite before claiming numerical execution. F-160 does
not add equation lowering or rewrite the historical F-042 artifacts. Its
waveform, event, function, noise and transfer fixtures independently exercise
existing executable target behavior.

## Reproduction entry points

Run from a clean, published candidate with the unchanged pinned tools. Keep the
baseline checkout and evidence directory outside the candidate source tree.

```sh
./nodal core scala
python3 tests/compiler/fixtures/increment160/run_predecessors.py \
  --out "$RUNNER_TEMP/nodal-f160-predecessors"
python3 tests/compiler/fixtures/increment160/run_parity.py \
  --candidate-root "$PWD" \
  --baseline-root "$RUNNER_TEMP/nodal-f160-baseline" \
  --out "$RUNNER_TEMP/nodal-f160-parity" \
  --nodalc /absolute/path/to/nodalc \
  --translate /absolute/path/to/nodal-translate \
  --native-build-receipt /absolute/path/to/native-build.json
```

Core CI creates the fresh native receipt only after its actual native build
succeeds. The receipt pins that checkout's commit/tree, unchanged compiler
subtree, lock file and executable hashes. Local artifact reuse has a separate
`--native-artifact-receipt` mode: it independently verifies the original source
archive/tree, every native source file, executable and library, and records the
original CI identity. That mode also requires `--native-artifact-zip` pointing
to the authenticated original artifact, so receipt members are bound to the
retained ZIP bytes. It provides fresh candidate-input execution using an
unchanged compiler, without claiming a new native rebuild or candidate CI run.
The experiment retains input identities before execution, all command outputs,
raw samples, per-artifact hashes and any incomplete or failed result. Never
reuse an existing results directory or replace differing expected output.
