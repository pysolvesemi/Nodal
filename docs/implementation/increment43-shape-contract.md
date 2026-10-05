# Increment 43 fixed and direct-symbolic shape contract

**Status:** bounded implementation checkpoint; no F-043 child is complete.

This checkpoint turns `Vec` declaration dimensions from inert spelling into a
checked construction, bridge and native contract for fixed positive dimensions
and directly referenced bounded `Integer` parameters. A symbolic dimension is
owned by the declaring module, has a finite range whose lower endpoint is
strictly positive, and marks its canonical parameter with `shape` and `rank`
structural effects. The bridge retains the parameter name in
`!nodal.shaped<...>` and rejects forged missing, ordinary, unbounded or
zero-capable dimensions. Native module verification independently resolves the
symbol, its structural classification, shape envelope and positive finite range.

Zero-sized shapes remain illegal even though an empty `hdlRange` remains legal.
`Vec` remains structural data rather than implicit memory. This checkpoint does
not accept compound dimension DAGs, implement index/slice/view transport, allow
generated lexical storage, or lower arrays/generation to Verilog-A. Those
capabilities remain open and must not be inferred from shaped port IR parsing.

Public tests cover deterministic fixed and symbolic snapshots, bridge output,
structural effect capture, configured native parsing when `NODAL_NODALC` is
available, and transactional rejection of zero, unbounded, zero-capable and
compound dimensions. The native matrix separately covers fixed and symbolic
positives plus forged missing parameters, ordinary classification, missing
shape envelopes and absent/zero-capable ranges.
