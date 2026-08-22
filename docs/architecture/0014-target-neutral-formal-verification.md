# ADR 0014: Use target-neutral, domain-aware formal properties with pluggable proof engines

- **Status:** Accepted
- **Date:** 2026-08-21
- **Scope:** Future user-authored digital formal properties, explicit synthesized immediate assertions, formal harnesses, symbolic values, temporal semantics, proof tasks, tool adapters, evidence, and counterexample replay

## Context

Nodal already has the main structural prerequisites for formal verification:

- MLIR is the authoritative compiler IR;
- clock/reset domains, CDC/RDC, protocols, parameters, hierarchy, source locations, and effects remain explicit;
- the portable digital backend has a `digital-verilog-formal` capability profile;
- ADR 0010 plans Yosys/SBY proof infrastructure and retained counterexamples;
- ADR 0012 provides versioned out-of-process formal-tool adapters;
- ADR 0013 lets optimization passes carry equivalence and formal proof obligations.

Those boundaries are sufficient for compiler-generated assertions and internal proof suites, but they do not yet define a scalable public formal-verification feature comparable in usability to SpinalHDL. A future user-facing capability needs more than invoking SBY: it needs language-level property semantics, sampled-value operators, symbolic environments, explicit clock/reset behavior, harness and black-box contracts, proof modes, vacuity/constraint checks, typed counterexamples, and stable portability rules.

SpinalHDL demonstrates a useful compact baseline with `assert`, `assume`, `cover`, sampled-history helpers such as `past`, `rose`, `fell`, `changed`, and `stable`, symbolic values, and direct bounded/prove/cover execution. Nodal should preserve that usability while avoiding a permanent dependency on SystemVerilog Assertion spelling or one proof engine.

CIRCT provides `verif` operations for assertions, assumptions, covers, symbolic values, formal tests, bounded model checking, equivalence, and compositional contracts, plus an `ltl` dialect for temporal properties and sequences. These are valuable implementation substrates where the pinned CIRCT revision matches Nodal semantics, but they do not replace Nodal's public API, domain rules, profile checks, or evidence contracts.

## Decision

Nodal reserves a future **target-neutral, domain-aware formal property layer** in the public API and authoritative IR. Proof engines and emitted assertion syntax remain replaceable adapters.

The binding rule is:

> **Author properties in Nodal semantics, preserve them in typed IR, lower only to declared tool capabilities, and retain proof and counterexample evidence.**

No user-authored formal API or proof engine is implemented by this ADR. Exact public names are frozen only by the future formal-verification design gate.

Synthesis eligibility is intentionally narrower than verification-property support. Only an explicitly selected immediate Boolean assertion may generate checker RTL. Concurrent or temporal properties and all formal environment constructs remain verification-only.

## Architectural layers

Formal verification is split into four independent layers.

### 1. Property-authoring API

A concise future Scala API will express:

- assertions, assumptions, covers, and named property groups;
- clocked and explicitly combinational properties;
- sampled history and edge/change operators;
- bounded temporal composition;
- symbolic inputs/constants and initial constraints;
- reusable component/protocol contracts;
- formal-only harness construction;
- proof-task configuration.

Directional examples are intentionally non-binding:

```scala
formal:
  assert(!(stream.valid && !stream.ready && changed(stream.payload)))
  assume(reset.isActive || requestAllowed)
  cover(transactionCompleted)

  when(pastValidAfterReset()):
    assert(past(request) ==> response)
```

The formal API is separate from Scala runtime `assert` and from ordinary simulation-only reporting. A formal-only block cannot silently change synthesizable behavior. An immediate Nodal assertion may be selected explicitly for checker synthesis, but temporal or concurrent properties never acquire synthesis eligibility.

### 2. Target-neutral formal IR

Formal constructs are preserved in Nodal MLIR with stable source locations, property IDs, domains, reset policies, messages, severity/classification metadata, and capability requirements.

The IR covers at least:

