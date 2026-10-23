package nodal.internal.testkit

import java.nio.file.{Files, Path}
import java.time.Duration
import nodal.*
import nodal.internal.bridge.*
import nodal_external.Increment58TypedResolvedMemberSource
import scala.compiletime.testing.typeCheckErrors
import utest.*

object Increment58TypedResolvedMemberTests extends TestSuite:
  private def capture(mode: String = "read", late: Boolean = true) =
    ConstructionKernel.inspect(new Increment58TypedResolvedMemberSource(mode, late))
  val tests = Tests:
    test("exact nested member witness reuses one typed carrier and seals late actual widths"):
      for late <- Vector(false, true) do
        var source: Option[Increment58TypedResolvedMemberSource] = None
        val snapshot = ConstructionKernel.inspect:
          val value = new Increment58TypedResolvedMemberSource("read", late)
          source = Some(value)
          value
        assert(source.get.selected eq source.get.selectedAgain)
        val endpoint = snapshot.interfaceContracts.find(value =>
          value.owner == snapshot.root && value.boundary.exists(_.endpoint.endsWith(".connected"))
        ).get
        val selected = snapshot.resolvedNets.find(_.member.exists(_.endpoint == endpoint.path)).get
        assert(selected.member.get.member == "bus.pad", selected.member.get.role == "connect")
        assert(selected.dataType == "UInt(actualWidth)")
        assert(selected.placement.isEmpty, selected.profile.isEmpty, selected.boundary.isEmpty)
        val expression = snapshot.digitalExpressions.find(value =>
          value.owner == snapshot.root && value.operation == "digital_net_read"
        ).get
        assert(expression.dataType == "UInt(actualWidth)")
    test("actual member reads drives and mixed pass-through reuse member access not new nets"):
      Vector("read", "copy", "drive", "pass-through").foreach: mode =>
        val text = ScalaToMlirBridge.fromSnapshot(capture(mode)).text
        assert(text.contains("\"nodal.member_access\""), text.contains("path = \"bus.pad\""))
        assert(text.contains("\"nodal.net_read\""), !text.contains("\"nodal.net_boundary\""))
        assert(text.sliding("\"nodal.resolved_net\"".length)
          .count(_ == "\"nodal.resolved_net\"") == (if mode == "pass-through" then 2 else 1))
    test("member witness identity ordered ownership and role rights reject forged capabilities"):
      Vector(
        "wrong-field",
        "wrong-nested",
        "missing-nested",
        "drive-cannot-read",
        "connect-cannot-drive"
      ).foreach: mode =>
        val failure = scala.util.Try(capture(mode)).failed.get
        assert(failure.isInstanceOf[ConstructionException])
        assert(failure.asInstanceOf[ConstructionException].diagnostic.code ==
          "NODAL-INTERFACE-054-003")
      val foreign = scala.util.Try(capture("raw")).failed.get
      assert(foreign.isInstanceOf[ConstructionException])
      assert(foreign.asInstanceOf[ConstructionException].diagnostic.code ==
        "NODAL-INTERFACE-054-007")
      assert(foreign.getMessage.contains("belongs to another module"))
      val wrong = scala.util.Try(capture("wrong-type")).failed.get
      assert(wrong.isInstanceOf[ConstructionException])
      assert(wrong.asInstanceOf[ConstructionException].diagnostic.code == "NODAL-DIGITAL-055-003")
    test("public shared capability cannot fabricate placement constructors or drive evidence"):
      assert(typeCheckErrors("""import nodal.*
        def invalid(value: ResolvedDigital[Bits, DriveMode.PushPull]) = value.placement
      """).nonEmpty)
      assert(typeCheckErrors("""import nodal.*
        def invalid(value: ResolvedDigital[Bits, DriveMode.OpenDrain], data: Expr[Bits], enable: Expr[Bool]) =
          value.drive(data, enable)
      """).nonEmpty)
      assert(typeCheckErrors("""import nodal.*
        def invalid(value: ResolvedDigital[Bits, DriveMode.ReadOnly]) = value.highZ()
      """).nonEmpty)
    test("bridge and canonical artifact retain exact origin without physical placement fiction"):
      val snapshot = capture()
      val endpoint = snapshot.interfaceContracts.find(value =>
        value.owner == snapshot.root && value.boundary.exists(_.endpoint.endsWith(".connected"))
      ).get
      val selected = snapshot.resolvedNets.find(_.member.exists(_.endpoint == endpoint.path)).get
      val origin = selected.member.get
      val artifact = ReproducibilityContract.canonicalSnapshot(snapshot)
      assert(artifact.contains("\"resolved_member\""), artifact.contains("bus.pad"))
      Vector(
        origin.copy(member = "bus.missing"),
        origin.copy(endpoint = origin.endpoint + "_other"),
        origin.copy(role = "drive")
      ).foreach: changed =>
        val forged = snapshot.copy(
          resolvedNets = snapshot.resolvedNets.map(net =>
            if net.path == selected.path then net.copy(member = Some(changed)) else net
          ),
          modules = snapshot.modules.map(module =>
            module.copy(declarations =
              module.declarations.map(declaration =>
                if declaration.path == selected.path then
                  declaration.copy(resolvedMember = Some(changed))
                else declaration
              )
            )
          )
        )
        val failure = scala.util.Try(ScalaToMlirBridge.fromSnapshot(forged)).failed.get
        assert(failure.isInstanceOf[BridgeException])
        assert(failure.asInstanceOf[BridgeException].diagnostic.code ==
          "NODAL-BRIDGE-054-INTERFACE")
        assert(ReproducibilityContract.canonicalSnapshot(forged) != artifact)
    test("joined split retains exact contextual obligation for the actual child driver"):
      val snapshot = capture("split")
      val splits = snapshot.digitalExpressions.filter(value =>
        Set("digital_net_write_view", "digital_net_enable_view").contains(value.operation)
      )
      assert(splits.nonEmpty, splits.forall(_.hierarchyDriver))
      val text = ScalaToMlirBridge.fromSnapshot(snapshot).text
      assert(text.contains("hierarchy_driver = unit"), text.contains("\"nodal.net_split\""))

    test("mixed resolved component retains exact Interface domain constraints transitively"):
      val failure = scala.util.Try(ConstructionKernel.inspect(
        new nodal_external.Increment58ResolvedDomainSource(false)
      )).failed.get
      assert(failure.isInstanceOf[ConstructionException])
      assert(failure.asInstanceOf[ConstructionException].diagnostic.code ==
        "NODAL-INTERFACE-054-006")
      assert(failure.getMessage.contains(
        "resolved pass-through component crosses exact Interface domain ownership"
      ))
      val snapshot = ConstructionKernel.inspect(new nodal_external.Increment58ResolvedDomainSource)
      val right = snapshot.interfaceContracts.find(_.path.endsWith(".right")).get
      val otherDomain = snapshot.modules.flatMap(_.domains).find(_.name == "second").get.path
      val forged = snapshot.copy(
        interfaceContracts = snapshot.interfaceContracts.map(endpoint =>
          if endpoint.path == right.path then endpoint.copy(domain = otherDomain) else endpoint
        ),
        modules = snapshot.modules.map(module =>
          module.copy(declarations =
            module.declarations.map(declaration =>
              if declaration.path == right.path then declaration.copy(domain = Some(otherDomain))
              else declaration
            )
          )
        )
      )
      val rejected = scala.util.Try(ScalaToMlirBridge.fromSnapshot(forged)).failed.get
      assert(rejected.isInstanceOf[BridgeException])
      assert(rejected.asInstanceOf[BridgeException].diagnostic.code == "NODAL-BRIDGE-054-INTERFACE")
      assert(rejected.getMessage.contains(
        "resolved pass-through component crosses exact Interface domain ownership"
      ))
      val local = ScalaToMlirBridge.fromSnapshot(ConstructionKernel.inspect(
        new nodal_external.Increment58LocalResolvedMemberSource
      )).text
      assert(local.contains("\"nodal.net_split\""), !local.contains("\"nodal.resolved_net\""))

