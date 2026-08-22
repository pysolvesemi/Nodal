# Formal Verification Public API and Execution Plan v0.1

**Status:** Deferred normative roadmap target  
**Architecture:** [ADR 0014](../architecture/0014-target-neutral-formal-verification.md)  
**Digital verification dependency:** [ADR 0010](../architecture/0010-digital-verilog-open-source-verification.md) and [`digital-verilog-open-source-verification-plan.md`](digital-verilog-open-source-verification-plan.md)  
**Plugin dependency:** [ADR 0012](../architecture/0012-versioned-capability-plugin-architecture.md)  
**Formal API gate:** Increment 109  
**Machine-readable candidate:** [`formal-verification-v0.1-surface.json`](formal-verification-v0.1-surface.json)

## Goal

Preserve a clear future path to user-authored formal verification with compact Scala ergonomics comparable to SpinalHDL, while keeping property semantics independent of SystemVerilog Assertion syntax, SBY configuration files, or one solver.

The binding rule is:

> **Author properties in Nodal semantics, preserve them in typed IR, lower only to declared tool capabilities, and retain proof and counterexample evidence.**

This plan adds no formal API or proof implementation now. It reserves the public, IR, harness, adapter, evidence, and conformance boundaries that later increments must implement. It also fixes the synthesis boundary: only an explicitly selected immediate Boolean assertion may become checker RTL; concurrent or temporal properties remain verification-only.

## Existing foundation retained

The current architecture already provides:

- authoritative MLIR and versioned bridge boundaries;
- explicit clock/reset domains and reset policies;
- explicit CDC/RDC, protocols, parameters, hierarchy, effects, and source locations;
- a portable digital Verilog backend with a formal capability profile;
- generated formal hooks and sidecar harness direction;
- Yosys/SBY synthesis, equivalence, and internal proof infrastructure in Increment 67;
- formal-tool adapters through the plugin process protocol;
- proof obligations for optimization passes;
- deterministic manifests, caching, diagnostics, and retained evidence.

The missing contract is the user-facing formal property model and its lowering/execution semantics.

## Comparison with the SpinalHDL baseline

The future Nodal surface must be able to express the useful compact baseline demonstrated by SpinalHDL:

- `assert`, `assume`, and `cover`;
- `past`, `rose`, `fell`, `changed`, and `stable`;
- initial-state and history-validity helpers;
- symbolic sequence and constant values;
- bounded model checking, prove/induction, and cover tasks;
- clock/reset-aware formal harnesses;
- direct invocation of an open-source formal stack.

Nodal intentionally does not copy SpinalHDL implementation details or expose SVA strings. It adds stronger target-neutral IR, domain, capability, evidence, vacuity, contract, and adapter boundaries from the start.

## Candidate public surface

The following spellings are directional only. Increment 109 evaluates Scala ambiguity, consistency with `Module`, `ClockDomain`, `when`, and simulation APIs, and freezes the smallest coherent surface.

### Embedded properties

```scala
final class Queue extends Module:
  // ordinary design

  formal:
    assert(count <= depth)
    assume(inputProtocolLegal)
    cover(full && output.fire)
```

Potential named variants:

```scala
formal:
  assert(
    property = count <= depth,
    id = "queue.occupancy.bound",
    message = "occupancy exceeded configured depth"
  )
```

### Sampled history

```scala
formal:
  when(pastValidAfterReset()):
    assert(past(stream.valid && !stream.ready) ==> stream.valid)
    assert(!(changed(stream.payload) && stream.valid && !stream.ready))
```

### Explicit domain and reset policy

```scala
formal(domain = bus, reset = FormalReset.DisableWhileActive):
  assert(...)

formal(domain = bus, reset = FormalReset.EnabledDuringReset):
  assert(...)
```

The exact syntax may instead use lexical domain blocks plus property options. The semantics are mandatory: domain, edge, reset disable/enable, and history validity are explicit in IR.

### Formal harness

