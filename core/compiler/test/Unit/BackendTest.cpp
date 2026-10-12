#include "nodal/Backend/Backend.h"

#include "circt/Dialect/HW/HWDialect.h"
#include "mlir/IR/MLIRContext.h"
#include "mlir/Parser/Parser.h"
#include "nodal/Backend/AnalogVerticalSlice.h"
#include "nodal/Dialect/Nodal/NodalDialect.h"

#include "llvm/ADT/StringRef.h"
#include "llvm/Support/raw_ostream.h"

#include <string>

namespace {

constexpr llvm::StringLiteral kAnalogModule = R"mlir(
module attributes {
  nodal.backend.check_profile = "release",
  nodal.backend.materialization = "safe-inline",
  nodal.backend.naming = "semantic",
  nodal.backend.profile = "verilog-a",
  nodal.backend.shaped_layout = "scalar-or-flat",
  nodal.target.profile = "analog"
} {
  "nodal.module"() <{metadata = {root = false}, sym_name = "Zeta"}> ({
  ^bb0:
  }) : () -> ()
  "nodal.module"() <{metadata = {root = true}, sym_name = "Alpha"}> ({
  ^bb0:
  }) : () -> ()
}
)mlir";

constexpr llvm::StringLiteral kReservedModule = R"mlir(
module attributes {
  nodal.backend.profile = "verilog-a",
  nodal.target.profile = "analog"
} {
  "nodal.module"() <{metadata = {root = true}, sym_name = "input"}> ({
  ^bb0:
  }) : () -> ()
}
)mlir";

constexpr llvm::StringLiteral kEndmoduleSubstringModule = R"mlir(
module attributes {
  nodal.backend.profile = "verilog-a",
  nodal.target.profile = "analog"
} {
  "nodal.module"() <{metadata = {root = true}, sym_name = "myendmoduleBlock"}> ({
  ^bb0:
  }) : () -> ()
}
)mlir";

constexpr llvm::StringLiteral kMalformedLayout = R"mlir(
module attributes {
  nodal.backend.profile = "verilog-a",
  nodal.backend.shaped_layout = 1 : i64,
  nodal.target.profile = "analog"
} {
  "nodal.module"() <{metadata = {root = true}, sym_name = "Top"}> ({
  ^bb0:
  }) : () -> ()
}
)mlir";

constexpr llvm::StringLiteral kUnsupportedModule = R"mlir(
module attributes {
  nodal.backend.profile = "verilog-a",
  nodal.target.profile = "analog"
} {
  "nodal.module"() <{metadata = {root = true}, sym_name = "Top"}> ({
  ^bb0:
    %zero = "nodal.constant"() <{metadata = {}, value = 0 : i64}> :
      () -> !nodal.uint<8>
  }) : () -> ()
}
)mlir";

constexpr llvm::StringLiteral kGeneratedContributionModule = R"mlir(
module attributes {
  nodal.backend.check_profile = "release",
  nodal.backend.materialization = "safe-inline",
  nodal.backend.naming = "semantic",
  nodal.backend.profile = "verilog-a",
  nodal.backend.shaped_layout = "scalar-or-flat",
  nodal.target.profile = "analog"
} {
  "nodal.module"() <{metadata = {root = true}, sym_name = "GeneratedContribution"}> ({
  ^bb0:
    %p = "nodal.terminal"() <{metadata = {declaration_kind = "analog-inout"}, name = "p"}> : () -> !nodal.terminal<"electrical">
    %n = "nodal.terminal"() <{metadata = {declaration_kind = "analog-inout"}, name = "n"}> : () -> !nodal.terminal<"electrical">
    %branch = "nodal.branch"(%p, %n) <{metadata = {}}> : (!nodal.terminal<"electrical">, !nodal.terminal<"electrical">) -> !nodal.branch<"electrical">
    "nodal.generate"() <{induction = "generate_0_index", lower = 0 : i64, upper = 2 : i64, step = 1 : i64, region_id = "GeneratedContribution.generate_0", induction_path = "GeneratedContribution.generate_0.index", metadata = {maximum_trip_count = 2 : i64}}> ({
    ^bb0:
      "nodal.analog"() <{generated_owner = "GeneratedContribution.generate_0", generated_induction = "GeneratedContribution.generate_0.index", metadata = {}}> ({
      ^bb0:
        %potential = "nodal.real_literal"() <{value = 1.0 : f64, metadata = {}}> : () -> f64
        "nodal.contribute"(%branch, %potential) <{kind = "potential", generated_owner = "GeneratedContribution.generate_0", generated_induction = "GeneratedContribution.generate_0.index", metadata = {}}> : (!nodal.branch<"electrical">, f64) -> ()
        %flow = "nodal.real_literal"() <{value = 1.0 : f64, metadata = {}}> : () -> f64
        "nodal.contribute"(%branch, %flow) <{kind = "flow", generated_owner = "GeneratedContribution.generate_0", generated_induction = "GeneratedContribution.generate_0.index", metadata = {}}> : (!nodal.branch<"electrical">, f64) -> ()
      }) : () -> ()
    }) : () -> ()
  }) : () -> ()
}
)mlir";

constexpr llvm::StringLiteral kGeneratedDdtContributionModule = R"mlir(
module attributes {
  nodal.backend.check_profile = "release",
  nodal.backend.materialization = "safe-inline",
  nodal.backend.naming = "semantic",
  nodal.backend.profile = "verilog-a",
  nodal.backend.shaped_layout = "scalar-or-flat",
  nodal.target.profile = "analog"
} {
  "nodal.module"() <{metadata = {root = true}, sym_name = "GeneratedDdtContribution"}> ({
  ^bb0:
    %p = "nodal.terminal"() <{metadata = {declaration_kind = "analog-inout"}, name = "p"}> : () -> !nodal.terminal<"electrical">
    %n = "nodal.terminal"() <{metadata = {declaration_kind = "analog-inout"}, name = "n"}> : () -> !nodal.terminal<"electrical">
    %branch = "nodal.branch"(%p, %n) <{metadata = {}}> : (!nodal.terminal<"electrical">, !nodal.terminal<"electrical">) -> !nodal.branch<"electrical">
    "nodal.generate"() <{induction = "generate_0_index", lower = 0 : i64, upper = 2 : i64, step = 1 : i64, region_id = "GeneratedDdtContribution.generate_0", induction_path = "GeneratedDdtContribution.generate_0.index", metadata = {maximum_trip_count = 2 : i64}}> ({
    ^bb0:
      "nodal.analog"() <{generated_owner = "GeneratedDdtContribution.generate_0", generated_induction = "GeneratedDdtContribution.generate_0.index", metadata = {}}> ({
      ^bb0:
        %voltage = "nodal.access"(%branch) <{kind = "potential", metadata = {}}> : (!nodal.branch<"electrical">) -> f64
        %derivative = "nodal.analog_ddt"(%voltage) <{analyses = ["ac", "dc", "initialization", "noise", "operating-point", "transient"], context = "contribution", generated_owner = "GeneratedDdtContribution.generate_0", generated_induction = "GeneratedDdtContribution.generate_0.index", initialization = "none", input_dimension = "1", metadata = {identity = "derivative"}, operator_contract = "increment35", operator_id = "GeneratedDdtContribution.derivative", owner = "GeneratedDdtContribution", result_dimension = "time^-1"}> : (f64) -> f64
        "nodal.contribute"(%branch, %derivative) <{kind = "potential", generated_owner = "GeneratedDdtContribution.generate_0", generated_induction = "GeneratedDdtContribution.generate_0.index", metadata = {}}> : (!nodal.branch<"electrical">, f64) -> ()
      }) : () -> ()
    }) : () -> ()
  }) : () -> ()
}
)mlir";

constexpr llvm::StringLiteral kGeneratedIdtContributionModule = R"mlir(
module attributes {
  nodal.backend.check_profile = "release",
  nodal.backend.materialization = "safe-inline",
  nodal.backend.naming = "semantic",
  nodal.backend.profile = "verilog-a",
  nodal.backend.shaped_layout = "scalar-or-flat",
  nodal.target.profile = "analog"
} {
  "nodal.module"() <{metadata = {root = true}, sym_name = "GeneratedIdtContribution"}> ({
  ^bb0:
    %p = "nodal.terminal"() <{metadata = {declaration_kind = "analog-inout"}, name = "p"}> : () -> !nodal.terminal<"electrical">
    %n = "nodal.terminal"() <{metadata = {declaration_kind = "analog-inout"}, name = "n"}> : () -> !nodal.terminal<"electrical">
    %branch = "nodal.branch"(%p, %n) <{metadata = {}}> : (!nodal.terminal<"electrical">, !nodal.terminal<"electrical">) -> !nodal.branch<"electrical">
    "nodal.generate"() <{induction = "generate_0_index", lower = 0 : i64, upper = 2 : i64, step = 1 : i64, region_id = "GeneratedIdtContribution.generate_0", induction_path = "GeneratedIdtContribution.generate_0.index", metadata = {maximum_trip_count = 2 : i64}}> ({
    ^bb0:
      "nodal.analog"() <{generated_owner = "GeneratedIdtContribution.generate_0", generated_induction = "GeneratedIdtContribution.generate_0.index", metadata = {}}> ({
      ^bb0:
        %rate = "nodal.real_literal"() <{value = 1.0 : f64, metadata = {}}> : () -> f64
        %integral = "nodal.analog_idt"(%rate) <{analyses = ["ac", "dc", "initialization", "noise", "operating-point", "transient"], context = "contribution", generated_owner = "GeneratedIdtContribution.generate_0", generated_induction = "GeneratedIdtContribution.generate_0.index", initialization = "solver-selected", input_dimension = "time^-1*voltage", metadata = {identity = "integral"}, operator_contract = "increment35", operator_id = "GeneratedIdtContribution.integral", owner = "GeneratedIdtContribution", result_dimension = "voltage", state_id = "GeneratedIdtContribution.integral.state"}> : (f64) -> f64
        "nodal.contribute"(%branch, %integral) <{kind = "potential", generated_owner = "GeneratedIdtContribution.generate_0", generated_induction = "GeneratedIdtContribution.generate_0.index", metadata = {}}> : (!nodal.branch<"electrical">, f64) -> ()
      }) : () -> ()
    }) : () -> ()
  }) : () -> ()
}
)mlir";

