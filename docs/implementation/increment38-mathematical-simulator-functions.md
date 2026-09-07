# Increment 38 — Mathematical and simulator functions

**Status:** Implementation candidate; executable qualification and evidence closure pending.

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
arity and kind errors, dimension errors, folded-attribute forgery, repeated
normalization and rejection without partial HDL. The dedicated workflow also feeds
both public-source witnesses into the same compiler and backend checks.

The roadmap checkbox remains open until exact-head validation, review, integration
and separate evidence closure are complete. No numerical solver execution or general
Verilog-AMS qualification is claimed. The approved scope and its explicit limitations
are in [the design gate](../design-gates/NodalAnalogFunctions-DG-v0.1.md).
