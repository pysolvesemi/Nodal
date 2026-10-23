package nodal_external
import nodal.*
final class GlobalInitializationEffectfulSource extends Module:
  val lanes =
    for i <- hdlRange(0, 3) yield new Area:
      private val schema = GlobalInitializationEffectfulFactory.make(i + 1.integer)
      val _ = schema
