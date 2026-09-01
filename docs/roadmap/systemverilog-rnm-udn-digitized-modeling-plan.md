# SystemVerilog RNM, UDN, and digitized-model reuse plan

**Status:** Binding roadmap clarification  
**Decision date:** 2026-09-01  
**SystemVerilog baseline:** IEEE 1800-2023  
**Machine-readable contract:** [`systemverilog-rnm-udn-digitized-modeling-surface.json`](systemverilog-rnm-udn-digitized-modeling-surface.json)  
**Related backend decision:** [`digital-hdl-language-standards.md`](digital-hdl-language-standards.md)  
**Related fixed-point plan:** [`ams-fpga-validation-plan.md`](ams-fpga-validation-plan.md)

## Goal

Allow Nodal to use SystemVerilog real-number modeling (RNM), user-defined nettypes (UDNs), existing electrical-net packages, `wreal` compatibility profiles, and fixed-point digitized models without turning Nodal into a duplicate implementation of established RNM libraries.

The binding rule is:

> **Reuse established RNM/UDN model and resolution algorithms. Nodal owns target-neutral semantics, capability matching, bindings, wrappers, transformations, and validation evidence; it does not copy or transliterate an existing library algorithm merely to provide Nodal syntax.**

## Separate concerns

Nodal keeps the following concerns separate:

1. **Model semantics** — quantities, dimensions, ports, drivers, resolution requirements, update behavior, parameters, and source identity.
2. **External model binding** — selection of an existing standard, vendor, project, or user-supplied RNM/UDN library and its symbols.
3. **SystemVerilog rendering** — imports, nettype use, module/interface instances, wrappers, adapters, compile order, and manifests.
4. **Model projection** — an explicit conversion of a continuous model into an event-driven RNM or sampled fixed-point model.
5. **Verification** — differential comparison, simulator qualification, accuracy envelopes, performance evidence, and failure classification.

Selecting `Backend.SystemVerilog` does not itself perform a model projection.

## Reuse-first policy

When an approved RNM or UDN library already supplies a required model, nettype, resolver, electrical payload, connect module, or bridge, Nodal must bind to that implementation rather than recreate it.

Nodal may generate:

- package imports and compile-order manifests;
- declarations that use an existing nettype or user-defined type;
- parameter and port mappings;
- thin wrappers around existing modules, interfaces, nettypes, resolvers, or connect modules;
- target-neutral-to-library adapters;
- source maps, diagnostics, capability checks, and validation harnesses.

Nodal must not:

- copy, translate, or lightly rename an existing resolver or electrical-model algorithm into generated code;
- bundle proprietary RNM library source;
- claim that a vendor-specific model is portable IEEE SystemVerilog;
- silently substitute a different resolution or electrical model;
- modify an external algorithm during optimization;
- report equivalence or portability without retained evidence.

The selected external library remains the algorithm authority for that binding profile. Nodal remains responsible for ensuring that the binding matches the declared Nodal quantities, units, directions, driver rules, parameters, and timing assumptions.

## External RNM/UDN binding contract

A reusable binding records at least:

- stable binding and capability IDs;
- library, package, module, interface, type, nettype, resolver, and connect-module symbols;
- version, content hash, license, provenance, and supported platform;
- required simulator, vendor profile, options, defines, and compile order;
- parameter mapping and legal parameter envelope;
- port, interface, quantity, dimension, unit, and scale mapping;
- direction, driver, no-drive, unknown, invalid, contention, and resolution behavior;
- event, delta-cycle, update, sampling, and initialization semantics;
- wrapper and adapter source maps;
- conformance tests, differential tests, known limitations, and failure classification.

The external source may remain outside the Nodal repository. A missing library, incompatible version, unresolved symbol, unsupported simulator capability, or unproven semantic mapping fails before accepted emission.

A user-supplied SystemVerilog RNM module or package may be treated as an external model contract. Nodal does not need to reconstruct its internal algorithm to instantiate, connect, configure, simulate, or verify it.

## SystemVerilog RNM and UDN profiles

RNM and UDN are explicit capability profiles of the future `Backend.SystemVerilog`; they are not a separate `Backend.RNM` identity.

Candidate profile categories include:

- standard IEEE 1800-2023 real-variable and real-port modeling;
- standard IEEE 1800-2023 user-defined types, nettypes, and resolution functions;
- approved project or open RNM/UDN packages;
- vendor-qualified RNM, electrical-net, and connect-module packages;
- `wreal` compatibility through an explicit Verilog-AMS or vendor profile;
- fixed-point digitized simulation using exact-width digital carriers.

Exact public names remain deferred to the Increment 99 design gate.

`Backend.Auto` does not select `Backend.SystemVerilog`, an RNM profile, a UDN profile, or a model projection merely because compatible tools or libraries are installed.

## Nodal-owned resolver boundary

Generating a Nodal-owned resolver is not the default.

It is allowed only when all of the following hold:

- the resolution semantics are explicitly defined and owned by Nodal or the user model;
- no approved external implementation is selected;
- standalone generation is explicitly requested or required by the chosen standard profile;
- the resolver is deterministic, capability-checked, source-mapped, tested, and documented;
- the generated resolver does not duplicate a known selected external algorithm.

The compiler preserves the complete logical driver set. It must not replace full-set resolution with partial, hierarchical, or tree resolution unless the resolver contract declares and validates the algebraic properties needed for that transformation, such as associativity and commutativity.

## Authored models versus projected models

Nodal supports several distinct origins:

- an existing external RNM/UDN library model;
- a user-supplied SystemVerilog RNM model;
- a user-authored target-neutral discrete Nodal model;
- an explicitly projected RNM approximation of a continuous Nodal model;
- an explicitly projected fixed-point digitized approximation.

