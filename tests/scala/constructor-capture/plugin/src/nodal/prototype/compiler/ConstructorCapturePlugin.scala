package nodal.prototype.compiler

import dotty.tools.dotc.ast.tpd
import dotty.tools.dotc.ast.tpd.*
import dotty.tools.dotc.core.Annotations.Annotation
import dotty.tools.dotc.core.Constants.Constant
import dotty.tools.dotc.core.Contexts.*
import dotty.tools.dotc.core.Flags.*
import dotty.tools.dotc.core.NameKinds.{DefaultGetterName, UniqueName}
import dotty.tools.dotc.core.Names.*
import dotty.tools.dotc.core.StdNames.nme
import dotty.tools.dotc.core.Symbols.*
import dotty.tools.dotc.core.Types.*
import dotty.tools.dotc.plugins.{PluginPhase, StandardPlugin}
import dotty.tools.dotc.report

import scala.collection.mutable

/** Compile-only probe, deliberately restricted to literal Real defaults.
  * No production Nodal classes are linked into the compiler plugin.
  */
final class ConstructorCapturePlugin extends StandardPlugin:
  val name = "nodal-constructor-probe"
  val description = "Bounded constructor/default capture probe for Scala 3.8.4"

  override def initialize(options: List[String])(using Context): List[PluginPhase] =
    if options.nonEmpty then
      report.error("NODAL-CTOR-PROTOTYPE-OPTIONS: this probe accepts no options")
    List(new ConstructorCapturePhase)

private final case class Parameter(name: String, default: Double)
private final case class Schema(parameters: List[Parameter]):
  def encoded: String = parameters.map { parameter =>
    parameter.name + ":" + java.lang.Long.toHexString(
      java.lang.Double.doubleToRawLongBits(parameter.default)
    )
  }.mkString(",")

