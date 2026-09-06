# Shaped values, HDL materialization, and quality API v0.3 plan

**Status:** Normative roadmap candidate
**Shape architecture:** [ADR 0017](../architecture/0017-semantic-multidimensional-values-and-target-layouts.md)
**Naming architecture:** [ADR 0018](../architecture/0018-expression-materialization-and-semantic-naming.md)
**Quality architecture:** [ADR 0019](../architecture/0019-mandatory-pre-emission-hardware-quality-gates.md)
**Unified formal freeze:** Increment 15 design gate with core semantics and automatic pipelines
**Machine-readable candidate:** [`shaped-values-naming-quality-v0.3-surface.json`](shaped-values-naming-quality-v0.3-surface.json)

## Goal

Freeze three related contracts before substantial frontend/backend implementation:

1. one target-neutral multidimensional structural value model with parameterized dimensions and explicit memory distinction;
2. readable HDL that avoids unnecessary compiler-generated wires while giving required state deterministic semantic names;
3. mandatory internal and external quality gates that reject broken hardware before an emission is accepted.

The combined rule is:

> **Preserve semantic shape, materialize only for a reason, and prove hardware legality before accepting output.**

No implementation is performed by this plan. Increment 13 compiles candidates; Increment 15 freezes the accepted public/configuration surface.

## Multidimensional `Vec` candidate

Preferred declaration:

```scala
val rows = param(default = 4.integer, range = 1 to 64)
val cols = param(default = 8.integer, range = 1 to 64)
val width = param(default = 12.integer, range = 1 to 32)

val samples = in(Vec(SInt(width), rows, cols))
```

Preferred access:

```scala
val element = samples(row, column)
val firstRow = samples.row(0)
val flat = samples.flatten
val reshaped = flat.reshape(rows, cols)
```

Increment 13 compares `Vec(element, dimensions*)` with a `Vec.of(element, dimensions*)` fallback. The accepted API must remain concise for rank one while scaling to arbitrary fixed/symbolic rank.

### Shape rules

- Rank is static.
- Every dimension is a positive elaboration-time or symbolic constant expression.
- Runtime signals cannot determine rank or dimension.
- Zero-sized dimensions are rejected initially.
- The rightmost dimension is contiguous.
- Canonical flattening is row-major.
- Nested aggregate fields use stable declaration order.
- Index origin is zero in Nodal semantics.
- Index count must equal rank unless using a named slice/view operation.
- Constant out-of-range indices fail immediately.
- Symbolic/dynamic indices carry bounds requirements and runtime policy where applicable.
- Reshape requires proof of equal symbolic element/bit counts.
- Connection requires exact rank, dimensions, element type, protocol, domain, and layout contract or an explicit adapter.

### Element types

Initial shaped elements include:

- `Bool`;
- `Bits`;
- `UInt`;
- `SInt`;
- native enums;
- directionless bundles;
- nested `Vec`;
- legal `Valid[T]` and `Stream[T]` payload structures;
- shaped constants and reset values.

Analog object arrays remain capability-gated by the analog array increment.

## Structural `Vec` versus `Mem`

`Vec` is structural. `Mem` is addressable storage.

```scala
val coefficients = Vec(UInt(16), taps)

val samples = Mem(
  depth = entries,
  element = UInt(16),
  read = Read.Sync(latency = 1, underWrite = ReadFirst),
  write = Write(mask = ByteMask)
)
```

Required distinctions:

- `Vec` has no implicit memory read latency or collision policy;
- `Mem` declares ports, latency, collision, initialization, domain, and mapping behavior;
- target unpacked-array syntax does not convert `Vec` into `Mem`;
- dynamic indexing of `Vec` may lower to mux/decoder logic and does not silently request RAM;
- any optimization that maps structural state to RAM declares its effect and proves exact behavior;
- synthesis reports identify every inferred memory and its originating storage contract;
- a release profile fails when structural values unexpectedly become addressable memories under a no-memory policy.

## Target layout candidates

Normal model source uses semantic `Vec`. Layout is a boundary/profile choice.

Candidate policies:

```scala
Layout.Auto
Layout.FlatPacked
Layout.Unpacked
Layout.PackedDimensions
```

Candidate application points:

```scala
val data = in(Vec(SInt(width), rows, cols), layout = Layout.Auto)
```

