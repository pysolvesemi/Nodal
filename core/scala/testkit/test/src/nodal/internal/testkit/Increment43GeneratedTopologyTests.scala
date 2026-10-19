package nodal.internal.testkit

import java.nio.file.{Files, Path}
import java.time.Duration

import nodal.*
import nodal.internal.bridge.*
import utest.*

final class Increment43TopologyCell extends Module:
  val resistance: Param[Real] = param(1.kOhm)
  val p = inout(Electrical)
  val n = inout(Electrical)
  analog:
    I(p, n) <+ V(p, n) / resistance

final class Increment43TopologyBank extends Module:
  val count: Param[Integer] = param(3.integer, range = 2 to 5)
  val resistance: Param[Real] = param(2.kOhm)
  val taps = inout(Electrical, count)
  val common = inout(Electrical)
  hdlRange(0, count): i =>
    val element = instance(new Increment43TopologyCell)
    element.param(_.resistance, resistance)
    connect(taps.at(i), element(_.p))
    element(_.n) <> common

final class Increment43TopologyMultiple extends Module:
  val count: Param[Integer] = param(3.integer, range = 2 to 5)
  val taps = inout(Electrical, count, 2)
  hdlRange(0, count): i =>
    val middle = node(Electrical)
    val first = instance(new Increment43TopologyCell)
    val second = instance(new Increment43TopologyCell)
    connect(taps.at(i, 0), first(_.p))
    connect(first(_.n), middle)
    connect(middle, second(_.p))
    connect(second(_.n), taps.at(i, 1))

final class Increment43TopologyNested extends Module:
  val rows: Param[Integer] = param(3.integer, range = 2 to 5)
  val taps = inout(Electrical, rows, 2)
  val common = inout(Electrical)
  hdlRange(0, rows): row =>
    hdlRange(0, 2): column =>
      val element = instance(new Increment43TopologyCell)
      connect(taps.at(row, column), element(_.p))
      connect(element(_.n), common)

final class Increment43TopologyView extends Module:
  val count: Param[Integer] = param(3.integer, range = 2 to 5)
  val taps = inout(Electrical, count, 4)
  val common = inout(Electrical)
  val selected = taps.slice(1, 3, axis = 1).flatten.reshape(count, 2)
  hdlRange(0, count): i =>
    val element = instance(new Increment43TopologyCell)
    connect(selected.at(i, 1), element(_.p))
    connect(element(_.n), common)

final class Increment43TopologyCompound extends Module:
  val count: Param[Integer] = param(3.integer, range = 2 to 6)
  val extent: Expr[Integer] = count + 1.integer
  val taps = inout(Electrical, extent)
  val common = inout(Electrical)
  hdlRange(1, extent, 2): i =>
    val element = instance(new Increment43TopologyCell)
    connect(taps.at(i), element(_.p))
    connect(element(_.n), common)

object Increment43TopologyCaptureCount:
  var value = 0

// Private capture fixture: retain runtime invariants independently of public callback attestation.
final class Increment43TopologyEmpty extends Module:
  val taps = inout(Electrical, 2)
  val common = inout(Electrical)
  ConstructionKernel.generatedRegion(-3, -3, 1, None): i =>
    Increment43TopologyCaptureCount.value += 1
    val element = instance(new Increment43TopologyCell)
    connect(taps.at(i), element(_.p))
    connect(element(_.n), common)

final class Increment43TopologyArrayChild extends Module:
  val count: Param[Integer] = param(3.integer, range = 2 to 5)
  val p = inout(Electrical, count)
  analog:
    I(p.at(0), p.at(1)) <+ V(p.at(0), p.at(1)) / 1.kOhm

final class Increment43TopologyArrayBindings extends Module:
  val count: Param[Integer] = param(3.integer, range = 2 to 5)
  val taps = inout(Electrical, count)
  hdlRange(0, 2): _ =>
    val element = instance(new Increment43TopologyArrayChild)
    connect(taps, element(_.p))
    element.param(_.count, count)

