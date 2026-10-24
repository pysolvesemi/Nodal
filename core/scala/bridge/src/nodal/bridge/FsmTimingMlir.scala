package nodal.internal.bridge

import nodal.*
import scala.collection.mutable

/** Closed snapshot admission for timed edges and read-only actual Stream observations. */
private[nodal] object FsmTimingMlir:
  final case class Query(
      machine: KernelFlatFsmSnapshot,
      inputs: Vector[String],
      cycles: Option[Int]
  )
  final class Plan(snapshot: ConstructionSnapshot, fail: (String, String) => Nothing):
    private def check(condition: Boolean, message: String, path: String): Unit =
      if !condition then fail(message, path)
    private var records = 0L
    private var bits = 0L
    snapshot.flatFsms.foreach: machine =>
      records += machine.timerEdges.size.toLong + machine.transferEdges.size
      bits += machine.timerStorage.size * FsmTimingRuntime.Width
      check(
        records <= 65536 && bits <= FsmTimingRuntime.MaximumStateBits &&
          machine.timerEdges.size + machine.transferEdges.size <= machine.transitions.size,
        "timing edge inventory or actual private timer bits exceed their finite budget",
        machine.path
      )
    private val expressions = snapshot.digitalExpressions.map(value => value.path -> value).toMap
    private val declarations = snapshot.modules.flatMap(module =>
      module.declarations.map(value => value.path -> (module.path, value))
    ).toMap
    private val owned = mutable.LinkedHashMap.empty[String, Query]
    snapshot.flatFsms.foreach: machine =>
      check(
        machine.timerStorage.nonEmpty == machine.timerEdges.nonEmpty,
        "timer storage requires actual timed edges and vice versa",
        machine.path
      )
      val edges = machine.transitions.map(value => value.sourceOrdinal -> value).toMap
      val ordinals = machine.timerEdges.map(_.ordinal) ++ machine.transferEdges.map(_.ordinal)
      check(
        ordinals.distinct.size == ordinals.size,
        "timing tables repeat an actual transition ordinal",
        machine.path
      )
      def add(ordinal: Int, guard: String, query: Query): Unit =
        check(
          edges.get(ordinal).exists(_.guard == guard),
          "timing edge must identify its exact transition guard",
          machine.path
        )
        check(
          owned.get(guard).forall(_ == query),
          "timing predicate cannot belong to a foreign machine or different edge policy",
          guard
        )
        owned.update(guard, query)
      machine.timerEdges.foreach: edge =>
        check(edge.cycles > 0, "timer cycles must be a positive Int", machine.path)
        add(
          edge.ordinal,
          edge.guard,
          Query(machine, machine.timerStorage.toVector, Some(edge.cycles))
        )
      machine.transferEdges.foreach: edge =>
        check(
          declarations.get(edge.ready).exists((owner, value) =>
            owner == machine.owner && value.kind == "wire" && value.dataType.contains("Bool") &&
              value.attributes.contains("stream_payload" -> edge.payload) &&
              value.attributes.contains("stream_valid" -> edge.valid) &&
              value.attributes.exists(_._1 == "stream_ready_default")
          ),
          "transfer wait requires the exact existing Stream payload, valid and ready carrier",
          edge.guard
        )
        add(edge.ordinal, edge.guard, Query(machine, Vector(edge.valid, edge.ready), None))
    val queries: Map[String, Query] = owned.toMap

    def validate(): Unit =
      queries.foreach: (path, query) =>
        val opcode = if query.cycles.nonEmpty then FsmTimingRuntime.ElapsedOperation
        else FsmTimingRuntime.TransferOperation
        check(
          expressions.get(path).exists(value =>
            value.owner == query.machine.owner && value.region.isEmpty &&
              value.dataType == "Bool" &&
              value.operation == opcode && value.operands == query.inputs &&
              value.literal.isEmpty &&
              value.enumIdentity.isEmpty && value.caseIds.isEmpty && value.structure.isEmpty &&
              value.materializationIntent.isEmpty
          ),
          "timing predicate differs from its exact canonical inputs",
          path
        )
      expressions.valuesIterator.foreach: value =>
        if Set(
            FsmTimingRuntime.ElapsedOperation,
            FsmTimingRuntime.TransferOperation
          )(value.operation)
        then
          check(
            queries.contains(value.path),
            "timing predicate has no actual transition owner",
            value.path
          )
        check(
          !value.operands.exists(queries.contains),
          "timing predicates may only feed their owned transition guards",
          value.path
        )
      snapshot.flatFsms.foreach: machine =>
        val ordinals =
          (machine.timerEdges.map(_.ordinal) ++ machine.transferEdges.map(_.ordinal)).toSet
        machine.transitions.foreach: edge =>
          queries.get(edge.guard).foreach(query =>
            check(
              query.machine.path == machine.path &&
                ordinals(edge.sourceOrdinal),
              "timing predicate has an unrecorded or foreign guard use",
              machine.path
            )
          )
        check(
          !machine.actions.exists(_.captures.exists(queries.contains)) &&
            !machine.control.exists(control =>
              Vector(control.restart, control.cancel, control.enable).exists(queries.contains)
            ),
          "timing predicates cannot escape through actions or lifecycle controls",
          machine.path
        )
      snapshot.digitalAssignments.foreach: value =>
        check(
          !queries.contains(value.value) && !queries.contains(value.target),
          "timing predicates cannot escape through assignment",
          value.path
        )
