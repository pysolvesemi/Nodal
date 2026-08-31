# Increment 33 procedural-assignment fixtures

This directory contains the native witness and machine-readable implementation
manifest for component-local analog variables and ordered procedural `:=`
assignment.

The witness proves:

- declaration and assignment order are retained exactly;
- repeated writes remain separate source statements;
- initialized variables may be read;
- read-before-write fails for an uninitialized variable;
- nested lexical variables cannot escape their scope;
- cross-component access fails;
- assigned physical dimensions must match;
- guards are Boolean and dimensionless;
- declarations and assignments require an active procedural region.

The fixture intentionally does not execute a solver or emit Verilog-A. Those
behaviors remain deferred to their owning increments.