constexpr llvm::StringLiteral kGeneratedTransitionContributionModule = R"mlir(
module attributes {
  nodal.backend.check_profile = "release",
  nodal.backend.materialization = "safe-inline",
  nodal.backend.naming = "semantic",
  nodal.backend.profile = "verilog-a",
  nodal.backend.shaped_layout = "scalar-or-flat",
  nodal.target.profile = "analog"
} {
  "nodal.unit"() <{dimension = "voltage", metadata = {}, native_suffix = "", scale = 1.0 : f64, sym_name = "Volt", symbol = "V"}> : () -> ()
  "nodal.module"() <{metadata = {root = true}, sym_name = "GeneratedTransitionContribution"}> ({
  ^bb0:
    "nodal.parameter"() <{classification = "ordinary", default_value = 1.0 : f64, metadata = {}, parameter_kind = "real", sym_name = "LEVEL", type = f64, unit = @Volt, variability = "fixed"}> : () -> ()
    %p = "nodal.terminal"() <{metadata = {declaration_kind = "analog-inout"}, name = "p"}> : () -> !nodal.terminal<"electrical">
    %n = "nodal.terminal"() <{metadata = {declaration_kind = "analog-inout"}, name = "n"}> : () -> !nodal.terminal<"electrical">
    %branch = "nodal.branch"(%p, %n) <{metadata = {}}> : (!nodal.terminal<"electrical">, !nodal.terminal<"electrical">) -> !nodal.branch<"electrical">
    "nodal.generate"() <{induction = "generate_0_index", lower = 0 : i64, upper = 2 : i64, step = 1 : i64, region_id = "GeneratedTransitionContribution.generate_0", induction_path = "GeneratedTransitionContribution.generate_0.index", metadata = {maximum_trip_count = 2 : i64}}> ({
    ^bb0:
      "nodal.analog"() <{generated_owner = "GeneratedTransitionContribution.generate_0", generated_induction = "GeneratedTransitionContribution.generate_0.index", metadata = {}}> ({
      ^bb0:
        %input = "nodal.parameter_ref"() <{metadata = {}, parameter = @LEVEL}> : () -> !nodal.quantity<"real", "voltage">
        %smoothed = "nodal.analog_transition"(%input) <{analyses = ["ac", "dc", "initialization", "noise", "operating-point", "transient"], context = "contribution", generated_owner = "GeneratedTransitionContribution.generate_0", generated_induction = "GeneratedTransitionContribution.generate_0.index", input_continuity = "constant", metadata = {identity = "smoothed"}, operand_dimensions = ["voltage"], operator_contract = "increment36", operator_id = "GeneratedTransitionContribution.smoothed", output_continuity = "continuous", owner = "GeneratedTransitionContribution", result_dimension = "voltage", state_id = "GeneratedTransitionContribution.smoothed.state"}> : (!nodal.quantity<"real", "voltage">) -> !nodal.quantity<"real", "voltage">
        "nodal.contribute"(%branch, %smoothed) <{kind = "potential", generated_owner = "GeneratedTransitionContribution.generate_0", generated_induction = "GeneratedTransitionContribution.generate_0.index", metadata = {}}> : (!nodal.branch<"electrical">, !nodal.quantity<"real", "voltage">) -> ()
      }) : () -> ()
    }) : () -> ()
  }) : () -> ()
}
)mlir";

constexpr llvm::StringLiteral kGeneratedSlewContributionModule = R"mlir(
module attributes {
  nodal.backend.check_profile = "release",
  nodal.backend.materialization = "safe-inline",
  nodal.backend.naming = "semantic",
  nodal.backend.profile = "verilog-a",
  nodal.backend.shaped_layout = "scalar-or-flat",
  nodal.target.profile = "analog"
} {
  "nodal.unit"() <{dimension = "voltage", metadata = {}, native_suffix = "", scale = 1.0 : f64, sym_name = "Volt", symbol = "V"}> : () -> ()
  "nodal.module"() <{metadata = {root = true}, sym_name = "GeneratedSlewContribution"}> ({
  ^bb0:
    "nodal.parameter"() <{classification = "ordinary", default_value = 1.0 : f64, metadata = {}, parameter_kind = "real", sym_name = "LEVEL", type = f64, unit = @Volt, variability = "fixed"}> : () -> ()
    %p = "nodal.terminal"() <{metadata = {declaration_kind = "analog-inout"}, name = "p"}> : () -> !nodal.terminal<"electrical">
    %n = "nodal.terminal"() <{metadata = {declaration_kind = "analog-inout"}, name = "n"}> : () -> !nodal.terminal<"electrical">
    %branch = "nodal.branch"(%p, %n) <{metadata = {}}> : (!nodal.terminal<"electrical">, !nodal.terminal<"electrical">) -> !nodal.branch<"electrical">
    "nodal.generate"() <{induction = "generate_0_index", lower = 0 : i64, upper = 2 : i64, step = 1 : i64, region_id = "GeneratedSlewContribution.generate_0", induction_path = "GeneratedSlewContribution.generate_0.index", metadata = {maximum_trip_count = 2 : i64}}> ({
    ^bb0:
      "nodal.analog"() <{generated_owner = "GeneratedSlewContribution.generate_0", generated_induction = "GeneratedSlewContribution.generate_0.index", metadata = {}}> ({
      ^bb0:
        %input = "nodal.parameter_ref"() <{metadata = {}, parameter = @LEVEL}> : () -> !nodal.quantity<"real", "voltage">
        %limited = "nodal.analog_slew"(%input) <{analyses = ["ac", "dc", "initialization", "noise", "operating-point", "transient"], context = "contribution", generated_owner = "GeneratedSlewContribution.generate_0", generated_induction = "GeneratedSlewContribution.generate_0.index", input_continuity = "constant", metadata = {identity = "limited"}, operand_dimensions = ["voltage"], operator_contract = "increment36", operator_id = "GeneratedSlewContribution.limited", output_continuity = "constant", owner = "GeneratedSlewContribution", result_dimension = "voltage", state_id = "GeneratedSlewContribution.limited.state"}> : (!nodal.quantity<"real", "voltage">) -> !nodal.quantity<"real", "voltage">
        "nodal.contribute"(%branch, %limited) <{kind = "potential", generated_owner = "GeneratedSlewContribution.generate_0", generated_induction = "GeneratedSlewContribution.generate_0.index", metadata = {}}> : (!nodal.branch<"electrical">, !nodal.quantity<"real", "voltage">) -> ()
      }) : () -> ()
    }) : () -> ()
  }) : () -> ()
}
)mlir";

