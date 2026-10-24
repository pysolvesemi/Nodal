package nodal.internal.testkit
import nodal.*
object InterfaceEpochFactory:
  def capture(): ConstructionSnapshot = ConstructionKernel.inspect(new InterfaceEpochRoot)
