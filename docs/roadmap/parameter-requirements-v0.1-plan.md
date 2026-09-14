# Automatic parameter-dependent `require` lowering plan v0.1

**Status:** Normative Foundation roadmap target; all new implementation and qualification work open  
**Created:** 2026-09-14  
**Architecture readiness:** [PRF supplement](parameter-requirements-foundation-readiness-v0.1.md)  
**Registered surface:** [Candidate manifest](parameter-requirements-v0.1-surface.json)  
**Foundation/barrier:** [Main roadmap](nodal-development-todo.md), [registry](dependent-track-gate-v0.1.json)

## 1. Scope and inspected baseline

Plan natural parameter/configuration requirements in Nodal source:

```scala
require(WIDTH > 8)
require(DATA_BITS >= LANES, "DATA_BITS must be >= LANES")
```

`WIDTH`, `DATA_BITS` and `LANES` may be independently declared, overridable HDL
parameters. Users must not need raw HDL, manually authored `initial` blocks or a
backend-specific assertion API. This is not a complete temporal assertion
language, a new width engine, or authorization for default-specialized hardware.

The inspected `dev` baseline is `f1e847a9fc205541f8921bfaafd3e6b4e3164838`.
Relevant existing implementation and contracts are:

- `core/scala/api/src/nodal/CandidateApi.scala`: `Param[A]` and `Signal[A]` both
  implement `Expr[A]`; `Module.param` declares parameters and typed instance
  selectors bind overrides. A Boolean result type alone therefore does not
  establish parameter-stage legality.
- `core/scala/bridge/src/nodal/bridge/ScalaToMlirBridge.scala`: construction
  snapshots, typed operation serialization, semantic paths, source locations,
  native verification and backend translation form the existing boundary.
- `core/compiler/include/nodal/Dialect/Nodal/ParameterModel.h` and
  `core/compiler/lib/Dialect/Nodal/ParameterModel.cpp`: declaration/type/scope,
  default, constraint, override and structural-envelope verification already
  have owners. The initial constant-expression operator set is not the complete
  Boolean requirement language requested here.
- [Parameter model gate v1.0](../design-gates/NodalParameterConstantUnit-DG-v1.0.md)
  and `tests/compiler/fixtures/increment29/manifest.json`: historical support is
  typed constants, range/exclusion constraints and structural envelopes. That
  gate explicitly defers public Scala syntax and hierarchical override emission;
  it is not evidence of general source `require` lowering.
- `core/compiler/test/IR/parameter-rendering.mlir`, the parameter-invalid fixtures,
  `core/compiler/test/Unit/ParameterModelTest.cpp` and `scripts/check_increment29.py`
  identify regression owners. Extend coverage later; do not relabel historical
  fixtures as coverage of this feature.

Architecture and API obligations reuse [ADR 0009](../architecture/0009-core-semantic-contracts.md),
[ADR 0005](../architecture/0005-backend-capability-profiles.md), the approved
[core API v0.3 gate](../design-gates/NodalCoreSemanticsPipelineApi-DG-v0.3.md),
[core semantic plan](core-semantics-api-v0.3-plan.md),
[digital standards](digital-hdl-language-standards.md),
[digital verification plan](digital-verilog-open-source-verification-plan.md),
and existing source-origin, staged-loop, shape, target-IR and formal contracts.
Historical gates and completed increment evidence remain unchanged. This plan
requires a versioned amendment before changing their public/IR surfaces; it is
not itself an approved replacement API gate.

## 2. Source-language and API contract

### A. Concrete condition

An active elaboration requirement such as `require(16 > 8)` is evaluated
immediately, exactly once. A concrete false condition raises an elaboration
failure with a stable Nodal diagnostic, the original Scala call-site span and
user message. A concrete typed `Expr[Bool]` with no overridable dependencies is
handled similarly after type/stage validation. A successful concrete check
requires no emitted HDL assertion.

The source-position capture must use compiler-provided Scala metadata, not
source-text scanning, reconstructed names, or a parsed predicate string.

### B. Parameter-dependent condition

