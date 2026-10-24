package nodal

import java.lang.reflect.InvocationTargetException
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, StandardOpenOption}
import scala.collection.mutable.ArrayBuffer

/** Standalone focused controls; intentionally not compiled or run in this source-only stage.
  * Reflection reaches only the private IO seam to avoid adding a production test API.
  */
object SemanticResolutionWorkControls:
  def main(args: Array[String]): Unit =
    val builder = new SemanticOriginBuilder
    val expected = builder.resolve()
    val failure = new IllegalArgumentException("work budget sentinel")
    try
      builder.resolveWithWork(_ => throw failure)
      assert(false, "budget callback must fail")
    catch case caught: IllegalArgumentException => assert(caught eq failure)
    assert(builder.resolve() == expected, "failed scoped meter must be restored")
    def measured(): Vector[Int] =
      val charges = ArrayBuffer.empty[Int]
      assert(builder.resolveWithWork(units => { charges += units; () }) == expected)
      charges.toVector
    assert(measured() == measured(), "stable repeated epochs must charge deterministically")
    assert(measured().map(_.toLong).sum == 192L, "empty resolution precharge")

    val meter = classOf[SemanticOriginBuilder].getDeclaredFields
      .find(_.getName.endsWith("resolutionWork")).get
    meter.setAccessible(true)
    val read = classOf[SemanticOriginBuilder].getDeclaredMethods
      .find(_.getName.endsWith("readSourceLines")).get
    read.setAccessible(true)
    val sourceLines = classOf[SemanticOriginBuilder].getDeclaredMethods
      .find(_.getName.endsWith("sourceLines")).get
    sourceLines.setAccessible(true)
    val siteConstructor = sourceLines.getParameterTypes.head.getDeclaredConstructors.head
    siteConstructor.setAccessible(true)
    def sourceSite(path: Path): AnyRef =
      siteConstructor.newInstance(
        Some(SourceSpan(path.toAbsolutePath.toString, 1, 1, 1, 1)),
        "user",
        "construct",
        None
      ).asInstanceOf[AnyRef]
    def carriedOriginal(error: Throwable): Throwable =
      val getter = error.getClass.getDeclaredMethod("original")
      getter.setAccessible(true)
      getter.invoke(error).asInstanceOf[Throwable]

    val path = Files.createTempFile("semantic-resolution-work-", ".scala")
    try
      val bytes = "val café = 1\r\nval next = 2\n".getBytes(StandardCharsets.UTF_8)
      Files.write(path, bytes)
      val charges = ArrayBuffer.empty[Int]
      // Manual meter installation does not create a per-call cache, so both reads
      // pay lookup/stat, full advertised bytes, post-read stat and entry/key accounting.
      val expectedReadCharges = Vector(
        path.getNameCount + 1,
        path.toString.length + 1,
        1,
        1,
        bytes.length,
        1,
        1,
        path.getNameCount + path.toString.length + 1,
        1
      )
      meter.set(builder, Some((units: Int) => { charges += units; () }))
      val lines = read.invoke(builder, path).asInstanceOf[Vector[String]]
      assert(lines == Vector("val café = 1", "val next = 2"))
      assert(charges.toVector == expectedReadCharges, "UTF-8 bytes, not character count")
      charges.clear()
      assert(read.invoke(builder, path) == lines)
      assert(charges.toVector == expectedReadCharges)
      meter.set(builder, None)
      assert(read.invoke(builder, path) == lines, "unmetered eager source result parity")

      meter.set(builder, Some((_: Int) => throw failure))
      try
        sourceLines.invoke(builder, sourceSite(path))
        assert(false, "sourceLines Exception fallback must not swallow budget failure")
      catch
        case wrapped: InvocationTargetException =>
          assert(carriedOriginal(wrapped.getCause) eq failure)

      var changed = false
      var growthChargeIndex = 0
      meter.set(
        builder,
        Some((units: Int) =>
          growthChargeIndex += 1
          // sourceLines pays raw/combined path work, then normalize components/chars,
          // lookup and initial stat. Only the seventh charge prepays advertised bytes.
          if growthChargeIndex == 7 then
            assert(units == bytes.length)
            changed = true
            Files.write(path, Array[Byte](10), StandardOpenOption.APPEND)
            ()
        )
      )
      try
        sourceLines.invoke(builder, sourceSite(path))
        assert(false, "growth beyond prepaid size must fail, not return truncated names")
      catch
        case wrapped: InvocationTargetException =>
          val original = carriedOriginal(wrapped.getCause)
          assert(changed, "growth must occur after initial stat, at byte precharge")
          assert(original.isInstanceOf[IllegalStateException])
          assert(original.getMessage == "semantic source grew beyond its prepaid byte bound")
    finally
      meter.set(builder, None)
      Files.deleteIfExists(path)
    assert(builder.resolve() == expected)
