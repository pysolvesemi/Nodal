package nodal.increment36fixture

import nodal.*
import nodal.internal.bridge.ScalaToMlirBridge
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}

final class WaveformSource extends Module:
  val p = inout(Electrical)
  val n = inout(Electrical)
  val maximum = param(4.0.ns)
  analog:
    val filtered = transition(1.0.V, 0.0.ns, 1.0.ns)
    val rate = 1.0.V / 1.0.ns
    val limited = slew(V(p, n), rate, -rate)
    val delayed = absdelay(limited, abstime + 1.0.ns, maximum)
    boundStep(1.0.ns)
    V(p, n) <+ filtered + filtered + delayed

object Increment36ConstructionCheck:
  def main(arguments: Array[String]): Unit =
    require(arguments.length == 1, "expected the output MLIR path")
    val first = ScalaToMlirBridge.lower(new WaveformSource)
    val second = ScalaToMlirBridge.lower(new WaveformSource)
    require(first == second, "waveform lowering is nondeterministic")
    require(first.text.contains("nodal.analog_bound_step"))
    Files.writeString(Paths.get(arguments(0)), first.text, StandardCharsets.UTF_8)
    println(s"Increment 36 source witness passed: ${first.sha256}")
