package nodal.internal.frontend.compiler

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

final class ConstructorCapturePlugin extends StandardPlugin:
  val name = "nodal-constructor"
  val description = "Nodal constructor metadata and allocation capture"

  override def initialize(options: List[String])(using Context): List[PluginPhase] =
    if options.nonEmpty then report.error("NODAL-CONSTRUCTOR-OPTIONS: no options are supported")
    List(new ConstructorCapturePhase)

private final case class Parameter(name: String, default: Double)
private final case class Schema(parameters: List[Parameter]):
  def encoded: String =
    parameters.map { parameter =>
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

  private def moduleClass(using Context) = requiredClass("nodal.Module")
  private def paramClass(using Context) = requiredClass("nodal.Param")
  private def realClass(using Context) = requiredClass("nodal.Real")
  private def markerClass(using Context) = requiredClass("nodal.ConstructorSchema")
  private def string(value: String)(using Context): Tree = Literal(Constant(value))
  private def number(value: Double)(using Context): Tree = Literal(Constant(value))

  private def fail(code: String, message: String, tree: Tree)(using Context): Unit =
    report.error(s"NODAL-CONSTRUCTOR-$code: $message", tree.srcPos)

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
                  definition.symbol.derivesFrom(moduleClass)
                then classes += ((definition, ctx))
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
    val constructors = definitions.keys.filter(symbol =>
      symbol.owner == cls && symbol.isConstructor
    ).toList
    val direct = cls.info.parents.exists(_.typeSymbol == moduleClass)
    val simpleOwner = cls.owner.is(Package)
    val simple = direct && simpleOwner && cls.typeParams.isEmpty &&
      !cls.is(Trait) && !cls.is(ModuleClass) && constructors.size == 1
    val formals = lists.flatten
    val expected = paramClass.typeRef.appliedTo(realClass.typeRef)
    val hasParameter = formals.exists(_.info.dealias.typeSymbol == paramClass)

    if cls.hasAnnotation(markerClass) then
      rejected += cls
      fail(
        "METADATA",
        "source must not supply compiler-owned ConstructorSchema metadata",
        definition
      )
    else if simple && lists.size == 1 && formals.isEmpty then
      publish(cls, definition, Schema(Nil))
    else if hasParameter then
      val supported = simple && lists.size == 1 && formals.nonEmpty &&
        formals.forall(parameter =>
          parameter.info.dealias =:= expected &&
            !parameter.isOneOf(Given | Implicit | Erased)
        )
      if !supported then
        rejected += cls
        fail(
          "SHAPE",
          "constructor Param capture requires a named top-level direct nongeneric Module with " +
            "one primary Param[Real] list and no secondary constructors",
          definition
        )
      else
        val fields = mutable.ListBuffer.empty[Parameter]
        val getterValues = mutable.ListBuffer.empty[(Symbol, Double)]
        var valid = true
        lists.head.zipWithIndex.foreach: (parameter, index) =>
          val name = parameter.name.toString
          val companion = cls.companionModule
          val getterName = DefaultGetterName(nme.CONSTRUCTOR, index)
          val getter = if companion.exists then
            val denotation = companion.info.member(getterName)
            if denotation.exists && !denotation.isOverloaded then denotation.symbol else NoSymbol
          else NoSymbol
          val declaredDefault = parameter.is(HasDefault) && getter.exists &&
            getter.owner == companion.moduleClass && getter.name == getterName
          val literal = if declaredDefault then
            definitions.get(getter).flatMap(definition => literalDefault(definition.rhs))
          else None
          literal match
            case Some(value)
                if java.lang.Double.isFinite(value) &&
                  name.matches("[A-Za-z_][A-Za-z0-9_]*") =>
              fields += Parameter(name, value)
              getterValues += ((getter, value))
            case _ =>
              valid = false
              fail(
                "DEFAULT",
                "constructor Param[Real] requires a finite Double literal default lifted by " +
                  "Param.liftReal",
                definition
              )
        if valid then
          publish(cls, definition, Schema(fields.toList))
          getterValues.foreach { case (getter, value) =>
            defaults(getter) = value
          }
        else rejected += cls
    else ()

  private def publish(cls: ClassSymbol, definition: TypeDef, schema: Schema)(using Context): Unit =
    schemas(cls) = schema
    cls.addAnnotation(
      Annotation(
        markerClass,
        List(Literal(Constant(1)), string(schema.encoded)),
        definition.span
      )
    )

  private def literalDefault(tree: Tree)(using Context): Option[Double] = tree match
    case Apply(function, List(Literal(constant)))
        if function.symbol == requiredMethod("nodal.Param.liftReal") =>
      constant.value match
        case value: Double => Some(value)
        case _ => None
    case Typed(expression, _) => literalDefault(expression)
    case Block(Nil, expression) => literalDefault(expression)
    case _ => None

  override def transformDefDef(tree: DefDef)(using Context): Tree =
    defaults.get(tree.symbol) match
      case Some(value) =>
        val ownerContext = ctx.fresh.setOwner(tree.symbol)
        val rhs = ref(requiredMethod("nodal.ConstructorCaptureRuntime.omittedReal"))
          .appliedTo(number(value))(using ownerContext)
          .withSpan(tree.rhs.span)
        cpy.DefDef(tree)(rhs = rhs)
      case None => tree

  private def importedSchema(cls: Symbol, tree: Tree)(using Context): Option[Schema] =
    if rejected(cls) then None
    else
      schemas.get(cls).orElse:
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
              parseImported(cls, encoded.get, tree)
          case Nil =>
            if requiresCapture(cls) then
              fail(
                "MISSING",
                "Module constructor was compiled without required constructor metadata",
                tree
              )
            None
          case _ =>
            fail("ABI", "constructor metadata must be unique with exactly two arguments", tree)
            None

  private def requiresCapture(cls: Symbol)(using Context): Boolean =
    val lists = cls.primaryConstructor.paramSymss
    val formals = lists.flatten
    val direct = cls.info.parents.exists(_.typeSymbol == moduleClass)
    val simple = direct && cls.owner.is(Package) && cls.asClass.typeParams.isEmpty &&
      !cls.is(Trait) && !cls.is(ModuleClass) &&
      cls.info.decl(nme.CONSTRUCTOR).alternatives.size == 1
    formals.exists(_.info.dealias.typeSymbol == paramClass) ||
    (simple && lists.size == 1 && formals.isEmpty)

  private def parseImported(
      cls: Symbol,
      encoded: String,
      tree: Tree
  )(using Context): Option[Schema] =
    try
      val parameters = if encoded.isEmpty then Nil
      else
        encoded.split(",", -1).toList.map: field =>
          val pair = field.split(":", -1)
          if pair.length != 2 || !pair(0).matches("[A-Za-z_][A-Za-z0-9_]*") then
            scala.util.Failure[Nothing](new IllegalArgumentException("invalid schema field")).get
          val value = java.lang.Double.longBitsToDouble(
            java.lang.Long.parseUnsignedLong(pair(1), 16)
          )
          if !java.lang.Double.isFinite(value) then
            scala.util.Failure[Nothing](new IllegalArgumentException("nonfinite default")).get
          Parameter(pair(0), value)
      val formals = cls.primaryConstructor.paramSymss
      val flattened = formals.flatten
      val expected = paramClass.typeRef.appliedTo(realClass.typeRef)
      val shapeMatches = requiresCapture(cls) && formals.size == 1 &&
        flattened.map(_.name.toString) == parameters.map(_.name) &&
        flattened.forall(parameter =>
          parameter.info.dealias =:= expected && parameter.is(HasDefault) &&
            !parameter.isOneOf(Given | Implicit | Erased)
        )
      if !shapeMatches then
        scala.util.Failure[Nothing](new IllegalArgumentException("signature mismatch")).get
      val schema = Schema(parameters)
      schemas(cls) = schema
      Some(schema)
    catch
      case _: IllegalArgumentException =>
        fail("ABI", "malformed constructor metadata or signature mismatch", tree)
        None

  private def guarded(site: String, body: Tree)(using Context): Tree =
    val thunk = Lambda(MethodType(Nil)(_ => Nil, _ => body.tpe), _ => body)
    ref(requiredMethod("nodal.ConstructorCaptureRuntime.guard"))
      .appliedToType(body.tpe)
      .appliedToArgs(List(string(site), thunk))

  override def transformApply(tree: Apply)(using Context): Tree = tree.fun match
    case selection: Select
        if selection.qualifier.isInstanceOf[New] && selection.symbol.isConstructor =>
      val cls = selection.symbol.owner
      if cls == moduleClass || !cls.derivesFrom(moduleClass) then tree
      else if selection.symbol != cls.primaryConstructor then
        if requiresCapture(cls) then
          fail("SHAPE", "secondary constructor allocation is unsupported", tree)
        tree
      else
        importedSchema(cls, tree) match
          case Some(schema) if schema.parameters.size == tree.args.size =>
            val site = ctx.source.file.path + ":" + tree.span.start
            val arguments = tree.args.map: argument =>
              SyntheticValDef(UniqueName.fresh(termName("constructorActual")), argument)
            val carriers = schema.parameters.zip(arguments).map: (parameter, actual) =>
              SyntheticValDef(
                UniqueName.fresh(termName("constructorCarrier")),
                ref(requiredMethod("nodal.ConstructorCaptureRuntime.carrierReal"))
                  .appliedToArgs(List(
                    string(parameter.name),
                    number(parameter.default),
                    ref(actual.symbol)
                  ))
              )
            val allocationBody = Block(
              carriers,
              cpy.Apply(tree)(tree.fun, carriers.map(value => ref(value.symbol)))
            )
            val allocationThunk =
              Lambda(MethodType(Nil)(_ => Nil, _ => tree.tpe), _ => allocationBody)
            val allocation = ref(requiredMethod("nodal.ConstructorCaptureRuntime.allocate"))
              .appliedToType(tree.tpe)
              .appliedToArgs(List(
                string(cls.fullName.toString),
                string(site),
                string(schema.encoded),
                allocationThunk
              ))
            guarded(site, Block(arguments, allocation)).withSpan(tree.span)
          case Some(_) =>
            fail("ARITY", "typed constructor application does not match metadata", tree)
            tree
          case None => tree
    case TypeApply(selection: Select, _)
        if selection.qualifier.isInstanceOf[New] && selection.symbol.isConstructor &&
          selection.symbol.owner.derivesFrom(moduleClass) &&
          requiresCapture(selection.symbol.owner) =>
      fail("SHAPE", "type-applied captured Module construction is unsupported", tree)
      tree
    case _ => tree

  private def guardCall(tree: Tree)(using Context): Boolean = tree match
    case Apply(TypeApply(function, _), _) =>
      function.symbol == requiredMethod("nodal.ConstructorCaptureRuntime.guard")
    case Apply(function, _) =>
      function.symbol == requiredMethod("nodal.ConstructorCaptureRuntime.guard")
    case _ => false

  /** Typer may place named-argument temporaries in an enclosing Block. Move one additional guard
    * around that block so failures in those original temporaries poison the same transaction.
    */
  override def transformBlock(tree: Block)(using Context): Tree =
    if tree.stats.nonEmpty && guardCall(tree.expr) then
      guarded(ctx.source.file.path + ":" + tree.span.start, tree).withSpan(tree.span)
    else tree
