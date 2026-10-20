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
    members: Vector[KernelAreaMemberSnapshot]
)

/** Owned solely by ConstructionSession, alongside its declaration and instance records. */
private[nodal] final class AreaRecord(
    val owner: Long,
    val source: SourceSpan,
    val parent: Option[AreaRecord],
    val domain: Option[ClockDomain]
):
  var value: Option[Area] = None
  var name: Option[String] = None
  var namePriority: Int = -1
  val aliases: mutable.ArrayBuffer[String] = mutable.ArrayBuffer.empty
  val members: mutable.LinkedHashMap[String, Any] = mutable.LinkedHashMap.empty
  val declarations: mutable.ArrayBuffer[DeclarationRef] = mutable.ArrayBuffer.empty
  val instances: mutable.ArrayBuffer[InstanceRecord] = mutable.ArrayBuffer.empty
