package nodal.internal.testkit

import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

import nodal.*
import nodal.internal.bridge.*

import utest.*

final class Increment43FixedNodeArray extends Module:
  val p = inout(Electrical)
  val n = inout(Electrical)
  val scalar: Node[Electrical.type] = node(Electrical)
  val tap: NodeArray[Electrical.type] = node(Electrical, 2, 3)
  val first = tap.at(0, 1)
  val duplicate = tap.at(0.integer, 1.integer)

  analog:
    I(first, p) <+ (V(duplicate, p) / 1.kOhm)
    V(tap.at(1, 2), n) <+ 2.0.V
    I(scalar, p) <+ 0.0.A

final class Increment43SymbolicNodeArray(initialRows: Int = 3) extends Module:
  val p = inout(Electrical)
  val rows: Param[Integer] = param(initialRows.integer, range = 3 to 5)
  val lane: Param[Integer] = param(1.integer, range = 1 to 2)
  val tap = node(Electrical, rows, 3)
  val first = tap.at(lane, 2)
  val duplicate = tap.at(lane, 2.integer)

  analog:
    I(first, p) <+ (V(duplicate, p) / 2.kOhm)

final class Increment43CompoundNodeArray(reuse: Boolean) extends Module:
  val p = inout(Electrical)
  val lane: Param[Integer] = param(0.integer, range = 0 to 1)
  val index: Expr[Integer] = lane + 1.integer
  val tap = node(Electrical, 4)
  val first = tap.at(index)
  val second = tap.at(if reuse then index else lane + 1.integer)

  analog:
    I(first, p) <+ (V(second, p) / 1.kOhm)

final class Increment43InlineNodeArrayIndex extends Module:
  val p = inout(Electrical)
  val lane: Param[Integer] = param(0.integer, range = 0 to 1)
  val tap = node(Electrical, 4)

  analog:
    val selected = tap.at(lane + 1.integer)
    I(selected, p) <+ (V(selected, p) / 1.kOhm)

final class Increment43InlineLiteralNodeArrayIndex extends Module:
  val p = inout(Electrical)
  val tap = node(discipline = Electrical, dimension = 2)
  val first = tap.at(0)

  analog:
    I(tap.at(0.integer), p) <+ (V(first, p) / 1.kOhm)

final class Increment43WideNodeArray extends Module:
  val tap = node(Electrical, Int.MaxValue, Int.MaxValue, 2)
  val selected = tap.at(0, 0, 1)

final class Increment43MultilineNodeArray extends Module:
  val tap =
    node(
      Electrical,
      2,
      3
    )
  val selected = tap.at(1, 2)

final class Increment43ParameterNodeArrayAlias(disjoint: Boolean) extends Module:
  val firstIndex: Param[Integer] = param(0.integer, range = 0 to 1)
  val secondIndex: Param[Integer] =
    if disjoint then param(2.integer, range = 2 to 3)
    else param(0.integer, range = 0 to 1)
  val tap = node(Electrical, 4)
  val first = tap.at(firstIndex)
  val second = tap.at(secondIndex)

final class Increment43CaughtNodeArrayAlias extends Module:
  val lane: Param[Integer] = param(0.integer, range = 0 to 1)
  val tap = node(Electrical, 2)
  val first = tap.at(0)
  val rejected = scala.util.Try(tap.at(lane)).failed.get.asInstanceOf[ConstructionException]
  assert(rejected.diagnostic.code == "NODAL-SHAPE-043-006")
  val second = tap.at(1)

final class Increment43SeparatedNodeArrayAxes extends Module:
  val firstIndex: Param[Integer] = param(0.integer, range = 0 to 1)
  val secondIndex: Param[Integer] = param(0.integer, range = 0 to 1)
  val tap = node(Electrical, 2, 2)
  val first = tap.at(0, firstIndex)
  val second = tap.at(1, secondIndex)

