package nodal.internal.testkit

import nodal.*

final class C2aRoot extends Module:
  val child = instance(new C2aLeaf(C2aControls.count))
  val input = child(_.data)
  val unusedInput = Option.when(C2aControls.keepUnused)(
    input.named("unused_input").asInstanceOf[KernelExpr[Vec[SInt]]]
  )
  val unused = unusedInput.map(value =>
    CandidateRuntime.shapeIndex(value, Vector(0)).asInstanceOf[KernelExpr[SInt]]
  )
  val result = C2aControls.reserve(input)
  C2aControls.exercise(this)
