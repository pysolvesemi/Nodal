# NodalGeneratedAreas-DG v0.1 — retained Area families

**Status:** Approved
**Scope:** public-api
**Compiler:** Scala 3.8.4, JDK 25.0.2
**Implementation status:** Bounded source capture, direct-storage transport and
native default-gate admission have focused evidence. Shared-source integration
and actual generated target admission remain under implementation.

## Authority and selected boundary

This additive gate extends the approved [lexical Area gate](NodalAreasHierarchy-DG-v0.1.md)
and implements the required generated collection witness in
[F58 B.2.2](../roadmap/areas-symbolic-conditionals-v0.1-plan.md).
F58 retains ordinary symbolic-if, behavioral updates, generated storage and static
member projection. F159 owns general mixed-effect partitioning and fusion, not
these required minimum semantics. Remaining F58 children stay open.

## Exact source contract

Existing callback `hdlRange` signatures and their default getter remain unchanged.
Explicit two-, three- and four-argument overloads return `HdlRange`, with the same
`Int | Expr[Integer]` bounds and optional explicit maximum. The `HdlRange.apply`
extension and `foreach` retain the existing checked callback owner. The extension
adapter, rather than a class-member apply, preserves all two/three/four-argument
colon callbacks without ambiguity or changing the old callback JVM entries.

```scala
final class HdlRange:
  def map[A <: Area](body: Expr[Integer] => A): GeneratedAreas[A]
final class GeneratedAreas[A <: Area]:
  def apply(ordinal: Int | Expr[Integer]): GeneratedAreaRef[A]
final class GeneratedAreaRef[A <: Area] extends Dynamic:
  transparent inline def selectDynamic(inline name: String): Any
```

Constructors are private. A map captures one checked symbolic template, including
an empty range. It does not enumerate Scala objects for lane values. A selected
reference is not Area, Module, Data or a runtime selector. It can be saved in an
immutable val and passed through typed helpers, including separate producer JARs.
The existing quotes layer resolves exact eager named/refined member types. Nested
Areas extend the logical member path; terminal storage retains its exact
Expr/Signal/Register type. No reflection, getter invocation, representative Area,
user-authored wrapper or extra module is introduced.

`apply(k)` is the zero-based collection ordinal. The existing iteration owner
proves its existence for every legal parameter setting. The canonical generated
domain alone derives `lower + k * step`; transport must not introduce a second
authoritative coordinate. The first source profile retains its existing positive
step rule and conservatively requires a homogeneous direct export. Empty-family
capture is legal but selection without existence proof is rejected.

## Canonical ownership and capability

ConstructionSession and GeneratedRegionRecord own the actual family, body and
handle. Existing declaration/expression registries remain authoritative. Area
member capture version 2 records static access, because an Expr field can hold a
writable object without granting writable access. Private/protected and host-typed
members do not enter the export inventory; a public typed alias may still expose
the same actual private storage root. Nested lookup independently requires the
public Area mode, and every selected use rechecks exact ordinal existence.
Version 1 remains valid for
ordinary lexical Areas but cannot silently supply generated export authority.

An immutable export points at an existing actual storage declaration and logical
member key. A projection records actual family, exact ordinal, key and monotonic
access. Its type/domain come from the existing declaration. Private nondeclaring
Signal/Register construction aliases one canonical expression identity; it adds no
storage declaration. The shared dependency sampler follows that actual storage
edge, retaining domain and source provenance. Read-only exports never become
writable because their runtime object happens to be a Register.

The shared digital lexical-region owner admits declarations only in the actual
Area-family Generate. Ordinary Module/Area and generated structural arms may
contain behavioral updates through exact owned ancestry. An intervening pure
collection or procedural loop remains unavailable. Structural-if and behavioral
callbacks retain their existing closed profiles. Distinct register update roots may coexist only when their actual
structural-arm ancestry contains opposite arms of the same conditional identity.
Unrelated or overlapping roots still reject. This bounded proof uses existing
region records, not default predicate evaluation or a second effect engine.

## Native contract and staged admission

The bounded native stage owns a parameterless `!nodal.generated_family`, an optional
single `nodal.generate` result, closed nested `nodal.generated_export` records and
`nodal.generated_member` with static ordinal and explicit monotonic access. Storage
exports retain exact SSA operands. Export and member operations carry `member`,
`access` and `metadata`; access is `read`, `wire` or `register`, with narrowing
only to read. The source attestation and actual declaration kind provide that
transport, not a new writable flag or duplicated type authority.
Later symbol exports must name actual owned
Instance/Interface declarations through a verified structural carrier, not fake
SSA or emitted-name lookup. Native independently verifies owner, type, capability,
ordinal, existence and driver legality through Shape/GeneratedDomain.

