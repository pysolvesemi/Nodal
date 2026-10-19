package nodal.callback.fixture

import nodal.*

// Expected: NODAL-COLLECTION-CALLBACK-FORGED
object ForgedFactory:
  val forged = DigitalCollectionCallback.checked1((x: Expr[UInt]) => x)
