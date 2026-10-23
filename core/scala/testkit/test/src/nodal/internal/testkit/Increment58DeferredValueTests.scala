package nodal.internal.testkit

import nodal.*
import nodal.internal.bridge.*
import nodal_external.{Increment58DeferredRealSource, Increment58DeferredValueSource}
import utest.*

object Increment58DeferredValueTests extends TestSuite:
  private def capture(late: Boolean = true, mode: String = "valid", concrete: Boolean = false) =
    ConstructionKernel.inspect(new Increment58DeferredValueSource(late, mode, concrete))
  private def failure(mode: String): ConstructionException =
    scala.util.Try(capture(mode = mode)).failed.get.asInstanceOf[ConstructionException]
  val tests = Tests:
    test("early and late conversion and Struct construction preserve identical actual widths"):
      for concrete <- Vector(false, true) do
        val early = ScalaToMlirBridge.fromSnapshot(capture(false, concrete = concrete))
        val late = ScalaToMlirBridge.fromSnapshot(capture(true, concrete = concrete))
        assert(early.text == late.text)
      val snapshot = capture()
      val operations =
        snapshot.digitalExpressions.filter(_.owner == snapshot.root).map(_.operation).toSet
      assert(Set(
        "digital_extend",
        "digital_truncate",
        "digital_wrap",
        "digital_saturate",
        "digital_resize_checked",
        "digital_to_signed",
        "digital_to_unsigned",
        "digital_reinterpret_signed",
        "digital_reinterpret_unsigned",
        "digital_struct_make",
        "digital_struct_get"
      ).subsetOf(operations))
    test("final actual ranges enforce loss and representability policies"):
      Vector(
        "extend" -> "NODAL-DIGITAL-054-007",
        "truncate" -> "NODAL-DIGITAL-054-007",
        "checked" -> "NODAL-DIGITAL-054-008",
        "negative" -> "NODAL-DIGITAL-054-008"
      ).foreach: (mode, code) =>
        assert(failure(mode).diagnostic.code == code)
    test("deferred construction preserves nominal schemas exact widths and member order"):
      Vector("foreign-schema", "foreign-width", "order").foreach: mode =>
        val error = failure(mode)
        assert(error.diagnostic.code == "NODAL-STRUCT-055-001")
        assert(error.getMessage.contains("belong to this semantic schema in declared order"))
    test("equal envelopes do not make independent parent parameters interchangeable"):
      val error =
        scala.util.Try(ScalaToMlirBridge.fromSnapshot(capture(mode = "wrong-width"))).failed.get
      assert(error.isInstanceOf[ConstructionException])
      assert(error.getMessage.contains("NODAL-COLLECTION-055-005"))
    test("bridge independently rejects stale conversion and Struct result descriptors"):
      val snapshot = capture()
      val signed = snapshot.digitalExpressions.find(value =>
        value.owner == snapshot.root && value.operation == "digital_to_signed"
      ).get
      val made = snapshot.digitalExpressions.find(value =>
        value.owner == snapshot.root && value.operation == "digital_struct_make"
      ).get
      Vector(
        signed.copy(dataType = "SInt(width)"),
        made.copy(dataType = made.dataType.replace("UInt(width)", "UInt(otherWidth)"))
      ).foreach: forged =>
        val original = snapshot.digitalExpressions.find(_.path == forged.path).get
        assert(forged.dataType != original.dataType)
        val error = scala.util.Try(ScalaToMlirBridge.fromSnapshot(snapshot.copy(
          digitalExpressions = snapshot.digitalExpressions.map(value =>
            if value.path == forged.path then forged else value
          )
        ))).failed.get
        assert(error.isInstanceOf[BridgeException])
    test("Real finiteness is proved only from finalized source width"):
      assert(ConstructionKernel.inspect(new Increment58DeferredRealSource(1023)).modules.nonEmpty)
      val error = scala.util.Try(
        ConstructionKernel.inspect(new Increment58DeferredRealSource(1024))
      ).failed.get
      assert(error.asInstanceOf[ConstructionException].diagnostic.code == "NODAL-DIGITAL-054-008")

object Increment58DeferredValueNativeTests extends TestSuite:
  val tests = Tests:
    test("actual finalized symbolic and concrete scalar and Struct values pass native gates"):
      for late <- Vector(false, true); concrete <- Vector(false, true) do
        val document = ScalaToMlirBridge.fromSnapshot(
          ConstructionKernel.inspect(new Increment58DeferredValueSource(late, concrete = concrete))
        )
        val directory = java.nio.file.Path.of(
          sys.env("NODAL_ROOT"),
          ".work",
          "f58-deferred-values",
          s"native-$late-$concrete"
        ).toAbsolutePath
        val _ = java.nio.file.Files.createDirectories(directory)
        val _ = java.nio.file.Files.writeString(directory.resolve("input.mlir"), document.text)
        val result = NativeCompilerClient.run(
          document,
          NativeCompilerRequest(
            java.nio.file.Path.of(sys.env("NODAL_NODALC")).toAbsolutePath,
            Vector("--nodal-gate-default"),
            directory,
            timeout = java.time.Duration.ofSeconds(90)
          )
        )
        val _ = java.nio.file.Files.writeString(
          directory.resolve("diagnostics.txt"),
          result.standardError
        )
        result match
          case success: NativeCompilerSuccess =>
            assert(success.normalizedMlir.contains("operator_name = \"digital_struct_make\""))
            assert(success.normalizedMlir.contains("operator_name = \"digital_to_signed\""))
          case failure: NativeCompilerFailure =>
            throw new java.lang.AssertionError(s"${failure.diagnostic}\n${failure.standardError}")
