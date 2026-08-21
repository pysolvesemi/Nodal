package external.reuse

import nodal.*

/** Reusable module authored only against the frozen public core API. */
final class GainStage(defaultGain: Expr[Real] = 2.0.real) extends Module:
  val input = in(Electrical)
  val output = out(Electrical)
  val common = inout(Electrical)
  val gain = param(defaultGain)

  analog:
    V(output, common) <+ gain * V(input, common)
