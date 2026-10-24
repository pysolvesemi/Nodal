package nodal.internal.testkit.deadsuffix

import nodal.*

final class DeadSuffixRoot extends Module:
  DeadSuffixControls.retain(this)

/** Each record captures this real authored constructor call site. */
final class DeadSuffixCapture(root: Module, mode: String):
  val builder = new SemanticOriginBuilder
  builder.captureModule(0L, root, "DeadSuffixRoot", None)
  mode match
    case "metadata" => SuffixCaptureApi.metadata(builder)
    case "fifo" => SuffixCaptureApi.fifo(builder)
    case "pipe" => SuffixCaptureApi.pipe(builder)
    case "delay" => SuffixCaptureApi.delay(builder)
    case "fsm-operation" => SuffixCaptureApi.fsm(builder)
    case "fsm-owner" => SuffixFsmCaptureApi.metadata(builder)
    case other => throw new IllegalArgumentException(other)
