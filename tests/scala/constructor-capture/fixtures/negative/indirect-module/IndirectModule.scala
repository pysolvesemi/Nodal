package nodal.prototype.negative.indirect

import nodal.prototype.*
import scala.language.implicitConversions

class DirectBase(gain: Param[Real] = 2.0) extends Module
class IndirectModule(gain: Param[Real] = 2.0) extends DirectBase(gain)