or a typed boundary annotation selected during Increment 13. The final API should avoid repeating layout on ordinary internal values.

### Portable Verilog

A multidimensional port with a scalar element becomes one flat packed vector:

```verilog
input [(ROWS*COLS*WIDTH)-1:0] data;
```

Portable Verilog does not receive SystemVerilog multidimensional packed syntax. The manifest records:

- rank and dimensions;
- canonical row-major bit mapping;
- element signedness/type;
- symbolic total-width expression;
- every generated element view;
- wrapper/adapter compatibility hash.

For `Vec[SInt]`, the flat carrier is signless and signed arithmetic uses deterministic signed element views:

```verilog
wire signed [WIDTH-1:0] data_row_col = data[OFFSET +: WIDTH];
```

The backend may use a signed helper for dynamic selection. Scalar `SInt` still emits `wire/reg/input/output signed` directly.

### Future SystemVerilog

Default structural layout:

```systemverilog
input logic signed [WIDTH-1:0] data [0:ROWS-1][0:COLS-1];
```

Optional packed-dimensional layout:

```systemverilog
logic [ROWS-1:0][COLS-1:0][WIDTH-1:0] data;
```

The profile must preserve the same canonical Nodal index/flatten mapping and reject unsupported tool combinations rather than silently alter the ABI.

### Verilog-A and Verilog-AMS

Digital shaped values use the portable flat representation unless a selected profile explicitly supports more. Analog arrays require legal analog declarations and are never silently serialized into digital vectors.

## Recursive named field-vector lowering (planned)

**Added:** 2026-09-06
**Status:** Roadmap requirement; implementation and evidence remain open.

The existing multidimensional shape contract is not sufficient on its own: the
portable backend must also support one named packed vector per scalar aggregate
leaf, without changing the model source. Here `Bundle` means a directionless
value aggregate, corresponding to Nodal's `Struct` contract; connectivity
`Interface`s keep their separate role and leaf-direction rules.

### Same source, field-wise boundary layout

The source remains an aggregate-valued `Vec`:

```scala
val pixels = in Vec(Rgb(width), count)
```

For an `Rgb` aggregate with `red`, `green`, and `blue` fields of width `WIDTH`,
the field-vector layout must emit:

```verilog
input wire [(WIDTH * COUNT)-1:0] pixels_red;
input wire [(WIDTH * COUNT)-1:0] pixels_green;
input wire [(WIDTH * COUNT)-1:0] pixels_blue;
```

Users must not have to rewrite `Vec[Rgb]` as an aggregate of three separately
declared `Vec`s, manually transpose the data, write packing wrappers, annotate
every leaf, or replace normal element/field access. Selection of this layout is
a boundary/profile decision, not a change to the logical source type. Exact
configuration spelling belongs to the applicable implementation/design gate;
this roadmap does not introduce a new public Scala API or reopen a completed
API freeze.

When selected, the rule applies generically to every supported directionless
aggregate, not just `Rgb`, pixels, or a particular library component. Input and
output declarations, internal materialized aggregate views, and parent/child
connections must use the same recursive mapping. Interface payloads reuse the
value mapping only after their boundary roles and directions are resolved.

### Recursive paths, widths, and dimensions

Walk the semantic type recursively from the root:

1. At a `Vec`, append its dimensions, in declared outermost-to-innermost order,
   to the dimensions already encountered on that leaf's path.
2. At a Bundle/Struct, recurse through fields in stable declaration order and
   append each field name to the logical path. Do not collapse the record into
   an opaque integer or discard any enclosing dimensions.
3. At a scalar leaf, emit one carrier named from the root and full field path.
   Its bit width is that leaf's width multiplied by every Vec dimension on that
   path, and by no dimensions belonging only to sibling fields.

For `pixels: Vec[Pixel, COUNT]`, nested scalar fields must therefore include:

```verilog
input wire [(RED_WIDTH * COUNT)-1:0] pixels_color_red;
input wire [(X_WIDTH * COUNT)-1:0] pixels_position_x;
input wire [(Y_WIDTH * COUNT)-1:0] pixels_position_y;
```

If `Pixel.samples` is itself `Vec[Rgb(COMP_WIDTH), SAMPLES]`, its red leaf becomes:

```verilog
input wire [(COMP_WIDTH * COUNT * SAMPLES)-1:0] pixels_samples_red;
```

