# Backend language capability inventory v0.1

**Status:** Approved
**Scope:** core-library-boundary
**Date:** 2026-10-18
**Owner:** Foundation 47

## Authority and compatibility

The owner requested implementation of all unchecked Foundation increments from 43
and authorized their public commits and pushes. This additive contract implements
F-047.A–F without changing the public Scala API, selecting a simulator on the user's
behalf, or extending the set of executable target operations. The original
[NodalBackendFramework v1.0](NodalBackendFramework-DG-v1.0.md) remains the historical
F-023 contract; its initial empty-shell limitation is not today's feature matrix.
The clone, gate, private candidate, target verification, reparse and publication
transaction remains mandatory.

## Exact shared contract

The existing `backend-profiles-v0.1.json` retains its schema/version and legacy
profile fields. An additive versioned inventory assigns stable feature IDs,
operation domains and admission categories, documented constraints and references
to the owning semantic validators. A deterministic generated immutable Support
registry is the single operation-name lookup consumed by early capability checks
and final direct admission. Unknown operations never gain final admission.

- Direct admission means the operation belongs to the existing lowering family;
  semantic verification, placement, ownership, numeric and rendering requirements
  still apply. It does not mean every operand or composition is implemented.
- Guarded admission retains the actual procedure, shape, storage, topology and
  probe validators. The catalog does not replace those proofs with a flag.
- Normalize describes source operations that require existing normalization before
  final admission. The early language gate must not reject them merely because
  they are absent from the final direct table.
- Unsupported identifies an explicit missing lowering or target-language boundary.
  Source equations retain their equation-to-target diagnostic and later owner.

The Dialect layer resolves actual digital requirements using the shared operation
catalog and typed carriers, including nested shape payloads and type attributes.
Builtin `i1` and `index` remain legitimate analog Boolean/static-index carriers.
Conservative terminals, branches and analog event tokens are not digital merely
because they contain names or are used in a bridge. A conservative/signal-flow
bridge must not be mislabeled AMS-only from its operation name alone.

The Verilog-A language gate examines all definitions emitted by the backend,
including nested generated regions and uninstantiated definitions. It does not
trust a root design-kind label or expand hierarchy instances. It runs before
normalization against the explicitly selected language, and is also reusable by
the semantic capability stage when a backend profile is supplied. Verilog-AMS
language eligibility does not imply implemented digital HDL lowering.

## Simulator boundary

The language baseline is the existing Verilog-AMS 2023 analog subset contract.
Consumer observations are separately identified by tool/version, concrete witness,
outcome and existing evidence. Missing observations mean untested, not supported.
An OpenVAF limitation must not remove valid source semantics from all language
profiles. Nominal-temperature availability remains conditional; unavailable context
providers do not acquire fallback values. F-048 owns executable consumer capability
integration; F-049/F-052 own numerical execution and reference comparisons.

## Alternatives and cost

Keep the existing manifest and generate its small operation table rather than add
another independent allowlist, dynamic plugin system or simulator-defined language.
Use existing semantic registries and guarded validators rather than duplicate
operator signatures or detailed shape/event contracts in this inventory. Sorted
name lookup is logarithmic in catalog size. The language scan visits the existing
IR and type/attribute structure without instance or array-lane expansion. No target
expression optimization or output rewrite is part of this change.

## Focused verification

Validate stable IDs, enum/reference uniqueness, deterministic generation and stale
output detection, preserving legacy manifest fields. Test actual analog positives,
digital type/operation negatives, transitive/shared child definitions, spoofed root
labels, nested generated scopes, unknown profiles and transactionality. Preserve
Boolean/index analog cases and exact source-equation/guarded-profile diagnostics.
Retain public Scala to normalized IR to Verilog-A witnesses and repeated output
identity; distinguish structural reparse, independent compilation and simulation.
Results belong in [F-047 readiness](../implementation/increment47-readiness.md).
