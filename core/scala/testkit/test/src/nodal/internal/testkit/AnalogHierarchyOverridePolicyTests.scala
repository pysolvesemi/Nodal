package nodal.internal.testkit

import nodal.AnalogHierarchyOverridePolicy

import utest.*

object AnalogHierarchyOverridePolicyTests extends TestSuite:
  private val Parent = 17L

  private def evidence(
      duplicate: Boolean = false,
      referencedOwners: Vector[Long] = Vector(Parent),
      expressionOwners: Vector[Long] = Vector(Parent),
      dynamicDependency: Boolean = false,
      operations: Vector[String] = Vector("analog_add"),
      targetType: Option[String] = Some("Real"),
      valueType: Option[String] = Some("Real"),
      requiresDimension: Boolean = true,
      targetDimension: Option[String] = Some("V"),
      valueDimension: Option[String] = Some("V")
  ): AnalogHierarchyOverridePolicy.Evidence =
    AnalogHierarchyOverridePolicy.Evidence(
      parentOwner = Parent,
      duplicate = duplicate,
      referencedDeclarationOwners = referencedOwners,
      expressionOwners = expressionOwners,
      dynamicDependency = dynamicDependency,
      operations = operations,
      targetTypeSignature = targetType,
      valueTypeSignature = valueType,
      requiresDimension = requiresDimension,
      targetDimensionSignature = targetDimension,
      valueDimensionSignature = valueDimension
    )

  private def rejectionCode(
      result: Either[AnalogHierarchyOverridePolicy.Rejection, Unit]
  ): String = result.swap.toOption.get.code

  val tests: Tests = Tests:
    test("accepts parent-owned typed dimension-preserving static override"):
      assert(AnalogHierarchyOverridePolicy.validate(evidence()) == Right(()))

    test("accepts literal-like override with no operation nodes"):
      assert(
        AnalogHierarchyOverridePolicy.validate(
          evidence(operations = Vector.empty, referencedOwners = Vector.empty)
        ) == Right(())
      )

    test("rejects duplicate override"):
      assert(
        rejectionCode(AnalogHierarchyOverridePolicy.validate(evidence(duplicate = true))) ==
          "NODAL-HIERARCHY-030"
      )

    test("rejects foreign declaration ownership"):
      assert(
        rejectionCode(
          AnalogHierarchyOverridePolicy.validate(evidence(referencedOwners = Vector(Parent + 1)))
        ) == "NODAL-HIERARCHY-031"
      )

    test("rejects foreign expression ownership"):
      assert(
        rejectionCode(
          AnalogHierarchyOverridePolicy.validate(evidence(expressionOwners = Vector(Parent + 1)))
        ) == "NODAL-HIERARCHY-031"
      )

    test("rejects dynamic dependency"):
      assert(
        rejectionCode(
          AnalogHierarchyOverridePolicy.validate(evidence(dynamicDependency = true))
        ) == "NODAL-HIERARCHY-032"
      )

    test("rejects transition even with parent-owned operands"):
      assert(
        rejectionCode(
          AnalogHierarchyOverridePolicy.validate(evidence(operations = Vector("transition")))
        ) == "NODAL-HIERARCHY-037"
      )

    test("rejects unknown stateful operation fail closed"):
      assert(
        rejectionCode(
          AnalogHierarchyOverridePolicy.validate(evidence(operations = Vector("future_state_op")))
        ) == "NODAL-HIERARCHY-037"
      )

    test("rejects unproven type"):
      assert(
        rejectionCode(
          AnalogHierarchyOverridePolicy.validate(evidence(valueType = None))
        ) == "NODAL-HIERARCHY-033"
      )

    test("rejects type mismatch"):
      assert(
        rejectionCode(
          AnalogHierarchyOverridePolicy.validate(
            evidence(valueType = Some("UInt<8>"), requiresDimension = false)
          )
        ) == "NODAL-HIERARCHY-034"
      )

    test("rejects unproven real dimension"):
      assert(
        rejectionCode(
          AnalogHierarchyOverridePolicy.validate(evidence(valueDimension = None))
        ) == "NODAL-HIERARCHY-035"
      )

    test("rejects real dimension mismatch"):
      assert(
        rejectionCode(
          AnalogHierarchyOverridePolicy.validate(evidence(valueDimension = Some("A")))
        ) == "NODAL-HIERARCHY-036"
      )
