package nodal.internal.testkit

import nodal.*
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, StandardOpenOption}
import scala.collection.mutable

/** SOURCE-ONLY DRAFT. Private locateSpan diagnostics, not an authored-hardware capture. Only a
  * newly created disposable file is changed/deleted. All frozen inputs are read-only. A cached
  * source path is deliberately retained across calls; source bytes are not.
  */
object CapturePrefixSourceControls:
  import CapturePrefixDiagnostics.{call, field}

  def main(args: Array[String]): Unit =
    assert(args.length == 1, "provide a pre-authorized disposable runtime directory")
    val directory = Path.of(args(0)).toAbsolutePath.normalize()
    assert(Files.isDirectory(directory))
    val path = Files.createTempFile(directory, "prefix-freshness-", ".scala")
    val file = path.getFileName.toString
    val owner = "prefixdiag.disposable.Source"
    val builder = new SemanticOriginBuilder
    val cache = field(builder, "sourceCache")
      .asInstanceOf[mutable.Map[(String, String), Option[Path]]]
    cache.update(file -> owner, Some(path))
    val baseline = builder.resolve()
    val oldWork = field(builder, "resolutionWork")
    val oldLines = field(builder, "resolutionSourceLines")
    def span(): (SourceSpan, Option[String]) =
      call(builder, "locateSpan", file, owner, Int.box(1))
        .asInstanceOf[(SourceSpan, Option[String])]
    def restored(): Unit =
      assert(field(builder, "resolutionWork") eq oldWork)
      assert(field(builder, "resolutionSourceLines") eq oldLines)
      assert(
        cache.get(file -> owner) == Some(Some(path)),
        "source-path cache survives; it must not assert byte freshness"
      )
    def scoped(action: => Unit): Unit =
      var entered = false
      val result = builder.resolveWithWork: _ =>
        if !entered then
          entered = true
          action
      assert(entered && result == baseline)
      restored()
    def write(binding: String): Unit =
      Files.write(path, s"val $binding = 1\n".getBytes(StandardCharsets.UTF_8))
      ()
    def changed(action: => Unit): Unit =
      var failure: Throwable = null
      try scoped(action)
      catch case caught: Throwable => failure = caught
      def original(error: Throwable): Throwable =
        if error.getClass.getSimpleName.contains("ResolutionWorkAborted") then
          original(call(error, "original").asInstanceOf[Throwable])
        else error
      assert(failure != null)
      val cause = original(failure)
      // The diagnostic runs inside a charging callback, so each nested chargeWork may
      // carry another wrapper; this is not an assertion about captureSite's public boundary.
      assert(cause.isInstanceOf[IllegalStateException])
      assert(
        cause.getMessage == "semantic source changed during metered resolution",
        "locateSpan must not swallow the original source-change failure as a fallback span"
      )
      restored()
      assert(builder.resolve() == baseline)
    try
      write("firstBinding")
      scoped:
        assert(span()._2.contains("firstBinding"))
        assert(span()._2.contains("firstBinding"))
      write("differentBindingAfterFirstInvocation")
      scoped:
        assert(
          span()._2.contains("differentBindingAfterFirstInvocation"),
          "unchanged cached source path cannot reuse earlier invocation bytes"
        )
      changed:
        assert(span()._2.contains("differentBindingAfterFirstInvocation"))
        Files.write(path, Array[Byte](10), StandardOpenOption.APPEND)
        span()
      write("beforeDeletion")
      changed:
        assert(span()._2.contains("beforeDeletion"))
        assert(Files.deleteIfExists(path))
        span()
      // Between invocations there is no source-lines entry. Preserve the old locateSpan
      // best-effort fallback for a cached path that was already missing on this call.
      scoped:
        assert(span() == (SourceSpan(file, 1, 1, 1, 1) -> None))
      write("restoredBinding")
      scoped:
        assert(span()._2.contains("restoredBinding"))
      println("PASS locateSpan diagnostics: cross-call reread, within-call change/deletion " +
        "refusal, missing-at-entry fallback and scope restoration")
    finally
      Files.deleteIfExists(path)
      restored()
