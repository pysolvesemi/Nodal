package nodal.increment45fixture

import nodal.*

/** Authored pure arithmetic remains distinct until the native canonicalization pass. */
final class AnalogCanonicalizationSource extends Module:
  val positive = inout(Electrical)
  val negative = inout(Electrical)
  val arithmetic = inout(Electrical)
  val observed = inout(Electrical)
  val gain = param(2.0.real)
  val offset = param(3.0.real)

  val identity = AnalogFunction("identitySignal", Real): f =>
    f.input("signal", Real)

  val preserve = AnalogFunction("preserveSignal", Real): f =>
    val signal = f.input("signal", Real)
    val _ = f.local("unusedLiteral", 9.0.real)
    val _ = f.local("unusedDynamic", signal + 1.0.real)
    val _ = f.local("unusedCall", identity(signal))
    signal

  analog:
    val first = gain + offset
    val second = gain + offset
    V(positive, negative) <+ first * 1.0.V
    V(positive, negative) <+ second * 1.0.V
    val twiceNegated = -(-gain)
    val constant = 2.0.real * 3.0.real
    V(arithmetic, negative) <+ (twiceNegated + constant) * 1.0.V
    val firstTime = AnalysisContext.time / 1.0.s
    val secondTime = AnalysisContext.time / 1.0.s
    val firstCall = preserve(gain) + offset
    val secondCall = preserve(gain) + offset
    V(observed, negative) <+ (firstTime + secondTime + firstCall + secondCall) * 1.0.V

/** F32 authored inventory only; target equation lowering is outside this witness. */
final class AnalogCanonicalizationEquationInventory extends Module:
  val positive = inout(Electrical)
  val negative = inout(Electrical)
  val gain = param(2.0.real)

  equations:
    equation(
      V(positive, negative),
      gain * 1.0.V,
      EquationOptions(
        id = Some(EquationId("authored-balance")),
        analyses = AnalysisApplicability.only(AnalysisKind.Dc, AnalysisKind.Transient),
        continuity = ContinuityClass.C1
      )
    )

  initialEquations:
    initialEquation(
      V(positive, negative),
      0.0.V,
      InitialEquationOptions(id = Some(EquationId("authored-initial")))
    )

  contributions:
    contribution(
      I(positive, negative),
      1.0.A,
      ContributionOptions(id = Some(ContributionId("equal-first")))
    )
    contribution(
      I(positive, negative),
      1.0.A,
      ContributionOptions(id = Some(ContributionId("equal-second")))
    )

object Increment45ConstructionCheck:
  def main(arguments: Array[String]): Unit =
    require(arguments.isEmpty, "no arguments expected")
    val _ = Nodal.emit(new AnalogCanonicalizationSource)
    val _ = Nodal.emit(new AnalogCanonicalizationEquationInventory)
    println("Increment 45 public canonicalization construction passed")
