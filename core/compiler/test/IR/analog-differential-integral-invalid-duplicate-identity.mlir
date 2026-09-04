module attributes {nodal.target.profile = "analog"} {
  "nodal.module"() <{metadata = {}, sym_name = "DuplicateContinuousIdentity"}> ({
  ^bb0:
    "nodal.analog"() <{metadata = {}}> ({
    ^bb0:
      %one = "nodal.real_literal"() <{metadata = {}, value = 1.0 : f64}> : () -> f64
      %two = "nodal.real_literal"() <{metadata = {}, value = 2.0 : f64}> : () -> f64
      %first = "nodal.analog_ddt"(%one) <{analyses = ["transient"], context = "equation", initialization = "none", input_dimension = "1", metadata = {}, operator_contract = "increment35", operator_id = "DuplicateContinuousIdentity.same", owner = "DuplicateContinuousIdentity", result_dimension = "time^-1"}> : (f64) -> f64
      %second = "nodal.analog_ddt"(%two) <{analyses = ["transient"], context = "equation", initialization = "none", input_dimension = "1", metadata = {}, operator_contract = "increment35", operator_id = "DuplicateContinuousIdentity.same", owner = "DuplicateContinuousIdentity", result_dimension = "time^-1"}> : (f64) -> f64
    }) : () -> ()
  }) : () -> ()
}