```scala
Nodal.formal(
  dut = new Queue(depth = 4),
  task = FormalTask.Prove(depth = 40),
) { dut =>
  val push = anySeq(Bool)
  val pop = anySeq(Bool)

  assume(protocolLegal(push, pop))
  assert(dut.occupancy <= 4)
  cover(dut.occupancy === 4)
}
```

Alternative names such as `FormalDut`, `FormalConfig`, `withBmc`, `withProve`, or `withCover` are compile candidates, not accepted names.

## Public property kinds

Increment 109 freezes at least:

```text
assert
assume
cover
```

Required semantics:

- `assert` states a guarantee expected to hold under the active assumptions;
- `assume` constrains only the declared environment/property scope;
- `cover` asks for a reachable witness and is not proof of correctness;
- property IDs are stable across deterministic re-elaboration;
- source locations, messages, tags, groups, and generated provenance survive lowering;
- Scala runtime assertions remain distinguishable at compile time;
- simulation-only assertions are not silently promoted into formal assumptions or guarantees.

A later gate may add `restrict`, fairness, or richer contract forms only when multiple engine semantics are reconciled.

## Property contexts and inclusion

Properties can be:

- embedded in a reusable module;
- contributed by an explicitly enabled passive property library;
- attached by a formal harness through stable selectors/metadata;
- generated by the compiler for a primitive or transformation invariant;
- supplied by an explicitly enabled verification plugin.

Each property declares inclusion policy:

```text
formal-only
simulation-and-formal
simulation-only
explicit synthesized immediate assertion
```

Installing a property library or adapter does not execute proofs automatically. Projects select property groups and tasks explicitly.

## Immediate assertion synthesis boundary

The synthesis contract is deliberately smaller than the formal property contract.

Only an immediate Boolean assertion may be selected explicitly for synthesis. Such a checker observes values at its declared combinational point or owning clock domain and emits a deterministic failure indication. The default checker is observational: it cannot reset, stall, gate, mutate, or otherwise control functional state. A design may use the failure indication functionally only through an ordinary, explicit Nodal connection that is visible to normal review, timing, CDC/RDC, and synthesis checks.

The following are never synthesis-eligible:

- concurrent or temporal properties and sequences;
- `past`, `rose`, `fell`, `changed`, `stable`, or other sampled-history operators;
- `assume`, `cover`, fairness, liveness, and environment constraints;
- symbolic formal values or constants;
- history registers, automata, counters, or monitors generated only to execute a verification property.

Those constructs may lower to verification-only immediate checks, monitor logic, SVA, sidecar harnesses, or engine-native input. They never enter the `digital-verilog-synth` DUT artifact. A request to synthesize an ineligible property is a stable error; the compiler never approximates it as an immediate assertion.

## Clock/reset contract

### Clocked properties

A clocked property has:

- one owning `ClockDomain`;
- one sampled edge;
- one reset enable/disable policy;
- one history-validity state;
- one property enable condition;
- source and domain provenance.

The current lexical domain may provide defaults only when unambiguous. An unbound property in a sequential context is an error.

### Combinational properties

A combinational assertion or assumption is explicitly declared and evaluated without sampled history. It cannot use `past`, temporal delay, or edge operators. Only an immediate Boolean assertion in this class can be synthesis-eligible; a combinational assumption remains verification-only.

### Reset

Candidate reset policies include:

```text
disable while reset is active
enabled during reset
disable until reset has been observed and released
explicit enable expression
```

The chosen policy is stored in IR and emitted as a capability-checked enable/disable condition. It is not inferred from one proof engine's default.

### Multi-clock restrictions

One temporal property cannot treat unrelated domains as one cycle stream. Legal alternatives are:

- observe synchronized values in one destination domain;
- use a protocol/transaction property with explicit source and destination events;
- use a separately gated multi-clock property construct supported by the selected runner;
- decompose the proof into per-domain assume-guarantee contracts.

## Sampled-value operators

The initial candidate set is:

