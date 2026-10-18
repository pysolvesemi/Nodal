# Foundation 47 readiness

**Date:** 2026-10-18
**Status:** Focused implementation complete; A–F checked, parent/G await verified integration.
**Branch:** `increment/47-verilog-a-capability-profile`
**Base:** `81cec5ab4a2c4ac4a7abfe731bf397d527d3afe0`

F-046 is integrated as `5ac3eb845c01f20488be767d0ae3b7ab09442718`, followed by
verified documentation-only closure. Apply focused prototype checks and concise
architecture review; publish with `[skip ci]`. No CI or hourly monitoring.

## Selected implementation and owners

The [capability gate](../design-gates/NodalBackendCapabilities-DG-v0.1.md) extends
existing backend profiles with an additive machine-readable feature inventory.
Support owns the generated immutable operation descriptors. Dialect owns actual
IR/type language classification, reused by the semantic capability stage and
explicit backend emission. Backend retains all guarded admission, normalization,
rendering and transactional target verification. Existing analog function/context
registries remain authoritative for their signatures and provider contracts.

The exact feature matrix is `core/compiler/backend-profiles-v0.1.json`. Its entries
must distinguish implemented direct families, guarded finite compositions,
normalization-only source forms and unsupported lowering. This is a compiler
profile, not an assertion that all Verilog-A syntax or every simulator is supported.

## Published coverage

The inventory contains 25 feature groups and all 125 current Nodal operations:
58 existing direct admissions, 30 guarded entries and 37 unsupported entries.
The generated table preserves the previous direct set exactly, including the
separately admitted held read. The reserved normalization category currently has
no entries; no unsupported source form is advertised as implicitly normalized.

- Implemented finite families: parameters/envelopes, natures/disciplines,
  conservative connectivity, potential/flow access, scalar analog expressions,
  contributions, differential/integral operators, mathematical functions,
  module-local pure functions, time/waveform operators, noise, transfer functions,
  held reads and static structural hierarchy.
- Conditional families: analysis/environment queries, event monitors, procedural
  control/storage, conservative/storage shape projections, partial topology
  records and normalized probes. The referenced native validators retain their
  ownership, phase, bounds, alias, cardinality and resource-budget requirements.
- Unsupported emission: digital behavior, bridge records, source-only topology
  forms, typed source equations and remaining unlowered shared forms. A pure
  analog logical interface identity is not a digital requirement; its eventual
  lowering remains independently unsupported.

Both target profiles currently share the implemented analog lowering families.
Verilog-AMS eligibility for mixed behavior does not assert digital implementation.
The feature inventory is additive to the existing two-profile configuration;
F-023 fields and F-044 context/provider data remain unchanged.

## Validation plan

Focused catalog tests cover unique references/enums, generated-file freshness,
legacy fields and deterministic output. Native cases cover ordinary analog and
AMS analog eligibility, actual digital types/operations, transitive child and
shared-definition leaks, nested generated scopes, forged root design-kind labels,
unknown profile, Boolean/index neighboring controls and unchanged input/output on
failure. Existing guarded shape/event/hierarchy validators retain their predecessor
checks. Public Scala witnesses retain normalized IR and actual target output.

Results for this plan are recorded below; later simulator execution remains untested.

## Consumer portability and subsequent numerical checks

Language eligibility, structural reparse, independent compilation and numerical
execution are four separate results. Existing concrete observations remain useful:

- F-043 array witnesses are structurally supported, while OpenVAF 23.5.0 and
  reloaded `20260616-3-g0e83f1ed` rejected the tested real arrays, including otherwise
  uninitialized arrays. Scalar controls compiled. See the exact cases in
  [F-043 shape contract](increment43-shape-contract.md).
- F-044 nominal temperature preserves `$simparam("tnom") + 273.15`; provider
  availability is conditional. The same tested witness compiled with reloaded
  and crashed with 23.5.0. See [F-044 readiness](increment44-readiness.md).
- F-046 safe continuous output compiled with reloaded. The tested `ceil` witness
  crashed with `intrinsic llvm.ceil.f64 not found`, exit 101 and no OSDI. See
  [F-046 readiness](increment46-readiness.md). No broader function inference follows.

F-048 should consume the actual supported analog hierarchy, context, array/event
and safe/risky function witnesses with their compiler identities and diagnostics.
It must classify observed failures separately from malformed generated output.
F-049/F-052 should reuse the RC analytic DC/transient/AC reference, context unit
references, and original F-043/F-044/F-046 event and function criteria. Candidate
value tolerances remain absolute `1e-12` plus relative `1e-9` in the compared SI
quantity; event-time tolerance `1e-12 s` is a proposed fixed-grid criterion, not an
achieved accuracy claim. Observe independent event histories and reached bodies;
never infer skipped numeric argument evaluation from monitor disablement.

