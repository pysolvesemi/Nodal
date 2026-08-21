# Verilog-family Optimization Pass Plan

**Status:** Normative roadmap target  
**Architecture:** [ADR 0013](../architecture/0013-versioned-verilog-family-optimization-passes.md)  
**Common plugin SPI:** [ADR 0012](../architecture/0012-versioned-capability-plugin-architecture.md) and [`plugin-spi-v0.1-plan.md`](plugin-spi-v0.1-plan.md)  
**Formal optimization-pass gate:** Increment 85  
**Machine-readable candidate:** [`verilog-family-optimization-pass-surface.json`](verilog-family-optimization-pass-surface.json)

## Goal

Allow users to add, order, configure, lock, verify, and reproduce optimization passes for:

- target-neutral Nodal IR;
- digital Nodal/CIRCT hardware IR;
- portable Verilog;
- Verilog-A;
- Verilog-AMS;
- external digital synthesis/optimization tools such as Yosys;
- future explicitly gated Verilog-family profiles.

The binding rule is:

> **Optimize the highest valid typed representation, declare every semantic effect, verify every mutation, and commit only a validated candidate.**

The plan does not permit arbitrary semantic text filters after HDL emission.

## Existing plugin-plan coverage

The merged plugin roadmap already provides:

- manifest-first plugin discovery;
- stable plugin and capability IDs;
- native MLIR pass and dialect plugins;
- named compiler extension points;
- out-of-process transform plugins;
- backend plugins;
- external tool adapters;
- lockfiles, trust, compatibility, provenance, and caching.

The missing contract is target-family optimization safety. This plan adds:

- versioned target HDL representations;
- Verilog/Verilog-A/Verilog-AMS extension points;
- pass kinds and semantic-effect classes;
- mandatory invariants;
- transactional acceptance/rollback;
- family-specific proof policies;
- parameterization, latency, source-map, and capability preservation;
- Yosys plugin/script integration without making Yosys define Nodal semantics.

## Candidate compiler configuration

Optimization is project/compiler configuration, not model-source behavior.

Directional Scala candidate:

```scala
val pipeline = OptimizationPipeline(
  profile = OptimizationProfile.Custom,
  passes = Seq(
    PassRef("org.nodal.opt.verilog.constant-fold"),
    PassRef(
      id = "com.acme.opt.verilog.clock-gate",
      version = "1.2.0",
      options = Map(
        "minimum-width" -> 4,
        "test-enable" -> "scan_enable"
      )
    )
  ),
  verification = VerificationPolicy.Required
)

val result = Nodal.emit(
  new DigitalTop,
  EmitOptions(
    backend = Backend.Verilog,
    optimization = pipeline
  )
)
```

Directional project configuration:

```toml
[optimization]
profile = "custom"
verification = "required"

[[optimization.passes]]
id = "org.nodal.opt.verilog.constant-fold"
version = "1.0.0"

[[optimization.passes]]
id = "com.acme.opt.verilog.clock-gate"
version = "1.2.0"

[optimization.passes.options]
minimum-width = 4
test-enable = "scan_enable"
```

Exact public names and schemas are frozen in Increment 85.

## Candidate public configuration types

The candidate surface includes:

```scala
OptimizationPipeline
OptimizationProfile
PassRef
PassId
PassVersion
PassStage
PassKind
SemanticEffect
VerificationPolicy
ProofPolicy
HdlFamily
TargetProfile
OptimizationResult
PassEvidence
```

These are compiler configuration and evidence types. They are not ordinary hardware-expression types.

## Target families

Initial target-family identifiers:

```scala
HdlFamily.Nodal
HdlFamily.Digital
HdlFamily.Verilog
HdlFamily.VerilogA
HdlFamily.VerilogAMS
```

A future SystemVerilog or SystemVerilog-AMS family requires its own capability gate and target representation version.

## Pass representations

### Target-neutral Nodal IR

Use for transformations whose semantics do not depend on target HDL spelling.

Examples:

- constant folding;
- dead declaration removal;
- safe common-subexpression elimination;
- hierarchy cleanup;
- quantity/effect/domain-aware canonicalization;
- automatic-pipeline scheduling;
- sampled-model recurrence simplification.

### Digital Nodal/CIRCT IR

Use for digital structural optimizations before portable Verilog legalization.

Examples:

- combinational and sequential canonicalization;
- memory lowering choices;
- mux/arithmetic restructuring;
- resource mapping;
- approved enable/gating transformations;
- preparation for Yosys.

### Typed target HDL IR

Versioned compiler-owned families:

```text
nodal-hdl-verilog/1
nodal-hdl-veriloga/1
nodal-hdl-verilogams/1
```

