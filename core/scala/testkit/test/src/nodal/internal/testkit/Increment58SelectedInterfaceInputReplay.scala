package nodal.internal.testkit

import nodal.*
import nodal.internal.bridge.*
import nodal_external.*
import java.nio.file.{Files, Path}

/** Source capture is not native whole-family acceptance, especially for an entirely missing lane. */
object Increment58SelectedInterfaceInputReplay:
  def main(args: Array[String]): Unit =
    require(args.length == 2, "expected output directory and explicit mode")
    val mode = args(1)
    require(Set("two", "missing-lane", "missing-leaf", "missing-endpoint", "duplicate-alias",
      "wrong-type", "output-write", "foreign", "feedback", "observe")(mode))
    def expected(property: String): Path =
      val value = Option(System.getProperty(property)).getOrElse(
        throw new IllegalArgumentException("missing expected jar property: " + property)
      )
      Path.of(value).toRealPath()
    val apiJar = expected("nodal.selectedInterface.expectedApiJar")
    val bridgeJar = expected("nodal.selectedInterface.expectedBridgeJar")
    def assertOrigin(name: String, jar: Path): Unit =
      val protection = Option(Class.forName(name).getProtectionDomain).getOrElse(
        throw new IllegalStateException("missing protection domain: " + name)
      )
      val source = Option(protection.getCodeSource).getOrElse(
        throw new IllegalStateException("missing code source: " + name)
      )
      val location = Option(source.getLocation).getOrElse(
        throw new IllegalStateException("missing code source location: " + name)
      )
      val actual = Path.of(location.toURI).toRealPath()
      require(actual == jar, "unexpected runtime owner for " + name + ": " + actual)
      println("ORIGIN " + name + " " + actual)
    Vector(
      "ConstructionSession", "DigitalInterfaceContract", "KernelDigitalConnectivitySnapshot",
      "KernelInterfaceBoundarySnapshot", "KernelInterfaceDefinitionContextSnapshot",
      "KernelInterfaceMemberSnapshot", "KernelInterfaceRoleSnapshot", "KernelInterfaceSnapshot"
    ).foreach(name => assertOrigin("nodal." + name, apiJar))
    assertOrigin("nodal.internal.bridge.InterfaceMlir$", bridgeJar)
    val directory = Path.of(args(0))
    val _ = Files.createDirectories(directory)
    val snapshot = mode match
      case "missing-leaf" =>
        ConstructionKernel.inspect(new Increment58SelectedInterfaceInputExtraTwo)
      case "missing-endpoint" =>
        ConstructionKernel.inspect(new Increment58SelectedInterfaceInputEndpointTwo)
      case "output-write" =>
        ConstructionKernel.inspect(new Increment58SelectedInterfaceInputOutputTwo)
      case _ => ConstructionKernel.inspect(new Increment58SelectedInterfaceInputTwo(mode))
    val text = ScalaToMlirBridge.fromSnapshot(snapshot).text
    val _ = Files.writeString(directory.resolve(mode + ".mlir"), text)
    println("CAPTURE selected Interface input " + mode +
      "; native complete declared leaf/lane coverage and cycle checks still required")
