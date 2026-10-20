package nodal.internal.testkit

import nodal.*

// Deliberately compiled in the testkit producer without the constructor plugin. A getter must
// not let this separately compiled singleton execute hidden authored control in an event body.
object EventControlForeignHooks:
  var enabled = true
  var target: Signal[Bool] = null

object EventControlForeignInitializer:
  val update: Unit = if EventControlForeignHooks.enabled then
    EventControlForeignHooks.target := true.B
