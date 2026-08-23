# Nodal Automatic Pipeline, Interface/Role, and inout Candidates — v0.3 Evaluation

**Status:** Approved

**Scope:** public-api

**Approval boundary:** Compile-candidate evaluation only; public API v0.3 remains unfrozen

**Increment:** 14

**Freeze owner:** Increment 15

## Purpose

This gate records the compile-only evaluation required by Increment 14. Approval permits inert public prototypes for source-shape comparison, external-library compilation, and negative type contracts. It does not authorize construction state, Interface IR, role expansion, scheduling, timing estimation, resolution, AMS topology, MLIR lowering, HDL emission, simulation, synthesis, formal generation, or runtime behavior.

The binding architecture is [ADR 0008](../architecture/0008-automatic-pipeline-architecture.md) together with [ADR 0021](../architecture/0021-unified-struct-interface-role-and-inout-architecture.md), the automatic-pipeline candidate plan/surface, and the Interface/Role/inout/AMS plan/surface. Increment 13 remains the semantic dependency; none of its arithmetic, shape, quantity, memory, effect, clock/reset, CDC/RDC, enum, FSM, naming, or checking contracts are weakened.

## Evaluated public direction

### Directionless values versus connectivity

The compile prototype keeps two different kinds:

- `Struct` is a directionless `Data` value descriptor and may be used by `wire`, `Reg`, `Mem`, `Valid`, or `Stream` when its members are storable;
- `Interface` is a non-`Data` connectivity identity and therefore cannot be registered, stored, used in arithmetic, or passed where a value expression is required;
- an interface contains typed value, `Valid`, `Stream`, nested-interface, resolved-digital, conservative-terminal, and directional signal-flow member descriptions;
- an exported `InterfacePort[I, R]` selects one named `Role[R]` and preserves the logical interface identity independently of backend layout;
- `InterfaceArray` accepts static or symbolic `Dimension` counts without cloning interface definitions per parameter value.

The factory-based `Struct(...)` prototype is intentionally compared with the case-class-derived shape in ADR 0021. Increment 15 owns the final spelling and derivation mechanism; the semantic distinction is already binding.

### Named roles, protocols, and exact connection

The candidate provides generic `RoleKind` identities plus built-in `master`, `slave`, `source`, `sink`, `initiator`, `target`, `controller`, `peripheral`, `device`, `environment`, and `monitor` roles.

- per-member access is explicit through `RoleAccess`;
- nested request/response ownership remains explicit;
- `Valid` is the canonical valid-only protocol and `Stream` is the canonical elastic protocol;
- exact connection requires the same interface identity and compile-visible complementary-role evidence;
- legal automatic inversion exists only for fully complementary digital role pairs;
- monitor roles have no inverse and no drive evidence;
- protocol conversion, field adaptation, resizing, CDC/RDC, buffering, latency insertion, and domain conversion remain explicit operations rather than connection side effects;
- external libraries may define interface identities, role declarations, and additional compatibility evidence using public APIs only.

### First-class digital inout

`DigitalInout[A, M]` is not an `Expr[A]`. It exposes a typed `read` view and separate drive operations:

- push-pull and open-source modes require explicit value plus enable;
- open-drain uses `driveLow(enable)` and cannot call arbitrary value drive;
- high impedance is explicit through `highZ()`;
- `split` exposes a read/write/enable carrier for portable internal transport;
- placement records top-level, black-box, hierarchy-pass-through, pad-boundary, or internal-resolved intent;
- the resolution profile records portable-boundary-only, full resolved simulation, or technology-mapped capability;
- `passThrough` preserves the same typed resolved endpoint contract;
- `padAdapter` is explicit and source visible.

The prototype intentionally carries capability metadata without implementing resolution or silently rewriting unsupported internal tri-state structures to multiplexers.

### Conservative AMS and directional signal flow

The prototype keeps three distinct categories:

