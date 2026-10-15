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

constexpr llvm::StringLiteral kGeneratedNodeArrayModule = R"mlir(
module attributes {
  nodal.backend.check_profile = "release",
  nodal.backend.materialization = "safe-inline",
  nodal.backend.naming = "semantic",
  nodal.backend.profile = "verilog-a",
  nodal.backend.shaped_layout = "scalar-or-flat",
  nodal.target.profile = "analog"
} {
  "nodal.module"() <{metadata = {root = true}, sym_name = "GeneratedNodeArray"}> ({
  ^bb0:
    "nodal.generate"() <{induction = "generate_0_index", lower = 0 : i64, upper = 3 : i64, step = 1 : i64, region_id = "GeneratedNodeArray.generate_0", induction_path = "GeneratedNodeArray.generate_0.index", metadata = {maximum_trip_count = 3 : i64}}> ({
    ^bb0:
      %tap = "nodal.node"() <{name = "tap", source_path = "GeneratedNodeArray.tap", generated_owner = "GeneratedNodeArray.generate_0", generated_induction = "GeneratedNodeArray.generate_0.index", metadata = {allow_floating = true, declaration_kind = "analog-node", generated_owner = "GeneratedNodeArray.generate_0", induction = "GeneratedNodeArray.generate_0.index"}}> : () -> !nodal.terminal<"electrical">
    }) : () -> ()
  }) : () -> ()
}
)mlir";

constexpr llvm::StringLiteral kGeneratedNodeContributionModule = R"mlir(
module attributes {
  nodal.backend.check_profile = "release",
  nodal.backend.materialization = "safe-inline",
  nodal.backend.naming = "semantic",
  nodal.backend.profile = "verilog-a",
  nodal.backend.shaped_layout = "scalar-or-flat",
  nodal.target.profile = "analog"
} {
  "nodal.module"() <{metadata = {root = true}, sym_name = "GeneratedNodeContribution"}> ({
  ^bb0:
    %p = "nodal.terminal"() <{metadata = {declaration_kind = "analog-inout"}, name = "p"}> : () -> !nodal.terminal<"electrical">
    "nodal.generate"() <{induction = "generate_0_index", lower = 0 : i64, upper = 3 : i64, step = 1 : i64, region_id = "GeneratedNodeContribution.generate_0", induction_path = "GeneratedNodeContribution.generate_0.index", metadata = {maximum_trip_count = 3 : i64}}> ({
    ^bb0:
      %tap = "nodal.node"() <{name = "tap", source_path = "GeneratedNodeContribution.tap", generated_owner = "GeneratedNodeContribution.generate_0", generated_induction = "GeneratedNodeContribution.generate_0.index", metadata = {allow_floating = true, declaration_kind = "analog-node", generated_owner = "GeneratedNodeContribution.generate_0", induction = "GeneratedNodeContribution.generate_0.index"}}> : () -> !nodal.terminal<"electrical">
      %branch = "nodal.branch"(%tap, %p) <{metadata = {generated_owner = "GeneratedNodeContribution.generate_0", generated_induction = "GeneratedNodeContribution.generate_0.index"}}> : (!nodal.terminal<"electrical">, !nodal.terminal<"electrical">) -> !nodal.branch<"electrical">
      "nodal.analog"() <{generated_owner = "GeneratedNodeContribution.generate_0", generated_induction = "GeneratedNodeContribution.generate_0.index", metadata = {}}> ({
      ^bb0:
        %potential = "nodal.real_literal"() <{value = 1.0 : f64, metadata = {}}> : () -> f64
        "nodal.contribute"(%branch, %potential) <{kind = "potential", generated_owner = "GeneratedNodeContribution.generate_0", generated_induction = "GeneratedNodeContribution.generate_0.index", metadata = {}}> : (!nodal.branch<"electrical">, f64) -> ()
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

constexpr llvm::StringLiteral kGeneratedAbstimeContributionModule = R"mlir(
module attributes {
  nodal.backend.check_profile = "release",
  nodal.backend.materialization = "safe-inline",
  nodal.backend.naming = "semantic",
  nodal.backend.profile = "verilog-a",
  nodal.backend.shaped_layout = "scalar-or-flat",
  nodal.target.profile = "analog"
} {
  "nodal.module"() <{metadata = {root = true}, sym_name = "GeneratedAbstimeContribution"}> ({
  ^bb0:
    %p = "nodal.terminal"() <{metadata = {declaration_kind = "analog-inout"}, name = "p"}> : () -> !nodal.terminal<"electrical">
    %n = "nodal.terminal"() <{metadata = {declaration_kind = "analog-inout"}, name = "n"}> : () -> !nodal.terminal<"electrical">
    %branch = "nodal.branch"(%p, %n) <{metadata = {}}> : (!nodal.terminal<"electrical">, !nodal.terminal<"electrical">) -> !nodal.branch<"electrical">
    "nodal.generate"() <{induction = "generate_0_index", lower = 0 : i64, upper = 2 : i64, step = 1 : i64, region_id = "GeneratedAbstimeContribution.generate_0", induction_path = "GeneratedAbstimeContribution.generate_0.index", metadata = {maximum_trip_count = 2 : i64}}> ({
    ^bb0:
      "nodal.analog"() <{generated_owner = "GeneratedAbstimeContribution.generate_0", generated_induction = "GeneratedAbstimeContribution.generate_0.index", metadata = {}}> ({
      ^bb0:
        %now = "nodal.analog_abstime"() <{analyses = ["ac", "dc", "initialization", "noise", "operating-point", "transient"], context = "contribution", generated_owner = "GeneratedAbstimeContribution.generate_0", generated_induction = "GeneratedAbstimeContribution.generate_0.index", input_continuity = "none", metadata = {identity = "now"}, operand_dimensions = [], operator_contract = "increment36", operator_id = "GeneratedAbstimeContribution.now", output_continuity = "continuous", owner = "GeneratedAbstimeContribution", result_dimension = "time"}> : () -> f64
        %scale = "nodal.real_literal"() <{value = 1.0 : f64, metadata = {}}> : () -> f64
        %value = "nodal.analog_mul"(%now, %scale) <{metadata = {}}> : (f64, f64) -> f64
        "nodal.contribute"(%branch, %value) <{kind = "potential", generated_owner = "GeneratedAbstimeContribution.generate_0", generated_induction = "GeneratedAbstimeContribution.generate_0.index", metadata = {}}> : (!nodal.branch<"electrical">, f64) -> ()
      }) : () -> ()
    }) : () -> ()
  }) : () -> ()
}
)mlir";

constexpr llvm::StringLiteral kGeneratedBoundStepModule = R"mlir(
module attributes {
  nodal.backend.check_profile = "release",
  nodal.backend.materialization = "safe-inline",
  nodal.backend.naming = "semantic",
  nodal.backend.profile = "verilog-a",
  nodal.backend.shaped_layout = "scalar-or-flat",
  nodal.target.profile = "analog"
} {
  "nodal.unit"() <{dimension = "time", metadata = {}, native_suffix = "", scale = 1.0 : f64, sym_name = "Second", symbol = "s"}> : () -> ()
  "nodal.module"() <{metadata = {root = true}, sym_name = "GeneratedBoundStep"}> ({
  ^bb0:
    "nodal.parameter"() <{classification = "ordinary", default_value = 1.0e-9 : f64, metadata = {}, parameter_kind = "real", sym_name = "LIMIT", type = f64, unit = @Second, variability = "fixed"}> : () -> ()
    "nodal.generate"() <{induction = "generate_0_index", lower = 0 : i64, upper = 2 : i64, step = 1 : i64, region_id = "GeneratedBoundStep.generate_0", induction_path = "GeneratedBoundStep.generate_0.index", metadata = {maximum_trip_count = 2 : i64}}> ({
    ^bb0:
      "nodal.analog"() <{generated_owner = "GeneratedBoundStep.generate_0", generated_induction = "GeneratedBoundStep.generate_0.index", metadata = {}}> ({
      ^bb0:
        %limit = "nodal.parameter_ref"() <{metadata = {}, parameter = @LIMIT}> : () -> !nodal.quantity<"real", "time">
        "nodal.analog_bound_step"(%limit) <{analyses = ["transient"], context = "legacy-analog", generated_owner = "GeneratedBoundStep.generate_0", generated_induction = "GeneratedBoundStep.generate_0.index", input_continuity = "none", metadata = {identity = "step"}, operand_dimensions = ["time"], operator_contract = "increment36", operator_id = "GeneratedBoundStep.step", output_continuity = "none", owner = "GeneratedBoundStep", result_dimension = "none"}> : (!nodal.quantity<"real", "time">) -> ()
      }) : () -> ()
    }) : () -> ()
  }) : () -> ()
}
)mlir";

constexpr llvm::StringLiteral kGeneratedLaplaceContributionModule = R"mlir(
module attributes {
  nodal.backend.check_profile = "release",
  nodal.backend.materialization = "safe-inline",
  nodal.backend.naming = "semantic",
  nodal.backend.profile = "verilog-a",
  nodal.backend.shaped_layout = "scalar-or-flat",
  nodal.target.profile = "analog"
} {
  "nodal.module"() <{metadata = {root = true}, sym_name = "GeneratedLaplaceContribution"}> ({
  ^bb0:
    %p = "nodal.terminal"() <{metadata = {declaration_kind = "analog-inout"}, name = "p"}> : () -> !nodal.terminal<"electrical">
    %n = "nodal.terminal"() <{metadata = {declaration_kind = "analog-inout"}, name = "n"}> : () -> !nodal.terminal<"electrical">
    %branch = "nodal.branch"(%p, %n) <{metadata = {}}> : (!nodal.terminal<"electrical">, !nodal.terminal<"electrical">) -> !nodal.branch<"electrical">
    "nodal.generate"() <{induction = "generate_0_index", lower = 0 : i64, upper = 2 : i64, step = 1 : i64, region_id = "GeneratedLaplaceContribution.generate_0", induction_path = "GeneratedLaplaceContribution.generate_0.index", metadata = {maximum_trip_count = 2 : i64}}> ({
    ^bb0:
      "nodal.analog"() <{generated_owner = "GeneratedLaplaceContribution.generate_0", generated_induction = "GeneratedLaplaceContribution.generate_0.index", metadata = {}}> ({
      ^bb0:
        %input = "nodal.access"(%branch) <{kind = "potential", metadata = {}}> : (!nodal.branch<"electrical">) -> f64
        %one = "nodal.real_literal"() <{value = 1.0 : f64, metadata = {}}> : () -> f64
        %filtered = "nodal.analog_transfer"(%input, %one, %one) <{coefficient_order = "ascending_s", contract_version = "1", denominator_size = 1 : i64, generated_owner = "GeneratedLaplaceContribution.generate_0", generated_induction = "GeneratedLaplaceContribution.generate_0.index", initialization = "simulator-default", metadata = {semantic_path = "GeneratedLaplaceContribution.filtered"}, numerator_size = 1 : i64, operator_id = "GeneratedLaplaceContribution.filtered", owner = "GeneratedLaplaceContribution", result_dimension = "voltage", state_id = "GeneratedLaplaceContribution.filtered.state", transfer_kind = "laplace_nd"}> : (f64, f64, f64) -> f64
        "nodal.contribute"(%branch, %filtered) <{kind = "potential", generated_owner = "GeneratedLaplaceContribution.generate_0", generated_induction = "GeneratedLaplaceContribution.generate_0.index", metadata = {}}> : (!nodal.branch<"electrical">, f64) -> ()
      }) : () -> ()
    }) : () -> ()
  }) : () -> ()
}
)mlir";

constexpr llvm::StringLiteral kGeneratedZiContributionModule = R"mlir(
module attributes {
  nodal.backend.check_profile = "release",
  nodal.backend.materialization = "safe-inline",
  nodal.backend.naming = "semantic",
  nodal.backend.profile = "verilog-a",
  nodal.backend.shaped_layout = "scalar-or-flat",
  nodal.target.profile = "analog"
} {
  "nodal.module"() <{metadata = {root = true}, sym_name = "GeneratedZiContribution"}> ({
  ^bb0:
    %p = "nodal.terminal"() <{metadata = {declaration_kind = "analog-inout"}, name = "p"}> : () -> !nodal.terminal<"electrical">
    %n = "nodal.terminal"() <{metadata = {declaration_kind = "analog-inout"}, name = "n"}> : () -> !nodal.terminal<"electrical">
    %branch = "nodal.branch"(%p, %n) <{metadata = {}}> : (!nodal.terminal<"electrical">, !nodal.terminal<"electrical">) -> !nodal.branch<"electrical">
    "nodal.generate"() <{induction = "generate_0_index", lower = 0 : i64, upper = 2 : i64, step = 1 : i64, region_id = "GeneratedZiContribution.generate_0", induction_path = "GeneratedZiContribution.generate_0.index", metadata = {maximum_trip_count = 2 : i64}}> ({
    ^bb0:
      "nodal.analog"() <{generated_owner = "GeneratedZiContribution.generate_0", generated_induction = "GeneratedZiContribution.generate_0.index", metadata = {}}> ({
      ^bb0:
        %input = "nodal.access"(%branch) <{kind = "potential", metadata = {}}> : (!nodal.branch<"electrical">) -> f64
        %one = "nodal.real_literal"() <{value = 1.0 : f64, metadata = {}}> : () -> f64
        %interval = "nodal.real_literal"() <{value = 1.0e-9 : f64, metadata = {unit = "s"}}> : () -> f64
        %filtered = "nodal.analog_transfer"(%input, %one, %one, %interval) <{coefficient_order = "ascending_z_inverse", contract_version = "1", denominator_size = 1 : i64, generated_owner = "GeneratedZiContribution.generate_0", generated_induction = "GeneratedZiContribution.generate_0.index", initialization = "simulator-default", metadata = {semantic_path = "GeneratedZiContribution.filtered"}, numerator_size = 1 : i64, operator_id = "GeneratedZiContribution.filtered", owner = "GeneratedZiContribution", result_dimension = "voltage", state_id = "GeneratedZiContribution.filtered.state", transfer_kind = "zi_nd"}> : (f64, f64, f64, f64) -> f64
        "nodal.contribute"(%branch, %filtered) <{kind = "potential", generated_owner = "GeneratedZiContribution.generate_0", generated_induction = "GeneratedZiContribution.generate_0.index", metadata = {}}> : (!nodal.branch<"electrical">, f64) -> ()
      }) : () -> ()
    }) : () -> ()
  }) : () -> ()
}
)mlir";

constexpr llvm::StringLiteral kGeneratedWhiteNoiseContributionModule = R"mlir(
module attributes {
  nodal.backend.check_profile = "release",
  nodal.backend.materialization = "safe-inline",
  nodal.backend.naming = "semantic",
  nodal.backend.profile = "verilog-a",
  nodal.backend.shaped_layout = "scalar-or-flat",
  nodal.target.profile = "analog"
} {
  "nodal.module"() <{metadata = {root = true}, sym_name = "GeneratedWhiteNoiseContribution"}> ({
  ^bb0:
    %p = "nodal.terminal"() <{metadata = {declaration_kind = "analog-inout"}, name = "p"}> : () -> !nodal.terminal<"electrical">
    %n = "nodal.terminal"() <{metadata = {declaration_kind = "analog-inout"}, name = "n"}> : () -> !nodal.terminal<"electrical">
    %branch = "nodal.branch"(%p, %n) <{metadata = {}}> : (!nodal.terminal<"electrical">, !nodal.terminal<"electrical">) -> !nodal.branch<"electrical">
    "nodal.generate"() <{induction = "generate_0_index", lower = 0 : i64, upper = 2 : i64, step = 1 : i64, region_id = "GeneratedWhiteNoiseContribution.generate_0", induction_path = "GeneratedWhiteNoiseContribution.generate_0.index", metadata = {maximum_trip_count = 2 : i64}}> ({
    ^bb0:
      "nodal.analog"() <{generated_owner = "GeneratedWhiteNoiseContribution.generate_0", generated_induction = "GeneratedWhiteNoiseContribution.generate_0.index", metadata = {}}> ({
      ^bb0:
        %amp = "nodal.real_literal"() <{value = 1.0 : f64, metadata = {unit = "A"}}> : () -> f64
        %second = "nodal.real_literal"() <{value = 1.0 : f64, metadata = {unit = "s"}}> : () -> f64
        %squared = "nodal.analog_mul"(%amp, %amp) <{metadata = {}}> : (f64, f64) -> f64
        %density = "nodal.analog_mul"(%squared, %second) <{metadata = {}}> : (f64, f64) -> f64
        %noise = "nodal.analog_noise"(%density) <{analyses = ["noise"], contract_version = "1", correlation = "independent", generated_owner = "GeneratedWhiteNoiseContribution.generate_0", generated_induction = "GeneratedWhiteNoiseContribution.generate_0.index", metadata = {semantic_path = "GeneratedWhiteNoiseContribution.noise"}, noise_kind = "white", noise_name = "generated white", owner = "GeneratedWhiteNoiseContribution", result_dimension = "current", source_id = "GeneratedWhiteNoiseContribution.noise"}> : (f64) -> f64
        "nodal.contribute"(%branch, %noise) <{kind = "flow", generated_owner = "GeneratedWhiteNoiseContribution.generate_0", generated_induction = "GeneratedWhiteNoiseContribution.generate_0.index", metadata = {}}> : (!nodal.branch<"electrical">, f64) -> ()
      }) : () -> ()
    }) : () -> ()
  }) : () -> ()
}
)mlir";

constexpr llvm::StringLiteral kGeneratedFlickerNoiseContributionModule = R"mlir(
module attributes {
  nodal.backend.check_profile = "release",
  nodal.backend.materialization = "safe-inline",
  nodal.backend.naming = "semantic",
  nodal.backend.profile = "verilog-a",
  nodal.backend.shaped_layout = "scalar-or-flat",
  nodal.target.profile = "analog"
} {
  "nodal.module"() <{metadata = {root = true}, sym_name = "GeneratedFlickerNoiseContribution"}> ({
  ^bb0:
    %p = "nodal.terminal"() <{metadata = {declaration_kind = "analog-inout"}, name = "p"}> : () -> !nodal.terminal<"electrical">
    %n = "nodal.terminal"() <{metadata = {declaration_kind = "analog-inout"}, name = "n"}> : () -> !nodal.terminal<"electrical">
    %branch = "nodal.branch"(%p, %n) <{metadata = {}}> : (!nodal.terminal<"electrical">, !nodal.terminal<"electrical">) -> !nodal.branch<"electrical">
    "nodal.generate"() <{induction = "generate_0_index", lower = 0 : i64, upper = 2 : i64, step = 1 : i64, region_id = "GeneratedFlickerNoiseContribution.generate_0", induction_path = "GeneratedFlickerNoiseContribution.generate_0.index", metadata = {maximum_trip_count = 2 : i64}}> ({
    ^bb0:
      "nodal.analog"() <{generated_owner = "GeneratedFlickerNoiseContribution.generate_0", generated_induction = "GeneratedFlickerNoiseContribution.generate_0.index", metadata = {}}> ({
      ^bb0:
        %amp = "nodal.real_literal"() <{value = 1.0 : f64, metadata = {unit = "A"}}> : () -> f64
        %second = "nodal.real_literal"() <{value = 1.0 : f64, metadata = {unit = "s"}}> : () -> f64
        %squared = "nodal.analog_mul"(%amp, %amp) <{metadata = {}}> : (f64, f64) -> f64
        %density = "nodal.analog_mul"(%squared, %second) <{metadata = {}}> : (f64, f64) -> f64
        %exponent = "nodal.real_literal"() <{value = 1.0 : f64, metadata = {}}> : () -> f64
        %noise = "nodal.analog_noise"(%density, %exponent) <{analyses = ["noise"], contract_version = "1", correlation = "independent", generated_owner = "GeneratedFlickerNoiseContribution.generate_0", generated_induction = "GeneratedFlickerNoiseContribution.generate_0.index", metadata = {semantic_path = "GeneratedFlickerNoiseContribution.noise"}, noise_kind = "flicker", noise_name = "generated flicker", owner = "GeneratedFlickerNoiseContribution", result_dimension = "current", source_id = "GeneratedFlickerNoiseContribution.noise"}> : (f64, f64) -> f64
        "nodal.contribute"(%branch, %noise) <{kind = "flow", generated_owner = "GeneratedFlickerNoiseContribution.generate_0", generated_induction = "GeneratedFlickerNoiseContribution.generate_0.index", metadata = {}}> : (!nodal.branch<"electrical">, f64) -> ()
      }) : () -> ()
    }) : () -> ()
  }) : () -> ()
}
)mlir";

constexpr llvm::StringLiteral kGeneratedTableNoiseContributionModule = R"mlir(
module attributes {
  nodal.backend.check_profile = "release",
  nodal.backend.materialization = "safe-inline",
  nodal.backend.naming = "semantic",
  nodal.backend.profile = "verilog-a",
  nodal.backend.shaped_layout = "scalar-or-flat",
  nodal.target.profile = "analog"
} {
  "nodal.module"() <{metadata = {root = true}, sym_name = "GeneratedTableNoiseContribution"}> ({
  ^bb0:
    %p = "nodal.terminal"() <{metadata = {declaration_kind = "analog-inout"}, name = "p"}> : () -> !nodal.terminal<"electrical">
    %n = "nodal.terminal"() <{metadata = {declaration_kind = "analog-inout"}, name = "n"}> : () -> !nodal.terminal<"electrical">
    %branch = "nodal.branch"(%p, %n) <{metadata = {}}> : (!nodal.terminal<"electrical">, !nodal.terminal<"electrical">) -> !nodal.branch<"electrical">
    "nodal.generate"() <{induction = "generate_0_index", lower = 0 : i64, upper = 2 : i64, step = 1 : i64, region_id = "GeneratedTableNoiseContribution.generate_0", induction_path = "GeneratedTableNoiseContribution.generate_0.index", metadata = {maximum_trip_count = 2 : i64}}> ({
    ^bb0:
      "nodal.analog"() <{generated_owner = "GeneratedTableNoiseContribution.generate_0", generated_induction = "GeneratedTableNoiseContribution.generate_0.index", metadata = {}}> ({
      ^bb0:
        %amp = "nodal.real_literal"() <{value = 1.0 : f64, metadata = {unit = "A"}}> : () -> f64
        %second = "nodal.real_literal"() <{value = 1.0 : f64, metadata = {unit = "s"}}> : () -> f64
        %one = "nodal.real_literal"() <{value = 1.0 : f64, metadata = {}}> : () -> f64
        %squared = "nodal.analog_mul"(%amp, %amp) <{metadata = {}}> : (f64, f64) -> f64
        %density = "nodal.analog_mul"(%squared, %second) <{metadata = {}}> : (f64, f64) -> f64
        %frequency = "nodal.analog_div"(%one, %second) <{metadata = {}}> : (f64, f64) -> f64
        %noise = "nodal.analog_noise"(%frequency, %density) <{analyses = ["noise"], contract_version = "1", correlation = "independent", generated_owner = "GeneratedTableNoiseContribution.generate_0", generated_induction = "GeneratedTableNoiseContribution.generate_0.index", metadata = {semantic_path = "GeneratedTableNoiseContribution.noise"}, noise_kind = "table", noise_name = "generated table", owner = "GeneratedTableNoiseContribution", result_dimension = "current", source_id = "GeneratedTableNoiseContribution.noise"}> : (f64, f64) -> f64
        "nodal.contribute"(%branch, %noise) <{kind = "flow", generated_owner = "GeneratedTableNoiseContribution.generate_0", generated_induction = "GeneratedTableNoiseContribution.generate_0.index", metadata = {}}> : (!nodal.branch<"electrical">, f64) -> ()
      }) : () -> ()
    }) : () -> ()
  }) : () -> ()
}
)mlir";

constexpr llvm::StringLiteral kGeneratedLexicalStorageModule = R"mlir(
module attributes {
  nodal.backend.check_profile = "release",
  nodal.backend.materialization = "safe-inline",
  nodal.backend.naming = "semantic",
  nodal.backend.profile = "verilog-a",
  nodal.backend.shaped_layout = "scalar-or-flat",
  nodal.target.profile = "analog"
} {
  "nodal.module"() <{metadata = {root = true, semantic_path = "GeneratedLexicalStorage"}, sym_name = "GeneratedLexicalStorage"}> ({
  ^bb0:
    "nodal.generate"() <{induction = "generate_0_index", lower = 0 : i64, upper = 2 : i64, step = 1 : i64, region_id = "GeneratedLexicalStorage.generate_0", induction_path = "GeneratedLexicalStorage.generate_0.index", metadata = {maximum_trip_count = 2 : i64}}> ({
    ^bb0:
      "nodal.analog"() <{generated_owner = "GeneratedLexicalStorage.generate_0", generated_induction = "GeneratedLexicalStorage.generate_0.index", metadata = {}}> ({
      ^bb0:
        "nodal.analog_procedure"() <{owner = "GeneratedLexicalStorage", generated_owner = "GeneratedLexicalStorage.generate_0", generated_induction = "GeneratedLexicalStorage.generate_0.index", metadata = {semantic_path = "GeneratedLexicalStorage.analogProcedure"}}> ({
        ^bb0:
          %state = "nodal.analog_variable"() <{identity = "GeneratedLexicalStorage.state", owner = "GeneratedLexicalStorage", declaration_order = 0 : i64, initialized = true, initializer_value = "0.0 V", initializer_kind = "real", initializer_dimension = "voltage", initializer_reads = [], generated_owner = "GeneratedLexicalStorage.generate_0", generated_induction = "GeneratedLexicalStorage.generate_0.index", metadata = {authored_path = "GeneratedLexicalStorage.state"}}> : () -> !nodal.variable<"real", "voltage">
          %state_read = "nodal.analog_variable_read"(%state) <{read_id = "GeneratedLexicalStorage.assignment.read_0", owner = "GeneratedLexicalStorage", generated_owner = "GeneratedLexicalStorage.generate_0", generated_induction = "GeneratedLexicalStorage.generate_0.index", metadata = {semantic_path = "GeneratedLexicalStorage.assignment.read_0"}}> : (!nodal.variable<"real", "voltage">) -> !nodal.quantity<"real", "voltage">
          "nodal.analog_assign"(%state, %state_read) <{analyses = ["dc", "transient"], authored_order = 0 : i64, guard_dimension = "", guard_kind = "", guard_present = false, guard_reads = [], guard_value = "", generated_owner = "GeneratedLexicalStorage.generate_0", generated_induction = "GeneratedLexicalStorage.generate_0.index", metadata = {semantic_path = "GeneratedLexicalStorage.assignment", value = "analog_add(GeneratedLexicalStorage.state,1.0 V)"}, owner = "GeneratedLexicalStorage", statement_id = "GeneratedLexicalStorage.assignment", value_dimension = "voltage", value_kind = "real"}> : (!nodal.variable<"real", "voltage">, !nodal.quantity<"real", "voltage">) -> ()
        }) : () -> ()
      }) : () -> ()
    }) : () -> ()
  }) : () -> ()
}
)mlir";

constexpr llvm::StringLiteral kGeneratedConditionalLexicalStorageModule = R"mlir(
module attributes {
  nodal.backend.check_profile = "release",
  nodal.backend.materialization = "safe-inline",
  nodal.backend.naming = "semantic",
  nodal.backend.profile = "verilog-a",
  nodal.backend.shaped_layout = "scalar-or-flat",
  nodal.target.profile = "analog"
} {
  "nodal.module"() <{metadata = {root = true, semantic_path = "GeneratedConditionalLexicalStorage"}, sym_name = "GeneratedConditionalLexicalStorage"}> ({
  ^bb0:
    "nodal.generate"() <{induction = "generate_0_index", lower = 0 : i64, upper = 2 : i64, step = 1 : i64, region_id = "GeneratedConditionalLexicalStorage.generate_0", induction_path = "GeneratedConditionalLexicalStorage.generate_0.index", metadata = {maximum_trip_count = 2 : i64}}> ({
    ^bb0:
      "nodal.analog"() <{generated_owner = "GeneratedConditionalLexicalStorage.generate_0", generated_induction = "GeneratedConditionalLexicalStorage.generate_0.index", metadata = {}}> ({
      ^bb0:
        "nodal.analog_procedure"() <{owner = "GeneratedConditionalLexicalStorage", generated_owner = "GeneratedConditionalLexicalStorage.generate_0", generated_induction = "GeneratedConditionalLexicalStorage.generate_0.index", metadata = {semantic_path = "GeneratedConditionalLexicalStorage.analogProcedure"}}> ({
        ^bb0:
          %select = "nodal.analog_variable"() <{identity = "GeneratedConditionalLexicalStorage.select", owner = "GeneratedConditionalLexicalStorage", declaration_order = 0 : i64, initialized = true, initializer_value = "false", initializer_kind = "boolean", initializer_dimension = "1", initializer_reads = [], generated_owner = "GeneratedConditionalLexicalStorage.generate_0", generated_induction = "GeneratedConditionalLexicalStorage.generate_0.index", metadata = {authored_path = "GeneratedConditionalLexicalStorage.select"}}> : () -> !nodal.variable<"boolean", "1">
          %state = "nodal.analog_variable"() <{identity = "GeneratedConditionalLexicalStorage.state", owner = "GeneratedConditionalLexicalStorage", declaration_order = 1 : i64, initialized = true, initializer_value = "0.0", initializer_kind = "real", initializer_dimension = "1", initializer_reads = [], generated_owner = "GeneratedConditionalLexicalStorage.generate_0", generated_induction = "GeneratedConditionalLexicalStorage.generate_0.index", metadata = {authored_path = "GeneratedConditionalLexicalStorage.state"}}> : () -> !nodal.variable<"real", "1">
          "nodal.analog_if"() <{metadata = {semantic_path = "GeneratedConditionalLexicalStorage.if_0"}, owner = "GeneratedConditionalLexicalStorage", statement_id = "GeneratedConditionalLexicalStorage.if_0"}> ({
          ^bb0:
            "nodal.analog_if_arm"() <{arm_id = "GeneratedConditionalLexicalStorage.if_0.branch_0", condition_dimension = "1", condition_kind = "boolean", condition_reads = ["GeneratedConditionalLexicalStorage.select"], condition_value = "GeneratedConditionalLexicalStorage.select", is_else = false, metadata = {semantic_path = "GeneratedConditionalLexicalStorage.if_0.condition_0"}, owner = "GeneratedConditionalLexicalStorage", stage = "runtime", static_value = false, static_value_present = false}> ({
            ^bb0:
              %state_read = "nodal.analog_variable_read"(%state) <{read_id = "GeneratedConditionalLexicalStorage.assignment.read_0", owner = "GeneratedConditionalLexicalStorage", generated_owner = "GeneratedConditionalLexicalStorage.generate_0", generated_induction = "GeneratedConditionalLexicalStorage.generate_0.index", metadata = {semantic_path = "GeneratedConditionalLexicalStorage.assignment.read_0"}}> : (!nodal.variable<"real", "1">) -> !nodal.quantity<"real", "1">
              "nodal.analog_assign"(%state, %state_read) <{analyses = ["dc", "transient"], authored_order = 0 : i64, guard_dimension = "", guard_kind = "", guard_present = false, guard_reads = [], guard_value = "", generated_owner = "GeneratedConditionalLexicalStorage.generate_0", generated_induction = "GeneratedConditionalLexicalStorage.generate_0.index", metadata = {semantic_path = "GeneratedConditionalLexicalStorage.assignment", value = "analog_add(GeneratedConditionalLexicalStorage.state,1.0)"}, owner = "GeneratedConditionalLexicalStorage", statement_id = "GeneratedConditionalLexicalStorage.assignment", value_dimension = "1", value_kind = "real"}> : (!nodal.variable<"real", "1">, !nodal.quantity<"real", "1">) -> ()
            }) : () -> ()
            "nodal.analog_if_arm"() <{arm_id = "GeneratedConditionalLexicalStorage.if_0.otherwise", condition_dimension = "", condition_kind = "", condition_reads = [], condition_value = "", is_else = true, metadata = {semantic_path = "GeneratedConditionalLexicalStorage.if_0.otherwise"}, owner = "GeneratedConditionalLexicalStorage", stage = "else", static_value = false, static_value_present = false}> ({
            ^bb0:
              %state_read_else = "nodal.analog_variable_read"(%state) <{read_id = "GeneratedConditionalLexicalStorage.assignment_else.read_0", owner = "GeneratedConditionalLexicalStorage", generated_owner = "GeneratedConditionalLexicalStorage.generate_0", generated_induction = "GeneratedConditionalLexicalStorage.generate_0.index", metadata = {semantic_path = "GeneratedConditionalLexicalStorage.assignment_else.read_0"}}> : (!nodal.variable<"real", "1">) -> !nodal.quantity<"real", "1">
              "nodal.analog_assign"(%state, %state_read_else) <{analyses = ["dc", "transient"], authored_order = 1 : i64, guard_dimension = "", guard_kind = "", guard_present = false, guard_reads = [], guard_value = "", generated_owner = "GeneratedConditionalLexicalStorage.generate_0", generated_induction = "GeneratedConditionalLexicalStorage.generate_0.index", metadata = {semantic_path = "GeneratedConditionalLexicalStorage.assignment_else", value = "analog_add(GeneratedConditionalLexicalStorage.state,2.0)"}, owner = "GeneratedConditionalLexicalStorage", statement_id = "GeneratedConditionalLexicalStorage.assignment_else", value_dimension = "1", value_kind = "real"}> : (!nodal.variable<"real", "1">, !nodal.quantity<"real", "1">) -> ()
            }) : () -> ()
          }) : () -> ()
        }) : () -> ()
      }) : () -> ()
    }) : () -> ()
  }) : () -> ()
}
)mlir";

