package nodal.internal.testkit
import nodal.*
final class EpochLeaf extends Module:
  val p = out(SInt(2))
  val q = out(SInt(3))
final class EpochRoot extends Module:
  val child = instance(new EpochLeaf)
  val first = child(_.p)
  EpochControls.exercise(this, child, first)
  def newAuthored(): Signal[SInt] = in(SInt(4))
