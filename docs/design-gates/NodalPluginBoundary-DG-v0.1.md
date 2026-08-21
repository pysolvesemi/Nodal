# NodalPluginBoundary-DG v0.1

**Status:** Approved  
**Scope:** core-library-boundary  
**Decision:** Separate passive reusable libraries from explicitly enabled executable plugins  
**Approved by:** Repository owner instruction to evaluate VexiiRiscv-style plugins and add a scalable Nodal plugin roadmap on 2026-08-21

## Decision boundary

This gate approves the durable dependency and packaging boundary introduced by ADR 0012. It does not freeze the exact plugin SPI syntax, implement a resolver or loader, authorize plugins to use core internals, or create an official production plugin.

The exact public plugin SPI remains controlled by future Increment 79 and its `NodalPluginSpi-DG-v0.1.md` gate.

## Approved architecture

Nodal distinguishes two optional extension forms:

1. A **library** is passive reusable source/data content that compiles through published Nodal core APIs.
2. A **plugin** is explicitly enabled executable extension code governed by a versioned SPI, lifecycle, compatibility, trust, and provenance contract.

Installing or depending on a library must not execute, discover, or enable a plugin automatically. A project may publish a library and a companion plugin, but they have distinct artifact identities and the plugin is selected explicitly.

The approved dependency direction is:

```text
libraries ───────────────► published core APIs
plugins ─────────────────► published core SPI/APIs
plugins ── optional ─────► published libraries
core ──X─────────────────► libraries or plugins
libraries ──X────────────► plugin implementations
```

Core must continue to build, test, package, and release without an optional library or plugin checkout/artifact.

## Approved plugin classes

The roadmap may separately plan:

- local design-composition plugins;
- approved Scala/frontend descriptor plugins;
- native compiler pass, analysis, dialect, and verifier plugins;
- explicitly selected backend plugins;
- out-of-process simulator, synthesis, formal, FPGA, board, programmer, and HIL adapters.

These categories do not grant unrestricted access to core implementation packages or permission to override core language semantics.

## Required safeguards

The future SPI must preserve:

- stable plugin and capability identities independent of implementation class names;
- explicit project selection and compatibility resolution;
- manifest-first discovery without executing plugin code;
- deterministic provider/cardinality/conflict resolution and lockfiles;
- no hidden core dependency on plugins or libraries;
- explicit trust for in-process Scala/native extensions;
- process isolation for external tool adapters where appropriate;
- checksums, provenance, license/SBOM metadata, and cache-key participation;
- positive and negative external-consumer fixtures.

## Rejected alternatives

- treating every model library as an automatically loaded executable plugin;
- allowing core to depend on an optional plugin or library;
- giving plugins privileged access to frontend/compiler internals by default;
- enabling companion plugins merely because a library is present;
- conflating source compatibility with Scala/native/process plugin compatibility;
- using plugin support to bypass public API, design-gate, or safety-verifier requirements.

## Follow-up

- ADR 0012 records the accepted high-level plugin architecture.
- Increments 79–84 freeze and implement the SPI, capability graph, local design host, compiler/backend/tool plugin boundaries, packaging, trust, provenance, caching, and conformance.
- Increment 87 defines passive library publication separately from executable plugin packaging.

This boundary remains binding unless superseded by a later approved `core-library-boundary` design gate.
