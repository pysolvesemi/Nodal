package nodal.internal.testkit

import java.nio.file.{Files, Path}
import java.time.Duration

import nodal.*
import nodal.internal.bridge.*
import utest.*

final class Increment43FixedNodeViews extends Module:
  val p = inout(Electrical)
  val taps = node(Electrical, 2, 3, 4)
  val plane = taps.plane(1)
  val row = plane.row(2)
  val selected = row.at(3)
  val direct = taps.at(1, 2, 3)
  val flat = taps.flatten.at(23)
  val reshaped = taps.reshape(4, 6).row(3).at(5)
  val firstSlice = taps.slice(1, 2).flatten.at(11)
  val noncontiguous = taps.slice(1, 4, axis = 2).plane(1).row(2).at(2)
  val other = taps.at(0, 0, 0)
  analog:
    I(selected, p) <+ V(noncontiguous, p) / 1.kOhm
    I(other, p) <+ V(flat, p) / 2.kOhm

final class Increment43SymbolicNodeViews(initialRows: Int = 3) extends Module:
  val p = inout(Electrical)
  val rows: Param[Integer] = param(initialRows.integer, range = 3 to 5)
  val cols: Param[Integer] = param(4.integer, range = 4 to 6)
  val lane: Param[Integer] = param(0.integer, range = 0 to 1)
  val otherLane: Param[Integer] = param(0.integer, range = 0 to 1)
  val taps = node(Electrical, rows, cols)
  val selected = taps.row(lane).at(2)
  val direct = taps.at(lane, 2)
  val fullSlice = taps.slice(0, rows).slice(0, cols, axis = 1).row(lane).at(2)
  val disjoint = taps.row(2).at(otherLane)
  analog:
    I(selected, p) <+ V(fullSlice, p) / 1.kOhm
    I(disjoint, p) <+ V(direct, p) / 2.kOhm

final class Increment43OffsetNodeViews extends Module:
  val p = inout(Electrical)
  val offset: Param[Integer] = param(1.integer, range = 1 to 2)
  val taps = node(Electrical, 6, 2)
  val selected = taps.slice(offset, 4).row(0).reshape(1, 2).at(0, 0)
  val direct = taps.at(offset, 0)
  analog:
    I(selected, p) <+ V(direct, p) / 1.kOhm

final class Increment43CompoundNodeViews(reuse: Boolean = true) extends Module:
  val p = inout(Electrical)
  val lane: Param[Integer] = param(0.integer, range = 0 to 1)
  val index: Expr[Integer] = lane + 1.integer
  val taps = node(Electrical, 3, 4)
  val selected = taps.row(index).at(2)
  val direct = taps.at(if reuse then index else lane + 1.integer, 2)
  analog:
    I(selected, p) <+ V(direct, p) / 1.kOhm

final class Increment43SingletonNodeViews extends Module:
  val taps = node(Electrical, 1, 1)
  val selected = taps.row(0).at(0)
  val direct = taps.flatten.reshape(1, 1).slice(0, 1, axis = 1).at(0, 0)

final class Increment43InlineNodeViews extends Module:
  val p = inout(Electrical)
  val lane: Param[Integer] = param(0.integer, range = 0 to 1)
  val taps = node(Electrical, 3, 4)
  analog:
    I(taps.row(lane + 1.integer).slice(0, 3.integer).at(2.integer), p) <+ 1.0.A

final class Increment43NodeViewChildBoundary extends Module:
  val p = inout(Electrical)
  val taps = node(Electrical, 2, 3)
  val child = instance(new Increment43NodeArrayChildPort)
  connect(taps.row(1).at(2), child(_.p))
  connect(p, child(_.n))

final class Increment43BadNodeView(form: Int) extends Module:
  val firstIndex: Param[Integer] = param(0.integer, range = 0 to 1)
  val secondIndex: Param[Integer] = param(0.integer, range = 0 to 1)
  val taps = node(Electrical, 2, 3)
  val selected = form match
    case 0 => taps.plane(0)
    case 1 => taps.row(2)
    case 2 => taps.reshape(5)
    case 3 => taps.slice(-1, 1)
    case 4 => taps.slice(1, 1)
    case 5 => taps.slice(0, 2, axis = 2)
    case 6 =>
      val first = taps.row(firstIndex).at(0)
      val second = taps.row(secondIndex).at(0)
      val _ = (first, second)
      taps
    case _ =>
      val first = taps.row(firstIndex).at(0)
      val second = taps.slice(0, 2).row(secondIndex).at(0)
      val _ = (first, second)
      taps

final class Increment43SingletonNodeViewAlias extends Module:
  val lane: Param[Integer] = param(0.integer, range = 0 to 0)
  val taps = node(Electrical, 1, 1)
  val symbolic = taps.row(0).at(lane)
  val literal = taps.flatten.at(0)

