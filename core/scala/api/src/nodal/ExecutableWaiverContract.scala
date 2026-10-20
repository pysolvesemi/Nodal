package nodal

import java.nio.charset.StandardCharsets

/** Closed stateless exception schema. This is neither a synchronizer nor a global domain fact. */
private[nodal] object ExecutableWaiverContract:
  val Operation = "cdc_waive"
  val Contract = "stateless_waiver_v1"
  val MaximumPayloadBits = 4096
  val Obligations: Vector[(String, String)] = Vector(
    "physical_safety" -> "user_responsibility",
    "delivery" -> "not_guaranteed",
    "reset" -> "not_exempt"
  )

  // Unicode White_Space, shared exactly with native ingress, not platform-dependent trim/isBlank.
  private def whitespace(value: Int): Boolean =
    (value >= 0x0009 && value <= 0x000d) || value == 0x0020 || value == 0x0085 ||
      value == 0x00a0 || value == 0x1680 ||
      (value >= 0x2000 && value <= 0x200a) ||
      value == 0x2028 || value == 0x2029 || value == 0x202f || value == 0x205f || value == 0x3000

  def validateText(id: String, reason: String, reject: String => Nothing): Unit =
    def check(value: String, limit: Int, name: String): Unit =
      if value == null || !StandardCharsets.UTF_8.newEncoder().canEncode(value) ||
        value.getBytes(StandardCharsets.UTF_8).length > limit ||
        !value.codePoints().anyMatch(point => !whitespace(point))
      then reject(s"Cdc.waive $name requires nonblank valid UTF-8 text of at most $limit bytes")
    check(id, 256, "id")
    check(reason, 4096, "reason")
