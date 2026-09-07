#include "nodal/Backend/AnalogEventBackend.h"

#include "llvm/ADT/StringRef.h"
#include "llvm/Support/raw_ostream.h"

#include <initializer_list>

int main() {
  for (llvm::StringRef call :
       {"white_noise(0, \"zero\")", "white_noise((power * 1e-18), \"same<+report//label\")",
        "white_noise((V(p, n) * V(p, n)), \"runtime\")",
        "flicker_noise(power, -1, \"negative exponent\")",
        "flicker_noise(sqrt(4), 0.5, \"fractional\")", "noise_table('{0, 1}, \"single point\")",
        "noise_table('{100, 2, 1, 4}, \"unsorted\")"}) {
    if (mlir::failed(nodal::reparseAnalogNoiseCall(call))) {
      llvm::errs() << "rejected valid noise call: " << call << '\n';
      return 1;
    }
  }
  for (llvm::StringRef call :
       {"white_noise(1)", "white_noise(1, \"\")", "white_noise(1, 2, \"extra\")",
        "flicker_noise(1, \"missing exponent\")", "flicker_noise(1, 2, 3, \"extra\")",
        "noise_table('{}, \"empty\")", "noise_table('{1, 2, 3}, \"odd\")",
        "noise_table({1, 2}, \"missing apostrophe\")", "noise_table(\"file.dat\", \"unsupported\")",
        "white_noise($random(), \"injected\")",
        "white_noise(white_noise(1, \"inner\"), \"nested\")", "white_noise(1, \"bad\\label\")",
        "white_noise(1, \"new\nline\")", "white_noise(1, \"ok\"); $finish;",
        "white_noise(1e999, \"nonfinite\")", "white_noise(1, \"ok\") trailing",
        "not_noise(1, \"unknown\")"}) {
    if (mlir::succeeded(nodal::reparseAnalogNoiseCall(call))) {
      llvm::errs() << "accepted malformed noise call: " << call << '\n';
      return 1;
    }
  }
  llvm::outs() << "Increment 39 noise reparse: 24 cases passed\n";
  return 0;
}
