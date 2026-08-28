module {
  "nodal.module"() <{metadata = {}, sym_name = "Child"}> ({
  ^bb0:
    "nodal.parameter"() <{classification = "ordinary", default_value = 4 : i64, metadata = {}, parameter_kind = "integer", sym_name = "COUNT", type = i64, variability = "symbolic"}> : () -> ()
    %value = "nodal.const_literal"() <{metadata = {}, spelling = "4", value = 4 : i64}> : () -> i64
    "nodal.parameter_value"(%value) <{metadata = {}, parameter = @COUNT}> : (i64) -> ()
    %low = "nodal.const_literal"() <{metadata = {}, spelling = "1", value = 1 : i64}> : () -> i64
    %high = "nodal.const_literal"() <{metadata = {}, spelling = "8", value = 8 : i64}> : () -> i64
    "nodal.parameter_constraint"(%low, %high) <{constraint_kind = "range", lower_inclusive = true, metadata = {}, parameter = @COUNT, upper_inclusive = true}> : (i64, i64) -> ()
  }) : () -> ()
  "nodal.module"() <{metadata = {}, sym_name = "Top"}> ({
  ^bb0:
    "nodal.instance"() <{domain_bindings = {}, metadata = {}, module = @Child, parameter_bindings = {}, sym_name = "child"}> : () -> ()
    %bad = "nodal.const_literal"() <{metadata = {}, spelling = "12", value = 12 : i64}> : () -> i64
    "nodal.parameter_override"(%bad) <{instance = @child, metadata = {}, parameter = @COUNT}> : (i64) -> ()
  }) : () -> ()
}
