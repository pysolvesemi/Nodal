package prototypes.candidates

import external.reuse.GainStage
import nodal.*

final class ExternalReuseCandidate extends Module:
  val source = inout(Electrical)
  val sink = inout(Electrical)
  val common = inout(Electrical)
  val gain = param(4.0.real)

  private val stage = instance(new GainStage()).param(_.gain, gain)

  connect(source, stage(_.source))
  connect(sink, stage(_.sink))
  connect(common, stage(_.common))
