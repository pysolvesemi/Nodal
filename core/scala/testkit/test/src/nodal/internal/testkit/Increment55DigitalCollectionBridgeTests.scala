package nodal.internal.testkit

import java.nio.file.{Files, Path}
import java.time.Duration

import nodal.*
import nodal.internal.bridge.*
import utest.*

final class DigitalCollectionProjectionBridgeSource extends Module, DigitalCollectionClock:
  val input = in(Vec(Vec(UInt(4), 2, 3), 2))
  val result = input.map(row => row.slice(0, 2, axis = 1).flatten.at(1))
  val viewed = input.map(row => row.reshape(3, 2))

final class DigitalCollectionStaticSelectorBridgeSource extends Module, DigitalCollectionClock:
  val position = param(1.integer, range = 0 to 2)
  val input = in(Vec(Vec(UInt(4), 4), 2))
  val result = input.map(row => row.at(position))

final class DigitalCollectionParameterBridgeSource extends Module, DigitalCollectionClock:
  val initial = param(0.U(4))
  val input = in(Vec(UInt(4), 2))
  val result = input.map(value => value + 1.U(4))

final class DigitalCollectionCapturedProofBridgeSource extends Module, DigitalCollectionClock:
  val captured = 3.S(4)
  val input = in(Vec(Vec(UInt(4), 2), 3))
  val result = input.map(row => row.map(value => captured.toUnsigned.resizeChecked(2) + value))

final class DigitalCollectionRepeatedChildBridgeSource extends Module:
  val first = instance(new DigitalCollectionMapSource)
  val second = instance(new DigitalCollectionMapSource)

