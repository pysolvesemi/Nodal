package nodal.internal.testkit

import java.nio.file.{Files, Path}
import java.time.Duration
import nodal.*
import nodal.internal.bridge.*
import nodal_external.*
import utest.*

object Increment58ExternalInoutTests extends TestSuite:
  private def capture(mode: String = "read") =
    ConstructionKernel.inspect(new Increment58ExternalSource(mode))
  private def externalFailure(body: => Any, message: String): Unit =
    val error = scala.util.Try(body).failed.get
    assert(error.isInstanceOf[ConstructionException])
    assert(error.asInstanceOf[ConstructionException].diagnostic.code == "NODAL-EXTERNAL-058-001")
    assert(error.getMessage.contains(message))
  val tests = Tests:
    test("explicit external signatures preserve exact pins and real child boundary identity"):
      Vector("read", "drive").foreach: mode =>
        val snapshot = capture(mode)
        val external = snapshot.modules.filter(_.externalName.nonEmpty)
        assert(external.size == 1, external.head.externalName.contains("Increment58ExternalPad"))
        assert(external.head.domains.isEmpty, external.head.instances.isEmpty)
        assert(external.head.declarations.map(_.name) == Vector("pin"))
        assert(external.head.declarations.forall(_.kind == "digital-inout"))
        val boundary = snapshot.resolvedNets.find(_.boundary.nonEmpty).get
        assert(boundary.boundary.get.endpoint == external.head.declarations.head.path)
        assert(snapshot.digitalExpressions.forall(_.owner != external.head.path))
        val text = ScalaToMlirBridge.fromSnapshot(snapshot).text
        assert(text.contains("external_name = \"Increment58ExternalPad\""))
        assert(text.contains("\"nodal.net_boundary\""), text.contains("\"nodal.net_pass_through\""))
      val low = ConstructionKernel.inspect(new Increment58ExternalOpenDrainSource)
      val lowText = ScalaToMlirBridge.fromSnapshot(low).text
      assert(lowText.contains("external_name = \"ExternalOpenDrainPad\""))
      assert(lowText.contains("\"nodal.net_drive_level\""))
    test("repeated exact external signatures share one emitted declaration but retain occurrences"):
      val snapshot = ConstructionKernel.inspect(new Increment58RepeatedExternalSource)
      assert(snapshot.modules.count(_.externalName.nonEmpty) == 2)
      val text = ScalaToMlirBridge.fromSnapshot(snapshot).text
      assert(text.sliding("external_name =".length).count(_ == "external_name =") == 1)
      assert(text.sliding("\"nodal.instance\"".length).count(_ == "\"nodal.instance\"") == 2)
      externalFailure(
        ConstructionKernel.inspect(new Increment58ConflictingExternalSource),
        "different exact signatures"
      )
    test("external bodies names and placement cannot borrow an ordinary module implementation"):
      externalFailure(
        ConstructionKernel.inspect(new Increment58ExternalBadSource),
        "declaration-only"
      )
      externalFailure(
        ConstructionKernel.inspect(new Increment58UnsupportedExternalSource("empty")),
        "declaration-only"
      )
      externalFailure(
        ConstructionKernel.inspect(new Increment58UnsupportedExternalSource("placement")),
        "direct portable black-box"
      )
      externalFailure(
        ConstructionKernel.inspect(new Increment58UnsupportedExternalSource("name")),
        "canonical exact physical symbol"
      )
    test(
      "one visible driver cannot prove uniqueness against an actual opaque external contribution"
    ):
      val failure = scala.util.Try(capture("split")).failed.get
      assert(failure.isInstanceOf[ConstructionException])
      assert(failure.asInstanceOf[ConstructionException].diagnostic.code ==
        "NODAL-INTERFACE-054-012")
      assert(failure.getMessage.contains("opaque external contribution"))
    test("bridge independently rejects a real owned executable expression inside an external body"):
      val snapshot = capture("drive")
      val external = snapshot.modules.find(_.externalName.nonEmpty).get
      val expression = snapshot.digitalExpressions.find(_.operation == "digital_net_read").get
      val forged = snapshot.copy(digitalExpressions =
        snapshot.digitalExpressions :+
          expression.copy(
            path = external.path + ".forged",
            owner = external.path,
            operands = Vector(external.declarations.head.path)
          )
      )
      externalFailure(ScalaToMlirBridge.fromSnapshot(forged), "declaration-only")
      val pin = external.declarations.head
      val borrowedOrigin = snapshot.copy(modules =
        snapshot.modules.map(value =>
          if value.path == external.path then
            value.copy(declarations =
              Vector(pin.copy(
                conservativeMember = Some(KernelInterfaceMemberOrigin("other", "pin", "connect"))
              ))
            )
          else value
        )
      )
      externalFailure(ScalaToMlirBridge.fromSnapshot(borrowedOrigin), "direct portable black-box")
    test("canonical artifacts retain exact external symbols only on actual external definitions"):
      val snapshot = capture()
      val artifact = ReproducibilityContract.canonicalSnapshot(snapshot)
      assert(artifact.contains("\"external_name\": \"Increment58ExternalPad\""))
      val renamed = snapshot.copy(modules =
        snapshot.modules.map(value =>
          if value.externalName.nonEmpty then value.copy(externalName = Some("OtherPad")) else value
        )
      )
      assert(ReproducibilityContract.canonicalSnapshot(renamed) != artifact)
      val historical = snapshot.copy(modules = snapshot.modules.map(_.copy(externalName = None)))
      assert(!ReproducibilityContract.canonicalSnapshot(historical).contains("\"external_name\""))
    test("a placement tag alone neither declares an external body nor hides local split proof"):
      val snapshot = ConstructionKernel.inspect(new Increment58LocalTaggedInoutSource)
      assert(snapshot.modules.forall(_.externalName.isEmpty))
      assert(snapshot.digitalExpressions.exists(_.operation == "digital_net_write_view"))
      assert(!ScalaToMlirBridge.fromSnapshot(snapshot).text.contains("external_name"))

    test("local alias pins and independent reused externs retain distinct declaration identity"):
      val local = ConstructionKernel.inspect(new Increment58LocalAliasedInoutSource)
      assert(local.resolvedNets.count(_.boundary.isEmpty) == 2)
      assert(local.digitalConnectivity.count(_.kind == "inout-pass-through") == 1)
      val localText = ScalaToMlirBridge.fromSnapshot(local).text
      assert(localText.sliding("\"nodal.resolved_net\"".length).count(_ ==
        "\"nodal.resolved_net\"") == 2)
      val independent = ConstructionKernel.inspect(new Increment58IndependentExternalSource)
      assert(independent.modules.count(_.externalName.nonEmpty) == 2)
      val joins = independent.digitalConnectivity.filter(_.kind == "inout-pass-through")
      assert(joins.size == 2)
      assert(joins.map(_.target).distinct.size == 2)
      val text = ScalaToMlirBridge.fromSnapshot(independent).text
      assert(text.sliding("external_name =".length).count(_ == "external_name =") == 1)
      val fixed = ConstructionKernel.inspect(new Increment58FixedResolvedInterfaceSource)
      assert(fixed.modules.forall(_.externalName.isEmpty))
      val fixedText = ScalaToMlirBridge.fromSnapshot(fixed).text
      assert(fixedText.contains("bus.pad"), fixedText.contains("\"nodal.interface_boundary\""))
      assert(!fixedText.contains("hierarchy_pass_through"))

