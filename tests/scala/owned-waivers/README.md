# Owned stateless waiver target witness

This focused runner consumes actual IR emitted by
`nodal.internal.testkit.Increment57WaiverSourceReplay` in
`core/scala/testkit/test/src/nodal/internal/testkit/Increment57WaiverSourceTests.scala`.
It lowers through the production `nodal-sequential-to-circt` pass, exports actual
SystemVerilog through CIRCT, and executes that unmodified design in Icarus.

```sh
python3 tests/scala/owned-waivers/run_target.py \
  --source-dir .work/waiver-source \
  --output-dir .work/waiver-source-target
```

The default selection is scalar `UInt(8)`, `Bool`, whole `Vec(UInt(3), 2)`, nested
waivers, and safely escaped authored text. Each checks 36 payload changes with all
clocks held, including unknown/high-impedance values. These are stateless identity
witnesses, not metastability, coherence, event-delivery or electrical certification.
The runner checks retained typed obligations and rejects invented storage, reset
logic or assertions pretending to certify the exception. `--cases` narrows the
selection; tool paths may be overridden explicitly. Generated files and logs stay
under the caller's ignored `.work/` output directory.

`nodal-waiver-circt-unit-tests OUTPUT_DIRECTORY` also writes independently owned
native scalar/aggregate/Struct fixtures. The same runner accepts those with
`--cases scalar aggregate structure`. Native verifier and hierarchy unit tests
cover malformed ownership/contracts and transactional unsupported hierarchy
lowering independently of source construction.
