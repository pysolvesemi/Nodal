# ADR 0018: Inline safe expressions and give required state semantic names

- **Status:** Accepted
- **Date:** 2026-08-22
- **Scope:** Expression DAGs, temporary wires, Verilog rendering, source names, anonymous registers, observability, source maps, deterministic naming, debug profiles, and optimization constraints

## Context

Software-based HDLs often lower each expression node to a generated wire even when the target language can represent the expression directly. A source expression such as:

```scala
a := b * c * d * e
```

can become:

```verilog
assign _zz1 = b * c;
assign _zz2 = _zz1 * d;
assign _zz3 = _zz2 * e;
assign a = _zz3;
```

Those anonymous wires can reduce readability, create noisy waveforms, destabilize diffs, and obscure the connection between the source expression and generated HDL.

Blind inlining is also unsafe. Finite-width result types, signedness, four-state behavior, explicit casts, common subexpressions, source observability, target expression limits, and tool compatibility may require a value to be materialized. Sequential state always requires an HDL object name.

Nodal therefore needs a typed expression DAG, an explicit materialization policy, deterministic semantic names, and source mapping that does not depend on every IR node becoming a wire.

## Decision

Nodal keeps pure combinational expressions as typed DAG/SSA values and **inlines compiler-generated single-use expressions when the selected backend can preserve exact semantics**. A temporary is materialized only for a declared reason and receives a deterministic semantic name.

The binding rule is:

> **Do not name an expression merely because the compiler has a node; name only storage, sharing, observability, legality, or an explicit user boundary.**

Exact option names are compile-tested in Increment 13 and frozen in Increment 15.

## Expression tree preservation

Nodal preserves the exact typed operation tree from source/IR. Inlining does not reassociate, reorder, duplicate, narrow, widen, change signedness, or change four-state semantics.

For the source tree equivalent to:

```scala
a := (((b * c) * d) * e)
```

a safe target may emit:

```verilog
assign a = (((b * c) * d) * e);
```

If Verilog's contextual width or signedness rules cannot directly represent the Nodal result, the emitter uses explicit sized operands, casts, concatenations, or a typed temporary. The policy never chooses shorter text over correctness.

Line wrapping and parentheses are formatting decisions; they do not require intermediate wires.

## Materialization policy

The candidate policies are:

```text
TemporaryPolicy.InlineSafe
TemporaryPolicy.Readable
TemporaryPolicy.Debug
TemporaryPolicy.ToolFriendly
```

`InlineSafe` is the preferred default:

- inline pure, single-use, non-observable expressions;
- preserve the original operation tree;
- materialize shared expressions when duplication could change area or tool behavior;
- materialize where target typing, signed element extraction, procedural boundaries, or backend legality requires it;
- keep user-visible source names when explicitly requested;
- apply a deterministic maximum expression complexity/line policy without inventing traversal-number names.

`Readable` may preserve user-named intermediate values and important semantic boundaries.

`Debug` materializes source-level observation anchors and selected IR nodes for waveforms, differential debugging, or formal correlation.

`ToolFriendly` applies a locked target/tool capability profile for expression depth, function use, part-selects, and known parser/synthesis limits.

A strict candidate may reject an avoidable anonymous combinational temporary rather than silently emit one. Mandatory temporaries remain legal and are explained in the materialization report.

## Reasons to materialize

A temporary may be required for:

- sequential state or a latch primitive;
- a value with more than one non-trivial use where duplication is not approved;
- explicit user observability, `keep`, debug, formal, or trace intent;
- a user-named expression preserved by the selected profile;
- target-language typing or signedness that cannot be carried by the nested expression;
- a flattened `Vec[SInt]` element view required by ADR 0017;
- procedural versus continuous-assignment boundaries;
- function/task argument or return restrictions;
- target expression depth or parser limits;
- source-map granularity requested by a debug profile;
- a pass-defined optimization or verification anchor;
- an external black-box or timing boundary;
- a common-subexpression decision that avoids unintended duplicated hardware.

Every generated temporary carries a reason code in the report.

## Common subexpressions

Nodal does not duplicate a shared expensive expression merely to avoid a wire. The compiler uses operation cost/effect metadata and the selected policy:

- pure trivial expressions may be duplicated when the profile permits;
- non-trivial shared expressions are normally materialized once;
- effectful operations are never duplicated;
- expressions crossing pipeline, memory, external, analog, event, or domain boundaries are not duplicated or moved;
- common-subexpression elimination and rematerialization are explicit verified passes with source-map and resource effects.

## User names versus compiler names

A Scala source declaration such as:

```scala
val product = b * c
```

has a user-origin name that can be preserved under readable/debug profiles. The compiler does not promise that every Scala `val` becomes a physical HDL wire in the minimal profile.

An explicit naming/observability candidate is reserved:

```scala
val product = name("product")(b * c)
```

or an equally concise `.named("product")` form. Increment 13 compares the exact spelling.

