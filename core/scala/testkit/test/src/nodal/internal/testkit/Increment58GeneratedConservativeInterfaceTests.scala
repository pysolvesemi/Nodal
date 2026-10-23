package nodal.internal.testkit
import nodal.*
import nodal.internal.bridge.*
import nodal_external.*
import utest.*
object Increment58GeneratedConservativeInterfaceTests extends TestSuite:
  val tests = Tests:
    test("actual local child and nested-member topology retains one template and aliases"):
      for build <- Vector(
          () => new Increment58GeneratedConservativeLocal,
          () => new Increment58GeneratedConservativeHierarchy,
          () => new Increment58GeneratedConservativeNestedMember,
          () => new Increment58GeneratedConservativeCount0,
          () => new Increment58GeneratedConservativeCount1
        )
      do
        val snapshot = ConstructionKernel.inspect(build())
        val text = ScalaToMlirBridge.fromSnapshot(snapshot, Backend.VerilogA).text
        val members = "\"nodal.member_access\""
        assert(text.sliding(members.length).count(_ == members) == 2)
        assert(text.contains("generated_owner"), text.contains("\"nodal.connect\""))
        assert(snapshot.generatedRegions.size == 1)
    test("unused forged fields role upgrades mixed and wrong region views reject"):
      for mode <- Vector("field", "role", "mixed") do
        val failure = scala.util.Try(
          ConstructionKernel.inspect(new Increment58GeneratedConservativeInvalid(mode))
        ).failed.get
        assert(
          failure.isInstanceOf[ConstructionException],
          failure.getMessage.contains("INTERFACE")
        )
      for build <- Vector(
          () => new Increment58GeneratedConservativeAncestor,
          () => new Increment58GeneratedConservativeNestedFamily,
          () => new Increment58GeneratedConservativeGuarded
        )
      do
        val failure = scala.util.Try(ConstructionKernel.inspect(build())).failed.get
        assert(
          failure.isInstanceOf[ConstructionException],
          failure.getMessage.contains("NODAL-INTERFACE-058-ANALOG")
        )
      val selected = scala.util.Try(
        ConstructionKernel.inspect(new Increment58GeneratedConservativeSelected)
      ).failed.get
      assert(
        selected.isInstanceOf[ConstructionException],
        selected.getMessage.contains(
          "NODAL-AREA-058-CAPABILITY: selected Interface requires ordinary digital members"
        )
      )
      var previous: Option[Increment58GeneratedConservativeLocal] = None
      val _ = ConstructionKernel.inspect:
        val module = new Increment58GeneratedConservativeLocal
        previous = Some(module)
        module
      val foreign = scala.util.Try(
        ConstructionKernel.inspect(new Increment58GeneratedConservativeForeign(previous.get.top))
      ).failed.get
      assert(
        foreign.isInstanceOf[ConstructionException],
        foreign.getMessage.contains("outer terminal has no captured identity")
      )
    test("bridge independently checks original leaf role discipline and generated scope"):
      val original = ConstructionKernel.inspect(new Increment58GeneratedConservativeLocal)
      val declaration =
        original.modules.flatMap(_.declarations).find(_.conservativeMember.nonEmpty).get
      val origin = declaration.conservativeMember.get
      val changes = Vector(
        declaration.copy(conservativeMember = Some(origin.copy(member = "absent"))),
        declaration.copy(conservativeMember = Some(origin.copy(endpoint = "absent"))),
        declaration.copy(conservativeMember = Some(origin.copy(role = "absent"))),
        declaration.copy(generatedScope = None),
        declaration.copy(generatedScope =
          declaration.generatedScope.map(_.copy(owner = "foreign"))
        ),
        declaration.copy(attributes =
          declaration.attributes.map((key, value) =>
            key -> (if key == "discipline" then "foreign" else value)
          )
        )
      )
      for changed <- changes do
        val forged = original.copy(modules =
          original.modules.map(module =>
            module.copy(declarations =
              module.declarations.map(value =>
                if value.path == declaration.path then changed else value
              )
            )
          )
        )
        val failure =
          scala.util.Try(ScalaToMlirBridge.fromSnapshot(forged, Backend.VerilogA)).failed.get
        assert(failure.isInstanceOf[BridgeException], failure.getMessage.contains("NODAL-BRIDGE"))