```text
past(value, cycles = 1)
rose(value)
fell(value)
changed(value)
stable(value)
initState()
pastValid()
pastValidAfterReset()
```

Rules to freeze:

- type and domain constraints;
- legal cycle-delay expressions;
- first-cycle and reset history semantics;
- behavior for four-state/unknown values where a selected profile supports them;
- deterministic verification-only lowering to history registers, CIRCT operations, SVA, or engine-native forms;
- source-map behavior for compiler-generated history state;
- diagnostics for unguarded or invalid history access.

`past` delay is initially a non-negative elaboration constant or approved symbolic constant supported by every selected task case. Runtime-variable history depth is not part of v0.1.

## Temporal property subset

The initial public gate evaluates a typed subset rather than full SVA grammar:

- Boolean implication and equivalence;
- exact and bounded cycle delay;
- bounded consecutive repetition;
- bounded eventuality;
- `until` with explicit weak/strong policy;
- sequence concatenation where deterministic lowering exists;
- property enable/disable conditions;
- explicit clocking.

Every operator declares:

- whether it forms a Boolean, sequence, or property;
- sampling domain and edge;
- bounded versus unbounded semantics;
- strong/weak and vacuity behavior;
- required target/engine capabilities;
- permitted simulation-monitor lowering;
- synthesis eligibility, which is always false for temporal or concurrent properties.

Unsupported temporal constructs are rejected; they are never shortened, bounded, or weakened silently.

## Symbolic values and constants

Candidate formal-only constructors:

```text
anySeq(type)
anyConst(type)
allSeq(type) when supported
allConst(type) when supported
```

Required behavior:

- values are typed and source-located;
- sequence values may vary each formal step;
- constant values remain fixed for a task instance;
- parameter relations and legal ranges are explicit assumptions/contracts;
- symbolic values do not appear in synthesis output;
- simulator replay receives concrete values from a counterexample trace rather than regenerating symbols.

## Formal task model

Candidate task kinds:

```text
BMC / bounded safety
Prove / induction-based or unbounded safety
Cover / witness search
Liveness when explicitly supported
Equivalence and refinement through existing verification infrastructure
```

A normalized task descriptor includes:

- stable task ID;
- selected top/harness and parameter cases;
- property groups and exclusions;
- task kind and depth;
- engines and solvers;
- timeout, memory, threads, and seed policies;
- clock/reset/initial-state policy;
- fairness and liveness constraints;
- memory and black-box model policy;
- expected result and CI tier;
- evidence and cache policy.

The project may provide a compact Scala builder and a checked-in TOML/JSON form. Both normalize to the same task IR/manifest.

## Target-neutral formal IR

Increment 110 implements Nodal formal operations or verified conversions for:

- formal test/harness regions;
- property IDs/groups/tags;
- assert/assume/cover;
- clock, edge, reset-disable, and enable metadata;
- symbolic values/constants;
- sampled-history operations;
- typed sequences and temporal properties;
- immediate-versus-temporal classification and explicit synthesis eligibility;
- contract require/ensure;
- fairness/liveness declarations;
- formal model references;
- task capability requirements;
- diagnostic and source-map provenance.

Selective CIRCT reuse candidates:

- `verif.assert`, `verif.assume`, `verif.cover`;
- clocked verification operations;
- `verif.formal` and `verif.symbolic_value`;
- `verif.contract`, `verif.require`, and `verif.ensure`;
- `verif.bmc`, LEC, and refinement operations where useful;
- `ltl` sequence/property types and temporal operations.

A conversion is accepted only when Nodal's domain/reset/parameter/source-map semantics are preserved. Nodal-owned operations remain valid when the pinned CIRCT revision lacks a required feature.

## Lowering profiles

### Portable open-source profile

Lower to:

- portable immediate assertions/assumptions/covers in verification artifacts;
- generated verification-only history and monitor logic;
- explicit checker RTL only for synthesis-selected immediate Boolean assertions;
- Yosys-recognized formal attributes/cells where required;
- sidecar harness modules and SBY task files;
- deterministic property/task manifests.

