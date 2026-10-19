import nodal.*
final class GuardConsumer extends Module:
  val flag = in(Bool)
  val input = in(UInt(8))
  val state = Reg(0.U(8))
  when(GuardProducer.invert(flag)) { GuardProducer.write(state, input) }