## Architecture and scale review

The catalog replaces the backend's private operation list rather than adding a
second source of truth. Sorted registry lookup is logarithmic in the finite entry
count; early inspection follows existing definitions and structural types without
instance or lane expansion. Actual guard proofs retain their existing costs.
No whole-pipeline linearity, simulator coverage or numerical equivalence is claimed.

## Focused implementation results

Four public Scala tests pass, together with the public construction check and
six fresh-instance-identical VA/AMS exports. The hierarchy witness preserves the
child's temperature/nominal-temperature query and actual conservative binding.
The storage witness uses analog Boolean/Integer storage and an initial-step
conditional/case body that sets a held voltage to 1 V. The negative keeps an
analog-only root declaration list and a genuine two-level descendant containing
an explicit clock domain and Boolean digital port.

The 22-command native replay passes normalized fixed points, repeated target
byte equality and direct-versus-normalized target equality for both positives in
both profiles. Truthfully mixed public source fails the existing design-kind
boundary. An explicitly adversarial copy changes only the claimed root profile
to analog; the new gate still rejects the actual descendant digital domain with
`NODAL-VERIFY-CAPABILITY-006` before normalization. No generated output is published
on either failure.

The new native target passes 64 focused cases, including 1,000 shared child
references, 128 nested type wrappers, operand/result/block-argument/nested-attribute
carriers, pure-analog interfaces versus digital members, unknown/nonstring language
profiles, named capability passes, input/output transactionality and the preserved
source-equation diagnostic. Focused review corrected an overbroad initial
classification of interface identities: only actual digital members establish that
requirement. The analog interface lowering limitation remains explicit.

The 28 focused catalog tests pass, including stale output, malformed inventory,
legacy profile compatibility and observation/admission independence.
All seven selected native targets pass. The directly affected predecessors cover the backend, semantic pipeline,
context backend, writable arrays, generated topology and generated persistent
storage. Source formatting/Scalafix, native formatting, architecture and package
visibility checks pass. Raw commands and outputs are under `.work/increment47/`.
No CI or simulator execution was run.

Reproduction uses the existing local toolchain:

```text
python3 scripts/generate_backend_capability_registry.py --check
python3 -m unittest discover -s tests/compiler -p test_backend_capability_registry.py
.work/scala-validation/mill-jvm.sh core.scala.testkit.test.testOnly nodal.internal.testkit.Increment47CapabilityTests
.work/scala-validation/mill-jvm.sh core.scala.testkit.test.runMain nodal.internal.testkit.Increment47MlirCheck .work/increment47/source
.work/scala-validation/mill-jvm.sh examples.continuousTimeApi.runMain nodal.increment47fixture.Increment47ConstructionCheck
```

Native tools are `nodalc --nodal-gate-default --mlir-print-debuginfo` and
`nodal-translate --nodal-to-verilog-a` / `--nodal-to-verilog-ams`, with each input
file and explicit `-o` output. The focused native test is
`nodal-target-capability-unit-tests`; its CTest name is
`nodal.native.target-capability-unit`.

## Independent compiler observations and exact handoff

Both new positive VA witnesses passed internal structural target reparse.
Independent OpenVAF-reloaded `20260616-3-g0e83f1ed` then produced distinct outcomes:

- `AnalogCapabilityStorage`: compilation succeeded, exit 0, OSDI produced.
  Generated model SHA-256:
  `277c6fb6e9d411e7f33146bead1a5f74de1b4143f1afc59dbd22cd3a2c289747`.
- `AnalogCapabilityHierarchy`: compilation rejected the named child module
  instance, exit 65, no OSDI. The diagnostic identifies the instance call rather
  than a query-provider failure. Generated model SHA-256:
  `424856c5f41fa403f382817238093e5585fb68b43115d7e5a1d08e3ffcd4482f`.

Compiler executable SHA-256:
`b117284d8f871dbf54609ab009cdff819f443d8d33064111b8b0782af5c7e931`.
Commands use `openvaf-r MODEL -o OUTPUT.osdi`; exact commands, exit codes and
stderr are retained in `.work/increment47/openvaf/`. These are individual witness
observations, separately encoded in the feature inventory. They do not certify
all storage/event compositions or make legal hierarchy a language exclusion.

For F-049/F-052, the new hierarchy's analytic DC reference is
`I = V / resistance * temperature / nominalTemperature` in SI units with explicit
nonzero resistance and available nominal-temperature provider. Hierarchy consumer
support must first be supplied by F-048 or the later owning lowering; no hidden
flattening is performed here. The compiled storage witness's reference is a held
1 V after its reached initial-step body, with the Boolean and Integer controls
observed separately. Use the proposed value/time criteria above, preserve analysis
and initialization conditions, and record numerical execution separately. Neither
witness has been simulated in this increment.
