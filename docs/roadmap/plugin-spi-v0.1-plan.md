# Nodal Plugin SPI v0.1 Plan

**Status:** Normative roadmap target

**Architecture:** [ADR 0012](../architecture/0012-versioned-capability-plugin-architecture.md)

**Formal SPI gate:** Increment 79

**Machine-readable candidate:** [`plugin-spi-v0.1-surface.json`](plugin-spi-v0.1-surface.json)

## Goal

Provide a scalable plugin ecosystem without turning Nodal into a process-global mutable service locator.

The plugin system must support:

- VexiiRiscv-style configurable design composition;
- public Scala/frontend extension descriptors;
- native MLIR pass, analysis, and dialect plugins;
- optional backend plugins;
- simulator, synthesis, formal, FPGA, programmer, board, and HIL adapters;
- independent packaging, compatibility, trust, provenance, and deterministic caching.

The binding rule is:

> **Explicit plugin plan, typed capability graph, deterministic phases, isolated extension boundaries, retained provenance.**

## Prior-art comparison

### VexiiRiscv strengths retained

VexiiRiscv uses an almost-empty top level containing a database and `PluginHost`. Hardware is contributed by plugins. Its plugin framework supports:

- type-based service discovery;
- exactly-one, optional, many, and filtered lookups;
- setup, build, patch, and check phases;
- retainers/locks for late contributions;
- optional plugin sets generated from configuration.

Nodal retains the architectural intent:

- a local host can compose a complex design from optional plugins;
- services are typed;
- multiple plugins may contribute to an aggregate;
- configuration and construction occur in phases;
- provider declaration order should not normally define semantics.

### Nodal changes required for scale

Nodal adds:

- stable IDs independent of Scala class names;
- capability versions and explicit cardinality;
- manifests parsed before executable code is loaded;
- dependency/conflict resolution and lockfiles;
- canonical phase ordering rather than public retain/release locks;
- immutable/append-only contributions rather than direct plugin mutation;
- independent Scala, native, backend, and process-adapter SPIs;
- exact native toolchain ABI checks;
- process isolation where appropriate;
- checksums, trust policy, provenance, cache integration, and conformance tests.

## Plugin bundle model

A plugin bundle is one coordinated release that may contain one or more facets:

```text
plugin bundle
├── nodal-plugin.toml/json
├── Scala 3 artifact              # design/frontend facet
├── native shared library         # compiler facet
├── external executable           # backend/tool-adapter facet
├── schemas and support files
├── checksums/signatures
├── license metadata
└── SBOM/provenance
```

A bundle has one stable plugin ID and semantic version. Each facet declares an independent SPI/protocol version and platform compatibility.

Example IDs:

```text
com.acme.nodal.cpu-branch
com.acme.nodal.custom-lint
com.acme.nodal.backend-vhdl
org.nodal.tool.verilator
org.nodal.fpga.ecp5-open
```

## Manifest candidate

Directional TOML:

```toml
schema = 1
id = "com.acme.nodal.cpu-branch"
version = "1.2.0"
spi = "nodal-plugin-spi-1"
nodal = ">=0.4,<0.5"
license = "Apache-2.0"
trusted = true

[facets.scala]
entry = "com.acme.nodal.BranchPlugin"
artifact = "com.acme:nodal-branch_3:1.2.0"

[[provides]]
id = "com.acme.cpu.decode-rules"
version = 1
cardinality = "many"

[[requires]]
id = "com.acme.cpu.fetch"
version = "^1"
cardinality = "exactly-one"

[[conflicts]]
id = "com.acme.nodal.legacy-branch"

[options]
schema = "schemas/options.json"
```

Native facet additions include:

```toml
[facets.native]
abi = "nodal-native-plugin-1"
toolchain_build = "<exact Nodal/LLVM/MLIR/CIRCT build id>"
library = "libnodal_acme_branch.so"
entry = "nodalGetPluginInfo"
```

Process facet additions include:

```toml
[facets.process]
protocol = "nodal-plugin-process-1"
executable = "nodal-acme-tool"
timeout_seconds = 120
```

