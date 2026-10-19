package nodal.internal.testkit

import java.nio.file.{FileAlreadyExistsException, Files, Path}
import scala.util.Try

import nodal.internal.integrations.*
import utest.*

object OsdiArtifactTests extends TestSuite:
  private def fixture(): (Path, OpenVafCompileResult) =
    val parent = Path.of(".work/increment53/artifact-tests").toAbsolutePath
    Files.createDirectories(parent)
    val root = Files.createTempDirectory(parent, "fixture-")
    val bytes = Array.tabulate[Byte](64)(_.toByte)
    val source = Files.write(root.resolve("compiled.osdi"), bytes)
    val success = ToolProcessResult(Vector("fixture"), "", "", Some(0), None, 0)
    val identity = OpenVafIdentity(
      "fixture",
      "a" * 64,
      "model.va",
      "verilog-a",
      "fixture",
      None,
      None,
      "fixture",
      None,
      None,
      Vector.empty,
      Map.empty,
      "linux-x86_64",
      "PT5S",
      "PT30S",
      None,
      Some("b" * 64),
      OpenVafProvenance()
    )
    val compiled = OpenVafCompileResult(
      OpenVafOutcome.Compiled,
      "fixture",
      identity,
      versionProbe = Some(success),
      compilation = Some(success),
      artifact =
        Some(OpenVafArtifact(source, OpenVafDiagnosticMapper.sha256(bytes.toVector), bytes.length))
    )
    root -> compiled

  private def openDescriptorsFor(path: Path): Long =
    val descriptors = Files.list(Path.of("/proc/self/fd"))
    try descriptors.filter(descriptor =>
        Try(Files.readSymbolicLink(descriptor)).toOption.contains(path)
      ).count()
    finally descriptors.close()

  val tests: Tests = Tests:
    test("repeated output acquisition failures close every artifact input"):
      val sample = fixture()
      val directory = sample._1
      val compiled = sample._2
      val destination = Files.writeString(directory.resolve("existing.osdi"), "keep")
      val source = compiled.artifact.get.path
      assert(openDescriptorsFor(source) == 0)
      for _ <- 0 until 32 do
        val failure = Try(OsdiArtifact.stage(compiled, destination)).failed.toOption
        assert(failure.exists(_.isInstanceOf[FileAlreadyExistsException]))
        assert(openDescriptorsFor(source) == 0)
      assert(Files.readString(destination) == "keep")

    test("successful staging and changed content retain hash checks and close files"):
      val sample = fixture()
      val directory = sample._1
      val compiled = sample._2
      val source = compiled.artifact.get.path
      val destination = directory.resolve("staged.osdi")
      assert(OsdiArtifact.stage(compiled, destination) == Right(()))
      assert(Files.readAllBytes(source).toVector == Files.readAllBytes(destination).toVector)
      assert(openDescriptorsFor(source) == 0, openDescriptorsFor(destination) == 0)
      Files.write(source, Array.fill[Byte](64)(0))
      val rejected = directory.resolve("rejected.osdi")
      assert(OsdiArtifact.stage(compiled, rejected).left.toOption.exists(_.contains("content")))
      assert(openDescriptorsFor(source) == 0, openDescriptorsFor(rejected) == 0)
