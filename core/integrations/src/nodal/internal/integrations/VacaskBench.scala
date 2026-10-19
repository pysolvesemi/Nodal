package nodal.internal.integrations

import java.util.Locale

/** Finite VACASK 0.3.4 dialect. The admitted OSDI is staged as model.osdi by the runner. No source
  * compilation, caller paths, commands or model-text rewriting are permitted.
  */
private[nodal] object VacaskBench:
  import AnalogCircuitValidation.{check, number}

  def generate(bench: AnalogCircuitBench): Either[String, AnalogGeneratedBench] =
    for
      _ <- AnalogCircuitValidation.validate(bench)
      grid = AnalogCircuitValidation.axis(bench.analysis)
      count <- AnalogCircuitValidation.nominalPointCount(grid)
      _ <- validateProfile(bench, count)
    yield
      val sources = bench.sources.map(source => source.id -> source).toMap
      val isAc = bench.analysis.kind == AnalogAnalysisKind.Ac
      val probes = bench.probes.map:
        case AnalogProbe.Voltage(node) => nodeName(node)
        case AnalogProbe.Current(id) => s"${sensorName(sources(id))}:flow(br)"
      val saves = bench.probes.map:
        case AnalogProbe.Voltage(node) => s"${if isAc then "dv" else "v"}(${nodeName(node)})"
        case AnalogProbe.Current(id) => s"${if isAc then "di" else "i"}(${sensorName(sources(id))})"
      val scale = bench.analysis.kind match
        case AnalogAnalysisKind.Dc => "axis"
        case AnalogAnalysisKind.Ac => "frequency"
        case AnalogAnalysisKind.Transient => "time"
      val parameters = bench.model.parameters.sortBy(_._1.toLowerCase(Locale.ROOT))
        .map((key, value) => s"${key.toLowerCase(Locale.ROOT)}=${number(value)}").mkString(" ")
      val options = bench.options
      val lines = Vector(
        "Nodal finite VACASK bench",
        "load \"model.osdi\"",
        s"model dut ${bench.model.moduleName.toLowerCase(Locale.ROOT)}" +
          (if parameters.isEmpty then "" else s" $parameters"),
        "model vsrc vsource",
        "model isrc isource",
        s"dut_instance (${bench.model.terminals.map(nodeName).mkString(" ")}) dut"
      ) ++ bench.sources.sortBy(_.id.value).flatMap(renderSource(_, bench.analysis.kind)) ++ Vector(
        "control",
        "abort always",
        "options rawfile=\"ascii\" strictsave=2 strictoutput=2 tolmode=\"spice\"" +
          s" reltol=${number(options.reltol)} abstol=${number(options.abstol)}" +
          s" vntol=${number(options.vntol)} temp=${number(options.temp)} tnom=${number(options.tnom)}",
        "save " + saves.mkString(" "),
        renderAnalysis(bench, sources, count),
        "endc"
      )
      AnalogGeneratedBench(
        lines.mkString("", "\n", "\n"),
        bench.analysis.kind,
        count,
        (scale +: probes).map(_ -> "notype"),
        grid
      )

  private def validateProfile(bench: AnalogCircuitBench, count: Int): Either[String, Unit] =
    for
      _ <- check(
        bench.nodes.size + bench.sources.count(_.kind == AnalogSourceKind.Current) <= 63,
        "VACASK declared and current-sensor nodes together exceed 63"
      )
      _ <- bench.analysis match
        case AnalogCircuitAnalysis.DcSweep(_, start, stop, step) =>
          check(
            (start + step).isFinite && start + step != start && stop - step != stop &&
              (count <= 1 || Math.fma((count - 1).toDouble, step, start).isFinite),
            "VACASK DC grid is below floating-point resolution"
          )
        case AnalogCircuitAnalysis.AcLinearSweep(points, start, stop) =>
          val step = (stop - start) / math.max(1, points - 1)
          check(
            points == 1 ||
              (step.isFinite && step > 0 && start + step > start && stop - step < stop),
            "VACASK AC grid is below floating-point resolution"
          )
        case AnalogCircuitAnalysis.AcDecadeSweep(points, start, stop) =>
          val ratio = stop / start
          val decades = Math.log10(ratio)
          val factor = Math.pow(10, 1.0 / points)
          check(
            ratio.isFinite && decades >= 1 && decades == Math.rint(decades) &&
              (stop * factor).isFinite && start * factor > start,
            "VACASK AC decade profile requires representable integer-decade spans"
          )
        case AnalogCircuitAnalysis.Transient(_, _, uic) =>
          bench.sources.foldLeft[Either[String, Unit]](Right(())): (state, source) =>
            state.flatMap: _ =>
              source.transient match
                case Some(AnalogTransientWaveform.Pulse(
                      initial,
                      _,
                      _,
                      rise,
                      fall,
                      width,
                      period
                    )) =>
                  for
                    _ <- check(
                      period > rise + width + fall,
                      "VACASK pulse period must strictly exceed rise + width + fall"
                    )
                    _ <- check(
                      uic || source.dc == initial,
                      "VACASK OP transient requires DC bias equal to the waveform initial value"
                    )
                  yield ()
                case Some(AnalogTransientWaveform.Pwl(points)) =>
                  check(
                    uic || source.dc == points.head._2,
                    "VACASK OP transient requires DC bias equal to the waveform initial value"
                  )
                case None => Right(())
    yield ()

  private def nodeName(node: AnalogNode): String = node match
    case AnalogNode.Ground => "0"
    case AnalogNode.Node(id) => s"n$id"

  private def sourceName(source: AnalogSource): String =
    s"${if source.kind == AnalogSourceKind.Voltage then "v" else "i"}s${source.id.value}"

  private def sensorName(source: AnalogSource): String =
    if source.kind == AnalogSourceKind.Voltage then sourceName(source)
    else s"vsense${source.id.value}"

  private def renderSource(source: AnalogSource, kind: AnalogAnalysisKind): Vector[String] =
    val current = source.kind == AnalogSourceKind.Current
    val negative = if current then s"sense${source.id.value}" else nodeName(source.negative)
    val ac = source.ac.fold("")(value =>
      s" mag=${number(value.magnitude)} phase=${number(value.phaseDegrees)}"
    )
    // VACASK uses the waveform value at t=0 for operating point. Other analyses use dc only.
    val waveform = if kind != AnalogAnalysisKind.Transient then s"dc=${number(source.dc)}"
    else
      source.transient match
        case None => s"dc=${number(source.dc)}"
        case Some(AnalogTransientWaveform.Pulse(
              initial,
              pulsed,
              delay,
              rise,
              fall,
              width,
              period
            )) =>
          s"type=\"pulse\" val0=${number(initial)} val1=${number(pulsed)} delay=${number(delay)}" +
            s" rise=${number(rise)} fall=${number(fall)} width=${number(width)} period=${number(period)}"
        case Some(AnalogTransientWaveform.Pwl(points)) =>
          "type=\"pwl\" break=\"all\" wave=[\n" + points.map((time, value) =>
            s"${number(time)}, ${number(value)}"
          ).mkString(",\n") + "\n]"
    Vector(s"${sourceName(source)} (${nodeName(source.positive)} $negative) ${
        if current then "isrc" else "vsrc"
      } $waveform$ac") ++
      Option.when(current)(
        s"${sensorName(source)} ($negative ${nodeName(source.negative)}) vsrc dc=0"
      ).toVector

  private def renderAnalysis(
      bench: AnalogCircuitBench,
      sources: Map[AnalogSourceId, AnalogSource],
      count: Int
  ): String = bench.analysis match
    case AnalogCircuitAnalysis.DcSweep(id, start, _, step) =>
      // Native stepped sweeps can omit an endpoint through repeated floating addition.
      // Explicit bounded values retain the neutral grid, using the same FMA as validation.
      val values = Vector.tabulate(count)(index => number(Math.fma(index.toDouble, step, start)))
      s"sweep axis instance=\"${sourceName(sources(id))}\" parameter=\"dc\" values=[\n" +
        values.grouped(8).map(_.mkString(", ")).mkString(",\n") + "\n]\nanalysis result op"
    case AnalogCircuitAnalysis.AcLinearSweep(1, start, _) =>
      s"analysis result ac values=[${number(start)}] writeop=0"
    case AnalogCircuitAnalysis.AcLinearSweep(points, start, stop) =>
      s"analysis result ac from=${number(start)} to=${number(stop)} mode=\"lin\" points=${points - 1} writeop=0"
    case AnalogCircuitAnalysis.AcDecadeSweep(points, start, stop) =>
      s"analysis result ac from=${number(start)} to=${number(stop)} mode=\"dec\" points=$points writeop=0"
    case AnalogCircuitAnalysis.Transient(step, stop, uic) =>
      val ic = if bench.initialConditions.isEmpty then ""
      else
        " ic={" + bench.initialConditions.sortBy(condition => nodeName(condition.node))
          .map(condition =>
            s"\"${nodeName(condition.node)}\", ${number(condition.volts)}"
          ).mkString(", ") + "}"
      s"analysis result tran stop=${number(stop)} step=${number(step)} maxstep=${number(step)}" +
        s" start=0 icmode=\"${if uic then "uic" else "op"}\"$ic"
