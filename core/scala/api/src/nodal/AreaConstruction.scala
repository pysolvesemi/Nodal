package nodal

import scala.collection.mutable

/** Logical member identity points at an existing canonical hardware object; it creates no value. */
private[nodal] final case class KernelAreaMemberSnapshot(
    name: String,
    path: String,
    target: String
)

private[nodal] final case class KernelAreaSnapshot(
    path: String,
    owner: String,
    parent: Option[String],
    source: SourceSpan,
    domain: Option[String],
    declarations: Vector[String],
    members: Vector[KernelAreaMemberSnapshot],
    generatedScope: Option[KernelGeneratedScope] = None
)

private[nodal] final case class AreaMemberRecord(value: Any, access: String)

private[nodal] final case class KernelGeneratedAreaExport(
    member: String,
    target: String,
    access: String,
    kind: String = "storage"
)

private[nodal] final case class KernelGeneratedFamilySelection(
    family: String,
    ordinal: String,
    member: String
)

private[nodal] final case class GeneratedFamilySelectionRecord(
    family: GeneratedRegionRecord,
    ordinal: Int | Expr[Integer],
    members: Vector[String]
)

private[nodal] final case class KernelGeneratedMemberSnapshot(
    family: String,
    ordinal: String,
    member: String,
    access: String,
    selectionPrefix: Vector[KernelGeneratedFamilySelection] = Vector.empty,
    familyInput: Option[String] = None
)

/** Exact non-Data exported symbol selection, sharing the original family reference and prefix. */
private[nodal] final case class GeneratedSymbolSelectionRecord(
    reference: GeneratedAreaRef[?],
    member: String
):
  private def ordinal(value: Int | Expr[Integer]): Any = value match
    case literal: Int => literal.toLong
    case expression: KernelExpr[?] =>
      expression.literal.filter(_.kind == "integer").flatMap(_.value.toLongOption)
        .getOrElse(expression)
    case expression => expression
  private val identity = (
    reference.family.generatedRegion,
    ordinal(reference.ordinal),
    reference.members,
    reference.family.selectionPrefix.map(value => (value.family, ordinal(value.ordinal), value.members)),
    member
  )
  override def equals(other: Any): Boolean = other match
    case value: GeneratedSymbolSelectionRecord => identity == value.identity
    case _ => false
  override def hashCode(): Int = identity.hashCode()

private[nodal] final case class InstanceSelectionRecord(
    instance: Instance[?],
    generated: Option[GeneratedSymbolSelectionRecord] = None
)

private[nodal] final case class InstancePortBoundaryRecord(
    instance: InstanceRecord,
    port: DeclarationRef,
    generated: Option[GeneratedSymbolSelectionRecord] = None,
    scope: Option[DigitalValueRegionRecord] = None
)

private[nodal] final case class GeneratedAreaMemberRecord(
    family: GeneratedRegionRecord,
    ordinal: Int | Expr[Integer],
    members: Vector[String],
    target: DeclarationRef,
    access: String,
    selectionPrefix: Vector[GeneratedFamilySelectionRecord] = Vector.empty
)

/** Owned solely by ConstructionSession, alongside its declaration and instance records. */
private[nodal] final class AreaRecord(
    val owner: Long,
    val source: SourceSpan,
    val parent: Option[AreaRecord],
    val domain: Option[ClockDomain],
    val generatedScope: Option[GeneratedRegionRecord] = None
):
  var value: Option[Area] = None
  var name: Option[String] = None
  var namePriority: Int = -1
  val aliases: mutable.ArrayBuffer[String] = mutable.ArrayBuffer.empty
  val members: mutable.LinkedHashMap[String, AreaMemberRecord] = mutable.LinkedHashMap.empty
  val declarations: mutable.ArrayBuffer[DeclarationRef] = mutable.ArrayBuffer.empty
  val instances: mutable.ArrayBuffer[InstanceRecord] = mutable.ArrayBuffer.empty

/** One boundary projection of an original complete Interface endpoint. */
private[nodal] final case class InterfaceBoundaryRecord(
    instance: Option[InstanceRecord],
    port: DeclarationRef,
    generated: Option[GeneratedSymbolSelectionRecord] = None,
    scope: Option[DigitalValueRegionRecord] = None
)
