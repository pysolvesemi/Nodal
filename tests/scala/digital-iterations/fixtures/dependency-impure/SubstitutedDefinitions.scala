package nodal.iteration.fixture

import nodal.*

final class SeparateChild extends Module:
  val p = inout(Electrical)

final class SeparateDefaultChild(gain: Param[Real] = 2.0) extends Module:
  println("substituted same-signature constructor host effect")
  val p = inout(Electrical)
  analog:
    I(p) <+ gain * V(p)
