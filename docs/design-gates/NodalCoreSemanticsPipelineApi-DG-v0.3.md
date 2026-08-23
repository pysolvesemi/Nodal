# NodalCoreSemanticsPipelineApi-DG v0.3

**Status:** Approved  
**Scope:** public-api  
**API version:** 0.3  
**Decision:** Freeze unified core semantics, Interface/Role/inout, automatic pipeline, and backend selection  
**Approved by:** Repository owner instruction to implement Increment 15 on 2026-08-23

## Decision

Nodal public API v0.3 is frozen by this gate. The authoritative inventories are:

- [`public-api-v0.3.json`](../../core/scala/api/public-api-v0.3.json);
- [`public-api-diagnostics-v0.3.json`](../../core/scala/api/public-api-diagnostics-v0.3.json);
- [`tests/api/fixtures/increment15/manifest.json`](../../tests/api/fixtures/increment15/manifest.json);
- [`public-api-v0.3.md`](../language-reference/public-api-v0.3.md);
- the v0.1-to-v0.3 and v0.2-to-v0.3 migration notes.

The binding rule is:

> **Values are explicit and lossless, connectivity is role-typed, state belongs to a domain, pipeline intent is transactional, and target selection never changes source semantics.**

This gate freezes source forms, type distinctions, diagnostics, compatibility rules, reporting shapes,
and semantic obligations. It does not claim that elaboration, scheduling, Interface IR, resolution,
topology, MLIR lowering, HDL generation, simulation, synthesis, formal execution, or timing closure is
implemented.

The Increment 13 and Increment 14 candidate gates remain historical evidence. Where candidates differ,
this gate and `public-api-v0.3.json` are authoritative.

## Value stages, numbers, shapes, and storage

Ordinary Scala values and Scala `for` loops are elaboration-only. Target-visible symbolic replication is
`generate(count)`. Bounded same-cycle hardware iteration is `loop(LoopBound.Static(...))` or
`loop(LoopBound.Symbolic(..., maximum = ...))`. Runtime-unbounded hardware loops are rejected.

The finite-width value types are `Bool`, `Bits`, `UInt`, and `SInt`. Arithmetic is lossless by default.
There is no implicit narrowing or signedness conversion. The frozen explicit operations are `extend`,
`truncate`, `wrap`, `saturate`, `resizeChecked`, `toSigned`, `toUnsigned`, `reinterpretSigned`, and
`reinterpretUnsigned`.

`Vec` is structural shaped data with static or symbolic dimensions and supports `at`, `flatten`,
`reshape`, `map`, `zip`, and `reduce`. `Mem` is explicit addressable storage with depth, latency,
read-under-write, ordering, domain, and mask contracts. A `Vec` must not silently become a memory.

`Quantity[D]` carries a physical dimension. Initial source-visible dimensions are voltage, current,
resistance, and time. Incompatible addition is a type error. Future dimensions may be additive only when
they do not weaken existing dimensional checks.

## Struct, Interface, Role, and protocols

`Struct` is the sole frozen directionless value aggregate. It is storable and may be a `Valid` or
`Stream` payload. The experimental `Aggregate`/`AggregateField` spelling is rejected and removed from
the supported source surface.

`Interface` is non-storable connectivity. `Role[R]` selects named per-member access. The built-in role
identities are `master`, `slave`, `source`, `sink`, `initiator`, `target`, `controller`, `peripheral`,
`device`, `environment`, and `monitor`. Automatic inversion exists only for fully complementary digital
roles and preserves the interface-specific member-access map. Monitor access is read-only.

Direct connection is `connectExact` and requires one Interface identity plus complementary role
evidence. v0.3 deliberately does not freeze generic `via` or `viewAs` helpers. Protocol conversion,
field adaptation, resizing, buffering, latency changes, and domain conversion use an explicit
user-authored `Module` boundary. This keeps adaptation visible in hierarchy and source maps.

`Valid[T]` is the sole valid-only protocol. `Stream[T]` is ordered elastic ready/valid transport.
`Txn[T]` is a fixed-rate transaction wrapper for automatic pipeline regions. No implicit conversion
exists among plain, `Valid`, `Stream`, or Interface identities.

## Clock, reset, state, and crossings

The v0.2 clock/reset surface remains frozen unchanged: `Clock` and `Reset` are distinct from `Bool`;
`ClockDomain.external`, `from`, `required`, and `generated` define domain ownership; `Reg` and `RegNext`
define state; and `when`/`elsewhen`/`otherwise` define priority updates. Ordinary synchronous
`always(event)` remains outside the public subset.

CDC, RDC, reset combination, clock gates, and glitchless muxes remain explicit through `Cdc`, `Rdc`,
`ResetController`, `ClockGate`, and `ClockMux`. Automatic pipeline regions capture one lexical domain and
cannot hide a crossing.

## Automatic pipeline

`pipe` schedules an explicit transaction transform; `delay` expresses structural latency. Frozen policy
includes `Latency.Auto`, `Latency.Exact`, `Latency.Range`, `Throughput.EveryCycle`, and
`ReadyPath.Auto`/`Combinational`/`Registered`. `stage` is a hard boundary and `sameStage` is a local
co-location constraint. `ParameterEnvelope` requires one schedule valid for the complete legal
parameter range. `inspectSchedule` exposes reviewable evidence.

