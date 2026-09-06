# ExternalModule HDL integration and simulation v0.1 plan

**Status:** Normative roadmap requirement; API gate, implementation, and evidence remain open  
**Added:** 2026-09-06  
**Public name:** `ExternalModule` — selected by the user on 2026-09-06  
**Foundation roadmap:** [`nodal-development-todo.md`](nodal-development-todo.md)  
**Related semantic contract:** [`core-semantics-api-v0.3-plan.md`](core-semantics-api-v0.3-plan.md)  
**Simulation contracts:** [`nodal-hvl-simulation-v0.1-plan.md`](nodal-hvl-simulation-v0.1-plan.md), [`native-digital-simulator-adapters-v0.1-plan.md`](native-digital-simulator-adapters-v0.1-plan.md), and [`dependent-productivity-and-verification-tracks-v0.1-plan.md`](dependent-productivity-and-verification-tracks-v0.1-plan.md)

## Purpose and existing coverage

Nodal must allow an existing Verilog, Verilog-A, or Verilog-AMS module to be
instantiated, connected, parameterized, emitted, and simulated without rewriting
its implementation in Nodal. Support both a Nodal design containing external
instances and an external module used as the entire DUT through a typed wrapper.

The existing roadmap already includes analog hierarchy/parameter overrides,
digital black-box boundaries, inout and domain propagation, external operation
effects and model availability, and simulator-adapter infrastructure. In
particular, `ExternalOp` describes operation-level type, latency, throughput,
domain, and effect metadata. Those requirements alone do not define a complete
foreign-HDL module declaration, source/dependency bundle, implementation binding,
link/elaboration lifecycle, or executable-model simulation contract. This plan
makes those missing deliverables explicit under existing owning increments.

SpinalHDL's BlackBox is the usability reference: declare the interface and
parameters, bind clock/reset signals and external names, associate HDL sources,
and instantiate the block normally. Its official documentation is linked below.
Nodal must implement its own Scala 3 and typed-IR contract, not add a SpinalHDL
dependency or promise complete source compatibility.

## Selected public name

Use **`ExternalModule`** as the user-selected public name, alongside Nodal's
`Module`. The naming decision was approved on 2026-09-06.

- `Module` describes hardware implemented in Nodal.
- `ExternalModule` describes a module whose implementation is supplied outside
  Nodal, including fully visible source, a supported compiled model, or an
  explicitly declared interface-only integration artifact.
- `ExternalOp` remains the operation/effect/latency abstraction. It may reference
  an external module implementation through an explicit binding; a module is not
  automatically a pure operation or a movable pipeline node.

The name does not imply that the source is secret, that the behavior is unknown
to the simulator, or that the instance cannot be tested. Use “BlackBox / foreign
HDL integration” in migration/search documentation. Do not add multiple synonymous
APIs merely for discoverability; `ExternalModule` is the selected name, not one
of several remaining naming candidates.

This records the approved name, not a completed implementation or frozen API
signature. The owning increment must still compile-test the declaration, binding,
and instantiation surface and obtain the applicable design-gate approval.
Do not change existing accepted APIs, historical gates, or machine-readable
surfaces in this documentation-only update.

## One declaration, explicit implementation selection

Keep three identities separate inside the existing construction/authoritative
MLIR architecture:

1. **Module declaration:** immutable external HDL definition/library identity,
   language/profile, typed formal parameters and ports, symbolic shape rules,
   boundary domains/effects, capabilities, source identity, and interface ABI.
2. **Implementation binding:** selected source set or supported compiled library,
   dependencies, tool/profile requirements, purpose, and provenance. Simulation,
   synthesis, formal, and interface-only packaging may select different declared
   implementations, but must retain or explicitly adapt the same interface.
3. **Instance:** hierarchy identity, actual parameter expressions, port/terminal
   connections, domain bindings, and the selected implementation reference.

Different legal parameter overrides must reuse the same external definition,
not clone a module for every elaboration default. Emit the external instantiation
and only genuinely required typed wrappers/adapters; never emit a competing empty
module body when the real definition is supplied. Detect duplicate module/library
symbols, ambiguous implementation selection, and accidental linkage to a stub.

