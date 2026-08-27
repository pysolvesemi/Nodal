# Nodal minimal analog expression and contribution IR design gate v1.0

**Revision:** v1.0
**Status:** Approved
**Scope:** compiler-ir
**Public API:** unchanged at 0.3
**Approved authority:** standing Nodal increment implementation and merge authorization

## Decision

Increment 24 introduces the smallest typed continuous-time equation vocabulary
needed to represent an RC constitutive equation without assigning target HDL
spelling to source semantics.

The private Nodal dialect gains one `nodal.analog` region, finite f64 real
literals, typed references to enclosing real parameters, ordered real
add/subtract/multiply/divide operations, `nodal.analog_ddt`, and explicit
potential/flow `nodal.contribute` operations on conservative branches. Existing
terminal, node, branch, and potential/flow access operations remain the
connectivity foundation.

## Binding rules

- Analog expression operations occur directly inside one `nodal.analog` body.
- Real literals are finite f64 values.
- Parameter references resolve to direct enclosing-module `nodal.parameter`
  symbols with type f64.
- Arithmetic and `ddt` are typed f64 expression nodes; they are not folded or
  reordered by this increment.
- A contribution targets one typed conservative branch and is either
  `potential` or `flow`.
- The semantic pipeline classifies every new operation as analog, and digital
  target profiles reject it.
- Stable `NODAL-ANALOG-*` diagnostics retain Increment 22 source mapping.

## Explicitly deferred

- Scala source lowering for these operations;
- executable Verilog-A/Verilog-AMS body emission;
- complete units, natures, disciplines, node aliases, access-function APIs,
  procedural analog statements, events, analyses, noise, and solver semantics;
- canonicalization, equation-system construction, and optimization.

Increment 25 owns the first Scala-to-Verilog-A RC vertical slice. Later phase-2
increments expand each analog semantic category without changing this initial
typed boundary.