They retain target constructs, profiles, stable node IDs, source origins, symbolic parameters, generate structures, analog contributions, events, disciplines, connect constructs, and deterministic printing.

### Emitted text

Only non-semantic formatting, comments, banners, line wrapping, source-map finalization, and packaging are allowed directly over text.

A semantic text-to-text external optimizer must:

1. run out of process;
2. return a declared artifact;
3. be reparsed into a supported typed representation;
4. pass all core verifiers;
5. satisfy the required proof/differential policy;
6. preserve source-map/provenance evidence;
7. be accepted transactionally.

## Candidate extension points

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

Every extension point freezes:

- input/output representation and version;
- entry/exit invariants;
- allowed pass kinds and effects;
- allowed construct changes;
- required analyses;
- mandatory core verifiers;
- proof policy;
- source-map behavior;
- parameter, hierarchy, latency, protocol, and capability rules.

## Pass descriptor candidate

Directional manifest fragment:

```toml
[[passes]]
id = "com.acme.opt.verilog.clock-gate"
version = "1.2.0"
api = "nodal-hdl-pass-1"
stage = "verilog.pre-emit"
input = "nodal-hdl-verilog/1"
output = "nodal-hdl-verilog/1"
kind = "optimization"
effect = "behavior-equivalent"
deterministic = true
cacheable = true
isolation = "process"

hdl_families = ["verilog"]
profiles = ["digital-verilog-synth"]

preserves = [
  "symbolic-parameters",
  "module-identity",
  "clock-reset-semantics",
  "cdc-rdc",
  "protocol-ordering",
  "published-latency",
  "source-origins"
]

invalidates = ["combinational-timing", "power-estimate"]
requires = ["clock-enable-analysis"]
verification = "digital-equivalence-required"
```

## Pass kinds

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

Mutation permissions:

| Kind | Semantic mutation | Normal acceptance rule |
| --- | --- | --- |
| analysis | No | deterministic result and declared analyses |
| lint | No | stable diagnostics |
| canonicalization | Equivalent | verifier plus canonical proof policy |
| optimization | Equivalent | family-specific proof/evidence required |
| legalization | Representation change | capability and semantic verification |
| lowering | Representation change | source-contract preservation |
| instrumentation | Declared added behavior | profile separation and explicit selection |
| formatting | No semantic token change | reparse/fingerprint check |
| report | No | read-only evidence |

## Semantic-effect classes

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

A pass cannot use an undefined `unsafe` class.

`specializing` requires explicit opt-in and a specialization manifest. It cannot run in the default native parameterized-HDL flow.

## Mandatory preservation contract

Default semantic passes preserve:

- symbolic parameters and legal ranges;
- named parameter overrides;
- one module per structural shape;
- finite-width arithmetic and signedness;
- explicit narrowing/overflow/rounding policy;
- clock/reset domain ownership and reset behavior;
- CDC/RDC structures and provenance;
- `Valid`/`Stream` ordering, capacity, and backpressure;
- fixed/bounded latency and transaction identity;
- automatic-pipeline sideband alignment;
- physical dimensions and discipline compatibility;
- analog state, contributions, events, and initialization;
- mixed-signal sample/drive boundaries;
- memory and external-operation contracts;
- target profile legality;
- stable names and source origins.

A pass that needs to change one of these must use an explicitly approved effect/profile with corresponding interface and verification rules.

## Transactional pass execution

A semantic pass is never allowed to mutate the accepted compiler state in place without rollback.

Required sequence:

```text
accepted input
    ↓ hash/snapshot
isolated candidate transformation
    ↓
parse + structural verify
    ↓
core semantic verify
    ↓
family-specific equivalence/differential/formal checks
    ↓
source-map + artifact validation
    ↓
accept candidate or discard candidate
```

Failure classes include:

- plugin crash;
- timeout or cancellation;
- malformed output;
- version mismatch;
- undeclared dialect/construct;
- verifier failure;
- proof failure;
- source-map corruption;
- nondeterministic replay;
- undeclared analysis invalidation;
- parameter specialization without permission;
- capability-profile violation.

The last accepted compiler state remains usable for diagnostics after failure.

## Digital Verilog verification

The required ladder is selected from:

- Nodal semantic verification;
- MLIR/CIRCT verification;
- normalized IR fingerprint checks;
- CIRCT LEC where applicable;
- Yosys RTL/netlist equivalence;
- Verilator and Icarus differential simulation;
- SBY safety, cover, induction, and selected liveness;
- parameter-envelope matrices;
- latency-aware pipeline equivalence;
- protocol-aware elastic equivalence;
- CDC/RDC and reset-structure checks.

### Yosys integration