A declaration alone is useful for packaging an integration boundary. It is not
an executable simulation model. Simulation requires a resolvable source-backed
or adapter-supported compiled implementation. Missing/unloadable models must
fail compilation/elaboration or preflight; never silently drive zero/X, substitute
an empty module, or report a passing simulation without an executing model.
An explicitly selected behavioral substitute is allowed only as a separately
identified model with its own applicability and evidence, not as evidence that
the original IP ran. Formal abstraction and synthesis black-box preservation
are separate explicit modes, not simulation fallbacks.

Foreign HDL bodies remain external artifacts; importing them must not require a
second Nodal semantic IR or a complete foreign-language frontend. Preserve typed
declarations/instances and required boundary metadata in authoritative MLIR.
Use available target/compiler signature checks where supported; optional
signature import must produce reviewable typed declarations, not regex-based
inference presented as authoritative semantics.

## Parameters, ports, topology, and stable names

- Support named integer, real, string, Boolean and sized bit-vector parameter
  bindings where legal in the selected language/profile. Retain exact width,
  signedness, defaults, legal ranges, units, and parent-to-child symbolic
  expressions. Reject runtime parameter values, unknown/duplicate formals, illegal
  overrides, unsupported kinds, and invalid parameter envelopes. Do not expose
  non-overridable local constants as user-settable parameters.
- Preserve scalar digital ports, signed/unsigned/bit-container values, enums via
  explicit encoding, direction, resolved digital inout, and independent symbolic
  widths and Vec dimensions. Map logical aggregate paths to the exact external
  pin names and layout; do not rename external pins through Nodal's ordinary
  internal naming policy. Diagnose missing pins, width/sign/direction conflicts,
  ambiguous names, and invalid open/tied connections.
- Reuse recursive named field-vector requirements FV-01 through FV-06 in
  [`shaped-values-naming-quality-v0.3-plan.md`](shaped-values-naming-quality-v0.3-plan.md).
  Both whole-record packed and named-leaf boundaries need exact recorded mapping;
  external `pixels_color_red` and `pixels_position_x` retain their own widths and
  dimensions. No automatic ABI transpose or equal-bit-count reinterpretation.
- Map clocks, reset polarity/style, enables, generated clocks, and multiple domains
  explicitly using existing domain primitives. Unknown external timing/domain
  behavior must not make CDC/RDC checks silently disappear. Retain declared versus
  independently verified assumptions, and preserve latency/protocol contracts.
- Verilog-A/AMS ports include discipline-qualified conservative terminals and,
  where supported, directional analog signal-flow or discrete-real values.
  Retain nature/discipline, physical units, reference node, connection-set identity,
  potential/flow access, and flow orientation. Conservative terminals are not
  ordinary input/output wires, digital inout buses, or implicit sampled reals.
- Keep digital resolution, conservative analog topology, directional analog
  signals, and event-driven real nets distinct. A mixed-signal connection requires
  an explicit approved bridge/connect rule and ownership of analog/digital
  scheduling. Reject unsupported conversions rather than adding an implicit ADC,
  DAC, mux, or tri-state simplification.

External state, memory, events, analog contributions, noise, and side effects must
have conservative boundary metadata. Unknown effects or latency form a scheduling
and optimization barrier. Do not move, merge, duplicate, retime, or remove an
opaque instance solely because its outputs appear unused. Transformations need
an applicable contract and evidence, including analog loading and side effects.
For opaque analog models, record the boundary topology and available state/event/
equation summaries; do not invent internal equations or claim complete DAE,
convergence, or internal CDC verification from the interface declaration alone.

## Source bundles and reproducible build integration

An implementation source bundle must carry explicit language/standard/profile,
entry definition and library identity, file order and compilation-unit boundaries,
include directories/files, macro defines, library searches/dependencies, and
auxiliary initialization/model/data files. Support `.v`, `.va`, and `.vams` inputs;
an extension is a hint, not proof of language capability or digital-only behavior.
Support multiple sources, nested dependencies, and packaged library resources.

