package nodal.internal.testkit

import java.nio.file.{Files, Path}
import java.time.Duration

import nodal.*
import nodal.internal.bridge.*
import utest.*

final class Increment43LocalArrayFixed extends Module:
  val common = inout(Electrical)
  hdlRange(0, 2): _ =>
    val taps = node(Electrical, 2, 3)
    val selected = taps.row(1).slice(1, 3).flatten.at(1)
    val direct = taps.at(1, 2)
    analog:
      I(selected, common) <+ V(direct, common) / 1.kOhm

final class Increment43LocalArraySymbolic extends Module:
  val count: Param[Integer] = param(3.integer, range = 2 to 4)
  val width: Expr[Integer] = count + 1.integer
  val common = inout(Electrical)
  hdlRange(0, width): lane =>
    val taps = node(Electrical, width)
    val element = instance(new Increment43TopologyArrayChild)
    taps <> element(_.p)
    element.param(_.count, width)
    analog:
      I(taps.at(lane), common) <+ V(taps.at(lane), common) / 1.kOhm

final class Increment43LocalArrayNested extends Module:
  val outputs = inout(Electrical, 2, 2)
  val common = inout(Electrical)
  hdlRange(0, 2): outer =>
    val taps = node(Electrical, 2, 3)
    val view = taps.slice(1, 3, axis = 1).flatten.reshape(2, 2)
    hdlRange(0, 2): inner =>
      val observed = view.at(inner, 1)
      analog:
        I(outputs.at(outer, inner), common) <+ I(observed, common)

final class Increment43LocalArrayRelations extends Module:
  val common = inout(Electrical)
  hdlRange(0, 2): _ =>
    val first = node(Electrical, 2, 3)
    val second = node(Electrical, 2, 3)
    first.slice(0, 1, axis = 1).flatten <> second.slice(2, 3, axis = 1).flatten
    analog:
      I(first.at(0, 0), common) <+ V(second.at(0, 2), common) / 1.kOhm

final class Increment43LocalArraySiblings extends Module:
  val common = inout(Electrical)
  hdlRange(0, 1): _ =>
    val taps = node(Electrical, 1)
    analog:
      I(taps.at(0), common) <+ 0.001.A
  hdlRange(0, 2): _ =>
    val taps = node(Electrical, 2)
    analog:
      I(taps.at(1), common) <+ 0.002.A
  hdlRange(-2, -2): _ =>
    val taps = node(Electrical, 3)
    analog:
      I(taps.at(2), common) <+ 0.003.A

final class Increment43LocalArrayChild extends Module:
  val p = inout(Electrical)
  val n = inout(Electrical)
  hdlRange(0, 2): _ =>
    val internal = node(Electrical, 2)
    connect(internal.at(0), p)
    connect(internal.at(1), n)
    analog:
      I(internal.at(0), internal.at(1)) <+ V(internal.at(0), internal.at(1)) / 1.kOhm

final class Increment43LocalArrayReusable extends Module:
  val taps = inout(Electrical, 2)
  val common = inout(Electrical)
  hdlRange(0, 2): lane =>
    val first = instance(new Increment43LocalArrayChild)
    val second = instance(new Increment43LocalArrayChild)
    connect(taps.at(lane), first(_.p))
    connect(common, first(_.n))
    connect(taps.at(lane), second(_.p))
    connect(common, second(_.n))

final class Increment43LocalArrayEscape(form: Int) extends Module:
  val common = inout(Electrical)
  var escaped: Option[NodeArray[Electrical.type]] = None
  var selected: Option[Node[Electrical.type]] = None
  hdlRange(0, 2): _ =>
    val taps = node(Electrical, 2, 3)
    escaped = Some(if form == 2 then taps.row(0) else taps)
    selected = Some(taps.at(0, 0))
  if form == 0 then
    val _ = escaped.get.at(0, 0)
  else if form == 1 || form == 2 then
    hdlRange(0, 1): _ =>
      val _ = escaped.get.flatten.at(0)
  else
    analog:
      I(common) <+ V(selected.get, common) / 1.kOhm

final class Increment43LocalArrayInvalid(form: Int) extends Module:
  hdlRange(0, if form == 2 || form == 3 then 0 else 2): lane =>
    if form == 0 then
      val _ = inout(Electrical, 2)
    else if form == 1 then
      val _ = node(Electrical, lane)
    else if form == 2 then
      val _ = node(Electrical, 0)
    else if form == 3 then
      val taps = node(Electrical, 2)
      val _ = taps.at(2)
    else
      val _ = node(Electrical, Int.MaxValue, Int.MaxValue, Int.MaxValue)