final class Increment43ForeignNodeView extends Module:
  val child = instance(new Increment43LocalNodeArrayChild)
  val selected = child.module.tap.reshape(1, 2)

final class Increment43WideNodeViews extends Module:
  val p = inout(Electrical)
  val taps = node(Electrical, 50000, 50000)
  val selected = taps.flatten.at(1)
  val direct = taps.at(0, 1)
  analog:
    I(selected, p) <+ V(direct, p) / 1.kOhm

final class Increment43OverflowNodeViews extends Module:
  val taps = node(Electrical, Int.MaxValue, Int.MaxValue, Int.MaxValue)
  val selected = taps.flatten.at(0)

final class Increment43NoncontiguousNodeViews extends Module:
  val p = inout(Electrical)
  val cols: Param[Integer] = param(5.integer, range = 4 to 6)
  val taps = node(Electrical, 3, cols)
  val selected = taps.slice(1, 3, axis = 1).flatten.at(3)
  val other = taps.at(0, 0)
  analog:
    I(selected, p) <+ V(other, p) / 1.kOhm

final class Increment43LiteralRadixNodeViews extends Module:
  val p = inout(Electrical)
  val cols: Param[Integer] = param(5.integer, range = 4 to 6)
  val taps = node(Electrical, 2, cols)
  val selected = taps.slice(1, 2, axis = 1).flatten.at(1)
  val direct = taps.at(1, 1)
  val other = taps.at(0, 0)
  analog:
    I(selected, p) <+ V(direct, p) / 1.kOhm
    I(other, p) <+ 0.0.A

final class Increment43SingletonMappedNodeAlias extends Module:
  val lane: Param[Integer] = param(0.integer, range = 0 to 0)
  val taps = node(Electrical, 1, 1)
  val selected = taps.flatten.at(lane)
  val direct = taps.at(0, 0)

