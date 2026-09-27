# Increment 42 - Analog hierarchy and parameterized instances

**Status:** Implementation in progress; not accepted or merged.

## Current production boundary

Exact source `f325a5d9f22226ce3ab532c9cf56231bf1eb38ff` is the current
targeted-green hierarchy checkpoint. Core run
[36318100915](https://github.com/pysolvesemi/Nodal/actions/runs/36318100915)
passed contracts, 217 Scala tests, 137 native CTests twice, 17 configured bridge
tests, formatting/lint and required aggregation. Exact-head Increment 23, 25,
28 and 29 runs
[36318114025](https://github.com/pysolvesemi/Nodal/actions/runs/36318114025),
[36318125104](https://github.com/pysolvesemi/Nodal/actions/runs/36318125104),
[36318138563](https://github.com/pysolvesemi/Nodal/actions/runs/36318138563) and
[36318152556](https://github.com/pysolvesemi/Nodal/actions/runs/36318152556)
also passed their named predecessor proofs plus the shared native and bridge
paths. The exact qualification is recorded in PR #134 comment `5856180094`.
Earlier exact source `7bee1fa9c6035f3df6e933de04f73e89b03e3188`
and documentation child `d1680b495ec6ca720756ff477dc5a38ad76e1d5d`
remain the targeted evidence that completed F-042.B.1. No receipt by itself
qualifies Increment 42 acceptance.

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

## Remaining implementation and acceptance

All original F-042 obligations remain in scope. Continue with typed public
named-port construction, parent-owned symbolic override DAGs, legal fixed
instance arrays, native ownership/type/unit/binding verification, recursive
hierarchy rejection, parameter-preserving Verilog-A emission and safe duplicate
definition elimination. Retain actual public Scala, normalized MLIR and generated
Verilog-A witnesses, predecessor interactions, negative mutations, source maps,
scale and determinism evidence. Complete targeted-first and full qualification,
review and verified integration before any accepted-evidence closure.

The authoritative Foundation parent and children remain unchecked. Independent
OpenVAF and numerical execution belong to their later qualification owners;
this compiler checkpoint does not count unavailable execution as passed or N/A.
