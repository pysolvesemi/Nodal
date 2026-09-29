package nodal.internal.testkit

import nodal.*
import nodal.increment35fixture.{Increment35MultipleStateFixture, Increment35TypedInitialFixture}
import nodal.increment36fixture.WaveformSource
import nodal.increment39fixture.AnalogNoiseSource
import nodal.increment40fixture.TransferFilters
import nodal.increment41fixture.FunctionAmplifier
import nodal.increment42fixture.*
import nodal.internal.bridge.{ReproducibilityContract, ScalaToMlirBridge}

import java.lang.management.ManagementFactory
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import scala.collection.mutable.ArrayBuffer
import scala.jdk.CollectionConverters.*
import scala.util.Try

/** Test-only lossless serialization of immutable construction products. Ordered fields and
  * sequences are retained; only intrinsically unordered maps and sets are canonicalized. Unknown
  * values fail closed instead of hiding object identities behind arbitrary toString.
  */
private[testkit] object ConstructionRecordJson:
  def quote(value: String): String =
    val escaped = value.flatMap:
      case '"' => "\\\""
      case '\\' => "\\\\"
      case '\n' => "\\n"
      case '\r' => "\\r"
      case '\t' => "\\t"
      case character if character.isControl => f"\\u${character.toInt}%04x"
      case character => character.toString
    s"\"$escaped\""

  def fields(values: Iterable[(String, String)]): String =
    values.map((name, value) => s"${quote(name)}:$value").mkString("{", ",", "}")

  def render(value: Any): String = value match
    case value: String => quote(value)
    case value: Char => fields(Vector("$char" -> quote(value.toString)))
    case value: Boolean => value.toString
    case value: Byte => value.toString
    case value: Short => value.toString
    case value: Int => value.toString
    case value: Long => value.toString
    case value: Float => fields(Vector("$floatBits" -> quote(
        java.lang.Integer.toHexString(java.lang.Float.floatToRawIntBits(value))
      )))
    case value: Double => fields(Vector("$doubleBits" -> quote(
        java.lang.Long.toHexString(java.lang.Double.doubleToRawLongBits(value))
      )))
    case None => fields(Vector("$none" -> "true"))
    case Some(value) => fields(Vector("$some" -> render(value)))
    case values: scala.collection.Map[?, ?] =>
      val entries = values.iterator.map((key, value) => render(key) -> render(value))
        .toVector.sortBy(_._1)
      require(entries.map(_._1).distinct.size == entries.size, "ambiguous serialized map keys")
      fields(Vector("$map" -> entries.map((key, value) => s"[$key,$value]")
        .mkString("[", ",", "]")))
    case values: scala.collection.Set[?] =>
      val entries = values.iterator.map(render).toVector.sorted
      require(entries.distinct.size == entries.size, "ambiguous serialized set members")
      fields(Vector("$set" -> entries.mkString("[", ",", "]")))
    case values: Seq[?] => values.iterator.map(render).mkString("[", ",", "]")
    case values: Array[?] => values.iterator.map(render).mkString("[", ",", "]")
    case value: Product =>
      val names = value.productElementNames.toVector
      val elements = value.productIterator.toVector
      require(
        names.size == elements.size && names.distinct.size == names.size,
        "incomplete product field inventory"
      )
      fields(Vector("$type" -> quote(value.productPrefix)) ++ names.zip(elements).map:
        (name, element) => name -> render(element))
    case other =>
      scala.util.Failure[Nothing](new IllegalArgumentException(
        s"unsupported immutable record value: ${other.getClass.getName}"
      )).get

  def document(value: Any): String = render(value) + "\n"

// These fixed-size public Scala constructions use the accepted explicit/captured profiles.
// They are not a new generation API or a substitute for the retained predecessor fixtures.
final class ParityDeepHierarchy(depth: Int) extends Module:
  val positive: Node[Electrical.type] = inout(Electrical)
  val negative: Node[Electrical.type] = inout(Electrical)
  val child: Option[Instance[ParityDeepHierarchy]] =
    if depth == 0 then None
    else
      val nested = instance(new ParityDeepHierarchy(depth - 1))
      connect(positive, nested(_.positive))
      connect(negative, nested(_.negative))
      Some(nested)

