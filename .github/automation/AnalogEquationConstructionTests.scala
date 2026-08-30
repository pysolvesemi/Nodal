package nodal

import nodal.internal.bridge.ReproducibilityContract
import nodal.internal.bridge.ScalaToMlirBridge
import utest.*

final class PublicAnalogEquationTop extends Module:
  val positive: Terminal[Electrical.type] = terminal(Electrical, "positive")
  val negative: Terminal[Electrical.type] = terminal(Electrical, "negative")
  val path: Branch[Electrical.type] = branch(positive, negative, "device_path")
  val resistance: Param[Real] = param(10.0.kOhm)
  val scratch: Variable[Real] = variable(Real, 0.0.real)

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
      InitialEquationOptions(id = Some(EquationId("initial-voltage")))
    )

  contributions:
    path.flow <+ path.potential / resistance
    contribution(
      path.potential,
      0.0.V,
      ContributionOptions(id = Some(ContributionId("reference-drive")))
    )

  analogProcedure:
    scratch := path.potential

final class PublicEquationOutsideRegion extends Module:
  equation(0.0.V, 0.0.V, EquationOptions(id = Some(EquationId("outside"))))

final class PublicNestedAnalogRegions extends Module:
  equations:
    contributions:
      ()

final class PublicAssignmentInEquationRegion extends Module:
  val scratch: Variable[Real] = variable(Real, 0.0.real)
  equations:
    scratch := 1.0.real

final class PublicInvalidContributionTarget extends Module:
  contributions:
    contribution(
      1.0.V,
      2.0.V,
      ContributionOptions(id = Some(ContributionId("invalid-target")))
    )

final class PublicEmptyEquationIdentity extends Module:
  equations:
    equation(
      0.0.V,
      0.0.V,
      EquationOptions(id = Some(EquationId(" ")))
    )

final class PublicEmptyContributionIdentity extends Module:
  val positive: Terminal[Electrical.type] = terminal(Electrical, "positive")
  val negative: Terminal[Electrical.type] = terminal(Electrical, "negative")
  val path: Branch[Electrical.type] = branch(positive, negative, "device_path")
  contributions:
    contribution(
      path.flow,
      1.0.A,
      ContributionOptions(id = Some(ContributionId(" ")))
    )

final class PublicUnknownEquationDimension extends Module:
  equations:
    equation(
      toReal(1.U(8)),
      1.0.real,
      EquationOptions(id = Some(EquationId("unknown-dimension")))
    )

final class PublicDimensionMismatch extends Module:
  val positive: Terminal[Electrical.type] = terminal(Electrical, "positive")
  val negative: Terminal[Electrical.type] = terminal(Electrical, "negative")
  val path: Branch[Electrical.type] = branch(positive, negative, "device_path")
  equations:
    equation(
      path.potential,
      1.0.A,
      EquationOptions(id = Some(EquationId("dimension-mismatch")))
    )

object AnalogEquationConstructionTests extends TestSuite:
  private def failureCode(top: => Module): String =
    scala.util.Try(ConstructionKernel.inspect(top)).failed.get match
      case error: ConstructionException => error.diagnostic.code
      case other => other.getClass.getName

  val tests: Tests = Tests:
    test("public equation and contribution APIs feed the Increment 32 recorder"):
      val snapshot = ConstructionKernel.inspect(new PublicAnalogEquationTop)
      val semantics = snapshot.analogSemantics

      assert(semantics.equations.size == 2)
      assert(
        semantics.equations.map(_.identity.value) ==
          Vector(
            "PublicAnalogEquationTop.initial-voltage",
            "PublicAnalogEquationTop.ohm-law"
          )
      )
      val initial = semantics.equations.head
      assert(initial.initialOnly)
      assert(initial.metadata.analyses == Set("initialization"))
      assert(initial.metadata.source.file.endsWith("AnalogEquationConstructionTests.scala"))

      val ordinary = semantics.equations(1)
      assert(!ordinary.initialOnly)
      assert(ordinary.residual.authoredLeft.dimension == "voltage")
      assert(ordinary.residual.authoredRight.dimension == "voltage")
      assert(!ordinary.residual.causallyOriented)
      assert(!ordinary.residual.divided)

      assert(semantics.contributions.size == 2)
      assert(semantics.contributions.map(_.target.kind).toSet == Set(
        AnalogEquationRuntime.ContributionKind.Potential,
        AnalogEquationRuntime.ContributionKind.Flow
      ))
      assert(
        semantics.contributions.flatMap(_.terms).exists(
          _.identity.value.endsWith("reference-drive")
        )
      )
      assert(
        semantics.contributions.flatMap(_.terms).exists(
          _.identity.value.contains("contribution_")
        )
      )

      val mlir = ScalaToMlirBridge.lower(new PublicAnalogEquationTop).text
      assert(mlir.contains("nodal.bridge.analog_semantics"))
      assert(mlir.contains("PublicAnalogEquationTop.ohm-law"))
      assert(mlir.contains("PublicAnalogEquationTop.initial-voltage"))
      assert(mlir.contains("initial_equation"))
      assert(mlir.contains("residual_convention"))

      val canonical = ReproducibilityContract.canonicalSnapshot(snapshot)
      assert(canonical.contains("\"analog_semantics\""))
      assert(canonical.contains("PublicAnalogEquationTop.ohm-law"))
      assert(canonical.contains("PublicAnalogEquationTop.reference-drive"))

    test("public region and target diagnostics are fail closed"):
      assert(failureCode(new PublicEquationOutsideRegion) == "NODAL-ANALOG-032-003")
      assert(failureCode(new PublicNestedAnalogRegions) == "NODAL-ANALOG-032-001")
      assert(
        failureCode(new PublicAssignmentInEquationRegion) == "NODAL-ANALOG-133-007"
      )
      assert(
        failureCode(new PublicInvalidContributionTarget) == "NODAL-ANALOG-133-005"
      )
      assert(failureCode(new PublicDimensionMismatch) == "NODAL-ANALOG-032-006")
      assert(
        failureCode(new PublicUnknownEquationDimension) == "NODAL-ANALOG-032-006"
      )
      assert(
        failureCode(new PublicEmptyEquationIdentity) == "NODAL-ANALOG-032-015"
      )
      assert(
        failureCode(new PublicEmptyContributionIdentity) == "NODAL-ANALOG-032-016"
      )
