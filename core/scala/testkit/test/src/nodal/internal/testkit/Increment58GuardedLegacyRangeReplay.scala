package nodal.internal.testkit
import nodal.*
import nodal_external.*
import nodal.internal.bridge.*
import java.nio.file.{Files, Path}
import java.security.MessageDigest

object Increment58GuardedLegacyRangeReplay:
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

  private def checked(make: () => Module, ordered: Boolean): String =
    val snapshot = ConstructionKernel.inspect(make())
    assert(snapshot.generatedRegions.size == 1)
    val generated = snapshot.generatedRegions.head
    val body = generated.digitalBody.get
    val outer = snapshot.structuralIfs.find(_.trueArm.path == generated.lexicalParent.get).get
    assert(generated.parent.isEmpty)
    assert(body.parentRegion.contains(outer.trueArm.path))
    assert(generated.yieldedArea.isEmpty && generated.exports.isEmpty)
    assert(generated.declarations.isEmpty && snapshot.areas.isEmpty)
    assert(snapshot.modules.size == 1 && snapshot.eventProcesses.isEmpty)
    assert(snapshot.digitalLoops.isEmpty && snapshot.analogRegions.isEmpty)
    assert(snapshot.digitalExpressions.forall(_.generatedMember.isEmpty))
    assert(body.arguments.head.role == "induction")
    val text = ScalaToMlirBridge.fromSnapshot(snapshot).text
    val lines = text.linesIterator.toVector
    val generates = lines.filter(_.contains("\"nodal.generate\"("))
    assert(generates.size == 1 && generates.head.trim.startsWith("\"nodal.generate\"("))
    assert(!text.contains("\"nodal.hardware_loop\"("))
    assert(!text.contains("\"nodal.hardware_loop_end\"("))
    assert(!text.contains("\"nodal.generated_export\"("))
    assert(text.contains(s"induction_path = \"${generated.induction}\""))
    if ordered then
      assert(outer.trueArm.arguments.exists(arg =>
        arg.role == "static_capture" && arg.path == generated.upperExclusive))
      assert(body.captures.nonEmpty)
      val writes = snapshot.digitalAssignments.filter(_.region.contains(outer.trueArm.path))
        .sortBy(_.sourceOrdinal)
      assert(writes.size == 2)
      assert(writes.head.sourceOrdinal < body.sourceOrdinal)
      assert(body.sourceOrdinal < writes.last.sourceOrdinal)
      def position(path: String): Int = lines.indexWhere(line =>
        line.contains("\"nodal.digital_assign\"(") &&
          line.contains(s"semantic_path = \"$path\""))
      val generatePosition = lines.indexWhere(_.contains("\"nodal.generate\"("))
      assert(position(writes.head.path) >= 0)
      assert(position(writes.head.path) < generatePosition)
      assert(generatePosition < position(writes.last.path))
      val inner = snapshot.structuralIfs.find(_.parentRegion.contains(generated.path)).get
      assert(inner.trueArm.yieldValue.nonEmpty && inner.falseArm.yieldValue.nonEmpty)
    assert(text == ScalaToMlirBridge.fromSnapshot(snapshot).text)
    text

  def main(args: Array[String]): Unit =
    runtimeProvenance()
    require(args.length == 1, "expected one output directory under .work")
    val output = Path.of(args(0)).toAbsolutePath.normalize
    require(output.startsWith(Path.of(".work").toAbsolutePath.normalize))
    val _ = Files.createDirectories(output)
    val profiles = Vector[(String, () => Module, Boolean)](
      ("guarded-default-zero", () => new Increment58GuardedLegacyRangeSource(0), true),
      ("guarded-default-one", () => new Increment58GuardedLegacyRangeSource(1), true),
      ("guarded-nonunit", () => new Increment58GuardedLegacyRangeSource(0, 1, 2), true),
      ("guarded-empty-range", () => new Increment58GuardedLegacyEmptyRangeSource, false),
      ("guarded-empty-clock", () => new Increment58GuardedLegacyEmptyClockSource, false))
    profiles.foreach: (name, make, ordered) =>
      val path = output.resolve(name + ".mlir")
      val _ = Files.writeString(path, checked(make, ordered))
      val sha = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))
        .map(value => f"${value & 0xff}%02x").mkString
      println(s"F58_GUARDED_LEGACY_RANGE_SOURCE $name $sha")
    val runtimeBound = scala.util.Try(ConstructionKernel.inspect(
      new Increment58GuardedLegacyRuntimeBound))
    assert(runtimeBound.isFailure)
    assert(runtimeBound.failed.get.getMessage.contains("NODAL-ITERATION-043-001"))
    assert(runtimeBound.failed.get.getMessage.contains(
      "hdlRange bound must be a static Integer expression"))
    val foreignBound = scala.util.Try(ConstructionKernel.inspect(
      new Increment58GuardedLegacyForeignBound))
    assert(foreignBound.isFailure)
    assert(foreignBound.failed.get.getMessage.contains("NODAL-ITERATION-043-001"))
    assert(foreignBound.failed.get.getMessage.contains(
      "must be owned by the active Module"))
    val invalid = scala.util.Try(ConstructionKernel.inspect(
      new Increment58GuardedLegacyInvalidAlternative))
    assert(invalid.isFailure && invalid.failed.get.getMessage.contains("same complete hardware ABI"))
    var original = ""
    val caught = scala.util.Try(ConstructionKernel.inspect:
      val module = new Increment58GuardedLegacyCaughtFailure
      original = module.caught.failed.get.getMessage
      module)
    assert(original.contains("same complete hardware ABI"))
    assert(caught.isFailure && caught.failed.get.getMessage.contains("NODAL-CONSTRUCTOR-LIFECYCLE-021"))
    val _ = checked(() => new Increment58GuardedLegacyRangeSource(0), true)
    // Existing predecessor source classes must be plugin-compiled from canonical copies.
    Vector[(String, () => Module)](
      "root-legacy-predecessor" -> (() => new PublicStructuralGeneratedValue),
      "guarded-area-predecessor" -> (() => new Increment58GuardedFamilySource)
    ).foreach: (name, make) =>
      val snapshot = ConstructionKernel.inspect(make())
      assert(snapshot.generatedRegions.size == 1 && snapshot.digitalLoops.isEmpty)
      val _ = Files.writeString(output.resolve(name + ".mlir"),
        ScalaToMlirBridge.fromSnapshot(snapshot).text)