Record all semantically relevant source/dependency content hashes, include/define
scope, resolved parameters, model/PVT corner selection, wrapper mappings, tool
versions/options, plugin/adapter ABI, platform for compiled libraries, and any
required external assets in deterministic manifests and build cache keys. A
changed included file, model library, memory initialization file, define, or
parameter must invalidate the appropriate compiled model cache. Resolve relative
paths against a declared source/package root, not an accidental working directory.

Preserve source-unit boundaries instead of blindly concatenating files with
possibly different macro, timescale, discipline, or library contexts. Deduplicate
only identical definitions with compatible identities/options. Keep generated
collateral and source maps distinguishable from third-party originals. Retain
external compiler file/line diagnostics and the Nodal declaration/instance that
caused the failure, even where foreign-body semantic source mapping is unavailable.

Allow local/user-provided sources and explicitly selected supported compiled or
protected libraries. Do not imply every simulator can load an arbitrary binary,
encrypted model, SPICE subcircuit, or foreign-language source. Preserve license
and redistribution restrictions; optional commercial/proprietary assets must not
be copied into public fixtures, logs, or packages. Loading native libraries or
running external build hooks follows the existing explicit plugin/process trust
policy; attaching a source path must not execute an undeclared shell command.

## Simulation routing and capability limits

The simulation flow includes the chosen external implementation:

```text
Nodal design or typed external-DUT wrapper
    + external declaration / instance bindings
    + selected foreign HDL source or compiled-model bundle
    -> backend and simulator capability preflight
    -> compile / link / elaborate the complete design
    -> simulation harness or qualified live Nodal HVL adapter
    -> typed values / events / quantities / waves / checks / results
```

The executable design inventory includes the transitive requirements of every
external implementation. `Backend.Auto` must not classify a design as pure digital
merely because Nodal cannot inspect a foreign body or because a file ends in `.v`.
Unknown requirements need explicit declarations and validation or a diagnostic.
Backend emission eligibility and simulator execution eligibility are separate:
being able to emit a module instance is not proof that any available simulator
can execute its model.

| External implementation | Planned execution route | Required boundary |
| --- | --- | --- |
| Supported digital Verilog | Compile the external source dependencies with the Nodal-generated DUT; qualify Icarus and Verilator independently. | Preserve parameter/port binding, clocks, reset, inout, event/timing behavior and the selected two-/four-state profile. |
| Supported Verilog-A | Use the pinned qualified OpenVAF/OSDI plus ngspice path for its accepted model subset, or an explicitly qualified analog/AMS adapter. | Validate model entry, OSDI/compiler/simulator ABI, pin order, model-versus-instance parameters, disciplines/units, and requested analyses. |
| Verilog-AMS including continuous-time and digital behavior | Use a qualified AMS simulator or explicitly qualified mixed-signal co-simulation profile. | Negotiate event/solver coupling, cross-domain bridges, model features, timestep/iteration, and tolerances; unsupported models fail before execution. |

The OpenVAF documentation describes compilation of supported Verilog-A into OSDI
libraries loaded by ngspice and also documents language-subset restrictions.
This is not a claim that arbitrary behavioral Verilog-A, arbitrary hierarchical
Verilog-A, or full Verilog-AMS runs through that route. Preserve a documented
capability rejection or use a separately qualified adapter. Do not flatten or
rewrite a foreign body into an approximation to force tool acceptance. A SPICE
harness may bind a supported compiled external model at its declared node boundary;
that is not an import of its internal implementation into Nodal IR.

Verilator's documented AMS support is a small subset of constructs with digital
counterparts; do not present it as a general continuous-time AMS solver. Open-source
mixed-signal execution is qualified per supported profile/model, not inferred from
filename acceptance. Full AMS and commercial simulator support remain within
the existing separately qualified/deferred tracks. A missing AMS engine must not
block release of already qualified digital or analog-only external-module paths.

### User-facing simulation behavior

