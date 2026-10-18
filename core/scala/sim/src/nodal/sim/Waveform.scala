package nodal.sim

import java.nio.ByteBuffer
import java.nio.charset.{CodingErrorAction, StandardCharsets}
import java.util.Base64
import scala.util.control.NonFatal

import nodal.*
import nodal.internal.integrations.*

/** Coordinates retain their existing quantity type; frequency uses the existing Frequency API. */
sealed abstract class WaveformAxis[A] private[sim] (
    val name: String,
    val unit: SimulationAxisUnit,
    private[sim] val mayDescend: Boolean
):
  private[nodal] def toSi(value: A): Double
  private[sim] def fromSi(value: Double): A
  private[sim] def valid(value: Double): Boolean = value.isFinite

object WaveformAxis:
  case object Time extends WaveformAxis[Quantity[TimeDimension]](
        "time",
        SimulationAxisUnit.Seconds,
        false
      ):
    private[nodal] def toSi(value: Quantity[TimeDimension]): Double = value.siValue
    private[sim] def fromSi(value: Double): Quantity[TimeDimension] = new Quantity(value)
    override private[sim] def valid(value: Double): Boolean = value.isFinite && value >= 0

  case object Frequency extends WaveformAxis[nodal.Frequency](
        "frequency",
        SimulationAxisUnit.Hertz,
        false
      ):
    private[nodal] def toSi(value: nodal.Frequency): Double = value.hertz
    private[sim] def fromSi(value: Double): nodal.Frequency = nodal.Frequency.hertz(value)
    override private[sim] def valid(value: Double): Boolean = value.isFinite && value > 0

  case object VoltageSweep extends WaveformAxis[Quantity[VoltageDimension]](
        "voltage-sweep",
        SimulationAxisUnit.Volts,
        true
      ):
    private[nodal] def toSi(value: Quantity[VoltageDimension]): Double = value.siValue
    private[sim] def fromSi(value: Double): Quantity[VoltageDimension] = new Quantity(value)

  case object CurrentSweep extends WaveformAxis[Quantity[CurrentDimension]](
        "current-sweep",
        SimulationAxisUnit.Amperes,
        true
      ):
    private[nodal] def toSi(value: Quantity[CurrentDimension]): Double = value.siValue
    private[sim] def fromSi(value: Double): Quantity[CurrentDimension] = new Quantity(value)

/** Unit witnesses are closed: users cannot assign amperes to a voltage dimension. */
final class WaveformUnit[D] private[sim] (val symbol: String)

object WaveformUnit:
  val Voltage: WaveformUnit[VoltageDimension] = new WaveformUnit("V")
  val Current: WaveformUnit[CurrentDimension] = new WaveformUnit("A")
  private[sim] def fromProbe[D](probe: Probe[D]): WaveformUnit[D] = new WaveformUnit(probe.unit)

final case class ComplexQuantity[D](real: Quantity[D], imaginary: Quantity[D]):
  def project(component: ComplexComponent): Either[SimulationError, Quantity[D]] =
    Waveform.checked("SIM-WAVEFORM"):
      val value = Waveform.project(real.siValue, imaginary.siValue, component)
      new Quantity[D](value)

object ComplexQuantity:
  def real[D](value: Quantity[D]): ComplexQuantity[D] =
    ComplexQuantity(value, new Quantity[D](0.0))

final case class WaveformSample[A, D](coordinate: A, value: ComplexQuantity[D])

/** Descriptive provenance, never an authority to read files or assert artifact authenticity. */
final case class WaveformProvenance(
    source: String,
    modelSha256: Option[String] = None,
    requestSha256: Option[String] = None,
    rawSha256: Option[String] = None
):
  require(
    source.nonEmpty && source.length <= 1024 &&
      StandardCharsets.UTF_8.newEncoder().canEncode(source) &&
      source.getBytes(StandardCharsets.UTF_8).length <= 1024,
    "waveform source must contain between 1 and 1024 valid UTF-8 bytes"
  )
  require(!source.exists(c => Character.isISOControl(c)), "control character in waveform source")
  require(
    Vector(modelSha256, requestSha256, rawSha256).flatten.forall(_.matches("[0-9a-f]{64}")),
    "waveform provenance hashes must be lowercase SHA-256"
  )

