package nodal.internal.testkit.fsmscale

import nodal.*
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

enum LeafMode derives HwEnum:
  case Main, Sub, Resume, Done

enum BranchMode derives HwEnum:
  case Work, Done

final class StackLeaf(capacity: Int):
  val owner = FsmDefinition[LeafMode](s"procedure-$capacity")
  val call = FsmGuard(owner, "call")
  val ret = FsmGuard(owner, "return")
  val graph = FsmDef.withCallStack(
    owner,
    LeafMode.Main,
    capacity,
    FsmStackPolicy.Trap(LeafMode.Done),
    FsmStackPolicy.Trap(LeafMode.Done),
    FsmStackPolicy.Trap(LeafMode.Done)
  )(FsmFragment("procedure")(
    FsmState(LeafMode.Main).call(call)(LeafMode.Sub, LeafMode.Resume),
    FsmState(LeafMode.Sub).returnOn(ret),
    FsmState(LeafMode.Resume).on(ret)(LeafMode.Done),
    FsmState(LeafMode.Done).terminal()
  ))
  def bindings(callInput: Expr[Bool], returnInput: Expr[Bool]): FsmBindings[LeafMode] =
    FsmBindings(Vector(call.bind(callInput), ret.bind(returnInput)))

final class ParallelBranch(val leaf: StackLeaf, count: Int):
  val owner = FsmDefinition[BranchMode](s"parallel-$count")
  val children = Vector.tabulate(count)(index => FsmChild(owner, s"leaf$index", leaf.graph))
  val graph = FsmDef(owner, BranchMode.Work)(FsmFragment("parallel")(
    FsmState(BranchMode.Work).children(children*).onAllComplete()(BranchMode.Done),
    FsmState(BranchMode.Done).terminal()
  ))
  def bindings(callInput: Expr[Bool], returnInput: Expr[Bool]): FsmBindings[BranchMode] =
    FsmBindings(
      Vector.empty[FsmGuardBinding[BranchMode]],
      Vector.empty[FsmActionBinding[BranchMode]],
      children.map(_.bind(
        leaf.bindings(callInput, returnInput),
        illegalState = IllegalStatePolicy.RecoverToInitial
      ))
    )

final class NestedBranches(val branch: ParallelBranch):
  val owner = FsmDefinition[BranchMode]("nested")
  val children = Vector.tabulate(2)(index => FsmChild(owner, s"branch$index", branch.graph))
  val graph = FsmDef(owner, BranchMode.Work)(FsmFragment("nested")(
    FsmState(BranchMode.Work).children(children*).onAllComplete()(BranchMode.Done),
    FsmState(BranchMode.Done).terminal()
  ))
  def bindings(callInput: Expr[Bool], returnInput: Expr[Bool]): FsmBindings[BranchMode] =
    FsmBindings(
      Vector.empty[FsmGuardBinding[BranchMode]],
      Vector.empty[FsmActionBinding[BranchMode]],
      children.map(_.bind(
        branch.bindings(callInput, returnInput),
        illegalState = IllegalStatePolicy.RecoverToInitial
      ))
    )

final class FsmScaleRoot(profile: String) extends Module:
  val clock = in(Clock)
  val reset = in(Reset)
  val restart = in(Bool)
  val cancel = in(Bool)
  val enable = in(Bool)
  val call = in(Bool)
  val ret = in(Bool)
  val status = out(EnumType[FsmStatus]())
  val domain = ClockDomain.from(
    clock,
    reset,
    ClockEdge.Rising,
    ResetPolicy.Sync,
    ResetPolarity.ActiveHigh,
    100.MHz,
    "root"
  )
  domain:
    val control = FsmControl(restart, cancel, enable)
    if profile == "single" then
      val leaf = new StackLeaf(2)
      leaf.graph.instantiateControlled(
        leaf.bindings(call, ret),
        control,
        illegalState = IllegalStatePolicy.RecoverToInitial
      ): run =>
        status := run.status.value
    else if profile == "parallel" then
      val branch = new ParallelBranch(new StackLeaf(8), 4)
      branch.graph.instantiateControlled(
        branch.bindings(call, ret),
        control,
        illegalState = IllegalStatePolicy.RecoverToInitial
      ): run =>
        status := run.status.value
    else
      assert(profile == "nested")
      val nested = new NestedBranches(new ParallelBranch(new StackLeaf(32), 2))
      nested.graph.instantiateControlled(
        nested.bindings(call, ret),
        control,
        illegalState = IllegalStatePolicy.RecoverToInitial
      ): run =>
        status := run.status.value
  assert(!ConstructionKernel.boundaryWorkActive)

