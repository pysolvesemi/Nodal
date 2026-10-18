# Analysis and environment query design gate v0.1

**Increment:** 44
**Status:** Approved
**Scope:** public-api

## Selected contract

This additive contract activates existing public query signatures. Acceptance and
verified integration are tracked in the F-044 implementation note. `AnalysisContext.active` retains the closed
Increment 38 analysis identities and overlapping-phase semantics.
`AnalysisContext.time` reuses the existing absolute-time query, in seconds.
`EnvironmentContext.temperature` reads ambient simulation temperature in kelvin.
`EnvironmentContext.nominalTemperature` reads the simulator default model-extraction
nominal temperature,
normalized to kelvin from the standard Celsius-valued `tnom` simulator parameter.
It does not substitute ambient temperature or a default nominal temperature.

These are simulator-context reads, not module parameters, pure functions, memory,
random draws or elaboration constants. A particular simulator keeping a value
unchanged does not authorize folding, persistent static initialization, shape-bound
proofs or movement across evaluation sites. Pure user functions remain unable to
read simulator context. Time-query admission must not relax placement restrictions
for stateful waveform operators. Existing initial/final step events retain their
identity, analysis filters and event lifetime; they are not scalar query aliases.

The authoritative `analog-functions-v1.json` registry generates shared Scala/native
context descriptors, retaining existing analysis/time adapters. The compiler uses
a closed shared context descriptor with semantic identity,
result kind/dimension and effect classification. `nodal.analog_context` has no
numeric operands, one Real temperature result, `query_kind` equal to `temperature`
or `nominal_temperature`, `registry_version = "1"`, and source metadata. It is not
marked Pure. Source scalar expressions use the corresponding zero-argument
`analog_context_v1_` function identities. Native verification independently checks
identity, version, arity, result dimension, lexical/generated context, simulator
read effects and static/fold restrictions. Strings do not grant target authority.
The existing time operation and analysis query retain their canonical owners.

## Target profile and portability

The language-structural VA/AMS profile emits `$temperature` or
`($simparam("tnom") + 273.15)`. No default argument is supplied; unsupported target
simulator parameters must not silently become successful numerical values.
Independent target parsing admits the specified kernel reads, not arbitrary
simulator calls or unchecked named options. Actual downstream compilation is
separate from structural target validation.

The existing frequency, operating-condition, corner, sweep-coordinate and seed
surfaces remain explicitly unsupported in this finite portable profile. Their source requests are checked against the shared descriptor and reject with
`NODAL-ANALOG-044-005`, identifying the unavailable capability. The existing
F-139.B.1 owner explicitly implements immutable typed environment providers for
corner/process, conditions, sweep coordinates and deterministic seeds. The
Increment 133 gate froze these signatures with compiler/simulator behavior inert;
F-044 provides the finite portable query implementation and explicit capability
boundary without claiming F-139 provider execution. This preserves that existing
ownership rather than waiving an F-044 requirement. In particular, a Spectre
frequency extension is not portable VA, VPI is not source-language syntax, and a
random draw or wall-clock value is not a deterministic immutable seed query.
No provider namespace or vendor adoption is selected by this gate.

## Alternatives and compatibility

Reuse the existing staged expression, function/query effects, time query and
procedural scalar expression owners. Reject a second context recorder, generic raw
HDL strings, default-value specialization, replacing missing environment values
with parameters, or a blanket pure-function exception. Existing public names and
analysis/event meanings remain unchanged. Previously inert temperature/time
candidates gain checked implementation; unsupported candidates fail explicitly.
Known forthcoming 138–140 context/scheduling owners remain separate.

## Focused validation

Check actual public source to normalized IR and VA/AMS for continuous and lawful
procedural query consumers, exact temperature dimensions and nominal conversion,
runtime nonconstness, initial/final and generated storage predecessors. Pair with
unknown identity/version, forged arity/type/dimension/fold, static initializer,
pure-function and illegal operator-placement negatives. Test independent parser
rejection of unsupported simulator names/defaults and accidental effect laundering.
Review repeated query traversal, stable source identities and generated ownership;
no exhaustive simulator matrix, CI or numerical success is implied.

## Normative basis and approval

[Accellera VAMS-2023](https://www.accellera.org/images/downloads/standards/v-ams/VAMS-LRM-2023.pdf)
§9.10 defines absolute time; §9.15/Table 9-27 distinguishes ambient kelvin from
nominal Celsius and defines simulator-parameter failure behavior. Initial/final
events remain §5.10.2. The
[SIMetrix function reference](https://simplis.com/documentation/simetrix/verilog_a_reference/topics/verilog_areference_verilog_afunctions.htm)
corroborates temperature/time and nominal parameter spelling; it is not an
executed portability result.

Authority is the owner's explicit instruction to implement all unchecked
Foundation increments starting at 43, continuing only Foundation, and to commit
and push subsequent Foundation changes. This authorizes the necessary additive
F-044 implementation contract; it is not a claim that the owner separately chose
these internal signatures or that checks have passed. Completion follows the
current focused prototype policy and verified integration.
