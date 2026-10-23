# Selected generated Instance target checks

`Increment58SelectedInstanceTargetSource.scala` is a bounded public-source fixture.
`Increment58SelectedInstanceTargetReplay` captures eleven real construction snapshots
through the Scala-to-MLIR bridge. Compile with the matching selected API, bridge and
constructor plugin; all three identities must be retained with the capture receipt.

`run_selected_instance_target.py` takes `--nodalc`, `--toolchain`, `--inputs`,
`--output` and `--cxx`. The output must be a fresh directory under ignored `.work`.
Use the validated `circt-structural-consumers-v1` managed prefix, including its `lib`
in `LD_LIBRARY_PATH`. No download or tool build is performed by the runner.

Nine positive source profiles cover twelve consumer configurations:
- Two different selected lanes and an independently reconstructed same-lane alias.
- Real generated child state with synchronous reset and low/high/repeated-clock checks.
- Nonzero lower bound and nonunit step: ordinals zero/one route to coordinates two/four.
- Complete two-level prefixes with row-major positions one/five.
- Two families sharing one child definition with distinct constant WIDTH4/WIDTH8 bindings.
- An invariant symbolic parent WIDTH, retained through the actual child override, at 4/8/16.
- Nested multi-field Struct with widths 8/3/2 and a two-element UInt4 Vec leaf.
- Scalar-element Vec payload, retaining its original element width and dimension group.
- Authored parameter guard, both arms, selected values only in the exact true arm.

Every input passes the native default gate before lowering. The target must retain
one helper and real loop per authored family, one child definition rather than one
per lane/binding, and the original child sites. Generated SV is neither rewritten
nor postprocessed. The standard optimized exporter runs with `--verify-each`.
The unchanged DUT is imported with explicit parameter overrides, compiled by Arc,
and checked with exact physical pin names/widths and distinct lane/field stimulus.
The runner records source, target, SV, model, harness and executable hashes.

The two induction-varying source profiles (`WIDTH=i+1`, unit and nonunit families)
must pass the native gate and explicitly refuse the invariant physical carrier
profile. They are not zero/default/max-width substitutions or skipped positives.

`GeneratedOccurrenceCIRCTTest` independently checks original declaration identity,
no body-local output view, unobserved preceding physical slots, repeated aliases,
forged symbol/member/ordinal and selected-input refusal, plus exact 4096-bit and
adjacent over-budget carrier cases. Its earlier body-local and empty-template
contracts remain in the same maintained executable.

This is bounded two-state behavioral evidence, not startup-X acceptance. Selected
Interface transport, induction-dependent slot layouts, selected writes, resolved
arrays, analog and CDC/control extensions remain required and fail closed.
