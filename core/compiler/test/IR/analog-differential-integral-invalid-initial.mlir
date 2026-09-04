module attributes { nodal.target.profile = "analog" } {
  "nodal.module"() <{metadata = {}, sym_name = "InvalidInitial"}> ({
  ^bb0:
    "nodal.analog"() <{metadata = {}}> ({
    ^bb0:
      %input = "nodal.real_literal"() <{metadata = {}, value = 1.0 : f64}> : () -> !nodal.quantity<"real", "1">
      %initial = "nodal.real_literal"() <{metadata = {}, value = 2.0 : f64}> : () -> !nodal.quantity<"real", "1">
      %bad = "nodal.analog_idt"(%input, %initial) <{analyses = ["transient"], context = "equation", initial_dimension = "time", initialization = "fixed", input_dimension = "1", metadata = {}, operator_contract = "increment35", operator_id = "InvalidInitial.bad", owner = "InvalidInitial", result_dimension = "time", state_id = "InvalidInitial.bad.state"}> : (!nodal.quantity<"real", "1">, !nodal.quantity<"real", "1">) -> !nodal.quantity<"real", "time">
    }) : () -> ()
  }) : () -> ()
}
