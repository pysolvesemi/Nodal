# NodalDigitalTypeEnum-DG v0.1

**Status:** Approved
**Scope:** public-api
**API version:** 0.1 additive to frozen core v0.3
**Decision:** Implement F54 digital type contracts and add a genuine hardware enum carrier
**Approved by:** Owner's explicit instruction to implement all unchecked Foundation increments from 43, with standing publication/integration approval

## Authority and compatibility

The owner requested: “start implementing all foundation unchecked increment items
in nodal repo from increment 43 and continue until all foundation checklists are
completed. Don't start another track just complete foundation track.” This is
scope authority for necessary F54 API implementation, not a claim that the owner
separately chose each signature. F15's frozen spellings remain authoritative over
older roadmap candidates. Existing implemented analog behavior is unchanged.

F15 explicitly froze an inert candidate surface. `DecodedEnum[A].value: A` is a
host Scala value and cannot represent runtime hardware decoding. Preserve that
case class, `EnumEncoding[A](values: Map[A, BigInt])` including Product/unapply,
`HwEnum[A]`, and the two-argument `decodeEnum` source signature. Executing the old
two-argument decode now diagnoses migration instead of returning its default.
Rejecting only during construction would permit a hoisted fake hardware value;
unconditional execution rejection closes that hole. Compile-only source fixtures
remain valid. No deprecation annotation introduces new fatal compiler warnings.

## Additive enum surface

- Invariant `EnumValue[A] <: Data` is the actual hardware carrier.
- `EnumType[A]()` returns `DataType[EnumValue[A]]` using sequential encoding;
  `EnumType.oneHot[A]()` and `EnumType.gray[A]()` select those policies.
- `EnumType.custom[A](width: Int, encoding: EnumEncoding[A])` fixes an exact width.
- `enumLiteral(enumType, case)` returns `Expr[EnumValue[A]]`.
- `decodeEnum(bits, enumType, defaultCase)` returns
  `DecodedEnum[Expr[EnumValue[A]]]` with actual dynamic value and validity.
- Enum expressions expose `asBits`, `isValid`, `isOneOf(cases*)` and same-enum
  equality `===`, returning typed expressions.
- `selectEnum(selector, invalidResult)(case -> result, ...)` is a complete typed
  expression table. Every legal case appears exactly once; results have identical
  types. Its explicit invalid result handles illegal codes, not missing cases.
  This does not introduce branch actions, state or FSM execution.

The existing `enumEncoding(entries*)` validates duplicate entries before building
its unchanged Map specification. Direct Map construction is revalidated when
resolving hardware metadata. A private immutable resolved ABI record owns policy,
width, ordered cases/codes, semantic identity and hash; no global mutable registry.

## Exact semantics and identity

`derives HwEnum` supports singleton-only native Scala enums at top level or nested
in objects. Reject payload cases, arbitrary sealed sums, generic enum declarations
and unstable method-local/path-dependent declarations. Generic consumers of an
existing `HwEnum[A]` remain supported. Use declaration-order Mirror metadata and
qualified authored declaration identity, with source spans separate from identity.
Manual empty HwEnum implementations remain source-compatible but hardware use
fails with a metadata diagnostic.

Authored identity components retain letters, digits, underscore and dollar sign;
other UTF-8 bytes are escaped as uppercase `%HH`, including literal percent and
component-internal dots. Components are then joined with dots. This distinguishes
an authored dotted name from nested declarations and keeps the type transport
unambiguous. Authored case display names and source spans remain separate. ABI
case ordering is lexicographic UTF-16 code-unit order on the encoded case IDs;
framing lengths count UTF-8 bytes. Native verification uses the same ordering.


Sequential codes are i; one-hot codes are 1 << i; Gray codes are i xor (i >> 1).
Compact width is max(1, bitLength(caseCount−1)); one-hot width is caseCount.
Custom width never silently widens. Codes are complete, unique, nonnegative and
fit the exact width. Preserve cases beyond signed 64-bit range with checked
arbitrary-precision magnitudes and finite pre-allocation limits.

