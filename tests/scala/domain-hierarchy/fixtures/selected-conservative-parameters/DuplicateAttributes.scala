package nodal.internal.testkit
import nodal.*
import nodal.internal.bridge.*
import nodal_external.*
object ReplayDuplicateAttributes:
  def main(args: Array[String]): Unit =
    val snapshot = ConstructionKernel.inspect(new SelectedLiteralParameters)
    Vector(
      "default" -> "NaN",
      "default" -> "(1.0 + 1.0)",
      "unit" -> "V",
      "classification" -> "structural"
    ).foreach: (key, hidden) =>
      Vector("unsupported-first", "unsupported-last").foreach: order =>
        val forged = snapshot.copy(modules =
          snapshot.modules.map(module =>
            module.copy(declarations =
              module.declarations.map(parameter =>
                if parameter.kind != "parameter" then parameter
                else
                  val valid = parameter.attributes.find(_._1 == key).map(_._2).getOrElse(if key ==
                      "classification"
                  then "ordinary"
                  else "")
                  val pair = Vector(key -> hidden, key -> valid)
                  parameter.copy(attributes =
                    parameter.attributes.filterNot(_._1 == key) ++
                      (if order == "unsupported-first" then pair else pair.reverse)
                  )
              )
            )
          )
        )
        val result = scala.util.Try(ScalaToMlirBridge.fromSnapshot(forged, Backend.VerilogA).text)
        val beforeAdmitted = args.headOption.contains("before") && order == "unsupported-first"
        assert(result.isSuccess == beforeAdmitted, s"$key $hidden $order: $result")
        if result.isFailure then
          val expected = if hidden == "(1.0 + 1.0)" && order == "unsupported-last" then
            "NODAL-BRIDGE-021: unsupported real parameter default"
          else if key == "classification" && order == "unsupported-last" then
            "NODAL-BRIDGE-043: structural parameters currently require Integer type"
          else "ordinary dimensionless Real literals"
          assert(result.failed.get.toString.contains(expected), result.failed.get.toString)
        println(s"PASS duplicate $key $hidden $order admitted=$beforeAdmitted")
