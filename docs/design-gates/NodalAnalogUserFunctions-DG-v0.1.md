# Nodal user-defined analog functions design gate v0.1

**Increment:** 41
**Status:** Approved
**Scope:** public-api

## Surface and purpose

`AnalogFunction(name, resultType, dimension)(body)` declares one module-local,
nonrecursive native analog function. The factory returns an immutable
`AnalogFunction[A]` callable with explicit `Expr` arguments. The body receives an
`AnalogFunctionBody` with typed `input`, initialized immutable `local`, and typed
`select` operations. Its final expression is its mandatory total return.
Ordinary Real arithmetic, comparisons, Boolean expressions, and `AnalogMath`
functions are reused. Named function inputs, results, and locals are Real or
Integer. Boolean intermediate expressions are allowed, not Boolean ABI values.

This is not a Scala helper whose body expands at every call. One source
function becomes one native declaration; calls remain calls. Argument ordering,
scalar kinds, physical dimensions, names, locals, return identity, and source
locations survive construction, the bridge, native verification, and emission.
The existing public API v0.3 compatibility base and core/library boundary remain
unchanged. The user's request to implement this roadmap increment authorizes
this additive design; validation and accepted-evidence closure remain separate.

## Typed source contract

Declare every input before any body expression or local. Input order defines
positional call order. Types are exact: there is no implicit Integer/Real
conversion, argument omission, variadic target signature, overload resolution,
or untyped target text. The call API accepts a sequence of typed expressions;
arity, kinds, and dimensions are independently checked at construction and in
native IR. Real values carry canonical physical dimensions. Integer values are
dimensionless signed integers. Numeric body helpers support same-kind addition,
subtraction, multiplication and negation; division requires Real operands.

Locals require an initializer and cannot be reassigned. A local becomes visible
after its initializer. The final expression must exactly match the declared
result kind and dimension. A declaration with no inputs or no valid return
rejects. `select` requires a Boolean guard and identical arm types. Known
constant zero divisors, nonfinite expressions, invalid mathematical domains,
and constant signed 32-bit Integer overflow reject rather than acquiring an
implicit truncation or numerical policy. Runtime domains and overflow are not
proved; no whole-envelope or numerical solver claim is made.

Every dynamic dependency must be an explicit argument. Passing module parameters
or probes at call sites is supported; capturing them inside definitions is not.
Function handles belong to one module and one elaboration session. Body handles
are lexical and closed after construction. Failed definitions do not enter the
session registry, and retrying the same declaration name is allowed after a
failed definition. No mutable registry is shared between elaborations.

## Resolution, recursion, and effects

Function names are unique per module: no overloads, duplicate local/input names,
implicit imports, or nested function declarations. Source calls in bodies resolve
to already completed same-module definitions. Native IR also accepts acyclic
forward references and emits dependencies first in deterministic name order.
Direct and indirect native call-graph cycles reject with a stable diagnostic.
Scala elaboration recursion is not an alternative runtime function mechanism.

Bodies may contain only typed arguments, constants, pure expressions, initialized
locals, calls to eligible functions, and one total return. Conservative probes,
module captures, analog state, contributions, procedural effects, analysis/time
queries, events, parameters, topology, and arbitrary native operations reject.
The native function region is isolated from enclosing SSA values. Function
creation belongs directly to a Module; external calls currently belong to plain
`analog` expression regions, not equations, procedural regions, or events.

No interprocedural constant folding, inlining, specialization, CSE of calls, or
call deletion is authorized. Constant analysis validates body subgraphs without
rewriting them. Claimed folding/simplification annotations on functions, body
values, calls, or dependent expressions must not substitute unproved results.
Calls are not proven static merely because their actual arguments are constant.
Consequently they cannot bypass earlier static coefficient/timing restrictions.

## Compiler representation and target

Four first-class operations carry a version-1 contract:
`nodal.analog_user_function`, `nodal.analog_function_value`,
`nodal.analog_user_call`, and `nodal.analog_function_return`.
The definition records its symbol, return type, metadata, and isolated typed SSA
body. Value operations distinguish inputs, constants, locals, arithmetic,
comparisons, logic, conditional selection and registered mathematical functions.
Calls use flat module-local symbol references. Native verifiers reconstruct type,
dimension, arity, ordering, ownership, purity, return, and call-graph legality.
The bridge carries an inventory and per-operation source locations, not raw HDL.

Verilog-A emits `analog function real/integer`, explicit input/type/local
declarations, initialized local assignments, and the final function-name return
assignment. Real constants preserve a decimal point or exponent: `1.0 / 2.0`
must not become integer `1 / 2`. Function, module, parameter, node, terminal and
branch names share the target namespace. Reserved names and cross-kind collisions
reject before publishing output. Inputs and locals must not shadow function
names. User-provided function/input/local names are retained; automatic Scala
local-binder preservation remains Foundation 153–157.

The independent target parser checks function grammar, input declarations,
local initialization order, total returns, pure allowed expressions, known
nonrecursive callees, and exact call arity. It rejects injected tasks, captures,
stateful functions, and malformed call nesting. Its signature table is module-local
and updated only after a complete accepted declaration. Native typed validation,
not textual inference, remains authoritative for physical dimensions.

## Qualification and scope limits

Reference: Accellera Verilog-AMS Language Reference Manual 2023, sections 4.7
(analog user-defined functions) and 4.5.14 (analog operator restrictions).
Nodal's input-only, total-return, pure scalar profile is deliberately narrower
than the language. In particular, this gate does not imply that the standard
forbids every feature deferred here.

Deferred: mutable locals, procedural loops and early returns; output/inout
arguments; string and array arguments/results; zero-argument functions; generic
type/dimension polymorphism; source forward declarations; cross-module calls;
function calls in equations/procedural/event bodies; interprocedural optimization;
numerical execution, OpenVAF/ngspice qualification, full Verilog-AMS and synthesis.
These forms reject rather than being silently approximated or passed through.

Acceptance requires public-only Scala construction, deterministic source-mapped
MLIR, native positive and mutation matrices, an independent target-parser suite,
exact generated Verilog-A evidence, predecessor regression, required Core CI,
review, squash merge, and exact post-merge qualification. The roadmap checkbox
remains open until a separate evidence closure records those results.

## Stable diagnostics

| Code suffix (`NODAL-ANALOG-041-`) | Meaning |
| --- | --- |
| 001 | Illegal context, escaped body or unsupported effect. |
| 002 | Invalid declaration, naming, ordering or contract version. |
| 003 | Scalar type, dimension, arity of body expression or literal mismatch. |
| 004 | Missing, invalid or nonfinal return. |
| 005 | Unresolved callee, call arity or module/session ownership. |
| 006 | Captured, unavailable or foreign body value. |
| 007 | Native recursive or unresolved call graph. |
| 008 | Known-invalid constant expression or overflow. |

Builtin mathematical domain diagnostics retain `NODAL-ANALOG-038-*` on the source
surface. Unproved folding uses the existing `NODAL-ANALOG-FOLD-001` family;
reserved names and target collisions use `NODAL-BACKEND-NAMING-*`.
