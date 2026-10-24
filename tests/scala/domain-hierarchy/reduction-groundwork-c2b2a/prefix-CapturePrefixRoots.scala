package nodal.internal.testkit

import nodal.*

/** Actual constructor/session controls. No authored-output or final-names-budget claim. */
final class CapturePrefixRoot extends Module:
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
  val result = CapturePrefixSessionControls.reserve(input)
  CapturePrefixSessionControls.construction(this)

/** Real C1 owner without any registered construction intent. */
final class CapturePrefixC1Root extends Module:
  CapturePrefixSessionControls.c1(this)

/** A fresh ordinary Session must remain owner-free before and after source capture. */
final class CapturePrefixOrdinaryRoot extends Module:
  CapturePrefixSessionControls.ordinary(this)
