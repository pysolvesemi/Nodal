# Nodal analog hierarchy construction design gate v0.1

**Increment:** 42
**Status:** Approved
**Scope:** public-api

## Exact contract

Foundation F-042.B.1 uses the existing scalar public construction surface first:
`instance(new Child)`, typed child selectors through `Instance.apply`, the existing
`Instance.param(select, value)` override form, `connect(...)`, and ordinary finite
Scala replication such as `Vector.tabulate`. This gate does not add a new public
`.port` or `.instances` convenience method.

A child-port binding is accepted only when the selected declaration is an allowed
public port of that exact immediate child instance. Selector syntax alone does not
prove ownership: internal declarations and declarations belonging to another child
must be rejected by the real construction transaction.

`Instance.param` retains its existing typed selector while the construction path
must enforce one override per exact child parameter, parent ownership of symbolic
references, analysis-static expression structure, exact inferred data type, and
physical-dimension equality for `Real` values. Literal overrides remain legal.
Parent-owned operands do not make a stateful operation such as `transition(...)`
analysis-static.

Increment 42 fixed replication is elaboration-time repetition of ordinary scalar
instances with deterministic element/source identity. Symbolic or shaped analog
arrays, target generation, empty/symbolic shapes, and generated-instance lexical
storage remain owned by Increment 43.

The first implementation checkpoint may introduce a private pure policy helper
and isolated tests before the construction transaction calls it. Such a helper is
partial evidence only: F-042.B.1 remains open until the actual public path derives
facts from exact declaration/instance identities, invokes the policy, and passes
integrated positive/negative qualification.

## Accepted alternatives

- Existing typed selectors may identify child declarations; a new named-port API
  is optional if the construction transaction independently proves child/port
  ownership.
- Fixed Scala loops or `Vector.tabulate` may express the supported fixed instance
  replication profile when scalar identities and deterministic source/index paths
  are preserved.
- Parent parameters may feed child overrides directly or through reviewed pure
  static expression DAGs; literal-only overrides remain valid.

## Rejected alternatives

- String names, display paths, reflection, global registries, or selector syntax
  alone may not establish ownership.
- Internal or foreign child declarations are not accepted named ports.
- Child/sibling-owned values, signals, variables, state, waveform/event/analysis
  operations, unknown operations, duplicate overrides, type changes, or physical
  unit changes are rejected rather than folded to defaults.
- A disconnected helper, source-only prototype, or the historical unpublished V2
  draft does not complete B.1.
- Broader symbolic/shaped generation is not pulled forward from Increment 43.

## Compatibility impact

No new public method is introduced by this gate. Existing scalar construction and
supported predecessor `Instance.param` forms remain source-compatible. The later
integration intentionally rejects previously unchecked invalid ownership,
staticness, duplicate, type, and unit cases. Bridge/native encoding and
hierarchical Verilog-A emission remain separately owned by F-042.B.2/B.3.

## Required tests

- isolated policy cases for duplicate, owner, dynamic/stateful, type, and unit
  acceptance/rejection, including `transition(parentBias)` rejection;
- integrated public construction using existing selectors and fixed scalar
  replication with stable instance/source identity;
- existing UInt literal and analog parent-parameter override regressions;
- foreign/internal port and child/sibling ownership negatives;
- affected targeted CI on the exact integrated candidate before B.1 completion.

## Approval evidence

Approved by the project owner's September 24, 2026 instruction to apply the live
readiness policy to the existing Increment 42 branch and continue under the
revised checklist assessment. This gate freezes only the scoped construction
contract above; it is not implementation, test, roadmap-completion, merge, or
accepted-evidence credit.
