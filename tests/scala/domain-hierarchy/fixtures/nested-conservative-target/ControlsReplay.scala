package nodal.internal.testkit
import nodal.*
import nodal.internal.bridge.*
import nodal_external.*

object ReplayNestedConservativeControls:
  def main(args: Array[String]): Unit =
    val shapeDiagnostic = "NODAL-HIERARCHY-058-INTERFACE: child Interface requires digital/resolved leaves or scalar conservative components"
    val indexDiagnostic = "NODAL-AREA-058-INDEX: generated ordinal existence is not proved in its actual lexical scope"
    val parameterDiagnostic = "NODAL-INTERFACE-058-ANALOG: selected conservative child parameters require ordinary dimensionless Real literals"
    val cases: Vector[(String, () => Module, String)] = Vector(
      ("outer-nonzero", () => new NestedConservativeShapeControl(outerLower = 1), shapeDiagnostic),
      ("inner-nonzero", () => new NestedConservativeShapeControl(innerLower = 1), shapeDiagnostic),
      ("outer-nonunit", () => new NestedConservativeShapeControl(outerStep = 2), shapeDiagnostic),
      ("inner-nonunit", () => new NestedConservativeShapeControl(innerStep = 2), shapeDiagnostic),
      ("outer-symbolic", () => new NestedConservativeShapeControl(symbolicOuter = true), shapeDiagnostic),
      ("inner-symbolic", () => new NestedConservativeShapeControl(symbolicInner = true), shapeDiagnostic),
      ("row-out-of-range", () => new NestedConservativeShapeControl(rowOrdinal = 2), indexDiagnostic),
      ("column-out-of-range", () => new NestedConservativeShapeControl(columnOrdinal = 3), indexDiagnostic),
      ("parent-reference", () => new NestedConservativeParentActual(false), parameterDiagnostic),
      ("parent-expression", () => new NestedConservativeParentActual(true), parameterDiagnostic),
      ("nested-local", () => new NestedConservativeLocalControl,
        "NODAL-INTERFACE-058-ANALOG: generated conservative member requires its direct scalar endpoint and actual occurrence")
    )
    cases.foreach: (name, make, expected) =>
      val result = scala.util.Try(ConstructionKernel.inspect(make()))
      assert(result.isFailure, name)
      val message = result.failed.get.toString
      assert(message.contains(expected), name + ": " + message)
      println("PASS source " + name + ": " + message)

    val snapshot = ConstructionKernel.inspect(new NestedConservativeSource)
    val selected = snapshot.interfaceContracts.filter(_.boundary.exists(_.selection.nonEmpty))
    assert(selected.size == 4)
    val endpoint = selected.head
    val boundary = endpoint.boundary.get
    val selection = boundary.selection.get
    assert(selection.selectionPrefix.size == 1)
    def forged(change: KernelGeneratedMemberSnapshot => KernelGeneratedMemberSnapshot)
        : ConstructionSnapshot =
      val changed = boundary.copy(selection = Some(change(selection)))
      snapshot.copy(
        interfaceContracts = snapshot.interfaceContracts.map(value =>
          if value.path == endpoint.path then value.copy(boundary = Some(changed)) else value
        ),
        modules = snapshot.modules.map(module => module.copy(declarations =
          module.declarations.map(value =>
            if value.path == endpoint.path then value.copy(interfaceBoundary = Some(changed)) else value
          )
        ))
      )
    val forgedCases = Vector(
      "missing-prefix" -> forged(_.copy(selectionPrefix = Vector.empty)),
      "repeated-family-prefix" -> forged(value =>
        value.copy(selectionPrefix = value.selectionPrefix ++ value.selectionPrefix)),
      "wrong-prefix-family" -> forged(value =>
        value.copy(selectionPrefix = value.selectionPrefix.map(_.copy(family = value.family))))
    )
    forgedCases.foreach: (name, value) =>
      val result = scala.util.Try(ScalaToMlirBridge.fromSnapshot(value, Backend.VerilogA))
      assert(result.isFailure, name)
      val message = result.failed.get.toString
      assert(message.contains("NODAL-BRIDGE-054-INTERFACE: selected conservative child requires its exact constant scalar connect profile"),
        name + ": " + message)
      println("PASS snapshot " + name + ": " + message)

    // Numeric repetition is valid; only repeated or discontinuous family provenance is rejected.
    val tuples = selected.map(_.boundary.get.selection.get).map(value =>
      value.selectionPrefix.head.ordinal -> value.ordinal
    ).toSet
    assert(tuples == Set("0" -> "0", "0" -> "1", "1" -> "0"), tuples)
    val recovered = ConstructionKernel.inspect(new NestedConservativeSource)
    val text = ScalaToMlirBridge.fromSnapshot(recovered, Backend.VerilogA).text
    assert(text.contains("nodal.generated_member"))
    println("PASS positive recovery and valid numeric tuples (0,0), (0,1), (1,0)")
