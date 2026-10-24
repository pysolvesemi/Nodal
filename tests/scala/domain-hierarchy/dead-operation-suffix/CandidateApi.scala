package nodal.internal.testkit.deadsuffix

import nodal.*

/** Resolver-unit API-frame fixture, NOT production DSL or typed hardware. This authored file
  * deliberately uses the resolver's existing API-file classification. No KernelSourceSite or
  * SourceSpan is manufactured or replaced.
  */
object SuffixCaptureApi:
  def metadata(builder: SemanticOriginBuilder): Unit =
    builder.captureOperation(0L, "metadata", Vector.empty)
  def fifo(builder: SemanticOriginBuilder): Unit =
    builder.captureOperation(0L, "metadata", Vector.empty)
  def pipe(builder: SemanticOriginBuilder): Unit =
    builder.captureOperation(0L, "metadata", Vector.empty)
  def delay(builder: SemanticOriginBuilder): Unit =
    builder.captureOperation(0L, "metadata", Vector.empty)
  def fsm(builder: SemanticOriginBuilder): Unit =
    builder.captureOperation(0L, "metadata", Vector.empty)

object SuffixFsmCaptureApi:
  def metadata(builder: SemanticOriginBuilder): Unit =
    builder.captureOperation(0L, "metadata", Vector.empty)