An existing external or user-supplied RNM model is bound and reused. It is not reverse engineered into Nodal and then regenerated.

A projection from a continuous-time model is a separate, explicit transformation with its own capability limits, approximation choices, validation envelope, and accuracy evidence. It must never be inferred from backend selection.

## Fixed-point digitized modeling boundary

Fixed-point digitized modeling is different from binding an existing RNM library.

For an explicit Nodal model projection, Nodal owns the transformation contract for:

- state and equation normalization;
- discrete recurrence generation;
- sample period and multi-rate behavior;
- range and physical scaling analysis;
- binary-point placement and guard bits;
- coefficient, state, input, output, and intermediate formats;
- rounding, saturation, wrap, trap, and overflow behavior;
- quantization and accumulated error budgets;
- bit-accurate reference generation;
- RTL, simulation, equivalence, and formal parity evidence.

Nodal may still bind to an existing fixed-point arithmetic, operator, DSP, or model library. Such reuse is preferred when it preserves the frozen semantics. The binding must expose exact width, binary point, scale, rounding, latency, overflow, reset, and error behavior; library use must not hide a numeric or timing change.

This fixed-point transformation remains explicit and is never selected by `Backend.Auto`.

## Tool and vendor profiles

Standard, vendor, project, and user-supplied profiles remain distinguishable.

A profile records:

- the exact language standard and extensions;
- required simulator family and tested versions;
- required packages, libraries, switches, defines, and compile order;
- supported real, UDN, `wreal`, connect, interface, assertion, and coverage capabilities;
- scheduling or resolution differences;
- limitations and workarounds;
- conformance and differential evidence.

Successful parsing by one simulator is not sufficient evidence of semantic support or portability.

## Verification obligations

Bindings and projections require different evidence.

For an external RNM/UDN binding, verify:

- symbol, parameter, port, type, unit, and direction mapping;
- wrapper transparency;
- driver and resolution behavior;
- initialization, update, and event ordering;
- compile/elaboration portability for the declared profile;
- compatibility with the library's own reference tests where available.

For a projected RNM or fixed-point model, additionally verify:

- the declared continuous-time or high-precision reference;
- approximation and sampling error;
- quantization and fixed-point error;
- threshold and event-time error;
- overflow, saturation, range, and invalid-state behavior;
- performance and simulator-specific scheduling differences;
- exact RTL or generated-model behavior within the validation envelope.

No passing digital or RNM regression replaces the authoritative Verilog-A or Verilog-AMS reference outside the declared validation envelope.

## Roadmap allocation

This clarification amends the intent of existing unchecked roadmap work; it does not create a parallel RNM-library implementation.

### Increment 68

Extend discrete-real and mixed-signal net work to include:

- target-neutral resolved quantity-net semantics;
- real values versus real nets;
- structured discrete quantity payloads;
- external RNM/UDN binding descriptors;
- driver, no-drive, invalid, contention, resolution, and update contracts;
- explicit `wreal` and vendor compatibility profiles.

### Increment 69

Extend analog/digital conversion work to include:

- typed logic/real/UDN bridges;
- threshold, hysteresis, quantization, saturation, sampling, and reconstruction;
- standard versus vendor connect behavior;
- external connect-module and bridge-library bindings;
- bidirectional and impedance-aware capability checks where supported.

### Increments 87-88

Use the tool-adapter and packaging SPI for:

- simulator and RNM-library capability discovery;
- external package/library paths and compile order;
- version, hash, license, trust, and provenance;
- isolated vendor options and workarounds;
- binding conformance and reproducibility.

### Increment 99

The SystemVerilog design gate must evaluate:

- IEEE 1800-2023 real modeling, UDTs, UDNs, nettypes, interconnects, and resolution functions;
- existing standard, open, project, and vendor RNM packages;
- external-model and external-resolver binding;
- full-driver-set resolution and allowed optimization properties;
- `wreal` and vendor connect behavior as separate profiles;
- simulator scheduling, assertion, coverage, UVM, and UVM-MS interoperability;
- explicit continuous-to-RNM projection boundaries;
- model-reuse and licensing policy.

### Increments 100-106

Retain the explicit Nodal-owned fixed-point digitization and validation flow. Add optional exact bindings to existing arithmetic, DSP, and fixed-point model libraries without delegating or hiding the numeric transformation contract.

### Increment 130

After Increment 99 approval, SystemVerilog implementation must support:

- imports and compile-order manifests;
- use of existing modules, interfaces, types, nettypes, resolvers, and connect modules;
- thin wrappers and semantic adapters;
- standard and vendor-qualified RNM/UDN profiles;
- optional Nodal-owned resolvers only under the restricted policy above;
- native/flat ABI, source-map, and behavior parity.

It must not implement a duplicate general-purpose RNM electrical library.

### AMS Verification Increments 8-10

Generated UVM-MS and commercial simulator profiles must consume the same external bindings and model identities. Generated verification collateral may instantiate and configure an RNM model but must not copy its internal algorithm.

## Non-goals

The roadmap does not require:

- reimplementation of an existing RNM electrical, UDN, resolver, or connect-library algorithm;
- reverse engineering of user-supplied SystemVerilog models;
- automatic conversion of arbitrary transistor, high-index DAE, variable-topology, discontinuous, or noisy models into RNM;
- silent selection of a vendor library;
- silent fallback from one resolver or electrical model to another;
- presenting vendor `wreal` behavior as standard IEEE 1800-2023 behavior;
- replacing continuous-time reference validation with fast event-driven simulation alone.
