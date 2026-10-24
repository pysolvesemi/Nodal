package nodal.internal.testkit
import nodal.*
import nodal.internal.bridge.*
import nodal_external.*
private object Increment58SelectedInterfaceChecks:
  def verify(): Int =
    var count = 0
    def accepted(name: String, body: => Module): ConstructionSnapshot =
      val snapshot = ConstructionKernel.inspect(body)
      val text = ScalaToMlirBridge.fromSnapshot(snapshot).text
      if name == "connect" then
        val paths = text.linesIterator.filter(_.contains("\"nodal.member_access\"")).map(line =>
          "semantic_path = \"([^\"]+)\"".r.findFirstMatchIn(line).get.group(1)
        ).toVector
        require(paths.size == paths.distinct.size && paths.size >= 4)
      if name == "guarded" then
        val literals = text.linesIterator.filter(line =>
          line.contains("\"nodal.const_literal\"") && line.contains("use = \"loop_bound\"")
        )
        require(literals.forall(_.contains("semantic_path =")))
        require(text.contains(".ordinal0"))
      count += 1
      println("PASS " + name)
      snapshot
    def rejected(name: String, snapshot: ConstructionSnapshot, reason: String): Unit =
      val failure = scala.util.Try(ScalaToMlirBridge.fromSnapshot(snapshot)).failed.get
      require(
        failure.isInstanceOf[BridgeException] && failure.getMessage.contains(reason),
        failure.getMessage
      )
      count += 1
      println("PASS " + name + " " + failure.getMessage)
    def boundary(
        snapshot: ConstructionSnapshot,
        path: String,
        change: KernelInterfaceBoundarySnapshot => KernelInterfaceBoundarySnapshot
    ): ConstructionSnapshot =
      snapshot.copy(
        modules = snapshot.modules.map(module =>
          module.copy(declarations =
            module.declarations.map(value =>
              if value.path == path then
                value.copy(interfaceBoundary = value.interfaceBoundary.map(change))
              else value
            )
          )
        ),
        interfaceContracts = snapshot.interfaceContracts.map(value =>
          if value.path == path then value.copy(boundary = value.boundary.map(change)) else value
        )
      )
    val direct = accepted("direct", new Increment58DirectSelectedInterface)
    val child = accepted("child", new Increment58ChildSelectedInterface)
    val width = accepted("width", new Increment58WidthSelectedInterface)
    val _ = accepted("connect", new Increment58ConnectedSelectedInterface)
    val guarded = accepted("guarded", new Increment58GuardedSelectedInterface)
    val _ = accepted("array", new Increment58ArraySelectedInterface)
    val directView = direct.interfaceContracts.find(_.boundary.nonEmpty).get
    val childView = child.interfaceContracts.find(_.boundary.exists(_.selection.nonEmpty)).get
    rejected(
      "direct cannot acquire instance",
      boundary(direct, directView.path, _.copy(instance = Some("foreign"))),
      "exact immediate child"
    )
    rejected(
      "selected child cannot lose instance",
      boundary(child, childView.path, _.copy(instance = None)),
      "exact original exported endpoint or child"
    )
    rejected(
      "selection cannot become raw endpoint",
      boundary(direct, directView.path, _.copy(selection = None)),
      "direct Interface boundary requires"
    )
    val drive = direct.digitalConnectivity.find(_.kind == "interface-drive").get
    def effect(
        snapshot: ConstructionSnapshot,
        path: String,
        change: KernelDigitalConnectivitySnapshot => KernelDigitalConnectivitySnapshot
    ) =
      snapshot.copy(digitalConnectivity =
        snapshot.digitalConnectivity.map(value =>
          if value.path == path then change(value) else value
        )
      )
    rejected(
      "regional effect needs source order",
      effect(direct, drive.path, _.copy(sourceOrdinal = None)),
      "actual regional or selected source order"
    )
    rejected(
      "regional effect cannot be module hoisted",
      effect(direct, drive.path, _.copy(region = None)),
      "actual regional or selected source order"
    )
    val observation = direct.digitalConnectivity.find(_.kind == "interface-observe").get
    rejected(
      "selected module effect needs source order",
      effect(direct, observation.path, _.copy(sourceOrdinal = None)),
      "actual regional or selected source order"
    )
    val input =
      direct.modules.find(_.path == direct.root).get.declarations.find(_.name == "input").get.path
    rejected(
      "regional value needs exact capture",
      effect(direct, drive.path, _.copy(value = Some(input))),
      "exact lexical scope"
    )
    rejected(
      "selected write cannot be forged",
      effect(direct, observation.path, _.copy(kind = "interface-drive", value = Some(input))),
      "selection-qualified driver proof"
    )
    val guardedView = guarded.interfaceContracts.find(_.boundary.exists(_.selection.nonEmpty)).get
    val actualRegion = guardedView.boundary.get.region.get
    require(guarded.structuralIfs.exists(_.trueArm.path == actualRegion))
    require(!guarded.generatedRegions.exists(_.declarations.contains(guardedView.path)))
    rejected(
      "guarded selection cannot lose family capture",
      boundary(
        guarded,
        guardedView.path,
        value => value.copy(selection = value.selection.map(_.copy(familyInput = None)))
      ),
      "selected family root escaped handle-definition scope"
    )
    rejected(
      "selected ordinal requires actual family extent",
      boundary(
        direct,
        directView.path,
        value => value.copy(selection = value.selection.map(_.copy(ordinal = "3")))
      ),
      "ordinal"
    )
    rejected(
      "selected Module effect cannot reorder before family",
      effect(
        direct,
        observation.path,
        _.copy(sourceOrdinal = Some(0))
      ),
      "source ordinals"
    )
    val second = width.interfaceContracts.find(value =>
      value.boundary.exists(_.selection.exists(_.ordinal == "1"))
    ).get
    rejected(
      "selected child width must match exact substituted formal",
      width.copy(interfaceContracts =
        width.interfaceContracts.map(value =>
          if value.path == second.path then
            value.copy(actualMemberTypes =
              value.actualMemberTypes.map((name, _) => name -> "UInt(1)")
            )
          else value
        )
      ),
      "exact finalized original type"
    )
    val forgedInventory = direct.copy(generatedRegions =
      direct.generatedRegions.map(value =>
        value.copy(declarations = value.declarations :+ directView.path)
      )
    )
    rejected(
      "selected projection is not a generated physical declaration",
      forgedInventory,
      "exact generated owner and induction"
    )
    val failure =
      scala.util.Try(ConstructionKernel.inspect(new Increment58SelectedInterfaceWrite)).failed.get
    require(
      failure.isInstanceOf[ConstructionException] &&
        failure.getMessage.contains("NODAL-AREA-058-CAPABILITY"),
      failure.getMessage
    )
    count += 1
    println("PASS source selected write refusal")
    println(s"CASES $count")
    require(count == 20)
    count

