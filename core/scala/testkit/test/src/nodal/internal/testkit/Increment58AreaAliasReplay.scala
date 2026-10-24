package nodal.internal.testkit

import nodal.*
import nodal.internal.bridge.*
import nodal_external.*
import java.nio.file.{Files, Path}

/** Internal binder stress, alongside the actual public plugin fixtures. */
final class Increment58AreaAliasPrioritySource extends Module:
  def helper(): Area =
    val local = new Area {}
    ConstructionKernel.bindArea("local", 1, local)
    ConstructionKernel.bindArea("local", 2, local)
    ConstructionKernel.bindArea("zeta", 0, local)
    ConstructionKernel.bindArea("zeta", 0, local)
    ConstructionKernel.bindArea("alpha", 0, local)
    // Distinct legal identifiers with the same JVM String hash.
    ConstructionKernel.bindArea("AaAa", 0, local)
    ConstructionKernel.bindArea("AaBB", 0, local)
    ConstructionKernel.bindArea("BBAa", 0, local)
    ConstructionKernel.bindArea("BBBB", 0, local)
    ConstructionKernel.bindArea("AaAa", 0, local)
    ConstructionKernel.bindArea("BBAa", 0, local)
    local
  val first = helper()
  // The caller's equal-priority name is deliberately an alias in this stress case.
  ConstructionKernel.bindArea("local", 2, first)

