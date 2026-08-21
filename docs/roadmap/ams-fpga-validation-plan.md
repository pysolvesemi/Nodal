# AMS-to-FPGA Approximation and Validation Plan

**Status:** Normative post-preview roadmap target

**Architecture:** [ADR 0011](../architecture/0011-ams-fpga-approximation-validation.md)

**Formal capability gate:** Increment 100

**Machine-readable candidate:** [`ams-fpga-validation-surface.json`](ams-fpga-validation-surface.json)

## Goal

Allow one Nodal model to produce both:

- authoritative Verilog-A or Verilog-AMS for continuous-time reference simulation; and
- an explicitly discretized, finite-precision, synthesizable digital approximation for accelerated FPGA validation.

The FPGA artifact validates the declared hardware approximation. It is not direct synthesis of general Verilog-AMS and is not evidence of unmodeled physical analog behavior.

The binding rule is:

> **Reference AMS semantics, explicit approximation contract, bounded evidence, synthesizable realization.**

The compiler-owned approximation semantics are not plugins. FPGA targets, place/route, bitstream, programmer, board, and HIL integrations use the explicit tool-adapter plugin protocol from [ADR 0012](../architecture/0012-versioned-capability-plugin-architecture.md) and [`plugin-spi-v0.1-plan.md`](plugin-spi-v0.1-plan.md).

## Architectural boundary

An AMS-to-FPGA transformation is never inferred from `Backend.Auto` and is never hidden behind `Backend.Verilog`.

The user explicitly requests an approximation transformation. After the transformation succeeds, the resulting digital design may be emitted through the portable Verilog backend and verified with the existing Verilator, Icarus, Yosys, and SBY plans.

Candidate source direction:

```scala
val approximation = FpgaApproximation(
  domain = fpga,
  samplePeriod = 10.ns,
  solver = Solver.Trapezoidal,
  numeric = FixedPointPolicy.Auto(
    error = ErrorBudget(
      absolute = 1.mV,
      relative = 0.1.percent
    ),
    rounding = Rounding.NearestEven,
    overflow = Overflow.Saturate
  ),
  envelope = ValidationEnvelope(...),
  target = FpgaTarget.Open("ecp5")
)

val hardwareModel = Nodal.approximate(new ControlledPlant, approximation)

val emission = Nodal.emit(
  hardwareModel,
  EmitOptions(backend = Backend.Verilog)
)
```

The exact names and constructor shapes are candidates. Increment 100 freezes the public capability profile and API after compile-positive and compile-negative evaluation.

## Required contract categories

### Reference semantics

Every approximation identifies the reference Nodal model, backend/profile, parameters, analyses, simulator/tool versions, and source hash used for differential validation.

The Verilog-A/Verilog-AMS or high-precision Nodal numerical result remains authoritative for the scenarios in the validation envelope.

### Time contract

The approximation declares:

- base sample period;
- FPGA clock frequency;
- cycles available per sample;
- single-rate or multi-rate partitions;
- rational rate relationships;
- input sample-and-hold behavior;
- output update and reconstruction behavior;
- event detection/interpolation policy;
- deadline-miss policy.

The compiler rejects an implementation whose worst-case schedule cannot complete before the next sample deadline.

### Solver contract

Candidate solver families include:

```scala
Solver.ForwardEuler
Solver.BackwardEuler(iterations = n)
Solver.Trapezoidal
Solver.Tustin
Solver.ExactZoh
Solver.Custom(...)
```

Each enabled solver declares:

- supported equation/model class;
- recurrence generation;
- coefficient calculation;
- initialization;
- stability and conditioning checks;
- fixed iteration/convergence rules where applicable;
- implementation latency and resource model;
- failure/overflow behavior.

No default solver is silently selected for a model class when the numerical consequences are material.

### Supported model classes

Initial positive candidates should include:

- linear state-space models;
- supported Laplace/transfer-function models;
- explicit ordinary differential equations with identifiable state;
- bounded static nonlinearities;
- sampled comparators, thresholds, ADCs, and DACs;
- digital control and protocol logic around the sampled plant;
- finite parameter envelopes;
- supported sampled events.

Initial negative candidates should include:

- unresolved DAEs or algebraic loops;
- hidden state;
- adaptive or variable-step behavior;
- unsupported stiff systems;
- transistor/device physics and process models;
- unsupported noise and random processes;
- ideal impulses/discontinuities;
- event precision smaller than the declared sampling policy;
- simulator-specific analyses without an FPGA meaning.

### Numeric contract

Candidate policies include:

```scala
FixedPointPolicy.Explicit(...)
FixedPointPolicy.Auto(error = ..., resources = ...)
Rounding.NearestEven
Rounding.TowardZero
Overflow.Saturate
Overflow.Trap
Overflow.Wrap
```

Every state, coefficient, input, output, and intermediate operation carries:

- physical dimension;
- scale and binary-point placement;
- proven or asserted range;
- stored width and guard bits;
- rounding point;
- overflow policy;
- accumulated error contribution.

