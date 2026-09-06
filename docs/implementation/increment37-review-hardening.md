# Increment 37 — Event dispatch and crossing-direction review

## Corrections

PR #124 review identified a digital `on(event)` compatibility regression and an
incomplete native crossing-direction check. Analog events continue to use the
owned analog-procedure path. Digital rising and falling events use their prior
candidate block path, including the existing continuous-waveform restrictions.
Digital event processes cannot enter an `analogProcedure` or an analog controlled
body and silently turn a conditional write into an unconditional assignment.

Nodal's public `Edge` contract accepts only falling, either, and rising. Source
and native validation require an explicit direction to be provably -1, 0, or 1.
A parameter default, mutable initializer, or supplied constant annotation is not
a proof of the direction. Omitted direction remains supported. Integer enable
expressions remain dynamic; the direction restriction does not restrict enables.
This is Nodal's supported profile, not a restriction asserted for the complete
Verilog-AMS language.

## Regression entry points

`AnalogEventReviewTests` exercises both public digital edge directions, exact
body execution during construction, digital-only classification, waveform
restrictions, rejected mixed contexts, and the shared direction contract.

`run_direction_matrix.py` is registered with CTest. It checks omitted direction,
all three supported values, unsupported signed literals including 64-bit
extremes, symbolic defaults, forged metadata, and initialized mutable storage.
Native parsing and optimization must reject invalid inputs. Both target entry
points must reject without publishing partial HDL. Accepted output must be
identical before and after optimization.

## Evidence boundary

The direction regression reproduces the failure on the prior recovered compiler.
The repaired local compiler passes 52 direction checks and the existing 63
native/source checks using the retained public-source witnesses. These are
historical local results, not final-head or post-merge qualification. The later
accepted implementation, resolved reviews, merge, and exact post-merge checks are
recorded in [the separate evidence closure](increment37-evidence-closure.md).

## Generated-loop lexical storage

The final-head review also found that hoisting a variable declared inside a
genvar event loop would incorrectly share its storage across generated
occurrences. The scalar event target now rejects such declarations with
`NODAL-BACKEND-EVENT-001` until per-generated-instance storage is represented.
The source-semantic IR remains legal. The backend checks all enclosing loop
scopes, including nested loops and event-handler-local declarations, before
accepting output; an error publishes no partial HDL.

The independent lowering review matrix reproduces the original shared scalar
on the previous compiler and checks zero, one, and multiple occurrences, nested
generated loops, and declarations within a generated handler before and after
optimization on both targets. Positive controls preserve deliberately shared
root storage and ordinary loop-local storage within a handler.
