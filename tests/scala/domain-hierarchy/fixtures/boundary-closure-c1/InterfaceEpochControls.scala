package nodal.internal.testkit
import nodal.*
object InterfaceEpochControls:
  private def invoke(target: AnyRef, name: String, args: AnyRef*): AnyRef =
    val method = target.getClass.getDeclaredMethods.find(value =>
      value.getName == name || value.getName.endsWith("$$" + name)
    ).get
    method.setAccessible(true)
    try method.invoke(target, args*)
    catch case failure: java.lang.reflect.InvocationTargetException => throw failure.getCause
  private def field(target: AnyRef, suffix: String): AnyRef =
    val member = target.getClass.getDeclaredFields.find(_.getName.endsWith(suffix)).get
    member.setAccessible(true)
    member.get(target)
  private def declaration(owner: AnyRef, value: AnyRef): AnyRef =
    val ids = field(owner, "declarationIds").asInstanceOf[java.util.IdentityHashMap[AnyRef, AnyRef]]
    val reference = ids.get(value)
    assert(reference != null)
    val records = field(owner, "records").asInstanceOf[scala.collection.mutable.Map[Any, AnyRef]]
    val module = records(invoke(reference, "module"))
    val declarations =
      field(module, "declarations").asInstanceOf[scala.collection.mutable.ArrayBuffer[AnyRef]]
    declarations(invoke(reference, "index").asInstanceOf[java.lang.Integer].intValue())
  private def counts(record: AnyRef): Vector[Any] =
    invoke(record, "attributes").asInstanceOf[Vector[(String, Any)]].collect:
      case ("count", value) => value
  private def dimensions(owner: AnyRef, record: AnyRef): Vector[Dimension] =
    invoke(owner, "interfaceDimensions", record).asInstanceOf[Vector[Dimension]]
  def exercise(root: InterfaceEpochRoot): Unit =
    val owner = invoke(ConstructionKernel, "active").asInstanceOf[Option[AnyRef]].get
    assert(root.first.boundary && root.first.origin.isEmpty)
    assert(counts(declaration(owner, root.first)).isEmpty)
    invoke(owner, "beginIncrementalBoundaryClosure", root, Int.box(65536))
    val closure = field(owner, "boundaryClosureOwner").asInstanceOf[Option[AnyRef]].get
    val first = declaration(owner, root.first)
    assert(counts(first) == Vector(2))
    assert(dimensions(owner, first) == Vector(2))
    val before = field(closure, "currentSnapshot")
    val second = root.next()
    assert(second.boundary && second.origin.isEmpty && (second ne root.first))
    assert(counts(declaration(owner, second)).isEmpty)
    invoke(owner, "drainIncrementalBoundaryClosure")
    val fresh = declaration(owner, second)
    assert(!(field(closure, "currentSnapshot") eq before))
    assert(declaration(owner, root.first) eq first)
    assert(counts(first) == Vector(2) && dimensions(owner, first) == Vector(2))
    assert(counts(fresh) == Vector(3) && dimensions(owner, fresh) == Vector(3))
    val settled = field(closure, "currentSnapshot")
    invoke(owner, "drainIncrementalBoundaryClosure")
    assert(field(closure, "currentSnapshot") eq settled)
    assert(declaration(owner, root.first) eq first)
    assert(declaration(owner, second) eq fresh)
    assert(counts(first) == Vector(2) && dimensions(owner, first) == Vector(2))
    assert(counts(fresh) == Vector(3) && dimensions(owner, fresh) == Vector(3))
  def main(args: Array[String]): Unit =
    assert(args.isEmpty)
    val snapshot = InterfaceEpochFactory.capture()
    assert(snapshot.modules.count(_.externalName.contains("EpochArrayPins")) == 1)
    assert(snapshot.modules.forall(_.domains.isEmpty))
    val endpoints = snapshot.interfaceContracts.filter(_.boundary.nonEmpty)
    assert(endpoints.size == 2)
    assert(endpoints.map(_.dimensions).toSet == Set(Vector("2"), Vector("3")))
    endpoints.foreach: endpoint =>
      val declaration = snapshot.modules.flatMap(_.declarations).find(_.path == endpoint.path).get
      assert(declaration.attributes.collect { case ("count", value) => value } ==
        endpoint.dimensions)
      val boundary = endpoint.boundary.get
      StructuralParameterBinding.validateInterfaceDimensions(
        snapshot,
        endpoint.owner,
        boundary.instance.get,
        boundary.endpoint,
        endpoint.dimensions
      )
    println("PASS InterfaceArray second-batch counts, exact dimensions and empty-drain idempotence")
