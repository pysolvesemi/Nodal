package nodal.internal.testkit

import java.nio.file.{Files, Path}
import java.time.Duration
import nodal.*
import nodal.internal.bridge.*
import utest.*

final class Increment43RelationChild extends Module:
  val count: Param[Integer] = param(4.integer, range = 2 to 2000000)
  val p = inout(Electrical, count)
  analog:
    I(p.at(0), p.at(1)) <+ V(p.at(0), p.at(1)) / 1.kOhm

final class Increment43RelationSplit extends Module:
  val taps = node(Electrical, 4)
  val child = instance(new Increment43RelationChild)
  val lower = child(_.p.slice(0, 2))
  val upper = child(_.p).slice(2, 4)
  taps.slice(0, 2) <> lower
  upper <> taps.slice(2, 4)
  child.param(_.count, 4.integer)

final class Increment43RelationGridChild extends Module:
  val rows: Param[Integer] = param(2.integer, range = 2 to 5)
  val columns: Param[Integer] = param(rows + 1.integer, range = 3 to 6)
  val p = inout(Electrical, rows, columns)
  analog:
    I(p.at(0, 0), p.at(0, 1)) <+ V(p.at(0, 0), p.at(0, 1)) / 1.kOhm

final class Increment43RelationDependent extends Module:
  val count: Param[Integer] = param(3.integer, range = 2 to 5)
  val width: Expr[Integer] = count + 1.integer
  val taps = node(Electrical, count, width)
  val child = instance(new Increment43RelationGridChild)
  val first = child(_.p.slice(0, 1, axis = 1).flatten)
  val rest = child(_.p).slice(1, width, axis = 1)
  connect(first, taps.slice(0, 1, axis = 1).flatten)
  connect(taps.slice(1, width, axis = 1), rest)
  child.param(_.rows, count)

final class Increment43RelationChildJoin extends Module:
  val taps = node(Electrical, 2)
  val first = instance(new Increment43RelationChild)
  val second = instance(new Increment43RelationChild)
  connect(first(_.p.slice(0, 2)), second(_.p.slice(0, 2)))
  connect(taps, first(_.p.slice(2, 4)))
  connect(second(_.p.slice(2, 4)), taps)

final class Increment43RelationScalar extends Module:
  val p = inout(Electrical)
  val n = inout(Electrical)
  val child = instance(new Increment43RelationChild)
  val first = child(_.p.at(0))
  val repeated = child(_.p).flatten.at(0)
  first <> p
  child(_.p.at(1)) <> n
  child.param(_.count, 2.integer)

final class Increment43RelationLocalJoin extends Module:
  val first = node(Electrical, 2, 3)
  val second = node(Electrical, 2, 3)
  first.slice(0, 1, axis = 1) <> second.slice(2, 3, axis = 1)
  analog:
    I(first.at(0, 0), first.at(1, 0)) <+ V(second.at(0, 2), second.at(1, 2)) / 1.kOhm

final class Increment43RelationGenerated extends Module:
  val taps = node(Electrical, 4)
  hdlRange(0, 2): _ =>
    val child = instance(new Increment43RelationChild)
    taps.slice(0, 2) <> child(_.p.slice(0, 2))
    child(_.p.slice(2, 4)) <> taps.slice(2, 4)

final class Increment43RelationMillion extends Module:
  val taps = node(Electrical, 2000000)
  val child = instance(new Increment43RelationChild)
  taps.slice(0, 1000000) <> child(_.p.slice(0, 1000000))
  child(_.p.slice(1000000, 2000000)) <> taps.slice(1000000, 2000000)
  child.param(_.count, 2000000.integer)

final class Increment43RelationHole extends Module:
  val taps = node(Electrical, 2)
  val child = instance(new Increment43RelationChild)
  taps <> child(_.p.slice(0, 2))

final class Increment43RelationOverlap extends Module:
  val taps = node(Electrical, 3)
  val child = instance(new Increment43RelationChild)
  taps <> child(_.p.slice(0, 3))
  taps <> child(_.p.slice(1, 4))

final class Increment43RelationGeneratedHole extends Module:
  val taps = node(Electrical, 2)
  hdlRange(0, 2): lane =>
    val child = instance(new Increment43RelationChild)
    child.param(_.count, 2.integer)
    taps.at(lane) <> child(_.p.at(lane))

final class Increment43RelationInternalChild extends Module:
  val p = inout(Electrical, 4)
  val internal = node(Electrical, 4)

