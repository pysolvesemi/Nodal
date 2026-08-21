# Target-HDL Optimization Pass SPI v0.1 Plan

**Status:** Normative roadmap target  
**Architecture:** [ADR 0013](../architecture/0013-structured-hdl-optimization-pass-architecture.md)  
**General plugin dependency:** [ADR 0012](../architecture/0012-versioned-capability-plugin-architecture.md) and [`plugin-spi-v0.1-plan.md`](plugin-spi-v0.1-plan.md)  
**Formal pass gate:** Increment 83  
**Machine-readable candidate:** [`target-hdl-optimization-pass-v0.1-surface.json`](target-hdl-optimization-pass-v0.1-surface.json)

## Goal

Allow independently packaged optimization passes to transform portable Verilog, Verilog-A, and Verilog-AMS generation safely and reproducibly without forking Nodal or editing raw output text blindly.

The binding rule is:

> **Optimize structured target IR, declare semantic effects, reverify every boundary, and retain proof evidence.**

## Why the generic compiler-plugin plan is insufficient by itself

The general plugin SPI already supports MLIR passes, dialects, analyses, named extension points, native/process isolation, manifests, lockfiles, and provenance. Target-HDL optimization adds requirements that must be frozen separately:

- distinct digital and continuous-time semantic preservation models;
- typed structured representations after target lowering;
- backend-profile compatibility;
- symbolic-parameter and hierarchy preservation;
- source-map transformation;
- render/reparse rules;
- deterministic pass ordering;
- digital equivalence/formal obligations;
- Verilog-A/Verilog-AMS equation, event, contribution, noise, and analysis validation;
- explicit optimization profiles;
- prohibition of arbitrary semantic raw-text filters.

## Candidate public/project surface

Optimization is explicit and separate from backend selection.

Directional project configuration:

```toml
[optimization]
profile = "portable"

[[optimization.passes]]
id = "com.acme.nodal.verilog.balance-muxes"
version = "1.2.0"
point = "after-digital-target-lowering"

[optimization.passes.options]
max_fanin = 4
```

Directional Scala configuration when programmatic construction is required:

```scala
val optimization = OptimizationPlan(
  profile = OptimizationProfile.Portable,
  passes = Seq(
    OptimizationPass.plugin(
      id = "com.acme.nodal.verilog.balance-muxes",
      version = "1.2.0",
      options = Map("max_fanin" -> 4)
    )
  )
)

Nodal.emit(
  new Top,
  EmitOptions(
    backend = Backend.Verilog,
    optimization = optimization
  )
)
```

The exact names and constructor forms are frozen by Increment 83. The semantic separation is mandatory:

- `Backend.Auto` chooses a backend only;
- optimization profile selection is explicit;
- installed plugins do not run automatically;
- a pass cannot silently join a built-in optimization profile;
- an emitted artifact records both backend and optimization pipeline.

## Pass classes

### Target-neutral pass

Operates on verified Nodal MLIR before backend-specific lowering.

Use for transformations whose semantics are independent of Verilog spelling or simulator profile.

### Digital target pass

Operates on verified structured digital target IR representing the portable Verilog profile.

The preferred representation selectively reuses CIRCT `hw`, `comb`, `seq`, and `sv` where semantics match, with Nodal-owned operations for contracts not represented directly.

### Verilog-A target pass

Operates on typed Verilog-A target IR preserving disciplines, nodes, branches, access functions, analog regions, contributions, continuous-time operators, events, tolerances, noise, analyses, dimensions, parameters, hierarchy, and source maps.

### Verilog-AMS target pass

Operates on typed Verilog-AMS target IR preserving both Verilog-A semantics and digital/event/connect-rule/mixed-signal structures.

### Render-only pass

May change deterministic formatting, comments, whitespace, declaration grouping, or metadata serialization only. It cannot alter parsed semantics.

### Reparse pass

An external transformation that consumes textual HDL is accepted only when its output is reparsed into the approved target representation, source/capability metadata is reconstructed or retained, mandatory verification succeeds, and the declared proof obligation passes.

## Candidate pass descriptor

Each pass descriptor contains:

```text
id
version
plugin id/version
pass SPI version
HDL family/profile
structured target IR version
extension point
required capabilities/invariants
options schema and normalized options
input/output dialects or node kinds
required/preserved/invalidated/produced analyses
parameterization/hierarchy/source-map effects
semantic preservation declarations
proof/validation class
artifact/report outputs
deterministic/cacheable declaration
```