constexpr llvm::StringLiteral kGeneratedAbsdelayContributionModule = R"mlir(
module attributes {
  nodal.backend.check_profile = "release",
  nodal.backend.materialization = "safe-inline",
  nodal.backend.naming = "semantic",
  nodal.backend.profile = "verilog-a",
  nodal.backend.shaped_layout = "scalar-or-flat",
  nodal.target.profile = "analog"
} {
  "nodal.unit"() <{dimension = "voltage", metadata = {}, native_suffix = "", scale = 1.0 : f64, sym_name = "Volt", symbol = "V"}> : () -> ()
  "nodal.unit"() <{dimension = "time", metadata = {}, native_suffix = "", scale = 1.0 : f64, sym_name = "Second", symbol = "s"}> : () -> ()
  "nodal.module"() <{metadata = {root = true}, sym_name = "GeneratedAbsdelayContribution"}> ({
  ^bb0:
    "nodal.parameter"() <{classification = "ordinary", default_value = 1.0 : f64, metadata = {}, parameter_kind = "real", sym_name = "LEVEL", type = f64, unit = @Volt, variability = "fixed"}> : () -> ()
    "nodal.parameter"() <{classification = "ordinary", default_value = 1.0e-9 : f64, metadata = {}, parameter_kind = "real", sym_name = "DELAY", type = f64, unit = @Second, variability = "fixed"}> : () -> ()
    %p = "nodal.terminal"() <{metadata = {declaration_kind = "analog-inout"}, name = "p"}> : () -> !nodal.terminal<"electrical">
    %n = "nodal.terminal"() <{metadata = {declaration_kind = "analog-inout"}, name = "n"}> : () -> !nodal.terminal<"electrical">
    %branch = "nodal.branch"(%p, %n) <{metadata = {}}> : (!nodal.terminal<"electrical">, !nodal.terminal<"electrical">) -> !nodal.branch<"electrical">
    "nodal.generate"() <{induction = "generate_0_index", lower = 0 : i64, upper = 2 : i64, step = 1 : i64, region_id = "GeneratedAbsdelayContribution.generate_0", induction_path = "GeneratedAbsdelayContribution.generate_0.index", metadata = {maximum_trip_count = 2 : i64}}> ({
    ^bb0:
      "nodal.analog"() <{generated_owner = "GeneratedAbsdelayContribution.generate_0", generated_induction = "GeneratedAbsdelayContribution.generate_0.index", metadata = {}}> ({
      ^bb0:
        %input = "nodal.parameter_ref"() <{metadata = {}, parameter = @LEVEL}> : () -> !nodal.quantity<"real", "voltage">
        %delay = "nodal.parameter_ref"() <{metadata = {}, parameter = @DELAY}> : () -> !nodal.quantity<"real", "time">
        %delayed = "nodal.analog_absdelay"(%input, %delay) <{analyses = ["ac", "dc", "initialization", "noise", "operating-point", "transient"], context = "contribution", generated_owner = "GeneratedAbsdelayContribution.generate_0", generated_induction = "GeneratedAbsdelayContribution.generate_0.index", input_continuity = "constant", metadata = {identity = "delayed"}, operand_dimensions = ["voltage", "time"], operator_contract = "increment36", operator_id = "GeneratedAbsdelayContribution.delayed", output_continuity = "unknown", owner = "GeneratedAbsdelayContribution", result_dimension = "voltage", state_id = "GeneratedAbsdelayContribution.delayed.state"}> : (!nodal.quantity<"real", "voltage">, !nodal.quantity<"real", "time">) -> !nodal.quantity<"real", "voltage">
        "nodal.contribute"(%branch, %delayed) <{kind = "potential", generated_owner = "GeneratedAbsdelayContribution.generate_0", generated_induction = "GeneratedAbsdelayContribution.generate_0.index", metadata = {}}> : (!nodal.branch<"electrical">, !nodal.quantity<"real", "voltage">) -> ()
      }) : () -> ()
    }) : () -> ()
  }) : () -> ()
}
)mlir";

constexpr llvm::StringLiteral kSymbolicGeneratedContributionModule = R"mlir(
module attributes {
  nodal.backend.check_profile = "release",
  nodal.backend.materialization = "safe-inline",
  nodal.backend.naming = "semantic",
  nodal.backend.profile = "verilog-a",
  nodal.backend.shaped_layout = "scalar-or-flat",
  nodal.target.profile = "analog"
} {
  "nodal.module"() <{metadata = {root = true}, sym_name = "SymbolicGeneratedContribution"}> ({
  ^bb0:
    "nodal.parameter"() <{classification = "structural", default_value = 4 : i64, metadata = {}, parameter_kind = "integer", sym_name = "COUNT", type = i64, variability = "symbolic"}> : () -> ()
    %default = "nodal.const_literal"() <{metadata = {}, spelling = "4", value = 4 : i64}> : () -> i64
    "nodal.parameter_value"(%default) <{metadata = {}, parameter = @COUNT}> : (i64) -> ()
    %minimum = "nodal.const_literal"() <{metadata = {}, spelling = "1", value = 1 : i64}> : () -> i64
    %maximum = "nodal.const_literal"() <{metadata = {}, spelling = "8", value = 8 : i64}> : () -> i64
    "nodal.parameter_constraint"(%minimum, %maximum) <{constraint_kind = "range", lower_inclusive = true, metadata = {}, parameter = @COUNT, upper_inclusive = true}> : (i64, i64) -> ()
    "nodal.parameter_envelope"() <{effects = ["topology"], metadata = {}, parameter = @COUNT, policy = "static_generate"}> : () -> ()
    %p = "nodal.terminal"() <{metadata = {declaration_kind = "analog-inout"}, name = "p"}> : () -> !nodal.terminal<"electrical">
    %n = "nodal.terminal"() <{metadata = {declaration_kind = "analog-inout"}, name = "n"}> : () -> !nodal.terminal<"electrical">
    %branch = "nodal.branch"(%p, %n) <{metadata = {}}> : (!nodal.terminal<"electrical">, !nodal.terminal<"electrical">) -> !nodal.branch<"electrical">
    "nodal.generate"() <{induction = "generate_0_index", lower = 0 : i64, upper = @COUNT, step = 1 : i64, region_id = "SymbolicGeneratedContribution.generate_0", induction_path = "SymbolicGeneratedContribution.generate_0.index", metadata = {maximum_trip_count = 8 : i64}}> ({
    ^bb0:
      "nodal.analog"() <{generated_owner = "SymbolicGeneratedContribution.generate_0", generated_induction = "SymbolicGeneratedContribution.generate_0.index", metadata = {}}> ({
      ^bb0:
        %potential = "nodal.real_literal"() <{value = 1.0 : f64, metadata = {}}> : () -> f64
        "nodal.contribute"(%branch, %potential) <{kind = "potential", generated_owner = "SymbolicGeneratedContribution.generate_0", generated_induction = "SymbolicGeneratedContribution.generate_0.index", metadata = {}}> : (!nodal.branch<"electrical">, f64) -> ()
      }) : () -> ()
    }) : () -> ()
  }) : () -> ()
}
)mlir";

constexpr llvm::StringLiteral kCompoundGeneratedContributionModule = R"mlir(
module attributes {
  nodal.backend.check_profile = "release",
  nodal.backend.materialization = "safe-inline",
  nodal.backend.naming = "semantic",
  nodal.backend.profile = "verilog-a",
  nodal.backend.shaped_layout = "scalar-or-flat",
  nodal.target.profile = "analog"
} {
  "nodal.module"() <{metadata = {root = true}, sym_name = "CompoundGeneratedContribution"}> ({
  ^bb0:
    "nodal.parameter"() <{classification = "structural", default_value = 3 : i64, metadata = {}, parameter_kind = "integer", sym_name = "COUNT", type = i64, variability = "symbolic"}> : () -> ()
    %count_default = "nodal.const_literal"() <{metadata = {}, spelling = "3", value = 3 : i64}> : () -> i64
    "nodal.parameter_value"(%count_default) <{metadata = {}, parameter = @COUNT}> : (i64) -> ()
    %count_minimum = "nodal.const_literal"() <{metadata = {}, spelling = "1", value = 1 : i64}> : () -> i64
    %count_maximum = "nodal.const_literal"() <{metadata = {}, spelling = "4", value = 4 : i64}> : () -> i64
    "nodal.parameter_constraint"(%count_minimum, %count_maximum) <{constraint_kind = "range", lower_inclusive = true, metadata = {}, parameter = @COUNT, upper_inclusive = true}> : (i64, i64) -> ()
    "nodal.parameter_envelope"() <{effects = ["topology"], metadata = {}, parameter = @COUNT, policy = "static_generate"}> : () -> ()
    "nodal.parameter"() <{classification = "structural", default_value = 1 : i64, metadata = {}, parameter_kind = "integer", sym_name = "EXTRA", type = i64, variability = "symbolic"}> : () -> ()
    %extra_default = "nodal.const_literal"() <{metadata = {}, spelling = "1", value = 1 : i64}> : () -> i64
    "nodal.parameter_value"(%extra_default) <{metadata = {}, parameter = @EXTRA}> : (i64) -> ()
    %extra_minimum = "nodal.const_literal"() <{metadata = {}, spelling = "1", value = 1 : i64}> : () -> i64
    %extra_maximum = "nodal.const_literal"() <{metadata = {}, spelling = "2", value = 2 : i64}> : () -> i64
    "nodal.parameter_constraint"(%extra_minimum, %extra_maximum) <{constraint_kind = "range", lower_inclusive = true, metadata = {}, parameter = @EXTRA, upper_inclusive = true}> : (i64, i64) -> ()
    "nodal.parameter_envelope"() <{effects = ["topology"], metadata = {}, parameter = @EXTRA, policy = "static_generate"}> : () -> ()
    %zero = "nodal.const_literal"() <{metadata = {source_path = "CompoundGeneratedContribution.generate_0.lower"}, spelling = "0", value = 0 : i64}> : () -> i64
    %count = "nodal.const_parameter_ref"() <{metadata = {source_path = "CompoundGeneratedContribution.count"}, parameter = @COUNT}> : () -> i64
    %extra = "nodal.const_parameter_ref"() <{metadata = {source_path = "CompoundGeneratedContribution.extra"}, parameter = @EXTRA}> : () -> i64
    %upper = "nodal.const_expr"(%count, %extra) <{metadata = {source_path = "CompoundGeneratedContribution.generate_0.upper"}, operator_name = "add"}> : (i64, i64) -> i64
    %one = "nodal.const_literal"() <{metadata = {source_path = "CompoundGeneratedContribution.generate_0.step"}, spelling = "1", value = 1 : i64}> : () -> i64
    %p = "nodal.terminal"() <{metadata = {declaration_kind = "analog-inout"}, name = "p"}> : () -> !nodal.terminal<"electrical">
    %n = "nodal.terminal"() <{metadata = {declaration_kind = "analog-inout"}, name = "n"}> : () -> !nodal.terminal<"electrical">
    %branch = "nodal.branch"(%p, %n) <{metadata = {}}> : (!nodal.terminal<"electrical">, !nodal.terminal<"electrical">) -> !nodal.branch<"electrical">
    "nodal.generate"(%zero, %upper, %one) <{induction = "generate_0_index", region_id = "CompoundGeneratedContribution.generate_0", induction_path = "CompoundGeneratedContribution.generate_0.index", metadata = {maximum_trip_count = 6 : i64}}> ({
    ^bb0:
      "nodal.analog"() <{generated_owner = "CompoundGeneratedContribution.generate_0", generated_induction = "CompoundGeneratedContribution.generate_0.index", metadata = {}}> ({
      ^bb0:
        %potential = "nodal.real_literal"() <{value = 1.0 : f64, metadata = {}}> : () -> f64
        "nodal.contribute"(%branch, %potential) <{kind = "potential", generated_owner = "CompoundGeneratedContribution.generate_0", generated_induction = "CompoundGeneratedContribution.generate_0.index", metadata = {}}> : (!nodal.branch<"electrical">, f64) -> ()
      }) : () -> ()
    }) : (i64, i64, i64) -> ()
  }) : () -> ()
}
)mlir";