`require(WIDTH > 8)` retains the parameter declaration reference and Boolean
predicate. A default value of 16 does not establish that later bindings of 8 or
7 are legal. Preserve the requirement in the emitted module and evaluate it for
each instantiated binding, including external HDL instantiation of a module
emitted only once by Nodal.

Default-validation policy: follow the existing fail-closed default-validation
contract. At the earliest stage where the relevant declaration defaults and
applicability scope can be evaluated correctly, diagnose a known-invalid active
default as an error, with the requirement's source and message. Declared module
defaults must be valid even when a particular instance would override them;
this feature does not introduce a must-override exception. Known concrete
instance bindings can also fail early. Neither a successful default check nor a
successful known-instance check removes the symbolic declaration-level
requirement. Unknown/unproved evaluations are not reported as successes.

Validate dependencies in their actual scope. Do not check only a child's default
when its requirement depends on an expression bound from the parent. Do not
substitute parent or child defaults for those symbolic bindings.

### C. Runtime signal condition

A predicate involving a port, wire, register, memory value, sampled analog value,
or other changing signal is not a parameter requirement. Recursively establish
stage and provenance before simplifying expressions; a dynamic operand cannot
be hidden by default folding or an algebraically constant-looking expression.

The initial contract rejects such a call with a source-correlated diagnostic
stating that `require` accepts elaboration/configuration conditions and that a
runtime assertion needs an explicitly supported runtime assertion contract.
Where no runtime assertion API is implemented, say so; do not recommend a
fictional API. A later approved runtime assertion surface may provide an explicit
route, but this work neither silently creates one nor schedules a one-shot
runtime check.

### Overload recognition and host compatibility

The candidate is a `require` overload family available in normal `Module` scope,
with a matching public helper-facing import surface. It dispatches by type:

- Scala `Boolean` overloads preserve normal `scala.Predef.require` condition,
  exception/cause and by-name `Any` message behavior. In Nodal elaboration, a
  source-capturing facade and diagnostic boundary attribute a failure to the
  original call without changing ordinary host evaluation or evaluating a
  successful requirement's lazy message. Calls outside that facade and explicitly
  qualified `scala.Predef.require` retain ordinary Scala behavior.
- `Expr[Bool]` overloads, with and without a diagnostic message, construct a typed
  requirement after validating its expression's stage. They never convert the
  predicate to a Scala Boolean. Parameter comparisons with Scala integer literals
  lift only the literals through the approved typed numeric contract.
- For a retained symbolic requirement, capture a deterministic elaboration-time
  message once for later HDL diagnostics. The candidate typed-message contract
  is a by-name `String`, evaluated when a check must be retained or a concrete
  failure reported; it must not depend on changing hardware. This differs
  deliberately from delaying a Scala-only message until a later JVM failure.
  The no-message form derives readable diagnostic text from the typed predicate
  and stable requirement ID, not from parsing the Scala source.

Freeze exact overload signatures and placement through PRF-001 compile contracts:
inside a module, helper functions and external-library consumers, explicit and
wildcard imports, qualified `Predef.require`, one/two-argument calls, lazy-message
side effects for Scala Booleans, and ambiguous/incorrect typed calls. Preserve
normal Scala behavior rather than relying on an untested import-shadowing trick.
No implicit symbolic-to-host conversion, `.default` extraction, source parsing,
raw HDL string payload or backend-specific source assertion is allowed.

### Structural applicability

Requirements belong to a module definition and, where applicable, an explicit
structural/generate region. Preserve the activation guard, region/binder
identities and hierarchy path. A requirement in an inactive generated branch
must not be hoisted and executed unconditionally. A concrete predicate inside a
parameter-dependent structural scope is still conditionally applicable; validate
it under that scope rather than treating it as an unconditional host failure.
A supported lowerer retains the scope or its exact guarded equivalent. Reject
unsupported scope/lowering combinations instead of checking a default-selected
branch. Runtime `when` or procedural signal control is not structural scope.

## 3. First-class compiler requirement

Plan a backend-neutral requirement operation in authoritative Nodal MLIR, with a
small typed frontend construction record and versioned bridge serialization.
The operation name is a gate candidate, not a currently registered operation.
It must carry or reference:

