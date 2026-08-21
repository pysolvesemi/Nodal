# ADR 0012: Use versioned capability graphs for Nodal plugins

- **Status:** Accepted
- **Date:** 2026-08-21
- **Scope:** Design composition, Scala/frontend extensions, native compiler extensions, backends, external tool adapters, packaging, compatibility, trust, and reproducibility

## Context

VexiiRiscv demonstrates that a hardware architecture can be composed almost entirely from plugins. Its top-level component owns a database and `PluginHost`, while plugins generate the actual hardware. Plugins discover other plugins and service traits by type, may request exactly one, optionally one, or many providers, and use setup/build/patch/check fibers plus retainers and locks to coordinate late contributions.

This approach provides valuable properties:

- optional features are independently selectable;
- a small host can compose a large design;
- plugins communicate through typed service interfaces;
- multiple providers and contribution aggregation are possible;
- setup and build phases allow late architectural decisions;
- plugin order can often be decoupled from source order.

Nodal has a wider extension problem than one configurable CPU. It must support:

- reusable design-composition plugins for configurable IP and subsystem generators;
- Scala-side extension descriptors and public construction helpers;
- native MLIR dialect, analysis, pass, and translation plugins;
- backend capability plugins;
- simulator, synthesis, formal, FPGA, and HIL tool adapters;
- independent packaging across Scala, native shared libraries, and external processes;
- deterministic builds, content-addressed caching, compatibility checks, and provenance.

A direct copy of VexiiRiscv's host would be too weak for those requirements. Class-reflection lookup, mutable cross-plugin objects, manual retain/release ordering, and in-process-only loading do not provide stable identities, version negotiation, ABI checks, isolation, or reproducible resolution across independently published artifacts.

MLIR already exposes versioned C entry points for pass and dialect plugins. Nodal should wrap those mechanisms rather than inventing an incompatible native loader, while adding Nodal-specific manifests, extension points, toolchain compatibility, and semantic verification.

## Decision

Nodal uses a **manifest-first, capability-scoped, deterministic plugin graph**.

The binding rule is:

> **Explicit plugin plan, typed capability graph, deterministic phases, isolated extension boundaries, retained provenance.**

Nodal keeps the strongest ideas from VexiiRiscv:

- an explicit local host for design composition;
- typed services and contribution points;
- optional and multiple providers;
- staged configuration and elaboration;
- late aggregation before final structure is committed.

Nodal replaces implicit or runtime-only mechanisms with:

- stable plugin and capability identifiers;
- semantic versions and compatibility ranges;
- explicit cardinality, qualifiers, conflicts, and dependencies;
- manifest-only discovery before code execution;
- a resolved immutable plugin plan and lockfile;
- canonical phase ordering and cycle diagnostics;
- append-only contributions instead of direct mutation of another plugin;
- separate Scala, native, backend, and process-adapter SPIs;
- checksums, trust policy, provenance, and cache-key participation.

## Plugin categories

### Design composition plugins

A `DesignPlugin` composes an explicitly created `DesignHost`. It is an elaboration-time mechanism for configurable architectures such as CPUs, controllers, mixed-signal subsystems, verification shells, and reusable generators.

A design plugin may:

- provide typed design services;
- contribute declarations, decode rules, pipeline elements, interfaces, reports, or verification hooks to named contribution points;
- consume capabilities declared by other design plugins;
- construct hardware through the frozen public Nodal model-authoring API;
- expose serializable configuration and deterministic generated evidence.

A design plugin may not:

- use a process-global service locator;
- locate another plugin by concrete implementation class;
- mutate another plugin's fields directly;
- use manual locks or retain/release calls to define semantic ordering;
- access frontend, compiler, backend, or simulator internals;
- bypass clock/reset, CDC/RDC, numeric, quantity, effect, or connection rules;
- load or unload plugins after graph resolution begins.

### Scala/frontend extension plugins

A Scala/frontend extension may register only approved namespaced extension descriptors such as:

- annotations and metadata schemas;
- external-operation contracts;
- typed attributes;
- diagnostics and lints;
- design-host capability types;
- source-mapping helpers;
- public construction helpers implemented only through supported APIs.

The initial SPI does not permit a plugin to replace core types, operators, width rules, units, clock/reset semantics, connection rules, or ordinary syntax. New language semantics require a versioned public design gate and core support.

### Native compiler plugins

Native plugins may provide:

- MLIR passes;
- analyses;
- named pass-pipeline extensions;
- out-of-tree dialects under a plugin-owned namespace;
- dialect interfaces;
- verification and lint rules;
- target-neutral transformations;
- translations or backend components where explicitly supported.

Nodal wraps MLIR's pass and dialect plugin entry points. A native in-process plugin must match the Nodal plugin ABI version and the exact pinned LLVM/MLIR/CIRCT toolchain build identity. No stable C++ ABI is promised across different toolchain revisions.

