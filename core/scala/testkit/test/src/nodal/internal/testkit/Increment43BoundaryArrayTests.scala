package nodal.internal.testkit

import java.nio.file.{Files, Path}
import java.time.Duration

import nodal.*
import nodal.internal.bridge.*
import utest.*

final class Increment43BoundaryDirections extends Module:
  val source = in(Electrical, 2, 3)
  val drain = out(Electrical, 2, 3)
  val both = inout(Electrical, 1)
  val selected = source.row(1).at(2)
  analog:
    I(selected, drain.at(1, 2)) <+ V(selected, both.at(0)) / 1.kOhm

final class Increment43BoundaryChild extends Module:
  val source = in(Electrical, 2, 3)
  val drain = out(Electrical, 2, 3)
  val both = inout(Electrical, 2, 3)
  analog:
    I(source.at(0, 0), drain.at(1, 2)) <+ V(both.at(0, 0), drain.at(1, 2)) / 1.kOhm

final class Increment43WholeArrayBindings extends Module:
  val port = inout(Electrical, 2, 3)
  val nets = node(Electrical, 2, 3)
  val child = instance(new Increment43BoundaryChild)
  connect(port, child(_.source))
  child(_.drain) <> nets
  connect(child(_.both), port)

final class Increment43LinearArrayChild extends Module:
  val count: Param[Integer] = param(3.integer, range = 2 to 16)
  val p = inout(Electrical, count)
  analog:
    I(p.at(0), p.at(1)) <+ V(p.at(0), p.at(1)) / 1.kOhm

final class Increment43ContiguousArrayBinding extends Module:
  val taps = node(Electrical, 2, 3)
  val child = instance(new Increment43LinearArrayChild)
  connect(taps.row(1), child(_.p))

final class Increment43GridArrayChild extends Module:
  val rows: Param[Integer] = param(2.integer, range = 2 to 5)
  val p = inout(Electrical, rows, 2)
  analog:
    I(p.at(0, 0), p.at(0, 1)) <+ V(p.at(0, 0), p.at(0, 1)) / 1.kOhm

final class Increment43NoncontiguousArrayBinding extends Module:
  val rows: Param[Integer] = param(2.integer, range = 2 to 5)
  val taps = node(Electrical, rows, 4)
  val child = instance(new Increment43GridArrayChild)
  val actual = taps.slice(1, 3, axis = 1).flatten.reshape(rows, 2)
  connect(actual, child(_.p))
  child.param(_.rows, rows)

final class Increment43DependentArrayChild extends Module:
  val count: Param[Integer] = param(2.integer, range = 2 to 5)
  val next: Param[Integer] = param(count + 1.integer, range = 3 to 6)
  val p = inout(Electrical, next)
  analog:
    I(p.at(0), p.at(1)) <+ V(p.at(0), p.at(1)) / 1.kOhm

final class Increment43DependentArrayBinding extends Module:
  val count: Param[Integer] = param(2.integer, range = 2 to 5)
  val extent: Expr[Integer] = count + 1.integer
  val taps = inout(Electrical, extent)
  val child = instance(new Increment43DependentArrayChild)
  connect(taps, child(_.p))
  child.param(_.count, count)

final class Increment43DefaultArrayBinding extends Module:
  val taps = node(Electrical, 3)
  val child = instance(new Increment43DependentArrayChild)
  taps <> child(_.p)

final class Increment43ExpressionArrayChild extends Module:
  val count: Param[Integer] = param(2.integer, range = 2 to 5)
  val p = inout(Electrical, 2.integer * count)
  analog:
    I(p.at(0), p.at(1)) <+ V(p.at(0), p.at(1)) / 1.kOhm

final class Increment43ExpressionArrayBinding extends Module:
  val count: Param[Integer] = param(2.integer, range = 2 to 5)
  val extent: Expr[Integer] = 2.integer * count
  val taps = node(Electrical, extent)
  val child = instance(new Increment43ExpressionArrayChild)
  child.param(_.count, count)
  taps <> child(_.p)

final class Increment43SharedExpressionArrayBinding extends Module:
  val count: Param[Integer] = param(2.integer, range = 2 to 5)
  val extent: Expr[Integer] = 2.integer * count
  val taps = node(Electrical, extent)
  val first = instance(new Increment43LinearArrayChild)
  val second = instance(new Increment43LinearArrayChild)
  connect(first(_.p), taps)
  first.param(_.count, extent)
  second.param(_.count, extent)
  taps <> second(_.p)

