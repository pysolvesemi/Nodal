package nodal.internal.testkit

import nodal.*
import nodal.internal.bridge.*
import nodal_external.*
import scala.compiletime.testing.typeCheckErrors
import utest.*

object Increment58StandaloneInoutTests extends TestSuite:
  private def capture(mode: String = "read", late: Boolean = true) =
    ConstructionKernel.inspect(new Increment58StandaloneInoutSource(mode, late))
  val tests = Tests:
    test("actual standalone selector reuses one carrier and seals late and early element types"):
      for late <- Vector(false, true) do
        var source: Option[Increment58StandaloneInoutSource] = None
        val snapshot = ConstructionKernel.inspect:
          val value = new Increment58StandaloneInoutSource("read", late)
          source = Some(value)
          value
        assert(source.get.projected eq source.get.selectedAgain)
        val boundary = snapshot.resolvedNets.filter(_.boundary.nonEmpty)
        assert(boundary.size == 1, snapshot.resolvedNets.size == 2)
        assert(boundary.head.dataType == "UInt(actualWidth)")
        assert(boundary.head.boundary.get.endpoint.endsWith(".child.pad"))
        val declaration =
          snapshot.modules.flatMap(_.declarations).find(_.path == boundary.head.path).get
        assert(declaration.resolvedBoundary == boundary.head.boundary, declaration.domain.isEmpty)
        val read = snapshot.digitalExpressions.find(value =>
          value.owner == snapshot.root && value.operation == "digital_net_read"
        ).get
        assert(read.dataType == "UInt(actualWidth)")
    test("bridge emits an alias rather than another physical net for actual reads and drivers"):
      Vector("read", "copy", "bitwise", "drive", "pass-through").foreach: mode =>
        val snapshot = capture(mode)
        val text = ScalaToMlirBridge.fromSnapshot(snapshot).text
        assert(text.sliding("\"nodal.net_boundary\"".length).count(_ == "\"nodal.net_boundary\"") ==
          1)
        val originals =
          text.sliding("\"nodal.resolved_net\"".length).count(_ == "\"nodal.resolved_net\"")
        assert(originals == (if mode == "pass-through" then 2 else 1))
        assert(text.contains("\"nodal.net_read\""))
    test(
      "equal bounds cannot satisfy final type identity and joined split counts both actual drivers"
    ):
      val wrong = scala.util.Try(capture("wrong-bitwise")).failed.get
      assert(wrong.isInstanceOf[ConstructionException])
      assert(wrong.asInstanceOf[ConstructionException].diagnostic.code == "NODAL-DIGITAL-055-003")
      val split = scala.util.Try(capture("split")).failed.get
      assert(split.isInstanceOf[ConstructionException])
      assert(split.asInstanceOf[ConstructionException].diagnostic.code == "NODAL-INTERFACE-054-012")
      assert(split.getMessage.contains(
        "split is ambiguous because the resolved net has multiple conditional drivers"
      ))
    test("bridge independently rejects forged exact child mode policy and element proofs"):
      val snapshot = capture()
      val selected = snapshot.resolvedNets.find(_.boundary.nonEmpty).get
      val boundary = selected.boundary.get
      val variants = Vector(
        selected.copy(boundary = Some(boundary.copy(endpoint = boundary.endpoint + "_missing"))),
        selected.copy(boundary = Some(boundary.copy(instance = boundary.instance + "_missing"))),
        selected.copy(mode = "open-drain"),
        selected.copy(profile = "TechnologyMapped"),
        selected.copy(placement = "PadCellBoundary"),
        selected.copy(dataType = "UInt(otherWidth)")
      )
      variants.foreach: changed =>
        val forged = snapshot.copy(
          resolvedNets =
            snapshot.resolvedNets.map(net => if net.path == selected.path then changed else net),
          modules = snapshot.modules.map(module =>
            module.copy(declarations =
              module.declarations.map(declaration =>
                if declaration.path != selected.path then declaration
                else
                  declaration.copy(
                    dataType = Some(changed.dataType),
                    resolvedBoundary = changed.boundary,
                    attributes = declaration.attributes.map((name, value) =>
                      name ->
                        (name match
                          case "mode" => changed.mode
                          case "profile" => changed.profile
                          case "placement" => changed.placement
                          case _ => value)
                    )
                  )
              )
            )
          )
        )
        val failure = scala.util.Try(ScalaToMlirBridge.fromSnapshot(forged)).failed.get
        assert(failure.isInstanceOf[BridgeException])
        assert(failure.asInstanceOf[BridgeException].diagnostic.code ==
          "NODAL-BRIDGE-054-INTERFACE")
    test("canonical artifact preserves exact net boundary relation and actual descriptor"):
      val snapshot = capture()
      val original = ReproducibilityContract.canonicalSnapshot(snapshot)
      val selected = snapshot.resolvedNets.find(_.boundary.nonEmpty).get
      val boundary = selected.boundary.get
      assert(
        original.contains("\"resolved_boundary\""),
        original.contains(boundary.instance),
        original.contains(boundary.endpoint)
      )
      val changed = snapshot.copy(resolvedNets =
        snapshot.resolvedNets.map(net =>
          if net.path == selected.path then
            net.copy(boundary = Some(boundary.copy(endpoint = boundary.endpoint + "_other")))
          else net
        )
      )
      assert(ReproducibilityContract.canonicalSnapshot(changed) != original)
      assert(ReproducibilityContract.canonicalSnapshot(capture()) == original)

    test("standalone mode evidence forbids read-only and open-drain arbitrary drivers"):
      val readonly = typeCheckErrors("""import nodal.*
        def drive(endpoint: DigitalInout[Bits, DriveMode.ReadOnly], enable: Expr[Bool]): Unit =
          endpoint.drive(endpoint.read, enable)
      """)
      val drain = typeCheckErrors("""import nodal.*
        def drive(endpoint: DigitalInout[Bits, DriveMode.OpenDrain], enable: Expr[Bool]): Unit =
          endpoint.drive(endpoint.read, enable)
      """)
      val release = typeCheckErrors("""import nodal.*
        def release(endpoint: DigitalInout[Bits, DriveMode.ReadOnly]): Unit = endpoint.highZ()
      """)
      assert(readonly.exists(_.message.contains("ArbitraryDrive")))
      assert(drain.exists(_.message.contains("ArbitraryDrive")))
      assert(release.exists(_.message.contains("ReleasableDrive")))
    test("literal constrained-mode projections retain original capability and driver kinds"):
      val snapshot = ConstructionKernel.inspect(new Increment58StandaloneCapabilitiesSource)
      val selected = snapshot.resolvedNets.filter(_.boundary.nonEmpty)
      assert(selected.size == 3, snapshot.resolvedNets.size == 6)
      assert(selected.map(_.mode).toSet == Set("open-drain", "open-source", "read-only"))
      assert(selected.forall(_.dataType == "Bits(4)"))
      assert(selected.find(_.mode == "open-drain").get.operations ==
        Vector("inout-drive-low", "inout-high-z"))
      assert(selected.find(_.mode == "open-source").get.operations ==
        Vector("inout-drive-high", "inout-high-z"))
      assert(selected.find(_.mode == "read-only").get.operations.isEmpty)
      val document = ScalaToMlirBridge.fromSnapshot(snapshot).text
      assert(document.sliding("\"nodal.net_boundary\"".length)
        .count(_ == "\"nodal.net_boundary\"") == 3)
    test("standalone projection rejects local sibling and wrapped foreign capabilities"):
      Vector("local", "sibling", "wrapped").foreach: mode =>
        val failure = scala.util.Try(ConstructionKernel.inspect(
          new Increment58StandaloneOwnershipSource(mode)
        )).failed.get
        assert(failure.isInstanceOf[ConstructionException])
        val diagnostic = failure.asInstanceOf[ConstructionException].diagnostic
        if mode == "wrapped" then
          assert(diagnostic.code == "NODAL-SEQUENTIAL-056-001")
          assert(diagnostic.message == "sequential dependency belongs to another module")
        else
          assert(diagnostic.code == "NODAL-HIERARCHY-058-INOUT")
          assert(diagnostic.message.contains("immediate child's direct resolved net"))
      val descendant = scala.util.Try(ConstructionKernel.inspect(
        new Increment58StandaloneDescendantSource
      )).failed.get
      assert(descendant.isInstanceOf[ConstructionException])
      assert(descendant.asInstanceOf[ConstructionException].diagnostic.code ==
        "NODAL-HIERARCHY-044")
      assert(descendant.getMessage.contains("exact lexical Module/Generate scope"))
    test("resolved snapshot reporting preserves ordered effects across a representative inventory"):
      val snapshot = ConstructionKernel.inspect(new Increment58ResolvedSnapshotScaleSource(128))
      assert(snapshot.resolvedNets.size == 128)
      assert(snapshot.resolvedNets.map(_.path) == snapshot.resolvedNets.map(_.path).sorted)
      snapshot.resolvedNets.foreach: net =>
        val name = net.path.split('.').last
        val connections = if name == "pad_0" || name == "pad_127" then 1 else 2
        assert(net.operations == Vector("inout-high-z", "inout-drive", "inout-high-z") ++
          Vector.fill(connections)("inout-pass-through"))
      assert(ReproducibilityContract.canonicalSnapshot(snapshot) ==
        ReproducibilityContract.canonicalSnapshot(ConstructionKernel.inspect(
          new Increment58ResolvedSnapshotScaleSource(128)
        )))

