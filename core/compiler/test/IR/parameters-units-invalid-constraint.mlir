module {
  "nodal.module"() <{metadata = {}, sym_name = "Fixture"}> ({
  ^bb0:
    "nodal.parameter"() <{classification = "ordinary", default_value = 20 : i64, metadata = {}, parameter_kind = "integer", sym_name = "COUNT", type = i64, variability = "symbolic"}> : () -> ()
    %value = "nodal.const_literal"() <{metadata = {}, spelling = "20", value = 20 : i64}> : () -> i64
    "nodal.parameter_value"(%value) <{metadata = {}, parameter = @COUNT}> : (i64) -> ()
    %low = "nodal.const_literal"() <{metadata = {}, spelling = "1", value = 1 : i64}> : () -> i64
    %high = "nodal.const_literal"() <{metadata = {}, spelling = "16", value = 16 : i64}> : () -> i64
    "nodal.parameter_constraint"(%low, %high) <{constraint_kind = "range", lower_inclusive = true, metadata = {}, parameter = @COUNT, upper_inclusive = true}> : (i64, i64) -> ()
  }) : () -> ()
}
