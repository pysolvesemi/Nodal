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

## Accepted source form

The public construction tranche uses explicit analog-only builders:

- `analogConditional`, `analogWhen`, `analogElseWhen`,
  `analogStaticWhen`, `analogStaticElseWhen`, and `analogOtherwise`;
- runtime and static `analogCase`, exact `analogCaseArm`, and one optional
  `analogCaseDefault`;
- exact `analogRepeat` and finite runtime `analogLoop`;
- `analogBreak()` and `analogContinue()`.

No ordinary Scala or digital control form acquires analog runtime semantics by
inference.

## Dataflow model

The analyzer carries one set of definitely initialized variable identities on
each reachable path.

### Sequential blocks

A declaration validates its initializer reads before adding an initialized
variable. An assignment validates all value reads before adding its target. An
explicit read requires its variable to be present in the incoming set.

A block-local declaration is removed from normal, `break`, and `continue` states
when the block exits.

### Conditional chains

Each reachable arm starts from the same incoming set. The outgoing normal state
is the intersection of all reachable arm states. Without `else`, the unchanged
incoming set is another reachable alternative.

Static false arms remain structurally retained but do not contribute reachable
read-before-write paths. The first static true arm terminates the chain.
Runtime arms remain possible while the unmatched path continues to later arms.

### Case statements

A runtime selector makes every arm reachable. Without a default, the unmatched
incoming path remains reachable. A static selector chooses one arm or the
default after complete structural validation.

### Loops

A zero-minimum loop includes the incoming set among possible exits, so body-only
assignments are not definite after the loop.

For a loop with at least one guaranteed iteration, the result intersects normal,
`break`, and `continue` states from the first reachable iteration. This is
conservative because later iterations may add initialization but cannot make a
first-iteration omission definite under the initial contract.

`break` and `continue` are consumed by the nearest runtime-bounded loop.

## Flat Increment 33 compatibility

Straight-line procedures continue to use the Increment 33 recorder unchanged.

When an explicit Increment 34 control construct is present, the construction
bridge retains declarations and assignments in the structured tree and removes
assignments from the published flat Increment 33 snapshot. This prevents a
runtime branch from being misrepresented as one unconditional ordered sequence.

Root declarations that predate the first control construct remain available in
the Increment 33 variable inventory. Control-local declarations remain in the
Increment 34 tree until canonical construction-snapshot integration is complete.

## Tranche 34a — source-semantic foundation

- [x] Freeze structured control-flow semantics.
- [x] Implement branch-sensitive definite-assignment analyzer.
- [x] Implement static/runtime condition and bounded-loop legality.
- [x] Implement case-label, `break`, and `continue` legality.
- [x] Add executable Scala witness and mutation-locked contract checker.
- [x] Publish a stacked draft pull request.
- [x] Pass the exact-head Increment 34 workflow and Core CI.

The only inherited failure on the first exact head occurred after all repository
tests passed, when the Increment 19 online toolchain probe received an external
GitHub HTTP 403 rate-limit response. It is not treated as accepted final matrix
evidence and must be rerun.

## Tranche 34b — public construction

- [x] Freeze explicit analog-only conditional, case, loop, and exit spelling.
- [x] Bind the accepted spelling to `analogProcedure` construction.
- [x] Build an owner-remapped immutable structured snapshot.
- [x] Preserve nested lexical scope, authored statement order, and source spans.
- [x] Run branch-sensitive definite-assignment over real public assignments.
- [x] Retain static unreachable branches while excluding their reads from
  reachable-path analysis.
- [x] Preserve block-local declarations and remove them at block exit.
- [x] Prevent structured assignments from appearing as a false flat Increment 33
  sequence.
- [x] Add public positive and negative construction tests and a standalone
  construction witness.
- [ ] Pass the exact-head Increment 34 workflow and all inherited workflows.
- [ ] Complete review of the public-construction exact head.

## Tranche 34c — bridge and native IR

- [ ] Add the control-flow tree to the canonical `ConstructionSnapshot`.
- [ ] Add first-class Nodal conditional, case, loop, break, continue, scope, and
  declaration operations and regions.
- [ ] Serialize the Scala statement tree without flattening branches.
- [ ] Implement native structural and branch-sensitive verifiers.
- [ ] Add direct-MLIR positive and negative fixtures.
- [ ] Preserve complete source-map coverage through parse and print.

## Tranche 34d — closure

- [ ] Complete deterministic reproducibility serialization.
- [ ] Run the full inherited workflow matrix on one exact head.
- [ ] Perform a fresh review and repair all findings.
- [ ] Merge after Increment 33 is closed.
- [ ] Run post-merge Core CI and dedicated Increment 34 validation.
- [ ] Record immutable evidence and mark the roadmap item complete in a separate
  closure pull request.

## Current boundaries

The public-construction checkpoint does not yet:

- add the control-flow tree to the canonical `ConstructionSnapshot`;
- expose control-flow through Scala-to-MLIR serialization;
- add first-class `nodal.analog_if`, `nodal.analog_case`,
  `nodal.analog_loop`, `nodal.analog_break`, or
  `nodal.analog_continue` operations;
- add native compiler verifiers or direct-MLIR fixtures;
- serialize complete control-flow source maps or reproducibility evidence;
- legalize or emit procedural target HDL.

Those items remain active Increment 34 work, not evidence gaps claimed as
complete behavior.

## Review focus

The next review should pay particular attention to:

- first-match static/runtime conditional analysis;
- `break` and `continue` state propagation;
- the conservative guaranteed-first-iteration rule;
- structural validation of statically unreachable branches;
- block-local declaration lifetime;
- ownership and final instance-path remapping;
- separation between the structured tree and Increment 33 flat snapshots;
- future lowering of the accepted public spelling into first-class native IR.
