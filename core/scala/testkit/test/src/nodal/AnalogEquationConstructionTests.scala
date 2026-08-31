package nodal.internal.testkit

import nodal.*
import nodal.internal.bridge.{ReproducibilityContract, ScalaToMlirBridge}

import utest.*

final class PublicAnalogEquationTop(reverseContributions: Boolean = false) extends Module:
  val positive: Terminal[Electrical.type] = terminal(Electrical, "positive")
  val negative: Terminal[Electrical.type] = terminal(Electrical, "negative")
  val path: Branch[Electrical.type] = branch(positive, negative, "device_path")
  val resistance: Param[Real] = param(10.0.kOhm)

  equations:
    equation(
      path.potential,
      resistance * path.flow,
      EquationOptions(
        id = Some(EquationId("ohm-law")),
        analyses = AnalysisApplicability.only(
          AnalysisKind.Dc,
          AnalysisKind.OperatingPoint,
          AnalysisKind.Transient
        ),
        continuity = ContinuityClass.C1
      )
    )

  initialEquations:
    initialEquation(
      path.potential,
      0.0.V,
      InitialEquationOptions(id = Some(EquationId("initial-charge")))
    )

  contributions:
    def sourceA(): Unit =
      contribution(
        path.flow,
        1.0.A,
        ContributionOptions(id = Some(ContributionId("source-a")))
      )

    def sourceB(): Unit =
      contribution(
        path.flow,
        2.0.A,
        ContributionOptions(id = Some(ContributionId("source-b")))
      )

    if reverseContributions then
      sourceB()
      sourceA()
    else
      sourceA()
      sourceB()

    path.flow <+ path.potential / resistance
final class EquationOutsideRegion extends Module:
  equation(
    1.0.V,
    1.0.V,
    EquationOptions(id = Some(EquationId("outside")))
  )

final class NestedAnalogSemanticRegion extends Module:
  val positive: Terminal[Electrical.type] = terminal(Electrical, "positive")
  val negative: Terminal[Electrical.type] = terminal(Electrical, "negative")
  val path: Branch[Electrical.type] = branch(positive, negative, "path")

  equations:
    contributions:
      path.flow <+ 1.0.A

final class DimensionMismatchEquation extends Module:
  equations:
    equation(
      1.0.V,
      1.0.A,
      EquationOptions(id = Some(EquationId("bad-dimension")))
    )

final class DeclarativeAssignment extends Module:
  val scratch: Variable[Real] = variable(Real, 0.0.real)

  equations:
    scratch := 1.0.real

final class InvalidContributionTarget extends Module:
  contributions:
    contribution(
      1.0.A,
      1.0.A,
      ContributionOptions(id = Some(ContributionId("invalid-target")))
    )

object AnalogEquationConstructionTests extends TestSuite:
  val tests: Tests = Tests:
    test("public APIs populate the construction-session recorder"):
      val snapshot = ConstructionKernel.inspect(new PublicAnalogEquationTop)
      val semantics = snapshot.analogSemantics

      assert(semantics.equations.size == 2)
      assert(semantics.equations.exists(_.identity.value.endsWith(".ohm-law")))
      val initial = semantics.equations.find(_.identity.value.endsWith(".initial-charge")).get
      assert(initial.initialOnly)
      assert(initial.metadata.analyses == Set("initialization"))
      assert(initial.residual.authoredLeft.dimension == "voltage")
      assert(initial.residual.authoredRight.dimension == "voltage")
      assert(!initial.residual.causallyOriented)
      assert(!initial.residual.divided)

      assert(semantics.contributions.size == 1)
      val bucket = semantics.contributions.head
      assert(bucket.target.kind == AnalogEquationRuntime.ContributionKind.Flow)
      assert(bucket.target.dimension == "current")
      assert(bucket.target.orientation.contains("positive"))
      assert(bucket.target.orientation.contains("negative"))
      assert(bucket.terms.size == 3)
      assert(bucket.terms.exists(_.identity.value.endsWith(".source-a")))
      assert(bucket.terms.exists(_.identity.value.endsWith(".source-b")))

      val mlir = ScalaToMlirBridge.lower(new PublicAnalogEquationTop)
      assert(mlir.text.contains("nodal.bridge.analog_semantics"))
      assert(mlir.text.contains("ohm-law"))
      assert(mlir.text.contains("lhs-minus-rhs-equals-zero"))
      assert(mlir.text.contains("target_orientation"))

      val reproducible = ReproducibilityContract.canonicalSnapshot(snapshot)
      assert(reproducible.contains("\"analog_semantics\""))
      assert(reproducible.contains("\"identity\": \"PublicAnalogEquationTop.ohm-law\""))
      assert(reproducible.contains("\"target_kind\": \"flow\""))

    test("contribution grouping is independent of public source order"):
      val forward = ConstructionKernel.inspect(new PublicAnalogEquationTop()).analogSemantics
      val reverse =
        ConstructionKernel
          .inspect(new PublicAnalogEquationTop(reverseContributions = true))
          .analogSemantics

      def identities(snapshot: AnalogEquationRuntime.Snapshot): Vector[String] =
        snapshot.contributions.flatMap(_.terms.map(_.identity.value))

      assert(identities(forward) == identities(reverse))
      assert(forward.contributions.map(_.target) == reverse.contributions.map(_.target))

    test("equation outside a region is rejected"):
      val failure = scala.util.Try(ConstructionKernel.inspect(new EquationOutsideRegion)).failed.get
        .asInstanceOf[ConstructionException]
      assert(failure.diagnostic.code == "NODAL-ANALOG-032-003")

    test("overlapping public semantic regions are rejected"):
      val failure =
        scala.util.Try(ConstructionKernel.inspect(new NestedAnalogSemanticRegion)).failed.get
          .asInstanceOf[ConstructionException]
      assert(failure.diagnostic.code == "NODAL-ANALOG-032-001")

    test("public equation dimensions are validated"):
      val failure =
        scala.util.Try(ConstructionKernel.inspect(new DimensionMismatchEquation)).failed.get
          .asInstanceOf[ConstructionException]
      assert(failure.diagnostic.code == "NODAL-ANALOG-032-006")

    test("procedural assignment is rejected in a declarative region"):
      val failure = scala.util.Try(ConstructionKernel.inspect(new DeclarativeAssignment)).failed.get
        .asInstanceOf[ConstructionException]
      assert(failure.diagnostic.code == "NODAL-ANALOG-133-007")

    test("non-access contribution target is rejected"):
      val failure =
        scala.util.Try(ConstructionKernel.inspect(new InvalidContributionTarget)).failed.get
          .asInstanceOf[ConstructionException]
      assert(failure.diagnostic.code == "NODAL-ANALOG-133-005")
