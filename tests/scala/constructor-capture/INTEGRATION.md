# Next execution and production boundary

## Required compile checkpoint

This directory stays outside normal Mill module `src` and `test/src` roots.
Its fixtures compile in separate stages rather than as one test source set.
The standalone protocol introduces no production dependency. The production
compiler artifact remains a compiler-only leaf, and producer modules opt into it
through the existing build owner.

`scripts/nodal.py` invokes the harness after the existing Scala compile/test
commands with a fresh run directory under `.validation/constructor-capture/`.
Thus `./nodal core scala` and full `./nodal check` execute the same mandatory
probe locally and in CI. The existing Core Scala job still calls that command;
its additional always-run step retains the probe's evidence, including failure
logs. Missing artifacts fail retention and cannot establish prototype evidence.

`build.mill` adds a plain `constructorCaptureSources extends Module` with only
a recursive `Task.Sources` target. Existing global Scalafmt `checkFormatAll` /
`reformatAll` discover it through `__.sources`; it has no ordinary compile target.
The same build adds the prototype root to existing Scalafix arguments. Both
commands retain the repository's single pinned versions, configurations and
`./nodal style check` / `fix` routes. No new formatter command, ScalaModule,
compilation flattening or lint exception is introduced.

Developer-command tests verify the probe call and failure propagation. The
source still needs exact-head targeted CI qualification. Inspect real compile
and runtime logs, `-Ycheck:all`, all six expected negatives, raw-factory rejection
and the source/command manifest before crediting prototype evidence. A green
artifact upload is not compiler success.

Expected stage dependency shape:

| Stage | Compile dependencies | Plugin enabled? |
| --- | --- | --- |
| Probe runtime | Existing Scala libraries | No |
| Compiler plugin | Existing pinned Scala compiler classpath | No |
| Definitions JAR | Runtime JAR | Yes |
| Factory JAR | Runtime and definitions JARs | Yes |
| Consumer | Runtime, definitions and factory JARs | Yes |
| Raw definitions | Runtime JAR | No |
| Raw factory | Runtime and definitions JARs | No |
| Negative/boundary consumers | Corresponding JARs and runtime | Yes |
| Production definitions JAR | Actual API JAR | Actual production plugin |
| Production factory JAR | Actual API and definitions JARs | Actual production plugin |
| Production consumer | Actual API, definitions and factory JARs | Actual production plugin |
| Production raw factory | Actual API and definitions JARs | No |
| Production boundary consumer | Actual API, definitions and raw factory JARs | Actual production plugin |

The plugin locates runtime symbols by their exact fully qualified compiler
symbols. It imports no runtime or core API binary. The runtime imports no plugin
or compiler. Definitions/factories depend on the runtime, and consumers depend
on their JARs. This avoids an API/frontend/compiler-plugin dependency cycle.

## Production owner and implemented hooks

The real owner is `ConstructionSession` / `ConstructionKernel` in
`core/scala/api/src/nodal/ElaborationConstructionKernel.scala`, with declaration
and lifecycle entry points in `CandidateApi.scala`. Constructor capture reuses
those owners and does not add another production hierarchy registry.

| Existing behavior | Integrated production behavior |
| --- | --- |
| `Param` constructor normally calls `CandidateRuntime.declare`. | Compiler-created carriers are inert until `Module.begin`; explicit `param(...)` retains the existing declaration path. The child declaration/default and parent actual remain distinct. |
| `Module` invokes `CandidateRuntime.beginModule(this)`. | The existing child context binds exact pending carriers once through the canonical declaration method. Retained field copies remain inert. |
| Explicit `Instance` attaches the child and `.param` runs existing legality. | Captured allocation creates or reuses that canonical handle and invokes the same attachment/override policy. Explicit naming retains priority over the automatic module alias. |
| A `ConstructionSession` is scoped by `ConstructionKernel.elaborate`. | Partial constructor/argument effects poison publication; a clean rejection before `Module.begin` is catchable. All exits restore scoped state and a fresh elaboration remains clean. |

The failure policy is deliberately narrower than an owner-wide rollback journal.
Once a constructor enters `Module.begin`, or argument evaluation has already
produced construction effects, a caught failure invalidates the whole transaction.
A rejection before `Module.begin` with no new construction depth is recoverable.
Public regressions cover both poison paths, clean recovery and a subsequent fresh
transaction. This policy does not authorize caught continuation after partial
child construction.

The compiler artifact is a compiler-only leaf using the single `Versions.scala3`
pin. Version-1 `ConstructorSchema` metadata and generated runtime calls form the
internal ABI. API/frontend compile without loading the plugin; testkit and all
repository example producer modules enable it through `ConstructorCapturedProducer`.
External producer and factory source in the supported profile must also enable
the plugin. A prebuilt uninstrumented factory is rejected; it is not silently
treated as a legacy explicit constructor.

## Approved scope and remaining capability boundary

The approved lightweight hierarchy gate expressly requests pinned constructor
and separate-compilation prototypes and permits necessary frontend/lifecycle
hooks. It covers this experiment; it is not evidence that the experiment works.
The gate does not explicitly specify a mandatory compiler plugin or support for
prebuilt factories lacking instrumentation. This probe makes that distinction
observable: instrumented non-inline factory JARs are supported by the proposed
protocol; an uninstrumented factory is rejected.

The producer plugin requirement, versioned runtime/metadata ABI and
missing-instrumentation behavior are recorded here and in the existing approved
lightweight hierarchy gate. Do not reinterpret separate compilation as support
for arbitrary prebuilt factory binaries.

General nonliteral constructor defaults, independent target witnesses and the
rest of the public API contract remain open. No roadmap checkbox, acceptance
receipt, source/output witness or historical gate is changed here.
