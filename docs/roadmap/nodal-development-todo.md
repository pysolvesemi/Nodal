# Nodal Incremental Development TODO

**Revision:** 1.53
**Created:** 2026-08-20
**Updated:** 2026-09-22
**Status:** Active roadmap
**Primary language target:** Verilog-AMS 2023
**Analog-only compatibility target:** Verilog-A

## Mission

Nodal is a modern Scala 3 hardware-construction language for analog and mixed-signal design and modeling. It provides a short, high-level public API, preserves useful Verilog-AMS terminology where it improves clarity, and uses MLIR/CIRCT as the compiler foundation.

The implementation is built from scratch with modern tooling. It carries no Scala 2, old-JDK, FIRRTL, Chisel, SpinalHDL, or legacy Nodal compatibility requirement.

## Fixed project direction

- Use a current stable Scala 3 release, modern JDK, and pinned Mill 1.x wrapper.
- Use CMake, Ninja, LLVM, MLIR, and CIRCT for the native compiler with checked-in, mutually compatible toolchain revisions.
- Define an out-of-tree `nodal` MLIR dialect. Reuse CIRCT `hw`, `comb`, `seq`, `sv`, and related dialects only where their semantics match Nodal.
- Treat MLIR as the authoritative compiler IR. The Scala frontend may keep a small construction model, but semantic passes and HDL emission operate on MLIR.
- Generate Verilog-AMS as the first complete backend and Verilog-A through an analog-only capability profile.
- Preserve symbolic parameters through elaboration, IR, hierarchy, optimization, and native parameterized Verilog-A/Verilog-AMS emission. Do not clone one module per parameter value.
- Define control/status registers through one canonical, bus-neutral Register IR. Keep immutable register ABI definitions, physical register-block instances, committed-access semantics, APB/AXI4-Lite/custom transports, and generated software or integration artifacts as separate layers.
- Permit one authoritative register-map source per block: native Scala DSL, supported SystemRDL 2.0, or versioned Nodal YAML/JSON. Treat IEEE 1685-2022 IP-XACT as later integration interchange and CSV/spreadsheets only as explicit conversion inputs; all frontends normalize into the same canonical Register IR.
- Emit fixed register offsets, field positions, masks, reset values, and access encodings as width-safe non-overridable Verilog `localparam`s/constants by default. Emit HDL `parameter`s only for explicit Nodal architectural variability, use block-relative decode by default, and keep an optional absolute-base wrapper explicit.
- Keep backend spelling out of the public API and target-neutral IR wherever practical.
- Generate deterministic, readable HDL, normalized IR, reports, and diagnostics.
- Keep all mandatory language, elaboration, compiler, backend, simulator-adapter, and test infrastructure under `core/`.
- Reserve `libraries/` for future optional reusable packages. Enforce the one-way dependency `libraries -> core`; core must never depend on a Nodal library.
- Model ordinary synchronous state with an implicit local clock/reset domain and high-level state/update constructs, not source-level Verilog `always` blocks.
- Make every clock-domain crossing and reset-domain crossing explicit through typed semantic primitives. Preserve domain provenance through hierarchy and IR so unsafe crossings fail before HDL generation.
- Prefer clock enables over user-created clocks. Generated clocks, physical clock gates, clock muxes, and reset trees require explicit primitives carrying relationship, mapping, and timing metadata.
- Treat automatic pipelining as deterministic scheduling of an explicit feed-forward transaction graph, not opaque HLS. Never silently change arithmetic, ordering, protocol, clock/reset domains, resource sharing, side effects, or parameterized module identity.
- Distinguish fixed-rate, valid-only, and elastic ready/valid pipelines in the type system. Insert and balance only pipeline-owned registers and protocol buffers inside an approved pipeline region.
- Distinguish directionless storable `Struct` values from non-storable connectivity `Interface`s; never hide boundary direction or connectivity roles inside reusable value fields.
- Apply named `Role`s at interface boundaries. Provide concise `master`/`slave` and `monitor` behavior for `Valid`/`Stream` while retaining a generic role model for request/response, controller/peripheral, device/environment, and AMS access.
- Support first-class digital `inout` through explicit typed read/drive/high-impedance semantics, resolved-net identity, open-drain/push-pull modes, black-box and hierarchical pass-through, and capability-checked internal tri-state use; never silently rewrite unsupported resolution into a mux.
- Keep digital resolved `inout`, conservative AMS terminals, directional analog signal-flow values, and discrete real nets as distinct semantic categories. Require explicit bridges for every analog/digital or conservative/signal-flow conversion.
- Preserve source-semantic analog constructs separately from normalized topology, hybrid equation systems, analysis projections, target AMS IR, and solver-facing representations; no simulator callback ABI or emitted HDL text defines Nodal semantics.
- Treat source-level continuous equations as unordered simultaneous constraints. Preserve authored left/right expressions and a canonical solver-neutral residual; do not infer execution order, causal direction, or division-based rearrangement in the frontend.
- Keep first-class equations, additive potential/flow contributions, procedural analog assignments, and conservative connections as distinct semantic operations with separate ordering, accumulation, ownership, and legality rules.
- Generate conservative connection equations from terminal connection sets: compatible potentials are equal and signed flows sum to zero, with branch orientation and provenance retained.
- Support partial and concrete physical-component contracts with local equation/unknown balance checks before whole-design island and DAE verification.
- Analyze a logically flattened topology/equation view while retaining source hierarchy and emitting hierarchical Verilog-A/Verilog-AMS wherever the selected target permits.
- Distinguish ordinary parameters, structural parameters, and dynamic values. Topology, component count, equation count, shape, or structural rank may change only through elaboration/static generation or an explicitly capability-gated variable-topology contract.
- Lower general equations through a capability-checked equation-to-target legalizer that may select a safe potential/flow form, introduce an explicit auxiliary unknown or branch, preserve a target-supported form, or reject the target; never silently approximate or orient an equation merely to emit HDL.
- Partition continuous behavior into explicit `AnalogIsland`s with stable topology, unknown, equation, contribution, state, event, noise, analysis, capability, and source identities.
- Make analog state, initialization, discontinuities, event iteration, analysis context, environment/PVT, derivatives, solver hints, and model-validity envelopes explicit and machine-readable rather than backend side effects.
- Negotiate simulator and solver capabilities before execution, reject unsupported behavior without approximation, and keep a native analog solver optional for the initial release.
- Preserve one logical Interface ABI through IR and emit deterministic flattened Verilog/Verilog-A/Verilog-AMS ports plus an optional future native SystemVerilog interface/modport representation with proven flat/native parity.
- Distinguish elaboration-only Scala values, symbolic HDL parameters/constants, and dynamic hardware values. Target-visible generation is explicit and never inferred from ordinary Scala control.
- Use lossless finite-width arithmetic by default. Narrowing, wrap, truncation, saturation, checked resize, and signedness conversion require explicit intent.
- Preserve `Bits` as signless, `UInt` as unsigned, and `SInt` as two's-complement signed through ports, parameters, memories, expressions, optimization, and every Verilog-family backend; never let backend expression rules define Nodal signedness.
- Distinguish ordinary Scala elaboration loops, symbolic structural `generate` loops, and bounded hardware-iteration loops. Dynamic or unbounded iteration must not acquire hidden latency or an inferred FSM.
- Preserve ordinary Scala `for` syntax through typed staged ranges: `genRange(...)` constructs structural generation and `hwRange(...)` constructs bounded hardware iteration, while ordinary Scala ranges remain elaboration-only. Bounds may be concrete Scala `Int` values or legal target-visible integer parameters/constants. Never infer loop staging from module instances, local binders, `Reg`/`Wire` presence, or any other loop-body content.
- Use native Scala 3 enums as the preferred semantic declaration and derive typed hardware enum metadata; Scala ordinal never defines the HDL ABI.
- Separate canonical enum interface encoding from local FSM storage encoding. Preserve one stable numeric mapping across portable Verilog localparams, Verilog-A/Verilog-AMS constants, and future SystemVerilog native enums.
- Model control as typed FSM/statechart graphs with explicit reset, transition priority, illegal-state, hierarchy, parallel, timing, completion, recursion-bound, source-map, and proof contracts rather than mutable backend-style process objects.
- Keep aggregate payloads directionless; apply direction at ports, use plain/`Valid`/`Stream` protocol types consistently, and require exact direct connections with typed adapters for intentional conversion.
- Represent multidimensional structural values with semantic rank, parameterized dimensions, stable row-major indexing, and explicit target layouts. Keep `Vec` structural and `Mem` addressable; target unpacked-array syntax never defines memory semantics.
- Map portable-Verilog multidimensional ports to deterministic flat packed carriers and future SystemVerilog ports to unpacked multidimensional arrays of packed elements by default, with explicit packed-layout interoperability when requested.
- Keep pure combinational expressions in typed DAG form and inline compiler-generated single-use expressions whenever exact width/sign/four-state semantics permit. Materialize only for a declared reason and give every required net/state a deterministic semantic name.
- Preserve Scala lexical binders through helper-function calls as structured caller/local name paths. A materialized helper-local value uses a readable name such as `pixelResult_widenedSum`; a safely inlined single-use expression retains its binder and aliases in IR/source maps without forcing an unnecessary HDL wire. Reserve `_net_*` for genuinely unnamed Nodal-owned combinational values, prefer semantic operation or sink-derived names, and prohibit `_zz*` and traversal-counter identities in accepted generated HDL.
- Reject invalid hardware through mandatory staged construction, semantic-graph, MLIR, target-legalization, reparse, lint, and synthesis gates before an HDL artifact is accepted. Core safety verifiers cannot be disabled by plugins or optimization passes.
- Preserve physical dimensions for analog and mixed-signal quantities and reject incompatible equations before HDL generation without exposing verbose unit types in normal source.
- Classify memory, external, analog, stateful, observational, and side-effecting operations explicitly so scheduling, optimization, and verification never guess latency or purity.
- Classify complete designs as digital-only, analog-only, or mixed-signal. `Backend.Auto` selects the narrowest compatible backend, including portable Verilog for digital-only designs.
- Verify generated pure-digital HDL through a pinned open-source matrix using Verilator, Icarus Verilog, Yosys, SBY, and optional cocotb interoperability.
- Keep future user-authored formal properties target-neutral and domain-aware in Nodal IR; do not make raw SVA strings, SBY files, or one solver define public semantics.
- Separate formal property authoring, target lowering, harness generation, and proof-engine execution so formal-only constructs cannot silently alter synthesizable behavior or ordinary simulation.
- Permit checker RTL only for an explicitly selected immediate Boolean assertion. Concurrent or temporal properties, sampled history, assumptions, covers, symbolic formal values, and generated verification monitors remain verification-only and never enter synthesizable DUT RTL.
- Require explicit property IDs, clock/reset semantics, assumption scope, symbolic environment, proof task, result state, source mapping, vacuity/constraint evidence, and counterexample provenance before reporting a formal result.
- Treat AMS-to-FPGA validation as an explicit discrete-time, finite-precision approximation transformation. `Backend.Auto` must never select it and no report may present it as direct synthesis of general Verilog-AMS.
- Require sample period, solver, state/reset, fixed-point, range, rounding/overflow, multi-rate/event, validation-envelope, error-budget, and target-FPGA contracts before generating an approximation.
- Preserve separate evidence for AMS-reference error, discretization/model-reduction error, fixed-point error, RTL implementation, FPGA timing/resources, and hardware-in-the-loop runtime.
- Reuse portable Verilog, automatic pipelines, clock/reset domains, CDC/RDC, Verilator/Icarus, Yosys, SBY, and an open Yosys+nextpnr target for FPGA approximation validation; vendor flows remain optional adapters.
- Separate passive reusable libraries from executable plugins. Installing a model library must not implicitly execute or enable a plugin.
- Resolve plugins from explicit manifests into a versioned typed capability graph and lockfile before loading Scala classes, native libraries, or external processes.
- Use local `DesignHost` scopes, stable capability IDs, explicit cardinality/qualifiers, append-only contributions, and deterministic phases instead of concrete-plugin lookup, global service registries, mutable cross-plugin access, or public retain/release ordering.
- Wrap MLIR pass/dialect plugin mechanisms with Nodal SPI, IR, toolchain-build, namespace, analysis-preservation, and mandatory re-verification contracts.
- Run simulator, synthesis, formal, FPGA, programmer, board, and HIL adapters through a versioned out-of-process plugin protocol with normalized artifacts and retained provenance.
- Make plugin graph, artifact, option, phase, pass, process-protocol, trust, and toolchain hashes part of deterministic build manifests and cache keys.
- Support plug-and-play Verilog, Verilog-A, and Verilog-AMS optimization through structured target-IR passes layered on the plugin SPI; arbitrary semantic raw-text filters are not an optimization contract.
- Require every target-HDL pass to declare target/profile/IR compatibility, semantic preservation and analysis invalidation, parameter/hierarchy/source-map effects, determinism, and proof/validation obligations.
- Keep backend selection separate from optimization profile selection. Installing a pass never executes it, changes `Backend.Auto`, or alters generated hardware without an explicit locked pipeline.
- Preserve symbolic parameters, one-module-per-structure, clock/reset and CDC/RDC, protocols/latency, physical dimensions, contributions/events/noise/connect rules, and source provenance through target optimization unless an explicit separately verified transformation contract says otherwise.

## Public API direction

- Prefer short names such as `Module`, `Param`, `ClockDomain`, `Reg`, `Electrical`, `Real`, `Integer`, `Bool`, `Bits`, and `UInt`.
- Preserve analog and mixed-signal terms such as `analog`, `initial`, `on`, `discipline`, `nature`, `V`, `I`, `ddt`, `idt`, `cross`, `timer`, `transition`, and `<+`.
- Freeze a first-class unordered equation block and explicit equation-construction form while keeping `<+` contribution and procedural assignment visibly distinct.
- Evaluate `equation(lhs, rhs)` and concise operator candidates through compile prototypes; do not overload `===` unless Boolean equality, type resolution, and diagnostics remain unambiguous.
- Provide acausal conservative terminal/branch, reusable one-port/two-port, partial/concrete component, initial-equation, state-initialization, and reinitialization candidates in the continuous-time gate.
- Use Scala traits, abstract classes, factories, and typed interfaces for replaceable model implementations rather than copying Modelica redeclaration syntax.
- Do not copy backend event-process syntax into ordinary synchronous source.
- Provide a compact automatic-pipeline surface centered on `pipe`, `delay`, protocol-typed transactions, latency/throughput policies, automatic sideband alignment, and optional hard stage constraints. Do not expose node/link plumbing in ordinary datapath source.
- Freeze value staging, lossless numeric/width rules, directionless aggregates, exact connections, physical quantities, memory/external effects, and automatic pipelines in one coherent public API v0.3 gate.
- Freeze native Scala enum derivation, canonical encoding/ABI, safe decode, exhaustive selection, local FSM encoding, flat FSM actions/transitions, reusable definitions, hierarchical/parallel/timed composition, and bounded recursion in the same v0.3 gate.
- Emit enum members as non-overridable `localparam`s in portable Verilog and Verilog-AMS profiles; a future SystemVerilog profile emits native typed enums with the same explicit values and compile-order metadata.
- Provide explicit lossy numeric conversions such as truncate, wrap, saturate, and checked resize; never narrow or reinterpret signedness silently.
- Freeze exact `SInt` declaration/literal/parameter/memory/expression rules, numeric conversion versus bit reinterpretation, mixed-sign diagnostics, arithmetic/logical shifts, and portable Verilog/future SystemVerilog signed lowering in public API v0.3.
- Keep ordinary Scala `for` over Scala ranges for elaboration; add typed staged range candidates `genRange(...)` and `hwRange(...)` so the same Scala `for` syntax explicitly constructs structural generation or bounded hardware iteration without body-based inference. Each staged constructor accepts concrete Scala `Int` bounds and legal symbolic integer parameter/constant bounds. `hwRange` obtains its finite envelope from the concrete bound, the parameter's declared legal range, or an explicit enforced `maximum`. Retain `generate(...)` and `loop(...)` as canonical explicit forms; reject runtime trip counts and unbounded `while` in the initial synthesizable contract.
- Freeze a parameterized multidimensional `Vec` shape/index/flatten/reshape contract, explicit `Vec` versus `Mem` storage semantics, and target layout policies for portable-Verilog flat carriers and future-SystemVerilog unpacked/packed ports.
- Freeze emission configuration candidates for safe expression inlining, readable/debug/tool-friendly materialization, semantic naming, source-span maps, and Fast/Default/Release quality profiles with typed waivers that cannot suppress mandatory safety checks.
- Treat `Valid[T]` and `Stream[T]` as general protocol types shared by ports, hierarchy, memories, simulation, and automatic pipelines.
- Add `Backend.Auto` and `Backend.Verilog` for pure-digital output while retaining explicit `Backend.VerilogA` and `Backend.VerilogAMS` profiles.
- Keep AMS approximation separate from backend selection. A future `FpgaApproximation`-class public contract must be explicitly requested, produce a digital approximation artifact, and only then use `Backend.Verilog`.
- Freeze the AMS-to-FPGA capability profile, solver/numeric/envelope contracts, claims language, diagnostics, and validation evidence through a dedicated post-preview design gate before implementation.
- Add a separately versioned plugin SPI candidate covering `DesignPlugin`, local `DesignHost`, stable `CapabilityKey`/`ContributionKey`, plugin descriptors/manifests, immutable `PluginPlan`, backend IDs, and process-adapter descriptors.
- Keep plugin identity independent of Scala implementation classes. Consumers depend on stable capability IDs and interfaces, never `host[ConcretePlugin]` or implicit first-provider selection.
- Require explicit project plugin configuration, compatibility resolution, lockfiles, checksums/trust policy, and offline locked mode; do not scan arbitrary classpaths or directories for executable extensions.
- Plugins may add namespaced, approved extensions but cannot override core language semantics, safety verifiers, width/unit/domain rules, or silently participate in `Backend.Auto`.
- Add a separately versioned target-HDL optimization-pass SPI with stable pass IDs, explicit extension points/profiles, normalized options, locked deterministic ordering, structured digital/analog/AMS IR, and proof-carrying evidence.
- Provide explicit optimization profiles such as none/canonical/portable/simulation/synthesis/formal/FPGA/custom while keeping the exact profile and pass graph visible in `EmitOptions`, project configuration, lockfiles, manifests, reports, and cache keys.
- Semantic post-render transforms must reparse into the approved target representation, restore source/capability metadata, run mandatory verification, and satisfy the pass proof obligation; render-only plugins may change formatting but not parsed meaning.
- Keep Scala/native in-process plugins trusted and explicitly enabled; prefer process isolation for external tools and long-lived transform/backend integrations.
- Reserve a separately versioned future formal-verification API for assert/assume/cover, sampled history, symbolic values, harnesses, contracts, and proof tasks; exact names remain deferred to its design gate.
- Keep Scala runtime assertions, simulation assertions, formal properties, and explicitly synthesized immediate assertions distinct unless a frozen inclusion policy intentionally shares one immediate invariant. Concurrent or temporal properties are never synthesis-eligible.
- Keep proof-engine options behind normalized task/adaptor contracts; installing a formal adapter or property library never executes a proof or changes `Backend.Auto`.
- Use compile-positive and compile-negative fixtures to freeze public names, types, construction forms, imports, and diagnostics.
- Keep ordinary model source backend-neutral and exclude frontend/compiler internals from the future library-author subset.
- Reserve a separately versioned register-factory API gate for immutable `RegisterMap` definitions, physical `RegisterBlock` bindings, typed field handles, orthogonal software/hardware/collision policies, committed-access endpoints, and Scala 3 transport adapters. Exact spellings remain deferred to Increment 116.
- Keep register authoring independent of a concrete access bus. APB, AXI4-Lite, and custom buses attach through capability-checked adapters; multiple access paths to one physical bank require an explicit arbiter/router.
- Require equivalent Scala/SystemRDL/YAML descriptions to produce equivalent canonical Register IR and ABI hashes. Generated headers, UVM models, documentation, SystemRDL, IP-XACT, and other views never become hidden competing sources.
- Any incompatible public API change after a freeze requires a new versioned design gate and migration note.

## Clock, reset, and timing-domain architecture

The binding architecture is [ADR 0007](../architecture/0007-implicit-clock-reset-domains.md). The exact public API candidate, staged delivery plan, and freeze exit criteria are in [`clock-reset-api-v0.2-plan.md`](clock-reset-api-v0.2-plan.md), with a machine-readable candidate in [`clock-reset-api-v0.2-surface.json`](clock-reset-api-v0.2-surface.json).

Nodal adopts:

> **Implicit local domain, explicit crossing, explicit emitted HDL.**

### Ordinary single-domain source

```scala
final class Counter extends Module:
  val enable = in(Bool)
  val value = out(UInt(8))

  val count = Reg(0.U(8))

  when(enable):
    count := count + 1.U

  value := count
```

The module does not name a clock or write `always(clock.rising)`. `Reg`, `RegNext`, memories, state machines, samplers, and clocked children capture the current lexical domain when created.

Rules to freeze in public API v0.2:

- `Reg(init)` creates resettable state and infers its type from the reset value.
- `Reg.uninitialized(kind)` creates deliberate resetless/uninitialized state.
- `RegNext(next, init)` and `RegNext.uninitialized(next)` provide one-stage pipeline forms.
- No assignment means hold.
- `when`/`elsewhen`/`otherwise` define deterministic lexical priority.
- Reset dominates clock enable and ordinary next-state updates.
- Unrelated multiple state drivers are errors.

### Root domain

```scala
final class Top extends Module:
  val core = ClockDomain.external(
    name = "core",
    edge = ClockEdge.Rising,
    reset = ResetPolicy.AsyncAssertSyncRelease(stages = 2),
    resetPolarity = ResetPolarity.ActiveLow,
    frequency = 100.MHz
  )

  core:
    val design = instance(new Design)
```

`ClockDomain.external` creates deterministic external clock/reset ports only when the domain is used. Pure analog and combinational modules receive no unused clock/reset ports.

`ClockDomain.from(...)` binds existing typed `Clock` and `Reset` signals. A root with unresolved sequential-domain requirements is an error; Nodal does not silently choose edge, polarity, or reset style.

### Reusable hierarchy and multiple domains

A single-domain child instantiated inside a domain inherits that domain automatically. An explicit override uses `.domain(actualDomain)`.

A reusable multi-domain module declares typed requirements:

```scala
final class AsyncBridge extends Module:
  val writeDomain = ClockDomain.required()
  val readDomain = ClockDomain.required()

  writeDomain:
    // write-side state

  readDomain:
    // read-side state
```

The parent binds requirements with typed selectors:

```scala
val bridge =
  instance(new AsyncBridge)
    .domain(_.writeDomain, bus)
    .domain(_.readDomain, pixel)
```

String-keyed domain maps are not part of the public API.

### Domain metadata and relationships

The freeze candidate contains `Clock`, `Reset`, `ClockDomain`, `ClockEdge`, `ClockRelation`, `ResetPolicy`, and `ResetPolarity`. `Clock` and `Reset` are distinct from `Bool`.

Initial reset policies:

```scala
ResetPolicy.None
ResetPolicy.Sync
ResetPolicy.Async
ResetPolicy.AsyncAssertSyncRelease(stages = 2)
```

Power-on initialization remains a separate state/backend capability rather than an implicit reset policy.

Generated clocks use `ClockDomain.generated(...)` with an explicit parent and `ClockRelation`. Initial relation categories are same/alias, ratio-derived, synchronous, mutually exclusive, asynchronous, and unknown. Equal frequency does not prove a safe relationship. Only same/alias domains are directly interchangeable by default.

The lexical domain stack is compiler-managed. It is not exposed as a Scala `implicit`, `given`, thread-local, mutable global, or JVM-identity contract.

### CDC and RDC

The public crossing surface is semantic:

```scala
Cdc.sync(bit, to = destination, stages = 2)
Cdc.gray(grayValue, to = destination, stages = 2)
Cdc.pulse(pulse, to = destination)
Cdc.handshake(payload, to = destination)
Cdc.fifo(stream, to = destination, depth = 4)
Rdc.sync(reset, to = destination, stages = 2)
```

`Cdc.sync` accepts only a one-bit level. `Cdc.gray` requires a Gray-code proof/type. Pulses use pulse/toggle semantics. Coherent multi-bit data uses handshake or asynchronous FIFO semantics. Nodal never silently inserts a generic synchronizer.

Exceptional crossings require `Cdc.waive(...)` with a stable waiver ID, reason, declared relationship, source location, and report/constraint evidence. A waiver does not erase provenance or disable reconvergence checking.

The compiler must diagnose direct asynchronous sampling, combinational CDC paths, unknown-domain use, multi-bit synchronizer misuse, unsafe pulse transfer, independently synchronized bus bits, reconvergence, unsafe generated clocks, unsynchronized reset release, reset reconvergence, partial-reset dependencies, and incompatible crossing primitives before HDL emission.

### Clock gates and muxes

Ordinary conditional updates use `when` and register enable semantics. Physical clock structure uses explicit `ClockGate(...)` and `ClockMux.glitchless(...)` primitives. These return derived domains and preserve parent, generated-clock, test-enable, mapping, and timing-constraint metadata. Arbitrary Boolean-to-clock conversion is an error.

### Analog events and low-level escape

These remain genuine analog/event semantics:

```scala
analog:
  ...

on(cross(...)):
  ...

on(timer(...)):
  ...
```

Analog-to-digital observation requires an explicit destination-domain sampler, threshold/comparator, or ADC operation. Digital-to-analog updates retain their source domain and transition/hold policy.

True event-driven behavior that cannot be represented as domain-owned state is isolated under a `nodal.lowlevel.process(event)`-style escape. It is not the ordinary register API, cannot create untracked state, and cannot bypass CDC/RDC or mixed-domain verification.

## Core semantic architecture

The binding architecture is [ADR 0009](../architecture/0009-core-semantic-contracts.md). The exact candidate, compile matrix, and unified freeze criteria are in [`core-semantics-api-v0.3-plan.md`](core-semantics-api-v0.3-plan.md), with a machine-readable candidate in [`core-semantics-api-v0.3-surface.json`](core-semantics-api-v0.3-surface.json).

Nodal adopts:

> **Explicit stage, lossless value semantics, exact connection, dimension-safe quantity, declared effect.**

### Value stages

Nodal distinguishes:

- ordinary Scala values used only during elaboration;
- symbolic `Param`/constant/width/range/generate values preserved in target HDL;
- dynamic ports, wires, registers, memories, protocols, and sampled analog values.

A symbolic parameter is not a Scala `Int`; a runtime signal cannot control hardware shape. Target-visible replication uses an explicit symbolic `generate(...)` construct rather than an ordinary Scala loop.

### Numeric and width policy

Ordinary finite-width arithmetic retains mathematically required result bits. Narrowing and signedness changes require explicit policy through candidates such as `extend`, `truncate`, `wrap`, `saturate`, `resizeChecked`, `toSigned`, and `toUnsigned`.

Assignment never silently truncates, wraps, saturates, or reinterprets. Automatic scheduling preserves the exact typed arithmetic graph and may not reassociate expressions or change overflow/rounding behavior.

### Directionless aggregates and protocols

Reusable aggregate payloads are directionless. `in(...)`, `out(...)`, and `inout(...)` apply direction at the boundary. Plain values, `Valid[T]`, and `Stream[T]` are shared transport types for ports, hierarchy, memories, simulation, and pipelines.

Direct connection is exact: no implicit resize, field loss, protocol conversion, domain crossing, or latency insertion. Intentional transformation uses a typed adapter/view contract.

### Physical quantities

Voltage, current, resistance, capacitance, time, frequency, charge, power, and dimensionless values retain physical dimensions through expressions and symbolic parameters. Addition/comparison require compatible dimensions; multiplication/division and `ddt`/`idt` derive dimensions. Unit mistakes fail before HDL generation.

### Effects, memories, and external operations

The compiler distinguishes pure combinational work from state, memory, analog contribution, events/observation, external operations, and side effects. Only pure or explicitly movable operations may be scheduled or retimed.

Memory declarations define read mode/latency, write masks, read-under-write, collision/ordering, domains, and initialization capability. External operations define type/protocol, latency, throughput, domain/reset, effect, ordering, and simulation/synthesis/formal models. Unknown behavior is a barrier, never a guessed default.


## Signed numeric and staged-loop architecture

The binding architecture is [ADR 0016](../architecture/0016-signed-types-and-staged-loops.md). The exact signed type, conversion, literal, backend, loop-category, lowering, verification, and freeze candidates are in [`signed-loop-api-v0.3-plan.md`](signed-loop-api-v0.3-plan.md), with a machine-readable candidate in [`signed-loop-api-v0.3-surface.json`](signed-loop-api-v0.3-surface.json).

Nodal adopts:

> **Signedness is a type contract; loop kind is a staging contract. Neither is inferred from backend syntax.**

The initial numeric distinction is `Bits(width)` for signless bit containers, `UInt(width)` for unsigned integers, and `SInt(width)` for two's-complement signed integers. Signedness survives parameters, ports, wires, registers, aggregates, memories, expressions, source maps, optimizations, and backend lowering. Mixed signed/unsigned arithmetic and comparisons require explicit conversion or a separately frozen lossless promotion; bit reinterpretation is distinct from numeric conversion.

Portable Verilog emits signed vectors, signed parameters/localparams, explicit sized negative literals, correct arithmetic shifts, and only the casts required by explicit Nodal semantics. Future SystemVerilog emits equivalent `logic signed` declarations and preserves signed packed fields, arrays, memories, functions, parameters, enums, and loop variables without replacing arbitrary-width `SInt` with `int`.

Loops have three categories:

1. ordinary Scala `for`/`foreach` executes during elaboration and accepts Scala values only;
2. `generate(...)` preserves structural repetition and symbolic parameter bounds into target HDL `genvar`/generate loops;
3. a distinct bounded hardware-loop candidate such as `loop(...)` describes repeated operations inside one combinational or clocked region and may lower deterministically to a procedural HDL `for` or verified unrolled operations.

Typed staged ranges provide the same Scala `for` surface without introducing a fourth loop category:

```scala
for index <- 0 until copies do                     // copies: Scala Int; elaboration
for index <- genRange(0, LANES) do                 // LANES: Int or integer Param/Const
for index <- hwRange(0, TAPS) do                   // TAPS: Int or bounded integer Param/Const
```

`genRange` and `hwRange` are frontend wrappers over canonical `generate(...)` and `loop(...)` semantics. Their staged range type, never inspection of the loop body, selects the loop category. A Scala `Int` becomes a concrete staged bound; a legal target-visible integer parameter or constant remains symbolic through IR and HDL. For `hwRange`, a declared finite parameter range supplies the required envelope, so `hwRange(0, TAPS)` needs no redundant `maximum`; when no finite envelope is otherwise available, an explicit `maximum` is required and enforced as part of the legal parameter contract rather than treated as an optimization hint.

The selected loop kind determines which body effects are legal. `genRange` may create structural declarations, instances, connections, and nested generation. `hwRange` performs repeated operations inside an enclosing combinational or sequential region and rejects module, port, instance, or other structural-object creation. A Scala local `val` is only a binder or alias unless it explicitly constructs hardware; it does not automatically become a local HDL signal or select a combinational/sequential process. Plain Scala ranges remain concretely elaborated even when they instantiate modules and are never semantically reclassified from body patterns. Generated process and block labels derive from semantic source names, caller/local binders, roles, and loop indices; generic `COMB_<id>`/`SEQ_<id>` traversal-counter labels are prohibited except for a deterministic collision suffix as the final fallback.

A bounded hardware loop has a finite static/symbolic-static trip count. It cannot create modules or ports, use a runtime signal as its trip count, hide multiple cycles, or contain unbounded/data-dependent termination. Multi-cycle iteration uses explicit FSM/statechart, pipeline, stream, memory, or iterative-operation contracts.


## Multidimensional shaped-value and target-layout architecture

The binding architecture is [ADR 0017](../architecture/0017-semantic-multidimensional-values-and-target-layouts.md). Exact shape/layout candidates and freeze criteria are in [`shaped-values-naming-quality-v0.3-plan.md`](shaped-values-naming-quality-v0.3-plan.md), with a machine-readable candidate in [`shaped-values-naming-quality-v0.3-surface.json`](shaped-values-naming-quality-v0.3-surface.json).

Nodal adopts:

> **Shape is semantic, layout is explicit evidence, and target syntax never decides whether a value is a memory.**

A parameterized multidimensional `Vec` has static rank, positive elaboration/symbolic dimensions, zero-based row-major indexing, exact element type, and deterministic flatten/reshape formulas. `Vec` remains structural; `Mem` alone owns addressable-storage latency, ports, collision, initialization, and mapping semantics.

Portable Verilog lowers a shaped module boundary to one flat packed carrier plus verified element/index views. A flattened `Vec[SInt]` carrier is signless and signed element accesses use deterministic signed views because portable Verilog cannot declare each flattened element independently signed. Future SystemVerilog defaults to unpacked multidimensional ports of packed signed/unsigned elements and may use an explicit packed-dimensional layout for serialization/interoperability.


## Expression materialization and semantic naming architecture

The binding architecture is [ADR 0018](../architecture/0018-expression-materialization-and-semantic-naming.md). Nodal adopts:

> **Do not name an expression merely because the compiler has a node; name only storage, sharing, observability, legality, or an explicit user boundary.**

The default candidate inlines pure single-use expressions while preserving the exact typed operation tree. Shared/observable/target-required values are materialized with reason codes. All emitted state receives a deterministic name derived from explicit/source names, destination role, subsystem role, source origin, or stable digest—not traversal-number `_zz` chains. Expression-level source maps survive inlining.


## Mandatory pre-emission quality-gate architecture

The binding architecture is [ADR 0019](../architecture/0019-mandatory-pre-emission-hardware-quality-gates.md). Nodal adopts:

> **Reject invalid hardware at the highest semantic layer, reverify after every lowering, and accept emitted HDL only with retained evidence.**

Mandatory internal checks cover scope/hierarchy, connections/drivers, widths/signs/shapes, latches, combinational loops, state/reset, CDC/RDC, protocols, parameters/generate/loops, enums/FSMs, pipelines, memories/effects, units/analog/mixed signal, and target capability. Generated HDL is then reparsed and independently checked with the selected Verilator/Icarus/Yosys/OpenVAF/simulator profile. Failed or partial output is diagnostic-only, and plugins cannot disable core verifiers.


## Enum and reusable FSM architecture

The binding architecture is [ADR 0015](../architecture/0015-native-scala-enum-and-hierarchical-fsm.md). The exact enum, encoding, statechart, hierarchy, recursion, backend, report, and freeze candidates are in [`enum-fsm-api-v0.3-plan.md`](enum-fsm-api-v0.3-plan.md), with a machine-readable candidate in [`enum-fsm-api-v0.3-surface.json`](enum-fsm-api-v0.3-surface.json).

Nodal adopts:

> **Names define meaning, explicit encodings define ABI, typed statecharts define control, and every lowering preserves reviewable state identity.**

Preferred source direction:

```scala
enum ControlState derives HwEnum:
  case Idle, Load, Run, Error

val controller = fsm(
  initial = ControlState.Idle,
  encoding = FsmEncoding.Compact
):
  state(ControlState.Idle):
    on(start).goto(ControlState.Load)

  state(ControlState.Load):
    entry:
      count := 0.U
    active:
      count := count + 1.U
    exclusive:
      on(fault).goto(ControlState.Error)
      on(done).goto(ControlState.Run)

  state(ControlState.Error):
    terminal()
```

Exact spellings are compile candidates until Increment 15. Binding semantics are:

- native Scala enum case identity is semantic; Scala `ordinal` is never the HDL encoding contract;
- a canonical enum encoding defines ports, parameters, aggregates, memories, protocols, and library ABI;
- sparse/custom values are explicit and safe decode returns typed value plus validity;
- local FSM storage may use compact, one-hot, Gray, custom, or explicit locked Auto encoding without changing public enum values;
- portable Verilog and Verilog-AMS use vector/integer storage plus member `localparam`s; future SystemVerilog uses `typedef enum logic` with identical values;
- enum module configuration values remain overrideable parameters, while enum member meanings remain non-overridable localparams;
- flat manual enum-register FSMs and high-level statecharts lower into the same target-neutral IR;
- no hidden boot state is introduced; reset entry behavior and illegal-state recovery are explicit;
- transitions are mutually exclusive by default and ordered priority is opt-in;
- reusable definitions are immutable, typed, separately compilable, and free of accidental dynamic capture;
- nested submachines, parallel regions, typed completion, timed/protocol waits, finite structural recursion, and explicit bounded call/return stacks are analyzable graph constructs;
- unbounded structural or runtime recursion is rejected;
- graph verification covers coverage, reachability, dead ends, overlap, drivers, completion, join deadlock, recursion, encoding, domains, effects, and backend capability;
- hierarchy flattening, state minimization, recoding, and retiming are explicit verified optimization passes rather than frontend side effects;
- enum/FSM reports preserve state/case names, encoding maps, transitions, source locations, waveforms, coverage IDs, and formal counterexample reconstruction.


## Automatic pipeline architecture

The proposed architecture is [ADR 0008](../architecture/0008-automatic-pipeline-architecture.md). The candidate API, staged delivery plan, and freeze criteria are in [`automatic-pipeline-api-v0.3-plan.md`](automatic-pipeline-api-v0.3-plan.md), with a machine-readable candidate in [`automatic-pipeline-api-v0.3-surface.json`](automatic-pipeline-api-v0.3-surface.json). The pipeline candidate depends on ADR 0009 and the core-semantics v0.3 plan; Increment 15 freezes both surfaces in one gate.

Nodal adopts:

> **Explicit transaction semantics, automatic stage placement, reviewable schedules.**

Directional source:

```scala
val result = pipe(
  input = Txn(a = a, b = b, c = c, tag = tag),
  target = 500.MHz,
  latency = Latency.Auto,
) { x =>
  Result(data = (x.a + x.b) * x.c, tag = x.tag)
}
```

The compiler automatically balances reconvergent operands and delays `tag` to the result transaction. `value.delay(3)` remains the simple explicit-delay form.

The protocol type defines transport semantics:

- plain transaction: fixed-rate, one transaction each active cycle;
- `Valid[T]`: bubbles without backpressure;
- `Stream[T]`: elastic ready/valid with backpressure.

Rules to freeze in public API v0.3:

- all dynamic inputs enter through one typed transaction and are sampled together;
- automatic scheduling is initially acyclic, feed-forward, single-domain, and initiation-interval one;
- arithmetic, ordering, widths, rounding, exceptions, and resource ownership are preserved exactly;
- sidebands, predicates, tags, and valid state are transported automatically only to their uses;
- fixed/valid published interfaces expose exact or bounded latency;
- elastic interfaces expose minimum latency, capacity, throughput, fall-through, and ready-path behavior;
- payload registers are resetless by default while validity/control state follows the current domain reset contract;
- CDC/RDC, analog sampling, memories, user state, side effects, commit barriers, and hard stage anchors are scheduling barriers;
- frequency-driven scheduling requires an applicable versioned timing model and never claims timing closure from an estimate;
- timing-affecting symbolic parameters require a finite envelope and one envelope-safe schedule so native parameterized HDL remains one module; silent clone-per-value specialization is forbidden;
- schedule reports and hashes make inserted stages, buffers, alignment delays, model inputs, and microarchitecture changes reviewable;
- general HLS, loop pipelining, silent sharing, arithmetic reassociation, and algorithm rewriting are outside the initial contract.

Candidate controls are `pipe`, `delay`, `Latency.Auto`, `Latency.Exact`, `Latency.Range`, `Throughput.EveryCycle`, ready-path policy, `stage(value)` as a hard cut, `sameStage { ... }`, and typed fixed/variable-latency operator contracts. Increment 14 compares exact pipeline forms against the Increment 13 semantic candidates; Increment 15 freezes the unified v0.3 surface and diagnostics before scheduler implementation.


## Pure-digital backend and open-source verification

The binding architecture is [ADR 0010](../architecture/0010-digital-verilog-open-source-verification.md). The complete tool, capability, simulation, synthesis, equivalence, formal, and CI plan is in [`digital-verilog-open-source-verification-plan.md`](digital-verilog-open-source-verification-plan.md), with the public candidate in [`digital-backend-v0.3-surface.json`](digital-backend-v0.3-surface.json).

Nodal classifies each complete design as digital-only, analog-only, mixed-signal, or unsupported. The v0.3 backend candidate is:

```scala
Backend.Auto
Backend.Verilog
Backend.VerilogA
Backend.VerilogAMS
```

`Backend.Auto` selects portable Verilog for digital-only designs, Verilog-A for analog-only designs, and Verilog-AMS for mixed-signal designs. Selection is deterministic and recorded in the emission manifest; it never depends on locally installed tools.

The first digital profile is a conservative synthesizable Verilog-2005-style subset. High-level aggregates and protocols are flattened deterministically, while symbolic parameters, hierarchy, clocks/resets, CDC/RDC, memories, and automatic pipeline structures remain explicit and reviewable.

Open-source verification exercises generated HDL rather than a separate frontend model:

- Verilator for strong lint and fast compiled simulation;
- Icarus Verilog for independent event-driven parse/elaboration/simulation;
- Yosys for synthesis, structural checks, netlists, and equivalence;
- SBY for safety, cover, induction, and selected liveness proofs;
- optional cocotb interoperability alongside the primary Scala simulation API.

Required CI retains tool versions, commands, hashes, logs, waveforms, synthesis reports, equivalence results, and counterexamples. A future explicit SystemVerilog profile may be added separately; it cannot replace the portable Verilog path.


## Future formal-verification architecture

The binding architecture is [ADR 0014](../architecture/0014-target-neutral-formal-verification.md). The deferred public API, property-IR, harness, task, adapter, evidence, replay, and conformance plan is in [`formal-verification-v0.1-plan.md`](formal-verification-v0.1-plan.md), with a machine-readable candidate in [`formal-verification-v0.1-surface.json`](formal-verification-v0.1-surface.json).

Nodal adopts:

> **Author properties in Nodal semantics, preserve them in typed IR, lower only to declared tool capabilities, and retain proof and counterexample evidence.**

The existing architecture is already scalable because MLIR is authoritative; clock/reset domains, CDC/RDC, protocols, parameters, effects, and source locations are explicit; the digital backend has a formal profile; tool adapters are versioned and isolated; and proof evidence participates in manifests and caches.

The remaining future-facing contract is the user-authored property layer. It is intentionally deferred and must remain independent of SVA and SBY spelling. The future gate covers:

- assert, assume, cover, property IDs/groups, and explicit simulation/formal inclusion;
- lexical or explicit clock domains, sampled edges, reset enable/disable, and history validity;
- `past`/edge/change/stability/init/history-validity operations and a bounded typed temporal subset;
- symbolic sequence/constants, initial assumptions, fairness, parameter cases, and legal environment contracts;
- sidecar or embedded harnesses, stable verification exports, memory/black-box/external-operation models, and compositional require/ensure contracts;
- BMC, prove/induction, cover, and capability-gated liveness tasks through pluggable formal adapters;
- per-property proven/failed/covered/inconclusive/unsupported/timeout/tool-error states;
- vacuity, over-constraint, assumption, coverage, counterexample, source-map, and replay evidence.

Nodal may selectively reuse CIRCT `verif` and `ltl` operations when the pinned revision preserves the frozen Nodal semantics. Nodal-owned formal operations remain valid where CIRCT or a selected runner lacks a required capability.

Increment 67 remains limited to Yosys/SBY integration, compiler-generated hooks, equivalence, and core property suites. It must preserve the target-neutral property seam but does not freeze or implement a user-authored formal API. The deferred formal phase may be pulled forward once its listed prerequisites are complete; it is not required for the initial core preview or the AMS-to-FPGA milestone.


## AMS-to-FPGA approximation architecture

The binding architecture is [ADR 0011](../architecture/0011-ams-fpga-approximation-validation.md). The complete capability, solver, numeric, validation, FPGA implementation, and HIL plan is in [`ams-fpga-validation-plan.md`](ams-fpga-validation-plan.md), with a machine-readable candidate in [`ams-fpga-validation-surface.json`](ams-fpga-validation-surface.json).

Nodal adopts:

> **Reference AMS semantics, explicit approximation contract, bounded evidence, synthesizable realization.**

An FPGA cannot execute general continuous-time Verilog-A/Verilog-AMS behavior directly. Nodal may instead transform a supported analog or mixed-signal model into an explicitly sampled, discrete-time, finite-precision digital approximation.

Candidate direction:

```scala
val approximation = FpgaApproximation(
  domain = fpga,
  samplePeriod = 10.ns,
  solver = Solver.Trapezoidal,
  numeric = FixedPointPolicy.Auto(
    error = ErrorBudget(absolute = 1.mV, relative = 0.1.percent),
    rounding = Rounding.NearestEven,
    overflow = Overflow.Saturate
  ),
  envelope = ValidationEnvelope(...),
  target = FpgaTarget.Open("reference")
)

val hardwareModel = Nodal.approximate(new ControlledPlant, approximation)
val rtl = Nodal.emit(hardwareModel, EmitOptions(backend = Backend.Verilog))
```

Exact names are deferred to Increment 96. Binding rules are:

- approximation is explicit and never selected by `Backend.Auto`;
- the original Verilog-A/Verilog-AMS or high-precision Nodal result remains the reference;
- the initial supported subset normalizes into deterministic state-space, transfer-function, or explicit-ODE recurrences;
- arbitrary DAEs/algebraic loops, hidden state, adaptive time, unsupported stiff systems, transistor/PVT/parasitic behavior, unsupported noise, and sub-sample ideal events fail explicitly;
- sample period, solver, state/reset, fixed-point formats, ranges, rounding/overflow, rate relationships, event policy, target, and validation envelope are versioned contract inputs;
- one schedule and numeric/resource plan must cover the legal symbolic parameter envelope; clone-per-value specialization is not the default;
- automatic pipelines may meet sample deadlines but cannot change recurrence, numeric, protocol, clock/reset, or event semantics;
- multi-rate partitions use explicit hold/interpolation/decimation/rate bridges and preserve CDC/RDC provenance;
- placed hardware must complete each update before its sample deadline.

The validation ladder remains separate:

1. AMS reference versus high-precision discrete reference;
2. high-precision discrete versus bit-accurate fixed-point reference;
3. fixed-point reference versus generated RTL using simulation/equivalence/formal;
4. RTL/netlist versus placed FPGA hardware and HIL traces.

Passing FPGA hardware does not erase a discretization or quantization mismatch. Reports retain model/tool hashes, parameters, solver, sample rates, numeric formats, stimuli, tolerances, resource/timing results, bitstream/board identity, and explicit limitations.

This capability validates the generated approximation, digital control, sequencing, calibration, and supported closed-loop behavior inside the declared envelope. It does not by itself validate transistor physics, unmodeled parasitics/PVT/mismatch, continuous-time behavior between samples, unmodeled noise/jitter, or behavior outside the envelope.


## Plugin and extension architecture

The binding architecture is [ADR 0012](../architecture/0012-versioned-capability-plugin-architecture.md). The complete SPI, manifest, capability, lifecycle, compatibility, packaging, and conformance plan is in [`plugin-spi-v0.1-plan.md`](plugin-spi-v0.1-plan.md), with a machine-readable candidate in [`plugin-spi-v0.1-surface.json`](plugin-spi-v0.1-surface.json).

Nodal adopts:

> **Explicit plugin plan, typed capability graph, deterministic phases, isolated extension boundaries, retained provenance.**

VexiiRiscv proves that an almost-empty hardware host can compose a large architecture from plugins, typed services, and phased contributions. Nodal retains local host composition, optional/multiple services, and late aggregation, but replaces runtime class identity, mutable cross-plugin access, manual retain/release ordering, and in-process-only loading with versioned manifests, stable capability keys, deterministic resolution, lockfiles, phase contexts, and separate Scala/native/process boundaries.

Plugin categories are:

- `DesignPlugin`: local configurable design/subsystem composition through public Nodal APIs;
- `FrontendPlugin`: approved namespaced metadata, external-operation, attribute, lint, diagnostic, and helper descriptors;
- `CompilerPlugin`: MLIR passes, analyses, dialects, verifiers, and named compiler extension points;
- `BackendPlugin`: explicitly selected output backend and capability profile;
- `ToolAdapterPlugin`: out-of-process simulator, synthesis, formal, FPGA, programmer, board, HIL, waveform, or reporting integration.

A reusable model library is not a plugin. It remains passive source/data content. A project may publish a separately enabled companion plugin with its own identity and compatibility contract.

Directional design-composition shape:

```scala
object FetchService extends CapabilityKey[FetchApi](
  id = "com.example.cpu.fetch",
  version = 1,
  cardinality = ExactlyOne
)

object DecodeRules extends ContributionKey[DecodeRule](
  id = "com.example.cpu.decode-rules",
  version = 1
)

final class BranchPlugin(config: BranchConfig) extends DesignPlugin:
  override val descriptor =
    plugin("com.example.nodal.branch", version = "1.0.0")
      .requires(FetchService)
      .contributes(DecodeRules)

  override def declare(ctx: DeclareContext): Unit =
    ctx.contribute(DecodeRules, branchRules(config))

  override def elaborate(ctx: ElaborateContext): Unit =
    val fetch = ctx.require(FetchService)
    // Construct hardware through public Nodal APIs.

val subsystem = DesignHost(
  plugins = Seq(FetchPlugin(...), DecodePlugin(...), BranchPlugin(...))
).build()
```

Exact syntax is deferred to Increment 79. Binding rules are:

- plugin and capability identities are stable globally qualified strings with independent versions;
- provider cardinality, qualifier, conflicts, replacements, options, and compatibility are resolved before executable code loads;
- a local host owns one immutable plugin plan and capability scope; nested hosts import/export capabilities explicitly;
- design contributions are typed, append-only, source-located, and closed at declared phases;
- discovery and resolution execute no plugin code;
- lifecycle phases are `discover`, `resolve`, `configure`, `declare`, `elaborate`, `transform`, `verify`, `emit`, `run`, and `report`;
- native plugins wrap MLIR pass/dialect plugin APIs and require exact pinned Nodal/LLVM/MLIR/CIRCT build compatibility;
- out-of-process transforms and tool adapters use versioned protocols and cannot leave partially accepted compiler state after crash, timeout, or malformed output;
- third-party backends are explicit in SPI v0.1 and do not silently join `Backend.Auto`;
- plugin graph/order/options/artifact/toolchain/process hashes participate in build manifests, provenance, release evidence, and cache invalidation;
- classpath scanning, process-global hosts, concrete-plugin lookup, direct mutable access, public retain/release ordering, core-semantic override, and hidden core-to-plugin dependency are rejected.

The plugin SPI gate freezes manifest and lockfile schemas, capability cardinality, local design-host behavior, native/process compatibility, diagnostics, trust classes, and extension boundaries before implementation.


## Target-HDL optimization-pass architecture

The binding architecture is [ADR 0013](../architecture/0013-structured-hdl-optimization-pass-architecture.md). The complete pass descriptor, extension-point, structured-IR, preservation, proof, profile, diagnostics, and conformance plan is in [`target-hdl-optimization-pass-v0.1-plan.md`](target-hdl-optimization-pass-v0.1-plan.md), with a machine-readable candidate in [`target-hdl-optimization-pass-v0.1-surface.json`](target-hdl-optimization-pass-v0.1-surface.json).

Nodal adopts:

> **Optimize structured target IR, declare semantic effects, reverify every boundary, and retain proof evidence.**

The target-pass layer is separate from backend selection and builds on the general plugin SPI. Pass kinds are target-neutral Nodal IR, digital target IR, Verilog-A target IR, Verilog-AMS target IR, render-only, and verified reparse passes.

Binding rules are:

- installed passes never execute automatically and never silently join an optimization profile;
- pass identity is a stable ID/version, not an implementation class name;
- pass order comes from locked extension points, dependencies, before/after constraints, conflicts, and deterministic resolution—not discovery or shared-library order;
- digital target IR selectively reuses CIRCT `hw`/`comb`/`seq`/`sv` plus Nodal-owned contracts;
- Verilog-A/Verilog-AMS target IR remains typed and preserves natures, disciplines, nodes, branches, access functions, dimensions, parameters, contributions, continuous-time operators, events/tolerances, noise/analysis identity, digital state, conversions, connect rules, capabilities, hierarchy, and source maps;
- raw semantic text rewriting is rejected unless output is reparsed, reverified, remapped, and proven under the same contract as a structured pass;
- every pass declares required/preserved/invalidated analyses and effects on types/widths, signedness/overflow, parameters/generate, hierarchy/ports, domains/CDC/RDC, protocols/latency, memories/effects, dimensions, contributions/events/noise/connect rules, mixed-signal provenance, source maps, and backend capabilities;
- ordinary profiles preserve symbolic parameters and one module per structural implementation; specialization is explicit, receives distinct identity, and requires case-specific equivalence;
- digital optimization uses Yosys equivalence, parameter matrices, latency/protocol-aware checks, SBY, and Verilator/Icarus regression as required;
- analog/AMS optimization uses typed equation/contribution/event/noise/connect-rule invariants plus required DC/AC/transient/noise/event differential validation and explicit rejection when no sound method exists;
- `Backend.Auto` selects only a backend; a separately explicit optimization profile selects a versioned pass pipeline;
- pass pipeline, options, IR versions, before/after hashes, source-map changes, analysis invalidation, proof evidence, tool versions/commands, and deterministic pipeline hash participate in manifests, provenance, release evidence, and caches.

Candidate profiles are none, canonical, portable, simulation, synthesis, formal, FPGA, and custom. Exact names, configuration APIs, descriptors, extension points, diagnostics, and lockfile fields are frozen by Increment 83 before execution is implemented.


## Core, plugin, and future library boundary

```text
user project
    ├── depends directly on Nodal core
    ├── may select zero or more passive Nodal libraries
    └── may explicitly enable zero or more executable Nodal plugins

libraries ───────────────► published core APIs
plugins ─────────────────► published core SPI/APIs
plugins ── optional ─────► published libraries
core ──X─────────────────► libraries or plugins
libraries ──X────────────► plugin implementations
```

- `core/` contains the language/API, plugin SPI and resolver, construction frontend, MLIR bridge/compiler, diagnostics, built-in backends, simulation API, future formal property/harness/task services, adapters, and mandatory tests.
- `libraries/` is reserved for optional passive reusable models, interfaces, helpers, and verification packages.
- `plugins/` is reserved for optional executable extension bundles or conformance fixtures; production plugins may live in independent repositories.
- A core-only project must compile with no library or plugin checkout/artifact.
- Future libraries and plugins receive no privileged access to `internal`, frontend, compiler, backend, or simulator implementation packages beyond their approved SPI/API.
- Installing a library does not enable executable plugin code. A companion plugin has a distinct artifact identity and explicit project configuration.
- Each library or plugin owns independent source roots, tests, documentation, semantic version, compatibility range, license/provenance, and publication metadata.

## Target scalable repository structure

```text
Nodal/
├── .github/workflows/             # CI, release, dependency and conformance jobs
├── build.mill                     # Scala monorepo orchestration
├── mill                           # Pinned Mill wrapper
├── CMakeLists.txt                 # Native compiler root
├── cmake/                         # Shared CMake modules
├── toolchains/                    # Locked Scala/JDK/LLVM/MLIR/CIRCT tools
├── core/
│   ├── scala/
│   │   ├── api/                   # Public target-neutral API
│   │   ├── frontend/              # Construction, hierarchy, naming, domains
│   │   ├── bridge/                # Scala-to-MLIR protocol and nodalc invocation
│   │   ├── cli/                   # JVM CLI
│   │   ├── sim/                   # Simulation/regression API
│   │   ├── formal/                # Deferred property, harness, task, and trace services
│   │   └── testkit/               # Core fixtures and test support
│   ├── compiler/
│   │   ├── include/nodal/         # Dialect, analyses, transforms, conversions
│   │   ├── lib/                   # Native implementations
│   │   ├── tools/nodalc/          # Compiler driver
│   │   └── test/                  # lit/FileCheck/native tests
│   └── integrations/              # OpenVAF, ngspice, commercial adapters
├── libraries/                     # Reserved for future optional packages
├── plugins/                       # Reserved for optional executable extension bundles
├── examples/                      # Analog, mixed-signal, external consumers
├── tests/                         # API, architecture, golden, integration, simulation
├── docs/                          # ADRs, gates, reference, tutorials, roadmap
├── packaging/                     # Core, library, and plugin publication/provenance
└── scripts/                       # Bootstrap, lint, checks, release utilities
```

Empty future-library or plugin directories are not committed merely as placeholders.

## Milestones

- **M0 — Foundation:** reproducible builds, CI, clock/reset plus unified core-semantics/automatic-pipeline API freezes, shaped-value/layout and naming/materialization contracts, mandatory quality-gate policy, digital-backend selection contract, and enforced core/library boundaries.
- **M1 — First vertical slice:** Scala RC model lowers through MLIR and emits validated Verilog-A.
- **M2 — Analog preview:** useful Verilog-A subset with source-semantic analog IR, explicit island/equation/state/event/analysis contracts, open-source compilation, and simulation regression.
- **M3 — Digital/AMS preview:** implicit-domain digital state, exact signed finite-width types, parameterized multidimensional shaped values, elaboration/generate/bounded hardware loops, native typed enums, reusable hierarchical/parallel FSMs, automatic fixed/valid/elastic pipelines, readable HDL without avoidable anonymous-wire chains, portable Verilog with mandatory internal checks plus open-source lint/simulation/synthesis/equivalence and compiler-generated formal verification, CDC/RDC-safe clock/reset architecture, mixed-signal crossings, and Verilog-AMS emission.
- **M4 — Scalable core release:** packaged compiler, complete reference, frozen plugin and target-HDL pass SPIs, deterministic extension/pass graphs, continuous-time solver-capability and model-validity manifests, optimization proof evidence, machine-readable check coverage and waiver inventory, conformance kits, library-author contract, and compatibility policy.
- **M5 — FPGA-accelerated AMS validation:** explicit sampled/fixed-point approximation, four-level reference evidence, open FPGA implementation, HIL runtime, and a published capability/limitations matrix.
- **M6 — User-authored formal verification extension:** frozen formal property API with an immediate-assertion-only synthesis boundary, target-neutral property IR, compositional harness/contracts, pluggable proof engines, vacuity/coverage, typed counterexample replay, property libraries, and conformance evidence.

# Foundation track — Incremental roadmap

The numbered roadmap below is the **Foundation track**. FPGA Productivity, Digital Verification, and Analog/Mixed-Signal Verification are dependent tracks with independent numbering that starts again at 1. Those dependent tracks remain implementation-blocked until every Foundation checkbox is complete. Architecture or public seams discovered while researching a dependent track must be added here rather than hidden in a vendor/tool implementation.


## Foundation nested-checklist and acceptance rules

This presentation applies to the complete numbered Foundation track, not just M0 or Increments 0-41. Foundation Increments 150-151 retain their sole progress entries in the [Foundation-only ASIC/memory readiness plan](asic-memory-dependent-tracks-v0.1-plan.md#foundation-todo). The interleaved optional analog component-library items, dependent-track capability text, other tracks, milestone boundaries, and Foundation completion barrier are unchanged.

Follow [Increment and sub-checklist progress](../../CONTRIBUTING.md#increment-and-sub-checklist-progress). A child is complete only with its own deliverable and applicable evidence, identified in the item or a linked record. A parent stays open while any required descendant, dependency, review, acceptance, demonstration, or separate evidence-closure obligation remains incomplete. Implementation completion alone is not increment completion. A genuine invalidated child is handled under CONTRIBUTING; a formatting migration does not reopen historically accepted work.

Keep original increment numbers, titles, parent lines, anchors, and existing child identities stable. New Foundation children use `F-NNN.A` through `F-NNN.G`, with numeric descendants for independently verifiable deliverables. These IDs are references, not a renumbering or an implicit execution order. Preserve an existing identifier instead of allocating a replacement. Split a child further only when the deliverables can be evidenced separately; keep extensive matrices and procedures in the existing linked plans.

Markdown remains the authoritative editable progress source. Manifests and generated views may reference the same IDs and dependencies but must not become a second editable status ledger. The future documentation consistency work is owned by `F-092.B.3`; no validator, synchronization script, compiler change, or workflow is implemented by this migration.

### Meaning and applicability of Foundation children

**A — Architecture, scope, and extensibility.** Retain evidence of fit with shared typed representations, ownership, compiler boundaries, and the feature's known consumers. Reuse existing infrastructure rather than invent a framework per increment. Identify relevant assumptions, unsupported cases, extension seams and actual prerequisites. Symbolic parameter legality is not established by evaluating defaults, and fixture-specific special cases are not architecture.

**B — Implementation and integration.** Deliver the existing feature-specific requirements at their owning layers: public construction, frontend, bridge, native IR/verifiers, lowering, backend, tools, or documentation. Distinguish a compiled candidate or source-level prototype from end-to-end behavior. Architecture-only, tooling, packaging, and documentation increments retain their actual deliverables rather than acquiring invented HDL implementation.

**C — Correctness, rejection, and predecessor regression.** Exercise positive and boundary cases, malformed or unsupported inputs, source-located diagnostics, and combinations selected from real prerequisites. Use negative or mutation controls for important safety checks. Record which predecessor interactions were selected and why; neither isolated happy-path fixtures nor an unbounded Cartesian product is the requirement.

**D — Independent generated-output validation.** Review applicability separately from execution. For each applicable lane retain actual generated inputs/artifacts, pinned tool identity, exact commands/options, results and capability limits. Keep syntax/elaboration, behavioral simulation, synthesis/structural checks for synthesizable digital output, equivalence/formal evidence, and numerical analog validation as distinct results. Numerical comparisons declare reference models, units, stimuli, analyses, parameter envelopes and tolerances. An internal parser is not an independent compiler; successful compilation is not simulation; simulation is not formal proof. Inconclusive, unsupported, timeout, tool-error and blocked results cannot count as a pass. A required check that cannot run stays blocked, not automatically not applicable.

**E — Optimization review and output quality.** Review generated HDL duplication, avoidable materialization, deterministic readable names and preserved symbolic parameters separately from compiler graph growth, repeated traversal, runtime and memory. A documented "no new optimization required" is valid. Preserve metadata needed by future passes, and link opportunities to their existing owner: canonicalization 45, pass framework 21/82/84, optimizations 85-86, naming 153-157, and benchmarks 96. Optional optimization is never required for correctness; mandatory legalization and verification remain mandatory. Any transformation actually introduced needs semantic-preservation evidence and relevant validation. Analog transformations must preserve state, dimensions, contributions, events, topology and numerical/domain assumptions; digital transformations retain exact widths, signedness, ordering, latency and domain contracts. This review does not pull all advanced optimization work into each feature.

**F — Scalability, determinism, and compatibility.** Select representative cases matching the feature: hierarchy, instances, graph size, rank/shape, symbolic parameters, source-map volume, data length, or artifact count. Retain repeatable output and relevant compatibility evidence. Record measured observations and justified expectations, not invented performance targets or a requirement to run the complete Increment 96 benchmark program for every feature.

**G — Evidence, documentation, and acceptance.** Retain reproduction commands, supported/rejected capability documentation, source/artifact and evidence references, applicable actual public Scala and generated Verilog-* demonstrations, review, verified merge and any separate accepted-evidence closure. Use [completion demonstrations](../../CONTRIBUTING.md#increment-completion-demonstrations) and the centralized [targeted-first CI, hourly monitoring and verified-merge policy](../../AGENTS.md), rather than duplicating that policy in each child. Documentation-only work uses the documented exception when explicitly authorized; this migration authorizes no implementation, CI, monitor or controller.

Applicability decisions identify the feature/profile and rationale. An applicability-review checkbox does not stand in for an execution checkbox. Explicitly optional enhancements and separately owned future qualifications remain outside the current required completion subtree unless included in scope by an approved change; keep their owning increment and restricted current claim visible. Do not silently delete, waive or check a required task to close its parent.

### Independent validation staging without dependency cycles

OpenVAF compile integration remains in Increment 48; ngspice/OSDI numerical integration remains in 49, with regression/portability qualification in 52-53 and continuous-time closure in 141-142. The digital [tool-integration plan](digital-verilog-open-source-verification-plan.md) assigns Verilator/Icarus to 66 and Yosys/SBY to 67. Mixed-signal simulator capability negotiation remains in 75, with conformance in 78. Reuse these plans and the [continuous-time plan](continuous-time-ams-v0.1-plan.md); tool names do not imply universal construct support.

Earlier compiler/IR/backend increments may establish only their explicitly bounded compiler/structural profile, retaining generated witnesses and a named later qualification obligation. For 42-47, carry those witnesses into 48-49/52; for 54-65, carry applicable digital witnesses into 66-67; for pre-adapter AMS work, carry applicable witnesses into 75/78. Naming metadata 153-157 is a prerequisite of backends 65/72, so its own acceptance must not depend on those later backend parents: verify metadata at available compiler boundaries and assign target parity to 65/72. A required original proof or executed test is not waived by this staging rule. Where it needs unavailable infrastructure, keep that obligation blocked or establish an independently schedulable infrastructure checkpoint without a parent-cycle; do not fabricate test credit.

Later qualification owners consume the earlier retained artifacts and close their own required execution children. No earlier increment gains a reverse dependency on the entire later increment merely to request that qualification. Parent numbers and references to consumers are not themselves dependency edges. Check actual prerequisite/checkpoint direction before scheduling, preserve pre-existing dependencies, and never treat limited early acceptance as final simulator, synthesis or formal qualification.

### Historical acceptance breakdown

Checked `H` children below summarize previously accepted deliverables or documentary boundaries and refer to existing records; they are not retrospective A-G qualification. Original scope statements and evidence remain intact. No newly invented optimization, scale, numerical, synthesis or formal obligation is checked or inserted unfinished under a completed parent. Historical tool runs, accepted hashes, review kind, merge identities, and demonstrations remain immutable.

In particular, Increment 41 retains only the accepted module-local pure scalar Real/Integer function compiler/Verilog-A profile in its [closure record](../implementation/increment41-evidence-closure.md). Numerical analog simulation, general Verilog-AMS, synthesis, broader function forms and interprocedural optimization are not implied. Older design gates and architecture-only corrections likewise do not claim executable backends or tools. Any discovered evidence-linkage or qualification gap is documented as separately scoped Foundation follow-up under 92 or the already named qualification owner, not as a rewrite of historical acceptance.


## Phase 0 — Repository, toolchains, architecture, and API contract

- [x] **Increment 0 — Roadmap bootstrap**
  - Add this checkbox roadmap, fixed architecture, milestones, and repository structure.
  - [x] **F-000.H — Historical acceptance breakdown: Roadmap bootstrap document.** This roadmap is the delivered bootstrap artifact; this historical child does not assert compiler or test execution.

- [x] **Increment 1 — Project charter and standards baseline**
  - Establish goals, non-goals, terminology, Verilog-AMS 2023 baseline, Verilog-A profile, and core/library scope.
  - Evidence: [`README.md`](../../README.md) and commit [`cb4c55f`](https://github.com/pysolvesemi/Nodal/commit/cb4c55f2e12305be3e92b66df0b499b1d4932c2c).
  - [x] **F-001.H — Historical acceptance breakdown: Charter and standards baseline.** Retain the charter/standards and core-library boundary in the existing README/commit evidence below.

- [x] **Increment 2 — Architecture decision records**
  - Record the Scala/native split, authoritative MLIR, out-of-tree dialect, selective CIRCT reuse, textual bridge, backend profiles, and core/library boundary.
  - Evidence: [`docs/architecture/README.md`](../architecture/README.md) and commit [`8052eca`](https://github.com/pysolvesemi/Nodal/commit/8052eca5de87244566f84d7d776d513991ef2e83).
  - [x] **F-002.H — Historical acceptance breakdown: Architecture decision records.** Retain the recorded Scala/native/MLIR/backend and core-library decisions, not new backend implementation claims.

- [x] **Increment 3 — Scalable repository skeleton**
  - Create required core modules and enforce architecture/dependency boundaries without populating optional libraries.
  - Evidence: [`core/modules.toml`](../../core/modules.toml), [`scripts/check_architecture.py`](../../scripts/check_architecture.py), [`tests/architecture/`](../../tests/architecture/), and commit [`300d389`](https://github.com/pysolvesemi/Nodal/commit/300d38949d2152af1c310caf528d0aea81eb0063).
  - [x] **F-003.H — Historical acceptance breakdown: Repository and ownership skeleton.** Retain the required module/dependency-boundary deliverables and their existing architecture-check evidence.

- [x] **Increment 4 — Modern Scala 3 build bootstrap**
  - Pin Scala 3, JDK, Mill, API/frontend/bridge/CLI/test modules, and a smoke test; no Scala 2 cross-build.
  - Evidence: [`build.mill`](../../build.mill), [`docs/development/scala-build.md`](../development/scala-build.md), commit [`95b8c91`](https://github.com/pysolvesemi/Nodal/commit/95b8c9155bb92890c04c9e42b68fdf7677432a10), and run [`32350691664`](https://github.com/pysolvesemi/Nodal/actions/runs/32350691664).
  - [x] **F-004.H — Historical acceptance breakdown: Pinned Scala build bootstrap.** Retain the recorded Scala/JDK/Mill modules and smoke-test evidence, not an HDL semantic qualification.

- [x] **Increment 5 — LLVM/MLIR/CIRCT toolchain lock**
  - Pin compatible native revisions, checksums, requirements, discovery, and source-build fallback.
  - Evidence: [`toolchains/lock.json`](../../toolchains/lock.json), [`toolchains/README.md`](../../toolchains/README.md), [`scripts/check_native_toolchain.py`](../../scripts/check_native_toolchain.py), commit [`7ea7c2b`](https://github.com/pysolvesemi/Nodal/commit/7ea7c2b6ea8993f9de58e528cc916d7ac7655272), and run [`32355110533`](https://github.com/pysolvesemi/Nodal/actions/runs/32355110533).
  - [x] **F-005.H — Historical acceptance breakdown: Native toolchain lock.** Retain the compatible revision/checksum/discovery and source-fallback contract and recorded tooling evidence.

- [x] **Increment 6 — Native compiler bootstrap**
  - Build the out-of-tree native project and `nodalc --version` plus native unit tests without language semantics.
  - Evidence: [`CMakeLists.txt`](../../CMakeLists.txt), [`core/compiler/tools/nodalc/`](../../core/compiler/tools/nodalc/), [`docs/development/native-compiler.md`](../development/native-compiler.md), commits [`72f1d8e`](https://github.com/pysolvesemi/Nodal/commit/72f1d8e3603e94dfd1c22a509b6d9c4438cbac2f) and [`1f4a785`](https://github.com/pysolvesemi/Nodal/commit/1f4a785efd99a3d6c2e5b10bb1dd61d2bb9739e8), and run [`32359466870`](https://github.com/pysolvesemi/Nodal/actions/runs/32359466870).
  - [x] **F-006.H — Historical acceptance breakdown: Native compiler bootstrap.** Retain the native project/version/unit-test bootstrap without implying language semantics.

- [x] **Increment 7 — Unified developer commands**
  - Provide stable local/CI commands for bootstrap, Scala, native, checks, clean, diagnostics, and reserved library namespaces.
  - Evidence: [`nodal`](../../nodal), [`scripts/nodal.py`](../../scripts/nodal.py), [`docs/development/commands.md`](../development/commands.md), commits [`f24a074`](https://github.com/pysolvesemi/Nodal/commit/f24a07461d075a16c9967bdec37fb551d5f66f05) and [`080cfd3`](https://github.com/pysolvesemi/Nodal/commit/080cfd38f64e06751ece5c7ca0c432575db4c2fc), and run [`32367451896`](https://github.com/pysolvesemi/Nodal/actions/runs/32367451896).
  - [x] **F-007.H — Historical acceptance breakdown: Unified developer command contract.** Retain the documented command namespace and existing local/CI command evidence.

- [x] **Increment 8 — Continuous integration baseline**
  - Add Scala/native/contracts CI, caching, dependency reports, and core/library boundary enforcement.
  - Evidence: [`.github/workflows/ci.yml`](../../.github/workflows/ci.yml), [`tests/ci/`](../../tests/ci/), [`docs/development/ci.md`](../development/ci.md), commit [`69c31a5`](https://github.com/pysolvesemi/Nodal/commit/69c31a5f4340688dff01a41911ca0a885153bb11), and run [`32396716336`](https://github.com/pysolvesemi/Nodal/actions/runs/32396716336).
  - [x] **F-008.H — Historical acceptance breakdown: CI baseline.** Retain the historical workflow/cache/contracts and boundary-check evidence; no new run is asserted here.

- [x] **Increment 9 — Formatting, linting, and contribution rules**
  - Add pinned Scala/native/Markdown formatting, package visibility, PR/branch policy, and design-gate enforcement.
  - Evidence: [`.scalafmt.conf`](../../.scalafmt.conf), [`.scalafix.conf`](../../.scalafix.conf), [`.clang-format`](../../.clang-format), [`.clang-tidy`](../../.clang-tidy), [`CONTRIBUTING.md`](../../CONTRIBUTING.md), [`scripts/check_increment9.py`](../../scripts/check_increment9.py), and run [`32444753017`](https://github.com/pysolvesemi/Nodal/actions/runs/32444753017).
  - [x] **F-009.H — Historical acceptance breakdown: Formatting and contribution policy.** Retain the accepted style/package/design-gate tooling and contribution rules; future checklist validation is separately scoped under 92.

- [x] **Increment 10 — Public API candidate prototypes**
  - Compile non-functional analog, hierarchy, mixed-signal, parameter, event, and external-library candidates against public core APIs.
  - Evidence: [`CandidateApi.scala`](../../core/scala/api/src/nodal/CandidateApi.scala), [`examples/publicApiCandidates/`](../../examples/publicApiCandidates/), [`examples/externalLibrary/`](../../examples/externalLibrary/), [`NodalPublicApiCandidates-DG-v0.1.md`](../design-gates/NodalPublicApiCandidates-DG-v0.1.md), and runs [`32447235052`](https://github.com/pysolvesemi/Nodal/actions/runs/32447235052) and [`32447239115`](https://github.com/pysolvesemi/Nodal/actions/runs/32447239115).
  - [x] **F-010.H — Historical acceptance breakdown: Inert public candidate prototypes.** Retain compile-only candidate/public-consumer evidence, not functional elaboration or generated HDL.

- [x] **Increment 11 — Public API design gate and v0.1 freeze**
  - Freeze the initial public API, native parameterized HDL contract, backend entry points, compatibility policy, and future library-author subset.
  - Evidence: [`NodalPublicApi-DG-v0.1.md`](../design-gates/NodalPublicApi-DG-v0.1.md), [`public-api-v0.1.json`](../../core/scala/api/public-api-v0.1.json), [`public-api-v0.1.md`](../language-reference/public-api-v0.1.md), [`CompilerApi.scala`](../../core/scala/api/src/nodal/CompilerApi.scala), [`scripts/check_increment11.py`](../../scripts/check_increment11.py), and run [`32455056652`](https://github.com/pysolvesemi/Nodal/actions/runs/32455056652).
  - [x] **F-011.H — Historical acceptance breakdown: Public API v0.1 freeze.** Retain the approved API/profile/library-author contract and recorded compile/freeze evidence.

- [x] **Increment 12 — Clock/reset public API v0.2 freeze and contract fixtures**
  - Use [ADR 0007](../architecture/0007-implicit-clock-reset-domains.md), [`clock-reset-api-v0.2-plan.md`](clock-reset-api-v0.2-plan.md), and [`clock-reset-api-v0.2-surface.json`](clock-reset-api-v0.2-surface.json) as the mandatory architecture and API candidate.
  - Compile candidates for `Clock`, `Reset`, `ClockDomain.external/from/required/generated`, lexical domain application, `Reg`, `Reg.uninitialized`, `RegNext`, `when`/`elsewhen`/`otherwise`, typed `.domain(...)`, `ResetPolicy`, `ClockRelation`, semantic `Cdc`/`Rdc`, `ClockGate`, `ClockMux`, and the quarantined `nodal.lowlevel.process(event)` escape.
  - Publish `NodalClockResetApi-DG-v0.2.md`, a v0.1-to-v0.2 migration note, and an updated machine-readable public API manifest. Supersede `always(clock.rising)` only for ordinary synchronous state; retain genuine analog/event semantics.
  - Add compile-positive fixtures for single/multiple/generated domains, every reset policy, legal level/Gray/pulse/handshake/FIFO/reset crossings, gates/muxes, analog-event separation, and an external-library consumer.
  - Add compile-negative fixtures for missing domains, direct CDC, multi-bit `Cdc.sync`, unsafe pulses, unsupported relationship assumptions, reset-release/reconvergence hazards, Boolean clocks, ordinary `always`, low-level misuse, and ambiguous/multiple state drivers. Freeze stable diagnostic codes and source locations.
  - Keep frontend/backend semantics inert. Mark this increment `[x]` only after every freeze exit criterion in the detailed plan passes CI.
  - Evidence: [`NodalClockResetApi-DG-v0.2.md`](../design-gates/NodalClockResetApi-DG-v0.2.md), [`public-api-v0.2.json`](../../core/scala/api/public-api-v0.2.json), [`clock-reset-diagnostics-v0.2.json`](../../core/scala/api/clock-reset-diagnostics-v0.2.json), [`tests/api/fixtures/increment12/manifest.json`](../../tests/api/fixtures/increment12/manifest.json), [`scripts/check_increment12.py`](../../scripts/check_increment12.py), and status `increment-12/clock-reset-api-v0-2`.
  - Freeze baseline: roadmap **Revision:** 1.12; later roadmap revisions must preserve this completed increment and its evidence.
  - [x] **F-012.H — Historical acceptance breakdown: Clock/reset v0.2 design gate.** Retain the frozen surface, positive/negative compile contracts and revision 1.12 acceptance boundary; frontend/backend semantics were inert.

- [x] **Increment 13 — Core semantic candidate prototypes and architecture comparison**
  - Use [ADR 0009](../architecture/0009-core-semantic-contracts.md), [`core-semantics-api-v0.3-plan.md`](core-semantics-api-v0.3-plan.md), and [`core-semantics-api-v0.3-surface.json`](core-semantics-api-v0.3-surface.json) as the mandatory candidate.
  - Compile and compare elaboration-only Scala values, symbolic `Param`/constant/width/range/generate values, and dynamic hardware values. Compile ordinary Scala elaboration loops, symbolic structural `generate(...)`, a distinct bounded hardware-loop candidate, and vector `map`/`reduce` forms; freeze stage/bound/body legality and dynamic/unbounded-loop diagnostics.
  - Compile lossless unsigned/signed arithmetic, `Bits`/`UInt`/`SInt` declarations, signed/negative literals, signed parameters/localparams/memories/aggregates, symbolic width rules, numeric conversion versus reinterpretation, arithmetic/logical shifts, explicit extend/truncate/wrap/saturate/checked-resize/signedness conversions, and negative mixed-sign/implicit-narrowing fixtures.
  - Compile directionless nested aggregates/vectors, exact port/connection semantics, typed adapters/views, and general plain/`Valid`/`Stream` protocols.
  - Compile rank-one through rank-four `Vec` candidates with positive Scala/symbolic parameter dimensions, multidimensional indexing/slicing/flatten/reshape/map/zip/reduce, signed elements, exact shape connections, explicit `Vec` versus `Mem`, portable-Verilog flat layout, and future-SystemVerilog unpacked/packed layout policies.
  - Compile `TemporaryPolicy`/`NamingPolicy`/`CheckProfile` candidates, safe inlining of pure expression chains, shared/observable/target-required materialization, explicit names versus keep/debug intent, deterministic sink-affinity register names, typed waivers, and negative latch/loop/driver/hierarchy/shape/profile fixtures from ADRs 0018-0019.
  - Compile dimension-safe analog quantities and negative unit equations without exposing verbose dimension types in ordinary source.
  - Compile explicit memory and external-operation contracts covering read latency, read-under-write, masks, ordering, domains, effects, throughput, and model availability. Reject unknown latency/effect in movable pipeline regions.
  - Include an external-library consumer using only public candidate APIs; keep frontend/backend behavior inert.
  - Compile native Scala 3 enum derivation, stable/custom canonical encodings, typed ports/parameters/aggregates/protocols, safe decode, exhaustive switch, portable-Verilog localparam mapping contracts, and future SystemVerilog native-enum contracts.
  - Compile manual enum-register FSMs plus concise flat, reusable, nested, parallel, timed, finite-recursive, and explicit bounded-call-stack statechart candidates from [ADR 0015](../architecture/0015-native-scala-enum-and-hierarchical-fsm.md).

  - Evidence: [`NodalCoreSemanticCandidates-DG-v0.3.md`](../design-gates/NodalCoreSemanticCandidates-DG-v0.3.md), [`CoreSemanticsCandidateApi.scala`](../../core/scala/api/src/nodal/CoreSemanticsCandidateApi.scala), [`tests/api/fixtures/increment13/manifest.json`](../../tests/api/fixtures/increment13/manifest.json), [`scripts/check_increment13.py`](../../scripts/check_increment13.py), PR [#32](https://github.com/pysolvesemi/Nodal/pull/32), and dedicated validation run [32587119017](https://github.com/pysolvesemi/Nodal/actions/runs/32587119017).
  - [x] **F-013.H — Historical acceptance breakdown: Core semantic candidate gate.** Retain the accepted candidate comparison and compile contracts; these prototypes were not executable backends.

- [x] **Increment 14 — Automatic pipeline, Interface/Role, and inout candidate prototypes and architecture comparison**
  - Use [ADR 0021](../architecture/0021-unified-struct-interface-role-and-inout-architecture.md), [`interface-role-inout-ams-v0.1-plan.md`](interface-role-inout-ams-v0.1-plan.md), and [`interface-role-inout-ams-v0.1-surface.json`](interface-role-inout-ams-v0.1-surface.json) as mandatory candidates alongside ADR 0008.
  - Compile and compare directionless storable `Struct` values versus non-storable `Interface` connectivity, named roles, legal digital role inversion, monitor views, nested request/response roles, `master`/`slave` `Valid` and `Stream`, exact role-compatible connection, symbolic interface arrays, and external reusable interfaces.
  - Compile first-class digital `inout` candidates with explicit read/drive/enable semantics, push-pull/open-drain modes, high impedance, split internal tri-state carriers, top-level/black-box pins, hierarchy pass-through, pad adapters, and profile-aware internal resolved-net restrictions.
  - Compile conservative-terminal-only and mixed digital/analog interface candidates with explicit connect/sense/contribute/monitor access, directional analog signal-flow values, and no implicit analog/digital or conservative/signal-flow conversion.
  - Compile `pipe`, `delay`, plain/`Valid`/`Stream` protocols, exact/ranged/auto latency, throughput and ready-path policy, automatic sideband transport and reconvergence balancing, `stage`/`sameStage`, schedule inspection, parameter envelopes, and fixed/variable-latency operators against Increment 13 semantics.
  - Compare current Chisel aggregate/connectable/protocol forms, current SpinalHDL Bundle/Interface/IMasterSlave/Stream/Flow/Analog/inout forms, current SystemVerilog interface/modport/net semantics, and CIRCT `pipeline`/ESI. Retain useful semantics without exposing lower-level graph plumbing or backend syntax.
  - Add compile-positive and negative contracts for role completeness, monitor drive, incompatible roles, protocol mismatch, interface storage, invalid inversion, multiple ordinary drivers, illegal open-drain drive, unsupported internal tri-state, discipline mismatch, sense-only contribution, implicit bridge conversion, flattening collision, and parameter-envelope layout conflict.
  - Prove that arithmetic, aggregate, protocol, interface ABI, inout resolution, quantity, memory, effect, clock/reset, CDC/RDC, native parameterized-module, and AMS topology contracts remain unchanged by the candidate scheduler surface.

  - Evidence: [`NodalPipelineInterfaceCandidates-DG-v0.3.md`](../design-gates/NodalPipelineInterfaceCandidates-DG-v0.3.md), [`PipelineInterfaceCandidateApi.scala`](../../core/scala/api/src/nodal/PipelineInterfaceCandidateApi.scala), [`tests/api/fixtures/increment14/manifest.json`](../../tests/api/fixtures/increment14/manifest.json), [`scripts/check_increment14.py`](../../scripts/check_increment14.py), PR [#36](https://github.com/pysolvesemi/Nodal/pull/36), and dedicated validation run [32639805716](https://github.com/pysolvesemi/Nodal/actions/runs/32639805716).
  - [x] **F-014.H — Historical acceptance breakdown: Pipeline/interface candidate gate.** Retain the accepted public candidate and boundary comparison, not scheduler or interface-lowering implementation.

- [x] **Increment 15 — Unified core semantics, Interface/Role/inout, and automatic pipeline public API v0.3 freeze**
  - Publish `docs/design-gates/NodalCoreSemanticsPipelineApi-DG-v0.3.md`, migration notes, and an updated machine-readable public API manifest using ADRs 0009/0008/0021 and the core, pipeline, and interface candidate plans/surfaces.
  - Freeze value stages; ordinary Scala elaboration loops; symbolic target `generate`; bounded hardware iteration and collection operations; `Bits`/`UInt`/`SInt`; exact signed declaration/literal/parameter/memory/expression/shift/conversion/reinterpretation and Verilog-family lowering rules; lossless numeric/width semantics; explicit lossy conversions; parameterized multidimensional `Vec` shape/index/flatten/reshape and target layout; explicit `Vec` versus `Mem`; physical quantities; memory/external effect contracts; native Scala enums; canonical enum ABI/safe decode/exhaustive selection; flat and reusable hierarchical/parallel/timed/bounded-recursive FSMs; local FSM encoding/illegal-state policies; safe expression inlining, materialization reasons, semantic naming, source-span maps, Fast/Default/Release check profiles and typed waivers; `pipe`/`delay`; latency/throughput/ready policy; stage constraints; parameter-envelope scheduling; and schedule evidence.
  - Freeze directionless storable `Struct` versus non-storable `Interface`, generic named `Role`, `master`/`slave`/`monitor`, nested roles, full `Valid`/`Stream` ownership, exact interface connection/adapters, interface arrays, logical Interface ABI/source mapping, deterministic flattening, and external-library extension rules.
  - Freeze first-class digital `inout` read/drive/high-impedance/resolution semantics, initial push-pull/open-drain modes, split-tristate boundary adapters, black-box/hierarchy pass-through, multiple-driver restrictions, profile-aware internal tri-state capability, and stable diagnostics. Keep digital inout distinct from conservative terminals and directional analog signal-flow values.
  - Freeze conservative boundary terminal versus internal node/branch semantics, analog role access, mixed-signal interfaces, explicit bridge requirements, continuous-time island/domain provenance, and backend capability obligations without exposing SystemVerilog or simulator-specific syntax in the source API.
  - Freeze `Backend.Auto`, `Backend.Verilog`, design-kind reporting, explicit synth/sim/formal digital profiles, portable flattened interface ABI, and future native SystemVerilog interface/modport parity requirements from ADR 0010, ADR 0021, and the digital-backend candidate.
  - Add positive and negative compile contracts for every candidate category, including external-library use, stable diagnostic codes/source locations, v0.1/v0.2 migration behavior, role/inout/AMS-interface misuse, and native-versus-flat layout candidates.
  - Keep elaboration, scheduler, interface IR, resolution/topology analysis, digital/AMS backends, and simulator behavior inert. Mark this increment `[x]` only when the unified gate, manifests, fixtures, diagnostics, and CI satisfy all linked exit criteria.

  - Evidence: [`NodalCoreSemanticsPipelineApi-DG-v0.3.md`](../design-gates/NodalCoreSemanticsPipelineApi-DG-v0.3.md), [`public-api-v0.3.json`](../../core/scala/api/public-api-v0.3.json), [`public-api-diagnostics-v0.3.json`](../../core/scala/api/public-api-diagnostics-v0.3.json), [`public-api-v0.2-to-v0.3.md`](../migrations/public-api-v0.2-to-v0.3.md), [`tests/api/fixtures/increment15/manifest.json`](../../tests/api/fixtures/increment15/manifest.json), [`scripts/check_increment15.py`](../../scripts/check_increment15.py), PR [#40](https://github.com/pysolvesemi/Nodal/pull/40), and dedicated validation run [32645312790](https://github.com/pysolvesemi/Nodal/actions/runs/32645312790).
  - [x] **F-015.H — Historical acceptance breakdown: Unified v0.3 API freeze.** Retain the unified frozen semantics/manifests/migration and compile contracts; execution/lowering remained inert at this gate.

## Phase 1 — Compiler vertical slice

- [x] **Increment 16 — Elaboration, hierarchy, shape, and lexical domain-context kernel**
  - Add deterministic `Struct`/`Interface` kind ownership, interface construction close, exported-role requirements, recursive role expansion, interface storage rejection, resolved-net endpoint registration, conservative-terminal topology ownership, and logical Interface ABI paths without globals or JVM identity.
  - Implement deterministic module construction, ownership, lifecycle, shaped-value rank/dimension capture, structural `Vec` versus `Mem` intent, transactional construction close, default-domain requirements, lexical domain stack, single-domain inheritance, named multi-domain requirements, typed bindings, and root-domain validation without public Scala implicits, globals, thread-locals, or JVM identity.
  - Evidence: PR [#41](https://github.com/pysolvesemi/Nodal/pull/41), dedicated validation run [32693824293](https://github.com/pysolvesemi/Nodal/actions/runs/32693824293), and Core CI run [32693824396](https://github.com/pysolvesemi/Nodal/actions/runs/32693824396).
  - [x] **F-016.H — Historical acceptance breakdown: Deterministic construction kernel.** Retain the accepted construction/ownership/domain/shape boundary and its recorded evidence without expanding downstream execution claims.

- [x] **Increment 17 — Source spans, semantic naming, and origin graph**
  - Capture Scala declaration/member names and expression spans; build stable origin/sink-affinity metadata; define deterministic names for modules, declarations, shaped elements/views, domains, generated clock/reset ports, synchronizers, FIFOs, reset controllers, crossings, pipeline/FSM state, anonymous registers, and required temporaries. Prohibit traversal-counter-only normal names and retain expression-level source maps when nodes are inlined.
  - Evidence: [`SemanticOriginKernel.scala`](../../core/scala/api/src/nodal/SemanticOriginKernel.scala), [`SemanticOriginTests.scala`](../../core/scala/testkit/test/src/nodal/SemanticOriginTests.scala), [`NodalSemanticOriginNaming-DG-v1.0.md`](../design-gates/NodalSemanticOriginNaming-DG-v1.0.md), PR [#42](https://github.com/pysolvesemi/Nodal/pull/42), dedicated validation run [32722646172](https://github.com/pysolvesemi/Nodal/actions/runs/32722646172), and Core CI run [32722646224](https://github.com/pysolvesemi/Nodal/actions/runs/32722646224).
  - [x] **F-017.H — Historical acceptance breakdown: Origin and semantic-naming kernel.** Retain the accepted source-origin/naming boundary; later function-local capture and full target parity remain owned by 153-157 and 65/72.

- [x] **Increment 18 — Nodal MLIR dialect skeleton**
  - Register the out-of-tree dialect, TableGen organization/docs, generic parser/printer, and a verified placeholder operation.
  - Evidence: [`NodalMlirDialectSkeleton-DG-v1.0.md`](../design-gates/NodalMlirDialectSkeleton-DG-v1.0.md), [`increment18-mlir-dialect-skeleton.md`](../implementation/increment18-mlir-dialect-skeleton.md), PR [#45](https://github.com/pysolvesemi/Nodal/pull/45), dedicated validation run [32767361722](https://github.com/pysolvesemi/Nodal/actions/runs/32767361722), and Core CI run [32767361651](https://github.com/pysolvesemi/Nodal/actions/runs/32767361651).
  - [x] **F-018.H — Historical acceptance breakdown: Native dialect skeleton.** Retain the registered dialect/placeholder/parser-printer bootstrap and recorded native evidence.

- [x] **Increment 19 — Core MLIR module, port, parameter, and domain model**
  - Add canonical Interface IR definitions/instances/roles/member access, full `Valid`/`Stream` channel identity, logical interface ABI metadata, digital resolved-net/read/driver/drive-mode operations, conservative terminal/node/branch/access operations, and explicit mixed-signal bridge operations while keeping target layouts separate.
  - Add target-neutral modules, ports, symbols, instances, symbolic parameters, signless/unsigned/signed finite-width types and constants, ranked shaped types with symbolic dimensions, canonical index/flatten/layout and structural-storage metadata, expression origin/materialization/observability metadata, structural generate regions, bounded hardware-iteration regions with typed induction variables/effects, semantic enum types/cases/canonical encodings, FSM definitions/regions/states/transitions/actions/completion/encoding policies, domain requirements/bindings, clock/reset relationships, state ownership, timing provenance, and crossing operations/types. Reuse CIRCT only after semantic comparison.
  - Evidence: [`NodalCoreMlirModel-DG-v1.0.md`](../design-gates/NodalCoreMlirModel-DG-v1.0.md), [`increment19-core-mlir-model.md`](../implementation/increment19-core-mlir-model.md), PR [#46](https://github.com/pysolvesemi/Nodal/pull/46), dedicated validation run [32829155720](https://github.com/pysolvesemi/Nodal/actions/runs/32829155720), and Core CI run [32829155633](https://github.com/pysolvesemi/Nodal/actions/runs/32829155633).
  - [x] **F-019.H — Historical acceptance breakdown: Core MLIR model.** Retain the accepted typed core model and representation boundary in the linked implementation record; representation does not imply every later backend consumer.

- [x] **Increment 20 — Scala-to-MLIR bridge**
  - Lower deterministic construction state to versioned textual MLIR with source locations and invoke `nodalc` through a clear process protocol.
  - [x] **F-020.H — Historical acceptance breakdown: Versioned bridge and process boundary.** Retain the serialized closed construction snapshot, exact supported Increment 19 operations, pre-launch rejection and native-process handling in [the implementation/closure record](../implementation/increment20-scala-mlir-bridge.md).

- [x] **Increment 21 — Native parse, staged semantic verification, and pass pipeline**
  - Parse Nodal MLIR; implement mandatory construction-closure, driver/assignment coverage, latch, combinational-cycle, hierarchy, width/sign/shape/layout/storage, parameter/generate/loop, enum/FSM, clock/reset/CDC/RDC, protocol/pipeline, memory/effect, analog/mixed-signal, and target-capability verifiers; run registered passes with analysis invalidation/reverification; print normalized IR; and expose explicit lit/FileCheck-friendly gate pipelines. Preserve the last accepted state transactionally on failure.
  - Evidence: [`NodalNativeSemanticPipeline-DG-v1.0.md`](../design-gates/NodalNativeSemanticPipeline-DG-v1.0.md), [`increment21-native-semantic-pipeline.md`](../implementation/increment21-native-semantic-pipeline.md), implementation PR [#50](https://github.com/pysolvesemi/Nodal/pull/50), closure PR [#51](https://github.com/pysolvesemi/Nodal/pull/51), dedicated validation run [32884043819](https://github.com/pysolvesemi/Nodal/actions/runs/32884043819), and Core CI run [32884043761](https://github.com/pysolvesemi/Nodal/actions/runs/32884043761).
  - [x] **F-021.H — Historical acceptance breakdown: Native staged semantic pipeline.** Retain the accepted pipeline/verifier boundary and separate historical closure evidence; do not infer later source/backend capability merely from an IR seam.

- [x] **Increment 22 — Cross-layer diagnostic mapping**
  - Include stable interface/role/inout/AMS codes for unstorable interfaces, missing roles/members, incompatible roles, monitor drive, invalid inversion, multiple ordinary drivers, illegal open-drain drive, unsupported resolution, hierarchy-pass-through failure, discipline/access mismatch, implicit bridge conversion, and interface-layout collisions.
  - Map construction, driver/latch/cycle/hierarchy, shape/rank/layout/storage/index, materialization/naming/source-span, parser, verifier, pass, backend, external-tool, signed literal/conversion/mixed-sign/width/shift, loop stage/bound/body/dependency/effect/profile, enum encoding/decode/exhaustiveness, FSM graph/transition/recursion/illegal-state, domain-binding, CDC, RDC, gate/mux, protocol/pipeline, memory/effect, analog/mixed-signal, and waiver diagnostics back to Scala locations, hierarchy/index paths, and stable codes.
  - Evidence: [`NodalCrossLayerDiagnostics-DG-v1.0.md`](../design-gates/NodalCrossLayerDiagnostics-DG-v1.0.md), [`increment22-cross-layer-diagnostic-mapping.md`](../implementation/increment22-cross-layer-diagnostic-mapping.md), PR [#53](https://github.com/pysolvesemi/Nodal/pull/53), dedicated validation run [32944621396](https://github.com/pysolvesemi/Nodal/actions/runs/32944621396), and Core CI run [32944621448](https://github.com/pysolvesemi/Nodal/actions/runs/32944621448).
  - [x] **F-022.H — Historical acceptance breakdown: Cross-layer diagnostic mapping.** Retain the recorded stable-code/source-path diagnostic boundary and existing native/Scala evidence.

- [x] **Increment 23 — Backend framework and capability profiles**
  - Add translation registration, deterministic output handling, profile-owned shaped-value layouts, expression materialization/naming and CheckProfile configuration, transactional target verification/reparse hooks, `verilog-a`/`verilog-ams` profiles, and explicit unsupported-feature errors.
  - Evidence: [`NodalBackendFramework-DG-v1.0.md`](../design-gates/NodalBackendFramework-DG-v1.0.md), [`increment23-backend-framework.md`](../implementation/increment23-backend-framework.md), implementation PR [#61](https://github.com/pysolvesemi/Nodal/pull/61), closure PR [#63](https://github.com/pysolvesemi/Nodal/pull/63), dedicated validation run [32966834961](https://github.com/pysolvesemi/Nodal/actions/runs/32966834961), and Core CI run [32966835105](https://github.com/pysolvesemi/Nodal/actions/runs/32966835105).
  - [x] **F-023.H — Historical acceptance breakdown: Backend framework and profiles.** Retain the accepted translation/profile/transactional-output boundary, not universal target-language support.

- [x] **Increment 24 — Minimal analog expression and contribution IR**
  - Add real literals, parameter references, arithmetic, electrical potential access, analog region, and contribution sufficient for a minimal RC equation.
  - Evidence: [`NodalMinimalAnalogIr-DG-v1.0.md`](../design-gates/NodalMinimalAnalogIr-DG-v1.0.md), [`increment24-minimal-analog-ir.md`](../implementation/increment24-minimal-analog-ir.md), implementation PR [#66](https://github.com/pysolvesemi/Nodal/pull/66), dedicated validation run [33039547022](https://github.com/pysolvesemi/Nodal/actions/runs/33039547022), and Core CI run [33039546995](https://github.com/pysolvesemi/Nodal/actions/runs/33039546995).
  - [x] **F-024.H — Historical acceptance breakdown: Minimal analog IR.** Retain the recorded minimal real/parameter/access/contribution representation sufficient for the RC slice.

- [x] **Increment 25 — RC filter end-to-end vertical slice**
  - Compile Scala RC through construction, Nodal MLIR, verification, and Verilog-A emission with exact golden output and failures.
  - [x] **F-025.H — Historical acceptance breakdown: RC compiler vertical slice.** Retain actual Scala-to-MLIR-to-Verilog-A structural/reparse and golden-output qualification in [the accepted implementation record](../implementation/increment25-rc-vertical-slice.md); numerical simulation is not implied.

- [x] **Increment 26 — Deterministic output and reproducibility contract**
  - Prove byte-identical MLIR, HDL, shape/layout and storage manifests, materialization decisions/reasons, semantic names, expression source maps, check inventories/waivers, domain manifests, and CDC/RDC reports across repeated builds and valid traversal orders.
  - Evidence: [`NodalReproducibilityContract-DG-v1.0.md`](../design-gates/NodalReproducibilityContract-DG-v1.0.md), [`increment26-reproducibility-contract.md`](../implementation/increment26-reproducibility-contract.md), implementation PR [#69](https://github.com/pysolvesemi/Nodal/pull/69), dedicated validation run [33078619538](https://github.com/pysolvesemi/Nodal/actions/runs/33078619538), and Core CI run [33078619501](https://github.com/pysolvesemi/Nodal/actions/runs/33078619501).
  - [x] **F-026.H — Historical acceptance breakdown: Reproducibility contract.** Retain the specific byte-stability and reproducibility evidence in the linked accepted record, not new scale measurements.

## Phase 2 — Analog language and Verilog-A profile

**Phase 2 dependency gate:** Increment 32 must not begin until the equation/component checkpoint of Increment 133 is approved. That checkpoint freezes unordered equation semantics; equation, contribution, and procedural-assignment separation; conservative connection equations; terminal/branch and partial/concrete component contracts; local balance; structural parameters; initialization equations; and unsupported-target behavior. The remaining analysis, PVT, noise, validity, and solver-hint portions of Increment 133 may complete later without weakening this prerequisite.

- [x] **Increment 27 — Natures and disciplines**
  - Implement units, access functions, tolerances, domains, potential/flow associations, declarations, imports, and compatibility.
  - [x] **F-027.H — Historical acceptance breakdown: Native nature/discipline declarations.** Retain symbolized declaration/import, tolerance/type/compatibility and native validation described in [the implementation record](../implementation/increment27-natures-disciplines.md). Source lowering and new declaration HDL emission were outside that accepted boundary.

- [x] **Increment 28 — Electrical nodes, nets, and branches**
  - Implement scalar conservative terminals/nodes, ground/reference behavior, implicit/named branches, port directions, aliases, connection-set identity, branch orientation, ownership, and source hierarchy.
  - Generate compatible-potential equality and signed zero-sum flow-conservation equations from connection sets, retaining provenance for later residual construction and target lowering.
  - Define partial versus concrete physical-component connectivity ownership without treating conservative terminals as directional signal flow.
  - [x] **F-028.H — Historical acceptance breakdown: Scalar conservative connectivity IR.** Retain normalized connection sets, oriented branches, potential/flow conservation and native validation in [the implementation record](../implementation/increment28-electrical-connectivity.md). Residual DAE construction and new-operation backend lowering remained deferred.

- [x] **Increment 29 — Parameters, constants, ranges, and units**
  - Implement supported parameter kinds, constraints, constant expressions, overrides, unit-aware literals, and lossless native HDL rendering.
  - Classify ordinary parameters, structural parameters, and dynamic values; require topology, component count, equation count, shape, or rank changes to be elaboration-time or static target generation, and diagnose unsupported parameter-envelope structural changes.
  - Evidence: implementation PR [#78](https://github.com/pysolvesemi/Nodal/pull/78), dedicated validation run [33154887196](https://github.com/pysolvesemi/Nodal/actions/runs/33154887196), merge commit [`09ffbf34`](https://github.com/pysolvesemi/Nodal/commit/09ffbf344a4bf2ee8f6b2fb16ba3272669f47f27), and Core CI run [33154887245](https://github.com/pysolvesemi/Nodal/actions/runs/33154887245).
  - [x] **F-029.H — Historical acceptance breakdown: Parameter/constant/range/unit compiler profile.** Retain the accepted parameter and structural-classification requirements with the exact historical PR/merge/run evidence below.

- [x] **Increment 30 — Analog numeric types and expression typing**
  - Define promotion, physical compatibility, comparisons/logical results, conditionals, invalid operations, and folding boundaries.
  - Evidence: implementation PR [#80](https://github.com/pysolvesemi/Nodal/pull/80), dedicated validation run [33192880165](https://github.com/pysolvesemi/Nodal/actions/runs/33192880165), merge commit [`401f78b3`](https://github.com/pysolvesemi/Nodal/commit/401f78b3836cc4e52d393ef343dc0915d60606e9), and Core CI run [33192880254](https://github.com/pysolvesemi/Nodal/actions/runs/33192880254).
  - [x] **F-030.H — Historical acceptance breakdown: Analog numeric typing.** Retain the accepted promotion/dimension/comparison/folding-boundary profile and recorded evidence.

- [x] **Increment 31 — Potential and flow access functions**
  - Implement `V`, `I`, discipline-specific access, one/two-node forms, branches, probes, and validation.
  - Evidence: implementation PR [#88](https://github.com/pysolvesemi/Nodal/pull/88), original draft PR [#87](https://github.com/pysolvesemi/Nodal/pull/87), dedicated validation run [33244625475](https://github.com/pysolvesemi/Nodal/actions/runs/33244625475), merge commit [`1662b79f`](https://github.com/pysolvesemi/Nodal/commit/1662b79f5f99686de4af2ed8a016fe8acf5c784e), and Core CI run [33244625490](https://github.com/pysolvesemi/Nodal/actions/runs/33244625490).
  - [x] **F-031.H — Historical acceptance breakdown: Potential/flow access profile.** Retain the accepted access-function/branch/probe validation boundary and historical implementation/merge records.

- [x] **Increment 32 — First-class analog equations, blocks, and contribution semantics**
  - Implement analog regions, unordered first-class equations, and `<+` potential/flow contributions as distinct source-semantic operations; keep both distinct from Increment 33 procedural assignment.
  - Preserve authored equation sides, stable equation identity, physical dimensions, guards, analysis applicability, and canonical residual intent without premature causal orientation or unsafe algebraic division.
  - Define additive contribution accumulation, source-order independence, equation/contribution interaction, illegal procedural use, and stable diagnostics.
  - Require the approved equation/component checkpoint from Increment 133 before implementation begins.
  - Evidence: implementation PR [#97](https://github.com/pysolvesemi/Nodal/pull/97), accepted head [`6a76516a`](https://github.com/pysolvesemi/Nodal/commit/6a76516aba541ead97205e937118bb0f689fcd98), dedicated validation run [33370821599](https://github.com/pysolvesemi/Nodal/actions/runs/33370821599), merge commit [`e9ea39e8`](https://github.com/pysolvesemi/Nodal/commit/e9ea39e823d5a226a65b952e176d3bb90ecda0aa), and post-merge Core CI run [33372029305](https://github.com/pysolvesemi/Nodal/actions/runs/33372029305).
  - [x] **F-032.H — Historical acceptance breakdown: Equation and contribution compiler profile.** Retain the accepted unordered-equation/contribution separation and the approved 133 checkpoint prerequisite without adding solver qualification.

- [x] **Increment 33 — Analog variables and procedural assignment**
  - Implement local variables, initialization, procedural assignment, scopes, read-before-write diagnostics, and lowering.

  - Evidence: implementation PR [#102](https://github.com/pysolvesemi/Nodal/pull/102), accepted head [`ea7f7da5`](https://github.com/pysolvesemi/Nodal/commit/ea7f7da51e85ba275dac71db7823ba0223f8d4ac), dedicated boundary run [33592719238](https://github.com/pysolvesemi/Nodal/actions/runs/33592719238), merge commit [`2e0ff291`](https://github.com/pysolvesemi/Nodal/commit/2e0ff291b8d6c0f6dcc4b4c8e27cc33984cff1b8), post-merge Core CI run [33605996500](https://github.com/pysolvesemi/Nodal/actions/runs/33605996500), and exact post-merge validation run [33714669557](https://github.com/pysolvesemi/Nodal/actions/runs/33714669557).
  - [x] **F-033.H — Historical acceptance breakdown: Analog procedural assignment profile.** Retain the accepted variable/scope/read-before-write/lowering boundary and separate exact post-merge evidence.

- [x] **Increment 34 — Analog control flow**
  - Implement conditionals, case, bounded loops, break/continue where supported, and static/runtime legality.

  - Evidence: implementation PR [#109](https://github.com/pysolvesemi/Nodal/pull/109), accepted head [`207fd1b5`](https://github.com/pysolvesemi/Nodal/commit/207fd1b580e9428e9948cd4e4bd8f2060fde4b79), 26-workflow exact-head matrix, Core CI run [33732864482](https://github.com/pysolvesemi/Nodal/actions/runs/33732864482), merge commit [`a9d3ec50`](https://github.com/pysolvesemi/Nodal/commit/a9d3ec50799953c41e7b9cf1d8bd6a2c5c9afd49), post-merge Core CI run [33758905273](https://github.com/pysolvesemi/Nodal/actions/runs/33758905273), exact post-merge validation run [33759112770](https://github.com/pysolvesemi/Nodal/actions/runs/33759112770), and evidence-closure PR [#111](https://github.com/pysolvesemi/Nodal/pull/111) validated from [`b59ed10f`](https://github.com/pysolvesemi/Nodal/commit/b59ed10f423d4a66e7e47d66ec764b7ff22531e7) by run [33761024228](https://github.com/pysolvesemi/Nodal/actions/runs/33761024228).
  - [x] **F-034.H — Historical acceptance breakdown: Analog control-flow profile.** Retain the accepted control-flow legality/lowering and historical exact-head/post-merge/closure evidence.

- [x] **Increment 35 — Differential and integral operators**
  - Implement `ddt`, `idt`, initial conditions, context restrictions, and semantics-preserving simplification.
  - Post-closure correctness follow-up: [review hardening](../implementation/increment35-review-hardening.md) addresses legacy derivative simplification, enclosing-module ownership and immutable closure evidence; historical acceptance below is unchanged.
  - Evidence: implementation PR [#113](https://github.com/pysolvesemi/Nodal/pull/113), accepted head [`d3410f6f`](https://github.com/pysolvesemi/Nodal/commit/d3410f6f64dc66df27d9c7f545c9e78f62695f2e), 25-workflow exact-head matrix, Core CI run [33890457304](https://github.com/pysolvesemi/Nodal/actions/runs/33890457304), merge commit [`7763e152`](https://github.com/pysolvesemi/Nodal/commit/7763e1524f31e4c2c41b11acb200670c360f0fde), post-merge Core CI run [33892575717](https://github.com/pysolvesemi/Nodal/actions/runs/33892575717), exact post-merge validation run [33892632854](https://github.com/pysolvesemi/Nodal/actions/runs/33892632854), and evidence-closure PR [#114](https://github.com/pysolvesemi/Nodal/pull/114) validated from [`39915b98`](https://github.com/pysolvesemi/Nodal/commit/39915b984707f0396777cc69030dfec29aa2befe) by dedicated run [33916159555](https://github.com/pysolvesemi/Nodal/actions/runs/33916159555) and Core CI run [33916159534](https://github.com/pysolvesemi/Nodal/actions/runs/33916159534).
  - [x] **F-035.H — Historical acceptance breakdown: Differential/integral operator profile.** Retain the accepted ddt/idt context/initialization/simplification boundary. The already linked review-hardening follow-up remains separate and is not marked newly complete.

- [x] **Increment 36 — Time and waveform operators**
  - Implement `transition`, `slew`, `absdelay`, `$abstime`, `$bound_step`, units, continuity, and diagnostics.
  - Evidence: implementation PR [#118](https://github.com/pysolvesemi/Nodal/pull/118), accepted head [`cf0d4504`](https://github.com/pysolvesemi/Nodal/commit/cf0d4504b5463eaad574edd08bd32ccd1ec74e78), all 26 exact-head workflows, merge [`aa93bc7e`](https://github.com/pysolvesemi/Nodal/commit/aa93bc7e9eb6df51162a486452b185025b77207a), post-merge Core CI [33951187187](https://github.com/pysolvesemi/Nodal/actions/runs/33951187187), exact post-merge validation [33951187157](https://github.com/pysolvesemi/Nodal/actions/runs/33951187157), and [accepted-evidence record](../implementation/increment36-evidence-closure.md).
  - [x] **F-036.H — Historical acceptance breakdown: Time/waveform compiler profile.** Retain the accepted operator/units/continuity diagnostics and the linked immutable accepted-evidence record.

- [x] **Increment 37 — Analog events**
  - Implement `cross`, `above`, `timer`, initial/final step, event composition, tolerances, and controlled statements.
  - Accepted compiler/event profile: implementation PR #124 at `5684be7a725495cbbbd330d23da4d6c35bf353cb`, merged as `2070bf5824b448aa23b917386ef1eccc089f28c7`; exact-head CI, independent review, exact post-merge Core CI `34020356639` and Increment 37 `34020356658`, and byte-identical retained public-source/target witnesses are recorded in [the accepted-evidence record](../implementation/increment37-evidence-closure.md).
  - Per-generated-instance lexical storage is rejected until represented under Increment 43. Numerical solver execution, full Verilog-AMS digital processes, and co-simulation remain separately scoped; structural target acceptance is not numerical simulation.
  - [x] **F-037.H — Historical acceptance breakdown: Analog-event compiler profile.** Retain the compiler/event witnesses and explicit generated-instance storage, numerical and general-AMS exclusions in the existing acceptance record.

- [x] **Increment 38 — Mathematical and simulator functions**
  - Add a versioned registry with type/arity checking, constant evaluation, analysis queries, and backend spelling.
  - Accepted compiler/Verilog-A profile: implementation PR #126 at `05047f4bb511ef19a812de6e8f08fc2e709cb5c8`, merged as `e593a60eb6d6fdb9a505d0762c859934e041b92d`; all 28 PR workflows, independent review, post-merge Core CI `34090729148` and Increment 38 `34090729146`, and byte-identical public-source/output witnesses are recorded in [the accepted-evidence record](../implementation/increment38-evidence-closure.md).
  - Supports 24 real mathematical functions and six dynamic analysis queries. Numerical simulator execution, general Verilog-AMS, noise/transfer operators, user-defined functions and environment access remain separately scoped.
  - [x] **F-038.H — Historical acceptance breakdown: Math/analysis compiler profile.** Retain the recorded 24 real functions and six analysis queries, with numerical simulation and separately owned operators/environment excluded.

- [x] **Increment 39 — Noise operators**
  - Implement white, flicker, and table noise with analysis, naming, units, and capability checks.
  - Accepted independent small-signal compiler/Verilog-A profile: implementation PR #128 at `c22846da1812ef8b30ee8299ad89906bcd5171f2`, merged as `924fd125b7958fc9fadefefd8857f34f58815351`; required Core CI `34119122192`, direct implementation-agent review, exact post-merge Core CI `34122571681` and Increment 39 `34122571567`, and matching public-source/output witnesses are retained in [the accepted-evidence record](../implementation/increment39-evidence-closure.md).
  - Naming here covers source identity, reporting labels, and collision avoidance, not full Scala-local binders; lexical preservation remains in Foundation 153–157 and backend parity 65/72. Numerical noise simulation, transient/group correlation, broader tables/creation contexts, and general Verilog-AMS remain deferred.
  - [x] **F-039.H — Historical acceptance breakdown: Independent small-signal noise compiler profile.** Retain the existing compiler/Verilog-A acceptance and its numerical, correlation and function-local naming exclusions.

- [x] **Increment 40 — Laplace and discrete transfer operators**
  - Implement supported Laplace/Z-domain forms, coefficient arrays, constant requirements, denominator validation, and emission.
  - Accepted numerator/denominator compiler/Verilog-A profile: implementation PR #130 at `14860e5cfbbaacdc866fdfc6f61f91ffb93d33a1`, merged as `e26971903ef808b17feafaaa21b940e4a6494583`; required Core CI `34185774643`, dedicated transfer run `34185774644`, direct implementation-agent review, exact post-merge Core CI `34190895982` and Increment 40 `34190896012`, and byte-identical public-source/output witnesses are recorded in [the accepted-evidence record](../implementation/increment40-evidence-closure.md).
  - Supports `laplaceNd`/`ziNd`, fixed coefficient-array lengths with symbolic scalar coefficients, physical-unit/static/d0/timing checks, and independent/shared/cascaded state preservation. Root/pole forms, broader symbolic proofs, numerical simulation, general Verilog-AMS and lexical binder naming (Foundation 153–157) remain deferred.
  - [x] **F-040.H — Historical acceptance breakdown: Numerator/denominator transfer compiler profile.** Retain the accepted laplaceNd/ziNd profile, fixed coefficient-array shapes and state preservation with the existing deferred boundaries.

- [x] **Increment 41 — User-defined analog functions**
  - Implement typed declarations, arguments, locals, returns, recursion/overload policy, resolution, and lowering.
  - Evidence: implementation PR [#132](https://github.com/pysolvesemi/Nodal/pull/132), accepted head `3ef30a40e2f81c5938e37cfbad7ad38c9146c3c2`, merge `4d979879d9ee1edd2413068f33ea1a2e4b357e6f`, exact post-merge Core CI `35538979449`, Increment 41 `35538979384`, and [accepted-evidence record](../implementation/increment41-evidence-closure.md).
  - [x] **F-041.H — Historical acceptance breakdown: Pure scalar module-local analog functions.** Retain typed Real/Integer inputs/results, initialized immutable locals, a total return and nested nonrecursive calls in [the accepted record](../implementation/increment41-evidence-closure.md). Recursion, overloads, captures and effectful/unsupported forms remain rejected.
    - [x] **F-041.H.1 — Compiler safety and structural target evidence.** Native verification, forged-call rejection, source-map namespace isolation, dependency-ordered Verilog-A emission, target reparse and no-partial-HDL handling are recorded in the unchanged closure.
    - [x] **F-041.H.2 — Historical merge and demonstration evidence.** The existing closure retains the accepted source/tree, actual merge, executed historical CI and actual public Scala/Verilog-A witnesses. Its review is direct implementation-agent review, not independent automated review.
  - Historical profile limit: compiler/Verilog-A only; numerical analog simulation, general Verilog-AMS, synthesis and broader function/optimization forms remain outside this acceptance.

- [ ] **Increment 42 — Analog hierarchy and parameterized instances**
  - Original scope retained: Implement instances, named ports, symbolic overrides, legal arrays, hierarchy verification, and recursion errors.
  - [ ] **F-042.A — Architecture, scope and extensibility.** Reuse module, instance, parameter and conservative-terminal ownership from 19/28/29/41; distinguish module-local function calls from hierarchical connectivity.
  - [ ] **F-042.B — Implementation and integration**
    - [ ] **F-042.B.1** Implement public construction and frontend ownership for instances, named ports, symbolic parameter overrides and the legal instance-array forms.
    - [ ] **F-042.B.2** Carry hierarchy, ports, overrides and ownership through the bridge/native IR; verify bindings and reject recursive or cross-owner construction with source locations.
    - [ ] **F-042.B.3** Lower the supported hierarchical Verilog-A profile with retained symbolic overrides and stable module/instance identity; document unsupported cases rather than prototype-only success.
  - [ ] **F-042.C — Correctness, rejection and predecessor regression.** Exercise named-port mismatch, illegal overrides, cross-owner terminals and recursion; combine hierarchy with equations, events and module-local functions.
  - [ ] **F-042.D — Independent validation and applicability**
    - [ ] **F-042.D.1** Compiler witnesses: Retain actual public Scala, normalized IR and generated Verilog-A witnesses for the supported compiler profile; distinguish internal reparse from independent OpenVAF compilation.
    - [ ] **F-042.D.2** Later tool qualification: Identify the applicable witness cases for 48 compile and 49/52 numerical qualification, with required analyses, references and tolerances. Preserve compiler-only acceptance limits; unavailable required execution remains blocked, not passed or N/A.
  - [ ] **F-042.E — Optimization review and output quality.** Review duplicate module emission, preserved symbolic overrides and hierarchy-derived names without clone-per-default specialization. Apply the proportionate review rule above; document no new optimization required when justified.
  - [ ] **F-042.F — Scale, determinism and compatibility.** Vary hierarchy depth, repeated instance count and non-default parameter combinations; compare deterministic instance/source paths.
  - [ ] **F-042.G — Evidence, documentation and acceptance.** Retain the applicable evidence, capability limits and reproduction/demonstration record for 42; complete review, verified integration and any separate closure under the centralized Foundation acceptance rules.

- [ ] **Increment 43 — Analog arrays, shaped values, and elaboration-time generation**
  - Original scope retained: Implement legal fixed/symbolic analog arrays under ADR 0017 shape/index rules, analog-object capability restrictions, indexing/slices, Scala elaboration loops, target generate constructs, static bounds, target layout checks, and explicit rejection of illegal analog flattening or memory inference.
  - [ ] **F-043.A — Architecture, scope and extensibility.** Reuse ADR 0017 rank/index and 42 hierarchy contracts; make per-generated-instance storage ownership explicit rather than borrowing enclosing lexical storage.
  - [ ] **F-043.B — Implementation and integration**
    - [ ] **F-043.B.1** Implement public/frontend fixed and symbolic analog shape/index/slice and elaboration/generate construction using the existing staged representations.
    - [ ] **F-043.B.2** Preserve generated-instance analog object and lexical-storage ownership through bridge/native IR and verifiers; enforce static bounds and capability restrictions.
    - [ ] **F-043.B.3** Lower supported target layouts and generation without illegal analog flattening or memory inference; retain source/index maps and rejected capability cases.
  - [ ] **F-043.C — Correctness, rejection and predecessor regression.** Cover empty/singleton/invalid dimensions, bounds and rank mismatch, generated event-local state, illegal analog flattening and accidental memory inference.
  - [ ] **F-043.D — Independent validation and applicability**
    - [ ] **F-043.D.1** Compiler witnesses: Retain actual public Scala, normalized IR and generated Verilog-A witnesses for the supported compiler profile; distinguish internal reparse from independent OpenVAF compilation.
    - [ ] **F-043.D.2** Later tool qualification: Identify the applicable witness cases for 48 compile and 49/52 numerical qualification, with required analyses, references and tolerances. Preserve compiler-only acceptance limits; unavailable required execution remains blocked, not passed or N/A.
  - [ ] **F-043.E — Optimization review and output quality.** Review array expansion and repeated shape traversal; retain symbolic bounds and instance-local state rather than eager flattening. Apply the proportionate review rule above; document no new optimization required when justified.
  - [ ] **F-043.F — Scale, determinism and compatibility.** Exercise nested dimensions, repeated generated instances and parameter envelopes with stable index/source maps.
  - [ ] **F-043.G — Evidence, documentation and acceptance.** Retain the applicable evidence, capability limits and reproduction/demonstration record for 43; complete review, verified integration and any separate closure under the centralized Foundation acceptance rules.

- [ ] **Increment 44 — Analysis state and environmental constructs**
  - Original scope retained: Implement analysis-dependent behavior, temperature/environment access, initial/final semantics, and portability policy.
  - [ ] **F-044.A — Architecture, scope and extensibility.** Separate analysis, environment and initialization context from ordinary parameters and pure function effects; preserve future 138-140 seams.
  - [ ] **F-044.B — Implementation and integration**
    - [ ] **F-044.B.1** Implement source construction and typed analysis/environment/initial/final context propagation through frontend and bridge.
    - [ ] **F-044.B.2** Implement native context verification and supported backend spelling/lowering without folding dynamic environment state or bypassing portability limits.
  - [ ] **F-044.C — Correctness, rejection and predecessor regression.** Test unsupported analyses, wrong units, illegal environment access in pure functions and initial/final ordering with 37-41 constructs.
  - [ ] **F-044.D — Independent validation and applicability**
    - [ ] **F-044.D.1** Compiler witnesses: Retain actual public Scala, normalized IR and generated Verilog-A witnesses for the supported compiler profile; distinguish internal reparse from independent OpenVAF compilation.
    - [ ] **F-044.D.2** Later tool qualification: Identify the applicable witness cases for 48 compile and 49/52 numerical qualification, with required analyses, references and tolerances. Preserve compiler-only acceptance limits; unavailable required execution remains blocked, not passed or N/A.
  - [ ] **F-044.E — Optimization review and output quality.** Review environment-query duplication without folding dynamic analysis state or moving initialization effects. Apply the proportionate review rule above; document no new optimization required when justified.
  - [ ] **F-044.F — Scale, determinism and compatibility.** Exercise analysis/environment combinations and repeated instances; retain stable context identities across runs.
  - [ ] **F-044.G — Evidence, documentation and acceptance.** Retain the applicable evidence, capability limits and reproduction/demonstration record for 44; complete review, verified integration and any separate closure under the centralized Foundation acceptance rules.

- [ ] **Increment 45 — Analog canonicalization passes**
  - [ ] **F-045.A — Architecture, scope and extensibility**
    - [ ] **F-045.A.1** Prohibit unproved equation orientation, division by possibly zero expressions, contribution reordering, state movement, topology change, or residual elimination; retain before/after equation provenance.
    - [ ] **F-045.A.2** Reuse the registered pass and source-equation contracts from 21/32; specify side-effect, state and residual-preservation boundaries.
  - [ ] **F-045.B — Implementation and integration.** Implement safe folding, residual-preserving algebraic normalization, dead declaration removal, branch/access normalization, and deterministic common-expression handling.
  - [ ] **F-045.C — Correctness, rejection and predecessor regression.** Use counterexamples and mutations for zero denominators, discontinuities, contribution multiplicity and stateful operators; retain predecessor output semantics.
  - [ ] **F-045.D — Independent validation and applicability**
    - [ ] **F-045.D.1** Compiler witnesses: Retain actual public Scala, normalized IR and generated Verilog-A witnesses for the supported compiler profile; distinguish internal reparse from independent OpenVAF compilation.
    - [ ] **F-045.D.2** Later tool qualification: Identify the applicable witness cases for 48 compile and 49/52 numerical qualification, with required analyses, references and tolerances. Preserve compiler-only acceptance limits; unavailable required execution remains blocked, not passed or N/A.
  - [ ] **F-045.E — Optimization review and output quality.** Separate mandatory normalization from optional simplification; document each introduced rewrite and its numerical/domain preconditions. Apply the proportionate review rule above; document no new optimization required when justified.
  - [ ] **F-045.F — Scale, determinism and compatibility.** Measure expression-graph growth and repeated-pass behavior; require deterministic fixed-point output and stable provenance.
  - [ ] **F-045.G — Evidence, documentation and acceptance.** Retain the applicable evidence, capability limits and reproduction/demonstration record for 45; complete review, verified integration and any separate closure under the centralized Foundation acceptance rules.

- [ ] **Increment 46 — Analog semantic lint suite**
  - [ ] **F-046.A — Architecture, scope and extensibility.** Reuse semantic topology, dimensions and diagnostics; distinguish the available source checks from later whole-island DAE analysis in 135.
  - [ ] **F-046.B — Implementation and integration.** Detect floating nodes, discipline conflicts, branch misuse, unit errors, local/global equation imbalance, structural singularity, unsafe equation orientation or division, invalid structural-parameter envelopes, unreachable events, discontinuities, parameter risks, and portability hazards.
  - [ ] **F-046.C — Correctness, rejection and predecessor regression.** Pair each important lint with a valid near-boundary case and a triggering mutation, including hierarchy, events and symbolic envelopes.
  - [ ] **F-046.D — Independent validation and applicability**
    - [ ] **F-046.D.1** Compiler witnesses: Retain actual public Scala, normalized IR and generated Verilog-A witnesses for the supported compiler profile; distinguish internal reparse from independent OpenVAF compilation.
    - [ ] **F-046.D.2** Later tool qualification: Identify the applicable witness cases for 48 compile and 49/52 numerical qualification, with required analyses, references and tolerances. Preserve compiler-only acceptance limits; unavailable required execution remains blocked, not passed or N/A.
  - [ ] **F-046.E — Optimization review and output quality.** Review repeated topology traversal and diagnostic duplication; lint must not repair or silently change mathematical behavior. Apply the proportionate review rule above; document no new optimization required when justified.
  - [ ] **F-046.F — Scale, determinism and compatibility.** Exercise sparse/deep connectivity and large diagnostic/source paths; compare stable issue ordering.
  - [ ] **F-046.G — Evidence, documentation and acceptance.** Retain the applicable evidence, capability limits and reproduction/demonstration record for 46; complete review, verified integration and any separate closure under the centralized Foundation acceptance rules.

- [ ] **Increment 47 — Verilog-A capability profile and feature matrix**
  - [ ] **F-047.A — Architecture, scope and extensibility.** Separate language capability from simulator support using existing backend profiles; keep AMS-only features outside the Verilog-A claim.
  - [ ] **F-047.B — Implementation and integration.** Publish exact `.va` coverage, reject AMS-only constructs early, document simulator portability, and expose machine-readable features.
  - [ ] **F-047.C — Correctness, rejection and predecessor regression.** Test advertised feature positives, unsupported-profile rejection and transitive hierarchy capability leaks.
  - [ ] **F-047.D — Independent validation and applicability**
    - [ ] **F-047.D.1** Compiler witnesses: Retain actual public Scala, normalized IR and generated Verilog-A witnesses for the supported compiler profile; distinguish internal reparse from independent OpenVAF compilation.
    - [ ] **F-047.D.2** Later tool qualification: Identify the applicable witness cases for 48 compile and 49/52 numerical qualification, with required analyses, references and tolerances. Preserve compiler-only acceptance limits; unavailable required execution remains blocked, not passed or N/A.
  - [ ] **F-047.E — Optimization review and output quality.** Review capability lookup cost and deterministic readable inventories without introducing emission rewrites. Apply the proportionate review rule above; document no new optimization required when justified.
  - [ ] **F-047.F — Scale, determinism and compatibility.** Exercise feature/profile combinations and manifest compatibility; identical designs must yield identical capability decisions.
  - [ ] **F-047.G — Evidence, documentation and acceptance.** Retain the applicable evidence, capability limits and reproduction/demonstration record for 47; complete review, verified integration and any separate closure under the centralized Foundation acceptance rules.

## Phase 3 — Open-source analog validation and testbench support

- [ ] **Increment 48 — OpenVAF compile validation**
  - [ ] **F-048.A — Architecture, scope and extensibility.** Bind the existing Verilog-A profile to pinned OpenVAF capabilities without making its supported subset define Nodal semantics.
  - [ ] **F-048.B — Implementation and integration.** Detect versions/features, compile generated models, classify expected limitations, and retain diagnostics.
  - [ ] **F-048.C — Correctness, rejection and predecessor regression.** Cover generated legal and intentionally malformed models, unsupported constructs, compiler errors, missing executables and diagnostic source mapping.
  - [ ] **F-048.D — Independent validation and applicability**
    - [ ] **F-048.D.1** Independent compilation: Compile actual generated Verilog-A with the pinned supported OpenVAF profile; retain commands, model/tool hashes and diagnostics for the selected cases.
    - [ ] **F-048.D.2** Capability and failure classification: Execute malformed-model and unsupported-profile controls separately from successful compilation. Numerical execution remains owned by 49; a compiled model is not a numerical result.
  - [ ] **F-048.E — Optimization review and output quality.** Review compile reuse and log normalization without caching across model/tool/profile changes. Apply the proportionate review rule above; document no new optimization required when justified.
  - [ ] **F-048.F — Scale, determinism and compatibility.** Compile repeated hierarchical models and parameter cases; retain tool-version compatibility and repeatable classifications.
  - [ ] **F-048.G — Evidence, documentation and acceptance.** Retain the applicable evidence, capability limits and reproduction/demonstration record for 48; complete review, verified integration and any separate closure under the centralized Foundation acceptance rules.

- [ ] **Increment 49 — ngspice simulation harness**
  - [ ] **F-049.A — Architecture, scope and extensibility.** Reuse generated-model identity and OpenVAF/OSDI output from 48; define simulator process, analysis and result ownership.
  - [ ] **F-049.B — Implementation and integration.** Add OSDI loading, generated SPICE benches, transient/DC/AC runs, timeout/error handling, outputs, and CI smoke simulation.
  - [ ] **F-049.C — Correctness, rejection and predecessor regression.** Cover model-load failure, invalid benches, nonconvergence, timeout and malformed output; combine hierarchy, initialization and event fixtures where supported.
  - [ ] **F-049.D — Independent validation and applicability**
    - [ ] **F-049.D.1** Syntax and model loading: Compile/load the actual generated supported Verilog-A/OSDI models through 48-49, retaining tool identities, model hashes, commands and capability decisions.
    - [ ] **F-049.D.2** Numerical behavior: Execute the applicable DC, transient and AC cases with declared independent references, units, stimuli and tolerances; add event/noise cases only under an explicitly qualified capability. Record numerical discrepancies and solver failures, not formal equivalence claims.
  - [ ] **F-049.E — Optimization review and output quality.** Review bench generation and result collection costs; never tune tolerances to hide mismatches. Apply the proportionate review rule above; document no new optimization required when justified.
  - [ ] **F-049.F — Scale, determinism and compatibility.** Exercise bounded sweep sizes and long transient output with reproducible seeds, parameters and solver options.
  - [ ] **F-049.G — Evidence, documentation and acceptance.** Retain the applicable evidence, capability limits and reproduction/demonstration record for 49; complete review, verified integration and any separate closure under the centralized Foundation acceptance rules.

- [ ] **Increment 50 — Scala simulation API v0.1**
  - [ ] **F-050.A — Architecture, scope and extensibility.** Keep the public Scala simulation contract independent of simulator syntax; reuse 48-49 process and evidence services.
  - [ ] **F-050.B — Implementation and integration.** Add compilation, source creation, clock/reset-domain stimulus, related/asynchronous clocks, analyses, sweeps, measurements, tolerances, and assertions without hiding tool evidence.
  - [ ] **F-050.C — Correctness, rejection and predecessor regression.** Exercise source/stimulus creation, clock/reset relationships, analyses, failed measurements and assertion tolerances; reject unsupported requests before execution.
  - [ ] **F-050.D — Independent validation and applicability**
    - [ ] **F-050.D.1** Syntax and model loading: Compile/load the actual generated supported Verilog-A/OSDI models through 48-49, retaining tool identities, model hashes, commands and capability decisions.
    - [ ] **F-050.D.2** Numerical behavior: Execute the applicable DC, transient and AC cases with declared independent references, units, stimuli and tolerances; add event/noise cases only under an explicitly qualified capability. Record numerical discrepancies and solver failures, not formal equivalence claims.
  - [ ] **F-050.E — Optimization review and output quality.** Review repeated compilation and stimulus/result buffering without hiding external commands or changing time ordering. Apply the proportionate review rule above; document no new optimization required when justified.
  - [ ] **F-050.F — Scale, determinism and compatibility.** Exercise repeated runs, sweep dimensions and concurrent independent sessions with deterministic result identities.
  - [ ] **F-050.G — Evidence, documentation and acceptance.** Retain the applicable evidence, capability limits and reproduction/demonstration record for 50; complete review, verified integration and any separate closure under the centralized Foundation acceptance rules.

- [ ] **Increment 51 — Waveform and result model**
  - [ ] **F-051.A — Architecture, scope and extensibility.** Model time/frequency/sweep axes and units explicitly; consume 49-50 results without confusing sample order with physical time.
  - [ ] **F-051.B — Implementation and integration.** Parse typed time/frequency/sweep results, preserve units, stream large data, and provide comparison/assertion utilities.
  - [ ] **F-051.C — Correctness, rejection and predecessor regression.** Test malformed, non-monotonic and unit-incompatible data, missing axes, boundary interpolation and streaming versus in-memory comparison.
  - [ ] **F-051.D — Independent validation and applicability**
    - [ ] **F-051.D.1** Independent result fixtures: Use retained simulator outputs and independently calculated unit/axis/comparison expectations; check streaming and in-memory results against the same reference, not merely parser success.
    - [ ] **F-051.D.2** Applicability boundary: Record that result parsing itself is not HDL generation, synthesis or formal proof; retain provenance to the actual simulator run where a numerical-result claim is made.
  - [ ] **F-051.E — Optimization review and output quality.** Review copying and retention of large traces; preserve precision and defined comparison semantics. Apply the proportionate review rule above; document no new optimization required when justified.
  - [ ] **F-051.F — Scale, determinism and compatibility.** Measure memory against long traces and large sweeps; verify stable serialization and format compatibility.
  - [ ] **F-051.G — Evidence, documentation and acceptance.** Retain the applicable evidence, capability limits and reproduction/demonstration record for 51; complete review, verified integration and any separate closure under the centralized Foundation acceptance rules.

- [ ] **Increment 52 — Analog regression suite**
  - [ ] **F-052.A — Architecture, scope and extensibility.** Build regression cases from the declared Verilog-A and simulator capability intersection, not an assumed universal analog profile.
  - [ ] **F-052.B — Implementation and integration.** Cover RC/RLC, diode, controlled source, amplifier, comparator, oscillator/VCO, hierarchy, events, sweeps, and failures as core fixtures.
  - [ ] **F-052.C — Correctness, rejection and predecessor regression.** Combine hierarchy, equations, state, functions, events and sweeps; retain failing stimuli and correct language-versus-solver classifications.
  - [ ] **F-052.D — Independent validation and applicability**
    - [ ] **F-052.D.1** Syntax and model loading: Compile/load the actual generated supported Verilog-A/OSDI models through 48-49, retaining tool identities, model hashes, commands and capability decisions.
    - [ ] **F-052.D.2** Numerical behavior: Execute the applicable DC, transient and AC cases with declared independent references, units, stimuli and tolerances; add event/noise cases only under an explicitly qualified capability. Record numerical discrepancies and solver failures, not formal equivalence claims.
  - [ ] **F-052.E — Optimization review and output quality.** Review fixture redundancy and result-retention cost without weakening meaningful cases or tolerances. Apply the proportionate review rule above; document no new optimization required when justified.
  - [ ] **F-052.F — Scale, determinism and compatibility.** Run representative parameter/sweep sizes and repeated seeded cases with deterministic manifests.
  - [ ] **F-052.G — Evidence, documentation and acceptance.** Retain the applicable evidence, capability limits and reproduction/demonstration record for 52; complete review, verified integration and any separate closure under the centralized Foundation acceptance rules.

- [ ] **Increment 53 — Cross-tool analog portability checks**
  - [ ] **F-053.A — Architecture, scope and extensibility.** Keep second-tool support optional and separately capability-qualified; compare only shared language and analysis semantics.
  - [ ] **F-053.B — Implementation and integration.** Add an optional second tool adapter, tolerance-based comparisons, and language-versus-tool failure classification.
  - [ ] **F-053.C — Correctness, rejection and predecessor regression.** Test disagreement, unsupported analysis, tolerance violation and adapter error classifications with common-model fixtures.
  - [ ] **F-053.D — Independent validation and applicability**
    - [ ] **F-053.D.1** Required baseline: Retain the applicable generated-model compile and numerical evidence from 48-52 with declared reference/tolerance contracts.
    - [ ] **F-053.D.2** Optional second-tool applicability: Record the selected second adapter, supported shared profile and capability limits. Optional use of that adapter does not waive the existing requirement to deliver and qualify its advertised functionality.
    - [ ] **F-053.D.3** Selected cross-tool execution: Run the selected shared generated models on the delivered second adapter and compare declared metrics/tolerances; retain unsupported and disagreement results separately. Required qualification stays blocked until actual execution exists.
  - [ ] **F-053.E — Optimization review and output quality.** Review portable model normalization without rewriting unsupported constructs into approximations. Apply the proportionate review rule above; document no new optimization required when justified.
  - [ ] **F-053.F — Scale, determinism and compatibility.** Compare supported tool/version/profile combinations and repeatable cross-tool result alignment.
  - [ ] **F-053.G — Evidence, documentation and acceptance.** Retain the applicable evidence, capability limits and reproduction/demonstration record for 53; complete review, verified integration and any separate closure under the centralized Foundation acceptance rules.

## Phase 4 — Digital semantics, portable Verilog, open-source verification, mixed signal, and Verilog-AMS

- [ ] **Increment 54 — Digital signed/unsigned type, literal, native enum ABI, and port layer**
  - [ ] **F-054.A — Architecture, scope and extensibility.** Reuse frozen 15/19 typed values, roles, enum ABI and storage intent; do not derive signedness or shape from backend syntax.
  - [ ] **F-054.B — Implementation and integration**
    - [ ] **F-054.B.1** Implement directionless storable `Struct`, non-storable digital `Interface`, named `Role`, plain/`Valid`/`Stream` interface members, scalar/vector resolved-net types, typed digital inout endpoints, read/drive/high-impedance semantics, drive modes, and exact port/member ABI identity.
    - [ ] **F-054.B.2** Add bit/logic, signless `Bits`, unsigned `UInt`, two's-complement `SInt`, exact signed/negative literals, signed parameters/localparams, signed aggregate fields and memory elements, ranked parameterized `Vec` and nested shaped values, canonical row-major indexing/flattening and exact shape connections, structural storage versus `Mem`, numeric conversion versus bit reinterpretation, integers, reals, nets/variables, directions, four-state policy, native Scala enum derivation, semantic enum types/cases, canonical sequential/one-hot/Gray/custom encodings, safe decode, exhaustive selection, enum aggregates/protocols/parameters/memories, ABI hashes, and compatible CIRCT/Nodal lowering.
  - [ ] **F-054.C — Correctness, rejection and predecessor regression.** Cover signed extrema, illegal widths/encodings, role/storage misuse, shape mismatch and explicit conversion boundaries.
  - [ ] **F-054.D — Independent validation and applicability**
    - [ ] **F-054.D.1** Compiler and target witnesses: Retain actual construction/IR and any generated HDL promised here, with source maps and structural checks at the implemented boundary. A future backend is not an executed tool result.
    - [ ] **F-054.D.2** Independent qualification handoff: Name the applicable 65/66 parse-and-simulation, 67 synthesis/equivalence/core-proof and 72 AMS-output cases. Preserve all original proof obligations and limit early claims; do not make a later consumer parent a reverse prerequisite.
  - [ ] **F-054.E — Optimization review and output quality.** Review type/shape interning and enum metadata growth; preserve exact symbolic widths and stable ABI names. Apply the proportionate review rule above; document no new optimization required when justified.
  - [ ] **F-054.F — Scale, determinism and compatibility.** Exercise ranked nested types, interface arrays and many enum cases; retain deterministic ABI hashes.
  - [ ] **F-054.G — Evidence, documentation and acceptance.** Retain the applicable evidence, capability limits and reproduction/demonstration record for 54; complete review, verified integration and any separate closure under the centralized Foundation acceptance rules.

- [ ] **Increment 55 — Digital expressions, bounded hardware iteration, and continuous assignments**
  - Original scope retained: Add typed expression DAGs with arithmetic, logic, bitwise, comparisons, concatenation, extraction, conditionals, multidimensional index/slice/flatten/reshape, exact width/sign/shape and mixed-sign rules, arithmetic/logical shifts, explicit signed casts/conversions, continuous assignment, typed hardware `map`/`zip`/`reduce`/`fold`/`scan`, safe single-use inlining, shared/observable/target-required materialization with reason codes, and bounded hardware iteration with finite static/symbolic bounds, ordered effects, dependency/index/driver checks, and deterministic unrolled versus procedural-loop lowering candidates. Reject runtime trip counts, structural declarations, hidden multi-cycle behavior, unbounded/data-dependent loops, accidental flat-carrier arithmetic, latches, and combinational cycles.
  - [ ] **F-055.A — Architecture, scope and extensibility.** Keep typed expression/effect DAGs and finite staged loop bounds distinct from structural construction under 54.
  - [ ] **F-055.B — Implementation and integration**
    - [ ] **F-055.B.1** Implement typed public/frontend expression and collection operations with exact widths, signs, shapes and explicit conversions.
    - [ ] **F-055.B.2** Carry DAG, finite hardware-loop bounds, induction/effect and materialization metadata through the bridge and native IR/verifiers.
    - [ ] **F-055.B.3** Implement continuous assignments and the approved deterministic unrolled/procedural-loop lowering boundary; retain source names and reject hidden latency or structural creation.
  - [ ] **F-055.C — Correctness, rejection and predecessor regression.** Test mixed-sign/narrowing errors, out-of-bounds indexing, loop-carried dependencies, driver conflicts and hidden latency; combine enums/shapes with expressions.
  - [ ] **F-055.D — Independent validation and applicability**
    - [ ] **F-055.D.1** Compiler and target witnesses: Retain actual construction/IR and any generated HDL promised here, with source maps and structural checks at the implemented boundary. A future backend is not an executed tool result.
    - [ ] **F-055.D.2** Independent qualification handoff: Name the applicable 65/66 parse-and-simulation, 67 synthesis/equivalence/core-proof and 72 AMS-output cases. Preserve all original proof obligations and limit early claims; do not make a later consumer parent a reverse prerequisite.
  - [ ] **F-055.E — Optimization review and output quality.** Review sharing and safe inlining without arithmetic reassociation; preserve iteration order, source aliases and materialization reasons. Apply the proportionate review rule above; document no new optimization required when justified.
  - [ ] **F-055.F — Scale, determinism and compatibility.** Exercise deep/shared DAGs, large finite loop envelopes and rank/parameter variation with deterministic output.
  - [ ] **F-055.G — Evidence, documentation and acceptance.** Retain the applicable evidence, capability limits and reproduction/demonstration record for 55; complete review, verified integration and any separate closure under the centralized Foundation acceptance rules.

- [ ] **Increment 56 — Implicit-domain registers, enum state, and flat FSM semantics**
  - Original scope retained: Implement `Reg`, `RegNext`, reset/uninitialized state, enum registers, exhaustive switches, `when` priority, enables, manual FSMs, concise high-level flat FSMs, entry/active/exit/transition actions, exclusive/priority transitions, terminal/completion states, local compact/one-hot/Gray/custom/Auto encoding, illegal-state policies, graph diagnostics, memory-port ownership, and CIRCT/Nodal sequential lowering without exposing normal `always` syntax or hidden boot state.
  - [ ] **F-056.A — Architecture, scope and extensibility.** Reuse explicit domain/state ownership and canonical enum ABI; separate local FSM encoding from external values.
  - [ ] **F-056.B — Implementation and integration**
    - [ ] **F-056.B.1** Implement public/frontend state, enum/FSM and priority construction with explicit reset/domain ownership.
    - [ ] **F-056.B.2** Integrate native state/transition/action, encoding and memory-port verification, preserving canonical external enum ABI.
    - [ ] **F-056.B.3** Lower the approved sequential profile through Nodal/CIRCT with retained action/reset/illegal-state behavior and source identities.
  - [ ] **F-056.C — Correctness, rejection and predecessor regression.** Test reset/enable priority, uninitialized state, illegal encodings, overlapping transitions, incomplete actions and multiple drivers.
  - [ ] **F-056.D — Independent validation and applicability**
    - [ ] **F-056.D.1** Compiler and target witnesses: Retain actual construction/IR and any generated HDL promised here, with source maps and structural checks at the implemented boundary. A future backend is not an executed tool result.
    - [ ] **F-056.D.2** Independent qualification handoff: Name the applicable 65/66 parse-and-simulation, 67 synthesis/equivalence/core-proof and 72 AMS-output cases. Preserve all original proof obligations and limit early claims; do not make a later consumer parent a reverse prerequisite.
  - [ ] **F-056.E — Optimization review and output quality.** Review redundant state/materialization without hidden boot states or changes to reset/action ordering. Apply the proportionate review rule above; document no new optimization required when justified.
  - [ ] **F-056.F — Scale, determinism and compatibility.** Exercise many states/transitions and several encoding/reset policies; preserve stable state identity and reports.
  - [ ] **F-056.G — Evidence, documentation and acceptance.** Retain the applicable evidence, capability limits and reproduction/demonstration record for 56; complete review, verified integration and any separate closure under the centralized Foundation acceptance rules.

- [ ] **Increment 57 — Clock/reset domains, CDC/RDC primitives, and low-level event escape**
  - Original scope retained: Implement domain construction/application, external/default/generated binding, relationship graphs, reset policies, async-assert/sync-release, timing provenance, all semantic CDC/RDC operations, gates/muxes, waivers, and restricted low-level processes.
  - [ ] **F-057.A — Architecture, scope and extensibility.** Use typed domain relationships and crossing/reset provenance; equal frequencies do not imply safe interchange.
  - [ ] **F-057.B — Implementation and integration**
    - [ ] **F-057.B.1** Implement public domain binding, relationships, typed crossing/reset primitives, gates/muxes and waivers.
    - [ ] **F-057.B.2** Propagate timing/crossing/reset provenance through hierarchy and bridge/native IR; implement mandatory rejection and source mapping.
    - [ ] **F-057.B.3** Lower supported owned crossing/reset/clock structures and quarantine the low-level event escape under the frozen capability contract.
  - [ ] **F-057.C — Correctness, rejection and predecessor regression.** Mutate direct/multibit/pulse/reconvergent crossings, reset release, gates and low-level escapes; test legal primitive counterparts.
  - [ ] **F-057.D — Independent validation and applicability**
    - [ ] **F-057.D.1** Compiler and target witnesses: Retain actual construction/IR and any generated HDL promised here, with source maps and structural checks at the implemented boundary. A future backend is not an executed tool result.
    - [ ] **F-057.D.2** Independent qualification handoff: Name the applicable 65/66 parse-and-simulation, 67 synthesis/equivalence/core-proof and 72 AMS-output cases. Preserve all original proof obligations and limit early claims; do not make a later consumer parent a reverse prerequisite.
  - [ ] **F-057.E — Optimization review and output quality.** Review crossing-graph traversal and generated reset/CDC structures without merging independent synchronizers. Apply the proportionate review rule above; document no new optimization required when justified.
  - [ ] **F-057.F — Scale, determinism and compatibility.** Exercise many domains, nested relationships and crossing instances; keep deterministic waiver and constraint identities.
  - [ ] **F-057.G — Evidence, documentation and acceptance.** Retain the applicable evidence, capability limits and reproduction/demonstration record for 57; complete review, verified integration and any separate closure under the centralized Foundation acceptance rules.

- [ ] **Increment 58 — Domain-aware hierarchy, reusable statecharts, and bounded recursive control**
  - [ ] **F-058.A — Architecture, scope and extensibility.** Compose roles, domains, symbolic hierarchy and immutable statecharts using 54-57 contracts; bound structural and runtime recursion separately.
  - [ ] **F-058.B — Implementation and integration**
    - [ ] **F-058.B.1** Propagate selected roles, nested interface members, domain provenance, resolved-net identity, black-box/top-level inout pass-through, conservative-terminal topology, symbolic interface arrays, and stable logical-to-physical interface paths through hierarchy.
    - [ ] **F-058.B.2** Implement default-domain inheritance, typed named-domain binding, inferred clock/reset ports, symbolic parameters, domain-polymorphic modules, parameterized shaped ports/instances, structural `generate` regions with symbolic bounds/nested legal generation, deterministic index-aware hierarchy and sink-affinity state naming, and deterministic variants only for material edge/reset differences. Keep ordinary Scala loops elaboration-only and preserve native target generate instead of clone-per-value specialization.
    - [ ] **F-058.B.3** Implement immutable reusable `FsmDef`/fragment candidates, explicit runtime bindings, nested submachines, typed completion/cancellation, parallel join policies, timed/protocol-aware states, finite elaboration recursion, and explicit bounded call/return stack contracts with overflow/underflow, reset, domain, report, and proof metadata. Reject unbounded recursion and accidental dynamic capture.
  - [ ] **F-058.C — Correctness, rejection and predecessor regression.** Test missing bindings, illegal capture, recursion/stack limits, join deadlock, reset behavior and generated boundary misuse.
  - [ ] **F-058.D — Independent validation and applicability**
    - [ ] **F-058.D.1** Compiler and target witnesses: Retain actual construction/IR and any generated HDL promised here, with source maps and structural checks at the implemented boundary. A future backend is not an executed tool result.
    - [ ] **F-058.D.2** Independent qualification handoff: Name the applicable 65/66 parse-and-simulation, 67 synthesis/equivalence/core-proof and 72 AMS-output cases. Preserve all original proof obligations and limit early claims; do not make a later consumer parent a reverse prerequisite.
  - [ ] **F-058.E — Optimization review and output quality.** Review repeated hierarchy/FSM expansion and names; preserve one module per structure and explicit state ownership. Apply the proportionate review rule above; document no new optimization required when justified.
  - [ ] **F-058.F — Scale, determinism and compatibility.** Exercise deep hierarchy, nested/parallel machines and bounded call stacks across parameter envelopes.
  - [ ] **F-058.G — Evidence, documentation and acceptance.** Retain the applicable evidence, capability limits and reproduction/demonstration record for 58; complete review, verified integration and any separate closure under the centralized Foundation acceptance rules.

- [ ] **Increment 59 — Pipeline transaction graph, latency provenance, and IR contract**
  - [ ] **F-059.A — Architecture, scope and extensibility.** Keep transaction identity, effect barriers, protocol roles and latency variables explicit; preserve interface/AMS ownership from 54-58.
  - [ ] **F-059.B — Implementation and integration**
    - [ ] **F-059.B.1** Preserve logical interface roles and ABI while extracting plain/`Valid`/`Stream` transaction graphs; protocol scheduling may insert pipeline-owned storage but cannot change role ownership, inout resolution, AMS topology, or explicit bridge semantics.
    - [ ] **F-059.B.2** Represent fixed-rate, valid-only, and elastic regions as single-domain feed-forward transaction graphs with protocol tokens, transaction identity, stage/latency variables, sideband demand, reconvergence constraints, exact/ranged latency, hard anchors, reset/control policy, parameter envelopes, and operation delay/latency metadata. Document selective CIRCT reuse.
  - [ ] **F-059.C — Correctness, rejection and predecessor regression.** Test mixed-domain/effect inputs, incompatible roles, contradictory latency anchors and parameter envelopes at graph construction.
  - [ ] **F-059.D — Independent validation and applicability**
    - [ ] **F-059.D.1** Compiler and target witnesses: Retain actual construction/IR and any generated HDL promised here, with source maps and structural checks at the implemented boundary. A future backend is not an executed tool result.
    - [ ] **F-059.D.2** Independent qualification handoff: Name the applicable 65/66 parse-and-simulation, 67 synthesis/equivalence/core-proof and 72 AMS-output cases. Preserve all original proof obligations and limit early claims; do not make a later consumer parent a reverse prerequisite.
  - [ ] **F-059.E — Optimization review and output quality.** Review graph duplication and sideband demand representation without premature scheduling or arithmetic changes. Apply the proportionate review rule above; document no new optimization required when justified.
  - [ ] **F-059.F — Scale, determinism and compatibility.** Exercise reconvergent graphs, fanout and sideband count; retain deterministic normalized transaction IDs.
  - [ ] **F-059.G — Evidence, documentation and acceptance.** Retain the applicable evidence, capability limits and reproduction/demonstration record for 59; complete review, verified integration and any separate closure under the centralized Foundation acceptance rules.

- [ ] **Increment 60 — Fixed-rate and valid-only automatic scheduling**
  - Original scope retained: Schedule acyclic II=1 datapaths under exact/ranged/auto latency and target-period constraints; insert pipeline-owned registers, balance operands and sidebands, propagate `Valid` bubbles, preserve finite-width semantics, and emit deterministic schedules, reports, normalized IR, and golden Verilog-AMS.
  - [ ] **F-060.A — Architecture, scope and extensibility.** Schedule only pipeline-owned acyclic single-domain II=1 regions from 59 with declared timing inputs.
  - [ ] **F-060.B — Implementation and integration**
    - [ ] **F-060.B.1** Implement deterministic exact/ranged/auto scheduling with explicit operation models and finite parameter envelopes.
    - [ ] **F-060.B.2** Integrate pipeline-owned register insertion, operand/sideband balance and Valid bubble/reset control into native lowering.
    - [ ] **F-060.B.3** Produce the promised schedules, reports, normalized IR and actual golden Verilog-AMS at the supported compiler boundary.
  - [ ] **F-060.C — Correctness, rejection and predecessor regression.** Test exact/ranged latency, infeasible targets, reset/valid bubbles and reconvergent data/sideband alignment.
  - [ ] **F-060.D — Independent validation and applicability**
    - [ ] **F-060.D.1** Compiler and target witnesses: Retain actual construction/IR and any generated HDL promised here, with source maps and structural checks at the implemented boundary. A future backend is not an executed tool result.
    - [ ] **F-060.D.2** Independent qualification handoff: Name the applicable 65/66 parse-and-simulation, 67 synthesis/equivalence/core-proof and 72 AMS-output cases. Preserve all original proof obligations and limit early claims; do not make a later consumer parent a reverse prerequisite.
  - [ ] **F-060.E — Optimization review and output quality.** Review unnecessary balancing registers and repeated scheduling work; prove every introduced delay preserves transaction arithmetic. Apply the proportionate review rule above; document no new optimization required when justified.
  - [ ] **F-060.F — Scale, determinism and compatibility.** Exercise graph depth/fanout and parameter envelopes; require stable schedules, stage names and hashes.
  - [ ] **F-060.G — Evidence, documentation and acceptance.** Retain the applicable evidence, capability limits and reproduction/demonstration record for 60; complete review, verified integration and any separate closure under the centralized Foundation acceptance rules.

- [ ] **Increment 61 — Elastic automatic pipeline and backpressure synthesis**
  - Original scope retained: Lower `Stream[T]` regions to full-throughput ready/valid stages with elastic registers, skid buffers, registered-ready cuts, bubble/stall propagation, capacity accounting, ready-loop checks, stall-stability assertions, and proofs of no loss, duplication, or reordering.
  - [ ] **F-061.A — Architecture, scope and extensibility.** Extend 59-60 with explicit elastic occupancy and ready-path ownership, not same-cycle fixed-latency assumptions.
  - [ ] **F-061.B — Implementation and integration**
    - [ ] **F-061.B.1** Implement elastic stage/storage, skid buffering and registered-ready cuts with explicit occupancy/capacity.
    - [ ] **F-061.B.2** Integrate ready-loop verification, bubble/stall/reset behavior and deterministic protocol lowering.
    - [ ] **F-061.B.3** Retain and satisfy the promised stall-stability and no-loss/duplication/reordering assertions/proof obligations using declared methods; a later tool handoff does not waive them.
  - [ ] **F-061.C — Correctness, rejection and predecessor regression.** Test stalls/bubbles/reset, capacity boundaries and ready loops; retain no-loss, no-duplication and ordering proof obligations.
  - [ ] **F-061.D — Independent validation and applicability**
    - [ ] **F-061.D.1** Compiler and target witnesses: Retain actual construction/IR and any generated HDL promised here, with source maps and structural checks at the implemented boundary. A future backend is not an executed tool result.
    - [ ] **F-061.D.2** Independent qualification handoff: Name the applicable 65/66 parse-and-simulation, 67 synthesis/equivalence/core-proof and 72 AMS-output cases. Preserve all original proof obligations and limit early claims; do not make a later consumer parent a reverse prerequisite.
  - [ ] **F-061.E — Optimization review and output quality.** Review redundant buffers and ready cuts without changing published capacity, fall-through or throughput. Apply the proportionate review rule above; document no new optimization required when justified.
  - [ ] **F-061.F — Scale, determinism and compatibility.** Exercise long pipelines and reconvergence under deterministic backpressure sequences with stable capacity reports.
  - [ ] **F-061.G — Evidence, documentation and acceptance.** Retain the applicable evidence, capability limits and reproduction/demonstration record for 61; complete review, verified integration and any separate closure under the centralized Foundation acceptance rules.

- [ ] **Increment 62 — Timing/resource models and target-driven partitioning**
  - [ ] **F-062.A — Architecture, scope and extensibility.** Version operation timing/resource models independently of semantic IR and distinguish estimates from implemented timing.
  - [ ] **F-062.B — Implementation and integration.** Add versioned generic, FPGA, ASIC, simulator, and user operation models covering width/sign-dependent delay, fixed multi-cycle latency, implementation choices, resource preferences, uncertainty, and finite parameter envelopes. Implement target scheduling with infeasibility diagnostics and optional synthesis-feedback import without claiming timing closure from estimates.
  - [ ] **F-062.C — Correctness, rejection and predecessor regression.** Reject missing/contradictory models and infeasible envelope constraints; test signed/width-dependent and fixed-multicycle cases.
  - [ ] **F-062.D — Independent validation and applicability**
    - [ ] **F-062.D.1** Compiler and target witnesses: Retain actual construction/IR and any generated HDL promised here, with source maps and structural checks at the implemented boundary. A future backend is not an executed tool result.
    - [ ] **F-062.D.2** Independent qualification handoff: Name the applicable 65/66 parse-and-simulation, 67 synthesis/equivalence/core-proof and 72 AMS-output cases. Preserve all original proof obligations and limit early claims; do not make a later consumer parent a reverse prerequisite.
  - [ ] **F-062.E — Optimization review and output quality.** Review model lookup and partition search cost; optimization must not change arithmetic or promise physical closure. Apply the proportionate review rule above; document no new optimization required when justified.
  - [ ] **F-062.F — Scale, determinism and compatibility.** Exercise model-table size, DAG size and envelope corners with repeatable schedules and model hashes.
  - [ ] **F-062.G — Evidence, documentation and acceptance.** Retain the applicable evidence, capability limits and reproduction/demonstration record for 62; complete review, verified integration and any separate closure under the centralized Foundation acceptance rules.

- [ ] **Increment 63 — Pipeline controls, anchors, memories, and multi-cycle units**
  - [ ] **F-063.A — Architecture, scope and extensibility.** Reuse memory/external-effect contracts and explicit commit barriers; define control priority before permitting movement.
  - [ ] **F-063.B — Implementation and integration.** Freeze and implement typed flush/cancel/replay and commit barriers, reset/stall/enable priority, named hard cuts, same-stage groups, synchronous memory latency/ordering, fixed-latency blocks, and elastic wrappers for variable-latency units. Reject or isolate side effects that cannot move safely.
  - [ ] **F-063.C — Correctness, rejection and predecessor regression.** Test simultaneous reset/stall/flush, cancellation around commit, memory collision/latency and variable-latency units.
  - [ ] **F-063.D — Independent validation and applicability**
    - [ ] **F-063.D.1** Compiler and target witnesses: Retain actual construction/IR and any generated HDL promised here, with source maps and structural checks at the implemented boundary. A future backend is not an executed tool result.
    - [ ] **F-063.D.2** Independent qualification handoff: Name the applicable 65/66 parse-and-simulation, 67 synthesis/equivalence/core-proof and 72 AMS-output cases. Preserve all original proof obligations and limit early claims; do not make a later consumer parent a reverse prerequisite.
  - [ ] **F-063.E — Optimization review and output quality.** Review buffering and repeated effect analysis without moving side effects or user-owned state across barriers. Apply the proportionate review rule above; document no new optimization required when justified.
  - [ ] **F-063.F — Scale, determinism and compatibility.** Exercise several memory ports, control combinations and unit latencies; preserve schedule/report compatibility.
  - [ ] **F-063.G — Evidence, documentation and acceptance.** Retain the applicable evidence, capability limits and reproduction/demonstration record for 63; complete review, verified integration and any separate closure under the centralized Foundation acceptance rules.

- [ ] **Increment 64 — Hierarchical composition, schedule stability, and bounded retiming**
  - [ ] **F-064.A — Architecture, scope and extensibility.** Make hierarchy latency contracts and pipeline-owned retiming limits explicit; reuse 58-63 provenance.
  - [ ] **F-064.B — Implementation and integration.** Compose regions/modules through explicit latency/protocol contracts, generate stable stage names and schedule hashes, diagnose latency drift, export reports/debug mappings, and retime only pipeline-owned registers inside declared boundaries—not across user state, CDC/RDC, analog boundaries, memories, side effects, parameter-envelope barriers, or observability anchors.
  - [ ] **F-064.C — Correctness, rejection and predecessor regression.** Mutate latency drift, forbidden boundary movement and observability anchors; check reset/protocol behavior after allowed retiming.
  - [ ] **F-064.D — Independent validation and applicability**
    - [ ] **F-064.D.1** Compiler and target witnesses: Retain actual construction/IR and any generated HDL promised here, with source maps and structural checks at the implemented boundary. A future backend is not an executed tool result.
    - [ ] **F-064.D.2** Independent qualification handoff: Name the applicable 65/66 parse-and-simulation, 67 synthesis/equivalence/core-proof and 72 AMS-output cases. Preserve all original proof obligations and limit early claims; do not make a later consumer parent a reverse prerequisite.
  - [ ] **F-064.E — Optimization review and output quality.** Review only bounded owned-register retiming; retain before/after semantic identities and validation for every move. Apply the proportionate review rule above; document no new optimization required when justified.
  - [ ] **F-064.F — Scale, determinism and compatibility.** Exercise nested regions and stable schedule hashes under unrelated hierarchy/order changes.
  - [ ] **F-064.G — Evidence, documentation and acceptance.** Retain the applicable evidence, capability limits and reproduction/demonstration record for 64; complete review, verified integration and any separate closure under the centralized Foundation acceptance rules.

- [ ] **Increment 65 — Digital-only classification, Backend.Auto, and portable Verilog backend**
  - [ ] **F-065.A — Architecture, scope and extensibility**
    - [ ] **F-065.A.1** Treat Foundation Increments 153-157 as mandatory naming prerequisites. Lower structured caller/local paths into deterministic portable-Verilog identifiers and preserve raw binders, aliases, provenance, definition/invocation locations, and materialization reasons in manifests and source maps.
    - [ ] **F-065.A.2** Concurrent or temporal properties and compiler-generated verification monitors are excluded from `digital-verilog-synth`; they remain formal, simulation, or sidecar artifacts.
    - [ ] **F-065.A.3** Keep broad SystemVerilog optional and separately gated; portable Verilog remains required for open-source interoperability.
    - [ ] **F-065.A.4** Reuse 54-64 semantics and mandatory naming prerequisites 153-157; keep IEEE 1364-2005 separate from future SystemVerilog.
  - [ ] **F-065.B — Implementation and integration**
    - [ ] **F-065.B.1** Deterministically flatten nested `Interface`/`Struct`/`Valid`/`Stream` members, emit logical Interface ABI/source-map manifests, and lower supported digital inout to net-typed ports plus explicit width-safe tri-state assignments. Reject analog members and profile-unsupported internal resolved nets without silent mux conversion.
    - [ ] **F-065.B.2** Implement transitive digital-only/analog-only/mixed-signal classification, construct inventories, deterministic `Backend.Auto` selection, explicit capability rejection, and machine-readable selection evidence.
    - [ ] **F-065.B.3** Emit the portable synthesizable Verilog profile with exact signed vector ports/wires/registers/parameters/localparams/memories/aggregate fields, explicitly sized signed literals, typed shifts/casts, parameterized multidimensional `Vec` ports as canonical flat packed carriers, verified row-major offset/slice/reshape formulas, deterministic signed element views, structural `Vec` versus `Mem` evidence, structural `genvar` generate loops, bounded procedural `for` loops or verified unrolled equivalents, symbolic parameters/generate, hierarchy, flattened aggregates/protocols, canonical enum vectors and member `localparam`s, enum configuration parameters, flat/hierarchical/parallel FSM state and completion logic, clocks/resets, memories, CDC/RDC, automatic pipelines, black boxes, explicitly synthesized immediate assertions, verification-only formal hooks, safe expression inlining and semantic temporary/state naming, materialization/shape/storage/signed/loop/enum/FSM manifests, expression-level source maps, deterministic formatting, target reparse, and exact golden fixtures.
  - [ ] **F-065.C — Correctness, rejection and predecessor regression**
    - [ ] **F-065.C.1** Add exact goldens for ordinary/local/nested methods, lambdas, multiline expressions, separately compiled libraries, repeated calls, loops/generate/unrolling, sharing/CSE, backticked or reserved identifiers, collision sanitization, safe-inline/readable/debug materialization profiles, different working directories, and repeated builds. Record the selected name, original binder, aliases, provenance, materialization reason, sanitization, and collision qualification.
    - [ ] **F-065.C.2** Exercise transitive classification, signed/shape/generate/state/protocol interactions and synth-profile exclusion of temporal monitors.
  - [ ] **F-065.D — Independent validation and applicability**
    - [ ] **F-065.D.1** Generated syntax boundary: Retain actual portable-Verilog artifacts and internal reparse/goldens, including non-default parameters and source maps; this is not yet independent-tool qualification.
    - [ ] **F-065.D.2** Independent downstream qualification: Provide retained cases and acceptance limits to 66 for independent syntax/elaboration and behavioral simulation, and 67 for synthesis/structure and applicable equivalence/formal checks. Do not create a 65-to-67-to-65 parent cycle.
  - [ ] **F-065.E — Optimization review and output quality**
    - [ ] **F-065.E.1** Preserve safe expression inlining: source such as `val widenedName = a + b; out := widenedName` may emit `assign out = a + b;` when exact semantics permit, while `widenedName` remains traceable in IR/source maps and no wire is materialized solely to expose the name.
    - [ ] **F-065.E.2** When materialization is required, emit caller-prefixed helper-local names such as `pixelResult_widenedSum`; repeated calls must produce semantic paths such as `leftResult_widenedSum` and `rightResult_widenedSum`. A returned value may emit as `pixelResult` while retaining `pixelResult_clippedSum` as an alias.
    - [ ] **F-065.E.3** Use `_net_<operation>_<stable-index>` only for genuinely unnamed Nodal-owned combinational objects, with corresponding `_reg_*`, `_mem_*`, `_inst_*`, and `_gen_*` namespaces for other generated objects. Never emit `_zz*`, `_T*`, `_GEN*`, traversal-counter-only, `expr_<number>`, or `tmp_<number>` names for accepted Nodal-owned HDL.
    - [ ] **F-065.E.4** Review anonymous wires, duplicated modules/casts and avoidable materialization; retain symbolic widths and source aliases. Apply the proportionate review rule above; document no new optimization required when justified.
  - [ ] **F-065.F — Scale, determinism and compatibility.** Exercise deep hierarchy, large shaped interfaces and parameter overrides across repeated builds and working directories.
  - [ ] **F-065.G — Evidence, documentation and acceptance.** Retain the applicable evidence, capability limits and reproduction/demonstration record for 65; complete review, verified integration and any separate closure under the centralized Foundation acceptance rules.

- [ ] **Increment 66 — Open-source digital lint, simulation, waveforms, and cocotb interoperability**
  - [ ] **F-066.A — Architecture, scope and extensibility.** Reuse 65 generated artifacts and logical interfaces; qualify Verilator and Icarus as separate execution paths.
  - [ ] **F-066.B — Implementation and integration**
    - [ ] **F-066.B.1** Add typed interface-role drivers/monitors and digital inout high-Z/readback/contention/open-drain/hierarchy tests, with logical Interface ABI metadata for Scala simulation, cocotb, waveforms, and source correlation.
    - [ ] **F-066.B.2** Pin and integrate Verilator and Icarus Verilog; run independent parse/elaboration, strong lint, fast compiled simulation, event-driven smoke simulation, normalized diagnostics, deterministic seeds, VCD/FST waveforms, supported coverage, multidimensional flat-layout/index/reshape fixtures, signed-element-view tests, no-avoidable-anonymous-wire goldens, and source-map correlation for inlined expressions.
    - [ ] **F-066.B.3** Extend the Scala simulation API with typed signed/unsigned/bit-container and aggregate/protocol access, clock/reset-domain stimulus, multiple clocks, randomized reset release, `Valid`/`Stream` drivers/monitors/scoreboards, signed boundary/shift/comparison checks, procedural-versus-unrolled loop differential fixtures, stalls/bubbles, latency-aware checking, timeouts, caching, and artifacts.
    - [ ] **F-066.B.4** Add optional cocotb metadata/runner support for Icarus and Verilator without making Python or cocotb define Nodal semantics.
  - [ ] **F-066.C — Correctness, rejection and predecessor regression.** Test malformed generated HDL, clock/reset/stall cases, four-state inout behavior and unsupported-tool diagnostics; optional cocotb stays separate.
  - [ ] **F-066.D — Independent validation and applicability**
    - [ ] **F-066.D.1** Independent syntax and elaboration: Run pinned Verilator lint and Icarus parse/elaboration on actual generated portable Verilog, with explicit language modes and capability results.
    - [ ] **F-066.D.2** Behavioral simulation: Execute the selected generated-DUT cases independently in the applicable simulator lanes; retain stimuli, seeds, waveforms, source correlation and reference comparisons. Keep 67 synthesis/proof evidence separate.
  - [ ] **F-066.E — Optimization review and output quality.** Review compile reuse and trace overhead without replacing generated-DUT execution with frontend-only models. Apply the proportionate review rule above; document no new optimization required when justified.
  - [ ] **F-066.F — Scale, determinism and compatibility.** Exercise long traces, multiple clocks and large transactions with deterministic seeds and tool-version manifests.
  - [ ] **F-066.G — Evidence, documentation and acceptance.** Retain the applicable evidence, capability limits and reproduction/demonstration record for 66; complete review, verified integration and any separate closure under the centralized Foundation acceptance rules.

- [ ] **Increment 67 — Yosys synthesis/equivalence and core SBY formal-readiness infrastructure**
  - [ ] **F-067.A — Architecture, scope and extensibility.** Separate synthesis structure, equivalence and core property proofs; retain verification-only monitors and future 109 API boundary.
  - [ ] **F-067.B — Implementation and integration**
    - [ ] **F-067.B.1** Verify flattened interface connectivity, full `Valid`/`Stream` ownership, top-level/black-box tri-state synthesis where supported, split-tristate boundary equivalence, internal resolved-net capability rejection, driver exclusivity assumptions, and native-versus-flat interface parity hooks.
    - [ ] **F-067.B.2** Pin and integrate Yosys, SBY, and selected solvers. Run hierarchy/process/memory/driver checks, target-neutral synthesis, inferred-latch/combinational-loop/black-box diagnostics, structural-`Vec` unexpected-memory-inference audit, normalized netlist emission, statistics, and parameter/shape/layout/generate elaboration matrices.
    - [ ] **F-067.B.3** Preserve stable property IDs, source maps, domain/reset/parameter metadata, normalized tasks, and adapter evidence in forms compatible with [ADR 0014](../architecture/0014-target-neutral-formal-verification.md). Generate core enum/FSM legality, one-hot, allowed-transition, reset-convergence, deadlock, completion, and bounded-stack checks. Use portable hooks or sidecar harnesses without freezing a user-authored formal API or binding Nodal semantics to SVA/SBY syntax.
  - [ ] **F-067.C — Correctness, rejection and predecessor regression.** Test latch/loop/driver/black-box rejection and failing/vacuous/timeout proof controls; combine signed shapes, loops, FSMs and pipelines.
  - [ ] **F-067.D — Independent validation and applicability**
    - [ ] **F-067.D.1** Add RTL-to-optimized/netlist equivalence, including signed width/extension/cast/shift checks, multidimensional flatten/unpack/index/reshape and inline-versus-debug materialization equivalence, generate/procedural/unrolled-loop equivalence and index-bound properties, latency-aware fixed-pipeline, and protocol-aware elastic checks.
    - [ ] **F-067.D.2** Add compiler-generated bounded/unbounded safety, cover, and selected liveness property suites for registers, resets, `Valid`/`Stream`, FIFOs, handshakes, synchronizers, CDC/RDC wrappers, and automatic pipelines. Retain traces and counterexamples as CI evidence.
    - [ ] **F-067.D.3** Synthesis and structural checks: Run pinned Yosys hierarchy/process/memory/driver and target-neutral synthesis checks on actual generated RTL; retain normalized netlists, statistics and declared black-box/storage assumptions.
    - [ ] **F-067.D.4** Equivalence: Execute the applicable RTL/optimized/netlist, parameter/layout and latency/protocol-aware equivalence cases, retaining configurations and counterexamples.
    - [ ] **F-067.D.5** Formal properties: Execute selected core SBY proof/cover tasks with property IDs, assumptions, solver/bound/result provenance and negative controls. Inconclusive or vacuous success claims are not accepted; user-authored formal API remains separate.
  - [ ] **F-067.E — Optimization review and output quality.** Review inferred cells/memories and transformed output against declared storage/latency semantics. Apply the proportionate review rule above; document no new optimization required when justified.
  - [ ] **F-067.F — Scale, determinism and compatibility.** Exercise parameter and shape matrices plus representative proof sizes with retained tool/resource observations.
  - [ ] **F-067.G — Evidence, documentation and acceptance.** Retain the applicable evidence, capability limits and reproduction/demonstration record for 67; complete review, verified integration and any separate closure under the centralized Foundation acceptance rules.

- [ ] **Increment 68 — Discrete real and mixed-signal net types**
  - [ ] **F-068.A — Architecture, scope and extensibility.** Distinguish discrete real resolution from conservative analog terminals and finite-width digital nets.
  - [ ] **F-068.B — Implementation and integration.** Implement `real`, `wreal` or profile equivalents, resolution, direction, sampling/update semantics, and portability.
  - [ ] **F-068.C — Correctness, rejection and predecessor regression.** Test competing real drivers, incompatible resolution, update ordering and unsupported backend profiles.
  - [ ] **F-068.D — Independent validation and applicability**
    - [ ] **F-068.D.1** Generated-profile structure: Retain actual selected Verilog-A/Verilog-AMS and digital-side artifacts, source maps and target reparse results, with explicit construct-level capability limits.
    - [ ] **F-068.D.2** Available independent lanes: Execute OpenVAF/ngspice checks only for qualified analog subsets and 66-67 digital checks only for applicable digital artifacts. Preserve original compile/check-versus-simulate boundaries.
    - [ ] **F-068.D.3** Mixed-signal qualification ownership: Retain the required mixed-signal reference, event-order and tolerance cases for 75/78. An unavailable AMS runner does not prove behavior, and it must not create a reverse dependency on its earlier compiler input.
  - [ ] **F-068.E — Optimization review and output quality.** Review resolution-tree expansion without changing update or sampling semantics. Apply the proportionate review rule above; document no new optimization required when justified.
  - [ ] **F-068.F — Scale, determinism and compatibility.** Exercise multi-driver graphs and hierarchical real nets with deterministic ordering and profile compatibility.
  - [ ] **F-068.G — Evidence, documentation and acceptance.** Retain the applicable evidence, capability limits and reproduction/demonstration record for 68; complete review, verified integration and any separate closure under the centralized Foundation acceptance rules.

- [ ] **Increment 69 — Analog/digital access and conversion semantics**
  - [ ] **F-069.A — Architecture, scope and extensibility.** Retain dimensions and source/destination domains through explicit conversion endpoints; reuse 57/68 contracts.
  - [ ] **F-069.B — Implementation and integration**
    - [ ] **F-069.B.1** Expose conversions only through typed interface bridge endpoints carrying physical dimensions, source/destination domains, thresholds, hysteresis, quantization, timing, transition, resolution, and model availability.
    - [ ] **F-069.B.2** Implement destination-domain samplers, thresholds/comparators, quantization, source-domain-aware DAC updates, transition shaping, event synchronization, and provenance transfer.
  - [ ] **F-069.C — Correctness, rejection and predecessor regression.** Test threshold/hysteresis/quantization boundaries, wrong units, illegal domain crossings and missing model capabilities.
  - [ ] **F-069.D — Independent validation and applicability**
    - [ ] **F-069.D.1** Generated-profile structure: Retain actual selected Verilog-A/Verilog-AMS and digital-side artifacts, source maps and target reparse results, with explicit construct-level capability limits.
    - [ ] **F-069.D.2** Available independent lanes: Execute OpenVAF/ngspice checks only for qualified analog subsets and 66-67 digital checks only for applicable digital artifacts. Preserve original compile/check-versus-simulate boundaries.
    - [ ] **F-069.D.3** Mixed-signal qualification ownership: Retain the required mixed-signal reference, event-order and tolerance cases for 75/78. An unavailable AMS runner does not prove behavior, and it must not create a reverse dependency on its earlier compiler input.
  - [ ] **F-069.E — Optimization review and output quality.** Review conversion temporaries without moving sampling, transition or event state. Apply the proportionate review rule above; document no new optimization required when justified.
  - [ ] **F-069.F — Scale, determinism and compatibility.** Exercise parameterized converter banks and asynchronous event sequences with stable provenance.
  - [ ] **F-069.G — Evidence, documentation and acceptance.** Retain the applicable evidence, capability limits and reproduction/demonstration record for 69; complete review, verified integration and any separate closure under the centralized Foundation acceptance rules.

- [ ] **Increment 70 — Connect modules and connect rules**
  - [ ] **F-070.A — Architecture, scope and extensibility.** Represent rule insertion and conservative access/topology explicitly, reusing hierarchy and bridge ownership.
  - [ ] **F-070.B — Implementation and integration**
    - [ ] **F-070.B.1** Integrate conservative `Terminal`/`Node`/`Branch` interface members, connect/sense/contribute role access, topology preservation, discipline conversion, and deterministic wrapper/interface ABI mapping.
    - [ ] **F-070.B.2** Implement declarations, rules, discipline insertion, direction/resolution analysis, hierarchy-wide application, and conflicts.
  - [ ] **F-070.C — Correctness, rejection and predecessor regression.** Test ambiguous rules, incompatible disciplines/directions, illegal sense/contribute access and hierarchy-wide conflicts.
  - [ ] **F-070.D — Independent validation and applicability**
    - [ ] **F-070.D.1** Generated-profile structure: Retain actual selected Verilog-A/Verilog-AMS and digital-side artifacts, source maps and target reparse results, with explicit construct-level capability limits.
    - [ ] **F-070.D.2** Available independent lanes: Execute OpenVAF/ngspice checks only for qualified analog subsets and 66-67 digital checks only for applicable digital artifacts. Preserve original compile/check-versus-simulate boundaries.
    - [ ] **F-070.D.3** Mixed-signal qualification ownership: Retain the required mixed-signal reference, event-order and tolerance cases for 75/78. An unavailable AMS runner does not prove behavior, and it must not create a reverse dependency on its earlier compiler input.
  - [ ] **F-070.E — Optimization review and output quality.** Review duplicate wrappers/rule traversal without changing connection sets or branch orientation. Apply the proportionate review rule above; document no new optimization required when justified.
  - [ ] **F-070.F — Scale, determinism and compatibility.** Exercise nested mixed interfaces and large rule sets with deterministic insertion and ABI mapping.
  - [ ] **F-070.G — Evidence, documentation and acceptance.** Retain the applicable evidence, capability limits and reproduction/demonstration record for 70; complete review, verified integration and any separate closure under the centralized Foundation acceptance rules.

- [ ] **Increment 71 — Mixed-domain, CDC/RDC, and scheduling verifier**
  - [ ] **F-071.A — Architecture, scope and extensibility.** Keep mandatory crossing, driver, role and topology verification independent of optional optimization.
  - [ ] **F-071.B — Implementation and integration**
    - [ ] **F-071.B.1** Verify interface role completeness, monitor access, nested-role connections, resolved-net drivers/contention, inout hierarchy, open-drain legality, conservative-terminal access/topology, and no implicit digital/analog/conservative/signal-flow conversion.
    - [ ] **F-071.B.2** Verify domain bindings, direct/combinational crossings, multi-bit misuse, pulses, reconvergence, reset release/reconvergence, generated clocks, gates/muxes, analog/digital legality, conversion loops, aggregate/shaped driver paths, latches, combinational/ready loops, structural-storage intent, drivers, waivers, and profile restrictions.
  - [ ] **F-071.C — Correctness, rejection and predecessor regression.** Use one relevant negative/mutation control per safety family, including aggregate paths, reset reconvergence and conversion loops.
  - [ ] **F-071.D — Independent validation and applicability**
    - [ ] **F-071.D.1** Generated-profile structure: Retain actual selected Verilog-A/Verilog-AMS and digital-side artifacts, source maps and target reparse results, with explicit construct-level capability limits.
    - [ ] **F-071.D.2** Available independent lanes: Execute OpenVAF/ngspice checks only for qualified analog subsets and 66-67 digital checks only for applicable digital artifacts. Preserve original compile/check-versus-simulate boundaries.
    - [ ] **F-071.D.3** Mixed-signal qualification ownership: Retain the required mixed-signal reference, event-order and tolerance cases for 75/78. An unavailable AMS runner does not prove behavior, and it must not create a reverse dependency on its earlier compiler input.
  - [ ] **F-071.E — Optimization review and output quality.** Review shared graph analyses and duplicate diagnostics; checks must not silently repair unsafe hardware. Apply the proportionate review rule above; document no new optimization required when justified.
  - [ ] **F-071.F — Scale, determinism and compatibility.** Exercise domain/driver/shape graph growth and long reconstructed source paths with repeatable findings.
  - [ ] **F-071.G — Evidence, documentation and acceptance.** Retain the applicable evidence, capability limits and reproduction/demonstration record for 71; complete review, verified integration and any separate closure under the centralized Foundation acceptance rules.

- [ ] **Increment 72 — Complete Verilog-AMS backend skeleton**
  - [ ] **F-072.A — Architecture, scope and extensibility**
    - [ ] **F-072.A.1** Treat Foundation Increments 153-157 as mandatory naming prerequisites and preserve the same structured source binder, caller aliases, provenance, and definition/invocation identity used by portable Verilog.
    - [ ] **F-072.A.2** Reuse analog/digital semantics and naming prerequisites 153-157; preserve distinct AMS capabilities and logical interface ABI.
  - [ ] **F-072.B — Implementation and integration**
    - [ ] **F-072.B.1** Flatten logical mixed-signal interfaces deterministically, including protocol leaves, resolved digital inout nets, discipline-qualified terminals, signal-flow values, and explicit bridges; emit the same Interface ABI/source-map manifest used by portable Verilog.
    - [ ] **F-072.B.2** Emit explicit inferred clock/reset ports; signed digital vectors/parameters/literals/casts; parameterized multidimensional digital values using the portable flat ABI and signed element views; structural generate and bounded procedural/unrolled loops; canonical enum localparams/vectors; flat, nested, parallel, timed, and bounded-procedure FSM state/action/completion logic; event processes lowered from high-level state and automatic schedules; fixed/valid/elastic pipeline registers and control; synchronizers/FIFOs; reset logic; gates/muxes; analog/digital declarations; disciplines; connect constructs; hierarchy; parameters; safe expression inlining and deterministic semantic state/temporary names; shape/layout/storage/materialization/check/signed/loop/enum/FSM/latency/schedule metadata; expression-level source maps; and mandatory target verification/reparse evidence.
  - [ ] **F-072.C — Correctness, rejection and predecessor regression**
    - [ ] **F-072.C.1** Add Verilog-A/Verilog-AMS parity goldens and manifests covering helper calls, repeated invocations, analog functions, events, contributions, conversion paths, readable/debug materialization, source maps, target reparse, and deterministic cross-backend name correlation.
    - [ ] **F-072.C.2** Test unsupported cross-profile constructs, analog-event/state interactions and loss of function-local naming metadata.
  - [ ] **F-072.D — Independent validation and applicability**
    - [ ] **F-072.D.1** Generated-profile structure: Retain actual selected Verilog-A/Verilog-AMS and digital-side artifacts, source maps and target reparse results, with explicit construct-level capability limits.
    - [ ] **F-072.D.2** Available independent lanes: Execute OpenVAF/ngspice checks only for qualified analog subsets and 66-67 digital checks only for applicable digital artifacts. Preserve original compile/check-versus-simulate boundaries.
    - [ ] **F-072.D.3** Mixed-signal qualification ownership: Retain the required mixed-signal reference, event-order and tolerance cases for 75/78. An unavailable AMS runner does not prove behavior, and it must not create a reverse dependency on its earlier compiler input.
  - [ ] **F-072.E — Optimization review and output quality**
    - [ ] **F-072.E.1** Apply function-local naming parity to analog procedural locals, user-defined analog-function locals, intermediate quantities, event expressions, branch/access calculations, mixed-signal bridge/conversion temporaries, and digital logic inside Verilog-AMS. Safely inline only where analog, event, scheduling, and contribution semantics remain exact; otherwise materialize the retained semantic name.
    - [ ] **F-072.E.2** Apply target-specific keyword, scope, escaping, and collision rules without losing the original Scala binder. Prefer semantic operation/branch/event/sink names for generated objects and prohibit Nodal-owned `_zz*` or traversal-counter fallbacks.
    - [ ] **F-072.E.3** Review inlining/materialization only where contribution, event and scheduling semantics remain exact. Apply the proportionate review rule above; document no new optimization required when justified.
  - [ ] **F-072.F — Scale, determinism and compatibility.** Exercise large mixed hierarchy and repeated helper calls across profiles with deterministic target names and maps.
  - [ ] **F-072.G — Evidence, documentation and acceptance.** Retain the applicable evidence, capability limits and reproduction/demonstration record for 72; complete review, verified integration and any separate closure under the centralized Foundation acceptance rules.

- [ ] **Increment 73 — ADC and DAC mixed-signal vertical slices**
  - [ ] **F-073.A — Architecture, scope and extensibility.** Integrate ADC/DAC slices only through explicit sampling, drive, clock/reset and finite parameter-envelope contracts.
  - [ ] **F-073.B — Implementation and integration.** Compile/check or simulate ADC/DAC models using implicit domains, typed mode/state enums, reusable hierarchical FSM control, automatically scheduled fixed and elastic digital datapaths, explicit sampling/drive, legal CDC, reset policies, parameter-envelope-safe scheduling, hierarchy, enum/FSM/pipeline/CDC/RDC reports, and deterministic parameterized Verilog-AMS.
  - [ ] **F-073.C — Correctness, rejection and predecessor regression.** Cover quantization limits, converter control FSMs, pipeline stalls, reset and legal/illegal CDC cases.
  - [ ] **F-073.D — Independent validation and applicability**
    - [ ] **F-073.D.1** Generated-profile structure: Retain actual selected Verilog-A/Verilog-AMS and digital-side artifacts, source maps and target reparse results, with explicit construct-level capability limits.
    - [ ] **F-073.D.2** Available independent lanes: Execute OpenVAF/ngspice checks only for qualified analog subsets and 66-67 digital checks only for applicable digital artifacts. Preserve original compile/check-versus-simulate boundaries.
    - [ ] **F-073.D.3** Mixed-signal qualification ownership: Retain the required mixed-signal reference, event-order and tolerance cases for 75/78. An unavailable AMS runner does not prove behavior, and it must not create a reverse dependency on its earlier compiler input.
  - [ ] **F-073.E — Optimization review and output quality.** Review redundant bridge/pipeline storage without changing conversion timing or accuracy assumptions. Apply the proportionate review rule above; document no new optimization required when justified.
  - [ ] **F-073.F — Scale, determinism and compatibility.** Exercise converter counts, hierarchy and envelope corners with stable parameterized modules and reports.
  - [ ] **F-073.G — Evidence, documentation and acceptance.** Retain the applicable evidence, capability limits and reproduction/demonstration record for 73; complete review, verified integration and any separate closure under the centralized Foundation acceptance rules.

- [ ] **Increment 74 — PLL/comparator mixed-signal vertical slice**
  - [ ] **F-074.A — Architecture, scope and extensibility.** Keep analog feedback, generated clocks and cross-domain conversion explicit in the PLL/comparator slice.
  - [ ] **F-074.B — Implementation and integration.** Exercise analog state, generated clocks, digital events, cross-domain conversion, feedback, reset behavior, and diagnostics.
  - [ ] **F-074.C — Correctness, rejection and predecessor regression.** Exercise threshold transitions, initial state, feedback/reset cases and unsafe domain relationships.
  - [ ] **F-074.D — Independent validation and applicability**
    - [ ] **F-074.D.1** Generated-profile structure: Retain actual selected Verilog-A/Verilog-AMS and digital-side artifacts, source maps and target reparse results, with explicit construct-level capability limits.
    - [ ] **F-074.D.2** Available independent lanes: Execute OpenVAF/ngspice checks only for qualified analog subsets and 66-67 digital checks only for applicable digital artifacts. Preserve original compile/check-versus-simulate boundaries.
    - [ ] **F-074.D.3** Mixed-signal qualification ownership: Retain the required mixed-signal reference, event-order and tolerance cases for 75/78. An unavailable AMS runner does not prove behavior, and it must not create a reverse dependency on its earlier compiler input.
  - [ ] **F-074.E — Optimization review and output quality.** Review state/bridge duplication without altering analog loop dynamics or event scheduling. Apply the proportionate review rule above; document no new optimization required when justified.
  - [ ] **F-074.F — Scale, determinism and compatibility.** Exercise feedback hierarchy and repeatable event/parameter cases within documented capability limits.
  - [ ] **F-074.G — Evidence, documentation and acceptance.** Retain the applicable evidence, capability limits and reproduction/demonstration record for 74; complete review, verified integration and any separate closure under the centralized Foundation acceptance rules.

- [ ] **Increment 75 — Verilog-AMS simulator adapter interface**
  - [ ] **F-075.A — Architecture, scope and extensibility.** Keep compile/elaborate/run and licensing/capability negotiation behind the adapter boundary.
  - [ ] **F-075.B — Implementation and integration.** Define pluggable compile/elaborate/run adapters, discovery, licensing-safe CI, log normalization, and optional local regression.
  - [ ] **F-075.C — Correctness, rejection and predecessor regression.** Test discovery failures, missing license/capability, compile versus run failures, timeout and malformed results.
  - [ ] **F-075.D — Independent validation and applicability**
    - [ ] **F-075.D.1** Adapter compile/elaboration: Exercise the declared adapter on actual generated supported models and retain licensing/capability decisions, commands and normalized compile/elaboration failures.
    - [ ] **F-075.D.2** Adapter execution: For every executable simulator claim, run the supported generated cases with declared numerical/event references and tolerances; unavailable required execution stays blocked. A mock protocol response is not simulator qualification.
  - [ ] **F-075.E — Optimization review and output quality.** Review process/log overhead without hiding commands or treating unavailable simulators as passed. Apply the proportionate review rule above; document no new optimization required when justified.
  - [ ] **F-075.F — Scale, determinism and compatibility.** Exercise repeated jobs and supported simulator profiles with deterministic normalized outcomes.
  - [ ] **F-075.G — Evidence, documentation and acceptance.** Retain the applicable evidence, capability limits and reproduction/demonstration record for 75; complete review, verified integration and any separate closure under the centralized Foundation acceptance rules.

- [ ] **Increment 76 — Portable and full AMS profiles**
  - [ ] **F-076.A — Architecture, scope and extensibility.** Separate portable, standard-oriented full and vendor-extension feature sets using 72/75 capabilities.
  - [ ] **F-076.B — Implementation and integration.** Publish portable/full standard-oriented and simulator-extension profiles with machine-readable feature coverage and no accidental leakage.
  - [ ] **F-076.C — Correctness, rejection and predecessor regression.** Test unsupported constructs and extension leakage across profiles with source-located rejection.
  - [ ] **F-076.D — Independent validation and applicability**
    - [ ] **F-076.D.1** Profile syntax and elaboration: Independently compile/elaborate actual generated artifacts through each selected qualified tool profile; retain rejected capabilities separately.
    - [ ] **F-076.D.2** Behavioral and numerical execution: Execute applicable analog/mixed-signal cases through 49/75 using declared waveform/event/analysis references and tolerances; do not infer universal Verilog-AMS support.
    - [ ] **F-076.D.3** Digital synthesis/proof boundary: Apply 66-67 synthesis/equivalence/formal checks only to synthesizable digital portions and introduced digital transformations. Record non-applicability for continuous-time equations without calling it a passed synthesis test.
  - [ ] **F-076.E — Optimization review and output quality.** Review duplicated capability decisions and readable feature reports without broadening emitted semantics. Apply the proportionate review rule above; document no new optimization required when justified.
  - [ ] **F-076.F — Scale, determinism and compatibility.** Exercise feature/profile/version combinations and stable machine-readable compatibility reports.
  - [ ] **F-076.G — Evidence, documentation and acceptance.** Retain the applicable evidence, capability limits and reproduction/demonstration record for 76; complete review, verified integration and any separate closure under the centralized Foundation acceptance rules.

- [ ] **Increment 77 — UVM-MS interoperability hooks**
  - [ ] **F-077.A — Architecture, scope and extensibility.** Bind interoperability metadata to logical interface and monitor identities; do not implement dependent-track UVM-MS generators.
  - [ ] **F-077.B — Implementation and integration**
    - [ ] **F-077.B.1** Generate role-aware interface/agent metadata, monitor views, flattened/native wrapper maps, resolved-inout access, conservative-terminal access, and logical Interface ABI correlation without making UVM-MS define Nodal semantics.
    - [ ] **F-077.B.2** Generate metadata, wrappers, or interfaces needed for UVM-MS integration without embedding a second verification methodology.
  - [ ] **F-077.C — Correctness, rejection and predecessor regression.** Test role/access and wrapper-map mismatches, missing identities and unsupported metadata capabilities.
  - [ ] **F-077.D — Independent validation and applicability**
    - [ ] **F-077.D.1** Independent artifact consumers: Validate actual emitted manifests/wrappers/software or interchange artifacts using applicable pinned parsers/consumers and independently specified identity/ABI expectations; record each format capability and loss limit.
    - [ ] **F-077.D.2** Generated HDL applicability: Where a generated HDL wrapper affects behavior, retain separate applicable syntax, simulation and synthesizable-digital structure/equivalence checks through the existing tool owners. Metadata-only outputs and unimplemented dependent generators receive no HDL-test credit.
  - [ ] **F-077.E — Optimization review and output quality.** Review redundant wrappers and metadata without adding simulation state or a second verification methodology. Apply the proportionate review rule above; document no new optimization required when justified.
  - [ ] **F-077.F — Scale, determinism and compatibility.** Exercise deeply nested interface metadata and stable cross-backend identity correlation.
  - [ ] **F-077.G — Evidence, documentation and acceptance.** Retain the applicable evidence, capability limits and reproduction/demonstration record for 77; complete review, verified integration and any separate closure under the centralized Foundation acceptance rules.

- [ ] **Increment 78 — Verilog-AMS conformance suite**
  - [ ] **F-078.A — Architecture, scope and extensibility.** Build the conformance matrix from actual 72/75/76 language and simulator profiles.
  - [ ] **F-078.B — Implementation and integration.** Build standards-oriented positive/negative tests, practical round trips, feature coverage, and simulator-result classification.
  - [ ] **F-078.C — Correctness, rejection and predecessor regression.** Pair standards-positive cases with malformed/unsupported negatives, round trips and classified simulator failures.
  - [ ] **F-078.D — Independent validation and applicability**
    - [ ] **F-078.D.1** Profile syntax and elaboration: Independently compile/elaborate actual generated artifacts through each selected qualified tool profile; retain rejected capabilities separately.
    - [ ] **F-078.D.2** Behavioral and numerical execution: Execute applicable analog/mixed-signal cases through 49/75 using declared waveform/event/analysis references and tolerances; do not infer universal Verilog-AMS support.
    - [ ] **F-078.D.3** Digital synthesis/proof boundary: Apply 66-67 synthesis/equivalence/formal checks only to synthesizable digital portions and introduced digital transformations. Record non-applicability for continuous-time equations without calling it a passed synthesis test.
  - [ ] **F-078.E — Optimization review and output quality.** Review fixture coverage/redundancy without converting tool limitations into language acceptance. Apply the proportionate review rule above; document no new optimization required when justified.
  - [ ] **F-078.F — Scale, determinism and compatibility.** Exercise feature intersections, hierarchy and repeatable tool/profile matrices with retained limits.
  - [ ] **F-078.G — Evidence, documentation and acceptance.** Retain the applicable evidence, capability limits and reproduction/demonstration record for 78; complete review, verified integration and any separate closure under the centralized Foundation acceptance rules.

## Phase 5 — Plugins, target-HDL optimization, extensibility, scale, documentation, and release

- [ ] **Increment 79 — Plugin architecture gate and SPI v0.1 contracts**
  - [ ] **F-079.A — Architecture, scope and extensibility**
    - [ ] **F-079.A.1** Use [ADR 0012](../architecture/0012-versioned-capability-plugin-architecture.md), [`plugin-spi-v0.1-plan.md`](plugin-spi-v0.1-plan.md), and [`plugin-spi-v0.1-surface.json`](plugin-spi-v0.1-surface.json) as the mandatory architecture and candidate.
    - [ ] **F-079.A.2** Keep loaders and plugin execution inert. Mark this increment `[x]` only after every SPI freeze criterion in the detailed plan passes CI.
    - [ ] **F-079.A.3** Freeze versioned typed plugin identities, capability cardinality, trust and execution boundaries through the existing SPI plan.
  - [ ] **F-079.B — Implementation and integration.** Compile plugin descriptors/manifests, stable plugin/capability IDs, versions, cardinalities, qualifiers, `DesignPlugin`, local `DesignHost`, typed services/contributions, phase contexts, backend IDs, native/process descriptors, and library-versus-plugin separation.
  - [ ] **F-079.C — Correctness, rejection and predecessor regression.** Compile valid external candidates and reject conflicting/ambiguous capability use or private-core dependencies.
  - [ ] **F-079.D — Independent validation and applicability.** Gate validation and applicability: Retain actual compile-positive/negative public or schema candidates and independent design review where required by the existing gate. Record that unimplemented backend/simulator/synthesis behavior receives no execution credit; documentation-only boundaries acquire no artificial HDL tests.
  - [ ] **F-079.E — Optimization review and output quality.** Review candidate complexity and future resolver cost; installation and candidate compilation must remain behaviorally inert. Apply the proportionate review rule above; document no new optimization required when justified.
  - [ ] **F-079.F — Scale, determinism and compatibility.** Exercise nested host/capability candidates and manifest-version compatibility without loading plugins.
  - [ ] **F-079.G — Evidence, documentation and acceptance**
    - [ ] **F-079.G.1** Publish `NodalPluginSpi-DG-v0.1.md`, manifest/lockfile schemas, compatibility/trust policy, machine-readable frozen SPI, and positive/negative fixtures with stable diagnostics.
    - [ ] **F-079.G.2** Retain the applicable evidence, capability limits and reproduction/demonstration record for 79; complete review, verified integration and any separate closure under the centralized Foundation acceptance rules.

- [ ] **Increment 80 — Manifest resolver, capability graph, lockfile, and plugin CLI**
  - [ ] **F-080.A — Architecture, scope and extensibility.** Resolve immutable manifest-only plans before executable code loads; reuse 79 stable identities and trust rules.
  - [ ] **F-080.B — Implementation and integration**
    - [ ] **F-080.B.1** Implement manifest-only discovery without code execution; validate SPI/core/API/IR/bridge/toolchain ranges, provided/required capability versions, cardinality, qualifiers, conflicts, replacements, platform artifacts, trust, and option schemas.
    - [ ] **F-080.B.2** Resolve a canonical immutable plugin plan, reject ambiguity and cycles, generate `nodal.plugins.lock`, and include graph/artifact/options hashes in build manifests and cache keys.
    - [ ] **F-080.B.3** Add `nodal plugins list/resolve/check/graph/explain/lock/inspect` with human and machine-readable evidence plus offline locked mode.
  - [ ] **F-080.C — Correctness, rejection and predecessor regression.** Reject cycles, ambiguous providers, incompatible versions, bad hashes/options and locked-offline misses.
  - [ ] **F-080.D — Independent validation and applicability**
    - [ ] **F-080.D.1** Independent consumer and failure fixtures: Exercise actual produced plans/protocols/reports/artifacts with independent expected results and benign malformed/version/crash/timeout controls, as applicable to this tool.
    - [ ] **F-080.D.2** Execution-claim applicability: Record which generated-HDL or external-tool results this tool actually affects and reference or execute the existing required qualification lanes; a mock, cache hit or successful protocol parse is not itself HDL simulation or proof.
  - [ ] **F-080.E — Optimization review and output quality.** Review repeated resolution and graph growth without discovery-order or first-provider shortcuts. Apply the proportionate review rule above; document no new optimization required when justified.
  - [ ] **F-080.F — Scale, determinism and compatibility.** Exercise large capability graphs and declaration-order permutations with identical locks and explanations.
  - [ ] **F-080.G — Evidence, documentation and acceptance.** Retain the applicable evidence, capability limits and reproduction/demonstration record for 80; complete review, verified integration and any separate closure under the centralized Foundation acceptance rules.

- [ ] **Increment 81 — Local design composition host and typed contribution system**
  - [ ] **F-081.A — Architecture, scope and extensibility**
    - [ ] **F-081.A.1** Prohibit process-global registries, concrete-plugin lookup, direct mutable cross-plugin access, public retain/release ordering, implicit first-provider selection, and undeclared contributions.
    - [ ] **F-081.A.2** Use local host ownership, typed append-only contributions and closed phases from 79-80.
  - [ ] **F-081.B — Implementation and integration.** Implement local `DesignHost` scopes, phase-specific contexts, stable capability keys, exactly-one/optional/many/qualified providers, contribution sets/sequences, close phases, nested explicit import/export, stable plugin instance qualifiers, names, and provenance.
  - [ ] **F-081.C — Correctness, rejection and predecessor regression**
    - [ ] **F-081.C.1** Add configurable digital/mixed-signal subsystem fixtures, multiple instances, nested hosts, conflict/cycle diagnostics, and declaration-order permutation tests producing identical IR/HDL/reports.
    - [ ] **F-081.C.2** Test scope leakage, premature/late contributions, conflict/cycle errors and undeclared mutable cross-plugin access.
  - [ ] **F-081.D — Independent validation and applicability**
    - [ ] **F-081.D.1** Generated output matrix: Retain actual artifacts per affected backend and execute the applicable independent syntax/elaboration, behavioral/numerical and synthesizable-digital structure lanes from 48-49/66-67/75, each recorded separately.
    - [ ] **F-081.D.2** Transformation validation: Execute applicable equivalence/formal or numerical/invariant validation for every introduced transformation, with preserved parameter/state/domain/source metadata and explicit capability limits.
  - [ ] **F-081.E — Optimization review and output quality.** Review contribution aggregation and repeated elaboration without merging distinct host identities. Apply the proportionate review rule above; document no new optimization required when justified.
  - [ ] **F-081.F — Scale, determinism and compatibility.** Exercise nested hosts and repeated plugin instances; declaration order must not alter IR, HDL or reports.
  - [ ] **F-081.G — Evidence, documentation and acceptance.** Retain the applicable evidence, capability limits and reproduction/demonstration record for 81; complete review, verified integration and any separate closure under the centralized Foundation acceptance rules.

- [ ] **Increment 82 — Native compiler plugin loader and versioned extension points**
  - [ ] **F-082.A — Architecture, scope and extensibility.** Wrap native/process extension seams with exact ABI, analysis-preservation and mandatory-verification contracts.
  - [ ] **F-082.B — Implementation and integration**
    - [ ] **F-082.B.1** Wrap MLIR pass and dialect plugin APIs with Nodal manifest validation, exact native ABI/toolchain-build matching, plugin-owned namespaces, analysis preservation/invalidation, named versioned pipeline extension points, normalized pass evidence, and mandatory core re-verification.
    - [ ] **F-082.B.2** Add out-of-process transform protocol for isolated/longer-lived extensions, with versioned IR exchange, diagnostics, output hashes, cancellation, timeout, crash, and malformed-response handling.
    - [ ] **F-082.B.3** Provide out-of-tree pass, analysis, dialect, verifier, and transform fixtures using no private core APIs.
  - [ ] **F-082.C — Correctness, rejection and predecessor regression.** Test version mismatch, namespace violations, malformed IR, timeout/crash and transactional recovery using benign fixtures.
  - [ ] **F-082.D — Independent validation and applicability**
    - [ ] **F-082.D.1** Independent consumer and failure fixtures: Exercise actual produced plans/protocols/reports/artifacts with independent expected results and benign malformed/version/crash/timeout controls, as applicable to this tool.
    - [ ] **F-082.D.2** Execution-claim applicability: Record which generated-HDL or external-tool results this tool actually affects and reference or execute the existing required qualification lanes; a mock, cache hit or successful protocol parse is not itself HDL simulation or proof.
  - [ ] **F-082.E — Optimization review and output quality.** Review load/serialization overhead without bypassing mandatory verification or accepting partial state. Apply the proportionate review rule above; document no new optimization required when justified.
  - [ ] **F-082.F — Scale, determinism and compatibility.** Exercise multiple compatible plugins and repeated process calls with stable ordering and diagnostics.
  - [ ] **F-082.G — Evidence, documentation and acceptance.** Retain the applicable evidence, capability limits and reproduction/demonstration record for 82; complete review, verified integration and any separate closure under the centralized Foundation acceptance rules.

- [ ] **Increment 83 — Target-HDL optimization pass gate and SPI v0.1 contracts**
  - [ ] **F-083.A — Architecture, scope and extensibility**
    - [ ] **F-083.A.1** Use [ADR 0013](../architecture/0013-structured-hdl-optimization-pass-architecture.md), [`target-hdl-optimization-pass-v0.1-plan.md`](target-hdl-optimization-pass-v0.1-plan.md), and [`target-hdl-optimization-pass-v0.1-surface.json`](target-hdl-optimization-pass-v0.1-surface.json) as the mandatory architecture and candidate.
    - [ ] **F-083.A.2** Freeze structured target-pass effects, profiles, preservation obligations and trust before enabling execution.
  - [ ] **F-083.B — Implementation and integration.** Compile descriptors/manifests for target-neutral, digital, Verilog-A, Verilog-AMS, render-only, and reparse passes; stable pass IDs; target/profile/IR versions; extension points; ordering/conflicts; options; preservation/invalidation; proof classes; parameterization, shaped-value/layout/storage, expression-materialization/naming/source-map, driver/latch/cycle/check-inventory effects; profiles; native/process facets; and evidence artifacts.
  - [ ] **F-083.C — Correctness, rejection and predecessor regression**
    - [ ] **F-083.C.1** Prove installation changes no output, `Backend.Auto` remains independent, raw semantic text cannot bypass reparse/reverification, and frontend/backend/pass execution remains inert until later increments.
    - [ ] **F-083.C.2** Compile positive/negative descriptors; reject raw-text bypasses, invalid ordering and implicit installation effects.
  - [ ] **F-083.D — Independent validation and applicability.** Gate validation and applicability: Retain actual compile-positive/negative public or schema candidates and independent design review where required by the existing gate. Record that unimplemented backend/simulator/synthesis behavior receives no execution credit; documentation-only boundaries acquire no artificial HDL tests.
  - [ ] **F-083.E — Optimization review and output quality.** Review descriptor complexity and future pass data requirements, not unapproved optimization implementations. Apply the proportionate review rule above; document no new optimization required when justified.
  - [ ] **F-083.F — Scale, determinism and compatibility.** Exercise descriptor/profile combinations and deterministic schema/order candidates with execution inert.
  - [ ] **F-083.G — Evidence, documentation and acceptance**
    - [ ] **F-083.G.1** Publish `NodalTargetHdlOptimizationPass-DG-v0.1.md`, a machine-readable frozen pass SPI, pass/profile lockfile schemas, compatibility/trust policy, and positive/negative fixtures with stable diagnostics.
    - [ ] **F-083.G.2** Retain the applicable evidence, capability limits and reproduction/demonstration record for 83; complete review, verified integration and any separate closure under the centralized Foundation acceptance rules.

- [ ] **Increment 84 — Structured target IR and deterministic optimization pass manager**
  - [ ] **F-084.A — Architecture, scope and extensibility.** Reuse 82 loading with typed digital/analog/AMS target IR and explicit analysis invalidation.
  - [ ] **F-084.B — Implementation and integration**
    - [ ] **F-084.B.1** Implement verified digital target IR using CIRCT where semantically appropriate plus Nodal-owned shaped-value/layout/storage, expression-origin/materialization/naming, and mandatory-check contracts, and typed Verilog-A/Verilog-AMS target IR preserving disciplines/nodes/branches/contributions/dimensions/continuous-time operators/events/noise/analyses/digital state/conversions/connect rules/capabilities/hierarchy/source maps.
    - [ ] **F-084.B.2** Implement deterministic locked pass resolution/execution, native/process loading through Increment 82, analysis invalidation/recomputation, mandatory target verification, transactional crash-safe acceptance, render-only and verified reparse boundaries, source-map updates, diagnostics, pass reports, cache/provenance integration, and pass/pipeline inspection commands.
  - [ ] **F-084.C — Correctness, rejection and predecessor regression**
    - [ ] **F-084.C.1** Add out-of-tree target-pass fixtures and declaration-order/load-order permutation tests producing identical verified target IR, HDL, diagnostics, reports, and pipeline hashes.
    - [ ] **F-084.C.2** Test pass conflicts, corrupted outputs, dropped metadata and failure recovery; mutate mandatory re-verification guards.
  - [ ] **F-084.D — Independent validation and applicability**
    - [ ] **F-084.D.1** Generated output matrix: Retain actual artifacts per affected backend and execute the applicable independent syntax/elaboration, behavioral/numerical and synthesizable-digital structure lanes from 48-49/66-67/75, each recorded separately.
    - [ ] **F-084.D.2** Transformation validation: Execute applicable equivalence/formal or numerical/invariant validation for every introduced transformation, with preserved parameter/state/domain/source metadata and explicit capability limits.
  - [ ] **F-084.E — Optimization review and output quality.** Review traversal/materialization growth while retaining transactional boundaries and exact pass effects. Apply the proportionate review rule above; document no new optimization required when justified.
  - [ ] **F-084.F — Scale, determinism and compatibility.** Exercise long pass pipelines, load-order permutations and large source maps with repeatable hashes.
  - [ ] **F-084.G — Evidence, documentation and acceptance.** Retain the applicable evidence, capability limits and reproduction/demonstration record for 84; complete review, verified integration and any separate closure under the centralized Foundation acceptance rules.

- [ ] **Increment 85 — Digital Verilog optimization plugins and equivalence/formal proof matrix**
  - [ ] **F-085.A — Architecture, scope and extensibility**
    - [ ] **F-085.A.1** Preserve widths/signedness/overflow, numeric-conversion versus reinterpretation, signed literal/shift/comparison semantics, ranked shapes/dimensions/index/flatten/layout and structural-storage class, expression tree/materialization/naming/observability and source spans, elaboration/generate/hardware-loop category, iteration/reduction order, index bounds, deterministic unroll/procedural choice, symbolic parameters/generate, one-module-per-structure, hierarchy, clocks/resets/CDC/RDC, protocol ordering, latency/throughput/capacity, user-owned state, memories/effects, mandatory check results, source maps, and portable-Verilog capabilities unless an explicit separately named transformation contract permits a verified change.
    - [ ] **F-085.A.2** Apply only explicit locked digital pass contracts from 83-84, preserving width, state, protocol and parameter semantics.
  - [ ] **F-085.B — Implementation and integration.** Implement built-in/reference plugins for parameter-aware constant/dead-logic cleanup, mux/logic/process/memory/generate normalization, safe common-subexpression elimination, hierarchy/portability cleanup, pipeline-owned bounded retiming, explicit synthesis attributes/target mapping, and locked external Yosys pass pipelines.
  - [ ] **F-085.C — Correctness, rejection and predecessor regression.** Add incorrect-rewrite mutations and predecessor cases for signed loops, shapes, FSMs, memories, CDC and pipelines.
  - [ ] **F-085.D — Independent validation and applicability**
    - [ ] **F-085.D.1** Require Verilator/Icarus differential regression, Yosys combinational/sequential and latency/protocol-aware equivalence, parameter-envelope matrices, selected SBY properties, deterministic before/after reports, and exact golden/profile fixtures.
    - [ ] **F-085.D.2** Syntax and elaboration: Use the pinned 66 Verilator/Icarus lanes on actual generated HDL in the selected language/profile; record source/output identities, commands and capability limits.
    - [ ] **F-085.D.3** Behavioral simulation: Simulate the selected boundary and predecessor-interaction cases against declared reference behavior, retaining seeds, reset/stimulus sequences and waveforms.
    - [ ] **F-085.D.4** Synthesis and structure: Use 67 Yosys checks for the synthesizable digital artifacts, retaining hierarchy, cell/memory/driver and parameter results.
    - [ ] **F-085.D.5** Equivalence and formal applicability: Record distinct equivalence/property applicability and execute every required transformation, safety or protocol check through 67; retain assumptions, bounds and counterexamples rather than using simulation as proof.
  - [ ] **F-085.E — Optimization review and output quality.** Review introduced optimizations with before/after structure and resource evidence, never anonymous cleanup by raw text. Apply the proportionate review rule above; document no new optimization required when justified.
  - [ ] **F-085.F — Scale, determinism and compatibility.** Exercise parameter envelopes and large shared graphs; record time/memory and deterministic pass results.
  - [ ] **F-085.G — Evidence, documentation and acceptance.** Retain the applicable evidence, capability limits and reproduction/demonstration record for 85; complete review, verified integration and any separate closure under the centralized Foundation acceptance rules.

- [ ] **Increment 86 — Verilog-A/Verilog-AMS optimization plugins and semantic validation**
  - [ ] **F-086.A — Architecture, scope and extensibility**
    - [ ] **F-086.A.1** Prohibit unapproved contribution deletion/reordering, equation reassociation across discontinuities/singularities, movement of continuous-time/delay/Laplace/Z operators, changes to event timing/tolerance/initialization/noise/analysis identity, silent approximation, mixed-signal scheduling changes, and raw semantic text substitution.
    - [ ] **F-086.A.2** Apply only declared analog/AMS transforms with domain, residual, state, event and topology preconditions.
  - [ ] **F-086.B — Implementation and integration.** Implement built-in/reference plugins for dimension-safe constant/parameter folding, dead declaration removal, branch/access/contribution canonicalization, approved algebraic identities with domain/singularity checks, event-condition simplification preserving direction/tolerance, connect-rule/discipline portability rewriting, and deterministic render normalization.
  - [ ] **F-086.C — Correctness, rejection and predecessor regression.** Use singularity/discontinuity/state/noise/contribution counterexamples and failed-validation mutations.
  - [ ] **F-086.D — Independent validation and applicability**
    - [ ] **F-086.D.1** Require typed target-IR invariants and normalized equivalence for approved rewrites plus relevant DC/AC/transient/noise/event differential suites, cross-tool portability evidence, source-map/provenance checks, deterministic golden fixtures, and explicit rejection where no sound validation method exists.
    - [ ] **F-086.D.2** Independent model compilation: Compile actual generated supported models using 48 and qualified 75 profiles where needed; retain exact capability and artifact identities.
    - [ ] **F-086.D.3** Numerical reference validation: Execute the relevant DC/AC/transient/noise/event cases with declared references, tolerances, parameter/environment conditions and state/topology assumptions. Unsupported analyses are not successful runs.
    - [ ] **F-086.D.4** Transformation correspondence: Retain independent mathematical/invariant checks and applicable before/after numerical comparisons for introduced transformations; waveform agreement is not a formal proof of unsupported structural changes.
  - [ ] **F-086.E — Optimization review and output quality.** Review each introduced rewrite independently; retain numerical assumptions and reject transformations without a sound validation method. Apply the proportionate review rule above; document no new optimization required when justified.
  - [ ] **F-086.F — Scale, determinism and compatibility.** Exercise large equations and repeated stateful models over parameter/analysis envelopes with stable provenance.
  - [ ] **F-086.G — Evidence, documentation and acceptance.** Retain the applicable evidence, capability limits and reproduction/demonstration record for 86; complete review, verified integration and any separate closure under the centralized Foundation acceptance rules.

- [ ] **Increment 87 — Backend and external tool-adapter plugins**
  - [ ] **F-087.A — Architecture, scope and extensibility.** Use one versioned process/evidence protocol while keeping backend selection and language semantics independent.
  - [ ] **F-087.B — Implementation and integration**
    - [ ] **F-087.B.1** Implement explicit third-party backend registration, capability profiles, options/artifact/source-map contracts, deterministic selection, and rejection before translation. Keep plugin backends out of `Backend.Auto` by default.
    - [ ] **F-087.B.2** Implement one versioned out-of-process adapter/evidence protocol for simulators, synthesis, formal, FPGA place/route, bitstreams, programmers, boards, HIL, waveforms, and reporters.
    - [ ] **F-087.B.3** Migrate built-in external adapters to the common protocol while preserving licensing-safe CI and the rule that external tools never define language semantics.
  - [ ] **F-087.C — Correctness, rejection and predecessor regression.** Test unsupported capabilities, altered options/hashes, partial output, cancellation and adapter crash recovery.
  - [ ] **F-087.D — Independent validation and applicability**
    - [ ] **F-087.D.1** Independent consumer and failure fixtures: Exercise actual produced plans/protocols/reports/artifacts with independent expected results and benign malformed/version/crash/timeout controls, as applicable to this tool.
    - [ ] **F-087.D.2** Execution-claim applicability: Record which generated-HDL or external-tool results this tool actually affects and reference or execute the existing required qualification lanes; a mock, cache hit or successful protocol parse is not itself HDL simulation or proof.
  - [ ] **F-087.E — Optimization review and output quality.** Review protocol/serialization overhead and redundant tool launches without hiding semantic or licensing limits. Apply the proportionate review rule above; document no new optimization required when justified.
  - [ ] **F-087.F — Scale, determinism and compatibility.** Exercise supported adapter kinds and protocol versions with deterministic artifacts and cache identities.
  - [ ] **F-087.G — Evidence, documentation and acceptance.** Retain the applicable evidence, capability limits and reproduction/demonstration record for 87; complete review, verified integration and any separate closure under the centralized Foundation acceptance rules.

- [ ] **Increment 88 — Plugin packaging, trust, provenance, caching, and conformance**
  - [ ] **F-088.A — Architecture, scope and extensibility.** Keep passive libraries, trusted in-process plugins and process-isolated artifacts separately identified.
  - [ ] **F-088.B — Implementation and integration**
    - [ ] **F-088.B.1** Define coordinated Scala/Maven, native-platform, process-executable, schema/support-file, checksum/signature, license, SBOM, and provenance packaging with explicit trusted-Scala/trusted-native/process-isolated policies.
    - [ ] **F-088.B.2** Integrate plugin graphs, artifacts, options, pass order, external commands, and outputs into incremental caching, release provenance, reproducibility, and offline resolution.
  - [ ] **F-088.C — Correctness, rejection and predecessor regression**
    - [ ] **F-088.C.1** Publish a plugin conformance kit plus out-of-tree design, frontend-lint, MLIR pass/dialect, backend, and tool-adapter reference plugins. Prove compatibility failures, crash isolation, load-order determinism, and no hidden core dependency.
    - [ ] **F-088.C.2** Test incompatible/missing bundles, malformed provenance, crash isolation and forbidden hidden core dependencies.
  - [ ] **F-088.D — Independent validation and applicability**
    - [ ] **F-088.D.1** Package consumer evidence: Use actual produced bundles in clean supported consumer/install contexts and independently verify hashes, manifests, licenses and compatibility; record platform-specific results.
    - [ ] **F-088.D.2** Execution-claim boundary: Retain underlying compiler/plugin qualification references rather than crediting packaging with new HDL simulation, synthesis or formal evidence.
  - [ ] **F-088.E — Optimization review and output quality.** Review package duplication and cached metadata without weakening checksum or compatibility checks. Apply the proportionate review rule above; document no new optimization required when justified.
  - [ ] **F-088.F — Scale, determinism and compatibility.** Exercise platform/plugin combinations and offline/load-order permutations with repeatable bundles and locks.
  - [ ] **F-088.G — Evidence, documentation and acceptance.** Retain the applicable evidence, capability limits and reproduction/demonstration record for 88; complete review, verified integration and any separate closure under the centralized Foundation acceptance rules.

- [ ] **Increment 89 — Versioned IR and bridge compatibility**
  - [ ] **F-089.A — Architecture, scope and extensibility.** Version dialect, bridge and extension-point semantics explicitly rather than inferring upgrades from syntax.
  - [ ] **F-089.B — Implementation and integration.** Add Nodal dialect/bridge/plugin-plan version metadata, supported upgrades, old-version fixtures, plugin extension-point compatibility, and explicit unknown-future-version rejection.
  - [ ] **F-089.C — Correctness, rejection and predecessor regression.** Test supported old-version upgrades and unknown-future rejection, including source maps and plugin plans.
  - [ ] **F-089.D — Independent validation and applicability**
    - [ ] **F-089.D.1** Representation evidence: Validate actual serialized/native IR and manifests against independently specified semantic expectations and malformed-input controls; compiler parse/print alone is not generated-HDL behavioral proof.
    - [ ] **F-089.D.2** Consumer qualification boundary: Retain source-correlated witnesses for the already assigned lowering/tool consumers and identify their later execution obligations without a reverse dependency. Do not claim unimplemented target behavior here.
  - [ ] **F-089.E — Optimization review and output quality.** Review conversion passes for redundant copies while preserving lossless semantic/version metadata. Apply the proportionate review rule above; document no new optimization required when justified.
  - [ ] **F-089.F — Scale, determinism and compatibility.** Exercise large serialized models and supported version pairs with deterministic round trips.
  - [ ] **F-089.G — Evidence, documentation and acceptance.** Retain the applicable evidence, capability limits and reproduction/demonstration record for 89; complete review, verified integration and any separate closure under the centralized Foundation acceptance rules.

- [ ] **Increment 90 — Incremental build and compiler caching**
  - Original scope retained: Cache construction, normalized MLIR, plugin resolution/transforms, native compilation, reports, and backend/tool outputs by content/toolchain/profile/plugin-plan hashes with proven invalidation.
  - [ ] **F-090.A — Architecture, scope and extensibility.** Key each cache stage by semantic content plus toolchain, profile and plugin-plan identity.
  - [ ] **F-090.B — Implementation and integration**
    - [ ] **F-090.B.1** Implement content-addressed cache identities and storage at the existing construction, IR, plugin, native and backend/tool boundaries.
    - [ ] **F-090.B.2** Integrate invalidation, corrupt-entry handling and reproducible artifact retrieval without promoting failed or stale results.
  - [ ] **F-090.C — Correctness, rejection and predecessor regression.** Mutate each key input and test stale-hit rejection, missing/corrupt artifacts and failure recovery.
  - [ ] **F-090.D — Independent validation and applicability**
    - [ ] **F-090.D.1** Independent consumer and failure fixtures: Exercise actual produced plans/protocols/reports/artifacts with independent expected results and benign malformed/version/crash/timeout controls, as applicable to this tool.
    - [ ] **F-090.D.2** Execution-claim applicability: Record which generated-HDL or external-tool results this tool actually affects and reference or execute the existing required qualification lanes; a mock, cache hit or successful protocol parse is not itself HDL simulation or proof.
  - [ ] **F-090.E — Optimization review and output quality.** Measure repeated work avoided without conflating presentation-only and semantic changes or caching failures as success. Apply the proportionate review rule above; document no new optimization required when justified.
  - [ ] **F-090.F — Scale, determinism and compatibility.** Exercise hierarchy/graph sizes and cold/warm caches with repeatable output and recorded memory/runtime.
  - [ ] **F-090.G — Evidence, documentation and acceptance.** Retain the applicable evidence, capability limits and reproduction/demonstration record for 90; complete review, verified integration and any separate closure under the centralized Foundation acceptance rules.

- [ ] **Increment 91 — Future library architecture and publication contract**
  - [ ] **F-091.A — Architecture, scope and extensibility.** Define passive external library packaging using public APIs only; executable companions remain separately enabled.
  - [ ] **F-091.B — Implementation and integration.** Define passive library module conventions, Maven coordinates, resources, independent versions, core ranges, conflicts, licenses, offline use, and external public-API-only fixtures. Keep executable companion plugins separately packaged and explicitly enabled.
  - [ ] **F-091.C — Correctness, rejection and predecessor regression.** Check conflicting versions, unsupported core ranges, resource/import isolation and private-core dependency rejection.
  - [ ] **F-091.D — Independent validation and applicability.** Gate validation and applicability: Retain actual compile-positive/negative public or schema candidates and independent design review where required by the existing gate. Record that unimplemented backend/simulator/synthesis behavior receives no execution credit; documentation-only boundaries acquire no artificial HDL tests.
  - [ ] **F-091.E — Optimization review and output quality.** Review publication conventions for needless duplication without importing library implementation into core. Apply the proportionate review rule above; document no new optimization required when justified.
  - [ ] **F-091.F — Scale, determinism and compatibility.** Exercise offline resolution and independently versioned public-consumer fixtures.
  - [ ] **F-091.G — Evidence, documentation and acceptance.** Retain the applicable evidence, capability limits and reproduction/demonstration record for 91; complete review, verified integration and any separate closure under the centralized Foundation acceptance rules.

- [ ] **Increment 92 — Complete language, plugin SPI, and API documentation**
  - [ ] **F-092.A — Architecture, scope and extensibility.** Keep documentation and progress contracts aligned with CONTRIBUTING and existing Markdown tooling; document supported behavior rather than planned behavior as implemented.
  - [ ] **F-092.B — Implementation and integration**
    - [ ] **F-092.B.1** Document `Struct` versus `Interface`, generic roles, `master`/`slave`/`monitor`, full `Valid`/`Stream`, digital inout/resolved-net/tri-state/open-drain/pad patterns, conservative terminals, signal-flow analog values, mixed-signal bridges, flattened/native backend layouts, and Interface ABI compatibility.
    - [ ] **F-092.B.2** Cover value staging; Scala elaboration, symbolic generate, and bounded hardware loops; signed/unsigned/signless declarations/literals/conversions/backend mapping; parameterized multidimensional `Vec`, shape/index/flatten/reshape, layout and `Vec`/`Mem`; expression inlining/materialization/naming/source maps; mandatory check profiles/inventory/waivers/transactional emission; numeric/width/overflow; aggregates/connections/protocols; quantities/effects; domains/CDC/RDC/reset; automatic pipelines; portable Verilog/backend inference; open-source verification; mixed-signal boundaries; plugin manifests/capabilities/lifecycle/loaders/adapters/trust/lockfiles; diagnostics; libraries; and migration.
    - [ ] **F-092.B.3 — Foundation roadmap consistency validator (future documentation tooling only)**
      - [ ] **F-092.B.3.1** Plan a read-only check beside the existing Markdown/contribution tooling for stable unique Foundation increment/child IDs, resolved dependency/checkpoint references and dependency-cycle detection; do not infer edges from every mention of a consumer.
      - [ ] **F-092.B.3.2** Check parent/required-descendant completion consistency, supporting evidence references for completed deliverables, and explicit applicability, optional work and separately owned deferred qualification. Recognize historical H breakdowns without retroactively inventing acceptance requirements.
      - [ ] **F-092.B.3.3** Add focused positive/negative document fixtures for duplicate/missing IDs, dangling/cyclic prerequisites, incomplete required children, missing evidence and unsupported applicability claims; keep Markdown authoritative and any machine-readable view generated/read-only.
  - [ ] **F-092.C — Correctness, rejection and predecessor regression.** Check source/output example provenance, stale references, rejected-feature documentation and contradictory completion claims.
  - [ ] **F-092.D — Independent validation and applicability**
    - [ ] **F-092.D.1** Documentation evidence: Check real links, source/example/artifact identities and capability statements against existing acceptance records. Distinguish generated demonstrations from candidates and examples from tests.
    - [ ] **F-092.D.2** Applicability record: Record documentation-only deliverables as not requiring new HDL simulation/synthesis/formal execution; examples that claim execution must cite actual existing evidence.
  - [ ] **F-092.E — Optimization review and output quality.** Review duplicated explanations and generated views; preserve Markdown as the only editable progress source. Apply the proportionate review rule above; document no new optimization required when justified.
  - [ ] **F-092.F — Scale, determinism and compatibility.** Exercise large nested checklists, documentation anchors and supported API-version references without invented performance targets.
  - [ ] **F-092.G — Evidence, documentation and acceptance.** Retain the applicable evidence, capability limits and reproduction/demonstration record for 92; complete review, verified integration and any separate closure under the centralized Foundation acceptance rules.
  - Foundation evidence follow-up scope: audit linkage to pre-existing historical records and named later qualification owners; document genuine gaps without rewriting accepted hashes, changing parent states, inventing runs or extending this task to other tracks.

- [ ] **Increment 93 — Tutorials, plugin-author guides, and cross-project reuse examples**
  - [ ] **F-093.A — Architecture, scope and extensibility.** Use public-only external consumers and existing supported compiler/plugin contracts for tutorial examples.
  - [ ] **F-093.B — Implementation and integration.** Add progressive analog/AMS/domain tutorials, patterns/anti-patterns, standalone external consumers, and out-of-tree design/compiler/backend/tool plugin author tutorials with conformance commands.
  - [ ] **F-093.C — Correctness, rejection and predecessor regression.** Exercise documented positive examples and intentional anti-pattern diagnostics; distinguish inert candidates from working examples.
  - [ ] **F-093.D — Independent validation and applicability**
    - [ ] **F-093.D.1** Actual public demonstrations: Reproduce selected public Scala/tutorial/plugin examples and retain the actual affected Verilog-* or other outputs plus commands, versions and paths.
    - [ ] **F-093.D.2** Independent example qualification: Execute only the applicable already-supported profile lanes, distinguishing syntax/elaboration, behavioral/numerical, synthesis and proof results; keep candidate-only tutorial sections explicitly inert.
  - [ ] **F-093.E — Optimization review and output quality.** Review tutorial/output readability and unnecessary scaffolding without replacing actual generated examples with illustrations. Apply the proportionate review rule above; document no new optimization required when justified.
  - [ ] **F-093.F — Scale, determinism and compatibility.** Exercise clean external-consumer contexts and repeated example generation with stable paths and supported versions.
  - [ ] **F-093.G — Evidence, documentation and acceptance.** Retain the applicable evidence, capability limits and reproduction/demonstration record for 93; complete review, verified integration and any separate closure under the centralized Foundation acceptance rules.

- [ ] **Increment 94 — Cross-platform core and plugin packaging**
  - [ ] **F-094.A — Architecture, scope and extensibility.** Separate core, native-platform and plugin bundle identities; preserve declared platform/source-fallback boundaries.
  - [ ] **F-094.B — Implementation and integration.** Produce checksummed core Scala/native bundles for supported Linux/macOS first, Windows strategy and source fallback, plus plugin bundle/platform conventions and stable hooks for independently published libraries/plugins.
  - [ ] **F-094.C — Correctness, rejection and predecessor regression.** Test install/discovery failures, mismatched native bundles and unsupported platform diagnostics.
  - [ ] **F-094.D — Independent validation and applicability**
    - [ ] **F-094.D.1** Package consumer evidence: Use actual produced bundles in clean supported consumer/install contexts and independently verify hashes, manifests, licenses and compatibility; record platform-specific results.
    - [ ] **F-094.D.2** Execution-claim boundary: Retain underlying compiler/plugin qualification references rather than crediting packaging with new HDL simulation, synthesis or formal evidence.
  - [ ] **F-094.E — Optimization review and output quality.** Review redundant distribution contents without omitting licenses, schema or required runtime files. Apply the proportionate review rule above; document no new optimization required when justified.
  - [ ] **F-094.F — Scale, determinism and compatibility.** Exercise declared Linux/macOS bundles and documented Windows/source-fallback policy with reproducible manifests.
  - [ ] **F-094.G — Evidence, documentation and acceptance.** Retain the applicable evidence, capability limits and reproduction/demonstration record for 94; complete review, verified integration and any separate closure under the centralized Foundation acceptance rules.

- [ ] **Increment 95 — Reproducible release, provenance, plugin lockfiles, and SBOM**
  - [ ] **F-095.A — Architecture, scope and extensibility.** Tie release artifacts to immutable source/toolchain/plugin/pass provenance and declared reproducibility inputs.
  - [ ] **F-095.B — Implementation and integration.** Add release automation, checksums/signatures where possible, dependency/plugin SBOM, plugin lockfile and graph provenance, toolchain/pass/adapter evidence, license inventory, and rebuild verification.
  - [ ] **F-095.C — Correctness, rejection and predecessor regression.** Reject inconsistent hashes, missing licenses/SBOM entries and rebuild divergence; retain failed-release diagnostics.
  - [ ] **F-095.D — Independent validation and applicability**
    - [ ] **F-095.D.1** Qualified-artifact audit: Tie actual release artifacts to existing executed per-profile compiler, syntax/elaboration, simulation/numerical, synthesis and proof evidence as applicable; no missing required lane is replaced by a release checklist.
    - [ ] **F-095.D.2** Reproduction and boundary audit: Verify supported installation/rebuild and artifact identity, declared limitations and deferred release boundaries. Do not require unrelated deferred-track implementations for the current release.
  - [ ] **F-095.E — Optimization review and output quality.** Review packaging/rebuild overhead without weakening source or artifact integrity. Apply the proportionate review rule above; document no new optimization required when justified.
  - [ ] **F-095.F — Scale, determinism and compatibility.** Exercise multiple supported bundles and repeated clean rebuilds with deterministic inventories.
  - [ ] **F-095.G — Evidence, documentation and acceptance.** Retain the applicable evidence, capability limits and reproduction/demonstration record for 95; complete review, verified integration and any separate closure under the centralized Foundation acceptance rules.

- [ ] **Increment 96 — Performance and scalability benchmarks**
  - [ ] **F-096.A — Architecture, scope and extensibility.** Reuse feature-specific scale observations and define representative benchmark families rather than fabricated universal targets.
  - [ ] **F-096.B — Implementation and integration**
    - [ ] **F-096.B.1** Benchmark deep/nested interfaces, symbolic interface arrays, role expansion, logical ABI/source-map size, flattening, wrapper generation, resolved-driver graphs, conservative topology graphs, and mixed-signal interface verification.
    - [ ] **F-096.B.2** Benchmark construction, ranked shape algebra and parameter matrices, expression inlining/materialization and source-map size, naming stability, mandatory check phases/path reconstruction, MLIR, semantic analyses, automatic pipelines, domains/CDC/RDC, portable Verilog, open-source verification, plugin manifest resolution, capability graphs, design-host contributions, native/process plugin overhead, cache behavior, pass time, memory, hierarchy, and regression launch.
  - [ ] **F-096.C — Correctness, rejection and predecessor regression.** Check workload validity, measurement isolation and regression detection; distinguish unsupported configurations from slow supported cases.
  - [ ] **F-096.D — Independent validation and applicability**
    - [ ] **F-096.D.1** Measurement evidence: Retain actual generated workloads/artifacts, pinned commands/environment and separate construction/compiler/output/tool timings, memory and size observations; repeat measurements where justified.
    - [ ] **F-096.D.2** Correctness before performance: Reference applicable qualified behavior for measured cases and verify equal semantic/configuration identity; a faster incorrect or reduced-scope result is not a benchmark improvement.
  - [ ] **F-096.E — Optimization review and output quality.** Review compiler and generated-output costs separately, retaining profiles, tool versions and methodology. Apply the proportionate review rule above; document no new optimization required when justified.
  - [ ] **F-096.F — Scale, determinism and compatibility.** Publish measured runtime/memory/output-size observations for the existing hierarchy, graph, interface, shape, plugin and tool workloads.
  - [ ] **F-096.G — Evidence, documentation and acceptance.** Retain the applicable evidence, capability limits and reproduction/demonstration record for 96; complete review, verified integration and any separate closure under the centralized Foundation acceptance rules.

- [ ] **Increment 97 — Public API and plugin SPI v1 review**
  - [ ] **F-097.A — Architecture, scope and extensibility.** Review implemented public API/SPI experience against frozen contracts; approve compatibility changes through a versioned gate.
  - [ ] **F-097.B — Implementation and integration.** Review v0.1/v0.2/v0.3 APIs and plugin SPI implementation experience, including shaped values/layout/storage, expression materialization/naming/source maps, check profiles/inventory/waivers, capability identity/cardinality, phase contexts, native/process compatibility, trust, determinism, plugin/library boundaries, implicit domains, pipelines, backend inference, and low-level escape. Approve only justified changes and define semantic versioning/deprecation/source/SPI compatibility.
  - [ ] **F-097.C — Correctness, rejection and predecessor regression.** Use concrete migration and rejection examples for source/SPI breaks and obsolete assumptions.
  - [ ] **F-097.D — Independent validation and applicability.** Gate validation and applicability: Retain actual compile-positive/negative public or schema candidates and independent design review where required by the existing gate. Record that unimplemented backend/simulator/synthesis behavior receives no execution credit; documentation-only boundaries acquire no artificial HDL tests.
  - [ ] **F-097.E — Optimization review and output quality.** Review API complexity and accumulated quality findings; justify each change instead of making compatibility churn a goal. Apply the proportionate review rule above; document no new optimization required when justified.
  - [ ] **F-097.F — Scale, determinism and compatibility.** Exercise representative external consumers and supported-version compatibility cases.
  - [ ] **F-097.G — Evidence, documentation and acceptance.** Retain the applicable evidence, capability limits and reproduction/demonstration record for 97; complete review, verified integration and any separate closure under the centralized Foundation acceptance rules.

- [ ] **Increment 98 — Nodal core preview release**
  - [ ] **F-098.A — Architecture, scope and extensibility.** Release only the declared core preview scope, preserving deferred formal, approximation and dependent-track boundaries.
  - [ ] **F-098.B — Implementation and integration.** Publish the supported preview with frozen public API and plugin SPI revisions, toolchain pins, shaped-value/layout and naming/materialization manifests, machine-readable mandatory-check coverage/waiver inventory, portable Verilog/Verilog-A/Verilog-AMS matrices, open-source verification evidence, plugin conformance kit, installation, examples, known limitations, library/plugin-author contracts, and reproducible provenance.
  - [ ] **F-098.C — Correctness, rejection and predecessor regression.** Audit advertised capabilities against actual qualified outputs and reject incomplete or inconsistent release evidence.
  - [ ] **F-098.D — Independent validation and applicability**
    - [ ] **F-098.D.1** Qualified-artifact audit: Tie actual release artifacts to existing executed per-profile compiler, syntax/elaboration, simulation/numerical, synthesis and proof evidence as applicable; no missing required lane is replaced by a release checklist.
    - [ ] **F-098.D.2** Reproduction and boundary audit: Verify supported installation/rebuild and artifact identity, declared limitations and deferred release boundaries. Do not require unrelated deferred-track implementations for the current release.
  - [ ] **F-098.E — Optimization review and output quality.** Review output/readability and packaging findings proportionately; unresolved required quality gates block release. Apply the proportionate review rule above; document no new optimization required when justified.
  - [ ] **F-098.F — Scale, determinism and compatibility.** Exercise reproducible installation/consumer cases on supported platforms and retain scale limitations.
  - [ ] **F-098.G — Evidence, documentation and acceptance.** Retain the applicable evidence, capability limits and reproduction/demonstration record for 98; complete review, verified integration and any separate closure under the centralized Foundation acceptance rules.

- [ ] **Increment 99 — Future SystemVerilog/SystemVerilog-AMS backend research gate**
  - [ ] **F-099.A — Architecture, scope and extensibility.** Keep SystemVerilog/SystemVerilog-AMS research separate from portable Verilog; gate implementation and native/flat parity explicitly.
  - [ ] **F-099.B — Implementation and integration**
    - [ ] **F-099.B.1** Evaluate native SystemVerilog `interface`/`modport`, nested interfaces, parameters, monitor roles, resolved `inout`, per-instance flatten overrides, wrapper/compile-order manifests, and exact semantic/ABI parity with portable flattened Verilog and Verilog-AMS representations.
    - [ ] **F-099.B.2** Reassess the current standards and tool support; map IR/plugin/backend coverage; evaluate exact `logic signed`/signed parameter/localparam/packed-field/array/memory/function/loop-variable lowering and parity with portable Verilog; default unpacked multidimensional array ports of packed elements, optional multidimensional packed layouts, parameterized dimensions, tool/profile compatibility, wrapper/ABI manifests, and signed-element parity with flat portable carriers; native `typedef enum logic` emission, design-level enum packages/compile-order manifests, enum-typed ports/parameters/aggregates/memories; structural generate and procedural-loop lowering; statechart lowering; and compatibility with portable-Verilog numeric mappings; identify required changes; and approve or reject implementation through a separate gate without speculating syntax into the stable API.
  - [ ] **F-099.C — Correctness, rejection and predecessor regression.** Compare approved candidates and unsupported syntax/tool capabilities without treating research examples as implemented backend support.
  - [ ] **F-099.D — Independent validation and applicability.** Research evidence: Retain dated standards/tool-capability sources and actual candidate experiment commands/results for the reviewed profiles. Explicitly separate handwritten/candidate fixtures from Nodal-generated output and a research recommendation from qualified backend support.
  - [ ] **F-099.E — Optimization review and output quality.** Review native versus flat representation cost and readability without weakening logical ABI or numeric semantics. Apply the proportionate review rule above; document no new optimization required when justified.
  - [ ] **F-099.F — Scale, determinism and compatibility.** Record representative shape/interface/enum/loop and tool-version compatibility experiments with clear limits.
  - [ ] **F-099.G — Evidence, documentation and acceptance.** Retain the applicable evidence, capability limits and reproduction/demonstration record for 99; complete review, verified integration and any separate closure under the centralized Foundation acceptance rules.

## Phase 6 — FPGA-accelerated AMS approximation and hardware validation

- [ ] **Increment 100 — AMS-to-FPGA approximation capability gate and API contracts**
  - [ ] **F-100.A — Architecture, scope and extensibility**
    - [ ] **F-100.A.1** Use [ADR 0011](../architecture/0011-ams-fpga-approximation-validation.md), [`ams-fpga-validation-plan.md`](ams-fpga-validation-plan.md), and [`ams-fpga-validation-surface.json`](ams-fpga-validation-surface.json) as the mandatory architecture and candidate.
    - [ ] **F-100.A.2** Freeze explicit approximation, solver, sample/rate, numeric and error-envelope contracts; Backend.Auto must remain non-approximating.
  - [ ] **F-100.B — Implementation and integration.** Compile candidate approximation, solver, sample/rate, numeric, range, error-budget, validation-envelope, target, and HIL contracts. Prove `Backend.Auto` never selects approximation and that unsupported AMS constructs fail with stable source-located diagnostics.
  - [ ] **F-100.C — Correctness, rejection and predecessor regression.** Compile positive/negative contracts, unsupported AMS cases and forbidden implicit approximation requests.
  - [ ] **F-100.D — Independent validation and applicability.** Gate validation and applicability: Retain actual compile-positive/negative public or schema candidates and independent design review where required by the existing gate. Record that unimplemented backend/simulator/synthesis behavior receives no execution credit; documentation-only boundaries acquire no artificial HDL tests.
  - [ ] **F-100.E — Optimization review and output quality.** Review candidate complexity and preservation of reference/error-budget metadata without implementing solvers at the gate. Apply the proportionate review rule above; document no new optimization required when justified.
  - [ ] **F-100.F — Scale, determinism and compatibility.** Exercise representative model/rate/numeric candidate combinations and external-consumer compatibility.
  - [ ] **F-100.G — Evidence, documentation and acceptance**
    - [ ] **F-100.G.1** Publish `NodalAmsFpgaApproximation-DG-v0.4.md`, compatibility/migration rules, a machine-readable frozen surface, external-library fixtures, claims language, and complete positive/negative contracts before implementation.
    - [ ] **F-100.G.2** Retain the applicable evidence, capability limits and reproduction/demonstration record for 100; complete review, verified integration and any separate closure under the centralized Foundation acceptance rules.

- [ ] **Increment 101 — Analog normalization and sampled-state IR**
  - [ ] **F-101.A — Architecture, scope and extensibility.** Normalize only declared model classes into dimensioned sampled-state IR with authoritative continuous reference identity.
  - [ ] **F-101.B — Implementation and integration**
    - [ ] **F-101.B.1** Normalize supported linear state-space, transfer-function, and explicit-ODE models into target-neutral state/update IR with dimensions, parameters, inputs/outputs, algebraic dependencies, events, initial conditions, and authoritative reference links.
    - [ ] **F-101.B.2** Diagnose unresolved DAEs/algebraic loops, hidden state, unsupported nonlinearities, unsupported analyses, and constructs that cannot form a deterministic sampled recurrence.
  - [ ] **F-101.C — Correctness, rejection and predecessor regression.** Reject unresolved DAEs, hidden state, unsupported nonlinearities and nondeterministic recurrences; test hierarchy and parameters.
  - [ ] **F-101.D — Independent validation and applicability**
    - [ ] **F-101.D.1** Independent mathematical reference: Compare actual normalized models, coefficients, formats, schedules or analysis results with independently constructed references and declared numerical tolerances/assumptions; retain units, envelopes and commands.
    - [ ] **F-101.D.2** Downstream target boundary: Preserve witnesses and configuration identity for the existing RTL/solver-facing qualification owners. These software/mathematical results are neither HDL synthesis evidence nor a general numerical/formal equivalence proof.
  - [ ] **F-101.E — Optimization review and output quality.** Review equation/state duplication without changing the supported model or introducing approximation silently. Apply the proportionate review rule above; document no new optimization required when justified.
  - [ ] **F-101.F — Scale, determinism and compatibility.** Exercise state-vector size and sparse algebraic dependencies with deterministic recurrence identities.
  - [ ] **F-101.G — Evidence, documentation and acceptance.** Retain the applicable evidence, capability limits and reproduction/demonstration record for 101; complete review, verified integration and any separate closure under the centralized Foundation acceptance rules.

- [ ] **Increment 102 — Solver and discrete-time recurrence generation**
  - [ ] **F-102.A — Architecture, scope and extensibility.** Bind recurrence generation to approved solver/model classes and explicit initialization, conditioning and convergence contracts.
  - [ ] **F-102.B — Implementation and integration**
    - [ ] **F-102.B.1** Implement the approved forward/backward Euler, trapezoidal/Tustin, exact-ZOH, and custom-solver contracts only for supported model classes.
    - [ ] **F-102.B.2** Generate deterministic coefficients and recurrence IR, high-precision software references, initialization/reset behavior, stability/conditioning evidence, bounded iteration/convergence rules, latency/resource models, and failure diagnostics.
  - [ ] **F-102.C — Correctness, rejection and predecessor regression.** Test unstable/ill-conditioned and unsupported systems, iteration limits and reset; compare supported solver cases against references.
  - [ ] **F-102.D — Independent validation and applicability**
    - [ ] **F-102.D.1** Independent mathematical reference: Compare actual normalized models, coefficients, formats, schedules or analysis results with independently constructed references and declared numerical tolerances/assumptions; retain units, envelopes and commands.
    - [ ] **F-102.D.2** Downstream target boundary: Preserve witnesses and configuration identity for the existing RTL/solver-facing qualification owners. These software/mathematical results are neither HDL synthesis evidence nor a general numerical/formal equivalence proof.
  - [ ] **F-102.E — Optimization review and output quality.** Review coefficient computation and recurrence cost without dropping precision or changing solver semantics. Apply the proportionate review rule above; document no new optimization required when justified.
  - [ ] **F-102.F — Scale, determinism and compatibility.** Exercise state count, sample-period and parameter-envelope cases with repeatable high-precision coefficients.
  - [ ] **F-102.G — Evidence, documentation and acceptance.** Retain the applicable evidence, capability limits and reproduction/demonstration record for 102; complete review, verified integration and any separate closure under the centralized Foundation acceptance rules.

- [ ] **Increment 103 — Range, fixed-point, quantization, and error-budget analysis**
  - [ ] **F-103.A — Architecture, scope and extensibility.** Represent physical scaling, fixed-point formats and accumulated error independently of backend arithmetic defaults.
  - [ ] **F-103.B — Implementation and integration**
    - [ ] **F-103.B.1** Add physical scaling, range assertions/inference, explicit/automatic fixed-point formats, guard bits, rounding, overflow, coefficient quantization, state/intermediate formats, and accumulated error accounting.
    - [ ] **F-103.B.2** Generate bit-accurate references and Level B evidence; reject unbounded state, uncovered ranges, impossible error/resource policies, or implicit numeric choices.
  - [ ] **F-103.C — Correctness, rejection and predecessor regression.** Test range/overflow/rounding boundaries and uncovered envelopes; reject implicit numeric policy and impossible budgets.
  - [ ] **F-103.D — Independent validation and applicability**
    - [ ] **F-103.D.1** Independent mathematical reference: Compare actual normalized models, coefficients, formats, schedules or analysis results with independently constructed references and declared numerical tolerances/assumptions; retain units, envelopes and commands.
    - [ ] **F-103.D.2** Downstream target boundary: Preserve witnesses and configuration identity for the existing RTL/solver-facing qualification owners. These software/mathematical results are neither HDL synthesis evidence nor a general numerical/formal equivalence proof.
  - [ ] **F-103.E — Optimization review and output quality.** Review word lengths and guard bits only with retained Level B error evidence. Apply the proportionate review rule above; document no new optimization required when justified.
  - [ ] **F-103.F — Scale, determinism and compatibility.** Exercise state/intermediate widths and envelope corners with deterministic format choices and references.
  - [ ] **F-103.G — Evidence, documentation and acceptance.** Retain the applicable evidence, capability limits and reproduction/demonstration record for 103; complete review, verified integration and any separate closure under the centralized Foundation acceptance rules.

- [ ] **Increment 104 — Multi-rate, sampled-event, and real-time scheduling**
  - [ ] **F-104.A — Architecture, scope and extensibility.** Make rational rate partitions, event sampling and update ordering explicit; integrate domains and scheduling barriers.
  - [ ] **F-104.B — Implementation and integration**
    - [ ] **F-104.B.1** Implement rational multi-rate partitions, sample/hold, interpolation, decimation, sampled/interpolated event detection, buffering, timestamps, update ordering, and rate/clock bridges.
    - [ ] **F-104.B.2** Integrate `ClockDomain`, reset, CDC/RDC, `Valid`/`Stream`, memories, and automatic pipelines. Prove each sample deadline and diagnose infeasible real-time schedules.
  - [ ] **F-104.C — Correctness, rejection and predecessor regression.** Test sample deadlines, bridge/reset/CDC interactions and event ordering; reject infeasible real-time schedules.
  - [ ] **F-104.D — Independent validation and applicability**
    - [ ] **F-104.D.1** Independent mathematical reference: Compare actual normalized models, coefficients, formats, schedules or analysis results with independently constructed references and declared numerical tolerances/assumptions; retain units, envelopes and commands.
    - [ ] **F-104.D.2** Downstream target boundary: Preserve witnesses and configuration identity for the existing RTL/solver-facing qualification owners. These software/mathematical results are neither HDL synthesis evidence nor a general numerical/formal equivalence proof.
  - [ ] **F-104.E — Optimization review and output quality.** Review buffers and rate-conversion work without changing sampled-event or interpolation contracts. Apply the proportionate review rule above; document no new optimization required when justified.
  - [ ] **F-104.F — Scale, determinism and compatibility.** Exercise multiple rate ratios and large schedules with reproducible deadline evidence.
  - [ ] **F-104.G — Evidence, documentation and acceptance.** Retain the applicable evidence, capability limits and reproduction/demonstration record for 104; complete review, verified integration and any separate closure under the centralized Foundation acceptance rules.

- [ ] **Increment 105 — Synthesizable FPGA approximation backend**
  - [ ] **F-105.A — Architecture, scope and extensibility.** Lower explicit sampled fixed-point semantics into existing digital IR and Backend.Verilog, never general AMS synthesis.
  - [ ] **F-105.B — Implementation and integration**
    - [ ] **F-105.B.1** Lower the discrete fixed-point model into ordinary Nodal digital IR and reuse symbolic parameters, hierarchy, memories, clock/reset, protocols, automatic pipelines, CDC/RDC, and `Backend.Verilog`.
    - [ ] **F-105.B.2** Emit deterministic portable Verilog, source maps, recurrence/numeric/rate/schedule manifests, capability limitations, simulation/formal hooks, and exact golden fixtures.
  - [ ] **F-105.C — Correctness, rejection and predecessor regression.** Test recurrence/reset/rate/numeric lowering and reject remaining unsupported analog behavior.
  - [ ] **F-105.D — Independent validation and applicability**
    - [ ] **F-105.D.1** Syntax and elaboration: Use the pinned 66 Verilator/Icarus lanes on actual generated HDL in the selected language/profile; record source/output identities, commands and capability limits.
    - [ ] **F-105.D.2** Behavioral simulation: Simulate the selected boundary and predecessor-interaction cases against declared reference behavior, retaining seeds, reset/stimulus sequences and waveforms.
    - [ ] **F-105.D.3** Synthesis and structure: Use 67 Yosys checks for the synthesizable digital artifacts, retaining hierarchy, cell/memory/driver and parameter results.
    - [ ] **F-105.D.4** Equivalence and formal applicability: Record distinct equivalence/property applicability and execute every required transformation, safety or protocol check through 67; retain assumptions, bounds and counterexamples rather than using simulation as proof.
  - [ ] **F-105.E — Optimization review and output quality.** Review registers, arithmetic and pipeline resources without changing approximation or numeric error budgets. Apply the proportionate review rule above; document no new optimization required when justified.
  - [ ] **F-105.F — Scale, determinism and compatibility.** Exercise parameterized model sizes and stable recurrence-to-RTL source maps.
  - [ ] **F-105.G — Evidence, documentation and acceptance.** Retain the applicable evidence, capability limits and reproduction/demonstration record for 105; complete review, verified integration and any separate closure under the centralized Foundation acceptance rules.

- [ ] **Increment 106 — Differential, equivalence, and formal validation ladder**
  - [ ] **F-106.A — Architecture, scope and extensibility.** Keep Levels A, B and C evidence separate and tied to one approximation/configuration identity.
  - [ ] **F-106.B — Implementation and integration**
    - [ ] **F-106.B.1** Implement Level A AMS-reference versus high-precision-discrete comparison and Level B high-precision versus fixed-point comparison using declared waveform/state/event/frequency metrics and envelopes.
    - [ ] **F-106.B.2** Implement Level C Verilator/Icarus regression, Yosys equivalence, and SBY properties for recurrence, reset, protocols, multi-rate scheduling, range/overflow, latency, and deadlines. Preserve failures and counterexamples by error class.
  - [ ] **F-106.C — Correctness, rejection and predecessor regression.** Retain failing reference, quantization and RTL/proof cases with correct error-class attribution.
  - [ ] **F-106.D — Independent validation and applicability**
    - [ ] **F-106.D.1** Level A: Compare actual AMS reference execution with high-precision discrete behavior using declared state/waveform/event/frequency metrics and envelopes.
    - [ ] **F-106.D.2** Level B: Compare high-precision and bit-accurate fixed-point references under the same solver/numeric configuration with retained quantization/range evidence.
    - [ ] **F-106.D.3** Level C: Independently parse/simulate generated RTL and execute applicable Yosys synthesis/equivalence and SBY properties; record these as separate results and preserve failing artifacts.
  - [ ] **F-106.E — Optimization review and output quality.** Review validation cost without loosening waveform/state/event/frequency metrics or claiming simulation as proof. Apply the proportionate review rule above; document no new optimization required when justified.
  - [ ] **F-106.F — Scale, determinism and compatibility.** Exercise declared envelopes and repeatable seeded cross-level comparisons with retained traces.
  - [ ] **F-106.G — Evidence, documentation and acceptance.** Retain the applicable evidence, capability limits and reproduction/demonstration record for 106; complete review, verified integration and any separate closure under the centralized Foundation acceptance rules.

- [ ] **Increment 107 — Open-source FPGA implementation and target evidence**
  - [ ] **F-107.A — Architecture, scope and extensibility.** Select a pinned complete open FPGA target and explicit constraints/board identity for the approximation, not the dependent productivity track.
  - [ ] **F-107.B — Implementation and integration**
    - [ ] **F-107.B.1** Select and pin at least one complete open FPGA target using Yosys, nextpnr, constraints, an open bitstream packer/programmer, deterministic seeds, and reproducible board metadata.
    - [ ] **F-107.B.2** Run synthesis, placement, routing, timing, bitstream generation, utilization, DSP/memory accounting, and post-route sample-deadline checks. Add optional vendor adapters without making them normative.
  - [ ] **F-107.C — Correctness, rejection and predecessor regression.** Test missing target capabilities, constraint errors and sample-deadline violations; retain build/tool failures.
  - [ ] **F-107.D — Independent validation and applicability**
    - [ ] **F-107.D.1** Generated RTL qualification: Retain the applicable independent parse/simulation, synthesis and equivalence/formal results for the actual approximation RTL.
    - [ ] **F-107.D.2** Physical target evidence: Execute the pinned synthesis/place/route/timing/bitstream stages; retain constraints, seeds, board identity, resource reports and post-route sample-deadline results. Estimates are not timing closure.
  - [ ] **F-107.E — Optimization review and output quality.** Review implemented resource/timing results separately from compiler estimates. Apply the proportionate review rule above; document no new optimization required when justified.
  - [ ] **F-107.F — Scale, determinism and compatibility.** Exercise representative target/parameter cases with fixed seeds, versioned board data and reproducible bitstreams.
  - [ ] **F-107.G — Evidence, documentation and acceptance.** Retain the applicable evidence, capability limits and reproduction/demonstration record for 107; complete review, verified integration and any separate closure under the centralized Foundation acceptance rules.

- [ ] **Increment 108 — Hardware-in-the-loop runtime, vertical slices, and capability matrix**
  - [ ] **F-108.A — Architecture, scope and extensibility.** Bind host transport, runtime status and HIL traces to the exact board/bitstream/model identity.
  - [ ] **F-108.B — Implementation and integration**
    - [ ] **F-108.B.1** Add deterministic start/stop/reset, timestamped sampled streams, parameter loading, trace capture, status/deadline/overflow reporting, reproducible host transport, and optional external ADC/DAC board profiles.
    - [ ] **F-108.B.2** Complete RC/RLC, controlled-plant, comparator/ADC/DAC, and PLL/control-loop vertical slices through all four validation levels.
    - [ ] **F-108.B.3** Publish supported/unsupported constructs, validation envelopes, approximation/error limits, resource/timing results, board/bitstream evidence, claims language, and the M5 FPGA-accelerated AMS validation release package.
  - [ ] **F-108.C — Correctness, rejection and predecessor regression.** Test reset/start/stop, loss/overflow/deadline reporting and declared reference envelopes across the promised vertical slices.
  - [ ] **F-108.D — Independent validation and applicability**
    - [ ] **F-108.D.1** Levels A-C provenance: Retain the required continuous/discrete/fixed-point/RTL reference and proof evidence for the exact configuration before presenting hardware results.
    - [ ] **F-108.D.2** Level D hardware execution: Execute the promised board/HIL cases on the identified bitstream, retaining timestamped traces, transport settings and deadline/overflow reports against declared tolerances. Hardware success cannot erase reference or quantization failures.
  - [ ] **F-108.E — Optimization review and output quality.** Review transport/trace overhead without hiding missed deadlines or approximation errors. Apply the proportionate review rule above; document no new optimization required when justified.
  - [ ] **F-108.F — Scale, determinism and compatibility.** Exercise sustained sampled streams and repeatable board runs within published capacity limits.
  - [ ] **F-108.G — Evidence, documentation and acceptance.** Retain the applicable evidence, capability limits and reproduction/demonstration record for 108; complete review, verified integration and any separate closure under the centralized Foundation acceptance rules.

## Phase 7 — Deferred, independently schedulable user-authored formal verification

This phase is deliberately outside the initial core, plugin, and AMS-to-FPGA milestones. It may be pulled forward after Increments 15, 19-23, 54-67, 82, and 87 provide the public semantic, IR, backend, core-proof, compiler-plugin, and formal-adapter prerequisites. No formal implementation is performed by this roadmap update.

- [ ] **Increment 109 — Formal verification architecture gate and public API v0.1 contracts**
  - [ ] **F-109.A — Architecture, scope and extensibility**
    - [ ] **F-109.A.1** Use [ADR 0014](../architecture/0014-target-neutral-formal-verification.md), [`formal-verification-v0.1-plan.md`](formal-verification-v0.1-plan.md), and [`formal-verification-v0.1-surface.json`](formal-verification-v0.1-surface.json) as the mandatory architecture and candidate.
    - [ ] **F-109.A.2** Freeze clock/reset, combinational, cross-domain, parameter, memory, black-box, assumption-scope, vacuity, result-state, source-map, simulation/formal-inclusion, and immediate-assertion-only synthesis semantics.
    - [ ] **F-109.A.3** Freeze target-neutral property/task authoring and verification-only inclusion before lowering or execution.
  - [ ] **F-109.B — Implementation and integration.** Compile and compare concise formal context, assert/assume/cover, property IDs/groups, sampled-value operators, bounded temporal forms, symbolic values, harness, contract, and task configuration candidates.
  - [ ] **F-109.C — Correctness, rejection and predecessor regression.** Compile domain/reset/parameter/model candidates and reject synthesis requests for temporal/history/assume/cover semantics.
  - [ ] **F-109.D — Independent validation and applicability.** Gate validation and applicability: Retain actual compile-positive/negative public or schema candidates and independent design review where required by the existing gate. Record that unimplemented backend/simulator/synthesis behavior receives no execution credit; documentation-only boundaries acquire no artificial HDL tests.
  - [ ] **F-109.E — Optimization review and output quality.** Review property/API complexity and preserved source identities without introducing a solver-specific authoring model. Apply the proportionate review rule above; document no new optimization required when justified.
  - [ ] **F-109.F — Scale, determinism and compatibility.** Exercise nested harness and property-group candidates plus external-consumer/version compatibility.
  - [ ] **F-109.G — Evidence, documentation and acceptance**
    - [ ] **F-109.G.1** Publish `NodalFormalVerification-DG-v0.1.md`, machine-readable frozen API/task surfaces, compatibility policy, stable diagnostics, and positive/negative external-consumer fixtures. Keep execution/lowering inert until approval.
    - [ ] **F-109.G.2** Retain the applicable evidence, capability limits and reproduction/demonstration record for 109; complete review, verified integration and any separate closure under the centralized Foundation acceptance rules.

- [ ] **Increment 110 — Target-neutral formal property IR, verifier, and lowering framework**
  - [ ] **F-110.A — Architecture, scope and extensibility**
    - [ ] **F-110.A.1** Selectively reuse CIRCT `verif` and `ltl` through verified conversions; retain Nodal-owned operations where semantics differ or capabilities are missing.
    - [ ] **F-110.A.2** Retain typed property/domain/reset semantics and explicit immediate-checker inclusion; reuse CIRCT only with matching semantics.
  - [ ] **F-110.B — Implementation and integration**
    - [ ] **F-110.B.1** Implement formal test/harness, property, symbolic-value, sampled-history, bounded-temporal, contract, enable/reset, and formal-model operations with stable IDs and source maps.
    - [ ] **F-110.B.2** Add property/domain/reset/type/capability verification, immediate-versus-temporal classification, deterministic parse/print, normalized reports, verification-only immediate/monitor/sidecar lowering, and explicit immediate-checker RTL lowering.
  - [ ] **F-110.C — Correctness, rejection and predecessor regression**
    - [ ] **F-110.C.1** Prove concurrent or temporal properties, sampled history, assumptions, covers, symbolic values, and generated verification monitors cannot enter ordinary synthesis artifacts; prove immediate assertions synthesize only through explicit inclusion.
    - [ ] **F-110.C.2** Mutate verification-only exclusion and test malformed temporal/history/property operations and unsupported capabilities.
  - [ ] **F-110.D — Independent validation and applicability**
    - [ ] **F-110.D.1** Lowering and synthesis exclusion: Inspect actual generated formal/sidecar and explicitly selected immediate-checker artifacts; independently parse them and use 67 structure checks to verify temporal/history/assume/cover monitors cannot enter ordinary synthesis.
    - [ ] **F-110.D.2** Property semantics: Validate selected property transformations with declared semantic references and existing core-proof facilities where applicable; retain later 112 adapter qualification without making it a reverse prerequisite.
  - [ ] **F-110.E — Optimization review and output quality.** Review monitor/history expansion while ensuring it cannot leak into ordinary synthesizable DUT RTL. Apply the proportionate review rule above; document no new optimization required when justified.
  - [ ] **F-110.F — Scale, determinism and compatibility.** Exercise property graph/history sizes with deterministic parse/print, IDs and sidecar layout.
  - [ ] **F-110.G — Evidence, documentation and acceptance.** Retain the applicable evidence, capability limits and reproduction/demonstration record for 110; complete review, verified integration and any separate closure under the centralized Foundation acceptance rules.

- [ ] **Increment 111 — Formal harnesses, symbolic environments, compositional contracts, and model abstractions**
  - [ ] **F-111.A — Architecture, scope and extensibility.** Compose harness assumptions, symbolic environments and abstractions with explicit soundness and domain contracts.
  - [ ] **F-111.B — Implementation and integration**
    - [ ] **F-111.B.1** Implement DUT wrappers, symbolic sequence/constants, initial assumptions, legal clock/reset generation, stable verification exports, property groups, and reusable harness composition.
    - [ ] **F-111.B.2** Implement exact or explicitly abstracted memory, external-operation, and black-box formal models with soundness and waiver reporting.
    - [ ] **F-111.B.3** Implement require/ensure contract checking/application, assume-guarantee composition, parameter matrices/envelopes, conservative multi-clock handling, and hidden-assumption/over-constraint diagnostics.
  - [ ] **F-111.C — Correctness, rejection and predecessor regression.** Test hidden assumptions, over-constraint, unsupported memory/external models and cross-clock restrictions.
  - [ ] **F-111.D — Independent validation and applicability**
    - [ ] **F-111.D.1** Harness evidence: Independently parse actual generated harnesses and execute applicable existing 67 core-proof/assumption controls with declared abstractions, clocks and parameter configurations.
    - [ ] **F-111.D.2** Adapter boundary: Retain task, assumption and trace witnesses for 112 execution qualification. Unsupported abstractions or unavailable required proof checks stay blocked, not proven.
  - [ ] **F-111.E — Optimization review and output quality.** Review harness duplication and abstraction cost without erasing assumptions or strengthening results silently. Apply the proportionate review rule above; document no new optimization required when justified.
  - [ ] **F-111.F — Scale, determinism and compatibility.** Exercise property groups and parameter envelopes with reproducible harness/source identities.
  - [ ] **F-111.G — Evidence, documentation and acceptance.** Retain the applicable evidence, capability limits and reproduction/demonstration record for 111; complete review, verified integration and any separate closure under the centralized Foundation acceptance rules.

- [ ] **Increment 112 — Pluggable proof execution, proof modes, and normalized evidence**
  - [ ] **F-112.A — Architecture, scope and extensibility.** Reuse the common adapter protocol and preserve normalized proof task/result semantics independently of SBY syntax.
  - [ ] **F-112.B — Implementation and integration**
    - [ ] **F-112.B.1** Implement SBY/Yosys as the required open-source formal adapter through the common process/evidence protocol, including BMC, prove/induction, cover, selected liveness, solver, timeout, and resource controls.
    - [ ] **F-112.B.2** Normalize per-property/task results, commands, logs, traces, proof metadata, source maps, cache keys, and reproduction commands; reject unsupported capabilities before execution.
  - [ ] **F-112.C — Correctness, rejection and predecessor regression**
    - [ ] **F-112.C.1** Add an out-of-tree mock or second formal-adapter conformance fixture so public semantics are not coupled to SBY files.
    - [ ] **F-112.C.2** Preserve inconclusive, timeout, cancellation, unsupported, and tool-error states without reporting them as proof success.
    - [ ] **F-112.C.3** Exercise a failing property, cover, inconclusive/timeout/tool-error and unsupported capability with retained evidence.
  - [ ] **F-112.D — Independent validation and applicability**
    - [ ] **F-112.D.1** Proof execution: Execute the selected actual generated property/harness tasks through the pinned qualified adapter, retaining proof mode, assumptions, solver/bounds, commands, logs and result states.
    - [ ] **F-112.D.2** Negative and replay evidence: Retain failed-property, vacuity/constraint and timeout/error controls plus applicable counterexample/cover replay; distinguish observed simulation from formal proof.
  - [ ] **F-112.E — Optimization review and output quality.** Review task/solver overhead without shortening bounds or changing assumptions merely to obtain a pass. Apply the proportionate review rule above; document no new optimization required when justified.
  - [ ] **F-112.F — Scale, determinism and compatibility.** Exercise task/solver/resource combinations and the second-adapter conformance fixture deterministically.
  - [ ] **F-112.G — Evidence, documentation and acceptance.** Retain the applicable evidence, capability limits and reproduction/demonstration record for 112; complete review, verified integration and any separate closure under the centralized Foundation acceptance rules.

- [ ] **Increment 113 — Property libraries, vacuity/coverage, counterexample replay, documentation, and conformance**
  - [ ] **F-113.A — Architecture, scope and extensibility.** Use public property/library contracts and typed replay; distinguish common property semantics from engine behavior.
  - [ ] **F-113.B — Implementation and integration**
    - [ ] **F-113.B.1** Publish passive property libraries for enums, legal-state/transition FSMs, reusable statecharts, protocols, FIFOs, pipelines, resets, CDC/RDC wrappers, memories, and common control structures using only public APIs.
    - [ ] **F-113.B.2** Implement assumption consistency, antecedent/scenario cover goals, supported vacuity checks, over-constraint reports, and defined property/scenario coverage metrics.
    - [ ] **F-113.B.3** Replay normalized counterexamples/covers through the Scala simulation API with typed transactions, domain timelines, source annotations, and VCD/FST waveforms.
  - [ ] **F-113.C — Correctness, rejection and predecessor regression.** Test vacuity, inconsistent assumptions, failed proofs and counterexample replay across protocols, FSMs and domains.
  - [ ] **F-113.D — Independent validation and applicability**
    - [ ] **F-113.D.1** Proof execution: Execute the selected actual generated property/harness tasks through the pinned qualified adapter, retaining proof mode, assumptions, solver/bounds, commands, logs and result states.
    - [ ] **F-113.D.2** Negative and replay evidence: Retain failed-property, vacuity/constraint and timeout/error controls plus applicable counterexample/cover replay; distinguish observed simulation from formal proof.
  - [ ] **F-113.E — Optimization review and output quality.** Review property/replay duplication without hiding over-constraint or reducing coverage definitions. Apply the proportionate review rule above; document no new optimization required when justified.
  - [ ] **F-113.F — Scale, determinism and compatibility.** Exercise large trace/property groups and external public-only library consumers with stable coverage IDs.
  - [ ] **F-113.G — Evidence, documentation and acceptance**
    - [ ] **F-113.G.1** Publish tutorials, adapter/property-library author guides, capability matrices, known limitations, conformance suites, and the M6 reproducible formal-verification extension package.
    - [ ] **F-113.G.2** Retain the applicable evidence, capability limits and reproduction/demonstration record for 113; complete review, verified integration and any separate closure under the centralized Foundation acceptance rules.

- [x] **Increment 114 — Immediate assertion synthesis boundary correction**
  - Restrict synthesizable assertion logic to an explicitly selected immediate Boolean assertion evaluated combinationally or in one owning clock domain.
  - Keep concurrent or temporal properties, sampled history, assumptions, covers, symbolic formal values, fairness/liveness declarations, and compiler-generated verification monitors outside synthesizable DUT RTL.
  - Require synthesized immediate assertions to be observational by default; functional control requires an ordinary explicit design connection.
  - Synchronize ADR 0014, the formal-verification plan/surface, the digital Verilog verification plan, the digital-backend surface, and roadmap revision 1.13. Keep exact public spelling and implementation deferred to Increments 109-110.
  - Evidence: [`0014-target-neutral-formal-verification.md`](../architecture/0014-target-neutral-formal-verification.md), [`formal-verification-v0.1-plan.md`](formal-verification-v0.1-plan.md), [`formal-verification-v0.1-surface.json`](formal-verification-v0.1-surface.json), [`digital-verilog-open-source-verification-plan.md`](digital-verilog-open-source-verification-plan.md), and [`digital-backend-v0.3-surface.json`](digital-backend-v0.3-surface.json).
  - [x] **F-114.H — Historical acceptance breakdown: Immediate-only synthesis architecture correction.** Retain the documentary immediate-assertion boundary and deferred 109-110 implementation; no temporal checker synthesis or executed formal tool is claimed.

- [x] **Increment 115 — Register factory architecture and roadmap contract**
  - Accept [ADR 0020](../architecture/0020-canonical-register-factory-and-transport-adapters.md), the staged [`register-factory-v0.1-plan.md`](register-factory-v0.1-plan.md), and the machine-readable [`register-factory-v0.1-surface.json`](register-factory-v0.1-surface.json).
  - Freeze register-definition-first separation: immutable bus-neutral `RegisterMap`, independent physical `RegisterBlock`, one clock/reset domain per physical bank, one exactly-once committed-access endpoint, and APB/AXI4-Lite/custom transport adapters that cannot redefine register semantics.
  - Record SystemRDL 2.0 as the primary standards-based register interchange, versioned safe Nodal YAML/JSON as a convenience frontend, IEEE 1685-2022 IP-XACT as later integration interchange, and CSV/spreadsheets only as explicit conversion inputs.
  - Freeze generated-Verilog policy: fixed register ABI symbols use width-safe non-overridable `localparam`s/constants; only explicit Nodal architectural variability becomes an HDL `parameter`; relative-offset decode is the default and any absolute-base wrapper is explicit.
  - Keep exact public API, canonical IR, parsers, bus adapters, RTL lowering, and artifact generators unimplemented and assigned to Increments 116-123.
  - [x] **F-115.H — Historical acceptance breakdown: Register-factory architecture contract.** Retain ADR 0020 and its linked plan/surface as the documentary evidence; the actual API/IR/parsers/adapters/RTL remain assigned to 116-123.

- [ ] **Increment 116 — Register factory public API candidates and design gate**
  - [ ] **F-116.A — Architecture, scope and extensibility.** Prototype immutable map versus physical block and bus-neutral committed-access semantics before implementation.
  - [ ] **F-116.B — Implementation and integration**
    - [ ] **F-116.B.1** Prototype concise Scala 3 `RegisterMap`, `RegisterBlock`, register/field definitions, typed handles, hardware bindings, arrays/submaps/windows/aliases, snapshots/commits, software/hardware/collision policies, and transport binding.
    - [ ] **F-116.B.2** Compare alternatives against mature bus-slave/register-interface facilities while preserving Nodal's stronger register-definition-first separation.
  - [ ] **F-116.C — Correctness, rejection and predecessor regression.** Compile policy/collision/geometry/transport candidates and reject ambiguous ownership or unsupported public forms.
  - [ ] **F-116.D — Independent validation and applicability.** Gate validation and applicability: Retain actual compile-positive/negative public or schema candidates and independent design review where required by the existing gate. Record that unimplemented backend/simulator/synthesis behavior receives no execution credit; documentation-only boundaries acquire no artificial HDL tests.
  - [ ] **F-116.E — Optimization review and output quality.** Review authoring verbosity and future canonicalization needs without embedding one bus in RegisterMap. Apply the proportionate review rule above; document no new optimization required when justified.
  - [ ] **F-116.F — Scale, determinism and compatibility.** Exercise nested/array/submap candidates and public-only external-consumer compatibility.
  - [ ] **F-116.G — Evidence, documentation and acceptance**
    - [ ] **F-116.G.1** Freeze exact imports, names, construction rules, diagnostics, extension points, external-library subset, and compile-positive/negative fixtures in `NodalRegisterFactory-DG-v0.1` before implementation.
    - [ ] **F-116.G.2** Retain the applicable evidence, capability limits and reproduction/demonstration record for 116; complete review, verified integration and any separate closure under the centralized Foundation acceptance rules.

- [ ] **Increment 117 — Canonical Register IR, source maps, verifier, and ABI manifest**
  - [ ] **F-117.A — Architecture, scope and extensibility.** Keep canonical Register IR, physical instances, access policy and transport capability separate.
  - [ ] **F-117.B — Implementation and integration**
    - [ ] **F-117.B.1** Implement target-neutral Register IR for blocks, registers, fields, hierarchy, geometry, policies, side effects, hardware bindings, domains, accesses, and transport capabilities.
    - [ ] **F-117.B.2** Add deterministic IDs/source locations, canonical JSON, semantic hashing, overlap/alignment/address-width/reserved-region checks, parameter-envelope verification, and ABI lock/diff classification.
  - [ ] **F-117.C — Correctness, rejection and predecessor regression.** Test overlap/alignment/reserved-region/address-width errors and parameter-envelope ABI mismatches.
  - [ ] **F-117.D — Independent validation and applicability**
    - [ ] **F-117.D.1** Representation evidence: Validate actual serialized/native IR and manifests against independently specified semantic expectations and malformed-input controls; compiler parse/print alone is not generated-HDL behavioral proof.
    - [ ] **F-117.D.2** Consumer qualification boundary: Retain source-correlated witnesses for the already assigned lowering/tool consumers and identify their later execution obligations without a reverse dependency. Do not claim unimplemented target behavior here.
  - [ ] **F-117.E — Optimization review and output quality.** Review repeated map expansion and semantic hashing without flattening away source/policy identity. Apply the proportionate review rule above; document no new optimization required when justified.
  - [ ] **F-117.F — Scale, determinism and compatibility.** Exercise large maps, nested arrays and ABI-version comparisons with deterministic IDs.
  - [ ] **F-117.G — Evidence, documentation and acceptance.** Retain the applicable evidence, capability limits and reproduction/demonstration record for 117; complete review, verified integration and any separate closure under the centralized Foundation acceptance rules.

- [ ] **Increment 118 — SystemRDL 2.0 and Nodal YAML/JSON frontends**
  - [ ] **F-118.A — Architecture, scope and extensibility.** Normalize safe versioned imports and the supported SystemRDL subset into 117 canonical IR, not independent status or semantics.
  - [ ] **F-118.B — Implementation and integration**
    - [ ] **F-118.B.1** Implement safe versioned `nodal-registers/v1` YAML/JSON parsing with deterministic explicit imports, controlled roots, cycle detection, schema migration diagnostics, and source spans; prohibit arbitrary tags, executable templates, and order-dependent semantics.
    - [ ] **F-118.B.2** Implement the supported SystemRDL 2.0 import subset and deterministic export, diagnose unsupported/lossy mappings, and prove equivalent Scala/SystemRDL/YAML definitions normalize to equivalent Register IR and ABI hashes.
  - [ ] **F-118.C — Correctness, rejection and predecessor regression.** Test import cycles/path escapes, executable tags/templates, unsupported/lossy constructs and equivalent authored maps.
  - [ ] **F-118.D — Independent validation and applicability**
    - [ ] **F-118.D.1** Independent artifact consumers: Validate actual emitted manifests/wrappers/software or interchange artifacts using applicable pinned parsers/consumers and independently specified identity/ABI expectations; record each format capability and loss limit.
    - [ ] **F-118.D.2** Generated HDL applicability: Where a generated HDL wrapper affects behavior, retain separate applicable syntax, simulation and synthesizable-digital structure/equivalence checks through the existing tool owners. Metadata-only outputs and unimplemented dependent generators receive no HDL-test credit.
  - [ ] **F-118.E — Optimization review and output quality.** Review parsing/canonicalization duplication without making input ordering semantic. Apply the proportionate review rule above; document no new optimization required when justified.
  - [ ] **F-118.F — Scale, determinism and compatibility.** Exercise large imported maps and supported schema versions with identical canonical ABI hashes.
  - [ ] **F-118.G — Evidence, documentation and acceptance.** Retain the applicable evidence, capability limits and reproduction/demonstration record for 118; complete review, verified integration and any separate closure under the centralized Foundation acceptance rules.

- [ ] **Increment 119 — Canonical access endpoint and APB3/APB4 adapter**
  - [ ] **F-119.A — Architecture, scope and extensibility.** Bind one physical bank/domain to an exactly-once committed endpoint and APB setup/access semantics.
  - [ ] **F-119.B — Implementation and integration**
    - [ ] **F-119.B.1** Implement register storage/update semantics, relative-address decode, read muxes, byte enables, errors, side effects, reset behavior, and the exactly-once committed request/response endpoint.
    - [ ] **F-119.B.2** Implement APB3/APB4 setup/access, wait-state, strobe/protection/error handling with open-source simulation, formal protocol/semantic checks, lint, and synthesis evidence.
  - [ ] **F-119.C — Correctness, rejection and predecessor regression.** Test waits, strobes, errors, reset and simultaneous software/hardware updates; detect duplicate or premature commits.
  - [ ] **F-119.D — Independent validation and applicability**
    - [ ] **F-119.D.1** Syntax and elaboration: Use the pinned 66 Verilator/Icarus lanes on actual generated HDL in the selected language/profile; record source/output identities, commands and capability limits.
    - [ ] **F-119.D.2** Behavioral simulation: Simulate the selected boundary and predecessor-interaction cases against declared reference behavior, retaining seeds, reset/stimulus sequences and waveforms.
    - [ ] **F-119.D.3** Synthesis and structure: Use 67 Yosys checks for the synthesizable digital artifacts, retaining hierarchy, cell/memory/driver and parameter results.
    - [ ] **F-119.D.4** Equivalence and formal applicability: Record distinct equivalence/property applicability and execute every required transformation, safety or protocol check through 67; retain assumptions, bounds and counterexamples rather than using simulation as proof.
  - [ ] **F-119.E — Optimization review and output quality.** Review decode/read-mux structure without changing byte enables, side effects or collision policy. Apply the proportionate review rule above; document no new optimization required when justified.
  - [ ] **F-119.F — Scale, determinism and compatibility.** Exercise large maps and sustained/waiting transfers with deterministic RTL and endpoint identities.
  - [ ] **F-119.G — Evidence, documentation and acceptance.** Retain the applicable evidence, capability limits and reproduction/demonstration record for 119; complete review, verified integration and any separate closure under the centralized Foundation acceptance rules.

- [ ] **Increment 120 — AXI4-Lite and custom transport conformance**
  - [ ] **F-120.A — Architecture, scope and extensibility.** Pair AXI address/data explicitly and keep custom transports behind the committed-access contract; require explicit multi-access arbitration.
  - [ ] **F-120.B — Implementation and integration**
    - [ ] **F-120.B.1** Implement AXI4-Lite address/data buffering and pairing, backpressure, strobes, protection, responses, ordering, reset, and declared outstanding policy without committing incomplete transactions.
    - [ ] **F-120.B.2** Freeze and implement the public custom-transport capability/conformance contract and an explicit multi-access arbiter/router; reject implicit dual attachment.
  - [ ] **F-120.C — Correctness, rejection and predecessor regression.** Test independent channel backpressure, ordering/reset and incomplete transactions; reject implicit dual attachment.
  - [ ] **F-120.D — Independent validation and applicability**
    - [ ] **F-120.D.1** Syntax and elaboration: Use the pinned 66 Verilator/Icarus lanes on actual generated HDL in the selected language/profile; record source/output identities, commands and capability limits.
    - [ ] **F-120.D.2** Behavioral simulation: Simulate the selected boundary and predecessor-interaction cases against declared reference behavior, retaining seeds, reset/stimulus sequences and waveforms.
    - [ ] **F-120.D.3** Synthesis and structure: Use 67 Yosys checks for the synthesizable digital artifacts, retaining hierarchy, cell/memory/driver and parameter results.
    - [ ] **F-120.D.4** Equivalence and formal applicability: Record distinct equivalence/property applicability and execute every required transformation, safety or protocol check through 67; retain assumptions, bounds and counterexamples rather than using simulation as proof.
  - [ ] **F-120.E — Optimization review and output quality.** Review queues/arbitration only within the declared outstanding and fairness/ordering contract. Apply the proportionate review rule above; document no new optimization required when justified.
  - [ ] **F-120.F — Scale, determinism and compatibility.** Exercise buffer limits, many transport instances and repeatable randomized transactions.
  - [ ] **F-120.G — Evidence, documentation and acceptance.** Retain the applicable evidence, capability limits and reproduction/demonstration record for 120; complete review, verified integration and any separate closure under the centralized Foundation acceptance rules.

- [ ] **Increment 121 — Portable Verilog register lowering and parameterized geometry**
  - [ ] **F-121.A — Architecture, scope and extensibility.** Preserve fixed register ABI as non-overridable localparams and reserve parameters for explicit architecture variability.
  - [ ] **F-121.B — Implementation and integration**
    - [ ] **F-121.B.1** Emit deterministic relative-offset decode, width-safe named `localparam`s, masks, reset/access constants, storage, side effects, and readable hierarchy; never expose fixed offsets as externally overridable parameters.
    - [ ] **F-121.B.2** Emit HDL parameters only for explicit Nodal symbolic configuration, support an explicit optional absolute-base wrapper, validate repeated/parameterized maps over declared envelopes, and prove RTL/artifact configuration consistency.
  - [ ] **F-121.C — Correctness, rejection and predecessor regression.** Test relative decode, optional absolute wrappers, geometry envelopes and software/RTL configuration mismatches.
  - [ ] **F-121.D — Independent validation and applicability**
    - [ ] **F-121.D.1** Syntax and elaboration: Use the pinned 66 Verilator/Icarus lanes on actual generated HDL in the selected language/profile; record source/output identities, commands and capability limits.
    - [ ] **F-121.D.2** Behavioral simulation: Simulate the selected boundary and predecessor-interaction cases against declared reference behavior, retaining seeds, reset/stimulus sequences and waveforms.
    - [ ] **F-121.D.3** Synthesis and structure: Use 67 Yosys checks for the synthesizable digital artifacts, retaining hierarchy, cell/memory/driver and parameter results.
    - [ ] **F-121.D.4** Equivalence and formal applicability: Record distinct equivalence/property applicability and execute every required transformation, safety or protocol check through 67; retain assumptions, bounds and counterexamples rather than using simulation as proof.
  - [ ] **F-121.E — Optimization review and output quality.** Review masks, read muxes and duplicated constants without exposing fixed offsets as parameters. Apply the proportionate review rule above; document no new optimization required when justified.
  - [ ] **F-121.F — Scale, determinism and compatibility.** Exercise large repeated maps and non-default configurations with stable RTL/artifact identities.
  - [ ] **F-121.G — Evidence, documentation and acceptance.** Retain the applicable evidence, capability limits and reproduction/demonstration record for 121; complete review, verified integration and any separate closure under the centralized Foundation acceptance rules.

- [ ] **Increment 122 — Artifact generators, IP-XACT, and software ABI flows**
  - [ ] **F-122.A — Architecture, scope and extensibility.** Generate all views from one canonical Register IR and resolved configuration; do not create competing authoring sources.
  - [ ] **F-122.B — Implementation and integration**
    - [ ] **F-122.B.1** Generate canonical JSON/ABI hashes, C/C++ headers, Rust metadata or PAC input, CMSIS-SVD, UVM RAL/RALF, Markdown/HTML, SystemRDL, and IEEE 1685-2022 IP-XACT register/memory-map views.
    - [ ] **F-122.B.2** Preserve stable identities, source provenance, semantic hashes, resolved parameter configuration, and explicit loss diagnostics in every artifact; add cross-artifact equivalence and compatibility-report tests.
  - [ ] **F-122.C — Correctness, rejection and predecessor regression.** Test lossy export, width/signedness/offset differences and cross-artifact ABI mismatch with independent consumers where supported.
  - [ ] **F-122.D — Independent validation and applicability**
    - [ ] **F-122.D.1** Independent artifact consumers: Validate actual emitted manifests/wrappers/software or interchange artifacts using applicable pinned parsers/consumers and independently specified identity/ABI expectations; record each format capability and loss limit.
    - [ ] **F-122.D.2** Generated HDL applicability: Where a generated HDL wrapper affects behavior, retain separate applicable syntax, simulation and synthesizable-digital structure/equivalence checks through the existing tool owners. Metadata-only outputs and unimplemented dependent generators receive no HDL-test credit.
  - [ ] **F-122.E — Optimization review and output quality.** Review redundant symbols and rendering cost while preserving width-safe metadata and provenance. Apply the proportionate review rule above; document no new optimization required when justified.
  - [ ] **F-122.F — Scale, determinism and compatibility.** Exercise large maps and supported output schema/tool versions with deterministic artifacts.
  - [ ] **F-122.G — Evidence, documentation and acceptance.** Retain the applicable evidence, capability limits and reproduction/demonstration record for 122; complete review, verified integration and any separate closure under the centralized Foundation acceptance rules.

- [ ] **Increment 123 — Register factory verification, scale, and reusable adapter/library qualification**
  - [ ] **F-123.A — Architecture, scope and extensibility.** Qualify register semantics and custom adapters using the already implemented canonical endpoint and profile boundaries.
  - [ ] **F-123.B — Implementation and integration**
    - [ ] **F-123.B.1** Generate semantic verification for reset/access/side-effect/collision/byte-enable/multiword/array/hierarchy/snapshot/commit/illegal-access behavior while keeping concurrent/temporal properties verification-only under Increment 114.
    - [ ] **F-123.B.2** Add large-map performance, deterministic output, Verilator/Icarus, Yosys quality/equivalence, custom-adapter conformance, and one external reusable register-map qualification using only public contracts.
  - [ ] **F-123.C — Correctness, rejection and predecessor regression.** Combine reset/access/side-effect/collision/byte-enable/snapshot/commit cases with temporal-checker synthesis exclusion.
  - [ ] **F-123.D — Independent validation and applicability**
    - [ ] **F-123.D.1** Syntax and elaboration: Use the pinned 66 Verilator/Icarus lanes on actual generated HDL in the selected language/profile; record source/output identities, commands and capability limits.
    - [ ] **F-123.D.2** Behavioral simulation: Simulate the selected boundary and predecessor-interaction cases against declared reference behavior, retaining seeds, reset/stimulus sequences and waveforms.
    - [ ] **F-123.D.3** Synthesis and structure: Use 67 Yosys checks for the synthesizable digital artifacts, retaining hierarchy, cell/memory/driver and parameter results.
    - [ ] **F-123.D.4** Equivalence and formal applicability: Record distinct equivalence/property applicability and execute every required transformation, safety or protocol check through 67; retain assumptions, bounds and counterexamples rather than using simulation as proof.
  - [ ] **F-123.E — Optimization review and output quality.** Review register RTL quality and verification generation without merging distinct physical-bank state. Apply the proportionate review rule above; document no new optimization required when justified.
  - [ ] **F-123.F — Scale, determinism and compatibility.** Exercise large hierarchical maps and external public-only register libraries with measured resources and stable output.
  - [ ] **F-123.G — Evidence, documentation and acceptance**
    - [ ] **F-123.G.1** Publish user, adapter-author, SystemRDL/YAML migration, artifact, and SoC-integration documentation.
    - [ ] **F-123.G.2** Retain the applicable evidence, capability limits and reproduction/demonstration record for 123; complete review, verified integration and any separate closure under the centralized Foundation acceptance rules.

## Phase 8 — Cross-cutting Interface, Role, digital inout, and AMS connectivity closure

This independently schedulable phase closes the cross-layer architecture accepted by ADR 0021. It does not replace the foundational implementation assigned to Increments 14-22, 54-77, and 99; it integrates and qualifies those pieces as one public connectivity system.

- [x] **Increment 124 — Interface, Role, AMS, and digital inout architecture roadmap contract**
  - Accept [ADR 0021](../architecture/0021-unified-struct-interface-role-and-inout-architecture.md), the staged [`interface-role-inout-ams-v0.1-plan.md`](interface-role-inout-ams-v0.1-plan.md), and the machine-readable [`interface-role-inout-ams-v0.1-surface.json`](interface-role-inout-ams-v0.1-surface.json).
  - Freeze the semantic separation among directionless storable `Struct`, non-storable connectivity `Interface`, named `Role`, digital resolved inout, conservative AMS terminals, directional analog signal-flow values, and explicit mixed-signal bridges.
  - Record `master`/`slave` as convenience roles over a generic role model, `Valid` as the canonical valid-only protocol, monitor read-only access, and explicit non-invertible AMS/shared roles.
  - Record first-class digital inout read/drive/high-impedance/resolution, push-pull/open-drain, black-box/hierarchy/pad use, split internal tri-state carriers, and capability-checked internal resolution with no silent mux rewrite.
  - Record one logical Interface ABI with deterministic portable Verilog/Verilog-A/Verilog-AMS flattening and future native SystemVerilog interface/modport parity. Keep exact public API and implementation assigned to Increment 14/15 and later implementation increments.
  - Evidence: [`0021-unified-struct-interface-role-and-inout-architecture.md`](../architecture/0021-unified-struct-interface-role-and-inout-architecture.md), [`interface-role-inout-ams-v0.1-plan.md`](interface-role-inout-ams-v0.1-plan.md), and [`interface-role-inout-ams-v0.1-surface.json`](interface-role-inout-ams-v0.1-surface.json).
  - [x] **F-124.H — Historical acceptance breakdown: Interface/role/inout architecture contract.** Retain ADR 0021 and the linked plan/surface, without turning an architecture agreement into implementation of later interface consumers.

- [ ] **Increment 125 — Canonical Interface IR, role expansion, source maps, and ABI manifest**
  - [ ] **F-125.A — Architecture, scope and extensibility.** Use one logical Interface ABI and explicit role expansion through construction, MLIR and bridge ownership.
  - [ ] **F-125.B — Implementation and integration**
    - [ ] **F-125.B.1** Implement interface/role definitions, member identity, recursive role expansion, exact connection compatibility, interface storage prohibition, parameterized member paths, source maps, diagnostics, canonical manifests, ABI hashes, and compatibility classification.
    - [ ] **F-125.B.2** Integrate with construction close, Nodal MLIR, cross-layer diagnostics, plugin metadata, caches, and deterministic parse/print.
  - [ ] **F-125.C — Correctness, rejection and predecessor regression.** Test role incompleteness, illegal interface storage, connection mismatch and mutation of source/ABI metadata.
  - [ ] **F-125.D — Independent validation and applicability**
    - [ ] **F-125.D.1** Representation evidence: Validate actual serialized/native IR and manifests against independently specified semantic expectations and malformed-input controls; compiler parse/print alone is not generated-HDL behavioral proof.
    - [ ] **F-125.D.2** Consumer qualification boundary: Retain source-correlated witnesses for the already assigned lowering/tool consumers and identify their later execution obligations without a reverse dependency. Do not claim unimplemented target behavior here.
  - [ ] **F-125.E — Optimization review and output quality.** Review repeated role expansion and source-map growth without using flattened names as semantic identity. Apply the proportionate review rule above; document no new optimization required when justified.
  - [ ] **F-125.F — Scale, determinism and compatibility.** Exercise nested interfaces and symbolic member arrays with deterministic ABI compatibility diffs.
  - [ ] **F-125.G — Evidence, documentation and acceptance.** Retain the applicable evidence, capability limits and reproduction/demonstration record for 125; complete review, verified integration and any separate closure under the centralized Foundation acceptance rules.

- [ ] **Increment 126 — Digital Struct/Interface/Role and full Valid/Stream implementation**
  - [ ] **F-126.A — Architecture, scope and extensibility.** Keep Struct storage, Interface connectivity, roles and full Valid/Stream transfer ownership distinct.
  - [ ] **F-126.B — Implementation and integration.** Implement directionless `Struct`, nested digital `Interface`, named roles, legal complementary-role derivation, monitor views, plain/`Valid`/`Stream`, transfer/stall/bubble semantics, exact connection, typed adapters, domain provenance, hierarchy propagation, and external protocol-interface conformance.
  - [ ] **F-126.C — Correctness, rejection and predecessor regression.** Test complementary/monitor roles, exact adapters, stalls/bubbles and illegal domain or shape connections.
  - [ ] **F-126.D — Independent validation and applicability**
    - [ ] **F-126.D.1** Syntax and elaboration: Use the pinned 66 Verilator/Icarus lanes on actual generated HDL in the selected language/profile; record source/output identities, commands and capability limits.
    - [ ] **F-126.D.2** Behavioral simulation: Simulate the selected boundary and predecessor-interaction cases against declared reference behavior, retaining seeds, reset/stimulus sequences and waveforms.
    - [ ] **F-126.D.3** Synthesis and structure: Use 67 Yosys checks for the synthesizable digital artifacts, retaining hierarchy, cell/memory/driver and parameter results.
    - [ ] **F-126.D.4** Equivalence and formal applicability: Record distinct equivalence/property applicability and execute every required transformation, safety or protocol check through 67; retain assumptions, bounds and counterexamples rather than using simulation as proof.
  - [ ] **F-126.E — Optimization review and output quality.** Review protocol/adaptor duplication without changing transfer/latency semantics. Apply the proportionate review rule above; document no new optimization required when justified.
  - [ ] **F-126.F — Scale, determinism and compatibility.** Exercise deeply nested digital interfaces and public external protocol consumers with stable member paths.
  - [ ] **F-126.G — Evidence, documentation and acceptance.** Retain the applicable evidence, capability limits and reproduction/demonstration record for 126; complete review, verified integration and any separate closure under the centralized Foundation acceptance rules.

- [ ] **Increment 127 — Digital inout, resolved nets, tri-state, open-drain, pads, and black-box hierarchy**
  - [ ] **F-127.A — Architecture, scope and extensibility.** Represent resolved-net drivers and 0/1/Z/X semantics explicitly, including pad and black-box hierarchy boundaries.
  - [ ] **F-127.B — Implementation and integration**
    - [ ] **F-127.B.1** Implement typed read/drive endpoints, driver states/enables, `0/1/Z/X` resolution, push-pull/open-drain/open-source modes, readback, pull/pad metadata, hierarchy pass-through, black-box connectivity, and split-tristate boundary adapters.
    - [ ] **F-127.B.2** Add profile-aware internal tri-state restrictions, contention diagnostics/properties, Verilator/Icarus tests, Yosys synthesis/equivalence where supported, and negative fixtures.
  - [ ] **F-127.C — Correctness, rejection and predecessor regression.** Test contention, readback, open-drain restrictions, split-tristate parity and unsupported internal resolution.
  - [ ] **F-127.D — Independent validation and applicability**
    - [ ] **F-127.D.1** Syntax and behavioral resolution: Run independent parse/elaboration and event-driven 0/1/Z/X/readback/contention simulations on actual generated resolved-net fixtures; distinguish each simulator capability.
    - [ ] **F-127.D.2** Synthesis capability and structure: Use pinned Yosys only for the declared top-level/black-box or split-tristate synthesis profile; execute rejection of unsupported internal resolution rather than silently mux-lowering it.
    - [ ] **F-127.D.3** Boundary equivalence and properties: Execute applicable split/native boundary equivalence and driver-exclusivity properties with explicit environmental assumptions and retained traces.
  - [ ] **F-127.E — Optimization review and output quality.** Review redundant drive/read wrappers without replacing unsupported resolution with mux semantics. Apply the proportionate review rule above; document no new optimization required when justified.
  - [ ] **F-127.F — Scale, determinism and compatibility.** Exercise many drivers, hierarchy depth and vector widths across supported tool profiles.
  - [ ] **F-127.G — Evidence, documentation and acceptance.** Retain the applicable evidence, capability limits and reproduction/demonstration record for 127; complete review, verified integration and any separate closure under the centralized Foundation acceptance rules.

- [ ] **Increment 128 — Conservative AMS terminals, signal-flow values, and mixed-signal roles**
  - [ ] **F-128.A — Architecture, scope and extensibility.** Keep conservative terminals, directional analog values and mixed bridge endpoints in distinct typed ownership classes.
  - [ ] **F-128.B — Implementation and integration.** Implement boundary `Terminal`, internal `Node`, `Branch`, discipline/nature/dimension checks, connect/sense/contribute/monitor access, directional analog signal-flow values, mixed interfaces, bridge endpoints, continuous-time island graphs, and no-implicit-conversion verification.
  - [ ] **F-128.C — Correctness, rejection and predecessor regression.** Test discipline/dimension/access mismatch, missing bridges and illegal implicit conversions with hierarchy.
  - [ ] **F-128.D — Independent validation and applicability**
    - [ ] **F-128.D.1** Independent model compilation: Compile actual generated supported models using 48 and qualified 75 profiles where needed; retain exact capability and artifact identities.
    - [ ] **F-128.D.2** Numerical reference validation: Execute the relevant DC/AC/transient/noise/event cases with declared references, tolerances, parameter/environment conditions and state/topology assumptions. Unsupported analyses are not successful runs.
    - [ ] **F-128.D.3** Transformation correspondence: Retain independent mathematical/invariant checks and applicable before/after numerical comparisons for introduced transformations; waveform agreement is not a formal proof of unsupported structural changes.
  - [ ] **F-128.E — Optimization review and output quality.** Review repeated topology/island construction without changing conservation or branch orientation. Apply the proportionate review rule above; document no new optimization required when justified.
  - [ ] **F-128.F — Scale, determinism and compatibility.** Exercise mixed nested interfaces and repeated terminal groups with deterministic topology IDs.
  - [ ] **F-128.G — Evidence, documentation and acceptance.** Retain the applicable evidence, capability limits and reproduction/demonstration record for 128; complete review, verified integration and any separate closure under the centralized Foundation acceptance rules.

- [ ] **Increment 129 — Flattened Verilog, Verilog-A, and Verilog-AMS interface lowering**
  - [ ] **F-129.A — Architecture, scope and extensibility.** Map the logical Interface ABI to profile-specific flattened ports/terminals without redefining ownership.
  - [ ] **F-129.B — Implementation and integration**
    - [ ] **F-129.B.1** Emit deterministic flattened names/ports/terminals for nested interfaces, protocols, shaped payloads, resolved inouts, conservative terminals, signal-flow values, and bridges.
    - [ ] **F-129.B.2** Generate wrappers, Interface ABI/source-map manifests, profile diagnostics, and exact golden fixtures.
  - [ ] **F-129.C — Correctness, rejection and predecessor regression.** Test naming collisions, shaped/signed payloads, resolved inouts and conservative bridge paths across profiles.
  - [ ] **F-129.D — Independent validation and applicability**
    - [ ] **F-129.D.1** Generated output matrix: Retain actual artifacts per affected backend and execute the applicable independent syntax/elaboration, behavioral/numerical and synthesizable-digital structure lanes from 48-49/66-67/75, each recorded separately.
    - [ ] **F-129.D.2** Transformation validation: Execute applicable equivalence/formal or numerical/invariant validation for every introduced transformation, with preserved parameter/state/domain/source metadata and explicit capability limits.
  - [ ] **F-129.E — Optimization review and output quality.** Review avoidable wrapper/materialization duplication and preserve readable deterministic source correlation. Apply the proportionate review rule above; document no new optimization required when justified.
  - [ ] **F-129.F — Scale, determinism and compatibility.** Exercise large nested interface arrays and parameter overrides with stable flat ABI manifests.
  - [ ] **F-129.G — Evidence, documentation and acceptance.** Retain the applicable evidence, capability limits and reproduction/demonstration record for 129; complete review, verified integration and any separate closure under the centralized Foundation acceptance rules.

- [ ] **Increment 130 — Native SystemVerilog interface/modport backend and wrapper parity**
  - [ ] **F-130.A — Architecture, scope and extensibility**
    - [ ] **F-130.A.1** After Increment 99 approval, emit native interfaces/modports, nested interfaces, parameters, monitor roles, inout nets, and per-instance flatten overrides.
    - [ ] **F-130.A.2** Require Increment 99 approval and preserve native/flat logical ABI, compile-order and per-instance override contracts.
  - [ ] **F-130.B — Implementation and integration.** Generate native/flat wrappers and prove logical ABI, simulation, synthesis, compile-order, and source-map parity across supported tool profiles.
  - [ ] **F-130.C — Correctness, rejection and predecessor regression.** Test role/modport misuse, wrapper mismatch, signed/shape layouts and unsupported native tool features.
  - [ ] **F-130.D — Independent validation and applicability**
    - [ ] **F-130.D.1** Native and flat compilation: Independently parse/elaborate actual generated native SystemVerilog and portable flat wrappers under the approved 99 capability matrix, retaining compile order and parameters.
    - [ ] **F-130.D.2** Behavioral parity: Simulate native/flat interfaces against common logical transactions, including roles, resolved nets and signed shaped payloads.
    - [ ] **F-130.D.3** Synthesis and equivalence: Run synthesis/structure and applicable native/flat equivalence on supported tool profiles; reject unsupported native constructs rather than losing the portable ABI.
  - [ ] **F-130.E — Optimization review and output quality.** Review native versus flat wrapper cost without sacrificing portable path parity. Apply the proportionate review rule above; document no new optimization required when justified.
  - [ ] **F-130.F — Scale, determinism and compatibility.** Exercise nested parameterized interfaces and supported tool versions with deterministic manifests.
  - [ ] **F-130.G — Evidence, documentation and acceptance.** Retain the applicable evidence, capability limits and reproduction/demonstration record for 130; complete review, verified integration and any separate closure under the centralized Foundation acceptance rules.

- [ ] **Increment 131 — Interface metadata, verification agents, scale, and external qualification**
  - [ ] **F-131.A — Architecture, scope and extensibility.** Derive simulation/interoperability/documentation metadata only from logical Interface ABI; retain dependent implementation boundaries.
  - [ ] **F-131.B — Implementation and integration**
    - [ ] **F-131.B.1** Generate Scala simulation, cocotb, UVM/UVM-MS, waveform, IP-XACT, and documentation metadata from the logical Interface ABI.
    - [ ] **F-131.B.2** Add role/inout/AMS checkers, large nested-interface performance, parameter matrices, deterministic output, compatibility diff, and external reusable interface/library qualification.
  - [ ] **F-131.C — Correctness, rejection and predecessor regression.** Test identity/access mismatch, checker violations and unsupported consumer capabilities without claiming a complete UVM/HVL runtime.
  - [ ] **F-131.D — Independent validation and applicability**
    - [ ] **F-131.D.1** Independent artifact consumers: Validate actual emitted manifests/wrappers/software or interchange artifacts using applicable pinned parsers/consumers and independently specified identity/ABI expectations; record each format capability and loss limit.
    - [ ] **F-131.D.2** Generated HDL applicability: Where a generated HDL wrapper affects behavior, retain separate applicable syntax, simulation and synthesizable-digital structure/equivalence checks through the existing tool owners. Metadata-only outputs and unimplemented dependent generators receive no HDL-test credit.
  - [ ] **F-131.E — Optimization review and output quality.** Review metadata duplication and consumer generation overhead without changing interface semantics. Apply the proportionate review rule above; document no new optimization required when justified.
  - [ ] **F-131.F — Scale, determinism and compatibility.** Exercise large interfaces, parameter matrices and external public-only consumers with compatibility diffs.
  - [ ] **F-131.G — Evidence, documentation and acceptance.** Retain the applicable evidence, capability limits and reproduction/demonstration record for 131; complete review, verified integration and any separate closure under the centralized Foundation acceptance rules.

## Phase 9 — Continuous-time equation, hybrid DAE, solver, and analog-model qualification closure

This independently schedulable phase closes the cross-layer continuous-time architecture accepted by ADR 0022. It does not replace the source-language, validation, mixed-signal, interface, or backend work in Increments 24-53, 68-78, and 128-129. It gives those increments one solver-independent semantic, mathematical, analysis, and evidence architecture.

- [x] **Increment 132 — Continuous-time equation, hybrid DAE, and solver architecture roadmap contract**
  - Accept [ADR 0022](../architecture/0022-layered-continuous-time-hybrid-dae-architecture.md), the staged [`continuous-time-ams-v0.1-plan.md`](continuous-time-ams-v0.1-plan.md), and the machine-readable [`continuous-time-ams-v0.1-surface.json`](continuous-time-ams-v0.1-surface.json).
  - Record distinct source-semantic analog IR, topology graph, hybrid equation-system IR, analysis projections, and target/solver representations.
  - Record explicit `AnalogIsland`, stable equation/unknown/state/event/noise identities, DAE structural verification, state and initialization ownership, hybrid event ordering, analysis/noise/environment contracts, solver capability negotiation, model validity envelopes, and retained evidence.
  - Keep exact public syntax, compiler implementation, solver behavior, and target lowering assigned to Increments 133-142 and the existing analog/AMS increments.
  - Evidence: [`0022-layered-continuous-time-hybrid-dae-architecture.md`](../architecture/0022-layered-continuous-time-hybrid-dae-architecture.md), [`continuous-time-ams-v0.1-plan.md`](continuous-time-ams-v0.1-plan.md), and [`continuous-time-ams-v0.1-surface.json`](continuous-time-ams-v0.1-surface.json).
  - [x] **F-132.H — Historical acceptance breakdown: Layered continuous-time architecture contract.** Retain ADR 0022 and the linked semantic/solver architecture plan; solver behavior and lowering remained separately assigned.

- [x] **Increment 133 — Analog semantic API and analysis contract design gate**
  - Schedule an equation/component API checkpoint ahead of Increment 32 while retaining the complete continuous-time API gate in this increment.
  - Compile and compare public candidates for unordered equations, contributions, procedural-assignment distinction, conservative terminals/branches/connections, partial/concrete component balance, structural parameters, initial equations, explicit analog state and reinitialization, event tolerance and discontinuity declarations, analysis context, environment/PVT access, noise identity/correlation, validity envelopes, and solver-hint metadata.
  - Publish `NodalEquationComponentApi-DG-v0.1.md`, a machine-readable checkpoint surface, migration notes, stable diagnostics, compile-positive/negative fixtures, and one external reusable physical-component fixture. Increment 32 may start only after this checkpoint is accepted.
  - Publish the complete `NodalContinuousTimeApi-DG-v0.1.md` and machine-readable public surface before closing Increment 133.
  - Keep frontend, equation normalization, solver, and backend behavior inert throughout the design gate.
  - Evidence: approved `NodalEquationComponentApi-DG-v0.1` and `NodalContinuousTimeApi-DG-v0.1`; implementation PR #91 (superseding draft PR #90); exact accepted head `a9c236384b32140d1b0a213cfbdb5c5512baab24`; dedicated run `33262841010`; accepted Core CI run `33262188693`; squash merge `4ca5d230bc5d3e3f985b9b4ed24386c69f74b539`; post-merge Core CI run `33263135167`.
  - [x] **F-133.H — Historical acceptance breakdown: Equation/component checkpoint and continuous-time gate.** Retain both approved design gates and the exact accepted head/run/merge identities below; equation normalization, solver and backend execution were inert.

- [ ] **Increment 134 — Source-semantic analog IR, `AnalogIsland`, and stable identities**
  - [ ] **F-134.A — Architecture, scope and extensibility**
    - [ ] **F-134.A.1** Preserve authored equation left/right expressions separately from later residual form and retain terminal, branch, connection-set, component, partial/concrete, local-balance, structural-parameter, hierarchy, dimension, guard, analysis, and source-span metadata.
    - [ ] **F-134.A.2** Represent source equations, contributions, procedural operations and connection sets distinctly in stable AnalogIslands.
  - [ ] **F-134.B — Implementation and integration**
    - [ ] **F-134.B.1** Implement distinct source-semantic operations for equations, contributions, procedural assignments, connections, operators, events, analyses, noise, environment, and solver hints.
    - [ ] **F-134.B.2** Build deterministic islands and stable IDs for topology objects, components, unknowns, equations, contributions, state, events, noise, bridges, and analyses.
  - [ ] **F-134.C — Correctness, rejection and predecessor regression**
    - [ ] **F-134.C.1** Add normalized parse/print, source maps, parameter formulas/envelopes, mutation tests, local semantic inventories, and semantic manifests.
    - [ ] **F-134.C.2** Mutate missing dimensions/ownership/source IDs and combine state, events and structural parameters.
  - [ ] **F-134.D — Independent validation and applicability**
    - [ ] **F-134.D.1** Representation evidence: Validate actual serialized/native IR and manifests against independently specified semantic expectations and malformed-input controls; compiler parse/print alone is not generated-HDL behavioral proof.
    - [ ] **F-134.D.2** Consumer qualification boundary: Retain source-correlated witnesses for the already assigned lowering/tool consumers and identify their later execution obligations without a reverse dependency. Do not claim unimplemented target behavior here.
  - [ ] **F-134.E — Optimization review and output quality.** Review duplicated graph metadata and repeated island construction without erasing authored equations. Apply the proportionate review rule above; document no new optimization required when justified.
  - [ ] **F-134.F — Scale, determinism and compatibility.** Exercise many islands, repeated hierarchy and large source maps with deterministic parse/print and IDs.
  - [ ] **F-134.G — Evidence, documentation and acceptance.** Retain the applicable evidence, capability limits and reproduction/demonstration record for 134; complete review, verified integration and any separate closure under the centralized Foundation acceptance rules.

- [ ] **Increment 135 — Topology expansion, residual DAE construction, and structural verification**
  - [ ] **F-135.A — Architecture, scope and extensibility**
    - [ ] **F-135.A.1** Keep residual construction independent of target causal orientation; reject unsupported higher-index or variable-structure systems explicitly and do not perform unapproved index reduction.
    - [ ] **F-135.A.2** Build solver-neutral residual/structural analysis independently of target equation orientation and preserve component balance provenance.
  - [ ] **F-135.B — Implementation and integration**
    - [ ] **F-135.B.1** Build a logically flattened hierarchy/topology/equation view for analysis without requiring flattened target emission.
    - [ ] **F-135.B.2** Expand conservative connection sets, first-class equations, and contribution sets into solver-neutral residual systems while preserving authored equation, component, branch, orientation, and hierarchy provenance.
    - [ ] **F-135.B.3** Classify continuous, derivative, algebraic, discrete, parameter, structural-parameter, environment, independent, and input variables.
    - [ ] **F-135.B.4** Implement local component balance plus whole-island incidence/dependency graphs, structural matching, block decomposition, equation/unknown balance, references, conservation, singularity, algebraic loops, initialization structure, variable-topology classification, and parameter-envelope checks.
  - [ ] **F-135.C — Correctness, rejection and predecessor regression.** Use reference incidence/matching cases and mutations for imbalance, singularity, initialization and unsupported higher-index/variable topology.
  - [ ] **F-135.D — Independent validation and applicability**
    - [ ] **F-135.D.1** Independent mathematical reference: Compare actual normalized models, coefficients, formats, schedules or analysis results with independently constructed references and declared numerical tolerances/assumptions; retain units, envelopes and commands.
    - [ ] **F-135.D.2** Downstream target boundary: Preserve witnesses and configuration identity for the existing RTL/solver-facing qualification owners. These software/mathematical results are neither HDL synthesis evidence nor a general numerical/formal equivalence proof.
  - [ ] **F-135.E — Optimization review and output quality.** Review sparse graph growth and repeated structural passes; no unapproved index reduction or unsafe division. Apply the proportionate review rule above; document no new optimization required when justified.
  - [ ] **F-135.F — Scale, determinism and compatibility.** Exercise large sparse islands and structural parameter envelopes with deterministic matching/reports.
  - [ ] **F-135.G — Evidence, documentation and acceptance.** Retain the applicable evidence, capability limits and reproduction/demonstration record for 135; complete review, verified integration and any separate closure under the centralized Foundation acceptance rules.

- [ ] **Increment 136 — Continuous state, initialization, operators, and hidden-state reporting**
  - [ ] **F-136.A — Architecture, scope and extensibility.** Own state/initialization explicitly across operators and bridges; keep guesses, fixed values, equations and reinitialization distinct.
  - [ ] **F-136.B — Implementation and integration**
    - [ ] **F-136.B.1** Implement state ownership for `ddt`, `idt`, Laplace/Z-domain operators, delay, transition, slew, hysteresis, sample/hold, and bridge state.
    - [ ] **F-136.B.2** Implement fixed values, guesses, initial equations, steady-state conditions, operating-point-derived initialization, reinitialization/jumps, and conflict diagnostics.
    - [ ] **F-136.B.3** Generate state/initialization reports and reject accidental duplication, movement, or hidden backend state.
  - [ ] **F-136.C — Correctness, rejection and predecessor regression**
    - [ ] **F-136.C.1** Add semantics-preserving operator lowering and differential fixtures.
    - [ ] **F-136.C.2** Test initialization conflicts, shared versus independent state and accidental operator movement/duplication.
  - [ ] **F-136.D — Independent validation and applicability**
    - [ ] **F-136.D.1** Independent model compilation: Compile actual generated supported models using 48 and qualified 75 profiles where needed; retain exact capability and artifact identities.
    - [ ] **F-136.D.2** Numerical reference validation: Execute the relevant DC/AC/transient/noise/event cases with declared references, tolerances, parameter/environment conditions and state/topology assumptions. Unsupported analyses are not successful runs.
    - [ ] **F-136.D.3** Transformation correspondence: Retain independent mathematical/invariant checks and applicable before/after numerical comparisons for introduced transformations; waveform agreement is not a formal proof of unsupported structural changes.
  - [ ] **F-136.E — Optimization review and output quality.** Review state/materialization only with semantic and numerical validation of introduced lowerings. Apply the proportionate review rule above; document no new optimization required when justified.
  - [ ] **F-136.F — Scale, determinism and compatibility.** Exercise repeated stateful models and long initialization/state inventories with stable ownership reports.
  - [ ] **F-136.G — Evidence, documentation and acceptance.** Retain the applicable evidence, capability limits and reproduction/demonstration record for 136; complete review, verified integration and any separate closure under the centralized Foundation acceptance rules.

- [ ] **Increment 137 — Hybrid event scheduler, discontinuities, and mode-dependent topology**
  - [ ] **F-137.A — Architecture, scope and extensibility**
    - [ ] **F-137.A.1** Keep solver hints separate from mathematical behavior.
    - [ ] **F-137.A.2** Separate mathematical discontinuities from solver hints and specify hybrid event ordering and fixed-point iteration.
  - [ ] **F-137.B — Implementation and integration**
    - [ ] **F-137.B.1** Freeze and implement observable ordering for initialization, integration, root detection, timers, analog events, digital events, bridge updates, zero-time event iteration, restart, and finalization.
    - [ ] **F-137.B.2** Implement dynamic event tolerances, named events, interrupted transitions, event fixed-point convergence, zero-time oscillation diagnostics, and event dependency graphs.
    - [ ] **F-137.B.3** Classify hard discontinuities, smoothness, hysteresis, guarded equations, and explicitly supported topology modes.
  - [ ] **F-137.C — Correctness, rejection and predecessor regression.** Test simultaneous roots/timers/digital updates, interrupted transitions, zero-time oscillation and unsupported topology modes.
  - [ ] **F-137.D — Independent validation and applicability**
    - [ ] **F-137.D.1** Independent model compilation: Compile actual generated supported models using 48 and qualified 75 profiles where needed; retain exact capability and artifact identities.
    - [ ] **F-137.D.2** Numerical reference validation: Execute the relevant DC/AC/transient/noise/event cases with declared references, tolerances, parameter/environment conditions and state/topology assumptions. Unsupported analyses are not successful runs.
    - [ ] **F-137.D.3** Transformation correspondence: Retain independent mathematical/invariant checks and applicable before/after numerical comparisons for introduced transformations; waveform agreement is not a formal proof of unsupported structural changes.
  - [ ] **F-137.E — Optimization review and output quality.** Review event-queue/dependency cost without moving events or altering tolerances and restart semantics. Apply the proportionate review rule above; document no new optimization required when justified.
  - [ ] **F-137.F — Scale, determinism and compatibility.** Exercise dense event sets and repeated mode transitions with deterministic observable ordering.
  - [ ] **F-137.G — Evidence, documentation and acceptance.** Retain the applicable evidence, capability limits and reproduction/demonstration record for 137; complete review, verified integration and any separate closure under the centralized Foundation acceptance rules.

- [ ] **Increment 138 — Analysis projections, linearization, derivatives, and noise**
  - [ ] **F-138.A — Architecture, scope and extensibility.** Derive analyses and derivatives from one model; retain noise identity, dimensions and capability limits.
  - [ ] **F-138.B — Implementation and integration**
    - [ ] **F-138.B.1** Derive initialization, DC/operating-point, transient, AC/small-signal, and noise projections from one semantic model.
    - [ ] **F-138.B.2** Implement derivative/Jacobian interfaces, sparse structure, differentiability diagnostics, symbolic/automatic/analytic derivative evidence, and capability-gated numerical differentiation.
    - [ ] **F-138.B.3** Preserve stable noise identity, PSD dimensions, hierarchy, correlation, transfer paths, and analysis applicability.
  - [ ] **F-138.C — Correctness, rejection and predecessor regression**
    - [ ] **F-138.C.1** Add cross-analysis consistency and derivative differential tests.
    - [ ] **F-138.C.2** Test differentiability boundaries, Jacobian reference mismatches, noise correlation and cross-analysis inconsistencies.
  - [ ] **F-138.D — Independent validation and applicability**
    - [ ] **F-138.D.1** Independent model compilation: Compile actual generated supported models using 48 and qualified 75 profiles where needed; retain exact capability and artifact identities.
    - [ ] **F-138.D.2** Numerical reference validation: Execute the relevant DC/AC/transient/noise/event cases with declared references, tolerances, parameter/environment conditions and state/topology assumptions. Unsupported analyses are not successful runs.
    - [ ] **F-138.D.3** Transformation correspondence: Retain independent mathematical/invariant checks and applicable before/after numerical comparisons for introduced transformations; waveform agreement is not a formal proof of unsupported structural changes.
  - [ ] **F-138.E — Optimization review and output quality.** Review sparse derivative/projection reuse without changing analysis applicability or PSD semantics. Apply the proportionate review rule above; document no new optimization required when justified.
  - [ ] **F-138.F — Scale, determinism and compatibility.** Exercise sparse large systems, analysis combinations and stable noise/source identities.
  - [ ] **F-138.G — Evidence, documentation and acceptance.** Retain the applicable evidence, capability limits and reproduction/demonstration record for 138; complete review, verified integration and any separate closure under the centralized Foundation acceptance rules.

- [ ] **Increment 139 — Environment, PVT, statistical variation, and model validity envelopes**
  - [ ] **F-139.A — Architecture, scope and extensibility.** Keep immutable environment/PVT, parameter variability, stochastic behavior and validity envelopes distinct.
  - [ ] **F-139.B — Implementation and integration**
    - [ ] **F-139.B.1** Implement immutable typed environment contexts for temperature, nominal temperature, corner/process, supplies or declared conditions, analysis/sweep coordinates, and deterministic random seeds.
    - [ ] **F-139.B.2** Separate parameters, environment, small-signal noise, transient stochastic behavior, global variation, and local mismatch.
    - [ ] **F-139.B.3** Implement static and dynamic validity-envelope checks, applicability/accuracy metadata, violation policy, source-located diagnostics, and manifests.
  - [ ] **F-139.C — Correctness, rejection and predecessor regression**
    - [ ] **F-139.C.1** Add PVT/seed matrix determinism and envelope-boundary fixtures.
    - [ ] **F-139.C.2** Test envelope boundaries, seed determinism, global versus local variation and wrong-unit/environment misuse.
  - [ ] **F-139.D — Independent validation and applicability**
    - [ ] **F-139.D.1** Independent model compilation: Compile actual generated supported models using 48 and qualified 75 profiles where needed; retain exact capability and artifact identities.
    - [ ] **F-139.D.2** Numerical reference validation: Execute the relevant DC/AC/transient/noise/event cases with declared references, tolerances, parameter/environment conditions and state/topology assumptions. Unsupported analyses are not successful runs.
    - [ ] **F-139.D.3** Transformation correspondence: Retain independent mathematical/invariant checks and applicable before/after numerical comparisons for introduced transformations; waveform agreement is not a formal proof of unsupported structural changes.
  - [ ] **F-139.E — Optimization review and output quality.** Review repeated environment expansion and sampling cost without conflating noise with mismatch. Apply the proportionate review rule above; document no new optimization required when justified.
  - [ ] **F-139.F — Scale, determinism and compatibility.** Exercise parameter/PVT/seed matrices and repeated hierarchy with deterministic reports.
  - [ ] **F-139.G — Evidence, documentation and acceptance.** Retain the applicable evidence, capability limits and reproduction/demonstration record for 139; complete review, verified integration and any separate closure under the centralized Foundation acceptance rules.

- [ ] **Increment 140 — Solver capability profiles and simulator/model ABI seam**
  - [ ] **F-140.A — Architecture, scope and extensibility.** Publish solver-neutral residual/state/event/noise/result interfaces and explicit capability negotiation, not a mandatory native solver.
  - [ ] **F-140.B — Implementation and integration**
    - [ ] **F-140.B.1** Define solver-neutral residual, state, event, derivative, noise, environment, and result interfaces.
    - [ ] **F-140.B.2** Publish capability descriptors and negotiation for analyses, DAEs, events, variable topology, noise, derivatives, tolerances, connect rules, mixed-signal bridges, statistics, hierarchy, and result fidelity.
    - [ ] **F-140.B.3** Define an OSDI-like external model adapter seam and a future native solver plugin seam without requiring either to define Nodal semantics.
  - [ ] **F-140.C — Correctness, rejection and predecessor regression.** Test unsupported analyses/topology/derivatives and malformed adapter metadata with failure-class retention.
  - [ ] **F-140.D — Independent validation and applicability**
    - [ ] **F-140.D.1** Independent consumer and failure fixtures: Exercise actual produced plans/protocols/reports/artifacts with independent expected results and benign malformed/version/crash/timeout controls, as applicable to this tool.
    - [ ] **F-140.D.2** Execution-claim applicability: Record which generated-HDL or external-tool results this tool actually affects and reference or execute the existing required qualification lanes; a mock, cache hit or successful protocol parse is not itself HDL simulation or proof.
  - [ ] **F-140.E — Optimization review and output quality.** Review ABI serialization and negotiation cost without allowing a simulator callback ABI to define semantics. Apply the proportionate review rule above; document no new optimization required when justified.
  - [ ] **F-140.F — Scale, determinism and compatibility.** Exercise version/capability combinations and large sparse interface descriptors deterministically.
  - [ ] **F-140.G — Evidence, documentation and acceptance**
    - [ ] **F-140.G.1** Retain adapter versions, options, commands, hashes, capability decisions, diagnostics, and failure classification.
    - [ ] **F-140.G.2** Retain the applicable evidence, capability limits and reproduction/demonstration record for 140; complete review, verified integration and any separate closure under the centralized Foundation acceptance rules.

- [ ] **Increment 141 — Verilog-A/Verilog-AMS and solver-facing lowering parity**
  - [ ] **F-141.A — Architecture, scope and extensibility**
    - [ ] **F-141.A.1** Do not claim waveform equivalence as proof for unsupported structural transformations.
    - [ ] **F-141.A.2** Legalize equations only with dimension, singularity, state/event, conditioning and target-capability evidence.
  - [ ] **F-141.B — Implementation and integration**
    - [ ] **F-141.B.1** Prove source-semantic, residual/analysis, and target-lowering correspondence for supported constructs.
    - [ ] **F-141.B.2** Implement a capability-checked equation-to-target legalizer that selects direct potential/flow contributions only after dimension, singularity, parameter-envelope, state/event, analysis, numerical-conditioning, and target-capability checks; otherwise introduce explicit auxiliary branches/unknowns where legal or reject the target.
    - [ ] **F-141.B.3** Preserve source module hierarchy where legal while using the logically flattened view only for analysis and proof.
    - [ ] **F-141.B.4** Emit deterministic state, event, noise, analysis, environment, contribution, source-map, and per-equation legalization metadata, including source equation, residual, selected target form, reason, introduced objects, and target location.
  - [ ] **F-141.C — Correctness, rejection and predecessor regression.** Test auxiliary-unknown cases, unsafe orientation, unsupported topology and source/residual/target correspondence mutations.
  - [ ] **F-141.D — Independent validation and applicability**
    - [ ] **F-141.D.1** Add target reparse, OpenVAF/OSDI where supported, ngspice and mixed-signal adapter differential fixtures, and explicit capability rejection.
    - [ ] **F-141.D.2** Independent model compilation: Compile actual generated supported models using 48 and qualified 75 profiles where needed; retain exact capability and artifact identities.
    - [ ] **F-141.D.3** Numerical reference validation: Execute the relevant DC/AC/transient/noise/event cases with declared references, tolerances, parameter/environment conditions and state/topology assumptions. Unsupported analyses are not successful runs.
    - [ ] **F-141.D.4** Transformation correspondence: Retain independent mathematical/invariant checks and applicable before/after numerical comparisons for introduced transformations; waveform agreement is not a formal proof of unsupported structural changes.
  - [ ] **F-141.E — Optimization review and output quality.** Review introduced objects and target form quality while preserving hierarchy and every legalization reason. Apply the proportionate review rule above; document no new optimization required when justified.
  - [ ] **F-141.F — Scale, determinism and compatibility.** Exercise large hierarchy/equation sets and parameter envelopes with deterministic source-to-target metadata.
  - [ ] **F-141.G — Evidence, documentation and acceptance.** Retain the applicable evidence, capability limits and reproduction/demonstration record for 141; complete review, verified integration and any separate closure under the centralized Foundation acceptance rules.

- [ ] **Increment 142 — Continuous-time validation, scale, and reusable-model qualification**
  - [ ] **F-142.A — Architecture, scope and extensibility.** Qualify continuous-time models over declared analyses, environments and solver profiles, preserving unsupported capabilities.
  - [ ] **F-142.B — Implementation and integration**
    - [ ] **F-142.B.1** Add DC/operating-point/transient/AC/noise differential suites, event-order and initialization tests, parameter/PVT/seed matrices, cross-simulator comparisons, and failure classification.
    - [ ] **F-142.B.2** Exercise large sparse islands, hierarchy, repeated models, deterministic ordering, derivative/Jacobian performance, cache keys, and memory/runtime budgets.
    - [ ] **F-142.B.3** Qualify one external reusable analog model package using only public contracts, including model/environment/validity/solver manifests and portability reports.
  - [ ] **F-142.C — Correctness, rejection and predecessor regression.** Combine initialization/events/noise/derivatives with PVT/seed/parameter cases and classify cross-simulator failures.
  - [ ] **F-142.D — Independent validation and applicability**
    - [ ] **F-142.D.1** Independent model compilation: Compile actual generated supported models using 48 and qualified 75 profiles where needed; retain exact capability and artifact identities.
    - [ ] **F-142.D.2** Numerical reference validation: Execute the relevant DC/AC/transient/noise/event cases with declared references, tolerances, parameter/environment conditions and state/topology assumptions. Unsupported analyses are not successful runs.
    - [ ] **F-142.D.3** Transformation correspondence: Retain independent mathematical/invariant checks and applicable before/after numerical comparisons for introduced transformations; waveform agreement is not a formal proof of unsupported structural changes.
  - [ ] **F-142.E — Optimization review and output quality.** Review reusable-model and derivative/Jacobian costs without relaxing accuracy envelopes. Apply the proportionate review rule above; document no new optimization required when justified.
  - [ ] **F-142.F — Scale, determinism and compatibility.** Measure large sparse islands, repeated hierarchy, caches and memory/runtime on declared workloads.
  - [ ] **F-142.G — Evidence, documentation and acceptance**
    - [ ] **F-142.G.1** Publish a capability and limitations matrix for higher-index DAE, dynamic topology, advanced analyses, stochastic features, and simulator extensions.
    - [ ] **F-142.G.2** Retain the applicable evidence, capability limits and reproduction/demonstration record for 142; complete review, verified integration and any separate closure under the centralized Foundation acceptance rules.

### Optional analog component-library track

This dependent track begins only after the Increment 133 equation/component checkpoint and the relevant Increments 134-142 contracts. It lives under `libraries/`, uses only public Nodal contracts, and never becomes a dependency of `core/`.

- [ ] **Library Increment AL-01 — Physical-component interfaces and analog-basic pilot**
  - Define public one-port/two-port, source/load, ground/reference, partial/concrete balance, factory/replacement, parameter-validity, and model-validity conventions.
  - Implement and qualify resistor, capacitor, inductor, ideal sources, controlled sources, and switch components with units, legal parameter ranges, local balance, generated-HDL goldens, and applicable DC/transient/AC evidence.

- [ ] **Library Increment AL-02 — Electrothermal composition pilot**
  - Add thermal terminal/nature/discipline use, thermal capacitance and conductance, power-to-heat coupling, a temperature-dependent resistor, and electrothermal RC examples.
  - Prove electrical-only and coupled models reuse the same conservative connector/equation architecture and reject dimension, balance, PVT, and validity-envelope failures.

- [ ] **Library Increment AL-03 — Library portability, reuse, and release qualification**
  - Add parameter matrices, hierarchy and repeated-instance tests, source-to-HDL traceability, cross-simulator capability reports, semantic versioning, compatibility metadata, and package/release evidence.
  - Keep Modelica-style stream connectors, fluid mixing, full redeclaration semantics, automatic high-index DAE reduction, and production compact-model coverage deferred.

## Capability consistency contract — revision 0.3

[ADR 0027](../architecture/0027-hvl-execution-projection-capability-contract.md) amends the earlier single-source wording. The detailed [HVL plan](nodal-hvl-simulation-v0.1-plan.md) and its [machine-readable surface](nodal-hvl-simulation-v0.1-surface.json) define the same contract.

- Execution class (`live` or `capturable`) and generated-profile eligibility are independent. Capturable does not mean universally projectable.
- A captured program contains common Verification Semantic IR **plus declared typed profile-extension operations**, all with serialization, verification, source locations, capability requirements and stable identities. Portable Core itself stays target-neutral.
- A live test may call a captured component only when every required operation has a qualified live implementation. UVM-only or Verilog-TB-only operations are not automatically live-executable.
- Generated-profile limitations must never restrict otherwise-supported live Nodal HVL. Live is the richer ordinary Scala experience, not a required emulator or strict superset of every target-specific methodology.
- Verilog-TB and UVM are sibling profiles with separate extension libraries, generated-language IRs, validators and release gates. Neither depends on or lowers through the other. Common libraries never import profile implementation libraries.
- Share common test intent; permit explicit profile-specific wrappers and packages. A package may support live only, VTB only, UVM only, several modes, or no runnable mode yet. Every claimed mode needs positive evidence; unsupported/inapplicable is never counted as passed.
- Compare only the declared common semantic intersection. UVM factory/phases/TLM and VTB module/task extensions are separately tested, not flattened into a lowest common denominator.
- `CAP`, `VTB`, `UVM`, `AMSP`, and `XPAR` are the current workstreams. `PORT` is a historical alias only. Numbering is ownership, not an implicit sequence of dependencies.
- Full verification/runtime/generator/VIP implementation remains blocked by the complete Foundation barrier. This roadmap refinement does not close Foundation 147, 148 or 149 or revise historical Increment 152 acceptance evidence.

## Phase 10 — Foundation comments, FPGA-readiness, and HVL verification-readiness

Detailed rationale and dependent-track plans are in [`dependent-productivity-and-verification-tracks-v0.1-plan.md`](dependent-productivity-and-verification-tracks-v0.1-plan.md). Verification backend ownership is defined by [ADR 0023](../architecture/0023-unified-hvl-native-sim-uvm-uvmms-architecture.md), and direct procedural HDL testbench projections are defined by [ADR 0025](../architecture/0025-generated-procedural-hdl-testbench-projections.md). Foundation reserves only the architecture/public seams needed for future native verification, procedural Verilog/Verilog-AMS testbench, UVM, and UVM-MS work; dependent-track implementations remain outside Foundation.

- [ ] **Foundation Increment 143 — Comment/documentation IR architecture and public API gate**
  - [ ] **F-143.A — Architecture, scope and extensibility.** Freeze target-neutral Comment IR anchors and separate presentation from semantic/directive identity.
  - [ ] **F-143.B — Implementation and integration**
    - [ ] **F-143.B.1** Freeze automatic ScalaDoc/unambiguous leading-comment capture plus an explicit target-neutral comment/documentation API for guaranteed placement.
    - [ ] **F-143.B.2** Define stable Comment IR anchors, propagation/orphan policy, directive separation, and semantic-versus-presentation hashing.
  - [ ] **F-143.C — Correctness, rejection and predecessor regression.** Compile positive/negative automatic and explicit placement candidates; reject ambiguity and directive confusion.
  - [ ] **F-143.D — Independent validation and applicability.** Gate validation and applicability: Retain actual compile-positive/negative public or schema candidates and independent design review where required by the existing gate. Record that unimplemented backend/simulator/synthesis behavior receives no execution credit; documentation-only boundaries acquire no artificial HDL tests.
  - [ ] **F-143.E — Optimization review and output quality.** Review capture/propagation complexity without imposing HDL wires or changing semantic hashes. Apply the proportionate review rule above; document no new optimization required when justified.
  - [ ] **F-143.F — Scale, determinism and compatibility.** Exercise nested/leading/ScalaDoc candidate locations and API-version compatibility.
  - [ ] **F-143.G — Evidence, documentation and acceptance.** Retain the applicable evidence, capability limits and reproduction/demonstration record for 143; complete review, verified integration and any separate closure under the centralized Foundation acceptance rules.

- [ ] **Foundation Increment 144 — Scala source-comment capture and Comment IR propagation**
  - Original scope retained: Implement Scala 3 source/comment extraction, explicit comment APIs, stable anchors, deterministic propagation, source correlation, and ambiguity/directive diagnostics.
  - [ ] **F-144.A — Architecture, scope and extensibility.** Capture comments at Scala compile time and propagate stable anchors independently of generated HDL names.
  - [ ] **F-144.B — Implementation and integration**
    - [ ] **F-144.B.1** Implement Scala source/ScalaDoc and explicit comment capture into stable target-neutral Comment IR anchors.
    - [ ] **F-144.B.2** Propagate anchors through the frontend/bridge/native transformations with deterministic orphan/ambiguity/directive diagnostics.
  - [ ] **F-144.C — Correctness, rejection and predecessor regression.** Test ambiguous, orphaned and directive-like comments, separate compilation and unavailable source files.
  - [ ] **F-144.D — Independent validation and applicability**
    - [ ] **F-144.D.1** Representation evidence: Validate actual serialized/native IR and manifests against independently specified semantic expectations and malformed-input controls; compiler parse/print alone is not generated-HDL behavioral proof.
    - [ ] **F-144.D.2** Consumer qualification boundary: Retain source-correlated witnesses for the already assigned lowering/tool consumers and identify their later execution obligations without a reverse dependency. Do not claim unimplemented target behavior here.
  - [ ] **F-144.E — Optimization review and output quality.** Review comment/source-map copying without changing semantic construction identity. Apply the proportionate review rule above; document no new optimization required when justified.
  - [ ] **F-144.F — Scale, determinism and compatibility.** Exercise many comments and deep scopes with deterministic propagation and source correlation.
  - [ ] **F-144.G — Evidence, documentation and acceptance.** Retain the applicable evidence, capability limits and reproduction/demonstration record for 144; complete review, verified integration and any separate closure under the centralized Foundation acceptance rules.

- [ ] **Foundation Increment 145 — Verilog-family comment and documentation lowering**
  - Original scope retained: Emit the same Comment IR deterministically to Verilog, SystemVerilog, Verilog-A, and Verilog-AMS plus documentation/source-map manifests without changing semantic HDL identity.
  - [ ] **F-145.A — Architecture, scope and extensibility.** Lower one Comment IR through supported backend profiles, retaining semantic-versus-presentation hash separation.
  - [ ] **F-145.B — Implementation and integration**
    - [ ] **F-145.B.1** Implement supported Verilog-family renderers from the same Comment IR, retaining backend-specific placement and escaping.
    - [ ] **F-145.B.2** Produce documentation/source-map sidecars and semantic-versus-presentation hashes; future SystemVerilog support remains capability-gated.
  - [ ] **F-145.C — Correctness, rejection and predecessor regression.** Test reserved text, escaping, moved/orphaned anchors and cross-backend location correlation.
  - [ ] **F-145.D — Independent validation and applicability**
    - [ ] **F-145.D.1** Rendered artifact validation: Compare actual generated comments, documentation and source maps across the supported backend profiles, then check parsed semantic identity with comments removed using the applicable independent parser.
    - [ ] **F-145.D.2** Semantic preservation boundary: Record unchanged semantic hashes and any genuinely applicable behavioral parity checks; do not invent analog simulation, synthesis or formal requirements solely for comment placement.
  - [ ] **F-145.E — Optimization review and output quality.** Review readability and duplicate comments; formatting must not change parsed HDL meaning. Apply the proportionate review rule above; document no new optimization required when justified.
  - [ ] **F-145.F — Scale, determinism and compatibility.** Exercise large generated files and repeatable documentation/source-map manifests across supported profiles.
  - [ ] **F-145.G — Evidence, documentation and acceptance.** Retain the applicable evidence, capability limits and reproduction/demonstration record for 145; complete review, verified integration and any separate closure under the centralized Foundation acceptance rules.

- [ ] **Foundation Increment 146 — FPGA productivity architecture readiness**
  - [ ] **F-146.A — Architecture, scope and extensibility**
    - [ ] **F-146.A.1** Do not implement vendor constraints, board libraries, FPGA builds/programming, timing-closure exploration, or debug insertion in Foundation.
    - [ ] **F-146.A.2** Freeze only resource/constraint/debug/provenance identities and adapter seams for later productivity implementations.
  - [ ] **F-146.B — Implementation and integration.** Freeze reusable-IP requirements, board/platform resources, project implementation intent, portable Constraint IR, stable semantic targets, vendor capability/tool-adapter seams, constraint coverage, normalized reports, debug identities, and build/program provenance.
  - [ ] **F-146.C — Correctness, rejection and predecessor regression.** Compile architecture candidates and reject unstable hierarchy-string binding or implicit vendor execution.
  - [ ] **F-146.D — Independent validation and applicability.** Gate validation and applicability: Retain actual compile-positive/negative public or schema candidates and independent design review where required by the existing gate. Record that unimplemented backend/simulator/synthesis behavior receives no execution credit; documentation-only boundaries acquire no artificial HDL tests.
  - [ ] **F-146.E — Optimization review and output quality.** Review seam complexity and preserved metadata without adding vendor flow implementation to Foundation. Apply the proportionate review rule above; document no new optimization required when justified.
  - [ ] **F-146.F — Scale, determinism and compatibility.** Exercise representative board/resource/constraint candidate identities and schema compatibility.
  - [ ] **F-146.G — Evidence, documentation and acceptance.** Retain the applicable evidence, capability limits and reproduction/demonstration record for 146; complete review, verified integration and any separate closure under the centralized Foundation acceptance rules.

- [ ] **Foundation Increment 147 — Nodal HVL Verification Semantic IR and public API architecture gate**
  - [ ] **F-147.A — Architecture, scope and extensibility.** Freeze common Verification Semantic IR plus typed profile extensions independently of live/projection eligibility.
  - [ ] **F-147.B — Implementation and integration**
    - [ ] **F-147.B.1** Freeze target-neutral tests/scenarios, transactions, processes/events/time, drivers/monitors/agents, scoreboards/reference models, constrained stimulus, deterministic replay, functional coverage, properties/checks, register bindings, reusable VIP packaging, and AMS verification extensions.
    - [ ] **F-147.B.2** Bind verification endpoints to logical Interface/Register identities rather than generated HDL hierarchy strings.
    - [ ] **F-147.B.3** Apply ADR 0027 and the revision 0.3 capability contract: execution eligibility is independent of projection eligibility; capture the complete common-plus-typed-extension program without requiring arbitrary live Scala capture.
  - [ ] **F-147.C — Correctness, rejection and predecessor regression**
    - [ ] **F-147.C.1** Freeze compile-positive/negative candidates for live-only, VTB-only, UVM-only and shared components, including live calls to unsupported generated-only extensions. Retain this architecture gate as incomplete until its actual acceptance evidence exists.
    - [ ] **F-147.C.2** Compile live-only, VTB-only, UVM-only and shared cases; reject unqualified live calls to generated-only extensions.
  - [ ] **F-147.D — Independent validation and applicability.** Gate validation and applicability: Retain actual compile-positive/negative public or schema candidates and independent design review where required by the existing gate. Record that unimplemented backend/simulator/synthesis behavior receives no execution credit; documentation-only boundaries acquire no artificial HDL tests.
  - [ ] **F-147.E — Optimization review and output quality.** Review capture/API complexity without forcing a universal UVM emulator or common lowest-denominator semantics. Apply the proportionate review rule above; document no new optimization required when justified.
  - [ ] **F-147.F — Scale, determinism and compatibility.** Exercise endpoint/transaction/profile candidate combinations with stable logical Interface/Register identities.
  - [ ] **F-147.G — Evidence, documentation and acceptance.** Retain the applicable evidence, capability limits and reproduction/demonstration record for 147; complete review, verified integration and any separate closure under the centralized Foundation acceptance rules.

- [ ] **Foundation Increment 148 — Native verification runtime and generated-SystemVerilog IR readiness**
  - [ ] **F-148.A — Architecture, scope and extensibility**
    - [ ] **F-148.A.1** Do not implement complete HVL runtime or UVM generation in Foundation.
    - [ ] **F-148.A.2** Keep otherwise-supported live Scala execution independent of generated-profile limits. A profile-specific operation is live-callable only through an explicitly qualified live implementation; do not require a universal UVM emulator.
    - [ ] **F-148.A.3** Freeze native runtime and verification-SystemVerilog IR readiness as distinct architecture contracts, not implemented runtime/generation.
  - [ ] **F-148.B — Implementation and integration**
    - [ ] **F-148.B.1** Freeze the native Nodal verification scheduler/runtime contract independently of UVM and the simulator-adapter boundary needed for direct Verilator/Icarus and future open mixed-signal execution.
    - [ ] **F-148.B.2** Define a verification-SystemVerilog IR sufficient for generated UVM/VIP classes, interfaces/virtual interfaces, clocking blocks, dynamic containers, processes/events/mailboxes/semaphores, constraints/randomization, covergroups, properties, and DPI/VPI shims.
  - [ ] **F-148.C — Correctness, rejection and predecessor regression.** Test candidate runtime/profile boundaries and reject implicit live support for unqualified generated operations.
  - [ ] **F-148.D — Independent validation and applicability.** Gate validation and applicability: Retain actual compile-positive/negative public or schema candidates and independent design review where required by the existing gate. Record that unimplemented backend/simulator/synthesis behavior receives no execution credit; documentation-only boundaries acquire no artificial HDL tests.
  - [ ] **F-148.E — Optimization review and output quality.** Review scheduler/IR representation scope without duplicating public authoring models or implementing dependent tracks. Apply the proportionate review rule above; document no new optimization required when justified.
  - [ ] **F-148.F — Scale, determinism and compatibility.** Exercise representative process/container/synchronization candidates and schema compatibility.
  - [ ] **F-148.G — Evidence, documentation and acceptance.** Retain the applicable evidence, capability limits and reproduction/demonstration record for 148; complete review, verified integration and any separate closure under the centralized Foundation acceptance rules.

- [ ] **Foundation Increment 149 — UVM/UVM-MS projection and vendor-profile architecture readiness**
  - [ ] **F-149.A — Architecture, scope and extensibility**
    - [ ] **F-149.A.1** Confine unavoidable vendor `ifdef`s to generated adapter/include units; do not scatter them through common VIP logic.
    - [ ] **F-149.A.2** Freeze independent UVM/UVM-MS extension namespaces, typed extension serialization/verifiers, per-profile applicability and simulator qualification. Never route UVM through Procedural HDL Testbench IR or make VTB completion a UVM prerequisite.
    - [ ] **F-149.A.3** Freeze independent UVM/UVM-MS projections, vendor-neutral common source and typed extension namespaces.
  - [ ] **F-149.B — Implementation and integration**
    - [ ] **F-149.B.1** Accept ADR 0023 and freeze Verification Semantic IR -> UVM/UVM-MS projections while keeping `nodal sim` independent of generated UVM.
    - [ ] **F-149.B.2** Freeze UVM component/TLM/factory/config/phase/objection/RAL mappings, UVM-MS structural/class bridge identities, vendor-neutral common source, and thin VCS/Questa/Xcelium profile seams.
  - [ ] **F-149.C — Correctness, rejection and predecessor regression.** Test candidate profile applicability, serialization and vendor-leakage rejection; VTB completion must not become a UVM prerequisite.
  - [ ] **F-149.D — Independent validation and applicability.** Gate validation and applicability: Retain actual compile-positive/negative public or schema candidates and independent design review where required by the existing gate. Record that unimplemented backend/simulator/synthesis behavior receives no execution credit; documentation-only boundaries acquire no artificial HDL tests.
  - [ ] **F-149.E — Optimization review and output quality.** Review adapter/include placement and future projection metadata without generating a complete methodology. Apply the proportionate review rule above; document no new optimization required when justified.
  - [ ] **F-149.F — Scale, determinism and compatibility.** Exercise common and vendor-profile candidates with stable Interface/Register/test identities.
  - [ ] **F-149.G — Evidence, documentation and acceptance.** Retain the applicable evidence, capability limits and reproduction/demonstration record for 149; complete review, verified integration and any separate closure under the centralized Foundation acceptance rules.

- [x] **Foundation Increment 152 — Direct procedural HDL testbench projection architecture readiness**
  - Accept ADR 0025 and freeze a Procedural HDL Testbench IR lowering seam beneath the canonical Verification Semantic IR; it is generated-language IR, not a second authoring model.
  - Define portable Verilog-2005 and standards-oriented Verilog-AMS testbench profiles, stable Interface/Register/test/check identities, source maps, manifests, deterministic replay sidecars, normalized results, and artifact hashes.
  - Classify every selected Verification IR operation as embedded, precomputed replay, companion-runtime-required, or unsupported; unsupported behavior must fail generation rather than be silently omitted.
  - Make Icarus the required event-driven portable-Verilog reference, qualify Verilator separately, and permit direct open-source Verilog-AMS execution only after exact capability conformance.
  - Keep Foundation architecture-only: no testbench generator, simulator runner, open AMS harness, UVM/UVM-MS generator, or verification library is implemented here.
  - Evidence: [ADR 0025](../architecture/0025-generated-procedural-hdl-testbench-projections.md), [staging plan](generated-hdl-testbench-projections-v0.1-plan.md), [PR #56](https://github.com/pysolvesemi/Nodal/pull/56), and [closure PR #58](https://github.com/pysolvesemi/Nodal/pull/58).
  - [x] **F-152.H — Historical acceptance breakdown: Procedural testbench projection architecture.** Retain ADR 0025, PR 56 and closure PR 58 as architecture-only acceptance; no testbench generator, simulator runner or UVM/HVL runtime is claimed.

- [ ] **Foundation Increment 153 — Function-local lexical naming and alias contract**
  - [ ] **F-153.A — Architecture, scope and extensibility**
    - [ ] **F-153.A.1** Freeze a target-neutral structured name-path contract with the priority `explicit user name > caller prefix plus function-local binder > lexical binder > outer/member alias > semantic role or sink affinity > generated fallback` while keeping public API v0.3 unchanged.
    - [ ] **F-153.A.2** Keep raw Scala spelling separate from target-HDL sanitization and collision resolution; preserve both inner function-local names and outer call-site aliases rather than replacing one with the other.
    - [ ] **F-153.A.3** Freeze naming priority and structured caller/local identities independently of materialization and public API v0.3.
  - [ ] **F-153.B — Implementation and integration.** Retain raw Scala binders and aliases for hardware-producing `val`, `var`, and `lazy val` declarations inside ordinary methods, local methods, lambdas, nested blocks, loop bodies, and match branches, together with lexical owner, definition span, invocation context, and provenance.
  - [ ] **F-153.C — Correctness, rejection and predecessor regression.** Test candidate explicit/local/member/role priorities, alias retention and source-bound versus genuinely unnamed distinctions.
  - [ ] **F-153.D — Independent validation and applicability**
    - [ ] **F-153.D.1** Compiler-boundary identity checks: Compare actual captured/serialized/native naming metadata against source-derived expected binders, aliases and definition/invocation paths, including deliberate loss/collision mutations.
    - [ ] **F-153.D.2** Backend parity handoff: Retain fixtures for the mandatory 65/72 target naming, safe-inlining and materialization parity checks. Those backends depend on 153-157; do not gate this metadata increment on their completion or claim target qualification prematurely.
  - [ ] **F-153.E — Optimization review and output quality**
    - [ ] **F-153.E.1** Separate naming from materialization: an expression keeps source-level naming metadata when safely inlined, while `keep`, observability, readable/debug policy, sharing, typing, or target legality independently decides whether an HDL object is emitted.
    - [ ] **F-153.E.2** Review metadata size and naming readability without requiring a wire solely to expose a binder. Apply the proportionate review rule above; document no new optimization required when justified.
  - [ ] **F-153.F — Scale, determinism and compatibility.** Exercise nested helpers and lexical contexts with deterministic target-neutral paths.
  - [ ] **F-153.G — Evidence, documentation and acceptance.** Retain the applicable evidence, capability limits and reproduction/demonstration record for 153; complete review, verified integration and any separate closure under the centralized Foundation acceptance rules.

- [ ] **Foundation Increment 154 — Scala 3 compiler-derived binder and naming-scope capture**
  - [ ] **F-154.A — Architecture, scope and extensibility.** Capture binders/owners/call scopes from Scala 3 typed trees; runtime inspection remains diagnostic fallback only.
  - [ ] **F-154.B — Implementation and integration**
    - [ ] **F-154.B.1** Replace runtime stack/source-line inspection as the naming authority with Scala 3 compile-time typed-tree capture of binders, owners, expansion positions, and call-site naming scopes; retain runtime inspection only as a diagnostic fallback.
    - [ ] **F-154.B.2** Propagate an outer binding such as `pixelResult` into helper construction so a local `widenedSum` carries the structured path `pixelResult / widenedSum` without requiring user annotations or source changes.
  - [ ] **F-154.C — Correctness, rejection and predecessor regression**
    - [ ] **F-154.C.1** Cover multiline right-hand sides, backticked identifiers, private/nested helpers, lambdas, separately compiled libraries or JARs, unavailable source files at elaboration time, and deterministic diagnostics when no trustworthy binder exists.
    - [ ] **F-154.C.2** Add compile-positive, compile-negative, macro-expansion, separate-compilation, and source-unavailable fixtures proving that naming does not depend on `StackWalker`, filesystem layout, or runtime source parsing.
    - [ ] **F-154.C.3** Test multiline/backticked/local/lambda forms, separate JAR compilation and source-unavailable behavior.
  - [ ] **F-154.D — Independent validation and applicability**
    - [ ] **F-154.D.1** Compiler-boundary identity checks: Compare actual captured/serialized/native naming metadata against source-derived expected binders, aliases and definition/invocation paths, including deliberate loss/collision mutations.
    - [ ] **F-154.D.2** Backend parity handoff: Retain fixtures for the mandatory 65/72 target naming, safe-inlining and materialization parity checks. Those backends depend on 153-157; do not gate this metadata increment on their completion or claim target qualification prematurely.
  - [ ] **F-154.E — Optimization review and output quality.** Review capture/expansion cost without forcing elaboration-time filesystem or StackWalker dependence. Apply the proportionate review rule above; document no new optimization required when justified.
  - [ ] **F-154.F — Scale, determinism and compatibility.** Exercise many helper invocations and build locations with repeatable metadata and diagnostics.
  - [ ] **F-154.G — Evidence, documentation and acceptance.** Retain the applicable evidence, capability limits and reproduction/demonstration record for 154; complete review, verified integration and any separate closure under the centralized Foundation acceptance rules.

- [ ] **Foundation Increment 155 — Helper invocation identity, return aliases, and collision semantics**
  - [ ] **F-155.A — Architecture, scope and extensibility.** Separate definition origin, invocation origin and return aliases; use semantic paths before stable digests.
  - [ ] **F-155.B — Implementation and integration**
    - [ ] **F-155.B.1** Define deterministic caller-qualified emission such as `pixelResult_widenedSum`, `leftResult_widenedSum`, and `rightResult_widenedSum` for repeated helper invocations while retaining the original local binder as a source alias.
    - [ ] **F-155.B.2** When a helper return and outer binding denote the same final value, prefer the outer call-site name such as `pixelResult` for the emitted object and retain `pixelResult_clippedSum` as an alias; emit a separate local object only when materialization policy requires one.
    - [ ] **F-155.B.3** Define nested-helper, local-function, recursion-policy, loop, symbolic-generate, unrolling, cloning, parameterized invocation, and hierarchy qualification rules using definition origin and invocation origin as distinct identities.
  - [ ] **F-155.C — Correctness, rejection and predecessor regression.** Test repeated/nested calls, alias-return identity, recursion policy and collisions under clone/unroll/generate contexts.
  - [ ] **F-155.D — Independent validation and applicability**
    - [ ] **F-155.D.1** Compiler-boundary identity checks: Compare actual captured/serialized/native naming metadata against source-derived expected binders, aliases and definition/invocation paths, including deliberate loss/collision mutations.
    - [ ] **F-155.D.2** Backend parity handoff: Retain fixtures for the mandatory 65/72 target naming, safe-inlining and materialization parity checks. Those backends depend on 153-157; do not gate this metadata increment on their completion or claim target qualification prematurely.
  - [ ] **F-155.E — Optimization review and output quality**
    - [ ] **F-155.E.1** Resolve collisions with semantic caller paths and stable source/call-site digests only as the final qualifier; never use pass traversal order or an opaque counter as semantic identity.
    - [ ] **F-155.E.2** Review duplicate aliases and qualifiers without traversal counters or unnecessary local materialization. Apply the proportionate review rule above; document no new optimization required when justified.
  - [ ] **F-155.F — Scale, determinism and compatibility.** Exercise large call graphs and parameterized repeated invocations with stable names under reorderings.
  - [ ] **F-155.G — Evidence, documentation and acceptance.** Retain the applicable evidence, capability limits and reproduction/demonstration record for 155; complete review, verified integration and any separate closure under the centralized Foundation acceptance rules.

- [ ] **Foundation Increment 156 — Function-local naming metadata in the Scala bridge and Nodal MLIR**
  - [ ] **F-156.A — Architecture, scope and extensibility.** Version naming metadata across construction snapshots, bridge and MLIR without target-specific sanitization.
  - [ ] **F-156.B — Implementation and integration**
    - [ ] **F-156.B.1** Extend the construction snapshot, bridge protocol, and Nodal MLIR with target-neutral equivalents of `nodal.source_name`, `nodal.source_aliases`, `nodal.name_provenance`, `nodal.lexical_scope`, `nodal.definition_loc`, `nodal.invocation_path`, and `nodal.materialization_boundary`.
    - [ ] **F-156.B.2** Preserve raw names, aliases, definition and invocation locations, source maps, and structured paths through parser/printer round trips, normalized textual IR, bridge versioning, cache keys, and deterministic fingerprints.
    - [ ] **F-156.B.3** Keep aliases on safely inlined expressions, assign distinct invocation identities to clones or unrolled instances without losing their original binders, and defer target keyword escaping/sanitization to the selected backend.
  - [ ] **F-156.C — Correctness, rejection and predecessor regression**
    - [ ] **F-156.C.1** Add byte-stability, bridge round-trip, clone/unroll, source-map, and mutation tests that fail when any source-bound value loses its lexical naming metadata.
    - [ ] **F-156.C.2** Mutate missing raw binders/aliases/provenance and test parse/print, clone/unroll and version round trips.
  - [ ] **F-156.D — Independent validation and applicability**
    - [ ] **F-156.D.1** Compiler-boundary identity checks: Compare actual captured/serialized/native naming metadata against source-derived expected binders, aliases and definition/invocation paths, including deliberate loss/collision mutations.
    - [ ] **F-156.D.2** Backend parity handoff: Retain fixtures for the mandatory 65/72 target naming, safe-inlining and materialization parity checks. Those backends depend on 153-157; do not gate this metadata increment on their completion or claim target qualification prematurely.
  - [ ] **F-156.E — Optimization review and output quality.** Review serialization and source-map growth while preserving metadata on safely inlined expressions. Apply the proportionate review rule above; document no new optimization required when justified.
  - [ ] **F-156.F — Scale, determinism and compatibility.** Exercise large alias/path inventories and cache fingerprints across repeat builds and versions.
  - [ ] **F-156.G — Evidence, documentation and acceptance.** Retain the applicable evidence, capability limits and reproduction/demonstration record for 156; complete review, verified integration and any separate closure under the centralized Foundation acceptance rules.

- [ ] **Foundation Increment 157 — Name preservation, generated namespaces, and verifier closure**
  - [ ] **F-157.A — Architecture, scope and extensibility**
    - [ ] **F-157.A.1** Reserve deterministic Nodal-owned generated namespaces such as `_net_<operation>_<stable-index>`, `_reg_<role>_<stable-index>`, `_mem_<role>_<stable-index>`, `_inst_<type>_<stable-index>`, and `_gen_<role>_<stable-index>`; prefer operation, sink, protocol, domain, or structural-role names before the generic fallback.
    - [ ] **F-157.A.2** Make metadata preservation and generated namespaces mandatory pass contracts distinct from optional optimization.
  - [ ] **F-157.B — Implementation and integration**
    - [ ] **F-157.B.1** Define mandatory behavior for inlining, materialization, common-subexpression elimination, dead-code elimination, cloning/unrolling, retiming/automatic pipelining, dialect conversion, target lowering, and optimization plugins: source-bound names and aliases survive every semantics-preserving transformation.
    - [ ] **F-157.B.2** Add a mandatory verifier that rejects a materialized source-bound value whose lexical name path was lost or replaced by an anonymous fallback, while allowing a safely inlined value to remain metadata-only.
  - [ ] **F-157.C — Correctness, rejection and predecessor regression**
    - [ ] **F-157.C.1** Add pass-by-pass mutation tests, declaration-order permutations, repeated-build goldens, and source-bound-versus-genuinely-unnamed inventories proving that `_net_*` is used only when no meaningful source, caller, sink, or role name exists.
    - [ ] **F-157.C.2** Use pass-by-pass metadata-loss mutations and source-bound/fallback classification negatives through available compiler boundaries.
  - [ ] **F-157.D — Independent validation and applicability**
    - [ ] **F-157.D.1** Compiler-boundary identity checks: Compare actual captured/serialized/native naming metadata against source-derived expected binders, aliases and definition/invocation paths, including deliberate loss/collision mutations.
    - [ ] **F-157.D.2** Backend parity handoff: Retain fixtures for the mandatory 65/72 target naming, safe-inlining and materialization parity checks. Those backends depend on 153-157; do not gate this metadata increment on their completion or claim target qualification prematurely.
  - [ ] **F-157.E — Optimization review and output quality**
    - [ ] **F-157.E.1** Allocate fallback suffixes only after normalized IR ordering so unrelated pass changes, working directories, JVM identity, or source traversal do not renumber accepted output. Prohibit Nodal-owned `_zz*`, `_T*`, `_GEN*`, `expr_<number>`, and `tmp_<number>` identifiers in accepted HDL.
    - [ ] **F-157.E.2** Review names and materialization decisions without permitting anonymous fallbacks to hide lost source identity. Apply the proportionate review rule above; document no new optimization required when justified.
  - [ ] **F-157.F — Scale, determinism and compatibility.** Exercise pass/declaration-order permutations and large generated namespaces with stable suffix assignment.
  - [ ] **F-157.G — Evidence, documentation and acceptance.** Retain the applicable evidence, capability limits and reproduction/demonstration record for 157; complete review, verified integration and any separate closure under the centralized Foundation acceptance rules.

- [x] **Foundation Increment 158 — Equation-oriented analog roadmap synchronization**
  - Record first-class unordered continuous equations separately from additive potential/flow contributions, procedural analog assignments, and conservative connections.
  - Record conservative connection equations, partial/concrete physical-component balance, structural parameters, logical flattening with hierarchy-preserving emission, residual-preserving canonicalization, and capability-checked equation-to-target legalization.
  - Make the Increment 133 equation/component API checkpoint a prerequisite for Increment 32 and add the dependent analog-basic/electrothermal library pilot track without changing frozen public syntax or implementing compiler behavior.
  - Evidence: [roadmap PR #74](https://github.com/pysolvesemi/Nodal/pull/74), [materialization run 33090381745](https://github.com/pysolvesemi/Nodal/actions/runs/33090381745), [Core CI run 33090944261](https://github.com/pysolvesemi/Nodal/actions/runs/33090944261), and passing Increment 13-27 regression runs attached to PR #74.
  - [x] **F-158.H — Historical acceptance breakdown: Equation-oriented roadmap synchronization.** Retain the recorded equation/contribution/assignment separation and 133-to-32 checkpoint dependency; no compiler behavior was implemented by the synchronization.

- [ ] **Foundation Increment 159 — Typed staged Scala `for` ranges for generate and hardware loops**
  - [ ] **F-159.A — Architecture, scope and extensibility**
    - [ ] **F-159.A.1** Preserve symbolic `genRange` bounds through hierarchy, target-neutral IR, and emitted HDL. For `hwRange`, derive the finite upper envelope from a concrete bound or the symbolic parameter's declared legal range; require `maximum` only when the bound contract otherwise lacks a finite upper envelope, enforce that maximum as a legality constraint, and reject dynamic hardware values or runtime trip counts.
    - [ ] **F-159.A.2** Select loop kind before validating the body. `genRange` may create local structural declarations, component instances, connections, generated process regions, and nested legal generation, but it may not mutate the enclosing module's frozen boundary ports. `hwRange` may perform repeated operations inside the enclosing combinational or sequential semantic region but may not create ports, component instances, or new structural hardware objects. A Scala local `val` remains a binder or alias unless it explicitly constructs hardware; component instances, local variables, or `Reg`/`Wire` presence never choose the loop category.
    - [ ] **F-159.A.3** Keep plain Scala loops concretely elaborated even when they instantiate modules. Do not silently reconstruct parameterization or change hierarchy, process ownership, naming, or source paths from body-pattern inference. Any future static-repetition compression must be a separately selected proof-carrying optimization, not a source-language staging rule.
    - [ ] **F-159.A.4** Prerequisites: Increments 55 and 58 plus Foundation Increments 153-157. Integrate portable Verilog, open-source equivalence, and Verilog-AMS regression through Increments 65-67 and 72 without changing their target loop semantics.
    - [ ] **F-159.A.5** Reuse existing generate/hardware-loop IR and naming 153-157; range type, never body inspection, selects staging.
  - [ ] **F-159.B — Implementation and integration**
    - [ ] **F-159.B.1** Add public backend-neutral staged range types and concise constructors such as `genRange(lower, upper, step = 1)` and `hwRange(lower, upper, step = 1, maximum = ...)`. Each bound accepts either a Scala `Int` or a legal target-visible integer parameter/constant; overloads or an internal static-bound abstraction must not require users to collapse symbolic parameters into Scala integers.
    - [ ] **F-159.B.2** Keep `for index <- 0 until count` as Scala elaboration only. Lower `for index <- genRange(0, LANES)` exactly to existing structural `nodal.generate` semantics and `for index <- hwRange(0, TAPS)` exactly to existing bounded `nodal.hardware_loop` semantics whether `LANES` and `TAPS` are concrete `Int` values or legal symbolic integer parameters/constants. Never inspect the loop body to infer or change staging.
    - [ ] **F-159.B.3** Make staged-range `for` forms and canonical `generate(...)`/`loop(...)` forms normalize to identical target-neutral IR, diagnostics, source maps, naming/provenance, optimization obligations, and Verilog-family lowering. Treat this as a frontend ergonomic layer, not a new loop kind or backend construct.
  - [ ] **F-159.C — Correctness, rejection and predecessor regression**
    - [ ] **F-159.C.1** Add positive and negative compile fixtures for `Int` and symbolic parameter/constant bounds, parameter-range-derived envelopes, explicit enforced maxima, ascending steps, empty/singleton ranges, nested and mixed loop categories, helper methods, separate compilation, generated names, invalid dynamic bounds, missing finite envelopes, structural creation inside `hwRange`, local alias non-materialization, plain-Scala non-inference, deterministic process labels, and explicit-form-versus-range-form IR/HDL equivalence.
    - [ ] **F-159.C.2** Test concrete/symbolic envelopes, explicit maxima, empty/singleton/nested loops and illegal structural creation in hwRange.
  - [ ] **F-159.D — Independent validation and applicability**
    - [ ] **F-159.D.1** Syntax and elaboration: Use the pinned 66 Verilator/Icarus lanes on actual generated HDL in the selected language/profile; record source/output identities, commands and capability limits.
    - [ ] **F-159.D.2** Behavioral simulation: Simulate the selected boundary and predecessor-interaction cases against declared reference behavior, retaining seeds, reset/stimulus sequences and waveforms.
    - [ ] **F-159.D.3** Synthesis and structure: Use 67 Yosys checks for the synthesizable digital artifacts, retaining hierarchy, cell/memory/driver and parameter results.
    - [ ] **F-159.D.4** Equivalence and formal applicability: Record distinct equivalence/property applicability and execute every required transformation, safety or protocol check through 67; retain assumptions, bounds and counterexamples rather than using simulation as proof.
  - [ ] **F-159.E — Optimization review and output quality**
    - [ ] **F-159.E.1** Derive generated combinational/sequential region and block labels from semantic source roles, caller/local binder paths, sinks/state owners, and symbolic indices. Prohibit generic `COMB_<id>`/`SEQ_<id>` or traversal-counter identities; use a deterministic collision suffix only as the final fallback.
    - [ ] **F-159.E.2** Review range-wrapper overhead and preserve symbolic bounds and semantic process labels without implicit repetition compression. Apply the proportionate review rule above; document no new optimization required when justified.
  - [ ] **F-159.F — Scale, determinism and compatibility.** Exercise nested ranges, helper/separate compilation and parameter matrices with explicit-form parity.
  - [ ] **F-159.G — Evidence, documentation and acceptance.** Retain the applicable evidence, capability limits and reproduction/demonstration record for 159; complete review, verified integration and any separate closure under the centralized Foundation acceptance rules.

## Foundation completion barrier

> **Blocked:** no FPGA Productivity, Digital Verification, or Analog/Mixed-Signal Verification implementation increment may start until every Foundation increment is complete, including architecture-only Increments 150-152 recorded in companion plans, function-local semantic-naming Increments 153-157, typed staged-loop range Increment 159, and any later Foundation item added before the barrier is released.

Research and feasibility work may continue while blocked. Any newly discovered core architecture requirement belongs in Foundation.

## FPGA Productivity Track — blocked by Foundation; numbering restarts

See [`dependent-productivity-and-verification-tracks-v0.1-plan.md`](dependent-productivity-and-verification-tracks-v0.1-plan.md) for detailed scope.

- [ ] **FPGA Increment 1 — FPGA public platform/resource/constraint API gate**
- [ ] **FPGA Increment 2 — Board/device/resource database and binding**
- [ ] **FPGA Increment 3 — Portable timing and I/O constraint engine**
- [ ] **FPGA Increment 4 — AMD Vivado constraint/build backend**
- [ ] **FPGA Increment 5 — Intel Quartus and open-source FPGA backends**
- [ ] **FPGA Increment 6 — Additional vendor profiles and constraint coverage**
- [ ] **FPGA Increment 7 — Reproducible build, program, artifact, and normalized reporting**
- [ ] **FPGA Increment 8 — Timing-closure feedback and bounded design-space exploration**
- [ ] **FPGA Increment 9 — Vendor primitive/IP abstraction and debug instrumentation**
- [ ] **FPGA Increment 10 — Board bring-up, IP packaging, HIL, and ecosystem qualification**

## Digital Verification Track — blocked by Foundation; numbering restarts

Live Nodal HVL is the primary host-side execution path and does not require complete static capture of ordinary Scala. Capturable common semantics plus declared typed profile extensions support only their qualified modes. Verilog-TB and UVM are independent sibling profiles with separate libraries, generated IRs and release gates; neither defines or restricts otherwise-supported live execution.

- [ ] **Digital Verification Increment 1 — Nodal HVL native digital simulation vertical slice**
- [ ] **Digital Verification Increment 2 — Scenarios, sequences, constrained stimulus, and replay**
- [ ] **Digital Verification Increment 3 — Agents, drivers, monitors, scoreboards, and reference models**
- [ ] **Digital Verification Increment 4 — Functional coverage and verification reporting**
- [ ] **Digital Verification Increment 5 — Properties, protocol checks, and register-model verification**
- [ ] **Digital Verification Increment 6 — Portable Verilog testbench generation**
  - Ownership: CAP-01 through CAP-05 and VTB-01 through VTB-03; use the independent VTB capability set, library and generated-language IR. No UVM implementation prerequisite.

- [ ] **Digital Verification Increment 7 — Open-source Verilog testbench execution and qualification**
  - Ownership: VTB-04 through VTB-07. Close the VTB release independently of UVM, AMSP and aggregate XPAR completion.

- [ ] **Digital Verification Increment 8 — Verification SystemVerilog and digital UVM generation**
  - Ownership: UVM-01 through UVM-06 after CAP-05, not after VTB. Capture explicit UVM-only extensions with separate typed operations and library boundaries.

- [ ] **Digital Verification Increment 9 — Commercial simulator profiles**
  - Ownership: UVM-07 for generated methodology qualification; direct commercial live adapters remain separate. Require an actual qualified simulator for an executable UVM claim, not every vendor family.

- [ ] **Digital Verification Increment 10 — Native, Verilog-testbench, and UVM semantic parity**
  - Ownership: XPAR-01 through XPAR-03 and XPAR-05. Compare only qualified common semantic intersections; generated-only methodology behavior is not a live or sibling-profile parity requirement.

- [ ] **Digital Verification Increment 11 — Reusable digital VIP qualification**
  - Ownership: VTB-06 and UVM-08 in their independent lanes. Common semantic VIP must not import either profile library; profile-specific wrappers and single-profile packages are valid.

- [ ] **Digital Verification Increment 12 — Scale, performance, compatibility, and verification release gate**
  - Ownership: LIVE-08, CAP-05, VTB-07, UVM-09 and applicable XPAR gates. Track independent releases; aggregate track completion is not a release prerequisite for an individual lane.

## Analog/Mixed-Signal Verification Track — blocked by Foundation; numbering restarts

This track is separate from Digital Verification but reuses its target-neutral transaction/component concepts and Foundation AMS semantics. Native/open execution, generated open AMS harness or Verilog-AMS testbench, and UVM-MS are sibling projections; none replaces the canonical Nodal HVL environment.

- [ ] **AMS Verification Increment 1 — Nodal HVL native mixed-signal simulation vertical slice**
- [ ] **AMS Verification Increment 2 — Analog/mixed-signal agents, drivers, monitors, and scoreboards**
- [ ] **AMS Verification Increment 3 — PVT, sweeps, stochastic stimulus, and deterministic replay**
- [ ] **AMS Verification Increment 4 — Analog measurements and functional coverage**
- [ ] **AMS Verification Increment 5 — Mixed-signal properties and register/control interaction**
- [ ] **AMS Verification Increment 6 — Standards-oriented Verilog-AMS testbench generation**
- [ ] **AMS Verification Increment 7 — Open-source AMS harness generation and capability-qualified execution**
- [ ] **AMS Verification Increment 8 — UVM-MS generation from Verification IR**
- [ ] **AMS Verification Increment 9 — Commercial mixed-signal simulator profiles**
- [ ] **AMS Verification Increment 10 — Native, open-harness, Verilog-AMS-testbench, and UVM-MS semantic parity**
- [ ] **AMS Verification Increment 11 — Reusable mixed-signal VIP qualification**
- [ ] **AMS Verification Increment 12 — Scale, portability, and mixed-signal verification release gate**

## Deferred reusable library roadmap

No official reusable model/component library or production plugin is implemented by Increment 115. Increments 116-123 define the future core register-factory and qualification track; they do not populate `libraries/` yet. After the core API, extension surface, packaging model, and preview release are proven, independently approved library/plugin roadmaps may populate `libraries/`, `plugins/`, or separate repositories while preserving the public-core dependency contract.

## Roadmap maintenance

When an increment is completed:

1. Change only that increment from `[ ]` to `[x]`.
2. Add implementation PR/commit and reproducible evidence links.
3. Record approved scope changes in a versioned ADR or design gate.
4. Recommend the first unchecked prerequisite unless an explicitly independent increment is selected.
5. Never mark completion from generated output alone; retain source, tests, diagnostics, and commands.

## References

- Scala 3 releases: <https://www.scala-lang.org/download/>
- Mill: <https://mill-build.org/>
- MLIR dialect definitions: <https://mlir.llvm.org/docs/DefiningDialects/>
- MLIR pass plugin API: <https://github.com/llvm/llvm-project/blob/main/mlir/include/mlir/Tools/Plugins/PassPlugin.h>
- MLIR dialect plugin API: <https://github.com/llvm/llvm-project/blob/main/mlir/include/mlir/Tools/Plugins/DialectPlugin.h>
- CIRCT dialects: <https://circt.llvm.org/docs/Dialects/>
- Chisel naming and helper-function prefixes: <https://www.chisel-lang.org/docs/explanations/naming>
- Chisel modules and implicit clock/reset: <https://www.chisel-lang.org/docs/explanations/modules>
- Chisel sequential circuits: <https://www.chisel-lang.org/docs/explanations/sequential-circuits>
- Chisel multiple clock domains: <https://www.chisel-lang.org/docs/explanations/multi-clock>
- Chisel reset semantics: <https://www.chisel-lang.org/docs/explanations/reset>
- Chisel enums: <https://www.chisel-lang.org/docs/explanations/chisel-enum>
- Chisel FSM cookbook: <https://www.chisel-lang.org/docs/cookbooks/cookbook#how-do-i-create-a-finite-state-machine-fsm>
- SpinalHDL enums: <https://spinalhdl.github.io/SpinalDoc-RTD/master/SpinalHDL/Data%20types/enum.html>
- SpinalHDL FSM library: <https://spinalhdl.github.io/SpinalDoc-RTD/master/SpinalHDL/Libraries/fsm.html>
- SpinalHDL clock domains: <https://spinalhdl.github.io/SpinalDoc-RTD/dev/SpinalHDL/Structuring/clock_domain.html>
- SpinalHDL clock-crossing diagnostics: <https://spinalhdl.github.io/SpinalDoc-RTD/master/SpinalHDL/Design%20errors/clock_crossing_violation.html>
- Chisel `Pipe`, `ShiftRegister`, `Queue`, and ready/valid API: <https://www.chisel-lang.org/api/latest/chisel3/util/>
- SpinalHDL pipeline library: <https://spinalhdl.github.io/SpinalDoc-RTD/master/SpinalHDL/Libraries/Pipeline/index.html>
- CIRCT pipeline dialect: <https://circt.llvm.org/docs/Dialects/Pipeline/>
- CIRCT ESI channel buffers: <https://circt.llvm.org/docs/Dialects/ESI/>
- Accellera SystemVerilog arrays and ports: <https://www.accellera.org/images/eda/vlog-pp/0438.html>
- Yosys arrays and memories: <https://yosyshq.readthedocs.io/projects/yosys/en/stable/CHAPTER_Basics.html>
- SpinalHDL design errors: <https://spinalhdl.github.io/SpinalDoc-RTD/master/SpinalHDL/Design%20errors/index.html>
- CIRCT passes and combinational-cycle checks: <https://circt.llvm.org/docs/Passes/>
- SpinalHDL formal verification: <https://spinalhdl.github.io/SpinalDoc-RTD/master/SpinalHDL/Formal%20verification/index.html>
- CIRCT Verif dialect: <https://circt.llvm.org/docs/Dialects/Verif/>
- CIRCT LTL dialect: <https://circt.llvm.org/docs/Dialects/LTL/>
- Chisel width inference: <https://www.chisel-lang.org/docs/explanations/width-inference>
- Chisel connectable API: <https://www.chisel-lang.org/docs/explanations/connectable>
- SpinalHDL streams: <https://spinalhdl.github.io/SpinalDoc-RTD/master/SpinalHDL/Libraries/stream.html>
- SpinalHDL SystemVerilog Interface/modport support: <https://spinalhdl.github.io/SpinalDoc-RTD/master/SpinalHDL/Structuring/interfacing_with_sv.html>
- SpinalHDL Analog/inout support: <https://spinalhdl.github.io/SpinalDoc-RTD/master/SpinalHDL/Other%20language%20features/analog_inout.html>
- SpinalHDL TriState guidance: <https://spinalhdl.github.io/SpinalDoc-RTD/master/SpinalHDL/Libraries/IO/tristate.html>
- Verilator guide: <https://verilator.org/guide/latest/>
- Icarus Verilog flags: <https://steveicarus.github.io/iverilog/usage/command_line_flags.html>
- Yosys Verilog frontend: <https://yosyshq.readthedocs.io/projects/yosys/en/stable/cmd/index_frontends.html>
- SBY formal verification: <https://yosyshq.readthedocs.io/projects/sby/en/stable/>
- cocotb simulator support: <https://docs.cocotb.org/en/stable/simulator_support.html>
- nextpnr portable FPGA place and route: <https://github.com/YosysHQ/nextpnr>
- Yosys FPGA synthesis documentation: <https://yosyshq.readthedocs.io/projects/yosys/en/stable/>
- VexiiRiscv plugin host: <https://github.com/SpinalHDL/VexiiRiscv/blob/dev/src/main/scala/vexiiriscv/VexiiRiscv.scala>
- VexiiRiscv typed plugin services: <https://github.com/SpinalHDL/VexiiRiscv/blob/dev/src/main/scala/vexiiriscv/execute/BranchPlugin.scala>
- SpinalHDL PluginHost: <https://github.com/SpinalHDL/SpinalHDL/blob/dev/lib/src/main/scala/spinal/lib/misc/plugin/Host.scala>
- SpinalHDL FiberPlugin lifecycle: <https://github.com/SpinalHDL/SpinalHDL/blob/dev/lib/src/main/scala/spinal/lib/misc/plugin/Fiber.scala>
- MLIR pass plugin API: <https://github.com/llvm/llvm-project/blob/main/mlir/include/mlir/Tools/Plugins/PassPlugin.h>
- MLIR dialect plugin API: <https://github.com/llvm/llvm-project/blob/main/mlir/include/mlir/Tools/Plugins/DialectPlugin.h>
- Verilog-AMS standards: <https://accellera.org/downloads/standards/v-ams>
- IEEE SystemVerilog 1800-2023 via Accellera/IEEE: <https://www.accellera.org/downloads/ieee>
- Accellera UVM / IEEE 1800.2 reference implementation: <https://www.accellera.org/downloads/standards/uvm>
- Accellera UVM-MS 1.0: <https://www.accellera.org/downloads/standards/uvm-ms>
- SystemVerilog-AMS working group: <https://accellera.org/activities/working-groups/systemverilog-ams>
