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

## Ordinary Interface boundaries

`Increment58InterfaceTargetReplay` exports four opt-in cases:
`interface-array-early`, `interface-array-late`, `interface-payload-early` and
`interface-payload-late`. The actual constructors are `HierarchyArrayParent` in
`Increment58InterfaceArrayTests.scala` and the external `HierarchyPayloadParent`
in `Increment58InterfacePayloadSource.scala`. Early/late orders the exact child
parameter overrides around projection, without modifying the source semantics.

Use the same replay command with that class, then invoke this runner with the four
explicit `--case` arguments. Array cases keep the legacy packed scalar-Vec ABI;
payload cases explicitly request `boundary-abi=named-fields`. The runner records
that choice and input/compiler/output hashes, requires two real reusable modules,
and checks retained selected roles, directions and inverse shape metadata.

Each generated root is instantiated at two legal parameter settings. Independent
valid, tag, payload-lane and outer-array patterns check member separation and
four-state forwarding; separate observations of the actual child's input pins
check the boundary-facing direction rather than accepting symmetric output
pass-through alone. Only the testbench is handwritten. These witnesses do not
claim resolved/inout, analog/control Interface, generated-loop or fixed-Struct CDC
support under the named-field profile.

## Fixed Struct/Vec CDC with named-field pins

`Increment58NamedCdcReplay` exports `gray`, `handshake` and `fifo` from the actual
public source `nodal_external/Increment58NamedCdcSource.scala`. Use the replay
command above with this class and `.work/f58-named-cdc-source`, then run:

```sh
python3 tests/scala/domain-hierarchy/run_named_cdc_target.py \
  --input .work/f58-named-cdc-source --out .work/f58-named-cdc-target \
  --nodalc .work/native-build/bin/nodalc \
  --firtool .work/circt-toolchain/bin/firtool \
  --iverilog .work/tools/iverilog/bin/iverilog \
  --vvp .work/tools/iverilog/bin/vvp
```

Use `--profile gray`, `handshake` or `fifo` for a narrower run. The runner requests
`boundary-abi=named-fields` explicitly. Its 19-bit payload contains two cells,
each with Bool, two UInt3 samples and SInt2, followed by a Bool marker. Whole
payload outputs and separate typed observations check every logical field using
asymmetric patterns, independently of the physical leaf-vector pin layout.
Startup remains unspecified. Gray single-bit transitions, handshake snapshots,
FIFO capacity/stall/order/wrap/reset and independent source/destination clocks
exercise the existing packed CDC executor, not a replacement implementation.

For example, the maintained Scala includes:

```scala
case "gray" => Cdc.gray(Gray(sampled.get), destination, 2)
case "handshake" => Cdc.handshake(sampled.get, destination)
```

The actual generated Gray SystemVerilog contains this inverse mapping (location
comments omitted from the excerpt only):

```systemverilog
wire [1:0] _GEN_1 = {__nodal_gray_0_stage_1[9], __nodal_gray_0_stage_1[0]};
wire [11:0] _GEN_2 = {__nodal_gray_0_stage_1[15:10], __nodal_gray_0_stage_1[6:1]};
wire [3:0] _GEN_3 = {__nodal_gray_0_stage_1[17:16], __nodal_gray_0_stage_1[8:7]};
```

This comes from the emitted `gray.sv`, not a handwritten DUT. Logical cells remain
row-major even though each physical output groups the same named leaf across
cells. Native target tests also pin the exact logical offsets, so a consistently
wrong packing/unpacking pair cannot be hidden by whole-value round-trip tests.

The runner retains generated SystemVerilog byte-for-byte and keeps assertions.
For the pinned firtool 1.154.0/Icarus consumer pair it uses the official
`--lowering-options=disallowPackedStructAssignments,disallowLocalVariables` export
configuration, recorded with the exact tool version and hashes. Earlier attempts
without these options exposed Icarus syntax/lifetime limitations, not a passing
simulation. Illegal two-bit Gray transitions and a FIFO stalled-payload mutation
must fail with the actual retained monitor; their receipts are explicitly marked
`expected-monitor-failure`, separate from three legal transport passes.

This is fixed-width named-field adaptation and fixed contiguous field/index
observation only. Symbolic CDC payloads, dynamic/noncontiguous selection and
physical timing/electrical certification are not claimed. The output directory
contains actual gated input, CIRCT, unmodified SV, handwritten testbenches, exact
commands and hashes. No generated artifacts are committed.

