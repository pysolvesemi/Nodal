package nodal.internal.testkit
import nodal.*
import nodal_external.*
import nodal.internal.bridge.*
import java.nio.file.{Files, Path}
import java.security.MessageDigest

object Increment58GuardedLegacyNonunitSingleLaneReplay:
  private def runtimeProvenance(): Unit =
    def expected(property: String): Path =
      val value = Option(System.getProperty(property)).getOrElse(
        throw new IllegalArgumentException("missing expected jar property: " + property)
      )
      Path.of(value).toRealPath()
    val apiJar = expected("nodal.guardedLegacy.expectedApiJar")
    val bridgeJar = expected("nodal.guardedLegacy.expectedBridgeJar")
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
    Vector("ConstructionSession", "SemanticOriginBuilder")
      .foreach(name => assertOrigin("nodal." + name, apiJar))
    Vector("ScalaToMlirBridge$", "ReproducibilityContract$")
      .foreach(name => assertOrigin("nodal.internal.bridge." + name, bridgeJar))
    val builder = new SemanticOriginBuilder
    // Verified private final Path field in the exact compiled owner, not a guessed accessor.
    val field = classOf[SemanticOriginBuilder].getDeclaredField("repositoryRoot")
    field.setAccessible(true)
    val actual = field.get(builder).asInstanceOf[Path].toRealPath()
    require(actual == expected("nodal.guardedLegacy.expectedRoot"), "unexpected selected source root")
    println("SOURCE_ROOT " + actual)

  def main(args: Array[String]): Unit =
    runtimeProvenance()
    require(args.length == 1, "expected one output directory under .work")
    val output = Path.of(args(0)).toAbsolutePath.normalize
    require(output.startsWith(Path.of(".work").toAbsolutePath.normalize))
    val snapshot = ConstructionKernel.inspect(new Increment58GuardedLegacyNonunitSingleLaneSource)
    assert(snapshot.generatedRegions.size == 1 && snapshot.modules.size == 1)
    val generated = snapshot.generatedRegions.head
    val body = generated.digitalBody.get
    val outer = snapshot.structuralIfs.find(_.trueArm.path == generated.lexicalParent.get).get
    assert(generated.parent.isEmpty && body.parentRegion.contains(outer.trueArm.path))
    assert(generated.yieldedArea.isEmpty && generated.exports.isEmpty)
    assert(generated.declarations.isEmpty && snapshot.areas.isEmpty)
    assert(snapshot.digitalLoops.isEmpty && snapshot.eventProcesses.isEmpty)
    assert(snapshot.analogRegions.isEmpty && body.arguments.head.role == "induction")
    val text = ScalaToMlirBridge.fromSnapshot(snapshot).text
    assert(text.linesIterator.count(_.contains("\"nodal.generate\"(")) == 1)
    assert(!text.contains("\"nodal.hardware_loop\"("))
    assert(text == ScalaToMlirBridge.fromSnapshot(snapshot).text)
    val _ = Files.createDirectories(output)
    val path = output.resolve("guarded-nonunit-single-lane.mlir")
    val _ = Files.writeString(path, text)
    val sha = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))
      .map(value => f"${value & 0xff}%02x").mkString
    println(s"F58_GUARDED_LEGACY_NONUNIT_SINGLE_LANE $sha")
