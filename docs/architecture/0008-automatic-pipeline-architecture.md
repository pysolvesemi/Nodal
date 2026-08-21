# ADR 0008: Use protocol-typed automatic pipeline regions

- **Status:** Proposed
- **Date:** 2026-08-21
- **Scope:** Public pipeline API, scheduling IR, latency, throughput, backpressure, parameterization, and bounded retiming
- **Decision target:** Increment 14 public API v0.3 gate

## Context

Current Chisel provides explicit primitives such as `Pipe`, `ShiftRegister`, `Queue`, `Valid`, and `Decoupled`, but most datapath stage placement and sideband balancing remain manual. Current SpinalHDL provides a richer pipeline graph built from payloads, nodes, links, and a builder, but that plumbing is more detailed than most datapath authors should need to expose in ordinary source.

CIRCT provides unscheduled and scheduled pipeline operations and ESI channel buffers that may help the compiler implementation. Those dialects do not by themselves define Nodal's public protocol, reset, parameterization, latency, mixed-signal, diagnostic, and schedule-stability contracts.

Nodal starts from a target-neutral MLIR layer and can expose a small transaction-oriented API while preserving a rich graph for scheduling, verification, reports, and deterministic Verilog-AMS generation.

## Recommendation

Nodal should provide protocol-typed automatic pipeline regions centered on a compact `pipe(input, policy)(transform)` form plus an explicit `delay(cycles)` helper.

The input protocol determines transport semantics:

- plain transaction: fixed-rate, one input and one output each cycle;
- `Valid[T]`: bubbles without backpressure;
- `Stream[T]`: elastic ready/valid with backpressure.

The compiler constructs a single-domain feed-forward transaction graph, schedules it from explicit latency, throughput, and timing constraints, inserts only pipeline-owned registers and elastic buffers, balances reconvergent paths, and transports only live sideband values. Generated stage placement remains visible in deterministic reports and explicit HDL.

## Candidate source shape

```scala
val result = pipe(
  input = Txn(a = a, b = b, c = c, tag = tag),
  target = 500.MHz,
  latency = Latency.Auto,
) { x =>
  Result(
    data = (x.a + x.b) * x.c,
    tag = x.tag,
  )
}
```

Candidate supporting constructs are:

- `value.delay(cycles)`;
- `Latency.Auto`, `Latency.Exact(n)`, and `Latency.Range(min, max)`;
- `Throughput.EveryCycle`;
- protocol-specific ready-path and buffering policy;
- `stage(value)` as a hard stage boundary;
- `sameStage { ... }` as a grouping constraint;
- fixed- and variable-latency operator contracts;
- deterministic latency and schedule inspection.

Exact spellings remain candidates until Increment 14.

## Semantic boundaries

The initial contract is deterministic pipelining, not general HLS. It must not silently:

- reassociate arithmetic or change finite-width, rounding, saturation, or exception behavior;
- reorder, duplicate, drop, merge, or speculate transactions;
- share operators or change initiation interval;
- pipeline loops or infer algorithmic state;
- cross clock/reset domains;
- move memory writes, analog contributions, external side effects, or user-owned state;
- claim timing closure without an applicable delay model.

Automatic movement is limited to pipeline-owned registers inside the declared region. CDC/RDC, analog sampling, memories, hierarchy contracts, side-effect commit points, and observability anchors remain explicit barriers.

All dynamic values consumed by a region enter through its typed input transaction and are sampled together. Parameters and constants are static. Reading an unrelated live signal inside a pipeline is rejected unless a later version defines an explicit live-control semantic.

## Clock and reset

A pipeline region belongs to one current `ClockDomain`. It cannot create a clock or hide a crossing.

Inserted payload registers are resetless by default. Validity, occupancy, and control state follow the current domain reset contract. Reset, enable, stall, flush, and update priority must be frozen by the public design gate and represented explicitly in IR.

## Protocol contracts

For fixed-rate and valid-only pipelines, a published module boundary has an exact or bounded latency contract. `Latency.Auto` may vary only inside a boundary that does not expose unconstrained latency.

For `Stream[T]`, the compiler may insert elastic registers, skid buffers, and registered-ready cuts while preserving:

- full declared throughput;
- data and valid stability while stalled;
- no loss, duplication, or reordering;
- explicit minimum latency, capacity, fall-through, and ready-path properties.

Fork, join, merge, arbitration, and reorder policies remain explicit because they change transaction and backpressure semantics.

## Scheduling and timing models

