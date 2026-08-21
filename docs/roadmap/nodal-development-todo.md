# Nodal Incremental Development TODO

**Revision:** 1.1
**Created:** 2026-08-20  
**Status:** Active roadmap  
**Primary language target:** Verilog-AMS 2023  
**Analog-only compatibility target:** Verilog-A  

## Mission

Nodal is a modern Scala 3 hardware-construction language for analog and mixed-signal design and modeling. It shall provide a short public API that stays as close as practical to Verilog-AMS terminology and semantics, while using MLIR/CIRCT as the compiler foundation.

The initial implementation will be built from scratch with modern tooling. It will not carry Scala 2, old-JDK, FIRRTL, Chisel, SpinalHDL, or legacy Nodal compatibility requirements.

## Fixed project direction

- Use the latest stable Scala 3 release available when the toolchain bootstrap increment is implemented. The bootstrap reference on 2026-08-20 is Scala 3.8.4.
- Use the official Scala 3 compiler on a modern JVM. The bootstrap reference is JDK 25.
- Use a pinned Mill 1.x wrapper for the Scala multi-module build.
- Use CMake, Ninja, LLVM, MLIR, and CIRCT for the native compiler. Pin a mutually compatible LLVM/CIRCT revision pair in a checked-in toolchain lock.
- Define an out-of-tree `nodal` MLIR dialect. Do not fork CIRCT unless a later design gate proves that an upstream extension is necessary.
- Reuse CIRCT `hw`, `comb`, `seq`, `sv`, and related dialects for digital constructs when their semantics fit. Do not force analog semantics into FIRRTL or digital-only dialects.
- Treat MLIR as the authoritative compiler IR. The Scala frontend may keep a small elaboration model, but it must lower into MLIR before semantic compiler passes or HDL emission.
- Generate Verilog-AMS as the first complete backend. Generate Verilog-A from the analog-only capability profile.
- Keep backend syntax out of the core IR wherever possible so a future SystemVerilog-AMS backend can be added without redesigning the frontend.
- Generate deterministic, readable HDL suitable for review, simulation, and golden testing.
- Model ordinary synchronous state with an implicit local clock/reset domain and high-level register/update constructs rather than source-level Verilog `always` blocks.
- Make every clock-domain crossing and reset-domain crossing explicit through typed crossing primitives; retain domain provenance through hierarchy and IR so unsafe crossings are diagnosed before HDL generation.
- Prefer clock enables over user-created clocks. Permit clock gates, clock muxes, generated clocks, and reset-tree transformations only through explicit primitives carrying implementation and timing metadata.
- Keep the Scala frontend and native compiler in one monorepo initially, with clean module boundaries so they can be distributed independently later.
- Keep all language, elaboration, compiler, backend, simulator-adapter, and core test infrastructure under the top-level `core/` path.
- Reserve the separate top-level `libraries/` path for optional reusable Nodal packages that may be shared across independent user projects in the future.
- Nodal core must never depend on a Nodal library. A library may depend only on published core APIs and approved extension contracts, never on core implementation internals.
- Do not implement or bundle reusable design libraries in the initial core roadmap. Establish only the directory, dependency, packaging, compatibility, and publication architecture needed to add them safely later.

## Public API direction

The exact API will be frozen by a dedicated design-gate increment before substantial language implementation.

The gate must enforce these principles:

- Prefer short names such as `Module`, `Param`, `Electrical`, `Real`, `Integer`, `Bool`, `Bits`, and `UInt`; avoid names such as `NodalComponent` in normal user code.
- Preserve established analog and mixed-signal terms where Scala syntax permits: `analog`, `initial`, `on`, `discipline`, `nature`, `V`, `I`, `ddt`, `idt`, `cross`, `timer`, `transition`, and `<+`; do not copy Verilog event-process syntax into ordinary synchronous RTL.
- Use concise high-level synchronous constructs such as `ClockDomain`, `Reg`, `RegNext`, and `when`. Reserve a low-level event/process escape hatch for behavior that genuinely cannot be represented by the domain-aware state model.
- Add no `Nodal` prefix merely for branding.
- Keep Scala-specific ceremony out of ordinary model definitions.
- Keep the source language independent of a particular output backend.
- Keep language-required constructs in core; keep optional reusable components, models, interfaces, helpers, and verification packages out of core.
- Make the future library-author surface an explicit, versioned subset of the public core API rather than exposing frontend or compiler internals.
- Use compile-positive and compile-negative fixtures to freeze syntax, types, diagnostics, and imports.
- After the v0.1 API gate is approved, any incompatible API change requires a new versioned design gate and migration note.

## Clock, reset, and timing-domain architecture

Nodal adopts **implicit local domains, explicit crossings, and explicit emitted HDL**. Ordinary source creates state with high-level constructors; generated Verilog-AMS remains explicit about clock/reset ports and event processes.

### Ordinary synchronous source

The intended source shape is:

```scala
final class Counter extends Module:
  val enable = in(Bool)
  val value = out(UInt(8))

  val count = Reg(0.U(8))
  when(enable):
    count := count + 1.U

  value := count
```

The user does not write `always @(posedge ...)` for normal registers. `Reg`, `RegNext`, memories, state machines, and conditional updates capture the current domain. Increment 12 will freeze the exact public spellings after compile-positive and compile-negative evaluation.

A secondary domain is introduced once and then applied lexically:

```scala
val pixel = ClockDomain.external(
  "pixel",
  reset = Reset.asyncAssertSyncRelease(activeLow = true),
  frequency = 148.5.MHz,
)

pixel:
  val x = Reg(0.U(12))
  val pipe = instance(new PixelPipe)
```

The example is directional rather than an already frozen API. The design gate may refine names while preserving the architecture below.

### Domain context and hierarchy

