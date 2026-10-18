package nodal.internal.testkit

import nodal.*
import nodal.internal.bridge.*
import utest.*

final class Increment43RetainedScalarSource extends Module:
  val scalar = variable(Real, 1.0.real)
  val flag = variable(Bool, true.B)
  val sink = variable(Real, 0.0.real)
  analogProcedure:
    on(initialStep):
      val uniform = Vec.fill(Vec(Real, 2, 3))(scalar + 1.0.real)
      val selected = uniform.slice(1, 3, axis = 1).flatten.at(2)
      val local = variable(Real, selected + scalar)
      analogConditional:
        analogWhen(flag && (selected > scalar)):
          sink := local

object Increment43ScalarSourceBridgeTests extends TestSuite:
  import AnalogProceduralRuntime.*
  import AnalogControlFlowRuntime.Statement

  private def snapshot: ConstructionSnapshot =
    ConstructionKernel.inspect(new Increment43RetainedScalarSource)

  private def reject(input: ConstructionSnapshot, message: String): Unit =
    val result = scala.util.Try(ScalaToMlirBridge.fromSnapshot(input)).failed.get
    assert(result.getMessage.contains("NODAL-BRIDGE-049"), result.getMessage.contains(message))

  private def initializer(input: ConstructionSnapshot)(change: Value => Value)
      : ConstructionSnapshot =
    val program = input.analogProcedural.head
    val local = program.variables.indexWhere(_.initializer.exists(_.scalarSources.nonEmpty))
    assert(local >= 0)
    input.copy(analogProcedural =
      Vector(program.copy(variables =
        program.variables.updated(
          local,
          program.variables(local).copy(initializer =
            program.variables(local).initializer.map(change)
          )
        )
      ))
    )

  private def view(value: Value)(change: KernelShapeIndexSnapshot => KernelShapeIndexSnapshot)
      : Value =
    value.source match
      case Some(ShapedSource.View(parent, selected: KernelShapeIndexSnapshot)) =>
        value.copy(source = Some(ShapedSource.View(parent, change(selected))))
      case _ => throw new java.lang.AssertionError("expected actual selected scalar source")

  private def changeFill(value: Value)(change: Value => Value): Value = value.source match
    case Some(ShapedSource.View(parent, selected)) =>
      value.copy(source = Some(ShapedSource.View(changeFill(parent)(change), selected)))
    case Some(ShapedSource.Fill(scalar)) =>
      value.copy(source = Some(ShapedSource.Fill(change(scalar))))
    case _ => throw new java.lang.AssertionError("expected actual uniform source chain")

  val tests: Tests = Tests:
    test("retained scalar views validate exact input result bounds and units before erasure"):
      val captured = snapshot
      val valid = ScalaToMlirBridge.fromSnapshot(captured).text
      assert(valid.contains("initializer_form = \"expression\""))
      assert(valid.contains("condition_value = \"bool_and("))
      for form <- 0 to 4 do
        val forged = initializer(captured): input =>
          val leaf = input.scalarSources.head
          val bad = form match
            case 0 => view(leaf)(_.copy(input = "Increment43RetainedScalarSource.scalar"))
            case 1 => view(leaf)(_.copy(path = "Increment43RetainedScalarSource.scalar"))
            case 2 => view(leaf)(_.copy(indices = Vector("4")))
            case 3 => view(leaf)(_.copy(owner = "Foreign"))
            case _ => leaf.copy(valueType = leaf.valueType.copy(dimension = "time"))
          input.copy(scalarSources = Vector(bad))
        val messages = Vector(
          "input identity",
          "result identity",
          "not in bounds",
          "input identity",
          "forged value type"
        )
        reject(forged, messages(form))

    test("missing unused and conflicting scalar attachments cannot become text overrides"):
      val captured = snapshot
      reject(
        initializer(captured)(_.copy(scalarSources = Vector.empty)),
        "missing a retained source"
      )
      reject(
        initializer(captured)(input => input.copy(rendered = "1.0")),
        "unused retained scalar source"
      )
      reject(
        initializer(captured)(input =>
          input.copy(scalarSources = input.scalarSources ++ input.scalarSources)
        ),
        "duplicate retained scalar source"
      )
      val conflicting = initializer(captured): input =>
        input.copy(scalarSources = input.scalarSources.map: leaf =>
          changeFill(leaf)(scalar => scalar.copy(rendered = scalar.rendered.replace("1.0", "9.0"))))
      reject(conflicting, "conflicting retained source identity")
      val shapedScalar = initializer(captured): input =>
        input.copy(scalarSources = input.scalarSources.map: leaf =>
          changeFill(leaf)(scalar =>
            scalar.copy(valueType = scalar.valueType.copy(dimensions = Vector("1")))
          ))
      reject(shapedScalar, "missing its retained source")

    test("complete condition values cannot disagree with flattened control fields"):
      val captured = snapshot
      def mapBlock(
          input: AnalogControlFlowRuntime.Block,
          form: Int
      ): AnalogControlFlowRuntime.Block =
        input.copy(statements = input.statements.map:
          case event: Statement.EventControl => event.copy(body = mapBlock(event.body, form))
          case branch: Statement.IfThenElse => branch.copy(branches = branch.branches.map: arm =>
              val condition = arm.condition
              assert(condition.sourceValue.nonEmpty)
              arm.copy(condition = form match
                case 0 => condition.copy(sourceValue = None)
                case 1 => condition.copy(rendered = "true")
                case _ => condition.copy(sourceValue =
                    condition.sourceValue.map(_.copy(scalarSources = Vector.empty))
                  )))
          case other => other)
      for form <- 0 to 2 do
        val program = captured.analogProcedural.head
        val forged = captured.copy(analogProcedural =
          Vector(program.copy(controlFlow =
            program.controlFlow.map(control => control.copy(root = mapBlock(control.root, form)))
          ))
        )
        reject(
          forged,
          if form == 0 then "missing its retained source"
          else if form == 1 then "disagree" else "missing a retained source"
        )

    test("exact scalar leaf substitution preserves prefix identities and fill multiplicity"):
      val text = "analog_add(Module.leaf,Module.leaf_extra)"
      assert(replaceScalarLeaves(text, Vector("Module.leaf" -> "analog_add(1.0,2.0)")) ==
        "analog_add(analog_add(1.0,2.0),Module.leaf_extra)")
      assert(scalarLeafOccurrences(text, Vector("Module.leaf")) == Vector("Module.leaf"))
      val captured = snapshot
      val source = captured.analogProcedural.head.variables.flatMap(
        _.initializer
      ).find(_.scalarSources.nonEmpty).get
      val leaf = source.scalarSources.head
      val repeated = source.copy(rendered = s"analog_add(${leaf.rendered},${leaf.rendered})")
      val derived = deriveUniformScalar(repeated)
      assert(derived.fillOccurrences.size == 2, derived.fillOccurrences.distinct.size == 1)

    test("shared retained value traversal preserves identity and bounds authored depth"):
      val captured = snapshot
      val original = captured.analogProcedural.head.variables.flatMap(_.initializer)
        .find(_.scalarSources.nonEmpty).get.scalarSources.head
      val prototype = original.source.get.asInstanceOf[ShapedSource.View].projection
        .asInstanceOf[KernelShapeIndexSnapshot]
      val root = original.allReads.head
      val scalarType = original.valueType
      var diamond = Value(root.identity, scalarType, Vector(root))
      for index <- 0 until 40 do
        val fill = Value(
          s"Module.fill_$index",
          scalarType.copy(dimensions = Vector("2")),
          source = Some(ShapedSource.Fill(diamond))
        )
        def selected(lane: Int): Value =
          val path = s"Module.view_${index}_$lane"
          Value(
            path,
            scalarType,
            source = Some(ShapedSource.View(
              fill,
              prototype.copy(path = path, input = fill.rendered, indices = Vector(lane.toString))
            ))
          )
        val left = selected(0)
        val right = selected(1)
        diamond = Value(
          s"analog_add(${left.rendered},${right.rendered})",
          scalarType,
          scalarSources = Vector(left, right)
        )
      assert(diamond.allReads == Vector(root))
      val live = deriveLiveScalars(Vector(diamond))
      assert(live.fills.size == 40, live.requiresSharing)
      assert(live.fills.forall(_.body.rendered.length < 200))
      val resource = live.eventResource("Module.event")
      assert(resource.wrappers == EventConsumerWrappers + 1)
      val rejected = scala.util.Try(requireEventResource(resource)).failed.get
      assert(rejected.getMessage.contains("before expansion"))
      val remapped = diamond.remap(identity, identity)
      val leftFill = remapped.scalarSources(0).source.get.asInstanceOf[ShapedSource.View].value
      val rightFill = remapped.scalarSources(1).source.get.asInstanceOf[ShapedSource.View].value
      assert(leftFill eq rightFill)
      assert(remapped.allReads == Vector(root))
      var tooDeep = Value("1.0", scalarType)
      for index <- 0 until 513 do
        tooDeep = Value(
          s"Module.deep_$index",
          scalarType.copy(dimensions = Vector("1")),
          source = Some(ShapedSource.Fill(tooDeep))
        )
      for action <-
          Vector[() => Any](() => tooDeep.allReads, () => tooDeep.remap(identity, identity))
      do
        val error = scala.util.Try(action()).failed.get
        assert(error.getMessage.contains("dependency depth"))

    test("live event resource boundaries and module totals saturate before allocation"):
      requireEventResource(EventResource(EventConsumerWrappers, EventConsumerBytes))
      requireEventResource(EventResource(EventModuleWrappers, EventModuleBytes), module = true)
      for resource <- Vector(
          EventResource(EventConsumerWrappers + 1L, 0L),
          EventResource(0L, EventConsumerBytes + 1L)
        )
      do assert(scala.util.Try(requireEventResource(resource)).isFailure)
      for resource <- Vector(
          EventResource(EventModuleWrappers + 1L, 0L),
          EventResource(0L, EventModuleBytes + 1L)
        )
      do assert(scala.util.Try(requireEventResource(resource, module = true)).isFailure)
      val maximum = EventResource(Long.MaxValue, Long.MaxValue) + EventResource(1L, 1L)
      assert(maximum == EventResource(EventModuleWrappers + 1L, EventModuleBytes + 1L))