## Scalar resolved pins and true external declarations

The actual public source is
`core/scala/testkit/test/src/nodal_external/Increment58ExternalInoutSource.scala`.
The maintained native source selector writes eight full-gate inputs, of which
seven have the bounded scalar physical target profile. With the JDK/tool setup
above, reproduce the source inputs and four-state target checks as follows:

```sh
export NODAL_ROOT="$PWD"
export NODAL_NODALC="$PWD/.work/native-build/bin/nodalc"
flock .work/f58-mill.lock sh .work/cache/mill/download/1.1.7 \
  --no-daemon core.scala.testkit.test.testOnly \
  nodal.internal.testkit.Increment58ExternalInoutNativeTests
python3 tests/scala/domain-hierarchy/run_resolved_target.py \
  --input .work/f58-external-inout/native --out .work/f58-resolved-target-run \
  --source core/scala/testkit/test/src/nodal_external/Increment58ExternalInoutSource.scala \
  --nodalc .work/native-build/bin/nodalc \
  --firtool .work/circt-toolchain/bin/firtool \
  --iverilog .work/tools/iverilog/bin/iverilog \
  --vvp .work/tools/iverilog/bin/vvp
```

Use `--profiles read drive` (or another subset) for a narrower run. The seven
profiles are `read`, `drive`, `open-drain`, `repeated`, `independent`, `local-alias`
and `interface-fixed`. They preserve actual inout ports, unknown external
contributions and declared local pin identity. The runner checks both directions,
all four-bit patterns, Z release, opposing/partial X, open-drain pullups, and
independence of two occurrences of one reused external declaration. The fixed
Interface profile retains the nested `bus.pad` member and canonical `connect`
role. The `local-tag` source input contains a split and is excluded from this
initial scalar-pin runner; the fixed split continuation below separately checks
original driver sidebands.

The emitted DUT is never modified. A separate handwritten external model is the
opaque environment for true `hw.module.extern` declarations, not a generated or
substituted implementation. Multiple proven local public pin aliases use a bounded
typed standard `tran` primitive because the pinned Icarus rejects SV `alias`.
For example, actual `local-alias` output contains:

```systemverilog
tran __nodal_resolved_0_alias_1[3:0] (first, second);
```

No paired continuous assignment or ordinary input/output pair substitutes for
bidirectionality. The receipt records exact physical pins, ABI, tool versions and
hashes, every command and output hash, and an unchanged-SV check. Native target
controls independently cover complete unaccessed pins, external-root and profile
refusals, generated-lane refusal, opaque uniqueness rejection and 64 separately
represented reused external instances. Symbolic/array resolved payloads,
internal/hierarchy-pass-through placements, generated resolved pins and exact
symbolic/generated split sideband routes remain required continuations. The
fixed sideband consumer is checked separately below.

## Fixed local and hierarchy split sidebands

The maintained standalone source is
`fixtures/fixed-split/Increment58FixedSplitSource.scala`, with its adjacent replay.
It deliberately uses fixed four-bit data; the original symbolic split fixtures
are not modified or relabeled as fixed acceptance. With the shared API/bridge and
constructor plugin already built, the runner can strictly compile only these
fixtures into private output and perform all five four-state checks:

```sh
python3 tests/scala/domain-hierarchy/run_split_target.py \
  --replay --java "$JAVA_HOME/bin/java" \
  --source tests/scala/domain-hierarchy/fixtures/fixed-split/Increment58FixedSplitSource.scala \
  --out .work/f58-split-target-run \
  --nodalc .work/native-build/bin/nodalc \
  --firtool .work/circt-toolchain/bin/firtool \
  --iverilog .work/tools/iverilog/bin/iverilog \
  --vvp .work/tools/iverilog/bin/vvp
```

With `--replay`, the runner uses freshly generated private
`source-replay` inputs. Without it, `--input` selects existing exact replay files
at `<profile>/input.mlir`. `--profiles down sibling` narrows the run. Replays use
actual shared classpath metadata and serialize on the existing Mill lock; they
do not rebuild or overwrite the shared classes. Direct compilation uses the
pinned compiler's matching Scala standard library rather than an older transitive
test-runtime jar; exact classpath inputs and that choice are recorded.