final class ParityWideLeaf(index: Int) extends Module:
  val positive: Node[Electrical.type] = inout(Electrical)
  val negative: Node[Electrical.type] = inout(Electrical)
  val coefficient: Param[Real] = param((index + 1).toDouble.real)

final class ParityWideHierarchy(size: Int) extends Module:
  val positive: Node[Electrical.type] = inout(Electrical)
  val negative: Node[Electrical.type] = inout(Electrical)
  val children: Vector[Instance[ParityWideLeaf]] = Vector.tabulate(size): index =>
    val child = instance(new ParityWideLeaf(index))
    connect(positive, child(_.positive))
    connect(negative, child(_.negative))
    child

final class ParityRepeatedLeaf(gain: Param[Real] = 2.0) extends Module:
  def parameter: Param[Real] = gain
  val positive: Node[Electrical.type] = inout(Electrical)
  val negative: Node[Electrical.type] = inout(Electrical)

final class ParityRepeatedHierarchy(size: Int) extends Module:
  val gain: Param[Real] = param(4.0.real)
  val positive: Node[Electrical.type] = inout(Electrical)
  val negative: Node[Electrical.type] = inout(Electrical)
  val children: Vector[ParityRepeatedLeaf] = Vector.tabulate(size): _ =>
    new ParityRepeatedLeaf(gain = gain)
  children.foreach: child =>
    positive <> child.positive
    negative <> child.negative

final class ParitySharedExpression(size: Int) extends Module:
  val resistance: Param[Real] = param(1.0.kOhm)
  val positive: Node[Electrical.type] = inout(Electrical)
  val negative: Node[Electrical.type] = inout(Electrical)
  analog:
    val shared = (V(positive, negative) + 1.0.V) / resistance
    for _ <- 0 until size do I(positive, negative) <+ shared

final class ParitySymbolicBindings(size: Int) extends Module:
  val gain: Param[Real] = param(4.0.real)
  val positive: Node[Electrical.type] = inout(Electrical)
  val negative: Node[Electrical.type] = inout(Electrical)
  val children: Vector[Instance[ParityRepeatedLeaf]] = Vector.tabulate(size): index =>
    val child = instance(new ParityRepeatedLeaf)
    child.param(_.parameter, gain + (index + 1).toDouble.real)
    connect(positive, child(_.positive))
    connect(negative, child(_.negative))
    child

final class ParityNestedSession(failInner: Boolean, effects: ArrayBuffer[String]) extends Module:
  val before: Param[Real] = param(3.0.real)
  private val inner = Try:
    if failInner then Nodal.emit(new ConstructorDuplicateTop)
    else Nodal.emit(new ConstructorTop)
  require(inner.isFailure == failInner, "unexpected nested session result")
  effects += (if inner.isFailure then "inner-rejected" else "inner-succeeded")
  val after: Param[Real] = param(5.0.real)

private[testkit] final case class ParityCaseInfo(
    id: String,
    family: String,
    size: Int,
    bridge: Boolean,
    target: Boolean,
    rejection: Option[String]
)

private[testkit] final case class ParityProbeCase(
    info: ParityCaseInfo,
    factory: ArrayBuffer[String] => Module,
    expectedEffects: Vector[String] = Vector.empty
)

private[testkit] final case class ParityMetric(
    wallNanos: Long,
    allocatedBytes: Option[Long],
    gcCollections: Option[Long],
    gcMillis: Option[Long]
):
  def remainder(body: ParityMetric): ParityMetric =
    def subtract(left: Option[Long], right: Option[Long]): Option[Long] =
      for a <- left; b <- right yield
        require(a >= b, "negative measured phase counter")
        a - b
    require(wallNanos >= body.wallNanos, "negative measured phase duration")
    ParityMetric(
      wallNanos - body.wallNanos,
      subtract(allocatedBytes, body.allocatedBytes),
      subtract(gcCollections, body.gcCollections),
      subtract(gcMillis, body.gcMillis)
    )

private[testkit] final case class ParitySample(
    kind: String,
    totalInspection: ParityMetric,
    construction: Option[ParityMetric],
    lifecycleValidationSnapshot: Option[ParityMetric],
    fullRecordSerialization: ParityMetric,
    canonicalSerialization: ParityMetric,
    bridge: Option[ParityMetric],
    heapUsedBytes: Long,
    heapPoolPeakBytes: Long
)

