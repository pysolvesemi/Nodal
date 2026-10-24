package nodal.internal.testkit

import java.nio.file.{Files, Path}
import java.time.Duration
import nodal.*
import nodal.internal.bridge.*
import nodal_external.*
import scala.compiletime.testing.typeCheckErrors
import utest.*

object Increment58ConservativeInterfaceTests extends TestSuite:
  private def capture() = ConstructionKernel.inspect(new Increment58ConservativeLocal)
  val tests = Tests:
    test("typed capability preserves terminal compatibility without fabricated placement"):
      assert(typeCheckErrors("""import nodal.*
        def invalid(a: ConservativeView[Electrical.type, ConservativeAccess.Sense],
                    b: ConservativeView[Electrical.type, ConservativeAccess.Connect]) = a.connectTo(b)
      """).nonEmpty)
      assert(typeCheckErrors("""import nodal.*
        def invalid[D <: Discipline, E <: Discipline](a: ConservativeView[D, ConservativeAccess.Connect],
                    b: ConservativeView[E, ConservativeAccess.Connect]) = a.connectTo(b)
      """).nonEmpty)
      assert(typeCheckErrors("""import nodal.*
        def invalid(a: ConservativeView[Electrical.type, ConservativeAccess.Connect]) = a.terminal
      """).nonEmpty)
      assert(typeCheckErrors("""import nodal.*
        def valid(a: TerminalView[Electrical.type, ConservativeAccess.Contribute]): Terminal[Electrical.type] = a.terminal
      """).isEmpty)

    test("ordinary monitor and nested child accesses retain exact semantic origins"):
      var saved: Option[Increment58ConservativeLocal] = None
      val original = ConstructionKernel.inspect:
        val source = new Increment58ConservativeLocal
        saved = Some(source)
        source
      assert(saved.get.positive eq saved.get.again)
      for snapshot <- Vector(
          original,
          ConstructionKernel.inspect(new Increment58ConservativeLocal("monitor-sense")),
          ConstructionKernel.inspect(new Increment58ConservativeHierarchy)
        )
      do
        val text = ScalaToMlirBridge.fromSnapshot(snapshot, Backend.VerilogA).text
        assert(text.contains("\"nodal.member_access\""))
        assert(!text.contains("\"nodal.terminal\""))
        assert(!text.contains("\"nodal.interface_connect\""))
        assert(ReproducibilityContract.canonicalSnapshot(snapshot).contains("conservative_member"))
    test("copied foreign nested and narrowed source capabilities fail independently"):
      for mode <- Vector("wrong-field", "wrong-role", "monitor-connect") do
        val failure = scala.util.Try(
          ConstructionKernel.inspect(new Increment58ConservativeLocal(mode))
        ).failed.get
        assert(failure.isInstanceOf[ConstructionException])
      for mode <- Vector("raw", "wrong-nested", "missing-nested") do
        val failure = scala.util.Try(
          ConstructionKernel.inspect(new Increment58ConservativeHierarchy(mode))
        ).failed.get
        assert(failure.isInstanceOf[ConstructionException])
      for monitor <- Vector(false, true) do
        val failure = scala.util.Try(
          ConstructionKernel.inspect(new Increment58ConservativeSenseContribution(monitor))
        ).failed.get
        assert(
          failure.isInstanceOf[ConstructionException],
          failure.getMessage.contains("contribution requires")
        )
    test("bridge independently rejects changed origin role and discipline"):
      val original = capture()
      val declaration =
        original.modules.flatMap(_.declarations).find(_.conservativeMember.nonEmpty).get
      val origin = declaration.conservativeMember.get
      val changes = Vector(
        declaration.copy(conservativeMember = Some(origin.copy(member = "absent"))),
        declaration.copy(conservativeMember = Some(origin.copy(endpoint = "absent"))),
        declaration.copy(conservativeMember = Some(origin.copy(role = "connected"))),
        declaration.copy(attributes =
          declaration.attributes.map((key, value) =>
            key -> (if key == "discipline" then "foreign" else value)
          )
        )
      )
      for changed <- changes do
        val forged = original.copy(modules =
          original.modules.map(module =>
            module.copy(declarations =
              module.declarations.map(value =>
                if value.path == declaration.path then changed else value
              )
            )
          )
        )
        val failure = scala.util.Try(ScalaToMlirBridge.fromSnapshot(forged)).failed.get
        assert(failure.isInstanceOf[BridgeException])
    test("old terminal node and resolved source artifacts retain their contract"):
      val snapshots = Vector(
        ConstructionKernel.inspect(new Increment58ConservativeTerminalPredecessor),
        ConstructionKernel.inspect(new Increment58ConservativeNodePredecessor),
        ConstructionKernel.inspect(new Increment58TypedResolvedMemberSource)
      )
      for snapshot <- snapshots do
        val _ = ScalaToMlirBridge.fromSnapshot(snapshot)
        assert(!ReproducibilityContract.canonicalSnapshot(snapshot).contains("conservative_member"))
      assert(ReproducibilityContract.canonicalSnapshot(snapshots.last).contains("resolved_member"))

