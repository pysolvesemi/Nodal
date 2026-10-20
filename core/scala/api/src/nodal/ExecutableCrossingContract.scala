package nodal

/** Closed source/bridge transport schema, never a proof derived from a host wrapper. */
private[nodal] object ExecutableCrossingContract:
  val Operations: Set[String] = Set("cdc_sync", "cdc_gray", "cdc_pulse")
  val MaximumStateBits = 262144L

  def contract(kind: String): String = kind match
    case "sync" => "level_v1"
    case "gray" => "gray_v1"
    case "pulse" => "pulse_v1"
    case _ => "invalid"

  def obligations(kind: String): Vector[(String, String)] = kind match
    case "gray" => Vector(
        "transition" -> "one_bit_per_source_sample",
        "skew" -> "less_than_source_period",
        "stability" -> "stable_between_source_samples"
      )
    case "pulse" => Vector(
        "spacing" -> "returned_acknowledgement",
        "width" -> "single_source_cycle",
        "reset_epoch" -> "common_assertion"
      )
    case _ => Vector.empty

  def stateBits(kind: String, maximumPayloadBits: BigInt, stages: Int): BigInt = kind match
    case "gray" => maximumPayloadBits * (stages + 1) + 1
    case "pulse" => BigInt(7)
    case _ => BigInt(stages)
