module {
  "nodal.module"() <{metadata = {}, sym_name = "Child"}> ({
    "nodal.parameter"() <{classification = "ordinary", default_value = 2.0 : f64, metadata = {}, parameter_kind = "real", sym_name = "gain", type = f64, variability = "symbolic"}> : () -> ()
  }) : () -> ()
  "nodal.module"() <{metadata = {}, sym_name = "Parent"}> ({
    "nodal.parameter"() <{classification = "ordinary", default_value = 5.0 : f64, metadata = {}, parameter_kind = "real", sym_name = "P", type = f64, variability = "symbolic"}> : () -> ()
    %p = "nodal.const_parameter_ref"() <{metadata = {}, parameter = @P}> : () -> f64
    %runtime = "nodal.dynamic_value"(%p) <{metadata = {}, origin = "runtime"}> : (f64) -> f64
    %zero = "nodal.const_literal"() <{metadata = {}, spelling = "0", value = 0 : i64}> : () -> i64
    %two = "nodal.const_literal"() <{metadata = {}, spelling = "2", value = 2 : i64}> : () -> i64
    %one = "nodal.const_literal"() <{metadata = {}, spelling = "1", value = 1 : i64}> : () -> i64
    %family = "nodal.generate"(%zero, %two, %one) <{induction = "i", region_id = "Parent.lanes", induction_path = "Parent.lanes.i", metadata = {maximum_trip_count = 2 : i64}}> ({
    ^bb0(%i: i64):
      "nodal.instance"() <{domain_bindings = {}, generated_owner = "Parent.lanes", generated_induction = "Parent.lanes.i", metadata = {}, module = @Child, parameter_bindings = {}, sym_name = "u"}> : () -> ()
      "nodal.parameter_override"(%runtime) <{generated_owner = "Parent.lanes", generated_induction = "Parent.lanes.i", instance = @u, parameter = @gain, metadata = {}}> : (f64) -> ()
    }) : (i64, i64, i64) -> !nodal.generated_family
  }) : () -> ()
}