A rank-two outer Vec adds both dimensions to every applicable leaf; for example,
`pixels_color_red` then has width `RED_WIDTH * ROWS * COLS`. Nested Vecs inside
Bundles append their own dimensions as well. Mixed leaf widths are independent:
`RED_WIDTH`, `X_WIDTH`, and `Y_WIDTH` must never be replaced by one shared width.

Preserve the complete ordered dimension list, each symbolic width expression,
parameter binding/provenance, leaf type, and source path through frontend
construction, authoritative MLIR, hierarchy, optimization, and HDL emission.
A product-only bit count is not sufficient shape metadata. Symbolic parameters
must not be replaced by their elaboration defaults or recovered from HDL text,
rendered names, or component-specific patterns.

### Indexing, names, and ABI preservation

Within each field vector, use ADR 0017's canonical row-major mapping, with the
rightmost dimension contiguous and logical index zero at the low end. For the
rank-one example, `pixels(i).red` corresponds to
`pixels_red[i * WIDTH +: WIDTH]`. For a rank-two leaf, the element offset is
`((row * COLS) + col) * LEAF_WIDTH`. Whole-value copies, field/element reads and
writes, slices, flatten/reshape, map/zip/reduce, and hierarchy connections must
preserve logical behavior under the field-wise boundary representation.

The public logical flattening/serialization order must not silently become
field-major merely because ports are emitted field-wise. Any conversion between
a whole-record packed ABI and a field-vector ABI needs an explicit recorded
mapping, with inverse/round-trip evidence. Equal total bit counts alone do not
establish compatible field types, dimension order, directions, or layout.

Names retain the complete field path, such as `pixels_color_red` and
`pixels_position_x`, rather than numbered per-element ports or anonymous
`_zz*`/`_net*` boundary names. Handle underscore/path ambiguities, reserved words,
and explicit-name collisions deterministically using the semantic naming
contract; never silently merge two leaves. Retain logical paths separately from
escaped/disambiguated HDL spellings in the ABI manifest and source maps.

`Bool`, `Bits`, `UInt`, `SInt`, and enum leaves retain their own semantics.
A flattened collection of `SInt` leaves uses signless carriers with correctly
signed element views, not one signed integer spanning the whole collection.
Structural `Vec` remains distinct from `Mem`, and analog terminals/quantities
must not be serialized into digital field vectors.

The layout choice, leaf names and order, types, widths, ordered dimensions,
offset formulas, direction, and parent/child parameter bindings are part of the
versioned boundary ABI. Do not silently change an existing external/black-box
ABI; use an explicit compatible profile or adapter where necessary. Future
SystemVerilog layouts must preserve this same logical leaf mapping and may
retain dimensions in supported native array syntax. This requirement does not
advance the deferred SystemVerilog backend.

### Implementation checklist and acceptance evidence

These are additional open deliverables of the existing digital shape, hierarchy,
backend, and verification increments, not claims that the feature is implemented
or that prior completed increments must be reopened.

- [ ] **FV-01 — Semantic leaf projection:** Increments 54-58 retain unchanged
  Vec-of-aggregate source and implement generic recursive leaf paths, independent
  symbolic widths, complete ordered Vec dimensions, shape-aware operations,
  read/write behavior, and hierarchy bindings using the existing typed IR.
- [ ] **FV-02 — Portable named field vectors:** Increment 65 implements the
  field-wise layout and exact RGB/nested examples above for inputs, outputs, and
  materialized views, without source rewrites or per-component special cases.
- [ ] **FV-03 — Naming, ABI, and diagnostics:** Increments 54-58 and 65 reuse the
  existing source-origin/naming/IR contracts, record leaf mapping and layout ABI,
  and reject incompatible shapes, lost parameters, illegal directions, invalid
  dimensions, overflowing widths, and unresolved naming collisions. Preserve
  manifests and source maps through the later optimization increments 83-88.
- [ ] **FV-04 — Positive and negative matrix:** Increments 66-67 cover plain and
  nested Bundles/Structs, Vec-of-Vec, Bundle-of-Vec, alternating Vec/Bundle shapes,
  unequal leaf widths, rank-one through rank-four shapes, size/width one,
  non-power-of-two counts, independent symbolic parameter overrides, signed/enum
  leaves, static/dynamic indexing and assignment, hierarchy, and collision cases.
  Reject zero/negative/runtime dimensions and equal-bit-count but incompatible
  field/shape/layout connections rather than silently reinterpreting them.
