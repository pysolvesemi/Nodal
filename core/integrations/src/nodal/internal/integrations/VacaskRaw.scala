package nodal.internal.integrations

import java.io.InputStream

/** Strict entry point for the pinned Vacask034 ASCII writer. */
private[nodal] object VacaskRaw:
  def parse(
      bytes: Vector[Byte],
      expectedKind: AnalogAnalysisKind,
      maxPoints: Int = 100000,
      maxVariables: Int = 64
  ): Either[String, AnalogRawPlot] =
    SpiceAsciiRaw.parse(SpiceRawDialect.Vacask034, bytes, expectedKind, maxPoints, maxVariables)

  def fold[A](
      input: InputStream,
      expectedKind: AnalogAnalysisKind,
      maxPoints: Int = 100000,
      maxVariables: Int = 64,
      asciiOnly: Boolean = false
  )(
      initialize: AnalogRawSchema => Either[String, A]
  )(
      consume: (A, Int, Vector[AnalogComplex]) => Either[String, A]
  ): Either[String, A] =
    SpiceAsciiRaw.fold(
      SpiceRawDialect.Vacask034,
      input,
      expectedKind,
      maxPoints,
      maxVariables,
      asciiOnly
    )(initialize)(consume)
