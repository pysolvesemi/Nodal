# Nodal Analog Procedural Assignment Design Gate v0.1

**Status:** Implementation contract  
**Increment:** 33  
**Predecessor:** validated Increment 32 equation/contribution semantics  
**Public compatibility base:** Nodal API v0.3

## Purpose

This gate defines component-local analog variables and ordered procedural `:=`
assignment without weakening the source-semantic separation established by
Increment 32.

## Binding semantic categories

The compiler must retain four different meanings:

| Source form | Meaning |
| --- | --- |
| `left === right` | unordered simultaneous equation |
| `target <+ value` | additive potential or flow contribution |
| conservative connection | compiler-owned topology equation |
| `variable := value` | ordered procedural variable update |

A procedural assignment must never be represented as an equation, contribution,
or conservative connection. Multiple assignments to the same variable remain
separate ordered statements; the compiler must not collapse them into a
last-writer-wins source record.

## Variables

An analog variable is a component-owned, non-topological value with:

- a stable authored identity;
- scalar kind `integer`, `real`, or `boolean` where permitted by the operation;
- a canonical physical dimension;
- a lexical declaration scope;
- an optional initializer;
- declaration source provenance.

Variables are not terminals, nodes, branches, parameters, nets, solver unknowns,
or contribution targets. A declaration is visible only in its lexical scope and
nested child scopes belonging to the same component.

## Procedural regions and scopes

Assignments are legal only inside an explicit procedural analog region. Nested
lexical scopes preserve authored order and visibility. A variable declared in a
child scope cannot be referenced after that scope exits. Cross-component access
is always illegal, even when a spelling happens to match.

Increment 33 records structured statement order but does not implement dynamic
conditionals, cases, or loops; those remain owned by Increment 34.

## Initialization and read-before-write

A variable is definitely initialized when either:

1. its declaration has a legal initializer; or
2. an earlier statement on the same straight-line procedural path assigns it.

Reading a variable before either condition holds is diagnosed. Increment 33
performs straight-line dominance only. Branch-sensitive definite-assignment
analysis is deferred until Increment 34 introduces analog control flow.

An initializer and every assigned value must be type-compatible and have the
same physical dimension as the variable. Integer-to-real promotion is legal;
real-to-integer narrowing is not implicit.

## Assignment metadata

Every retained assignment carries:

- a stable statement identity;
- the target variable identity;
- the authored value expression identity;
- a monotonically increasing authored order within its procedural region;
- lexical scope and component owner;
- optional Boolean guard metadata;
- analysis applicability;
- source file, line, and column when available.

Guards must be dimensionless Boolean expressions. Analysis applicability must be
non-empty and use recognized analysis names.

## Determinism

Snapshot order is declaration order for variables and authored statement order
for assignments. Repeated inspection of the same source must be byte-identical.
No target backend may reorder assignments during this increment.

## Stable diagnostics

Increment 33 reserves `NODAL-ANALOG-033-*` for at least:

- `001` empty variable identity;
- `002` duplicate variable identity;
- `003` declaration outside a procedural region;
- `004` initializer type mismatch;
- `005` initializer dimension mismatch;
- `006` empty statement identity;
- `007` duplicate statement identity;
- `008` assignment outside a procedural region;
- `009` cross-component variable access;
- `010` out-of-scope variable access;
- `011` read before initialization or earlier write;
- `012` assignment type mismatch;
- `013` assignment dimension mismatch;
- `014` non-Boolean assignment guard;
- `015` invalid analysis applicability;
- `016` invalid lexical scope identity;
- `017` unknown variable identity;
- `018` nested procedural region;
- `019` unsupported variable scalar kind.

Diagnostics include the relevant stable variable or statement path whenever one
is available.

## Retention and lowering boundary

Increment 33 must retain variables and assignments in compiler-owned snapshots,
Scala-to-MLIR source documents, reproducibility evidence, and native semantic
witnesses. Its lowering result is an ordered procedural semantic inventory, not
executable solver code or emitted HDL.

## Deliberately deferred

The following are not enabled by this gate:

- analog conditionals, cases, loops, break, or continue;
- branch-sensitive definite-assignment analysis;
- topology expansion or residual/DAE construction;
- solver-state allocation or solver execution;
- analysis scheduling;
- target legalization;
- Verilog-A or Verilog-AMS procedural emission.

## Acceptance

The increment is implementation-complete only when public construction paths,
Scala and native recorders, source retention, stable diagnostics, positive and
negative fixtures, mutation checks, formatting, Core CI, and every inherited
workflow pass on one exact head. Roadmap closure and immutable evidence are
recorded in a separate pull request.
