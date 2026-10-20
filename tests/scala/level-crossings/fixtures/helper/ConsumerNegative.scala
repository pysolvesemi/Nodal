package levelcrossingconsumer

import nodal.*
import levelcrossinghelper.LevelCrossingHelper

final class HelperCollectionNegative extends Module:
  val destination = ClockDomain.required("destination")
  val input = in(Bool)
  destination:
    val crossed = map(in(Vec(Bool, 2))) { _ =>
      LevelCrossingHelper.sync(input, destination, 2)
    }
    val _ = crossed