final class Increment43SeparateNodeArrays extends Module:
  val firstArray = node(Electrical, 2)
  val secondArray = node(Electrical, 2)
  val first = firstArray.at(0)
  val second = secondArray.at(0.integer)

final class Increment43InvalidNodeArrayDimension(form: Int) extends Module:
  val bounded: Param[Integer] = param(2.integer, range = 0 to 4)
  val unbounded: Param[Integer] = param(2.integer)
  val dynamic: Signal[Integer] = wire(Integer)
  val tap = form match
    case 0 => node(Electrical, 0)
    case 1 => node(Electrical, -1)
    case 2 => node(Electrical, bounded)
    case 3 => node(Electrical, unbounded)
    case 4 => node(Electrical, Int.MaxValue, Int.MaxValue, Int.MaxValue)
    case 5 => node(Electrical, bounded - 1.integer)
    case _ => node(Electrical, dynamic)

final class Increment43NamedNodeArray extends Module:
  val custom = discipline("custom", Voltage, Current)
  val tap = node(custom, 2)

final class Increment43InvalidNodeArrayIndex(form: Int) extends Module:
  val bounded: Param[Integer] = param(1.integer, range = 0 to 3)
  val unbounded: Param[Integer] = param(1.integer)
  val dynamic: Signal[Integer] = wire(Integer)
  val tap = node(Electrical, 2, 3)
  val selected = form match
    case 0 => tap.at()
    case 1 => tap.at(0)
    case 2 => tap.at(0, 0, 0)
    case 3 => tap.at(-1, 0)
    case 4 => tap.at(2, 0)
    case 5 => tap.at(0, 3)
    case 6 => tap.at(0, bounded)
    case 7 => tap.at(0, unbounded)
    case _ => tap.at(0, dynamic)

final class Increment43ForeignNodeArrayChild extends Module:
  val lane: Param[Integer] = param(0.integer, range = 0 to 1)
  val tap = node(Electrical, 2)

final class Increment43ForeignNodeArray(form: Int) extends Module:
  val child = new Increment43ForeignNodeArrayChild
  val tap = node(Electrical, 2)
  val selected = if form == 0 then child.tap.at(0) else tap.at(child.lane)

final class Increment43GeneratedNodeArray(declare: Boolean) extends Module:
  val tap = node(Electrical, 2)
  hdlRange(0, 2): index =>
    if declare then
      val generated = node(Electrical, 2)
      val _ = generated
    else
      val selected = tap.at(index)
      val _ = selected
    ()

final class Increment43AnalogNodeArrayDeclaration extends Module:
  analog:
    val tap = node(Electrical, 2)
    val _ = tap

final class Increment43NodeArrayConnections extends Module:
  val scalar = node(Electrical)
  val tap = node(Electrical, 2)
  val selected = tap.at(0)
  connect(selected, scalar)
  tap.at(0.integer) <> scalar
  connect(selected, tap.at(0))

final class Increment43NodeArrayChildPort extends Module:
  val p = inout(Electrical)
  val n = inout(Electrical)
  analog:
    I(p, n) <+ (V(p, n) / 1.kOhm)

final class Increment43NodeArrayHierarchy extends Module:
  val p = inout(Electrical)
  val tap = node(Electrical, 2)
  val child = instance(new Increment43NodeArrayChildPort)
  connect(tap.at(0), child(_.p))
  connect(p, child(_.n))

final class Increment43LocalNodeArrayChild extends Module:
  val p = inout(Electrical)
  val n = inout(Electrical)
  val tap = node(Electrical, 2)
  val selected = tap.at(1)

  analog:
    I(p, selected) <+ (V(p, selected) / 1.kOhm)
    I(selected, n) <+ (V(selected, n) / 2.kOhm)

final class Increment43ChildLocalNodeArray extends Module:
  val p = inout(Electrical)
  val n = inout(Electrical)
  val child = instance(new Increment43LocalNodeArrayChild)
  connect(p, child(_.p))
  connect(n, child(_.n))