Stable IDs are globally qualified strings. Scala or C++ implementation class names are not public pass identity.

## Candidate extension points

Increment 83 evaluates and freezes versioned points equivalent to:

```text
before-digital-target-lowering
after-digital-target-lowering
before-verilog-render
after-verilog-reparse
before-analog-target-lowering
after-verilog-a-target-lowering
before-verilog-a-render
after-verilog-a-reparse
after-verilog-ams-target-lowering
before-verilog-ams-render
after-verilog-ams-reparse
render-only
report-only
```

Each extension point publishes:

- accepted IR and profile version;
- guaranteed invariants;
- legal mutations;
- required verifier pipeline;
- available analyses;
- cache and determinism requirements;
- source-map obligations;
- acceptable native/process facets.

## Deterministic pipeline resolution

Pass ordering is resolved from:

- extension point;
- explicit dependencies;
- before/after constraints;
- conflicts and replacements;
- optimization-profile membership;
- pass/profile versions;
- deterministic stable-ID tie-break only where semantics permit.

Discovery order, manifest order, classpath order, shared-library load order, filesystem enumeration, and process completion order do not define semantics.

Cycles, ambiguous replacement, incompatible profiles, and unsatisfied proof adapters are errors before loading executable pass code.

The pass graph is stored in `nodal.plugins.lock` or a linked optimization lock section and contributes to the plugin-plan/build/cache hash.

## Structured target representations

### Digital target representation

The digital representation must preserve:

- modules, ports, hierarchy, instances;
- symbolic parameters, ranges, overrides, and generate;
- finite-width arithmetic and signedness;
- registers, resets, memories, and state machines;
- clock/reset domains and CDC/RDC provenance;
- plain/`Valid`/`Stream` protocol contracts;
- automatic-pipeline latency, throughput, capacity, stalls, and source mapping;
- assertions/formal hooks and backend capabilities;
- deterministic flattening metadata.

### Analog target representation

The Verilog-A representation must preserve:

- nature/discipline definitions;
- electrical and custom nodes;
- branches and access functions;
- contribution identity and equation participation;
- physical dimensions;
- parameter constraints and symbolic expressions;
- `ddt`, `idt`, Laplace/Z, waveform, and delay operators;
- event expressions, tolerances, initial/final behavior;
- noise source names and analysis semantics;
- simulator/environment queries;
- hierarchy, ports, source maps, and capability profile.

### AMS target representation

The Verilog-AMS representation additionally preserves:

- digital nets/variables and event processes;
- explicit clock/reset lowering;
- CDC/RDC and pipeline structures;
- real-number/mixed-signal nets;
- samplers, DAC updates, thresholds, and conversion provenance;
- connect modules/rules and discipline resolution;
- analog/digital scheduling relationships;
- profile-specific simulator extensions.

Text rendering happens only after target representation verification.

## Preservation model

Every pass declares effects on:

```text
types and widths
signedness/overflow/rounding
symbolic parameters and legal envelopes
one-module-per-structure identity
hierarchy, ports, names, and source maps
clock/reset domains and CDC/RDC
protocol ordering, latency, throughput, and capacity
memory and external-operation contracts
physical dimensions
analog branches and contributions
events, tolerances, initialization, and discontinuities
noise identity and analyses
mixed-signal conversions and connect rules
backend capability profile
```

The core pass manager verifies declarations, invalidates/recomputes analyses, and runs required target verifiers after each pass or explicitly verified pass group.

A pass that changes a published interface property must be classified as an explicit transformation, not an ordinary optimization.

## Parameterization policy

All ordinary optimization profiles preserve:

- symbolic HDL parameters;
- parameter constraints;
- target `generate`;
- named overrides;
- parameterized widths/ranges;
- one module per structural implementation.

Explicit specialization is a separately named pass/profile action with:

- selected parameter/value map;
- distinct artifact/module identity;
- provenance and source contract;
- proof/equivalence for each specialization;
- no claim that the result is the generic module.

Silent clone-per-value optimization is rejected.

## Proof and validation classes

### `Structural`

For deterministic formatting, declaration sorting, metadata-only changes, verified dead declarations, and canonical forms with mechanically checked invariants.

### `DigitalEquivalence`

For combinational/sequential logic transforms using one or more of:

- Yosys equivalence;
- parameter-elaboration matrices;
- latency-aware transaction equivalence;
- protocol-aware elastic equivalence;
- SBY properties;
- Verilator/Icarus differential regression.

### `AnalogInvariant`