/** Native-process-free focused checks. These do not assert native semantic admission. */
object Increment58AreaAliasReplay:
  private def runtimeProvenance(): Unit =
    def expected(property: String): Path =
      val value = Option(System.getProperty(property)).getOrElse(
        throw new IllegalArgumentException("missing expected jar property: " + property)
      )
      Path.of(value).toRealPath()
    val apiJar = expected("nodal.areaAlias.expectedApiJar")
    val bridgeJar = expected("nodal.areaAlias.expectedBridgeJar")
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
    require(actual == expected("nodal.areaAlias.expectedRoot"), "unexpected selected source root")
    println("SOURCE_ROOT " + actual)

  private def artifact(name: String, text: String): Unit =
    val directory = Path.of(System.getProperty("nodal.areaAlias.outputDirectory"))
    val _ = Files.createDirectories(directory)
    val _ = Files.writeString(directory.resolve(name + ".mlir"), text)

  private def areaNames(snapshot: ConstructionSnapshot) = snapshot.names.filter(_.category == "area")

  def helpers(): Unit =
    val snapshot = ConstructionKernel.inspect(new Increment58AreaAliasSource)
    val repeat = ConstructionKernel.inspect(new Increment58AreaAliasSource)
    assert(snapshot.areas == repeat.areas && snapshot.names == repeat.names)
    assert(snapshot.sourceMap == repeat.sourceMap)
    assert(snapshot.modules.size == 1 && snapshot.areas.size == 6)
    assert(snapshot.modules.head.declarations.size == 7)
    assert(snapshot.digitalAssignments.size == 6)
    assert(!snapshot.areas.exists(_.path.endsWith(".local")))
    assert(areaNames(snapshot).size == 2)
    Vector("first", "second").foreach: name =>
      val path = snapshot.root + "." + name
      val area = snapshot.areas.find(_.path == path).get
      val entry = areaNames(snapshot).find(_.semanticPath == path).get
      assert(entry.name == name && entry.aliases == Vector("local"))
      assert(entry.source.contains(area.source) && entry.provenance == "scala-declaration")
      assert(!snapshot.sourceMap.exists(_.semanticPath == path))
      val nested = snapshot.areas.find(_.path == path + ".nested").get
      assert(nested.parent.contains(path))
      assert(area.members.exists(member => member.name == "nested" && member.target == nested.path))
      assert(area.members.find(_.name == "copied").get.target == snapshot.root + "." + name + "_copied")
    val inherited = snapshot.areas.find(_.path.endsWith(".inherited")).get
    assert(inherited.members.find(_.name == "copied").get.target ==
      inherited.members.find(_.name == "alias").get.target)

  def priorities(): Unit =
    val snapshot = ConstructionKernel.inspect(new Increment58AreaAliasPrioritySource)
    assert(snapshot.areas.size == 1)
    val entry = areaNames(snapshot).head
    assert(entry.name == "local")
    val collisions = Vector("AaAa", "AaBB", "BBAa", "BBBB")
    assert(collisions.map(_.hashCode).distinct.size == 1)
    assert(entry.aliases == collisions ++ Vector("alpha", "first", "zeta"))
    assert(entry.aliases.distinct == entry.aliases && !entry.aliases.contains(entry.name))
    assert(snapshot.areas.head.path == entry.semanticPath)
    assert(snapshot.modules.head.declarations.isEmpty && snapshot.digitalAssignments.isEmpty)
    assert(snapshot.sourceMap.forall(_.semanticPath != entry.semanticPath))

  def memory(): Unit =
    val aliased = ConstructionKernel.inspect(new Increment58AreaAliasMemorySource(true))
    val control = ConstructionKernel.inspect(new Increment58AreaAliasMemorySource(false))
    val path = aliased.root + ".memory_write_0"
    val entry = areaNames(aliased).find(_.semanticPath == path).get
    assert(entry.aliases == Vector("local"))
    assert(areaNames(control).isEmpty)
    assert(aliased.areas.size == 1 && control.areas.size == 1)
    assert(aliased.modules == control.modules)
    assert(aliased.digitalExpressions == control.digitalExpressions)
    assert(aliased.digitalAssignments == control.digitalAssignments)
    assert(aliased.sourceMap == control.sourceMap)
    assert(aliased.memoryWrites == control.memoryWrites)
    assert(aliased.memoryWrites.map(_.path) == Vector(path))
    // The actual auxiliary writer keeps this path. A source row for the Area
    // would collide here or force the writer's otherwise unnecessary renaming.
    assert(aliased.sourceMap.count(_.semanticPath == path) == 1)
    artifact("memory", ScalaToMlirBridge.fromSnapshot(aliased).text)
    val _ = ScalaToMlirBridge.fromSnapshot(control)

  def consumers(): Unit =
    val snapshot = ConstructionKernel.inspect(new Increment58AreaAliasMemorySource(true))
    val text = ScalaToMlirBridge.fromSnapshot(snapshot).text
    artifact("consumers", text)
    assert(text.contains("nodal.bridge.names"))
    assert(text.contains("category = \"area\"") && text.contains("aliases = [\"local\"]"))
    val original = ReproducibilityContract.describe(snapshot, Backend.Verilog)
    assert(original.manifest.text.contains("\"semantic_names\""))
    assert(original.manifest.text.contains("\"category\": \"area\""))
    assert(original.manifest.text.contains("\"local\""))
    val changed = snapshot.copy(names = snapshot.names.map: entry =>
      if entry.category == "area" then entry.copy(aliases = Vector("mutated_alias")) else entry
    )
    val mutation = ReproducibilityContract.describe(changed, Backend.Verilog)
    assert(changed.copy(names = snapshot.names) == snapshot)
    assert(original.sourceMlir != mutation.sourceMlir)
    assert(original.construction != mutation.construction)
    assert(original.manifest.sha256 != mutation.manifest.sha256)
    assert(mutation.manifest.text.contains("\"mutated_alias\""))
    // Restoring only names restores every deterministic generic artifact.
    assert(ReproducibilityContract.describe(changed.copy(names = snapshot.names), Backend.Verilog) == original)

  def resolver(): Unit =
    var aliases = Vector("local")
    var calls = 0
    var entered = false
    val source = SourceSpan("area-alias-resolver.scala", 1, 1, 1, 10)
    val builder = new SemanticOriginBuilder((paths, charge) =>
      assert(paths().isEmpty)
      calls += 1
      entered = true
      charge(7L)
      Vector(KernelNameSnapshot("top.first", "first", "area", "scala-declaration", Some(source), aliases))
    )
    val first = builder.resolve()
    assert(calls == 1 && first.names.head.aliases == Vector("local"))
    assert(first.sourceMap.isEmpty)
    Vector(false, true).foreach: construction =>
      def resolve(charge: Int => Unit): SemanticOriginResult =
        if construction then builder.resolveConstructionWithWork(charge)
        else builder.resolveWithWork(charge)
      var work = 0L
      assert(resolve(units => work += units) == first && work > 0L)
      entered = false
      val exhausted = new IllegalStateException("focused callback budget exhausted")
      val failure = scala.util.Try(resolve(_ =>
        if entered then throw exhausted
      )).failed.get
      assert(failure eq exhausted)
      assert(entered)
      assert(resolve(_ => ()) == first && builder.resolve() == first)
    // Same builder, later binding state: no cross-resolution alias cache.
    aliases = Vector("later", "local")
    val rebound = builder.resolve()
    assert(rebound.names.head.aliases == aliases && rebound.sourceMap.isEmpty)
    assert(builder.resolveWithWork(_ => ()) == rebound)
    assert(builder.resolveConstructionWithWork(_ => ()) == rebound)

  def main(args: Array[String]): Unit =
    runtimeProvenance()
    val selected = args.headOption.getOrElse("all")
    val checks = Vector(
      "helpers" -> (() => helpers()), "priorities" -> (() => priorities()),
      "memory" -> (() => memory()), "consumers" -> (() => consumers()),
      "resolver" -> (() => resolver())
    )
    require(selected == "all" || checks.exists(_._1 == selected))
    checks.filter(item => selected == "all" || item._1 == selected).foreach: (name, run) =>
      run()
      println(s"F58_AREA_ALIAS $name PASS (Scala/generic-only)")
