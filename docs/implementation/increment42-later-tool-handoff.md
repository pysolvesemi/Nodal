# Increment 42 later-tool qualification handoff

**Date:** 2026-09-27

**Source compiler checkpoint:** `7ab7ec09f70e7928118857231da6f8a8c0c99fe4`

**Scope:** F-042.D.2 case selection only; no OpenVAF or numerical execution is claimed here.

## Purpose and boundary

This record maps the completed Increment 42 compiler witnesses into the later
Increment 48 OpenVAF, Increment 49 ngspice and Increment 52 analog-regression
owners. It completes the F-042.D.2 identification obligation without making
those later parents reverse prerequisites for compiler-only Increment 42
acceptance.

Strict target reparse in Increment 42 verifies Nodal's emitted named-instance
grammar and cross-definition bindings. It is not independent OpenVAF
compilation. A successful OpenVAF compile is not a numerical result. A
successful ngspice model load is not a behavioral comparison. The later owners
must retain those three result classes separately.

The authoritative public source is
`examples/continuousTimeApi/src/nodal/increment42fixture/Increment42PredecessorCombinations.scala`.
The configured production path is the test named
`public Scala hierarchy compiles to reusable named Verilog-A instances when configured`
in `ScalaToMlirBridgeTests.scala`. Exact-head Core run
[36332604210](https://github.com/pysolvesemi/Nodal/actions/runs/36332604210)
and Increment 37 run
[36334138396](https://github.com/pysolvesemi/Nodal/actions/runs/36334138396)
prove generation, native verification, strict internal reparse and predecessor
compatibility on the source checkpoint above. Later execution must regenerate
from that source or a reviewed descendant and retain the new target bytes; it
must not substitute a hand-written model.

## Stable handoff cases

| Case ID | Public top | Compiler property carried forward | Later owner |
|---|---|---|---|
| H42-EQUATION-DC | `HierarchyEquationTop` | Named child, symbolic `.gain(rootGain)`, parent-owned equation | 48 compile/load; 49 and 52 DC |
| H42-EVENT-TRAN | `HierarchyEventTop` | Named child, symbolic override, `initial_step`, `transition` | 48 capability/compile; capability-gated 49 and 52 transient |
| H42-FUNCTION-DC | `HierarchyFunctionTop` | Named child, symbolic override, module-local pure analog function | 48 compile/load; 49 and 52 DC sweep |
| H42-REPEATED-LOAD | `HierarchyRepeatedTop` | Four instances, one reusable definition, symbolic and literal actuals 3 and 5 | 48 compile; 49 load/sweep smoke |
| H42-NESTED-LOAD | `HierarchyNestedTop` | Three emitted levels, two reusable branches, symbolic and literal actual 6 | 48 compile; 49 load smoke; 52 hierarchy composition |
| H42-ROOT-ACTUAL-NEG | `HierarchyEquationTop` or the configured baseline constructed with a non-default root actual | Frontend/backend rejects `NODAL-BACKEND-HIERARCHY-010`; no target is published | Remains an Increment 42 compiler negative; it is not an OpenVAF malformed-model case |

`HierarchyCombinationLeaf` and `HierarchyNestedBranch` deliberately contain
only parameter and terminal structure. Therefore H42-REPEATED-LOAD and
H42-NESTED-LOAD qualify compile, module-load, identity, binding and sweep
plumbing only. They do not by themselves support a numerical transfer-function
claim. Increment 52 must add a behavior-bearing hierarchy counterpart, derived
through the same public constructor/bridge path, before claiming numerical
parameter propagation through repeated or nested children.

## Increment 48 compile contract

For each positive H42 case, Increment 48 must:

1. regenerate the Verilog-A from public Scala through the production bridge;
2. retain the Scala source commit, normalized-MLIR SHA256, Verilog-A SHA256,
   OpenVAF executable/version/build identity, complete command line,
   stdout/stderr, exit status and produced OSDI SHA256;
3. compile the complete generated module graph, not an extracted leaf or a
   hand-edited target;
4. verify that the selected top and every referenced reusable definition are
   present in the compiled input; and
5. classify unsupported syntax or tool capability as a retained blocked result,
   never as pass or N/A.

H42-EQUATION-DC, H42-FUNCTION-DC, H42-REPEATED-LOAD and H42-NESTED-LOAD are
unconditional selected compile cases. H42-EVENT-TRAN is selected but may succeed
only under a pinned OpenVAF profile that advertises the emitted
`initial_step`/`transition` subset; otherwise Increment 48 must retain an
unsupported-profile result that blocks the corresponding event execution.
H42-ROOT-ACTUAL-NEG ends before target publication and must not be replaced by a
fabricated malformed Verilog-A file.

Repeated compilation must use identical source/model/tool/profile inputs and
produce the same success/failure classification and OSDI digest. Cache reuse
must be invalidated by any model, tool or capability-profile change.

## Increment 49/52 numerical contracts

Use the comparison rule:

`abs(measured - reference) <= A + R * abs(reference)`

Here `A` is the per-metric absolute tolerance and `R` is the relative
tolerance. Retain raw samples, units, bench text, OSDI/model hashes, solver
options, accepted/rejected timestep history where available, and the
independently computed reference values. Tolerances are fixed by this handoff
and must not be loosened after seeing results; a justified future change requires
a reviewed successor record.

| Case ID | Bench and analysis | Independent reference | Required tolerance |
|---|---|---|---|
| H42-EQUATION-DC | Ground `vout`; force `V(vin,vout)` with a DC voltage source at 0, 0.5 V and 1 V; define model current as `-I(Vdrive)` | `I(vin,vout) = V(vin,vout) / R` with `resistance = 1 kOhm`: 0, 0.5 mA and 1 mA | driven voltage: `A = 1 uV`, `R = 1e-6`; model current: `A = 1 pA`, `R = 1e-6` |
| H42-FUNCTION-DC | Add a 1 MOhm shunt for a defined operating point; DC runs with external top parameter `rootGain` = 2, 4 and 6 | `V(vin,vout) = rootGain * 1 V`: 2 V, 4 V and 6 V | voltage: `A = 1 uV`, `R = 1e-6` |
| H42-EVENT-TRAN | Add a 1 MOhm shunt; transient 0 to 2 ns with maximum step no larger than 10 ps | source semantics give 0 V before the initial-step update and a completed 1 V plateau after the 1 ns transition | endpoint voltage: `A = 1 uV`, `R = 1e-6`; final plateau by `1 ns + maxstep`; every sample in `[-1 uV, 1 V + 1 uV]` |
| H42-REPEATED-LOAD | Load the complete four-instance model and sweep the externally visible root parameter over 2, 4 and 6 | structural/load case only; exact instance/actual identities come from retained target and compile manifests | no numerical behavior claim; load and sweep setup must complete without altering model identity |
| H42-NESTED-LOAD | Load the complete three-level model at default parameters and once with external root value 6 | structural/load case only; exact nested identities come from retained target and compile manifests | no numerical behavior claim; successful load is reported separately from simulation |

For H42-EVENT-TRAN, the later owner must first demonstrate that its pinned
OpenVAF/ngspice capability profile implements the emitted event and transition
forms. If not, the case remains blocked and no event numerical claim is made.
The endpoint reference does not assert a tool-specific interpolation polynomial.

For H42-EQUATION-DC, place the voltage source's positive terminal at `vin`
and negative terminal at grounded `vout`. SPICE reports source current into
the positive terminal, so the model's `I(vin, vout)` is `-I(Vdrive)` by KCL.
Compare that signed value, never its magnitude. Other DC measurements must retain
the public `V(vin, vout)` orientation. Each numerical case must run at least
twice with the same model, bench and solver options; raw waveform/result digests
and pass/fail classification must repeat.

## Increment 52 behavior-bearing hierarchy addition

Before Increment 52 claims numerical hierarchy or parameter propagation, add one
public companion fixture whose leaf contributes a dimensionally valid scalar
relation using its constructor parameter. Exercise one symbolic parent-to-child
actual and two distinct literal actuals in repeated or nested instances. The
reference must be derived independently from the declared relation and bench,
use the same voltage/current tolerances above unless a stricter reviewed case
contract is supplied, and retain both flattened measurement identities and
source hierarchy paths.

This later companion may reuse the canonical Increment 42 construction,
instance, terminal, override and backend contracts. It must not change the
accepted Increment 42 compiler semantics, silently specialize a module per
actual value, or relabel the structure-only H42-REPEATED-LOAD/H42-NESTED-LOAD
cases as numerical evidence.

## Completion effect

This mapping completes F-042.D.2 only. It records selected cases, analyses,
references, units, tolerances, capability gates and negative boundaries. It does
not complete any F-048, F-049 or F-052 checkbox and does not claim OpenVAF,
OSDI, ngspice or numerical execution. Increment 42 subsequently completed
F-042.G, full applicable CI, review, verified integration, merge and separate
accepted-evidence closure without changing these later-owner obligations.
