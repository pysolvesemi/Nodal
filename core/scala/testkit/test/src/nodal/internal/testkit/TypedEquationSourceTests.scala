package nodal.internal.testkit

import nodal.*
import nodal.increment46structuralfixture.*
import nodal.internal.bridge.{BridgeException, ReproducibilityContract, ScalaToMlirBridge}
import utest.*

object TypedEquationSourceTests extends TestSuite:
  private def count(text: String, operation: String): Int =
    text.linesIterator.count(_.contains(s"\"nodal.$operation\"("))

  val tests = Tests:
    test("component scope captures exact declarations and actual typed roots"):
      val snapshot = ConstructionKernel.inspect(new BalancedEquationSource)
      val semantic = snapshot.analogSemantics
      assert(semantic.components.size == 1)
      assert(semantic.states.size == 2)
      assert(semantic.equations.size == 3)
      val mapped = snapshot.sourceMap.map(_.semanticPath).toSet
      assert((semantic.states.map(_.identity) ++ semantic.equations.map(_.identity.value)).forall(
        mapped.contains
      ))
      assert(semantic.equations.forall(_.residual.authoredLeft.root.nonEmpty))
      assert(semantic.states.map(_.initialization).toSet == Set("fixed", "guess"))
      assert(semantic.states.forall(_.initializer.flatMap(_.root).nonEmpty))
      val source = ScalaToMlirBridge.fromSnapshot(snapshot).text
      assert(count(source, "analog_unknown") == 2)
      assert(count(source, "analog_equation") == 3)
      assert(source.contains("!nodal.quantity<\"real\", \"voltage\">"))
      assert(source.contains("unit = @\"_nodal_source_unit_V\""))
      assert(source.contains("initial_only = true"))
      assert(source.contains("authored_left"))
      assert(source.contains("nodal.bridge.source_equations"))
      val replay = ConstructionKernel.inspect(new BalancedEquationSource)
      assert(source == ScalaToMlirBridge.fromSnapshot(replay).text)
      assert(ReproducibilityContract.canonicalSnapshot(snapshot) ==
        ReproducibilityContract.canonicalSnapshot(replay))

    test("unused declarations and repeated reads keep one unknown identity"):
      assert(Nodal.emit(new UnusedEquationSource).report.designKind == DesignKind.AnalogOnly)
      val unused = ScalaToMlirBridge.lower(new UnusedEquationSource).text
      assert(count(unused, "analog_unknown") == 1)
      assert(count(unused, "analog_equation") == 0)
      val repeated = ScalaToMlirBridge.lower(new RepeatedReadEquationSource).text
      assert(count(repeated, "analog_unknown") == 1)
      assert(count(repeated, "analog_add") == 2)
      assert(count(repeated, "analog_equation") == 1)
      assert(repeated.contains("\"nodal.analog_add\"(%source_value_0, %source_value_0)"))

    test("causal structural graphs survive the source bridge without assignment lowering"):
      Vector[(Int, Int, () => Module)](
        (2, 1, () => new UnderconstrainedEquationSource),
        (1, 2, () => new OverconstrainedEquationSource),
        (2, 2, () => new SingularEquationSource),
        (3, 3, () => new CompensatingEquationSource)
      ).foreach: (states, equations, source) =>
        val text = ScalaToMlirBridge.lower(source()).text
        assert(count(text, "analog_unknown") == states)
        assert(count(text, "analog_equation") == equations)
        assert(count(text, "analog_assign") == 0)
        assert(!text.contains("profile_complete = false"))

    test("unsupported profiles explicitly retain missing coverage"):
      Vector[() => Module](
        () => new UnsupportedEquationSource,
        () => new GuardedEquationSource,
        () => new PartialEquationSource,
        () => new UnscopedEquationSource,
        () => new CrossComponentEquationSource,
        () => new HierarchicalEquationSource,
        () => new UnsupportedInitializerEquationSource
      ).foreach: source =>
        val text = ScalaToMlirBridge.lower(source()).text
        assert(text.contains("profile_complete = false"))
        assert(text.contains("equation_ids = ["))
        assert(text.contains("typed_equation_ids = ["))
      val cross = ScalaToMlirBridge.lower(new CrossComponentEquationSource).text
      assert(count(cross, "analog_unknown") == 2)
      assert(count(cross, "analog_equation") == 1)
      assert(cross.contains("cross-component state read"))

    test("sparse source paths and shared DAG growth stay bounded"):
      val sparse = ScalaToMlirBridge.lower(new SparseEquationSource).text
      assert(count(sparse, "analog_unknown") == 64)
      assert(count(sparse, "analog_equation") == 64)
      val shared = ScalaToMlirBridge.lower(new SharedDagEquationSource).text
      assert(count(shared, "analog_unknown") == 1)
      assert(count(shared, "analog_add") == 8)
      assert(count(shared, "analog_equation") == 1)

    test("initialization phases stay distinct from runtime equations"):
      val source = ScalaToMlirBridge.lower(new InitializationEquationSource).text
      assert(count(source, "analog_unknown") == 6)
      assert(count(source, "analog_equation") == 7)
      Vector(
        "fixed",
        "guess",
        "initial-equation",
        "steady-state",
        "operating-point",
        "solver-selected"
      )
        .foreach(mode => assert(source.contains(s"initialization = \"$mode\"")))
      assert(source.linesIterator.count(line =>
        line.contains("\"nodal.analog_equation\"") &&
          line.contains("initial_only = true")
      ) == 1)

    test("malformed ownership identity and dimensional metadata are errors"):
      val snapshot = ConstructionKernel.inspect(new BalancedEquationSource)
      val semantics = snapshot.analogSemantics
      val state = semantics.states.head
      val equation = semantics.equations.head
      val malformed = Vector(
        semantics.copy(states = semantics.states :+ state),
        semantics.copy(states = state.copy(owner = "forged") +: semantics.states.tail),
        semantics.copy(states = state.copy(dimension = "current") +: semantics.states.tail),
        semantics.copy(states =
          state.copy(initialization = "solver-selected") +: semantics.states.tail
        ),
        semantics.copy(equations =
          equation.copy(metadata = equation.metadata.copy(owner = "forged")) +:
            semantics.equations.tail
        ),
        semantics.copy(equations =
          equation.copy(residual =
            equation.residual.copy(
              authoredLeft = equation.residual.authoredLeft.copy(dimension = "current")
            )
          ) +: semantics.equations.tail
        )
      )
      malformed.foreach: source =>
        val error = scala.util.Try(ScalaToMlirBridge.fromSnapshot(snapshot.copy(analogSemantics =
          source
        ))).failed.get.asInstanceOf[BridgeException]
        assert(error.diagnostic.code == "NODAL-ANALOG-134-001")
      val error = scala.util.Try(
        ConstructionKernel.inspect(new InvalidStateInitializerSource)
      ).failed.get.asInstanceOf[ConstructionException]
      assert(error.diagnostic.code == "NODAL-ANALOG-134-002")

    test("missing root is incomplete while forged scalar type is rejected"):
      val snapshot = ConstructionKernel.inspect(new BalancedEquationSource)
      val equation = snapshot.analogSemantics.equations.head
      val missing = equation.copy(residual =
        equation.residual.copy(
          authoredLeft = equation.residual.authoredLeft.copy(root = None)
        )
      )
      val missingSnapshot = snapshot.copy(analogSemantics =
        snapshot.analogSemantics.copy(
          equations = missing +: snapshot.analogSemantics.equations.tail
        )
      )
      assert(
        ScalaToMlirBridge.fromSnapshot(missingSnapshot).text.contains("profile_complete = false")
      )
      val bool = new KernelExpr[Bool](
        Vector(true),
        Some(KernelTypeDescriptor("Bool")),
        Some(KernelLiteral("boolean", "true", KernelTypeDescriptor("Bool")))
      )
      val forged = equation.copy(residual =
        equation.residual.copy(
          authoredLeft = equation.residual.authoredLeft.copy(root = Some(bool))
        )
      )
      val forgedSnapshot = snapshot.copy(analogSemantics =
        snapshot.analogSemantics.copy(
          equations = forged +: snapshot.analogSemantics.equations.tail
        )
      )
      val error = scala.util.Try(ScalaToMlirBridge.fromSnapshot(forgedSnapshot)).failed.get
      assert(error.isInstanceOf[BridgeException])

