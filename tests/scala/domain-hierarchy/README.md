# Domain-aware hierarchy target witnesses

`Increment58HierarchyTargetReplay` exports actual public Scala source from
`core/scala/testkit/test/src/nodal_external/Increment58HierarchyTargetSource.scala`.
The maintained runner gates those inputs, lowers them through Nodal to CIRCT,
exports SystemVerilog with the pinned `firtool`, and simulates the generated DUT
with Icarus. Only the testbench is handwritten.

The five selected cases are:

- `shared`: two different physical clocks with identical material domain
  semantics reuse one child module, while state and reset remain independent.
- `nested`: an intermediate module forwards its formal domain and data to a
  grandchild without adding a clock or register.
- `material`: rising/active-high and falling/active-low synchronous domains
  require deterministic material child variants.
- `resetless`: a resetless uninitialized child acquires no reset or fabricated
  startup value; a legal edge establishes its state.
- `inherited-release`: the retained F57 public source forwards an
  asynchronous-assert/two-edge synchronous-release reset through two child
  levels. The grandchild must use the original release with no extra latency
  or duplicate synchronizer.

The cases check unspecified startup, active-edge updates, hold between edges,
independent siblings and actual reset behavior where applicable. The runner checks real target
instances and module counts, rather than accepting a flattened substitute.
These are focused digital witnesses, not analog, physical timing, synthesis or
equivalence certification. The presence of this runner is not a passing result;
executed results belong in the increment readiness record.

Reproduce from the repository root after building the focused compiler target:

```sh
source .work/tools/env.sh
export JAVA_HOME="$PWD/.work/cache/coursier/arc/https/cdn.azul.com/zulu/bin/zulu25.32.17-ca-jdk25.0.2-linux_x64.tar.gz/zulu25.32.17-ca-jdk25.0.2-linux_x64"
export PATH="$JAVA_HOME/bin:$PATH"
flock .work/f58-mill.lock sh .work/cache/mill/download/1.1.7 \
  --no-daemon core.scala.testkit.test.runMain \
  nodal.internal.testkit.Increment58HierarchyTargetReplay \
  .work/f58-hierarchy-source
python3 tests/scala/domain-hierarchy/run_target.py \
  --input .work/f58-hierarchy-source --out .work/f58-hierarchy-target \
  --nodalc .work/native-build/bin/nodalc \
  --firtool .work/circt-toolchain/bin/firtool \
  --iverilog .work/tools/iverilog/bin/iverilog \
  --vvp .work/tools/iverilog/bin/vvp
```

Use `--case shared` (or another named case) to select a narrower replay.
The ignored output directory contains command lines, logs, actual target files,
testbenches and a manifest with input and output hashes. No generated outputs
are committed.

## Symbolic whole-value boundaries

`Increment58SymbolicHierarchyReplay` exports the maintained public source
`nodal_external/Increment58SymbolicHierarchySource.scala`. Its four cases are
`scalar-early`, `scalar-late`, `vector-early` and `vector-late`. Early/late
select whether overrides are supplied before or after port selection.
The Scala width and extent remain symbolic; no preferred default is used as
shape identity or as an emitted specialization.

Run the same export command with that replay class and a separate `.work`
directory. Pass its output to the same target runner with all four explicit
`--case` arguments. These cases are opt-in and do not change the five-case
default above. The testbench instantiates the actual generated root twice with
different legal parameter values, checks elaborated port widths and compares
complete input/output bit patterns. It requires exactly two reusable module
bodies and real hierarchical instances. This is a whole-value scalar/packed-Vec
witness, not generated Area, symbolic operation, Struct adapter or Interface
target qualification.