object Increment43NodeViewTests extends TestSuite:
  private def failure(top: => Module, code: String): Unit =
    val error =
      scala.util.Try(ConstructionKernel.inspect(top)).failed.get.asInstanceOf[ConstructionException]
    assert(error.diagnostic.code == code)

  private def bridgeFailure(snapshot: ConstructionSnapshot): Unit =
    val error = scala.util.Try(
      ScalaToMlirBridge.fromSnapshot(snapshot)
    ).failed.get.asInstanceOf[BridgeException]
    assert(Set(
      "NODAL-BRIDGE-044",
      "NODAL-BRIDGE-045",
      "NODAL-BRIDGE-046",
      "NODAL-BRIDGE-048"
    ).contains(error.diagnostic.code))

  private def run(
      document: NodalMlirDocument,
      executable: Path,
      args: Vector[String],
      directory: Path
  ): String =
    NativeCompilerClient.run(
      document,
      NativeCompilerRequest(
        executable = executable,
        arguments = args,
        workingDirectory = directory,
        timeout = Duration.ofSeconds(60)
      )
    ) match
      case success: NativeCompilerSuccess => success.normalizedMlir
      case failure: NativeCompilerFailure =>
        throw new java.lang.AssertionError(s"${failure.diagnostic}\n${failure.standardError}")

  val tests: Tests = Tests:
    test("fixed node views share one physical endpoint through every map and preserve source uses"):
      val snapshot = ConstructionKernel.inspect(new Increment43FixedNodeViews)
      assert(snapshot.modules.head.declarations.count(_.kind == "analog-node-array") == 1)
      val endpoints = snapshot.shapeIndices.filter(_.sourceUses.nonEmpty)
      assert(endpoints.size == 2)
      val shared = endpoints.find(_.sourceUses.size == 6).get
      assert(shared.input != s"${snapshot.root}.taps")
      shared.sourceUses.foreach: path =>
        val source = snapshot.sourceMap.find(_.semanticPath == path).get.source
        assert(source.path.endsWith("Increment43NodeViewTests.scala"), source.line > 0)
      assert(snapshot.shapeViews.nonEmpty)
      val singleton = ConstructionKernel.inspect(new Increment43SingletonNodeViews)
      assert(singleton.shapeIndices.count(_.sourceUses.nonEmpty) == 1)
      assert(singleton.shapeIndices.find(_.sourceUses.nonEmpty).get.sourceUses.size == 2)
      assert(ScalaToMlirBridge.fromSnapshot(singleton).text.contains("nodal.shape_view"))
      val text = ScalaToMlirBridge.fromSnapshot(snapshot).text
      assert(text.sliding("\"nodal.node_array\"".length).count(_ == "\"nodal.node_array\"") == 1)
      assert(text.contains("\"nodal.shape_view\""), text.contains("\"nodal.shape_slice\""))
      assert(text == ScalaToMlirBridge.fromSnapshot(
        ConstructionKernel.inspect(new Increment43FixedNodeViews)
      ).text)

    test("symbolic prefix and full extent views retain exact atoms and disjoint root axes"):
      val snapshot = ConstructionKernel.inspect(new Increment43SymbolicNodeViews)
      val endpoints = snapshot.shapeIndices.filter(_.sourceUses.nonEmpty)
      assert(endpoints.size == 2, endpoints.exists(_.sourceUses.size == 3))
      val changed = ConstructionKernel.inspect(new Increment43SymbolicNodeViews(5))
      assert(changed.shapeIndices.map(_.path) == snapshot.shapeIndices.map(_.path))
      assert(
        ScalaToMlirBridge.fromSnapshot(snapshot).text.contains("!nodal.terminal<\"electrical\">")
      )
      val radix = ConstructionKernel.inspect(new Increment43LiteralRadixNodeViews)
      assert(radix.shapeIndices.count(_.sourceUses.nonEmpty) == 2)
      assert(radix.shapeIndices.exists(_.sourceUses.size == 2))
      assert(ScalaToMlirBridge.fromSnapshot(radix).text.contains("nodal.shape_slice"))
      val noncontiguous = ConstructionKernel.inspect(new Increment43NoncontiguousNodeViews)
      assert(noncontiguous.shapeIndices.count(_.sourceUses.nonEmpty) == 2)
      assert(ScalaToMlirBridge.fromSnapshot(noncontiguous).text.contains("nodal.shape_slice"))
      val compound = ConstructionKernel.inspect(new Increment43CompoundNodeViews)
      assert(compound.shapeIndices.count(_.sourceUses.nonEmpty) == 1)
      assert(compound.shapeIndices.find(_.sourceUses.nonEmpty).get.sourceUses.size == 2)
      assert(ScalaToMlirBridge.fromSnapshot(compound).text.contains("nodal.const_expr"))
      failure(new Increment43CompoundNodeViews(false), "NODAL-SHAPE-043-006")
      val offset = ConstructionKernel.inspect(new Increment43OffsetNodeViews)
      assert(offset.shapeIndices.count(_.sourceUses.nonEmpty) == 1)
      assert(offset.shapeIndices.find(_.sourceUses.nonEmpty).get.sourceUses.size == 2)
      assert(ScalaToMlirBridge.fromSnapshot(offset).text.contains("\"nodal.const_expr\""))

    test("inline views remain structural and selected child connections retain scalar semantics"):
      val inline = ConstructionKernel.inspect(new Increment43InlineNodeViews)
      assert(inline.analogRegions.flatMap(_.expressions).forall(expression =>
        !expression.operation.startsWith("shape_") && expression.operation != "analog_add" &&
          expression.operation != "analog_integer_literal"
      ))
      assert(ScalaToMlirBridge.fromSnapshot(inline).text.contains("\"nodal.shape_slice\""))
      val hierarchy = ConstructionKernel.inspect(new Increment43NodeViewChildBoundary)
      assert(hierarchy.topology.size == 2)
      assert(ScalaToMlirBridge.fromSnapshot(hierarchy).text.contains("\"nodal.connect\""))

    test("invalid rank cardinality bounds foreign owners and possible view aliases reject"):
      Vector(0, 1).foreach(form => failure(new Increment43BadNodeView(form), "NODAL-SHAPE-043-002"))
      failure(new Increment43BadNodeView(2), "NODAL-SHAPE-043-003")
      Vector(3, 4, 5).foreach(form =>
        failure(new Increment43BadNodeView(form), "NODAL-SHAPE-043-004")
      )
      Vector(6, 7).foreach(form => failure(new Increment43BadNodeView(form), "NODAL-SHAPE-043-006"))
      failure(new Increment43ForeignNodeView, "NODAL-SHAPE-043-006")
      failure(new Increment43SingletonNodeViewAlias, "NODAL-SHAPE-043-006")
      failure(new Increment43SingletonMappedNodeAlias, "NODAL-SHAPE-043-006")

    test("wide flatten retains signed64 shape proof and fixed endpoint identity"):
      val snapshot = ConstructionKernel.inspect(new Increment43WideNodeViews)
      assert(snapshot.shapeViews.head.dimensions == Vector("2500000000"))
      assert(snapshot.shapeIndices.count(_.sourceUses.nonEmpty) == 1)
      assert(snapshot.shapeIndices.find(_.sourceUses.nonEmpty).get.sourceUses.size == 2)
      assert(ScalaToMlirBridge.fromSnapshot(snapshot).text.contains("2500000000"))
      failure(new Increment43OverflowNodeViews, "NODAL-SHAPE-043-005")

    test("bridge rejects forged view graphs dimensions owners and physical duplicates"):
      val snapshot = ConstructionKernel.inspect(new Increment43FixedNodeViews)
      val view = snapshot.shapeViews.head
      bridgeFailure(snapshot.copy(shapeViews =
        snapshot.shapeViews.map(value =>
          if value == view then value.copy(input = value.path) else value
        )
      ))
      bridgeFailure(snapshot.copy(shapeViews =
        snapshot.shapeViews.map(value =>
          if value == view then value.copy(input = s"${snapshot.root}.missing") else value
        )
      ))
      bridgeFailure(snapshot.copy(shapeViews =
        snapshot.shapeViews.map(value =>
          if value == view then value.copy(dimensions = Vector("25")) else value
        )
      ))
      bridgeFailure(snapshot.copy(shapeViews =
        snapshot.shapeViews.map(value =>
          if value == view then value.copy(owner = "foreign") else value
        )
      ))
      val other = snapshot.shapeIndices.find(_.sourceUses.size == 1).get
      val duplicatePath = s"${snapshot.root}.taps.at(literal:1,literal:2,literal:3)"
      val physicalDuplicate = snapshot.copy(
        shapeIndices = snapshot.shapeIndices.map(value =>
          if value == other then value.copy(indices = Vector("1", "2", "3"), path = duplicatePath)
          else value
        ),
        sourceMap = snapshot.sourceMap.map(entry =>
          if entry.semanticPath == other.path then entry.copy(semanticPath = duplicatePath)
          else entry
        )
      )
      val duplicateError = scala.util.Try(
        ScalaToMlirBridge.fromSnapshot(physicalDuplicate)
      ).failed.get.asInstanceOf[BridgeException]
      assert(duplicateError.diagnostic.code == "NODAL-BRIDGE-048")
      assert(duplicateError.diagnostic.message.contains(
        "duplicate canonical node-array physical endpoint"
      ))
      val symbolic = ConstructionKernel.inspect(new Increment43SymbolicNodeViews)
      val endpoint = symbolic.shapeIndices.find(_.sourceUses.size == 3).get
      val disjoint = symbolic.shapeIndices.find(_.sourceUses.size == 1).get
      bridgeFailure(symbolic.copy(shapeIndices =
        symbolic.shapeIndices.map(value =>
          if value == disjoint then
            value.copy(input = endpoint.input, indices = endpoint.indices, path = endpoint.path)
          else value
        )
      ))

    test("actual fixed symbolic noncontiguous and child view endpoints emit deterministically"):
      (sys.env.get("NODAL_NODALC"), sys.env.get("NODAL_TRANSLATE")) match
        case (Some(nodalc), Some(translator)) =>
          val root = Path.of(".work", "node-view-public").toAbsolutePath
          Files.createDirectories(root)
          val models = Vector[() => Module](
            () => new Increment43FixedNodeViews,
            () => new Increment43SymbolicNodeViews,
            () => new Increment43OffsetNodeViews,
            () => new Increment43CompoundNodeViews,
            () => new Increment43WideNodeViews,
            () => new Increment43LiteralRadixNodeViews,
            () => new Increment43NoncontiguousNodeViews,
            () => new Increment43InlineNodeViews,
            () => new Increment43NodeViewChildBoundary
          )
          models.foreach: construct =>
            val snapshot = ConstructionKernel.inspect(construct())
            val document = ScalaToMlirBridge.fromSnapshot(snapshot)
            Files.writeString(root.resolve(s"${snapshot.root}.mlir"), document.text)
            val normalized = run(
              document,
              Path.of(nodalc),
              Vector("--pass-pipeline=builtin.module(nodal-gate-default)"),
              root
            )
            val output = run(
              document.copy(text = normalized),
              Path.of(translator),
              Vector("--nodal-to-verilog-a"),
              root
            )
            val repeat = run(
              document.copy(text = normalized),
              Path.of(translator),
              Vector("--nodal-to-verilog-a"),
              root
            )
            assert(output == repeat, !output.contains("node_array"))
            Files.writeString(root.resolve(s"${snapshot.root}.va"), output)
            if snapshot.root == "Increment43FixedNodeViews" then
              assert(output.contains("[23]"), output.contains("[0]"))
            if snapshot.root == "Increment43WideNodeViews" then
              assert(output.contains("2499999999"), output.contains("[1]"))
              assert(!output.contains("node_map_"))
            if snapshot.root == "Increment43NoncontiguousNodeViews" then
              assert(output.contains("localparam integer node_map_"))
        case _ => ()
