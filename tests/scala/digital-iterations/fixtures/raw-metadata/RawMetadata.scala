package nodal.iteration.fixture

import nodal.*

@ConstructorSchema(version = 1, encoded = "")
final class MissingSummaryChild extends Module

@ConstructorSchema(version = 1, encoded = "")
@ConstructorEffectSummary(
  version = 999,
  kind = "constructor",
  signature =
    "nodal.iteration.fixture.StaleSummaryChild():nodal.iteration.fixture.StaleSummaryChild",
  profile = 1,
  dependencies = ""
)
final class StaleSummaryChild extends Module

@ConstructorSchema(version = 1, encoded = "")
@ConstructorEffectSummary(
  version = 1,
  kind = "singleton",
  signature =
    "nodal.iteration.fixture.WrongKindSummaryChild():nodal.iteration.fixture.WrongKindSummaryChild",
  profile = 1,
  dependencies = ""
)
final class WrongKindSummaryChild extends Module

@ConstructorSchema(version = 1, encoded = "")
@ConstructorEffectSummary(
  version = 1,
  kind = "constructor",
  signature = "forged.signature",
  profile = 1,
  dependencies = ""
)
final class WrongSignatureSummaryChild extends Module

@ConstructorSchema(version = 1, encoded = "")
@ConstructorEffectSummary(
  version = 1,
  kind = "constructor",
  signature =
    "nodal.iteration.fixture.WrongProfileSummaryChild():nodal.iteration.fixture.WrongProfileSummaryChild",
  profile = 99,
  dependencies = ""
)
final class WrongProfileSummaryChild extends Module

@ConstructorSchema(version = 1, encoded = "")
@ConstructorEffectSummary(
  version = 1,
  kind = "constructor",
  signature =
    "nodal.iteration.fixture.CyclicSummaryChild():nodal.iteration.fixture.CyclicSummaryChild",
  profile = 1,
  dependencies =
    "constructor\tclass:nodal.iteration.fixture.CyclicSummaryChild\t\tnodal.iteration.fixture.CyclicSummaryChild():nodal.iteration.fixture.CyclicSummaryChild\t1"
)
final class CyclicSummaryChild extends Module

// Compiled without the plugin: same harmless body, deliberately no effect summary.
object UninstrumentedHelper:
  def effect(index: LoopIndex): Unit =
    val _ = index
