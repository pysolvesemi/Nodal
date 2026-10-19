package nodal.internal.testkit

import java.nio.file.{Files, Path}
import java.time.Duration

import nodal.*
import nodal.internal.bridge.*
import utest.*

enum MaterializationMode:
  case Idle, Run

given HwEnum[MaterializationMode] = HwEnum.derived

final class DigitalMaterializationSource extends Module, DigitalCollectionClock:
  val width = param(4.integer, range = 2 to 8)
  val left = in(UInt(width))
  val right = in(UInt(width))
  val product = left * right
  val explicitProduct = product.named("pretty_product")
  val kept = explicitProduct.keep("waveform")
  val shared = product + product
  val signedAlias = left.toSigned.named("nonnegative").keep("sign proof").toUnsigned
  val divisor = (1.U(1) + 1.U(1)).named("divisor").keep("constant divisor")
  val quotient = left / divisor
  val selected = (left === right).named("predicate")
  val matrix = in(Vec(SInt(4), 2, 3))
  val matrixAlias = matrix.flatten.named("flat_matrix").keep("shape observation")
  val mode = enumLiteral(EnumType[MaterializationMode](), MaterializationMode.Idle)
  val modeAlias = mode.named("mode_alias")
  val checked = (3.U(2) + 4.U(3)).named("checked_sum").keep("proof boundary").resizeChecked(3)

final class DigitalMaterializationCollectionSource extends Module, DigitalCollectionClock:
  val values = in(Vec(UInt(4), 4))
  val mapped = values.map(value => (value + 1.U(1)).named("lane_sum").keep("lane trace"))

final class DigitalMaterializationCapturedSource extends Module, DigitalCollectionClock:
  val values = in(Vec(UInt(4), 4))
  val divisor = (1.U(1) + 1.U(1)).named("captured_divisor").keep("proof boundary")
  val divided = values.map(value => (value / divisor).named("lane_quotient"))

