# Foundation 43: native symbolic integer-bound checkpoint

**Status:** Implementation checkpoint; not Foundation 43 acceptance.
**Integration:** Existing Increment 43 branch and PR #139 into `dev`.

Read the [increment readiness decision](increment43-readiness.md), the
[main F-043 checklist](../roadmap/nodal-development-todo.md), and its three
[iteration descendants](../roadmap/lightweight-hierarchy-iteration-v0.1-plan.md).
Those files remain the sole checklist owners. This document adds no completion
state or second progress ledger.

## Owning layer and production integration

`ParameterModel.cpp` already owns parameter declarations, canonical constant
expressions, ranges, structural envelopes, default folding and overrides. The
new read-only integer-bound analysis belongs there, rather than in a separate
range IR, mutable registry or a frontend-only approximation. It is consumed by
`verifyParameterModel` for every `nodal.generate`, including nested regions.
The exported compiler helper accepts an existing MLIR value; it does not add a
Scala API, new operation, alternate parameter representation or emitted syntax.

Default evaluation and legal-setting bounds answer different questions. A
symbolic STEP with default 1 and declared range [0, 2] must not be certified as a
nonzero step. Likewise, a fixed derived parameter whose expression depends on
an overridable parameter must retain that dependency when computing bounds.
The existing default, constraint, envelope, override, structural-class and
independent operation checks remain in place.

The analysis reuses the canonical literal, parameter-reference, expression,
parameter-value and constraint owners. It uses closed outer intervals, checked
128-bit intermediates and signed 64-bit endpoint metadata. Addition,
subtraction, multiplication, division, remainder and negation are supported
when their whole inferred interval fits both the host endpoint representation
and the declared storage type. Division/remainder reject a possibly zero divisor
and preserve the existing signed-minimum/-1 rejection contract. Identical-SSA
subtraction is exact; other unproved correlations are not assumed.

A symbolic parameter needs an explicit finite range. Inclusive/exclusive range
endpoints and intersections are retained. Varying range endpoints use their
outer bounds, not defaults or inner bounds. Fixed parameters follow the real
`parameter_value` expression when present. Exclusion holes can shrink a set but
are not used to certify a zero-containing outer interval. Cycles, unresolved
ownership, dynamic origins, incompatible types/units and unsupported proofs
fail closed. No bound is clamped, wrapped or inferred from an overridable
default. Explicit unsigned bound literals are not sign-reinterpreted; established
signless loop-attribute spelling, including `-1 : i64`, is retained.

Caches are local to one analysis/module and keyed by the existing value and
parameter identities. Shared subexpressions are analyzed once rather than
expanded into a tree. The combined active dependency depth is limited to 512;
over-depth input is rejected, not accepted with a truncated result. This is a
bounded native proof profile, not a universal scalability or speedup claim.

## Generated-domain checks and compatibility

The production verifier checks typed integer literal/reference attributes,
structural parameter ownership, finite symbolic intervals, a nonzero
single-sign step, and compatible direction across every setting in the proven
outer domain. It traverses nested generated regions with the enclosing module's
canonical parameter owner. An unresolved or ordinary parameter in a nested
bound is no longer missed by the old direct-body-only check.

The existing native directional-loop profile, including negative steps and
same-bound empty domains, is retained. This is not the final public `hdlRange`
contract: its preferred positive-step half-open capture, count limits, shaped
results, generated objects and target-specific legality still require their
own implementation. A conservative rejection of an unknown correlated envelope
is not proof that the user's mathematics is impossible.

New diagnostics are registered in the existing catalog:

- `NODAL-ITERATION-043-001`: finite typed integer bounds cannot be proved.
- `NODAL-ITERATION-043-002`: a symbolic step can be zero or change sign.
- `NODAL-ITERATION-043-003`: direction is not proved for the whole legal domain.

The existing `NODAL-PARAMETER-STRUCTURAL-001` remains the owner for an illegal
structural reference. No old diagnostic, verifier or predecessor test is removed.

## Validation and retained limits

The new C++ unit target exercises interval results and unchanged input IR for
finite/open/intersected/dependent ranges, fixed expressions, arithmetic, signed
and unsigned limits, unbounded/cyclic/invalid inputs, shared DAGs and the depth
limit. The real-compiler Python matrix checks 30 accepted/rejected generated
models, including nested scopes and forged metadata. Its 13 positive cases run
twice, preserve symbolic bound references and generated-region counts, and must
produce identical normalized output. Negative cases require normal compiler
exit 1 with the expected diagnostic; timeout, process failure or crashes do not
qualify as expected rejection. Source, output, error and repeated-run digests
are recorded in the requested evidence directory.

