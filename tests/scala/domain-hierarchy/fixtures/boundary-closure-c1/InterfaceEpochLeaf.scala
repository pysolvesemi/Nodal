package nodal.internal.testkit
import nodal.*
import nodal.InoutPlacement.BlackBoxPin
import nodal.ResolutionProfile.PortableBoundaryOnly
import InterfaceEpochSchema.{link, role}
final class InterfaceEpochLeaf extends ExternalModule("EpochArrayPins"):
  val p = externalInterfaceArray(link, role, 2, "p", BlackBoxPin, PortableBoundaryOnly)
  val q = externalInterfaceArray(link, role, 3, "q", BlackBoxPin, PortableBoundaryOnly)
