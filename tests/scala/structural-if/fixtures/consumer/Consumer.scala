import nodal.*
final class StructuralConsumer extends Module:
  val MODE = param(0.integer, 0 to 1)
  val input = in(Bits(8))
  val output = out(Bits(8))
  val selected = if MODE == 0 then ReadOnlyProducer.invert(input) else ~input
  if MODE == 0 then ReadOnlyProducer.write(output, selected) else output := input
