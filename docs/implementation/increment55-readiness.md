# Increment 55 readiness and implementation

Status: in progress; every F-055 child and parent remains open. Base
`ec307cef5e11e2a852131fb8e16aa93195f397e4` is the verified F54 documentation closure.
Branch: `increment/55-digital-expressions-iteration`.

## Original obligations and owners

The original F55 checklist requires typed combinational expressions, exact width/
sign/shape rules, bounded staged iteration, collection operations, continuous
assignments, cycle/driver/effect checks and deterministic materialization/lowering
choices. ADRs 0016/0017/0018 and the approved unified `hdlRange` amendment remain
binding. Explicit `loop(LoopBound)` is procedural-only; common `hdlRange` staging
is selected by actual effects, not a new family of required public range names.

Linked shaped-value BT-01/02/03 obligations include ordered adjacent-pair balanced
reduction, odd carry, singleton bypass, symbolic sizes/widths, growing intermediate
types and whole aggregate callbacks. They do not redefine left reduce/fold order.
F58 shares structural integration; F65/66/67 own later target emission/execution/
proof consumers. No full later parent is a reverse prerequisite.

## Shared architecture and first implementation

Reuse F54 DigitalValueConstruction, DigitalValueModel, storage/assignment and the
single bridge value schedule. Existing shape and ParameterModel owners retain
identity, checked arithmetic and parameter substitution. A canonical static maximum
is required by the approved lossless width formulas; do not approximate it with
sum widths or evaluate a parameter default. Source/native scalar implementation
starts at the additive [F55 gate](../design-gates/NodalDigitalExpressions-DG-v0.1.md).

Existing source `map`, `reduce`, `loop`, arithmetic and materialization candidates
still contain inert calls. The frozen `zip(other): Expr[Vec[A]]` cannot retain B;
its implementation needs an explicit compatible migration plus a genuine typed
combiner/pair representation. LoopIndex requires actual scoped induction access.
No placeholder, forged zero induction or discarded pair field satisfies F55.

## Pending decisions and focused validation

Division/remainder need an explicit dynamic-zero policy; no reviewed historical
contract supplies one. Runtime shifts need full all-legal amount bounds. Symbolic
width-changing reductions require genuine type recurrence, not a fixed-width
accumulator with hidden truncation. Collection, loop and materialization contracts
will be specified before their implementation depends on them.

Run focused positive/negative/boundary checks and directly affected regressions;
retain actual Scala→IR artifacts and concise architecture/scale review. No CI,
hourly monitor, full qualification or unsupported target-execution claims.

## Focused implementation progress

The shared static integer owner now carries `max` through source capture, interval
analysis, bridge constant expressions and native parameter/override identity.
The focused native parameter-model executable passes, including its predecessor
cases and new maximum bounds, type/arity/owner/unit, overflow and override checks.
Rendering is preflight-bounded by expanded bytes, graph depth and unique nodes:
an eight-level shared maximum produces exactly 305,831 bytes; forty-level shared
maximum/add graphs, 513 unary levels and cycles reject before text expansion.
This is a finite checked rendering profile, not an unbounded scaling claim.
Raw logs are retained under `.work/increment55/static-max-render-*.log`.

The first digital scalar checkpoint passes 36 focused source cases (F55 operators,
F54 conversions/enums and static maximum), 21 bridge/predecessor cases, and native
DigitalValue 203/203, DigitalType 81/81, DigitalStorage 66/66, EnumValue 68/68 and
the parameter-model executable. Nine preserved actual Scala-generated source and
normalized artifacts replay with the final native binary and retain byte-identical,
idempotent normalized output. Commands and raw logs are under `.work/increment55/`.

Equivalent symbolic widths use canonical ordered static DAG identity across source,
select, enum-select and assignment. Maximum idempotence applies only to the same
actual expression identity; distinct parameters with coincident bounds remain
unequal. No parameter default is substituted. Shared width/type queries retain
nominal aggregate and enum identity. A focused review caught enum width admission
into scalar operators; the repaired scalar guard rejects enum arithmetic and
conversions while shared enum validation preserves legal constants and enum
expressions as selection data. Sparse-code negative and select-positive tests
exercise that boundary.

Formatting, focused new-file lint, capability-registry generation and diff checks
pass. The syntactic lint replay of shared construction files still reports ten
pre-existing return/throw diagnostics in unchanged code; no suppression was added.
This checkpoint does not close any F55 checkbox. Collection operations, loops,
materialization and the remaining original/amendment obligations remain open.

The next collection tranche follows the approved
[typed collection gate](../design-gates/NodalDigitalCollections-DG-v0.1.md): map,
zipWith, exact homogeneous fold and seed-excluded, shape-preserving scan. A real
isolated SSA region retains callback arguments, captures and yield. Widening
reduce/balanced-tree recurrence, callback-effect validation, bounded loop writes
and unified hdlRange integration remain open; homogeneous fold is not a substitute.

A directly affected follow-on remains after the scalar checkpoint: interface-drive
and resolved-net payload gates still compare raw native type spellings. They must
reuse the same canonical, same-owner type identity when admitting fresh equivalent
symbolic arithmetic results, retaining all domain, driver and capability checks.
The corresponding source/bridge gates need the same causal witness before F55
closure; a scalar assignment positive does not establish Interface drive support.
