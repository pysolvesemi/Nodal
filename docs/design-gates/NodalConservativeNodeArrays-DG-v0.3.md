# Nodal conservative node arrays design gate v0.3

**Status:** Approved
**Scope:** public-api, core-library-boundary
**Increment:** Foundation 43
**Decision date:** 2026-10-18

## Authority and selected scope

The owner's standing Foundation implementation authority is:

> start implementing all foundation unchecked increment items in nodal repo from increment 43 and continue until all foundation checklists are completed. Don't start another track just complete foundation track.

These implementation-selected signatures extend
[v0.2](NodalConservativeNodeArrays-DG-v0.2.md); the owner did not separately choose
these signatures. Implement one integrated boundary-array/whole-child binding
path, including exact parameter substitution, dependent Integer defaults and
compact noncontiguous actuals. Keep Foundation 43 open while remaining required
profiles are unfinished. Generic language semantics remain Verilog-AMS 2023.

## Public surface and capabilities

```scala
protected final def in[D <: Discipline](discipline: D,
    dimension: Dimension, dimensions: Dimension*): NodeArray[D]
protected final def out[D <: Discipline](discipline: D,
    dimension: Dimension, dimensions: Dimension*): NodeArray[D]
protected final def inout[D <: Discipline](discipline: D,
    dimension: Dimension, dimensions: Dimension*): NodeArray[D]
protected final def connect[D <: Discipline](
    left: NodeArray[? <: D], right: NodeArray[? <: D]): Unit
// On NodeArray:
infix def <>(other: NodeArray[?]): Unit
```

Retain scalar overloads and v0.2 local-view signatures. The required first
dimension avoids overload ambiguity. Use existing boundary direction kinds with
conservative dimensions and the existing NodeArray root/projection distinction.
Only root declarations create ports; views retain owned physical capability.
Electrical remains the implemented conservative target capability. Numeric Vec,
digital/wreal ports, implicit broadcasting and equal-count shape coercion reject.

Connect accepts one whole declared port of an attached immediate child and one
parent-owned local or boundary array root/view. Either argument order is legal;
normalize internally. Use unchanged Instance.apply, retaining the actual child
instance and declaration references. Foreign/internal/grandchild selectors and
child-array projections in the parent reject in this whole-binding profile.
Validate after all instance parameter overrides, including .param after connect;
there must be no connect-time default freezing or call-order dependence.

Capture typed immutable binding facts through the existing operation/snapshot
owner: binding path, owner, exact instance, declared child port and actual
parent shape reference. Shape remains authoritative on declarations/projections,
not copied into a second mutable binding registry. Require one binding per exact
child port; different child ports may share a parent actual. Missing/duplicate
bindings use the existing hierarchy boundary checks.

## Exact finalized instance parameter proof

ParameterModel owns a query-local immutable substituted Integer DAG and bounds,
using the existing actual Instance, ParameterOverride SSA, literal bindings,
Parameter declarations and ParameterValue default DAGs. ShapeModel consumes that
query for exact rank/extent checks; no second hierarchy or parameter engine.
Reject conflicting bindings, foreign/runtime values, fixed-parameter overrides,
cycles and checked-arithmetic overflow.

For an exact child formal, substitute an explicit parent SSA override or literal
binding; otherwise substitute its declared default recursively under the same
finalized instance environment. An omitted default is a legitimate instance
actual, not permission to specialize an arbitrary parent parameter at its default.
For B default A+1 with A bound to parent N, B remains N+1, not the child declaration's
folded default. Parent N retains its actual identity and entire legal envelope.
Validate every used actual against the formal's effective bounds/exclusions;
unproved correlated constraints reject explicitly.

Extent equality proves exact substituted opcode/ordered-operand correspondence,
actual literals with checked folding, and exact parent parameter declarations.
Use existing shape product identities where justified. Equal names/defaults/
envelopes or total cardinality alone do not prove equality. Arbitrary algebraic
rewriting is outside this finite proof. Extent equality is distinct from opaque
captured-expression identity used for conservative physical selection keys.

Include dependent Integer default roots in the existing expression closure.
Bridge nonliteral defaults through ordinary ConstParameterRef/ConstExpr and
ParameterValue operations. Any numeric declaration-default transport attribute
is independently checked against the retained DAG and never proves instantiated
shape identity. Do not claim new Real/Boolean dependent-default support here.
Reusable child definitions are not cloned per parameter value.

## Native representation and physical relation

Add explicit direct-module operations, preserving scalar siblings:

- terminal_array: existing name/direction/flow-orientation/source metadata, one
  shaped TerminalType result whose dimensions are authoritative.
- instance_terminal_array: exact instance/declared port and inherited outward
  orientation, one shaped result expressed in parent dimension vocabulary.
- connect_array: actual parent shape and child array reference operands plus
  connection occurrence/source metadata, no result.

The instance reference may use the validated actual's parent-owned shape type,
but native verification independently proves every extent against the substituted
formal. This is not acceptance of a caller-asserted effective type. Array binding
integrates with F42 direction/discipline/nature, definition-cycle and boundary
coverage checks. Scalar operations retain their prior acceptance.

