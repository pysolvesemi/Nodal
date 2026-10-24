# Bounded symbolic consumer repair

This optional managed profile retains `circt-structural-consumers-v1` and extends
its canonical CIRCT repair at commit
`87898a876f730a2ebc607dc9b83da487cba49119`. It rebuilds complete HW, Comb, Datapath,
DatapathToComb, SVTransforms and ExportVerilog components from checksum-closed
upstream CMake inventories. Comb's symbolic width inference, folding, analyses and
shared matcher respect HW parameter-expression identity rather than defaults.
GenerateOp keeps its named opening and hierarchy but emits its closing name as
a comment, matching existing GenerateFor style for consumer compatibility.
All actual direct consumers of changed `CombOps.h` are rebuilt; the installed
header is independently checked against its manifest after-hash.

The existing manager remains the sole fetch/build/validation owner, with two
closed component catalogs and no manifest-supplied commands. Library and header
identity is checked in the normal installer/state/CMake path. The profile is
optional, fresh-prefix-only and transactional; it does not change global paths.
The predecessor's source inventory, patch, ID and semantics are unchanged; its
builder checksum alone changes to the current canonical recipe. Historical
manifests and receipts remain historical and are not relabelled as current.

Focused checks use `tests/toolchain/test_native_symbolic_overlay.py`, the unchanged
predecessor tests, `check_structural_consumers.py`, and
`check_symbolic_consumers.py`, and `check_generate_consumers.py`. The latter takes explicit `--toolchain`,
`--predecessor`, `--work`, `--iverilog` and `--vvp` paths and verifies actual linked
libraries. It checks the native API, custom/generic parsing, verifier refusals,
static output parity, and widths 1/3/8 through Slang/Arc two-state simulation and
Icarus four-state controls. It does not qualify arbitrary symbolic Comb arithmetic
or admit dependent target slots by itself.
