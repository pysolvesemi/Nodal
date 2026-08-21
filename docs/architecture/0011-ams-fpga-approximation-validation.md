# ADR 0011: Validate AMS behavior on FPGA through explicit approximation

- **Status:** Accepted
- **Date:** 2026-08-21
- **Scope:** AMS approximation semantics, discrete-time lowering, fixed-point implementation, FPGA realization, differential validation, and hardware-in-the-loop evidence

## Context

General Verilog-A and Verilog-AMS describe continuous-time electrical behavior, implicit equations, analog events, and simulator analyses that an FPGA fabric cannot execute directly. An FPGA remains useful as a high-speed validation platform when supported analog behavior is transformed into an explicitly sampled, discrete-time, finite-precision digital model.

That transformation is not ordinary HDL emission. It changes time representation, numerical method, precision, overflow behavior, and often the supported physical effects. Treating it as a silent backend choice would create false confidence and make it impossible to distinguish analog-model error from discretization, quantization, RTL, implementation, or hardware-I/O error.

Nodal already plans:

- an authoritative Verilog-A/Verilog-AMS path for analog and mixed-signal semantics;
- a portable Verilog path for digital-only designs;
- explicit clock/reset domains, fixed-width numeric policy, automatic pipelines, open-source simulation, synthesis, equivalence, and formal verification.

An FPGA approximation path should reuse those foundations while preserving a separate, reviewable approximation contract.

## Decision

Nodal supports AMS-on-FPGA validation only through an explicit approximation transformation.

The architectural rule is:

> **Reference AMS semantics, explicit approximation contract, bounded evidence, synthesizable realization.**

The original Nodal model and its Verilog-A/Verilog-AMS behavior remain the reference semantics. The FPGA artifact is a generated digital approximation with its own versioned contract, capability profile, and evidence.

`Backend.Auto` must never turn an analog-only or mixed-signal model into an FPGA approximation. An author must opt in to the approximation, select or accept an explicit numerical contract, and receive diagnostics for every unsupported construct. After approximation, the resulting digital design may reuse the portable Verilog backend and digital verification stack.

## Approximation contract

The future public API must make these decisions explicit or derive them through a separately approved analysis with reportable evidence:

- destination `ClockDomain` and FPGA clock frequency;
- sample period and update rate;
- numerical solver/discretization method;
- state initialization and reset behavior;
- fixed-point formats, scaling, rounding, and overflow policy;
- legal parameter and signal ranges;
- single-rate or multi-rate scheduling and rate relationships;
- input/output sample-and-hold, interpolation, decimation, and event policies;
- validation envelope, stimuli, metrics, and error budgets;
- target FPGA profile and implementation constraints.

Candidate public spellings are deferred to a dedicated design gate. The architecture does not require users to maintain a separate handwritten FPGA model.

## Initial semantic subset

The first FPGA approximation profile is intentionally narrower than Verilog-AMS. It should begin with constructs that can be normalized into deterministic sampled recurrences, including:

- explicit state-space and transfer-function models;
- supported causal ordinary differential equations with identifiable state;
- algebraic expressions with bounded, dimensionally valid nonlinear functions;
- sampled comparators, thresholds, ADC/DAC boundaries, and digital control;
- supported analog events converted into declared sampled or interpolated event policies;
- parameterized coefficients and finite range envelopes;
- digital hierarchy, clocks/resets, protocols, memories, and automatic pipelines around the approximation.

The initial profile rejects or requires a manually supplied approximation contract for:

- arbitrary differential-algebraic equation systems and unresolved algebraic loops;
- hidden state that cannot be normalized deterministically;
- stiff systems without a supported stable method and feasibility proof;
- variable-step or adaptive-time behavior;
- transistor/device physics, parasitics, mismatch, PVT, and process models;
- analog noise operators unless a separately approved stochastic hardware model exists;
- ideal discontinuities, impulses, or events whose required timing precision is below the declared sample contract;
- simulator-specific analyses or system functions without an approximation meaning.

Unsupported behavior is a source-located error. Nodal must not silently delete or simplify it.

## Discrete-time and solver semantics

Every generated hardware model uses a deterministic update schedule. The sample period is part of the model contract, not merely a simulation option.

The initial solver study should compare methods such as forward Euler, backward Euler, trapezoidal/Tustin, and exact zero-order-hold discretization for supported linear state-space forms. A method is enabled only when the compiler can define:

