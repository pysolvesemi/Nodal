package nodal.constructor.separate

import nodal.*

final class RawSeparateTop extends Module:
  val child: SeparateGain = RawSeparateFactory.gain()

object RawSeparateConsumer:
  def main(arguments: Array[String]): Unit =
    val failure = scala.util.Try(ConstructionKernel.inspect(new RawSeparateTop)).failed.get
    val diagnostic = failure.asInstanceOf[ConstructionException].diagnostic
    assert(diagnostic.code == "NODAL-CONSTRUCTOR-MISSING-016")
    println("UNINSTRUMENTED_FACTORY_REJECTION_PASS")
