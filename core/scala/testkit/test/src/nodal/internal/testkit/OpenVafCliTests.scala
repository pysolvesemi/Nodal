package nodal.internal.testkit

import java.io.{ByteArrayOutputStream, PrintStream}
import java.nio.charset.StandardCharsets
import nodal.internal.cli.OpenVafCheck
import utest.*

object OpenVafCliTests extends TestSuite:
  private def invoke(arguments: String*): (Int, String, String) =
    val output = new ByteArrayOutputStream()
    val errors = new ByteArrayOutputStream()
    val out = new PrintStream(output, true, StandardCharsets.UTF_8)
    val err = new PrintStream(errors, true, StandardCharsets.UTF_8)
    try
      val status = OpenVafCheck.run(arguments.toArray, out, err)
      (status, output.toString(StandardCharsets.UTF_8), errors.toString(StandardCharsets.UTF_8))
    finally
      out.close()
      err.close()

  val tests: Tests = Tests:
    test("help and exact pinned profiles are available without launching a compiler"):
      val help = invoke("--help")
      assert(help._1 == 0, help._2.contains("--map-authored-source"), help._3.isEmpty)
      val profiles = invoke("--list-profiles")
      assert(profiles._1 == 0, profiles._3.isEmpty)
      assert(profiles._2.linesIterator.size == 2)
      assert(profiles._2.contains("openvaf-23.5.0-linux-x86_64"))
      assert(profiles._2.contains("openvaf-reloaded-20260616-linux-x86_64"))

    test("missing duplicate and unknown options reject before compiler invocation"):
      Vector(
        Vector.empty[String],
        Vector("--help", "--profile", "ignored"),
        Vector("--model"),
        Vector("--model", "one", "--model", "two"),
        Vector("--map-authored-source", "--map-authored-source"),
        Vector("--unknown", "value")
      ).foreach: arguments =>
        val result = invoke(arguments*)
        assert(result._1 == 2, result._2.isEmpty)
        assert(result._3.contains("NODAL-OPENVAF-CLI-001"))

    test("missing model is an explicit CLI input rejection"):
      val result = invoke(
        "--model",
        "/nonexistent/nodal-openvaf-model.va",
        "--executable",
        "/nonexistent/openvaf",
        "--evidence-dir",
        "/nonexistent/evidence",
        "--profile",
        "openvaf-23.5.0-linux-x86_64"
      )
      assert(result._1 == 2, result._2.isEmpty)
      assert(result._3.contains("model must be a regular file"))
