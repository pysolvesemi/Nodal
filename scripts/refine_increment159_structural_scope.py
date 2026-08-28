#!/usr/bin/env python3

from pathlib import Path

path = Path("docs/roadmap/nodal-development-todo.md")
text = path.read_text(encoding="utf-8")
old = "  - Select loop kind before validating the body. `genRange` may create structural declarations, modules, ports, instances, connections, and nested legal generation. `hwRange` may perform repeated operations inside the enclosing combinational or sequential semantic region but may not create modules, ports, instances, or new structural hardware objects. A Scala local `val` remains a binder or alias unless it explicitly constructs hardware; module instances, local variables, or `Reg`/`Wire` presence never choose the loop category.\n"
new = "  - Select loop kind before validating the body. `genRange` may create local structural declarations, component instances, connections, generated process regions, and nested legal generation, but it may not mutate the enclosing module's frozen boundary ports. `hwRange` may perform repeated operations inside the enclosing combinational or sequential semantic region but may not create ports, component instances, or new structural hardware objects. A Scala local `val` remains a binder or alias unless it explicitly constructs hardware; component instances, local variables, or `Reg`/`Wire` presence never choose the loop category.\n"
if text.count(old) != 1:
    raise SystemExit(f"expected one structural-scope anchor, found {text.count(old)}")
path.write_text(text.replace(old, new, 1), encoding="utf-8")