- the recurrence and coefficient generation;
- stability and conditioning checks appropriate to the supported model class;
- initialization and reset semantics;
- implementation cost and latency;
- reproducibility across software reference, generated RTL, and FPGA hardware.

Implicit methods that require solving equations must expose the selected hardware solver, iteration bound, convergence behavior, failure policy, and real-time cost. A method that cannot complete within the sample interval is infeasible rather than silently slowed.

## Multi-rate and event behavior

A single FPGA model may contain partitions with different rationally related sample periods. Rate changes require explicit hold, interpolation, decimation, buffering, and anti-alias assumptions where applicable.

The scheduler must preserve:

- state update ordering;
- transaction identity and latency;
- clock/reset-domain provenance;
- deterministic event ordering at equal timestamps;
- declared cross-rate and CDC/RDC structures;
- the real-time requirement that all work for a sample completes before its next deadline.

Arbitrary unrelated sample clocks are treated as explicit clock-domain crossings.

## Fixed-point and range policy

Floating-point or real-valued discrete recurrences are reference models, not automatically synthesizable implementations. The FPGA profile lowers them through an explicit finite-precision policy.

The compiler must track:

- physical dimensions and scale factors;
- proven or asserted value ranges;
- coefficient and state formats;
- rounding after each operation;
- overflow behavior such as saturate, trap, or deliberate wrap;
- guard bits and intermediate widths;
- quantization and accumulated error bounds;
- resource and timing impact.

Automatic format selection is permitted only when it produces a reportable solution satisfying an approved error/resource policy across the declared validation envelope. Otherwise the user supplies formats or constraints.

## Four-level validation ladder

Nodal separates four different questions.

### 1. Continuous-reference versus discrete-reference validation

Compare the authoritative analog/AMS result with a floating or high-precision discrete-time reference over a declared stimulus and parameter envelope. This measures sample/solver/model-reduction error.

### 2. Discrete-reference versus fixed-point-reference validation

Compare the high-precision recurrence with a bit-accurate finite-precision software model. This isolates quantization, rounding, saturation, coefficient, and range error.

### 3. Fixed-point-reference versus generated RTL validation

Use Verilator/Icarus simulation, Yosys equivalence where applicable, and SBY properties to prove or test that generated RTL implements the bit-accurate recurrence, protocol, reset, latency, and rate-control contract.

### 4. RTL/netlist versus FPGA hardware validation

Compare hardware traces against RTL/netlist expectations while retaining board clock, reset, I/O, transport, timing, and bitstream evidence. This validates the implemented digital approximation and runtime infrastructure.

Passing a later level does not erase an earlier approximation error. Reports must preserve the full ladder.

## Error metrics and evidence

Validation envelopes may use metrics appropriate to the model, including:

- absolute and relative waveform error;
- state error and invariant violation;
- gain and phase error;
- settling time, rise/fall time, overshoot, and steady-state error;
- event-time and threshold-crossing error;
- frequency-response error;
- saturation, overflow, and range coverage;
- control-loop stability margins where a supported analysis exists.

Every result identifies the model hash, parameter set, solver, sample periods, numeric formats, tool versions, target profile, seeds, stimuli, and tolerance policy.

## FPGA implementation flow

The generated approximation is a digital design and reuses Nodal's portable Verilog, automatic-pipeline, clock/reset, CDC/RDC, simulation, synthesis, equivalence, and formal infrastructure.

The open-source implementation path should use:

- Yosys for synthesis and structural analysis;
- nextpnr for timing-driven place and route on at least one fully open supported FPGA family;
- the corresponding open bitstream packer/programmer for the selected board;
- Verilator/Icarus and SBY before hardware execution.

Vendor FPGA tools may be supported through optional adapters for devices not covered adequately by the open flow. Vendor-specific results must remain separate from language semantics and portable evidence.

The FPGA report includes resource use, achieved clock frequency, sample-rate feasibility, pipeline latency, memory use, DSP use where applicable, placement/routing seed, constraints, and bitstream hash.

## Hardware-in-the-loop

HIL support provides deterministic sampled input/output streams, timestamps, configuration, reset, start/stop, capture, and host transport. Board adapters may use UART, USB, Ethernet, PCIe, or memory-mapped interfaces, but transport choice is outside the mathematical approximation semantics.

