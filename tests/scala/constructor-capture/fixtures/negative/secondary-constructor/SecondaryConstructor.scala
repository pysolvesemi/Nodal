package nodal.prototype.negative.secondary

import nodal.prototype.*
import scala.language.implicitConversions

class SecondaryConstructor(gain: Param[Real] = 2.0) extends Module:
  def this(ignored: Int) = this(2.0)

