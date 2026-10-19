// Expected: NODAL-COLLECTION-CALLBACK-UNAVAILABLE
// Expected detail: nodal.SignalSelection.:=
package nodal_external
import nodal.*
final class WritableFoldWrite extends Module:
  val input = in(Vec(UInt(8), 2))
  val cells = wire(Vec(UInt(8), 2))
  val selected = cells.writable.at(0)
  val folded = input.fold(0.U(8))((accumulator, value) =>
    selected := value
    accumulator ^ value
  )
