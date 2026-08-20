# Architecture boundary tests

These tests validate the machine-readable module graph and protect the two irreversible dependency boundaries established by the accepted ADRs:

```text
core.compiler  -X->  core.scala.frontend
core           -X->  libraries
```

Run locally with only Python 3.11 or newer:

```bash
python3 scripts/check_architecture.py
python3 -m unittest discover -s tests/architecture -p 'test_*.py'
```

The tests intentionally inject invalid dependencies, source references, cycles, and placeholder library directories to prove that the checker rejects them.