Every dynamic value used by a transform must enter through its transaction. Constants and parameters
remain static. Fixed-rate published interfaces require exact or bounded latency. Variable-latency
operators accept elastic `Stream` input. The scheduler may balance sidebands and reconvergence but may
not reassociate arithmetic, share resources silently, move effects, cross domains, specialize one module
per parameter value, or perform general HLS.

## Memory, external operations, enums, FSMs, naming, and checks

`ExternalOp`, `FixedLatencyOperator`, and `VariableLatencyOperator` require explicit latency,
initiation interval, effect, model availability, and domain contracts. Unknown effects or models are
barriers and errors where required.

Native Scala enums derive `HwEnum`; `enumEncoding` freezes canonical ABI values and `decodeEnum` is the
safe decode form. `FsmDefinition` and `fsm` cover flat, nested, parallel, timed, and bounded-call-stack
control. Canonical enum ABI is separate from local FSM storage encoding. Unbounded recursion is rejected.

`TemporaryPolicy`, `NamingPolicy`, `CheckProfile`, `CheckWaiver`, and `EmitQuality` freeze source-visible
quality policy. Mandatory safety checks cannot be disabled. Names, materialization reasons, source spans,
logical Interface ABI entries, and schedule evidence appear through `DesignReport` rather than backend
comments or traversal-counter names.

## Digital inout and AMS connectivity

`DigitalInout[A, M]` has a separate `read` view and drive state. Push-pull and open-source endpoints use
explicit value plus enable; open-drain uses `driveLow`; release is `highZ`. `split` exposes the portable
read/write/enable carrier. `passThrough` and `padAdapter` are explicit hierarchy and technology
boundaries. Multiple drivers exist only on declared resolved nets, and unsupported internal resolution
is an error rather than a silent mux rewrite.

Conservative `Terminal` connectivity is distinct from digital inout and directional `AnalogSignal` flow.
Terminal access is explicitly connect, sense, contribute, or monitor. `MixedSignalBridge` and
`ConservativeSignalBridge` require sample/update timing, thresholds, hysteresis, quantization, model
availability, and provenance. There is no implicit digital/analog or conservative/signal-flow conversion.

## Backends, layouts, and reports

The frozen backends are `Backend.Auto`, `Backend.Verilog`, `Backend.VerilogA`, and
`Backend.VerilogAMS`. `EmitOptions()` now defaults to `Backend.Auto`. Automatic selection is:

- digital-only -> portable `Backend.Verilog`;
- analog-only -> `Backend.VerilogA`;
- mixed-signal -> `Backend.VerilogAMS`.

The frozen design kinds are `DigitalOnly`, `AnalogOnly`, `MixedSignal`, and `Unsupported`. Portable
digital profiles are `Synthesis`, `Simulation`, and `Formal`. `InterfaceLayout.PortableFlattened` is the
supported v0.3 Interface ABI layout. `FutureSystemVerilogNative` remains a comparison value only and must
be rejected by an unsupported backend/profile combination.

`Backend.SystemVerilog` is not public v0.3 API. Native SystemVerilog interfaces/modports require the
separate future gate and must preserve the same logical Interface ABI as flattened output.

`Emission` returns deterministic in-memory files plus `DesignReport`, which carries design kind,
selected backend, digital profile, logical-to-emitted Interface ABI entries, source-map entries, and
schedule inspections. No filesystem write occurs unless the caller performs it.

## Compatibility and migration

v0.1 analog, parameter, hierarchy, and explicit Verilog-A/Verilog-AMS forms remain source compatible.
v0.2 clock/reset/domain/crossing forms remain source compatible. The semantic default for
`EmitOptions()` changes from Verilog-AMS to Auto; code requiring the old behavior must pass
`backend = Backend.VerilogAMS` explicitly.

Source compatibility is required across v0.3.x for every symbol listed in `public-api-v0.3.json`. Binary
compatibility is not promised before 1.0. A breaking source or semantic change requires a new approved
design gate and migration note. An additive overload is allowed only when resolution and semantics stay
unambiguous.

Reusable libraries use `import nodal.*` and may use language, protocol, domain, connectivity, pipeline,
memory, enum, and FSM contracts. Compiler selection/reporting, `Nodal.emit`, `nodal.lowlevel.*`,
`nodal.internal.*`, and bootstrap APIs remain outside the reusable-library subset.

## Rejected alternatives

- `Aggregate` as a second value aggregate beside `Struct`;
- `Flow` as a second valid-only core protocol beside `Valid`;
- implicit protocol, role, width, latency, or domain adaptation;
- direct variable-style assignment to resolved inout without drive state;
- automatic clock creation or silent CDC/RDC insertion;
- clone-per-parameter scheduling or backend specialization;
- raw SystemVerilog interface/modport syntax in the source API;
- raw CIRCT/MLIR graph operations in ordinary Nodal source;
- backend-dependent arithmetic, storage, protocol, or Interface semantics;
- timing estimates represented as timing closure.

## Validation and implementation boundary

The freeze is accepted only with positive native and external-library compilation, independent Scala
type-negative fixtures, semantic-contract fixtures with stable source-located diagnostics, migration
fixtures for v0.1/v0.2, Increment 13 and Increment 14 non-regression, and green Core CI.

All implementation behavior remains inert. Increment 16 is the first construction-kernel increment and
must implement against this gate rather than redesigning the public surface.