The exact serialization format and field names are frozen in Increment 79. The semantic requirements are mandatory.

## Plugin kinds

### `DesignPlugin`

Used inside an explicit local `DesignHost` to compose configurable hardware/model architectures.

Candidate source:

```scala
object FetchService extends CapabilityKey[FetchApi](
  id = "com.acme.cpu.fetch",
  version = 1,
  cardinality = ExactlyOne
)

object DecodeRules extends ContributionKey[DecodeRule](
  id = "com.acme.cpu.decode-rules",
  version = 1
)

final class BranchPlugin(config: BranchConfig) extends DesignPlugin:
  override val descriptor =
    plugin("com.acme.nodal.branch", version = "1.2.0")
      .requires(FetchService)
      .contributes(DecodeRules)

  override def declare(ctx: DeclareContext): Unit =
    ctx.contribute(DecodeRules, branchRules(config))

  override def elaborate(ctx: ElaborateContext): Unit =
    val fetch = ctx.require(FetchService)
    // Construct hardware using public Nodal APIs.
```

Candidate host:

```scala
val subsystem = DesignHost(
  plugins = Seq(
    FetchPlugin(...),
    DecodePlugin(...),
    BranchPlugin(...)
  )
).build()
```

The exact Scala shape is evaluated and frozen by Increment 79.

### `FrontendPlugin`

Registers approved namespaced descriptors:

- metadata/annotation schemas;
- external-operation declarations;
- typed attributes;
- public lints and diagnostics;
- public design-host capability types;
- source mapping and report helpers.

It cannot override core syntax or semantics.

### `CompilerPlugin`

Registers one or more:

- MLIR passes;
- analyses;
- dialects under a plugin-owned namespace;
- dialect interfaces;
- lint/verifier rules;
- named pass-pipeline extensions;
- optional translations.

The initial native loader wraps MLIR pass/dialect plugin APIs and validates a Nodal manifest before registration.

### `BackendPlugin`

Registers a stable backend ID and capability profile. Third-party backend selection is explicit in v0.1.

### `ToolAdapterPlugin`

Runs out of process and integrates simulators, synthesis/formal tools, FPGA flows, programmers, boards, HIL transports, and other external tools.

## Capability model

### Stable key

A capability key contains:

- stable string ID;
- capability version;
- Scala type for local checking when applicable;
- provider cardinality;
- optional qualifier type/schema;
- merge or selection policy;
- close phase after which no further provider/contribution is legal.

### Cardinality

Candidate policies:

```scala
ExactlyOne
ZeroOrOne
OneOrMore
ZeroOrMore
Qualified("name")
ContributionSet
ContributionSequence
```

`ContributionSequence` requires an explicit deterministic ordering relation. It must not inherit discovery order.

### Provider selection

Selection is resolved before plugin code executes. There is no implicit "first provider wins" or classpath preference.

If more than one provider can satisfy an exactly-one requirement, the project must provide a qualifier/selection rule or resolution fails.

### Multiple instances

A plugin kind may permit multiple instances only when each instance has a stable qualifier:

```text
com.acme.cache@instruction
com.acme.cache@data
```

Names, reports, cache keys, and capability providers include the qualifier.

## Local design host

`DesignHost` is not a process-global singleton.

A host owns:

- one immutable resolved plugin plan;
- one local capability scope;
- one deterministic contribution registry;
- phase-specific contexts;
- source and plugin provenance;
- stable instance IDs and names;
- diagnostics and evidence.

Nested hosts must explicitly import/export capabilities. They do not inherit all parent services automatically.

### No concrete plugin lookup

This is rejected as a public contract:

```scala
ctx.host[ConcreteBranchPlugin]
ctx.host.list[ConcretePlugin]
```

Consumers depend on service interfaces/capability keys, not implementation classes.

### No direct mutation

This is rejected:

```scala
otherPlugin.rules += myRule
otherPlugin.lock.release()
```

Instead:

```scala
ctx.contribute(DecodeRules, myRule)
```

