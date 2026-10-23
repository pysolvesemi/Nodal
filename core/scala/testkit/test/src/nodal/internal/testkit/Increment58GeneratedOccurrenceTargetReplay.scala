package nodal.internal.testkit

import nodal.*
import nodal.internal.bridge.*
import nodal_external.*
import java.nio.file.{Files, Path}

/** Exports the maintained source witnesses. The destination is supplied by the caller. */
object Increment58GeneratedOccurrenceTargetReplay:
  def main(args: Array[String]): Unit =
    require(args.length == 1, "expected an existing output directory under .work")
    val output = Path.of(args(0)).toAbsolutePath.normalize()
    require(Files.isDirectory(output))
    require(output.toString.split(java.io.File.separator).contains(".work"))
    Vector[(String, () => Module)](
      "scalar" -> (() => new Increment58GeneratedOccurrenceTargetScalar),
      "state" -> (() => new Increment58GeneratedOccurrenceTargetState),
      "nested" -> (() => new Increment58GeneratedOccurrenceTargetNested),
      "struct" -> (() => new Increment58GeneratedOccurrenceTargetStruct),
      "multifield" -> (() => new Increment58GeneratedOccurrenceTargetMultifield),
      "guarded" -> (() => new Increment58GeneratedOccurrenceTargetGuarded),
      "nested-multifield" -> (() => new Increment58GeneratedOccurrenceTargetNestedMultifield),
      "nonunit" -> (() => new Increment58GeneratedOccurrenceTargetNonunit),
      "repeated" -> (() => new Increment58GeneratedOccurrenceTargetRepeated),
      "empty" -> (() => new Increment58GeneratedOccurrenceTargetEmpty)
    ).foreach: (name, make) =>
      Files.writeString(output.resolve(name + ".mlir"), ScalaToMlirBridge.lower(make()).text)
      println("GENERATED_OCCURRENCE_SOURCE_PASS " + name)
