import nodal.*
final class GuardPredicateConsumer extends Module:
  val flag = in(Bool)
  val input = in(UInt(8))
  val state = Reg(0.U(8))
  when(GuardProducer.touch(state, input, flag)) { state := input }
