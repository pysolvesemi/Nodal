package nodal.internal.testkit

import java.nio.file.{Files, Path, StandardOpenOption}
import java.security.MessageDigest
import nodal.*
import nodal.internal.bridge.*
import nodal_external.*

/** Focused source replay after the shared source-projection preflight, never a fallback. */
object Increment58SymbolicConservativeArrayReplay:
  private val sourcePath =
    "core/scala/testkit/test/src/nodal_external/Increment58SymbolicConservativeArraySource.scala"
  private val fixedSourcePath =
    "core/scala/testkit/test/src/nodal_external/Increment58ConservativeArraySource.scala"

  private def runtimeProvenance(): Unit =
    def expected(property: String): Path =
      Path.of(Option(System.getProperty("nodal.symbolicArray." + property)).getOrElse(
        throw new IllegalArgumentException("missing runtime origin property: " + property)
      )).toRealPath()
    def origin(name: String, property: String): Unit =
      val actual = Path.of(Class.forName(name).getProtectionDomain.getCodeSource.getLocation.toURI)
        .toRealPath()
      require(actual == expected(property), "unexpected runtime owner for " + name + ": " + actual)
      println("ORIGIN " + name + " " + actual)
    Vector("nodal.ConstructionSession", "nodal.SemanticOriginBuilder")
      .foreach(origin(_, "expectedConstructionApiJar"))
    origin("nodal.DigitalInterfaceContract$", "expectedApiJar")
    origin("nodal.internal.bridge.InterfaceMlir$", "expectedBridgeJar")
    val field = classOf[SemanticOriginBuilder].getDeclaredField("repositoryRoot")
    field.setAccessible(true)
    val root = field.get(new SemanticOriginBuilder).asInstanceOf[Path].toRealPath()
    require(root == expected("expectedRoot"), "unexpected runtime source root")
    require(Files.isRegularFile(root.resolve(sourcePath)))
    require(Files.isRegularFile(root.resolve(fixedSourcePath)))
    println("SOURCE_ROOT " + root)

  private def count(text: String, token: String): Int = text.sliding(token.length).count(_ == token)

  private def checked(
      snapshot: ConstructionSnapshot, instances: Int, symbolic: Boolean, artifact: Path
  ): String =
    val root = snapshot.modules.find(_.path == snapshot.root).get
    assert(root.instances.size == instances)
    assert(snapshot.modules.size == instances + 1)
    assert(snapshot.generatedRegions.isEmpty && snapshot.areas.isEmpty)
    assert(snapshot.shapeIndices.isEmpty && snapshot.shapeViews.isEmpty)
    val connections = snapshot.digitalConnectivity.filter(_.kind == "interface-connect")
    assert(connections.size == instances * 2)
    assert(connections.flatMap(_.member).toSet == Set("pair.p", "pair.n"))
    assert(connections.forall(value => value.region.isEmpty && value.sourceOrdinal.isEmpty))
    assert(snapshot.interfaceContracts.forall(endpoint =>
      endpoint.dimensions.size == 1 && endpoint.members.map(_.name) == Vector("pair.p", "pair.n")))
    if symbolic then
      val query = new StructuralParameterBinding.Query(snapshot)
      val parent = root.declarations.find(_.name == "p").get
      val expected = query.resolve(root.path, parent.path)
      assert(query.bounds(expected) == IterationDomain.Bounds(1L, 131072L))
      assert(snapshot.interfaceContracts.filter(_.owner == root.path).forall(endpoint =>
        query.resolve(root.path, endpoint.dimensions.head) == expected))
      assert(snapshot.interfaceContracts.forall(_.dimensions.head.toIntOption.isEmpty))
      snapshot.interfaceContracts.filter(_.boundary.nonEmpty).foreach: endpoint =>
        val boundary = endpoint.boundary.get
        StructuralParameterBinding.validateInterfaceDimensions(
          snapshot, endpoint.owner, boundary.instance.get, boundary.endpoint, endpoint.dimensions
        )
    val text = ScalaToMlirBridge.fromSnapshot(snapshot, Backend.VerilogA).text
    // Persist genuine emitted evidence before checking target-format-specific details.
    val _ = Files.writeString(artifact, text, StandardOpenOption.CREATE_NEW)
    val snapshotFile = artifact.resolveSibling(artifact.getFileName.toString.stripSuffix(".mlir") + ".snapshot.json")
    val _ = Files.writeString(snapshotFile, ReproducibilityContract.canonicalSnapshot(snapshot),
      StandardOpenOption.CREATE_NEW)
    assert(text.contains(if symbolic then sourcePath else fixedSourcePath))
    assert(count(text, "\"nodal.connect_array\"") == instances * 2)
    assert(count(text, "\"nodal.module\"") == 2)
    assert(!text.contains("\"nodal.interface_connect\""))
    assert(!text.contains("\"nodal.shape_index\""))
    assert(!text.contains("\"nodal.generate\""))
    if symbolic then
      assert(text.contains("\"nodal.parameter_envelope\""))
      assert(text.contains("!nodal.shaped<\"p\","))
      // The unaccessed child formal has only declaration metadata before native adaptation.
      val formalDeclarations = text.linesIterator.filter(_.contains("\"nodal.interface_instance\""))
      assert(formalDeclarations.exists(_.contains("dimensions = [\"n\"]")))
      snapshot.interfaceContracts.filter(_.owner != root.path).foreach: endpoint =>
        val owner = snapshot.modules.find(_.path == endpoint.owner).get
        val formal = owner.declarations.find(_.name == "n").get
        val query = new StructuralParameterBinding.Query(snapshot)
        assert(query.resolve(owner.path, endpoint.dimensions.head) == query.resolve(owner.path, formal.path))
    if instances == 2 then
      val refs = text.linesIterator.filter(_.contains("\"nodal.instance\"")).map(line =>
        """module = (@[^, >]+)""".r.findFirstMatchIn(line).get.group(1)).toVector
      assert(refs.size == 2 && refs.distinct.size == 1)
      assert(root.instances.map(_.path).distinct.size == 2)
      assert(root.instances.map(_.childModule).distinct.size == 2)
    assert(text == ScalaToMlirBridge.fromSnapshot(snapshot, Backend.VerilogA).text)
    text

  private def sourceRejected(name: String, build: () => Module, causes: String*): Unit =
    val result = scala.util.Try(ConstructionKernel.inspect(build()))
    assert(result.isFailure, name + " was admitted")
    val failure = result.failed.get
    val expectedType = if name == "formal-range" then
      failure.getClass == classOf[IllegalArgumentException] && failure.getMessage ==
        "NODAL-SHAPE-043-007: actual Integer envelope escapes formal 'SymbolicConservativeArrayTop.child.n' range"
    else failure.isInstanceOf[ConstructionException]
    assert(expectedType, failure)
    assert(causes.exists(failure.getMessage.contains),
      name + " failed at an unrelated barrier: " + failure.getMessage)
    println("PASS source-causal " + name + " " + failure.getMessage)

  private def bridgeControls(original: ConstructionSnapshot): Unit =
    val edge = original.digitalConnectivity.find(_.kind == "interface-connect").get
    val endpoint = original.interfaceContracts.find(_.boundary.nonEmpty).get
    val root = original.modules.find(_.path == original.root).get
    val parent = root.declarations.find(_.name == "p").get
    def parentAttributes(change: Vector[(String, String)] => Vector[(String, String)]) =
      original.copy(modules = original.modules.map(module => module.copy(declarations =
        module.declarations.map(value =>
          if value.path == parent.path then value.copy(attributes = change(value.attributes))
          else value))))
    def overrideActual(value: String) = original.copy(modules = original.modules.map(module =>
      if module.path != root.path then module
      else module.copy(instances = module.instances.map(instance => instance.copy(
        parameterBindings = instance.parameterBindings.map((name, old) =>
          name -> (if name == "n" then value else old)))))))
    val changedDimension = original.copy(
      interfaceContracts = original.interfaceContracts.map(value =>
        if value.path == endpoint.path then value.copy(dimensions = Vector("65536")) else value),
      modules = original.modules.map(module => module.copy(declarations =
        module.declarations.map(value =>
          if value.path == endpoint.path then value.copy(attributes = value.attributes.map(
            (key, old) => key -> (if key == "count" then "65536" else old))) else value))))
    val forged = Vector(
      "missing-leaf" -> original.copy(digitalConnectivity = original.digitalConnectivity.filterNot(_ == edge)),
      "duplicate-leaf" -> original.copy(digitalConnectivity = original.digitalConnectivity :+
        edge.copy(path = edge.path + "_duplicate")),
      "default-is-not-shape" -> changedDimension,
      "equal-default-distinct-identity" -> overrideActual("other"),
      "foreign-operand" -> overrideActual(root.instances.head.childModule + ".n"),
      "missing-upper-constraint" -> parentAttributes(_.filterNot(_._1 == "integer_range_upper")),
      "duplicate-constraint" -> parentAttributes(values => values :+ values.find(_._1 == "integer_range_lower").get),
      "nonpositive-constraint" -> parentAttributes(_.map((key, old) =>
        key -> (if key == "integer_range_lower" then "0" else old)))
    )
    forged.foreach: (name, snapshot) =>
      val result = scala.util.Try(ScalaToMlirBridge.fromSnapshot(snapshot, Backend.VerilogA))
      assert(result.isFailure, "forged " + name + " was admitted")
      assert(result.failed.get.isInstanceOf[BridgeException], result.failed.get)
      val expected = name match
        case "missing-leaf" | "duplicate-leaf" =>
          Vector("conservative forwarding must cover each ordered member exactly once")
        case "default-is-not-shape" =>
          Vector("Interface projection changes exact owner", "Interface array count is not the exact finalized child expression")
        case "equal-default-distinct-identity" =>
          Vector("Interface array dimensions differ from the exact occurrence", "Interface array count is not the exact finalized child expression")
        case "foreign-operand" =>
          Vector("NODAL-SHAPE-043-007: Integer dependency 'SymbolicConservativeArrayTop.child.n' has no retained identity")
        case "missing-upper-constraint" =>
          Vector("range requires both bounds", "Interface projection changes exact owner")
        case "duplicate-constraint" =>
          Vector("duplicate parameter attribute", "Interface projection changes exact owner")
        case "nonpositive-constraint" =>
          Vector("positive", "Interface projection changes exact owner")
        case _ => Vector.empty
      val message = result.failed.get.getMessage
      assert(expected.exists(message.contains), name + " failed at an unrelated barrier: " + message)
      println("PASS bridge-causal " + name + " " + message)

  def main(args: Array[String]): Unit =
    runtimeProvenance()
    require(args.length == 1 || (args.length == 2 && args(1) == "negative-tail"),
      "expected a fresh output directory, or preserved output directory and negative-tail")
    val tail = args.length == 2
    val output = Path.of(args(0)).toAbsolutePath.normalize
    require(output.startsWith(Path.of(".work").toAbsolutePath.normalize))
    if tail then require(Files.isDirectory(output), "tail requires retained positive artifacts")
    else
      require(!Files.exists(output), "preserve existing replay artifacts")
      val _ = Files.createDirectories(output)
    def save(name: String, snapshot: ConstructionSnapshot, instances: Int, symbolic: Boolean): Unit =
      val text = checked(snapshot, instances, symbolic, output.resolve(name + ".mlir"))
      val digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8))
        .map(value => f"${value & 0xff}%02x").mkString
      println("F58_SYMBOLIC_ARRAY_SOURCE " + name + " " + digest)
    if !tail then
      Vector("early", "late", "add-zero", "multiply-one", "reverse", "two-instances").foreach: mode =>
        save(mode, ConstructionKernel.inspect(new SymbolicConservativeArrayTop(mode)),
          if mode == "two-instances" then 2 else 1, symbolic = true)
      save("default-one", ConstructionKernel.inspect(new SymbolicConservativeArrayTop("late", 1)),
        1, symbolic = true)
      Vector(("fixed-extent1", 1, "direct"), ("fixed-extent3", 3, "direct"),
        ("fixed-reverse", 3, "reverse"), ("fixed-two-instances", 3, "two-instances")).foreach:
        (name, extent, mode) =>
          save(name, ConstructionKernel.inspect(new ConservativeArrayTop(extent, mode)),
            if mode == "two-instances" then 2 else 1, symbolic = false)
      Vector("different-identity", "missing-binding").foreach(mode =>
        sourceRejected(mode, () => new SymbolicConservativeArrayTop(mode), "exact interface connection"))
      sourceRejected("unresolved-expression", () => new SymbolicConservativeArrayTop("unresolved-expression"),
        "exact interface connection", "direct", "bounded parameter")
      sourceRejected("foreign-operand", () => new SymbolicConservativeArrayTop("foreign-operand"),
        "NODAL-HIERARCHY-031")
      sourceRejected("foreign-neutral", () => new SymbolicConservativeArrayTop("foreign-neutral"),
        "NODAL-HIERARCHY-031")
      sourceRejected("runtime-annihilated", () => new SymbolicConservativeArrayTop("runtime-annihilated"),
        "NODAL-HIERARCHY-032")
      sourceRejected("runtime-operand", () => new SymbolicConservativeArrayTop("runtime-operand"),
        "NODAL-HIERARCHY-032")
    sourceRejected("formal-range", () => new SymbolicConservativeArrayTop("formal-range"),
      "envelope escapes formal")
    sourceRejected("duplicate", () => new SymbolicConservativeArrayTop("duplicate"),
      "conservative forwarding")
    sourceRejected("nonpositive", () => new SymbolicConservativeArrayNonpositive, "positive")
    sourceRejected("unbounded", () => new SymbolicConservativeArrayUnsupported("unbounded"),
      "positive", "bounded", "range")
    sourceRejected("expression-endpoint", () => new SymbolicConservativeArrayUnsupported("expression-endpoint"),
      "conservative forwarding")
    // The historical symbolic-parent/fixed-child mismatch remains a negative, not new support.
    sourceRejected("fixed-child-symbolic-parent", () => new ConservativeArraySymbolic,
      "exact interface connection", "conservative forwarding")
    sourceRejected("literal-parent-symbolic-child", () => new SymbolicConservativeArrayLiteralParent,
      "conservative forwarding", "exact interface connection")
    bridgeControls(ConstructionKernel.inspect(new SymbolicConservativeArrayTop))
    println(if tail then "PASS remaining source and bridge controls; positive artifacts retained"
      else "PASS focused symbolic and literal conservative InterfaceArray source replay")