constexpr llvm::StringLiteral kHierarchyModule = R"mlir(
module attributes {
  nodal.backend.check_profile = "release",
  nodal.backend.materialization = "safe-inline",
  nodal.backend.naming = "semantic",
  nodal.backend.profile = "verilog-a",
  nodal.backend.shaped_layout = "scalar-or-flat",
  nodal.root.module = @Top,
  nodal.root.parameter_bindings = {rootGain = 4.0 : f64},
  nodal.target.profile = "analog"
} {
  "nodal.nature"() <{abstol = 1.0e-6 : f64, access = "V", metadata = {}, sym_name = "Voltage", units = "V"}> : () -> ()
  "nodal.nature"() <{abstol = 1.0e-12 : f64, access = "I", metadata = {}, sym_name = "Current", units = "A"}> : () -> ()
  "nodal.discipline"() <{domain = "continuous", flow = @Current, metadata = {}, potential = @Voltage, sym_name = "electrical"}> : () -> ()
  "nodal.module"() <{metadata = {}, sym_name = "Child"}> ({
  ^bb0:
    %child_vin = "nodal.terminal"() <{direction = "input", flow_orientation = "into_component", metadata = {allow_floating = true, declaration_kind = "analog-input"}, name = "vin", source_path = "Child.vin"}> : () -> !nodal.terminal<"electrical">
    %child_vout = "nodal.terminal"() <{direction = "output", flow_orientation = "into_component", metadata = {allow_floating = true, declaration_kind = "analog-output"}, name = "vout", source_path = "Child.vout"}> : () -> !nodal.terminal<"electrical">
    "nodal.parameter"() <{classification = "ordinary", default_value = 2.0 : f64, metadata = {}, parameter_kind = "real", sym_name = "gain", type = f64, variability = "symbolic"}> : () -> ()
    %child_default = "nodal.const_literal"() <{metadata = {}, spelling = "2.0", value = 2.0 : f64}> : () -> f64
    "nodal.parameter_value"(%child_default) <{metadata = {}, parameter = @gain}> : (f64) -> ()
  }) : () -> ()
  "nodal.module"() <{metadata = {}, sym_name = "Top"}> ({
  ^bb0:
    %top_vin = "nodal.terminal"() <{direction = "input", flow_orientation = "into_component", metadata = {declaration_kind = "analog-input"}, name = "vin", source_path = "Top.vin"}> : () -> !nodal.terminal<"electrical">
    %top_vout = "nodal.terminal"() <{direction = "output", flow_orientation = "into_component", metadata = {declaration_kind = "analog-output"}, name = "vout", source_path = "Top.vout"}> : () -> !nodal.terminal<"electrical">
    "nodal.parameter"() <{classification = "ordinary", default_value = 4.0 : f64, metadata = {}, parameter_kind = "real", sym_name = "rootGain", type = f64, variability = "symbolic"}> : () -> ()
    %top_default = "nodal.const_literal"() <{metadata = {}, spelling = "4.0", value = 4.0 : f64}> : () -> f64
    "nodal.parameter_value"(%top_default) <{metadata = {}, parameter = @rootGain}> : (f64) -> ()
    "nodal.instance"() <{domain_bindings = {}, metadata = {}, module = @Child, parameter_bindings = {}, sym_name = "child"}> : () -> ()
    %child_vin_ref = "nodal.instance_terminal"() <{direction = "input", flow_orientation = "out_of_component", instance = @child, metadata = {}, name = "child.vin", port = "vin", source_path = "Top.child.vin"}> : () -> !nodal.terminal<"electrical">
    %child_vout_ref = "nodal.instance_terminal"() <{direction = "output", flow_orientation = "out_of_component", instance = @child, metadata = {}, name = "child.vout", port = "vout", source_path = "Top.child.vout"}> : () -> !nodal.terminal<"electrical">
    "nodal.connect"(%top_vin, %child_vin_ref) <{connection_id = "vin", metadata = {}, source_path = "Top.vin-child.vin"}> : (!nodal.terminal<"electrical">, !nodal.terminal<"electrical">) -> ()
    "nodal.connect"(%child_vout_ref, %top_vout) <{connection_id = "vout", metadata = {}, source_path = "Top.child.vout-vout"}> : (!nodal.terminal<"electrical">, !nodal.terminal<"electrical">) -> ()
    %root_gain = "nodal.const_parameter_ref"() <{metadata = {}, parameter = @rootGain}> : () -> f64
    "nodal.parameter_override"(%root_gain) <{instance = @child, metadata = {}, parameter = @gain}> : (f64) -> ()
  }) : () -> ()
}
)mlir";

class RejectingReparseHooks final : public nodal::TargetVerificationHooks {
public:
  mlir::LogicalResult verifyTarget(llvm::StringRef,
                                   const nodal::BackendConfiguration &) const final {
    return mlir::success();
  }

  mlir::LogicalResult reparseTarget(llvm::StringRef,
                                    const nodal::BackendConfiguration &) const final {
    return mlir::failure();
  }
};

int fail(llvm::StringRef message) {
  llvm::errs() << "BackendTest: " << message << '\n';
  return 1;
}

mlir::OwningOpRef<mlir::ModuleOp> parse(mlir::MLIRContext &context, llvm::StringRef text) {
  return mlir::parseSourceString<mlir::ModuleOp>(text, &context);
}

} // namespace