private[testkit] final case class ParityProbeReport(
    schema: String,
    caseInfo: ParityCaseInfo,
    factoryCalls: Int,
    samples: Vector[ParitySample],
    javaVersion: String,
    javaVendor: String
)

/** Executed directly by the pinned-JDK Python runner after the ordinary Mill testkit compile. This
  * entry point owns no baseline/candidate judgment and never modifies compiler sources.
  */
object ConstructionParityProbe:
  private val Warmups = 2
  private val WarmSamples = 5
  private val options = EmitOptions(backend = Backend.VerilogA)
  private val threadBean = ManagementFactory.getThreadMXBean match
    case bean: com.sun.management.ThreadMXBean if bean.isThreadAllocatedMemorySupported =>
      if !bean.isThreadAllocatedMemoryEnabled then bean.setThreadAllocatedMemoryEnabled(true)
      Some(bean)
    case _ => None

  private def accepted(
      id: String,
      factory: => Module,
      bridge: Boolean = false,
      target: Boolean = false
  ): ParityProbeCase =
    ParityProbeCase(ParityCaseInfo(id, "accepted", 0, bridge, target, None), _ => factory)

  private def rejected(id: String, code: String, factory: => Module): ParityProbeCase =
    ParityProbeCase(ParityCaseInfo(id, "rejection", 0, false, false, Some(code)), _ => factory)

  private[testkit] val cases: Vector[ParityProbeCase] = Vector(
    accepted("constructor-default", new ConstructorTop, bridge = true),
    accepted("constructor-root-actual", new ConstructorTop(topGain = 6.0), bridge = true),
    accepted("constructor-omitted-equal", new ConstructorOmittedAndEqualTop, bridge = true),
    accepted("constructor-automatic", new ConstructorAutomaticFormTop, bridge = true),
    accepted("constructor-explicit", new ConstructorExplicitFormTop, bridge = true),
    accepted("constructor-handle", new ConstructorHandleTop, bridge = true),
    accepted("domain-automatic", new ConstructorAutomaticDomainTop),
    accepted("domain-explicit", new ConstructorExplicitDomainTop),
    ParityProbeCase(
      ParityCaseInfo("factory-count", "accepted", 0, false, false, None),
      log => new ConstructorFactoryTop(log),
      Vector("right", "left", "factory")
    ),
    accepted("fixed-replication", new ConstructorReplicationTop),
    accepted("symbolic-overrides", new ValidOverrideIntegrationTop, bridge = true),
    accepted("boolean-dimensions", new BooleanDimensionOverrideIntegrationTop(0), bridge = true),
    accepted("interface-domain-layout", new KernelTop),
    accepted("waiver-origin", new ReproducibilityDomainFixture),
    accepted("hierarchy-equation", new HierarchyEquationTop, bridge = true, target = true),
    accepted("hierarchy-event", new HierarchyEventTop, bridge = true, target = true),
    accepted("hierarchy-function", new HierarchyFunctionTop, bridge = true, target = true),
    accepted("hierarchy-repeated", new HierarchyRepeatedTop, bridge = true, target = true),
    accepted("hierarchy-nested", new HierarchyNestedTop, bridge = true, target = true),
    accepted("integral-initial", new Increment35TypedInitialFixture, bridge = true),
    accepted("integral-states", new Increment35MultipleStateFixture, bridge = true),
    accepted("waveform", new WaveformSource, bridge = true, target = true),
    accepted("noise", new AnalogNoiseSource, bridge = true, target = true),
    accepted("transfer", new TransferFilters, bridge = true, target = true),
    accepted("user-function", new FunctionAmplifier, bridge = true, target = true),
    rejected("duplicate-binding", "NODAL-HIERARCHY-030", new ConstructorDuplicateTop),
    rejected("foreign-target", "NODAL-PARAMETER-BINDING-017", new ConstructorForeignTargetTop),
    rejected("sibling-actual", "NODAL-HIERARCHY-031", new ConstructorSiblingActualTop),
    rejected("internal-endpoint", "NODAL-HIERARCHY-040", new ConstructorInternalEndpointTop),
    rejected(
      "detached-endpoint",
      "NODAL-HIERARCHY-038", {
        var detached: Option[Node[Electrical.type]] = None
        val _ = Nodal.emit {
          val previous = new ConstructorZeroTop
          detached = Some(previous.child.pin)
          previous
        }
        new ConstructorDetachedEndpointTop(detached.get)
      }
    ),
    rejected("dynamic-actual", "NODAL-HIERARCHY-032", new DynamicValueOverrideIntegrationTop),
    rejected("stateful-actual", "NODAL-HIERARCHY-037", new StatefulValueOverrideIntegrationTop),
    rejected("width-mismatch", "NODAL-HIERARCHY-034", new WidthMismatchOverrideIntegrationTop),
    rejected("unit-mismatch", "NODAL-HIERARCHY-036", new DimensionMismatchOverrideIntegrationTop),
    rejected(
      "nested-unit-mismatch",
      "NODAL-HIERARCHY-035",
      new NestedZeroMismatchOverrideIntegrationTop(false)
    ),
    rejected(
      "boolean-unit-mismatch",
      "NODAL-HIERARCHY-035",
      new BooleanDimensionOverrideIntegrationTop(2)
    ),
    rejected("double-attachment", "NODAL-HIERARCHY-017", new ConstructorLegacyDoubleAttachTop),
    ParityProbeCase(
      ParityCaseInfo(
        "caught-body-failure",
        "rejection",
        0,
        false,
        false,
        Some("NODAL-CONSTRUCTOR-LIFECYCLE-021")
      ),
      log => new ConstructorCaughtBodyFailureTop(log),
      Vector("constructor-body-failure")
    ),
    ParityProbeCase(
      ParityCaseInfo(
        "caught-argument-failure",
        "rejection",
        0,
        false,
        false,
        Some("NODAL-CONSTRUCTOR-LIFECYCLE-021")
      ),
      log => new ConstructorCaughtArgumentFailureTop(log),
      Vector("first-argument-child", "second-argument-failure", "constructor-argument-failure")
    ),
    ParityProbeCase(
      ParityCaseInfo("nested-session-success", "accepted", 0, false, false, None),
      log => new ParityNestedSession(false, log),
      Vector("inner-succeeded")
    ),
    ParityProbeCase(
      ParityCaseInfo("nested-session-failure", "accepted", 0, false, false, None),
      log => new ParityNestedSession(true, log),
      Vector("inner-rejected")
    )
  ) ++ Vector.tabulate(4)(form =>
    accepted(s"connect-form-$form", new ConnectivityIntegrationTop(form), bridge = true)
  ) ++ Vector(
    ("deep", Vector(4, 24), (size: Int) => new ParityDeepHierarchy(size)),
    ("wide", Vector(8, 128), (size: Int) => new ParityWideHierarchy(size)),
    ("repeated", Vector(8, 256), (size: Int) => new ParityRepeatedHierarchy(size)),
    ("shared-dag", Vector(16, 256), (size: Int) => new ParitySharedExpression(size)),
    ("symbolic", Vector(8, 128), (size: Int) => new ParitySymbolicBindings(size))
  ).flatMap: (family, sizes, factory) =>
    sizes.map: size =>
      ParityProbeCase(
        ParityCaseInfo(s"$family-$size", family, size, true, true, None),
        _ => factory(size)
      )

  private def validateWorkload(snapshot: ConstructionSnapshot, info: ParityCaseInfo): Unit =
    val byPath = snapshot.modules.map(module => module.path -> module).toMap
    require(byPath.size == snapshot.modules.size, "duplicate module identity in workload")
    val root = byPath(snapshot.root)
    if info.family == "deep" then
      require(snapshot.modules.size == info.size + 1, "deep workload module count differs")
      require(
        snapshot.modules.map(_.instances.size).sum == info.size,
        "deep workload edge count differs"
      )
      require(snapshot.modules.forall(_.instances.size <= 1), "deep workload became wide")
      var frontier = Vector(snapshot.root)
      var depth = -1
      while frontier.nonEmpty do
        depth += 1
        require(depth <= info.size, "deep workload is cyclic or unexpectedly deep")
        frontier = frontier.flatMap(path => byPath(path).instances.map(_.childModule))
      require(depth == info.size, "deep workload is smaller than its declared size")
    else if Set("wide", "repeated", "symbolic").contains(info.family) then
      require(
        snapshot.modules.size == info.size + 1 && root.instances.size == info.size,
        "flat hierarchy cardinality differs from the declared workload"
      )
      require(
        root.instances.map(_.childModule).distinct.size == info.size,
        "flat hierarchy lost distinct child instances"
      )
      val children = root.instances.map(instance => byPath(instance.childModule))
      require(children.forall(_.instances.isEmpty), "flat hierarchy acquired nested children")
      val defaults = children.map: child =>
        val parameters = child.declarations.filter(_.kind == "parameter")
        require(parameters.size == 1, "scale leaf parameter declaration count differs")
        parameters.head.attributes.toMap.apply("default")
      if info.family == "wide" then
        require(defaults.distinct.size == info.size, "wide definitions are no longer distinct")
        require(
          root.instances.forall(_.parameterBindings.isEmpty),
          "wide authored-default construction changed into parameter actual overrides"
        )
      else
        require(defaults.distinct.size == 1, "compatible repeated leaf defaults changed")
        require(
          root.instances.forall(_.parameterBindings.size == 1),
          "scale parameter binding count differs"
        )
        if info.family == "repeated" then
          val gain = root.declarations.find(_.name == "gain").get.path
          require(
            root.instances.forall(_.parameterBindings == Vector("gain" -> gain)),
            "repeated hierarchy lost the shared parent parameter reference"
          )
        else
          val expressions = snapshot.parameterExpressions
          require(
            expressions.count(_.operation == "analog_add") == info.size,
            "symbolic workload lost its distinct static expression combinations"
          )
          require(expressions.forall(_.owner == root.path), "symbolic expression ownership changed")
          val paths = expressions.map(_.path).toSet
          require(
            root.instances.forall(instance => paths.contains(instance.parameterBindings.head._2)),
            "symbolic override no longer references a retained expression"
          )
    else if info.family == "shared-dag" then
      require(snapshot.modules.size == 1, "shared DAG unexpectedly introduced hierarchy")
      val contributions = snapshot.analogRegions.flatMap(_.contributions)
      require(contributions.size == info.size, "shared DAG consumer count differs")
      require(
        contributions.map(_.value).distinct.size == 1,
        "shared producer was copied per consumer"
      )
      val sharedPath = contributions.head.value
      require(
        snapshot.analogRegions.flatMap(_.expressions).count(_.path == sharedPath) == 1,
        "shared DAG producer must have one canonical expression record"
      )

    info.id match
      case "hierarchy-equation" => require(snapshot.analogSemantics.equations.nonEmpty)
      case "hierarchy-event" => require(snapshot.analogProcedural.nonEmpty)
      case "hierarchy-function" | "user-function" => require(snapshot.analogFunctions.nonEmpty)
      case "integral-initial" | "integral-states" => require(snapshot.continuousOperators.nonEmpty)
      case "waveform" => require(snapshot.waveformOperators.nonEmpty)
      case "noise" => require(snapshot.noiseOperators.nonEmpty)
      case "transfer" => require(snapshot.transferOperators.nonEmpty)
      case "interface-domain-layout" => require(snapshot.interfaceAbi.nonEmpty)
      case "waiver-origin" => require(snapshot.waivers.nonEmpty)
      case _ => ()

  private def counters(): (Option[Long], Option[Long], Option[Long]) =
    val allocated = threadBean.map(_.getThreadAllocatedBytes(Thread.currentThread().threadId()))
      .filter(_ >= 0)
    val collectors = ManagementFactory.getGarbageCollectorMXBeans.asScala.toVector
    def sum(values: Vector[Long]): Option[Long] =
      if values.isEmpty || values.exists(_ < 0) then None else Some(values.sum)
    (allocated, sum(collectors.map(_.getCollectionCount)), sum(collectors.map(_.getCollectionTime)))

  private def measured[A](operation: => A): (A, ParityMetric) =
    val before = counters()
    val start = System.nanoTime()
    val result = operation
    val elapsed = System.nanoTime() - start
    val after = counters()
    def delta(first: Option[Long], second: Option[Long]): Option[Long] =
      for a <- first; b <- second yield b - a
    result -> ParityMetric(
      elapsed,
      delta(before._1, after._1),
      delta(before._2, after._2),
      delta(before._3, after._3)
    )

  private def writeOrCompare(directory: Path, name: String, value: String): Unit =
    val path = directory.resolve(name)
    if Files.exists(path) then
      require(
        Files.readString(path, StandardCharsets.UTF_8) == value,
        s"nondeterministic artifact: $name"
      )
    else
      val _ = Files.writeString(path, value, StandardCharsets.UTF_8)

  private def inspectOnce(
      selected: ParityProbeCase,
      directory: Path,
      kind: String
  ): ParitySample =
    val effects = ArrayBuffer.empty[String]
    var calls = 0
    var body: Option[ParityMetric] = None
    val (attempt, total) = measured:
      Try(ConstructionKernel.inspect(
        {
          val (module, metric) = measured {
            calls += 1
            selected.factory(effects)
          }
          body = Some(metric)
          module
        },
        options
      ))
    require(calls == 1, "public construction factory was not evaluated exactly once")
    require(
      effects.toVector == selected.expectedEffects,
      s"unexpected effects in ${selected.info.id}"
    )
    writeOrCompare(directory, "effects.json", ConstructionRecordJson.document(effects.toVector))
    val snapshot = selected.info.rejection match
      case None => attempt.get
      case Some(expected) =>
        val exception = attempt.failed.get match
          case failure: ConstructionException => failure
          case other => scala.util.Failure[Nothing](other).get
        require(
          exception.diagnostic.code == expected,
          s"wrong diagnostic: expected $expected, got ${exception.diagnostic.code}"
        )
        writeOrCompare(
          directory,
          "diagnostic.json",
          ConstructionRecordJson.document(exception.diagnostic)
        )
        // A rejected factory may throw before returning, so its elapsed body is unavailable.
        // Recovery is independently required after every failed attempt and serialized in full.
        val recovered = ConstructionKernel.inspect(new ConstructorZeroTop, options)
        require(recovered.modules.size == 2, "failed construction contaminated the fresh session")
        recovered
    validateWorkload(snapshot, selected.info)
    val (full, fullMetric) = measured(ConstructionRecordJson.document(snapshot))
    val (canonical, canonicalMetric) = measured(ReproducibilityContract.canonicalSnapshot(snapshot))
    writeOrCompare(directory, "construction.full.json", full)
    writeOrCompare(directory, "construction.canonical.json", canonical)
    val bridge =
      if selected.info.bridge then
        val (document, metric) =
          measured(ScalaToMlirBridge.fromSnapshot(snapshot, Backend.VerilogA))
        writeOrCompare(directory, "source.mlir", document.text)
        Some(metric)
      else None
    val remainder = body.map(total.remainder)
    val heap = ManagementFactory.getMemoryMXBean.getHeapMemoryUsage.getUsed
    val peaks = ManagementFactory.getMemoryPoolMXBeans.asScala
      .filter(_.getType == java.lang.management.MemoryType.HEAP)
      .map(_.getPeakUsage.getUsed).sum
    ParitySample(kind, total, body, remainder, fullMetric, canonicalMetric, bridge, heap, peaks)

  def main(arguments: Array[String]): Unit =
    if arguments.toVector == Vector("--startup") then
      println(s"F160_STARTUP_PASS ${ManagementFactory.getRuntimeMXBean.getUptime}")
    else if arguments.toVector == Vector("--inventory") then
      println(ConstructionRecordJson.fields(Vector(
        "cases" -> ConstructionRecordJson.render(cases.map(_.info)),
        "warmups" -> Warmups.toString,
        "warm_samples" -> WarmSamples.toString
      )))
    else
      require(arguments.length == 2, "expected case id and new output directory")
      val selected = cases.find(_.info.id == arguments(0)).getOrElse(
        scala.util.Failure[Nothing](new IllegalArgumentException("unknown fixed workload")).get
      )
      val directory = Path.of(arguments(1)).toAbsolutePath.normalize()
      require(!Files.exists(directory), "probe evidence directory must not already exist")
      val _ = Files.createDirectories(directory)
      val kinds = Vector("cold") ++ Vector.fill(Warmups)("warmup") ++
        Vector.fill(WarmSamples)("warm")
      val samples = kinds.map(kind => inspectOnce(selected, directory, kind))
      val report = ParityProbeReport(
        "nodal.increment160.probe.v1",
        selected.info,
        samples.size,
        samples,
        System.getProperty("java.version"),
        System.getProperty("java.vendor")
      )
      val _ = Files.writeString(
        directory.resolve("measurements.json"),
        ConstructionRecordJson.document(report),
        StandardCharsets.UTF_8
      )
      println(s"F160_PROBE_PASS ${selected.info.id} ${samples.size}")
