# Bounded iteration callback source witnesses

Run the shared typed-callback harness with already-built API and plugin artifacts:

```sh
python3 tests/scala/digital-collections/run.py --profile iterations \
  --java "$JAVA" --compiler-classpath "$COMPILER_CLASSPATH" \
  --api-classpath "$API_CLASSPATH" --plugin-jar "$PLUGIN_JAR" \
  --out-parent .work/increment55/iteration-callbacks
```

The default runner profile still checks the existing collection fixtures. Neither
profile downloads tools, starts CI, invokes Mill, or writes build outputs.

These fixtures cover exact explicit-loop assignments, index and writable selectors,
pure nested collections, mixed analog/digital source ranges, existing nested analog
ranges, inspected child constructors and defaults, transfer/noise and procedural
analog bodies. Source-located negatives cover host effects, mutable/lazy captures,
helper/default/singleton effects, unavailable helpers/constructors, nested effectful
iteration, assignment inside a pure collection, and authored attestation factories.
The harness independently checks bounded traversal, private induction construction,
and rejection of uninstrumented zero-count loop/range callbacks before host effects.

These are frontend attestation witnesses. They do not replace construction-session,
bridge, native semantic, dependency, coverage, or target-emission checks. Internal
forged construction fixtures retain their original semantic assertions by invoking
the internal capture owner directly, separately from the public callback boundary.

Separate compilation uses the existing constructor capture ABI plus an independent,
compiler-produced ConstructorEffectSummary v1. The producer validates actual typed
initializers with the same bounded callback walker, without running them. It exports
only direct supported Module constructors, their literal parameter-default getters,
closed Module-returning factories, and checked singleton initialization. The summary
records its kind, exact symbol/type signature, and structural-range or full-range
profile, plus a bounded set of imported effect-proof dependencies. Consumers resolve
those exact dependency symbols against the currently supplied JARs, cache checked
proofs and reject cycles, missing/stale proofs or profile changes. They independently
validate those fields, receiver initialization and
actual arguments before attesting a callback. ConstructorSchema alone proves only
allocation capture and never authorizes initialization effects.

The fixture runner builds definitions.jar, factory.jar and consumer.jar in distinct
compiler invocations, without producer source in the later invocations. It includes
actual range allocation through the factory at runtime, direct/defaulted/actual-Param
constructors, and hostile producer/factory/singleton, authored-marker, missing,
stale-version, wrong-kind, wrong-signature, cyclic and unsupported-profile negatives.
A safe factory JAR is also reused against same-signature allocation-only and impure
replacement definition JARs; both must fail before the callback runs.

Compatibility migration: rebuild producer definitions and non-inline factories with
the current plugin before using them inside attested ranges. Old allocation-only
JARs remain usable outside attested callbacks through the existing capture protocol,
but inside a range they diagnose missing effect summaries rather than running a
callback to discover whether it was safe. A missing or unsupported summary never
falls back to trusting the constructor schema, a package or a method spelling.

Same-compilation immutable callbacks and closed helpers retain actual-body checking.
General separately compiled helpers and Function callbacks/aliases remain unsupported
inside attested callbacks; this summary ABI does not claim their effects. A library
may compile its own loop/range call with the plugin, whose checked carrier is then
validated by the unchanged runtime gate. Uninstrumented calls still reject before
host effects, including empty domains. These compiler ABIs assume producer code is
built with the plugin; they are not a sandbox against hand-forged binary metadata.
