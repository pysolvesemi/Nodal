package nodal.internal.testkit
import nodal.*
import nodal.internal.bridge.*
import nodal_external.*
import utest.*

object Increment58LexicalInterfaceTests extends TestSuite:
  private def rejected(snapshot: ConstructionSnapshot, reason: String): Unit =
    val failure = scala.util.Try(ScalaToMlirBridge.fromSnapshot(snapshot)).failed.get
    assert(failure.isInstanceOf[BridgeException], failure.getMessage.contains(reason))

  val tests = Tests:
    test("direct and selected lane-varying Interface retain one original lexical definition"):
      Vector(false, true).foreach: select =>
        val snapshot = ConstructionKernel.inspect(new Increment58LexicalInterfaceSource(select))
        val original = snapshot.interfaceContracts.find(_.boundary.isEmpty).get
        assert(original.definitionContexts.size == 1)
        assert(original.definitionContexts.head.region == snapshot.generatedRegions.head.path)
        val text = ScalaToMlirBridge.fromSnapshot(snapshot).text
        assert(text.sliding("\"nodal.interface\"".length).count(_ == "\"nodal.interface\"") == 1)
        if select then
          assert(snapshot.interfaceContracts.find(_.boundary.nonEmpty).get.actualMemberTypes ==
            Vector("data" -> "UInt(2)"))
    test("nested Vec payload and role application retain complete original declaration contexts"):
      val snapshot = ConstructionKernel.inspect(new Increment58LexicalNestedInterfaceSource)
      assert(snapshot.interfaceContracts.forall(_.definitionContexts.size == 2))
      assert(snapshot.interfaceContracts.find(_.boundary.nonEmpty).get.actualMemberTypes ==
        Vector("nested.data" -> "Vec(UInt(2);2)"))
      val _ = ScalaToMlirBridge.fromSnapshot(snapshot)
    test("arm-owned definition and selected actual type producers precede their uses"):
      val snapshot = ConstructionKernel.inspect(new Increment58LexicalGuardedInterfaceSource)
      assert(snapshot.interfaceContracts.forall(_.definitionContexts.head.region ==
        snapshot.structuralIfs.head.trueArm.path))
      val _ = ScalaToMlirBridge.fromSnapshot(snapshot)
    test("module invariant definition token inventory is independent of selected use"):
      Vector(false, true).foreach: select =>
        val snapshot =
          ConstructionKernel.inspect(new Increment58InvariantLexicalInterfaceSource(select))
        assert(snapshot.interfaceContracts.forall(_.definitionContexts.isEmpty))
        val _ = ScalaToMlirBridge.fromSnapshot(snapshot)
    test(
      "withRoles preserves original definition identity while sibling declarations stay distinct"
    ):
      val shared = ConstructionKernel.inspect(new Increment58SharedLexicalDefinitionSource)
      assert(shared.interfaceContracts.flatMap(_.definitionContexts).distinct.size == 1)
      val _ = ScalaToMlirBridge.fromSnapshot(shared)
      val siblings = ConstructionKernel.inspect(new Increment58SiblingLexicalDefinitionSource)
      assert(siblings.interfaceContracts.flatMap(_.definitionContexts).distinct.size == 2)
      val _ = ScalaToMlirBridge.fromSnapshot(siblings)
    test("unequal lane-dependent payload expression rejects before emission"):
      val failure = scala.util.Try(
        ConstructionKernel.inspect(new Increment58UnequalLexicalPayloadSource)
      ).failed.get
      assert(failure.isInstanceOf[ConstructionException], failure.getMessage.contains("type"))
    test("bridge independently rejects foreign absent and malformed definition ownership"):
      val original = ConstructionKernel.inspect(new Increment58LexicalInterfaceSource(true))
      val endpoint = original.interfaceContracts.find(_.boundary.isEmpty).get
      val context = endpoint.definitionContexts.head
      Vector(
        Vector(context.copy(owner = "foreign")) -> "actual module",
        Vector(context.copy(region = "absent")) -> "actual module",
        Vector(context.copy(application = "fake")) -> "complete original application",
        Vector(context.copy(identity = "fake")) -> "complete original application",
        Vector(context.copy(sourceOrdinal = -1)) -> "actual module",
        Vector(context, context) -> "unique bounded"
      ).foreach: (contexts, reason) =>
        rejected(
          original.copy(interfaceContracts =
            original.interfaceContracts.map(value =>
              if value.path == endpoint.path then value.copy(definitionContexts = contexts)
              else value
            )
          ),
          reason
        )
    test("selected context erasure wrong actual width and forward declaration reject causally"):
      val original = ConstructionKernel.inspect(new Increment58LexicalInterfaceSource(true))
      val selected = original.interfaceContracts.find(_.boundary.nonEmpty).get
      Vector(
        selected.copy(definitionContexts = Vector.empty) -> "projection changes exact owner",
        selected.copy(actualMemberTypes = Vector("data" -> "UInt(3)")) -> "type"
      ).foreach: (changed, reason) =>
        rejected(
          original.copy(interfaceContracts =
            original.interfaceContracts.map(value =>
              if value.path == selected.path then changed else value
            )
          ),
          reason
        )
      rejected(
        original.copy(interfaceContracts =
          original.interfaceContracts.map(value =>
            value.copy(definitionContexts =
              value.definitionContexts.map(_.copy(sourceOrdinal = 100))
            )
          )
        ),
        "precedes its actual lexical definition"
      )

    test("scheduled same-role forwarding has endpoint-qualified unique member identities"):
      val snapshot = ConstructionKernel.inspect(new Increment58SelectedInterfaceForwardSource)
      val text = ScalaToMlirBridge.fromSnapshot(snapshot).text
      val identities = "semantic_path = \"([^\"]+)\"".r.findAllMatchIn(text)
        .map(_.group(1)).filter(_.contains(".access.")).toVector
      assert(identities.size == 6, identities.distinct.size == 6)
      val selected = snapshot.interfaceContracts.flatMap(_.boundary).flatMap(_.selection)
      assert(selected.map(_.ordinal) == Vector("0", "1"))
      val connections = snapshot.digitalConnectivity.filter(_.kind == "interface-connect")
      assert(connections.size == 3, connections.map(_.target).distinct.size == 3)
      assert(connections.groupBy(_.source).values.map(_.size).toVector.sorted == Vector(1, 2))
