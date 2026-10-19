package nodal.iteration.fixture

import nodal.*

@ConstructorSchema(version = 1, encoded = "")
final class SeparateChild extends Module:
  val p = inout(Electrical)

@ConstructorSchema(version = 1, encoded = "gain:4000000000000000")
final class SeparateDefaultChild(gain: Param[Real] = 2.0) extends Module:
  val p = inout(Electrical)
  analog:
    I(p) <+ gain * V(p)