| Field | Required meaning |
| --- | --- |
| Predicate | Boolean result and typed expression operands; arithmetic semantics retained |
| Dependencies | Genuine scoped parameter declaration identities, aliases, shared and independent dependency edges |
| Diagnostic | User message or typed derived message; original Scala file/range and source-origin chain |
| Ownership | Owning module-definition identity and stable requirement identity |
| Applicability | Structural/generate region, binders and activation guards, not an inferred default branch |
| Validation | Stage, default/known-binding result, proof classification and proof-domain provenance |
| Capability | Required language/diagnostic/formal capabilities and selected lowering/evidence obligations |

Module names, equal parameter names and equal defaults do not establish identity.
Two independent declarations remain independent; aliases of one declaration
retain sharing. Resolve verified symbols/SSA references with owner and scope,
not a string-to-default map. Reject stale, forged, unresolved, wrongly typed and
out-of-scope references transactionally at construction, bridge and native
boundaries. Prevent cross-module scope escape; explicit instance binding is the
legal hierarchy boundary.

Extend or reuse the existing typed constant-expression model after semantic
comparison with the numeric/Boolean operators. Required initial expressions
include:

```scala
WIDTH > 8
DATA_BITS + GENERATION_BITS <= 4096
(DATA_BITS > 0) && (LANES > 0)
!(LANES > 1) || DATA_BITS >= LANES
```

Support the necessary comparisons, addition and Boolean `&&`, `||`, `!` as
structured typed operations. Freeze signedness, operand/result widths, promotion,
literal lifting, overflow, four-state behavior and any dimension checks. Do not
let a backend's implicit integer width/sign rules change a predicate. Preserve
lossless arithmetic or diagnose unsupported representability. Retain existing
checks for division by zero, cycles and illegal expressions; Boolean composition
does not grant permission for an ill-typed operand or dynamic side effect.
No wholesale symbolic-width redesign is included.

Maintain four separate proof classifications over an explicitly recorded legal
domain and structural scope:

- **Universally true:** established by sound proof, not by testing defaults.
- **Universally false:** established false for every applicable legal binding;
  diagnose the impossible active requirement rather than publish a valid design.
- **Mixed-validity:** there are established valid and invalid configurations;
  retain the requirement and check each binding.
- **Unproved:** proof is absent/incomplete; never label it true. Retain and lower
  a supported predicate without requiring a whole-domain proof.

Default evaluation is a separate result, not one of these universal proofs.
Do not assume the requirement itself to prove it true or manufacture an empty
legal domain that vacuously proves success. Report inconsistent/empty domains.
Cartesian enumeration is not a prerequisite for preserving or emitting a
supported predicate involving independent parameters. Sound bounded analyses
may provide evidence, but failed/unfinished analysis does not remove the check.
The initial contract keeps symbolic requirements even when proven true; any
later proof-backed elision needs an explicitly approved preservation contract.

Serialization, optimization, hierarchy rewriting and native target lowering
must preserve the requirement and its provenance as a verification effect. A
metadata inventory without an executable lowering is insufficient. Preserve one
module per supported structure; do not clone modules by default values just to
avoid symbolic checks. Requirement IDs derive from stable source/semantic/scope
identity, including helper invocation or structural index where needed; no JVM
identity, traversal-counter-only names or source-file parsing is permitted.

## 4. Backend-capability and assertion behavior

### SystemVerilog-capable profile: intended target

Illustrative future Nodal source (the new `require` behavior is not implemented):

```scala
import nodal.*

final class Example extends Module:
  val WIDTH = param(16)
  require(WIDTH > 8, "WIDTH must be > 8")
  val data = in(Bits(WIDTH))
```

Illustrative SystemVerilog target, not actual compiler output:

```systemverilog
module Example #(
    parameter integer WIDTH = 16
) (
    input wire [WIDTH-1:0] data
);

`ifndef SYNTHESIS
    // Nodal source: Example.scala:5; requirement: Example.require_width_gt_8
    initial begin : nodal_require_width_gt_8
        assert (WIDTH > 8)
            else $fatal(1, "Nodal requirement failed: WIDTH must be > 8");
    end
