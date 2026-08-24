# Increment 16 construction kernel

## Status

This document describes the first implementation increment beneath the frozen Nodal public API v0.3.
It does not change the source API frozen by Increment 15.

## Transaction model

Each `Nodal.emit` or private test inspection allocates one construction transaction. The transaction is
bound through `java.lang.ScopedValue`; Nodal does not expose Scala implicits, givens, thread-local
variables, or mutable global construction state. Parallel calls therefore receive independent module,
domain, expression, and topology stores.

A transaction is published only after successful construction close. An exception discards the local
transaction, so a failed elaboration cannot contaminate a later call.

## Ownership and identity

Temporary identity maps locate live Scala objects during one construction transaction; their keys never become stable design identity.

A `Module` enters construction from its base constructor. Declarations are owned by the active module
and receive deterministic local ordinals. A child module enters a nested construction frame and must be
attached immediately by `instance(new Child)`. The instance closes the child frame and records the
parent/child relationship.

Generated identities use only:

- module class names;
- parent hierarchy;
- local instance ordinals;
- local declaration or expression ordinals;
- explicit endpoint and domain names.

The transaction uses object identity only as a temporary lookup key while Scala objects are alive. JVM
identity values, hash codes, reflection order, and allocation addresses are never emitted or used in a
stable identifier. Increment 17 will replace normal ordinal declaration names with captured semantic
source names while retaining these ordinals as deterministic fallbacks.

## Clock and reset domains

`ClockDomain.apply` pushes one lexical domain for the duration of its body. State created in the body
captures that domain immediately. State outside a lexical block may use a default only when exactly one
effective domain is visible in the module.

Reusable child modules may declare `ClockDomain.required`. A single child requirement may be resolved
by, in priority order:

1. a named selector binding;
2. an explicit default instance binding;
3. the lexical parent domain at the instance site;
4. the parent's sole effective domain.

Multiple requirements require named bindings. An unresolved required domain at the root is always an
error; Nodal does not invent top-level clocks or resets.

## Shape and storage intent

Construction records finite-width type descriptors, `Struct` fields, and all `Vec` dimensions without
flattening them. `Mem` is recorded as a separate addressable-storage declaration with depth, latency,
read-under-write, ordering, and domain attributes. Therefore structural `Vec` intent cannot silently
become memory intent during construction.

## Interface construction close

Every exported `InterfacePort` or `InterfaceArray` is closed transactionally. The kernel verifies:

- unique interface-member names;
- exactly one role-access entry per top-level member;
- no unknown or duplicate role entries;
- access compatibility with value, protocol, nested, digital-resolved, conservative, and signal-flow
  members;
- a resolved endpoint domain.

The kernel recursively expands nested logical members and expands `Valid`/`Stream` channels into stable
logical ABI leaves. A protocol payload remains one typed ABI leaf: `Struct` fields stay preserved in its
type descriptor and are not flattened during construction. This report remains backend-neutral and does
not choose a Verilog flattening policy beyond the deterministic placeholder emitted names required by
the frozen report shape.

## Digital inout and conservative topology

Digital inout declarations retain data type, drive mode, placement, and resolution profile. Drive,
release, open-drain/open-source, and hierarchy pass-through operations are registered against the
endpoint. Electrical resolution and target capability checks remain later work.

Conservative terminals and internal analog nodes remain distinct declarations. Connections are stored
as topology edges owned by the construction transaction. Discipline, branch, island, and contribution
verification remain later semantic passes.

## Public result

`Nodal.emit` still emits no HDL files in Increment 16. It now returns a deterministic `DesignReport` with:

- digital, analog, mixed-signal, or unsupported construction classification;
- the requested backend selection unchanged;
- the selected digital profile when applicable;
- the logical Interface ABI.

Source maps and pipeline schedules remain empty until their dedicated increments.

## Deliberate boundaries

Increment 16 does not implement source spans, semantic source-name capture, scheduling, MLIR, HDL
lowering, resolved-net resolution, analog topology verification, simulation, synthesis, formal
execution, or timing closure. The frozen API remains authoritative; implementation-specific classes
and diagnostics are package-private.
