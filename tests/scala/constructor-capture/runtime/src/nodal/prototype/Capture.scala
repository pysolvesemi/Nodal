package nodal.prototype

import scala.collection.mutable.ArrayBuffer

/** Reference identity in a single trace, deliberately not a production node ID. */
final class ModuleIdentity private[prototype] (
    val ordinal: Int,
    val classId: String,
    val site: String,
    val parent: Option[ModuleIdentity]
):
  override def toString: String = s"$classId#$ordinal@$site"

final case class Declaration(
    name: String,
    defaultValue: Double,
    carrier: Param[Real],
    actual: Option[Param[Real]]
)

final case class Allocation(
    identity: ModuleIdentity,
    encodedSchema: String,
    declarations: Vector[Declaration]
)

sealed trait CaptureEvent
final case class OmittedDefaultGetter(value: Double) extends CaptureEvent
final case class Prepared(classId: String, site: String, parent: Option[ModuleIdentity])
    extends CaptureEvent
final case class Begun(identity: ModuleIdentity, declarationCount: Int) extends CaptureEvent
final case class Committed(identity: ModuleIdentity) extends CaptureEvent
final case class RolledBack(
    classId: String,
    site: String,
    discarded: Vector[ModuleIdentity],
    exceptionClass: String
) extends CaptureEvent

final case class Observation[+A](
    outcome: Either[Throwable, A],
    allocations: Vector[Allocation],
    events: Vector[CaptureEvent],
    pendingDepth: Int
):
  def value: A = outcome match
    case Right(result) => result
    case Left(failure) => scala.util.Failure[Nothing](failure).get

/**
 * A compiler lifecycle trace/assertion probe only. It has no hierarchy lookup,
 * topology, parameter expression evaluator, bridge, IR, or HDL implementation.
 * Metadata is recorded verbatim; it is never parsed to infer ownership.
 */
object Capture:
  private final class Pending(
      val classId: String,
      val site: String,
      val encoded: String,
      val parent: Option[ModuleIdentity]
  ):
    val carriers = ArrayBuffer.empty[Param[Real]]
    val nested = ArrayBuffer.empty[Allocation]
    var module: Option[Module] = None
    var identity: Option[ModuleIdentity] = None

  private final class State:
    var stack: List[Pending] = Nil
    var nextOrdinal = 0
    val events = ArrayBuffer.empty[CaptureEvent]
    val committed = ArrayBuffer.empty[Allocation]

  private val current = new ThreadLocal[State]

  private def state: State =
    Option(current.get()).getOrElse(
      scala.util.Failure[Nothing](
        new IllegalStateException("constructor probe requires Capture.observe")
      ).get
    )

  def activeDepth: Int = Option(current.get()).fold(0)(_.stack.size)

  def activeModule: Option[ModuleIdentity] =
    Option(current.get()).flatMap(_.stack.headOption.flatMap(_.identity))

  /** Each observation isolates its trace and restores a surrounding observation. */
  def observe[A](label: String)(body: => A): Observation[A] =
    require(label.nonEmpty, "a trace label is required")
    val previous = Option(current.get())
    val fresh = new State
    current.set(fresh)
    try
      val result =
        try Right(body)
        catch case failure: Throwable => Left(failure)
      Observation(result, fresh.committed.toVector, fresh.events.toVector, fresh.stack.size)
    finally
      previous match
        case Some(outer) => current.set(outer)
        case None        => current.remove()

  /** The rewritten pure default getter returns this inert omission marker. */
  def omitted(defaultValue: Double): Param[Real] =
    Option(current.get()).foreach(_.events += OmittedDefaultGetter(defaultValue))
    new Param[Real](Some(defaultValue), true, None, None, None)

  /** Called inside allocate's thunk, after original host arguments were evaluated. */
  def carrier(name: String, defaultValue: Double, actual: Param[Real]): Param[Real] =
    val pending = state.stack.headOption.getOrElse(
      scala.util.Failure[Nothing](
        new IllegalStateException("constructor carrier requires pending allocation")
      ).get
    )
    require(pending.module.isEmpty, "constructor carrier must precede Module.begin")
    require(!pending.carriers.exists(_.carrierName.contains(name)), "duplicate carrier name")
    val fresh = new Param[Real](
      None,
      false,
      Some(name),
      Some(defaultValue),
      if actual.omittedDefault then None else Some(actual)
    )
    pending.carriers += fresh
    fresh

  /** Called by the base constructor, before subclass bodies or child allocation. */
  def begin(module: Module): ModuleIdentity =
    val active = state
    val pending = active.stack.headOption.getOrElse(
      scala.util.Failure[Nothing](
        new IllegalStateException("Module constructor was not captured by the plugin")
      ).get
    )
    require(pending.module.isEmpty, "one allocation can begin exactly one Module")
    active.nextOrdinal += 1
    val identity = new ModuleIdentity(
      active.nextOrdinal,
      pending.classId,
      pending.site,
      pending.parent
    )
    pending.module = Some(module)
    pending.identity = Some(identity)
    pending.carriers.foreach(_.bind(identity))
    active.events += Begun(identity, pending.carriers.size)
    identity

  /**
   * Commit is local to an allocation: nested records remain staged until their
   * outer allocation succeeds. A failed outer body discards those records too.
   */
  def allocate[A <: Module](
      classId: String,
      site: String,
      encoded: String,
      thunk: () => A
  ): A =
    val active = state
    val pending = new Pending(classId, site, encoded, activeModule)
    val previous = active.stack
    active.stack = pending :: previous
    active.events += Prepared(classId, site, pending.parent)
    try
      val result = thunk()
      require(pending.module.exists(_ eq result), "allocation returned a different Module")
      require(active.stack.headOption.contains(pending), "allocation stack was not restored")
      val identity = pending.identity.getOrElse(
        scala.util.Failure[Nothing](
          new IllegalStateException("allocation did not enter Module.begin")
        ).get
      )
      val declarations = pending.carriers.toVector.map { parameter =>
        Declaration(
          parameter.carrierName.get,
          parameter.declarationDefault.get,
          parameter,
          parameter.actual
        )
      }
      val records = Allocation(identity, encoded, declarations) +: pending.nested.toVector
      previous.headOption match
        case Some(parent) => parent.nested ++= records
        case None         => active.committed ++= records
      active.events += Committed(identity)
      result
    catch
      case failure: Throwable =>
        pending.carriers.foreach(_.unbind())
        pending.nested.foreach(_.declarations.foreach(_.carrier.unbind()))
        val discarded = pending.identity.toVector ++ pending.nested.map(_.identity)
        active.events += RolledBack(classId, site, discarded.toVector, failure.getClass.getName)
        scala.util.Failure[Nothing](failure).get
    finally active.stack = previous