object Increment58TypedResolvedMemberNativeTests extends TestSuite:
  val tests = Tests:
    test(
      "actual typed nested member source passes default gates and preserves causal child feedback"
    ):
      Vector(
        "read",
        "copy",
        "drive",
        "pass-through",
        "feedback",
        "local",
        "same-domain",
        "split"
      ).foreach: mode =>
        val snapshot = ConstructionKernel.inspect:
          mode match
            case "local" => new nodal_external.Increment58LocalResolvedMemberSource
            case "same-domain" => new nodal_external.Increment58ResolvedDomainSource
            case _ => new Increment58TypedResolvedMemberSource(mode)
        val document = ScalaToMlirBridge.fromSnapshot(snapshot)
        val directory =
          Path.of(sys.env("NODAL_ROOT"), ".work", "f58-typed-resolved", "native", mode)
        Files.createDirectories(directory)
        val _ = Files.writeString(directory.resolve("input.mlir"), document.text)
        val result = NativeCompilerClient.run(
          document,
          NativeCompilerRequest(
            Path.of(sys.env("NODAL_NODALC")).toAbsolutePath,
            Vector("--nodal-gate-default"),
            directory,
            timeout = Duration.ofSeconds(90)
          )
        )
        val _ = Files.writeString(directory.resolve("output.mlir"), result.standardOutput)
        val _ = Files.writeString(directory.resolve("diagnostics.txt"), result.standardError)
        if mode == "feedback" then
          assert(result.isInstanceOf[NativeCompilerFailure])
          assert(result.standardError.contains("NODAL-DIGITAL-055-010"))
          assert(result.standardError.contains(
            "hierarchy connection contains a causal combinational dependency cycle"
          ))
        else
          result match
            case _: NativeCompilerSuccess => ()
            case failure: NativeCompilerFailure =>
              throw new java.lang.AssertionError(
                s"${failure.diagnostic}\n${failure.standardError}"
              )
