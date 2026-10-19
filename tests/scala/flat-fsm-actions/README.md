# Flat-FSM and action-switch source capture

Focused local Scala 3.8.4 witnesses for the exact owned public entry points:
`StateBuilder.entry/active/exit/on/onWithAction`, `EnumSwitchBuilder.is`, and
`switchEnum`. The harness uses existing pinned compiler/API/plugin artifacts and
writes generated files only beneath the supplied ignored `.work` directory.

Run from the repository root after building the affected API and frontend plugin:

```sh
python tests/scala/flat-fsm-actions/run.py \
  --java /path/to/pinned/java \
  --compiler-classpath /path/to/compiler-and-dependencies \
  --api-classpath /path/to/current/api-classes \
  --plugin-jar /path/to/nodal-constructor-plugin.jar \
  --out-parent .work/flat-fsm-actions
```

Coverage includes checked action lowering and bytecode, nested guards/switches,
ordinary graph wiring, manual enum register actions, Pure transition/selector/case/
evidence operands, named argument evaluation, host effects, forged runtime/kernel
entry points, real separate-JAR helper and enum metadata proofs, unavailable and
incompatible replacement dependencies, no-plugin rejection, zero-effects runtime
attestation checks, and the approved appended `FsmEncoding.Custom` compatibility
change. An unrelated same-named host method remains ordinary Scala.

The shared `DigitalCollectionCallbackPhase` owns all effect profiles and summaries;
`FlatFsmCapture` only identifies exact typed symbols and creates the existing
checked sequential callbacks. Native singleton enum initialization reuses the
same Pure proof: compiler-owned enum factory, inventory, `classOf`, mirror cast
and `HwEnum.derived` forms are recognized by actual symbols/flags, while authored
initializers retain ordinary Pure checks. Successful enum proofs are memoized
within a compilation with their dependency records and proof budget charge. Reuse
also preserves the proved caller/helper depth; deeper callers revalidate. Paired
shallow/deep enum-initializer fixtures exercise this resource boundary.
Missing, stale or effectful separately compiled evidence cannot acquire purity
from a name, enum type, callback shape, or package.

Graph and encoding validity, scoped runtime ownership, and native independent
verification have their own focused source/native tests. These compiler checks
are not CI, exhaustive qualification, simulator certification, or full F56
completion. Run the directly affected behavioral-guards and structural-if
predecessor harnesses when changing the shared validator.
