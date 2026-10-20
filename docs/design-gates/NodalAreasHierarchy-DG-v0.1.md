# NodalAreasHierarchy-DG v0.1 — bounded lexical Area checkpoint

**Status:** Approved
**Scope:** public-api
**Compiler:** Scala 3.8.4, pinned JDK 25.0.2
**Implementation status:** Lexical checkpoint validated locally on 2026-10-20;
required generated/conditional descendants remain open.

## Authority and boundary

The owner's standing instruction to implement all unchecked Foundation increments
and the approved [Area amendment](../roadmap/areas-symbolic-conditionals-v0.1-plan.md)
authorize this necessary additive F58 contract. This does not assert separate owner
selection of each signature, executed evidence, or completion of F-058.A.1,
F-058.B.2.2, or any parent.

This first checkpoint implements ordinary Module-scope lexical Areas, including
anonymous, nested, helper-returned and named reusable subclasses. Generated Area
collections, conditional allocation, guarded member existence and generated child
references remain required open work. The current callback `hdlRange` signature,
host-loop staging and explicit forms are unchanged. F58 retains the required
minimum `for ... hdlRange ... yield new Area` collection, static index and member
projection integration. F159 owns the later general mixed-effect partitioning
and fusion, not a waiver or transfer of the required F58 collection work.

## Exact additive source contract

`abstract class Area extends Selectable` admits `new Area { ... }` and ordinary subclasses. It does
not extend Module or Data. It adds no port boundary, process, clock, state or
latency. Hardware keeps its enclosing Module and declaration-time domain.

An Area exposes the existing construction operations as protected helpers:

```scala
protected final def wire[A <: Data](dataType: DataType[A]): Signal[A]
protected final def instance[M <: Module](module: M): Instance[M]
protected final def connect[A <: Data](left: Signal[A], right: Signal[A]): Unit
```

These delegate directly to existing construction owners. Registers, expressions,
domains and supported behavioral guards retain their existing public forms.
No user-authored wrapper, export list or result/view class is needed.

Hardware members are immutable eager Scala vals. Direct legal access retains the
actual capability and all existing ownership, writable-target, driver and domain
checks. An exposed child member does not authorize access to a child's internals.
Area references are not serializable hardware payloads or runtime selectors.
The bounded name profile accepts unambiguous ASCII Scala identifiers.
The final `selectDynamic(name: String): Any` implements Scala's refined anonymous
member typing by looking up the exact compiler-captured value in the current
construction session. It does not use `reflectiveSelectable`, reflection, getter
execution, hardware serialization or emitted-name lookup. Ordinary eager host
vals may also be retrieved; they do not become hardware declarations. Anonymous
member methods and mutable/lazy host fields are outside this initial projection
profile; named subclass methods retain ordinary Scala dispatch.

The compiler captures allocations and actual hardware member binders, including
private members, without evaluating member getters by reflection. One allocation
executes once in ordinary host evaluation order. Arguments precede Area entry.
Failed construction poisons the same transaction; an ignored exception cannot
publish partially captured hardware. A missing plugin or incompatible producer
class fails closed. Separate producer/consumer compilation retains a versioned
compiler-owned Area schema and injected member captures.

Field binders outrank construction-local binders, which outrank helper-local
binders. The first binder at the same priority remains canonical, so repeated
helper calls acquire distinct caller names and aliases do not rename storage.
Arguments, including named-argument temporaries, share the existing constructor
failure guard and retain ordinary Scala evaluation order.

Ordinary symbolic `if` in an Area uses the existing exact predicate grammar,
read-only value joins and shared effect checker. This checkpoint does not admit
Area or child allocation inside either symbolic branch, or relax behavioral
creation restrictions. Host Boolean if executes only its selected branch.
Callback member access admits Scala's inserted structural cast only for the exact
final Area lookup, a literal statically known refined member and the identical
refined result type. General casts, readonly-to-writable casts and incompatible
datatype casts do not acquire authority.

## Ownership and compatibility

ConstructionSession owns the Area stack and object records alongside existing
declarations, instances and domains. Immutable Area snapshots retain logical
member paths pointing to existing canonical hardware identities. The existing
SemanticOriginBuilder consumes exact lexical binders for naming; it is not a
second ownership registry. Emitted names do not establish access permission.
Area domain metadata reuses the same local-domain candidate and declaration
resolution as hardware members. An inherited parent clock is represented by the
child-local formal requirement, not by a foreign declaration path. Unscoped
default-bound state uses its existing resolved local declaration domain.

The compiler phase supplies only lifecycle/member capture. StructuralIfCapture
and DigitalCollectionCallbackPhase remain the only predicate/effect-validation
owners. Their closed profiles, summary protocol and budgets are not duplicated.
The new producer metadata requires rebuilding Area producer classes with the
matching plugin; existing non-Area APIs and compiled consumers are unchanged.

Reject Area construction outside a Module, inside generated/value/behavioral/
analog contexts not enabled by this checkpoint, foreign/detached references,
mutable/lazy hardware members and authored compiler metadata. Preserve transaction
integrity and finite count/depth/member limits. Do not reinterpret Scala visibility
as a hardware capability or silently turn unsupported allocation into a host value.

