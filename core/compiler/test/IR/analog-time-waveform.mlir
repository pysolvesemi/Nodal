module attributes {
  nodal.backend.check_profile = "release",
  nodal.backend.materialization = "safe-inline",
  nodal.backend.naming = "semantic",
  nodal.backend.shaped_layout = "scalar-or-flat",
  nodal.target.profile = "analog"
} {
  "nodal.module"() ({
    %p = "nodal.terminal"() {name = "p", metadata = {declaration_kind = "analog-inout"}} : () -> !nodal.terminal<"electrical">
    %n = "nodal.terminal"() {name = "n", metadata = {declaration_kind = "analog-inout"}} : () -> !nodal.terminal<"electrical">
    %b = "nodal.branch"(%p, %n) {metadata = {}} : (!nodal.terminal<"electrical">, !nodal.terminal<"electrical">) -> !nodal.branch<"electrical">
    "nodal.analog"() ({
      %level = "nodal.real_literal"() {value = 1.0 : f64, metadata = {}} : () -> !nodal.quantity<"real", "voltage">
      %seconds = "nodal.real_literal"() {value = 1.0e-9 : f64, metadata = {}} : () -> !nodal.quantity<"real", "time">
      %zero = "nodal.real_literal"() {value = 0.0 : f64, metadata = {}} : () -> !nodal.quantity<"real", "time">
      %up = "nodal.real_literal"() {value = 1.0e9 : f64, metadata = {}} : () -> !nodal.quantity<"real", "time^-1*voltage">
      %down = "nodal.real_literal"() {value = -1.0e9 : f64, metadata = {}} : () -> !nodal.quantity<"real", "time^-1*voltage">
      %input = "nodal.access"(%b) {kind = "potential", metadata = {}} : (!nodal.branch<"electrical">) -> !nodal.quantity<"real", "voltage">
      %smoothed = "nodal.analog_transition"(%level, %zero, %seconds, %seconds, %zero) {operator_contract = "increment36", operator_id = "Waveform.smoothed", owner = "Waveform", context = "legacy-analog", operand_dimensions = ["voltage", "time", "time", "time", "time"], result_dimension = "voltage", input_continuity = "constant", output_continuity = "continuous", analyses = ["ac", "dc", "initialization", "noise", "operating-point", "transient"], state_id = "Waveform.smoothed.state", metadata = {}} : (!nodal.quantity<"real", "voltage">, !nodal.quantity<"real", "time">, !nodal.quantity<"real", "time">, !nodal.quantity<"real", "time">, !nodal.quantity<"real", "time">) -> !nodal.quantity<"real", "voltage">
      %limited = "nodal.analog_slew"(%input, %up, %down) {operator_contract = "increment36", operator_id = "Waveform.limited", owner = "Waveform", context = "legacy-analog", operand_dimensions = ["voltage", "time^-1*voltage", "time^-1*voltage"], result_dimension = "voltage", input_continuity = "unknown", output_continuity = "continuous", analyses = ["ac", "dc", "initialization", "noise", "operating-point", "transient"], state_id = "Waveform.limited.state", metadata = {}} : (!nodal.quantity<"real", "voltage">, !nodal.quantity<"real", "time^-1*voltage">, !nodal.quantity<"real", "time^-1*voltage">) -> !nodal.quantity<"real", "voltage">
      %now = "nodal.analog_abstime"() {operator_contract = "increment36", operator_id = "Waveform.now", owner = "Waveform", context = "legacy-analog", operand_dimensions = [], result_dimension = "time", input_continuity = "none", output_continuity = "continuous", analyses = ["ac", "dc", "initialization", "noise", "operating-point", "transient"], metadata = {}} : () -> !nodal.quantity<"real", "time">
      %changing = "nodal.analog_add"(%now, %seconds) {metadata = {}} : (!nodal.quantity<"real", "time">, !nodal.quantity<"real", "time">) -> !nodal.quantity<"real", "time">
      %delayed = "nodal.analog_absdelay"(%limited, %changing, %seconds) {operator_contract = "increment36", operator_id = "Waveform.delayed", owner = "Waveform", context = "legacy-analog", operand_dimensions = ["voltage", "time", "time"], result_dimension = "voltage", input_continuity = "continuous", output_continuity = "unknown", analyses = ["ac", "dc", "initialization", "noise", "operating-point", "transient"], state_id = "Waveform.delayed.state", metadata = {}} : (!nodal.quantity<"real", "voltage">, !nodal.quantity<"real", "time">, !nodal.quantity<"real", "time">) -> !nodal.quantity<"real", "voltage">
      "nodal.analog_bound_step"(%zero) {operator_contract = "increment36", operator_id = "Waveform.step", owner = "Waveform", context = "legacy-analog", operand_dimensions = ["time"], result_dimension = "none", input_continuity = "none", output_continuity = "none", analyses = ["transient"], metadata = {}} : (!nodal.quantity<"real", "time">) -> ()
      %shared = "nodal.analog_add"(%smoothed, %smoothed) {metadata = {}} : (!nodal.quantity<"real", "voltage">, !nodal.quantity<"real", "voltage">) -> !nodal.quantity<"real", "voltage">
      %sum = "nodal.analog_add"(%shared, %delayed) {metadata = {}} : (!nodal.quantity<"real", "voltage">, !nodal.quantity<"real", "voltage">) -> !nodal.quantity<"real", "voltage">
      "nodal.contribute"(%b, %sum) {kind = "potential", metadata = {}} : (!nodal.branch<"electrical">, !nodal.quantity<"real", "voltage">) -> ()
    }) {metadata = {}} : () -> ()
  }) {sym_name = "Waveform", metadata = {}} : () -> ()
}