For longer-lived compatibility or isolation, Nodal also supports a versioned out-of-process transform protocol operating on normalized textual MLIR or another approved bridge representation.

Native plugins cannot silently weaken core verifiers. Core verification runs after plugin transformations at required boundaries.

### Backend plugins

A backend plugin registers:

- a stable backend ID;
- a capability profile;
- accepted design kinds and IR features;
- options schema;
- output and sidecar artifact contract;
- deterministic formatting and source-mapping behavior;
- tool or runtime requirements;
- unsupported-feature diagnostics.

Third-party backends are selected explicitly in the initial SPI. They do not participate in `Backend.Auto` merely because a plugin happens to be installed. A later opt-in auto-selection policy may use only an explicitly locked plugin graph and an approved deterministic priority rule.

### External tool-adapter plugins

Simulator, synthesis, formal, FPGA, programmer, board, and HIL integrations use an out-of-process adapter protocol rather than being linked into the compiler.

An adapter declares:

- tool kind and capability ID;
- supported profiles and artifact types;
- version discovery and command schema;
- environment and licensing requirements;
- input/output/log/waveform/report contracts;
- timeout and cancellation behavior;
- reproducibility and cache policy;
- whether the adapter is safe for CI.

External tools never define Nodal language semantics.

### Libraries are not plugins

A model library is source/data content that compiles through public Nodal APIs. It is not automatically executable as a plugin and receives no lifecycle callbacks.

A library may publish a separate optional plugin bundle, for example a design-composition plugin or tool adapter. That plugin has a distinct artifact identity and must be explicitly enabled.

Dependency direction is:

```text
libraries ───────────────► public Nodal core API
plugins ─────────────────► public Nodal core SPI/API
plugins ── optional ─────► published libraries
core ──X─────────────────► libraries or plugins
libraries ──X────────────► plugin implementations
```

## Stable identities and capability keys

Every plugin has a globally stable ID, for example:

```text
com.example.nodal.branch-predictor
org.nodal.tool.verilator
org.nodal.backend.portable-verilog
```

Every service or contribution point has a stable capability ID and independent capability version. Scala type parameters provide local type safety, but runtime identity does not depend on reflection or a class name.

Directional candidate:

```scala
object DecodeRules extends ContributionKey[DecodeRule](
  id = "org.example.cpu.decode-rules",
  version = 1
)

object FetchService extends CapabilityKey[FetchApi](
  id = "org.example.cpu.fetch",
  version = 1,
  cardinality = ExactlyOne
)
```

Supported cardinality policies include:

- exactly one;
- zero or one;
- one or more;
- zero or more;
- explicitly qualified provider;
- contribution set or ordered contribution sequence.

The resolver diagnoses missing, duplicate, ambiguous, incompatible, or cyclic capabilities before elaboration.

## Plugin manifest

Each extension bundle includes a machine-readable manifest containing at least:

- plugin ID and semantic version;
- plugin SPI version;
- supported Nodal core/API/IR/bridge versions;
- exact native toolchain build ID where applicable;
- plugin categories or facets;
- provided and required capabilities with versions and cardinality;
- conflicts and replacements;
- named lifecycle phases and pass extension points;
- serializable options schema and defaults;
- platform artifacts and hashes;
- license and provenance metadata;
- trust/isolation requirements;
- deterministic entry-point names.

Discovery parses manifests without loading Scala classes, native shared libraries, or executables.

## Resolution and lockfile

A project explicitly lists enabled plugins. Nodal does not scan an arbitrary classpath, shared-library directory, current directory, or environment for executable extensions.

Resolution performs:

1. manifest schema validation;
2. Nodal/SPI/IR/bridge/toolchain compatibility checks;
3. capability-provider selection;
4. cardinality and qualifier validation;
5. conflict/replacement validation;
6. cycle detection;
7. deterministic phase and pass-extension ordering;
8. platform artifact selection;
9. checksum and trust-policy validation;
10. production of an immutable plugin plan and lockfile.

Ties that do not carry semantic ordering are canonicalized by stable plugin ID and instance qualifier, never by discovery order, classpath order, hash-map iteration, or JVM object identity.

The lockfile records artifact coordinates, exact versions, hashes, resolved providers, options, graph order, native build IDs, and process protocol versions. It participates in every relevant cache key.

## Lifecycle

The common conceptual lifecycle is:

```text
discover → resolve → configure → declare → elaborate → transform → verify → emit → run → report
```

Not every plugin participates in every phase.

### Discover and resolve

Only manifests are read. No plugin code runs.

### Configure

Configuration is validated and normalized into serializable values. Hardware, IR, and external processes are not created.