The host owns aggregation, closure, validation, and ordering.

## Lifecycle candidate

### `discover`

Read manifests only. No plugin code or external process executes.

### `resolve`

Validate compatibility, capabilities, conflicts, cycles, platform artifacts, trust, and options. Produce an immutable `PluginPlan` and lockfile.

### `configure`

Normalize plugin options into serializable values. No model/IR mutation.

### `declare`

Register capabilities, schemas, structural requirements, contribution points, and names. Contributions are append-only and source-located.

### `elaborate`

Design plugins construct model content using public Nodal APIs and resolved services.

### `transform`

Native or process plugins run at named compiler extension points.

### `verify`

Core and plugin verifiers run with explicit ordering. Core safety/semantic verifiers cannot be disabled by a normal plugin.

### `emit`

Selected backend owns its declared artifacts. Multi-backend output is an explicit plan.

### `run`

External adapters invoke tools and return normalized evidence.

### `report`

Read-only report generation; no semantic mutation.

## Named compiler extension points

Candidate extension points are versioned and may be refined by Increment 79:

```text
after-bridge-parse
after-core-structural-verify
after-type-domain-effect-analysis
before-canonicalize
after-canonicalize
before-target-lowering
after-target-lowering
before-backend-verify
report-only
```

Each point declares:

- accepted IR/bridge version;
- available invariants;
- legal dialects and modifications;
- required analysis preservation/invalidation;
- mandatory exit verification;
- cacheability and determinism requirements.

A plugin pass pipeline appears in normalized compiler evidence.

## Native plugin compatibility

The initial in-process native contract requires exact equality for:

- Nodal native plugin ABI version;
- Nodal build ID;
- LLVM version/revision;
- MLIR version/revision;
- CIRCT revision;
- target platform/architecture;
- compiler ABI configuration where relevant.

A mismatch is diagnosed before `dlopen`/registration where possible.

MLIR's `MLIR_PLUGIN_API_VERSION` is necessary but not sufficient for Nodal compatibility.

## Out-of-process transform protocol

A process plugin receives:

- normalized versioned input IR;
- plugin options;
- requested extension point;
- source-map and manifest references;
- deterministic environment description.

It returns:

- normalized output IR or declared artifacts;
- diagnostics with stable plugin-local codes and source locations;
- analysis preservation/invalidation data;
- tool/plugin version and command evidence;
- output hashes.

Malformed, timed-out, or crashed plugins cannot leave partially accepted compiler state.

## Backend and tool adapters

### Backend registration

Candidate explicit selection:

```scala
Backend.Plugin("com.acme.backend.vhdl")
```

or another spelling selected by Increment 79.

A backend plugin provides a capability matrix and rejects unsupported constructs before translation.

`Backend.Auto` initially considers only built-in approved backends. A plugin backend can join auto-selection only through a future opt-in deterministic profile gate.

### External adapters

Tool adapter kinds include:

```text
simulator
synthesis
formal
fpga-place-route
bitstream
programmer
board-runtime
hil-transport
waveform-converter
reporter
```

All share one common process/evidence envelope plus kind-specific capability schemas.

## Configuration and lockfile

Candidate project declaration:

```toml
[[plugins]]
id = "com.acme.nodal.cpu-branch"
version = "1.2.0"
qualifier = "branch0"

[plugins.options]
late = true
```

The resolver produces `nodal.plugins.lock` containing:

- exact artifact coordinates and versions;
- hashes/signatures;
- resolved facets and platform binaries;
- capability provider mapping;
- qualifiers and normalized options;
- graph and phase order;
- native build IDs and process protocol versions;
- plugin-plan hash.

Release and CI builds use locked mode by default.

## Trust policy

Candidate trust classes:

```text
manifest-only
process-isolated
trusted-scala
trusted-native
```

Scala and native facets require explicit trusted enablement because they execute in process.

The policy may require:

- allowlisted IDs/publishers;
- exact hashes;
- signatures;
- license approval;
- offline-only resolution;
- process resource limits;
- disabled network access for adapters where practical.

