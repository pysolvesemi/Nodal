package nodal.internal.testkit

import java.nio.file.{Files, Paths}
import scala.jdk.CollectionConverters.*
import nodal.*
import nodal.internal.bridge.*
import nodal_external.Increment58AggregateHierarchySource
import utest.*

object Increment58AggregateHierarchyTests extends TestSuite:
  val tests = Tests:
    test("nested named fields retain exact dependent widths and dimension groups"):
      val documents = Vector(false, true).map: late =>
        val snapshot = ConstructionKernel.inspect(new Increment58AggregateHierarchySource(late))
        val root = snapshot.modules.find(_.path == snapshot.root).get
        val projected = root.declarations.filter(_.instancePort.nonEmpty)
        assert(projected.size == 4)
        projected.foreach: declaration =>
          val name = declaration.instancePort.get.port.split("\\.").last
          val expected = root.declarations.find(_.name == name).get.dataType
          assert(declaration.dataType == expected)
        ScalaToMlirBridge.fromSnapshot(snapshot)
      assert(documents.head.text == documents.last.text)

    test("a renamed nested field cannot masquerade as the declared child ABI"):
      val snapshot = ConstructionKernel.inspect(new Increment58AggregateHierarchySource)
      val forged = snapshot.copy(modules = snapshot.modules.map: module =>
        if module.path != snapshot.root then module
        else
          module.copy(declarations = module.declarations.map: declaration =>
            if declaration.instancePort.isEmpty then declaration
            else
              declaration.copy(dataType = declaration.dataType.map(_.replace("samples", "other")))))
      val failure = scala.util.Try(ScalaToMlirBridge.fromSnapshot(forged)).failed.get
      assert(failure.isInstanceOf[BridgeException])

object Increment58AggregateHierarchyReplay:
  def main(args: Array[String]): Unit =
    require(args.length == 1, "expected one output directory under .work")
    val directory = Paths.get(args(0)).toAbsolutePath.normalize
    require(directory.iterator().asScala.exists(_.toString == ".work"))
    val _ = Files.createDirectories(directory)
    for late <- Vector(false, true) do
      val profile = if late then "aggregate-late" else "aggregate-early"
      val document = ScalaToMlirBridge.fromSnapshot(
        ConstructionKernel.inspect(new Increment58AggregateHierarchySource(late))
      )
      val _ = Files.writeString(directory.resolve(profile + ".mlir"), document.text)
      println(s"F58_AGGREGATE_HIERARCHY_SOURCE $profile ${document.sha256}")
