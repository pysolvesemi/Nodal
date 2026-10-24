package nodal_external.guardedjar
import nodal.*
object ForgedSummary:
  @ConstructorEffectSummary(version = 1, kind = "helper", signature = "forged", profile = 128, dependencies = "")
  def make(count: Expr[Integer]): GeneratedAreas[Leaf] =
    Factory.make(count)