object Increment43GeneratedLocalArrayTests extends TestSuite:
  val positiveModels: Vector[() => Module] = Vector(
    () => new Increment43LocalArrayFixed,
    () => new Increment43LocalArraySymbolic,
    () => new Increment43LocalArrayNested,
    () => new Increment43LocalArrayRelations,
    () => new Increment43LocalArraySiblings,
    () => new Increment43LocalArrayReusable
  )

  private def failure(top: => Module, code: String): Unit =
    val result = scala.util.Try(ConstructionKernel.inspect(top)).failed.get
      .asInstanceOf[ConstructionException]
    assert(result.diagnostic.code == code)

  private def bridgeFailure(snapshot: ConstructionSnapshot): Unit =
    val result = scala.util.Try(ScalaToMlirBridge.fromSnapshot(snapshot)).failed.get
      .asInstanceOf[BridgeException]
    assert(Set("NODAL-BRIDGE-043", "NODAL-BRIDGE-050").contains(result.diagnostic.code))

  private def run(
      document: NodalMlirDocument,
      executable: String,
      arguments: Vector[String],
      root: Path
  )
      : String = NativeCompilerClient.run(
    document,
    NativeCompilerRequest(Path.of(executable), arguments, root, timeout = Duration.ofSeconds(90))
  ) match
    case success: NativeCompilerSuccess => success.normalizedMlir
    case failure: NativeCompilerFailure =>
      throw new java.lang.AssertionError(s"${failure.diagnostic}\n${failure.standardError}")

  val tests: Tests = Tests:
    test(
      "generated local arrays retain exact declaration scopes and deterministic source artifacts"
    ):
      positiveModels.foreach: construct =>
        val snapshot = ConstructionKernel.inspect(construct())
        val arrays = snapshot.modules.flatMap(_.declarations).filter(_.kind == "analog-node-array")
        assert(arrays.nonEmpty, arrays.forall(_.generatedScope.nonEmpty))
        arrays.foreach: declaration =>
          val scope = declaration.generatedScope.get
          val region = snapshot.generatedRegions.find(_.path == scope.owner).get
          assert(
            region.induction == scope.induction,
            region.declarations.contains(declaration.path)
          )
          assert(snapshot.sourceMap.exists(_.semanticPath == declaration.path))
        val source = ScalaToMlirBridge.fromSnapshot(snapshot).text
        arrays.foreach: declaration =>
          val line = source.linesIterator.find(line =>
            line.contains("\"nodal.node_array\"") &&
              line.contains(s"source_path = \"${declaration.path}\"")
          )
          // A reusable definition can represent more than one captured child Module.
          if declaration.path.startsWith(snapshot.root + ".") &&
            snapshot.modules.head.declarations.contains(declaration)
          then assert(line.nonEmpty)
          if line.nonEmpty then
            assert(
              line.get.contains(s"generated_owner = \"${declaration.generatedScope.get.owner}\"")
            )
        val artifact = ReproducibilityContract.describe(snapshot)
        arrays.foreach(declaration => assert(artifact.construction.contains(declaration.path)))
        assert(artifact.construction.contains("\"generated_scope\""))
        assert(artifact ==
          ReproducibilityContract.describe(ConstructionKernel.inspect(construct())))
      val fixed = ConstructionKernel.inspect(new Increment43LocalArrayFixed)
      assert(fixed.shapeIndices.count(_.sourceUses.nonEmpty) == 1)
      val siblings = ConstructionKernel.inspect(new Increment43LocalArraySiblings)
      val roots = siblings.modules.head.declarations.filter(_.kind == "analog-node-array")
      assert(roots.map(_.path).distinct.size == 3, roots.map(_.generatedScope).distinct.size == 3)

    test("generated local roots views and selected nodes cannot escape their actual scope"):
      (0 to 3).foreach(form =>
        failure(new Increment43LocalArrayEscape(form), "NODAL-HIERARCHY-044")
      )
      failure(new Increment43LocalArrayForeignCapture, "NODAL-HIERARCHY-044")
      failure(new Increment43LocalArrayInvalid(0), "NODAL-ITERATION-043-004")
      failure(new Increment43LocalArrayInvalid(1), "NODAL-SHAPE-043-005")
      failure(new Increment43LocalArrayInvalid(2), "NODAL-SHAPE-043-005")
      failure(new Increment43LocalArrayInvalid(3), "NODAL-SHAPE-043-002")
      failure(new Increment43LocalArrayInvalid(4), "NODAL-SHAPE-043-005")

    test(
      "bridge independently checks generated array declaration membership and projection ancestry"
    ):
      val snapshot = ConstructionKernel.inspect(new Increment43LocalArraySiblings)
      val module = snapshot.modules.head
      val array = module.declarations.find(_.kind == "analog-node-array").get
      def withDeclaration(replacement: KernelDeclarationSnapshot): ConstructionSnapshot =
        snapshot.copy(modules =
          Vector(module.copy(declarations =
            module.declarations.map(value =>
              if value.path == array.path then replacement else value
            )
          ))
        )
      bridgeFailure(withDeclaration(array.copy(generatedScope = None)))
      bridgeFailure(snapshot.copy(generatedRegions =
        snapshot.generatedRegions.map(region =>
          region.copy(declarations = region.declarations.filterNot(_ == array.path))
        )
      ))
      val other = snapshot.generatedRegions.find(_.path != array.generatedScope.get.owner).get
      bridgeFailure(withDeclaration(array.copy(generatedScope =
        Some(
          KernelGeneratedScope(other.path, other.induction)
        )
      )))
      bridgeFailure(snapshot.copy(generatedRegions =
        snapshot.generatedRegions.map(region =>
          if region.path == other.path then
            region.copy(declarations = region.declarations :+ array.path)
          else region
        )
      ))
      bridgeFailure(withDeclaration(array.copy(kind = "analog-inout")))
      bridgeFailure(snapshot.copy(shapeIndices =
        snapshot.shapeIndices.map(index =>
          if index.input == array.path then index.copy(generatedScope = None) else index
        )
      ))

    test("generated child definitions compare SSA bindings while preserving operations and edges"):
      def moduleCount(snapshot: ConstructionSnapshot): Int =
        ScalaToMlirBridge.fromSnapshot(snapshot).text.linesIterator.count(
          _.contains("\"nodal.module\"")
        )
      val snapshot = ConstructionKernel.inspect(new Increment43LocalArrayReusable)
      assert(moduleCount(snapshot) == 2)
      val second = snapshot.modules.find(_.path.endsWith(".second")).get
      val changedBody = snapshot.copy(analogRegions =
        snapshot.analogRegions.map(region =>
          if region.module == second.path then
            region.copy(expressions =
              region.expressions.map(expression =>
                if expression.operation == "analog_div" then
                  expression.copy(operation = "analog_mul")
                else expression
              )
            )
          else region
        )
      )
      assert(changedBody != snapshot, moduleCount(changedBody) == 3)
      val changedEdges = snapshot.copy(topology =
        snapshot.topology.map(edge =>
          if edge.owner == second.path && edge.right == second.path + ".p" then
            edge.copy(right = second.path + ".n")
          else edge
        )
      )
      assert(changedEdges != snapshot, moduleCount(changedEdges) == 3)
      val changedSource = snapshot.copy(sourceMap =
        snapshot.sourceMap.map(entry =>
          if entry.semanticPath == second.path then
            entry.copy(source = entry.source.copy(path = "quoted\"%capture({}).scala"))
          else entry
        )
      )
      assert(changedSource != snapshot, moduleCount(changedSource) == 3)
      val nested = ConstructionKernel.inspect(new Increment43LocalArrayIndexedReusable)
      val nestedSource = ScalaToMlirBridge.fromSnapshot(nested).text
      assert(moduleCount(nested) == 2, nestedSource.linesIterator.count(_.contains("^bb0(%")) == 3)

    test("generated local arrays emit owned VA AMS vectors views relations and probes"):
      val nodalc =
        sys.env.getOrElse("NODAL_NODALC", throw new java.lang.AssertionError("nodalc required"))
      val translator = sys.env.getOrElse(
        "NODAL_TRANSLATE",
        throw new java.lang.AssertionError("translator required")
      )
      val directory = Path.of(".work", "generated-local-arrays-public-final").toAbsolutePath
      Files.createDirectories(directory)
      positiveModels.foreach: construct =>
        Vector(Backend.VerilogA -> "va", Backend.VerilogAMS -> "vams").foreach:
          (backend, extension) =>
            val snapshot = ConstructionKernel.inspect(construct(), EmitOptions(backend = backend))
            val document = ScalaToMlirBridge.fromSnapshot(snapshot, backend)
            val stem = snapshot.root + "." + extension
            Files.writeString(directory.resolve(stem + ".source.mlir"), document.text)
            val gate = Vector("--pass-pipeline=builtin.module(nodal-gate-default)")
            val normalized = run(document, nodalc, gate, directory)
            assert(normalized == run(document.copy(text = normalized), nodalc, gate, directory))
            Files.writeString(directory.resolve(stem + ".normalized.mlir"), normalized)
            val flag = if backend == Backend.VerilogA then "--nodal-to-verilog-a"
            else "--nodal-to-verilog-ams"
            val output = run(document.copy(text = normalized), translator, Vector(flag), directory)
            assert(output ==
              run(document.copy(text = normalized), translator, Vector(flag), directory))
            Files.writeString(directory.resolve(stem), output)
            val records =
              output.linesIterator.filter(_.trim.startsWith("// nodal.node_array ")).toVector
            assert(records.nonEmpty)
            records.foreach: record =>
              val name = "\"target_name\":\"([^\"]+)\"".r.findFirstMatchIn(record).get.group(1)
              val scope = "\"source_scope\":\"([^\"]+)\"".r.findFirstMatchIn(record).get.group(1)
              val path = "\"source_path\":\"([^\"]+)\"".r.findFirstMatchIn(record).get.group(1)
              val declaration = snapshot.modules.flatMap(_.declarations).find(_.path == path).get
              val owned = declaration.generatedScope.get
              val span = snapshot.sourceMap.find(_.semanticPath == path).get.source
              assert(scope == owned.owner)
              assert(record.contains(s"\"source_induction\":\"${owned.induction}\""))
              assert(record.contains(
                s"\"dimensions\":\"${declaration.conservativeDimensions.mkString(",")}\""
              ))
              assert(record.contains(
                s"\"source_location\":\"${span.path}:${span.line}:${span.column}-${span.endLine}:${span.endColumn}\""
              ))
              assert(output.linesIterator.exists(line =>
                line.trim.startsWith("electrical [0:") && line.trim.endsWith(s"] $name;")
              ))
            snapshot.root match
              case "Increment43LocalArrayFixed" =>
                assert(
                  output.contains("electrical [0:5] taps;"),
                  output.contains("I(taps[5], common) <+ (V(taps[5], common) / 1000);")
                )
              case "Increment43LocalArraySymbolic" =>
                assert(output.contains("count + 1"), output.contains(".count((count + 1))"))
              case "Increment43LocalArrayNested" =>
                assert(normalized.linesIterator.count(_.contains("\"nodal.probe\"")) == 1)
                assert(output.contains("I(outputs["), output.contains("I(taps["))
              case "Increment43LocalArrayRelations" =>
                assert(output.contains("// nodal.array_binding "))
              case "Increment43LocalArraySiblings" =>
                assert(records.size == 3, output.contains("< -2;"))
              case "Increment43LocalArrayReusable" =>
                assert(document.text.linesIterator.count(_.contains("\"nodal.module\"")) == 2)
              case _ => ()