The first scheduler handles acyclic, feed-forward, initiation-interval-one graphs. It accepts exact, ranged, or automatically minimized latency and a target period/frequency.

Operation models are versioned inputs and may be generic, FPGA, ASIC, simulator-only, or user supplied. They account for width, signedness, target profile, implementation choice, fixed multi-cycle latency, and uncertainty. Generic estimates support architecture exploration but are not timing-closure guarantees.

Optional synthesis feedback may refine estimates later, but it cannot silently violate exact latency, protocol, or interface contracts.

## Parameterized HDL

Nodal must preserve one native parameterized module rather than silently cloning one module per parameter value.

Automatic scheduling with timing-affecting parameters is legal only when:

- every such parameter has a finite declared envelope;
- the timing model can conservatively evaluate the envelope; and
- one structural schedule is valid for every legal value.

The scheduler chooses one envelope-safe stage structure while the emitted widths and values remain symbolic. When no safe finite envelope or monotonic model exists, compilation requires explicit stage boundaries, an approved timing assumption, or a concrete non-parameterized schedule. It must not specialize silently by parameter value.

A simple `delay` structure may later emit native parameterized depth when the backend can represent it safely; an automatically partitioned expression graph cannot pretend an unresolved timing parameter has a known schedule.

## CIRCT reuse

Nodal will compare and selectively reuse:

- `pipeline.unscheduled` and `pipeline.scheduled` for feed-forward scheduling and stage materialization;
- ESI channel buffers/stages for elastic transport;
- `seq` and `sv` for register and control lowering.

Nodal retains target-neutral operations where upstream semantics are incomplete, unstable, or unable to preserve its protocol, reset, parameterization, source mapping, and diagnostics. Public source never exposes CIRCT operations directly.

## Verification and evidence

The pipeline subsystem must produce:

- positive and negative public API fixtures;
- deterministic schedule, latency, capacity, and critical-path reports;
- normalized IR and golden Verilog-AMS;
- formal checks for transaction conservation, ordering, latency, stall stability, reset, and flush;
- randomized bubble and backpressure regressions;
- schedule hashes and change diagnostics when the toolchain, model, policy, or parameter envelope changes the microarchitecture.

## Consequences

### Positive

- Source remains concise and independent of selected stage count.
- Sidebands and reconvergent paths are aligned automatically.
- Fixed, valid-only, and elastic semantics are explicit and testable.
- Target-driven scheduling and manual hard boundaries coexist.
- Parameterized HDL can retain one module using an envelope-safe schedule.
- Schedules, reset behavior, backpressure, and generated RTL remain reviewable.

### Costs

- Timing models and parameter envelopes become versioned compiler inputs.
- Elastic control and flush semantics need strong formal verification.
- Published latency contracts constrain later rescheduling.
- Hierarchy, memories, and variable-latency units require explicit contracts.

## Rejected alternatives

- **Only fixed-cycle shift helpers:** useful, but they do not schedule expressions or align sidebands.
- **Expose nodes, links, and builders as the primary API:** powerful but too much plumbing for ordinary datapaths.
- **Infer pipelines from arbitrary module logic:** boundaries, side effects, latency, and user intent become ambiguous.
- **Automatically pipeline across hierarchy or clock domains:** hides interface and CDC semantics.
- **Silently rely on synthesis retiming:** can move reset, enable, observability, memory, and mixed-signal boundaries.
- **Specialize parameterized modules per value:** conflicts with native parameterized HDL and obscures hierarchy.
- **Treat frequency metadata as timing closure:** estimates are not implementation proof.

## Follow-up increments

- Increment 13 compiles and compares candidate source APIs.
- Increment 14 freezes the v0.3 API and contract fixtures.
- Increments 58-63 implement graph IR, fixed/valid scheduling, elastic transport, timing models, controls, hierarchy, reports, and bounded retiming.
- Increment 68 integrates schedules into Verilog-AMS emission.
- Increment 69 proves the model in ADC/DAC mixed-signal vertical slices.

## References reviewed

- Chisel util API: <https://www.chisel-lang.org/api/latest/chisel3/util/>
- SpinalHDL pipeline library: <https://spinalhdl.github.io/SpinalDoc-RTD/master/SpinalHDL/Libraries/Pipeline/index.html>
- CIRCT pipeline dialect: <https://circt.llvm.org/docs/Dialects/Pipeline/>
- CIRCT ESI dialect: <https://circt.llvm.org/docs/Dialects/ESI/>
