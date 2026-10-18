# Nodal writable Real arrays design gate v0.7

**Status:** Approved
**Scope:** public-api
**Scope:** core-library-boundary
**Increment:** Foundation 43
**Decision date:** 2026-10-18

## Authority and required scope

The owner's standing implementation request is:

> start implementing all foundation unchecked increment items in nodal repo from increment 43 and continue until all foundation checklists are completed. Don't start another track just complete foundation track.

This implementation-selected extension completes the repeated live/event sharing
obligation explicitly retained in [v0.5](NodalWritableRealArrays-DG-v0.5.md).
It does not attribute a separately selected signature to the owner. It follows
[v0.6](NodalWritableRealArrays-DG-v0.6.md) generated storage and the conservative
array checkpoint `1c87baaf13e27e5c3b72d9658ab2e542d3c3c7f1`.
The selected contracts received focused architecture and normative review before
implementation. This gate is design authority, not executed acceptance or F43 closure.

## Source identity, demand and lifetime

Preserve the existing public syntax and original retained Fill/View graph.
Validate its actual ownership, shapes, static indices, bounds, types, units and
effects before derived scalar erasure. One actual retained fill has one demanded
value per complete consumer; different projections share it, while distinct
fills with identical text remain distinct. Evaluate a fill only at its first
reached demand, once, then reuse its value. Do not read skipped fill sources,
duplicate evaluation, infer identity from text, or reuse caches across consumers.

For controls, one reached if-arm predicate, one case selector, or one loop-entry
bound is a consumer. Later if-arm predicates execute only after previous arms
fail. Bound evaluation remains on loop entry, not each iteration. For events,
one execution of the complete event statement, including all its OR primitive
occurrences, is a consumer. Preserve one OR statement/body and separate primitive
monitor histories. There is no mandated authored-leftmost argument/OR evaluation
order, and correctness must not depend on one.

Reset private ready flags on every reached consumer evaluation, including repeated
same-time analog-block visits. Do not use time, initial_step or handler invocation
as an epoch. Generated scratch belongs to its actual procedure occurrence.
Immutable assignment/initializer captures and ordinary single-occurrence behavior
remain on their existing supported paths. Already-proven static control literals
remain static; no monitor-containing static branch may become a runtime predicate.

## Shared representations and native transport

Use the existing retained source graph, canonical scalar parser, actual storage
bindings, structured DA, event ownership and backend statement owners. Do not add
another source language, shape engine, mutable registry or raw target-code input.
A derived DAG contains one body per actual fill identity and references child
fills instead of expanding their bodies.

Add the internal capability `!nodal.analog_deferred<"real", "dimension">`, with
canonical physical dimension and Real kind only. It is not numeric storage,
a shaped element or an immutable snapshot. Only `nodal.analog_live_fill` defines
it. That operation has variadic actual Variable/AnalogDeferred references,
required String identity/value/owner attributes, optional paired generated owner
and induction attributes, required metadata and one deferred result. It has no
regions, Pure trait, claimed-read/static flag or caller-supplied execution plan.
Defining a handle evaluates nothing; ordinary value CSE must not merge distinct
fill identities.

Selected controls and primitive event operands may bind actual storage/deferred
handles using explicit `reference_form = "live"`; absent/storage forms retain
their existing contracts. Live form requires a deferred dependency. Labels derive
from actual storage identity/projection metadata or actual live-fill identity;
there is no independently trusted binding table. Consumer read inventories remain
exact transitive storage reads, not deferred labels. Verify direct operand usage
separately from transitive read inventories.

Native verification independently checks actual defining operations, types/units,
canonical bodies, exact used bindings, unique labels/identities, lexical/generated
ownership, dominance, acyclicity, illegal effects and one ultimate consumer.
For events, derive that consumer through the actual primitive/OR/on use closure,
including unused outer event statements; source event reuse receives fresh
consumer definitions. Do not hide storage from selected DA or event relocation
barriers through nested deferral. Definitions do not require initialized storage
until its actual consuming point; they cannot escape to snapshots, assignments,
persistent initializers, public functions or another consumer.

## Control lowering

Compile only demand-bearing expression paths and unique fill bodies into a finite
private scalar plan. Demand-free subtrees retain the existing parser/renderer.
Each fill has cache, ready and continuation scalars; acyclicity prevents active
recursive reentry, so no runtime stack/array is needed. A first demand jumps to
its body, writes cache before ready and returns to its call site; later demands
read the cache. Preserve &&, || and conditional-expression reachability.

