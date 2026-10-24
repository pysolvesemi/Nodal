package nodal.internal.testkit.deadsuffix

import nodal.*
import scala.collection.mutable.ArrayBuffer

/** Counting/throwing callbacks are resolver diagnostics, NOT 65536-unit Session acceptance. */
object DeadSuffixControls:
  private var root: Module = null
  def retain(value: Module): Unit = root = value

  private def field(target: AnyRef, suffix: String): AnyRef =
    val fields = target.getClass.getDeclaredFields.filter(_.getName.endsWith(suffix))
    assert(fields.length == 1, suffix)
    fields.head.setAccessible(true)
    fields.head.get(target)

  private def off(builder: SemanticOriginBuilder): Unit =
    assert(field(builder, "canonicalAccounting") == java.lang.Boolean.FALSE)
    assert(field(builder, "resolutionWork") == None)
    assert(field(builder, "resolutionSourceLines") == None)

  private def suffixDepth: Int =
    Thread.currentThread.getStackTrace.count(_.getMethodName.contains("sourceSuffix"))

  private def inSuffix: Boolean = suffixDepth > 0

  private def measured(builder: SemanticOriginBuilder, exact: Boolean)
      : (SemanticOriginResult, Vector[Int], Vector[Int]) =
    val all = ArrayBuffer.empty[Int]
    val suffix = ArrayBuffer.empty[Int]
    val charge: Int => Unit = units =>
      all += units
      if inSuffix then suffix += units
      ()
    val result =
      if exact then builder.resolveConstructionWithWork(charge)
      else builder.resolveWithWork(charge)
    off(builder)
    (result, all.toVector, suffix.toVector)

  // Canonical text retains every field while sorting only unordered maps/sets.
  private def raw(value: Any): String = value match
    case map: scala.collection.Map[?, ?] =>
      map.iterator.map((key, item) => raw(key) + "->" + raw(item)).toVector.sorted
        .mkString("Map(", ",", ")")
    case set: Set[?] => set.iterator.map(raw).toVector.sorted.mkString("Set(", ",", ")")
    case text: String => "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"")
        .replace("\n", "\\n").replace("\r", "\\r") + "\""
    case seq: Seq[?] => seq.iterator.map(raw).mkString("Seq(", ",", ")")
    case product: Product => product.productIterator.map(raw)
        .mkString(product.productPrefix + "(", ",", ")")
    case other => String.valueOf(other)

  private def abortSuffix(builder: SemanticOriginBuilder, exact: Boolean): Unit =
    val sentinel = new IllegalStateException("used suffix diagnostic exhaustion")
    var stopped = false
    val charge: Int => Unit = _ =>
      if inSuffix then
        stopped = true
        throw sentinel
    val error = scala.util.Try:
      if exact then builder.resolveConstructionWithWork(charge)
      else builder.resolveWithWork(charge)
    assert(stopped && error.failed.get.eq(sentinel))
    off(builder)
    assert(builder.resolveConstructionWithWork(_ => ()) == builder.resolve())
    off(builder)

  private def nestedAbort(builder: SemanticOriginBuilder): Unit =
    val sentinel = new IllegalStateException("nested suffix diagnostic exhaustion")
    var reached = false
    builder.resolveConstructionWithWork: _ =>
      if !reached && inSuffix then
        reached = true
        val outerWork = field(builder, "resolutionWork")
        val outerLines = field(builder, "resolutionSourceLines")
        val outerDepth = suffixDepth
        val thrown = scala.util.Try(builder.resolveWithWork(_ =>
          if suffixDepth > outerDepth then throw sentinel
        )).failed.get
        assert(thrown eq sentinel)
        assert(field(builder, "canonicalAccounting") == java.lang.Boolean.TRUE)
        assert(field(builder, "resolutionWork") eq outerWork)
        assert(field(builder, "resolutionSourceLines") eq outerLines)
    assert(reached)
    off(builder)

  def main(args: Array[String]): Unit =
    // baseline permits the old exact metadata suffix solely for cross-jar raw comparison.
    val baseline = args.contains("baseline")
    ConstructionKernel.inspect(new DeadSuffixRoot)
    val modes = Vector("metadata", "fifo", "pipe", "delay", "fsm-operation", "fsm-owner")
    modes.foreach: mode =>
      val builder = new DeadSuffixCapture(root, mode).builder
      val operation = field(builder, "operations").asInstanceOf[ArrayBuffer[AnyRef]].head
      val site = field(operation, "site")
      val span = field(site, "span").asInstanceOf[Option[SourceSpan]].get
      assert(span.path.endsWith("DeadSuffixRoot.scala") && span.line > 0)
      val expectedOperation = if mode.startsWith("fsm-") then
        if mode == "fsm-operation" then "fsm" else "metadata"
      else mode
      assert(field(site, "apiOperation") == expectedOperation)
      assert(field(site, "apiOwner") ==
        (if mode == "fsm-owner" then "SuffixFsmCaptureApi" else "SuffixCaptureApi"))
      val (old, oldAll, oldSuffix) = measured(builder, false)
      val (current, exactAll, exactSuffix) = measured(builder, true)
      assert(old == current, "complete SemanticOriginResult parity: " + mode)
      assert(oldSuffix.nonEmpty, "legacy eager suffix: " + mode)
      if mode == "metadata" then
        assert(current.generatedNames.isEmpty)
        if baseline then assert(exactSuffix.nonEmpty) else assert(exactSuffix.isEmpty)
      else
        assert(exactSuffix.nonEmpty, "used suffix retained: " + mode)
        val categories = current.generatedNames.map(_.category).sorted
        val expected = mode match
          case "fifo" => Vector("crossing", "fifo")
          case "pipe" | "delay" => Vector("pipeline-state")
          case _ => Vector("fsm-state")
        assert(categories == expected.sorted, mode + ": " + categories)
        abortSuffix(builder, true)
        nestedAbort(builder)
      // Even metadata must fail in legacy: eager suffix precedes dead consumer branches.
      abortSuffix(builder, false)
      println("RAW " + mode + " " + raw(current))
      println("LEGACY " + mode + " " + oldAll.mkString(","))
      println("DIAGNOSTIC " + mode + " old=" + oldAll.map(_.toLong).sum +
        " exact=" + exactAll.map(_.toLong).sum + " suffixOld=" + oldSuffix.size +
        " suffixExact=" + exactSuffix.size)
    println(
      "PASS dead suffix resolver raw parity, consumers, abort restoration, legacy eager order"
    )
