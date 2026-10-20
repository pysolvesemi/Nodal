package nodal

/** Closed owned clock transport. Provenance still comes from actual operands at each boundary. */
private[nodal] object OwnedClockContract:
  val Operations: Set[String] = Set("clock_gate", "clock_mux")
  def contract(kind: String): String = if kind == "gate" then "gate_v1" else "glitchless_mux_v1"
  def obligations(kind: String): Vector[(String, String)] =
    if kind == "gate" then
      Vector(
        "control_timing" -> "inactive_phase_aperture",
        "startup" -> "known_after_inactive_phase"
      )
    else
      Vector(
        "control_sampling" -> "level_coalescing",
        "switching" -> "serialized_break_before_make",
        "reset_epoch" -> "common_assertion",
        "pulse_integrity" -> "except_reset_assertion"
      )
