package nodal.internal.testkit

import nodal.*

final class NamingPoisonRoot extends Module:
  val source = in(Vec(SInt(2), 1))
  NamingControls.poison(this, source)

final class NamingRecoveryRoot extends Module:
  val source = in(SInt(2))
