# Increment 28 implementation — electrical nodes, nets, and branches

Increment 28 implements normalized scalar conservative connectivity while
keeping public API v0.3 unchanged.

## Compiler IR

The dialect now represents:

- `nodal.component_contract` for partial/concrete ownership;
- boundary `nodal.terminal` direction, independent flow orientation, and source
  hierarchy;
- source-semantic `nodal.connect`, `nodal.alias`, and `nodal.reference`;
- named or implicit oriented `nodal.branch` declarations;
- compiler-owned `nodal.connection_set`, `nodal.potential_equality`,
  `nodal.reference_potential`, and `nodal.flow_conservation` records.

Legacy terminal/node/branch spellings remain accepted when no component
contract opts into Increment 28.

## Normalization

`materializeConservativeConnectivity` runs transactionally before the semantic
verification gates. It removes old generated records, validates source topology,
uses union-find to construct connection sets, sorts members by retained source
path, derives deterministic hash-based symbols, and regenerates normalized
potential and flow equations. Generated ODS inherent attributes are installed
through MLIR operation properties rather than discardable attributes.
User-authored normalized operations without compiler provenance are rejected;
only records carrying the compiler-owned Increment 28 provenance contract may
be replaced during an idempotent rebuild. Component hierarchy paths must also
remain canonical and unique.

Branch flow is positive from positive to negative. A boundary terminal's sign is
controlled only by `flow_orientation`; input/output/inout remains interface
metadata. Concrete records are complete/local. Partial records are
incomplete/extensible.

## Validation

The native tests cover:

- canonical discipline aliases;
- deterministic and idempotent set generation;
- input/output/inout independence from flow orientation;
- named and implicit branch orientation;
- global references and generated zero-potential records;
- generated potential equality and signed zero-sum flow conservation;
- partial versus concrete completeness;
- generated reference identity, oriented flow provenance, and ownership
  self-consistency;
- incompatible disciplines, signal-flow misuse, duplicate implicit branches,
  mixed reference scopes, invalid ownership, and mutation of the repository
  contract.

The Verilog-A/Verilog-AMS backends remain fail-closed for the new operations.
Residual DAE construction and hierarchical topology flattening remain deferred.
