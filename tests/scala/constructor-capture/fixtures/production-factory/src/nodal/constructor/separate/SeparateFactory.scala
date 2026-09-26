package nodal.constructor.separate

import nodal.*

object SeparateFactory:
  def gain(actual: Param[Real]): SeparateGain = new SeparateGain(gain = actual)
  def zero(): SeparateZero = new SeparateZero