object Increment58ExternalInoutNativeTests extends TestSuite:
  val tests = Tests:
    test(
      "actual external read drive open-drain repeated and internal-tag witnesses pass full gate"
    ):
      Vector(
        "read",
        "drive",
        "open-drain",
        "repeated",
        "local-tag",
        "local-alias",
        "independent",
        "interface-fixed"
      ).foreach: mode =>
        val snapshot = ConstructionKernel.inspect:
          mode match
            case "open-drain" => new Increment58ExternalOpenDrainSource
            case "repeated" => new Increment58RepeatedExternalSource
            case "local-tag" => new Increment58LocalTaggedInoutSource
            case "local-alias" => new Increment58LocalAliasedInoutSource
            case "independent" => new Increment58IndependentExternalSource
            case "interface-fixed" => new Increment58FixedResolvedInterfaceSource
            case _ => new Increment58ExternalSource(mode)
        val document = ScalaToMlirBridge.fromSnapshot(snapshot)
        val directory =
          Path.of(sys.env("NODAL_ROOT"), ".work", "f58-external-inout", "native", mode)
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
        result match
          case _: NativeCompilerSuccess => ()
          case failure: NativeCompilerFailure =>
            throw new java.lang.AssertionError(s"${failure.diagnostic}\n${failure.standardError}")
