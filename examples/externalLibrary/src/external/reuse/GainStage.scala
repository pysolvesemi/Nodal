package external.reuse

import nodal.*

/** Reusable module prototype authored only against the proposed public core API. */
final class GainStage(defaultGain: Expr[Real] = 2.0.real) extends Module:
  val source = input(Electrical)
  val sink = output(Electrical)
  val common = inout(Electrical)
  val gain = param(defaultGain)

  analog:
    V(sink, common) <+ gain * V(source, common)