### Declare

Plugins register typed capabilities, schemas, names, contribution points, and structural requirements through append-only APIs.

### Elaborate

Design plugins create hardware and contributions using only the public construction API and the resolved context.

### Transform and verify

Native plugins run at named compiler extension points. Required analyses and preservation/invalidation are explicit. Core verification reruns after extension transforms.

### Emit

Exactly one selected backend owns each output artifact unless an explicit multi-output plan is requested.

### Run and report

External adapters execute as processes and return normalized artifacts and evidence. Report callbacks are read-only with respect to model and IR semantics.

There is no unrestricted `patch` phase after verification. A plugin requiring a later mutation must use an approved named extension point with explicit invalidation and re-verification.

## DesignHost contribution model

A `DesignHost` is local to one explicit model region. Multiple hosts may exist in one design without sharing services accidentally.

Directional shape:

```scala
val subsystem = DesignHost(
  plugins = Seq(
    FetchPlugin(...),
    DecodePlugin(...),
    BranchPlugin(...)
  )
).build()
```

A plugin receives phase-specific contexts rather than the host's mutable internals:

```scala
final class BranchPlugin extends DesignPlugin:
  override val descriptor = plugin("org.example.branch", "1.0.0")
    .requires(FetchService)
    .contributes(DecodeRules)

  override def declare(ctx: DeclareContext): Unit =
    ctx.contribute(DecodeRules, branchDecodeRules)

  override def elaborate(ctx: ElaborateContext): Unit =
    val fetch = ctx.require(FetchService)
    // Construct Nodal hardware through the public API.
```

Exact syntax is frozen by the plugin SPI gate. The semantic requirements are binding:

- phase contexts expose only legal operations;
- services are interfaces keyed by stable capability IDs;
- contribution collection is append-only until its declared close point;
- merge and conflict rules are deterministic;
- consumers cannot depend on plugin implementation classes;
- a plugin instance has a stable explicit qualifier when multiple instances are legal;
- source locations and plugin provenance are attached to every contribution.

## Compiler extension points

Nodal exposes named, versioned extension points instead of arbitrary pass insertion. Candidate categories include:

- after bridge parse;
- after core structural verification;
- after domain/type/effect analysis;
- before and after canonicalization;
- before target-specific lowering;
- before backend verification;
- report-only analysis hooks.

An extension point specifies:

- accepted IR version and dialect set;
- invariants available on entry;
- legal modifications;
- analyses that must be preserved or explicitly invalidated;
- mandatory verifiers on exit;
- whether the point participates in deterministic caching.

A plugin cannot insert a transform ahead of source-location capture, capability checks, or safety-critical core verification unless an explicit experimental mode and design gate permit it.

## Isolation and trust

Scala and native in-process plugins execute arbitrary host code. Nodal therefore treats them as trusted artifacts and requires explicit enablement.

The plugin policy supports:

- allowlists and deny lists;
- exact checksums;
- optional signatures;
- license and SBOM metadata;
- offline resolution;
- process isolation for transform/backend/tool plugins where supported;
- resource/time limits and cancellation for external processes;
- no automatic download or execution during ordinary compilation without project policy.

A plugin crash or malformed response must be reported with plugin identity, phase, source context, and retained diagnostic artifacts.

## Reproducibility and caching

Every build manifest records:

- resolved plugin graph and lockfile hash;
- plugin artifact and configuration hashes;
- selected providers and qualifiers;
- lifecycle and pass order;
- native toolchain build IDs;
- external adapter commands and versions;
- plugin-produced IR, HDL, reports, and source-map provenance.

A cache entry is invalid when any semantic plugin input changes. Plugins must declare whether they are deterministic and which inputs influence their output. Non-deterministic plugins are excluded from shared caches and release evidence.

Load-order permutation tests must prove that unrelated plugin declaration order does not change normalized IR, HDL, diagnostics, or reports.

## Packaging

A plugin bundle may contain one or more coordinated facets:

- Scala 3 Maven artifact;
- native shared library for each supported host platform;
- out-of-process executable;
- schemas, templates, models, or support files;
- manifest, checksums, license, and SBOM.

The bundle version identifies the coordinated release, while each facet also declares its SPI/protocol compatibility.

Reference plugins in the Nodal repository remain conformance fixtures under `examples/` or `tests/`; optional production plugins may use a reserved top-level `plugins/` structure or independent repositories. Core must build and release with no optional plugin checkout or artifact.

## CLI and diagnostics

The planned developer interface includes:

```text
nodal plugins list
nodal plugins resolve
nodal plugins check
nodal plugins graph
nodal plugins explain <capability>
nodal plugins lock
```

Diagnostics must cover:

- missing or duplicate provider;
- ambiguous qualifier;
- capability version mismatch;
- plugin SPI or Nodal compatibility mismatch;
- native toolchain/ABI mismatch;
- conflict or replacement ambiguity;
- dependency cycle;
- illegal phase access;
- undeclared contribution;
- plugin namespace collision;
- invalid analysis preservation;
- non-deterministic output;
- checksum or trust failure;
- plugin crash, timeout, or malformed process response.

## Consequences

### Positive

- Nodal can support VexiiRiscv-style configurable architectures without making the entire compiler a mutable service locator.
- Design, compiler, backend, and tool plugins use appropriate isolation and compatibility boundaries.
- Missing and ambiguous services fail before elaboration rather than deep inside plugin code.
- Plugin graphs, pass ordering, configuration, and artifacts are reproducible and cacheable.
- Optional extensions remain independently publishable and cannot become hidden core dependencies.
- Native MLIR plugin mechanisms are reused where appropriate.
- Tool adapters for simulators, synthesis, formal, FPGA, and HIL share one process protocol and provenance model.

### Costs

- Plugin authors must write manifests and declare capabilities explicitly.
- Native in-process plugins require exact toolchain compatibility.
- Immutable contributions and named phases are less permissive than arbitrary shared mutation.
- Design plugin APIs and compiler SPIs require independent compatibility tests and versioning.
- Out-of-process isolation adds serialization and launch overhead.

## Rejected alternatives

- **Copy VexiiRiscv's PluginHost directly:** insufficient versioning, isolation, packaging, and reproducibility for a language/compiler ecosystem.
- **Use concrete Scala class types as global service identity:** couples consumers to implementations and does not survive independent artifacts or protocol boundaries.
- **Allow arbitrary mutable cross-plugin access:** creates hidden order dependence and invalidates caching and parallel elaboration.
- **Use retain/release locks as the public ordering contract:** difficult to diagnose, prone to cycles, and not serializable.
- **Scan the classpath or plugin directories automatically:** makes builds environment-dependent and unsafe.
- **Treat libraries and plugins as the same artifact:** source reuse and executable extension code have different trust and compatibility requirements.
- **Promise a stable C++ ABI across LLVM/MLIR revisions:** unrealistic; exact native build matching or process isolation is required.
- **Allow plugins to override core semantics:** destroys portability and language compatibility.
- **Make all plugins out-of-process:** design composition needs efficient typed Scala construction, while trusted native passes benefit from MLIR's in-process registration.
- **Make all plugins in-process:** tool adapters and long-lived external extensions need isolation and protocol versioning.

## Follow-up increments

- Increment 79 freezes the plugin SPI, manifest, capability, lifecycle, and compatibility contracts.
- Increment 80 implements manifest discovery, graph resolution, lockfiles, and developer inspection commands.
- Increment 81 implements the local `DesignHost`, typed capabilities, contribution points, and deterministic phase contexts.
- Increment 82 implements MLIR pass/dialect/analysis plugin loading and named compiler extension points.
- Increment 83 implements backend registration and the out-of-process tool-adapter protocol.
- Increment 84 completes packaging, trust, provenance, cache integration, conformance tests, and out-of-tree reference plugins.
- Increment 85 continues with versioned IR and bridge compatibility after plugin boundaries are established.
- Increment 87 defines the separate future library publication contract.

## References reviewed

- VexiiRiscv plugin-only top level: <https://github.com/SpinalHDL/VexiiRiscv/blob/dev/src/main/scala/vexiiriscv/VexiiRiscv.scala>
- VexiiRiscv typed service and phased plugin usage: <https://github.com/SpinalHDL/VexiiRiscv/blob/dev/src/main/scala/vexiiriscv/execute/BranchPlugin.scala>
- SpinalHDL `PluginHost`: <https://github.com/SpinalHDL/SpinalHDL/blob/dev/lib/src/main/scala/spinal/lib/misc/plugin/Host.scala>
- SpinalHDL `FiberPlugin`: <https://github.com/SpinalHDL/SpinalHDL/blob/dev/lib/src/main/scala/spinal/lib/misc/plugin/Fiber.scala>
- SpinalHDL plugin service demo: <https://github.com/SpinalHDL/SpinalHDL/blob/dev/tester/src/main/scala/spinal/lib/misc/plugin/ServiceDemo.scala>
- MLIR pass plugin API: <https://github.com/llvm/llvm-project/blob/main/mlir/include/mlir/Tools/Plugins/PassPlugin.h>
- MLIR dialect plugin API: <https://github.com/llvm/llvm-project/blob/main/mlir/include/mlir/Tools/Plugins/DialectPlugin.h>
- MLIR standalone plugin example: <https://github.com/llvm/llvm-project/blob/main/mlir/examples/standalone/standalone-plugin/standalone-plugin.cpp>