Names are semantic metadata, not an instruction to alter logic or prevent all optimization. Separate `keep`/observability controls define optimization barriers.

## Registers and other required objects

Verilog-family declarations require identifiers for registers, memories, ports, functions, generated scopes, and materialized nets. There is no truly unnamed emitted register.

Nodal derives names in this priority order:

1. explicit user name;
2. captured Scala declaration/member name;
3. owning sink or output plus semantic role;
4. protocol, pipeline, FSM, memory, CDC/RDC, or generated-structure role;
5. nearest named ancestor plus operation role and stable source origin;
6. source-location/content digest fallback.

Examples of preferred generated names are:

```text
result_reg
result_delay_1
pixel_pipe_stage_2_data
controller_state
stream_valid_reg
fifo_write_ptr_gray
out_signed_element_view
```

Traversal-number-only names such as `_zz1` are not normal output. Collision suffixes use deterministic role/index or stable digest information, not discovery order.

## Sink-affinity naming

Anonymous state created directly in an assignment can inherit the sink's role:

```scala
out := RegNext(value)
```

can become a semantic name such as:

```text
out_reg
```

Multiple stages use deterministic names such as `out_delay_1`, `out_delay_2`, or pipeline-owned stage names. If one state value feeds several unrelated sinks, the source role and stable origin determine its name instead of arbitrarily selecting one consumer.

## Source maps for inlined expressions

Inlining does not discard source provenance. The backend records generated text spans and target-IR nodes for:

- operators;
- operands;
- casts and extensions;
- indexing/part-select formulas;
- conditional arms;
- flattened aggregate elements;
- materialized objects.

Diagnostics can therefore map a Verilator, Icarus, Yosys, OpenVAF, or simulator message back to the source expression even when no temporary wire exists.

## Determinism

Names and materialization decisions must be stable across:

- valid elaboration traversal orders;
- parallel construction;
- hash-map/set iteration order;
- JVM object identity;
- independent builds in different directories;
- unrelated source additions outside the naming scope;
- plugin discovery order;
- equivalent target profile resolution.

The build manifest records the naming algorithm version, materialization policy, target/tool profile, expression-complexity thresholds, and decision hash.

## Verification

The implementation proves:

- byte-stable output and source maps across repeated builds;
- equivalent logic between inline and debug/materialized profiles;
- exact width, signedness, four-state, shift, and overflow behavior;
- no duplicated side effects or unintended hardware;
- stable common-subexpression decisions;
- readable sink-derived names for anonymous state;
- no avoidable anonymous-wire chains in golden examples;
- required signed element views for flattened arrays remain correctly typed;
- external diagnostics map to original expressions;
- `keep`/debug/formal anchors survive only where requested.

Digital profiles use simulation and Yosys/CIRCT equivalence between materialization policies. Analog/AMS profiles use target-IR identity and bounded differential evidence where expression rendering differs.

## Consequences

### Positive

- Simple expressions generate simple HDL.
- Generated waveforms and diffs contain fewer meaningless names.
- Required state receives stable, role-based identifiers.
- Debug profiles remain available without defining normal output quality.
- Source diagnostics do not depend on materializing every expression.
- Tool limitations are explicit profile inputs rather than hidden codegen heuristics.
- Optimization can reason over a typed DAG instead of text-level temporary chains.

### Costs

- The emitter needs a precise typed-expression printer.
- Source mapping must support expression spans, not only declarations.
- Verilog's width/signedness rules sometimes force explicit materialization.
- Common-subexpression and expression-depth decisions need resource/tool metadata.
- Name stability must be versioned and regression tested.

## Rejected alternatives

- **Materialize every IR node:** produces unnecessary anonymous wires and poor readability.
- **Inline every expression:** can duplicate hardware, lose observability, exceed tool limits, or change target typing.
- **Name temporaries by traversal count:** unstable under harmless compiler changes.
- **Use destination names for every shared value:** ambiguous when one value has multiple unrelated consumers.
- **Drop source maps when inlining:** makes external diagnostics unusable.
- **Use post-render text substitution to remove wires:** loses typed semantics and conflicts with ADR 0013.
- **Promise physically unnamed registers:** Verilog-family state declarations require identifiers.

## Follow-up increments

- Increment 13 compiles naming/materialization option candidates and representative expression shapes.
- Increment 15 freezes the option and naming contracts.
- Increments 16-17 implement source-name capture, origin graphs, role naming, and stable fallback names.
- Increment 19 preserves expression/state identity and materialization metadata in IR.
- Increment 21 verifies expression and naming invariants.
- Increment 26 proves deterministic names, decisions, and source maps.
- Increments 55 and 64 implement typed expression DAGs, pipeline names, and observability barriers.
- Increment 65 implements safe portable-Verilog inlining and semantic temporary names.
- Increments 66-67 prove multi-profile simulation/synthesis equivalence.
- Increment 72 applies the same policy to Verilog-AMS.
- Increments 83-86 expose preservation/effect contracts to target-HDL optimization passes.
