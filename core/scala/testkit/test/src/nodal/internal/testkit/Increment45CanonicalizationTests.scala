package nodal.internal.testkit

import nodal.*
import nodal.increment45fixture.*
import nodal.internal.bridge.ScalaToMlirBridge
import utest.*

/** Source-side checks deliberately precede native rewriting and retain authored identity. */
object Increment45CanonicalizationTests extends TestSuite:
  private def operations(text: String, name: String): Vector[String] =
    text.linesIterator.filter(_.contains(s"\"nodal.$name\"(")).toVector

  val tests: Tests = Tests:
    test("public symbolic duplicate arithmetic retains ordered operands and distinct provenance"):
      val snapshot = ConstructionKernel.inspect(new AnalogCanonicalizationSource)
      val expressions = snapshot.analogRegions.head.expressions
      val first = expressions.find(_.path == "AnalogCanonicalizationSource.first").get
      val second = expressions.find(_.path == "AnalogCanonicalizationSource.second").get
      assert(first.operation == "analog_add", second.operation == "analog_add")
      assert(first.operands == second.operands)
      assert(first.operands == Vector(
        "AnalogCanonicalizationSource.gain",
        "AnalogCanonicalizationSource.offset"
      ))
      assert(first.path != second.path)
      assert(first.literal.isEmpty, second.literal.isEmpty)
      val origins =
        snapshot.sourceMap.filter(value => Set(first.path, second.path)(value.semanticPath))
      assert(origins.size == 2)
      assert(origins.map(_.source.line).distinct.size == 2)
      val document = ScalaToMlirBridge.fromSnapshot(snapshot)
      assert(operations(document.text, "parameter_ref").size == 2)
      assert(operations(document.text, "analog_add").size == 8)
      assert(operations(document.text, "analog_mul").size == 5)
      assert(operations(document.text, "analog_neg").size == 2)
      assert(operations(document.text, "analog_div").size == 2)
      assert(!document.text.contains("nodal.canonical"))

    test("authored double negation constant arithmetic and equal contributions are not pre-erased"):
      val snapshot = ConstructionKernel.inspect(new AnalogCanonicalizationSource)
      val region = snapshot.analogRegions.head
      val negatives = region.expressions.filter(_.operation == "analog_neg")
      assert(negatives.size == 2)
      assert(negatives.head.operands == Vector("AnalogCanonicalizationSource.gain"))
      assert(negatives.last.operands == Vector(negatives.head.path))
      val constants =
        region.expressions.filter(_.literal.nonEmpty).map(value => value.path -> value).toMap
      val product = region.expressions.find(value =>
        value.operation == "analog_mul" && value.operands.forall(constants.contains) &&
          value.operands.map(constants(_).literal.get) == Vector("2.0", "3.0")
      )
      assert(product.nonEmpty)
      assert(region.contributions.size == 4)
      val equal = region.contributions.take(2)
      val accesses = equal.map(term => region.expressions.find(_.path == term.target).get)
      assert(accesses.map(_.operation).distinct == Vector("potential_access"))
      assert(accesses.map(_.operands).distinct == Vector(Vector(
        "AnalogCanonicalizationSource.positive",
        "AnalogCanonicalizationSource.negative"
      )))
      assert(equal.map(_.path).distinct.size == 2)
      assert(equal.map(_.value).distinct.size == 2)
      val products = equal.map(term => region.expressions.find(_.path == term.value).get)
      assert(products.forall(_.operation == "analog_mul"))
      assert(products.map(_.operands.head) == Vector(
        "AnalogCanonicalizationSource.first",
        "AnalogCanonicalizationSource.second"
      ))

    test("literal dynamic and call-fed unused locals all reach native verification intact"):
      val snapshot = ConstructionKernel.inspect(new AnalogCanonicalizationSource)
      val definition =
        snapshot.analogFunctions.find(_.definition.name == "preserveSignal").get.definition
      assert(definition.nodes.size == 8)
      assert(definition.returned == 0)
      assert(definition.nodes.head.operation == "input")
      val locals = definition.nodes.zipWithIndex.filter(_._1.operation == "local")
      assert(locals.map(_._1.name) == Vector("unusedLiteral", "unusedDynamic", "unusedCall"))
      assert(locals.forall((_, index) => !definition.nodes.exists(_.operands.contains(index))))
      assert(locals.map((value, _) => definition.nodes(value.operands.head).operation) ==
        Vector("literal", "add", "call"))
      assert(definition.nodes.forall(_.source.nonEmpty))
      val document = ScalaToMlirBridge.fromSnapshot(snapshot)
      assert(operations(
        document.text,
        "analog_function_value"
      ).count(_.contains("kind = \"local\"")) == 3)
      assert(operations(document.text, "analog_user_call").size == 3)
      assert(operations(document.text, "analog_abstime").size == 2)
      assert(operations(document.text, "analog_div").size == 2)
      assert(operations(document.text, "contribute").size == 4)
      assert(!document.text.contains("nodal.canonical.dead_local"))

    test("authored residual and equal contribution inventory remains complete and noncausal"):
      val snapshot = ConstructionKernel.inspect(new AnalogCanonicalizationEquationInventory)
      val semantics = snapshot.analogSemantics
      assert(semantics.equations.size == 2)
      val ordinary = semantics.equations.find(!_.initialOnly).get
      val initial = semantics.equations.find(_.initialOnly).get
      assert(ordinary.identity.value.endsWith(".authored-balance"))
      assert(ordinary.metadata.analyses == Set("dc", "transient"))
      assert(ordinary.metadata.continuity == "c1")
      assert(initial.identity.value.endsWith(".authored-initial"))
      assert(initial.metadata.analyses == Set("initialization"))
      assert(semantics.equations.forall(value =>
        !value.residual.causallyOriented && !value.residual.divided &&
          value.residual.canonicalConvention == "lhs-minus-rhs-equals-zero" &&
          value.residual.authoredLeft.dimension == "voltage" &&
          value.residual.authoredRight.dimension == "voltage"
      ))
      assert(semantics.contributions.size == 1)
      val terms = semantics.contributions.head.terms
      assert(terms.size == 2)
      assert(terms.map(_.identity.value).distinct.size == 2)
      assert(terms.map(_.value).distinct.size == 1)
      val document = ScalaToMlirBridge.fromSnapshot(snapshot)
      assert(document.text.contains("nodal.bridge.analog_semantics"))
      assert(document.text.contains("kind = \"initial_equation\""))
      assert(document.text.contains("equal-first"), document.text.contains("equal-second"))

    test("fresh public construction exports deterministic VA and AMS profile documents"):
      Vector(Backend.VerilogA, Backend.VerilogAMS).foreach: backend =>
        val first = ScalaToMlirBridge.fromSnapshot(
          ConstructionKernel.inspect(new AnalogCanonicalizationSource),
          backend
        )
        val second = ScalaToMlirBridge.fromSnapshot(
          ConstructionKernel.inspect(new AnalogCanonicalizationSource),
          backend
        )
        assert(first == second)
      val inventory = ScalaToMlirBridge.lower(new AnalogCanonicalizationEquationInventory)
      assert(inventory == ScalaToMlirBridge.lower(new AnalogCanonicalizationEquationInventory))
