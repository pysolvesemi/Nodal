package nodal.internal.testkit
import nodal.*
final class InterfaceEpochRoot extends Module:
  val child = instance(new InterfaceEpochLeaf)
  val first = child(_.p)
  InterfaceEpochControls.exercise(this)
  def next(): InterfaceArray[InterfaceEpochLink, SourceRole] = child(_.q)
