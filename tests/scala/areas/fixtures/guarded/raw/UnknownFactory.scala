package nodal_external.guardedjar
import nodal.*
@ConstructorEffectSummary(version = 1, kind = "singleton", signature = "nodal_external.guardedjar.UnknownFactory$:java.lang.Object", profile = 4, dependencies = "")
object UnknownFactory:
  @ConstructorEffectSummary(version = 1, kind = "helper", signature = "nodal_external.guardedjar.UnknownFactory$.make(count:nodal.Expr[nodal.Integer]):nodal.GeneratedAreas[nodal_external.guardedjar.Leaf]", profile = 256, dependencies = "")
  def make(count: Expr[Integer]): GeneratedAreas[Leaf] =
    val _ = count
    scala.Predef.???
