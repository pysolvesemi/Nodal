# Elastic asynchronous FIFO target witnesses

The public Scala FIFO replay supplies actual bridge IR to `run_target.py`; no
replacement test design is accepted. The harness takes `--input`, `--out`,
`--nodalc`, `--firtool`, `--iverilog` and `--vvp`. Generated target files, per-command
logs and hashes stay under ignored `.work`.

A scoreboard records only accepted source valid-and-ready transfers and compares
every accepted destination transfer in order. Directed tests fill the exact
requested capacity, stall both endpoints, drain, exercise pointer return delays,
wrap repeatedly and reset with queued entries. Invalid valid/ready combinations
must not create transactions. Fixed Vec/Struct payloads retain complete packed
ABI annotations after SystemVerilog export.

Separate negative simulations require source payload changes or valid withdrawal
during a stall to fail nonzero with the expected emitted FIFO assertion. Both
local startup checks cover missing and unknown reset. Eight control mutations
force each real pointer/synchronizer vector unknown after reset and require its
local checker to fail. Testbench fallback failures and unrelated compile errors
do not count as successful assertion execution.

Physical Gray-pointer skew, bank-data settling and common reset assertion duration
remain explicit environment/timing obligations. Knownness checks do not detect
every unilateral restart. This is focused local prototype validation, not CI or
exhaustive physical CDC qualification.

All 14 actual source profiles are consumed. Additional bounded stimuli in
`special_profiles.py` exercise the shared physical clock under Sync reset,
unattached default-false destination ready, continuously valid `Stream(payload)`
and two FIFOs across three clock domains. The chain proves exact combined
capacity eight before repeated ordered backpressured delivery and a common
flush. Enum traffic uses only its declared nonzero encodings. Separate forced
bank/visibility mutations execute both destination stall failures.

The firtool export uses `--lowering-options=disallowLocalVariables` because Icarus
does not support automatic block-local variables. This is a supported exporter
option; the emitted hardware and assertions are not rewritten for simulation.
Negative cases must contain the specific expected endpoint/obligation diagnostic.