final class Increment43RelationDerivedJoin extends Module:
  val count: Param[Integer] = param(3.integer, range = 2 to 5)
  val first = instance(new Increment43RelationGridChild)
  val second = instance(new Increment43RelationGridChild)
  first(_.p).flatten <> second(_.p).flatten
  first.param(_.rows, count)
  second.param(_.rows, count)

final class Increment43RelationUnrelatedDefault extends Module:
  val unbounded: Param[Integer] = param(1.integer)
  val unrelated: Param[Integer] = param(unbounded + 1.integer)
  val taps = node(Electrical, 4)
  val child = instance(new Increment43RelationChild)
  taps <> child(_.p)

final class Increment43RelationOwnershipFailure(form: Int) extends Module:
  val taps = node(Electrical, 4)
  if form <= 2 then
    val first = instance(new Increment43RelationInternalChild)
    val second = instance(new Increment43RelationInternalChild)
    if form == 0 then taps <> Vector(first(module => module), second(module => module)).head.p
    else if form == 1 then taps <> first(_.internal)
    else
      val detached = new Increment43RelationInternalChild
      taps <> detached.p
  else if form == 3 then
    var escaped: Option[NodeArray[Electrical.type]] = None
    hdlRange(0, 1): _ =>
      val child = instance(new Increment43RelationInternalChild)
      escaped = Some(child(_.p.slice(0, 2)))
    hdlRange(0, 1): _ =>
      taps.slice(0, 2) <> escaped.get
  else if form == 4 then
    hdlRange(3, 3): _ =>
      val child = instance(new Increment43RelationInternalChild)
      taps.slice(0, 2) <> child(_.p.slice(-1, 1))
  else
    val child = instance(new Increment43RelationChild)
    val selected = child(_.p.slice(2, 4))
    taps.slice(0, 2) <> selected
    child.param(_.count, 2.integer)

