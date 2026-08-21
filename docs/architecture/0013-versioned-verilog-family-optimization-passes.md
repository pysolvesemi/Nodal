# ADR 0013: Use versioned typed IR pipelines for Verilog-family optimization passes

- **Status:** Accepted
- **Date:** 2026-08-21
- **Scope:** Plug-in compiler optimization, Verilog/Verilog-A/Verilog-AMS target lowering, external optimization tools, verification, determinism, provenance, and source mapping

## Context

ADR 0012 establishes a manifest-first plugin system with native MLIR passes, named compiler extension points, backend plugins, and out-of-process tool adapters. That architecture is sufficient to load general compiler passes, but it does not by itself define a safe plug-and-play contract for optimizations that are specific to a Verilog-family target.

The phrase **Verilog-family optimization pass** may refer to materially different operations:

- a target-neutral optimization performed before backend selection;
- a digital optimization over Nodal/CIRCT hardware IR;
- a Verilog-specific legalization or structural optimization;
- a Verilog-A equation or contribution optimization;
- a Verilog-AMS mixed-signal hierarchy or connect-rule optimization;
- a synthesis transformation performed by Yosys or another external tool;
- a text filter applied after HDL emission.

Treating all of these as arbitrary text-to-text filters would be unsafe. A textual pass could silently:

- specialize or erase symbolic parameters;
- clone modules per parameter value;
- change finite-width arithmetic or overflow behavior;
- alter clock/reset, CDC/RDC, pipeline latency, or protocol semantics;
- reorder analog contributions or events;
- change physical dimensions or continuous-time equations;
- invalidate source maps and diagnostics;
- introduce constructs unsupported by the selected backend profile;
- produce output that is not equivalent to the verified compiler IR.

Digital synthesis frameworks demonstrate that composable pass systems are valuable. Yosys is organized around scripts that combine passes and can load additional commands from plugins. MLIR and CIRCT similarly use typed IR, pass managers, analysis preservation, verifiers, and staged lowering. Nodal should retain that composability while preserving Nodal-specific symbolic parameter, AMS, domain, pipeline, and evidence contracts.

## Decision

Nodal supports plug-and-play Verilog-family optimization through **versioned typed IR pipelines**, not arbitrary semantic text rewriting.

The binding rule is:

> **Optimize the highest valid typed representation, declare every semantic effect, verify every mutation, and commit only a validated candidate.**

The existing plugin graph from ADR 0012 remains the resolver, loader, trust, packaging, provenance, and caching foundation. ADR 0013 adds the target-family pass model, extension points, verification obligations, and transactional acceptance rules.

## Optimization layers

A pass must declare exactly one primary input/output representation and extension point.

### Layer 1 — Target-neutral Nodal IR

This is the preferred layer for optimizations that do not depend on HDL spelling.

Examples:

- constant folding under symbolic-parameter rules;
- dead declaration removal;
- safe algebraic normalization;
- hierarchy cleanup;
- common subexpression elimination where effects permit;
- domain, protocol, quantity, and effect-aware canonicalization;
- automatic-pipeline scheduling and bounded retiming;
- sampled-model recurrence simplification.

These passes preserve backend neutrality and are reusable across Verilog, Verilog-A, and Verilog-AMS.

### Layer 2 — Digital CIRCT/MLIR hardware IR

Digital-only transforms may operate over approved CIRCT dialects or Nodal digital IR before portable Verilog emission.

Examples:

- combinational simplification;
- sequential canonicalization;
- memory legalization;
- mux and arithmetic restructuring;
- technology-independent resource mapping;
- approved clock-enable or pipeline transformations;
- preparation for Yosys or another synthesis flow.

The pass must preserve Nodal width, signedness, reset, domain, CDC/RDC, protocol, latency, and symbolic-parameter contracts.

### Layer 3 — Typed target HDL IR

Nodal defines versioned internal target representations for:

```text
nodal-hdl-verilog
nodal-hdl-veriloga
nodal-hdl-verilogams
```

These representations model target declarations, expressions, statements, modules, parameters, generate constructs, analog contributions, events, disciplines, connect constructs, attributes, comments, and source-map anchors without reducing the design to unstructured text.

Target-specific optimization, legalization, and instrumentation passes run here.

Examples:

- portable-Verilog legalization;
- deterministic aggregate flattening cleanup;
- parameter/generate normalization;
- Verilog-A expression factoring that preserves equation semantics;
- Verilog-AMS connect-rule and declaration canonicalization;
- target-specific attribute insertion;
- simulator-compatibility rewrites approved by a capability profile;
- source-level instrumentation for simulation or formal verification.

### Layer 4 — Emitted text