### Future SystemVerilog/SVA profile

A future profile may render supported temporal properties as SVA. It is not the definition of the public API and does not replace the portable open-source profile.

### Engine-native profile

A formal adapter may consume Nodal/CIRCT IR directly only through a versioned capability contract. Engine-native lowering remains reproducible and retains a human-reviewable normalized representation.

## Harness and DUT access

A harness may:

- instantiate a DUT with explicit parameters;
- drive input ports from symbolic or constrained values;
- generate legal clocks/resets from domain metadata;
- observe public ports and explicitly exported verification signals;
- attach properties through stable selectors and sidecar metadata;
- instantiate formal models for black boxes/external operations;
- create formal-only scoreboards, monitors, and transaction trackers.

A harness may not:

- depend on compiler-internal object identity;
- mutate the elaborated DUT after verification boundaries close;
- bypass domain, width, effect, or connection rules;
- access arbitrary private internals without an explicit verification-export contract;
- redefine a black box as unconstrained without recording the abstraction.

## Compositional contracts

Reusable components may publish target-neutral contracts:

```text
require: legal environment assumptions
ensure: guarantees under those requirements
```

The compiler can:

- check a component contract by assuming requirements and asserting guarantees;
- apply an approved contract by asserting caller obligations and assuming callee guarantees;
- retain contract identity, proof evidence, parameter envelope, and source mapping;
- reject cyclic or unsound assume-guarantee composition.

Contracts are passive metadata/property content until an explicit formal task selects them.

## Memory, external-operation, and black-box models

Every nontrivial opaque operation declares one of:

- exact formal implementation model;
- assume-guarantee contract;
- uninterpreted abstraction with explicit soundness scope;
- finite memory abstraction with declared read/write/collision behavior;
- unsupported for the selected formal profile.

Unknown outputs are not silently unconstrained. A waiver contains a stable ID, reason, affected properties/tasks, and evidence limitation.

## Parameterized proofs

Tasks declare one of:

- symbolic parameter proof supported by the selected representation/engine;
- finite parameter matrix;
- explicit specialization artifact with distinct identity;
- bounded parameter envelope plus a sound abstraction.

A proof for one parameter case is not reported as proof of the generic module. Results identify exact parameter values or symbolic assumptions.

## Assumptions, fairness, and over-constraint

Assumptions are grouped and named. Reports retain:

- active assumptions per task;
- source and property IDs;
- assumptions supplied by the harness, DUT, contract, or adapter;
- fairness/liveness assumptions;
- assumption consistency checks;
- cover reachability for important antecedents/scenarios;
- over-constraint or vacuity findings where supported.

The compiler/tool adapter must not make a failing assertion pass by inserting hidden assumptions.

## Results and evidence

Normalized result states include:

```text
proven
failed
covered
unreachable
inconclusive
unsupported
timed-out
cancelled
tool-error
```

Evidence contains:

- source, IR, HDL, harness, property, task, plugin, and tool hashes;
- exact active assumptions and property groups;
- tool/runner/solver versions and commands;
- depth, induction, fairness, memory, and parameter configuration;
- stdout/stderr and normalized diagnostics;
- proof certificates when available;
- counterexample or cover trace;
- property coverage/vacuity findings;
- cache provenance and reproduction command.

## Counterexample and cover replay

Increment 113 integrates normalized traces with the Scala simulation API.

Replay provides:

- typed signal and aggregate values;
- clock/reset-domain timelines;
- `Valid`/`Stream` transaction reconstruction;
- property failure/cover annotations;
- VCD/FST generation;
- source-location navigation;
- optional trace minimization while retaining original evidence.

Replay must fail explicitly when simulator semantics cannot reproduce a formal abstraction or unknown-state assumption.

## Reusable property libraries

Future passive property libraries can provide contracts and property groups for:

- plain/`Valid`/`Stream` protocols;
- FIFOs and queues;
- handshake bridges;
- fixed/valid/elastic pipelines;
- memories;
- clock/reset controllers;
- synchronizers and CDC/RDC wrappers;
- arbiters, encoders, and common control structures.

Libraries use only the frozen public formal API and cannot execute tools. Optional generator or report plugins remain separately enabled executable artifacts.

## Diagnostics to freeze

Increment 109 freezes stable categories for at least:

- Scala runtime assertion used as a formal property;
- property without an owning domain;
- illegal cross-domain temporal sampling;
- sampled-history access without valid history policy;
- unsupported temporal operator/profile;
- assumption outside legal environment scope;
- contradictory or unused assumption where detectable;
- formal-only value leaking into synthesis/simulation;
- implicit synthesis of an assertion without an explicit immediate-checker policy;
- concurrent or temporal property requested for synthesis;
- assumption, cover, sampled history, or symbolic formal value requested for synthesis;
- synthesized immediate assertion attempting to alter functional behavior without an explicit design connection;
- unsupported memory or black-box model;
- missing formal model for an external operation;
- property ID collision;
- task/property group mismatch;
- unsupported parameter proof mode;
- unavailable proof runner/solver capability;
- malformed counterexample or coverage result;
- inconclusive proof reported as success;
- hidden or adapter-injected assumption;
- vacuous property result where a required non-vacuity check fails.

## Positive fixture matrix

- embedded assert/assume/cover;
- shared simulation/formal invariant;
- explicit synthesized immediate assertion with a deterministic observational failure indication;
- the same immediate invariant excluded from synthesis by default;
- explicit formal-only block;
- lexical and explicit domains;
- reset-disabled and reset-active properties;
- every sampled-value operator;
- exact and bounded temporal properties;
- symbolic sequence and constant values;
- BMC, prove, and cover tasks;
- parameter matrix;
- memory model;
- black-box contract;
- module contract check and application;
- sidecar harness;
- internal compiler-generated property plus user property;
- counterexample replay;
- property library external consumer;
- SBY adapter plus a mock second adapter using the common protocol.

## Negative fixture matrix

- raw SVA string in ordinary public API;
- unclocked use of sampled history;
- unrelated-domain sampling without an explicit contract;
- reset semantics inferred ambiguously;
- `past` with illegal depth;
- temporal operator unsupported by selected profile;
- assumption that constrains a DUT-owned output illegally;
- formal symbol in synthesis output;
- unmodeled black box or external operation;
- hidden unconstrained memory behavior;
- contradictory assumptions;
- task falsely declaring an inconclusive result proven;
- property ID collision;
- unsupported symbolic parameter proof;
- engine-specific option leaking into core property semantics;
- malformed or source-unmapped counterexample;
- plugin that inserts an undeclared assumption;
- implicit synthesis of an ordinary assertion;
- temporal or concurrent property requested for synthesis;
- assumption or cover requested for synthesis;
- sampled-history or symbolic formal value used by a synthesized immediate assertion.

## Deferred incremental delivery

### Increment 109 — Formal verification architecture gate and public API v0.1 contracts

- [ ] Use ADR 0014, this plan, and the machine-readable surface as mandatory candidates.
- [ ] Compile and compare concise `formal` context, assert/assume/cover, property IDs/groups, sampled-value operators, symbolic values, harness, contract, and task configuration candidates.
- [ ] Freeze clock/reset, combinational, cross-domain, parameter, memory, black-box, assumption-scope, vacuity, result, source-map, and immediate-assertion-only synthesis semantics.
- [ ] Publish `NodalFormalVerification-DG-v0.1.md`, machine-readable frozen API/task surfaces, migration/compatibility policy, and positive/negative external-consumer fixtures.
- [ ] Keep property execution, lowering, and adapters inert until the gate is approved.

### Increment 110 — Target-neutral formal property IR, verifier, and lowering framework

