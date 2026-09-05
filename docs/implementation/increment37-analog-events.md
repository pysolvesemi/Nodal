# Increment 37 — Analog events

**Status:** Implementation in progress
**Branch:** `increment/37-analog-events`
**Integration baseline:** `916f088bd7b0762dbeb14ef78ce4838d4d537f59`
**Design gate:** [NodalAnalogEvents-DG-v0.1](../design-gates/NodalAnalogEvents-DG-v0.1.md)

## Implemented semantics

The public API supports all prefix arities of `cross`, `above`, and `timer`, typed crossing direction, integer enables, lifecycle events with analysis filters, and ordered event OR. `crossing` delegates to the crossing contract and retains its semantic name. Digital edge handles remain separate. Event controls belong to `analogProcedure`; module-level `on` and legacy `analog { on(...) }` convenience forms remain rejected rather than interpreted as digital processes.

Controlled bodies use the existing procedural tree for ordered assignment, lexical scopes, conditionals, case selection, and bounded loops. Event-only writes do not establish unconditional initialization, even under `initialStep`. Empty bodies and unused monitors are retained. Nested event controls, contributions, continuous equations, and analog filters in controlled bodies are rejected. History-bearing monitors under runtime-dependent control are also rejected.

The bridge emits distinct `!nodal.analog_event` values, primitive event operations, `nodal.analog_event_or`, and `nodal.analog_on`. Native verification independently binds source expressions to actual parameters, terminals, and variables, recomputes dimensions and read inventories, verifies owner and source identities, and rejects unknown grammar or forged constant claims. Parameter defaults never prove a constant under overrides. A monitor cannot move past an intervening write to observed storage.

## Target lowering and held state

Event-containing procedures emit ordered blocking assignments and native Verilog-A event statements. Captured variable reads are materialized where they occur: a later write cannot change an earlier captured value. Source-expression strings are parsed and typed before rendering, never pasted into HDL.

Initialized root variables become persistent module variables. Declaration initializers may use constants and parameters, not mutable storage or terminal samples. Module-level declaration assignment is context-free under the [Verilog-AMS 2023 LRM](https://www.accellera.org/images/downloads/standards/v-ams/VAMS-LRM-2023.pdf), section 7.2.2; older tool restrictions require a separately tested compatibility profile. Lexical initializers execute at their authored location.

A continuous held read requires a completed source procedure, one initialized root-local real variable, and exclusively event-controlled writes. `nodal.analog_held_read` independently verifies this proof at the compiler boundary. Event updates, continuous `transition` evaluation, and contributions execute in one ordered analog target process. Filters are not executed inside the event body.

Static monitor loops use `genvar`, preserving separate history for each elaborated occurrence, including nested loops. Runtime loop bounds are evaluated once and checked against their finite envelope. Invalid bounds report `NODAL-ANALOG-034-008` and request termination without executing the loop body; the target simulator determines termination timing. Break and continue use private flags for the nearest loop. Counts are not clamped or silently truncated.

Ordinary event-free procedure lowering and legacy analysis-restricted writes remain separately gated. The structural target parser recognizes the emitted grammar, including event arities, lifecycle filters, nesting, expressions, and allowed tasks. It is not a general Verilog-A parser or a numerical simulator.

## Executable evidence

| Layer | Entry point |
|---|---|
| Public construction | `nodal.increment37fixture.Increment37ConstructionCheck` |
| Construction and bridge tests | `AnalogEventConstructionTests`, `AnalogEventBridgeTests` |
| Separately compiled source witness | `nodal.internal.testkit.Increment37MlirCheck` |
| Native and source-to-target matrix | `tests/compiler/fixtures/increment37/run_native_matrix.py` |
| Ordered reads, monitor loops, held-state failures, exact sample/hold golden | `tests/compiler/fixtures/increment37/run_review_matrix.py` |
| Repository and mutation checks | `scripts/check_increment37.py`, `tests/compiler/test_increment37.py` |
| Published-head CI | `.github/workflows/increment-37-analog-events.yml` |

The native matrices run both before and after folding, canonicalization, and common-subexpression elimination. They require identical accepted target text, preserve monitor occurrences, and reject invalid input without partial HDL. The review matrix covers captured reads across intervening writes, zero/one/multiple and nested static monitor loops, illegal runtime monitor placement, monitor/write ordering, unsafe held initialization, missing or continuous held writes, and an independently written exact sample/hold target.

Recovered candidate validation [33976368474](https://github.com/pysolvesemi/Nodal/actions/runs/33976368474) passed every executed gate for tree `7fde1282cc80349a3e6ae8f5396feb463ed538ee`. The same tree was published in [commit 7d5d1095](https://github.com/pysolvesemi/Nodal/commit/7d5d1095459660d28b2cd076575714d0ff170132). That candidate result is not final-head or post-merge evidence. The additional review matrix is qualified by the subsequent published-head workflow.

## Closure boundary

Implementation merge, exact post-merge checks, and separate immutable evidence closure remain required. The roadmap checkbox stays open until that evidence is recorded. No numerical simulation, solver accuracy, or general target-equivalence result is claimed. Numerical scheduling, analog solvers, digital/analog co-simulation, and full Verilog-AMS digital processes remain outside this increment.
