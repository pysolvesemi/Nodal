# CI baseline tests

These tests cover the generic Core CI contract introduced in Increment 8.

- `test_ci_baseline.py` protects workflow triggers, required-job aggregation,
  cache allowlists, report-only permissions, branch policy, and ownership.
- `test_formatting_baseline.py` protects the dependency-free text-formatting
  checks used before language-specific formatters are introduced.
- `test_dependency_report.py` protects stable-version filtering, candidate-only
  reporting, failure transparency, and offline evidence generation.

Run them through the unified command:

```bash
./nodal check --contracts-only
```

or directly:

```bash
python3 -m unittest discover -s tests/ci -p 'test_*.py'
```
