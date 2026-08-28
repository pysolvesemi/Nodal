module {
  "nodal.unit"() <{dimension = "resistance", metadata = {}, native_suffix = "", scale = 1.0 : f64, sym_name = "Ohm", symbol = "Ohm"}> : () -> ()
  "nodal.unit"() <{dimension = "resistance", metadata = {}, native_suffix = "k", scale = 1.0e3 : f64, sym_name = "kOhm", symbol = "kOhm"}> : () -> ()
  "nodal.module"() <{metadata = {}, sym_name = "ParameterizedCell"}> ({
  ^bb0:
    "nodal.parameter"() <{classification = "ordinary", default_value = 1.0e3 : f64, metadata = {}, parameter_kind = "real", sym_name = "R", type = f64, unit = @Ohm, variability = "symbolic"}> : () -> ()
    %r_default = "nodal.const_literal"() <{metadata = {}, spelling = "1k", unit = @kOhm, value = 1.0 : f64}> : () -> f64
    "nodal.parameter_value"(%r_default) <{metadata = {}, parameter = @R}> : (f64) -> ()
    %r_lower = "nodal.const_literal"() <{metadata = {}, spelling = "1", unit = @Ohm, value = 1.0 : f64}> : () -> f64
    %r_upper = "nodal.const_literal"() <{metadata = {}, spelling = "10k", unit = @kOhm, value = 1.0e1 : f64}> : () -> f64
    "nodal.parameter_constraint"(%r_lower, %r_upper) <{constraint_kind = "range", lower_inclusive = true, metadata = {}, parameter = @R, upper_inclusive = true}> : (f64, f64) -> ()
    %r_excluded = "nodal.const_literal"() <{metadata = {}, spelling = "5k", unit = @kOhm, value = 5.0 : f64}> : () -> f64
    "nodal.parameter_constraint"(%r_excluded) <{constraint_kind = "exclude", lower_inclusive = true, metadata = {}, parameter = @R, upper_inclusive = true}> : (f64) -> ()

    "nodal.parameter"() <{classification = "structural", default_value = 4 : i64, metadata = {}, parameter_kind = "integer", sym_name = "TAPS", type = i64, variability = "symbolic"}> : () -> ()
    %taps_default = "nodal.const_literal"() <{metadata = {}, spelling = "4", value = 4 : i64}> : () -> i64
    "nodal.parameter_value"(%taps_default) <{metadata = {}, parameter = @TAPS}> : (i64) -> ()
    %taps_lower = "nodal.const_literal"() <{metadata = {}, spelling = "1", value = 1 : i64}> : () -> i64
    %taps_upper = "nodal.const_literal"() <{metadata = {}, spelling = "16", value = 16 : i64}> : () -> i64
    "nodal.parameter_constraint"(%taps_lower, %taps_upper) <{constraint_kind = "range", lower_inclusive = true, metadata = {}, parameter = @TAPS, upper_inclusive = true}> : (i64, i64) -> ()
    "nodal.parameter_envelope"() <{effects = ["topology", "shape"], metadata = {}, parameter = @TAPS, policy = "static_generate"}> : () -> ()

    "nodal.parameter"() <{classification = "ordinary", default_value = true, metadata = {}, parameter_kind = "boolean", sym_name = "ENABLE", type = i1, variability = "symbolic"}> : () -> ()
    %enable_default = "nodal.const_literal"() <{metadata = {}, spelling = "1", value = true}> : () -> i1
    "nodal.parameter_value"(%enable_default) <{metadata = {}, parameter = @ENABLE}> : (i1) -> ()

    "nodal.parameter"() <{classification = "ordinary", default_value = 8.0 : f64, metadata = {}, parameter_kind = "real", sym_name = "GAIN", type = f64, variability = "symbolic"}> : () -> ()
    %two = "nodal.const_literal"() <{metadata = {}, spelling = "2.0", value = 2.0 : f64}> : () -> f64
    %four = "nodal.const_literal"() <{metadata = {}, spelling = "4.0", value = 4.0 : f64}> : () -> f64
    %gain_default = "nodal.const_expr"(%two, %four) <{metadata = {}, operator_name = "mul"}> : (f64, f64) -> f64
    "nodal.parameter_value"(%gain_default) <{metadata = {}, parameter = @GAIN}> : (f64) -> ()

    %zero = "nodal.constant"() <{metadata = {}, value = 0 : i64}> : () -> i64
    %runtime = "nodal.dynamic_value"(%zero) <{metadata = {}, origin = "ParameterizedCell.runtime"}> : (i64) -> i64
  }) : () -> ()

  "nodal.module"() <{metadata = {}, sym_name = "ParameterizedTop"}> ({
  ^bb0:
    "nodal.instance"() <{domain_bindings = {}, metadata = {}, module = @ParameterizedCell, parameter_bindings = {R = 2.0e3 : f64}, sym_name = "cell"}> : () -> ()
    %override = "nodal.const_literal"() <{metadata = {}, spelling = "2k", unit = @kOhm, value = 2.0 : f64}> : () -> f64
    "nodal.parameter_override"(%override) <{instance = @cell, metadata = {}, parameter = @R}> : (f64) -> ()
  }) : () -> ()
}
