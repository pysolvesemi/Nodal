package nodal.internal.testkit
import nodal.*
import nodal.internal.bridge.*
import nodal_external.*
import java.nio.file.{Files, Path}
object Increment58GeneratedConservativeReplay:
  def main(args: Array[String]): Unit =
    require(args.length == 1)
    val dir = Path.of(args(0))
    val _ = Files.createDirectories(dir)
    val positives = Vector(
      "local" -> (() => new Increment58GeneratedConservativeLocal),
      "child" -> (() => new Increment58GeneratedConservativeHierarchy),
      "nested-member" -> (() => new Increment58GeneratedConservativeNestedMember),
      "count0" -> (() => new Increment58GeneratedConservativeCount0),
      "count1" -> (() => new Increment58GeneratedConservativeCount1)
    )
    positives.foreach: (name, build) =>
      val snapshot = ConstructionKernel.inspect(build())
      val text = ScalaToMlirBridge.fromSnapshot(snapshot, Backend.VerilogA).text
      assert(text.sliding("\"nodal.member_access\"".length).count(_ == "\"nodal.member_access\"") ==
        2)
      assert(text.contains("generated_owner"))
      val _ = Files.writeString(dir.resolve(name + ".mlir"), text)
      println("PASS source " + name)
    Vector("field", "role", "mixed").foreach: mode =>
      val failure = scala.util.Try(
        ConstructionKernel.inspect(new Increment58GeneratedConservativeInvalid(mode))
      ).failed.get
      assert(failure.isInstanceOf[ConstructionException], failure)
      assert(failure.getMessage.contains("INTERFACE"), failure)
      println("PASS causal " + mode + " " + failure.getMessage)
    Vector(
      "ancestor" -> (() => new Increment58GeneratedConservativeAncestor),
      "nested-family" -> (() => new Increment58GeneratedConservativeNestedFamily),
      "selected" -> (() => new Increment58GeneratedConservativeSelected)
    ).foreach: (name, build) =>
      val failure = scala.util.Try(ConstructionKernel.inspect(build())).failed.get
      assert(failure.isInstanceOf[ConstructionException], failure)
      assert(
        failure.getMessage.contains(if name == "selected" then
          "NODAL-AREA-058-CAPABILITY: selected Interface requires ordinary digital members"
        else "NODAL-INTERFACE-058-ANALOG"),
        failure
      )
      println("PASS causal " + name + " " + failure.getMessage)
    var previous: Option[Increment58GeneratedConservativeLocal] = None
    val _ = ConstructionKernel.inspect:
      val module = new Increment58GeneratedConservativeLocal
      previous = Some(module)
      module
    val foreign = scala.util.Try(
      ConstructionKernel.inspect(new Increment58GeneratedConservativeForeign(previous.get.top))
    ).failed.get
    assert(
      foreign.isInstanceOf[ConstructionException] && foreign.getMessage.contains("terminal"),
      foreign
    )
    println("PASS causal foreign " + foreign.getMessage)
    val guarded = scala.util.Try(
      ConstructionKernel.inspect(new Increment58GeneratedConservativeGuarded)
    ).failed.get
    assert(
      guarded.isInstanceOf[ConstructionException] &&
        guarded.getMessage.contains("NODAL-INTERFACE-058-ANALOG"),
      guarded
    )
    println("PASS causal guarded " + guarded.getMessage)
    val original = ConstructionKernel.inspect(new Increment58GeneratedConservativeLocal)
    val declaration =
      original.modules.flatMap(_.declarations).find(_.conservativeMember.nonEmpty).get
    val origin = declaration.conservativeMember.get
    val changes = Vector(
      declaration.copy(conservativeMember = Some(origin.copy(member = "absent"))),
      declaration.copy(conservativeMember = Some(origin.copy(endpoint = "absent"))),
      declaration.copy(conservativeMember = Some(origin.copy(role = "absent"))),
      declaration.copy(generatedScope = None),
      declaration.copy(generatedScope = declaration.generatedScope.map(_.copy(owner = "foreign"))),
      declaration.copy(attributes =
        declaration.attributes.map((key, value) =>
          key -> (if key == "discipline" then "foreign" else value)
        )
      )
    )
    changes.zipWithIndex.foreach: (changed, index) =>
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
      assert(failure.isInstanceOf[BridgeException], failure)
      println("PASS bridge-causal " + index + " " + failure.getMessage)
