package nodal.internal.testkit

import nodal.*
import nodal.internal.bridge.*
import java.nio.file.{Files, Paths}

/** Actual source witness for source-local, state-derived ready feedback into valid. */
object Increment57FifoFeedbackReplay:
  def main(args: Array[String]): Unit =
    val directory = Paths.get(args(0))
    val _ = Files.createDirectories(directory)
    val snapshot = ConstructionKernel.inspect(new FifoProtocolCapture("feedback"))
    val result = ScalaToMlirBridge.fromSnapshot(snapshot)
    val _ = Files.writeString(directory.resolve("fifo_feedback.mlir"), result.text)
