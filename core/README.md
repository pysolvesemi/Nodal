# Nodal core layout

`core/` contains everything required to author, elaborate, compile, emit, and test Nodal models without any optional reusable library.

The directory is intentionally split by responsibility before implementation begins:

```text
core/
├── modules.toml          # Authoritative module and dependency manifest
├── scala/
│   ├── api/              # Backend-neutral public language surface
│   ├── frontend/         # Elaboration, hierarchy, names, locations, validation
│   ├── bridge/           # Versioned textual-MLIR and nodalc process boundary
│   ├── sim/              # Public simulation/regression API
│   ├── cli/              # JVM command-line entry points
│   └── testkit/          # Core-only test support
├── compiler/             # Native MLIR/CIRCT compiler and nodalc
└── integrations/         # External compiler/simulator adapters
```

Each module owns a `module.toml` descriptor. Later Mill and CMake bootstraps may generate or validate build declarations from the same dependency graph, but they must not create a second contradictory architecture graph.

## Allowed dependency direction

```text
frontend ───────────────► api
bridge ─────────────────► frontend, api
sim ────────────────────► bridge, api, integrations
cli ────────────────────► sim, bridge, frontend, api
testkit ────────────────► cli, sim, bridge, frontend, api

compiler                 # independent native authority; consumes bridge output, not frontend internals
integrations             # external-tool adapters with no frontend dependency
```

Arrows mean “depends on.” The exact declared edges live in `core/modules.toml` and the referenced module descriptors.

Mandatory rules:

- every declared dependency must be explicit;
- the dependency graph must remain acyclic;
- `core.compiler` must not depend on `core.scala.frontend` or frontend implementation packages;
- no module under `core/` may depend on a future `libraries.*` module or source path;
- core must build and test with no `libraries/` directory present;
- optional libraries, when introduced by a separate roadmap, depend only on published core APIs.

Run the architecture check with:

```bash
python3 scripts/check_architecture.py
python3 -m unittest discover -s tests/architecture -p 'test_*.py'
```

Increment 3 creates descriptors and checks only. Scala build sources arrive in Increment 4; native compiler sources arrive in Increment 6; CI wiring arrives in Increment 8.