External ADC/DAC devices may connect a hardware model to real signals. Their transfer functions, latency, noise, calibration, and bandwidth are explicit board/I/O models. They do not make the FPGA fabric itself analog.

## Parameterized models

Symbolic parameters remain native HDL parameters where the generated structure can support one implementation across the declared envelope. Timing-affecting or state-dimension parameters require a finite envelope and one schedule, numeric policy, memory shape, and resource plan valid for every legal value.

Nodal must not silently generate one FPGA module per parameter value. Concrete specialization is explicit and reported when the target device cannot support a single parameterized implementation.

## Claims and limitations

An FPGA run may validate:

- digital control and sequencing around an analog model;
- register programming and calibration algorithms;
- mixed-signal state machines and protocols;
- approximate closed-loop behavior inside the declared envelope;
- high-speed corner-case and long-duration functional scenarios;
- generated fixed-point recurrence, latency, and reset behavior.

It does not by itself validate:

- transistor or physical device behavior;
- parasitics, process variation, mismatch, or temperature effects;
- continuous-time behavior between samples;
- noise or jitter not included in the approximation;
- dynamics outside the declared model, parameter, and stimulus envelope;
- physical analog stability beyond the evidence produced by the reference comparison.

Reports and documentation must use **approximation**, **emulation**, or **hardware validation model** terminology, not claim direct FPGA synthesis of general Verilog-AMS.

## Plugin adapter boundary

The approximation transformation, sampled-state IR, solver semantics, numeric/error analysis, and validation ladder remain core compiler semantics. FPGA target databases, place/route flows, bitstream packers, programmers, board runtimes, external ADC/DAC profiles, and HIL transports use the versioned tool-adapter plugin protocol from [ADR 0012](0012-versioned-capability-plugin-architecture.md).

A plugin target cannot weaken the declared approximation envelope, error budget, deadline proof, or claims language.

## Consequences

### Positive

- One Nodal source can produce an analog-accurate simulation model and a reviewable hardware approximation without separately maintained RTL.
- Digital control and long-duration mixed-signal scenarios can run much faster than conventional AMS simulation.
- Approximation, quantization, RTL, implementation, and hardware-I/O errors are isolated rather than conflated.
- Existing portable Verilog and open-source digital verification infrastructure is reused.
- Automatic pipelines can help satisfy real-time sample deadlines while preserving explicit latency and numeric semantics.

### Costs

- Supported analog behavior must be normalized into explicit state and sampled recurrence forms.
- Numerical stability, range, precision, and error analysis become compiler responsibilities.
- General Verilog-AMS remains far broader than the FPGA approximation profile.
- Multi-rate scheduling, fixed-point optimization, and HIL infrastructure require substantial validation.
- Board and vendor tool support must be versioned separately from language semantics.

## Rejected alternatives

- **Treat Verilog-AMS as directly synthesizable to FPGA:** physically and semantically incorrect for general analog behavior.
- **Let `Backend.Auto` choose an FPGA approximation:** hides a semantics-changing transformation.
- **Maintain a separate handwritten FPGA model:** creates long-term divergence from the reference model.
- **Use floating-point simulation code as the FPGA contract:** does not define finite-precision hardware behavior.
- **Validate only RTL against FPGA hardware:** misses discretization and quantization error relative to the AMS reference.
- **Silently replace unsupported analog constructs:** creates unreviewable false equivalence.
- **Claim physical analog validation from a passing FPGA run:** exceeds the evidence.

## Follow-up increments

- Increment 100 freezes the approximation capability profile, candidate API, diagnostics, and validation contract.
- Increments 101-104 implement analog normalization, solver/discrete-time IR, fixed-point/range analysis, and multi-rate scheduling.
- Increment 105 emits synthesizable portable Verilog for the approximation and integrates automatic pipelines.
- Increment 106 implements the complete differential, equivalence, and formal validation ladder.
- Increment 107 adds open-source FPGA synthesis, place/route, bitstream, and target evidence.
- Increment 108 adds HIL runtime support, representative vertical slices, and the capability/limitations matrix.

## References reviewed

- nextpnr portable FPGA place-and-route project: <https://github.com/YosysHQ/nextpnr>
- Yosys documentation: <https://yosyshq.readthedocs.io/projects/yosys/en/stable/>
- Verilator guide: <https://verilator.org/guide/latest/>
- SBY documentation: <https://yosyshq.readthedocs.io/projects/sby/en/stable/>
