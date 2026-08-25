package nodal.internal.bridge

private[nodal] object NativeDiagnosticMapper:
  private val StableCode = raw"(NODAL-[A-Z0-9-]+):\s*([^\n]*)".r
  private val SemanticPath = raw"\[semantic-path=([^\]]+)\]".r
  private val HierarchyPath = raw"\[hierarchy-path=([^\]]+)\]".r
  private val IndexPath = raw"\[index-path=([^\]]+)\]".r
  private val SourceRange = raw"\[source-range=([^\]]+)\]".r

  def classify(standardError: String, exitCode: Int): BridgeDiagnostic =
    val normalized = standardError.replace("\r\n", "\n").replace('\r', '\n')
    val stable = StableCode.findFirstMatchIn(normalized)
    val code = stable.map(_.group(1)).getOrElse(fallbackCode(normalized))
    val message = stable
      .map(_.group(2).takeWhile(_ != '[').trim)
      .filter(_.nonEmpty)
      .getOrElse(fallbackMessage(normalized, exitCode))
    BridgeDiagnostic(
      code = code,
      message = message,
      semanticPath = capture(SemanticPath, normalized),
      hierarchyPath = capture(HierarchyPath, normalized),
      indexPath = capture(IndexPath, normalized),
      sourceRange = capture(SourceRange, normalized)
    )

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
      .getOrElse(s"native compiler exited with status $exitCode")
