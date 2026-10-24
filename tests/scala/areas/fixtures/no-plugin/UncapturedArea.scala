package nodal

final class UncapturedAreaModule extends Module:
  val group = new Area:
    val target = wire(Bool)

object UncapturedArea:
  def main(args: Array[String]): Unit =
    val _ = args
    val failure = scala.util.Try(ConstructionKernel.inspect(new UncapturedAreaModule)).failed.get
    assert(failure.getMessage.contains("NODAL-AREA-058-MISSING"))
    println("AREA_NO_PLUGIN_REJECTED")
