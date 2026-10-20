package nodal.internal.bridge

import nodal.*
import scala.collection.mutable

/** Exact fixed scalar control identity shared by sequential and reset ingress. */
private[nodal] object PhysicalControlMlir:
  final case class Identity(root: String, scalarOffset: Long)
  final case class Resolved(
      identity: Identity,
      declaration: KernelDeclarationSnapshot,
      kind: String
  )

  final class Resolver(snapshot: ConstructionSnapshot):
    private val declarations = snapshot.modules.flatMap(m =>
      m.declarations.map(d => d.path -> (m.path, d))
    )
    private val roots = declarations.toMap
    private val records: Vector[KernelShapeIndexSnapshot | KernelShapeViewSnapshot] =
      snapshot.shapeIndices ++ snapshot.shapeViews
    private val shapes: Vector[(String, KernelShapeIndexSnapshot | KernelShapeViewSnapshot)] =
      records.map:
        case value: KernelShapeIndexSnapshot => value.path -> value
        case value: KernelShapeViewSnapshot => value.path -> value
    private val byPath = shapes.toMap
    private val uniqueIdentities = roots.size == declarations.size && byPath.size == records.size &&
      !roots.keySet.exists(byPath.contains)
    private val cache = mutable.Map.empty[(String, String, String), Resolved]

    def resolve(path: String, owner: String, kind: String): Resolved =
      cache.getOrElseUpdate((path, owner, kind), resolveUncached(path, owner, kind))

    private def resolveUncached(path: String, owner: String, kind: String): Resolved =
      def fail(message: String): Nothing = throw new BridgeException(
        BridgeDiagnostic("NODAL-BRIDGE-056-SEQUENTIAL", message, Some(path))
      )
      if !uniqueIdentities || !Set("Clock", "Reset")(kind)
      then fail("physical controls require unique typed root and projection identities")
      def literal(token: String): Long = token.toLongOption.filter(value =>
        value.toString == token && value >= 0
      ).getOrElse(fail("physical control coordinates require canonical nonnegative literals"))
      def dimensions(tokens: Vector[String]): Vector[Long] =
        val result = tokens.map(literal)
        if result.isEmpty || result.size > 128 || result.exists(_ <= 0) ||
          !result.map(BigInt(_)).product.isValidLong
        then fail("physical control shape requires bounded positive literal dimensions")
        result
      val chain = mutable.ArrayBuffer.empty[StructuralShapeCoordinates.Projection]
      def collect(current: String, seen: Set[String]): (KernelDeclarationSnapshot, Vector[String]) =
        if seen(current) || seen.size > 128 then
          fail("physical control projection is cyclic or exceeds depth budget")
        roots.get(current) match
          case Some((rootOwner, declaration)) =>
            if rootOwner != owner || declaration.kind != "input" ||
              declaration.generatedScope.nonEmpty
            then fail("physical control root must be an exact owned input boundary")
            val text = declaration.dataType.getOrElse(fail("physical control root lacks type"))
            if text == kind then declaration -> Vector.empty
            else
              val prefix = s"Vec($kind;"
              if !text.startsWith(prefix) || !text.endsWith(")") then
                fail("physical control requires an exact typed input boundary")
              val shape = text.drop(prefix.length).dropRight(1).split("x", -1).toVector
              dimensions(shape)
              declaration -> shape
          case None =>
            val record =
              byPath.getOrElse(
                current,
                fail("physical control has no actual owned declaration or projection")
              )
            val (recordOwner, input, scoped) = record match
              case value: KernelShapeIndexSnapshot =>
                (
                  value.owner,
                  value.input,
                  value.generatedScope.nonEmpty || value.region.nonEmpty ||
                    value.sourceUses.nonEmpty
                )
              case value: KernelShapeViewSnapshot =>
                (value.owner, value.input, value.generatedScope.nonEmpty || value.region.nonEmpty)
            if recordOwner != owner || scoped then
              fail("physical control projection is foreign or regional")
            val (root, inputShape) = collect(input, seen + current)
            val inputDimensions = dimensions(inputShape)
            val projection = StructuralShapeCoordinates.projection(record)
            val output = StructuralShapeCoordinates.dimensions(projection)
            projection match
              case StructuralShapeCoordinates.Projection.Index(indices, result) =>
                if indices.isEmpty || indices.size > inputDimensions.size ||
                  result != inputShape.drop(indices.size) ||
                  indices.map(literal).zip(inputDimensions).exists((index, extent) =>
                    index >= extent
                  )
                then fail("physical control index has invalid static bounds or result shape")
              case StructuralShapeCoordinates.Projection.View(result, slice) =>
                val outputDimensions = dimensions(result)
                slice match
                  case None =>
                    if inputDimensions.map(BigInt(_)).product !=
                        outputDimensions.map(BigInt(_)).product
                    then
                      fail("physical control view changes scalar cardinality")
                  case Some(selection) =>
                    if selection.axis < 0 || selection.axis >= inputDimensions.size then
                      fail("physical control slice axis is invalid")
                    val lower = literal(selection.lower)
                    val upper = literal(selection.upperExclusive)
                    if lower >= upper || upper > inputDimensions(selection.axis) ||
                      outputDimensions != inputDimensions.updated(selection.axis, upper - lower)
                    then fail("physical control slice has invalid static bounds or result shape")
            chain += projection
            root -> output
      val (root, output) = collect(path, Set.empty)
      if output.nonEmpty then fail("physical control must select a scalar capability")
      val rootShape = if root.dataType.contains(kind) then Vector.empty
      else
        root.dataType.get.drop(s"Vec($kind;".length).dropRight(1).split("x", -1).toVector
      try
        val region = StructuralShapeCoordinates.scalarRegion(rootShape, chain.toVector)
        region match
          case StructuralShapeCoordinates.Region(
                StructuralShapeCoordinates.Coordinate.Literal(1),
                StructuralShapeCoordinates.Coordinate.Literal(offset)
              ) if offset >= 0 => Resolved(Identity(root.path, offset), root, kind)
          case _ => fail("physical control does not resolve to one fixed scalar coordinate")
      catch
        case error: StructuralShapeCoordinates.InvalidCoordinate => fail(error.getMessage)
