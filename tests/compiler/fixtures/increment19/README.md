# Increment 19 compiler fixtures

Increment 19 validates the first hardware-semantic, target-neutral `nodal` MLIR schema while preserving public Scala API v0.3.

The canonical positive fixture, `core/compiler/test/IR/core-model.mlir`, exercises finite-width and shaped values, logical Interfaces and protocol identity, module hierarchy, symbolic parameters, domains and crossings, structural generation, bounded hardware iteration, resolved digital nets, conservative connectivity, explicit bridges, semantic enums, FSM/statechart structure, state ownership, and timing provenance.

The negative fixtures deliberately prove four local structural boundaries:

- zero-width finite values are rejected;
- resolved-net driver and value types must match the net element type;
- canonical enum ABI values must be unique and fit their declared width;
- each FSM requires exactly one initial state.

Whole-design symbol resolution, driver coverage, CDC/RDC analysis, scheduling, analog equations, CIRCT conversion, and HDL lowering remain assigned to Increment 21 and later roadmap items.
