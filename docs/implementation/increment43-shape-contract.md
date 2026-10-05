# Increment 43 fixed and direct-symbolic shape contract

**Status:** bounded implementation checkpoint; no F-043 child is complete.

This checkpoint turns `Vec` declaration dimensions from inert spelling into a
checked construction, bridge and native contract for fixed positive dimensions
and directly referenced bounded `Integer` parameters. A symbolic dimension is
owned by the declaring module, has a finite range whose lower endpoint is
strictly positive, and marks its canonical parameter with `shape` and `rank`
structural effects. The bridge retains the parameter name in
`!nodal.shaped<...>` and rejects forged missing, ordinary, unbounded or
zero-capable dimensions. Native module verification independently resolves the
symbol, its structural classification, shape envelope and positive finite range.

Zero-sized shapes remain illegal even though an empty `hdlRange` remains legal.
`Vec` remains structural data rather than implicit memory. This checkpoint does
not accept compound dimension DAGs, implement index/slice/view transport, allow
generated lexical storage, or lower arrays/generation to Verilog-A. Those
capabilities remain open and must not be inferred from shaped port IR parsing.

Public tests cover deterministic fixed and symbolic snapshots, bridge output,
structural effect capture, configured native parsing when `NODAL_NODALC` is
available, and transactional rejection of zero, unbounded, zero-capable and
compound dimensions. The native matrix separately covers fixed and symbolic
positives plus forged missing parameters, ordinary classification, missing
shape envelopes and absent/zero-capable ranges.

## Native static indexing checkpoint

The existing `nodal.shape_index` operation now checks each zero-based index
against every legal shape, in addition to rank and exact element type. The
bounded native profile accepts index-typed `nodal.constant` operands. Negative,
out-of-range, unknown runtime and metadata-only claimed proofs fail with
`NODAL-SHAPE-043-002`. Malformed empty dimension tokens also fail normally.

For a symbolic extent, the verifier queries the existing parameter interval
analysis through its canonical declaration. It uses the lower bound of the
legal extent, preserves exclusive endpoints and intersecting constraints, and
never substitutes an overridable default. This is a narrow query interface to
the existing arithmetic owner, not another interval engine. Integer arithmetic,
parameter folding and storage semantics are unchanged.

The native matrix includes ten positives run twice and seventeen exact-code
rejections. It covers first/last and singleton indices, multiple axes, nested
element types, symbolic minimum/exclusive/intersecting ranges, signed extremes,
64 repeated symbolic axes and a direct shaped-port value. The two additional
carrier rejections cover absent ports and exact type mismatch. Preservation
checks resolve actual SSA index operands and retain their order, value,
shape/element types, source identity and structural storage metadata. Mutation
controls reject lost operations, changed types/indices/axis order, changed
source/storage identity, abnormal exits and unrelated diagnostics. SSA renaming
is allowed. The registered CTest retains a 60-second deadline and the shared
runner's deterministic two-execution check.

Within one index operation, symbolic minima are cached per distinct dimension;
repeated axes reuse the proof. Traversal is linear in rank plus the existing
bounded parameter analysis for each distinct symbolic extent. This statement
does not claim an optimization of unrelated module-wide shape verification.

This section records the native trust boundary; the following checkpoint adds
the bounded public `Vec.at` capture and bridge transport. Symbolic index
expressions, slices/views, generated lexical storage and legal target
array/generation lowering remain unfinished. Hand-authored MLIR must not be
reported as generated-Verilog-A evidence. All F43 parent and descendant
acceptance items remain open.

## Public static port indexing checkpoint

The existing public `Vec.at(...)` surface now captures fixed literal indices on
fixed or directly bounded symbolic shaped input/output ports. Construction
checks rank and every literal against the minimum legal extent before publishing
the expression. Negative, end, rank-mismatched and symbolic/runtime indices fail
with `NODAL-SHAPE-043-002`; no parameter default supplies an index proof.
Existing internal-wire `.at` calls retain their compatibility-only inert
expression capture and do not enter this port transport.

The snapshot retains one source-correlated index identity, its module-owned port
and ordered literals. The bridge independently rejects forged owner, input,
rank, or bounds and emits a typed `nodal.port_value`, index-typed constants and
the existing `nodal.shape_index`. Native verification resolves the direct port,
checks its exact shaped type, and then applies the existing all-legal-extents
index proof. This reuses the canonical declaration, parameter interval and
`shape_index` owners rather than adding another shape engine or registry.

This bounded profile does not yet accept internal shaped wires, symbolic index
DAGs, slices/views, assignments through indexed l-values, generated-instance
storage, or target array lowering. It is public Scala-to-normalized-IR evidence,
not generated Verilog-A acceptance. Every F43 parent and descendant item remains
open.
