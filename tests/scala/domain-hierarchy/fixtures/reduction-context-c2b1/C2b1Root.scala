package nodal.internal.testkit

import nodal.*

/** Independent root witness: no rewrite or wrapper of an accepted C2a witness. */
final class C2b1Root extends Module:
  val core = ClockDomain.external(
    "core",
    ClockEdge.Rising,
    ResetPolicy.Sync,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  var input: Signal[Vec[SInt]] = null
  core:
    input = in(Vec(SInt(2), 1))
  val result = C2b1Controls.reserve(input)
  C2b1Controls.exercise(this, input, result)