- `assert`, `assume`, and `cover` property nodes;
- formal tests and harness regions;
- symbolic sequence and constant values;
- sampled-value/history operations;
- bounded temporal sequence/property operations;
- fairness and liveness declarations where explicitly supported;
- contract `require`/`ensure` semantics;
- property groups, enable/disable conditions, and provenance;
- formal models for memories, external operations, and black boxes.

Nodal may selectively lower this layer to CIRCT `verif` and `ltl` dialects. Any missing Nodal semantic remains in Nodal-owned operations until a verified conversion exists.

### 3. Target lowering and harness generation

The formal backend lowers verified property IR to the narrowest selected capability:

- portable immediate assertions/assumptions/covers in verification artifacts accepted by Yosys;
- generated history and monitor logic confined to formal or simulation artifacts;
- explicit synthesizable checker logic only for an immediate Boolean assertion selected for synthesis;
- sidecar harness modules;
- explicit bind-style artifacts where an approved backend supports them;
- future SystemVerilog/SVA output only through a separate capability profile;
- engine-native input only through a declared adapter contract.

Property meaning is never defined by emitted text. Unsupported temporal or multi-clock semantics fail before tool invocation rather than being weakened silently.

### 4. Proof-engine adapters

SBY/Yosys is the initial open-source execution path. Future commercial or research engines use `ToolAdapterPlugin` manifests and the same normalized task/evidence protocol.

An adapter declares:

- supported property and temporal capabilities;
- proof modes and engines/solvers;
- reset, initial-state, memory, liveness, fairness, and multi-clock limitations;
- accepted input artifacts;
- task/timeout/seed/resource options;
- counterexample and coverage output formats;
- determinism and cache policy;
- version, command, environment, and license evidence.

Installing an adapter does not change property semantics, activate a proof, or alter `Backend.Auto`.

## Core property kinds

The first public gate evaluates and freezes at least:

```text
assert
assume
cover
```

`assert` states a required design guarantee. `assume` constrains the formal environment and is prohibited from hiding an implementation bug through undeclared scope changes. `cover` requests a reachable witness and does not prove correctness.

Property IDs are stable and source-located. Messages and tags survive IR, lowering, reports, and counterexample replay.

## Immediate assertion synthesis boundary

Synthesis eligibility is intentionally narrow:

- only an immediate Boolean assertion may be selected for synthesis;
- synthesis is opt-in and is never inferred from the use of `assert`;
- `assume`, `cover`, sampled-history operators, temporal or sequence operators, fairness or liveness declarations, symbolic formal values, and compiler-generated temporal monitors are never synthesis-eligible;
- generated history or monitor state for concurrent or temporal properties is confined to formal or simulation artifacts and never enters synthesizable DUT RTL;
- a synthesized immediate assertion is observational by default and produces a deterministic checker or failure indication; it does not reset, stall, mutate, or otherwise control functional state unless ordinary design logic explicitly connects that indication.

The future design gate freezes the exact public spelling and failure-export policy. The authoritative IR records immediate-versus-temporal classification and synthesis eligibility before target lowering.

## Clock and reset semantics

Clock/reset behavior is part of property meaning, not an emitter convenience.

- A clocked property captures the current lexical `ClockDomain` unless a domain is supplied explicitly.
- The sampled edge is inherited from the domain and recorded in IR.
- Reset disable behavior is explicit and derived from a declared policy; Nodal does not universally assume that every property is disabled in reset.
- Properties that must run during reset use an explicit reset scope/policy.
- `past`, edge, and stability operators require valid sampled history.
- `pastValid` and `pastValidAfterReset`-class guards are first-class semantics rather than user-maintained ad hoc registers.
- A property cannot sample unrelated domains as one timeline. Cross-domain properties require an explicit observation domain, synchronization/transaction abstraction, or a separately supported multi-clock contract.
- Combinational/unclocked assertions are explicit and cannot accidentally acquire the current clock.

