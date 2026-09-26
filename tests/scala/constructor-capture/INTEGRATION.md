# Next execution and production boundary

## Smallest next compile checkpoint

This directory stays outside normal Mill module `src` and `test/src` roots.
Its fixtures compile in separate stages rather than as one test source set.
No new production Mill dependency is introduced.

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

The plugin locates runtime symbols by their exact fully qualified compiler
symbols. It imports no runtime or core API binary. The runtime imports no plugin
or compiler. Definitions/factories depend on the runtime, and consumers depend
on their JARs. This avoids an API/frontend/compiler-plugin dependency cycle.

## Existing production owner and minimum required hooks

The real owner is still `ConstructionSession` / `ConstructionKernel` in
`core/scala/api/src/nodal/ElaborationConstructionKernel.scala`, with declaration
and lifecycle entry points in `CandidateApi.scala`. The prototype does not add
another production hierarchy registry.

| Existing behavior | Required focused integration |
| --- | --- |
| `Param` constructor immediately calls `CandidateRuntime.declare`. | Add a compiler-carrier construction mode that is inert before `Module.begin`; keep explicit `param(...)` on its current declaration path. Construct the actual child default in the child context; do not let literal materialization register it in the parent. |
| `Module` invokes `CandidateRuntime.beginModule(this)`, which begins kernel and procedural state. | After those owners enter the child context, bind the exact pending carriers once through the existing declaration method with compiler parameter/source metadata. Retained parameter field copies remain inert. |
| Explicit `Instance` attaches the child and `.param` runs existing override legality. | On successful captured allocation, create/reuse the canonical instance handle and invoke the same attachment and override path. Direct child aliases must resolve by exact recorded identity. Do not infer ownership from class/member names or create value-specialized module definitions. |
| A whole `ConstructionSession` is scoped by `ConstructionKernel.elaborate`; `finish` rejects unattached children. | Complete captured allocation inside that owner. A failure must prevent snapshot publication and leave later elaborations clean. Continuing after a caught child failure would require coordinated rollback across all touched owners. |

The last row is a concrete production gap. Current source has mutable module,
domain, declaration, expression and instance identity maps, module/domain
stacks, operation and analog/operator records, semantic origins and procedural
state. A rollback that only pops `moduleStack` would leave invalid state behind.
A bounded owner-local undo journal/checkpoint would need to cover every touched
owner to permit caught-child continuation. A smaller initial production policy
is to poison the existing session on constructor/argument failure and refuse
`finish` publication even if host code catches the exception, then restore scoped
procedural state and permit a fresh elaboration. The failure guard must cover
argument evaluation while the pending child frame starts only after those
arguments have evaluated. Either policy needs actual production tests. The
standalone trace tests do not prove this production rollback. Do not port the
trace registry into production or claim B.1.1 from the prototype alone.

A production compiler artifact should remain a compiler-only leaf using the
single `Versions.scala3` pin. Its runtime bridge belongs to the existing API
construction owner, and generated calls need a deliberate internal ABI and
source identity format. Enabling the plugin for all supported producer/factory
modules and downstream users is a build/distribution compatibility requirement.
Do not make `api` depend on a plugin that itself depends on compiled `api`.

## Approved scope and precise remaining boundary decision

The approved lightweight hierarchy gate expressly requests pinned constructor
and separate-compilation prototypes and permits necessary frontend/lifecycle
hooks. It covers this experiment; it is not evidence that the experiment works.
The gate does not explicitly specify a mandatory compiler plugin or support for
prebuilt factories lacking instrumentation. This probe makes that distinction
observable: instrumented non-inline factory JARs are supported by the proposed
protocol; an uninstrumented factory is rejected.

Before production integration, document/review the producer plugin requirement,
versioned runtime/metadata ABI and migration/distribution behavior in the same
existing gate or an applicable versioned supplement. If production module
manifests or architecture-enforcement/core-library boundary rules change,
CONTRIBUTING requires the applicable approved design gate in that PR. Do not
silently reinterpret the existing separate-compilation requirement as support
for all arbitrary prebuilt factory binaries.

General nonliteral constructor defaults, independent target witnesses and the
rest of the public API contract remain open. No roadmap checkbox, acceptance
receipt, source/output witness or historical gate is changed here.