The eventual live HVL flow must drive, sample, wait, monitor, and check an external
DUT through the same typed endpoint identity as a native Nodal DUT. Support both
a standalone imported DUT and a mixed native/external hierarchy. Nodal retains
HVL scheduling/check/transaction ownership; the selected simulator executes the
external behavior. Do not require users to write VPI/DPI, C++, Tcl, shell, or
manual SPICE harness plumbing for the ordinary supported flow.

Digital adapters expose stable top-level or explicitly exported endpoints,
width-safe values, timescale/precision, four-state/profile behavior, edge/time-slot
synchronization, waveforms, and normalized errors. Internal probes are optional,
explicitly exported, and capability-dependent; opaque or encrypted internal
signals cannot be promised merely from their names.

Analog adapters use typed excitation/measurement and monitor contracts, including
units, analysis kind, initial conditions, tolerances, and parameter/PVT sweeps.
Do not treat conservative-node writes like assignments to digital variables.
A solver owns continuous-time iteration/convergence; expose supported live
control separately from batch DC/AC/transient harness capability. Batch simulation
is not evidence that live analog run-until/read/write control is implemented.
Mixed-signal execution additionally records analog/digital event synchronization,
bridge choices, timestep/interpolation/error rules, and the qualified common
semantic subset used for comparisons.

Generated Verilog/AMS testbench projections may consume the same declaration and
implementation manifest where their profiles support it. They remain optional
siblings of live HVL, not a prerequisite for running an otherwise supported live
external DUT. Simulator changes should not require a different public declaration
or testbench for the shared supported subset; report real capability differences
instead of silently weakening tests.

## Ownership, ordering, and Foundation barrier

These requirements refine existing increments and do not introduce a parallel
compiler, renumber the roadmap, or reopen completed acceptance evidence.

- The analog external declaration/binding checkpoint belongs before acceptance
  of Foundation Increment 42, with interface/shape work in 43 and capability,
  compile, harness, and regression work in 47-53 as already scoped.
- Digital declaration/parameter/shape/domain/hierarchy work belongs to 54-58;
  portable emission, tool-backed simulation, synthesis/equivalence infrastructure
  and regressions belong to 65-67. AMS emission/adapter/profile work belongs to
  68-78 where applicable. Existing naming and plugin prerequisites still apply.
- Foundation 147-149 must include external-DUT logical endpoint, artifact-binding,
  capability, simulation-lifecycle, and provenance seams in their architecture
  gates. Foundation 152's accepted history is unchanged; later implementations
  consume its existing generated-testbench projection seam.
- Full live HVL runtime, production native/analog/mixed adapters, reusable agents,
  generated-testbench implementations, and commercial integrations remain blocked
  by the complete Foundation barrier in the dependent verification plans. Only
  already assigned Foundation compile/harness/smoke work and architecture gates
  occur before it. This plan does not use external modules to bypass that barrier.

## Implementation checklist and acceptance evidence

All items below are open. Each is completed only by its owning implementation
and applicable gate/evidence, never by this roadmap edit alone.

- [ ] **EM-01 — Declaration, name, and boundary API gate:** Increments 42 and
  54-58 approve the API under the selected `ExternalModule` name, including
  definition/binding/instance separation, external names, typed parameters,
  digital/analog ports, directions, units, domains, effects, and exact ABI checks.
  Keep `ExternalOp` separate.
- [ ] **EM-02 — Source and implementation manifest:** Increments 42, 47-53 and
  65-67 implement source bundles, dependencies/includes/defines/library order,
  auxiliary assets, purpose-specific bindings, content hashes, cache invalidation,
  licensing/provenance, compile diagnostics, and missing/ambiguous-model errors.
- [ ] **EM-03 — IR, hierarchy, and backend integration:** Increments 42-43,
  54-58, 65 and 68-78 preserve symbolic parameter/shape expressions, named-field
  layouts, pin/terminal identity, opaque boundaries, exact instantiation, and
  transitive backend/simulator capability checks. Do not emit duplicate bodies
  or weaken topology/domain rules. Later passes 83-88 preserve this contract.