enum WaveformAlignment:
  /** Both comparisons and lookup require exactly equal SI coordinates by default. */
  case Exact

  /** Interpolate real and imaginary components, then project; extrapolation is never allowed. */
  case Linear

/** Immutable indexed view. Simulator-backed instances retain existing rows, not copied columns. */
final class Waveform[A, D] private[sim] (
    val axis: WaveformAxis[A],
    val unit: WaveformUnit[D],
    val provenance: WaveformProvenance,
    val sampleCount: Int,
    private[sim] val cell: Int => (Double, Double, Double)
):
  def sample(index: Int): Either[SimulationError, WaveformSample[A, D]] =
    if index < 0 || index >= sampleCount then
      Left(SimulationError("SIM-WAVEFORM", "sample index is out of bounds"))
    else
      Waveform.checked("SIM-WAVEFORM"):
        val (coordinate, real, imaginary) = cell(index)
        WaveformSample(
          axis.fromSi(coordinate),
          ComplexQuantity(
            new Quantity[D](real),
            new Quantity[D](imaginary)
          )
        )

  def samples: Iterator[WaveformSample[A, D]] =
    Iterator.range(0, sampleCount).map(index => sample(index).toOption.get)

  def at(
      coordinate: A,
      alignment: WaveformAlignment = WaveformAlignment.Exact
  ): Either[SimulationError, ComplexQuantity[D]] =
    valueAtSi(axis.toSi(coordinate), alignment).map((real, imaginary) =>
      ComplexQuantity(new Quantity[D](real), new Quantity[D](imaginary))
    )

  def compare(
      reference: Waveform[A, D],
      tolerance: Tolerance[D],
      component: ComplexComponent = ComplexComponent.Real,
      alignment: WaveformAlignment = WaveformAlignment.Exact
  ): Either[SimulationError, WaveformComparisonResult] =
    if axis != reference.axis || unit.symbol != reference.unit.symbol then
      Left(SimulationError("SIM-COMPARE", "waveform axis or unit differs from reference"))
    else
      val reducer = WaveformComparison.reducer(reference, tolerance, component, alignment)
      var index = 0
      var error = Option.empty[SimulationError]
      while index < sampleCount && error.isEmpty do
        val (coordinate, real, imaginary) = cell(index)
        reducer.addSi(coordinate, real, imaginary) match
          case Left(failure) => error = Some(failure)
          case Right(_) => ()
        index += 1
      error match
        case Some(failure) => Left(failure)
        case None => reducer.finish()

  def serialize: Either[SimulationError, String] = WaveformCodec.encode(this)

  private[sim] def valueAtSi(
      coordinate: Double,
      alignment: WaveformAlignment
  ): Either[SimulationError, (Double, Double)] = Waveform.checked("SIM-WAVEFORM"):
    require(axis.valid(coordinate), "invalid waveform coordinate")
    val ascending = sampleCount < 2 || cell(1)._1 > cell(0)._1
    def before(left: Double, right: Double): Boolean =
      if ascending then left < right else left > right
    require(
      !before(coordinate, cell(0)._1) && !before(cell(sampleCount - 1)._1, coordinate),
      "coordinate lies outside waveform domain; extrapolation is unsupported"
    )
    var low = 0
    var high = sampleCount - 1
    while low <= high do
      val middle = low + (high - low) / 2
      val x = cell(middle)._1
      if before(x, coordinate) then low = middle + 1
      else if before(coordinate, x) then high = middle - 1
      else
        low = middle
        high = middle - 1
    val (rightX, rightReal, rightImaginary) = cell(low)
    if rightX == coordinate then (rightReal, rightImaginary)
    else
      require(alignment == WaveformAlignment.Linear, "coordinate is absent from waveform")
      val (leftX, leftReal, leftImaginary) = cell(low - 1)
      val width = rightX - leftX
      val fraction =
        if width.isFinite then (coordinate - leftX) / width
        else (coordinate / 2 - leftX / 2) / (rightX / 2 - leftX / 2)
      require(
        fraction.isFinite && fraction >= 0 && fraction <= 1,
        "interpolation coordinate arithmetic overflow"
      )
      def interpolate(left: Double, right: Double): Double =
        val value = left * (1 - fraction) + right * fraction
        require(value.isFinite, "interpolated sample overflow")
        value
      (interpolate(leftReal, rightReal), interpolate(leftImaginary, rightImaginary))

