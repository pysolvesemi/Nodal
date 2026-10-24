package nodal.internal.testkit

import nodal.*

object C2aFactory:
  def capture(): ConstructionSnapshot = ConstructionKernel.inspect(new C2aRoot)
  def manual(): ConstructionSnapshot = ConstructionKernel.inspect(new C2aManualRoot)
  def assigned(): ConstructionSnapshot = ConstructionKernel.inspect(new C2aAssignedRoot)
