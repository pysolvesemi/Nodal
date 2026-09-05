# ADR 0023: Use one Nodal HVL semantic IR with native-simulation and UVM/UVM-MS projections

- **Status:** Accepted
- **Date:** 2026-08-23
- **Scope:** Hardware verification language, verification IR, simulation runtime, constrained stimulus, transactions, agents, drivers, monitors, scoreboards, coverage, assertions, reusable VIP, UVM, UVM-MS, vendor profiles, source maps, and cross-backend parity

## Amendment — execution and projection eligibility (2026-09-05)

[ADR 0027](0027-hvl-execution-projection-capability-contract.md) is authoritative for execution classes, complete common-plus-extension capture, sibling profile libraries/IRs, live eligibility, pairwise parity and independent release gates. Earlier single-source statements below apply to qualified shared semantics, not arbitrary Scala or every target-specific methodology. Historical acceptance and Increment 152 closure evidence remain intact; the amendment does not claim Foundation 147-149 implementation or acceptance is complete.

## Context

Nodal intends to reduce verification effort as aggressively as it reduces RTL/AMS construction effort. Users should be able to reuse common verification intent across qualified live and generated modes. Ordinary live Scala need not be capturable, and explicit UVM/UVM-MS-only extensions or wrappers do not imply native execution or procedural-Verilog eligibility.

Making UVM itself the canonical semantic model would create several problems:

- `nodal sim` would inherit a commercial-tool-oriented SystemVerilog class-library execution model even when the DUT is run through Verilator, Icarus, ngspice, or another open simulator;
- UVM phases, factory/config mechanisms, virtual interfaces, and simulator scheduling details could leak into Nodal semantics;
- UVM-MS structural/class bridges and vendor extensions could constrain ordinary mixed-signal simulation;
- reusable Nodal VIP would become coupled to SystemVerilog source generation rather than reusable verification intent;
- vendor differences could contaminate common verification logic with conditionals.

At the same time, generated UVM must be structurally idiomatic and reusable with existing commercial verification environments. UVM-MS 1.0 is based on IEEE 1800.2 UVM and adds a standardized mixed-signal verification framework connecting class-based and structural environments. The architecture must therefore support faithful UVM and UVM-MS projections without making them the native execution foundation.

## Decision

Nodal adopts the binding rule:

> **Share verification intent in Nodal HVL. Run live code directly, or capture common semantics plus declared typed profile extensions and select only qualified execution/projection profiles.**

The exact public Scala API is frozen in a later Foundation increment. This ADR freezes semantic ownership and backend boundaries.

## Canonical verification layers

### Nodal HVL source

The source language may express typed transactions, tests, scenarios, sequences, drivers, monitors, agents, scoreboards, reference models, coverage, assertions/properties, clocks/time, concurrency, randomization, configuration, resources, reusable VIP, and mixed-signal verification intent.

### Verification Semantic IR

The canonical IR is independent of UVM, SystemVerilog, and any simulator. It records stable identities and source spans for:

- verification components and hierarchy;
- typed transaction schemas and values;
- stimulus/scenario/sequence graphs;
- processes, waits, events, timers, forks/joins, cancellation, and deterministic scheduling dependencies;
- driver/monitor endpoint bindings to the logical Nodal `Interface` ABI;
- scoreboards, reference-model calls, analysis streams, and transaction correlation;
- random variables, constraints, distributions, seeds, scopes, and replay metadata;
- functional coverage models and sampled values;
- immediate checks and target-neutral formal/simulation properties;
- configuration/resources and override intent;
- register-model bindings to the canonical Register IR;
- analog quantities, tolerances, measurements, continuous/discrete events, PVT/sweep context, and UVM-MS bridge intent where applicable;
- source maps, capability requirements, backend exclusions, and generated-artifact identities.

The IR does not encode UVM class names, macro spellings, simulator command lines, or vendor preprocessor symbols as semantics.

### Native simulation projection

`nodal sim` executes live host-side Nodal HVL through the Nodal runtime; qualified captured components may also execute through that runtime. Arbitrary Scala control does not require complete static IR capture, and generated-only operations require separate live qualification. Verification logic remains in the Scala/JVM-side runtime or another Nodal-owned runtime and directly controls/observes the compiled DUT through versioned simulator adapters.