object Increment43ConservativeRelationTests extends TestSuite:
  val models: Vector[() => Module] = Vector(
    () => new Increment43RelationSplit,
    () => new Increment43RelationDependent,
    () => new Increment43RelationChildJoin,
    () => new Increment43RelationScalar,
    () => new Increment43RelationLocalJoin,
    () => new Increment43RelationGenerated,
    () => new Increment43RelationMillion,
    () => new Increment43RelationDerivedJoin
  )

  private def run(document: NodalMlirDocument, tool: Path, args: Vector[String], directory: Path)
      : NativeCompilerResult = NativeCompilerClient.run(
    document,
    NativeCompilerRequest(
      executable = tool,
      arguments = args,
      workingDirectory = directory,
      timeout = Duration.ofSeconds(90)
    )
  )

  private def success(result: NativeCompilerResult): String = result match
    case value: NativeCompilerSuccess => value.normalizedMlir
    case failure: NativeCompilerFailure =>
      throw new java.lang.AssertionError(s"${failure.diagnostic}\n${failure.standardError}")

  private def assertTarget(snapshot: ConstructionSnapshot, output: String): Unit =
    def contains(text: String): Unit = assert(output.contains(text))
    snapshot.root match
      case "Increment43RelationSplit" =>
        contains("electrical [0:(4 - 1)] binding_child_instance_p_bus;")
        contains("relation_1_stage_0 = (relation_1_lane + 2);")
        contains(
          ".lhs(binding_child_instance_p_bus[relation_1_stage_0]), .rhs(taps[relation_1_right_stage_0])"
        )
      case "Increment43RelationDependent" | "Increment43RelationDerivedJoin" =>
        contains("[0:((count * (count + 1)) - 1)]")
        contains("#(.rows(count))")
        assert(!output.contains("#(.rows(3))"))
        if snapshot.root == "Increment43RelationDependent" then
          contains(
            "relation_0_stage_0 = ((relation_0_lane * (count + 1)) + (relation_0_lane % 1));"
          )
          contains(
            "relation_1_right_stage_0 = (((relation_1_lane / ((count + 1) - 1)) * (count + 1)) + ((relation_1_lane % ((count + 1) - 1)) + 1));"
          )
      case "Increment43RelationChildJoin" =>
        contains("electrical [0:(4 - 1)] binding_first_instance_p_bus;")
        contains("electrical [0:(4 - 1)] binding_second_instance_p_bus;")
        contains(
          ".lhs(binding_first_instance_p_bus[relation_0_stage_0]), .rhs(binding_second_instance_p_bus[relation_0_right_stage_0])"
        )
        contains("first first_instance(.p(binding_first_instance_p_bus));")
        contains("first second_instance(.p(binding_second_instance_p_bus));")
      case "Increment43RelationScalar" =>
        contains("electrical [0:(2 - 1)] binding_child_instance_p_bus;")
        contains("relation_0_stage_0 = 0;")
        contains("relation_1_stage_0 = 1;")
        contains(".lhs(binding_child_instance_p_bus[relation_0_stage_0]), .rhs(p)")
        contains(".lhs(binding_child_instance_p_bus[relation_1_stage_0]), .rhs(n)")
      case "Increment43RelationLocalJoin" =>
        contains("relation_0_stage_0 = ((relation_0_lane * 3) + (relation_0_lane % 1));")
        contains(
          "relation_0_right_stage_0 = ((relation_0_lane * 3) + ((relation_0_lane % 1) + 2));"
        )
        contains("I(first[0], first[3]) <+ (V(second[2], second[5]) / 1000);")
      case "Increment43RelationGenerated" =>
        val loop = output.indexOf("begin : generate_0")
        val bus = output.indexOf("electrical [0:(4 - 1)] binding_child_instance_p_bus;")
        val child = output.indexOf("child child_instance(.p(binding_child_instance_p_bus));")
        assert(loop >= 0, loop < bus, bus < child)
      case "Increment43RelationMillion" =>
        contains("electrical [0:(2000000 - 1)] binding_child_instance_p_bus;")
        contains("relation_1_stage_0 = (relation_1_lane + 1000000);")
        assert(output.length < 20000)
      case _ => throw new java.lang.AssertionError("missing exact relation target assertion")

  val tests: Tests = Tests:
    test(
      "deferred child roots preserve full finalized shape and composed relation source identities"
    ):
      models.foreach: model =>
        val snapshot = ConstructionKernel.inspect(model())
        val document = ScalaToMlirBridge.fromSnapshot(snapshot)
        val repeated = ConstructionKernel.inspect(model())
        assert(document.text == ScalaToMlirBridge.fromSnapshot(repeated).text)
        snapshot.instanceArrayRoots.foreach: root =>
          assert(root.dimensions.nonEmpty, snapshot.sourceMap.exists(_.semanticPath == root.path))
          assert(document.text.contains(root.path))
        snapshot.conservativeArrayBindings.foreach: relation =>
          assert(snapshot.sourceMap.exists(_.semanticPath == relation.path))
          assert(document.text.contains(relation.left), document.text.contains(relation.right))
          assert(
            snapshot.sourceMap.exists(_.semanticPath == relation.left),
            snapshot.sourceMap.exists(_.semanticPath == relation.right)
          )
        assert(ReproducibilityContract.describe(snapshot) ==
          ReproducibilityContract.describe(repeated))
      val dependent = ConstructionKernel.inspect(new Increment43RelationDependent)
      val root = dependent.instanceArrayRoots.head
      val actual =
        dependent.modules.find(_.path == dependent.root).get.declarations.find(_.name == "taps").get
      assert(root.dimensions == actual.conservativeDimensions, root.dimensions.head == "count")
      val width = dependent.parameterExpressions.find(_.path == root.dimensions(1)).get
      assert(width.operation == "analog_add", width.operands.head == s"${dependent.root}.count")
      val scalar = ConstructionKernel.inspect(new Increment43RelationScalar)
      val selected = scalar.shapeIndices.filter(_.owner == scalar.root)
      assert(selected.size == 2, selected.map(_.sourceUses.size).sorted == Vector(1, 2))
      assert(scalar.instanceArrayRoots.head.dimensions == Vector("2"))
      val joined = ConstructionKernel.inspect(new Increment43RelationChildJoin)
      assert(joined.instanceArrayRoots.size == 2)
      assert(joined.instanceArrayRoots.map(_.instance).distinct.size == 2)

    test("unrelated Integer defaults do not acquire child shape proof obligations"):
      val snapshot = ConstructionKernel.inspect(new Increment43RelationUnrelatedDefault)
      assert(snapshot.instanceArrayRoots.head.dimensions == Vector("4"))
      val text = ScalaToMlirBridge.fromSnapshot(snapshot).text
      assert(text.contains("nodal.parameter_value"), text.contains("unrelated"))

    test(
      "finalized instance shape and retained projection snapshots reject forged ownership and extents"
    ):
      val snapshot = ConstructionKernel.inspect(new Increment43RelationSplit)
      val root = snapshot.instanceArrayRoots.head
      val relation = snapshot.conservativeArrayBindings.head
      val invalid = Vector(
        snapshot.copy(instanceArrayRoots = Vector(root.copy(dimensions = Vector("2")))),
        snapshot.copy(instanceArrayRoots = Vector(root.copy(instance = "foreign"))),
        snapshot.copy(conservativeArrayBindings = Vector(relation.copy(left = root.port))),
        snapshot.copy(shapeViews =
          snapshot.shapeViews.map(view =>
            if view.input == root.path then view.copy(input = root.port) else view
          )
        )
      )
      invalid.foreach: value =>
        val error = scala.util.Try(ScalaToMlirBridge.fromSnapshot(value)).failed.get
        assert(error.isInstanceOf[BridgeException])

    test(
      "instance qualification lexical ownership and empty domain shape obligations reject causally"
    ):
      Vector(
        0 -> "NODAL-SHAPE-043-007",
        1 -> "NODAL-SHAPE-043-007",
        2 -> "NODAL-SHAPE-043-007",
        3 -> "NODAL-HIERARCHY-044",
        4 -> "NODAL-SHAPE-043-004",
        5 -> "NODAL-SHAPE-043-004"
      ).foreach: (form, code) =>
        val error =
          scala.util.Try(ConstructionKernel.inspect(new Increment43RelationOwnershipFailure(form)))
            .failed.get.asInstanceOf[ConstructionException]
        assert(error.diagnostic.code == code)

    test("actual symmetric relations and child partitions emit VA and AMS with compact full buses"):
      (sys.env.get("NODAL_NODALC"), sys.env.get("NODAL_TRANSLATE")) match
        case (Some(nodalc), Some(translator)) =>
          val directory = Path.of(".work", "conservative-relations-public-final").toAbsolutePath
          Files.createDirectories(directory)
          models.foreach: model =>
            Vector(Backend.VerilogA -> "va", Backend.VerilogAMS -> "vams").foreach:
              (backend, extension) =>
                val snapshot = ConstructionKernel.inspect(model(), EmitOptions(backend = backend))
                val source = ScalaToMlirBridge.fromSnapshot(snapshot, backend)
                val stem = s"${snapshot.root}.$extension"
                Files.writeString(directory.resolve(stem + ".source.mlir"), source.text)
                val normalized = success(run(
                  source,
                  Path.of(nodalc),
                  Vector("--pass-pipeline=builtin.module(nodal-gate-default)"),
                  directory
                ))
                Files.writeString(directory.resolve(stem + ".normalized.mlir"), normalized)
                val flag = if backend == Backend.VerilogA then "--nodal-to-verilog-a"
                else "--nodal-to-verilog-ams"
                val output = success(run(
                  source.copy(text = normalized),
                  Path.of(translator),
                  Vector(flag),
                  directory
                ))
                assert(output == success(run(
                  source.copy(text = normalized),
                  Path.of(translator),
                  Vector(flag),
                  directory
                )))
                Files.writeString(directory.resolve(stem), output)
                assert(output.contains("electrical"), output.length < 80000)
                assertTarget(snapshot, output)
                snapshot.conservativeArrayBindings.foreach: relation =>
                  val record =
                    output.linesIterator.find(_.contains(s"\"binding\":\"${relation.path}\"")).get
                  assert(record.contains("\"left\":"), record.contains("\"right\":"))
                  assert(
                    record.contains(s"\"reference\":\"${relation.left}\""),
                    record.contains(s"\"reference\":\"${relation.right}\"")
                  )
                  val span = snapshot.sourceMap.find(_.semanticPath == relation.path).get.source
                  val location =
                    s"${span.path}:${span.line}:${span.column}-${span.endLine}:${span.endColumn}"
                  assert(record.contains(s"\"source_location\":\"$location\""))
        case _ => ()

    test("native coverage rejects holes overlaps and per occurrence generated singleton gaps"):
      sys.env.get("NODAL_NODALC").foreach: nodalc =>
        val directory = Path.of(".work", "conservative-relations-negative").toAbsolutePath
        Files.createDirectories(directory)
        Vector[() => Module](
          () => new Increment43RelationHole,
          () => new Increment43RelationOverlap,
          () => new Increment43RelationGeneratedHole
        ).foreach: model =>
          val snapshot = ConstructionKernel.inspect(model())
          val source = ScalaToMlirBridge.fromSnapshot(snapshot)
          Files.writeString(directory.resolve(snapshot.root + ".mlir"), source.text)
          run(source, Path.of(nodalc), Vector.empty, directory) match
            case failure: NativeCompilerFailure =>
              assert(failure.standardError.contains("NODAL-SHAPE-043-009"))
            case _ => throw new java.lang.AssertionError("invalid child coverage was accepted")