constexpr llvm::StringLiteral kGeneratedCaseLexicalStorageModule = R"mlir(
module attributes {
  nodal.backend.check_profile = "release",
  nodal.backend.materialization = "safe-inline",
  nodal.backend.naming = "semantic",
  nodal.backend.profile = "verilog-a",
  nodal.backend.shaped_layout = "scalar-or-flat",
  nodal.target.profile = "analog"
} {
  "nodal.module"() <{metadata = {root = true, semantic_path = "GeneratedCaseLexicalStorage"}, sym_name = "GeneratedCaseLexicalStorage"}> ({
  ^bb0:
    "nodal.generate"() <{induction = "generate_0_index", lower = 0 : i64, upper = 2 : i64, step = 1 : i64, region_id = "GeneratedCaseLexicalStorage.generate_0", induction_path = "GeneratedCaseLexicalStorage.generate_0.index", metadata = {maximum_trip_count = 2 : i64}}> ({
    ^bb0:
      "nodal.analog"() <{generated_owner = "GeneratedCaseLexicalStorage.generate_0", generated_induction = "GeneratedCaseLexicalStorage.generate_0.index", metadata = {}}> ({
      ^bb0:
        "nodal.analog_procedure"() <{owner = "GeneratedCaseLexicalStorage", generated_owner = "GeneratedCaseLexicalStorage.generate_0", generated_induction = "GeneratedCaseLexicalStorage.generate_0.index", metadata = {semantic_path = "GeneratedCaseLexicalStorage.analogProcedure"}}> ({
        ^bb0:
          %mode = "nodal.analog_variable"() <{identity = "GeneratedCaseLexicalStorage.mode", owner = "GeneratedCaseLexicalStorage", declaration_order = 0 : i64, initialized = true, initializer_value = "0", initializer_kind = "integer", initializer_dimension = "1", initializer_reads = [], generated_owner = "GeneratedCaseLexicalStorage.generate_0", generated_induction = "GeneratedCaseLexicalStorage.generate_0.index", metadata = {authored_path = "GeneratedCaseLexicalStorage.mode"}}> : () -> !nodal.variable<"integer", "1">
          %state = "nodal.analog_variable"() <{identity = "GeneratedCaseLexicalStorage.state", owner = "GeneratedCaseLexicalStorage", declaration_order = 1 : i64, initialized = true, initializer_value = "0.0", initializer_kind = "real", initializer_dimension = "1", initializer_reads = [], generated_owner = "GeneratedCaseLexicalStorage.generate_0", generated_induction = "GeneratedCaseLexicalStorage.generate_0.index", metadata = {authored_path = "GeneratedCaseLexicalStorage.state"}}> : () -> !nodal.variable<"real", "1">
          "nodal.analog_case"() <{metadata = {semantic_path = "GeneratedCaseLexicalStorage.case_0.selector"}, owner = "GeneratedCaseLexicalStorage", selector_dimension = "1", selector_kind = "integer", selector_reads = ["GeneratedCaseLexicalStorage.mode"], selector_value = "GeneratedCaseLexicalStorage.mode", statement_id = "GeneratedCaseLexicalStorage.case_0", static_value = "", static_value_present = false}> ({
          ^bb0:
            "nodal.analog_case_arm"() <{arm_id = "GeneratedCaseLexicalStorage.case_0.arm_0", is_default = false, labels = ["integer:0"], metadata = {semantic_path = "GeneratedCaseLexicalStorage.case_0.arm_0"}, owner = "GeneratedCaseLexicalStorage"}> ({
            ^bb0:
              %state_read = "nodal.analog_variable_read"(%state) <{read_id = "GeneratedCaseLexicalStorage.assignment.read_0", owner = "GeneratedCaseLexicalStorage", generated_owner = "GeneratedCaseLexicalStorage.generate_0", generated_induction = "GeneratedCaseLexicalStorage.generate_0.index", metadata = {semantic_path = "GeneratedCaseLexicalStorage.assignment.read_0"}}> : (!nodal.variable<"real", "1">) -> !nodal.quantity<"real", "1">
              "nodal.analog_assign"(%state, %state_read) <{analyses = ["dc", "transient"], authored_order = 0 : i64, guard_dimension = "", guard_kind = "", guard_present = false, guard_reads = [], guard_value = "", generated_owner = "GeneratedCaseLexicalStorage.generate_0", generated_induction = "GeneratedCaseLexicalStorage.generate_0.index", metadata = {semantic_path = "GeneratedCaseLexicalStorage.assignment", value = "analog_add(GeneratedCaseLexicalStorage.state,3.0)"}, owner = "GeneratedCaseLexicalStorage", statement_id = "GeneratedCaseLexicalStorage.assignment", value_dimension = "1", value_kind = "real"}> : (!nodal.variable<"real", "1">, !nodal.quantity<"real", "1">) -> ()
            }) : () -> ()
            "nodal.analog_case_arm"() <{arm_id = "GeneratedCaseLexicalStorage.case_0.default", is_default = true, labels = [], metadata = {semantic_path = "GeneratedCaseLexicalStorage.case_0.default"}, owner = "GeneratedCaseLexicalStorage"}> ({
            ^bb0:
              %state_read_default = "nodal.analog_variable_read"(%state) <{read_id = "GeneratedCaseLexicalStorage.assignment_default.read_0", owner = "GeneratedCaseLexicalStorage", generated_owner = "GeneratedCaseLexicalStorage.generate_0", generated_induction = "GeneratedCaseLexicalStorage.generate_0.index", metadata = {semantic_path = "GeneratedCaseLexicalStorage.assignment_default.read_0"}}> : (!nodal.variable<"real", "1">) -> !nodal.quantity<"real", "1">
              "nodal.analog_assign"(%state, %state_read_default) <{analyses = ["dc", "transient"], authored_order = 1 : i64, guard_dimension = "", guard_kind = "", guard_present = false, guard_reads = [], guard_value = "", generated_owner = "GeneratedCaseLexicalStorage.generate_0", generated_induction = "GeneratedCaseLexicalStorage.generate_0.index", metadata = {semantic_path = "GeneratedCaseLexicalStorage.assignment_default", value = "analog_add(GeneratedCaseLexicalStorage.state,4.0)"}, owner = "GeneratedCaseLexicalStorage", statement_id = "GeneratedCaseLexicalStorage.assignment_default", value_dimension = "1", value_kind = "real"}> : (!nodal.variable<"real", "1">, !nodal.quantity<"real", "1">) -> ()
            }) : () -> ()
          }) : () -> ()
        }) : () -> ()
      }) : () -> ()
    }) : () -> ()
  }) : () -> ()
}
)mlir";

constexpr llvm::StringLiteral kGeneratedLoopLexicalStorageModule = R"mlir(
module attributes {
  nodal.backend.check_profile = "release",
  nodal.backend.materialization = "safe-inline",
  nodal.backend.naming = "semantic",
  nodal.backend.profile = "verilog-a",
  nodal.backend.shaped_layout = "scalar-or-flat",
  nodal.target.profile = "analog"
} {
  "nodal.module"() <{metadata = {root = true, semantic_path = "GeneratedLoopLexicalStorage"}, sym_name = "GeneratedLoopLexicalStorage"}> ({
  ^bb0:
    "nodal.generate"() <{induction = "generate_0_index", lower = 0 : i64, upper = 2 : i64, step = 1 : i64, region_id = "GeneratedLoopLexicalStorage.generate_0", induction_path = "GeneratedLoopLexicalStorage.generate_0.index", metadata = {maximum_trip_count = 2 : i64}}> ({
    ^bb0:
      "nodal.analog"() <{generated_owner = "GeneratedLoopLexicalStorage.generate_0", generated_induction = "GeneratedLoopLexicalStorage.generate_0.index", metadata = {}}> ({
      ^bb0:
        "nodal.analog_procedure"() <{owner = "GeneratedLoopLexicalStorage", generated_owner = "GeneratedLoopLexicalStorage.generate_0", generated_induction = "GeneratedLoopLexicalStorage.generate_0.index", metadata = {semantic_path = "GeneratedLoopLexicalStorage.analogProcedure"}}> ({
        ^bb0:
          %iterations = "nodal.analog_variable"() <{identity = "GeneratedLoopLexicalStorage.iterations", owner = "GeneratedLoopLexicalStorage", declaration_order = 0 : i64, initialized = true, initializer_value = "1", initializer_kind = "integer", initializer_dimension = "1", initializer_reads = [], generated_owner = "GeneratedLoopLexicalStorage.generate_0", generated_induction = "GeneratedLoopLexicalStorage.generate_0.index", metadata = {authored_path = "GeneratedLoopLexicalStorage.iterations"}}> : () -> !nodal.variable<"integer", "1">
          %state = "nodal.analog_variable"() <{identity = "GeneratedLoopLexicalStorage.state", owner = "GeneratedLoopLexicalStorage", declaration_order = 1 : i64, initialized = true, initializer_value = "0.0", initializer_kind = "real", initializer_dimension = "1", initializer_reads = [], generated_owner = "GeneratedLoopLexicalStorage.generate_0", generated_induction = "GeneratedLoopLexicalStorage.generate_0.index", metadata = {authored_path = "GeneratedLoopLexicalStorage.state"}}> : () -> !nodal.variable<"real", "1">
          "nodal.analog_loop"() <{bound_dimension = "1", bound_kind = "integer", bound_reads = ["GeneratedLoopLexicalStorage.iterations"], bound_value = "GeneratedLoopLexicalStorage.iterations", maximum_iterations = 2 : i64, metadata = {semantic_path = "GeneratedLoopLexicalStorage.loop_0"}, minimum_iterations = 1 : i64, owner = "GeneratedLoopLexicalStorage", stage = "runtime", statement_id = "GeneratedLoopLexicalStorage.loop_0", static_trip_count = 0 : i64, static_trip_count_present = false}> ({
          ^bb0:
            %state_read = "nodal.analog_variable_read"(%state) <{read_id = "GeneratedLoopLexicalStorage.loop_0.assignment.read_0", owner = "GeneratedLoopLexicalStorage", generated_owner = "GeneratedLoopLexicalStorage.generate_0", generated_induction = "GeneratedLoopLexicalStorage.generate_0.index", metadata = {semantic_path = "GeneratedLoopLexicalStorage.loop_0.assignment.read_0"}}> : (!nodal.variable<"real", "1">) -> !nodal.quantity<"real", "1">
            "nodal.analog_assign"(%state, %state_read) <{analyses = ["dc", "transient"], authored_order = 0 : i64, guard_dimension = "", guard_kind = "", guard_present = false, guard_reads = [], guard_value = "", generated_owner = "GeneratedLoopLexicalStorage.generate_0", generated_induction = "GeneratedLoopLexicalStorage.generate_0.index", metadata = {semantic_path = "GeneratedLoopLexicalStorage.loop_0.assignment", value = "analog_add(GeneratedLoopLexicalStorage.state,5.0)"}, owner = "GeneratedLoopLexicalStorage", statement_id = "GeneratedLoopLexicalStorage.loop_0.assignment", value_dimension = "1", value_kind = "real"}> : (!nodal.variable<"real", "1">, !nodal.quantity<"real", "1">) -> ()
          }) : () -> ()
        }) : () -> ()
      }) : () -> ()
    }) : () -> ()
  }) : () -> ()
}
)mlir";

constexpr llvm::StringLiteral kGeneratedLoopConditionalLexicalStorageModule = R"mlir(
module attributes {
  nodal.backend.check_profile = "release",
  nodal.backend.materialization = "safe-inline",
  nodal.backend.naming = "semantic",
  nodal.backend.profile = "verilog-a",
  nodal.backend.shaped_layout = "scalar-or-flat",
  nodal.target.profile = "analog"
} {
  "nodal.module"() <{metadata = {root = true, semantic_path = "GeneratedLoopConditionalLexicalStorage"}, sym_name = "GeneratedLoopConditionalLexicalStorage"}> ({
  ^bb0:
    "nodal.generate"() <{induction = "generate_0_index", lower = 0 : i64, upper = 2 : i64, step = 1 : i64, region_id = "GeneratedLoopConditionalLexicalStorage.generate_0", induction_path = "GeneratedLoopConditionalLexicalStorage.generate_0.index", metadata = {maximum_trip_count = 2 : i64}}> ({
    ^bb0:
      "nodal.analog"() <{generated_owner = "GeneratedLoopConditionalLexicalStorage.generate_0", generated_induction = "GeneratedLoopConditionalLexicalStorage.generate_0.index", metadata = {}}> ({
      ^bb0:
        "nodal.analog_procedure"() <{owner = "GeneratedLoopConditionalLexicalStorage", generated_owner = "GeneratedLoopConditionalLexicalStorage.generate_0", generated_induction = "GeneratedLoopConditionalLexicalStorage.generate_0.index", metadata = {semantic_path = "GeneratedLoopConditionalLexicalStorage.analogProcedure"}}> ({
        ^bb0:
          %iterations = "nodal.analog_variable"() <{identity = "GeneratedLoopConditionalLexicalStorage.iterations", owner = "GeneratedLoopConditionalLexicalStorage", declaration_order = 0 : i64, initialized = true, initializer_value = "1", initializer_kind = "integer", initializer_dimension = "1", initializer_reads = [], generated_owner = "GeneratedLoopConditionalLexicalStorage.generate_0", generated_induction = "GeneratedLoopConditionalLexicalStorage.generate_0.index", metadata = {authored_path = "GeneratedLoopConditionalLexicalStorage.iterations"}}> : () -> !nodal.variable<"integer", "1">
          %select = "nodal.analog_variable"() <{identity = "GeneratedLoopConditionalLexicalStorage.select", owner = "GeneratedLoopConditionalLexicalStorage", declaration_order = 1 : i64, initialized = true, initializer_value = "false", initializer_kind = "boolean", initializer_dimension = "1", initializer_reads = [], generated_owner = "GeneratedLoopConditionalLexicalStorage.generate_0", generated_induction = "GeneratedLoopConditionalLexicalStorage.generate_0.index", metadata = {authored_path = "GeneratedLoopConditionalLexicalStorage.select"}}> : () -> !nodal.variable<"boolean", "1">
          %state = "nodal.analog_variable"() <{identity = "GeneratedLoopConditionalLexicalStorage.state", owner = "GeneratedLoopConditionalLexicalStorage", declaration_order = 2 : i64, initialized = true, initializer_value = "0.0", initializer_kind = "real", initializer_dimension = "1", initializer_reads = [], generated_owner = "GeneratedLoopConditionalLexicalStorage.generate_0", generated_induction = "GeneratedLoopConditionalLexicalStorage.generate_0.index", metadata = {authored_path = "GeneratedLoopConditionalLexicalStorage.state"}}> : () -> !nodal.variable<"real", "1">
          "nodal.analog_loop"() <{bound_dimension = "1", bound_kind = "integer", bound_reads = ["GeneratedLoopConditionalLexicalStorage.iterations"], bound_value = "GeneratedLoopConditionalLexicalStorage.iterations", maximum_iterations = 2 : i64, metadata = {semantic_path = "GeneratedLoopConditionalLexicalStorage.loop_0"}, minimum_iterations = 1 : i64, owner = "GeneratedLoopConditionalLexicalStorage", stage = "runtime", statement_id = "GeneratedLoopConditionalLexicalStorage.loop_0", static_trip_count = 0 : i64, static_trip_count_present = false}> ({
          ^bb0:
            "nodal.analog_if"() <{metadata = {semantic_path = "GeneratedLoopConditionalLexicalStorage.loop_0.if_0"}, owner = "GeneratedLoopConditionalLexicalStorage", statement_id = "GeneratedLoopConditionalLexicalStorage.loop_0.if_0"}> ({
            ^bb0:
              "nodal.analog_if_arm"() <{arm_id = "GeneratedLoopConditionalLexicalStorage.loop_0.if_0.branch_0", condition_dimension = "1", condition_kind = "boolean", condition_reads = ["GeneratedLoopConditionalLexicalStorage.select"], condition_value = "GeneratedLoopConditionalLexicalStorage.select", is_else = false, metadata = {semantic_path = "GeneratedLoopConditionalLexicalStorage.loop_0.if_0.condition_0"}, owner = "GeneratedLoopConditionalLexicalStorage", stage = "runtime", static_value = false, static_value_present = false}> ({
              ^bb0:
                %state_read = "nodal.analog_variable_read"(%state) <{read_id = "GeneratedLoopConditionalLexicalStorage.loop_0.if_0.assignment.read_0", owner = "GeneratedLoopConditionalLexicalStorage", generated_owner = "GeneratedLoopConditionalLexicalStorage.generate_0", generated_induction = "GeneratedLoopConditionalLexicalStorage.generate_0.index", metadata = {semantic_path = "GeneratedLoopConditionalLexicalStorage.loop_0.if_0.assignment.read_0"}}> : (!nodal.variable<"real", "1">) -> !nodal.quantity<"real", "1">
                "nodal.analog_assign"(%state, %state_read) <{analyses = ["dc", "transient"], authored_order = 0 : i64, guard_dimension = "", guard_kind = "", guard_present = false, guard_reads = [], guard_value = "", generated_owner = "GeneratedLoopConditionalLexicalStorage.generate_0", generated_induction = "GeneratedLoopConditionalLexicalStorage.generate_0.index", metadata = {semantic_path = "GeneratedLoopConditionalLexicalStorage.loop_0.if_0.assignment", value = "analog_add(GeneratedLoopConditionalLexicalStorage.state,6.0)"}, owner = "GeneratedLoopConditionalLexicalStorage", statement_id = "GeneratedLoopConditionalLexicalStorage.loop_0.if_0.assignment", value_dimension = "1", value_kind = "real"}> : (!nodal.variable<"real", "1">, !nodal.quantity<"real", "1">) -> ()
              }) : () -> ()
              "nodal.analog_if_arm"() <{arm_id = "GeneratedLoopConditionalLexicalStorage.loop_0.if_0.otherwise", condition_dimension = "", condition_kind = "", condition_reads = [], condition_value = "", is_else = true, metadata = {semantic_path = "GeneratedLoopConditionalLexicalStorage.loop_0.if_0.otherwise"}, owner = "GeneratedLoopConditionalLexicalStorage", stage = "else", static_value = false, static_value_present = false}> ({
              ^bb0:
                %state_read_else = "nodal.analog_variable_read"(%state) <{read_id = "GeneratedLoopConditionalLexicalStorage.loop_0.if_0.assignment_else.read_0", owner = "GeneratedLoopConditionalLexicalStorage", generated_owner = "GeneratedLoopConditionalLexicalStorage.generate_0", generated_induction = "GeneratedLoopConditionalLexicalStorage.generate_0.index", metadata = {semantic_path = "GeneratedLoopConditionalLexicalStorage.loop_0.if_0.assignment_else.read_0"}}> : (!nodal.variable<"real", "1">) -> !nodal.quantity<"real", "1">
                "nodal.analog_assign"(%state, %state_read_else) <{analyses = ["dc", "transient"], authored_order = 1 : i64, guard_dimension = "", guard_kind = "", guard_present = false, guard_reads = [], guard_value = "", generated_owner = "GeneratedLoopConditionalLexicalStorage.generate_0", generated_induction = "GeneratedLoopConditionalLexicalStorage.generate_0.index", metadata = {semantic_path = "GeneratedLoopConditionalLexicalStorage.loop_0.if_0.assignment_else", value = "analog_add(GeneratedLoopConditionalLexicalStorage.state,7.0)"}, owner = "GeneratedLoopConditionalLexicalStorage", statement_id = "GeneratedLoopConditionalLexicalStorage.loop_0.if_0.assignment_else", value_dimension = "1", value_kind = "real"}> : (!nodal.variable<"real", "1">, !nodal.quantity<"real", "1">) -> ()
              }) : () -> ()
            }) : () -> ()
          }) : () -> ()
        }) : () -> ()
      }) : () -> ()
    }) : () -> ()
  }) : () -> ()
}
)mlir";

constexpr llvm::StringLiteral kGeneratedLoopCaseLexicalStorageModule = R"mlir(
module attributes {
  nodal.backend.check_profile = "release",
  nodal.backend.materialization = "safe-inline",
  nodal.backend.naming = "semantic",
  nodal.backend.profile = "verilog-a",
  nodal.backend.shaped_layout = "scalar-or-flat",
  nodal.target.profile = "analog"
} {
  "nodal.module"() <{metadata = {root = true, semantic_path = "GeneratedLoopCaseLexicalStorage"}, sym_name = "GeneratedLoopCaseLexicalStorage"}> ({
  ^bb0:
    "nodal.generate"() <{induction = "generate_0_index", lower = 0 : i64, upper = 2 : i64, step = 1 : i64, region_id = "GeneratedLoopCaseLexicalStorage.generate_0", induction_path = "GeneratedLoopCaseLexicalStorage.generate_0.index", metadata = {maximum_trip_count = 2 : i64}}> ({
    ^bb0:
      "nodal.analog"() <{generated_owner = "GeneratedLoopCaseLexicalStorage.generate_0", generated_induction = "GeneratedLoopCaseLexicalStorage.generate_0.index", metadata = {}}> ({
      ^bb0:
        "nodal.analog_procedure"() <{owner = "GeneratedLoopCaseLexicalStorage", generated_owner = "GeneratedLoopCaseLexicalStorage.generate_0", generated_induction = "GeneratedLoopCaseLexicalStorage.generate_0.index", metadata = {semantic_path = "GeneratedLoopCaseLexicalStorage.analogProcedure"}}> ({
        ^bb0:
          %iterations = "nodal.analog_variable"() <{identity = "GeneratedLoopCaseLexicalStorage.iterations", owner = "GeneratedLoopCaseLexicalStorage", declaration_order = 0 : i64, initialized = true, initializer_value = "1", initializer_kind = "integer", initializer_dimension = "1", initializer_reads = [], generated_owner = "GeneratedLoopCaseLexicalStorage.generate_0", generated_induction = "GeneratedLoopCaseLexicalStorage.generate_0.index", metadata = {authored_path = "GeneratedLoopCaseLexicalStorage.iterations"}}> : () -> !nodal.variable<"integer", "1">
          %mode = "nodal.analog_variable"() <{identity = "GeneratedLoopCaseLexicalStorage.mode", owner = "GeneratedLoopCaseLexicalStorage", declaration_order = 1 : i64, initialized = true, initializer_value = "0", initializer_kind = "integer", initializer_dimension = "1", initializer_reads = [], generated_owner = "GeneratedLoopCaseLexicalStorage.generate_0", generated_induction = "GeneratedLoopCaseLexicalStorage.generate_0.index", metadata = {authored_path = "GeneratedLoopCaseLexicalStorage.mode"}}> : () -> !nodal.variable<"integer", "1">
          %state = "nodal.analog_variable"() <{identity = "GeneratedLoopCaseLexicalStorage.state", owner = "GeneratedLoopCaseLexicalStorage", declaration_order = 2 : i64, initialized = true, initializer_value = "0.0", initializer_kind = "real", initializer_dimension = "1", initializer_reads = [], generated_owner = "GeneratedLoopCaseLexicalStorage.generate_0", generated_induction = "GeneratedLoopCaseLexicalStorage.generate_0.index", metadata = {authored_path = "GeneratedLoopCaseLexicalStorage.state"}}> : () -> !nodal.variable<"real", "1">
          "nodal.analog_loop"() <{bound_dimension = "1", bound_kind = "integer", bound_reads = ["GeneratedLoopCaseLexicalStorage.iterations"], bound_value = "GeneratedLoopCaseLexicalStorage.iterations", maximum_iterations = 2 : i64, metadata = {semantic_path = "GeneratedLoopCaseLexicalStorage.loop_0"}, minimum_iterations = 1 : i64, owner = "GeneratedLoopCaseLexicalStorage", stage = "runtime", statement_id = "GeneratedLoopCaseLexicalStorage.loop_0", static_trip_count = 0 : i64, static_trip_count_present = false}> ({
          ^bb0:
            "nodal.analog_case"() <{metadata = {semantic_path = "GeneratedLoopCaseLexicalStorage.loop_0.case_0.selector"}, owner = "GeneratedLoopCaseLexicalStorage", selector_dimension = "1", selector_kind = "integer", selector_reads = ["GeneratedLoopCaseLexicalStorage.mode"], selector_value = "GeneratedLoopCaseLexicalStorage.mode", statement_id = "GeneratedLoopCaseLexicalStorage.loop_0.case_0", static_value = "", static_value_present = false}> ({
            ^bb0:
              "nodal.analog_case_arm"() <{arm_id = "GeneratedLoopCaseLexicalStorage.loop_0.case_0.arm_0", is_default = false, labels = ["integer:0"], metadata = {semantic_path = "GeneratedLoopCaseLexicalStorage.loop_0.case_0.arm_0"}, owner = "GeneratedLoopCaseLexicalStorage"}> ({
              ^bb0:
                %state_read = "nodal.analog_variable_read"(%state) <{read_id = "GeneratedLoopCaseLexicalStorage.loop_0.case_0.assignment.read_0", owner = "GeneratedLoopCaseLexicalStorage", generated_owner = "GeneratedLoopCaseLexicalStorage.generate_0", generated_induction = "GeneratedLoopCaseLexicalStorage.generate_0.index", metadata = {semantic_path = "GeneratedLoopCaseLexicalStorage.loop_0.case_0.assignment.read_0"}}> : (!nodal.variable<"real", "1">) -> !nodal.quantity<"real", "1">
                "nodal.analog_assign"(%state, %state_read) <{analyses = ["dc", "transient"], authored_order = 0 : i64, guard_dimension = "", guard_kind = "", guard_present = false, guard_reads = [], guard_value = "", generated_owner = "GeneratedLoopCaseLexicalStorage.generate_0", generated_induction = "GeneratedLoopCaseLexicalStorage.generate_0.index", metadata = {semantic_path = "GeneratedLoopCaseLexicalStorage.loop_0.case_0.assignment", value = "analog_add(GeneratedLoopCaseLexicalStorage.state,8.0)"}, owner = "GeneratedLoopCaseLexicalStorage", statement_id = "GeneratedLoopCaseLexicalStorage.loop_0.case_0.assignment", value_dimension = "1", value_kind = "real"}> : (!nodal.variable<"real", "1">, !nodal.quantity<"real", "1">) -> ()
              }) : () -> ()
              "nodal.analog_case_arm"() <{arm_id = "GeneratedLoopCaseLexicalStorage.loop_0.case_0.default", is_default = true, labels = [], metadata = {semantic_path = "GeneratedLoopCaseLexicalStorage.loop_0.case_0.default"}, owner = "GeneratedLoopCaseLexicalStorage"}> ({
              ^bb0:
                %state_read_default = "nodal.analog_variable_read"(%state) <{read_id = "GeneratedLoopCaseLexicalStorage.loop_0.case_0.assignment_default.read_0", owner = "GeneratedLoopCaseLexicalStorage", generated_owner = "GeneratedLoopCaseLexicalStorage.generate_0", generated_induction = "GeneratedLoopCaseLexicalStorage.generate_0.index", metadata = {semantic_path = "GeneratedLoopCaseLexicalStorage.loop_0.case_0.assignment_default.read_0"}}> : (!nodal.variable<"real", "1">) -> !nodal.quantity<"real", "1">
                "nodal.analog_assign"(%state, %state_read_default) <{analyses = ["dc", "transient"], authored_order = 1 : i64, guard_dimension = "", guard_kind = "", guard_present = false, guard_reads = [], guard_value = "", generated_owner = "GeneratedLoopCaseLexicalStorage.generate_0", generated_induction = "GeneratedLoopCaseLexicalStorage.generate_0.index", metadata = {semantic_path = "GeneratedLoopCaseLexicalStorage.loop_0.case_0.assignment_default", value = "analog_add(GeneratedLoopCaseLexicalStorage.state,9.0)"}, owner = "GeneratedLoopCaseLexicalStorage", statement_id = "GeneratedLoopCaseLexicalStorage.loop_0.case_0.assignment_default", value_dimension = "1", value_kind = "real"}> : (!nodal.variable<"real", "1">, !nodal.quantity<"real", "1">) -> ()
              }) : () -> ()
            }) : () -> ()
          }) : () -> ()
        }) : () -> ()
      }) : () -> ()
    }) : () -> ()
  }) : () -> ()
}
)mlir";