The initial digital adapters may target Verilator and Icarus without requiring UVM support in those tools. Mixed-signal native execution may coordinate Verilog-A/Verilog-AMS/open-model adapters, ngspice/OpenVAF-compatible paths, and digital simulators according to declared capabilities.

The native runtime owns deterministic Nodal scheduling, seed/replay, transaction/coverage collection, source-level diagnostics, and backend capability checks. A simulator adapter supplies DUT execution, signal access, time/event callbacks, waveforms, and tool-specific result data; it does not define the HVL language.

### Verification SystemVerilog IR

A separate generated-language IR represents the SystemVerilog constructs required to render UVM/UVM-MS testbenches. It is not a second semantic authority.

Its required feature envelope includes, as needed by generated UVM/VIP:

- packages/imports and compilation units;
- classes, inheritance, virtual/interface classes, overrides, polymorphism, parameterized classes, constructors, and visibility;
- enums, structs/unions, arrays, queues, associative arrays, strings, object handles, and casts;
- tasks/functions, automatic variables, references, and arguments;
- processes, `fork`/`join*`, events, mailboxes, semaphores, timing controls, and clocking blocks;
- interfaces and virtual interfaces;
- constrained randomization and constraint blocks;
- covergroups/coverpoints/crosses where selected;
- assertions/properties through the target-neutral property layer;
- DPI/VPI-facing shims where required;
- macros only when required by UVM registration or a selected compatibility profile;
- source locations and deterministic naming.

Nodal does not need to become a general handwritten-SystemVerilog parser to generate this subset. Unsupported generated-language features are capability errors.

### UVM projection

The digital UVM backend maps semantic components into idiomatic UVM concepts such as:

- `uvm_test`, `uvm_env`, `uvm_agent`, `uvm_driver`, `uvm_monitor`, `uvm_sequencer`, `uvm_sequence`, `uvm_sequence_item`, and scoreboard/component classes;
- TLM ports/exports/FIFOs and analysis ports;
- factory registration and explicit override intent;
- configuration/resource transfer where needed;
- objections and phase participation derived from target-neutral lifecycle intent;
- virtual-interface or generated interface bindings;
- register adapters/predictors and generated UVM RAL from the canonical Register IR;
- coverage, report, transaction-recording, and source-map metadata.

The projection may introduce UVM mechanisms to realize Nodal semantics, but those mechanisms are not visible in the canonical IR unless the user explicitly requests a UVM-specific extension.

### UVM-MS projection

The mixed-signal backend extends the same Verification IR with UVM-MS 1.0 concepts and generated structural/class bridges. It preserves:

- mixed digital/analog interface identity;
- continuous/discrete-real quantities and tolerances;
- analog stimulus/monitor operations;
- mixed-signal transactions and scoreboards;
- bridge, connect-rule, analysis, environment/PVT, and measurement provenance;
- native-versus-UVM-MS capability differences.

UVM-MS generation depends on the Foundation AMS semantic/interface architecture, not on ad-hoc real-number shortcuts.

## Vendor profiles

Common UVM/UVM-MS source is vendor neutral by default. Tool-specific differences are isolated in thin profiles for compile/elaboration/run commands, package/include selection, DPI/VPI loading, AMS binding, waveform/report options, and known language/workaround requirements.

When source-level conditional compilation is unavoidable, vendor `ifdef` logic is confined to generated adapter/include packages. Nodal must not scatter vendor conditionals throughout common generated VIP logic.

A generated manifest records:

- standard profile and reference-implementation version;
- simulator/vendor profile;
- feature/capability decisions;
- vendor adaptation files and defines;
- common-source hash versus adapter hash;
- compile/elaboration/run commands;
- source maps and unsupported/approximated features.

## Cross-backend parity

The same Nodal HVL environment may target native simulation and generated UVM/UVM-MS, but parity is defined semantically rather than by identical scheduler implementation.

Required parity includes, where supported:

- transaction values and ordering;
- driver/monitor protocol behavior;
- deterministic seed/replay behavior;
- scoreboard/reference-model decisions;
- coverage sampling intent and normalized results;
- assertion/property outcomes;
- test termination and timeout policy;
- register accesses and predictor behavior;
- analog stimulus/measurement tolerance contracts;
- source-level failure identity.

Backend-specific behavior must be explicitly classified. A feature supported only by UVM, UVM-MS, or the native runtime is not silently ignored by another projection.

