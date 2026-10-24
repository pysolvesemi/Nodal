package nodal.internal.testkit
import nodal.*
final class FinalNameForeignRoot extends Module:
  val signal = in(SInt(2))
final class FinalNameGuardRoot extends Module:
  val input = in(Vec(SInt(2), 2))
  val captured = in(SInt(2))
  val mapped = DigitalCollectionContract.map(
    input,
    DigitalCollectionCallback.checked1((_: Expr[SInt]) => captured)
  )
  FinalNameGuardControls.exercise(this)
