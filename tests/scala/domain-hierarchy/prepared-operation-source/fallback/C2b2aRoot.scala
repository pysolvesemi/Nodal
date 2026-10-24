package nodal.internal.testkit.drivendiagnostic

import nodal.*

final class C2b2aLeaf extends Module:
  val required = ClockDomain.required("required")
  val data = in(SInt(2))

/** Source-changing driven successor: one assignment, no added declarations or expressions. */
final class C2b2aRoot extends Module:
  TokenFallbackControls.bind(this)
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
  val result = TokenFallbackControls.reserve(input)
  TokenFallbackControls.exercise(this)

  var unwound = 0
  def driveCompletedScalar(proxy: Signal[SInt], depth: Int): Unit =
    if depth == 0 then proxy := result.asInstanceOf[Expr[SInt]]
    else
      driveCompletedScalar(proxy, depth - 1)
      unwound += 1
  def probeDepth(depth: Int): Unit =
    if depth == 0 then TokenFallbackControls.probe()
    else
      probeDepth(depth - 1)
      unwound += 1
