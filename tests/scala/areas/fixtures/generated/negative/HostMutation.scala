import nodal.*
final class GeneratedHostMutation extends Module:
  var visits = 0
  val lanes =
    for i <- hdlRange(0, 3) yield new Area:
      visits += 1
      val stored = Reg(false.B)
      val _ = i
