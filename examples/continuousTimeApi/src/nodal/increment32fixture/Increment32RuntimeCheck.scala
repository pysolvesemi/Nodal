package nodal.increment32fixture

import nodal.AnalogEquationRuntime.*

/** Executable semantic witness for Increment 32. */
object Increment32RuntimeCheck:
  def main(arguments: Array[String]): Unit =
    val _ = arguments
    val first = build(Vector("source-a", "source-b"))
    val second = build(Vector("source-b", "source-a"))

    assert(first == second, "contribution order must not affect the canonical snapshot")
    assert(first.equations.size == 2)
    assert(first.equations.head.identity.value == "dc-law")
    assert(first.equations.head.residual.authoredLeft.rendered == "V(p,n)")
    assert(first.equations.head.residual.authoredRight.rendered == "R * I(p,n)")
    assert(!first.equations.head.residual.causallyOriented)
    assert(!first.equations.head.residual.divided)
    assert(first.contributions.size == 1)
    assert(first.contributions.head.terms.map(_.identity.value) == Vector("source-a", "source-b"))

    val duplicate = new Recorder
    val duplicateError = duplicate.region(RegionKind.Equation) {
      val recorded = duplicate.recordEquation(
        EquationIdentity("same"),
        real("left", "V"),
        real("right", "V"),
        metadata(20)
      )
      assert(recorded.isRight)
      duplicate.recordEquation(
        EquationIdentity("same"),
        real("again", "V"),
        real("again", "V"),
        metadata(21)
      )
    }
    assert(
      duplicateError.exists(_.left.exists(_.code == "NODAL-ANALOG-032-004")),
      "duplicate identities must fail with a stable diagnostic"
    )

    val procedural = new Recorder
    val proceduralError = procedural.region(RegionKind.Procedural) {
      procedural.recordContribution(
        ContributionIdentity("illegal"),
        target,
        real("1.0", "A"),
        metadata(30)
      )
    }
    assert(
      proceduralError.exists(_.left.exists(_.code == "NODAL-ANALOG-032-012")),
      "procedural contribution misuse must fail closed"
    )

  private def build(order: Vector[String]): Snapshot =
    val recorder = new Recorder
    assert(
      recorder.region(RegionKind.Equation) {
        assert(
          recorder
            .recordEquation(
              EquationIdentity("dc-law"),
              real("V(p,n)", "V"),
              real("R * I(p,n)", "V"),
              metadata(10)
            )
            .isRight
        )
      }.isRight
    )
    assert(
      recorder.region(RegionKind.InitialEquation) {
        assert(
          recorder
            .recordEquation(
              EquationIdentity("initial-voltage"),
              real("V(p,n)", "V"),
              real("0.0", "V"),
              metadata(11)
            )
            .isRight
        )
      }.isRight
    )
    assert(
      recorder.region(RegionKind.Contribution) {
        order.foreach { identity =>
          val magnitude = if identity == "source-a" then "1.0" else "2.0"
          assert(
            recorder
              .recordContribution(
                ContributionIdentity(identity),
                target,
                real(magnitude, "A"),
                metadata(12)
              )
              .isRight
          )
        }
      }.isRight
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
