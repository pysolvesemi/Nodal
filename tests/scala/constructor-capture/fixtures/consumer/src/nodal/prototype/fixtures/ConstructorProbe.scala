package nodal.prototype.fixtures

import nodal.prototype.*
import nodal.prototype.fixtures.factory.PackagedFactory
import scala.language.implicitConversions

// A consumer-defined Module calls both a previously compiled class and factory.
class FactoryTop(topGain: Param[Real] = 4.0) extends Module:
  val observedTopGain = topGain
  val direct = new GainStage(gain = ProbeEffects.mark("consumer.argument", topGain))
  val packaged = PackagedFactory.stage(topGain)

object ConstructorProbe:
  private var assertions = 0
  private var cases = 0

  private def check(condition: Boolean, clue: String): Unit =
    assertions += 1
    if !condition then scala.util.Failure[Nothing](new AssertionError(clue)).get

  private def test(label: String)(body: => Unit): Unit =
    ProbeEffects.reset()
    check(Capture.activeDepth == 0, s"$label started with an active allocation")
    body
    check(Capture.activeDepth == 0, s"$label leaked an active allocation")
    cases += 1
    println(s"PASS $label")

  private def successful[A](observation: Observation[A]): A =
    check(observation.pendingDepth == 0, "successful observation left a pending allocation")
    check(observation.outcome.isRight, s"unexpected failure: ${observation.outcome}")
    observation.value

  private def record(observation: Observation[?], module: Module): Allocation =
    val matches = observation.allocations.filter(_.identity eq module.captureIdentity)
    check(matches.size == 1, "expected exactly one record for this module identity")
    matches.head

  private def declaration(observation: Observation[?], module: Module, name: String): Declaration =
    val matches = record(observation, module).declarations.filter(_.name == name)
    check(matches.size == 1, s"expected exactly one declaration named $name")
    matches.head

  private def sameBits(left: Double, right: Double): Boolean =
    java.lang.Double.doubleToRawLongBits(left) == java.lang.Double.doubleToRawLongBits(right)

  def main(args: Array[String]): Unit =
    test("inert literal lifting") {
      val literal: Param[Real] = 2.0
      check(literal.literalValue.contains(2.0), "literal lifting lost the Double")
      check(literal.owner.isEmpty, "literal lifting allocated a declaration")
      check(literal.declarationDefault.isEmpty, "literal became a constructor declaration")
      check(literal.actual.isEmpty, "literal gained an actual reference")
    }

    test("exact syntax, independent default, fresh children, helper") {
      val observation = Capture.observe("ordinary new") { new Top() }
      val top = successful(observation)
      check(observation.allocations.size == 6, "ordinary constructor/helper allocation count")
      val parent = declaration(observation, top, "topGain")
      val amp = declaration(observation, top.amp, "gain")
      val another = declaration(observation, top.another, "gain")
      check(sameBits(parent.defaultValue, 4.0), "parent default was changed")
      check(sameBits(amp.defaultValue, 2.0), "child declaration default used supplied actual")
      check(amp.carrier eq top.amp.observedGain, "body did not receive child carrier")
      check(amp.carrier.owner.contains(top.amp.captureIdentity), "child does not own its carrier")
      check(another.carrier.owner.contains(top.another.captureIdentity), "second child owner")
      check(!(amp.carrier eq top.observedTopGain), "child aliases the parent's Param")
      check(!(amp.carrier eq another.carrier), "two children share one declaration")
      check(amp.actual.exists(_ eq top.observedTopGain), "parent actual identity was lost")
      check(another.actual.exists(_ eq top.observedTopGain), "second parent actual identity")
      check(top.observedTopGain.owner.contains(top.captureIdentity), "parent ownership changed")
      check(top.amp.captureIdentity.parent.contains(top.captureIdentity), "automatic attachment")
      check(top.amp.enteredWithOwner && top.amp.enteredAsActive, "begin must precede class body")
      check(record(observation, top.amp).encodedSchema == "gain:4000000000000000", "schema default")
      check(record(observation, top.amp).identity.site.nonEmpty, "constructor call site was lost")
      check(
        top.amp.captureIdentity.classId == top.another.captureIdentity.classId,
        "same class identity"
      )
      check(ProbeEffects.calls.toVector == Vector("helper.argument"), "helper argument evaluation")
      val helper = declaration(observation, top.viaHelper, "gain")
      check(helper.actual.exists(_ eq top.observedTopGain), "helper lost parent actual")
      check(top.viaHelper.captureIdentity.parent.contains(top.captureIdentity), "helper attachment")
    }

    test("omitted default differs from explicit equal literal") {
      val observation = Capture.observe("default distinction") { new Top() }
      val top = successful(observation)
      check(observation.events.collect { case event: OmittedDefaultGetter => event.value } ==
        Vector(4.0, 2.0), "parent and omitted child getters must each execute once")
      val omitted = declaration(observation, top.omitted, "gain")
      val explicit = declaration(observation, top.explicitDefault, "gain")
      check(omitted.actual.isEmpty, "omitted default was represented as an override")
      check(sameBits(omitted.defaultValue, 2.0), "omitted declaration default")
      check(
        explicit.actual.flatMap(_.literalValue).contains(2.0),
        "explicit equal literal was dropped"
      )
      check(explicit.actual.flatMap(_.owner).isEmpty, "literal actual acquired child ownership")
      check(sameBits(explicit.defaultValue, 2.0), "explicit actual changed default")
    }

    test("named arguments retain host order and execute once") {
      val observation = Capture.observe("named order") { new OrderedTop() }
      val top = successful(observation)
      check(
        ProbeEffects.calls.toVector == Vector("second", "first"),
        "named argument order/count changed"
      )
      val first = declaration(observation, top.stage, "first")
      val second = declaration(observation, top.stage, "second")
      check(
        !(top.observedFirstGain eq top.observedSecondGain),
        "order probe needs distinct actuals"
      )
      check(first.actual.exists(_ eq top.observedFirstGain), "first binding reordered")
      check(second.actual.exists(_ eq top.observedSecondGain), "second binding reordered")
      check(
        sameBits(first.defaultValue, 2.0) && sameBits(second.defaultValue, 3.0),
        "ordered defaults"
      )
      check(
        record(observation, top.stage).declarations.map(_.name) == Vector("first", "second"),
        "declaration order"
      )
    }

    test("top-level omitted and explicit constructors") {
      val omittedObservation = Capture.observe("top-level omitted") { new GainStage() }
      val omitted = successful(omittedObservation)
      check(omittedObservation.events.count(_.isInstanceOf[OmittedDefaultGetter]) == 1,
        "omitted constructor must invoke its getter once")
      check(omitted.captureIdentity.parent.isEmpty, "top-level module has a parent")
      check(declaration(omittedObservation, omitted, "gain").actual.isEmpty, "top-level default")
      val literal: Param[Real] = 9.0
      val explicitObservation = Capture.observe("top-level explicit") {
        new GainStage(gain = ProbeEffects.mark("top-level.argument", literal))
      }
      val explicit = successful(explicitObservation)
      check(explicitObservation.events.count(_.isInstanceOf[OmittedDefaultGetter]) == 0,
        "explicit actual must not invoke a default getter")
      val bound = declaration(explicitObservation, explicit, "gain")
      check(bound.actual.exists(_ eq literal), "top-level literal reference")
      check(sameBits(bound.defaultValue, 2.0), "top-level explicit value replaced default")
      check(literal.owner.isEmpty, "top-level actual was mutated")
      check(
        ProbeEffects.calls.toVector == Vector("top-level.argument"),
        "top-level host evaluation count"
      )
    }

    test("separate definitions and packaged factory") {
      val observation = Capture.observe("jar boundaries") { new FactoryTop() }
      val top = successful(observation)
      check(observation.allocations.size == 3, "separate compilation allocation count")
      check(
        ProbeEffects.calls.toVector == Vector("consumer.argument", "packaged.argument"),
        "packaged host count/order"
      )
      for child <- Vector(top.direct, top.packaged) do
        val gain = declaration(observation, child, "gain")
        check(gain.actual.exists(_ eq top.observedTopGain), "cross-jar parent reference")
        check(sameBits(gain.defaultValue, 2.0), "cross-jar metadata default")
        check(child.captureIdentity.parent.contains(top.captureIdentity), "cross-jar attachment")
      check(
        top.packaged.captureIdentity.site.contains("PackagedFactory.scala"),
        "factory allocation source"
      )
    }

    test("body failure discards nested allocations and fresh observation recovers") {
      ProbeEffects.bodyFailureEnabled = true
      val failed = Capture.observe("failed outer body") { new BodyFailure() }
      check(
        failed.outcome.left.toOption.exists(_.isInstanceOf[BodyFailureException]),
        "body failure did not escape"
      )
      check(failed.allocations.isEmpty, "body failure retained staged child records")
      check(failed.pendingDepth == 0, "body failure retained pending allocation")
      check(
        ProbeEffects.escapedFailedParameter.exists(_.owner.isEmpty),
        "failed carrier kept owner"
      )
      check(
        ProbeEffects.escapedFailedChildParameter.exists(_.owner.isEmpty),
        "failed descendant kept owner"
      )
      check(
        failed.events.collect { case event: RolledBack => event }.exists(_.discarded.size == 2),
        "rollback trace omitted descendant"
      )
      ProbeEffects.bodyFailureEnabled = false
      val recovered = Capture.observe("fresh after failure") { new GainStage() }
      val module = successful(recovered)
      check(
        module.captureIdentity.ordinal == 1,
        "fresh observation retained failed identity counter"
      )
      check(recovered.allocations.size == 1, "fresh observation retained failed records")
    }

    test("caught body failure restores enclosing construction") {
      ProbeEffects.bodyFailureEnabled = true
      val observation = Capture.observe("caught child failure") { new RecoveringTop() }
      val top = successful(observation)
      check(observation.allocations.size == 2, "caught child failure retained discarded records")
      check(
        top.after.captureIdentity.parent.contains(top.captureIdentity),
        "caught failure lost parent"
      )
      check(
        top.observedTopGain.owner.contains(top.captureIdentity),
        "rollback unbound parent's actual"
      )
      check(
        declaration(observation, top.after, "gain").actual.exists(_ eq top.observedTopGain),
        "recovery binding"
      )
      check(
        ProbeEffects.calls.toVector == Vector("body.check", "body.caught"),
        "caught body effect count"
      )
      check(
        ProbeEffects.escapedFailedParameter.exists(_.owner.isEmpty),
        "caught failure carrier owner"
      )
    }

    test("throwing host argument precedes allocation and fresh construction recovers") {
      val failed = Capture.observe("argument failure") {
        new GainStage(gain = ProbeEffects.failArgument(Param.literal(8.0)))
      }
      check(
        failed.outcome.left.toOption.exists(_.isInstanceOf[ArgumentFailure]),
        "argument failure did not escape"
      )
      check(failed.events.isEmpty, "allocation started before original host argument evaluation")
      check(failed.allocations.isEmpty && failed.pendingDepth == 0, "argument failure state leaked")
      check(
        ProbeEffects.calls.toVector == Vector("argument.throw"),
        "argument evaluated more than once"
      )
      ProbeEffects.calls.clear()
      val recovered = Capture.observe("caught host argument failure") { new ArgumentRecovery() }
      val top = successful(recovered)
      check(recovered.allocations.size == 2, "failed argument created a child allocation")
      check(
        recovered.events.count(_.isInstanceOf[Prepared]) == 2,
        "failed argument entered pending stack"
      )
      check(
        top.after.captureIdentity.parent.contains(top.captureIdentity),
        "argument failure lost parent"
      )
      check(
        top.observedTopGain.owner.contains(top.captureIdentity),
        "argument failure changed parent owner"
      )
      check(
        ProbeEffects.calls.toVector == Vector("argument.throw", "argument.caught"),
        "caught argument evaluation count"
      )
    }

    test("nested constructors restore exact parent") {
      val observation = Capture.observe("nested hierarchy trace") { new NestedTop() }
      val top = successful(observation)
      check(observation.allocations.size == 4, "nested allocation count")
      check(top.branch.captureIdentity.parent.contains(top.captureIdentity), "branch parent")
      check(
        top.branch.leaf.captureIdentity.parent.contains(top.branch.captureIdentity),
        "leaf immediate parent"
      )
      check(
        top.afterBranch.captureIdentity.parent.contains(top.captureIdentity),
        "parent not restored after branch"
      )
      check(
        declaration(observation, top.branch.leaf, "gain").actual
          .exists(_ eq top.branch.observedGain),
        "leaf actual owner"
      )
      check(
        top.branch.observedGain.owner.contains(top.branch.captureIdentity),
        "branch parameter owner"
      )
      check(
        declaration(observation, top.afterBranch, "gain").actual.exists(_ eq top.observedTopGain),
        "post-branch actual"
      )
    }

    test("zero-argument module and nested isolated observation") {
      val observation = Capture.observe("outer isolated observation") { new IsolatedTop() }
      val top = successful(observation)
      val isolated = successful(top.isolated)
      check(
        record(observation, top).declarations.isEmpty,
        "zero-argument module acquired a parameter"
      )
      check(record(observation, top).encodedSchema.isEmpty, "zero-argument schema must be empty")
      check(
        observation.allocations.size == 2 && top.isolated.allocations.size == 1,
        "isolated traces merged"
      )
      check(isolated.captureIdentity.parent.isEmpty, "isolated construction acquired outer parent")
      check(
        top.afterIsolation.captureIdentity.parent.contains(top.captureIdentity),
        "outer state was not restored"
      )
      check(!(isolated.captureIdentity eq top.captureIdentity), "isolated sessions shared identity")
    }

    println(s"CONSTRUCTOR_PROBE_PASS cases=$cases assertions=$assertions")

