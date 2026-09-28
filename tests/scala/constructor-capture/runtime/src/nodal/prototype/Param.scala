package nodal.prototype

import scala.language.implicitConversions

/** A type marker for the bounded compiler experiment, not a Nodal numeric type. */
sealed trait Real

/** An inert expression or constructor carrier. Creating either kind has no declaration or
  * allocation side effect. Only Capture.begin binds a carrier.
  */
final class Param[A] private[prototype] (
    val literalValue: Option[Double],
    private[prototype] val omittedDefault: Boolean,
    private[prototype] val carrierName: Option[String],
    val declarationDefault: Option[Double],
    val actual: Option[Param[Real]]
):
  private var boundOwner: Option[ModuleIdentity] = None

  def owner: Option[ModuleIdentity] = boundOwner

  private[prototype] def bind(identity: ModuleIdentity): Unit =
    require(carrierName.nonEmpty, "only a constructor carrier can be bound")
    require(boundOwner.isEmpty, "a constructor carrier must be fresh")
    boundOwner = Some(identity)

  private[prototype] def unbind(): Unit =
    boundOwner = None

object Param:
  def literal(value: Double): Param[Real] =
    new Param[Real](Some(value), false, None, None, None)

  // The compiler prototype matches this exact symbol, not a spelling heuristic.
  implicit def liftReal(value: Double): Param[Real] = literal(value)