## Reusable VIP

A Nodal VIP is a passive reusable package authored against public HVL, Interface ABI, Register IR, and verification APIs. It may contain:

- transaction schemas;
- protocol configuration;
- active/passive agents;
- native drivers and monitors expressed semantically rather than in simulator calls;
- scenarios/sequences;
- protocol assertions;
- functional coverage;
- scoreboards/reference models;
- register adapters;
- mixed-signal bridge/measurement behavior where applicable;
- backend capability declarations.

From one VIP source, Nodal may generate:

- native-simulation BFM/agent behavior;
- digital UVM VIP;
- UVM-MS VIP where the protocol includes mixed-signal semantics;
- documentation and machine-readable metadata.

The public VIP ABI is based on logical interfaces and transactions, not generated hierarchy strings.

## Randomization and replay

Nodal owns the canonical random model and seed hierarchy. Generated UVM may map supported constraints to SystemVerilog randomization, or may use a generated deterministic stimulus stream when exact cross-backend replay is required.

The architecture must distinguish:

- semantic constraint intent;
- random solver/backend used;
- seed hierarchy;
- generated value stream;
- replay mode;
- unsupported constraint features.

Exact cross-solver random sequence identity is not assumed unless Nodal supplies the value stream. Semantic distribution/constraint parity and deterministic replay are required.

## Coverage

Coverage is target neutral in Verification IR. Native simulation records canonical coverage data. UVM generation may emit covergroups or Nodal-generated sampling code depending on capability/profile.

Coverage identity survives across backends. UCIS export/import may be added without making UCIS the authoring model.

## Formal and assertions

The existing target-neutral property architecture remains authoritative. Verification IR references property IDs and sampling contexts rather than embedding raw SVA. Generated UVM/SystemVerilog may emit SVA or procedural checks according to the selected profile. Native simulation evaluates the supported simulation projection directly.

## Relationship to Foundation

The Digital Verification and Analog/Mixed-Signal Verification tracks are blocked until the entire Foundation track is complete.

Foundation must provide architecture and public seams for:

- stable source/hierarchy/interface/register identities;
- clocks/resets, domains, CDC/RDC, protocols, and inout;
- analog topology/equation/event/analysis/environment semantics;
- simulation adapters and deterministic evidence;
- plugin/tool-adapter capability negotiation;
- target-neutral properties;
- Comment IR and source maps;
- Verification Semantic IR and generated SystemVerilog/UVM/UVM-MS backend seams.

Foundation does not implement the later UVM/UVM-MS VIP libraries or commercial-simulator flows.

## Consequences

### Positive

- One testbench source can serve fast open-source/native simulation and commercial UVM/UVM-MS flows.
- Open-source simulation is not blocked by UVM support in Verilator/Icarus.
- Generated UVM remains standard-oriented and reusable.
- UVM-MS can reuse the same transactions, agents, scoreboards, coverage, register models, and source identities as native mixed-signal verification.
- Vendor differences remain thin adapters.
- Nodal VIP can become a true multi-backend verification asset.

### Costs

- Nodal needs a real verification semantic IR and scheduler rather than direct UVM code templates.
- Cross-backend randomization and scheduling parity require explicit policies.
- A substantial verification-SystemVerilog generator is required for UVM.
- UVM-MS vendor/tool differences require ongoing adapter qualification.
- Native mixed-signal simulation remains capability-dependent on available open tools and co-simulation seams.

## Rejected alternatives

### Make UVM IR canonical

Rejected because it would unnecessarily couple native simulation and Nodal semantics to SystemVerilog/UVM implementation mechanisms.

### Generate UVM directly from Scala AST templates

Rejected because reusable VIP, source maps, cross-backend parity, transformations, and vendor adaptation would be fragile.

### Use generated UVM as the only simulation runtime

Rejected because it would make ordinary Nodal simulation dependent on simulators with sufficient UVM support.

### Maintain separate native and UVM testbenches

Independent copies of shared test intent are discouraged because behavior can drift. This does not prohibit explicit profile-specific wrappers, library implementations or generated-only packages; ADR 0027 requires those boundaries rather than a universal implementation class.

### Put vendor `ifdef`s throughout generated testbench logic

Rejected because common semantics become difficult to audit and reuse; vendor conditionals belong in thin adaptation units.
