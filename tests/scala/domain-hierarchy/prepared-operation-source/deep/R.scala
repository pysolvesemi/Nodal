package nodal

final class L extends Module:
  val required = ClockDomain.required("required")
  val data = in(SInt(2))

/** Source-changing driven successor: one assignment, no added declarations or expressions. */
final class R extends Module:
  F.bind(this)
  val core = ClockDomain.external(
    "core",
    ClockEdge.Rising,
    ResetPolicy.Sync,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val leaf = new L
  val child = instance(leaf).domain(core)
  var input: Signal[Vec[SInt]] = null
  core:
    input = in(Vec(SInt(2), 1))
  val result = F.reserve(input)
  F.exercise(this)

  var unwound = 0
  def driveCompletedScalar(proxy: Signal[SInt], depth: Int): Unit =
    if depth == 0 then proxy := result.asInstanceOf[Expr[SInt]]
    else
      driveCompletedScalar(proxy, depth - 1)
      unwound += 1
  def probeDepth(depth: Int): Unit =
    if depth == 0 then F.probe()
    else
      probeDepth(depth - 1)
      unwound += 1
