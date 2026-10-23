package nodal.internal.testkit

import nodal.*
import nodal.internal.bridge.*
import nodal_external.Increment58DeferredArithmeticSource
import utest.*

object Increment58DeferredArithmeticTests extends TestSuite:
  private def capture(late: Boolean = true, mode: String = "valid") =
    ConstructionKernel.inspect(new Increment58DeferredArithmeticSource(late, mode))
  val tests = Tests:
    test("early and late binding publish identical actual arithmetic descriptors"):
      val early = ScalaToMlirBridge.fromSnapshot(capture(false))
      val late = ScalaToMlirBridge.fromSnapshot(capture(true))
      assert(early.text == late.text)
      val snapshot = capture()
      val operations =
        snapshot.digitalExpressions.filter(_.owner == snapshot.root).map(_.operation).toSet
      assert(Set(
        "digital_add",
        "digital_mul",
        "digital_neg",
        "digital_concat",
        "digital_extract",
        "digital_shl",
        "digital_lt",
        "digital_div"
      ).subsetOf(operations))
    test("final width and exact divisor obligations remain causal"):
      Vector(
        "extract" -> "NODAL-DIGITAL-055-004",
        "zero" -> "NODAL-DIGITAL-055-005",
        "runtime" -> "NODAL-DIGITAL-055-005"
      ).foreach: (mode, code) =>
        val failure = scala.util.Try(capture(mode = mode)).failed.get
        assert(failure.asInstanceOf[ConstructionException].diagnostic.code == code)
    test("bridge rejects assignment to the old unexpanded width"):
      val failure =
        scala.util.Try(ScalaToMlirBridge.fromSnapshot(capture(mode = "wrong-width"))).failed.get
      assert(
        failure.isInstanceOf[BridgeException],
        failure.getMessage.contains("NODAL-BRIDGE-054-STORAGE")
      )
    test("bridge independently rejects a forged arithmetic result descriptor"):
      val snapshot = capture()
      val result = snapshot.digitalExpressions.find(item =>
        item.owner == snapshot.root && item.operation == "digital_add"
      ).get
      val forged = snapshot.copy(digitalExpressions =
        snapshot.digitalExpressions.map(item =>
          if item.path == result.path then item.copy(dataType = "UInt(width)") else item
        )
      )
      val failure = scala.util.Try(ScalaToMlirBridge.fromSnapshot(forged)).failed.get
      assert(failure.isInstanceOf[BridgeException])

object Increment58DeferredArithmeticNativeTests extends TestSuite:
  val tests = Tests:
    test("actual finalized symbolic and concrete arithmetic pass native ownership gates"):
      for concrete <- Vector(false, true) do
        val document = ScalaToMlirBridge.fromSnapshot(
          ConstructionKernel.inspect(new Increment58DeferredArithmeticSource(concrete = concrete))
        )
        val directory = java.nio.file.Path.of(
          sys.env("NODAL_ROOT"),
          ".work",
          "f58-deferred-arithmetic",
          s"native-$concrete"
        ).toAbsolutePath
        java.nio.file.Files.createDirectories(directory)
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
            assert(success.normalizedMlir.contains("operator_name = \"digital_add\""))
          case failure: NativeCompilerFailure =>
            throw new java.lang.AssertionError(s"${failure.diagnostic}\n${failure.standardError}")