- `Terminal[D]` is a conservative physical boundary terminal;
- `TerminalView[D, A]` selects explicit `connect`, `sense`, `contribute`, or monitor access;
- `AnalogSignal[Q, R]` is a directional signal-flow value with a physical-dimension marker and source/sink/monitor direction.

Only contribute views have contribution evidence. Sense and monitor views cannot add equations. Exact conservative connection requires the same discipline type. Digital inout, conservative terminals, signal-flow values, and finite-width digital expressions have no implicit conversions.

`BridgeContract`, `MixedSignalBridge`, and `ConservativeSignalBridge` make sampling time, threshold, hysteresis, quantization, model availability, and provenance explicit. They remain scheduling and lowering barriers.

### Automatic pipeline

The selected candidate preserves the short transaction-lambda source direction while making policy explicit:

```scala
val output = pipe(
  Txn(Input(a, b, c, tag)),
  PipelinePolicy(
    latency = Latency.Auto,
    target = Some(500.MHz),
    envelopes = Seq(ParameterEnvelope(width, 8, 64))
  )
): x =>
  val sum = stage(x.a + x.b)
  Result(sameStage(sum + x.c), x.tag)
```

The candidate covers:

- plain fixed-rate `Txn`, `Valid`, and elastic `Stream` inputs without implicit protocol conversion;
- structural `delay` for plain values and all three transaction protocols;
- `Latency.Auto`, `Latency.Exact`, and `Latency.Range`;
- `Throughput.EveryCycle` and `ReadyPath.Auto`/`Combinational`/`Registered`;
- hard `stage(value)` and local `sameStage { ... }` constraints;
- explicit parameter envelopes and one envelope-safe scheduling policy;
- schedule inspection metadata without making generated numeric stage indices part of functional source;
- fixed-latency operators for plain/`Valid`/`Stream` data;
- variable-latency operators restricted to elastic `Stream` input;
- automatic sideband transport and reconvergence balancing as scheduler obligations rather than user-managed shift-register plumbing.

The prototype does not schedule anything. `pipe`, `delay`, operator, and inspection calls remain inert compile candidates backed only by placeholder values.

## Architecture comparison

### Chisel-style strengths retained

Nodal retains directionless reusable value records, typed protocol payloads, compile-time role incompatibility where Scala can express it, concise fixed/elastic transport, and compositional library definitions. It does not make a backend aggregate, `Flipped`-style inversion, or one ready/valid convention the universal connectivity model. Generic named roles, monitor access, resolved inout, and conservative AMS permissions remain first-class Nodal semantics.

### SpinalHDL-style strengths retained

Nodal retains concise bundle/protocol construction, master/slave convenience, reusable `Stream`-like transport, explicit clock-domain ownership, and high-level pipeline intent. It avoids exposing node/link/builder plumbing in ordinary datapath source and does not infer analog/conservative access by digital direction inversion. `Valid` remains the single canonical valid-only core type rather than adding a second equivalent transport kind.

### SystemVerilog role

SystemVerilog `interface`, `modport`, net types, and tri-state constructs are target representations, not Nodal source semantics. The logical Interface ABI, role access, protocol identity, resolved-net mode, and AMS access must remain identical between deterministic flattened ports and any future native interface backend. Native SystemVerilog emission remains gated by Increment 99.

### CIRCT/MLIR role

Selective CIRCT `pipeline`, ESI, `seq`, and `sv` reuse remains an implementation option after Nodal construction closes and the semantic verifier has preserved transaction identity, role ownership, latency, capacity, effects, domains, parameter envelopes, resolution, and source provenance. CIRCT operation names and graph plumbing are not exposed in this public candidate.

## Positive compile coverage

`examples.interfacePipelineApi` covers:

