# OpenVAF compile validation v0.1

**Status:** Approved
**Scope:** core-library-boundary
**Date:** 2026-10-18
**Owner:** Foundation 48

## Authority and compatibility

The standing owner request covers every unchecked Foundation increment from 43,
public commits/pushes, and completed-increment integration with `[skip ci]`.
This gate implements the existing F-048 compiler integration scope. It does not
change public construction APIs, define Nodal semantics by a simulator's subset,
or perform the F-049/F-052 numerical execution tasks.

## Shared owners and accepted design

`core.integrations` owns tool-neutral subprocess execution and the OpenVAF adapter.
Extract the existing native bridge process mechanics into that shared layer; keep
MLIR staging, bridge protocol environment, diagnostic translation and cleanup in
the bridge. Explicit build/module dependency edges follow this direction. The CLI
may consume integrations and the bridge; integrations never depends on the bridge.
The versioned F-047 catalog gains separate pinned consumer profiles and a generated
typed Scala view, without changing its native language admission table.

A request names an absolute executable and evidence directory, exact UTF-8 model
bytes and logical filename, selected consumer profile and positive bounded
version/compile timeouts. Initial profiles pin the existing Linux x86-64 OpenVAF
23.5.0 and OpenVAF-reloaded 20260616 binaries by exact executable hash and complete
version response. Fixed generic-CPU target/interface/options avoid host-native
code-generation assumptions. Only standard bundled analog includes are accepted;
arbitrary user include/define configuration is outside this finite first profile.
No batch cache, persistent result cache, model rewrite, leaf extraction or implicit
hierarchy flattening is permitted.

The process owner passes an argument vector without shell interpolation, controls
inherited environment explicitly, bounds total capture and termination/drain,
handles descendant-held pipes, and preserves interruption. Capture retains bounded
raw bytes separately from decoded diagnostic text; malformed UTF-8 cannot silently
change retained logs. Settled reader errors, overflow and cancellation are checked
before success is committed. Truncated capture is never successful evidence. Version probing and compilation have separate deadlines.
The bridge retains its diagnostic code families, validation and normalization.
A direct launch failure remains typed as launch failure. After a Linux controller
has acknowledged startup, shell `exec` failures are ordinary 126/127 exits and map
to the existing process-exit diagnostic; stderr text is not used to invent a failed
launch. A controller handshake expiry is a typed launch failure.

Pinned OpenVAF requests require Linux process-group containment. A fixed launcher
program receives separate positional arguments, records a fresh session-leader PID
and uses bounded group cleanup; caller text is never interpolated into that program.
Both logical tool argv and executed wrapper argv are retained. This covers ordinary
fork/exit children, not a hostile program deliberately escaping its session or
changing credentials. Platform-default bridge execution uses that containment when
available; its existing non-Linux descendant-observation fallback remains explicit
and cannot claim detection of children reparented before observation.

## Results and consumer boundaries

Each compilation retains exact model bytes, invocation, raw stdout/stderr, version,
executable/model/profile identity, exit status and structured outcome in a fresh
attempt directory. OSDI is published as successful only after exit zero and a fresh
regular nonempty output with the finite format check. This is not a model-load or
numerical-validity assertion. Failed/stale output is never returned as success.

Keep unavailable tool, profile mismatch, launch failure, timeout, interruption,
output-limit failure, compiler error/crash and invalid output distinguishable.
Do not label every exit 65 malformed input or unsupported language: the legal
F-047 hierarchy witness has that exit too. Malformed controls are identified as
such by their test construction and preserved compiler diagnostics. Exact historical
witness observations annotate evidence separately; different arguments or models
must not silently inherit an expectation. A crash is never downgraded to an expected
rejection. Unknown consumer configurations fail closed; missing feature observations
remain untested.

Request identity includes model, executable/version, catalog/profile contents,
ordered arguments, controlled environment and platform. The first implementation
performs no compile-result reuse. This deliberate choice avoids an incomplete cache
key while leaving a typed artifact service for F-049/F-050.

## Finite target-source provenance

An opt-in private Boolean attribute `nodal.backend.source_provenance` adds safe
standalone comments; absent/false preserves existing target bytes. Non-Boolean
configuration rejects. Backend owns each marker immediately before a single emitted
parameter declaration, named instance statement or contribution statement. This is
statement-level mapping only; other sites remain target-only.

The exact versioned encoding is:

```text
// nodal-source-v1: semanticBase64|pathBase64|startLine|startColumn|endLine|endColumn
```

Base64 is the standard alphabet over UTF-8, not a raw path/comment escape. Resolve
actual operation semantic identity through `nodal.bridge.source_map` at the shared
DiagnosticSupport owner. Do not use a temporary parsed `input.mlir` location or
infer source from a target identifier. Missing source data produces no invented
mapping; malformed/ambiguous spans reject opted-in generation. Existing diagnostic
behavior outside this new mapping API remains compatible.

The adapter validates encoding, version, positive ordered spans and unique mapping
for the immediately following single physical statement line. It associates a
compiler location only with that exact generated line in the exact model bytes.
No preceding marker is extended across unrelated lines; subexpression precision
is not claimed. Invalid provenance is reported separately without erasing original
compiler outcome or logs. Paths with spaces/Unicode remain data, not commands.

## Alternatives and verification

Reject a script-only second process implementation, simulator-specific source
semantics, arbitrary inferred feature support, unbounded stream joins and a general
name-based source-map heuristic. Reuse the existing source inventory, backend
rendering sites, integration module and generated catalog instead.

Focused checks cover subprocess recovery/argv/timeout/descendant pipes/capture,
profile and version mismatch, missing executables, real compiled witnesses,
intentional malformed controls, crashes, unsupported hierarchy/arrays, fresh/stale
OSDI, source-map positives/negatives, deterministic identities and existing bridge
regressions. Whole-model hierarchy cases are retained even when the consumer rejects
them. Results belong in [F-048 readiness](../implementation/increment48-readiness.md).
No broad qualification, CI or numerical simulator campaign is required.

## Focused implementation review

The final process repair preserves exact byte capture and checks settled reader
state before committing success. On Linux the session controller waits at most
20 ten-millisecond acknowledgment intervals before exiting; an expired caller
cannot acknowledge or execute a late controller. Failure cleanup has a separate
bounded 250 ms grace period, and final receipts retain actual wrapper arguments.
The focused immediate-orphan and inherited-pipe cases terminated without runnable
survivors or unsettled readers; these are finite local checks, not an adversarial
sandbox guarantee. Non-Linux fallback and deliberate session-escape limits above
remain explicit.

Native provenance and runner architecture inspections found no blocking defect
in their final scoped changes. Adapter tests keep process outcomes, source-map
validity and consumer-language eligibility separate. Original target bytes are
never normalized for compilation; diagnostic display decoding is separate from
raw retained logs. No persistent cache or optimization claim is introduced.