Extend the existing conservative resolver with actual owned TerminalArray roots.
The child array reference is initially a binding endpoint only. The authoritative
relation maps each logical child lane to the actual parent's verified root/map,
without enumerating symbolic lanes or inventing scalar nodes. Full port binding
coverage is not proof of complete component equations; sparse selections still
cannot certify full array topology.

Keep physical node identity, authored occurrences and port-current incidence
separate. Native port_flow_access may resolve a scalar selection to an owned local
boundary-array port and mapped lane, preserving quantity/nature/orientation.
It rejects internal node-array roots, shaped operands and child-array references.
Existing scalar port-flow acceptance is unchanged. Public one-argument V/I is not
silently reinterpreted as angle-delimited port current.

## Target layout and lawful compact adapters

Explicitly supersede v0.1/v0.2 local unpacked-net representation with one ascending
vector layout for all owned conservative shapes: electrical [0:C-1] n, and matching
input/output/inout [0:C-1] p for boundaries. C is the checked row-major product;
logical ordinal k maps to vector index k. Singleton shape stays [0:0]. Update
maintained output assertions while preserving physical semantics and historical
accepted evidence. Existing direct signed64 support is not silently narrowed. Existing synthesized
nets for source GenerateOp ranges retain their authored nonzero lower/index
semantics when moved to vector declaration syntax; the zero-based rule above
applies to owned shaped NodeArray/TerminalArray layouts.

Whole-root actuals use whole vector identifiers. Proven contiguous views may use
ascending constant part-selects. Other accepted views use one bridge vector and
one module-scope structural generate loop. Reuse the shared immutable map program
with the loop ordinal and scoped localparam stages. Reject counts/strides/
intermediates unsupported by the portable signed32 genvar/Integer carrier.
Do not enumerate lanes, duplicate expressions exponentially or use runtime temps.

Each generated lane joins a root lane and bridge lane through a structural helper
whose two external named formals refer to the same single internal electrical net:

```verilog
module nodal_alias_electrical (.lhs(n), .rhs(n));
  inout n;
  electrical n;
endmodule
```

This is a planned structural pattern, not execution evidence. Emit one helper per
implemented discipline contract, with deterministic collision-safe names. Its
actual shared-net definition establishes the already verified source relation;
two separate internal nets would not. Do not replace it with numeric branch
equations, digital assignments/transmission primitives or alias system functions.
Synthesized structural loops do not claim source-generated topology support.

The [2023 LRM](https://www.accellera.org/images/downloads/standards/v-ams/VAMS-LRM-2023.pdf)
sections 6.5 and 6.6 define named formal port expressions, vector actuals and
structural generated instances/localparams; section 6.6.1 gives genvar occurrence
constants. Sections 1.3/1.3.1 establish analog node connectivity, and Annex C.8
includes Clause 6 in the Verilog-A subset except real-valued ports. Section 5.5
permits static vector-element references in local port probes. Internal parser
acceptance is not independent simulator/tool acceptance.

## Parser, provenance, alternatives and focused checks

Extend actual target grammar for vector declarations/direction ranges, whole/
member/part actuals, named formal-to-internal net expressions, structural scopes,
ordered immutable stages and local selected port probes. Parse helper connectivity
from its definition, never a privileged generated name. Independently reject
unknown/forward/Real dependencies, wrong widths/orientation, invalid bounds,
separate-net aliases and behavioral/digital constructs outside this profile.
A standalone target parser cannot recover unrepresented source intentions: exact
source-map correspondence is a backend obligation checked against the native
relation and focused source/output fixtures, not an invented parser proof.

Source maps retain binding occurrence, instance/formal, effective shape and
parameter/default derivation, actual root/projections, port incidence, orientation,
bridge bus and generated stages. Derived helper instances are not user component
contract evidence. Never serialize pointer-based cache keys as stable identities.

Chosen: existing parameter/shape/connectivity owners plus explicit typed boundary
ops and lawful structural adapters. Rejected: default specialization, raw type-string
comparison across modules, per-instance cloned definitions, copied semantic
registries, numerical ideal-wire equations and per-lane source expansion.

Focused positives include fixed/symbolic/singleton ports, owned views, whole and
contiguous/noncontiguous bindings, repeated child definitions, literal/parent/
expression overrides, dependent defaults, .param after connect and local native
port-current incidence. Negatives include rank/extent/envelope mismatch, distinct
equal-default parameters, cycles/forged types, wrong child/owner/capability,
duplicate/missing bindings, malformed helper/maps and carrier overflow. Retain
actual public source/IR/VA/AMS witnesses and directly affected predecessors.
Record concise architecture/scale findings and executed results in the existing
implementation note, raw artifacts in .work. No CI/full qualification.

Partial child bindings, child-to-child/bulk local joins, source-generated
connections/instances, remaining generated state/lifetime and repeated-live-fill
demand lowering remain open wherever required by full F43 scope.
