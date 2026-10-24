package nodal.internal.testkit

import nodal.*
import nodal.internal.bridge.*
import nodal_external.Increment58DeferredHierarchySource
import utest.*

object Increment58DeferredHierarchyTests extends TestSuite:
  private def capture(mode: String, late: Boolean = true) =
    ConstructionKernel.inspect(new Increment58DeferredHierarchySource(mode, late))
  val tests = Tests:
    test("late-bound copies and aliases publish only exact parent descriptors"):
      for late <- Vector(false, true) do
        val snapshot = capture("copy", late)
        val parentValues = snapshot.digitalExpressions.filter(_.owner == snapshot.root)
        val copies = parentValues.filter(value =>
          Set("digital_not", "digital_shr", "digital_alias")(value.operation)
        )
        assert(copies.size == 4)
        assert(copies.forall(_.dataType == "UInt(actualWidth)"))
        val text = ScalaToMlirBridge.fromSnapshot(snapshot).text
        assert(
          text.contains("operator_name = \"digital_not\""),
          text.contains("operator_name = \"digital_alias\"")
        )
    test("bitwise and select retain final complete type equality obligations"):
      Vector("bitwise", "select").foreach: mode =>
        val snapshot = capture(mode)
        val values = snapshot.digitalExpressions.filter(value =>
          value.owner == snapshot.root && Set("digital_and", "digital_select")(value.operation)
        )
        assert(values.size == 1, values.head.dataType == "UInt(actualWidth)")
        val _ = ScalaToMlirBridge.fromSnapshot(snapshot)
      Vector("wrong-bitwise", "wrong-select").foreach: mode =>
        val failure = scala.util.Try(capture(mode)).failed.get
        assert(failure.isInstanceOf[ConstructionException])
        assert(failure.asInstanceOf[ConstructionException].diagnostic.code ==
          "NODAL-DIGITAL-055-003")
    test("field selection uses the finalized receiver rather than the formal field width"):
      val snapshot = capture("field")
      val field = snapshot.digitalExpressions.find(value =>
        value.owner == snapshot.root && value.operation == "digital_struct_get"
      ).get
      assert(field.dataType == "UInt(actualWidth)")
      val _ = ScalaToMlirBridge.fromSnapshot(snapshot)
    test("shape-changing inference seals only after actual parameter binding"):
      val early = ScalaToMlirBridge.fromSnapshot(capture("arithmetic", false))
      val late = ScalaToMlirBridge.fromSnapshot(capture("arithmetic", true))
      assert(early.text == late.text, late.text.contains("operator_name = \"digital_add\""))
    test("bridge independently rejects a stale formal result after final capture"):
      val snapshot = capture("copy")
      val value = snapshot.digitalExpressions.find(item =>
        item.owner == snapshot.root && item.operation == "digital_not"
      ).get
      val forged = snapshot.copy(digitalExpressions =
        snapshot.digitalExpressions.map(item =>
          if item.path == value.path then item.copy(dataType = "UInt(otherWidth)") else item
        )
      )
      val failure = scala.util.Try(ScalaToMlirBridge.fromSnapshot(forged)).failed.get
      assert(failure.isInstanceOf[BridgeException])

object Increment58DeferredHierarchyNativeTests extends TestSuite:
  val tests = Tests:
    test("finalized actual hierarchy values pass the native default semantic gate"):
      Vector("copy", "bitwise", "select", "field", "arithmetic").foreach: mode =>
        val document = ScalaToMlirBridge.fromSnapshot(
          ConstructionKernel.inspect(new Increment58DeferredHierarchySource(mode))
        )
        val directory = java.nio.file.Path.of(
          sys.env("NODAL_ROOT"),
          ".work",
          "f58-deferred-hierarchy",
          mode
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
            assert(success.normalizedMlir.contains("nodal.instance_port"))
          case failure: NativeCompilerFailure =>
            throw new java.lang.AssertionError(s"${failure.diagnostic}\n${failure.standardError}")
