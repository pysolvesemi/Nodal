package nodal

import java.lang.reflect.InvocationTargetException
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, StandardOpenOption}
import scala.collection.mutable.ArrayBuffer

/** Proposal-only controls; no build or execution is authorized in this worker. */
object SemanticResolutionSourceCacheControls:
  def main(args: Array[String]): Unit =
    val builder = new SemanticOriginBuilder
    val baseline = builder.resolve()
    val read = classOf[SemanticOriginBuilder].getDeclaredMethods
      .find(_.getName.endsWith("readSourceLines")).get
    read.setAccessible(true)
    val cache = classOf[SemanticOriginBuilder].getDeclaredFields
      .find(_.getName.endsWith("resolutionSourceLines")).get
    cache.setAccessible(true)
    val meter = classOf[SemanticOriginBuilder].getDeclaredFields
      .find(_.getName.endsWith("resolutionWork")).get
    meter.setAccessible(true)
    def lines(path: Path): Vector[String] =
      try read.invoke(builder, path).asInstanceOf[Vector[String]]
      catch case failure: InvocationTargetException => throw failure.getCause
    def original(failure: Throwable): Throwable =
      if failure.getClass.getSimpleName.contains("ResolutionWorkAborted") then
        val getter = failure.getClass.getDeclaredMethod("original")
        getter.setAccessible(true)
        original(getter.invoke(failure).asInstanceOf[Throwable])
      else failure
    // The first callback runs the IO controls inside the real scoped public entrypoint.
    // Recursive accounting callbacks only collect units; they never repeat the action.
    def scoped(action: ArrayBuffer[Int] => Unit): Vector[Int] =
      val charges = ArrayBuffer.empty[Int]
      var entered = false
      val result = builder.resolveWithWork: units =>
        charges += units
        if !entered then
          entered = true
          action(charges)
      assert(result == baseline)
      assert(cache.get(builder) == None && meter.get(builder) == None)
      charges.toVector

    val path = Files.createTempFile("semantic-source-cache-", ".scala")
    try
      val bytes = ("val café = 1\n//" + ("x" * 12000) + "\n").getBytes(StandardCharsets.UTF_8)
      Files.write(path, bytes)
      def repeated(): Vector[Int] = scoped: charges =>
        val start = charges.size
        val first = lines(path)
        val firstCharges = charges.drop(start).toVector
        val reuseStart = charges.size
        val again = lines(path)
        assert(first eq again, "same-call repeated path must reuse the exact immutable lines")
        val reusedCharges = charges.drop(reuseStart).toVector
        val lookup = Vector(path.getNameCount + 1, path.toString.length + 1, 1, 1)
        assert(firstCharges ==
          lookup ++ Vector(bytes.length, 1, 1, path.getNameCount + path.toString.length + 1, 1))
        assert(reusedCharges == lookup, "reuse still pays normalization, lookup and stat")
      val firstCall = repeated()
      val nextCall = repeated()
      assert(firstCall == nextCall, "stable calls must charge deterministically")
      assert(firstCall.count(_ == bytes.length) == 1)
      assert(nextCall.count(_ == bytes.length) == 1, "next call must physically read bytes again")
      assert(lines(path) == Vector("val café = 1", "//" + ("x" * 12000)))
      assert(cache.get(builder) == None, "ordinary reads must not create a cache")

      val sentinel = new IllegalArgumentException("cache scope budget sentinel")
      try
        scoped: _ =>
          lines(path)
          throw sentinel
        assert(false)
      catch case failure: Throwable => assert(original(failure) eq sentinel)
      assert(cache.get(builder) == None && meter.get(builder) == None)
      assert(builder.resolve() == baseline)

      try
        scoped: _ =>
          lines(path)
          Files.write(path, Array[Byte](10), StandardOpenOption.APPEND)
          lines(path)
        assert(false, "observable source change must refuse cached reuse")
      catch
        case failure: Throwable =>
          assert(original(failure).isInstanceOf[IllegalStateException])
      assert(cache.get(builder) == None && meter.get(builder) == None)

      scoped: _ =>
        val outer = lines(path)
        val outerCache = cache.get(builder)
        val outerMeter = meter.get(builder)
        scopedNested(
          builder,
          () => {
            val nested = lines(path)
            assert(!(nested eq outer), "nested public call owns a fresh source snapshot")
          }
        )
        assert(cache.get(builder) eq outerCache)
        assert(meter.get(builder) eq outerMeter)
        assert(lines(path) eq outer)
    finally
      Files.deleteIfExists(path)
    println("PASS: 7 semantic source-cache control groups")

  private def scopedNested(builder: SemanticOriginBuilder, action: () => Unit): Unit =
    var entered = false
    builder.resolveWithWork: _ =>
      if !entered then
        entered = true
        action()
    ()
