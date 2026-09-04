module attributes {nodal.target.profile = "analog"} {
  "nodal.module"() <{metadata = {}, sym_name = "InvalidContinuousContract"}> ({
  ^bb0:
    "nodal.analog"() <{metadata = {}}> ({
    ^bb0:
      %input = "nodal.real_literal"() <{metadata = {}, value = 1.0 : f64}> : () -> f64
      %bad = "nodal.analog_idt"(%input) <{analyses = ["transient"], context = "equation", initialization = "solver-selected", input_dimension = "1", metadata = {}, operator_id = "InvalidContinuousContract.bad", owner = "InvalidContinuousContract", result_dimension = "time", state_id = "InvalidContinuousContract.bad.state"}> : (f64) -> f64
    }) : () -> ()
  }) : () -> ()
}
