import nodal.*
final class PredicateConsumer extends Module:
  val MODE = param(1.integer, 0 to 2)
  val input = in(Bits(8))
  val output = out(Bits(8))
  val selected =
    if ({ ReadOnlyProducer.write(output, input); MODE }) == 0 then ~input else input