- A module that creates synchronous state acquires a logical default-domain requirement. A child instance inherits the current domain unless its instantiation explicitly selects another domain.
- Pure combinational and analog modules do not gain unused clock/reset ports. Emitted HDL materializes only the domain ports required by the module contract.
- A root design must bind every unresolved domain requirement to external or generated clock/reset resources. A conventional `clock`/`reset` default may be offered only as an explicit emission policy, never by silently choosing reset semantics.
- Domain application is a compiler-managed lexical stack, not a public Scala `implicit`, `given`, thread-local, or mutable global. This avoids Scala initialization-order leaks and keeps parallel elaboration deterministic.
- A register, memory port, child instance, assertion, or sampler captures its domain when it is created. Later assignments cannot silently move state between domains.
- Domain identity and generated names derive from stable source/hierarchy symbols rather than JVM object identity.

### Domain metadata and relationships

- A `ClockDomain` records its clock, active edge, optional frequency/period and phase, optional clock enable, reset contract, and implementation metadata.
- Clock relationships and reset relationships are tracked separately. Sharing a clock does not make two independently released resets RDC-safe.
- Supported clock relations include same, rationally derived, synchronous with known or unknown phase, mutually exclusive, asynchronous, and unknown. Unknown is treated conservatively.
- Derived clocks must be created through domain operations that preserve ratio, phase, gating, mux, and source information. A manual relationship declaration is a checked design contract, not a way to suppress analysis silently.
- Values carry timing provenance through combinational expressions, ports, hierarchy, memories, and mixed-signal boundaries. Constants and elaboration parameters are domain-neutral; state-derived values retain their producing domain.

### CDC and RDC safety

- A value may enter state in another domain only through an approved crossing operation or a proven-safe declared relationship.
- The public architecture provides distinct crossing categories for a level bit or Gray value, pulse/event transfer, coherent request/acknowledge bundle, ready/valid stream through an asynchronous FIFO, reset synchronization, and an explicitly waived unsafe crossing. Candidate concise forms include `syncTo`, `pulseTo`, `handshakeTo`, `streamTo`, `resetTo`, and `unsafeCrossTo`.
- A two-flop synchronizer is legal only for a single-bit level or a value proven to use an appropriate encoding. Applying it independently to an arbitrary multi-bit bus is an error.
- The compiler diagnoses direct asynchronous sampling, unknown-domain consumption, combinational CDC paths, insufficient pulse width, synchronized/unsynchronized reconvergence, independently synchronized bus bits, unsafe generated clocks, and crossing primitives used with incompatible relationships.
- `unsafeCrossTo` requires a source-located waiver, rationale, and report entry. It never removes provenance or makes downstream reconvergence checks disappear.
- CDC primitives attach implementation attributes, formal assumptions, simulation checks, and timing-constraint intent so the generated structure and reports remain aligned.

### Reset architecture

- Reusable modules are reset-policy agnostic where target semantics permit. Domain binding selects synchronous reset, asynchronous reset, asynchronous assertion with synchronized release, power-on initialization, or no reset.
- Reset polarity is normalized at the boundary; ordinary state code responds to the domain reset contract rather than repeatedly inverting reset signals.
- Resettable state must declare its reset value. Resetless or intentionally uninitialized state must be explicit and is reported separately.
- Asynchronous reset deassertion must be synchronized in each destination domain. The RDC pass checks reset crossings, release reconvergence, partial-reset dependencies, reset-tree mixing, and state observed while a related domain remains in reset.
- Multiple reset causes are combined only through a reset-controller/tree primitive that preserves source, assertion, release, and destination-domain metadata.

### Clock enables, gates, and muxes

- Conditional state updates and domain clock-enable metadata are preferred over constructing clocks with Boolean expressions.
- Arbitrary data-to-clock casts and combinational clock generation are errors.
- Power-oriented clock gating and clock selection use explicit `ClockGate` and glitchless `ClockMux`-class primitives with technology mapping, generated-clock relationships, test-enable handling, and constraint metadata.
- A gated or muxed clock creates a derived domain; it does not erase its parent relationship or bypass CDC analysis.

### Analog and mixed-signal boundary

- `analog` regions and `on(cross(...))` remain genuine analog/event semantics. Their existence does not justify exposing Verilog-style `always` as the normal digital state API.
- Analog-to-digital observation uses an explicit sampler, threshold/comparator, or ADC boundary tied to a destination clock domain. Digital-to-analog updates similarly declare their source domain and transition policy.
- The mixed-domain scheduler and verifier preserve analog event provenance separately from digital clock-domain provenance and reject implicit unsafe conversion in either direction.

### Compiler and verification outputs

The domain graph becomes a first-class compiler analysis that can produce:

- early CDC and RDC diagnostics with source locations and stable codes;
- a machine-readable clock/reset/domain manifest and crossing report;
- generated-clock, asynchronous-group, false-path, and synchronizer intent for SDC/XDC-style constraints;
- deterministic clock/reset ports and explicit backend processes;
- simulation clock/reset stimulus, assertions, and randomized reset-release checks;
- formal assumptions for synchronizers, handshakes, asynchronous FIFOs, clock muxes, and reset release;
- hierarchy summaries showing which modules are combinational, analog, single-domain, or multi-domain.

## Development rules

- Implement one increment at a time on a dedicated branch.
- An increment is complete only after code, tests, documentation, and CI evidence are present.
- Mark an increment `[x]` only in the same change that completes it.
- Do not silently broaden an increment. Add a new increment when new work is discovered.
- Keep generated files out of source control unless they are intentional golden fixtures.
- No compiler pass may depend on unstable frontend object identity or source traversal order.
- Every user-visible diagnostic must carry a stable error code and source location when available.
- Every backend must declare its supported feature profile and reject unsupported constructs explicitly.
- Enforce the dependency direction `libraries -> core`; any `core -> libraries` source, build, test, packaging, or generated dependency is an architecture violation.
- Empty future-library directories must not be committed merely as placeholders.

## Core and future library boundary

The top-level paths have different responsibilities:

```text
user project
    ├── depends directly on Nodal core
    └── may select zero or more Nodal libraries
                                  │
                                  └── depend on public Nodal core APIs

Nodal core  -X->  Nodal libraries
```

