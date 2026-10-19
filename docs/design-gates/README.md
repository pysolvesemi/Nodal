# Design-gate policy

Design gates freeze compatibility-sensitive decisions before implementation is
allowed to depend on them.

## Required scopes

The contribution checker currently recognizes these scopes:

```text
public-api
core-library-boundary
```

A pull request touching a protected path must add or update an approved gate in
this directory. The gate file name must match `*-DG-v*.md` and contain:

```text
**Status:** Approved
**Scope:** public-api
```

or:

```text
**Status:** Approved
**Scope:** core-library-boundary
```

A gate must state the exact contract, accepted alternatives, rejected
alternatives, compatibility impact, tests, and approval evidence. Draft or
proposed documents do not authorize protected changes.

## API freezes and candidate evaluations

Increment 11 froze public API v0.1, and Increment 12 froze the clock/reset v0.2
surface. Candidate-evaluation gates may authorize compile-only prototypes while
explicitly leaving their public surface unfrozen. Increment 14 uses that model
for automatic-pipeline, Interface/Role, digital-inout, and AMS candidates;
Increment 15 owns the unified public API v0.3 freeze.
## Unified public API v0.3

`NodalCoreSemanticsPipelineApi-DG-v0.3.md` is the authoritative unified
freeze for core semantics, Interface/Role/inout, automatic pipeline, and
backend selection. The Increment 13 and 14 gates remain candidate evidence.

## Foundation 47 language capabilities

[NodalBackendCapabilities-DG-v0.1.md](NodalBackendCapabilities-DG-v0.1.md) adds the
shared operation/feature inventory and actual-IR language gate to the existing
backend framework. Simulator observations remain separate from language admission.

## Foundation 48 compiler integration

[NodalOpenVafValidation-DG-v0.1.md](NodalOpenVafValidation-DG-v0.1.md) defines the
shared bounded process service, pinned OpenVAF receipts and finite opt-in target
statement provenance. Consumer limitations do not redefine source semantics.

## Foundation 49 simulator harness

[NodalNgspiceHarness-DG-v0.1.md](NodalNgspiceHarness-DG-v0.1.md) defines the private
bounded bench, simulator and raw-result boundary over F-048 compiled artifacts.
Public simulation and general waveform APIs remain with F-050 and F-051.

## Foundation 50 Scala simulation API

[NodalScalaSimulationApi-DG-v0.1.md](NodalScalaSimulationApi-DG-v0.1.md) defines
source-correlated immutable captures, dimensioned stimuli, bounded batch analyses
and explicit measurement/assertion evidence over F-048/F-049 shared services.

- [Typed waveform results and bounded streaming v0.1](NodalWaveformResults-DG-v0.1.md):
  Foundation 51 public typed axes, interpolation/comparison and shared bounded
  streaming protocol/simulator ownership; preserves Foundation 50 compatibility.

- [Analog regression and scalar hierarchy simulation v0.1](NodalAnalogRegression-DG-v0.1.md):
  Foundation 52 maintained numerical cases, represented analog assignment admission
  and explicit canonical whole-graph scalar hierarchy materialization.

- [NodalCrossToolPortability-DG-v0.1](NodalCrossToolPortability-DG-v0.1.md):
  approved additive F53 optional second-consumer, shared finite bench and explicit
  cross-tool comparison contract.

- [NodalDigitalTypeEnum-DG v0.1](NodalDigitalTypeEnum-DG-v0.1.md): approved additive F54 hardware enum carrier, exact digital type contracts and explicit legacy decode migration under standing Foundation authority.