object TypedEquationEmptyRegionTests extends TestSuite:
  private class EmptyModule extends Module

  val tests = Tests:
    test("empty mandatory regions retain a block and incomplete source coverage"):
      val empty = ScalaToMlirBridge.lower(new EmptyModule).text
      assert(empty.contains("^bb0:"))
      val unscoped = ScalaToMlirBridge.lower(new UnscopedEquationSource).text
      assert(unscoped.contains("^bb0:"))
      assert(unscoped.contains("profile_complete = false"))
      assert(unscoped.contains("uncovered_state_ids = [\""))
      val unsupported = ScalaToMlirBridge.lower(new UnsupportedInitializerEquationSource).text
      assert(unsupported.contains("profile_complete = false"))
      val emptyComponent = ScalaToMlirBridge.lower(new EmptyComponentEquationSource).text
      assert(emptyComponent.contains("^bb0:"))
      assert(!emptyComponent.contains("profile_complete = false"))
      val stateOnly = ScalaToMlirBridge.lower(new UnusedEquationSource).text
      assert(stateOnly.contains("\"nodal.analog_unknown\""))
      assert(!stateOnly.contains("profile_complete = false"))

    test("reused child definitions preserve uncovered occurrence identities"):
      val snapshot = ConstructionKernel.inspect(new RepeatedHierarchyEquationSource)
      assert(snapshot.analogSemantics.components.size == 2)
      assert(snapshot.analogSemantics.states.size == 2)
      val text = ScalaToMlirBridge.fromSnapshot(snapshot).text
      assert(text.linesIterator.count(_.contains("\"nodal.module\"(")) == 2)
      assert(text.linesIterator.count(_.contains("\"nodal.instance\"(")) == 2)
      assert(text.linesIterator.count(_.contains("\"nodal.analog_unknown\"(")) == 1)
      assert(text.contains("uncovered_component_ids = [\""))
      assert(text.contains("uncovered_state_ids = [\""))
      assert(text.contains("uncovered_equation_ids = [\""))
      assert(text.contains("profile_complete = false"))
