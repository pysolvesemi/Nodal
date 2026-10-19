# NodalCrossToolPortability-DG-v0.1

**Status:** Approved
**Scope:** public-api
**Scope:** core-library-boundary
**Date:** 2026-10-19
**Owner:** Foundation 53

## Authority and selected architecture

The owner's explicit request to implement all unchecked Foundation increments from
43 covers this additive F53 contract and its necessary shared infrastructure.
Standing public publication and completed-increment integration authority applies
with `[skip ci]`. This records the selected implementation, not a separate owner
choice of individual signatures. Focused local acceptance applies; no CI or broad
qualification is introduced.

The optional second consumer is the official VACASK 0.3.4 Linux release. It loads
exactly the same verified OpenVAF OSDI 0.4 artifact as the existing ngspice adapter.
No implicit recompilation, model-text rewrite, leaf extraction, approximate lowering
or fallback is allowed. Its actual executed finite profile will be recorded in the
F53 readiness note; documented upstream support alone is not qualification.

One neutral finite electrical bench owns nodes, ordered DUT terminals, sources,
analysis intent, physical probes, initial conditions and solver tolerances. Existing
private Ngspice names retain compatibility aliases. Semantic validation is shared;
consumer-specific syntax, grid rules, capability rejection and output validation
remain in their adapters. Neutral complex/schema/plot values are shared without
copying retained rows. The bounded process runner and OSDI artifact authentication
owner are reused. No generic plugin registry or new simulator framework is needed.

## Public selection and comparison

Append `backend: SimulatorBackend = SimulatorBackend.Ngspice` and
`runtimeLibraryDirectories: Vector[Path] = Vector.empty` to `SimulatorTools`.
The closed alternatives are `Ngspice` and `Vacask`. Existing calls retain their
ngspice behavior; executable names/profile strings do not silently select a tool.
Explicit runtime directories are an optional VACASK tool-installation input, not
a model parameter. Their bounded exact SONAME/content manifest is pinned by the
profile and checked before and after execution; no inherited loader path is used.
Ngspice rejects nonempty explicit directories in its current profile. A private
execution owner dispatches collected runs and streaming folds consistently.
Sweep preflight uses the selected adapter before launching any point, including its
bounded retention estimate. Construction validates bench semantics; unsupported
consumer capabilities reject before tool launch.

`SimulationSession.compareTools` accepts one compiled bench, explicit reference and
candidate tools, typed axis/probe, existing tolerance/component/alignment values.
Both executions must retain identical model/artifact hashes. The reference waveform
uses the existing immutable row view; candidate comparison uses the existing bounded
reducer. Exact alignment is the default. Explicit linear alignment does not extrapolate
or claim symmetric equivalence: the candidate must cover the declared observation
interval, and comparison semantics remain actual candidate samples against the
reference. Both evidence records and underlying diagnostics are retained.

Completed numerical agreement, completed disagreement, unsupported consumer profile,
invalid bench/artifact, tool/adapter failure and incompatible result/alignment remain
distinct. A simulator failure is not evidence of a source-language defect. Source
language/capability rejection remains owned by compilation. Cross-tool agreement
never substitutes for the independent F52 analytical references.

## Process, artifact and output obligations

Pin official archive, executable, version, platform and runtime dependencies. Run in
fresh private evidence directories with bounded time, captures, file/row/cell counts
and the shared process containment. Explicitly load the staged `.osdi`; disable
embedded extraction and postprocessing. Use the pinned consumer's `--tomlfile` option with an owned empty file to replace
default system/user/current-directory startup configuration, and require the fresh
netlist directory's additional configuration file absent before/after execution. Preserve
raw output bytes, deterministic request identity and pre/post input checks.

VACASK raw output has its own admitted dialect and schema. Reuse bounded lexical
machinery only where the actual formats agree; never loosen ngspice validation to
accept a second writer accidentally. Expected probe names/order, physical signs,
complex components, strict axis direction, exact requested DC/AC grid and full
transient domain come from the admitted bench. Failed/truncated/changed output or
callback failure cannot publish a successful aggregate.

## Alternatives and focused acceptance

Rejected alternatives: a second Verilog-A compiler when the same OSDI can be loaded,
a full generic adapter framework, duplicate bench/waveform semantics, trusting any
SPICE-looking output, silent compiler fallback, and treating unavailable second-tool
execution as completion. Xyce/ADMS remains a possible future consumer, not an extra
F53 requirement.

Focused cases include actual unchanged-artifact DC/AC/transient where supported,
independent references, complex real/imaginary comparisons, current-source signs,
repeated identities and a representative bounded trace. Negatives cover disagreement,
tolerance violation, unsupported analysis before launch, profile/artifact mismatch,
load/process/nonconvergence/raw failures, missing probes and alignment/domain errors.
Existing ngspice decks/default public calls and directly affected runner/raw/waveform
checks remain predecessors. Optional use of the delivered adapter does not waive
actual qualification of its advertised functionality.
