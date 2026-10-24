package nodal.internal.testkit
import nodal.*
object EpochFactories:
  def root(): ConstructionSnapshot = ConstructionKernel.inspect(new EpochRoot)
  def mapped(): ConstructionSnapshot = ConstructionKernel.inspect(new EpochMapRoot)
