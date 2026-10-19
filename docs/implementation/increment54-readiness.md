# Increment 54 readiness and implementation

Status: focused implementation acceptance complete; F-054.G and the parent remain
open for verified integration. Base
`7dff755dbd22c8f91d70e1d1f863bc3d33273326` is the verified F53 documentation
closure; branch `increment/54-digital-type-port-layer`.

## Required boundary and shared owners

Implement actual public construction → closed snapshot → independently verified
native Nodal IR for digital scalar/literal/type/aggregate/enum/port contracts.
F15 froze source forms but explicitly did not implement their behavior; F19
provides native type/operation shells. Generic untyped expressions or metadata-only
inventory do not satisfy F54. Preserve existing analog owners and exact symbolic
parameter identity; never specialize widths at a parameter default.

The original A–G and B.1/B.2 remain binding. Linked F-054.B.2.1 adds ordered shape,
scalar-leaf widths, typed parameter/publication expressions, storage and signedness.
FV-01/FV-03 and BT-01..03 retain their shared 54–58 allocation; F54 contributions
will be recorded without closing later-operation/hierarchy obligations.

Selected implementation order:

1. Typed signed/unsigned literals and recursive digital type validation, preserving
   exact widths and explicit numeric conversion versus bit reinterpretation.
2. Canonical recursive Struct/Vec, owned structural carriers and explicit Mem
   declaration/storage identity, reusing existing shape and parameter proof owners.
3. Actual enum derivation/encoding/type/case/decode/selection and native policy/ABI
   verification, through an additive gate correcting the inert host-value stub.
4. Interface/Role/protocol and resolved-net/inout operand capture and native
   connectivity/access validation, keeping endpoint access separate from resolution.
5. Focused source/native trust-boundary and affected predecessor checks, deterministic
   source-correlated ABI/shape witnesses and explicit later-tool handoffs.

Portable HDL emission belongs to F65; independent simulation to F66, synthesis/
equivalence to F67 and complete AMS output to F72. They are not reverse prerequisites.
F54's own type conversions, safe decode, exhaustive expression selection and exact
connectivity cannot be waived as later arithmetic/FSM work. General expression
operators/loops remain F55, state/FSM timing F56, complete hierarchy F58.

## Initial causal observations

Existing native core-model fixture parses. Duplicate enum codes and codes outside
two-bit width reject. Relabeling sequential {0,1} as one-hot accepted at the base,
as did a signed enum underlying type: these were concrete F54 native policy gaps.
Source SInt literals/conversions and symbolic UInt literals lack typed operation
identity; Struct has no native representation. Existing interface/net snapshots
lost required executable read/drive/connect edges. These were implementation targets,
not successful capability claims.

Use focused local positive/negative/boundary checks and concise architecture review.
No CI, full qualification or hourly monitor is authorized by the prototype policy.

## Implemented boundary and focused checks

The shared source digital value contract transports exact scalar literals,
explicit conversions and hardware enum operations through one typed SSA graph.
The enum ABI owner validates derivation, policy, code membership and stable hashes;
the recursive native type owner retains ranked shape, signedness and symbolic
width identity. Storage declarations and projections reuse the existing shape
mapper. Typed Interface capture retains complete module-local role/member maps
and actual connectivity operands through independent bridge/native verification.

All 74 distinct F54 Scala tests pass across focused incremental replays: enum
source 14, enum bridge 9, digital type 5, literal 5, conversion 7, digital bridge 5,
storage 5, Interface source 9, Interface bridge 13 and capability wiring 2.
This is a reconciled set of successful focused cases, not a claim that earlier
failed attempts passed. Both repaired Interface cases were replayed, including
all 13 actual public source/native normalization fixtures and the two-domain
capability witness. Raw commands and logs remain under ignored `.work/`.
Affected predecessor acceptance is recorded below.

Concrete integration repairs retain causal negatives: nested Clock/Reset cannot
become storage bits; memory footprint includes Integer/Real leaves; enum generic
constant producers must prove actual code membership; and width proofs must not
substitute defaults. These are shared source/native boundary checks, rather than
backend spelling fixes. The finite native model does not implement HDL emission,
four-state simulation, register timing or memory-port scheduling.