The bridge transports supported direct families and fails closed for a retained
family without an admitted digital body; it never discards export/member facts.
Actual target admission requires one `sv.generate.for`, a shared
body/storage profile, inherited clock/reset and actual static member selection.
The source million-lane metadata witness establishes one source template only;
it does not establish target resource admission or emitted scale.

Guarded/nested-family exports, symbol endpoints, generated child capture and
ordinal-aware external writes remain required follow-on F58 work. The first source
slice rejects unsupported external writes through selected members rather than
bypassing the canonical driver owner. No required work is transferred to F159.

## Generated target contract (direct fixed-scalar checkpoint)

The production CIRCT target consumes verified direct, nonempty, zero-based,
unit-stride families with fixed scalar storage leaves. It emits one reusable
helper module per actual represented family and material module variant, and one
real `sv.generate.for` containing its `hw.instance`. It does not enumerate legal
lane counts, clone bodies for parameter values, create source Modules, or add a
second hierarchy/domain registry. Helper keys retain actual operation identity;
source-template budgets and physical target-footprint admission remain separate.

The existing logical-port plan and physical adapters transport actual outer SSA
captures, including real clock/reset capabilities. Scalar expressions and clocked
storage use the same emitters as ordinary modules. The canonical normalized
StructuralIf arms already contain priority, enable and hold behavior: target
emission only retains their exact equality/inequality elaboration choice using
`sv.generate.case` at helper-module scope. A single actual register consumes the
selected next-value wire. No target-inferred missing arm, initial value, reset
policy or representative-lane domain is substituted.

Direct output buses require ShapeModel's exact complete iteration partition.
Exports and selected reads retain the actual storage and existing ordinal proof;
no selected member creates state, an instance or a resolved net. Conditional
output writes, nested/guarded families, selected family views, generated instances
and symbols, nonunit strides, symbolic scalar state widths and resolved/analog
endpoints remain explicitly unsupported by this first target profile.

The requested concrete-loop preservation behavior is exposed as
`--nodal-sequential-to-circt=preserve-constant-loops=true`. Eligible concrete
families remain real HDL loops with that option; without it the bounded profile
refuses them instead of silently unrolling. Symbolic loops remain parameterized
independently. `nodal.preserve_constant_loops` and `nodal.boundary_abi` retain the
chosen target configuration. This does not claim a new Scala configuration API.

### Pinned consumers and exact adapters

CIRCT firtool 1.154.0 exports the real helper/loop structure. Icarus 12 rejects its
legal named outer generate end-label, so the maintained executable witness uses
CIRCT-Slang and the installed arcilator on the **unchanged emitted SystemVerilog**.
This is a two-state behavioral check, not startup-X or four-state certification.
The earlier primitive probes remain historical feasibility evidence under
`.work/f58-generate-target-probe/`; they are not substituted for production output.

The pinned verifier cannot represent a genvar as a module `ParamDeclRefAttr`.
The helper's index actual is therefore a typed `ParamVerbatimAttr` containing only
the generated collision-checked induction identifier associated with that exact
loop block argument. Authored names, arbitrary expressions and metadata never
enter this text carrier. Other arithmetic uses the existing signed-i32 checked
parameter DAG, preserving the mathematical-i64 source proof without truncation.

The pinned indexed-part-select operation does not accept symbolic `hw.int`
carriers. A narrow typed `sv.verbatim.expr` adapter uses only a fixed compiler
format, actual SSA carrier/index and verified literal scalar stride. In particular,
output connection selectors retain the actual genvar directly: a `comb.mul`
runtime wire is not an elaboration constant. No source expression string or
sampled bound is substituted. This shared adapter preserves the row-major,
element-zero-at-LSB mapping and the selected-member inverse mapping.

Emitted parameter guards retain canonical range and exclusion constraints.
For the maintained illegal `LANES=9` override, Slang retains the exact range-check
`$fatal` as reachable `sim.terminate failure`; pinned arcilator refuses that path
rather than generating a simulator. The runner checks this exact reason, not any
arbitrary tool failure, and does not label the negative as a successful simulation.

## Qualified public lane witness and staged evidence

The maintained public [lane witnesses](../../core/scala/testkit/test/src/nodal_external/Increment58GeneratedLaneRegisters.scala)
use existing pinned-Scala spellings rather than adding another initializer API:
`Reg(0.U(8).extract(0, 8))` has exact `Bits(8)` storage, `enable.at(i)` selects a
per-lane Bool, and `dataOut.writable.at(i) := stored` retains the shaped output.
The symbolic witness declares `LANES` with the legal envelope 1 through 8; the
second witness uses concrete bound 3. Both retain the required lightweight
`for ... hdlRange ... yield new Area`, ordinary structural `if`, behavioral
`when`, lane-zero inversion, other-lane copying and `lanes(0).stored` alias.
The roadmap's proposed `Reg(Bits(8)) init 0` spelling is not claimed as an
implemented API.