object Increment55DigitalCollectionBridgeTests extends TestSuite:
  private def rejected(snapshot: ConstructionSnapshot, message: String): Unit =
    val error = scala.util.Try(ScalaToMlirBridge.fromSnapshot(snapshot)).failed.get
    assert(error.isInstanceOf[BridgeException])
    assert(error.getMessage.contains(message))

  private def updateCollection(snapshot: ConstructionSnapshot, path: String)(
      update: KernelDigitalCollectionSnapshot => KernelDigitalCollectionSnapshot
  ): ConstructionSnapshot =
    val collection = update(snapshot.digitalCollections.find(_.path == path).get)
    snapshot.copy(
      digitalCollections = snapshot.digitalCollections.map(value =>
        if value.path == path then collection else value
      ),
      digitalExpressions = snapshot.digitalExpressions.map(value =>
        if value.path == path then
          value.copy(
            owner = collection.owner,
            region = collection.parentRegion,
            dataType = collection.dataType,
            operands = collection.inputs ++ collection.seed ++ collection.captures
          )
        else value
      )
    )

  private def updateExpression(snapshot: ConstructionSnapshot, path: String)(
      update: KernelDigitalExpressionSnapshot => KernelDigitalExpressionSnapshot
  ): ConstructionSnapshot =
    snapshot.copy(digitalExpressions =
      snapshot.digitalExpressions.map(value =>
        if value.path == path then update(value) else value
      )
    )

  val tests: Tests = Tests:
    test("map and zip preserve symbolic rank heterogeneous elements and actual right operand"):
      val map = ScalaToMlirBridge.lower(new DigitalCollectionMapSource)
      assert(map.text.contains("kind = \"map\""))
      assert(map.text.contains("!nodal.shaped<\"rows,3\", !nodal.uint<5>>"))
      assert(map.text.contains("nodal.shape_index"))
      val snapshot = ConstructionKernel.inspect(new DigitalCollectionZipSource)
      val zip = ScalaToMlirBridge.fromSnapshot(snapshot)
      assert(zip.text.contains("operandSegmentSizes = array<i32: 2, 0, 0>"))
      assert(zip.text.contains("!nodal.shaped<\"2,3\", !nodal.sint<7>>"))
      val collection = snapshot.digitalCollections.head
      assert(snapshot.digitalExpressions.exists(value =>
        value.region.contains(collection.path) &&
          value.operands.contains(collection.arguments(1).path)
      ))
      assert(zip.text == ScalaToMlirBridge.lower(new DigitalCollectionZipSource).text)

    test("whole aggregates enums and nested captures have isolated real block arguments"):
      val aggregate = ScalaToMlirBridge.lower(new DigitalCollectionAggregateSource)
      assert(aggregate.text.contains("!nodal.struct<\"Payload\""))
      assert(aggregate.text.contains("name = \"measure\", type = f64"))
      assert(aggregate.text.contains("operandSegmentSizes = array<i32: 1, 0, 1>"))
      val nested = ScalaToMlirBridge.lower(new DigitalCollectionNestedSource)
      assert(nested.text.linesIterator.count(_.contains("\"nodal.collection\"")) == 3)
      assert(nested.text.linesIterator.count(_.contains("\"nodal.collection_yield\"")) == 3)
      assert(nested.text.contains("nodal.shape_index"))
      val identities = ScalaToMlirBridge.lower(new DigitalCollectionIdentitySource)
      assert(identities.text.contains("!nodal.enum<"))
      assert(identities.text.linesIterator.count(_.contains("\"nodal.collection\"")) == 2)

    test(
      "two child instances preserve collection regions and source-significant definitions"
    ):
      val snapshot = ConstructionKernel.inspect(new DigitalCollectionRepeatedChildBridgeSource)
      val document = ScalaToMlirBridge.fromSnapshot(snapshot)
      assert(snapshot.digitalCollections.size == 4)
      // Constructor source identity and external reset-source metadata differ;
      // these instances are not alpha-equivalent canonical definitions.
      assert(document.text.linesIterator.count(_.contains("\"nodal.module\"")) == 3)
      assert(document.text.linesIterator.count(_.contains("\"nodal.collection\"")) == 4)
      snapshot.digitalCollections.foreach(value =>
        assert(document.text.contains(s"region_id = \"${value.path}\""))
      )
      assert(document.text ==
        ScalaToMlirBridge.lower(new DigitalCollectionRepeatedChildBridgeSource).text)

    test("nested explicit captures preserve immutable scalar proof facts"):
      val snapshot = ConstructionKernel.inspect(new DigitalCollectionCapturedProofBridgeSource)
      val document = ScalaToMlirBridge.fromSnapshot(snapshot)
      assert(snapshot.digitalCollections.size == 2)
      assert(snapshot.digitalCollections.forall(_.captures.size == 1))
      assert(document.text.contains("digital_to_unsigned"))
      assert(document.text.contains("digital_resize_checked"))
      val literal = snapshot.digitalExpressions.find(value =>
        value.region.isEmpty && value.dataType == "SInt(4)"
      ).get
      rejected(
        updateExpression(snapshot, literal.path)(_.copy(literal = Some("-1"))),
        "checked-value proof"
      )

    test("fold and scan keep seed type and shape with explicit conversion and no seed output"):
      val snapshot = ConstructionKernel.inspect(new DigitalCollectionFoldScanSource)
      val document = ScalaToMlirBridge.fromSnapshot(snapshot)
      assert(document.text.contains("kind = \"fold_left\""))
      assert(document.text.contains("kind = \"scan_left\""))
      assert(document.text.contains("!nodal.shaped<\"2,3\", !nodal.uint<8>>"))
      assert(snapshot.digitalCollections.find(_.kind == "fold_left").get.dataType == "UInt(8)")
      assert(snapshot.digitalCollections.find(_.kind == "scan_left").get.dataType ==
        "Vec(UInt(8);2x3)")
      val symbolic = ScalaToMlirBridge.lower(new DigitalCollectionSymbolicSource)
      assert(symbolic.text.contains("!nodal.digital<\"uint\", \"width\">"))
      assert(symbolic.text.contains("!nodal.shaped<\"count\""))

    test("body-local views slices and indices remain in the collection body"):
      val snapshot = ConstructionKernel.inspect(new DigitalCollectionProjectionBridgeSource)
      val document = ScalaToMlirBridge.fromSnapshot(snapshot)
      assert(snapshot.shapeViews.forall(_.region.nonEmpty))
      assert(snapshot.shapeIndices.forall(_.region.nonEmpty))
      assert(document.text.contains("nodal.shape_slice"))
      assert(document.text.contains("nodal.shape_view"))
      assert(document.text.contains("nodal.shape_index"))
      assert(document.text.indexOf("nodal.collection") < document.text.indexOf("nodal.shape_slice"))

    test("module-static selectors retain actual explicit captures and reject forged omission"):
      val snapshot = ConstructionKernel.inspect(new DigitalCollectionStaticSelectorBridgeSource)
      val collection = snapshot.digitalCollections.head
      assert(collection.captures == Vector(snapshot.root + ".position"))
      val argument = collection.arguments.last
      assert(argument.role == "static_capture")
      assert(snapshot.shapeIndices.head.indices == Vector(argument.path))
      val document = ScalaToMlirBridge.fromSnapshot(snapshot)
      assert(document.text.contains("parameter = @position"))
      assert(document.text.contains("nodal.shape_index"))
      rejected(
        updateCollection(snapshot, collection.path)(_.copy(captures = Vector.empty)),
        "capture"
      )

    test("forged region escape sibling capture parent cycle and missing explicit captures reject"):
      val snapshot = ConstructionKernel.inspect(new DigitalCollectionNestedSource)
      val inner = snapshot.digitalCollections.find(_.parentRegion.nonEmpty).get
      val outer =
        snapshot.digitalCollections.find(value => inner.parentRegion.contains(value.path)).get
      val sibling = snapshot.digitalCollections.find(value =>
        value.parentRegion.isEmpty && value.path != outer.path
      ).get
      rejected(
        updateCollection(snapshot, outer.path)(_.copy(yieldValue = inner.arguments.head.path)),
        "exact lexical scope"
      )
      rejected(
        updateCollection(snapshot, sibling.path)(value =>
          value.copy(captures = Vector(inner.arguments.head.path))
        ),
        "exact lexical scope"
      )
      rejected(
        updateCollection(snapshot, outer.path)(_.copy(
          parentRegion = Some(inner.path),
          inputs = Vector(inner.arguments.head.path),
          captures = Vector.empty
        )),
        "lexical owners contain a cycle"
      )
      val addition = snapshot.digitalExpressions.find(_.operation == "digital_add").get
      rejected(
        updateExpression(snapshot, addition.path)(value =>
          value.copy(operands = value.operands.updated(1, snapshot.root + ".increment"))
        ),
        "exact lexical scope"
      )
      rejected(
        updateExpression(snapshot, addition.path)(_.copy(region = None)),
        "exact lexical scope"
      )
      val escaped = addition.copy(path = snapshot.root + ".escaped", region = None)
      rejected(
        snapshot.copy(digitalExpressions = snapshot.digitalExpressions :+ escaped),
        "exact lexical scope"
      )

    test("forged collection kinds argument identity type shape and seed recurrence reject"):
      val snapshot = ConstructionKernel.inspect(new DigitalCollectionZipSource)
      val collection = snapshot.digitalCollections.head
      val wrongRight = snapshot.copy(modules =
        snapshot.modules.map(module =>
          module.copy(
            declarations = module.declarations.map(value =>
              if value.path == collection.inputs(1) then
                value.copy(dataType = Some("Vec(SInt(6);3x2)"))
              else value
            )
          )
        )
      )
      rejected(wrongRight, "zip requires identical complete dimensions")
      val oldArgument = collection.arguments.head.path
      val newArgument = oldArgument + ".forged"
      val renamed = updateCollection(snapshot, collection.path)(value =>
        value.copy(
          arguments = value.arguments.updated(0, value.arguments.head.copy(path = newArgument))
        )
      )
      rejected(
        renamed.copy(digitalExpressions =
          renamed.digitalExpressions.map(value =>
            value.copy(operands =
              value.operands.map(input => if input == oldArgument then newArgument else input)
            )
          )
        ),
        "argument identity must be canonical"
      )
      rejected(
        updateCollection(snapshot, collection.path)(_.copy(kind = "zip_discard_right")),
        "collection kind"
      )
      rejected(
        updateCollection(snapshot, collection.path)(_.copy(arguments = collection.arguments.tail)),
        "exact lexical scope"
      )
      rejected(
        updateCollection(snapshot, collection.path)(value =>
          value.copy(arguments =
            value.arguments.updated(0, value.arguments.head.copy(dataType = "UInt(5)"))
          )
        ),
        "complete finite operand type"
      )
      rejected(
        updateCollection(snapshot, collection.path)(_.copy(dataType = "Vec(SInt(7);3x2)")),
        "every input dimension"
      )
      rejected(
        updateCollection(snapshot, collection.path)(_.copy(dataType = "Vec(UInt(7);2x3)")),
        "every input dimension"
      )
      val fold = ConstructionKernel.inspect(new DigitalCollectionFoldScanSource)
      val folded = fold.digitalCollections.find(_.kind == "fold_left").get
      rejected(
        updateCollection(fold, folded.path)(_.copy(yieldValue = folded.arguments(1).path)),
        "complete seed type"
      )
      val symbolic = ConstructionKernel.inspect(new DigitalCollectionSymbolicSource)
      val symbolicFold = symbolic.digitalCollections.find(_.kind == "fold_left").get
      rejected(
        updateCollection(symbolic, symbolicFold.path)(value =>
          value.copy(arguments =
            value.arguments.updated(0, value.arguments.head.copy(dataType = "UInt(4)"))
          )
        ),
        "complete finite operand type"
      )

    test("forged body effects orphan records and external shape references reject"):
      val snapshot = ConstructionKernel.inspect(new DigitalCollectionMapSource)
      val collection = snapshot.digitalCollections.head
      val expression = snapshot.digitalExpressions.find(value =>
        value.region.contains(collection.path) && value.operation == "digital_add"
      ).get
      rejected(
        updateExpression(snapshot, expression.path)(_.copy(operation = "digital_external_latency")),
        "exact opcode"
      )
      rejected(snapshot.copy(digitalCollections = Vector.empty), "region owner")
      rejected(
        snapshot.copy(digitalExpressions =
          snapshot.digitalExpressions.filterNot(_.path == collection.path)
        ),
        "exact lexical scope"
      )
      val nested = ConstructionKernel.inspect(new DigitalCollectionNestedSource)
      val index = nested.shapeIndices.find(_.region.nonEmpty).get
      rejected(
        nested.copy(shapeIndices =
          nested.shapeIndices.map(value =>
            if value.path == index.path then value.copy(input = nested.root + ".input") else value
          )
        ),
        "exact lexical scope"
      )
      val standalone = expression.copy(
        path = snapshot.root + ".realEffect",
        operation = "real_literal",
        operands = Vector.empty,
        dataType = "Real",
        literal = Some("1.0")
      )
      rejected(
        snapshot.copy(digitalExpressions = snapshot.digitalExpressions :+ standalone),
        "finite digital values"
      )

    test("module parameter defaults cannot refer to callback-local literals"):
      val snapshot = ConstructionKernel.inspect(new DigitalCollectionParameterBridgeSource)
      val local = snapshot.digitalExpressions.find(value =>
        value.region.nonEmpty && value.operation == "integer"
      ).get
      val forged = snapshot.copy(modules =
        snapshot.modules.map(module =>
          module.copy(
            declarations = module.declarations.map(value =>
              if value.path == snapshot.root + ".initial" then
                value.copy(attributes =
                  value.attributes.map((name, content) =>
                    name -> (if name == "default" then local.path else content)
                  )
                )
              else value
            )
          )
        )
      )
      rejected(forged, "parameter literal cannot escape")
      assert(ScalaToMlirBridge.fromSnapshot(snapshot).text.contains("nodal.collection"))

    test("million-lane shape keeps constant body size and deterministic capture"):
      def snapshot(count: Int): ConstructionSnapshot = ConstructionKernel.inspect(
        new DigitalCollectionRuntimeSource(count, "count")
      )
      val small = ScalaToMlirBridge.fromSnapshot(snapshot(1))
      val large = ScalaToMlirBridge.fromSnapshot(snapshot(1000000))
      assert(small.text.linesIterator.size == large.text.linesIterator.size)
      assert(large.text.linesIterator.count(_.contains("\"nodal.digital_expr\"")) == 1)
      assert(large.text.linesIterator.count(_.contains("\"nodal.collection\"")) == 1)
      assert(large.text == ScalaToMlirBridge.fromSnapshot(snapshot(1000000)).text)

    test("actual public source witnesses verify and round trip through the native boundary"):
      val compiler = sys.env.getOrElse(
        "NODAL_NODALC",
        scala.util.Failure[Nothing](
          new java.lang.AssertionError("configured nodalc is required")
        ).get
      )
      val directory = Path.of(".work", "increment55-collection-bridge").toAbsolutePath
      Files.createDirectories(directory)
      val witnesses = Vector[(String, () => Module)](
        "map" -> (() => new DigitalCollectionMapSource),
        "zip" -> (() => new DigitalCollectionZipSource),
        "aggregate" -> (() => new DigitalCollectionAggregateSource),
        "nested" -> (() => new DigitalCollectionNestedSource),
        "fold-scan" -> (() => new DigitalCollectionFoldScanSource),
        "symbolic" -> (() => new DigitalCollectionSymbolicSource),
        "identity" -> (() => new DigitalCollectionIdentitySource),
        "projections" -> (() => new DigitalCollectionProjectionBridgeSource),
        "static-selector" -> (() => new DigitalCollectionStaticSelectorBridgeSource),
        "repeated-child" -> (() => new DigitalCollectionRepeatedChildBridgeSource),
        "captured-proof" -> (() => new DigitalCollectionCapturedProofBridgeSource)
      )
      def native(document: NodalMlirDocument): String = NativeCompilerClient.run(
        document,
        NativeCompilerRequest(
          Path.of(compiler).toAbsolutePath,
          Vector.empty,
          directory,
          timeout = Duration.ofSeconds(90)
        )
      ) match
        case success: NativeCompilerSuccess => success.normalizedMlir
        case failure: NativeCompilerFailure => scala.util.Failure[Nothing](
            new java.lang.AssertionError(s"${failure.diagnostic}\n${failure.standardError}")
          ).get
      witnesses.foreach: (name, source) =>
        val document = ScalaToMlirBridge.lower(source())
        Files.writeString(directory.resolve(name + ".mlir"), document.text)
        val normalized = native(document)
        Files.writeString(directory.resolve(name + ".normalized.mlir"), normalized)
        assert(normalized.contains("nodal.collection"))
        assert(native(document.copy(text = normalized)) == normalized)