The four-state-policy reconciliation follows ADR 0021 and the frozen F15 surface:
Bool/Bits/UInt/SInt remain ordinary values; explicit resolved nets retain identity,
conditional drivers, release and readback. No-driver Z and conflicting-driver
unknown/contention semantics are preserved for the F66/F127 execution consumers.
No new public Logic/X/Z literal API or simulator is a reverse prerequisite for
F54. Required native transport/access/mode checks remain part of this increment;
unsupported internal resolution cannot silently become a mux or known zero.

The subsequent storage/parameter native checkpoint passed 61 storage cases, eight
selected native suites, and seven actual `nodalc` root/instance binding cases
(four legal, three rejected). The storage suite includes a UInt(1) memory depth of
9,223,372,036,854,775,807 and rejects overflowing depth-times-width footprints.
CTest measured 0.04 seconds for that suite and 0.23 seconds for the eight-suite
selection on this workspace. This demonstrates the selected metadata-only
boundary without lane expansion; it is not a whole-pipeline scaling guarantee.
The final Interface integration replay is recorded below.

F54's twelve new native operations are added to the authoritative backend
capability catalog as digital and unsupported by the current analog emitters.
All 47 focused registry tests pass, preserving the exact historical inventory
subset and generated consumer pins while refreshing the catalog digest. This is
capability classification, not a new digital HDL admission claim.

## Downstream handoff (not executed here)

- F65: preserve exact symbolic signed widths and ranked row-major views; lower
  sparse enum decode with its original validity and explicit fallback; preserve
  complete role/member ABI and resolved-net driver/release identity when emitting
  flat ports and tri-state assignments. The existing emitted analog language
  profiles continue to reject these digital operations.
- F66: simulate signed boundary conversions and invalid enum codes, protocol
  forward/backward directions, undriven Z, release, compatible/conflicting drivers
  and pass-through readback. The F54 native verifier is not a simulator.
- F67: compare explicit numeric conversions versus bit reinterpretation and exact
  Struct/Vec leaf ordering; synthesize only the supported inout profile, preserving
  unsupported internal-resolution diagnostics rather than replacing resolution
  with priority muxing.
- F72: retain digital versus conservative/signal-flow member ownership and explicit
  boundary adapters. A mixed logical interface does not authorize digital drive
  operations on an analog leaf.
- F127: evaluate the established four-state resolution policy and its contention
  diagnostics using the retained typed net/driver graph; no source-name inference.

## Architecture and proportionality review

The existing construction transaction owns identities, static expression capture
and source maps. Digital type/value, enum ABI, interface role and storage helpers
share that captured representation. The bridge retains one dependency scheduler
and delegates specialized rendering without creating parallel SSA inventories.
Native type/shape/parameter proof owners remain authoritative; interface checks
cache actual definition/role/member resolution and use disjoint-set components
for resolved connectivity. Actual memory/array cardinalities are metadata, not
requests to enumerate lanes. Exact member/role maps and enum tables necessarily
grow with their authored records.

Bounds on type/value traversal and optional constant proofs reject unsupported
resource shapes before expansion. Interface flattening visits represented leaves,
not interface-array lanes. Existing interning is reused; no new global mutable
registry or speculative optimization is required for this finite profile.
Repeated proofs, role expansions and retained source strings may still cost more
than a single graph walk. The focused scale witnesses therefore establish only
the measured/checked boundaries above, not universal linear time or a speedup.

## Final native integration checkpoint

The completed native Interface/capability replay passes 115 cases; enum values
pass 65 cases; all nine selected native suites pass (core model, semantic
pipeline, parameter model, target capability, digital type/value/storage, enum
value and Interface model). These include sparse enum constants and effective
parameter bindings, transitive resolved-net domain conflicts, actual readable
member/input-port provenance, exact Clock/Reset kinds and structurally projected
boundary wiring. All 74 F54 source/bridge cases also pass as detailed above.

The deep target-classification witness remains classified as digital, but its
128 nested two-lane wrappers now correctly reject the canonical structural
resource bounds. A legal 127-singleton-wrapper witness separately verifies.
This preserves the classification purpose without claiming an oversized type is
admitted. Capability type transport retains the F56/F57 execution boundary:
static Clock/Reset declarations and exact boundary connections are supported;
computed capabilities, storage, protocol payloads and enum-result muxes reject.