constexpr llvm::StringLiteral kGeneratedEventModule = R"mlir(
module attributes {
  nodal.backend.check_profile = "release",
  nodal.backend.materialization = "safe-inline",
  nodal.backend.naming = "semantic",
  nodal.backend.profile = "verilog-a",
  nodal.backend.shaped_layout = "scalar-or-flat",
  nodal.target.profile = "analog"
} {
  "nodal.module"() <{metadata = {root = true, semantic_path = "GeneratedEvent"}, sym_name = "GeneratedEvent"}> ({
  ^bb0:
    "nodal.generate"() <{induction = "generate_0_index", lower = 0 : i64, upper = 2 : i64, step = 1 : i64, region_id = "GeneratedEvent.generate_0", induction_path = "GeneratedEvent.generate_0.index", metadata = {maximum_trip_count = 2 : i64}}> ({
    ^bb0:
      "nodal.analog"() <{generated_owner = "GeneratedEvent.generate_0", generated_induction = "GeneratedEvent.generate_0.index", metadata = {}}> ({
      ^bb0:
        "nodal.analog_procedure"() <{owner = "GeneratedEvent", generated_owner = "GeneratedEvent.generate_0", generated_induction = "GeneratedEvent.generate_0.index", metadata = {semantic_path = "GeneratedEvent.analogProcedure"}}> ({
        ^bb0:
          %cross = "nodal.analog_cross"() <{event_id = "GeneratedEvent.event_0.event", owner = "GeneratedEvent", contract = "increment37", name = "", generated_owner = "GeneratedEvent.generate_0", generated_induction = "GeneratedEvent.generate_0.index", arguments = [{slot = 0 : i64, value = "1.0 V", kind = "real", dimension = "voltage", reads = []}], analyses = [], event_reads = [], metadata = {}}> : () -> !nodal.analog_event
          "nodal.analog_on"(%cross) <{statement_id = "GeneratedEvent.event_0", owner = "GeneratedEvent", generated_owner = "GeneratedEvent.generate_0", generated_induction = "GeneratedEvent.generate_0.index", metadata = {}}> ({
          ^bb0:
          }) : (!nodal.analog_event) -> ()
        }) : () -> ()
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

  auto generatedNodeArray = parse(context, kGeneratedNodeArrayModule);
  if (!generatedNodeArray)
    return fail("could not parse the generated analog-node array fixture");
  std::string generatedNodeArrayOutput;
  llvm::raw_string_ostream generatedNodeArrayStream(generatedNodeArrayOutput);
  if (mlir::failed(nodal::emitBackend(*generatedNodeArray, nodal::BackendKind::VerilogA,
                                      generatedNodeArrayStream)))
    return fail("generated analog-node array emission failed");
  generatedNodeArrayStream.flush();
  if (generatedNodeArrayOutput.find("  electrical tap[0:(3 - 1)];\n") == std::string::npos ||
      generatedNodeArrayOutput.find("genvar generate_0_index") != std::string::npos ||
      generatedNodeArrayOutput.find("analog begin") != std::string::npos)
    return fail("generated analog node was not retained as one legal electrical array");
  if (mlir::failed(nodal::reparseBackendTarget(generatedNodeArrayOutput, *configuration)))
    return fail("generated analog-node array failed independent target reparse");
  for (const auto &mutation :
       {std::make_pair("electrical tap[0:(3 - 1)];", "electrical tap[0:(3 && 1)];"),
        std::make_pair("electrical tap[0:(3 - 1)];", "electrical tap[0:MISSING];")}) {
    std::string invalid = generatedNodeArrayOutput;
    auto position = invalid.find(mutation.first);
    if (position == std::string::npos)
      return fail("generated analog-node array mutation anchor missing");
    invalid.replace(position, std::string(mutation.first).size(), mutation.second);
    if (mlir::succeeded(nodal::reparseBackendTarget(invalid, *configuration)))
      return fail("malformed generated analog-node array passed independent reparse");
  }
  auto forgedGeneratedNodeArray = parse(context, kGeneratedNodeArrayModule);
  if (!forgedGeneratedNodeArray)
    return fail("could not parse the generated analog-node owner mutation fixture");
  mlir::Operation *forgedGeneratedNode = nullptr;
  forgedGeneratedNodeArray->walk([&](mlir::Operation *operation) {
    if (!forgedGeneratedNode && operation->getName().getStringRef() == "nodal.node")
      forgedGeneratedNode = operation;
  });
  if (!forgedGeneratedNode)
    return fail("generated analog-node owner mutation fixture has no node");
  forgedGeneratedNode->setAttr(
      "generated_owner", mlir::StringAttr::get(&context, "GeneratedNodeArray.generate_forged"));
  std::string forgedGeneratedNodeOutput = "sentinel";
  llvm::raw_string_ostream forgedGeneratedNodeStream(forgedGeneratedNodeOutput);
  if (mlir::succeeded(nodal::emitBackend(*forgedGeneratedNodeArray, nodal::BackendKind::VerilogA,
                                         forgedGeneratedNodeStream)))
    return fail("forged generated analog-node owner passed backend verification");
  forgedGeneratedNodeStream.flush();
  if (forgedGeneratedNodeOutput != "sentinel")
    return fail("forged generated analog-node owner published partial output");

  for (llvm::StringRef mutation : {"valid", "owner", "induction", "name", "source"}) {
    auto group = parse(context, kGeneratedNodeArrayModule);
    if (!group)
      return fail("could not parse generated node group fixture");
    mlir::Operation *firstNode = nullptr;
    group->walk([&](mlir::Operation *operation) {
      if (operation->getName().getStringRef() == "nodal.node")
        firstNode = operation;
    });
    if (!firstNode)
      return fail("generated node group fixture has no first node");
    mlir::Operation *secondNode = firstNode->clone();
    firstNode->getBlock()->push_back(secondNode);
    secondNode->setAttr("name", mlir::StringAttr::get(&context, "returnTap"));
    secondNode->setAttr("source_path",
                        mlir::StringAttr::get(&context, "GeneratedNodeArray.returnTap"));
    if (mutation == "owner")
      secondNode->setAttr("generated_owner", mlir::StringAttr::get(&context, "forged"));
    if (mutation == "induction")
      secondNode->setAttr("generated_induction", mlir::StringAttr::get(&context, "forged.index"));
    if (mutation == "name")
      secondNode->setAttr("name", firstNode->getAttr("name"));
    if (mutation == "source")
      secondNode->setAttr("source_path", mlir::StringAttr::get(&context, ""));
    std::string output = "sentinel";
    llvm::raw_string_ostream stream(output);
    const bool emitted =
        mlir::succeeded(nodal::emitBackend(*group, nodal::BackendKind::VerilogA, stream));
    stream.flush();
    if (mutation != "valid") {
      if (emitted || output != "sentinel")
        return fail("invalid second generated node was accepted or published partial output");
      continue;
    }
    if (!emitted ||
        output.find("  electrical tap[0:(3 - 1)];\n"
                    "  electrical returnTap[0:(3 - 1)];\n") == std::string::npos ||
        output.find("genvar") != std::string::npos ||
        output.find("analog begin") != std::string::npos)
      return fail("generated node group did not preserve separate arrays and declaration order");
    std::string target = output.substr(std::string("sentinel").size());
    if (mlir::failed(nodal::reparseBackendTarget(target, *configuration)))
      return fail("generated node group failed independent target reparse");
    std::string repeated;
    llvm::raw_string_ostream repeatedStream(repeated);
    if (mlir::failed(nodal::emitBackend(*group, nodal::BackendKind::VerilogA, repeatedStream)))
      return fail("generated node group repeated emission failed");
    repeatedStream.flush();
    if (target != repeated)
      return fail("generated node group emission is nondeterministic");
  }

  for (llvm::StringRef mutation :
       {"valid-single", "valid-group", "branch-owner", "source", "second-owner", "second-induction",
        "second-name", "second-source", "second-source-duplicate", "body-shape"}) {
    auto mixed = parse(context, kGeneratedNodeContributionModule);
    if (!mixed)
      return fail("could not parse generated node contribution fixture");
    mlir::Operation *node = nullptr;
    mlir::Operation *branch = nullptr;
    mlir::Operation *analog = nullptr;
    mixed->walk([&](mlir::Operation *operation) {
      if (operation->getName().getStringRef() == "nodal.node")
        node = operation;
      if (operation->getName().getStringRef() == "nodal.branch")
        branch = operation;
      if (operation->getName().getStringRef() == "nodal.analog")
        analog = operation;
    });
    if (!node || !branch || !analog)
      return fail("generated node contribution fixture lost its direct topology");
    mlir::Operation *secondNode = nullptr;
    if (mutation != "valid-single" && mutation != "branch-owner" && mutation != "source") {
      secondNode = node->clone();
      node->getBlock()->getOperations().insert(branch->getIterator(), secondNode);
      secondNode->setAttr("name", mlir::StringAttr::get(&context, "returnTap"));
      secondNode->setAttr("source_path",
                          mlir::StringAttr::get(&context, "GeneratedNodeContribution.returnTap"));
    }
    if (mutation == "branch-owner") {
      mlir::NamedAttrList metadata(branch->getAttrOfType<mlir::DictionaryAttr>("metadata"));
      metadata.set("generated_owner", mlir::StringAttr::get(&context, "forged"));
      branch->setAttr("metadata", metadata.getDictionary(&context));
    }
    if (mutation == "source")
      node->setAttr("source_path", mlir::StringAttr::get(&context, ""));
    if (mutation == "second-owner")
      secondNode->setAttr("generated_owner", mlir::StringAttr::get(&context, "forged"));
    if (mutation == "second-induction")
      secondNode->setAttr("generated_induction", mlir::StringAttr::get(&context, "forged.index"));
    if (mutation == "second-name")
      secondNode->setAttr("name", node->getAttr("name"));
    if (mutation == "second-source")
      secondNode->setAttr("source_path", mlir::StringAttr::get(&context, ""));
    if (mutation == "second-source-duplicate")
      secondNode->setAttr("source_path", node->getAttr("source_path"));
    if (mutation == "body-shape")
      node->getBlock()->getOperations().insert(analog->getIterator(), branch->clone());
    std::string output = "sentinel";
    llvm::raw_string_ostream stream(output);
    const bool emitted =
        mlir::succeeded(nodal::emitBackend(*mixed, nodal::BackendKind::VerilogA, stream));
    stream.flush();
    if (mutation != "valid-single" && mutation != "valid-group") {
      if (emitted || output != "sentinel")
        return fail("invalid generated node contribution was accepted or published output");
      continue;
    }
    if (!emitted || output.find("  electrical tap[0:(3 - 1)];\n") == std::string::npos ||
        output.find("  genvar generate_0_index;\n") == std::string::npos ||
        output.find("V(tap[generate_0_index], p) <+ 1;\n") == std::string::npos)
      return fail("generated node contribution lost its array, index, or equation");
    if (mutation == "valid-group" &&
        output.find("  electrical tap[0:(3 - 1)];\n"
                    "  electrical returnTap[0:(3 - 1)];\n") == std::string::npos)
      return fail("generated node-group contribution lost declaration order or array bounds");
    std::string target = output.substr(std::string("sentinel").size());
    if (mlir::failed(nodal::reparseBackendTarget(target, *configuration)))
      return fail("generated node contribution failed independent target reparse");
    std::string repeated;
    llvm::raw_string_ostream repeatedStream(repeated);
    if (mlir::failed(nodal::emitBackend(*mixed, nodal::BackendKind::VerilogA, repeatedStream)))
      return fail("generated node contribution repeated emission failed");
    repeatedStream.flush();
    if (target != repeated)
      return fail("generated node contribution emission is nondeterministic");
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

  auto generatedAbstime = parse(context, kGeneratedAbstimeContributionModule);
  if (!generatedAbstime)
    return fail("could not parse the generated abstime contribution fixture");
  std::string generatedAbstimeOutput;
  llvm::raw_string_ostream generatedAbstimeStream(generatedAbstimeOutput);
  if (mlir::failed(nodal::emitBackend(*generatedAbstime, nodal::BackendKind::VerilogA,
                                      generatedAbstimeStream)))
    return fail("generated abstime contribution emission failed");
  generatedAbstimeStream.flush();
  if (generatedAbstimeOutput.find("V(p, n) <+ ($abstime * 1);\n") == std::string::npos ||
      generatedAbstimeOutput.find("real waveform_") != std::string::npos)
    return fail("generated abstime lost its single owned per-occurrence expression");

  {
    auto forged = parse(context, kGeneratedAbstimeContributionModule);
    if (!forged)
      return fail("could not parse the generated abstime mutation fixture");
    mlir::Operation *abstime = nullptr;
    forged->walk([&](mlir::Operation *operation) {
      if (!abstime && operation->getName().getStringRef() == "nodal.analog_abstime")
        abstime = operation;
    });
    if (!abstime)
      return fail("generated abstime mutation anchor missing");
    abstime->setAttr("generated_owner",
                     mlir::StringAttr::get(&context, "GeneratedAbstimeContribution.forged"));
    std::string rejected = "sentinel";
    llvm::raw_string_ostream rejectedStream(rejected);
    if (mlir::succeeded(nodal::emitBackend(*forged, nodal::BackendKind::VerilogA, rejectedStream)))
      return fail("forged generated abstime ownership passed backend verification");
    rejectedStream.flush();
    if (rejected != "sentinel")
      return fail("forged generated abstime ownership published partial output");
  }

  for (const auto &mutation :
       {std::make_pair("%branch, %value", "%branch, %scale"),
        std::make_pair("        \"nodal.contribute\"(%branch, %value)",
                       "        \"nodal.contribute\"(%branch, %value) <{kind = \"potential\", "
                       "generated_owner = \"GeneratedAbstimeContribution.generate_0\", "
                       "generated_induction = \"GeneratedAbstimeContribution.generate_0.index\", "
                       "metadata = {}}> : (!nodal.branch<\"electrical\">, f64) -> ()\n"
                       "        \"nodal.contribute\"(%branch, %value)")}) {
    std::string invalidSource = kGeneratedAbstimeContributionModule.str();
    auto position = invalidSource.find(mutation.first);
    if (position == std::string::npos)
      return fail("generated abstime consumer mutation anchor missing");
    invalidSource.replace(position, std::string(mutation.first).size(), mutation.second);
    auto invalidModule = parse(context, invalidSource);
    if (!invalidModule)
      return fail("generated abstime consumer mutation did not parse");
    std::string rejected = "sentinel";
    llvm::raw_string_ostream rejectedStream(rejected);
    if (mlir::succeeded(
            nodal::emitBackend(*invalidModule, nodal::BackendKind::VerilogA, rejectedStream)))
      return fail("invalid generated abstime consumer passed backend verification");
    rejectedStream.flush();
    if (rejected != "sentinel")
      return fail("invalid generated abstime consumer published partial output");
  }

  auto generatedBoundStep = parse(context, kGeneratedBoundStepModule);
  if (!generatedBoundStep)
    return fail("could not parse the generated boundStep fixture");
  std::string generatedBoundStepOutput;
  llvm::raw_string_ostream generatedBoundStepStream(generatedBoundStepOutput);
  if (mlir::failed(nodal::emitBackend(*generatedBoundStep, nodal::BackendKind::VerilogA,
                                      generatedBoundStepStream)))
    return fail("generated boundStep emission failed");
  generatedBoundStepStream.flush();
  if (generatedBoundStepOutput.find("$bound_step(LIMIT);\n") == std::string::npos ||
      generatedBoundStepOutput.find("real waveform_") != std::string::npos)
    return fail("generated boundStep lost its single owned per-occurrence effect");

  for (bool duplicate : {false, true}) {
    auto forged = parse(context, kGeneratedBoundStepModule);
    if (!forged)
      return fail("could not parse the generated boundStep mutation fixture");
    mlir::Operation *boundStep = nullptr;
    forged->walk([&](mlir::Operation *operation) {
      if (!boundStep && operation->getName().getStringRef() == "nodal.analog_bound_step")
        boundStep = operation;
    });
    if (!boundStep)
      return fail("generated boundStep mutation anchor missing");
    if (duplicate) {
      mlir::Operation *second = boundStep->clone();
      second->setAttr("operator_id", mlir::StringAttr::get(&context, "GeneratedBoundStep.second"));
      auto insertionPoint = mlir::Block::iterator(boundStep);
      ++insertionPoint;
      boundStep->getBlock()->getOperations().insert(insertionPoint, second);
    } else {
      boundStep->setAttr("generated_owner",
                         mlir::StringAttr::get(&context, "GeneratedBoundStep.forged"));
    }
    std::string rejected = "sentinel";
    llvm::raw_string_ostream rejectedStream(rejected);
    if (mlir::succeeded(nodal::emitBackend(*forged, nodal::BackendKind::VerilogA, rejectedStream)))
      return fail(duplicate ? "multiple generated boundStep effects passed backend verification"
                            : "forged generated boundStep ownership passed backend verification");
    rejectedStream.flush();
    if (rejected != "sentinel")
      return fail("invalid generated boundStep published partial output");
  }

  auto generatedLaplace = parse(context, kGeneratedLaplaceContributionModule);
  if (!generatedLaplace)
    return fail("could not parse the generated laplaceNd contribution fixture");
  std::string generatedLaplaceOutput;
  llvm::raw_string_ostream generatedLaplaceStream(generatedLaplaceOutput);
  if (mlir::failed(nodal::emitBackend(*generatedLaplace, nodal::BackendKind::VerilogA,
                                      generatedLaplaceStream)))
    return fail("generated laplaceNd contribution emission failed");
  generatedLaplaceStream.flush();
  if (generatedLaplaceOutput.find("V(p, n) <+ laplace_nd(V(p, n), '{1}, '{1});\n") ==
          std::string::npos ||
      generatedLaplaceOutput.find("real transfer_") != std::string::npos)
    return fail("generated laplaceNd lost its single owned per-occurrence expression");

  for (const auto &[attribute, value] :
       {std::make_pair("generated_owner", "GeneratedLaplaceContribution.forged"),
        std::make_pair("state_id", "GeneratedLaplaceContribution.filtered.forged")}) {
    auto forged = parse(context, kGeneratedLaplaceContributionModule);
    if (!forged)
      return fail("could not parse the generated laplaceNd mutation fixture");
    mlir::Operation *laplace = nullptr;
    forged->walk([&](mlir::Operation *operation) {
      if (!laplace && operation->getName().getStringRef() == "nodal.analog_transfer")
        laplace = operation;
    });
    if (!laplace)
      return fail("generated laplaceNd mutation anchor missing");
    laplace->setAttr(attribute, mlir::StringAttr::get(&context, value));
    std::string rejected = "sentinel";
    llvm::raw_string_ostream rejectedStream(rejected);
    if (mlir::succeeded(nodal::emitBackend(*forged, nodal::BackendKind::VerilogA, rejectedStream)))
      return fail("forged generated laplaceNd ownership passed backend verification");
    rejectedStream.flush();
    if (rejected != "sentinel")
      return fail("forged generated laplaceNd ownership published partial output");
  }

  for (const auto &mutation :
       {std::make_pair("%branch, %filtered", "%branch, %input"),
        std::make_pair("        \"nodal.contribute\"(%branch, %filtered)",
                       "        \"nodal.contribute\"(%branch, %filtered) <{kind = \"potential\", "
                       "generated_owner = \"GeneratedLaplaceContribution.generate_0\", "
                       "generated_induction = \"GeneratedLaplaceContribution.generate_0.index\", "
                       "metadata = {}}> : (!nodal.branch<\"electrical\">, f64) -> ()\n"
                       "        \"nodal.contribute\"(%branch, %filtered)")}) {
    std::string invalidSource = kGeneratedLaplaceContributionModule.str();
    auto position = invalidSource.find(mutation.first);
    if (position == std::string::npos)
      return fail("generated laplaceNd consumer mutation anchor missing");
    invalidSource.replace(position, std::string(mutation.first).size(), mutation.second);
    auto invalidModule = parse(context, invalidSource);
    if (!invalidModule)
      return fail("generated laplaceNd consumer mutation did not parse");
    std::string rejected = "sentinel";
    llvm::raw_string_ostream rejectedStream(rejected);
    if (mlir::succeeded(
            nodal::emitBackend(*invalidModule, nodal::BackendKind::VerilogA, rejectedStream)))
      return fail("invalid generated laplaceNd consumer passed backend verification");
    rejectedStream.flush();
    if (rejected != "sentinel")
      return fail("invalid generated laplaceNd consumer published partial output");
  }

  auto generatedZi = parse(context, kGeneratedZiContributionModule);
  if (!generatedZi)
    return fail("could not parse the generated ziNd contribution fixture");
  std::string generatedZiOutput;
  llvm::raw_string_ostream generatedZiStream(generatedZiOutput);
  if (mlir::failed(
          nodal::emitBackend(*generatedZi, nodal::BackendKind::VerilogA, generatedZiStream)))
    return fail("generated ziNd contribution emission failed");
  generatedZiStream.flush();
  if (generatedZiOutput.find("V(p, n) <+ zi_nd(V(p, n), '{1}, '{1}, 1e-09);\n") ==
          std::string::npos ||
      generatedZiOutput.find("real transfer_") != std::string::npos)
    return fail("generated ziNd lost its single owned per-occurrence expression");

  for (const auto &[attribute, value] :
       {std::make_pair("generated_owner", "GeneratedZiContribution.forged"),
        std::make_pair("state_id", "GeneratedZiContribution.filtered.forged")}) {
    auto forged = parse(context, kGeneratedZiContributionModule);
    if (!forged)
      return fail("could not parse the generated ziNd mutation fixture");
    mlir::Operation *zi = nullptr;
    forged->walk([&](mlir::Operation *operation) {
      if (!zi && operation->getName().getStringRef() == "nodal.analog_transfer")
        zi = operation;
    });
    if (!zi)
      return fail("generated ziNd mutation anchor missing");
    zi->setAttr(attribute, mlir::StringAttr::get(&context, value));
    std::string rejected = "sentinel";
    llvm::raw_string_ostream rejectedStream(rejected);
    if (mlir::succeeded(nodal::emitBackend(*forged, nodal::BackendKind::VerilogA, rejectedStream)))
      return fail("forged generated ziNd ownership passed backend verification");
    rejectedStream.flush();
    if (rejected != "sentinel")
      return fail("forged generated ziNd ownership published partial output");
  }

  for (const auto &mutation :
       {std::make_pair("%branch, %filtered", "%branch, %input"),
        std::make_pair("        \"nodal.contribute\"(%branch, %filtered)",
                       "        \"nodal.contribute\"(%branch, %filtered) <{kind = \"potential\", "
                       "generated_owner = \"GeneratedZiContribution.generate_0\", "
                       "generated_induction = \"GeneratedZiContribution.generate_0.index\", "
                       "metadata = {}}> : (!nodal.branch<\"electrical\">, f64) -> ()\n"
                       "        \"nodal.contribute\"(%branch, %filtered)")}) {
    std::string invalidSource = kGeneratedZiContributionModule.str();
    auto position = invalidSource.find(mutation.first);
    if (position == std::string::npos)
      return fail("generated ziNd consumer mutation anchor missing");
    invalidSource.replace(position, std::string(mutation.first).size(), mutation.second);
    auto invalidModule = parse(context, invalidSource);
    if (!invalidModule)
      return fail("generated ziNd consumer mutation did not parse");
    std::string rejected = "sentinel";
    llvm::raw_string_ostream rejectedStream(rejected);
    if (mlir::succeeded(
            nodal::emitBackend(*invalidModule, nodal::BackendKind::VerilogA, rejectedStream)))
      return fail("invalid generated ziNd consumer passed backend verification");
    rejectedStream.flush();
    if (rejected != "sentinel")
      return fail("invalid generated ziNd consumer published partial output");
  }

  auto generatedWhiteNoise = parse(context, kGeneratedWhiteNoiseContributionModule);
  if (!generatedWhiteNoise)
    return fail("could not parse the generated whiteNoise contribution fixture");
  std::string generatedWhiteNoiseOutput;
  llvm::raw_string_ostream generatedWhiteNoiseStream(generatedWhiteNoiseOutput);
  if (mlir::failed(nodal::emitBackend(*generatedWhiteNoise, nodal::BackendKind::VerilogA,
                                      generatedWhiteNoiseStream)))
    return fail("generated whiteNoise contribution emission failed");
  generatedWhiteNoiseStream.flush();
  if (generatedWhiteNoiseOutput.find("I(p, n) <+ white_noise(1, \"generated white\");\n") ==
          std::string::npos ||
      generatedWhiteNoiseOutput.find("real noise_") != std::string::npos)
    return fail("generated whiteNoise lost its single owned per-occurrence expression");

  for (const auto &[attribute, value] :
       {std::make_pair("generated_owner", "GeneratedWhiteNoiseContribution.forged"),
        std::make_pair("source_id", "GeneratedWhiteNoiseContribution.forged")}) {
    auto forged = parse(context, kGeneratedWhiteNoiseContributionModule);
    if (!forged)
      return fail("could not parse the generated whiteNoise mutation fixture");
    mlir::Operation *noise = nullptr;
    forged->walk([&](mlir::Operation *operation) {
      if (!noise && operation->getName().getStringRef() == "nodal.analog_noise")
        noise = operation;
    });
    if (!noise)
      return fail("generated whiteNoise mutation anchor missing");
    noise->setAttr(attribute, mlir::StringAttr::get(&context, value));
    std::string rejected = "sentinel";
    llvm::raw_string_ostream rejectedStream(rejected);
    if (mlir::succeeded(nodal::emitBackend(*forged, nodal::BackendKind::VerilogA, rejectedStream)))
      return fail("forged generated whiteNoise ownership passed backend verification");
    rejectedStream.flush();
    if (rejected != "sentinel")
      return fail("forged generated whiteNoise ownership published partial output");
  }

  for (const auto &mutation :
       {std::make_pair("%branch, %noise", "%branch, %amp"),
        std::make_pair("        \"nodal.contribute\"(%branch, %noise)",
                       "        \"nodal.contribute\"(%branch, %noise) <{kind = \"flow\", "
                       "generated_owner = \"GeneratedWhiteNoiseContribution.generate_0\", "
                       "generated_induction = "
                       "\"GeneratedWhiteNoiseContribution.generate_0.index\", metadata = {}}> : "
                       "(!nodal.branch<\"electrical\">, f64) -> ()\n"
                       "        \"nodal.contribute\"(%branch, %noise)")}) {
    std::string invalidSource = kGeneratedWhiteNoiseContributionModule.str();
    auto position = invalidSource.find(mutation.first);
    if (position == std::string::npos)
      return fail("generated whiteNoise consumer mutation anchor missing");
    invalidSource.replace(position, std::string(mutation.first).size(), mutation.second);
    auto invalidModule = parse(context, invalidSource);
    if (!invalidModule)
      return fail("generated whiteNoise consumer mutation did not parse");
    std::string rejected = "sentinel";
    llvm::raw_string_ostream rejectedStream(rejected);
    if (mlir::succeeded(
            nodal::emitBackend(*invalidModule, nodal::BackendKind::VerilogA, rejectedStream)))
      return fail("invalid generated whiteNoise consumer passed backend verification");
    rejectedStream.flush();
    if (rejected != "sentinel")
      return fail("invalid generated whiteNoise consumer published partial output");
  }

  auto generatedFlickerNoise = parse(context, kGeneratedFlickerNoiseContributionModule);
  if (!generatedFlickerNoise)
    return fail("could not parse the generated flickerNoise contribution fixture");
  std::string generatedFlickerNoiseOutput;
  llvm::raw_string_ostream generatedFlickerNoiseStream(generatedFlickerNoiseOutput);
  if (mlir::failed(nodal::emitBackend(*generatedFlickerNoise, nodal::BackendKind::VerilogA,
                                      generatedFlickerNoiseStream)))
    return fail("generated flickerNoise contribution emission failed");
  generatedFlickerNoiseStream.flush();
  if (generatedFlickerNoiseOutput.find(
          "I(p, n) <+ flicker_noise(1, 1, \"generated flicker\");\n") == std::string::npos ||
      generatedFlickerNoiseOutput.find("real noise_") != std::string::npos)
    return fail("generated flickerNoise lost its single owned per-occurrence expression");

  for (const auto &[attribute, value] :
       {std::make_pair("generated_owner", "GeneratedFlickerNoiseContribution.forged"),
        std::make_pair("source_id", "GeneratedFlickerNoiseContribution.forged"),
        std::make_pair("noise_kind", "table")}) {
    auto forged = parse(context, kGeneratedFlickerNoiseContributionModule);
    if (!forged)
      return fail("could not parse the generated flickerNoise mutation fixture");
    mlir::Operation *noise = nullptr;
    forged->walk([&](mlir::Operation *operation) {
      if (!noise && operation->getName().getStringRef() == "nodal.analog_noise")
        noise = operation;
    });
    if (!noise)
      return fail("generated flickerNoise mutation anchor missing");
    noise->setAttr(attribute, mlir::StringAttr::get(&context, value));
    std::string rejected = "sentinel";
    llvm::raw_string_ostream rejectedStream(rejected);
    if (mlir::succeeded(nodal::emitBackend(*forged, nodal::BackendKind::VerilogA, rejectedStream)))
      return fail("forged generated flickerNoise metadata passed backend verification");
    rejectedStream.flush();
    if (rejected != "sentinel")
      return fail("forged generated flickerNoise metadata published partial output");
  }

  for (const auto &mutation :
       {std::make_pair("%branch, %noise", "%branch, %amp"),
        std::make_pair("        \"nodal.contribute\"(%branch, %noise)",
                       "        \"nodal.contribute\"(%branch, %noise) <{kind = \"flow\", "
                       "generated_owner = \"GeneratedFlickerNoiseContribution.generate_0\", "
                       "generated_induction = "
                       "\"GeneratedFlickerNoiseContribution.generate_0.index\", metadata = {}}> "
                       ": (!nodal.branch<\"electrical\">, f64) -> ()\n"
                       "        \"nodal.contribute\"(%branch, %noise)")}) {
    std::string invalidSource = kGeneratedFlickerNoiseContributionModule.str();
    auto position = invalidSource.find(mutation.first);
    if (position == std::string::npos)
      return fail("generated flickerNoise consumer mutation anchor missing");
    invalidSource.replace(position, std::string(mutation.first).size(), mutation.second);
    auto invalidModule = parse(context, invalidSource);
    if (!invalidModule)
      return fail("generated flickerNoise consumer mutation did not parse");
    std::string rejected = "sentinel";
    llvm::raw_string_ostream rejectedStream(rejected);
    if (mlir::succeeded(
            nodal::emitBackend(*invalidModule, nodal::BackendKind::VerilogA, rejectedStream)))
      return fail("invalid generated flickerNoise consumer passed backend verification");
    rejectedStream.flush();
    if (rejected != "sentinel")
      return fail("invalid generated flickerNoise consumer published partial output");
  }

  auto generatedTableNoise = parse(context, kGeneratedTableNoiseContributionModule);
  if (!generatedTableNoise)
    return fail("could not parse the generated tableNoise contribution fixture");
  std::string generatedTableNoiseOutput;
  llvm::raw_string_ostream generatedTableNoiseStream(generatedTableNoiseOutput);
  if (mlir::failed(nodal::emitBackend(*generatedTableNoise, nodal::BackendKind::VerilogA,
                                      generatedTableNoiseStream)))
    return fail("generated tableNoise contribution emission failed");
  generatedTableNoiseStream.flush();
  if (generatedTableNoiseOutput.find("I(p, n) <+ noise_table('{1, 1}, \"generated table\");\n") ==
          std::string::npos ||
      generatedTableNoiseOutput.find("real noise_") != std::string::npos)
    return fail("generated tableNoise lost its single owned per-occurrence expression");

  for (const auto &[attribute, value] :
       {std::make_pair("generated_owner", "GeneratedTableNoiseContribution.forged"),
        std::make_pair("source_id", "GeneratedTableNoiseContribution.forged"),
        std::make_pair("noise_kind", "white")}) {
    auto forged = parse(context, kGeneratedTableNoiseContributionModule);
    if (!forged)
      return fail("could not parse the generated tableNoise mutation fixture");
    mlir::Operation *noise = nullptr;
    forged->walk([&](mlir::Operation *operation) {
      if (!noise && operation->getName().getStringRef() == "nodal.analog_noise")
        noise = operation;
    });
    if (!noise)
      return fail("generated tableNoise mutation anchor missing");
    noise->setAttr(attribute, mlir::StringAttr::get(&context, value));
    std::string rejected = "sentinel";
    llvm::raw_string_ostream rejectedStream(rejected);
    if (mlir::succeeded(nodal::emitBackend(*forged, nodal::BackendKind::VerilogA, rejectedStream)))
      return fail("forged generated tableNoise metadata passed backend verification");
    rejectedStream.flush();
    if (rejected != "sentinel")
      return fail("forged generated tableNoise metadata published partial output");
  }

  for (const auto &mutation :
       {std::make_pair("%branch, %noise", "%branch, %amp"),
        std::make_pair("        \"nodal.contribute\"(%branch, %noise)",
                       "        \"nodal.contribute\"(%branch, %noise) <{kind = \"flow\", "
                       "generated_owner = \"GeneratedTableNoiseContribution.generate_0\", "
                       "generated_induction = "
                       "\"GeneratedTableNoiseContribution.generate_0.index\", metadata = {}}> "
                       ": (!nodal.branch<\"electrical\">, f64) -> ()\n"
                       "        \"nodal.contribute\"(%branch, %noise)")}) {
    std::string invalidSource = kGeneratedTableNoiseContributionModule.str();
    auto position = invalidSource.find(mutation.first);
    if (position == std::string::npos)
      return fail("generated tableNoise consumer mutation anchor missing");
    invalidSource.replace(position, std::string(mutation.first).size(), mutation.second);
    auto invalidModule = parse(context, invalidSource);
    if (!invalidModule)
      return fail("generated tableNoise consumer mutation did not parse");
    std::string rejected = "sentinel";
    llvm::raw_string_ostream rejectedStream(rejected);
    if (mlir::succeeded(
            nodal::emitBackend(*invalidModule, nodal::BackendKind::VerilogA, rejectedStream)))
      return fail("invalid generated tableNoise consumer passed backend verification");
    rejectedStream.flush();
    if (rejected != "sentinel")
      return fail("invalid generated tableNoise consumer published partial output");
  }

  auto generatedLexicalStorage = parse(context, kGeneratedLexicalStorageModule);
  if (!generatedLexicalStorage)
    return fail("could not parse the generated lexical-storage fixture");
  std::string generatedLexicalStorageOutput;
  llvm::raw_string_ostream generatedLexicalStorageStream(generatedLexicalStorageOutput);
  if (mlir::failed(nodal::emitBackend(*generatedLexicalStorage, nodal::BackendKind::VerilogA,
                                      generatedLexicalStorageStream)))
    return fail("generated lexical-storage emission failed");
  generatedLexicalStorageStream.flush();
  auto lexicalLoop = generatedLexicalStorageOutput.find("for (generate_0_index");
  auto lexicalDeclaration =
      generatedLexicalStorageOutput.find("real event_GeneratedLexicalStorage_state = 0.0;");
  auto lexicalProcedure =
      generatedLexicalStorageOutput.find("begin : event_GeneratedLexicalStorage_procedure");
  auto lexicalRead =
      generatedLexicalStorageOutput.find("event_GeneratedLexicalStorage_assignment_read_0 = "
                                         "event_GeneratedLexicalStorage_state;");
  auto lexicalAssignment = generatedLexicalStorageOutput.find(
      "event_GeneratedLexicalStorage_state = "
      "(event_GeneratedLexicalStorage_assignment_read_0 + 1.0);");
  if (lexicalLoop == std::string::npos || lexicalDeclaration == std::string::npos ||
      lexicalProcedure == std::string::npos || lexicalRead == std::string::npos ||
      lexicalAssignment == std::string::npos ||
      !(lexicalLoop < lexicalDeclaration && lexicalDeclaration < lexicalProcedure &&
        lexicalProcedure < lexicalRead && lexicalRead < lexicalAssignment) ||
      generatedLexicalStorageOutput.find("@(") != std::string::npos ||
      mlir::failed(nodal::reparseBackendTarget(generatedLexicalStorageOutput, *configuration)))
    return fail("generated lexical storage lost its per-occurrence target layout");

  {
    auto invalid = parse(context, kGeneratedLexicalStorageModule);
    if (!invalid)
      return fail("could not parse the generated lexical-storage mutation fixture");
    mlir::Operation *variable = nullptr;
    invalid->walk([&](mlir::Operation *operation) {
      if (!variable && operation->getName().getStringRef() == "nodal.analog_variable")
        variable = operation;
    });
    if (!variable)
      return fail("generated lexical-storage mutation anchor missing");
    variable->setAttr("generated_owner",
                      mlir::StringAttr::get(&context, "GeneratedLexicalStorage.forged"));
    std::string rejected = "sentinel";
    llvm::raw_string_ostream rejectedStream(rejected);
    if (mlir::succeeded(nodal::emitBackend(*invalid, nodal::BackendKind::VerilogA, rejectedStream)))
      return fail("forged generated lexical ownership passed backend verification");
    rejectedStream.flush();
    if (rejected != "sentinel")
      return fail("invalid generated lexical storage published partial output");
  }

  {
    std::string unsupportedSource = kGeneratedLexicalStorageModule.str();
    const std::string anchor = "        }) : () -> ()\n      }) : () -> ()";
    const std::string unsupportedScope =
        "          \"nodal.analog_scope\"() <{metadata = {semantic_path = "
        "\"GeneratedLexicalStorage.scope_0\"}, owner = \"GeneratedLexicalStorage\", "
        "scope_id = \"GeneratedLexicalStorage.scope_0\"}> ({\n"
        "          ^bb0:\n"
        "          }) : () -> ()\n";
    auto position = unsupportedSource.find(anchor);
    if (position == std::string::npos)
      return fail("generated lexical-storage scope mutation anchor missing");
    unsupportedSource.insert(position, unsupportedScope);
    auto unsupported = parse(context, unsupportedSource);
    if (!unsupported)
      return fail("generated lexical-storage scope mutation did not parse");
    std::string rejected = "sentinel";
    llvm::raw_string_ostream rejectedStream(rejected);
    if (mlir::succeeded(
            nodal::emitBackend(*unsupported, nodal::BackendKind::VerilogA, rejectedStream)))
      return fail("unsupported generated lexical scope passed backend verification");
    rejectedStream.flush();
    if (rejected != "sentinel")
      return fail("unsupported generated lexical scope published partial output");
  }

  auto generatedConditionalStorage = parse(context, kGeneratedConditionalLexicalStorageModule);
  if (!generatedConditionalStorage)
    return fail("could not parse the generated conditional lexical-storage fixture");
  std::string generatedConditionalOutput;
  llvm::raw_string_ostream generatedConditionalStream(generatedConditionalOutput);
  if (mlir::failed(nodal::emitBackend(*generatedConditionalStorage, nodal::BackendKind::VerilogA,
                                      generatedConditionalStream)))
    return fail("generated conditional lexical-storage emission failed");
  generatedConditionalStream.flush();
  auto conditionalLoop = generatedConditionalOutput.find("for (generate_0_index");
  auto conditionalDeclaration = generatedConditionalOutput.find(
      "integer event_GeneratedConditionalLexicalStorage_select = 0;");
  auto conditionalIf =
      generatedConditionalOutput.find("if (event_GeneratedConditionalLexicalStorage_select) begin");
  auto conditionalFirst = generatedConditionalOutput.find(
      "event_GeneratedConditionalLexicalStorage_state = "
      "(event_GeneratedConditionalLexicalStorage_assignment_read_0 + 1.0);");
  auto conditionalElse = generatedConditionalOutput.find("else begin", conditionalIf);
  auto conditionalSecond = generatedConditionalOutput.find(
      "event_GeneratedConditionalLexicalStorage_state = "
      "(event_GeneratedConditionalLexicalStorage_assignment_else_read_0 + 2.0);");
  if (conditionalLoop == std::string::npos || conditionalDeclaration == std::string::npos ||
      conditionalIf == std::string::npos || conditionalFirst == std::string::npos ||
      conditionalElse == std::string::npos || conditionalSecond == std::string::npos ||
      !(conditionalLoop < conditionalDeclaration && conditionalDeclaration < conditionalIf &&
        conditionalIf < conditionalFirst && conditionalFirst < conditionalElse &&
        conditionalElse < conditionalSecond) ||
      generatedConditionalOutput.find("@(") != std::string::npos ||
      mlir::failed(nodal::reparseBackendTarget(generatedConditionalOutput, *configuration)))
    return fail("generated conditional lexical storage lost its authored target layout");

  {
    auto invalid = parse(context, kGeneratedConditionalLexicalStorageModule);
    if (!invalid)
      return fail("could not parse the generated conditional ownership mutation fixture");
    mlir::Operation *assignment = nullptr;
    invalid->walk([&](mlir::Operation *operation) {
      if (!assignment && operation->getName().getStringRef() == "nodal.analog_assign")
        assignment = operation;
    });
    if (!assignment)
      return fail("generated conditional ownership mutation anchor missing");
    assignment->setAttr(
        "generated_owner",
        mlir::StringAttr::get(&context, "GeneratedConditionalLexicalStorage.forged"));
    std::string rejected = "sentinel";
    llvm::raw_string_ostream rejectedStream(rejected);
    if (mlir::succeeded(nodal::emitBackend(*invalid, nodal::BackendKind::VerilogA, rejectedStream)))
      return fail("forged generated conditional storage ownership passed backend verification");
    rejectedStream.flush();
    if (rejected != "sentinel")
      return fail("forged generated conditional storage published partial output");
  }

  {
    std::string unsupportedSource = kGeneratedConditionalLexicalStorageModule.str();
    const std::string anchor = "              %state_read = \"nodal.analog_variable_read\"(%state)";
    const std::string unsupportedScope =
        "              \"nodal.analog_scope\"() <{metadata = {semantic_path = "
        "\"GeneratedConditionalLexicalStorage.scope_0\"}, owner = "
        "\"GeneratedConditionalLexicalStorage\", scope_id = "
        "\"GeneratedConditionalLexicalStorage.scope_0\"}> ({\n"
        "              ^bb0:\n"
        "              }) : () -> ()\n";
    auto position = unsupportedSource.find(anchor);
    if (position == std::string::npos)
      return fail("generated conditional lexical scope mutation anchor missing");
    unsupportedSource.insert(position, unsupportedScope);
    auto unsupported = parse(context, unsupportedSource);
    if (!unsupported)
      return fail("generated conditional lexical scope mutation did not parse");
    std::string rejected = "sentinel";
    llvm::raw_string_ostream rejectedStream(rejected);
    if (mlir::succeeded(
            nodal::emitBackend(*unsupported, nodal::BackendKind::VerilogA, rejectedStream)))
      return fail("unsupported nested generated conditional scope passed backend verification");
    rejectedStream.flush();
    if (rejected != "sentinel")
      return fail("unsupported nested generated conditional scope published partial output");
  }

  auto generatedCaseStorage = parse(context, kGeneratedCaseLexicalStorageModule);
  if (!generatedCaseStorage)
    return fail("could not parse the generated case lexical-storage fixture");
  std::string generatedCaseOutput;
  llvm::raw_string_ostream generatedCaseStream(generatedCaseOutput);
  if (mlir::failed(nodal::emitBackend(*generatedCaseStorage, nodal::BackendKind::VerilogA,
                                      generatedCaseStream)))
    return fail("generated case lexical-storage emission failed");
  generatedCaseStream.flush();
  auto caseLoop = generatedCaseOutput.find("for (generate_0_index");
  auto caseDeclaration =
      generatedCaseOutput.find("integer event_GeneratedCaseLexicalStorage_mode = 0;");
  auto caseTree = generatedCaseOutput.find("case (event_GeneratedCaseLexicalStorage_mode)");
  auto caseArm = generatedCaseOutput.find("0: begin", caseTree);
  auto caseFirst =
      generatedCaseOutput.find("event_GeneratedCaseLexicalStorage_state = "
                               "(event_GeneratedCaseLexicalStorage_assignment_read_0 + 3.0);");
  auto caseDefault = generatedCaseOutput.find("default: begin", caseTree);
  auto caseSecond = generatedCaseOutput.find(
      "event_GeneratedCaseLexicalStorage_state = "
      "(event_GeneratedCaseLexicalStorage_assignment_default_read_0 + 4.0);");
  if (caseLoop == std::string::npos || caseDeclaration == std::string::npos ||
      caseTree == std::string::npos || caseArm == std::string::npos ||
      caseFirst == std::string::npos || caseDefault == std::string::npos ||
      caseSecond == std::string::npos ||
      !(caseLoop < caseDeclaration && caseDeclaration < caseTree && caseTree < caseArm &&
        caseArm < caseFirst && caseFirst < caseDefault && caseDefault < caseSecond) ||
      generatedCaseOutput.find("@(") != std::string::npos ||
      mlir::failed(nodal::reparseBackendTarget(generatedCaseOutput, *configuration)))
    return fail("generated case lexical storage lost its authored target layout");

  {
    auto invalid = parse(context, kGeneratedCaseLexicalStorageModule);
    if (!invalid)
      return fail("could not parse the generated case ownership mutation fixture");
    mlir::Operation *assignment = nullptr;
    invalid->walk([&](mlir::Operation *operation) {
      if (!assignment && operation->getName().getStringRef() == "nodal.analog_assign")
        assignment = operation;
    });
    if (!assignment)
      return fail("generated case ownership mutation anchor missing");
    assignment->setAttr("generated_owner",
                        mlir::StringAttr::get(&context, "GeneratedCaseLexicalStorage.forged"));
    std::string rejected = "sentinel";
    llvm::raw_string_ostream rejectedStream(rejected);
    if (mlir::succeeded(nodal::emitBackend(*invalid, nodal::BackendKind::VerilogA, rejectedStream)))
      return fail("forged generated case storage ownership passed backend verification");
    rejectedStream.flush();
    if (rejected != "sentinel")
      return fail("forged generated case storage published partial output");
  }

  {
    std::string unsupportedSource = kGeneratedCaseLexicalStorageModule.str();
    const std::string anchor = "              %state_read = \"nodal.analog_variable_read\"(%state)";
    const std::string unsupportedScope =
        "              \"nodal.analog_scope\"() <{metadata = {semantic_path = "
        "\"GeneratedCaseLexicalStorage.scope_0\"}, owner = \"GeneratedCaseLexicalStorage\", "
        "scope_id = \"GeneratedCaseLexicalStorage.scope_0\"}> ({\n"
        "              ^bb0:\n"
        "              }) : () -> ()\n";
    auto position = unsupportedSource.find(anchor);
    if (position == std::string::npos)
      return fail("generated case lexical scope mutation anchor missing");
    unsupportedSource.insert(position, unsupportedScope);
    auto unsupported = parse(context, unsupportedSource);
    if (!unsupported)
      return fail("generated case lexical scope mutation did not parse");
    std::string rejected = "sentinel";
    llvm::raw_string_ostream rejectedStream(rejected);
    if (mlir::succeeded(
            nodal::emitBackend(*unsupported, nodal::BackendKind::VerilogA, rejectedStream)))
      return fail("unsupported nested generated case scope passed backend verification");
    rejectedStream.flush();
    if (rejected != "sentinel")
      return fail("unsupported nested generated case scope published partial output");
  }

  auto generatedLoopStorage = parse(context, kGeneratedLoopLexicalStorageModule);
  if (!generatedLoopStorage)
    return fail("could not parse the generated loop lexical-storage fixture");
  std::string generatedLoopOutput;
  llvm::raw_string_ostream generatedLoopStream(generatedLoopOutput);
  if (mlir::failed(nodal::emitBackend(*generatedLoopStorage, nodal::BackendKind::VerilogA,
                                      generatedLoopStream)))
    return fail("generated loop lexical-storage emission failed");
  generatedLoopStream.flush();
  auto generatedOuterLoop = generatedLoopOutput.find("for (generate_0_index");
  auto generatedIterations =
      generatedLoopOutput.find("integer event_GeneratedLoopLexicalStorage_iterations = 1;");
  auto generatedState =
      generatedLoopOutput.find("real event_GeneratedLoopLexicalStorage_state = 0.0;");
  auto generatedRuntimeLoop =
      generatedLoopOutput.find("for (event_GeneratedLoopLexicalStorage_loop_0_index = 0;");
  auto generatedAssignment = generatedLoopOutput.find(
      "event_GeneratedLoopLexicalStorage_state = "
      "(event_GeneratedLoopLexicalStorage_loop_0_assignment_read_0 + 5.0);");
  if (generatedOuterLoop == std::string::npos || generatedIterations == std::string::npos ||
      generatedState == std::string::npos || generatedRuntimeLoop == std::string::npos ||
      generatedAssignment == std::string::npos ||
      !(generatedOuterLoop < generatedIterations && generatedIterations < generatedState &&
        generatedState < generatedRuntimeLoop && generatedRuntimeLoop < generatedAssignment) ||
      generatedLoopOutput.find("@(") != std::string::npos ||
      mlir::failed(nodal::reparseBackendTarget(generatedLoopOutput, *configuration)))
    return fail("generated loop lexical storage lost its per-occurrence target layout");

  {
    std::string exitSource = kGeneratedLoopLexicalStorageModule.str();
    const std::string assignmentAnchor = "            \"nodal.analog_assign\"(%state, %state_read)";
    auto assignmentPosition = exitSource.find(assignmentAnchor);
    if (assignmentPosition == std::string::npos)
      return fail("generated loop exit assignment anchor missing");
    auto assignmentEnd = exitSource.find('\n', assignmentPosition);
    if (assignmentEnd == std::string::npos)
      return fail("generated loop exit assignment terminator missing");
    const std::string exits =
        "            \"nodal.analog_continue\"() <{metadata = {semantic_path = "
        "\"GeneratedLoopLexicalStorage.loop_0.continue_0\"}, owner = "
        "\"GeneratedLoopLexicalStorage\", statement_id = "
        "\"GeneratedLoopLexicalStorage.loop_0.continue_0\"}> : () -> ()\n"
        "            \"nodal.analog_break\"() <{metadata = {semantic_path = "
        "\"GeneratedLoopLexicalStorage.loop_0.break_1\"}, owner = "
        "\"GeneratedLoopLexicalStorage\", statement_id = "
        "\"GeneratedLoopLexicalStorage.loop_0.break_1\"}> : () -> ()\n";
    exitSource.insert(assignmentEnd + 1, exits);

    auto exitModule = parse(context, exitSource);
    if (!exitModule)
      return fail("generated loop exit fixture did not parse");
    std::string exitOutput;
    llvm::raw_string_ostream exitStream(exitOutput);
    if (mlir::failed(nodal::emitBackend(*exitModule, nodal::BackendKind::VerilogA, exitStream)))
      return fail("generated loop exit emission failed");
    exitStream.flush();
    auto exitRuntimeLoop =
        exitOutput.find("for (event_GeneratedLoopLexicalStorage_loop_0_index = 0;");
    auto exitAssignment = exitOutput.find(" + 5.0);", exitRuntimeLoop);
    auto continueFlag = exitOutput.find("_continue = 1;", exitAssignment);
    auto breakFlag = exitOutput.find("_break = 1;", continueFlag);
    if (exitRuntimeLoop == std::string::npos || exitAssignment == std::string::npos ||
        continueFlag == std::string::npos || breakFlag == std::string::npos ||
        !(exitRuntimeLoop < exitAssignment && exitAssignment < continueFlag &&
          continueFlag < breakFlag) ||
        mlir::failed(nodal::reparseBackendTarget(exitOutput, *configuration)))
      return fail("generated loop exits lost their per-occurrence target layout");

    auto forged = parse(context, exitSource);
    if (!forged)
      return fail("generated loop exit ownership fixture did not parse");
    mlir::Operation *continueOperation = nullptr;
    forged->walk([&](mlir::Operation *operation) {
      if (!continueOperation && operation->getName().getStringRef() == "nodal.analog_continue")
        continueOperation = operation;
    });
    if (!continueOperation)
      return fail("generated loop exit ownership anchor missing");
    continueOperation->setAttr(
        "owner", mlir::StringAttr::get(&context, "GeneratedLoopLexicalStorage.forged"));
    std::string rejected = "sentinel";
    llvm::raw_string_ostream rejectedStream(rejected);
    if (mlir::succeeded(nodal::emitBackend(*forged, nodal::BackendKind::VerilogA, rejectedStream)))
      return fail("forged generated loop exit ownership passed backend verification");
    rejectedStream.flush();
    if (rejected != "sentinel")
      return fail("forged generated loop exit published partial output");

    std::string duplicateSource = exitSource;
    auto duplicatePosition =
        duplicateSource.find("            \"nodal.analog_break\"()", assignmentEnd + 1);
    if (duplicatePosition == std::string::npos)
      return fail("generated loop duplicate-exit mutation anchor missing");
    std::string duplicateBreak = exits.substr(exits.find("            \"nodal.analog_break\"()"));
    for (auto position = duplicateBreak.find("break_1"); position != std::string::npos;
         position = duplicateBreak.find("break_1", position + 7))
      duplicateBreak.replace(position, 7, "break_2");
    duplicateSource.insert(duplicatePosition, duplicateBreak);
    auto duplicate = parse(context, duplicateSource);
    if (!duplicate)
      return fail("generated loop duplicate-exit mutation did not parse");
    rejected = "sentinel";
    llvm::raw_string_ostream duplicateRejectedStream(rejected);
    if (mlir::succeeded(
            nodal::emitBackend(*duplicate, nodal::BackendKind::VerilogA, duplicateRejectedStream)))
      return fail("duplicate generated loop exit passed backend verification");
    duplicateRejectedStream.flush();
    if (rejected != "sentinel")
      return fail("duplicate generated loop exit published partial output");

    std::string deeperSource = kGeneratedLoopLexicalStorageModule.str();
    const std::string readAnchor =
        "            %state_read = \"nodal.analog_variable_read\"(%state)";
    auto deeperPosition = deeperSource.find(readAnchor);
    if (deeperPosition == std::string::npos)
      return fail("generated loop deeper-exit mutation anchor missing");
    const std::string deeperExits =
        "            \"nodal.analog_scope\"() <{metadata = {semantic_path = "
        "\"GeneratedLoopLexicalStorage.loop_0.scope_0\"}, owner = "
        "\"GeneratedLoopLexicalStorage\", scope_id = "
        "\"GeneratedLoopLexicalStorage.loop_0.scope_0\"}> ({\n"
        "            ^bb0:\n" +
        exits + "            }) : () -> ()\n";
    deeperSource.insert(deeperPosition, deeperExits);
    auto deeper = parse(context, deeperSource);
    if (!deeper)
      return fail("generated loop deeper-exit mutation did not parse");
    rejected = "sentinel";
    llvm::raw_string_ostream deeperRejectedStream(rejected);
    if (mlir::succeeded(
            nodal::emitBackend(*deeper, nodal::BackendKind::VerilogA, deeperRejectedStream)))
      return fail("scope-contained generated loop exits passed backend verification");
    deeperRejectedStream.flush();
    if (rejected != "sentinel")
      return fail("scope-contained generated loop exits published partial output");
  }

  {
    auto invalid = parse(context, kGeneratedLoopLexicalStorageModule);
    if (!invalid)
      return fail("could not parse the generated loop ownership mutation fixture");
    mlir::Operation *assignment = nullptr;
    invalid->walk([&](mlir::Operation *operation) {
      if (!assignment && operation->getName().getStringRef() == "nodal.analog_assign")
        assignment = operation;
    });
    if (!assignment)
      return fail("generated loop ownership mutation anchor missing");
    assignment->setAttr("generated_owner",
                        mlir::StringAttr::get(&context, "GeneratedLoopLexicalStorage.forged"));
    std::string rejected = "sentinel";
    llvm::raw_string_ostream rejectedStream(rejected);
    if (mlir::succeeded(nodal::emitBackend(*invalid, nodal::BackendKind::VerilogA, rejectedStream)))
      return fail("forged generated loop storage ownership passed backend verification");
    rejectedStream.flush();
    if (rejected != "sentinel")
      return fail("forged generated loop storage published partial output");
  }

  {
    std::string scopedSource = kGeneratedLoopLexicalStorageModule.str();
    const std::string anchor = "            %state_read = \"nodal.analog_variable_read\"(%state)";
    const std::string scopeHeader =
        "            \"nodal.analog_scope\"() <{metadata = {semantic_path = "
        "\"GeneratedLoopLexicalStorage.loop_0.scope_0\"}, owner = "
        "\"GeneratedLoopLexicalStorage\", scope_id = "
        "\"GeneratedLoopLexicalStorage.loop_0.scope_0\"}> ({\n"
        "            ^bb0:\n";
    const std::string assignmentAnchor = "            \"nodal.analog_assign\"(%state, %state_read)";
    auto position = scopedSource.find(anchor);
    if (position == std::string::npos)
      return fail("generated loop lexical scope mutation anchor missing");
    scopedSource.insert(position, scopeHeader);
    auto assignmentPosition = scopedSource.find(assignmentAnchor, position + scopeHeader.size());
    if (assignmentPosition == std::string::npos)
      return fail("generated loop lexical scope assignment anchor missing");
    auto assignmentEnd = scopedSource.find('\n', assignmentPosition);
    if (assignmentEnd == std::string::npos)
      return fail("generated loop lexical scope assignment terminator missing");
    scopedSource.insert(assignmentEnd + 1, "            }) : () -> ()\n");

    auto scoped = parse(context, scopedSource);
    if (!scoped)
      return fail("generated loop lexical scope fixture did not parse");
    std::string scopedOutput;
    llvm::raw_string_ostream scopedStream(scopedOutput);
    if (mlir::failed(nodal::emitBackend(*scoped, nodal::BackendKind::VerilogA, scopedStream)))
      return fail("generated loop lexical scope emission failed");
    scopedStream.flush();
    auto scopedOuter = scopedOutput.find("for (generate_0_index");
    auto scopedRuntime =
        scopedOutput.find("for (event_GeneratedLoopLexicalStorage_loop_0_index = 0;");
    auto scopedLoopBegin = scopedOutput.find("begin", scopedRuntime);
    auto scopedBegin = scopedOutput.find("begin", scopedLoopBegin + 5);
    auto scopedAssignment =
        scopedOutput.find("event_GeneratedLoopLexicalStorage_state = "
                          "(event_GeneratedLoopLexicalStorage_loop_0_assignment_read_0 + 5.0);");
    if (scopedOuter == std::string::npos || scopedRuntime == std::string::npos ||
        scopedLoopBegin == std::string::npos || scopedBegin == std::string::npos ||
        scopedAssignment == std::string::npos ||
        !(scopedOuter < scopedRuntime && scopedRuntime < scopedLoopBegin &&
          scopedLoopBegin < scopedBegin && scopedBegin < scopedAssignment) ||
        scopedOutput.find("@(") != std::string::npos ||
        mlir::failed(nodal::reparseBackendTarget(scopedOutput, *configuration)))
      return fail("generated loop lexical scope lost its per-occurrence target layout");

    auto forged = parse(context, scopedSource);
    if (!forged)
      return fail("could not parse the generated loop lexical scope ownership fixture");
    mlir::Operation *scope = nullptr;
    forged->walk([&](mlir::Operation *operation) {
      if (!scope && operation->getName().getStringRef() == "nodal.analog_scope")
        scope = operation;
    });
    if (!scope)
      return fail("generated loop lexical scope ownership mutation anchor missing");
    scope->setAttr("owner", mlir::StringAttr::get(&context, "GeneratedLoopLexicalStorage.forged"));
    std::string rejected = "sentinel";
    llvm::raw_string_ostream rejectedStream(rejected);
    if (mlir::succeeded(nodal::emitBackend(*forged, nodal::BackendKind::VerilogA, rejectedStream)))
      return fail("forged generated loop lexical scope ownership passed backend verification");
    rejectedStream.flush();
    if (rejected != "sentinel")
      return fail("forged generated loop lexical scope published partial output");

    std::string deeperSource = scopedSource;
    const std::string deeperScope =
        "            \"nodal.analog_scope\"() <{metadata = {semantic_path = "
        "\"GeneratedLoopLexicalStorage.loop_0.scope_0.scope_0\"}, owner = "
        "\"GeneratedLoopLexicalStorage\", scope_id = "
        "\"GeneratedLoopLexicalStorage.loop_0.scope_0.scope_0\"}> ({\n"
        "            ^bb0:\n"
        "            }) : () -> ()\n";
    auto deeperPosition = deeperSource.find(anchor);
    if (deeperPosition == std::string::npos)
      return fail("generated loop deeper lexical scope mutation anchor missing");
    deeperSource.insert(deeperPosition, deeperScope);
    auto deeper = parse(context, deeperSource);
    if (!deeper)
      return fail("generated loop deeper lexical scope mutation did not parse");
    rejected = "sentinel";
    llvm::raw_string_ostream deeperRejectedStream(rejected);
    if (mlir::succeeded(
            nodal::emitBackend(*deeper, nodal::BackendKind::VerilogA, deeperRejectedStream)))
      return fail("deeper generated loop lexical scope passed backend verification");
    deeperRejectedStream.flush();
    if (rejected != "sentinel")
      return fail("deeper generated loop lexical scope published partial output");
  }

  {
    std::string scopedExitSource = kGeneratedLoopLexicalStorageModule.str();
    const std::string readAnchor =
        "            %state_read = \"nodal.analog_variable_read\"(%state)";
    const std::string assignmentAnchor = "            \"nodal.analog_assign\"(%state, %state_read)";
    const std::string scopeHeader =
        "            \"nodal.analog_scope\"() <{metadata = {semantic_path = "
        "\"GeneratedLoopLexicalStorage.loop_0.scope_0\"}, owner = "
        "\"GeneratedLoopLexicalStorage\", scope_id = "
        "\"GeneratedLoopLexicalStorage.loop_0.scope_0\"}> ({\n"
        "            ^bb0:\n";
    const std::string scopedExits =
        "            \"nodal.analog_continue\"() <{metadata = {semantic_path = "
        "\"GeneratedLoopLexicalStorage.loop_0.scope_0.continue_0\"}, owner = "
        "\"GeneratedLoopLexicalStorage\", statement_id = "
        "\"GeneratedLoopLexicalStorage.loop_0.scope_0.continue_0\"}> : () -> ()\n"
        "            \"nodal.analog_break\"() <{metadata = {semantic_path = "
        "\"GeneratedLoopLexicalStorage.loop_0.scope_0.break_0\"}, owner = "
        "\"GeneratedLoopLexicalStorage\", statement_id = "
        "\"GeneratedLoopLexicalStorage.loop_0.scope_0.break_0\"}> : () -> ()\n";
    auto readPosition = scopedExitSource.find(readAnchor);
    if (readPosition == std::string::npos)
      return fail("generated loop lexical scope-exit read anchor missing");
    scopedExitSource.insert(readPosition, scopeHeader);
    auto assignmentPosition = scopedExitSource.find(assignmentAnchor, readPosition);
    if (assignmentPosition == std::string::npos)
      return fail("generated loop lexical scope-exit assignment anchor missing");
    auto assignmentEnd = scopedExitSource.find('\n', assignmentPosition);
    if (assignmentEnd == std::string::npos)
      return fail("generated loop lexical scope-exit assignment terminator missing");
    scopedExitSource.insert(assignmentEnd + 1, scopedExits + "            }) : () -> ()\n");

    auto scopedExit = parse(context, scopedExitSource);
    if (!scopedExit)
      return fail("generated loop lexical scope-exit fixture did not parse");
    std::string scopedExitOutput;
    llvm::raw_string_ostream scopedExitStream(scopedExitOutput);
    if (mlir::failed(
            nodal::emitBackend(*scopedExit, nodal::BackendKind::VerilogA, scopedExitStream)))
      return fail("generated loop lexical scope-exit emission failed");
    scopedExitStream.flush();
    auto runtimeLoop =
        scopedExitOutput.find("for (event_GeneratedLoopLexicalStorage_loop_0_index = 0;");
    auto loopBegin = scopedExitOutput.find("begin", runtimeLoop);
    auto scopeBegin = scopedExitOutput.find("begin", loopBegin + 5);
    auto assignment = scopedExitOutput.find(" + 5.0);", scopeBegin);
    auto loopContinue = scopedExitOutput.find("_continue = 1;", assignment);
    auto loopBreak = scopedExitOutput.find("_break = 1;", loopContinue);
    if (runtimeLoop == std::string::npos || loopBegin == std::string::npos ||
        scopeBegin == std::string::npos || assignment == std::string::npos ||
        loopContinue == std::string::npos || loopBreak == std::string::npos ||
        !(runtimeLoop < loopBegin && loopBegin < scopeBegin && scopeBegin < assignment &&
          assignment < loopContinue && loopContinue < loopBreak) ||
        scopedExitOutput.find("@(") != std::string::npos ||
        mlir::failed(nodal::reparseBackendTarget(scopedExitOutput, *configuration)))
      return fail("generated loop lexical scope exits lost their direct target topology");

    auto forged = parse(context, scopedExitSource);
    if (!forged)
      return fail("generated loop lexical scope-exit ownership fixture did not parse");
    mlir::Operation *breakOperation = nullptr;
    forged->walk([&](mlir::Operation *operation) {
      if (!breakOperation && operation->getName().getStringRef() == "nodal.analog_break")
        breakOperation = operation;
    });
    if (!breakOperation)
      return fail("generated loop lexical scope-exit ownership anchor missing");
    breakOperation->setAttr("owner",
                            mlir::StringAttr::get(&context, "GeneratedLoopLexicalStorage.forged"));
    std::string rejected = "sentinel";
    llvm::raw_string_ostream rejectedStream(rejected);
    if (mlir::succeeded(nodal::emitBackend(*forged, nodal::BackendKind::VerilogA, rejectedStream)))
      return fail("forged generated loop lexical scope exit passed backend verification");
    rejectedStream.flush();
    if (rejected != "sentinel")
      return fail("forged generated loop lexical scope exit published partial output");

    std::string duplicateSource = scopedExitSource;
    auto duplicatePosition = duplicateSource.find("            }) : () -> ()", assignmentEnd + 1);
    if (duplicatePosition == std::string::npos)
      return fail("generated loop lexical scope duplicate-exit anchor missing");
    std::string duplicateBreak =
        scopedExits.substr(scopedExits.find("            \"nodal.analog_break\"()"));
    for (auto position = duplicateBreak.find("break_0"); position != std::string::npos;
         position = duplicateBreak.find("break_0", position + 7))
      duplicateBreak.replace(position, 7, "break_1");
    duplicateSource.insert(duplicatePosition, duplicateBreak);
    auto duplicate = parse(context, duplicateSource);
    if (!duplicate)
      return fail("generated loop lexical scope duplicate-exit mutation did not parse");
    rejected = "sentinel";
    llvm::raw_string_ostream duplicateRejectedStream(rejected);
    if (mlir::succeeded(
            nodal::emitBackend(*duplicate, nodal::BackendKind::VerilogA, duplicateRejectedStream)))
      return fail("duplicate generated loop lexical scope exit passed backend verification");
    duplicateRejectedStream.flush();
    if (rejected != "sentinel")
      return fail("duplicate generated loop lexical scope exit published partial output");
  }

  auto generatedLoopConditionalStorage =
      parse(context, kGeneratedLoopConditionalLexicalStorageModule);
  if (!generatedLoopConditionalStorage)
    return fail("could not parse the generated loop-conditional lexical-storage fixture");
  std::string generatedLoopConditionalOutput;
  llvm::raw_string_ostream generatedLoopConditionalStream(generatedLoopConditionalOutput);
  if (mlir::failed(nodal::emitBackend(*generatedLoopConditionalStorage,
                                      nodal::BackendKind::VerilogA,
                                      generatedLoopConditionalStream)))
    return fail("generated loop-conditional lexical-storage emission failed");
  generatedLoopConditionalStream.flush();
  auto loopConditionalOuter = generatedLoopConditionalOutput.find("for (generate_0_index");
  auto loopConditionalRuntime = generatedLoopConditionalOutput.find(
      "for (event_GeneratedLoopConditionalLexicalStorage_loop_0_index = 0;");
  auto loopConditionalIf = generatedLoopConditionalOutput.find(
      "if (event_GeneratedLoopConditionalLexicalStorage_select) begin");
  auto loopConditionalWhen = generatedLoopConditionalOutput.find(
      "event_GeneratedLoopConditionalLexicalStorage_state = "
      "(event_GeneratedLoopConditionalLexicalStorage_loop_0_if_0_assignment_read_0 + 6.0);");
  auto loopConditionalOtherwise = generatedLoopConditionalOutput.find(
      "event_GeneratedLoopConditionalLexicalStorage_state = "
      "(event_GeneratedLoopConditionalLexicalStorage_loop_0_if_0_assignment_else_read_0 + "
      "7.0);");
  if (loopConditionalOuter == std::string::npos || loopConditionalRuntime == std::string::npos ||
      loopConditionalIf == std::string::npos || loopConditionalWhen == std::string::npos ||
      loopConditionalOtherwise == std::string::npos ||
      !(loopConditionalOuter < loopConditionalRuntime &&
        loopConditionalRuntime < loopConditionalIf && loopConditionalIf < loopConditionalWhen &&
        loopConditionalWhen < loopConditionalOtherwise) ||
      generatedLoopConditionalOutput.find("@(") != std::string::npos ||
      mlir::failed(nodal::reparseBackendTarget(generatedLoopConditionalOutput, *configuration)))
    return fail("generated loop-conditional lexical storage lost its direct target topology");

  {
    std::string exitSource = kGeneratedLoopConditionalLexicalStorageModule.str();
    const std::string assignmentAnchor =
        "                \"nodal.analog_assign\"(%state, %state_read)";
    auto assignmentPosition = exitSource.find(assignmentAnchor);
    if (assignmentPosition == std::string::npos)
      return fail("generated loop conditional-arm exit assignment anchor missing");
    auto assignmentEnd = exitSource.find('\n', assignmentPosition);
    if (assignmentEnd == std::string::npos)
      return fail("generated loop conditional-arm exit assignment terminator missing");
    const std::string loopContinue =
        "                \"nodal.analog_continue\"() <{metadata = {semantic_path = "
        "\"GeneratedLoopConditionalLexicalStorage.loop_0.if_0.continue_0\"}, owner = "
        "\"GeneratedLoopConditionalLexicalStorage\", statement_id = "
        "\"GeneratedLoopConditionalLexicalStorage.loop_0.if_0.continue_0\"}> : () -> ()\n";
    exitSource.insert(assignmentEnd + 1, loopContinue);

    auto exitModule = parse(context, exitSource);
    if (!exitModule)
      return fail("generated loop conditional-arm exit fixture did not parse");
    std::string exitOutput;
    llvm::raw_string_ostream exitStream(exitOutput);
    if (mlir::failed(nodal::emitBackend(*exitModule, nodal::BackendKind::VerilogA, exitStream)))
      return fail("generated loop conditional-arm exit emission failed");
    exitStream.flush();
    auto exitRuntime =
        exitOutput.find("for (event_GeneratedLoopConditionalLexicalStorage_loop_0_index = 0;");
    auto exitConditional = exitOutput.find(
        "if (event_GeneratedLoopConditionalLexicalStorage_select) begin", exitRuntime);
    auto exitAssignment = exitOutput.find(" + 6.0);", exitConditional);
    auto exitContinue = exitOutput.find("_continue = 1;", exitAssignment);
    auto exitOtherwise = exitOutput.find("else begin", exitContinue);
    if (exitRuntime == std::string::npos || exitConditional == std::string::npos ||
        exitAssignment == std::string::npos || exitContinue == std::string::npos ||
        exitOtherwise == std::string::npos ||
        !(exitRuntime < exitConditional && exitConditional < exitAssignment &&
          exitAssignment < exitContinue && exitContinue < exitOtherwise) ||
        mlir::failed(nodal::reparseBackendTarget(exitOutput, *configuration)))
      return fail("generated loop conditional-arm exit lost its direct target topology");

    std::string breakSource = kGeneratedLoopConditionalLexicalStorageModule.str();
    auto breakAssignmentPosition = breakSource.find(assignmentAnchor);
    auto breakAssignmentEnd = breakSource.find('\n', breakAssignmentPosition);
    if (breakAssignmentPosition == std::string::npos || breakAssignmentEnd == std::string::npos)
      return fail("generated loop conditional-arm break assignment anchor missing");
    const std::string loopBreak =
        "                \"nodal.analog_break\"() <{metadata = {semantic_path = "
        "\"GeneratedLoopConditionalLexicalStorage.loop_0.if_0.break_0\"}, owner = "
        "\"GeneratedLoopConditionalLexicalStorage\", statement_id = "
        "\"GeneratedLoopConditionalLexicalStorage.loop_0.if_0.break_0\"}> : () -> ()\n";
    breakSource.insert(breakAssignmentEnd + 1, loopBreak);
    auto breakModule = parse(context, breakSource);
    if (!breakModule)
      return fail("generated loop conditional-arm break fixture did not parse");
    std::string breakOutput;
    llvm::raw_string_ostream breakStream(breakOutput);
    if (mlir::failed(nodal::emitBackend(*breakModule, nodal::BackendKind::VerilogA, breakStream)))
      return fail("generated loop conditional-arm break emission failed");
    breakStream.flush();
    auto breakRuntime =
        breakOutput.find("for (event_GeneratedLoopConditionalLexicalStorage_loop_0_index = 0;");
    auto breakConditional = breakOutput.find(
        "if (event_GeneratedLoopConditionalLexicalStorage_select) begin", breakRuntime);
    auto breakAssignment = breakOutput.find(" + 6.0);", breakConditional);
    auto breakExit = breakOutput.find("_break = 1;", breakAssignment);
    auto breakOtherwise = breakOutput.find("else begin", breakExit);
    if (breakRuntime == std::string::npos || breakConditional == std::string::npos ||
        breakAssignment == std::string::npos || breakExit == std::string::npos ||
        breakOtherwise == std::string::npos ||
        !(breakRuntime < breakConditional && breakConditional < breakAssignment &&
          breakAssignment < breakExit && breakExit < breakOtherwise) ||
        breakOutput.find("@(") != std::string::npos ||
        mlir::failed(nodal::reparseBackendTarget(breakOutput, *configuration)))
      return fail("generated loop conditional-arm break lost its direct target topology");

    auto forgedBreak = parse(context, breakSource);
    if (!forgedBreak)
      return fail("generated loop conditional-arm break ownership fixture did not parse");
    mlir::Operation *breakOperation = nullptr;
    forgedBreak->walk([&](mlir::Operation *operation) {
      if (!breakOperation && operation->getName().getStringRef() == "nodal.analog_break")
        breakOperation = operation;
    });
    if (!breakOperation)
      return fail("generated loop conditional-arm break ownership anchor missing");
    breakOperation->setAttr(
        "owner", mlir::StringAttr::get(&context, "GeneratedLoopConditionalLexicalStorage.forged"));
    std::string breakRejected = "sentinel";
    llvm::raw_string_ostream forgedBreakStream(breakRejected);
    if (mlir::succeeded(
            nodal::emitBackend(*forgedBreak, nodal::BackendKind::VerilogA, forgedBreakStream)))
      return fail("forged generated loop conditional-arm break passed backend verification");
    forgedBreakStream.flush();
    if (breakRejected != "sentinel")
      return fail("forged generated loop conditional-arm break published partial output");

    std::string duplicateBreakSource = breakSource;
    std::string secondBreak = loopBreak;
    for (auto duplicateId = secondBreak.find("break_0"); duplicateId != std::string::npos;
         duplicateId = secondBreak.find("break_0", duplicateId + 7))
      secondBreak.replace(duplicateId, 7, "break_1");
    duplicateBreakSource.insert(breakAssignmentEnd + 1, secondBreak);
    auto duplicateBreak = parse(context, duplicateBreakSource);
    if (!duplicateBreak)
      return fail("generated loop conditional-arm duplicate-break mutation did not parse");
    breakRejected = "sentinel";
    llvm::raw_string_ostream duplicateBreakStream(breakRejected);
    if (mlir::succeeded(nodal::emitBackend(*duplicateBreak, nodal::BackendKind::VerilogA,
                                           duplicateBreakStream)))
      return fail("duplicate generated loop conditional-arm break passed backend verification");
    duplicateBreakStream.flush();
    if (breakRejected != "sentinel")
      return fail("duplicate generated loop conditional-arm break published partial output");

    auto forged = parse(context, exitSource);
    if (!forged)
      return fail("generated loop conditional-arm exit ownership fixture did not parse");
    mlir::Operation *continueOperation = nullptr;
    forged->walk([&](mlir::Operation *operation) {
      if (!continueOperation && operation->getName().getStringRef() == "nodal.analog_continue")
        continueOperation = operation;
    });
    if (!continueOperation)
      return fail("generated loop conditional-arm exit ownership anchor missing");
    continueOperation->setAttr(
        "owner", mlir::StringAttr::get(&context, "GeneratedLoopConditionalLexicalStorage.forged"));
    std::string rejected = "sentinel";
    llvm::raw_string_ostream rejectedStream(rejected);
    if (mlir::succeeded(nodal::emitBackend(*forged, nodal::BackendKind::VerilogA, rejectedStream)))
      return fail("forged generated loop conditional-arm exit passed backend verification");
    rejectedStream.flush();
    if (rejected != "sentinel")
      return fail("forged generated loop conditional-arm exit published partial output");

    std::string deeperSource = kGeneratedLoopConditionalLexicalStorageModule.str();
    const std::string readAnchor =
        "                %state_read = \"nodal.analog_variable_read\"(%state)";
    auto deeperPosition = deeperSource.find(readAnchor);
    if (deeperPosition == std::string::npos)
      return fail("generated loop conditional-arm deeper-exit mutation anchor missing");
    const std::string deeperExit =
        "                \"nodal.analog_scope\"() <{metadata = {semantic_path = "
        "\"GeneratedLoopConditionalLexicalStorage.loop_0.if_0.scope_0\"}, owner = "
        "\"GeneratedLoopConditionalLexicalStorage\", scope_id = "
        "\"GeneratedLoopConditionalLexicalStorage.loop_0.if_0.scope_0\"}> ({\n"
        "                ^bb0:\n" +
        loopContinue + "                }) : () -> ()\n";
    deeperSource.insert(deeperPosition, deeperExit);
    auto deeper = parse(context, deeperSource);
    if (!deeper)
      return fail("generated loop conditional-arm deeper-exit mutation did not parse");
    rejected = "sentinel";
    llvm::raw_string_ostream deeperRejectedStream(rejected);
    if (mlir::succeeded(
            nodal::emitBackend(*deeper, nodal::BackendKind::VerilogA, deeperRejectedStream)))
      return fail("scope-contained generated loop conditional-arm exit passed verification");
    deeperRejectedStream.flush();
    if (rejected != "sentinel")
      return fail("scope-contained generated loop conditional-arm exit published partial output");
  }

  {
    auto invalid = parse(context, kGeneratedLoopConditionalLexicalStorageModule);
    if (!invalid)
      return fail("could not parse the generated loop-conditional owner mutation fixture");
    mlir::Operation *conditional = nullptr;
    invalid->walk([&](mlir::Operation *operation) {
      if (!conditional && operation->getName().getStringRef() == "nodal.analog_if")
        conditional = operation;
    });
    if (!conditional)
      return fail("generated loop-conditional owner mutation anchor missing");
    conditional->setAttr(
        "owner", mlir::StringAttr::get(&context, "GeneratedLoopConditionalLexicalStorage.forged"));
    std::string rejected = "sentinel";
    llvm::raw_string_ostream rejectedStream(rejected);
    if (mlir::succeeded(nodal::emitBackend(*invalid, nodal::BackendKind::VerilogA, rejectedStream)))
      return fail("forged generated loop-conditional owner passed backend verification");
    rejectedStream.flush();
    if (rejected != "sentinel")
      return fail("forged generated loop-conditional owner published partial output");
  }

  {
    std::string scopedSource = kGeneratedLoopConditionalLexicalStorageModule.str();
    const std::string anchor =
        "                %state_read = \"nodal.analog_variable_read\"(%state)";
    const std::string scopeHeader =
        "                \"nodal.analog_scope\"() <{metadata = {semantic_path = "
        "\"GeneratedLoopConditionalLexicalStorage.loop_0.if_0.scope_0\"}, owner = "
        "\"GeneratedLoopConditionalLexicalStorage\", scope_id = "
        "\"GeneratedLoopConditionalLexicalStorage.loop_0.if_0.scope_0\"}> ({\n"
        "                ^bb0:\n";
    const std::string assignmentAnchor =
        "                \"nodal.analog_assign\"(%state, %state_read)";
    auto position = scopedSource.find(anchor);
    if (position == std::string::npos)
      return fail("generated loop-conditional scope mutation anchor missing");
    scopedSource.insert(position, scopeHeader);
    auto assignmentPosition = scopedSource.find(assignmentAnchor, position + scopeHeader.size());
    if (assignmentPosition == std::string::npos)
      return fail("generated loop-conditional scope assignment anchor missing");
    auto assignmentEnd = scopedSource.find('\n', assignmentPosition);
    if (assignmentEnd == std::string::npos)
      return fail("generated loop-conditional scope assignment terminator missing");
    scopedSource.insert(assignmentEnd + 1, "                }) : () -> ()\n");

    auto scoped = parse(context, scopedSource);
    if (!scoped)
      return fail("generated loop-conditional scope fixture did not parse");
    std::string scopedOutput;
    llvm::raw_string_ostream scopedStream(scopedOutput);
    if (mlir::failed(nodal::emitBackend(*scoped, nodal::BackendKind::VerilogA, scopedStream)))
      return fail("generated loop-conditional scope emission failed");
    scopedStream.flush();
    auto scopedRuntime =
        scopedOutput.find("for (event_GeneratedLoopConditionalLexicalStorage_loop_0_index = 0;");
    auto scopedConditional = scopedOutput.find(
        "if (event_GeneratedLoopConditionalLexicalStorage_select) begin", scopedRuntime);
    auto scopedConditionalBegin = scopedOutput.find("begin", scopedConditional);
    auto scopedArmBegin = scopedOutput.find("begin", scopedConditionalBegin + 5);
    auto scopedAssignment = scopedOutput.find(
        "event_GeneratedLoopConditionalLexicalStorage_state = "
        "(event_GeneratedLoopConditionalLexicalStorage_loop_0_if_0_assignment_read_0 + 6.0);",
        scopedArmBegin);
    auto scopedOtherwise = scopedOutput.find("else begin", scopedAssignment);
    if (scopedRuntime == std::string::npos || scopedConditional == std::string::npos ||
        scopedConditionalBegin == std::string::npos || scopedArmBegin == std::string::npos ||
        scopedAssignment == std::string::npos || scopedOtherwise == std::string::npos ||
        !(scopedRuntime < scopedConditional && scopedConditional < scopedConditionalBegin &&
          scopedConditionalBegin < scopedArmBegin && scopedArmBegin < scopedAssignment &&
          scopedAssignment < scopedOtherwise) ||
        scopedOutput.find("@(") != std::string::npos ||
        mlir::failed(nodal::reparseBackendTarget(scopedOutput, *configuration)))
      return fail("generated loop-conditional scope lost its direct target topology");

    std::string scopedExitSource = scopedSource;
    auto scopedExitAssignment = scopedExitSource.find(assignmentAnchor);
    auto scopedExitAssignmentEnd = scopedExitSource.find('\n', scopedExitAssignment);
    if (scopedExitAssignment == std::string::npos || scopedExitAssignmentEnd == std::string::npos)
      return fail("generated loop-conditional scope-exit assignment anchor missing");
    const std::string scopedContinue =
        "                \"nodal.analog_continue\"() <{metadata = {semantic_path = "
        "\"GeneratedLoopConditionalLexicalStorage.loop_0.if_0.scope_0.continue_0\"}, owner = "
        "\"GeneratedLoopConditionalLexicalStorage\", statement_id = "
        "\"GeneratedLoopConditionalLexicalStorage.loop_0.if_0.scope_0.continue_0\"}> : () -> "
        "()\n";
    scopedExitSource.insert(scopedExitAssignmentEnd + 1, scopedContinue);
    auto scopedExit = parse(context, scopedExitSource);
    if (!scopedExit)
      return fail("generated loop-conditional scope-exit fixture did not parse");
    std::string scopedExitOutput;
    llvm::raw_string_ostream scopedExitStream(scopedExitOutput);
    if (mlir::failed(
            nodal::emitBackend(*scopedExit, nodal::BackendKind::VerilogA, scopedExitStream)))
      return fail("generated loop-conditional scope-exit emission failed");
    scopedExitStream.flush();
    auto scopedExitRuntime = scopedExitOutput.find(
        "for (event_GeneratedLoopConditionalLexicalStorage_loop_0_index = 0;");
    auto scopedExitConditional = scopedExitOutput.find(
        "if (event_GeneratedLoopConditionalLexicalStorage_select) begin", scopedExitRuntime);
    auto scopedExitConditionalBegin = scopedExitOutput.find("begin", scopedExitConditional);
    auto scopedExitScopeBegin = scopedExitOutput.find("begin", scopedExitConditionalBegin + 5);
    auto scopedExitAssignmentOutput = scopedExitOutput.find(" + 6.0);", scopedExitScopeBegin);
    auto scopedExitContinue = scopedExitOutput.find("_continue = 1;", scopedExitAssignmentOutput);
    auto scopedExitOtherwise = scopedExitOutput.find("else begin", scopedExitContinue);
    if (scopedExitRuntime == std::string::npos || scopedExitConditional == std::string::npos ||
        scopedExitConditionalBegin == std::string::npos ||
        scopedExitScopeBegin == std::string::npos ||
        scopedExitAssignmentOutput == std::string::npos ||
        scopedExitContinue == std::string::npos || scopedExitOtherwise == std::string::npos ||
        !(scopedExitRuntime < scopedExitConditional &&
          scopedExitConditional < scopedExitConditionalBegin &&
          scopedExitConditionalBegin < scopedExitScopeBegin &&
          scopedExitScopeBegin < scopedExitAssignmentOutput &&
          scopedExitAssignmentOutput < scopedExitContinue &&
          scopedExitContinue < scopedExitOtherwise) ||
        scopedExitOutput.find("@(") != std::string::npos ||
        mlir::failed(nodal::reparseBackendTarget(scopedExitOutput, *configuration)))
      return fail("generated loop-conditional scope exit lost its direct target topology");

    std::string scopedBreakSource = scopedExitSource;
    auto scopedBreakName = scopedBreakSource.find("nodal.analog_continue");
    if (scopedBreakName == std::string::npos)
      return fail("generated loop-conditional scope-break operation anchor missing");
    scopedBreakSource.replace(scopedBreakName, std::string("nodal.analog_continue").size(),
                              "nodal.analog_break");
    for (auto breakId = scopedBreakSource.find("continue_0"); breakId != std::string::npos;
         breakId = scopedBreakSource.find("continue_0", breakId + 7))
      scopedBreakSource.replace(breakId, std::string("continue_0").size(), "break_0");
    auto scopedBreakModule = parse(context, scopedBreakSource);
    if (!scopedBreakModule)
      return fail("generated loop-conditional scope-break fixture did not parse");
    std::string scopedBreakOutput;
    llvm::raw_string_ostream scopedBreakStream(scopedBreakOutput);
    if (mlir::failed(nodal::emitBackend(*scopedBreakModule, nodal::BackendKind::VerilogA,
                                        scopedBreakStream)))
      return fail("generated loop-conditional scope-break emission failed");
    scopedBreakStream.flush();
    auto scopedBreakAssignment = scopedBreakOutput.find(" + 6.0);");
    auto scopedBreakExit = scopedBreakOutput.find("_break = 1;", scopedBreakAssignment);
    auto scopedBreakOtherwise = scopedBreakOutput.find("else begin", scopedBreakExit);
    if (scopedBreakAssignment == std::string::npos || scopedBreakExit == std::string::npos ||
        scopedBreakOtherwise == std::string::npos ||
        !(scopedBreakAssignment < scopedBreakExit && scopedBreakExit < scopedBreakOtherwise) ||
        scopedBreakOutput.find("@(") != std::string::npos ||
        mlir::failed(nodal::reparseBackendTarget(scopedBreakOutput, *configuration)))
      return fail("generated loop-conditional scope break lost its direct target topology");

    mlir::Operation *scopedBreakOperation = nullptr;
    scopedBreakModule->walk([&](mlir::Operation *operation) {
      if (!scopedBreakOperation && operation->getName().getStringRef() == "nodal.analog_break")
        scopedBreakOperation = operation;
    });
    if (!scopedBreakOperation)
      return fail("generated loop-conditional scope-break ownership anchor missing");
    scopedBreakOperation->setAttr(
        "owner", mlir::StringAttr::get(&context, "GeneratedLoopConditionalLexicalStorage.forged"));
    std::string breakRejected = "sentinel";
    llvm::raw_string_ostream forgedBreakStream(breakRejected);
    if (mlir::succeeded(nodal::emitBackend(*scopedBreakModule, nodal::BackendKind::VerilogA,
                                           forgedBreakStream)))
      return fail("forged generated loop-conditional scope break passed backend verification");
    forgedBreakStream.flush();
    if (breakRejected != "sentinel")
      return fail("forged generated loop-conditional scope break published partial output");
    scopedBreakOperation->setAttr(
        "owner", mlir::StringAttr::get(&context, "GeneratedLoopConditionalLexicalStorage"));

    mlir::Operation *duplicateScopedBreak = scopedBreakOperation->clone();
    duplicateScopedBreak->setAttr(
        "statement_id",
        mlir::StringAttr::get(
            &context, "GeneratedLoopConditionalLexicalStorage.loop_0.if_0.scope_0.break_1"));
    scopedBreakOperation->getBlock()->push_back(duplicateScopedBreak);
    breakRejected = "sentinel";
    llvm::raw_string_ostream duplicateBreakStream(breakRejected);
    if (mlir::succeeded(nodal::emitBackend(*scopedBreakModule, nodal::BackendKind::VerilogA,
                                           duplicateBreakStream)))
      return fail("duplicate generated loop-conditional scope break passed backend verification");
    duplicateBreakStream.flush();
    if (breakRejected != "sentinel")
      return fail("duplicate generated loop-conditional scope break published partial output");
    duplicateScopedBreak->erase();

    mlir::Operation *scopedBreakScope = scopedBreakOperation->getParentOp();
    mlir::Operation *deeperBreakScope = scopedBreakScope->clone();
    scopedBreakScope->getRegion(0).front().push_back(deeperBreakScope);
    breakRejected = "sentinel";
    llvm::raw_string_ostream deeperBreakStream(breakRejected);
    if (mlir::succeeded(nodal::emitBackend(*scopedBreakModule, nodal::BackendKind::VerilogA,
                                           deeperBreakStream)))
      return fail("deeper generated loop-conditional scope break passed backend verification");
    deeperBreakStream.flush();
    if (breakRejected != "sentinel")
      return fail("deeper generated loop-conditional scope break published partial output");
    deeperBreakScope->erase();

    auto forgedExit = parse(context, scopedExitSource);
    if (!forgedExit)
      return fail("generated loop-conditional scope-exit ownership fixture did not parse");
    mlir::Operation *continueOperation = nullptr;
    forgedExit->walk([&](mlir::Operation *operation) {
      if (!continueOperation && operation->getName().getStringRef() == "nodal.analog_continue")
        continueOperation = operation;
    });
    if (!continueOperation)
      return fail("generated loop-conditional scope-exit ownership anchor missing");
    continueOperation->setAttr(
        "owner", mlir::StringAttr::get(&context, "GeneratedLoopConditionalLexicalStorage.forged"));
    std::string exitRejected = "sentinel";
    llvm::raw_string_ostream forgedExitStream(exitRejected);
    if (mlir::succeeded(
            nodal::emitBackend(*forgedExit, nodal::BackendKind::VerilogA, forgedExitStream)))
      return fail("forged generated loop-conditional scope exit passed backend verification");
    forgedExitStream.flush();
    if (exitRejected != "sentinel")
      return fail("forged generated loop-conditional scope exit published partial output");

    std::string duplicateExitSource = scopedExitSource;
    auto duplicateExitPosition =
        duplicateExitSource.find("                }) : () -> ()", scopedExitAssignmentEnd + 1);
    if (duplicateExitPosition == std::string::npos)
      return fail("generated loop-conditional scope duplicate-exit anchor missing");
    std::string duplicateContinue = scopedContinue;
    for (auto duplicateId = duplicateContinue.find("continue_0"); duplicateId != std::string::npos;
         duplicateId = duplicateContinue.find("continue_0", duplicateId + 10))
      duplicateContinue.replace(duplicateId, 10, "continue_1");
    duplicateExitSource.insert(duplicateExitPosition, duplicateContinue);
    auto duplicateExit = parse(context, duplicateExitSource);
    if (!duplicateExit)
      return fail("generated loop-conditional scope duplicate-exit mutation did not parse");
    exitRejected = "sentinel";
    llvm::raw_string_ostream duplicateExitStream(exitRejected);
    if (mlir::succeeded(
            nodal::emitBackend(*duplicateExit, nodal::BackendKind::VerilogA, duplicateExitStream)))
      return fail("duplicate generated loop-conditional scope exit passed backend verification");
    duplicateExitStream.flush();
    if (exitRejected != "sentinel")
      return fail("duplicate generated loop-conditional scope exit published partial output");

    std::string deeperExitSource = scopedSource;
    auto deeperExitAssignment = deeperExitSource.find(assignmentAnchor);
    auto deeperExitAssignmentEnd = deeperExitSource.find('\n', deeperExitAssignment);
    if (deeperExitAssignment == std::string::npos || deeperExitAssignmentEnd == std::string::npos)
      return fail("generated loop-conditional deeper scope-exit assignment anchor missing");
    const std::string deeperScopedContinue =
        "                \"nodal.analog_scope\"() <{metadata = {semantic_path = "
        "\"GeneratedLoopConditionalLexicalStorage.loop_0.if_0.scope_0.scope_0\"}, owner = "
        "\"GeneratedLoopConditionalLexicalStorage\", scope_id = "
        "\"GeneratedLoopConditionalLexicalStorage.loop_0.if_0.scope_0.scope_0\"}> ({\n"
        "                ^bb0:\n" +
        scopedContinue + "                }) : () -> ()\n";
    deeperExitSource.insert(deeperExitAssignmentEnd + 1, deeperScopedContinue);
    auto deeperExit = parse(context, deeperExitSource);
    if (!deeperExit)
      return fail("generated loop-conditional deeper scope-exit mutation did not parse");
    exitRejected = "sentinel";
    llvm::raw_string_ostream deeperExitStream(exitRejected);
    if (mlir::succeeded(
            nodal::emitBackend(*deeperExit, nodal::BackendKind::VerilogA, deeperExitStream)))
      return fail("deeper generated loop-conditional scope exit passed backend verification");
    deeperExitStream.flush();
    if (exitRejected != "sentinel")
      return fail("deeper generated loop-conditional scope exit published partial output");

    auto forged = parse(context, scopedSource);
    if (!forged)
      return fail("could not parse the generated loop-conditional scope ownership fixture");
    mlir::Operation *scope = nullptr;
    forged->walk([&](mlir::Operation *operation) {
      if (!scope && operation->getName().getStringRef() == "nodal.analog_scope")
        scope = operation;
    });
    if (!scope)
      return fail("generated loop-conditional scope ownership mutation anchor missing");
    scope->setAttr(
        "owner", mlir::StringAttr::get(&context, "GeneratedLoopConditionalLexicalStorage.forged"));
    std::string rejected = "sentinel";
    llvm::raw_string_ostream rejectedStream(rejected);
    if (mlir::succeeded(nodal::emitBackend(*forged, nodal::BackendKind::VerilogA, rejectedStream)))
      return fail("forged generated loop-conditional scope ownership passed backend verification");
    rejectedStream.flush();
    if (rejected != "sentinel")
      return fail("forged generated loop-conditional scope published partial output");

    std::string emptyScopeSource = kGeneratedLoopConditionalLexicalStorageModule.str();
    const std::string emptyScope =
        "                \"nodal.analog_scope\"() <{metadata = {semantic_path = "
        "\"GeneratedLoopConditionalLexicalStorage.loop_0.if_0.empty_scope\"}, owner = "
        "\"GeneratedLoopConditionalLexicalStorage\", scope_id = "
        "\"GeneratedLoopConditionalLexicalStorage.loop_0.if_0.empty_scope\"}> ({\n"
        "                ^bb0:\n"
        "                }) : () -> ()\n";
    auto emptyPosition = emptyScopeSource.find(anchor);
    if (emptyPosition == std::string::npos)
      return fail("generated loop-conditional empty scope mutation anchor missing");
    emptyScopeSource.insert(emptyPosition, emptyScope);
    auto empty = parse(context, emptyScopeSource);
    if (!empty)
      return fail("generated loop-conditional empty scope mutation did not parse");
    rejected = "sentinel";
    llvm::raw_string_ostream emptyRejectedStream(rejected);
    if (mlir::succeeded(
            nodal::emitBackend(*empty, nodal::BackendKind::VerilogA, emptyRejectedStream)))
      return fail("empty generated loop-conditional scope passed backend verification");
    emptyRejectedStream.flush();
    if (rejected != "sentinel")
      return fail("empty generated loop-conditional scope published partial output");

    std::string deeperSource = scopedSource;
    const std::string deeperScope =
        "                \"nodal.analog_scope\"() <{metadata = {semantic_path = "
        "\"GeneratedLoopConditionalLexicalStorage.loop_0.if_0.scope_0.scope_0\"}, owner = "
        "\"GeneratedLoopConditionalLexicalStorage\", scope_id = "
        "\"GeneratedLoopConditionalLexicalStorage.loop_0.if_0.scope_0.scope_0\"}> ({\n"
        "                ^bb0:\n"
        "                }) : () -> ()\n";
    auto deeperPosition = deeperSource.find(anchor);
    if (deeperPosition == std::string::npos)
      return fail("generated loop-conditional deeper scope mutation anchor missing");
    deeperSource.insert(deeperPosition, deeperScope);
    auto deeper = parse(context, deeperSource);
    if (!deeper)
      return fail("generated loop-conditional deeper scope mutation did not parse");
    rejected = "sentinel";
    llvm::raw_string_ostream deeperRejectedStream(rejected);
    if (mlir::succeeded(
            nodal::emitBackend(*deeper, nodal::BackendKind::VerilogA, deeperRejectedStream)))
      return fail("deeper generated loop-conditional scope passed backend verification");
    deeperRejectedStream.flush();
    if (rejected != "sentinel")
      return fail("deeper generated loop-conditional scope published partial output");
  }

  auto generatedLoopCaseStorage = parse(context, kGeneratedLoopCaseLexicalStorageModule);
  if (!generatedLoopCaseStorage)
    return fail("could not parse the generated loop-case lexical-storage fixture");
  std::string generatedLoopCaseOutput;
  llvm::raw_string_ostream generatedLoopCaseStream(generatedLoopCaseOutput);
  if (mlir::failed(nodal::emitBackend(*generatedLoopCaseStorage, nodal::BackendKind::VerilogA,
                                      generatedLoopCaseStream)))
    return fail("generated loop-case lexical-storage emission failed");
  generatedLoopCaseStream.flush();
  auto loopCaseOuter = generatedLoopCaseOutput.find("for (generate_0_index");
  auto loopCaseRuntime =
      generatedLoopCaseOutput.find("for (event_GeneratedLoopCaseLexicalStorage_loop_0_index = 0;");
  auto loopCaseTree = generatedLoopCaseOutput.find(
      "case (event_GeneratedLoopCaseLexicalStorage_mode)", loopCaseRuntime);
  auto loopCaseArm = generatedLoopCaseOutput.find("0: begin", loopCaseTree);
  auto loopCaseAssignment = generatedLoopCaseOutput.find(
      "event_GeneratedLoopCaseLexicalStorage_state = "
      "(event_GeneratedLoopCaseLexicalStorage_loop_0_case_0_assignment_read_0 + 8.0);",
      loopCaseArm);
  auto loopCaseDefault = generatedLoopCaseOutput.find("default: begin", loopCaseTree);
  auto loopCaseDefaultAssignment = generatedLoopCaseOutput.find(
      "event_GeneratedLoopCaseLexicalStorage_state = "
      "(event_GeneratedLoopCaseLexicalStorage_loop_0_case_0_assignment_default_read_0 + 9.0);",
      loopCaseDefault);
  if (loopCaseOuter == std::string::npos || loopCaseRuntime == std::string::npos ||
      loopCaseTree == std::string::npos || loopCaseArm == std::string::npos ||
      loopCaseAssignment == std::string::npos || loopCaseDefault == std::string::npos ||
      loopCaseDefaultAssignment == std::string::npos ||
      !(loopCaseOuter < loopCaseRuntime && loopCaseRuntime < loopCaseTree &&
        loopCaseTree < loopCaseArm && loopCaseArm < loopCaseAssignment &&
        loopCaseAssignment < loopCaseDefault && loopCaseDefault < loopCaseDefaultAssignment) ||
      generatedLoopCaseOutput.find("@(") != std::string::npos ||
      mlir::failed(nodal::reparseBackendTarget(generatedLoopCaseOutput, *configuration)))
    return fail("generated loop-case lexical storage lost its direct target topology");

  {
    std::string exitSource = kGeneratedLoopCaseLexicalStorageModule.str();
    const std::string assignmentAnchor =
        "                \"nodal.analog_assign\"(%state, %state_read_default)";
    auto assignmentPosition = exitSource.find(assignmentAnchor);
    if (assignmentPosition == std::string::npos)
      return fail("generated loop case-arm exit assignment anchor missing");
    auto assignmentEnd = exitSource.find('\n', assignmentPosition);
    if (assignmentEnd == std::string::npos)
      return fail("generated loop case-arm exit assignment terminator missing");
    const std::string loopBreak =
        "                \"nodal.analog_break\"() <{metadata = {semantic_path = "
        "\"GeneratedLoopCaseLexicalStorage.loop_0.case_0.break_0\"}, owner = "
        "\"GeneratedLoopCaseLexicalStorage\", statement_id = "
        "\"GeneratedLoopCaseLexicalStorage.loop_0.case_0.break_0\"}> : () -> ()\n";
    exitSource.insert(assignmentEnd + 1, loopBreak);

    auto exitModule = parse(context, exitSource);
    if (!exitModule)
      return fail("generated loop case-arm exit fixture did not parse");
    std::string exitOutput;
    llvm::raw_string_ostream exitStream(exitOutput);
    if (mlir::failed(nodal::emitBackend(*exitModule, nodal::BackendKind::VerilogA, exitStream)))
      return fail("generated loop case-arm exit emission failed");
    exitStream.flush();
    auto exitRuntime =
        exitOutput.find("for (event_GeneratedLoopCaseLexicalStorage_loop_0_index = 0;");
    auto exitCase =
        exitOutput.find("case (event_GeneratedLoopCaseLexicalStorage_mode)", exitRuntime);
    auto exitDefault = exitOutput.find("default: begin", exitCase);
    auto exitAssignment = exitOutput.find(" + 9.0);", exitDefault);
    auto exitBreak = exitOutput.find("_break = 1;", exitAssignment);
    if (exitRuntime == std::string::npos || exitCase == std::string::npos ||
        exitDefault == std::string::npos || exitAssignment == std::string::npos ||
        exitBreak == std::string::npos ||
        !(exitRuntime < exitCase && exitCase < exitDefault && exitDefault < exitAssignment &&
          exitAssignment < exitBreak) ||
        mlir::failed(nodal::reparseBackendTarget(exitOutput, *configuration)))
      return fail("generated loop case-arm exit lost its direct target topology");

    std::string continueSource = kGeneratedLoopCaseLexicalStorageModule.str();
    const std::string loopContinue =
        "                \"nodal.analog_continue\"() <{metadata = {semantic_path = "
        "\"GeneratedLoopCaseLexicalStorage.loop_0.case_0.continue_0\"}, owner = "
        "\"GeneratedLoopCaseLexicalStorage\", statement_id = "
        "\"GeneratedLoopCaseLexicalStorage.loop_0.case_0.continue_0\"}> : () -> ()\n";
    continueSource.insert(assignmentEnd + 1, loopContinue);
    auto continueModule = parse(context, continueSource);
    if (!continueModule)
      return fail("generated loop case-arm continue fixture did not parse");
    std::string continueOutput;
    llvm::raw_string_ostream continueStream(continueOutput);
    if (mlir::failed(
            nodal::emitBackend(*continueModule, nodal::BackendKind::VerilogA, continueStream)))
      return fail("generated loop case-arm continue emission failed");
    continueStream.flush();
    auto continueRuntime =
        continueOutput.find("for (event_GeneratedLoopCaseLexicalStorage_loop_0_index = 0;");
    auto continueCase =
        continueOutput.find("case (event_GeneratedLoopCaseLexicalStorage_mode)", continueRuntime);
    auto continueDefault = continueOutput.find("default: begin", continueCase);
    auto continueAssignment = continueOutput.find(" + 9.0);", continueDefault);
    auto continueExit = continueOutput.find("_continue = 1;", continueAssignment);
    if (continueRuntime == std::string::npos || continueCase == std::string::npos ||
        continueDefault == std::string::npos || continueAssignment == std::string::npos ||
        continueExit == std::string::npos ||
        !(continueRuntime < continueCase && continueCase < continueDefault &&
          continueDefault < continueAssignment && continueAssignment < continueExit) ||
        mlir::failed(nodal::reparseBackendTarget(continueOutput, *configuration)))
      return fail("generated loop case-arm continue lost its direct target topology");

    auto forgedContinue = parse(context, continueSource);
    if (!forgedContinue)
      return fail("generated loop case-arm continue ownership fixture did not parse");
    mlir::Operation *continueOperation = nullptr;
    forgedContinue->walk([&](mlir::Operation *operation) {
      if (!continueOperation && operation->getName().getStringRef() == "nodal.analog_continue")
        continueOperation = operation;
    });
    if (!continueOperation)
      return fail("generated loop case-arm continue ownership anchor missing");
    continueOperation->setAttr(
        "owner", mlir::StringAttr::get(&context, "GeneratedLoopCaseLexicalStorage.forged"));
    std::string continueRejected = "sentinel";
    llvm::raw_string_ostream forgedContinueStream(continueRejected);
    if (mlir::succeeded(nodal::emitBackend(*forgedContinue, nodal::BackendKind::VerilogA,
                                           forgedContinueStream)))
      return fail("forged generated loop case-arm continue passed backend verification");
    forgedContinueStream.flush();
    if (continueRejected != "sentinel")
      return fail("forged generated loop case-arm continue published partial output");

    std::string duplicateContinueSource = continueSource;
    std::string secondContinue = loopContinue;
    for (auto duplicateId = secondContinue.find("continue_0"); duplicateId != std::string::npos;
         duplicateId = secondContinue.find("continue_0", duplicateId + 10))
      secondContinue.replace(duplicateId, 10, "continue_1");
    duplicateContinueSource.insert(assignmentEnd + 1, secondContinue);
    auto duplicateContinue = parse(context, duplicateContinueSource);
    if (!duplicateContinue)
      return fail("generated loop case-arm duplicate-continue mutation did not parse");
    continueRejected = "sentinel";
    llvm::raw_string_ostream duplicateContinueStream(continueRejected);
    if (mlir::succeeded(nodal::emitBackend(*duplicateContinue, nodal::BackendKind::VerilogA,
                                           duplicateContinueStream)))
      return fail("duplicate generated loop case-arm continue passed backend verification");
    duplicateContinueStream.flush();
    if (continueRejected != "sentinel")
      return fail("duplicate generated loop case-arm continue published partial output");
  }

  {
    auto invalid = parse(context, kGeneratedLoopCaseLexicalStorageModule);
    if (!invalid)
      return fail("could not parse the generated loop-case owner mutation fixture");
    mlir::Operation *caseOperation = nullptr;
    invalid->walk([&](mlir::Operation *operation) {
      if (!caseOperation && operation->getName().getStringRef() == "nodal.analog_case")
        caseOperation = operation;
    });
    if (!caseOperation)
      return fail("generated loop-case owner mutation anchor missing");
    caseOperation->setAttr(
        "owner", mlir::StringAttr::get(&context, "GeneratedLoopCaseLexicalStorage.forged"));
    std::string rejected = "sentinel";
    llvm::raw_string_ostream rejectedStream(rejected);
    if (mlir::succeeded(nodal::emitBackend(*invalid, nodal::BackendKind::VerilogA, rejectedStream)))
      return fail("forged generated loop-case owner passed backend verification");
    rejectedStream.flush();
    if (rejected != "sentinel")
      return fail("forged generated loop-case owner published partial output");
  }

  {
    std::string scopedSource = kGeneratedLoopCaseLexicalStorageModule.str();
    const std::string anchor =
        "                %state_read = \"nodal.analog_variable_read\"(%state)";
    const std::string scopeHeader =
        "                \"nodal.analog_scope\"() <{metadata = {semantic_path = "
        "\"GeneratedLoopCaseLexicalStorage.loop_0.case_0.scope_0\"}, owner = "
        "\"GeneratedLoopCaseLexicalStorage\", scope_id = "
        "\"GeneratedLoopCaseLexicalStorage.loop_0.case_0.scope_0\"}> ({\n"
        "                ^bb0:\n";
    const std::string assignmentAnchor =
        "                \"nodal.analog_assign\"(%state, %state_read)";
    auto position = scopedSource.find(anchor);
    if (position == std::string::npos)
      return fail("generated loop-case scope mutation anchor missing");
    scopedSource.insert(position, scopeHeader);
    auto assignmentPosition = scopedSource.find(assignmentAnchor, position + scopeHeader.size());
    if (assignmentPosition == std::string::npos)
      return fail("generated loop-case scope assignment anchor missing");
    auto assignmentEnd = scopedSource.find('\n', assignmentPosition);
    if (assignmentEnd == std::string::npos)
      return fail("generated loop-case scope assignment terminator missing");
    scopedSource.insert(assignmentEnd + 1, "                }) : () -> ()\n");

    auto scoped = parse(context, scopedSource);
    if (!scoped)
      return fail("generated loop-case scope fixture did not parse");
    std::string scopedOutput;
    llvm::raw_string_ostream scopedStream(scopedOutput);
    if (mlir::failed(nodal::emitBackend(*scoped, nodal::BackendKind::VerilogA, scopedStream)))
      return fail("generated loop-case scope emission failed");
    scopedStream.flush();
    auto scopedRuntime =
        scopedOutput.find("for (event_GeneratedLoopCaseLexicalStorage_loop_0_index = 0;");
    auto scopedCase =
        scopedOutput.find("case (event_GeneratedLoopCaseLexicalStorage_mode)", scopedRuntime);
    auto scopedArm = scopedOutput.find("0: begin", scopedCase);
    auto scopedArmBegin = scopedOutput.find("begin", scopedArm);
    auto scopedScopeBegin = scopedOutput.find("begin", scopedArmBegin + 5);
    auto scopedAssignment = scopedOutput.find(
        "event_GeneratedLoopCaseLexicalStorage_state = "
        "(event_GeneratedLoopCaseLexicalStorage_loop_0_case_0_assignment_read_0 + 8.0);",
        scopedScopeBegin);
    auto scopedDefault = scopedOutput.find("default: begin", scopedAssignment);
    if (scopedRuntime == std::string::npos || scopedCase == std::string::npos ||
        scopedArm == std::string::npos || scopedArmBegin == std::string::npos ||
        scopedScopeBegin == std::string::npos || scopedAssignment == std::string::npos ||
        scopedDefault == std::string::npos ||
        !(scopedRuntime < scopedCase && scopedCase < scopedArm && scopedArm < scopedArmBegin &&
          scopedArmBegin < scopedScopeBegin && scopedScopeBegin < scopedAssignment &&
          scopedAssignment < scopedDefault) ||
        scopedOutput.find("@(") != std::string::npos ||
        mlir::failed(nodal::reparseBackendTarget(scopedOutput, *configuration)))
      return fail("generated loop-case scope lost its direct target topology");

    std::string scopedExitSource = scopedSource;
    auto scopedExitAssignment = scopedExitSource.find(assignmentAnchor);
    auto scopedExitAssignmentEnd = scopedExitSource.find('\n', scopedExitAssignment);
    if (scopedExitAssignment == std::string::npos || scopedExitAssignmentEnd == std::string::npos)
      return fail("generated loop-case scope-exit assignment anchor missing");
    const std::string scopedBreak =
        "                \"nodal.analog_break\"() <{metadata = {semantic_path = "
        "\"GeneratedLoopCaseLexicalStorage.loop_0.case_0.scope_0.break_0\"}, owner = "
        "\"GeneratedLoopCaseLexicalStorage\", statement_id = "
        "\"GeneratedLoopCaseLexicalStorage.loop_0.case_0.scope_0.break_0\"}> : () -> ()\n";
    scopedExitSource.insert(scopedExitAssignmentEnd + 1, scopedBreak);
    auto scopedExit = parse(context, scopedExitSource);
    if (!scopedExit)
      return fail("generated loop-case scope-exit fixture did not parse");
    std::string scopedExitOutput;
    llvm::raw_string_ostream scopedExitStream(scopedExitOutput);
    if (mlir::failed(
            nodal::emitBackend(*scopedExit, nodal::BackendKind::VerilogA, scopedExitStream)))
      return fail("generated loop-case scope-exit emission failed");
    scopedExitStream.flush();
    auto scopedExitRuntime =
        scopedExitOutput.find("for (event_GeneratedLoopCaseLexicalStorage_loop_0_index = 0;");
    auto scopedExitCase = scopedExitOutput.find("case (event_GeneratedLoopCaseLexicalStorage_mode)",
                                                scopedExitRuntime);
    auto scopedExitArm = scopedExitOutput.find("0: begin", scopedExitCase);
    auto scopedExitArmBegin = scopedExitOutput.find("begin", scopedExitArm);
    auto scopedExitScopeBegin = scopedExitOutput.find("begin", scopedExitArmBegin + 5);
    auto scopedExitAssignmentOutput = scopedExitOutput.find(" + 8.0);", scopedExitScopeBegin);
    auto scopedExitBreak = scopedExitOutput.find("_break = 1;", scopedExitAssignmentOutput);
    auto scopedExitDefault = scopedExitOutput.find("default: begin", scopedExitBreak);
    if (scopedExitRuntime == std::string::npos || scopedExitCase == std::string::npos ||
        scopedExitArm == std::string::npos || scopedExitArmBegin == std::string::npos ||
        scopedExitScopeBegin == std::string::npos ||
        scopedExitAssignmentOutput == std::string::npos || scopedExitBreak == std::string::npos ||
        scopedExitDefault == std::string::npos ||
        !(scopedExitRuntime < scopedExitCase && scopedExitCase < scopedExitArm &&
          scopedExitArm < scopedExitArmBegin && scopedExitArmBegin < scopedExitScopeBegin &&
          scopedExitScopeBegin < scopedExitAssignmentOutput &&
          scopedExitAssignmentOutput < scopedExitBreak && scopedExitBreak < scopedExitDefault) ||
        scopedExitOutput.find("@(") != std::string::npos ||
        mlir::failed(nodal::reparseBackendTarget(scopedExitOutput, *configuration)))
      return fail("generated loop-case scope exit lost its direct target topology");

    std::string scopedContinueSource = scopedSource;
    auto scopedContinueAssignment = scopedContinueSource.find(assignmentAnchor);
    auto scopedContinueAssignmentEnd = scopedContinueSource.find('\n', scopedContinueAssignment);
    if (scopedContinueAssignment == std::string::npos ||
        scopedContinueAssignmentEnd == std::string::npos)
      return fail("generated loop-case scope-continue assignment anchor missing");
    const std::string scopedContinue =
        "                \"nodal.analog_continue\"() <{metadata = {semantic_path = "
        "\"GeneratedLoopCaseLexicalStorage.loop_0.case_0.scope_0.continue_0\"}, owner = "
        "\"GeneratedLoopCaseLexicalStorage\", statement_id = "
        "\"GeneratedLoopCaseLexicalStorage.loop_0.case_0.scope_0.continue_0\"}> : () -> ()\n";
    scopedContinueSource.insert(scopedContinueAssignmentEnd + 1, scopedContinue);
    auto scopedContinueModule = parse(context, scopedContinueSource);
    if (!scopedContinueModule)
      return fail("generated loop-case scope-continue fixture did not parse");
    std::string scopedContinueOutput;
    llvm::raw_string_ostream scopedContinueStream(scopedContinueOutput);
    if (mlir::failed(nodal::emitBackend(*scopedContinueModule, nodal::BackendKind::VerilogA,
                                        scopedContinueStream)))
      return fail("generated loop-case scope-continue emission failed");
    scopedContinueStream.flush();
    auto scopedContinueRuntime =
        scopedContinueOutput.find("for (event_GeneratedLoopCaseLexicalStorage_loop_0_index = 0;");
    auto scopedContinueCase = scopedContinueOutput.find(
        "case (event_GeneratedLoopCaseLexicalStorage_mode)", scopedContinueRuntime);
    auto scopedContinueArm = scopedContinueOutput.find("0: begin", scopedContinueCase);
    auto scopedContinueArmBegin = scopedContinueOutput.find("begin", scopedContinueArm);
    auto scopedContinueScopeBegin = scopedContinueOutput.find("begin", scopedContinueArmBegin + 5);
    auto scopedContinueAssignmentOutput =
        scopedContinueOutput.find(" + 8.0);", scopedContinueScopeBegin);
    auto scopedContinueExit =
        scopedContinueOutput.find("_continue = 1;", scopedContinueAssignmentOutput);
    auto scopedContinueDefault = scopedContinueOutput.find("default: begin", scopedContinueExit);
    if (scopedContinueRuntime == std::string::npos || scopedContinueCase == std::string::npos ||
        scopedContinueArm == std::string::npos || scopedContinueArmBegin == std::string::npos ||
        scopedContinueScopeBegin == std::string::npos ||
        scopedContinueAssignmentOutput == std::string::npos ||
        scopedContinueExit == std::string::npos || scopedContinueDefault == std::string::npos ||
        !(scopedContinueRuntime < scopedContinueCase && scopedContinueCase < scopedContinueArm &&
          scopedContinueArm < scopedContinueArmBegin &&
          scopedContinueArmBegin < scopedContinueScopeBegin &&
          scopedContinueScopeBegin < scopedContinueAssignmentOutput &&
          scopedContinueAssignmentOutput < scopedContinueExit &&
          scopedContinueExit < scopedContinueDefault) ||
        scopedContinueOutput.find("@(") != std::string::npos ||
        mlir::failed(nodal::reparseBackendTarget(scopedContinueOutput, *configuration)))
      return fail("generated loop-case scope continue lost its direct target topology");

    auto forgedContinue = parse(context, scopedContinueSource);
    if (!forgedContinue)
      return fail("generated loop-case scope-continue ownership fixture did not parse");
    mlir::Operation *continueOperation = nullptr;
    forgedContinue->walk([&](mlir::Operation *operation) {
      if (!continueOperation && operation->getName().getStringRef() == "nodal.analog_continue")
        continueOperation = operation;
    });
    if (!continueOperation)
      return fail("generated loop-case scope-continue ownership anchor missing");
    continueOperation->setAttr(
        "owner", mlir::StringAttr::get(&context, "GeneratedLoopCaseLexicalStorage.forged"));
    std::string continueRejected = "sentinel";
    llvm::raw_string_ostream forgedContinueStream(continueRejected);
    if (mlir::succeeded(nodal::emitBackend(*forgedContinue, nodal::BackendKind::VerilogA,
                                           forgedContinueStream)))
      return fail("forged generated loop-case scope continue passed backend verification");
    forgedContinueStream.flush();
    if (continueRejected != "sentinel")
      return fail("forged generated loop-case scope continue published partial output");

    std::string duplicateContinueSource = scopedContinueSource;
    auto duplicateContinuePosition = duplicateContinueSource.find("                }) : () -> ()",
                                                                  scopedContinueAssignmentEnd + 1);
    if (duplicateContinuePosition == std::string::npos)
      return fail("generated loop-case scope duplicate-continue anchor missing");
    std::string secondContinue = scopedContinue;
    for (auto duplicateId = secondContinue.find("continue_0"); duplicateId != std::string::npos;
         duplicateId = secondContinue.find("continue_0", duplicateId + 10))
      secondContinue.replace(duplicateId, 10, "continue_1");
    duplicateContinueSource.insert(duplicateContinuePosition, secondContinue);
    auto duplicateContinue = parse(context, duplicateContinueSource);
    if (!duplicateContinue)
      return fail("generated loop-case scope duplicate-continue mutation did not parse");
    continueRejected = "sentinel";
    llvm::raw_string_ostream duplicateContinueStream(continueRejected);
    if (mlir::succeeded(nodal::emitBackend(*duplicateContinue, nodal::BackendKind::VerilogA,
                                           duplicateContinueStream)))
      return fail("duplicate generated loop-case scope continue passed backend verification");
    duplicateContinueStream.flush();
    if (continueRejected != "sentinel")
      return fail("duplicate generated loop-case scope continue published partial output");

    auto forgedExit = parse(context, scopedExitSource);
    if (!forgedExit)
      return fail("generated loop-case scope-exit ownership fixture did not parse");
    mlir::Operation *breakOperation = nullptr;
    forgedExit->walk([&](mlir::Operation *operation) {
      if (!breakOperation && operation->getName().getStringRef() == "nodal.analog_break")
        breakOperation = operation;
    });
    if (!breakOperation)
      return fail("generated loop-case scope-exit ownership anchor missing");
    breakOperation->setAttr(
        "owner", mlir::StringAttr::get(&context, "GeneratedLoopCaseLexicalStorage.forged"));
    std::string exitRejected = "sentinel";
    llvm::raw_string_ostream forgedExitStream(exitRejected);
    if (mlir::succeeded(
            nodal::emitBackend(*forgedExit, nodal::BackendKind::VerilogA, forgedExitStream)))
      return fail("forged generated loop-case scope exit passed backend verification");
    forgedExitStream.flush();
    if (exitRejected != "sentinel")
      return fail("forged generated loop-case scope exit published partial output");

    std::string duplicateExitSource = scopedExitSource;
    auto duplicateExitPosition =
        duplicateExitSource.find("                }) : () -> ()", scopedExitAssignmentEnd + 1);
    if (duplicateExitPosition == std::string::npos)
      return fail("generated loop-case scope duplicate-exit anchor missing");
    std::string duplicateBreak = scopedBreak;
    for (auto duplicateId = duplicateBreak.find("break_0"); duplicateId != std::string::npos;
         duplicateId = duplicateBreak.find("break_0", duplicateId + 7))
      duplicateBreak.replace(duplicateId, 7, "break_1");
    duplicateExitSource.insert(duplicateExitPosition, duplicateBreak);
    auto duplicateExit = parse(context, duplicateExitSource);
    if (!duplicateExit)
      return fail("generated loop-case scope duplicate-exit mutation did not parse");
    exitRejected = "sentinel";
    llvm::raw_string_ostream duplicateExitStream(exitRejected);
    if (mlir::succeeded(
            nodal::emitBackend(*duplicateExit, nodal::BackendKind::VerilogA, duplicateExitStream)))
      return fail("duplicate generated loop-case scope exit passed backend verification");
    duplicateExitStream.flush();
    if (exitRejected != "sentinel")
      return fail("duplicate generated loop-case scope exit published partial output");

    std::string deeperExitSource = scopedSource;
    auto deeperExitAssignment = deeperExitSource.find(assignmentAnchor);
    auto deeperExitAssignmentEnd = deeperExitSource.find('\n', deeperExitAssignment);
    if (deeperExitAssignment == std::string::npos || deeperExitAssignmentEnd == std::string::npos)
      return fail("generated loop-case deeper scope-exit assignment anchor missing");
    const std::string deeperScopedBreak =
        "                \"nodal.analog_scope\"() <{metadata = {semantic_path = "
        "\"GeneratedLoopCaseLexicalStorage.loop_0.case_0.scope_0.scope_0\"}, owner = "
        "\"GeneratedLoopCaseLexicalStorage\", scope_id = "
        "\"GeneratedLoopCaseLexicalStorage.loop_0.case_0.scope_0.scope_0\"}> ({\n"
        "                ^bb0:\n" +
        scopedBreak + "                }) : () -> ()\n";
    deeperExitSource.insert(deeperExitAssignmentEnd + 1, deeperScopedBreak);
    auto deeperExit = parse(context, deeperExitSource);
    if (!deeperExit)
      return fail("generated loop-case deeper scope-exit mutation did not parse");
    exitRejected = "sentinel";
    llvm::raw_string_ostream deeperExitStream(exitRejected);
    if (mlir::succeeded(
            nodal::emitBackend(*deeperExit, nodal::BackendKind::VerilogA, deeperExitStream)))
      return fail("deeper generated loop-case scope exit passed backend verification");
    deeperExitStream.flush();
    if (exitRejected != "sentinel")
      return fail("deeper generated loop-case scope exit published partial output");

    auto forged = parse(context, scopedSource);
    if (!forged)
      return fail("could not parse the generated loop-case scope ownership fixture");
    mlir::Operation *scope = nullptr;
    forged->walk([&](mlir::Operation *operation) {
      if (!scope && operation->getName().getStringRef() == "nodal.analog_scope")
        scope = operation;
    });
    if (!scope)
      return fail("generated loop-case scope ownership mutation anchor missing");
    scope->setAttr("owner",
                   mlir::StringAttr::get(&context, "GeneratedLoopCaseLexicalStorage.forged"));
    std::string rejected = "sentinel";
    llvm::raw_string_ostream rejectedStream(rejected);
    if (mlir::succeeded(nodal::emitBackend(*forged, nodal::BackendKind::VerilogA, rejectedStream)))
      return fail("forged generated loop-case scope ownership passed backend verification");
    rejectedStream.flush();
    if (rejected != "sentinel")
      return fail("forged generated loop-case scope published partial output");

    std::string emptyScopeSource = kGeneratedLoopCaseLexicalStorageModule.str();
    const std::string emptyScope =
        "                \"nodal.analog_scope\"() <{metadata = {semantic_path = "
        "\"GeneratedLoopCaseLexicalStorage.loop_0.case_0.empty_scope\"}, owner = "
        "\"GeneratedLoopCaseLexicalStorage\", scope_id = "
        "\"GeneratedLoopCaseLexicalStorage.loop_0.case_0.empty_scope\"}> ({\n"
        "                ^bb0:\n"
        "                }) : () -> ()\n";
    auto emptyPosition = emptyScopeSource.find(anchor);
    if (emptyPosition == std::string::npos)
      return fail("generated loop-case empty scope mutation anchor missing");
    emptyScopeSource.insert(emptyPosition, emptyScope);
    auto empty = parse(context, emptyScopeSource);
    if (!empty)
      return fail("generated loop-case empty scope mutation did not parse");
    rejected = "sentinel";
    llvm::raw_string_ostream emptyRejectedStream(rejected);
    if (mlir::succeeded(
            nodal::emitBackend(*empty, nodal::BackendKind::VerilogA, emptyRejectedStream)))
      return fail("empty generated loop-case scope passed backend verification");
    emptyRejectedStream.flush();
    if (rejected != "sentinel")
      return fail("empty generated loop-case scope published partial output");

    std::string deeperSource = scopedSource;
    const std::string deeperScope =
        "                \"nodal.analog_scope\"() <{metadata = {semantic_path = "
        "\"GeneratedLoopCaseLexicalStorage.loop_0.case_0.scope_0.scope_0\"}, owner = "
        "\"GeneratedLoopCaseLexicalStorage\", scope_id = "
        "\"GeneratedLoopCaseLexicalStorage.loop_0.case_0.scope_0.scope_0\"}> ({\n"
        "                ^bb0:\n"
        "                }) : () -> ()\n";
    auto deeperPosition = deeperSource.find(anchor);
    if (deeperPosition == std::string::npos)
      return fail("generated loop-case deeper scope mutation anchor missing");
    deeperSource.insert(deeperPosition, deeperScope);
    auto deeper = parse(context, deeperSource);
    if (!deeper)
      return fail("generated loop-case deeper scope mutation did not parse");
    rejected = "sentinel";
    llvm::raw_string_ostream deeperRejectedStream(rejected);
    if (mlir::succeeded(
            nodal::emitBackend(*deeper, nodal::BackendKind::VerilogA, deeperRejectedStream)))
      return fail("deeper generated loop-case scope passed backend verification");
    deeperRejectedStream.flush();
    if (rejected != "sentinel")
      return fail("deeper generated loop-case scope published partial output");
  }

  auto generatedEvent = parse(context, kGeneratedEventModule);
  if (!generatedEvent)
    return fail("could not parse the generated event fixture");
  std::string generatedEventOutput;
  llvm::raw_string_ostream generatedEventStream(generatedEventOutput);
  if (mlir::failed(
          nodal::emitBackend(*generatedEvent, nodal::BackendKind::VerilogA, generatedEventStream)))
    return fail("generated event emission failed");
  generatedEventStream.flush();
  if (generatedEventOutput.find(
          "for (generate_0_index = 0; generate_0_index < 2; generate_0_index = "
          "generate_0_index + 1) begin : generate_0\n") == std::string::npos ||
      generatedEventOutput.find("@(cross(1.0)) begin\n") == std::string::npos ||
      generatedEventOutput.find("real event_") != std::string::npos ||
      generatedEventOutput.find("integer event_") != std::string::npos ||
      mlir::failed(nodal::reparseBackendTarget(generatedEventOutput, *configuration)))
    return fail("generated event lost its single owned target occurrence");

  {
    auto forged = parse(context, kGeneratedEventModule);
    if (!forged)
      return fail("could not parse the generated event mutation fixture");
    mlir::Operation *procedure = nullptr;
    forged->walk([&](mlir::Operation *operation) {
      if (!procedure && operation->getName().getStringRef() == "nodal.analog_procedure")
        procedure = operation;
    });
    if (!procedure)
      return fail("generated event procedure mutation anchor missing");
    procedure->setAttr("generated_owner", mlir::StringAttr::get(&context, "GeneratedEvent.forged"));
    std::string rejected = "sentinel";
    llvm::raw_string_ostream rejectedStream(rejected);
    if (mlir::succeeded(nodal::emitBackend(*forged, nodal::BackendKind::VerilogA, rejectedStream)))
      return fail("forged generated event ownership passed backend verification");
    rejectedStream.flush();
    if (rejected != "sentinel")
      return fail("forged generated event ownership published partial output");
  }

  {
    const std::string anchor = "          %cross = \"nodal.analog_cross\"()";
    const std::string storage =
        "          %select = \"nodal.analog_variable\"() <{identity = "
        "\"GeneratedEvent.select\", owner = \"GeneratedEvent\", declaration_order = 0 : "
        "i64, initialized = true, initializer_value = \"false\", initializer_kind = "
        "\"boolean\", initializer_dimension = \"1\", initializer_reads = [], "
        "generated_owner = \"GeneratedEvent.generate_0\", generated_induction = "
        "\"GeneratedEvent.generate_0.index\", metadata = {}}> : () -> "
        "!nodal.variable<\"boolean\", \"1\">\n"
        "          %mode = \"nodal.analog_variable\"() <{identity = "
        "\"GeneratedEvent.mode\", owner = \"GeneratedEvent\", declaration_order = 1 : "
        "i64, initialized = true, initializer_value = \"0\", initializer_kind = "
        "\"integer\", initializer_dimension = \"1\", initializer_reads = [], "
        "generated_owner = \"GeneratedEvent.generate_0\", generated_induction = "
        "\"GeneratedEvent.generate_0.index\", metadata = {}}> : () -> "
        "!nodal.variable<\"integer\", \"1\">\n"
        "          %iterations = \"nodal.analog_variable\"() <{identity = "
        "\"GeneratedEvent.iterations\", owner = \"GeneratedEvent\", declaration_order = 2 : "
        "i64, initialized = true, initializer_value = \"1\", initializer_kind = "
        "\"integer\", initializer_dimension = \"1\", initializer_reads = [], "
        "generated_owner = \"GeneratedEvent.generate_0\", generated_induction = "
        "\"GeneratedEvent.generate_0.index\", metadata = {}}> : () -> "
        "!nodal.variable<\"integer\", \"1\">\n"
        "          %state = \"nodal.analog_variable\"() <{identity = "
        "\"GeneratedEvent.state\", owner = \"GeneratedEvent\", declaration_order = 3 : "
        "i64, initialized = true, initializer_value = \"0.0 V\", initializer_kind = "
        "\"real\", initializer_dimension = \"voltage\", initializer_reads = [], "
        "generated_owner = \"GeneratedEvent.generate_0\", generated_induction = "
        "\"GeneratedEvent.generate_0.index\", metadata = {}}> : () -> "
        "!nodal.variable<\"real\", \"voltage\">\n";
    const std::string bodyAnchor = "          ^bb0:\n          }) : (!nodal.analog_event) -> ()";
    const std::string conditionalAssignment =
        "          ^bb0:\n"
        "            \"nodal.analog_if\"() <{metadata = {semantic_path = "
        "\"GeneratedEvent.event_0.if_0\"}, owner = \"GeneratedEvent\", statement_id = "
        "\"GeneratedEvent.event_0.if_0\"}> ({\n"
        "            ^bb0:\n"
        "              \"nodal.analog_if_arm\"() <{arm_id = "
        "\"GeneratedEvent.event_0.if_0.branch_0\", condition_dimension = \"1\", "
        "condition_kind = \"boolean\", condition_reads = [\"GeneratedEvent.select\"], "
        "condition_value = \"GeneratedEvent.select\", is_else = false, metadata = "
        "{semantic_path = \"GeneratedEvent.event_0.if_0.condition_0\"}, owner = "
        "\"GeneratedEvent\", stage = \"runtime\", static_value = false, "
        "static_value_present = false}> ({\n"
        "              ^bb0:\n"
        "                %state_read = \"nodal.analog_variable_read\"(%state) "
        "<{read_id = \"GeneratedEvent.event_0.assignment.read_0\", owner = "
        "\"GeneratedEvent\", generated_owner = \"GeneratedEvent.generate_0\", "
        "generated_induction = \"GeneratedEvent.generate_0.index\", metadata = "
        "{semantic_path = \"GeneratedEvent.event_0.assignment.read_0\"}}> : "
        "(!nodal.variable<\"real\", \"voltage\">) -> "
        "!nodal.quantity<\"real\", \"voltage\">\n"
        "                \"nodal.analog_assign\"(%state, %state_read) <{analyses = "
        "[\"dc\", \"transient\"], authored_order = 0 : i64, guard_dimension = "
        "\"\", guard_kind = \"\", guard_present = false, guard_reads = [], "
        "guard_value = \"\", generated_owner = \"GeneratedEvent.generate_0\", "
        "generated_induction = \"GeneratedEvent.generate_0.index\", metadata = "
        "{semantic_path = \"GeneratedEvent.event_0.assignment\", value = "
        "\"analog_add(GeneratedEvent.state,1.0 V)\"}, owner = \"GeneratedEvent\", "
        "statement_id = \"GeneratedEvent.event_0.assignment\", value_dimension = "
        "\"voltage\", value_kind = \"real\"}> : (!nodal.variable<\"real\", "
        "\"voltage\">, !nodal.quantity<\"real\", \"voltage\">) -> ()\n"
        "              }) : () -> ()\n"
        "              \"nodal.analog_if_arm\"() <{arm_id = "
        "\"GeneratedEvent.event_0.if_0.otherwise\", condition_dimension = \"\", "
        "condition_kind = \"\", condition_reads = [], condition_value = \"\", "
        "is_else = true, metadata = {semantic_path = "
        "\"GeneratedEvent.event_0.if_0.otherwise\"}, owner = \"GeneratedEvent\", "
        "stage = \"else\", static_value = false, static_value_present = false}> ({\n"
        "              ^bb0:\n"
        "                %state_read_else = \"nodal.analog_variable_read\"(%state) "
        "<{read_id = \"GeneratedEvent.event_0.assignment_else.read_0\", owner = "
        "\"GeneratedEvent\", generated_owner = \"GeneratedEvent.generate_0\", "
        "generated_induction = \"GeneratedEvent.generate_0.index\", metadata = "
        "{semantic_path = \"GeneratedEvent.event_0.assignment_else.read_0\"}}> : "
        "(!nodal.variable<\"real\", \"voltage\">) -> "
        "!nodal.quantity<\"real\", \"voltage\">\n"
        "                \"nodal.analog_assign\"(%state, %state_read_else) <{analyses = "
        "[\"dc\", \"transient\"], authored_order = 1 : i64, guard_dimension = "
        "\"\", guard_kind = \"\", guard_present = false, guard_reads = [], "
        "guard_value = \"\", generated_owner = \"GeneratedEvent.generate_0\", "
        "generated_induction = \"GeneratedEvent.generate_0.index\", metadata = "
        "{semantic_path = \"GeneratedEvent.event_0.assignment_else\", value = "
        "\"analog_add(GeneratedEvent.state,2.0 V)\"}, owner = \"GeneratedEvent\", "
        "statement_id = \"GeneratedEvent.event_0.assignment_else\", value_dimension = "
        "\"voltage\", value_kind = \"real\"}> : (!nodal.variable<\"real\", "
        "\"voltage\">, !nodal.quantity<\"real\", \"voltage\">) -> ()\n"
        "              }) : () -> ()\n"
        "            }) : () -> ()\n"
        "            \"nodal.analog_case\"() <{metadata = {semantic_path = "
        "\"GeneratedEvent.event_0.case_1.selector\"}, owner = \"GeneratedEvent\", "
        "selector_dimension = \"1\", selector_kind = \"integer\", selector_reads = "
        "[\"GeneratedEvent.mode\"], selector_value = \"GeneratedEvent.mode\", "
        "statement_id = \"GeneratedEvent.event_0.case_1\", static_value = \"\", "
        "static_value_present = false}> ({\n"
        "            ^bb0:\n"
        "              \"nodal.analog_case_arm\"() <{arm_id = "
        "\"GeneratedEvent.event_0.case_1.arm_0\", is_default = false, labels = "
        "[\"integer:0\"], metadata = {semantic_path = "
        "\"GeneratedEvent.event_0.case_1.arm_0\"}, owner = \"GeneratedEvent\"}> ({\n"
        "              ^bb0:\n"
        "                %state_read_case = \"nodal.analog_variable_read\"(%state) "
        "<{read_id = \"GeneratedEvent.event_0.case_assignment.read_0\", owner = "
        "\"GeneratedEvent\", generated_owner = \"GeneratedEvent.generate_0\", "
        "generated_induction = \"GeneratedEvent.generate_0.index\", metadata = "
        "{semantic_path = \"GeneratedEvent.event_0.case_assignment.read_0\"}}> : "
        "(!nodal.variable<\"real\", \"voltage\">) -> "
        "!nodal.quantity<\"real\", \"voltage\">\n"
        "                \"nodal.analog_assign\"(%state, %state_read_case) <{analyses = "
        "[\"dc\", \"transient\"], authored_order = 2 : i64, guard_dimension = "
        "\"\", guard_kind = \"\", guard_present = false, guard_reads = [], "
        "guard_value = \"\", generated_owner = \"GeneratedEvent.generate_0\", "
        "generated_induction = \"GeneratedEvent.generate_0.index\", metadata = "
        "{semantic_path = \"GeneratedEvent.event_0.case_assignment\", value = "
        "\"analog_add(GeneratedEvent.state,3.0 V)\"}, owner = \"GeneratedEvent\", "
        "statement_id = \"GeneratedEvent.event_0.case_assignment\", value_dimension = "
        "\"voltage\", value_kind = \"real\"}> : (!nodal.variable<\"real\", "
        "\"voltage\">, !nodal.quantity<\"real\", \"voltage\">) -> ()\n"
        "              }) : () -> ()\n"
        "              \"nodal.analog_case_arm\"() <{arm_id = "
        "\"GeneratedEvent.event_0.case_1.default\", is_default = true, labels = [], "
        "metadata = {semantic_path = \"GeneratedEvent.event_0.case_1.default\"}, "
        "owner = \"GeneratedEvent\"}> ({\n"
        "              ^bb0:\n"
        "                %state_read_default = \"nodal.analog_variable_read\"(%state) "
        "<{read_id = \"GeneratedEvent.event_0.case_default_assignment.read_0\", owner = "
        "\"GeneratedEvent\", generated_owner = \"GeneratedEvent.generate_0\", "
        "generated_induction = \"GeneratedEvent.generate_0.index\", metadata = "
        "{semantic_path = \"GeneratedEvent.event_0.case_default_assignment.read_0\"}}> : "
        "(!nodal.variable<\"real\", \"voltage\">) -> "
        "!nodal.quantity<\"real\", \"voltage\">\n"
        "                \"nodal.analog_assign\"(%state, %state_read_default) <{analyses = "
        "[\"dc\", \"transient\"], authored_order = 3 : i64, guard_dimension = "
        "\"\", guard_kind = \"\", guard_present = false, guard_reads = [], "
        "guard_value = \"\", generated_owner = \"GeneratedEvent.generate_0\", "
        "generated_induction = \"GeneratedEvent.generate_0.index\", metadata = "
        "{semantic_path = \"GeneratedEvent.event_0.case_default_assignment\", value = "
        "\"analog_add(GeneratedEvent.state,4.0 V)\"}, owner = \"GeneratedEvent\", "
        "statement_id = \"GeneratedEvent.event_0.case_default_assignment\", "
        "value_dimension = \"voltage\", value_kind = \"real\"}> : "
        "(!nodal.variable<\"real\", \"voltage\">, !nodal.quantity<\"real\", "
        "\"voltage\">) -> ()\n"
        "              }) : () -> ()\n"
        "            }) : () -> ()\n"
        "            \"nodal.analog_loop\"() <{bound_dimension = \"1\", "
        "bound_kind = \"integer\", bound_reads = [\"GeneratedEvent.iterations\"], "
        "bound_value = \"GeneratedEvent.iterations\", maximum_iterations = 2 : i64, "
        "metadata = {semantic_path = \"GeneratedEvent.event_0.loop_2\"}, "
        "minimum_iterations = 1 : i64, owner = \"GeneratedEvent\", stage = \"runtime\", "
        "statement_id = \"GeneratedEvent.event_0.loop_2\", static_trip_count = 0 : i64, "
        "static_trip_count_present = false}> ({\n"
        "            ^bb0:\n"
        "              \"nodal.analog_if\"() <{metadata = {semantic_path = "
        "\"GeneratedEvent.event_0.loop_2.if_0\"}, owner = \"GeneratedEvent\", "
        "statement_id = \"GeneratedEvent.event_0.loop_2.if_0\"}> ({\n"
        "              ^bb0:\n"
        "                \"nodal.analog_if_arm\"() <{arm_id = "
        "\"GeneratedEvent.event_0.loop_2.if_0.branch_0\", condition_dimension = \"1\", "
        "condition_kind = \"boolean\", condition_reads = [\"GeneratedEvent.select\"], "
        "condition_value = \"GeneratedEvent.select\", is_else = false, metadata = "
        "{semantic_path = \"GeneratedEvent.event_0.loop_2.if_0.condition_0\"}, owner = "
        "\"GeneratedEvent\", stage = \"runtime\", static_value = false, "
        "static_value_present = false}> ({\n"
        "                ^bb0:\n"
        "                  %state_read_loop = \"nodal.analog_variable_read\"(%state) "
        "<{read_id = \"GeneratedEvent.event_0.loop_2.if_0.assignment.read_0\", owner = "
        "\"GeneratedEvent\", generated_owner = \"GeneratedEvent.generate_0\", "
        "generated_induction = \"GeneratedEvent.generate_0.index\", metadata = "
        "{semantic_path = \"GeneratedEvent.event_0.loop_2.if_0.assignment.read_0\"}}> : "
        "(!nodal.variable<\"real\", \"voltage\">) -> "
        "!nodal.quantity<\"real\", \"voltage\">\n"
        "                  \"nodal.analog_assign\"(%state, %state_read_loop) <{analyses = "
        "[\"dc\", \"transient\"], authored_order = 4 : i64, guard_dimension = \"\", "
        "guard_kind = \"\", guard_present = false, guard_reads = [], guard_value = \"\", "
        "generated_owner = \"GeneratedEvent.generate_0\", generated_induction = "
        "\"GeneratedEvent.generate_0.index\", metadata = {semantic_path = "
        "\"GeneratedEvent.event_0.loop_2.if_0.assignment\", value = "
        "\"analog_add(GeneratedEvent.state,5.0 V)\"}, owner = \"GeneratedEvent\", "
        "statement_id = \"GeneratedEvent.event_0.loop_2.if_0.assignment\", "
        "value_dimension = \"voltage\", value_kind = \"real\"}> : "
        "(!nodal.variable<\"real\", \"voltage\">, !nodal.quantity<\"real\", "
        "\"voltage\">) -> ()\n"
        "                }) : () -> ()\n"
        "                \"nodal.analog_if_arm\"() <{arm_id = "
        "\"GeneratedEvent.event_0.loop_2.if_0.otherwise\", condition_dimension = \"\", "
        "condition_kind = \"\", condition_reads = [], condition_value = \"\", "
        "is_else = true, metadata = {semantic_path = "
        "\"GeneratedEvent.event_0.loop_2.if_0.otherwise\"}, owner = \"GeneratedEvent\", "
        "stage = \"else\", static_value = false, static_value_present = false}> ({\n"
        "                ^bb0:\n"
        "                  %state_read_loop_else = "
        "\"nodal.analog_variable_read\"(%state) <{read_id = "
        "\"GeneratedEvent.event_0.loop_2.if_0.assignment_else.read_0\", owner = "
        "\"GeneratedEvent\", generated_owner = \"GeneratedEvent.generate_0\", "
        "generated_induction = \"GeneratedEvent.generate_0.index\", metadata = "
        "{semantic_path = "
        "\"GeneratedEvent.event_0.loop_2.if_0.assignment_else.read_0\"}}> : "
        "(!nodal.variable<\"real\", \"voltage\">) -> "
        "!nodal.quantity<\"real\", \"voltage\">\n"
        "                  \"nodal.analog_assign\"(%state, %state_read_loop_else) "
        "<{analyses = [\"dc\", \"transient\"], authored_order = 5 : i64, "
        "guard_dimension = \"\", guard_kind = \"\", guard_present = false, "
        "guard_reads = [], guard_value = \"\", generated_owner = "
        "\"GeneratedEvent.generate_0\", generated_induction = "
        "\"GeneratedEvent.generate_0.index\", metadata = {semantic_path = "
        "\"GeneratedEvent.event_0.loop_2.if_0.assignment_else\", value = "
        "\"analog_add(GeneratedEvent.state,6.0 V)\"}, owner = \"GeneratedEvent\", "
        "statement_id = \"GeneratedEvent.event_0.loop_2.if_0.assignment_else\", "
        "value_dimension = \"voltage\", value_kind = \"real\"}> : "
        "(!nodal.variable<\"real\", \"voltage\">, !nodal.quantity<\"real\", "
        "\"voltage\">) -> ()\n"
        "                }) : () -> ()\n"
        "              }) : () -> ()\n"
        "              \"nodal.analog_scope\"() <{metadata = {semantic_path = "
        "\"GeneratedEvent.event_0.loop_2.scope_0\"}, owner = \"GeneratedEvent\", scope_id = "
        "\"GeneratedEvent.event_0.loop_2.scope_0\"}> ({\n"
        "              ^bb0:\n"
        "                %state_read_scope = \"nodal.analog_variable_read\"(%state) "
        "<{read_id = \"GeneratedEvent.event_0.loop_2.scope_0.assignment.read_0\", owner = "
        "\"GeneratedEvent\", generated_owner = \"GeneratedEvent.generate_0\", "
        "generated_induction = \"GeneratedEvent.generate_0.index\", metadata = "
        "{semantic_path = \"GeneratedEvent.event_0.loop_2.scope_0.assignment.read_0\"}}> : "
        "(!nodal.variable<\"real\", \"voltage\">) -> "
        "!nodal.quantity<\"real\", \"voltage\">\n"
        "                \"nodal.analog_assign\"(%state, %state_read_scope) <{analyses = "
        "[\"dc\", \"transient\"], authored_order = 6 : i64, guard_dimension = \"\", "
        "guard_kind = \"\", guard_present = false, guard_reads = [], guard_value = \"\", "
        "generated_owner = \"GeneratedEvent.generate_0\", generated_induction = "
        "\"GeneratedEvent.generate_0.index\", metadata = {semantic_path = "
        "\"GeneratedEvent.event_0.loop_2.scope_0.assignment\", value = "
        "\"analog_add(GeneratedEvent.state,9.0 V)\"}, owner = \"GeneratedEvent\", "
        "statement_id = \"GeneratedEvent.event_0.loop_2.scope_0.assignment\", "
        "value_dimension = \"voltage\", value_kind = \"real\"}> : "
        "(!nodal.variable<\"real\", \"voltage\">, !nodal.quantity<\"real\", "
        "\"voltage\">) -> ()\n"
        "              }) : () -> ()\n"
        "              \"nodal.analog_continue\"() <{metadata = {semantic_path = "
        "\"GeneratedEvent.event_0.loop_2.continue_0\"}, owner = \"GeneratedEvent\", "
        "statement_id = \"GeneratedEvent.event_0.loop_2.continue_0\"}> : () -> ()\n"
        "              \"nodal.analog_break\"() <{metadata = {semantic_path = "
        "\"GeneratedEvent.event_0.loop_2.break_1\"}, owner = \"GeneratedEvent\", "
        "statement_id = \"GeneratedEvent.event_0.loop_2.break_1\"}> : () -> ()\n"
        "            }) : () -> ()\n"
        "          }) : (!nodal.analog_event) -> ()";
    std::string generatedStorageSource = kGeneratedEventModule.str();
    auto position = generatedStorageSource.find(anchor);
    if (position == std::string::npos)
      return fail("generated event storage mutation anchor missing");
    generatedStorageSource.insert(position, storage);
    position = generatedStorageSource.find(bodyAnchor);
    if (position == std::string::npos)
      return fail("generated event assignment mutation anchor missing");
    generatedStorageSource.replace(position, bodyAnchor.size(), conditionalAssignment);
    auto generatedStorage = parse(context, generatedStorageSource);
    if (!generatedStorage)
      return fail("generated event storage fixture did not parse");
    std::string generatedStorageOutput;
    llvm::raw_string_ostream generatedStorageStream(generatedStorageOutput);
    if (mlir::failed(nodal::emitBackend(*generatedStorage, nodal::BackendKind::VerilogA,
                                        generatedStorageStream)))
      return fail("generated event storage emission failed");
    generatedStorageStream.flush();
    auto loop = generatedStorageOutput.find("for (generate_0_index");
    auto select = generatedStorageOutput.find("integer event_GeneratedEvent_select = 0;");
    auto mode = generatedStorageOutput.find("integer event_GeneratedEvent_mode = 0;");
    auto iterations = generatedStorageOutput.find("integer event_GeneratedEvent_iterations = 1;");
    auto state = generatedStorageOutput.find("real event_GeneratedEvent_state = 0.0;");
    auto read = generatedStorageOutput.find("real event_GeneratedEvent_event_0_assignment_read_0;");
    auto caseRead =
        generatedStorageOutput.find("real event_GeneratedEvent_event_0_case_assignment_read_0;");
    auto loopIndex =
        generatedStorageOutput.find("integer event_GeneratedEvent_event_0_loop_2_index;");
    auto loopRead = generatedStorageOutput.find(
        "real event_GeneratedEvent_event_0_loop_2_if_0_assignment_read_0;");
    auto loopElseRead = generatedStorageOutput.find(
        "real event_GeneratedEvent_event_0_loop_2_if_0_assignment_else_read_0;");
    auto loopScopeRead = generatedStorageOutput.find(
        "real event_GeneratedEvent_event_0_loop_2_scope_0_assignment_read_0;");
    auto procedure = generatedStorageOutput.find("begin : event_GeneratedEvent_procedure");
    if (loop == std::string::npos || select == std::string::npos || mode == std::string::npos ||
        iterations == std::string::npos || state == std::string::npos ||
        read == std::string::npos || caseRead == std::string::npos ||
        loopIndex == std::string::npos || loopRead == std::string::npos ||
        loopElseRead == std::string::npos || loopScopeRead == std::string::npos ||
        procedure == std::string::npos ||
        !(loop < select && select < mode && mode < iterations && iterations < state &&
          state < read && read < caseRead && caseRead < loopRead && loopRead < loopElseRead &&
          loopElseRead < loopScopeRead && loopScopeRead < loopIndex && loopIndex < procedure) ||
        generatedStorageOutput.find("if (event_GeneratedEvent_select) begin") ==
            std::string::npos ||
        generatedStorageOutput.find("else begin") == std::string::npos ||
        generatedStorageOutput.find("case (event_GeneratedEvent_mode)") == std::string::npos ||
        generatedStorageOutput.find("0: begin") == std::string::npos ||
        generatedStorageOutput.find("default: begin") == std::string::npos ||
        generatedStorageOutput.find(
            "event_GeneratedEvent_event_0_assignment_read_0 = event_GeneratedEvent_state;") ==
            std::string::npos ||
        generatedStorageOutput.find(
            "event_GeneratedEvent_state = (event_GeneratedEvent_event_0_assignment_read_0 + "
            "1.0);") == std::string::npos ||
        generatedStorageOutput.find(
            "event_GeneratedEvent_state = "
            "(event_GeneratedEvent_event_0_assignment_else_read_0 + 2.0);") == std::string::npos ||
        generatedStorageOutput.find(
            "event_GeneratedEvent_state = "
            "(event_GeneratedEvent_event_0_case_assignment_read_0 + 3.0);") == std::string::npos ||
        generatedStorageOutput.find(
            "event_GeneratedEvent_state = "
            "(event_GeneratedEvent_event_0_case_default_assignment_read_0 + 4.0);") ==
            std::string::npos ||
        generatedStorageOutput.find(
            "event_GeneratedEvent_event_0_loop_2_limit = event_GeneratedEvent_iterations;") ==
            std::string::npos ||
        generatedStorageOutput.find("for (event_GeneratedEvent_event_0_loop_2_index = 0; "
                                    "(event_GeneratedEvent_event_0_loop_2_index < "
                                    "event_GeneratedEvent_event_0_loop_2_limit) && "
                                    "!event_GeneratedEvent_event_0_loop_2_break") ==
            std::string::npos ||
        generatedStorageOutput.find(
            "event_GeneratedEvent_state = "
            "(event_GeneratedEvent_event_0_loop_2_if_0_assignment_read_0 + 5.0);") ==
            std::string::npos ||
        generatedStorageOutput.find(
            "event_GeneratedEvent_state = "
            "(event_GeneratedEvent_event_0_loop_2_if_0_assignment_else_read_0 + 6.0);") ==
            std::string::npos ||
        generatedStorageOutput.find(
            "event_GeneratedEvent_state = "
            "(event_GeneratedEvent_event_0_loop_2_scope_0_assignment_read_0 + 9.0);") ==
            std::string::npos ||
        generatedStorageOutput.find("event_GeneratedEvent_event_0_loop_2_continue = 1;") ==
            std::string::npos ||
        generatedStorageOutput.find("event_GeneratedEvent_event_0_loop_2_break = 1;") ==
            std::string::npos ||
        mlir::failed(nodal::reparseBackendTarget(generatedStorageOutput, *configuration)))
      return fail("generated event storage lost its per-occurrence target layout");

    std::string misplacedStorage = generatedStorageOutput;
    auto declarationStart = misplacedStorage.rfind('\n', state);
    declarationStart = declarationStart == std::string::npos ? 0 : declarationStart + 1;
    auto declarationEnd = misplacedStorage.find('\n', state);
    if (declarationEnd == std::string::npos)
      return fail("generated event storage target declaration anchor missing");
    std::string declaration =
        misplacedStorage.substr(declarationStart, declarationEnd - declarationStart + 1);
    misplacedStorage.erase(declarationStart, declaration.size());
    auto procedureClose = misplacedStorage.find(
        "    end\n    end\n", misplacedStorage.find("begin : event_GeneratedEvent_procedure"));
    if (procedureClose == std::string::npos)
      return fail("generated event storage target procedure anchor missing");
    misplacedStorage.insert(procedureClose + std::string("    end\n").size(), declaration);
    if (mlir::succeeded(nodal::reparseBackendTarget(misplacedStorage, *configuration)))
      return fail("late generated event storage declaration passed target reparse");

    auto invalid = parse(context, generatedStorageSource);
    if (!invalid)
      return fail("generated event storage mutation fixture did not parse");
    mlir::Operation *variable = nullptr;
    invalid->walk([&](mlir::Operation *operation) {
      if (!variable && operation->getName().getStringRef() == "nodal.analog_variable")
        variable = operation;
    });
    if (!variable)
      return fail("generated event storage ownership mutation anchor missing");
    variable->setAttr("generated_owner", mlir::StringAttr::get(&context, "GeneratedEvent.forged"));
    std::string rejected = "sentinel";
    llvm::raw_string_ostream rejectedStream(rejected);
    if (mlir::succeeded(nodal::emitBackend(*invalid, nodal::BackendKind::VerilogA, rejectedStream)))
      return fail("forged generated event storage passed backend verification");
    rejectedStream.flush();
    if (rejected != "sentinel")
      return fail("forged generated event storage published partial output");

    std::string nestedLoopSource = generatedStorageSource;
    const std::string nestedLoopAnchor = "            ^bb0:\n"
                                         "              \"nodal.analog_if\"()";
    const std::string nestedLoop =
        "            \"nodal.analog_loop\"() <{bound_dimension = \"1\", "
        "bound_kind = \"integer\", bound_reads = [], bound_value = \"1\", "
        "maximum_iterations = 1 : i64, metadata = {semantic_path = "
        "\"GeneratedEvent.event_0.loop_2.loop_0\"}, minimum_iterations = 1 : i64, owner = "
        "\"GeneratedEvent\", stage = \"runtime\", statement_id = "
        "\"GeneratedEvent.event_0.loop_2.loop_0\", static_trip_count = 0 : i64, "
        "static_trip_count_present = false}> ({\n"
        "            ^bb0:\n"
        "              %nested_read = \"nodal.analog_variable_read\"(%state) "
        "<{read_id = \"GeneratedEvent.event_0.loop_2.loop_0.read_0\", owner = "
        "\"GeneratedEvent\", generated_owner = \"GeneratedEvent.generate_0\", "
        "generated_induction = \"GeneratedEvent.generate_0.index\", metadata = "
        "{semantic_path = \"GeneratedEvent.event_0.loop_2.loop_0.read_0\"}}> : "
        "(!nodal.variable<\"real\", \"voltage\">) -> "
        "!nodal.quantity<\"real\", \"voltage\">\n"
        "            }) : () -> ()\n";
    position = nestedLoopSource.find(nestedLoopAnchor);
    if (position == std::string::npos)
      return fail("generated event nested-loop mutation anchor missing");
    position += std::string("            ^bb0:\n").size();
    nestedLoopSource.insert(position, nestedLoop);
    auto nestedLoopModule = parse(context, nestedLoopSource);
    if (!nestedLoopModule)
      return fail("generated event nested-loop mutation did not parse");
    std::string nestedLoopRejected = "sentinel";
    llvm::raw_string_ostream nestedLoopRejectedStream(nestedLoopRejected);
    if (mlir::succeeded(nodal::emitBackend(*nestedLoopModule, nodal::BackendKind::VerilogA,
                                           nestedLoopRejectedStream)))
      return fail("unsupported generated event nested loop passed backend verification");
    nestedLoopRejectedStream.flush();
    if (nestedLoopRejected != "sentinel")
      return fail("unsupported generated event nested loop published partial output");

    auto loopCaseModule = parse(context, generatedStorageSource);
    if (!loopCaseModule)
      return fail("generated event loop-case mutation fixture did not parse");
    mlir::Operation *runtimeLoop = nullptr;
    mlir::Operation *eventCase = nullptr;
    loopCaseModule->walk([&](mlir::Operation *operation) {
      if (!runtimeLoop && operation->getName().getStringRef() == "nodal.analog_loop")
        runtimeLoop = operation;
      if (!eventCase && operation->getName().getStringRef() == "nodal.analog_case")
        eventCase = operation;
    });
    if (!runtimeLoop || !eventCase)
      return fail("generated event loop-case mutation anchor missing");
    auto retagStructuredClone = [&](mlir::Operation *clone, llvm::StringRef suffix,
                                    int64_t assignmentOrder) {
      clone->walk([&](mlir::Operation *nested) {
        for (llvm::StringRef key : {"statement_id", "arm_id", "read_id"})
          if (auto identity = nested->getAttrOfType<mlir::StringAttr>(key))
            nested->setAttr(key,
                            mlir::StringAttr::get(&context, (identity.getValue() + suffix).str()));
        if (nested->hasAttr("authored_order"))
          nested->setAttr(
              "authored_order",
              mlir::IntegerAttr::get(mlir::IntegerType::get(&context, 64), assignmentOrder++));
      });
    };
    mlir::Operation *loopCase = eventCase->clone();
    retagStructuredClone(loopCase, ".loop_case_mutation", 7);
    runtimeLoop->getRegion(0).front().push_back(loopCase);
    std::string loopCaseOutput;
    llvm::raw_string_ostream loopCaseStream(loopCaseOutput);
    if (mlir::failed(
            nodal::emitBackend(*loopCaseModule, nodal::BackendKind::VerilogA, loopCaseStream)))
      return fail("generated event loop case failed backend verification");
    loopCaseStream.flush();
    auto loopCaseStart = loopCaseOutput.find("for (event_GeneratedEvent_event_0_loop_2_index");
    auto nestedCaseStart = loopCaseOutput.find("case (event_GeneratedEvent_mode)", loopCaseStart);
    if (loopCaseStart == std::string::npos || nestedCaseStart == std::string::npos ||
        nestedCaseStart < loopCaseStart ||
        mlir::failed(nodal::reparseBackendTarget(loopCaseOutput, *configuration)))
      return fail("generated event loop case lost its direct target topology");

    auto &loopCaseBlock = loopCase->getRegion(0).front();
    mlir::Operation &lastCaseArm = loopCaseBlock.back();
    mlir::Operation *nestedCase = loopCase->clone();
    retagStructuredClone(nestedCase, ".nested_case_mutation", 9);
    lastCaseArm.getRegion(0).front().push_back(nestedCase);
    std::string nestedCaseRejected = "sentinel";
    llvm::raw_string_ostream nestedCaseRejectedStream(nestedCaseRejected);
    if (mlir::succeeded(nodal::emitBackend(*loopCaseModule, nodal::BackendKind::VerilogA,
                                           nestedCaseRejectedStream)))
      return fail("unsupported nested generated loop case passed backend verification");
    nestedCaseRejectedStream.flush();
    if (nestedCaseRejected != "sentinel")
      return fail("unsupported nested generated loop case published partial output");

    auto nestedConditionalModule = parse(context, generatedStorageSource);
    if (!nestedConditionalModule)
      return fail("generated event nested-conditional mutation fixture did not parse");
    mlir::Operation *loopConditional = nullptr;
    nestedConditionalModule->walk([&](mlir::Operation *operation) {
      if (operation->getName().getStringRef() == "nodal.analog_if" && operation->getParentOp() &&
          operation->getParentOp()->getName().getStringRef() == "nodal.analog_loop")
        loopConditional = operation;
    });
    if (!loopConditional)
      return fail("generated event nested-conditional mutation anchor missing");
    auto &conditionalBlock = loopConditional->getRegion(0).front();
    mlir::Operation &lastArm = conditionalBlock.back();
    mlir::Operation *nestedConditional = loopConditional->clone();
    retagStructuredClone(nestedConditional, ".nested_conditional_mutation", 7);
    lastArm.getRegion(0).front().push_back(nestedConditional);
    std::string nestedConditionalRejected = "sentinel";
    llvm::raw_string_ostream nestedConditionalRejectedStream(nestedConditionalRejected);
    if (mlir::succeeded(nodal::emitBackend(*nestedConditionalModule, nodal::BackendKind::VerilogA,
                                           nestedConditionalRejectedStream)))
      return fail("unsupported nested generated loop conditional passed backend verification");
    nestedConditionalRejectedStream.flush();
    if (nestedConditionalRejected != "sentinel")
      return fail("unsupported nested generated loop conditional published partial output");

    auto nestedExitModule = parse(context, generatedStorageSource);
    if (!nestedExitModule)
      return fail("generated event nested-exit mutation fixture did not parse");
    mlir::Operation *nestedExitConditional = nullptr;
    mlir::Operation *nestedExitLoopOp = nullptr;
    mlir::Operation *nestedExitRootCase = nullptr;
    mlir::Operation *directBreak = nullptr;
    mlir::Operation *directContinue = nullptr;
    nestedExitModule->walk([&](mlir::Operation *operation) {
      if (!nestedExitLoopOp && operation->getName().getStringRef() == "nodal.analog_loop")
        nestedExitLoopOp = operation;
      if (operation->getName().getStringRef() == "nodal.analog_if" && operation->getParentOp() &&
          operation->getParentOp()->getName().getStringRef() == "nodal.analog_loop")
        nestedExitConditional = operation;
      if (!nestedExitRootCase && operation->getName().getStringRef() == "nodal.analog_case")
        nestedExitRootCase = operation;
      if (!directBreak && operation->getName().getStringRef() == "nodal.analog_break")
        directBreak = operation;
      if (!directContinue && operation->getName().getStringRef() == "nodal.analog_continue")
        directContinue = operation;
    });
    if (!nestedExitConditional || !nestedExitLoopOp || !nestedExitRootCase || !directBreak ||
        !directContinue)
      return fail("generated event nested-exit mutation anchor missing");
    mlir::Operation *nestedExitCase = nestedExitRootCase->clone();
    retagStructuredClone(nestedExitCase, ".arm_exit_mutation", 7);
    nestedExitLoopOp->getRegion(0).front().push_back(nestedExitCase);
    mlir::Operation *conditionalExit = directContinue->clone();
    conditionalExit->setAttr(
        "statement_id",
        mlir::StringAttr::get(&context, "GeneratedEvent.event_0.loop_2.arm_continue_mutation"));
    nestedExitConditional->getRegion(0).front().front().getRegion(0).front().push_back(
        conditionalExit);
    mlir::Operation *caseExit = directBreak->clone();
    caseExit->setAttr(
        "statement_id",
        mlir::StringAttr::get(&context, "GeneratedEvent.event_0.loop_2.arm_break_mutation"));
    nestedExitCase->getRegion(0).front().back().getRegion(0).front().push_back(caseExit);
    std::string nestedExitOutput;
    llvm::raw_string_ostream nestedExitStream(nestedExitOutput);
    if (mlir::failed(
            nodal::emitBackend(*nestedExitModule, nodal::BackendKind::VerilogA, nestedExitStream)))
      return fail("generated event loop-arm exit failed backend verification");
    nestedExitStream.flush();
    auto nestedExitLoop = nestedExitOutput.find("for (event_GeneratedEvent_event_0_loop_2_index");
    auto nestedExitIf = nestedExitOutput.find("if (event_GeneratedEvent_select)", nestedExitLoop);
    auto nestedExitContinue = nestedExitOutput.find("_continue = 1;", nestedExitIf);
    auto nestedExitCaseStart =
        nestedExitOutput.find("case (event_GeneratedEvent_mode)", nestedExitLoop);
    auto nestedExitDefault = nestedExitOutput.find("default: begin", nestedExitCaseStart);
    auto nestedExitBreak = nestedExitOutput.find("_break = 1;", nestedExitDefault);
    if (nestedExitLoop == std::string::npos || nestedExitIf == std::string::npos ||
        nestedExitContinue == std::string::npos || nestedExitCaseStart == std::string::npos ||
        nestedExitDefault == std::string::npos || nestedExitBreak == std::string::npos ||
        mlir::failed(nodal::reparseBackendTarget(nestedExitOutput, *configuration)))
      return fail("generated event loop-arm exit lost its direct target topology");
    conditionalExit->setAttr("owner", mlir::StringAttr::get(&context, "ForgedGeneratedEvent"));
    std::string forgedExitRejected = "sentinel";
    llvm::raw_string_ostream forgedExitRejectedStream(forgedExitRejected);
    if (mlir::succeeded(nodal::emitBackend(*nestedExitModule, nodal::BackendKind::VerilogA,
                                           forgedExitRejectedStream)))
      return fail("forged generated loop-arm exit passed backend verification");
    forgedExitRejectedStream.flush();
    if (forgedExitRejected != "sentinel")
      return fail("forged generated loop-arm exit published partial output");

    auto caseContinueModule = parse(context, generatedStorageSource);
    if (!caseContinueModule)
      return fail("generated event case-arm continue mutation fixture did not parse");
    mlir::Operation *caseContinueLoop = nullptr;
    mlir::Operation *caseContinueRootCase = nullptr;
    mlir::Operation *caseContinueTemplate = nullptr;
    mlir::Operation *caseContinueRootBreak = nullptr;
    caseContinueModule->walk([&](mlir::Operation *operation) {
      if (!caseContinueLoop && operation->getName().getStringRef() == "nodal.analog_loop")
        caseContinueLoop = operation;
      if (!caseContinueRootCase && operation->getName().getStringRef() == "nodal.analog_case" &&
          operation->getParentOp() &&
          operation->getParentOp()->getName().getStringRef() == "nodal.analog_on")
        caseContinueRootCase = operation;
      if (!caseContinueTemplate && operation->getName().getStringRef() == "nodal.analog_continue")
        caseContinueTemplate = operation;
      if (!caseContinueRootBreak && operation->getName().getStringRef() == "nodal.analog_break")
        caseContinueRootBreak = operation;
    });
    if (!caseContinueLoop || !caseContinueRootCase || !caseContinueTemplate ||
        !caseContinueRootBreak)
      return fail("generated event case-arm continue mutation anchor missing");
    mlir::Operation *caseContinue = caseContinueRootCase->clone();
    retagStructuredClone(caseContinue, ".case_continue_mutation", 7);
    mlir::Operation *caseContinueExit = caseContinueTemplate->clone();
    caseContinueExit->setAttr(
        "statement_id",
        mlir::StringAttr::get(&context, "GeneratedEvent.event_0.loop_2.case_continue_mutation"));
    caseContinueTemplate->erase();
    caseContinueRootBreak->erase();
    caseContinue->getRegion(0).front().back().getRegion(0).front().push_back(caseContinueExit);
    caseContinueLoop->getRegion(0).front().push_back(caseContinue);
    std::string caseContinueOutput;
    llvm::raw_string_ostream caseContinueStream(caseContinueOutput);
    if (mlir::failed(nodal::emitBackend(*caseContinueModule, nodal::BackendKind::VerilogA,
                                        caseContinueStream)))
      return fail("generated event case-arm continue failed backend verification");
    caseContinueStream.flush();
    auto caseContinueLoopStart =
        caseContinueOutput.find("for (event_GeneratedEvent_event_0_loop_2_index");
    auto caseContinueCase =
        caseContinueOutput.find("case (event_GeneratedEvent_mode)", caseContinueLoopStart);
    auto caseContinueDefault = caseContinueOutput.find("default: begin", caseContinueCase);
    auto caseContinueTarget = caseContinueOutput.find("_continue = 1;", caseContinueDefault);
    if (caseContinueLoopStart == std::string::npos || caseContinueCase == std::string::npos ||
        caseContinueDefault == std::string::npos || caseContinueTarget == std::string::npos ||
        !(caseContinueLoopStart < caseContinueCase && caseContinueCase < caseContinueDefault &&
          caseContinueDefault < caseContinueTarget) ||
        mlir::failed(nodal::reparseBackendTarget(caseContinueOutput, *configuration)))
      return fail("generated event case-arm continue lost its direct target topology");
    caseContinueExit->setAttr("owner", mlir::StringAttr::get(&context, "ForgedGeneratedEvent"));
    std::string caseContinueRejected = "sentinel";
    llvm::raw_string_ostream caseContinueRejectedStream(caseContinueRejected);
    if (mlir::succeeded(nodal::emitBackend(*caseContinueModule, nodal::BackendKind::VerilogA,
                                           caseContinueRejectedStream)))
      return fail("forged generated event case-arm continue passed backend verification");
    caseContinueRejectedStream.flush();
    if (caseContinueRejected != "sentinel")
      return fail("forged generated event case-arm continue published partial output");

    auto deeperExitModule = parse(context, generatedStorageSource);
    if (!deeperExitModule)
      return fail("generated event deeper-exit mutation fixture did not parse");
    mlir::Operation *deeperExitScope = nullptr;
    mlir::Operation *deeperBreak = nullptr;
    deeperExitModule->walk([&](mlir::Operation *operation) {
      if (!deeperExitScope && operation->getName().getStringRef() == "nodal.analog_scope" &&
          operation->getParentOp() &&
          operation->getParentOp()->getName().getStringRef() == "nodal.analog_loop")
        deeperExitScope = operation;
      if (!deeperBreak && operation->getName().getStringRef() == "nodal.analog_break")
        deeperBreak = operation;
    });
    if (!deeperExitScope || !deeperBreak)
      return fail("generated event deeper-exit mutation anchor missing");
    mlir::Operation *scopeExit = deeperBreak->clone();
    scopeExit->setAttr(
        "statement_id",
        mlir::StringAttr::get(&context, "GeneratedEvent.event_0.loop_2.scope_break_mutation"));
    deeperExitScope->getRegion(0).front().push_back(scopeExit);
    std::string deeperExitRejected = "sentinel";
    llvm::raw_string_ostream deeperExitRejectedStream(deeperExitRejected);
    if (mlir::succeeded(nodal::emitBackend(*deeperExitModule, nodal::BackendKind::VerilogA,
                                           deeperExitRejectedStream)))
      return fail("unsupported deeper generated loop exit passed backend verification");
    deeperExitRejectedStream.flush();
    if (deeperExitRejected != "sentinel")
      return fail("unsupported deeper generated loop exit published partial output");

    auto conditionalScopeBreakModule = parse(context, generatedStorageSource);
    if (!conditionalScopeBreakModule)
      return fail("generated event conditional-scope break mutation fixture did not parse");
    mlir::Operation *conditionalScopeBreakConditional = nullptr;
    mlir::Operation *conditionalScopeBreakDirectScope = nullptr;
    mlir::Operation *conditionalScopeBreakOp = nullptr;
    conditionalScopeBreakModule->walk([&](mlir::Operation *operation) {
      if (operation->getName().getStringRef() == "nodal.analog_if" && operation->getParentOp() &&
          operation->getParentOp()->getName().getStringRef() == "nodal.analog_loop")
        conditionalScopeBreakConditional = operation;
      if (!conditionalScopeBreakDirectScope &&
          operation->getName().getStringRef() == "nodal.analog_scope" && operation->getParentOp() &&
          operation->getParentOp()->getName().getStringRef() == "nodal.analog_loop")
        conditionalScopeBreakDirectScope = operation;
      if (!conditionalScopeBreakOp && operation->getName().getStringRef() == "nodal.analog_break")
        conditionalScopeBreakOp = operation;
    });
    if (!conditionalScopeBreakConditional || !conditionalScopeBreakDirectScope ||
        !conditionalScopeBreakOp)
      return fail("generated event conditional-scope break mutation anchor missing");
    mlir::Operation *conditionalBreakScope = conditionalScopeBreakDirectScope->clone();
    conditionalBreakScope->setAttr(
        "scope_id",
        mlir::StringAttr::get(&context,
                              "GeneratedEvent.event_0.loop_2.conditional_break_scope_mutation"));
    conditionalScopeBreakConditional->getRegion(0).front().back().getRegion(0).front().push_back(
        conditionalBreakScope);
    conditionalScopeBreakOp->moveBefore(&conditionalBreakScope->getRegion(0).front(),
                                        conditionalBreakScope->getRegion(0).front().end());
    conditionalScopeBreakDirectScope->erase();
    std::string conditionalScopeBreakOutput;
    llvm::raw_string_ostream conditionalScopeBreakStream(conditionalScopeBreakOutput);
    if (mlir::failed(nodal::emitBackend(*conditionalScopeBreakModule, nodal::BackendKind::VerilogA,
                                        conditionalScopeBreakStream)))
      return fail("generated event conditional-arm scope break failed backend verification");
    conditionalScopeBreakStream.flush();
    auto conditionalBreakElse = conditionalScopeBreakOutput.find("else begin");
    auto conditionalBreakAssignment = conditionalScopeBreakOutput.find(
        "event_GeneratedEvent_state = "
        "(event_GeneratedEvent_event_0_loop_2_scope_0_assignment_read_0 + 9.0);",
        conditionalBreakElse);
    auto conditionalBreakExit =
        conditionalScopeBreakOutput.find("_break = 1;", conditionalBreakAssignment);
    auto conditionalBreakContinue =
        conditionalScopeBreakOutput.find("_continue = 1;", conditionalBreakExit);
    if (conditionalBreakElse == std::string::npos ||
        conditionalBreakAssignment == std::string::npos ||
        conditionalBreakExit == std::string::npos ||
        conditionalBreakContinue == std::string::npos ||
        conditionalBreakAssignment < conditionalBreakElse ||
        conditionalBreakExit < conditionalBreakAssignment ||
        conditionalBreakExit > conditionalBreakContinue ||
        mlir::failed(nodal::reparseBackendTarget(conditionalScopeBreakOutput, *configuration)))
      return fail("generated event conditional-arm scope break lost its direct target topology");

    conditionalScopeBreakOp->setAttr("owner",
                                     mlir::StringAttr::get(&context, "ForgedGeneratedEvent"));
    std::string forgedConditionalScopeBreakRejected = "sentinel";
    llvm::raw_string_ostream forgedConditionalScopeBreakRejectedStream(
        forgedConditionalScopeBreakRejected);
    if (mlir::succeeded(nodal::emitBackend(*conditionalScopeBreakModule,
                                           nodal::BackendKind::VerilogA,
                                           forgedConditionalScopeBreakRejectedStream)))
      return fail("forged generated loop conditional-arm scope break passed backend "
                  "verification");
    forgedConditionalScopeBreakRejectedStream.flush();
    if (forgedConditionalScopeBreakRejected != "sentinel")
      return fail("forged generated loop conditional-arm scope break published partial output");
    conditionalScopeBreakOp->setAttr("owner", mlir::StringAttr::get(&context, "GeneratedEvent"));

    mlir::Operation *duplicateConditionalScopeBreak = conditionalScopeBreakOp->clone();
    duplicateConditionalScopeBreak->setAttr(
        "statement_id",
        mlir::StringAttr::get(
            &context, "GeneratedEvent.event_0.loop_2.duplicate_conditional_scope_break_mutation"));
    conditionalBreakScope->getRegion(0).front().push_back(duplicateConditionalScopeBreak);
    std::string duplicateConditionalScopeBreakRejected = "sentinel";
    llvm::raw_string_ostream duplicateConditionalScopeBreakRejectedStream(
        duplicateConditionalScopeBreakRejected);
    if (mlir::succeeded(nodal::emitBackend(*conditionalScopeBreakModule,
                                           nodal::BackendKind::VerilogA,
                                           duplicateConditionalScopeBreakRejectedStream)))
      return fail("duplicate generated loop conditional-arm scope break passed backend "
                  "verification");
    duplicateConditionalScopeBreakRejectedStream.flush();
    if (duplicateConditionalScopeBreakRejected != "sentinel")
      return fail("duplicate generated loop conditional-arm scope break published partial output");
    duplicateConditionalScopeBreak->erase();

    mlir::Operation *deeperConditionalBreakScope = conditionalBreakScope->clone();
    deeperConditionalBreakScope->setAttr(
        "scope_id",
        mlir::StringAttr::get(
            &context, "GeneratedEvent.event_0.loop_2.deeper_conditional_break_scope_mutation"));
    deeperConditionalBreakScope->getRegion(0).front().clear();
    conditionalBreakScope->getBlock()->getOperations().insert(
        mlir::Block::iterator(conditionalBreakScope), deeperConditionalBreakScope);
    conditionalBreakScope->moveBefore(&deeperConditionalBreakScope->getRegion(0).front(),
                                      deeperConditionalBreakScope->getRegion(0).front().end());
    std::string deeperConditionalBreakScopeRejected = "sentinel";
    llvm::raw_string_ostream deeperConditionalBreakScopeRejectedStream(
        deeperConditionalBreakScopeRejected);
    if (mlir::succeeded(nodal::emitBackend(*conditionalScopeBreakModule,
                                           nodal::BackendKind::VerilogA,
                                           deeperConditionalBreakScopeRejectedStream)))
      return fail("unsupported deeper generated loop conditional-arm break scope passed backend "
                  "verification");
    deeperConditionalBreakScopeRejectedStream.flush();
    if (deeperConditionalBreakScopeRejected != "sentinel")
      return fail("unsupported deeper generated loop conditional-arm break scope published "
                  "partial output");

    auto nestedScopeModule = parse(context, generatedStorageSource);
    if (!nestedScopeModule)
      return fail("generated event nested-scope mutation fixture did not parse");
    mlir::Operation *scopeConditional = nullptr;
    mlir::Operation *directScope = nullptr;
    mlir::Operation *scopeLoop = nullptr;
    mlir::Operation *scopeCasePrototype = nullptr;
    mlir::Operation *scopeBreak = nullptr;
    mlir::Operation *scopeContinue = nullptr;
    nestedScopeModule->walk([&](mlir::Operation *operation) {
      if (operation->getName().getStringRef() == "nodal.analog_if" && operation->getParentOp() &&
          operation->getParentOp()->getName().getStringRef() == "nodal.analog_loop")
        scopeConditional = operation;
      if (!scopeLoop && operation->getName().getStringRef() == "nodal.analog_loop")
        scopeLoop = operation;
      if (!scopeCasePrototype && operation->getName().getStringRef() == "nodal.analog_case")
        scopeCasePrototype = operation;
      if (!scopeBreak && operation->getName().getStringRef() == "nodal.analog_break")
        scopeBreak = operation;
      if (!scopeContinue && operation->getName().getStringRef() == "nodal.analog_continue")
        scopeContinue = operation;
      if (!directScope && operation->getName().getStringRef() == "nodal.analog_scope" &&
          operation->getParentOp() &&
          operation->getParentOp()->getName().getStringRef() == "nodal.analog_loop")
        directScope = operation;
    });
    if (!scopeConditional || !scopeLoop || !scopeCasePrototype || !directScope || !scopeBreak ||
        !scopeContinue)
      return fail("generated event nested-scope mutation anchor missing");
    mlir::Operation *nestedScope = directScope->clone();
    nestedScope->setAttr(
        "scope_id",
        mlir::StringAttr::get(&context, "GeneratedEvent.event_0.loop_2.nested_scope_mutation"));
    scopeConditional->getRegion(0).front().back().getRegion(0).front().push_back(nestedScope);
    scopeContinue->moveBefore(&nestedScope->getRegion(0).front(),
                              nestedScope->getRegion(0).front().end());
    directScope->erase();
    std::string nestedScopeOutput;
    llvm::raw_string_ostream nestedScopeStream(nestedScopeOutput);
    if (mlir::failed(nodal::emitBackend(*nestedScopeModule, nodal::BackendKind::VerilogA,
                                        nestedScopeStream)))
      return fail("generated event conditional-arm scope failed backend verification");
    nestedScopeStream.flush();
    auto conditionalScopeStart = nestedScopeOutput.find("else begin");
    auto conditionalScopeAssignment = nestedScopeOutput.find(
        "event_GeneratedEvent_state = "
        "(event_GeneratedEvent_event_0_loop_2_scope_0_assignment_read_0 + 9.0);");
    auto conditionalScopeContinue =
        nestedScopeOutput.find("_continue = 1;", conditionalScopeAssignment);
    auto conditionalScopeBreak = nestedScopeOutput.find("_break = 1;", conditionalScopeContinue);
    if (conditionalScopeStart == std::string::npos ||
        conditionalScopeAssignment == std::string::npos ||
        conditionalScopeContinue == std::string::npos ||
        conditionalScopeBreak == std::string::npos ||
        conditionalScopeAssignment < conditionalScopeStart ||
        conditionalScopeContinue < conditionalScopeAssignment ||
        conditionalScopeContinue > conditionalScopeBreak ||
        mlir::failed(nodal::reparseBackendTarget(nestedScopeOutput, *configuration)))
      return fail("generated event conditional-arm scope continue lost its direct target topology");

    scopeContinue->setAttr("owner", mlir::StringAttr::get(&context, "ForgedGeneratedEvent"));
    std::string forgedConditionalScopeExitRejected = "sentinel";
    llvm::raw_string_ostream forgedConditionalScopeExitRejectedStream(
        forgedConditionalScopeExitRejected);
    if (mlir::succeeded(nodal::emitBackend(*nestedScopeModule, nodal::BackendKind::VerilogA,
                                           forgedConditionalScopeExitRejectedStream)))
      return fail("forged generated loop conditional-arm scope continue passed backend "
                  "verification");
    forgedConditionalScopeExitRejectedStream.flush();
    if (forgedConditionalScopeExitRejected != "sentinel")
      return fail("forged generated loop conditional-arm scope continue published partial "
                  "output");
    scopeContinue->setAttr("owner", mlir::StringAttr::get(&context, "GeneratedEvent"));

    mlir::Operation *duplicateScopeContinue = scopeContinue->clone();
    duplicateScopeContinue->setAttr(
        "statement_id",
        mlir::StringAttr::get(&context,
                              "GeneratedEvent.event_0.loop_2.duplicate_scope_continue_mutation"));
    nestedScope->getRegion(0).front().push_back(duplicateScopeContinue);
    std::string duplicateConditionalScopeExitRejected = "sentinel";
    llvm::raw_string_ostream duplicateConditionalScopeExitRejectedStream(
        duplicateConditionalScopeExitRejected);
    if (mlir::succeeded(nodal::emitBackend(*nestedScopeModule, nodal::BackendKind::VerilogA,
                                           duplicateConditionalScopeExitRejectedStream)))
      return fail("duplicate generated loop conditional-arm scope continue passed backend "
                  "verification");
    duplicateConditionalScopeExitRejectedStream.flush();
    if (duplicateConditionalScopeExitRejected != "sentinel")
      return fail("duplicate generated loop conditional-arm scope continue published partial "
                  "output");
    duplicateScopeContinue->erase();

    mlir::Operation *deeperConditionalScope = nestedScope->clone();
    deeperConditionalScope->setAttr(
        "scope_id",
        mlir::StringAttr::get(&context,
                              "GeneratedEvent.event_0.loop_2.deeper_conditional_scope_mutation"));
    deeperConditionalScope->getRegion(0).front().clear();
    nestedScope->getBlock()->getOperations().insert(mlir::Block::iterator(nestedScope),
                                                    deeperConditionalScope);
    nestedScope->moveBefore(&deeperConditionalScope->getRegion(0).front(),
                            deeperConditionalScope->getRegion(0).front().end());
    std::string deeperConditionalScopeRejected = "sentinel";
    llvm::raw_string_ostream deeperConditionalScopeRejectedStream(deeperConditionalScopeRejected);
    if (mlir::succeeded(nodal::emitBackend(*nestedScopeModule, nodal::BackendKind::VerilogA,
                                           deeperConditionalScopeRejectedStream)))
      return fail("unsupported deeper generated loop conditional-arm scope passed backend "
                  "verification");
    deeperConditionalScopeRejectedStream.flush();
    if (deeperConditionalScopeRejected != "sentinel")
      return fail("unsupported deeper generated loop conditional-arm scope published partial "
                  "output");
    nestedScope->moveBefore(deeperConditionalScope);
    deeperConditionalScope->erase();
    scopeContinue->moveBefore(scopeBreak);

    mlir::Operation *scopeCase = scopeCasePrototype->clone();
    retagStructuredClone(scopeCase, ".scope_case_mutation", 6);
    scopeLoop->getRegion(0).front().push_back(scopeCase);
    mlir::Operation *caseScope = nestedScope->clone();
    retagStructuredClone(caseScope, ".case_scope_mutation", 8);
    scopeCase->getRegion(0).front().back().getRegion(0).front().push_back(caseScope);
    scopeBreak->moveBefore(&caseScope->getRegion(0).front(), caseScope->getRegion(0).front().end());
    nestedScope->erase();
    std::string caseScopeOutput;
    llvm::raw_string_ostream caseScopeStream(caseScopeOutput);
    if (mlir::failed(
            nodal::emitBackend(*nestedScopeModule, nodal::BackendKind::VerilogA, caseScopeStream)))
      return fail("generated event case-arm scope failed backend verification");
    caseScopeStream.flush();
    auto caseScopeAssignment = caseScopeOutput.find(
        "event_GeneratedEvent_state = "
        "(event_GeneratedEvent_event_0_loop_2_scope_0_assignment_read_0_case_scope_mutation + "
        "9.0);");
    auto caseScopeStart = caseScopeOutput.rfind("default: begin", caseScopeAssignment);
    auto caseScopeBreak = caseScopeOutput.find("_break = 1;", caseScopeAssignment);
    auto caseScopeEnd = caseScopeOutput.find("endcase", caseScopeAssignment);
    if (caseScopeStart == std::string::npos || caseScopeEnd == std::string::npos ||
        caseScopeAssignment == std::string::npos || caseScopeBreak == std::string::npos ||
        caseScopeAssignment < caseScopeStart || caseScopeBreak < caseScopeAssignment ||
        caseScopeBreak > caseScopeEnd ||
        mlir::failed(nodal::reparseBackendTarget(caseScopeOutput, *configuration)))
      return fail("generated event case-arm scope break lost its direct target topology");

    scopeBreak->setAttr("owner", mlir::StringAttr::get(&context, "ForgedGeneratedEvent"));
    std::string forgedScopeExitRejected = "sentinel";
    llvm::raw_string_ostream forgedScopeExitRejectedStream(forgedScopeExitRejected);
    if (mlir::succeeded(nodal::emitBackend(*nestedScopeModule, nodal::BackendKind::VerilogA,
                                           forgedScopeExitRejectedStream)))
      return fail("forged generated loop case-arm scope break passed backend verification");
    forgedScopeExitRejectedStream.flush();
    if (forgedScopeExitRejected != "sentinel")
      return fail("forged generated loop case-arm scope break published partial output");
    scopeBreak->setAttr("owner", mlir::StringAttr::get(&context, "GeneratedEvent"));

    mlir::Operation *duplicateScopeBreak = scopeBreak->clone();
    duplicateScopeBreak->setAttr(
        "statement_id",
        mlir::StringAttr::get(&context,
                              "GeneratedEvent.event_0.loop_2.duplicate_scope_break_mutation"));
    caseScope->getRegion(0).front().push_back(duplicateScopeBreak);
    std::string duplicateScopeExitRejected = "sentinel";
    llvm::raw_string_ostream duplicateScopeExitRejectedStream(duplicateScopeExitRejected);
    if (mlir::succeeded(nodal::emitBackend(*nestedScopeModule, nodal::BackendKind::VerilogA,
                                           duplicateScopeExitRejectedStream)))
      return fail("duplicate generated loop case-arm scope break passed backend verification");
    duplicateScopeExitRejectedStream.flush();
    if (duplicateScopeExitRejected != "sentinel")
      return fail("duplicate generated loop case-arm scope break published partial output");
    duplicateScopeBreak->erase();

    mlir::Operation *caseScopeContinue = scopeContinue->clone();
    caseScopeContinue->setAttr(
        "statement_id",
        mlir::StringAttr::get(&context,
                              "GeneratedEvent.event_0.loop_2.case_scope_continue_mutation"));
    scopeBreak->erase();
    caseScope->getRegion(0).front().push_back(caseScopeContinue);
    std::string caseScopeContinueOutput;
    llvm::raw_string_ostream caseScopeContinueStream(caseScopeContinueOutput);
    if (mlir::failed(nodal::emitBackend(*nestedScopeModule, nodal::BackendKind::VerilogA,
                                        caseScopeContinueStream)))
      return fail("generated event case-arm scope continue failed backend verification");
    caseScopeContinueStream.flush();
    auto caseScopeContinueAssignment = caseScopeContinueOutput.find(
        "event_GeneratedEvent_state = "
        "(event_GeneratedEvent_event_0_loop_2_scope_0_assignment_read_0_case_scope_mutation + "
        "9.0);");
    auto caseScopeContinueStart =
        caseScopeContinueOutput.rfind("default: begin", caseScopeContinueAssignment);
    auto caseScopeContinueExit =
        caseScopeContinueOutput.find("_continue = 1;", caseScopeContinueAssignment);
    auto caseScopeContinueEnd =
        caseScopeContinueOutput.find("endcase", caseScopeContinueAssignment);
    if (caseScopeContinueStart == std::string::npos || caseScopeContinueEnd == std::string::npos ||
        caseScopeContinueAssignment == std::string::npos ||
        caseScopeContinueExit == std::string::npos ||
        caseScopeContinueAssignment < caseScopeContinueStart ||
        caseScopeContinueExit < caseScopeContinueAssignment ||
        caseScopeContinueExit > caseScopeContinueEnd ||
        mlir::failed(nodal::reparseBackendTarget(caseScopeContinueOutput, *configuration)))
      return fail("generated event case-arm scope continue lost its direct target topology");

    caseScopeContinue->setAttr("owner", mlir::StringAttr::get(&context, "ForgedGeneratedEvent"));
    std::string forgedCaseScopeContinueRejected = "sentinel";
    llvm::raw_string_ostream forgedCaseScopeContinueRejectedStream(forgedCaseScopeContinueRejected);
    if (mlir::succeeded(nodal::emitBackend(*nestedScopeModule, nodal::BackendKind::VerilogA,
                                           forgedCaseScopeContinueRejectedStream)))
      return fail("forged generated loop case-arm scope continue passed backend verification");
    forgedCaseScopeContinueRejectedStream.flush();
    if (forgedCaseScopeContinueRejected != "sentinel")
      return fail("forged generated loop case-arm scope continue published partial output");
    caseScopeContinue->setAttr("owner", mlir::StringAttr::get(&context, "GeneratedEvent"));

    mlir::Operation *duplicateCaseScopeContinue = caseScopeContinue->clone();
    duplicateCaseScopeContinue->setAttr(
        "statement_id",
        mlir::StringAttr::get(
            &context, "GeneratedEvent.event_0.loop_2.duplicate_case_scope_continue_mutation"));
    caseScope->getRegion(0).front().push_back(duplicateCaseScopeContinue);
    std::string duplicateCaseScopeContinueRejected = "sentinel";
    llvm::raw_string_ostream duplicateCaseScopeContinueRejectedStream(
        duplicateCaseScopeContinueRejected);
    if (mlir::succeeded(nodal::emitBackend(*nestedScopeModule, nodal::BackendKind::VerilogA,
                                           duplicateCaseScopeContinueRejectedStream)))
      return fail("duplicate generated loop case-arm scope continue passed backend verification");
    duplicateCaseScopeContinueRejectedStream.flush();
    if (duplicateCaseScopeContinueRejected != "sentinel")
      return fail("duplicate generated loop case-arm scope continue published partial output");
    duplicateCaseScopeContinue->erase();

    mlir::Operation *deeperCaseScope = caseScope->clone();
    deeperCaseScope->setAttr(
        "scope_id", mlir::StringAttr::get(
                        &context, "GeneratedEvent.event_0.loop_2.deeper_case_scope_mutation"));
    deeperCaseScope->getRegion(0).front().clear();
    caseScope->getBlock()->getOperations().insert(mlir::Block::iterator(caseScope),
                                                  deeperCaseScope);
    caseScope->moveBefore(&deeperCaseScope->getRegion(0).front(),
                          deeperCaseScope->getRegion(0).front().end());
    std::string deeperCaseScopeRejected = "sentinel";
    llvm::raw_string_ostream deeperCaseScopeRejectedStream(deeperCaseScopeRejected);
    if (mlir::succeeded(nodal::emitBackend(*nestedScopeModule, nodal::BackendKind::VerilogA,
                                           deeperCaseScopeRejectedStream)))
      return fail("unsupported deeper generated loop case-arm scope passed backend verification");
    deeperCaseScopeRejectedStream.flush();
    if (deeperCaseScopeRejected != "sentinel")
      return fail("unsupported deeper generated loop case-arm scope published partial output");
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