## Determinism and caching

The plugin plan is a compiler input.

Cache keys include:

- plugin-plan/lockfile hash;
- plugin artifacts and options;
- provider resolution;
- phase/pass order;
- target platform and native build IDs;
- process adapter/tool versions where outputs depend on them.

A plugin declares whether it is deterministic and cacheable. A shared cache rejects evidence from a non-deterministic plugin.

Load-order permutation tests must prove identical normalized IR, HDL, diagnostics, and reports for semantically equivalent plugin declarations.

## Plugin versus library packaging

A library remains a source/model artifact. It can be installed without executing plugin lifecycle code.

A related plugin is separately enabled and separately versioned, even if both are released from one project.

Candidate optional repository structure:

```text
plugins/
└── <plugin-id>/
    ├── scala/
    ├── native/
    ├── process/
    ├── tests/
    ├── docs/
    ├── nodal-plugin.toml
    └── packaging/
```

The initial core roadmap may use only out-of-tree or `examples/plugins` fixtures. It does not require an official production plugin.

## Developer commands

Planned commands:

```text
nodal plugins list
nodal plugins resolve
nodal plugins check
nodal plugins graph
nodal plugins explain <capability>
nodal plugins lock
nodal plugins inspect <id>
```

Commands support human-readable and machine-readable output.

## Stable diagnostics to freeze

Increment 79 freezes codes/categories for at least:

- invalid manifest;
- duplicate plugin ID/instance qualifier;
- missing required capability;
- ambiguous exactly-one provider;
- capability version mismatch;
- SPI/core/API/IR/bridge incompatibility;
- native toolchain/ABI mismatch;
- conflict/replacement ambiguity;
- dependency or phase cycle;
- illegal phase operation;
- contribution after close;
- undeclared capability or artifact;
- namespace collision;
- invalid analysis preservation;
- forbidden core-semantic override;
- checksum/signature/trust failure;
- nondeterministic output;
- plugin crash, timeout, or malformed response;
- environment-dependent unpinned resolution.

## Compile-positive and structural fixture matrix

- manifest-only discovery without code execution;
- exactly-one, optional, many, and contribution capabilities;
- qualified multiple plugin instances;
- order-independent design composition;
- local nested hosts with explicit capability export/import;
- Scala frontend descriptor plugin;
- native MLIR pass plugin;
- native dialect plugin;
- out-of-process transform plugin;
- explicit backend plugin;
- simulator/tool adapter plugin;
- library plus separately enabled companion plugin;
- offline locked resolution;
- cache hit with unchanged plugin graph;
- external repository plugin using public SPI only.

## Negative fixture matrix

- concrete-plugin-class service lookup;
- process-global host/service registry;
- direct cross-plugin mutable field access;
- manual public retain/release ordering;
- missing or ambiguous provider;
- duplicate stable ID or qualifier;
- dependency/phase cycle;
- version or native ABI mismatch;
- untrusted in-process plugin;
- plugin that changes undeclared output;
- plugin that runs code during discovery;
- backend plugin silently joining `Backend.Auto`;
- plugin pass disabling required verification;
- invalid analysis preservation;
- classpath/environment-dependent plugin resolution;
- plugin crash/timeout/malformed response;
- load-order-dependent normalized output.

## Incremental delivery

### Increment 79 — Plugin architecture gate and SPI v0.1 contracts

- [ ] Compile candidate descriptors, capability keys, cardinalities, qualifiers, `DesignPlugin`, `DesignHost`, phase contexts, backend IDs, adapter descriptors, and manifest schemas.
- [ ] Publish `NodalPluginSpi-DG-v0.1.md` and a machine-readable frozen SPI surface.
- [ ] Freeze plugin/library separation, stable IDs, compatibility fields, lifecycle, allowed extension classes, and forbidden core-semantic overrides.
- [ ] Add positive/negative compile and manifest fixtures with stable diagnostics.

### Increment 80 — Resolver, capability graph, lockfile, and CLI