Automatic fixed-point selection succeeds only when it can satisfy the declared error/resource envelope and emit a deterministic report. Otherwise explicit formats or tighter assumptions are required.

### Multi-rate contract

Multi-rate partitions require explicit rate relationships and adapters:

```scala
Hold.zeroOrder(...)
Interpolate.linear(...)
Decimate(...)
RateBridge(...)
```

Exact names are candidates. The scheduler must account for state ordering, buffers, timestamp alignment, anti-alias assumptions, CDC/RDC, and real-time deadlines.

### Event contract

Analog events become one of:

- sampled threshold detection;
- interpolated crossing-time estimate;
- explicit bounded-latency event output;
- unsupported diagnostic.

The report states event-time resolution, interpolation method, worst-case detection latency, hysteresis/debounce behavior, and missed-event assumptions.

### Parameterized implementation

The preferred result is one native parameterized digital module valid for a finite parameter envelope.

A timing-, state-dimension-, memory-, or fixed-point-affecting parameter requires one schedule and numeric/resource plan valid for every legal value. Silent clone-per-value generation is prohibited.

Explicit concrete specialization may be requested when required by the target FPGA, but the manifest records the specialization and does not present it as the default parameterized result.

## Validation ladder

### Level A — AMS reference to high-precision discrete reference

Purpose: measure discretization and model-reduction error.

Evidence:

- matched stimuli and parameters;
- time-alignment policy;
- waveform/state/event/frequency metrics;
- stability and invariant checks;
- tolerance results and failure regions.

### Level B — High-precision discrete to fixed-point reference

Purpose: isolate coefficient quantization, state quantization, rounding, saturation, overflow, and range error.

Evidence:

- bit-accurate software recurrence;
- per-state and per-operation formats;
- overflow/range coverage;
- accumulated error budget;
- worst-case and randomized tests.

### Level C — Fixed-point reference to generated RTL

Purpose: verify the digital implementation contract.

Evidence:

- Verilator and Icarus simulation;
- protocol, reset, latency, and multi-rate checks;
- Yosys synthesis/equivalence where applicable;
- SBY safety/cover/selected liveness properties;
- deterministic waveforms, traces, and counterexamples.

### Level D — RTL/netlist to placed FPGA hardware

Purpose: validate synthesis, place/route, bitstream, board runtime, and host transport.

Evidence:

- synthesis and resource reports;
- place/route timing and utilization;
- clock and sample-deadline checks;
- bitstream hash and target/board manifest;
- hardware trace comparison;
- runtime errors, dropped samples, and deadline misses.

Every report preserves the previous levels. A passing Level D run cannot hide a failing Level A or B comparison.

## Error and acceptance metrics

Supported metrics should include:

- absolute and relative waveform error;
- maximum, RMS, percentile, and integrated error;
- state/invariant violation;
- gain and phase error;
- settling time, overshoot, rise/fall time, and steady-state error;
- threshold/event-time error;
- frequency-response error;
- overflow/saturation count and range coverage;
- latency, jitter, and missed-deadline count;
- control-loop stability metrics where a supported analysis exists.

The validation envelope lists legal parameters, initial conditions, stimuli, operating duration, input bandwidth, tolerances, and unsupported regions. Passing results apply only to that envelope.

## FPGA target and tool profiles

The first required open target should use a device family supported by a complete open flow.

The target profile records:

- device/family/package and board;
- FPGA clock and sample-rate requirements;
- Yosys synthesis command/profile;
- nextpnr architecture, constraints, and seed;
- bitstream packer/programmer;
- LUT, flip-flop, memory, DSP, and routing use;
- achieved timing and slack;
- board I/O and transport resources.

The architecture does not mandate a particular first board in this planning increment. Increment 107 selects and pins at least one fully open reference target. Vendor adapters are optional and separately versioned.

## Hardware-in-the-loop

The HIL layer provides:

- deterministic start/stop/reset;
- timestamped sampled inputs and outputs;
- parameter/configuration loading;
- stimulus streaming and trace capture;
- overflow/deadline/error status;
- host transport adapters;
- reproducible test packages;
- optional external ADC/DAC calibration and latency models.

Transport and board support do not change the approximation mathematics. They are implementation profiles with independent evidence.

## Initial vertical slices

### RC/RLC plant

Proves explicit ODE/state normalization, solver selection, fixed-point state, waveform comparison, open FPGA synthesis, and HIL trace capture.

### Digitally controlled first- or second-order plant

Proves digital control around sampled analog state, reset/calibration, parameter updates, saturation, and long-duration closed-loop scenarios.

### Comparator/ADC/DAC loop

Proves sampled thresholds, event latency, quantization, `Valid`/`Stream` transport, automatic pipelines, and board I/O abstraction.

### PLL/control-loop approximation

Proves multi-rate behavior, phase/event metrics, generated clocks or timebase modeling, and explicit limitations for jitter/noise not represented.

## Non-goals of the initial profile

