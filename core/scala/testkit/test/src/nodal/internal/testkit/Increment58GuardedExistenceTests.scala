package nodal.internal.testkit
import nodal.*
import nodal_external.*
import nodal.internal.bridge.*
import utest.*
object Increment58GuardedExistenceTests extends TestSuite:
  private def valid(make: () => Module): ConstructionSnapshot =
    val snapshot = ConstructionKernel.inspect(make())
    val count = snapshot.modules.head.declarations.find(_.name == "count").get
    assert(count.attributes.contains("integer_range_lower" -> "0"))
    assert(count.attributes.contains("default" -> "0"))
    val family = snapshot.generatedRegions.head
    val projection = snapshot.digitalExpressions.find(_.generatedMember.nonEmpty).get
    assert(family.lexicalParent.nonEmpty && projection.region == family.lexicalParent)
    assert(snapshot.structuralIfs.exists(value =>
      Some(value.trueArm.path) == projection.region ||
        Some(value.falseArm.path) == projection.region
    ))
    assert(snapshot.behavioralIfs.exists(_.trueArm.captures.contains(projection.path)))
    assert(ScalaToMlirBridge.fromSnapshot(snapshot).text.contains("nodal.generated_member"))
    snapshot
  private def rejected(make: () => Module): Unit =
    val outcome = scala.util.Try(ConstructionKernel.inspect(make()))
    assert(outcome.isFailure)
    assert(outcome.failed.get.getMessage.contains("NODAL-AREA-058-INDEX"))
  val tests = Tests:
    test("guard proves lane zero without changing global bound or sampling zero default"):
      val _ = valid(() => new Increment58GuardedExistenceSource)
    test("not false arm conjunction and every disjunction arm retain exact ordering"):
      for make <- Vector[() => Module](
          () => new Increment58GuardedExistenceNegated,
          () => new Increment58GuardedExistenceFalseArm,
          () => new Increment58GuardedExistenceAnd,
          () => new Increment58GuardedExistenceOr
        )
      do
        val _ = valid(make)
    test("inclusive zero foreign same-envelope and unproven later ordinal reject"):
      for make <- Vector[() => Module](
          () => new Increment58GuardedExistenceInclusive,
          () => new Increment58GuardedExistenceForeign,
          () => new Increment58GuardedExistenceLater
        )
      do
        rejected(make)
    test("unproven disjunction contradiction and stronger nonexact comparison reject"):
      for make <- Vector[() => Module](
          () => new Increment58GuardedExistenceUnprovenOr,
          () => new Increment58GuardedExistenceUnreachable,
          () => new Increment58GuardedExistenceStrongerOr
        )
      do
        rejected(make)
    test("module family requiring later guard cannot hoist projection out of proof scope"):
      val snapshot = ConstructionKernel.inspect(new Increment58GuardedExistenceOuterHandle)
      val projection = snapshot.digitalExpressions.find(_.generatedMember.nonEmpty).get
      assert(projection.region.nonEmpty)
      assert(projection.generatedMember.get.familyInput.nonEmpty)
      assert(snapshot.generatedRegions.head.lexicalParent.isEmpty)
      assert(ScalaToMlirBridge.fromSnapshot(snapshot).text.contains("!nodal.generated_family"))
    test("forged comparison cannot inherit authored presence authority"):
      for make <- Vector[() => Module](
          () => new Increment58GuardedExistenceSource,
          () => new Increment58GuardedExistenceAnd,
          () => new Increment58GuardedExistenceOr
        )
      do
        val snapshot = valid(make)
        val comparison = snapshot.staticPredicates.find(_.operation == "gt").get
        val forged = snapshot.copy(staticPredicates =
          snapshot.staticPredicates.map(value =>
            if value.path == comparison.path then value.copy(operation = "ge") else value
          )
        )
        val outcome = scala.util.Try(ScalaToMlirBridge.fromSnapshot(forged))
        assert(outcome.isFailure)
        assert(outcome.failed.get.getMessage.contains("generated ordinal does not exist"))
    test("shared predicate proof bounds cycles and Boolean expansion"):
      val cycle = new StructuralPredicateOrdering.Query[Int, Int](
        _ => StructuralPredicateOrdering.Logic("not", Vector(0)),
        _ => IterationDomain.Bounds(0L, 8L),
        _ == _
      )
      val cycleFailure = scala.util.Try(cycle.under(0, false, 0, 1, false))
      assert(cycleFailure.failed.get.getMessage.contains("cyclic"))
      val nodes =
        scala.collection.mutable.ArrayBuffer.empty[StructuralPredicateOrdering.Node[Int, Int]]
      def add(value: StructuralPredicateOrdering.Node[Int, Int]): Int =
        val id = nodes.size
        nodes += value
        id
      var root = -1
      for index <- 0 until 20 do
        val left = add(StructuralPredicateOrdering.Compare("lt", index * 4, index * 4 + 1))
        val right = add(StructuralPredicateOrdering.Compare("lt", index * 4 + 2, index * 4 + 3))
        val alternatives = add(StructuralPredicateOrdering.Logic("or", Vector(left, right)))
        root = if root < 0 then alternatives
        else add(StructuralPredicateOrdering.Logic("and", Vector(root, alternatives)))
      val proof = new StructuralPredicateOrdering.Query[Int, Int](
        nodes(_),
        _ => IterationDomain.Bounds(0L, 8L),
        _ == _
      )
      val limit = scala.util.Try(proof.under(root, false, 1000, 1001, false))
      assert(limit.failed.get.getMessage.contains("work budget"))
