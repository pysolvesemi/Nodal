# Tracked root-event target witnesses

`nodal.internal.testkit.Increment57EventSourceExport` emits actual public Scala
event fixtures through the production construction/snapshot/bridge path.
The frozen `contracts.clockreset.positive.LowLevelEventFixture` remains unchanged.
`run_target.py` consumes the four supported flat witnesses and runs the current
Nodal compiler's default semantic gate on each actual source replay before any
target normalization or lowering. This retains the integration regression for
independently verified root-event boundaries with absent domains, rather than
allowing normalization to hide those declarations. It then invokes the pinned
CIRCT exporter and simulates the actual generated SystemVerilog with Icarus.
Handwritten code is limited to the testbench, never a replacement DUT.

The focused cases check:

- The frozen Bool event writes true and holds it between events.
- Rising and falling UInt8 pairs seed on their first legal event using pure
  selects, then swap old state simultaneously, including source port aliases.
- The opposite edge and input changes alone do not update storage.
- Whole Vec(Bool, 4) updates retain the existing row-major packed ABI.
- State is unspecified before its first selected edge, with no reset or invented
  initialization. Testbench trigger levels start inactive, without a selected
  startup transition. No hierarchical write or force initializes the DUT.
- Source-located known-level/definite-edge and external setup/hold obligations
  survive into target output. Digital simulation does not certify X/Z edge
  behavior, metastability, electrical timing or setup/hold compliance.

After emitting the replay inputs, run:

```sh
python3 tests/scala/root-events/run_target.py \
  --input .work/increment57/event-live \
  --out .work/increment57/event-target \
  --nodalc .work/native-build/bin/nodalc \
  --firtool .work/circt-toolchain/bin/firtool \
  --iverilog .work/tools/iverilog/bin/iverilog \
  --vvp .work/tools/iverilog/bin/vvp
```

All generated IR, HDL, benches, logs and result manifests stay under ignored
`.work/`. The test's existence is not a passing result. Executed commands,
results and limitations belong in the existing increment readiness record.

`EventCIRCTTest.cpp` additionally checks actual target old-state cones, canonical
PortValue aliases, both edge mappings, distinct storage identities through CSE,
whole Vec/Struct selects, retained obligations, textual round-trip, and
transactional preservation when verification or the narrower target profile
rejects an input. Native event ownership, capture and isolation rejection tests
live in `EventModelTest.cpp`, rather than duplicating the semantic owner here.

Bounded pure collection maps and folds remain valid in the source/native event
graph, including unused lane/seed operands and dead pure captures. The existing
flat finite CIRCT profile has no collection-region lowering, so those target
requests reject transactionally with its existing flat-operation diagnostic.
A source alias/index path may encounter an earlier unsupported target operation;
retain that actual source-located diagnostic rather than rewriting the source
to reach the collection refusal. This is not a generated-HDL claim for collection
callbacks. There is no event-specific collection compiler or replacement
handwritten RTL.