object Increment58SelectedSymbolTests extends utest.TestSuite:
  import utest.*
  val tests = Tests:
    test("selected fixed child and two lane state cones retain exact authored occurrence"):
      Vector[() => Module](
        () => new Increment58SelectedInstanceSource,
        () => new Increment58TwoSelectedSource,
        () => new Increment58TwoSelectedSource(true)
      ).foreach: source =>
        val snapshot = ConstructionKernel.inspect(source())
        assert(snapshot.generatedRegions.size == 1)
        val _ = ScalaToMlirBridge.fromSnapshot(snapshot)
    test("selected child width substitutes original coordinate including nonunit domain"):
      Vector((0, 1, 2), (2, 2, 5)).foreach: (lower, step, width) =>
        val snapshot = ConstructionKernel.inspect(new Increment58WidthSelectedSource(lower, step))
        val text = ScalaToMlirBridge.fromSnapshot(snapshot).text
        val selected = text.linesIterator.filter(_.contains("\"nodal.instance_port\"(%")).toVector
        assert(selected.size == 1, selected.head.contains(s"!nodal.uint<$width>"))
    test("a valid selected width cannot hide an invalid original all-lane formal range"):
      val failure = scala.util.Try(
        ConstructionKernel.inspect(new Increment58SelectedFullFormalRangeSource)
      ).failed.get
      assert(
        failure.isInstanceOf[IllegalArgumentException],
        failure.getMessage.contains("NODAL-SHAPE-043-007"),
        failure.getMessage.contains("actual Integer envelope escapes formal")
      )
    test("wrong selected result width remains a causal final type refusal"):
      val failure =
        scala.util.Try(ConstructionKernel.inspect(new Increment58WidthSelectedSource(wrong =
          true
        ))).failed.get
      assert(failure.isInstanceOf[ConstructionException], failure.getMessage.contains("type"))
    test("selected Instance writes do not gain template mutation authority"):
      val failure =
        scala.util.Try(ConstructionKernel.inspect(new Increment58TwoSelectedSource(write =
          true
        ))).failed.get
      assert(
        failure.isInstanceOf[ConstructionException],
        failure.getMessage.contains("NODAL-HIERARCHY-058-001")
      )
    test("selected Interface complete ABI ordering ownership and capability controls"):
      assert(Increment58SelectedInterfaceChecks.verify() == 20)
