// Expected: NODAL-COLLECTION-CALLBACK-UNAVAILABLE
// Expected detail: nodal.SignalSelection.:=
package nodal_external
import nodal.*
final class WritableMapWrite extends Module:
  val input = in(Vec(UInt(8), 2))
  val cells = wire(Vec(UInt(8), 2))
  val selected = cells.writable.at(0)
  val mapped = input.map(value =>
    selected := value
    value
  )
