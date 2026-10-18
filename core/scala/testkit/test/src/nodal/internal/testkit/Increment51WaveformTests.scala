package nodal.internal.testkit

import nodal.*
import nodal.internal.integrations.*
import nodal.sim.*
import utest.*

/** Collected native-result adapter checks complement the typed-model and raw-parser suites. */
object Increment51WaveformTests extends TestSuite:
  private val Evidence = SimulationEvidence(
    None,
    "Simulated",
    "fixture",
    Some("a" * 64),
    "b" * 64,
    Some("c" * 64),
    Some("d" * 64),
    Some("fixture"),
    None,
    Some("e" * 64),
    None
  )
  private val VoltageProbe = Probe.voltage("output", SimulationNode.Node(0))

  private def get[A](value: Either[SimulationError, A]): A =
    value.fold(
      error =>
        scala.util.Failure[A](new IllegalArgumentException(s"${error.code}: ${error.message}")).get,
      identity
    )

  private def plot(rows: Vector[Vector[NgspiceComplex]], complex: Boolean = false): NgspiceRawPlot =
    NgspiceRawPlot(
      "fixture",
      if complex then "AC Analysis" else "Transient Analysis",
      complex,
      Vector(
        NgspiceRawVariable(0, "axis", "writer-metadata"),
        NgspiceRawVariable(1, "v(n0)", "voltage")
      ),
      rows
    )

  val tests: Tests = Tests:
    test("collected adapter preserves physical coordinates values and artifact provenance"):
      val raw = plot(Vector(
        Vector(NgspiceComplex(0.0, 0.0), NgspiceComplex(2.0, 0.0)),
        Vector(NgspiceComplex(0.5, 0.0), NgspiceComplex(4.0, 0.0)),
        Vector(NgspiceComplex(1.0, 0.0), NgspiceComplex(6.0, 0.0))
      ))
      val waveform = get(Waveform.fromRows(
        WaveformAxis.Time,
        VoltageProbe,
        Evidence,
        raw,
        1,
        SimulationAxisUnit.Seconds
      ))
      assert(waveform.sampleCount == 3, waveform.unit.symbol == "V")
      assert(waveform.samples.map(_.coordinate.siValue).toVector == Vector(0.0, 0.5, 1.0))
      assert(waveform.samples.map(_.value.real.siValue).toVector == Vector(2.0, 4.0, 6.0))
      assert(waveform.provenance == WaveformProvenance(
        "simulation",
        Some("b" * 64),
        Some("a" * 64),
        Some("e" * 64)
      ))
      val serialized = get(waveform.serialize)
      val decoded = get(WaveformCodec.decode(serialized, WaveformAxis.Time, WaveformUnit.Voltage))
      assert(get(decoded.serialize) == serialized)
      assert(get(decoded.compare(waveform, Tolerance(0.0.volts, 0.0))).passed)
      assert(get(waveform.at(0.25.seconds, WaveformAlignment.Linear)).real.siValue == 3.0)

    test("finite imaginary frequency writer metadata is not a second physical axis"):
      val raw = plot(
        Vector(
          Vector(NgspiceComplex(10.0, 2.0), NgspiceComplex(3.0, 4.0)),
          Vector(NgspiceComplex(20.0, -7.0), NgspiceComplex(6.0, 8.0))
        ),
        complex = true
      )
      val waveform = get(Waveform.fromRows(
        WaveformAxis.Frequency,
        VoltageProbe,
        Evidence,
        raw,
        1,
        SimulationAxisUnit.Hertz
      ))
      assert(waveform.samples.map(_.coordinate.hertz).toVector == Vector(10.0, 20.0))
      assert(get(get(waveform.sample(0)).value.project(ComplexComponent.Magnitude)).siValue == 5.0)
      val middle = get(waveform.at(Frequency.hertz(15), WaveformAlignment.Linear))
      assert(middle.real.siValue == 4.5, middle.imaginary.siValue == 6.0)
      val nonfinite = raw.copy(rows =
        raw.rows.updated(
          1,
          raw.rows(1).updated(0, NgspiceComplex(20.0, Double.NaN))
        )
      )
      assert(Waveform.fromRows(
        WaveformAxis.Frequency,
        VoltageProbe,
        Evidence,
        nonfinite,
        1,
        SimulationAxisUnit.Hertz
      ).isLeft)

    test("verified analysis descriptor and selected column govern the collected view"):
      val raw = plot(Vector(Vector(NgspiceComplex(1.0, 0.0), NgspiceComplex(2.0, 0.0))))
      assert(Waveform.fromRows(
        WaveformAxis.Time,
        VoltageProbe,
        Evidence,
        raw,
        1,
        SimulationAxisUnit.Volts
      ).isLeft)
      assert(Waveform.fromRows(
        WaveformAxis.Time,
        VoltageProbe,
        Evidence,
        raw,
        0,
        SimulationAxisUnit.Seconds
      ).isLeft)
      assert(Waveform.fromRows(
        WaveformAxis.Time,
        VoltageProbe,
        Evidence,
        raw,
        2,
        SimulationAxisUnit.Seconds
      ).isLeft)
      val secondProbe = Probe.current(
        "source-current",
        ElectricalDrive.voltage(
          0,
          SimulationNode.Node(0),
          SimulationNode.Ground,
          1.0.volts
        )
      )
      val multiple = raw.copy(
        variables = raw.variables :+ NgspiceRawVariable(2, "i(v0)", "current"),
        rows = Vector(raw.rows.head :+ NgspiceComplex(-0.001, 0.0))
      )
      val selected = get(Waveform.fromRows(
        WaveformAxis.VoltageSweep,
        secondProbe,
        Evidence,
        multiple,
        2,
        SimulationAxisUnit.Volts
      ))
      assert(selected.unit.symbol == "A", get(selected.sample(0)).value.real.siValue == -0.001)

    test("invalid collected rows cannot be promoted to typed waveform results"):
      val row = Vector(NgspiceComplex(1.0, 0.0), NgspiceComplex(2.0, 0.0))
      val invalid = Vector(
        plot(Vector.empty),
        plot(Vector(row.take(1))),
        plot(Vector(row, row)),
        plot(Vector(row.updated(0, NgspiceComplex(1.0, 1.0)))),
        plot(Vector(row.updated(1, NgspiceComplex(Double.PositiveInfinity, 0.0))))
      )
      invalid.foreach: raw =>
        assert(Waveform.fromRows(
          WaveformAxis.Time,
          VoltageProbe,
          Evidence,
          raw,
          1,
          SimulationAxisUnit.Seconds
        ).isLeft)