Profiles `down`, `up`, and `sibling` test both sideband directions and their least
common ancestor. `reused` drives two occurrences independently over all 256 data
pairs. `variants` reuses one original two-pin reader with one joined-driver
occurrence and one independently driven occurrence. The expected physical body
counts are 2, 2, 3, 2, and 3 respectively, not per-value cloning. In all cases the
original data/enable remain visible when the actual pin is released, externally
driven, or contended. Separately observed reads and public inout pins must agree
with an independent four-state reference net. The generated DUT is never edited.

The actual `Increment58FixedSplitReader` uses these public statements:

```scala
val view = pad.split
written := view.write
enabled := view.enable
observed := view.read
```

With the driver in its parent, the actual emitted `reader` body keeps read and
sidebands distinct:

```systemverilog
assign enabled = __nodal_split_0_enable;
assign observed = pad;
assign written = __nodal_split_0_data;
```

The original driver data remains available even when `observed` is Z or X.
Ordinary
sideband ports pass this data/enable through real instances with no added
registers or latency. Receipts record source/plugin/classpath, native compiler,
exporter, simulator, commands, output hashes, exact relative module counts, and
unchanged-SV checks. Symbolic/array payloads and generated selection-qualified
physical split routes are separate required continuations.

## Bounded symbolic resolved scalar transport

The same `run_resolved_target.py` also accepts five explicit symbolic profiles;
its default seven fixed profiles are unchanged. Use the original public
`Increment58TypedResolvedMemberSource` read/drive replays and
`Increment58ResolvedSplitSource` Interface/child-driver/parent-driver replays,
without replacing their symbolic types by defaults or maximum widths. Place the
exact replays at `symbolic-read.mlir`, `symbolic-drive.mlir`,
`symbolic-split-interface.mlir`, `symbolic-split-child-driver.mlir` and
`symbolic-split-parent-driver.mlir` in the input directory.

The pinned base CIRCT omits recursive InOut parameter substitution. Install and
select the checksum-owned
[managed repair profile](../../../toolchains/patches/circt-inout-parameters-v1/README.md)
for the Nodal build and its downstream firtool. An unrepaired linked consumer
fails target preflight with the exact required profile name, even if a stale
manifest claims otherwise. Existing fixed profiles still work with the base.

```sh
python3 tests/scala/domain-hierarchy/run_resolved_target.py \
  --input .work/symbolic-resolved-replays --out .work/symbolic-resolved-target \
  --nodalc .work/native-repaired/bin/nodalc \
  --firtool /path/to/repaired-prefix/bin/firtool \
  --iverilog .work/tools/iverilog/bin/iverilog --vvp .work/tools/iverilog/bin/vvp \
  --profiles symbolic-read symbolic-drive symbolic-split-interface \
    symbolic-split-child-driver symbolic-split-parent-driver
```

Each profile runs widths 2, 7 and 8 against the same emitted parameterized body.
The typed Interface profiles deliberately map child `width` to parent
`actualWidth` and keep an independent sibling width. Their benches drive both
physical aliases, check the nested `bus.pad` identity, and verify the independent
pin is not shorted. The split profiles retain original data/enable during external
Z/X interference rather than substituting the resolved read. All use independent
four-state reference nets and the unchanged emitted DUT. The receipt includes
input/output/tool hashes, consumer manifest identity, explicit runtime library
selection when present, parameter settings and commands.

The maintained `Increment58ResolvedSplitSource.scala` supplies the actual child
storage type and conditional driver (excerpt from `Increment58ResolvedSplitLeaf`):

```scala
val width = param(4.integer, range = 2 to 8)
val data = in(UInt(width))
val enable = in(Bool)
val pad = digitalInout(
  UInt(width), DriveMode.pushPull,
  InoutPlacement.TopLevelPin, ResolutionProfile.PortableBoundaryOnly, "pad"
)
if ownDriver then pad.drive(data, enable)
```

The parent binds `child.param(_.width, width)` and observes
`selected.split.write` and `selected.split.enable`. For the child-driver profile,
the actual SystemVerilog backend emits symbolic carriers and distinct sidebands:

```systemverilog
assign pad = enable ? data : 'z;
assign __nodal_split_0_data = data;
assign __nodal_split_0_enable = enable;
```

The initial symbolic continuation remains scalar Bits/UInt/SInt with canonical
finite width proofs. Arrays, selection-qualified generated physical routes,
internal/hierarchy-pass-through placements and broader electrical profiles are
still required continuations, not implicit support from a symbolic width.
