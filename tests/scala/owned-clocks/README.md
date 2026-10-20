# Owned gate and glitchless mux target witnesses

`Increment57OwnedClockReplay` captures the real public Scala examples in
`core/scala/testkit/test/src/nodal_external/Increment57OwnedClockSource.scala`.
`run_target.py` lowers those bridge files through the production native CIRCT
path, exports SystemVerilog with firtool, and simulates the unmodified target
with Icarus. There is no handwritten replacement DUT or edited generated HDL.

Pass `--input`, `--out`, `--nodalc`, `--firtool`, `--iverilog` and `--vvp`.
`--circt-opt` defaults to the `circt-opt` binary beside firtool.
`--profile NAME` optionally selects one named actual replay profile and may be
repeated. The default selection is twelve focused source profiles: four gate
edge/reset-polarity combinations, two resetless gates, four mux edge/polarity
combinations, nested gate parents, and shaped physical clock/reset projections.
All generated source/target files, testbenches, per-command logs, and the manifest
of source/target/testbench hashes stay under ignored root `.work`.

For example, after generating the actual Scala replay into `.work/owned-clock-source-replay`:

```sh
python3 tests/scala/owned-clocks/run_target.py \
  --input .work/owned-clock-source-replay --out .work/owned-clock-target \
  --nodalc .work/native-build/bin/nodalc \
  --firtool .work/circt-toolchain/bin/firtool \
  --iverilog .work/tools/iverilog/bin/iverilog --vvp .work/tools/iverilog/bin/vvp
```

The source consumer's `done` output establishes independent output-clock reset
release and post-release operation. Physical waveforms are measured on retained
internal `__nodal_clock_<ordinal>_output` wires, not a new Clock output API. Mux
`branch0_enable`/`branch1_enable` registers expose the actual gate state, not
requests or prematurely returned acknowledgement. Source identity, parent/domain
identity and typed obligation text must survive export.

Gate cases change enable and test-enable during both physical phases, disable
and restart, and establish resetless startup by explicitly driving the unknown
clock into a complete known inactive phase. This does not rely on time-zero
declaration initialization triggering a Verilog sensitivity list. Active-phase
control changes cannot create or truncate a pulse. Both physical edge conventions are checked, including idle-high falling-edge output.
A gate must not mask its clock on reset. A gate with known controls does not
invent a reset-epoch prerequisite; resetless startup remains supported.

Mux cases exercise serialized break-before-make with unequal and simultaneous
clock edges, both switching directions, a stopped first-parent controller,
a candidate clock stopped inactive, and a selector reversal while that branch's
request is in flight. WAIT_ON must finish the outstanding request before changing
branches. A selected parent stopped active must retain its full pulse and block
handover until it resumes; cutting it is not a liveness shortcut. Every measured
output active edge must coincide with its enabled parent's active edge, and its
end must coincide with that same parent's inactive edge. Actual enables must
never overlap. Finite liveness budgets apply only while the necessary clocks run.

Only a known asserted coordinated reset permits mux pulse truncation. One test
asserts it in the middle of an active output pulse, verifies that every consumer
is reset, and checks subsequent independent recovery. The monitor does not
exclude selector-change windows, unknown reset, or arbitrary settling periods.
The v0.6 reset exception is not applied to gates.

Separate negative simulations inject unknown actual gate controls, mux selector,
controller, and branch request stages. Mux startup without an established reset
epoch fails. Unknown raw reset combined with unknown controls must not suppress
local checks, including both branch inactive-edge checks. Each run must exit
nonzero with its specific emitted `NODAL-CLOCK-057` checker marker after the
testbench reaches its explicit injection point. An earlier warmup failure is
not evidence for the requested mutation. A testbench fallback fatal, timeout,
or unrelated compiler/simulator error is never a pass.

Before firtool export, the official CIRCT `--lower-seq-to-sv` pass runs with
`disable-reg-randomization=true` and source debug information preserved. It
lowers `seq.clock_gate` into its real uninitialized transparent latch, rather
than firtool's default external `EICG_wrapper` specialization. This is a normal
compiler pipeline stage, not a handwritten wrapper or a textual HDL rewrite.

The supported firtool option `--lowering-options=disallowLocalVariables` keeps
assertion lowering compatible with Icarus without rewriting hardware. These are
bounded local prototype regressions, not CI, a full parameter qualification,
physical metastability proof, or setup/hold/common-assertion-duration closure.
