# Increment 34 — Analog control-flow implementation

**Status:** In progress  
**Branch:** `increment/34-analog-control-flow-v1`  
**Baseline:** stacked on Increment 33 head
`ea7f7da51e85ba275dac71db7823ba0223f8d4ac`

## Objective

Implement structured control flow for ordered analog procedural code while
preserving variable ownership, lexical scope, authored statement order, physical
dimensions, source provenance, and the separation between procedural assignment,
equations, contributions, and conservative connectivity.

## First tranche

The initial Increment 34 checkpoint adds:

- the approved source-semantic contract;
- an executable Scala control-flow model;
- explicit static and runtime condition classification;
- first-match conditional chains;
- dimensionless integer and Boolean case selection;
- exact static loops and runtime loops with a finite maximum;
- scoped `break` and `continue`;
- branch-sensitive definite-assignment analysis;
- regressions for missing `else`, missing case default, zero-trip loops,
  `continue` paths, duplicate case labels, and illegal loop exits;
- a machine-readable implementation manifest, mutation tests, and a permanent
  read-only workflow.

This checkpoint is deliberately stacked on the unmerged Increment 33
implementation. It does not bypass the predecessor gate and cannot merge to
`dev` before Increment 33 closes.

## Dataflow model

The analyzer carries one set of definitely initialized variable identities on
each reachable path.

### Sequential blocks

An assignment adds its target after all value reads are validated. An explicit
read requires its variable to be present in the incoming set.

### Conditional chains

Each reachable arm starts from the same incoming set. The outgoing normal state
is the intersection of all reachable arm states. Without `else`, the unchanged
incoming set is another reachable alternative.

Static false arms do not participate in dataflow. The first static true arm
terminates the chain. Runtime arms remain possible while the unmatched path
continues to later arms.

### Case statements

A runtime selector makes every arm reachable. Without a default, the unmatched
incoming path remains reachable. A static selector chooses one arm or the
default after complete structural validation.

### Loops

A zero-minimum loop includes the incoming set among possible exits, so body-only
assignments are not definite after the loop.

For a loop with at least one guaranteed iteration, the result intersects normal,
`break`, and `continue` exit states from the first reachable iteration. This is
conservative because later iterations may add initialization but cannot make a
first-iteration omission definite.

`break` and `continue` are consumed by the nearest runtime-bounded loop.

## Current boundaries

The first checkpoint does not yet:

- connect the public `analogProcedure` construction path to the new statement
  tree;
- freeze final Scala spelling for case and loop builders;
- replace the Increment 33 flat assignment snapshot;
- add first-class `nodal.analog_if`, `nodal.analog_case`,
  `nodal.analog_loop`, `nodal.analog_break`, or
  `nodal.analog_continue` operations;
- add native compiler verifiers or direct-MLIR fixtures;
- serialize control-flow source maps or reproducibility evidence;
- legalize or emit procedural target HDL.

Those items are the next implementation tranches, not evidence gaps claimed as
complete work.

## Planned tranches

### Tranche 34a — source-semantic foundation

- [x] Freeze structured control-flow semantics.
- [x] Implement branch-sensitive definite-assignment analyzer.
- [x] Implement static/runtime condition and bounded-loop legality.
- [x] Implement case-label, `break`, and `continue` legality.
- [x] Add executable Scala witness and mutation-locked contract checker.
- [x] Publish a stacked draft pull request.
- [ ] Pass the exact-head Increment 34 workflow.

### Tranche 34b — public construction

- [ ] Compile-prototype concise conditional, case, and loop spelling.
- [ ] Bind the approved spelling to `analogProcedure`.
- [ ] Preserve nested lexical scope and authored operation order.
- [ ] Retain stable source identities and branch paths.
- [ ] Add public positive and negative construction tests.

### Tranche 34c — bridge and native IR

- [ ] Add first-class Nodal control-flow operations and regions.
- [ ] Serialize the Scala statement tree without flattening branches.
- [ ] Implement native structural and branch-sensitive verifiers.
- [ ] Add direct-MLIR positive and negative fixtures.
- [ ] Preserve complete source-map coverage through parse and print.

### Tranche 34d — closure

- [ ] Complete deterministic evidence serialization.
- [ ] Run the full inherited workflow matrix on one exact head.
- [ ] Perform a fresh review and repair all findings.
- [ ] Merge after Increment 33 is closed.
- [ ] Run post-merge Core CI and dedicated Increment 34 validation.
- [ ] Record immutable evidence and mark the roadmap item complete in a
  separate closure pull request.

## Review focus

The next review should pay particular attention to:

- whether first-match static/runtime conditional analysis is conservative;
- whether `break` and `continue` states are merged correctly;
- whether a guaranteed first loop iteration is sufficient for each propagated
  initialization fact;
- whether static selection still validates unreachable source structure;
- whether the eventual public API can reuse existing `when`/`elsewhen`/
  `otherwise` without changing digital semantics;
- whether case selector restrictions are appropriate for the first portable
  Verilog-A/Verilog-AMS target profile.