- direct synthesis of arbitrary Verilog-AMS;
- transistor-level emulation;
- automatic physical parasitic or PVT extraction;
- adaptive solvers;
- arbitrary DAE solving;
- guaranteed equivalence outside the validation envelope;
- hidden replacement of unsupported behavior;
- automatic `Backend.Auto` selection of FPGA approximation;
- claims that FPGA hardware reproduces an unmodeled physical analog implementation.

## Incremental delivery plan

### Increment 100 — AMS-to-FPGA approximation capability gate and API contracts

- [ ] Accept the exact capability boundary from ADR 0011.
- [ ] Compile candidate approximation, solver, numeric, envelope, target, and validation APIs.
- [ ] Publish `NodalAmsFpgaApproximation-DG-v0.4.md`, migration/compatibility policy, diagnostics, and a machine-readable frozen surface.
- [ ] Prove that `Backend.Auto` never selects approximation.
- [ ] Add external-library and unsupported-construct fixtures.

### Increment 101 — Analog normalization and sampled-state IR

- [ ] Normalize supported state-space, transfer-function, and explicit-ODE models into target-neutral state/update IR.
- [ ] Identify state, inputs, outputs, algebraic dependencies, events, units, parameters, and unsupported loops/hidden state.
- [ ] Preserve source mapping and authoritative AMS-reference links.

### Increment 102 — Solver and discrete-time recurrence generation

- [ ] Implement approved explicit/implicit/linear discretization methods.
- [ ] Generate coefficients and recurrence IR deterministically.
- [ ] Verify stability/conditioning, iteration/convergence, initialization, reset, and failure policy.
- [ ] Produce high-precision software reference models and golden recurrence evidence.

### Increment 103 — Range, fixed-point, quantization, and error-budget analysis

- [ ] Add range assertions/inference, physical scaling, explicit and automatic fixed-point formats, rounding, overflow, guard bits, and coefficient quantization.
- [ ] Generate bit-accurate reference models and Level B differential evidence.
- [ ] Reject unsatisfied error/resource policies and unbounded states.

### Increment 104 — Multi-rate, sampled-event, and real-time scheduling

- [ ] Implement rational multi-rate partitions, holds/interpolation/decimation, event detection/interpolation, buffers, and timestamp alignment.
- [ ] Integrate `ClockDomain`, CDC/RDC, `Valid`/`Stream`, memories, and automatic pipelines.
- [ ] Prove per-sample deadlines and report infeasible schedules.

### Increment 105 — Synthesizable FPGA approximation backend

- [ ] Lower the discrete fixed-point model into ordinary Nodal digital IR.
- [ ] Reuse `Backend.Verilog`, symbolic parameters, hierarchy, memories, clock/reset, automatic pipelines, and deterministic source maps.
- [ ] Emit approximation, numeric, schedule, rate, and limitation manifests plus golden portable Verilog.

### Increment 106 — Differential, equivalence, and formal validation ladder

- [ ] Implement Level A and B waveform/state/event/frequency comparison with declared envelopes and tolerances.
- [ ] Implement Level C Verilator/Icarus regression, Yosys equivalence, and SBY properties for recurrence, reset, rate control, protocols, overflow, and deadlines.
- [ ] Preserve failures and counterexamples by error class.

### Increment 107 — Open-source FPGA implementation and target evidence

- [ ] Pin Yosys, nextpnr, a fully open device/board flow, constraints, packer, and programmer.
- [ ] Run synthesis, placement, routing, timing, bitstream generation, utilization, and deterministic seed/manifest checks.
- [ ] Add optional vendor-tool adapters without making them normative.
- [ ] Require real-time sample feasibility after place and route.

### Increment 108 — Hardware-in-the-loop runtime, vertical slices, and capability matrix

- [ ] Add board/host runtime, sampled streaming, configuration, trace capture, status, reproducible test bundles, and optional external ADC/DAC profiles.
- [ ] Complete RC/RLC, controlled-plant, comparator/ADC/DAC, and PLL/control-loop vertical slices.
- [ ] Publish exact supported/unsupported constructs, approximation limits, error envelopes, resource/timing results, and claims language.
- [ ] Add M5 FPGA-accelerated AMS validation release evidence.

## Gate exit criteria

The FPGA validation capability is not complete until:

1. the approximation is always explicit and never selected by `Backend.Auto`;
2. supported constructs and rejected AMS behavior are machine readable;
3. sample, solver, numeric, range, event, multi-rate, and reset contracts are explicit;
4. the four validation levels produce separate evidence;
5. the reference envelope and tolerance metrics are reproducible;
6. generated RTL matches the bit-accurate recurrence;
7. at least one open FPGA target completes synthesis, place/route, timing, bitstream, and hardware trace validation;
8. deadline, overflow, and hardware transport failures are visible;
9. reports clearly state that the result validates an approximation rather than physical analog behavior;
10. CI and retained artifacts pass for all required vertical slices.

## References

- nextpnr: <https://github.com/YosysHQ/nextpnr>
- Yosys: <https://yosyshq.readthedocs.io/projects/yosys/en/stable/>
- Verilator: <https://verilator.org/guide/latest/>
- SBY: <https://yosyshq.readthedocs.io/projects/sby/en/stable/>
