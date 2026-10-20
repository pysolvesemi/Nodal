package nodal.internal.testkit

import nodal.*
import nodal.internal.bridge.*
import nodal_external.*
import utest.*

object Increment58NestedGeneratedAreaTests extends TestSuite:
  private def families(snapshot: ConstructionSnapshot)
      : (KernelGeneratedRegionSnapshot, KernelGeneratedRegionSnapshot) =
    assert(snapshot.generatedRegions.size == 2)
    val outer = snapshot.generatedRegions.find(_.parent.isEmpty).get
    val inner = snapshot.generatedRegions.find(_.parent.nonEmpty).get
    assert(inner.parent.contains(outer.path))
    assert(outer.digitalBody.nonEmpty && inner.digitalBody.nonEmpty)
    assert(outer.declarations.isEmpty && outer.exports.isEmpty)
    assert(inner.declarations.size == 1 && inner.exports.map(_.member) == Vector("stored"))
    val member = snapshot.digitalExpressions.find(_.generatedMember.nonEmpty).get
    assert(member.region.contains(outer.path))
    assert(member.generatedMember.get.family == inner.path)
    val assignment = snapshot.digitalAssignments.find(_.region.contains(outer.path)).get
    assert(inner.digitalBody.get.sourceOrdinal < assignment.sourceOrdinal)
    (outer, inner)

  private def bridgeRejected(snapshot: ConstructionSnapshot, message: String): Unit =
    val failure = scala.util.Try(ScalaToMlirBridge.fromSnapshot(snapshot)).failed.get
    assert(failure.getMessage.contains(message))

  val tests = Tests:
    test("concrete nested bodies retain one actual template and exact lexical selection"):
      val snapshot = ConstructionKernel.inspect(new Increment58NestedGeneratedConcrete)
      val pair = families(snapshot)
      assert(pair._1.maximumTripCount == 2 && pair._2.maximumTripCount == 3)
      val text = ScalaToMlirBridge.fromSnapshot(snapshot).text
      assert(text.linesIterator.count(_.contains("\"nodal.generate\"(")) == 2)
      assert(text.linesIterator.count(_.contains("\"nodal.digital_storage\"(")) == 1)
      assert(text.linesIterator.count(_.contains("\"nodal.generated_export\"(")) == 1)
      assert(text.linesIterator.count(_.contains("\"nodal.generated_member\"(")) == 1)
      assert(text.contains(pair._1.path + ".bound0") && text.contains(pair._2.path + ".bound0"))
    test("independent symbolic inner bound uses its actual outer static capture"):
      val snapshot = ConstructionKernel.inspect(new Increment58NestedGeneratedSymbolic)
      val pair = families(snapshot)
      val outer = pair._1
      val inner = pair._2
      assert(outer.maximumTripCount == 4 && inner.maximumTripCount == 8)
      val body = outer.digitalBody.get
      val position = body.captures.indexOf(snapshot.root + ".columns")
      assert(position >= 0)
      assert(body.arguments(position + 1).path == inner.upperExclusive)
      assert(body.arguments(position + 1).role == "static_capture")
      val text = ScalaToMlirBridge.fromSnapshot(snapshot).text
      assert(text.linesIterator.count(_.contains("\"nodal.generate\"(")) == 2)
      assert(text.contains("sym_name = \"columns\"") && text.contains("sym_name = \"rows\""))
    test("symbolic ordinal snapshot retains its existing exact outer capture"):
      val snapshot = ConstructionKernel.inspect(new Increment58NestedGeneratedSymbolicOrdinal)
      val pair = families(snapshot)
      val outer = pair._1
      val body = outer.digitalBody.get
      val position = body.captures.indexOf(snapshot.root + ".selected")
      val member = snapshot.digitalExpressions.find(_.generatedMember.nonEmpty).get
      assert(position >= 0)
      assert(member.generatedMember.get.ordinal == body.arguments(position + 1).path)
      val text = ScalaToMlirBridge.fromSnapshot(snapshot).text
      assert(text.linesIterator.count(_.contains("\"nodal.generated_member\"(")) == 1)
      bridgeRejected(
        snapshot.copy(digitalExpressions =
          snapshot.digitalExpressions.map(value =>
            if value.path == member.path then
              value.copy(generatedMember =
                value.generatedMember.map(
                  _.copy(ordinal = snapshot.root + ".selected")
                )
              )
            else value
          )
        ),
        "scope"
      )
    test("inner behavioral update captures enable through actual nested owners"):
      val snapshot = ConstructionKernel.inspect(new Increment58NestedGeneratedUpdated)
      val pair = families(snapshot)
      val outer = pair._1
      val inner = pair._2
      assert(snapshot.behavioralIfs.size == 1)
      assert(snapshot.behavioralIfs.head.parentRegion.contains(inner.path))
      assert(outer.digitalBody.get.captures.contains(snapshot.root + ".enable"))
      assert(inner.digitalBody.get.captures.exists(_.startsWith(outer.path + ".arg")))
      assert(snapshot.modules.flatMap(_.declarations).count(_.kind == "register") == 1)
      val text = ScalaToMlirBridge.fromSnapshot(snapshot).text
      assert(text.linesIterator.count(_.contains("\"nodal.behavioral_if\"(")) == 1)
      assert(text.contains("kind = \"register_next\""))
    test("inner ordinal existence is checked independently across the full envelope"):
      Vector(
        () => new Increment58NestedGeneratedConcrete(-1),
        () => new Increment58NestedGeneratedConcrete(3),
        () => new Increment58NestedGeneratedConcrete(0, 0),
        () => new Increment58NestedGeneratedSymbolic(1)
      ).foreach: source =>
        val failure = scala.util.Try(ConstructionKernel.inspect(source())).failed.get
        assert(failure.getMessage.contains("NODAL-AREA-058-INDEX"))
    test("forged nested parent projection escape and raw storage escape reject"):
      val snapshot = ConstructionKernel.inspect(new Increment58NestedGeneratedConcrete)
      val pair = families(snapshot)
      val outer = pair._1
      val inner = pair._2
      val member = snapshot.digitalExpressions.find(_.generatedMember.nonEmpty).get
      bridgeRejected(
        snapshot.copy(generatedRegions =
          snapshot.generatedRegions.map(value =>
            if value.path == inner.path then value.copy(parent = None) else value
          )
        ),
        "scope"
      )
      bridgeRejected(
        snapshot.copy(generatedRegions =
          snapshot.generatedRegions.map(value =>
            if value.path == inner.path then value.copy(parent = Some(inner.path)) else value
          )
        ),
        "cycle"
      )
      bridgeRejected(
        snapshot.copy(digitalExpressions =
          snapshot.digitalExpressions.map(value =>
            if value.path == member.path then value.copy(region = None) else value
          )
        ),
        "scope"
      )
      bridgeRejected(
        snapshot.copy(digitalAssignments =
          snapshot.digitalAssignments.map(value =>
            if value.region.contains(outer.path) then value.copy(value = inner.exports.head.target)
            else value
          )
        ),
        "scope"
      )
    test("outer induction dependent inner domain remains explicitly outside this slice"):
      val failure = scala.util.Try(
        ConstructionKernel.inspect(new Increment58NestedGeneratedDependent)
      ).failed.get
      assert(failure.getMessage.contains("NODAL-ITERATION-043-001"))
