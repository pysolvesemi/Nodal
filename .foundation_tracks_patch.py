from pathlib import Path

path = Path(".foundation_tracks_apply.py")
text = path.read_text(encoding="utf-8")
old = 'ref_anchor = "- Accellera Verilog-AMS standards: <https://www.accellera.org/downloads/standards/v-ams>\\n"'
new = 'ref_anchor = "- Verilog-AMS standards: <https://accellera.org/downloads/standards/v-ams>\\n"'
if text.count(old) != 1:
    raise SystemExit("expected obsolete verification-reference anchor exactly once")
path.write_text(text.replace(old, new, 1), encoding="utf-8")
