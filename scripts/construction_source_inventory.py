"""Current production sources for the construction frontend's logical unit.

Increment 160 split the former single compilation unit by responsibility.  The
source-review predicates still inspect its complete implementation: this tuple
is explicit and ordered, and every listed file is read.  It is neither a source
snapshot nor an acceptance/progress record.
"""

from __future__ import annotations

from collections.abc import Callable
from pathlib import Path
import re


CONSTRUCTION_SOURCES = (
    "core/scala/api/src/nodal/ElaborationConstructionKernel.scala",
    "core/scala/api/src/nodal/ConstructionRecords.scala",
    "core/scala/api/src/nodal/ConstructionSession.scala",
    "core/scala/api/src/nodal/ConstructionExpressionFacts.scala",
    "core/scala/api/src/nodal/ConstructionInterfaceLayout.scala",
)
CONSTRUCTION_FACADE = CONSTRUCTION_SOURCES[0]


def read_construction_sources(
    root: Path, reader: Callable[[str], str] | None = None
) -> str:
    """Read every current part, preserving the caller's missing-file diagnostics.

    The optional reader only adapts the existing checker's diagnostic interface;
    it does not select or omit paths.  Concatenation changes no source bytes and
    is used only by lexical repository checks, never by Scala compilation.
    """
    if reader is None:
        reader = lambda relative: (root / relative).read_text(encoding="utf-8")
    return "\n".join(reader(relative) for relative in CONSTRUCTION_SOURCES)


def missing_construction_source_exclusions(semantic_source: str) -> tuple[str, ...]:
    """Require precise internal-frame exclusions for every extracted source."""
    match = re.search(
        r"\bprivate val internalSourceFiles\s*=\s*Set\(([^)]*)\)", semantic_source
    )
    excluded = set(re.findall(r'"([^"\n]+)"', match.group(1))) if match else set()
    return tuple(
        Path(relative).name
        for relative in CONSTRUCTION_SOURCES
        if Path(relative).name not in excluded
    )