Both tests are registered in the existing CTest suite. Reproduce after building
the repository-pinned native toolchain:

```sh
ctest --test-dir out/native/release \
  -R 'nodal.native.(parameter-integer-bounds|iteration-envelope-integration)' \
  --output-on-failure
python3 tests/compiler/fixtures/increment43/run_integer_bounds_matrix.py \
  --nodalc out/native/release/bin/nodalc \
  --work-dir out/increment43-integer-bounds-evidence
```

Pre-repair reproduction used the retained accepted native compiler from F-160's
source/target artifact, not a rebuilt candidate. All 13 valid matrix cases were
accepted deterministically; all 17 newly invalid cases were also incorrectly
accepted. That is a failing-baseline reproduction, not candidate qualification.
The unit fixture strings were also parsed independently with that existing
compiler before publication; parsing does not execute the new bound analysis.
The unchanged 444-test compiler Python suite and existing Increment 29 and
architecture checks passed locally. Compiled candidate results, exact heads,
actual run/job IDs and review dispositions belong in the current PR checkpoint;
this document does not predeclare remote success.

The affected targeted set is Core CI plus Increment 29 parameter/range/unit
qualification. Core includes the new CTests and inherited native/Scala coverage;
Increment 29 retains its distinct folding, constraints, envelope, override and
native-rendering witnesses. Already-passing runs on the preceding static-domain
head are historical evidence only for a new source head. Full CI still waits
for coherent whole-increment implementation and completed required review.

This checkpoint changes native validation, not HDL generation. It is not an
actual Scala-to-array/generated-Verilog-A demonstration. Public symbolic range
capture, fixed/symbolic analog shapes and indexing/slicing, per-generated-instance
storage, bridge/source-index propagation, analog operator/event restrictions,
legal emitted arrays/generation, scale tests and the 48/49/52 tool handoff remain
required F-043 work. No independent OpenVAF, numerical simulation, synthesis,
whole-child completion, reviewed approval, merge or accepted-evidence closure is
claimed here.

## Shared frontend interval and count checkpoint - 2026-10-03

The next bounded change extends the existing private `IterationDomain`, rather
than adding another source-expression graph or parameter registry. Its closed
interval endpoints now retain signed 64-bit values. Checked addition,
subtraction, multiplication, division and negation use exact `BigInt`
intermediates, reject a possible zero divisor and reject any result outside the
signed endpoint range. Division truncates toward zero. Equal intervals do not
establish identity of two independently overridable parameters.

The existing production structural-domain proof consumes the same wide
subtraction primitive to bound its distance. Concrete `analogRepeat` and
structural capture now share one positive-trip-count check. The existing
32-bit count limit, explicit maximum enforcement, positive-step policy,
rejection order, once-only body capture and wide concrete exit value remain
unchanged. A proof distance can exceed signed 64 bits even when its count is
small; that does not make overflowing signed expression arithmetic legal.
These are bounded arithmetic operations, never iteration or lane allocation.

This arithmetic substrate is not complete compound `hdlRange` support. The
current public construction path still accepts literals and directly bounded
owned Integer parameters. Capturing compound `KernelExpr` bounds, retaining
reachable canonical DAG nodes, checking dependencies and lowering them through
the bridge/native boundary remain required. The wider private interval type
does not introduce a new public Long-literal API or certify target induction
storage. Native `ParameterModel` stays the independent semantic authority; no
native verifier, diagnostic, expected result or signed/unsigned distinction is
removed. No user expression is replaced by a default or a made-up parameter.

`IterationBoundArithmeticTests` independently enumerates every concrete result
for small operand intervals and every legal small structural domain. Separate
controls cover signed extremes, precision above the floating-point integer
limit, zero divisors, malformed arities, independent equal-range parameters,
maximum/count overflow and a distance wider than 64 bits. Existing public
repetition, range-capture and configured Integer/native suites remain intact.
The focused changed-source qualification is Core CI plus the configured
Increment 20 bridge workflow, including their inherited control-flow tests.
The distinct Increment 34 PR-only witness is not dispatchable and remains a
separate full-phase obligation, not claimed as executed by these workflows.
The earlier native-bound checkpoint's Increment 29 qualification is preserved
as historical evidence, not transferred to a new source head. Pinned execution
results and any required repairs belong in the live PR/continuation checkpoint;
this paragraph records the design and test scope, not a passing CI receipt.