For transformations proven through typed target-IR rules such as dimensional compatibility, branch/access preservation, contribution-set identity, constant-expression normalization, and approved algebraic identities under declared domains.

### `AnalogDifferential`

For transformations additionally validated through DC, AC, transient, noise, or event comparisons within declared tolerance/envelope and tool/profile versions.

Differential simulation cannot replace missing structural semantic justification for arbitrary continuous-time rewrites.

### `BackendCapability`

For simulator portability, synthesis intent, FPGA mapping, extensions, or tool-specific attributes. Requires capability checks and retained tool evidence.

A pass may require multiple classes.

## Digital Verilog optimization scope

Initial safe/reviewable candidate passes include:

- constant propagation and dead logic under symbolic-parameter rules;
- mux/logic canonicalization;
- common subexpression elimination where width/effect/protocol safe;
- process normalization;
- memory inference normalization;
- parameter/generate normalization;
- hierarchy cleanup without interface change;
- pipeline-owned register cleanup or bounded retiming under latency contracts;
- portability cleanup for the Verilog-2005 profile;
- synthesis attributes and target mapping through explicit profiles;
- external Yosys pass pipelines wrapped as locked process adapters.

Initial exclusions without a stronger explicit contract include:

- changing published latency/capacity;
- retiming user-owned state;
- crossing clock/reset/CDC/RDC boundaries;
- lossy width/signedness changes;
- silent resource sharing;
- parameter specialization;
- black-box replacement;
- unverified post-synthesis netlist substitution.

## Verilog-A and Verilog-AMS optimization scope

Initial safe/reviewable candidate passes include:

- constant folding with physical dimensions;
- parameter-expression normalization;
- dead local/declaration removal;
- branch/access canonicalization;
- contribution grouping only where equation identity is preserved;
- safe algebraic identities with explicit domains and singularity checks;
- event-condition simplification preserving crossing direction/tolerance;
- connect-rule and discipline canonicalization;
- simulator-portability lowering selected by explicit profile;
- deterministic declaration/order/format normalization.

Initial exclusions unless separately specified and validated include:

- contribution deletion or arbitrary reordering;
- equation reassociation across discontinuities/singularities;
- moving `ddt`, `idt`, Laplace/Z, delay, or waveform operators;
- changing event timing, direction, tolerance, or initialization;
- merging/renaming noise sources where analysis identity changes;
- changing simulator/environment queries;
- approximating unsupported analog behavior;
- changing analog/digital scheduling or connect-rule insertion;
- raw textual macro substitution that bypasses target verification.

## Diagnostics

Increment 83 freezes stable categories for:

- invalid pass descriptor;
- unsupported target family/profile/IR version;
- unknown extension point;
- pass-order cycle or ambiguity;
- incompatible optimization profile;
- undeclared dialect/node mutation;
- invalid analysis preservation/invalidation;
- symbolic-parameter loss or illegal specialization;
- hierarchy/interface/source-map corruption;
- clock/reset/CDC/RDC/protocol/latency violation;
- dimension/contribution/event/noise/connect-rule violation;
- capability-profile escape;
- missing/failed proof adapter;
- equivalence or differential-validation failure;
- nondeterministic output;
- raw semantic text modification without successful reparse;
- native ABI/toolchain mismatch;
- process crash, timeout, or malformed output.

Each diagnostic records pass ID/version, extension point, source location, invariant/proof obligation, artifact hashes, and reproduction command.

## Evidence and caching

The build manifest and cache key include:

- backend/profile;
- optimization profile;
- ordered pass graph and pipeline hash;
- plugin/artifact hashes and trust class;
- normalized pass options;
- target IR versions;
- required/preserved/invalidated analyses;
- before/after IR/text/artifact hashes;
- statistics and source-map changes;
- verifier/proof/differential results;
- tool versions, commands, seeds, and tolerance envelopes;
- determinism/cacheability declaration.

A shared cache does not reuse semantic outputs from an untrusted nondeterministic pass.

## CLI and inspection

Planned commands extend the plugin CLI with concepts such as:

```text
nodal passes list
nodal passes resolve
nodal passes graph
nodal passes explain <id>
nodal passes pipeline
nodal passes verify-lock
nodal passes diff <build-a> <build-b>
nodal passes reproduce <evidence>
```

Exact command placement may be under `nodal plugins` if one coherent command tree is clearer. Machine-readable output is required.

## Positive fixtures

Increment 83 contract fixtures include:

