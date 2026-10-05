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

The native matrix includes thirteen positives run twice and twenty-one
exact-code rejections. In addition to the literal cases, it covers a direct
ranged parameter, a parameter-minus-literal DAG and same-identity subtraction.
Expression negatives cover an unsafe upper endpoint, ordinary dependencies,
runtime values and a zero-capable divisor. The earlier first/last, singleton,
multiple-axis, nested-element, symbolic-extent, signed-extreme, 64-axis and
direct-port cases remain. Preservation checks retain the expression operators,
parameter references and literal spellings as well as operand order,
shape/element types, source identity and structural storage metadata. Mutation
controls reject changed expression operators in addition to the earlier loss,
type, value, axis, source, storage, abnormal-exit and diagnostic controls. SSA
renaming is allowed. The registered CTest retains a 60-second deadline and the
shared runner's deterministic two-execution check.

Within one index operation, symbolic minima are cached per distinct dimension;
repeated axes reuse the proof. Traversal is linear in rank plus the existing
bounded parameter analysis for each distinct symbolic extent. This statement
does not claim an optimization of unrelated module-wide shape verification.

This section records the native trust boundary; the following checkpoints add
bounded public `Vec.at` capture, bridge transport and static Integer expression
indices. Slices/views, generated lexical storage and legal target
array/generation lowering remain unfinished. Hand-authored MLIR must not be
reported as generated-Verilog-A evidence. All F43 parent and descendant
acceptance items remain open.

## Public static port indexing checkpoint

The existing public `Vec.at(...)` surface captures fixed literal indices on
fixed or directly bounded symbolic shaped input/output ports. Construction
checks rank and every literal against the minimum legal extent before publishing
the expression. Negative, end and rank-mismatched indices fail with
`NODAL-SHAPE-043-002`; no parameter default supplies an index proof.
Existing internal-wire `.at` calls retain their compatibility-only inert
expression capture and do not enter this port transport.

The snapshot retains one source-correlated index identity, its module-owned port
and ordered literals. The bridge independently rejects forged owner, input,
rank, or bounds and emits a typed `nodal.port_value`, index-typed constants and
the existing `nodal.shape_index`. Native verification resolves the direct port,
checks its exact shaped type, and then applies the existing all-legal-extents
index proof. This reuses the canonical declaration, parameter interval and
`shape_index` owners rather than adding another shape engine or registry.

This bounded profile does not yet accept internal shaped wires, slices/views,
assignments through indexed l-values, generated-instance storage, or target
array lowering. It is public Scala-to-normalized-IR evidence, not generated
Verilog-A acceptance. Every F43 parent and descendant item remains open.

## Static Integer expression index checkpoint

Public port indexing now also accepts a canonical static `Integer` index DAG
when its conservative interval is non-negative and strictly below the minimum
legal extent. Construction reuses the same captured-expression traversal and
`IterationDomain` arithmetic as compound `hdlRange` bounds. It supports direct
ranged parameters and pure `add`, `sub`, `mul`, `div` and `neg` DAGs, including
same-identity subtraction correlation. Zero-capable divisors, overflow,
unbounded parameters, unsupported/runtime operations, foreign ownership and
unsafe endpoints reject transactionally with `NODAL-SHAPE-043-002`. Parameters
that participate are marked with the existing `shape` structural effect.

Snapshots retain literal axes as canonical decimal strings and symbolic axes as
canonical declaration/expression paths. Static index expressions are roots of
the existing parameter-expression inventory; no parallel expression graph is
created. The bridge independently traverses that inventory with the shared
interval arithmetic, requires module-local structural shape dependencies, and
emits the existing `nodal.const_parameter_ref`, `nodal.const_literal` and
`nodal.const_expr` SSA DAG. Literal axes preserve their prior index-typed
`nodal.constant` form. The native `nodal.shape_index` verifier accepts exactly
those two carriers: a legacy index literal or an i64 static-expression DAG. For
i64, it independently checks direct-module ownership, structural shape
dependencies and `inferParameterIntegerBounds` before applying the all-legal-
extents bound.

Public tests exercise deterministic direct and compound expression transport,
configured native execution, construction rejection of an unsafe symbolic
endpoint, and bridge mutations for missing expression identity, changed
arithmetic and ordinary parameter classification. This remains an indexing-only
checkpoint: compound dimension DAGs, slices/views, l-value indexing, generated
storage and target arrays/generation are still open, as are all parent and child
F43 acceptance boxes.
