from pathlib import Path

path = Path("core/scala/api/src/nodal/CandidateApi.scala")
content = path.read_text(encoding="utf-8")

package_line = "package nodal\n"
import_line = "package nodal\n\nimport scala.annotation.targetName\n"
if "import scala.annotation.targetName" not in content:
    if package_line not in content:
        raise SystemExit("CandidateApi.scala package declaration was not found")
    content = content.replace(package_line, import_line, 1)

needle = "extension (left: Expr[UInt])\n  def +(right: Expr[UInt]): Expr[UInt] ="
replacement = (
    "extension (left: Expr[UInt])\n"
    "  @targetName(\"uintAddition\")\n"
    "  def +(right: Expr[UInt]): Expr[UInt] ="
)
if needle in content:
    content = content.replace(needle, replacement, 1)
elif '@targetName("uintAddition")' not in content:
    raise SystemExit("UInt addition extension was not found")

path.write_text(content, encoding="utf-8")
