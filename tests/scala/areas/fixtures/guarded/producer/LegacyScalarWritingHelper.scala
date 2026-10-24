package nodal_external.guardedjar
import nodal.*

/** Closed scalar signature has an actual checked writing summary, unlike opaque Vec. */
final class LegacyScalarWritingHelper:
  final def write(output: Signal[Bits], input: Expr[Bits]): Unit = output := input