One semantic enum identity has one ABI within a construction transaction, including
nested Struct/Vec/protocol uses. The versioned hash covers qualified type identity,
exact width and case-ID/code pairs in canonical case-ID order; locations and
traversal counters are excluded. Reordering an explicit mapping preserves ABI;
changing declaration order under sequential encoding can change codes and ABI.

Safe decode requires equal input/enum widths and an exact belonging default case.
Its validity tests original input bits. Legal codes pass through; illegal codes
produce the explicit default but remain invalid. No implicit Bits-to-enum or
cross-enum conversion is introduced. Numeric conversion and bit reinterpretation
remain separate F15 contracts.

## Canonical implementation and checks

Reuse KernelTypeDescriptor/KernelExpr, the construction transaction, parameter and
shape proofs, semantic/source identities and native Enum/EnumCase/EnumType owners.
Transport actual typed operands/results and reachable definitions; do not serialize
host enum objects, manufacture UInt aliases or accept metadata-only effects.
Independently verify native policy, code/name uniqueness, unsigned canonical base,
definition/result width, case/default/selection identity, hash and exact type edges.

Focused positives: signed extrema; singleton and multi-case policies; sparse custom
{0,3,7}; padded custom width; separately compiled enum and generic consumer;
enum ports/parameters/Struct/Vec/protocol/Mem declarations; dynamic safe decode and
complete expression selection. Negatives: overflow/invalid widths, missing/foreign/
duplicate cases, false policies, conflicting ABIs, cross-enum use, old decode and
forged snapshot/native metadata. Repeat construction and a bounded many-case enum
establish deterministic identity and metadata growth without a speedup claim.

Alternatives rejected: host default masquerading as hardware, changing the existing
case-class representation, adopting unfrozen `Mode.hw` spellings, a second mutable
registry, and silently reducing semantic enum values to unsigned integers.
F54 acceptance is the real construction/native-IR boundary. Portable HDL and later
simulation remain their original increment owners; no CI/full campaign is required.

## Additive Interface role binding and digital connectivity

F15's `RoleAccess.Nested(member, roleName)` retains a role name but supplies no
actual nested role map. F54 adds immutable `InterfaceType.withRoles(roles*)`,
returning a definition with explicit named maps. Existing Interface/Role/Nested
signatures remain valid. Nested names resolve from those explicit bindings or
actual endpoint/array role applications on the same definition in the current
construction transaction. Conflicting maps for one name and missing definitions
are errors. A role name never implies protocol direction; in particular a request
and response may flow in opposite directions under one initiator role. No global
mutable role registry or runtime reflection is introduced.

One typed recursive member expansion owns complete role validation, protocol
forward/backward direction, nested selected-role identity, enum/payload types,
resolved-net mode and the flattened ABI projection. Interface views retain their
original endpoint identity. Monitor views are read-only, and automatic inversion
is restricted to fully complementary digital leaves; analog access and resolved
permissions cannot become ordinary reversed directions.

Digital member drives and observations, exact endpoint connections, net reads,
conditional drivers, explicit release and pass-through retain actual captured
operand identities, exact types and module ownership. A release remains distinct
from an enabled value driver. `split` retains separately typed net read and driver
write/enable projections; these are semantic views, not host values or fabricated
constants. The finite split contract requires exactly one authored conditional driver after
pass-through identity is resolved. Its actual value and enable define the views;
constrained low/high drivers supply their declared active level. Zero or multiple
possible value drivers diagnose unavailable/ambiguous projections. Separate
high-impedance releases remain inactive drivers and do not manufacture values or
count as competing value writers. Independent bridge/native checks preserve this
contract without inferring simulator state. Portable-boundary-only
resolution rejects internal resolved nets. Full resolved simulation and technology
mapping remain explicit capability requirements, not claims that this checkpoint
implements simulation or target HDL emission. Conservative and signal-flow analog
members retain their existing owners and are not treated as digital drivers.

The selected owner is the existing construction transaction plus a pure typed
connectivity contract. Alternatives rejected are string-only operation inventory,
direction heuristics based on role names, a second mutable registry, and flattening
that discards nested role identity. Layout/metadata expansion is bounded and visits
the represented leaves; shared definitions are discovered by identity once.

