package nodal.internal.testkit

import scala.compiletime.testing.typeCheckErrors
import scala.util.Try

import nodal.*
import nodal.sim.*
import nodal.internal.integrations.*
import utest.*

object WaveformTests extends TestSuite:
  private val referenceSource = WaveformProvenance("independent analytical reference")
  private val zeroTolerance = Tolerance(0.0.volts, 0.0)

  private def timeWaveform(values: Vector[(Double, Double, Double)]) =
    Waveform.reference(
      WaveformAxis.Time,
      WaveformUnit.Voltage,
      values.map((time, real, imaginary) =>
        WaveformSample(
          time.seconds,
          ComplexQuantity(real.volts, imaginary.volts)
        )
      ),
      referenceSource
    ).toOption.get

  private def evidence = SimulationEvidence(
    None,
    "Simulated",
    "fixture",
    None,
    "a" * 64,
    None,
    None,
    None,
    None,
    Some("b" * 64),
    None
  )

  val tests: Tests = Tests:
    test("axis and sample types exclude incompatible quantities"):
      val wrongTime = typeCheckErrors("""
        import nodal.*
        import nodal.sim.*
        Waveform.reference(WaveformAxis.Time, WaveformUnit.Voltage,
          Vector(WaveformSample(1.0.volts, ComplexQuantity.real(2.0.volts))),
          WaveformProvenance("reference"))
      """)
      val wrongSignal = typeCheckErrors("""
        import nodal.*
        import nodal.sim.*
        Waveform.reference(WaveformAxis.Time, WaveformUnit.Voltage,
          Vector(WaveformSample(1.0.seconds, ComplexQuantity.real(2.0.amps))),
          WaveformProvenance("reference"))
      """)
      val forgedUnit = typeCheckErrors("""
        import nodal.*
        import nodal.sim.*
        new WaveformUnit[VoltageDimension]("A")
      """)
      assert(wrongTime.nonEmpty, wrongSignal.nonEmpty, forgedUnit.nonEmpty)

    test("typed samples and frequency coordinates retain quantities"):
      val waveform = Waveform.reference(
        WaveformAxis.Frequency,
        WaveformUnit.Current,
        Vector(WaveformSample(Frequency.hertz(10), ComplexQuantity(3.0.amps, 4.0.amps))),
        referenceSource
      ).toOption.get
      val sample = waveform.sample(0).toOption.get
      val magnitude = sample.value.project(ComplexComponent.Magnitude).toOption.get
      assert(sample.coordinate.hertz == 10, magnitude.siValue == 5)
      assert(waveform.sample(-1).isLeft, waveform.sample(1).isLeft)
      assert(waveform.at(Frequency.hertz(11)).isLeft)
      assert(waveform.samples.map(_.coordinate.hertz).toVector == Vector(10.0))

    test("time and frequency increase while both DC sweep directions are valid"):
      def time(values: Vector[Double]) = Waveform.reference(
        WaveformAxis.Time,
        WaveformUnit.Voltage,
        values.map(value => WaveformSample(value.seconds, ComplexQuantity.real(1.0.volts))),
        referenceSource
      )
      assert(time(Vector.empty).isLeft)
      assert(time(Vector(-1.0, 0.0)).isLeft)
      assert(time(Vector(0.0, 0.0)).isLeft)
      assert(time(Vector(-0.0, 0.0)).isLeft)
      assert(time(Vector(1.0, 0.0)).isLeft)
      assert(time(Vector(0.0, 2.0, 1.0)).isLeft)
      val voltageSweep = Waveform.reference(
        WaveformAxis.VoltageSweep,
        WaveformUnit.Current,
        Vector(2.0, 0.0, -2.0).map(value =>
          WaveformSample(
            value.volts,
            ComplexQuantity.real((value / 1000).amps)
          )
        ),
        referenceSource
      ).toOption.get
      assert(voltageSweep.at(1.0.volts, WaveformAlignment.Linear).toOption.get.real.siValue ==
        0.001)
      assert(voltageSweep.at(0.0.volts).toOption.get.real.siValue == 0)
      assert(voltageSweep.at(3.0.volts, WaveformAlignment.Linear).isLeft)
      val currentSweep = Waveform.reference(
        WaveformAxis.CurrentSweep,
        WaveformUnit.Voltage,
        Vector(-2.0, 0.0, 2.0).map(value =>
          WaveformSample(
            value.amps,
            ComplexQuantity.real(value.volts)
          )
        ),
        referenceSource
      )
      assert(currentSweep.isRight)

    test("interpolation is explicit inclusive bounded and precedes complex projection"):
      val waveform = timeWaveform(Vector((0.0, 1.0, 0.0), (2.0, -1.0, 0.0)))
      assert(waveform.at(1.0.seconds).isLeft)
      assert(waveform.at(0.0.seconds).toOption.get.real.siValue == 1)
      assert(waveform.at(2.0.seconds).toOption.get.real.siValue == -1)
      assert(waveform.at((-1.0).seconds, WaveformAlignment.Linear).isLeft)
      assert(waveform.at(3.0.seconds, WaveformAlignment.Linear).isLeft)
      val midpoint = waveform.at(1.0.seconds, WaveformAlignment.Linear).toOption.get
      assert(midpoint.project(ComplexComponent.Magnitude).toOption.get.siValue == 0)
      val actual = timeWaveform(Vector((1.0, 0.0, 0.0)))
      assert(actual.compare(
        waveform,
        zeroTolerance,
        ComplexComponent.Magnitude,
        WaveformAlignment.Linear
      ).toOption.get.passed)
      assert(actual.compare(waveform, zeroTolerance).isLeft)

    test("interpolation avoids unnecessary endpoint subtraction overflow"):
      val max = Double.MaxValue
      val waveform = Waveform.reference(
        WaveformAxis.VoltageSweep,
        WaveformUnit.Voltage,
        Vector(
          WaveformSample((-max).volts, ComplexQuantity.real((-max).volts)),
          WaveformSample(max.volts, ComplexQuantity.real(max.volts))
        ),
        referenceSource
      ).toOption.get
      assert(waveform.at(0.0.volts, WaveformAlignment.Linear).toOption.get.real.siValue == 0)

    test("collected and streaming comparison use independent metric expectations"):
      val reference = timeWaveform(Vector((0.0, 0.0, 0.0), (1.0, 0.0, 0.0), (2.0, 0.0, 0.0)))
      val actual = timeWaveform(Vector((0.0, 1.0, 0.0), (1.0, -1.0, 0.0), (2.0, 2.0, 0.0)))
      val tolerance = Tolerance(1.0.volts, 0.0)
      val collected = actual.compare(reference, tolerance).toOption.get
      val reducer = WaveformComparison.reducer(reference, tolerance)
      actual.samples.foreach(sample => assert(reducer.add(sample.coordinate, sample.value).isRight))
      val streamed = reducer.finish().toOption.get
      assert(collected == streamed)
      assert(collected.sampleCount == 3, collected.failureCount == 1)
      assert(collected.firstFailureIndex.contains(2), !collected.passed)
      assert(collected.maximumAbsoluteError == 2)
      assert(math.abs(collected.meanAbsoluteError - 4.0 / 3.0) < 1e-15)
      assert(math.abs(collected.rootMeanSquareError - math.sqrt(2)) < 1e-15)
      assert(actual.compare(actual, zeroTolerance).toOption.get.passed)

    test("exact grids cardinality and malformed late rows cannot report a pass"):
      val reference = timeWaveform(Vector((0.0, 0.0, 0.0), (1.0, 0.0, 0.0)))
      val short = WaveformComparison.reducer(reference, zeroTolerance)
      assert(short.add(0.0.seconds, ComplexQuantity.real(0.0.volts)).isRight)
      assert(short.finish().isLeft)
      assert(short.add(1.0.seconds, ComplexQuantity.real(0.0.volts)).isLeft)
      val malformed = WaveformComparison.reducer(reference, zeroTolerance)
      assert(malformed.addSi(0, 0, 0).isRight)
      assert(malformed.addSi(1, Double.NaN, 0).isLeft)
      assert(malformed.finish().isLeft)
      val missing = timeWaveform(Vector((0.0, 0.0, 0.0)))
      val extra = timeWaveform(Vector((0.0, 0.0, 0.0), (1.0, 0.0, 0.0), (2.0, 0.0, 0.0)))
      assert(missing.compare(reference, zeroTolerance).isLeft)
      assert(extra.compare(reference, zeroTolerance).isLeft)
      val different = timeWaveform(Vector((0.0, 0.0, 0.0), (2.0, 0.0, 0.0)))
      assert(different.compare(reference, zeroTolerance).isLeft)

    test("linear streaming rejects non-monotonic samples and missing domain coverage"):
      val reference = timeWaveform(Vector((0.0, 0.0, 0.0), (2.0, 4.0, 0.0)))
      val reducer = WaveformComparison.reducer(
        reference,
        zeroTolerance,
        alignment = WaveformAlignment.Linear
      )
      assert(reducer.addSi(0, 0, 0).isRight, reducer.addSi(1, 2, 0).isRight)
      assert(reducer.addSi(0.5, 1, 0).isLeft, reducer.finish().isLeft)
      val outside = WaveformComparison.reducer(
        reference,
        zeroTolerance,
        alignment = WaveformAlignment.Linear
      )
      assert(outside.addSi(3, 6, 0).isLeft)

    test("projection difference tolerance and aggregate overflow are explicit"):
      val maximum = Double.MaxValue
      val positive = timeWaveform(Vector((0.0, maximum, 0.0)))
      val negative = timeWaveform(Vector((0.0, -maximum, 0.0)))
      assert(positive.compare(negative, zeroTolerance).isLeft)
      assert(positive.compare(positive, Tolerance(0.0.volts, maximum)).isLeft)
      val projected = ComplexQuantity(maximum.volts, maximum.volts)
      assert(projected.project(ComplexComponent.Magnitude).isLeft)
      val zero = timeWaveform(Vector((0.0, 0.0, 0.0)))
      val finite = positive.compare(zero, zeroTolerance).toOption.get
      assert(finite.maximumAbsoluteError == maximum, finite.meanAbsoluteError == maximum)
      assert(finite.rootMeanSquareError == maximum)

    test("versioned codec is deterministic bit preserving and provenance preserving"):
      val waveform = timeWaveform(Vector((-0.0, -0.0, 1e-300), (2.0, Double.MaxValue, -1e-300)))
      val encoded = waveform.serialize.toOption.get
      val decoded =
        WaveformCodec.decode(encoded, WaveformAxis.Time, WaveformUnit.Voltage).toOption.get
      assert(decoded.serialize.toOption.get == encoded)
      assert(decoded.provenance == referenceSource)
      val first = decoded.sample(0).toOption.get
      assert(java.lang.Double.doubleToRawLongBits(first.coordinate.siValue) ==
        java.lang.Double.doubleToRawLongBits(-0.0))
      assert(java.lang.Double.doubleToRawLongBits(first.value.real.siValue) ==
        java.lang.Double.doubleToRawLongBits(-0.0))
      assert(decoded.sample(1).toOption.get.value.real.siValue == Double.MaxValue)

    test("provenance preserves Unicode and rejects malformed surrogate input"):
      val provenance = WaveformProvenance("independent λ reference 🎛 \"quoted\"")
      val waveform = Waveform.reference(
        WaveformAxis.Time,
        WaveformUnit.Voltage,
        Vector(WaveformSample(0.0.seconds, ComplexQuantity.real(1.0.volts))),
        provenance
      ).toOption.get
      val encoded = waveform.serialize.toOption.get
      val decoded =
        WaveformCodec.decode(encoded, WaveformAxis.Time, WaveformUnit.Voltage).toOption.get
      assert(decoded.provenance == provenance)
      assert(decoded.serialize.toOption.get == encoded)
      assert(Try(WaveformProvenance("unpaired " + 0xd800.toChar)).isFailure)
      assert(Try(WaveformProvenance("unpaired " + 0xdc00.toChar)).isFailure)
      assert(Try(WaveformProvenance("control\ncharacter")).isFailure)

    test("codec rejects incompatible unknown malformed nonfinite and oversized data"):
      val encoded = timeWaveform(Vector((0.0, 1.0, 0.0))).serialize.toOption.get
      assert(WaveformCodec.decode(encoded, WaveformAxis.VoltageSweep, WaveformUnit.Voltage).isLeft)
      assert(WaveformCodec.decode(encoded, WaveformAxis.Time, WaveformUnit.Current).isLeft)
      def decode(text: String) = WaveformCodec.decode(text, WaveformAxis.Time, WaveformUnit.Voltage)
      assert(decode(encoded.replace("nodal-waveform-v1", "nodal-waveform-v2")).isLeft)
      assert(decode(encoded.replace("count=1", "count=100001")).isLeft)
      assert(decode(encoded.replace("count=1", "count=2")).isLeft)
      assert(decode(encoded.replace("0x1.0p0", "NaN")).isLeft)
      assert(decode(encoded.replace("0x1.0p0", "1.0")).isLeft)
      assert(decode(encoded.replace("model=-", "model=bad")).isLeft)
      assert(decode(encoded + "trailing\n").isLeft)
      assert(decode(encoded.dropRight(1)).isLeft)
      assert(decode("x" * (16 * 1024 * 1024 + 1)).isLeft)

    test("raw plot view retains simulator provenance and validates axis shape"):
      val probe = Probe.voltage("output", SimulationNode.Node(1))
      val plot = NgspiceRawPlot(
        "fixture",
        "Transient Analysis",
        false,
        Vector(NgspiceRawVariable(0, "time", "time"), NgspiceRawVariable(1, "v(1)", "voltage")),
        Vector(
          Vector(NgspiceComplex(0, 0), NgspiceComplex(3, 0)),
          Vector(NgspiceComplex(1, 0), NgspiceComplex(4, 0))
        )
      )
      val waveform = Waveform.fromRows(
        WaveformAxis.Time,
        probe,
        evidence,
        plot,
        1,
        SimulationAxisUnit.Seconds
      ).toOption.get
      assert(waveform.provenance.modelSha256.contains("a" * 64))
      assert(waveform.provenance.rawSha256.contains("b" * 64))
      assert(waveform.sample(1).toOption.get.value.real.siValue == 4)
      assert(Waveform.fromRows(
        WaveformAxis.Time,
        probe,
        evidence,
        plot,
        0,
        SimulationAxisUnit.Seconds
      ).isLeft)
      assert(Waveform.fromRows(
        WaveformAxis.Frequency,
        probe,
        evidence,
        plot,
        1,
        SimulationAxisUnit.Seconds
      ).isLeft)
      val missing = plot.copy(rows = Vector(Vector(NgspiceComplex(0, 0))))
      assert(Waveform.fromRows(
        WaveformAxis.Time,
        probe,
        evidence,
        missing,
        1,
        SimulationAxisUnit.Seconds
      ).isLeft)
      val complexAxis = plot.copy(rows = Vector(Vector(NgspiceComplex(0, 1), NgspiceComplex(3, 0))))
      assert(Waveform.fromRows(
        WaveformAxis.Time,
        probe,
        evidence,
        complexAxis,
        1,
        SimulationAxisUnit.Seconds
      ).isLeft)

    test("bounded long trace shares comparison reducer without collecting actual samples"):
      val count = 100000
      val roundoffTolerance = Tolerance(1e-9.volts, 0.0)
      val reference = timeWaveform(Vector((0.0, 0.0, 0.0), ((count - 1).toDouble, 199998.0, 0.0)))
      val reducer = WaveformComparison.reducer(
        reference,
        roundoffTolerance,
        alignment = WaveformAlignment.Linear
      )
      var index = 0
      while index < count do
        assert(reducer.addSi(index.toDouble, 2.0 * index, 0.0).isRight)
        index += 1
      val result = reducer.finish().toOption.get
      assert(result.sampleCount == count, result.passed)
      val excess = WaveformComparison.reducer(
        reference,
        roundoffTolerance,
        alignment = WaveformAlignment.Linear
      )
      var added = 0
      while added < count do
        assert(excess.addSi(added.toDouble, 2.0 * added, 0.0).isRight)
        added += 1
      assert(excess.addSi(count.toDouble, 2.0 * count, 0.0).isLeft)
      assert(excess.finish().isLeft)
