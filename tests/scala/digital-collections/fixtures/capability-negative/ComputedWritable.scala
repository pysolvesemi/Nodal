// Expected detail: value writable is not a member
package nodal_external
import nodal.*
final class ComputedWritable extends Module:
  val input = in(Vec(UInt(8), 2))
  val result = input.map(value => value)
  result.writable.at(0) := input.at(0)