- [ ] Implement formal test/harness, property, symbolic-value, sampled-history, bounded-temporal, contract, enable/reset, and formal-model operations with stable IDs and source maps.
- [ ] Selectively reuse CIRCT `verif` and `ltl` only through verified conversions; retain Nodal-owned operations where semantics differ or capabilities are missing.
- [ ] Add property/domain/reset/type/capability verification, immediate-versus-temporal classification, deterministic parse/print, normalized reports, verification-only immediate/monitor/sidecar lowering, and explicit immediate-checker RTL lowering.
- [ ] Prove concurrent or temporal properties, sampled history, assumptions, covers, symbolic values, and generated verification monitors cannot enter ordinary synthesis artifacts; prove immediate assertions synthesize only through explicit inclusion.

### Increment 111 — Formal harnesses, symbolic environments, compositional contracts, and model abstractions

- [ ] Implement DUT wrappers, symbolic sequence/constants, initial assumptions, legal clock/reset generation, stable internal verification exports, property groups, and reusable harness composition.
- [ ] Implement memory, external-operation, and black-box formal models/abstractions with exact soundness and waiver reporting.
- [ ] Implement require/ensure contract checking and application, assume-guarantee composition, parameter matrices/envelopes, and conservative multi-clock handling.
- [ ] Add over-constraint/hidden-assumption diagnostics and declaration-order determinism fixtures.

### Increment 112 — Pluggable proof execution, proof modes, and normalized evidence

- [ ] Implement SBY/Yosys as the required open-source adapter through the common process/evidence protocol, with BMC, prove/induction, cover, selected liveness, engines/solvers, timeout, and resource controls.
- [ ] Add an out-of-tree mock/second formal adapter conformance fixture so the public semantics are not coupled to SBY files.
- [ ] Normalize per-property/task results, commands, logs, traces, proof metadata, coverage, source maps, cache keys, and reproduction commands.
- [ ] Reject unsupported capabilities before execution and preserve incomplete/timeout/tool-error states without reporting success.

### Increment 113 — Property libraries, vacuity/coverage, counterexample replay, documentation, and conformance

- [ ] Publish passive property libraries for protocols, FIFOs, pipelines, resets, CDC/RDC wrappers, memories, and common control structures using only public APIs.
- [ ] Implement assumption consistency, antecedent/scenario cover goals, supported vacuity checks, over-constraint reports, and defined property/scenario coverage metrics.
- [ ] Replay normalized counterexamples/covers through the Scala simulation API with typed transactions, domain timelines, source annotations, and waveforms.
- [ ] Publish tutorials, migration examples, adapter/property-library author guides, conformance suites, capability matrices, known limitations, and reproducible release evidence.

## Gate exit criteria

Increment 109 may be checked only when:

1. `NodalFormalVerification-DG-v0.1.md` is approved;
2. property, sequence, clock/reset, symbolic-value, harness, contract, and task semantics are frozen without backend spelling;
3. Scala runtime assertions and simulation/formal statements are unambiguous;
4. only explicitly selected immediate Boolean assertions are synthesis-eligible, and all temporal or concurrent properties remain verification-only;
5. cross-domain and reset behavior is explicit;
6. assumptions and guarantees have enforceable scopes;
7. parameter, memory, external-operation, and black-box proof policies are explicit;
8. result states distinguish proven, failed, covered, inconclusive, unsupported, timeout, and tool errors;
9. vacuity, over-constraint, and hidden-assumption expectations are documented;
10. source maps, property IDs, traces, and reproducibility fields are frozen;
11. external consumer fixtures use no compiler internals;
12. no formal execution or hidden property inclusion occurs during the gate; and
13. Core CI passes.

## References

- SpinalHDL formal verification: <https://spinalhdl.github.io/SpinalDoc-RTD/master/SpinalHDL/Formal%20verification/index.html>
- CIRCT Verif dialect: <https://circt.llvm.org/docs/Dialects/Verif/>
- CIRCT LTL dialect: <https://circt.llvm.org/docs/Dialects/LTL/>
- SBY formal verification: <https://yosyshq.readthedocs.io/projects/sby/en/stable/>
