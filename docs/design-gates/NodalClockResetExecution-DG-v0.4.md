# Nodal coherent snapshot handshake execution gate v0.4

**Status:** Approved
**Scope:** public-api

## Meaning and bounded shared representation

This required F57 continuation implements the latest-snapshot handshake already
approved in v0.1. `Cdc.handshake(payload, to): Expr[A]` remains unchanged. It is
not a lossless event queue: public source changes while busy may coalesce. The
destination holds the last coherently delivered snapshot; before first delivery
and during reset recovery its value is unspecified. No public ready/valid signal
or payload initializer is invented.

Use existing single-input/result crossing representation with kind `handshake`,
contract `handshake_snapshot_v1`, stages exactly 2 and closed obligations:

- `delivery = latest_snapshot_coalescing`;
- `bundled_data = settled_before_request_capture`;
- `reset_epoch = common_assertion`.

Admit the same exact finite bit-pattern ABI as Gray. Shared type validation and
checked allocation charge `2 * maximumPayloadBits + 6` physical bits before
allocation under the existing sequential budget. Fixed target support reuses the
whole-value scalar/Vec/Struct packing and exact-width query in v0.3; unresolved
widths/layouts and unsupported operations reject transactionally.

## Actual handshake state and timing

Physical state consists of uninitialized source-held and destination-held payload
registers, one source request toggle, two destination request synchronizer bits,
one destination acknowledgement bit and two source acknowledgement synchronizer
bits. Only the six control bits reset, through actual reset producers.

At a source edge, request equal to returned acknowledgement means idle: capture
the public payload and invert request. Otherwise hold both source snapshot and
request. Public payload changes while busy impose no caller stability duty.

At a destination edge, synchronized request different from acknowledgement means
a pending snapshot: capture the held source payload and update acknowledgement
to synchronized request. Otherwise hold both. Returned acknowledgement therefore
reflects actual capture, not mere arrival at the request synchronizer. Ordinary
old-state/nonblocking semantics apply to simultaneous edges. Repeated identical
payload values may transfer again. Eventual delivery assumes both clocks continue
and reset remains inactive; no fixed wall-clock latency is promised.

The held source payload remains stable through returned acknowledgement by owned
hardware construction. Physical bundled-data settling before request-enabled
destination capture remains a retained environment/timing obligation, not a
claim that digital simulation proves metastability safety.

## Reset epoch and executable checks

Reuse the Pulse rule: nonempty equal actual physical reset-root/element/polarity
sets, with independently proved safe effective release at each endpoint. Factor
this shared proof rather than copy a second reset-lineage walk. Valid synchronous
reset proof is not excluded merely to simplify the target.

On every local clock edge, unless local reset is known asserted, require all
local protocol control bits to be known: request plus both returned-ack stages
at source; both request stages plus acknowledgement at destination. Unknown reset
must not disable these checks. No payload-knownness check applies before first
delivery and no initialized epoch flag is added. This catches unestablished
startup rather than silently remaining unknown forever.

The environment must provide a common assertion observed by both domains with
adequate duration and no unilateral restart. Known-state startup checks are not
a universal detector of repeated one-sided synchronous resets after an earlier
epoch. Preserve that limitation explicitly in typed reports and target comments.

## Shared owners and focused acceptance

ConstructionSession, existing sampled provenance/reconvergence, exact storage
queries, DigitalValueMlir and CrossingModel remain authoritative. Reuse the
existing physical graph and common-reset proof for source/native ingress.
SequentialToCIRCT owns independent hardware state and source-located immediate
checks; both request and acknowledgement synchronizers preserve physical identity
through canonicalization. No additional graph or protocol framework is needed.

Focused checks cover actual source/bridge/native/target delivery and coalescing,
held destination value, both edges, unequal/stopped/resumed/simultaneous clocks,
real startup failures, malformed obligations/ABI/stages and bounds, unequal reset
roots or elements, unsafe release, pulse relabeling, instance identity and
reconvergence. Run directly affected predecessors, not CI or broad qualification.
The asynchronous FIFO and all remaining F57 categories stay required and open;
this gate does not close a checklist item or implement FIFO by sampling payloads.