object Waveform:
  private[sim] val MaxPoints = 100000

  def reference[A, D](
      axis: WaveformAxis[A],
      unit: WaveformUnit[D],
      samples: Vector[WaveformSample[A, D]],
      provenance: WaveformProvenance
  ): Either[SimulationError, Waveform[A, D]] =
    create(
      axis,
      unit,
      provenance,
      samples.size,
      index =>
        val sample = samples(index)
        (axis.toSi(sample.coordinate), sample.value.real.siValue, sample.value.imaginary.siValue)
    )

  private[nodal] def fromRows[A, D](
      axis: WaveformAxis[A],
      probe: Probe[D],
      evidence: SimulationEvidence,
      plot: NgspiceRawPlot,
      column: Int,
      axisUnit: SimulationAxisUnit
  ): Either[SimulationError, Waveform[A, D]] =
    checked("SIM-WAVEFORM"):
      require(axis.unit == axisUnit, "requested waveform axis differs from simulation axis")
      require(plot.variables.nonEmpty && plot.variables.size <= 64, "invalid waveform variables")
      require(column > 0 && column < plot.variables.size, "probe column is absent")
      require(plot.rows.nonEmpty && plot.rows.size <= MaxPoints, "invalid waveform sample count")
      require(
        plot.rows.size.toLong * plot.variables.size <= 1000000,
        "waveform cell limit exceeded"
      )
      require(
        plot.rows.forall(row =>
          row.size == plot.variables.size &&
            row.head.imaginary.isFinite &&
            (axisUnit == SimulationAxisUnit.Hertz || row.head.imaginary == 0)
        ),
        "waveform rows have missing variables or an invalid axis"
      )
      WaveformProvenance(
        "simulation",
        Some(evidence.modelSha256),
        evidence.requestSha256,
        evidence.rawSha256
      )
    .flatMap(provenance =>
      create(
        axis,
        WaveformUnit.fromProbe(probe),
        provenance,
        plot.rows.size,
        index =>
          val row = plot.rows(index)
          (row.head.real, row(column).real, row(column).imaginary)
      )
    )

  private[sim] def create[A, D](
      axis: WaveformAxis[A],
      unit: WaveformUnit[D],
      provenance: WaveformProvenance,
      count: Int,
      cell: Int => (Double, Double, Double)
  ): Either[SimulationError, Waveform[A, D]] = checked("SIM-WAVEFORM"):
    require(count > 0 && count <= MaxPoints, "waveform requires between 1 and 100000 samples")
    val ordering = new CoordinateOrder(axis)
    var index = 0
    while index < count do
      val (coordinate, real, imaginary) = cell(index)
      ordering.add(coordinate)
      require(real.isFinite && imaginary.isFinite, "nonfinite waveform sample")
      index += 1
    new Waveform(axis, unit, provenance, count, cell)

  private[sim] def project(real: Double, imaginary: Double, component: ComplexComponent): Double =
    val value = component match
      case ComplexComponent.Real => real
      case ComplexComponent.Imaginary => imaginary
      case ComplexComponent.Magnitude => math.hypot(real, imaginary)
    require(value.isFinite, "complex projection overflow")
    value

  private[sim] def checked[A](code: String)(body: => A): Either[SimulationError, A] =
    try Right(body)
    catch
      case NonFatal(error) => Left(SimulationError(
          code,
          Option(error.getMessage).getOrElse(error.getClass.getName)
        ))

  private[sim] final class CoordinateOrder[A](axis: WaveformAxis[A]):
    private var previous = Option.empty[Double]
    private var direction = 0
    def add(coordinate: Double): Unit =
      require(axis.valid(coordinate), "invalid or nonfinite waveform coordinate")
      previous.foreach: prior =>
        val nextDirection = java.lang.Double.compare(coordinate, prior)
        require(coordinate != prior, "duplicate waveform coordinate")
        require(axis.mayDescend || nextDirection > 0, "time/frequency axis must increase")
        require(direction == 0 || direction == nextDirection, "non-monotonic waveform axis")
        direction = nextDirection
      previous = Some(coordinate)

