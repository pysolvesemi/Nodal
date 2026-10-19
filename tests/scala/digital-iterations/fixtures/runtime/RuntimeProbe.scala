package nodal.iteration.fixture

import nodal.*

import nodal_iteration_external.Uninstrumented

final class CheckedEmptyLoop extends Module:
  loop(LoopBound.Static(0)): _ =>
    ()

final class CheckedEmptyRange extends Module:
  hdlRange(0, 0): _ =>
    ()

final class UncheckedLoop extends Module:
  Uninstrumented.loopEmpty()

final class UncheckedRange extends Module:
  Uninstrumented.rangeEmpty()

final class CheckedLibraryAllocation extends Module:
  val child = instance(new SeparateChild)

final class CheckedLibraryRangeAllocation extends Module:
  hdlRange(0, 2): _ =>
    val _ = instance(SeparateFactory.defaultChild())

object RuntimeProbe:
  def main(arguments: Array[String]): Unit =
    val _ = arguments
    assert(ConstructionKernel.inspect(new CheckedLibraryAllocation).modules.size == 2)
    assert(ConstructionKernel.inspect(new CheckedLibraryRangeAllocation).modules.size == 2)
    assert(ConstructionKernel.inspect(new CheckedEmptyLoop).digitalLoops.size == 1)
    ConstructionKernel.inspect(new CheckedEmptyRange)
    val loopFailure = scala.util.Try(ConstructionKernel.inspect(new UncheckedLoop)).failed.get
    assert(loopFailure.asInstanceOf[ConstructionException].diagnostic.code ==
      "NODAL-LOOP-CALLBACK-MISSING")
    val rangeFailure = scala.util.Try(ConstructionKernel.inspect(new UncheckedRange)).failed.get
    assert(rangeFailure.asInstanceOf[ConstructionException].diagnostic.code ==
      "NODAL-RANGE-CALLBACK-MISSING")
    assert(Uninstrumented.calls == 0)
    println("DIGITAL_ITERATION_CALLBACK_PASS checked-public-source empty-uninstrumented-rejection")
