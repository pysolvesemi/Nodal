# Nodal clock/reset execution design gate v0.10

**Status:** Approved
**Scope:** public-api

## Useful quarantined event execution

This remaining Foundation 57 slice implements the frozen positive low-level event
fixture: a root-owned Bool input edge persistently updates an explicitly tracked
output. It does not reject every process, infer ordinary clock domains from Bool,
or revive unrestricted always blocks. The frozen lowlevel.process(event) spelling
remains the explicit escape. Existing digital on(event) compatibility must retain
the same quarantine and verification rather than becoming a less checked route.
Analog event handling remains separate and unchanged. The historical
AnalogEventReviewTests.DigitalOnReviewSource accepted two inert candidate
processes writing the same output. That acceptance is corrected, not claimed as
valid executable behavior: retain it as a dual-writer negative and add a legal
separate-output counterpart preserving both on(event) spellings.

The bounded profile uses one rising or falling event on an actual root-owned
scalar Bool input boundary with no attributed clock domain, event owner or
internal driver, never a Clock/Reset cast or ordinary domain-owned state. Supported
events assume known trigger levels and definite rising/falling transitions; X/Z
transitions are outside that environmental contract. Ordinary target four-state
edge behavior is not a runtime certification of this assumption.
Digital event composition is explicitly unsupported in this slice; Event.or's
existing analog meaning must not be silently repurposed. A process is application
root-only, not a reusable child-library definition. Native admission checks the
actual selected root and instance graph, not a serialized root-only flag.

## Tracked state and assignment semantics

Every assigned output becomes explicitly tracked event-owned storage. Preserve
its actual declared finite digital type/layout and source identity; reuse the
existing fixed-whole finite payload/type limits. Storage is unspecified before
its first qualifying update, with no invented reset or initialization. It holds
between events. This storage is not an ordinary ClockDomain register and cannot
acquire ordinary domain provenance through an output annotation.

A process body may use bounded pure digital expressions, constants, permitted
external input observations and old values of outputs owned by that same process.
Use one unconditional assignment per assigned output in this initial profile.
All right-hand sides observe pre-event values and all updates commit together;
there is no accidental host execution order or blocking-assignment race. Explicitly
reject unsupported conditional/loop/control forms rather than dropping them or
inventing a general event scheduling language. Separate processes may own
separate outputs; an output has one writer across event and continuous paths.

Forbid ordinary Reg, memory, FSM, domain, instance or analog-state allocation
inside the process, nested processes and untracked hardware effects. Ordinary
Scala elaboration bookkeeping still executes at capture time; no generic host
side-effect detector or runtime execution of host counters is promised. Existing
assignment/write ownership remains authoritative, extended with the actual
process identity rather than bypassed. Reusing a process's output internally
must be a tracked state dependency, not an untracked combinational feedback loop.

## Captures and timing isolation

Retain actual trigger SSA, edge, process identity, captures, write targets and
source locations. A permitted external observation is an exact root input boundary with no attributed clock
domain, event owner or internal driver; it carries an explicit event-sampling
setup/hold responsibility. Ordinary domain-owned state, another process's state,
analog values, foreign captures and hidden CDC/RDC dependencies reject. A source
annotation cannot turn these into an external input observation.

Event-owned outputs remain event-classified through combinational aliases and
hierarchical provenance. Sampling them into an ordinary domain requires a
separately implemented explicit supported boundary or rejects; no currently
unsupported crossing is declared safe merely because it follows an escape.
Cdc.waive must not fabricate an ordinary source domain for event state. Clock
and Reset capabilities cannot be created from process outputs or triggers.

## Native and target ownership

Introduce closed owned event/process/update records through the existing
construction, snapshot and bridge path. Native validation independently checks
actual trigger ownership/type, root restriction, exact capture graph, write
ownership, output state classification and bounded resources. Descriptor-only
event markers cannot authorize execution. Reuse existing digital dependency,
type and materialization infrastructure rather than another expression compiler.

Target lowering emits real edge-triggered event storage with simultaneous
pre-event update semantics and no reset/init. Any internal target clock/event
conversion is private to this verified quarantined construct, never a public
Bool-to-Clock operation or proof of clock-domain membership. Preserve the actual
trigger, edge, source map and external sampling obligations. No physical timing
certification is claimed. Unsupported hierarchy target lowering still rejects
transactionally under the F57/F58 boundary.

## Focused acceptance

Actual flat Scala-to-native-to-HDL positive witnesses cover the frozen Bool
output fixture, both edges, multiple tracked outputs with old-state dependencies,
holding between events and unspecified startup before a legal edge. Initialize
testbench trigger levels without a spurious selected edge; never initialize the
DUT to make an uninitialized contract look deterministic. Finite payload/type
coverage follows the existing ABI rather than a new encoding.

Causal negatives cover ordinary state allocation, hidden clocked/event/analog
captures, multiple writers, forged trigger/type/root attributes, child-library
use, nested/composed events, unsupported body control, event-output sampling into
ordinary state and descriptor-only execution. Existing analog on(event), ordinary
register construction and domain/crossing checks are affected predecessors.
This gate neither completes F57 nor permits CI or broad qualification.
