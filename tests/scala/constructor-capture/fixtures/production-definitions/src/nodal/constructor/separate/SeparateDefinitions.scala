package nodal.constructor.separate

import nodal.*

final class SeparateGain(gain: Param[Real] = 2.0) extends Module:
  def parameter: Param[Real] = gain
  val pin: Node[Electrical.type] = in(Electrical)

final class SeparateZero extends Module:
  val pin: Node[Electrical.type] = in(Electrical)
