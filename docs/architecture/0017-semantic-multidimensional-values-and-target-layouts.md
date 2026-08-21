# ADR 0017: Use semantic multidimensional values with target-aware layouts

- **Status:** Accepted
- **Date:** 2026-08-22
- **Scope:** Parameterized multidimensional values, structural vectors, memories, indexing/layout, Verilog/SystemVerilog/Verilog-AMS ports, signed elements, hierarchy ABI, and synthesis evidence

## Context

Nodal already reserves directionless aggregates, symbolic widths and lengths, exact connections, arrays, and target generation. It does not yet define the difference between a multidimensional structural value and an addressable memory, nor how one semantic shape maps to portable Verilog and future SystemVerilog.

The target languages have materially different representations:

- portable Verilog has one packed vector range on an ordinary signal and does not provide SystemVerilog-style multidimensional packed types or portable unpacked-array module ports;
- SystemVerilog supports multidimensional packed and unpacked arrays, including fixed-size unpacked arrays on module ports;
- an unpacked array declaration does not by itself establish Nodal memory semantics, while synthesis tools may choose memory or individual-register implementations from access patterns;
- a flattened portable-Verilog carrier cannot express independent signedness for each element of `Vec[SInt]` in its declaration.

Nodal therefore needs one target-neutral shape model, explicit storage intent, deterministic flattening, and backend mappings that preserve element semantics without making target syntax part of ordinary model source.

## Decision

Nodal represents a multidimensional structural collection as a typed **`Vec`-class shaped value** whose element type and rank/dimensions are semantic. Addressable storage remains an explicit **`Mem`** contract.

The binding rule is:

> **Shape is semantic, layout is explicit evidence, and target syntax never decides whether a value is a memory.**

Exact Scala spelling is compile-tested in Increment 13 and frozen by the unified public API v0.3 gate in Increment 15.

## Preferred source shape

The preferred candidate is concise and supports symbolic dimensions:

```scala
val rows = param(default = 4.integer, range = 1 to 64)
val cols = param(default = 8.integer, range = 1 to 64)
val width = param(default = 12.integer, range = 1 to 32)

val samples = in(Vec(SInt(width), rows, cols))
val selected = samples(row, column)
```

Increment 13 compares this form with a `Vec.of(element, dimensions*)` fallback if Scala overload resolution or separate compilation makes the preferred form fragile.

A `Vec` dimension may be:

- an elaboration-time positive Scala integer;
- a symbolic positive Nodal integer parameter or constant expression;
- a legal generate index-dependent constant expression where the containing structure permits it.

Runtime signals cannot determine rank, shape, port width, or storage allocation. Zero and negative dimensions are rejected in the initial contract.

## Logical indexing and canonical flattening

Nodal defines a canonical logical index order independent of target syntax:

- index origin is zero;
- dimensions are declared outermost to innermost;
- the rightmost dimension is contiguous;
- flattening is row-major by default;
- element bit zero remains the least-significant bit of its element representation;
- nested aggregates flatten recursively in stable declaration order;
- reshape is legal only when the symbolic total element/bit count is proven equal.

For `Vec(UInt(W), R, C)`, element `(r, c)` begins at the canonical flat bit offset:

```text
((r * C) + c) * W
```

The manifest records rank, dimensions, element type, flattening order, bit offset formulas, symbolic constraints, and target representation.

## Structural `Vec` versus `Mem`

`Vec` is a structural collection of values. `Mem` is addressable storage with declared read/write ports, latency, collision, reset/initialization, and mapping semantics.

The language does not infer `Mem` merely because:

- a collection has an unpacked SystemVerilog representation;
- an index is dynamic;
- a synthesis tool could map a register array to RAM;
- the collection is large.

A backend may choose a representation that is structurally equivalent, but it must retain the semantic storage class in reports. A selected resource-mapping optimization may map structural state to memory only through an explicit effect, legality proof, interface/latency preservation, and resource report.

A `Vec` that must never become an addressable memory uses the structural storage contract. The digital synthesis evidence reports any unexpected memory inference for structural values. An explicit `Mem` is the normal way to request addressable memory behavior.

## Portable Verilog mapping

Portable Verilog module boundaries use one flat packed carrier because portable unpacked-array ports and SystemVerilog multidimensional packed syntax are not available in the required profile.

For:

```scala
val data = in(Vec(UInt(width), rows, cols))
```

the conceptual declaration is:

```verilog
input [(ROWS*COLS*WIDTH)-1:0] data;
```

Constant and symbolic indexing lower to verified bit-offset expressions or generated element views. Whole-value assignments use the same canonical flattening.

The flat carrier is a serialization boundary, not an arithmetic scalar. Whole-array arithmetic is not inferred from the carrier's Verilog type.

### Signed elements

A flat carrier cannot declare each element independently signed. Therefore `Vec[SInt]` uses a signless flat carrier plus signed element views when an element participates in signed arithmetic:

```verilog
input [(ROWS*COLS*WIDTH)-1:0] data;
wire signed [WIDTH-1:0] data_r_c = data[OFFSET +: WIDTH];
```

For dynamic indices, the backend may use a deterministically named signed selection wire or a verified signed helper function. It must not scatter redundant `$signed(...)` wrappers throughout ordinary expressions or treat the whole flattened collection as one signed integer.

Scalar `SInt` declarations remain directly signed as required by ADR 0016. The signed-element view is an unavoidable portable-Verilog boundary adaptation caused by flattening, and it is recorded in the layout/materialization report.

## Future SystemVerilog mapping

The default SystemVerilog representation for structural vectors is an unpacked multidimensional array of packed elements:

