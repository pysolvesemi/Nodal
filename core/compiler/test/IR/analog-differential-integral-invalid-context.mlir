module attributes { nodal.target.profile = "analog" } {
  "nodal.module"() <{metadata = {}, sym_name = "InvalidContext"}> ({
  ^bb0:
    "nodal.analog"() <{metadata = {}}> ({
    ^bb0:
      %one = "nodal.real_literal"() <{metadata = {}, value = 1.0 : f64}> : () -> !nodal.quantity<"real", "1">
      %bad = "nodal.analog_ddt"(%one) <{analyses = ["transient"], context = "initial-equation", initialization = "none", input_dimension = "1", metadata = {}, operator_contract = "increment35", operator_id = "InvalidContext.bad", owner = "InvalidContext", result_dimension = "time^-1"}> : (!nodal.quantity<"real", "1">) -> !nodal.quantity<"real", "time^-1">
    }) : () -> ()
  }) : () -> ()
}
