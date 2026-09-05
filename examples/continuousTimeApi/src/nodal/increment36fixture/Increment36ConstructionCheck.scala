package nodal.increment36fixture

import nodal.*
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
    val first = ConstructionKernel.inspect(new WaveformSource)
    val second = ConstructionKernel.inspect(new WaveformSource)
    require(first == second, "waveform construction is nondeterministic")
    require(first.waveformOperators.size == 5)
    require(first.waveformOperators.flatMap(_.stateId).distinct.size == 3)
    Files.writeString(
      Paths.get(arguments(0)),
      "Increment 36 public construction: PASS\n",
      StandardCharsets.UTF_8
    )
