# Compact scalar declaration API: Foundation 97 amendment

**Revision:** 0.1
**Date:** 2026-10-01
**Status:** Approved roadmap scope; API gate, implementation and qualification pending.
**Owner:** Foundation Increment 97, public API and plugin SPI v1 review.

## Authority and placement

The owner requested a lighter real-variable declaration such as `Real(0.0.V)`
or `Real(init = 0.0.V)`, with the argument label optional and ordinary named
arguments available when a call has more options. This amendment records that
request under the existing open F-097 API-refinement increment; it does not
start implementation or add a new numbered increment.

Read with the [roadmap index](README.md), the
[main Foundation roadmap](nodal-development-todo.md),
[CONTRIBUTING.md](../../CONTRIBUTING.md) and [AGENTS.md](../../AGENTS.md).
The main file retains F-097 and its existing A-G obligations. This companion
solely owns the new descendants listed below; do not copy their checkboxes
into a second status ledger. F-097 cannot be accepted until these required
additions and its original obligations are satisfied.

Foundation 160 remains accepted and unchanged: it preserves construction
behavior rather than authorizing a new public declaration API. Accepted
Increments 29, 30, 33 and 42 supply parameter, type/unit, analog-variable and
hierarchy behavior; their historical checklists and evidence are not reopened.
F-043 remains eligible and unstarted. This amendment adds no prerequisite for
F-043 and makes no change to the approved Foundation execution order. F-097
owns the additive API gate, implementation and qualification; F-092/F-093
consume its accepted reference material and examples within their existing
API-documentation/tutorial obligations, without a reverse dependency.

## Required user-facing contract

These are planned declarations, not syntax implemented by this roadmap update:

```scala
val held = Real(0.0.V)
val another = Real(init = 0.0.V)
val uninitialized = Real()
```

The first two forms must create equivalent initialized analog variables. The
label `init =` is optional; it is not a request to make every call use named
arguments. Preserve ordinary Scala positional, named, and legal mixed argument
calls. Any additional option admitted by the API gate must have a stable public
parameter name and documented default; do not add options merely to require a
more verbose call.

`Real()` must be a distinct no-explicit-initializer declaration, equivalent to
`variable(Real)`, not an implicit zero initializer. Define and test its dimension
and read-before-assignment legality through the existing analog-variable
contract; absence of an initializer must not silently invent voltage units or
bypass initialization checks. `Real(0.0.V)` is equivalent to
`variable(Real, 0.0.V)` and retains voltage metadata, not just numeric zero.

Keep bare `Real` usable as the existing data-type descriptor. Its type-level
`Real` data kind must not be redefined as an assignable object. The compact
constructor returns `Variable[Real]`, normally inferred in `val held = ...`;
`val held: Real = ...` is not part of this proposal. Preserve the distinctions
among variables, parameters, expressions, digital signals/registers and
conservative nodes. `Real(...)` declares a variable: it must not also silently
mean an expression cast, a literal, a parameter or a clocked register.

The normal documented unit spelling remains `0.0.V`. Also prototype and support
`Real(0.0 V)` and `Real(init = 0.0 V)` through Scala postfix notation where the
pinned compiler and explicitly enabled `scala.language.postfixOps` permit it.
Require real compile fixtures for both spellings, relevant imports/options,
line breaks and diagnostics; do not claim whitespace syntax without checking
it. Do not globally relax language warnings to enable the optional spelling.
A new lexical suffix such as `0.0v`, a source preprocessor or a custom Scala
parser is outside this scope. Other unit extensions retain their existing
meaning and physical-dimension validation.

## Shared implementation and compatibility boundary

Use a thin additive `Real.apply` facade over the existing canonical variable
construction path. Both spellings must use the same active construction
session, declaration owner, initializer rules and procedural assignment API.
Each call creates exactly one declaration and evaluates its initializer once.
Preserve constructor/factory capture, failed-construction cleanup, user-source
locations, stable lexical names, bridge semantics and backend capability checks.
No new registry, alternate elaborator, textual HDL rewriting or unit erasure is
justified by shorter source syntax.

Retain `variable(Real, value)` and `variable(Real)` as compatible explicit forms.
Obtain the required versioned public-API design gate before modifying protected
implementation paths; this roadmap approval is not approval of an unspecified
signature change. Evolve current API-surface/compatibility manifests explicitly,
without changing frozen historical contracts or weakening source checks.

Real-valued analog variables are the required first scope. Review consistency
with existing `Integer`, `Bool`, `Bits` and `UInt` syntax, but do not silently
change width, signedness, signal, parameter or register semantics, or make a
blanket constructor redesign a prerequisite for this bounded addition.

## New descendants of existing Foundation 97 obligations

- [ ] **F-097.B.1 - Compact real-variable declaration API**
  - [ ] **F-097.B.1.1** Approve a versioned additive API gate with compile prototypes for `Real(value)`, `Real(init = value)` and `Real()`, optional argument-label behavior, stable named-option contracts, descriptor/type distinction, and initialized versus absent-initializer semantics. Compare reuse of the current variable factory with alternatives; record the narrow shared owner and reject ambiguous literal/cast/parameter/register interpretations.
  - [ ] **F-097.B.1.2** Implement the approved facade on the actual canonical variable-construction path, returning inferred `Variable[Real]` and preserving explicit legacy forms, single declaration/evaluation, units, ownership, assignment legality, failure cleanup, naming and source provenance. No new mutable registry or backend-specific semantic branch.
  - [ ] **F-097.B.1.3** Qualify dotted and optional postfix unit calls with the pinned Scala toolchain and explicit language-feature requirements, including positional/named arguments, imports, formatting and multiline use. Keep the dotted form free of a postfix-feature requirement; reject unsupported lexical suffixes without a parser workaround.
  - [ ] **F-097.B.1.4** Integrate the approved methods with public imports, constructor capture and current API-surface/compatibility manifests. Preserve existing `Real` descriptor consumers and explicit variable, parameter, signal and register APIs; retain historical manifests and accepted source/evidence hashes.
- [ ] **F-097.C.1 - Compact declaration correctness and rejection** Test positional/named equivalence, initialized/no-initializer separation, supported unit/parameter-valued initializers, legal later `:=` updates, exact-once initializer evaluation, separate declarations, nested/helper/module ownership and recovery after failure. Retain existing type/dimension, non-static initializer, invalid-scope and initialization rejection rules; add negative cases for unknown/duplicate arguments, misleading `: Real` annotations and unsupported unit spellings. Do not replace original predecessor tests.
- [ ] **F-097.D.1 - Explicit/compact public-source parity** Run equivalent old and compact declarations through the real frontend, bridge and applicable existing Verilog-A/Verilog-AMS profiles. Retain commands, source/tool identities, construction records and actual deterministic IR/HDL; require equivalent semantics and unchanged generated output for matched supported fixtures. Check correct authored source locations rather than demanding identical character offsets for different source spellings or normalizing discrepancies away. Required unavailable target lanes remain explicit blockers, not inferred passes.
- [ ] **F-097.G.1 - Reference, compatibility and completion handoff** Document the accepted positional/named/empty forms, required unit-syntax imports, initialization and type distinctions, stable option names and source compatibility. Supply a small public Scala example plus its actual unchanged generated Verilog-* output and reproduction commands for the existing F-092/F-093 documentation/tutorial work. Complete F-097's required review, targeted-first/full qualification, verified integration and evidence closure before advertising the API as available.

All eight new boxes remain open. No existing checkbox, acceptance record or
implementation status is changed by this planning publication. No simulator,
synthesis, numerical-behavior or performance result is claimed here.
