# Increment 42 - Analog hierarchy and parameterized instances

**Status:** Implementation in progress; not accepted or merged.

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
Targeted CI, full CI, review, merge and accepted-evidence closure remain pending.
No targeted run or hourly continuation has been launched for this checkpoint.

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
