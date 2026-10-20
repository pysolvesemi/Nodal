# NodalGeneratedAreas-DG v0.1 — retained Area families

**Status:** Approved
**Scope:** public-api
**Compiler:** Scala 3.8.4, JDK 25.0.2
**Implementation status:** Bounded source capture under implementation; native
transport, generated drivers and target admission are not complete.

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

The next native stage owns a parameterless `!nodal.generated_family`, an optional
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

The bridge must fail closed until this transport exists, never discard new export
or member facts. Actual target admission requires one `sv.generate.for`, a shared
body/storage profile, inherited clock/reset and actual static member selection.
The source million-lane metadata witness establishes one source template only;
it does not establish target resource admission or emitted scale.

Guarded/nested-family exports, symbol endpoints, generated child capture and
ordinal-aware external writes remain required follow-on F58 work. The first source
slice rejects unsupported external writes through selected members rather than
bypassing the canonical driver owner. No required work is transferred to F159.

## Pinned target execution contract (implementation in progress)

The installed CIRCT firtool 1.154.0 probe admits `sv.generate` containing an
actual `sv.generate.for` and `hw.instance`. It emits one parameterized HDL loop,
not instances cloned for legal values. Installed CIRCT-Slang accepts that output.
Icarus 12 rejects the legal named outer generate end-label, so this profile uses
Slang for legal-output admission until a compatible simulator is selected; output
must not be rewritten to satisfy a consumer. This is a pinned-consumer limitation,
not a claim of source or target completion.

A second probe establishes that nesting a fresh `sv.generate`/`sv.generate.case`
inside the loop emits illegal nested generate regions, rejected by Slang. The
bounded target plan therefore uses one reusable helper module per represented
family template and material domain profile, with static choices at helper-module
scope. The enclosing real loop instantiates that helper. This is a target adapter,
not a new source Module, hierarchy occurrence registry or body per lane. Existing
hierarchy/parameter authorities supply the exact template and finalized context;
represented-template budgets and target physical-footprint limits remain separate.

Helper inputs explicitly transport the actual outer SSA captures and physical
clock/reset capabilities. A static coordinate parameter transports the canonical
`lower + ordinal * step` coordinate. The pinned HW instance verifier cannot use a
`ParamDeclRefAttr` for a generate induction, because it is not a module parameter.
The successful probe uses a typed `ParamVerbatimAttr` containing only the generated,
collision-checked target induction identifier associated with the exact loop block
argument. Authored names, arbitrary expressions and metadata do not enter this
text carrier. Other arithmetic remains the existing checked parameter DAG with
signed-i32 target transport admission.

Exports and selected-member reads must share one physical family storage/wire
mapping and its canonical ordinal/coordinate proof. No selected member creates
state, an instance or a new resolved net. Helper/module/port adapters retain the
original family, declaration and source paths as provenance while target names
remain implementation detail. Domains and reset policies are resolved from the
actual occurrence, never a representative lane or sampled parameter default.
The probes establish primitive feasibility only; Nodal family lowering, inverse
member mapping, waveform behavior and nested/generated-instance occurrence support
remain open. Probe commands, pinned hashes and results are retained under
`.work/f58-generate-target-probe/`.

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
