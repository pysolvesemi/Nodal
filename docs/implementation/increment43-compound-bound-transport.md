# Foundation 43: compound generated-bound transport

**Status:** Implementation checkpoint; whole F-043 acceptance remains open.

The owning checklist and applicability decisions remain in the
[readiness record](increment43-readiness.md), the main Foundation roadmap and
the lightweight iteration amendment. This checkpoint completes transport of
captured compound bounds, not analog shapes, generated storage or target arrays.

## Shared implementation

The bridge reuses the existing static parameter-expression emitter for instance
overrides and generated bounds. Generated expressions use one module-local SSA
cache keyed by canonical expression identity. Shared and nested ranges retain
their source DAG without host unrolling, helper parameters or default
substitution. Missing, cyclic and over-depth expression snapshots reject.

Literal/direct-parameter ranges preserve the existing zero-operand bound
attributes. If any bound is compound, all three ordered bounds (lower,
upper-exclusive, step) become signless i64 SSA operands of `nodal.generate`.
The operation and parameter-model verifier share the exclusive-form check:
zero operands requires all three attributes; three operands prohibits those
attributes; every other arity rejects. Existing identities and count metadata
remain mandatory for the new form. Empty generated bodies retain an explicit
empty block, so a zero-operation body is not confused with a zero-block region.

The existing native `IntegerBoundsAnalysis` independently computes the finite
envelope and count. Only the generated SSA profile interprets public Integer
signless i64 intermediates as signed mathematical endpoints, matching existing
loop-literal spelling. General parameter default folding, exported interval
analysis and explicit unsigned/narrow storage rules remain unchanged. Checked
wide arithmetic, possible-zero divisors, signed overflow, direction, positive
step and the exact maximum-count contract remain enforced.

A separate bounded dependency walk uses the same native DAG and validates
module-static ownership, i64 types, supported arithmetic and structural
parameter dependencies. Fixed declarations follow their actual parameter-value
expression transitively; neither a default nor a declared interval hides an
ordinary dependency. Both walkers memoize source identities within the module
and reject excessive depth. This preserves the independent native trust boundary
without introducing another expression representation.

## Regression scope and reproduction

The 49 original count fixtures and their strict validator are preserved.
Their runner now accepts an explicit matrix/validator so the new SSA suite
shares process handling, ten-second per-case timeout, exact diagnostic checking,
two executions of every positive, deterministic output and hash recording.
The new 34-case matrix covers all six native integer operations, signed
intermediates, identical versus independent endpoints, nested/shared DAGs,
fixed transitive dependencies, depth, overflow, count/maxima, dynamic inputs,
ordinary parameters, possible-zero divisors and malformed/mixed forms.

The SSA output validator compares source-identified DAG nodes and ordered edges,
parameter references, literal values and generated bound identities independently
of temporary SSA names. Seven Python test methods exercise its preservation and
rejection controls, including renamed temporaries, changed operator/edges/values,
missing operands/definitions, wrong diagnostics and process failures.

The public compound suite retains the first ten construction regression groups.
Its interim unsupported-transport test becomes a positive deterministic transport
test while retaining the direct-parameter attribute witness. Additional tests
cover shared emission, missing/cyclic snapshot graphs, structural dependencies
and configured native success/rejection. Native-dependent branches only count
as executed when `NODAL_NODALC` is set to the freshly built compiler.

After installing the pinned toolchain and building:

```sh
ctest --test-dir out/native/release \
  -R 'nodal.native.(parameter-integer-bounds|iteration-envelope-integration|generate-count-integration|generate-ssa-integration)' \
  --output-on-failure
NODAL_NODALC="$PWD/out/native/release/bin/nodalc" \
  ./mill core.scala.testkit.test.testOnly \
  nodal.internal.testkit.Increment43CompoundBoundTests
python3 -m unittest discover -s tests/compiler -p test_increment43_generate_ssa.py
```

Local validation on October 5 passed the 141-test native suite, the 34-case SSA
matrix within it, 451 compiler Python tests and all 15 public compound groups
with the rebuilt native compiler configured. The predecessor Integer-expression
and count suites also passed. The compound run found and drove the explicit
empty-block repair; the repaired run passes signed, empty and shared-DAG cases.
Pinned Scala and native formatting passed. Local ClangTidy encountered a host
process/library error, so native lint remains a remote qualification obligation.
Actual source/tree identities and remote results belong in the PR checkpoint.
Affected remote qualification is Core CI, configured Bridge20 and Parameters29.
Local checks are repair evidence, not a substitute for those exact-head runs.
No old-head CI credit transfers to this source. Full CI and required independent
review wait for coherent whole-increment implementation.

This stage does not implement generated Verilog-A arrays, shape/index/slice
lowering, per-generated-instance storage or the final source/output witnesses.
The remaining main and descendant requirements, tool-qualification handoff,
review, integration, accepted-evidence closure and completion demonstration
remain required and unchecked. No independent OpenVAF or numerical result is
claimed by internal native acceptance.
