package nodal.internal.testkit

import nodal.*

final class C2b2aLeaf extends Module:
  val required = ClockDomain.required("required")
  val data = in(SInt(2))

/** Keep the ordinary witness identical for calibration and charged failure. */
final class C2b2aRoot extends Module:
  val core = ClockDomain.external(
    "core",
    ClockEdge.Rising,
    ResetPolicy.Sync,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val leaf = new C2b2aLeaf
  val child = instance(leaf).domain(core)
  var input: Signal[Vec[SInt]] = null
  core:
    input = in(Vec(SInt(2), 1))
  val result = C2b2aControls.reserve(input)
  val later = if C2b2aControls.mode == "late_occurrence" then
    Some(instance(new C2b2aLeaf).domain(core))
  else None
  // A genuine root-owned allocation, deliberately NOT a child-owned type root.
  val lateWidth = if C2b2aControls.mode == "late_foreign_type_root" then Some(2.integer)
  else None
  C2b2aControls.exercise(this)