- [ ] **EM-04 — Executing digital-model vertical slice:** Increments 65-67 add
  a parameterized external arithmetic block plus a sequential RAM/FIFO fixture,
  multiple differently parameterized instances, a native/external hierarchy, and
  a standalone external DUT. Exercise Icarus and Verilator within declared
  capabilities, reset/enable, signed data, inout where supported, initialization
  dependencies and the FV named-field ABI. Demonstrate real model behavior.
- [ ] **EM-05 — Executing analog-model vertical slice:** Increments 47-53 add
  a supported external Verilog-A resistor/diode or similarly qualified model,
  node/parameter binding, a native/external harness, independent DC/transient
  and applicable AC expectations, units/tolerances, model-card/include changes,
  source and supported OSDI loading, and unsupported-feature/ABI rejection.
  Qualify hierarchical foreign models only where the selected tool supports them.
- [ ] **EM-06 — Mixed-signal qualification and later adapters:** Foundation
  68-78 and 147-149 freeze the required seams and capability probes; the existing
  Foundation-gated Analog/Mixed-Signal Verification track implements the live
  external AMS slice, analog/digital event and bridge semantics, and qualified
  open/commercial profiles. Keep unsupported/full-AMS states explicit; no silent
  digital-only or sampled approximation of continuous-time behavior.
- [ ] **EM-07 — Live external-DUT simulation and projections:** Foundation
  147-149 freeze endpoint/lifecycle/cache contracts. After the complete barrier,
  Digital Verification's native adapters and the corresponding analog/mixed
  workstreams implement ordinary typed HVL stimulus, waits, checks, waves,
  timeouts, failure mapping, and standalone/nested external DUTs. Qualify optional
  generated-testbench projections independently under the existing HVL plan.
- [ ] **EM-08 — Negative, independent, and boundary evidence:** The owning
  simulation/validation increments cover absent/stub/duplicate definitions,
  missing includes/libraries, wrong top/pin order, incompatible parameter kind/
  width/sign/shape/discipline/domain, zero/invalid shapes, malformed models,
  unsupported timing/solver/library ABI, crash/timeout, bad manifests, stale
  caches, and source-less simulation requests. Change/remove a model and a
  dependency to prove the run uses the selected external implementation. Compare
  actual behavior with independent numeric/protocol/physical reference checks.
  Formal equivalence with an abstract external instance proves only the modeled
  boundary/assumptions; publish that limit instead of claiming the IP verified.
- [ ] **EM-09 — Documentation and release matrix:** Increment 92 documents
  BlackBox-to-ExternalModule concepts, parameterized Verilog, supported Verilog-A,
  mixed-signal examples, source packaging, name/leaf mappings, model selection,
  standalone-DUT simulation, limitations, and failure diagnosis. The applicable
  release gates publish separate declaration/emission, compile/elaboration,
  batch/live simulation, synthesis, and formal capability evidence per language,
  tool version, profile, and model. Update machine-readable surfaces with the
  approved implementation; do not mark unsupported/unavailable as passed.

## References and qualification notes

Reviewed on 2026-09-06. External tool documentation is motivation and capability
input, not a blanket support claim. Implementation must qualify the repository's
pinned compiler/simulator distribution and exact versions, including OSDI ABI.

- SpinalHDL BlackBox declaration, generics, clock/reset mapping, naming and RTL
  sources: <https://spinalhdl.github.io/SpinalDoc-RTD/master/SpinalHDL/Structuring/blackbox.html>
- OpenVAF supported-model compilation and OSDI/ngspice loading:
  <https://openvaf.semimod.de/docs/getting-started/usage/>
- OpenVAF language-subset compliance and limitations:
  <https://openvaf.semimod.de/docs/details/verilog-a-standard/>
- ngspice OSDI/OpenVAF integration:
  <https://ngspice.sourceforge.io/osdi.html>
- Verilator language support and its limited AMS subset:
  <https://verilator.org/guide/latest/languages.html#verilog-ams-support>