object Increment55DigitalMaterializationTests extends TestSuite:
  private def snapshot(quality: EmitQuality = EmitQuality()): ConstructionSnapshot =
    ConstructionKernel.inspect(new DigitalMaterializationSource, EmitOptions(quality = quality))

  private def reject(value: ConstructionSnapshot): Unit =
    val error = scala.util.Try(ScalaToMlirBridge.fromSnapshot(value)).failed.get
    assert(error.isInstanceOf[BridgeException])

  val tests: Tests = Tests:
    test("same-type aliases preserve actual input edges and distinct typed intent"):
      val captured = snapshot()
      val aliases = captured.digitalExpressions.filter(_.operation == "digital_alias")
      assert(aliases.nonEmpty, aliases.forall(_.operands.size == 1))
      val named = aliases.find(
        _.materializationIntent.contains(KernelDigitalIntent.Named("pretty_product"))
      ).get
      val kept =
        aliases.find(_.materializationIntent.contains(KernelDigitalIntent.Keep("waveform"))).get
      val product = captured.digitalExpressions.find(_.operation == "digital_mul").get
      assert(named.operands == Vector(product.path), named.dataType == product.dataType)
      assert(kept.operands == Vector(named.path), kept.dataType == named.dataType)
      assert(aliases.exists(_.dataType == "Bool"))
      assert(aliases.exists(_.dataType.startsWith("Vec(")))
      assert(aliases.exists(_.dataType.startsWith("Enum(")))
      val identity = captured.names.find(_.semanticPath == named.path).get
      assert(identity.provenance == "explicit")
      assert(
        identity.aliases.contains("explicitProduct"),
        identity.aliases.contains("pretty_product")
      )

    test("checked-value proof passes through name and keep without rewriting arithmetic"):
      val captured = snapshot()
      assert(captured.digitalExpressions.exists(_.operation == "digital_resize_checked"))
      assert(captured.digitalExpressions.exists(_.operation == "digital_to_unsigned"))
      assert(captured.digitalExpressions.exists(_.operation == "digital_div"))
      val text = ScalaToMlirBridge.fromSnapshot(captured).text
      assert(text.contains("digital_alias"), text.contains("digital_resize_checked"))
      assert(text.contains("digital_intent"), text.contains("digital_source"))

    test("frozen policy is retained through inspect and source-only round trip"):
      Vector(TemporaryPolicy.InlineSafe, TemporaryPolicy.Readable, TemporaryPolicy.Debug).foreach:
        policy =>
          val requested = EmitQuality(
            temporaries = policy,
            naming = NamingPolicy.DebugStable,
            checks = CheckProfile.Release,
            waivers = Seq(CheckWaiver("example", "requested only", "example-check"))
          )
          val captured = snapshot(requested)
          assert(captured.emitQuality == requested)
          val text = ScalaToMlirBridge.fromSnapshot(captured).text
          val policyName = policy match
            case TemporaryPolicy.InlineSafe => "inline-safe"
            case TemporaryPolicy.Readable => "readable"
            case TemporaryPolicy.Debug => "debug"
          assert(text.contains(s"temporaries = \"$policyName\""))
          assert(text.contains("naming = \"debug-stable\""))
          assert(!text.contains("nodal.digital.materialization"))
          assert(text == ScalaToMlirBridge.lower(
            new DigitalMaterializationSource,
            EmitOptions(quality = requested)
          ).text)
          val described = ReproducibilityContract.describe(captured)
          assert(described.manifest.version == 2)
          assert(described.construction.contains("digital_graph"))
          Vector(described.construction, described.manifest.text).foreach: receipt =>
            assert(receipt.contains("\"checks_requested\": \"Release\""))
            assert(receipt.contains("\"waivers_requested\""))
            assert(receipt.contains("\"id\": \"example\""))
            assert(receipt.contains("\"reason\": \"requested only\""))
            assert(receipt.contains("\"check\": \"example-check\""))
          assert(described.manifest.text.contains("\"decision_status\": \"not-run\""))
          assert(described.manifest.text.contains("\"target_requirements\": \"pending\""))
          assert(described.manifest.text.contains("\"waivers\": []"))
          assert(!described.manifest.text.contains("\"id\": \"example-check\""))
          assert(!described.manifest.text.contains("see-normalized-mlir"))
          assert(described.manifest.text.contains("not-evaluated-by-source"))

    test("invalid authored names reasons and non-digital roots reject"):
      Vector("", "bad name", "9name", "bad-name", "a" * 256).foreach: name =>
        assert(scala.util.Try(1.U(1).named(name)).isFailure)
      Vector("", " ", " leading", "trailing ", "line\nbreak", "a" * 1025).foreach: reason =>
        assert(scala.util.Try(1.U(1).keep(reason)).isFailure)
      assert(scala.util.Try(1.0.real.named("analog")).isFailure)
      assert(scala.util.Try(1.integer.keep("integer")).isFailure)

    test("forged snapshots independently reject missing misplaced and contradictory intent"):
      val captured = snapshot()
      val alias = captured.digitalExpressions.find(_.operation == "digital_alias").get
      def replace(value: KernelDigitalExpressionSnapshot): ConstructionSnapshot =
        captured.copy(digitalExpressions =
          captured.digitalExpressions.map(current =>
            if current.path == alias.path then value else current
          )
        )
      reject(replace(alias.copy(materializationIntent = None)))
      reject(replace(alias.copy(materializationIntent =
        Some(KernelDigitalIntent.Named("bad name"))
      )))
      reject(replace(alias.copy(operation = "digital_not")))
      reject(replace(alias.copy(operands = alias.operands ++ alias.operands)))
      reject(replace(alias.copy(dataType = "SInt(1)")))
      reject(replace(alias.copy(literal = Some("1"))))
      reject(replace(alias.copy(caseIds = Vector("forged"))))
      reject(replace(alias.copy(region = Some("forged"))))

    test("collection aliases remain in their exact callback region"):
      val captured = ConstructionKernel.inspect(new DigitalMaterializationCollectionSource)
      val collection = captured.digitalCollections.head
      val aliases = captured.digitalExpressions.filter(_.operation == "digital_alias")
      assert(aliases.size == 2, aliases.forall(_.region.contains(collection.path)))
      val text = ScalaToMlirBridge.fromSnapshot(captured).text
      assert(text.contains("digital_alias"), text.contains("lane trace"))

    test("actual source policies aliases and captured proofs survive native normalization"):
      val compiler = Path.of(sys.env.getOrElse(
        "NODAL_NODALC",
        scala.util.Failure[Nothing](
          new java.lang.AssertionError("configured nodalc is required")
        ).get
      )).toAbsolutePath
      val cases = Vector(
        "inline" -> snapshot(EmitQuality(temporaries = TemporaryPolicy.InlineSafe)),
        "readable" -> snapshot(EmitQuality(temporaries = TemporaryPolicy.Readable)),
        "debug" -> snapshot(EmitQuality(temporaries = TemporaryPolicy.Debug)),
        "collection" -> ConstructionKernel.inspect(new DigitalMaterializationCollectionSource),
        "captured" -> ConstructionKernel.inspect(new DigitalMaterializationCapturedSource)
      )
      cases.foreach: (name, captured) =>
        val directory = Path.of(".work", "increment55-materialization", name).toAbsolutePath
        Files.createDirectories(directory)
        val source = ScalaToMlirBridge.fromSnapshot(captured)
        Files.writeString(directory.resolve("source.mlir"), source.text)
        def native(document: NodalMlirDocument): String = NativeCompilerClient.run(
          document,
          NativeCompilerRequest(
            compiler,
            Vector("--pass-pipeline=builtin.module(nodal-gate-default)"),
            directory,
            timeout = Duration.ofSeconds(90)
          )
        ) match
          case success: NativeCompilerSuccess => success.normalizedMlir
          case failure: NativeCompilerFailure =>
            scala.util.Failure[Nothing](new java.lang.AssertionError(
              s"${failure.diagnostic}\n${failure.standardError}"
            )).get
        val normalized = native(source)
        Files.writeString(directory.resolve("normalized.mlir"), normalized)
        assert(normalized.contains("nodal.digital.materialization"))
        assert(normalized.contains("target-neutral"), normalized.contains("explicit-keep"))
        if name == "inline" then assert(normalized.contains("shared-value"))
        if name == "readable" then assert(normalized.contains("source-name"))
        if name == "debug" then assert(normalized.contains("debug-anchor"))
        assert(native(source.copy(text = normalized)) == normalized)
