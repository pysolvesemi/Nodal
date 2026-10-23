# Native toolchain tests

These tests validate the immutable LLVM/MLIR/CIRCT lock, release checksums,
source-build plan, managed prebuilt discovery, install manifest, and archive
extraction safety. They use only Python's standard library and do not download
or build the native toolchain.

`test_native_overlay.py` exercises the optional checksum-owned CIRCT repair:
exact revision/patch/source identity, closed paths, supported host/mode,
transactional fresh-prefix installation, idempotence, output/receipt tampering,
and exact-prefix verification without discovery fallback. It mocks the native
build and network boundary; it is not itself an HDL or compiler acceptance run.
See [the repair profile](../../toolchains/patches/circt-inout-parameters-v1/README.md)
for explicit bootstrap and consumer selection.

## Structural consumer overlay

Generated Instance target workflows select the combined
[`circt-structural-consumers-v1`](../../toolchains/patches/circt-structural-consumers-v1/README.md)
profile. It preserves the accepted HW-only profile and exact upstream pin,
adding bounded SSA-order repairs in PrettifyVerilog and PrepareForEmission.
The existing installer builds the complete three-component catalog in a fresh
prefix and validates all output, recipe and receipt identities.

Run `test_native_structural_overlay.py` for the focused closed-manifest controls
and `check_structural_consumers.py --toolchain PREFIX --work OUTPUT` for actual
default-optimized SSA/graph export and unchanged-SV two-state checks. Symbolic
resolved four-state waveform checks remain separate in the domain-hierarchy
runner; the two simulation models are not interchangeable.

The optional `circt-symbolic-consumers-v1` successor adds canonical symbolic Comb
consumers and installs the checked changed header, rebuilding all nine direct
Comb/Datapath/DatapathToComb translation units. Its focused portable checker is
`python tests/toolchain/check_symbolic_consumers.py --toolchain <successor-prefix>
--predecessor <structural-prefix> --work <fresh-work-directory>
--iverilog <iverilog-executable> --vvp <vvp-executable>`. Use jobs 2 and the native
build lock for installation. This is selected local prototype validation, not CI
or whole-toolchain qualification. The prior profile keeps its source/patch
contract; only its builder checksum is updated when the canonical recipe changes.

`check_generate_consumers.py` accepts the same explicit prefix, predecessor, work,
Icarus and vvp arguments and checks named early/late, conditional and nested
hierarchy paths with unchanged generated SV. Graph output stays byte-identical;
other predecessor differences must be closing-label-to-comment substitutions.
