from pathlib import Path

path = Path("/tmp/inc33-apply.py")
text = path.read_text()
old = '''kernel = insert_before(
    kernel,
    \'\'\'  def registerDomain(domain: ClockDomain, kind: KernelDomainKind): Unit =
\'\'\',
    \'\'\'  def currentModulePath: String = active
    .map(_.currentModulePath)
    .getOrElse(
      throw new IllegalStateException(
        "procedural module construction has no active transaction"
      )
    )

  def captureAnalogProceduralSource: Option[AnalogProceduralRuntime.Source] =
    active.flatMap(_.captureAnalogProceduralSource)

\'\'\',
    "ConstructionKernel procedural wrappers",
)
'''
new = '''kernel = replace_once(
    kernel,
    \'\'\'  def beginModule(module: Module): Unit = active.foreach(_.beginModule(module))

  def registerDomain(domain: ClockDomain, kind: KernelDomainKind): Unit =
\'\'\',
    \'\'\'  def beginModule(module: Module): Unit = active.foreach(_.beginModule(module))

  def currentModulePath: String = active
    .map(_.currentModulePath)
    .getOrElse(
      throw new IllegalStateException(
        "procedural module construction has no active transaction"
      )
    )

  def captureAnalogProceduralSource: Option[AnalogProceduralRuntime.Source] =
    active.flatMap(_.captureAnalogProceduralSource)

  def registerDomain(domain: ClockDomain, kind: KernelDomainKind): Unit =
\'\'\',
    "ConstructionKernel procedural wrappers",
)
'''
if text.count(old) != 1:
    raise SystemExit(f"repair-01 expected one script block, found {text.count(old)}")
path.write_text(text.replace(old, new))
