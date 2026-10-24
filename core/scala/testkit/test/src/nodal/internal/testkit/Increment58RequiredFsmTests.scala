package nodal.internal.testkit

import java.nio.file.{Files, Path}
import java.time.Duration
import nodal.*
import nodal.internal.bridge.*
import utest.*

object Increment58RequiredFsmTests extends TestSuite:
  private def capture(profile: String = "mixed"): ConstructionSnapshot =
    ConstructionKernel.inspect(new nodal_external.Increment58RequiredFsmSource(profile))

  private def changed(
      value: ConstructionSnapshot,
      path: String,
      transform: KernelDeclarationSnapshot => KernelDeclarationSnapshot
  ): ConstructionSnapshot =
    value.copy(modules =
      value.modules.map(module =>
        module.copy(declarations =
          module.declarations.map(declaration =>
            if declaration.path == path then transform(declaration) else declaration
          )
        )
      )
    )

  private def rejectsBridge(snapshot: ConstructionSnapshot): Unit =
    val failure = scala.util.Try(ScalaToMlirBridge.fromSnapshot(snapshot)).failed.get
    assert(failure.isInstanceOf[BridgeException])

  val tests = Tests:
    test("one graph retains contextual initialization in both actual occurrences"):
      val value = capture()
      assert(value.flatFsms.size == 2)
      val privateStorage = value.flatFsms.flatMap(machine =>
        Vector(machine.storage, machine.control.get.storage)
      ).toSet
      val declarations = value.modules.flatMap(_.declarations).filter(declaration =>
        privateStorage(declaration.path)
      )
      assert(declarations.size == 4)
      assert(declarations.map(_.path).distinct.size == 4)
      val localNames = value.flatFsms.map: machine =>
        val names = declarations.filter(_.path.startsWith(machine.owner + ".")).map(_.name).sorted
        assert(names.size == 2, names.distinct.size == 2)
        names
      assert(localNames(0) == localNames(1))
      assert(declarations.forall(_.attributes.contains("initializer_policy" -> "if_reset")))
      assert(declarations.forall(declaration =>
        !declaration.attributes.contains("initial" -> "none")
      ))
      val required = value.modules.flatMap(_.domains).filter(_.kind == "required")
      assert(required.size == 2, required.forall(_.resetProjection.isEmpty))
      assert(value.modules.find(_.path == value.root).get.instances.size == 2)

    test("bridge keeps one reusable formal machine rather than material specialization"):
      val text = ScalaToMlirBridge.fromSnapshot(capture()).text
      assert(text.linesIterator.count(_.contains("\"nodal.flat_fsm\"")) == 1)
      assert(text.linesIterator.count(line =>
        line.contains("\"nodal.digital_storage\"") &&
          line.contains("initializer_policy = \"if_reset\"")
      ) == 2)
      assert(text.contains("\"nodal.domain_requirement\""))
      assert(text == ScalaToMlirBridge.fromSnapshot(capture()).text)

    test("ordinary initialized register still rejects a resetless occurrence"):
      val failure = scala.util.Try(capture("mandatory")).failed.get
      assert(failure.isInstanceOf[ConstructionException])
      assert(failure.getMessage.contains("resetless register cannot have a reset initializer"))

    test("bridge rejects missing unknown and non-FSM conditional initialization"):
      val value = capture()
      val machine = value.flatFsms.head
      rejectsBridge(changed(
        value,
        machine.storage,
        declaration =>
          declaration.copy(attributes =
            declaration.attributes.filterNot(
              _._1 == "initializer_policy"
            )
          )
      ))
      rejectsBridge(changed(
        value,
        machine.storage,
        declaration =>
          declaration.copy(attributes = declaration.attributes.map:
            case ("initializer_policy", _) => "initializer_policy" -> "guess_reset"
            case pair => pair)
      ))
      val ordinary =
        value.modules.find(_.path == machine.owner).get.declarations.find(declaration =>
          declaration.kind == "register" &&
            declaration.path != machine.storage &&
            declaration.path != machine.control.get.storage
        ).get
      rejectsBridge(changed(
        value,
        ordinary.path,
        declaration =>
          declaration.copy(attributes =
            declaration.attributes :+ ("initializer_policy" -> "if_reset")
          )
      ))

    test("required machine cannot stand alone without whole-root binding"):
      val failure = scala.util.Try(
        ConstructionKernel.inspect(new nodal_external.Increment58RequiredFsmLeaf)
      ).failed.get
      assert(failure.isInstanceOf[ConstructionException])
      assert(failure.getMessage.contains("NODAL-ROOT-DOMAIN-016"))

    test("material edges and forwarded requirements remain occurrence owned"):
      Vector("material", "forwarded").foreach: profile =>
        val value = capture(profile)
        assert(value.flatFsms.size == 2)
        assert(value.modules.flatMap(_.domains).filter(_.kind == "required").forall(
          _.resetProjection.isEmpty
        ))
        val text = ScalaToMlirBridge.fromSnapshot(value).text
        assert(text.linesIterator.count(_.contains("\"nodal.flat_fsm\"")) == 1)

object Increment58RequiredFsmNativeTests extends TestSuite:
  val tests = Tests:
    test("configured native verifies contextual source and normalizes formal updates"):
      val executable = sys.env.getOrElse(
        "NODAL_NODALC",
        throw new IllegalStateException("NODAL_NODALC is required for contextual FSM acceptance")
      )
      val directory =
        Path.of(".work", "f58-statecharts", "required", "source-native").toAbsolutePath
      Files.createDirectories(directory)
      Vector("mixed", "material", "forwarded").foreach: profile =>
        val document = ScalaToMlirBridge.fromSnapshot(ConstructionKernel.inspect(
          new nodal_external.Increment58RequiredFsmSource(profile)
        ))
        Files.writeString(directory.resolve(s"$profile.source.mlir"), document.text)
        Vector("--nodal-gate-default", "--nodal-normalize-sequential").foreach: pass =>
          val request = NativeCompilerRequest(
            Path.of(executable).toAbsolutePath,
            Vector(pass),
            directory,
            timeout = Duration.ofSeconds(90)
          )
          NativeCompilerClient.run(document, request) match
            case success: NativeCompilerSuccess =>
              if pass == "--nodal-normalize-sequential" then
                assert(!success.normalizedMlir.contains("\"nodal.flat_fsm\""))
                assert(success.normalizedMlir.contains("\"nodal.sequential_update\""))
                assert(success.normalizedMlir.contains("initializer_policy = \"if_reset\""))
              else assert(success.normalizedMlir.contains("nodal.digital.materialization"))
            case failure: NativeCompilerFailure =>
              throw new java.lang.AssertionError(s"${failure.diagnostic}\n${failure.standardError}")