- [ ] **FV-05 — Independent functional evidence:** Increments 66-67 add golden
  declaration/manifest checks, deterministic naming/source-map checks, portable
  Verilog parse/elaboration/simulation/synthesis evidence, and equivalence or
  formal checks against the unchanged logical aggregate behavior using an
  independently specified packing map. Cover read/write and pack/unpack
  round-trips, not just matching emitted text. These checks are future feature
  acceptance work; no tests, formal jobs, or CI are run for this roadmap-only edit.
- [ ] **FV-06 — Documentation and cross-backend closure:** Increment 92 documents
  the unchanged source, recursive leaf examples, bit mapping, and ABI selection;
  Increment 99 adds field-wise flat/native SystemVerilog parity evidence within
  its existing deferred scope. Update machine-readable contracts with the
  implementation rather than claiming coverage in this documentation change.

## Shape operations

Initial candidates:

```scala
value(i, j)
value.row(i)
value.plane(i)
value.slice(...)
value.flatten
value.reshape(dimensions*)
value.map(f)
value.zip(other)
value.reduce(f)
```

`map`, `zip`, and `reduce` obey ADR 0016 staged-loop rules. They cannot hide runtime iteration, state, CDC, analog sampling, or side effects.

The gate freezes:

- result shape and element type;
- signed element semantics;
- static/symbolic/dynamic index legality;
- bounds behavior;
- slice order;
- flatten/reshape formulas;
- whole-value assignment;
- constant construction;
- source mapping and diagnostics.

## Expression materialization candidates

Preferred compiler option direction:

```scala
EmitOptions(
  backend = Backend.Verilog,
  temporaries = TemporaryPolicy.InlineSafe,
  naming = NamingPolicy.Semantic,
  checks = CheckProfile.Default
)
```

Exact option/property names remain candidates.

### Temporary policies

```text
TemporaryPolicy.InlineSafe
TemporaryPolicy.Readable
TemporaryPolicy.Debug
TemporaryPolicy.ToolFriendly
```

`InlineSafe` is the preferred default. For:

```scala
a := b * c * d * e
```

the expected simple output is conceptually:

```verilog
assign a = (((b * c) * d) * e);
```

provided the typed renderer can preserve every intermediate width, sign, overflow, four-state, and source-order rule. If not, the backend materializes a typed value or emits explicit casts/extensions.

A policy cannot force semantically incorrect inlining.

### Materialization reason inventory

Every generated combinational temporary records one of:

```text
shared-expression
user-observable
user-named
signed-element-view
target-type-preservation
procedural-boundary
target-expression-limit
external-operation-boundary
pipeline-or-timing-boundary
formal-or-debug-anchor
source-map-granularity
plugin-pass-anchor
```

The list is versioned. A release report flags unknown/unclassified anonymous temporaries.

### User naming and observability

Candidate explicit name forms:

```scala
val product = name("product")(b * c)
```

or:

```scala
val product = (b * c).named("product")
```

Naming is separate from an optimization/observability barrier. Candidate controls include a distinct `keep`/debug/trace intent.

### Anonymous register names

All emitted state needs an identifier. Naming priority is:

1. explicit user name;
2. captured Scala `val`/member name;
3. destination/sink plus role;
4. subsystem role such as FSM/pipeline/protocol/memory/CDC;
5. nearest named ancestor plus operation role and source origin;
6. stable source/content digest fallback.

Examples:

```text
result_reg
result_delay_1
controller_state
pixel_pipe_stage_2_data
stream_valid_reg
out_signed_element_view
```

Traversal-number-only `_zz1`-style names are not accepted normal output.

## Source-map requirements

Inlining preserves source mapping at expression-span level. The sidecar map covers:

- generated declarations;
- process and assignment spans;
- operators and operands;
- casts/extensions;
- index/flatten formulas;
- generated views;
- source-named and compiler-named objects;
- optimization-origin chains.

External diagnostics must map to the original Scala expression even when no intermediate wire exists.

## Check-profile candidates

```scala
CheckProfile.Fast
CheckProfile.Default
CheckProfile.Release
```

### Fast

Runs every mandatory internal safety check:

