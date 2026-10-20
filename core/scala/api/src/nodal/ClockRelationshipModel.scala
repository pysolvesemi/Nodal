package nodal

import scala.collection.mutable

/** Bounded exact relationship constraints. Physical Clock identity is supplied by its existing
  * owner; this index grants only direct-sampling permission and never replaces actual domain
  * identity.
  */
private[nodal] object ClockRelationshipModel:
  val Code = "NODAL-CLOCK-RELATION-057-001"
  val Contract = "relationship_v1"
  val Kinds = Set("same", "ratio", "synchronous", "mutually_exclusive", "asynchronous", "unknown")
  def proof(kind: String, structural: Boolean): String = kind match
    case "same" => if structural then "structural" else "external_contract"
    case "ratio" | "synchronous" => "timing_contract"
    case "mutually_exclusive" => "operating_mode_contract"
    case _ => "classification"
  def obligations(kind: String, proof: String): Vector[(String, String)] = kind match
    case "same" if proof == "external_contract" => Vector("active_edges" -> "identical_stream")
    case "ratio" => Vector("timing" -> "exact_relative_period_phase")
    case "synchronous" => Vector("timing" -> "synchronous_phase_knowledge_retained")
    case "mutually_exclusive" => Vector("exclusion" -> "never_simultaneously_enabled")
    case _ => Vector.empty

  final case class Endpoint(
      path: String,
      owner: String,
      clock: String,
      identity: String,
      external: Boolean,
      edge: String,
      frequency: Option[String]
  )

  private[nodal] final class Arithmetic(fail: String => Nothing):
    private val bits = 4096
    private val maximum = (BigInt(1) << bits) - 1
    private def checked(n: BigInt, d: BigInt): Rational =
      if d == 0 || n.abs.bitLength > bits || d.abs.bitLength > bits then
        fail("exact relationship arithmetic exceeds its 4096-bit budget")
      val g = n.gcd(d)
      val sign = if d < 0 then -1 else 1
      Rational(n / g * sign, d.abs / g)
    private def product(a: BigInt, b: BigInt): BigInt =
      if b != 0 && a.abs > maximum / b.abs then
        fail("exact relationship multiplication exceeds its 4096-bit budget")
      a * b
    final case class Rational private[Arithmetic] (n: BigInt, d: BigInt):
      def +(other: Rational): Rational =
        val g = d.gcd(other.d)
        val a = product(n, other.d / g)
        val b = product(other.n, d / g)
        if a.signum == b.signum && a.abs > maximum - b.abs then
          fail("exact relationship addition exceeds its 4096-bit budget")
        checked(a + b, product(d, other.d / g))
      def unary_- : Rational = Rational(-n, d)
      def -(other: Rational): Rational = this + -other
      def *(other: Rational): Rational =
        val g = n.gcd(other.d)
        val h = other.n.gcd(d)
        checked(product(n / g, other.n / h), product(d / h, other.d / g))
      def /(other: Rational): Rational = this * checked(other.d, other.n)
      def modulo(period: Rational): Rational =
        val quotient = this / period
        val remainder = quotient.n % quotient.d
        checked(if remainder < 0 then remainder + quotient.d else remainder, quotient.d) * period
      def isZero: Boolean = n == 0
    val zero = checked(0, 1)
    val one = checked(1, 1)
    def integer(value: Int): Rational = checked(value, 1)
    def decimal(value: String): Rational =
      if value.length > 1400 || !value.matches("[+-]?[0-9]+(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?") then
        fail("relationship number requires a bounded exact decimal")
      val exponentParts = value.split("[eE]", -1)
      val exponent = if exponentParts.length == 1 then 0
      else
        exponentParts(1).toIntOption.filter(v => v >= -1232 && v <= 1232).getOrElse(
          fail("relationship decimal exponent exceeds its arithmetic budget")
        )
      val significand = exponentParts(0)
      val point = significand.indexOf('.')
      val fraction = if point < 0 then 0 else significand.length - point - 1
      val scale = fraction - exponent
      if scale.abs > 1232 then fail("relationship decimal exponent exceeds its arithmetic budget")
      var unscaled = BigInt(0)
      significand.filter(_.isDigit).foreach: digit =>
        val next = product(unscaled, 10)
        val increment = BigInt(digit - '0')
        if next > maximum - increment then
          fail("relationship decimal exceeds its arithmetic budget")
        unscaled = next + increment
      if significand.startsWith("-") then unscaled = -unscaled
      if scale >= 0 then checked(unscaled, BigInt(10).pow(scale))
      else checked(product(unscaled, BigInt(10).pow(-scale)), 1)

  /** Path-compressed union index, reused for distinct permission and consistency equivalences. */
  private final class Components(paths: Iterable[String]):
    private val parent = mutable.Map.from(paths.iterator.map(p => p -> p))
    private val rank = mutable.Map.from(paths.iterator.map(p => p -> 0))
    def root(path: String): String =
      var current = path
      while parent(current) != current do current = parent(current)
      var entry = path
      while parent(entry) != entry do
        val next = parent(entry)
        parent(entry) = current
        entry = next
      current
    def join(left: String, right: String): Unit =
      val a = root(left)
      val b = root(right)
      if a != b then
        if rank(a) < rank(b) then parent(a) = b
        else
          parent(b) = a
          if rank(a) == rank(b) then rank(a) = rank(a) + 1

  final class Index(
      endpoints: Vector[Endpoint],
      relations: Vector[KernelClockRelationshipSnapshot],
      reject: (String, String) => Nothing
  ):
    private val nodes = endpoints.map(e => e.path -> e).toMap
    private def fail(message: String, path: String): Nothing = reject(message, path)
    if nodes.size != endpoints.size || nodes.size > 65536 || relations.size > 65536 then
      fail(
        "relationship graph requires unique bounded domain identities",
        endpoints.headOption.map(_.path).getOrElse("")
      )
    private val same = new Components(nodes.keys)
    private def find(path: String): String = same.root(path)
    private def union(left: String, right: String): Unit = same.join(left, right)
    def permitsSampling(source: String, destination: String): Boolean =
      source == destination ||
        nodes.contains(source) && nodes.contains(destination) && find(source) == find(destination)

    private var activePath = endpoints.headOption.map(_.path).getOrElse("")
    private val a = new Arithmetic(message => fail(message, activePath))
    import a.Rational
    private final case class Constraint(
        source: String,
        destination: String,
        ratio: Rational,
        phase: Rational,
        path: String
    )
    private val constraints = mutable.ArrayBuffer.empty[Constraint]
    private val pairKinds =
      mutable.Map.empty[(String, String), mutable.Map[String, KernelClockRelationshipSnapshot]]
    private def pair(left: String, right: String): (String, String) =
      if left < right then left -> right else right -> left
    private def endpoint(path: String, relation: KernelClockRelationshipSnapshot): Endpoint =
      nodes.getOrElse(
        path,
        fail("relationship endpoint is not an actual owned domain", relation.path)
      )

    relations.sortBy(_.path).foreach: relation =>
      activePath = relation.path
      val source = endpoint(relation.source, relation)
      val destination = endpoint(relation.destination, relation)
      val structural = source.identity == destination.identity
      if source.owner != destination.owner ||
        source.edge != destination.edge || !Set("rising", "falling")(source.edge) ||
        !Kinds(relation.kind) || relation.contract != Contract ||
        relation.proof != proof(relation.kind, structural) ||
        relation.obligations != obligations(relation.kind, relation.proof) ||
        relation.multiply.nonEmpty !=
          (relation.kind == "ratio") ||
          relation.divide.nonEmpty !=
          (relation.kind == "ratio") ||
          relation.phase.nonEmpty !=
          (relation.kind == "ratio") ||
          relation.phaseKnown.nonEmpty !=
          (relation.kind == "synchronous")
      then
        fail(
          "relationship requires exact owned clock operands, fields, proof and obligations",
          relation.path
        )
      if relation.kind == "same" then
        if !structural && !(source.external && destination.external) then
          fail("distinct gate or mux producers cannot acquire Same from metadata", relation.path)
        union(source.path, destination.path)
      if relation.kind == "same" || relation.kind == "ratio" then
        val ratio = if relation.kind == "same" then a.one
        else
          if relation.multiply.get <= 0 || relation.divide.get <= 0 then
            fail("ratio requires positive int32 multiply and divide", relation.path)
          a.integer(relation.divide.get) / a.integer(relation.multiply.get)
        val phase = if relation.kind == "same" then a.zero
        else
          val value = relation.phase.get
          val double = value.toDoubleOption.filter(java.lang.Double.isFinite).getOrElse(fail(
            "phase requires finite canonical Double decimal",
            relation.path
          ))
          if java.lang.Double.toString(double) != value then
            fail("phase requires canonical Double decimal spelling", relation.path)
          (a.decimal(value) / a.integer(360)).modulo(a.one)
        constraints += Constraint(source.path, destination.path, ratio, phase, relation.path)
    // Exact physical aliases are zero-phase timing constraints, not an implicit Same declaration.
    endpoints.groupBy(e => (e.owner, e.identity, e.edge)).values.foreach: aliases =>
      val ordered = aliases.sortBy(_.path)
      ordered.drop(1).foreach(e =>
        constraints += Constraint(ordered.head.path, e.path, a.one, a.zero, e.path)
      )
    // Duplicate claims normalize both structural aliases and proved Same, not timing ratios.
    private val claims = new Components(nodes.keys)
    endpoints.groupBy(e => (e.owner, e.identity, e.edge)).values.foreach: aliases =>
      aliases.drop(1).foreach(e => claims.join(aliases.head.path, e.path))
    relations.filter(_.kind == "same").foreach(r => claims.join(r.source, r.destination))
    relations.filter(_.kind != "unknown").sortBy(_.path).foreach: relation =>
      val key = pair(claims.root(relation.source), claims.root(relation.destination))
      val kinds = pairKinds.getOrElseUpdate(key, mutable.Map.empty)
      kinds.get("synchronous").filter(_ => relation.kind == "synchronous").foreach: prior =>
        if prior.phaseKnown != relation.phaseKnown then
          fail("contradictory synchronous phase knowledge", relation.path)
      kinds(relation.kind) = relation
    private val adjacency = mutable.Map.empty[String, mutable.ArrayBuffer[(Constraint, Boolean)]]
    constraints.foreach: constraint =>
      adjacency.getOrElseUpdate(constraint.source, mutable.ArrayBuffer.empty) +=
        ((constraint, false))
      adjacency.getOrElseUpdate(constraint.destination, mutable.ArrayBuffer.empty) +=
        ((constraint, true))
    private val periods = mutable.Map.empty[String, Rational]
    private val offsets = mutable.Map.empty[String, Rational]
    private val components = mutable.Map.empty[String, String]
    private var work = 0L
    nodes.keys.toVector.sorted.foreach: root =>
      if !periods.contains(root) then
        periods(root) = a.one
        offsets(root) = a.zero
        val pending = mutable.ArrayDeque(root)
        while pending.nonEmpty do
          val current = pending.removeHead()
          components(current) = root
          adjacency.getOrElse(current, mutable.ArrayBuffer.empty).sortBy((c, reverse) =>
            (if reverse then c.source else c.destination, c.path)
          ).foreach: (c, reverse) =>
            work += 1
            activePath = c.path
            if work > 524288 then fail("relationship graph exceeds work budget", c.path)
            val next = if reverse then c.source else c.destination
            if !periods.contains(next) then
              val period =
                if reverse then periods(current) / c.ratio else periods(current) * c.ratio
              val offset = if reverse then offsets(current) - periods(current) * c.phase
              else offsets(current) + period * c.phase
              periods(next) = period
              offsets(next) = offset
              pending.append(next)
    constraints.foreach: c =>
      activePath = c.path
      val expected = periods(c.source) * c.ratio
      if periods(c.destination) != expected ||
        !(offsets(c.destination) - offsets(c.source) - expected * c.phase).modulo(expected).isZero
      then
        fail("inconsistent ratio or phase through aliases, cycles or alternative paths", c.path)
    private val absolute = mutable.Map.empty[String, Rational]
    private val physicalFrequency = mutable.Map.empty[(String, String), Rational]
    endpoints.sortBy(_.path).foreach: e =>
      activePath = e.path
      e.frequency.foreach: spelling =>
        val frequency = a.decimal(spelling)
        if frequency.n <= 0 then fail("known clock frequency must be positive", e.path)
        val key = (e.owner, e.identity)
        physicalFrequency.get(key).foreach: known =>
          if known != frequency then
            fail("physical clock aliases have inconsistent frequencies", e.path)
        physicalFrequency(key) = frequency
        val rootPeriod = a.one / frequency / periods(e.path)
        absolute.get(components(e.path)).foreach: known =>
          if known != rootPeriod then
            fail("known clock frequency contradicts exact relationship", e.path)
        absolute(components(e.path)) = rootPeriod
    private val timing = new Components(nodes.keys)
    private def timingRoot(path: String): String = timing.root(path)
    private def joinTiming(left: String, right: String): Unit = timing.join(left, right)
    constraints.foreach(c => joinTiming(c.source, c.destination))
    relations.filter(_.kind == "synchronous").foreach(r => joinTiming(r.source, r.destination))
    relations.foreach: r =>
      activePath = r.path
      if r.kind == "asynchronous" && timingRoot(r.source) == timingRoot(r.destination) then
        fail("asynchronous claim contradicts known timing or Same", r.path)
      if r.kind == "mutually_exclusive" &&
        (find(r.source) == find(r.destination) ||
          nodes(r.source).identity == nodes(r.destination).identity)
      then
        fail("exclusive sources cannot be physically identical or Same", r.path)
    pairKinds.toVector.sortBy(_._1).foreach: (_, kinds) =>
      val contradictory = kinds.contains("asynchronous") &&
        (kinds.contains("same") || kinds.contains("ratio") || kinds.contains("synchronous")) ||
        kinds.contains("mutually_exclusive") && kinds.contains("same")
      if contradictory then
        fail(
          "contradictory relationship claims on one physical clock pair",
          kinds.values.toVector.sortBy(_.path).head.path
        )