int main() {
  // The target grammar distinguishes reserved built-in calls from identifiers.
  for (llvm::StringRef keyword :
       {"laplace_nd", "zi_nd", "abs", "analysis", "transition", "noise_table_log", "ln1p", "expm1",
        "enddiscipline", "continuous", "return", "uwire"}) {
    if (nodal::isPortableVerilogIdentifier(keyword) ||
        !nodal::isPortableVerilogIdentifier((keyword + "_value").str()))
      return fail("analog reserved identifiers must reject without rejecting suffixes");
  }
  if (!nodal::isPortableVerilogIdentifier("Abs") ||
      !nodal::isPortableVerilogIdentifier("negedgenmos"))
    return fail("identifier checking must retain case sensitivity and token boundaries");

  mlir::DialectRegistry registry;
  registry.insert<circt::hw::HWDialect, nodal::NodalDialect>();
  mlir::MLIRContext context(registry);
  context.loadAllAvailableDialects();

  auto analog = parse(context, kAnalogModule);
  if (!analog)
    return fail("could not parse the analog backend fixture");

  const nodal::BackendProfile &verilogA = nodal::getBackendProfile(nodal::BackendKind::VerilogA);
  const nodal::BackendProfile &verilogAMS =
      nodal::getBackendProfile(nodal::BackendKind::VerilogAMS);
  if (verilogA.id != "verilog-a" || verilogA.translation != "nodal-to-verilog-a" ||
      verilogA.shapedValueLayout != nodal::ShapedValueLayout::ScalarOrFlat ||
      verilogA.materialization != nodal::MaterializationPolicy::SafeInline ||
      verilogA.supportsMixedSignal)
    return fail("Verilog-A profile contract is incorrect");
  if (verilogAMS.id != "verilog-ams" || verilogAMS.translation != "nodal-to-verilog-ams" ||
      verilogAMS.shapedValueLayout != nodal::ShapedValueLayout::FlatPacked ||
      verilogAMS.materialization != nodal::MaterializationPolicy::Readable ||
      !verilogAMS.supportsMixedSignal)
    return fail("Verilog-AMS profile contract is incorrect");

  std::string first;
  llvm::raw_string_ostream firstStream(first);
  if (mlir::failed(nodal::emitBackend(*analog, nodal::BackendKind::VerilogA, firstStream)))
    return fail("first deterministic emission failed");
  firstStream.flush();

  std::string second;
  llvm::raw_string_ostream secondStream(second);
  if (mlir::failed(nodal::emitBackend(*analog, nodal::BackendKind::VerilogA, secondStream)))
    return fail("second deterministic emission failed");
  secondStream.flush();

  if (first != second)
    return fail("repeated emissions are not byte-identical");
  if (first.find(" * check-profile: release\n") == std::string::npos)
    return fail("CheckProfile configuration was not retained");
  const size_t alpha = first.find("module Alpha;");
  const size_t zeta = first.find("module Zeta;");
  if (alpha == std::string::npos || zeta == std::string::npos || alpha >= zeta)
    return fail("module output is not sorted by semantic name");

  auto hierarchy = parse(context, kHierarchyModule);
  if (!hierarchy)
    return fail("could not parse the hierarchy backend fixture");
  std::string hierarchyOutput;
  llvm::raw_string_ostream hierarchyStream(hierarchyOutput);
  if (mlir::failed(nodal::emitBackend(*hierarchy, nodal::BackendKind::VerilogA, hierarchyStream)))
    return fail("named symbolic hierarchy emission failed");
  hierarchyStream.flush();
  constexpr llvm::StringLiteral childDefinition = "module Child(vin, vout);";
  const size_t firstChildDefinition = hierarchyOutput.find(childDefinition.str());
  if (hierarchyOutput.find("Child #(.gain(rootGain)) child(.vin(vin), .vout(vout));") ==
          std::string::npos ||
      firstChildDefinition == std::string::npos ||
      hierarchyOutput.find(childDefinition.str(), firstChildDefinition + 1) != std::string::npos)
    return fail("hierarchy output lost symbolic overrides, names, or definition reuse");

  std::string signedZeroRootSource = kHierarchyModule.str();
  const std::string signedZeroBinding = "nodal.root.parameter_bindings = {rootGain = 4.0 : f64}";
  const size_t signedZeroBindingPosition = signedZeroRootSource.find(signedZeroBinding);
  const std::string rootDefault = "default_value = 4.0 : f64";
  const size_t rootDefaultPosition = signedZeroRootSource.find(rootDefault);
  const std::string rootDefaultLiteral = "spelling = \"4.0\", value = 4.0 : f64";
  const size_t rootDefaultLiteralPosition = signedZeroRootSource.find(rootDefaultLiteral);
  if (signedZeroBindingPosition == std::string::npos || rootDefaultPosition == std::string::npos ||
      rootDefaultLiteralPosition == std::string::npos)
    return fail("signed-zero root-actual mutation anchors missing");
  signedZeroRootSource.replace(rootDefaultLiteralPosition, rootDefaultLiteral.size(),
                               "spelling = \"0.0\", value = 0.0 : f64");
  signedZeroRootSource.replace(rootDefaultPosition, rootDefault.size(),
                               "default_value = 0.0 : f64");
  signedZeroRootSource.replace(signedZeroBindingPosition, signedZeroBinding.size(),
                               "nodal.root.parameter_bindings = {rootGain = -0.0 : f64}");
  auto signedZeroRoot = parse(context, signedZeroRootSource);
  if (!signedZeroRoot)
    return fail("could not parse the signed-zero root-actual fixture");
  std::string signedZeroRootOutput;
  llvm::raw_string_ostream signedZeroRootStream(signedZeroRootOutput);
  if (mlir::failed(
          nodal::emitBackend(*signedZeroRoot, nodal::BackendKind::VerilogA, signedZeroRootStream)))
    return fail("numerically equal signed-zero root actual was rejected");
  signedZeroRootStream.flush();
  if (signedZeroRootOutput.find("parameter real rootGain = 0.0;") == std::string::npos)
    return fail("signed-zero root fixture lost the authored declaration default");

  std::string mismatchedRootSource = kHierarchyModule.str();
  const std::string matchingRoot = "nodal.root.parameter_bindings = {rootGain = 4.0 : f64}";
  const size_t matchingRootPosition = mismatchedRootSource.find(matchingRoot);
  if (matchingRootPosition == std::string::npos)
    return fail("root-actual mutation anchor missing");
  mismatchedRootSource.replace(matchingRootPosition, matchingRoot.size(),
                               "nodal.root.parameter_bindings = {rootGain = 6.0 : f64}");
  auto mismatchedRoot = parse(context, mismatchedRootSource);
  if (!mismatchedRoot)
    return fail("could not parse the non-default root-actual fixture");
  std::string rejectedRootOutput = "sentinel";
  llvm::raw_string_ostream rejectedRootStream(rejectedRootOutput);
  if (mlir::succeeded(
          nodal::emitBackend(*mismatchedRoot, nodal::BackendKind::VerilogA, rejectedRootStream)))
    return fail("non-default root actual was silently discarded");
  rejectedRootStream.flush();
  if (rejectedRootOutput != "sentinel")
    return fail("root-actual rejection published partial output");

  auto configuration = nodal::resolveBackendConfiguration(*analog, nodal::BackendKind::VerilogA);
  if (mlir::failed(configuration))
    return fail("could not resolve waveform reparse configuration");
  const std::string waveformTarget =
      "module Waveform;\nreal w;\nanalog begin\nw = transition(1, 0, 1e-9);\n"
      "$bound_step(0);\nend\nendmodule\n";
  if (mlir::failed(nodal::reparseBackendTarget(waveformTarget, *configuration)))
    return fail("waveform declarations, assignments, or effect task did not reparse");
  for (llvm::StringRef invalid :
       {"module M;\nreal w;\nanalog begin\nx = transition(1);\nend\nendmodule\n",
        "module M;\nreal w;\nanalog begin\nw = transition();\nend\nendmodule\n",
        "module M;\nreal w;\nanalog begin\nw = absdelay(1);\nend\nendmodule\n",
        "module M;\nreal w;\nanalog begin\nw = slew(1, 2, 3, 4);\nend\nendmodule\n",
        "module M;\nreal w;\nanalog begin\nw = transition((1);\nend\nendmodule\n",
        "module M;\nreal w;\nanalog begin\nw = other(1);\nend\nendmodule\n",
        "module M;\nreal w;\nanalog begin\nw = slew(1);\nw = slew(2);\nend\nendmodule\n",
        "module M;\nreal w;\nreal w;\nanalog begin\nw = slew(1);\nend\nendmodule\n",
        "module M;\nanalog begin\n$bound_step();\nend\nendmodule\n",
        "module M;\nanalog begin\n$bound_step(1, 2);\nend\nendmodule\n"}) {
    if (mlir::succeeded(nodal::reparseBackendTarget(invalid, *configuration)))
      return fail("malformed waveform target passed structural reparsing");
  }

  const std::string eventTarget =
      "module E;\nreal event_held = 0.0;\ninteger event_select = 1;\n"
      "analog begin\nbegin : event_procedure\n"
      "@(initial_step or cross(V(p, n), 1, 0, 0.01) or timer(0, 1e-9)) begin\n"
      "if (event_select) begin\nevent_held = V(p, n);\nend else begin\n"
      "event_held = (-1.0);\nend\nend\n@(final_step(\"tran\")) begin\nend\n"
      "end\nend\nendmodule\n";
  if (mlir::failed(nodal::reparseBackendTarget(eventTarget, *configuration)))
    return fail("event control, composition, lifecycle, or initialized variable failed reparse");
  for (auto mutation : {std::make_pair("timer(0, 1e-9)", "timer()"),
                        std::make_pair("cross(V(p, n), 1, 0, 0.01)", "cross(1, 1, 1, 1, 1, 1)"),
                        std::make_pair("initial_step", "unknown_event"),
                        std::make_pair("event_held = V(p, n);", "event_held = injected();"),
                        std::make_pair("event_held = V(p, n);", "$shell(\"injected\");"),
                        std::make_pair("end else begin", "end else injected"),
                        std::make_pair("final_step(\"tran\")", "final_step()")}) {
    std::string invalid = eventTarget;
    size_t position = invalid.find(mutation.first);
    if (position == std::string::npos)
      return fail("event mutation anchor missing");
    invalid.replace(position, std::string(mutation.first).size(), mutation.second);
    if (mlir::succeeded(nodal::reparseBackendTarget(invalid, *configuration)))
      return fail("malformed or injected event target passed structural reparse");
  }

  std::string declarationTarget =
      "/* Nodal backend framework v1\n * profile: verilog-a\n */\n"
      "`include \"constants.vams\"\n`include \"disciplines.vams\"\n"
      "module DeclarationNames(p, n);\ninout p, n;\nelectrical p, n;\n"
      "parameter real gain = 1;\nanalog begin\nV(p, n) <+ gain;\nend\nendmodule\n";
  if (mlir::failed(nodal::reparseBackendTarget(declarationTarget, *configuration)))
    return fail("valid target declaration names were rejected");
  for (const auto &mutation : {std::make_pair("Names(p, n)", "Names(input, n)"),
                               std::make_pair("Names(p, n)", "Names(p,, n)"),
                               std::make_pair("inout p, n;", "inout input, n;"),
                               std::make_pair("electrical p, n;", "electrical p, analog;"),
                               std::make_pair("real gain =", "real parameter =")}) {
    std::string invalid = declarationTarget;
    auto position = invalid.find(mutation.first);
    if (position == std::string::npos)
      return fail("target declaration mutation anchor missing");
    invalid.replace(position, std::string(mutation.first).size(), mutation.second);
    if (mlir::succeeded(nodal::reparseBackendTarget(invalid, *configuration)))
      return fail("reserved or empty target declaration passed independent reparse");
  }

  const std::string hierarchyTarget =
      "module Leaf(vin, vout);\ninput vin;\noutput vout;\nelectrical vin, vout;\n"
      "parameter real gain = 2;\nendmodule\n"
      "module Top(vin, vout);\ninput vin;\noutput vout;\nelectrical vin, vout;\n"
      "parameter real rootGain = 4;\n"
      "Leaf #(.gain(((rootGain + 1) * 2))) child(.vin(vin), .vout(vout));\nendmodule\n";
  if (mlir::failed(nodal::reparseBackendTarget(hierarchyTarget, *configuration)))
    return fail("valid named scalar hierarchy failed independent reparse");
  for (const auto &mutation :
       {std::make_pair(".vout(vout)", ".unknown(vout)"), std::make_pair(", .vout(vout)", ""),
        std::make_pair(".vin(vin)", ".vin(foreign)"),
        std::make_pair(".gain(((rootGain + 1) * 2))", ".missing(rootGain)"),
        std::make_pair("parameter real gain = 2", "localparam real gain = 2"),
        std::make_pair("Leaf #(", "Top #("),
        std::make_pair("module Leaf(vin, vout);", "module Missing(vin, vout);")}) {
    std::string invalid = hierarchyTarget;
    auto position = invalid.find(mutation.first);
    if (position == std::string::npos)
      return fail("hierarchy target mutation anchor missing");
    invalid.replace(position, std::string(mutation.first).size(), mutation.second);
    if (mlir::succeeded(nodal::reparseBackendTarget(invalid, *configuration)))
      return fail("malformed hierarchy passed independent reparse");
  }

  auto reserved = parse(context, kReservedModule);
  if (!reserved)
    return fail("could not parse the reserved-keyword fixture");
  std::string reservedOutput = "sentinel";
  llvm::raw_string_ostream reservedStream(reservedOutput);
  if (mlir::succeeded(nodal::emitBackend(*reserved, nodal::BackendKind::VerilogA, reservedStream)))
    return fail("reserved Verilog module name was accepted");
  reservedStream.flush();
  if (reservedOutput != "sentinel")
    return fail("reserved-name failure published partial output");

  auto substring = parse(context, kEndmoduleSubstringModule);
  if (!substring)
    return fail("could not parse the endmodule-substring fixture");
  std::string substringOutput;
  llvm::raw_string_ostream substringStream(substringOutput);
  if (mlir::failed(nodal::emitBackend(*substring, nodal::BackendKind::VerilogA, substringStream)))
    return fail("valid identifier containing endmodule was rejected");
  substringStream.flush();
  if (substringOutput.find("module myendmoduleBlock;") == std::string::npos)
    return fail("endmodule-substring module was not emitted");

  auto malformed = parse(context, kMalformedLayout);
  if (!malformed)
    return fail("could not parse the malformed-layout fixture");
  std::string malformedOutput = "sentinel";
  llvm::raw_string_ostream malformedStream(malformedOutput);
  if (mlir::succeeded(
          nodal::emitBackend(*malformed, nodal::BackendKind::VerilogA, malformedStream)))
    return fail("non-string profile-owned layout was accepted");
  malformedStream.flush();
  if (malformedOutput != "sentinel")
    return fail("malformed profile setting published partial output");

  auto unsupported = parse(context, kUnsupportedModule);
  if (!unsupported)
    return fail("could not parse the unsupported-operation fixture");
  std::string unchanged = "sentinel";
  llvm::raw_string_ostream unsupportedStream(unchanged);
  if (mlir::succeeded(
          nodal::emitBackend(*unsupported, nodal::BackendKind::VerilogA, unsupportedStream)))
    return fail("unsupported operation was accepted");
  unsupportedStream.flush();
  if (unchanged != "sentinel")
    return fail("failed capability checking published partial output");

  auto generated = parse(context, kGeneratedContributionModule);
  if (!generated)
    return fail("could not parse the fixed generated contribution fixture");
  std::string generatedOutput;
  llvm::raw_string_ostream generatedStream(generatedOutput);
  if (mlir::failed(nodal::emitBackend(*generated, nodal::BackendKind::VerilogA, generatedStream)))
    return fail("fixed generated contribution emission failed");
  generatedStream.flush();
  if (generatedOutput.find("  genvar generate_0_index;\n") == std::string::npos ||
      generatedOutput.find("    for (generate_0_index = 0; generate_0_index < 2; "
                           "generate_0_index = generate_0_index + 1) begin : generate_0\n") ==
          std::string::npos ||
      generatedOutput.find("    V(p, n) <+ 1;\n") == std::string::npos ||
      generatedOutput.find("    I(p, n) <+ 1;\n") == std::string::npos)
    return fail("fixed generated contribution target lost its loop, owner, or equations");
  if (generatedOutput.find("V(p, n) <+ 1;\n    V(p, n) <+ 1;") != std::string::npos)
    return fail("fixed generated contribution was eagerly unrolled");
  for (const auto &mutation :
       {std::make_pair("  genvar generate_0_index;\n", ""),
        std::make_pair("generate_0_index + 1) begin", "generate_0_index + 0) begin"),
        std::make_pair("generate_0_index < 2", "generate_0_index <= 2"),
        std::make_pair("begin : generate_0", "begin : for")}) {
    std::string invalid = generatedOutput;
    auto position = invalid.find(mutation.first);
    if (position == std::string::npos)
      return fail("generated target mutation anchor missing");
    invalid.replace(position, std::string(mutation.first).size(), mutation.second);
    if (mlir::succeeded(nodal::reparseBackendTarget(invalid, *configuration)))
      return fail("malformed generated target passed independent reparse");
  }

  auto generatedDdt = parse(context, kGeneratedDdtContributionModule);
  if (!generatedDdt)
    return fail("could not parse the generated ddt contribution fixture");
  std::string generatedDdtOutput;
  llvm::raw_string_ostream generatedDdtStream(generatedDdtOutput);
  if (mlir::failed(
          nodal::emitBackend(*generatedDdt, nodal::BackendKind::VerilogA, generatedDdtStream)))
    return fail("generated ddt contribution emission failed");
  generatedDdtStream.flush();
  if (generatedDdtOutput.find("V(p, n) <+ ddt(V(p, n));\n") == std::string::npos)
    return fail("generated ddt contribution lost its owned operator expression");
  auto forgedDdtOwner = parse(context, kGeneratedDdtContributionModule);
  if (!forgedDdtOwner)
    return fail("could not parse the generated ddt owner-mutation fixture");
  mlir::Operation *forgedDdt = nullptr;
  forgedDdtOwner->walk([&](mlir::Operation *operation) {
    if (!forgedDdt && operation->getName().getStringRef() == "nodal.analog_ddt")
      forgedDdt = operation;
  });
  if (!forgedDdt)
    return fail("generated ddt owner-mutation anchor missing");
  forgedDdt->setAttr("generated_owner",
                     mlir::StringAttr::get(&context, "GeneratedDdtContribution.forged"));
  std::string forgedOwnerOutput = "sentinel";
  llvm::raw_string_ostream forgedOwnerStream(forgedOwnerOutput);
  if (mlir::succeeded(
          nodal::emitBackend(*forgedDdtOwner, nodal::BackendKind::VerilogA, forgedOwnerStream)))
    return fail("forged generated ddt owner passed backend verification");
  forgedOwnerStream.flush();
  if (forgedOwnerOutput != "sentinel")
    return fail("forged generated ddt owner published partial output");

  for (const auto &mutation : {std::make_pair("%branch, %derivative", "%branch, %voltage")}) {
    std::string invalidSource = kGeneratedDdtContributionModule.str();
    auto position = invalidSource.find(mutation.first);
    if (position == std::string::npos)
      return fail("generated ddt source mutation anchor missing");
    invalidSource.replace(position, std::string(mutation.first).size(), mutation.second);
    auto invalidModule = parse(context, invalidSource);
    if (!invalidModule)
      return fail("generated ddt source mutation did not parse");
    std::string rejected = "sentinel";
    llvm::raw_string_ostream rejectedStream(rejected);
    if (mlir::succeeded(
            nodal::emitBackend(*invalidModule, nodal::BackendKind::VerilogA, rejectedStream)))
      return fail("invalid generated ddt source passed backend verification");
    rejectedStream.flush();
    if (rejected != "sentinel")
      return fail("invalid generated ddt source published partial output");
  }

  auto generatedIdt = parse(context, kGeneratedIdtContributionModule);
  if (!generatedIdt)
    return fail("could not parse the generated idt contribution fixture");
  std::string generatedIdtOutput;
  llvm::raw_string_ostream generatedIdtStream(generatedIdtOutput);
  if (mlir::failed(
          nodal::emitBackend(*generatedIdt, nodal::BackendKind::VerilogA, generatedIdtStream)))
    return fail("generated idt contribution emission failed");
  generatedIdtStream.flush();
  if (generatedIdtOutput.find("V(p, n) <+ idt(1);\n") == std::string::npos)
    return fail("generated idt contribution lost its owned stateful expression");

  for (const auto &[attribute, value] :
       {std::make_pair("generated_owner", "GeneratedIdtContribution.forged"),
        std::make_pair("state_id", "GeneratedIdtContribution.integral.forged")}) {
    auto forged = parse(context, kGeneratedIdtContributionModule);
    if (!forged)
      return fail("could not parse the generated idt mutation fixture");
    mlir::Operation *integral = nullptr;
    forged->walk([&](mlir::Operation *operation) {
      if (!integral && operation->getName().getStringRef() == "nodal.analog_idt")
        integral = operation;
    });
    if (!integral)
      return fail("generated idt mutation anchor missing");
    integral->setAttr(attribute, mlir::StringAttr::get(&context, value));
    std::string rejected = "sentinel";
    llvm::raw_string_ostream rejectedStream(rejected);
    if (mlir::succeeded(nodal::emitBackend(*forged, nodal::BackendKind::VerilogA, rejectedStream)))
      return fail("forged generated idt ownership passed backend verification");
    rejectedStream.flush();
    if (rejected != "sentinel")
      return fail("forged generated idt ownership published partial output");
  }

  {
    std::string invalidSource = kGeneratedIdtContributionModule.str();
    auto position = invalidSource.find("%branch, %integral");
    if (position == std::string::npos)
      return fail("generated idt source mutation anchor missing");
    invalidSource.replace(position, std::string("%branch, %integral").size(), "%branch, %rate");
    auto invalidModule = parse(context, invalidSource);
    if (!invalidModule)
      return fail("generated idt source mutation did not parse");
    std::string rejected = "sentinel";
    llvm::raw_string_ostream rejectedStream(rejected);
    if (mlir::succeeded(
            nodal::emitBackend(*invalidModule, nodal::BackendKind::VerilogA, rejectedStream)))
      return fail("unused generated idt state passed backend verification");
    rejectedStream.flush();
    if (rejected != "sentinel")
      return fail("unused generated idt state published partial output");
  }

  auto generatedTransition = parse(context, kGeneratedTransitionContributionModule);
  if (!generatedTransition)
    return fail("could not parse the generated transition contribution fixture");
  std::string generatedTransitionOutput;
  llvm::raw_string_ostream generatedTransitionStream(generatedTransitionOutput);
  if (mlir::failed(nodal::emitBackend(*generatedTransition, nodal::BackendKind::VerilogA,
                                      generatedTransitionStream)))
    return fail("generated transition contribution emission failed");
  generatedTransitionStream.flush();
  if (generatedTransitionOutput.find("V(p, n) <+ transition(LEVEL);\n") == std::string::npos ||
      generatedTransitionOutput.find("real waveform_") != std::string::npos)
    return fail("generated transition lost its single owned per-occurrence expression");

  for (const auto &[attribute, value] :
       {std::make_pair("generated_owner", "GeneratedTransitionContribution.forged"),
        std::make_pair("state_id", "GeneratedTransitionContribution.smoothed.forged")}) {
    auto forged = parse(context, kGeneratedTransitionContributionModule);
    if (!forged)
      return fail("could not parse the generated transition mutation fixture");
    mlir::Operation *transition = nullptr;
    forged->walk([&](mlir::Operation *operation) {
      if (!transition && operation->getName().getStringRef() == "nodal.analog_transition")
        transition = operation;
    });
    if (!transition)
      return fail("generated transition mutation anchor missing");
    transition->setAttr(attribute, mlir::StringAttr::get(&context, value));
    std::string rejected = "sentinel";
    llvm::raw_string_ostream rejectedStream(rejected);
    if (mlir::succeeded(nodal::emitBackend(*forged, nodal::BackendKind::VerilogA, rejectedStream)))
      return fail("forged generated transition ownership passed backend verification");
    rejectedStream.flush();
    if (rejected != "sentinel")
      return fail("forged generated transition ownership published partial output");
  }

  for (const auto &mutation :
       {std::make_pair("%branch, %smoothed", "%branch, %input"),
        std::make_pair(
            "        \"nodal.contribute\"(%branch, %smoothed)",
            "        \"nodal.contribute\"(%branch, %smoothed) <{kind = \"potential\", "
            "generated_owner = \"GeneratedTransitionContribution.generate_0\", "
            "generated_induction = \"GeneratedTransitionContribution.generate_0.index\", "
            "metadata = {}}> : (!nodal.branch<\"electrical\">, "
            "!nodal.quantity<\"real\", \"voltage\">) -> ()\n"
            "        \"nodal.contribute\"(%branch, %smoothed)")}) {
    std::string invalidSource = kGeneratedTransitionContributionModule.str();
    auto position = invalidSource.find(mutation.first);
    if (position == std::string::npos)
      return fail("generated transition consumer mutation anchor missing");
    invalidSource.replace(position, std::string(mutation.first).size(), mutation.second);
    auto invalidModule = parse(context, invalidSource);
    if (!invalidModule)
      return fail("generated transition consumer mutation did not parse");
    std::string rejected = "sentinel";
    llvm::raw_string_ostream rejectedStream(rejected);
    if (mlir::succeeded(
            nodal::emitBackend(*invalidModule, nodal::BackendKind::VerilogA, rejectedStream)))
      return fail("invalid generated transition consumer passed backend verification");
    rejectedStream.flush();
    if (rejected != "sentinel")
      return fail("invalid generated transition consumer published partial output");
  }

  auto generatedSlew = parse(context, kGeneratedSlewContributionModule);
  if (!generatedSlew)
    return fail("could not parse the generated slew contribution fixture");
  std::string generatedSlewOutput;
  llvm::raw_string_ostream generatedSlewStream(generatedSlewOutput);
  if (mlir::failed(
          nodal::emitBackend(*generatedSlew, nodal::BackendKind::VerilogA, generatedSlewStream)))
    return fail("generated slew contribution emission failed");
  generatedSlewStream.flush();
  if (generatedSlewOutput.find("V(p, n) <+ slew(LEVEL);\n") == std::string::npos ||
      generatedSlewOutput.find("real waveform_") != std::string::npos)
    return fail("generated slew lost its single owned per-occurrence expression");

  for (const auto &[attribute, value] :
       {std::make_pair("generated_owner", "GeneratedSlewContribution.forged"),
        std::make_pair("state_id", "GeneratedSlewContribution.limited.forged")}) {
    auto forged = parse(context, kGeneratedSlewContributionModule);
    if (!forged)
      return fail("could not parse the generated slew mutation fixture");
    mlir::Operation *slew = nullptr;
    forged->walk([&](mlir::Operation *operation) {
      if (!slew && operation->getName().getStringRef() == "nodal.analog_slew")
        slew = operation;
    });
    if (!slew)
      return fail("generated slew mutation anchor missing");
    slew->setAttr(attribute, mlir::StringAttr::get(&context, value));
    std::string rejected = "sentinel";
    llvm::raw_string_ostream rejectedStream(rejected);
    if (mlir::succeeded(nodal::emitBackend(*forged, nodal::BackendKind::VerilogA, rejectedStream)))
      return fail("forged generated slew ownership passed backend verification");
    rejectedStream.flush();
    if (rejected != "sentinel")
      return fail("forged generated slew ownership published partial output");
  }

  for (const auto &mutation :
       {std::make_pair("%branch, %limited", "%branch, %input"),
        std::make_pair("        \"nodal.contribute\"(%branch, %limited)",
                       "        \"nodal.contribute\"(%branch, %limited) <{kind = \"potential\", "
                       "generated_owner = \"GeneratedSlewContribution.generate_0\", "
                       "generated_induction = \"GeneratedSlewContribution.generate_0.index\", "
                       "metadata = {}}> : (!nodal.branch<\"electrical\">, "
                       "!nodal.quantity<\"real\", \"voltage\">) -> ()\n"
                       "        \"nodal.contribute\"(%branch, %limited)")}) {
    std::string invalidSource = kGeneratedSlewContributionModule.str();
    auto position = invalidSource.find(mutation.first);
    if (position == std::string::npos)
      return fail("generated slew consumer mutation anchor missing");
    invalidSource.replace(position, std::string(mutation.first).size(), mutation.second);
    auto invalidModule = parse(context, invalidSource);
    if (!invalidModule)
      return fail("generated slew consumer mutation did not parse");
    std::string rejected = "sentinel";
    llvm::raw_string_ostream rejectedStream(rejected);
    if (mlir::succeeded(
            nodal::emitBackend(*invalidModule, nodal::BackendKind::VerilogA, rejectedStream)))
      return fail("invalid generated slew consumer passed backend verification");
    rejectedStream.flush();
    if (rejected != "sentinel")
      return fail("invalid generated slew consumer published partial output");
  }

  auto generatedAbsdelay = parse(context, kGeneratedAbsdelayContributionModule);
  if (!generatedAbsdelay)
    return fail("could not parse the generated absdelay contribution fixture");
  std::string generatedAbsdelayOutput;
  llvm::raw_string_ostream generatedAbsdelayStream(generatedAbsdelayOutput);
  if (mlir::failed(nodal::emitBackend(*generatedAbsdelay, nodal::BackendKind::VerilogA,
                                      generatedAbsdelayStream)))
    return fail("generated absdelay contribution emission failed");
  generatedAbsdelayStream.flush();
  if (generatedAbsdelayOutput.find("V(p, n) <+ absdelay(LEVEL, DELAY);\n") == std::string::npos ||
      generatedAbsdelayOutput.find("real waveform_") != std::string::npos)
    return fail("generated absdelay lost its single owned per-occurrence expression");

  for (const auto &[attribute, value] :
       {std::make_pair("generated_owner", "GeneratedAbsdelayContribution.forged"),
        std::make_pair("state_id", "GeneratedAbsdelayContribution.delayed.forged")}) {
    auto forged = parse(context, kGeneratedAbsdelayContributionModule);
    if (!forged)
      return fail("could not parse the generated absdelay mutation fixture");
    mlir::Operation *absdelay = nullptr;
    forged->walk([&](mlir::Operation *operation) {
      if (!absdelay && operation->getName().getStringRef() == "nodal.analog_absdelay")
        absdelay = operation;
    });
    if (!absdelay)
      return fail("generated absdelay mutation anchor missing");
    absdelay->setAttr(attribute, mlir::StringAttr::get(&context, value));
    std::string rejected = "sentinel";
    llvm::raw_string_ostream rejectedStream(rejected);
    if (mlir::succeeded(nodal::emitBackend(*forged, nodal::BackendKind::VerilogA, rejectedStream)))
      return fail("forged generated absdelay ownership passed backend verification");
    rejectedStream.flush();
    if (rejected != "sentinel")
      return fail("forged generated absdelay ownership published partial output");
  }

  for (const auto &mutation :
       {std::make_pair("%branch, %delayed", "%branch, %input"),
        std::make_pair("        \"nodal.contribute\"(%branch, %delayed)",
                       "        \"nodal.contribute\"(%branch, %delayed) <{kind = \"potential\", "
                       "generated_owner = \"GeneratedAbsdelayContribution.generate_0\", "
                       "generated_induction = \"GeneratedAbsdelayContribution.generate_0.index\", "
                       "metadata = {}}> : (!nodal.branch<\"electrical\">, "
                       "!nodal.quantity<\"real\", \"voltage\">) -> ()\n"
                       "        \"nodal.contribute\"(%branch, %delayed)")}) {
    std::string invalidSource = kGeneratedAbsdelayContributionModule.str();
    auto position = invalidSource.find(mutation.first);
    if (position == std::string::npos)
      return fail("generated absdelay consumer mutation anchor missing");
    invalidSource.replace(position, std::string(mutation.first).size(), mutation.second);
    auto invalidModule = parse(context, invalidSource);
    if (!invalidModule)
      return fail("generated absdelay consumer mutation did not parse");
    std::string rejected = "sentinel";
    llvm::raw_string_ostream rejectedStream(rejected);
    if (mlir::succeeded(
            nodal::emitBackend(*invalidModule, nodal::BackendKind::VerilogA, rejectedStream)))
      return fail("invalid generated absdelay consumer passed backend verification");
    rejectedStream.flush();
    if (rejected != "sentinel")
      return fail("invalid generated absdelay consumer published partial output");
  }

  auto symbolicGenerated = parse(context, kSymbolicGeneratedContributionModule);
  if (!symbolicGenerated)
    return fail("could not parse the symbolic generated contribution fixture");
  std::string symbolicGeneratedOutput;
  llvm::raw_string_ostream symbolicGeneratedStream(symbolicGeneratedOutput);
  if (mlir::failed(nodal::emitBackend(*symbolicGenerated, nodal::BackendKind::VerilogA,
                                      symbolicGeneratedStream)))
    return fail("symbolic generated contribution emission failed");
  symbolicGeneratedStream.flush();
  if (symbolicGeneratedOutput.find("parameter integer COUNT = 4 from [1:8];\n") ==
          std::string::npos ||
      symbolicGeneratedOutput.find("generate_0_index < COUNT") == std::string::npos ||
      symbolicGeneratedOutput.find("V(p, n) <+ 1;\n") == std::string::npos)
    return fail("symbolic generated contribution lost its parameterized target loop");
  std::string unknownGeneratedBound = symbolicGeneratedOutput;
  auto symbolicPosition = unknownGeneratedBound.find("generate_0_index < COUNT");
  if (symbolicPosition == std::string::npos)
    return fail("symbolic generated bound mutation anchor missing");
  unknownGeneratedBound.replace(symbolicPosition, std::string("generate_0_index < COUNT").size(),
                                "generate_0_index < MISSING");
  if (mlir::succeeded(nodal::reparseBackendTarget(unknownGeneratedBound, *configuration)))
    return fail("undeclared symbolic generated bound passed independent reparse");

  auto compoundGenerated = parse(context, kCompoundGeneratedContributionModule);
  if (!compoundGenerated)
    return fail("could not parse the compound generated contribution fixture");
  std::string compoundGeneratedOutput;
  llvm::raw_string_ostream compoundGeneratedStream(compoundGeneratedOutput);
  if (mlir::failed(nodal::emitBackend(*compoundGenerated, nodal::BackendKind::VerilogA,
                                      compoundGeneratedStream)))
    return fail("compound generated contribution emission failed");
  compoundGeneratedStream.flush();
  if (compoundGeneratedOutput.find("parameter integer COUNT = 3 from [1:4];\n") ==
          std::string::npos ||
      compoundGeneratedOutput.find("parameter integer EXTRA = 1 from [1:2];\n") ==
          std::string::npos ||
      compoundGeneratedOutput.find("generate_0_index < (COUNT + EXTRA)") == std::string::npos ||
      compoundGeneratedOutput.find("V(p, n) <+ 1;\n") == std::string::npos)
    return fail("compound generated contribution lost its structural expression target loop");
  for (const auto &mutation :
       {std::make_pair("classification = \"structural\"", "classification = \"ordinary\""),
        std::make_pair("operator_name = \"add\"}>",
                       "operator_name = \"add\"}> {unit = @forged}")}) {
    std::string invalidSource = kCompoundGeneratedContributionModule.str();
    auto position = invalidSource.find(mutation.first);
    if (position == std::string::npos)
      return fail("compound generated source mutation anchor missing");
    invalidSource.replace(position, std::string(mutation.first).size(), mutation.second);
    auto invalidModule = parse(context, invalidSource);
    if (!invalidModule)
      return fail("compound generated source mutation did not parse");
    std::string rejected = "sentinel";
    llvm::raw_string_ostream rejectedStream(rejected);
    if (mlir::succeeded(
            nodal::emitBackend(*invalidModule, nodal::BackendKind::VerilogA, rejectedStream)))
      return fail("invalid compound generated source passed backend verification");
    rejectedStream.flush();
    if (rejected != "sentinel")
      return fail("invalid compound generated source published partial output");
  }
  for (const auto &mutation : {std::make_pair("(COUNT + EXTRA)", "(COUNT + MISSING)"),
                               std::make_pair("(COUNT + EXTRA)", "(COUNT && EXTRA)"),
                               std::make_pair("(COUNT + EXTRA)", "(COUNT + EXTRA")}) {
    std::string invalid = compoundGeneratedOutput;
    auto position = invalid.find(mutation.first);
    if (position == std::string::npos)
      return fail("compound generated target mutation anchor missing");
    invalid.replace(position, std::string(mutation.first).size(), mutation.second);
    if (mlir::succeeded(nodal::reparseBackendTarget(invalid, *configuration)))
      return fail("malformed compound generated target passed independent reparse");
  }

  RejectingReparseHooks rejectingHooks;
  std::string rejected = "sentinel";
  llvm::raw_string_ostream rejectedStream(rejected);
  if (mlir::succeeded(nodal::emitBackend(*analog, nodal::BackendKind::VerilogA, rejectedStream,
                                         &rejectingHooks)))
    return fail("rejecting reparse hook was ignored");
  rejectedStream.flush();
  if (rejected != "sentinel")
    return fail("failed target reparse published partial output");

  return 0;
}
