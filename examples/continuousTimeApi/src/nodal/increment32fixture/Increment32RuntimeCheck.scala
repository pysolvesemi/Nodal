package nodal.increment32fixture

import nodal.AnalogEquationRuntime.*

/** Executable semantic witness for Increment 32. */
object Increment32RuntimeCheck:
  def main(arguments: Array[String]): Unit =
    val _ = arguments
    try runChecks()
    catch
      case error: Throwable =>
        error.printStackTrace(System.err)
        System.err.flush()
        throw error

  private def runChecks(): Unit =
    val first = build(Vector("source-a", "source-b"))
    val second = build(Vector("source-b", "source-a"))

    check(first == second, s"source order must not affect the canonical snapshot: first=$first second=$second")
    check(first.equations.size == 2, s"expected two equations, got ${first.equations}")
    check(
      first.equations.head.identity.value == "dc-law",
      s"expected dc-law first, got ${first.equations.map(_.identity.value)}"
    )
    check(
      first.equations.head.residual.authoredLeft.rendered == "V(p,n)",
      s"authored left expression was not retained: ${first.equations.head.residual}"
    )
    check(
      first.equations.head.residual.authoredRight.rendered == "R * I(p,n)",
      s"authored right expression was not retained: ${first.equations.head.residual}"
    )
    check(
      !first.equations.head.residual.causallyOriented,
      s"equation was causally oriented: ${first.equations.head.residual}"
    )
    check(
      !first.equations.head.residual.divided,
      s"equation was divided: ${first.equations.head.residual}"
    )
    val initial = first.equations.find(_.identity.value == "initial-voltage")
    check(initial.exists(_.initialOnly), s"initial equation classification was lost: $initial")
    check(first.contributions.size == 1, s"expected one contribution bucket: ${first.contributions}")
    check(
      first.contributions.head.terms.map(_.identity.value) == Vector("source-a", "source-b"),
      s"contribution terms are not canonically ordered: ${first.contributions.head.terms}"
    )

    val duplicate = new Recorder
    val duplicateResult = duplicate.region(RegionKind.Equation) {
      val recorded = duplicate.recordEquation(
        EquationIdentity("same"),
        real("left", "V"),
        real("right", "V"),
        metadata(20)
      )
      check(recorded.isRight, s"first duplicate fixture equation failed unexpectedly: $recorded")
      duplicate.recordEquation(
        EquationIdentity("same"),
        real("again", "V"),
        real("again", "V"),
        metadata(21)
      )
    }
    duplicateResult match
      case Right(Left(error)) =>
        check(
          error.code == "NODAL-ANALOG-032-004",
          s"duplicate identities must fail with NODAL-ANALOG-032-004, got $error"
        )
      case other =>
        fail(s"duplicate identity fixture did not return the expected nested diagnostic: $other")

    val procedural = new Recorder
    val proceduralResult = procedural.region(RegionKind.Procedural) {
      procedural.recordContribution(
        ContributionIdentity("illegal"),
        target,
        real("1.0", "A"),
        metadata(30)
      )
    }
    proceduralResult match
      case Right(Left(error)) =>
        check(
          error.code == "NODAL-ANALOG-032-012",
          s"procedural contribution misuse must fail with NODAL-ANALOG-032-012, got $error"
        )
      case other =>
        fail(s"procedural contribution fixture did not return the expected nested diagnostic: $other")

  private def build(order: Vector[String]): Snapshot =
    val recorder = new Recorder
    check(
      recorder.region(RegionKind.Equation) {
        check(
          recorder
            .recordEquation(
              EquationIdentity("dc-law"),
              real("V(p,n)", "V"),
              real("R * I(p,n)", "V"),
              metadata(10)
            )
            .isRight,
          "dc-law equation must record successfully"
        )
      }.isRight,
      "ordinary equation region must complete successfully"
    )
    check(
      recorder.region(RegionKind.InitialEquation) {
        check(
          recorder
            .recordEquation(
              EquationIdentity("initial-voltage"),
              real("V(p,n)", "V"),
              real("0.0", "V"),
              metadata(11)
            )
            .isRight,
          "initial-voltage equation must record successfully"
        )
      }.isRight,
      "initial equation region must complete successfully"
    )
    check(
      recorder.region(RegionKind.Contribution) {
        order.foreach { identity =>
          val magnitude = if identity == "source-a" then "1.0" else "2.0"
          check(
            recorder
              .recordContribution(
                ContributionIdentity(identity),
                target,
                real(magnitude, "A"),
                metadata(12)
              )
              .isRight,
            s"contribution $identity must record successfully"
          )
        }
      }.isRight,
      "contribution region must complete successfully"
    )
    recorder.snapshot

  private def target: ContributionTarget =
    ContributionTarget("branch:p->n", ContributionKind.Flow, "A", "p-to-n")

  private def real(rendered: String, dimension: String): Expression =
    Expression(rendered, dimension, ValueKind.Real)

  private def metadata(line: Int): Metadata =
    Metadata(
      owner = "fixture.resistor",
      guard = None,
      analyses = Set("dc", "transient"),
      continuity = "continuous",
      source = SourceSpan("Increment32RuntimeCheck.scala", line, 1)
    )

  private def check(condition: Boolean, message: => String): Unit =
    if !condition then fail(message)

  private def fail(message: String): Nothing =
    throw new IllegalStateException(message)