- `core/` is the language implementation and mandatory developer/runtime tooling: public language API, elaboration, MLIR bridge, compiler, backends, diagnostics, simulation API, and simulator adapters.
- `libraries/` is reserved for optional reusable source packages such as device models, behavioral blocks, interfaces, connect rules, verification helpers, and domain-specific model collections.
- A user must be able to compile a Nodal project with core alone and no library checkout or artifact.
- Each future library must have its own source root, tests, documentation, artifact identity, semantic version, core compatibility range, and license metadata.
- Libraries must compile through the same public entry points available to external user projects. They receive no privileged access to `internal` packages or native compiler implementation details.
- Language and standards conformance features required to compile or emit Verilog-A/Verilog-AMS belong to core. Optional reusable design content belongs to libraries.
- Libraries may initially live in this monorepo, but their dependency and publication contracts must allow them to move to independent repositories without changing Nodal core.
- Actual reusable library development is deferred to a separate future roadmap after the core public API and packaging contracts are proven.

## Target scalable repository structure

```text
Nodal/
├── .github/
│   └── workflows/                 # CI, release, dependency and conformance jobs
├── build.mill                     # Monorepo orchestration and dependency boundaries
├── mill                           # Pinned Mill bootstrap wrapper
├── CMakeLists.txt                 # Native core compiler root
├── CMakePresets.json              # Reproducible native configure/build presets
├── cmake/                         # Shared CMake modules
├── toolchains/
│   ├── lock.json                  # Scala/JDK/Mill/LLVM/CIRCT/CMake toolchain pins
│   ├── checksums/                 # Download integrity metadata
│   └── README.md                  # Supported host and bootstrap instructions
├── core/
│   ├── scala/
│   │   ├── api/                   # Public types and target-neutral user API
│   │   ├── frontend/              # Elaboration, hierarchy, naming and validation
│   │   ├── bridge/                # Frontend-to-MLIR serialization and nodalc invocation
│   │   ├── cli/                   # JVM-side command-line entry points
│   │   ├── sim/                   # Scala simulation and regression API
│   │   └── testkit/               # Compile fixtures and reusable core test support
│   ├── compiler/
│   │   ├── include/nodal/
│   │   │   ├── Dialect/Nodal/     # MLIR dialect, operations, types and attributes
│   │   │   ├── Analysis/          # Domain, connectivity and semantic analyses
│   │   │   ├── Transforms/        # Canonicalization and optimization passes
│   │   │   ├── Conversion/        # Lowering and backend conversions
│   │   │   └── Translation/       # Verilog-A and Verilog-AMS emission interfaces
│   │   ├── lib/
│   │   │   ├── Dialect/Nodal/
│   │   │   ├── Analysis/
│   │   │   ├── Transforms/
│   │   │   ├── Conversion/
│   │   │   └── Translation/
│   │   ├── tools/
│   │   │   └── nodalc/            # Native compiler driver
│   │   └── test/
│   │       ├── Dialect/           # Parser, printer and verifier tests
│   │       ├── Analysis/
│   │       ├── Transforms/
│   │       ├── Conversion/
│   │       └── Translation/       # FileCheck and golden backend tests
│   └── integrations/
│       ├── openvaf/               # Verilog-A compiler adapter and feature probes
│       ├── ngspice/               # Open-source analog simulation adapter
│       └── simulators/            # Optional commercial Verilog-AMS adapters
├── libraries/                     # Reserved; not populated by the initial core roadmap
│   ├── std/                       # Future optional standard/convenience packages
│   ├── models/                    # Future reusable device and behavioral models
│   ├── interfaces/                # Future interfaces, disciplines and connect packages
│   └── verification/              # Future reusable testbench and verification packages
├── examples/
│   ├── analog/                    # Core-language RC, RLC, diode, amplifier examples
│   ├── mixed-signal/              # Core-language ADC, DAC, comparator and PLL examples
│   └── external-library/          # Future consumer fixtures using only published APIs
├── tests/
│   ├── api/                       # Frozen public API compile contracts
│   ├── architecture/              # Core/library and module dependency checks
│   ├── golden/                    # Deterministic generated HDL
│   ├── integration/               # Scala -> MLIR -> HDL vertical tests
│   ├── simulation/                # Open-source and optional commercial regressions
│   └── conformance/               # Language-profile and standards-oriented tests
├── docs/
│   ├── architecture/              # ADRs and compiler/library architecture
│   ├── design-gates/              # Versioned API and semantic approvals
│   ├── language-reference/        # Nodal language and API reference
│   ├── tutorials/                 # User-focused examples
│   └── roadmap/                   # Incremental plans
├── packaging/
│   ├── core/                      # Core JARs, native binaries and distributions
│   └── libraries/                 # Future independent library publication metadata
└── scripts/                       # Bootstrap, lint, release and developer utilities
```

The `libraries/` and `packaging/libraries/` paths are architectural reservations, not current implementation commitments. Empty directories should not be committed merely to match this tree. Each directory is created when its first real increment needs it.

## Milestones

- **M0 — Foundation:** reproducible Scala/native builds, CI, the corrected domain-aware public API, clock/reset architecture gate, and enforced core/library boundaries.
- **M1 — First vertical slice:** a Scala Nodal RC model lowers through MLIR and emits validated Verilog-A.
- **M2 — Analog preview:** useful Verilog-A subset with open-source compilation and simulation regression.
- **M3 — AMS preview:** implicit-domain digital state, CDC/RDC-safe clock/reset architecture, analog/mixed-signal crossings, and Verilog-AMS emission.
- **M4 — Scalable core release:** packaged compiler, language reference, stable extension points, library-author contract, and compatibility policy.

# Incremental roadmap

## Phase 0 — Repository, toolchains, architecture and API contract

- [x] **Increment 0 — Roadmap bootstrap**
  - Add this checkbox-based roadmap, fixed architectural direction, milestone boundaries, and target repository structure as the initial repository commit.