final class Increment43LiteralArrayBinding extends Module:
  val taps = node(Electrical, 5)
  val child = instance(new Increment43LinearArrayChild)
  taps <> child(_.p)
  child.param(_.count, 5.integer)

final class Increment43DistinctArrayBindings extends Module:
  val firstCount: Param[Integer] = param(2.integer, range = 2 to 5)
  val secondCount: Param[Integer] = param(3.integer, range = 3 to 6)
  val firstTaps = node(Electrical, firstCount)
  val secondTaps = node(Electrical, secondCount)
  val first = instance(new Increment43LinearArrayChild)
  val second = instance(new Increment43LinearArrayChild)
  first.param(_.count, firstCount)
  second.param(_.count, secondCount)
  connect(firstTaps, first(_.p))
  connect(secondTaps, second(_.p))

final class Increment43UnprovedAlgebraArrayBinding extends Module:
  val count: Param[Integer] = param(2.integer, range = 2 to 5)
  val taps = node(Electrical, count + count)
  val child = instance(new Increment43ExpressionArrayChild)
  child.param(_.count, count)
  taps <> child(_.p)

final class Increment43BadArrayBinding(form: Int) extends Module:
  val count: Param[Integer] = param(3.integer, range = 2 to 5)
  val independent: Param[Integer] = param(3.integer, range = 2 to 5)
  val wide: Param[Integer] = param(3.integer, range = 1 to 6)
  val taps = node(Electrical, if form == 0 then 4 else 3)
  val shaped = node(Electrical, 1, 3)
  val symbolic = node(Electrical, count + 1.integer)
  val child = instance(new Increment43DependentArrayChild)
  form match
    case 0 => connect(taps, child(_.p))
    case 1 => connect(shaped, child(_.p))
    case 2 => connect(taps, child(_.p)); connect(taps, child(_.p))
    case 3 => ()
    case 4 => connect(symbolic, child(_.p)); child.param(_.count, independent)
    case 5 => connect(symbolic, child(_.p)); child.param(_.count, wide)
    case 6 => connect(symbolic, child(_.p))
    case 7 => connect(taps, child(_.p).flatten)
    case 8 => connect(taps, symbolic)
    case _ =>
      val wrong = node(Electrical, count + count)
      connect(wrong, child(_.p))
      child.param(_.count, count)