Post-emission text plugins are limited to operations declared **non-semantic**, such as:

- whitespace and formatting;
- line wrapping;
- comment formatting;
- deterministic banner generation;
- source-map line-table finalization;
- artifact packaging.

A text plugin may not change tokens that affect HDL semantics. Semantic text-to-text optimization is not part of the normal SPI.

A legacy external optimizer that accepts and returns HDL text must be wrapped as an out-of-process transform, reparsed into a supported typed representation, fully verified, and accepted only under the same proof policy as any other semantic pass.

## Pass identity and descriptor

Every optimization pass has a stable globally qualified ID and independent semantic version, for example:

```text
org.nodal.opt.verilog.constant-fold
com.acme.opt.verilog.clock-gate
com.acme.opt.veriloga.factor-linear-contributions
com.acme.opt.verilogams.flatten-connect-rules
```

A pass descriptor declares:

- pass ID and version;
- plugin bundle ID and version;
- pass API/SPI version;
- input and output IR family/version;
- named extension point;
- applicable HDL families and backend profiles;
- pass kind;
- semantic-effect class;
- required analyses and invariants;
- preserved and invalidated analyses;
- legal dialects/constructs introduced or removed;
- symbolic-parameter and hierarchy behavior;
- latency, clock/reset, domain, CDC/RDC, protocol, quantity, and effect behavior;
- source-map behavior;
- determinism and cacheability;
- proof/validation obligations;
- options schema, defaults, and normalized option hash;
- trust/isolation requirement;
- conflicts, ordering constraints, and replacement relationships.

Runtime class names or shared-library symbol names are not pass identities.

## Pass kinds

The initial model distinguishes:

```text
analysis
lint
canonicalization
optimization
legalization
lowering
instrumentation
formatting
report
```

`analysis`, `lint`, and `report` do not mutate semantic IR.

`formatting` may mutate only non-semantic emitted text.

`canonicalization` and `optimization` claim semantic preservation and require the declared verification policy.

`legalization` and `lowering` may change representation but must preserve the source language contract within the selected capability profile.

`instrumentation` may add declared observation, assertion, coverage, trace, or simulation behavior and must be excluded from synthesis artifacts unless the profile explicitly permits it.

## Semantic-effect classes

A pass declares one of:

```text
read-only
representation-preserving
behavior-equivalent
latency-equivalent
protocol-equivalent
profile-legalizing
instrumenting
specializing
```

`specializing` is never implicit. A specializing pass must identify the frozen parameter values or capability choices, emit a specialization manifest, and use an explicitly requested pipeline. It may not participate in the default parameterized-HDL flow.

No pass may claim a generic `unsafe` effect and continue without verification. Unsupported effects are rejected.

## Stable extension points

The initial extension-point family is versioned and includes:

```text
nodal.pre-canonicalize
nodal.post-canonicalize
nodal.pre-target-lowering
nodal.post-target-lowering

digital.pre-circt-lowering
digital.post-circt-lowering
digital.pre-verilog-legalize

verilog.pre-legalize
verilog.post-legalize
verilog.pre-emit

veriloga.pre-legalize
veriloga.post-legalize
veriloga.pre-emit

verilogams.pre-legalize
verilogams.post-legalize
verilogams.pre-emit

hdl.post-emit-format
report-only
```

Each extension point specifies:

- accepted IR family and version;
- invariants guaranteed on entry;
- legal mutation scope;
- required invariants on exit;
- allowed pass kinds/effects;
- mandatory core verifiers;
- proof policy;
- source-map requirements;
- whether hierarchy, latency, protocols, parameters, or target profile may change.

A plugin cannot invent a new globally ordered compiler phase merely by naming it in its manifest. New extension points require a versioned SPI update or a plugin-owned nested pipeline inside an already approved point.

## Pipeline configuration

Optimization selection belongs to compiler/emission configuration, not ordinary model source.

Directional candidate:

```scala
val optimization = OptimizationPipeline(
  profile = OptimizationProfile.Custom,
  passes = Seq(
    PassRef("org.nodal.opt.verilog.constant-fold"),
    PassRef(
      "com.acme.opt.verilog.clock-gate",
      options = Map("minimum-width" -> 4)
    )
  ),
  verification = VerificationPolicy.Required
)

Nodal.emit(
  new DigitalTop,
  EmitOptions(
    backend = Backend.Verilog,
    optimization = optimization
  )
)
```

The exact public configuration names are frozen by the dedicated pass gate. Model libraries do not enable optimization plugins implicitly.

A pipeline may also select a versioned built-in profile such as:

```text
none
debug
portable
area
performance
power
simulation
formal
```

Profile contents, pass versions, ordering, options, and proof policy are resolved into the plugin lockfile and emission manifest.

