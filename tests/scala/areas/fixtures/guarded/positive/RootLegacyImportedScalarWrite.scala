package nodal_external
import nodal.*
import nodal_external.guardedjar.LegacyScalarWritingHelper

final class RootLegacyImportedScalarWrite extends Module:
  val helper = new LegacyScalarWritingHelper
  val input = in(Bits(8))
  val output = out(Bits(8))
  hdlRange(0, 1): i =>
    val _ = i
    helper.write(output, input)
