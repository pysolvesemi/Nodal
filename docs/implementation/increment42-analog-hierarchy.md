# Increment 42 - Analog hierarchy and parameterized instances

**Status:** Validated

The accepted compiler-structural Verilog-A profile, exact qualification,
verified merge, intentionally skipped post-merge CI, public Scala/MLIR/target
witnesses and capability limits are recorded in the
[accepted-evidence closure](increment42-evidence-closure.md). Historical
checkpoints below are retained as implementation history and are not newer
acceptance boundaries.

## Current production boundary

Exact source `7ab7ec09f70e7928118857231da6f8a8c0c99fe4`, tree
`942f7f46589b715f5d4a12f8887d0f0fe041378b`, is the current targeted-green
hierarchy checkpoint. Core run
[36332604210](https://github.com/pysolvesemi/Nodal/actions/runs/36332604210)
passed contracts, 219 Scala tests, all 370 compiler Python tests, the constructor
probe, 137 native CTests twice, 19 configured bridge tests, formatting/lint and
required aggregation. Exact-head Increment 23, 25, 28, 29 and 41 runs
[36332616923](https://github.com/pysolvesemi/Nodal/actions/runs/36332616923),
[36332627314](https://github.com/pysolvesemi/Nodal/actions/runs/36332627314),
[36332644101](https://github.com/pysolvesemi/Nodal/actions/runs/36332644101),
[36332654703](https://github.com/pysolvesemi/Nodal/actions/runs/36332654703) and
[36332684933](https://github.com/pysolvesemi/Nodal/actions/runs/36332684933)
also passed.

The first exact-head Increment 37 run
[36332674015](https://github.com/pysolvesemi/Nodal/actions/runs/36332674015)
failed only on an external Maven HTTP 429. The bounded one-request dispatcher
retry [36334138396](https://github.com/pysolvesemi/Nodal/actions/runs/36334138396)
then passed the complete public event/source-to-native/Verilog-A matrix on the
same source and tree. Artifact `10937037949` retains seven predecessor-event
MLIR/Verilog-A files; its ZIP/API/upload SHA256 is
`11de528d5243783ba08c95f5df308b3515779d53deeeb7bcf57416621f24cb17`,
and all internal hashes match the job log. The exact qualification is recorded
in [PR #134 comment 5857885496](https://github.com/pysolvesemi/Nodal/pull/134#issuecomment-5857885496).

Earlier exact source `7bee1fa9c6035f3df6e933de04f73e89b03e3188` and documentation
child `d1680b495ec6ca720756ff477dc5a38ad76e1d5d` retain the targeted
evidence that completed F-042.B.1. No receipt by itself qualifies final
Increment 42 acceptance.

The published B.2 work carries the construction snapshot's exact topology
owner, typed root actuals, direct parent-parameter overrides and immediate-child
conservative terminals into typed MLIR. Direct symbolic overrides use the
existing `nodal.const_parameter_ref`, `nodal.const_literal`, `nodal.const_expr`
and `nodal.parameter_override` operations. `nodal.instance_terminal` identifies
one boundary terminal of one direct child instance; the native hierarchy
verifier resolves its exact instance/module/port, type and direction. Explicit
`nodal.connect` operations retain parent/child topology. Missing or foreign
owners, non-boundary ports, type/direction changes, unsupported expressions and
cycles fail with source locations. The retained 570-case hierarchy matrix
preserves every historical graph, diagnostic and bounded-stack control while
adding typed terminal, root-binding and symbolic-override cases.

## Current Verilog-A successor candidate

The current successor connects those canonical operations to the
existing production backend. It resolves conservative connection components,
requires each child boundary port to have exactly one parent-local named net,
and rejects incomplete, multiply anchored, parent-only or domain-bound
connectivity under this scalar profile. It emits one reusable module definition
and deterministic named instances with sorted port and parameter bindings. Both
literal and parent-owned symbolic overrides retain their target spelling; no
clone-per-value specialization or second hierarchy registry is introduced.

The independent backend reparser now recognizes only the emitted named scalar
instance grammar and verifies module, port, net, parameter, fixed-parameter and
dependency scope across the complete output. Its constant grammar is extended
only for the already-supported comparison and Boolean override operators.
Malformed ports, foreign nets, unknown/fixed parameters, duplicate instance
names and recursive output remain failures before target publication.

Root actuals remain separate from authored parameter defaults in canonical
MLIR. Because a standalone Verilog-A library has no parent instance in which to
encode a root override, this compiler profile accepts an explicit root binding
only when it equals the authored default. A non-default root actual fails with
`NODAL-BACKEND-HIERARCHY-010` and requires a later external top-binding adapter;
it is never silently discarded, substituted into the declaration default or
represented by an invented parent module.

The final external-boundary repair approves floating child declarations only
when an actual topology edge owned by another module binds that terminal. Root,
local and unbound declarations remain unapproved. Its regression requires
exactly two approvals for the two immediate child ports, while the configured
public-source witness emits one reusable child definition and one named instance
with a symbolic parent parameter override. The non-default root-actual negative
remains mandatory and retains `NODAL-BACKEND-HIERARCHY-010`.

## Current predecessor-combination candidate

Three separately compiled public-only sources now combine the same direct child
boundary and symbolic override with, respectively, a parent-owned analog
equation, an `initial_step` event-controlled assignment plus continuous
contribution, and a module-local pure analog function call. The focused bridge
regression lowers each source twice and requires identical normalized MLIR,
exact child terminal/connect counts and its predecessor operation inventory.
The configured path compiles all three through `nodalc` and `nodal-translate`,
retains the normalized predecessor witness, reparses the complete target, and
requires one reusable child definition and one symbolic named instance.

A local Scala 3.8.4 compile with the production constructor plugin passed for
the public fixture. An executable bridge smoke check produced deterministic
document hashes `eb09715fc24d2049c8f2e145c554efa0fa1a22fcf5d8b040ecb964d01acc21eb`
(equation), `84b353dc6d37815d672445a3acab1dc218f8d28aded2bf91839fb195313e1203`
(event) and `7bc6fe3e7854eb54b376ad52580dfa268a692de2b4e100e785ca590ebfb1537b`
(function). Those local checks do not qualify the pending native target,
formatter or exact successor head. Scale review, final applicable CI and closure
also remain required.

## Repeated-definition and bounded-depth candidate

The published candidate makes the bridge, rather than the target printer, the
owner of reusable definition selection. It computes module depth iteratively
from the already captured hierarchy, then compares exact normalized definition
text from leaves upward. Normalization removes only the candidate module's
absolute semantic prefix and definition symbol; constructed-class metadata, declaration
types/defaults, source locations, analog bodies, local names, child structure
and canonical child targets remain part of the comparison. The root definition
is never folded into a child. This allows only actually identical constructed
definitions to share the lexicographically first representative and does not
introduce clone-per-actual specialization or a second mutable hierarchy model.

Every parent instance retains its own handle, name, parameter actual, terminal,
connection and source path while targeting the representative definition. The
complete declaration/name/topology/source inventories remain instance-specific.
The new public witness covers four compatible leaf instances with two symbolic
and two distinct literal actuals, plus two compatible nested branches and their
compatible leaves. The expected normalized MLIR has two definitions for the
flat case and three for the nested case; target checks require four instances of
the first leaf definition and two instances of the first branch definition.

The added canonicalization performs one iterative `O(V + E)` depth pass, sorts
paths within depth groups, and hashes exact rendered structure strings whose
total size is `S`; its additional expected work is `O(V log V + E + S)` with a
depth-independent call stack. Local Scala 3.8.4 compilation with `-Ycheck:all`
passed for the bridge, fixture and regression source. Repeated execution produced
deterministic document hashes
`afdb052bc2f107957bfc87840be133e6b29e21b160bcff7bc5acc7133f507e71`
(four leaves) and
`7bb83d1b7461c2be413b624356218793fb247ed56345e00ae4c29a2c6961acd8`
(nested branches).

Exact source `62561b3bc704d9321a7ae38ca22e79406698c6ef` was targeted
through Core and Increments 23, 25, 28, 29, 37 and 41. Contracts and Scala
passed, and every native path completed all 137 CTests twice before the same
configured bridge assertion failed. The backend intentionally renders finite
real bindings with `std::to_chars(..., std::chars_format::general)`, so the
literal actuals are canonically emitted as `.gain(3)` and `.gain(5)`.
The new target witness had incorrectly required the source spellings
`.gain(3.0)` and `.gain(5.0)`. The focused successor changes only those two
test expectations to the established target spelling; production bridge,
canonical MLIR and backend behavior are unchanged. These results do not yet
qualify the repaired exact head.

Exact source `7f874f91916b0a282fb092b7de8a171fde509838` repeated the same
seven-workflow boundary. Core contracts and Scala succeeded; every native path
again passed all 137 CTests twice and then failed only at the remaining nested
target assertion, which required `.branchGain(6.0)`. The same canonical target
formatter emits `.branchGain(6)`, so the focused successor updates that final
configured-target spelling expectation. Production bridge, fixture, MLIR and
backend bytes remain unchanged. The `7f874f9` runs are terminal diagnosis and
do not qualify the successor.

## Checklist reconciliation — 2026-09-27

The authoritative roadmap now records F-042.A, B.2, B.3, C, D.1, E and F
complete on the exact targeted-green compiler head. The evidence demonstrates
one canonical construction/IR/backend path; typed root actuals, symbolic child
overrides and direct child terminals; reusable named Verilog-A definitions;
source-located negatives and predecessor combinations; retained public
Scala/normalized-MLIR/generated-Verilog-A witnesses; exact structural reuse; and
bounded-depth/repeated/nested determinism.

F-042.D.2 is complete in the [later-tool qualification handoff](increment42-later-tool-handoff.md).
That record selects the 48 compile/load and 49/52 numerical cases, pins analyses,
independent references, units and tolerances, capability-gates event execution,
and prevents structure-only witnesses from becoming numerical claims. Strict
internal target reparse remains distinct from independent OpenVAF execution.
At this 2026-09-27 checkpoint, F-042.G and the Increment 42 parent remained
open pending final full applicable CI, review, verified integration, merge and
separate accepted-evidence closure. Those gates subsequently completed in the
accepted-evidence closure linked above.

## Published checkpoint 1

Base: `c24207e47e012d8704da5d6d8e650914b2804f28` on `dev`.
Branch: `increment/42-analog-hierarchy`.

This checkpoint implements two dependency-free C++17 support utilities and
registers their tests with the ordinary native CTest build. It does not yet
connect the public Scala construction, native hierarchy operations, or
Verilog-A emitter to these utilities. No end-to-end hierarchy capability is
claimed by this checkpoint.

- `HierarchyOrder.h`: deterministic, iterative dependency ordering with shared
  children, unknown-target diagnostics and cycle detection. Failure discards
  the partial ordering rather than returning a usable prefix.
- `AnalogHierarchySyntax.h`: a restricted named scalar-instance grammar and
  cross-definition binding checks. It retains symbolic parameter references,
  rejects missing/duplicate/foreign ports, unknown or fixed parameter overrides,
  instance-name collisions and recursive module dependencies. It is not a
  general or independent Verilog-A parser.

## Executed local checks

`HierarchyOrderTest.cpp` passed 9 checks, including 100,000-deep traversal,
repeated wide instances, sharing, unknown targets, cycles and repeat stability.
`AnalogHierarchySyntaxTest.cpp` passed 49 checks, including malformed grammar,
scope and binding mutations, recursive dependencies and a 100,000-parenthesis
constant expression. Both suites passed with GCC and with Clang using Address
Sanitizer and Undefined Behavior Sanitizer. These are 58 unique cases executed
under two local compiler configurations, not 116 independent requirements.

Reproduce each standalone test from the repository root:

```sh
c++ -std=c++17 -Wall -Wextra -Werror -pedantic -O2 \
  -Icore/compiler/include core/compiler/test/Unit/HierarchyOrderTest.cpp \
  -o /tmp/nodal-hierarchy-order-test
/tmp/nodal-hierarchy-order-test
c++ -std=c++17 -Wall -Wextra -Werror -pedantic -O2 \
  -Icore/compiler/include core/compiler/test/Unit/AnalogHierarchySyntaxTest.cpp \
  -o /tmp/nodal-hierarchy-syntax-test
/tmp/nodal-hierarchy-syntax-test
```

Local helper tests do not qualify a native MLIR build, Scala source, generated
HDL, independent OpenVAF compilation, numerical simulation or the increment.
The initial targeting found a pinned formatting failure, repaired in
`17394368c3ab57bcf88e7b551407690754a4b166`. Targeted Core CI
[35825288843](https://github.com/pysolvesemi/Nodal/actions/runs/35825288843)
and Increment 41
[35825298112](https://github.com/pysolvesemi/Nodal/actions/runs/35825298112)
passed on that exact head/tree `784e8b13c64337cbaeb89b771c0da61370cf4573`.
All five applicable jobs passed; the dispatch-only aggregate push-status step
was legitimately inapplicable, not executed qualification.

The original Increment 41 artifact `10734538485` has SHA256
`06a0578d5a9967d4efaf5159df074a9821ace810fcaaf0547b40f3e98138a04b`.
Its archived source independently reconstructs the exact candidate tree.
Retained logs record 136 CTest cases, 13 closure Python tests and 61
function-source/native cases. These overlapping predecessor/helper results do
not qualify the subsequent production repair. Hourly continuation is enabled;
full CI, review, merge and accepted-evidence closure remain pending.

## Native hierarchy verifier integration candidate

The production `nodal-verify-hierarchy` stage now uses the existing iterative
`orderHierarchy` kernel instead of a recursive `std::function` traversal.
Definition and instance names select deterministic diagnostics. Every instance
edge, including repeated children and disconnected definitions, is checked.
Unknown references retain `NODAL-VERIFY-HIERARCHY-004`; cycles retain
`NODAL-VERIFY-HIERARCHY-005` and identify the closing instance's real source
location rather than an arbitrary module declaration. The existing closure
guard, parameter/domain verification, pipeline transaction and other semantic
stages remain required and unchanged.

This verifier does not reorder or specialize the input IR, fold overrides,
merge state or emit hierarchical Verilog-A. Its ordering work is
`O(V log V + sum(d log d) + E)` for definitions and instance names; the graph
traversal itself is `O(V + E)` with heap storage and depth-independent call
stack. The ordering result is used for verification, not as a claim of backend
module reuse or complete source hierarchy support.

The ordinary native CTest build registers
`nodal.native.hierarchy-integration`, invoking the actual `nodalc` through
`tests/compiler/fixtures/increment42/run_hierarchy_matrix.py`. Its 559 cases
cover all 512 directed three-definition graphs (self-edges included), checked
against an independent Kahn reference; definition/instance-order permutations;
source locations; unknown references; disconnected cycles; repeated children;
parse/print stability; all three gate profiles; seeded wider DAGs; and
50,000-definition depth cases. A parse-only control and both acyclic/cyclic
hierarchy-pass cases use an explicit 1 MiB compiler-child stack. A crash,
timeout, wrong diagnostic, missing output or partial failed output is a test
failure. The script retains inputs, outputs, diagnostics and result hashes under
`out/native/release/increment42-hierarchy-evidence`; the Increment 41 workflow
retains that directory when produced without changing its predecessor tests.

Executed baseline evidence: the retained compiler from exact head `17394368`
passed 547/559 of these new cases. Twelve cases exposed closing-instance and
ordering defects or stack crashes. The 50,000-definition parse-only control
passed under the same 1 MiB limit; the hierarchy verifier crashed on the
acyclic and back-edge cases. With the normal host stack, the original
50,000-definition acyclic probe passed. This is an explicitly bounded-stack
regression, not a claim that every ordinary 50,000-definition run crashes.

Local checks on the new source: 368 compiler Python tests passed, including
12 new harness controls; predecessor contract checkers 19-23 and 41 passed.
These are not native compilation evidence. The modified native translation
unit and the 559-case matrix still require exact-head remote qualification
with the pinned SDK and format/lint tools. No current-head success is inferred
from the earlier helper artifact.

Reproduce with the candidate's built compiler, not the retained baseline:

```sh
python3 tests/compiler/fixtures/increment42/run_hierarchy_matrix.py \
  --nodalc out/native/release/bin/nodalc \
  --work-dir out/native/release/increment42-hierarchy-evidence
```

## Accepted implementation and historical acceptance notes

The compiler implementation scope through F-042.A-G is accepted on
`fa921f95cc6aad9b1cb86e33edc7f09e53a7f4e4`, tree
`e9b74f0a8963ecec76b7e68406e104beda08bf04`, and merged as
`98085f79aeaef3a5c7eeabfda462afa7299cbaf7`. Do not reopen that scope without
a concrete defect. Preserve the qualified public Scala, normalized MLIR,
generated Verilog-A, negative mutations, source maps, scale and determinism
evidence.

The [F-042.D.2 handoff](increment42-later-tool-handoff.md) maps concrete
hierarchy witnesses into the later Increment 48 OpenVAF compile and Increment
49/52 numerical owners without claiming their execution. Those later-tool
obligations remain open under their own increments; they are not relabeled as
Increment 42 execution or made reverse prerequisites for this accepted compiler
profile.
