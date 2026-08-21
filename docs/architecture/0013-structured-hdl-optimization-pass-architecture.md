# ADR 0013: Use structured, proof-carrying target-HDL optimization passes

- **Status:** Accepted
- **Date:** 2026-08-21
- **Scope:** Verilog, Verilog-A, Verilog-AMS, target lowering, optimization plugins, equivalence, semantic validation, source maps, and reproducibility

## Context

Nodal already plans a versioned plugin system with native MLIR passes, out-of-process transforms, backend plugins, tool adapters, deterministic extension points, lockfiles, and retained provenance. That generic architecture is necessary, but it is not sufficient to define a safe plug-and-play optimization ecosystem for the emitted HDL families.

The three initial HDL profiles have materially different semantics:

- portable digital Verilog describes discrete hardware and can use synthesis/equivalence/formal tools;
- Verilog-A describes continuous-time equations, contributions, events, noise, and simulator analyses;
- Verilog-AMS combines analog semantics with event-driven digital and connect-rule behavior.

A raw textual rewrite can easily break symbolic parameters, hierarchy, widths, source mapping, contribution semantics, event timing, initialization, noise identity, clock/reset ownership, CDC/RDC structure, automatic-pipeline latency, or simulator portability. Therefore target-HDL optimization must operate on typed structured representations and carry explicit proof/validation obligations.

## Decision

Nodal supports plug-and-play target-HDL optimization passes through a separately versioned pass SPI layered on the general plugin SPI.

The binding rule is:

> **Optimize structured target IR, declare semantic effects, reverify every boundary, and retain proof evidence.**

## Pass layers

Nodal distinguishes four pass layers.

### Target-neutral Nodal IR passes

These operate before backend selection/lowering and may optimize only semantics represented in target-neutral Nodal MLIR.

Examples:

- safe constant folding;
- dead declaration removal;
- hierarchy cleanup;
- domain/effect-aware canonicalization;
- automatic-pipeline scheduling and bounded retiming;
- parameter-expression normalization.

These passes cannot introduce backend-specific spelling or simulator extensions.

### Digital target IR passes

Portable Verilog lowering uses structured digital IR, preferentially CIRCT `hw`, `comb`, `seq`, `sv`, and Nodal-owned companion operations where their semantics match the approved digital profile.

Digital target passes may optimize:

- combinational logic;
- sequential processes;
- memories within declared contracts;
- parameterized generate structure;
- protocol control;
- pipeline-owned state;
- attributes and synthesis intent;
- deterministic flattening and naming.

They must preserve clock/reset, CDC/RDC, latency, protocol, parameterization, and observable behavior unless the pass contract explicitly changes a published property and the user selects that transformation.

### Analog/AMS target IR passes

Nodal owns a typed structured emission representation for Verilog-A and Verilog-AMS constructs not modeled by CIRCT. It preserves:

- natures and disciplines;
- nodes, branches, and access functions;
- analog regions and contribution sets;
- physical dimensions;
- continuous-time operators;
- events and tolerances;
- noise source identity;
- analyses and environmental queries;
- connect modules/rules;
- mixed-signal conversions;
- source locations and backend capability metadata.

Analog/AMS passes may perform only transformations allowed by the pass contract and verifier. Contribution reordering, equation reassociation, event movement, discontinuity changes, initial-condition changes, noise merging, and simulator-query changes are prohibited unless a specific semantics-preserving rule and validation obligation are defined.

### Render-only passes

After structured target IR is verified, render-only passes may change formatting, comments, whitespace, deterministic declaration grouping, or metadata serialization without changing parsed semantics.

A plugin that edits arbitrary emitted text is not an optimization pass. A semantic post-render transformation must reparse into the approved structured target representation, recover required metadata/source maps, pass capability verification, and satisfy the same proof obligations as a pre-render pass.

## Target-HDL pass descriptor

Every pass has a stable descriptor containing at least:

- globally qualified pass ID and semantic version;
- plugin ID/version and artifact hash;
- accepted HDL family/profile and structured IR version;
- named extension point;
- required capabilities and invariants;
- pass options schema and normalized options;
- deterministic/cacheable declaration;
- declared input/output dialects or target nodes;
- analyses required, preserved, invalidated, or produced;
- hierarchy, parameterization, naming, source-map, and artifact effects;
- clock/reset, CDC/RDC, protocol, latency, numeric, dimension, event, contribution, and effect preservation declarations;
- proof/validation obligation;
- expected diagnostics and reports.