final case class WaveformComparisonResult(
    sampleCount: Int,
    failureCount: Int,
    firstFailureIndex: Option[Int],
    maximumAbsoluteError: Double,
    meanAbsoluteError: Double,
    rootMeanSquareError: Double
):
  def passed: Boolean = sampleCount > 0 && failureCount == 0

object WaveformComparison:
  def reducer[A, D](
      reference: Waveform[A, D],
      tolerance: Tolerance[D],
      component: ComplexComponent = ComplexComponent.Real,
      alignment: WaveformAlignment = WaveformAlignment.Exact
  ): WaveformComparisonReducer[A, D] =
    new WaveformComparisonReducer(reference, tolerance, component, alignment)

/** One bounded reducer for iterator, collected, and non-collecting simulator consumers. Exact
  * requires equal grids and lengths. Linear compares every actual sample against the reference's
  * closed domain; the actual grid may be a subset. Neither mode extrapolates. A failed add
  * permanently poisons the reducer, so a late malformed row cannot yield a pass.
  */
final class WaveformComparisonReducer[A, D] private[sim] (
    reference: Waveform[A, D],
    tolerance: Tolerance[D],
    component: ComplexComponent,
    alignment: WaveformAlignment
):
  private val ordering = new Waveform.CoordinateOrder(reference.axis)
  private var count = 0
  private var failures = 0
  private var firstFailure = Option.empty[Int]
  private var maximum = 0.0
  private var mean = 0.0
  private var squareScale = 0.0
  private var scaledSquares = 0.0
  private var failure = Option.empty[SimulationError]
  private var finished = false

  def add(coordinate: A, value: ComplexQuantity[D]): Either[SimulationError, Unit] =
    addSi(reference.axis.toSi(coordinate), value.real.siValue, value.imaginary.siValue)

  private[nodal] def addSi(
      coordinate: Double,
      real: Double,
      imaginary: Double
  ): Either[SimulationError, Unit] =
    val result = failure match
      case Some(error) => Left(error)
      case None => Waveform.checked("SIM-COMPARE"):
          require(!finished, "comparison is already finished")
          require(count < Waveform.MaxPoints, "comparison sample limit exceeded")
          ordering.add(coordinate)
          require(real.isFinite && imaginary.isFinite, "nonfinite actual sample")
          val expected = alignment match
            case WaveformAlignment.Exact =>
              require(count < reference.sampleCount, "actual waveform has extra samples")
              val (x, r, i) = reference.cell(count)
              require(coordinate == x, "actual and reference coordinates differ")
              (r, i)
            case WaveformAlignment.Linear =>
              val interpolated = reference.valueAtSi(coordinate, alignment)
              require(interpolated.isRight, interpolated.left.toOption.map(_.message).getOrElse(""))
              interpolated.toOption.get
          val actual = Waveform.project(real, imaginary, component)
          val target = Waveform.project(expected._1, expected._2, component)
          val difference = math.abs(actual - target)
          val allowance = tolerance.absolute.siValue + tolerance.relative * math.abs(target)
          require(difference.isFinite && allowance.isFinite, "comparison arithmetic overflow")
          if difference > allowance then
            failures += 1
            if firstFailure.isEmpty then firstFailure = Some(count)
          count += 1
          maximum = math.max(maximum, difference)
          mean += (difference - mean) / count
          if difference > 0 then
            if squareScale < difference then
              val ratio = squareScale / difference
              scaledSquares = 1 + scaledSquares * ratio * ratio
              squareScale = difference
            else
              val ratio = difference / squareScale
              scaledSquares += ratio * ratio
          require(mean.isFinite && scaledSquares.isFinite, "comparison aggregate overflow")
    result.left.foreach(error => failure = Some(error))
    result

  def finish(): Either[SimulationError, WaveformComparisonResult] =
    finished = true
    val result = failure match
      case Some(error) => Left(error)
      case None => Waveform.checked("SIM-COMPARE"):
          require(count > 0, "comparison has no samples")
          require(
            alignment != WaveformAlignment.Exact || count == reference.sampleCount,
            "actual waveform has missing samples"
          )
          val rms = squareScale * math.sqrt(scaledSquares / count)
          require(rms.isFinite, "comparison aggregate overflow")
          WaveformComparisonResult(count, failures, firstFailure, maximum, mean, rms)
    result.left.foreach(error => failure = Some(error))
    result

