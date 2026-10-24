package nodal_external
import nodal.*
import nodal_external.guardedjar.LegacyScalarWritingHelper

// Expected: NODAL-STRUCTURAL-055-SUMMARY on the actual checked writing helper.
final class GuardedLegacyImportedScalarWrite extends Module:
  val helper = new LegacyScalarWritingHelper
  val choice = param(0.integer, range = 0 to 1)
  val input = in(Bits(8))
  val output = out(Bits(8))
  if choice == 0 then
    hdlRange(0, 1): i =>
      val _ = i
      helper.write(output, input)
  else output := input