final class Increment43TopologyChildWithRange extends Module:
  val p = inout(Electrical)
  val n = inout(Electrical)
  hdlRange(0, 2): _ =>
    val element = instance(new Increment43TopologyCell)
    connect(p, element(_.p))
    connect(n, element(_.n))

final class Increment43TopologyReusableNested extends Module:
  val taps = inout(Electrical, 2)
  val common = inout(Electrical)
  hdlRange(0, 2): i =>
    val child = instance(new Increment43TopologyChildWithRange)
    connect(taps.at(i), child(_.p))
    connect(common, child(_.n))

final class Increment43TopologyMillion extends Module:
  val taps = inout(Electrical, 1000000)
  val common = inout(Electrical)
  hdlRange(0, 1000000): i =>
    val element = instance(new Increment43TopologyCell)
    connect(taps.at(i), element(_.p))
    connect(common, element(_.n))

final class Increment43TopologySingleton extends Module:
  val taps = inout(Electrical, 2)
  val common = inout(Electrical)
  hdlRange(1, 2, 2): i =>
    val element = instance(new Increment43TopologyCell)
    connect(taps.at(i), element(_.p))
    connect(common, element(_.n))

final class Increment43TopologySiblingScopes extends Module:
  val taps = inout(Electrical, 2)
  val common = inout(Electrical)
  hdlRange(0, 2): _ =>
    val first = instance(new Increment43TopologyCell)
    connect(taps.at(0), first(_.p))
    connect(common, first(_.n))
    val repeated = instance(new Increment43TopologyCell)
    connect(taps.at(0), repeated(_.p))
    connect(common, repeated(_.n))
  hdlRange(0, 2): _ =>
    val second = instance(new Increment43TopologyCell)
    connect(taps.at(0), second(_.p))
    connect(common, second(_.n))

final class Increment43TopologyMissingPort(empty: Boolean) extends Module:
  val common = inout(Electrical)
  hdlRange(0, if empty then 0 else 2): _ =>
    val child = instance(new Increment43TopologyCell)
    connect(common, child(_.p))

final class Increment43TopologyDuplicatePort extends Module:
  val p = inout(Electrical)
  val n = inout(Electrical)
  hdlRange(0, 2): _ =>
    val child = instance(new Increment43TopologyCell)
    connect(p, child(_.p))
    connect(n, child(_.p))
    connect(n, child(_.n))

final class Increment43TopologyEqualDag extends Module:
  val count: Param[Integer] = param(3.integer, range = 2 to 6)
  val taps = inout(Electrical, count + 1.integer)
  val common = inout(Electrical)
  hdlRange(0, count + 1.integer): i =>
    val element = instance(new Increment43TopologyCell)
    connect(taps.at(i), element(_.p))
    connect(common, element(_.n))

final class Increment43TopologyAnalog extends Module:
  val count: Param[Integer] = param(3.integer, range = 2 to 5)
  val taps = inout(Electrical, count)
  val common = inout(Electrical)
  hdlRange(0, count): i =>
    val element = instance(new Increment43TopologyCell)
    val selected = taps.at(i)
    connect(selected, element(_.p))
    connect(common, element(_.n))
    analog:
      I(selected, common) <+ V(selected, common) / 2.kOhm

// Private capture fixture: retain runtime invariants independently of public callback attestation.
final class Increment43TopologyEscaped(form: Int) extends Module:
  val taps = inout(Electrical, 2)
  val common = inout(Electrical)
  var retained: Option[Instance[Increment43TopologyCell]] = None
  var induction: Option[Expr[Integer]] = None
  var endpoint: Option[Node[Electrical.type]] = None
  ConstructionKernel.generatedRegion(0, 2, 1, None): i =>
    val child = instance(new Increment43TopologyCell)
    connect(taps.at(i), child(_.p))
    connect(common, child(_.n))
    retained = Some(child)
    induction = Some(i)
    endpoint = Some(taps.at(i))
  if form == 0 then connect(common, retained.get(_.p))
  else if form == 1 then retained.get.param(_.resistance, 2.kOhm)
  else if form == 2 then
    ConstructionKernel.generatedRegion(0, 2, 1, None): _ =>
      connect(common, retained.get(_.p))
  else if form == 3 then
    val _ = taps.at(induction.get)
  else connect(common, endpoint.get)

