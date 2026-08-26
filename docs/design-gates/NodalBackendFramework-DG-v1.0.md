# Nodal backend framework and capability profiles design gate v1.0

**Revision:** v1.0
**Status:** Approved
**Scope:** compiler-backend-framework
**Public API:** unchanged at 0.3
**Approved authority:** standing Nodal increment implementation and merge authorization

## Decision

Increment 23 introduces the native backend seam without claiming analog-language
lowering. MLIR remains authoritative. A backend is selected explicitly through a
registered translation, resolves one immutable capability profile, runs the
mandatory semantic gate on a clone, renders into a private buffer, verifies and
structurally reparses that candidate, and publishes bytes only after every step
accepts the transaction.

The built-in translation identities are:

- `nodal-to-verilog-a`, profile `verilog-a`;
- `nodal-to-verilog-ams`, profile `verilog-ams`.

Installing or registering a translation never changes `Backend.Auto`. Backend
selection and future target-HDL optimization remain separate contracts.

## Profile ownership

A capability profile owns target-visible layout and presentation policy:

| Profile | Accepted design kind | Shaped layout | Materialization | Naming |
| --- | --- | --- | --- | --- |
| `verilog-a` | analog | `scalar-or-flat` | `safe-inline` | `semantic` |
| `verilog-ams` | analog, mixed-signal | `flat-packed` | `readable` | `semantic` |

Source modules may select `fast`, `default`, or `release` through
`nodal.backend.check_profile`. They may repeat profile-owned settings only when
the value exactly matches the selected profile. A conflicting layout,
materialization, naming, profile, or design-kind request is rejected rather than
silently rewritten.

## Transactional output contract

Emission follows this binding order:

1. clone the accepted Nodal module;
2. resolve the selected profile and `CheckProfile`;
3. run the Increment 21 clone-before-commit semantic gate;
4. reject operations outside the profile's implemented capability;
5. sort module definitions by semantic symbol name;
6. render into a private in-memory candidate;
7. run the target verification hook;
8. run the target structural reparse hook;
9. append the complete candidate to the caller's stream.

No failure may publish a prefix, stale output, or partially accepted target.
The initial built-in hooks verify deterministic framework structure and reparse
the emitted module skeleton. Later profiles may replace the implementation
behind the same hook contract with standards parsers or simulator adapters, but
cannot bypass the transaction.

## Unsupported-feature boundary

Increment 23 emits only deterministic empty module shells plus profile metadata
and standard include directives. Every Nodal operation below `nodal.module` is
an explicit unsupported feature until its lowering increment lands. This is
intentional: Increment 24 adds minimal analog expression/contribution IR and
Increment 25 adds the first complete RC lowering.

Stable errors cover unknown check profiles, conflicts with profile-owned
settings, profile/design-kind mismatch, translation/profile mismatch,
unsupported operations, invalid target identifiers, target verification
failure, and target reparse failure.

## Verification obligations

Native unit tests prove byte-identical repeated output, semantic-name ordering,
profile ownership, configurable `CheckProfile`, no publication after capability
failure, and no publication after reparse failure. CLI fixtures prove both
translation registrations, exact goldens, profile mismatch, unsupported-feature
diagnostics, read-only CI, and unchanged public API v0.3.

## Explicitly deferred

- analog expressions, contributions, equations, and executable HDL bodies;
- complete Verilog-A or Verilog-AMS syntax/semantic reparse;
- digital `Backend.Verilog` and deterministic `Backend.Auto` selection;
- target optimization passes and plugin-provided backends;
- simulator-specific capabilities, compilation, or remediation.
