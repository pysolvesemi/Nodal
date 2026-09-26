package nodal.prototype.negative.curried

import nodal.prototype.*
import scala.language.implicitConversions

// Separate compilation must reject the shape rather than publish partial metadata.
class CurriedConstructor(first: Param[Real] = 2.0)(second: Param[Real] = 3.0) extends Module