## Sampled-value and temporal operations

The initial gate evaluates compact equivalents of:

```text
past(value, cycles)
rose(value)
fell(value)
changed(value)
stable(value)
initState
pastValid
pastValidAfterReset
```

The architecture also reserves typed sequence/property combinators for bounded delay, repetition, implication, `until`, and bounded eventuality. Nodal does not expose the complete SVA grammar as its public API. Operators are added only when their semantics, clocking, vacuity behavior, lowering, and tool capability requirements are frozen.

## Symbolic environment

A future formal harness may create explicit symbolic values equivalent to:

```text
any sequence value
any constant value
all-sequence/all-constant values where a selected engine defines them
```

Symbolic values are formal-only IR nodes with stable names and types. They are not random simulation values and cannot leak into synthesis artifacts.

Initial-state assumptions, parameter envelopes, legal protocol stimulus, and fairness constraints are explicit named properties. The compiler reports unused, contradictory, or over-constraining assumptions where analysis or tool support allows.

## Harness and hierarchy model

Formal verification supports both embedded properties and sidecar harnesses without duplicating the DUT's implementation model.

A future harness contract covers:

- selecting a DUT/top and parameter values or parameter envelope;
- exposing internal signals only through stable selectors/debug metadata;
- formal-only wrapper logic;
- assumptions on external environment behavior;
- black-box/external-operation formal models;
- memory initialization and abstraction policies;
- property inclusion/exclusion and proof scopes;
- reusable protocol/property libraries;
- compositional require/ensure contracts;
- assume-guarantee verification across hierarchy.

Core semantics never depend on a particular hierarchy-flattening strategy chosen by a solver.

## Proof tasks

The initial task model evaluates compact equivalents of:

```text
bounded model check
prove / induction-based safety
cover witness generation
```

Selected liveness and fairness proof modes remain capability-gated. A task declares depth, engines/solvers, timeout, reset/initial-state policy, parameter cases, memory policy, property groups, and evidence requirements.

A proof result is never reduced to one Boolean. It records proven, failed, covered, unreachable, inconclusive, unsupported, timed out, cancelled, or tool-error states per property/task.

## Vacuity, over-constraint, and coverage

A passing assertion is insufficient evidence when assumptions make its antecedent unreachable.

The future capability therefore reserves:

- assumption consistency and reachability checks;
- cover goals for property antecedents and protocol scenarios;
- vacuity classification for supported temporal properties;
- over-constraint diagnostics;
- proof depth and induction metadata;
- property and scenario coverage reports;
- explicit inconclusive results when a sound check is unavailable.

No coverage percentage is presented as proof completeness without a defined denominator and capability contract.

## Counterexamples and replay

Formal adapters return normalized typed traces mapped to Nodal signals, domains, transactions, properties, and source locations.

The Scala simulation API can replay a compatible counterexample against the same generated HDL and produce waveforms, logs, and property annotations. Replay is evidence correlation, not a second definition of formal semantics.

Counterexample minimization, trace shortening, and signal slicing are optional adapter/report passes whose transformations and hashes are retained.

## Parameterization, memories, and external operations

- Symbolic Nodal parameters remain symbolic when the selected formal engine supports them; otherwise the task declares a finite case matrix explicitly.
- Silent one-module-per-value cloning remains prohibited outside an explicit specialization artifact.
- Memories retain declared read/write/collision/reset semantics. Abstraction is explicit and proof-scoped.
- External operations and black boxes require an exact formal model, an assume-guarantee contract, or an explicit uninterpreted abstraction with stated soundness limits.
- A missing formal model is a stable error or an explicitly waived proof limitation, never an unconstrained silent fallback.

## Simulation assertions versus formal properties

Nodal may reuse the same simple immediate invariant in simulation, formal execution, and an explicitly selected synthesized checker when semantics match. The compiler still records whether a statement is:

- simulation-only;
- formal-only;
- shared simulation/formal;
- an explicitly synthesized immediate assertion.