- API/stage/type/shape checks;
- hierarchy/scope/ownership;
- drivers and assignment coverage;
- latch and combinational-cycle checks;
- width/sign/enum/FSM/loop checks;
- clock/reset and CDC/RDC;
- memory/storage/effect contracts;
- pipeline/protocol checks;
- analog/mixed-signal semantic checks;
- authoritative MLIR and target-profile verification.

It may skip expensive external tool matrices.

### Default

Adds target render/reparse plus normal pinned lint/compile tools available for the selected backend.

For portable digital Verilog, default evidence includes Verilator, Icarus, and Yosys parse/hierarchy/process/check/memory smoke where available in the locked toolchain.

### Release

Adds:

- all supported independent tools;
- parameter/shape/generate matrices;
- synthesis smoke and memory-inference audit;
- inline-versus-debug materialization equivalence;
- optimization equivalence/formal obligations;
- reproducibility and source-map checks;
- cross-tool portability;
- analog/AMS differential evidence where applicable;
- complete check-inventory and waiver reports.

No profile can disable mandatory safety checks.

## Mandatory internal checks

The initial inventory includes every applicable category below.

### Construction, hierarchy, and connections

- illegal scope/hierarchy access;
- recursive module instantiation;
- duplicate or conflicting names;
- input/output/inout direction violation;
- missing/duplicate/partial connection;
- incompatible instance signatures;
- undriven required outputs and signals;
- illegal cross-module mutable capture;
- unresolved black-box/external contracts.

### Types, shapes, and constants

- width/sign mismatch;
- implicit narrowing or sign conversion;
- out-of-range/ambiguous constants;
- rank/dimension mismatch;
- invalid shape/reshape/index/slice;
- invalid symbolic dimension or total-width expression;
- enum encoding/decode/exhaustiveness;
- unsupported target layout;
- signed element loss during flattening.

### Combinational logic and drivers

- assignment overlap and multiple drivers;
- continuous/procedural driver conflict;
- partial combinational assignment and inferred latch;
- combinational loop, including protocol-ready and indexed aggregate paths;
- read-before-definition;
- unreachable selection;
- implicit feedback through functions or generated regions;
- accidental whole-flat-vector arithmetic on structural collections.

### Sequential logic

- register without required domain;
- multiple register drivers;
- inconsistent reset values/policies;
- partial or ambiguous update priority;
- uninitialized/resetless state policy violation;
- illegal state/input direction;
- unsupported explicit latch use;
- FSM reachability, overlap, dead ends, join/recursion/encoding failures.

### Clock/reset and protocols

- direct CDC/RDC;
- invalid multi-bit synchronizer;
- unsafe pulse transfer;
- reset release/reconvergence;
- generated-clock/gate/mux relationship failures;
- protocol ordering/backpressure/stability/capacity errors;
- combinational ready loops;
- crossing and waiver misuse.

### Parameters, loops, pipelines, memories, and effects

- parameter stage misuse;
- invalid generate or runtime loop bounds;
- hidden multi-cycle behavior;
- loop-carried combinational cycle;
- parameter-envelope gaps;
- pipeline reconvergence/latency/capacity mismatch;
- memory collision/latency/init ambiguity;
- structural `Vec` unexpectedly treated as `Mem`;
- duplicated/reordered side effects;
- unknown external latency/effect.

### Analog and mixed signal

- physical-dimension mismatch;
- discipline/node/branch conflict;
- illegal or missing contribution;
- event/tolerance/initialization errors;
- unsupported algebraic loop or discontinuity;
- illegal analog/digital conversion;
- mixed-domain feedback and scheduling loops;
- backend-profile portability hazards.

## Explicit latch policy

Accidental latch inference is an error. Partial combinational assignment does not request storage.

A future latch feature, if approved, uses a distinct `Latch`-class primitive with explicit gate/reset/domain/profile semantics, diagnostics, simulation, synthesis, and formal contracts. No latch is generated merely because an `if` lacks an `else`.

## Waiver candidate

A waiver is typed and source-located:

```scala
waive(
  check = Check.CdcReconvergence,
  id = "CDC-001",
  reason = "...",
  scope = signal
)
```

The exact spelling is deferred. Required fields include stable ID, check code, scope, reason, owner/source, optional expiry, risk, and evidence. Blanket disabling of mandatory checks is not part of the normal API.

## Transactional output contract

