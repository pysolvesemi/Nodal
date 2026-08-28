# Nodal Parameter, Constant, Range, and Unit Design Gate v1.0

**Status:** Approved  
**Scope:** compiler-ir and minimal native Verilog-A/Verilog-AMS rendering  
**Public API:** unchanged at 0.3  
**Roadmap owner:** Increment 29

## Decision

Increment 29 introduces a target-neutral compile-time parameter model while
preserving all legacy Increment 19 parameter IR. New declarations explicitly
classify parameters as `ordinary` or `structural` and select one supported
scalar kind: `real`, `integer`, or `boolean`. String, aggregate, and
analysis-dependent parameter kinds remain fail-closed for later gates.

A top-level `nodal.unit` declaration owns a canonical dimension, positive
finite scale, display symbol, and an allowed native Verilog scale suffix.
Affine offsets are deliberately excluded. Unit-aware literals retain both an
authored magnitude and an exact validated spelling; semantic folding converts
through the declared scale while native HDL rendering reuses the spelling
rather than reconstructing a decimal approximation.

Compile-time expressions form a typed SSA DAG using `nodal.const_literal`,
`nodal.const_parameter_ref`, and `nodal.const_expr`. The initial operator set is
`add`, `sub`, `mul`, `div`, `mod`, `neg`, and `not`. Folding is deterministic,
cycle-checked, finite, and scalar. Addition/subtraction require equal physical
dimensions. Multiplication/division permit at most one dimensioned operand;
composite-unit algebra and general analog promotion remain owned by Increment
30.

`nodal.parameter_value` attaches the lossless expression to the canonical
`default_value`. The folded expression must reproduce that canonical value.
`nodal.parameter_constraint` supports native range and exclusion contracts,
and the same contract is applied to defaults, legacy instance bindings, and
lossless `nodal.parameter_override` operations.

Structural parameters must be symbolic integer or Boolean values, have a
bounded range, and own exactly one `static_generate` envelope listing any of
`topology`, `component_count`, `equation_count`, `shape`, or `rank`. Ordinary
parameters may only use an empty `fixed_topology` envelope. Symbolic generate
bounds that explicitly reference a parameter require the structural
classification. Unsupported parameter-envelope structural changes are
rejected before target lowering.

`nodal.dynamic_value` is an explicit runtime classification marker. Dynamic
values are forbidden from constant expressions, defaults, constraints, and
overrides. No frontend or optimizer may infer compile-time status from a
runtime value.

## Native rendering contract

The existing analog backend renders real parameters as `parameter real` and
integer/Boolean parameters as `parameter integer`. It emits retained constant
spellings, parenthesized constant expressions, native `from` ranges, and
`exclude` values. A declared unit is retained as a deterministic trailing
comment. Hierarchical instance emission and native override syntax remain
outside the current backend vertical slice, but override legality is fully
verified in target-neutral IR.

## Compatibility and deferrals

Legacy `fixed`/`symbolic` parameters without the new optional attributes remain
valid and infer their scalar kind from the existing type. The approved public
API v0.3 is unchanged; Scala syntax is deferred. Analog expression promotion,
composite dimensions, runtime quantity typing, equation typing, hierarchy
lowering, and general backend support remain fail-closed in their owning
increments.
