package nodal.callback.fixture

import nodal.*
import nodal_callback_external.Uninstrumented

final class CheckedSource extends Module:
  val input = in(Vec(UInt(8), 4))
  val offset = in(UInt(8))
  private def add(value: Expr[UInt]): Expr[UInt] = value + offset
  val result = input.map(add)

final class UncheckedSource extends Module:
  val input = in(Vec(UInt(8), 4))
  val result = Uninstrumented.map(input)

object RuntimeProbe:
  def main(arguments: Array[String]): Unit =
    val _ = arguments
    val checked = ConstructionKernel.capture(new CheckedSource)(module => Vector(module.result))
    assert(checked.references.nonEmpty)
    val failure = scala.util.Try(ConstructionKernel.inspect(new UncheckedSource)).failed.get
    assert(failure.asInstanceOf[ConstructionException].diagnostic.code ==
      "NODAL-COLLECTION-CALLBACK-MISSING")
    assert(Uninstrumented.calls == 0)
    println("DIGITAL_COLLECTION_CALLBACK_PASS checked-public-source uninstrumented-rejection")
