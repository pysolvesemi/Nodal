# Increment 21 evidence fixture

This directory records the versioned native semantic-pipeline contract.

The permanent implementation provides thirteen ordered verifier stages,
`nodal-gate-fast`, `nodal-gate-default`, and `nodal-gate-release`, normalized
pipeline metadata, MLIR verification after each pass, and clone-before-commit
transactional acceptance. The ordered stages and primary native diagnostic
identities are frozen by the adjacent JSON inventories.

The roadmap checkbox and accepted run identifiers are added only after the
dedicated workflow and Core CI pass on one stable implementation head. A final
PR-head validation then checks the evidence commit before squash merge.