A bounded for/case dispatcher uses compiler-derived labels and a checked portable
bound: the count of unique non-halt blocks, each reached at most once per visit.
Verify jumps/returns, unique fill bodies and definition-before-use. Private plans
are never caller-authored IR. No source variable is assigned by the dispatcher.
Place later predicate programs inside nested else blocks; do not evaluate every
predicate before the chain. Case and loop-bound programs remain at their existing
entry points. Structural reference-map setup must not acquire eager numeric reads.

## Inline event lowering and private effect ABI

Keep numeric work at the original reached event argument demand sites. Render a
demand as `ready ? cache : private_store(F, cache, ready)`, with F recursively
using the same checked fill identities. A flag-only reset immediately precedes
the complete unchanged event statement. Do not compute event argument values in
a preceding statement program or place actual monitors in private runtime loops,
case branches or helper functions. Enable zero does not imply skipped argument
arithmetic; only source expression short circuit governs demand.

The compiler-private analog helper takes one Real input, one Real output cache
and one Integer output ready flag, returns the input, copies input to cache and
sets ready to one. It has no persistent state, probes, module-variable accesses,
events, contributions, recursive calls or arbitrary effects. Each call's output
actuals are distinct private writable scalars; acyclic F cannot access its own
output actuals. Nested calls may update only their own private caches/flags.
Do not model this helper as an existing pure public user function.

The independent target parser must validate the selected private signature/body,
return and output types, actual writable scalar arguments, non-aliasing/effect
restrictions, names and expression syntax. Commentary does not grant validity.
Native graph/target generation retain source ownership; target syntax acceptance
is not a proof of source evaluation counts or numerical simulator behavior.

## Normative basis and compatibility

The language baseline is Verilog-AMS 2023. Sections 4.2.3 and 4.2.12 preserve only
reached Boolean/conditional branches. Sections 4.7.1–4.7.3 permit analog function
output formals and specify return copy-out; argument order is unspecified.
The helper's distinct outputs and acyclic input dependencies make output-formal
initialization timing irrelevant to its result. Sections 5.2–5.3 and 5.10 define
sequential analog execution and the complete nonblocking event statement; 5.10.1
retains OR composition and 5.10.3 monitor histories. Section 9.3 includes repeated
analog evaluation during convergence. This uses the existing event-statement
consumer contract, not an invented simulator-internal callback epoch.

[Authoritative LRM](https://accellera.org/images/downloads/standards/v-ams/VAMS-LRM-2023.pdf).
Numeric evaluation remains inside event expressions, unlike the rejected temporary-
argument relocation proposal. Do not impose Verilog-AMS 2.4 tolerance restrictions
on the 2023 source language. Independent simulator/OpenVAF compatibility remains
an explicit later capability handoff, not a claimed result of this gate.

## Resource profile, alternatives and focused acceptance

Inline event text can duplicate nested bodies even though runtime evaluation is
cached. Count expansion with saturating checked arithmetic over the unexpanded
DAG before allocating expanded strings. Initial selected event limits are 4,096
demand wrappers and 1 MiB rendered expression bytes per complete consumer, with
65,536 wrappers and 16 MiB aggregate per module definition. Enforce source/native
preflight and independent actual target byte/depth limits; retain the existing
expression-depth restrictions. Reject over-budget input explicitly before
expansion/output publication. Source consumer/module preflight uses
`NODAL-SHAPE-043-010`; it does not reuse the existing boundary-array diagnostic.
Final limits and measured representative sizes are
recorded in the implementation note; no unlimited or linear event-output claim.

Compare a meaningful nested diamond demanded across OR, skipped first use,
distinct equal-text fills and depth/size boundaries. The existing 40-level
read/remap traversal remains supported; event emission of an over-budget graph
may reject before expansion. The approved scope does not require arbitrary DAG
size. A polynomial expression-site dispatcher was considered but adds a larger
private effect ABI and output burden without a demonstrated required consumer.
Eager functions, duplicated runtime evaluation, numeric event preludes, global
cache epochs and exponential expansion before admission are rejected alternatives.

Focused checks cover source/bridge/native forged graph and binding cases,
transitive selected DA, ownership and consumer reuse, actual control/event
Scala→native→VA/AMS witnesses, lazy/once-only/reset traces, target ABI/effect
mutations, measured nested output and causal over-budget rejection. Preserve
ordinary controls/events, static monitor placement, immutable snapshots and
module/generated lifetimes. Use focused affected predecessors and concise review;
no CI, exhaustive qualification or unsupported completion claim. Review all
original F43 children after implementation, not merely this new profile.
