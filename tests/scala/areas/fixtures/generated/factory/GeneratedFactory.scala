package generated_producer
import nodal.*
object LaneFactory:
  def apply(initial: Expr[UInt]): ReusableLane = new ReusableLane(initial)
  def first[A <: Area](lanes: GeneratedAreas[A]): GeneratedAreaRef[A] = lanes(0)
  def keep[A <: Area](reference: GeneratedAreaRef[A]): GeneratedAreaRef[A] = reference

object NestedLaneFactory:
  def apply(): ReusableOuter = new ReusableOuter
  def selected(value: GeneratedAreaRef[ReusableOuter]): GeneratedAreas[ReusableLane] = value.inner
