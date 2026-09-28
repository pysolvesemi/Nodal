package nodal.prototype.fixtures.raw

import nodal.prototype.*
import nodal.prototype.fixtures.GainStage

// Compile without the plugin against the instrumented definitions JAR.
object RawFactory:
  def stage(actual: Param[Real]): GainStage = new GainStage(actual)