```systemverilog
input logic signed [WIDTH-1:0] data [0:ROWS-1][0:COLS-1];
```

This preserves element signedness and indexing directly. Fixed symbolic dimensions are emitted as constant expressions.

A separately selected packed-layout contract is also supported for serialization or interoperability:

```systemverilog
logic [ROWS-1:0][COLS-1:0][WIDTH-1:0] data;
```

The public semantic type remains `Vec`; the target/profile layout is selected by an explicit port/layout policy and recorded in the ABI manifest. A profile may reject an unpacked port layout when the selected tool does not support it rather than silently changing the interface.

## Verilog-A and Verilog-AMS mapping

Portable Verilog-A and Verilog-AMS profiles use flat vectors or deterministic scalar expansion according to the element kind and profile capability. Digital multidimensional ports follow the portable-Verilog flat ABI unless a future profile explicitly supports a richer form.

Analog nodes, branches, and continuous quantities are not silently packed into digital vectors. Arrays of analog objects require their own legal target construct and capability checks.

## Layout policy

Normal model source does not need to mention Verilog syntax. The candidate layout policies are target-neutral:

```text
Layout.Auto
Layout.FlatPacked
Layout.Unpacked
Layout.PackedDimensions
```

`Auto` selects the canonical profile mapping and emits the exact decision. Explicit layout is intended for external ABI/interoperability, not routine internal coding.

Layout affects representation only. It cannot change:

- rank or dimensions;
- element type and signedness;
- index order;
- protocol/domain metadata;
- latency or storage semantics;
- enum encoding;
- source identity.

## Operations

The initial shaped-value contract covers:

- exact multidimensional indexing;
- constant and symbolic slices;
- row/plane views;
- `flatten` and `reshape` with proven element-count equality;
- elementwise `map`, `zip`, and comparisons where the operation is legal;
- `reduce`/`fold` through ADR 0016's bounded hardware-iteration rules;
- exact shape-aware assignment and connection;
- nested `Vec`, `Bundle`, enum, `Valid`, and `Stream` elements;
- shaped constants and reset values;
- shaped register state;
- explicit adapters between compatible layouts.

Whole-array arithmetic is never inherited accidentally from a flattened HDL carrier.

## Verification

Before emission, the compiler checks:

- rank and dimension positivity;
- static/symbolic legality and parameter envelopes;
- total-width overflow in compiler and target constant expressions;
- index count, type, and bounds;
- slice/reshape compatibility;
- exact shape and element compatibility on connections;
- signed element preservation across flatten/unflatten;
- stable aggregate field order;
- protocol and domain compatibility;
- no implicit structural-`Vec` to `Mem` conversion;
- target/profile port legality;
- generated adapter and offset-formula equivalence;
- consistent Verilog/SystemVerilog numeric and index mapping.

The open-source digital matrix elaborates parameter combinations, simulates index/reshape behavior, parses both layouts where supported, and audits Yosys memory inference against the declared storage contract.

## Consequences

### Positive

- One user type supports parameterized multidimensional values across all backends.
- Portable Verilog receives a legal flat port ABI without sacrificing semantic rank.
- Future SystemVerilog receives readable unpacked multidimensional ports and signed elements.
- Memory behavior is explicit and never guessed from target declaration syntax.
- Flattening is deterministic and independently testable.
- Signed element handling remains correct despite portable-Verilog limits.
- External ABI/layout changes are visible in manifests and compatibility checks.

### Costs

- Portable Verilog needs generated offset expressions and signed element views.
- Whole-array debugging requires sidecar shape metadata when the carrier is flat.
- Tool-specific SystemVerilog array support requires capability profiles.
- Structural storage versus resource mapping needs synthesis evidence.
- Shape algebra and symbolic total-width proofs add compiler complexity.

## Rejected alternatives

- **Represent every vector as nested Scala collections:** loses symbolic HDL shape and exact backend ABI.
- **Use target unpacked arrays everywhere:** illegal or non-portable on required Verilog ports and risks target syntax defining semantics.
- **Treat every unpacked array as memory:** declaration syntax and synthesis choice do not define the Nodal storage contract.
- **Treat a flat `Vec[SInt]` carrier as one signed integer:** corrupts element arithmetic and indexing semantics.
- **Always flatten SystemVerilog ports:** legal but unnecessarily sacrifices readability, type information, and tool diagnostics.
- **Let each backend choose index order:** breaks cross-backend equivalence and reusable interfaces.
- **Allow runtime dimensions:** changes hardware shape dynamically and is outside static HDL elaboration.

## Follow-up increments

- Increment 13 compiles parameterized multidimensional `Vec`, shape, indexing, layout, signed-element, and negative fixtures.
- Increment 15 freezes the public shaped-value and layout contract.
- Increments 16-19 implement shape construction, stable identities, and target-neutral shape/layout IR.
- Increment 21 implements mandatory shape/storage verification.
- Increment 43 implements analog arrays and target generation under the same shape rules.
- Increments 54-58 implement digital shaped values, state, loops, hierarchy, and generate.
- Increment 65 implements portable-Verilog flattening and signed element views.
- Increments 66-67 validate simulation, synthesis, memory inference, and equivalence.
- Increment 72 applies the portable mapping to Verilog-AMS.
- Increment 99 evaluates native SystemVerilog unpacked and packed multidimensional layouts.

## References reviewed

- Accellera SystemVerilog array and port work: <https://www.accellera.org/images/eda/vlog-pp/0438.html>
- Yosys arrays and memories: <https://yosyshq.readthedocs.io/projects/yosys/en/stable/CHAPTER_Basics.html>
