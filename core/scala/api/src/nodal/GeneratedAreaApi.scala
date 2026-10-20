package nodal

import scala.language.dynamics

/** A retained structural range. Existing callback syntax and foreach keep the same effect owner. */
final class HdlRange private[nodal] (
    private[nodal] val lower: Int | Expr[Integer],
    private[nodal] val upperExclusive: Int | Expr[Integer],
    private[nodal] val step: Int | Expr[Integer],
    private[nodal] val maximum: Option[Int]
):
  def foreach(body: Expr[Integer] => Unit): Unit =
    CandidateRuntime.hdlRange(lower, upperExclusive, step, maximum)(body)
  def map[A <: Area](body: Expr[Integer] => A): GeneratedAreas[A] =
    DigitalIterationCallback.requireAreaRange(body)
    ConstructionKernel.generatedAreas(lower, upperExclusive, step, maximum)(body)

/** Extension application preserves callback syntax without making overload selection ambiguous. */
extension (range: HdlRange)
  def apply(body: Expr[Integer] => Unit): Unit = range.foreach(body)

/** One typed generated family, never an eagerly constructed Scala collection of lanes. */
final class GeneratedAreas[A <: Area] private[nodal] (
    private[nodal] val generatedRegion: GeneratedRegionRecord,
    private[nodal] val selectionPrefix: Vector[GeneratedFamilySelectionRecord] = Vector.empty
):
  def apply(ordinal: Int | Expr[Integer]): GeneratedAreaRef[A] =
    ConstructionKernel.generatedAreaReference(this, ordinal)

/** A non-Data lexical reference. Saving or passing it never materializes an Area or storage. */
final class GeneratedAreaRef[A <: Area] private[nodal] (
    private[nodal] val family: GeneratedAreas[?],
    private[nodal] val ordinal: Int | Expr[Integer],
    private[nodal] val members: Vector[String]
) extends Dynamic:
  transparent inline def selectDynamic(inline name: String): Any =
    ${ GeneratedAreaDerivation.member[A]('this, 'name) }
  transparent inline def applyDynamic(inline name: String)(ordinal: Int | Expr[Integer]): Any =
    ${ GeneratedAreaDerivation.familyAt[A]('this, 'name, 'ordinal) }

private[nodal] object GeneratedAreaRuntime:
  val MemberOperation = "generated_member"
  def nested[A <: Area](value: GeneratedAreaRef[?], name: String): GeneratedAreaRef[A] =
    ConstructionKernel.generatedAreaNested(value, name)
  def family[A <: Area](value: GeneratedAreaRef[?], name: String): GeneratedAreas[A] =
    ConstructionKernel.generatedAreaFamily(value, name)
  def member[A](value: GeneratedAreaRef[?], name: String, kind: String): A =
    ConstructionKernel.generatedAreaMember(value, name, kind).asInstanceOf[A]
  def bind[A <: Area](name: String, priority: Int, value: GeneratedAreas[A]): GeneratedAreas[A] =
    ConstructionKernel.bindGeneratedAreas(name, priority, value)
    value