On 2026-10-20, strict Scala 3.8.4/JDK 25.0.2 compilation of the maintained source,
tests and [six-case replay](../../core/scala/testkit/test/src/nodal/internal/testkit/Increment58GeneratedAreaReplay.scala)
against frozen checkpoint-7 classes plus the exact scope/bridge overlays passed.
All six actual replay documents passed `--nodal-gate-default` on frozen native
compiler SHA-256
`01059e71197a1ef88b7e673a76d4479b5631a309cf6b813d1788c7c952425f8b`.
They cover the original lane composition, ordinary Module/Area structural updates,
readonly selection, guarded nested callback captures, and both exact lane witnesses.
Commands and outputs remain under `.work/f58-generated-areas/maintained-third/`.
Subsequently, the actual shared-source `Increment58GeneratedAreaTests` passed all
14 groups, and the maintained six-case replay from those shared classes passed
the full default native gate with compiler
`ef01aec403d9c0db760c7efa90ae71f22ad8c352ab4144a726dca29d7fc2460d`.
The source/compiler hashes and all six exit statuses are recorded under
`.work/f58-generated-areas/shared-checkpoint/receipt.json`; the exact Bits symbolic
and concrete-three input hashes are unchanged. Neither admission result claims
actual emitted target structure or waveform behavior.

The first direct-family production target subsequently passed the maintained
[Area target runner](../../tests/scala/areas/run_target.py) on compiler SHA-256
`83d54e05ab033664162048cd1071fea8a3b0a09db1c710447355179c0092aa53`.
Both exact replays passed the full default gate, target lowering and firtool export.
Unchanged SV passed Slang/arcilator for symbolic lane counts 1, 3 and 8 and concrete
3. Actual state-map-derived C++ stimulus checked per-lane enables, old-state hold,
lane-zero inversion, other-lane copy, reset dominance, flat output and firstLane.
The same output rejected the out-of-envelope 9-lane simulation for its retained
range fatal. Exactly one helper and one real loop remain in each emitted design.
Commands, all tool/input/output hashes and two-state results are recorded under
`.work/f58-generated-target/third/waveforms/manifest.json`. Five actual concrete
hierarchy predecessors also passed their existing Icarus waveforms on that compiler,
including opposite clock edge/reset polarity and resetless state. See
[consumer instructions](../../tests/scala/areas/TARGET.md) for reproduction and
limitations. The remaining guarded/nested/generated-symbol target requirements
remain open; this direct storage checkpoint is not closure of F58.B.2.

## Focused validation and architecture review

Focused source probes cover one-template capture, required if/when lane updates,
saved/nested references and separate producer/factory/consumer JARs. Boundary cases
include nonzero lower/step with ordinal zero, empty capture, negative/upper/empty
selection, runtime selectors, default-only bounds and forged readonly expansion.
Compile negatives cover inaccessible/method/host members, readonly assignment,
datatype changes, treating a selected reference as hardware payload and behavioral
updates inside pure collection/procedural-loop ancestry. Ordinary Module and
nested Area if/when use the same structural exclusivity proof as generated lanes. Native
mutation and actual generated-SV tests remain required and unclaimed here.

The selected design reuses quotes, callback summaries, lexical capture, structural
bounds and canonical registries. Compared with a new compiler-only member phase,
the typed reference supports saved aliases and helpers without a representative
Area. Construction work is independent of lane cardinality; export traversal and
structural proof have explicit finite budgets. No per-lane cloning, warning
suppression, duplicate ownership engine, CI or full qualification is introduced.

## Selected nested family views (N1b bounded extension)

The approved selected-family extension reuses `GeneratedAreas[A]` as an immutable
view of one actual nested template plus an ordered outer selection prefix.
`GeneratedAreaRef` adds only the narrow transparent source adapter:

```scala
transparent inline def applyDynamic(inline name: String)(ordinal: Int | Expr[Integer]): Any
```

It admits an exact literal eager `GeneratedAreas` member with its declared element
type, allowing `outer(0).inner(1).stored`. Saved family views, named/refined members,
ordinary nested Area paths and separate producer/helper JARs use the same path.
No member getter, reflection, representative lane or per-value construction is
introduced. The runtime rechecks every original family, direct actual nested
export, ordinal and continuous prefix. Bare inner handles cannot escape their
lexical scope, and selected views cannot be re-exported as original families.

