# ADR 0019: Require staged hardware quality gates before accepted emission

- **Status:** Accepted
- **Date:** 2026-08-22
- **Scope:** Elaboration checks, semantic verification, latches, loops, hierarchy, drivers, CDC/RDC, target legalization, HDL reparse, lint, synthesis smoke checks, diagnostics, waivers, and release evidence

## Context

A software-based HDL should catch structural and semantic hardware errors before users debug generated Verilog. Current SpinalHDL documents early checks for assignment overlap, clock crossings, hierarchy violations, combinational loops, latches, undriven signals, width mismatches, unreachable selections, out-of-range constants, and related design errors.

Nodal's roadmap already includes exact widths, domains, CDC/RDC, enum/FSM checks, pipeline verification, analog lint, external lint/simulation, Yosys synthesis/equivalence, and target capability profiles. It did not yet define one mandatory staged quality contract or make clear that generated HDL is accepted only after internal verification.

Nodal also covers semantics beyond a conventional digital HDL: symbolic parameters, multidimensional shapes, physical dimensions, analog contributions/events, mixed-signal conversion, plugins, optimization passes, automatic pipelines, reusable statecharts, and AMS-to-FPGA approximation. Those features require a broader quality gate than relying on a downstream linter.

## Decision

Nodal uses a **transactional, staged verification pipeline**. No HDL artifact is reported as accepted output until every mandatory internal gate for the selected design/profile succeeds. External parsers, linters, synthesis, simulation, equivalence, and formal tools provide independent defense in depth rather than replacing Nodal checks.

The binding rule is:

> **Reject invalid hardware at the highest semantic layer, reverify after every lowering, and accept emitted HDL only with retained evidence.**

The architecture target is to cover every published SpinalHDL safety-check category and add Nodal-specific checks. This is an implementation/evidence requirement, not a present claim that unfinished Nodal already has equal coverage.

## Transactional emission

`Nodal.emit` conceptually executes:

```text
construct
  -> elaborate
  -> semantic verify
  -> normalize/optimize
  -> reverify
  -> target legalize
  -> target verify
  -> render
  -> reparse/lint
  -> optional synthesis/equivalence/formal profile
  -> accept emission and evidence
```

On failure:

- no output is marked accepted;
- partial files remain only in a diagnostic staging directory when requested;
- the last accepted IR/artifact remains intact;
- diagnostics, source paths, tool logs, and reproduction commands are retained;
- plugins or passes cannot suppress mandatory core verifiers.

## Gate 1: Scala/API and construction checks

The construction layer checks before authoritative MLIR creation:

- public API type correctness and stage separation;
- source ownership, scope, and hierarchy access;
- module lifecycle and recursive instantiation;
- duplicate names and incompatible explicit names;
- port direction and connection ownership;
- missing, duplicate, partial, or conflicting connections;
- rank, shape, parameter, range, and index legality;
- signedness, width, literal, enum, and conversion rules;
- FSM definition, transition, and recursion contracts;
- loop/generate stage and bound legality;
- clock/reset-domain availability and binding;
- illegal dynamic capture by reusable definitions;
- unsupported backend-neutral constructs.

Compile-negative fixtures freeze diagnostic codes and source locations for every public contract.

## Gate 2: Elaborated semantic graph checks

After construction closes, Nodal checks the complete graph:

- exactly one or explicitly resolved driver per value/field/element;
- no undriven required signal or output;
- no illegal read-before-definition;
- total combinational assignment coverage;
- no accidental latch inference;
- no combinational cycle, including aggregate/index, function, protocol-ready, and generated paths;
- no multiple state drivers or inconsistent reset values;
- no partial state assignment outside a legal update contract;
- hierarchy and scope legality across every instance;
- no illegal recursive hardware hierarchy;
- exact width, signedness, shape, enum, protocol, and domain compatibility;
- CDC/RDC, pulse, reconvergence, reset release, gate/mux, and waiver correctness;
- memory port, collision, initialization, and latency contracts;
- automatic-pipeline acyclicity, latency, capacity, ready-loop, and sideband alignment;
- FSM reachability, dead ends, transition overlap, encoding, join, and stack bounds;
- structural `Vec` versus `Mem` storage intent;
- effect, observability, and movable-boundary legality;
- analog node/branch/discipline/unit/contribution/event consistency;
- mixed-signal sampling/drive and scheduling legality.

Accidental digital latches are errors. A future latch feature requires an explicit `Latch`-class semantic primitive and profile; partial combinational assignment never silently requests one.