final class Increment43TopologyBadIndex(form: Int) extends Module:
  val count: Param[Integer] = param(3.integer, range = 2 to 5)
  val independent: Param[Integer] = param(3.integer, range = 2 to 5)
  val taps = inout(Electrical, count)
  if form == 0 then
    hdlRange(0, independent): i =>
      val _ = taps.at(i)
  else if form == 1 then
    hdlRange(-1, count): i =>
      val _ = taps.at(i)
  else if form == 2 then
    hdlRange(0, count): i =>
      val _ = taps.at(i + 1.integer)
  else
    hdlRange(0, 0): i =>
      val _ = taps.at(i, 0)

final class Increment43TopologyInductionDefault(value: Expr[Integer]) extends Module:
  val hidden: Param[Integer] = param(value, range = 0 to 5)
  val p = inout(Electrical)

// Private capture fixture: retain runtime invariants independently of public callback attestation.
final class Increment43TopologyForeignDefault extends Module:
  ConstructionKernel.generatedRegion(0, 2, 1, None): i =>
    val _ = instance(new Increment43TopologyInductionDefault(i))

// Private capture fixture: retain runtime invariants independently of public callback attestation.
final class Increment43TopologyCaughtFailure extends Module:
  try
    ConstructionKernel.generatedRegion(0, 2, 1, None): _ =>
      val _ = instance(new Increment43TopologyCell)
      throw new IllegalStateException("authored callback failed")
  catch case _: IllegalStateException => ()

