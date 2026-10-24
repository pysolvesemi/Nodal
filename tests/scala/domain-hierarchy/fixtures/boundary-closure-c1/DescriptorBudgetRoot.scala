package nodal.internal.testkit
import nodal.*
final class DescriptorBudgetRoot extends Module:
  val p = in(SInt(2))
  DescriptorBudgetControls.exercise(this)
