module attributes { nodal.target.profile = "analog" } {
  "nodal.module"() <{metadata = {}, sym_name = "InvalidSimplification"}> ({
  ^bb0:
    %p = "nodal.terminal"() <{metadata = {}, name = "p"}> : () -> !nodal.terminal<"electrical">
    %n = "nodal.terminal"() <{metadata = {}, name = "n"}> : () -> !nodal.terminal<"electrical">
    %branch = "nodal.branch"(%p, %n) <{metadata = {}}> : (!nodal.terminal<"electrical">, !nodal.terminal<"electrical">) -> !nodal.branch<"electrical">
    "nodal.analog"() <{metadata = {}}> ({
    ^bb0:
      %voltage = "nodal.access"(%branch) <{kind = "potential", metadata = {}}> : (!nodal.branch<"electrical">) -> !nodal.quantity<"real", "voltage">
      %bad = "nodal.analog_ddt"(%voltage) <{analyses = ["transient"], context = "equation", initialization = "none", input_dimension = "voltage", metadata = {}, operator_contract = "increment35", operator_id = "InvalidSimplification.bad", owner = "InvalidSimplification", result_dimension = "time^-1*voltage"}> {nodal.simplification_provenance = "increment35", nodal.simplification_rule = "ddt-time-invariant-zero", nodal.simplified = true, nodal.simplified_dimension = "time^-1*voltage", nodal.simplified_value = 0.0 : f64} : (!nodal.quantity<"real", "voltage">) -> !nodal.quantity<"real", "time^-1*voltage">
    }) : () -> ()
  }) : () -> ()
}
