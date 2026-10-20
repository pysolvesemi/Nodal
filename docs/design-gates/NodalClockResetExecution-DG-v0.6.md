# Nodal clock/reset execution design gate v0.6

**Status:** Approved
**Scope:** public-api

## Allocation and compatibility

This additive Foundation 57 execution gate implements the existing ClockGate and
ClockMux.glitchless signatures under the owner's standing Foundation-only
implementation instruction. It does not turn generated clocks into Same aliases,
add implicit synchronizers to data, or replace an ordinary clock mux with a false
glitchless claim. Source, native model and advertised target behavior must agree.

The selected slice is owned gate/mux producers and their reset/clock proof. Typed
relationship completion, actual hierarchy propagation, combinational-output
reconvergence, waivers and restricted low-level events remain required subsequent
F57 slices. Descriptor-only fixtures remain distinguishable from executable
construction. This gate does not close any coarse F57 checklist.

## Gate behavior

ClockGate produces a distinct owned Clock capability and derived domain. Enable
is the OR of actual enable and testEnable. For rising-edge domains it is latched
during the input's low phase and held throughout the active phase; the output is
that held enable AND the parent clock. Falling-edge domains use the inverted
clock equivalent: transparent during physical high, disabled output held high.
The target may use CIRCT seq.clock_gate with explicit falling-edge adaptation,
retaining test intent and the physical instance identity.

There is no fabricated latch initialization. A resetless gate becomes defined
after a complete inactive phase with known controls; startup with an active or
stopped parent may leave its output unspecified. ClockGate itself does not mask
clocks on reset. Control changes during the active phase cannot cut that pulse.
Physical setup/hold at the latch-closing edge remains an explicit aperture
obligation, not a digital metastability proof.

Enable/testEnable may be constants, actual parent-domain values, or an owned
external Bool control boundary with the retained aperture obligation. An
unannotated external control is not attributed to a domain merely by accepting
this contract. Mixed-domain expressions and Pulse relabeling reject. Captured
foreign values, clock-as-Bool and Boolean-as-Clock casts remain invalid.

A resetless parent produces a resetless derived domain. Otherwise the derived
domain receives its own two-stage async-assert/sync-release chain from the actual
common assertion roots, clocked by the derived output. It must not reuse the
parent's release chain as proof of derived-clock release. Disabled output may
hold the derived domain in reset until enough valid output edges occur.

## Glitchless mux behavior

Exactly two distinct actual parents are required, with matching active edges.
False selects the first and true the second. Both parent identities survive;
there is no single arbitrary parent alias. The result is a distinct clock, not a
Same relation. Normal data between parent/derived domains still requires the
applicable checked relationship or explicit crossing/waiver contract. In v0.6,
ordinary parent-to-derived and derived-to-parent data sampling rejects with
NODAL-CDC-001; ancestry or subset edges alone never grant permission. Existing
explicit CDC remains valid. The stated control-sampling contracts are separate.

The finite implementation uses a serialized break-before-make controller:

- The first parent's ungated active edge clocks a two-stage select-level sampler
  and the controller. Initial controller states allow two select samples before
  making the first request. With old-state sequential semantics, the first request
  cannot consume the second sampler stage before the edge following its second
  completed sample.
- Each branch samples its request through two stages on that parent's inactive
  edge; an actual enable register follows on an inactive edge. Its gate is held
  fixed throughout every active phase. Returned actual-enable acknowledgements
  pass through two stages to the controller.
- OFF/WAIT_OFF requests neither branch and waits for both acknowledged off.
  It then requests only the currently selected branch.
- WAIT_ON retains that one request until actual-on acknowledgement. It cannot
  cancel an in-flight request early, even if selection changes.
- RUN changes to WAIT_OFF on a changed sampled selection, dropping both requests.
  A new branch cannot start until the old actual enable is acknowledged off.

This rule prevents stale off acknowledgements from overlapping a delayed old
request with a new branch. Select transitions may coalesce. No fixed switching
latency or delivery of every select transition is promised. Liveness requires the
controller clock and the relevant branch inactive edges. A selected parent
stopped active may hold output active and prevent handover; cutting that pulse
is not an allowed liveness shortcut. Unequal and simultaneous clocks are legal.
Normalize each parent to active-high, gate it with its actual enable and OR
the two normalized branches. Invert the combined result for falling-edge
domains, so the disabled physical output is high. The ordinary CIRCT
seq.clock_mux alone does not satisfy this contract.