An emission is accepted only after the selected mandatory gates pass. Failures retain:

- stable diagnostic codes;
- source and hierarchy paths;
- relevant parameter/shape/domain values;
- IR and staged HDL where requested;
- tool versions, logs, and commands;
- waiver inventory;
- reproduction instructions.

Partial files are diagnostic artifacts, not accepted generated HDL.

## Compile-positive matrix

Increment 13 must compile candidates for:

- rank-one through rank-four `Vec`;
- parameterized dimensions and nested bundles/enums;
- `Vec[SInt]` signed indexing;
- flatten/reshape/slice/map/zip/reduce;
- structural vectors versus explicit memories;
- default/flat/unpacked/packed layout requests;
- safe inlining of arithmetic and conditional expressions;
- shared-expression materialization;
- explicit source naming and debug observability;
- anonymous register sink-affinity names;
- Fast/Default/Release check-profile configuration;
- typed waiver metadata without executing checks.

## Compile-negative matrix

Required failures include:

- runtime/zero/negative/overflowing dimension;
- rank/index/reshape mismatch;
- illegal layout for selected backend profile;
- implicit `Vec` to `Mem` conversion;
- signed element semantics lost by flattening;
- forced inlining that cannot preserve target semantics;
- duplicate/conflicting explicit names;
- unknown materialization reason;
- mandatory check disabled by profile;
- blanket waiver or waiver without ID/reason/scope;
- accidental latch, combinational cycle, multiple driver, undriven output, hierarchy violation, width/sign/shape mismatch, CDC/RDC, ready loop, and unexpected memory inference fixtures.

## Freeze exit criteria

Increment 15 may freeze this contract only when:

1. parameterized multidimensional shapes compile under separate-library use;
2. canonical flatten/index formulas are exact and documented;
3. portable-Verilog flat ports and future-SystemVerilog unpacked/packed ports are numerically equivalent;
4. signed elements retain `SInt` semantics across both layouts;
5. `Vec` and `Mem` remain semantically distinct with synthesis evidence requirements;
6. safe inlining removes avoidable anonymous-wire chains without changing typed expression semantics;
7. required temporaries and all state receive deterministic semantic names;
8. source maps survive inlining/materialization changes;
9. mandatory Fast/Default/Release gates and waiver boundaries are unambiguous;
10. the check inventory covers every published SpinalHDL safety category plus listed Nodal-specific categories;
11. positive/negative fixtures, machine-readable surfaces, migration notes, and CI pass.

## Increment integration

- Increment 13: API/configuration candidates and negative fixtures.
- Increment 15: unified public API/configuration freeze.
- Increment 16: construction ownership, shape capture, and transactional lifecycle.
- Increment 17: source-span, semantic name, sink-affinity, and origin graph.
- Increment 19: shaped types/layout/storage and materialization metadata in MLIR.
- Increment 21: staged internal verification and target re-verification.
- Increment 22: source-located diagnostics and path reporting.
- Increment 26: deterministic names, materialization, layouts, checks, and evidence.
- Increment 43: analog arrays and static generation.
- Increments 54-58: digital shaped values, expressions, state, hierarchy, and the recursive named field-vector deliverables FV-01/FV-03.
- Increments 65-67: portable Verilog flattening/inlining, recursive named field-vector lowering and evidence FV-02 through FV-05, external lint/simulation/synthesis/equivalence.
- Increment 71: full mixed-domain verifier.
- Increment 72: Verilog-AMS mapping.
- Increments 83-88: pass preservation and mandatory re-verification.
- Increment 92: user/reference documentation, including recursive named field-vector examples and ABI mapping FV-06.
- Increment 96: performance and scaling measurements.
- Increment 97: v1 API/quality coverage review.
- Increment 98: preview release evidence.
- Increment 99: future SystemVerilog unpacked/packed port layout gate and field-wise flat/native parity FV-06.

## References

- Accellera SystemVerilog arrays and ports: <https://www.accellera.org/images/eda/vlog-pp/0438.html>
- Yosys arrays and memories: <https://yosyshq.readthedocs.io/projects/yosys/en/stable/CHAPTER_Basics.html>
- SpinalHDL design errors: <https://spinalhdl.github.io/SpinalDoc-RTD/master/SpinalHDL/Design%20errors/index.html>
- CIRCT passes: <https://circt.llvm.org/docs/Passes/>
