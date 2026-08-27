# Increment 24 — Minimal analog expression and contribution IR

Increment 24 extends the private `nodal` MLIR dialect with the first executable
analog equation vocabulary while leaving public API v0.3 and backend spelling
unchanged.

## IR vocabulary

- `nodal.analog`: one continuous-time equation region per semantic block.
- `nodal.real_literal`: finite f64 constant.
- `nodal.parameter_ref`: direct reference to an enclosing real parameter.
- `nodal.analog_add`, `analog_sub`, `analog_mul`, `analog_div`: ordered f64
  expression nodes.
- `nodal.analog_ddt`: continuous-time derivative identity.
- `nodal.contribute`: explicit potential or flow contribution to a conservative
  branch.

The existing `nodal.terminal`, `nodal.node`, `nodal.branch`, and `nodal.access`
operations provide electrical topology and potential/flow sensing. The RC
fixture represents `I(branch) = V(branch)/R + C*ddt(V(branch))` without relying
on Verilog-A text as an intermediate semantic format.

## Verification

Operation-local verification checks region shape, legal children, finite
literals, parameter resolution and type, f64 arithmetic, valid derivative
placement, branch discipline, and contribution kind. The Increment 21 analog
and target-capability stages recognize every new operation. Native typed unit,
custom/generic round-trip, negative parameter/contribution, checker mutation,
formatting, and locked-toolchain tests provide closure evidence.

Backend emission remains fail-closed for the new operations until Increment 25
adds the complete RC lowering and exact Verilog-A golden.
