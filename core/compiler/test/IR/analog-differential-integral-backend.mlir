module attributes {
  nodal.backend.check_profile = "release",
  nodal.backend.materialization = "safe-inline",
  nodal.backend.naming = "semantic",
  nodal.backend.shaped_layout = "scalar-or-flat",
  nodal.target.profile = "analog"
} {
  "nodal.module"() <{metadata = {}, sym_name = "DifferentialIntegralBackend"}> ({
  ^bb0:
    %p = "nodal.terminal"() <{metadata = {declaration_kind = "analog-input"}, name = "p"}> : () -> !nodal.terminal<"electrical">
    %n = "nodal.terminal"() <{metadata = {declaration_kind = "analog-inout"}, name = "n"}> : () -> !nodal.terminal<"electrical">
    %branch = "nodal.branch"(%p, %n) <{metadata = {identity = "p_n"}}> : (!nodal.terminal<"electrical">, !nodal.terminal<"electrical">) -> !nodal.branch<"electrical">
    "nodal.analog"() <{metadata = {increment = 35 : i64}}> ({
    ^bb0:
      %one = "nodal.real_literal"() <{metadata = {}, value = 1.0 : f64}> : () -> !nodal.quantity<"real", "1">
      %zero = "nodal.real_literal"() <{metadata = {}, value = 0.0 : f64}> : () -> !nodal.quantity<"real", "1">
      %integral = "nodal.analog_idt"(%one, %zero) <{analyses = ["transient"], context = "contribution", initial_dimension = "time", initialization = "fixed", input_dimension = "1", metadata = {identity = "integral"}, operator_contract = "increment35", operator_id = "DifferentialIntegralBackend.integral", owner = "DifferentialIntegralBackend", result_dimension = "time", state_id = "DifferentialIntegralBackend.integral.state"}> : (!nodal.quantity<"real", "1">, !nodal.quantity<"real", "1">) -> !nodal.quantity<"real", "time">
      "nodal.contribute"(%branch, %integral) <{kind = "flow", metadata = {}}> : (!nodal.branch<"electrical">, !nodal.quantity<"real", "time">) -> ()
      %charge = "nodal.real_literal"() <{metadata = {}, value = 2.0 : f64}> : () -> !nodal.quantity<"real", "current*time">
      %derivative = "nodal.analog_ddt"(%charge) <{analyses = ["transient"], context = "contribution", initialization = "none", input_dimension = "current*time", metadata = {identity = "derivative"}, operator_contract = "increment35", operator_id = "DifferentialIntegralBackend.derivative", owner = "DifferentialIntegralBackend", result_dimension = "current"}> : (!nodal.quantity<"real", "current*time">) -> !nodal.quantity<"real", "current">
      "nodal.contribute"(%branch, %derivative) <{kind = "flow", metadata = {}}> : (!nodal.branch<"electrical">, !nodal.quantity<"real", "current">) -> ()
    }) : () -> ()
  }) : () -> ()
}