- [ ] Implement manifest-only discovery with no code execution.
- [ ] Implement version/capability/cardinality/conflict/qualifier/cycle resolution.
- [ ] Produce canonical immutable plugin plans and lockfiles.
- [ ] Add `list`, `resolve`, `check`, `graph`, `explain`, `lock`, and `inspect` commands.
- [ ] Include plugin-plan hashes in diagnostics and build manifests.

### Increment 81 — Local design composition host

- [ ] Implement local `DesignHost`, phase contexts, typed services, contribution points, close phases, and stable qualifiers.
- [ ] Prohibit concrete-plugin lookup, global registries, direct mutation, and public lock ordering.
- [ ] Add configurable architecture fixtures, nested hosts, multiple instances, conflict diagnostics, and load-order permutation tests.

### Increment 82 — Native compiler plugin loader and extension points

- [ ] Wrap MLIR pass and dialect plugin APIs with Nodal manifest validation.
- [ ] Add named versioned extension points, analysis preservation/invalidation, plugin namespaces, and mandatory exit verification.
- [ ] Enforce exact native toolchain build compatibility.
- [ ] Add out-of-process transform protocol and crash/malformed-response isolation.

### Increment 83 — Backend and external tool-adapter plugins

- [ ] Implement explicit backend registration, capability profiles, artifact contracts, and source maps.
- [ ] Keep plugin backends out of `Backend.Auto` by default.
- [ ] Implement the shared external process/evidence protocol for simulator, synthesis, formal, FPGA, programmer, board, and HIL adapters.
- [ ] Migrate core adapters to the common protocol without making external tools define semantics.

### Increment 84 — Packaging, trust, provenance, caching, and conformance

- [ ] Define coordinated Scala/native/process bundles, platform variants, Maven/native/process artifact metadata, checksums/signatures, licenses, and SBOMs.
- [ ] Implement trust policies, offline resolution, process limits, and explicit in-process enablement.
- [ ] Integrate plugin graphs with caching and release provenance.
- [ ] Publish the plugin conformance kit and out-of-tree reference design/pass/dialect/backend/tool plugins.
- [ ] Prove deterministic load-order permutations and compatibility failure behavior.

## Freeze exit criteria

Increment 79 may be checked only when:

1. the plugin SPI gate is approved;
2. manifest and lockfile schemas have stable version fields;
3. plugin IDs and capability IDs are stable strings independent of implementation classes;
4. design, frontend, compiler, backend, and tool-adapter boundaries are explicit;
5. plugin/library separation is tested;
6. lifecycle and legal phase operations are explicit;
7. provider cardinality and ambiguity behavior are frozen;
8. native ABI and process-protocol compatibility policies are documented;
9. positive and negative fixtures have stable diagnostics and source locations where applicable;
10. external consumer fixtures use no core internals; and
11. Core CI passes.

## References

- VexiiRiscv plugin host top level: <https://github.com/SpinalHDL/VexiiRiscv/blob/dev/src/main/scala/vexiiriscv/VexiiRiscv.scala>
- VexiiRiscv plugin service usage: <https://github.com/SpinalHDL/VexiiRiscv/blob/dev/src/main/scala/vexiiriscv/execute/BranchPlugin.scala>
- SpinalHDL plugin host: <https://github.com/SpinalHDL/SpinalHDL/blob/dev/lib/src/main/scala/spinal/lib/misc/plugin/Host.scala>
- SpinalHDL fiber plugin lifecycle: <https://github.com/SpinalHDL/SpinalHDL/blob/dev/lib/src/main/scala/spinal/lib/misc/plugin/Fiber.scala>
- MLIR pass plugin API: <https://github.com/llvm/llvm-project/blob/main/mlir/include/mlir/Tools/Plugins/PassPlugin.h>
- MLIR dialect plugin API: <https://github.com/llvm/llvm-project/blob/main/mlir/include/mlir/Tools/Plugins/DialectPlugin.h>
- MLIR standalone plugin example: <https://github.com/llvm/llvm-project/blob/main/mlir/examples/standalone/standalone-plugin/standalone-plugin.cpp>
