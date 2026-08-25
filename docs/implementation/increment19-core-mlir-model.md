# Increment 19 implementation: canonical core MLIR model

Increment 19 establishes the private target-neutral MLIR vocabulary used by
later Scala lowering, semantic verification, scheduling, and backend work.

## Source organization

- `NodalTypes.td` defines canonical dialect-owned value and identity types.
- `NodalOps.td` defines structural, connectivity, domain, enum, and FSM ops.
- `NodalTypes.cpp` verifies type-local invariants and registers generated types.
- `NodalOps.cpp` verifies operation-local invariants without pretending to run
  the whole-design checks owned by Increment 21.
- `core/compiler/test/IR/core-model.mlir` is the positive textual fixture.
- Four negative fixtures exercise width, resolved-net, enum, and FSM failures.
- `CoreModelTest.cpp` validates typed C++ parsing and exact type parameters.

## Semantic separations

Shape, storage, and target layout remain separate. Logical Interface identity
is preserved independently of backend flattening. Resolved digital nets,
conservative terminals/branches, and explicit mixed-signal bridges are distinct.
Canonical enum ABI identity is distinct from local FSM storage encoding.
Domains and crossings retain source/destination identity and declared kind.

## Predecessor compatibility

The Increment 18 `nodal.placeholder` verifier retains its exact non-empty-label
contract and diagnostic text. Increment 19 adds new types and operations without
weakening or rewriting the already validated dialect-bootstrap behavior.

## Validation

The permanent read-only workflow runs the Increment 18 compatibility checker,
Increment 19 structural and mutation tests, the locked native build, CTest,
custom and generic textual round trips, and negative verifier fixtures. Pull
requests retain the repository-required Summary, Validation, Design gate, and
Checklist sections so local and CI contribution-policy checks use one contract.

Roadmap completion and evidence are recorded only after the dedicated workflow
and required Core CI pass on the permanent pull-request head.

## Accepted evidence

- Pull request: #46.
- Dedicated Increment 19 workflow: run 32829155720.
- Required Core CI: run 32829155633.
- Roadmap revision: 1.23.
