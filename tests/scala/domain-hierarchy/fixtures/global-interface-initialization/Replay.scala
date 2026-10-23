package nodal.internal.testkit
import nodal.*
import nodal.internal.bridge.*
import nodal_external.*
object GlobalInitializationReplay:
  def main(args: Array[String]): Unit =
    require(args.isEmpty)
    for run <- 0 until 3 do
      val snapshot = ConstructionKernel.inspect(new GlobalInitializationSource)
      val text = ScalaToMlirBridge.fromSnapshot(snapshot).text
      require(text.contains("nodal.interface"))
      require(snapshot.interfaceContracts.forall(_.definitionContexts.isEmpty))
      println(s"PASS global $run")
    for source <- Vector[() => Module](() => new GlobalInitializationFactorySource) do
      val snapshot = ConstructionKernel.inspect(source())
      require(snapshot.interfaceContracts.exists(_.definitionContexts.nonEmpty))
      val _ = ScalaToMlirBridge.fromSnapshot(snapshot)
      println("PASS regional factory/local object")
    val local = ConstructionKernel.inspect(new GlobalInitializationLocalObjectSource)
    val _ = ScalaToMlirBridge.fromSnapshot(local)
    require(local.interfaceContracts.forall(_.definitionContexts.isEmpty))
    println("PASS module-local dependent object")
    GlobalInitializationRuntimeChecks.verify()
    var escaped: InterfaceType[GlobalInitializationLink] = null
    val _ = ConstructionKernel.capture(new GlobalInitializationFactorySource): root =>
      val area = root.lanes.generatedRegion.yieldedArea.get.value.get
      val field = area.getClass.getDeclaredFields.find(_.getName.endsWith("endpoint")).get
      field.setAccessible(true)
      escaped =
        field.get(area).asInstanceOf[InterfacePort[GlobalInitializationLink, SourceRole]].definition
      Vector.empty[AnyRef]
    val foreign = scala.util.Try(
      ConstructionKernel.inspect(new GlobalInitializationForeignSource(escaped))
    ).failed.get
    require(foreign.getMessage.contains("NODAL-INTERFACE-058-SCOPE"), foreign.getMessage)
    println("PASS foreign exact definition context: " + foreign.getMessage)
    val foreignType = scala.util.Try(
      ConstructionKernel.inspect(new GlobalInitializationForeignTypeSource(escaped))
    ).failed.get
    require(foreignType.isInstanceOf[ConstructionException] && foreignType.getMessage.contains("NODAL-DIGITAL-054-001") && foreignType.getMessage.contains("captured construction identity"), foreignType.getMessage)
    println("PASS foreign scoped type: " + foreignType.getMessage)