private final class ConstructorCapturePhase extends PluginPhase:
  val phaseName = "nodalConstructorCapture"
  override val runsAfter = Set("posttyper")
  override val runsBefore = Set("pickler")

  private var scanned = false
  private val schemas = mutable.HashMap.empty[Symbol, Schema]
  private val defaults = mutable.HashMap.empty[Symbol, Double]
  private val rejected = mutable.HashSet.empty[Symbol]

  private def moduleClass(using Context) = requiredClass("nodal.prototype.Module")
  private def paramClass(using Context) = requiredClass("nodal.prototype.Param")
  private def realClass(using Context) = requiredClass("nodal.prototype.Real")
  private def markerClass(using Context) = requiredClass("nodal.prototype.ConstructorSchema")
  private def string(value: String)(using Context): Tree = Literal(Constant(value))
  private def number(value: Double)(using Context): Tree = Literal(Constant(value))

  private def fail(code: String, message: String, tree: Tree)(using Context): Unit =
    report.error(s"NODAL-CTOR-PROTOTYPE-$code: $message", tree.srcPos)

  /** Scan every already-typed unit before transforming any one unit. DefTree is
    * not generally retained for non-inline default getters; traversing the real
    * units also handles a constructor definition later in the source list.
    */
  override def prepareForUnit(tree: Tree)(using Context): Context =
    if !scanned then
      scanned = true
      val definitions = mutable.HashMap.empty[Symbol, DefDef]
      val classes = mutable.ArrayBuffer.empty[(TypeDef, Context)]
      val scanBase = ctx
      scanBase.run.nn.units.foreach: unit =>
        given Context = scanBase.fresh.setCompilationUnit(unit)
        val collect = new TreeTraverser:
          override def traverse(tree: Tree)(using Context): Unit =
            tree match
              case definition: DefDef => definitions(definition.symbol) = definition
              case definition: TypeDef if definition.symbol.isClass =>
                if definition.symbol != moduleClass &&
                    definition.symbol.derivesFrom(moduleClass) then
                  classes += ((definition, ctx))
              case _ => ()
            traverseChildren(tree)
        collect.traverse(unit.tpdTree)
      classes.foreach: (definition, context) =>
        capture(definition, definitions.toMap)(using context)
    ctx

  private def capture(
      definition: TypeDef,
      definitions: Map[Symbol, DefDef]
  )(using Context): Unit =
    val cls = definition.symbol.asClass
    val constructor = cls.primaryConstructor
    val lists = constructor.paramSymss
    val constructors = definitions.keys.filter(s => s.owner == cls && s.isConstructor).toList
    val direct = cls.info.parents.exists(_.typeSymbol == moduleClass)
    val simpleOwner = cls.owner.is(Package)
    val plain = direct && simpleOwner && cls.typeParams.isEmpty &&
      !cls.is(Trait) && !cls.is(ModuleClass) &&
      lists.size == 1 && constructors.size == 1
    if !plain then
      rejected += cls
      fail("SHAPE", "partial probe requires a named top-level direct Module subclass, " +
        "one primary parameter list and no type parameters or secondary constructors", definition)
    else if cls.hasAnnotation(markerClass) then
      rejected += cls
      fail(
        "METADATA",
        "source must not supply compiler-owned ConstructorSchema metadata",
        definition
      )
    else
      val expected = paramClass.typeRef.appliedTo(realClass.typeRef)
      val fields = mutable.ListBuffer.empty[Parameter]
      val getterValues = mutable.ListBuffer.empty[(Symbol, Double)]
      var valid = true
      lists.head.zipWithIndex.foreach: (parameter, index) =>
        val name = parameter.name.toString
        if !(parameter.info.dealias =:= expected) ||
            parameter.isOneOf(Given | Implicit | Erased) ||
            !name.matches("[A-Za-z_][A-Za-z0-9_]*") then
          valid = false
          fail(
            "PARAMETER",
            "partial probe requires Param[Real] parameters with simple names",
            definition
          )
        else
          val companion = cls.companionModule
          val getterName = DefaultGetterName(nme.CONSTRUCTOR, index)
          val getter = if companion.exists then
            val denotation = companion.info.member(getterName)
            if denotation.exists && !denotation.isOverloaded then denotation.symbol else NoSymbol
          else NoSymbol
          val declaredDefault = parameter.is(HasDefault) && getter.exists &&
            getter.owner == companion.moduleClass && getter.name == getterName
          val literal = if declaredDefault then
            definitions.get(getter).flatMap(d => literalDefault(d.rhs))
          else None
          literal match
            case Some(value) if java.lang.Double.isFinite(value) =>
              fields += Parameter(name, value)
              getterValues += ((getter, value))
            case _ =>
              valid = false
              fail("DEFAULT", "partial probe accepts only a finite Double literal default " +
                "lifted by Param.liftReal; general host/default expressions remain required work",
                definition
              )
      if valid then
        val schema = Schema(fields.toList)
        schemas(cls) = schema
        getterValues.foreach: (getter, value) => defaults(getter) = value
        cls.addAnnotation(Annotation(
          markerClass,
          List(Literal(Constant(1)), string(schema.encoded)),
          definition.span
        ))
      else rejected += cls

  /** This symbol equality admits only the known pure literal lifting method.
    * It does not execute the getter or inspect arbitrary host expression names.
    */
  private def literalDefault(tree: Tree)(using Context): Option[Double] = tree match
    case Apply(function, List(Literal(constant)))
        if function.symbol == requiredMethod("nodal.prototype.Param.liftReal") =>
      constant.value match
        case value: Double => Some(value)
        case _ => None
    case Typed(expression, _) => literalDefault(expression)
    case Block(Nil, expression) => literalDefault(expression)
    case _ => None

  /** Keep the compiler's original getter call and all named-argument temporaries.
    * Change only its approved pure literal body into an inert omitted marker.
    * Explicit arguments execute no getter; omitted arguments execute it once.
    */
  override def transformDefDef(tree: DefDef)(using Context): Tree =
    defaults.get(tree.symbol) match
      case Some(value) =>
        val ownerContext = ctx.fresh.setOwner(tree.symbol)
        val rhs = ref(requiredMethod("nodal.prototype.Capture.omitted"))
          .appliedTo(number(value))(using ownerContext)
          .withSpan(tree.rhs.span)
        cpy.DefDef(tree)(rhs = rhs)
      case None => tree

  private def importedSchema(cls: Symbol, tree: Tree)(using Context): Option[Schema] =
    if rejected(cls) then None
    else schemas.get(cls).orElse:
      val annotations = cls.annotations.filter(_.symbol == markerClass)
      annotations match
        case annotation :: Nil if annotation.arguments.size == 2 =>
          val versionOne = annotation.argumentConstant(0).exists: constant =>
            constant.value match
              case value: Int => value == 1
              case _ => false
          val encoded = annotation.argumentConstantString(1)
          if !versionOne || encoded.isEmpty then
            fail("ABI", "missing or unsupported constructor metadata version", tree)
            None
          else
            try
              val parameters = if encoded.get.isEmpty then Nil else
                encoded.get.split(",", -1).toList.map: field =>
                  val pair = field.split(":", -1)
                  if pair.length != 2 || !pair(0).matches("[A-Za-z_][A-Za-z0-9_]*") then
                    scala.util.Failure[Nothing](
                      new IllegalArgumentException("invalid schema field")
                    ).get
                  val value = java.lang.Double.longBitsToDouble(
                    java.lang.Long.parseUnsignedLong(pair(1), 16)
                  )
                  if !java.lang.Double.isFinite(value) then
                    scala.util.Failure[Nothing](
                      new IllegalArgumentException("nonfinite default")
                    ).get
                  Parameter(pair(0), value)
              val formals = cls.primaryConstructor.paramSymss
              val profileMatches = cls.info.parents.exists(_.typeSymbol == moduleClass) &&
                cls.owner.is(Package) && cls.asClass.typeParams.isEmpty &&
                !cls.is(Trait) && !cls.is(ModuleClass) &&
                cls.info.decl(nme.CONSTRUCTOR).alternatives.size == 1
              if !profileMatches || formals.size != 1 ||
                  formals.head.map(_.name.toString) != parameters.map(_.name) ||
                  formals.head.exists(p =>
                    !(p.info.dealias =:= paramClass.typeRef.appliedTo(realClass.typeRef)) ||
                      !p.is(HasDefault) || p.isOneOf(Given | Implicit | Erased)
                  ) then
                scala.util.Failure[Nothing](
                  new IllegalArgumentException("metadata does not match constructor signature")
                ).get
              val schema = Schema(parameters)
              schemas(cls) = schema
              Some(schema)
            catch
              case _: IllegalArgumentException =>
                fail("ABI", "malformed constructor metadata or signature mismatch", tree)
                None
        case Nil =>
          fail(
            "MISSING",
            "Module constructor was compiled without the required capture metadata",
            tree
          )
          None
        case _ =>
          fail("ABI", "constructor metadata must be unique with exactly two arguments", tree)
          None

  override def transformApply(tree: Apply)(using Context): Tree = tree.fun match
    case selection: Select
        if selection.qualifier.isInstanceOf[New] && selection.symbol.isConstructor =>
      val cls = selection.symbol.owner
      if cls == moduleClass || !cls.derivesFrom(moduleClass) then tree
      else if selection.symbol != cls.primaryConstructor then
        fail("SHAPE", "secondary constructor allocation is outside the partial probe", tree)
        tree
      else importedSchema(cls, tree) match
        case Some(schema) if schema.parameters.size == tree.args.size =>
          // Original typer-generated Blocks remain outside this replacement.
          // These argument values preserve their typed application order once.
          val arguments = tree.args.map: argument =>
            SyntheticValDef(UniqueName.fresh(termName("constructorActual")), argument)
          val body = () =>
            val carriers = schema.parameters.zip(arguments).map: (parameter, actual) =>
              SyntheticValDef(
                UniqueName.fresh(termName("constructorCarrier")),
                ref(requiredMethod("nodal.prototype.Capture.carrier")).appliedToArgs(List(
                  string(parameter.name), number(parameter.default), ref(actual.symbol)
                ))
              )
            Block(carriers, cpy.Apply(tree)(args = carriers.map(v => ref(v.symbol))))
          val thunk = Lambda(MethodType(Nil)(_ => Nil, _ => tree.tpe), _ => body())
          val allocation = ref(requiredMethod("nodal.prototype.Capture.allocate"))
            .appliedToType(tree.tpe)
            .appliedToArgs(List(
              string(cls.fullName.toString),
              string(ctx.source.file.path + ":" + tree.span.start),
              string(schema.encoded),
              thunk
            ))
          Block(arguments, allocation).withSpan(tree.span)
        case Some(_) =>
          fail("ARITY", "typed constructor application does not match captured metadata", tree)
          tree
        case None => tree
    case TypeApply(selection: Select, _)
        if selection.qualifier.isInstanceOf[New] && selection.symbol.isConstructor &&
          selection.symbol.owner.derivesFrom(moduleClass) =>
      fail("SHAPE", "type-applied Module construction is outside the partial probe", tree)
      tree
    case _ => tree
