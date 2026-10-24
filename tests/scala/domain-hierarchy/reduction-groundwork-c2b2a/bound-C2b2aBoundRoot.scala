package nodal.internal.testkit

import nodal.*

final class C2b2aBoundLeaf extends Module:
  val required = ClockDomain.required("required")
  val data = in(SInt(2))

/** One real root, one real required-domain child, and no public selector callback. */
final class C2b2aBoundRoot extends Module:
  val core = ClockDomain.external(
    "core",
    ClockEdge.Rising,
    ResetPolicy.Sync,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val leaf = new C2b2aBoundLeaf
  val child = instance(leaf).domain(core)
  var input: Signal[Vec[SInt]] = null
  core:
    input = in(Vec(SInt(2), 1))
  val result = C2b2aBoundControls.reserve(input)
  C2b2aBoundControls.exercise(this)

/** Fresh ordinary recovery is not a second bound-profile publication/budget claim. */
final class C2b2aBoundOrdinary extends Module:
  C2b2aBoundControls.ordinaryRecovery()
