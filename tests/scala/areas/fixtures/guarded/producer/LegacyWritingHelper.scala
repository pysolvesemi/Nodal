package nodal_external.guardedjar
import nodal.*
object LegacyWritingHelper:
  def write(target: Signal[Vec[UInt]], input: Expr[Vec[UInt]], index: Expr[Integer]): Unit =
    target.writable.at(index) := input.at(index)
