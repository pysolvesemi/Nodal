package prototypes.candidates

import external.reuse.GainStage
import nodal.*

final class ExternalReuseCandidate extends Module:
  val input = inout(Electrical)
  val output = inout(Electrical)
  val common = inout(Electrical)
  val gain = param(4.0.real)

  private val stage = instance(new GainStage()).param(_.gain, gain)

  connect(input, stage(_.input))
  connect(output, stage(_.output))
  connect(common, stage(_.common))