Digital combinational cycles are errors. Analog algebraic loops are a separate analog semantic category and are accepted only where the selected solver/profile defines and validates them.

## Gate 3: Authoritative MLIR verification

Nodal and selectively reused CIRCT verifiers check:

- operation/type/attribute/region legality;
- symbol definitions and references;
- SSA dominance and use legality;
- module and instance signature agreement;
- parameter constant-expression legality;
- shape/layout/storage invariants;
- domain, effect, latency, and source-origin metadata;
- recursive instantiation and combinational cycles;
- enum/FSM and pipeline invariants;
- analog and mixed-signal operation restrictions;
- plugin-owned operation namespace/version/capability contracts.

Every semantic pass declares preserved/invalidated analyses. Invalidated analyses are recomputed, and mandatory verifiers run after the pass or verified pass group.

## Gate 4: Target-profile legalization and verification

Before rendering, the selected backend/profile verifies:

- every construct has a legal representation;
- portable Verilog ports contain no illegal unpacked arrays or SystemVerilog-only syntax;
- SystemVerilog packed/unpacked layouts match the ABI policy;
- declarations preserve sign, width, net/variable kind, and four-state policy;
- generated temporary/materialization decisions preserve expression semantics;
- parameters, localparams, generates, loops, enums, arrays, and hierarchy are constant/legal;
- procedural assignments are complete and do not infer unintended latches;
- no illegal multiple drivers or continuous/procedural conflicts;
- memories and structural vectors follow their storage contracts;
- clocks/resets/processes/CDC/RDC lower exactly;
- analog contributions/events/connect rules and mixed-signal constructs satisfy the target profile;
- source maps cover every emitted declaration/process/expression span required by diagnostics;
- unsupported constructs fail before text emission.

## Gate 5: Render, reparse, and structural round trip

The generated HDL is reparsed by the Nodal target parser or approved structured target IR where available. The round trip checks:

- syntactic validity;
- declaration and type reconstruction;
- parameter and generate structure;
- module/port/instance agreement;
- shape/layout and flattening formulas;
- signedness and explicit casts;
- process completeness and driver classes;
- target capability conformance;
- source-map continuity;
- deterministic parse/print normalization.

A raw text plugin cannot bypass this gate. ADR 0013's transactional pass rules apply to any semantic post-render transformation.

## Gate 6: Independent digital lint and synthesis checks

For the default/release digital profiles, generated portable Verilog is independently checked with pinned tools:

- Verilator lint and parse;
- Icarus Verilog parse/elaboration and event-driven smoke simulation;
- Yosys parse, hierarchy check, process lowering, `check`, memory audit, and target-neutral synthesis smoke;
- parameter elaboration matrices for symbolic shapes/widths/generates;
- optional cocotb interoperability;
- equivalence/formal tasks where required by the profile or optimization.

The independent matrix checks at least:

- latches;
- combinational loops;
- multiple/conflicting drivers;
- undriven/unused/unconnected signals under policy;
- width/signedness/constant-range issues;
- hierarchy/top/black-box errors;
- unsupported or non-portable syntax;
- generate/elaboration failures;
- unexpected memory inference for structural values;
- reset/state/protocol properties;
- simulator/synthesizer disagreement.

Nodal maps external diagnostics back to source and classifies them as frontend, compiler, backend, tool, portability, or unsupported-feature failures.

## Gate 7: Analog and AMS checks

Analog and mixed-signal profiles use:

- Nodal analog semantic lint;
- OpenVAF compilation for supported Verilog-A;
- ngspice and optional second-tool differential regression;
- AMS compiler/simulator adapters where available;
- equation/unit/branch/event/connect-rule invariants;
- bounded waveform/event tolerances;
- digital-partition lint/synthesis/formal where applicable.

A tool's acceptance does not override a Nodal semantic error.

## Check profiles

Candidate profiles are:

```text
CheckProfile.Fast
CheckProfile.Default
CheckProfile.Release
```

`Fast` runs all mandatory internal safety gates and skips expensive independent tool matrices not required for interactive development.

`Default` adds target reparse and the normal available lint/compile tools for the selected backend.

`Release` adds the full pinned portability, synthesis, parameter-matrix, equivalence/formal, reproducibility, and evidence requirements.

The exact option names are frozen in Increment 15. A profile may add checks, but no profile may disable mandatory type, driver, latch, cycle, hierarchy, domain, storage, effect, or target-legality checks.

## Severity and waiver policy

