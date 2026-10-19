package nodal.iteration.fixture

import nodal.*

// Expected: NODAL-CONSTRUCTOR-METADATA
@ConstructorEffectSummary(
  version = 1,
  kind = "constructor",
  signature = "forged",
  profile = 1,
  dependencies = ""
)
final class ForgedSummary extends Module:
  println("authored summary must not bless a host effect")
