package nodal_external.guardedjar
import nodal.*
final class UnknownConsumer extends Module:
  val choice = param(0.integer, range = 0 to 1)
  val count = param(3.integer, range = 2 to 8)
  if choice == 0 then
    val _ = UnknownFactory.make(count)
  else ()