Yosys supports synthesis scripts composed from passes and can load commands supplied by plugins. Nodal uses this as an **external optimization engine**, not as the definition of Nodal semantics.

A Yosys-backed pass bundle records:

- exact Yosys build ID;
- loaded Yosys plugin binaries and hashes;
- Yosys script and command order;
- options and environment;
- input/output hashes;
- deterministic seed where relevant;
- synthesis/equivalence logs;
- resulting netlist or re-imported Verilog;
- pass-local reports and proof artifacts.

The output is accepted only after Nodal reparses and verifies it and the configured equivalence policy passes.

A Yosys result may be emitted as an implementation/netlist artifact without replacing the canonical Nodal RTL when the chosen flow does not preserve source-level structure.

## Verilog-A verification

Passes must preserve:

- dimensions and units;
- natures/disciplines;
- node/branch identity;
- contribution semantics;
- analog state and initial conditions;
- event and tolerance semantics;
- analysis/profile legality;
- symbolic parameters and hierarchy.

Evidence may include:

- typed target-IR verification;
- narrowly scoped symbolic/algebraic equivalence;
- normalized equation comparison;
- OpenVAF compilation;
- ngspice differential simulation;
- optional second simulator comparison;
- declared parameter/stimulus/analysis envelope;
- absolute/relative/state/event/frequency-domain tolerances.

No finite test envelope is reported as universal analog equivalence.

## Verilog-AMS verification

Evidence combines:

- Verilog-A checks;
- digital equivalence/formal checks;
- domain and CDC/RDC verification;
- connect-rule and resolution checks;
- mixed-signal sampling/drive verification;
- pipeline/protocol/latency checks;
- simulator differential regression;
- portability-profile checks.

## Source mapping

Every transformation emits a deterministic origin relation:

```text
Nodal source
    → input IR node
    → pass ID/version + output IR node(s)
    → target HDL node
    → emitted file/range
    → external tool diagnostics/evidence
```

The relation supports one-to-one, one-to-many, many-to-one, and removed-node cases.

Pass diagnostics include:

- pass/plugin ID and version;
- extension point;
- input/output IR family/version;
- input/output hash;
- source location/origin set;
- violated invariant or proof obligation;
- external command/tool evidence where applicable.

## Determinism, lockfiles, and caching

The resolved pass pipeline is included in `nodal.plugins.lock` or a linked optimization lock section.

The pass-plan hash includes:

- pass IDs/versions/artifacts;
- total order and extension points;
- normalized options;
- IR versions;
- target family/profile;
- toolchain IDs;
- trust/isolation mode;
- proof policy;
- scripts, seeds, and external tools;
- source-map version.

Release builds require deterministic replay. Exploration-only nondeterminism must be explicit and cannot produce release evidence without deterministic replay.

## Built-in profiles

Initial candidates:

```text
none
debug
portable
area
performance
power
simulation
formal
custom
```

A profile is a versioned resolved pass set, not a vague optimization level. Its pass graph, versions, options, and proof policy are inspectable.

`none` still runs mandatory legality, verifier, and deterministic-emission passes.

## CLI candidate

```text
nodal passes list
nodal passes inspect <id>
nodal passes graph
nodal passes resolve
nodal passes explain <id-or-stage>
nodal passes lock
nodal passes run
nodal passes verify
nodal passes replay
nodal passes diff
```

Machine-readable output includes the resolved graph, extension points, invariants, proof obligations, input/output hashes, source-map changes, and evidence locations.

## Positive fixture matrix

- target-neutral analysis pass;
- target-neutral canonicalization pass;
- digital CIRCT optimization pass;
- Verilog legalization pass;
- Verilog structural optimization preserving parameters;
- Yosys built-in-pass script adapter;
- Yosys dynamically loaded plugin pass;
- Verilog-A algebraic pass;
- Verilog-AMS declaration/connect-rule canonicalization;
- simulation instrumentation pass;
- formatting-only pass;
- process-isolated pass;
- trusted-native pass with exact toolchain match;
- multiple independent passes with canonical ordering;
- explicitly ordered dependent passes;
- parameter-envelope fixture;
- fixed/elastic pipeline fixture;
- external repository pass using public SPI only;
- locked offline replay;
- cache hit with identical pass-plan hash.

## Negative fixture matrix

- semantic post-emission text filter;
- undeclared pass kind/effect;
- wrong IR family/version;
- invalid extension point;
- ambiguous pass order;
- dependency/phase cycle;
- pass introducing unsupported dialect/construct;
- pass disabling a core verifier;
- false analysis-preservation claim;
- parameter specialization without explicit policy;
- module clone per parameter value;
- width/signedness/overflow change;
- latency/protocol/backpressure change;
- clock/reset/CDC/RDC change;
- analog unit or contribution change;
- mixed-signal boundary change;
- source-map loss;
- nondeterministic output;
- untrusted native pass;
- native toolchain mismatch;
- process crash/timeout/malformed output;
- Yosys output failing reparse or equivalence;
- analog differential regression outside tolerance;
- formatting pass changing semantic tokens.

