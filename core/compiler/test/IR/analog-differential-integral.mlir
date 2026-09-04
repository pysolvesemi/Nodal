module attributes {
  nodal.target.profile = "analog"
} {
  "nodal.unit"() <{dimension = "voltage", metadata = {}, native_suffix = "", scale = 1.0 : f64, sym_name = "Volt", symbol = "V"}> : () -> ()
  "nodal.unit"() <{dimension = "current", metadata = {}, native_suffix = "", scale = 1.0 : f64, sym_name = "Amp", symbol = "A"}> : () -> ()
  "nodal.unit"() <{dimension = "current*time", metadata = {}, native_suffix = "", scale = 1.0 : f64, sym_name = "Coulomb", symbol = "C"}> : () -> ()
  "nodal.module"() <{metadata = {}, sym_name = "DifferentialIntegral"}> ({
  ^bb0:
    "nodal.parameter"() <{classification = "ordinary", default_value = 1.5 : f64, metadata = {}, parameter_kind = "real", sym_name = "V0", type = f64, unit = @Volt, variability = "fixed"}> : () -> ()
    "nodal.parameter"() <{classification = "ordinary", default_value = 0.25 : f64, metadata = {}, parameter_kind = "real", sym_name = "I0", type = f64, unit = @Amp, variability = "fixed"}> : () -> ()
    "nodal.parameter"() <{classification = "ordinary", default_value = 0.0 : f64, metadata = {}, parameter_kind = "real", sym_name = "Q0", type = f64, unit = @Coulomb, variability = "fixed"}> : () -> ()
    "nodal.analog"() <{metadata = {increment = 35 : i64}}> ({
    ^bb0:
      %voltage = "nodal.parameter_ref"() <{metadata = {}, parameter = @V0}> : () -> !nodal.quantity<"real", "voltage">
      %slope = "nodal.analog_ddt"(%voltage) <{analyses = ["ac", "dc", "initialization", "noise", "operating-point", "transient"], context = "equation", initialization = "none", input_dimension = "voltage", metadata = {identity = "slope"}, operator_contract = "increment35", operator_id = "DifferentialIntegral.slope", owner = "DifferentialIntegral", result_dimension = "time^-1*voltage"}> : (!nodal.quantity<"real", "voltage">) -> !nodal.quantity<"real", "time^-1*voltage">
      %current = "nodal.parameter_ref"() <{metadata = {}, parameter = @I0}> : () -> !nodal.quantity<"real", "current">
      %charge = "nodal.parameter_ref"() <{metadata = {}, parameter = @Q0}> : () -> !nodal.quantity<"real", "current*time">
      %fixed = "nodal.analog_idt"(%current, %charge) <{analyses = ["ac", "dc", "initialization", "noise", "operating-point", "transient"], context = "equation", initial_dimension = "current*time", initialization = "fixed", input_dimension = "current", metadata = {identity = "fixed"}, operator_contract = "increment35", operator_id = "DifferentialIntegral.fixed", owner = "DifferentialIntegral", result_dimension = "current*time", state_id = "DifferentialIntegral.fixed.state"}> : (!nodal.quantity<"real", "current">, !nodal.quantity<"real", "current*time">) -> !nodal.quantity<"real", "current*time">
      %solver = "nodal.analog_idt"(%current) <{analyses = ["ac", "dc", "initialization", "noise", "operating-point", "transient"], context = "contribution", initialization = "solver-selected", input_dimension = "current", metadata = {identity = "solver"}, operator_contract = "increment35", operator_id = "DifferentialIntegral.solver", owner = "DifferentialIntegral", result_dimension = "current*time", state_id = "DifferentialIntegral.solver.state"}> : (!nodal.quantity<"real", "current">) -> !nodal.quantity<"real", "current*time">
    }) : () -> ()
  }) : () -> ()
}