## Scalar values, widths and owned storage

Bits, UInt and SInt retain their original fixed or owned static Integer width
expression. Widths must be positive over every legal parameter valuation; a
parameter default is not a width proof. Width dependencies use the explicit
`width` effect, distinct from `shape`. Literal magnitudes and parameter bindings
must fit the minimum legal width. Signed and unsigned arbitrary-precision
attributes preserve their actual numeric interpretation.

Explicit extension preserves value with sign/zero extension; truncation keeps low
bits. Wrap is modular, saturation clamps, and checked resize requires an
all-values-fit or exact constant proof. Reinterpretation preserves bits and width.
Unsigned-to-signed numeric conversion adds a sign bit; signed-to-unsigned requires
a nonnegative proof. UInt-to-Real uses finite binary64 round-to-nearest-even, so
representable rounding is allowed and no exact-integer promise is made. Optional
constant proofs are resource-bounded and never inspect a parameter default or
mutable signal initializer. Unproved checked conversions reject rather than
silently choose a value.

Wire and register declarations retain actual recursive types and owned SSA
identity. Registers retain their actual initializer edge and local domain; memory
retains element type, depth, domain, read latency, read-under-write policy and
ordering. Shape projections reuse the existing ordered shape mapping and cannot
turn an input/read-only root into writable storage. Continuous assignment and
register-next assignment are explicit typed edges. F54 validates their ownership,
shape and type contract; execution timing and memory-port behavior remain with the
later state/memory increments. Clock and Reset are capabilities, including when
nested, and cannot be hidden as ordinary storage bits. Recursive footprint bounds
are checked without allocating one object per lane.

The canonical digital expression snapshot and native value/type models own these
contracts. Bridge specializations share a single SSA dependency schedule; enum,
scalar, storage and interface helpers do not maintain separate competing value
graphs. Existing analog procedural storage and its placement/definite-assignment
rules retain their own owner. Native malformed-input verification remains
independent of the source checks.

## Resolution policy and downstream execution

The existing [unified Interface/inout architecture](../architecture/0021-unified-struct-interface-role-and-inout-architecture.md)
owns the four-state policy: no active driver produces Z unless an explicit
pull/resolution model applies; a compatible active driver produces its value;
conflicting active drivers produce the declared unknown/contention result.
Ordinary Bool/Bits/UInt/SInt variables are not implicitly resolved nets. Explicit
high-impedance release is retained as an inactive driver effect, and resolved
readback retains the actual net identity. Unsupported internal resolution is an
error, never a priority mux or a known-zero substitution.

F15's frozen public types and explicit release API do not require a new public
Logic or X/Z literal carrier for F54. This increment preserves the resolution
contract and independently validates driver/access/mode edges. Executing 0/1/Z/X
resolution belongs to the resolution-aware simulator/tool consumers: F66 target
simulation and F127's typed digital simulator. Their handoff must cover undriven
readback, compatible and conflicting drivers, release, open-drain/open-source
active-level restrictions and pass-through identity. F54 does not report those
future simulator cases as executed.

Clock and Reset remain distinct capabilities in the native type contract as well
as the source ABI. Their allowed port/interface declarations, member accesses and
exact connections retain capability identity instead of sharing an unqualified
one-bit integer carrier. They cannot substitute for each other or for ordinary
Bool/Bits storage and scalar arithmetic. This type preservation does not add
clock execution, CDC analysis, reset sequencing or timing behavior; those remain
the existing state/domain increment responsibilities.

The capability profile permits direct Clock-to-Clock and Reset-to-Reset boundary
wiring, including structurally verified boundary projections. Verification follows
actual producer/root edges; a metadata label alone cannot manufacture a capability.
Register-next, storage, ordinary protocol payloads, arithmetic and enum-selection
results cannot produce or store capabilities. A capability selection would require
an explicit derived-clock/reset or mux contract, which F54 does not introduce.
This corrects the historical native i1 erasure while retaining legacy i1 fixtures
as legacy ordinary values rather than relabeling them as clocks or resets.
