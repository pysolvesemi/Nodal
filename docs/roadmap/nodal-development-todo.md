# Nodal Incremental Development TODO

**Revision:** 1.46
**Created:** 2026-08-20
**Updated:** 2026-09-04
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


## Phase 0 — Repository, toolchains, architecture, and API contract

- [x] **Increment 0 — Roadmap bootstrap**
  - Add this checkbox roadmap, fixed architecture, milestones, and repository structure.

- [x] **Increment 1 — Project charter and standards baseline**
  - Establish goals, non-goals, terminology, Verilog-AMS 2023 baseline, Verilog-A profile, and core/library scope.
  - Evidence: [`README.md`](../../README.md) and commit [`cb4c55f`](https://github.com/pysolvesemi/Nodal/commit/cb4c55f2e12305be3e92b66df0b499b1d4932c2c).

- [x] **Increment 2 — Architecture decision records**
  - Record the Scala/native split, authoritative MLIR, out-of-tree dialect, selective CIRCT reuse, textual bridge, backend profiles, and core/library boundary.
  - Evidence: [`docs/architecture/README.md`](../architecture/README.md) and commit [`8052eca`](https://github.com/pysolvesemi/Nodal/commit/8052eca5de87244566f84d7d776d513991ef2e83).

- [x] **Increment 3 — Scalable repository skeleton**
  - Create required core modules and enforce architecture/dependency boundaries without populating optional libraries.
  - Evidence: [`core/modules.toml`](../../core/modules.toml), [`scripts/check_architecture.py`](../../scripts/check_architecture.py), [`tests/architecture/`](../../tests/architecture/), and commit [`300d389`](https://github.com/pysolvesemi/Nodal/commit/300d38949d2152af1c310caf528d0aea81eb0063).

- [x] **Increment 4 — Modern Scala 3 build bootstrap**
  - Pin Scala 3, JDK, Mill, API/frontend/bridge/CLI/test modules, and a smoke test; no Scala 2 cross-build.
  - Evidence: [`build.mill`](../../build.mill), [`docs/development/scala-build.md`](../development/scala-build.md), commit [`95b8c91`](https://github.com/pysolvesemi/Nodal/commit/95b8c9155bb92890c04c9e42b68fdf7677432a10), and run [`32350691664`](https://github.com/pysolvesemi/Nodal/actions/runs/32350691664).

- [x] **Increment 5 — LLVM/MLIR/CIRCT toolchain lock**
  - Pin compatible native revisions, checksums, requirements, discovery, and source-build fallback.
  - Evidence: [`toolchains/lock.json`](../../toolchains/lock.json), [`toolchains/README.md`](../../toolchains/README.md), [`scripts/check_native_toolchain.py`](../../scripts/check_native_toolchain.py), commit [`7ea7c2b`](https://github.com/pysolvesemi/Nodal/commit/7ea7c2b6ea8993f9de58e528cc916d7ac7655272), and run [`32355110533`](https://github.com/pysolvesemi/Nodal/actions/runs/32355110533).

- [x] **Increment 6 — Native compiler bootstrap**
  - Build the out-of-tree native project and `nodalc --version` plus native unit tests without language semantics.
  - Evidence: [`CMakeLists.txt`](../../CMakeLists.txt), [`core/compiler/tools/nodalc/`](../../core/compiler/tools/nodalc/), [`docs/development/native-compiler.md`](../development/native-compiler.md), commits [`72f1d8e`](https://github.com/pysolvesemi/Nodal/commit/72f1d8e3603e94dfd1c22a509b6d9c4438cbac2f) and [`1f4a785`](https://github.com/pysolvesemi/Nodal/commit/1f4a785efd99a3d6c2e5b10bb1dd61d2bb9739e8), and run [`32359466870`](https://github.com/pysolvesemi/Nodal/actions/runs/32359466870).

- [x] **Increment 7 — Unified developer commands**
  - Provide stable local/CI commands for bootstrap, Scala, native, checks, clean, diagnostics, and reserved library namespaces.
  - Evidence: [`nodal`](../../nodal), [`scripts/nodal.py`](../../scripts/nodal.py), [`docs/development/commands.md`](../development/commands.md), commits [`f24a074`](https://github.com/pysolvesemi/Nodal/commit/f24a07461d075a16c9967bdec37fb551d5f66f05) and [`080cfd3`](https://github.com/pysolvesemi/Nodal/commit/080cfd38f64e06751ece5c7ca0c432575db4c2fc), and run [`32367451896`](https://github.com/pysolvesemi/Nodal/actions/runs/32367451896).

- [x] **Increment 8 — Continuous integration baseline**
  - Add Scala/native/contracts CI, caching, dependency reports, and core/library boundary enforcement.
  - Evidence: [`.github/workflows/ci.yml`](../../.github/workflows/ci.yml), [`tests/ci/`](../../tests/ci/), [`docs/development/ci.md`](../development/ci.md), commit [`69c31a5`](https://github.com/pysolvesemi/Nodal/commit/69c31a5f4340688dff01a41911ca0a885153bb11), and run [`32396716336`](https://github.com/pysolvesemi/Nodal/actions/runs/32396716336).

- [x] **Increment 9 — Formatting, linting, and contribution rules**
  - Add pinned Scala/native/Markdown formatting, package visibility, PR/branch policy, and design-gate enforcement.
  - Evidence: [`.scalafmt.conf`](../../.scalafmt.conf), [`.scalafix.conf`](../../.scalafix.conf), [`.clang-format`](../../.clang-format), [`.clang-tidy`](../../.clang-tidy), [`CONTRIBUTING.md`](../../CONTRIBUTING.md), [`scripts/check_increment9.py`](../../scripts/check_increment9.py), and run [`32444753017`](https://github.com/pysolvesemi/Nodal/actions/runs/32444753017).

- [x] **Increment 10 — Public API candidate prototypes**
  - Compile non-functional analog, hierarchy, mixed-signal, parameter, event, and external-library candidates against public core APIs.
  - Evidence: [`CandidateApi.scala`](../../core/scala/api/src/nodal/CandidateApi.scala), [`examples/publicApiCandidates/`](../../examples/publicApiCandidates/), [`examples/externalLibrary/`](../../examples/externalLibrary/), [`NodalPublicApiCandidates-DG-v0.1.md`](../design-gates/NodalPublicApiCandidates-DG-v0.1.md), and runs [`32447235052`](https://github.com/pysolvesemi/Nodal/actions/runs/32447235052) and [`32447239115`](https://github.com/pysolvesemi/Nodal/actions/runs/32447239115).

- [x] **Increment 11 — Public API design gate and v0.1 freeze**
  - Freeze the initial public API, native parameterized HDL contract, backend entry points, compatibility policy, and future library-author subset.
  - Evidence: [`NodalPublicApi-DG-v0.1.md`](../design-gates/NodalPublicApi-DG-v0.1.md), [`public-api-v0.1.json`](../../core/scala/api/public-api-v0.1.json), [`public-api-v0.1.md`](../language-reference/public-api-v0.1.md), [`CompilerApi.scala`](../../core/scala/api/src/nodal/CompilerApi.scala), [`scripts/check_increment11.py`](../../scripts/check_increment11.py), and run [`32455056652`](https://github.com/pysolvesemi/Nodal/actions/runs/32455056652).

- [x] **Increment 12 — Clock/reset public API v0.2 freeze and contract fixtures**
  - Use [ADR 0007](../architecture/0007-implicit-clock-reset-domains.md), [`clock-reset-api-v0.2-plan.md`](clock-reset-api-v0.2-plan.md), and [`clock-reset-api-v0.2-surface.json`](clock-reset-api-v0.2-surface.json) as the mandatory architecture and API candidate.
  - Compile candidates for `Clock`, `Reset`, `ClockDomain.external/from/required/generated`, lexical domain application, `Reg`, `Reg.uninitialized`, `RegNext`, `when`/`elsewhen`/`otherwise`, typed `.domain(...)`, `ResetPolicy`, `ClockRelation`, semantic `Cdc`/`Rdc`, `ClockGate`, `ClockMux`, and the quarantined `nodal.lowlevel.process(event)` escape.
  - Publish `NodalClockResetApi-DG-v0.2.md`, a v0.1-to-v0.2 migration note, and an updated machine-readable public API manifest. Supersede `always(clock.rising)` only for ordinary synchronous state; retain genuine analog/event semantics.
  - Add compile-positive fixtures for single/multiple/generated domains, every reset policy, legal level/Gray/pulse/handshake/FIFO/reset crossings, gates/muxes, analog-event separation, and an external-library consumer.
  - Add compile-negative fixtures for missing domains, direct CDC, multi-bit `Cdc.sync`, unsafe pulses, unsupported relationship assumptions, reset-release/reconvergence hazards, Boolean clocks, ordinary `always`, low-level misuse, and ambiguous/multiple state drivers. Freeze stable diagnostic codes and source locations.
  - Keep frontend/backend semantics inert. Mark this increment `[x]` only after every freeze exit criterion in the detailed plan passes CI.
  - Evidence: [`NodalClockResetApi-DG-v0.2.md`](../design-gates/NodalClockResetApi-DG-v0.2.md), [`public-api-v0.2.json`](../../core/scala/api/public-api-v0.2.json), [`clock-reset-diagnostics-v0.2.json`](../../core/scala/api/clock-reset-diagnostics-v0.2.json), [`tests/api/fixtures/increment12/manifest.json`](../../tests/api/fixtures/increment12/manifest.json), [`scripts/check_increment12.py`](../../scripts/check_increment12.py), and status `increment-12/clock-reset-api-v0-2`.
  - Freeze baseline: roadmap **Revision:** 1.12; later roadmap revisions must preserve this completed increment and its evidence.


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

## Phase 1 — Compiler vertical slice

- [x] **Increment 16 — Elaboration, hierarchy, shape, and lexical domain-context kernel**
  - Add deterministic `Struct`/`Interface` kind ownership, interface construction close, exported-role requirements, recursive role expansion, interface storage rejection, resolved-net endpoint registration, conservative-terminal topology ownership, and logical Interface ABI paths without globals or JVM identity.
  - Implement deterministic module construction, ownership, lifecycle, shaped-value rank/dimension capture, structural `Vec` versus `Mem` intent, transactional construction close, default-domain requirements, lexical domain stack, single-domain inheritance, named multi-domain requirements, typed bindings, and root-domain validation without public Scala implicits, globals, thread-locals, or JVM identity.
  - Evidence: PR [#41](https://github.com/pysolvesemi/Nodal/pull/41), dedicated validation run [32693824293](https://github.com/pysolvesemi/Nodal/actions/runs/32693824293), and Core CI run [32693824396](https://github.com/pysolvesemi/Nodal/actions/runs/32693824396).

- [x] **Increment 17 — Source spans, semantic naming, and origin graph**
  - Capture Scala declaration/member names and expression spans; build stable origin/sink-affinity metadata; define deterministic names for modules, declarations, shaped elements/views, domains, generated clock/reset ports, synchronizers, FIFOs, reset controllers, crossings, pipeline/FSM state, anonymous registers, and required temporaries. Prohibit traversal-counter-only normal names and retain expression-level source maps when nodes are inlined.
  - Evidence: [`SemanticOriginKernel.scala`](../../core/scala/api/src/nodal/SemanticOriginKernel.scala), [`SemanticOriginTests.scala`](../../core/scala/testkit/test/src/nodal/SemanticOriginTests.scala), [`NodalSemanticOriginNaming-DG-v1.0.md`](../design-gates/NodalSemanticOriginNaming-DG-v1.0.md), PR [#42](https://github.com/pysolvesemi/Nodal/pull/42), dedicated validation run [32722646172](https://github.com/pysolvesemi/Nodal/actions/runs/32722646172), and Core CI run [32722646224](https://github.com/pysolvesemi/Nodal/actions/runs/32722646224).

- [x] **Increment 18 — Nodal MLIR dialect skeleton**
  - Register the out-of-tree dialect, TableGen organization/docs, generic parser/printer, and a verified placeholder operation.
  - Evidence: [`NodalMlirDialectSkeleton-DG-v1.0.md`](../design-gates/NodalMlirDialectSkeleton-DG-v1.0.md), [`increment18-mlir-dialect-skeleton.md`](../implementation/increment18-mlir-dialect-skeleton.md), PR [#45](https://github.com/pysolvesemi/Nodal/pull/45), dedicated validation run [32767361722](https://github.com/pysolvesemi/Nodal/actions/runs/32767361722), and Core CI run [32767361651](https://github.com/pysolvesemi/Nodal/actions/runs/32767361651).

- [x] **Increment 19 — Core MLIR module, port, parameter, and domain model**
  - Add canonical Interface IR definitions/instances/roles/member access, full `Valid`/`Stream` channel identity, logical interface ABI metadata, digital resolved-net/read/driver/drive-mode operations, conservative terminal/node/branch/access operations, and explicit mixed-signal bridge operations while keeping target layouts separate.
  - Add target-neutral modules, ports, symbols, instances, symbolic parameters, signless/unsigned/signed finite-width types and constants, ranked shaped types with symbolic dimensions, canonical index/flatten/layout and structural-storage metadata, expression origin/materialization/observability metadata, structural generate regions, bounded hardware-iteration regions with typed induction variables/effects, semantic enum types/cases/canonical encodings, FSM definitions/regions/states/transitions/actions/completion/encoding policies, domain requirements/bindings, clock/reset relationships, state ownership, timing provenance, and crossing operations/types. Reuse CIRCT only after semantic comparison.
  - Evidence: [`NodalCoreMlirModel-DG-v1.0.md`](../design-gates/NodalCoreMlirModel-DG-v1.0.md), [`increment19-core-mlir-model.md`](../implementation/increment19-core-mlir-model.md), PR [#46](https://github.com/pysolvesemi/Nodal/pull/46), dedicated validation run [32829155720](https://github.com/pysolvesemi/Nodal/actions/runs/32829155720), and Core CI run [32829155633](https://github.com/pysolvesemi/Nodal/actions/runs/32829155633).

- [x] **Increment 20 — Scala-to-MLIR bridge**
  - Lower deterministic construction state to versioned textual MLIR with source locations and invoke `nodalc` through a clear process protocol.

- [x] **Increment 21 — Native parse, staged semantic verification, and pass pipeline**
  - Parse Nodal MLIR; implement mandatory construction-closure, driver/assignment coverage, latch, combinational-cycle, hierarchy, width/sign/shape/layout/storage, parameter/generate/loop, enum/FSM, clock/reset/CDC/RDC, protocol/pipeline, memory/effect, analog/mixed-signal, and target-capability verifiers; run registered passes with analysis invalidation/reverification; print normalized IR; and expose explicit lit/FileCheck-friendly gate pipelines. Preserve the last accepted state transactionally on failure.
  - Evidence: [`NodalNativeSemanticPipeline-DG-v1.0.md`](../design-gates/NodalNativeSemanticPipeline-DG-v1.0.md), [`increment21-native-semantic-pipeline.md`](../implementation/increment21-native-semantic-pipeline.md), implementation PR [#50](https://github.com/pysolvesemi/Nodal/pull/50), closure PR [#51](https://github.com/pysolvesemi/Nodal/pull/51), dedicated validation run [32884043819](https://github.com/pysolvesemi/Nodal/actions/runs/32884043819), and Core CI run [32884043761](https://github.com/pysolvesemi/Nodal/actions/runs/32884043761).

- [x] **Increment 22 — Cross-layer diagnostic mapping**
  - Include stable interface/role/inout/AMS codes for unstorable interfaces, missing roles/members, incompatible roles, monitor drive, invalid inversion, multiple ordinary drivers, illegal open-drain drive, unsupported resolution, hierarchy-pass-through failure, discipline/access mismatch, implicit bridge conversion, and interface-layout collisions.
  - Map construction, driver/latch/cycle/hierarchy, shape/rank/layout/storage/index, materialization/naming/source-span, parser, verifier, pass, backend, external-tool, signed literal/conversion/mixed-sign/width/shift, loop stage/bound/body/dependency/effect/profile, enum encoding/decode/exhaustiveness, FSM graph/transition/recursion/illegal-state, domain-binding, CDC, RDC, gate/mux, protocol/pipeline, memory/effect, analog/mixed-signal, and waiver diagnostics back to Scala locations, hierarchy/index paths, and stable codes.
  - Evidence: [`NodalCrossLayerDiagnostics-DG-v1.0.md`](../design-gates/NodalCrossLayerDiagnostics-DG-v1.0.md), [`increment22-cross-layer-diagnostic-mapping.md`](../implementation/increment22-cross-layer-diagnostic-mapping.md), PR [#53](https://github.com/pysolvesemi/Nodal/pull/53), dedicated validation run [32944621396](https://github.com/pysolvesemi/Nodal/actions/runs/32944621396), and Core CI run [32944621448](https://github.com/pysolvesemi/Nodal/actions/runs/32944621448).

- [x] **Increment 23 — Backend framework and capability profiles**
  - Add translation registration, deterministic output handling, profile-owned shaped-value layouts, expression materialization/naming and CheckProfile configuration, transactional target verification/reparse hooks, `verilog-a`/`verilog-ams` profiles, and explicit unsupported-feature errors.
  - Evidence: [`NodalBackendFramework-DG-v1.0.md`](../design-gates/NodalBackendFramework-DG-v1.0.md), [`increment23-backend-framework.md`](../implementation/increment23-backend-framework.md), implementation PR [#61](https://github.com/pysolvesemi/Nodal/pull/61), closure PR [#63](https://github.com/pysolvesemi/Nodal/pull/63), dedicated validation run [32966834961](https://github.com/pysolvesemi/Nodal/actions/runs/32966834961), and Core CI run [32966835105](https://github.com/pysolvesemi/Nodal/actions/runs/32966835105).

- [x] **Increment 24 — Minimal analog expression and contribution IR**
  - Add real literals, parameter references, arithmetic, electrical potential access, analog region, and contribution sufficient for a minimal RC equation.
  - Evidence: [`NodalMinimalAnalogIr-DG-v1.0.md`](../design-gates/NodalMinimalAnalogIr-DG-v1.0.md), [`increment24-minimal-analog-ir.md`](../implementation/increment24-minimal-analog-ir.md), implementation PR [#66](https://github.com/pysolvesemi/Nodal/pull/66), dedicated validation run [33039547022](https://github.com/pysolvesemi/Nodal/actions/runs/33039547022), and Core CI run [33039546995](https://github.com/pysolvesemi/Nodal/actions/runs/33039546995).

- [x] **Increment 25 — RC filter end-to-end vertical slice**
  - Compile Scala RC through construction, Nodal MLIR, verification, and Verilog-A emission with exact golden output and failures.

- [x] **Increment 26 — Deterministic output and reproducibility contract**
  - Prove byte-identical MLIR, HDL, shape/layout and storage manifests, materialization decisions/reasons, semantic names, expression source maps, check inventories/waivers, domain manifests, and CDC/RDC reports across repeated builds and valid traversal orders.
  - Evidence: [`NodalReproducibilityContract-DG-v1.0.md`](../design-gates/NodalReproducibilityContract-DG-v1.0.md), [`increment26-reproducibility-contract.md`](../implementation/increment26-reproducibility-contract.md), implementation PR [#69](https://github.com/pysolvesemi/Nodal/pull/69), dedicated validation run [33078619538](https://github.com/pysolvesemi/Nodal/actions/runs/33078619538), and Core CI run [33078619501](https://github.com/pysolvesemi/Nodal/actions/runs/33078619501).

## Phase 2 — Analog language and Verilog-A profile

**Phase 2 dependency gate:** Increment 32 must not begin until the equation/component checkpoint of Increment 133 is approved. That checkpoint freezes unordered equation semantics; equation, contribution, and procedural-assignment separation; conservative connection equations; terminal/branch and partial/concrete component contracts; local balance; structural parameters; initialization equations; and unsupported-target behavior. The remaining analysis, PVT, noise, validity, and solver-hint portions of Increment 133 may complete later without weakening this prerequisite.

- [x] **Increment 27 — Natures and disciplines**
  - Implement units, access functions, tolerances, domains, potential/flow associations, declarations, imports, and compatibility.

- [x] **Increment 28 — Electrical nodes, nets, and branches**
  - Implement scalar conservative terminals/nodes, ground/reference behavior, implicit/named branches, port directions, aliases, connection-set identity, branch orientation, ownership, and source hierarchy.
  - Generate compatible-potential equality and signed zero-sum flow-conservation equations from connection sets, retaining provenance for later residual construction and target lowering.
  - Define partial versus concrete physical-component connectivity ownership without treating conservative terminals as directional signal flow.

- [x] **Increment 29 — Parameters, constants, ranges, and units**
  - Implement supported parameter kinds, constraints, constant expressions, overrides, unit-aware literals, and lossless native HDL rendering.
  - Classify ordinary parameters, structural parameters, and dynamic values; require topology, component count, equation count, shape, or rank changes to be elaboration-time or static target generation, and diagnose unsupported parameter-envelope structural changes.
  - Evidence: implementation PR [#78](https://github.com/pysolvesemi/Nodal/pull/78), dedicated validation run [33154887196](https://github.com/pysolvesemi/Nodal/actions/runs/33154887196), merge commit [`09ffbf34`](https://github.com/pysolvesemi/Nodal/commit/09ffbf344a4bf2ee8f6b2fb16ba3272669f47f27), and Core CI run [33154887245](https://github.com/pysolvesemi/Nodal/actions/runs/33154887245).

- [x] **Increment 30 — Analog numeric types and expression typing**
  - Define promotion, physical compatibility, comparisons/logical results, conditionals, invalid operations, and folding boundaries.
  - Evidence: implementation PR [#80](https://github.com/pysolvesemi/Nodal/pull/80), dedicated validation run [33192880165](https://github.com/pysolvesemi/Nodal/actions/runs/33192880165), merge commit [`401f78b3`](https://github.com/pysolvesemi/Nodal/commit/401f78b3836cc4e52d393ef343dc0915d60606e9), and Core CI run [33192880254](https://github.com/pysolvesemi/Nodal/actions/runs/33192880254).

- [x] **Increment 31 — Potential and flow access functions**
  - Implement `V`, `I`, discipline-specific access, one/two-node forms, branches, probes, and validation.
  - Evidence: implementation PR [#88](https://github.com/pysolvesemi/Nodal/pull/88), original draft PR [#87](https://github.com/pysolvesemi/Nodal/pull/87), dedicated validation run [33244625475](https://github.com/pysolvesemi/Nodal/actions/runs/33244625475), merge commit [`1662b79f`](https://github.com/pysolvesemi/Nodal/commit/1662b79f5f99686de4af2ed8a016fe8acf5c784e), and Core CI run [33244625490](https://github.com/pysolvesemi/Nodal/actions/runs/33244625490).

- [x] **Increment 32 — First-class analog equations, blocks, and contribution semantics**
  - Implement analog regions, unordered first-class equations, and `<+` potential/flow contributions as distinct source-semantic operations; keep both distinct from Increment 33 procedural assignment.
  - Preserve authored equation sides, stable equation identity, physical dimensions, guards, analysis applicability, and canonical residual intent without premature causal orientation or unsafe algebraic division.
  - Define additive contribution accumulation, source-order independence, equation/contribution interaction, illegal procedural use, and stable diagnostics.
  - Require the approved equation/component checkpoint from Increment 133 before implementation begins.
  - Evidence: implementation PR [#97](https://github.com/pysolvesemi/Nodal/pull/97), accepted head [`6a76516a`](https://github.com/pysolvesemi/Nodal/commit/6a76516aba541ead97205e937118bb0f689fcd98), dedicated validation run [33370821599](https://github.com/pysolvesemi/Nodal/actions/runs/33370821599), merge commit [`e9ea39e8`](https://github.com/pysolvesemi/Nodal/commit/e9ea39e823d5a226a65b952e176d3bb90ecda0aa), and post-merge Core CI run [33372029305](https://github.com/pysolvesemi/Nodal/actions/runs/33372029305).

- [x] **Increment 33 — Analog variables and procedural assignment**
  - Implement local variables, initialization, procedural assignment, scopes, read-before-write diagnostics, and lowering.

  - Evidence: implementation PR [#102](https://github.com/pysolvesemi/Nodal/pull/102), accepted head [`ea7f7da5`](https://github.com/pysolvesemi/Nodal/commit/ea7f7da51e85ba275dac71db7823ba0223f8d4ac), dedicated boundary run [33592719238](https://github.com/pysolvesemi/Nodal/actions/runs/33592719238), merge commit [`2e0ff291`](https://github.com/pysolvesemi/Nodal/commit/2e0ff291b8d6c0f6dcc4b4c8e27cc33984cff1b8), post-merge Core CI run [33605996500](https://github.com/pysolvesemi/Nodal/actions/runs/33605996500), and exact post-merge validation run [33714669557](https://github.com/pysolvesemi/Nodal/actions/runs/33714669557).

- [x] **Increment 34 — Analog control flow**
  - Implement conditionals, case, bounded loops, break/continue where supported, and static/runtime legality.

  - Evidence: implementation PR [#109](https://github.com/pysolvesemi/Nodal/pull/109), accepted head [`207fd1b5`](https://github.com/pysolvesemi/Nodal/commit/207fd1b580e9428e9948cd4e4bd8f2060fde4b79), 26-workflow exact-head matrix, Core CI run [33732864482](https://github.com/pysolvesemi/Nodal/actions/runs/33732864482), merge commit [`a9d3ec50`](https://github.com/pysolvesemi/Nodal/commit/a9d3ec50799953c41e7b9cf1d8bd6a2c5c9afd49), post-merge Core CI run [33758905273](https://github.com/pysolvesemi/Nodal/actions/runs/33758905273), exact post-merge validation run [33759112770](https://github.com/pysolvesemi/Nodal/actions/runs/33759112770), and evidence-closure PR [#111](https://github.com/pysolvesemi/Nodal/pull/111) validated from [`b59ed10f`](https://github.com/pysolvesemi/Nodal/commit/b59ed10f423d4a66e7e47d66ec764b7ff22531e7) by run [33761024228](https://github.com/pysolvesemi/Nodal/actions/runs/33761024228).

- [x] **Increment 35 — Differential and integral operators**
  - Implement `ddt`, `idt`, initial conditions, context restrictions, and semantics-preserving simplification.
  - Evidence: implementation PR [#113](https://github.com/pysolvesemi/Nodal/pull/113), accepted head [`d3410f6f`](https://github.com/pysolvesemi/Nodal/commit/d3410f6f64dc66df27d9c7f545c9e78f62695f2e), 25-workflow exact-head matrix, Core CI run [33890457304](https://github.com/pysolvesemi/Nodal/actions/runs/33890457304), merge commit [`7763e152`](https://github.com/pysolvesemi/Nodal/commit/7763e1524f31e4c2c41b11acb200670c360f0fde), post-merge Core CI run [33892575717](https://github.com/pysolvesemi/Nodal/actions/runs/33892575717), exact post-merge validation run [33892632854](https://github.com/pysolvesemi/Nodal/actions/runs/33892632854), and evidence-closure PR [#114](https://github.com/pysolvesemi/Nodal/pull/114) validated from [`39915b98`](https://github.com/pysolvesemi/Nodal/commit/39915b984707f0396777cc69030dfec29aa2befe) by dedicated run [33916159555](https://github.com/pysolvesemi/Nodal/actions/runs/33916159555) and Core CI run [33916159534](https://github.com/pysolvesemi/Nodal/actions/runs/33916159534).

- [ ] **Increment 36 — Time and waveform operators**
  - Implement `transition`, `slew`, `absdelay`, `$abstime`, `$bound_step`, units, continuity, and diagnostics.

- [ ] **Increment 37 — Analog events**
  - Implement `cross`, `above`, `timer`, initial/final step, event composition, tolerances, and controlled statements.

- [ ] **Increment 38 — Mathematical and simulator functions**
  - Add a versioned registry with type/arity checking, constant evaluation, analysis queries, and backend spelling.

- [ ] **Increment 39 — Noise operators**
  - Implement white, flicker, and table noise with analysis, naming, units, and capability checks.

- [ ] **Increment 40 — Laplace and discrete transfer operators**
  - Implement supported Laplace/Z-domain forms, coefficient arrays, constant requirements, denominator validation, and emission.

- [ ] **Increment 41 — User-defined analog functions**
  - Implement typed declarations, arguments, locals, returns, recursion/overload policy, resolution, and lowering.

- [ ] **Increment 42 — Analog hierarchy and parameterized instances**
  - Implement instances, named ports, symbolic overrides, legal arrays, hierarchy verification, and recursion errors.

- [ ] **Increment 43 — Analog arrays, shaped values, and elaboration-time generation**
  - Implement legal fixed/symbolic analog arrays under ADR 0017 shape/index rules, analog-object capability restrictions, indexing/slices, Scala elaboration loops, target generate constructs, static bounds, target layout checks, and explicit rejection of illegal analog flattening or memory inference.

- [ ] **Increment 44 — Analysis state and environmental constructs**
  - Implement analysis-dependent behavior, temperature/environment access, initial/final semantics, and portability policy.

- [ ] **Increment 45 — Analog canonicalization passes**
  - Implement safe folding, residual-preserving algebraic normalization, dead declaration removal, branch/access normalization, and deterministic common-expression handling.
  - Prohibit unproved equation orientation, division by possibly zero expressions, contribution reordering, state movement, topology change, or residual elimination; retain before/after equation provenance.

- [ ] **Increment 46 — Analog semantic lint suite**
  - Detect floating nodes, discipline conflicts, branch misuse, unit errors, local/global equation imbalance, structural singularity, unsafe equation orientation or division, invalid structural-parameter envelopes, unreachable events, discontinuities, parameter risks, and portability hazards.

- [ ] **Increment 47 — Verilog-A capability profile and feature matrix**
  - Publish exact `.va` coverage, reject AMS-only constructs early, document simulator portability, and expose machine-readable features.

## Phase 3 — Open-source analog validation and testbench support

- [ ] **Increment 48 — OpenVAF compile validation**
  - Detect versions/features, compile generated models, classify expected limitations, and retain diagnostics.

- [ ] **Increment 49 — ngspice simulation harness**
  - Add OSDI loading, generated SPICE benches, transient/DC/AC runs, timeout/error handling, outputs, and CI smoke simulation.

- [ ] **Increment 50 — Scala simulation API v0.1**
  - Add compilation, source creation, clock/reset-domain stimulus, related/asynchronous clocks, analyses, sweeps, measurements, tolerances, and assertions without hiding tool evidence.

- [ ] **Increment 51 — Waveform and result model**
  - Parse typed time/frequency/sweep results, preserve units, stream large data, and provide comparison/assertion utilities.

- [ ] **Increment 52 — Analog regression suite**
  - Cover RC/RLC, diode, controlled source, amplifier, comparator, oscillator/VCO, hierarchy, events, sweeps, and failures as core fixtures.

- [ ] **Increment 53 — Cross-tool analog portability checks**
  - Add an optional second tool adapter, tolerance-based comparisons, and language-versus-tool failure classification.

## Phase 4 — Digital semantics, portable Verilog, open-source verification, mixed signal, and Verilog-AMS

- [ ] **Increment 54 — Digital signed/unsigned type, literal, native enum ABI, and port layer**
  - Implement directionless storable `Struct`, non-storable digital `Interface`, named `Role`, plain/`Valid`/`Stream` interface members, scalar/vector resolved-net types, typed digital inout endpoints, read/drive/high-impedance semantics, drive modes, and exact port/member ABI identity.
  - Add bit/logic, signless `Bits`, unsigned `UInt`, two's-complement `SInt`, exact signed/negative literals, signed parameters/localparams, signed aggregate fields and memory elements, ranked parameterized `Vec` and nested shaped values, canonical row-major indexing/flattening and exact shape connections, structural storage versus `Mem`, numeric conversion versus bit reinterpretation, integers, reals, nets/variables, directions, four-state policy, native Scala enum derivation, semantic enum types/cases, canonical sequential/one-hot/Gray/custom encodings, safe decode, exhaustive selection, enum aggregates/protocols/parameters/memories, ABI hashes, and compatible CIRCT/Nodal lowering.

- [ ] **Increment 55 — Digital expressions, bounded hardware iteration, and continuous assignments**
  - Add typed expression DAGs with arithmetic, logic, bitwise, comparisons, concatenation, extraction, conditionals, multidimensional index/slice/flatten/reshape, exact width/sign/shape and mixed-sign rules, arithmetic/logical shifts, explicit signed casts/conversions, continuous assignment, typed hardware `map`/`zip`/`reduce`/`fold`/`scan`, safe single-use inlining, shared/observable/target-required materialization with reason codes, and bounded hardware iteration with finite static/symbolic bounds, ordered effects, dependency/index/driver checks, and deterministic unrolled versus procedural-loop lowering candidates. Reject runtime trip counts, structural declarations, hidden multi-cycle behavior, unbounded/data-dependent loops, accidental flat-carrier arithmetic, latches, and combinational cycles.

- [ ] **Increment 56 — Implicit-domain registers, enum state, and flat FSM semantics**
  - Implement `Reg`, `RegNext`, reset/uninitialized state, enum registers, exhaustive switches, `when` priority, enables, manual FSMs, concise high-level flat FSMs, entry/active/exit/transition actions, exclusive/priority transitions, terminal/completion states, local compact/one-hot/Gray/custom/Auto encoding, illegal-state policies, graph diagnostics, memory-port ownership, and CIRCT/Nodal sequential lowering without exposing normal `always` syntax or hidden boot state.

- [ ] **Increment 57 — Clock/reset domains, CDC/RDC primitives, and low-level event escape**
  - Implement domain construction/application, external/default/generated binding, relationship graphs, reset policies, async-assert/sync-release, timing provenance, all semantic CDC/RDC operations, gates/muxes, waivers, and restricted low-level processes.

- [ ] **Increment 58 — Domain-aware hierarchy, reusable statecharts, and bounded recursive control**
  - Propagate selected roles, nested interface members, domain provenance, resolved-net identity, black-box/top-level inout pass-through, conservative-terminal topology, symbolic interface arrays, and stable logical-to-physical interface paths through hierarchy.
  - Implement default-domain inheritance, typed named-domain binding, inferred clock/reset ports, symbolic parameters, domain-polymorphic modules, parameterized shaped ports/instances, structural `generate` regions with symbolic bounds/nested legal generation, deterministic index-aware hierarchy and sink-affinity state naming, and deterministic variants only for material edge/reset differences. Keep ordinary Scala loops elaboration-only and preserve native target generate instead of clone-per-value specialization.
  - Implement immutable reusable `FsmDef`/fragment candidates, explicit runtime bindings, nested submachines, typed completion/cancellation, parallel join policies, timed/protocol-aware states, finite elaboration recursion, and explicit bounded call/return stack contracts with overflow/underflow, reset, domain, report, and proof metadata. Reject unbounded recursion and accidental dynamic capture.

- [ ] **Increment 59 — Pipeline transaction graph, latency provenance, and IR contract**
  - Preserve logical interface roles and ABI while extracting plain/`Valid`/`Stream` transaction graphs; protocol scheduling may insert pipeline-owned storage but cannot change role ownership, inout resolution, AMS topology, or explicit bridge semantics.
  - Represent fixed-rate, valid-only, and elastic regions as single-domain feed-forward transaction graphs with protocol tokens, transaction identity, stage/latency variables, sideband demand, reconvergence constraints, exact/ranged latency, hard anchors, reset/control policy, parameter envelopes, and operation delay/latency metadata. Document selective CIRCT reuse.

- [ ] **Increment 60 — Fixed-rate and valid-only automatic scheduling**
  - Schedule acyclic II=1 datapaths under exact/ranged/auto latency and target-period constraints; insert pipeline-owned registers, balance operands and sidebands, propagate `Valid` bubbles, preserve finite-width semantics, and emit deterministic schedules, reports, normalized IR, and golden Verilog-AMS.

- [ ] **Increment 61 — Elastic automatic pipeline and backpressure synthesis**
  - Lower `Stream[T]` regions to full-throughput ready/valid stages with elastic registers, skid buffers, registered-ready cuts, bubble/stall propagation, capacity accounting, ready-loop checks, stall-stability assertions, and proofs of no loss, duplication, or reordering.

- [ ] **Increment 62 — Timing/resource models and target-driven partitioning**
  - Add versioned generic, FPGA, ASIC, simulator, and user operation models covering width/sign-dependent delay, fixed multi-cycle latency, implementation choices, resource preferences, uncertainty, and finite parameter envelopes. Implement target scheduling with infeasibility diagnostics and optional synthesis-feedback import without claiming timing closure from estimates.

- [ ] **Increment 63 — Pipeline controls, anchors, memories, and multi-cycle units**
  - Freeze and implement typed flush/cancel/replay and commit barriers, reset/stall/enable priority, named hard cuts, same-stage groups, synchronous memory latency/ordering, fixed-latency blocks, and elastic wrappers for variable-latency units. Reject or isolate side effects that cannot move safely.

- [ ] **Increment 64 — Hierarchical composition, schedule stability, and bounded retiming**
  - Compose regions/modules through explicit latency/protocol contracts, generate stable stage names and schedule hashes, diagnose latency drift, export reports/debug mappings, and retime only pipeline-owned registers inside declared boundaries—not across user state, CDC/RDC, analog boundaries, memories, side effects, parameter-envelope barriers, or observability anchors.



- [ ] **Increment 65 — Digital-only classification, Backend.Auto, and portable Verilog backend**
  - Treat Foundation Increments 153-157 as mandatory naming prerequisites. Lower structured caller/local paths into deterministic portable-Verilog identifiers and preserve raw binders, aliases, provenance, definition/invocation locations, and materialization reasons in manifests and source maps.
  - Preserve safe expression inlining: source such as `val widenedName = a + b; out := widenedName` may emit `assign out = a + b;` when exact semantics permit, while `widenedName` remains traceable in IR/source maps and no wire is materialized solely to expose the name.
  - When materialization is required, emit caller-prefixed helper-local names such as `pixelResult_widenedSum`; repeated calls must produce semantic paths such as `leftResult_widenedSum` and `rightResult_widenedSum`. A returned value may emit as `pixelResult` while retaining `pixelResult_clippedSum` as an alias.
  - Use `_net_<operation>_<stable-index>` only for genuinely unnamed Nodal-owned combinational objects, with corresponding `_reg_*`, `_mem_*`, `_inst_*`, and `_gen_*` namespaces for other generated objects. Never emit `_zz*`, `_T*`, `_GEN*`, traversal-counter-only, `expr_<number>`, or `tmp_<number>` names for accepted Nodal-owned HDL.
  - Add exact goldens for ordinary/local/nested methods, lambdas, multiline expressions, separately compiled libraries, repeated calls, loops/generate/unrolling, sharing/CSE, backticked or reserved identifiers, collision sanitization, safe-inline/readable/debug materialization profiles, different working directories, and repeated builds. Record the selected name, original binder, aliases, provenance, materialization reason, sanitization, and collision qualification.
  - Deterministically flatten nested `Interface`/`Struct`/`Valid`/`Stream` members, emit logical Interface ABI/source-map manifests, and lower supported digital inout to net-typed ports plus explicit width-safe tri-state assignments. Reject analog members and profile-unsupported internal resolved nets without silent mux conversion.
  - Implement transitive digital-only/analog-only/mixed-signal classification, construct inventories, deterministic `Backend.Auto` selection, explicit capability rejection, and machine-readable selection evidence.
  - Emit the portable synthesizable Verilog profile with exact signed vector ports/wires/registers/parameters/localparams/memories/aggregate fields, explicitly sized signed literals, typed shifts/casts, parameterized multidimensional `Vec` ports as canonical flat packed carriers, verified row-major offset/slice/reshape formulas, deterministic signed element views, structural `Vec` versus `Mem` evidence, structural `genvar` generate loops, bounded procedural `for` loops or verified unrolled equivalents, symbolic parameters/generate, hierarchy, flattened aggregates/protocols, canonical enum vectors and member `localparam`s, enum configuration parameters, flat/hierarchical/parallel FSM state and completion logic, clocks/resets, memories, CDC/RDC, automatic pipelines, black boxes, explicitly synthesized immediate assertions, verification-only formal hooks, safe expression inlining and semantic temporary/state naming, materialization/shape/storage/signed/loop/enum/FSM manifests, expression-level source maps, deterministic formatting, target reparse, and exact golden fixtures.
  - Concurrent or temporal properties and compiler-generated verification monitors are excluded from `digital-verilog-synth`; they remain formal, simulation, or sidecar artifacts.
  - Keep broad SystemVerilog optional and separately gated; portable Verilog remains required for open-source interoperability.

- [ ] **Increment 66 — Open-source digital lint, simulation, waveforms, and cocotb interoperability**
  - Add typed interface-role drivers/monitors and digital inout high-Z/readback/contention/open-drain/hierarchy tests, with logical Interface ABI metadata for Scala simulation, cocotb, waveforms, and source correlation.
  - Pin and integrate Verilator and Icarus Verilog; run independent parse/elaboration, strong lint, fast compiled simulation, event-driven smoke simulation, normalized diagnostics, deterministic seeds, VCD/FST waveforms, supported coverage, multidimensional flat-layout/index/reshape fixtures, signed-element-view tests, no-avoidable-anonymous-wire goldens, and source-map correlation for inlined expressions.
  - Extend the Scala simulation API with typed signed/unsigned/bit-container and aggregate/protocol access, clock/reset-domain stimulus, multiple clocks, randomized reset release, `Valid`/`Stream` drivers/monitors/scoreboards, signed boundary/shift/comparison checks, procedural-versus-unrolled loop differential fixtures, stalls/bubbles, latency-aware checking, timeouts, caching, and artifacts.
  - Add optional cocotb metadata/runner support for Icarus and Verilator without making Python or cocotb define Nodal semantics.

- [ ] **Increment 67 — Yosys synthesis/equivalence and core SBY formal-readiness infrastructure**
  - Verify flattened interface connectivity, full `Valid`/`Stream` ownership, top-level/black-box tri-state synthesis where supported, split-tristate boundary equivalence, internal resolved-net capability rejection, driver exclusivity assumptions, and native-versus-flat interface parity hooks.
  - Pin and integrate Yosys, SBY, and selected solvers. Run hierarchy/process/memory/driver checks, target-neutral synthesis, inferred-latch/combinational-loop/black-box diagnostics, structural-`Vec` unexpected-memory-inference audit, normalized netlist emission, statistics, and parameter/shape/layout/generate elaboration matrices.
  - Add RTL-to-optimized/netlist equivalence, including signed width/extension/cast/shift checks, multidimensional flatten/unpack/index/reshape and inline-versus-debug materialization equivalence, generate/procedural/unrolled-loop equivalence and index-bound properties, latency-aware fixed-pipeline, and protocol-aware elastic checks.
  - Add compiler-generated bounded/unbounded safety, cover, and selected liveness property suites for registers, resets, `Valid`/`Stream`, FIFOs, handshakes, synchronizers, CDC/RDC wrappers, and automatic pipelines. Retain traces and counterexamples as CI evidence.
  - Preserve stable property IDs, source maps, domain/reset/parameter metadata, normalized tasks, and adapter evidence in forms compatible with [ADR 0014](../architecture/0014-target-neutral-formal-verification.md). Generate core enum/FSM legality, one-hot, allowed-transition, reset-convergence, deadlock, completion, and bounded-stack checks. Use portable hooks or sidecar harnesses without freezing a user-authored formal API or binding Nodal semantics to SVA/SBY syntax.

- [ ] **Increment 68 — Discrete real and mixed-signal net types**
  - Implement `real`, `wreal` or profile equivalents, resolution, direction, sampling/update semantics, and portability.

- [ ] **Increment 69 — Analog/digital access and conversion semantics**
  - Expose conversions only through typed interface bridge endpoints carrying physical dimensions, source/destination domains, thresholds, hysteresis, quantization, timing, transition, resolution, and model availability.
  - Implement destination-domain samplers, thresholds/comparators, quantization, source-domain-aware DAC updates, transition shaping, event synchronization, and provenance transfer.

- [ ] **Increment 70 — Connect modules and connect rules**
  - Integrate conservative `Terminal`/`Node`/`Branch` interface members, connect/sense/contribute role access, topology preservation, discipline conversion, and deterministic wrapper/interface ABI mapping.
  - Implement declarations, rules, discipline insertion, direction/resolution analysis, hierarchy-wide application, and conflicts.

- [ ] **Increment 71 — Mixed-domain, CDC/RDC, and scheduling verifier**
  - Verify interface role completeness, monitor access, nested-role connections, resolved-net drivers/contention, inout hierarchy, open-drain legality, conservative-terminal access/topology, and no implicit digital/analog/conservative/signal-flow conversion.
  - Verify domain bindings, direct/combinational crossings, multi-bit misuse, pulses, reconvergence, reset release/reconvergence, generated clocks, gates/muxes, analog/digital legality, conversion loops, aggregate/shaped driver paths, latches, combinational/ready loops, structural-storage intent, drivers, waivers, and profile restrictions.

- [ ] **Increment 72 — Complete Verilog-AMS backend skeleton**
  - Treat Foundation Increments 153-157 as mandatory naming prerequisites and preserve the same structured source binder, caller aliases, provenance, and definition/invocation identity used by portable Verilog.
  - Apply function-local naming parity to analog procedural locals, user-defined analog-function locals, intermediate quantities, event expressions, branch/access calculations, mixed-signal bridge/conversion temporaries, and digital logic inside Verilog-AMS. Safely inline only where analog, event, scheduling, and contribution semantics remain exact; otherwise materialize the retained semantic name.
  - Apply target-specific keyword, scope, escaping, and collision rules without losing the original Scala binder. Prefer semantic operation/branch/event/sink names for generated objects and prohibit Nodal-owned `_zz*` or traversal-counter fallbacks.
  - Add Verilog-A/Verilog-AMS parity goldens and manifests covering helper calls, repeated invocations, analog functions, events, contributions, conversion paths, readable/debug materialization, source maps, target reparse, and deterministic cross-backend name correlation.
  - Flatten logical mixed-signal interfaces deterministically, including protocol leaves, resolved digital inout nets, discipline-qualified terminals, signal-flow values, and explicit bridges; emit the same Interface ABI/source-map manifest used by portable Verilog.
  - Emit explicit inferred clock/reset ports; signed digital vectors/parameters/literals/casts; parameterized multidimensional digital values using the portable flat ABI and signed element views; structural generate and bounded procedural/unrolled loops; canonical enum localparams/vectors; flat, nested, parallel, timed, and bounded-procedure FSM state/action/completion logic; event processes lowered from high-level state and automatic schedules; fixed/valid/elastic pipeline registers and control; synchronizers/FIFOs; reset logic; gates/muxes; analog/digital declarations; disciplines; connect constructs; hierarchy; parameters; safe expression inlining and deterministic semantic state/temporary names; shape/layout/storage/materialization/check/signed/loop/enum/FSM/latency/schedule metadata; expression-level source maps; and mandatory target verification/reparse evidence.

- [ ] **Increment 73 — ADC and DAC mixed-signal vertical slices**
  - Compile/check or simulate ADC/DAC models using implicit domains, typed mode/state enums, reusable hierarchical FSM control, automatically scheduled fixed and elastic digital datapaths, explicit sampling/drive, legal CDC, reset policies, parameter-envelope-safe scheduling, hierarchy, enum/FSM/pipeline/CDC/RDC reports, and deterministic parameterized Verilog-AMS.

- [ ] **Increment 74 — PLL/comparator mixed-signal vertical slice**
  - Exercise analog state, generated clocks, digital events, cross-domain conversion, feedback, reset behavior, and diagnostics.

- [ ] **Increment 75 — Verilog-AMS simulator adapter interface**
  - Define pluggable compile/elaborate/run adapters, discovery, licensing-safe CI, log normalization, and optional local regression.

- [ ] **Increment 76 — Portable and full AMS profiles**
  - Publish portable/full standard-oriented and simulator-extension profiles with machine-readable feature coverage and no accidental leakage.

- [ ] **Increment 77 — UVM-MS interoperability hooks**
  - Generate role-aware interface/agent metadata, monitor views, flattened/native wrapper maps, resolved-inout access, conservative-terminal access, and logical Interface ABI correlation without making UVM-MS define Nodal semantics.
  - Generate metadata, wrappers, or interfaces needed for UVM-MS integration without embedding a second verification methodology.

- [ ] **Increment 78 — Verilog-AMS conformance suite**
  - Build standards-oriented positive/negative tests, practical round trips, feature coverage, and simulator-result classification.

## Phase 5 — Plugins, target-HDL optimization, extensibility, scale, documentation, and release

- [ ] **Increment 79 — Plugin architecture gate and SPI v0.1 contracts**
  - Use [ADR 0012](../architecture/0012-versioned-capability-plugin-architecture.md), [`plugin-spi-v0.1-plan.md`](plugin-spi-v0.1-plan.md), and [`plugin-spi-v0.1-surface.json`](plugin-spi-v0.1-surface.json) as the mandatory architecture and candidate.
  - Compile plugin descriptors/manifests, stable plugin/capability IDs, versions, cardinalities, qualifiers, `DesignPlugin`, local `DesignHost`, typed services/contributions, phase contexts, backend IDs, native/process descriptors, and library-versus-plugin separation.
  - Publish `NodalPluginSpi-DG-v0.1.md`, manifest/lockfile schemas, compatibility/trust policy, machine-readable frozen SPI, and positive/negative fixtures with stable diagnostics.
  - Keep loaders and plugin execution inert. Mark this increment `[x]` only after every SPI freeze criterion in the detailed plan passes CI.

- [ ] **Increment 80 — Manifest resolver, capability graph, lockfile, and plugin CLI**
  - Implement manifest-only discovery without code execution; validate SPI/core/API/IR/bridge/toolchain ranges, provided/required capability versions, cardinality, qualifiers, conflicts, replacements, platform artifacts, trust, and option schemas.
  - Resolve a canonical immutable plugin plan, reject ambiguity and cycles, generate `nodal.plugins.lock`, and include graph/artifact/options hashes in build manifests and cache keys.
  - Add `nodal plugins list/resolve/check/graph/explain/lock/inspect` with human and machine-readable evidence plus offline locked mode.

- [ ] **Increment 81 — Local design composition host and typed contribution system**
  - Implement local `DesignHost` scopes, phase-specific contexts, stable capability keys, exactly-one/optional/many/qualified providers, contribution sets/sequences, close phases, nested explicit import/export, stable plugin instance qualifiers, names, and provenance.
  - Prohibit process-global registries, concrete-plugin lookup, direct mutable cross-plugin access, public retain/release ordering, implicit first-provider selection, and undeclared contributions.
  - Add configurable digital/mixed-signal subsystem fixtures, multiple instances, nested hosts, conflict/cycle diagnostics, and declaration-order permutation tests producing identical IR/HDL/reports.

- [ ] **Increment 82 — Native compiler plugin loader and versioned extension points**
  - Wrap MLIR pass and dialect plugin APIs with Nodal manifest validation, exact native ABI/toolchain-build matching, plugin-owned namespaces, analysis preservation/invalidation, named versioned pipeline extension points, normalized pass evidence, and mandatory core re-verification.
  - Add out-of-process transform protocol for isolated/longer-lived extensions, with versioned IR exchange, diagnostics, output hashes, cancellation, timeout, crash, and malformed-response handling.
  - Provide out-of-tree pass, analysis, dialect, verifier, and transform fixtures using no private core APIs.

- [ ] **Increment 83 — Target-HDL optimization pass gate and SPI v0.1 contracts**
  - Use [ADR 0013](../architecture/0013-structured-hdl-optimization-pass-architecture.md), [`target-hdl-optimization-pass-v0.1-plan.md`](target-hdl-optimization-pass-v0.1-plan.md), and [`target-hdl-optimization-pass-v0.1-surface.json`](target-hdl-optimization-pass-v0.1-surface.json) as the mandatory architecture and candidate.
  - Compile descriptors/manifests for target-neutral, digital, Verilog-A, Verilog-AMS, render-only, and reparse passes; stable pass IDs; target/profile/IR versions; extension points; ordering/conflicts; options; preservation/invalidation; proof classes; parameterization, shaped-value/layout/storage, expression-materialization/naming/source-map, driver/latch/cycle/check-inventory effects; profiles; native/process facets; and evidence artifacts.
  - Publish `NodalTargetHdlOptimizationPass-DG-v0.1.md`, a machine-readable frozen pass SPI, pass/profile lockfile schemas, compatibility/trust policy, and positive/negative fixtures with stable diagnostics.
  - Prove installation changes no output, `Backend.Auto` remains independent, raw semantic text cannot bypass reparse/reverification, and frontend/backend/pass execution remains inert until later increments.

- [ ] **Increment 84 — Structured target IR and deterministic optimization pass manager**
  - Implement verified digital target IR using CIRCT where semantically appropriate plus Nodal-owned shaped-value/layout/storage, expression-origin/materialization/naming, and mandatory-check contracts, and typed Verilog-A/Verilog-AMS target IR preserving disciplines/nodes/branches/contributions/dimensions/continuous-time operators/events/noise/analyses/digital state/conversions/connect rules/capabilities/hierarchy/source maps.
  - Implement deterministic locked pass resolution/execution, native/process loading through Increment 82, analysis invalidation/recomputation, mandatory target verification, transactional crash-safe acceptance, render-only and verified reparse boundaries, source-map updates, diagnostics, pass reports, cache/provenance integration, and pass/pipeline inspection commands.
  - Add out-of-tree target-pass fixtures and declaration-order/load-order permutation tests producing identical verified target IR, HDL, diagnostics, reports, and pipeline hashes.

- [ ] **Increment 85 — Digital Verilog optimization plugins and equivalence/formal proof matrix**
  - Implement built-in/reference plugins for parameter-aware constant/dead-logic cleanup, mux/logic/process/memory/generate normalization, safe common-subexpression elimination, hierarchy/portability cleanup, pipeline-owned bounded retiming, explicit synthesis attributes/target mapping, and locked external Yosys pass pipelines.
  - Preserve widths/signedness/overflow, numeric-conversion versus reinterpretation, signed literal/shift/comparison semantics, ranked shapes/dimensions/index/flatten/layout and structural-storage class, expression tree/materialization/naming/observability and source spans, elaboration/generate/hardware-loop category, iteration/reduction order, index bounds, deterministic unroll/procedural choice, symbolic parameters/generate, one-module-per-structure, hierarchy, clocks/resets/CDC/RDC, protocol ordering, latency/throughput/capacity, user-owned state, memories/effects, mandatory check results, source maps, and portable-Verilog capabilities unless an explicit separately named transformation contract permits a verified change.
  - Require Verilator/Icarus differential regression, Yosys combinational/sequential and latency/protocol-aware equivalence, parameter-envelope matrices, selected SBY properties, deterministic before/after reports, and exact golden/profile fixtures.

- [ ] **Increment 86 — Verilog-A/Verilog-AMS optimization plugins and semantic validation**
  - Implement built-in/reference plugins for dimension-safe constant/parameter folding, dead declaration removal, branch/access/contribution canonicalization, approved algebraic identities with domain/singularity checks, event-condition simplification preserving direction/tolerance, connect-rule/discipline portability rewriting, and deterministic render normalization.
  - Prohibit unapproved contribution deletion/reordering, equation reassociation across discontinuities/singularities, movement of continuous-time/delay/Laplace/Z operators, changes to event timing/tolerance/initialization/noise/analysis identity, silent approximation, mixed-signal scheduling changes, and raw semantic text substitution.
  - Require typed target-IR invariants and normalized equivalence for approved rewrites plus relevant DC/AC/transient/noise/event differential suites, cross-tool portability evidence, source-map/provenance checks, deterministic golden fixtures, and explicit rejection where no sound validation method exists.

- [ ] **Increment 87 — Backend and external tool-adapter plugins**
  - Implement explicit third-party backend registration, capability profiles, options/artifact/source-map contracts, deterministic selection, and rejection before translation. Keep plugin backends out of `Backend.Auto` by default.
  - Implement one versioned out-of-process adapter/evidence protocol for simulators, synthesis, formal, FPGA place/route, bitstreams, programmers, boards, HIL, waveforms, and reporters.
  - Migrate built-in external adapters to the common protocol while preserving licensing-safe CI and the rule that external tools never define language semantics.

- [ ] **Increment 88 — Plugin packaging, trust, provenance, caching, and conformance**
  - Define coordinated Scala/Maven, native-platform, process-executable, schema/support-file, checksum/signature, license, SBOM, and provenance packaging with explicit trusted-Scala/trusted-native/process-isolated policies.
  - Integrate plugin graphs, artifacts, options, pass order, external commands, and outputs into incremental caching, release provenance, reproducibility, and offline resolution.
  - Publish a plugin conformance kit plus out-of-tree design, frontend-lint, MLIR pass/dialect, backend, and tool-adapter reference plugins. Prove compatibility failures, crash isolation, load-order determinism, and no hidden core dependency.

- [ ] **Increment 89 — Versioned IR and bridge compatibility**
  - Add Nodal dialect/bridge/plugin-plan version metadata, supported upgrades, old-version fixtures, plugin extension-point compatibility, and explicit unknown-future-version rejection.

- [ ] **Increment 90 — Incremental build and compiler caching**
  - Cache construction, normalized MLIR, plugin resolution/transforms, native compilation, reports, and backend/tool outputs by content/toolchain/profile/plugin-plan hashes with proven invalidation.

- [ ] **Increment 91 — Future library architecture and publication contract**
  - Define passive library module conventions, Maven coordinates, resources, independent versions, core ranges, conflicts, licenses, offline use, and external public-API-only fixtures. Keep executable companion plugins separately packaged and explicitly enabled.

- [ ] **Increment 92 — Complete language, plugin SPI, and API documentation**
  - Document `Struct` versus `Interface`, generic roles, `master`/`slave`/`monitor`, full `Valid`/`Stream`, digital inout/resolved-net/tri-state/open-drain/pad patterns, conservative terminals, signal-flow analog values, mixed-signal bridges, flattened/native backend layouts, and Interface ABI compatibility.
  - Cover value staging; Scala elaboration, symbolic generate, and bounded hardware loops; signed/unsigned/signless declarations/literals/conversions/backend mapping; parameterized multidimensional `Vec`, shape/index/flatten/reshape, layout and `Vec`/`Mem`; expression inlining/materialization/naming/source maps; mandatory check profiles/inventory/waivers/transactional emission; numeric/width/overflow; aggregates/connections/protocols; quantities/effects; domains/CDC/RDC/reset; automatic pipelines; portable Verilog/backend inference; open-source verification; mixed-signal boundaries; plugin manifests/capabilities/lifecycle/loaders/adapters/trust/lockfiles; diagnostics; libraries; and migration.

- [ ] **Increment 93 — Tutorials, plugin-author guides, and cross-project reuse examples**
  - Add progressive analog/AMS/domain tutorials, patterns/anti-patterns, standalone external consumers, and out-of-tree design/compiler/backend/tool plugin author tutorials with conformance commands.

- [ ] **Increment 94 — Cross-platform core and plugin packaging**
  - Produce checksummed core Scala/native bundles for supported Linux/macOS first, Windows strategy and source fallback, plus plugin bundle/platform conventions and stable hooks for independently published libraries/plugins.

- [ ] **Increment 95 — Reproducible release, provenance, plugin lockfiles, and SBOM**
  - Add release automation, checksums/signatures where possible, dependency/plugin SBOM, plugin lockfile and graph provenance, toolchain/pass/adapter evidence, license inventory, and rebuild verification.

- [ ] **Increment 96 — Performance and scalability benchmarks**
  - Benchmark deep/nested interfaces, symbolic interface arrays, role expansion, logical ABI/source-map size, flattening, wrapper generation, resolved-driver graphs, conservative topology graphs, and mixed-signal interface verification.
  - Benchmark construction, ranked shape algebra and parameter matrices, expression inlining/materialization and source-map size, naming stability, mandatory check phases/path reconstruction, MLIR, semantic analyses, automatic pipelines, domains/CDC/RDC, portable Verilog, open-source verification, plugin manifest resolution, capability graphs, design-host contributions, native/process plugin overhead, cache behavior, pass time, memory, hierarchy, and regression launch.

- [ ] **Increment 97 — Public API and plugin SPI v1 review**
  - Review v0.1/v0.2/v0.3 APIs and plugin SPI implementation experience, including shaped values/layout/storage, expression materialization/naming/source maps, check profiles/inventory/waivers, capability identity/cardinality, phase contexts, native/process compatibility, trust, determinism, plugin/library boundaries, implicit domains, pipelines, backend inference, and low-level escape. Approve only justified changes and define semantic versioning/deprecation/source/SPI compatibility.

- [ ] **Increment 98 — Nodal core preview release**
  - Publish the supported preview with frozen public API and plugin SPI revisions, toolchain pins, shaped-value/layout and naming/materialization manifests, machine-readable mandatory-check coverage/waiver inventory, portable Verilog/Verilog-A/Verilog-AMS matrices, open-source verification evidence, plugin conformance kit, installation, examples, known limitations, library/plugin-author contracts, and reproducible provenance.

- [ ] **Increment 99 — Future SystemVerilog/SystemVerilog-AMS backend research gate**
  - Evaluate native SystemVerilog `interface`/`modport`, nested interfaces, parameters, monitor roles, resolved `inout`, per-instance flatten overrides, wrapper/compile-order manifests, and exact semantic/ABI parity with portable flattened Verilog and Verilog-AMS representations.
  - Reassess the current standards and tool support; map IR/plugin/backend coverage; evaluate exact `logic signed`/signed parameter/localparam/packed-field/array/memory/function/loop-variable lowering and parity with portable Verilog; default unpacked multidimensional array ports of packed elements, optional multidimensional packed layouts, parameterized dimensions, tool/profile compatibility, wrapper/ABI manifests, and signed-element parity with flat portable carriers; native `typedef enum logic` emission, design-level enum packages/compile-order manifests, enum-typed ports/parameters/aggregates/memories; structural generate and procedural-loop lowering; statechart lowering; and compatibility with portable-Verilog numeric mappings; identify required changes; and approve or reject implementation through a separate gate without speculating syntax into the stable API.

## Phase 6 — FPGA-accelerated AMS approximation and hardware validation

- [ ] **Increment 100 — AMS-to-FPGA approximation capability gate and API contracts**
  - Use [ADR 0011](../architecture/0011-ams-fpga-approximation-validation.md), [`ams-fpga-validation-plan.md`](ams-fpga-validation-plan.md), and [`ams-fpga-validation-surface.json`](ams-fpga-validation-surface.json) as the mandatory architecture and candidate.
  - Compile candidate approximation, solver, sample/rate, numeric, range, error-budget, validation-envelope, target, and HIL contracts. Prove `Backend.Auto` never selects approximation and that unsupported AMS constructs fail with stable source-located diagnostics.
  - Publish `NodalAmsFpgaApproximation-DG-v0.4.md`, compatibility/migration rules, a machine-readable frozen surface, external-library fixtures, claims language, and complete positive/negative contracts before implementation.

- [ ] **Increment 101 — Analog normalization and sampled-state IR**
  - Normalize supported linear state-space, transfer-function, and explicit-ODE models into target-neutral state/update IR with dimensions, parameters, inputs/outputs, algebraic dependencies, events, initial conditions, and authoritative reference links.
  - Diagnose unresolved DAEs/algebraic loops, hidden state, unsupported nonlinearities, unsupported analyses, and constructs that cannot form a deterministic sampled recurrence.

- [ ] **Increment 102 — Solver and discrete-time recurrence generation**
  - Implement the approved forward/backward Euler, trapezoidal/Tustin, exact-ZOH, and custom-solver contracts only for supported model classes.
  - Generate deterministic coefficients and recurrence IR, high-precision software references, initialization/reset behavior, stability/conditioning evidence, bounded iteration/convergence rules, latency/resource models, and failure diagnostics.

- [ ] **Increment 103 — Range, fixed-point, quantization, and error-budget analysis**
  - Add physical scaling, range assertions/inference, explicit/automatic fixed-point formats, guard bits, rounding, overflow, coefficient quantization, state/intermediate formats, and accumulated error accounting.
  - Generate bit-accurate references and Level B evidence; reject unbounded state, uncovered ranges, impossible error/resource policies, or implicit numeric choices.

- [ ] **Increment 104 — Multi-rate, sampled-event, and real-time scheduling**
  - Implement rational multi-rate partitions, sample/hold, interpolation, decimation, sampled/interpolated event detection, buffering, timestamps, update ordering, and rate/clock bridges.
  - Integrate `ClockDomain`, reset, CDC/RDC, `Valid`/`Stream`, memories, and automatic pipelines. Prove each sample deadline and diagnose infeasible real-time schedules.

- [ ] **Increment 105 — Synthesizable FPGA approximation backend**
  - Lower the discrete fixed-point model into ordinary Nodal digital IR and reuse symbolic parameters, hierarchy, memories, clock/reset, protocols, automatic pipelines, CDC/RDC, and `Backend.Verilog`.
  - Emit deterministic portable Verilog, source maps, recurrence/numeric/rate/schedule manifests, capability limitations, simulation/formal hooks, and exact golden fixtures.

- [ ] **Increment 106 — Differential, equivalence, and formal validation ladder**
  - Implement Level A AMS-reference versus high-precision-discrete comparison and Level B high-precision versus fixed-point comparison using declared waveform/state/event/frequency metrics and envelopes.
  - Implement Level C Verilator/Icarus regression, Yosys equivalence, and SBY properties for recurrence, reset, protocols, multi-rate scheduling, range/overflow, latency, and deadlines. Preserve failures and counterexamples by error class.

- [ ] **Increment 107 — Open-source FPGA implementation and target evidence**
  - Select and pin at least one complete open FPGA target using Yosys, nextpnr, constraints, an open bitstream packer/programmer, deterministic seeds, and reproducible board metadata.
  - Run synthesis, placement, routing, timing, bitstream generation, utilization, DSP/memory accounting, and post-route sample-deadline checks. Add optional vendor adapters without making them normative.

- [ ] **Increment 108 — Hardware-in-the-loop runtime, vertical slices, and capability matrix**
  - Add deterministic start/stop/reset, timestamped sampled streams, parameter loading, trace capture, status/deadline/overflow reporting, reproducible host transport, and optional external ADC/DAC board profiles.
  - Complete RC/RLC, controlled-plant, comparator/ADC/DAC, and PLL/control-loop vertical slices through all four validation levels.
  - Publish supported/unsupported constructs, validation envelopes, approximation/error limits, resource/timing results, board/bitstream evidence, claims language, and the M5 FPGA-accelerated AMS validation release package.


## Phase 7 — Deferred, independently schedulable user-authored formal verification

This phase is deliberately outside the initial core, plugin, and AMS-to-FPGA milestones. It may be pulled forward after Increments 15, 19-23, 54-67, 82, and 87 provide the public semantic, IR, backend, core-proof, compiler-plugin, and formal-adapter prerequisites. No formal implementation is performed by this roadmap update.

- [ ] **Increment 109 — Formal verification architecture gate and public API v0.1 contracts**
  - Use [ADR 0014](../architecture/0014-target-neutral-formal-verification.md), [`formal-verification-v0.1-plan.md`](formal-verification-v0.1-plan.md), and [`formal-verification-v0.1-surface.json`](formal-verification-v0.1-surface.json) as the mandatory architecture and candidate.
  - Compile and compare concise formal context, assert/assume/cover, property IDs/groups, sampled-value operators, bounded temporal forms, symbolic values, harness, contract, and task configuration candidates.
  - Freeze clock/reset, combinational, cross-domain, parameter, memory, black-box, assumption-scope, vacuity, result-state, source-map, simulation/formal-inclusion, and immediate-assertion-only synthesis semantics.
  - Publish `NodalFormalVerification-DG-v0.1.md`, machine-readable frozen API/task surfaces, compatibility policy, stable diagnostics, and positive/negative external-consumer fixtures. Keep execution/lowering inert until approval.

- [ ] **Increment 110 — Target-neutral formal property IR, verifier, and lowering framework**
  - Implement formal test/harness, property, symbolic-value, sampled-history, bounded-temporal, contract, enable/reset, and formal-model operations with stable IDs and source maps.
  - Selectively reuse CIRCT `verif` and `ltl` through verified conversions; retain Nodal-owned operations where semantics differ or capabilities are missing.
  - Add property/domain/reset/type/capability verification, immediate-versus-temporal classification, deterministic parse/print, normalized reports, verification-only immediate/monitor/sidecar lowering, and explicit immediate-checker RTL lowering.
  - Prove concurrent or temporal properties, sampled history, assumptions, covers, symbolic values, and generated verification monitors cannot enter ordinary synthesis artifacts; prove immediate assertions synthesize only through explicit inclusion.

- [ ] **Increment 111 — Formal harnesses, symbolic environments, compositional contracts, and model abstractions**
  - Implement DUT wrappers, symbolic sequence/constants, initial assumptions, legal clock/reset generation, stable verification exports, property groups, and reusable harness composition.
  - Implement exact or explicitly abstracted memory, external-operation, and black-box formal models with soundness and waiver reporting.
  - Implement require/ensure contract checking/application, assume-guarantee composition, parameter matrices/envelopes, conservative multi-clock handling, and hidden-assumption/over-constraint diagnostics.

- [ ] **Increment 112 — Pluggable proof execution, proof modes, and normalized evidence**
  - Implement SBY/Yosys as the required open-source formal adapter through the common process/evidence protocol, including BMC, prove/induction, cover, selected liveness, solver, timeout, and resource controls.
  - Add an out-of-tree mock or second formal-adapter conformance fixture so public semantics are not coupled to SBY files.
  - Normalize per-property/task results, commands, logs, traces, proof metadata, source maps, cache keys, and reproduction commands; reject unsupported capabilities before execution.
  - Preserve inconclusive, timeout, cancellation, unsupported, and tool-error states without reporting them as proof success.

- [ ] **Increment 113 — Property libraries, vacuity/coverage, counterexample replay, documentation, and conformance**
  - Publish passive property libraries for enums, legal-state/transition FSMs, reusable statecharts, protocols, FIFOs, pipelines, resets, CDC/RDC wrappers, memories, and common control structures using only public APIs.
  - Implement assumption consistency, antecedent/scenario cover goals, supported vacuity checks, over-constraint reports, and defined property/scenario coverage metrics.
  - Replay normalized counterexamples/covers through the Scala simulation API with typed transactions, domain timelines, source annotations, and VCD/FST waveforms.
  - Publish tutorials, adapter/property-library author guides, capability matrices, known limitations, conformance suites, and the M6 reproducible formal-verification extension package.

- [x] **Increment 114 — Immediate assertion synthesis boundary correction**
  - Restrict synthesizable assertion logic to an explicitly selected immediate Boolean assertion evaluated combinationally or in one owning clock domain.
  - Keep concurrent or temporal properties, sampled history, assumptions, covers, symbolic formal values, fairness/liveness declarations, and compiler-generated verification monitors outside synthesizable DUT RTL.
  - Require synthesized immediate assertions to be observational by default; functional control requires an ordinary explicit design connection.
  - Synchronize ADR 0014, the formal-verification plan/surface, the digital Verilog verification plan, the digital-backend surface, and roadmap revision 1.13. Keep exact public spelling and implementation deferred to Increments 109-110.
  - Evidence: [`0014-target-neutral-formal-verification.md`](../architecture/0014-target-neutral-formal-verification.md), [`formal-verification-v0.1-plan.md`](formal-verification-v0.1-plan.md), [`formal-verification-v0.1-surface.json`](formal-verification-v0.1-surface.json), [`digital-verilog-open-source-verification-plan.md`](digital-verilog-open-source-verification-plan.md), and [`digital-backend-v0.3-surface.json`](digital-backend-v0.3-surface.json).


- [x] **Increment 115 — Register factory architecture and roadmap contract**
  - Accept [ADR 0020](../architecture/0020-canonical-register-factory-and-transport-adapters.md), the staged [`register-factory-v0.1-plan.md`](register-factory-v0.1-plan.md), and the machine-readable [`register-factory-v0.1-surface.json`](register-factory-v0.1-surface.json).
  - Freeze register-definition-first separation: immutable bus-neutral `RegisterMap`, independent physical `RegisterBlock`, one clock/reset domain per physical bank, one exactly-once committed-access endpoint, and APB/AXI4-Lite/custom transport adapters that cannot redefine register semantics.
  - Record SystemRDL 2.0 as the primary standards-based register interchange, versioned safe Nodal YAML/JSON as a convenience frontend, IEEE 1685-2022 IP-XACT as later integration interchange, and CSV/spreadsheets only as explicit conversion inputs.
  - Freeze generated-Verilog policy: fixed register ABI symbols use width-safe non-overridable `localparam`s/constants; only explicit Nodal architectural variability becomes an HDL `parameter`; relative-offset decode is the default and any absolute-base wrapper is explicit.
  - Keep exact public API, canonical IR, parsers, bus adapters, RTL lowering, and artifact generators unimplemented and assigned to Increments 116-123.

- [ ] **Increment 116 — Register factory public API candidates and design gate**
  - Prototype concise Scala 3 `RegisterMap`, `RegisterBlock`, register/field definitions, typed handles, hardware bindings, arrays/submaps/windows/aliases, snapshots/commits, software/hardware/collision policies, and transport binding.
  - Compare alternatives against mature bus-slave/register-interface facilities while preserving Nodal's stronger register-definition-first separation.
  - Freeze exact imports, names, construction rules, diagnostics, extension points, external-library subset, and compile-positive/negative fixtures in `NodalRegisterFactory-DG-v0.1` before implementation.

- [ ] **Increment 117 — Canonical Register IR, source maps, verifier, and ABI manifest**
  - Implement target-neutral Register IR for blocks, registers, fields, hierarchy, geometry, policies, side effects, hardware bindings, domains, accesses, and transport capabilities.
  - Add deterministic IDs/source locations, canonical JSON, semantic hashing, overlap/alignment/address-width/reserved-region checks, parameter-envelope verification, and ABI lock/diff classification.

- [ ] **Increment 118 — SystemRDL 2.0 and Nodal YAML/JSON frontends**
  - Implement safe versioned `nodal-registers/v1` YAML/JSON parsing with deterministic explicit imports, controlled roots, cycle detection, schema migration diagnostics, and source spans; prohibit arbitrary tags, executable templates, and order-dependent semantics.
  - Implement the supported SystemRDL 2.0 import subset and deterministic export, diagnose unsupported/lossy mappings, and prove equivalent Scala/SystemRDL/YAML definitions normalize to equivalent Register IR and ABI hashes.

- [ ] **Increment 119 — Canonical access endpoint and APB3/APB4 adapter**
  - Implement register storage/update semantics, relative-address decode, read muxes, byte enables, errors, side effects, reset behavior, and the exactly-once committed request/response endpoint.
  - Implement APB3/APB4 setup/access, wait-state, strobe/protection/error handling with open-source simulation, formal protocol/semantic checks, lint, and synthesis evidence.

- [ ] **Increment 120 — AXI4-Lite and custom transport conformance**
  - Implement AXI4-Lite address/data buffering and pairing, backpressure, strobes, protection, responses, ordering, reset, and declared outstanding policy without committing incomplete transactions.
  - Freeze and implement the public custom-transport capability/conformance contract and an explicit multi-access arbiter/router; reject implicit dual attachment.

- [ ] **Increment 121 — Portable Verilog register lowering and parameterized geometry**
  - Emit deterministic relative-offset decode, width-safe named `localparam`s, masks, reset/access constants, storage, side effects, and readable hierarchy; never expose fixed offsets as externally overridable parameters.
  - Emit HDL parameters only for explicit Nodal symbolic configuration, support an explicit optional absolute-base wrapper, validate repeated/parameterized maps over declared envelopes, and prove RTL/artifact configuration consistency.

- [ ] **Increment 122 — Artifact generators, IP-XACT, and software ABI flows**
  - Generate canonical JSON/ABI hashes, C/C++ headers, Rust metadata or PAC input, CMSIS-SVD, UVM RAL/RALF, Markdown/HTML, SystemRDL, and IEEE 1685-2022 IP-XACT register/memory-map views.
  - Preserve stable identities, source provenance, semantic hashes, resolved parameter configuration, and explicit loss diagnostics in every artifact; add cross-artifact equivalence and compatibility-report tests.

- [ ] **Increment 123 — Register factory verification, scale, and reusable adapter/library qualification**
  - Generate semantic verification for reset/access/side-effect/collision/byte-enable/multiword/array/hierarchy/snapshot/commit/illegal-access behavior while keeping concurrent/temporal properties verification-only under Increment 114.
  - Add large-map performance, deterministic output, Verilator/Icarus, Yosys quality/equivalence, custom-adapter conformance, and one external reusable register-map qualification using only public contracts.
  - Publish user, adapter-author, SystemRDL/YAML migration, artifact, and SoC-integration documentation.


## Phase 8 — Cross-cutting Interface, Role, digital inout, and AMS connectivity closure

This independently schedulable phase closes the cross-layer architecture accepted by ADR 0021. It does not replace the foundational implementation assigned to Increments 14-22, 54-77, and 99; it integrates and qualifies those pieces as one public connectivity system.

- [x] **Increment 124 — Interface, Role, AMS, and digital inout architecture roadmap contract**
  - Accept [ADR 0021](../architecture/0021-unified-struct-interface-role-and-inout-architecture.md), the staged [`interface-role-inout-ams-v0.1-plan.md`](interface-role-inout-ams-v0.1-plan.md), and the machine-readable [`interface-role-inout-ams-v0.1-surface.json`](interface-role-inout-ams-v0.1-surface.json).
  - Freeze the semantic separation among directionless storable `Struct`, non-storable connectivity `Interface`, named `Role`, digital resolved inout, conservative AMS terminals, directional analog signal-flow values, and explicit mixed-signal bridges.
  - Record `master`/`slave` as convenience roles over a generic role model, `Valid` as the canonical valid-only protocol, monitor read-only access, and explicit non-invertible AMS/shared roles.
  - Record first-class digital inout read/drive/high-impedance/resolution, push-pull/open-drain, black-box/hierarchy/pad use, split internal tri-state carriers, and capability-checked internal resolution with no silent mux rewrite.
  - Record one logical Interface ABI with deterministic portable Verilog/Verilog-A/Verilog-AMS flattening and future native SystemVerilog interface/modport parity. Keep exact public API and implementation assigned to Increment 14/15 and later implementation increments.
  - Evidence: [`0021-unified-struct-interface-role-and-inout-architecture.md`](../architecture/0021-unified-struct-interface-role-and-inout-architecture.md), [`interface-role-inout-ams-v0.1-plan.md`](interface-role-inout-ams-v0.1-plan.md), and [`interface-role-inout-ams-v0.1-surface.json`](interface-role-inout-ams-v0.1-surface.json).

- [ ] **Increment 125 — Canonical Interface IR, role expansion, source maps, and ABI manifest**
  - Implement interface/role definitions, member identity, recursive role expansion, exact connection compatibility, interface storage prohibition, parameterized member paths, source maps, diagnostics, canonical manifests, ABI hashes, and compatibility classification.
  - Integrate with construction close, Nodal MLIR, cross-layer diagnostics, plugin metadata, caches, and deterministic parse/print.

- [ ] **Increment 126 — Digital Struct/Interface/Role and full Valid/Stream implementation**
  - Implement directionless `Struct`, nested digital `Interface`, named roles, legal complementary-role derivation, monitor views, plain/`Valid`/`Stream`, transfer/stall/bubble semantics, exact connection, typed adapters, domain provenance, hierarchy propagation, and external protocol-interface conformance.

- [ ] **Increment 127 — Digital inout, resolved nets, tri-state, open-drain, pads, and black-box hierarchy**
  - Implement typed read/drive endpoints, driver states/enables, `0/1/Z/X` resolution, push-pull/open-drain/open-source modes, readback, pull/pad metadata, hierarchy pass-through, black-box connectivity, and split-tristate boundary adapters.
  - Add profile-aware internal tri-state restrictions, contention diagnostics/properties, Verilator/Icarus tests, Yosys synthesis/equivalence where supported, and negative fixtures.

- [ ] **Increment 128 — Conservative AMS terminals, signal-flow values, and mixed-signal roles**
  - Implement boundary `Terminal`, internal `Node`, `Branch`, discipline/nature/dimension checks, connect/sense/contribute/monitor access, directional analog signal-flow values, mixed interfaces, bridge endpoints, continuous-time island graphs, and no-implicit-conversion verification.

- [ ] **Increment 129 — Flattened Verilog, Verilog-A, and Verilog-AMS interface lowering**
  - Emit deterministic flattened names/ports/terminals for nested interfaces, protocols, shaped payloads, resolved inouts, conservative terminals, signal-flow values, and bridges.
  - Generate wrappers, Interface ABI/source-map manifests, profile diagnostics, and exact golden fixtures.

- [ ] **Increment 130 — Native SystemVerilog interface/modport backend and wrapper parity**
  - After Increment 99 approval, emit native interfaces/modports, nested interfaces, parameters, monitor roles, inout nets, and per-instance flatten overrides.
  - Generate native/flat wrappers and prove logical ABI, simulation, synthesis, compile-order, and source-map parity across supported tool profiles.

- [ ] **Increment 131 — Interface metadata, verification agents, scale, and external qualification**
  - Generate Scala simulation, cocotb, UVM/UVM-MS, waveform, IP-XACT, and documentation metadata from the logical Interface ABI.
  - Add role/inout/AMS checkers, large nested-interface performance, parameter matrices, deterministic output, compatibility diff, and external reusable interface/library qualification.

## Phase 9 — Continuous-time equation, hybrid DAE, solver, and analog-model qualification closure

This independently schedulable phase closes the cross-layer continuous-time architecture accepted by ADR 0022. It does not replace the source-language, validation, mixed-signal, interface, or backend work in Increments 24-53, 68-78, and 128-129. It gives those increments one solver-independent semantic, mathematical, analysis, and evidence architecture.

- [x] **Increment 132 — Continuous-time equation, hybrid DAE, and solver architecture roadmap contract**
  - Accept [ADR 0022](../architecture/0022-layered-continuous-time-hybrid-dae-architecture.md), the staged [`continuous-time-ams-v0.1-plan.md`](continuous-time-ams-v0.1-plan.md), and the machine-readable [`continuous-time-ams-v0.1-surface.json`](continuous-time-ams-v0.1-surface.json).
  - Record distinct source-semantic analog IR, topology graph, hybrid equation-system IR, analysis projections, and target/solver representations.
  - Record explicit `AnalogIsland`, stable equation/unknown/state/event/noise identities, DAE structural verification, state and initialization ownership, hybrid event ordering, analysis/noise/environment contracts, solver capability negotiation, model validity envelopes, and retained evidence.
  - Keep exact public syntax, compiler implementation, solver behavior, and target lowering assigned to Increments 133-142 and the existing analog/AMS increments.
  - Evidence: [`0022-layered-continuous-time-hybrid-dae-architecture.md`](../architecture/0022-layered-continuous-time-hybrid-dae-architecture.md), [`continuous-time-ams-v0.1-plan.md`](continuous-time-ams-v0.1-plan.md), and [`continuous-time-ams-v0.1-surface.json`](continuous-time-ams-v0.1-surface.json).

- [x] **Increment 133 — Analog semantic API and analysis contract design gate**
  - Schedule an equation/component API checkpoint ahead of Increment 32 while retaining the complete continuous-time API gate in this increment.
  - Compile and compare public candidates for unordered equations, contributions, procedural-assignment distinction, conservative terminals/branches/connections, partial/concrete component balance, structural parameters, initial equations, explicit analog state and reinitialization, event tolerance and discontinuity declarations, analysis context, environment/PVT access, noise identity/correlation, validity envelopes, and solver-hint metadata.
  - Publish `NodalEquationComponentApi-DG-v0.1.md`, a machine-readable checkpoint surface, migration notes, stable diagnostics, compile-positive/negative fixtures, and one external reusable physical-component fixture. Increment 32 may start only after this checkpoint is accepted.
  - Publish the complete `NodalContinuousTimeApi-DG-v0.1.md` and machine-readable public surface before closing Increment 133.
  - Keep frontend, equation normalization, solver, and backend behavior inert throughout the design gate.
  - Evidence: approved `NodalEquationComponentApi-DG-v0.1` and `NodalContinuousTimeApi-DG-v0.1`; implementation PR #91 (superseding draft PR #90); exact accepted head `a9c236384b32140d1b0a213cfbdb5c5512baab24`; dedicated run `33262841010`; accepted Core CI run `33262188693`; squash merge `4ca5d230bc5d3e3f985b9b4ed24386c69f74b539`; post-merge Core CI run `33263135167`.

- [ ] **Increment 134 — Source-semantic analog IR, `AnalogIsland`, and stable identities**
  - Implement distinct source-semantic operations for equations, contributions, procedural assignments, connections, operators, events, analyses, noise, environment, and solver hints.
  - Preserve authored equation left/right expressions separately from later residual form and retain terminal, branch, connection-set, component, partial/concrete, local-balance, structural-parameter, hierarchy, dimension, guard, analysis, and source-span metadata.
  - Build deterministic islands and stable IDs for topology objects, components, unknowns, equations, contributions, state, events, noise, bridges, and analyses.
  - Add normalized parse/print, source maps, parameter formulas/envelopes, mutation tests, local semantic inventories, and semantic manifests.

- [ ] **Increment 135 — Topology expansion, residual DAE construction, and structural verification**
  - Build a logically flattened hierarchy/topology/equation view for analysis without requiring flattened target emission.
  - Expand conservative connection sets, first-class equations, and contribution sets into solver-neutral residual systems while preserving authored equation, component, branch, orientation, and hierarchy provenance.
  - Classify continuous, derivative, algebraic, discrete, parameter, structural-parameter, environment, independent, and input variables.
  - Implement local component balance plus whole-island incidence/dependency graphs, structural matching, block decomposition, equation/unknown balance, references, conservation, singularity, algebraic loops, initialization structure, variable-topology classification, and parameter-envelope checks.
  - Keep residual construction independent of target causal orientation; reject unsupported higher-index or variable-structure systems explicitly and do not perform unapproved index reduction.

- [ ] **Increment 136 — Continuous state, initialization, operators, and hidden-state reporting**
  - Implement state ownership for `ddt`, `idt`, Laplace/Z-domain operators, delay, transition, slew, hysteresis, sample/hold, and bridge state.
  - Implement fixed values, guesses, initial equations, steady-state conditions, operating-point-derived initialization, reinitialization/jumps, and conflict diagnostics.
  - Generate state/initialization reports and reject accidental duplication, movement, or hidden backend state.
  - Add semantics-preserving operator lowering and differential fixtures.

- [ ] **Increment 137 — Hybrid event scheduler, discontinuities, and mode-dependent topology**
  - Freeze and implement observable ordering for initialization, integration, root detection, timers, analog events, digital events, bridge updates, zero-time event iteration, restart, and finalization.
  - Implement dynamic event tolerances, named events, interrupted transitions, event fixed-point convergence, zero-time oscillation diagnostics, and event dependency graphs.
  - Classify hard discontinuities, smoothness, hysteresis, guarded equations, and explicitly supported topology modes.
  - Keep solver hints separate from mathematical behavior.

- [ ] **Increment 138 — Analysis projections, linearization, derivatives, and noise**
  - Derive initialization, DC/operating-point, transient, AC/small-signal, and noise projections from one semantic model.
  - Implement derivative/Jacobian interfaces, sparse structure, differentiability diagnostics, symbolic/automatic/analytic derivative evidence, and capability-gated numerical differentiation.
  - Preserve stable noise identity, PSD dimensions, hierarchy, correlation, transfer paths, and analysis applicability.
  - Add cross-analysis consistency and derivative differential tests.

- [ ] **Increment 139 — Environment, PVT, statistical variation, and model validity envelopes**
  - Implement immutable typed environment contexts for temperature, nominal temperature, corner/process, supplies or declared conditions, analysis/sweep coordinates, and deterministic random seeds.
  - Separate parameters, environment, small-signal noise, transient stochastic behavior, global variation, and local mismatch.
  - Implement static and dynamic validity-envelope checks, applicability/accuracy metadata, violation policy, source-located diagnostics, and manifests.
  - Add PVT/seed matrix determinism and envelope-boundary fixtures.

- [ ] **Increment 140 — Solver capability profiles and simulator/model ABI seam**
  - Define solver-neutral residual, state, event, derivative, noise, environment, and result interfaces.
  - Publish capability descriptors and negotiation for analyses, DAEs, events, variable topology, noise, derivatives, tolerances, connect rules, mixed-signal bridges, statistics, hierarchy, and result fidelity.
  - Define an OSDI-like external model adapter seam and a future native solver plugin seam without requiring either to define Nodal semantics.
  - Retain adapter versions, options, commands, hashes, capability decisions, diagnostics, and failure classification.

- [ ] **Increment 141 — Verilog-A/Verilog-AMS and solver-facing lowering parity**
  - Prove source-semantic, residual/analysis, and target-lowering correspondence for supported constructs.
  - Implement a capability-checked equation-to-target legalizer that selects direct potential/flow contributions only after dimension, singularity, parameter-envelope, state/event, analysis, numerical-conditioning, and target-capability checks; otherwise introduce explicit auxiliary branches/unknowns where legal or reject the target.
  - Preserve source module hierarchy where legal while using the logically flattened view only for analysis and proof.
  - Emit deterministic state, event, noise, analysis, environment, contribution, source-map, and per-equation legalization metadata, including source equation, residual, selected target form, reason, introduced objects, and target location.
  - Add target reparse, OpenVAF/OSDI where supported, ngspice and mixed-signal adapter differential fixtures, and explicit capability rejection.
  - Do not claim waveform equivalence as proof for unsupported structural transformations.

- [ ] **Increment 142 — Continuous-time validation, scale, and reusable-model qualification**
  - Add DC/operating-point/transient/AC/noise differential suites, event-order and initialization tests, parameter/PVT/seed matrices, cross-simulator comparisons, and failure classification.
  - Exercise large sparse islands, hierarchy, repeated models, deterministic ordering, derivative/Jacobian performance, cache keys, and memory/runtime budgets.
  - Qualify one external reusable analog model package using only public contracts, including model/environment/validity/solver manifests and portability reports.
  - Publish a capability and limitations matrix for higher-index DAE, dynamic topology, advanced analyses, stochastic features, and simulator extensions.

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

## Phase 10 — Foundation comments, FPGA-readiness, and HVL verification-readiness

Detailed rationale and dependent-track plans are in [`dependent-productivity-and-verification-tracks-v0.1-plan.md`](dependent-productivity-and-verification-tracks-v0.1-plan.md). Verification backend ownership is defined by [ADR 0023](../architecture/0023-unified-hvl-native-sim-uvm-uvmms-architecture.md), and direct procedural HDL testbench projections are defined by [ADR 0025](../architecture/0025-generated-procedural-hdl-testbench-projections.md). Foundation reserves only the architecture/public seams needed for future native verification, procedural Verilog/Verilog-AMS testbench, UVM, and UVM-MS work; dependent-track implementations remain outside Foundation.

- [ ] **Foundation Increment 143 — Comment/documentation IR architecture and public API gate**
  - Freeze automatic ScalaDoc/unambiguous leading-comment capture plus an explicit target-neutral comment/documentation API for guaranteed placement.
  - Define stable Comment IR anchors, propagation/orphan policy, directive separation, and semantic-versus-presentation hashing.

- [ ] **Foundation Increment 144 — Scala source-comment capture and Comment IR propagation**
  - Implement Scala 3 source/comment extraction, explicit comment APIs, stable anchors, deterministic propagation, source correlation, and ambiguity/directive diagnostics.

- [ ] **Foundation Increment 145 — Verilog-family comment and documentation lowering**
  - Emit the same Comment IR deterministically to Verilog, SystemVerilog, Verilog-A, and Verilog-AMS plus documentation/source-map manifests without changing semantic HDL identity.

- [ ] **Foundation Increment 146 — FPGA productivity architecture readiness**
  - Freeze reusable-IP requirements, board/platform resources, project implementation intent, portable Constraint IR, stable semantic targets, vendor capability/tool-adapter seams, constraint coverage, normalized reports, debug identities, and build/program provenance.
  - Do not implement vendor constraints, board libraries, FPGA builds/programming, timing-closure exploration, or debug insertion in Foundation.

- [ ] **Foundation Increment 147 — Nodal HVL Verification Semantic IR and public API architecture gate**
  - Freeze target-neutral tests/scenarios, transactions, processes/events/time, drivers/monitors/agents, scoreboards/reference models, constrained stimulus, deterministic replay, functional coverage, properties/checks, register bindings, reusable VIP packaging, and AMS verification extensions.
  - Bind verification endpoints to logical Interface/Register identities rather than generated HDL hierarchy strings.

- [ ] **Foundation Increment 148 — Native verification runtime and generated-SystemVerilog IR readiness**
  - Freeze the native Nodal verification scheduler/runtime contract independently of UVM and the simulator-adapter boundary needed for direct Verilator/Icarus and future open mixed-signal execution.
  - Define a verification-SystemVerilog IR sufficient for generated UVM/VIP classes, interfaces/virtual interfaces, clocking blocks, dynamic containers, processes/events/mailboxes/semaphores, constraints/randomization, covergroups, properties, and DPI/VPI shims.
  - Do not implement complete HVL runtime or UVM generation in Foundation.

- [ ] **Foundation Increment 149 — UVM/UVM-MS projection and vendor-profile architecture readiness**
  - Accept ADR 0023 and freeze Verification Semantic IR -> UVM/UVM-MS projections while keeping `nodal sim` independent of generated UVM.
  - Freeze UVM component/TLM/factory/config/phase/objection/RAL mappings, UVM-MS structural/class bridge identities, vendor-neutral common source, and thin VCS/Questa/Xcelium profile seams.
  - Confine unavoidable vendor `ifdef`s to generated adapter/include units; do not scatter them through common VIP logic.

- [x] **Foundation Increment 152 — Direct procedural HDL testbench projection architecture readiness**
  - Accept ADR 0025 and freeze a Procedural HDL Testbench IR lowering seam beneath the canonical Verification Semantic IR; it is generated-language IR, not a second authoring model.
  - Define portable Verilog-2005 and standards-oriented Verilog-AMS testbench profiles, stable Interface/Register/test/check identities, source maps, manifests, deterministic replay sidecars, normalized results, and artifact hashes.
  - Classify every selected Verification IR operation as embedded, precomputed replay, companion-runtime-required, or unsupported; unsupported behavior must fail generation rather than be silently omitted.
  - Make Icarus the required event-driven portable-Verilog reference, qualify Verilator separately, and permit direct open-source Verilog-AMS execution only after exact capability conformance.
  - Keep Foundation architecture-only: no testbench generator, simulator runner, open AMS harness, UVM/UVM-MS generator, or verification library is implemented here.
  - Evidence: [ADR 0025](../architecture/0025-generated-procedural-hdl-testbench-projections.md), [staging plan](generated-hdl-testbench-projections-v0.1-plan.md), [PR #56](https://github.com/pysolvesemi/Nodal/pull/56), and [closure PR #58](https://github.com/pysolvesemi/Nodal/pull/58).

- [ ] **Foundation Increment 153 — Function-local lexical naming and alias contract**
  - Freeze a target-neutral structured name-path contract with the priority `explicit user name > caller prefix plus function-local binder > lexical binder > outer/member alias > semantic role or sink affinity > generated fallback` while keeping public API v0.3 unchanged.
  - Retain raw Scala binders and aliases for hardware-producing `val`, `var`, and `lazy val` declarations inside ordinary methods, local methods, lambdas, nested blocks, loop bodies, and match branches, together with lexical owner, definition span, invocation context, and provenance.
  - Keep raw Scala spelling separate from target-HDL sanitization and collision resolution; preserve both inner function-local names and outer call-site aliases rather than replacing one with the other.
  - Separate naming from materialization: an expression keeps source-level naming metadata when safely inlined, while `keep`, observability, readable/debug policy, sharing, typing, or target legality independently decides whether an HDL object is emitted.

- [ ] **Foundation Increment 154 — Scala 3 compiler-derived binder and naming-scope capture**
  - Replace runtime stack/source-line inspection as the naming authority with Scala 3 compile-time typed-tree capture of binders, owners, expansion positions, and call-site naming scopes; retain runtime inspection only as a diagnostic fallback.
  - Propagate an outer binding such as `pixelResult` into helper construction so a local `widenedSum` carries the structured path `pixelResult / widenedSum` without requiring user annotations or source changes.
  - Cover multiline right-hand sides, backticked identifiers, private/nested helpers, lambdas, separately compiled libraries or JARs, unavailable source files at elaboration time, and deterministic diagnostics when no trustworthy binder exists.
  - Add compile-positive, compile-negative, macro-expansion, separate-compilation, and source-unavailable fixtures proving that naming does not depend on `StackWalker`, filesystem layout, or runtime source parsing.

- [ ] **Foundation Increment 155 — Helper invocation identity, return aliases, and collision semantics**
  - Define deterministic caller-qualified emission such as `pixelResult_widenedSum`, `leftResult_widenedSum`, and `rightResult_widenedSum` for repeated helper invocations while retaining the original local binder as a source alias.
  - When a helper return and outer binding denote the same final value, prefer the outer call-site name such as `pixelResult` for the emitted object and retain `pixelResult_clippedSum` as an alias; emit a separate local object only when materialization policy requires one.
  - Define nested-helper, local-function, recursion-policy, loop, symbolic-generate, unrolling, cloning, parameterized invocation, and hierarchy qualification rules using definition origin and invocation origin as distinct identities.
  - Resolve collisions with semantic caller paths and stable source/call-site digests only as the final qualifier; never use pass traversal order or an opaque counter as semantic identity.

- [ ] **Foundation Increment 156 — Function-local naming metadata in the Scala bridge and Nodal MLIR**
  - Extend the construction snapshot, bridge protocol, and Nodal MLIR with target-neutral equivalents of `nodal.source_name`, `nodal.source_aliases`, `nodal.name_provenance`, `nodal.lexical_scope`, `nodal.definition_loc`, `nodal.invocation_path`, and `nodal.materialization_boundary`.
  - Preserve raw names, aliases, definition and invocation locations, source maps, and structured paths through parser/printer round trips, normalized textual IR, bridge versioning, cache keys, and deterministic fingerprints.
  - Keep aliases on safely inlined expressions, assign distinct invocation identities to clones or unrolled instances without losing their original binders, and defer target keyword escaping/sanitization to the selected backend.
  - Add byte-stability, bridge round-trip, clone/unroll, source-map, and mutation tests that fail when any source-bound value loses its lexical naming metadata.

- [ ] **Foundation Increment 157 — Name preservation, generated namespaces, and verifier closure**
  - Define mandatory behavior for inlining, materialization, common-subexpression elimination, dead-code elimination, cloning/unrolling, retiming/automatic pipelining, dialect conversion, target lowering, and optimization plugins: source-bound names and aliases survive every semantics-preserving transformation.
  - Add a mandatory verifier that rejects a materialized source-bound value whose lexical name path was lost or replaced by an anonymous fallback, while allowing a safely inlined value to remain metadata-only.
  - Reserve deterministic Nodal-owned generated namespaces such as `_net_<operation>_<stable-index>`, `_reg_<role>_<stable-index>`, `_mem_<role>_<stable-index>`, `_inst_<type>_<stable-index>`, and `_gen_<role>_<stable-index>`; prefer operation, sink, protocol, domain, or structural-role names before the generic fallback.
  - Allocate fallback suffixes only after normalized IR ordering so unrelated pass changes, working directories, JVM identity, or source traversal do not renumber accepted output. Prohibit Nodal-owned `_zz*`, `_T*`, `_GEN*`, `expr_<number>`, and `tmp_<number>` identifiers in accepted HDL.
  - Add pass-by-pass mutation tests, declaration-order permutations, repeated-build goldens, and source-bound-versus-genuinely-unnamed inventories proving that `_net_*` is used only when no meaningful source, caller, sink, or role name exists.

- [x] **Foundation Increment 158 — Equation-oriented analog roadmap synchronization**
  - Record first-class unordered continuous equations separately from additive potential/flow contributions, procedural analog assignments, and conservative connections.
  - Record conservative connection equations, partial/concrete physical-component balance, structural parameters, logical flattening with hierarchy-preserving emission, residual-preserving canonicalization, and capability-checked equation-to-target legalization.
  - Make the Increment 133 equation/component API checkpoint a prerequisite for Increment 32 and add the dependent analog-basic/electrothermal library pilot track without changing frozen public syntax or implementing compiler behavior.
  - Evidence: [roadmap PR #74](https://github.com/pysolvesemi/Nodal/pull/74), [materialization run 33090381745](https://github.com/pysolvesemi/Nodal/actions/runs/33090381745), [Core CI run 33090944261](https://github.com/pysolvesemi/Nodal/actions/runs/33090944261), and passing Increment 13-27 regression runs attached to PR #74.


- [ ] **Foundation Increment 159 — Typed staged Scala `for` ranges for generate and hardware loops**
  - Add public backend-neutral staged range types and concise constructors such as `genRange(lower, upper, step = 1)` and `hwRange(lower, upper, step = 1, maximum = ...)`. Each bound accepts either a Scala `Int` or a legal target-visible integer parameter/constant; overloads or an internal static-bound abstraction must not require users to collapse symbolic parameters into Scala integers.
  - Keep `for index <- 0 until count` as Scala elaboration only. Lower `for index <- genRange(0, LANES)` exactly to existing structural `nodal.generate` semantics and `for index <- hwRange(0, TAPS)` exactly to existing bounded `nodal.hardware_loop` semantics whether `LANES` and `TAPS` are concrete `Int` values or legal symbolic integer parameters/constants. Never inspect the loop body to infer or change staging.
  - Preserve symbolic `genRange` bounds through hierarchy, target-neutral IR, and emitted HDL. For `hwRange`, derive the finite upper envelope from a concrete bound or the symbolic parameter's declared legal range; require `maximum` only when the bound contract otherwise lacks a finite upper envelope, enforce that maximum as a legality constraint, and reject dynamic hardware values or runtime trip counts.
  - Select loop kind before validating the body. `genRange` may create local structural declarations, component instances, connections, generated process regions, and nested legal generation, but it may not mutate the enclosing module's frozen boundary ports. `hwRange` may perform repeated operations inside the enclosing combinational or sequential semantic region but may not create ports, component instances, or new structural hardware objects. A Scala local `val` remains a binder or alias unless it explicitly constructs hardware; component instances, local variables, or `Reg`/`Wire` presence never choose the loop category.
  - Keep plain Scala loops concretely elaborated even when they instantiate modules. Do not silently reconstruct parameterization or change hierarchy, process ownership, naming, or source paths from body-pattern inference. Any future static-repetition compression must be a separately selected proof-carrying optimization, not a source-language staging rule.
  - Make staged-range `for` forms and canonical `generate(...)`/`loop(...)` forms normalize to identical target-neutral IR, diagnostics, source maps, naming/provenance, optimization obligations, and Verilog-family lowering. Treat this as a frontend ergonomic layer, not a new loop kind or backend construct.
  - Derive generated combinational/sequential region and block labels from semantic source roles, caller/local binder paths, sinks/state owners, and symbolic indices. Prohibit generic `COMB_<id>`/`SEQ_<id>` or traversal-counter identities; use a deterministic collision suffix only as the final fallback.
  - Add positive and negative compile fixtures for `Int` and symbolic parameter/constant bounds, parameter-range-derived envelopes, explicit enforced maxima, ascending steps, empty/singleton ranges, nested and mixed loop categories, helper methods, separate compilation, generated names, invalid dynamic bounds, missing finite envelopes, structural creation inside `hwRange`, local alias non-materialization, plain-Scala non-inference, deterministic process labels, and explicit-form-versus-range-form IR/HDL equivalence.
  - Prerequisites: Increments 55 and 58 plus Foundation Increments 153-157. Integrate portable Verilog, open-source equivalence, and Verilog-AMS regression through Increments 65-67 and 72 without changing their target loop semantics.

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

Nodal HVL is canonical. Native/open-source execution, generated procedural Verilog testbenches, and generated UVM are sibling projections of one Verification Semantic IR; no generated backend is the simulation foundation.

- [ ] **Digital Verification Increment 1 — Nodal HVL native digital simulation vertical slice**
- [ ] **Digital Verification Increment 2 — Scenarios, sequences, constrained stimulus, and replay**
- [ ] **Digital Verification Increment 3 — Agents, drivers, monitors, scoreboards, and reference models**
- [ ] **Digital Verification Increment 4 — Functional coverage and verification reporting**
- [ ] **Digital Verification Increment 5 — Properties, protocol checks, and register-model verification**
- [ ] **Digital Verification Increment 6 — Portable Verilog testbench generation**
- [ ] **Digital Verification Increment 7 — Open-source Verilog testbench execution and qualification**
- [ ] **Digital Verification Increment 8 — Verification SystemVerilog and digital UVM generation**
- [ ] **Digital Verification Increment 9 — Commercial simulator profiles**
- [ ] **Digital Verification Increment 10 — Native, Verilog-testbench, and UVM semantic parity**
- [ ] **Digital Verification Increment 11 — Reusable digital VIP qualification**
- [ ] **Digital Verification Increment 12 — Scale, performance, compatibility, and verification release gate**

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
