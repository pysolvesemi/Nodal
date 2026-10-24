package nodal.internal.testkit
import nodal.*
object DescriptorBudgetFactory:
  def run(): ConstructionSnapshot = ConstructionKernel.inspect(new DescriptorBudgetRoot)
