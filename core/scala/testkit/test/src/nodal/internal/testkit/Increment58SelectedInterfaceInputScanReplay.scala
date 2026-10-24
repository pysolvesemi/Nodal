package nodal.internal.testkit

import nodal.*
import nodal.internal.bridge.*
import nodal_external.*
import java.nio.file.Path

/** Tests the independent bridge scan bound, not source admission of a large design. */
object Increment58SelectedInterfaceInputScanReplay:
  def main(args: Array[String]): Unit =
    require(args.isEmpty, "expected no arguments; runtime jars use the ordinary origin properties")
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
    val snapshot = ConstructionKernel.inspect(new Increment58SelectedInterfaceInputTwo("observe"))
    val positive = ScalaToMlirBridge.fromSnapshot(snapshot)
    require(positive.text.contains("nodal.interface_observe"), "one-observer positive lost its effect")
    println("PASS default one-observer source and bridge prerequisite " + positive.sha256)
    val observations = snapshot.digitalConnectivity.filter(_.kind == "interface-observe")
    require(observations.size == 1, "expected exactly one actual authored observation")
    val observed = observations.head
    val endpoint = snapshot.interfaceContracts.find(_.members.exists(_.path == observed.target)).get
    require(endpoint.boundary.exists(value => value.instance.nonEmpty && value.selection.nonEmpty))
    require(observed.access.contains("in") && observed.member.contains("small") &&
      observed.value.isEmpty && observed.source.isEmpty && observed.enable.isEmpty &&
      observed.region.isEmpty && observed.sourceOrdinal.nonEmpty)
    require(snapshot.memoryWrites.isEmpty && snapshot.eventProcesses.isEmpty &&
      snapshot.structuralIfs.isEmpty && snapshot.behavioralIfs.isEmpty &&
      snapshot.enumSwitches.isEmpty && snapshot.flatFsms.isEmpty && snapshot.digitalLoops.isEmpty,
      "the scan control must retain the small ordinary fixture without additional ordered owners")
    val ordinals = snapshot.digitalConnectivity.flatMap(_.sourceOrdinal) ++
      snapshot.digitalAssignments.map(_.sourceOrdinal) ++
      snapshot.generatedRegions.flatMap(_.digitalBody.map(_.sourceOrdinal))
    val count = 17000
    val last = ordinals.max
    require(last >= 0 && last < Int.MaxValue - count)
    val appended = Vector.tabulate(count): index =>
      observed.copy(
        path = observed.owner + ".bridge_scan_observation_" + index,
        sourceOrdinal = Some(last + 1 + index)
      )
    val oldPaths = snapshot.digitalConnectivity.map(_.path).toSet
    require(appended.map(_.path).distinct.size == count &&
      appended.forall(value => !oldPaths(value.path)))
    require(appended.map(_.sourceOrdinal).distinct.size == count &&
      appended.forall(value => value.copy(path = observed.path,
        sourceOrdinal = observed.sourceOrdinal) == observed),
      "only immutable observation identity/order may change")
    val enlarged = snapshot.copy(digitalConnectivity = snapshot.digitalConnectivity ++ appended)
    require(enlarged.digitalConnectivity.size < 262144 &&
      enlarged.copy(digitalConnectivity = snapshot.digitalConnectivity) == snapshot)
    val failure = scala.util.Try(ScalaToMlirBridge.fromSnapshot(enlarged)).failed.get
    failure match
      case error: BridgeException =>
        require(error.diagnostic.code == "NODAL-BRIDGE-054-INTERFACE" &&
          error.diagnostic.message == "selected Interface input proof exceeds its 65536 scan-work bound",
          "unexpected earlier bridge refusal: " + error.getMessage)
        println("PASS bridge-only scan exhaustion after " + count + " appended nonwriter observations: " +
          error.diagnostic.code + ": " + error.diagnostic.message)
      case other => throw new IllegalStateException("unexpected non-bridge failure", other)