Safety and semantic violations are errors by default. Warnings are reserved for quality, portability, unused/dead behavior, suspicious-but-legal constructs, and explicitly bounded risks.

A waiver is typed and records:

- stable waiver ID;
- check code;
- exact object/path/scope;
- source location;
- reason and owner;
- optional expiry/version;
- declared risk and required external evidence;
- whether it affects simulation, synthesis, formal, AMS, or portability.

Blanket suppression of a category is not part of the normal API. Waivers never erase provenance or prevent downstream tools from reporting the issue.

## Diagnostic quality

A diagnostic includes where applicable:

- stable code and severity;
- primary source location;
- related declarations and assignments;
- complete hierarchy path;
- combinational/CDC/RDC/driver path;
- parameter values or symbolic constraints;
- expected and actual type/width/shape/domain/effect;
- backend/profile/tool capability;
- suggested explicit repair, never a silent fallback;
- retained IR/HDL/tool evidence.

For cycles and crossings, Nodal reports the shortest useful path plus additional related paths where needed. For aggregate/shape errors, it reports the exact field and multidimensional index formula.

## Quality inventory and conformance

Nodal publishes a machine-readable check inventory containing:

- stable check ID;
- phase/gate;
- category;
- default severity;
- applicable design/backends;
- waiver policy;
- positive and negative fixture IDs;
- internal/external implementation status;
- source-map requirements;
- release-profile evidence.

The conformance suite includes every current published SpinalHDL design-error category plus Nodal-specific checks for parameters, shapes/layout, signedness, enums/FSMs, pipelines, effects, units, analog/AMS, plugins, passes, and approximation.

No release may claim equal-or-greater check coverage without the inventory and passing negative fixtures.

## Consequences

### Positive

- Errors are reported at the semantic layer where users can fix them.
- Generated HDL is independently checked instead of trusted blindly.
- No compiler pass or plugin can quietly bypass core safety verification.
- Latches, loops, CDC/RDC, hierarchy, width/sign, and driver issues are caught before accepted output.
- Target-specific array, signedness, temporary, and syntax limitations are verified explicitly.
- Release quality is measurable through a stable check inventory and evidence.
- Analog and mixed-signal correctness receives dedicated checks rather than digital-only assumptions.

### Costs

- Verification analyses and negative fixtures are substantial implementation work.
- Fast/default/release profiles need carefully defined performance boundaries.
- External-tool adapters and version pins require maintenance.
- Some diagnostics require path reconstruction and source-map infrastructure.
- Cross-tool disagreement needs classification rather than one simple pass/fail result.

## Rejected alternatives

- **Rely on Verilator/Yosys after emission:** too late and loses high-level semantic context.
- **Run only internal checks:** misses parser, synthesis, and portability discrepancies.
- **Treat warnings as success for latches or CDC:** permits broken hardware.
- **Allow backend generation despite failed internal verification:** creates misleading artifacts.
- **Let plugins disable verifiers:** breaks the language safety contract.
- **Use one global strict flag:** does not distinguish mandatory safety from optional expensive evidence.
- **Claim parity with another HDL without conformance fixtures:** unverifiable marketing rather than engineering evidence.

## Follow-up increments

- Increment 13 compiles check-profile, waiver, explicit-latch rejection, and diagnostic candidates.
- Increment 15 freezes public check/profile/waiver contracts.
- Increments 16-22 implement construction, graph, IR, target, and diagnostic gates.
- Increment 26 proves deterministic diagnostics and evidence.
- Increment 46 implements analog semantic lint.
- Increments 54-65 implement digital semantics and backend legality checks.
- Increment 66 integrates independent lint/parse/simulation.
- Increment 67 integrates synthesis, memory audit, equivalence, and formal readiness.
- Increment 71 implements full mixed-domain verification.
- Increment 72 verifies Verilog-AMS target lowering.
- Increments 83-88 enforce verifier and proof obligations for plugins/passes.
- Increments 92, 96, 97, and 98 publish documentation, performance, coverage review, and release evidence.

## References reviewed

- SpinalHDL design errors: <https://spinalhdl.github.io/SpinalDoc-RTD/master/SpinalHDL/Design%20errors/index.html>
- SpinalHDL clock crossing diagnostics: <https://spinalhdl.github.io/SpinalDoc-RTD/master/SpinalHDL/Design%20errors/clock_crossing_violation.html>
- CIRCT passes and combinational-cycle checks: <https://circt.llvm.org/docs/Passes/>
