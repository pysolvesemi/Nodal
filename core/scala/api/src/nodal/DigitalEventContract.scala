package nodal

/** Closed quarantine transport and resource envelope. This is not a ClockDomain capability. */
private[nodal] object DigitalEventContract:
  val Contract = "root_event_v1"
  val MaximumPayloadBits = 4096
  val MaximumModuleStateBits = 262144L
  val MaximumCaptures = 4096
  val MaximumNodes = 65536
  val MaximumEdges = 262144
  val MaximumTypeDepth = 128
  val Obligations = Vector(
    "trigger" -> "known_levels_definite_edges",
    "external_sampling" -> "user_setup_hold",
    "startup" -> "unspecified",
    "updates" -> "simultaneous_pre_event"
  )
