package nodal.internal.testkit
import nodal.*
import nodal.internal.bridge.*
import nodal_external.*
object ReplayForgedParameters:
  def main(args: Array[String]): Unit =
    val snapshot = ConstructionKernel.inspect(new SelectedLiteralParameters)
    def declaration(change: KernelDeclarationSnapshot => KernelDeclarationSnapshot)
        : ConstructionSnapshot =
      snapshot.copy(modules =
        snapshot.modules.map(module =>
          module.copy(declarations =
            module.declarations.map(value =>
              if value.kind == "parameter" then change(value) else value
            )
          )
        )
      )
    def binding(change: KernelInstanceSnapshot => KernelInstanceSnapshot): ConstructionSnapshot =
      snapshot.copy(modules =
        snapshot.modules.map(module =>
          module.copy(instances = module.instances.map(change))
        )
      )
    def attribute(key: String, value: String): ConstructionSnapshot = declaration(parameter =>
      parameter.copy(attributes = parameter.attributes.filterNot(_._1 == key) :+ (key -> value))
    )
    val cases = Vector(
      "wrong-type" -> declaration(_.copy(dataType = Some("Integer"))),
      "unit-default" -> attribute("unit", "V"),
      "structural-parameter" -> attribute("classification", "structural"),
      "expression-default" -> attribute("default", "unowned.default.expression"),
      "nonfinite-default" -> attribute("default", "NaN"),
      "foreign-binding-name" -> binding(_.copy(parameterBindings = Vector("foreignGain" -> "3.0"))),
      "foreign-binding-expression" ->
        binding(_.copy(parameterBindings = Vector("gain" -> "Foreign.gain"))),
      "nonfinite-binding" -> binding(_.copy(parameterBindings = Vector("gain" -> "Infinity")))
    )
    cases.foreach: (name, forged) =>
      val result = scala.util.Try(ScalaToMlirBridge.fromSnapshot(forged, Backend.VerilogA))
      assert(result.isFailure, name)
      println("PASS " + name + ": " + result.failed.get.toString)