- storable directionless `Struct` values;
- non-storable interfaces with plain, `Valid`, `Stream`, nested, resolved-digital, conservative, and signal-flow members;
- source/sink, initiator/target, controller/peripheral, device/environment, and monitor roles;
- legal inversion, monitor views, exact role-compatible connection, symbolic interface arrays, and an external reusable interface;
- flattened and future-native layout policy candidates;
- top-level and black-box push-pull inout, open-drain drive/release, split carriers, pass-through, pad adapters, and profile-marked internal resolution;
- conservative connect/sense/contribute/monitor access;
- directional analog signal flow and explicit mixed-signal bridges;
- plain, `Valid`, and `Stream` pipeline transforms;
- automatic, exact, and ranged latency; ready-path and throughput policy; parameter envelopes; sideband transport; reconvergence; `stage`; `sameStage`; `delay`; inspection; and fixed/variable-latency operators.

`examples.interfacePipelineExternal` proves independent reusable interface and pipeline source using only `import nodal.*` and the public API module.

## Scala type-negative coverage

Independent fixtures prove that Scala rejects:

- storing an `InterfacePort` as register data (`NODAL-IFACE-014`);
- connecting two non-complementary master roles (`NODAL-ROLE-014`);
- automatically inverting a monitor role (`NODAL-INVERT-014`);
- driving through a monitor role (`NODAL-MONITOR-014`);
- arbitrary push-pull-style driving of an open-drain endpoint (`NODAL-INOUT-014`);
- contributing through a sense-only conservative view (`NODAL-AMS-014`);
- exact connection between different protocol/interface identities (`NODAL-PROTOCOL-014`).

The manifest separately records construction/IR semantic negatives that Scala types alone cannot prove, including role completeness, missing members, multiple ordinary drivers, unsupported internal resolution, hierarchy binding, discipline/topology legality, flattening collisions, parameter-envelope layout conflicts, latency impossibility, unrelated live capture, and schedule constraint conflicts.

## Semantic non-regression

The candidate surface changes no existing contract:

- numeric operations and explicit narrowing remain Increment 13 semantics;
- `Struct` payload layout does not change signedness, shape, enum, or physical-quantity rules;
- `Valid` and `Stream` keep their distinct bubble/backpressure contracts;
- pipeline regions capture one lexical clock domain and cannot hide CDC/RDC;
- memory and external operations retain explicit latency, ordering, effect, throughput, domain, and model availability;
- native parameterized modules remain one symbolic module and automatic scheduling requires a finite envelope;
- conservative topology and explicit bridges remain optimization barriers;
- interface layout policy cannot redefine the logical Interface ABI;
- no source candidate activates a frontend, scheduler, backend, simulator, synthesis, or formal implementation.

## Deferred decisions

Increment 14 deliberately does not freeze:

- case-class derivation versus descriptor construction for `Struct`/`Interface`;
- exact role declaration syntax, built-in aliases, or adapter vocabulary;
- final terminal, signal-flow, resolved-net, pad, layout, bridge, policy, envelope, schedule-report, or operator spellings;
- stable v0.3 diagnostic numbering beyond fixture anchors;
- Interface IR, scheduler IR, CIRCT mappings, HDL lowering, ABI manifest schema, or simulator behavior;
- backend support for internal tri-state or native SystemVerilog interfaces;
- timing-model selection or any timing-closure claim.

Increment 15 freezes one unified v0.3 surface and migration contract after reviewing Increment 13 and Increment 14 together.

## Exit criteria

Increment 14 is complete only when:

1. the public candidate API and both positive modules compile on the pinned Scala 3 toolchain;
2. every Scala type-negative fixture fails independently and positive compilation recovers afterward;
3. the external module imports no internal, frontend, compiler, or scheduler package;
4. the checker confirms ADR 0008/0021 and both candidate surface links;
5. the candidate manifest records all deferred semantic negatives and inert behavior boundaries;
6. formatting, repository Core CI, and the dedicated Increment 14 workflow are green;
7. no construction, scheduler, resolution, topology, frontend, backend, or simulator implementation is introduced;
8. the roadmap marks Increment 14 complete and leaves Increment 15 unchecked as the freeze owner.