`endif

endmodule
```

The production lowerer must bind the predicate to the actual module parameters,
preserve exact arithmetic, escape diagnostic text safely, retain user message
and Scala source attribution in diagnostics/source maps, and use deterministic,
collision-free names. The simple label above assumes no collision; repeated
requirements/helper calls require semantic disambiguation. Report instance
identity, for example through a qualified `%m` diagnostic field, without adding
functional ports or changing the user's message.

An `initial` immediate assertion executes at simulation time zero after parameter
binding. It is not guaranteed to reject during HDL compilation or before all
HDL type/structural elaboration. It must fail when the predicate is not
definitively true, including X/Z under a supported four-state profile. A two-state
simulator must not be presented as evidence of four-state coverage. Termination
must be observable as a deterministic non-success by the regression runner.
Compilation alone and an unexecuted initial block are not acceptance evidence.

Use native IR/backend lowering, never emitted-text post-processing. Emit no new
functional hardware, clocks, ports, user-visible parameter overrides, width
clamps or default changes. Hardware declarations and parameter arithmetic stay
outside the diagnostic guard.

### Synthesis boundary

Every generated simulation diagnostic is enclosed in:

```verilog
`ifndef SYNTHESIS
    // simulation-only requirement diagnostic
`endif
```

When `SYNTHESIS` is defined the diagnostic is removed, including when a flow
spells the definition `SYNTHESIS=0`. It therefore does not enforce parameter
legality during synthesis. `SYNTHESIS` is a tool/flow convention, not a portable
language guarantee; supported synthesis adapters must explicitly establish or
verify it and retain the effective defines in evidence. Do not describe this
simulation-only check as synthesis-time protection.

A synthesis flow's configuration validation, where offered, is a separate
explicit contract. Mandatory Nodal graph/type/structure checks still apply;
they are not waived by the simulation guard. A time-zero assertion cannot repair
hardware already constructed or structurally selected incorrectly from defaults.

### Portable Verilog-2001/2005

Required `Backend.Verilog` remains the conservative IEEE 1364-2005 profile;
any advertised 2001 compatibility requires its own mode evidence. Never silently
emit SystemVerilog `assert` or `$fatal`, or require a SystemVerilog parser.
Plan an equivalent procedural diagnostic, for example:

