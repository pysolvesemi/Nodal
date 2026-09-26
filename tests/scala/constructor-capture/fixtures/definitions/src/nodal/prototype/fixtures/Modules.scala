package nodal.prototype.fixtures

import nodal.prototype.*
import scala.collection.mutable.ArrayBuffer
import scala.language.implicitConversions

/** Host effects are assertions about Scala evaluation, not captured HDL effects. */
object ProbeEffects:
  val calls = ArrayBuffer.empty[String]
  var bodyFailureEnabled = false
  var escapedFailedParameter: Option[Param[Real]] = None
  var escapedFailedChildParameter: Option[Param[Real]] = None

  def reset(): Unit =
    calls.clear()
    bodyFailureEnabled = false
    escapedFailedParameter = None
    escapedFailedChildParameter = None

  def mark(label: String, actual: Param[Real]): Param[Real] =
    calls += label
    actual

  def failArgument(actual: Param[Real]): Param[Real] =
    calls += "argument.throw"
    scala.util.Failure[Nothing](new ArgumentFailure).get

  def failBody(): Unit =
    calls += "body.check"
    if bodyFailureEnabled then scala.util.Failure[Nothing](new BodyFailureException).get

final class ArgumentFailure extends RuntimeException("intentional argument failure")
final class BodyFailureException extends RuntimeException("intentional body failure")

// This is the exact constructor spelling required by the approved design gate.
class GainStage(gain: Param[Real] = 2.0) extends Module:
  val observedGain = gain
  val enteredWithOwner = gain.owner.contains(captureIdentity)
  val enteredAsActive = Capture.activeModule.contains(captureIdentity)

class TwoGains(first: Param[Real] = 2.0, second: Param[Real] = 3.0) extends Module:
  val observedFirst = first
  val observedSecond = second

object Helpers:
  def stage(actual: Param[Real]): GainStage =
    new GainStage(gain = ProbeEffects.mark("helper.argument", actual))

class Top(topGain: Param[Real] = 4.0) extends Module:
  val observedTopGain = topGain
  val amp = new GainStage(gain = topGain)
  val another = new GainStage(gain = topGain)
  val omitted = new GainStage()
  val explicitDefault = new GainStage(gain = 2.0)
  val viaHelper = Helpers.stage(topGain)

class OrderedTop(firstGain: Param[Real] = 4.0, secondGain: Param[Real] = 5.0) extends Module:
  val observedFirstGain = firstGain
  val observedSecondGain = secondGain
  val stage = new TwoGains(
    second = ProbeEffects.mark("second", secondGain),
    first = ProbeEffects.mark("first", firstGain)
  )

class BodyFailure(gain: Param[Real] = 7.0) extends Module:
  val child = new GainStage(gain = gain)
  ProbeEffects.escapedFailedParameter = Some(gain)
  ProbeEffects.escapedFailedChildParameter = Some(child.observedGain)
  ProbeEffects.failBody()

class RecoveringTop(topGain: Param[Real] = 4.0) extends Module:
  val observedTopGain = topGain
  try new BodyFailure(gain = topGain)
  catch case _: BodyFailureException => ProbeEffects.calls += "body.caught"
  val after = new GainStage(gain = topGain)

class ArgumentRecovery(topGain: Param[Real] = 4.0) extends Module:
  val observedTopGain = topGain
  try new GainStage(gain = ProbeEffects.failArgument(topGain))
  catch case _: ArgumentFailure => ProbeEffects.calls += "argument.caught"
  val after = new GainStage(gain = topGain)

class Branch(gain: Param[Real] = 6.0) extends Module:
  val observedGain = gain
  val leaf = new GainStage(gain = gain)

class NestedTop(topGain: Param[Real] = 4.0) extends Module:
  val observedTopGain = topGain
  val branch = new Branch(gain = topGain)
  val afterBranch = new GainStage(gain = topGain)

class IsolatedTop extends Module:
  val isolated = Capture.observe("nested isolated observation") { new GainStage() }
  val afterIsolation = new GainStage()
