# ADR 0005: Use explicit backend capability profiles

- **Status:** Accepted
- **Date:** 2026-08-20
- **Scope:** Backend and simulator compatibility architecture

## Context

Nodal is one source language with analog, digital, and mixed-signal constructs. Verilog-A can represent only the analog subset, while Verilog-AMS is the first complete target. Simulators and open-source tool flows may support smaller or different subsets than the language standard.

Without explicit profiles, a backend may silently omit, approximate, or rewrite unsupported semantics.

## Decision

Every Nodal backend declares a named, versioned capability profile.

The initial language-output profiles are:

- `verilog-a`: analog-only output for models that use supported analog constructs;
- `verilog-ams`: the first complete analog-and-digital mixed-signal output target.

A future SystemVerilog-AMS backend receives a distinct profile after its research/design gate. It must not change the meaning of existing profiles.

Before translation, the compiler runs profile verification over authoritative IR. Unsupported constructs are reported with stable diagnostics and source locations. The backend must not silently:

- drop a construct;
- replace it with a weaker approximation;
- convert digital behavior to real-number behavior;
- remove unsupported analysis/event behavior;
- select simulator-specific syntax without an explicit profile.

Capability information must be available in human-readable documentation and a machine-readable form suitable for CLI queries, tests, packaging, and simulator adapters.

Language profiles and simulator/tool profiles are separate. For example, an OpenVAF/ngspice validation profile may support only part of generated Verilog-A; it does not redefine the `verilog-a` language profile.

Common semantic verification and target-neutral transformations occur before profile-specific lowering. Target-specific spelling and portability work occur as late as practical.

## Required profile metadata

Each profile eventually declares:

- profile name and version;
- language/standard target;
- supported Nodal feature IDs;
- required lowering passes;
- unsupported and conditionally supported constructs;
- target file kind and extension;
- simulator/tool compatibility notes;
- extension policy;
- diagnostic behavior for profile violations.

## Consequences

### Positive

- Backend selection is predictable and testable.
- Analog-only open-source flows coexist with full AMS output.
- Simulator limitations do not leak into core language semantics.
- New backends can be added without adding target-specific public APIs.
- Capability matrices can drive conformance coverage.

### Costs

- Every new feature must update profile metadata and tests.
- Portability differences require explicit classification.
- Some models will compile for one profile and be correctly rejected for another.

## Rejected alternatives

- **Infer behavior from the output extension:** too implicit and cannot express tool compatibility.
- **One permissive backend with best-effort output:** risks silent semantic corruption.
- **Separate Nodal frontend languages for Verilog-A and Verilog-AMS:** fragments the API and duplicates semantics.
- **Treat simulator support as the language definition:** makes the language dependent on external tool limitations.

## Follow-up increments

- Increment 20 creates the backend/profile framework.
- Increment 44 publishes the Verilog-A feature matrix.
- Increment 64 adds portable/full AMS and simulator-extension profiles.
- Increment 78 evaluates a future SystemVerilog-AMS profile.