- manifest-only pass discovery without code execution;
- target-neutral, digital, Verilog-A, Verilog-AMS, render-only, and reparse pass descriptors;
- stable pass ID independent of implementation class;
- profile and extension-point compatibility;
- deterministic dependency/before/after ordering;
- parameter-preserving digital pass;
- parameter-preserving analog pass;
- source-map-preserving transform;
- analysis invalidation/recomputation;
- digital equivalence obligation;
- analog invariant and differential obligation;
- locked out-of-process Yosys transform;
- external repository pass using public SPI only;
- offline locked resolution and cache-key evidence.

## Negative fixtures

- arbitrary raw-text semantic filter;
- pass automatically enabled by installation;
- pass silently joining a profile;
- unknown profile or extension point;
- ordering cycle or ambiguous replacement;
- undeclared target mutation;
- false preservation claim;
- dropped symbolic parameter or clone-per-value output;
- changed port/latency/domain/protocol without explicit transformation contract;
- analog contribution/event/noise/connect-rule change without approved rule;
- missing proof adapter;
- equivalence/differential failure;
- broken source map;
- nondeterministic output;
- untrusted native pass;
- ABI/toolchain mismatch;
- process crash/timeout/malformed target IR.

## Increment delivery plan

### Increment 83 — Target-HDL optimization pass gate and contracts

Freeze:

- target pass manifest/descriptor;
- HDL families and target IR versions;
- extension points;
- optimization profiles;
- ordering and conflicts;
- preservation/invalidation model;
- proof/validation classes;
- parameterization/source-map/provenance rules;
- positive/negative fixtures and diagnostics.

Publish `NodalTargetHdlOptimizationPass-DG-v0.1.md` and machine-readable frozen surface. No pass execution is implemented.

### Increment 84 — Structured target IR and deterministic pass manager

Implement:

- digital and analog/AMS target representations;
- target verifiers and printers;
- native/process pass loading through the general plugin plan;
- deterministic pipeline resolution and execution;
- analysis invalidation/recomputation;
- render-only and reparse boundaries;
- pass manifests, reports, source-map updates, cache/provenance integration;
- crash-safe transactional acceptance.

### Increment 85 — Digital Verilog optimization plugins and proof matrix

Implement built-in/reference plugins for:

- digital canonicalization;
- logic/process/memory/generate normalization;
- portability cleanup;
- bounded pipeline-owned retiming;
- explicit Yosys optimization pipelines.

Require Verilator/Icarus regressions, Yosys equivalence, parameter matrices, latency/protocol-aware checks, and selected SBY proofs.

### Increment 86 — Verilog-A/Verilog-AMS optimization plugins and semantic validation

Implement built-in/reference plugins for:

- dimension-safe constant and parameter folding;
- declaration/branch/access/contribution canonicalization;
- approved algebraic/event/connect-rule portability rewrites;
- deterministic rendering normalization.

Require target-IR invariants, DC/AC/transient/noise/event differential suites where relevant, cross-tool portability checks, and explicit rejection for transformations without sound validation.

## Exit criteria

The pass architecture is complete only when:

- a third-party pass can be installed without source changes to Nodal;
- installation alone changes no output;
- manifest resolution executes no pass code;
- pass ordering is deterministic and locked;
- symbolic parameters and one-module-per-structure are preserved by default;
- digital passes demonstrate equivalence/formal evidence;
- analog/AMS passes demonstrate typed invariants and required differential evidence;
- raw semantic text modification cannot bypass reparse/reverification;
- source maps and diagnostics survive transformations;
- native/process crashes cannot leave partially accepted state;
- cache/provenance records fully identify the pass pipeline;
- external fixtures use only frozen public SPI;
- Core CI and conformance suites are green.

## References

- Nodal plugin SPI: [`plugin-spi-v0.1-plan.md`](plugin-spi-v0.1-plan.md)
- MLIR pass plugin API: <https://github.com/llvm/llvm-project/blob/main/mlir/include/mlir/Tools/Plugins/PassPlugin.h>
- MLIR dialect plugin API: <https://github.com/llvm/llvm-project/blob/main/mlir/include/mlir/Tools/Plugins/DialectPlugin.h>
- CIRCT dialects: <https://circt.llvm.org/docs/Dialects/>
- Yosys documentation: <https://yosyshq.readthedocs.io/projects/yosys/en/stable/>
- SBY documentation: <https://yosyshq.readthedocs.io/projects/sby/en/stable/>
- Verilator documentation: <https://verilator.org/guide/latest/>
- Verilog-AMS standard information: <https://accellera.org/downloads/standards/v-ams>
