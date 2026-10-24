package nodal.internal.testkit.drivendiagnostic

import nodal.*

final class C2b2aLeaf extends Module:
  val required = ClockDomain.required("required")
  val data = in(SInt(2))

/** Source-changing driven successor: one assignment, no added declarations or expressions. */
final class C2b2aRoot extends Module:
  PreparedTokenControls.bind(this)
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
  val result = PreparedTokenControls.reserve(input)
  PreparedTokenControls.exercise(this)

  def driveCompletedScalar(proxy: Signal[SInt]): Unit =
    proxy := result.asInstanceOf[Expr[SInt]]
