module attributes {nodal.target.profile = "analog"} {
  "nodal.module"() <{metadata = {}, sym_name = "InvalidContinuousDimension"}> ({
  ^bb0:
    "nodal.analog"() <{metadata = {}}> ({
    ^bb0:
      %input = "nodal.real_literal"() <{metadata = {}, value = 1.0 : f64}> : () -> f64
      %bad = "nodal.analog_ddt"(%input) <{analyses = ["transient"], context = "equation", initialization = "none", input_dimension = "current", metadata = {}, operator_contract = "increment35", operator_id = "InvalidContinuousDimension.bad", owner = "InvalidContinuousDimension", result_dimension = "voltage"}> : (f64) -> f64
    }) : () -> ()
  }) : () -> ()
}