## Ordering and composition

Pass order is resolved from:

- extension-point order;
- explicit `before`/`after` relations over stable pass IDs;
- required analysis/provider dependencies;
- declared conflicts/replacements;
- profile-owned total ordering where necessary.

Unconstrained independent passes are canonicalized by stable pass ID and instance qualifier only when commutativity is declared and verified. Otherwise, ambiguous order is an error.

A pass cannot depend on discovery order, plugin load order, classpath order, filesystem order, or hash-map iteration.

## Transactional execution

Every semantic pass runs transactionally:

1. the compiler snapshots or hashes the accepted input IR;
2. the pass receives immutable input or an isolated mutable candidate;
3. the pass produces candidate IR plus diagnostics and preservation metadata;
4. the candidate is parsed and structurally verified;
5. mandatory core semantic verifiers run;
6. required equivalence, differential, simulation, or formal checks run;
7. source maps and artifact declarations are validated;
8. only a successful candidate becomes the next accepted compiler state.

A crash, timeout, malformed response, verifier failure, proof failure, nondeterministic result, or undeclared mutation leaves the last accepted state unchanged.

There is no partially accepted compiler state.

## Mandatory invariants

Unless a pass explicitly uses an approved specializing or instrumentation contract, it must preserve:

- module and symbol identity where externally observable;
- native symbolic parameters, ranges, and named overrides;
- one-module-per-structure behavior;
- finite-width arithmetic, signedness, overflow, rounding, and conversion semantics;
- clock/reset domain ownership and reset policy;
- CDC/RDC structure and provenance;
- protocol ordering, capacity, throughput, and backpressure semantics;
- published fixed or bounded latency contracts;
- automatic-pipeline transaction identity and sideband alignment;
- physical dimensions and discipline compatibility;
- analog contribution, event, state, and initialization semantics;
- mixed-signal sampling and drive boundaries;
- backend capability/profile legality;
- deterministic names and source-map anchors;
- declared black-box, memory, external-operation, and effect contracts.

## Verification policies

Verification is selected by pass family and effect, not left to plugin preference.

### Digital Verilog

The required ladder may include:

- Nodal and MLIR/CIRCT structural/semantic verification;
- normalized IR comparison for canonical passes;
- CIRCT LEC where applicable;
- Yosys RTL/netlist equivalence;
- Verilator and Icarus differential simulation;
- SBY safety/cover/selected liveness properties;
- latency- and protocol-aware equivalence for pipelines and streams;
- parameter-envelope matrices without silent specialization.

Yosys optimization plugins are integrated through a pinned out-of-process tool adapter or a separately versioned trusted plugin bundle. Their scripts, loaded plugin binaries, pass order, options, Yosys build ID, output hashes, and proof evidence are retained.

### Verilog-A

Verification may include:

- typed target-IR verification;
- units, dimensions, disciplines, branches, contributions, events, and initialization checks;
- symbolic/algebraic equivalence for a narrowly approved transformation class;
- deterministic normalized equation comparison;
- OpenVAF compilation;
- ngspice or another approved simulator differential regression over a declared parameter/stimulus/analysis envelope;
- tolerances and unsupported-proof limitations recorded explicitly.

A passing finite regression is evidence within the declared envelope, not a proof of global analog equivalence.

### Verilog-AMS

Verification combines:

- analog and digital verifiers;
- domain, CDC/RDC, connect-rule, resolution, and mixed-signal-boundary checks;
- digital equivalence for the digital partition;
- AMS differential simulation for approved scenarios;
- pipeline/protocol/latency checks;
- backend profile and simulator portability checks.

### Formatting/report passes

Non-semantic formatting passes are validated by reparsing, token/semantic fingerprint comparison, source-map consistency, and byte-determinism checks.

## Target HDL representations

The typed target HDL representations are compiler implementation contracts, not public model-authoring APIs.

They must provide:

- versioned serialization suitable for process isolation and fixtures;
- deterministic parse/print round trips;
- stable node IDs and source-map anchors;
- explicit profile/capability annotations;
- lossless symbolic parameter and generate representation;
- analog/mixed-signal constructs unavailable in CIRCT's digital dialects;
- verifier hooks and pass instrumentation;
- conversion to final text only after semantic optimization closes.

The internal representation may be implemented as Nodal-owned MLIR dialects, immutable typed ASTs, or a combination, provided the versioned pass contract remains stable.

## External optimizer adapters

An external optimizer is a `ToolAdapterPlugin` or out-of-process `CompilerPlugin` facet.

It declares:

