# Nodal Incremental Development TODO

**Revision:** 1.3
**Created:** 2026-08-20
**Updated:** 2026-08-21
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
- Keep backend spelling out of the public API and target-neutral IR wherever practical.
- Generate deterministic, readable HDL, normalized IR, reports, and diagnostics.
- Keep all mandatory language, elaboration, compiler, backend, simulator-adapter, and test infrastructure under `core/`.
- Reserve `libraries/` for future optional reusable packages. Enforce the one-way dependency `libraries -> core`; core must never depend on a Nodal library.
- Model ordinary synchronous state with an implicit local clock/reset domain and high-level state/update constructs, not source-level Verilog `always` blocks.
- Make every clock-domain crossing and reset-domain crossing explicit through typed semantic primitives. Preserve domain provenance through hierarchy and IR so unsafe crossings fail before HDL generation.
- Prefer clock enables over user-created clocks. Generated clocks, physical clock gates, clock muxes, and reset trees require explicit primitives carrying relationship, mapping, and timing metadata.
- Treat automatic pipelining as deterministic scheduling of an explicit feed-forward transaction graph, not opaque HLS. Never silently change arithmetic, ordering, protocol, clock/reset domains, resource sharing, side effects, or parameterized module identity.
- Distinguish fixed-rate, valid-only, and elastic ready/valid pipelines in the type system. Insert and balance only pipeline-owned registers and protocol buffers inside an approved pipeline region.

## Public API direction

- Prefer short names such as `Module`, `Param`, `ClockDomain`, `Reg`, `Electrical`, `Real`, `Integer`, `Bool`, `Bits`, and `UInt`.
- Preserve analog and mixed-signal terms such as `analog`, `initial`, `on`, `discipline`, `nature`, `V`, `I`, `ddt`, `idt`, `cross`, `timer`, `transition`, and `<+`.
- Do not copy backend event-process syntax into ordinary synchronous source.
- Provide a compact automatic-pipeline surface centered on `pipe`, `delay`, protocol-typed transactions, latency/throughput policies, automatic sideband alignment, and optional hard stage constraints. Do not expose node/link plumbing in ordinary datapath source.
- Use compile-positive and compile-negative fixtures to freeze public names, types, construction forms, imports, and diagnostics.
- Keep ordinary model source backend-neutral and exclude frontend/compiler internals from the future library-author subset.
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

## Automatic pipeline architecture

The proposed architecture is [ADR 0008](../architecture/0008-automatic-pipeline-architecture.md). The candidate API, staged delivery plan, and freeze criteria are in [`automatic-pipeline-api-v0.3-plan.md`](automatic-pipeline-api-v0.3-plan.md), with a machine-readable candidate in [`automatic-pipeline-api-v0.3-surface.json`](automatic-pipeline-api-v0.3-surface.json).

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

Candidate controls are `pipe`, `delay`, `Latency.Auto`, `Latency.Exact`, `Latency.Range`, `Throughput.EveryCycle`, ready-path policy, `stage(value)` as a hard cut, `sameStage { ... }`, and typed fixed/variable-latency operator contracts. Increment 15 compares exact Scala forms; Increment 16 freezes the accepted surface and diagnostics before scheduler implementation.


## Core and future library boundary

```text
user project
    ├── depends directly on Nodal core
    └── may select zero or more independently versioned Nodal libraries
                                      │
                                      └── depend only on published core APIs

Nodal core  -X->  Nodal libraries
```

