package nodal.prototype

/** Metadata transported through TASTy by the prototype compiler plugin. */
final class ConstructorSchema(val version: Int, val encoded: String)
    extends scala.annotation.StaticAnnotation

/** The sole lifecycle hook exercised by this isolated experiment. */
abstract class Module:
  final val captureIdentity: ModuleIdentity = Capture.begin(this)

