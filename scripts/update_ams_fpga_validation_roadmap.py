#!/usr/bin/env python3
"""Integrate the AMS-to-FPGA approximation roadmap into durable project plans."""

from __future__ import annotations

import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def replace_once(text: str, old: str, new: str, *, subject: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{subject}: expected one anchor, found {count}: {old[:160]!r}")
    return text.replace(old, new, 1)


def update_roadmap() -> None:
    path = ROOT / "docs/roadmap/nodal-development-todo.md"
    text = path.read_text(encoding="utf-8")

    text = replace_once(
        text,
        "**Revision:** 1.4",
        "**Revision:** 1.5",
        subject="roadmap revision",
    )

    fixed_anchor = (
        "- Verify generated pure-digital HDL through a pinned open-source matrix using "
        "Verilator, Icarus Verilog, Yosys, SBY, and optional cocotb interoperability."
    )
    fixed_replacement = fixed_anchor + "\n" + "\n".join(
        (
            "- Treat AMS-to-FPGA validation as an explicit discrete-time, finite-precision approximation transformation. `Backend.Auto` must never select it and no report may present it as direct synthesis of general Verilog-AMS.",
            "- Require sample period, solver, state/reset, fixed-point, range, rounding/overflow, multi-rate/event, validation-envelope, error-budget, and target-FPGA contracts before generating an approximation.",
            "- Preserve separate evidence for AMS-reference error, discretization/model-reduction error, fixed-point error, RTL implementation, FPGA timing/resources, and hardware-in-the-loop runtime.",
            "- Reuse portable Verilog, automatic pipelines, clock/reset domains, CDC/RDC, Verilator/Icarus, Yosys, SBY, and an open Yosys+nextpnr target for FPGA approximation validation; vendor flows remain optional adapters.",
        )
    )
    text = replace_once(
        text,
        fixed_anchor,
        fixed_replacement,
        subject="fixed FPGA approximation direction",
    )

    public_anchor = (
        "- Add `Backend.Auto` and `Backend.Verilog` for pure-digital output while retaining "
        "explicit `Backend.VerilogA` and `Backend.VerilogAMS` profiles."
    )
    public_replacement = public_anchor + "\n" + "\n".join(
        (
            "- Keep AMS approximation separate from backend selection. A future `FpgaApproximation`-class public contract must be explicitly requested, produce a digital approximation artifact, and only then use `Backend.Verilog`.",
            "- Freeze the AMS-to-FPGA capability profile, solver/numeric/envelope contracts, claims language, diagnostics, and validation evidence through a dedicated post-preview design gate before implementation.",
        )
    )
    text = replace_once(
        text,
        public_anchor,
        public_replacement,
        subject="public FPGA approximation direction",
    )

    fpga_section = r'''## AMS-to-FPGA approximation architecture

The binding architecture is [ADR 0011](../architecture/0011-ams-fpga-approximation-validation.md). The complete capability, solver, numeric, validation, FPGA implementation, and HIL plan is in [`ams-fpga-validation-plan.md`](ams-fpga-validation-plan.md), with a machine-readable candidate in [`ams-fpga-validation-surface.json`](ams-fpga-validation-surface.json).

Nodal adopts:

> **Reference AMS semantics, explicit approximation contract, bounded evidence, synthesizable realization.**

An FPGA cannot execute general continuous-time Verilog-A/Verilog-AMS behavior directly. Nodal may instead transform a supported analog or mixed-signal model into an explicitly sampled, discrete-time, finite-precision digital approximation.

Candidate direction:

```scala
val approximation = FpgaApproximation(
  domain = fpga,
  samplePeriod = 10.ns,
  solver = Solver.Trapezoidal,
  numeric = FixedPointPolicy.Auto(
    error = ErrorBudget(absolute = 1.mV, relative = 0.1.percent),
    rounding = Rounding.NearestEven,
    overflow = Overflow.Saturate
  ),
  envelope = ValidationEnvelope(...),
  target = FpgaTarget.Open("reference")
)

val hardwareModel = Nodal.approximate(new ControlledPlant, approximation)
val rtl = Nodal.emit(hardwareModel, EmitOptions(backend = Backend.Verilog))
```

Exact names are deferred to Increment 91. Binding rules are:

- approximation is explicit and never selected by `Backend.Auto`;
- the original Verilog-A/Verilog-AMS or high-precision Nodal result remains the reference;
- the initial supported subset normalizes into deterministic state-space, transfer-function, or explicit-ODE recurrences;
- arbitrary DAEs/algebraic loops, hidden state, adaptive time, unsupported stiff systems, transistor/PVT/parasitic behavior, unsupported noise, and sub-sample ideal events fail explicitly;
- sample period, solver, state/reset, fixed-point formats, ranges, rounding/overflow, rate relationships, event policy, target, and validation envelope are versioned contract inputs;
- one schedule and numeric/resource plan must cover the legal symbolic parameter envelope; clone-per-value specialization is not the default;
- automatic pipelines may meet sample deadlines but cannot change recurrence, numeric, protocol, clock/reset, or event semantics;
- multi-rate partitions use explicit hold/interpolation/decimation/rate bridges and preserve CDC/RDC provenance;
- placed hardware must complete each update before its sample deadline.

The validation ladder remains separate:

1. AMS reference versus high-precision discrete reference;
2. high-precision discrete versus bit-accurate fixed-point reference;
3. fixed-point reference versus generated RTL using simulation/equivalence/formal;
4. RTL/netlist versus placed FPGA hardware and HIL traces.

Passing FPGA hardware does not erase a discretization or quantization mismatch. Reports retain model/tool hashes, parameters, solver, sample rates, numeric formats, stimuli, tolerances, resource/timing results, bitstream/board identity, and explicit limitations.

This capability validates the generated approximation, digital control, sequencing, calibration, and supported closed-loop behavior inside the declared envelope. It does not by itself validate transistor physics, unmodeled parasitics/PVT/mismatch, continuous-time behavior between samples, unmodeled noise/jitter, or behavior outside the envelope.


'''
    text = replace_once(
        text,
        "## Core and future library boundary",
        fpga_section + "## Core and future library boundary",
        subject="FPGA approximation architecture section",
    )

    milestone_anchor = (
        "- **M4 — Scalable core release:** packaged compiler, complete reference, stable "
        "extension points, library-author contract, and compatibility policy."
    )
    milestone_replacement = milestone_anchor + "\n" + (
        "- **M5 — FPGA-accelerated AMS validation:** explicit sampled/fixed-point approximation, "
        "four-level reference evidence, open FPGA implementation, HIL runtime, and a published "
        "capability/limitations matrix."
    )
    text = replace_once(
        text,
        milestone_anchor,
        milestone_replacement,
        subject="M5 milestone",
    )

    phase6 = r'''## Phase 6 — FPGA-accelerated AMS approximation and hardware validation

- [ ] **Increment 91 — AMS-to-FPGA approximation capability gate and API contracts**
  - Use [ADR 0011](../architecture/0011-ams-fpga-approximation-validation.md), [`ams-fpga-validation-plan.md`](ams-fpga-validation-plan.md), and [`ams-fpga-validation-surface.json`](ams-fpga-validation-surface.json) as the mandatory architecture and candidate.
  - Compile candidate approximation, solver, sample/rate, numeric, range, error-budget, validation-envelope, target, and HIL contracts. Prove `Backend.Auto` never selects approximation and that unsupported AMS constructs fail with stable source-located diagnostics.
  - Publish `NodalAmsFpgaApproximation-DG-v0.4.md`, compatibility/migration rules, a machine-readable frozen surface, external-library fixtures, claims language, and complete positive/negative contracts before implementation.

- [ ] **Increment 92 — Analog normalization and sampled-state IR**
  - Normalize supported linear state-space, transfer-function, and explicit-ODE models into target-neutral state/update IR with dimensions, parameters, inputs/outputs, algebraic dependencies, events, initial conditions, and authoritative reference links.
  - Diagnose unresolved DAEs/algebraic loops, hidden state, unsupported nonlinearities, unsupported analyses, and constructs that cannot form a deterministic sampled recurrence.

- [ ] **Increment 93 — Solver and discrete-time recurrence generation**
  - Implement the approved forward/backward Euler, trapezoidal/Tustin, exact-ZOH, and custom-solver contracts only for supported model classes.
  - Generate deterministic coefficients and recurrence IR, high-precision software references, initialization/reset behavior, stability/conditioning evidence, bounded iteration/convergence rules, latency/resource models, and failure diagnostics.

- [ ] **Increment 94 — Range, fixed-point, quantization, and error-budget analysis**
  - Add physical scaling, range assertions/inference, explicit/automatic fixed-point formats, guard bits, rounding, overflow, coefficient quantization, state/intermediate formats, and accumulated error accounting.
  - Generate bit-accurate references and Level B evidence; reject unbounded state, uncovered ranges, impossible error/resource policies, or implicit numeric choices.

- [ ] **Increment 95 — Multi-rate, sampled-event, and real-time scheduling**
  - Implement rational multi-rate partitions, sample/hold, interpolation, decimation, sampled/interpolated event detection, buffering, timestamps, update ordering, and rate/clock bridges.
  - Integrate `ClockDomain`, reset, CDC/RDC, `Valid`/`Stream`, memories, and automatic pipelines. Prove each sample deadline and diagnose infeasible real-time schedules.

- [ ] **Increment 96 — Synthesizable FPGA approximation backend**
  - Lower the discrete fixed-point model into ordinary Nodal digital IR and reuse symbolic parameters, hierarchy, memories, clock/reset, protocols, automatic pipelines, CDC/RDC, and `Backend.Verilog`.
  - Emit deterministic portable Verilog, source maps, recurrence/numeric/rate/schedule manifests, capability limitations, simulation/formal hooks, and exact golden fixtures.

- [ ] **Increment 97 — Differential, equivalence, and formal validation ladder**
  - Implement Level A AMS-reference versus high-precision-discrete comparison and Level B high-precision versus fixed-point comparison using declared waveform/state/event/frequency metrics and envelopes.
  - Implement Level C Verilator/Icarus regression, Yosys equivalence, and SBY properties for recurrence, reset, protocols, multi-rate scheduling, range/overflow, latency, and deadlines. Preserve failures and counterexamples by error class.

- [ ] **Increment 98 — Open-source FPGA implementation and target evidence**
  - Select and pin at least one complete open FPGA target using Yosys, nextpnr, constraints, an open bitstream packer/programmer, deterministic seeds, and reproducible board metadata.
  - Run synthesis, placement, routing, timing, bitstream generation, utilization, DSP/memory accounting, and post-route sample-deadline checks. Add optional vendor adapters without making them normative.

- [ ] **Increment 99 — Hardware-in-the-loop runtime, vertical slices, and capability matrix**
  - Add deterministic start/stop/reset, timestamped sampled streams, parameter loading, trace capture, status/deadline/overflow reporting, reproducible host transport, and optional external ADC/DAC board profiles.
  - Complete RC/RLC, controlled-plant, comparator/ADC/DAC, and PLL/control-loop vertical slices through all four validation levels.
  - Publish supported/unsupported constructs, validation envelopes, approximation/error limits, resource/timing results, board/bitstream evidence, claims language, and the M5 FPGA-accelerated AMS validation release package.


'''
    text = replace_once(
        text,
        "## Deferred reusable library roadmap",
        phase6 + "## Deferred reusable library roadmap",
        subject="Phase 6 FPGA increments",
    )

    text = replace_once(
        text,
        "No official reusable model/component library is implemented by Increments 0-90.",
        "No official reusable model/component library is implemented by Increments 0-99.",
        subject="deferred roadmap range",
    )

    reference_anchor = (
        "- cocotb simulator support: <https://docs.cocotb.org/en/stable/simulator_support.html>"
    )
    reference_replacement = reference_anchor + "\n" + "\n".join(
        (
            "- nextpnr portable FPGA place and route: <https://github.com/YosysHQ/nextpnr>",
            "- Yosys FPGA synthesis documentation: <https://yosyshq.readthedocs.io/projects/yosys/en/stable/>",
        )
    )
    text = replace_once(
        text,
        reference_anchor,
        reference_replacement,
        subject="FPGA references",
    )

    path.write_text(text, encoding="utf-8")


def update_branch_policy() -> None:
    path = ROOT / ".github/branch-policy.json"
    policy = json.loads(path.read_text(encoding="utf-8"))
    promotions = policy["milestone_promotions"]
    promotions["M5"] = "after Increment 99"
    path.write_text(json.dumps(policy, indent=2) + "\n", encoding="utf-8")


def update_digital_adr() -> None:
    path = ROOT / "docs/architecture/0010-digital-verilog-open-source-verification.md"
    text = path.read_text(encoding="utf-8")
    anchor = "- mixed-signal → Verilog-AMS.\n"
    replacement = anchor + (
        "\nThis selection performs no numerical or semantic approximation. In particular, "
        "`Backend.Auto` never converts analog or mixed-signal content into an FPGA model. "
        "AMS-to-FPGA validation is the explicit transformation defined by "
        "[ADR 0011](0011-ams-fpga-approximation-validation.md); only its resulting digital "
        "artifact may use the portable Verilog backend.\n"
    )
    text = replace_once(
        text,
        anchor,
        replacement,
        subject="ADR 0010 approximation boundary",
    )
    path.write_text(text, encoding="utf-8")


def update_digital_plan() -> None:
    path = ROOT / "docs/roadmap/digital-verilog-open-source-verification-plan.md"
    text = path.read_text(encoding="utf-8")
    anchor = (
        "The selected backend and classification evidence are included in the emission "
        "result and build manifest."
    )
    replacement = anchor + (
        "\n\n`Backend.Auto` does not discretize or approximate analog/mixed-signal content. "
        "The explicit AMS-to-FPGA transformation is planned separately in "
        "[`ams-fpga-validation-plan.md`](ams-fpga-validation-plan.md); its resulting digital "
        "artifact may then use this portable Verilog flow."
    )
    text = replace_once(
        text,
        anchor,
        replacement,
        subject="digital plan approximation boundary",
    )
    path.write_text(text, encoding="utf-8")


def update_digital_surface() -> None:
    path = ROOT / "docs/roadmap/digital-backend-v0.3-surface.json"
    surface = json.loads(path.read_text(encoding="utf-8"))
    surface["ams_fpga_approximation"] = {
        "backend_auto_may_select": False,
        "explicit_transform_required": True,
        "architecture": "docs/architecture/0011-ams-fpga-approximation-validation.md",
        "plan": "docs/roadmap/ams-fpga-validation-plan.md",
        "post_transform_backend": "Backend.Verilog",
    }
    path.write_text(json.dumps(surface, indent=2) + "\n", encoding="utf-8")


def validate_json() -> None:
    for path in sorted((ROOT / "docs/roadmap").glob("*.json")):
        json.loads(path.read_text(encoding="utf-8"))
    json.loads((ROOT / ".github/branch-policy.json").read_text(encoding="utf-8"))


def main() -> None:
    update_roadmap()
    update_branch_policy()
    update_digital_adr()
    update_digital_plan()
    update_digital_surface()
    validate_json()
    print("AMS-to-FPGA approximation roadmap integrated")


if __name__ == "__main__":
    main()