object Increment58ConservativeInterfaceNativeTests extends TestSuite:
  private def run(
      source: => Module,
      name: String,
      rewrite: String => String = identity
  ): NativeCompilerResult =
    val original =
      ScalaToMlirBridge.fromSnapshot(ConstructionKernel.inspect(source), Backend.VerilogA)
    val text = rewrite(original.text)
    val digest = java.security.MessageDigest.getInstance("SHA-256")
      .digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8))
      .map(value => f"${value & 0xff}%02x").mkString
    val document = original.copy(text = text, sha256 = digest)
    val directory = Path.of(sys.env("NODAL_ROOT"), ".work", "f58-conservative-interface", name)
    val _ = Files.createDirectories(directory)
    val _ = Files.writeString(directory.resolve("input.mlir"), document.text)
    val result = NativeCompilerClient.run(
      document,
      NativeCompilerRequest(
        Path.of(sys.env("NODAL_TRANSLATE")).toAbsolutePath,
        Vector("--nodal-to-verilog-a-scalar-hierarchy"),
        directory,
        timeout = Duration.ofSeconds(90)
      )
    )
    val _ = Files.writeString(directory.resolve("output.va"), result.standardOutput)
    val _ = Files.writeString(directory.resolve("diagnostics.txt"), result.standardError)
    result
  val tests = Tests:
    test("actual local monitor nested child and compatible predecessors emit verified Verilog A"):
      for name <- Vector("local", "monitor", "nested", "terminal", "node") do
        val result = run(
          name match
            case "local" => new Increment58ConservativeLocal
            case "monitor" => new Increment58ConservativeLocal("monitor-sense")
            case "nested" => new Increment58ConservativeHierarchy
            case "terminal" => new Increment58ConservativeTerminalPredecessor
            case _ => new Increment58ConservativeNodePredecessor
          ,
          name
        )
        assert(result.isInstanceOf[NativeCompilerSuccess])
        assert(result.standardOutput.contains("endmodule"))
        if Set("local", "monitor", "nested").contains(name) then
          assert(result.standardOutput.contains("nodal.interface_correspondence mlir-v1"))
    test("live state and unaccessed mixed leaf cannot be erased as proof-only domains"):
      for mode <- Vector("state", "mixed") do
        val result = run(new Increment58ConservativeLiveDigital(mode), mode)
        assert(result.isInstanceOf[NativeCompilerFailure], result.standardOutput.isEmpty)
        assert(result.standardError.contains(if mode == "state" then "surviving consumer"
        else "mixed/digital/signal-flow"))
    test("extra instance metadata cannot retain an erased Interface proof-domain symbol"):
      val result = run(
        new Increment58ConservativeHierarchy,
        "surviving-instance-symbol",
        text =>
          val changed = text.linesIterator.map(line =>
            if line.contains("\"nodal.instance\"") then
              line.replace("metadata = {", "metadata = {surviving_domain = @core, ")
            else line
          ).mkString("\n")
          assert(changed.contains("surviving_domain = @core"))
          changed
      )
      assert(result.isInstanceOf[NativeCompilerFailure], result.standardOutput.isEmpty)
      assert(result.standardError.contains("outside exact instance domain_bindings"))