```verilog
`ifndef SYNTHESIS
initial begin : nodal_require_width_gt_8
    if ((WIDTH > 8) !== 1'b1) begin
        $display("NODAL_REQUIRE_FAILED: WIDTH must be > 8; instance=%m");
        $stop;
    end
end
`endif
```

This is a profile candidate, not an unconditional portability claim. The
case-inequality form fails for false or unknown predicates. The complete
language-plus-runner profile must terminate deterministically and return failure.
The Icarus candidate uses noninteractive `vvp -N` so `$stop` is an error exit;
plain `$finish`, `$finish(1)`, interactive `$stop` or `vvp -n` alone do not
establish the required portable runner-visible failure contract. Verilator
must qualify its corresponding fatal-stop/assertion options and any adapter
callbacks; no stop-to-success override may hide failure. A qualified alternative
may normalize an unambiguous diagnostic event to a failing runner result, but
must also terminate safely and retain raw tool status.

If a selected backend/language/tool profile cannot enforce the requirement at
its declared validation stage, reject it with a source-correlated capability
diagnostic. Do not silently drop the predicate, preserve metadata only, silently
upgrade to SystemVerilog, or select a different backend. An explicitly declared
synthesis use of guarded HDL is not a promise of simulation or synthesis
configuration validation.

### Verilog-A, Verilog-AMS, and formal are separate

Verilog-A and Verilog-AMS need separate language and simulator capability rows,
including legal context, initialization/analysis timing, predicate type subset,
message handling and runner-visible termination. Existing native `from`/`exclude`
constraints may be reused only where they are demonstrably equivalent; they do
not cover arbitrary independent cross-parameter predicates automatically. Do not
copy the SystemVerilog immediate-assert syntax into these profiles. Unsupported
predicates or enforcement mechanisms must fail explicitly, even when an IR
metadata record can be retained. An OpenVAF/ngspice path is not evidence of full
Verilog-AMS support.

Formal handling is a separate capability contract under core formal owner 67.
`ifndef SYNTHESIS` can leave diagnostics visible in a formal flow; do not assume
that the guard removes them or that a formal parser supports `$fatal`/`$stop`.
Before such a flow, use a supported native lowering to a configuration failure or
formal assertion, or reject the profile. Validate concrete formal configurations
explicitly; preserve symbolic parameter binding and scope where supported.
Never automatically translate a requirement to an assumption that hides invalid
configurations or makes a proof vacuous. Report the formal-domain and result
separately from simulation evidence. The later full temporal property language
is not a prerequisite for this configuration-only contract.

## 5. Structural and width safety

`require(LANES > 0)` does not make `LANES` a Scala integer and does not authorize
ordinary host loops, collection sizes, branch decisions or graph topology to use
its default. Parameter-controlled construction still requires supported symbolic
structural lowering, a justified genuinely concrete decision, or explicit
rejection. Preserve existing bounded structural-envelope and scope rules.

Distinguish a deferred configuration predicate from immediate obligations needed
to construct a well-typed compiler graph, such as valid SSA/types, finite supported
shape representation, legal stage, ownership, dominance and structural effects.
Do not rely on a downstream tool rejecting `wire [-1:0]`: the range spelling is
not a reliable configuration-error mechanism. Do not silently clamp widths or
use an asserted predicate to conceal an already incorrect compiler graph.

Width/expression/hierarchy dependencies must be listed before implementation:
typed integer comparisons and Boolean composition; literal lifting and exact
addition widths; parameter identity/override substitution; supported symbolic
width and structural scope representation; native target expression rendering;
and versioned requirement/diagnostic serialization. Resolve actual gaps in the
existing owners through narrow amendments rather than broadening this task into
a new symbolic-width architecture.

## 6. Owner-linked implementation and qualification sub-checklists

These are registered extensions of the named existing Foundation items, not new
global increments. PRF checkpoints are architecture prerequisites; PRC tasks are
later implementation/qualification work. Markdown checkboxes are authoritative.
No parent may close based only on the existence of this document or a manifest.

- [ ] **PRC-001 — Source/API follow-up to owners 13-17, 22 and 29-30**
  - [ ] **PRC-001.a:** After PRF-001 and PRF-002, implement approved overloads, typed predicate capture, concrete/default policy and source-correlated diagnostics without symbolic-to-host conversion.
  - [ ] **PRC-001.b:** Preserve ordinary Scala Boolean behavior, external helper/import compatibility and message semantics; reject runtime conditions and source-text/default extraction shortcuts.
  - [ ] **PRC-001.c:** Close source portions of PRV-001, PRV-007 and PRV-009 with actual positive/negative compile and elaboration evidence; record the approved API amendment rather than changing historical gate claims.

- [ ] **PRC-002 — Requirement IR/bridge follow-up to owners 19-21, 29-30 and 89**
  - [ ] **PRC-002.a:** After PRF-002 and PRF-003, implement typed requirement operations, comparison/Boolean DAG support, real declaration identities, independent/shared dependencies and structural applicability.
  - [ ] **PRC-002.b:** Preserve requirements through snapshots, versioned bridge/native round trips and optimization; verify stage/type/arithmetic/provenance/scope and separate default results from proof classifications.
  - [ ] **PRC-002.c:** Demonstrate non-enumerative retention, positive/negative native verifiers, old/new serialization handling, and IR portions of PRV-004 through PRV-009; no metadata-only enforcement claims.

- [ ] **PRC-003 — Hierarchy, safety and preservation follow-up to owners 16-22, 26, 29, 83-86 and 153-157**
  - [ ] **PRC-003.a:** After PRC-001/002 and PRF-003, preserve child-parent expression bindings, per-instance configuration checks, shared aliases and independent declarations across hierarchy and legal generation.
  - [ ] **PRC-003.b:** Preserve source/requirement identities and messages across passes, helper calls, inlining and deterministic target naming; maintain default-independent structure and width checks.
  - [ ] **PRC-003.c:** Close compiler-side PRV-004 through PRV-008 and preservation mutations; require separate emitted-HDL execution evidence under the backend owners below.

- [ ] **PRC-004 — Increment 65 portable backend requirement lowering**
  - [ ] **PRC-004.a:** After PRF-004 and PRC-001/002/003, lower supported requirements natively to guarded procedural Verilog diagnostics bound to actual parameters and scope, with exact predicate semantics and no functional changes.
  - [ ] **PRC-004.b:** Enforce separate language/runner capability selection, deterministic failure contract, pure Verilog-2005 syntax and explicit unsupported rejection; do not require future SystemVerilog.
  - [ ] **PRC-004.c:** Close emission portions of PRV-002 through PRV-008, PRV-013 and PRV-014; actual Icarus/Verilator qualification belongs to PRC-005, not an emission-only success claim.

- [ ] **PRC-005 — Increment 66 simulation and adapter qualification**
  - [ ] **PRC-005.a:** After PRF-005 and PRC-004, implement the runner contracts with owners 87-88/148 as applicable; pin tool builds, modes, defines, assertion-enable and stop/fatal options, startup evaluation and deterministic result normalization.
  - [ ] **PRC-005.b:** Execute Icarus and Verilator positive/negative matrices using one generated module artifact; complete PRV-001 through PRV-011, PRV-013 through PRV-015 for the supported portable profiles.
  - [ ] **PRC-005.c:** Publish commands, logs, artifact/configuration hashes, tool statuses and failure canaries; no full HVL or dependent verification-track implementation is required for these compiler regression runners.

- [ ] **PRC-006 — Increment 67 synthesis isolation and core formal handling**
  - [ ] **PRC-006.a:** After PRF-004/005 and PRC-004, establish/verify `SYNTHESIS`, complete PRV-012 and guard-related mutations, and demonstrate unchanged functional hardware with diagnostics removed.
  - [ ] **PRC-006.b:** Qualify a separate formal configuration/assertion lowering or explicit unsupported rejection; test effective macro modes, invalid configurations, scope/binding and absence of automatic requirement-to-assumption conversion.
  - [ ] **PRC-006.c:** Report synthesis equivalence, formal outcomes and unsupported cases separately; never claim that guarded diagnostics validate synthesis configurations.

- [ ] **PRC-007 — Increments 99/130 SystemVerilog gate and native backend**
  - [ ] **PRC-007.a:** Under 99, after PRF-004, approve the immediate-assert/fatal language/profile contract without importing a complete temporal assertion language or changing `Backend.Auto`.
  - [ ] **PRC-007.b:** Under 130, after its existing gate and PRC-001/002/003, implement native scoped `initial` immediate-assert lowering, guard, exact predicate semantics and deterministic source/name/failure behavior.
  - [ ] **PRC-007.c:** With 66-67/87-88, qualify the applicable PRV matrix in pinned SystemVerilog modes, including enabled assertion execution, false/X/Z handling where supported, synthesis isolation and unsupported formal/tool profiles. These are later SystemVerilog obligations, not prerequisites of the portable implementation.

- [ ] **PRC-008 — Existing AMS/capability owners 23, 72, 75-76, 78 and 87-88**
  - [ ] **PRC-008.a:** After PRF-004/005 and typed IR readiness, publish independent Verilog-A/Verilog-AMS predicate, context, timing and termination capability decisions; explicitly reject unsupported lowerings rather than retain metadata alone.
  - [ ] **PRC-008.b:** Implement only approved AMS lowerings through native target IR with the same parameter identity, source and scope contracts; qualify any equivalent native constraint encoding, guard and diagnostic mechanism separately.
  - [ ] **PRC-008.c:** Run applicable parameter-binding, negative, source/scope, guard and unsupported-profile tests through qualified AMS tools when available. Record unsupported/unavailable/deferred states honestly; absence of a tool is not a pass and full commercial AMS support is not an M0 gate.

## 7. Explicit future acceptance tests

Every item below is open. Assign feature portions to the PRC owners above and
record actual evidence before closing them. These tests were not run for this
roadmap update.

- [ ] **PRV-001 — Concrete and default evaluation:** concrete true passes and concrete false fails immediately with original source/message; preserve Scala Boolean lazy-message behavior. Reject known-invalid active defaults early; valid defaults do not remove symbolic requirements.
- [ ] **PRV-002 — One reusable emitted artifact:** generate `Example` once with default `WIDTH=16`; retain its exact HDL hash and reuse it with `WIDTH=9` and `32` passing, and `WIDTH=8` and `7` failing. HDL compilation per binding is allowed; Nodal regeneration/specialization per tuple is not. These invalid predicates still have positive widths, so an unrelated width error cannot impersonate an assertion failure.
- [ ] **PRV-003 — Valid default, invalid override:** an independently supplied external HDL instantiation overrides the valid default with an invalid value and fails during executed checking. Reject a constant-folded-default substitute for the predicate.
- [ ] **PRV-004 — Independent and shared dependencies:** test `DATA_BITS >= LANES` with valid tuples `(16,4)` and `(4,4)` and invalid `(3,4)`; test sum-bound and composed Boolean predicates. Equal names/defaults do not merge independent declarations; aliases retain sharing. Add wide/non-enumerable domains and prove supported predicates can be retained without Cartesian enumeration; unproved never becomes true.
- [ ] **PRV-005 — Two instances:** the same emitted module instantiated with `WIDTH=9` and `WIDTH=8` checks each binding independently and attributes the failure to the invalid instance. Add an all-valid two-instance run and a swapped-binding mutation.
- [ ] **PRV-006 — Child/parent expressions and scopes:** bind child parameters to parent expressions, vary parent overrides and retain the correct dependency graph; cover nested generation, active/inactive branches and independent sibling bindings without default-selected hardware.
- [ ] **PRV-007 — Deterministic diagnostics and names:** repeated builds and supported traversal changes retain messages, original Scala spans, source-origin maps, stable requirement IDs and collision-free HDL labels; cover repeated helpers and scoped requirements. Validate escaping and instance attribution.
- [ ] **PRV-008 — Native reference and scope negatives:** reject forged, stale, unresolved, wrongly typed and cross-scope references, cycles, illegal structural scope escape, invalid bridge/IR payloads and duplicate identities. Retain graph/width/envelope safety; neither clamping nor downstream negative-range rejection is a substitute.
- [ ] **PRV-009 — Runtime-stage negatives:** signal/register/memory/analog-dependent `Expr[Bool]` conditions are not parameter requirements, including mixed symbolic/runtime expressions. Report the correct source diagnostic; no one-time silent conversion.
- [ ] **PRV-010 — Actual execution:** simulator test configurations enable and execute the checks, reach required time-zero initialization/evaluation, and produce runner-visible failures. Merely compiling HDL is insufficient. Include an intentional failing canary; reject disabled assertions, premature testbench success/termination or callbacks that swallow failure.
- [ ] **PRV-011 — Independent simulator qualification:** Icarus and Verilator execute each claimed diagnostic profile in the declared language modes, including pass/fail/unknown tests within their supported value models. Retain native exit/signal status and normalized regression result; do not infer four-state support from two-state execution.
- [ ] **PRV-012 — Synthesis isolation:** Yosys with `SYNTHESIS` explicitly defined sees unchanged functional hardware and no simulation-only diagnostic logic. Compare ports, parameters, widths, cells/state and appropriate equivalence against a diagnostic-free reference for valid bindings. Inspect guard structure/effective defines; this does not validate invalid synthesis configurations.
- [ ] **PRV-013 — Plain-Verilog purity:** compile/reparse in Verilog-2005 and any separately advertised 2001 mode with no accidental SystemVerilog `assert`, `$fatal`, or other SV-only syntax. Do not use a permissive SV parser as the sole check.
- [ ] **PRV-014 — Capability negatives and formal:** unsupported backend, language, predicate, structural scope and runner combinations fail explicitly. Test Verilog-A and Verilog-AMS separately. Exercise formal macro visibility and approved lowering/rejection; a requirement must not become an assumption hiding an invalid configuration.
- [ ] **PRV-015 — Mutation sensitivity:** the acceptance suite detects a removed synthesis guard, a dropped predicate, a reversed comparison, a default-folded condition, a wrong-instance binding and disabled assertion execution. Retain each mutation diff, expected failing test and observed failure; passing an unmutated suite alone does not close this item.

## 8. Evidence and tool-command requirements

For every claimed profile, record the compiler commit, bridge/IR/profile versions,
source fixture and hash, emitted HDL and hash, generation invocation/count,
per-instance override tuples, tool executable/version/build hash, language mode,
complete effective defines, assertion and fatal-stop controls, adapter/runner
version, full compile/elaborate/run/synthesis/formal commands, working directory,
exit code/signal, timeout classification, complete stdout/stderr logs, expected
and observed result, requirement/source/instance identities, and retained output
artifacts. Pin versions when implementing qualification; this plan does not
invent a current pinned version or report any tool as qualified.

Keep source/elaboration, IR round-trip/verifier, emission/reparse, simulator
execution, synthesis equivalence and formal evidence separate. A tool missing,
license unavailable, timeout, unexecuted run or unsupported feature cannot count
as a pass. The negative canary must fail before ordinary successful tests are
accepted; compile success alone never establishes assertion execution. Ensure
testbench success does not race unfinished time-zero checks.

Illustrative future command profiles, to be completed and pinned by PRC-005/006
and PRC-007 (not commands run in this documentation task):

```text
# Portable profile: wrappers bind the unchanged emitted Example.v per instance.
iverilog -g2005 -s tb -o run.vvp Example.v tb_bindings.v
vvp -N run.vvp

# A separately advertised 2001 profile substitutes -g2001 and needs its own evidence.
# SystemVerilog candidate, only after the SV gate and on a pinned supporting version.
iverilog -g2012 -gassertions -s tb -o run.vvp Example.sv tb_bindings.sv
vvp -N run.vvp

# Verilator: pin and verify exact supported syntax/options, then execute the binary.
verilator --binary --timing --assert --language 1364-2005 --top-module tb Example.v tb_bindings.v
./obj_dir/Vtb
# The SV profile uses the exact supported 1800 language mode of its pinned tool.
# Do not enable options/callbacks that turn fatal-stop or assertion failure into success.

# Synthesis: retain a full script and comparison evidence, not just parse success.
yosys -p 'read_verilog -D SYNTHESIS=1 Example.v; hierarchy -check -top Example; proc; opt; check; stat'
```

A tool's supported input mode may cover only a subset of the future
IEEE 1800-2023 backend contract; record that subset, never claim full standard
qualification from a `-g2012` example. Further commands must capture the actual
reference/netlist equivalence check, normalized diagnostic runner and mutations.
Do not treat `$finish(1)` as a cross-tool nonzero-exit guarantee.

Primary tool references for the future adapter qualification are the Icarus
VVP flag documentation (`https://steveicarus.github.io/iverilog/usage/vvp_flags.html`),
the Verilator executable/language guide (`https://verilator.org/guide/latest/exe_verilator.html`),
and the version-matched Yosys/SBY frontend documentation. These are design
references, not execution evidence; retain pinned documentation/tool versions in
the eventual qualification record.

## 9. Completion and non-goals

Complete PRF architecture/API checkpoints independently of later implementation,
then complete each PRC owner obligation and its assigned PRV evidence. Preserve
all original Foundation IDs and completed history. The existing complete
Foundation barrier includes these registered follow-ups, but does not depend on
implementing any separately blocked verification/productivity track.

No runtime/temporal assertion language, wholesale symbolic-width redesign,
SYNTHESIS-independent synthesis legality enforcement, compiler implementation,
workflow modification, CI, simulation, synthesis or formal run is performed by
this documentation commit. All source and HDL examples are illustrative targets.
Current generated Verilog-* is unchanged.
