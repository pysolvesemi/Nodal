Warning: truncated output (original token count: 97262)
Total output lines: 5908

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

constexpr llvm::StringLiteral kNestedGeneratedNodeArrayModule = R"mlir(
module attributes {
  nodal.backend.check_profile = "release",
  nodal.backend.materialization = "safe-inline",
  nodal.backend.naming = "semantic",
  nodal.backend.profile = "verilog-a",
  nodal.backend.shaped_layout = "scalar-or-flat",
  nodal.target.profile = "analog"
} {
  "nodal.module"() <{metadata = {root = true}, sym_name = "NestedGeneratedNodeArray"}> ({
  ^bb0:
    "nodal.generate"() <{induction = "generate_0_index", lower = 1 : i64, upper = 8 : i64, step = 2 : i64, region_id = "NestedGeneratedNodeArray.generate_0", induction_path = "NestedGeneratedNodeArray.generate_0.index", metadata = {maximum_trip_count = 4 : i64}}> ({
    ^bb0:
      "nodal.generate"() <{induction = "generate_1_index", lower = 2 : i64, upper = 11 : i64, step = 3 : i64, region_id = "NestedGeneratedNodeArray.generate_0.generate_1", induction_path = "NestedGeneratedNodeArray.generate_0.generate_1.index", metadata = {maximum_trip_count = 3 : i64}}> ({
      ^bb0:
        %tap = "nodal.node"() <{name = "nestedTap", source_path = "NestedGeneratedNodeArray.nestedTap", generated_owner = "NestedGeneratedNodeArray.generate_0.generate_1", generated_induction = "NestedGeneratedNodeArray.generate_0.generate_1.index", metadata = {allow_floating = true, declaration_kind = "analog-node", generated_owner = "NestedGeneratedNodeArray.generate_0.generate_1", induction = "NestedGeneratedNodeArray.generate_0.generate_1.index"}}> : () -> !nodal.terminal<"electrical">
      }) : () -> ()
    }) : () -> ()
  }) : () -> ()
}
)mlir";

