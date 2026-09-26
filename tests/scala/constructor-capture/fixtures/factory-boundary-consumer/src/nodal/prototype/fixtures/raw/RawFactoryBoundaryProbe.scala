package nodal.prototype.fixtures.raw

import nodal.prototype.*

object RawFactoryBoundaryProbe:
  def main(args: Array[String]): Unit =
    assert(Capture.activeDepth == 0, "probe started with an active allocation")
    val observation = Capture.observe("uninstrumented factory boundary") {
      RawFactory.stage(Param.literal(7.0))
    }
    val rejected = observation.outcome.left.toOption.exists { failure =>
      failure.isInstanceOf[IllegalStateException] &&
      failure.getMessage == "Module constructor was not captured by the plugin"
    }
    assert(rejected, s"expected missing factory capture rejection: ${observation.outcome}")
    assert(observation.allocations.isEmpty, "raw factory published an allocation")
    assert(observation.events.isEmpty, "raw factory created capture events")
    assert(observation.pendingDepth == 0, "raw factory left a pending allocation")
    assert(Capture.activeDepth == 0, "raw factory leaked active state")
    println("UNINSTRUMENTED_FACTORY_REJECTED")