- [x] **Increment 1 — Project charter and standards baseline**
  - Add `README.md`, project goals, non-goals, terminology, supported abstraction levels, Verilog-AMS 2023 baseline, analog-only Verilog-A profile, the core-versus-library scope boundary, and an explicit statement that SystemVerilog-AMS is a future backend target rather than an initial dependency.
  - Evidence: [`README.md`](../../README.md) and commit [`cb4c55f`](https://github.com/pysolvesemi/Nodal/commit/cb4c55f2e12305be3e92b66df0b499b1d4932c2c).

- [x] **Increment 2 — Architecture decision records**
  - Record the Scala frontend/native compiler split, MLIR as authoritative IR, out-of-tree Nodal dialect, selective CIRCT reuse, textual MLIR process boundary for the first implementation, backend capability profiles, top-level `core/` versus `libraries/` separation, and the enforced one-way `libraries -> core` dependency rule.
  - Evidence: [`docs/architecture/README.md`](../architecture/README.md) and commit [`8052eca`](https://github.com/pysolvesemi/Nodal/commit/8052eca5de87244566f84d7d776d513991ef2e83).

- [x] **Increment 3 — Scalable repository skeleton**
  - Create only the `core/` directories and module descriptors required for the initial build. Reserve `libraries/` in architecture/build conventions without creating empty library modules. Add ownership boundaries and automated dependency-direction rules preventing compiler code from depending on frontend internals and all core code from depending on future libraries.
  - Evidence: [`core/modules.toml`](../../core/modules.toml), [`scripts/check_architecture.py`](../../scripts/check_architecture.py), [`tests/architecture/`](../../tests/architecture/), and commit [`300d389`](https://github.com/pysolvesemi/Nodal/commit/300d38949d2152af1c310caf528d0aea81eb0063).

- [x] **Increment 4 — Modern Scala 3 build bootstrap**
  - Re-check the newest stable Scala 3 release, then pin it with JDK 25 and a current Mill 1.x wrapper. Add `core/scala/api`, `core/scala/frontend`, `core/scala/bridge`, `core/scala/cli`, and core test modules with one passing smoke test. Do not add Scala 2 cross-builds.
  - Evidence: [`build.mill`](../../build.mill), [`docs/development/scala-build.md`](../development/scala-build.md), commit [`95b8c91`](https://github.com/pysolvesemi/Nodal/commit/95b8c9155bb92890c04c9e42b68fdf7677432a10), and successful validation run [`32350691664`](https://github.com/pysolvesemi/Nodal/actions/runs/32350691664).

- [x] **Increment 5 — LLVM/MLIR/CIRCT toolchain lock**
  - Select and pin a compatible LLVM/MLIR/CIRCT revision pair, CMake and Ninja requirements, checksums, source-build fallback, and prebuilt-toolchain discovery. Avoid unpinned `main` dependencies.
  - Evidence: [`toolchains/lock.json`](../../toolchains/lock.json), [`toolchains/README.md`](../../toolchains/README.md), [`scripts/check_native_toolchain.py`](../../scripts/check_native_toolchain.py), [`scripts/bootstrap_native_toolchain.py`](../../scripts/bootstrap_native_toolchain.py), commit [`7ea7c2b`](https://github.com/pysolvesemi/Nodal/commit/7ea7c2b6ea8993f9de58e528cc916d7ac7655272), and successful validation run [`32355110533`](https://github.com/pysolvesemi/Nodal/actions/runs/32355110533).

- [x] **Increment 6 — Native compiler bootstrap**
  - Add the out-of-tree CMake project under `core/compiler`, link MLIR/CIRCT, and produce `nodalc --version` plus a native unit-test target without defining language semantics yet.
  - Evidence: [`CMakeLists.txt`](../../CMakeLists.txt), [`core/compiler/tools/nodalc/`](../../core/compiler/tools/nodalc/), [`core/compiler/test/`](../../core/compiler/test/), [`docs/development/native-compiler.md`](../development/native-compiler.md), [`scripts/check_native_compiler_bootstrap.py`](../../scripts/check_native_compiler_bootstrap.py), implementation commit [`72f1d8e`](https://github.com/pysolvesemi/Nodal/commit/72f1d8e3603e94dfd1c22a509b6d9c4438cbac2f), ABI-normalization commit [`1f4a785`](https://github.com/pysolvesemi/Nodal/commit/1f4a785efd99a3d6c2e5b10bb1dd61d2bb9739e8), and successful validation run [`32359466870`](https://github.com/pysolvesemi/Nodal/actions/runs/32359466870).

- [x] **Increment 7 — Unified developer commands**
  - Provide stable commands for bootstrap, core Scala build, core native build, full check, clean, and toolchain diagnostics. Reserve command namespaces for future independently selectable library checks. The same core commands must run locally and in CI.
  - Evidence: [`nodal`](../../nodal), [`scripts/nodal.py`](../../scripts/nodal.py), [`docs/development/commands.md`](../development/commands.md), [`scripts/check_developer_commands.py`](../../scripts/check_developer_commands.py), [`tests/developer/`](../../tests/developer/), implementation commit [`f24a074`](https://github.com/pysolvesemi/Nodal/commit/f24a07461d075a16c9967bdec37fb551d5f66f05), test-isolation fix [`080cfd3`](https://github.com/pysolvesemi/Nodal/commit/080cfd38f64e06751ece5c7ca0c432575db4c2fc), and successful validation run [`32367451896`](https://github.com/pysolvesemi/Nodal/actions/runs/32367451896).

- [x] **Increment 8 — Continuous integration baseline**
  - Add Linux CI for Scala compilation/tests, native compilation/tests, formatting, toolchain-lock validation, and core/library dependency-boundary enforcement. Cache dependencies without caching unverified generated outputs. Add a scheduled dependency-report job that proposes rather than silently applies compiler upgrades.
  - Evidence: [`.github/workflows/ci.yml`](../../.github/workflows/ci.yml), [`.github/workflows/dependency-report.yml`](../../.github/workflows/dependency-report.yml), [`scripts/check_ci_baseline.py`](../../scripts/check_ci_baseline.py), [`scripts/check_format_baseline.py`](../../scripts/check_formatting_baseline.py), [`scripts/dependency_report.py`](../../scripts/dependency_report.py), [`tests/ci/`](../../tests/ci/), [`docs/development/ci.md`](../development/ci.md), [`docs/development/branching.md`](../development/branching.md), implementation commit [`69c31a5`](https://github.com/pysolvesemi/Nodal/commit/69c31a5f4340688dff01a41911ca0a885153bb11), and successful completion validation run [`32396716336`](https://github.com/pysolvesemi/Nodal/actions/runs/32396716336).

- [x] **Increment 9 — Formatting, linting and contribution rules**
  - Add Scalafmt/Scalafix, ClangFormat/ClangTidy where compatible with LLVM style, Markdown checks, commit/PR expectations, package-visibility checks, and rules that public API or core/library boundary changes require a design gate.
  - Evidence: [`.scalafmt.conf`](../../.scalafmt.conf), [`.scalafix.conf`](../../.scalafix.conf), [`.clang-format`](../../.clang-format), [`.clang-tidy`](../../.clang-tidy), [`CONTRIBUTING.md`](../../CONTRIBUTING.md), [`scripts/check_increment9.py`](../../scripts/check_increment9.py), [`tests/lint/`](../../tests/lint/), [`docs/development/style-and-contributions.md`](../development/style-and-contributions.md), completion commit [`80ab4e0`](https://github.com/pysolvesemi/Nodal/commit/80ab4e0764efb82cedf0adff87b1912b8ffd0f21), and successful validation run [`32444753017`](https://github.com/pysolvesemi/Nodal/actions/runs/32444753017).

- [x] **Increment 10 — Public API candidate prototypes**
  - Create non-functional compile prototypes for representative resistor, RC filter, comparator, ADC, DAC, hierarchy, parameter override, analog event, mixed-signal modules, and an external reusable module authored only against proposed public core APIs. Compare alternatives while keeping the API short and close to Verilog-AMS.
  - Evidence: [`CandidateApi.scala`](../../core/scala/api/src/nodal/CandidateApi.scala), [`examples/publicApiCandidates/`](../../examples/publicApiCandidates/), [`examples/externalLibrary/`](../../examples/externalLibrary/), [`NodalPublicApiCandidates-DG-v0.1.md`](../design-gates/NodalPublicApiCandidates-DG-v0.1.md), [`public-api-candidates.md`](../development/public-api-candidates.md), [`scripts/check_increment10.py`](../../scripts/check_increment10.py), [`tests/api/`](../../tests/api/), verified implementation commit [`75f3a65`](https://github.com/pysolvesemi/Nodal/commit/75f3a65911a1cb03bd87afb6d8db22f7ddfcf8ff), successful Increment 10 validation run [`32447235052`](https://github.com/pysolvesemi/Nodal/actions/runs/32447235052), and successful Core CI run [`32447239115`](https://github.com/pysolvesemi/Nodal/actions/runs/32447239115).

- [x] **Increment 11 — Public API design gate and v0.1 freeze**
  - Publish `docs/design-gates/NodalPublicApi-DG-v0.1.md` with exact imports, names, operators, construction rules, backend entry points, examples, rejected alternatives, compatibility policy, core-only API, and the versioned subset permitted to future library authors. Freeze the approved API before substantial implementation.
  - Evidence: [`NodalPublicApi-DG-v0.1.md`](../design-gates/NodalPublicApi-DG-v0.1.md), [`public-api-v0.1.json`](../../core/scala/api/public-api-v0.1.json), [`public-api-v0.1.md`](../language-reference/public-api-v0.1.md), [`CompilerApi.scala`](../../core/scala/api/src/nodal/CompilerApi.scala), [`scripts/check_increment11.py`](../../scripts/check_increment11.py), [`tests/api/test_increment11.py`](../../tests/api/test_increment11.py), validated implementation commit [`613b439`](https://github.com/pysolvesemi/Nodal/commit/613b439eaee72fb2a97cfa9b71231bb2b1467382), and successful full validation run [`32454472753`](https://github.com/pysolvesemi/Nodal/actions/runs/32454472753).

- [ ] **Increment 12 — Clock/reset architecture gate, public API v0.2 revision, and contract fixtures**
  - Publish `docs/design-gates/NodalClockResetApi-DG-v0.2.md` and a migration note that supersede only the v0.1 ordinary synchronous/event subset. Freeze the implicit-local-domain and explicit-crossing architecture, exact `ClockDomain`/reset binding, `Reg`/`RegNext`/`when` syntax, child-domain inheritance, root-domain binding, CDC/RDC primitive categories, clock-gate/mux escape policy, analog-event separation, and diagnostics before frontend implementation.
  - Turn the approved v0.2 examples into compile-positive and compile-negative tests, including default-domain reuse, multiple domains, derived clocks, reset-policy variants, legal single-bit/Gray/pulse/handshake/stream crossings, illegal direct or multi-bit crossings, reset-release failures, an external-library consumer with no internal-package access, and stable diagnostic codes for prohibited or ambiguous usage.

## Phase 1 — Compiler vertical slice

- [ ] **Increment 13 — Elaboration, hierarchy, and lexical domain-context kernel**
  - Implement deterministic module construction, parent/child scopes, declaration and state ownership, the compiler-managed clock/reset-domain stack, child default-domain inheritance, explicit domain overrides, duplicate detection, root-domain requirements, and lifecycle rules behind the frozen API. Prohibit reliance on Scala implicits, thread-locals, mutable globals, or JVM object identity.

- [ ] **Increment 14 — Source locations and deterministic naming**
  - Capture Scala source locations with Scala 3 inline/macro support where useful. Define explicit-name, inferred-name, generated-name, collision, anonymous-expression, domain-symbol, generated clock/reset port, synchronizer, and crossing-instance naming policies independent of JVM object identity.

- [ ] **Increment 15 — Nodal MLIR dialect skeleton**
  - Define and register the `nodal` dialect with TableGen organization, dialect documentation generation, generic parser/printer support, and one verified placeholder operation.

- [ ] **Increment 16 — Core MLIR module, port, parameter, and domain model**
  - Add target-neutral module, port, symbol, instance-reference, parameter declaration/reference, clock/reset-domain requirement and binding, clock/reset relationship, state ownership, timing provenance, and crossing operations/types. Reuse `hw`, `seq`, and related CIRCT constructs only after semantic comparison preserves Nodal domain analysis.

- [ ] **Increment 17 — Scala-to-MLIR bridge**
  - Lower the elaborated Scala model to deterministic textual MLIR with source locations and invoke `nodalc` through stdin/files. Define a versioned bridge protocol and clear process-failure diagnostics.

- [ ] **Increment 18 — Native parse, verify and pass pipeline**
  - Make `nodalc` parse Nodal MLIR, run registered verifiers/passes, print normalized IR, and expose explicit pass-pipeline options suitable for lit/FileCheck tests.

- [ ] **Increment 19 — Cross-layer diagnostic mapping**
  - Map MLIR diagnostics back to Scala source locations and stable Nodal error codes. Cover parser, verifier, pass, backend, external-tool, domain-binding, CDC, RDC, clock-gating/mux, and unsafe-crossing waiver failures.

- [ ] **Increment 20 — Backend framework and capability profiles**
  - Add translation registration, output-file handling, deterministic formatting, `verilog-a` and `verilog-ams` capability profiles, and explicit unsupported-feature diagnostics.

- [ ] **Increment 21 — Minimal analog expression and contribution IR**
  - Add real literals, parameter references, arithmetic expressions, one electrical potential access, an analog region, and a contribution operation sufficient for a minimal RC equation.

- [ ] **Increment 22 — RC filter end-to-end vertical slice**
  - Compile a Scala RC filter through elaboration, Nodal MLIR, verification, and Verilog-A emission. Add exact golden HDL and failure tests.

- [ ] **Increment 23 — Deterministic output and reproducibility contract**
  - Prove repeated builds and different valid source traversal orders generate byte-identical normalized MLIR, HDL, domain manifests, and CDC/RDC reports. Record normalization rules for whitespace, declarations, symbols, parameters, expressions, domain identities, generated ports, synchronizers, and crossing instances.

## Phase 2 — Analog language and Verilog-A profile

- [ ] **Increment 24 — Natures and disciplines**
  - Model nature units, access functions, tolerances, discipline domains, potential/flow association, declarations, imports, and compatibility checks.

- [ ] **Increment 25 — Electrical nodes, nets and branches**
  - Implement scalar nodes, ground/reference behavior, implicit and named branches, port directions, connectivity, aliases, and branch ownership verification.

- [ ] **Increment 26 — Parameters, constants, ranges and units**
  - Add real/integer/string parameters as supported by the target profile, `from`/`exclude` constraints, constant expressions, parameter overrides, unit-aware Scala literals, and lossless HDL rendering.

- [ ] **Increment 27 — Analog numeric types and expression typing**
  - Define real/integer promotion, physical-quantity compatibility, comparison/logical results, conditional expressions, invalid mixed-domain operations, and constant folding boundaries.

- [ ] **Increment 28 — Potential and flow access functions**
  - Implement `V(...)`, `I(...)`, discipline-specific access functions, one-node/two-node forms, branch access, probes, and semantic validation.

- [ ] **Increment 29 — Analog blocks and contribution semantics**
  - Implement analog regions, `<+`, potential/flow contributions, direct and indirect contributions where supported, equation participation, ordering rules, and illegal procedural-use checks.

- [ ] **Increment 30 — Analog variables and procedural assignment**
  - Add local real/integer variables, initialization rules, procedural assignment distinct from contribution, declaration scopes, read-before-write diagnostics, and backend lowering.

- [ ] **Increment 31 — Analog control flow**
  - Add `if`/`else`, `case`, bounded loops, `break`/`continue` where supported, static versus runtime condition rules, and control-flow verification.

- [ ] **Increment 32 — Differential and integral operators**
  - Implement `ddt`, `idt`, initial conditions, nesting/context restrictions, analysis-dependent legality, and simplification rules without changing equation semantics.

- [ ] **Increment 33 — Time and waveform operators**
  - Add `transition`, `slew`, `absdelay`, `$abstime`, `$bound_step`, optional arguments, units, continuity constraints, and unsupported-context diagnostics.

- [ ] **Increment 34 — Analog events**
  - Add `cross`, `above`, `timer`, `initial_step`, `final_step`, event OR-composition, direction/tolerance arguments, event-controlled statements, and deterministic emission.

- [ ] **Increment 35 — Mathematical and simulator functions**
  - Add a versioned function registry for standard math functions, analysis queries, temperature/frequency access, arity/type checking, constant evaluation, and backend spelling.

- [ ] **Increment 36 — Noise operators**
  - Implement white, flicker, and table-driven noise constructs with analysis restrictions, optional naming, unit validation, and feature-profile checks.

- [ ] **Increment 37 — Laplace and discrete transfer operators**
  - Add supported Laplace/Z-domain forms, coefficient arrays, constant-expression requirements, denominator checks, and readable deterministic generation.

- [ ] **Increment 38 — User-defined analog functions**
  - Add typed function declarations, arguments, local variables, return semantics, recursion policy, name resolution, overload policy, and backend lowering.

- [ ] **Increment 39 — Analog hierarchy and parameterized instances**
  - Implement module instances, named port connections, parameter overrides, arrays where legal, hierarchy validation, unresolved references, and recursive-instantiation diagnostics.

- [ ] **Increment 40 — Arrays and elaboration-time generation**
  - Add fixed-size arrays, indexing, slices supported by the language profile, Scala elaboration loops, target generate constructs where needed, and static-bound validation.

- [ ] **Increment 41 — Analysis state and environmental constructs**
  - Add analysis-dependent behavior, temperature/environment access, initial/final semantics, and a documented portability policy for simulator-specific system functions.

- [ ] **Increment 42 — Analog canonicalization passes**
  - Add safe constant folding, algebraic canonicalization, dead declaration removal, branch/access normalization, and common-expression handling with tests proving no semantic reordering of contributions.

- [ ] **Increment 43 — Analog semantic lint suite**
  - Detect floating nodes where determinable, inconsistent disciplines, illegal branch use, dimension/unit mistakes, unreachable events, discontinuity hazards, suspicious parameter ranges, and backend portability risks.

- [ ] **Increment 44 — Verilog-A capability profile and feature matrix**
  - Define exactly which Nodal constructs can emit `.va`, reject digital/AMS-only constructs before translation, document simulator portability, and publish a machine-readable feature matrix.

## Phase 3 — Open-source analog validation and testbench support

- [ ] **Increment 45 — OpenVAF compile validation**
  - Add version detection, supported-feature probes, generated-model compilation, expected unsupported-feature classification, and CI artifacts for OpenVAF diagnostics.

- [ ] **Increment 46 — ngspice simulation harness**
  - Add OSDI loading, generated SPICE testbench support, transient/DC/AC invocation, timeout/error handling, reproducible output capture, and a CI smoke simulation.

- [ ] **Increment 47 — Scala simulation API v0.1**
  - Add a compact API for model compilation, source creation, clock/reset-domain stimulus from frequency and reset contracts, asynchronous and related-clock scenarios, analyses, parameter sweeps, measurements, tolerances, and assertions without hiding the underlying simulator command/evidence.

- [ ] **Increment 48 — Waveform and result model**
  - Parse simulator outputs into typed time/frequency/sweep data, preserve units, support streaming large results, and provide comparison/assertion utilities with numeric tolerance policies.

- [ ] **Increment 49 — Analog regression suite**
  - Add RC/RLC, diode, controlled source, amplifier, comparator, oscillator/VCO, and parameter-sweep examples covering equations, events, hierarchy, and failure diagnostics. Keep these as core regression fixtures rather than publishing them as a reusable user library.

- [ ] **Increment 50 — Cross-tool analog portability checks**
  - Define optional adapters for a second compatible simulator/tool, compare supported results within declared tolerances, and distinguish language bugs from simulator capability differences.

## Phase 4 — Digital semantics, clock/reset domains, mixed signal and Verilog-AMS

- [ ] **Increment 51 — Digital type and port layer**
  - Add logic/bit, signed/unsigned vectors, integers, reals, nets/variables, directions, four-state policy, and lowering to CIRCT `hw` types where semantically correct.

- [ ] **Increment 52 — Digital combinational expressions and continuous assignments**
  - Add arithmetic, logical, bitwise, comparison, concatenation, extraction, conditional expressions, width/sign rules, and continuous assignment using CIRCT `comb`/`sv` constructs where applicable.

- [ ] **Increment 53 — Implicit-domain synchronous state and register semantics**
  - Implement `Reg`, `RegNext`, reset values, explicit resetless/uninitialized state, conditional updates with `when`/`elsewhen`/`otherwise`, clock enables, state machines, and memory-port domain ownership. Lower ordinary synchronous source to CIRCT `seq`/`sv` and backend event processes without exposing `always` as the normal public API.

- [ ] **Increment 54 — Clock/reset domains, CDC/RDC primitives, and low-level event escape**
  - Implement `ClockDomain` construction and lexical application, external/default/generated domain binding, clock and reset relationship graphs, synchronous/asynchronous reset policies, asynchronous-assert/synchronous-release handling, clock-enable preference, explicit gate/mux primitives, timing provenance propagation, CDC/RDC checking, and level/Gray, pulse, handshake, stream/async-FIFO, reset, and waived crossing operations. Add a clearly separated low-level event/process API only for genuine event-driven behavior and document the digital/analog scheduling boundary.

- [ ] **Increment 55 — Domain-aware digital hierarchy and parameterization**
  - Add digital/mixed module instances, default-domain inheritance, explicit per-instance domain binding, inferred clock/reset port requirements, parameter propagation, connections, generate behavior, domain-polymorphic reusable modules, and reuse of CIRCT hardware symbols without duplicating Nodal hierarchy concepts. Require explicit adapters or deterministic policy wrappers for incompatible reset realizations rather than silent cloning.

- [ ] **Increment 56 — Discrete real and mixed-signal net types**
  - Model `real`, `wreal` or applicable Verilog-AMS equivalents, resolution behavior, directionality, sampling/update semantics, and portability profiles.

- [ ] **Increment 57 — Analog/digital access and conversion semantics**
  - Add explicit destination-domain samplers, thresholds/comparators, quantization, source-domain-aware DAC updates, transition shaping, event synchronization, timing-provenance transfer, and strict diagnostics for implicit unsafe analog/digital or clock-domain conversion.

- [ ] **Increment 58 — Connect modules and connect rules**
  - Implement connect-module declarations, connect rules, discipline insertion, direction and resolution analysis, hierarchy-wide application, and conflict diagnostics.

- [ ] **Increment 59 — Mixed-domain, CDC/RDC, and scheduling verifier**
  - Verify analog/digital region legality, clock/reset-domain bindings, direct and combinational crossings, multi-bit synchronizer misuse, pulse transfer, reconvergence, reset release and reset reconvergence, generated clock/gate/mux safety, connection domains, event feedback, conversion loops, multiple drivers, contribution/assignment misuse, unsafe-crossing waivers, and simulator-profile restrictions.

- [ ] **Increment 60 — Complete Verilog-AMS backend skeleton**
  - Emit modules containing analog and digital declarations/regions, explicit inferred clock/reset ports, register/event processes lowered from the high-level state model, synchronizers and async FIFOs, disciplines, connect constructs, hierarchy, parameters, stable source mapping, and clock/reset metadata. Keep analog-only output on the separate Verilog-A profile.

- [ ] **Increment 61 — ADC and DAC mixed-signal vertical slices**
  - Compile and simulate or compile-check representative ADC and digitally controlled DAC models using implicit clock domains, `Reg`-based state, explicit analog/digital sampling, a legal cross-domain transfer, reset-policy coverage, quantization, transition behavior, parameters, hierarchy, CDC/RDC reports, and generated golden Verilog-AMS.

- [ ] **Increment 62 — PLL/comparator mixed-signal vertical slice**
  - Add a realistic control-loop example that exercises analog state, digital events, cross-domain conversion, feedback, and backend diagnostics.

- [ ] **Increment 63 — Verilog-AMS simulator adapter interface**
  - Define pluggable adapters for available commercial simulators, environment discovery, compile/elaborate/run phases, licensing-safe CI behavior, log normalization, and optional local regression execution.

- [ ] **Increment 64 — Portable and full AMS profiles**
  - Publish machine-readable feature profiles for portable Verilog-AMS, full standard-oriented output, simulator-specific extensions, and future SystemVerilog-AMS preparation. Reject accidental profile leakage.

- [ ] **Increment 65 — UVM-MS interoperability hooks**
  - Add generated metadata, wrappers, or interface files needed to integrate Nodal models into UVM-MS environments without implementing a separate verification methodology inside Nodal.

- [ ] **Increment 66 — Verilog-AMS conformance suite**
  - Build standards-oriented positive/negative tests, parser/emitter round trips where practical, feature coverage reporting, and simulator-result classification without copying restricted specification text.

## Phase 5 — Extensibility, scale, documentation and release

- [ ] **Increment 67 — Compiler pass, extension and library-author API**
  - Stabilize pass registration, dialect interfaces, analysis preservation, pipeline configuration, custom lint hooks, and out-of-tree extension examples without exposing unstable compiler internals as public API. Define the minimal supported core surface available to future library authors.

- [ ] **Increment 68 — Versioned IR and bridge compatibility**
  - Add Nodal dialect/bridge version metadata, upgrade diagnostics, textual and bytecode compatibility policy, test fixtures for old supported versions, and explicit rejection of unknown future versions.

- [ ] **Increment 69 — Incremental build and compiler caching**
  - Cache elaboration, normalized MLIR, native compilation, and backend outputs by content/toolchain/profile hashes. Prove cache correctness and deterministic invalidation before enabling CI reuse.

- [ ] **Increment 70 — Future library architecture and publication contract**
  - Define the reserved top-level `libraries/` module convention, independent Maven coordinates, optional native/model resources, per-library versioning, core compatibility ranges, dependency-conflict policy, license metadata, offline use, and the rule that libraries compile only through public core APIs. Add an external-library fixture proving the contract, but do not implement or publish an official reusable model library in this roadmap.

- [ ] **Increment 71 — Complete language reference and API documentation**
  - Generate Scala API docs plus a Nodal language reference covering syntax, semantics, clock/reset/timing domains, CDC/RDC primitives and diagnostics, analog/mixed-signal boundaries, backend profiles, simulator support, generated constraints and reports, core/library ownership rules, library-author compatibility boundaries, and migration rules.

- [ ] **Increment 72 — Tutorials and cross-project reuse examples**
  - Add progressive analog and AMS tutorials, design patterns, anti-patterns, and standalone consumer-project examples. Demonstrate how a separately built example package can reuse public Nodal core APIs without treating that example as an official Nodal library.

- [ ] **Increment 73 — Cross-platform core packaging**
  - Produce checksummed core Scala artifacts and native compiler bundles for supported Linux and macOS targets first, with a documented Windows strategy, source-build fallback, and stable hooks for separately published future libraries.

- [ ] **Increment 74 — Reproducible release, provenance and SBOM**
  - Add release automation, signed/checksummed artifacts where infrastructure permits, dependency SBOM, toolchain provenance, license inventory, and rebuild verification.

- [ ] **Increment 75 — Performance and scalability benchmarks**
  - Benchmark elaboration, MLIR size, domain-provenance propagation, CDC/RDC and relationship analysis, report generation, pass time, memory, HDL generation, multi-domain hierarchy scaling, and simulation-launch overhead. Add regression thresholds based on measured baselines rather than guesses.

- [ ] **Increment 76 — Public API v1 review and compatibility policy**
  - Review the v0.1 and v0.2 gates against implemented experience, including implicit domains, reset contracts, crossing primitives, and the low-level event escape. Approve only justified revisions through a versioned design gate, define semantic versioning, deprecation rules, source-compatibility tests, and the compatibility guarantees provided to independently versioned libraries.

- [ ] **Increment 77 — Nodal core preview release**
  - Publish the first supported core preview with frozen API revision, compiler/toolchain pins, Verilog-A and Verilog-AMS capability matrices, installation instructions, examples, known limitations, library-author contract, and reproducible release evidence. No reusable model library is required for this release.

- [ ] **Increment 78 — Future SystemVerilog-AMS backend research gate**
  - Reassess the then-current Accellera/IEEE standard status, map Nodal IR coverage, identify required dialect changes, and approve or reject implementation through a separate design gate. Do not speculate new syntax into the stable API before this review.

## Deferred reusable library roadmap

No official reusable component/model library is implemented by Increments 0–78. After the Nodal core API, extension surface, package model, and preview release are proven, reusable libraries may receive their own independently approved checkbox roadmap. That future roadmap may populate `libraries/` in this monorepo or use separate repositories while preserving the same public-core dependency contract.

## Roadmap maintenance

When an increment is completed:

1. Change only that increment from `[ ]` to `[x]`.
2. Add links to the implementation PR/commit and evidence under the increment.
3. Record any approved scope change in a versioned ADR or design gate.
4. Keep the next increment recommendation aligned with the first unchecked prerequisite, unless an explicitly independent increment is selected.
5. Never mark an increment complete based only on generated output; retain source, tests, diagnostics, and reproducible commands.

## Initial references

- Scala 3 current releases: <https://www.scala-lang.org/download/>
- Mill build tool: <https://mill-build.org/>
- MLIR dialect definition documentation: <https://mlir.llvm.org/docs/DefiningDialects/>
- CIRCT dialect documentation: <https://circt.llvm.org/docs/Dialects/>
- Chisel sequential circuits and implicit clock/reset: <https://www.chisel-lang.org/docs/explanations/sequential-circuits>
- Chisel multiple clock domains: <https://www.chisel-lang.org/docs/explanations/multi-clock>
- SpinalHDL clock domains: <https://spinalhdl.github.io/SpinalDoc-RTD/dev/SpinalHDL/Structuring/clock_domain.html>
- SpinalHDL clock crossing checks: <https://spinalhdl.github.io/SpinalDoc-RTD/master/SpinalHDL/Design%20errors/clock_crossing_violation.html>
- Verilog-AMS standards: <https://accellera.org/downloads/standards/v-ams>
- SystemVerilog-AMS working group: <https://accellera.org/activities/working-groups/systemverilog-ams>
