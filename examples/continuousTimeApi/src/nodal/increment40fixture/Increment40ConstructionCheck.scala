package nodal.increment40fixture

import nodal.*

/** Public-only witness; native generation is performed separately by qualification. */
final class TransferFilters extends Module:
  val stimulus = inout(Electrical)
  val filtered = inout(Electrical)
  val sampled = inout(Electrical)
  val reference = inout(Electrical)
  val gain = param(2.0.real)
  val tau = param(1.0e-3.s)
  analog:
    val signal = V(stimulus, reference)
    val shared = laplaceNd(signal, Seq(gain), Seq(1.0.real, tau))
    V(filtered, reference) <+ shared + shared
    val discrete = ziNd(
      shared,
      Seq(0.5.real, 0.5.real),
      Seq(1.0.real),
      1.0e-3.s,
      1.0e-6.s,
      0.0.s
    )
    V(sampled, reference) <+ discrete
    // Equal calls own distinct states. Unused/zero filters must not be erased.
    val _ = laplaceNd(signal, Seq(gain), Seq(1.0.real, tau))
    val _ = laplaceNd(0.0.V, Seq(0.0.real), Seq(1.0.real, 1.0e-3.s))
    val _ = ziNd(signal, Seq(1.0.real), Seq(1.0.real), 1.0e-3.s)
    val _ = ziNd(signal, Seq(1.0.real), Seq(1.0.real), 1.0e-3.s, 1.0e-6.s)

object Increment40ConstructionCheck:
  def main(arguments: Array[String]): Unit =
    require(arguments.isEmpty, "no arguments expected")
    val _ = Nodal.emit(new TransferFilters)
    println("Increment 40 public transfer construction passed")