Selector provenance must be one actual domain, a constant, or an owned external
Bool boundary; the sampler is the explicit control crossing. Mixed-domain
combinational controls and Pulse relabeling reject. Known selector/control state
is checked at each applicable non-reset local edge; unknown state must fail an
executed assertion rather than pass through an unknown predicate. Unknown reset
cannot suppress these checks; only a known asserted local reset may do so.

## Mux reset and pulse-integrity interval

Parents require equal nonempty actual reset root/element/polarity sets and safe
independent release. Resetless muxes reject in this bounded profile. Independently
released parent resets must not be combined, because that reconverges release
chains. Reuse or reconstruct the canonical common raw assertion producer from
verified physical roots, retaining per-root polarity and source identity.

Controller active-edge logic and both branch inactive-edge logic receive separate
owned two-stage release chains; the output domain receives another independent
two-stage release. Controller progress must not depend on output-domain release.
Common assertion clears sampler, controller, request synchronizers, actual
enables and returned acknowledgements. No power-up initialization is invented.

Coordinated reset assertion can terminate a current output pulse because it
asynchronously clears the active gate while all output consumers are held reset.
The normal-operation no-runt/no-overlap guarantee explicitly excludes that reset
assertion interval and resumes after recovery. This exception is retained in the
typed obligation, reports, generated comments and waveform checks; it is not a
normal-operation clock-switching shortcut. Every domain consuming the mux clock,
including native alternate bindings, must retain the same common assertion roots
and safe output-clock release. Resetless or unrelated-reset consumers reject, so
this reset-time exception cannot leave an active consumer unprotected. Common assertion duration and physical
control timing remain environment obligations.

## Shared schema and bounded proof

A narrow ClockModel resolves physical input/projection identity or distinct
owned derived-producer identity, parent edges and timing contracts.
PhysicalControlModel remains the physical Clock/Reset input-element owner;
ResetModel compares resolved clock identity at actual consumers without losing
physical reset-root identity. DomainModel consumes the shared clock proof.
Existing digital dependency/sampling owners validate controls; no second CDC
graph or copied transitive provenance sets are introduced.

Closed native producers are clock_gate(parentClock, enable, testEnable) returning
Clock under gate_v1 and clock_mux(clock0, clock1, select, commonAssertion)
returning Clock under glitchless_mux_v1. They retain exact actual source and
receiving domain references, edge, semantic identity, source location, fixed
stage/state contract and closed obligations. Actual operands, generated-domain
records and parent edges must agree. Descriptor metadata cannot forge authority.

Clock and reset dependency graphs are preflighted independently before state
expansion. Reject cycles, foreign producers, duplicate mux parents, mismatched
edges/roots/polarities, malformed attributes, unsupported construction contexts
and budget overflow transactionally. Use existing finite operation/state limits;
charge generated controller/release/gate state and graph work before allocation.
Independent identical-looking producers and release chains are never merged.

## Focused acceptance

Source/bridge/native positives and causal negatives cover actual operands,
provenance, callbacks/poisoning, reset roots, nested producers, shaped input
projections, independent identities, cycles and finite expansion. Actual public
Scala replay must execute through native IR, CIRCT and SystemVerilog simulation.

Gate witnesses exercise both edges, enable/test changes during both phases,
disabled restart and resetless inactive-phase startup. Mux witnesses exercise
both directions, changing select during handover, unequal/simultaneous/stopped
clocks, delayed release, common reset/recovery, nested gate parents, no overlap
and complete pulses outside the stated reset interval. Assertions must genuinely
fail in negative simulations. Source maps and obligations survive export.

Run directly affected domain, reset, sequential, crossing and CIRCT tests plus
maintained actual reset/crossing predecessors and descriptor DomainStimulus
checks. This is focused local prototype validation, not CI, synthesis closure or
physical CDC certification. Representative scale and final F57 integration remain
required; no future consumer's completion is claimed here.