/** Versioned, bounded text interchange. Hexadecimal doubles round-trip all finite IEEE bits. Decode
  * requires explicit expected axis and unit witnesses; serialized metadata cannot forge their Scala
  * types. Source provenance is descriptive and is not upgraded to trusted evidence.
  */
object WaveformCodec:
  private val MaxCharacters = 16 * 1024 * 1024
  private val Magic = "nodal-waveform-v1"

  def encode[A, D](waveform: Waveform[A, D]): Either[SimulationError, String] =
    Waveform.checked("SIM-WAVEFORM-CODEC"):
      val source = Base64.getEncoder.encodeToString(
        waveform.provenance.source.getBytes(StandardCharsets.UTF_8)
      )
      val builder = new StringBuilder
      builder.append(Magic).append('\n')
      builder.append("axis=").append(waveform.axis.name).append('\n')
      builder.append("unit=").append(waveform.unit.symbol).append('\n')
      builder.append("source=").append(source).append('\n')
      builder.append("model=").append(waveform.provenance.modelSha256.getOrElse("-")).append('\n')
      builder.append(
        "request="
      ).append(waveform.provenance.requestSha256.getOrElse("-")).append('\n')
      builder.append("raw=").append(waveform.provenance.rawSha256.getOrElse("-")).append('\n')
      builder.append("count=").append(waveform.sampleCount).append('\n')
      var index = 0
      while index < waveform.sampleCount do
        val (coordinate, real, imaginary) = waveform.cell(index)
        builder.append(java.lang.Double.toHexString(coordinate)).append('\t')
        builder.append(java.lang.Double.toHexString(real)).append('\t')
        builder.append(java.lang.Double.toHexString(imaginary)).append('\n')
        require(builder.length <= MaxCharacters, "serialized waveform exceeds size limit")
        index += 1
      builder.toString

  def decode[A, D](
      text: String,
      axis: WaveformAxis[A],
      unit: WaveformUnit[D]
  ): Either[SimulationError, Waveform[A, D]] =
    Waveform.checked("SIM-WAVEFORM-CODEC"):
      require(text.nonEmpty && text.length <= MaxCharacters, "invalid serialized waveform size")
      require(text.endsWith("\n") && !text.contains('\r'), "invalid waveform line endings")
      val lines = text.linesIterator
      def next(): String =
        require(lines.hasNext, "truncated serialized waveform")
        val line = lines.next()
        require(line.length <= 2048, "serialized waveform line exceeds limit")
        line
      def field(name: String): String =
        val line = next()
        require(line.startsWith(name + "="), s"missing waveform $name field")
        line.substring(name.length + 1)
      require(next() == Magic, "unsupported waveform format version")
      require(field("axis") == axis.name, "serialized waveform axis is incompatible")
      require(field("unit") == unit.symbol, "serialized waveform unit is incompatible")
      val sourceBytes = Base64.getDecoder.decode(field("source"))
      val source = StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(sourceBytes)).toString
      def hash(name: String): Option[String] =
        val value = field(name)
        Option.when(value != "-")(value)
      val provenance = WaveformProvenance(source, hash("model"), hash("request"), hash("raw"))
      val countText = field("count")
      val count = countText.toInt
      require(
        countText == count.toString && count > 0 && count <= Waveform.MaxPoints,
        "invalid serialized sample count"
      )
      val cells = Vector.newBuilder[(Double, Double, Double)]
      var index = 0
      while index < count do
        val values = next().split("\t", -1)
        require(values.length == 3, "serialized waveform row must have three values")
        def number(value: String): Double =
          val result = java.lang.Double.parseDouble(value)
          require(
            result.isFinite && java.lang.Double.toHexString(result) == value,
            "serialized waveform requires canonical finite hexadecimal doubles"
          )
          result
        cells += ((number(values(0)), number(values(1)), number(values(2))))
        index += 1
      require(!lines.hasNext, "trailing serialized waveform data")
      (provenance, cells.result())
    .flatMap((provenance, cells) =>
      Waveform.create(axis, unit, provenance, cells.size, cells.apply)
    )