## Public construction demonstration

The executed `DigitalEnumBridgeSource` fixture uses the public API below inside
its module (with its explicit external ClockDomain):

```scala
val modeType = EnumType.custom(
  3,
  enumEncoding(Idle -> BigInt(0), Run -> BigInt(3), Stop -> BigInt(7))
)
val bits = in(Bits(3))
val mode = in(modeType)
val nested =
  in(Vec(Struct("Tagged", StructField("mode", modeType), StructField("valid", Bool)), 2))
val initial = param(enumLiteral(modeType, Idle))
val decoded = decodeEnum(bits, modeType, Stop)
val returnedBits = decoded.value.asBits
val valid = decoded.value.isValid
val selected = selectEnum(decoded.value, 0.U(8))(Stop -> 9.U(8), Idle -> 1.U(8), Run -> 4.U(8))
```

Its focused source/bridge test retains the actual owned enum definition, sparse
case codes, typed parameter, nested aggregate port and decode/validity/selection
SSA edges, then verifies and reparses native IR. Separate wide-code and symbolic
width fixtures preserve parameter identity and values above 64 bits. No digital
HDL output is promised by this boundary; F65 receives these actual IR contracts.

The final capability-drive witness exposed and repaired a source provenance gap:
plain Signal declaration capture had discarded the lexical ClockDomain. Actual
Clock/Reset Input/Output declarations now retain their enclosing domain (or their
explicit one), including shaped capability ports. Other scalar declaration rules
are unchanged. Source validation follows only captured shape projection edges to
an actual same-module input root and compares the exact local domain; the bridge
and native verifier independently repeat the provenance obligation. The causal
positive uses two distinct domains, and negatives require the explicit capability
diagnostic for a foreign-domain or output-root source.

The repaired two-domain capability-drive case passes its focused replay, including
exact `NODAL-DIGITAL-054-018` source negatives and independently forged bridge
root/domain negatives. All 13 source/native round-trip fixtures also pass.

## Acceptance and reproduction

The final focused selection is green:

- 74 distinct F54 Scala source/bridge cases across the successful replays listed
  above, including 13 actual Interface native round-trip fixtures.
- F43 shape: 25 unaffected cases from the initial selection plus the repaired
  wire/index case; generated scope/induction predecessor: one case.
- Procedural initialization ordering: one valid lexical-scope positive and one
  exact persistent-static-initializer negative.
- Nine selected native suites; Interface/capability 115 cases, enum value 65,
  storage 61, and seven actual root/instance parameter binding witnesses.
- 47 backend capability registry tests, generated registry freshness, scoped
  formatting, Markdown and diff checks. New/changed helper code passes scoped
  Scalafix; the existing ConstructionSession return at node-selection deferral
  and throw at generated-region rollback remain pre-existing whole-file lint
  findings, not successful lint claims or new semantic changes.

The old procedural predecessor placed a mutable-dependent initializer at the
persistent procedure root, contradicting the existing static-initializer gate.
Its unchecked success cast concealed `NODAL-ANALOG-037-009`. The affected test now
retains that exact negative and tests the intended chronological ordering under
a lawful lexical scope with deliberately inverted source locations. Historical
CI is not relabeled as successful semantic evidence for that old fixture.

Reproduce affected cases with the repository toolchain and `./mill --no-server
core.scala.testkit.test.testOnly nodal.internal.testkit.Increment54EnumSourceTests`
(or the other named F54 suite). Selected-case runs use the suite's actual test
selector and are counted only from terminal test output. Native selection:

```sh
ctest --test-dir .work/native-release --output-on-failure -R 'nodal.native.(core-model|semantic-pipeline|parameter-model|target-capability|digital-type-model|enum-value-model|digital-value-model|digital-storage-model|digital-interface-model)-unit'
python3 scripts/generate_backend_capability_registry.py --check
```

The implementation and its architecture review satisfy F-054.A–F and linked
F-054.B.2.1 at the documented construction/native boundary. F-054.G and the parent
await the actual verified squash integration. No CI, target HDL simulation,
synthesis, four-state execution or full qualification was run for this increment.