constexpr llvm::StringLiteral kNestedGeneratedNodeEquationModule = R"mlir(
module attributes {
  nodal.backend.check_profile = "release",
  nodal.backend.materialization = "safe-inline",
  nodal.backend.naming = "semantic",
  nodal.backend.profile = "verilog-a",
  nodal.backend.shaped_layout = "scalar-or-flat",
  nodal.target.profile = "analog"
} {
  "nodal.module"() <{metadata = {root = true}, sym_name = "NestedGeneratedNodeEquation"}> ({
  ^bb0:
    %p = "nodal.terminal"() <{metadata = {declaration_kind = "analog-inout"}, name = "p"}> : () -> !nodal.terminal<"electrical">
    "nodal.generate"() <{induction = "generate_0_index", lower = 1 : i64, upper = 8 : i64, step = 2 : i64, region_id = "NestedGeneratedNodeEquation.generate_0", induction_path = "NestedGeneratedNodeEquation.generate_0.index", metadata = {maximum_trip_count = 4 : i64}}> ({
    ^bb0:
      "nodal.generate"() <{induction = "generate_1_index", lower = 2 : i64, upper = 11 : i64, step = 3 : i64, region_id = "NestedGeneratedNodeEquation.generate_0.generate_1", induction_path = "NestedGeneratedNodeEquation.generate_0.generate_1.index", metadata = {maximum_trip_count = 3 : i64}}> ({
      ^bb0:
        %tap = "nodal.node"() <{name = "nestedTap", source_path = "NestedGeneratedNodeEquation.nestedTap", generated_owner = "NestedGeneratedNodeEquation.generate_0.generate_1", generated_induction = "NestedGeneratedNodeEquation.generate_0.generate_1.index", metadata = {allow_floating = true, declaration_kind = "analog-node", generated_owner = "NestedGeneratedNodeEquation.generate_0.generate_1", induction = "NestedGeneratedNodeEquation.generate_0.generate_1.index"}}> : () -> !nodal.terminal<"electrical">
        %branch = "nodal.branch"(%tap, %p) <{metadata = {generated_owner = "NestedGeneratedNodeEquation.generate_0.generate_1", generated_induction = "NestedGeneratedNodeEquation.generate_0.generate_1.index"}}> : (!nodal.terminal<"electrical">, !nodal.terminal<"electrical">) -> !nodal.branch<"electrical">
        "nodal.analog"() <{generated_owner = "NestedGeneratedNodeEquation.generate_0.generate_1", generated_induction = "NestedGeneratedNodeEquation.generate_0.generate_1.index", metadata = {}}> ({
        ^bb0:
          %voltage = "nodal.access"(%branch) <{kind = "potential", metadata = {generated_owner = "NestedGeneratedNodeEquation.generate_0.generate_1", generated_induction = "NestedGeneratedNodeEquation.generate_0.generate_1.index"}}> : (!nodal.branch<"electrical">) -> f64
          %resistance = "nodal.real_literal"() <{value = 1000.0 : f64, metadata = {}}> : () -> f64
          %current = "nodal.analog_div"(%voltage, %resistance) <{metadata = {}}> : (f64, f64) -> f64
          "nodal.contribute"(%branch, %current) <{kind = "flow", generated_owner = "NestedGeneratedNodeEquation.generate_0.generate_1", generated_induction = "NestedGeneratedNodeEquation.generate_0.generate_1.index", metadata = {}}> : (!nodal.branch<"electrical">, f64) -> ()
        }) : () -> ()
      }) : () -> ()
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

constexpr llvm::StringLiteral kGeneratedNodeDdtContributionModule = R"mlir(
module attributes {
  nodal.backend.check_profile = "release",
  nodal.backend.materialization = "safe-inline",
  nodal.backend.naming = "semantic",
  nodal.backend.profile = "verilog-a",
  nodal.backend.shaped_layout = "scalar-or-flat",
  nodal.target.profile = "analog"
} {
  "nodal.module"() <{metadata = {root = true}, sym_name = "GeneratedNodeDdtContribution"}> ({
  ^bb0:
    %p = "nodal.terminal"() <{metadata = {declaration_kind = "analog-inout"}, name = "p"}> : () -> !nodal.terminal<"electrical">
    "nodal.generate"() <{induction = "generate_0_index", lower = 1 : i64, upper = 4 : i64, step = 1 : i64, region_id = "GeneratedNodeDdtContribution.generate_0", induction_path = "GeneratedNodeDdtContribution.generate_0.index", metadata = {maximum_trip_count = 3 : i64}}> ({
    ^bb0:
      %tap = "nodal.node"() <{name = "tap", source_path = "GeneratedNodeDdtContribution.tap", generated_owner = "GeneratedNodeDdtContribution.generate_0", generated_induction = "GeneratedNodeDdtContribution.generate_0.index", metadata = {allow_floating = true, declaration_kind = "analog-node", generated_owner = "GeneratedNodeDdtContribution.generate_0", induction = "GeneratedNodeDdtContribution.generate_0.index"}}> : () -> !nodal.terminal<"electrical">
      %branch = "nodal.branch"(%tap, %p) <{metadata = {generated_owner = "GeneratedNodeDdtContribution.generate_0", generated_induction = "GeneratedNodeDdtContribution.generate_0.index"}}> : (!nodal.terminal<"electrical">, !nodal.terminal<"electrical">) -> !nodal.branch<"electrical">
      "nodal.analog"() <{generated_owner = "GeneratedNodeDdtContribution.generate_0", generated_induction = "GeneratedNodeDdtContribution.generate_0.index", metadata = {}}> ({
      ^bb0:
        %voltage = "nodal.access"(%branch) <{kind = "potential", metadata = {generated_owner = "GeneratedNodeDdtContribution.generate_0", generated_induction = "GeneratedNodeDdtContribution.generate_0.index"}}> : (!nodal.branch<"electrical">) -> f64
        %derivative = "nodal.analog_ddt"(%voltage) <{analyses = ["ac", "dc", "initialization", "noise", "operating-point", "transient"], context = "contribution", generated_owner = "GeneratedNodeDdtContribution.generate_0", generated_induction = "GeneratedNodeDdtContribution.generate_0.index", initialization = "none", input_dimension = "1", metadata = {identity = "derivative"}, operator_contract = "increment35", operator_id = "GeneratedNodeDdtContribution.derivative", owner = "GeneratedNodeDdtContribution", result_dimension = "time^-1"}> : (f64) -> f64
        "nodal.contribute"(%branch, %derivative) <{kind = "flow", generated_owner = "GeneratedNodeDdtContribution.generate_0", generated_induction = "GeneratedNodeDdtContribution.generate_0.index", metadata = {}}> : (!nodal.branch<"electrical">, f64) -> ()
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

constexpr llvm::StringLiteral kCompoundGeneratedNodeContributionModule = R"mlir(
module attributes {
  nodal.backend.check_profile = "release",
  nodal.backend.materialization = "safe-inline",
  nodal.backend.naming = "semantic",
  nodal.backend.profile = "verilog-a",
  nodal.backend.shaped_layout = "scalar-or-flat",
  nodal.target.profile = "analog"
} {
  "nodal.module"() <{metadata = {root = true}, sym_name = "CompoundGeneratedNodeContribution"}> ({
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
    %zero = "nodal.const_literal"() <{metadata = {source_path = "CompoundGeneratedNodeContribution.generate_0.lower"}, spelling = "0", value = 0 : i64}> : () -> i64
    %count = "nodal.const_parameter_ref"() <{metadata = {source_path = "CompoundGeneratedNodeContribution.count"}, parameter = @COUNT}> : () -> i64
    %extra = "nodal.const_parameter_ref"() <{metadata = {source_path = "CompoundGeneratedNodeContribution.extra"}, parameter = @EXTRA}> : () -> i64
    %upper = "nodal.const_expr"(%count, %extra) <{metadata = {source_path = "CompoundGeneratedNodeContribution.generate_0.upper"}, operator_name = "add"}> : (i64, i64) -> i64
    %one = "nodal.const_literal"() <{metadata = {source_path = "CompoundGeneratedNodeContribution.generate_0.step"}, spelling = "1", value = 1 : i64}> : () -> i64
    %p = "nodal.terminal"() <{metadata = {declaration_kind = "analog-inout"}, name = "p"}> : () -> !nodal.terminal<"electrical">
    "nodal.generate"(%zero, %upper, %one) <{induction = "generate_0_index", region_id = "CompoundGeneratedNodeContribution.generate_0", induction_path = "CompoundGeneratedNodeContribution.generate_0.index", metadata = {maximum_trip_count = 6 : i64}}> ({
    ^bb0:
      %tap = "nodal.node"() <{name = "tap", source_path = "CompoundGeneratedNodeContribution.tap", generated_owner = "CompoundGeneratedNodeContribution.generate_0", generated_induction = "CompoundGeneratedNodeContribution.generate_0.index", metadata = {allow_floating = true, declaration_kind = "analog-node", generated_owner = "CompoundGeneratedNodeContribution.generate_0", induction = "CompoundGeneratedNodeContribution.generate_0.index"}}> : () -> !nodal.terminal<"electrical">
      %return_tap = "nodal.node"() <{name = "returnTap", source_path = "CompoundGeneratedNodeContribution.returnTap", generated_owner = "CompoundGeneratedNodeContribution.generate_0", generated_induction = "CompoundGeneratedNodeContribution.generate_0.index", metadata = {allow_floating = true, declaration_kind = "analog-node", generated_owner = "CompoundGeneratedNodeContribution.generate_0", induction = "CompoundGeneratedNodeContribution.generate_0.index"}}> : () -> !nodal.terminal<"electrical">
      %tap_branch = "nodal.branch"(%tap, %p) <{metadata = {generated_owner = "CompoundGeneratedNodeContribution.generate_0", generated_induction = "CompoundGeneratedNodeContribution.generate_0.index"}}> : (!nodal.terminal<"electrical">, !nodal.terminal<"electrical">) -> !nodal.branch<"electrical">
      %return_tap_branch = "nodal.branch"(%return_tap, %p) <{metadata = {generated_owner = "CompoundGeneratedNodeContribution.generate_0", generated_induction = "CompoundGeneratedNodeContribution.generate_0.index"}}> : (!nodal.terminal<"electrical">, !nodal.terminal<"electrical">) -> !nodal.branch<"electrical">
      "nodal.analog"() <{generated_owner = "CompoundGeneratedNodeContribution.generate_0", generated_induction = "CompoundGeneratedNodeContribution.generate_0.index", metadata = {}}> ({
      ^bb0:
        %one_volt = "nodal.real_literal"() <{value = 1.0 : f64, metadata = {}}> : () -> f64
        "nodal.contribute"(%tap_branch, %one_volt) <{kind = "potential", generated_owner = "CompoundGeneratedNodeContribution.generate_0", generated_induction = "CompoundGeneratedNodeContribution.generate_0.index", metadata = {}}> : (!nodal.branch<"electrical">, f64) -> ()
        %two_volts = "nodal.real_literal"() <{value = 2.0 : f64, metadata = {}}> : () -> f64
        "nodal.contribute"(%return_tap_branch, %two_volts) <{kind = "potential", generated_owner = "CompoundGeneratedNodeContribution.generate_0", generated_induction = "CompoundGeneratedNodeContribution.generate_0.index", metadata = {}}> : (!nodal.branch<"electrical">, f64) -> ()
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
  llvm::err…47262 tokens truncated…0.scope_0\"}> ({\n"
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

  auto compoundGeneratedNodes = parse(context, kCompoundGeneratedNodeContributionModule);
  if (!compoundGeneratedNodes)
    return fail("could not parse the compound generated node contribution fixture");
  std::string compoundGeneratedNodeOutput;
  llvm::raw_string_ostream compoundGeneratedNodeStream(compoundGeneratedNodeOutput);
  if (mlir::failed(nodal::emitBackend(*compoundGeneratedNodes, nodal::BackendKind::VerilogA,
                                      compoundGeneratedNodeStream)))
    return fail("compound generated node contribution emission failed");
  compoundGeneratedNodeStream.flush();
  if (compoundGeneratedNodeOutput.find("  electrical tap[0:((COUNT + EXTRA) - 1)];\n"
                                       "  electrical returnTap[0:((COUNT + EXTRA) - 1)];\n") ==
          std::string::npos ||
      compoundGeneratedNodeOutput.find("generate_0_index < (COUNT + EXTRA)") == std::string::npos ||
      compoundGeneratedNodeOutput.find("V(tap[generate_0_index], p) <+ 1;\n") ==
          std::string::npos ||
      compoundGeneratedNodeOutput.find("V(returnTap[generate_0_index], p) <+ 2;\n") ==
          std::string::npos)
    return fail("compound generated node group lost symbolic arrays, loop, or equation order");
  if (mlir::failed(nodal::reparseBackendTarget(compoundGeneratedNodeOutput, *configuration)))
    return fail("compound generated node contribution failed independent target reparse");
  std::string repeatedCompoundGeneratedNodeOutput;
  llvm::raw_string_ostream repeatedCompoundGeneratedNodeStream(repeatedCompoundGeneratedNodeOutput);
  if (mlir::failed(nodal::emitBackend(*compoundGeneratedNodes, nodal::BackendKind::VerilogA,
                                      repeatedCompoundGeneratedNodeStream)))
    return fail("compound generated node contribution repeated emission failed");
  repeatedCompoundGeneratedNodeStream.flush();
  if (compoundGeneratedNodeOutput != repeatedCompoundGeneratedNodeOutput)
    return fail("compound generated node contribution emission is nondeterministic");
  for (const auto &mutation :
       {std::make_pair(
            "name = \"returnTap\", source_path = \"CompoundGeneratedNodeContribution.returnTap\", "
            "generated_owner = \"CompoundGeneratedNodeContribution.generate_0\"",
            "name = \"returnTap\", source_path = \"CompoundGeneratedNodeContribution.returnTap\", "
            "generated_owner = \"forged\""),
        std::make_pair(
            "name = \"returnTap\", source_path = \"CompoundGeneratedNodeContribution.returnTap\", "
            "generated_owner = \"CompoundGeneratedNodeContribution.generate_0\", "
            "generated_induction = \"CompoundGeneratedNodeContribution.generate_0.index\"",
            "name = \"returnTap\", source_path = \"CompoundGeneratedNodeContribution.returnTap\", "
            "generated_owner = \"CompoundGeneratedNodeContribution.generate_0\", "
            "generated_induction = \"forged.index\""),
        std::make_pair(
            "name = \"returnTap\", source_path = \"CompoundGeneratedNodeContribution.returnTap\"",
            "name = \"tap\", source_path = \"CompoundGeneratedNodeContribution.returnTap\""),
        std::make_pair("source_path = \"CompoundGeneratedNodeContribution.returnTap\"",
                       "source_path = \"\""),
        std::make_pair("source_path = \"CompoundGeneratedNodeContribution.generate_0.step\"}, "
                       "spelling = \"1\", value = 1 : i64",
                       "source_path = \"CompoundGeneratedNodeContribution.generate_0.step\"}, "
                       "spelling = \"2\", value = 2 : i64")}) {
    std::string invalidSource = kCompoundGeneratedNodeContributionModule.str();
    auto position = invalidSource.find(mutation.first);
    if (position == std::string::npos)
      return fail("compound generated node source mutation anchor missing");
    invalidSource.replace(position, std::string(mutation.first).size(), mutation.second);
    auto invalidModule = parse(context, invalidSource);
    if (!invalidModule)
      continue;
    std::string rejectedCompoundNode = "sentinel";
    llvm::raw_string_ostream rejectedCompoundNodeStream(rejectedCompoundNode);
    if (mlir::succeeded(nodal::emitBackend(*invalidModule, nodal::BackendKind::VerilogA,
                                           rejectedCompoundNodeStream)))
      return fail("invalid compound generated node source passed backend verification");
    rejectedCompoundNodeStream.flush();
    if (rejectedCompoundNode != "sentinel")
      return fail("invalid compound generated node source published partial output");
  }
  std::string invalidCompoundNodeTarget = compoundGeneratedNodeOutput;
  auto compoundNodeBound = invalidCompoundNodeTarget.find("((COUNT + EXTRA) - 1)");
  if (compoundNodeBound == std::string::npos)
    return fail("compound generated node target mutation anchor missing");
  invalidCompoundNodeTarget.replace(compoundNodeBound, std::string("((COUNT + EXTRA) - 1)").size(),
                                    "((COUNT + MISSING) - 1)");
  if (mlir::succeeded(nodal::reparseBackendTarget(invalidCompoundNodeTarget, *configuration)))
    return fail("undeclared compound generated node bound passed independent reparse");

  std::string sharedGeneratedNodeSource = kCompoundGeneratedNodeContributionModule.str();
  auto sharedBranch =
      sharedGeneratedNodeSource.find("%return_tap_branch = \"nodal.branch\"(%return_tap, %p)");
  if (sharedBranch == std::string::npos)
    return fail("shared generated node branch mutation anchor missing");
  sharedGeneratedNodeSource.replace(
      sharedBranch, std::string("%return_tap_branch = \"nodal.branch\"(%return_tap, %p)").size(),
      "%return_tap_branch = \"nodal.branch\"(%tap, %p)");
  auto sharedGeneratedNode = parse(context, sharedGeneratedNodeSource);
  if (!sharedGeneratedNode)
    return fail("shared generated node branch fixture did not parse");
  std::string sharedGeneratedNodeOutput;
  llvm::raw_string_ostream sharedGeneratedNodeStream(sharedGeneratedNodeOutput);
  if (mlir::failed(nodal::emitBackend(*sharedGeneratedNode, nodal::BackendKind::VerilogA,
                                      sharedGeneratedNodeStream)))
    return fail("shared generated node branch emission failed");
  sharedGeneratedNodeStream.flush();
  if (sharedGeneratedNodeOutput.find("V(tap[generate_0_index], p) <+ 1;\n") == std::string::npos ||
      sharedGeneratedNodeOutput.find("V(tap[generate_0_index], p) <+ 2;\n") == std::string::npos ||
      mlir::failed(nodal::reparseBackendTarget(sharedGeneratedNodeOutput, *configuration)))
    return fail("shared generated node branches lost their ordered target equations");
  std::string repeatedSharedGeneratedNodeOutput;
  llvm::raw_string_ostream repeatedSharedGeneratedNodeStream(repeatedSharedGeneratedNodeOutput);
  if (mlir::failed(nodal::emitBackend(*sharedGeneratedNode, nodal::BackendKind::VerilogA,
                                      repeatedSharedGeneratedNodeStream)))
    return fail("shared generated node branch repeated emission failed");
  repeatedSharedGeneratedNodeStream.flush();
  if (sharedGeneratedNodeOutput != repeatedSharedGeneratedNodeOutput)
    return fail("shared generated node branch emission is nondeterministic");

  auto escapedGeneratedNode = parse(context, sharedGeneratedNodeSource);
  if (!escapedGeneratedNode)
    return fail("generated node escape mutation fixture did not parse");
  mlir::Operation *escapedBranchTemplate = nullptr;
  mlir::Operation *escapedNodeAnalog = nullptr;
  escapedGeneratedNode->walk([&](mlir::Operation *operation) {
    if (!escapedBranchTemplate && operation->getName().getStringRef() == "nodal.branch")
      escapedBranchTemplate = operation;
    if (!escapedNodeAnalog && operation->getName().getStringRef() == "nodal.analog")
      escapedNodeAnalog = operation;
  });
  if (!escapedBranchTemplate || !escapedNodeAnalog)
    return fail("generated node escape mutation anchor missing");
  escapedNodeAnalog->getRegion(0).front().push_back(escapedBranchTemplate->clone());
  std::string rejectedEscapedNode = "sentinel";
  llvm::raw_string_ostream rejectedEscapedNodeStream(rejectedEscapedNode);
  if (mlir::succeeded(nodal::emitBackend(*escapedGeneratedNode, nodal::BackendKind::VerilogA,
                                         rejectedEscapedNodeStream)))
    return fail("generated node non-branch escape passed backend verification");
  rejectedEscapedNodeStream.flush();
  if (rejectedEscapedNode != "sentinel")
    return fail("generated node non-branch escape published partial output");

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
