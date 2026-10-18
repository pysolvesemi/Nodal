package nodal.internal.bridge

private[nodal] object NativeDiagnosticMapper:
  private val DiagnosticSeverity = raw"(?:^\s*|:\s+)(error|warning|note|remark):\s*".r
  private val StableCode = raw"(NODAL-[A-Z0-9-]+):\s*([^\n]*)".r
  private val SemanticPath = raw"\[semantic-path=([^\]]+)\]".r
  private val HierarchyPath = raw"\[hierarchy-path=([^\]]+)\]".r
  private val BracketedIndexPath = raw"\[index-path=(\[[^\]]*\])\]".r
  private val PlainIndexPath = raw"\[index-path=([^\]]+)\]".r
  private val SourceRange = raw"\[source-range=([^\]]+)\]".r
  private val StagedInputPath =
    raw"""(?:[A-Za-z]:)?(?:[^\s:]*[/\\])*input\.mlir""".r

  def classify(standardError: String, exitCode: Int): BridgeDiagnostic =
    val normalized = standardError.replace("\r\n", "\n").replace('\r', '\n')
    val selected = primaryDiagnostic(normalized)
    val stable = StableCode.findFirstMatchIn(selected)
    val code = stable.map(_.group(1)).getOrElse(fallbackCode(selected))
    val message = stable
      .map(_.group(2).takeWhile(_ != '[').trim)
      .filter(_.nonEmpty)
      .getOrElse(fallbackMessage(selected, exitCode))
    BridgeDiagnostic(
      code = code,
      message = message,
      semanticPath = capture(SemanticPath, selected),
      hierarchyPath = capture(HierarchyPath, selected),
      indexPath = capture(BracketedIndexPath, selected)
        .orElse(capture(PlainIndexPath, selected)),
      sourceRange = capture(SourceRange, selected)
    )

  private def primaryDiagnostic(text: String): String =
    // Native context is appended to its diagnostic line. Never borrow a code or
    // context from a warning, note, or later error; retain legacy unprefixed output.
    text.linesIterator
      .find(line => capture(DiagnosticSeverity, line).contains("error"))
      .getOrElse:
        if text.linesIterator.exists(line => DiagnosticSeverity.findFirstIn(line).nonEmpty)
        then ""
        else
          text.linesIterator
            .find(line => StableCode.findFirstIn(line).nonEmpty)
            .orElse(text.linesIterator.find(_.trim.nonEmpty))
            .getOrElse("")

  private def capture(pattern: scala.util.matching.Regex, text: String): Option[String] =
    pattern.findFirstMatchIn(text).map(_.group(1)).filter(_.nonEmpty)

  private def fallbackCode(text: String): String =
    val lower = text.toLowerCase(java.util.Locale.ROOT)
    if lower.contains("failed to parse") ||
      lower.contains("expected operation") ||
      lower.contains("expected attribute") ||
      lower.contains("unexpected token")
    then "NODAL-DIAGNOSTIC-PARSER-001"
    else if lower.contains("verification failed") ||
      lower.contains("failed to verify") ||
      lower.contains("op verification")
    then "NODAL-DIAGNOSTIC-VERIFIER-001"
    else if lower.contains("failed to run pass") ||
      lower.contains("failed to apply") ||
      lower.contains("pass pipeline")
    then "NODAL-DIAGNOSTIC-PASS-001"
    else if lower.contains("backend") || lower.contains("translation")
    then "NODAL-DIAGNOSTIC-BACKEND-001"
    else "NODAL-DIAGNOSTIC-EXTERNAL-001"

  private def fallbackMessage(text: String, exitCode: Int): String =
    text.linesIterator
      .map(_.trim)
      .find(_.nonEmpty)
      .map(line => StagedInputPath.replaceAllIn(line, "<bridge-input>"))
      .getOrElse(s"native compiler exited with status $exitCode")
