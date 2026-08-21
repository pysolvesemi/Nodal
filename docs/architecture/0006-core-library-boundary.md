# ADR 0006: Separate mandatory core from optional reusable libraries

- **Status:** Accepted
- **Date:** 2026-08-20
- **Scope:** Repository, build, package, and API dependency architecture

## Context

Nodal core must provide the language and compiler independently of any reusable model collection. Future users may need shared device models, AMS blocks, interfaces, connect rules, and verification helpers, but those packages need different ownership, release, compatibility, and licensing lifecycles.

Mixing language implementation and optional model content would make the compiler depend on libraries and make cross-project reuse difficult.

## Decision

Nodal uses separate top-level architectural roots:

```text
Nodal/
├── core/
└── libraries/   # reserved for future optional packages
```

The allowed dependency direction is:

```text
libraries ──► published core APIs
core      ──X──► libraries
```

`core/` owns mandatory infrastructure:

- public language APIs and approved extension contracts;
- elaboration, diagnostics, and the Scala-to-MLIR bridge;
- native dialects, analyses, transformations, and compiler tools;
- Verilog-A/Verilog-AMS backends;
- core simulation APIs and simulator adapters;
- language conformance and compiler regression infrastructure.

Future `libraries/` packages may own optional reusable content:

- behavioral and device models;
- reusable analog/mixed-signal blocks;
- disciplines, interfaces, and connect packages;
- verification helpers and domain-specific collections.

No official reusable library is implemented by the initial core roadmap. Empty library directories are not committed merely as placeholders.

Each future library must:

- compile through the same published API available to an external user project;
- have no privileged access to frontend/compiler internals;
- own its source root, tests, documentation, artifact identity, semantic version, license metadata, and core compatibility range;
- be independently selectable, buildable, and publishable;
- remain movable to another repository without redesigning Nodal core.

Core regression examples may resemble reusable models, but they remain test fixtures until a separate library roadmap and publication decision promote them.

## Enforcement

The architecture will be enforced progressively:

- build modules expose no core dependency on library modules;
- package visibility prevents external access to core internals;
- architecture tests inspect dependency direction;
- CI builds core with no library checkout/artifact;
- API contract fixtures compile an external-library-style consumer;
- packaging gives core and each future library independent coordinates and versions.

Generated files or tests cannot create a hidden reverse dependency.

## 2026-08-21 extension clarification

[ADR 0012](0012-versioned-capability-plugin-architecture.md) distinguishes executable plugins from source/model libraries.

A library remains passive reusable source/data content. A plugin is explicitly enabled executable extension code with lifecycle, compatibility, trust, and provenance requirements. Optional plugin bundles may use a reserved `plugins/` root or independent repositories, but they remain outside mandatory core.

The extended dependency direction is:

```text
libraries ───────────────► published core APIs
plugins ─────────────────► published core SPI/APIs
plugins ── optional ─────► published libraries
core ──X─────────────────► libraries or plugins
libraries ──X────────────► plugin implementations
```

A library may publish a separate companion plugin artifact, but installing the library does not execute that plugin and does not implicitly enable it.

## Consequences

### Positive

- Nodal core remains small, sufficient, and independently releasable.
- Libraries can evolve at different rates and under different licenses.
- A reusable package developed in this monorepo behaves like a real external consumer.
- Users install only the packages they need.
- Future libraries can move to independent repositories.
- Executable extensions receive stronger trust and compatibility controls than passive model packages.

### Costs

- Library authors cannot use convenient compiler internals.
- Shared API changes require compatibility discipline.
- Cross-repository testing and version matrices become necessary after libraries exist.
- Some helpers must be promoted deliberately into stable core extension APIs.
- A project that ships both models and executable extensions may publish separate coordinated artifacts.

## Rejected alternatives

- **Put reusable components under `core`:** confuses language requirements with optional content.
- **Let core use a standard library internally:** creates a reverse dependency and prevents core-only use.
- **Delay the boundary until the first library:** risks accidentally exposing internals and entangling build paths.
- **Require every library to remain in the monorepo:** unnecessarily restricts ownership and publication.
- **Treat every library as an automatically loaded plugin:** executes code unexpectedly and conflates source compatibility with plugin SPI/ABI compatibility.

## Follow-up increments

- Increment 3 creates the scalable core skeleton and boundary rules.
- Increment 8 adds CI enforcement.
- Increments 10–12 validate the external library-author surface.
- Increments 79–84 freeze and implement the plugin SPI, capability graph, loaders, adapters, packaging, trust, and conformance.
- Increment 87 defines the future library publication contract separately from plugin packaging.
