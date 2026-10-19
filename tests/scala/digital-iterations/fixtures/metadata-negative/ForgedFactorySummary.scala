package nodal.iteration.fixture

import nodal.*

object ForgedFactorySummary:
  // Expected: NODAL-CONSTRUCTOR-METADATA
  @ConstructorEffectSummary(
    version = 1,
    kind = "factory",
    signature = "forged",
    profile = 1,
    dependencies = ""
  )
  def child(): SeparateChild =
    println("authored factory summary must not bless a host effect")
    new SeparateChild
