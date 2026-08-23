package contracts.v03negative

import nodal.*

final class MixedSignednessNegative extends Module:
  val unsigned = in(UInt(8))
  val signed = in(SInt(8))

  // diagnostic-anchor: NODAL-NUM-015
  val illegal = unsigned + signed
