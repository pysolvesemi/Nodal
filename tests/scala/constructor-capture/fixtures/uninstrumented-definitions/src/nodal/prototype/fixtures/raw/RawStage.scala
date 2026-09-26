package nodal.prototype.fixtures.raw

import nodal.prototype.*
import scala.language.implicitConversions

// Compile without the plugin: the class deliberately has no constructor schema.
class RawStage(gain: Param[Real] = 2.0) extends Module:
  val observedGain = gain
