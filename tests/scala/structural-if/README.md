# Structural `if` source witnesses

Run the focused compiler checks with already-built artifacts and the pinned JDK 25 / Scala 3.8.4:

```sh
python3 tests/scala/structural-if/run.py \
  --java "$JAVA" --compiler-classpath "$COMPILER_CLASSPATH" \
  --api-classpath "$API_CLASSPATH" --plugin-jar "$PLUGIN_JAR" \
  --out-parent .work/increment55/structural-if-callbacks
```

The harness does not download tools, run CI, invoke Mill, or mutate supplied build
outputs. It retains exact commands and logs under a fresh ignored `.work` directory.
A compiler crash, an unrelated type failure, or failure in another source does not
satisfy a negative. Positive transformed trees pass the compiler's phase checker.

The production constructor plugin's existing callback phase owns this capture.
`StructuralIfCapture` only recognizes exact typed syntax and builds trees; it does
not supply a second effect checker, call-graph walker or summary registry. Both
arms and the predicate operands, including explicit equality evidence, use the
existing closed callback validation before `checked0` is emitted. Statement and
read-only value callbacks have a separate private carrier from collection/loop
callbacks. Runtime construction still independently checks both attestations,
static ownership, exact ABI, lexical lifetime and transaction integrity.

## Selected checks

- All six Integer comparisons; exact inherited Any equality with two statically
  Integer expressions; direct `&&`, `||`, `!`; nested/else-if/missing-else shapes;
  real hdlRange syntax; typed val, method-result, and ascription propagation;
  branch-local immutable values and helpers; a union-typed Integer order RHS.
- Existing generated analog temperature, nominal-temperature and time queries retain their
  exact range-profile admission; the same queries remain unavailable in digital structural arms.
- Real/UInt/SInt comparison compatibility; ordinary host Boolean control; legacy
  Expr reference equality outside captured If; unrelated same-spelling equality
  methods stay ordinary Scala. The host runtime witness executes one arm only.
- Inferred Signal joins reject with `NODAL-STRUCTURAL-055-001`, including a writable
  selection from the conditional result. No first-arm return or Signal cast is
  used to manufacture a joined value.
- Print, host mutation, lazy reads, recursive helpers, singleton initialization,
  effectful predicates/equality evidence, and branch allocation reject statically.
  Predicate operands and equality evidence use the shared read-only profile, even
  when branch bodies permit writes. Direct writes and a separately compiled JAR
  helper with a write-capable summary are rejected in these predicate positions.
  Compile-time marker aliases, Boolean-returning marker helpers, mixed host
  predicates, Int-left comparisons and Real/Bool Int-RHS equality also reject.
- Authored calls to structural capture/attestation methods reject before the
  compiler injects its own calls. These private carriers and annotations are a
  normal compiler ABI, not a sandbox against forged bytecode or reflection.

Separate helper tests compile the base, producer and consumer independently. The
single `ConstructorEffectSummary` protocol now also describes closed, first-order
Expr/Unit-returning helpers and singleton initialization: profile 4 is read-only,
and profile 8 permits the bounded structural branch/write effects. Existing
allocation profiles 1/3 cannot attest a structural branch. Signature, kind, version,
profile, and transitive proof dependencies are revalidated against the supplied
classes. Replacing the base helper with an uninstrumented or genuinely impure
same-signature implementation invalidates the already-compiled producer proof.
There is no trust in allocation metadata, method names, or unavailable code alone.

## Explicit boundary

The new Int-RHS markers require the matching API and plugin. Unchanged inherited
Expr-RHS equality still compiles without the plugin and retains historical host
reference-equality semantics; `no-plugin/Legacy.scala` preserves that documented
exception. Boolean values previously obtained from that legacy equality are host
Booleans. Marker aliases are not a predicate carrier.

Parameter-only predicates also work inside the existing bounded procedural loop
profile through its actual lexical scopes. Procedural induction values remain
selectors, including explicit arm captures; they cannot become structural predicates
or static width facts. This does not admit state, hierarchy or nested procedural loops.

The positive syntax amalgam is not elaborated as a full driver-coverage module.
These tests establish frontend typing, effect checking and separate helper proof
transport. The testkit structural-if suite and native owners separately establish
real captures, both retained alternatives, exact yields, generated-index stage,
coverage/conflicts/cycles, and bridge verification. This harness does not claim
HDL generation, target lowering, full F55 completion or exhaustive qualification.
