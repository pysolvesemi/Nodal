package nodal.internal.testkit
import nodal.*
import nodal.internal.bridge.*
import nodal_external.*
import java.nio.file.{Files, Path}
object ReplayDuplicateSnapshot:
  def main(args: Array[String]): Unit =
    val snapshot = ConstructionKernel.inspect(new SelectedLiteralParameters)
    val forged = snapshot.copy(modules =
      snapshot.modules.map(module =>
        module.copy(instances =
          module.instances.map(instance =>
            instance.copy(parameterBindings = instance.parameterBindings :+ ("gain" -> "9.0"))
          )
        )
      )
    )
    val text = ScalaToMlirBridge.fromSnapshot(forged, Backend.VerilogA).text
    assert(text.contains("gain = 3.0 : f64, gain = 9.0 : f64"), text)
    val _ = Files.writeString(Path.of(args(0)), text)
    println("PASS forged duplicate transport preserves both bindings; native rejection required")