- `core/` contains the language API, construction frontend, MLIR bridge/compiler, diagnostics, backends, simulation API, adapters, and mandatory tests.
- `libraries/` is reserved for optional reusable models, interfaces, helpers, and verification packages.
- A core-only project must compile without a library checkout or artifact.
- Future libraries receive no privileged access to `internal`, frontend, compiler, backend, or simulator implementation packages.
- Each library will have independent source roots, tests, documentation, artifact identity, semantic version, core compatibility range, and license metadata.

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
│   │   └── testkit/               # Core fixtures and test support
│   ├── compiler/
│   │   ├── include/nodal/         # Dialect, analyses, transforms, conversions
│   │   ├── lib/                   # Native implementations
│   │   ├── tools/nodalc/          # Compiler driver
│   │   └── test/                  # lit/FileCheck/native tests
│   └── integrations/              # OpenVAF, ngspice, commercial adapters
├── libraries/                     # Reserved for future optional packages
├── examples/                      # Analog, mixed-signal, external consumers
├── tests/                         # API, architecture, golden, integration, simulation
├── docs/                          # ADRs, gates, reference, tutorials, roadmap
├── packaging/                     # Core and future independent library publication
└── scripts/                       # Bootstrap, lint, checks, release utilities
```

Empty future-library directories are not committed merely as placeholders.

## Milestones

- **M0 — Foundation:** reproducible builds, CI, clock/reset and automatic-pipeline API freezes, frozen contracts, and enforced core/library boundaries.
- **M1 — First vertical slice:** Scala RC model lowers through MLIR and emits validated Verilog-A.
- **M2 — Analog preview:** useful Verilog-A subset with open-source compilation and simulation regression.
- **M3 — AMS preview:** implicit-domain digital state, automatic fixed/valid/elastic pipelines, CDC/RDC-safe clock/reset architecture, mixed-signal crossings, and Verilog-AMS emission.
- **M4 — Scalable core release:** packaged compiler, complete reference, stable extension points, library-author contract, and compatibility policy.

# Incremental roadmap

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

- [ ] **Increment 12 — Clock/reset public API v0.2 freeze and contract fixtures**
  - Use [ADR 0007](../architecture/0007-implicit-clock-reset-domains.md), [`clock-reset-api-v0.2-plan.md`](clock-reset-api-v0.2-plan.md), and [`clock-reset-api-v0.2-surface.json`](clock-reset-api-v0.2-surface.json) as the mandatory architecture and API candidate.
  - Compile candidates for `Clock`, `Reset`, `ClockDomain.external/from/required/generated`, lexical domain application, `Reg`, `Reg.uninitialized`, `RegNext`, `when`/`elsewhen`/`otherwise`, typed `.domain(...)`, `ResetPolicy`, `ClockRelation`, semantic `Cdc`/`Rdc`, `ClockGate`, `ClockMux`, and the quarantined `nodal.lowlevel.process(event)` escape.
  - Publish `NodalClockResetApi-DG-v0.2.md`, a v0.1-to-v0.2 migration note, and an updated machine-readable public API manifest. Supersede `always(clock.rising)` only for ordinary synchronous state; retain genuine analog/event semantics.
  - Add compile-positive fixtures for single/multiple/generated domains, every reset policy, legal level/Gray/pulse/handshake/FIFO/reset crossings, gates/muxes, analog-event separation, and an external-library consumer.
  - Add compile-negative fixtures for missing domains, direct CDC, multi-bit `Cdc.sync`, unsafe pulses, unsupported relationship assumptions, reset-release/reconvergence hazards, Boolean clocks, ordinary `always`, low-level misuse, and ambiguous/multiple state drivers. Freeze stable diagnostic codes and source locations.
  - Keep frontend/backend semantics inert. Mark this increment `[x]` only after every freeze exit criterion in the detailed plan passes CI.


- [ ] **Increment 13 — Automatic pipeline candidate prototypes and architecture comparison**
  - Add compile-only candidates for `pipe`, `delay`, plain/`Valid`/`Stream` protocols, exact/ranged/auto latency, throughput and ready-path policy, automatic sideband transport and reconvergence balancing, `stage`/`sameStage` constraints, schedule inspection, parameter envelopes, and fixed/variable-latency operator declarations.
  - Compare current Chisel `Pipe`/`ShiftRegister`/`Queue`/`Decoupled`, current SpinalHDL `Node`/`Payload`/`Link`/`Builder`, and CIRCT `pipeline`/ESI. Retain their useful semantics without exposing lower-level graph plumbing as Nodal's ordinary API.

- [ ] **Increment 14 — Automatic pipeline public API v0.3 freeze and contract fixtures**
  - Use [ADR 0008](../architecture/0008-automatic-pipeline-architecture.md), [`automatic-pipeline-api-v0.3-plan.md`](automatic-pipeline-api-v0.3-plan.md), and [`automatic-pipeline-api-v0.3-surface.json`](automatic-pipeline-api-v0.3-surface.json) as the mandatory architecture and candidate.
  - Publish `NodalAutomaticPipelineApi-DG-v0.3.md`, a migration note, and a machine-readable frozen public surface. Freeze protocol types, `pipe`/`delay`, latency/throughput/ready-path semantics, input capture, automatic alignment, anchors, reset priority, one-domain restriction, parameter-envelope behavior, published latency, schedule stability, side-effect barriers, and diagnostics before scheduler implementation.
  - Add positive fixtures for fixed-rate, valid-only, elastic, exact/ranged/internal-auto latency, sideband/reconvergence alignment, hard constraints, envelope-safe parameterized HDL, fixed/variable-latency operators, and external-library use.
  - Add negative fixtures for missing domains, hidden CDC/RDC, live external reads, protocol conversion, missing timing models, impossible latency, side effects, ready loops, unbounded timing parameters, clone-per-value requests, and conflicting constraints. Freeze stable codes and source locations.

## Phase 1 — Compiler vertical slice

- [ ] **Increment 15 — Elaboration, hierarchy, and lexical domain-context kernel**
  - Implement deterministic module construction, ownership, lifecycle, default-domain requirements, lexical domain stack, single-domain inheritance, named multi-domain requirements, typed bindings, and root-domain validation without public Scala implicits, globals, thread-locals, or JVM identity.

- [ ] **Increment 16 — Source locations and deterministic naming**
  - Capture Scala source locations and define stable names for modules, declarations, domains, generated clock/reset ports, synchronizers, FIFOs, reset controllers, crossings, and anonymous expressions.

- [ ] **Increment 17 — Nodal MLIR dialect skeleton**
  - Register the out-of-tree dialect, TableGen organization/docs, generic parser/printer, and a verified placeholder operation.

- [ ] **Increment 18 — Core MLIR module, port, parameter, and domain model**
  - Add target-neutral modules, ports, symbols, instances, symbolic parameters, domain requirements/bindings, clock/reset relationships, state ownership, timing provenance, and crossing operations/types. Reuse CIRCT only after semantic comparison.

- [ ] **Increment 19 — Scala-to-MLIR bridge**
  - Lower deterministic construction state to versioned textual MLIR with source locations and invoke `nodalc` through a clear process protocol.

- [ ] **Increment 20 — Native parse, verify, and pass pipeline**
  - Parse Nodal MLIR, run registered verifiers/passes, print normalized IR, and expose explicit lit/FileCheck-friendly pipelines.

- [ ] **Increment 21 — Cross-layer diagnostic mapping**
  - Map parser, verifier, pass, backend, external-tool, domain-binding, CDC, RDC, gate/mux, and waiver diagnostics back to Scala locations and stable codes.

- [ ] **Increment 22 — Backend framework and capability profiles**
  - Add translation registration, deterministic output handling, `verilog-a`/`verilog-ams` profiles, and explicit unsupported-feature errors.

- [ ] **Increment 23 — Minimal analog expression and contribution IR**
  - Add real literals, parameter references, arithmetic, electrical potential access, analog region, and contribution sufficient for a minimal RC equation.

- [ ] **Increment 24 — RC filter end-to-end vertical slice**
  - Compile Scala RC through construction, Nodal MLIR, verification, and Verilog-A emission with exact golden output and failures.

- [ ] **Increment 25 — Deterministic output and reproducibility contract**
  - Prove byte-identical MLIR, HDL, domain manifests, and CDC/RDC reports across repeated builds and valid traversal orders.

## Phase 2 — Analog language and Verilog-A profile

- [ ] **Increment 26 — Natures and disciplines**
  - Implement units, access functions, tolerances, domains, potential/flow associations, declarations, imports, and compatibility.

- [ ] **Increment 27 — Electrical nodes, nets, and branches**
  - Implement scalar nodes, ground/reference behavior, implicit/named branches, directions, connectivity, aliases, and ownership.

- [ ] **Increment 28 — Parameters, constants, ranges, and units**
  - Implement supported parameter kinds, constraints, constant expressions, overrides, unit-aware literals, and lossless native HDL rendering.

- [ ] **Increment 29 — Analog numeric types and expression typing**
  - Define promotion, physical compatibility, comparisons/logical results, conditionals, invalid operations, and folding boundaries.

- [ ] **Increment 30 — Potential and flow access functions**
  - Implement `V`, `I`, discipline-specific access, one/two-node forms, branches, probes, and validation.

- [ ] **Increment 31 — Analog blocks and contribution semantics**
  - Implement analog regions, `<+`, potential/flow contributions, equation participation, ordering, and illegal procedural use.

- [ ] **Increment 32 — Analog variables and procedural assignment**
  - Implement local variables, initialization, procedural assignment, scopes, read-before-write diagnostics, and lowering.

- [ ] **Increment 33 — Analog control flow**
  - Implement conditionals, case, bounded loops, break/continue where supported, and static/runtime legality.

- [ ] **Increment 34 — Differential and integral operators**
  - Implement `ddt`, `idt`, initial conditions, context restrictions, and semantics-preserving simplification.

- [ ] **Increment 35 — Time and waveform operators**
  - Implement `transition`, `slew`, `absdelay`, `$abstime`, `$bound_step`, units, continuity, and diagnostics.

- [ ] **Increment 36 — Analog events**
  - Implement `cross`, `above`, `timer`, initial/final step, event composition, tolerances, and controlled statements.

- [ ] **Increment 37 — Mathematical and simulator functions**
  - Add a versioned registry with type/arity checking, constant evaluation, analysis queries, and backend spelling.

- [ ] **Increment 38 — Noise operators**
  - Implement white, flicker, and table noise with analysis, naming, units, and capability checks.

- [ ] **Increment 39 — Laplace and discrete transfer operators**
  - Implement supported Laplace/Z-domain forms, coefficient arrays, constant requirements, denominator validation, and emission.

- [ ] **Increment 40 — User-defined analog functions**
  - Implement typed declarations, arguments, locals, returns, recursion/overload policy, resolution, and lowering.

- [ ] **Increment 41 — Analog hierarchy and parameterized instances**
  - Implement instances, named ports, symbolic overrides, legal arrays, hierarchy verification, and recursion errors.

- [ ] **Increment 42 — Arrays and elaboration-time generation**
  - Implement fixed arrays, indexing/slices, Scala elaboration loops, target generate constructs, and static bounds.

- [ ] **Increment 43 — Analysis state and environmental constructs**
  - Implement analysis-dependent behavior, temperature/environment access, initial/final semantics, and portability policy.

- [ ] **Increment 44 — Analog canonicalization passes**
  - Implement safe folding, algebraic normalization, dead declaration removal, branch/access normalization, and no contribution reordering.

- [ ] **Increment 45 — Analog semantic lint suite**
  - Detect floating nodes, discipline conflicts, branch misuse, unit errors, unreachable events, discontinuities, parameter risks, and portability hazards.

- [ ] **Increment 46 — Verilog-A capability profile and feature matrix**
  - Publish exact `.va` coverage, reject AMS-only constructs early, document simulator portability, and expose machine-readable features.

## Phase 3 — Open-source analog validation and testbench support

- [ ] **Increment 47 — OpenVAF compile validation**
  - Detect versions/features, compile generated models, classify expected limitations, and retain diagnostics.

- [ ] **Increment 48 — ngspice simulation harness**
  - Add OSDI loading, generated SPICE benches, transient/DC/AC runs, timeout/error handling, outputs, and CI smoke simulation.

- [ ] **Increment 49 — Scala simulation API v0.1**
  - Add compilation, source creation, clock/reset-domain stimulus, related/asynchronous clocks, analyses, sweeps, measurements, tolerances, and assertions without hiding tool evidence.

- [ ] **Increment 50 — Waveform and result model**
  - Parse typed time/frequency/sweep results, preserve units, stream large data, and provide comparison/assertion utilities.

- [ ] **Increment 51 — Analog regression suite**
  - Cover RC/RLC, diode, controlled source, amplifier, comparator, oscillator/VCO, hierarchy, events, sweeps, and failures as core fixtures.

- [ ] **Increment 52 — Cross-tool analog portability checks**
  - Add an optional second tool adapter, tolerance-based comparisons, and language-versus-tool failure classification.

## Phase 4 — Digital semantics, clock/reset domains, mixed signal, and Verilog-AMS

- [ ] **Increment 53 — Digital type and port layer**
  - Add bit/logic, signed/unsigned vectors, integers, reals, nets/variables, directions, four-state policy, and compatible CIRCT lowering.

- [ ] **Increment 54 — Digital combinational expressions and continuous assignments**
  - Add arithmetic, logic, bitwise, comparisons, concatenation, extraction, conditionals, width/sign rules, and continuous assignment.

- [ ] **Increment 55 — Implicit-domain synchronous state and register semantics**
  - Implement `Reg`, `RegNext`, reset/uninitialized state, `when` priority, enables, state machines, memory-port ownership, and CIRCT sequential lowering without exposing normal `always` syntax.

- [ ] **Increment 56 — Clock/reset domains, CDC/RDC primitives, and low-level event escape**
  - Implement domain construction/application, external/default/generated binding, relationship graphs, reset policies, async-assert/sync-release, timing provenance, all semantic CDC/RDC operations, gates/muxes, waivers, and restricted low-level processes.

- [ ] **Increment 57 — Domain-aware digital hierarchy and parameterization**
  - Implement default-domain inheritance, typed named-domain binding, inferred clock/reset ports, symbolic parameters, domain-polymorphic modules, generate behavior, and deterministic variants only for material edge/reset differences.

- [ ] **Increment 58 — Pipeline transaction graph, latency provenance, and IR contract**
  - Represent fixed-rate, valid-only, and elastic regions as single-domain feed-forward transaction graphs with protocol tokens, transaction identity, stage/latency variables, sideband demand, reconvergence constraints, exact/ranged latency, hard anchors, reset/control policy, parameter envelopes, and operation delay/latency metadata. Document selective CIRCT reuse.

- [ ] **Increment 59 — Fixed-rate and valid-only automatic scheduling**
  - Schedule acyclic II=1 datapaths under exact/ranged/auto latency and target-period constraints; insert pipeline-owned registers, balance operands and sidebands, propagate `Valid` bubbles, preserve finite-width semantics, and emit deterministic schedules, reports, normalized IR, and golden Verilog-AMS.

- [ ] **Increment 60 — Elastic automatic pipeline and backpressure synthesis**
  - Lower `Stream[T]` regions to full-throughput ready/valid stages with elastic registers, skid buffers, registered-ready cuts, bubble/stall propagation, capacity accounting, ready-loop checks, stall-stability assertions, and proofs of no loss, duplication, or reordering.

- [ ] **Increment 61 — Timing/resource models and target-driven partitioning**
  - Add versioned generic, FPGA, ASIC, simulator, and user operation models covering width/sign-dependent delay, fixed multi-cycle latency, implementation choices, resource preferences, uncertainty, and finite parameter envelopes. Implement target scheduling with infeasibility diagnostics and optional synthesis-feedback import without claiming timing closure from estimates.

- [ ] **Increment 62 — Pipeline controls, anchors, memories, and multi-cycle units**
  - Freeze and implement typed flush/cancel/replay and commit barriers, reset/stall/enable priority, named hard cuts, same-stage groups, synchronous memory latency/ordering, fixed-latency blocks, and elastic wrappers for variable-latency units. Reject or isolate side effects that cannot move safely.

- [ ] **Increment 63 — Hierarchical composition, schedule stability, and bounded retiming**
  - Compose regions/modules through explicit latency/protocol contracts, generate stable stage names and schedule hashes, diagnose latency drift, export reports/debug mappings, and retime only pipeline-owned registers inside declared boundaries—not across user state, CDC/RDC, analog boundaries, memories, side effects, parameter-envelope barriers, or observability anchors.


- [ ] **Increment 64 — Discrete real and mixed-signal net types**
  - Implement `real`, `wreal` or profile equivalents, resolution, direction, sampling/update semantics, and portability.

- [ ] **Increment 65 — Analog/digital access and conversion semantics**
  - Implement destination-domain samplers, thresholds/comparators, quantization, source-domain-aware DAC updates, transition shaping, event synchronization, and provenance transfer.

- [ ] **Increment 66 — Connect modules and connect rules**
  - Implement declarations, rules, discipline insertion, direction/resolution analysis, hierarchy-wide application, and conflicts.

- [ ] **Increment 67 — Mixed-domain, CDC/RDC, and scheduling verifier**
  - Verify domain bindings, direct/combinational crossings, multi-bit misuse, pulses, reconvergence, reset release/reconvergence, generated clocks, gates/muxes, analog/digital legality, conversion loops, drivers, waivers, and profile restrictions.

- [ ] **Increment 68 — Complete Verilog-AMS backend skeleton**
  - Emit explicit inferred clock/reset ports, event processes lowered from high-level state and automatic schedules, fixed/valid/elastic pipeline registers and control, synchronizers/FIFOs, reset logic, gates/muxes, analog/digital declarations, disciplines, connect constructs, hierarchy, parameters, latency/schedule metadata, and source maps.

- [ ] **Increment 69 — ADC and DAC mixed-signal vertical slices**
  - Compile/check or simulate ADC/DAC models using implicit domains, automatically scheduled fixed and elastic digital datapaths, explicit sampling/drive, legal CDC, reset policies, parameter-envelope-safe scheduling, hierarchy, pipeline/CDC/RDC reports, and deterministic parameterized Verilog-AMS.

- [ ] **Increment 70 — PLL/comparator mixed-signal vertical slice**
  - Exercise analog state, generated clocks, digital events, cross-domain conversion, feedback, reset behavior, and diagnostics.

- [ ] **Increment 71 — Verilog-AMS simulator adapter interface**
  - Define pluggable compile/elaborate/run adapters, discovery, licensing-safe CI, log normalization, and optional local regression.

- [ ] **Increment 72 — Portable and full AMS profiles**
  - Publish portable/full standard-oriented and simulator-extension profiles with machine-readable feature coverage and no accidental leakage.

- [ ] **Increment 73 — UVM-MS interoperability hooks**
  - Generate metadata, wrappers, or interfaces needed for UVM-MS integration without embedding a second verification methodology.

- [ ] **Increment 74 — Verilog-AMS conformance suite**
  - Build standards-oriented positive/negative tests, practical round trips, feature coverage, and simulator-result classification.

## Phase 5 — Extensibility, scale, documentation, and release

- [ ] **Increment 75 — Compiler pass, extension, and library-author API**
  - Stabilize pass registration, dialect interfaces, analysis preservation, custom lints, out-of-tree examples, and the minimal supported library-author surface.

- [ ] **Increment 76 — Versioned IR and bridge compatibility**
  - Add version metadata, supported upgrades, old-version fixtures, and explicit unknown-future-version rejection.

- [ ] **Increment 77 — Incremental build and compiler caching**
  - Cache construction, normalized MLIR, native compilation, reports, and backend outputs by content/toolchain/profile hashes with proven invalidation.

- [ ] **Increment 78 — Future library architecture and publication contract**
  - Define module conventions, Maven coordinates, resources, independent versions, core ranges, conflicts, licenses, offline use, and external public-API-only fixtures without publishing an official library yet.

- [ ] **Increment 79 — Complete language reference and API documentation**
  - Cover syntax, semantics, domains, CDC/RDC, reset, automatic pipeline protocols/policies, latency/throughput and parameter-envelope contracts, stage controls, schedule reports/diagnostics, analog/mixed-signal boundaries, profiles, constraints, simulators, libraries, and migration.

- [ ] **Increment 80 — Tutorials and cross-project reuse examples**
  - Add progressive analog/AMS/domain tutorials, patterns, anti-patterns, and standalone external consumer projects.

- [ ] **Increment 81 — Cross-platform core packaging**
  - Produce checksummed Scala/native bundles for supported Linux/macOS first, with Windows strategy, source fallback, and future library hooks.

- [ ] **Increment 82 — Reproducible release, provenance, and SBOM**
  - Add release automation, checksums/signatures where possible, dependency SBOM, toolchain provenance, licenses, and rebuild verification.

- [ ] **Increment 83 — Performance and scalability benchmarks**
  - Benchmark construction, MLIR size, pipeline graph construction, scheduling/model lookup, parameter-envelope analysis, sideband and elastic-control generation, schedule reporting, domain provenance, CDC/RDC analysis, pass time, memory, HDL, multi-domain/pipeline hierarchy, caching, and simulation launch.

- [ ] **Increment 84 — Public API v1 review and compatibility policy**
  - Review v0.1/v0.2/v0.3 implementation experience, implicit domains, resets, crossings, automatic pipeline protocol/latency/parameter semantics, schedule stability, stage controls, and low-level escape; approve only justified changes and define semantic versioning, deprecation, and source compatibility.

- [ ] **Increment 85 — Nodal core preview release**
  - Publish the supported preview with frozen API revision, toolchain pins, capability matrices, installation, examples, known limitations, library-author contract, and reproducible evidence.

- [ ] **Increment 86 — Future SystemVerilog-AMS backend research gate**
  - Reassess the current standard, map IR coverage, identify required changes, and approve or reject implementation through a separate gate without speculating syntax into the stable API.

## Deferred reusable library roadmap

No official reusable model/component library is implemented by Increments 0-86. After the core API, extension surface, packaging model, and preview release are proven, independently approved library roadmaps may populate `libraries/` or separate repositories while preserving the public-core dependency contract.

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
- CIRCT dialects: <https://circt.llvm.org/docs/Dialects/>
- Chisel modules and implicit clock/reset: <https://www.chisel-lang.org/docs/explanations/modules>
- Chisel sequential circuits: <https://www.chisel-lang.org/docs/explanations/sequential-circuits>
- Chisel multiple clock domains: <https://www.chisel-lang.org/docs/explanations/multi-clock>
- Chisel reset semantics: <https://www.chisel-lang.org/docs/explanations/reset>
- SpinalHDL clock domains: <https://spinalhdl.github.io/SpinalDoc-RTD/dev/SpinalHDL/Structuring/clock_domain.html>
- SpinalHDL clock-crossing diagnostics: <https://spinalhdl.github.io/SpinalDoc-RTD/master/SpinalHDL/Design%20errors/clock_crossing_violation.html>
- Chisel `Pipe`, `ShiftRegister`, `Queue`, and ready/valid API: <https://www.chisel-lang.org/api/latest/chisel3/util/>
- SpinalHDL pipeline library: <https://spinalhdl.github.io/SpinalDoc-RTD/master/SpinalHDL/Libraries/Pipeline/index.html>
- CIRCT pipeline dialect: <https://circt.llvm.org/docs/Dialects/Pipeline/>
- CIRCT ESI channel buffers: <https://circt.llvm.org/docs/Dialects/ESI/>
- Verilog-AMS standards: <https://accellera.org/downloads/standards/v-ams>
- SystemVerilog-AMS working group: <https://accellera.org/activities/working-groups/systemverilog-ams>
