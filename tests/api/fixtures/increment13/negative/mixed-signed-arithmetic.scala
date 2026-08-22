package increment13negative

import nodal.*

final class MixedSignedArithmetic extends Module:
  val unsigned = in(UInt(8))
  val signed = in(SInt(8))

  // diagnostic-anchor: NODAL-NUM-013
  val illegal = unsigned + signed