object Increment58StandaloneInoutNativeTests extends TestSuite:
  val tests = Tests:
    test("actual source standalone aliases and conditional drivers pass the default semantic gate"):
      Vector(
        "read",
        "copy",
        "bitwise",
        "drive",
        "pass-through",
        "feedback",
        "cross-feedback"
      ).foreach: mode =>
        val document = ScalaToMlirBridge.fromSnapshot(
          ConstructionKernel.inspect(new Increment58StandaloneInoutSource(mode))
        )
        val directory = java.nio.file.Path.of(
          sys.env("NODAL_ROOT"),
          ".work",
          "f58-standalone-source",
          mode
        ).toAbsolutePath
        java.nio.file.Files.createDirectories(directory)
        val _ = java.nio.file.Files.writeString(directory.resolve("input.mlir"), document.text)
        val result = NativeCompilerClient.run(
          document,
          NativeCompilerRequest(
            java.nio.file.Path.of(sys.env("NODAL_NODALC")).toAbsolutePath,
            Vector("--nodal-gate-default"),
            directory,
            timeout = java.time.Duration.ofSeconds(90)
          )
        )
        val _ = java.nio.file.Files.writeString(
          directory.resolve("diagnostics.txt"),
          result.standardError
        )
        if mode == "feedback" || mode == "cross-feedback" then
          assert(result.isInstanceOf[NativeCompilerFailure])
          if mode == "feedback" then
            assert(result.standardError.contains("combinational value cycle"))
          else assert(result.standardError.contains("causal combinational dependency cycle"))
        else
          result match
            case success: NativeCompilerSuccess =>
              assert(success.normalizedMlir.contains("nodal.net_boundary"))
            case failure: NativeCompilerFailure =>
              throw new java.lang.AssertionError(s"${failure.diagnostic}\n${failure.standardError}")

    test("literal open-drain open-source and read-only boundary uses pass the default gate"):
      val document = ScalaToMlirBridge.fromSnapshot(
        ConstructionKernel.inspect(new Increment58StandaloneCapabilitiesSource)
      )
      val directory = java.nio.file.Path.of(
        sys.env("NODAL_ROOT"),
        ".work",
        "f58-standalone-source",
        "capabilities"
      ).toAbsolutePath
      java.nio.file.Files.createDirectories(directory)
      val _ = java.nio.file.Files.writeString(directory.resolve("input.mlir"), document.text)
      val result = NativeCompilerClient.run(
        document,
        NativeCompilerRequest(
          java.nio.file.Path.of(sys.env("NODAL_NODALC")).toAbsolutePath,
          Vector("--nodal-gate-default"),
          directory,
          timeout = java.time.Duration.ofSeconds(90)
        )
      )
      val _ =
        java.nio.file.Files.writeString(directory.resolve("diagnostics.txt"), result.standardError)
      result match
        case success: NativeCompilerSuccess =>
          assert(success.normalizedMlir.contains("nodal.net_drive_level"))
          assert(success.normalizedMlir.contains("nodal.net_boundary"))
        case failure: NativeCompilerFailure =>
          throw new java.lang.AssertionError(s"${failure.diagnostic}\n${failure.standardError}")
