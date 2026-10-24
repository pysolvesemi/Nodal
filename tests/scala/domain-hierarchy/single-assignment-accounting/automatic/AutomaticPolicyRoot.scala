package nodal.internal.testkit.automaticpolicy

import nodal.*

final class AutomaticPolicyRoot extends Module:
  AutomaticPolicy.bind(this)
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
  val result = AutomaticPolicy.complete(input)
