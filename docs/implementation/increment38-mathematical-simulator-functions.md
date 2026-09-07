# Increment 38 — Mathematical and simulator functions

**Status:** Implementation candidate; final-head qualification, review and evidence closure pending.

The implementation adds a closed version-1 registry for 24 real mathematical functions
and six simulator analysis queries. Generated Scala and native identity/spelling tables
are checked against the canonical JSON. Frontend and native evaluators independently
check arguments, dimensions, finite constants and real domains. Query expressions never
use parameter defaults or forged fold values as simulator-state evidence.

`AnalogMath` supplies abs, min, max, sqrt, hypot, atan2, pow, exp, ln, log10, sin, cos,
tan, asin, acos, atan, sinh, cosh, tanh, asinh, acosh, atanh, floor and ceil. The existing
`AnalysisContext.active` constructs a Boolean query for a closed `AnalysisKind` value.
The public source witnesses exercise continuous expressions, symbolic parameters and
analysis-dependent event-controlled assignments feeding a continuous transition.

## Compiler boundary

Functions and analysis queries are explicitly admitted by the analog-region verifier.
Boolean literals may compose queries, but general digital constants and operations are
not admitted by that exception. Native validators independently check the registry
version, identity, type, arity, dimensions, and provable constant domain.

Direct advisory query annotations cannot establish constness. A forged constant-fold
certificate on an enclosing expression that reads an analysis query is rejected with
`NODAL-ANALOG-FOLD-001`. Queries remain dynamic before and after normalization.

Constant function parents may legitimately be folded by the mandatory backend pipeline.
The native matrix therefore compares constant values with a numerical oracle and checks
target function spellings on symbolic calls, rather than demanding redundant constant
calls in emitted HDL. Analysis-dependent behavior must survive both paths.

## Validation commands

```sh
python3 scripts/generate_analog_function_registry.py --check
python3 -m unittest discover -s tests/compiler -p 'test_increment38.py'
./nodal core scala
./mill -i examples.continuousTimeApi.runMain nodal.increment38fixture.Increment38ConstructionCheck
./mill -i core.scala.testkit.test.runMain nodal.internal.testkit.Increment38MlirCheck /tmp/increment38.mlir
./nodal core native
```

The native CTest matrix checks every registry entry, constant and symbolic calls,
query retention and target names, domain failures, malformed versions/identities,
arity and kind errors, dimension errors, direct and parent folded-attribute forgery,
repeated normalization and rejection without partial HDL. The dedicated workflow also
feeds both public-source witnesses into the same compiler and backend checks and retains
exact source identities, logs, emitted HDL and compiler tools as qualification artifacts.

Candidate recovery runs passed Scala, Python, formatting, architecture and contract
checks and compiled the native compiler. Native qualification exposed an omitted region
allowlist entry and an incorrect constant-call spelling expectation; both have committed
fixes. Those earlier runs are not final-head acceptance evidence.

The roadmap checkbox remains open until exact-head validation, review, integration
and separate evidence closure are complete. No numerical solver execution or general
Verilog-AMS qualification is claimed. The approved scope and its explicit limitations
are in [the design gate](../design-gates/NodalAnalogFunctions-DG-v0.1.md).
