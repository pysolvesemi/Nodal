import nodal.*
final class GuardBodyConsumer extends Module:
  val flag = in(Bool)
  val input = in(UInt(8))
  val output = out(UInt(8))
  when(flag) { GuardProducer.continuous(output, input) }
