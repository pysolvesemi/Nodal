module attributes {
  nodal.backend.check_profile = "default",
  nodal.backend.materialization = "safe-inline",
  nodal.backend.naming = "semantic",
  nodal.backend.profile = "verilog-a",
  nodal.backend.shaped_layout = "scalar-or-flat",
  nodal.target.profile = "analog",
  nodal.verify.analog_topology = true,
  nodal.verify.assignment_coverage = true,
  nodal.verify.cdc_rdc_safe = true,
  nodal.verify.clock_reset_domains = true,
  nodal.verify.combinational_acyclic = true,
  nodal.verify.construction_closed = true,
  nodal.verify.driver_coverage = true,
  nodal.verify.enum_fsm = true,
  nodal.verify.hierarchy_closed = true,
  nodal.verify.latch_free = true,
  nodal.verify.layout_storage = true,
  nodal.verify.memory_effects = true,
  nodal.verify.mixed_signal_bridges = true,
  nodal.verify.parameters_complete = true,
  nodal.verify.protocol_pipeline = true,
  nodal.verify.target_capability = true,
  nodal.verify.width_sign_shape = true
} {
  "nodal.unit"() <{dimension = "resistance", metadata = {}, native_suffix = "", scale = 1.0 : f64, sym_name = "Ohm", symbol = "Ohm"}> : () -> ()
  "nodal.unit"() <{dimension = "resistance", metadata = {}, native_suffix = "k", scale = 1.0e3 : f64, sym_name = "kOhm", symbol = "kOhm"}> : () -> ()
  "nodal.unit"() <{dimension = "resistance", metadata = {}, native_suffix = "k", scale = 1.0e3 : f64, sym_name = "kOhmPretty", symbol = "kΩ/V"}> : () -> ()
  "nodal.module"() <{metadata = {root = true}, sym_name = "ParameterRendering"}> ({
  ^bb0:
    "nodal.parameter"() <{classification = "ordinary", default_value = 1.0 : f64, metadata = {}, parameter_kind = "real", sym_name = "R_BARE", type = f64, unit = @kOhmPretty, variability = "symbolic"}> : () -> ()
    %r_bare = "nodal.const_literal"() <{metadata = {}, spelling = "1", value = 1.0 : f64}> : () -> f64
    "nodal.parameter_value"(%r_bare) <{metadata = {}, parameter = @R_BARE}> : (f64) -> ()
    %r_bare_low = "nodal.const_literal"() <{metadata = {}, spelling = "1", value = 1.0 : f64}> : () -> f64
    %r_bare_high = "nodal.const_literal"() <{metadata = {}, spelling = "2", value = 2.0 : f64}> : () -> f64
    "nodal.parameter_constraint"(%r_bare_low, %r_bare_high) <{constraint_kind = "range", lower_inclusive = true, metadata = {}, parameter = @R_BARE, upper_inclusive = true}> : (f64, f64) -> ()
    "nodal.parameter"() <{classification = "ordinary", default_value = 340282366920938463463374607431768211455 : i129, metadata = {}, parameter_kind = "integer", sym_name = "WIDE", type = !nodal.uint<128>, variability = "fixed"}> : () -> ()
    "nodal.parameter"() <{classification = "ordinary", default_value = 2 : i64, metadata = {}, parameter_kind = "integer", sym_name = "A_DEP", type = i64, variability = "symbolic"}> : () -> ()
    %z_ref = "nodal.const_parameter_ref"() <{metadata = {}, parameter = @Z_BASE}> : () -> i64
    "nodal.parameter_value"(%z_ref) <{metadata = {}, parameter = @A_DEP}> : (i64) -> ()
    "nodal.parameter"() <{classification = "ordinary", default_value = 2 : i64, metadata = {}, parameter_kind = "integer", sym_name = "Z_BASE", type = i64, variability = "symbolic"}> : () -> ()
    %z_base = "nodal.const_literal"() <{metadata = {}, spelling = "2", value = 2 : i64}> : () -> i64
    "nodal.parameter_value"(%z_base) <{metadata = {}, parameter = @Z_BASE}> : (i64) -> ()
    "nodal.parameter"() <{classification = "ordinary", default_value = true, metadata = {}, parameter_kind = "boolean", sym_name = "ENABLE", type = i1, variability = "symbolic"}> : () -> ()
    %enable = "nodal.const_literal"() <{metadata = {}, spelling = "1", value = true}> : () -> i1
    "nodal.parameter_value"(%enable) <{metadata = {}, parameter = @ENABLE}> : (i1) -> ()

    "nodal.parameter"() <{classification = "ordinary", default_value = 8.0 : f64, metadata = {}, parameter_kind = "real", sym_name = "GAIN", type = f64, variability = "symbolic"}> : () -> ()
    %two = "nodal.const_literal"() <{metadata = {}, spelling = "2.0", value = 2.0 : f64}> : () -> f64
    %four = "nodal.const_literal"() <{metadata = {}, spelling = "4.0", value = 4.0 : f64}> : () -> f64
    %gain = "nodal.const_expr"(%two, %four) <{metadata = {}, operator_name = "mul"}> : (f64, f64) -> f64
    "nodal.parameter_value"(%gain) <{metadata = {}, parameter = @GAIN}> : (f64) -> ()

    "nodal.parameter"() <{classification = "ordinary", default_value = 1.0e3 : f64, metadata = {}, parameter_kind = "real", sym_name = "R", type = f64, unit = @Ohm, variability = "symbolic"}> : () -> ()
    %r = "nodal.const_literal"() <{metadata = {}, spelling = "1k", unit = @kOhm, value = 1.0 : f64}> : () -> f64
    "nodal.parameter_value"(%r) <{metadata = {}, parameter = @R}> : (f64) -> ()
    %r_low = "nodal.const_literal"() <{metadata = {}, spelling = "1", unit = @Ohm, value = 1.0 : f64}> : () -> f64
    %r_high = "nodal.const_literal"() <{metadata = {}, spelling = "10k", unit = @kOhm, value = 1.0e1 : f64}> : () -> f64
    "nodal.parameter_constraint"(%r_low, %r_high) <{constraint_kind = "range", lower_inclusive = true, metadata = {}, parameter = @R, upper_inclusive = true}> : (f64, f64) -> ()
    %r_exclude = "nodal.const_literal"() <{metadata = {}, spelling = "5k", unit = @kOhm, value = 5.0 : f64}> : () -> f64
    "nodal.parameter_constraint"(%r_exclude) <{constraint_kind = "exclude", lower_inclusive = true, metadata = {}, parameter = @R, upper_inclusive = true}> : (f64) -> ()

    "nodal.parameter"() <{classification = "structural", default_value = 4 : i64, metadata = {}, parameter_kind = "integer", sym_name = "TAPS", type = i64, variability = "symbolic"}> : () -> ()
    %taps = "nodal.const_literal"() <{metadata = {}, spelling = "4", value = 4 : i64}> : () -> i64
    "nodal.parameter_value"(%taps) <{metadata = {}, parameter = @TAPS}> : (i64) -> ()
    %taps_low = "nodal.const_literal"() <{metadata = {}, spelling = "1", value = 1 : i64}> : () -> i64
    %taps_high = "nodal.const_literal"() <{metadata = {}, spelling = "16", value = 16 : i64}> : () -> i64
    "nodal.parameter_constraint"(%taps_low, %taps_high) <{constraint_kind = "range", lower_inclusive = true, metadata = {}, parameter = @TAPS, upper_inclusive = true}> : (i64, i64) -> ()
    "nodal.parameter_envelope"() <{effects = ["topology"], metadata = {}, parameter = @TAPS, policy = "static_generate"}> : () -> ()
  }) : () -> ()
}
