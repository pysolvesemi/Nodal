package nodal.prototype.negative.missingmetadata

import nodal.prototype.fixtures.raw.RawStage

// Compile with the plugin against the separately compiled raw definitions JAR.
// Expected diagnostic: NODAL-CTOR-PROTOTYPE-MISSING.
object MissingMetadata:
  def construct(): RawStage = new RawStage()

