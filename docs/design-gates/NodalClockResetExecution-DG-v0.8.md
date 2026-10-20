# Nodal clock/reset execution design gate v0.8

**Status:** Approved
**Scope:** public-api

## Required hierarchy proof and allocation

This additive Foundation 57 slice implements F-057.B.2: timing, crossing and reset
provenance through concrete hierarchy and bridge/native IR. It follows the
relationship contract in v0.7. Existing public default/named domain binding
spellings remain unchanged. Metadata-only instance dictionaries are not executable
proof of actual clock/reset bindings.

Foundation 58 still owns inferred structural clock/reset ports, domain-polymorphic
module specialization, parameterized/generate hierarchy and general hierarchy
HDL execution. This slice must demonstrate actual source-to-native hierarchical
proof and causal rejection. Until F58 lowering is available, native target export
must reject unsupported hierarchy transactionally, without partial HDL or a false
claim that hierarchy was emitted. This boundary does not defer F57 provenance.

## Canonical owners and actual bindings

Reuse the existing construction instance registry, domain requirement identities,
module snapshots and native instance symbols. Do not introduce a second module
elaborator or a flattened shadow graph. Extend the instance boundary with typed
actual clock/reset binding operands and concrete digital port connections. Retain
exact instance, child requirement, actual parent domain, binding origin and source
location. Default, lexical inheritance and named selection resolve to one
unambiguous authoritative binding. Conflicting repeated bindings reject rather
than using last-writer or first-match behavior; explicit named overrides retain
the existing precedence over a default intended for a single requirement.

Native validation independently matches operands to the actual parent domain and
child requirement. A matching string, symbol name or authored annotation alone
cannot certify clock identity or reset release. Missing, duplicate, foreign and
wrong-instance bindings reject. Resetless and reset-bearing resolved occurrence bindings remain
distinct; ClockDomain.required itself acquires no invented reset-policy
constraint. An inherited release proof must retain its actual receiving clock,
active edge, assertion roots and polarity rather than a copied policy label.

An occurrence context consists of the actual instance path, module definition and
its verified requirement substitution. Shared clock/domain/reset/dependency
queries consume this context. Physical inputs, owned clock producers, reset
chains and crossing instances retain occurrence-qualified identity. Two instances
of one definition are not one synchronizer or reset chain. Same permits direct
sampling only under v0.7 and does not merge physical or occurrence identities.

## Digital boundary causality

Add a narrow parent-owned typed digital instance-port reference/connection
representation for concrete immediate-child inputs and outputs, using exact
existing types and direction and the existing assignment/write-ownership rules.
The current zero-operand InstanceOp and conservative-terminal-only instance
references do not provide this digital boundary; raw child handles must not
become a foreign-module scope bypass. Preserve existing selector/assignment
spellings; input connections use ordinary single-driver ownership. Do not repurpose
conservative-terminal operations or relax foreign-value scope checks. Parent expressions drive actual child inputs;
parent reads of child outputs follow the child's actual driver and all selectors
or predicates. An output's declared domain is not proof of its contents. Child
internal or sibling capture without an explicit legal boundary is rejected.

The same sampling and crossing checks must follow dependencies across boundaries,
not stop at a nominal port domain. Legal primitive outputs retain the real
crossing boundary and source provenance. Independent crossing results combined
into one observed value remain reconvergent even across instances. One reused
crossing result may fan out safely; independent unrelated output values are not
artificially grouped into a single observation merely because they share a module.

Use a shared bounded hierarchy-context/dependency layer beneath domain, reset and
sampling consumers, not separate traversals with divergent rules. Preserve flat
consumer behavior and existing conservative-terminal/Interface owners. General
symbolic Interface expansion and generated hierarchy remain with F58; unsupported
boundary forms reject explicitly rather than dropping provenance.

## Bounds and deterministic behavior

Traverse the concrete instance DAG iteratively with explicit occurrence and edge
budgets, detect recursive instantiation, and reject unsupported expansion before
allocating beyond the bound. Cache facts by occurrence plus value/requirement,
not by module definition alone. Do not clone the entire IR or build all-pairs
transitive provenance sets. Stable paths, binding origins and diagnostics must be
independent of map iteration order. Measure a representative repeated-definition
and nested-instance case; no universal performance claim is required.

## Focused acceptance

Positive source/native witnesses cover inherited/default and named bindings,
multiple requirements, a grandchild, sibling occurrences, and legal parent-to-child
and child-to-parent crossings. Reset proof traverses at least two boundaries.
Causal negatives swap actual clock/reset operands without changing metadata,
misbind an instance requirement, omit or duplicate a binding, use an invalid
receiving release chain, forge an output domain, capture a sibling, alias crossing
identities across instances, reconverge independent crossings across boundaries,
and construct recursive hierarchy. Existing flat relationships, reset and owned
crossing cases remain direct predecessors.

Retain source-located actual native witnesses and the explicit transactional target
rejection until hierarchy lowering is implemented in F58. No CI, repository-wide
qualification, speculative architecture rewrite or coarse F57 closure is implied.
