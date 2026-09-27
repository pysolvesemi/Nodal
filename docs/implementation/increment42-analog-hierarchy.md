# Increment 42 - Analog hierarchy and parameterized instances

**Status:** Implementation in progress; not accepted or merged.

## Current production boundary

Exact source `7bee1fa9c6035f3df6e933de04f73e89b03e3188` is the targeted-green
constructor/frontend checkpoint. Core run
[36292225388](https://github.com/pysolvesemi/Nodal/actions/runs/36292225388)
passed contracts, all 212 Scala tests, the production separate-compilation and
raw-factory sentinels, all 137 native CTests twice, all 12 bridge tests, 39
clang-tidy translation units and required aggregation. Its fixed-replication
matrix covers empty, singleton and boundary ranges, `Vector.tabulate`,
`List.tabulate`, deterministic instance/register identities and invalid child
reuse. Documentation-only child `d1680b495ec6ca720756ff477dc5a38ad76e1d5d`
reconciles that evidence by marking F-042.B.1 complete. These results qualify
B.1 on their exact source; they do not qualify downstream B.2 or Increment 42
acceptance.

The next bridge/native checkpoint carries the construction snapshot's exact
topology owner, root actual bindings, direct parent-parameter overrides and
immediate-child conservative terminals into typed MLIR. Direct symbolic
overrides use the existing `nodal.const_parameter_ref` and
`nodal.parameter_override` operations rather than a string or a symbol-valued
instance attribute. `nodal.instance_terminal` identifies one boundary terminal
of one direct child instance; the native hierarchy verifier resolves its exact
instance/module/port, type and direction. Missing instances, non-boundary or
missing ports, type changes and direction changes are source-located failures.
The bridge emits explicit `nodal.connect` operations for parent/child topology
and fails unavailable or incompatible endpoints instead of omitting them.

The proposed hierarchy matrix grows from the historical 559 cases below to 570 cases:
six typed child-terminal cases, three root-binding cases and two direct symbolic
override cases are added without altering the original graph, diagnostic or
bounded-stack controls. Local Scala
3.8.4/JDK 25 compilation of the complete API/frontend/bridge source and an
instrumented public hierarchy runtime witness pass. The native translation
units, pinned format/lint and the 570-case matrix still require exact-head CI;
no local review or prior-head result qualifies this successor.

This is a published bounded B.2 checkpoint, not completed B.2. Static override-expression
DAG serialization beyond a direct parent parameter, native-to-Verilog-A
hierarchy lowering, combined equation/event/function witnesses, scale review,
full qualification and closure remain required. No local review or prior-head
result qualifies this candidate.

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