The compiler rejects undeclared mutation or invalid preservation claims.

## Named target extension points

The exact names are frozen by the pass SPI gate, but the architecture requires distinct points equivalent to:

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

Each point publishes its accepted IR version, invariants, legal mutations, mandatory verifier pipeline, and cache/provenance rules.

## Pass pipeline resolution

The project explicitly selects an optimization profile or pass pipeline. Installing a plugin does not execute its passes.

A candidate configuration is conceptually:

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

Resolution uses stable IDs, dependency edges, before/after constraints, conflicts, profile compatibility, and deterministic tie-breaking. Discovery order and shared-library load order never define pass order.

The lockfile records the exact pass graph, options, artifacts, extension points, toolchain compatibility, proof adapters, and pipeline hash.

## Preservation and invalidation

Passes declare preserved and invalidated properties at semantic granularity, including:

- type and width correctness;
- symbolic parameter identity and legal envelope;
- one-module-per-structure policy;
- hierarchy and port contract;
- clock/reset domains;
- CDC/RDC structures and waivers;
- fixed/valid/elastic protocol semantics;
- latency, throughput, capacity, and ordering;
- arithmetic overflow/rounding/signedness;
- physical dimensions;
- analog contribution and branch semantics;
- event time/tolerance behavior;
- noise identity and analysis behavior;
- mixed-signal conversion provenance;
- source-map fidelity;
- backend capability profile.

The host recomputes invalidated analyses and runs mandatory core verification after each pass or verified pass group.

## Proof and validation classes

A pass selects one or more required evidence classes.

### Structural proof

Used for formatting, naming, dead declarations, canonical grouping, and transformations whose equivalence follows from verified structural invariants.

### Digital equivalence/formal proof

Digital passes may require:

- combinational or sequential equivalence through Yosys;
- latency-aware transaction equivalence;
- protocol-aware elastic equivalence;
- SBY safety/cover/liveness checks;
- Verilator/Icarus differential simulation;
- parameter-envelope elaboration matrices.

### Analog/AMS semantic validation

Analog/AMS passes may require:

- typed equation/contribution invariant checks;
- dimensional and branch-access equivalence;
- event/tolerance/initial-condition preservation checks;
- normalized target-IR equivalence for approved rewrite classes;
- differential DC/AC/transient/noise simulation within declared tolerances;
- cross-simulator portability checks where available;
- explicit rejection where no sound validation method exists.

A sampled waveform comparison alone does not authorize arbitrary continuous-time rewrites.

### Backend/tool validation

A pass affecting portability, synthesis intent, simulator extensions, or FPGA mapping may require capability-profile checks and external tool evidence.

## Parameterization and specialization

Target-HDL optimization must preserve symbolic parameters, target `generate`, named overrides, and one module per structural implementation by default.

A pass may specialize a parameter only when:

- specialization is explicit in the selected pipeline;
- the specialized parameter/value and source contract are recorded;
- the output receives a distinct artifact identity;
- the transformation does not masquerade as the generic parameterized module;
- verification/equivalence is run for the specialized case.

Silent clone-per-value optimization is prohibited.

## Source maps, diagnostics, and provenance

Every pass receives stable source-map references and returns a mapping from output entities to input entities or marks deliberately synthesized entities with plugin/pass provenance.

Diagnostics contain:

- stable plugin-local code;
- pass ID/version;
- extension point;
- Nodal and target-HDL source location where possible;
- violated invariant or failed proof obligation;
- before/after artifact hashes;
- reproduction command.

The build manifest includes:

- ordered pass pipeline;
- plugin and artifact hashes;
- normalized options;
- target IR/profile versions;
- analysis invalidation/recomputation;
- before/after hashes and statistics;
- proof/validation evidence;
- external tool versions and commands;
- source-map transformations;
- deterministic pipeline hash.

## Native and process isolation

Trusted in-process passes use the native compiler plugin loader and exact Nodal/LLVM/MLIR/CIRCT build compatibility.

