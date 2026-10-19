package nodal.internal.bridge

import nodal.*
import scala.collection.mutable

/** Independent snapshot admission. The retained type/static graphs are the input; the shared
  * Integer query and finite transfer owner supply arithmetic, never serialized proof claims.
  */
private[nodal] final class DigitalReductionMlir(
    snapshot: ConstructionSnapshot,
    valueType: String => String,
    vectorType: (String, String) => (String, Vector[String]),
    structType: (String, String) => (String, Vector[(String, String)])
):
  import DigitalReductionContract.*
  import StructuralParameterBinding.Query

  private enum Shape:
    case Scalar(kind: String, width: String)
    case VectorValue(element: Shape, dimensions: Vector[String])
    case Record(name: String, fields: Vector[(String, Shape)])
    case Invariant(text: String)

  private val scalar = raw"(Bits|UInt|SInt)\(([^)]+)\)".r
  private val collections = snapshot.digitalCollections.map(value => value.path -> value).toMap
  private val parameters = snapshot.parameterExpressions.map(value => value.path -> value).toMap
  private val parameterRegions = snapshot.parameterExpressions.groupBy(_.region)
  private val expressionRegions = snapshot.digitalExpressions.groupBy(_.region)
  private var work = 0L

  private def fail(message: String, path: String): Nothing =
    scala.util.Failure[Nothing](
      new BridgeException(BridgeDiagnostic("NODAL-BRIDGE-055-REDUCTION", message, Some(path)))
    ).get

  private def charge(amount: Long, path: String): Unit =
    work += amount
    if work > MaximumWork then
      fail("collection node-context analysis exceeds the 65536 work budget", path)

  private def parse(text: String, path: String): Shape =
    var nodes = 0
    def visit(value: String, depth: Int): Shape =
      nodes += 1
      charge(1L, path)
      if depth > 128 || nodes > MaximumWork || value.length > 1048576 then
        fail("reduction type exceeds the bounded schema budget", path)
      value match
        case scalar(kind, width) => Shape.Scalar(kind, width)
        case value if value.startsWith("Vec(") =>
          val (element, dimensions) = vectorType(value, path)
          Shape.VectorValue(visit(element, depth + 1), dimensions)
        case value if value.startsWith("Struct(") =>
          val (name, fields) = structType(value, path)
          Shape.Record(name, fields.map((name, field) => name -> visit(field, depth + 1)))
        case "Bool" | "Real" | "Integer" => Shape.Invariant(value)
        case value if value.startsWith("Enum(") => Shape.Invariant(value)
        case _ => fail("reduction requires a complete finite digital type", path)
    visit(text, 0)

  private def widths(shape: Shape): Vector[String] = shape match
    case Shape.Scalar(_, width) => Vector(width)
    case Shape.VectorValue(element, _) => widths(element)
    case Shape.Record(_, fields) => fields.flatMap(value => widths(value._2))
    case Shape.Invariant(_) => Vector.empty

  private def template(shape: Shape, formal: Iterator[String]): String = shape match
    case Shape.Scalar(kind, _) => s"$kind(${formal.next()})"
    case Shape.VectorValue(element, dimensions) =>
      s"Vec(${template(element, formal)};${dimensions.mkString("x")})"
    case Shape.Record(name, fields) =>
      s"Struct($name{${fields.map((name, field) => s"$name:${template(field, formal)}").mkString(",")}})"
    case Shape.Invariant(text) => text

  private def sameShape(a: Shape, b: Shape, owner: String, query: Query): Boolean = (a, b) match
    case (Shape.Scalar(ak, _), Shape.Scalar(bk, _)) => ak == bk
    case (Shape.VectorValue(ae, ad), Shape.VectorValue(be, bd)) =>
      ad.size == bd.size &&
      ad.zip(bd).forall((x, y) => query.resolve(owner, x) == query.resolve(owner, y)) &&
      sameShape(ae, be, owner, query)
    case (Shape.Record(an, af), Shape.Record(bn, bf)) =>
      an == bn && af.map(_._1) == bf.map(_._1) &&
      af.zip(bf).forall((x, y) => sameShape(x._2, y._2, owner, query))
    case (Shape.Invariant(at), Shape.Invariant(bt)) => at == bt
    case _ => false

  private def sameAcross(
      actual: Shape,
      expected: Shape,
      owner: String,
      child: Query,
      parent: Query,
      includeWidths: Boolean = true
  ): Boolean =
    def sameToken(actual: String, expected: String): Boolean =
      child.resolve(owner, actual) == child.importValue(
        owner,
        parent.exportValues(Vector(parent.resolve(owner, expected)))
      )
    (actual, expected) match
      case (Shape.Scalar(ak, aw), Shape.Scalar(ek, ew)) =>
        ak == ek && (!includeWidths || sameToken(aw, ew))
      case (Shape.VectorValue(ae, ad), Shape.VectorValue(ee, ed)) =>
        ad.size == ed.size && ad.zip(ed).forall(sameToken) &&
        sameAcross(ae, ee, owner, child, parent, includeWidths)
      case (Shape.Record(an, af), Shape.Record(en, ef)) =>
        an == en && af.map(_._1) == ef.map(_._1) &&
        af.zip(ef).forall((a, e) => sameAcross(a._2, e._2, owner, child, parent, includeWidths))
      case (Shape.Invariant(a), Shape.Invariant(e)) => a == e
      case _ => false

  private def headerType(shape: Shape, collection: KernelDigitalCollectionSnapshot): Unit =
    def check(token: String): Unit =
      if parameters.get(token).exists(_.region.contains(collection.path)) then
        fail("block argument type cannot depend on a body-local static definition", collection.path)
    shape match
      case Shape.Scalar(_, width) => check(width)
      case Shape.VectorValue(element, dimensions) =>
        dimensions.foreach(check)
        headerType(element, collection)
      case Shape.Record(_, fields) => fields.foreach(value => headerType(value._2, collection))
      case Shape.Invariant(_) => ()

  def argumentMatches(
      collection: KernelDigitalCollectionSnapshot,
      argument: KernelDigitalRegionArgument,
      expected: String,
      child: Query,
      parent: Query
  ): Boolean =
    if argument.dataType != "Integer" then
      headerType(parse(argument.dataType, argument.path), collection)
    if argument.dataType == "Integer" || expected == "Integer" then argument.dataType == expected
    else if Kinds.contains(collection.kind) && Set("left", "right").contains(argument.role) then
      argument.dataType == expected
    else
      sameAcross(
        parse(argument.dataType, argument.path),
        parse(expected, collection.path),
        collection.owner,
        child,
        parent
      )

  def resultMatches(
      collection: KernelDigitalCollectionSnapshot,
      yielded: String,
      child: Query,
      parent: Query
  ): Boolean =
    val actual = parse(yielded, collection.yieldValue)
    val seedMatches = collection.seed.forall(seed =>
      sameAcross(
        actual,
        parse(valueType(seed), seed),
        collection.owner,
        child,
        parent
      )
    )
    if collection.kind == "fold_left" then
      seedMatches && sameAcross(
        actual,
        parse(collection.dataType, collection.path),
        collection.owner,
        child,
        parent
      )
    else
      val (element, dimensions) = vectorType(collection.dataType, collection.path)
      val inputDimensions = vectorType(valueType(collection.inputs.head), collection.path)._2
      seedMatches && dimensions.size == inputDimensions.size &&
      dimensions.zip(inputDimensions).forall((a, b) =>
        parent.resolve(collection.owner, a) == parent.resolve(collection.owner, b)
      ) &&
      sameAcross(actual, parse(element, collection.path), collection.owner, child, parent)

  private def finite(shape: Shape, owner: String, query: Query, path: String): BigInt =
    def extent(token: String): BigInt =
      val bounds = query.bounds(query.resolve(owner, token))
      if bounds.lower <= 0 then
        fail("complete digital type has a nonpositive reachable extent", path)
      BigInt(bounds.upper)
    val result = shape match
      case Shape.Scalar(_, width) => extent(width)
      case Shape.VectorValue(element, dimensions) =>
        dimensions.foldLeft(finite(element, owner, query, path))((n, d) => n * extent(d))
      case Shape.Record(_, fields) => fields.map(value => finite(value._2, owner, query, path)).sum
      case Shape.Invariant("Bool") => BigInt(1)
      case Shape.Invariant("Real" | "Integer") => BigInt(64)
      case Shape.Invariant(text) =>
        val fields = text.drop(5).dropRight(1).split(",")
        if fields.length != 3 then fail("malformed invariant enum ABI", path)
        BigInt(fields(1).toLongOption.filter(_ > 0).getOrElse(fail("invalid enum width", path)))
    if result <= 0 || result > BigInt(Long.MaxValue) then
      fail("complete digital footprint exceeds positive signed-i64 bounds", path)
    result

  private def graph(query: Query, path: String): IntegerGraph[Int] = new IntegerGraph[Int]:
    override def charge(work: Int): Unit = DigitalReductionMlir.this.charge(work.toLong, path)
    def same(left: Int, right: Int): Boolean = left == right
    def operation(value: Int): Option[(String, Vector[Int])] = query.operation(value)
    def literal(value: Long): Int = query.literal(value)
    def bounds(value: Int): (Long, Long) =
      val valueBounds = query.bounds(value)
      valueBounds.lower -> valueBounds.upper
    def add(left: Int, right: Int): Int = query.compose("analog_add", Vector(left, right))
    def subtract(left: Int, right: Int): Int = query.compose("analog_sub", Vector(left, right))
    def multiply(left: Int, right: Int): Int = query.compose("analog_mul", Vector(left, right))
    def maximum(left: Int, right: Int): Int =
      query.compose("static_integer_max", Vector(left, right))
    def ceilLog2(value: Int): Int = query.compose("static_integer_ceil_log2", Vector(value))
    def chooseZero(test: Int, whenZero: Int, whenNonzero: Int): Int =
      query.compose("static_integer_choose_zero", Vector(test, whenZero, whenNonzero))

  def arguments(collection: KernelDigitalCollectionSnapshot): Option[Vector[(String, String)]] =
    Option.when(Kinds.contains(collection.kind)):
      val shape =
        parse(vectorType(valueType(collection.inputs.head), collection.path)._1, collection.path)
      val count = widths(shape).size
      if 2L * count + 2L + collection.captures.size > MaximumArguments then
        fail("reduction paired signature exceeds the 4098 argument budget", collection.path)
      val left = Vector.tabulate(count)(index => s"${collection.path}.arg$index")
      val right = Vector.tabulate(count)(index => s"${collection.path}.arg${count + index}")
      left.indices.map(index => "Integer" -> s"left_width:$index").toVector ++
        right.indices.map(index => "Integer" -> s"right_width:$index").toVector ++
        Vector(template(shape, left.iterator) -> "left", template(shape, right.iterator) -> "right")

  def contexts(collection: KernelDigitalCollectionSnapshot, parent: Query): Vector[Query] =
    val path = collection.path
    if parent.scope != collection.parentRegion then
      fail("collection context has another lexical parent", path)
    val captureStart = collection.arguments.size - collection.captures.size
    if captureStart < 0 then fail("collection capture argument segment is absent", path)
    val captures = collection.captures.zip(collection.arguments.drop(captureStart)).collect:
      case (source, argument) if argument.dataType == "Integer" =>
        if argument.role != "static_capture" || valueType(source) != "Integer" then
          fail("static capture requires its exact Integer operand and role", path)
        argument.path -> parent.exportValues(Vector(parent.resolve(collection.owner, source)))
    val captureBindings = captures.toMap
    if !Kinds.contains(collection.kind) then
      charge(
        expressionRegions.getOrElse(Some(path), Vector.empty).size.toLong +
          parameterRegions.getOrElse(Some(path), Vector.empty).size.toLong +
          collection.arguments.size,
        path
      )
      Vector(new Query(
        snapshot,
        scope = Some(path),
        exact = captureBindings,
        enclosing = Some(parent)
      ))
    else
      var ancestor = collection.parentRegion
      while ancestor.nonEmpty do
        val outer = collections(ancestor.get)
        if Kinds.contains(outer.kind) then
          fail("a reduction nested inside a reduction is outside the initial profile", path)
        ancestor = outer.parentRegion
      val (elementText, dimensions) = vectorType(valueType(collection.inputs.head), path)
      val initialShape = parse(elementText, path)
      val initialTokens = widths(initialShape)
      val m = initialTokens.size
      val expectedArguments = arguments(collection).get
      if collection.arguments.size != expectedArguments.size + collection.captures.size ||
        !collection.arguments.take(expectedArguments.size).zip(expectedArguments).forall(
          (a, expected) =>
            a.dataType == expected._1 && a.role == expected._2
        )
      then
        fail(
          "reduction templates must consume every canonical paired width formal exactly once",
          path
        )
      val count = dimensions.map(parent.resolve(collection.owner, _)).reduce((a, b) =>
        parent.compose("analog_mul", Vector(a, b))
      )
      val countBounds = parent.bounds(count)
      if countBounds.lower <= 0 then fail("reduction input count must remain positive", path)
      val initials = initialTokens.map(parent.resolve(collection.owner, _))
      val formals = collection.arguments.take(2 * m).map(_.path)
      val rawDomains = formals.zipWithIndex.map((formal, index) =>
        formal -> parent.bounds(initials(index % m))
      ).toMap
      val raw = new Query(
        snapshot,
        scope = Some(path),
        exact = captureBindings,
        domains = rawDomains,
        enclosing = Some(parent)
      )
      val yielded = parse(valueType(collection.yieldValue), path)
      val proposed = parse(collection.dataType, path)
      val pairedShapes = collection.arguments.slice(2 * m, 2 * m + 2).map(value =>
        parse(value.dataType, value.path)
      )
      if !sameShape(initialShape, yielded, collection.owner, raw) ||
        !sameShape(initialShape, proposed, collection.owner, parent)
      then
        fail("reduction yield or result changed its complete invariant schema", path)
      val rawFormals = formals.map(raw.resolve(collection.owner, _))
      val transfers = widths(yielded).zipWithIndex.map: (width, index) =>
        infer(
          raw.resolve(collection.owner, width),
          rawFormals(index),
          rawFormals(m + index),
          rawFormals,
          graph(raw, path)
        )
      def importTransfer(value: Transfer[Int]): Transfer[Int] =
        def imported(index: Int): Int =
          parent.importValue(collection.owner, raw.exportValues(Vector(index)))
        value match
          case Transfer.Invariant => Transfer.Invariant
          case Transfer.Fixed(value) => Transfer.Fixed(imported(value))
          case Transfer.Maximum(value) => Transfer.Maximum(imported(value))
          case Transfer.Sum(value) => Transfer.Sum(imported(value))
      val profiles = transfers.map(importTransfer)
      val balanced = collection.kind == "reduce_balanced"
      val resultWidths = widths(proposed).map(parent.resolve(collection.owner, _))
      profiles.zipWithIndex.foreach: (profile, index) =>
        val expected = formula(profile, initials(index), count, balanced, graph(parent, path))
        if resultWidths(index) != expected then
          fail("reduction final width is not the exact all-legal recurrence formula", path)
      val _ = finite(proposed, collection.owner, parent, path)
      val geometry =
        DigitalReductionContract.contexts(countBounds.lower, countBounds.upper, balanced)
      // A serialized singleton body still has to be well-formed; source construction bypasses it.
      val reachable =
        if geometry.isEmpty then Vector(SizeRange(1, 1) -> SizeRange(1, 1)) else geometry
      val regionNodes = expressionRegions.getOrElse(Some(path), Vector.empty).size +
        parameterRegions.getOrElse(Some(path), Vector.empty).size + collection.arguments.size
      charge(reachable.size.toLong * (regionNodes.toLong + m.toLong), path)
      reachable.map: (left, right) =>
        val exact = mutable.Map.from(captureBindings)
        val domains = mutable.Map.empty[String, IterationDomain.Bounds]
        Vector(left, right).zipWithIndex.foreach: (range, side) =>
          profiles.zipWithIndex.foreach: (profile, index) =>
            val formal = formals(side * m + index)
            def at(size: Long): Int =
              formula(profile, initials(index), parent.literal(size), balanced, graph(parent, path))
            profile match
              case Transfer.Invariant =>
                exact.update(formal, parent.exportValues(Vector(initials(index))))
              case Transfer.Fixed(value) if range.lower >= 2 =>
                exact.update(formal, parent.exportValues(Vector(value)))
              case _ if range.lower == range.upper =>
                exact.update(formal, parent.exportValues(Vector(at(range.lower))))
              case _ =>
                val lo = parent.bounds(at(range.lower))
                val hi = parent.bounds(at(range.upper))
                domains.update(formal, IterationDomain.Bounds(lo.lower, hi.upper))
        val query = new Query(
          snapshot,
          scope = Some(path),
          exact = exact.toMap,
          domains = domains.toMap,
          enclosing = Some(parent)
        )
        // Inner dimensions and nonselected leaves are invariant at every reachable combine,
        // not merely finite or equal under the first-call width substitution.
        if !(pairedShapes :+ yielded).forall(shape =>
            sameAcross(shape, initialShape, collection.owner, query, parent, includeWidths = false)
          )
        then
          fail("reachable reduction context changed its complete invariant schema", path)
        collection.arguments.filter(_.dataType != "Integer").foreach(value =>
          finite(parse(value.dataType, value.path), collection.owner, query, value.path)
        )
        // Validate every retained local type and static operation, including unused nodes.
        parameterRegions.getOrElse(Some(path), Vector.empty).foreach(value =>
          query.resolve(collection.owner, value.path)
        )
        expressionRegions.getOrElse(Some(path), Vector.empty).foreach: value =>
          val _ = finite(parse(value.dataType, value.path), collection.owner, query, value.path)
        query
