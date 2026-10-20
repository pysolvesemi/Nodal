# NodalSequentialState-DG v0.9 — checked finite graph elaboration

**Status:** Approved and implemented; focused production validation passed
**Scope:** public-api and compiler-boundary; finite elaboration part of F-058.B.3, not runtime recursion or completion of B.3
**Baseline:** accepted timing/composition checkpoints in [increment 58 readiness](../implementation/increment58-readiness.md); [v0.8](NodalSequentialState-DG-v0.8.md) remains unchanged

The standing Foundation implementation authority and prototype process authorize
this necessary additive selection. This does not claim that the owner individually
chose its spellings. Runtime call/return storage and overflow semantics remain a
required separate implementation slice, not an unavailable form counted as done.

## Selected source contract

`FsmDef.unroll` takes a type parameter S, `(levels: Int, base: FsmDef[S])`, then
`(step: (Int, FsmDef[S]) => FsmDef[S])`, and returns `FsmDef[S]`.
It executes exactly levels iterations numbered 1 through levels. Levels is 0..127;
zero returns the identical base object. Each result is an admitted immutable graph
with the exact base enum evidence. A fresh definition token is allowed. The final
graph contains no callback, Module/session value, runtime expression or binding.

The existing canonical graph admission charges actual local records, selected join
operands, occurrence count and depth before the next callback. Bounds remain 4096
actual occurrences, depth 128 and 65536 graph records. Returning the prior graph
unchanged does not invent storage or inflate the hardware report. The iteration
count is a host elaboration bound, not a proof of arbitrary Scala termination.
No recursive helper or nested unroll is admitted inside a checked step.

For explicit binding of newly created slots, FsmDef exposes read-only direct
inventories: `guardSlots`, `actionSlots`, `transferSlots`, and `childSlots`.
These preserve exact authored tokens and canonical first-use order. Transfer slots
retain their actual FsmTransfer identity and carrier-typed bind operation; their
implementation Bool guards are excluded from guardSlots. Child order remains the
existing authored inventory. There is no lookup by guessed names, implicit
recursive binding, runtime closure in a definition, or second slot registry.
Completeness, exact owner identity and actual Stream provenance remain checked by
the existing runtime and bridge owners.

## One checked callback owner

The source method is compile-time-only and the existing typed callback phase
lowers its exact symbol to a checked runtime call. A distinct private Function2
carrier attests the ImmutableFsm profile only. Authored attestation/runtime entry
references are rejected; an ordinary function cannot forge the carrier at runtime.

ImmutableFsm is a closed profile of the existing DigitalCollectionCallbackPhase,
not a separate effect checker. It permits immutable FSM factories/combinators,
canonical enum evidence and closed scalar arithmetic. It rejects runtime hardware
or binding capture, Module/Area/Reg allocation, writes, mutable Scala state,
nested callbacks, host topology control, unknown effects and recursive helpers.
The existing 8192-node/128-depth proof budget and exact dispatch rules remain.

Additive summary bit 64 denotes this narrower effect profile. Eligible helpers and
singletons publish it before broader expression-pure bit 4. Consumers that accepted
read-only bit 4 also accept 64; ImmutableFsm accepts only 64, never broad bit 4.
Imported signatures/dependency identities and existing summary limits remain
mandatory. No user annotation or metadata certifies permission.

## Ownership, scaling and validation

FsmDefinitionRuntime performs the bounded iterative fold and independently checks
levels, nulls, exact enum evidence and cached admitted costs. FsmCompositionRuntime
and FlatFsmGraphContract remain sole graph-cost/reachability owners. The existing
FlatFSM executor, native topology, hierarchy proof and target lowering consume the
result unchanged. No native opcode or Cartesian state flattening is introduced.

Work is bounded by the checked step bodies plus each newly constructed graph's
existing direct records/children and immutable cost summaries. There is no recursive
walk of all prior subgraphs per level. Public inventories are retained immutable
views built during canonical graph admission, not recomputed graph resolvers.

Focused acceptance covers direct/named/separately compiled callbacks; levels 0/127;
negative/128/null/foreign-enum/unchecked callbacks; exact overflow before the next
iteration; forbidden runtime allocation, mutation, recursion, nested unroll and
broad-Pure imported summaries; typed transfer inventory/binding; independent actual
child storage; affected callback/FSM predecessors; and actual unrolled Scala through
the default native gate, CIRCT, SystemVerilog and waveform replay. Prototype evidence
is not exhaustive certification of host Scala or every target tool.

## Focused production acceptance

Production API/plugin and maintained source tests passed 34 groups: finite unroll
4, reusable definitions 11, timers 9 and composition 10. Thirteen maintained
frontend/runtime checks passed, including separate producer JARs and missing-plugin
rejection. The actual public unrolled source passed default gating, CIRCT,
SystemVerilog export and Icarus simulation; its typed-transfer companion passed
native gating. All used compiler SHA-256
`3480b1de98f6a380c80a446808ff4e3dc2d121826badd6b657d7a970570c6ff2`.
The sole first-run predecessor failure was stale expected diagnostic text for an
existing foreign-slot rejection; the exact assertion was corrected and all eleven
reusable groups reran successfully without changing production rejection behavior.
See [maintained reproduction](../../tests/scala/reusable-fsm/README.md) and the F58
readiness checkpoint for commands and limits. Runtime stacks remain required and
are not implemented by this finite-elaboration slice.
