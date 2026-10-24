module {
  "nodal.module"() <{metadata = {}, sym_name = "Child"}> ({
    "nodal.parameter"() <{classification = "ordinary", default_value = 3 : i64, metadata = {}, sym_name = "N", type = i64, variability = "symbolic"}> : () -> ()
    %lo = "nodal.const_literal"() <{metadata = {}, spelling = "-4", value = -4 : i64}> : () -> i64
    %hi = "nodal.const_literal"() <{metadata = {}, spelling = "8", value = 8 : i64}> : () -> i64
    "nodal.parameter_constraint"(%lo, %hi) <{constraint_kind = "range", lower_inclusive = true, upper_inclusive = true, metadata = {}, parameter = @N}> : (i64, i64) -> ()
  }) : () -> ()
  "nodal.module"() <{metadata = {}, sym_name = "Parent"}> ({
    "nodal.parameter"() <{classification = "ordinary", default_value = 3 : i64, metadata = {}, sym_name = "P", type = i64, variability = "symbolic"}> : () -> ()
    %plo = "nodal.const_literal"() <{metadata = {}, spelling = "-2", value = -2 : i64}> : () -> i64
    %phi = "nodal.const_literal"() <{metadata = {}, spelling = "8", value = 8 : i64}> : () -> i64
    "nodal.parameter_constraint"(%plo, %phi) <{constraint_kind = "range", lower_inclusive = true, upper_inclusive = true, metadata = {}, parameter = @P}> : (i64, i64) -> ()
    %p = "nodal.const_parameter_ref"() <{metadata = {}, parameter = @P}> : () -> i64
    %zero = "nodal.const_literal"() <{metadata = {}, spelling = "0", value = 0 : i64}> : () -> i64
    %two = "nodal.const_literal"() <{metadata = {}, spelling = "2", value = 2 : i64}> : () -> i64
    %one = "nodal.const_literal"() <{metadata = {}, spelling = "1", value = 1 : i64}> : () -> i64
    %family = "nodal.generate"(%zero, %two, %one) <{induction = "i", region_id = "Parent.lanes", induction_path = "Parent.lanes.i", metadata = {maximum_trip_count = 2 : i64}}> ({
    ^bb0(%i: i64):
      %actual = "nodal.const_expr"(%i, %p) <{metadata = {}, operator_name = "add"}> : (i64, i64) -> i64
      "nodal.instance"() <{domain_bindings = {}, generated_owner = "Parent.lanes", generated_induction = "Parent.lanes.i", metadata = {}, module = @Child, parameter_bindings = {}, sym_name = "u"}> : () -> ()
      "nodal.parameter_override"(%actual) <{generated_owner = "Parent.lanes", generated_induction = "Parent.lanes.i", instance = @u, parameter = @N, metadata = {}}> : (i64) -> ()
    }) : (i64, i64, i64) -> !nodal.generated_family
  }) : () -> ()
}
