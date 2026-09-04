module attributes { nodal.target.profile = "analog" } {
  "nodal.module"() <{metadata = {}, sym_name = "InvalidState"}> ({
  ^bb0:
    "nodal.analog"() <{metadata = {}}> ({
    ^bb0:
      %input = "nodal.real_literal"() <{metadata = {}, value = 1.0 : f64}> : () -> !nodal.quantity<"real", "1">
      %bad = "nodal.analog_idt"(%input) <{analyses = ["transient"], context = "equation", initialization = "solver-selected", input_dimension = "1", metadata = {}, operator_contract = "increment35", operator_id = "InvalidState.bad", owner = "InvalidState", result_dimension = "time", state_id = "InvalidState.some_other_state"}> : (!nodal.quantity<"real", "1">) -> !nodal.quantity<"real", "time">
    }) : () -> ()
  }) : () -> ()
}
