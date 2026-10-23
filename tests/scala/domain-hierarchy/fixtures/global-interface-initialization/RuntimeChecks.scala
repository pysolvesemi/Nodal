package nodal
import nodal_external.*
import scala.collection.mutable

/** White-box transaction test using the two actual registered source regions, not new source
  * permission.
  */
object GlobalInitializationRuntimeChecks:
  def verify(): Unit =
    val _ = ConstructionKernel.capture(new GlobalInitializationTwoRegionsSource): root =>
      val active = ConstructionKernel.getClass.getDeclaredMethod("active")
      active.setAccessible(true)
      val session = active.invoke(ConstructionKernel).asInstanceOf[Option[ConstructionSession]].get
      val stackField =
        session.getClass.getDeclaredFields.find(_.getName.endsWith("collectionStack")).get
      stackField.setAccessible(true)
      val stack =
        stackField.get(session).asInstanceOf[mutable.ArrayBuffer[DigitalValueRegionRecord]]
      val before = stack.toVector
      val first = root.first.generatedRegion.digitalRegion.get
      val second = root.second.generatedRegion.digitalRegion.get
      val counts = Vector(first, second).map(region => region -> region.assignmentCount)
      val marker = new IllegalStateException("initializer test")
      try
        stack += first
        val failure = scala.util.Try(session.initializeGlobalInterface(() => {
          require(session.interfaceDefinitionContext().isEmpty)
          stack += second
          try require(session.interfaceDefinitionContext().exists(_.region eq second))
          finally stack.remove(stack.size - 1)
          val nested =
            scala.util.Try(session.initializeGlobalInterface(() => throw marker)).failed.get
          require(nested eq marker)
          require(session.interfaceDefinitionContext().isEmpty)
          throw marker
        })).failed.get
        require(failure eq marker)
        require(session.interfaceDefinitionContext().exists(_.region eq first))
        println("PASS exact region, nested restoration, exception restoration")
      finally
        stack.clear(); stack ++= before
        counts.foreach((region, count) => region.assignmentCount = count)
      Vector.empty[AnyRef]
