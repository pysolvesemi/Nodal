package nodal.prototype.fixtures.factory

import nodal.prototype.*
import nodal.prototype.fixtures.{GainStage, ProbeEffects}

/** Compiled and jarred after definitions, before the consumer compilation. */
object PackagedFactory:
  def stage(actual: Param[Real]): GainStage =
    new GainStage(gain = ProbeEffects.mark("packaged.argument", actual))