Area capture version3 adds the closed family member mode. Private/protected and
host fields remain excluded; a public typed alias can expose the same actual
private family. Imported classes or bases with public family fields require
version3; older ordinary v1/v2 profiles retain their existing admission. Missing
family metadata never becomes export authority merely from the runtime object.

The existing export/member transport gains an optional closed `kind = "family"`.
Absent kind remains storage. A family export has read access and consumes the
exact actual directly nested Generate SSA handle. A family member projection
consumes the checked dominating family handle and its static ordinal, returns the
existing non-Data generated-family type, and retains the exact actual producer
chain. Native derives each coordinate through its existing GeneratedDomain owner.
The terminal storage projection retains its independent exact read/wire/register
capability, payload, domain and physical storage root. Read-only family selection
is not authority to write selected storage.

Source snapshots reuse the existing ordered export inventory and add an ordered
prefix of actual family, exact ordinal use and member key to the existing terminal
member snapshot. Every ordinal is rebound through the existing lexical capture
owner. The bridge proves the first handle and all ordinal dependencies dominate
the selected projection; isolated callbacks capture only the resulting typed Data,
never the family capability. The native storage/shape owner independently resolves
the bounded actual SSA chain, with no name lookup or duplicate occurrence registry.

This extension covers independently bounded positive-step families. The existing
approved positive-step rule is retained: descending source ranges reject in the
canonical IterationDomain owner, rather than being remapped or sampled. Guarded
family declarations, guard-dependent existence, outer-induction-dependent inner
bounds, generated Instance/Interface carriers, ordinal-aware external writes and
actual selected-family target lowering remain explicit required subsequent work.
The source/bridge/native default gate evidence does not establish these profiles
or target resource/waveform acceptance.

The representation grows with authored family templates, exports and selected
paths, never with lane count. Prefix validation and immutable append are bounded
by the existing 128-depth and 65536-inventory limits; straightforward repeated
prefix validation can be quadratic in authored depth. No unmeasured performance
improvement or universal target scale is claimed.

## N2a: guarded generated declarations with independent bounds

This additive source/transport slice permits an actual generated Area family inside an ordinary static `if` arm, including an enclosing generated body. The family's full independent parameter envelope must still prove the selected ordinal exists. Its body alone may own fresh storage; the arm itself does not gain ordinary `Reg`, `Wire`, Module, legacy mixed `foreach`, pure-collection or procedural-loop declaration authority.

Ownership remains singular. `KernelGeneratedRegionSnapshot.parent` is the nearest actual Generate. `KernelDigitalGeneratedBody.parentRegion` records the actual immediate lexical arm/body, with `lexicalParent` preserving old direct-family records. Captured bounds, scheduling and projections consume this same edge. Guarded family paths are derived from the actual arm path, while direct legacy family paths are unchanged. Existing `StructuralParameterBinding.Query` now exposes the same actual arm capture substitution to both bridge consumers. The bridge verifies the bounded complete lexical ancestry and nearest generated ancestor independently; a non-Data handle never crosses an isolated callback as Data.

The existing compiler effect-summary protocol gains exact `StructuralFamily` profile128, separate from existing non-allocation structural profile8 and immutable-FSM profile64. Only actual closed `HdlRange` returning factories, `map`, compiler-owned bind/attestation and independently checked generated bodies gain this authority. A separately compiled closed helper returning `GeneratedAreas[A]` uses the same signature/dependency certificate, not reflection or a new checker. Pure, procedural Loop and Sequential consumers reject128; forged annotations and unknown256 are rejected. Reusing8 was rejected because it would expand old writable-loop consumers' declaration authority.

Focused scratch proof before promotion used actual Scala3.8.4/JDK25.0.2 strict compilation: seven maintained draft groups, four actual replay profiles, a separate producer/helper JAR, four direct effect negatives and five imported effect/metadata negatives. All four replays and the separate-JAR actual MLIR passed `--nodal-gate-default` on frozen compiler83d54e05. This is focused prototype evidence, not target execution or full qualification. Shared-source acceptance is recorded separately after integration.

Known remaining scope stays open: predicate-dependent ordinal existence such as count0..8 with count>0/select0 (N2b), guarded family export/merge/escape, generated Instance/Interface carriers, external selected writes, and remaining target coverage. These are not transferred to F159 general mixed-effect partitioning. Canonical queries are cached per lexical scope and traverse bounded actual templates/captures; no lane enumeration or per-lane source registry is introduced. The added conditional-arm lookup is one immutable snapshot index shared with child Query contexts, not repeated all-arm scans.
