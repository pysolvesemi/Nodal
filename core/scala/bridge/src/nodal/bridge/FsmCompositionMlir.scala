package nodal.internal.bridge

import nodal.*
import scala.collection.mutable

/** Independent closed snapshot admission; execution and local graph policy remain shared owners. */
private[nodal] object FsmCompositionMlir:
  final case class Join(parent: KernelFlatFsmSnapshot, value: KernelFsmJoinSnapshot)
  final case class Activation(parent: KernelFlatFsmSnapshot, value: KernelFsmChildGroup)

  final class Plan(snapshot: ConstructionSnapshot, fail: (String, String) => Nothing):
    // Bound every nested inventory before producing maps or copied edge lists.
    if snapshot.flatFsms.size > 4096 then
      fail("composition exceeds bounded machine inventory", snapshot.root)
    private var preflight = 0L
    snapshot.flatFsms.foreach: machine =>
      machine.composition.foreach: composition =>
        preflight += machine.states.size.toLong + machine.transitions.size + machine.actions.size
        if preflight > 65536 || composition.groups.size > machine.states.size ||
          composition.joins.size > machine.transitions.size ||
          composition.fragments.size > 4096
        then fail("composition exceeds bounded graph inventory", machine.path)
        composition.groups.foreach: group =>
          preflight += group.children.size
          if preflight > 65536 then
            fail("composition exceeds bounded child inventory", machine.path)
        composition.joins.foreach: join =>
          preflight += join.children.size
          if join.children.isEmpty || join.children.size > 4095 || preflight > 65536 then
            fail("composition exceeds bounded join operand inventory", machine.path)
        var metadataWork = 0L
        composition.fragments.foreach: (_, cases) =>
          metadataWork += cases.size
          if metadataWork > 4096 then
            fail("composition fragment inventory exceeds local states", machine.path)
    private val machines = snapshot.flatFsms.map(value => value.path -> value).toMap
    private val expressions = snapshot.digitalExpressions.map(value => value.path -> value).toMap
    private val composed = snapshot.flatFsms.filter(_.composition.nonEmpty)
    private def check(condition: Boolean, message: String, path: String): Unit =
      if !condition then fail(message, path)
    private def machine(path: String): KernelFlatFsmSnapshot =
      machines.getOrElse(path, fail("composition references an absent actual machine", path))
    private def status(path: String): String = machine(path).control
      .getOrElse(fail("composition requires controlled child machines", path)).storage

    val joins: Map[String, Join] = composed.flatMap(parent =>
      parent.composition.get.joins.map(value =>
        value.result -> Join(parent, value)
      )
    ).toMap
    val activations: Map[String, Activation] = composed.flatMap(parent =>
      parent.composition.get.groups.flatMap(value =>
        Vector(value.restart, value.cancel, value.enable).map(_ -> Activation(parent, value))
      )
    ).toMap

    def activationInputs(parent: KernelFlatFsmSnapshot): Vector[String] =
      val control = parent.control.get
      Vector(
        parent.storage,
        control.storage,
        parent.nextLocal,
        parent.transitionTaken,
        control.entered,
        control.exited,
        parent.composition.get.establishment,
        control.enable
      )

    def identityInputs(parent: KernelFlatFsmSnapshot, children: Vector[String]): Vector[String] =
      Vector(parent.storage, parent.control.get.storage) ++ childIdentityInputs(children)

    def childIdentityInputs(children: Vector[String]): Vector[String] =
      children.flatMap(path => Vector(machine(path).storage, status(path)))

    def validate(): Unit =
      check(
        joins.size == composed.map(_.composition.get.joins.size).sum &&
          activations.size == 3 * composed.map(_.composition.get.groups.size).sum &&
          joins.keySet.intersect(activations.keySet).isEmpty,
        "composition repeats an actual join or activation result",
        snapshot.root
      )
      val owned = mutable.HashSet.empty[String]
      composed.foreach: parent =>
        val composition = parent.composition.get
        check(
          parent.control.nonEmpty && composition.establishment == parent.path + ".establishment" &&
            composition.parent.nonEmpty == composition.ownerCase.nonEmpty &&
            composition.parent.nonEmpty == composition.slot.nonEmpty &&
            (composition.parent.nonEmpty || composition.groups.nonEmpty),
          "composition requires canonical controlled topology and establishment",
          parent.path
        )
        check(
          composition.definitionName.nonEmpty && composition.definitionName.length <= 4096 &&
            composition.definitionHash.matches("[0-9a-f]{64}") &&
            composition.fragments.map(_._1).distinct.size == composition.fragments.size &&
            composition.fragments.forall((name, _) =>
              name.nonEmpty && name.length <= 4096 && name == name.trim
            ) &&
            composition.fragments.flatMap(_._2) == parent.states.map(_.caseId),
          "composition requires bounded immutable definition/fragment provenance",
          parent.path
        )
        val allSlots = composition.groups.flatMap(_.children).map(path =>
          machine(path).composition.flatMap(_.slot)
        )
        check(
          allSlots.distinct.size == allSlots.size,
          "child slots repeat across owner states",
          parent.path
        )
        check(
          composition.groups.map(_.ownerCase).distinct.size == composition.groups.size,
          "composition repeats an owner state",
          parent.path
        )
        composition.groups.foreach: group =>
          check(
            parent.states.exists(state => state.caseId == group.ownerCase && !state.terminal) &&
              group.children.nonEmpty && group.children.size <= 4095 &&
              group.children.distinct.size == group.children.size,
            "child inventory requires one actual nonterminal owner state and distinct children",
            parent.path
          )
          group.children.foreach: childPath =>
            val child = machine(childPath)
            check(
              owned.add(childPath) && child.owner == parent.owner &&
                child.domain == parent.domain &&
                child.composition.exists(value =>
                  value.parent.contains(parent.path) &&
                    value.ownerCase.contains(group.ownerCase) && value.root == composition.root
                ) &&
                child.control.exists(value =>
                  Vector(value.restart, value.cancel, value.enable) ==
                    Vector(group.restart, group.cancel, group.enable)
                ),
              "child requires one exact parent, domain, owner state and activation control",
              childPath
            )
          val slotNames = group.children.map(path => machine(path).composition.get.slot.get)
          check(
            slotNames.forall(name => name.nonEmpty && name.length <= 4096 && name == name.trim) &&
              slotNames.distinct.size == slotNames.size,
            "child slots require unique bounded names",
            parent.path
          )
          val base = parent.path + ".children." +
            parent.localCodes.indexWhere(_.caseId == group.ownerCase)
          check(
            Vector(group.restart, group.cancel, group.enable) ==
              Vector("restart", "cancel", "enable").map(base + "." + _),
            "activation results require distinct canonical roles",
            parent.path
          )
          Vector(
            group.restart -> FlatFsmRuntime.RestartOperation,
            group.cancel -> FlatFsmRuntime.CancelOperation,
            group.enable -> FlatFsmRuntime.EnableOperation
          ).foreach: (path, operation) =>
            expression(path, parent.owner, operation, activationInputs(parent))
        composition.joins.foreach: join =>
          val group = composition.groups.find(_.ownerCase == join.ownerCase)
            .getOrElse(fail("completion edge has no actual child owner state", join.result))
          check(
            Set("all", "any")(join.policy) &&
              (if join.selection == "all_children" then join.children == group.children
               else
                 join.selection == "one_child" && join.policy == "all" && join.children.size == 1 &&
                 group.children.contains(join.children.head)) &&
              parent.transitions.exists(edge =>
                edge.sourceCase == join.ownerCase && edge.guard == join.result
              ),
            "completion join must feed its exact owner transition and actual child inventory",
            join.result
          )
          expression(
            join.result,
            parent.owner,
            FlatFsmRuntime.JoinOperation,
            join.children.map(status)
          )
      check(
        owned == composed.filter(_.composition.get.parent.nonEmpty).map(_.path).toSet,
        "composition contains orphan child ownership",
        snapshot.root
      )
      val pending = mutable.ArrayDeque.from(composed.filter(_.composition.get.parent.isEmpty).map(
        value => (value.path, value.path, 1)
      ))
      val ordered = mutable.ArrayBuffer.empty[String]
      while pending.nonEmpty do
        val (path, root, depth) = pending.removeHead()
        val value = machine(path)
        check(
          depth <= 128 && value.composition.get.root == root,
          "composition root or depth is invalid",
          path
        )
        ordered += path
        value.composition.get.groups.foreach(_.children.foreach(child =>
          pending.append((child, root, depth + 1))
        ))
      check(ordered.size == composed.size, "composition topology contains a cycle", snapshot.root)
      val costs = mutable.HashMap.empty[String, (Int, Int, Int)]
      ordered.reverseIterator.foreach: path =>
        val value = machine(path)
        val facts = value.composition.get
        val children = facts.groups.flatMap(_.children)
        val occurrences = 1 + children.map(costs(_)._1).sum
        val records = value.states.size + value.transitions.size + value.actions.size +
          children.size + facts.joins.map(_.children.size).sum + children.map(costs(_)._2).sum
        val depth = 1 + children.map(costs(_)._3).maxOption.getOrElse(0)
        check(
          occurrences <= 4096 && records <= 65536 && depth <= 128 &&
            facts.occurrences == occurrences && facts.records == records && facts.depth == depth &&
            facts.completionPossible == value.states.exists(_.terminal),
          "composition report facts must match actual separate state and bounded subtree",
          path
        )
        costs.update(path, (occurrences, records, depth))
        facts.joins.foreach: join =>
          val possibilities = join.children.map(child => machine(child).states.exists(_.terminal))
          check(
            if join.policy == "all" then possibilities.forall(identity)
            else possibilities.exists(identity),
            "completion join has no structural completion possibility",
            join.result
          )
      expressions.valuesIterator.filter(value =>
        Set(
          FlatFsmRuntime.JoinOperation,
          FlatFsmRuntime.RestartOperation,
          FlatFsmRuntime.CancelOperation,
          FlatFsmRuntime.EnableOperation
        )(value.operation)
      ).foreach: value =>
        check(
          joins.contains(value.path) || activations.contains(value.path),
          "composition expression has no actual topology owner",
          value.path
        )

    private def expression(
        path: String,
        owner: String,
        operation: String,
        inputs: Vector[String]
    ): Unit =
      check(
        expressions.get(path).exists(value =>
          value.owner == owner && value.region.isEmpty &&
            value.operation == operation && value.dataType == "Bool" && value.operands == inputs &&
            value.literal.isEmpty && value.enumIdentity.isEmpty && value.caseIds.isEmpty &&
            value.structure.isEmpty &&
            value.materializationIntent.isEmpty
        ),
        "composition expression differs from its canonical semantic inputs",
        path
      )