object Increment43BoundaryArrayTests extends TestSuite:
  private def failure(top: => Module, code: String, fragment: String): Unit =
    val error =
      scala.util.Try(ConstructionKernel.inspect(top)).failed.get.asInstanceOf[ConstructionException]
    assert(error.diagnostic.code == code, error.diagnostic.message.contains(fragment))

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
        timeout = Duration.ofSeconds(90)
      )
    ) match
      case value: NativeCompilerSuccess => value.normalizedMlir
      case error: NativeCompilerFailure =>
        throw new java.lang.AssertionError(s"${error.diagnostic}\n${error.standardError}")

  val models: Vector[() => Module] = Vector(
    () => new Increment43BoundaryDirections,
    () => new Increment43WholeArrayBindings,
    () => new Increment43ContiguousArrayBinding,
    () => new Increment43NoncontiguousArrayBinding,
    () => new Increment43DependentArrayBinding,
    () => new Increment43DefaultArrayBinding,
    () => new Increment43ExpressionArrayBinding,
    () => new Increment43SharedExpressionArrayBinding,
    () => new Increment43LiteralArrayBinding,
    () => new Increment43DistinctArrayBindings
  )

  val tests: Tests = Tests:
    test("boundary roots and whole bindings retain exact owned shapes and source occurrences"):
      val roots = ConstructionKernel.inspect(new Increment43BoundaryDirections)
      assert(
        roots.modules.head.declarations.filter(_.conservativeDimensions.nonEmpty).map(_.kind) ==
          Vector("analog-input", "analog-output", "analog-inout")
      )
      val rootText = ScalaToMlirBridge.fromSnapshot(roots).text
      assert(rootText.contains("nodal.terminal_array"), rootText.contains("nodal.shape_index"))
      val snapshot = ConstructionKernel.inspect(new Increment43WholeArrayBindings)
      assert(snapshot.conservativeArrayBindings.size == 3)
      snapshot.conservativeArrayBindings.foreach: binding =>
        assert(binding.owner == snapshot.root, binding.instance.contains(".child"))
        val source = snapshot.sourceMap.find(_.semanticPath == binding.path).get.source
        assert(source.path.endsWith("Increment43BoundaryArrayTests.scala"), source.line > 0)
      val text = ScalaToMlirBridge.fromSnapshot(snapshot).text
      assert(text.sliding("\"nodal.connect_array\"".length).count(_ == "\"nodal.connect_array\"") ==
        3)
      assert(text.contains("nodal.instance_terminal_array"))
      assert(text == ScalaToMlirBridge.fromSnapshot(
        ConstructionKernel.inspect(new Increment43WholeArrayBindings)
      ).text)

    test("finalized overrides and dependent Integer defaults preserve exact parent DAGs"):
      Vector[() => Module](
        () => new Increment43DependentArrayBinding,
        () => new Increment43DefaultArrayBinding,
        () => new Increment43ExpressionArrayBinding,
        () => new Increment43SharedExpressionArrayBinding,
        () => new Increment43NoncontiguousArrayBinding
      ).foreach: model =>
        val snapshot = ConstructionKernel.inspect(model())
        val text = ScalaToMlirBridge.fromSnapshot(snapshot).text
        assert(text.contains("nodal.connect_array"))
      val dependent = ConstructionKernel.inspect(new Increment43DependentArrayBinding)
      val child = dependent.modules.find(_.className == "Increment43DependentArrayChild").get
      val next = child.declarations.find(_.name == "next").get
      assert(next.attributes.toMap.apply("default").startsWith(child.path + "."))
      assert(dependent.parameterExpressions.exists(_.path ==
        next.attributes.toMap.apply("default")))
      val text = ScalaToMlirBridge.fromSnapshot(dependent).text
      assert(text.contains("nodal.parameter_value"))
      assert(StructuralParameterBinding.defaultInteger(dependent, child.path, next.path) == 3L)
      assert(child.declarations.find(
        _.name == "count"
      ).get.attributes.toMap.get("structural_effects").exists(_.contains("shape")))
      val shared = ConstructionKernel.inspect(new Increment43SharedExpressionArrayBinding)
      val extent = shared.parameterExpressions.find(value =>
        value.owner == shared.root && value.operation == "analog_mul"
      ).get
      val sharedText = ScalaToMlirBridge.fromSnapshot(shared).text
      val identity = s"semantic_path = \"${extent.path}\""
      assert(sharedText.linesIterator.count(line =>
        line.contains("\"nodal.const_expr\"") && line.contains(identity)
      ) == 1)

    test(
      "reusable array definitions ignore provenance but retain default body and shape distinctions"
    ):
      val snapshot = ConstructionKernel.inspect(new Increment43SharedExpressionArrayBinding)
      def childCount(value: ConstructionSnapshot): Int =
        ScalaToMlirBridge.fromSnapshot(value).text.linesIterator.count(line =>
          line.contains("\"nodal.module\"") &&
            line.contains("class_name = \"Increment43LinearArrayChild\"")
        )
      assert(childCount(snapshot) == 1)
      val second = snapshot.modules.find(_.path.endsWith(".second")).get
      val changedDefault = snapshot.copy(modules =
        snapshot.modules.map(module =>
          if module.path == second.path then
            module.copy(declarations =
              module.declarations.map(declaration =>
                if declaration.name == "count" then
                  declaration.copy(attributes = declaration.attributes.map {
                    case ("default", _) => "default" -> "4"
                    case value => value
                  })
                else declaration
              )
            )
          else module
        )
      )
      assert(childCount(changedDefault) == 2)
      val changedBody = snapshot.copy(analogRegions =
        snapshot.analogRegions.map(region =>
          if region.module == second.path then
            region.copy(expressions =
              region.expressions.map(expression =>
                if expression.literal.contains("1000.0") then
                  expression.copy(literal = Some("2000.0"))
                else expression
              )
            )
          else region
        )
      )
      assert(changedBody != snapshot, childCount(changedBody) == 2)
      val changedShape = snapshot.copy(modules =
        snapshot.modules.map(module =>
          if module.path == second.path then
            module.copy(declarations =
              module.declarations.map(declaration =>
                if declaration.name == "p" then
                  declaration.copy(conservativeDimensions = Vector("4"))
                else declaration
              )
            )
          else module
        )
      )
      assert(scala.util.Try(ScalaToMlirBridge.fromSnapshot(changedShape)).isFailure)
      val sharedText = ScalaToMlirBridge.fromSnapshot(snapshot).text
      snapshot.shapeIndices.filter(_.owner == second.path).flatMap(_.sourceUses).foreach(path =>
        assert(sharedText.contains(path))
      )

    test("rank extent independent defaults envelopes and unsupported ownership reject causally"):
      failure(new Increment43UnprovedAlgebraArrayBinding, "NODAL-SHAPE-043-007", "extent")
      failure(new Increment43BadArrayBinding(0), "NODAL-SHAPE-043-007", "extent")
      failure(new Increment43BadArrayBinding(1), "NODAL-SHAPE-043-007", "rank")
      failure(new Increment43BadArrayBinding(2), "NODAL-HIERARCHY-043", "more than once")
      failure(new Increment43BadArrayBinding(3), "NODAL-HIERARCHY-043", "whole binding")
      failure(new Increment43BadArrayBinding(4), "NODAL-SHAPE-043-007", "extent")
      failure(new Increment43BadArrayBinding(5), "NODAL-SHAPE-043-007", "envelope")
      failure(new Increment43BadArrayBinding(6), "NODAL-SHAPE-043-007", "extent")
      failure(new Increment43BadArrayBinding(7), "NODAL-SHAPE-043-006", "local owned root")
      failure(new Increment43BadArrayBinding(8), "NODAL-SHAPE-043-007", "whole port")
      failure(new Increment43BadArrayBinding(9), "NODAL-SHAPE-043-007", "extent")

    test("bridge independently rejects forged bindings defaults and substituted types"):
      val snapshot = ConstructionKernel.inspect(new Increment43DependentArrayBinding)
      val binding = snapshot.conservativeArrayBindings.head
      def rejects(value: ConstructionSnapshot): Unit =
        val error = scala.util.Try(
          ScalaToMlirBridge.fromSnapshot(value)
        ).failed.get.asInstanceOf[BridgeException]
        assert(Set(
          "NODAL-BRIDGE-050",
          "NODAL-BRIDGE-022",
          "NODAL-BRIDGE-048"
        ).contains(error.diagnostic.code))
      rejects(snapshot.copy(conservativeArrayBindings = Vector.empty))
      rejects(snapshot.copy(conservativeArrayBindings = Vector(binding.copy(owner = "foreign"))))
      rejects(snapshot.copy(conservativeArrayBindings = Vector(binding.copy(instance = "foreign"))))
      rejects(snapshot.copy(conservativeArrayBindings =
        Vector(binding.copy(port = binding.actual))
      ))
      rejects(snapshot.copy(conservativeArrayBindings =
        Vector(binding.copy(actual = binding.port))
      ))
      rejects(snapshot.copy(conservativeArrayBindings =
        Vector(binding, binding.copy(path = binding.path + "_duplicate"))
      ))
      rejects(snapshot.copy(modules =
        snapshot.modules.map(module =>
          if module.path == snapshot.root then
            module.copy(
              instances = module.instances.map(_.copy(parameterBindings = Vector("count" -> "2")))
            )
          else module
        )
      ))
      val child = snapshot.modules.find(_.className == "Increment43DependentArrayChild").get
      val next = child.declarations.find(_.name == "next").get
      val defaultPath = next.attributes.toMap.apply("default")
      rejects(snapshot.copy(parameterExpressions =
        snapshot.parameterExpressions.map(value =>
          if value.path == defaultPath then value.copy(operands = Vector(next.path, "1")) else value
        )
      ))

    test("binding artifacts retain source actual formal and finalized parameter provenance"):
      val snapshot = ConstructionKernel.inspect(new Increment43DependentArrayBinding)
      val first = ReproducibilityContract.describe(snapshot)
      val second = ReproducibilityContract.describe(
        ConstructionKernel.inspect(new Increment43DependentArrayBinding)
      )
      assert(first == second)
      assert(first.construction.contains("conservative_array_bindings"))
      val binding = snapshot.conservativeArrayBindings.head
      Vector(binding.path, binding.instance, binding.port, binding.actual).foreach(value =>
        assert(first.construction.contains(value))
      )
      assert(first.manifest.text.contains("conservative-vector-row-major"))
      assert(
        first.construction.contains("parameter_expressions"),
        first.construction.contains("effective_dimensions")
      )
      val views = ConstructionKernel.inspect(new Increment43NoncontiguousArrayBinding)
      val viewArtifact = ReproducibilityContract.describe(views)
      assert(viewArtifact.construction.contains("actual_projection_chain"))
      views.shapeViews.foreach(view =>
        assert(
          viewArtifact.construction.contains(view.path),
          viewArtifact.construction.contains(view.input)
        )
      )

    test(
      "actual boundary whole contiguous noncontiguous and substituted arrays emit VA and AMS deterministically"
    ):
      (sys.env.get("NODAL_NODALC"), sys.env.get("NODAL_TRANSLATE")) match
        case (Some(nodalc), Some(translator)) =>
          val root = Path.of(".work", "boundary-array-public").toAbsolutePath
          Files.createDirectories(root)
          models.foreach: construct =>
            Vector(Backend.VerilogA -> "va", Backend.VerilogAMS -> "vams").foreach:
              (backend, extension) =>
                val snapshot =
                  ConstructionKernel.inspect(construct(), EmitOptions(backend = backend))
                val document = ScalaToMlirBridge.fromSnapshot(snapshot, backend)
                val stem = s"${snapshot.root}.$extension"
                Files.writeString(root.resolve(stem + ".source.mlir"), document.text)
                val normalized = run(
                  document,
                  Path.of(nodalc),
                  Vector("--pass-pipeline=builtin.module(nodal-gate-default)"),
                  root
                )
                Files.writeString(root.resolve(stem + ".normalized.mlir"), normalized)
                val flag = if backend == Backend.VerilogA then "--nodal-to-verilog-a"
                else "--nodal-to-verilog-ams"
                val output =
                  run(document.copy(text = normalized), Path.of(translator), Vector(flag), root)
                val repeated =
                  run(document.copy(text = normalized), Path.of(translator), Vector(flag), root)
                Files.writeString(root.resolve(stem), output)
                assert(output == repeated, output.contains("electrical [0:"))
                snapshot.conservativeArrayBindings.foreach: binding =>
                  val span = snapshot.sourceMap.find(_.semanticPath == binding.path).get.source
                  val expected =
                    s"${span.path}:${span.line}:${span.column}-${span.endLine}:${span.endColumn}"
                  val record =
                    output.linesIterator.find(_.contains(s"\"binding\":\"${binding.path}\"")).get
                  assert(record.contains(s"\"source_location\":\"$expected\""))
                if snapshot.root == "Increment43BoundaryDirections" then
                  assert(
                    output.contains("input [0:5] source"),
                    output.contains("output [0:5] drain"),
                    output.contains("inout [0:0] both")
                  )
                if snapshot.root == "Increment43NoncontiguousArrayBinding" then
                  assert(
                    output.contains("generate"),
                    output.contains("localparam integer"),
                    output.contains(".lhs("),
                    output.contains(".rhs(")
                  )
                  assert(!output.contains("<+ 0"), output.length < 16000)
                if snapshot.root == "Increment43DependentArrayBinding" then
                  assert(output.contains("count + 1"), output.contains(".count(count)"))
        case _ => ()

    test(
      "native verifier rejects forged effective extents missing ports and missing whole bindings"
    ):
      sys.env.get("NODAL_NODALC").foreach: nodalc =>
        val snapshot = ConstructionKernel.inspect(new Increment43DependentArrayBinding)
        val document = ScalaToMlirBridge.fromSnapshot(snapshot)
        val root = Path.of(".work", "boundary-array-native-negatives").toAbsolutePath
        Files.createDirectories(root)
        val extent = snapshot.modules.find(_.path == snapshot.root).get.declarations.find(_.name ==
          "taps").get.conservativeDimensions.mkString(",")
        val actualType = s"!nodal.shaped<\"$extent\", !nodal.terminal<\"electrical\">>"
        val forgedType =
          document.text.replace(actualType, "!nodal.shaped<\"3\", !nodal.terminal<\"electrical\">>")
        val missingPort = document.text.linesIterator.map(line =>
          if line.contains("\"nodal.instance_terminal_array\"") then
            line.replace("port = \"p\"", "port = \"missing\"")
          else line
        ).mkString("\n") + "\n"
        val missingBinding = document.text.linesIterator.filterNot(_.contains(
          "\"nodal.connect_array\""
        )).mkString("\n") + "\n"
        Vector(
          ("forged-effective-type", forgedType, "NODAL-SHAPE-043-008"),
          ("missing-child-port", missingPort, "NODAL-SHAPE-043-008"),
          ("missing-whole-binding", missingBinding, "NODAL-VERIFY-HIERARCHY-012")
        ).foreach: (name, text, code) =>
          assert(text != document.text)
          Files.writeString(root.resolve(name + ".mlir"), text)
          NativeCompilerClient.run(
            document.copy(text = text),
            NativeCompilerRequest(
              executable = Path.of(nodalc),
              arguments = Vector("--verify-each"),
              workingDirectory = root,
              timeout = Duration.ofSeconds(60)
            )
          ) match
            case failure: NativeCompilerFailure =>
              Files.writeString(root.resolve(name + ".log"), failure.standardError)
              assert(failure.standardError.contains(code))
            case success: NativeCompilerSuccess =>
              throw new java.lang.AssertionError(
                s"forged boundary input accepted: ${success.normalizedMlir}"
              )