## Incremental delivery

### Increment 85 — Verilog-family optimization pass architecture gate and contracts

- [ ] Use ADR 0013 and the machine-readable candidate as the mandatory architecture.
- [ ] Compile candidate pass IDs/descriptors, stages, kinds, semantic effects, target families, pipeline configuration, profiles, proof policies, and evidence APIs.
- [ ] Freeze versioned target HDL representation names and extension-point contracts.
- [ ] Freeze mandatory preservation, transactional acceptance, rollback, source-map, determinism, trust, lockfile, and specialization rules.
- [ ] Publish `NodalVerilogFamilyOptimizationPass-DG-v0.1.md`, schemas, stable diagnostics, and positive/negative fixtures.
- [ ] Keep pass execution inert until the gate is approved.

### Increment 86 — Versioned target HDL IR and transactional pass manager

- [ ] Implement deterministic parse/print/verify for `nodal-hdl-verilog/1`, `nodal-hdl-veriloga/1`, and `nodal-hdl-verilogams/1` or approved equivalent representations.
- [ ] Implement stable node IDs, origin chains, capability annotations, symbolic parameters/generate, and analog/mixed-signal constructs.
- [ ] Implement pass descriptors, extension-point validation, total-order resolution, analysis preservation/invalidation, immutable candidate execution, rollback, pass tracing, and cache/provenance hashes.
- [ ] Make core built-in passes use the same evidence model where practical.

### Increment 87 — Digital Verilog optimization plugins and Yosys interoperability

- [ ] Implement target-neutral/digital/Verilog pass loading at approved points.
- [ ] Integrate pinned Yosys scripts and dynamically loaded Yosys plugins through the external adapter protocol.
- [ ] Implement re-import, portable-profile verification, parameter/generate preservation, and canonical-versus-implementation artifact handling.
- [ ] Add Yosys/CIRCT/Verilator/Icarus/SBY equivalence and differential policies, including pipeline/protocol/latency and parameter-envelope checks.
- [ ] Publish reference digital passes and invalid-pass conformance fixtures.

### Increment 88 — Verilog-A/Verilog-AMS optimization plugins and bounded validation

- [ ] Implement target Verilog-A/AMS pass loading, typed equation/contribution/event/connect-rule verification, and target-profile legality.
- [ ] Add narrowly scoped symbolic equivalence where sound, normalized equation evidence, OpenVAF compilation, ngspice/optional second-tool differential regression, and mixed-signal partition checks.
- [ ] Preserve units, disciplines, state, events, symbolic parameters, domains, protocols, source maps, and declared validation envelopes.
- [ ] Publish reference Verilog-A/AMS passes, instrumentation/formatting examples, and negative proof/portability fixtures.

## Freeze exit criteria

Increment 85 may be checked only when:

1. the optimization-pass design gate is approved;
2. pass identity is independent of implementation class/library symbol;
3. input/output IR family and version are explicit;
4. target extension points and invariants are versioned;
5. pass kinds and semantic effects are closed/frozen for v0.1;
6. arbitrary semantic text filters are prohibited;
7. native symbolic parameterization and no-clone defaults are tested;
8. latency/protocol/domain/quantity/effect preservation is explicit;
9. transactional rollback behavior is frozen;
10. family-specific proof policies are documented;
11. source maps, diagnostics, lockfiles, provenance, and cache keys include pass evidence;
12. external Yosys and generic process-transform boundaries are explicit;
13. positive/negative external-consumer fixtures use no core internals; and
14. Core CI passes.

## References

- Nodal plugin SPI plan: [`plugin-spi-v0.1-plan.md`](plugin-spi-v0.1-plan.md)
- Nodal digital backend plan: [`digital-verilog-open-source-verification-plan.md`](digital-verilog-open-source-verification-plan.md)
- MLIR pass plugin API: <https://github.com/llvm/llvm-project/blob/main/mlir/include/mlir/Tools/Plugins/PassPlugin.h>
- MLIR dialect plugin API: <https://github.com/llvm/llvm-project/blob/main/mlir/include/mlir/Tools/Plugins/DialectPlugin.h>
- CIRCT passes: <https://circt.llvm.org/docs/Passes/>
- Yosys repository and pass framework: <https://github.com/YosysHQ/yosys>
- Yosys plugins: <https://github.com/YosysHQ/yosys-plugins>