object Increment43GeneratedTopologyTests extends TestSuite:
  val models: Vector[() => Module] = Vector(
    () => new Increment43TopologyBank,
    () => new Increment43TopologyMultiple,
    () => new Increment43TopologyNested,
    () => new Increment43TopologyView,
    () => new Increment43TopologyCompound,
    () => new Increment43TopologyEmpty,
    () => new Increment43TopologyArrayBindings,
    () => new Increment43TopologyReusableNested,
    () => new Increment43TopologyMillion,
    () => new Increment43TopologySingleton,
    () => new Increment43TopologySiblingScopes,
    () => new Increment43TopologyEqualDag,
    () => new Increment43TopologyAnalog
  )

  private def run(
      document: NodalMlirDocument,
      tool: String,
      arguments: Vector[String],
      directory: Path
  ): String =
    NativeCompilerClient.run(
      document,
      NativeCompilerRequest(
        Path.of(tool),
        arguments,
        directory,
        timeout = Duration.ofSeconds(90)
      )
    ) match
      case value: NativeCompilerSuccess => value.normalizedMlir
      case failure: NativeCompilerFailure =>
        throw new java.lang.AssertionError(s"${failure.diagnostic}\n${failure.standardError}")

  private def assertTargetCorrespondence(
      snapshot: ConstructionSnapshot,
      document: NodalMlirDocument,
      output: String
  ): Unit =
    val regions = snapshot.generatedRegions.sortBy(_.path)
    val symbols = regions.map: region =>
      val lines = document.text.linesIterator.filter(line =>
        line.contains("\"nodal.generate\"") && line.contains(s"region_id = \"${region.path}\"")
      ).toVector
      assert(lines.size == 1)
      val symbol = "induction = \"([^\"]+)\"".r.findFirstMatchIn(lines.head).get.group(1)
      val upper = if region.upperExclusive.toLongOption.nonEmpty then region.upperExclusive
      else if snapshot.parameterExpressions.exists(_.path == region.upperExclusive) then
        "(count + 1)"
      else region.upperExclusive.split('.').last
      val loop = s"for ($symbol = ${region.lower}; $symbol < $upper; " +
        s"$symbol = $symbol + ${region.step}) begin : ${region.path.split('.').last}"
      assert(output.linesIterator.count(_.trim == loop) == 1)
      assert(output.linesIterator.count(_.trim == s"genvar $symbol;") == 1)
      assert(output.contains(s"\"source_scope\":\"${region.path}\""))
      assert(output.contains(s"\"source_induction\":\"${region.induction}\""))
      symbol
    assert(output.linesIterator.count(_.trim.startsWith("for (")) == regions.size)
    val definitions = document.text.linesIterator.filter(_.contains("\"nodal.module\""))
      .map(line => "sym_name = \"([^\"]+)\"".r.findFirstMatchIn(line).get.group(1)).toVector
    definitions.foreach(name =>
      assert(output.linesIterator.count(_.startsWith(s"module $name(")) == 1)
    )
    val rootBody = output.substring(
      output.indexOf(s"module ${snapshot.root}("),
      output.indexOf("endmodule", output.indexOf(s"module ${snapshot.root}("))
    )
    val first = regions.zip(symbols).find(_._1.owner == snapshot.root).get._2
    snapshot.root match
      case "Increment43TopologyBank" =>
        assert(rootBody.contains(s"localparam integer node_map_taps_0_0 = $first;"))
        assert(rootBody.contains(
          "element #(.resistance(resistance)) element_instance(.n(common), .p(taps[node_map_taps_0_0]));"
        ))
      case "Increment43TopologyNested" =>
        assert(rootBody.contains(
          s"localparam integer node_map_taps_0_0 = (($first * 2) + ${symbols(1)});"
        ))
        assert(
          rootBody.contains("element element_instance(.n(common), .p(taps[node_map_taps_0_0]));")
        )
      case "Increment43TopologyView" =>
        assert(rootBody.contains(s"localparam integer node_map_taps_0_0 = (($first * 2) + 1);"))
        assert(rootBody.contains(
          "localparam integer node_map_taps_0_1 = (((node_map_taps_0_0 / 2) * 4) + ((node_map_taps_0_0 % 2) + 1));"
        ))
        assert(
          rootBody.contains("element element_instance(.n(common), .p(taps[node_map_taps_0_1]));")
        )
      case "Increment43TopologyCompound" | "Increment43TopologyEqualDag" |
          "Increment43TopologyMillion" | "Increment43TopologySingleton" |
          "Increment43TopologyEmpty" =>
        assert(rootBody.contains(s"localparam integer node_map_taps_0_0 = $first;"))
        assert(
          rootBody.contains("element element_instance(.n(common), .p(taps[node_map_taps_0_0]));")
        )
        if snapshot.root == "Increment43TopologyMillion" then
          assert(rootBody.contains("electrical [0:999999] taps;"), rootBody.length < 4000)
      case "Increment43TopologyMultiple" =>
        assert(rootBody.linesIterator.count(_.trim == "electrical middle;") == 1)
        assert(rootBody.contains(s"localparam integer node_map_taps_0_0 = ($first * 2);"))
        assert(rootBody.contains(s"localparam integer node_map_taps_1_0 = (($first * 2) + 1);"))
        assert(rootBody.contains("first first_instance(.n(middle), .p(taps[node_map_taps_0_0]));"))
        assert(rootBody.contains("first second_instance(.n(taps[node_map_taps_1_0]), .p(middle));"))
      case "Increment43TopologyArrayBindings" =>
        assert(rootBody.contains("element #(.count(count)) element_instance(.p(taps));"))
        snapshot.conservativeArrayBindings.foreach: binding =>
          val record =
            rootBody.linesIterator.find(_.contains(s"\"binding\":\"${binding.path}\"")).get
          val root = snapshot.instanceArrayRoots.find(value =>
            Set(binding.left, binding.right).contains(value.path)
          ).get
          val actual = if binding.left == root.path then binding.right else binding.left
          assert(
            record.contains(s"\"formal\":\"${root.port}\""),
            record.contains(s"\"root\":\"$actual\""),
            record.contains(s"\"root_reference\":\"${root.path}\""),
            record.contains(s"\"root_shape\":\"${root.dimensions.mkString(",")}\""),
            record.contains("\"left\":{"),
            record.contains("\"right\":{")
          )
          assert(record.contains(s"\"source_scope\":\"${binding.generatedScope.get.owner}\""))
          assert(record.contains("\"generated_scope\":\"\""))
          val span = snapshot.sourceMap.find(_.semanticPath == binding.path).get.source
          val location =
            s"${span.path}:${span.line}:${span.column}-${span.endLine}:${span.endColumn}"
          assert(record.contains(s"\"source_location\":\"$location\""))
      case "Increment43TopologyReusableNested" =>
        assert(rootBody.contains("child child_instance(.n(common), .p(taps[node_map_taps_0_0]));"))
        assert(output.contains("element element_instance(.n(n), .p(p));"))
      case "Increment43TopologySiblingScopes" =>
        Vector("first", "repeated", "second").foreach(name =>
          assert(rootBody.linesIterator.count(_.trim ==
            s"first ${name}_instance(.n(common), .p(taps[0]));") == 1)
        )
      case "Increment43TopologyAnalog" =>
        val child = "element element_instance(.n(common), .p(taps[node_map_taps_0_0]));"
        val contribution =
          "I(taps[node_map_taps_0_0], common) <+ (V(taps[node_map_taps_0_0], common) / 2000);"
        assert(rootBody.contains(s"localparam integer node_map_taps_0_0 = $first;"))
        assert(rootBody.linesIterator.count(_.trim == contribution) == 1)
        assert(
          rootBody.indexOf(child) >= 0,
          rootBody.indexOf(child) < rootBody.indexOf(contribution)
        )
      case _ => ()

  val tests: Tests = Tests:
    test("capture keeps exact generated occurrences and independent reusable child definitions"):
      models.foreach: model =>
        val snapshot = ConstructionKernel.inspect(model())
        val text = ScalaToMlirBridge.fromSnapshot(snapshot).text
        assert(text == ScalaToMlirBridge.fromSnapshot(ConstructionKernel.inspect(model())).text)
        val root = snapshot.modules.find(_.path == snapshot.root).get
        assert(root.instances.nonEmpty || snapshot.generatedRegions.exists(_.parent.nonEmpty))
        snapshot.modules.foreach: module =>
          module.instances.foreach: instance =>
            assert(instance.generatedScope.nonEmpty)
            val scope = instance.generatedScope.get
            assert(snapshot.generatedRegions.exists(region =>
              region.path == scope.owner &&
                region.owner == module.path && region.induction == scope.induction
            ))
        snapshot.instanceArrayRoots.foreach: arrayRoot =>
          val instance = root.instances.find(_.path == arrayRoot.instance).get
          assert(
            arrayRoot.owner == root.path,
            arrayRoot.dimensions == Vector("count"),
            arrayRoot.generatedScope == instance.generatedScope,
            snapshot.sourceMap.exists(_.semanticPath == arrayRoot.path)
          )
          val relations = snapshot.conservativeArrayBindings.filter(binding =>
            Set(binding.left, binding.right).contains(arrayRoot.path)
          )
          assert(relations.size == 1, relations.head.generatedScope == arrayRoot.generatedScope)
        snapshot.shapeIndices.filter(_.generatedScope.nonEmpty).foreach: index =>
          assert(index.path.endsWith(s".in(${index.generatedScope.get.owner})"))
          assert(index.sourceUses.forall(path => snapshot.sourceMap.exists(_.semanticPath == path)))
      val multiple = ScalaToMlirBridge.lower(new Increment43TopologyMultiple).text
      assert(multiple.linesIterator.count(line =>
        line.contains("\"nodal.module\"") &&
          line.contains("class_name = \"Increment43TopologyCell\"")
      ) == 1)
      assert(multiple.contains("^bb0("), multiple.contains(": i64):"))

    test("empty callback captures once and scope artifacts reconstruct actual owners"):
      Increment43TopologyCaptureCount.value = 0
      val empty = ConstructionKernel.inspect(new Increment43TopologyEmpty)
      assert(
        Increment43TopologyCaptureCount.value == 1,
        empty.generatedRegions.head.maximumTripCount == 0
      )
      val snapshot = ConstructionKernel.inspect(new Increment43TopologyNested)
      val artifact = ReproducibilityContract.describe(snapshot)
      assert(artifact.construction.contains("generated_topology_scopes"))
      snapshot.generatedRegions.foreach(region =>
        assert(artifact.construction.contains(region.path))
      )
      snapshot.topology.foreach(edge =>
        assert(
          edge.generatedScope.nonEmpty,
          edge.path.nonEmpty,
          snapshot.sourceMap.exists(_.semanticPath == edge.path.get)
        )
      )

    test("escaped sibling induction and unproved index captures reject causally"):
      (0 to 4).foreach: form =>
        val error = scala.util.Try(
          ConstructionKernel.inspect(new Increment43TopologyEscaped(form))
        ).failed.get
        assert(error.isInstanceOf[ConstructionException])
        assert(Set("NODAL-HIERARCHY-044", "NODAL-SHAPE-043-006").contains(
          error.asInstanceOf[ConstructionException].diagnostic.code
        ))
      (0 to 3).foreach: form =>
        val error = scala.util.Try(
          ConstructionKernel.inspect(new Increment43TopologyBadIndex(form))
        ).failed.get
        assert(error.isInstanceOf[ConstructionException])
        assert(Set("NODAL-SHAPE-043-002", "NODAL-SHAPE-043-006").contains(
          error.asInstanceOf[ConstructionException].diagnostic.code
        ))

    test("selection sharing stays within actual scopes and large ranges retain one template"):
      val siblings = ConstructionKernel.inspect(new Increment43TopologySiblingScopes)
      val indices = siblings.shapeIndices.filter(_.generatedScope.nonEmpty)
      assert(indices.size == 2, indices.map(_.generatedScope).distinct.size == 2)
      assert(indices.map(_.sourceUses.size).sorted == Vector(1, 2))
      val singleton = ConstructionKernel.inspect(new Increment43TopologySingleton)
      assert(singleton.generatedRegions.head.maximumTripCount == 1)
      val equalDag = ScalaToMlirBridge.lower(new Increment43TopologyEqualDag)
      assert(equalDag.text.contains("induction:"))
      val composed = ScalaToMlirBridge.lower(new Increment43TopologyAnalog)
      assert(
        composed.text.contains("\"nodal.analog\""),
        composed.text.contains("nodal.instance")
      )
      val million = ConstructionKernel.inspect(new Increment43TopologyMillion)
      assert(million.generatedRegions.head.maximumTripCount == 1000000)
      assert(million.modules.find(_.path == million.root).get.instances.size == 1)
      val text = ScalaToMlirBridge.fromSnapshot(million).text
      assert(text.linesIterator.count(_.contains("\"nodal.instance\"")) == 1, text.length < 100000)

    test("foreign induction defaults and failed callback transactions reject at capture"):
      val foreign =
        scala.util.Try(ConstructionKernel.inspect(new Increment43TopologyForeignDefault)).failed.get
      assert(foreign.isInstanceOf[ConstructionException])
      assert(foreign.asInstanceOf[ConstructionException].diagnostic.code ==
        "NODAL-ITERATION-043-004")
      val caught =
        scala.util.Try(ConstructionKernel.inspect(new Increment43TopologyCaughtFailure)).failed.get
      assert(caught.isInstanceOf[ConstructionException])
      assert(caught.asInstanceOf[ConstructionException].diagnostic.code ==
        "NODAL-CONSTRUCTOR-LIFECYCLE-021")

    test("bridge rejects forged exact scope and induction relationships"):
      val snapshot = ConstructionKernel.inspect(new Increment43TopologyBank)
      val root = snapshot.modules.find(_.path == snapshot.root).get
      val instance = root.instances.head
      val scope = instance.generatedScope.get
      val malformed = Vector(
        snapshot.copy(modules =
          snapshot.modules.map(module =>
            if module.path == root.path then
              module.copy(instances = Vector(instance.copy(generatedScope = None)))
            else module
          )
        ),
        snapshot.copy(shapeIndices =
          snapshot.shapeIndices.map(index =>
            if index.generatedScope.nonEmpty then
              index.copy(generatedScope = Some(scope.copy(induction = "foreign")))
            else index
          )
        ),
        snapshot.copy(topology = snapshot.topology.map(_.copy(generatedScope = None))),
        snapshot.copy(generatedRegions =
          snapshot.generatedRegions.map(region => region.copy(parent = Some(region.path)))
        ),
        snapshot.copy(generatedRegions =
          snapshot.generatedRegions.map(_.copy(maximumTripCount = 0))
        )
      )
      malformed.foreach(value =>
        assert(scala.util.Try(ScalaToMlirBridge.fromSnapshot(value)).isFailure)
      )
      val effectless = snapshot.copy(modules =
        snapshot.modules.map(module =>
          if module.path != root.path then module
          else
            module.copy(declarations =
              module.declarations.map(declaration =>
                declaration.copy(attributes =
                  declaration.attributes.map((key, value) =>
                    key ->
                      (if key == "structural_effects" then
                         value.split(",").filterNot(_ == "topology").mkString(",")
                       else value)
                  )
                )
              )
            )
        )
      )
      val failure = scala.util.Try(ScalaToMlirBridge.fromSnapshot(effectless)).failed.get
      assert(
        failure.getMessage.contains("generated range parameter is not structural Integer data")
      )

    test("native validates missing and duplicate generated child ports even in empty scopes"):
      sys.env.get("NODAL_NODALC") match
        case Some(nodalc) =>
          val directory = Path.of(".work", "generated-topology-negative").toAbsolutePath
          Files.createDirectories(directory)
          Vector[(String, String, () => Module)](
            (
              "NODAL-VERIFY-HIERARCHY-012",
              "requires an actual connection",
              () => new Increment43TopologyMissingPort(false)
            ),
            (
              "NODAL-VERIFY-HIERARCHY-012",
              "requires an actual connection",
              () => new Increment43TopologyMissingPort(true)
            ),
            (
              "NODAL-VERIFY-HIERARCHY-010",
              "duplicate connection bindings",
              () => new Increment43TopologyDuplicatePort
            )
          ).foreach: (code, reason, model) =>
            val document = ScalaToMlirBridge.lower(model(), EmitOptions(backend = Backend.VerilogA))
            NativeCompilerClient.run(
              document,
              NativeCompilerRequest(
                Path.of(nodalc),
                Vector("--pass-pipeline=builtin.module(nodal-gate-default)"),
                directory,
                timeout = Duration.ofSeconds(90)
              )
            ) match
              case failure: NativeCompilerFailure =>
                assert(failure.diagnostic.code == code, failure.standardError.contains(reason))
              case _: NativeCompilerSuccess =>
                throw new java.lang.AssertionError("malformed generated child binding was accepted")
        case _ => throw new java.lang.AssertionError("configured nodalc is required")

    test("actual compact generated topology emits VA and AMS deterministically"):
      (sys.env.get("NODAL_NODALC"), sys.env.get("NODAL_TRANSLATE")) match
        case (Some(nodalc), Some(translator)) =>
          val directory = Path.of(".work", "generated-topology-public").toAbsolutePath
          Files.createDirectories(directory)
          models.foreach: model =>
            Vector(Backend.VerilogA -> "va", Backend.VerilogAMS -> "vams").foreach:
              (backend, extension) =>
                val snapshot = ConstructionKernel.inspect(model(), EmitOptions(backend = backend))
                val document = ScalaToMlirBridge.fromSnapshot(snapshot, backend)
                val stem = snapshot.root + "." + extension
                Files.writeString(directory.resolve(stem + ".source.mlir"), document.text)
                val normalized = run(
                  document,
                  nodalc,
                  Vector("--pass-pipeline=builtin.module(nodal-gate-default)"),
                  directory
                )
                Files.writeString(directory.resolve(stem + ".normalized.mlir"), normalized)
                val flag = if backend == Backend.VerilogA then "--nodal-to-verilog-a"
                else "--nodal-to-verilog-ams"
                val output =
                  run(document.copy(text = normalized), translator, Vector(flag), directory)
                assert(output ==
                  run(document.copy(text = normalized), translator, Vector(flag), directory))
                Files.writeString(directory.resolve(stem), output)
                assert(
                  output.contains("generate"),
                  output.contains("for ("),
                  output.length < 100000
                )
                assertTargetCorrespondence(snapshot, document, output)
        case _ =>
          throw new java.lang.AssertionError("configured nodalc and nodal-translate are required")