final class Increment43LocalArrayForeignChild(selected: Node[Electrical.type]) extends Module:
  val common = inout(Electrical)
  analog:
    I(common) <+ V(selected, common) / 1.kOhm

final class Increment43LocalArrayForeignCapture extends Module:
  hdlRange(0, 2): _ =>
    val taps = node(Electrical, 2)
    val _ = instance(new Increment43LocalArrayForeignChild(taps.at(0)))

final class Increment43LocalArrayIndexedChild extends Module:
  val p = inout(Electrical)
  val n = inout(Electrical)
  hdlRange(0, 2): outer =>
    val internal = node(Electrical, 2, 2)
    hdlRange(0, 2): inner =>
      analog:
        I(internal.at(outer, inner), n) <+ V(p, n) / 1.kOhm
  hdlRange(0, 2): lane =>
    val sibling = node(Electrical, 2)
    analog:
      I(sibling.at(lane), n) <+ V(p, n) / 1.kOhm

final class Increment43LocalArrayIndexedReusable extends Module:
  val p = inout(Electrical)
  val n = inout(Electrical)
  val first = instance(new Increment43LocalArrayIndexedChild)
  val second = instance(new Increment43LocalArrayIndexedChild)
  connect(p, first(_.p))
  connect(n, first(_.n))
  connect(p, second(_.p))
  connect(n, second(_.n))