Out-of-process passes exchange normalized versioned target IR plus source maps and manifests. A crash, timeout, malformed response, or failed proof cannot commit partial compiler state.

Raw third-party tools such as Yosys are integrated as process adapters or wrapped pass plugins. Their scripts, versions, options, and outputs are locked and retained.

## Optimization profiles

Nodal may publish built-in profiles such as:

```text
none
canonical
portable
simulation
synthesis
formal
fpga
custom
```

Profiles are explicit, versioned pass pipelines. `Backend.Auto` chooses a backend, not an optimization profile. A backend plugin or installed pass cannot silently change the selected optimization pipeline.

## Safety boundaries

Plugins cannot:

- override core language semantics;
- disable mandatory target verifiers;
- erase clock/reset or crossing provenance;
- silently narrow values or change signedness/overflow;
- reorder visible transactions;
- change published pipeline latency or capacity without an explicit transformation contract;
- drop analog contributions or connect rules;
- change event timing, tolerance, initialization, noise, or analyses without an approved rule;
- replace unsupported constructs with approximations silently;
- specialize symbolic parameters implicitly;
- bypass capability-profile checks;
- modify emitted semantic text without reparsing and re-verification.

## Consequences

### Positive

- Third parties can add optimization passes without forking Nodal.
- Digital, analog, and mixed-signal semantics receive appropriate proof obligations rather than one unsafe generic equivalence claim.
- Target-specific passes remain deterministic, versioned, cacheable, and reproducible.
- Symbolic parameterization, source maps, domains, protocols, quantities, and AMS semantics remain visible through optimization.
- Yosys and other external tools can participate without becoming hidden language semantics.
- Pass pipelines can be inspected, locked, reproduced, and compared across releases.

### Costs

- Nodal must define and version structured target representations before text emission.
- Analog/AMS validation is more restrictive and expensive than digital equivalence.
- Native plugin compatibility follows the pinned LLVM/MLIR/CIRCT toolchain.
- Pass authors must provide preservation declarations and proof evidence.
- Reparse-based semantic post-processing requires robust target parsers or must remain unsupported.

## Rejected alternatives

- **Arbitrary raw-text filters:** cannot reliably preserve semantics, parameters, source maps, or capability profiles.
- **One universal optimization pass interface:** digital equivalence and continuous-time AMS validation have different obligations.
- **Pass order from plugin discovery:** non-deterministic and not reproducible.
- **Trust pass-provided preservation claims without verification:** permits silent corruption.
- **Run all installed passes automatically:** installation must not change generated hardware.
- **Use synthesis output as the only proof:** insufficient for analog/AMS behavior and may hide changed interfaces or latency.
- **Allow plugins to disable core safety checks:** incompatible with Nodal's high-level safety goals.

## Follow-up increments

- Increment 79 freezes the general plugin manifest, resolver, lifecycle, trust, and capability SPI.
- Increment 82 implements the native/process compiler plugin loader and generic extension-point machinery.
- A dedicated target-HDL pass gate freezes the target pass descriptors, structured representations, extension points, preservation model, profiles, and proof classes.
- Subsequent increments implement the target pass manager, digital Verilog pass/equivalence flow, and Verilog-A/Verilog-AMS semantic pass/validation flow.
- Backend, packaging, caching, documentation, conformance, release, and AMS-to-FPGA increments consume the same locked pass pipeline and provenance model.

## References reviewed

- VexiiRiscv plugin-composed design: <https://github.com/SpinalHDL/VexiiRiscv>
- MLIR pass plugin API: <https://github.com/llvm/llvm-project/blob/main/mlir/include/mlir/Tools/Plugins/PassPlugin.h>
- MLIR dialect plugin API: <https://github.com/llvm/llvm-project/blob/main/mlir/include/mlir/Tools/Plugins/DialectPlugin.h>
- CIRCT dialects: <https://circt.llvm.org/docs/Dialects/>
- Yosys documentation: <https://yosyshq.readthedocs.io/projects/yosys/en/stable/>
- SBY documentation: <https://yosyshq.readthedocs.io/projects/sby/en/stable/>
- Verilog-AMS standard information: <https://accellera.org/downloads/standards/v-ams>
