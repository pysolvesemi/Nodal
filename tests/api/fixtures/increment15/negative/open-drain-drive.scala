package contracts.v03negative

import nodal.*

val openDrainEndpoint = digitalInout(
  Bits(1),
  DriveMode.openDrain,
  InoutPlacement.TopLevelPin,
  ResolutionProfile.PortableBoundaryOnly,
  "openDrain"
)

// diagnostic-anchor: NODAL-INOUT-015
openDrainEndpoint.drive(1.U(1), true.B)