Concurrent or temporal properties, sampled history, assumptions, covers, symbolic formal values, and generated verification monitors remain verification-only. Severity or reporting behavior in simulation does not redefine proof semantics. Assumptions are never treated as ordinary simulation assertions without an explicit harness policy.

## Digital and AMS boundary

The first user-authored formal capability is digital-only.

Analog and mixed-signal correctness may continue to use equation checks, differential simulation, bounded envelopes, and digital-partition proofs. General continuous-time formal verification requires a separate research and capability gate; this ADR does not claim that digital temporal properties prove arbitrary Verilog-A/Verilog-AMS behavior.

## Relationship to existing roadmap

Increment 67 remains the core open-source synthesis/equivalence and **internal formal-readiness** increment. It may implement compiler-generated hooks and proof suites for Nodal primitives, but it does not freeze a user-authored formal API.

The deferred formal phase after the current roadmap freezes and implements the public property layer, target-neutral formal IR, harness/contracts, pluggable proof tasks, counterexample replay, vacuity/coverage, and conformance. Those increments may be scheduled earlier once their prerequisites are complete; their placement does not make formal support a dependency of the initial core preview or AMS-to-FPGA milestone.

## Consequences

### Positive

- Nodal can later offer SpinalHDL-like formal usability without binding the language to SVA or SBY.
- Explicit domains, resets, protocols, parameters, source maps, and effects become reusable formal semantics rather than reconstructed metadata.
- CIRCT `verif`/`ltl`, Yosys/SBY, and future commercial tools can coexist behind capability-checked conversions and adapters.
- Formal-only code remains isolated from synthesis and ordinary simulation artifacts.
- Immediate assertion synthesis has a small, reviewable boundary, while temporal properties cannot add hidden monitor state to functional RTL.
- Compositional contracts, reusable property libraries, and typed counterexample replay can scale beyond unit proofs.
- Proof manifests, tool versions, options, assumptions, and traces remain reproducible and cacheable.

### Costs

- Property API, temporal IR, lowering, and tool capabilities require an additional versioned surface.
- Multi-clock, liveness, memory, and black-box semantics must be conservative and may reject unsupported cases.
- Vacuity and over-constraint analysis adds proof tasks and evidence volume.
- Different proof engines will support different capability subsets and require explicit classification.

## Rejected alternatives

- **Expose raw SVA strings:** loses type, domain, parameter, source-map, and portability guarantees.
- **Make SBY configuration the public formal API:** couples language semantics to one runner and file format.
- **Treat formal as only a backend switch:** properties, assumptions, symbolic environments, and harnesses are semantic inputs, not output formatting.
- **Use Scala simulation assertions as formal properties automatically:** simulation reporting and formal quantification have different semantics.
- **Let every property inherit reset disabling silently:** hides reset bugs and makes reusable properties context-dependent.
- **Allow implicit cross-clock temporal sampling:** creates undefined or tool-specific timelines.
- **Make commercial tools core dependencies:** prevents open, reproducible baseline use and complicates licensing.
- **Implement full SVA syntax first:** creates a large backend-shaped API before the useful portable subset and IR contracts are proven.
- **Synthesize concurrent or temporal properties into DUT monitors:** adds hidden state and timing impact to functional RTL and confuses verification semantics with implementation.
- **Treat assumptions, covers, or symbolic formal values as synthesizable:** gives environment and witness constructs an invalid hardware meaning.

## References reviewed

- SpinalHDL formal verification: <https://spinalhdl.github.io/SpinalDoc-RTD/master/SpinalHDL/Formal%20verification/index.html>
- CIRCT Verif dialect: <https://circt.llvm.org/docs/Dialects/Verif/>
- CIRCT LTL dialect: <https://circt.llvm.org/docs/Dialects/LTL/>
- SBY documentation: <https://yosyshq.readthedocs.io/projects/sby/en/stable/>