Alternatives rejected: Area as Module; a parallel mutable construction engine;
reflection-only member inference; mandatory scope/export/view syntax; cloning
generated templates by defaults or representative indices; incidental declaration
kinds selecting loop staging.

## Focused evidence required

Compile exact anonymous, nested, reusable, helper-returned and separate-library
forms. Elaborate actual wires/registers and existing child instances, preserving
one enclosing Module, actual inherited/explicit domains and typed member identity.
Check legal external writes and rejection of foreign or internal child access.
Check callback/branch creation rejection, mutable/lazy members, missing/forged
producer metadata and plugin-absent allocation, ordinary host evaluation order,
failure cleanup, repeated construction and bounded depth.

These are source/construction checkpoint checks, not native Area ingress or target
generation certification. Required bridge/native identity transport and generated
Area/conditional target witnesses remain with the open F58 children. No CI, full
qualification, or later F65–67 parent completion is a prerequisite.

### Executed lexical checkpoint

The matched source uses Scala 3.8.4 and the verified cached Zulu JDK 25.0.2+10-LTS
executable, not the separately installed default JDK 25.0.4.

- `core.scala.testkit.test.testOnly nodal.internal.testkit.Increment58AreaTests`:
  6/6, including actual source/bridge construction and inherited, default and
  named child-local formal domain identity.
- `tests/scala/areas/run.py`: exact source/phase checks and actual runtime pass;
  seven negative source fixtures, separate producer/factory JAR inheritance and
  helper/member access, missing producer metadata and plugin-absent rejection.
  Runtime checks include positional/named argument order and failure poisoning,
  body failure cleanup, repeated local-helper naming, bounded depth, foreign
  transactions and behavioral member writes. Readonly and datatype cast
  negatives are independently required.
- `Increment58AreaReplay` followed by `tests/scala/areas/run_native.py`:
  two actual lexical source snapshots accepted and duplicate member drivers
  rejected by the existing native driver owner with `NODAL-DIGITAL-055-010`.
  The frozen F57 compiler SHA-256 is
  `2b0121c941d1299927b4e770dd1937d4c6072b7a2efc5bd42273f5f01ede9dcd`.
- The external-write replay passed the existing default gate and sequential
  CIRCT lowering, then actual SystemVerilog export with firtool 1.154.0.
  This witnesses reuse of ordinary storage/target semantics, not new native
  Area-family transport.

Mill invocations used the shared `.work/f58-mill.lock`. Generated logs and outputs
remain ignored under `.work/f58-area-local-domain-final.log`,
`.work/f58-areas/`, `.work/f58-area-native-checks/` and
`.work/f58-area-native/`. No CI or full qualification ran.

The actual maintained source in `Increment58AreaSource.scala` includes:

```scala
val group = new Area:
  val target = wire(Bits(8))
group.target := input
output := group.target
```

The generated external-write SystemVerilog contains:

```verilog
wire [7:0] group_target = input_0;
assign output_0 = group_target;
```

This is one enclosing module and one existing wire with its Area-derived name,
not an added module boundary, process or cycle of latency.

## Next required generated-family integration

This is a design boundary for remaining F58 work, not an implemented feature or
an approval of unprototyped collection signatures. The canonical family remains
`GeneratedRegionRecord` and its existing region/induction identity. An Area
collection captures one typed Area template and its member identities under
that family; it must not materialize representative lanes or clone an Area per
parameter default.

Static member access must retain the family, exact structural index, original
declaration or immediate child-instance identity, lexical member path and branch
existence proof. A bare selected Area is not a hardware payload or runtime
selector. The exact public collection and projection syntax must be pinned
compile-tested before extending this gate's implemented API contract.

InterfaceArray and child-boundary projections use the same root endpoint,
ordered shape/index tokens and generated/branch proof. Role orientation remains
owned by the existing Interface contract; crossing a child boundary changes
orientation, not physical endpoint identity. Native hierarchy consumes the shared
iteration-image proof and emits a real generated instance family rather than
concrete occurrence expansion.

Required follow-on owners are the existing structural-bound/Shape and generated
region models, bridge hierarchy/digital ingress, and native hierarchy/target
lowering. There is no new range engine, role classifier, per-value family registry
or emitted-name reconstruction. Conditional existence, generated state/drivers
and the minimum lane-yield/static-member witness remain required F58 work;
general mixed-effect partitioning and fusion remain F159.

## Bounded architecture review

The implemented lexical path adds only session-owned Area records and an exact
compiler capture phase. The existing callback validator owns effects, including
ordinary source and separately compiled helpers. Finite limits are 128 nested
Areas, 65,536 Areas per transaction and 65,536 captured members per Area.
Name preparation uses per-Area ownership sets rather than a member-by-declaration
quadratic scan. Construction and snapshot work are linear in captured entries
apart from the bounded lexical-depth factor; this is a complexity review, not a
measured performance improvement.
