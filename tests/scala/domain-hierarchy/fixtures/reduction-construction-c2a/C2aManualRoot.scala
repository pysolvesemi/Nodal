package nodal.internal.testkit

import nodal.*

final class C2aManualRoot extends Module:
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
  val result = C2aControls.reserveLocal(input)
  if C2aControls.scopedDrain then
    core:
      C2aControls.refuseScopedDrain(this)
  else C2aControls.completeBeforeFinish()