- accepted input artifact or IR;
- produced output artifact or IR;
- exact tool and plugin versions;
- scripts/commands and environment;
- timeout, resource, and network policy;
- deterministic-seed policy;
- proof/evidence outputs;
- parser and profile used to re-import output;
- unsupported-feature behavior.

External optimization cannot define Nodal semantics. It may produce an alternative implementation only after the Nodal verifier and required equivalence/differential gates accept it.

## Source mapping and diagnostics

Passes preserve stable origin chains from generated constructs to:

- Nodal source location;
- originating plugin and pass;
- original IR node;
- transformed IR nodes;
- emitted HDL token/range;
- external tool diagnostics and evidence.

When one source node becomes many target nodes, the pass emits a deterministic relation. When many nodes combine, all relevant origins remain available.

Diagnostics identify the pass ID/version, extension point, input/output hashes, plugin instance, source locations, and violated invariant or proof obligation.

## Determinism, caching, and provenance

The optimization pipeline is a compiler input.

Cache and provenance keys include:

- resolved pass graph and total order;
- pass/plugin versions and artifact hashes;
- normalized options;
- input/output IR versions and hashes;
- target family/profile;
- preserved/invalidated analyses;
- toolchain and external-tool build IDs;
- proof policy, scripts, seeds, and evidence hashes;
- source-map version;
- trust/isolation mode.

A nondeterministic semantic pass is rejected from shared/release caches. Exploration-only nondeterminism requires explicit mode, retained seed, and cannot produce release evidence without a deterministic replay.

## Trust and isolation

Pass trust classes reuse ADR 0012:

```text
manifest-only
process-isolated
trusted-native
trusted-scala
```

Target-family optimization should prefer process isolation when long-lived compatibility or third-party trust is required.

A trusted in-process native pass must exactly match the pinned Nodal/LLVM/MLIR/CIRCT build identity. A process pass uses versioned IR and cannot mutate compiler memory directly.

## Built-in passes and conformance

Core built-in optimization passes use the same descriptor, extension-point, invariant, evidence, and deterministic-order model as external passes wherever practical.

The conformance kit includes reference plugins for:

- a read-only analysis pass;
- a target-neutral canonicalization pass;
- a digital Verilog optimization pass;
- a Yosys-backed external optimization pass;
- a Verilog-A algebraic pass;
- a Verilog-AMS instrumentation pass;
- a formatting-only pass;
- a deliberately invalid pass for each major diagnostic class.

## Consequences

### Positive

- Users can add optimization passes without forking Nodal.
- The same resolver, lockfile, trust, packaging, and provenance architecture covers design, compiler, backend, tool, and optimization plugins.
- Most optimizations operate on typed IR rather than brittle text.
- Digital passes can reuse CIRCT, Yosys, Verilator, Icarus, and SBY evidence.
- Analog and AMS passes retain dimensions, contributions, events, domains, and validation envelopes.
- Failed plugins cannot corrupt the accepted compiler state.
- Parameterized HDL, source mapping, deterministic output, and cache correctness remain first-class.

### Costs

- Nodal must define and version target HDL representations.
- Semantic transforms require verification infrastructure and may be expensive.
- Analog equivalence generally provides bounded evidence rather than universal proof.
- Plugin authors must declare effects, invariants, analyses, and proof obligations precisely.
- Compatibility across native toolchain revisions requires rebuilds or process isolation.

## Rejected alternatives

- **Allow arbitrary post-emission text optimizers:** loses typed semantics, source maps, and reliable verification.
- **Run every optimization only after Verilog emission:** prevents reuse across backends and makes AMS semantics difficult to preserve.
- **Expose compiler internals directly to Scala plugins:** couples plugins to implementation details and weakens isolation.
- **Trust a pass because it loads successfully:** loading does not establish semantic correctness.
- **Let passes silently specialize parameters:** violates native parameterized-HDL requirements.
- **Let external synthesis output replace Nodal RTL without equivalence:** hides translation and tool errors.
- **Permit plugins to disable core verifiers:** turns optional extensions into semantic authorities.
- **Treat simulator regression as global analog equivalence:** overstates the evidence.
- **Make pass order depend on plugin discovery order:** produces irreproducible hardware.

## Follow-up increments

- Increment 79 freezes the common plugin SPI, manifest, lockfile, trust, and extension boundaries.
- Increment 82 implements native/process compiler plugin loading and named extension points.
- Increment 84 implements packaging, provenance, caching, and the common conformance kit.
- Increment 85 freezes the Verilog-family optimization pass SPI and target representation contracts.
- Increment 86 implements versioned target HDL IR and the transactional pass manager.
- Increment 87 implements the digital Verilog/Yosys optimization path and equivalence ladder.
- Increment 88 implements Verilog-A/Verilog-AMS optimization and bounded differential validation.
