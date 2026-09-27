# Constructor capture compiler and production ABI probes

**Status: isolated probe executed successfully; production integration is present
in the current candidate but remains unqualified and does not complete B.1.** At
commit `d3764318a1f332e3fc216969e47fda710287e7a2`,
[Core Scala job 108443599578](https://github.com/pysolvesemi/Nodal/actions/runs/36256292650/job/108443599578)
compiled the probe with Scala 3.8.4 and passed 11 runtime cases with 169
assertions, all six expected compile rejections, and the uninstrumented-factory
runtime rejection. This records that exact source revision's isolated result;
it does not qualify the current head or complete production F-042.B.1.1. The
current harness retains that historical protocol and additionally compiles the
real API/plugin definitions, factory and consumer in separate compiler processes.
Source review uses Scala 3.8.4 commit
`7d4833b619d31ea9acac97fccf1e74b42b89c49f`; the Nodal review base is
`315905bde74f23997ce1c03fe5bc3a7569a00b0d`.

This directory provides bounded compiler evidence for F-042.B.1.1, not completion
of that item. The
approved lightweight hierarchy gate requires constructor lifecycle/default,
factory, separate-compilation, evaluation and failure evidence. This experiment
has executable fixtures for the isolated protocol and the production ABI. The
production API owns the transaction semantics; this harness does not establish
IR, HDL, full static-effect/unit coverage or final qualification.

## Run through the existing toolchain owner

From a Nodal checkout:

```bash
./nodal core scala
```

The shared CLI invokes the harness after the existing Scala compile/test
commands. Each invocation creates a fresh evidence directory under
`.validation/constructor-capture/`; it preserves prior evidence. `./nodal check`
reaches the same command. Core CI needs no private execution sequence.

The harness asks the existing Mill module for
`core.scala.api.scalaCompilerClasspath`, verifies `dotty.tools.dotc.Main
-version` is exactly 3.8.4, and invokes that existing compiler classpath. The
isolated protocol uses existing `java` and `jar`; production runtime assertions
use Mill's managed Java 25 from the repository's pinned `mill-jvm-version`. The
harness has no alternative downloader, token, proxy, new build system, or remote
write. `--compiler-classpath` accepts an explicit
already-available classpath for controlled runs. A nonempty output directory is
rejected so evidence cannot be overwritten silently.

`--inventory-only` writes a source SHA-256 inventory without starting Java,
Mill or Scala. An inventory-only result is explicitly `not-run`, never a pass.

The first phase creates independent prototype runtime and plugin JARs, then compiles and
JARs module definitions, a non-inline helper factory, and a downstream consumer
in separate compiler processes. It does not place definitions or factory source
on the consumer compiler command. `-Ycheck:all` checks generated trees throughout
the compiler; `-Vprint:nodalConstructorCapture` retains the transformed trees.
The harness also compiles raw producers without the plugin to test the boundary.
The second phase obtains `core.scala.api.jar` and
`core.scala.constructorPlugin.jar` from their existing Mill owners. It compiles
production definitions, a non-inline factory and a downstream consumer as three
separate JAR/compiler stages with the production plugin, `-Ycheck:all`, strict
warnings and retained transformed trees. It also compiles a factory without the
plugin and requires the downstream runtime boundary to reject it with
`NODAL-CONSTRUCTOR-MISSING-016`.

The final manifest records every exact command, exit code, log hash, input source
hash and compiler/artifact JAR hash. It also records actual Git HEAD, tree and
worktree status. Attempts are persisted before execution; timeouts preserve
partial logs and fail. Failure logs print a bounded excerpt to CI stdout/stderr.
The runtime must report exactly 11 cases and a positive assertion count. Expected
negative compiles require exit 1, the exact diagnostic code and no compiler crash
marker. Missing sentinels and unexpectedly compiling negative cases fail.

## What the source implements

The syntax in the definitions fixture is exactly:

```scala
class GainStage(gain: Param[Real] = 2.0) extends Module:
  val observedGain = gain

class Top(topGain: Param[Real] = 4.0) extends Module:
  val amp = new GainStage(gain = topGain)
```

The names above live under `nodal.prototype`; they remain an isolated reference
protocol, not replacements for the real public Nodal API. Production fixtures
under `nodal.constructor.separate` use the actual `nodal.Module`, `Param`,
`ConstructionKernel`, compiler plugin and canonical construction transaction.

1. One `StandardPlugin` phase runs after posttyper and before TASTy pickling.
   It scans all already-typed units first, obtains actual companion default
   getter symbols using the compiler's canonical `DefaultGetterName`, checks
   `HasDefault` and exact ownership, and validates the original getter body.
2. For the partial literal profile, it persists version-1 class metadata with
   ordered parameter names and exact Double bits. It rewrites each approved
   literal getter body to an inert omitted marker. The original getter call and
   the compiler's named-argument temporaries stay in place: omitted default once,
   explicit actual no getter, no speculative/default getter reevaluation.
3. A typed `new` evaluates original arguments once before allocation begins,
   creates fresh unbound carriers inside the allocation boundary, then passes
   those carriers to the original constructor. The parent actual reference and
   independent declaration default are separate fields.
4. `Module.begin` binds carriers after entering the base constructor. Scala
   3.8.4's `Constructors.scala` copies retained parameters **before** the super
   call. Those copies only carry inert references; they must never declare
   parameters. Body assertions check that binding exists before user body code.
5. A small allocation trace records begin/commit/rollback and reference identity.
   Nested records remain staged until an outer allocation succeeds. The trace
   has no expression semantics, topology registry, IR, native bridge or emitter.

Eleven positive runtime cases assert inert lifting; exact constructor spelling;
fresh child declarations/default independence; omitted versus explicitly equal
actual; named argument source order/count with distinct parent identities; default
getter invocation counts (omitted once, explicit zero); top-level construction; local and
separately compiled non-inline factories; nested ownership; argument/body failure;
caught child failure and fresh construction recovery; and isolated observation.

Six compile-negative groups require exact diagnostic codes for effectful
defaults, curried constructors, generic modules, secondary constructors, indirect
Module inheritance and missing separately compiled metadata. A separately
compiled factory without the plugin must fail at runtime without publishing any
allocation. The latter establishes a distribution requirement, not support for
uninstrumented third-party factory binaries.

## Explicit partial profile and unresolved requirements

The experiment accepts only named top-level direct Module subclasses with one
ordinary primary parameter list whose members are `Param[Real]` and whose
constructor defaults are finite Double literals through the exact pure
`Param.liftReal` method. Zero-argument Module classes are included. Other host
parameter types, general/default expressions, contextual/erased/by-name/repeated
parameters, inheritance, type parameters and secondary constructors are outside
this compile profile and are not waived from the approved feature.

General host defaults remain an explicit requirement. In particular, invoking
an effectful getter solely to recover the child declaration default would
violate the approved evaluation contract when an explicit actual is supplied.
This prototype neither invokes it twice nor accepts that shortcut; it rejects
such definitions with `NODAL-CTOR-PROTOTYPE-DEFAULT`.

The pre-pickler phase does not cover allocations first introduced by later
macros/compiler transforms or suspended compilation units. Ordinary producer
source, including non-inline factory source, must be compiled with the plugin.
Metadata is checked for exact marker identity, unique occurrence, argument
arity/kinds/version and the supported constructor signature. Metadata does not
replace independent ownership/type/effect/native semantic validation.

The production candidate implements literal lifting, source/instance identity,
automatic canonical attachment, root and child actual separation, explicit-form
parity, ownership checks and transaction invalidation after partial construction.
The public integration suite supplies the corresponding frontend regressions.
General defaults, fixed replication, the B.2/B.3 lowering/output path, downstream
witnesses and final qualification remain required.

All new Scala sources participate in existing Scalafmt 3.11.5 and Scalafix 0.14.7
checks through a sources-only Mill inventory and the existing syntactic lint
path list. The trace retains intentional failures using the repository's
`scala.util.Failure` and its `get` method; no rule is suppressed.
The historical `d3764318` run initially exposed formatting changes; its successor
applied the exact retained formatter output and later targeted checks passed.
Every new candidate still requires current-head pinned formatting, lint and
qualification; local or historical compilation cannot supply that credit.

See `INTEGRATION.md` for the exact shared CLI hook, dependency shape, production
ABI and remaining capability boundary. The pinned compiler-source review is not
execution evidence.
