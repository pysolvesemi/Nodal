# Coherent snapshot handshake target witnesses

`Increment57HandshakeReplay` captures real public Scala construction into bridge
IR. `run_target.py` accepts `--input`, `--out`, `--nodalc`, `--firtool`,
`--iverilog` and `--vvp`, then lowers and simulates those files. All generated
artifacts, command logs and hashes remain under ignored `.work`.

The handshake is an automatic latest-snapshot transfer, not a lossless event
queue. Directed source changes while busy must coalesce, while the actual held
snapshot reaches the destination intact. The target holds its last delivered
value until the next capture. Tests distinguish request synchronization, actual
destination capture and returned acknowledgement using stopped clocks and exact
edge sequences, then exercise simultaneous edges and coordinated reset recovery.
Payload startup stays unspecified; no zero initializer is allowed merely for
simulation convenience. Both edges/polarities and fixed scalar/whole Vec/Struct
payloads retain the same protocol meaning and exact packed ABI.

Separate negative runs require actual source-side and destination-side startup
assertions to fail nonzero with the emitted Nodal handshake marker. Six focused
mutation runs also force each actual protocol control bit unknown after a valid
reset epoch and require its local checker to fail. Unknown reset
cannot vacuously suppress these checks. Testbench fallback failure does not count.
These checks do not prove all unilateral reset epochs are detected: common
assertion duration and physical bundled-data settling remain explicit retained
obligations. This is focused local prototype validation, not CI or physical CDC
qualification.