object Increment43NodeArrayTests extends TestSuite:
  private def failure(top: => Module, code: String): Unit =
    val error = scala.util.Try(ConstructionKernel.inspect(top))
      .failed.get.asInstanceOf[ConstructionException]
    assert(error.diagnostic.code == code)

  private def bridgeFailure(snapshot: ConstructionSnapshot, codes: Set[String]): Unit =
    val error = scala.util.Try(ScalaToMlirBridge.fromSnapshot(snapshot))
      .failed.get.asInstanceOf[BridgeException]
    assert(codes.contains(error.diagnostic.code))

  private def changeDeclaration(
      snapshot: ConstructionSnapshot,
      name: String
  )(change: KernelDeclarationSnapshot => KernelDeclarationSnapshot): ConstructionSnapshot =
    snapshot.copy(modules =
      snapshot.modules.map(module =>
        module.copy(declarations =
          module.declarations.map(declaration =>
            if declaration.name == name then change(declaration) else declaration
          )
        )
      )
    )

  private def sourceSpans(snapshot: ConstructionSnapshot, paths: Vector[String]): Unit =
    paths.foreach: path =>
      val source = snapshot.sourceMap.find(_.semanticPath == path).get.source
      assert(source.path.endsWith("Increment43NodeArrayTests.scala"))
      assert(source.line > 0, source.column > 0, source.endLine >= source.line)

  private def manifestEntry(text: String, path: String): String =
    "\\{[^{}]*\\}".r.findAllIn(text)
      .find(entry =>
        entry.contains("\"declaration_kind\"") &&
          entry.contains(s"\"semantic_path\": \"$path\"")
      ).get

  private def delete(path: Path): Unit =
    if Files.isDirectory(path) then
      val stream = Files.list(path)
      try
        val iterator = stream.iterator()
        while iterator.hasNext do delete(iterator.next())
      finally stream.close()
    val _ = Files.deleteIfExists(path)

  private def withNativeTools(body: (Path, Path, Path) => Unit): Unit =
    (sys.env.get("NODAL_NODALC"), sys.env.get("NODAL_TRANSLATE")) match
      case (Some(nodalc), Some(translator)) =>
        val compilerPath = Path.of(nodalc).toAbsolutePath
        val translatorPath = Path.of(translator).toAbsolutePath
        assert(Files.isExecutable(compilerPath), Files.isExecutable(translatorPath))
        val root = Path.of(".work", "increment43-node-array-tests").toAbsolutePath
        val _ = Files.createDirectories(root)
        val directory = Files.createTempDirectory(root, "native-")
        try body(compilerPath, translatorPath, directory)
        finally delete(directory)
      case _ => ()

  private def nativeRequest(executable: Path, directory: Path): NativeCompilerRequest =
    NativeCompilerRequest(
      executable = executable,
      arguments = Vector("--canonicalize", "--verify-each"),
      workingDirectory = directory,
      timeout = Duration.ofSeconds(60)
    )

  private def nativeSuccess(
      document: NodalMlirDocument,
      request: NativeCompilerRequest
  ): String = NativeCompilerClient.run(document, request) match
    case success: NativeCompilerSuccess => success.normalizedMlir
    case failure: NativeCompilerFailure =>
      throw new java.lang.AssertionError(s"${failure.diagnostic}\n${failure.standardError}")

  private def emit(
      document: NodalMlirDocument,
      compiler: Path,
      translator: Path,
      directory: Path
  ): String =
    val request = nativeRequest(compiler, directory)
    val normalized = nativeSuccess(
      document,
      request.copy(arguments = Vector("--pass-pipeline=builtin.module(nodal-gate-default)"))
    )
    nativeSuccess(
      document.copy(text = normalized),
      request.copy(executable = translator, arguments = Vector("--nodal-to-verilog-a"))
    )

  val tests: Tests = Tests:
    test("fixed node arrays preserve scalar overload and exact physical alias identity"):
      val snapshot = ConstructionKernel.inspect(new Increment43FixedNodeArray)
      val declarations = snapshot.modules.head.declarations
      val array = declarations.find(_.name == "tap").get
      val scalar = declarations.find(_.name == "scalar").get
      assert(array.kind == "analog-node-array")
      assert(array.dataType.isEmpty, array.conservativeDimensions == Vector("2", "3"))
      assert(scalar.kind == "analog-node", scalar.conservativeDimensions.isEmpty)
      assert(!declarations.exists(declaration =>
        Set("first", "duplicate").contains(declaration.name)
      ))

      val first = s"${array.path}.at(literal:0,literal:1)"
      val second = s"${array.path}.at(literal:1,literal:2)"
      assert(snapshot.shapeIndices.map(_.path).toSet == Set(first, second))
      val selection = snapshot.shapeIndices.find(_.path == first).get
      assert(selection.indices == Vector("0", "1"), selection.resultDimensions.isEmpty)
      assert(selection.sourceUses.size == 2)
      assert(selection.sourceUses == selection.sourceUses.distinct.sorted)
      val sourcePaths = snapshot.sourceMap.map(_.semanticPath).toSet
      assert((selection.sourceUses :+ first).forall(sourcePaths.contains))
      assert(!selection.sourceUses.contains(first))
      sourceSpans(snapshot, selection.sourceUses :+ first :+ array.path)
      val operands = snapshot.analogRegions.flatMap(_.expressions.flatMap(_.operands))
      assert(operands.count(_ == first) == 2, operands.count(_ == second) == 1)
      assert(snapshot.analogRegions.flatMap(_.contributions).size == 3)

    test(
      "symbolic dimensions and duplicate parameters retain declaration identities and envelopes"
    ):
      val snapshot = ConstructionKernel.inspect(new Increment43SymbolicNodeArray)
      val array = snapshot.modules.head.declarations.find(_.name == "tap").get
      val selected = snapshot.shapeIndices.head
      assert(array.conservativeDimensions == Vector("rows", "3"))
      assert(selected.indices == Vector(s"${snapshot.root}.lane", "2"))
      assert(selected.path == s"${array.path}.at(parameter:${snapshot.root}.lane,literal:2)")
      assert(selected.sourceUses.size == 2, snapshot.shapeIndices.size == 1)
      val changed = ConstructionKernel.inspect(new Increment43SymbolicNodeArray(5))
      assert(changed.shapeIndices.map(_.path) == snapshot.shapeIndices.map(_.path))
      val document = ScalaToMlirBridge.fromSnapshot(snapshot)
      assert(document.text.contains("!nodal.shaped<\"rows,3\", !nodal.terminal<\"electrical\">>"))
      assert(document.text.contains("\"nodal.const_parameter_ref\""))
      assert(document.text.contains("effects = [\"rank\", \"shape\"]"))

    test("captured compound root reuse shares one endpoint and retains independent authored uses"):
      val snapshot = ConstructionKernel.inspect(new Increment43CompoundNodeArray(true))
      val selected = snapshot.shapeIndices.head
      assert(snapshot.shapeIndices.size == 1, selected.sourceUses.size == 2)
      val root = snapshot.parameterExpressions.find(_.path == selected.indices.head).get
      assert(root.operation == "analog_add")
      assert(selected.path == s"${selected.input}.at(expression:${root.path})")
      assert(ScalaToMlirBridge.fromSnapshot(snapshot).text.contains("\"nodal.const_expr\""))
      failure(new Increment43CompoundNodeArray(false), "NODAL-SHAPE-043-006")

    test("inline compound selection in analog remains a static structural reference"):
      val snapshot = ConstructionKernel.inspect(new Increment43InlineNodeArrayIndex)
      assert(snapshot.shapeIndices.size == 1, snapshot.parameterExpressions.nonEmpty)
      val document = ScalaToMlirBridge.fromSnapshot(snapshot)
      assert(document.text.contains("\"nodal.shape_index\""))
      assert(document.text.contains("\"nodal.const_expr\""))

    test("named declaration and inline Integer literal reuse the earlier physical endpoint"):
      val snapshot = ConstructionKernel.inspect(new Increment43InlineLiteralNodeArrayIndex)
      assert(snapshot.shapeIndices.size == 1)
      val selected = snapshot.shapeIndices.head
      assert(selected.indices == Vector("0"), selected.sourceUses.size == 2)
      assert(selected.path == s"${snapshot.root}.tap.at(literal:0)")
      val sourcePaths = snapshot.sourceMap.map(_.semanticPath).toSet
      assert(selected.sourceUses.forall(sourcePaths.contains))
      sourceSpans(snapshot, selected.sourceUses :+ selected.path)
      assert(ScalaToMlirBridge.fromSnapshot(snapshot).text.contains("\"nodal.shape_index\""))

    test("multiline array declarations retain authored names and concrete source spans"):
      val snapshot = ConstructionKernel.inspect(new Increment43MultilineNodeArray)
      val declaration = snapshot.modules.head.declarations.head
      assert(declaration.name == "tap", declaration.path == s"${snapshot.root}.tap")
      assert(snapshot.shapeIndices.head.input == declaration.path)
      sourceSpans(snapshot, snapshot.shapeIndices.head.sourceUses :+ declaration.path)

    test(
      "reproducibility artifacts retain conservative shape source occurrences and scalar metadata"
    ):
      val snapshot = ConstructionKernel.inspect(new Increment43FixedNodeArray)
      val first = ReproducibilityContract.describe(snapshot)
      val second = ReproducibilityContract.describe(
        ConstructionKernel.inspect(new Increment43FixedNodeArray)
      )
      val reordered = ReproducibilityContract.describe(snapshot.copy(
        modules = snapshot.modules.reverse.map(module =>
          module.copy(declarations = module.declarations.reverse)
        ),
        sourceMap = snapshot.sourceMap.reverse
      ))
      assert(first == second, first == reordered)
      val array = manifestEntry(first.manifest.text, s"${snapshot.root}.tap")
      assert(array.contains("\"shape\": \"2x3\""))
      assert(array.contains("\"layout\": \"conservative-vector-row-major\""))
      assert(array.contains("\"storage\": \"structural\""))
      val scalar = manifestEntry(first.manifest.text, s"${snapshot.root}.scalar")
      assert(scalar.contains("\"shape\": \"connectivity\""))
      assert(scalar.contains("\"layout\": \"not-applicable\""))
      assert(scalar.contains("\"storage\": \"connectivity\""))
      assert(first.construction.replaceAll("\\s", "")
        .contains("\"conservative_dimensions\":[\"2\",\"3\"]"))
      assert(first.construction.contains("\"node_array_selections\""))
      assert(first.construction.replaceAll("\\s", "").contains("\"indices\":[\"0\",\"1\"]"))
      assert(first.construction.contains("\"source_uses\""))
      snapshot.shapeIndices.foreach: selection =>
        assert(first.sourceMlir.contains(selection.path))
        selection.sourceUses.foreach: source =>
          assert(first.construction.contains(source), first.manifest.text.contains(source))
          assert(first.sourceMlir.contains(source))

    test("positive signed64 cardinality boundary stays compact without lane expansion"):
      val snapshot = ConstructionKernel.inspect(new Increment43WideNodeArray)
      assert(snapshot.modules.head.declarations.size == 1)
      assert(snapshot.modules.head.declarations.head.conservativeDimensions ==
        Vector(Int.MaxValue.toString, Int.MaxValue.toString, "2"))
      assert(snapshot.shapeIndices.size == 1)
      val document = ScalaToMlirBridge.fromSnapshot(snapshot)
      assert(document.text.linesIterator.count(_.contains("\"nodal.node_array\"")) == 1)
      assert(document.text.linesIterator.count(_.contains("\"nodal.shape_index\"")) == 1)

    test("distinct selectors require a disjoint axis rather than equal defaults or envelopes"):
      failure(new Increment43ParameterNodeArrayAlias(false), "NODAL-SHAPE-043-006")
      Vector(
        ConstructionKernel.inspect(new Increment43ParameterNodeArrayAlias(true)),
        ConstructionKernel.inspect(new Increment43SeparatedNodeArrayAxes),
        ConstructionKernel.inspect(new Increment43SeparateNodeArrays)
      ).foreach: snapshot =>
        assert(snapshot.shapeIndices.size == 2)
        assert(snapshot.shapeIndices.map(_.path).distinct.size == 2)
        assert(ScalaToMlirBridge.fromSnapshot(snapshot).text.contains("\"nodal.shape_index\""))

    test("caught alias rejection leaves neither endpoint source uses nor parameter effects"):
      val snapshot = ConstructionKernel.inspect(new Increment43CaughtNodeArrayAlias)
      assert(snapshot.shapeIndices.map(_.indices).toSet == Set(Vector("0"), Vector("1")))
      assert(snapshot.shapeIndices.flatMap(_.sourceUses).size == 2)
      assert(snapshot.parameterExpressions.isEmpty)
      val attributes =
        snapshot.modules.head.declarations.find(_.name == "lane").get.attributes.toMap
      assert(!attributes.get("structural_effects").exists(_.split(",").contains("shape")))
      sourceSpans(snapshot, snapshot.shapeIndices.flatMap(_.sourceUses))
      assert(ScalaToMlirBridge.fromSnapshot(snapshot).text.contains("\"nodal.shape_index\""))

    test("nonpositive unbounded dynamic and overflowing array declarations fail"):
      (0 to 6).foreach(form =>
        failure(new Increment43InvalidNodeArrayDimension(form), "NODAL-SHAPE-043-005")
      )
      failure(new Increment43NamedNodeArray, "NODAL-SHAPE-043-005")
      failure(new Increment43AnalogNodeArrayDeclaration, "NODAL-SHAPE-043-005")

    test("selection requires complete rank and proven bounds for all legal values"):
      (0 to 6).foreach(form =>
        failure(new Increment43InvalidNodeArrayIndex(form), "NODAL-SHAPE-043-002")
      )
      (7 to 8).foreach(form =>
        failure(new Increment43InvalidNodeArrayIndex(form), "NODAL-SHAPE-043-006")
      )

    test(
      "foreign ownership and generated array declarations reject while scoped selection is retained"
    ):
      (0 to 1).foreach(form =>
        failure(new Increment43ForeignNodeArray(form), "NODAL-SHAPE-043-006")
      )
      failure(new Increment43GeneratedNodeArray(true), "NODAL-ITERATION-043-004")
      val selected = ConstructionKernel.inspect(new Increment43GeneratedNodeArray(false))
      assert(selected.shapeIndices.size == 1, selected.shapeIndices.head.generatedScope.nonEmpty)
      assert(selected.shapeIndices.head.indices == Vector(selected.generatedRegions.head.induction))

    test("selected scalar connections retain aliases and inherited scalar self relations"):
      val snapshot = ConstructionKernel.inspect(new Increment43NodeArrayConnections)
      assert(snapshot.shapeIndices.size == 1)
      val endpoint = snapshot.shapeIndices.head.path
      assert(snapshot.shapeIndices.head.sourceUses.size == 3)
      assert(snapshot.topology.count(edge => edge.left == endpoint && edge.right == endpoint) == 1)
      assert(snapshot.topology.count(edge =>
        Set(edge.left, edge.right) == Set(endpoint, s"${snapshot.root}.scalar")
      ) == 2)
      val hierarchy = ConstructionKernel.inspect(new Increment43NodeArrayHierarchy)
      assert(hierarchy.shapeIndices.size == 1)
      assert(hierarchy.topology.exists(edge =>
        edge.left == hierarchy.shapeIndices.head.path ||
          edge.right == hierarchy.shapeIndices.head.path
      ))
      assert(ScalaToMlirBridge.fromSnapshot(hierarchy).text.contains("\"nodal.connect\""))

    test("bridge rejects forged conservative declaration shape and source metadata"):
      val snapshot = ConstructionKernel.inspect(new Increment43FixedNodeArray)
      Vector(
        changeDeclaration(snapshot, "tap")(_.copy(conservativeDimensions = Vector.empty)),
        changeDeclaration(snapshot, "tap")(_.copy(conservativeDimensions = Vector("0", "3"))),
        changeDeclaration(snapshot, "tap")(_.copy(conservativeDimensions = Vector("02", "3"))),
        changeDeclaration(snapshot, "tap")(_.copy(conservativeDimensions = Vector("missing", "3"))),
        changeDeclaration(
          snapshot,
          "tap"
        )(_.copy(conservativeDimensions = Vector(Long.MaxValue.toString, "2"))),
        changeDeclaration(snapshot, "tap")(_.copy(dataType = Some("Vec(Real;2x3)"))),
        changeDeclaration(snapshot, "tap")(declaration =>
          declaration.copy(attributes = declaration.attributes.filterNot(_._1 == "discipline"))
        ),
        changeDeclaration(snapshot, "scalar")(_.copy(conservativeDimensions = Vector("2"))),
        snapshot.copy(sourceMap =
          snapshot.sourceMap.filterNot(_.semanticPath == s"${snapshot.root}.tap")
        )
      ).foreach(forged => bridgeFailure(forged, Set("NODAL-BRIDGE-047")))

    test("bridge rejects forged selector identity rank and authored source occurrences"):
      val snapshot = ConstructionKernel.inspect(new Increment43FixedNodeArray)
      val index = snapshot.shapeIndices.head
      Vector(
        index.copy(path = s"${index.input}.at(literal:1,literal:1)"),
        index.copy(input = s"${snapshot.root}.scalar"),
        index.copy(indices = Vector("0")),
        index.copy(indices = Vector("00", "1")),
        index.copy(resultDimensions = Vector("3")),
        index.copy(sourceUses = index.sourceUses.reverse),
        index.copy(sourceUses = Vector(index.sourceUses.head, index.sourceUses.head)),
        index.copy(sourceUses = Vector("Foreign.selection")),
        index.copy(sourceUses = Vector(index.input)),
        index.copy(sourceUses = Vector(index.path))
      ).foreach(forged =>
        bridgeFailure(
          snapshot.copy(shapeIndices = forged +: snapshot.shapeIndices.tail),
          Set("NODAL-BRIDGE-048")
        )
      )
      bridgeFailure(
        snapshot.copy(shapeIndices =
          index.copy(sourceUses = Vector.empty) +: snapshot.shapeIndices.tail
        ),
        Set("NODAL-BRIDGE-044", "NODAL-BRIDGE-048")
      )
      bridgeFailure(
        snapshot.copy(sourceMap =
          snapshot.sourceMap.filterNot(_.semanticPath == index.sourceUses.head)
        ),
        Set("NODAL-BRIDGE-048")
      )

    test("public fixed and symbolic node arrays emit exact compact physical endpoints"):
      withNativeTools: (compiler, translator, directory) =>
        val fixed = ScalaToMlirBridge.lower(new Increment43FixedNodeArray)
        assert(fixed == ScalaToMlirBridge.lower(new Increment43FixedNodeArray))
        assert(fixed.text.linesIterator.count(_.contains("\"nodal.node_array\"")) == 1)
        assert(fixed.text.linesIterator.count(_.contains("\"nodal.shape_index\"")) == 2)
        assert(fixed.text.contains("source_uses = ["))
        val output = emit(fixed, compiler, translator, directory)
        assert(output == emit(fixed, compiler, translator, directory))
        assert(output.linesIterator.count(_.trim == "electrical [0:5] tap;") == 1)
        assert(output.contains("I(tap[1], p) <+ (V(tap[1], p) / 1000);"))
        assert(output.contains("V(tap[5], n) <+ 2;"))
        assert(!output.contains("electrical first"), !output.contains("electrical duplicate"))
        val symbolic = emit(
          ScalaToMlirBridge.lower(new Increment43SymbolicNodeArray),
          compiler,
          translator,
          directory
        )
        assert(symbolic.contains("electrical [0:((rows * 3) - 1)] tap;"))
        assert(
          symbolic.contains("I(tap[((lane * 3) + 2)], p) <+ (V(tap[((lane * 3) + 2)], p) / 2000);")
        )
        val inline = emit(
          ScalaToMlirBridge.lower(new Increment43InlineNodeArrayIndex),
          compiler,
          translator,
          directory
        )
        assert(inline.contains("tap[(lane + 1)]"))
        val literal = emit(
          ScalaToMlirBridge.lower(new Increment43InlineLiteralNodeArrayIndex),
          compiler,
          translator,
          directory
        )
        assert(literal.contains("electrical [0:1] tap;"))
        assert(literal.contains("I(tap[0], p) <+ (V(tap[0], p) / 1000);"))

    test("a child module retains its local array and selected internal target endpoints"):
      val snapshot = ConstructionKernel.inspect(new Increment43ChildLocalNodeArray)
      val child = snapshot.modules.find(_.path != snapshot.root).get
      val array = child.declarations.find(_.name == "tap").get
      val selection = snapshot.shapeIndices.head
      assert(selection.owner == child.path, selection.input == array.path)
      assert(selection.path == s"${array.path}.at(literal:1)")
      sourceSpans(snapshot, selection.sourceUses :+ array.path)
      withNativeTools: (compiler, translator, directory) =>
        val document = ScalaToMlirBridge.fromSnapshot(snapshot)
        val output = emit(document, compiler, translator, directory)
        assert(output == emit(document, compiler, translator, directory))
        assert(output.linesIterator.count(_.trim == "electrical [0:1] tap;") == 1)
        assert(output.contains("I(p, tap[1]) <+ (V(p, tap[1]) / 1000);"))
        assert(output.contains("I(tap[1], n) <+ (V(tap[1], n) / 2000);"))

    test("native verification independently rejects forged physical identity and source uses"):
      withNativeTools: (compiler, _, directory) =>
        val document = ScalaToMlirBridge.lower(new Increment43FixedNodeArray)
        val request = nativeRequest(compiler, directory)
        val forged = Vector(
          document.text.replace("selection_kind = \"node_array\"", "selection_kind = \"fake\""),
          document.text.replaceFirst("source_uses = \\[[^\\]]+\\]", "source_uses = []"),
          document.text.replace("tap.at(literal:0,literal:1)", "tap.at(literal:1,literal:1)"),
          document.text.replace(
            "source_path = \"Increment43FixedNodeArray.tap\"",
            "source_path = \"Foreign.tap\""
          )
        )
        forged.foreach: text =>
          assert(text != document.text)
          NativeCompilerClient.run(document.copy(text = text), request) match
            case failure: NativeCompilerFailure =>
              // Relabeling a connected array invalidates its selectors' input provenance first.
              assert(failure.standardError.contains("NODAL-SHAPE-043-006"))
            case success: NativeCompilerSuccess =>
              throw new java.lang.AssertionError(
                s"forged node array was accepted: ${success.normalizedMlir}"
              )
