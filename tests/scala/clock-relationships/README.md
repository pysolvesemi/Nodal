# Clock relationship target witnesses

`run_target.py` consumes the actual public Scala replay emitted by
`nodal.internal.testkit.Increment57RelationshipReplay` from
`core/scala/testkit/test/src/nodal_external/Increment57RelationshipSource.scala`.
It lowers through production native Nodal, official CIRCT `lower-seq-to-sv`, and
firtool, then executes the unmodified generated SystemVerilog in Icarus. There
is no replacement DUT, generated-HDL rewrite, forced state, or clock aliasing.

The seven Same waveform profiles cover both active-edge conventions and reset
polarities, exact structural clock identity, transitive external Same chains,
and shaped clock/reset projections. Every externally related domain receives
matching active edges, with deliberately different inactive duty phases. The
active-edge period is exactly 10 ns, matching the declared 100 MHz root. The
scoreboard checks source-defined old-state direct sampling, combined Same roots,
transitive sampling, genuine startup reset, asynchronous assertion during an
active pulse, and each retained two-stage reset chain through re-release.
Inactive edges and the unused shaped reset lane must not change state.

Target inspection additionally checks each register's actual clock owner and the
exact source/reset register set. It verifies separate physical clocks, domain
references, reset chains, typed obligations and source locations survive.
Relationships introduce no clocks, storage, or runtime contract detector. The
five other category profiles are target-only classification/obligation checks:
Ratio (including exact authored parameters), Synchronous, MutuallyExclusive,
Asynchronous and Unknown. They do not sample foreign data.

External Same, timing and operating-mode exclusion are environment assumptions,
not runtime assertion certification. The harness never violates an external
contract and calls that an unsupported runtime negative. Source/native causal
negatives separately reject unauthorized direct sampling. Equal frequency,
Ratio(1,1,0), and Synchronous(true) are not Same permission. Legal common edges
do not alone prove independently clocked reset ownership; the target structural
checks retain and verify the separate chains and owners as well.
The mixed/nested expression uses the old root AND NOT the old child, producing
both high and low witnesses rather than a vacuous always-low conjunction.

Generate replay, then run against a completed immutable compiler copy:

```sh
source .work/tools/env.sh
MILL_OUTPUT_DIR=.work/reset-source-mill sh .work/cache/mill/download/1.1.7 \
  --no-daemon core.scala.testkit.test.runMain \
  nodal.internal.testkit.Increment57RelationshipReplay .work/relationship-source-replay

# Only copy after the build is finished; do not race a concurrent compiler build.
mkdir -p .work/relationship-compiler
cp .work/native-build/bin/nodalc .work/relationship-compiler/nodalc
chmod a-w .work/relationship-compiler/nodalc
python3 tests/scala/clock-relationships/run_target.py \
  --input .work/relationship-source-replay --out .work/relationship-target \
  --nodalc .work/relationship-compiler/nodalc \
  --firtool .work/circt-toolchain/bin/firtool \
  --iverilog .work/tools/iverilog/bin/iverilog --vvp .work/tools/iverilog/bin/vvp
```

`--profile NAME` selects one replay profile and can be repeated. `--circt-opt`
defaults to the binary beside firtool. All generated artifacts must stay in this
repository's ignored `.work`: exact replay copies, CIRCT/target IR, SystemVerilog,
testbenches, VCDs, per-command logs, and the manifest. The manifest records source
revision/worktree state, hashes of compiler/Scala source files, exact input and
tool hashes, retained obligations, target/testbench/VCD hashes and outcomes. Tool
hashes are rechecked at completion to detect a compiler changed during the run.

These are focused local prototype checks, not CI, broad qualification, physical
metastability/setup/hold proof, or certification of external clock obligations.
