package increment13negative

import nodal.*

final class RuntimeGenerateBound extends Module:
  val count = in(UInt(8))

  // diagnostic-anchor: NODAL-STAGE-013
  generate(count): _ => ()