object FsmScale:
  private def digest(bytes: Array[Byte]): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).map(b => f"${b & 255}%02x").mkString

  def main(args: Array[String]): Unit =
    assert(args.length == 1)
    val profile = args(0)
    val (expectedMachines, expectedLeaves, expectedCapacity, expectedDepth, expectedBits) =
      profile match
        case "single" => (1, 1, 2, 1, 6)
        case "parallel" => (5, 4, 8, 2, 80)
        case "nested" => (7, 4, 32, 3, 280)
        case _ => throw new IllegalArgumentException(profile)
    def capture(): (ConstructionSnapshot, Long) =
      val start = System.nanoTime()
      val snapshot = ConstructionKernel.inspect(new FsmScaleRoot(profile))
      snapshot -> (System.nanoTime() - start)
    val (first, firstNanos) = capture()
    val (second, secondNanos) = capture()
    assert(first == second)
    val bytes = first.toString.getBytes(StandardCharsets.UTF_8)
    assert(java.util.Arrays.equals(bytes, second.toString.getBytes(StandardCharsets.UTF_8)))
    assert(first.modules.size == 1 && first.modules.head.instances.isEmpty)
    val machines = first.flatFsms
    assert(machines.size == expectedMachines)
    assert(machines.map(_.path).distinct.size == expectedMachines)
    assert(machines.forall(_.owner == first.root))
    val domains = first.modules.head.domains.filter(_.name == "root")
    assert(domains.size == 1 && machines.forall(_.domain == domains.head.path))
    val byPath = machines.map(value => value.path -> value).toMap
    val leaves = machines.filter(_.stack.nonEmpty)
    assert(leaves.size == expectedLeaves && leaves.forall(_.localWidth == 2))
    if profile == "single" then
      assert(machines.head.composition.isEmpty && expectedDepth == 1)
    else
      assert(machines.forall(_.composition.nonEmpty))
      val root = machines.filter(_.composition.get.parent.isEmpty)
      assert(root.size == 1)
      assert(root.head.composition.get.occurrences == expectedMachines)
      assert(root.head.composition.get.depth == expectedDepth)
      assert(machines.forall(_.composition.get.root == root.head.path))
      assert(root.head.composition.get.slot.isEmpty && root.head.composition.get.ownerCase.isEmpty)
      val childPaths = machines.flatMap(_.composition.get.groups.flatMap(_.children))
      assert(childPaths.distinct.size == expectedMachines - 1)
      assert(childPaths.toSet ==
        machines.filter(_.composition.get.parent.nonEmpty).map(_.path).toSet)
      machines.foreach: machine =>
        val composition = machine.composition.get
        assert(composition.completionPossible)
        composition.parent.foreach: parent =>
          val actualParent = byPath(parent)
          assert(composition.slot.nonEmpty &&
            composition.ownerCase.contains(actualParent.initialCase))
          assert(actualParent.composition.get.groups.exists(group =>
            group.ownerCase == composition.ownerCase.get && group.children.contains(machine.path)
          ))
        val expectedSlots =
          if machine.stack.nonEmpty then Set.empty[String]
          else if profile == "parallel" then Set("leaf0", "leaf1", "leaf2", "leaf3")
          else if machine.path == root.head.path then Set("branch0", "branch1")
          else Set("leaf0", "leaf1")
        assert(composition.groups.size == (if expectedSlots.isEmpty then 0 else 1))
        composition.groups.foreach: group =>
          assert(group.ownerCase == machine.initialCase)
          assert(group.children.distinct.size == group.children.size)
          assert(group.children.size == expectedSlots.size)
          assert(group.children.map(child => byPath(child).composition.get.slot.get).toSet ==
            expectedSlots)
          assert(group.children.forall(child =>
            byPath(child).composition.get.parent.contains(machine.path)
          ))
      assert(leaves.map(_.composition.get.definitionHash).distinct.size == 1)
    val declarationList = first.modules.flatMap(_.declarations)
    assert(declarationList.map(_.path).distinct.size == declarationList.size)
    val declarations = declarationList.map(value => value.path -> value).toMap
    val storage = machines.flatMap(machine =>
      Vector(machine.storage) ++ machine.control.toVector.map(_.storage) ++
        machine.stack.toVector.flatMap(stack => stack.pointerStorage +: stack.frameStorage)
    )
    assert(storage.distinct.size == storage.size)
    storage.foreach: path =>
      assert(declarations.contains(path) && declarations(path).kind == "register")
      assert(declarations(path).domain.contains(machines.head.domain))
    var stackBits = 0
    var frameCount = 0
    leaves.foreach: machine =>
      val stack = machine.stack.get
      assert(stack.capacity == expectedCapacity)
      assert(stack.pointerWidth == BigInt(expectedCapacity).bitLength)
      assert(stack.frameStorage.size == expectedCapacity)
      assert(declarations(stack.pointerStorage).dataType.contains(s"UInt(${stack.pointerWidth})"))
      stack.frameStorage.foreach: path =>
        assert(declarations(path).dataType.contains(s"Bits(${machine.localWidth})"))
        assert(declarations(path).attributes.contains("initial" -> "none"))
        assert(!declarations(path).attributes.exists(_._1 == "initializer_policy"))
      frameCount += stack.frameStorage.size
      stackBits += stack.pointerWidth + stack.capacity * machine.localWidth
    assert(frameCount == expectedLeaves * expectedCapacity && stackBits == expectedBits)
    println(
      s"PASS profile=$profile machines=${machines.size} leaves=${leaves.size} depth=$expectedDepth frames=$frameCount stackBits=$stackBits uniqueStorage=${storage.size} declarations=${declarations.size} snapshotBytes=${bytes.length} sha256=${digest(bytes)} firstNanos=$firstNanos secondNanos=$secondNanos"
    )
